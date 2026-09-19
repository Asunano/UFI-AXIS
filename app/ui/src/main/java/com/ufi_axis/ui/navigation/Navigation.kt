package com.ufi_axis.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.util.lerp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.ThemeManager
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis_core.util.UiFrameGate
import kotlin.math.roundToInt

// === 二级页：Material Shared Axis X + 旧页压暗 / 新页圆角（2026-09）===
//
// 位移仍是纯水平 Shared Axis。深度图层 [ufiSharedAxisLayer] 按 **方向感知的下层角色**
// （[UfiNavRecedeRole]）分配，不用 `targetState` 猜 —— pop 时「离场页」是上层 detail，
// 不是下层，旧判据会把压暗加在正在滑走的新页上。
//
// - **下层 / 旧页**（push 留在后面的宿主、pop 回来的宿主）：仅 scrim 压暗（不做缩放）
// - **上层**（push 进场的新页、pop 滑出的 detail）：满屏只平移（铁律 A）；
//   **仅 push 进场**时叠圆角 20dp→0（卡片描边），不做整屏 scale —— scale+clip 曾是卡顿源
// 不引入 alpha 淡入淡出。Tab 切换仍由 UfiPageSwitcher 负责。
// 时长与 Tab 共用 [ufiNavTransitionDurationMs]，平移 / 图层 / 角色进度同起同落。

/** Shared Axis 视差分母：旧页走整屏的 1/6（与历史 HOST_PARALLAX 对齐，大位移更易掉帧）。 */
private const val SHARED_AXIS_X_PARALLAX = 6

/**
 * 上层进场页圆角峰值（dp）。落位必须精确回到 0。
 *
 * 2026-09-15：20 → 36。20dp 在整屏尺度上几乎看不出弧度（用户："圆角可以裁切大一点"）。
 */
private const val SHARED_AXIS_ENTER_CORNER_DP = 36f

/** 下层压暗幅度：与 [UfiMotion.NavRecede] 同一套 token。深度感由 scrim 承担，本页不做缩放。 */
private fun sharedAxisScrimAlpha(): Float = UfiMotion.NavRecede.ScrimAlpha

/**
 * 二级页转场相对「转场时长」滑块的放慢系数（2026-09-15）。
 *
 * 为什么不是直接把 `ThemeManager.TRANSITION_DURATION_DEFAULT_MS` 调大：那个值同时驱动
 * Tab 横滑切页，而且只影响**没改过设置**的用户（存过值的人不会变）。这里改系数，
 * 无论用户滑到哪一档，二级页都比 Tab 切页稳一档 —— 二级页是整屏位移，
 * 与 Tab 那种同层横滑相比需要更长的落位时间才不显得"甩过去"。
 *
 * 上限仍由 [ufiNavTransitionDurationMs] 的 600ms 夹住之后再乘，所以最大约 720ms。
 */
private const val SHARED_AXIS_SLOWDOWN = 1.2f

/**
 * 二级页转场的实际时长。0（关闭 / 系统降低动效）原样透传，不得被系数放大成 1 帧动画。
 *
 * 公开是因为胶囊导航栏要用它对齐自己的浮出时机（见 MainNavGraph 的 enterProgress）——
 * 两处必须读同一个函数，否则"页面落位"与"胶囊浮出"会错开。
 */
fun ufiSharedAxisDurationMs(durationMillis: Int): Int =
    if (durationMillis <= 0) 0 else (durationMillis * SHARED_AXIS_SLOWDOWN).roundToInt()

/**
 * 转场曲线。
 *
 * 两个页面在 Shared Axis 里是**同时**平移的，所以进/出必须用**同一条**曲线：
 * 一边加速一边减速会让上下层的视差关系在中途走歪（观感是两层之间"错位/拉扯"）。
 *
 * 2026-09-15 试过换成 M3 emphasized decelerate（`Easing.EmphasizedIn`），**已回退**：
 * 那条曲线在前 25% 的时间里就走完约 70% 的位移，整屏平移用它的观感是"页面猛地弹到位、
 * 最后几像素再慢慢爬"，用户直接反馈"动画异常的快 + 闪"。emphasized 系列是给
 * 小元件入场用的，整屏位移要的是全程匀顺 —— 回到 `Easing.Standard`（FastOutSlowIn）。
 * 放慢仍由 [SHARED_AXIS_SLOWDOWN] 负责，那是"总时长"而不是"前后快慢分配"。
 */
private fun <T> sharedAxisSpec(durationMillis: Int) =
    tween<T>(ufiSharedAxisDurationMs(durationMillis), easing = UfiMotion.Easing.Standard)

/** 前进：新页从右缘整屏滑入。上层，不做 scale。 */
fun detailSharedAxisEnter(durationMillis: Int): EnterTransition {
    if (durationMillis <= 0) return EnterTransition.None
    return slideInHorizontally(animationSpec = sharedAxisSpec(durationMillis)) { it }
}

/**
 * 前进：旧页向左视差退场，并在此登记「谁是下层」。
 *
 * 赋值排在 `<= 0` 早退之前，保证关闭档也刷新角色（与 [detailSharedAxisExit] 同理）。
 */
fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.detailSharedAxisExit(
    durationMillis: Int,
    role: UfiNavRecedeRole,
): ExitTransition {
    role.recedingEntryId = initialState.id
    if (durationMillis <= 0) return ExitTransition.None
    return slideOutHorizontally(animationSpec = sharedAxisSpec(durationMillis)) {
        -it / SHARED_AXIS_X_PARALLAX
    }
}

/**
 * 返回：下层（原宿主/上一页）自左侧视差滑入，并登记「谁是下层」。
 *
 * pop 的下层是回来的 `targetState` —— 这正是 `targetState == PostExit` 判据会判反的地方。
 */
fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.detailSharedAxisPopEnter(
    durationMillis: Int,
    role: UfiNavRecedeRole,
): EnterTransition {
    role.recedingEntryId = targetState.id
    if (durationMillis <= 0) return EnterTransition.None
    return slideInHorizontally(animationSpec = sharedAxisSpec(durationMillis)) {
        -it / SHARED_AXIS_X_PARALLAX
    }
}

/** 返回：上层 detail 整屏右滑退出。不做 scale、不压暗。 */
fun detailSharedAxisPopExit(durationMillis: Int): ExitTransition {
    if (durationMillis <= 0) return ExitTransition.None
    return slideOutHorizontally(animationSpec = sharedAxisSpec(durationMillis)) { it }
}

/**
 * Shared Axis 的深度图层（2026-09-13 二次修订：改用 in-place canvas clipPath）。
 *
 * - **下层**（[isReceding]）：只压暗（scrim），**不做任何变换**。
 * - **上层且正在进场**：满屏只平移，叠圆角 20dp→0（**in-place `clipPath`，不开离屏层**）；
 *   pop 滑出的 detail 不叠圆角。
 * - **上层且正在离场**（pop 的 detail）：零变换，纯平移。
 *
 * ## ★ 为什么是 `drawWithContent { canvas.clipPath(...) }` 而不是 `graphicsLayer { clip, shape }`
 * 上一轮（2026-09-13 一次修订）把离场页的 `scale 0.96 + CompositingStrategy.Offscreen` 删掉、
 * 换成 `graphicsLayer { clip = true; shape = RoundedCornerShape }` 来给进场页做圆角。
 * 但 `graphicsLayer` 一旦带了**非矩形的 `shape`**，Compose 会走 `clipToOutline` —— 它**强制
 * 分配一张整屏离屏缓冲**（无法像矩形那样原地裁剪）。这张缓冲在被父级 `slide` 平移的过程中
 * 合成到错误偏移，于是：整页「从下方 / 右下角飘上来」（问题 1 / 问题 4，多次返回必现）；
 * 且圆角半径逐帧变化 → 离屏缓冲每帧重分配 → 连续 / 快速返回明显掉帧（问题 2）；
 * 缓冲在未铺满屏时又被遮住，于是「新页圆角完全没有」（问题 3）。
 * 改成在 `DrawScope` 里用 `canvas.clipPath(roundRectPath)` **原地裁剪**（不分配任何层），
 * 上述三项一并消失：裁剪随父级平移正确跟随、无缓冲开销、圆角真正裁到页面底色。
 *
 * ## ★ 圆角滞后收起（问题 3 的落点）
 * 圆角不再与位移同进度抹平 —— 那种写法下圆角在页面还停在屏外右侧（progress≈0）时就是峰值、
 * 一旦滑入屏内（progress 大半）已归零，于是「新页完全看不到圆角」。现改为前 75% 保持
 * [SHARED_AXIS_ENTER_CORNER_DP]、最后 25% 才抹平，保证进场全程圆角清晰可见，
 * 落位瞬间收成整屏矩形。圆角外的补集**不填任何颜色**，露出的就是下层页面。
 *
 * 进度挂在本目的地的 [AnimatedVisibilityScope.transition] 上，可被预测性返回 seek。
 * 只在 `drawWithContent` 里读 progress / [isReceding]，零重组、零离屏缓冲。
 *
 * @param isReceding 本页是否是本次转场的下层（旧页）。判据见 [UfiNavRecedeRole]。
 * @param durationMillis 与位移同一时长；`<= 0` 时只留空 RenderNode 边界。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AnimatedVisibilityScope.ufiSharedAxisLayer(
    isReceding: () -> Boolean,
    durationMillis: Int,
): Modifier {
    if (durationMillis <= 0) return Modifier

    val progress: State<Float> = transition.animateFloat(
        transitionSpec = { sharedAxisSpec(durationMillis) },
        label = "ufiSharedAxisProgress",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }

    val scrimColor: Color = LocalResolvedPalette.current.scrim
    return Modifier.drawWithContent {
        val p = progress.value.coerceIn(0f, 1f)
        val receding = isReceding()

        if (receding) {
            // 下层：只画内容 + 压暗 scrim，零变换、零裁剪。
            drawContent()
            val alpha = (1f - p) * sharedAxisScrimAlpha()
            if (alpha > 0f) drawRect(color = scrimColor.copy(alpha = alpha))
            return@drawWithContent
        }

        // 上层（进场新页 / 滑出的 detail）：满屏只平移，不缩放、不压暗。
        // 圆角用 in-place canvas clipPath（不分配离屏缓冲），规避
        // graphicsLayer{RoundedCornerShape} 在整屏强开离屏层导致的首帧偏移/从下方飞上来
        // 与逐帧缓冲重分配。
        //
        // 2026-09-15：去掉原来的 `enteringUpper` 闸门 —— 它只让 **push 进场**的新页有圆角，
        // 返回时正在右滑出去的 detail 是方角，用户反馈"缺少圆角"就是这一半。
        // `p` 在两个方向上的语义都是"落位程度"（1 = 严丝合缝铺满、0 = 完全离屏），
        // 所以同一条公式对进场与离场都成立：只要没落位就有圆角。
        // 峰值保持到 75%（原 60%）再抹平，圆角在整段位移里都看得见。
        val cornerT = ((p - 0.75f) / 0.25f).coerceIn(0f, 1f)
        val cornerDp = lerp(SHARED_AXIS_ENTER_CORNER_DP, 0f, cornerT)
        if (cornerDp > 0.5f) {
            val r = cornerDp * density
            val path = Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
            }
            drawContext.canvas.save()
            drawContext.canvas.clipPath(path, ClipOp.Intersect)
            drawContent()
            drawContext.canvas.restore()
            // 圆角外那四小块**什么都不画**（2026-09-15 按用户要求）：
            // 曾经在这里用 ClipOp.Difference 填过一层深色底做边界，但那层黑角在整屏尺度上
            // 比圆角本身更抢眼。现在露出的就是下层页面（它正带着 scrim 和反向视差在动），
            // 圆角的可见性由"两页错位"提供。
        } else {
            drawContent()
        }
    }
}


// === Page transition — 统一 detail 风格 ===
//
// 2026-09-04（二级页返回观感 + 可预测性返回）三条约定，改这里前先读完：
//
// 1. **只平移，不改 alpha**。2026-08-30 已经踩过：任何一侧用 fade，手势返回过程中都会
//    透出下层 / 背景变淡（detail 页 fadeOut 让自己变透，宿主 fadeIn 让底层由淡变实）。
//    所以下面全部是位移，禁止再加 fadeOut/fadeIn 到 pop 方向。
// 2. **下层要有反向视差**。原来 pop 方向下层是 `EnterTransition.None`（完全静止），
//    只有上层在平移 —— 单层平移没有层次感，落地那一刻下层"凭空出现"，就是"不够优雅"
//    和"卡一下"的观感来源。现在下层从 -1/[SHARED_AXIS_X_PARALLAX] 屏滑回 0，
//    与上层同一 easing、同一时长：两层同时动、速度不同，才是标准的返回视差。
//    这同时让**可预测性手势返回**的预览是对的：手势 seek 时下层跟着手指走。
// 3. **时长跟随用户设置**，唯一来源见 [ufiNavTransitionDurationMs]。
//    ⚠ 2026-09-04（卡片浮起）此条**已修正**：原文写的是"时长用页面转场锚点
//    UfiMotion.Duration.Sweeping（320ms）"，也就是写死在本文件里的一个常量。
//    可 Tab 切页早就在用 `ThemeManager.transitionDurationMs`（默认 380，区间 150..600，
//    0=关闭）—— 于是"用户把转场时长拖到 600"只对 Tab 生效、二级页仍然 320，
//    这正是本文件第 3 条自己想避免的"三个数各行其是"。现在两条链路共用同一个用户设置。
//
// === 2026-09-04：二级页转场的深度层次 —— 「离场页后退」模型 ===
//
// 本轮（同日二次修订）把上午的「卡片浮起」推翻，改成 Material 共享轴 + 深度 /
// Android 预测性返回的标准做法。两条铁律：
//
// ★ 铁律 A：**进场页永远满屏铺满** —— 只平移，不缩放、不圆角、不投影。
//   原始理由（2026-09-04 上午）是这个 App 的 chrome **不在动画容器里**：`MainNavGraph`
//   把 `Scaffold` 的 `innerPadding`（= safeDrawing，含状态栏 / 导航栏）加在**包着
//   NavHost 的那个 Box** 上，于是状态栏色带与底部色带由 `Scaffold` 的 `containerColor`
//   静态绘制，进场页一旦 `scale < 1` 就会露出这些**静止**的 chrome，圆角与投影还会把
//   这条缝描得更清楚 —— 也就是用户说的「顶部标题上方一块不动、屏幕下方一块不动、割裂」。
//
//   ⚠ 2026-09-04（转场区满屏化）**这条理由已经不再成立**：`Scaffold` 的
//   `contentWindowInsets` 已清零，安全区改由页面侧的 `UfiScreenScaffold` 消费，
//   转场容器就是整块屏幕，状态栏 / 底部两条带子现在**随页面一起平移**。
//
//   但铁律 A **依然保留**，理由换成两条：
//   1. 底部悬浮胶囊 `UfiCapsuleTabBar` 活在**独立的 `Dialog` 窗口**里，永远不在这棵
//      Compose 树上、永远不会跟着缩 —— 进场页缩放依旧会在胶囊四周露出错位；
//   2. 「进场页不做任何变换」是 [ufiSharedAxisLayer] 零开销的前提（非 `isReceding()` 分支
//      **不读** progress，整段转场里这一层一次都不会被动画失效）。破掉它等于把
//      整屏缩放 + 离屏裁剪重新请回来 —— 那正是上一版卡顿的来源。

//
// ★ 铁律 B：**深度感由下层承担** —— 离场页（退到后面那页）只叠 scrim（峰值 30%，
//   [UfiMotion.NavRecede.ScrimAlpha]），**不再缩放**。它在下层、被进场页逐步覆盖，
//   压暗表达「退到后面去」的层次；代价只有一次 `drawRect`，零额外 transform。
//
//   ⚠ 2026-09-13 修订：原 `scale 1 → 0.96` + `CompositingStrategy.Offscreen` 已删除。
//   缩放 + 离屏合成在「系统预测性返回」逐帧 seek 时会把整块全屏纹理反复重渲染
//   → 慢速拖动掉帧（问题 3）；且与圆角 clip 叠加在首帧会渲染到错误偏移
//   → 新页「从右下角飞上来」（问题 4）。去掉缩放后两者一并消失，深度改由更深的 scrim 承担。
//
// ★ 顺带修掉卡顿：删掉的正是最贵的两项 —— 整屏 `shadowElevation`（大面积
//   RenderNode 投影，每帧重算）与 `clip = true`（离屏裁剪）。scrim 一直画在
//   同一条 modifier 链的 `drawWithContent` 里，不额外起 layout 节点。
//
//   ⚠ 2026-09-13（预测性返回掉帧 / 首帧飞角）最终修订：**不再使用任何离屏合成**。
//   此前（2026-09-04）为省掉子树几十张卡片的逐帧阴影重光栅化，转场中临时开了
//   `CompositingStrategy.Offscreen` 把子树录进纹理再缩放 —— 但它在预测性返回逐帧
//   seek 时把整块全屏纹理反复重渲染（问题 3 掉帧），且与圆角 clip 叠加在首帧
//   渲染到错误偏移（问题 4 新页从右下角飞上来）。现改为：下层不缩放、不开 Offscreen，
//   深度只由 scrim 表达；上层进场圆角用**纯 canvas clip**（`compositingStrategy` 保持
//   默认 `Auto`），既不再有纹理开销、也不再有首帧偏移。静止 / 非进场时一律退回
//   矩形、不裁剪，零常驻缓冲。

//
// ★ 单一进度/时长来源（这条约束从上午起未变，别再破）：
//   - 时长：[ufiNavTransitionDurationMs]（把 ThemeManager 的用户值 + 系统降低动效
//     合并成**一个数**，`0` 即"不播"）；
//   - 曲线 + spec 实例：同一条 `sharedAxisSpec`（平移 / 后退进度全部引用它）；
//   - 进度：[ufiSharedAxisLayer] 里那**一个** `transition.animateFloat` ——
//     它挂在 NavHost 自己的 `Transition<EnterExitState>` 上，因此与平移同源、可被
//     可预测性手势返回逐帧 seek。下层 scrim 与上层进场圆角都是它的纯函数
//     （scrim 幅度取 [UfiMotion.NavRecede.ScrimAlpha] token，本文件 `sharedAxisScrimAlpha()`），**没有第二个 animateXxxAsState、没有第二个时长**。
//   - 幅度：`UfiMotion.NavRecede`（`ScrimAlpha`），本文件不写裸数值。


// ── 时长 / 曲线：唯一来源 ────────────────────────────────────────────────────

/**
 * 二级页转场的**唯一时长来源**：把 [ThemeManager.transitionDurationMs] 的原始值
 * 与系统「降低动效」合并成一个数。
 *
 * 语义（调用点只需判 `> 0`）：
 * - 返回 `0` ⇒ **完全不播转场**。此时 [detailSharedAxisEnter] 等一律返回 `None`，
 *   且 [ufiSharedAxisLayer] 不施加后退缩放 / scrim ——
 *   "关闭"必须是整套一起关，只关平移会留下"页面瞬移但下层还在缩"的怪相。
 * - 返回 `>0` ⇒ 可直接喂给 `tween` 的合法时长，跟随用户在「外观」页的设置
 *   （[ThemeManager.TRANSITION_DURATION_MIN_MS] ~ [ThemeManager.TRANSITION_DURATION_MAX_MS]）。
 *
 * 为什么把哨兵单独兜住：`ThemeManager` 的进程级共享 flow 在从 prefs 播种前是
 * `TRANSITION_DURATION_UNSEEDED = -1`。若照 `coerceAtLeast(1)` 处理会得到 1ms（瞬变），
 * 若照 `<= 0 → 关闭` 处理又会把"还没读盘"误判成"用户关了转场"（P2f 修过同款 bug）。
 * 这里显式回落到默认档 [ThemeManager.TRANSITION_DURATION_DEFAULT_MS]。
 *
 * @param rawMs              `ThemeManager.transitionDurationMs` 的原始值（可能是 -1 / 0 / 越界值）。
 * @param systemReduceMotion 系统「移除动画」/ 无障碍降低动效（由 `:app` 探测后经
 *                           `LocalUfiReduceMotion` 注入）。为真时直接返回 `0`，
 *                           与用户开关是 or 关系。
 */
fun ufiNavTransitionDurationMs(rawMs: Int, systemReduceMotion: Boolean): Int = when {
    systemReduceMotion -> 0
    rawMs == ThemeManager.TRANSITION_DURATION_OFF -> 0
    rawMs < ThemeManager.TRANSITION_DURATION_MIN_MS -> ThemeManager.TRANSITION_DURATION_DEFAULT_MS
    else -> rawMs.coerceAtMost(ThemeManager.TRANSITION_DURATION_MAX_MS)
}


// ── 「谁是下层」的角色持有者 ─────────────────────────────────────────────────

/**
 * 本次转场里**哪一页扮演「下层」**（= 退到后面去、要吃 scale + scrim 的那一页）。
 *
 * ## ★ 为什么不能用 `NavController.visibleEntries` 的栈顶（2026-09-05 P0 根因）
 * 旧实现（`MainNavGraph` 里的 `isFrontEntry`）判据是
 * `visibleEntries.value.lastOrNull()?.id == entry.id`，注释断言「pop 时被关掉的那页仍在栈顶」。
 * **这条断言是错的。** navigation-runtime 2.8.0 `NavController.kt:1192 populateVisibleEntries()`
 * 的排序规则是：**先**收 `transitionsInProgress` 里 `maxLifecycle < STARTED` 的 entry
 * （含**已经出栈、正在跑退场动画**的那页），**再**收 `backQueue` 里 `>= STARTED` 的。
 * 库自己的 KDoc（`NavController.kt:134-151`）写得很清楚：
 * "CREATED entries are listed first ... can include entries that have been popped off" +
 * "The last entry in the list is the topmost entry in the back stack"。于是：
 * - push：`[host(CREATED), detail(STARTED)]` → `.last()` = detail = **进场页**（恰好也是前景页，蒙对）；
 * - pop： `[detail(CREATED, 已出栈), host(STARTED)]` → `.last()` = **host** = 进场页，但它是**下层**。
 *
 * 也就是说 `.last()` 的语义从来是「**进场页**」而不是「前景页」，两者只在 push 方向重合。
 * pop 期间旧判据把角色整个判反：正在整屏右滑出去的 detail 拿到 `scale 1→0.96` + scrim（错），
 * 宿主则完全不做变换、复位动画根本没播（错）。用户报的「退出偏快、卡顿跳帧、下层表现不对、
 * 收尾没滑完就消失」四条症状全部指向这里。
 * 交叉验证：`NavHost.kt:540-551` 自己就是拿 `visibleEntries.lastOrNull()` 当
 * `AnimatedContent` 的 targetState —— pop 转场能启动，本身就反证 `.last()` 已经变成 host。
 *
 * ## ★ 为什么也不能只用 `transition.targetState == EnterExitState.PostExit`
 * 那是「**离场页**」，与「进场页」互为反面，pop 方向同样错（pop 的离场页是上层的 detail）。
 * 「下层」这个概念**必须知道方向**：
 * - push ⇒ 下层是留在后面的 `initialState`；
 * - pop  ⇒ 下层是回来的 `targetState`。
 *
 * ## ★ 方向信息其实已经在手上
 * navigation-compose 按方向决定调用哪一组转场函数（`NavHost.kt:556-582`：push 走
 * `enterTransition/exitTransition`，pop 走 `popEnterTransition/popExitTransition`），
 * 所以「**哪个函数被调用**」本身就是方向信号；而这些函数是 `AnimatedContentTransitionScope`，
 * 能直接拿到 `initialState` / `targetState`。于是只需在**描述下层的那两个方向**
 * （[detailSharedAxisExit] = push 的旧页、[detailSharedAxisPopEnter] = pop 回来的旧页）里把 entry id 记到这里，
 * 图层侧比对 id 即可，完全不必碰 `visibleEntries`。
 *
 * ## ★ 为什么是普通 `var` 而不是 `MutableState`
 * 它只在 [ufiSharedAxisLayer] 的 `drawWithContent { }` lambda 里被读，
 * 不建立 snapshot 依赖 ⇒ **零重组**（用 `MutableState` 反而会把 NavHost 订阅进去，
 * 正是旧实现刻意避开的那件事）。角色切换只失效图层与绘制。
 *
 * ## ★ 手势返回也一并变对
 * 可预测性手势返回拖动期间，`NavHost.kt:514-524` 的 `prepareForTransition` **不发射**
 * `visibleEntries`（它只调 `NavController.prepareForTransition` 把生命周期挪到 STARTED），
 * 旧判据在「松手瞬间」才看到角色互换、于是缩放/scrim 会跳一下；新判据根本不读
 * `visibleEntries`，角色在 popEnter 函数被调用的那一刻就定了，跳变问题随之消失。
 *
 * ## ★ 落位安全性（所以不需要任何兜底/清理）
 * 留在屏上的那页最终 `progress == 1f` ⇒ 下层不再施加任何 transform（scale 恒为 `1f`，
 * 上层进场圆角经 `lerp(SHARED_AXIS_ENTER_CORNER_DP, 0f, 1f)` 精确回到 `0dp`）、
 * scrim 经 `sharedAxisScrimAlpha()` 配合 `(1f - progress)` 精确回到 `0f`：
 * **不论 [recedingEntryId] 标记的是谁，静止时都精确归零**。
 * 因此本类不需要 `onDispose` 清空，压暗幅度函数 `sharedAxisScrimAlpha()` 也一个字都不用改。
 */
class UfiNavRecedeRole {
    /** 本次转场里扮演「下层」的那个 `NavBackStackEntry.id`；尚无转场时为 `null`。 */
    var recedingEntryId: String? = null
}


// === 转场帧闸门 ===

/**
 * 在 NavHost 目的地转场（含**可预测性手势返回**的逐帧 seek）进行期间举起 [UiFrameGate]，
 * 压住数据层的整屏重组。
 *
 * 数据层（`DashboardModule` 的 WS 实时指标）看到闸门举起就把
 * cpu/内存/流量/信号的状态更新攒起来，等转场结束再一次性刷入。宿主页在根节点收
 * `dashboardState` 这一个大对象，一条 WS 推送 = 一次整屏重组；手势返回时宿主页与
 * 二级页**同时在画**，这次重组会和平移叠在同一帧里 —— 慢速返回时手势可能持续数秒，
 * 期间会撞上十几条推送，正是「慢慢返回能看到掉帧」的来源。
 *
 * Pager 侧早已有同样的闸门（见 `UfiPageSwitcherHost`），这里把它补到导航层。
 *
 * 用 `currentState != targetState` 而非时间判据：可预测性手势返回是被手势进度 seek 的，
 * 没有固定时长，只有「状态尚未落定」这一个可靠信号。
 *
 * ⚠ 2026-09-05：本函数曾经还向下 provide 一个 `LocalUfiNavTransitionActive`，供
 * `PagerBackend` 在转场期间把 `beyondViewportPageCount` 降到 0（pop 首帧只组合当前 Tab 页）。
 * 那条链路已整体回退 —— 转场途中改 Pager 入参会触发 LazyLayout 重测量，撞上正在跑的
 * `animateScrollToPage` 会让页面停在两页之间，而胶囊已经落到目标下标，于是出现
 * 「胶囊显示 a、实际是 b」。理由详见 `UfiPageSwitcherHost` 里 `baseBeyond` 的注释。
 * 本函数现在只做帧闸门这一件事，不再有 CompositionLocal 副作用。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AnimatedVisibilityScope.UfiNavFrameGate(content: @Composable () -> Unit) {
    if (transition.currentState != transition.targetState) {
        DisposableEffect(Unit) {
            UiFrameGate.begin()
            onDispose { UiFrameGate.end() }
        }
    }
    content()
}
