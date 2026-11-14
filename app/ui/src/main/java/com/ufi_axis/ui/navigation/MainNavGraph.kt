package com.ufi_axis.ui.navigation

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavHostController
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.ufi_axis.ui.animation.blurEntrance
import com.ufi_axis.ui.animation.page.UfiPage
import com.ufi_axis.ui.animation.page.UfiPageSwitcher
import com.ufi_axis.ui.animation.page.UfiPageSwitcherDefaults
import com.ufi_axis.ui.animation.page.LocalUfiBlurEnabled
import com.ufi_axis.ui.animation.page.UfiPageTransitions
import com.ufi_axis.ui.animation.page.LocalUfiReduceMotion
import com.ufi_axis.ui.animation.page.LocalUfiPageActive
import com.ufi_axis.ui.animation.page.registerBuiltInTransitions
import com.ufi_axis.ui.components.common.CapsuleBlurHost
import com.ufi_axis.ui.components.common.CapsuleInsetHolder
import com.ufi_axis.ui.components.common.CapsuleTabItem
import com.ufi_axis.ui.components.common.CapsuleTouchGate
import com.ufi_axis.ui.components.common.DOCK_CONTENT_MIN_HEIGHT
import com.ufi_axis.ui.components.common.LocalCapsuleBottomInset
import com.ufi_axis.ui.components.common.dockSurfaceColor
import com.ufi_axis.ui.components.common.UfiBottomDock
import com.ufi_axis.ui.components.common.UfiCapsuleTabBar
import com.ufi_axis.ui.components.common.ufiCapsuleBottomInset
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.ThemeManager
import com.ufi_axis.ui.theme.UfiInheritUiScale


/**
 * 底部 5 Tab 导航（方案 B）：仪表盘 / 网络 / 监控 / 工具 / 我的。
 * 监控由 detail 提升为常驻主目的地，便于高频实时查看。
 */
private data class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

// 图标与 web 面板对齐（web 用 @vicons/ionicons5 的 outline 系列，见 DefaultLayout.vue 的 menuOptions）：
// 仪表盘=SpeedometerOutline→Speed、网络=WifiOutline→Wifi、监控=StatsChartOutline→BarChart、
// 设置=SettingsOutline→Settings；工具是 app 独有的聚合页，取 web「应用」的宫格语义 GridView。
// 一律用 Outlined 变体：web 是描边风格，实心图标压在实心 accent 滑块上会糊成一坨。
// ⚠ 这些是 extended 图标，只能待在 app/ui（:app 模块只依赖 material-icons-core）。
private val BOTTOM_TABS = listOf(
    BottomTab(Routes.DASHBOARD, "仪表盘", Icons.Outlined.Speed),
    BottomTab(Routes.NETWORK, "网络", Icons.Outlined.Wifi),
    BottomTab(Routes.MONITOR, "监控", Icons.Outlined.BarChart),
    BottomTab(Routes.TOOLS, "工具", Icons.Outlined.GridView),
    BottomTab(Routes.SETTINGS, "我的", Icons.Outlined.Settings)
)

private val TAB_ROUTES = BOTTOM_TABS.map { it.route }

// 底栏几何全部由窗口层决定（`UfiCapsuleBlurHost`）：通栏形态为 MATCH_PARENT 宽 +
// gravity=BOTTOM + y=0，没有抬升、四周投影或「离热区多远」的概念（热区那条带子就是
// 栏自己的一部分），因此本文件不再持有 `CAPSULE_BOTTOM_MARGIN` / `CAPSULE_SHADOW_ROOM`。

/**
 * 胶囊导航栏**收起**时长（毫秒）。全库唯一一处刻意不在 [UfiMotion.Duration] 梯度上的时长。
 *
 * 不并入梯度的理由：
 * - 它与浮出时长（[UfiMotion.Duration.Emphatic] 420）是一对**刻意不对称**的参数 ——
 *   进场慢、退场快 60ms，底栏浮层因此表现为从容进入、快速退出；
 * - 360 与最近的两个档位（Sweeping 320 / Deliberate 400）都差 40ms，超出「≤20ms 才允许吸附」
 *   的容差，吸附会改变这对不对称关系；
 * - 只服务胶囊收起这一处，没有第二个调用点，并入全局梯度会诱导误用。
 *
 * 本文件内的具名常量是唯一来源，不得散落为魔法数字。调整时需连带评估
 * [UfiMotion.Duration.Emphatic]，两者是一对。
 */
private const val CAPSULE_HIDE_MS = 360

/**
 * 底部栏形态开关：`true` = 贴底通栏低栏 [UfiBottomDock]，`false` = 悬浮胶囊 [UfiCapsuleTabBar]。
 *
 * ## 为什么存在这个开关
 * 两种形态的几何假设是**互斥**的：
 * 胶囊是 wrap-content 窗口 + `LayoutParams.y` 抬起 + 四周一圈投影留白；
 * 通栏是 MATCH_PARENT 宽 + `gravity=BOTTOM` + `y=0` + 安全区靠栏内 Spacer 让位。
 *
 * ## ⚠ `false` 分支不是可用的回退位
 * 窗口层（MATCH_PARENT / `gravity=BOTTOM` / `y=0`）、页面 inset（取栏总高与「58 + 安全区」
 * 较大值）、进出场（纵向滑动）均按通栏口径实现。切回 `false` 会得到
 * **贴死屏幕底边、不再悬浮的胶囊**，且页面底部留白按通栏计算，属于错位的中间态。
 * 需要回退时必须整体还原窗口层、页面 inset 与进出场三处，不能只翻这个开关。
 */
private const val USE_BOTTOM_DOCK = true

/**
 * 底部导航栏。
 *
 * 方案 A′ 迁移后改为 **selectedIndex 驱动**：5 个 Tab 已收敛进 [Routes.MAIN] 单一目的地，
 * 不再能通过 `currentRoute` 区分选中项。
 *
 * 两种形态二选一挂载，见 [USE_BOTTOM_DOCK]。选中态动画 / 滑块指示器 / 名称显隐等
 * 全部由被挂载的那个组件内部自洽处理。
 *
 * 注意：**不自带 `padding(bottom = …)`**。底栏活在独立的 `Dialog` 窗口里，与屏幕底边的
 * 关系全部由窗口层负责（见 [CapsuleBlurHost]）：
 * - 悬浮胶囊形态：`LayoutParams.y` = 系统预留区 + 安全间距 8dp + 抬高 30dp；
 * - 贴底通栏形态：`y = 0`，栏自己铺到屏幕真实底边，安全区由**栏内 Spacer** 让位。
 * 两种形态下在这里再留一次白都会造成双重偏移。
 *
 * 另注意：通栏形态下窗口已是 `MATCH_PARENT` 宽，因此本层**不需要**「不得 fillMaxWidth」
 * 的约束（该约束针对 wrap-content 窗口：撑满宽度会把整条底部触摸圈进窗口矩形，
 * 而 `FLAG_NOT_TOUCH_MODAL` 只放行窗口**之外**的触摸）。通栏即占满那条带子；
 * 二级页整块穿透由 [CapsuleTouchGate] 的 `FLAG_NOT_TOUCHABLE` 保证。
 */
@Composable
private fun AppBottomNavigation(
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = BOTTOM_TABS.map { CapsuleTabItem(it.route, it.label, it.icon) }

    if (USE_BOTTOM_DOCK) {
        UfiBottomDock(
            tabs = tabs,
            selectedIndex = selectedIndex,
            onTabSelected = onTabSelected
        )
        return
    }

    // 不加 `Modifier.padding(CAPSULE_SHADOW_ROOM)`：那圈留白是给胶囊 `shadow(10.dp)`
    //   画到 wrap-content 窗口外留的余量。通栏形态没有四周投影（只有顶边发丝线 + 上缘高光，
    //   都画在自己边界之内），且窗口已是 MATCH_PARENT —— 留着它会在栏左右各留一道空隙。
    Box(
        contentAlignment = Alignment.Center
    ) {
        // 胶囊的淡色底 **归属 Compose 内容层**：它画在 UfiCapsuleTabBar 内部、
        //   根 Box 的 graphicsLayer 之内的那层 Box 上（clip + background），
        //   因此随收起/展开一起缩放。**它不在窗口 drawable 上**。
        // 不得把这层底色挪回 Window.setBackgroundDrawable 或主题 windowBackground：
        //   窗口背景必须恒为 null（配合 setFormat(TRANSLUCENT)），挂上 drawable 会产生
        //   胶囊外圈 1px 细线与浮窗退回不透明黑底两个缺陷。
        //   回归护栏：CapsuleRegressionGuardTest.capsuleBackground_mustBeContentLayer。
        UfiCapsuleTabBar(
            tabs = tabs,
            selectedIndex = selectedIndex,
            onTabSelected = onTabSelected
        )
    }
}

/**
 * 主导航图 — 现代圆润设计系统 + 常驻底部 5 Tab 导航。
 *
 * ## 结构
 * 5 个 Tab 是 [Routes.MAIN] 单一目的地内 `UfiPageSwitcher` 的 5 页，
 * 页间动画由可插拔的 [UfiPageTransitions] 策略驱动（支持横滑手势、可打断、可反向）。
 * 其余 detail 页留在 NavHost，按 Shared Axis X 转场。
 *
 * ## 兼容性
 * 5 个旧 Tab 路由保留为「重定向壳」：任何遗留的 `navigate("settings")` 仍可用 ——
 * 进入后立即把宿主切到对应 Tab 并 `popBackStack()`，对调用方透明。
 *
 * @param screens      路由 → 屏幕内容的映射表。
 * @param themeManager 提供 `pageTransition` 设置项（用户在设置页选择的切换动画 id）。
 * @param suppressCapsule 强制隐藏底部胶囊（当前唯一用途：启动加载页期间）。
 *   胶囊在独立 Dialog 窗口里，主窗口的全屏浮层盖不住它，只能从这里关。
 */
@Composable
// SharedTransitionLayout / SharedTransitionScope 仍是实验 API（androidx.compose.animation 1.10）。
// 只在本函数声明 OptIn，不给整个文件加 @file:OptIn —— 避免别处误用实验 API 时被静默放行。
@OptIn(ExperimentalSharedTransitionApi::class)
fun MainNavGraph(
    screens: Map<String, AppScreen>,
    themeManager: ThemeManager,
    navController: NavHostController,
    pendingSmsPhone: MutableState<String?> = mutableStateOf(null),
    pendingAlertDeepLink: MutableState<String?> = mutableStateOf(null),
    suppressCapsule: Boolean = false
) {
    // 装配六种内置策略。幂等且线程安全，重复调用无副作用。
    // 必须早于任何 UfiPageSwitcher 首次组合，否则 byId() 只能查到安全 Fade 兜底，
    // 导致用户选择的 Cube3D / Flip3D 等全部静默退化成渐入渐出。
    remember { registerBuiltInTransitions() }

    val palette = LocalResolvedPalette.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 宿主内的当前 Tab。用 rememberSaveable 以便进程重建后恢复。
    var mainTabIndex by rememberSaveable { mutableIntStateOf(0) }

    // 胶囊实时追踪：Pager 连续页位置（滑动中逐帧更新，静止时=整数页）。
    // 改用原始 State 对象以支持 lambda 形式下发进度。
    val capsuleSelectionProgressState = remember { mutableFloatStateOf(0f) }

    // 用户设置的动画过渡时长（毫秒）：**主界面（Tab）**那一档
    val transitionDurationMs by themeManager.transitionDurationMs.collectAsState()

    // 二级页转场时长：单独一档（与 Tab 档互不影响）
    val detailTransitionDurationMs by themeManager.detailTransitionDurationMs.collectAsState()

    // 用户在「外观」页选择的切换动画策略 id（如 "fade" / "slide" / "cube3d" / "flip3d"），
    // 经 UfiPageTransitions 注册表解析为具体策略；未知 id 安全回退到 Fade。
    val pageTransitionId by themeManager.pageTransition.collectAsState()

    // ── 二级页转场：唯一的时长/开关来源 ──────────────────────────────────────
    //
    // `transitionDurationMs`（用户设置）与系统「降低动效」
    // 在这里**合并成一个数**，全部 detail/host 转场与深度图层都只认它：
    //   > 0  → 合法 tween 时长（跟随用户的 150..600）
    //   == 0 → 不播转场（平移 + 下层后退缩放 + scrim 一并不生效）
    // 二级页平移与 Tab 切页必须共用同一个用户设置（否则滑块只影响半个 App）。
    // 归一化规则（含 -1 未播种哨兵）见 [ufiNavTransitionDurationMs] 的 KDoc。
    val systemReduceMotion = LocalUfiReduceMotion.current
    // 主界面（Tab）与二级页的时长**分开两档**，各自还能单独关到 0。
    // - tabTransitionMs：底部 5 个 Tab 之间（UfiPageSwitcher 的 animationSpec / 降低动效）
    // - navTransitionMs：进/出二级页（NavHost 的 enter/exit/pop + 深度层）
    val tabTransitionMs = ufiNavTransitionDurationMs(transitionDurationMs, systemReduceMotion)
    val navTransitionMs = ufiNavTransitionDurationMs(detailTransitionDurationMs, systemReduceMotion)

    // 转场位移起点的兜底宽度（px）。
    //
    // 二级页出现「从右下角长出来」时的兜底 ——
    // `slideInHorizontally { it }` 的 `it` 是目标页在**那一帧**测量的宽度，
    // 转场被中途打断（新目标页测量与转场起点同帧发生）时它可能尚未落定，
    // 起点就退化成"右缘一个点"。详见 [slideStartOffsetGuarded] 的 KDoc。
    //
    // 用 LocalConfiguration 而不是 LocalDensity：这里要的是**整屏宽度**，
    // 不是某个 dp 值换算出来的像素。旋转屏幕时该值会变，故用 remember 之外直接读。
    val fallbackSlideWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }.roundToInt()

    // Shared Axis 深度图层的「谁是下层」角色持有者。push/pop 在 exit / popEnter
    // 里登记 entry id；图层侧用 `() -> Boolean` 比对，零重组。详见 UfiNavRecedeRole。
    val recedeRole = remember { UfiNavRecedeRole() }

    // ── 下层背景模糊的两道**与页面无关**的闸门 ────────
    //
    // 1. 「设置 → 外观」里的「过渡模糊」开关。该开关同时管辖 Tab 切页
    //    （下发给 UfiPageSwitcher 的 LocalUfiBlurEnabled）与二级页转场，语义为"转场是否模糊"。
    //    因此这里把 collect 从宿主目的地内部**上提到本函数**：一处取值、两处使用
    //    （provide 给 Tab 宿主 + 喂给二级页的深度图层）。开关变化会重组整个 MainNavGraph，
    //    但它是设置页的单次操作，不在任何动画帧上。
    // 2. 设备能否承担满屏 GPU 模糊：复用页面切换的判定
    //    （UfiPageSwitcherDefaults.isBlurSupported），不另立"低端机"标准。
    //    结果在进程内恒定，按 context 记录一次。
    val blurEnabled by themeManager.blurEnabled.collectAsState()
    // 二级页转场的另外两项效果开关与「过渡模糊」同处「外观 → 切换动画」弹窗。
    // 压暗与过渡模糊**默认关闭**（部分系统上渐变效果会频闪，由用户显式开启）。
    val navScrimEnabled by themeManager.navScrimEnabled.collectAsState()
    val navCornersEnabled by themeManager.navCornersEnabled.collectAsState()
    // 可预测性返回（跟手预览）开关。关闭时在下方的 DisposableEffect 里接管返回。
    val navPredictiveBack by themeManager.navPredictiveBack.collectAsState()
    val context = LocalContext.current
    val blurSupported = remember(context) { UfiPageSwitcherDefaults.isBlurSupported(context) }
    val underlayBlurAllowed = blurEnabled && blurSupported

    // 「这一次转场的下层该不该模糊」只有登记函数知道（对端是不是升起面板），结论在
    // recedeRole 上。做成 remember 住的 lambda：图层在**绘制期**读，既不订阅快照、
    // 也不会因为换了个新 lambda 实例让 modifier 链每帧失效。
    val underlayBlurActive: () -> Boolean = remember { { recedeRole.recedingBlur } }


    // 只有停留在宿主目的地时才显示底部栏；detail 页仍然隐藏。




    // 只有停留在宿主目的地时才显示底部栏；detail 页仍然隐藏。
    //
    // [suppressCapsule]：启动加载页期间也要隐藏。胶囊活在**独立的 Dialog 窗口**里，
    // 窗口层级高于 Activity 主窗口，主窗口里铺满屏幕的启动浮层**盖不住它** ——
    // 必须从这里关闭，否则加载页上会浮着一条胶囊。
    val showBottomBar = currentRoute == Routes.MAIN && !suppressCapsule


    // ★ 把「胶囊此刻是否接收触摸」下发到窗口层。
    //
    // 胶囊窗口自首次进入 MAIN 起常驻（挂载后不再卸载，避免窗口增删闪帧），二级页只把内容
    // scale/alpha 动到 0 —— 但**窗口矩形未缩小**，而 FLAG_NOT_TOUCH_MODAL 只放行窗口
    // 矩形之外的触摸。于是二级页里屏幕底部那条带子（≈ 胶囊高度 + 抬高量）上的控件
    // 收不到事件（音乐播放页的进度条与播放键整排落在其中，表现为点击无响应或偏移）。
    // 隐藏期由 CapsuleTouchGate 给窗口补 FLAG_NOT_TOUCHABLE：窗口保留、不绘制像素、
    // 触摸整块下发给页面。
    LaunchedEffect(showBottomBar) {
        CapsuleTouchGate.interactive.value = showBottomBar
    }

    // ★★ 导航发起时刻的唯一打点 + Tab 落点清空去抖窗口 ★★
    //
    // 两项操作都只能在**目的地变化**这一刻做，而这个回调是唯一同时覆盖 push 与 pop 的地方
    // （`navigate()` / `popBackStack()` 内部同步回调，早于任何组合）：
    // 1. 帧统计里的 `启动=` 需要"导航发起 → 转场第一帧"的起点。只在 push 时打点会使
    //    退出方向缺少该指标（见 UfiNavGuard 中 2319ms 噪声的说明）。
    // 2. 回到 Tab 页时把 `ufiNavigateOnce` 的时间窗清零 —— 否则「点进 → 立刻返回 →
    //    立刻再点」若发生在 350ms 内，第三次点击会被当成连击静默丢弃。
    // 「返回连击守卫」的状态：在下方（NavHost 之后）注册回调，这里先建出来给
    // 目的地变化监听上弦用。
    val backBurstGuard = remember { BackBurstGuard() }
    DisposableEffect(navController) {
        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
            destination.route?.let { ufiNavNoteDestinationChanged(it) }
            // 同时给「返回连击守卫」上弦：popBackStack() 会**同步**走到这里，
            // 早于下一次按键派发，正是库那处越界窗口唯一可靠的信号（见下方 BackBurstGuard）。
            backBurstGuard.arm()
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }


    // SMS 通知深链接：点击短信/验证码通知后，跳转到短信对话界面。
    // pendingSmsPhone 由 MainActivity 从 Intent 中读取并写入，此处监听变化后导航。
    // 若已在 SMS 界面则跳过导航（SmsScreen 自身的 LaunchedEffect 会处理手机号变化）。
    //
    // ⚠ 这里用的是原生 `navigate()`，不是 `ufiNavigateOnce`：深链接是**程序触发**，
    //   不该被用户上一次点击留下的 350ms 去抖窗口吃掉（通知到来时用户可能刚点完别的入口）。
    //   裸 `navigate` 的豁免范围见 `UfiNavGuard.kt` 的 KDoc，回归护栏见 `NavPushGuardTest`。
    LaunchedEffect(pendingSmsPhone.value) {
        val phone = pendingSmsPhone.value
        if (!phone.isNullOrEmpty() && currentRoute != Routes.DETAIL_SMS) {
            navController.navigate(Routes.DETAIL_SMS)
        }
    }

    // P3（应用内通知）：告警系统通知深链接。点击通知后由 MainActivity 写入 pendingAlertDeepLink，
    // 此处监听变化后跳转到事件中心（Routes.DETAIL_EVENTS）。已在事件中心则跳过。
    //
    // ⚠ 同上面那条：程序触发的深链接走原生 `navigate()`，不参与点击去抖。
    LaunchedEffect(pendingAlertDeepLink.value) {
        val target = pendingAlertDeepLink.value
        if (!target.isNullOrEmpty() && currentRoute != Routes.DETAIL_EVENTS) {
            navController.navigate(Routes.DETAIL_EVENTS)
            pendingAlertDeepLink.value = null
        }
    }

    Scaffold(
        containerColor = palette.pageBg,
        // ★★ contentWindowInsets 必须显式清零 ★★
        //
        // M3 `Scaffold` 的默认值是 `ScaffoldDefaults.contentWindowInsets`（= safeDrawing，
        // 含状态栏 + 导航栏/手势条）。默认值下 `innerPadding` 的上下量非零，而 `NavHost`
        // 活在下面那个 `.padding(innerPadding)` 的 Box **里面** —— 于是：
        //   1. 转场区被 inset 内缩，两页的平移只发生在"安全区内"的那块矩形里；
        //   2. 状态栏那条带子与底部导航栏那条带子由 `Scaffold` 的 `containerColor`
        //      **静态绘制**，转场期间完全不动。
        // 表现为 inset 边界上一条「动画到此为止」的横线。
        //
        // 清零后 `innerPadding` 四边全 0，`NavHost` 区域真正满屏，
        // `MainActivity.enableEdgeToEdge()` 才真正生效（否则被本 Scaffold 抵消）。
        //
        // ⚠ 代价与配套：安全区改由**页面侧**消费，唯一落点是共用壳
        // `UfiScreenScaffold`（header 上方 `statusBarsPadding()`、内容区
        // `windowInsetsPadding(WindowInsets.navigationBars)`）。不走该壳的目的地
        // 必须自行处理（见 `UfiScaffold.kt` 的文件级说明）。
        // 回归护栏：`NavInsetHandoffGuardTest`。
        //
        // ⚠ 本 Scaffold 没有 topBar / bottomBar 槽在用（胶囊导航是独立 Dialog 窗口，
        // 见下方 `Dialog`）。新增这两个槽时必须自己加 `statusBarsPadding()` /
        // `navigationBarsPadding()`：它们不再依赖 Scaffold 的 contentWindowInsets 自动避让。
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        // 主窗口根读取胶囊共享单例，并通过 CompositionLocal 下发。
        // 胶囊 Dialog 在独立窗口（CompositionLocal 不跨 Window），页面读不到 Dialog 内的 provide；
        // 故由 CapsuleBlurHost 写共享单例 → 此处下发给 NavHost 内所有主 Tab 页。
        //
        // ★★ 这里下发的是**读值的方式**，不是值 ★★
        //
        // 若写成 `val capsuleBottomInset = CapsuleInsetHolder.bottomInset.value` 再
        // `LocalCapsuleBottomInset provides capsuleBottomInset`，两个事实会叠加：
        //   1. `LocalCapsuleBottomInset` 是 `staticCompositionLocalOf` —— 它**没有**细粒度
        //      读追踪，值一变，provider 以下**整棵树无条件重组**（static 变体的定义）；
        //   2. 在**组合期**解引用 `.value` 会把 MainNavGraph 订阅进那条 MutableState。
        // 后果是返回动画头几帧整棵 NavHost 子树重组，且兜底值 88dp 与真值相差约 50dp，
        // 同时表现为卡顿与位置跳动。
        //
        // 因此 provide 一个**稳定的 `() -> Dp`**（remember 一次，实例不变）⇒ static local
        // 的值永远不变 ⇒ 不触发整树重组；对 `CapsuleInsetHolder.bottomInset` 的
        // 快照读被推迟到消费方的 **layout 阶段**（见 `Modifier.ufiCapsuleBottomInset`），
        // inset 变化只失效那一个节点的测量。
        //
        // ⚠ 护栏：`NavInsetHandoffGuardTest.capsuleInset_providerMustNotDereferenceAtComposition`
        //   禁止此处再出现组合期 `.value`。
        val capsuleBottomInsetProvider: () -> Dp = remember { { CapsuleInsetHolder.bottomInset.value } }
        CompositionLocalProvider(
            LocalCapsuleBottomInset provides capsuleBottomInsetProvider,
            LocalPendingSmsPhone provides pendingSmsPhone,
            // 页内「列表 → 明细」用的时长：与全局二级页转场同源。
            // 页面自己那层 AnimatedContent 读它，避免"点进去没有动画"或时长与别处不一致。
            LocalUfiDetailTransitionMs provides ufiSharedAxisDurationMs(navTransitionMs)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // `innerPadding` **四边恒为 0**（见上方 contentWindowInsets 的说明）。
                    // 仍然保留这一句：它是 Scaffold 契约的一部分 —— 若给本 Scaffold 加回
                    // topBar / bottomBar，槽位高度只会从这里下发，删掉会让新加的栏直接盖住 NavHost。
                    .padding(innerPadding)
                    // 启动动画：整个导航区淡入 + 轻微上移，**整个 App 生命周期只播一次**。
                    // 挂在根节点上使语义等于"应用启动"：只有冷启动会动；若由每个页面各自播放，
                    // 首次切到某个 Tab、或从二级页返回导致宿主组合重建时都会各播一次。
                    // （胶囊导航栏在独立 Dialog 窗口，不受本 graphicsLayer 影响，它有自己的入场动画。）
                    .blurEntrance("app-launch"),
                contentAlignment = Alignment.BottomCenter      // 胶囊浮层锚定底部居中
            ) {
            // 共享元素的宿主。★ 必须在 NavHost **外面** ★
            //
            // 两个理由，都不能绕：
            // 1. 共享元素要跨两个目的地，而两个目的地只在 NavHost 之上才有共同祖先；
            // 2. 转场期每个目的地都套着 `ufiSharedAxisLayer`，那一层用 clipPath 对整页做圆角裁剪
            //    （见 Navigation.kt）。共享元素默认 renderInOverlayDuringTransition = true，
            //    会被提到 SharedTransitionLayout 的 overlay 层绘制，从而绕开那层裁剪 ——
            //    overlay 属于本布局，所以本布局必须在裁剪层之上。
            //
            // 没有任何页面用共享元素时，这一层的开销只是一个 LookaheadScope 容器；
            // 它不改变子树的测量结果，因此对现有页面是透明的。
            SharedTransitionLayout {
            CompositionLocalProvider(LocalUfiSharedTransitionScope provides this) {
            // 二级页：Material Shared Axis X（纯水平，无交叉淡入淡出）。
            // Tab 之间仍由宿主内 UfiPageSwitcher 负责。
            // 可预测性返回开关的**运行时**实现。
            //
            // manifest 的 `enableOnBackInvokedCallback` 是编译期开关、无法运行时切换；所以"关闭"
            // 用一个**非预测性**返回回调接管：系统不再驱动跟手进度，`popBackStack()` 触发
            // popExitTransition，按「动画时长」完整播放（快滑不会缩短它）。
            //
            // ⚠ 根因约束：**navigation-compose 2.8.0 的 `NavHost` 自己注册了一条
            // `PredictiveBackHandler`**（见其 NavHost.kt:514），与
            // `navController.enableOnBackPressed(false)` 无关 —— 后者只关掉 NavController
            // 自己那条 `OnBackPressedCallback`，预测性手势仍由 NavHost 那条回调消费。
            //
            // `OnBackPressedDispatcher` 取最后注册且启用的回调派发（activity 1.8.2
            // OnBackPressedDispatcher.kt:233/271：`lastOrNull { it.isEnabled }`）。因此本 effect
            // 必须写在 `NavHost` **之后**，否则回调先注册，永远抢不到返回，
            // "关闭"在运行时是死代码。
            //
            // PredictiveBackHandler 的注册只在 lifecycleOwner / backDispatcher 变化时重做，
            // 返回栈变化只改 `isEnabled`，不会重新插到本回调之后；Activity 重建时两边按
            // 同一源码顺序重注册，本回调仍在最后。诊断日志里的 `effective` 因此才与实际生效值一致。

            NavHost(
                navController = navController,
                startDestination = Routes.MAIN,
                // ★★ 新页面从底部飞出：sizeTransform 必须为 null ★★
                //
                // 触发条件：**切换页面时数据未加载完就退出，再进入任意二级页，页面大概率从底部飞出**。
                //
                // 根因：`NavHost` 的 `sizeTransform` 默认是 `SizeTransform()` —— AnimatedContent 会在
                // 两个目的地之间**补间容器尺寸**，并把子节点在容器内**居中摆放**。平时所有二级页都是
                // 整屏，尺寸相同、补间无效果，所以看不出来。但"数据没加载完就退出"那一刻，退出页的
                // 高度可能还没落到整屏（骨架/占位与真实内容高度不同），于是这次退出记住了一个
                // **非整屏的高度**；下一次进任何二级页时，容器要从那个高度补间到整屏 ——
                // 子节点居中 ⇒ 新页的顶边从下方缓缓推到顶边，观感正是"界面从底部飞出"。
                //
                // 这与 `SmsScreen` 里 `AnimatedContent(sizeTransform = null)` 是同一个决定：
                // 整屏页面之间没有"尺寸也要过渡"的语义，容器尺寸补间只会带来这种伪位移。
                // 关掉后容器恒为整屏，位移只剩我们显式写的那条横向 Shared Axis。
                sizeTransform = null,
                enterTransition = { detailSharedAxisEnter(navTransitionMs, fallbackSlideWidthPx) },
                // 与下面每一条登记**逐项对齐**（见 MAIN 那一条上的说明）：
                // 这几条只是兜底，但"钉住默认值"的意义就在于保持兜底与显式登记一致。
                //
                // 这里与 MAIN / DETAIL 三条登记一起**撤掉了下层模糊**
                // （原为 `blur = underlayBlurAllowed`）。逐帧开销写在下面 DETAIL 循环开头：
                // 下层的模糊是「每帧重录整棵下层子树 + 每帧全屏 110px RenderEffect」，
                // 而它同时还在平移。压暗与圆角两项不受影响。
                exitTransition = { detailSharedAxisExit(navTransitionMs, recedeRole, fallbackSlideWidthPx, blur = true) },
                popEnterTransition = {
                    detailSharedAxisPopEnter(navTransitionMs, recedeRole, fallbackSlideWidthPx, blur = true)
                },
                popExitTransition = { detailSharedAxisPopExit(navTransitionMs, fallbackSlideWidthPx) }
            ) {
            // ── Tab 宿主目的地：5 个 Tab 作为 UfiPageSwitcher 的 5 页
            composable(
                route = Routes.MAIN,
                // 宿主与二级页同一套 Shared Axis，进/回二级页时两侧同步。
                enterTransition = { detailSharedAxisEnter(navTransitionMs, fallbackSlideWidthPx) },
                /*
                 * ★ 两条登记的效果参数必须**逐项对齐**：
                 *
                 * 进入二级页有两条来路（Tab 宿主 → 二级页、二级页 → 二级页）；若「压暗 / 圆角」
                 * 只在其中一侧传值，同一个操作会得到两套观感：从 Tab 进音乐时背后那张 Tab 页
                 * 不模糊、且忽略用户的「压暗 / 圆角」设置；从音乐进音乐设置时三项全部生效。
                 *
                 * 当前两侧一致：`blur` 均为 false（下层不做真实模糊，论证见下面 DETAIL 循环
                 * 开头那段），**压暗 / 圆角两项保留**并听用户开关。
                 */
                exitTransition = { detailSharedAxisExit(navTransitionMs, recedeRole, fallbackSlideWidthPx, blur = true) },
                popEnterTransition = {
                    detailSharedAxisPopEnter(navTransitionMs, recedeRole, fallbackSlideWidthPx, blur = true)
                },
                popExitTransition = { detailSharedAxisPopExit(navTransitionMs, fallbackSlideWidthPx) }
            ) { entry ->
                // 先把导航层的 AnimatedVisibilityScope 抓在手里：下面要在几层 lambda 内部
                // provide 它，而那些 lambda 里的 `this` 已经是别的 receiver 了。
                //
                // Tab 宿主目前没有任何页面用共享元素，仍然 provide：漏掉这一处的后果是
                // 「将来从某个 Tab 页挂 ufiSharedElement 时它静默失效」——读到 null 就退化成
                // 不共享，既不报错也没有动画，排查成本高于显式 provide。
                val navAnimatedScope: AnimatedVisibilityScope = this
                // 深度层：去二级页时宿主是下层（压暗+后退）；回宿主时宿主仍是下层。
                //
                // 效果参数与二级页那一条**逐项相同**（理由见上面 exitTransition 的说明）：
                // 转场长什么样只取决于"谁在上面"。
                //
                // `blurUnderlay` 保持恒 true 是**允许**而不是执行：宿主这条登记不会把
                // recedingBlur 置 true，绘制期因此永远走"只压暗"那一支，那张离屏图层不会被录制
                // （空 GraphicsLayer 只是一只未录制的 RenderNode，不占缓冲）。逐帧开销见 DETAIL
                // 循环开头。
                val axisLayer = ufiSharedAxisLayer(
                    isReceding = { recedeRole.recedingEntryId == entry.id },
                    durationMillis = navTransitionMs,
                    blurUnderlay = underlayBlurAllowed,
                    underlayBlurActive = underlayBlurActive,
                    clipCorners = { navCornersEnabled },
                    // 压暗与过渡模糊**默认关闭**（外观 → 切换动画里可开）。
                    scrimEnabled = navScrimEnabled,
                )

                // ★★ 宿主重新进入时**错峰组合** ★★
                //
                // pop 时宿主目的地被重新组合，而 `UfiPageSwitcher` 的 `keepPagesAlive`
                // 默认为 true ⇒ `beyondViewportPageCount` = 页数-1 = 4 ⇒ **5 个 Tab 页在同一帧里
                // 全部组合**（含全 App 最重的设置页）。那一帧既要组合 5 个整屏子树、又要开始平移，
                // 实测可顶到 33.3ms（22 次 push 满帧，11 次 pop 中 6 次掉 1 帧，`frames` 只有
                // 24~28，比 push 的 30 少 2~6 帧）。掉的是**第一帧尚未产出**，不是动画被缩短。
                //
                // 修法与 `beyondViewportPageCount` 无关（**不得**在转场途中修改它 —— 那会让
                // LazyLayout 重测量，与滚动动画抢时序，见 UfiPageSwitcherHost 的
                // `baseBeyond` 上方说明）。这里只改**页内容何时组合**：
                //   · 当前选中的那一页立刻组合（visible 的是它，必须第一帧就有内容）；
                //   · 其余 4 页先放一个同尺寸空 Box，等本次导航转场落定后**一帧补一页**。
                //
                // 为什么必须"一帧补一页"而不是一次性补齐：4 个整屏页一起组合同样是一次 30ms+
                // 的尖峰，只是从动画中段挪到了动画刚落位那一帧 —— 用户照样看得到顿一下。
                // 摊成一帧一页，每帧只多 ~1/5 的组合量，且这些页此刻**不可见**（Pager 只绘可见页），
                // 没有任何可见代价。
                //
                // 兜底：`withTimeoutOrNull` 保证可预测性返回被手指按住不放时，1.5s 后照样补齐 ——
                // 绝不能出现"某几个 Tab 永远是空的"。
                //
                // 页是否"当前"用 [LocalUfiPageActive] 判断，而不是在组合期读 `mainTabIndex`：
                // 后者会让整个宿主内容订阅 tab 下标（每次切页都白重组一层），而前者本身就是
                // 每页一个的稳定容器（见 UfiPageSwitcherHost 的 pageActiveStates）。
                val tabWarm = remember { List(TAB_ROUTES.size) { mutableStateOf(false) } }
                LaunchedEffect(tabWarm) {
                    // 等到"本段转场已落定"。兜底 1.5s：可预测性返回可能被手指按住不放。
                    val awaitSettled: suspend () -> Unit = {
                        withTimeoutOrNull(1_500L) {
                            snapshotFlow {
                                navAnimatedScope.transition.currentState ==
                                    navAnimatedScope.transition.targetState
                            }.first { it }
                        }
                        Unit
                    }
                    // 先让一帧：宿主刚组合的那一刻，"转场是否已开始"可能还没写进 `transition`
                    // （AnimatedContent 在同一帧稍后才推进），此时读到 `currentState == targetState`
                    // 会误判成"没有转场"而立刻补齐 —— 等于什么都没做。等一帧再问，pop 方向
                    // 必然已是"未落定"。
                    withFrameNanos { }
                    awaitSettled()
                    // 落定后再**延迟 220ms** 才开始补齐，而不是下一帧。
                    //
                    // 理由：这 4 个整屏页的组合是一次不小的分配，紧随其后的 GC 很可能落在
                    // "退出后立即再点一个入口"的那段转场里 —— 实测 push 的 `最差帧` 掉在 @1~@4、
                    // 且 `frames` 比正常少 5~7 帧（多帧偏慢，不是单点尖峰）。
                    // 220ms 足以让分配落地，也让手指从"返回"移到下一个入口；
                    // 期间若切到了某个 Tab，`active` 判据会立即把它组合出来，不会空白。
                    delay(220L)
                    tabWarm.forEach { state ->
                        if (!state.value) {
                            // 补的过程中又开了一段转场（快速退出重进）就先等待，避免争抢帧。
                            if (navAnimatedScope.transition.currentState !=
                                navAnimatedScope.transition.targetState
                            ) {
                                awaitSettled()
                            }
                            state.value = true
                            withFrameNanos { }
                        }
                    }
                }

                // 缓存页列表，避免每次重组都重建 5 个 UfiPage 与其 content lambda。
                val tabPages = remember(screens, navController, entry, tabWarm) {
                    TAB_ROUTES.mapIndexed { index, route ->
                        UfiPage(
                            key = route,
                            content = {
                                val warmed = tabWarm[index].value
                                // 只读本页的 active 容器：当前页第一帧就必须组合。
                                val active = LocalUfiPageActive.current.value
                                if (warmed || active) {
                                    screens[route]?.invoke(entry, navController)
                                } else {
                                    Box(Modifier.fillMaxSize())
                                }
                            }
                        )
                    }
                }

                // 帧闸门也必须套在宿主目的地上：返回过程中真正做重活的是**宿主** ——
                // 宿主根节点收 dashboardState 这一个大对象，一条 WS 推送 = 一次整屏重组，
                // 而返回时宿主正在重新组合 5 个 Tab 页，两者叠在平移的同几帧里。
                UfiNavFrameGate(route = Routes.MAIN) {
                CompositionLocalProvider(
                    LocalUfiMainTabController provides { targetIndex -> mainTabIndex = targetIndex },
                    LocalUfiNavAnimatedScope provides navAnimatedScope
                ) {
                    // blurEnabled 已上提到本函数顶部（见那里的说明：同一个用户开关同时
                    // 管 Tab 切页与二级页的下层模糊，取值只能有一处）。

                    // 转场时长滑块的下端是「0 = 关闭转场」。
                    // 落地方式**不是**传 tween(0)：那样两页仍会走一遍 AnimatedContent /
                    // Pager 的组合与模糊管线，只是时长为 0，既有开销还可能闪一帧。改为把「关闭」
                    // 翻译成宿主已有的降低动效通道（LocalUfiReduceMotion → scrollToPage + 极短淡入），
                    // 与系统「移除动画」走**同一条**代码路径，不新增第二种"无动画"实现。
                    // 系统设置（MainActivity 探测 TRANSITION/ANIMATOR_DURATION_SCALE == 0 后注入）
                    // 与用户开关是 or 关系：任一为真即降级，两者互不覆盖。
                    //
                    // 该判据已收进 [ufiNavTransitionDurationMs]，`navTransitionMs == 0` 即其结论，
                    // 二级页与 Tab 切页共用同一个开关。
                    val reduceMotion = tabTransitionMs == 0
                    CompositionLocalProvider(
                        LocalUfiBlurEnabled provides blurEnabled,
                        LocalUfiReduceMotion provides reduceMotion,
                    ) {
                        UfiPageSwitcher(
                            pages = tabPages,
                            selectedIndex = mainTabIndex,
                            onSelectedIndexChange = { mainTabIndex = it },
                            onSelectionProgressChange = { capsuleSelectionProgressState.floatValue = it },
                            // ★ 目的地自带不透明底色，必须放在 axisLayer **之后**（链上更内层）——
                            //   这样圆角 clip 会把底色一并切成圆角；顺序相反时方形底色会盖住裁剪。
                            //   axisLayer 负责转场期圆角/scrim（进场新页圆角 20dp→0）。
                            modifier = Modifier
                                .fillMaxSize()
                                // ⚠ 顺序同 DETAIL：axisLayer（圆角 clip）在外，底色在内。
                                .then(axisLayer)
                                .background(palette.pageBg),

                            transition = UfiPageTransitions.byId(pageTransitionId),
                            swipeEnabled = true,
                            // 切换动画由用户在「外观」页选择（默认渐入渐出）。远程多页跳转走原生
                            // 逐页匀速扫场以保留中间页动画（避免 pivot 方案丢失中间页）；
                            // 降低动效下退化为瞬移 + 极短淡入。
                            respectReducedMotion = true,
                            animationSpec = tween(
                                // 关闭档（0）时本 spec 走不到（reduceMotion 分支不读它），
                                // 但 tween 的 durationMillis 必须合法，故夹到 ≥1。
                                // 必须读 navTransitionMs（已归一化）而不是原始的 transitionDurationMs ——
                                // 后者在 prefs 播种前是 -1，`coerceAtLeast(1)` 会把
                                // 那一瞬间的 Tab 切页变成 1ms 瞬变。
                                durationMillis = tabTransitionMs.coerceAtLeast(1),

                                // 曲线口径（三条路径统一）：
                                // - (0.2, 0, 0, 1)：起步斜率极陡，开头几帧就要走掉大半屏位移，
                                //   而那几帧恰好也是入页首帧组合最贵的时候 → 观感"跳帧"；
                                // - spring(dampingRatio 0.8)：尾段回弹被感知成"抽一下"；
                                // - (0.05, 0.7, 0.1, 1)（M3 emphasized-decelerate）：时间轴的两个
                                //   控制点都极小（0.05 / 0.1），导致进度被严重前置 —— 代入贝塞尔算
                                //   一下：时间走到 18% 时位移已完成 76%，走到 40% 时已完成 92%，
                                //   剩下 60% 的时长（380ms 里约 230ms）只挪最后 8%（1080px 宽屏上
                                //   ≈2px/帧）。这就是"开头丝滑、快结束时变慢并抖"的来源：
                                //   亚像素步进本身就会被看成抖动，且这段时间模糊与两页混合仍在
                                //   每帧照付开销，动得越少越显得卡。
                                //
                                // 不使用 (0.4, 0, 0.2, 1)（FastOutSlowIn）：其第二控制点为 (0.2, 1)，
                                // 末端切线斜率**恰好为 0**，最后 20% 的时间只走 2.5% 的行程
                                // （1080px 屏上约 27px / 76ms），画面看起来已经停了、实际仍在移动。
                                // 这段窗口里任何一次掉帧都会被读成顿挫。
                                //
                                // 现用 (0.25, 0.10, 0.25, 0.92)：末端切线斜率 ≈ 0.107（不为 0），
                                // 同样 20% 时间走 4.4% 行程，低速段**缩短约 40%**，且起步仍平缓
                                // （起点斜率 0.3，不是"猛冲"）。y 全程单调（0.3→1.365→0.24），无过冲。
                                easing = CubicBezierEasing(0.25f, 0.10f, 0.25f, 0.92f),
                            ),
                        )
                    } // LocalUfiBlurEnabled / LocalUfiReduceMotion
                } // LocalUfiMainTabController
                } // UfiNavFrameGate
            }

            // ── 兼容重定向壳：遗留的 navigate("dashboard"/"settings"/…) 仍可用。
            //    用 None 转场，使这一跳「无感」——壳内容为空，若走淡入淡出会闪一下白屏。
            TAB_ROUTES.forEachIndexed { index, route ->
                composable(
                    route = route,
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None }
                ) {
                    LaunchedEffect(Unit) {
                        mainTabIndex = index
                        // 弹到 MAIN（不含 MAIN 自身），而不是只弹掉本壳。
                        // 若只用无参 popBackStack()，从 detail 页触发时会退回那个 detail 页，
                        // 用户点了「设置」却仍停在原页面 —— 只有 mainTabIndex 被静默改掉。
                        navController.popBackStack(route = Routes.MAIN, inclusive = false)
                    }
                }
            }

            // ── 其余 detail 页：Shared Axis X（与 NavHost 默认一致，显式钉住防止未来改默认）
            //
            // exit / popEnter 多了一个分支：这两个方向描述的是**下层**（留在后面 / 重新露出的
            // 那一页），而对端可能是升起面板（音乐播放页，TransitionType.RISE）。面板竖着升起时
            // 下层再横滑，就是"两层往两个方向晃"—— 正是 RISE 要修掉的观感，所以对端是 RISE 时
            // 换成不动的下层处理。其余任何两页之间的转场一字未动。
            //
            // ★ 判据是"对端是 RISE 路由 **且**本页自己挂着迷你条"（[isUfiMiniBarHostRoute]）。
            //   播放页的进场动画按**来源**二选一（见下面 RISE 循环）：来源没有迷你条时退回横向
            //   共享轴。上下两层必须用同一条判据，否则会出现"上层横着滑进来、下层却静止 + 模糊"
            //   这种半套动画。目前能跳到播放页的 DETAIL 页都挂迷你条；新增的 DETAIL 页若没有
            //   迷你条，会自动跟着上层退回普通横向转场。
            //
            // ★★ 二级页转场：下层**不做真实模糊**，只保留压暗 ★★
            //
            // 表现：进/出二级页时整段动画掉帧（低端机尤甚），与"进的是哪一个二级页"无关。
            //
            // 逐帧开销。`ufiSharedAxisLayer` 的下层分支在
            // `underlayBlurActive()` 为真时，每一帧都要执行两步：
            //   ① `blurLayer.record { drawContent() }` —— 把**整棵下层子树**的绘制指令重录一遍。
            //      从 Tab 页进二级页时，这棵子树是 UfiPageSwitcher 里 3 个已组合的页
            //      （beyondViewportPageCount = 1，当前页 ± 相邻页），是全局最重的一棵
            //      （胶囊不在这里 —— 它在独立 Dialog 窗口）；
            //   ② 给这张全屏图层挂半径最大 110px 的 `RenderEffect` 再合成 —— 半径取自弹窗的
            //      `UfiDialogAnim.BlurRadius`，而它原本服务的是"静止背景 + 只糊一次峰值"。
            // 而二级页的下层**全程还在做 1/6 屏的视差平移**，所以 ①+② 是每帧实打实地付，
            // 付满整段转场（默认 380×1.2 ≈ 456ms ≈ 28 帧）。最不划算的是收益：下层此刻被上层
            // 盖住，真正露出来的只有左侧一条窄边 —— 全屏模糊的钱，一条边的画面。
            //
            // 为什么不是"把半径调小"：Tab 切页链路同样为此把峰值从 18dp 降到 12dp
            // （见 `UfiPageSwitcherHost.TRANSITION_BLUR_MAX_RADIUS_DP`），降完仍只在 2D 策略上
            // 保留。转场模糊这条路上，**"下层在动"本身就是不该做实时模糊的判据** —— 平台自己也
            // 只在静止 backdrop 上做（弹窗 FLAG_BLUR_BEHIND / M3 modal scrim）。所以这里不调参，
            // 而是把这一种处境整段摘掉。
            //
            // 代价（视觉差异）：二级页转场时背后那页不再模糊，层次改由
            // **压暗（scrim 峰值 0.5）+ 1/6 屏视差 + 上层圆角**表达。RISE 面板那条路径
            // （下层**静止**、被面板整块盖住）**仍然保持模糊**，未做改动 —— 那才是"背景像弹窗
            // 背景那样模糊"的原始诉求。外观里的「过渡模糊」开关依旧有效（Tab 切页 + RISE 面板）。
            //
            // 恢复方式：把 `blur = underlayBlurAllowed` 加回下面两条登记。恢复前必须确认
            // `Navigation.kt` 里 `detailSharedAxisExit` 的 `blur` 形参约定：那里已把
            // "只允许静止下层"写成契约，恢复它等于把卡顿一起恢复。
            appRoutes.filter { it.transition == TransitionType.DETAIL }.forEach { appRoute ->
                composable(
                    route = appRoute.route,
                    arguments = appRoute.arguments,
                    enterTransition = { detailSharedAxisEnter(navTransitionMs, fallbackSlideWidthPx) },
                    exitTransition = {
                        if (isUfiMiniBarHostRoute(appRoute.route) &&
                            isUfiRiseRoute(targetState.destination.route)
                        ) {
                            // 下层**静止**（面板竖着盖上来），模糊留给它 —— 与上面那段不冲突。
                            riseUnderlayExit(navTransitionMs, recedeRole)
                        } else {
                            // 不传 `blur`（= false）⇒ 下层的 recedingBlur 为 false
                            // ⇒ 绘制期 `underlayBlurActive()` 返回 false ⇒ 不录制、不挂
                            // RenderEffect，只剩一次 scrim 的 drawRect。理由见本循环开头。
                            detailSharedAxisExit(navTransitionMs, recedeRole, fallbackSlideWidthPx, blur = true)
                        }
                    },
                    popEnterTransition = {
                        if (isUfiMiniBarHostRoute(appRoute.route) &&
                            isUfiRiseRoute(initialState.destination.route)
                        ) {
                            riseUnderlayPopEnter(recedeRole)
                        } else {
                            // 同 exitTransition：pop 回来时下层同样只压暗、不模糊。
                            detailSharedAxisPopEnter(navTransitionMs, recedeRole, fallbackSlideWidthPx, blur = true)
                        }
                    },
                    popExitTransition = { detailSharedAxisPopExit(navTransitionMs, fallbackSlideWidthPx) }
                ) { entry ->
                // 深度层：进二级页时上层圆角描边（满屏不缩放）；返回时 detail 是上层纯平移。
                //
                // blurUnderlay：本页作为下层时**允许**背景模糊，但只在"对端是升起面板"那种
                // 处境才真的模糊 —— 那条判定由 underlayBlurActive 在绘制期问 recedeRole，
                // 与上面 exitTransition / popEnterTransition 里的 isUfiRiseRoute 分支同源
                // （那两条分支就是写入方）。普通详情页当垫底时只压暗（见本循环开头）。
                // 这里保持 true 而不是写死 false：这一页**既可能**给升起面板垫底（要模糊）、
                // **也可能**给普通详情页垫底（此时不模糊），两者共用同一份图层。
                val axisLayer = ufiSharedAxisLayer(
                    isReceding = { recedeRole.recedingEntryId == entry.id },
                    durationMillis = navTransitionMs,
                    blurUnderlay = underlayBlurAllowed,
                    underlayBlurActive = underlayBlurActive,
                    // 压暗 / 圆角可由用户在「外观 → 切换动画」中关闭。默认 true = 原行为。
                    clipCorners = { navCornersEnabled },
                    // 压暗与过渡模糊**默认关闭**（外观 → 切换动画里可开）。
                    scrimEnabled = navScrimEnabled,
                )

                // ★ 每个 detail 目的地**自己画不透明底色**（`.background(palette.pageBg)`），
                //   不能只靠外层 Scaffold 的 containerColor。targetSdk 36 上系统「可预测性手势
                //   返回」默认开启，navigation-compose 在手势进行中会同时组合「即将退出的 detail 页」
                //   和「下面那一层」，并把 detail 页整体丢进 graphicsLayer 做位移/圆角/scrim。
                //   该图层只包含本页**自己绘制的像素** —— 页面没有底色时，卡片与图标之间全是
                //   透明区，手势中直接看到下层内容。
                //
                // ★★ 底色与 axisLayer 的**顺序**：axisLayer 在外、底色在内 ★★
                // Compose 的 modifier 链**靠前的先绘制（在外层）**，而 `ufiSharedAxisLayer` 是
                // `drawWithContent`：它的 `clipPath` 只作用于自己的 `drawContent()`，即**链上更内层**
                // 的绘制与页面内容。写成 `.background(pageBg).then(axisLayer)` 时，不裁切的整屏
                // 方形底色先画完，圆角外的四块被它填满 ⇒ **圆角裁切执行但不可见**。
                // 写成 `.then(axisLayer).background(pageBg)` 后底色进入 clip 内部：圆角外露出的是
                // 下层页面（带着 scrim 与反向视差在动），圆角因此可见；底色本身仍在，
                // 可预测性手势返回所需的"本页不透明像素"没有减少。
                // ★ UfiNavFrameGate：转场/手势期间压住 WS 整屏重组（性能用，不是动画）。
                // ★ LocalUfiNavAnimatedScope：把**导航层**的 AnimatedVisibilityScope 下发给页面。
                //   必须包住 `screens[...].invoke`，共享元素（Modifier.ufiSharedElement）才找得到
                //   它要跨的那次转场；页面内部自己的 AnimatedContent 提供的同类型 scope 不能用，
                //   那是页内动画、对端不在另一个目的地上。
                CompositionLocalProvider(LocalUfiNavAnimatedScope provides this) {
                UfiNavFrameGate(route = appRoute.route) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // ⚠ 顺序：axisLayer（含圆角 clip）在外，底色在**内** —— 见上方说明。
                        // 反过来写会让不裁切的方形底色把圆角外的四块填满，圆角完全看不见。
                        .then(axisLayer)
                        .background(palette.pageBg)
                ) {
                    screens[appRoute.route]?.invoke(entry, navController)
                }
                }
                }
            }
            }

            // ── 升起面板（TransitionType.RISE）：目前只有音乐播放页。
            //
            // 与上面那个 DETAIL 循环**互斥**：那边 filter 的是 `== TransitionType.DETAIL`，
            // RISE 路由不会被它捞进去，同一条 route 不会登记两次（登记两次会让
            // NavHost 抛 IllegalArgumentException，不是静默问题，但也别指望靠崩溃来发现）。
            //
            // ★ 升起动画**按来源二选一**，不是无条件。
            //   这套动画是"底部迷你控制条长成整页"，起点是那条控制条的上沿；只有从挂着迷你条的
            //   页面（[isUfiMiniBarHostRoute]：音乐列表页 / 音乐分组页）进来时那条线才在屏上。
            //   从别处（仪表盘等 Tab 页点标题栏的「正在播放」挂件）进来时没有起点，升起会读成
            //   "页面凭空从屏幕外飞进来"，那条路径也未挂共享元素 —— 所以退回原来的横向
            //   shared-axis。**不给播放页再开第二条路由**：`AnimatedContentTransitionScope`
            //   已经把 `initialState` / `targetState` 递到手上，来源信息本来就在这儿。
            //   exit / popEnter 不分支：那两个方向描述的是"播放页自己当下层"（它往更深一层去、
            //   或从更深一层返回），此刻它的形态与"从哪来"无关，照旧是面板语义。
            //
            // 结构与 DETAIL 循环逐行对齐。三件事必须原样保留：
            //   1. LocalUfiNavAnimatedScope 包住 `screens[...].invoke` —— 迷你条封面到播放页
            //      封面那条共享元素靠它找到对端；
            //   2. ufiSharedAxisLayer —— scrim 那部分仍要（从播放页再往里进一层时面板是下层）；
            //      圆角裁剪按语义开关（见下方 `clipCorners`）：升起时关、退化成横向时照常开。
            //   3. UfiNavFrameGate —— 转场期压住 WS 整屏重组。
            appRoutes.filter { it.transition == TransitionType.RISE }.forEach { appRoute ->
                composable(
                    route = appRoute.route,
                    arguments = appRoute.arguments,
                    enterTransition = {
                        // 来源挂着迷你条 ⇒ 升起有起点。同时把这次的语义记进 recedeRole，
                        // 供下面的 clipCorners 在**绘制期**读（组合期还不知道来源是谁）。
                        val fromMiniBarHost = isUfiMiniBarHostRoute(initialState.destination.route)
                        recedeRole.upperIsRisePanel = fromMiniBarHost
                        if (fromMiniBarHost) {
                            risePanelEnter(navTransitionMs)
                        } else {
                            detailSharedAxisEnter(navTransitionMs, fallbackSlideWidthPx)
                        }
                    },
                    exitTransition = { risePanelExit(navTransitionMs, recedeRole) },
                    popEnterTransition = { risePanelPopEnter(navTransitionMs, recedeRole) },
                    popExitTransition = {
                        // 回哪去决定怎么关：落回迷你条上沿只在那条线会回到屏上时成立。
                        // 去与回必须是同一段行程的正反，否则会出现"横着进来、竖着落下去"。
                        val toMiniBarHost = isUfiMiniBarHostRoute(targetState.destination.route)
                        recedeRole.upperIsRisePanel = toMiniBarHost
                        if (toMiniBarHost) {
                            risePanelPopExit(navTransitionMs)
                        } else {
                            detailSharedAxisPopExit(navTransitionMs, fallbackSlideWidthPx)
                        }
                    }
                ) { entry ->
                val axisLayer = ufiSharedAxisLayer(
                    isReceding = { recedeRole.recedingEntryId == entry.id },
                    durationMillis = navTransitionMs,
                    // 圆角：升起语义下不启用（贴底长出来的一整块，加圆角会读成浮在屏上的卡片），
                    // 退化成横向 shared-axis 时要（横着推进来的卡片本来就该有圆角，
                    // 少了它这条路径会变成方角整屏硬切）。
                    //
                    // ⚠ 这里必须是**绘制期**才问的 lambda，不能在组合期算：图层是本目的地
                    //   登记时构造的一份，而"来源是谁"只有上面 enter / popExit 被调用的那一刻
                    //   才知道 —— 那两处已经把结论写进了 recedeRole，且它们必然在本页作为上层
                    //   被画之前跑过。读普通 var 不建立快照依赖 ⇒ 零重组（与 isReceding 同一套）。
                    clipCorners = { !recedeRole.upperIsRisePanel },
                    // 面板自己退作下层时（从播放页再往里进一层）也吃模糊：此刻它的角色与
                    // "给面板垫底的列表页"完全一样。判定仍走 recedeRole（risePanelExit /
                    // risePanelPopEnter 写 true），不在这里另立标准。
                    //
                    // blurUnderlay 保持恒 true（只受用户开关与机型闸门约束）：它是**组合期**
                    // 常量、决定是否为这个目的地准备那张离屏图层，而"这一次该不该真模糊"
                    // 由绘制期的 underlayBlurActive 回答。两者的时序差正是这样拆开的。
                    //
                    // 从 Tab 页横着进播放页时下层（Tab 宿主）不模糊，与"从二级页进播放页"一致 ——
                    // 两条路径都由各自的登记决定，这里**不需要**补任何分支（下层模糊已在
                    // DETAIL / MAIN 两条登记上撤掉，见 DETAIL 循环开头）。
                    blurUnderlay = underlayBlurAllowed,
                    underlayBlurActive = underlayBlurActive,
                    // 与 DETAIL / MAIN 两条登记对齐：用户关掉「压暗」时面板当垫底也不该压暗
                    // 压暗与过渡模糊**默认关闭**（外观 → 切换动画里可开）。
                    scrimEnabled = navScrimEnabled,
                )

                CompositionLocalProvider(LocalUfiNavAnimatedScope provides this) {
                UfiNavFrameGate(route = appRoute.route) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // ⚠ 顺序：axisLayer（含圆角 clip）在外，底色在**内** —— 见上方说明。
                        // 反过来写会让不裁切的方形底色把圆角外的四块填满，圆角完全看不见。
                        .then(axisLayer)
                        .background(palette.pageBg)
                ) {
                    screens[appRoute.route]?.invoke(entry, navController)
                }
                }
                }
            }
            }

            }
            } // LocalUfiSharedTransitionScope
            } // SharedTransitionLayout

            /*
             * 可预测性返回开关的运行时接管。
             *
             * 必须放在 NavHost **之后**：NavHost（navigation-compose 2.8.0）自己会通过
             * PredictiveBackHandler 往同一个 OnBackPressedDispatcher 注册一条回调；该 dispatcher
             * 按「后注册者优先」派发（`lastOrNull { isEnabled }`）。放在 NavHost 之前注册的回调
             * 永远抢不到返回 —— 这正是"开关显示关闭、实际仍跟手"的根因。
             *
             * 关闭时：先关掉 NavController 自己那条回调，再注册本回调（最后一条 ⇒ 由它接管），
             * 系统不再驱动预测性进度，`popBackStack()` 走完整的 popExitTransition。
             * 打开时：移除本回调并恢复 NavController 的返回处理，预测性手势交还给 NavHost。
             */
            DisposableEffect(navPredictiveBack, context) {
                val activity = context.findComponentActivity()
                // 诊断：外观页开关、MainNavGraph 实际读值与最终生效分支必须能对照。
                com.ufi_axis_core.util.UiLogBridge.w(
                    "MainNavGraph",
                    "effective navPredictiveBack=$navPredictiveBack activity=${activity != null}"
                )
                if (navPredictiveBack || activity == null) {
                    navController.enableOnBackPressed(true)
                    onDispose { }
                } else {
                    navController.enableOnBackPressed(false)
                    val callback = object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() {
                            if (!navController.popBackStack()) activity.finish()
                        }
                    }
                    // 注册在 NavHost 的 PredictiveBackHandler 之后 ⇒ 位于 dispatcher 末尾；
                    // started / progressed / pressed 都会先落到这条非预测性回调上。
                    //
                    // ⚠ 必须**带 lifecycleOwner** 注册。NavHost 的 PredictiveBackHandler 用的是
                    // `addCallback(lifecycleOwner, cb)`：ON_STOP 会把那条回调移出队列，ON_START
                    // 再追加回来。若我们这里用无 lifecycle 的 `addCallback(cb)`，回桌面时我们的
                    // 回调留在队列里、预测回调被移除，回前台后预测回调追加到队尾 ⇒ 又变成
                    // "后注册者优先"的那一条，跟手预览复活。
                    // 两边都挂同一个 lifecycle 后，ON_START 按 observer 注册顺序重放：预测回调
                    // 先入队、我们的后入队，顺序才稳定为"我们在最后"。
                    // （lifecycleOwner 直接传 activity：ComponentActivity 即 LifecycleOwner。）
                    activity.onBackPressedDispatcher.addCallback(activity, callback)
                    onDispose {
                        callback.remove()
                        navController.enableOnBackPressed(true)
                    }
                }
            }


            /*
             * ★★ 转场进行中再次按返回会触发库缺陷，必须在此处截获。
             *
             * navigation-compose 2.8.0 的 `NavHost.kt:518` 在 `PredictiveBackHandler` 内直接取
             * `currentBackStack[currentBackStack.size - 2]`，无下标保护（其调用形式为
             * `PredictiveBackHandler(currentBackStack.size > 1) { … }`）。当返回栈已缩到 1 条、
             * 而 `enabled` 尚未改写为 false 时再次返回，下标为 -1，
             * 抛 `IndexOutOfBoundsException` 并闪退。这是库的缺陷，不是本项目的逻辑问题。
             *
             * 处理方式：转场/连击窗口内（[UiFrameGate] 举起，或 500ms 内刚发生过导航）把返回
             * **截下来**，落定后再补一次 `popBackStack()`。只记**一次**待办：转场途中连按
             * 十下也只多退一级 —— 这是有意的取舍，宁可少退一级，也不把返回栈退穿。
             * 窗口之外本回调**不启用**，预测性返回的手势预览照旧。
             *
             * 注册位置必须在本文件所有 `addCallback` 之**后**：dispatcher 按「最后注册且启用」
             * 派发（见上方可预测性返回接管的说明）。
             */
            DisposableEffect(context) {
                val activity = context.findComponentActivity()
                if (activity == null) {
                    onDispose { }
                } else {
                    val callback = object : OnBackPressedCallback(false) {
                        override fun handleOnBackPressed() {
                            // 只排一次：转场途中连按十下也只补一级，避免把栈退穿。
                            // （用户想退两级时，第二下需要等动画结束再按 —— 手感代价换稳定性。）
                            backBurstGuard.pendingBack = true
                        }
                    }
                    activity.onBackPressedDispatcher.addCallback(activity, callback)
                    backBurstGuard.callback = callback
                    onDispose {
                        callback.remove()
                        backBurstGuard.callback = null
                    }
                }
            }
            // 守卫的开关判据：**闸门举起** 或 **最近 500ms 内刚发生过一次导航**。
            //
            // 后者不能省：库里的崩溃窗口就在"pop 已经发生、`enabled` 还没被重组改写"之间，
            // 那几毫秒里闸门往往还没举起（转场要等下一帧组合完才开始）。目的地变化回调是
            // `popBackStack()` **同步**触发的，所以按它起算的 500ms 能稳稳盖住这个窗口。
            //
            // 空闲态 60ms 巡检一次（每次只读三个字段），活跃态 16ms 一帧。
            LaunchedEffect(navController, backBurstGuard) {
                while (true) {
                    val now = SystemClock.uptimeMillis()
                    val busy = com.ufi_axis_core.util.UiFrameGate.isBusy
                    val remain = backBurstGuard.armedUntilMs - now
                    val on = busy || remain > 0L
                    backBurstGuard.callback?.isEnabled = on
                    if (!on) {
                        if (backBurstGuard.pendingBack) {
                            backBurstGuard.pendingBack = false
                            navController.popBackStack()
                        }
                        delay(60L)
                    } else {
                        delay(16L)
                    }
                }
            }




            // 底栏 overlay 浮层（不占 innerPadding，避免遮挡页面内容）
            //
            // 底部留白不由这里换算：底栏活在自己的 Dialog 窗口里，窗口几何全部由
            // CapsuleBlurHost 写进 WindowManager.LayoutParams。
            // 当前几何为「MATCH_PARENT 宽 + gravity=BOTTOM + y=0」。
            //
            // 注意：这里**不**注入任何「胶囊模糊」开关。窗口级真模糊已移除
            // （连同其「每帧缩放窗口矩形」的配套方案 —— 那是动画卡顿与胶囊自激塌缩的根因），
            // 胶囊的玻璃观感由 UfiCapsuleTabBar 的 frosted 渐变背景层恒定承担，
            // 因此驱动它的用户开关与 CompositionLocal 一并废弃，避免留下无效的死开关。
            // 底栏 hide/show 是手动画（graphicsLayer + Animatable）：
            // - show：p=0（translationY = 栏总高，整条藏在屏幕底边之下）→ p=1（translationY=0），
            //   tween Sweeping(320) 慢-快-慢；
            // - hide：反向整条向下滑出，tween 360ms 慢-快-慢。
            // 不用 AnimatedVisibility 的 slideOutVertically：那会在 Dialog 内按**窗口**尺寸算位移，
            // 而我们要的是按栏自身总高算。
            //
            // ★ 进出场为**纵向滑动**，不使用 `scaleX/scaleY = p` +
            //   `transformOrigin = TransformOrigin(0.5f, 1f)`：
            //   缩放是为**悬浮胶囊**设计的（一块四周都有空隙的浮块，缩放才读得出「浮出/收回」）。
            //   贴底通栏是一条横贯整屏、下边与屏幕底边重合的带子 —— 缩放会把它缩成屏幕正下方
            //   一小块，两侧露出页面。整条上下滑进滑出才是底栏的语义。
            val showCapsule = remember { mutableStateOf(showBottomBar) }
            val enterProgress = remember { Animatable(if (showBottomBar) 1f else 0f) }
            // 滑动距离 = **栏总高** = 内容区 58dp + 底部安全区。
            //
            // ★ 不得沿用 `safeDrawing.getBottom(density) + 38.dp`：该 38dp 来自胶囊的
            //   「抬高 30dp + 安全间距 8dp」，贴底之后这一项必须归 0，
            //   否则栏会从屏幕外 38dp 处开始滑动。
            //   剩下的两项换成栏自己的总高：safeDrawing 的 bottom 就是安全区那一段，
            //   再加内容区高度 [DOCK_CONTENT_MIN_HEIGHT]（**复用通栏组件的常量，不另写 58**）。
            val density = LocalDensity.current
            val dockTotalHeightPx = WindowInsets.safeDrawing.getBottom(density) +
                with(density) { DOCK_CONTENT_MIN_HEIGHT.toPx() }
            LaunchedEffect(showBottomBar) {
                // ★★ **挂载不得延后** ★★
                //
                // 若在此插入 `delay(navTransitionMs + 48)` 再 `showCapsule = true`：
                // 等待期内胶囊窗口不存在，而 `mainTabIndex` / Pager 仍可被改动
                // （重定向壳、深链接、页面内 `LocalUfiMainTabController`、横滑手势），
                // 胶囊在等待结束后才**首次组合** —— 它内部所有 `remember` 初值
                // （`lastIndex` / `dragPos` / `indicatorPos` / `selectionFactors`）
                // 取的都是那一刻的组合值，而它的归位驱动又以 Pager 的连续进度为权威，
                // 于是「胶囊亮 a、页面在 b」的分叉窗口被拉长一个转场时长。
                // 正确性优先：胶囊与宿主同一帧出现，两者读同一个 `mainTabIndex`。
                if (showBottomBar) showCapsule.value = true
                enterProgress.animateTo(
                    if (showBottomBar) 1f else 0f,
                    // 两处原为裸 `durationMillis = 420 / 360`。
                    // - 浮出 420 → UfiMotion.Duration.Emphatic（值不变），与
                    //   SpeedTestScreen.PHASE_SWITCH_MS 共用同一档。
                    // - 收起 360 走本文件的 [CAPSULE_HIDE_MS]（值不变，有意例外，理由见其注释）。
                    //
                    // ★★ 浮出**延后一个转场时长** ★★
                    // 胶囊活在 Dialog 里，那是一个**盖在 Activity 窗口之上**的独立窗口。
                    // 返回时 `currentBackStackEntryAsState()` 在转场**开始**的那一帧就变成 MAIN，
                    // 胶囊若立刻浮出，而正在右滑退场的二级页仍铺满整屏，
                    // 观感就是胶囊从二级页上面冒出来。
                    // 不能靠延后挂载解决（见上方「挂载不得延后」）：挂载仍在同一帧，
                    // 只把**可见性**推迟到页面落位。delay 走 ufiSharedAxisDurationMs，
                    // 与页面平移读同一个函数，两边不会错开；关闭动效时它返回 0，行为不变。
                    if (showBottomBar) tween(
                        // delay 取转场的 55%、浮出用 Sweeping(320)：页面滑过一半多即起浮，
                        // 视觉上与页面一起落位，但仍晚于"二级页还铺满整屏"的那段。
                        // （delay = 整段转场 456ms + 浮出 420ms ≈ 0.9s 会导致到位明显偏晚。）
                        durationMillis = UfiMotion.Duration.Sweeping,
                        delayMillis = ufiSharedAxisDurationMs(navTransitionMs) * 55 / 100,
                        easing = EaseInOutCubic,
                    )
                    else tween(
                        durationMillis = CAPSULE_HIDE_MS,               // 消失：慢-快-慢，放慢
                        easing = EaseInOutCubic,
                    )
                )
                // ★★ 挂载后**不再卸载** ★★
                // 仅延后浮出动画不足以消除闪烁 —— 闪的是**窗口本身**：
                // 胶囊活在独立 Dialog Window 里，转场中途 addView/removeView 一个窗口会带来
                // 一帧与 Activity 窗口不同步的合成（窗口测量、背景、系统栏对比层都在那一帧生效）。
                // 窗口自第一次进入 MAIN 起常驻，导航过程中不再有窗口增删：
                // 隐藏只把 p 动到 0（整条滑出屏幕底边 + alpha 归零、不绘制像素），窗口留着。
                // 代价：detail 页上多一个通栏宽的透明窗 —— 它带
                // FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCH_MODAL，且隐藏期由 CapsuleTouchGate 补
                // FLAG_NOT_TOUCHABLE（通栏后窗口更宽，这条 gate 因此更为关键）。
            }

            // 此处**不**由主窗口补画手势区带子。
            //
            // 底栏的 floating Dialog 窗口在 Android 15/16 上并不会被 DecorView 吃掉系统 inset，
            // 它本身已铺到屏幕底边。若窗口缺少 `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS`，透明
            // navigationBarColor 会失效，系统补一条不透明导航栏底色
            // （见 `UfiCapsuleBlurHost.CAPSULE_WINDOW_FLAGS_ON`）—— 那才是需要修复的成因。
            // 主窗口补画的带子与栏自己的安全区 Spacer 同色同高同位置，属于重复绘制。



            if (showCapsule.value) {
                // 用 Dialog（而非 Popup）承载底栏：Dialog 才有 Window 对象，才能在窗口层
                // 统一施加触摸穿透 flag、底部锚定与透明背景 —— 这是「常驻底部浮层 + 栏以上
                // 点击穿透」的实现基础。（整屏模糊的 FLAG_BLUR_BEHIND 对本窗口**永久禁用**；
                // 窗口内背景模糊也已移除，见 CapsuleBlurHost。）
                //
                // usePlatformDefaultWidth = false ⇒ 解除平台默认宽度约束，宽度由我们自己
                // 在 LayoutParams 里决定，当前为 **MATCH_PARENT**（通栏）。
                // 不能写成 true —— 那会交回平台决定宽度，失去对窗口矩形的控制。
                //
                // decorFitsSystemWindows = false ⇒ 窗口**不**被系统栏安全区约束。
                // 写成 true 时窗口被夹在安全区内，系统会把手势小白条那条带子当成本窗口的
                // 装饰区填成白色遮罩，同时把栏整体顶高 —— 实际表现为「底栏在屏幕下边框
                // 被截断 + 底部一条白边」。改为 false 后（配合 FLAG_LAYOUT_NO_LIMITS +
                // 透明 navigationBarColor），窗口能一路铺到屏幕真实底边，正是贴底通栏的前提。
                //
                // 触摸穿透红线（FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL）、窗口宽高、
                // 底部 gravity 与 y=0，全部由 CapsuleBlurHost 在窗口层统一施加。
                // 底栏常驻不可关闭，故两个 dismiss 开关都关掉。
                Dialog(
                    onDismissRequest = { /* 常驻浮层，不可关闭 */ },
                    properties = DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false
                    )
                ) {
                    // 胶囊活在**独立 Window** 里：Compose 为 Dialog 新建 AbstractComposeView 时，
                    // 会在子组合根部重新 `LocalDensity provides owner.density`，把 UFIAXISTheme
                    // 覆盖的全局缩放 density 冲掉 —— 现象就是"全局都缩了，只有胶囊没缩"。
                    // 自定义 local（LocalResolvedPalette / LocalUfiUiScale）不在平台那份重置列表里，
                    // 所以颜色能继承、density 不能，必须在这里显式补一次。
                    // 必须包在 CapsuleBlurHost **外面**：它自己也读 LocalDensity 做 dp↔px 换算
                    // （安全区 reservedPx → dp、窗口圆角），一并跟随缩放才不会与栏本体错位。
                    UfiInheritUiScale {
                    CapsuleBlurHost {
                        // 以 lambda 形式下发进度。
                        // 由于 provides { ... } 本身不读取 floatValue，因此滑动时本行与子树均零重组。
                        CompositionLocalProvider(
                            LocalUfiCapsuleSelectionProgress provides { capsuleSelectionProgressState.floatValue }
                        ) {
                            Box(
                                modifier = Modifier.graphicsLayer {
                                    // ★ `enterProgress.value` 必须在**这个 lambda 里**读。
                                    // 在 Scaffold content 顶层 `val p = enterProgress.value` 属于组合期读取：
                                    // 胶囊每动画帧都会让整个 Scaffold content（含 NavHost 调用点）失效一次，
                                    // 与二级页转场时间重叠时会造成掉帧/抖动。graphicsLayer 的 block
                                    // 在图层阶段执行，在这里读只订阅这一层，零重组。
                                    val p = enterProgress.value
                                    // 渐入渐出：叠在纵向位移上。留着它是**可见性保险** ——
                                    // 只靠 translationY 时，隐藏态是否真的一个像素都不画取决于
                                    // 窗口裁剪；alpha=0 让「二级页上完全不可见」成为无条件事实。
                                    alpha = p
                                    // ★ 纵向滑动：p→0 时整条下移一个栏总高、滑到屏幕底边
                                    //   之下；p→1 时从底边之下滑回原位。两者互为镜像。
                                    // ★ 不使用 `scaleX/scaleY = p` 与
                                    //   `transformOrigin = TransformOrigin(0.5f, 1f)`：
                                    //   「从底部中心缩放」是悬浮胶囊的进出场语义，贴底通栏用它会
                                    //   缩成屏幕正下方一小块、两侧漏出页面（见上方 val 处注释）。
                                    translationY = (1f - p) * dockTotalHeightPx
                                }
                            ) {
                                AppBottomNavigation(
                                    selectedIndex = mainTabIndex,
                                    onTabSelected = { mainTabIndex = it }
                                )
                            }
                        }
                    } // CapsuleBlurHost
                    } // UfiInheritUiScale
                }
            }
        }
        } // CompositionLocalProvider
    } // Scaffold innerPadding
} // MainNavGraph

/**
 * 沿 ContextWrapper 链找宿主 Activity。
 *
 * `LocalContext.current` 在主题包装 / Compose 的某些子组合里可能是 `ContextThemeWrapper`，
 * 直接 `as? ComponentActivity` 会得到 null —— 那会让"关闭可预测性返回"静默退化成不可用
 * （旧实现就是直接强转）。这里与 `UfiToastOverlay.findActivity` 同一种链式查找。
 */
private fun Context.findComponentActivity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 「转场进行中重复按返回」的守卫状态。
 *
 * 只被 [MainNavGraph] 里那两条 effect 读写，且只在主线程：一条在按下时置
 * [pendingBack]，另一条在 [com.ufi_axis_core.util.UiFrameGate] 放下时消费。
 * 不用 `MutableState`：没有任何 Composable 需要订阅它，走快照只会让宿主多一档失效。
 */
private class BackBurstGuard {
    /** 只在闸门举起 / 上弦窗口内启用；其余时间让位给 NavHost 的预测性返回。 */
    var callback: OnBackPressedCallback? = null

    /** 被截下来的那一次返回，落定后补发。 */
    var pendingBack: Boolean = false

    /** 守卫保持启用的截止时刻（[SystemClock.uptimeMillis] 口径）；0 = 未上弦。 */
    var armedUntilMs: Long = 0L

    /** 每次导航发生都重新上弦，把 500ms 的窗口从这一刻起算。 */
    fun arm(nowMs: Long = SystemClock.uptimeMillis()) {
        armedUntilMs = nowMs + BACK_BURST_WINDOW_MS
    }
}

/**
 * 返回连击守卫的窗口长度。
 *
 * 只要盖住「一次 pop 已发生 → 库的重组改写 `enabled`」这段间隙即可；500ms 同时也覆盖了
 * 二级页转场（456ms）+ 闸门尾巴（150ms 由 [UiFrameGate] 计时）的大部分，
 * 让"动画途中再按一次"也落进被截获的一侧。宁长勿短：多吞一次返回只是手感略钝，
 * 少吞一次就是闪退。
 */
private const val BACK_BURST_WINDOW_MS: Long = 500L
