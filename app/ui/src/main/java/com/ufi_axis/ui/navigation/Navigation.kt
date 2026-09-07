package com.ufi_axis.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.ThemeManager
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis_core.util.UiFrameGate


// === Page transition — 统一 detail 风格 ===
//
// 2026-09-04（二级页返回观感 + 可预测性返回）三条约定，改这里前先读完：
//
// 1. **只平移，不改 alpha**。2026-08-30 已经踩过：任何一侧用 fade，手势返回过程中都会
//    透出下层 / 背景变淡（detail 页 fadeOut 让自己变透，宿主 fadeIn 让底层由淡变实）。
//    所以下面全部是位移，禁止再加 fadeOut/fadeIn 到 pop 方向。
// 2. **下层要有反向视差**。原来 pop 方向下层是 `EnterTransition.None`（完全静止），
//    只有上层在平移 —— 单层平移没有层次感，落地那一刻下层"凭空出现"，就是"不够优雅"
//    和"卡一下"的观感来源。现在下层从 -1/[HOST_PARALLAX_DIVISOR] 屏滑回 0，
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
//   2. 「进场页不做任何变换」是 [ufiNavRecedeLayer] 零开销的前提（非 `isReceding()` 分支
//      **不读** progress，整段转场里这一层一次都不会被动画失效）。破掉它等于把
//      整屏缩放 + 离屏裁剪重新请回来 —— 那正是上一版卡顿的来源。

//
// ★ 铁律 B：**深度感由下层承担** —— 离场页（退到后面那页）`scale 1 → 0.96`
//   + scrim 淡入到 12%。它在下层、被进场页逐步覆盖，缩小不会露出 chrome。
//   观感与「卡片浮起」等价，但代价只有一个 scale + 一次 `drawRect`。
//
// ★ 顺带修掉卡顿：删掉的正是最贵的两项 —— 整屏 `shadowElevation`（大面积
//   RenderNode 投影，每帧重算）与 `clip = true`（离屏裁剪）。scrim 一直画在
//   同一条 modifier 链的 `drawWithContent` 里，不额外起 layout 节点。
//
//   ⚠ 2026-09-04（阴影闪烁）补充：`clip` 仍然不加，但**转场进行中**会临时开一层
//   离屏合成（`CompositingStrategy.Offscreen`，见 [ufiNavRecedeLayer]）。这不是把上一版
//   删掉的"整屏投影 + 离屏裁剪"请回来 —— 那一版是**每帧**重算整屏 RenderNode 投影，
//   这一版是**一次**把子树录进纹理，之后每帧只缩放这张纹理，恰好省掉子树里几十张
//   `ufiCardShadow` 卡片的逐帧阴影重光栅化。静止时（`scale == 1f`）自动退回 `Auto`，
//   不常驻任何离屏缓冲。

//
// ★ 单一进度/时长来源（这条约束从上午起未变，别再破）：
//   - 时长：[ufiNavTransitionDurationMs]（把 ThemeManager 的用户值 + 系统降低动效
//     合并成**一个数**，`0` 即"不播"）；
//   - 曲线 + spec 实例：[ufiNavTransitionSpec]，平移 / 淡入 / 后退进度全部引用它；
//   - 进度：[ufiNavRecedeLayer] 里那**一个** `transition.animateFloat` ——
//     它挂在 NavHost 自己的 `Transition<EnterExitState>` 上，因此与平移同源、可被
//     可预测性手势返回逐帧 seek。后退缩放与 scrim 都是它的纯函数
//     （[UfiNavRecedeProfile]），**没有第二个 animateXxxAsState、没有第二个时长**。
//   - 幅度：`UfiMotion.NavRecede`（`ScaleTo` / `ScrimAlpha`），本文件不写裸数值。
/** 下层视差位移的分母：下层从 -屏宽/6 滑回 0（上层是整屏），比例约 1:6。 */
private const val HOST_PARALLAX_DIVISOR = 6


// ── 时长 / 曲线：唯一来源 ────────────────────────────────────────────────────

/**
 * 二级页转场的**唯一时长来源**：把 [ThemeManager.transitionDurationMs] 的原始值
 * 与系统「降低动效」合并成一个数。
 *
 * 语义（调用点只需判 `> 0`）：
 * - 返回 `0` ⇒ **完全不播转场**。此时 [detailEnter] 等一律返回 `None`，
 *   且 [ufiNavRecedeLayer] 不施加后退缩放 / scrim ——
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

/**
 * 二级页转场的**唯一曲线来源**。位移、淡入、下层后退（缩放/scrim）全部用它，
 * 因此三者天然同起同落 —— 不存在"位移已经停了、scrim 还在淡"这种半拍错位。
 *
 * 曲线取 [UfiMotion.Easing.Standard]（Material 标准 FastOutSlowIn），与 Tab 切页
 * 在 `MainNavGraph` 用的 `CubicBezierEasing(0.4, 0, 0.2, 1)` 是同一条曲线，口径一致。
 */
fun <T> ufiNavTransitionSpec(durationMillis: Int): FiniteAnimationSpec<T> =
    tween(durationMillis = durationMillis, easing = UfiMotion.Easing.Standard)

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
 * （[detailExit] = push 的旧页、[detailPopEnter] = pop 回来的旧页）里把 entry id 记到这里，
 * 图层侧比对 id 即可，完全不必碰 `visibleEntries`。
 *
 * ## ★ 为什么是普通 `var` 而不是 `MutableState`
 * 它只在 [ufiNavRecedeLayer] 的 `graphicsLayer { }` / `drawWithContent { }` lambda 里被读，
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
 * 留在屏上的那页最终 `progress == 1f` ⇒ `UfiNavRecedeProfile.recedingScale(1f) == 1f`、
 * `scrimAlpha(1f) == 0f`：**不论 [recedingEntryId] 标记的是谁，静止时都精确归零**。
 * 因此本类不需要 `onDispose` 清空，[UfiNavRecedeProfile] 也一个字都不用改。
 */
class UfiNavRecedeRole {
    /** 本次转场里扮演「下层」的那个 `NavBackStackEntry.id`；尚无转场时为 `null`。 */
    var recedingEntryId: String? = null
}

// ── 四个方向的进出场 ─────────────────────────────────────────────────────────

/**
 * 进入 detail 页：从右侧**整屏外**滑入 + fade。
 *
 * ★ 2026-09-05：位移由 `+屏宽/2` 改为 `+屏宽`，与 [detailPopExit] 的整屏位移对称。
 * 起因是用户反馈"退出比进入短"。四个方向本来就共用同一个时长与同一条 [ufiNavTransitionSpec]，
 * 差异不在时长而在**距离**：进入只走半屏、退出走整屏，同样 380ms 下退出的视觉速度正好是
 * 进入的 2 倍，感知上就成了"一闪就没了"。
 * 反过来把退出改成半屏是不行的 —— 见 [detailPopExit] 的记录，半屏位移会在动画收尾时
 * 让页面还占着半个屏幕就被移除，且退出方向没有 fadeOut 兜底，必然可见地"啪"一下消失。
 * 所以对称只能靠抬高进入距离。
 *
 * ⚠ 进场页**不得**再叠任何缩放 / 圆角 / 投影（铁律 A，见文件头）：它必须始终满屏铺满，
 * 否则会露出动画容器之外的静止 chrome（状态栏色带 / 底部胶囊窗口）。
 * 深度感全部由下层的 [ufiNavRecedeLayer] 承担。
 */
fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.detailEnter(
    durationMillis: Int,
): EnterTransition {
    if (durationMillis <= 0) return EnterTransition.None
    return slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = ufiNavTransitionSpec(durationMillis),
    ) + fadeIn(animationSpec = ufiNavTransitionSpec(durationMillis))
}


/**
 * 前进时离开当前页（= 旧页「退到后面去」）：**反向视差平移**，与 [detailPopEnter] 互为镜像。
 *
 * 2026-09-04 把原来的 `fadeOut(Duration.Base)` 换掉，两个理由：
 * 1. 本文件第 1 条就写着"任何一侧用 fade 都会让下层/背景变淡"，pop 方向早就清干净了，
 *    push 方向这一处 fade 是遗留 —— 旧页 200ms 内淡成全透明，于是它上面的
 *    「后退 + scrim」层次根本来不及被看见（scrim 会随图层 alpha 一起消失）。
 * 2. pop 方向下层是 `-1/6 屏 → 0` 的反向视差，push 方向却是"原地淡掉"，
 *    两个方向不是镜像；用户要求"后退把整套反向播放"，那 push 也得是同一套的正向。
 * 2026-09-05：这里同时**登记「谁是下层」**（[role]）。push 方向的下层就是留在后面的旧页，
 * 即 `initialState`。赋值刻意排在 `durationMillis <= 0` 早退**之前** —— 关闭档下角色也必须
 * 刷新，否则 role 会停在上一次转场的陈旧值上（虽然关闭档 [ufiNavRecedeLayer] 不读它，
 * 但用户中途把转场时长从 0 拖回非 0 时，第一次转场就会读到脏值）。
 * 为什么不能靠 `visibleEntries` 栈顶判角色，见 [UfiNavRecedeRole] 的 KDoc。
 */
fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.detailExit(
    durationMillis: Int,
    role: UfiNavRecedeRole,
): ExitTransition {
    // push：留在后面的旧页 = 下层。
    role.recedingEntryId = initialState.id
    if (durationMillis <= 0) return ExitTransition.None
    return slideOutHorizontally(
        targetOffsetX = { -it / HOST_PARALLAX_DIVISOR },
        animationSpec = ufiNavTransitionSpec(durationMillis),
    )
}

/**
 * 返回时进入上一页（detail → detail）：**反向视差平移，不淡入**。
 *
 * 见文件头第 2 条：静止的下层会让返回落地显得生硬。这里与 [hostPopEnter] 同一套参数。
 *
 * 2026-09-05：这里同时**登记「谁是下层」**（[role]）。pop 方向的下层就是回来的旧页，
 * 即 `targetState` —— 它正从 `-1/6 屏` 滑回 0 并把 `scale 0.96 → 1`、scrim 淡出。
 * 上层（正整屏右滑出去的那页）由 [detailPopExit] 描述，不写 role，避免两个方向互相覆盖。
 * 赋值同样排在早退之前，理由见 [detailExit]。
 */
fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.detailPopEnter(
    durationMillis: Int,
    role: UfiNavRecedeRole,
): EnterTransition {
    // pop：回来的旧页 = 下层。
    role.recedingEntryId = targetState.id
    if (durationMillis <= 0) return EnterTransition.None
    return slideInHorizontally(
        initialOffsetX = { -it / HOST_PARALLAX_DIVISOR },
        animationSpec = ufiNavTransitionSpec(durationMillis),
    )
}

/**
 * 返回时离开当前页：**纯向右滑出，不淡出**。
 *
 * 2026-08-30：去掉原来的 `fadeOut` 与「半屏位移」。
 * 侧滑/可预测性手势返回时，`fadeOut` 会把整页（含刚补上的不透明底色）一起调低 alpha，
 * 手势中就看到「背景变淡、能透出下层」；位移只走半屏（`it / 2`）则会在动画收尾时
 * 页面还占着半个屏幕就被直接移除，观感是「滑一半突然消失」。
 * 改为整屏位移 + 全程不透明：本页始终盖住下层，下层做反向视差（[hostPopEnter]）。
 */
fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.detailPopExit(
    durationMillis: Int,
): ExitTransition {
    if (durationMillis <= 0) return ExitTransition.None
    return slideOutHorizontally(
        targetOffsetX = { it },
        animationSpec = ufiNavTransitionSpec(durationMillis),
    )
}

// === Tab transition — 底部 Tab 之间切换（交叉淡入 + 轻微缩放） ===

/** 进入 Tab：淡入 + 轻微放大 */
val tabEnter: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(animationSpec = tween(UfiMotion.Duration.Standard)) + scaleIn(initialScale = 0.98f, animationSpec = tween(UfiMotion.Duration.Standard))
}

/** 离开 Tab：淡出 */
val tabExit: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(animationSpec = tween(UfiMotion.Duration.Quick))
}

/** 返回 Tab：淡入 */
val tabPopEnter: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(animationSpec = tween(UfiMotion.Duration.Quick))
}

/** 离开到另一 Tab：淡出 + 轻微缩小 */
val tabPopExit: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(animationSpec = tween(UfiMotion.Duration.Quick)) + scaleOut(targetScale = 0.98f, animationSpec = tween(UfiMotion.Duration.Quick))
}

// === Host transition — Tab 宿主目的地（Routes.MAIN）的整体进出场 ===
//
// 方案 A′ 迁移后，Tab 之间的切换动画由宿主内部的 UfiPageSwitcher 负责，
// NavHost 层只处理「宿主 ↔ detail 页」的整体进出场。
//
// [hostEnter] / [hostPopExit] 仍直接复用 tab* 定义（**别名，不是副本**）：这两个方向不是
// 「二级页 push/pop」这一对，而是"宿主整体出现 / 宿主让位给另一个 Tab 路由"，
// 与「离场页后退」这套深度层次无关，保持原观感。tab* 作为 [F24] 冻结件也不会被本轮改动碰到。

/** 进入 Tab 宿主：等同 [tabEnter]。 */
val hostEnter = tabEnter

/**
 * 离开 Tab 宿主（前往 detail 页）：等同 [detailExit] —— 宿主就是那张"退到后面去"的旧页。
 *
 * 2026-09-04：由 `tabExit`（`fadeOut(180)`）改为与 detail 同一套反向视差。
 * 理由同 [detailExit]：宿主 180ms 淡成全透明后，新页还有大半段行程要走，
 * 那段时间下层是空的（只剩 Scaffold 底色），既谈不上"层次"，也和 pop 方向不对称。
 * [tabExit] 作为 [F24] 冻结件保持原样不动，这里只是不再复用它。
 *
 * 2026-09-05：`role` 参数只是透传给 [detailExit]（角色登记逻辑只有一份）。
 */
fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.hostExit(
    durationMillis: Int,
    role: UfiNavRecedeRole,
): ExitTransition = detailExit(durationMillis, role)

/**
 * 返回 Tab 宿主：**反向视差平移，不淡入**。
 *
 * 2026-08-30：从 detail 页侧滑返回时，真正"进场"的是宿主页，走 `fadeIn` 就等于
 * 宿主从 alpha=0 起亮 —— 手势全程能看到底层背景由淡变实，正是"背景变淡"的另一半来源
 * （另一半是 detail 页自己的 fadeOut，见 [detailPopExit]）。所以**alpha 一律不动**。
 *
 * 2026-09-04：由 `EnterTransition.None` 改为反向视差位移。完全静止的下层配上层整屏平移，
 * 只有一个图层在动，落地瞬间下层"凭空出现"——这就是用户说的"不够优雅、卡卡的"。
 * 现在下层从 -屏宽/[HOST_PARALLAX_DIVISOR] 滑回 0，与上层同 easing 同时长；
 * 位移是 seekable 的，可预测性手势返回时下层会跟着手指进度走，预览才成立。
 *
 * [tabPopEnter] 作为 [F24] 冻结件保持原样不动，这里只是不再复用它。
 *
 * 2026-09-05：`role` 参数只是透传给 [detailPopEnter]（角色登记逻辑只有一份）。
 * 宿主正是 pop 方向的下层 —— 这一条以前被 `visibleEntries` 栈顶判据判成了「进场页」，
 * 于是它整段返回动画里既不缩放也不退 scrim，见 [UfiNavRecedeRole]。
 */
fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.hostPopEnter(
    durationMillis: Int,
    role: UfiNavRecedeRole,
): EnterTransition = detailPopEnter(durationMillis, role)

/** 从 Tab 宿主回退离开：等同 [tabPopExit]。 */
val hostPopExit = tabPopExit

// === 深度层次（离场页后退 + scrim） ===

/**
 * 「离场页后退」的**纯函数剖面**：把转场进度 `p` 映射成各项幅度。
 *
 * 拆成纯函数（无 Compose、无状态）的理由与 `UfiPageTransition.layerAt` 一致 ——
 * 可以在纯 JVM 单测里断言关键采样点（见 `UfiNavRecedeProfileTest`），
 * 不用起模拟器就能守住"落位后缩放与遮罩精确归零"这类回归。
 *
 * ## `p` 的语义（唯一约定）
 * `p` 是**本页自己**的可见度进度，由 NavHost 的 `Transition<EnterExitState>` 给出：
 * `0f` = 完全不可见（屏外 / 尚未进场 / 已经退场），`1f` = 落位。
 * 因此"前进"与"后退"不需要各写一套：前进时新页 `p: 0→1`、旧页 `p: 1→0`，
 * 后退时正好互换，整套动画天然反向播放。
 *
 * ## 为什么只剩两个函数
 * 2026-09-04 二次修订删掉了 `cardScale` / `cornerFraction` / `shadowEnvelope` /
 * `cardElevation` / `cardCornerRadius`：那五个只服务"进场页当卡片浮起"，而进场页
 * 现在必须满屏铺满（铁律 A，见文件头）—— 一旦缩放就会露出动画容器之外的静止 chrome。
 * 整屏 `shadowElevation` + `clip` 同时也是上一版卡顿的主要开销来源。
 */
object UfiNavRecedeProfile {

    /** 把任意输入夹到合法进度区间。手势 seek / spring 过冲都可能给出界外值。 */
    private fun clamp(p: Float): Float = p.coerceIn(0f, 1f)

    /**
     * 离场页（下层）的缩放：[UfiMotion.NavRecede.ScaleTo] → `1f`。
     *
     * 前进时 `p: 1→0`，于是读出来是 `1 → 0.96`（后退）；后退时 `p: 0→1`，读出 `0.96 → 1`（复位）。
     * 落位必须精确回到 `1f`，否则静止页面会永久挂着 4% 的缩放。
     */
    fun recedingScale(p: Float): Float =
        lerp(UfiMotion.NavRecede.ScaleTo, 1f, clamp(p))

    /**
     * 离场页 scrim 的不透明度：`p = 1`（落位）时 0，`p = 0`（完全退到后面）时
     * [UfiMotion.NavRecede.ScrimAlpha]。
     */
    fun scrimAlpha(p: Float): Float =
        (1f - clamp(p)) * UfiMotion.NavRecede.ScrimAlpha
}

/**
 * 给一个 NavHost 目的地套上「离场页后退 + scrim」图层，返回待挂到该页根节点的 [Modifier]。
 *
 * ## 两种角色
 * - **下层**（[isReceding] 为真，即"退到后面去"的那一页）：`scale 1 → 0.96` + scrim 淡入到
 *   [UfiMotion.NavRecede.ScrimAlpha]。它被上层覆盖，缩小不会露出 chrome。
 * - **上层**（层级更深、整屏平移的那一页）：**不施加任何变换**，
 *   只留一层 `graphicsLayer` 当 RenderNode 边界（理由见 `MainNavGraph` 对应注释）。
 *   它必须始终满屏铺满 —— 铁律 A，见文件头。
 *
 * ## 进度来源
 * 只有一个：[AnimatedVisibilityScope.transition]（NavHost 自己那条
 * `Transition<EnterExitState>`）上的一个 `animateFloat`，spec 引用
 * [ufiNavTransitionSpec] —— 与本页的平移共用同一时长与同一条曲线。
 * 因此它也是 **seekable** 的：可预测性手势返回时下层的后退/复位跟着手指走，而不是自播一遍。
 * 刻意不用 `animateDpAsState` / `animateFloatAsState` 之类"跟随目标值"的动画：
 * 那会引入第二个时长来源，正是 P2c 修过的静默漂移。
 *
 * ## 零重组（两处刻意设计，别改回去）
 * 1. `progress` 只在 `graphicsLayer { }` / `drawWithContent { }` 的 lambda 里读，
 *    所以状态变化只失效图层与绘制，不触发重组 —— 整屏转场每帧重组一次是掉帧的直接来源。
 * 2. [isReceding] 是**函数而不是 `Boolean`**：调用点比对的是 [UfiNavRecedeRole.recedingEntryId]
 *    （一个普通 `var`，由 [detailExit] / [detailPopEnter] 在被调用时按方向写入）。
 *    以函数形式下发 ⇒ 角色切换只失效图层，不触发任何重组。
 *
 *    ⚠ 2026-09-05 P0：这里**曾经**用 `NavController.visibleEntries` 的栈顶反着判
 *    （`isFront`），pop 方向判反 —— 完整根因、库源码依据、以及"为什么 `PostExit` 同样不行"
 *    全部记在 [UfiNavRecedeRole] 的 KDoc 里，改这里之前先读那一段。
 *
 * scrim 画在**同一条 modifier 链**的 `drawWithContent` 里（`drawContent()` 之后一次
 * `drawRect`），不另起 `Box` + `background`：后者多一个 layout 节点与一层绘制。
 *
 * @param isReceding   本页是否是本次转场的「下层」（退到后面去的那页）。
 *                     push 时 = 留在后面的旧页；pop 时 = 回来的旧页。
 *                     判据见 [UfiNavRecedeRole]，调用点见 `MainNavGraph`。
 * @param durationMillis [ufiNavTransitionDurationMs] 的返回值。`<= 0` ⇒ 转场关闭，
 *                     本函数只返回一个空的 `graphicsLayer()`（保留 RenderNode 边界，
 *                     理由见 `MainNavGraph` 对应注释），后退缩放与 scrim 一并不生效。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AnimatedVisibilityScope.ufiNavRecedeLayer(
    isReceding: () -> Boolean,
    durationMillis: Int,
): Modifier {
    // 关闭档：整套不生效。仍留一层空 graphicsLayer 作为 RenderNode 边界。
    if (durationMillis <= 0) return Modifier.graphicsLayer()

    val progress: State<Float> = transition.animateFloat(
        transitionSpec = { ufiNavTransitionSpec(durationMillis) },
        label = "ufiNavRecedeProgress",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }

    val scrimColor: Color = LocalResolvedPalette.current.scrim
    return Modifier
        .graphicsLayer {
            // 上层恒为 1f：此分支**不读** progress，于是整段转场里这一层
            // 一次都不会被动画失效（既省开销，也保证它满屏铺满、不露 chrome）。
            val scale: Float =
                if (isReceding()) UfiNavRecedeProfile.recedingScale(progress.value) else 1f
            scaleX = scale
            scaleY = scale

            // ★★ 2026-09-04（返回时「阴影闪烁」）：缩放期间必须离屏合成一次 ★★
            //
            // 病灶：全站卡片的阴影是 `Modifier.ufiCardShadow` = `Modifier.shadow(...)`
            // （见 `UfiCardDefaults.kt`），它给每张卡片单独建一个带 `shadowElevation`
            // 与 outline 的 RenderNode。默认的 `CompositingStrategy.Auto` 在
            // alpha == 1 且无 renderEffect 时**不开离屏缓冲**，只把变换挂到本
            // RenderNode 上 —— 于是下层页 `scale 0.96→1` 的那 380ms 里，子树里
            // 几十张卡片的阴影要**逐帧按新的非整数缩放重新光栅化**。阴影是靠
            // outline 做高斯模糊得到的，缩放比例每帧变化会让模糊核落在不同的
            // 亚像素位置，边缘亮度逐帧抖动 —— 真机观感正是用户说的「能看到阴影
            // 在闪」，且只在**下层**（做缩放的那一层）出现，进场页（scale 恒 1）
            // 不闪，与用户"只有返回时闪"的描述吻合。
            //
            // 治法：把这一层改成 `Offscreen` —— 子树连阴影一起先画进一张离屏纹理，
            // 之后整块纹理做缩放合成，阴影不再逐帧重算，边缘抖动随之消失。
            //
            // ⚠ 为什么写在 lambda 里而不是常开：`Offscreen` 会常驻一张
            //   ≈屏幕大小的纹理（1080×2340 ARGB8888 ≈ 10MB），而宿主页 99% 的
            //   时间是静止的，常开等于白占这块显存。`scale == 1f` 时（静止、
            //   以及进场页的全过程）退回 `Auto` → 零离屏、零额外内存；只有
            //   转场那几百毫秒才付这一次代价。`compositingStrategy` 是
            //   `GraphicsLayerScope` 的属性，可以在图层 lambda 里按帧改写，
            //   因此这条判断不带来任何重组。
            compositingStrategy =
                if (scale == 1f) CompositingStrategy.Auto else CompositingStrategy.Offscreen
        }
        .drawWithContent {
            drawContent()
            val alpha: Float =
                if (isReceding()) UfiNavRecedeProfile.scrimAlpha(progress.value) else 0f
            if (alpha > 0f) drawRect(color = scrimColor.copy(alpha = alpha))
        }
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
