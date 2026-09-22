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
    DETAIL,

    /**
     * 从屏幕下沿升起的整页面板（2026-09-20）。
     *
     * 用在「由一个**当前可见**的小控件直接长大成整页」这类入口上 —— 目前只有
     * 迷你控制条 → 音乐播放页（封面是一条共享元素，见 [UFI_SHARED_KEY_AUDIO_COVER]）。
     *
     * 为什么它不能跟 [DETAIL] 共用横向共享轴：那条共享元素的位移几乎是纯竖直的"原地长大"，
     * 页面同时整屏横移等于让同一块封面被两个方向各拽一次，观感是两个动画在打架。
     * 面板改成竖向升起后，页面位移与共享元素同向，封面看起来是被它所在的面板一起带上来的。
     *
     * 转场函数见 `Navigation.kt` 的 `risePanel*`；登记循环在 `MainNavGraph` 里与 [DETAIL] 并列。
     *
     * ★ 2026-09-20 二次修订：这条语义**按来源生效**，不是这条路由的固定属性。
     * 起点是迷你控制条的上沿，所以只有从挂着迷你条的页面（[isUfiMiniBarHostRoute]）进来时
     * 才走升起；从别处（Tab 页标题栏的「正在播放」挂件）进来时没有这个起点，
     * 登记循环会把它退回 [DETAIL] 那套横向共享轴。判据与分支都在登记处，播放页仍然只有一条路由。
     */
    RISE
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
    /**
     * 进阶工具页（2026-09-20）。
     *
     * 工具页原来平铺 11 张入口卡，其中内网穿透 / 定时任务 / 应用管理 / 高级控制台
     * 这四项都是"低频 + 需要先懂点什么才敢点"的东西 —— 和文件、媒体、短信这些
     * 日常入口混在一格里，既把常用的挤到下面，也让人误以为随便点都安全。
     * 这一页专门收它们，一级页只留日常七项 + 本页入口。
     */
    const val DETAIL_TOOLS_EXTRA = "detail/tools-extra"
    const val DETAIL_SPEED_TEST = "detail/speed-test"
    const val DETAIL_TRAFFIC_MGMT = "detail/traffic-management"
    /** 流量历史页（2026-09-15 从流量管理页里的一张卡拆成独立页：明细最多 31 行，挤在一屏会把限额设置压到很下面）。 */
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
     * Webhook **首次配置向导**（2026-09-21）。入口在 [DETAIL_WEBHOOK_NOTIFY]，只在
     * core 回的 `configured == false` 时出现。
     *
     * 为什么是"多一条路"而不是改造 [DETAIL_WEBHOOK_NOTIFY]：那一页的旋钮是**改完立即下发**的
     * 日常微调面板（开关 / 级别 / 上限 / 场景各自独立），套进向导等于给它们造一个不存在的
     * 「完成」时刻。而从 0 配一条 Webhook 有天然顺序：选预设 → 填那一样密钥 → 定规则 → 保存并启用。
     * 两条路各管一件事，配好后仍回到原页面做日常调整。
     */
    const val DETAIL_WEBHOOK_SETUP = "detail/webhook-notify/setup"

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

    /**
     * 推送任务页（外部存储上传的第二阶段：core 暂存 → 远端，2026-09-21）。
     *
     * query 参数 `source`：**按源过滤**。入口在各个外部存储源自己的工具栏右上角 ——
     * 用户是在"某个源里"关心"我传给这个源的文件到了没有"，一个混着所有源的全局列表
     * 反而要他自己挑。传空则显示全部（留给将来可能的全局入口）。
     *
     * 为什么是独立页面而不是弹窗：一条作业要显示文件名 / 目标源 / 大小 / 状态 / 进度 /
     * 失败原因，再加取消与重试两个按钮；失败详情还是一整段异常链，需要能滚、能复制。
     */
    const val DETAIL_REMOTE_PUSH = "detail/remote-push?source={source}"


    /**
     * 外部存储源的新增 / 编辑（2026-09-21）。query 参数 `id` 为空时=新增，非空时=编辑对应源。
     *
     * 为什么是独立页面而不是弹窗：这张表单有 20 个字段（协议 + 标签 + 地址 + 凭据 +
     * 四种协议各自的专属项 + 通用选项 + 试连），和 `detail/task-edit` 当初从弹窗搬出来的
     * 理由一样 —— `UfiScrollableDialog` 只有 82% 屏高，字段一多底部的保存键就被裁掉；
     * 而分步向导自带固定底部操作栏，必须吃满屏高才立得住。
     */
    const val DETAIL_STORAGE_SOURCE_EDIT = "detail/storage-source-edit?id={id}"

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
    /**
     * 界面小功能（2026-09-17）：首页标题栏上的小挂件设置，第一个是天气。
     *
     * 与「外观」分开：那页管的是主题与配色（纯本机、无网络依赖），这里的东西要连 core
     * 拉数据、有刷新周期与上游配额，两类混在一页只会越攒越乱。
     */
    const val DETAIL_UI_EXTRAS = "detail/ui-extras"
    /**
     * 今日天气（2026-09-18）：原先躺在 [DETAIL_UI_EXTRAS] 那一屏里的天气设置。
     *
     * 拆出来的原因：诗词落地后「界面小功能」要同时装两套「开关 + 内容预览 + 立即刷新」，
     * 一屏里会出现两个长得一样的刷新按钮，分不清谁管谁。
     */
    const val DETAIL_WEATHER = "detail/weather"
    /** 今日诗词（2026-09-18）：标题栏下方那行诗的开关、出处显示与全篇预览。拆分理由同 [DETAIL_WEATHER]。 */
    const val DETAIL_POETRY = "detail/poetry"
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
    /**
     * FRP 新建隧道向导（2026-09-21）。
     *
     * 为什么要独立一页：原来「新建」只是个单输入框弹窗（只问名字），创建出来的是一份
     * 空值 TOML 模板 —— serverAddr / serverPort / auth.token 和 proxy 的 5 个键全要用户
     * 在详情页手写，改错一个键名只能靠启动失败的日志发现。现在由 [UfiWizard] 把这 8 个
     * 字段结构化收齐再生成 TOML；详情页的 TOML 编辑器保留为专家出口。
     */
    const val DETAIL_TUNNEL_FRP_NEW = "detail/tunnel/frp/new"
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

    /**
     * 媒体库：工具页三个入口（视频 / 音乐 / 图片）+ 各自的播放 / 查看页。
     *
     * 2026-09-16 下午改成三个独立列表页。原先是一条 `media/center`（一页三栏），
     * 拆分理由与 `detail/sms-filter` 那次一样：三类各有各的扫描范围、授权状态与列表状态，
     * 挤在一页里顶部要同时放分栏控件和范围条，读代码与用界面都要先问"我现在在哪一栏"。
     * 扫描范围随之按类型各存一份（core `/api/media/config?type=`），每页因此是自洽的。
     *
     * 更早的 `file/media?path=&type=` 与 `file/image?path=` 是两条**孤儿路由**（对应的
     * Screen 早已删除），已在上一轮一并清掉。
     */
    const val MEDIA_LIBRARY_VIDEO = "media/library/video"
    const val MEDIA_LIBRARY_AUDIO = "media/library/audio"
    const val MEDIA_LIBRARY_IMAGE = "media/library/image"

    /**
     * 视频页的设置（扫描目录 / 本机抽帧开关 / 封面缓存 / 批量生成 / 下载落点）。
     *
     * 单独一页而不是塞回视频页：这些是"配一次就不动"的东西，摆在浏览界面上只会挤掉内容。
     */
    const val MEDIA_VIDEO_SETTINGS = "media/library/video/settings"

    /**
     * 视频下载任务：队列 + 历史（2026-09-20 从 [MEDIA_VIDEO_SETTINGS] 拆出）。
     *
     * 拆页判据是**条数是否固定**：设置项的条数写死在代码里，而队列与历史的条数由使用量决定、
     * 还会一直长（批量下载一次就能进来几十条）。留在设置页里的结果是真正的设置项被推到屏幕外。
     * 队列与历史同页是因为它们是同一条任务的前后两态（排队 → 下载中 → 落进历史），
     * 分两页的话任何时刻都有一页是空的。
     */
    const val MEDIA_VIDEO_DOWNLOADS = "media/library/video/downloads"


    /**
     * 音乐页的设置（扫描目录 / 重新扫描）。
     *
     * 拆出来的直接原因：音乐页底部要放「全部 / 专辑 / 歌手 / 文件夹」四页切换与迷你控制条，
     * 顶部工具条那两颗配置按钮与它们抢地方，而它们本来就是"配一次就不动"的东西。
     */
    const val MEDIA_AUDIO_SETTINGS = "media/library/audio/settings"

    /**
     * 图片页的设置（扫描目录 / 重新扫描）。
     *
     * 与音乐页同一条理由：图片页顶部要放「时间轴 / 文件夹」两栏切换，工具条上那颗「目录」
     * 与它抢地方，而扫描范围本来就是"配一次就不动"的东西。
     */
    const val MEDIA_IMAGE_SETTINGS = "media/library/image/settings"


    /**
     * 音乐分组详情（某个专辑 / 某个歌手 / 某个文件夹里的曲目）。
     *
     * `by` 是聚合维度（`album` / `artist` / `folder`），`key` 是那一组的标识 ——
     * folder 维度下它是**绝对路径**，因此必须编码后再拼（见 `mediaAudioGroupRouteOf`），
     * 否则里面的 `/` 会被 Navigation 当成路径分隔符。取参处**不要再解一次**：
     * Navigation 自己已经做过一次 `Uri.decode`。
     *
     * 一条带参路由而不是三条：三个维度的列表形态、分页、顶栏完全一样，
     * 复制三份的唯一产物是三处会各自跑偏的文案。
     */
    const val MEDIA_AUDIO_GROUP = "media/library/audio/group?by={by}&key={key}"

    /**
     * 歌单详情（某个歌单里的曲目）。
     *
     * `id` 是 core 生成的 8 位歌单 id（`/api/playlists` 返回），**不带歌单名** ——
     * 名字会被重命名，而路由里的参数会随返回栈一起留在进程里，带上它就会出现
     * "标题还是旧名字"。标题由页面拿 id 去状态里取（重命名后 force 重拉会更新）。
     */
    const val MEDIA_AUDIO_PLAYLIST = "media/library/audio/playlist?id={id}"
    const val MEDIA_VIDEO = "media/video?path={path}"

    /**
     * 音乐播放页。
     *
     * `scope` / `scopeKey` 是**播放范围**（2026-09-21 新增）：
     * - 专辑 / 歌手 / 文件夹 / 歌单详情页点歌 → 带上那一组；
     * - 「全部」页点歌 → 带 `scope=all`（整库）；
     * - **不带**（两者为空）→ "没指定范围"，播放页**沿用现有队列**，只跳到这一首。
     *   通知栏深链接、迷你条、标题栏挂件走的是这一条：它们只知道"正在播这首歌"，
     *   不知道队列是按什么范围装的，没有资格要求重建。
     *
     * 「不带」与「`scope=all`」必须分开：把不带当成整库的那一版会让"从迷你条点回播放页"
     * 把歌单/专辑队列静默重装成整库 —— 歌还是那首，「下一首」已经跑出去了。
     *
     * 为什么范围必须进路由：播放队列要靠它决定「下一首去哪」。此前分组详情页与「全部」页
     * 走的是同一条只带 path 的路由，于是从歌手页点第一首时队列就已经是整库了 ——
     * 「只播这个歌手的歌」根本无从表达。
     *
     * `scopeKey` 在 folder 维度下是**绝对路径**，必须编码后再拼（见 `mediaAudioRouteOf`），
     * 理由同 [MEDIA_AUDIO_GROUP]。
     */
    const val MEDIA_AUDIO = "media/audio?path={path}&scope={scope}&scopeKey={scopeKey}"
    const val MEDIA_IMAGE = "media/image?path={path}"
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
    AppRoute(Routes.DETAIL_TOOLS_EXTRA, TransitionType.DETAIL),
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
    AppRoute(Routes.DETAIL_WEBHOOK_SETUP, TransitionType.DETAIL),
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
    AppRoute(
        route = Routes.DETAIL_REMOTE_PUSH,
        transition = TransitionType.DETAIL,
        arguments = listOf(
            navArgument("source") { type = NavType.StringType; defaultValue = "" }
        )
    ),
    AppRoute(
        route = Routes.DETAIL_STORAGE_SOURCE_EDIT,
        transition = TransitionType.DETAIL,
        arguments = listOf(
            navArgument("id") { type = NavType.StringType; defaultValue = ""; nullable = true }
        )
    ),
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
    AppRoute(Routes.DETAIL_UI_EXTRAS, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_WEATHER, TransitionType.DETAIL),
    AppRoute(Routes.DETAIL_POETRY, TransitionType.DETAIL),
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
    AppRoute(Routes.DETAIL_TUNNEL_FRP_NEW, TransitionType.DETAIL),
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
    AppRoute(Routes.MEDIA_LIBRARY_VIDEO, TransitionType.DETAIL),
    AppRoute(Routes.MEDIA_LIBRARY_AUDIO, TransitionType.DETAIL),
    AppRoute(Routes.MEDIA_LIBRARY_IMAGE, TransitionType.DETAIL),
    AppRoute(Routes.MEDIA_VIDEO_SETTINGS, TransitionType.DETAIL),
    AppRoute(Routes.MEDIA_VIDEO_DOWNLOADS, TransitionType.DETAIL),
    AppRoute(Routes.MEDIA_AUDIO_SETTINGS, TransitionType.DETAIL),
    AppRoute(Routes.MEDIA_IMAGE_SETTINGS, TransitionType.DETAIL),
    // 分组详情：两个参数都给空缺省，缺参进来时至少能落到一个不崩的页面（列表为空 + 维度名当标题）
    AppRoute(
        route = Routes.MEDIA_AUDIO_GROUP,
        transition = TransitionType.DETAIL,
        arguments = listOf(
            navArgument("by") { type = NavType.StringType; defaultValue = "" },
            navArgument("key") { type = NavType.StringType; defaultValue = "" }
        )
    ),
    // 歌单详情：id 缺省空串 —— 缺参进来时页面显示空歌单而不是崩，与分组详情同一口径
    AppRoute(
        route = Routes.MEDIA_AUDIO_PLAYLIST,
        transition = TransitionType.DETAIL,
        arguments = listOf(
            navArgument("id") { type = NavType.StringType; defaultValue = "" }
        )
    ),
    AppRoute(
        route = Routes.MEDIA_VIDEO,
        transition = TransitionType.DETAIL,
        arguments = listOf(
            navArgument("path") { type = NavType.StringType; defaultValue = "" }
        )
    ),
    // 音乐播放页是**唯一**的 RISE：它是从迷你条封面长起来的，横滑会和那条共享元素打架。
    // 其余媒体路由（音乐列表 / 音乐设置 / 分组 / 视频 / 图片）都还是普通二级页，保持 DETAIL。
    AppRoute(
        route = Routes.MEDIA_AUDIO,
        transition = TransitionType.RISE,
        arguments = listOf(
            navArgument("path") { type = NavType.StringType; defaultValue = "" },
            // 范围缺省为空 = 整库。老入口（通知栏 / 迷你条）不带这两个参数也能正常进页。
            navArgument("scope") { type = NavType.StringType; defaultValue = "" },
            navArgument("scopeKey") { type = NavType.StringType; defaultValue = "" }
        )
    ),
    AppRoute(
        route = Routes.MEDIA_IMAGE,
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

/**
 * [TransitionType.RISE] 的路由集合。
 *
 * 必须写在 [appRoutes] **之后**：文件级属性按书写顺序初始化，写在前面会读到空表。
 */
private val riseRoutePatterns: Set<String> =
    appRoutes.filter { it.transition == TransitionType.RISE }.map { it.route }.toSet()

/**
 * 这条 route 是不是升起面板（[TransitionType.RISE]）。
 *
 * 为什么导航层需要问这个问题：navigation-compose 的转场是**按目的地各自登记**的 ——
 * 进场 / popExit 取的是"正在动的那一页"自己登记的函数，而 exit / popEnter 取的是**对端**
 * （留在下面那一页）登记的函数。也就是说播放页自己怎么升起由 RISE 登记决定，但
 * 「面板升起时下层的音乐列表怎么动」写在**列表页**那条 DETAIL 登记里。列表页若照常横滑，
 * 用户仍然会看到"面板往上、底下那页往左"两个方向同时动 —— 这次要修的正是这个。
 * 所以 DETAIL 登记要能识别出"这次的对端是升起面板"，改用不动的下层处理。
 *
 * 比较的是**路由模板**（含 `?path={path}` 这样的占位符），与
 * `NavBackStackEntry.destination.route` 的取值口径一致，不需要解析实参。
 *
 * @param route `NavDestination.route`，可空（图节点等没有 route 的目的地）。
 */
fun isUfiRiseRoute(route: String?): Boolean = route != null && route in riseRoutePatterns

/**
 * 「底部挂着迷你控制条」的页面集合（2026-09-20）。
 *
 * 为什么需要这份名单：升起面板那套动画（[TransitionType.RISE]）的起点是**迷你控制条的
 * 上沿** —— 面板是从用户刚刚点的那条线上长出来的。这个前提只在**那条线此刻真的在屏上**
 * 时成立，也就是只有从挂着迷你条的页面进播放页才成立。
 *
 * 从别处进播放页（例如仪表盘等 Tab 页点标题栏的「正在播放」挂件，那里没有迷你条）时
 * 没有这个起点：面板会从屏幕外整整一屏下方飞上来，观感是"页面凭空从屏幕外飞进来"，
 * 而且那条路径压根没挂共享元素（[UFI_SHARED_KEY_AUDIO_COVER] 只在迷你条与播放页两端），
 * 竖向升起连"跟着封面走"这条理由都没有。所以那些来源要退回默认的横向 shared-axis。
 *
 * ⚠ 新增"挂迷你条"的页面时这里要一起加。漏了的表现不是崩溃，而是那一页点迷你条进播放页
 * 时动画不连贯（用户点的是底部那条线，页面却从右边滑进来）。
 *
 * 比较口径与 [isUfiRiseRoute] **完全一致**：拿 `NavDestination.route` 那个**模板**直接比
 * （[Routes.MEDIA_AUDIO_GROUP] 带 `?by={by}&key={key}` 占位符，这里存的就是含占位符的原串），
 * 不解析实参、不做任何归一化 —— 两个判定必须同一套，否则某天会出现"一个认得、一个不认得"。
 */
private val miniBarHostRoutePatterns: Set<String> = setOf(
    Routes.MEDIA_LIBRARY_AUDIO,
    Routes.MEDIA_AUDIO_GROUP,
    Routes.MEDIA_AUDIO_PLAYLIST,
)

/**
 * 这条 route 是不是「挂着迷你控制条、因此能当升起起点」的页面。名单与理由见
 * [miniBarHostRoutePatterns]。
 *
 * @param route `NavDestination.route`，可空（图节点等没有 route 的目的地）。
 */
fun isUfiMiniBarHostRoute(route: String?): Boolean =
    route != null && route in miniBarHostRoutePatterns

