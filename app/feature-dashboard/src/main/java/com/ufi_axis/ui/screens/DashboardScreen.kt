package com.ufi_axis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.ufi_axis.ui.animation.page.isUfiPageForeground
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.screens.home.HomeConnectionCard
import com.ufi_axis.ui.screens.home.HomeDeviceInfoCard
import com.ufi_axis.ui.screens.home.HomeMetricsCard
import com.ufi_axis.ui.screens.home.HomeMetricsDialog
import com.ufi_axis.ui.screens.home.MetricsSection
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.module.FOREGROUND_REFRESH_INTERVAL_MS
import com.ufi_axis.viewmodel.state.DashboardState
import com.ufi_axis.data.repository.ConnectionState
import java.util.Calendar

/**
 * 首页 — 现代圆润设计系统。
 *
 * 布局（从上到下）：
 * 1. 顶部栏：问候语 + 设置齿轮图标
 * 2. 连接状态卡（替代液动 Hero）
 * 3. 4-in-1 指标卡（CPU/内存/电池/存储）
 * 4. 设备信息列表卡（型号/固件/系统/内核/运行时间/SIM）
 *
 * 导航：从首页卡片点击进入各 detail 页，无底部 Tab。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.dashboardState.collectAsState()
    val wsState by viewModel.wsConnectionState.collectAsState()
    // 本月流量数据源：与「工具-流量管理」同源（getTrafficLimit 端点），避免用仪表盘 summary 不可靠的 traffic_limit 字段。
    val trafficMgmt by viewModel.trafficManagementState.collectAsState()
    val palette = LocalResolvedPalette.current

    // ── WiFi 信息（hero 卡「已连接设备 / WiFi 信息」数据源） ──
    // 与「网络-无线设置」同源：NetworkModule.refreshWifi() 在页面前台轻量拉取
    // networkState.wifiSettings / wifiClients，这里仅读属性，不再重复请求。
    val networkState by viewModel.networkState.collectAsState()
    val wifi = networkState.wifiSettings
    // 已连接设备总数 = station_list + lan_station_list（与网络界面同口径）
    val wifiClientCount = networkState.wifiClients?.allStations?.size
    // WiFi 名称 / 频段：仅当热点开启时展示（chip1=2.4G, chip2=5G，与 AppPreferences 注释一致）
    val wifiSsid = if (wifi?.enabled == true) wifi.ssid?.takeIf { it.isNotBlank() } else null
    val wifiBand = if (wifi?.enabled == true) {
        if (wifi.activeChip == "chip2") "5GHz" else "2.4GHz"
    } else null

    // 指标快捷详情弹窗状态：点击首页指标环时弹出对应对话框，而非跳转新页面。
    var metricsDialog by remember { mutableStateOf<MetricsSection?>(null) }

    // 实时连接状态提示
    val realtimeStatusMessage: String? = when (wsState) {
        ConnectionState.CONNECTED -> null
        ConnectionState.CONNECTING -> null
        ConnectionState.RECONNECTING -> "实时通道重连中，数据可能过期"
        ConnectionState.DISCONNECTED -> "实时连接已断开，数据可能过期"
    }

    // 页面前台判定（离屏 Tab 门控的总开关） ──
    // 本页是底部 Tab 的 index 0，宿主 UfiPageSwitcher 开了 keepPagesAlive
    // （→ beyondViewportPageCount = 1），所以本页的组合**永不销毁**：用户切到网络/监控/
    // 工具/我的任何一个 Tab，下面 DisposableEffect 的 onDispose 都不会执行，10s 全量轮询
    // 会一直空跑（每分钟 6 次 dashboard/summary），每轮回包都让不可见页面重组一次。
    // 故必须用它做门控。
    //（2026-09-05：轮询每轮还会把 isLoading 翻转两次那条已经不成立 —— 有数据时刷新是静默的，
    // 见 DashboardModule.refreshDashboardInternal。但"空跑 6 次请求"这条理由本身没变。）
    //
    // 必须用 isUfiPageForeground() 而不是 LocalUfiPageActive.current：后者默认 false，
    // 本页被 NavHost 当 detail 页单独打开时并不在 Switcher 内，只能拿到默认值，
    // 直接拿它门控会把 detail 场景误判成后台页，导致数据永不加载。
    val pageForeground = isUfiPageForeground()

    // ── 自动刷新之一：只负责跟踪 Activity 是否前台，不再直接启停轮询 ──
    // 原实现把「观察者注册」和「轮询启停」揉在同一个 DisposableEffect 里，
    // 导致启停只能由 Activity 级事件驱动、感知不到 Tab 切换。这里拆成两段：
    // 本段专管观察者的注册/反注册（key 仍是 lifecycleOwner，注册正确性不变），
    // 只把结果落到 lifecycleResumed 状态上，交给下一段做真正的启停决策。
    var lifecycleResumed by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            lifecycleResumed = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lifecycleResumed = false
        }
    }

    // ── 「停止服务」后不再自动轮询 ──
    // 2026-09-03：用户在设置里点了「停止服务」，core 只停了自己的采集循环（DataScheduler），
    // 而本页 10s 一轮的 dashboard/summary 照旧发；这些读接口在 core 的缓存过期后会顺手把
    // goform 查询（连带登录）拉起来 —— 表现就是"已经停止服务了还在登录设备后台"。
    // 既然用户明确要求停，app 侧的自动轮询也必须一起停；手动下拉刷新不受影响。
    // 服务状态进页面读一次（/api/service/status 只读本地开关，不碰 goform）；
    // 还没读到时按"开启"处理，避免误伤正常使用。
    val serviceState by viewModel.network.serviceState.collectAsState()
    val serviceEnabled = !serviceState.loaded || serviceState.enabled
    LaunchedEffect(Unit) { viewModel.network.loadServiceStatus() }

    // ── 自动刷新之二：轮询启停 = (Activity 前台 && 页面前台 && 后台服务未被停止) ──
    // 轮询 Job 挂在 ViewModel scope 上（DashboardModule.startAutoRefresh），组合销毁不会自动
    // 收走它，所以这里用 DisposableEffect 而非 LaunchedEffect —— 前者的 onDispose 能在
    // 「条件变化」和「组合真的销毁」两种情况下都保证 stop 被调用，不会漏关。
    // startAutoRefresh 首行即 stopAutoRefresh()，重复调用幂等安全。
    //
    // ⚠ `pageForeground` 必须留在 key 里（否则轮询不启停，这是红线）。它翻转带来的那次
    // 「立刻重拉」已在 Module 层用数据新鲜度闸门消掉了 —— 见 DashboardModule.startAutoRefresh
    // 与 DataFreshness.kt：新鲜窗口内只补定时器、不发请求，于是横滑落定后不再有 REST 回包
    // 整页写落在胶囊归位的可见运动窗口里。
    DisposableEffect(lifecycleResumed, pageForeground, serviceEnabled) {
        if (lifecycleResumed && pageForeground && serviceEnabled) {
            viewModel.dashboard.startAutoRefresh(FOREGROUND_REFRESH_INTERVAL_MS)
        } else {
            viewModel.dashboard.stopAutoRefresh()
        }
        onDispose { viewModel.dashboard.stopAutoRefresh() }
    }

    // 本月流量数据源对齐「工具-流量管理」（独立 getTrafficLimit 端点，仪表盘 summary 的
    // traffic_limit 字段不可靠）。前台时加载一次，供 hero 卡「本月流量」使用。
    // 同时轻量刷新 WiFi 信息（hero 卡「已连接设备 / WiFi 信息」，与网络界面同源）。
    //
    // 这两个调用都自带新鲜度闸门（见各自 KDoc）：横滑回到本页时若数据还新鲜就直接返回，
    // 一个请求都不发、一次状态都不写。要强制重拉的路径（写后回读 / core 推送 / 在线设备页
    // 的 5s 轮询）在调用点显式传 `force = true`。
    LaunchedEffect(lifecycleResumed, pageForeground, serviceEnabled) {
        if (lifecycleResumed && pageForeground && serviceEnabled) {
            viewModel.tools.loadTrafficLimit()
            viewModel.network.refreshWifi()
        }
    }

    // 根据时间生成问候语
    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            hour in 5..11 -> "早上好"
            hour in 12..17 -> "下午好"
            else -> "晚上好"
        }
    }

    // 使用新的左对齐 Header（无底部导航）
    // 顶栏不放手动刷新：本页已有 10s 轮询 + WebSocket 推送 + 回前台重取三条自动刷新路径，
    // 出错时错误横幅本身带「重试」入口，手动按钮属于重复能力。
    UfiScreenScaffold(
        title = greeting
    ) { padding ->
        // 原来是 `PullToRefreshBox(isRefreshing = false, onRefresh = refreshDashboard(force))`：
        // 永不显示指示器的下拉手势 —— 用户下拉时得不到任何反馈，却要和整页滚动抢纵向手势。
        // 删手势、保留刷新能力（上移到顶栏 actions）。
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 入场动画已上移到 MainNavGraph 根节点（"app-launch"），只在冷启动播一次；
            // 页面级 blurEntrance 会在切 Tab / 从二级页返回时重播，观感是抖动，故移除。
            UfiPageBackground {
                // 2026-09-05：「后端服务未连接」（UfiOfflineBanner）与「实时通道重连中」
                //（UfiRealtimeStatusBanner）两张行内横幅已并入下方连接状态卡 —— 断网时它们会
                // 同时出现、两张卡把内容压下去一百多 dp，而且和卡里那句"未连接"重复告知。
                // 错误提示也不在这里了：改由 Activity 级的全局错误浮层统一展示
                //（MainActivity 读 viewModel.globalError），页面不再各写一遍。

                // 加载骨架屏
                if (state.isLoading && state.deviceInfo == null) {
                    UfiShimmerLoading()
                    return@UfiPageBackground
                }

                // ═══════════ ① 连接状态卡（替代液动 Hero） ═══════════
                HomeConnectionCard(
                    state = state,
                    trafficLimitConfig = trafficMgmt.limitConfig,
                    wifiSsid = wifiSsid,
                    wifiBand = wifiBand,
                    wifiClientCount = wifiClientCount,
                    backendOffline = state.isOffline,
                    lastUpdatedText = state.lastUpdated?.let {
                        com.ufi_axis.util.FormatUtils.formatRelativeTime(it)
                    },
                    realtimeStatus = realtimeStatusMessage,
                    modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
                )

                // ═══════════ ② 4-in-1 指标卡 ═══════════
                HomeMetricsCard(
                    state = state,
                    modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin),
                    onMetricsClick = { section -> metricsDialog = section }
                )

                // ═══════════ ③ 设备信息列表卡 ═══════════
                HomeDeviceInfoCard(
                    state = state,
                    modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
                )

                // ═══════════ 指标快捷详情弹窗（CPU / 内存 / 电池 / 存储） ═══════════
                metricsDialog?.let { section ->
                    HomeMetricsDialog(
                        section = section,
                        state = state,
                        onDismiss = { metricsDialog = null }
                    )
                }

                Spacer(Modifier.height(Spacing.Large))
            }
        }
    }
}
