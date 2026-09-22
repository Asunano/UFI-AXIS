package com.ufi_axis.ui.navigation

import android.graphics.RenderEffect
import android.graphics.Shader
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
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.util.lerp
import com.ufi_axis.ui.components.common.UfiDialogAnim
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


// ── 下层背景模糊（2026-09-20，只服务升起面板）─────────────────────────────────
//
// 为什么这条链路与公共弹窗（[UfiDialogShell]）**实现不同但取值同源**：
// 弹窗是独立 Window，能用 `FLAG_BLUR_BEHIND + blurBehindRadius` 让系统去模糊
// **它背后那个 Window**；而导航转场的两页在**同一个 Window** 里，跨窗口模糊这条路
// 根本不通 —— 只能在绘制期对下层那棵子树自己做一次离屏模糊。
// 所以观感口径（峰值半径、量化档数）直接读弹窗那两个常量，实现方式各自为政。
//
// 半径单位对得上，不需要换算：弹窗的 [UfiDialogAnim.BlurRadius] 是写进
// `WindowManager.LayoutParams.blurBehindRadius` 的**设备像素**，而
// `RenderEffect.createBlurEffect` 收的也是**像素**。（若改用 `Modifier.blur(Dp)`
// 就必须先除以 density —— 那条路这里没走，理由见 [ufiSharedAxisLayer] 的 blurUnderlay。）
//
// API 门槛：`RenderEffect` 需要 API 31，而全库 minSdk 就是 31，所以不存在
// "低版本静默失效、只剩 scrim" 的退化分支要写。

/**
 * 把 0..1 的模糊进度量化成 0..[UfiDialogAnim.BackdropSteps] 档。
 *
 * 量化的动机与弹窗那边同源（见 [UfiDialogAnim.BackdropSteps]）：半径这种视觉量 12 档
 * 已经看不出台阶，而**逐帧新半径**意味着逐帧 `createBlurEffect`（JNI + 原生对象分配）——
 * 页面切换那侧早就为此加过缓存（见 `UfiPageSwitcherHost` 的 `blurEffectCache`）。
 * 量化之后整段转场最多命中 12 个不同半径，配合 [underlayBlurEffect] 的缓存即为零分配。
 */
private fun underlayBlurStep(fraction: Float): Int =
    (fraction.coerceIn(0f, 1f) * UfiDialogAnim.BackdropSteps).roundToInt()

/**
 * 量化档 → 模糊 `RenderEffect` 的缓存。
 *
 * `RenderEffect` 不可变，跨帧 / 跨图层复用安全；只在**绘制期**（主线程）访问，
 * 因此用普通 `HashMap` 不加锁。键是档位而不是半径浮点数：浮点数当键迟早因为
 * 末位误差把缓存打成每帧新建。
 */
private val underlayBlurEffects = HashMap<Int, androidx.compose.ui.graphics.RenderEffect>()

/**
 * 取某一档的模糊效果；第 0 档返回 `null` —— 调用方据此**整段跳过离屏图层**。
 *
 * 半径按档位线性插到 [UfiDialogAnim.BlurRadius]，第 1 档已经约 9px，
 * 不存在"小到看不见却照付一次离屏渲染"的区间，所以这里不需要额外的最小半径守卫。
 */
private fun underlayBlurEffect(step: Int): androidx.compose.ui.graphics.RenderEffect? {
    if (step <= 0) return null
    return underlayBlurEffects.getOrPut(step) {
        val radius = UfiDialogAnim.BlurRadius.toFloat() * step / UfiDialogAnim.BackdropSteps
        RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP).asComposeRenderEffect()
    }
}

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
    // 普通二级页当下层：只压暗，不模糊。这一行是**复位**而不是冗余 —— 角色对象跨转场复用，
    // 上一次若是给升起面板垫底（置过 true），不写回 false 就会让下一次普通转场继承模糊。
    role.recedingBlur = false
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
    // 与 [detailSharedAxisExit] 同理：普通二级页的下层不模糊，且必须显式复位。
    role.recedingBlur = false
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


// === 升起面板：音乐播放页专用（2026-09-20）=====================================
//
// 为什么这一页要从共用的横向共享轴里摘出来：迷你控制条的封面到播放页封面是一条共享元素
// （[UFI_SHARED_KEY_AUDIO_COVER]），它的行程几乎是纯竖直的"原地长大"。页面同时整屏横移，
// 等于让同一块封面同时被两个方向拽 —— 观感就是两个动画在打架。改成"面板自迷你条上沿升起 /
// 落回那条线"后（起点见 [riseStartOffset]），页面位移与共享元素同向，封面看起来是被它所在的
// 面板一起带上来的。
//
// ★ 一次转场里两页各自登记的函数不是对称的，改这里前务必看清（navigation-compose 语义）：
//   - 进场 / popExit 取**正在进来或正在被关掉**的那一页自己登记的函数；
//   - exit / popEnter 取**对端**（留在下面那一页）登记的函数。
//   所以 `risePanel*` 这四个只描述**播放页自己**的四种处境；而"面板升起时下层的音乐列表
//   怎么动"落在列表页的 DETAIL 登记上，由 [riseUnderlayExit] / [riseUnderlayPopEnter]
//   通过 [isUfiRiseRoute] 识别对端后接管。
//
// ★ 2026-09-20 二次修订：**升起语义只在有起点时才成立**。
//   这套动画的起点是迷你控制条的上沿（[riseStartOffset]），前提是那条线此刻真的在屏上 ——
//   也就是只有从挂着迷你条的页面（[isUfiMiniBarHostRoute]）进来才成立。从别处进播放页
//   （Tab 页标题栏的「正在播放」挂件）时没有这个起点，面板会从屏幕外整整一屏下方飞上来；
//   那条路径两端也没挂共享元素，连"跟着封面走"这条理由都不存在。所以那些来源退回
//   [detailSharedAxisEnter] / [detailSharedAxisPopExit]（横向共享轴 + 圆角）。
//   二选一在**登记处**按 `initialState` / `targetState` 挑函数（见 `MainNavGraph` 的 RISE 循环），
//   不给播放页开第二条路由 —— 同一个页面只该有一条路由，否则返回栈与深链接都要各维护一份。
//
// 面板仍然套 [ufiSharedAxisLayer]（scrim 那部分要 —— 从播放页再往里进一层时它是下层），
// 但**圆角裁剪要关掉**（登记处传 `clipCorners = false`）：36dp→0 的圆角是"卡片从旁边推进来"
// 的语言，而这块面板贴着屏幕下沿升起、左右始终顶到屏幕边缘，加圆角会变成"一张圆角卡片
// 浮在屏幕上"，与"贴底长出来的一整块"互相矛盾。DETAIL / HOST 那两套照旧带圆角。

/**
 * 面板升起 / 落回的曲线与时长。
 *
 * 时长复用 [ufiSharedAxisDurationMs]（同一个用户滑块、同一个放慢系数）不是省事：
 * [ufiSharedAxisLayer] 内那条圆角进度动画用的是 `sharedAxisSpec`，位移一旦与它不同长，
 * 圆角就会在面板还没落位时提前抹平、或落位后还挂着一圈。两条必须同起同落。
 *
 * 仍然独立成一个函数而不是直接调 `sharedAxisSpec`：两者语义不同（横向共享轴 / 竖向面板），
 * 日后单独调其中一条时不该牵连另一条。
 *
 * 曲线取 [UfiMotion.Easing.Standard]，**没有**用 `EmphasizedIn/Out`：`sharedAxisSpec` 的
 * 注释里记着 2026-09-15 那次回退 —— emphasized 系列会把七成行程压进前四分之一时间，
 * 在**整屏尺度**的位移上观感是"猛地弹到位、最后几像素再慢慢爬"。面板行程是整屏高度，
 * 与那次是同一类，没有理由再试一遍。
 */
private fun <T> riseSpec(durationMillis: Int) =
    tween<T>(ufiSharedAxisDurationMs(durationMillis), easing = UfiMotion.Easing.Standard)

/**
 * 面板淡入淡出占总时长的比例。
 *
 * 为什么只给 0.4 而不是与位移同长：本文件「只平移、不改 alpha」那条约定的理由是半透明的
 * 上层会把下层内容透出来（返回途中尤其明显）。面板这里仍要一点 alpha —— 纯位移的整屏面板
 * 在起步几帧像"凭空贴上来的一块板"—— 但把它压在**贴近屏幕外**的那一段：升起时前 40% 就淡完，
 * 落回时最后 40% 才开始淡，半透明只发生在面板大半已经在屏幕外的时候。
 */
private const val RISE_FADE_FRACTION = 0.4f

/** 面板淡入淡出的实际时长；至少 1ms（`tween` 不接受 0）。 */
private fun riseFadeMs(durationMillis: Int): Int =
    (ufiSharedAxisDurationMs(durationMillis) * RISE_FADE_FRACTION).roundToInt().coerceAtLeast(1)

/**
 * 面板**作为下层**时的竖向视差分母：整屏高度的 1/12。
 *
 * 用在"从播放页再往里进一层"（例如播放页 → 某个设置页）的场景：那时候播放页变成下层，
 * 深度感主要由 scrim 承担（见 [UfiNavRecedeRole]），位移只需要一点点、且必须**竖向** ——
 * 横向位移会让人以为面板被推走 / 关掉了。比 `SHARED_AXIS_X_PARALLAX`（1/6）更小，
 * 因为屏幕高度远大于宽度，同样的分母换成竖向会是明显大得多的位移。
 */
private const val RISE_UNDER_PARALLAX = 12

/**
 * 面板「贴着迷你条上沿」的竖向起点 / 终点偏移（像素）。
 *
 * `fullHeight` 是**转场容器的整屏高度**（`slideInVertically` 的入参）。直接用它意味着
 * 面板的**顶边**从屏幕外下方整整一屏处起步 —— 观感是"一块板从屏幕外飞进来"。
 * 减掉迷你条高度之后，起始位置正好落在迷你条的**上沿**（也就是那条 2dp 进度线所在的 y），
 * 于是面板的顶边是从这条线往上展开的：用户点的那条线，就是页面长出来的地方。
 * 同一个偏移也用在关闭方向，面板会收回到同一条线上，去/回是同一段行程。
 *
 * 迷你条高度为 0（没在播放、或不是从挂着迷你条的页面进来的）时结果就是 `fullHeight`，
 * 即"自屏幕下沿升起"的原行为 —— 这是正确的兜底，不需要额外分支。
 *
 * `coerceAtLeast(0)`：只防迷你条高度因为某次异常测量大过屏高时算出负数
 * （负偏移会让面板从屏幕**上方**掉下来，方向整个反掉）。
 */
private fun riseStartOffset(fullHeight: Int): Int =
    (fullHeight - UfiMiniPlayerBarMetrics.heightPx).coerceAtLeast(0)

/**
 * 打开播放页：整屏面板自迷你条上沿升起（起点见 [riseStartOffset]）。上层，只平移 + 贴近屏外那段淡入。
 *
 * ⚠ 只在**来源是迷你条宿主页**（[isUfiMiniBarHostRoute]）时使用 —— 起点那条线得真的在屏上。
 * 其他来源由登记处换成 [detailSharedAxisEnter]，理由见本节开头的"二次修订"。
 */
fun risePanelEnter(durationMillis: Int): EnterTransition {
    if (durationMillis <= 0) return EnterTransition.None
    return slideInVertically(animationSpec = riseSpec(durationMillis)) { riseStartOffset(it) } +
        fadeIn(
            animationSpec = tween(
                durationMillis = riseFadeMs(durationMillis),
                easing = UfiMotion.Easing.Standard,
            )
        )
}

/**
 * 从播放页再往里进一层：面板退作下层。
 *
 * 只登记「谁是下层」+ 一点竖向视差，**不横移、不淡出**：面板横着滑走会被读成"面板关了"，
 * 而淡出会把下一页还没铺满时的空隙透出来（本文件开头第 1 条）。深度交给 scrim。
 *
 * 赋值排在 `<= 0` 早退之前，保证关闭档也刷新角色（与 [detailSharedAxisExit] 同理）。
 */
fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.risePanelExit(
    durationMillis: Int,
    role: UfiNavRecedeRole,
): ExitTransition {
    role.recedingEntryId = initialState.id
    // 面板退作下层时也吃模糊：它此刻的角色与"给面板垫底的列表页"完全一样 —— 被上层盖住的那层。
    role.recedingBlur = true
    if (durationMillis <= 0) return ExitTransition.None
    return slideOutVertically(animationSpec = riseSpec(durationMillis)) { -it / RISE_UNDER_PARALLAX }
}

/**
 * 从更深一层返回播放页：面板作为下层回到原位（[risePanelExit] 的逆过程，同一条视差）。
 *
 * 注意它**不是**"关掉播放页"那一步 —— 那一步走的是 [risePanelPopExit]。
 */
fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.risePanelPopEnter(
    durationMillis: Int,
    role: UfiNavRecedeRole,
): EnterTransition {
    role.recedingEntryId = targetState.id
    role.recedingBlur = true
    if (durationMillis <= 0) return EnterTransition.None
    return slideInVertically(animationSpec = riseSpec(durationMillis)) { -it / RISE_UNDER_PARALLAX }
}

/**
 * 关闭播放页：面板落回迷你条上沿（终点见 [riseStartOffset]）。淡出压在最后 [RISE_FADE_FRACTION] 段，理由见该常量。
 *
 * ⚠ 只在**去向是迷你条宿主页**（[isUfiMiniBarHostRoute]）时使用 —— 落点那条线得真的会回到屏上。
 * 去向是别处（Tab 页等）时由登记处换成 [detailSharedAxisPopExit]：去与回必须是同一段行程的正反，
 * 否则会出现"横着滑进来、竖着落下去"。
 *
 * ⚠ 已知退化：面板打开期间，下层那张挂着迷你条的列表页已经离开组合，
 * [UfiMiniPlayerBarMetrics.heightPx] 因此是 0；下层重新组合并测量与本函数读值发生在
 * 同一帧，谁先谁后取决于 `AnimatedContent` 的子节点测量顺序。读到 0 时本函数退化成
 * "落回屏幕下沿"（也就是改动前的行为），不会出现错误几何。
 */
fun risePanelPopExit(durationMillis: Int): ExitTransition {
    if (durationMillis <= 0) return ExitTransition.None
    val total = ufiSharedAxisDurationMs(durationMillis)
    val fade = riseFadeMs(durationMillis)
    return slideOutVertically(animationSpec = riseSpec(durationMillis)) { riseStartOffset(it) } +
        fadeOut(
            animationSpec = tween(
                durationMillis = fade,
                delayMillis = (total - fade).coerceAtLeast(0),
                easing = UfiMotion.Easing.Standard,
            )
        )
}

/**
 * 面板升起时的**下层**：留在原位，吃 scrim + 逐渐模糊。
 *
 * 由下层自己那条 DETAIL 登记调用（对端是 RISE 时），不是播放页调用的。
 * 下层不动是有意的：上层面板正在往上长，下层再横滑就成了两层往两个方向晃 ——
 * 这正是本轮要修掉的观感；而"被面板逐渐盖住"本身已经是足够的层次反馈，
 * 深度由 [ufiSharedAxisLayer] 的 scrim **与背景模糊**表达（所以这里仍要登记 [UfiNavRecedeRole]）。
 *
 * ★ 2026-09-20：这里把 [UfiNavRecedeRole.recedingBlur] 置 true，图层侧据此才会给这一页
 *   上模糊。判据刻意与登记入口绑在一起 —— 只有"我正在给一个升起的面板当垫底"这一种
 *   处境才会走到本函数（DETAIL 登记处的 `isUfiRiseRoute(对端)` 分支），
 *   普通详情页当垫底走的是 [detailSharedAxisExit]，那条把标志复位成 false。
 *
 * ★ `targetAlpha = 1f` 的淡出是一条**零视觉效果**的动画，唯一作用是给退场页一个明确的
 *   时长。别因为"看起来没用"把它删成 [ExitTransition.None]：AnimatedContent 只在所有
 *   动画跑完后才回收退场内容，没有任何动画的那一侧会在第一帧就被回收掉 ——
 *   面板升起时底下会露出 Scaffold 的空底色而不是那张列表。
 */
fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.riseUnderlayExit(
    durationMillis: Int,
    role: UfiNavRecedeRole,
): ExitTransition {
    role.recedingEntryId = initialState.id
    role.recedingBlur = true
    if (durationMillis <= 0) return ExitTransition.None
    return fadeOut(animationSpec = riseSpec(durationMillis), targetAlpha = 1f)
}

/**
 * 面板落回时的**下层**：完全不动地露出来，模糊由峰值化开到 0。
 *
 * 同样由下层那条 DETAIL 登记在"对端是 RISE"时调用。这里可以放心用 [EnterTransition.None]：
 * 要活到转场结束的是**上层**那张正在往下落的面板（[risePanelPopExit] 自带位移动画），
 * 下层是进场页，本来就会一直留在屏上。
 *
 * ⚠ [EnterTransition.None] 只是说"本页自己不平移、不淡入"，**不影响**
 *   [ufiSharedAxisLayer] 里那条 `transition.animateFloat` —— 它是挂在本页
 *   `Transition<EnterExitState>` 上自己加的一条动画，照旧从 0 走到 1。
 *   所以 scrim 与模糊都会随面板落回平滑化开，而不是瞬间消失。
 */
fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.riseUnderlayPopEnter(
    role: UfiNavRecedeRole,
): EnterTransition {
    role.recedingEntryId = targetState.id
    role.recedingBlur = true
    return EnterTransition.None
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
 * 只在 `drawWithContent` 里读 progress / [isReceding]，零重组。
 *
 * 离屏缓冲同样是零 —— **除了** [blurUnderlay] 打开且这一次真的要给升起面板垫底那种情形：
 * 模糊不可能不落地成一张离屏图层，那条例外的代价与闸门写在 [blurUnderlay] 的说明里。
 *
 * @param isReceding 本页是否是本次转场的下层（旧页）。判据见 [UfiNavRecedeRole]。
 * @param durationMillis 与位移同一时长；`<= 0` 时只留空 RenderNode 边界。
 * @param clipCorners 这**一次**转场里，要不要给**上层**叠 [SHARED_AXIS_ENTER_CORNER_DP]→0 的
 *                    圆角裁剪。返回 `false` 时整段 clipPath 分支被跳过（scrim 不受影响，那是下层的事）。
 *                    圆角是"一张卡片从旁边推进来"的语言：它要让人看出上层是有边界的一块、
 *                    盖在下层之上。而升起面板（[risePanelEnter]）是从屏幕下沿贴着长上来、
 *                    左右两边始终贴屏幕边缘，给它加圆角会读成"一张圆角卡片浮在屏幕上"，
 *                    与"贴底升起的整块面板"这个语义冲突 —— 所以只有走升起语义那一次才返回 false。
 *
 *   ## 为什么是 `() -> Boolean` 而不是 Boolean（2026-09-20 二次修订）
 *   播放页**同一条路由**现在有两种转场语义：来源挂着迷你条时升起（不要圆角），从别处进来时
 *   退回横向共享轴（要圆角，否则那条路径会变成"方角整屏硬切"）。而"来源是谁"只有**转场发生
 *   的那一刻**才知道，本函数却是在**目的地登记处**调用的（一个目的地一份、组合期就定了）。
 *   做成绘制期才问的 lambda，判定就能来自 [UfiNavRecedeRole]（由登记函数按来源写入），
 *   与 [isReceding] / [underlayBlurActive] 同一套路：零重组，只失效绘制。

 * @param blurUnderlay 本页**作为下层时**是否允许叠背景模糊（默认关，现有页面的转场一字不变）。
 *
 *   ## 为什么它只是"允许"，真正的判定还要看 [underlayBlurActive]
 *   本形参是**登记期**的常量（每个目的地一个值），只承担与具体那一次转场无关的三道闸门：
 *   用户的「过渡模糊」开关、设备能否扛住满屏模糊（`UfiPageSwitcherDefaults.isBlurSupported`）、
 *   以及"这个目的地压根不参与"。它同时决定**要不要分配那张离屏图层** ——
 *   所以必须是编译期就定下来的 Boolean，不能是 lambda。
 *
 *   而"这一次转场的下层该不该模糊"是**运行期**才知道的：同一张音乐列表页既可能给升起的
 *   播放面板垫底（要模糊），也可能给普通详情页垫底（不模糊）。那一层判定走
 *   [underlayBlurActive]。
 *
 *   ## 为什么不用 `Modifier.blur()`
 *   它收 `Dp` 且是**组合期**参数：半径要跟着进度走就得每帧重组一次本函数的调用点，
 *   而调用点是 NavHost 的目的地 content lambda —— 那是整页重组，比模糊本身贵得多。
 *   改成录一层 [rememberGraphicsLayer] 再给它设 `renderEffect`：半径只在**绘制期**变，
 *   零重组（与本文件"只在 drawWithContent 里读 progress"的既有约定一致）。
 *
 *   ## 与 2026-09-13 那次"禁止离屏合成"的关系（这条别误读成推翻）
 *   当时删掉的是 `CompositingStrategy.Offscreen` + `graphicsLayer{非矩形 shape}`：
 *   前者给**每一次**二级页转场的下层都开整屏纹理，后者因为圆角半径逐帧变化导致
 *   **缓冲逐帧重分配**，且被父级 slide 平移时合成到错误偏移。这里三条都不成立：
 *   (a) 只有升起面板这一种转场、且要额外通过用户开关与机型闸门；
 *   (b) 图层尺寸全程不变，只换 `renderEffect`，不重分配；
 *   (c) `record` 是在当前 DrawScope 的坐标里录制、`drawLayer` 原地画回，
 *       与 `UfiPageSwitcherHost` 那张快照层同一套用法，不存在偏移问题。
 *   模糊在本质上必须有一张离屏缓冲 —— 这是"下层像弹窗背景那样糊掉"的最小代价，
 *   所以代价被限制在一种转场里，而不是放回到所有转场上。
 * @param underlayBlurActive 这**一次**转场的下层该不该模糊，只在 [blurUnderlay] 为真时被问。
 *   判据的唯一来源是 [UfiNavRecedeRole.recedingBlur]（由登记函数按方向写入），
 *   与 [isReceding] 一样做成 lambda 是为了**在绘制期**读、不建立快照依赖 ⇒ 零重组。
 *   默认 `{ true }`：只翻 [blurUnderlay] 的调用方会立刻看到效果，
 *   而不是掉进"开关开了却什么都没发生"的坑。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AnimatedVisibilityScope.ufiSharedAxisLayer(
    isReceding: () -> Boolean,
    durationMillis: Int,
    clipCorners: () -> Boolean = { true },
    blurUnderlay: Boolean = false,
    underlayBlurActive: () -> Boolean = { true },
): Modifier {
    if (durationMillis <= 0) return Modifier

    val progress: State<Float> = transition.animateFloat(
        transitionSpec = { sharedAxisSpec(durationMillis) },
        label = "ufiSharedAxisProgress",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }

    // 图层只分配给"可能用到模糊"的目的地（DETAIL / RISE 两条登记，且用户开关与机型闸门都放行）。
    // 一个空的 GraphicsLayer 只是一个未录制的 RenderNode，不占离屏缓冲；真正的开销在
    // record + renderEffect，那两步只在下面判定通过的那些帧才执行。
    // 条件分支里的 remember 在这里是安全的：[blurUnderlay] 是登记期常量，
    // 同一个调用点的取值不会在重组之间翻转（用户改开关会让整个 MainNavGraph 重建这条链）。
    val blurLayer = if (blurUnderlay) rememberGraphicsLayer() else null

    val scrimColor: Color = LocalResolvedPalette.current.scrim
    return Modifier.drawWithContent {
        val p = progress.value.coerceIn(0f, 1f)
        val receding = isReceding()

        if (receding) {
            // 下层：内容（可能带模糊）+ 压暗 scrim，零变换、零裁剪。
            //
            // ★ 层次顺序是有意的：模糊只包住 `drawContent()`，scrim 画在**模糊之后**。
            //   反过来（先画 scrim 再一起模糊）会把那层压暗也糊开，观感是"脏了一层"
            //   而不是"背景糊了"，而且 scrim 的边界会随半径溢出到屏幕外。
            //
            // 进度用的就是 scrim 那一条 `p`（不另开 animateFloat，否则两条进度会错步）：
            // 下层的 `1f - p` 语义是"被盖住的程度" —— 面板升起时 0→1、落回时 1→0，
            // 所以模糊与压暗天然同起同落，落位（p == 1）时两者都精确归零。
            val blurStep = if (blurLayer != null && underlayBlurActive()) {
                underlayBlurStep(1f - p)
            } else {
                0
            }
            val effect = underlayBlurEffect(blurStep)
            if (blurLayer != null && effect != null) {
                blurLayer.renderEffect = effect
                blurLayer.record { this@drawWithContent.drawContent() }
                drawLayer(blurLayer)
            } else {
                // 第 0 档（静止、或这一次转场不该模糊）走这里：**一行都不碰图层** ——
                // 不录制、不合成，树上不留任何常驻的模糊节点，开销与改动前完全一致。
                drawContent()
            }
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
        //
        // clipCorners() == false（这一次走升起语义）时整段跳过：那一页要的是"贴着屏幕下沿
        // 长上来的一整块"，圆角会把它读成浮在屏上的卡片。**但同一条路由退化成横向共享轴的
        // 那一次（来源没有迷你条）必须照常带圆角** —— 所以这里是绘制期问一次，不是登记期常量。
        // 理由详见形参注释。
        val cornerT = ((p - 0.75f) / 0.25f).coerceIn(0f, 1f)
        val cornerDp = lerp(SHARED_AXIS_ENTER_CORNER_DP, 0f, cornerT)
        if (cornerDp > 0.5f && clipCorners()) {
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

    /**
     * 本次转场的下层要不要**背景模糊**（2026-09-20）。
     *
     * 为什么这条判定必须活在角色对象里、而不是图层的形参里：图层是在**目的地登记处**
     * 构造的（一个目的地一份），而"下层是在给升起面板垫底、还是在给普通详情页垫底"
     * 只有**转场发生的那一刻**才知道 —— 那正是登记函数被调用的时刻，
     * 而登记函数手上已经有这个 role 对象了（它本来就靠这条通道传"谁是下层"）。
     *
     * 写入点与 [recedingEntryId] 严格一一对应，两者永远同时赋值：
     * - [riseUnderlayExit] / [riseUnderlayPopEnter]（给升起面板垫底）⇒ `true`；
     * - [risePanelExit] / [risePanelPopEnter]（面板自己退作下层）⇒ `true`；
     * - [detailSharedAxisExit] / [detailSharedAxisPopEnter]（普通二级页）⇒ `false`。
     *
     * 最后那条是**必须写的复位**：角色对象跨转场复用，漏写会让下一次普通转场继承上一次的
     * `true`，于是"只有升起面板才模糊"这条约束会在第二次转场时静默失效。
     *
     * 与 [recedingEntryId] 同理用普通 `var`：只在 `drawWithContent` 里被读，零重组。
     */
    var recedingBlur: Boolean = false

    /**
     * 本次转场里，**播放页作为上层**时走的是不是「升起语义」（2026-09-20 二次修订）。
     *
     * 只有一个消费者：[ufiSharedAxisLayer] 的 `clipCorners` —— 升起语义要方角（贴底长出来的
     * 一整块），退化成横向共享轴时要照常带圆角（从旁边推进来的卡片）。
     *
     * 为什么这条也得走角色对象、而不是图层的形参：判据是「来源/去向是不是迷你条宿主页」
     * （[isUfiMiniBarHostRoute]），只有**转场发生的那一刻**才知道；而图层是在**目的地登记处**
     * 构造的（一个目的地一份、组合期就定了）。与 [recedingBlur] 是同一个时序问题、同一个解法。
     *
     * ★ 与 [recedingBlur] 不同，它描述的是**上层**，所以写入点也不同：只有 RISE 那条登记的
     *   `enterTransition` / `popExitTransition`（播放页自己当上层的两个方向）会写它，
     *   而那两个方向必然在播放页被当作上层绘制之前刚刚跑过，所以不需要任何复位 ——
     *   其他页面的登记函数一律不碰这个字段（它们的 `clipCorners` 用默认的恒 true）。
     *
     * 默认 `false`（= 带圆角的普通语义）：万一哪天有条路径绕过了那两个写入点，
     * 退化结果是"和其他二级页一样"，而不是"莫名其妙的方角整屏"。
     *
     * 与 [recedingEntryId] 同理用普通 `var`：只在 `drawWithContent` 里被读，零重组。
     */
    var upperIsRisePanel: Boolean = false
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
