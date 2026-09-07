package com.ufi_axis.ui.navigation

import com.ufi_axis.ui.theme.ThemeManager
import com.ufi_axis.ui.theme.UfiMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二级页转场「离场页后退」的纯 JVM 单元测试。
 *
 * 与 `UfiPageTransitionTest` 同一套思路：把动画的数值映射做成纯函数
 * （[UfiNavRecedeProfile]），于是"落位后缩放/遮罩是否精确归零""关闭档是不是真的整套关掉"
 * 这类回归可以不起模拟器就守住。
 *
 * `p` 语义：本页自己的可见度进度，`0f` = 完全不可见（屏外），`1f` = 落位。
 *
 * ⚠ 2026-09-04 二次修订：原来断言 `cardScale` / `cornerFraction` / `shadowEnvelope` /
 * `cardElevation` / `cardCornerRadius` 的用例已**整组删除**，不是改成宽松断言 ——
 * 那五个映射连同「进场页当卡片浮起」一起被删掉了（进场页现在必须满屏铺满，
 * 理由见 `Navigation.kt` 文件头铁律 A）。留着断言等于给死代码上锁。
 */
class UfiNavRecedeProfileTest {

    /** 浮点断言容差。映射都是四则运算，`1e-4` 足够宽松。 */
    private val eps: Float = 1e-4f

    // ── 离场页后退缩放 ────────────────────────────────────────────────────────

    @Test
    fun recedingScale_returnsToOneAtRest() {
        // 落位必须精确回到 1f：否则静止页面会永久挂着 4% 的缩放，
        // 而这个 App 的 chrome 在动画容器之外，缩放残留会直接露出静止色带。
        assertEquals(1f, UfiNavRecedeProfile.recedingScale(1f), eps)
        assertEquals(UfiMotion.NavRecede.ScaleTo, UfiNavRecedeProfile.recedingScale(0f), eps)
    }

    @Test
    fun recedingScale_isLinearInProgress() {
        // 缩放必须是进度的线性插值 —— 任何额外的自有缓动都等于第二条动画曲线。
        val mid = (UfiMotion.NavRecede.ScaleTo + 1f) / 2f
        assertEquals(mid, UfiNavRecedeProfile.recedingScale(0.5f), eps)
    }

    @Test
    fun recedingScale_shrinksButStaysNearOne() {
        // 整屏量级的后退幅度必须很小：退太多会在进场页尚未覆盖的那一侧露出容器底色。
        assertTrue(UfiNavRecedeProfile.recedingScale(0f) < 1f)
        assertTrue(UfiNavRecedeProfile.recedingScale(0f) >= 0.94f)
    }

    // ── 离场页 scrim ──────────────────────────────────────────────────────────

    @Test
    fun scrimAlpha_isZeroAtRestAndPeaksWhenFullyReceded() {
        assertEquals(0f, UfiNavRecedeProfile.scrimAlpha(1f), eps)
        assertEquals(UfiMotion.NavRecede.ScrimAlpha, UfiNavRecedeProfile.scrimAlpha(0f), eps)
        assertEquals(UfiMotion.NavRecede.ScrimAlpha / 2f, UfiNavRecedeProfile.scrimAlpha(0.5f), eps)
    }

    // ── 界外输入（手势 seek / 过冲）不得外插 ──────────────────────────────────

    @Test
    fun outOfRangeProgress_isClamped() {
        assertEquals(
            UfiNavRecedeProfile.recedingScale(0f),
            UfiNavRecedeProfile.recedingScale(-0.5f),
            eps,
        )
        assertEquals(
            UfiNavRecedeProfile.recedingScale(1f),
            UfiNavRecedeProfile.recedingScale(1.5f),
            eps,
        )
        assertEquals(0f, UfiNavRecedeProfile.scrimAlpha(2f), eps)
        assertEquals(UfiMotion.NavRecede.ScrimAlpha, UfiNavRecedeProfile.scrimAlpha(-0.5f), eps)
    }

    @Test
    fun scaleAndAlpha_stayWithinRange() {
        var step = -5
        while (step <= 15) {
            val p = step / 10f
            assertTrue("p=$p scrim 越界", UfiNavRecedeProfile.scrimAlpha(p) in 0f..1f)
            assertTrue(
                "p=$p 后退缩放越界",
                UfiNavRecedeProfile.recedingScale(p) in UfiMotion.NavRecede.ScaleTo..1f,
            )
            step++
        }
    }

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
