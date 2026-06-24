package com.ufi_axis_core.collector.at

import android.content.Context
import android.os.Build
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import com.ufi_axis_core.util.ShellQoS
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AT 指令通道 — 通过 Android service call (Binder IPC) 直接与 Unisoc modem 通信
 *
 * 无需外部二进制，根据 Android API 等级选择对应的 HIDL 接口：
 * - API > 33 (Android 14+): vendor.sprd.hardware.tool.IToolControl/default 事务码 3
 * - API <= 33 (Android 13-): vendor.sprd.hardware.log.ILogControl/default 事务码 1
 *
 * service call 输出为小端序 hex word，需解析为可读文本。
 */
class ATChannel(private val context: Context) {
    private val tag = "ATChannel"
    private val mutex = Mutex()

    enum class Platform { SPREADTRUM, QUALCOMM, UNKNOWN }

    private var platform: Platform = Platform.UNKNOWN
    private var connected: Boolean = false
    val isConnected: Boolean get() = connected

    // ── 限流 & 退避 ──
    // 最小命令间隔 500ms，防止连续 AT 命令压垮 modem
    private val minCommandIntervalMs = 500L
    private var lastCommandTime: Long = 0L
    // 连续失败计数（用于指数退避）
    private var consecutiveFailures = 0
    private val maxBackoffMs = 30_000L
    // 最大连续失败次数，超过后禁用 AT 通道，需外部重新 init()
    private val maxConsecutiveFailures = 20
    @Volatile var isDisabled: Boolean = false

    // ── Mutex 超时保护：防止 sendCommand/init 无限阻塞 ──
    val isLocked: Boolean get() = mutex.isLocked

    companion object {
        const val MUTEX_WAIT_TIMEOUT_MS = 10_000L   // sendCommand 等待 mutex 超时
    }

    // ── service call Binder IPC 防抖 ──
    // 每次 service call 可能触发 sprd_ipc_probe 驱动重新加载 /class/smem，
    // 在高频调用下（如每 3s 2 次）会触发 kobject_add_internal -EEXIST 内核 panic。
    // 最小 service call 间隔 15s，防止内核驱动竞态。
    private val minServiceCallIntervalMs = 15_000L
    private var lastServiceCallTime: Long = 0L

    /**
     * 初始化 AT 通道：探测设备平台，标记通道可用
     */
    suspend fun init(): Boolean {
        platform = detectPlatform()
        connected = true
        AppLogger.i(tag, "AT channel ready ($platform, API ${Build.VERSION.SDK_INT})")
        return true
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
     * 内部发送逻辑，不加锁。
     * init() 已持有 mutex，直接调用此方法；外部通过 sendCommand() 加锁调用。
     *
     * 根据 Android API 等级构造 service call 命令，通过 root shell 执行，
     * 然后将 hex word 输出解析为可读文本。
     *
     * 限流策略：
     * - 最小命令间隔 200ms，防止连续 AT 压垮 modem
     * - 连续失败时指数退避（1→2→4→8...最多 10s）
     * - 成功后重置退避计数
     */
    private suspend fun sendCommandInternal(command: String, timeoutMs: Long = 3000): String? {
        // ── 熔断：连续失败过多，禁用 AT 通道 ──
        if (isDisabled) {
            AppLogger.w(tag, "AT channel disabled due to $consecutiveFailures consecutive failures")
            return null
        }

        // ── 限流：确保命令间隔 ≥ minCommandIntervalMs ──
        val elapsed = System.currentTimeMillis() - lastCommandTime
        if (elapsed < minCommandIntervalMs) {
            kotlinx.coroutines.delay(minCommandIntervalMs - elapsed)
        }

        // ── service call 防抖：两次 Binder IPC ≥ 15s，防止 sprd_ipc_probe 驱动竞态 ──
        val scElapsed = System.currentTimeMillis() - lastServiceCallTime
        if (scElapsed < minServiceCallIntervalMs) {
            val waitMs = minServiceCallIntervalMs - scElapsed
            AppLogger.d(tag, "service call cooldown: waiting ${waitMs}ms (min interval=${minServiceCallIntervalMs}ms)")
            kotlinx.coroutines.delay(waitMs)
        }

        // ── 退避：连续失败时等待更长时间 ──
        if (consecutiveFailures > 0) {
            val backoff = (1L shl (consecutiveFailures - 1).coerceAtMost(6)) * 500L
                .coerceAtMost(maxBackoffMs)
            kotlinx.coroutines.delay(backoff)
        }

        AppLogger.at("TX", command)
        try {
            val shellCmd = buildServiceCallCommand(command, slot = 0)
            // 必须通过 ShellQoS 执行 — service call 会触发 sprd_ipc_probe 驱动，
            // 直接调用 ShellExecutor 绕过 root 信号量限流，并发时导致内核 panic
            val result = ShellQoS.executeAsRoot(shellCmd, timeoutMs + 500)
            val rawText = parseServiceCallHex(result.stdout)
            val response = parseATResponse(rawText)

            // 成功 → 重置退避计数
            consecutiveFailures = 0
            AppLogger.at("RX", command, response ?: "ERROR")
            return response
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消不递增失败计数，防止误触发熔断禁用 AT 通道
            throw e
        } catch (e: Exception) {
            consecutiveFailures++
            // ── 熔断：连续失败超过上限 → 禁用 AT 通道 ──
            if (consecutiveFailures >= maxConsecutiveFailures) {
                isDisabled = true
                AppLogger.e(tag, "AT channel DISABLED after $consecutiveFailures consecutive failures")
            }
            AppLogger.e(tag, "AT sendCommand failed (failure #$consecutiveFailures)", e)
            return null
        } finally {
            lastCommandTime = System.currentTimeMillis()
            lastServiceCallTime = System.currentTimeMillis()
        }
    }

    /**
     * 根据 Android API 等级构造 service call 命令字符串
     *
     * Android 14+ (API > 33): 使用 IToolControl 接口
     *   service call vendor.sprd.hardware.tool.IToolControl/default 3 i32 <phoneId> s16 "<at_command>"
     *
     * Android 13 及以下 (API <= 33): 使用 ILogControl 接口
     *   service call vendor.sprd.hardware.log.ILogControl/default 1 s16 "miscserver" s16 "sendAt <phoneId> <at_command>"
     */
    private fun buildServiceCallCommand(atCommand: String, slot: Int): String {
        val escaped = atCommand.replace("\"", "\\\"")
        return if (Build.VERSION.SDK_INT > 33) {
            // Android 14+: IToolControl.sendAtCmd(phoneId, atCommand)
            "service call vendor.sprd.hardware.tool.IToolControl/default 3 i32 $slot s16 \"$escaped\""
        } else {
            // Android 13-: ILogControl.sendCommand("miscserver", "sendAt <phoneId> <atCommand>")
            "service call vendor.sprd.hardware.log.ILogControl/default 1 " +
                "s16 \"miscserver\" s16 \"sendAt $slot $escaped\""
        }
    }

    /**
     * 解析 service call 输出的 hex word 为可读文本
     *
     * service call 输出格式类似:
     *   Result: Parcel(
     *     00000000    00000000 00000000 4b4f2b0a '...\n+OK'
     *   )
     *
     * 每个 8 字符 hex word 代表 4 字节小端序数据，
     * 按小端序拆解为 2 字节一组，转为 UTF-16LE 字符。
     */
    private fun parseServiceCallHex(raw: String): String {
        val hexWordPattern = Regex("\\b[0-9a-fA-F]{8}\\b")
        val sb = StringBuilder()
        for (line in raw.lines()) {
            // 跳过单引号后的内容（service call 输出中的 ASCII 预览部分）
            val truncated = line.substringBefore('\'', line)
            for (word in hexWordPattern.findAll(truncated)) {
                val hex = word.value
                if (hex.length != 8) continue
                // 小端序：bytes 排列为 [b3b4, b1b2] → 重组为 [b1, b2, b3, b4]
                val bytes = listOf(
                    hex.substring(6, 8), hex.substring(4, 6),
                    hex.substring(2, 4), hex.substring(0, 2)
                )
                for (i in 0 until 4 step 2) {
                    val lo = bytes[i].toIntOrNull(16) ?: continue
                    val hi = bytes[i + 1].toIntOrNull(16) ?: continue
                    val charCode = (hi shl 8) or lo
                    if (charCode in 32..126 || charCode == '\n'.code || charCode == '\r'.code) {
                        sb.append(charCode.toChar())
                    }
                }
            }
        }
        return sb.toString()
    }

    fun getPlatformInfo(): Map<String, Any> = if (connected) {
        mapOf("platform" to platform.name, "connected" to true, "method" to "service_call")
    } else {
        mapOf("connected" to false)
    }

    private suspend fun detectPlatform(): Platform {
        val cpuInfo = ShellQoS.executeAsRootCached("cat /proc/cpuinfo").stdout
        return when {
            cpuInfo.contains("Spreadtrum", true) || cpuInfo.contains("sprd", true) -> Platform.SPREADTRUM
            cpuInfo.contains("Qualcomm", true) || cpuInfo.contains("qcom", true) -> Platform.QUALCOMM
            else -> Platform.UNKNOWN
        }
    }

    private fun parseATResponse(raw: String): String? {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.filter { !it.startsWith("AT") && it != "OK" && it != "ERROR" }
            .joinToString("\n").ifEmpty { if (raw.contains("OK")) "OK" else null }
    }

    fun stop() {
        connected = false
        consecutiveFailures = 0
        isDisabled = false
    }
}
