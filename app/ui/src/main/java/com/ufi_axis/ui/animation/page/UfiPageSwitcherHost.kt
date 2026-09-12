package com.ufi_axis.ui.animation.page

import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.State
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis_core.util.UiFrameGate
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

// 注：这里曾有 EmphasizedEasing（M3 强调级缓动）的本地定义，与 UfiMotion.Easing.EmphasizedIn
// 数值完全相同。2026-09-01 批 5 收敛为单一来源，改用 UfiAnimSpecs.emphasizedInEasing。
// 2026-09-04（P2c）：本文件最后一处 emphasizedInEasing（rememberFiniteSpec 的 fallback）
// 已并入 UfiPageTransitionDefaultSpec，UfiAnimSpecs 的 import 随之移除。
//
// 注：这里曾有 SweepEasing（远程扫场专用缓动）。2026-09-01「远程 = 相邻」后，
// 所有切换共用调用方传入的同一条 spec，专用曲线随之删除。


// ─────────────────────────────────────────────────────────────────────────────
// 过渡模糊总开关（由「设置 → 外观」页控制；通过 CompositionLocal 下传，不侵入 UfiPageSwitcher 签名）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 过渡模糊是否启用（由「设置 → 外观」页控制）。
 * 默认 `true`（未提供时保持原有行为：施加模糊）。
 */
val LocalUfiBlurEnabled = staticCompositionLocalOf { true }

// 注：曾经这里还有一个 `LocalUfiCapsuleBlurEnabled`（底部胶囊玻璃模糊开关）。
// 胶囊的窗口级真模糊已被彻底移除（连同「每帧缩放窗口矩形」的配套方案 —— 那是动画卡顿
// 与胶囊自激塌缩的根因），玻璃观感改由 UfiCapsuleTabBar 的 frosted 渐变背景层恒定承担，
// 该开关随之失去意义，已连同 ThemeManager 偏好项与设置页开关一并删除。
// 本文件的 [LocalUfiBlurEnabled]（**页面切换过渡模糊**）与之无关，不受影响。


// ─────────────────────────────────────────────────────────────────────────────
// 宿主层视觉守卫常量
//
// 这三个常量**只服务于宿主层的鲁棒性与观感**，与任何具体动画策略无关，
// 因此刻意不放进 T01 契约（`UfiPageTransition`）—— 策略实现不该也不需要感知它们。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「已静止」判定阈值：`|position|` 小于该值即认定该页已完全归位。
 *
 * Pager 在动画被打断 / settle 的瞬间，可能对一个**本该静止**的页报出 `position = ±0.001`
 * 这类残值。对 Cube3D 而言 `rotationY = -0.001 × 90 ≈ -0.09°`，单帧不可见，但快速连续切页时
 * 会逐帧累积成肉眼可见的倾斜（页面「卡在半空」）。命中该阈值时直接写恒等变换即可根除。
 *
 * 取 `0.01f`：约等于 1% 屏宽，远小于任何一帧的真实位移（60fps / 320ms 下单帧约 5%），
 * 因此不会误伤正常过渡中的页。
 */
private const val SETTLE_EPSILON: Float = 0.01f

/**
 * 「完全离屏」判定阈值：`|position|` 达到该值即认定该页一个像素都不可见。
 *
 * ## 几何依据（与阈值取值直接相关）
 * Pager 中第 `i` 页的槽位在视口坐标系里占据 `[position × W, (position + 1) × W]`。
 * 因此 `position == +1` 时槽位恰好是 `[W, 2W]`、`position == -1` 时恰好是 `[-W, 0]` ——
 * **与视口 `[0, W]` 的交集为空**。且 `HorizontalPager` 通过 `clipScrollableContainer`
 * 在**主轴（水平）上严格裁剪到视口边界**（只在交叉轴放宽 30dp 给阴影），
 * 所以越界内容不可能漏出。故取 `1f`（含等号）而非更保守的 `> 1f`。
 *
 * ## 为什么「含等号」既安全又必要
 * 静止状态下相邻页的 position **恰好等于 ±1**。若用 `> 1f`，这批「常驻且完全不可见」的
 * 相邻页（`beyondViewportPageCount = 1` 时每帧都在跑 layer 回调）反而无法短路，
 * 优化收益会丢掉一大半 —— 而它们正是最值得省的对象。
 *
 * ## 六种内置策略在 `|p| == 1` 的可见性核对（T03 · 逐个确认过才敢加等号）
 * | 策略 | `|p| == 1` 时 | 是否不可见 |
 * |---|---|---|
 * | Fade    | `alpha = (1 - 1 × 1.6).coerceIn(0,1) = 0` | ✅ 自身已全透明 |
 * | Scale   | `alpha = 1 - 1 = 0`                       | ✅ 自身已全透明 |
 * | Rotate  | `alpha = 1 - 1 = 0`                       | ✅ 自身已全透明 |
 * | Flip3D  | `|p| > 0.5` 即背面剔除 `alpha = 0`         | ✅ 早在 0.5 就归零 |
 * | Slide   | `translationX = ±W`（在槽位偏移之上**再往外推**一屏） | ✅ 更远离视口 |
 * | Cube3D  | `translationX = ±W` 且 `rotationY = ∓90°`（正侧对观察者，投影宽度为 0） | ✅ 双重不可见 |
 *
 * 关键结论：没有任何一种内置策略在 `|p| == 1` 处**向视口内**平移，
 * 因此「槽位在视口外 ⇒ 不可见」的推理对全部六种策略成立，不存在 Cube3D 拼接边缘漏光的风险。
 *
 * ⚠ 该结论依赖「策略不会把离屏页拉回视口内」这一前提。日后若新增一种
 * 「把远处页面钉在视口里」（如 `translationX = -position × W` 的视差/固定背景）的策略，
 * 必须重新评估本短路，否则该策略会整页消失。
 */
private const val OFFSCREEN_THRESHOLD: Float = 1f

/**
 * 「改目标收尾」时长（毫秒）。
 *
 * 动画进行中用户点了别的页时，当前这一段不会就地掐断（掐断点是半成品状态，撤掉出场层
 * 会闪一下），而是把**剩余部分压缩到这个时长内跑完**，再从干净状态起步走向新目标。
 *
 * 取值权衡：太短接近瞬移、又开始有跳变感；太长就退化成「排队等它播完」。
 * 120ms 约 7 帧，肉眼判定为「加速收尾」而不是「卡了一下」。
 *
 * 2026-09-04（P2b）：值不变，改为引用 [UfiMotion.Duration.Micro]（也是 120）——
 * 原来是裸字面量，与全局时长梯度失联。
 */
private const val RETARGET_FINISH_MS: Int = UfiMotion.Duration.Micro

/**
 * 等帧的最长时间（毫秒）。
 *
 * 快照路径要靠 `withFrameNanos {}` 卡住"录制完成"和"预热完成"这两个时机。但帧时钟并不
 * 保证一定会来（窗口不可见、Recomposer 暂停、极端负载），一旦不来，切页协程就永久挂住，
 * 现象是"点导航栏有反应但界面永远不动"。所以每次等帧都设上限：超时就当这一帧已经过去，
 * 最坏情况只是快照少录一帧（画面差异肉眼不可见），绝不允许卡死。
 *
 * 100ms ≈ 6 帧（60Hz），正常情况下 16ms 内就返回，这个上限只在异常时才会触发。
 */
private const val FRAME_WAIT_TIMEOUT_MS: Long = 100L

/**
 * 「位置与目标不符却没有任何动画在跑」的兜底纠正延迟（毫秒）。
 *
 * 这是最后一道自愈闸门：正常路径由切页驱动负责，本闸门只在驱动没被唤醒（丢事件）或
 * 上一段异常退出时补一次瞬移。延迟 350ms 是为了不和正常起步抢 —— 一段动画的时长
 * 通常 300~400ms，起步只需一帧，所以 350ms 内还没动就基本可以断定驱动没接住。
 */
private const val STUCK_RECOVER_DELAY_MS: Long = 350L

/**
 * 转场闸门放下前的「尾巴」时长（毫秒）。
 *
 * 闸门放下意味着数据层把整段转场里攒下的 WS 状态一次性刷入 = 一次整屏重组。
 * 这个动作绝不能和动画的最后几帧同帧发生，否则那一帧必然超预算（观感：糊一下 + 抽一下）。
 * 150ms ≈ 9 帧，足够让最后一帧完整上屏，同时短到用户不会觉得数据"迟了"。
 */
private const val FRAME_GATE_TAIL_MS: Long = 150L

/**
 * 过渡模糊的**峰值**半径（dp）。
 *
 * 由 [UfiPageTransition.blurProfile] 返回的归一化系数（0~1）乘以本值得到实际 `RenderEffect`
 * 半径。
 *
 * ★ 2026-09-01 掉帧优化：18f → 12f。模糊半径直接决定离屏采样成本（近似随半径线性增长，
 *   且是**每帧**、**两个页层**同时付），是转场里最贵的一项。实测 12dp 仍保有明显的
 *   「糊→清晰」质感，但峰值帧耗时下降可观；配合放缓后的曲线，掉帧感基本消失。
 */
private const val TRANSITION_BLUR_MAX_RADIUS_DP: Float = 12f

/**
 * 施加模糊的最小有效半径（px）。
 *
 * 双重作用：
 * 1. 半径过小时肉眼无感，却仍要付出一次离屏渲染，纯属浪费；
 * 2. 规避部分 OEM 对 `RenderEffect.createBlurEffect(0f, 0f, …)` 的非法参数异常。
 */
private const val MIN_EFFECTIVE_BLUR_PX: Float = 0.5f

/**
 * 施加模糊的可见性下限（alpha）。
 *
 * [applyTransitionBlur] 入口用：当当前层 alpha 已低于本阈值（出入页都已近乎透明），
 * 该页在视觉上不可见，再对其跑满屏 GPU 模糊纯属浪费，且会无谓地维持一张离屏 FBO。
 * 直接 `renderEffect = null` 跳过，削减过渡起点 `null→非空` 的离屏缓冲分配尖峰（见 F7 注释）。
 */
private const val BLUR_VISIBLE_MIN_ALPHA: Float = 0.02f

/**
 * 宿主可选的两种渲染后端（T02 · 内部实现细节，不对外暴露）。
 */
internal enum class HostBackend {

    /**
     * 基于 `HorizontalPager`。
     *
     * 能力最全：支持手势拖拽、出入页同时在场、离屏页保活。代价是始终按「可滚动列表」组织，
     * 内存与组合开销略高。
     */
    Pager,

    /**
     * 基于 `AnimatedContent`。
     *
     * 轻量后端：同一时刻只保留出入两页，切换结束后旧页立即销毁。仅适用于
     * 「无手势 + 无需配对渲染 + 无需保活」的纯展示型场景。
     */
    AnimatedContent,
}

/**
 * 后端选择策略（纯函数，可直接单测）。
 *
 * 判定顺序即优先级，任一条件命中即选用能力更全的 [HostBackend.Pager]：
 * 1. [swipeEnabled] —— 只有 Pager 能提供跟手的横向拖拽；
 * 2. [UfiPageTransition.requiresPairedRendering] —— Cube3D 之类需要出入页同时在场；
 * 3. [keepPagesAlive] —— 需要离屏页保活以保留页面内状态。
 *
 * 三者皆否时才退到轻量的 [HostBackend.AnimatedContent]。
 *
 * @param transition     当前生效的转场策略（**已完成降级判定**，见 [UfiPageSwitcher]）。
 * @param swipeEnabled   是否允许横向滑动手势切页。
 * @param keepPagesAlive 是否让非当前页保持存活。
 */
internal fun selectBackend(
    transition: UfiPageTransition,
    swipeEnabled: Boolean,
    keepPagesAlive: Boolean,
): HostBackend = when {
    swipeEnabled -> HostBackend.Pager
    transition.requiresPairedRendering -> HostBackend.Pager
    keepPagesAlive -> HostBackend.Pager
    else -> HostBackend.AnimatedContent
}

/**
 * Pager 驱动侧的一组判据快照（2026-09-05）。
 *
 * 存在的唯一理由是**把这些输入的读取从组合期挪进协程**：以前它们直接当
 * `LaunchedEffect` 的 key（= 组合期读），`isDragged` / `programmaticAnim` 每翻转一次
 * 就重组整个 `PagerBackend` 函数体、重跑 5 个页槽的 item 组合。
 * 现在打包成一个 `data class` 走 `snapshotFlow` + `distinctUntilChanged` + `collectLatest`：
 * - `data class` 的 `equals` 让 `distinctUntilChanged` 等价于原来的「key 是否变化」；
 * - `collectLatest` 的「新值到达即取消上一次 body」等价于原来的「key 变了就重启 effect」。
 *
 * ⚠ **不要**把 `currentPageOffsetFraction` 加进来：它每帧都变，会把本流变成每帧一次的
 * 发射源（原实现也没把它当 key，只在 body 里现读）。
 */
private data class PagerDriveProbe(
    val target: Int,
    val page: Int,
    val scrolling: Boolean,
    val dragged: Boolean,
    val programmatic: Boolean,
)

/**
 * 「位置与目标不符、却没有任何动画在跑」的兜底判据（纯函数，可直接单测）。
 *
 * 兜底自愈的**前后两次检查**共用它，避免两处各写一份而慢慢长歪。
 *
 * @param offsetFraction `pagerState.currentPageOffsetFraction`，用 [SETTLE_EPSILON] 判"已归位"
 *   （浮点累加的结果在收尾帧常常差着 1e-7，`== 0f` 会误判）。
 * @return `true` = 需要瞬移补位；只要有任何一种"在动"（滚动 / 拖拽 / 程序化）就恒为 `false`
 *   —— 兜底绝不允许打断正常交互。
 */
internal fun pagerNeedsStuckRecovery(
    currentPage: Int,
    offsetFraction: Float,
    target: Int,
    scrolling: Boolean,
    dragged: Boolean,
    programmatic: Boolean,
): Boolean {
    if (scrolling || dragged || programmatic) return false
    return currentPage != target || abs(offsetFraction) >= SETTLE_EPSILON
}

// 注：这里曾有 SWEEP_BASE_MS / SWEEP_PER_EXTRA_PAGE_MS / SWEEP_MAX_MS / sweepDurationMs
// （远程扫场的距离相关时长）与 SKELETON_BLOCK_HEIGHTS（中间页骨架的条块高度）。
// 2026-09-01 起「远程跳转 = 一次相邻动画」，不再有扫场、不再有中间页，
// 时长与曲线一律复用相邻切换那一条（调用方传入的 animationSpec / 策略自带的 spec），
// 因此这些常量全部删除 —— 少一套参数，也就少一处「两种切换观感不一致」的来源。

/**

 * 页面切换宿主（T02 · 双后端实现核心）。
 *
 * 职责边界：
 * - **只负责**「把归一化 position 喂给 [UfiPageTransition.layerAt]，再把返回的 [UfiPageLayer]
 *   刷到 `graphicsLayer`」这条主链路，以及选中项的双向同步；
 * - **不负责**降级判定 —— 调用方 [UfiPageSwitcher] 传进来的 [transition] 已是最终生效策略；
 * - **不定义**任何具体动画 —— 那是 T03 `BuiltInTransitions.kt` 的职责。
 *
 * @param transition 已完成降级判定的最终策略。
 */
@Composable
internal fun UfiPageSwitcherHost(
    pages: List<UfiPage>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onSelectionProgressChange: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
    transition: UfiPageTransition,
    swipeEnabled: Boolean,
    animationSpec: AnimationSpec<Float>?,
    keepPagesAlive: Boolean,
    reduceMotion: Boolean = false,
) {
    // 空列表兜底：Pager 的 pageCount 为 0 时行为未定义，直接渲染空容器。
    if (pages.isEmpty()) {
        Box(modifier = modifier)
        return
    }

    // 调用方可能传入越界下标（如页面列表刚刚缩短），统一夹取，避免 IndexOutOfBounds。
    val safeIndex: Int = selectedIndex.coerceIn(0, pages.lastIndex)

    when (selectBackend(transition, swipeEnabled, keepPagesAlive)) {
        HostBackend.Pager -> PagerBackend(
            pages = pages,
            selectedIndex = safeIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            onSelectionProgressChange = onSelectionProgressChange,
            modifier = modifier,
            transition = transition,
            swipeEnabled = swipeEnabled,
            animationSpec = animationSpec,
            keepPagesAlive = keepPagesAlive,
            reduceMotion = reduceMotion,
        )

        HostBackend.AnimatedContent -> AnimatedContentBackend(
            pages = pages,
            selectedIndex = safeIndex,
            modifier = modifier,
            transition = transition,
            animationSpec = animationSpec,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 后端一：HorizontalPager（主路径）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `HorizontalPager` 后端 —— 支持跟手拖拽、可打断、可反向。
 *
 * ## position 公式（全局唯一约定，与 T01 契约 / T03 策略实现严格一致）
 * ```
 * rawPosition = (pageIndex - pagerState.currentPage) - pagerState.currentPageOffsetFraction
 * position    = if (RTL) -rawPosition else rawPosition
 * ```
 * 即：`0f` = 正中，`+1f` = 右侧一屏外，`-1f` = 左侧一屏外。
 *
 * ## 为什么把 position 的计算放进 `graphicsLayer` 块里
 * `pagerState.currentPageOffsetFraction` 每帧都变。若在组合阶段读取，会导致**每帧重组每一页**
 * （Pager 的经典性能陷阱）。放进 `graphicsLayer` 的 lambda 后，这次读取被推迟到
 * **draw/layer 阶段**，滚动过程中只重跑图层更新，不触发重组。
 */
@Composable
private fun PagerBackend(
    pages: List<UfiPage>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onSelectionProgressChange: ((Float) -> Unit)? = null,
    modifier: Modifier,
    transition: UfiPageTransition,
    swipeEnabled: Boolean,
    animationSpec: AnimationSpec<Float>?,
    keepPagesAlive: Boolean,
    reduceMotion: Boolean = false,
) {
    // 模糊开关：与下面的 `transitionState` **完全同一手法** —— 用 rememberUpdatedState 包一层，
    // 图层 lambda 捕获的是身份稳定的容器，`.value` 的读取被推迟到 draw/layer 阶段（快照读）。
    //
    // 为什么不能直接 `val blurEnabled = LocalUfiBlurEnabled.current`：那是**组合期读一次的
    // 裸 Boolean**，会被 `graphicsLayer {}` 的 lambda 按值闭包捕获。每页的 lambda 建立在
    // HorizontalPager 的 item 子组合里，其跳过/复用策略不由本函数控制 —— item 没重组时
    // 继续用旧 lambda，屏幕上就永远是首次组合时的那个开关值（`LocalUfiBlurEnabled` 还是
    // staticCompositionLocalOf，对子组合的穿透本身也不保证触发重组）。
    // 改成快照读后，开关一变即触发图层失效重跑，连重组都不需要。
    val blurEnabledState: State<Boolean> = rememberUpdatedState(LocalUfiBlurEnabled.current)
    val pagerState: PagerState = rememberPagerState(initialPage = selectedIndex) { pages.size }



    // 降低动效（Reduced Motion / 低端机）下，瞬移到位后对内容做一次极短淡入。
    val contentFade = remember { Animatable(1f) }

    // 转场闸门（`UiFrameGate`）的举起 / 放下见下方 `transitionRunning` —— 它要读
    // programmaticAnim / remoteJump，所以必须放在那两个状态声明之后。





    // ── 胶囊选中进度：只反映「当前是哪一页」+ 手势拖拽中的连续位置 ──
    //
    // 2026-09-01 删除扫荡：程序化切页不再让进度连续扫过中间图标（那会让沿途每个图标
    // 依次亮一遍，跨 4 页就是 4 次无意义的高亮），改为**直接落到目标**。
    // 「点哪个亮哪个」的淡入淡出由胶囊自己按 Tab 各做一份（见 UfiCapsuleTabBar）。
    val capsuleProgress = remember { Animatable(selectedIndex.toFloat()) }

    // 程序化切页是否进行中。用途有二：
    // 1. 阻止「手势跟手」把 pager 瞬时位置写进 capsuleProgress（两条路径抢写同一份状态）；
    // 2. 阻止切页途中的整数页被回吐成新的 selectedIndex。
    var programmaticAnim by remember { mutableStateOf(false) }

    // 出场层状态。非空表示「正在把一张页面快照当出场层、把目标页当相邻页那样搬进来」。
    var remoteJump by remember { mutableStateOf<RemoteJump?>(null) }

    // 需要录制快照的页下标。见 snapshotLayer 的说明。
    var captureSlot by remember { mutableStateOf<Int?>(null) }

    // 起始页的一张「画面快照」，跳转期间当出场层用：
    // 目标页瞬移到位后，起始页在 Pager 里已被回收（跨多页时不在组合范围内），
    // 但出场动画还需要它的画面 —— 录一层 RenderNode 即可，**零重组、零副作用**。
    //
    // ⚠️ 2026-09-01 血的教训：**只录单个页槽，绝不录「整块切换区」**。
    // 曾经为了做「中途改目标时录下当前合成画面」而改成双缓冲两层轮换，结果：
    // 第一次接管录进 B 层，B 的内容里含 `drawLayer(A)`；第二次接管录进 A 层，
    // A 的内容里就含了 `drawLayer(B)` —— A↔B 互相引用，RenderThread 递归绘制直接原生崩溃
    // （而且原生崩溃不走 Java 的 UncaughtExceptionHandler，CrashHandler 抓不到、
    // 重进也没有闪退弹窗，极难定位）。任何有限层数的轮换最终都会成环，此路不通。
    // 附带代价还有：录制已带 renderEffect 的离屏图层等于再套一层离屏，卡顿 + 模糊失效。
    //
    // 页槽内容里不含任何 `drawLayer`，所以录单页永远不会成环。
    val snapshotLayer = rememberGraphicsLayer()



    // 最新的目标下标。必须用 rememberUpdatedState 包一层：下面的切页循环 key = pagerState，
    // 不随 selectedIndex 重启，直接闭包捕获会永远读到第一次的值。
    val targetIndexState = rememberUpdatedState(selectedIndex)

    // ── 转场掉帧治理：转场进行中举起「逐帧动画」闸门（2026-08-31 引入） ──
    //
    // 数据层（DashboardModule 的 WS 实时指标）看到闸门举起就把 cpu/内存/流量/信号的状态更新
    // 攒起来，等转场结束再一次性刷入。原因：页面在根节点收 `dashboardState` 这一个大对象，
    // 一条 WS 推送 = 一次整屏重组；落在转场那 300ms 里会和「两页同时绘制 + 全屏模糊」叠加，
    // 直接超帧预算 —— 这正是「转场时随机卡一下」的来源（成本不在动画本身）。
    //
    // ★ 2026-09-02 「快结束时糊一下然后抽一下」的修复：闸门放下必须**比动画结束再晚一点**。
    // 旧条件就是"动画在跑"，于是落定那一帧同时发生两件事：最后几帧变换 + 闸门放下导致
    // 攒了一路的 WS 状态一次性刷入（整屏重组）。这一帧必然超预算：上一帧（还带着模糊）被
    // 多显示了一会 → 看起来"糊一下"，接着变换突然跳到位 → "抽一下"，然后一切正常。
    // 拖一个尾巴把这次刷入挪到静止之后 —— 静止时的一次重组肉眼看不见。
    //
    // 顺带修掉一个漏网：旧条件只看 isScrollInProgress，而跨页跳转靠 jump.progress 驱动
    // （pager 早已静止），那条路径上闸门根本没举起来过。
    //
    // ★★ 2026-09-05（次因 2 的一半）：判据整体挪出组合期 ★★
    //
    // 旧写法是在**组合期**算 `val transitionRunning = isScrollInProgress || contentFade.isRunning
    // || programmaticAnim || remoteJump != null`，再用它当 `LaunchedEffect` 的 key、并用
    // `if (transitionRunning || frameGateTail) DisposableEffect(Unit){…}` 开关闸门。
    // 于是 `programmaticAnim` 的每次 true/false 翻转（一次切页至少两次）都要**重组整个
    // PagerBackend 函数体**：重建传给 `HorizontalPager` 的 item lambda → 重跑 5 个页槽的
    // item 组合。这笔开销恰好落在 settle 前后，正是「落定那一刻顿一下」的次因。
    //
    // 现在改成一条长驻协程：同样四个输入，但读取发生在 `snapshotFlow` 的 lambda 里
    // （**协程内的快照读**，只让本协程重新求值，零重组、零布局）。
    // 闸门语义逐条对齐旧实现，没有任何行为差异：
    //   1. 「跑起来」立刻举闸门（旧：组合期 if 命中 → DisposableEffect 挂载 → begin）；
    //   2. 「停下来」等 FRAME_GATE_TAIL_MS 再放（旧：frameGateTail 计时后 if 变假 → dispose）；
    //   3. 尾巴期间又跑起来 ⇒ `collectLatest` 掐掉那次 delay，闸门**保持举起**不做 end/begin
    //      抖动（旧：`transitionRunning || frameGateTail` 全程为真，DisposableEffect 不重挂）；
    //   4. 组合销毁 / 协程取消 ⇒ `finally` 补一次 end（旧：onDispose）。
    LaunchedEffect(pagerState) {
        var held = false
        try {
            snapshotFlow {
                pagerState.isScrollInProgress ||
                    contentFade.isRunning ||
                    programmaticAnim ||
                    remoteJump != null
            }
                .distinctUntilChanged()
                .collectLatest { running ->
                    if (running) {
                        if (!held) {
                            UiFrameGate.begin()
                            held = true
                        }
                    } else {
                        delay(FRAME_GATE_TAIL_MS)
                        if (held) {
                            UiFrameGate.end()
                            held = false
                        }
                    }
                }
        } finally {
            if (held) UiFrameGate.end()
        }
    }


    // ── 唯一的程序化切页驱动 ──
    //
    // 演进史（都踩过，别再回头）：
    // - 第一版：两个 `LaunchedEffect(selectedIndex)`（一个滚页、一个扫胶囊）。连点时两个 effect
    //   各自被取消重启，取消点在动画中途 → pager 停在非整数 offset、胶囊停在非整数进度，
    //   新一轮从脏状态起步且两条动画错步 —— 真机现象「界面抽搐错乱」。
    // - 第二版：`conflate()` 串行队列，当前这段必定播完再切最新目标。抽搐没了，但连点要排队
    //   等一整段 300ms+，手感是「点了没反应」。
    // - 第三版：`collectLatest` 掐断 + 被掐断者走「瞬移 + contentFade 淡入」。响应快了，但
    //   contentFade 是整块 Pager 的 alpha，`snapTo(0f)` 会整屏闪一下，且掐断点是半成品状态。
    //
    // 第五版（当前）：**改目标 = 加速收尾 + 接着走**。
    //
    // 第四版试过「真正的中途拐弯」：掐掉动画但保留出场层，把此刻整屏（含出场层）录成新快照
    // 当出场层接着用。语义最理想，但需要两层快照轮换，第二次接管时两层互相 `drawLayer`
    // 成环 → RenderThread 原生崩溃（不走 Java 异常处理器，闪退还抓不到日志），
    // 而且录制已带 renderEffect 的离屏图层会再套一层离屏，卡顿 + 模糊丢失。详见 snapshotLayer 注释。
    //
    // 现在的做法：watcher 发现目标变了以后，**不掐断**，而是把当前这段剩余部分用
    // `RETARGET_FINISH_MS` 的短 tween 跑完（从当前位置继续，画面连续），落到整数位后
    // 立刻开始走向新目标。连点 1→2→3→4 的观感是「一路顺下去」，不排队、不闪、不崩。
    LaunchedEffect(pagerState) {
        /**
         * 播一段动画，同时盯着目标下标。
         *
         * @param current 本段动画的目标。watcher 只在目标**变成别的值**时才触发。
         * @return `null` = 正常播完；非空 = 播放途中用户点了别的页，值为新目标。
         */
        suspend fun playWatchingTarget(current: Int, animate: suspend () -> Unit): Int? =
            coroutineScope {
                var retarget: Int? = null
                val animJob = launch { animate() }
                val watchJob = launch {
                    retarget = snapshotFlow { targetIndexState.value }.first { it != current }
                    // 只掐动画。出场层 / 快照 / alpha 一律不动，交给外层无缝接管。
                    animJob.cancel()
                }
                animJob.join()
                watchJob.cancel()
                retarget
            }

        /** 等一帧，但**绝不无限等**。见 FRAME_WAIT_TIMEOUT_MS 的说明。 */
        suspend fun awaitFrameGuarded() {
            withTimeoutOrNull(FRAME_WAIT_TIMEOUT_MS) { withFrameNanos {} }
        }

        snapshotFlow { targetIndexState.value }
            .distinctUntilChanged()
            .conflate()
            .collect {
                programmaticAnim = true
                try {
                    // ★★ 2026-09-02 「偶现卡死在某一页」的结构性修复 ★★
                    //
                    // 旧写法：进入 collect 时读一次目标，之后靠上一段返回的 retarget 值决定
                    // 下一段去哪、返回 null 就退出。两个后果：
                    // 1. 目标只在「动画进行中」被 watcher 看见 —— 落在段与段之间、或落在
                    //    收尾 tween 那 120ms 里的点击，watcher 已经取消，这次点击就被丢了；
                    //    而 distinctUntilChanged 之后同一个值不会再来第二次 → 永久卡在旧页。
                    // 2. 任何一次异常退出都直接结束循环，位置与目标的不一致没人纠正。
                    //
                    // 新写法：每轮**现读** targetIndexState，只要「位置 ≠ 目标」就继续跑，
                    // 相符才退出。这样丢事件、异常、竞态都会在下一轮被自愈。
                    while (true) {
                        val target: Int = targetIndexState.value
                        if (contentFade.value != 1f) contentFade.snapTo(1f)
                        // 胶囊直接落到目标下标，不扫过中间图标。
                        if (capsuleProgress.value != target.toFloat()) {
                            capsuleProgress.snapTo(target.toFloat())
                        }
                        // 偏移判据统一走 SETTLE_EPSILON：currentPageOffsetFraction 是浮点累加的
                        // 结果，`== 0f` 在收尾帧常常差着 1e-7，用它当退出条件会让循环白转一轮
                        // （随后 animateScrollToPage 到同一页 → 一次多余的 0 距离动画）。
                        if (pagerState.currentPage == target &&
                            abs(pagerState.currentPageOffsetFraction) < SETTLE_EPSILON &&
                            remoteJump == null
                        ) {
                            break
                        }

                        val fromPage: Int = pagerState.currentPage

                        // ★ 所有切换共用同一条 spec：相邻与跨页的观感完全一致。
                        val motionSpec: AnimationSpec<Float> = animationSpec ?: transition.spec

                        // 能走原生滚动的条件：静止起步（无残留偏移、无出场层）且相邻。
                        val nativeAdjacent: Boolean =
                            remoteJump == null &&
                                abs(pagerState.currentPageOffsetFraction) < SETTLE_EPSILON &&
                                abs(target - fromPage) <= 1

                        when {
                            reduceMotion -> {
                                // 降低动效：瞬移 + 极短淡入。
                                // 2026-09-04（P2b）：原为裸 `tween(140)`。140 与梯度上的
                                // Duration.Micro(120) 只差 20ms（≤ 容差），吸附过去以免"极短淡入"
                                // 这一种手感在全库存在两个数；20ms 约 1.2 帧，肉眼不可分辨。
                                remoteJump = null
                                pagerState.scrollToPage(target)
                                contentFade.snapTo(0f)
                                contentFade.animateTo(
                                    1f,
                                    tween(UfiMotion.Duration.Micro, easing = LinearEasing),
                                )
                            }

                            nativeAdjacent -> {
                                val n: Int? = playWatchingTarget(target) {
                                    pagerState.animateScrollToPage(
                                        page = target,
                                        animationSpec = motionSpec,
                                    )
                                }
                                // 被改目标了：把这一段**剩下的部分压缩到极短时间内跑完**，
                                // 而不是就地掐断。animateScrollToPage 从当前偏移继续，
                                // 所以画面连续；跑完后页面处于整数位，下一轮干净起步。
                                if (n != null) {
                                    pagerState.animateScrollToPage(
                                        page = target,
                                        animationSpec = tween(
                                            RETARGET_FINISH_MS,
                                            easing = LinearEasing,
                                        ),
                                    )
                                }
                            }

                            // 快照路径：跨多页跳转。
                            //
                            // 为什么非它不可：HorizontalPager 只组合当前页附近的页，
                            // 1 和 4 不可能同时在场，「1 滑出、4 滑入」没法靠原生滚动实现
                            // （滚过去必然经过 2、3；先瞬移到 3 再滚一页，出场的就是 3 而不是 1）。
                            else -> {
                                // 1) 把起始页录进快照层（**只录单页**，页槽内不含 drawLayer，
                                //    不会出现图层互相引用；见 snapshotLayer 的告警注释）。
                                captureSlot = fromPage
                                // 等两帧：状态写入 → 下一帧组合 → 该帧 draw 里才真正录完。
                                awaitFrameGuarded()
                                awaitFrameGuarded()
                                captureSlot = null

                                // 2) 先挂出场层再瞬移：目标页首帧就按虚拟 position（屏外）渲染，
                                //    否则会闪一下「目标页已经在位」的画面。
                                val jump = RemoteJump(
                                    page = target,
                                    dir = if (target > fromPage) 1f else -1f,
                                )
                                remoteJump = jump
                                pagerState.scrollToPage(target)
                                // 预热一帧：目标页的首次组合 / 测量落在动画**开始之前**，
                                // 这帧开销表现为极轻微的点击延迟，而不是动画中掉帧。
                                awaitFrameGuarded()
                                val n: Int? = playWatchingTarget(target) {
                                    jump.progress.animateTo(1f, motionSpec)
                                }
                                // 同上：剩余部分极短时间收尾。
                                if (n != null && jump.progress.value < 1f) {
                                    jump.progress.animateTo(
                                        1f,
                                        tween(RETARGET_FINISH_MS, easing = LinearEasing),
                                    )
                                }
                                // progress 已到 1：出场层在屏外（alpha 0）、目标页正好落位，
                                // 所以每段结束都撤出场层 —— 零视觉变化，且下一轮能走更省的
                                // 原生相邻路径，也不会把脏的 remoteJump 带进下一轮。
                                remoteJump = null
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 取消必须原样抛：组合销毁 / 上层重启都走这条，吞掉会让协程无法结束
                    throw e
                } catch (e: Throwable) {
                    // ★★ 2026-09-04 「切页动画整体消失、重启 App 才恢复」的修复 ★★
                    //
                    // 本 collect 是**唯一**的程序化切页驱动，而它挂在 `LaunchedEffect(pagerState)` 上 ——
                    // key 是 remember 出来的 pagerState，重组不会变。于是这里一旦抛出非取消异常，
                    // 整个 collect 就永久结束、**再也不会重启**：此后点 Tab 只剩 350ms 后的
                    // `scrollToPage` 兜底自愈顶上，表现正是「能切页但一律瞬切，重启才好」。
                    // （复现路径：关闭后台服务后页内容被 ServiceGate 换成提示页，再点"启动"
                    //   → 页面重建期间这段动画链上的任一步抛错，动画驱动就死了。）
                    // 单次异常只应该丢掉这一次动画，不该带走驱动本身。
                    android.util.Log.w("UFI-AXIS/PageSwitcher", "page switch driver error (kept alive): ${e.message}")
                } finally {
                    // 三件事都不能依赖动画正常结束：出场层必须撤（否则永久盖住页面）、
                    // 录制必须停、alpha 必须回到 1（否则整块 Pager 停在半透明 = 暗屏）。
                    remoteJump = null
                    captureSlot = null
                    programmaticAnim = false
                }
            }
    }

    // Pager 内部翻页（**仅手势**）→ 回吐给外部状态。
    //
    // 2026-09-01：加上「这次 settle 是否由手势引起」的判据。
    // 原实现只看 `currentPage != selectedIndex`，而串行队列在两段动画之间也满足这个条件
    // （已到 B、最新目标是 C），于是会把 B 回吐成新目标、把 C 冲掉 —— 又一处抽搐来源。
    // 拖拽是手势路径的必要条件，用它把两种 settle 彻底分开。
    //
    // ★★ 2026-09-05（次因 2 的另一半）：`isDragged` 不再在组合期读 ★★
    //
    // 旧写法 `val isDragged: Boolean by …collectIsDraggedAsState()` 是**组合期读**：
    // 手指按下 / 抬手各翻转一次，每次都重组整个 `PagerBackend` 函数体 → 重建 Pager 的
    // item lambda → 重跑 5 个页槽的 item 组合。抬手那次翻转恰好落在 settle 前后，
    // 正是横滑「落定那一刻顿一下」的次因之一（点击路径 isDragged 恒 false，故无此开销）。
    //
    // 现在只保留 `State` 容器（`collectIsDraggedAsState()` 返回的就是它），所有读取都推迟到
    // 协程内的 `snapshotFlow` lambda 里。下面三条 effect 因此全部改成
    // 「`LaunchedEffect(pagerState)` 长驻 + snapshotFlow(判据快照) + collectLatest 重跑」：
    // `collectLatest` 的「新值到达即取消上一次 body」与旧的「key 变化即重启 effect」语义一致，
    // 而输入的读取从组合期挪进了协程 —— 判据不变、时序不变、重组消失。
    val isDraggedState: State<Boolean> = pagerState.interactionSource.collectIsDraggedAsState()
    var gestureSettlePending: Boolean by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        snapshotFlow { isDraggedState.value }.collect { dragged ->
            if (dragged) gestureSettlePending = true
        }
    }

    // ── 兜底自愈：位置与目标不符、却没有任何动画在跑 ──
    //
    // 「点导航栏有反应（图标变了）但界面卡在旧页」这一类现象的最后一道闸门。
    // 驱动本身已改成每轮现读目标（见上面的说明），理论上不该再丢事件；本闸门管的是
    // 理论之外的情况：驱动协程被异常中止、或 Pager 停在了非整数偏移上。
    // 判据必须排除手势与正在进行的滚动，否则会打断正常交互。
    //
    // 注：探针**刻意不含** `currentPageOffsetFraction`（它每帧都变，放进 snapshotFlow
    // 就成了每帧一次的流发射）。与旧实现一致：偏移只在 body 里现读，不参与重启判据。
    LaunchedEffect(pagerState) {
        snapshotFlow {
            PagerDriveProbe(
                target = targetIndexState.value,
                page = pagerState.currentPage,
                scrolling = pagerState.isScrollInProgress,
                dragged = isDraggedState.value,
                programmatic = programmaticAnim,
            )
        }
            .distinctUntilChanged()
            .collectLatest { probe ->
                if (!pagerNeedsStuckRecovery(
                        currentPage = probe.page,
                        offsetFraction = pagerState.currentPageOffsetFraction,
                        target = probe.target,
                        scrolling = probe.scrolling,
                        dragged = probe.dragged,
                        programmatic = probe.programmatic,
                    )
                ) {
                    return@collectLatest
                }
                delay(STUCK_RECOVER_DELAY_MS)
                // 350ms 后**现读**全部输入再判一次：这段时间里驱动很可能已经接住了。
                val latest: Int = targetIndexState.value
                if (pagerNeedsStuckRecovery(
                        currentPage = pagerState.currentPage,
                        offsetFraction = pagerState.currentPageOffsetFraction,
                        target = latest,
                        scrolling = pagerState.isScrollInProgress,
                        dragged = isDraggedState.value,
                        programmatic = programmaticAnim,
                    )
                ) {
                    // 到这一步已经确定「没人在动、位置也不对」：直接补位。瞬移而不是补动画 ——
                    // 这是异常兜底，正确性优先于观感，而且此时用户早已在等着看新页面了。
                    pagerState.scrollToPage(latest)
                }
            }
    }

    // 回吐点。`onSelectedIndexChange` 经 rememberUpdatedState 取用：本 effect 的 key 只有
    // pagerState，直接闭包捕获会一直用首次组合那个 lambda。
    val onIndexChangeState: State<(Int) -> Unit> = rememberUpdatedState(onSelectedIndexChange)
    LaunchedEffect(pagerState) {
        snapshotFlow {
            PagerDriveProbe(
                target = targetIndexState.value,
                page = pagerState.currentPage,
                scrolling = pagerState.isScrollInProgress,
                dragged = isDraggedState.value,
                programmatic = programmaticAnim,
            )
        }
            .distinctUntilChanged()
            .collectLatest { probe ->
                // gestureSettlePending 必须在「手势结束且没有程序化动画」时无条件清掉。
                // 原实现只在 currentPage/isScrollInProgress 变化时清，若手势中途被程序化切换接管
                // （点导航栏打断拖拽），标记就会一直挂着，下一次任意 settle 都会被误判成手势 settle
                // 并把 Pager 的当前页回吐成新目标。
                //
                // ⚠ 这里的「回吐必须等 `isScrollInProgress == false`」是硬性约束，不许加提前量：
                //   提前回吐会让 Pager 的 settle 与程序化驱动互相打断。
                if (probe.scrolling || probe.dragged) return@collectLatest
                if (!gestureSettlePending) return@collectLatest
                gestureSettlePending = false
                if (probe.programmatic) return@collectLatest
                if (probe.page != probe.target) {
                    onIndexChangeState.value(probe.page)
                }
            }
    }


    // 统一发射器：每帧把 capsuleProgress 转发给 onSelectionProgressChange
    // （手势跟手与程序化扫动两条路径都经此唯一出口，避免双路 emit 相互覆盖）。
    LaunchedEffect(Unit) {
        snapshotFlow { capsuleProgress.value }
            .collect { onSelectionProgressChange?.invoke(it) }
    }

    // 手势跟手：用户拖拽/惯性期间，把 pager 连续位置同步进 capsuleProgress。
    // programmaticAnim 为真时（程序化动画进行中）跳过，避免与上面的 animateTo 抢写同一份状态。
    LaunchedEffect(pagerState) {
        snapshotFlow {
            pagerState.currentPage + pagerState.currentPageOffsetFraction
        }.collect { p ->
            if (pagerState.isScrollInProgress && !programmaticAnim) {
                capsuleProgress.snapTo(p)
            }
        }
    }

    val isRtl: Boolean = LocalLayoutDirection.current == LayoutDirection.Rtl
    val layoutDirection: LayoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val layerDensity: Density = LocalDensity.current

    // 远程跳转出场层专用的复用槽（与各页内那份互不干扰，同样是为了消除每帧对象分配）。
    val outgoingScratch: UfiPageLayerScratch = remember { UfiPageLayerScratch() }

    // ★★ 2026-09-01 「外观页换动画不生效」的第二处根因：图层 lambda 的陈旧捕获 ★★
    //
    // 每页的 `Modifier.graphicsLayer { … }` 是在 **Pager 的 item 组合** 里建立的。
    // item 属于 LazyLayout 的子组合，其复用/跳过策略不由本函数控制 —— 一旦某次 item
    // 没有重组，它就继续用**上一次**的 lambda 实例，而那个实例按值捕获了旧的 `transition`。
    // 于是设置页改了策略、宿主也拿到了新策略，屏幕上却永远是首次组合时那一种。
    //
    // 解法：把策略装进一个**身份稳定**的 State（`rememberUpdatedState` 的返回值在整个
    // PagerBackend 生命周期内是同一个对象），图层 lambda 捕获的是这个容器而不是策略本身。
    // 即使 lambda 是旧的，`.value` 读到的也是最新策略；而且这次读取发生在 draw/layer 阶段
    // （快照读），策略一变就自动触发图层失效重跑 —— 连重组都不需要。
    val transitionState: State<UfiPageTransition> = rememberUpdatedState(transition)


    // 2026-08-23 稳定性校准：回归保守的预加载策略。
    //
    // 经真机测试，beyond=2 仍可能在复杂页面（如 Monitor）切换时造成同步负载尖峰。
    // 恢复 beyond=1（仅组合当前页与相邻一页），配合标准 LazyColumn 架构实现最高稳定性。
    //
    // ★★ 2026-09-05：**不要再让本值随导航转场动态变化** ★★
    //
    // 上一轮为了压 pop 首帧卡顿，加过一个 `neighborsReady`：外层 NavHost 转场进行中先按
    // `0` 组合、落定后再放开到 1。它被回退，因为那让 `beyondViewportPageCount` 在
    // **转场途中**从 0 跳到 1 —— 这个入参一变，`HorizontalPager` 底层的 LazyLayout 会重建
    // item provider 并重新测量，而这一刻程序化切页驱动（上面那条 `collect`）很可能正在
    // `animateScrollToPage` 中途。重测量落在滚动动画里会让 `currentPage` /
    // `currentPageOffsetFraction` 停在非整数位，驱动的 `while` 循环已按 `SETTLE_EPSILON`
    // 判定「到位」退出，于是页面卡在两页之间、而胶囊的 `capsuleProgress` 早已 `snapTo(目标)`
    // —— 真机观感正是「胶囊显示 a、实际是 b，而且点不动」。
    //
    // 正确性优先于那点首帧开销：本值只由「策略 / 保活 / 页数」这三个静态输入决定，
    // 一次组合内恒定。
    val baseBeyond: Int = remember(transition, keepPagesAlive, pages.size) {
        when {
            transition.requiresPairedRendering -> 1
            keepPagesAlive -> 1
            else -> 0
        }
    }

    // ★★ 2026-09-05 P0（横滑落定「顿一下」的主因）：每页一个身份稳定的 active 容器 ★★
    //
    // `LocalUfiPageActive` 曾是 `staticCompositionLocalOf<Boolean>`，而宿主每页 provide
    // `pageIndex == selectedIndex`。static local **没有细粒度读追踪**：provide 的值一变，
    // provider 以下整棵子树无条件重组（连 skippable 的子 composable 都不许跳过）。
    // `beyondViewportPageCount = 1` 时在场三页里有**两页**的值会同刻翻转 ⇒
    // **两棵完整页子树被强制重组**，恰好落在 settle 那一帧 ⇒ 「顿一下」。
    // 这与 `MainNavGraph` 里已修过的 `LocalCapsuleBottomInset` 是同一个机制。
    //
    // 现在 provide 的是**容器**（`State<Boolean>`）：每页一个，实例跨 `selectedIndex` 变化
    // 永不改变 ⇒ static local 的值从不变化 ⇒ 强制整树重组彻底消失；
    // 页面侧 `isUfiPageForeground()` 读 `.value` 是细粒度快照读，只失效真正读它的 composable。
    //
    // ⚠ 为什么容器必须建在**这里**（PagerBackend 作用域）而不是页 lambda 里：
    // 页 lambda 属于 `HorizontalPager` 的 item 子组合，其跳过 / 复用策略不由本函数控制
    // （见 transitionState 的说明：item 没重组时会继续用旧 lambda）。若把
    // `rememberUpdatedState(pageIndex == selectedIndex)` 写在 item 里，选中页翻转时可能
    // 根本没人去更新它 —— 那就是「轮询永远不随前后台切换启停」的严重 bug。
    // 建在这里则由 `selectedIndex`（本函数的入参）直接驱动，一定会更新；而快照失效不依赖
    // lambda 身份，页面照样能收到。
    val pageActiveStates: List<MutableState<Boolean>> = remember(pages.size) {
        List(pages.size) { index -> mutableStateOf(index == selectedIndex) }
    }
    // 手法同 `rememberUpdatedState`：组合期写容器。值相同时 `MutableState` 不派发失效，
    // 所以稳态下这一行是零成本；变化时只失效"读过它"的那些 composable。
    pageActiveStates.forEachIndexed { index, state -> state.value = index == selectedIndex }

    Box(modifier = modifier.fillMaxSize().graphicsLayer { alpha = contentFade.value }) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = swipeEnabled,
            beyondViewportPageCount = baseBeyond,
        ) { pageIndex ->
        val page: UfiPage = pages[pageIndex]
        // 每页各一份的 context 复用槽；remember 在 page lambda 内 → 天然按页作用域隔离。
        val layerScratch: UfiPageLayerScratch = remember { UfiPageLayerScratch() }
        UfiSaveablePage(key = page.key) {
            CompositionLocalProvider(
                // ★ provide 的是**容器**而不是值（见上方 pageActiveStates 的说明）：
                //   实例恒定 ⇒ static local 不会触发「provider 以下整树无条件重组」。
                LocalUfiPageActive provides pageActiveStates[pageIndex],
            // 告知页面「你正处在 Switcher 宿主之下」，使 isUfiPageForeground() 能把
            // 「后台页」与「根本不在 Switcher 内」这两种 active == false 区分开。
            // 注：这里 provide 的是**常量 true**，值永不变化，所以它虽然也是 static local
            // 却不会引发上述强制重组，无需改成容器。
            LocalUfiPageHosted provides true,
        ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // ★★ 2026-09-02 「相邻切换有一层淡底色掠过」的根因修复 ★★
                        //
                        // 曾经这里挂的是外层 `Modifier.clipToBounds()`。它建立的裁剪图层是
                        // 变换图层的**父节点**，裁剪矩形因此固定在 Pager 给这一页的**槽位**上
                        // （第 i 页槽位在 realPosition × W）。而下面的图层又把槽位偏移减掉、
                        // 把内容拉回视口 —— 于是每页只在「视口 ∩ 自己槽位」这半屏里可见，
                        // 两页各占一半、拼接处是硬边。再叠上策略 alpha 在 |p| 较大处提前归零
                        // （Fade 在 |p| ≥ 0.625 即为 0），屏幕上就出现一条只剩容器底色
                        // （MainNavGraph 的 palette.pageBg）的纯色带，沿滑动方向掠过全屏。
                        // 跨页快照路径 realPosition 恒为 0（已 scrollToPage 到位、位移由虚拟
                        // progress 驱动），裁剪矩形恰好等于视口，所以只有相邻切换会犯病。
                        //
                        // 改成在**同一个** graphicsLayer 里写 `clip`：裁剪发生在变换之前
                        // （先把内容裁到本页边界、再整体变换），这才是策略契约要的语义。
                        .graphicsLayer {
                            val rawPosition: Float =
                                (pageIndex - pagerState.currentPage) -
                                    pagerState.currentPageOffsetFraction
                            // 真实位置：由 Pager 的滚动状态决定，也决定了该页**槽位的布局偏移**。
                            val realPosition: Float = if (isRtl) -rawPosition else rawPosition

                            // 视觉位置：远程跳转时目标页由 jump.progress 驱动一条虚拟位置
                            // （从 ±1 走到 0），此时 pager 已静止在目标页（realPosition == 0）。
                            val jump: RemoteJump? = remoteJump
                            val position: Float =
                                if (jump != null && pageIndex == jump.page) {
                                    (1f - jump.progress.value) * jump.dir
                                } else {
                                    realPosition
                                }
                            val absPosition: Float = abs(position)

                            // ★ 当前生效策略：从**身份稳定**的容器里取（见 transitionState 的说明）。
                            // 这是 draw/layer 阶段的快照读 —— 换策略即触发图层失效重跑，
                            // 不依赖 Pager item 是否重组，从根上杜绝「选了不生效」。
                            val tr: UfiPageTransition = transitionState.value

                            // 裁剪与变换同层：先把内容裁到**本页边界**，再对整层做变换。
                            // 与旧的外层 clipToBounds 的区别见本 Box 上方的根因注释。
                            clip = tr.clipToBounds



                            // ★ 优化：完全离屏短路（省一次策略计算 + 一次 RenderEffect 设置）。
                            //
                            // |position| >= 1 时该页槽位与视口交集为空（推导见 OFFSCREEN_THRESHOLD），
                            // 六种内置策略在此处均不可见。直接写 alpha = 0 并**跳过 layerAt() 与
                            // applyTransitionBlur()** —— alpha == 0 的图层会被跳过绘制，收益叠加。

                            //
                            // 这里省下的不只是「过渡途中掠过的页」：`beyondViewportPageCount = 1`
                            // （Cube3D / keepPagesAlive）时，静止状态下左右相邻两页常驻组合、
                            // 且 position 恰好等于 ±1，原本每帧都要白跑一遍策略计算 —— 现在全省。
                            //
                            // 安全性：Compose 在每次调用本 lambda 前都会 `ReusableGraphicsLayerScope
                            // .reset()`（已核对 1.10.4 字节码：NodeCoordinator.updateLayerParameters
                            // 中 reset() → block() → updateLayerProperties() 的固定顺序），
                            // 所以「不写」等价于「写默认值」，绝不会残留上一帧的变换。
                            if (absPosition >= OFFSCREEN_THRESHOLD) {
                                alpha = 0f
                                return@graphicsLayer
                            }

                            // ★ 修复：静止归零保护（竞态守卫之二，与上面的 scrollToPage 守卫配套）。
                            // 只要判定为已静止，就**完全跳过策略计算**直接写恒等变换 ——
                            // 这是「本该静止的页绝不残留任何变换」的最后一道闸门，
                            // 也顺带省掉一次 layerAt() 调用。仅 Pager 后端需要：
                            // AnimatedContent 后端由 Transition 驱动，不存在手势打断问题。
                            if (absPosition < SETTLE_EPSILON) {
                                applyIdentityLayer()
                            } else {
                                val ctx: UfiPageLayerContext = layerScratch.contextFor(
                                    width = size.width.roundToInt(),
                                    height = size.height.roundToInt(),
                                    density = layerDensity,
                                    layoutDirection = layoutDirection,
                                    // 按 Pager 语义判断当前页是否为「即将进入」的页。
                                    isIncoming = pageIndex == pagerState.targetPage,
                                )
                                tr.writeLayer(layerScratch.reusableLayer, position, ctx)
                                applyLayer(layerScratch.reusableLayer)
                            }

                            // ★★ 2026-09-01 「外观页选什么动画都一样」的根因修复 ★★
                            //
                            // Pager 的整页横滑来自**布局摆放**（第 i 页槽位在 realPosition × W），
                            // 不在本 graphicsLayer 的管辖范围，所以策略写的 translationX 是
                            // **叠加**在它之上的，而不是替代它。后果：
                            // - 六种策略全部被同一条全屏横滑主导，差异只剩 0.98 vs 0.82 这类细节；
                            // - Slide / Cube3D 自己写了 translationX = position × W，位移直接翻倍，
                            //   Cube3D 的立方体接缝被推出可见区，「3D 感」根本看不到。
                            //
                            // 在这里减掉槽位偏移，把基线归零 —— 于是 `writeLayer` 成为**唯一**
                            // 视觉来源，这也正是 T01 契约与各策略 KDoc 一直声明的语义
                            // （Slide 的 KDoc 自称「与手势 1:1」，只有归零后才成立）。
                            if (realPosition != 0f) {
                                translationX -= realPosition * size.width
                            }

                        // 过渡期柔化：让切换不生硬。必须放在变换之后 ——
                        // renderEffect 与其余图层属性互不覆盖，但顺序统一便于阅读。
                        // 3D 配对渲染（Cube3D/Flip3D）本身已是离屏图层，再叠模糊会触发第二遍
                        // 离屏渲染、GPU 翻倍；设计上明确「不与模糊叠加」，故配对渲染直接跳过。
                        if (!tr.requiresPairedRendering) {
                            applyTransitionBlur(
                                position = position,
                                density = layerDensity,
                                blurEnabled = blurEnabledState.value,
                                transition = tr,
                                isIncoming = pageIndex == pagerState.targetPage,
                            )
                        }
                    }
                        // 跳转期间的快照录制：只在指定页槽、只在录制窗口内生效。
                        // 录进 RenderNode 后立刻把同一层画回来 —— 画面完全不变，用户看不出。
                        .drawWithContent {
                            if (captureSlot == pageIndex) {
                                snapshotLayer.record { this@drawWithContent.drawContent() }
                                drawLayer(snapshotLayer)
                            } else {
                                drawContent()
                            }
                        },
                ) {
                    page.content()
                }
            }
        }
        // （关闭 HorizontalPager 的 pageIndex lambda）
    }


        // ── 远程跳转的「出场层」：起始页的快照，按当前策略做出场变换 ──
        //
        // 只在远程跳转（|Δ| > 1）期间存在。它盖在 Pager 之上，与「入场的目标页」共同
        // 构成一次**完全等同于相邻切换**的两层动画：出场层走 position 0 → -dir，
        // 入场页走 position dir → 0，两者共用同一个 writeLayer + 模糊链路。
        val outgoingJump: RemoteJump? = remoteJump
        if (outgoingJump != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (transition.clipToBounds) Modifier.clipToBounds() else Modifier)
                    .graphicsLayer {
                        val position: Float = -outgoingJump.progress.value * outgoingJump.dir
                        val absPosition: Float = abs(position)
                        if (absPosition >= OFFSCREEN_THRESHOLD) {
                            alpha = 0f
                            return@graphicsLayer
                        }
                        if (absPosition < SETTLE_EPSILON) {
                            applyIdentityLayer()
                        } else {
                            val ctx: UfiPageLayerContext = outgoingScratch.contextFor(
                                width = size.width.roundToInt(),
                                height = size.height.roundToInt(),
                                density = layerDensity,
                                layoutDirection = layoutDirection,
                                isIncoming = false,
                            )
                            transition.writeLayer(outgoingScratch.reusableLayer, position, ctx)
                            applyLayer(outgoingScratch.reusableLayer)
                        }
                        if (!transition.requiresPairedRendering) {
                            applyTransitionBlur(
                                position = position,
                                density = layerDensity,
                                blurEnabled = blurEnabledState.value,
                                transition = transition,
                                isIncoming = false,
                            )
                        }
                    }
                    .drawBehind { drawLayer(snapshotLayer) }
            )
        }
    }
}



// ─────────────────────────────────────────────────────────────────────────────
// 远程跳转（|Δ| > 1）状态
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 一次远程跳转的驱动状态。
 *
 * @param page 目标页下标。该页的视觉位置改由 [progress] 驱动（虚拟 position `dir → 0`），
 *   而不再取自 Pager 的滚动状态 —— 因为 Pager 已经**瞬移**到位，真实 position 恒为 0。
 * @param dir  方向：往后翻 `+1`（目标页从右侧进入），往前翻 `-1`。
 *
 * 历史：这里曾经是 `passthrough: IntRange` + `UfiPassthroughSkeleton`（把中间页渲染成骨架
 * 再逐页扫过去）。2026-09-01 按「所有切换都是一次相邻动画、不掠过中间页」的要求整体替换：
 * 起始页录快照当出场层，目标页按虚拟 position 入场，中间页彻底不参与。
 */
private class RemoteJump(val page: Int, val dir: Float) {
    val progress: Animatable<Float, AnimationVector1D> = Animatable(0f)
}


// ─────────────────────────────────────────────────────────────────────────────
// 后端二：AnimatedContent（轻量 fallback）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 记录最近一次切换方向的**非快照**持有者。
 *
 * 用普通可变对象而非 `mutableStateOf`，是为了能在**组合阶段同步写入并立即读到**，
 * 且不触发额外重组（写快照状态再读同一状态会多跑一次组合）。
 */
private class UfiPageDirectionTracker(initialIndex: Int) {
    var lastIndex: Int = initialIndex
    var forward: Boolean = true
}

/**
 * `AnimatedContent` 后端 —— 仅在「无手势 + 无需配对渲染 + 无需保活」时选用。
 *
 * 用 `AnimatedVisibilityScope.transition` 派生一条 `animateFloat`，把出入两页各自的
 * position 从 `±1f ↔ 0f` 连续驱动，再交给同一个 [UfiPageTransition.layerAt] 渲染 ——
 * 因此**与 Pager 后端共享完全相同的策略实现**，T03 的动画零改动即可在两种后端下工作。
 *
 * position 取值与 Pager 后端同构：
 * - 进入页：`forward` 时 `+1f → 0f`，回退时 `-1f → 0f`；
 * - 离开页：`0f → -1f`（forward）或 `0f → +1f`（回退）。
 *
 * 说明：这里刻意传 `EnterTransition.None / ExitTransition.None`，让 `layerAt` 成为**唯一**的
 * 视觉来源（否则 AnimatedContent 自带的 fade 会与 `layer.alpha` 相乘）。出场页的存活时长由
 * 我们挂在同一个 `Transition` 上的 `animateFloat` 撑住，动画跑完才会被移除。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun AnimatedContentBackend(
    pages: List<UfiPage>,
    selectedIndex: Int,
    modifier: Modifier,
    transition: UfiPageTransition,
    animationSpec: AnimationSpec<Float>?,
) {
    // 模糊开关：与 Pager 后端逐字同构 —— rememberUpdatedState 包一层，图层 lambda 捕获
    // 身份稳定的容器，`.value` 是 draw/layer 阶段的快照读，开关一变即触发图层失效重跑。
    // 直接读裸 Boolean 会被 `graphicsLayer {}` 的 lambda 按值捕获（AnimatedContent 的
    // 每个槽位也是子组合），表现为「关了开关还在模糊，杀进程重进才生效」。
    val blurEnabledState: State<Boolean> = rememberUpdatedState(LocalUfiBlurEnabled.current)
    val isRtl: Boolean = LocalLayoutDirection.current == LayoutDirection.Rtl
    val layoutDirection: LayoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val layerDensity: Density = LocalDensity.current
    val finiteSpec: FiniteAnimationSpec<Float> = rememberFiniteSpec(animationSpec, transition)

    // 组合阶段同步推导切换方向：进入页需要知道自己该从左边还是右边飞进来。
    val tracker: UfiPageDirectionTracker = remember { UfiPageDirectionTracker(selectedIndex) }
    if (tracker.lastIndex != selectedIndex) {
        tracker.forward = selectedIndex > tracker.lastIndex
        tracker.lastIndex = selectedIndex
    }
    val forward: Boolean = tracker.forward

    AnimatedContent(
        targetState = selectedIndex,
        modifier = modifier,
        // 不使用 SizeTransform：本后端页面切场动画由 Box 的 graphicsLayer 位移（position）实现，
        // 页面本体是 fillMaxSize（与容器同尺寸），不存在「尺寸收缩/展开」观感，SizeTransform 纯属冗余。
        // 关键：AnimatedContent 一旦挂上 SizeTransform，会在切场测量时**额外用 maxHeight=Infinity 复测一遍
        // 槽位以取「自然尺寸」做插值**；此时页面内 LazyColumn(weight(1f)) 在父 Column 拿到无限高度后
        // 会展开到完整内容高度（长目录可达数十万 px），AnimatedContent 再把这个值当 minHeight 强压给容器，
        // 超过屏幕 maxHeight → SizeNode 抛 `Can't represent a width of 0 and height of N in Constraints`
        // （与先前 scaleIn 0 宽崩溃同机制，2026-09-11 真机崩溃日志复现：height=412858）。
        // clip 由下方 Box 的 Modifier.clipToBounds() 独立负责，移除 SizeTransform 不影响裁剪。
        transitionSpec = {
            EnterTransition.None togetherWith ExitTransition.None
        },
        contentAlignment = Alignment.Center,
        label = "UfiPageSwitcherAnimatedContent",
    ) { targetIndex ->
        val pageIndex: Int = targetIndex.coerceIn(0, pages.lastIndex)
        val page: UfiPage = pages[pageIndex]

        // delta == 0 → 本槽位是「进入页」；否则是正在离场的旧页。
        val delta: Int = pageIndex - selectedIndex
        val restPosition: Float = when {
            delta > 0 -> 1f
            delta < 0 -> -1f
            else -> 0f
        }
        val enterPosition: Float = if (forward) 1f else -1f
        val isIncoming: Boolean = delta == 0

        val rawPosition: Float by this.transition.animateFloat(
            transitionSpec = { finiteSpec },
            label = "ufiPagePosition",
        ) { state ->
            when (state) {
                EnterExitState.PreEnter -> if (isIncoming) enterPosition else restPosition
                EnterExitState.Visible -> 0f
                EnterExitState.PostExit -> restPosition
            }
        }

        // 每槽位各一份的 context 复用槽（语义同 Pager 后端，保持两个后端对称）。
        val layerScratch: UfiPageLayerScratch = remember { UfiPageLayerScratch() }

        // 与 Pager 后端同一手法：provide 身份稳定的**容器**而不是裸 Boolean。
        // 本槽位的 `isIncoming` 会随 `selectedIndex` 翻转（出场槽位由 true 变 false），
        // 若直接 provide 值，static local 会强制整棵页子树重组（见 LocalUfiPageActive 的 KDoc）。
        // 这里能安全用 rememberUpdatedState：AnimatedContent 的每个槽位都由本函数体的重组
        // 重新求值（`isIncoming` 就取自 `selectedIndex`），不存在 Pager item 那种"可能不重组"。
        val activeState: State<Boolean> = rememberUpdatedState(isIncoming)

        UfiSaveablePage(key = page.key) {
            CompositionLocalProvider(
                LocalUfiPageActive provides activeState,
                // 与 Pager 后端对称：两个后端都必须声明「内容处于宿主管理之下」，
                // 否则 isUfiPageForeground() 会因后端不同而给出不一致的结果。
                LocalUfiPageHosted provides true,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (transition.clipToBounds) Modifier.clipToBounds() else Modifier)
                        .graphicsLayer {
                            val position: Float = if (isRtl) -rawPosition else rawPosition
                            val ctx: UfiPageLayerContext = layerScratch.contextFor(
                                width = size.width.roundToInt(),
                                height = size.height.roundToInt(),
                                density = layerDensity,
                                layoutDirection = layoutDirection,
                                isIncoming = isIncoming,
                            )
                            transition.writeLayer(layerScratch.reusableLayer, position, ctx)
                            applyLayer(layerScratch.reusableLayer)

                            // 与 Pager 后端保持逐字段一致的观感。
                            // 这里不加「静止归零」守卫：position 由 Transition.animateFloat 驱动，
                            // 起止值是精确的 0f / ±1f，不存在被手势打断而留下残值的通路。
                            //
                            // 同理也**刻意不加**「完全离屏短路」：本后端同一时刻只有出入两页，
                            // 它们仅在动画首尾各一两帧触及 |position| == 1，随即被移除 ——
                            // 不存在 Pager 那种「常驻离屏页每帧空跑」的稳态开销，
                            // 加了只增风险不增收益（与上面「不加静止归零」同一取舍逻辑）。
                            applyTransitionBlur(
                                position = position,
                                density = layerDensity,
                                blurEnabled = blurEnabledState.value,
                                transition = transition,
                                isIncoming = isIncoming,
                            )
                        },
                ) {
                    page.content()
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 共享工具
// ─────────────────────────────────────────────────────────────────────────────

/**
 * **每页各一份**的 [UfiPageLayerContext] 复用槽（消除 `graphicsLayer` 的每帧对象分配）。
 *
 * ## 要解决的问题
 * `graphicsLayer {}` 的 lambda 在**每一帧**都会执行。原实现每帧都 `UfiPageLayerContext(...)`
 * 新建一个对象：过渡期通常有 2 页在场 → 每帧 2 个短命对象，60fps 下即 120 个/秒，
 * 叠加策略必然返回的 [UfiPageLayer]（T01 契约要求，省不掉），young gen 压力可观。
 *
 * ## 复用依据
 * [UfiPageLayerContext] 是 `@Immutable data class`，其四个入参的变化频率都极低：
 * - `density` / `layoutDirection`：一次组合内基本恒定；
 * - `containerSize`：仅在容器尺寸变化时变（旋屏、分屏）；
 * - `isIncoming`：仅在 `targetPage` 变化时变（每次切页至多一次）。
 *
 * 也就是说，**绝大多数帧的四个入参与上一帧完全相同**，完全可以复用同一个实例。
 * 本类逐字段比较，命中则返回上一帧的对象，未命中才新建 —— 稳态下分配次数降为 0。
 *
 * ## 实现约束
 * - 刻意用**普通可变类**而非 `mutableStateOf`：这些字段在 draw/layer 阶段被读写，
 *   若是快照状态会触发无谓的重组，与优化目标背道而驰（同 [UfiPageDirectionTracker] 的取舍）;
 * - 必须**每页一份**。在两个后端里它都 `remember` 在「单页内容」的作用域内
 *   （Pager 的 page lambda / AnimatedContent 的 content lambda），天然按页隔离；
 *   若做成全局单例，相邻两页的 `isIncoming` 不同会导致互相打架、每帧都 miss。
 */
private class UfiPageLayerScratch {

    private var width: Int = -1
    private var height: Int = -1
    private var density: Density? = null
    private var layoutDirection: LayoutDirection? = null
    private var isIncoming: Boolean = false
    private var cached: UfiPageLayerContext? = null

    /**
     * 可复用的 [UfiPageLayer] 槽位（F5）。
     *
     * 宿主每帧调用 [UfiPageTransition.writeLayer] 把变换**就地写入**本实例，再刷到图层，
     * 避免每帧为出入两页各 `UfiPageLayer(...)` 新建一个短命对象（降 young-gen GC）。
     * 与 [contextFor] 同理：每页一份（在 page / content lambda 内 `remember`），天然按页隔离，
     * 故相邻两页不会争用同一实例、不会互相覆盖。
     */
    val reusableLayer: UfiPageLayer = UfiPageLayer()

    /**
     * 取用与入参匹配的 [UfiPageLayerContext]：命中则复用上一帧实例，否则新建并记忆。
     *
     * @param width           容器宽度（px）。拆成两个 Int 传入是为了避免为比较而先构造 `IntSize`。
     * @param height          容器高度（px）。
     * @param density         屏幕密度。
     * @param layoutDirection 布局方向。
     * @param isIncoming      该页是否为「即将进入」的页。
     */
    fun contextFor(
        width: Int,
        height: Int,
        density: Density,
        layoutDirection: LayoutDirection,
        isIncoming: Boolean,
    ): UfiPageLayerContext {
        val hit: UfiPageLayerContext? = cached
        if (hit != null &&
            this.width == width &&
            this.height == height &&
            this.density == density &&
            this.layoutDirection == layoutDirection &&
            this.isIncoming == isIncoming
        ) {
            return hit
        }
        val fresh = UfiPageLayerContext(
            // GraphicsLayerScope.size 是 Compose 的 Size(Float)，契约要 IntSize。
            containerSize = IntSize(width = width, height = height),
            density = density,
            layoutDirection = layoutDirection,
            isIncoming = isIncoming,
        )
        this.width = width
        this.height = height
        this.density = density
        this.layoutDirection = layoutDirection
        this.isIncoming = isIncoming
        cached = fresh
        return fresh
    }
}

/**
 * 把纯数据的 [UfiPageLayer] 刷进 Compose 的图层作用域。
 *
 * 抽成扩展函数以保证**两个后端逐字段一致**，杜绝「Pager 支持 rotationY、AnimatedContent 漏了」
 * 这类不对称 bug。
 *
 * ## 关于 cameraDistance：为什么**不再乘 density**
 * T01 契约与 T03 内置策略的 KDoc 都声明该字段采用「Compose `graphicsLayer` 语义」。
 * 核对 Compose UI 1.10.4 字节码可知，`ViewLayer` 在下发给平台前已用
 * `displayMetrics.densityDpi`（≈ `160 × density`）换算过一次：
 * ```
 * View.cameraDistance = scope.cameraDistance * densityDpi
 * ```
 * 这正是 Compose 默认值 `8f` 恰好等价于 Android View 默认 `1280 × density` px 的原因
 * （`8 × 160 × density == 1280 × density`）。
 *
 * 因此该字段**本身就是密度无关的**，此处若再乘一次 `density.density`，在 2.75x 的设备上会把
 * T03 的 `Cube3D(16f)` / `Flip3D(12f)` 放大到 44 / 33 —— 透视被摊平近 3 倍，3D 效果近乎失效。
 * 直接赋值才符合契约。
 *
 * @param layer 策略计算出的图层变换。
 */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.applyLayer(layer: UfiPageLayer) {
    alpha = layer.alpha
    translationX = layer.translationX
    translationY = layer.translationY
    scaleX = layer.scaleX
    scaleY = layer.scaleY
    rotationX = layer.rotationX
    rotationY = layer.rotationY
    rotationZ = layer.rotationZ
    cameraDistance = layer.cameraDistance
    transformOrigin = layer.transformOrigin
    shadowElevation = layer.shadowElevation.toPx()
}

/**
 * 静止页的恒等变换（竞态修复配套）。
 *
 * 当 `|position| < SETTLE_EPSILON` 时调用，确保「本该静止」的页**绝对不残留任何空间变换**。
 * 不清零 [androidx.compose.ui.graphics.GraphicsLayerScope.alpha]（保持 1f 即可），只把位移/缩放/旋转/相机距离/锚点全部复位。
 */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.applyIdentityLayer() {
    translationX = 0f
    translationY = 0f
    scaleX = 1f
    scaleY = 1f
    rotationX = 0f
    rotationY = 0f
    rotationZ = 0f
    cameraDistance = 8f
    transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
}

/**
 * 全局默认过渡模糊曲线（三角脉冲）。
 *
 * 当某个转场策略未自定义 [UfiPageTransition.blurProfile]（默认返回 0）时采用，
 * 使「滑动 / 缩放 / 旋转」等 2D 动画也能享有与渐入渐出一致的「旧界面起糊 → 新界面
 * 模糊淡入 → 慢慢清晰」观感。3D 策略（[UfiPageTransition.requiresPairedRendering]）由宿主层
 * 直接跳过模糊，不会进入本函数。
 *
 * 曲线与 [FadeTransition.blurProfile] 相同：上升段线性快速起糊、下降段指数慢释放，落定柔和归零。
 */
private fun defaultBlurProfile(position: Float, isIncoming: Boolean): Float {
    val d: Float = abs(position).coerceIn(0f, 1f)
    // 共享进度：入页 d:1→0 ⇒ t:0→1；出页 d:0→1 ⇒ t:0→1。两页同瞬 t 相同 ⇒ 模糊同步。
    val t: Float = if (isIncoming) 1f - d else d
    val peak: Float = 0.5f
    val factor: Float = if (t <= peak) {
        t / peak // 上升段：线性快速起糊
    } else {
        // 2026-09-02：指数 1.4 → 2.4。1.4 的释放太慢，行程走完 80% 时还有 27% 的半径，
        // 于是"最后一段又慢又糊"，模糊的每帧离屏开销恰好压在收尾帧上。2.4 把模糊集中在
        // 前半段（观感差异肉眼几乎不可分辨），尾段干净、也不再为它付 GPU。
        ((1f - t) / (1f - peak)).pow(2.4f)
    }
    return factor.coerceIn(0f, 1f)
}

/**
 * 过渡模糊的 [androidx.compose.ui.graphics.RenderEffect] 缓存（按量化半径复用）。
 *
 * `graphicsLayer {}` 的 lambda 每帧都会执行，原本每个过渡中的页面每帧都 `createBlurEffect`
 * 一次（JNI + 原生 RenderEffect 分配）。半径只取决于 `|position|`，范围极小
 * （0 ~ [TRANSITION_BLUR_MAX_RADIUS_DP]，量化到 1px 桶后最多几十个），缓存后把「每帧分配」
 * 降为「首次命中分配」，显著减少 GC 压力与掉帧。RenderEffect 不可变，跨帧/跨图层复用安全。
 */
private val blurEffectCache = HashMap<Int, androidx.compose.ui.graphics.RenderEffect>()

/** [blurEffectCache] 的容量上限；越界时整体清空，避免异常密度下无界增长。 */
private const val BLUR_CACHE_MAX: Int = 128

/**
 * 取用（或首次创建）指定半径的模糊 [androidx.compose.ui.graphics.RenderEffect]。
 *
 * 半径按 1px 量化成整数 key —— 亚像素级的半径差异肉眼完全不可分辨，量化后命中率极高，
 * 而模糊的 GPU 计算量与观感均不受影响。
 *
 * @param blurRadiusPx 期望的模糊半径（px，非负）。
 * @return 可跨帧复用的不可变模糊效果。
 */
private fun getBlurRenderEffect(blurRadiusPx: Float): androidx.compose.ui.graphics.RenderEffect {
    val key: Int = blurRadiusPx.roundToInt().coerceAtLeast(0)
    blurEffectCache[key]?.let { return it }
    val effect: androidx.compose.ui.graphics.RenderEffect = RenderEffect.createBlurEffect(
        blurRadiusPx,
        blurRadiusPx,
        Shader.TileMode.CLAMP,
    ).asComposeRenderEffect()
    if (blurEffectCache.size >= BLUR_CACHE_MAX) blurEffectCache.clear()
    blurEffectCache[key] = effect
    return effect
}

/**
 * 对过渡中的页面施加模糊交叉淡入（[RenderEffect]）。
 *
 * 模糊**系数**优先用策略自定义的 [UfiPageTransition.blurProfile]；策略未自定义（默认 0，
 * 如滑动 / 缩放 / 旋转）时回退到 [defaultBlurProfile] 全局三角脉冲，使「过渡模糊」开关对全部
 * 2D 动画生效；[FadeTransition] 即采用该同款「快速起糊 + 慢释放」曲线。
 * 本函数只负责把归一化系数 × [TRANSITION_BLUR_MAX_RADIUS_DP] 转成实际像素半径并下发给图层。
 *
 * 仅在页面处于「过渡中」`(|position| < 1)` 时生效；完全离屏页保持清晰，避免无谓的渲染开销。
 * 系数在行程中点取到峰值、两端归零。
 * （2026-08-31：远程多页跳转已改为「淡出 → 瞬移 → 淡入」，不再掠过中间页，
 *   因此原来的「扫场恒定模糊」分支随之删除。）
 *
 * 依赖：minSdk = 31，[RenderEffect.createBlurEffect] 可用。
 *
 * ## 关于「每帧写 `renderEffect = null` 是否浪费」—— 结论：不浪费，**且不可再优化**
 * 核对 Compose UI 1.10.4 字节码，两处事实决定了这一点：
 * 1. `ReusableGraphicsLayerScope.setRenderEffect` 的 setter **自带等值 diff**：
 *    ```
 *    getfield renderEffect / aload value / Intrinsics.areEqual / ifne → return
 *    ```
 *    值相同时直接返回，既不写字段、也不置 `mutatedFields` 的 RenderEffect 位，
 *    因此**不会把图层标脏**，代价仅一次虚调用 + 一次引用比较；
 * 2. `NodeCoordinator.updateLayerParameters` 的固定顺序是
 *    `scope.reset()` → `block()` → `layer.updateLayerProperties(scope)`，
 *    而 `reset()` 内部就调用了 `setRenderEffect(null)` 并把 `mutatedFields` 清零。
 *
 * 即：本 lambda 每次执行时 `renderEffect` 已经是 `null`，这里的 `renderEffect = null`
 * 命中 diff、完全是 no-op。
 *
 * ⚠ **切勿**改成「用 holder 记住上一帧写入值、相同则跳过写入」的写法 —— 那会是个 bug：
 * 由于事实 2，每帧开头 `renderEffect` 都被 `reset()` 成 `null`，跳过写入意味着模糊
 * **彻底消失**，而不是「保持上一帧的模糊」。
 *
 * ## ⚠ 根因与本次修复（F1 / F3 / F4）的边界
 * 上面「同帧写 `null` 是 no-op」的结论**只覆盖稳态下的 `非空 → null →（已被 reset 为 null）` 路径**，
 * **并不覆盖过渡起点 `null → 非空` 的翻转**：平台在该翻转时会为整页分配一张离屏 FBO 缓冲
 * （入页 + 出页各触发一次），正是「某一帧卡一下」的根因（模拟器软渲染下开销尤甚）。
 * 把模糊从仅 Fade 扩展到全部 2D 后，所有切换都背满屏 GPU 模糊，该尖峰被放大到每次切换必现。
 * 本次修复的用意**不是消除**该分配（那需重构渲染后端），而是**削减其频率与规模**：
 * - **F1**：出页 alpha 趋零后直接跳过其模糊，不再为不可见页分配 / 维持 FBO；
 * - **F3**：按策略强度（Slide = 0.6f）缩小模糊半径，降低单次 FBO 的 GPU 像素负载；
 * - **F4**：弱机（低内存 / 低 RAM）彻底免模糊，从根上规避低端真机卡顿。
 *
 * @param position     归一化位置（语义同 T01 契约：`0`=居中，`±1`=一屏外）。
 * @param density      屏幕密度，用于把 dp 半径换算成 px。
 * @param blurEnabled  全局过渡模糊开关（来自 [LocalUfiBlurEnabled]）。
 * @param transition   当前生效的转场策略（提供 [UfiPageTransition.blurProfile]）。
 * @param isIncoming   该页是「即将进入」(`true`) 还是「正在离开」(`false`)，供策略区分出入页。
 */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.applyTransitionBlur(
    position: Float,
    density: Density,
    blurEnabled: Boolean,
    transition: UfiPageTransition,
    isIncoming: Boolean,
) {
    // ★ 全局模糊开关：用户可在「设置 → 外观」中关闭过渡模糊以节省 GPU 开销。
    // `blurEnabled` 由调用方（@Composable 函数体）用 `rememberUpdatedState` 包成 State 后
    // 在 draw/layer 阶段读 `.value` 传入 —— 原因：graphicsLayer {} 的 lambda 是
    // **非 composable 上下文**（draw 阶段执行），不能在此直接读取 CompositionLocal.current
    // （编译期会被视为 composable 调用而报错）；而组合期读一次的裸 Boolean 会被 lambda
    // 按值捕获，导致「关了开关还在模糊」（见调用点 blurEnabledState 的说明）。
    //
    // 关闭时**显式**把 renderEffect 清成 null：不依赖「Compose 每次调用前会
    // ReusableGraphicsLayerScope.reset()」这条外部约定 —— 那是运行时实现细节，
    // 而这里要的是「开关一关，本函数自己保证这层没有模糊」。
    // ── F1：可见性短路（削减过渡起点 null→非空 离屏 FBO 分配尖峰）─────────────────
    // 当前层 alpha 已由 applyLayer 在本函数之前写入。出页 alpha 早早趋零
    // （Fade 在 |p|≈0.625 即 alpha=0、Scale/Rotate 出页 alpha 也随 |p| 增大趋零）后，
    // 该页在视觉上已不可见，再对其跑满屏 GPU 模糊纯属浪费，且会无谓维持一张离屏 FBO。
    // 直接置 null 跳过；仅在 alpha 接近 0 时触发，不影响任何可见页的正常模糊。
    if (alpha < BLUR_VISIBLE_MIN_ALPHA) {
        renderEffect = null
        return
    }

    if (blurEnabled) {
        val absPos: Float = kotlin.math.abs(position)
        if (absPos < 0.99f) {
            if (absPos > SETTLE_EPSILON) {
                // 其余情况（相邻切换 / 手势 / 静止页）的模糊系数：优先用策略自定义曲线，
                // 未自定义（滑动 / 缩放 / 旋转等的 blurProfile 默认 0）时回退到全局三角脉冲，
                // 使「过渡模糊」开关对所有 2D 动画都生效（与渐入渐出一致观感）。
                val factor: Float = transition.blurProfile(position, isIncoming).takeIf { it > 0f }
                    ?: defaultBlurProfile(position, isIncoming)
                // F3：模糊系数乘以 intensity（默认 1f；Slide=0.6f）。intensity=0 时半径=0 → 退化为无模糊。
                val blurRadiusPx: Float = with(density) { (TRANSITION_BLUR_MAX_RADIUS_DP * factor * transition.blurIntensity).dp.toPx() }
                // 系数为 0 或半径过小（肉眼无感却仍要一次离屏渲染，纯属浪费；也规避部分 OEM
                // 对 `createBlurEffect(0f, 0f, …)` 的非法参数异常，见 [MIN_EFFECTIVE_BLUR_PX]）时跳过。
                renderEffect = if (factor > 0f && blurRadiusPx >= MIN_EFFECTIVE_BLUR_PX) {
                    getBlurRenderEffect(blurRadiusPx)
                } else {
                    null
                }
            } else {
                renderEffect = null
            }
        } else {
            renderEffect = null
        }
    } else {
        // 开关关闭：显式清零。原实现在这一支什么都不写，靠 Compose 在每次调用本 lambda 前
        // `ReusableGraphicsLayerScope.reset()` 把 renderEffect 带回 null —— 那是运行时的
        // 实现细节（外部约定），一旦哪天不成立，「关掉开关」就会退化成「保持上一帧的模糊」。
        // 代价为零：`setRenderEffect` 自带等值 diff，同帧已是 null 时命中 diff 直接返回，
        // 不会把图层标脏（推导见本函数 KDoc「每帧写 renderEffect = null 是否浪费」）。
        renderEffect = null
    }
}

/**
 * 把调用点传入的 [AnimationSpec] 收敛为 `AnimatedContent` 所需的 [FiniteAnimationSpec]。
 *
 * `Transition.animateFloat` 只接受有限时长的 spec（无限动画会让内容永不出场 / 永不移除）。
 * 传入的是无限 spec（如 `infiniteRepeatable`）时安全回退到默认 tween。
 */
@Suppress("UNCHECKED_CAST")
@Composable
private fun rememberFiniteSpec(
    animationSpec: AnimationSpec<Float>?,
    transition: UfiPageTransition,
): FiniteAnimationSpec<Float> {
    val raw: AnimationSpec<Float> = animationSpec ?: transition.spec
    return remember(raw) {
        // 2026-09-04（P2c）：原为就地写死的 `tween(320, UfiAnimSpecs.emphasizedInEasing)`——
        // 与 UfiPageTransition.spec 的默认值是同一个概念的第二份副本，且曲线已经漂移
        // （接口默认是 EaseInOutCubic）。现统一引用唯一来源，改一处两边同步。
        raw as? FiniteAnimationSpec<Float>
            ?: UfiPageTransitionDefaultSpec
    }
}
