package com.ufi_axis.ui.animation.page

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T03 · 六种内置转场策略的纯 JVM 单元测试。
 *
 * 之所以能不启动模拟器就断言动画行为，正是 T01 契约的核心收益：
 * [UfiPageTransition.layerAt] 是无状态、无副作用的纯函数，
 * [UfiPageLayer] 是纯数据类而非 Compose 的 `GraphicsLayerScope`。
 *
 * position 语义：`0f` 居中 / `+1f` 右侧一屏外 / `-1f` 左侧一屏外。
 */
class UfiPageTransitionTest {

    /** 浮点断言容差。所有映射都是简单四则运算，`1e-4` 已足够宽松。 */
    private val eps: Float = 1e-4f

    /** 容器宽度（px），与 [ctx] 保持同源，避免测试里散落魔法数字。 */
    private val containerWidth: Int = 1080

    /** 容器高度（px）。 */
    private val containerHeight: Int = 1920

    /** 标准测试上下文：1080×1920、density 1.0、LTR、离场页。 */
    private val ctx: UfiPageLayerContext = UfiPageLayerContext(
        containerSize = IntSize(containerWidth, containerHeight),
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        isIncoming = false,
    )

    @Before
    fun setUp() {
        // 触发内置策略装配。幂等，可被每个用例重复调用。
        registerBuiltInTransitions()
    }

    // ── 注册表 ────────────────────────────────────────────────────────────────

    @Test
    fun registry_containsExactlySixBuiltInTransitions() {
        // T01 的安全 Fade 兜底占用 id "fade"，会被 FadeTransition 原地覆盖，故总数仍是 6。
        assertEquals(6, UfiPageTransitions.all().size)
    }

    @Test
    fun registry_preservesRegistrationOrder() {
        val expectedIds: List<String> =
            listOf("fade", "slide", "scale", "rotate", "cube3d", "flip3d")
        assertEquals(expectedIds, UfiPageTransitions.all().map { it.id })
    }

    @Test
    fun registry_displayNamesAreAssigned() {
        val expectedNames: List<String> =
            listOf("渐入渐出", "滑动", "缩放", "旋转", "立方体3D", "翻转3D")
        assertEquals(expectedNames, UfiPageTransitions.all().map { it.displayName })
    }

    @Test
    fun registry_lateinitReferencesPointToRealImplementations() {
        assertEquals("fade", UfiPageTransitions.Fade.id)
        assertEquals("slide", UfiPageTransitions.Slide.id)
        assertEquals("scale", UfiPageTransitions.Scale.id)
        assertEquals("rotate", UfiPageTransitions.Rotate.id)
        assertEquals("cube3d", UfiPageTransitions.Cube3D.id)
        assertEquals("flip3d", UfiPageTransitions.Flip3D.id)
    }

    @Test
    fun byId_unknownIdFallsBackSafely() {
        // 脏数据 / 旧版本 id 不得崩溃，应回退到安全 Fade。
        assertEquals("fade", UfiPageTransitions.byId("no-such-transition").id)
        assertEquals("fade", UfiPageTransitions.byId(null).id)
    }

    // ── Fade ─────────────────────────────────────────────────────────────────

    @Test
    fun fade_atCenter_isFullyOpaqueAndUnscaled() {
        val layer: UfiPageLayer = FadeTransition.layerAt(0f, ctx)
        assertEquals(1f, layer.alpha, eps)
        assertEquals(1f, layer.scaleX, eps)
        assertEquals(1f, layer.scaleY, eps)
    }

    @Test
    fun fade_fadesOutFasterThanTravel() {
        // alpha = 1 - |p| * 1.6 → 在 |p| = 0.625 处已完全透明。
        assertEquals(0.2f, FadeTransition.layerAt(0.5f, ctx).alpha, eps)
        assertEquals(0f, FadeTransition.layerAt(0.625f, ctx).alpha, eps)
        assertEquals(0f, FadeTransition.layerAt(1f, ctx).alpha, eps)
    }

    @Test
    fun fade_scaleIsClampedForOffscreenPages() {
        // |p| > 1 时 scale 不得外插到 0.98 以下。
        assertEquals(0.98f, FadeTransition.layerAt(2f, ctx).scaleX, eps)
        assertEquals(0.98f, FadeTransition.layerAt(-2f, ctx).scaleX, eps)
    }

    // ── Fade 模糊交叉淡入（blurProfile） ──────────────────────────────────────

    @Test
    fun fade_blurProfile_isZeroAtRestAndOffscreen() {
        // 落定（position=0）与完全离屏（position=±1）必须清晰，静止页绝不残留模糊。
        assertEquals(0f, FadeTransition.blurProfile(0f, isIncoming = true), eps)
        assertEquals(0f, FadeTransition.blurProfile(1f, isIncoming = true), eps)
        assertEquals(0f, FadeTransition.blurProfile(-1f, isIncoming = false), eps)
    }

    @Test
    fun fade_blurProfile_peaksAtMidTransition() {
        // 行程中点（共享进度 t≈0.5）应取到峰值模糊 1.0。
        // 入页 d=0.5 → t=0.5；出页 d=0.5 → t=0.5，两者系数相同（模糊同步）。
        assertEquals(1f, FadeTransition.blurProfile(0.5f, isIncoming = true), eps)
        assertEquals(1f, FadeTransition.blurProfile(0.5f, isIncoming = false), eps)
    }

    @Test
    fun fade_blurProfile_releasesFasterThanItAttacks() {
        // 「加速释放」：2026-08-23 把释放段指数从 0.6f 改为 1.4f —— 原先 0.6f 是慢释放，
        // 与页面位移用的 Emphasized 曲线长尾叠加后，模糊比页面静止「慢一拍」，
        // 落定后还能看到一帧糊影。现在释放段必须比攻击段更陡。
        // 入页 d:1→0 映射到共享进度 t:0→1，取关于峰值 t=0.5 对称的两点：
        //   attack  (t=0.3, 即 d=0.7)：线性上升段，系数 = 0.3/0.5 = 0.6
        //   release (t=0.7, 即 d=0.3)：指数 1.4 释放段，系数 = 0.6^1.4 ≈ 0.489
        val attack = FadeTransition.blurProfile(0.7f, isIncoming = true)   // t=0.3 攻击侧
        val release = FadeTransition.blurProfile(0.3f, isIncoming = true)  // t=0.7 释放侧（对称）
        assertTrue(
            "释放段应比攻击段更陡（系数更小），当前 attack=$attack release=$release",
            release < attack
        )
        // 接近落定（d=0.1 ⇒ t=0.9）模糊应已基本散尽，且明显小于对称的起糊侧（t=0.1）。
        val nearSettle = FadeTransition.blurProfile(0.1f, isIncoming = true) // t=0.9 释放侧
        val earlyAttack = FadeTransition.blurProfile(0.9f, isIncoming = true) // t=0.1 攻击侧
        assertTrue(
            "接近落定时模糊应已基本散尽，当前 nearSettle=$nearSettle earlyAttack=$earlyAttack",
            nearSettle < earlyAttack
        )
        assertTrue("落定前一帧的残留模糊不应超过 0.15，否则仍会看到糊影", nearSettle < 0.15f)
    }

    @Test
    fun fade_blurProfile_incomingAndOutgoingShareProgress() {
        // 同一瞬间：入页在 d 处与出页在 (1-d) 处应算出相同系数（整屏同步起糊/变清）。
        for (d in listOf(0.2f, 0.35f, 0.5f, 0.7f, 0.85f)) {
            val incoming = FadeTransition.blurProfile(d, isIncoming = true)
            val outgoing = FadeTransition.blurProfile(1f - d, isIncoming = false)
            assertEquals("d=$d 时出入页模糊应同步", incoming, outgoing, eps)
        }
    }

    // ── Slide ────────────────────────────────────────────────────────────────

    @Test
    fun slide_translatesProportionallyToContainerWidth() {
        assertEquals(0.5f * containerWidth, SlideTransition.layerAt(0.5f, ctx).translationX, eps)
        assertEquals(-1f * containerWidth, SlideTransition.layerAt(-1f, ctx).translationX, eps)
        assertEquals(0f, SlideTransition.layerAt(0f, ctx).translationX, eps)
    }

    @Test
    fun slide_neverChangesOpacity() {
        assertEquals(1f, SlideTransition.layerAt(0f, ctx).alpha, eps)
        assertEquals(1f, SlideTransition.layerAt(1f, ctx).alpha, eps)
    }

    // ── Scale ────────────────────────────────────────────────────────────────

    @Test
    fun scale_atCenter_isUnscaledAndOpaque() {
        val layer: UfiPageLayer = ScaleTransition.layerAt(0f, ctx)
        assertEquals(1f, layer.scaleX, eps)
        assertEquals(1f, layer.scaleY, eps)
        assertEquals(1f, layer.alpha, eps)
    }

    @Test
    fun scale_shrinksTo82PercentAtOneScreenAway() {
        val layer: UfiPageLayer = ScaleTransition.layerAt(1f, ctx)
        assertEquals(0.82f, layer.scaleX, eps)
        assertEquals(0.82f, layer.scaleY, eps)
        assertEquals(0f, layer.alpha, eps)
    }

    @Test
    fun scale_isSymmetricAroundCenter() {
        assertEquals(
            ScaleTransition.layerAt(0.4f, ctx).scaleX,
            ScaleTransition.layerAt(-0.4f, ctx).scaleX,
            eps,
        )
    }

    // ── Rotate ───────────────────────────────────────────────────────────────

    @Test
    fun rotate_rotatesUpToMaxDegreesAtOneScreenAway() {
        val transition = RotateTransition.Default
        assertEquals(22f, transition.layerAt(1f, ctx).rotationZ, eps)
        assertEquals(-22f, transition.layerAt(-1f, ctx).rotationZ, eps)
        assertEquals(0f, transition.layerAt(0f, ctx).rotationZ, eps)
    }

    @Test
    fun rotate_pivotsAroundBottomCenter() {
        assertEquals(
            TransformOrigin(0.5f, 1f),
            RotateTransition.Default.layerAt(0.5f, ctx).transformOrigin,
        )
    }

    @Test
    fun rotate_honoursCustomMaxDegrees() {
        assertEquals(45f, RotateTransition(maxDegrees = 45f).layerAt(1f, ctx).rotationZ, eps)
    }

    @Test
    fun rotate_scalesDownTo72PercentAtOneScreenAway() {
        assertEquals(0.72f, RotateTransition.Default.layerAt(1f, ctx).scaleX, eps)
        assertEquals(1f, RotateTransition.Default.layerAt(0f, ctx).scaleX, eps)
    }

    @Test
    fun rotate_translatesWithPosition() {
        // 宿主抵消了 Pager 的布局基线，位移必须由策略自己给，否则页面原地倾斜不走人。
        val expected: Float = containerWidth * 0.7f
        assertEquals(expected, RotateTransition.Default.layerAt(1f, ctx).translationX, eps)
        assertEquals(-expected, RotateTransition.Default.layerAt(-1f, ctx).translationX, eps)
        assertEquals(0f, RotateTransition.Default.layerAt(0f, ctx).translationX, eps)
    }

    // ── Cube3D ───────────────────────────────────────────────────────────────

    @Test
    fun cube3d_rotatesNegativeNinetyDegreesPerScreen() {
        assertEquals(-45f, Cube3DTransition.layerAt(0.5f, ctx).rotationY, eps)
        assertEquals(-90f, Cube3DTransition.layerAt(1f, ctx).rotationY, eps)
        assertEquals(90f, Cube3DTransition.layerAt(-1f, ctx).rotationY, eps)
    }

    @Test
    fun cube3d_pivotsOnTheEdgeFacingTheCentre() {
        // 右侧来页绕左棱转，左侧来页绕右棱转 —— 两页才能拼成同一个立方体。
        assertEquals(
            TransformOrigin(0f, 0.5f),
            Cube3DTransition.layerAt(0.5f, ctx).transformOrigin,
        )
        assertEquals(
            TransformOrigin(1f, 0.5f),
            Cube3DTransition.layerAt(-0.5f, ctx).transformOrigin,
        )
    }

    @Test
    fun cube3d_translatesLikeSlideAndStaysOpaque() {
        val layer: UfiPageLayer = Cube3DTransition.layerAt(0.5f, ctx)
        assertEquals(0.5f * containerWidth, layer.translationX, eps)
        // 立方体是不透明实体，任何淡化都会露馅。
        assertEquals(1f, layer.alpha, eps)
        assertEquals(1f, Cube3DTransition.layerAt(1f, ctx).alpha, eps)
        assertEquals(16f, layer.cameraDistance, eps)
    }

    @Test
    fun cube3d_declaresPairedRenderingAndNoClipping() {
        assertTrue(Cube3DTransition.requiresPairedRendering)
        assertFalse(Cube3DTransition.clipToBounds)
    }

    // ── Flip3D ───────────────────────────────────────────────────────────────

    @Test
    fun flip3d_cullsBackFaceBeyondQuarterTurn() {
        assertEquals(0f, Flip3DTransition.layerAt(0.6f, ctx).alpha, eps)
        assertEquals(0f, Flip3DTransition.layerAt(-0.6f, ctx).alpha, eps)
        // 恰好 0.5 仍在正面（条件是严格大于）。
        assertEquals(1f, Flip3DTransition.layerAt(0.5f, ctx).alpha, eps)
        assertEquals(1f, Flip3DTransition.layerAt(0f, ctx).alpha, eps)
    }

    @Test
    fun flip3d_rotatesHalfTurnPerScreen() {
        assertEquals(180f, Flip3DTransition.layerAt(1f, ctx).rotationY, eps)
        assertEquals(-90f, Flip3DTransition.layerAt(-0.5f, ctx).rotationY, eps)
        assertEquals(12f, Flip3DTransition.layerAt(0f, ctx).cameraDistance, eps)
    }

    @Test
    fun flip3d_disablesClippingForPerspective() {
        assertFalse(Flip3DTransition.clipToBounds)
        assertFalse(Flip3DTransition.requiresPairedRendering)
    }

    // ── 跨策略不变量 ──────────────────────────────────────────────────────────

    @Test
    fun allTransitions_atCenter_areFullyOpaqueAndUntransformed() {
        // 居中页必须完全「原样」呈现，否则静止状态就会糊。
        for (transition in BuiltInTransitions.All) {
            val layer: UfiPageLayer = transition.layerAt(0f, ctx)
            val hint: String = "transition=${transition.id}"
            assertEquals(hint, 1f, layer.alpha, eps)
            assertEquals(hint, 1f, layer.scaleX, eps)
            assertEquals(hint, 1f, layer.scaleY, eps)
            assertEquals(hint, 0f, layer.translationX, eps)
            assertEquals(hint, 0f, layer.translationY, eps)
            assertEquals(hint, 0f, layer.rotationX, eps)
            assertEquals(hint, 0f, layer.rotationY, eps)
            assertEquals(hint, 0f, layer.rotationZ, eps)
        }
    }

    @Test
    fun allTransitions_alphaStaysWithinUnitRange() {
        // 采样整个 [-1.5, 1.5] 区间（含离屏页），alpha 不得越界。
        for (transition in BuiltInTransitions.All) {
            var step = -15
            while (step <= 15) {
                val position: Float = step / 10f
                val alpha: Float = transition.layerAt(position, ctx).alpha
                assertTrue(
                    "transition=${transition.id} position=$position alpha=$alpha",
                    alpha in 0f..1f,
                )
                step++
            }
        }
    }

    @Test
    fun allTransitions_haveUniqueIds() {
        val ids: List<String> = BuiltInTransitions.All.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun allTransitions_ignoreLayoutDirection() {
        // RTL 翻转由 T02 宿主统一处理一次，策略读取 layoutDirection 会导致翻转两次。
        val rtlCtx: UfiPageLayerContext = ctx.copy(layoutDirection = LayoutDirection.Rtl)
        for (transition in BuiltInTransitions.All) {
            assertEquals(
                "transition=${transition.id}",
                transition.layerAt(0.4f, ctx),
                transition.layerAt(0.4f, rtlCtx),
            )
        }
    }

    @Test
    fun allTransitions_ignoreIncomingFlag() {
        // 同一 position 的图层变换只由 position 决定，与出/入方向无关，保证手势可打断可反向。
        val incomingCtx: UfiPageLayerContext = ctx.copy(isIncoming = true)
        for (transition in BuiltInTransitions.All) {
            assertEquals(
                "transition=${transition.id}",
                transition.layerAt(-0.3f, ctx),
                transition.layerAt(-0.3f, incomingCtx),
            )
        }
    }
}
