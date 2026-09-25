package com.ufi_axis

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ufi_axis.connection.ConnectionBootstrap
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.data.repository.ConnectionState
import com.ufi_axis.data.repository.WebSocketRepository
import com.ufi_axis.crash.CrashHandler
import com.ufi_axis.ui.components.CoreCrashDialog
import com.ufi_axis.ui.components.CrashLogDialog
import com.ufi_axis.ui.components.UnifiedUpdateDialog
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiAlertToastBridge
import com.ufi_axis.ui.components.common.UfiErrorBanner
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.app.navigation.buildAppScreens
import com.ufi_axis.app.startup.UfiStartupOverlay
import androidx.navigation.compose.rememberNavController
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.LocalUfiDialogClose
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.animation.page.LocalUfiReduceMotion
import com.ufi_axis.ui.animation.page.registerBuiltInTransitions
import com.ufi_axis.ui.navigation.MainNavGraph
import com.ufi_axis.ui.components.common.UfiNowPlayingSlot
import com.ufi_axis.ui.media.UfiAudioNowPlayingChip
import com.ufi_axis.ui.media.UfiAudioNowPlayingProbe
import com.ufi_axis.ui.screens.SetupScreen
import com.ufi_axis.ui.screens.UfiHeaderCaptionProbe
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.ThemeManager
import com.ufi_axis.ui.theme.ThemeMode
import com.ufi_axis.ui.theme.UFIAXISTheme
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import com.ufi_axis_core.util.UiLogBridge
import com.ufi_axis.util.DeviceKeyStore
import com.ufi_axis.util.NetworkMonitor
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.module.isForegroundDataFresh

/**
 * 同一条全局错误的重复抑制窗口（ms）。
 *
 * 失败的轮询会每隔几秒把同一句错误重写回 state（如「短信联系人加载失败」），
 * 不抑制就会一直弹同一条 toast。30s 内同文案只弹一次。
 */
private const val GLOBAL_ERROR_DEDUPE_MS = 30_000L

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var networkMonitor: NetworkMonitor

    /** 通知点击携带的 SMS 手机号（深链接），由 SmsScreen 消费后清空。 */
    internal val pendingSmsPhone = mutableStateOf<String?>(null)

    /** P3（应用内通知）：告警系统通知深链接标记，由 MainNavGraph 消费后清空。 */
    internal val pendingAlertDeepLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ★ 2026-09-24：**显式**指定两条系统栏的 scrim，不用 `enableEdgeToEdge()` 的默认值。
        //
        // androidx.activity 1.8.0 的默认值是（`EdgeToEdge.kt:72-73`）：
        //     statusBarStyle     = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT)
        //     navigationBarStyle = SystemBarStyle.auto(DefaultLightScrim, DefaultDarkScrim)
        // 其中 `DefaultLightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)` —— **90% 白**（`:38`），
        // 而 `auto()` 的 `detectDarkMode` 读的是**系统**夜间模式，与本应用的 `ThemeMode` 无关：
        // 系统浅色 + 应用内深色 ⇒ 取 lightScrim ⇒ 深色界面底部压一条 90% 白。
        //
        // `SystemBarStyle.dark(TRANSPARENT)` 把 lightScrim 与 darkScrim 都设成透明，
        // 且 `nightMode = MODE_NIGHT_YES` ⇒ `isNavigationBarContrastEnforced` 在
        // `EdgeToEdgeApi29`（`:293-294`）里直接算出 false，不必再靠下面那行补救
        //（那行保留，作为「将来换库版本、默认值又变回 auto」的双保险）。
        // appearance（图标明暗）由下方 setContent 里那个 LaunchedEffect 按**应用内** isDark 覆盖。
        //
        // ⚠ 复盘：这一处**不是**真机上那条「底部白带」的因（换成透明后白带照旧）。
        //   真因是底栏那个 Dialog 窗口缺 `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS`，
        //   见 `UfiCapsuleBlurHost.CAPSULE_WINDOW_FLAGS_ON`。这里保留是因为默认值确实不对
        //   （API < 29 的分支会直接把 90% 白写进 navigationBarColor），但别指望它修那类问题。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        // 2026-08-20：禁用导航栏对比度增强层，消除手势导航模式下的底部白色遮罩（scrim）。
        // enableEdgeToEdge() 默认 isNavigationBarContrastEnforced=true，系统会在导航栏区域
        // 叠加半透明 scrim 以保证对比度——表现为「底部小白条沉浸不彻底」。
        window.isNavigationBarContrastEnforced = false
        prefs = AppPreferences(this)
        networkMonitor = NetworkMonitor(this)
        networkMonitor.startMonitoring()

        // 读取启动 Intent 中的 SMS 深链接（通知点击携带的手机号）
        handleSmsDeepLinkIntent(intent)
        // P3（应用内通知）：读取启动 Intent 中的告警深链接标记
        handleAlertDeepLinkIntent(intent)

        // 装配六种内置页面转场策略（幂等）。必须早于任何 UfiPageSwitcher 首次组合，
        // 以及早于设置页从 SharedPreferences 还原 pageTransition id —— 否则注册表
        // 只剩安全 Fade 兜底，全部动画会静默退化为渐入渐出（不崩、也不报错）。
        registerBuiltInTransitions()

        // :app:ui 的日志出口接线。:app:ui 不依赖 :app:data，拿不到 DebugLog，
        // 在此把 sink 指向它，UI 层的日志才能进「关于设备 → 调试日志」的「手机 APP」页签。
        // 用 w 而非 i：i 受「详细日志」开关约束，w 无条件记录，用户不必先开开关。
        UiLogBridge.sink = { tag, msg -> DebugLog.w(tag, msg) }

        setContent {
            val themeManager = remember { ThemeManager(applicationContext) }
            // 这里走的是 observeExternal 的默认值 true，所以 ThemeManager 在 init 里往
            // applicationContext 的 prefs 上注册了监听 —— 而 prefs 活到进程结束，Activity 不是。
            // Activity 每次重建（切主题、转屏、字体缩放）都会新造一个 ThemeManager 再注册一次，
            // 旧的那个没人解绑 ⇒ 监听器逐次累积。所以这里必须配对 dispose()。
            // 不能改成 observeExternal = false：设置页改完要实时联动，关掉监听就退化成「重启才生效」。
            DisposableEffect(themeManager) { onDispose { themeManager.dispose() } }

            // 系统「移除动画」无障碍设置探测（开发者选项把动画时长缩放调为 0 亦命中）。
            // 只在首次组合读一次：该设置改变会重启 Activity，无需实时监听。
            val reduceMotion = remember {
                val transitionScale = Settings.Global.getFloat(
                    contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f
                )
                val animatorScale = Settings.Global.getFloat(
                    contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
                )
                transitionScale == 0f || animatorScale == 0f
            }
            val selectedThemeId by themeManager.selectedThemeId.collectAsState()
            val themeMode by themeManager.themeMode.collectAsState()
            // 全局 UI 缩放：设置页写入后经 ThemeManager 的 companion 共享 flow 立刻可见，整树重排
            val uiScalePercent by themeManager.uiScalePercent.collectAsState()
            // Material You 动态取色开关（2026-09-05 下午）。同样走 companion 共享 flow，
            // 所以设置页拨完开关这里立刻收到 —— 它必须进下面 remember 的 key，见那行注释。
            val dynamicEnabled by themeManager.dynamicEnabled.collectAsState()
            // 「自定义」皮肤的种子色（2026-09-05 傍晚）。同上，companion 共享 flow，
            // 取色器点确认后这里立刻收到；同样必须进 remember 的 key。
            val customSeedColor by themeManager.customSeedColor.collectAsState()

            // 外观模式 → 是否深色（AUTO 跟随系统）
            val systemDark = isSystemInDarkTheme()
            val isDark = when (themeMode) {
                ThemeMode.AUTO -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // `getCurrentPalette()` 是**非 Composable** 的 `.value` 直读，它自己不会因为 flow
            // 变化而重算，所以 remember 的 key 必须**覆盖它读的全部输入**。
            // 它现在读三个：`selectedThemeId`（皮肤 id）、`dynamicEnabled`（壁纸取色开关）、
            // `customSeedColor`（自定义皮肤的种子色）。漏掉任何一个的表现都很具体：
            // 漏 `dynamicEnabled` = 拨了开关整页颜色不动；
            // 漏 `customSeedColor` = 在取色器里换了颜色、确认了，整页颜色不动 ——
            // 直到别的原因触发重组才"忽然"变色。
            val palette = remember(selectedThemeId, dynamicEnabled, customSeedColor) {
                themeManager.getCurrentPalette()
            }

            // ★ 2026-09-24：系统栏图标 / 手势小白条的明暗必须跟**应用内**主题走。
            //
            // `enableEdgeToEdge()` 只在 onCreate 里按 `Configuration.uiMode`（**系统**夜间模式）
            // 定过一次 appearance，之后不再更新。本应用的外观是自己的设置项
            //（`ThemeMode.LIGHT/DARK/AUTO` + 多套皮肤），两者可以完全相反：
            // 系统深色 + 应用内浅色 ⇒ 系统仍按"深背景"渲染 ⇒ 状态栏图标与手势小白条都是**白**的，
            // 压在浅色页面上直接看不见。贴底通栏之后小白条正好落在栏里，这条就更明显了。
            //
            // 判据用**本应用**算出来的 `isDark`（`ThemeMode` + 系统夜间模式的最终结论），
            // 而不是 `enableEdgeToEdge()` 内部那份只看 `Configuration.uiMode` 的判断。
            // 这里拿不到解析后的 `pageBg`（它在 UFIAXISTheme 里面才 provide），
            // 而 `isDark` 已经足够：页面底色的明暗本来就由它决定。
            // 底栏所在的 Dialog 窗口是另一个窗口、有自己的 appearance，判据更精细
            //（按栏底色的实际亮度），见 `UfiCapsuleBlurHost` 的 `CapsuleWindowMetrics.lightNavHandleSurface`。
            val lightSystemBarIcons = !isDark
            LaunchedEffect(lightSystemBarIcons) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                if (controller.isAppearanceLightNavigationBars != lightSystemBarIcons) {
                    controller.isAppearanceLightNavigationBars = lightSystemBarIcons
                }
                if (controller.isAppearanceLightStatusBars != lightSystemBarIcons) {
                    controller.isAppearanceLightStatusBars = lightSystemBarIcons
                }
            }

            UFIAXISTheme(palette = palette, darkTheme = isDark, uiScalePercent = uiScalePercent) {
                // ★ 窗口底色跟随主题（对齐参考实现 UFITOOLS-Widget 的 BackgroundUtil：底色画在
                //   window.decorView 上）。Theme.UFIAXIS 继承 android:Theme.Material.Light 的
                //   windowBackground 是**不透明白底**，深色/自定义配色下：
                //     • 冷启动首帧闪白；
                //     • 系统「可预测性手势返回」把整窗缩放时，窗口四周露出白色边框。
                //   窗口层与 Compose 内容层用同一个 pageBg，这两个现象一起消失。
                val windowBg = LocalResolvedPalette.current.pageBg
                SideEffect {
                    window.setBackgroundDrawable(ColorDrawable(windowBg.toArgb()))
                }
                // 全局 Toast 反馈（UfiToastHost）：state 提升到 MainActivity 根，
                // CrashLogDialog 等弹窗场景通过 onToast 上抛，host 置于弹窗之后组合保证层级。
                var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
                Box(modifier = Modifier.fillMaxSize()) {
                // 启动后检测历史闪退日志，存在则弹窗提示（2026-08-11：去重——同一崩溃 fileLastModified 只弹一次）
                val crashFile = remember { CrashHandler.latestCrash(this@MainActivity) }
                var showCrashLog by remember {
                    mutableStateOf(
                        crashFile != null && !CrashHandler.isCrashAlreadyShown(this@MainActivity, crashFile.lastModified())
                    )
                }
                // 标记本次已展示（避免 dismiss 后下次再弹同一崩溃）
                LaunchedEffect(crashFile) {
                    if (crashFile != null && showCrashLog) {
                        CrashHandler.markCrashShown(this@MainActivity, crashFile.lastModified())
                    }
                }
                if (showCrashLog && crashFile != null) {
                    CrashLogDialog(
                        file = crashFile,
                        onDismiss = { showCrashLog = false },
                        onCleared = {
                            CrashHandler.clearCrashes(this@MainActivity)
                            showCrashLog = false
                        },
                        onToast = { toastMessage = it }
                    )
                }

                var isSetupComplete by remember { mutableStateOf(prefs.isSetupComplete) }
                // 主动重新配对开关：由设置页「重新配对 / 切换设备」触发（UID-001，T05）。
                var forceSetup by remember { mutableStateOf(false) }

                // 主 UI 分支里创建的 VM：切换设备时要清它的会话缓存。
                // 用 var 槽而不是把 lambda 放到 VM 之后 —— onRepairRequested 必须在
                // if/else 之前就绪（SetupScreen 分支也要能用，且 appScreens 要记住稳定实例）。
                var sessionVm by remember { mutableStateOf<MainViewModel?>(null) }

                // 主动重新配对：清空凭据 + 丢弃设备身份密钥 + 丢掉上一台设备的会话缓存，
                // 强制回到 SetupScreen（T05 / 2026-09 P1）。
                // 必须连密钥一起删：留着旧密钥就等于留着旧设备身份，用户"换设备/重新配对"的
                // 意图（尤其是把这台从设备列表里摘掉）就落不了地。
                val onRepairRequested: () -> Unit = {
                    sessionVm?.prepareDeviceSwitch()
                    prefs.token = ""
                    DeviceKeyStore.reset()
                    prefs.isSetupComplete = false
                    isSetupComplete = false
                    forceSetup = true
                }

                // 凭据被**确证**吊销 → 清空本地 Token 并回到配对引导（边缘情况 #1/#3）。
                // 触发条件（444 / 连续次数 / 时间跨度 / 传输失败豁免）全在 RetrofitClient 的
                // 鉴权策略里，这里只是装配；一次瞬时 401 不再把人踢下线（2026-09-08 事故）。
                // F22：全局回调集中到 ConnectionBootstrap。
                ConnectionBootstrap.registerUnauthorizedHandler(this@MainActivity, prefs) {
                    isSetupComplete = false
                }

                if (!isSetupComplete || forceSetup) {
                    SetupScreen(
                        onSetupComplete = { ip, port, token ->
                            prefs.serverIp = ip
                            prefs.serverPort = port
                            prefs.token = token
                            ConnectionBootstrap.recreateRetrofit(prefs)
                            prefs.isSetupComplete = true
                            isSetupComplete = true
                            forceSetup = false
                        }
                    )
                } else {
                    val api = remember { RetrofitClient.getApiService(prefs) }
                    val webSocketRepository = remember {
                        WebSocketRepository(
                            ConnectionBootstrap.rewriteWsUrl(prefs.baseUrl),
                            prefs.token
                        )
                    }
                    val owner = LocalLifecycleOwner.current
                    val viewModel: MainViewModel = viewModel(
                        factory = ConnectionBootstrap.mainViewModelFactory(
                            api, webSocketRepository, networkMonitor, applicationContext
                        )
                    )
                    sessionVm = viewModel
                    // F12：绑定网络恢复监听 + App 回到前台主动重连（网络/前台恢复）
                    // 2026-09：同时接健康检查前后台闸门 —— 后台停周期 /health，回前台静默探一次再恢复。
                    DisposableEffect(webSocketRepository, viewModel) {
                        webSocketRepository.bindNetworkRecovery(applicationContext)
                        // 2026-09-21：任何业务请求在传输层失败都立刻驱动一次 /health 探活。
                        // 这条桥接与"是哪个域失败"无关，补上了只覆盖 5 个 state 的旧触发链。
                        ConnectionBootstrap.registerTransportFailureHandler {
                            viewModel.onApiTransportFailure()
                        }
                        val observer = LifecycleEventObserver { _, event ->
                            when (event) {
                                Lifecycle.Event.ON_RESUME -> {
                                    webSocketRepository.onAppForegrounded()
                                    viewModel.onAppForegrounded()
                                }
                                Lifecycle.Event.ON_PAUSE -> viewModel.onAppBackgrounded()
                                else -> {}
                            }
                        }
                        owner.lifecycle.addObserver(observer)
                        onDispose {
                            owner.lifecycle.removeObserver(observer)
                            webSocketRepository.unbindNetworkRecovery()
                            ConnectionBootstrap.unregisterTransportFailureHandler()
                        }
                    }

                    // 2026-08-25: 启动全局告警轮询（UI 进程），确保在非监控页也能弹出 Toast（应用内 banner）。
                    // 与独立进程 NotifyService 的后台轮询配合：独立进程管系统通知，UI 进程管前台 banner。
                    // 仅在 Activity 处于前台（RESUMED）时轮询，息屏或退到后台时停止以省电。
                    // 2026-09-03: 再加一道「后台服务开关」门控 —— 用户点了停止服务就不该再有自动轮询
                    //（这些读接口在 core 缓存过期后会顺带把 goform 查询/登录拉起来）。未读到状态时按开启处理。
                    val svcState by viewModel.network.serviceState.collectAsState()
                    val alertPollAllowed = !svcState.loaded || svcState.enabled
                    DisposableEffect(viewModel, alertPollAllowed) {
                        // 初始检查：如果当前已经是 RESUMED 状态，立即启动
                        if (alertPollAllowed && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            viewModel.dashboard.startAlertPolling(30_000)
                        } else if (!alertPollAllowed) {
                            viewModel.dashboard.stopAlertPolling()
                        }

                        val observer = LifecycleEventObserver { _, event ->
                            when (event) {
                                Lifecycle.Event.ON_RESUME ->
                                    if (alertPollAllowed) viewModel.dashboard.startAlertPolling(30_000)
                                Lifecycle.Event.ON_PAUSE -> viewModel.dashboard.stopAlertPolling()
                                else -> {}
                            }
                        }
                        owner.lifecycle.addObserver(observer)
                        onDispose {
                            owner.lifecycle.removeObserver(observer)
                            viewModel.dashboard.stopAlertPolling()
                        }
                    }

                    // ── 统一更新提示（2026-09-06）──
                    //
                    // 原来这里是「core 有新版本」的专属弹窗 + 一个本地 `showUpdateDialog` 布尔，
                    // 而关于页另有一套 App 自更新弹窗（也是本地 remember 布尔）。两条链路互不知情，
                    // 同时命中就是两个独立平台 Window 叠在一起：两层 scrim、返回键只关最上层。
                    //
                    // 现在可见性与内容都来自唯一的 UpdatePromptModule —— App 项与 Core 项是
                    // **同一个弹窗里的两块**，关于页的手动检查也打开这同一个槽，叠加从构造上不可能发生。
                    // 自动弹出的节流（本次启动只弹一次、关掉不再自动重弹）也在那个模块里，
                    // 所以这里不需要再写 LaunchedEffect 上升沿判断。
                    val updatePrompt by viewModel.updatePromptState.collectAsState()
                    UnifiedUpdateDialog(
                        state = updatePrompt,
                        onDismiss = { viewModel.updatePrompt.dismiss() },
                        onDownloadApp = { viewModel.tools.downloadFrontendApk() },
                        onInstallApp = { viewModel.tools.installFrontendApk() },
                        onCoreUpdateConfirmed = {
                            // 先标记再发请求：标记后 coreStatus 才会被认作"core 自更新进度"
                            // （updateDeviceState 也被手动推送 APK 链路复用）。
                            viewModel.updatePrompt.markCoreUpdateTriggered()
                            viewModel.tools.triggerDeviceUpdate()
                        }
                    )

                    // 设备后端（core）崩溃提醒（2026-09-04）。
                    // core 崩溃后由 keepalive 脚本 / START_STICKY 自动拉起，app 此前完全无感 ——
                    // 用户只看到数据断了一下。这里在首帧与每次重新连上设备时拉一次
                    // `GET /api/service/crash`，同一次崩溃只提示一次（去重键是 core 的时间戳）。
                    val coreCrash by viewModel.tools.coreCrashNotice.collectAsState()
                    val wsState by webSocketRepository.connectionState.collectAsState()
                    // 2026-09-05（P3）：触发条件从"每次 wsState 翻转"收紧到**上升沿 + 去抖**。
                    //
                    // 原来 key 是整个 wsState，而弱网下这个流会在
                    // DISCONNECTED / CONNECTING / RECONNECTING / CONNECTED 之间高频抖动 ——
                    // 每翻一次就发一个 `GET /api/service/crash`。去重只发生在 UI 侧（同一崩溃只弹一次），
                    // 请求本身照发，等于把一个"偶发一次"的查询变成了跟着抖动走的轮询。
                    //
                    // 上升沿判据用 `旧值 != CONNECTED && 新值 == CONNECTED`，不是"旧值必须是
                    // DISCONNECTED"：真实路径是 DISCONNECTED → CONNECTING → CONNECTED，
                    // 卡死在 DISCONNECTED 直接跳 CONNECTED 反而是少数情况。
                    //
                    // 首帧（previous == null，此刻通常是 DISCONNECTED）保留一次：崩溃提醒不该依赖
                    // WS 连上 —— core 刚崩过时往往正好连不上，那恰恰是最需要提醒的时刻。
                    var lastWsState by remember { mutableStateOf<ConnectionState?>(null) }
                    var lastCrashQueryElapsedMs by remember { mutableLongStateOf(0L) }
                    LaunchedEffect(wsState) {
                        val previous = lastWsState
                        lastWsState = wsState
                        val isFirstFrame = previous == null
                        val isRisingEdge =
                            previous != ConnectionState.CONNECTED && wsState == ConnectionState.CONNECTED
                        if (!isFirstFrame && !isRisingEdge) return@LaunchedEffect
                        // 最小间隔去抖：复用 DataFreshness 那套单调时钟判据（elapsedRealtime，
                        // **不是** currentTimeMillis —— 后者可被 NTP/用户改，往前跳会把去抖窗口跳过去）。
                        // 窗口沿用前台刷新周期：连续掉线重连时最多每 10s 问一次崩溃状态。
                        val nowElapsedMs = SystemClock.elapsedRealtime()
                        if (isForegroundDataFresh(lastCrashQueryElapsedMs, nowElapsedMs)) {
                            return@LaunchedEffect
                        }
                        lastCrashQueryElapsedMs = nowElapsedMs
                        viewModel.tools.loadCoreCrashNotice()
                    }
                    coreCrash?.let { notice ->
                        CoreCrashDialog(
                            timestamp = notice.timestamp,
                            summary = notice.summary,
                            file = notice.file,
                            onDismiss = { viewModel.tools.markCoreCrashNoticeShown() },
                            onToast = { toastMessage = it }
                        )
                    }

                    // 保存连接前记住上一次端点，用于 P2「同机轻刷新 / 换机丢缓存」分流。
                    var lastEndpoint by remember {
                        mutableStateOf("${prefs.serverIp}:${prefs.serverPort}")
                    }
                    val onServerConfigChanged: () -> Unit = {
                        val next = "${prefs.serverIp}:${prefs.serverPort}"
                        val hostChanged = next != lastEndpoint
                        lastEndpoint = next
                        ConnectionBootstrap.recreateRetrofit(prefs)
                        // 刷新实时通道：更新 WS 连接参数（url/token）并重连，
                        // 避免停留在旧地址（本实例由 MainViewModel 长期持有，更新即作用于同一连接）。
                        webSocketRepository.updateConfig(
                            ConnectionBootstrap.rewriteWsUrl(prefs.baseUrl),
                            prefs.token
                        )
                        sessionVm?.onServerEndpointChanged(hostChanged)
                        // 通知守护进程（token/IP 变更时 prefs setter 已 dispatch；此处兜底再推一次快照）
                        com.ufi_axis.data.notification.NotifyDispatchReceiver.dispatchConnectionChange(
                            applicationContext, prefs.baseUrl, prefs.token
                        )
                    }

                    CompositionLocalProvider(LocalUfiReduceMotion provides reduceMotion) {
                        // 2026-09-04：这张 map 必须 remember。
                        // `buildAppScreens` 内部 `mapValues` 每次调用都产出**全新** Map 与全新
                        // content lambda，而宿主页缓存的 key 正是它（MainNavGraph 里
                        // `remember(screens, navController, entry)`）—— 于是任何上层重组
                        // （主题、缩放、toast、深链接 state…）都会让 5 个 Tab 页连内容一起重建。
                        // 从二级页返回时这次重建恰好和平移动画撞在同几帧，就是"返回卡一下"。
                        val navController = rememberNavController()
                        val appScreens = remember(viewModel, onServerConfigChanged, onRepairRequested) {
                            buildAppScreens(viewModel, onServerConfigChanged, onRepairRequested)
                        }
                        MainNavGraph(
                            screens = appScreens,
                            themeManager = themeManager,
                            navController = navController,
                            pendingSmsPhone = pendingSmsPhone,
                            pendingAlertDeepLink = pendingAlertDeepLink,
                            // 胶囊活在独立 Dialog 窗口里，主窗口的全屏浮层盖不住它 ——
                            // 启动页期间必须从这里关掉，否则加载页上浮着一条胶囊。
                            suppressCapsule = viewModel.startupOverlayVisible.collectAsState().value
                        )

                        // ── 「正在播放」：常驻探测 + 注入到各页标题栏右侧 ──
                        //
                        // 音乐跑在前台服务里、离开播放页也不停，控制入口得跟着用户走。
                        // 之前试过悬浮迷你条（底部/顶部都试了）—— 无论放哪都在遮内容，还得配
                        // 最小化/上滑关闭/自动收起才不烦人，那是在给"遮挡"打补丁。改放标题栏
                        // 右侧动作区：不占内容空间、不需要收起逻辑，每页位置一致。
                        //
                        // 探测器必须挂在这里（Activity 顶层）而不是控件内部：标题栏要先知道
                        // "有没有东西要显示"才能决定版式，写在控件里就成了「不渲染 ⇒ 探测不到」的死结。
                        UfiAudioNowPlayingProbe(viewModel)
                        // 槽位是**进程级**单例，lambda 捕获了 navController：
                        // 离开时必须清掉，否则 Activity 销毁后槽里还攥着一个已死的 navController。
                        DisposableEffect(navController) {
                            UfiNowPlayingSlot.content.value =
                                { UfiAudioNowPlayingChip(navController) }
                            onDispose { UfiNowPlayingSlot.content.value = null }
                        }
                        // 标题栏「标题下方小字」：天气 + 今日诗词（2026-09-18）。
                        // 天气原来在标题栏右侧、与正在播放抢位置，现在两者各有其位。
                        UfiHeaderCaptionProbe(viewModel)
                        // 告警浮层：2026-08-30 起复用**普通 Toast**（UfiToastOverlay），
                        // 原来那套独立的应用内 banner（UfiAlertBanner* 三个文件）已删除 ——
                        // 两套都是"挂在 decorView 的顶部浮层"，没有理由维护两份。
                        // Bridge 仅 collect AlertBus + 解析配色，自身不渲染任何 UI。
                        // 依赖 viewModel，故必须在 else（已配对）分支内。
                        UfiAlertToastBridge(
                            onAck = { item -> viewModel.dashboard.ackAlertByBanner(item.type) }
                        )

                        // ── 全局错误 → Toast（2026-09-05 第二版）──
                        //
                        // 第一版是"Activity 级悬浮错误卡 + 自己数 8s 收起"。两个毛病：
                        // 1. 生命周期自己管 = 和 module state 打架。清源后 message 变 null、下一次
                        //    轮询失败又写回同一句（如「短信联系人加载失败」），于是卡片反复闪；
                        //    多个来源同时有错时还会一条接一条弹，观感就是"自动消失不正常"。
                        // 2. 「某个列表加载失败」这类本来就是一次性告知、不需要常驻卡片。
                        //
                        // 现在直接复用全站 Toast 通道（UfiToastHost → UfiToastOverlay）：
                        // 生命周期、退出动画、层级全由它负责，这里只负责"翻译一次并清源"。
                        // 代价：没有「重试」按钮了 —— 仪表盘/网络/监控本来就有轮询 + 回前台重取，
                        // 全局通道里的重试对 tools/service 也从来不成立。
                        //
                        // dismissGlobalError 立刻调用（不等 toast 播完）：错误留在 state 上，
                        // 下次切页重组会被当成新错误再弹一次。
                        // 同文案 30s 内不重复弹：失败的轮询会每隔几秒重写同一句错误。
                        val globalError by viewModel.globalError.collectAsState()
                        var lastGlobalErrorText by remember { mutableStateOf<String?>(null) }
                        var lastGlobalErrorAt by remember { mutableLongStateOf(0L) }
                        LaunchedEffect(globalError?.message) {
                            val err = globalError ?: return@LaunchedEffect
                            viewModel.dismissGlobalError(err.source)
                            val now = System.currentTimeMillis()
                            val duplicate = err.message == lastGlobalErrorText &&
                                now - lastGlobalErrorAt < GLOBAL_ERROR_DEDUPE_MS
                            if (duplicate) return@LaunchedEffect
                            lastGlobalErrorText = err.message
                            lastGlobalErrorAt = now
                            toastMessage = ToastMessage(err.message, ToastType.ERROR)
                        }

                        // ── 写操作成功提示（2026-09-22）──
                        //
                        // 与上面的全局错误共用同一个 toast 宿主（宿主是平台层单例，后来的覆盖先来的）。
                        // 挂在 Activity 顶层的理由和错误通道完全一样：写进各个页面就会抄 N 遍，
                        // 漏一个页面就变成"保存了但没提示"。
                        //
                        // 这里**刻意不做**错误通道那套 30s 同文案去重：连续保存两次就该看到两次确认。
                        // LaunchedEffect 的 key 用整个 notice（含自增 seq），保证同文案也能重新触发。
                        val writeNotice by viewModel.writeNotice.collectAsState()
                        LaunchedEffect(writeNotice) {
                            val notice = writeNotice ?: return@LaunchedEffect
                            viewModel.dismissWriteNotice()
                            toastMessage = ToastMessage(
                                text = notice.message,
                                type = ToastType.SUCCESS,
                                subtitle = notice.subtitle
                            )
                        }

                        // ── 启动加载页 ＋「连不上设备」的唯一展示面（2026-09-22）──
                        //
                        // 叠在导航图**之上**而不是替换它：替换的话导航图要等浮层消失才开始组合、
                        // 各页的 LaunchedEffect 才开始取数，预加载省下的时间又还回去了。
                        // 现在下面正常取数，浮层淡出时已经是有数据的界面。
                        //
                        // 这一页同时接管了原来那个「无法连接后端服务」模态弹窗的职责
                        //（那段代码已删）。判据没变，还是 `connectivity`（/health 连击确证）；
                        // 变的是展示形态 —— 冷启动连不上时弹窗底下是个没数据的空界面，
                        // 用一整页写清病因和下一步更清楚。仪表盘卡内状态条保留作被动残留。
                        //
                        // 消失条件全在 MainViewModel：`startupOverlayVisible`（两个挡的理由）
                        // 与 `startupGate`（"加载中"那一态的三条终局，防止永远停在加载中）。
                        val startupVisible by viewModel.startupOverlayVisible.collectAsState()
                        val preloadProgress by viewModel.preloadProgress.collectAsState()
                        val startupProblem by viewModel.startupProblem.collectAsState()
                        UfiStartupOverlay(
                            visible = startupVisible,
                            progress = preloadProgress,
                            problem = startupProblem,
                            // 重试**不让页面消失**：探活结果才是答案，通了页面自己会走。
                            // 但必须给一句反馈 —— 不然点下去屏幕上什么都不动，
                            // 看起来像按钮坏了（探活最长 15s 才出结论）。
                            onRetry = {
                                toastMessage = ToastMessage("正在重新连接设备…", ToastType.INFO)
                                viewModel.retryConnectivity()
                            },
                            // 去别的页面前必须先让这一页让开，否则它会把目标页整个盖住
                            onOpenServerConfig = {
                                viewModel.skipConnectionNotice()
                                navController.navigate(Routes.DETAIL_SERVER_CONFIG)
                            },
                            onSetup = {
                                viewModel.skipConnectionNotice()
                                forceSetup = true
                            },
                            onSkip = { viewModel.skipConnectionNotice() }
                        )
                    }
                }
                UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
            }
        }
    }
}

    override fun onResume() {
        super.onResume()
        // 后台守护「从最近任务隐藏」开关：每次回到前台重新应用，覆盖进程被系统回收重建的场景。
        prefs.applyHideFromRecents()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::networkMonitor.isInitialized) {
            networkMonitor.stopMonitoring()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSmsDeepLinkIntent(intent)
        handleAlertDeepLinkIntent(intent)
    }

    /** 从 Intent 中提取 SMS 深链接手机号，写入 [pendingSmsPhone] 供 Compose 层消费。 */
    private fun handleSmsDeepLinkIntent(intent: Intent?) {
        val phone = intent?.getStringExtra(NotificationCenter.EXTRA_SMS_PHONE)
        if (!phone.isNullOrEmpty()) {
            pendingSmsPhone.value = phone
        }
    }

    /** P3（应用内通知）：从 Intent 中提取告警深链接标记，写入 [pendingAlertDeepLink] 供 MainNavGraph 消费。 */
    private fun handleAlertDeepLinkIntent(intent: Intent?) {
        val deep = intent?.getStringExtra(NotificationCenter.EXTRA_ALERT_DEEPLINK)
        if (!deep.isNullOrEmpty()) {
            pendingAlertDeepLink.value = deep
        }
    }
}
