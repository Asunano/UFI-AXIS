package com.ufi_axis.app.navigation

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ufi_axis.ui.navigation.AppScreen
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.screens.*
import com.ufi_axis.ui.screens.AboutDeviceScreen
import com.ufi_axis.ui.screens.DeviceControlScreen
import com.ufi_axis.ui.screens.RuleEditScreen
import com.ufi_axis.ui.screens.TaskEditScreen
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 构建「路由字符串 → 可组合屏幕」映射，供 [com.ufi_axis.ui.navigation.MainNavGraph] 装配。
 *
 * F23：本文件从 `com.ufi_axis.ui.navigation` 迁至独立包 `com.ufi_axis.app.navigation`，
 * 避免与 :app:ui 模块同包导致的跨模块共享包所有权问题（:app:ui 不依赖这些类型，
 * 相关定义全部经参数注入）。[AppScreen] / [Routes] 类型仍定义于 :app:ui
 * （com.ufi_axis.ui.navigation），此处通过 import 引用，签名与调用方完全兼容。
 *
 * 此文件位于 :app 模块，故可访问全部 feature 屏幕（com.ufi_axis.ui.screens.*）、
 * [MainViewModel] 与 [AppPreferences]。
 *
 * map 的 key 必须使用 [Routes] 常量，与 appRoutes 中需要实际屏幕内容的 route 集合一致。
 * 注意：[Routes.MAIN] 为 Tab 宿主目的地，由内部 `UfiPageSwitcher` 渲染 5 个 Tab 页，
 * 无需在此登记条目（其内容是 switcher 本身，而非某个 [AppScreen]）。
 */
fun buildAppScreens(
    viewModel: MainViewModel,
    onServerConfigChanged: () -> Unit,
    onRepairRequested: () -> Unit = {}
): Map<String, AppScreen> =
    rawAppScreens(viewModel, onServerConfigChanged, onRepairRequested).mapValues { (route, screen) ->
        // 2026-09-03：后端服务被用户停掉时，除 [SERVICE_INDEPENDENT_ROUTES] 之外的页面
        // 一律换成「服务已停止」提示页。装在这一层而不是各屏幕内部，是因为页面不被组合
        // ⇒ 它们的轮询 LaunchedEffect 不会启动，等于顺手把 app 侧的定时请求全部静默了。
        if (route in SERVICE_INDEPENDENT_ROUTES) {
            screen
        } else {
            { entry, nc -> ServiceGate(viewModel) { screen(entry, nc) } }
        }
    }

private fun rawAppScreens(
    viewModel: MainViewModel,
    onServerConfigChanged: () -> Unit,
    onRepairRequested: () -> Unit
): Map<String, AppScreen> = mapOf(
    Routes.DASHBOARD to { _, nc -> DashboardScreen(viewModel, nc) },
    Routes.NETWORK to { _, nc -> NetworkScreen(viewModel, nc) },
    Routes.MONITOR to { _, nc -> MonitorScreen(viewModel, nc, showBack = false) },
    Routes.TOOLS to { _, nc -> ToolsScreen(viewModel, nc) },
    Routes.SETTINGS to { _, nc -> SettingsScreen(viewModel, onServerConfigChanged, nc, onRepairRequested) },

    Routes.DETAIL_SERVER_CONFIG to { _, nc -> ServerConfigScreen(viewModel, onServerConfigChanged, nc) },
    Routes.DETAIL_TOOLS_ADVANCED to { _, nc -> AdvancedConsoleScreen(viewModel, nc) },
    Routes.DETAIL_SPEED_TEST to { _, nc -> SpeedTestScreen(viewModel, nc) },
    Routes.DETAIL_TRAFFIC_MGMT to { _, nc -> TrafficManagementScreen(viewModel, nc) },
    Routes.DETAIL_SMS to { _, nc -> SmsScreen(viewModel, nc) },
    Routes.DETAIL_APPS to { _, nc ->
        val ctx = LocalContext.current
        val prefs = remember { AppPreferences(ctx) }
        AppManagerScreen(viewModel, prefs, nc)
    },
    Routes.DETAIL_TASKS to { _, nc -> TaskScreen(viewModel, nc) },
    Routes.DETAIL_EMAIL_NOTIFY to { _, nc -> EmailNotifyScreen(viewModel, nc) },
    Routes.DETAIL_FILES to { _, nc -> FileManagerScreen(viewModel, nc) },
    Routes.DETAIL_DEBUG_LOG to { _, nc -> DebugLogScreen(viewModel, nc) },
    Routes.DETAIL_DIAGNOSE to { _, nc -> DiagnoseScreen(viewModel, nc) },
    Routes.DETAIL_MONITOR to { _, nc -> MonitorScreen(viewModel, nc) },
    Routes.DETAIL_MONITOR_SETTINGS to { _, nc -> MonitorSettingsScreen(viewModel, nc) },
    // 监控设置的 5 个分组：2026-09-03 改成独立路由（与 设置→服务器→服务器配置 同构）
    Routes.DETAIL_MONITOR_COLLECTION to { _, nc -> MonitorCollectionSettingsScreen(viewModel, nc) },
    Routes.DETAIL_MONITOR_METRICS to { _, nc -> MonitorMetricsSettingsScreen(viewModel, nc) },
    Routes.DETAIL_MONITOR_CHART to { _, nc -> MonitorChartSettingsScreen(viewModel, nc) },
    Routes.DETAIL_MONITOR_BEHAVIOR to { _, nc -> MonitorBehaviorSettingsScreen(viewModel, nc) },
    Routes.DETAIL_MONITOR_SCHEDULER to { _, nc -> MonitorSchedulerSettingsScreen(viewModel, nc) },
    Routes.DETAIL_MONITOR_STORAGE to { _, nc -> MonitorStorageSettingsScreen(viewModel, nc) },
    Routes.DETAIL_EVENTS to { _, nc -> EventsScreen(viewModel, nc) },
    Routes.DETAIL_DOWNLOADS to { _, nc -> DownloadScreen(viewModel, nc) },
    Routes.DETAIL_DOWNLOAD_SETTINGS to { _, nc -> DownloadSettingsScreen(viewModel, nc) },
    // 下载设置 4 个分组的独立页面（2026-09-02 从弹窗改造）
    Routes.DETAIL_DOWNLOAD_BASIC to { _, nc -> DownloadBasicSettingsScreen(viewModel, nc) },
    Routes.DETAIL_DOWNLOAD_THROTTLE to { _, nc -> DownloadThrottleSettingsScreen(viewModel, nc) },
    Routes.DETAIL_DOWNLOAD_TRACKER to { _, nc -> DownloadTrackerSettingsScreen(viewModel, nc) },
    Routes.DETAIL_DOWNLOAD_ADVANCED to { _, nc -> DownloadAdvancedSettingsScreen(viewModel, nc) },
    Routes.DETAIL_DATA_MANAGEMENT to { _, nc -> DataManagementScreen(viewModel, nc) },
    Routes.DETAIL_CELL_LOCK to { _, nc -> CellLockScreen(viewModel, nc) },
    Routes.DETAIL_NETWORK_MODE to { _, nc -> NetworkModeScreen(viewModel, nc) },
    Routes.DETAIL_BAND_LOCK to { _, nc -> BandLockScreen(viewModel, nc) },
    Routes.DETAIL_CELLULAR_ADVANCED to { _, nc -> CellularAdvancedScreen(viewModel, nc) },
    Routes.DETAIL_NETWORK_FEATURES to { _, nc -> NetworkFeaturesScreen(viewModel, nc) },
    Routes.DETAIL_ONLINE_DEVICES to { _, nc -> OnlineDevicesScreen(viewModel, nc) },
    Routes.DETAIL_PAIRING to { _, nc -> PairingConfigScreen(viewModel, nc) },
    Routes.DETAIL_APPEARANCE to { _, nc -> AppearanceSettingsScreen(viewModel, nc) },
    // 组件画廊：纯 UI 预览，不需要 viewModel（组件本身在 :app:ui，画廊也放在那）
    Routes.DETAIL_UI_GALLERY to { _, nc -> UfiGalleryScreen(nc) },
    Routes.DETAIL_DEVICE_CONTROL to { _, nc -> DeviceControlScreen(viewModel, nc) },
    Routes.DETAIL_ABOUT to { _, nc -> AboutDeviceScreen(viewModel, nc) },
    Routes.DETAIL_ALERT_SETTINGS to { _, nc -> AlertSettingsScreen(viewModel, nc) },
    Routes.DETAIL_DAILY_NOTIFY to { _, nc -> DailyNotifyScreen(viewModel, nc) },
    Routes.DETAIL_BACKGROUND_GUARD to { _, nc -> BackgroundGuardScreen(viewModel, nc) },
    Routes.DETAIL_TUNNEL to { _, nc -> TunnelScreen(viewModel, nc) },
    // FIX-9：内网穿透三拆屏
    Routes.DETAIL_TUNNEL_FRP to { _, nc -> FrpDetailScreen(viewModel, nc) },
    // FRP 单隧道配置页：name 由 Routes.tunnelFrpChannel(Uri.encode) 编码，Navigation 已解码，这里不再解码
    Routes.DETAIL_TUNNEL_FRP_CHANNEL to { entry, nc ->
        FrpChannelScreen(viewModel, nc, entry.arguments?.getString("name") ?: "")
    },
    Routes.DETAIL_TUNNEL_CF to { _, nc -> CfDetailScreen(viewModel, nc) },
    // CF 单隧道管理页：name 已由 Navigation 解码一次，这里不再解码
    Routes.DETAIL_TUNNEL_CF_TUNNEL to { entry, nc ->
        CfTunnelScreen(viewModel, nc, entry.arguments?.getString("name") ?: "")
    },
    Routes.DETAIL_TUNNEL_SETTINGS to { _, nc -> TunnelSettingsScreen(viewModel, nc) },
    Routes.DETAIL_SERVER to { _, nc -> ServerScreen(viewModel, onServerConfigChanged, nc) },
    Routes.DETAIL_NOTIFICATIONS_GUARD to { _, nc -> NotificationsGuardScreen(viewModel, nc) },

    // 条件规则编辑器：id 空=新建，非空=编辑；编辑器从 viewModel.tasksState 读规则数据
    Routes.DETAIL_RULE_EDIT to { entry, nc ->
        val id = entry.arguments?.getString("id").orEmpty()
        RuleEditScreen(viewModel = viewModel, navController = nc, ruleId = id)
    },
    // 定时任务编辑器：id 空=新建，非空=编辑；编辑器从 viewModel.tasksState 读任务数据
    Routes.DETAIL_TASK_EDIT to { entry, nc ->
        val id = entry.arguments?.getString("id").orEmpty()
        TaskEditScreen(viewModel = viewModel, navController = nc, taskId = id)
    },

    Routes.FILE_EDITOR to { entry, nc ->
        val path = entry.arguments?.getString("path") ?: ""
        TextEditorScreen(viewModel, nc, java.net.URLDecoder.decode(path, "UTF-8"))
    },
    Routes.FILE_MEDIA to { entry, nc ->
        val path = entry.arguments?.getString("path") ?: ""
        val type = entry.arguments?.getString("type") ?: "video"
        MediaScreen(viewModel, nc, java.net.URLDecoder.decode(path, "UTF-8"), type)
    },
    Routes.FILE_IMAGE to { entry, nc ->
        val path = entry.arguments?.getString("path") ?: ""
        ImageViewerScreen(viewModel, nc, java.net.URLDecoder.decode(path, "UTF-8"))
    }
)
