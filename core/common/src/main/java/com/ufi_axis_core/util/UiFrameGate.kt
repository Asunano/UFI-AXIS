package com.ufi_axis_core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「UI 正在做逐帧动画」闸门（2026-08-31 转场掉帧治理）。
 *
 * ## 为什么需要它
 * app 的实时数据靠 WebSocket 推送：signal ~3s、cpu/memory/traffic ~10s 各一条。
 * 每条推送都会写 `DashboardState`，而页面在根节点 `collectAsState()` 收这一个大状态对象 ——
 * 于是**一条推送 = 一次整屏重组**。稳态下没人察觉（120Hz 下偶尔一帧超预算无所谓），
 * 但正好落在页面转场那 300ms 里时，整屏重组会和「两页同时绘制 + 全屏模糊」叠加，
 * 直接吃掉整个帧预算 —— 表现就是「转场时随机卡一下、频率还挺高」。
 *
 * 这类掉帧靠优化转场本身是修不掉的：成本不在动画，而在动画期间插进来的那次重组。
 *
 * ## 语义
 * - UI 层在开始逐帧动画时 [begin]、结束时 [end]（引用计数，可嵌套 / 并发）；
 * - 数据层在写「非关键的高频状态」前查 [isBusy]，为 true 就把值攒起来，
 *   用 [awaitIdle] 等闸门放开后再一次性刷进状态流；
 * - **只用于可延迟的实时指标**（CPU/内存/流量/信号）。告警、通知、用户操作的反馈
 *   一律不得走这条延迟路径 —— 那些是「必须立刻可见」的语义。
 *
 * 延迟上限就是一次转场的时长（几百毫秒），因此不需要超时兜底：
 * [begin] / [end] 成对由 Compose 的 `DisposableEffect` / `LaunchedEffect` 保证，
 * 且计数为负时会被夹到 0（见 [end]），不会因为一次漏配对而永久卡住。
 */
object UiFrameGate {

    private val depth = AtomicInteger(0)
    private val _busy = MutableStateFlow(false)

    /** 是否正在做逐帧动画。数据层据此决定「立刻写」还是「攒着等」。 */
    val busy: StateFlow<Boolean> = _busy

    /** [busy] 的快照读法（无需协程）。 */
    val isBusy: Boolean get() = _busy.value

    /** 进入逐帧动画。可重入：多处动画同时进行时按引用计数累加。 */
    fun begin() {
        depth.incrementAndGet()
        _busy.value = true
    }

    /**
     * 退出逐帧动画。计数归零时放开闸门；计数被夹在 >= 0，漏配对也不会永久锁死。
     *
     * 2026-09-05（P3）：夹到 0 与递减必须是**同一次**原子操作。
     * 原实现是 `decrementAndGet()` 之后再独立 `depth.set(0)`，两步之间有窗口：
     * 若此刻另一处 [begin] 把计数抬到 1，`set(0)` 会把它抹掉并把 [_busy] 置 false
     * —— 动画还在跑闸门就放开了；而那个持有者随后调用 [end] 时又会把计数打成负数。
     * 改成 `updateAndGet` 后，"递减 + 夹 0" 是一次 CAS，读回的 `remaining`
     * 就是本次操作后的真实计数，据它决定是否放开闸门即可。
     */
    fun end() {
        val remaining = depth.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (remaining == 0) {
            _busy.value = false
        }
    }

    /** 挂起直到闸门放开（已经是空闲则立即返回）。 */
    suspend fun awaitIdle() {
        if (!_busy.value) return
        _busy.first { !it }
    }
}
