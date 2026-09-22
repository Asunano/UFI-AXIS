package com.ufi_axis.app.navigation

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ufi_axis.ui.media.MediaAudioGroupScreen
import com.ufi_axis.ui.media.MediaAudioPlayerScreen
import com.ufi_axis.ui.media.MediaAudioScreen
import com.ufi_axis.ui.media.MediaAudioSettingsScreen
import com.ufi_axis.ui.media.MediaImageScreen
import com.ufi_axis.ui.media.MediaImageSettingsScreen
import com.ufi_axis.ui.media.MediaImageViewerScreen
import com.ufi_axis.ui.media.MediaPlaylistScreen
import com.ufi_axis.ui.media.MediaVideoDownloadHistoryScreen
import com.ufi_axis.ui.media.MediaVideoPlayerScreen
import com.ufi_axis.ui.media.MediaVideoScreen
import com.ufi_axis.ui.media.MediaVideoSettingsScreen
import com.ufi_axis.ui.navigation.AppScreen
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.screens.*
import com.ufi_axis.ui.screens.AboutDeviceScreen
import com.ufi_axis.ui.screens.DeviceControlScreen
import com.ufi_axis.ui.screens.RuleEditScreen
import com.ufi_axis.ui.screens.TaskEditScreen
import com.ufi_axis.ui.screens.filemanager.RemotePushScreen
import com.ufi_axis.ui.screens.filemanager.StorageSourceEditScreen
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
    // 进阶工具页只是一页入口网格，不碰 viewModel
    Routes.DETAIL_TOOLS_EXTRA to { _, nc -> ToolsExtraScreen(nc) },
    Routes.DETAIL_SPEED_TEST to { _, nc -> SpeedTestScreen(viewModel, nc) },
    Routes.DETAIL_TRAFFIC_MGMT to { _, nc -> TrafficManagementScreen(viewModel, nc) },
    Routes.DETAIL_SMS to { _, nc -> SmsScreen(viewModel, nc) },
    Routes.DETAIL_SMS_SETTINGS to { _, nc -> SmsSettingsScreen(viewModel, nc) },
    // 拦截规则 / 已拦截：2026-09-08 从原来的双 Tab 页拆成两条无参路由，各自一个入口。
    Routes.DETAIL_SMS_FILTER_RULES to { _, nc -> SmsFilterRulesScreen(viewModel, nc) },
    Routes.DETAIL_SMS_BLOCKED to { _, nc -> SmsBlockedScreen(viewModel, nc) },

    Routes.DETAIL_APPS to { _, nc ->
        val ctx = LocalContext.current
        val prefs = remember { AppPreferences(ctx) }
        AppManagerScreen(viewModel, prefs, nc)
    },
    Routes.DETAIL_TASKS to { _, nc -> TaskScreen(viewModel, nc) },
    Routes.DETAIL_EMAIL_NOTIFY to { _, nc -> EmailNotifyScreen(viewModel, nc) },
    // 推送渠道总览 → Webhook 配置（2026-09-09 新增）。
    // 渠道页里的「邮件」行指向上面同一个 EmailNotifyScreen，不做第二份。
    Routes.DETAIL_PUSH_CHANNELS to { _, nc -> PushChannelsScreen(viewModel, nc) },
    Routes.DETAIL_WEBHOOK_NOTIFY to { _, nc -> WebhookNotifyScreen(viewModel, nc) },
    // Webhook 首次配置向导：只从 WebhookNotifyScreen 的「还没配好」入口进（configured=false 时才有）
    Routes.DETAIL_WEBHOOK_SETUP to { _, nc -> WebhookSetupWizardScreen(viewModel, nc) },
    // 本机短信回发（2026-09-09 阶段 3）：唯一走信令网的渠道，按条计费。
    Routes.DETAIL_LOCAL_SMS_NOTIFY to { _, nc -> LocalSmsNotifyScreen(viewModel, nc) },
    Routes.DETAIL_FILES to { _, nc -> FileManagerScreen(viewModel, nc) },
    // 推送任务页：外部存储上传的第二阶段（core 暂存 → 远端）。
    // 入口在各外部存储源的工具栏右上角，`source` 带上该源的 id 以便只看这一个源的作业。
    Routes.DETAIL_REMOTE_PUSH to { entry, nc ->
        RemotePushScreen(
            viewModel = viewModel,
            navController = nc,
            sourceId = entry.arguments?.getString("source")?.takeIf { it.isNotBlank() }
        )
    },
    // 存储源编辑器：id 空=新增，非空=编辑；表单分 4 步走公共向导 UfiWizard。
    // 入口在文件管理器的**存储列表**（长按一行→编辑 / 末尾行→添加）——
    // 2026-09-21 起不再有独立的「外部存储」列表页，它与存储列表完全重复。
    Routes.DETAIL_STORAGE_SOURCE_EDIT to { entry, nc ->
        val id = entry.arguments?.getString("id").orEmpty()
        StorageSourceEditScreen(viewModel = viewModel, navController = nc, sourceId = id)
    },

    Routes.DETAIL_DEBUG_LOG to { _, nc -> DebugLogScreen(viewModel, nc) },
    Routes.DETAIL_DIAGNOSE to { _, nc -> DiagnoseScreen(viewModel, nc) },
    Routes.DETAIL_MONITOR to { _, nc -> MonitorScreen(viewModel, nc) },
    Routes.DETAIL_MONITOR_SETTINGS to { _, nc -> MonitorSettingsScreen(viewModel, nc) },
    // 监控设置的分组：2026-09-03 改成独立路由（与 设置→服务器→服务器配置 同构）
    // 2026-09-08 少了 metrics / behavior 两条（分别并入 collection 的弹窗与 storage 的导出区）
    Routes.DETAIL_MONITOR_COLLECTION to { _, nc -> MonitorCollectionSettingsScreen(viewModel, nc) },
    Routes.DETAIL_MONITOR_CHART to { _, nc -> MonitorChartSettingsScreen(viewModel, nc) },
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
    // 界面小功能（2026-09-17）：2026-09-18 起只是入口页，天气与诗词各自一页
    Routes.DETAIL_UI_EXTRAS to { _, nc -> UiExtrasSettingsScreen(viewModel, nc) },
    Routes.DETAIL_WEATHER to { _, nc -> WeatherSettingsScreen(viewModel, nc) },
    Routes.DETAIL_POETRY to { _, nc -> PoetrySettingsScreen(viewModel, nc) },
    // 组件画廊：纯 UI 预览，不需要 viewModel（组件本身在 :app:ui，画廊也放在那）
    Routes.DETAIL_UI_GALLERY to { _, nc -> UfiGalleryScreen(nc) },
    Routes.DETAIL_DEVICE_CONTROL to { _, nc -> DeviceControlScreen(viewModel, nc) },
    Routes.DETAIL_ABOUT to { _, nc -> AboutDeviceScreen(viewModel, nc) },
    Routes.DETAIL_ALERT_SETTINGS to { _, nc -> AlertSettingsScreen(viewModel, nc) },
    Routes.DETAIL_DAILY_NOTIFY to { _, nc -> DailyNotifyScreen(viewModel, nc) },
    // 通知管理：分类开关 / 免打扰 / 测试 / 设备告警 / 系统通知记录入口
    Routes.DETAIL_NOTIFY_MANAGE to { _, nc -> NotifyManageScreen(viewModel, nc) },
    // 两份记录各自独立成页（2026-09-08 从双 Tab 拆开）：
    // 系统通知记录的入口在通知管理页，投递记录的入口在各条渠道自己的配置页
    Routes.DETAIL_NOTIFY_HISTORY to { _, nc -> SystemNotifyHistoryScreen(viewModel, nc) },
    // 投递记录：三条渠道共用一个 composable，channel 由路由参数带进来（缺省 mail）
    Routes.DETAIL_DELIVERY_HISTORY to { entry, nc ->
        DeliveryHistoryScreen(viewModel, nc, entry.arguments?.getString("channel") ?: "mail")
    },
    Routes.DETAIL_BACKGROUND_GUARD to { _, nc -> BackgroundGuardScreen(viewModel, nc) },
    Routes.DETAIL_BACKUP_RESTORE to { _, nc -> BackupRestoreScreen(viewModel, nc) },
    Routes.DETAIL_TUNNEL to { _, nc -> TunnelScreen(viewModel, nc) },
    // FIX-9：内网穿透三拆屏
    Routes.DETAIL_TUNNEL_FRP to { _, nc -> FrpDetailScreen(viewModel, nc) },
    // FRP 新建隧道向导：8 个字段结构化收齐后生成 TOML（原来只问名字、写一份空值模板）
    Routes.DETAIL_TUNNEL_FRP_NEW to { _, nc -> FrpNewChannelScreen(viewModel, nc) },
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
    Routes.DETAIL_SERVER to { _, nc ->
        ServerScreen(viewModel, onServerConfigChanged, nc, onRepairRequested)
    },
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

    // 媒体库：工具页三个入口（视频 / 音乐 / 图片）+ 各自的播放 / 查看页（2026-09-16）
    Routes.MEDIA_LIBRARY_VIDEO to { _, nc ->
        MediaVideoScreen(viewModel, nc)
    },
    Routes.MEDIA_LIBRARY_AUDIO to { _, nc ->
        MediaAudioScreen(viewModel, nc)
    },
    Routes.MEDIA_LIBRARY_IMAGE to { _, nc ->
        MediaImageScreen(viewModel, nc)
    },
    Routes.MEDIA_VIDEO_SETTINGS to { _, nc ->
        MediaVideoSettingsScreen(viewModel, nc)
    },
    // 下载任务（队列 + 历史）：数据全在 MediaDownloadQueue 这个进程内单例里，不经 viewModel
    Routes.MEDIA_VIDEO_DOWNLOADS to { _, nc ->
        MediaVideoDownloadHistoryScreen(nc)
    },
    Routes.MEDIA_AUDIO_SETTINGS to { _, nc ->
        MediaAudioSettingsScreen(viewModel, nc)
    },
    Routes.MEDIA_IMAGE_SETTINGS to { _, nc ->
        MediaImageSettingsScreen(viewModel, nc)
    },
    // 分组详情：by/key 由 mediaAudioGroupRouteOf(encodeUriComponent) 编码。
    //
    // **这里不要再解码**（2026-09-21 修"专辑里没有歌"）：Navigation 取 query 参数时已经做过
    // 一次 `Uri.decode`。原来这里又叠了一次 `URLDecoder.decode`，而后者把 `+` 当空格 ——
    // 于是专辑名 `万岁2001 新曲+精选` 里那个真的 `+` 被吃成空格，拿它去查曲目一条都查不到。
    // 编码侧改用 encodeUriComponent（空格也编成 %20，结果里不留裸 `+`）之后，一次解码就够。
    Routes.MEDIA_AUDIO_GROUP to { entry, nc ->
        val by = entry.arguments?.getString("by") ?: ""
        val key = entry.arguments?.getString("key") ?: ""
        MediaAudioGroupScreen(viewModel, nc, by, key)
    },
    // 歌单详情：id 是 core 生成的短 id。同样只靠 Navigation 那一次解码
    Routes.MEDIA_AUDIO_PLAYLIST to { entry, nc ->
        MediaPlaylistScreen(viewModel, nc, entry.arguments?.getString("id") ?: "")
    },
    Routes.MEDIA_VIDEO to { entry, nc ->
        MediaVideoPlayerScreen(viewModel, nc, entry.arguments?.getString("path") ?: "")
    },
    Routes.MEDIA_AUDIO to { entry, nc ->
        val path = entry.arguments?.getString("path") ?: ""
        /*
         * 播放范围（2026-09-21）。**空串 = 没指定范围**（不是整库）——
         * 迷你条 / 标题栏挂件 / 通知栏进来时就是空的，播放页收到空值不会去重建队列。
         * 「全部」页点歌会显式带 `scope=all`。语义见 AudioQueueScope.of。
         */
        MediaAudioPlayerScreen(
            viewModel, nc, path,
            scopeKind = entry.arguments?.getString("scope") ?: "",
            scopeKey = entry.arguments?.getString("scopeKey") ?: ""
        )
    },
    Routes.MEDIA_IMAGE to { entry, nc ->
        MediaImageViewerScreen(viewModel, nc, entry.arguments?.getString("path") ?: "")
    }
)
