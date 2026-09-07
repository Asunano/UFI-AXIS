package com.ufi_axis_core.collector.at

import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * AT 指令通道 — 与 Unisoc modem 通信的唯一入口。
 *
 * 底层通道是 [ServiceCallAtExecutor]：Kotlin 直接调 `/system/bin/service`。
 * 它最终打的是展锐 HAL：
 * - API > 33 (Android 14+): `vendor.sprd.hardware.tool.IToolControl/default` 事务码 3
 * - API <= 33 (Android 13-): `vendor.sprd.hardware.log.ILogControl/default` 事务码 1
 *
 * 为什么走外部进程（这条约束**依然成立**，所以实现是 fork `service` 而不是
 * 在本进程内直连 HAL）：在 app 进程里直连会撞 `sprd_ipc_probe` 驱动竞态，需要 30s 防抖；
 * 交给独立进程执行就没有这个问题，也不需要 root。
 *
 * 2026-09-06：移除了旧的外部 `sendat` 二进制回落通道及其执行器（SendatExecutor 已删除），
 * 本类现在只认 service call 一条路。HAL 认不出来即视为本机 AT 通道不可用。
 */
class ATChannel {
    private val tag = "ATChannel"
    private val mutex = Mutex()

    enum class Platform { SPREADTRUM, QUALCOMM, UNKNOWN }

    private var platform: Platform = Platform.UNKNOWN
    private var connected: Boolean = false
    val isConnected: Boolean get() = connected

    /** 实际在用的通道；null = 未初始化或本机两套都不可用。 */
    private var transport: AtTransport? = null

    // ── 限流 & 退避 ──
    private val minCommandIntervalMs = 500L
    private var lastCommandTime: Long = 0L
    private var consecutiveFailures = 0
    private val maxConsecutiveFailures = 20
    @Volatile var isDisabled: Boolean = false

    val isLocked: Boolean get() = mutex.isLocked

    companion object {
        const val MUTEX_WAIT_TIMEOUT_MS = 10_000L
    }

    /**
     * 初始化 AT 通道：探测平台，选定可用的下发通道。
     *
     * 探测只问一次 servicemanager（`service check`，不碰 modem），代价极低。
     */
    suspend fun init(): Boolean {
        platform = detectPlatform()

        val serviceCall = ServiceCallAtExecutor()
        if (serviceCall.probe()) {
            transport = serviceCall
            connected = true
            AppLogger.i(tag, "AT channel ready ($platform, method=${serviceCall.name})")
            return true
        }

        AppLogger.w(tag, "service call 不可用，AT channel unavailable")
        connected = false
        return false
    }

    /**
     * 发送 AT 指令并返回响应文本
     */
    suspend fun sendCommand(command: String, timeoutMs: Long = 3000): String? = withTimeoutOrNull(MUTEX_WAIT_TIMEOUT_MS) {
        mutex.withLock {
            sendCommandInternal(command, timeoutMs)
        }
    } ?: run {
        AppLogger.w(tag, "sendCommand('$command') timed out waiting for mutex (${MUTEX_WAIT_TIMEOUT_MS}ms)")
        null
    }

    /**
     * 内部发送逻辑
     *
     * 限流策略：
     * - 最小命令间隔 500ms，防止连续 AT 压垮 modem
     * - 连续失败时指数退避
     * - 成功后重置退避计数
     */
    private suspend fun sendCommandInternal(command: String, timeoutMs: Long = 3000): String? {
        // ── 熔断 ──
        if (isDisabled) {
            AppLogger.w(tag, "AT channel disabled due to $consecutiveFailures consecutive failures")
            return null
        }

        val executor = transport
        if (executor == null) {
            AppLogger.w(tag, "AT transport 未初始化（init() 未调用或本机无可用通道）")
            return null
        }

        // ── 限流 ──
        val elapsed = System.currentTimeMillis() - lastCommandTime
        if (elapsed < minCommandIntervalMs) {
            kotlinx.coroutines.delay(minCommandIntervalMs - elapsed)
        }

        // ── 退避 ──
        if (consecutiveFailures > 0) {
            val backoff = (1L shl (consecutiveFailures - 1).coerceAtMost(6)) * 500L
                .coerceAtMost(30_000L)
            kotlinx.coroutines.delay(backoff)
        }

        try {
            val response = executor.sendCommand(command, slot = 0, timeoutMs = timeoutMs)

            if (response != null) {
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    isDisabled = true
                    AppLogger.e(tag, "AT channel DISABLED after $consecutiveFailures consecutive failures")
                }
            }

            return response
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            consecutiveFailures++
            if (consecutiveFailures >= maxConsecutiveFailures) {
                isDisabled = true
                AppLogger.e(tag, "AT channel DISABLED after $consecutiveFailures consecutive failures")
            }
            AppLogger.e(tag, "AT sendCommand failed (failure #$consecutiveFailures)", e)
            return null
        } finally {
            lastCommandTime = System.currentTimeMillis()
        }
    }

    fun getPlatformInfo(): Map<String, Any> = if (connected) {
        mapOf(
            "platform" to platform.name,
            "connected" to true,
            // 实际在用的通道名（当前恒为 service-call）——排查"AT 为什么不通"时第一眼要看的就是这个
            "method" to (transport?.name ?: "none")
        )
    } else {
        mapOf("connected" to false)
    }

    private fun detectPlatform(): Platform {
        // 直接读 /proc/cpuinfo，无需 shell
        return try {
            val cpuInfo = File("/proc/cpuinfo").readText()
            when {
                cpuInfo.contains("Spreadtrum", true) || cpuInfo.contains("sprd", true) -> Platform.SPREADTRUM
                cpuInfo.contains("Qualcomm", true) || cpuInfo.contains("qcom", true) -> Platform.QUALCOMM
                else -> Platform.UNKNOWN
            }
        } catch (_: Exception) {
            Platform.UNKNOWN
        }
    }

    fun stop() {
        connected = false
        consecutiveFailures = 0
        isDisabled = false
        transport?.reset()
    }
}
