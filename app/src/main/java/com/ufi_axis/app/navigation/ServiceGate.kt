package com.ufi_axis.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ufi_axis.ui.components.common.UfiServiceStoppedNotice
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 「后端服务已停止」页面闸门（2026-09-03）。
 *
 * 用户点了设置页的「停止服务」后，core 侧除 HTTP 之外的自主活动全停（采集/告警/定时任务/
 * 短信转发/下载轮询/隧道看护），依赖设备实时数据的页面此时既没有新数据可显示，也不该继续
 * 每 2~10 秒发一轮请求。做法是**不组合**这些页面、直接换成 [UfiServiceStoppedNotice]：
 * 页面不进组合树，它们各自 `LaunchedEffect` 里的轮询协程就不会启动 —— 这比给十几个界面
 * 逐个加 `serviceEnabled` 门控可靠得多，也不会漏掉后来新增的页面。
 *
 * 例外名单见 [SERVICE_INDEPENDENT_ROUTES]：设置类页面必须始终可用，否则用户没地方把服务开回来。
 */
@Composable
internal fun ServiceGate(
    viewModel: MainViewModel,
    content: @Composable () -> Unit
) {
    val state by viewModel.network.serviceState.collectAsState()
    // 首次进入时补一次状态读取：状态可能是别的端（web 面板）改的，app 这边还不知道。
    LaunchedEffect(Unit) {
        if (!state.loaded) viewModel.network.loadServiceStatus()
    }
    // loaded=false（还没读到 / 读失败）时按"运行中"处理：网络抖一下就把整个 app 换成停止页太蠢。
    // restarting 期间也不换：那是「重启服务」的 10 秒窗口，不是用户主动停服。
    val stopped = state.loaded && !state.enabled && !state.restarting
    if (stopped) {
        UfiServiceStoppedNotice(
            onEnable = { viewModel.network.setBackgroundService(true) },
            onRefresh = { viewModel.network.loadServiceStatus() },
            busy = state.isBusy,
            errorMessage = state.errorMessage
        )
    } else {
        content()
    }
}

/**
 * 服务停止时**仍然完整可用**的路由白名单。
 *
 * 两类：
 * 1. 设置入口与其配置子页 —— 用户得能在这里把服务开回来，且改配置写的是 core 的持久化设置，
 *    与后台采集是否在跑无关；
 * 2. 排障页（调试日志 / 网络诊断 / 服务器地址 / 配对）—— 服务停了正是要看这些的时候。
 *
 * 不在名单里的页面（仪表盘/网络/监控/工具/短信/下载/事件/隧道/文件…）一律换成停止提示页。
 */
internal val SERVICE_INDEPENDENT_ROUTES: Set<String> = setOf(
    Routes.SETTINGS,
    Routes.DETAIL_SERVER_CONFIG,
    Routes.DETAIL_SERVER,
    Routes.DETAIL_PAIRING,
    Routes.DETAIL_APPEARANCE,
    Routes.DETAIL_DEBUG_LOG,
    Routes.DETAIL_DIAGNOSE,
    Routes.DETAIL_ABOUT,
    Routes.DETAIL_DATA_MANAGEMENT,
    Routes.DETAIL_MONITOR_SETTINGS,
    Routes.DETAIL_MONITOR_COLLECTION,
    Routes.DETAIL_MONITOR_CHART,
    Routes.DETAIL_MONITOR_SCHEDULER,
    Routes.DETAIL_MONITOR_STORAGE,
    Routes.DETAIL_ALERT_SETTINGS,
    Routes.DETAIL_EMAIL_NOTIFY,
    Routes.DETAIL_DAILY_NOTIFY,
    Routes.DETAIL_BACKGROUND_GUARD,
    Routes.DETAIL_NOTIFICATIONS_GUARD,
    // 通知管理：分类开关、免打扰、保留上限写的都是本机 prefs（core 只是回显），
    // core 挂着时也必须能改 —— 通知能不能弹是本机的事。
    Routes.DETAIL_NOTIFY_MANAGE,
    // 系统通知记录读本机 Room，与 core 服务无关；被 ServiceGate 换掉会让
    // "core 挂了想查本机记录"直接没门。
    Routes.DETAIL_NOTIFY_HISTORY,
    // 投递记录在设备端：读不到就画空态，比换成「服务已停止」更贴近用户此刻想知道的事。
    Routes.DETAIL_DELIVERY_HISTORY,
    Routes.DETAIL_DOWNLOAD_SETTINGS,
    Routes.DETAIL_DOWNLOAD_BASIC,
    Routes.DETAIL_DOWNLOAD_THROTTLE,
    Routes.DETAIL_DOWNLOAD_TRACKER,
    Routes.DETAIL_DOWNLOAD_ADVANCED,
    Routes.DETAIL_TUNNEL_SETTINGS
)
