package com.ufi_axis.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `[NavAnim]` 里 `启动=` 的匹配规则（2026-11-03 第二次修正）。
 *
 * ## 为什么要测这个
 * 这个指标本身不影响任何行为，但它**直接决定下一步优化哪里**。它已经错过两次：
 * 1. 第一版只在 push 打点 ⇒ 用户最在意的**退出**根本没有数；
 * 2. 第二版只比时间戳 ⇒ pop 回宿主时跟"很久以前那次 push"相减，日志里出现
 *    676 / 833 / 1190 / 1711 / 2319ms 五个纯噪声值。
 *
 * 所以把它钉成断言：跨目的地必须为 0，超过窗口必须为 0，只有"同一目的地 + 1s 内"
 * 才是真的同一次导航。
 */
class UfiNavStartupProbeTest {

    private val t0 = 5_000_000L

    @Test
    fun sameDestinationWithinWindow_reportsNavigationTime() {
        ufiNavNoteDestinationChanged(Routes.MAIN, nowMs = t0)
        assertEquals(t0, ufiNavStartedAtFor(Routes.MAIN, nowMs = t0 + 100L))
    }

    @Test
    fun otherDestination_neverMatches() {
        // pop 回宿主时，退场中的二级页也会跑一次同样的帧统计 —— 它必须拿不到起点，
        // 否则就会和主界面那次导航相减（第一版 2319ms 噪声的来源）。
        ufiNavNoteDestinationChanged(Routes.MAIN, nowMs = t0)
        assertEquals(0L, ufiNavStartedAtFor(Routes.DETAIL_ABOUT, nowMs = t0 + 100L))
    }

    @Test
    fun staleNavigation_neverMatches() {
        ufiNavNoteDestinationChanged(Routes.MAIN, nowMs = t0)
        assertEquals(0L, ufiNavStartedAtFor(Routes.MAIN, nowMs = t0 + 1_001L))
    }

    @Test
    fun parameterisedRoute_matchesByDestinationIdentity() {
        // 配置目标路线的模板形态（`media/audio?path={path}`）必须能和具体串对上。
        ufiNavNoteDestinationChanged("media/audio?path={path}", nowMs = t0)
        assertTrue(
            ufiNavStartedAtFor("media/audio?path=%2Fsdcard%2Fa.mp3", nowMs = t0 + 50L) == t0
        )
    }
}
