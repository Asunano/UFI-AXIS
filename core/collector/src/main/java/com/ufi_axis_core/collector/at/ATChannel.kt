package com.ufi_axis_core.collector.at

import com.ufi_axis_core.devicespi.AtTransport
import com.ufi_axis_core.devicespi.CpuInfoPlatform
import com.ufi_axis_core.devicespi.ProbeEnv
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AT 指令通道 — 与 Unisoc modem 通信的唯一入口。
 *
 * 底层通道由**装配层注入**（[init] 的参数）：现在唯一的实现是
 * `SprdPlatform.atTransports()` 给出的 `ServiceCallAtExecutor`，Kotlin 直接调
 * `/system/bin/service`。它最终打的是展锐 HAL：
 * - API > 33 (Android 14+): `vendor.sprd.hardware.tool.IToolControl/default` 事务码 3
 * - API <= 33 (Android 13-): `vendor.sprd.hardware.log.ILogControl/default` 事务码 1
 *
 * 为什么走外部进程（这条约束**依然成立**，所以实现是 fork `service` 而不是
 * 在本进程内直连 HAL）：在 app 进程里直连会撞 `sprd_ipc_probe` 驱动竞态，需要 30s 防抖；
 * 交给独立进程执行就没有这个问题，也不需要 root。
 *
 * 2026-09-06：移除了旧的外部 `sendat` 二进制回落通道及其执行器（SendatExecutor 已删除），
 * 本类现在只认 service call 一条路。HAL 认不出来即视为本机 AT 通道不可用。
 *
 * 2026-09-24（阶段 4 批 F）：`AtTransport` 上移到 `:core:device-spi`、
 * `ServiceCallAtExecutor` 搬到 `:core:device-plugins` 的 `platform/sprd/`。
 * **本类留在 collector** —— 限流、退避、熔断是**我们的策略**，不是设备事实
 * （计划书 §11.7）。于是本类不再 `new` 任何具体实现：通道由 [init] 注入。
 *
 * 2026-09-24（阶段 4 的 4.6）：平台判定不再自己读 `/proc/cpuinfo` ——
 * 文件由装配层的 `ProbeEnvCollector` 读一次并放进 `ProbeEnv`，本类从 [init] 收下那一份、
 * 只做「串 → [Platform]」的映射（判据常量见 `CpuInfoPlatform`）。
 * 枚举与它的三个取值**一个没动**，理由见 [detectPlatform]。
 */
class ATChannel {
    private val tag = "ATChannel"
    private val mutex = Mutex()

    /**
     * 平台判定结果。
     *
     * ⚠ 这三个名字是**对外取值**：`getPlatformInfo()` 下发的是 `platform.name`
     * （大写），`/api/at/platform`、`/api/at/status`、`/api/dashboard` 与
     * `/api/device/...` 都在消费。**改名等于改对外 JSON**。
     */
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
     * 初始化 AT 通道：定平台，从注入的候选里选定可用的下发通道。
     *
     * 2026-09-24（阶段 4 的 4.6）：平台**不再自己读文件**，改为从注入的 [ProbeEnv] 派生
     * （见 [detectPlatform]）。本方法因此不再有任何本机文件 I/O，
     * 剩下的 I/O 只有候选通道自己的 `probe()`（`service check`，不碰 modem，代价极低）。
     *
     * @param transports 候选通道，**按优先级排列**（下标 0 优先）。由装配层从
     *   `DevicePlugin.platform(ctx).atTransports()` 取来 —— 本类**不许**自己 `new` 具体实现，
     *   那是设备/平台知识（阶段 4 批 F 起）。空列表 = 本机没有 AT 通道。
     * @param probeEnv 装配层在构造组件图时**采一次后共享**的那份指纹
     *   （`ProbeEnvCollector.collect()`，阶段 5 的 5.1）。本类只用它的
     *   [ProbeEnv.cpuInfoPlatform] 一项。
     *
     *   收整个 [ProbeEnv] 而不是只收一个 `String?`：这样签名本身就说明「用的是**那份共享的**
     *   指纹」，而不是某个调用方现场读出来的串 —— 后者正是 4.6 要消灭的第二份判据。
     *   参数是**必填**的：给个默认值就等于允许「忘了传 → 平台恒为 UNKNOWN」静默发生。
     */
    suspend fun init(transports: List<AtTransport>, probeEnv: ProbeEnv): Boolean {
        platform = detectPlatform(probeEnv)

        if (transports.isEmpty()) {
            AppLogger.w(tag, "未注入任何 AT transport，AT channel unavailable")
            connected = false
            return false
        }

        for (candidate in transports) {
            if (candidate.probe()) {
                transport = candidate
                connected = true
                AppLogger.i(tag, "AT channel ready ($platform, method=${candidate.name})")
                return true
            }
        }

        // 文案沿用改造前那一行：当前唯一的候选就是 service call，日志读起来与此前逐字一致。
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

    /**
     * 由共享指纹派生平台 —— 阶段 4 的 4.6。**只剩映射，没有 I/O。**
     *
     * 改造前本方法自己 `File("/proc/cpuinfo").readText()` 并内联两组 marker；现在文件由
     * `ProbeEnvCollector` 读（整个组件图一次）、marker 由 [CpuInfoPlatform] 定（全仓一份）。
     *
     * ## 三条刻意保留的东西
     *
     * 1. **[Platform] 的三个取值一个不少**。`QUALCOMM` 在本仓没有任何插件与之对应，
     *    但它是 `/api/at/platform` 与 `/api/at/status` 的 `platform` 字段的合法取值 ——
     *    删了就是把高通机器报成 `UNKNOWN`，丢掉「这台其实不是展锐」这条排障信息。
     * 2. **判定顺序**：先展锐、后高通、都不中为 `UNKNOWN`，与改造前逐字一致。
     * 3. **不许改成 `adapter.name`**。`PlatformAdapter.name` 说的是「**选中的插件**跑在什么
     *    平台上」，恒为 `SPREADTRUM`；本字段说的是「**这台机器**看起来是什么平台」。
     *    换成前者的话，把 F50 插件硬配到一台高通机器上时这里也会报 `SPREADTRUM`，
     *    正好把这个字段唯一的排障价值抹掉。
     *
     * ## 一处行为变更（4.6 的已知代价，已报）
     *
     * marker 合并取了**并集**，于是展锐一侧多了 `unisoc`（改造前只有插件打分那一侧带它）。
     * 结果：cpuinfo 里只写 `unisoc`、不写 `sprd` / `spreadtrum` 的机型上，
     * 本方法从 `UNKNOWN` 变成 `SPREADTRUM`。在用的 F50 本来就命中 `spreadtrum`，取值不变。
     *
     * 读不到 cpuinfo（`cpuInfoPlatform == null`）→ `UNKNOWN`，与改造前的 `catch` 分支同义。
     */
    private fun detectPlatform(probeEnv: ProbeEnv): Platform {
        val cpuInfo = probeEnv.cpuInfoPlatform
        return when {
            CpuInfoPlatform.isSpreadtrum(cpuInfo) -> Platform.SPREADTRUM
            CpuInfoPlatform.isQualcomm(cpuInfo) -> Platform.QUALCOMM
            else -> Platform.UNKNOWN
        }
    }

    fun stop() {
        connected = false
        consecutiveFailures = 0
        isDisabled = false
        transport?.reset()
    }
}
