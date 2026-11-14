package com.ufi_axis.ui.navigation

import com.ufi_axis.ui.theme.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 二级页转场「时长 / 总开关」的纯 JVM 单元测试。
 *
 * [ufiNavTransitionDurationMs] 是二级页转场**唯一的时长来源**：把
 * [ThemeManager.transitionDurationMs] 的原始值与系统「降低动效」合并成一个数，
 * `0` 即整套转场（平移 / 后退缩放 / scrim）一并关闭。
 *
 * 这一类回归可以不起模拟器就守住："用户设置是否原值透传""关闭档是不是真的整体关掉"
 * "未播种哨兵(-1)是否回落默认而不是误判为关闭"。
 */
class UfiNavTransitionDurationTest {

    /** 浮点断言容差。映射都是四则运算，`1e-4` 足够宽松。 */
    private val eps: Float = 1e-4f

    // ── 时长唯一来源 ──────────────────────────────────────────────────────────

    @Test
    fun duration_followsUserSetting() {
        // 用户设置在合法区间内必须原值透传，不许被吸附到某个固定档。
        assertEquals(150, ufiNavTransitionDurationMs(150, systemReduceMotion = false))
        assertEquals(380, ufiNavTransitionDurationMs(380, systemReduceMotion = false))
        assertEquals(600, ufiNavTransitionDurationMs(600, systemReduceMotion = false))
    }

    @Test
    fun duration_zeroMeansOffForEverything() {
        // 0 = 用户关闭转场。调用点只判 `<= 0`，于是平移 / 后退缩放 / scrim 一并不生效。
        assertEquals(0, ufiNavTransitionDurationMs(ThemeManager.TRANSITION_DURATION_OFF, false))
    }

    @Test
    fun duration_systemReduceMotionOverridesUserValue() {
        assertEquals(0, ufiNavTransitionDurationMs(380, systemReduceMotion = true))
        assertEquals(0, ufiNavTransitionDurationMs(600, systemReduceMotion = true))
    }

    @Test
    fun duration_unseededSentinelFallsBackToDefaultNotToOff() {
        // -1 是 ThemeManager「还没从 prefs 播种」的哨兵。
        // 既不能当成"关闭"（P2f 修过的静默降级），也不能 coerceAtLeast(1) 变成 1ms 瞬变。
        assertEquals(
            ThemeManager.TRANSITION_DURATION_DEFAULT_MS,
            ufiNavTransitionDurationMs(-1, systemReduceMotion = false),
        )
    }

    @Test
    fun duration_clampsAboveMax() {
        assertEquals(
            ThemeManager.TRANSITION_DURATION_MAX_MS,
            ufiNavTransitionDurationMs(5000, systemReduceMotion = false),
        )
    }
}
