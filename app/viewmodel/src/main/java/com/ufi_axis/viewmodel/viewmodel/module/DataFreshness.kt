package com.ufi_axis.viewmodel.module

/*
 * 「页面前台化时该不该重拉」的**数据新鲜度判据**（2026-09-05）。
 *
 * ## 为什么需要它（横滑落定后的重组风暴）
 * 底部 Tab 的宿主开了 `keepPagesAlive`，页面组合永不销毁，所以离屏页靠
 * `isUfiPageForeground()` 做门控 —— 它一翻转就重启两条副作用：
 * ```
 * DisposableEffect(lifecycleResumed, pageForeground, serviceEnabled) { startAutoRefresh(...) }
 * LaunchedEffect(lifecycleResumed, pageForeground, serviceEnabled)  { loadTrafficLimit(); refreshWifi() }
 * ```
 * `viewModelScope` 是 `Dispatchers.Main.immediate` + `CoroutineStart.DEFAULT`，主线程调用时
 * `isDispatchNeeded == false`，协程体**同步内联**开跑：于是 settle 的那一帧里就同步写了
 * 两次整页状态（`isLoading = true`），随后 0~500ms 内 3~5 个 REST 回包又各写一次。
 * 横滑落定时页面已静止，但胶囊滑块 / 图标着色还在归位（+80ms ~ +460ms 有可见运动），
 * 每一次整页重组都落在这段里 ⇒ 就是用户说的"顿一下"。点击路径这些写落在 t=0 首帧、
 * 且此刻眼睛没有可追踪的运动，所以感知不到。
 *
 * ## 两条治理原则（本文件只负责第 2 条）
 * 1. **有数据时不写 loading 态**：刷新本身不产生重组，只有回包带来真实字段变化才重组一次；
 * 2. **数据新鲜就别重拉**：前台化时若上次成功拉取还在新鲜窗口内，直接跳过这一轮请求。
 *
 * ## 为什么用 `SystemClock.elapsedRealtime()` 而不是 `System.currentTimeMillis()`
 * 后者可被用户 / NTP 随时改（往前跳会把陈旧数据判成新鲜，往后跳会让新鲜数据被判成过期，
 * 甚至算出负数年龄）。新鲜度是一段**时长**，必须用单调时钟。
 * 时间戳一律存在 ViewModel / Module 层（跨组合存活）—— 放在 composable 的 `remember` 里
 * 会在换页往复时丢失，等于没做。
 */

/**
 * 前台自动刷新周期，同时是**数据新鲜度窗口**的默认取值（ms）。
 *
 * 取值依据：`DashboardScreen` 的 10s 全量轮询就是产品认可的"数据最长可以旧多久"。
 * 新鲜度窗口取同一个数，语义才自洽 —— 若窗口比周期短，前台化时必然重拉（等于没做）；
 * 若比周期长，就会出现"轮询已经刷了、前台化却认为还不新鲜"的自相矛盾。
 * 公开（而非 `internal`）是为了让 `DashboardScreen` 直接复用，避免两处各写一遍 `10_000`。
 */
const val FOREGROUND_REFRESH_INTERVAL_MS: Long = 10_000L

/**
 * 前台化时「第一次请求还要等多久」（ms，纯函数，可单测）。
 *
 * `0` = 立刻请求（数据从未拉取过 / 已过期）；`> 0` = 数据仍新鲜，返回的是**剩余新鲜时长**，
 * 调用方 `delay(它)` 之后再进入常规轮询循环，这样定时器节奏与「上次成功时刻」对齐，
 * 既跳过了 settle 窗口里那次无谓的重拉，也不会让刷新间隔被拉长。
 *
 * @param lastSuccessElapsedMs 上次**成功**落地的 `SystemClock.elapsedRealtime()`；`0` = 从未成功。
 * @param nowElapsedMs         当前 `SystemClock.elapsedRealtime()`。
 * @param freshWindowMs        新鲜度窗口，默认 [FOREGROUND_REFRESH_INTERVAL_MS]。
 *   `<= 0` 视为"不启用新鲜度"，恒返回 0（立刻请求）。
 * @return 需要等待的毫秒数，恒 `>= 0`。
 */
fun foregroundRefreshDelayMs(
    lastSuccessElapsedMs: Long,
    nowElapsedMs: Long,
    freshWindowMs: Long = FOREGROUND_REFRESH_INTERVAL_MS,
): Long {
    if (lastSuccessElapsedMs <= 0L) return 0L
    if (freshWindowMs <= 0L) return 0L
    val age: Long = nowElapsedMs - lastSuccessElapsedMs
    // 负年龄只可能来自"时间戳不是同一个时钟量出来的"这类实现错误（elapsedRealtime 单调不回退）。
    // 保守按已过期处理：宁可多发一次请求，也不要因为一个坏时间戳把数据永久钉成"新鲜"。
    if (age < 0L) return 0L
    if (age >= freshWindowMs) return 0L
    return freshWindowMs - age
}

/**
 * 前台化时数据是否仍然新鲜（= [foregroundRefreshDelayMs] 还有剩余时长）。
 *
 * 与 [foregroundRefreshDelayMs] **同一份判据**，刻意用它实现而不是各写一遍比较：
 * 两处若慢慢长歪，就会出现"轮询以为要等、一次性加载以为该拉"的错位。
 */
fun isForegroundDataFresh(
    lastSuccessElapsedMs: Long,
    nowElapsedMs: Long,
    freshWindowMs: Long = FOREGROUND_REFRESH_INTERVAL_MS,
): Boolean = foregroundRefreshDelayMs(lastSuccessElapsedMs, nowElapsedMs, freshWindowMs) > 0L
