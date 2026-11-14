package com.ufi_axis.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 「转场期暂缓发布」窗口的**时长下限**护栏（2026-11-03）。
 *
 * 2026-11-03 的日志复盘：动画本身满帧，但每次 `[NavAnim]` 都有一帧 16.6ms / 33.1ms 的
 * 最差帧，且时间点全部落在"进页后 ~300ms"。根因是 [MAX_DEFER_MS] 原来是 300ms，
 * 比**默认**二级页转场（380×1.2=456ms + 闸门尾巴 150ms）还短 —— 统一闸门每段转场
 * 都会在中途强制放行一次，数据正好砸在动画中段。
 *
 * 这个测试不依赖 `:app:ui`（viewmodel 不能反向依赖 UI 模块），所以把两端的最坏值
 * 写成常量：二级页转场最长 600ms × 1.2 = 720ms，`UfiNavFrameGate` 尾巴 150ms。
 * 改动其中任何一项时，这个断言会先失败，提醒同步 [MAX_DEFER_MS]。
 */
class TransitionGatedFlowDeferWindowTest {

    @Test
    fun maxDefer_coversSlowestTransitionPlusGateTail() {
        val slowestDetailMs = (600 * 1.2f).roundToInt() // ThemeManager 上限 × SHARED_AXIS_SLOWDOWN
        val frameGateTailMs = 150                     // UfiNavFrameGate / UfiPageSwitcherHost
        assertTrue(
            "MAX_DEFER_MS=$MAX_DEFER_MS 小于最慢转场($slowestDetailMs)+尾巴($frameGateTailMs)，" +
                "统一闸门会在动画中途强制放行 —— 这正是 2026-11-03 每次转场掉一帧的根因。",
            MAX_DEFER_MS >= slowestDetailMs + frameGateTailMs,
        )
    }

    @Test
    fun maxDefer_stillBounded() {
        // 上限不能无限大：可预测性返回可能被手指按住数秒，必须有一道兜底让状态恢复流动。
        assertTrue("MAX_DEFER_MS 过大，长按返回手势期间状态会长时间冻住", MAX_DEFER_MS <= 2_000L)
    }
}
