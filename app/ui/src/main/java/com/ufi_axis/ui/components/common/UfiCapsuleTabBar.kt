package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.ufi_axis.ui.theme.UfiAnimSpecs
import com.ufi_axis.ui.animation.page.LocalUfiReduceMotion
import com.ufi_axis.ui.navigation.LocalUfiCapsuleSelectionProgress
import com.ufi_axis.ui.navigation.ufiNavTransitionDurationMs
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.NeutralOutline
import com.ufi_axis.ui.theme.ThemeManager
import com.ufi_axis.ui.theme.UfiTextStyles
import androidx.compose.foundation.interaction.MutableInteractionSource
// ★ 已删除（2026-09-24，迁移阶段 2.4）：
//   `androidx.compose.foundation.gestures.Orientation` / `.draggable` / `.rememberDraggableState`
//   —— 横向拖拽切页整套已移除，理由见本文件下方 `settleIndex` 处的删除说明与
//   `docs/bottom-dock-migration-plan.md` §5.2。
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.ufi_axis.ui.theme.UfiMotion

/**
 * 悬浮胶囊导航栏的单个 Tab 描述。
 *
 * @param key   稳定标识（通常直接复用路由名），用于列表 key 与调试定位。
 * @param label Tab 名称，仅在胶囊处于**展开态**时淡入显示（展开态下 5 个标签同时出现）。
 * @param icon  Tab 图标，常驻显示 —— 收起态也保留全部图标，只是整体缩小。
 */
data class CapsuleTabItem(
    val key: String,
    val label: String,
    val icon: ImageVector
)

// ── 视觉常量 ────────────────────────────────────────────────────────────────

/**
 * 单个 Tab 的**最小**宽度。
 *
 * 2026-09-04 由 54dp 加到 62dp：着色滑块的宽度就是「格宽 - 2×[INDICATOR_INSET_H]」，
 * 而药丸的圆角恒取自身半高，**宽高比 ≤ 1.2 就一定渲染成圆**。格宽 54 + 图标 38 时
 * 滑块约 50×48 —— 那正是用户看到的"圆点"。加宽格宽是把它拉回胶囊比例的两个杠杆之一
 * （另一个是把图标默认尺寸降到 26dp，见 `ThemeManager.DEFAULT_CAPSULE_ICON_SIZE_DP`）。
 * 整条胶囊宽度 = 2×[PAD] + 5×本值 + 4×[GAP] ≈ 340dp，仍能落在 360dp 宽屏内；
 * 再加宽会有窄屏 + 放大 UI 缩放时被窗口裁切的风险，所以测量块另加了一道按可用宽度收窄的闸门。
 */
private val TAB_MIN_W: Dp = 62.dp

/** Tab 之间的水平间距。 */
private val GAP: Dp = 4.25.dp

/** 胶囊**横向**内边距（左右各一份）。 */
private val PAD: Dp = 6.5.dp

/**
 * 胶囊内容区与**上下边框**的固定间隔（上下各一份）。
 *
 * 2026-09-04 由 7.5dp 加到 13dp：图标默认尺寸降到 26dp 后，整条胶囊高度
 * （= 2×本值 + 图标 + 标签）从 ~66dp 掉到 ~54dp，收起态再乘 [COLLAPSED_SCALE] 只剩 46dp，
 * 配上加宽到 340dp 的横向尺寸，观感就是"压成一条线"。
 * 用纵向内边距补回高度是唯一不影响药丸形状的杠杆 —— 滑块高度由**单元格**决定，
 * 与本值无关，所以加高胶囊不会把药丸重新撑成圆。
 */
private val CAPSULE_INNER_VPAD: Dp = 13.dp

/**
 * 胶囊**内容层**背景的圆角半径（dp 数值）。
 */
private const val CAPSULE_CORNER: Int = 18

/**
 * 图标尺寸的**回退默认值**。
 */
private val ICON_SIZE: Dp = 24.dp

/**
 * 展开态的自动收起延迟（ms）。
 */
private const val COLLAPSE_DELAY_MS = 1500L

/**
 * 收起态下**整个胶囊**（窗口 + 背景 + 内容）的缩放倍率（展开态恒为 1f）。
 */
private const val COLLAPSED_SCALE = 0.86f

/**
 * 展开进度 → 胶囊整体缩放倍率。
 */
internal fun capsuleScaleOf(progress: Float): Float =
    COLLAPSED_SCALE + (1f - COLLAPSED_SCALE) * progress.coerceIn(0f, 1f)

/**
 * 展开进度弹簧：临界阻尼（无过冲），整段展开/收回约 0.3s。
 *
 * 2026-09-01 曾从 StiffnessMedium 提到 3000f（≈0.15s），理由是"别比页面转场晚到位"。
 * 2026-09-08 回到 900f（≈0.3s）：3000f 让胶囊在 0.15s 内"咚"地长到位，而标签是
 * [LABEL_FADE_IN_MS]=320ms 的 tween —— 一次交互被拆成"胶囊先跳、字后显影"两段，
 * 这正是「文字显示消失太突兀」的来源。现在两条动画的时长同量级，读起来是一段运动。
 * 仍然是 spring（护栏 `EXPAND_SPRING` 必须含字面 `spring(`，防止有人换成 snap）。
 */
private val EXPAND_SPRING: AnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = UfiAnimSpecs.CapsuleExpandStiffness)

/**
 * **收回**进度弹簧（2026-09-23）。刚度比 [EXPAND_SPRING] 高一档，整段收回约 0.2s。
 *
 * 为什么收回要单独一条：收回时格宽跟的是 [labelReveal]（[LABEL_FADE_OUT_MS] = 220ms 走完），
 * 而整体缩放跟的是 expandProgress（展开弹簧 ≈ 300ms）—— 于是宽度已经收到位、缩放还在跑那
 * 最后 80ms，观感就是「收完之后又抖一下」。这正是「收回动画不好看」的来源。
 *
 * 1700f ≈ 0.2s，与标签淡出同量级，两条线同时到位。顺带也符合动效通则：**退出快于进入**
 *（展开是用户在"打开"，值得从容；收回是收尾，拖着只会显得迟滞）。
 * 仍用 `spring(` 而不是 tween：与展开同族，中途反向打断时不会有曲线突变。
 */
private val COLLAPSE_SPRING: AnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1700f)

/**
 * 选中态「显示 / 消失」动画曲线。
 *
 * 2026-09-01 起胶囊不再有「扫荡」：点哪个就是哪个，被点的那个淡入、原来那个淡出，
 * 两者同时进行、各自独立 —— 中间的图标一个都不会被点亮。
 * 200ms 是「看得出在动、又不至于等它」的区间；曲线用标准 FastOutSlowIn，不要弹簧尾巴。
 *
 * 2026-09-03：选中态只剩**着色**（色相 + 不透明度）。原来还叠了一层 1.15× 放大，
 * 图标会顶出着色滑块的边界 —— 有了滑块之后"哪个被选中"已经说得很清楚，放大纯属多余。
 */
private val SELECTION_SPEC: AnimationSpec<Float> = tween(UfiMotion.Duration.Base, easing = UfiMotion.Easing.Standard)

/** 未选中图标的透明度。选中态为 1f，二者之间做渐入渐出。 */
private const val ICON_ALPHA_UNSEL: Float = 0.45f

// ── 选中滑块（着色框）────────────────────────────────────────────────────────

/**
 * 滑块相对 Tab 单元格的横向内缩（左右各一份）。
 *
 * 2026-09-04 由 6dp 收到 2dp：滑块宽 = 单元格宽 - 2×inset。内缩越大药丸越窄、越容易
 * 撞上"圆角取半高 ⇒ 渲染成圆"那条线；2dp 是"还能看出格与格之间有间隙"的下限。
 * 真正决定形状的是宽高比，见 [TAB_MIN_W] 与 [COLLAPSED_ICON_PAD]。
 */
private val INDICATOR_INSET_H: Dp = 2.dp

/**
 * 滑块相对单元格高度的纵向内缩（上下各一份）。
 *
 * 2026-09-04 由 3dp 改为 0：**展开态包不住文字**的原因就在这。
 * 单元格高度 = 图标 + 间距 + 标签（标签始终占位，只有 alpha 在动），也就是说
 * 展开时内容**正好填满**整个单元格；滑块再上下各内缩 3dp，就必然切掉图标顶部与文字底部。
 * 收起态看不出来，是因为那时整组内容被 translationY 下移、可见的只有图标，
 * 3dp 的亏空落在空白区里。
 * 现在滑块与单元格等高：展开态完整包裹图标+文字，收起态则是一颗更饱满的药丸。
 * 横向仍保留 [INDICATOR_INSET_H] 的 2dp —— 那是为了让相邻滑块之间留出间隙，与包裹无关。
 *
 * 2026-09-04 第二版：收起态**不再**用满格高，而是在 draw 阶段按 [COLLAPSED_ICON_PAD]
 * 夹到"图标 + 呼吸位"（见 CapsuleSelectionSlider）；本常量只决定**展开态**的满格内缩，保持 0。
 */
private val INDICATOR_INSET_V: Dp = 0.dp

/**
 * 滑块底色不透明度：**实心 accent 药丸**的上缘值，下缘取 [INDICATOR_FILL_BOTTOM]。
 *
 * 2026-09-03 第三版：前两版都是 accent 的 16%~20% 淡底，在浅色胶囊上"糊"成一片、
 * 边界读不出来，所以怎么调都不好看。改成主流做法（M3 NavigationBar / iOS 分段控件）：
 * 滑块吃满 accent，选中的图标与文字反白（[ThemePalette.onAccent]）——
 * 对比度一步到位，选中态一眼可辨。
 */
private const val INDICATOR_FILL_TOP: Float = 1f

/** 滑块底色下缘不透明度：比上缘略低一档，给药丸一点厚度，避免纯平涂。 */
private const val INDICATOR_FILL_BOTTOM: Float = 0.90f

/*
 * ★ 已删除（2026-09-24，迁移阶段 2.4）：`INDICATOR_DRAG_TINT = 0.12f` ★
 * 抓住滑块拖动时叠在药丸上的一层白（"抓住了"的确认感）。唯一消费者是
 * `CapsuleSelectionSlider` 的 dragBoost 分支，随横向拖拽整套一起退休，见计划 §5.2。
 */

/**
 * 滑块的滑动弹簧。用全站统一的「指示条滑动」档位 —— 与 UfiScrollableTabRow 的下划线同源。
 *
 * ## 使用范围
 * 只剩**一个**调用点：`settleIndex != null` 时「钉到目标格」那一下（C2 分支）。
 *
 * 2026-09-24 前它的触发入口是**拖拽松手**——一次手势释放，弹簧的速度连续性正是它要的
 * 手感（把滑块当被甩出去的实体）。横向拖拽删除后（§5.2），同一个分支改由**点击**进入
 * （点击跨多页同样触发 pager 逐页扫场，见 `settleIndex` 的说明）。
 *
 * ⚠ 这意味着点击路径的滑块动画曲线从 [indicatorSettleSpec]（与页面转场同源的 tween）
 *   变成了本弹簧 —— 计划 §7-2.4 只交代了「把赋值时机从松手改到点击」，没有交代这条曲线
 *   该跟着换成哪一条。**未自行拍板**，留待 2.5/2.6 收尾时与真机观感一起决策。
 *
 * ⚠ 常规归位（横滑落定 / 无 settleIndex 的点击）仍**不**用它，用 [indicatorSettleSpec]。
 */
private val INDICATOR_SPEC: AnimationSpec<Float> = UfiMotion.tabSlider()

/**
 * 滑块**常规归位**的动画 spec —— 与页面切换走**同一条时间轴**（2026-09-05）。
 *
 * ## 为什么必须同源
 * 页面走的是 `tween(navTransitionMs, CubicBezierEasing(0.4, 0, 0.2, 1))`
 * （`MainNavGraph` 传给 `UfiPageSwitcher` 的 `animationSpec`，默认 380ms），
 * 而滑块原来走 `spring(0.8, 500)`：两者**时长不同、曲线不同、结束时刻也不同**。
 * 点一下 Tab，页面和滑块各跑各的，用户感知到的就是「胶囊不跟手 / 没有即点即达」。
 *
 * 现在两边同源：
 * - 时长：同一个 [ufiNavTransitionDurationMs]（用户在「设置 → 外观 → 转场时长」里调的那个数，
 *   并与系统「降低动效」合并），`coerceAtLeast(1)` 与 `MainNavGraph` 逐字一致
 *   —— 关闭档（0）时 tween 的时长必须合法；
 * - 曲线：[UfiMotion.Easing.Standard]（FastOutSlowIn）就是 `CubicBezierEasing(0.4, 0, 0.2, 1)`，
 *   `Navigation.kt` 的二级页转场（`sharedAxisSpec`）也用它，全站三处同一条曲线。
 *
 * 不写裸字面量：改转场时长时滑块自动跟上，不会再出现「页面 380、滑块 500 刚度」这种失联。
 */
internal fun indicatorSettleSpec(navTransitionMs: Int): AnimationSpec<Float> =
    tween(durationMillis = navTransitionMs.coerceAtLeast(1), easing = UfiMotion.Easing.Standard)

/**
 * "不滑不拖"确认时长（2026-09-04）。
 *
 * 页面拖动收尾时 `isScrolling` 会短暂抖成 false，若立刻归位就会与随后的连续进度打架
 * （表现为滑块抽搐）。等这么久仍然静止才归位；期间恢复滑动则归位自动取消。
 * 约 5 帧，肉眼察觉不到延迟。
 *
 * 2026-09-24（迁移阶段 2.7）：`private` → `internal`，**仅可见性**。
 * `UfiBottomDock` 的归位也要付同一个确认窗，两处各写一个 80L 迟早漂移。
 */
internal const val IDLE_SETTLE_CONFIRM_MS = 80L

/**
 * 归位时「已经到位」的判定阈值（下标空间）。
 *
 * 页面手势收尾时滑块往往已停在离整格不到 1% 的位置，此时再跑一次弹簧毫无意义，
 * 却会因为弹簧带初速而多晃一下。差距小于本值直接 snap。
 *
 * 2026-09-24（迁移阶段 2.7）：`private` → `internal`，**仅可见性**。[UfiBottomDock] 的
 * 归位分支用同一道阈值 —— 它与 `isScrolling` 的 0.01f 判据是一对（本值必须更宽），
 * 两处各写一份数字就会把这层关系拆散。
 */
internal const val SETTLE_SNAP_EPSILON: Float = 0.02f

/**
 * 「等权威追上」的最长等待时长（2026-09-05 第二版）。
 *
 * ## 为什么需要上限
 * 归位分支要等「Pager 连续进度取整 == 宿主下标」才动手（见 [awaitSettleAuthority]）。
 * 正常路径这个条件几乎立刻成立或在一次回吐内成立，但**进度本身可能不可信**：
 * 宿主刚重建还没发第一帧、或胶囊窗口在场而宿主树已被 `NavHost` 销毁（进过二级页），
 * 此时 `capsuleSelectionProgressState` 停在过期值上，条件可能**永远**不成立。
 * 没有上限就会复活 2026-09-05 修过的老 bug：滑块与图标着色永久锁死在旧格子。
 *
 * ## 为什么取这个值
 * `UfiMotion.Duration.Sweeping`(320ms) 是页面转场的默认时长 —— 一次手势 settle 从
 * 抬手到 `isScrollInProgress` 落下的上界；再加一个确认窗 [IDLE_SETTLE_CONFIRM_MS]，
 * 就覆盖了「settle 跑满全程 + 回吐 + 重组」这条最慢的正常链路。超过它仍未一致，
 * 只可能是进度不可信，此时按唯一真相源（宿主下标）归位才是正确行为。
 * 不写裸字面量：值随全站转场时长梯度一起走，改了转场时长这里自动跟上。
 *
 * 2026-09-24（迁移阶段 2.7）：`private` → `internal`，**仅可见性**。
 * [UfiBottomDock] 的 `awaitDockSettleAuthority` 用同一个上限（同一条兜底红线）。
 */
internal const val AUTHORITY_CATCHUP_TIMEOUT_MS: Long =
    UfiMotion.Duration.Sweeping.toLong() + IDLE_SETTLE_CONFIRM_MS

/**
 * 归位目标：**恒取宿主的选中下标**（[latestIndex]），即驱动 Pager 的那一份真相。
 *
 * ## 为什么不能拿 pager 的连续进度当权威（2026-09-05 回归修复）
 * 上一版写的是「优先取 `selectionProgressProvider()` 的实时值，NaN 才退回下标」，
 * 动机是躲开「拿 effect 重启那一刻捕获的旧下标把滑块往回拽」的那下抽搐。
 * 但它引入了**第二个真相源**：
 * - 页面渲染哪一页，由 `MainNavGraph.mainTabIndex` → `pagerState` 决定；
 * - 胶囊亮哪一格、滑块停哪一格，由 `capsuleSelectionProgressState` 决定。
 *
 * 而后者只在 **Tab 宿主处于组合中**时才被 `PagerBackend` 的发射器刷新 —— 一旦进过二级页
 * （宿主整棵树被 `NavHost` 销毁），它就停在旧值上；此时任何一次归位都会把**旧下标**
 * 锁进滑块与图标着色，而本 effect 的 key 里没有它，不会自愈。真机现象正是
 * 「胶囊显示 a、实际是 b」。
 *
 * 抽搐问题改由**读最新值**解决：调用点先走 [awaitSettleAuthority]（等权威追上 + 防抖确认窗），
 * 再从 `rememberUpdatedState` 里取**此刻**的下标 —— 不存在旧捕获，也就没有回拽。
 */
internal fun settledIndexOf(latestIndex: Int, lastIndex: Int): Int =
    latestIndex.coerceIn(0, lastIndex)

/**
 * 「权威下标是否已经反映了这次手势的结果」判据（纯函数，可单测）。
 *
 * ## 存在的意义（2026-09-05 第二版：横滑切页时滑块往回抽一下）
 * 胶囊里有**两个「何时算滑完」的时钟**，它们不同步：
 * - 胶囊自己判"不滑了"，看的是连续进度是否落进整数 ±0.01 带（见 `isScrolling` 派生），
 *   这一刻 **Pager 的 settle 动画还没结束**；
 * - 而 `mainTabIndex` 的回吐被硬性要求在 `pagerState.isScrollInProgress == false`
 *   **之后**才执行（见 `UfiPageSwitcherHost` 的手势回吐 effect），再加一次
 *   `MainNavGraph` → Dialog 子树重组才抵达胶囊。
 *
 * 两者间隔常常超过 [IDLE_SETTLE_CONFIRM_MS]，于是归位分支会在 `latestIndex` 还是**旧格**时
 * 启动 `animateTo(旧格)` —— 滑块先到新格、再被拽回旧格、回吐抵达后又滑回新格，
 * 就是用户看到的"往回抽一下"。图标着色走同一套驱动，所以也跟着抽。
 *
 * 修法不是加长防抖，而是**加一个前提**：归位只能在权威已经追上之后执行。
 * 判据就是本函数 —— 进度取整与权威下标一致。
 *
 * ## NaN 必须立刻放行（红线）
 * `LocalUfiCapsuleSelectionProgress` 的默认值是 `{ Float.NaN }`（见 `MainTabController`），
 * 也就是"宿主没挂载 / 没发布过进度"。这种情形下等下去永远等不到，
 * 必然复活 2026-09-05 修过的「进过二级页面后胶囊永久锁死在旧格子」。
 * 所以 NaN 一律视为**已追上**：直接按唯一真相源（宿主下标）归位，零等待。
 *
 * 注意本函数只回答「什么时候可以归位」，**不参与决定归位到哪一格** ——
 * 目标恒由 [settledIndexOf] 取自宿主下标，进度永远不是第二个真相源。
 */
internal fun authorityCaughtUp(progress: Float, latestIndex: Int): Boolean =
    progress.isNaN() || progress.roundToInt() == latestIndex

/**
 * 「这次归位要不要付防抖确认窗」判据（纯函数，可单测）。
 *
 * ## 防抖窗的真实用途（2026-09-05 收窄）
 * [IDLE_SETTLE_CONFIRM_MS] 存在的唯一理由是：**手势收尾阶段** `isScrolling` 会在整数带
 * 附近抖成 false，立刻归位就会与随后恢复的连续进度打架（滑块抽搐）。
 * 也就是说它只对「刚从滑动 / 拖拽状态退出」的归位有意义。
 *
 * 而**点击路径根本不存在中途态**：点击时宿主会把 `capsuleProgress.snapTo(目标)`
 * （见 `UfiPageSwitcherHost` 的驱动循环），胶囊这边 `isScrolling` 全程为 false、
 * [authorityCaughtUp] 在**当帧**就成立。让这条路径白等 80ms，用户感知就是"点了不跟手"。
 *
 * @param followsMotion             本次归位之前是否发生过滑动 / 拖拽（点击会清掉该标记）。
 * @param authorityCaughtUpAtEntry  进入归位分支的**当帧**权威是否已经追上。
 * @return `true` = 需要 `delay(IDLE_SETTLE_CONFIRM_MS)`。
 *   「等过权威」也算需要（`!authorityCaughtUpAtEntry`）：那说明这次归位确实处在
 *   "回吐还在路上"的中途态里，与手势收尾同一类，防抖窗照付。
 */
internal fun settleNeedsConfirmWindow(
    followsMotion: Boolean,
    authorityCaughtUpAtEntry: Boolean,
): Boolean = followsMotion || !authorityCaughtUpAtEntry

/**
 * 两条归位驱动（滑块位置 / 图标着色）的一组判据快照（2026-09-05 第四版）。
 *
 * ## 存在的唯一理由：把这些输入的读取从**组合期**挪进协程
 * 它们原来直接当 `LaunchedEffect` 的 key（`safeIndex, isScrolling, settleIndex`），
 * 也就是组合期读。横滑一次 `isScrolling` 必然翻转两下（起手 false→true、落定 true→false），
 * 每一下都让 [UfiCapsuleTabBar] **整体重组**；而这个 composable 活在独立 Dialog 窗口 +
 * 跨窗口渲染里，一次重组要重测量、重绘、再提交那个窗口。两下恰好落在 settle 前后 ——
 * 也就是屏幕上还有可见运动（滑块 / 图标着色归位）的那段窗口里 ⇒ 肉眼可见的 hitch。
 * 点击路径 `isScrolling` 全程为 false（宿主直接 `capsuleProgress.snapTo(target)`，
 * 进度不离整数带），所以这两次重组**只存在于横滑路径** —— 正是「点击不顿、横滑顿」的差异之一。
 *
 * ## 为什么等价（与上一轮 `PagerDriveProbe` 同构）
 * - `data class` 的 `equals` + `distinctUntilChanged()` ≡ 原来的「key 是否变化」；
 * - `collectLatest` 的「新值到达即取消上一次 body」≡ 原来的「key 变了就取消并重启 effect」。
 *
 * ⚠ **不要**把 `selectionProgressProvider()` 加进来：它每帧都变，会把本流变成每帧一次的
 * 发射源（原实现也没把它当 key，只在分支体内用嵌套 `snapshotFlow` 现读）。
 *
 * ★ 已删除字段（2026-09-24，迁移阶段 2.4）：`dragging`。它的唯一来源是横向拖拽的
 *   `isDragging`，拖拽整套已移除（§5.2），于是这个判据恒为 false。
 *
 * @param index 权威下标（`latestIndex.value`，即 `mainTabIndex` 那一份唯一真相）。
 */
private data class CapsuleSettleProbe(
    val index: Int,
    val scrolling: Boolean,
    val settleIndex: Int?,
)

/**
 * 「展开态自动收起」计时器的判据快照（2026-09-05 第四版，理由同 [CapsuleSettleProbe]）。
 *
 * `resetToken` 只用于「重新计时」：它单调递增，两次自增被 `snapshotFlow` 的同帧合并成一次
 * 发射也不改变语义 —— `collectLatest` 收到任何新值都会重启 `delay(COLLAPSE_DELAY_MS)`。
 *
 * ★ 已删除字段（2026-09-24，迁移阶段 2.4）：`dragging`（原语义「手指正按着滑块拖动期间
 *   不许自动收起」）。来源 `isDragging` 随横向拖拽一起移除，判据恒为 false。
 *   收起/展开状态机本身属于阶段 3，本批不动。
 */
private data class CapsuleCollapseProbe(
    val expanded: Boolean,
    val resetToken: Int,
    val scrolling: Boolean,
)

/**
 * 归位前的统一等待：先等权威追上（带超时兜底），必要时再走一个防抖确认窗。
 *
 * 滑块位置与图标着色两条归位驱动**必须共用**这一段，否则两者的归位时机重新错开，
 * 又会出现"滑块已到位、图标还在抽"这种半修好的状态。
 *
 * ## 三条兜底路径（缺一条就会锁死胶囊，见 [authorityCaughtUp] 与 [AUTHORITY_CATCHUP_TIMEOUT_MS]）
 * 1. 进度是 `NaN`（宿主未挂载 / 从未发布）—— [authorityCaughtUp] 立刻返回 true，零等待；
 * 2. 进度是**过期值**（宿主刚重建、还没发第一帧）—— 新宿主发出第一帧即自动一致；
 *    真的一直不一致时由第 3 条收尾；
 * 3. 超时（[AUTHORITY_CATCHUP_TIMEOUT_MS]）—— 放弃等待，直接归位。
 *
 * 三条路径的终点完全相同：按 `latestIndex` 归位。等待只影响**时机**，不影响**目标**，
 * 所以无论走哪一条，胶囊都不可能停在与宿主不符的格子上。
 *
 * ## 防抖窗是**有条件**的（2026-09-05 第三版：即点即达）
 * 判据见 [settleNeedsConfirmWindow]。要点：这不会让「滑块滑动后往回抽」复活 ——
 * 那个 bug 的成因是**归位时读到陈旧的 `latestIndex`**，真正的修复是"等权威追上"
 * （本函数第一段，一字未动）；防抖窗只是附带的手势收尾保护，而它在**手势路径上照旧全额支付**
 * （`followsMotion` 为真）。被省掉的只有"当帧权威就已一致、且此前不在滑动中"的点击路径，
 * 该路径下不存在旧下标可读（`latestIndex` 已是点击写入的新值），也不存在会抖动的 `isScrolling`。
 */
private suspend fun awaitSettleAuthority(
    latestIndex: State<Int>,
    selectionProgressProvider: () -> Float,
    followsMotion: Boolean,
) {
    // 当帧先判一次：点击路径这里就已成立，不进流、不等待。
    val caughtUpAtEntry: Boolean = authorityCaughtUp(selectionProgressProvider(), latestIndex.value)
    if (!caughtUpAtEntry) {
        withTimeoutOrNull(AUTHORITY_CATCHUP_TIMEOUT_MS) {
            snapshotFlow { authorityCaughtUp(selectionProgressProvider(), latestIndex.value) }
                .first { it }
        }
    }
    // 手势收尾阶段 isScrolling 会在整数带附近抖动，这段时间里若恢复滑动，
    // 本 effect 会被重启、归位自动取消。点击路径不存在该抖动，直接跳过（零延迟）。
    if (settleNeedsConfirmWindow(followsMotion, caughtUpAtEntry)) {
        delay(IDLE_SETTLE_CONFIRM_MS)
    }
}

/**
 * 在给定约束下，单个 Tab **最多**能占多宽（px）。宽度无界时返回 [Int.MAX_VALUE]（不设限）。
 *
 * 存在的意义是防裁切：胶囊活在 wrap-content 的 Dialog 窗口里，内容比屏幕宽就直接被切掉，
 * 而本组件的 `Layout` 原来完全无视入参约束。格宽从 54dp 调到 62dp 后余量变小，加这道闸门。
 */
internal fun availableTabPx(constraints: Constraints, tabCount: Int, padPx: Int, gapPx: Int): Int {
    if (!constraints.hasBoundedWidth || tabCount <= 0) return Int.MAX_VALUE
    val usable = constraints.maxWidth - padPx * 2 - gapPx * (tabCount - 1)
    return (usable / tabCount).coerceAtLeast(1)
}

/**
 * 收起态滑块上下各留给图标的呼吸位（2026-09-04 第二版）。
 *
 * 单元格高度恒为「图标 + 间距 + 标签」（标签始终占位，只有 alpha 在动），
 * 收起态可见的只有图标，滑块若仍取满格高就是一颗比图标高出一大截的药丸 ——
 * 用户说的「静默状态下无法居中包裹」正是这个：几何上确实居中，但根本没在"包"图标。
 * 所以收起态把滑块夹到 `图标高 + 2×本值`，展开态再张开到满格（见 sliderCollapsedInsetPx）。
 */
private val COLLAPSED_ICON_PAD: Dp = 5.dp

// ── 标签淡入淡出 ────────────────────────────────────────────────────────────

/**
 * 标签淡入 / 淡出时长与起始延迟（2026-09-04 第二版）。
 *
 * 为什么不再挂在 `expandProgress` 上：那条弹簧的刚度是
 * [UfiAnimSpecs.CapsuleExpandStiffness] = 3000f（整段展开 ≈ 0.15~0.2s，刻意压得比
 * 页面转场更快），文字若跟着它走、又只分到后 65% 的行程，实际淡入不到 100ms ——
 * 观感就是"字直接出现"，用户反馈的「太快、没有渐入渐出」就是这么来的。
 * 现在文字有自己的 tween：胶囊先长大（[LABEL_FADE_IN_DELAY_MS]），文字再从容淡入；
 * 收起时不延迟、并且比淡入更短，先把字撤干净再收胶囊。
 * 时长写死在本文件而不走 [UfiMotion.Duration]：这是"标签揭示"独有的节奏，
 * 与页面转场/按压反馈不同源，绑到公共档位反而会被别处的调参带跑。
 *
 * 2026-09-04 第三版（「还是很生硬」）：曲线从 Standard(FastOutSlowIn) 换成 M3 emphasized ——
 * 淡入用 EmphasizedIn（极速起步 + 长尾减速，收尾几乎察觉不到"停"），
 * 淡出用 EmphasizedOut（先慢后快，字像被吸走而不是被关掉）。
 * 真正让它显得硬的还有第二处：图标那一组的位移原来跟着**展开弹簧**（~0.15s）走，
 * 文字淡入 300ms 却在旁边慢慢显影，两者不同步 —— 现在两者共用同一个 labelReveal 进度。
 */
private const val LABEL_FADE_IN_MS: Int = 320
private const val LABEL_FADE_IN_DELAY_MS: Int = 40
private const val LABEL_FADE_OUT_MS: Int = 220

/**
 * 标签淡入时的上浮距离（2026-09-04）。
 *
 * **必须是固定 dp**，不能取自 `onSizeChanged` 的文字高度：那个值首帧为 0，
 * 会让文字在淡入过程中被顶一下（这正是 2026-09-03 把位移整个删掉的原因）。
 * 6dp 足够给出方向感，又不会和胶囊整体缩放叠成明显形变。
 *
 * 2026-09-04 第三版收到 3dp：图标那一组现在也跟着 labelReveal 平移（约半个标签高 ≈ 7dp），
 * 文字若再自带 6dp，两段位移叠起来就是 13dp 的"窜动"。3dp 只作为文字相对整组的一点延迟感。
 */
private val LABEL_RISE: Dp = 3.dp

/**
 * 标签淡入时的起始缩放（2026-09-08）。
 *
 * 只做 alpha 时，10sp 的小字在人眼里近似"有 / 无"两态 —— 用户说的"突兀"有一半来自这里。
 * 0.92 是"看得出在长大、又不至于变形"的一档：字高 10sp ≈ 13px，8% 只有 1px 左右的尺度变化，
 * 不会与胶囊整体缩放叠成明显形变（那正是 2026-09-03 撤掉图标独立缩放的原因）。
 */
private const val LABEL_SCALE_FROM: Float = 0.92f

/**
 * 收起态单个 Tab 的格宽（2026-09-08）。
 *
 * 展开态格宽是 [TAB_MIN_W]（62dp，要容下"仪表盘"这类三字标签）；收起态只剩一个 26dp 图标，
 * 62dp 的格子让整条胶囊白占 ~340dp，观感是"一条横贯屏幕的长条"。收起时按本值把格宽收窄，
 * 整条约 2×[PAD] + 5×46 + 4×[GAP] ≈ 260dp。
 *
 * 为什么不是"图标宽 + 内边距"（36dp）：格宽同时是**点击目标**宽度，且收起态还要再乘
 * [COLLAPSED_SCALE]（0.86）。46dp × 0.86 ≈ 40dp 是仍可稳定命中的下限；36dp 会掉到 31dp。
 *
 * 插值进度用的是 [labelReveal] 而不是展开弹簧：格宽变化必须与标签的显隐同步，
 * 否则字还在淡出、格子已经收窄，文字会被横向裁掉一半。
 */
private val COLLAPSED_TAB_WIDTH: Dp = 46.dp

/**
 * 收起态胶囊底色的不透明度系数（2026-09-04）。
 *
 * 收起时胶囊只剩一排图标、体积最小，此时让它更透一点，浮在内容之上不抢戏；
 * 展开时回到全不透明，保证文字可读。系数乘在 [capsuleSurfaceColor] 的 alpha 上，
 * 且只走 `drawBehind`（不是整层 alpha）—— 否则图标和滑块会跟着一起变透。
 */
private const val CAPSULE_SURFACE_ALPHA_COLLAPSED: Float = 0.82f

/**
 * 胶囊**内容层**的底色（分浅色 / 深色两套）。
 *
 * 2026-09-03（P1c）：浅色分支原本写死 `Color.White.copy(alpha = 0.90f)`，深色分支才读
 * `palette.cardBg` —— 于是整体换配色时，深色下的胶囊会跟着卡片色变，浅色下永远是那块白，
 * 与新配色的卡片/页面并排时明显是"上一版主题的残影"（暖白 vs 冷白尤其刺眼）。
 * 现在两个分支统一读 [ResolvedPalette.cardBg]，只保留各自的 alpha 差异
 * （深 0.92 / 浅 0.90，这是磨砂通透度的产品参数，与配色无关，故不动）。
 *
 * 2026-09-05：明暗判据由 `isSystemInDarkTheme()` 改为 [ResolvedPalette.isDark]。
 * 前者**绕过了「设置 → 外观 → 外观模式」**：用户强制浅色时，深色系统下的胶囊底色
 * 仍按 0.92 走，而 cardBg 已经是浅色的那套 —— 两个来源打架，表现为胶囊通透度与
 * 页面明暗不一致。`isDark` 是 `UFIAXISTheme(darkTheme = …)` 解析出来的那一个真值，
 * 与页面、卡片、文字色同源。
 */
@Composable
private fun capsuleSurfaceColor(): Color {
    val palette = LocalResolvedPalette.current
    return if (palette.isDark) {
        palette.cardBg.copy(alpha = 0.92f)
    } else {
        palette.cardBg.copy(alpha = 0.90f)
    }
}

/**
 * 底色竖向渐变里「顶部 alpha 相对底部」的比例（2026-09-23）。
 *
 * 玻璃有厚度：光从上方进来，上缘更透、下缘更实。0.70 是"看得出分层但不至于上缘露底"的值。
 * 刻意做成**相对比例**而不是第二个绝对 alpha —— 通透度与配色仍由 [capsuleSurfaceColor]
 * 一处掌管，这里只负责分层，调参不会变成两个数打架。
 */
private const val CAPSULE_SURFACE_ALPHA_TOP_RATIO: Float = 0.70f

/** 上缘高光的收束位置：到 42% 高度就完全透明，下半截必须干净，否则整条会发白发灰。 */
private const val CAPSULE_SHEEN_STOP: Float = 0.42f

/** 噪点强度。0.05 已经够去塑料感；再高在纯色页面上会看出"脏"。 */
private const val CAPSULE_NOISE_ALPHA: Float = 0.05f

/**
 * 跟随选中滑块的**局部高光**峰值 alpha（2026-09-23）。
 *
 * 静态的竖向高光看久了是一块死渐变。真玻璃的反光会随视角/物体移动而移动，所以让亮斑中心
 * 跟着当前选中格走：切 tab 时高光会滑过去，与交互同源，不是为动而动的呼吸灯。
 * 浅色 0.13 / 深色 0.07 —— 同样的白在深底上提亮幅度大得多。
 */
private const val CAPSULE_SHEEN_SPOT_ALPHA_LIGHT: Float = 0.13f
private const val CAPSULE_SHEEN_SPOT_ALPHA_DARK: Float = 0.07f

/** 局部亮斑的半径（相对胶囊宽度）。0.42 ≈ 一格半，够柔和不至于糊成整条。 */
private const val CAPSULE_SHEEN_SPOT_RADIUS_RATIO: Float = 0.42f

/**
 * 展开瞬间的一次性**扫光**（2026-09-23）：一道亮带从左掠到右。
 *
 * 只在展开时跑一次，收回不跑 —— 收回本来就要"快、干脆"，再来一道光是画蛇添足。
 * 420ms 略长于展开弹簧（≈300ms），所以光会在胶囊定型后才扫完，读起来是"玻璃被点亮"
 * 而不是和形变混在一起。
 */
private const val CAPSULE_SWEEP_MS: Int = 420
private const val CAPSULE_SWEEP_ALPHA_LIGHT: Float = 0.20f
private const val CAPSULE_SWEEP_ALPHA_DARK: Float = 0.11f

/** 扫光亮带的半宽（相对胶囊宽度）。 */
private const val CAPSULE_SWEEP_HALF_WIDTH: Float = 0.16f

/**
 * 上缘高光色（2026-09-23）。
 *
 * 深色主题下要明显弱一些：同样的白在深底上提亮幅度大得多，0.26 会让胶囊上缘发灰发脏。
 */
@Composable
private fun capsuleSheenColor(): Color {
    val palette = LocalResolvedPalette.current
    return if (palette.isDark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.26f)
}

/**
 * 胶囊描边（2026-09-23）：竖向渐变取代原来的均匀 `NeutralOutline@0.40`。
 *
 * 上缘亮（受光）→ 中段几乎消失 → 下缘回到中性描边色。均匀描边在浅色页面上像"贴纸边"，
 * 这是本次要去掉的主要观感问题之一。
 */
@Composable
private fun capsuleStrokeBrush(): Brush {
    val palette = LocalResolvedPalette.current
    val top = if (palette.isDark) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.58f)
    return Brush.verticalGradient(
        0f to top,
        0.45f to Color.White.copy(alpha = 0.04f),
        1f to NeutralOutline.copy(alpha = 0.32f)
    )
}

/**
 * 噪点画刷（2026-09-23）。进程内只生成一次，所有胶囊共享。
 *
 * 为什么运行时生成而不是放一张 PNG：省掉一个二进制资源和它的 dpi 变体，
 * 也避免有人误改分辨率导致颗粒尺寸跟着屏幕密度变。48×48 足够，`TileMode.Repeated` 铺开后
 * 看不出周期性。
 *
 * 噪声刻意围绕**中灰 128** 抖动而不是纯黑白：配 `BlendMode.Overlay` 时中灰是恒等值，
 * 所以这一层只加颗粒、几乎不改底色亮度。固定随机种子是为了让同一版本的纹理稳定可复现
 *（截图比对不会每次都差一点）。
 */
private val CAPSULE_NOISE_BRUSH: Brush by lazy {
    val size = 48
    val pixels = IntArray(size * size)
    val random = java.util.Random(20260923L)
    for (i in pixels.indices) {
        val v = (128 + random.nextInt(65) - 32).coerceIn(0, 255)
        pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bitmap = android.graphics.Bitmap.createBitmap(
        pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888
    )
    ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/**
 * 悬浮胶囊底部导航栏。
 */
@Composable
fun UfiCapsuleTabBar(
    tabs: List<CapsuleTabItem>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return

    // 越界保护：宿主传入的下标可能因状态恢复而临时越界。
    val safeIndex: Int = selectedIndex.coerceIn(0, tabs.lastIndex)

    // ★ 归位驱动读的「最新选中下标」。
    //   两条归位 effect（滑块 / 图标着色）都要在 delay 之后取**此刻**的下标，
    //   而不是 effect 重启那一刻捕获的值 —— 后者会把已经停在新格的滑块往回拽（抽搐）。
    //   rememberUpdatedState 的返回对象身份稳定，读它不会让 effect 重启。
    val latestIndex: State<Int> = rememberUpdatedState(safeIndex)

    // 实时追踪进度：MainNavGraph 提供连续页位置 lambda；未提供时退化为按 selectedIndex。
    // 2026-08-23 优化：推迟到下层读取，本函数体实现零重组。
    val selectionProgressProvider = LocalUfiCapsuleSelectionProgress.current
    
    // 实时追踪·滑动中展开：progress 偏离整数页即「滑动中」。
    //
    // 2026-09-05 第四版（「横滑落定还顿一下（变轻但没消失）」）：从
    // `var isScrolling by remember { mutableStateOf(false) }` 改成**只保留 State 容器**。
    // 旧写法是**组合期读** —— 它同时是三条 effect（自动收起 / 图标着色归位 / 滑块归位）的 key。
    // 详细成因与等价性论证见 [CapsuleSettleProbe] 的 KDoc；一句话：横滑一次它翻转两下，
    // 就是 `UfiCapsuleTabBar` 在 settle 前后整体重组两次（跨窗口渲染，代价高且正好可见）。
    // 现在所有读取都推迟到协程内（`snapshotFlow { isScrollingState.value }`）。
    val isScrollingState: MutableState<Boolean> = remember { mutableStateOf(false) }
    // ── 「本次归位是否紧跟一段滑动」──────────────────────────────────────────────
    //
    // 只有这种归位需要付 [IDLE_SETTLE_CONFIRM_MS] 防抖窗（手势收尾时 isScrolling 会抖）；
    // 点击路径不存在中途态，必须零延迟，否则就是用户说的"不跟手 / 没有即点即达"。
    // 写入点恰好两处，覆盖全部入口：
    //   - 页面横滑（本 effect，进度离开整数带）→ true
    //   - 点 Tab（CapsuleTab.onClick）→ false
    // （2026-09-24 前还有第三处「抓住滑块拖动 onDragStarted → true」，随横向拖拽一起删除，§5.2。）
    // 刻意用快照状态而不是"可消费的一次性标记"：滑块位置与图标着色两条归位驱动都要读它，
    // 一次性标记会被先跑到的那条消费掉，另一条读到 false ⇒ 两者错开 80ms（半修好状态）。
    var motionSettlePending by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { 
            val p = selectionProgressProvider()
            if (p.isNaN()) 0f else abs(p - p.roundToInt())
        }.collect { offset ->
            val scrolling = offset > 0.01f
            isScrollingState.value = scrolling
            if (scrolling) motionSettlePending = true
        }
    }

    val appContext = LocalContext.current.applicationContext
    val themeManager = remember { ThemeManager(appContext, observeExternal = true) }
    // 反注册 prefs 监听：不解的话本栏销毁后监听仍挂在进程级 SharedPreferences 上。
    DisposableEffect(themeManager) { onDispose { themeManager.dispose() } }
    val iconSizeDp by themeManager.capsuleIconSizeDp.collectAsState()
    val labelTextSp by themeManager.capsuleLabelTextSp.collectAsState()
    val cornerDp by themeManager.capsuleCornerDp.collectAsState()
    val iconTextSpacingDp by themeManager.capsuleIconTextSpacingDp.collectAsState()

    // ── 滑块归位与页面转场同一条时间轴（2026-09-05）──────────────────────────────
    //
    // 时长必须与 `MainNavGraph` 传给 `UfiPageSwitcher` 的那条 tween 完全同源，否则页面到位、
    // 滑块还在跑（或反之），观感就是"胶囊不跟手"。
    // 走本组件**已有**的 ThemeManager（第一手设置源）+ 已有的 `LocalUfiReduceMotion`
    // （系统降低动效），归一化函数也复用 `ufiNavTransitionDurationMs` —— 三个输入与
    // `MainNavGraph` 逐字相同。**刻意不加形参**：`UfiCapsuleTabBar` 的签名被
    // `CapsuleRegressionGuardTest.publicApi_signatures_mustRemainStable` 锁死。
    val rawTransitionDurationMs by themeManager.transitionDurationMs.collectAsState()
    val systemReduceMotion = LocalUfiReduceMotion.current
    val navTransitionMs: Int = ufiNavTransitionDurationMs(rawTransitionDurationMs, systemReduceMotion)
    // rememberUpdatedState：归位 effect 的 key 里没有它（不该因为改了个设置就打断正在跑的归位），
    // 但 effect 内必须读到**此刻**的 spec，不能是首次组合那份。
    val settleSpecState: State<AnimationSpec<Float>> = rememberUpdatedState(
        remember(navTransitionMs) { indicatorSettleSpec(navTransitionMs) }
    )

    val iconSize: Dp = if (iconSizeDp > 0) iconSizeDp.dp else ICON_SIZE
    val labelFontSize: TextUnit = labelTextSp.sp
    val iconTextSpacing: Dp = if (iconTextSpacingDp >= 0) iconTextSpacingDp.dp else 0.dp
    val capsuleCorner: Dp = (if (cornerDp > 0) cornerDp else CAPSULE_CORNER).dp
    val capsuleShape = RoundedCornerShape(capsuleCorner)

    var expanded: Boolean by remember { mutableStateOf(false) }
    var resetToken: Int by remember { mutableIntStateOf(0) }
    // ★ 已删除（2026-09-24，迁移阶段 2.4，计划 §5.2）：`isDragging` 与 `dragPos` ★
    //
    // 它们是「抓住滑块横向拖拽切页」的两个状态：前者标记手指正按着（期间不许自动收起、
    // 滑块不受"静止时弹回目标格"那条驱动管辖），后者是拖动位置（下标空间，可为小数；
    // 手势只写这个**同步**状态，绝不自己碰 Animatable —— 上一版在 onDelta 里
    // `launch{snapTo}`，队列滞后一帧以上，松手时既读到旧位置、又被残留 snapTo 掐掉归位动画）。
    //
    // 为什么删：贴底后栏占满屏幕最底部，左右边缘约 24dp 是系统返回手势热区，拖着切 tab
    // 会被判成返回；传统底栏本来也不支持拖拽。少一套手势少一处冲突，也就不需要引入
    // `setSystemGestureExclusionRects()`。2026-09-23 定稿，见迁移计划 §5.2。
    //
    // ⚠ 但 `settleIndex` **必须留下** —— 它防的不是拖拽，是 pager 的逐页扫场：
    // pager 收到跨多页的切换请求会「逐页扫场」，此期间 selectionProgress 是从**起点**
    // 一路扫过来的；滑块若去跟它，观感就是被拽回原处再追一遍。
    // 2026-09-03 最容易复现的入口是「从仪表盘按住、1s 内快划到我的」，但**点击跨多页
    // 同样会触发**（点第 1 格直接跳第 4 格）。所以拖拽删掉之后它只是换了赋值时机：
    // 从「松手（onDragStopped）」改到「点击（CapsuleTab.onClick）」。
    var settleIndex: Int? by remember { mutableStateOf(null) }
    val expandProgress: Animatable<Float, AnimationVector1D> = remember { Animatable(0f) }

    val hostExpandProgress = LocalCapsuleExpandProgress.current
    val naturalSize = LocalCapsuleNaturalSize.current
    LaunchedEffect(Unit) {
        snapshotFlow { expandProgress.value }.collect { p -> hostExpandProgress.value = p }
    }

    LaunchedEffect(expanded) {
        // 收回单独走 COLLAPSE_SPRING：与标签淡出（220ms）同量级，免得"宽度收完、缩放还在跑"
        expandProgress.animateTo(
            if (expanded) 1f else 0f,
            if (expanded) EXPAND_SPRING else COLLAPSE_SPRING
        )
    }

    // 展开瞬间的一次性扫光。刻意**只在展开时**跑：收回要的是快和干脆。
    // snapTo(0) 而不是让它停在 1：下一次展开要从左边重新扫，否则第二次展开没有光。
    val sheenSweep: Animatable<Float, AnimationVector1D> = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        if (expanded) {
            sheenSweep.snapTo(0f)
            sheenSweep.animateTo(
                1f,
                tween(durationMillis = CAPSULE_SWEEP_MS, easing = UfiMotion.Easing.Standard)
            )
        } else {
            sheenSweep.snapTo(0f)
        }
    }

    // 标签的淡入淡出**独立于**展开弹簧（2026-09-04 第二版）。
    // 挂在 expandProgress 上时，文字的实际淡入不到 100ms（弹簧刚度 3000f 且只分到后 65%
    // 行程），观感是"字直接出现"。拆出来之后：胶囊先长大 → 文字从容淡入并微微上浮；
    // 收起时文字先撤（更短、无延迟）→ 胶囊再收。两条动画各自可打断、可反向。
    val labelReveal: Animatable<Float, AnimationVector1D> = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        if (expanded) {
            labelReveal.animateTo(
                1f,
                tween(
                    durationMillis = LABEL_FADE_IN_MS,
                    delayMillis = LABEL_FADE_IN_DELAY_MS,
                    easing = UfiMotion.Easing.EmphasizedIn
                )
            )
        } else {
            labelReveal.animateTo(
                0f,
                tween(durationMillis = LABEL_FADE_OUT_MS, easing = UfiMotion.Easing.EmphasizedOut)
            )
        }
    }

    // 展开态自动收起：不滑、静置 COLLAPSE_DELAY_MS 后收起。
    // 2026-09-05 第四版：判据改走 [CapsuleCollapseProbe]（协程内读），
    // `isScrolling` 不再出现在 effect 的 key 里。语义等价：
    // distinctUntilChanged ≡「key 是否变化」，collectLatest ≡「key 变了就取消重启」。
    // 2026-09-24：判据里的「不拖」一项随横向拖拽删除（§5.2），只剩「不滑」。
    LaunchedEffect(Unit) {
        snapshotFlow {
            CapsuleCollapseProbe(
                expanded = expanded,
                resetToken = resetToken,
                scrolling = isScrollingState.value,
            )
        }
            .distinctUntilChanged()
            .collectLatest { probe ->
                if (probe.expanded && !probe.scrolling) {
                    delay(COLLAPSE_DELAY_MS)
                    expanded = false
                }
            }
    }

    var lastIndex: Int by remember { mutableIntStateOf(safeIndex) }
    LaunchedEffect(safeIndex) {
        if (safeIndex != lastIndex) {
            lastIndex = safeIndex
            expanded = true
            resetToken++
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { isScrollingState.value }.collect { scrolling ->
            if (scrolling) {
                expanded = true
                resetToken++
            }
        }
    }

    // ── 每个 Tab 一份「选中度」──
    //
    // 2026-09-01 删除扫荡动画：原来选中度是从**一个**连续进度算出来的
    // （factor = 1 - |progress - index|），于是跨多页跳转时 progress 扫过沿途每个下标，
    // 中间图标被依次点亮一遍 —— 那就是「拖拉」的观感来源。
    //
    // 现在每个 Tab 各有一份 Animatable，由下面唯一一条驱动决定：
    // - 静止（含点击切页）：被选中的那个 animateTo(1)、其余 animateTo(0) —— 点哪个亮哪个，
    //   中间图标全程不参与，淡入淡出 + 缩放由 [SELECTION_SPEC] 负责；
    // - 手势拖拽中：按连续进度 snapTo 跟手（这一段必须跟手，不能再套一层平滑）。
    val selectionFactors = remember(tabs.size) {
        List(tabs.size) { index -> Animatable(if (index == safeIndex) 1f else 0f) }
    }
    // 2026-09-05 第四版：四条分支的判据改走 [CapsuleSettleProbe]（协程内读），
    // `safeIndex` / `isScrolling` / `settleIndex` 全部离开 effect 的 key。
    // 分支选择顺序与优先级一字未动，仅数据来源从「组合期捕获的 key」换成「探针字段」。
    // 2026-09-24（§5.2）：原来的第一条分支「拖动中 → 着色跟 dragPos 走」随横向拖拽删除，
    // 现在只剩三条（归位中 / 滑动中 / 静止）。
    LaunchedEffect(selectionFactors) {
        snapshotFlow {
            CapsuleSettleProbe(
                index = latestIndex.value,
                scrolling = isScrollingState.value,
                settleIndex = settleIndex,
            )
        }
            .distinctUntilChanged()
            .collectLatest { probe ->
                val settling = probe.settleIndex
                if (settling != null) {
                    // 归位中：直接点亮目标格并保持，不跟 pager 的扫场进度（否则沿途图标被依次点亮）
                    coroutineScope {
                        selectionFactors.forEachIndexed { index, factor ->
                            launch { factor.animateTo(if (index == settling) 1f else 0f, SELECTION_SPEC) }
                        }
                    }
                } else if (probe.scrolling) {
                    snapshotFlow { selectionProgressProvider() }.collect { p ->
                        if (p.isNaN()) return@collect
                        selectionFactors.forEachIndexed { index, factor ->
                            factor.snapTo((1f - abs(p - index)).coerceIn(0f, 1f))
                        }
                    }
                } else {
                    // 静止：点哪个亮哪个。与滑块同一条"确认再归位"的规则（见 indicatorPos 那段），
                    // 否则页面拖动收尾的空帧里图标亮度会跟着来回跳一次。
                    // 归位目标同样取**最新的宿主下标**（唯一真相源）—— 理由见 settledIndexOf。
                    //
                    // 2026-09-05 第二版：与滑块共用 [awaitSettleAuthority]（等权威追上 + 防抖确认窗）。
                    // 只 delay 不等权威时，这里会在 latestIndex 还是旧格时把着色 animateTo 回旧格，
                    // 与滑块同步"抽一下"。两条驱动必须同一套时机，否则只修好一半。
                    //
                    // 2026-09-05 第三版：防抖窗改为**有条件**（点击路径零延迟），
                    // 判据见 [settleNeedsConfirmWindow]；两条驱动读同一份 motionSettlePending，仍然同步。
                    awaitSettleAuthority(latestIndex, selectionProgressProvider, motionSettlePending)
                    val target = settledIndexOf(latestIndex.value, tabs.lastIndex)
                    coroutineScope {
                        selectionFactors.forEachIndexed { index, factor ->
                            val goal = if (index == target) 1f else 0f
                            if (factor.value != goal) {
                                launch { factor.animateTo(goal, SELECTION_SPEC) }
                            }
                        }
                    }
                }
            }
    }

    Box(
        modifier = modifier
            .wrapContentSize(align = Alignment.BottomCenter, unbounded = true)
            .graphicsLayer {
                val scale: Float = capsuleScaleOf(expandProgress.value)
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin.Center
            },
        contentAlignment = Alignment.Center
    ) {
    // ── 选中滑块位置 ──
    //
    // 位置用「下标空间」存（0..lastIndex），换算成像素在 draw / layer 阶段乘上格宽完成 ——
    // 格宽要等测量完才知道，若把像素值塞进 Animatable，每次尺寸变化都会打断正在跑的滑动。
    // 驱动源与图标选中度同一套：拖拽中 snapTo 跟手，静止时弹簧滑到目标格。
    val indicatorPos: Animatable<Float, AnimationVector1D> =
        remember { Animatable(safeIndex.toFloat()) }
    var tabStepPx: Int by remember { mutableIntStateOf(0) }
    // 收起态滑块相对满格高度的上下内缩（px，由测量块写入、draw 阶段读）。
    // 只能在测量里算：它取决于图标高与单元格高，而两者都由 ThemeManager 的可调项决定。
    var sliderCollapsedInsetPx: Float by remember { mutableFloatStateOf(0f) }
    // 2026-09-05 第四版：判据同样改走 [CapsuleSettleProbe]（协程内读），
    // `safeIndex` / `isScrolling` / `settleIndex` 全部离开 effect 的 key。
    // 2026-09-24（§5.2）：原来的第一条分支「拖动中 → 滑块逐帧 snapTo(dragPos)」随横向拖拽删除。
    LaunchedEffect(indicatorPos) {
        snapshotFlow {
            CapsuleSettleProbe(
                index = latestIndex.value,
                scrolling = isScrollingState.value,
                settleIndex = settleIndex,
            )
        }
            .distinctUntilChanged()
            .collectLatest { probe ->
                val settling = probe.settleIndex
                if (settling != null) {
                    // 归位中：钉在目标格，等 pager 真正停在目标页后才交还控制权。
                    // 2026-09-05 第四版：等待条件从「捕获的 safeIndex」换成 `latestIndex.value`
                    // 的**实时读**。旧写法靠 effect 重启来刷新那个捕获值（safeIndex 是 key），
                    // 探针化之后本 body 的闭包不再随组合更新，必须直接读唯一真相源。
                    // 语义等价：两者都在「权威下标 == 目标格且已不在滑动」时才交还控制权。
                    indicatorPos.animateTo(settling.toFloat(), INDICATOR_SPEC)
                    snapshotFlow { latestIndex.value == settling && !isScrollingState.value }.first { it }
                    settleIndex = null
                } else if (probe.scrolling) {
                    snapshotFlow { selectionProgressProvider() }.collect { p ->
                        if (p.isNaN()) return@collect
                        indicatorPos.snapTo(p.coerceIn(0f, tabs.lastIndex.toFloat()))
                    }
                } else {
                    // 归位：任何时候不拖不滑，滑块必须弹回**整格**，绝不允许停在两格中间。
                    //
                    // 2026-09-04（「拖动界面切页时滑块抽搐」）：这里必须先等一小会儿再确认。
                    // 页面拖动的收尾阶段 `isScrolling` 会短暂抖成 false（fling 结束到 settle 开始之间
                    // 有空帧），而 `safeIndex` 也在同一段时间里翻新 —— 判据里含这两者，
                    // 于是本分支被重新选中并立刻执行：滑块被从"跟手位置"猛拽向整格，下一帧
                    // `isScrolling` 又变 true、再 snapTo 回连续进度，来回两下就是肉眼可见的抽搐。
                    // delay 期间只要判据变化（滑动恢复），这次归位就自动取消，什么都不会发生。
                    //
                    // 2026-09-04 第二版（「切换完成后还会往后抽一下」）：只加 delay 不够。
                    // 本分支原来捕获的 `safeIndex` 是 effect **重启那一刻**的组合值，而手势 settle 的
                    // 新下标要等 `onSelectedIndexChange → mainTabIndex → 重组` 才到；新页首帧组合
                    // 一旦超过确认窗（真机上很常见，新 Tab 要建整棵子树），这里就会拿着**旧下标**
                    // 把已经停在新格的滑块往回拽，等新下标到了再拽回来 —— 正是那一下"往后抽搐"。
                    // 修法：归位目标改读 [latestIndex] 的**此刻值**（`rememberUpdatedState`，
                    // 与驱动 Pager 的是同一份 `mainTabIndex`，唯一真相源）；
                    // 并且差距小于 [SETTLE_SNAP_EPSILON] 时直接 snap，不再跑一次没必要的弹簧。
                    //
                    // ⚠ 2026-09-05：这里曾经改读 `selectionProgressProvider()`（pager 连续进度）。
                    //   已回退 —— 那是第二个真相源，进过二级页后它会停在旧值上，把胶囊锁在旧格
                    //   （「胶囊显示 a、实际是 b」）。详见 [settledIndexOf] 的 KDoc。
                    //
                    // 2026-09-05 第二版（「横滑切页时滑块先到目标格、往回抽一下、再回目标」）：
                    // 只 delay 仍然不够 —— 那是"靠等"，而两个判据用的是**不同的时钟**：
                    // 胶囊判"不滑了"看连续进度是否落进整数带（Pager 的 settle 还没结束就成立），
                    // 而 `mainTabIndex` 的回吐被要求在 `isScrollInProgress == false` 之后才发生，
                    // 间隔常 > [IDLE_SETTLE_CONFIRM_MS]。于是本分支会在 `latestIndex` 还是旧格时
                    // `animateTo(旧格)`，等回吐抵达再 `animateTo(新格)` —— 那一下反向动画就是"抽"。
                    //（旁证：[SETTLE_SNAP_EPSILON] 比 isScrolling 的 0.01 带更宽，只要目标是正确那格，
                    //  进来时必然满足 snap 条件、根本不会有动画；能看见反向动画即证明目标是旧格。）
                    // 修法：加一个前提 —— 归位只在**权威已经反映这次手势的结果**之后执行，
                    // 见 [awaitSettleAuthority]（内含 NaN / 过期 / 超时三条兜底与原有防抖确认窗）。
                    //
                    // 2026-09-05 第三版（「胶囊不跟手 / 没有即点即达」）：两处调整，都只影响**点击路径**。
                    // ① 防抖确认窗改为有条件（[settleNeedsConfirmWindow]）：手势收尾照付，点击零延迟；
                    // ② 归位 spec 从 spring(0.8, 500) 换成与页面同源的 [indicatorSettleSpec]
                    //    （同一个 navTransitionMs + 同一条 Standard 曲线）⇒ 滑块与页面同起同落。
                    awaitSettleAuthority(latestIndex, selectionProgressProvider, motionSettlePending)
                    val target = settledIndexOf(latestIndex.value, tabs.lastIndex)
                    val targetF = target.toFloat()
                    if (abs(indicatorPos.value - targetF) <= SETTLE_SNAP_EPSILON) {
                        if (indicatorPos.value != targetF) indicatorPos.snapTo(targetF)
                    } else {
                        indicatorPos.animateTo(targetF, settleSpecState.value)
                    }
                }
            }
    }

    // ★ 已删除（2026-09-24，迁移阶段 2.4，计划 §5.2）：「抓住滑块拖动」整套 ★
    //
    // 删掉的是：`rememberDraggableState { … }`（onDelta：按 `delta / tabStepPx` 累加 dragPos，
    // 并在跨过整格时立刻 `onTabSelected(nearest)` —— 拖到哪一格页面就切到哪一格，
    // 手势阈值交给 draggable 的 touch slop，没过阈值仍是普通点击）、
    // 挂在 Layout 上的 `.draggable(state, Orientation.Horizontal, onDragStarted, onDragStopped)`、
    // 以及「被抓住」的视觉反馈 `dragBoost`（Animatable，跟 isDragging 动到 1，在滑块上叠一层白）
    // 与它用的 `INDICATOR_DRAG_TINT`。
    //
    // 为什么删：贴底通栏之后栏占满屏幕最底部，左右边缘约 24dp 是系统返回手势热区，
    // 拖着切 tab 会被判成返回；而这套拖拽本来是为「可交互悬浮浮层」设计的，
    // 传统底栏并不支持拖拽。少一套手势少一处冲突，也省掉引入
    // `setSystemGestureExclusionRects()` 的必要（代码里至今没有这个 API）。
    //
    // `tabStepPx` **保留**：滑块的像素位移仍然是 `indicatorPos × tabStepPx`（见下方 Layout）。

    // 底色/高光/描边在组合期取一次（drawBehind 里不能调 @Composable）
    val capsuleSurface = capsuleSurfaceColor()
    val capsuleSheen = capsuleSheenColor()
    val capsuleStroke = capsuleStrokeBrush()
    val isDarkPalette = LocalResolvedPalette.current.isDark
    val sheenSpotAlpha =
        if (isDarkPalette) CAPSULE_SHEEN_SPOT_ALPHA_DARK else CAPSULE_SHEEN_SPOT_ALPHA_LIGHT
    val sweepAlpha =
        if (isDarkPalette) CAPSULE_SWEEP_ALPHA_DARK else CAPSULE_SWEEP_ALPHA_LIGHT
    Box(
    
            modifier = Modifier
                .shadow(
                    elevation = 15.dp,
                    shape = capsuleShape,
                    ambientColor = NeutralOutline.copy(alpha = 0.45f),
                    spotColor = NeutralOutline.copy(alpha = 0.45f),
                )
                .clip(capsuleShape)
                // 2026-09-04：底色改为 drawBehind，随展开进度调不透明度
                // （收起更透、展开全实，见 CAPSULE_SURFACE_ALPHA_COLLAPSED）。
                // 不能用整层 alpha —— 那会把图标、文字、滑块一起变透。
                // 已经 clip 成胶囊形，所以这里直接铺满即可。
                //
                // 2026-09-23：单层实色 → 三层「渐变玻璃 + 噪点」。
                // 原来一层平铺的 cardBg 没有厚度感，浅色页面上像贴纸、深色下偏灰。
                // 现在是：底色竖向渐变（上更透、下更实，模拟玻璃厚度）
                //       + 上缘高光（模拟光从上方打进来）
                //       + 极淡噪点（Overlay 混合，去掉"塑料片"感）。
                // 三层都乘同一个展开进度 factor，收起/展开的通透变化与改造前一致。
                // 真模糊不在这里 —— 那条路要么依赖 OEM 的 surface_flinger 开关（不可靠），
                // 要么得把胶囊搬回主窗口配 GraphicsLayer 采样，见 UfiCapsuleBlurHost 的复盘。
                .drawBehind {
                    val base = capsuleSurface
                    val p = expandProgress.value.coerceIn(0f, 1f)
                    val factor = CAPSULE_SURFACE_ALPHA_COLLAPSED +
                        (1f - CAPSULE_SURFACE_ALPHA_COLLAPSED) * p
                    val bottomAlpha = base.alpha * factor
                    // 顶部按比例更透：alpha 的绝对值仍由 capsuleSurfaceColor 统一掌管，
                    // 这里只做相对分层，调通透度/换配色依旧只改那一个函数。
                    val topAlpha = bottomAlpha * CAPSULE_SURFACE_ALPHA_TOP_RATIO
                    drawRect(
                        Brush.verticalGradient(
                            colors = listOf(base.copy(alpha = topAlpha), base.copy(alpha = bottomAlpha))
                        )
                    )
                    // 上缘高光：只覆盖上半部分，下半截必须干净，否则整条会发白发灰
                    drawRect(
                        Brush.verticalGradient(
                            0f to capsuleSheen.copy(alpha = capsuleSheen.alpha * factor),
                            CAPSULE_SHEEN_STOP to Color.Transparent,
                            1f to Color.Transparent
                        )
                    )
                    // 局部亮斑：中心跟着选中格走，切 tab 时高光滑过去（静态渐变看久了是块死光）。
                    // 横向位置用「第几格 / 共几格」近似，不去精确还原 pad+gap —— 亮斑本身很柔，
                    // 差几个 px 看不出来，却省掉把 Layout 的格宽回传到背景层这条耦合。
                    val spotCenterX = size.width * ((indicatorPos.value + 0.5f) / tabs.size.coerceAtLeast(1))
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = sheenSpotAlpha * factor),
                                Color.Transparent
                            ),
                            center = Offset(spotCenterX, size.height * 0.15f),
                            radius = size.width * CAPSULE_SHEEN_SPOT_RADIUS_RATIO
                        )
                    )
                    // 展开时的一次性扫光：亮带从左掠到右，走完即停（sweep 回到 0 就不画）。
                    val sweep = sheenSweep.value
                    if (sweep > 0f && sweep < 1f) {
                        // 中心从 -halfWidth 掠到 1+halfWidth，保证亮带完整进出画面两端
                        val c = -CAPSULE_SWEEP_HALF_WIDTH +
                            sweep * (1f + CAPSULE_SWEEP_HALF_WIDTH * 2f)
                        // 两端淡出：刚出发和即将离场时压暗，否则会看到光"凭空出现/消失"
                        val edgeFade = kotlin.math.sin(sweep * Math.PI).toFloat()
                        drawRect(
                            Brush.horizontalGradient(
                                (c - CAPSULE_SWEEP_HALF_WIDTH).coerceIn(0f, 1f) to Color.Transparent,
                                c.coerceIn(0f, 1f) to Color.White.copy(alpha = sweepAlpha * edgeFade),
                                (c + CAPSULE_SWEEP_HALF_WIDTH).coerceIn(0f, 1f) to Color.Transparent
                            )
                        )
                    }
                    // 噪点：Overlay 混合 + 中灰噪声，所以它只加颗粒、几乎不改亮度
                    // （SrcOver 那样直接盖会把底色整体往灰推）。
                    drawRect(
                        brush = CAPSULE_NOISE_BRUSH,
                        alpha = CAPSULE_NOISE_ALPHA * factor,
                        blendMode = BlendMode.Overlay
                    )
                }
                // 描边也改成竖向渐变：上缘亮（受光）、中段几乎消失、下缘回到中性描边色。
                // 均匀描边在浅色页面上像"贴纸边"，这是这次要去掉的观感之一。
                .border(1.25.dp, capsuleStroke, capsuleShape)
        ) {
            Layout(
                content = {
                    // 第 0 个孩子固定是滑块，后面才是 tabs —— 测量块按这个约定拆分 measurables。
                    CapsuleSelectionSlider(
                        offsetX = { indicatorPos.value * tabStepPx },
                        // 药丸的"张开/收拢"必须跟标签同一条进度：若跟展开弹簧（~0.15s），
                        // 收起时药丸已经缩回图标大小、文字还在淡出，字会掉到药丸外面。
                        revealProgress = { labelReveal.value },
                        collapsedInsetY = { sliderCollapsedInsetPx }
                    )
                    tabs.forEachIndexed { index, tab ->
                        CapsuleTab(
                            tab = tab,
                            // 只读 Animatable 的当前值 —— lambda 在 draw 阶段执行，
                            // 逐帧变化只触发重绘，不触发重组。
                            selectionFactor = { selectionFactors[index].value },
                            isSelected = index == safeIndex,
                            iconSize = iconSize,
                            labelFontSize = labelFontSize,
                            iconTextSpacing = iconTextSpacing,
                            labelReveal = { labelReveal.value },
                            onClick = {
                                // ★ 2026-09-24（迁移阶段 2.4，计划 §5.2）：这里原来是 `settleIndex = null`，
                                //   「钉到目标格」只在拖拽松手（onDragStopped）时才设。拖拽删除后，
                                //   赋值时机搬到**点击**：点第 1 格直接跳第 4 格时 pager 同样会逐页扫场，
                                //   selectionProgress 从起点一路扫过来，滑块若跟着它就是「被拽回原处
                                //   再追一遍」。钉住目标格直到 pager 真的停在目标页（见上面两条归位驱动的
                                //   `settling != null` 分支），观感才是即点即达。
                                settleIndex = index
                                // 点击路径没有"滑动中途态"：显式清掉，让归位跳过防抖确认窗
                                // （即点即达，见 settleNeedsConfirmWindow）。
                                // 若此刻页面其实还在滑，上面那条 isScrolling 收集器会立刻把它重新置真，
                                // 于是照旧付防抖窗 —— 保守方向出错，不会引入抽搐。
                                motionSettlePending = false
                                onTabSelected(index)
                                expanded = true
                                resetToken++
                            }
                        )
                    }
                },
                // ★ 已删除（2026-09-24）：这里原来链着 `.draggable(state = dragState,
                //   orientation = Orientation.Horizontal, enabled = tabs.size > 1,
                //   onDragStarted = { settleIndex = null; dragPos = indicatorPos.value;
                //   isDragging = true; motionSettlePending = true; expanded = true; resetToken++ },
                //   onDragStopped = { …吸附到最近整格、必要时 onTabSelected、settleIndex = target… })`。
                //   理由见上方那段「抓住滑块拖动整套已删除」的说明与计划 §5.2。
                modifier = Modifier
                    .zIndex(1f)
            ) { measurables, constraints ->
                val padPx = PAD.roundToPx()
                val gapPx = GAP.roundToPx()
                val minTabPx = TAB_MIN_W.roundToPx()

                val sliderMeasurable = measurables.first()
                val tabMeasurables = measurables.drop(1)

                val expandedCellPx = tabMeasurables.maxOf {
                    it.maxIntrinsicWidth(Constraints.Infinity)
                }.coerceAtLeast(minTabPx)
                    // 窄屏 / 放大 UI 缩放兜底：格宽加宽到 62dp 后整条约 340dp，
                    // 已经接近 360dp 窄屏的上限。窗口是 wrap-content，超出会被直接裁掉
                    //（本 Layout 原来完全无视入参约束），所以这里按可用宽度回收一次。
                    .coerceAtMost(availableTabPx(constraints, tabs.size, padPx, gapPx))

                // 2026-09-08：格宽随标签显隐插值 —— 收起态收窄到 COLLAPSED_TAB_WIDTH，整条约 260dp
                //（原来恒为展开宽 62dp × 5 ≈ 340dp，收起只是整体缩放 0.86，左右仍占满一条）。
                // 进度取 labelReveal 而不是 expandProgress：格宽与文字必须同步，
                // 否则字还在淡出、格子已经收窄，文字会被横向裁掉。
                // 每帧重测只涉及 5 个 Tab，代价可忽略；滑块的步进 stepPx 也跟着一起变，
                // 所以药丸永远对齐当前格心。
                val revealForWidth = labelReveal.value.coerceIn(0f, 1f)
                val collapsedCellPx = COLLAPSED_TAB_WIDTH.roundToPx().coerceAtMost(expandedCellPx)
                val maxWidth = (collapsedCellPx + (expandedCellPx - collapsedCellPx) * revealForWidth)
                    .roundToInt()
                    .coerceAtLeast(1)

                val placeables = tabMeasurables.map {
                    it.measure(Constraints.fixedWidth(maxWidth))
                }
                val maxHeight = placeables.maxOf { it.height }
                val vPadPx = CAPSULE_INNER_VPAD.roundToPx()

                // 滑块尺寸 = 一个 Tab 单元格四周各内缩一点，正好套住图标+文字
                val insetH = INDICATOR_INSET_H.roundToPx()
                val insetV = INDICATOR_INSET_V.roundToPx()
                val slider = sliderMeasurable.measure(
                    Constraints.fixed(
                        width = (maxWidth - insetH * 2).coerceAtLeast(0),
                        height = (maxHeight - insetV * 2).coerceAtLeast(0)
                    )
                )

                val totalWidth = padPx * 2 + maxWidth * tabs.size + gapPx * (tabs.size - 1)
                val totalHeight = vPadPx * 2 + maxHeight

                // 一格的步进：滑块的像素位移 = indicatorPos × 该值（在 layer 阶段乘）
                val stepPx = maxWidth + gapPx
                if (tabStepPx != stepPx) tabStepPx = stepPx

                // 收起态滑块要"包住图标"而不是占满整格：满格高含标签占位，收起时标签不可见，
                // 满格药丸会比图标高出一大截（观感就是没在包裹）。这里算出上下各需内缩多少，
                // 由 draw 阶段按展开进度插值（展开 → 0，收起 → 本值）。
                val collapsedSliderH = iconSize.roundToPx() + COLLAPSED_ICON_PAD.roundToPx() * 2
                val collapsedInset = ((maxHeight - insetV * 2 - collapsedSliderH) / 2f)
                    .coerceAtLeast(0f)
                if (sliderCollapsedInsetPx != collapsedInset) {
                    sliderCollapsedInsetPx = collapsedInset
                }

                // 报给窗口宿主的"自然尺寸"恒取**展开态**宽度：窗口尺寸每帧改一次
                // 会走 WindowManager.updateViewLayout（跨进程），而内容在窗口里是居中的，
                // 收窄时留白即可，不需要窗口跟着缩。
                val expandedTotalWidth =
                    padPx * 2 + expandedCellPx * tabs.size + gapPx * (tabs.size - 1)
                val intrinsicSize = IntSize(expandedTotalWidth, totalHeight)
                if (naturalSize.value != intrinsicSize) {
                    naturalSize.value = intrinsicSize
                }

                layout(totalWidth, totalHeight) {
                    // 滑块先放 = 画在最底层（tabs 的 zIndex 最低也是 0f，同层按放置顺序绘制）
                    slider.placeRelative(x = padPx + insetH, y = vPadPx + insetV)
                    var x = padPx
                    placeables.forEach { placeable ->
                        placeable.placeRelative(
                            x = x,
                            y = vPadPx + (maxHeight - placeable.height) / 2
                        )
                        x += maxWidth + gapPx
                    }
                }
            }
        }
    }
}

/**
 * 选中滑块（着色框）：胶囊内跟着选中项横向滑动的一块**实心 accent 药丸**。
 *
 * 造型与外层胶囊的圆角**解耦**：圆角恒取自身当前绘制高度的一半（两端半圆 + 中间直边）。
 * 2026-09-03 修正：原来跟随外层圆角再 clamp 到 `height / 2`，外层圆角一调大就撞上限，
 * 在收起态退化成一个正圆／椭圆，和 18dp 圆角的胶囊完全不是一个语言。
 * 2026-09-04 第二版：高度随展开进度变化 —— 收起态夹到"图标 + 呼吸位"（真正包住图标），
 * 展开态张开到满格（包住图标 + 文字）。
 *
 * 配色第三版：前两版是 accent 的 16%~20% 淡底，边界读不出来，怎么调都不好看。
 * 现在吃满 accent（上缘 [INDICATOR_FILL_TOP]、下缘 [INDICATOR_FILL_BOTTOM] 给一点厚度）
 * + 一道上缘白色高光；选中的图标/文字由 [CapsuleTab] 反白到 `palette.onAccent`。
 * 不再画发丝描边：实心底上描边看不见，只会和胶囊自身 1.25dp 边框抢戏。
 *
 * 位移走 `graphicsLayer` 的 lambda 形态：每帧只更新图层参数，既不重组也不重新布局
 * —— 与图标的 `drawWithCache` 同一策略。尺寸与初始位置由父 `Layout` 给定。
 *
 * @param offsetX          相对第 0 格的横向位移（px），在 layer 阶段求值。
 * @param revealProgress   0..1 标签揭示进度，决定药丸是"包图标"（0）还是"占满整格"（1）。
 *                         刻意与标签同源而非跟展开弹簧，见调用点说明。
 * @param collapsedInsetY  收起态上下各内缩多少 px（由父 `Layout` 给出）。
 *
 * ★ 已删除形参（2026-09-24，迁移阶段 2.4）：`dragBoost: () -> Float`（0..1 的"被抓住"程度，
 *   拖动时在药丸上叠一层白做确认反馈）。横向拖拽整套已移除（§5.2），它恒为 0，
 *   连同所用的 `INDICATOR_DRAG_TINT` 一并退休。
 */
@Composable
private fun CapsuleSelectionSlider(
    offsetX: () -> Float,
    revealProgress: () -> Float,
    collapsedInsetY: () -> Float
) {
    val palette = LocalResolvedPalette.current
    val accent = palette.accent
    val fillTop = accent.copy(alpha = INDICATOR_FILL_TOP)
    val fillBottom = accent.copy(alpha = INDICATOR_FILL_BOTTOM)
    // 2026-09-03（P1c）审过，**这处白刻意不接 palette**：
    // 它不是"某个语义角色的颜色"，而是叠在 accent 实底上的**光照效果**——
    // sheenTop 是上缘弧面镜面高光，模拟的是光源色而非主题色。
    // 接成 onAccent 会出错：onAccent 是给这块底上的**文字/图标**用的，
    // 若某主题把它改成深色（浅 accent 场景），高光就变成一道黑影、按下去反而变暗，语义完全反了。
    // 两个 alpha（0.12 / 0.22，深/浅）也是按纯白校准的，换色源必须连带重调。
    // 换配色时的残影风险：无——白高光叠在会跟着变的 accent 上，观感随 accent 一起走。
    // （同类的 `dragTint`「被抓住」提亮反馈已于 2026-09-24 随横向拖拽一起删除。）
    // 上缘高光：让药丸有一点弧面感，而不是一块死平的色块。
    //
    // 2026-09-05：明暗**档位**的判据由 `isSystemInDarkTheme()` 改为 [ResolvedPalette.isDark]
    // （颜色本身仍是纯白，理由见上）。原写法绕过「设置 → 外观 → 外观模式」：
    // 用户强制浅色而系统是深色时，药丸底色已按浅色 accent 走、高光却还取 0.12 那一档，
    // 于是高光在浅底上几乎看不见（反之在深底上过曝）。isDark 与页面同源。
    val sheenTop = Color.White.copy(alpha = if (palette.isDark) 0.12f else 0.22f)
    Spacer(
        modifier = Modifier
            .graphicsLayer { translationX = offsetX() }
            .drawWithCache {
                // 胶囊形：两端半圆 + 中间直边，所以圆角恒取**当前绘制高度**的一半。
                // 2026-09-04 之前是 `corner - INDICATOR_INSET_V` 再 clamp 到 height/2 ——
                // 外层胶囊圆角（可由 ThemeManager.capsuleCornerDp 调）一大就撞到上限，
                // 而当时滑块又接近正方形（见 INDICATOR_INSET_H 的说明），渲染出来就是一个圆。
                // 现在形状与外层圆角解耦：半径恒为"实际画出来的那块"的 h/2。
                //
                // 两条渐变都不写死 startY/endY：不指定时着色器按**本次 drawRoundRect 的尺寸**
                // 铺开，所以收起态药丸变矮时，底色厚度与上缘高光会等比跟着收，
                // 不会出现"高光占了半颗药丸"的走形。
                val fill = Brush.verticalGradient(colors = listOf(fillTop, fillBottom))
                val sheen = Brush.verticalGradient(
                    0f to sheenTop,
                    0.55f to Color.Transparent,
                    1f to Color.Transparent
                )
                onDrawBehind {
                    // 收起 → 展开：上下内缩从 collapsedInsetY 线性收到 0。
                    // 图标本身恒在单元格正中（见 CapsuleTab 的整组 translationY），
                    // 所以上下等量内缩后的药丸依然以图标为心 —— 这就是"居中包裹"。
                    val p = revealProgress().coerceIn(0f, 1f)
                    val inset = (collapsedInsetY() * (1f - p)).coerceAtLeast(0f)
                    val h = (size.height - inset * 2f).coerceAtLeast(1f)
                    val topLeft = Offset(0f, (size.height - h) / 2f)
                    val pillSize = Size(size.width, h)
                    val radius = CornerRadius(h / 2f)
                    drawRoundRect(brush = fill, topLeft = topLeft, size = pillSize, cornerRadius = radius)
                    drawRoundRect(brush = sheen, topLeft = topLeft, size = pillSize, cornerRadius = radius)
                    // ★ 已删除（2026-09-24）：`if (boost > 0f) drawRoundRect(color = dragTint, …)`
                    //   —— 「被抓住」时叠的那层白，随横向拖拽一起退休（§5.2）。
                }
            }
    )
}

/**
 * 胶囊内的单个 Tab。
 */
@Composable
private fun CapsuleTab(
    tab: CapsuleTabItem,
    selectionFactor: () -> Float,
    isSelected: Boolean,
    iconSize: Dp,
    labelFontSize: TextUnit,
    iconTextSpacing: Dp,
    labelReveal: () -> Float,
    onClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val unselectedColor = palette.textSecondary
    // 选中项正落在实心 accent 滑块上，必须反白，否则 accent 图标压 accent 底 = 看不见
    val selectedColor = palette.onAccent
    val density = LocalDensity.current

    var textHeightPx by remember { mutableIntStateOf(0) }
    val iconTextSpacingPx = (iconTextSpacing.value * density.density).roundToInt()

    Box(
        modifier = Modifier
            // 用离散选中态而不是动画值：读 Animatable 会让 zIndex 每帧触发一次重组。
            .zIndex(if (isSelected) 1f else 0f)
            .widthIn(min = TAB_MIN_W)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                // 收起态标签不可见（reveal=0），此时若不整体下移，图标会偏在单元格上半部分、
                // 在着色滑块里看着"没坐正"。下移量正好是标签块的一半高，图标即落到格心。
                // 注意是**整组**平移：图标和标签一起走，图标不再单独做位移或缩放
                //（那种独立漂移 + 1.15× 放大会顶出滑块边界，2026-09-03 已删）。
                //
                // 2026-09-04 第三版：进度源从 expandProgress 换成 labelReveal。
                // 展开弹簧只有 ~0.15s，而文字淡入 320ms —— 图标先"咚"地就位、文字再慢慢显影，
                // 两条不同步的动画同屏就是用户说的"生硬"。共用一个进度后，
                // 图标上移与文字浮现是同一段运动的两个面。
                translationY = (iconTextSpacingPx + textHeightPx) / 2f * (1f - labelReveal())
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(iconTextSpacing)
        ) {
            val iconPainter = rememberVectorPainter(tab.icon)
            Spacer(
                modifier = Modifier
                    .size(iconSize)
                    .drawWithCache {
                        onDrawWithContent {
                            val factor = selectionFactor().coerceIn(0f, 1f)
                            val color = lerp(unselectedColor, selectedColor, factor)
                            val alpha = ICON_ALPHA_UNSEL + (1f - ICON_ALPHA_UNSEL) * factor
                            with(iconPainter) {
                                draw(size, alpha = alpha, colorFilter = ColorFilter.tint(color))
                            }
                        }
                    }
            )
            Text(
                text = tab.label,
                style = UfiTextStyles.capsuleLabel,
                fontSize = labelFontSize,
                maxLines = 1,
                color = lerp(unselectedColor, selectedColor, selectionFactor().coerceIn(0f, 1f)),
                modifier = Modifier
                    .onSizeChanged { textHeightPx = it.height }
                    .graphicsLayer {
                        // 渐入渐出 + 一点点上浮（2026-09-04）。
                        // 2026-09-03 曾把位移整个撤掉，原因是当时的位移量取自 onSizeChanged 拿到的
                        // textHeightPx —— 首帧是 0，文字会在淡入过程中被"顶"一下，再叠上胶囊整体
                        // 缩放的二次形变，观感就是抽搐。这里换成**固定 dp**（不依赖任何测量结果），
                        // 首帧就是确定值，既有"浮上来"的方向感又不会形变。
                        // 进度源是**独立的** labelReveal tween（不再是 expandProgress 的分段映射）：
                        // 展开弹簧只有 ~0.15s，挂在它上面的淡入肉眼等于瞬现。
                        val reveal = labelReveal().coerceIn(0f, 1f)
                        alpha = reveal
                        translationY = (1f - reveal) * LABEL_RISE.toPx()
                        // 2026-09-08：补一点同步缩放。只靠 alpha 时文字是"整块变透明"，
                        // 在 10sp 这种小字号上人眼几乎只看到"有/无"两态；配上 0.92→1 的缩放，
                        // 淡入过程本身有了尺度变化，才读得出"长出来"而不是"被打开"。
                        // 轴心放在文字中心（默认），所以左右同时向外展开，不会偏向一侧。
                        val s = LABEL_SCALE_FROM + (1f - LABEL_SCALE_FROM) * reveal
                        scaleX = s
                        scaleY = s
                    }
            )
        }
    }
}
