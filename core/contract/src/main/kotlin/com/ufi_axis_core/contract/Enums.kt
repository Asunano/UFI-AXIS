package com.ufi_axis_core.contract

/**
 * 定时任务 / 自动化规则的动作类型。
 *
 * 权威来源：`core/scheduler/.../ActionExecutor.kt` 的 `VALID_ACTION_TYPES`
 * （TaskRoutes 的 4 个校验点全部引用它，越界返回 400 `Invalid action type`）。
 * 执行侧 `core/.../ActionExecutorImpl.kt` 的 `when(actionType)` 分支必须与本枚举一一对应。
 *
 * 注意既存差异（**不是 bug，别顺手"统一"**）：
 * - 定时任务（`api/tasks`）缺省 actionType = [CUSTOM_SHELL]；
 * - 自动化规则（`api/rules`）缺省 actionType = [DATA_TOGGLE]。
 */
enum class ActionType(val wire: String) {
    DATA_TOGGLE("data_toggle"),
    WIFI_TOGGLE("wifi_toggle"),
    AIRPLANE_TOGGLE("airplane_toggle"),
    REBOOT("reboot"),
    SHUTDOWN("shutdown"),
    LED_TOGGLE("led_toggle"),
    PERFORMANCE_MODE("performance_mode"),
    ROAMING_TOGGLE("roaming_toggle"),
    NETWORK_MODE("network_mode"),
    CUSTOM_SHELL("custom_shell");

    companion object {
        val ALL: List<String> = entries.map { it.wire }

        fun fromWire(wire: String?): ActionType? = entries.firstOrNull { it.wire == wire }

        /** 参数契约（来自 ActionExecutorImpl）：键名 → 类型/默认值，改动必须双侧同步。 */
        const val PARAM_ENABLED = "enabled"       // 5 个 *_toggle，Boolean
        const val PARAM_MODE = "mode"             // performance_mode: Int（默认 0）；network_mode: String
        const val PARAM_COMMAND = "command"       // custom_shell，String
    }
}

/**
 * 网络模式。
 *
 * **这里有一个真实 bug 的边界**：`POST api/network/mode` 会把别名映射成 goform 的
 * BearerPreference 再下发（`NetworkRoutes.kt` 的 `when (mode.uppercase())`），
 * 但 `ActionExecutorImpl` 的 `network_mode` 分支**跳过了这张映射表**，
 * 直接把 `params["mode"]` 当 BearerPreference 用。
 * 于是定时任务选「仅 5G」会把别名 `5G_ONLY` 直发设备（设备期望 `Only_5G`），只有
 * `LTE_AND_5G` 因两个取值域同名而侥幸可用。
 *
 * 因此 contract 同时定义两个取值域并给出映射，**双侧都必须先经 [toBearer] 再下发**。
 */
object NetworkMode {

    /** 客户端可选的别名（大小写不敏感，core 侧 `uppercase()` 后匹配）。 */
    const val AUTO = "AUTO"
    const val ONLY_5G = "5G_ONLY"
    const val LTE_AND_5G = "LTE_AND_5G"
    const val ONLY_LTE = "ONLY_LTE"
    const val ONLY_WCDMA = "WCDMA_ONLY"
    const val WCDMA_AND_LTE = "WCDMA_AND_LTE"

    /** 给 UI 用的别名清单（对用户暴露的档位，顺序即展示顺序）。 */
    val UI_OPTIONS: List<String> = listOf(AUTO, ONLY_5G, LTE_AND_5G, ONLY_LTE, WCDMA_AND_LTE, ONLY_WCDMA)

    /** goform BearerPreference 实际取值（**大小写敏感**，设备只认这一组）。 */
    object Bearer {
        const val WL_AND_5G = "WL_AND_5G"
        const val ONLY_5G = "Only_5G"
        const val LTE_AND_5G = "LTE_AND_5G"
        const val ONLY_LTE = "Only_LTE"
        const val ONLY_WCDMA = "Only_WCDMA"
        const val WCDMA_AND_LTE = "WCDMA_AND_LTE"
    }

    /**
     * 别名 → BearerPreference。与 `NetworkRoutes.kt` 的 `when` 完全等价（含全部历史别名）。
     * 未识别的取值原样返回（保持 core 现有的"直接透传"行为，不在此处改变语义）。
     */
    fun toBearer(mode: String): String = when (mode.uppercase()) {
        "AUTO", "WL_AND_5G" -> Bearer.WL_AND_5G
        "5G_ONLY", "ONLY_5G", "5G_SA" -> Bearer.ONLY_5G
        "5G_NSA", "LTE_AND_5G" -> Bearer.LTE_AND_5G
        "LTE_ONLY", "ONLY_LTE", "4G_ONLY" -> Bearer.ONLY_LTE
        "WCDMA_ONLY", "ONLY_WCDMA" -> Bearer.ONLY_WCDMA
        "LTE_WCDMA", "WCDMA_AND_LTE" -> Bearer.WCDMA_AND_LTE
        else -> mode
    }

    /**
     * BearerPreference → 别名（`toBearer` 的逆映射，与 web 的 `BearerToNetworkMode` 一一对应）。
     * 设备回读的 `BearerPreference` 是 Bearer 取值域，UI 若用别名做选中比对必须先经此函数换算。
     * 未识别的取值（例如老字段 `net_select` 可能回的 `AUTO`）原样返回。
     */
    fun fromBearer(bearer: String): String = when (bearer.uppercase()) {
        "WL_AND_5G" -> AUTO
        "ONLY_5G" -> ONLY_5G
        "LTE_AND_5G" -> LTE_AND_5G
        "ONLY_LTE" -> ONLY_LTE
        "ONLY_WCDMA" -> ONLY_WCDMA
        "WCDMA_AND_LTE" -> WCDMA_AND_LTE
        else -> bearer
    }

    /** band lock 的"解锁"= 锁全部频段（来自 `GoformNetworkClient`）。 */
    const val LTE_ALL_BANDS = "1,3,5,8,34,38,39,40,41"
    const val NR_ALL_BANDS = "1,5,8,28,41,78"

    /**
     * 切换制式后的「回读确认」预算（2026-09-11 真机缺陷修复）。
     *
     * ## 为什么需要它
     *
     * `POST /api/network/mode` 返回成功只代表**固件收下了**这条命令，不代表制式已经切完：
     * 设备要重新注册网络，这期间 `GET /api/device/settings` 的 `BearerPreference`
     * **仍然报旧值**。客户端只回读一次（web 是写完 600ms 一次，app 是压根没回读设备设置）
     * 就把结果渲染上去，于是界面停在旧档位，直到别的地方偶然又拉了一次设置才自己变对
     * —— 这正是用户看到的"切换生效了但界面还显示旧制式"。
     *
     * 正确做法是给一个「切换中」的中间态，然后**有上限地**轮询回读，直到设备报出目标档位
     * 或预算用尽。上限是硬要求：设备在弱信号下可能十几秒都注册不上，无限轮询会一直打
     * goform（每次回读都是一次设备侧查询），把 `GoformQoS` 的许可耗在这上面。
     *
     * ## 为什么放在 contract
     *
     * app（Kotlin）与 web（`web/src/api/contract.ts` 的 `NetworkModeSwitchProbe`）必须用
     * 同一套节奏，否则两端"多久算超时"不一致，同一台设备在两个客户端上表现不同。
     */
    object SwitchProbe {

        /**
         * 下发成功后到第一次回读的等待。
         * 设备 goform 写入到查询接口可见有约 600ms 延迟，立刻回读拿到的**一定**是旧值。
         */
        const val FIRST_DELAY_MS = 600L

        /** 之后每次回读的间隔。 */
        const val INTERVAL_MS = 1_500L

        /** 回读次数上限（**含**第一次）。到顶还没读到目标档位就报超时，不许再读。 */
        const val MAX_ATTEMPTS = 10

        /** 总时长上限，= [FIRST_DELAY_MS] + (次数-1) × [INTERVAL_MS]，给文案与守卫用。 */
        const val TOTAL_BUDGET_MS = FIRST_DELAY_MS + (MAX_ATTEMPTS - 1) * INTERVAL_MS

        /**
         * 还要不要再回读一次。
         *
         * @param attemptNo 刚刚完成的是第几次回读（从 1 开始）
         * @param reachedTarget 这次回读到的档位是否已经等于目标档位
         *
         * 两个终止条件都是硬的：读到目标就停（成功），次数到顶就停（超时）。
         * **不要**改成"按总时长判断"再叠一层 —— 两套上限并存时，谁先到谁生效会变成偶发行为。
         */
        fun shouldKeepProbing(attemptNo: Int, reachedTarget: Boolean): Boolean =
            !reachedTarget && attemptNo < MAX_ATTEMPTS
    }
}

/**
 * 告警类型与级别。
 *
 * **注意：core 侧没有白名单校验**（`AlertRoutes` 对 `type`/`level` 只做 `isNotBlank()`
 * 就透传给 SQL 过滤，`Entities.kt` 里只有注释）。取值的事实来源是 `AlertEngine`
 * 各 check 方法的字面量。所以本对象是"约定"而非"强制"，等 AlertRoutes 加上校验
 * （批次 C）之后才具备强制力。
 */
object Alerts {
    object Type {
        const val TEMPERATURE = "temperature"
        const val BATTERY = "battery"
        const val TRAFFIC = "traffic"
        const val SIGNAL = "signal"
        const val CONNECTIVITY = "connectivity"

        // 2026-09-07 补齐：以下三类 AlertEngine 一直在检测（checkTrafficLimit / recordDeviceEvent），
        // 但从未出现在本清单里，于是 `AlertConfig.perType` 里也没有对应键。而 `typeEnabled` 的判据是
        // `perType[type] == true`（缺键视为关闭），结果这三类**永远不会触发** —— 套餐限额预警、
        // WiFi 客户端接入/离开在实际使用中是死的。补进来后 app / web 的分类开关才能真正打开它们。
        /** 套餐限额百分比预警（≠ TRAFFIC 的绝对 MB 阈值） */
        const val TRAFFIC_LIMIT = "traffic_limit"
        const val DEVICE_ONLINE = "device_online"
        const val DEVICE_OFFLINE = "device_offline"

        val ALL: List<String> = listOf(
            TEMPERATURE, BATTERY, TRAFFIC, SIGNAL, CONNECTIVITY,
            TRAFFIC_LIMIT, DEVICE_ONLINE, DEVICE_OFFLINE
        )
    }

    object Level {
        const val INFO = "info"
        const val WARNING = "warning"
        const val CRITICAL = "critical"

        /**
         * `normal` 只是 AlertEngine 的**内部状态机值**，不会落库、不会推给客户端；
         * 客户端如果对它做展示分支就是错的。
         */
        const val INTERNAL_NORMAL = "normal"

        /** 会出现在告警记录里的级别。 */
        val PERSISTED: List<String> = listOf(INFO, WARNING, CRITICAL)
    }

    /**
     * `api/alerts/list` 的参数与钳制（`AlertRoutes.kt`：limit `coerceIn(1, 200)`，默认 50）。
     * 命名为 `ListQuery` 而非 `List`：内层 `object List` 会遮蔽 `kotlin.collections.List`。
     */
    object ListQuery {
        const val PARAM_LIMIT = "limit"
        const val PARAM_LEVEL = "level"
        const val PARAM_TYPE = "type"
        const val PARAM_UNREAD = "unread"
        const val PARAM_START_TIME = "start_time"
        const val PARAM_END_TIME = "end_time"
        const val PARAM_CURSOR = "cursor"

        const val LIMIT_MIN = 1
        const val LIMIT_MAX = 200
        const val LIMIT_DEFAULT = 50

        /**
         * cursor 是 base64 的 `"$timestamp.$id"`，**向更早的记录翻页**。
         * 增量拉取要用 [PARAM_START_TIME]（闭区间，边界那条会重复返回一次）。
         */
        fun clampLimit(limit: Int): Int = limit.coerceIn(LIMIT_MIN, LIMIT_MAX)
    }

    /** `PUT api/alerts/config` 版本守门失败时的响应键（HTTP 409）。 */
    object ConfigConflict {
        const val ERROR = "config_version_conflict"
        const val KEY_CURRENT_VERSION = "currentVersion"
        const val KEY_CONFIG = "config"
    }
}

/**
 * WebSocket 频道。
 *
 * 协议（`core/websocket/.../WebSocketManager.kt`）：
 * - 订阅报文 `{"subscribe": ["cpu", "traffic", ...]}`，**替换语义**（先清空旧订阅再加），
 *   另有 `{"unsubscribe": [...]}` 做增量移除；
 * - 推送 `{"type": "<channel>", "data": {...}, "timestamp": <ms>}`；
 * - core 按频道严格过滤：`subscriptions[type] ?: return` —— **没订阅就永远收不到**；
 * - 最大连接数 [MAX_CONNECTIONS]，超限以 `TRY_AGAIN_LATER`(1013) 关闭。
 */
object WsChannel {
    const val TRAFFIC = "traffic"
    const val SIGNAL = "signal"
    const val CPU = "cpu"
    const val MEMORY = "memory"
    const val ALERT = "alert"
    const val NOTIFICATION = "notification"
    const val DATA_CHANGED = "data_changed"
    const val CONFIG_CHANGED = "config_changed"
    const val UPDATE = "update"

    /** core 真正会广播的全部频道（`broadcast("x")` / `broadcaster("x")` 的字面量）。 */
    val ALL: List<String> = listOf(
        TRAFFIC, SIGNAL, CPU, MEMORY, ALERT, NOTIFICATION, DATA_CHANGED, CONFIG_CHANGED, UPDATE
    )

    /**
     * 前台 UI（app 主进程 / web）应订阅的集合 = [ALL] 去掉 [NOTIFICATION]。
     *
     * 为什么去掉 notification：core 的 `WebSocketPushService` 对**同一条告警**先发
     * `notification` 再发 `alert`（同一 payload 双发，为兼容旧客户端）。UI 侧两个分支都
     * 会建 AlertRecord / 弹 toast，同时订阅就会一条告警响两次。UI 只认 [ALERT]。
     */
    val UI_TOPICS: List<String> = ALL.filter { it != NOTIFICATION }

    /**
     * 通知守护进程（`:ufi_notify`）订阅集合：只要告警，两个都订是为了兼容
     * （去重由 `NotificationCenter` 的游标 + 签名 TTL 负责）。
     * 不订 cpu/memory/traffic 等高频频道 —— 有连接时 core 采集会从 60s 提速到 3s，
     * 后台进程不该为此抬高设备负载。
     */
    val NOTIFY_TOPICS: List<String> = listOf(ALERT, NOTIFICATION)


    /**
     * **禁止订阅**：core 从不广播这两个频道，订阅只会白占连接名额。
     * 电量数据请走 `api/dashboard/summary` REST。
     * 校验器会把订阅它们报成 P0。
     */
    val NEVER_BROADCAST: List<String> = listOf("battery", "sms_contacts")

    /** 连接建立后的欢迎帧类型，不是可订阅频道。 */
    const val WELCOME = "connected"

    const val MAX_CONNECTIONS = 4
}

/**
 * `api/config` 各字段的取值范围。
 *
 * 权威来源：`core/api/.../ConfigRoutes.kt` 的区间判断。
 * **越界不会报错**——core 静默丢弃该字段且不计入 `updated`，所以客户端必须自己先钳制，
 * 否则用户会看到"保存成功但值没变"。
 */
object ConfigLimits {
    val PORT = 1024..65535
    val GOFORM_PORT = 1..65535
    val QOS_SHELL_MAX_CONCURRENT = 1..10
    val QOS_CACHE_TTL_MS = 500..30000
    val QOS_GOFORM_QUERY_MAX = 1..8
    val QOS_GOFORM_SET_MAX = 1..4
    val SMS_CODE_CLEANUP_HOURS = 0..720

    /**
     * `goform_password` 读取时是**脱敏值**，回写脱敏值会被拒（`isMaskedValue`）。
     * 客户端在用户未修改该字段时**不要**把读到的值再 PUT 回去。
     */
    const val MASKED_HINT = "脱敏字段不可回写"
}

/**
 * 错误信封。
 *
 * 现状：core 混用 `{"success": false, ...}`、`{"ok": false, "error": ...}` 与纯 HTTP 状态码，
 * **尚无统一 helper 与错误码枚举**。统一工作在 C01（`ok()` / `fail()`）里做，
 * 那之前这里只登记"客户端必须同时认这些键"，避免又造一份与 core 不一致的枚举。
 */
object ErrorEnvelope {
    const val KEY_SUCCESS = "success"
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_MESSAGE = "message"

    /**
     * 关键陷阱：**HTTP 200 也可能是失败**（`{"success": false}`）。
     * 只看 HTTP 状态码的客户端会把失败当成功——这是上一轮 web 修复里反复出现的 bug 类别。
     */
    const val PITFALL_HTTP_200_MAY_FAIL = "HTTP 200 + success:false 也是失败"
}
