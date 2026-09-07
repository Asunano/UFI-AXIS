package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 胶囊归位判据的纯函数单测（[authorityCaughtUp] / [settledIndexOf]）。
 *
 * ## 守的是什么
 * 归位（滑块弹回整格 + 图标着色点亮目标格）有两条不变量：
 * 1. **时机**：只能在「权威下标已经反映了这次手势的结果」之后执行 —— 判据是
 *    `round(进度) == latestIndex`（[authorityCaughtUp]）。否则会在 `mainTabIndex`
 *    还是旧格时把滑块往回拽，真机表现为横滑切页后「往回抽一下」。
 * 2. **目标**：恒取宿主下标（[settledIndexOf]），Pager 的连续进度永远不是第二个真相源 ——
 *    否则进过二级页面后进度停在旧值上，胶囊会永久锁死在旧格。
 *
 * 判据里最容易被"优化"掉的是 NaN 分支：它代表「宿主没挂载 / 从未发布过进度」
 * （`LocalUfiCapsuleSelectionProgress` 的默认值就是 `{ Float.NaN }`），
 * 此时等一致永远等不到，必须立刻放行。这里逐条钉死。
 */
class CapsuleSettleAuthorityTest {

    // ── authorityCaughtUp：什么时候可以归位 ──────────────────────────────────

    /** 进度已落在权威下标上：立刻放行（点击路径就是这一档，零额外等待）。 */
    @Test
    fun `进度与权威下标一致时立即放行`() {
        assertTrue(authorityCaughtUp(2f, 2))
    }

    /**
     * 手势 settle 的收尾帧：进度已经贴到目标整数（差值在 isScrolling 的 0.01 带内），
     * 取整后与权威一致，同样放行。
     */
    @Test
    fun `进度贴近整数且取整命中权威时放行`() {
        assertTrue(authorityCaughtUp(2.004f, 2))
        assertTrue(authorityCaughtUp(1.996f, 2))
    }

    /**
     * ★ 本次 bug 的核心：进度已到新格、而权威回吐还没到（仍是旧格）。
     *
     * 此时**不得**放行 —— 放行就会 `animateTo(旧格)`，那一下反向动画正是用户看到的"抽"。
     */
    @Test
    fun `进度已到新格但权威仍是旧格时不放行`() {
        assertFalse(authorityCaughtUp(1f, 0))
        assertFalse(authorityCaughtUp(0.98f, 0))
    }

    /** 滑动进行中（进度在两格之间）：取整不命中权威就不放行。 */
    @Test
    fun `滑动中途不放行`() {
        assertFalse(authorityCaughtUp(0.5f, 0))
        assertFalse(authorityCaughtUp(2.6f, 2))
    }

    /**
     * ★ 兜底红线：进度为 NaN（宿主未挂载 / 从未发布）必须**立刻**放行。
     *
     * 这一条缺失就会复活「进过二级页面后胶囊永久锁死在旧格子」：
     * NaN 永远不等于任何下标，等待会一直挂着，归位永远不发生。
     */
    @Test
    fun `进度为NaN时必须立即放行`() {
        assertTrue(authorityCaughtUp(Float.NaN, 0))
        assertTrue(authorityCaughtUp(Float.NaN, 4))
    }

    /** 无穷值同样属于"进度不可信"，但它取整不会等于任何合法下标，只能靠超时兜底 —— 这里固定住现状。 */
    @Test
    fun `无穷进度不会被误判为已追上`() {
        assertFalse(authorityCaughtUp(Float.POSITIVE_INFINITY, 4))
        assertFalse(authorityCaughtUp(Float.NEGATIVE_INFINITY, 0))
    }

    // ── settledIndexOf：归位到哪一格 ────────────────────────────────────────

    /** 正常范围内原样返回：目标恒等于宿主下标（唯一真相源）。 */
    @Test
    fun `归位目标恒取宿主下标`() {
        assertEquals(0, settledIndexOf(0, 4))
        assertEquals(3, settledIndexOf(3, 4))
        assertEquals(4, settledIndexOf(4, 4))
    }

    /** 状态恢复期宿主可能传入越界下标，必须夹回合法区间而不是抛异常 / 返回负数。 */
    @Test
    fun `越界下标被夹回合法区间`() {
        assertEquals(4, settledIndexOf(9, 4))
        assertEquals(0, settledIndexOf(-2, 4))
    }

    // ── settleNeedsConfirmWindow：这次归位要不要付 80ms 防抖窗 ────────────────

    /**
     * ★ 点击路径必须零延迟：当帧权威就已一致、且此前不在滑动/拖拽中。
     *
     * 这一条就是「即点即达」。点击时宿主会 `capsuleProgress.snapTo(目标)`，
     * 胶囊这边既没有 `isScrolling` 抖动、也没有陈旧的 `latestIndex` 可读，白等 80ms
     * 纯粹是延迟，真机观感就是用户说的「胶囊不跟手」。
     */
    @Test
    fun `点击路径不付防抖确认窗`() {
        assertFalse(settleNeedsConfirmWindow(followsMotion = false, authorityCaughtUpAtEntry = true))
    }

    /** 手势路径照旧全额支付：手势收尾时 isScrolling 会在整数带附近抖，少了它滑块会抽搐。 */
    @Test
    fun `手势收尾仍付防抖确认窗`() {
        assertTrue(settleNeedsConfirmWindow(followsMotion = true, authorityCaughtUpAtEntry = true))
        assertTrue(settleNeedsConfirmWindow(followsMotion = true, authorityCaughtUpAtEntry = false))
    }

    /**
     * 「等过权威」也算中途态：进入分支时权威还没追上，说明回吐仍在路上，
     * 与手势收尾同一类，防抖窗照付（保守方向出错，绝不会引入抽搐）。
     */
    @Test
    fun `等过权威的归位也付防抖确认窗`() {
        assertTrue(settleNeedsConfirmWindow(followsMotion = false, authorityCaughtUpAtEntry = false))
    }

    // ── indicatorSettleSpec：滑块与页面必须同一条时间轴 ──────────────────────

    /**
     * 滑块常规归位的 spec 必须是「用户设置的转场时长 + Standard 曲线」的 tween ——
     * 与 `MainNavGraph` 传给 `UfiPageSwitcher` 的那条逐字同源，两者才会同起同落。
     */
    @Test
    fun `滑块归位时长与页面转场同源`() {
        val spec = indicatorSettleSpec(380) as TweenSpec<Float>
        assertEquals(380, spec.durationMillis)
        assertEquals(0, spec.delay)
        assertTrue(
            "曲线必须是 UfiMotion.Easing.Standard（= FastOutSlowIn = CubicBezier(0.4,0,0.2,1)），" +
                "与页面转场同一条",
            spec.easing === UfiMotion.Easing.Standard
        )
    }

    /**
     * 「关闭转场」档（`navTransitionMs == 0`）下 tween 的时长必须仍然合法（≥1）。
     *
     * 与 `MainNavGraph` 的 `durationMillis = navTransitionMs.coerceAtLeast(1)` 逐字一致：
     * 那条路径下页面走的是「瞬移 + 极短淡入」，滑块跟着 1ms 到位即可，
     * 但**不能**把 0 直接塞进 tween（非法参数）。
     */
    @Test
    fun `关闭转场档位下时长仍然合法`() {
        val spec = indicatorSettleSpec(0) as TweenSpec<Float>
        assertEquals(1, spec.durationMillis)
    }
}
