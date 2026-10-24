package com.ufi_axis_core.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicInteger

/**
 * goform 的**隔离与熔断**（2026-10-17）。
 *
 * ## 它解决什么
 * 2026-10-17 实测：F50 **没插卡（无网络）**时启动，设备后台取模组数据变慢/挂住，
 * 结果 **连文件管理这类纯本地功能都打不开**。根因不是"文件管理依赖 goform"，而是：
 * 慢请求把客户端连接池占满 + 读路径没有整体超时 + 没有熔断（每个请求都重新等满超时）。
 *
 * 这个类只做三件事，但每一件都是"别再拖垮别的功能"的关键：
 *
 * 1. **[dispatcher]：goform 专用调度器**
 *    goform 用 `Dispatchers.IO.limitedParallelism(4)`，与文件/数据库/媒体那些通用 IO **隔离**。
 *    以前 goform 和文件操作抢同一个 IO 池，goform 一慢，文件操作也跟着排队。
 *
 * 2. **[READ_BUDGET_MS]：读路径整体预算**
 *    以前只有写路径有 `withTimeout(20s)`，读路径（`read` / `readOne`）完全没有 deadline，
 *    最坏要串登录链 + 查询 ≈ 50s。现在一次读最多 5s，超时按失败处理。
 *
 * 3. **熔断**
 *    连续 [FAILURE_THRESHOLD] 次失败/超时 → **OPEN [OPEN_DURATION_MS]**，期间读请求
 *    **一个字节都不发**、直接返回 null（调用方拿旧值/空值），冷却结束自动恢复。
 *    以前设备坏了，每个请求都重新去试、重新等满超时 —— 请求越多越慢。
 *
 * ## 为什么不在许可层做"抢不到就跳过"
 * `GoformQoS.kt:13-19` 记录过：那会把限流代价变成前端字段缺失（首屏卡片空白就是这么来的）。
 * 这里做的是"**快速失败 + 上层走旧值**"，语义不同。
 *
 * ## 熔断是"全局单例"而不是按 key
 * goform 只有一台设备、一个后台；它坏了就是全坏。按 cmd 分桶只会让每个 key 各熔断一次、
 * 总请求数反而更多。
 */
object GoformGuard {

    /** goform 专用调度器：与通用 IO 池隔离，避免 goform 慢拖累文件/数据库等操作。 */
    val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(PARALLELISM)

    /** goform 并发上限（与 `GoformQoS` 的查询许可同量级；真正的闸门仍在 QoS）。 */
    const val PARALLELISM = 4

    /** 一次读的**整体**预算（含等许可 + 登录 + 查询）。 */
    const val READ_BUDGET_MS = 5_000L

    /** 连续失败多少次后熔断。 */
    const val FAILURE_THRESHOLD = 3

    /** 熔断持续时长；到期自动恢复（没有半开探测：恢复后第一波请求本身就是探测，失败会立刻再次熔断）。 */
    const val OPEN_DURATION_MS = 30_000L

    private val consecutiveFailures = AtomicInteger(0)
    private val totalFastFails = AtomicInteger(0)
    private val totalTimeouts = AtomicInteger(0)

    /**
     * 健康状态变化回调（2026-10-17）。
     *
     * 装配层用它切换 `ResponseCache` 的"只服务旧值"闸门：熔断打开时不再为过期去调 goform，
     * 直接把上一次成功值返回给前端 —— 这样设备后台坏掉时，页面显示的是"旧数据"而不是空白/报错。
     *
     * 用回调而不是让本类依赖缓存：`GoformGuard` 在 `core:common`，缓存是更高层的实现，
     * 反向依赖会形成环。
     */
    @Volatile
    var onHealthChanged: ((healthy: Boolean) -> Unit)? = null

    @Volatile private var openUntil: Long = 0L
    @Volatile private var lastFailureAt: Long = 0L
    @Volatile private var lastSuccessAt: Long = 0L

    /**
     * 现在是否处于熔断（应快速失败）。
     *
     * @param now 便于测试注入
     */
    fun isOpen(now: Long = System.currentTimeMillis()): Boolean {
        val until = openUntil
        if (until == 0L) return false
        if (now < until) return true
        // 冷却结束：自动恢复。不重置失败计数 —— 恢复后的第一波请求若再失败，
        // 计数已经 ≥ 阈值，会立刻重新熔断（避免"每 30s 放一波慢请求"的锯齿）。
        openUntil = 0L
        return false
    }

    /** 记录一次失败。@return 记录后的连续失败次数 */
    fun recordFailure(now: Long = System.currentTimeMillis()): Int {
        lastFailureAt = now
        val count = consecutiveFailures.incrementAndGet()
        if (count >= FAILURE_THRESHOLD) {
            val wasOpen = openUntil != 0L
            openUntil = now + OPEN_DURATION_MS
            // 只在"从未熔断到熔断"这一跳通知一次，避免每次失败都回调
            if (!wasOpen) runCatching { onHealthChanged?.invoke(false) }
        }
        return count
    }

    /** 记录一次成功：清零失败计数并解除熔断。 */
    fun recordSuccess(now: Long = System.currentTimeMillis()) {
        lastSuccessAt = now
        val wasOpen = openUntil != 0L
        consecutiveFailures.set(0)
        openUntil = 0L
        if (wasOpen) runCatching { onHealthChanged?.invoke(true) }
    }

    /** 熔断期间被快速失败的请求数（诊断用）。 */
    fun recordFastFail() {
        totalFastFails.incrementAndGet()
    }

    /** 因超出读预算被判失败的次数（诊断用）。 */
    fun recordTimeout() {
        totalTimeouts.incrementAndGet()
    }

    /** 当前连续失败次数（测试/诊断用）。 */
    fun consecutiveFailureCount(): Int = consecutiveFailures.get()

    /** 供诊断接口/日志使用的快照。 */
    fun snapshot(): Map<String, Any> = mapOf(
        "open" to isOpen(),
        "consecutive_failures" to consecutiveFailures.get(),
        "open_until" to openUntil,
        "last_failure_at" to lastFailureAt,
        "last_success_at" to lastSuccessAt,
        "total_fast_fails" to totalFastFails.get(),
        "total_timeouts" to totalTimeouts.get(),
        "read_budget_ms" to READ_BUDGET_MS,
        "parallelism" to PARALLELISM
    )

    /** 仅供测试：把状态清回初始值。 */
    internal fun resetForTest() {
        consecutiveFailures.set(0)
        totalFastFails.set(0)
        totalTimeouts.set(0)
        openUntil = 0L
        lastFailureAt = 0L
        lastSuccessAt = 0L
        onHealthChanged = null
    }
}
