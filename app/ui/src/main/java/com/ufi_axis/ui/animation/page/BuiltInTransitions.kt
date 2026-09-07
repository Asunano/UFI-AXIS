package com.ufi_axis.ui.animation.page

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.ufi_axis.ui.components.common.UfiExperimentalApi
import kotlin.math.abs
import kotlin.math.pow

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  T03 · 六种内置页面转场策略
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *  position 语义（与 T01 契约、T02 宿主全局一致）：
 *    ` 0f` = 居中（当前选中页）
 *    `+1f` = 完全位于「右侧」一屏外
 *    `-1f` = 完全位于「左侧」一屏外
 *  切换 / 手势拖拽过程中为连续小数。
 *
 *  ⚠ RTL 翻转已由 T02 宿主层统一处理一次，本文件所有策略
 *    **一律不读取** `ctx.layoutDirection`，否则会翻转两次。
 *
 *  所有 `layerAt` 均为纯函数：无状态、无副作用、非 @Composable，
 *  因此可被 `UfiPageTransitionTest` 以纯 JVM 单测 100% 覆盖。
 */

/** 归一化距离：`|position|` 夹取到 `0f..1f`，避免离屏页（`|p| > 1`）把 `lerp` 推向外插值。 */
private fun normalizedDistance(position: Float): Float = abs(position).coerceIn(0f, 1f)

// ─────────────────────────────────────────────────────────────────────────────
// 1. Fade —— 渐入渐出
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 渐入渐出（默认策略）。
 *
 * 观感复刻现有 `tabEnter`（`fadeIn(220) + scaleIn(0.98f)`），保证接入后**零视觉回归**。
 *
 * 映射：
 * - `alpha = (1 - |p| * 1.6) coerceIn 0..1` —— 系数 `1.6` 让淡出比位移更快结束，
 *   出入页在中段短暂交叠，避免「双页同时半透明」造成的浑浊感；
 * - `scale = lerp(0.98, 1, 1 - |p|)`        —— 极轻微的呼吸感。
 */
@UfiExperimentalApi
object FadeTransition : UfiPageTransition {

    override val id: String = "fade"

    override val displayName: String = "渐入渐出"

    override fun writeLayer(
        target: UfiPageLayer,
        position: Float,
        ctx: UfiPageLayerContext,
    ) {
        val distance: Float = normalizedDistance(position)
        val scale: Float = lerp(start = 0.98f, stop = 1f, fraction = 1f - distance)
        target.alpha = (1f - abs(position) * 1.6f).coerceIn(0f, 1f)
        target.translationX = 0f
        target.translationY = 0f
        target.scaleX = scale
        target.scaleY = scale
        target.rotationX = 0f
        target.rotationY = 0f
        target.rotationZ = 0f
        target.cameraDistance = 8f
        target.transformOrigin = TransformOrigin.Center
        target.shadowElevation = 0.dp
    }

    override fun layerAt(position: Float, ctx: UfiPageLayerContext): UfiPageLayer {
        val layer = UfiPageLayer()
        writeLayer(layer, position, ctx)
        return layer
    }

    /**
     * 模糊交叉淡入的模糊系数曲线。
     *
     * 观感目标（用户原话）：「切换时原界面模糊 → 淡入新界面并模糊 → 慢慢清晰」。
     *
     * 实现：用一个**共享进度** `t` 驱动两页的模糊，确保入/出页在同一瞬间模糊系数相同
     * （入页 `d:1→0` 映射到 `t:0→1`；出页 `d:0→1` 映射到 `t:0→1`），于是整屏同步起糊、
     * 同步变清晰，呈现交叉淡入而非各糊各的。
     *
     * 曲线取「三角脉冲」：
     * - 上升段（attack，`t ∈ [0, 0.5]`）线性快速起糊 —— 对应「原界面立刻开始模糊」；
     * - 下降段（release，`t ∈ [0.5, 1]`）用指数 `0.6f` 做慢释放（ease-out）——
     *   越接近落定模糊越持久、归零越柔和，对应「新界面淡入并模糊，然后**慢慢**清晰」。
     *
     * 落定（`t=1`）系数为 `0`，静止页绝不残留模糊。
     */
    override fun blurProfile(position: Float, isIncoming: Boolean): Float {
        val d: Float = abs(position).coerceIn(0f, 1f)
        // 共享进度：入页 d:1→0 ⇒ t:0→1；出页 d:0→1 ⇒ t:0→1。两页同瞬 t 相同 ⇒ 模糊同步。
        val t: Float = if (isIncoming) 1f - d else d
        val peak: Float = 0.5f
        val factor: Float = if (t <= peak) {
            t / peak // 上升段：线性快速起糊
        } else {
            // 2026-09-02：指数 1.4 → 2.4，与宿主的 defaultBlurProfile 保持同一口径。
            // 1.4 的释放仍然偏慢：行程走完 80% 时还剩 27% 的半径，收尾段"又慢又糊"，
            // 而模糊的离屏开销恰好压在最后那几帧上。2.4 把模糊集中在前半段，尾段干净。
            ((1f - t) / (1f - peak)).pow(2.4f)
        }
        return factor.coerceIn(0f, 1f)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Slide —— 滑动
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 水平平移。
 *
 * 最朴素也最「跟手」的策略：页面位置与手指位移严格 1:1，`alpha` 恒为 `1f`。
 *
 * 映射：`translationX = p * 容器宽度(px)`。
 */
@UfiExperimentalApi
object SlideTransition : UfiPageTransition {

    override val id: String = "slide"

    override val displayName: String = "滑动"

    // F3：Slide 双页全程全不透明，是模糊最重的路径；降到 0.6f 减轻 GPU 负载，观感不变。
    override val blurIntensity: Float = 0.6f

    override fun writeLayer(
        target: UfiPageLayer,
        position: Float,
        ctx: UfiPageLayerContext,
    ) {
        target.alpha = 1f
        target.translationX = position * ctx.containerSize.width.toFloat()
        target.translationY = 0f
        target.scaleX = 1f
        target.scaleY = 1f
        target.rotationX = 0f
        target.rotationY = 0f
        target.rotationZ = 0f
        target.cameraDistance = 8f
        target.transformOrigin = TransformOrigin.Center
        target.shadowElevation = 0.dp
    }

    override fun layerAt(position: Float, ctx: UfiPageLayerContext): UfiPageLayer {
        val layer = UfiPageLayer()
        writeLayer(layer, position, ctx)
        return layer
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Scale —— 缩放
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 缩放淡入。
 *
 * 出页向内塌陷、入页由小放大，配合淡出形成「层叠卡片」的纵深感。
 *
 * 映射：
 * - `scale = lerp(0.82, 1, 1 - |p|)`
 * - `alpha = 1 - |p|`
 */
@UfiExperimentalApi
object ScaleTransition : UfiPageTransition {

    override val id: String = "scale"

    override val displayName: String = "缩放"

    override fun writeLayer(
        target: UfiPageLayer,
        position: Float,
        ctx: UfiPageLayerContext,
    ) {
        val distance: Float = normalizedDistance(position)
        val scale: Float = lerp(start = 0.82f, stop = 1f, fraction = 1f - distance)
        target.alpha = 1f - distance
        target.translationX = 0f
        target.translationY = 0f
        target.scaleX = scale
        target.scaleY = scale
        target.rotationX = 0f
        target.rotationY = 0f
        target.rotationZ = 0f
        target.cameraDistance = 8f
        target.transformOrigin = TransformOrigin.Center
        target.shadowElevation = 0.dp
    }

    override fun layerAt(position: Float, ctx: UfiPageLayerContext): UfiPageLayer {
        val layer = UfiPageLayer()
        writeLayer(layer, position, ctx)
        return layer
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Rotate —— 旋转（唯一带构造参数的内置策略）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 平面旋转（绕 Z 轴）。
 *
 * 锚点取**底边中点** `TransformOrigin(0.5f, 1f)`，使页面像被「按住下沿摆动的牌」，
 * 而不是绕几何中心自转 —— 后者在 Tab 场景观感突兀。
 *
 * 映射：
 * - `rotationZ = p * [maxDegrees]`
 * - `scale     = lerp(0.72, 1, 1 - |p|)`
 * - `alpha     = 1 - |p|`
 * - `translationX = p * 容器宽度(px) * 0.7`
 *
 * 本类是 `data class` 而非 `object`，因此支持自定义摆动幅度并按值比较：
 * ```
 * UfiPageTransitions.register(RotateTransition(maxDegrees = 35f))
 * ```
 *
 * @property maxDegrees 位于一屏外（`|p| == 1`）时的旋转角度上限，单位**度**。
 */
@UfiExperimentalApi
@Immutable
data class RotateTransition(
    val maxDegrees: Float = 22f,
) : UfiPageTransition {

    override val id: String = "rotate"

    override val displayName: String = "旋转"

    override fun writeLayer(
        target: UfiPageLayer,
        position: Float,
        ctx: UfiPageLayerContext,
    ) {
        val distance: Float = normalizedDistance(position)
        val scale: Float = lerp(start = 0.72f, stop = 1f, fraction = 1f - distance)
        target.alpha = 1f - distance
        // 必须自己给位移：宿主已抵消 Pager 的布局基线（见 UfiPageSwitcherHost 的
        // `translationX -= realPosition * size.width`），这里写 0 会让页面原地倾斜不走人，
        // 观感是"抽搐"而不是"旋转切换"。取 0.7 屏宽而非满屏：配合 alpha→0 与底边支点的
        // 摆动，页面在离屏前就已淡透，位移给满反而让摆动幅度看起来被拉散。
        target.translationX = position * ctx.containerSize.width.toFloat() * TRANSLATION_FRACTION
        target.translationY = 0f
        target.scaleX = scale
        target.scaleY = scale
        target.rotationX = 0f
        target.rotationY = 0f
        target.rotationZ = position * maxDegrees
        target.cameraDistance = 8f
        target.transformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 1f)
        target.shadowElevation = 0.dp
    }

    override fun layerAt(position: Float, ctx: UfiPageLayerContext): UfiPageLayer {
        val layer = UfiPageLayer()
        writeLayer(layer, position, ctx)
        return layer
    }

    companion object {

        /** 位于一屏外（`|p| == 1`）时的水平位移占容器宽度的比例。 */
        private const val TRANSLATION_FRACTION: Float = 0.7f

        /**
         * 默认参数（`22°`）的共享单例。
         *
         * 内置装配统一复用它，避免 [UfiPageTransitions.Rotate] 与注册表里的实例
         * 是两个「相等但不同一」的对象。
         */
        val Default: RotateTransition = RotateTransition()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. Cube3D —— 立方体 3D
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 3D 立方体翻页。
 *
 * 出入两页各自绕**贴合的那条竖棱**旋转 90°，拼成立方体相邻两面：
 * - 右侧来页（`p > 0`）锚点取左边缘 `TransformOrigin(0f, 0.5f)`；
 * - 左侧来页（`p < 0`）锚点取右边缘 `TransformOrigin(1f, 0.5f)`。
 *
 * 因此：
 * - [requiresPairedRendering] 必须为 `true` —— 缺任意一面立方体即穿帮；
 * - [clipToBounds] 必须为 `false` —— 否则旋转出界的棱边会被裁掉；
 * - `alpha` 恒为 `1f` —— 立方体是不透明实体，任何淡化都会露馅。
 *
 * 映射：
 * - `rotationY       = -p * 90`（负号使旋转方向与位移方向视觉一致）
 * - `translationX    = p * 容器宽度(px)`
 * - `cameraDistance  = 16`（Compose `graphicsLayer` 语义，越大透视越平缓）
 */
@UfiExperimentalApi
object Cube3DTransition : UfiPageTransition {

    override val id: String = "cube3d"

    override val displayName: String = "立方体3D"

    override val requiresPairedRendering: Boolean = true

    override val clipToBounds: Boolean = false

    override fun writeLayer(
        target: UfiPageLayer,
        position: Float,
        ctx: UfiPageLayerContext,
    ) {
        target.alpha = 1f
        target.translationX = position * ctx.containerSize.width.toFloat()
        target.translationY = 0f
        target.scaleX = 1f
        target.scaleY = 1f
        target.rotationX = 0f
        target.rotationY = -position * 90f
        target.rotationZ = 0f
        target.cameraDistance = 16f
        target.transformOrigin = if (position > 0f) {
            TransformOrigin(pivotFractionX = 0f, pivotFractionY = 0.5f)
        } else {
            TransformOrigin(pivotFractionX = 1f, pivotFractionY = 0.5f)
        }
        target.shadowElevation = 0.dp
    }

    override fun layerAt(position: Float, ctx: UfiPageLayerContext): UfiPageLayer {
        val layer = UfiPageLayer()
        writeLayer(layer, position, ctx)
        return layer
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. Flip3D —— 翻转 3D
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 3D 卡片翻转（含背面剔除）。
 *
 * 页面绕中心竖轴翻转 180°。`|p| > 0.5` 即转过 90°、正在展示背面，此时
 * `alpha` 直接归零做**背面剔除**（back-face culling），使出入两页在 90° 处
 * 无缝交接，看起来像同一张卡片的正反面。
 *
 * [clipToBounds] 为 `false`，避免透视投影放大的边缘被裁切。
 *
 * 映射：
 * - `rotationY      = p * 180`
 * - `alpha          = if (|p| > 0.5) 0 else 1`（硬切，不做插值）
 * - `cameraDistance = 12`（比 Cube3D 更近，透视更夸张，强化「卡片」感）
 */
@UfiExperimentalApi
object Flip3DTransition : UfiPageTransition {

    override val id: String = "flip3d"

    override val displayName: String = "翻转3D"

    override val clipToBounds: Boolean = false

    override fun writeLayer(
        target: UfiPageLayer,
        position: Float,
        ctx: UfiPageLayerContext,
    ) {
        target.alpha = if (abs(position) > 0.5f) 0f else 1f
        target.translationX = 0f
        target.translationY = 0f
        target.scaleX = 1f
        target.scaleY = 1f
        target.rotationX = 0f
        target.rotationY = position * 180f
        target.rotationZ = 0f
        target.cameraDistance = 12f
        target.transformOrigin = TransformOrigin.Center
        target.shadowElevation = 0.dp
    }

    override fun layerAt(position: Float, ctx: UfiPageLayerContext): UfiPageLayer {
        val layer = UfiPageLayer()
        writeLayer(layer, position, ctx)
        return layer
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 装配：把六种内置策略灌入 UfiPageTransitions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 内置策略的**装配器**（T03 · 注册入口）。
 *
 * ## 装配内容
 * 1. 为 [UfiPageTransitions] 的六个 `lateinit` 引用赋上真实实现，覆盖 T01 的安全 Fade 兜底；
 * 2. 按 [All] 的顺序 [UfiPageTransitions.register] 六种策略 —— 该顺序即设置页下拉的显示顺序。
 *
 * ## 幂等性
 * 装配逻辑写在 `init` 块里，由 JVM 的类初始化机制保证**全进程仅执行一次**；
 * 即使 [registerBuiltInTransitions] 被反复调用也不会重复注册。
 * 退一步说，[UfiPageTransitions.register] 本身按 id 覆盖且保持插入位置，重复注册亦无副作用。
 *
 * ## 触发时机
 * Kotlin `object` 是懒初始化的 —— **不引用就不会执行 `init`**。
 * 因此调用方（T04 / `Application.onCreate`）必须显式调用 [registerBuiltInTransitions] 触发装配。
 *
 * @see registerBuiltInTransitions
 */
@UfiExperimentalApi
object BuiltInTransitions {

    /**
     * 六种内置策略，**列表顺序 == 注册顺序 == 设置页下拉顺序**。
     *
     * 顺序编排原则：由弱到强、由 2D 到 3D，把性能开销最低、最保守的 [FadeTransition]
     * 放在首位（它同时是默认值与降级目标）。
     */
    val All: List<UfiPageTransition> = listOf(
        FadeTransition,
        SlideTransition,
        ScaleTransition,
        RotateTransition.Default,
        Cube3DTransition,
        Flip3DTransition,
    )

    init {
        // ── 1. 覆盖 T01 的安全 Fade 兜底引用
        UfiPageTransitions.Fade = FadeTransition
        UfiPageTransitions.Slide = SlideTransition
        UfiPageTransitions.Scale = ScaleTransition
        UfiPageTransitions.Rotate = RotateTransition.Default
        UfiPageTransitions.Cube3D = Cube3DTransition
        UfiPageTransitions.Flip3D = Flip3DTransition

        // ── 2. 注册进查找表（"fade" 会原地覆盖 T01 的占位项，故 all().size == 6）
        for (transition in All) {
            UfiPageTransitions.register(transition)
        }
    }

    /**
     * 空实现，仅用于**强制触发本 object 的类初始化**。
     *
     * 直接写 `BuiltInTransitions` 作为语句会被编译器判定为「无副作用的未使用表达式」，
     * 因此改用一次真实的成员调用来确保 `<clinit>` 被执行。
     */
    internal fun ensureInitialized() = Unit
}

/**
 * 注册全部六种内置转场策略（**T04 / `Application.onCreate` 的唯一调用入口**）。
 *
 * 必须在任何 `UfiPageSwitcher` 首次组合、以及从 DataStore 还原设置项之前调用；
 * 否则 [UfiPageTransitions] 只含 T01 的安全 Fade 兜底，全部动画都会表现为渐入渐出。
 *
 * 本函数**幂等且线程安全**（由 JVM 类初始化的 happens-before 语义保证），可放心重复调用：
 * ```
 * class UfiApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         registerBuiltInTransitions()
 *     }
 * }
 * ```
 */
@UfiExperimentalApi
fun registerBuiltInTransitions() {
    BuiltInTransitions.ensureInitialized()
}
