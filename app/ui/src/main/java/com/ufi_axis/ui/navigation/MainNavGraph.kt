package com.ufi_axis.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.ufi_axis.ui.animation.page.LocalUfiBlurEnabled
import com.ufi_axis.ui.animation.page.UfiPageTransitions
import com.ufi_axis.ui.animation.page.LocalUfiReduceMotion
import com.ufi_axis.ui.animation.page.registerBuiltInTransitions
import com.ufi_axis.ui.components.common.CAPSULE_BOTTOM_MARGIN
import com.ufi_axis.ui.components.common.CAPSULE_SHADOW_ROOM
import com.ufi_axis.ui.components.common.CapsuleBlurHost
import com.ufi_axis.ui.components.common.CapsuleInsetHolder
import com.ufi_axis.ui.components.common.CapsuleTabItem
import com.ufi_axis.ui.components.common.CapsuleTouchGate
import com.ufi_axis.ui.components.common.LocalCapsuleBottomInset
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

// 胶囊的两个几何常量（[CAPSULE_BOTTOM_MARGIN] 距导航栏顶边的视觉间距 / [CAPSULE_SHADOW_ROOM] 投影留白）
// 现在的**单一真源**在 `UfiCapsuleBlurHost.kt`：窗口层（gravity + y 偏移 + 模糊区 inset）
// 与内容层（本文件的 padding）必须用同一组数值，两处各写一份迟早漂移。
// 这里只 import 使用，不再本地定义。

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
 * 底部导航栏。
 *
 * 方案 A′ 迁移后改为 **selectedIndex 驱动**：5 个 Tab 已收敛进 [Routes.MAIN] 单一目的地，
 * 不再能通过 `currentRoute` 区分选中项。
 *
 * 渲染委托给悬浮胶囊组件 [UfiCapsuleTabBar]：这里只负责水平居中，
 * 选中态动画 / 滑块指示器 / 名称延时收起等全部由该组件内部自洽处理。
 *
 * 注意：**不再自带 `padding(bottom = …)`**。胶囊活在独立的 `Dialog` 窗口里，底部边距
 * （系统导航栏高度 + [CAPSULE_BOTTOM_MARGIN]）由窗口层的 `LayoutParams.y` 统一提供
 * （见 [CapsuleBlurHost]）；若两处都留白会造成双重偏移，胶囊被顶得过高。
 *
 * 另注意：这里**不再 `fillMaxWidth()`**。此 Composable 整体活在 Dialog 窗口里，且该窗口
 * 是 wrap-content（`usePlatformDefaultWidth = false` + `setLayout(WRAP_CONTENT, …)`），
 * 撑满宽度会让窗口横贯整屏 —— 既会拉出一条全宽的模糊带，也会把整条底部的触摸区域圈进
 * 窗口矩形内（`FLAG_NOT_TOUCH_MODAL` 只放行**窗口之外**的触摸），胶囊之外的点击就穿不下去了。
 *
 * 仅保留 [CAPSULE_SHADOW_ROOM] 一圈留白给投影：窗口紧贴内容，`shadow(10.dp)` 画到窗口外
 * 的部分会被裁掉。这圈留白在窗口层被 `InsetDrawable` 从模糊区里扣回去，所以模糊仍≈胶囊本体。
 */
@Composable
private fun AppBottomNavigation(
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    Box(
        modifier = Modifier.padding(CAPSULE_SHADOW_ROOM),
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
            tabs = BOTTOM_TABS.map { CapsuleTabItem(it.route, it.label, it.icon) },
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
 */
@Composable
fun MainNavGraph(
    screens: Map<String, AppScreen>,
    themeManager: ThemeManager,
    navController: NavHostController,
    pendingSmsPhone: MutableState<String?> = mutableStateOf(null),
    pendingAlertDeepLink: MutableState<String?> = mutableStateOf(null)
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

    // 只有停留在宿主目的地时才显示底部栏；detail 页仍然隐藏。




    // 只有停留在宿主目的地时才显示底部栏；detail 页仍然隐藏。
    val showBottomBar = currentRoute == Routes.MAIN

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
                    LocalUfiMainTabController provides { targetIndex -> mainTabIndex = targetIndex }
                ) {
                    val blurEnabled by themeManager.blurEnabled.collectAsState()
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
            appRoutes.filter { it.transition == TransitionType.DETAIL }.forEach { appRoute ->
                composable(
                    route = appRoute.route,
                    arguments = appRoute.arguments,
                    enterTransition = { detailSharedAxisEnter(navTransitionMs) },
                    exitTransition = { detailSharedAxisExit(navTransitionMs, recedeRole) },
                    popEnterTransition = { detailSharedAxisPopEnter(navTransitionMs, recedeRole) },
                    popExitTransition = { detailSharedAxisPopExit(navTransitionMs) }
                ) { entry ->
                // 深度层：进二级页时上层圆角描边（满屏不缩放）；返回时 detail 是上层纯平移。
                val axisLayer = ufiSharedAxisLayer(
                    isReceding = { recedeRole.recedingEntryId == entry.id },
                    durationMillis = navTransitionMs,
                )
                // ★ 每个 detail 目的地**自己画不透明底色**（`.background(palette.pageBg)`，
                //   且必须放在 axisLayer **之前**，否则圆角 clip 裁不到底色、圆角消失），
                //   不能只靠外层 Scaffold 的 containerColor。根因（2026-08-30 修）：targetSdk 36
                //   上系统「可预测性手势返回」默认开启，navigation-compose 在手势进行中会同时
                //   组合「即将退出的 detail 页」和「下面那一层」，并把 detail 页整体丢进
                //   graphicsLayer 做位移/圆角/scrim。该图层只包含本页**自己绘制的像素** ——
                //   页面没有底色时，卡片与图标之间全是透明区，手势中就直接看到下层内容。
                // ★ UfiNavFrameGate：转场/手势期间压住 WS 整屏重组（性能用，不是动画）。
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




            // 悬浮胶囊 overlay 浮层（不再占 innerPadding，避免遮挡页面内容）
            //
            // 底部留白不再由这里换算：胶囊现在活在自己的 Dialog 窗口里，
            // 距底偏移（导航栏高度 + CAPSULE_BOTTOM_MARGIN - CAPSULE_SHADOW_ROOM）
            // 由 CapsuleBlurHost 直接写进 WindowManager.LayoutParams.y。
            //
            // 注意：这里**不再**注入任何「胶囊模糊」开关。窗口级真模糊已被彻底移除
            // （连同其「每帧缩放窗口矩形」的配套方案 —— 那是动画卡顿与胶囊自激塌缩的根因），
            // 胶囊的玻璃观感改由 UfiCapsuleTabBar 的 frosted 渐变背景层恒定承担，
            // 因此驱动它的用户开关与 CompositionLocal 一并废弃，避免留下无效的死开关。
            // 胶囊 hide/show 改为手动画（graphicsLayer + Animatable）：
            // - show：p=0（scale 0 + alpha 0 + translationY=bottomEdgePx，缩在屏幕底部边缘）→ p=1（scale 1 + alpha 1 + translationY=0，
            //   idle 0.86 经内部 graphicsLayer 复合 → idle 态无跳变），tween 420ms 慢-快-慢；
            // - hide：反向向下方收缩（底边锚定 + translationY 补偿，p→0 时缩进屏幕底部边缘 + 渐隐），tween 360ms 慢-快-慢。
            // 不用 AnimatedVisibility 的 scaleOut(0f)+slideOutVertically：那会在 Dialog 内视觉上"吸回中心"而非向下方。
            val showCapsule = remember { mutableStateOf(showBottomBar) }
            val enterProgress = remember { Animatable(if (showBottomBar) 1f else 0f) }
            // 屏幕底部边缘：底部系统 inset（导航栏/手势条）+ 悬浮抬高(lift≈30dp)+安全间距(gap≈8dp)。
            // 缩放时以此补偿，使胶囊底边一路下移到屏幕底部边缘，而不是停在悬浮位。
            val density = LocalDensity.current
            val bottomEdgePx = WindowInsets.safeDrawing.getBottom(density) +
                with(density) { 38.dp.toPx() }   // 38 ≈ 悬浮抬高 30dp + 安全间距 8dp
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
                // 隐藏只把 p 动到 0（scale/alpha 归零、不绘制像素），窗口留着。
                // 代价：detail 页上多一个 wrap-content 的透明小窗 —— 它带
                // FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCH_MODAL（CapsuleBlurHost 施加），不吃触摸。
            }

            if (showCapsule.value) {
                // 用 Dialog（而非 Popup）承载胶囊：Dialog 才有 Window 对象，才能在窗口层
                // 统一施加触摸穿透 flag、底部锚定与透明背景 —— 这是「常驻底部浮层 + 窗外
                // 点击穿透」的实现基础。（整屏模糊的 FLAG_BLUR_BEHIND 对胶囊**永久禁用**；
                // 窗口内背景模糊也已移除，见 CapsuleBlurHost。）
                //
                // usePlatformDefaultWidth = false ⇒ 解除平台默认宽度约束，窗口尺寸由内容
                // 决定；胶囊是 wrap-content，于是拿到紧贴胶囊的小窗（窗外点击可穿透）。
                // 写成 true 会被撑成平台默认全宽 —— 吞掉整条底部点击，正是要避免的事故。
                //
                // decorFitsSystemWindows = false ⇒ 窗口**不**被系统栏安全区约束。
                // 写成 true 时窗口被夹在安全区内，系统会把手势小白条那条带子当成本窗口的
                // 装饰区填成白色遮罩，同时把胶囊整体顶高 —— 真机现象就是「胶囊在屏幕下边框
                // 被截断 + 底部一条白边」。改为 false 后，导航栏高度改由 CapsuleBlurHost
                // 通过 WindowInsets.navigationBars 动态读取并写进 LayoutParams.y
                // （配合 FLAG_LAYOUT_NO_LIMITS + 透明 navigationBarColor），偏移量精确可控。
                //
                // 触摸穿透红线（FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL）、窗口
                // wrap-content、底部 gravity/偏移，全部由 CapsuleBlurHost 在窗口层统一施加。
                // 胶囊常驻不可关闭，故两个 dismiss 开关都关掉。
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
                    // 必须包在 CapsuleBlurHost **外面**：它自己也读 LocalDensity 来算窗口 y 偏移
                    // （抬高 / 导航栏 inset），一并跟随缩放才不会与胶囊本体错位。
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
                                    scaleX = p
                                    scaleY = p
                                    // 渐入渐出：p=0 全透明 → p=1 不透明，叠加在缩放+位移上。
                                    alpha = p
                                    transformOrigin = TransformOrigin(0.5f, 1f)
                                    // 收缩/弹出都锚定「屏幕底部边缘」：胶囊底边到屏幕底的物理距离
                                    // ≈ 底部 inset（导航栏/手势条）+ 悬浮抬高(lift≈30dp)+安全间距(gap≈8dp)。
                                    // p→0 时胶囊整体下移 bottomEdgePx 同时缩到 0 → 视觉「缩进屏幕底部边缘」；
                                    // p→1 时从屏幕底部边缘向上弹入（与收缩互为镜像）。
                                    // ⚠ 只在 MainNavGraph 使用：护栏 collapseOrigin_mustNotBeBottom
                                    // 只检查 UfiCapsuleTabBar.kt，本文件不受限。
                                    translationY = (1f - p) * bottomEdgePx
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
