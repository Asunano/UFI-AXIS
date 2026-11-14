package com.ufi_axis_core.util


/**
 * 智能循环节拍器（2026-10-07 实用功能批）：DataScheduler 各定时循环共用的 delay 决策器。
 *
 * ## 为什么需要它
 *
 * DataScheduler 有 14 条独立 `while(isActive){ work(); delay(fixed) }` 循环，节拍策略
 * 至少 4 种且各写各的：实时采集有「前端连接 + QoS 负载」自适应，SMS 缓存有「连接三元」，
 * 其余是固定 delay。**没有任何一条看网络吞吐量**——大流量下载时流量统计还是固定 15s，
 * 图表阶梯化；零流量深夜电池循环还按 30s 空转。[SmartLoop] 把节拍决策收敛成一处：
 *
 * ```
 * delay = clamp(base × loadFactor × trafficFactor × connFactor, minMs, maxMs)
 * ```
 *
 * - **base / min / max**：使用方声明（组件不内置业务值——每条循环的「多快算快」不同）
 * - **loadFactor**：QoS 负载（忙时放慢防打满，1.0~[LOAD_MAX]）
 * - **trafficFactor**（本组件新增的核心）：按吞吐量分档，
 *   零流量慢循环省电、大流量快循环降数据延迟（用户 2026-10-07 拍板的需求）
 * - **connFactor**：前端在线时略快（用户在看，数据新鲜度值钱）
 *
 * ## 使用方式
 *
 * ```kotlin
 * val loop = SmartLoop("traffic-stats", baseMs = 15_000, trafficSource = { latest })
 * while (isActive) {
 *     work()
 *     loop.delaySuspend()   // 代替 delay(fixed)
 * }
 * ```
 *
 * ## 可调参数
 *
 * 默认档位见 [SmartLoopTuning]，经 `AppSettings.getRawString` 读写（`smart_loop_*` 键组），
 * 后续做设置 UI 时直接改值即可，组件无感（每次计算都重新读 provider）。
 *
 * 放 core/common：依赖 GoformQoS/ShellQoS 与 TrafficRecord；scheduler 依赖 common，
 * 不产生循环依赖。
 */
class SmartLoop(
    /** 日志/统计标识（"traffic-stats" / "battery" / "plan-expiry" …） */
    val name: String,
    /** 基准间隔 ms：理想负载 + 中等流量时的节拍 */
    private val baseMs: Long,
    /** 下限：流量再大也不快于它（防 goform 被打满 / 防空转） */
    private val minMs: Long = baseMs / 2,
    /** 上限：再空闲也不慢于它（保底的新鲜度） */
    private val maxMs: Long = baseMs * 6,
    /**
     * 吞吐量源：返回 (rxSpeed, txSpeed) bytes/s；null = 尚无数据（按零流量处理）。
     * 传 Pair 而不是 TrafficRecord：本模块不依赖 core:database（TrafficRecord 在那），
     * 依赖方向必须保持 scheduler→common 单向。
     */
    private val trafficSource: () -> Pair<Long, Long>?,
    /** 负载源：0.0（闲）~1.0（满），QoS 占用率 */
    private val loadSource: () -> Double = { 0.0 },
    /** 前端是否在线（有 WS 连接） */
    private val frontConnected: () -> Boolean = { false },
    /** 调参源：每次计算现读（调参即时生效）；null = 用默认档位 */
    private val tuningProvider: () -> SmartLoopTuning.Tuning = { SmartLoopTuning.defaults() },
) {
    companion object {
        /** 零流量判定：低于此值（bytes/s）视为无流量，进慢档 */
        const val IDLE_BYTES_PER_SEC = 16 * 1024L
        /** 高流量第一档：超过此值进入 1.0 基准节拍 */
        const val ACTIVE_BYTES_PER_SEC = 1L * 1024 * 1024   // 1 MB/s
        /** 高流量第二档：超过此值进入 0.5 快档（大文件下载/测速中） */
        const val HEAVY_BYTES_PER_SEC = 20L * 1024 * 1024   // 20 MB/s
        /** 忙时放慢上限（与既有 getAdaptiveDelay 的 1.5 系数同一口径） */
        const val LOAD_MAX = 1.5
        /** 前端在线加快的幅度 */
        const val CONN_FAST = 0.7
    }

    /**
     * 计算下一轮等待 ms。不落盘、无状态——每次调用现算（provider 都是内存读，
     * 一次乘除法的代价可忽略，换来「调参立即生效」）。
     */
    fun nextDelayMs(now: Long = System.currentTimeMillis()): Long {
        val t = tuningProvider()
        // 一键回退：关掉智能节拍时所有循环退回固定 base（出问题不拆代码）
        if (!t.enabled) return baseMs
        // 1) 流量档位
        val totalSpeed = trafficSource()?.let { (rx, tx) -> rx + tx } ?: 0L
        val trafficFactor = when {
            totalSpeed >= t.heavyBytesPerSec -> 0.5    // 大流量：快档，降数据延迟
            totalSpeed >= t.activeBytesPerSec -> 1.0   // 中流量：基准
            totalSpeed >= t.idleBytesPerSec -> 1.2     // 零星流量：略慢
            else -> 1.5                                 // 零流量：慢档省电
        }
        // 2) QoS 负载（忙时放慢；负值/异常一律按 0 处理，不放大）
        val loadFactor = (1.0 + loadSource().coerceIn(0.0, 0.5)).coerceAtMost(LOAD_MAX)
        // 3) 前端在线
        val connFactor = if (frontConnected()) CONN_FAST else 1.0

        val raw = baseMs * trafficFactor * loadFactor * connFactor
        return raw.toLong().coerceIn(minMs, maxMs)
    }

    /** 挂起等待一轮。等价于 `delay(loop.nextDelayMs())` 的语法糖。 */
    suspend fun delaySuspend() {
        kotlinx.coroutines.delay(nextDelayMs())
    }
}

/**
 * SmartLoop 可调参数（2026-10-07：先给默认值，设置接口走 AppSettings 原始键，
 * 后续做 UI 直接读写这几个键；每次 [SmartLoop.nextDelayMs] 都重新读——调参即时生效）。
 *
 * 单位：bytes/s。阈值语义见 [SmartLoop] 伴生对象注释。
 */
object SmartLoopTuning {
    private const val KEY_IDLE = "smart_loop_idle_bps"
    private const val KEY_ACTIVE = "smart_loop_active_bps"
    private const val KEY_HEAVY = "smart_loop_heavy_bps"
    private const val KEY_ENABLED = "smart_loop_enabled"

    data class Tuning(
        val idleBytesPerSec: Long,
        val activeBytesPerSec: Long,
        val heavyBytesPerSec: Long,
        /** false = 所有 SmartLoop 退化为固定 baseMs（一键回退开关，出问题不拆代码） */
        val enabled: Boolean,
    )

    fun read(settings: com.ufi_axis_core.util.AppSettings): Tuning {
        return Tuning(
            idleBytesPerSec = settings.getRawString(KEY_IDLE)?.toLongOrNull() ?: SmartLoop.IDLE_BYTES_PER_SEC,
            activeBytesPerSec = settings.getRawString(KEY_ACTIVE)?.toLongOrNull() ?: SmartLoop.ACTIVE_BYTES_PER_SEC,
            heavyBytesPerSec = settings.getRawString(KEY_HEAVY)?.toLongOrNull() ?: SmartLoop.HEAVY_BYTES_PER_SEC,
            enabled = settings.getRawString(KEY_ENABLED)?.let { it != "false" } ?: true,
        )
    }

    fun write(settings: com.ufi_axis_core.util.AppSettings, t: Tuning) {
        settings.setRawString(KEY_IDLE, t.idleBytesPerSec.toString())
        settings.setRawString(KEY_ACTIVE, t.activeBytesPerSec.toString())
        settings.setRawString(KEY_HEAVY, t.heavyBytesPerSec.toString())
        settings.setRawString(KEY_ENABLED, t.enabled.toString())
    }

    /** 默认档位（无 settings / 键缺失时的取值） */
    fun defaults() = Tuning(
        idleBytesPerSec = SmartLoop.IDLE_BYTES_PER_SEC,
        activeBytesPerSec = SmartLoop.ACTIVE_BYTES_PER_SEC,
        heavyBytesPerSec = SmartLoop.HEAVY_BYTES_PER_SEC,
        enabled = true,
    )
}
