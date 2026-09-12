package com.ufi_axis.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument

/**
 * 通知深链接待消费手机号：由 MainActivity 写入，SmsScreen 消费后清空。
 * 通过 CompositionLocal 下发，避免 feature 模块直接依赖 :app 模块。
 */
val LocalPendingSmsPhone = staticCompositionLocalOf<MutableState<String?>> {
    error("LocalPendingSmsPhone not provided")
}

/**
 * 单个可组合屏幕的签名：
 * 接收回退栈条目 [NavBackStackEntry] 与导航控制器 [NavHostController]，无返回值。
 */
typealias AppScreen = @Composable (entry: NavBackStackEntry, navController: NavHostController) -> Unit

/**
 * 页面转场类型。
 */
enum class TransitionType {
    /**
     * 底部 Tab 页：交叉淡入 + 轻微缩放。
     *
     * 保留以兼容既有引用。方案 A′ 迁移后 5 个 Tab 已收敛进 [Routes.MAIN] 宿主目的地，
     * 路由表中不再有 TAB 项（改用 [HOST]）。
     */
    TAB,

    /**
     * Tab 宿主目的地：[Routes.MAIN] 及 5 个「兼容重定向壳」。
     *
     * 宿主内部由 `UfiPageSwitcher` 承担页间动画，NavHost 层只做整体进出场。
     */
    HOST,

    /** 详情页：水平滑动。 */
    DETAIL
}

/**
 * 路由描述。
 *
 * @param route 路由字符串（与旧 MainNavGraph 的 composable route 完全一致）
 * @param transition 转场类型 [TransitionType]
 * @param arguments 导航参数列表，默认空
 */
data class AppRoute(
    val route: String,
    val transition: TransitionType,
    val arguments: List<NamedNavArgument> = emptyList()
)

/**
 * 全量路由常量。值必须与 [appRoutes] 及旧 MainNavGraph 的 route 完全一致。
 */
object Routes {
    /**
     * Tab 宿主目的地（方案 A′）。
     *
     * 5 个底部 Tab 不再是 5 个独立 NavHost 目的地，而是本目的地内 `UfiPageSwitcher` 的 5 页。
     * 本路由是 NavHost 的 startDestination。
     */
    const val MAIN = "main"

    const val DASHBOARD = "dashboard"
    const val NETWORK = "network"
    const val MONITOR = "monitor"
    const val TOOLS = "tools"
    const val SETTINGS = "settings"

    const val DETAIL_SERVER_CONFIG = "detail/server-config"
    const val DETAIL_TOOLS_ADVANCED = "detail/tools-advanced"
    const val DETAIL_SPEED_TEST = "detail/speed-test"
    const val DETAIL_TRAFFIC_MGMT = "detail/traffic-management"
    const val DETAIL_SMS = "detail/sms"
    /** 短信设置页（2026-09-08 由短信页顶栏齿轮的弹窗改造成独立页面）。 */
    const val DETAIL_SMS_SETTINGS = "detail/sms-settings"
    /**
     * 拦截规则页（号码黑名单与关键词的增删改查）。
     *
     * 2026-09-08：原先与拦截记录合成一个 `detail/sms-filter?tab={tab}` 双 Tab 页，
     * 现已拆成两条无参路由 —— 那两块内容各有各的顶栏动作、角标与列表状态，
     * 合在一页里读代码和用界面都要先问"我现在在哪一半"。
     */
    const val DETAIL_SMS_FILTER_RULES = "detail/sms-filter-rules"
    /** 已拦截页（core 记下的拦截结果，只读 + 自查动作）。拆分理由同 [DETAIL_SMS_FILTER_RULES]。 */
    const val DETAIL_SMS_BLOCKED = "detail/sms-blocked"

    const val DETAIL_APPS = "detail/apps"
    const val DETAIL_TASKS = "detail/tasks"
    /**
     * 邮件通知配置页（2026-08-29 由「短信转发」改造）。
     * 路由名跟着功能改了，但后端接口仍是 `/api/sms-forward/…` —— web 端与 API 手册都引用该路径。
     */
    const val DETAIL_EMAIL_NOTIFY = "detail/email-notify"
    const val DETAIL_DAILY_NOTIFY = "detail/daily-notify"

    /**
     * 推送渠道总览（2026-09-09）：设备把通知投到哪里去，一页列全。
     *
     * 为什么要有这一页而不是把 Webhook 直接挂进「通知与守护」：
     * 那一页管的是「要不要提醒你」（总闸 / 分类 / 免打扰 / 守护），渠道管的是「往哪儿送」，
     * 两件事。而且渠道会继续加（邮件 → Webhook → 本机短信 → …），每加一个就往那页塞一张卡的话，
     * 那页会变成配置大杂烩。
     *
     * 本页只列**可配置**的渠道（邮件 / Webhook / 本机短信）。WS 实时推送不在这里 ——
     * 它随 core 常开、没有任何可配项，摆一行只读说明只会让用户以为自己漏设了什么。
     */
    const val DETAIL_PUSH_CHANNELS = "detail/push-channels"

    /** 通用 Webhook 渠道配置（`/api/notify/webhook/…`）。入口在 [DETAIL_PUSH_CHANNELS]。 */
    const val DETAIL_WEBHOOK_NOTIFY = "detail/webhook-notify"

    /**
     * 本机短信回发渠道配置（`/api/notify/sms/…`）。入口在 [DETAIL_PUSH_CHANNELS]。
     *
     * 唯一走**信令网**的渠道：邮件与 Webhook 都靠数据网，而"数据断了 / 套餐用尽 / 自动关网"
     * 恰恰是它们发不出去的时候。代价是**按条计费**，所以那一页的每个开关都带费用提示。
     */
    const val DETAIL_LOCAL_SMS_NOTIFY = "detail/local-sms-notify"

    /**
     * 通知管理（2026-09-08）：通知类开关 + 告警类设置 + 系统通知记录，全部收在这一页。
     *
     * 拆页的直接原因：上一版把「全局通知」（客户端投递总闸）与「告警总开关」
     * （设备端告警引擎）摆在同一屏，两个都长得像总开关，分不清谁管谁。
     * 现在「通知与守护」只剩 Hero（全局通知总闸）+ 三个入口：本页 / 邮件通知 / 后台守护。
     */
    const val DETAIL_NOTIFY_MANAGE = "detail/notify-manage"

    /** 系统通知记录（本机 Room）：每条状态栏通知的结果，含被拦下的原因。入口在通知管理页。 */
    const val DETAIL_NOTIFY_HISTORY = "detail/notify-history"

    /**
     * 投递记录（设备端 `mail_send_records`）：三条渠道共用同一个页面，`channel` 决定看哪一份。
     *
     * 用一条带参路由而不是给三条渠道各复制一个 composable：列表形态、筛选、翻页完全一样，
     * 复制三份的唯一产物是三处会各自跑偏的文案。入口在各渠道自己的配置页里。
     */
    const val DETAIL_DELIVERY_HISTORY = "detail/delivery-history?channel={channel}"

    const val DETAIL_FILES = "detail/files"
    const val DETAIL_DEBUG_LOG = "detail/debug-log"
    const val DETAIL_DIAGNOSE = "detail/diagnose"
    const val DETAIL_MONITOR = "detail/monitor"
    const val DETAIL_MONITOR_SETTINGS = "detail/monitor-settings"
    // 监控设置的分组：2026-09-03 从"同一页内的局部状态"改成独立页面，
    // 与「设置 → 服务器 → 服务器配置」同构（每一级都是路由，转场/返回/系统返回键全部由导航接管）
    // 2026-09-08 删掉两条：metrics（8 个指标开关并入 collection 的弹窗）、
    // behavior（唯一的导出 ZIP 开关并入 storage）。
    const val DETAIL_MONITOR_COLLECTION = "detail/monitor-settings/collection"
    const val DETAIL_MONITOR_CHART = "detail/monitor-settings/chart"
    // 采集调度：core 侧 DataScheduler / 告警扫描 / 温控的调参入口（保留天数、各类间隔、温控档位）
    const val DETAIL_MONITOR_SCHEDULER = "detail/monitor-settings/scheduler"
    const val DETAIL_MONITOR_STORAGE = "detail/monitor-settings/storage"
    const val DETAIL_EVENTS = "detail/events"
    const val DETAIL_DOWNLOADS = "detail/downloads"
    const val DETAIL_DOWNLOAD_SETTINGS = "detail/download-settings"
    // 下载设置的 4 个分组：2026-09-02 从弹窗改成独立页面
    const val DETAIL_DOWNLOAD_BASIC = "detail/download-settings/basic"
    const val DETAIL_DOWNLOAD_THROTTLE = "detail/download-settings/throttle"
    const val DETAIL_DOWNLOAD_TRACKER = "detail/download-settings/tracker"
    const val DETAIL_DOWNLOAD_ADVANCED = "detail/download-settings/advanced"
    const val DETAIL_DATA_MANAGEMENT = "detail/data-management"
    const val DETAIL_CELL_LOCK = "detail/cell-lock"
    const val DETAIL_NETWORK_MODE = "detail/network-mode"
    const val DETAIL_BAND_LOCK = "detail/band-lock"
    const val DETAIL_CELLULAR_ADVANCED = "detail/cellular-advanced"
    const val DETAIL_NETWORK_FEATURES = "detail/network-features"
    /** 在线设备列表（2026-08-30 从网络页尾的卡片拆出，避免长列表被底部胶囊导航栏遮挡）。 */
    const val DETAIL_ONLINE_DEVICES = "detail/online-devices"
    const val DETAIL_PAIRING = "detail/pairing"
    const val DETAIL_APPEARANCE = "detail/appearance"
    /** 组件画廊：共享组件按族铺开，用于发现重复与不一致（入口在「外观」页）。 */
    const val DETAIL_UI_GALLERY = "detail/ui-gallery"
    const val DETAIL_DEVICE_CONTROL = "detail/device-control"
    const val DETAIL_ABOUT = "detail/about"
    const val DETAIL_ALERT_SETTINGS = "detail/alert-settings"
    const val DETAIL_BACKGROUND_GUARD = "detail/background-guard"
    /** 配置备份与恢复（2026-09-11）：core + 本机偏好合体的 `.ufibak` 包导出 / 导入。 */
    const val DETAIL_BACKUP_RESTORE = "detail/backup-restore"
    const val DETAIL_TUNNEL = "detail/tunnel"
    // FIX-9（2026-08-23）：内网穿透拆 3 屏：主页（入口列表）+ FRP 详情 + CF Tunnel 详情 + 通用设置
    const val DETAIL_TUNNEL_FRP = "detail/tunnel/frp"
    // 2026-08-26：FRP 拆为「隧道列表」+「单隧道配置」，name 为隧道名（可含中文，导航时 URL 编码）
    const val DETAIL_TUNNEL_FRP_CHANNEL = "detail/tunnel/frp/channel?name={name}"
    const val DETAIL_TUNNEL_CF = "detail/tunnel/cf"
    // 2026-08-26：CF 也拆为「隧道列表」+「单隧道管理」
    const val DETAIL_TUNNEL_CF_TUNNEL = "detail/tunnel/cf/tunnel?name={name}"
    const val DETAIL_TUNNEL_SETTINGS = "detail/tunnel/settings"

    /**
     * 构造 FRP 单隧道配置页路由（隧道名可能含中文/空格）。
     * 用 `Uri.encode`（不是 `URLEncoder`：后者把空格编成 `+`，而 Navigation 内部只做 `Uri.decode`，
     * 于是 `+` 会被当字面量或被二次解码成空格 → 名字对不上）。取参处**不要再解码一次**。
     */
    fun tunnelFrpChannel(name: String): String =
        "detail/tunnel/frp/channel?name=" + android.net.Uri.encode(name)

    /** 构造 CF 单隧道管理页路由（隧道名含中文/空格，编解码规则同上） */
    fun tunnelCfTunnel(name: String): String =
        "detail/tunnel/cf/tunnel?name=" + android.net.Uri.encode(name)

    /**
     * 构造投递记录页路由。
     *
     * 渠道 id 是 core 定义的 ASCII 常量（`mail` / `webhook` / `local_sms`），不需要 URL 编码；
     * 留这个构造函数是为了让三个入口拼路由的方式只有一种写法。
     */
    fun deliveryHistory(channel: String): String = "detail/delivery-history?channel=$channel"



    // 设置页 9 入口精简为 4：新增的两个合并二级页
    const val DETAIL_SERVER = "detail/server"
    const val DETAIL_NOTIFICATIONS_GUARD = "detail/notifications-guard"

    // 条件规则编辑器：query 参数 `id` 为空时=新建，非空时=编辑对应规则

    const val DETAIL_RULE_EDIT = "detail/rule-edit?id={id}"

    // 定时任务编辑器：query 参数 `id` 为空时=新建，非空时=编辑对应任务
    const val DETAIL_TASK_EDIT = "detail/task-edit?id={id}"

    const val FILE_EDITOR = "file/editor?path={path}"
    const val FILE_MEDIA = "file/media?path={path}&type={type}"
    const val FILE_IMAGE = "file/image?path={path}"
}

/**
 * 应用路由表：全部为 DETAIL 转场（无底部 Tab）。
 * file/ 三个路由携带 navArgument。
 */
val appRoutes: List<AppRoute> = listOf(
    // ── Tab 宿主：NavHost 的 startDestination，内含 UfiPageSwitcher 的 5 页
    AppRoute(Routes.MAIN, TransitionType.HOST),

    // ── 5 个旧 Tab 路由：保留以兼容既有 navigate("settings") 等调用点，
    //    现降级为「重定向壳」——进入后立即切到对应 Tab 并 popBackStack。
    AppRoute(Routes.DASHBOARD, TransitionType.HOST),
    AppRoute(Routes.NETWORK, TransitionType.HOST),
    AppRoute(Routes.MONITOR, TransitionType.HOST),
    AppRoute(Routes.TOOLS, TransitionType.HOST),
    AppRoute(Routes.SETTINGS, TransitionType.HOST),

    AppRoute(Routes.DETAIL_SERVER_CONFIG, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_TOOLS_ADVANCED, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_SPEED_TEST, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_TRAFFIC_MGMT, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_SMS, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_SMS_SETTINGS, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_SMS_FILTER_RULES, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_SMS_BLOCKED, TransitionType.DETAIL),

    AppRoute(Routes.DETAIL_APPS, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_TASKS, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_EMAIL_NOTIFY, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_PUSH_CHANNELS, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_WEBHOOK_NOTIFY, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_LOCAL_SMS_NOTIFY, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_DAILY_NOTIFY, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_NOTIFY_MANAGE, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_NOTIFY_HISTORY, TransitionType.DETAIL),
    // 投递记录：channel 缺省成邮件 —— 老的深链接（不带参数）仍能落到一个有意义的页面，
    // 而不是标题空白、列表混着三条渠道。
    AppRoute(
        route = Routes.DETAIL_DELIVERY_HISTORY,
        transition = TransitionType.DETAIL,
        arguments = listOf(
            navArgument("channel") { type = NavType.StringType; defaultValue = "mail" }
        )
    ),
    AppRoute(Routes.DETAIL_FILES, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_DEBUG_LOG, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_DIAGNOSE, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_MONITOR, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_DOWNLOADS, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_DOWNLOAD_SETTINGS, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_DOWNLOAD_BASIC, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_DOWNLOAD_THROTTLE, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_DOWNLOAD_TRACKER, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_DOWNLOAD_ADVANCED, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_DATA_MANAGEMENT, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_CELL_LOCK, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_NETWORK_MODE, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_BAND_LOCK, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_CELLULAR_ADVANCED, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_NETWORK_FEATURES, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_ONLINE_DEVICES, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_PAIRING, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_APPEARANCE, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_UI_GALLERY, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_DEVICE_CONTROL, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_ABOUT, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_ALERT_SETTINGS, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_BACKGROUND_GUARD, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_BACKUP_RESTORE, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_TUNNEL, TransitionType.DETAIL),
    // FIX-9：内网穿透三拆路由
    AppRoute(Routes.DETAIL_TUNNEL_FRP, TransitionType.DETAIL),
    AppRoute(
        route = Routes.DETAIL_TUNNEL_FRP_CHANNEL,
        transition = TransitionType.DETAIL,
        arguments = listOf(
            navArgument("name") { type = NavType.StringType; defaultValue = "" }
        )
    ),
    AppRoute(Routes.DETAIL_TUNNEL_CF, TransitionType.DETAIL),
    AppRoute(
        route = Routes.DETAIL_TUNNEL_CF_TUNNEL,
        transition = TransitionType.DETAIL,
        arguments = listOf(
            navArgument("name") { type = NavType.StringType; defaultValue = "" }
        )
    ),
    AppRoute(Routes.DETAIL_TUNNEL_SETTINGS, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_SERVER, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_NOTIFICATIONS_GUARD, TransitionType.DETAIL),

    AppRoute(
        route = Routes.DETAIL_RULE_EDIT,
        transition = TransitionType.DETAIL,
        arguments = listOf(
            navArgument("id") { type = NavType.StringType; defaultValue = ""; nullable = true }
        )
    ),
    AppRoute(
        route = Routes.DETAIL_TASK_EDIT,
        transition = TransitionType.DETAIL,
        arguments = listOf(
            navArgument("id") { type = NavType.StringType; defaultValue = ""; nullable = true }
        )
    ),

    AppRoute(
        route = Routes.FILE_EDITOR,
        transition = TransitionType.DETAIL,
        arguments = listOf(
            navArgument("path") { type = NavType.StringType; defaultValue = "" }
        )
    ),
    AppRoute(
        route = Routes.FILE_MEDIA,
        transition = TransitionType.DETAIL,
        arguments = listOf(
            navArgument("path") { type = NavType.StringType; defaultValue = "" },
            navArgument("type") { type = NavType.StringType; defaultValue = "video" }
        )
    ),
    AppRoute(
        route = Routes.FILE_IMAGE,
        transition = TransitionType.DETAIL,
        arguments = listOf(
            navArgument("path") { type = NavType.StringType; defaultValue = "" }
        )
    ),
    AppRoute(Routes.DETAIL_MONITOR_SETTINGS, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_MONITOR_COLLECTION, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_MONITOR_CHART, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_MONITOR_SCHEDULER, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_MONITOR_STORAGE, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_EVENTS, TransitionType.DETAIL)
)
