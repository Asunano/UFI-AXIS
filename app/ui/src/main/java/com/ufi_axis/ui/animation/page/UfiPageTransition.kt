package com.ufi_axis.ui.animation.page

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.UfiExperimentalApi
import com.ufi_axis.ui.theme.UfiMotion

/**
 * 页面转场的**默认时间曲线** —— 全库唯一来源。
 *
 * 2026-09-04（P2c，bug 修复而非风格整理）：`tween(320, EaseInOutCubic)` 此前在两个地方
 * 各写了一遍 —— [UfiPageTransition.spec] 的接口默认值，与宿主
 * `UfiPageSwitcherHost.rememberFiniteSpec` 的 fallback。两者本该是同一条曲线，
 * 但**实际已经漂移**：宿主那份用的是 `UfiAnimSpecs.emphasizedInEasing`，
 * 不是接口默认的 `EaseInOutCubic`。也就是说"调用点没传 spec"与"调用点传了非有限 spec"
 * 这两条路走出来的转场手感不一样，而且改任一处另一处都不会跟着动。
 *
 * 现在两处都引用本 val：时长与曲线只有一个定义点，且是同一个对象实例（顺带省掉每次
 * `remember` 失效时的重复分配）。曲线口径统一取**接口默认的 `EaseInOutCubic`**——
 * 它是文档里写明的"推荐曲线"，fallback 应该镜像默认值而不是自作主张。
 *
 * 时长走 [UfiMotion.Duration.Sweeping]（320ms，值与收编前一致，零观感回归）。
 */
val UfiPageTransitionDefaultSpec: TweenSpec<Float> =
    tween(durationMillis = UfiMotion.Duration.Sweeping, easing = EaseInOutCubic)

/**
 * 可插拔「页面转场策略」契约（T01 · 契约核心）。
 *
 * ## 设计要点
 * 本契约刻意用 **归一化位置 → 图层变换** 的映射取代 Compose 的 `EnterTransition` / `ExitTransition`：
 *
 * - [layerAt] 是一个**纯函数**（无 `@Composable`、无副作用、无状态读取），因此
 *   1. 可被 JVM 单元测试 100% 覆盖（断言关键采样点）；
 *   2. 与宿主实现解耦 —— `HorizontalPager` 与 `AnimatedContent` 两种后端都能喂出同一语义的
 *      `position`，策略实现零改动。
 *
 * ## position 语义（全局唯一约定）
 * | 值 | 含义 |
 * |---|---|
 * | ` 0f` | 正中，完全可见（当前选中页） |
 * | `-1f` | 完全位于左侧一屏外 |
 * | `+1f` | 完全位于右侧一屏外 |
 *
 * 切换过程中为连续小数（如 `-0.37f`），手势拖拽时同样连续，因此天然支持可打断 / 可反向。
 *
 * ## 新增一种动画的完整成本
 * 只有两步：实现本接口 + 调用 [UfiPageTransitions.register]。
 *
 * ## 实现约定
 * - 角度单位统一为**度**（degree），不用弧度。
 * - RTL 方向翻转由宿主层统一处理一次，**策略实现不得各自判断** [UfiPageLayerContext.layoutDirection]。
 * - 本接口为普通 `interface`（非 `sealed`），以便 `:app` 层与任意 feature 模块在外部扩展。
 *
 * @see UfiPageLayer
 * @see UfiPageLayerContext
 * @see UfiPageTransitions
 */
@UfiExperimentalApi
@Immutable
interface UfiPageTransition {

    /** 唯一标识。用于注册表查找 / 持久化到设置项 / 埋点。约定为小写短横线风格，如 `"cube3d"`。 */
    val id: String

    /** 展示名（设置页「Tab 切换动画」下拉直接使用）。 */
    val displayName: String

    /**
     * 该策略推荐的时间曲线。宿主可用调用点传入的 spec 覆盖它。
     *
     * 2026-09-04（P2c）：默认值原来是就地写的 `tween(320, EaseInOutCubic)`，与宿主 fallback
     * 的副本已经漂移；现统一引用 [UfiPageTransitionDefaultSpec]（唯一来源，理由见其 KDoc）。
     */
    val spec: AnimationSpec<Float>
        get() = UfiPageTransitionDefaultSpec

    /**
     * 出入页是否**必须同时在场**渲染。
     *
     * 例如 Cube3D 依靠两页拼成一个立方体的相邻两面，缺一面即穿帮，故为 `true`。
     */
    val requiresPairedRendering: Boolean
        get() = false

    /**
     * 是否把绘制裁剪在容器边界内。
     *
     * 3D 透视类动画必须为 `false`，否则旋转出界的部分会被裁边。
     */
    val clipToBounds: Boolean
        get() = true

    /**
     * ReduceMotion / 低端机降级目标。
     *
     * `null` 表示"没有指定降级目标"，此时宿主统一降级到 [UfiPageTransitions.Fade]。
     */
    val reducedMotionFallback: UfiPageTransition?
        get() = null

    /**
     * ★ 唯一必须实现的方法 —— 整个扩展机制的支点。
     *
     * @param position 该页相对「当前选中页」的归一化偏移，语义见类级 KDoc。
     * @param ctx      容器尺寸 / 密度 / 布局方向 / 出入方向等环境信息。
     * @return         该页在此刻应施加的图层变换。
     */
    fun layerAt(position: Float, ctx: UfiPageLayerContext): UfiPageLayer

    /**
     * 归一化「过渡模糊」系数（`0f`=完全清晰，`1f`=峰值模糊）。
     *
     * 宿主会把它乘以 [TRANSITION_BLUR_MAX_RADIUS_DP]（见宿主实现）后转成 `RenderEffect`
     * 的模糊半径。这是**可选装饰**——默认 `0f`（不施加任何模糊），让「模糊过场」成为具体
     * 策略的观感选择，而非全局强制。
     *
     * ## 设计要点（写给实现者）
     * - 入参语义与 [layerAt] 完全一致：`position` 是归一化偏移，`ctx.isIncoming` 标注入/出页；
     * - 同一个过渡瞬间，**入页与出页应算出相同的系数**（靠共享进度 `t` 保证），这样两页的模糊
     *   才会同步起落，呈现「整屏一起糊、再一起变清晰」的交叉淡入观感，而不是各糊各的；
     * - 落定时（`|position| → 0`）系数必须回到 `0f`，否则静止页会残留模糊。
     *
     * [FadeTransition] 用它实现「旧界面起糊 → 新界面淡入并模糊 → 慢慢清晰」的模糊交叉淡入。
     */
    fun blurProfile(position: Float, isIncoming: Boolean): Float = 0f

    /**
     * 过渡模糊的**强度倍率**（`0f`~`1f`，默认 `1f`）。
     *
     * 宿主在把 [blurProfile] 的归一化系数换算成实际像素半径时，会再乘以本倍率，
     * 让单个策略在不改变其位移 / 旋转观感的前提下，单独调高或调低模糊负载。
     * 例如 [SlideTransition] 双页全程全不透明、是模糊最重的路径，可降到 `0.6f` 减轻 GPU 压力。
     *
     * 默认 `1f`：所有内置 / 自定义策略若未显式覆写，均保持「满模糊」观感，向后兼容；
     * 取 `0f` 即完全退化为无模糊。
     */
    val blurIntensity: Float
        get() = 1f

    /**
     * 把本策略在 [position] 处的图层变换**就地写入** [target]，而非返回新对象。
     *
     * 这是 [layerAt] 的「零分配」等价物，专为宿主 `graphicsLayer` 的**每帧热路径**设计：
     * 宿主持有一个可复用的 [UfiPageLayer]（见 `UfiPageLayerScratch`），每帧调用本方法把变换
     * 写入该复用实例再刷到图层，消除每帧 2 个短命 [UfiPageLayer] 的内存分配（降 young-gen GC）。
     *
     * 默认实现委托给 [layerAt]（造临时对象后逐字段拷回），保证**未覆写的外部 / 自定义策略
     * 依旧正确**；内置策略覆写为真正的就地计算，彻底跳过分配。本方法与 [layerAt] 必须产出
     * **完全一致**的字段值——内置策略统一让 [layerAt] 反向委托给本方法，以单一数据源避免漂移。
     *
     * @param target  待覆写的复用实例，所有字段都会被无条件改写（含未使用的默认值）。
     * @param position 该页相对「当前选中页」的归一化偏移。
     * @param ctx     容器尺寸 / 密度 / 布局方向 / 出入方向等环境信息。
     */
    fun writeLayer(target: UfiPageLayer, position: Float, ctx: UfiPageLayerContext) {
        val layer: UfiPageLayer = layerAt(position, ctx)
        target.alpha = layer.alpha
        target.translationX = layer.translationX
        target.translationY = layer.translationY
        target.scaleX = layer.scaleX
        target.scaleY = layer.scaleY
        target.rotationX = layer.rotationX
        target.rotationY = layer.rotationY
        target.rotationZ = layer.rotationZ
        target.cameraDistance = layer.cameraDistance
        target.transformOrigin = layer.transformOrigin
        target.shadowElevation = layer.shadowElevation
    }
}

/**
 * 纯数据的图层变换描述。
 *
 * 刻意**不直接暴露** Compose 的 `GraphicsLayerScope`，这样：
 * 1. 策略实现不依赖 Compose UI 运行时，可在纯 JVM 单测中构造与断言；
 * 2. 日后底层渲染方式演进（如换成 `RenderEffect`）不会破坏公共 API。
 *
 * 所有字段均带默认值，等价于「不做任何变换」。
 *
 * ## ⚠ 宿主映射约定（写给实现 `graphicsLayer` 映射的人）
 * 下列字段**全部直接赋值给 `GraphicsLayerScope` 的同名属性，不做任何换算**，唯一例外是
 * [shadowElevation]（`Dp` → `Float` px，需 `.toPx()`）。尤其注意 [cameraDistance]
 * **不要**再乘 density —— Compose 内部已经乘过了，再乘一次会让 3D 透视彻底失真。
 *
 * @property alpha           不透明度，`0f`（全透明）~ `1f`（不透明）。
 * @property translationX    水平位移，**已经是 px**，宿主直接赋值，不要再乘 density。
 * @property translationY    垂直位移，**已经是 px**，宿主直接赋值，不要再乘 density。
 * @property scaleX          水平缩放倍率。
 * @property scaleY          垂直缩放倍率。
 * @property rotationX       绕 X 轴旋转，单位**度**。
 * @property rotationY       绕 Y 轴旋转，单位**度**。
 * @property rotationZ       绕 Z 轴旋转（平面旋转），单位**度**。
 * @property cameraDistance  透视相机距离，采用 Compose `graphicsLayer` 的原生语义
 *                           （其内部按 `值 × density` 换算成物理距离）。
 *                           ★ 宿主**直接赋值即可，切勿自行乘 density**，否则透视失真。
 *                           值越小透视越夸张；3D 类动画通常取 `12f`~`16f`。
 * @property transformOrigin 变换锚点，归一化坐标（`0f..1f`）。宿主直接赋值。
 * @property shadowElevation 阴影高度。★ 本字段是 `Dp`，而 `GraphicsLayerScope.shadowElevation`
 *                           是 `Float` px，宿主映射时**必须 `.toPx()`**。
 */
/**
 * 纯数据的图层变换描述（**可变** `data class`）。
 *
 * 字段全部为 `var`：宿主热路径通过 [UfiPageTransition.writeLayer] 把变换**就地写入**
 * 一个可复用实例，避免每帧分配。除「就地复用」外仍保持纯数据语义，不依赖 Compose UI 运行时，
 * 可在纯 JVM 单测中构造与断言（`data class` 结构性相等依旧成立）。
 * 各字段的宿主映射约定见上方类级文档（直接赋值，[cameraDistance] 切勿乘 density，
 * [shadowElevation] 需 `.toPx()`）。
 */
data class UfiPageLayer(
    var alpha: Float = 1f,
    var translationX: Float = 0f,
    var translationY: Float = 0f,
    var scaleX: Float = 1f,
    var scaleY: Float = 1f,
    var rotationX: Float = 0f,
    var rotationY: Float = 0f,
    var rotationZ: Float = 0f,
    var cameraDistance: Float = 8f,
    var transformOrigin: TransformOrigin = TransformOrigin.Center,
    var shadowElevation: Dp = 0.dp,
)

/**
 * [UfiPageTransition.layerAt] 的环境上下文。
 *
 * @property containerSize   容器像素尺寸。策略需要按屏宽换算位移时使用（如 `p * containerSize.width`）。
 * @property density         屏幕密度。需要把 dp 换算成 px 时使用。
 * @property layoutDirection 布局方向。**仅供只读参考** —— RTL 翻转已由宿主层统一处理，
 *                           策略实现不应再自行取反，否则会翻转两次。
 * @property isIncoming      该页是「即将进入」(`true`) 还是「正在离开」(`false`)。
 */
@Immutable
data class UfiPageLayerContext(
    val containerSize: IntSize,
    val density: Density,
    val layoutDirection: LayoutDirection,
    val isIncoming: Boolean,
)
