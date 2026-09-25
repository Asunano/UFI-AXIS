package com.ufi_axis.ui.navigation

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavHostController
import com.ufi_axis.ui.animation.blurEntrance
import com.ufi_axis.ui.animation.page.UfiPage
import com.ufi_axis.ui.animation.page.UfiPageSwitcher
import com.ufi_axis.ui.animation.page.UfiPageSwitcherDefaults
import com.ufi_axis.ui.animation.page.LocalUfiBlurEnabled
import com.ufi_axis.ui.animation.page.UfiPageTransitions
import com.ufi_axis.ui.animation.page.LocalUfiReduceMotion
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

// ★ 已删除（2026-09-24，贴底通栏低栏迁移阶段 2.2）：
//   `import CAPSULE_BOTTOM_MARGIN`（胶囊距预留区顶边的 8dp 安全间距，历史别名）与
//   `import CAPSULE_SHADOW_ROOM`（胶囊四周投影留白，恒 0.dp）。
//
// 这两个常量原本是「窗口层（gravity + y 偏移）与内容层（本文件的 padding）必须用同一组
// 数值」的单一真源。通栏形态下窗口是 MATCH_PARENT 宽 + gravity=BOTTOM + y=0：
// 没有抬升、没有四周投影、也没有「离热区多远」这个问题（热区那条带子就是栏自己的一部分），
// 两个概念一起退休，源头的声明已在 `UfiCapsuleBlurHost.kt` 删除并留下完整说明。
// 见 `docs/bottom-dock-migration-plan.md` §2.1 / §2.5。

/**
 * 胶囊导航栏**收起**时长（毫秒）。★ 全库唯一一处刻意不在 [UfiMotion.Duration] 梯度上的时长。
 *
 * 2026-09-04（P2b）：原为调用点裸字面量 `360`。为什么不收编进梯度：
 * - 它与浮出时长（[UfiMotion.Duration.Emphatic] 420）是一对**刻意不对称**的参数 ——
 *   进场慢、退场快 60ms，这样底部浮层"来得从容、走得干净"；
 * - 360 与最近的两个档位（Sweeping 320 / Deliberate 400）都差 40ms，超出「≤20ms 才允许吸附」
 *   的容差，硬吸过去会改变这对不对称关系的手感；
 * - 只服务胶囊收起这一处，没有第二个调用点，收进全局梯度反而会诱导别人误用。
 *
 * 结论：保留原值，但在本文件固化为具名常量（唯一来源），不再散落为魔法数字。
 * 调它请连带评估 [UfiMotion.Duration.Emphatic]，两者是一对。
 */
private const val CAPSULE_HIDE_MS = 360

/**
 * 底部栏形态开关：`true` = 贴底通栏低栏 [UfiBottomDock]，`false` = 悬浮胶囊 [UfiCapsuleTabBar]。
 *
 * ## 为什么要有这个开关
 * 两种形态的几何假设是**互斥**的（见 `docs/bottom-dock-migration-plan.md`）：
 * 胶囊是 wrap-content 窗口 + `LayoutParams.y` 抬起 + 四周一圈投影留白；
 * 通栏是 MATCH_PARENT 宽 + `gravity=BOTTOM` + `y=0` + 安全区靠栏内 Spacer 让位。
 * 原地改写就没有退路了；并存一段才能装包逐项对比、真机出问题时一行回退。
 *
 * ## ⚠ `false` 分支已不是回退位
 * 窗口层（MATCH_PARENT / `gravity=BOTTOM` / `y=0`）、页面 inset（实测总高与「58 + 安全区」
 * 取较大值）、进出场（纵向滑动）都已切到通栏口径。此时翻回 `false` 会得到
 * **贴死屏幕底边、不再悬浮的胶囊**，且页面底部留白按通栏算 —— 是个错位的中间态。
 * 真要回退请整批 revert 阶段 2，而不是只翻这个开关。
 * 它留到阶段 3 只为「旧实现还在、可对照读代码」，届时连开关一起删。
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
 * - 2026-09-24 之前（悬浮胶囊）：`LayoutParams.y` = 系统预留区 + 安全间距 8dp + 抬高 30dp；
 * - 现在（贴底通栏）：`y = 0`，栏自己铺到屏幕真实底边，安全区由**栏内 Spacer** 让位。
 * 两种形态下在这里再留一次白都会造成双重偏移。
 *
 * 另注意：通栏形态下窗口已是 `MATCH_PARENT` 宽，所以本层**不再需要**「不许 fillMaxWidth」
 * 那条旧约束（它当年的理由是：wrap-content 窗口一撑满宽度就会把整条底部触摸圈进窗口矩形，
 * 而 `FLAG_NOT_TOUCH_MODAL` 只放行窗口**之外**的触摸）。通栏就是要占满那条带子；
 * 二级页整块穿透改由 [CapsuleTouchGate] 的 `FLAG_NOT_TOUCHABLE` 保证（计划 §5.4）。
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

    // ★ 已删除（2026-09-24，迁移阶段 2.2）：这里原来套着 `Modifier.padding(CAPSULE_SHADOW_ROOM)`。
    //   那圈留白是给胶囊 `shadow(10.dp)` 画到 wrap-content 窗口外留的余量（取值已长期为 0.dp，
    //   只剩「与窗口层共用同一个数」这一个语义）。通栏形态没有四周投影
    //   （只有顶边发丝线 + 上缘高光，都画在自己边界之内），而窗口也已是 MATCH_PARENT ——
    //   留着它会在栏左右各留一道空隙，通栏就不通了。常量本体已在 `UfiCapsuleBlurHost` 删除。
    Box(
        contentAlignment = Alignment.Center
    ) {
        // ★ 胶囊的淡色底 **归属 Compose 内容层**：它画在 UfiCapsuleTabBar 内部、
        //   根 Box 的 graphicsLayer 之内的那层 Box 上（clip + background），
        //   因此随收起/展开一起缩放。**它不在窗口 drawable 上**。
        // ★ 后人请勿"顺手"把这层底色挪回 Window.setBackgroundDrawable 或主题
        //   windowBackground：窗口背景必须恒为 null（红线 R1，配合 setFormat(TRANSLUCENT)），
        //   一旦挂上 drawable 会复活「胶囊外圈 1px 细线」与「浮窗退回不透明黑底」两个真机 bug。
        //   回归护栏：CapsuleRegressionGuardTest.capsuleBackground_mustBeContentLayer。
        UfiCapsuleTabBar(
            tabs = tabs,
            selectedIndex = selectedIndex,
            onTabSelected = onTabSelected
        )
    }
}

/**
 * 主导航图 — 现代圆润设计系统 + 常驻底部 5 Tab 导航（方案 A′）。
 *
 * ## 与迁移前的结构差异
 * 迁移前：5 个 Tab 是 5 个独立的 NavHost 目的地，切换走 `tab*` 转场。
 * 迁移后：5 个 Tab 收敛为 [Routes.MAIN] 单一目的地内 `UfiPageSwitcher` 的 5 页，
 * 页间动画由可插拔的 [UfiPageTransitions] 策略驱动（支持横滑手势、可打断、可反向）。
 *
 * 其余 detail 页仍留在 NavHost，转场行为与迁移前完全一致。
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
// 只在本函数开口，不给整个文件加 @file:OptIn —— 免得以后别处误用实验 API 也被静默放行。
@OptIn(ExperimentalSharedTransitionApi::class)
fun MainNavGraph(
    screens: Map<String, AppScreen>,
    themeManager: ThemeManager,
    navController: NavHostController,
    pendingSmsPhone: MutableState<String?> = mutableStateOf(null),
    pendingAlertDeepLink: MutableState<String?> = mutableStateOf(null),
    suppressCapsule: Boolean = false
) {
    // 装配 T03 六种内置策略。幂等且线程安全，重复调用无副作用。
    // 必须早于任何 UfiPageSwitcher 首次组合，否则 byId() 只能查到 T01 的安全 Fade 兜底，
    // 导致用户选的 Cube3D / Flip3D 等全部静默退化成渐入渐出。
    remember { registerBuiltInTransitions() }

    val palette = LocalResolvedPalette.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 宿主内的当前 Tab。用 rememberSaveable 以便进程重建后恢复。
    var mainTabIndex by rememberSaveable { mutableIntStateOf(0) }

    // 胶囊实时追踪：Pager 连续页位置（滑动中逐帧更新，静止时=整数页）。
    // 2026-08-23 优化：改用原始 State 对象以支持 lambda 形式下发进度。
    val capsuleSelectionProgressState = remember { mutableFloatStateOf(0f) }

    // 用户设置的动画过渡时长（毫秒）
    val transitionDurationMs by themeManager.transitionDurationMs.collectAsState()

    // 用户在「外观」页选择的切换动画策略 id（如 "fade" / "slide" / "cube3d" / "flip3d"），
    // 经 UfiPageTransitions 注册表解析为具体策略；未知 id 安全回退到 Fade。
    val pageTransitionId by themeManager.pageTransition.collectAsState()

    // ── 二级页转场：唯一的时长/开关来源 ──────────────────────────────────────
    //
    // 2026-09-04：`transitionDurationMs`（用户设置）与系统「降低动效」
    // 在这里**合并成一个数**，全部 detail/host 转场与深度图层都只认它：
    //   > 0  → 合法 tween 时长（跟随用户的 150..600）
    //   == 0 → 不播转场（平移 + 下层后退缩放 + scrim 一并不生效）
    // 收敛的动机：以前二级页平移写死 320ms（Navigation.kt 的 UfiMotion.Duration.Sweeping），
    // 只有 Tab 切页读用户设置 —— 同一个「转场时长」滑块只管半个 App。
    // 归一化规则（含 -1 未播种哨兵）见 [ufiNavTransitionDurationMs] 的 KDoc。
    val systemReduceMotion = LocalUfiReduceMotion.current
    val navTransitionMs = ufiNavTransitionDurationMs(transitionDurationMs, systemReduceMotion)

    // Shared Axis 深度图层的「谁是下层」角色持有者。push/pop 在 exit / popEnter
    // 里登记 entry id；图层侧用 `() -> Boolean` 比对，零重组。详见 UfiNavRecedeRole。
    val recedeRole = remember { UfiNavRecedeRole() }

    // ── 下层背景模糊（2026-09-20，只服务升起面板）的两道**与页面无关**的闸门 ────────
    //
    // 1. 用户在「设置 → 外观」里的「过渡模糊」开关。这个开关原来只管 Tab 切页
    //    （下发给 UfiPageSwitcher 的 LocalUfiBlurEnabled），但用户读到的是"转场要不要模糊"——
    //    新加的这条转场模糊如果不听它，那就是个只管一半的开关。所以这里把 collect 从
    //    宿主目的地内部**上提到本函数**：一处取值、两处使用（provide 给 Tab 宿主 +
    //    喂给二级页的深度图层）。代价是开关一变会重组整个 MainNavGraph，
    //    而它是"用户在设置页按一下"级别的事件，不在任何动画帧上。
    // 2. 设备扛不扛得住满屏 GPU 模糊：直接复用页面切换那套判定
    //    （UfiPageSwitcherDefaults.isBlurSupported），不另立一套"什么叫低端机"的标准。
    //    结果在进程内恒定，按 context 记一次。
    val blurEnabled by themeManager.blurEnabled.collectAsState()
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
    // [suppressCapsule]：启动加载页期间也要藏。胶囊活在**独立的 Dialog 窗口**里
    //（见下方 ~800 行的说明），窗口层级高于 Activity 主窗口，所以主窗口里那个
    // 铺满屏幕的启动浮层**盖不住它** —— 不从这里关掉，加载页上会浮着一条胶囊。
    val showBottomBar = currentRoute == Routes.MAIN && !suppressCapsule


    // ★ 2026-09-16（二级页底部控件点不动）：把「胶囊此刻该不该吃触摸」下发到窗口层。
    //
    // 胶囊窗口自 2026-09-15 起常驻（挂载后不再卸载，避免窗口增删闪帧），二级页只把内容
    // scale/alpha 动到 0 —— 但**窗口矩形没缩**，而 FLAG_NOT_TOUCH_MODAL 只放行窗口
    // 矩形之外的触摸。于是二级页里屏幕底部那条带子（≈胶囊高度 + 抬高量）上的控件全都
    // 收不到事件：音乐播放页的进度条与播放键正好整排落在里面，表现为「点不动 / 点偏才响应」。
    // 隐藏期改由 CapsuleTouchGate 给窗口补 FLAG_NOT_TOUCHABLE：窗口留着、像素不画、
    // 触摸整块下发给页面。
    LaunchedEffect(showBottomBar) {
        CapsuleTouchGate.interactive.value = showBottomBar
    }


    // SMS 通知深链接：点击短信/验证码通知后，跳转到短信对话界面。
    // pendingSmsPhone 由 MainActivity 从 Intent 中读取并写入，此处监听变化后导航。
    // 若已在 SMS 界面则跳过导航（SmsScreen 自身的 LaunchedEffect 会处理手机号变化）。
    LaunchedEffect(pendingSmsPhone.value) {
        val phone = pendingSmsPhone.value
        if (!phone.isNullOrEmpty() && currentRoute != Routes.DETAIL_SMS) {
            navController.navigate(Routes.DETAIL_SMS)
        }
    }

    // P3（应用内通知）：告警系统通知深链接。点击通知后由 MainActivity 写入 pendingAlertDeepLink，
    // 此处监听变化后跳转到事件中心（Routes.DETAIL_EVENTS）。已在事件中心则跳过。
    LaunchedEffect(pendingAlertDeepLink.value) {
        val target = pendingAlertDeepLink.value
        if (!target.isNullOrEmpty() && currentRoute != Routes.DETAIL_EVENTS) {
            navController.navigate(Routes.DETAIL_EVENTS)
            pendingAlertDeepLink.value = null
        }
    }

    Scaffold(
        containerColor = palette.pageBg,
        // ★★ 2026-09-04（转场区满屏化）：contentWindowInsets 必须显式清零 ★★
        //
        // M3 `Scaffold` 的默认值是 `ScaffoldDefaults.contentWindowInsets`（= safeDrawing，
        // 含状态栏 + 导航栏/手势条）。默认值下 `innerPadding` 的上下量非零，而 `NavHost`
        // 活在下面那个 `.padding(innerPadding)` 的 Box **里面** —— 于是：
        //   1. 转场区被 inset 内缩，两页的平移只发生在"安全区内"的那块矩形里；
        //   2. 状态栏那条带子与底部导航栏那条带子由 `Scaffold` 的 `containerColor`
        //      **静态绘制**，转场期间完全不动。
        // 真机观感就是 inset 边界上一条「动画到此为止」的横线（用户截图已确认）。
        //
        // 清零后 `innerPadding` 四边全 0，`NavHost` 区域真正满屏，
        // `MainActivity.enableEdgeToEdge()` 才算真的接上（此前开了又被本 Scaffold 抵消）。
        //
        // ⚠ 代价与配套：安全区改由**页面侧**消费，唯一落点是共用壳
        // `UfiScreenScaffold`（header 上方 `statusBarsPadding()`、内容区
        // `windowInsetsPadding(WindowInsets.navigationBars)`）。不走该壳的目的地
        // 必须自己处理（审计结论见 `UfiScaffold.kt` 的文件级说明）。
        // 回归护栏：`NavInsetHandoffGuardTest`。
        //
        // ⚠ 本 Scaffold 目前**没有** topBar / bottomBar 槽在用（胶囊导航是独立 Dialog
        // 窗口，见下方 `Dialog`）。后人若新增这两个槽，注意它们此前依赖 Scaffold 的
        // contentWindowInsets 自动避让，清零后必须自己加 `statusBarsPadding()` /
        // `navigationBarsPadding()`。
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        // 2026-08-08 19:47 方案 A 修订版：主窗口根读取胶囊共享单例并 CompositionLocal 下发。
        // 胶囊 Dialog 在独立窗口（CompositionLocal 不跨 Window），页面读不到 Dialog 内的 provide；
        // 故由 CapsuleBlurHost 写共享单例 → 此处下发给 NavHost 内所有主 Tab 页。
        //
        // ★★ 2026-09-05 P1（pop 第 2~3 帧整树重组）：这里下发的是**读值的方式**，不是值 ★★
        //
        // 原写法是 `val capsuleBottomInset = CapsuleInsetHolder.bottomInset.value` 然后
        // `LocalCapsuleBottomInset provides capsuleBottomInset`。两个事实叠在一起就出事：
        //   1. `LocalCapsuleBottomInset` 是 `staticCompositionLocalOf` —— 它**没有**细粒度
        //      读追踪，值一变，provider 以下**整棵树无条件重组**（这正是 static 变体的定义）；
        //   2. 在**组合期**解引用 `.value` 把 MainNavGraph 订阅进了那条 MutableState。
        // 于是返回动画的时间线正好被砸中：
        //   离开 MAIN → 胶囊 dispose → 写回兜底 88dp（UfiCapsuleBlurHost 约 1063）
        //   → pop → 胶囊重挂 → 首帧 naturalSize == 0 不发布（已有护栏）
        //   → 第 2~3 帧测量完成、发布真值 → 整个 NavHost 子树重组 + 所有读它的页面重排。
        // 88dp 兜底与真值差约 50dp，所以既是"卡顿"（整树重组撞在转场头几帧）也是"位置跳"。
        //
        // 现在 provide 一个**稳定的 `() -> Dp`**（remember 一次，实例永不变）⇒ static local
        // 的值永远不变 ⇒ 永远不触发那次整树重组；对 `CapsuleInsetHolder.bottomInset` 的
        // 快照读被推迟到消费方的 **layout 阶段**（见 `Modifier.ufiCapsuleBottomInset`），
        // inset 变化只失效那一个节点的测量。
        //
        // ⚠ 护栏：`NavInsetHandoffGuardTest.capsuleInset_providerMustNotDereferenceAtComposition`
        //   盯着这里不许再出现组合期 `.value`。
        val capsuleBottomInsetProvider: () -> Dp = remember { { CapsuleInsetHolder.bottomInset.value } }
        CompositionLocalProvider(
            LocalCapsuleBottomInset provides capsuleBottomInsetProvider,
            LocalPendingSmsPhone provides pendingSmsPhone
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 2026-09-04：`innerPadding` 现在**四边恒为 0**（见上方 contentWindowInsets
                    // 的说明）。仍然保留这一句而不是删掉：它是 Scaffold 契约的一部分 ——
                    // 后人若给本 Scaffold 加回 topBar / bottomBar，槽位高度只会从这里下发，
                    // 删掉就会让新加的栏直接盖住 NavHost。
                    .padding(innerPadding)
                    // 启动动画：整个导航区淡入 + 轻微上移，**整个 App 生命周期只播一次**。
                    // 以前是每个页面各自 blurEntrance("home"/"network"/…)，于是首次切到某个 Tab、
                    // 以及从二级页返回导致宿主组合重建时都会各播一次 —— 观感就是"切界面就抖一下"。
                    // 挂在根节点后，语义回归"应用启动"：只有冷启动这一次会动。
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
            NavHost(
                navController = navController,
                startDestination = Routes.MAIN,
                enterTransition = { detailSharedAxisEnter(navTransitionMs) },
                exitTransition = { detailSharedAxisExit(navTransitionMs, recedeRole) },
                popEnterTransition = { detailSharedAxisPopEnter(navTransitionMs, recedeRole) },
                popExitTransition = { detailSharedAxisPopExit(navTransitionMs) }
            ) {
            // ── Tab 宿主目的地：5 个 Tab 作为 UfiPageSwitcher 的 5 页
            composable(
                route = Routes.MAIN,
                // 宿主与二级页同一套 Shared Axis，进/回二级页时两侧同步。
                enterTransition = { detailSharedAxisEnter(navTransitionMs) },
                exitTransition = { detailSharedAxisExit(navTransitionMs, recedeRole) },
                popEnterTransition = { detailSharedAxisPopEnter(navTransitionMs, recedeRole) },
                popExitTransition = { detailSharedAxisPopExit(navTransitionMs) }
            ) { entry ->
                // 先把导航层的 AnimatedVisibilityScope 抓在手里：下面要在几层 lambda 内部
                // provide 它，而那些 lambda 里的 `this` 已经是别的 receiver 了。
                //
                // Tab 宿主目前没有任何页面用共享元素，仍然 provide：漏掉这一处的后果是
                // 「将来从某个 Tab 页挂 ufiSharedElement 时它静默失效」——读到 null 就退化成
                // 不共享，既不报错也没动画，排查成本远高于现在多写一行。
                val navAnimatedScope: AnimatedVisibilityScope = this
                // 深度层：去二级页时宿主是下层（压暗+后退）；回宿主时宿主仍是下层。
                val axisLayer = ufiSharedAxisLayer(
                    isReceding = { recedeRole.recedingEntryId == entry.id },
                    durationMillis = navTransitionMs,
                )

                // 缓存页列表，避免每次重组都重建 5 个 UfiPage 与其 content lambda。
                val tabPages = remember(screens, navController, entry) {
                    TAB_ROUTES.map { route ->
                        UfiPage(
                            key = route,
                            content = { screens[route]?.invoke(entry, navController) }
                        )
                    }
                }

                // 2026-09-04：帧闸门补到宿主目的地。
                // 它原来只套在 detail 目的地上，可返回过程中真正在做重活的是**宿主** ——
                // 宿主根节点收 dashboardState 这一个大对象，一条 WS 推送 = 一次整屏重组，
                // 而返回时宿主正在重新组合 5 个 Tab 页，两者叠在平移的同几帧里。
                UfiNavFrameGate {
                CompositionLocalProvider(
                    LocalUfiMainTabController provides { targetIndex -> mainTabIndex = targetIndex },
                    LocalUfiNavAnimatedScope provides navAnimatedScope
                ) {
                    // blurEnabled 已上提到本函数顶部（见那里的说明：同一个用户开关现在同时
                    // 管 Tab 切页与二级页的下层模糊，取值只能有一处）。

                    // P2f（2026-09-04）：转场时长滑块的下端新增「0 = 关闭转场」。
                    // 落地方式刻意**不是**去传 tween(0)：那样两页仍会走一遍 AnimatedContent /
                    // Pager 的组合与模糊管线，只是时长为 0，白付开销还可能闪一帧。改为把「关闭」
                    // 翻译成宿主已有的降低动效通道（LocalUfiReduceMotion → scrollToPage + 极短淡入），
                    // 与系统「移除动画」走**同一条**代码路径，不新增第二种"无动画"实现。
                    // 系统设置（MainActivity 探测 TRANSITION/ANIMATOR_DURATION_SCALE == 0 后注入）
                    // 与用户开关是 or 关系：任一为真就降级，两者互不覆盖。
                    //
                    // 2026-09-04（卡片浮起）：这里原来自己写了一遍
                    // `systemReduceMotion || transitionDurationMs == TRANSITION_DURATION_OFF`
                    // （含对 -1 未播种哨兵的说明）。现在这条判据已经收进
                    // [ufiNavTransitionDurationMs]，`navTransitionMs == 0` 就是它的结论 ——
                    // 二级页与 Tab 切页共用同一个开关，不会出现"关了一半"。
                    val reduceMotion = navTransitionMs == 0
                    CompositionLocalProvider(
                        LocalUfiBlurEnabled provides blurEnabled,
                        LocalUfiReduceMotion provides reduceMotion,
                    ) {
                        UfiPageSwitcher(
                            pages = tabPages,
                            selectedIndex = mainTabIndex,
                            onSelectedIndexChange = { mainTabIndex = it },
                            onSelectionProgressChange = { capsuleSelectionProgressState.floatValue = it },
                            // ★ 目的地自带不透明底色，必须放在 axisLayer **之前** —— 这样圆角
                            //   clip 会把底色一并切成圆角（否则方形底色盖在裁剪层之上，圆角消失）。
                            //   axisLayer 负责转场期圆角/scrim（进场新页圆角 20dp→0）。
                            modifier = Modifier
                                .fillMaxSize()
                                .background(palette.pageBg)
                                .then(axisLayer),

                            transition = UfiPageTransitions.byId(pageTransitionId),
                            swipeEnabled = true,
                            // 切换动画由用户在「外观」页选择（默认渐入渐出）。远程多页跳转走原生
                            // 逐页匀速扫场以保留中间页动画（修掉旧 pivot 方案丢中间页的 BUG）；
                            // 降低动效下退化为瞬移 + 极短淡入。
                            respectReducedMotion = true,
                            animationSpec = tween(
                                // 关闭档（0）时本 spec 走不到（reduceMotion 分支不读它），
                                // 但 tween 的 durationMillis 必须合法，故夹到 ≥1。
                                // 2026-09-04：改读 navTransitionMs（已归一化，见其定义）而不是
                                // 原始的 transitionDurationMs —— 后者在 prefs 播种前是 -1，
                                // `coerceAtLeast(1)` 会把那一瞬间的 Tab 切页变成 1ms 瞬变。
                                durationMillis = navTransitionMs.coerceAtLeast(1),

                                // 曲线口径（2026-09-02 二次定稿，三条路都别再走）：
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
                                // 现用 Material 标准 (0.4, 0, 0.2, 1)（FastOutSlowIn）：中段速度平稳、
                                // 首尾都短，单调无过冲，全程没有"几乎不动"的长尾 —— 要的就是连贯。
                                easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f),
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
            // ★ 2026-09-20 二次修订：判据从"对端是 RISE 路由"收紧成"对端是 RISE 路由 **且**
            //   本页自己挂着迷你条"（[isUfiMiniBarHostRoute]）。原因是播放页的进场动画现在按
            //   **来源**二选一（见下面 RISE 循环）：来源没有迷你条时它退回横向共享轴。上下两层
            //   必须用同一条判据，否则会出现"上层横着滑进来、下层却静止 + 模糊"这种半套动画。
            //   目前 DETAIL 循环里能跳到播放页的只有音乐列表页与分组页（两者都挂迷你条），
            //   所以这一条现在不改变任何实际表现；它是为"日后又有 DETAIL 页能进播放页"准备的 ——
            //   那一页若没有迷你条，会自动跟着上层一起退回普通横向转场。
            appRoutes.filter { it.transition == TransitionType.DETAIL }.forEach { appRoute ->
                composable(
                    route = appRoute.route,
                    arguments = appRoute.arguments,
                    enterTransition = { detailSharedAxisEnter(navTransitionMs) },
                    exitTransition = {
                        if (isUfiMiniBarHostRoute(appRoute.route) &&
                            isUfiRiseRoute(targetState.destination.route)
                        ) {
                            riseUnderlayExit(navTransitionMs, recedeRole)
                        } else {
                            detailSharedAxisExit(navTransitionMs, recedeRole)
                        }
                    },
                    popEnterTransition = {
                        if (isUfiMiniBarHostRoute(appRoute.route) &&
                            isUfiRiseRoute(initialState.destination.route)
                        ) {
                            riseUnderlayPopEnter(recedeRole)
                        } else {
                            detailSharedAxisPopEnter(navTransitionMs, recedeRole)
                        }
                    },
                    popExitTransition = { detailSharedAxisPopExit(navTransitionMs) }
                ) { entry ->
                // 深度层：进二级页时上层圆角描边（满屏不缩放）；返回时 detail 是上层纯平移。
                //
                // blurUnderlay：本页作为下层时**允许**背景模糊，但只在"对端是升起面板"那种
                // 处境才真的模糊 —— 那条判定由 underlayBlurActive 在绘制期问 recedeRole，
                // 与上面 exitTransition / popEnterTransition 里的 isUfiRiseRoute 分支同源
                // （那两条分支就是写入方）。普通详情页当垫底时一如既往只压暗。
                val axisLayer = ufiSharedAxisLayer(
                    isReceding = { recedeRole.recedingEntryId == entry.id },
                    durationMillis = navTransitionMs,
                    blurUnderlay = underlayBlurAllowed,
                    underlayBlurActive = underlayBlurActive,
                )

                // ★ 每个 detail 目的地**自己画不透明底色**（`.background(palette.pageBg)`，
                //   且必须放在 axisLayer **之前**，否则圆角 clip 裁不到底色、圆角消失），
                //   不能只靠外层 Scaffold 的 containerColor。根因（2026-08-30 修）：targetSdk 36
                //   上系统「可预测性手势返回」默认开启，navigation-compose 在手势进行中会同时
                //   组合「即将退出的 detail 页」和「下面那一层」，并把 detail 页整体丢进
                //   graphicsLayer 做位移/圆角/scrim。该图层只包含本页**自己绘制的像素** ——
                //   页面没有底色时，卡片与图标之间全是透明区，手势中就直接看到下层内容。
                // ★ UfiNavFrameGate：转场/手势期间压住 WS 整屏重组（性能用，不是动画）。
                // ★ LocalUfiNavAnimatedScope：把**导航层**的 AnimatedVisibilityScope 下发给页面。
                //   必须包住 `screens[...].invoke`，共享元素（Modifier.ufiSharedElement）才找得到
                //   它要跨的那次转场；页面内部自己的 AnimatedContent 提供的同类型 scope 不能用，
                //   那是页内动画、对端不在另一个目的地上。
                CompositionLocalProvider(LocalUfiNavAnimatedScope provides this) {
                UfiNavFrameGate {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.pageBg)
                        .then(axisLayer)
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
            // ★ 2026-09-20 二次修订：升起动画**按来源二选一**，不是无条件。
            //   这套动画是"底部迷你控制条长成整页"，起点是那条控制条的上沿；只有从挂着迷你条的
            //   页面（[isUfiMiniBarHostRoute]：音乐列表页 / 音乐分组页）进来时那条线才在屏上。
            //   从别处（仪表盘等 Tab 页点标题栏的「正在播放」挂件）进来时没有起点，升起会读成
            //   "页面凭空从屏幕外飞进来"，那条路径也压根没挂共享元素 —— 所以退回原来的横向
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
                            detailSharedAxisEnter(navTransitionMs)
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
                            detailSharedAxisPopExit(navTransitionMs)
                        }
                    }
                ) { entry ->
                val axisLayer = ufiSharedAxisLayer(
                    isReceding = { recedeRole.recedingEntryId == entry.id },
                    durationMillis = navTransitionMs,
                    // 圆角：升起语义下不要（贴底长出来的一整块，加圆角会读成浮在屏上的卡片），
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
                    // 常量、决定要不要给这个目的地备那张离屏图层，而"这一次该不该真模糊"
                    // 由绘制期的 underlayBlurActive 回答。两者的时序差正是这样拆开的。
                    // 至于"从 Tab 页横着进来那次下层不该模糊"—— 那条已经天然成立：
                    // 下层是 MAIN 宿主，它登记的 detailSharedAxisExit 会把 recedingBlur 复位成
                    // false，于是 underlayBlurActive 在那一次返回 false。这里不需要额外做什么。
                    blurUnderlay = underlayBlurAllowed,
                    underlayBlurActive = underlayBlurActive,
                )

                CompositionLocalProvider(LocalUfiNavAnimatedScope provides this) {
                UfiNavFrameGate {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.pageBg)
                        .then(axisLayer)
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





            // 底栏 overlay 浮层（不占 innerPadding，避免遮挡页面内容）
            //
            // 底部留白不由这里换算：底栏活在自己的 Dialog 窗口里，窗口几何全部由
            // CapsuleBlurHost 写进 WindowManager.LayoutParams。
            // 2026-09-24 起那份几何是「MATCH_PARENT 宽 + gravity=BOTTOM + y=0」；
            // 在此之前是「wrap-content + y = 导航栏高度 + CAPSULE_BOTTOM_MARGIN − CAPSULE_SHADOW_ROOM」，
            // 两个常量已随抬升语义一起删除（见文件顶部那段说明）。
            //
            // 注意：这里**不再**注入任何「胶囊模糊」开关。窗口级真模糊已被彻底移除
            // （连同其「每帧缩放窗口矩形」的配套方案 —— 那是动画卡顿与胶囊自激塌缩的根因），
            // 胶囊的玻璃观感改由 UfiCapsuleTabBar 的 frosted 渐变背景层恒定承担，
            // 因此驱动它的用户开关与 CompositionLocal 一并废弃，避免留下无效的死开关。
            // 底栏 hide/show 是手动画（graphicsLayer + Animatable）：
            // - show：p=0（translationY = 栏总高，整条藏在屏幕底边之下）→ p=1（translationY=0），
            //   tween Sweeping(320) 慢-快-慢；
            // - hide：反向整条向下滑出，tween 360ms 慢-快-慢。
            // 不用 AnimatedVisibility 的 slideOutVertically：那会在 Dialog 内按**窗口**尺寸算位移，
            // 而我们要的是按栏自身总高算。
            //
            // ★ 2026-09-24（迁移阶段 2.2，计划 §5.5）：进出场由「从底部中心缩放」改成**纵向滑动**。
            //   旧实现是 `scaleX/scaleY = p` + `transformOrigin = TransformOrigin(0.5f, 1f)`，
            //   那是为**悬浮胶囊**设计的：一块四周都有空隙的浮块，缩放才读得出「浮出/收回」。
            //   贴底通栏是一条横贯整屏、下边与屏幕底边重合的带子 —— 缩放会把它缩成屏幕正下方
            //   一小块，两侧露出页面，观感完全不对。整条上下滑进滑出才是底栏的语言。
            val showCapsule = remember { mutableStateOf(showBottomBar) }
            val enterProgress = remember { Animatable(if (showBottomBar) 1f else 0f) }
            // 滑动距离 = **栏总高** = 内容区 58dp + 底部安全区（§1 参数表）。
            //
            // ★ 2026-09-24：原式是 `safeDrawing.getBottom(density) + 38.dp`，那个 38
            //   照抄的是胶囊的「抬高 30dp + 安全间距 8dp」—— 贴底之后这一项必须归 0，
            //   否则栏会从屏幕外 38dp 处开始动（计划 §2.5 / §7-2.2）。
            //   剩下的两项换成栏自己的总高：safeDrawing 的 bottom 就是安全区那一段，
            //   再加内容区高度 [DOCK_CONTENT_MIN_HEIGHT]（**复用通栏组件的常量，不另写 58**）。
            val density = LocalDensity.current
            val dockTotalHeightPx = WindowInsets.safeDrawing.getBottom(density) +
                with(density) { DOCK_CONTENT_MIN_HEIGHT.toPx() }
            LaunchedEffect(showBottomBar) {
                // ★★ 2026-09-05（胶囊与实际页面错位）：**挂载不得延后** ★★
                //
                // 上一轮为了压掉「返回时底部阴影闪一下」，在这里插过一条
                // `delay(navTransitionMs + 48)` 再 `showCapsule = true`。回退理由：
                // 那段等待期里胶囊窗口不存在，而 `mainTabIndex` / Pager 仍可被改动
                // （重定向壳、深链接、页面内 `LocalUfiMainTabController`、横滑手势），
                // 胶囊是在等待结束后才**首次组合**的 —— 它内部所有 `remember` 初值
                // （`lastIndex` / `dragPos` / `indicatorPos` / `selectionFactors`）
                // 取的都是那一刻的组合值，而它的归位驱动又以 Pager 的连续进度为权威，
                // 于是「胶囊亮 a、页面在 b」的分叉窗口被整整拉长一个转场时长。
                // 正确性优先：胶囊与宿主同一帧出现，两者读同一个 `mainTabIndex`。
                if (showBottomBar) showCapsule.value = true
                enterProgress.animateTo(
                    if (showBottomBar) 1f else 0f,
                    // 2026-09-04（P2b）：两处原为裸 `durationMillis = 420 / 360`。
                    // - 浮出 420 → UfiMotion.Duration.Emphatic（值不变）。收编前 420 在本文件与
                    //   SpeedTestScreen.PHASE_SWITCH_MS 各写了一遍，现在共用一个档。
                    // - 收起 360 走本文件的 [CAPSULE_HIDE_MS]（值不变，有意例外，理由见其注释）。
                    //
                    // ★★ 2026-09-15（返回二级页时"闪一下"）：浮出**延后一个转场时长** ★★
                    // 胶囊活在 Dialog 里，那是一个**盖在 Activity 窗口之上**的独立窗口。
                    // 返回时 `currentBackStackEntryAsState()` 在转场**开始**的那一帧就变成 MAIN，
                    // 于是胶囊立刻开始浮出 —— 而此时正在右滑退场的二级页还铺满整屏，
                    // 用户看到的就是"胶囊从二级页上面冒出来一下"。
                    // 不能靠延后挂载来解（上面那段注释记着上一轮为什么回退）：挂载仍在同一帧，
                    // 只把**可见性**推迟到页面落位。delay 走 ufiSharedAxisDurationMs，
                    // 与页面平移读同一个函数，两边不会错开；关闭动效时它返回 0，行为不变。
                    if (showBottomBar) tween(
                        // 2026-09-15 二次调整（"弹出晚了 0.5~1s"）：
                        // 上一版是 delay = 整段转场(456ms) + 浮出 420ms ≈ 0.9s 才到位。
                        // 现在 delay 降到转场的 55%、浮出换成 Sweeping(320)：
                        // 页面滑过一半多就起浮，视觉上与页面一起落位，但仍晚于
                        // "二级页还铺满整屏"的那段，不会从旧页上面冒出来。
                        durationMillis = UfiMotion.Duration.Sweeping,
                        delayMillis = ufiSharedAxisDurationMs(navTransitionMs) * 55 / 100,
                        easing = EaseInOutCubic,
                    )
                    else tween(
                        durationMillis = CAPSULE_HIDE_MS,               // 消失：慢-快-慢，放慢
                        easing = EaseInOutCubic,
                    )
                )
                // ★★ 2026-09-15（返回时仍然闪一下）：挂载后**不再卸载** ★★
                // 上一轮只把浮出动画延后，闪烁依旧 —— 因为闪的不是 alpha，而是**窗口本身**：
                // 胶囊活在独立 Dialog Window 里，转场中途 addView/removeView 一个窗口会带来
                // 一帧与 Activity 窗口不同步的合成（窗口测量、背景、系统栏对比层都在那一帧生效）。
                // 现在窗口从第一次进入 MAIN 起常驻，导航过程中不再有窗口增删：
                // 隐藏只把 p 动到 0（整条滑出屏幕底边 + alpha 归零、不绘制像素），窗口留着。
                // 代价：detail 页上多一个通栏宽的透明窗 —— 它带
                // FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCH_MODAL，且隐藏期由 CapsuleTouchGate 补
                // FLAG_NOT_TOUCHABLE（通栏后窗口更宽，这条 gate 比改造前更重要，计划 §5.4）。
            }

            // ★ 2026-09-24：此处曾有一条「由主窗口补画手势区带子」的绘制，已删除。
            //
            // 当时的假设是「底栏那个 floating Dialog 窗口在 Android 15/16 上被 DecorView 吃掉
            // 系统 inset、铺不到屏幕底边」，所以让主窗口补一条同色带子。诊断版（洋红/青色双色
            // 标记）在真机上证伪了这个假设：**青色完全盖住洋红**，底栏窗口本来就铺到了底边。
            // 真因是那个窗口缺 `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS`，导致透明 navigationBarColor
            // 空转、系统补了一条不透明导航栏底色（见 `UfiCapsuleBlurHost.CAPSULE_WINDOW_FLAGS_ON`）。
            // 补画那条带子与栏自己的安全区 Spacer 同色同高同位置，是纯重复绘制，故一并移除；
            // 跨窗口传值用的 `CapsuleInsetHolder.bottomReservedDp` 也随之删除。



            if (showCapsule.value) {
                // 用 Dialog（而非 Popup）承载底栏：Dialog 才有 Window 对象，才能在窗口层
                // 统一施加触摸穿透 flag、底部锚定与透明背景 —— 这是「常驻底部浮层 + 栏以上
                // 点击穿透」的实现基础。（整屏模糊的 FLAG_BLUR_BEHIND 对本窗口**永久禁用**；
                // 窗口内背景模糊也已移除，见 CapsuleBlurHost。）
                //
                // usePlatformDefaultWidth = false ⇒ 解除平台默认宽度约束，宽度由我们自己
                // 在 LayoutParams 里说了算。2026-09-24 起那个值是 **MATCH_PARENT**（通栏）；
                // 在此之前是 wrap-content（紧贴胶囊的小窗，让胶囊左右的点击能穿透）。
                // 仍然不能写成 true —— 那会交回平台去决定宽度，我们就失去了对窗口矩形的控制。
                //
                // decorFitsSystemWindows = false ⇒ 窗口**不**被系统栏安全区约束。
                // 写成 true 时窗口被夹在安全区内，系统会把手势小白条那条带子当成本窗口的
                // 装饰区填成白色遮罩，同时把栏整体顶高 —— 真机现象就是「底栏在屏幕下边框
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
                        // 2026-08-23 性能优化：以 lambda 形式下发进度。
                        // 由于 provides { ... } 本身不读取 floatValue，因此滑动时本行与子树均零重组。
                        CompositionLocalProvider(
                            LocalUfiCapsuleSelectionProgress provides { capsuleSelectionProgressState.floatValue }
                        ) {
                            Box(
                                modifier = Modifier.graphicsLayer {
                                    // ★ 2026-09-15：`enterProgress.value` 改在**这个 lambda 里**读。
                                    // 原来是在 Scaffold content 顶层 `val p = enterProgress.value` ——
                                    // 那是组合期读取，胶囊每动画帧都会让整个 Scaffold content
                                    // （含 NavHost 调用点）失效一次；它与二级页转场时间重叠，
                                    // 是转场期掉帧/抖动的一个来源。graphicsLayer 的 block 在图层阶段执行，
                                    // 在这里读只订阅这一层，零重组。
                                    val p = enterProgress.value
                                    // 渐入渐出：叠在纵向位移上。留着它是**可见性保险** ——
                                    // 只靠 translationY 时，隐藏态是否真的一个像素都不画取决于
                                    // 窗口裁剪；alpha=0 让「二级页上完全不可见」成为无条件事实。
                                    alpha = p
                                    // ★ 纵向滑动（§5.5）：p→0 时整条下移一个栏总高、滑到屏幕底边
                                    //   之下；p→1 时从底边之下滑回原位。两者互为镜像。
                                    // ★ 已删除：`scaleX/scaleY = p` 与
                                    //   `transformOrigin = TransformOrigin(0.5f, 1f)`。
                                    //   「从底部中心缩放」是悬浮胶囊的进出场语言，贴底通栏用它会
                                    //   缩成屏幕正下方一小块、两侧漏出页面（理由见上方 val 处注释）。
                                    //   顺带说明：护栏 collapseOrigin_mustNotBeBottom 只检查
                                    //   UfiCapsuleTabBar.kt，本文件当年用 TransformOrigin(0.5f,1f)
                                    //   并不违规；现在连用都不用了。
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
