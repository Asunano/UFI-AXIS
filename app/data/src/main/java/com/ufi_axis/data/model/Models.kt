@file:OptIn(ExperimentalSerializationApi::class)

package com.ufi_axis.data.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

// ========== Device ==========

@Serializable
data class DeviceInfoResponse(
    val device: DeviceModel? = null,
    val sim: SimState? = null,
    val storage: StorageInfo? = null,
    val uptime: UptimeInfo? = null,
    val at_channel: AtChannelState? = null,
    val kernel: String? = null,
    val network: NetworkInfo? = null,
    val identity: Map<String, String>? = null
)

@Serializable
data class DeviceModel(
    val brand: String,
    val model: String,
    val device: String,
    val manufacturer: String,
    val android_version: String,
    val sdk_version: String,
    val build_id: String
)

@Serializable
data class SimState(
    val sim_state: String,
    val phone_type: String
)

@Serializable
data class AtChannelState(
    val connected: Boolean
)

// ========== System ==========

@Serializable
data class CpuInfo(
    val usage_percent: Double,
    val core_count: Int,
    val cores: List<CpuCore>,
    val temperature: Double = 0.0
)

@Serializable
data class CpuCore(
    val core: Int,
    val freq_mhz: Double,
    val freq_display: String = ""
)

@Serializable
data class MemoryInfo(
    val total: Long,
    val available: Long,
    val free: Long,
    val buffers: Long,
    val cached: Long,
    val used: Long,
    val usage_percent: Double
)

@Serializable
data class BatteryInfo(
    val level: Int,
    val scale: Int,
    val percent: Int,
    val temperature: Double,
    val voltage: Double,
    val is_charging: Boolean,
    val plugged: String
)

@Serializable
data class StorageInfo(
    val total: Long,
    val available: Long,
    val used: Long,
    val usage_percent: Double
)

@Serializable
data class UptimeInfo(
    val uptime_seconds: Long,
    val uptime_display: String
)

// ========== Traffic ==========

@Serializable
data class TrafficRealtime(
    val rx_speed: Long,
    val tx_speed: Long,
    @JsonNames("realtime_rx_bytes") val rx_bytes: Long,
    @JsonNames("realtime_tx_bytes") val tx_bytes: Long,
    val rx_speed_display: String = "",
    val tx_speed_display: String = "",
    val realtime_rx_thrpt: Long = 0,
    val realtime_tx_thrpt: Long = 0,
    val timestamp: Long = 0L
)

@Serializable
data class TrafficHistoryResponse(
    val records: List<TrafficRecord>,
    val count: Int,
    val period_hours: Int
)

@Serializable
data class TrafficRecord(
    val id: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val rxSpeed: Long,
    val txSpeed: Long,
    val timestamp: Long
)

@Serializable
data class TrafficSummary(
    val total_rx_bytes: Long = 0,
    val total_tx_bytes: Long = 0,
    val total_bytes: Long = 0,
    val total_rx_display: String = "0 B",
    val total_tx_display: String = "0 B",
    val record_count: Int = 0,
    val today_rx_display: String = "0 B",
    val today_tx_display: String = "0 B",
    /** 今日上下行合计（core 组装，监控中心「今日流量」用这个）。 */
    val today_total_bytes: Long = 0,
    val today_total_display: String = "0 B",
    val month_rx_display: String = "0 B",
    val month_tx_display: String = "0 B"
)

/**
 * 流量限额配置（`GET /api/device/traffic-limit`）。
 *
 * core 已经把设备侧的复合串（`"470_1024"` = 470 GB）拆成 [limit_value] +
 * [limit_unit_display] + [limit_bytes]，**客户端不再解析任何复合格式**。
 * 告警判定一律用 [limit_bytes]（0 = 未设限额）—— 历史上这里各写了一份解析，
 * 其中一份读不出复合格式，导致 GB 档位的流量告警永不触发。
 */
@Serializable
data class TrafficLimitConfig(
    val enabled: Boolean = false,
    /** 限额数值（单位见 [limit_unit_display]）。 */
    val limit_value: String = "",
    /** 限额单位显示名：`MB` / `GB` / `TB`。 */
    val limit_unit_display: String = "GB",
    /** 限额字节数；0 = 未设限额。 */
    val limit_bytes: Long = 0,
    @JsonNames("data_volume_alert_percent") val alert_percent: String = "80",
    val auto_clear: Boolean = false,
    val clear_date: String = "1",
    val monthly_rx_bytes: Long = 0,
    val monthly_tx_bytes: Long = 0,
    val monthly_time: Long = 0,
    /** 本月已用字节（rx + tx，core 算好）。 */
    val used_bytes: Long = 0,
    /** core 自制的「到达告警阈值自动关闭移动数据」——不是设备字段，缺省全关。 */
    val auto_off: TrafficAutoOffConfig = TrafficAutoOffConfig(),
    val error: String? = null
)

/**
 * 到达流量告警阈值后自动关网（core 侧功能，见 `TrafficAutoOffGuard`）。
 *
 * 阈值直接复用 [TrafficLimitConfig.alert_percent]，没有第二个阈值配置。
 */
@Serializable
data class TrafficAutoOffConfig(
    /** 达到阈值后：先发邮件，发信成功再等 1 分钟关闭移动数据。 */
    val enabled: Boolean = false,
    /** 流量清零后自动重新打开；false = 只关一次，等手动开。 */
    val restore_on_reset: Boolean = false,
    /** 本计费周期是否已经因为限额关过网（只读）。 */
    val triggered: Boolean = false
)

// ========== Network ==========

/**
 * 信号详情（`GET /api/network/signal`，以及 WS `signal` 频道的载荷）。
 *
 * 下半部分是**服务小区统一字段**（计划书 1.10 / 决策 D2）：core 已按「字段存在性」
 * 做了 NR 优先、LTE 兜底的合并，客户端**不要再按制式 if**，也不要自己拼 `n`/`B` 前缀
 * （[band_label] 就是拼好的）。`nr_*` / `lte_*` 原字段仍在响应里（NSA 双连接要看两边），
 * 但只有排查双连接时才需要读。
 *
 * 缺失即省略 key（数值型无数据用 `-1` 哨兵），所以全部可空。
 */
@Serializable
data class SignalInfo(
    val rsrp: Int? = null,
    val sinr: Int? = null,
    val rsrq: Int? = null,
    val rssi: Int? = null,
    val rat: String? = null,
    val operator: String? = null,
    val network_registered: Boolean? = null,
    /** 服务小区频段号，纯数字（`"78"` / `"3"`）。 */
    val band: String? = null,
    /** 频段显示名，core 拼好（`"n78"` / `"B3"`）。 */
    val band_label: String? = null,
    /** 服务小区频点（NR-ARFCN 或 EARFCN）。 */
    val arfcn: Long? = null,
    /** 服务小区带宽 kHz。固件普遍不填，多半是 null。 */
    val band_width: Int? = null,
    /** 服务小区信号强度 dBm。 */
    val signal_strength: Int? = null,
    /** 服务小区物理小区标识。 */
    val pci: Int? = null
) {
    /** 当前是否驻留 5G：**看服务小区频段标签**（core 派生，NR 优先），不解析 [rat] 文案。 */
    val isNr: Boolean get() = band_label?.startsWith("n", ignoreCase = true) == true
}

@Serializable
data class NetworkStatusResponse(
    val network: NetworkState = NetworkState(),
    val mobile_data: Boolean = false,
    val ppp_status: String = "",
    val operator: String = "",
    val network_type: String = ""
) {
    /** 参考 UFI-TOOLS-REF: 用 ppp_status goform 字段判断蜂窝数据真实连接状态。
     *  ZTE 设备上 ConnectivityManager 不反映 modem PPP 链路状态，必须用此字段。 */
    val isCellularConnected: Boolean get() =
        ppp_status.contains("connected", ignoreCase = true) &&
        !ppp_status.contains("disconnected", ignoreCase = true)
}

@Serializable
data class NetworkState(
    val is_connected: Boolean = false,
    val has_internet: Boolean = false,
    val has_cellular: Boolean = false,
    val has_wifi: Boolean = false
)

@Serializable
data class NetworkInfo(
    val operator: String,
    val type: String,
    val connected: Boolean
)

@Serializable
data class SuccessResponse(
    val success: Boolean
)

@Serializable
data class EnabledResponse(
    val success: Boolean,
    val enabled: Boolean
)

@Serializable
data class AirplaneResponse(
    val success: Boolean,
    val airplane_mode: Boolean
)

@Serializable
data class BandRequest(
    val rat: String,
    val bands: String,
    val action: String = "lock"
)

@Serializable
data class ModeRequest(
    val mode: String
)

@Serializable
data class ModeResponse(
    val success: Boolean,
    val mode: String
)

// ========== SIM / SMS ==========

@Serializable
data class SmsSendRequest(
    val phone: String,
    val message: String
)

@Serializable
data class SmsSendResponse(
    val success: Boolean,
    val message: String? = null,
    val phone: String
)

@Serializable
data class SmsListResponse(
    val messages: List<SmsRecord>,
    val count: Int,
    val total: Int = 0
)

@Serializable
data class SmsContact(
    val phoneNumber: String,
    val total: Int,
    val unread: Int,
    val latestMsg: String,
    val latestTimestamp: Long,
    val latestDirection: String
)

@Serializable
data class SmsContactListResponse(
    val contacts: List<SmsContact>,
    val count: Int
)

@Serializable
data class SmsRecord(
    val id: Long,
    val direction: String,
    val phoneNumber: String,
    val content: String,
    val timestamp: Long,
    val source: String? = null,
    val read: Boolean = true
)

@Serializable
data class VerificationCode(
    val msgId: Long,
    val code: String,
    val source: String,
    /** 80 字截断预览（列表卡片、通知用） */
    val snippet: String,
    val timestamp: Long,
    val keyword: String,
    /**
     * 原短信全文（core DB v8 起随验证码一并入库）。
     * v8 之前的旧记录、或 core 版本较旧时为空串 —— 展示方回退到 [snippet]。
     */
    val body: String = ""
)

@Serializable
data class VerificationCodeListResponse(
    val codes: List<VerificationCode>,
    val count: Int
)

// ========== 短信拦截（号码黑名单 + 关键词，同一张表的不同 scope） ==========

/**
 * [SmsRule.scope] 取值。core 侧真源是 `SmsFilter.SCOPES`，这里是客户端镜像。
 *
 * 号码黑名单 = [SENDER]，关键词 = [BODY]；[BOTH] 是旧 `blacklist` 字段的语义
 * （同一条同时匹配发件人和正文），保留可选值但新建规则不默认走它 —— 它正是
 * 「屏蔽 10086，正文含 10086 的正常短信也被拦」的成因。
 */
object SmsRuleScope {
    const val SENDER = "sender"
    const val BODY = "body"
    const val BOTH = "both"
}

/** [SmsRule.matchType] 取值。**没有正则**（决策已定），四种都是可预测的字符串匹配。 */
object SmsRuleMatch {
    const val CONTAINS = "contains"
    const val EQUALS = "equals"
    const val PREFIX = "prefix"
    const val SUFFIX = "suffix"
}

/**
 * 短信拦截规则（`GET/POST /api/sms/rules`、`PUT/DELETE /api/sms/rules/{id}`）。
 *
 * 字段名与 core entity 的 snake_case 一一对应（[matchType] / [hitCount] / …），
 * Kotlin 侧用驼峰，靠 `@SerialName` 桥接 —— 别把驼峰名直接发给 core，core 读的是 snake_case。
 *
 * 全字段带默认值：`AppJson` 是 lenient + ignoreUnknownKeys，但**缺字段**在没有默认值时
 * 仍会抛 `MissingFieldException`。老 core 少回一个字段不该让整个列表解析失败。
 */
@Serializable
data class SmsRule(
    val id: Long = 0,
    val enabled: Boolean = true,
    /** 见 [SmsRuleScope]。 */
    val scope: String = SmsRuleScope.BODY,
    /** 见 [SmsRuleMatch]。 */
    @SerialName("match_type") val matchType: String = SmsRuleMatch.CONTAINS,
    val pattern: String = "",
    val note: String = "",
    /** 命中次数，core 侧内存累加 + 增量 UPDATE；只读，客户端不回写。 */
    @SerialName("hit_count") val hitCount: Int = 0,
    @SerialName("last_hit_at") val lastHitAt: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0
)

@Serializable
data class SmsRuleListResponse(
    val rules: List<SmsRule> = emptyList(),
    val count: Int = 0
)

/**
 * 规则写入请求体（新增 / 修改共用）。
 *
 * 全部可空且默认 null：core 的 `PUT /api/sms/rules/{id}` 是**字段级合并**
 * （只覆盖请求体里给了值的字段）。`AppJson` 的 `encodeDefaults = true` 会把 null
 * 序列化成 `"field": null`，而 core 取值走 `contentOrNull` / `booleanOrNull`，
 * JsonNull 得到 null → 落到 `?: current.xxx` 保留原值 —— 语义正好是「没传就不改」。
 *
 * 所以「只切 enabled」传 `SmsRuleRequest(enabled = false)` 即可，不会把 pattern/scope 清掉。
 */
@Serializable
data class SmsRuleRequest(
    val pattern: String? = null,
    val scope: String? = null,
    @SerialName("match_type") val matchType: String? = null,
    val note: String? = null,
    val enabled: Boolean? = null
)

/**
 * 短信拦截记录（`GET /api/sms/blocked`）。
 *
 * [rulePattern] / [ruleScope] / [ruleMatch] 是**规则快照**：规则被删之后这条记录
 * 仍然读得懂「当时是被哪条规则拦的」，展示侧一律读快照，**不要**拿 [ruleId] 回查规则表。
 */
@Serializable
data class SmsBlockedLog(
    val id: Long = 0,
    /** 设备侧短信 id；邮件路径拿不到时为 0。 */
    @SerialName("msg_id") val msgId: Long = 0,
    val sender: String = "",
    /** 正文预览（120 字）。 */
    val snippet: String = "",
    /** 正文全文；受 core 的 `sms_filter_store_full_body` 控制，默认关 → 空串。 */
    val body: String = "",
    @SerialName("rule_id") val ruleId: Long = 0,
    @SerialName("rule_pattern") val rulePattern: String = "",
    @SerialName("rule_scope") val ruleScope: String = "",
    @SerialName("rule_match") val ruleMatch: String = "",
    /** 命中路径，逗号拼接：`mail` / `push` / `vc` 的任意组合。 */
    @SerialName("blocked_path") val blockedPath: String = "",
    @SerialName("blocked_at") val blockedAt: Long = 0
)

/**
 * 拦截记录分页响应（keyset 游标，**无 offset**）。
 *
 * 注意列表字段名是 `records` 而不是 `logs` —— 与 core `RootSmsRoutes` 的
 * `"records" to records` 一致。翻页把 [nextCursorTs] / [nextCursorId] 原样带回去即可。
 */
@Serializable
data class SmsBlockedListResponse(
    val records: List<SmsBlockedLog> = emptyList(),
    val count: Int = 0,
    /** 记录总条数（core 侧环形上限写死 500）。 */
    val total: Int = 0,
    @SerialName("next_cursor_ts") val nextCursorTs: Long? = null,
    @SerialName("next_cursor_id") val nextCursorId: Long? = null,
    @SerialName("has_more") val hasMore: Boolean = false
)

/**
 * 一条通知投递记录（core `mail_send_records` 表，`GET /api/sms-forward/history`）。
 *
 * 三条渠道（邮件 / Webhook / 本机短信）共用这一张表，用 [channel] 区分；结果是**三态**，
 * 判定走 [MailSendRecord.deliveryOutcome]：除了「发出」与「发起了但失败」，还有「渠道配好了，
 * 但这一条被闸门拦下、根本没发起」的跳过。core 只在渠道**已配置**时才记跳过行 ——
 * 否则没配 SMTP 的用户每来一条通知就多一行"跳过"，记录表会被刷满而没有任何信息量。
 *
 * 不含正文：正文可能带验证码与短信全文，历史只回答"发了什么主题、成没成、没成是为什么"。
 */
@Serializable
data class MailSendRecord(
    val id: Long = 0,
    /** 触发场景 id（与 `NotifyScene.sceneId` 对齐；设备短信为 sms / verification）。 */
    val scene: String = "",
    /**
     * 投递渠道 id：`mail` / `webhook` / `local_sms`（core DB v11 新增的 `channel` 列）。
     *
     * 缺省 `"mail"` 有两个来源，取值一致所以合并成一个默认：DB 那一列的 `DEFAULT 'mail'`
     * （升级前的存量行），以及不回这个键的老 core。
     */
    val channel: String = "mail",
    val subject: String = "",
    /** 收件地址 / 目标（邮件可能是逗号分隔的多个，Webhook 是请求地址，本机短信是号码）。 */
    val recipient: String = "",
    /**
     * 投递结果的**线上口径原值**（core DB v12 新增的 `outcome` 列）：
     * `sent` / `failed` / `skipped`，老 core 不回这个键时是空串。
     *
     * **不要直接比这个字符串做分类**，走 [deliveryOutcome] —— 那是全 app 唯一一处判定，
     * 它同时处理了"老 core 没有这一列"的情况。
     */
    val outcome: String = "",
    /**
     * [outcome] `== "sent"` 的副本。
     *
     * 三态之后它不足以分类（跳过行的 `success` 也是 false），保留有两个用处：
     * 老 core 没有 [outcome] 时它是唯一的判据（见 [deliveryOutcome]），以及不破坏既有调用方。
     */
    val success: Boolean = false,
    /**
     * 结果说明（≤200 字）：failed 时是异常链摊平，skipped 时是跳过原因的中文说明
     * （如「通知总开关已关闭」/「处于免打扰时段」），sent 时空串。
     *
     * 原因文案由 core 生成，app 侧**不维护第二张原因映射表** —— 抄一份的结果是
     * core 加了一种跳过原因、app 这边显示成空白或"未知"。2026-09-10 core 把原来的
     * 一档 `GATE`（"通知总开关关闭或处于免打扰时段"）拆成了两档，app 侧一行都不用改，
     * 正是因为这里只**显示**它、不按文案分类（分类只看 [outcome]，见 [deliveryOutcome]）。
     */
    val error: String = "",
    @SerialName("sent_at") val sentAt: Long = 0
)

/** 一条投递记录的三态结论。判定入口只有 [MailSendRecord.deliveryOutcome] 一处。 */
enum class DeliveryOutcome {
    /** 已发出。 */
    SENT,

    /** 发起了投递但失败（`error` 是异常链）。 */
    FAILED,

    /** 没发起，被闸门按用户配置拦下（`error` 是中文原因）。 */
    SKIPPED
}

/**
 * 把 [MailSendRecord.outcome] 读成三态结论。**全 app 唯一一处分类判定。**
 *
 * `outcome` 为空时回落到 [MailSendRecord.success]。这不是兼容层，是**外部响应的版本容错**：
 * app 与 core 是两个独立安装的 APK，用户完全可能 app 已经升到这一版、设备上的 core 还是
 * DB v11（没有 `outcome` 列）。而这一页是诊断页 —— 把「失败」渲染成「已发出」是它最不能犯的错，
 * 用户会因此停止排查。老 core 只记发起过投递的行，所以那时 `success` 就是完整判据，
 * 回落不会把任何东西说错（那种 core 根本不产生 skipped 行）。
 *
 * core 全量升到 v12（`outcome` 恒有值）之后，`outcome.isEmpty()` 这个分支可以删掉。
 */
val MailSendRecord.deliveryOutcome: DeliveryOutcome
    get() = when {
        outcome == OUTCOME_SKIPPED -> DeliveryOutcome.SKIPPED
        outcome == OUTCOME_FAILED -> DeliveryOutcome.FAILED
        outcome == OUTCOME_SENT -> DeliveryOutcome.SENT
        // outcome 缺失（老 core）：success 是唯一判据。
        success -> DeliveryOutcome.SENT
        else -> DeliveryOutcome.FAILED
    }

/**
 * `outcome` 列的线上口径值。
 *
 * 与 `GET /api/sms-forward/history` 的 `result` 查询参数**不是同一套**：已发出那一档在
 * `outcome` 里叫 `sent`，在 `result` 里叫 `success`（后者早于三态存在，改它会破坏跨端契约）。
 */
private const val OUTCOME_SENT = "sent"
private const val OUTCOME_FAILED = "failed"
private const val OUTCOME_SKIPPED = "skipped"

/**
 * 投递记录分页响应（keyset 游标，形态与 [SmsBlockedListResponse] 一致）。
 *
 * [failedTotal] / [skippedTotal] 都是**全表**计数而不是本页的 —— 入口行与筛选要用它们打标，
 * 只数当前页会随着翻页变动。
 */
@Serializable
data class MailHistoryListResponse(
    val records: List<MailSendRecord> = emptyList(),
    val count: Int = 0,
    val total: Int = 0,
    /** 全表失败条数（`outcome == "failed"`）。 */
    @SerialName("failed_total") val failedTotal: Int = 0,
    /**
     * 全表跳过条数（`outcome == "skipped"`）。
     *
     * 与 [failedTotal] **分列，且两者不能相加**：跳过是闸门按用户自己的配置拦下的
     * （总开关关着 / 场景没勾 / 级别不够 / 配额用尽），投递从未发起，
     * 并进失败数就等于把"按设置没发"报成"发送出了故障"。
     */
    @SerialName("skipped_total") val skippedTotal: Int = 0,
    @SerialName("next_cursor_ts") val nextCursorTs: Long? = null,
    @SerialName("next_cursor_id") val nextCursorId: Long? = null,
    @SerialName("has_more") val hasMore: Boolean = false
)

/**
 * 规则 / 记录的写入结果。
 *
 * `deleted = 0` 表示目标 id 已不存在，core 仍回 `success = true` ——
 * 目标状态「它不在列表里」已达成，客户端不该把这种情况当失败。
 */@Serializable
data class SmsFilterMutationResponse(
    val success: Boolean = false,
    val id: Long = 0,
    val deleted: Int = 0
)

// ========== Shell ==========

@Serializable
data class ShellExecRequest(
    val command: String,
    val as_root: Boolean = true,
    val timeout: Int = 30,
    /** 发起端标识，只用于终端历史列表展示「这条是谁发的」。 */
    val source: String = "app"
)

@Serializable
data class ShellExecResponse(
    val exit_code: Int,
    val stdout: String,
    val stderr: String,
    val success: Boolean
)

// ========== 终端命令历史（core 存，app / web 共享同一份） ==========

/**
 * 一条终端命令记录（对应 core 的 `ConsoleHistoryRecord`）。
 *
 * `exit_code` 可空：AT 通道与「被安全策略拦下」的记录没有退出码，
 * 不能用 0 顶替（0 是执行成功的真值）。
 */
@Serializable
data class ConsoleHistoryItem(
    val id: Long = 0,
    val channel: String = CONSOLE_CHANNEL_SHELL,
    val command: String = "",
    val as_root: Boolean = false,
    val exit_code: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val ok: Boolean = false,
    val truncated: Boolean = false,
    val duration_ms: Long = 0,
    val source: String = "unknown",
    val created_at: Long = 0
)

@Serializable
data class ConsoleHistoryResponse(
    val records: List<ConsoleHistoryItem> = emptyList(),
    val count: Int = 0,
    val total: Int = 0,
    val next_cursor_ts: Long? = null,
    val next_cursor_id: Long? = null,
    val has_more: Boolean = false
)

@Serializable
data class ConsoleHistoryImportRequest(val records: List<ConsoleHistoryItem>)

@Serializable
data class ConsoleHistoryImportResponse(
    val success: Boolean = false,
    val imported: Int = 0,
    val received: Int = 0
)

const val CONSOLE_CHANNEL_SHELL = "shell"
const val CONSOLE_CHANNEL_AT = "at"

// ========== AT ==========

@Serializable
data class AtCommandRequest(
    val command: String,
    /** 发起端标识，只用于终端历史列表展示「这条是谁发的」。 */
    val source: String = "app"
)

@Serializable
data class AtCommandResponse(
    val command: String,
    val response: String,
    val success: Boolean
)

@Serializable
data class AtStatusResponse(
    val connected: Boolean,
    val platform: PlatformInfo? = null
)

@Serializable
data class PlatformInfo(
    val connected: Boolean
)

// ========== Alerts ==========

@Serializable
data class AlertConfig(
    // 2026-08-09 19:57 字段全部加默认值（与后端 AlertEngine 默认值匹配）：
    // 前端 AppJson 虽启用 ignoreUnknownKeys+coerceInputValues，但 coerceInputValues 不处理缺失字段，
    // 缺字段时若字段无默认值会抛 MissingFieldException → alertsState.config=null → 4 类阈值不渲染。
    // 2026-08-23 P2 扩展：多端同步字段全部带默认值，旧 core 不返回也不崩。
    // 2026-09-07：默认 false（用户要求告警不默认开启，需手动打开）。
    // 与 core AlertEngine.AlertConfig.enabled / NotificationsGuardScreen 的 `?: false` 逐字一致。
    val enabled: Boolean = false,                // 总开关（core 端唯一真源）
    // 2026-09-08：删掉 notifyEnabled —— 它自称"投递开关"，但 core 全仓只有邮件转发读它，
    // WS 推送与入库都无条件执行。邮件是否放行改由 NotificationConfig 的
    // master_enabled + mail_respect_dnd 决定（判定只在 core 的 mailAllowed 一处）。
    val perType: Map<String, Boolean> = emptyMap(), // 分类开关 type->enabled
    val minIntervalSec: Int = 1800,              // 同 (type,level) 最小聚合间隔
    val configVersion: Long = 1L,                // 多端同步版本号（单调递增）
    val temperatureWarning: Double = 45.0,
    val temperatureCritical: Double = 55.0,
    val batteryWarning: Int = 20,
    val batteryCritical: Int = 10,
    val trafficWarningMb: Long = 1024,    // 1 GB
    val trafficCriticalMb: Long = 2048,   // 2 GB
    val signalWarningRsrp: Int = -100,
    val signalCriticalRsrp: Int = -115
)

@Serializable
data class AlertConfigUpdateResponse(
    val success: Boolean,
    val config: AlertConfig
)

@Serializable
data class AlertListResponse(
    val alerts: List<AlertRecord>,
    val count: Int,
    val total: Int = count,
    val counts: AlertCounts? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean = false
)

/** 告警聚合计数（对应 Core AlertEngine.AlertCounts；P1 列表 API 新增）。 */
@Serializable
data class AlertCounts(
    val total: Int = 0,
    val unread: Int = 0,
    val byType: Map<String, Int> = emptyMap(),
    val byLevel: Map<String, Int> = emptyMap()
)

@Serializable
data class AlertRecord(
    val id: Long,
    val type: String,
    val level: String,
    val message: String,
    val value: String,
    val threshold: String,
    val acknowledged: Boolean,
    val timestamp: Long,
    val count: Int = 1,
    val firstSeenAt: Long = timestamp,
    val resolvedAt: Long? = null
)

@Serializable
data class AckRequest(
    val id: Long
)

/**
 * 批量已读请求体（POST /api/alerts/ack-all）。
 * ids/type/level 至少传其一定位目标；unreadOnly 默认仅处理未读（避免重复 ack 已读项）。
 */
@Serializable
data class AckAllRequest(
    val ids: List<Long>? = null,
    val type: String? = null,
    val level: String? = null,
    val unreadOnly: Boolean = true
)

/**
 * 删除单条告警请求体（POST /api/alerts/delete）。
 *
 * 与 [AckRequest] 字段结构相同，但语义不同（删除 vs 已读），故独立成型：
 * 避免把「删除」误当「已读」调用，也便于后续扩展（如批量 ids）。
 */
@Serializable
data class AlertDeleteRequest(
    val id: Long
)

// ========== WiFi ==========

@Serializable
data class WifiSsidRequest(
    val ssid: String,
    val password: String? = null
)

@Serializable
data class WifiPasswordRequest(
    val password: String,
    val encryption: String = "WPA2-PSK"
)

// WiFi 设置 / 客户端列表的强类型响应见 DeviceResponses.kt
// （WifiSettingsResponse / WifiClientsResponse，阶段 4.3 从 typealias = JsonElement 收敛）

// ========== Device Control ==========

@Serializable
data class DeviceControlResponse(
    val success: Boolean
)

@Serializable
data class DeviceDebugResponse(
    val success: Boolean,
    val enabled: Boolean
)

/**
 * 服务状态（`GET /api/service/status`，以及 start/stop/autostart 的返回体）。
 * `enabled` = 用户可见的后台采集服务开关（core 侧持久化）；`collecting` = 采集循环实际运行状态。
 */
@Serializable
data class ServiceStatusResponse(
    val success: Boolean = true,
    val enabled: Boolean = true,
    val collecting: Boolean = false,
    @SerialName("http_running") val httpRunning: Boolean = true,
    @SerialName("uptime_ms") val uptimeMs: Long = 0L,
    @SerialName("auto_start_on_boot") val autoStartOnBoot: Boolean = true
)

/**
 * 上一次 core 崩溃信息（`GET /api/service/crash`，2026-09-04）。
 *
 * `timestamp` 是去重键：app 把它与 `AppPreferences.coreCrashShownAt` 比对，不同才弹窗。
 * 老 core 没有这个端点（404）→ 调用方按"没崩溃"处理即可。
 */
@Serializable
data class CoreCrashResponse(
    val success: Boolean = true,
    val crashed: Boolean = false,
    val timestamp: Long = 0L,
    val summary: String = "",
    /** 崩溃详情文件在设备上的绝对路径（提示用户去哪找，app 不直接读）。 */
    val file: String = "",
    @SerialName("uptime_ms") val uptimeMs: Long = 0L
)

/** `POST /api/service/restart` 的返回体（服务会在约 10 秒后自动恢复）。 */
@Serializable
data class ServiceRestartResponse(
    val success: Boolean = true,
    val restarting: Boolean = false,
    @SerialName("estimated_downtime_ms") val estimatedDowntimeMs: Long = 10_000L,
    val hint: String? = null
)

// ========== Network Control (new) ==========

@Serializable
data class BearerPreferenceResponse(
    val success: Boolean,
    val preference: String
)

@Serializable
data class ConnectionModeResponse(
    val success: Boolean,
    val mode: String
)

// ========== SMS Action ==========

@Serializable
data class SmsActionResponse(
    val success: Boolean,
    // 后端对 deleteSms / markSmsRead / markConversationRead 等动作接口
    // 不一定回写 id（仅返回 success），严格模式会抛
    // "Field 'id' is required ... but missing"。置为可空 + 默认值以容忍缺字段。
    val id: Long? = null
)

// ========== SIM Switch ==========

@Serializable
data class SimSwitchResponse(
    val success: Boolean,
    // core 返回的是 goform 卡槽字符串（"0"/"1"/"2"/"11"），不是数字：
    // `toJsonElement(mapOf("success" to success, "slot" to targetSlot))` 里 targetSlot 是 String。
    // 以前写成 Int，反序列化必然抛类型异常（调用方 try/catch 吞掉，表现为"切换失败"提示）。
    val slot: String
)

// ========== Config ==========

@Serializable
data class AppConfig(
    val port: Int,
    val auto_start_on_boot: Boolean,
    val goform_ip: String = "192.168.0.1",
    val goform_port: Int = 8080,
    val goform_password: String = "admin",
    val qos_enabled: Boolean = true,
    val qos_shell_max_concurrent: Int = 3,
    val qos_cache_ttl_ms: Int = 2000,
    val qos_goform_query_max: Int = 4,
    val qos_goform_set_max: Int = 2,
    val sms_code_enabled: Boolean = false,
    val sms_code_cleanup_hours: Int = 24,
    /** 自动复制验证码到剪贴板（需先开启系统无障碍服务，否则不生效） */
    val sms_code_auto_copy: Boolean = false,
    /**
     * 验证码豁免关键词拦截（core 真源 `AppSettings.smsFilterExemptVerificationCode`，默认 **true**）。
     *
     * 语义：被判定为验证码的短信跳过 `scope=body` 的关键词规则，但**仍受 `scope=sender`
     * 的号码黑名单约束** —— 拉黑号码是明确意图，不该被豁免绕过。
     *
     * 默认值必须与 core 一致（true）：不一致会让开关一进页面就显示成关，
     * 用户「打开」它其实什么都没改，正是「假开关」。
     */
    val sms_filter_exempt_verification_code: Boolean = true,
    // ── 日志四层开关（2026-09-04 全部改为可空）──
    // 为什么可空：这四个字段的唯一真源在 core，app 侧 `refreshDeviceConfig()` 会用它们**覆盖**
    // 本地缓存。原来它们是非空 + 默认 true，于是「core 响应里没有这个 key」和
    // 「core 明确说它是 true」在 app 看来完全一样 —— 任何字段缺失（老 core、响应被裁剪、
    // 契约漂移）都会把用户刚关掉的开关重新打开。null 现在的语义是「core 没给，别动本地」。
    /** 日志总开关（core 端真源；关闭后 core 不写缓冲/文件/logcat。app 侧 `AppPreferences.logEnabled` 是它的缓存） */
    val log_enabled: Boolean? = null,
    /** core 端日志子开关（从属于 log_enabled） */
    val core_log_enabled: Boolean? = null,
    /** app 端日志子开关（core 只替 app 保管真源，便于 web 一起控制、多机一致） */
    val app_log_enabled: Boolean? = null,
    /** 调试日志开关（core 端真源；app 侧 `AppPreferences.debugMode` 只是它的缓存） */
    val debug_mode: Boolean? = null,
    // 2026-08-12：后端更新源 / 镜像前缀（GET /api/config 返回；AppJson ignoreUnknownKeys 下缺省安全）
    val update_url: String = "",
    val update_mirror_base: String = ""
)

/**
 * `PUT /api/config` 被拒字段（C03）。
 * reason 取值见 core `ErrorCode`：OUT_OF_RANGE（带 min/max）/ MASKED_VALUE / BLANK_VALUE / WRONG_TYPE。
 * 改造前这些字段是"静默丢弃 + success:true"，客户端只能靠 updated_fields 反推且拿不到原因。
 */
@Serializable
data class ConfigRejectedField(
    val field: String,
    val reason: String,
    val min: Int? = null,
    val max: Int? = null
) {
    fun describe(): String = when (reason) {
        "OUT_OF_RANGE" -> "$field 取值需在 $min ~ $max 之间"
        "MASKED_VALUE" -> "$field 不能回写脱敏值"
        "BLANK_VALUE" -> "$field 不能为空"
        "WRONG_TYPE" -> "$field 值类型不正确"
        else -> "$field: $reason"
    }
}

@Serializable
data class ConfigUpdateResponse(
    val success: Boolean,
    val updated_fields: List<String>,
    val needs_restart: Boolean,
    val hint: String? = null,
    val rejected_fields: List<ConfigRejectedField> = emptyList()
)

@Serializable
data class ConfigResetResponse(
    val success: Boolean,
    val message: String
)

// ========== 邮件通知（路径仍是 /api/sms-forward，见 SmsForwardRoutes 说明） ==========

/**
 * 邮件通知配置。
 *
 * 2026-08-29：`method` / `curl_*` / `dingtalk_*` 全部删除 —— 渠道收敛为只有 SMTP 邮件。
 * 新增 [scenes]：允许转成邮件的 **app 通知场景 id**（`NotifyScene.sceneId`），
 * 空集合 = 只转发设备短信、不转发 app 通知。
 *
 * 2026-09-10「规则同构」：补上 [min_level] / [daily_limit] 两个旋钮与它们的只读伴生位。
 * 三条渠道（邮件 / Webhook / 本机短信）现在有**同一组**字段，界面因此可以共用同一批行
 * （见 `NotifyChannelRules.kt`）。区间与默认值刻意各不相同 —— 邮件是 0..500、默认不限，
 * 值域读 [daily_limit_min] / [daily_limit_max]，别在 app 里抄第二份。
 *
 * **本 DTO 同时是 GET 响应与 POST 请求体**，而 [sent_today] / [quota_remaining] 是只读位：
 * POST 时会被原样回传，**core 忽略它们**。契约上把只读位塞进写请求确实不干净
 * （Webhook / 本机短信那两条用独立的 `XxxConfigPatch` 分开了），但给邮件单开一个 patch DTO
 * 要动 core 的既有端点形状，收益不抵改动 —— 2026-09-10 裁决留着现状，别再"顺手重构"。
 *
 * 真正要紧的是 [min_level] / [daily_limit]：POST 发的是整个对象，未编辑时必须从 GET 的值
 * 原样带回，否则会被默认值覆盖。
 */
@Serializable
data class SmsForwardConfig(
    val enabled: Boolean = false,
    val smtp_host: String = "",
    val smtp_port: Int = 465,
    val smtp_user: String = "",
    val smtp_pass: String = "",
    val smtp_pass_set: Boolean = false,
    val smtp_from: String = "",
    val smtp_to: String = "",
    /**
     * 「能不能发出去」的判据 —— **core 的 `SmsForwardController.isSendable()` 那一份**
     * （enabled + smtp_host + smtp_user + smtp_pass + smtp_to 五项齐全）。
     *
     * 2026-09-11 新增。app 侧**不许**再用 `smtp_host.isBlank() || smtp_to.isBlank()`
     * 之类的组合自算一套：只填服务器与收件地址时那套会判"已配置"，而一封都发不出去。
     * 口径与 `/api/notify/{sms,webhook}/config` 的 `configured` 对齐（字段名沿用
     * `/diagnose` 已在用的 `sendable`）。老 core 不回这个字段时默认 false ——
     * 宁可显示"配置不完整"，也不要承诺一件发不出去的事。
     */
    val sendable: Boolean = false,
    val forward_dev_info: Boolean = false,
    val scenes: List<String> = emptyList(),
    /**
     * 最低级别的**线上口径小写名**（`info` / `warning` / `critical`）。
     * 传别的值 core 回 400；默认 `info` 与 core 一致。
     */
    val min_level: String = "info",
    /**
     * 每日上限，**0 = 不限**（邮件不花钱，"不限"是合法诉求）。
     *
     * 量词按「条」，与另两条渠道一致：core 的报错文案不带量词、web 那侧统一"条"，
     * 邮件单独说"封"会让同一个旋钮在三处出现三种叫法。
     */
    val daily_limit: Int = 0,
    /** 今天已发出几条（只读，按设备本地日期跨天重置）。 */
    val sent_today: Int = 0,
    /**
     * 今天还剩几条（只读）。**[daily_limit] = 0 时 core 回 null**，界面必须渲染成「不限」。
     *
     * 所以类型是 `Int?` 而不是 `Int = 0`：`AppJson` 开了 `coerceInputValues`，
     * 非空 Int 遇到 JSON null 会被悄悄当成 0 —— 那就把"不限"显示成"今天一条都不能发了"。
     */
    val quota_remaining: Int? = null,
    /** 级别取值域（渲染下拉用）：**对象数组**，中文名由 core 下发，见 [NotifyLevelOptionDto]。 */
    @Serializable(with = NotifyLevelOptionsSerializer::class)
    val levels: List<NotifyLevelOptionDto> = emptyList(),
    /** [daily_limit] 的允许区间（邮件当前 0..500）。 */
    val daily_limit_min: Int = 0,
    val daily_limit_max: Int = 500
)

@Serializable
data class SmsForwardSaveResponse(val success: Boolean)

// ========== Scheduled Tasks ==========

@Serializable
data class ExecutionLog(
    val id: String = "",
    val taskId: String = "",
    val timestamp: Long = 0,
    val success: Boolean = false,
    val output: String = ""
)

@Serializable
data class ScheduledTask(
    val id: String = "",
    val name: String = "",
    val actionType: String = "data_toggle",
    val params: Map<String, kotlinx.serialization.json.JsonPrimitive> = emptyMap(),
    val command: String = "",
    val hour: Int = 0,
    val minute: Int = 0,
    val repeatDaily: Boolean = true,
    // ── 时间触发扩展（v9 · 8 preset + 自定义 cron，向后兼容）──
    val triggerMode: String? = null,                          // "schedule" | "condition" | null=legacy
    val scheduleType: String? = null,                         // once/daily/weekly/monthly/every_n_days/.../custom
    val cron: String? = null,                                 // 5 段标准 cron；null=走旧路径
    val scheduleParams: Map<String, kotlinx.serialization.json.JsonPrimitive> = emptyMap(),
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val logs: List<ExecutionLog> = emptyList()
)

@Serializable
data class TaskListResponse(
    val tasks: List<ScheduledTask>,
    val count: Int
)

@Serializable
data class TaskCreateResponse(
    val success: Boolean,
    val id: String = ""
)

@Serializable
data class TaskLogsResponse(
    val logs: List<ExecutionLog>,
    val count: Int
)

// ========== Automation Rules (条件触发 · 当…就…) ==========
// 字段与后端 core/scheduler/AutomationRule.kt 严格对齐；
// triggerType v1 取值：traffic_total_reached / network_type_changed / signal_below / battery_below / disconnect
// 动作复用 ActionExecutor（与 ScheduledTask 同一套 actionType/params）。

@Serializable
data class AutomationRule(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    // —— 触发条件 ——
    val triggerType: String = "traffic_total_reached",
    val triggerParams: Map<String, JsonPrimitive> = emptyMap(),
    // —— 动作（复用 ActionExecutor）——
    val actionType: String = "data_toggle",
    val params: Map<String, JsonPrimitive> = emptyMap(),
    // —— 通用 ——
    val cooldownMs: Long = 60_000L,
    val createdAt: Long = 0L,
    val logs: List<ExecutionLog> = emptyList()
)

@Serializable
data class RuleListResponse(
    val rules: List<AutomationRule>,
    val count: Int
)

@Serializable
data class RuleLogsResponse(
    val logs: List<ExecutionLog>,
    val count: Int
)

@Serializable
data class RuleCreateResponse(
    val success: Boolean,
    val id: String = ""
)

// ========== Common ==========

@Serializable
data class HealthResponse(
    val status: String,
    val timestamp: String
)

@Serializable
data class ErrorResponse(
    val error: String
)

// ========== History ==========

@Serializable
data class CpuHistoryRecord(
    val id: Long,
    val usagePercent: Double,
    val coreCount: Int,
    val maxFreqMhz: Double,
    val temperature: Double,
    val timestamp: Long
)

@Serializable
data class CpuHistoryResponse(
    val records: List<CpuHistoryRecord>,
    val count: Int,
    val period_hours: Int
)

@Serializable
data class SignalHistoryRecord(
    val id: Long,
    val rsrp: Int,
    val sinr: Int,
    val rsrq: Int,
    val rssi: Int,
    val rat: String,
    val operator: String,
    val timestamp: Long
)

@Serializable
data class SignalHistoryResponse(
    val records: List<SignalHistoryRecord>,
    val count: Int,
    val period_hours: Int
)

// ========== Version ==========

@Serializable
data class ServerVersionInfo(
    val version: String,
    val min_client_version: String,
    val update_url: String
)

/** 设备更新状态（2026-08-10，对应后端 UpdateManager.UpdateStatus） */
@Serializable
data class UpdateStatusResponse(
    val state: String = "idle",           // idle/downloading/verifying/installing/done/failed
    val progress: Int = 0,
    val message: String = "",
    val current_version: String = "",
    val latest_version: String? = null,
    val apk_path: String? = null,
    val reconnecting: Boolean = false     // P0-7 前端断连过渡态：轮询失败连续 N 次置 true（设备重启中）
)

/** APK 上传结果（兜底推送） */
@Serializable
data class UpdateUploadResponse(
    val ok: Boolean = false,
    val apk_path: String? = null,
    val error: String? = null
)

/** 本地 APK 安装结果（兜底第二段） */
@Serializable
data class UpdateInstallResponse(
    val ok: Boolean = false,
    val status: UpdateStatusResponse? = null
)

/**
 * 前端 App 更新信息（2026-08-10 C5：经 Core 转发仓库根 version.json 的 frontend 对象；
 * 2026-08-12 对齐旧项目 UFITOOLS-Widget 的 camelCase 字段 apkUrl/apkSha256）。
 * 字段与后端 UpdateManager.FrontendUpdateInfo 保持一致（version/versionCode/changelog/apkUrl/apkSha256）；
 * 经 @JsonNames 同时兼容旧 snake_case（apk_url/sha256）命名。
 */
@Serializable
data class FrontendUpdateInfo(
    // C6：新格式 versionName（对齐旧项目 UFITOOLS-Widget），兼容旧 version
    @JsonNames("versionName") val version: String = "",
    val versionCode: Int = 0,
    val changelog: String = "",
    @JsonNames("apk_url") val apkUrl: String = "",
    @JsonNames("sha256") val apkSha256: String = ""
)

/**
 * 后端 core 自身的更新信息（`GET /api/update/backend-info`，2026-09-06）。
 *
 * 与 [FrontendUpdateInfo] 的区别：那个是「App 自己的新版本」（前端直连 GitHub 清单拿到），
 * 这个是「设备上跑的 core 有没有新版本」——由 core 读清单 backend 对象并**自己**做版本比对，
 * 所以 [has_update] 是唯一可信判据（App 侧不该拿 core 自报版本去跟任何常量比）。
 *
 * 只检查、不安装：真正的「下载+校验+安装+重启」仍是 `POST /api/update/check`
 * （[UpdateStatusResponse] 那条链路）。core 拉不到清单时本端点返回 502，Retrofit 会抛 HttpException。
 *
 * 字段用 snake_case 直接对齐 core 的响应体（与同文件 [UpdateStatusResponse] 同一口径）。
 */
@Serializable
data class BackendUpdateInfo(
    val current_version: String = "",
    val latest_version: String = "",
    val has_update: Boolean = false,
    val changelog: String = "",
    val apk_url: String = "",
    val apk_size: Long = 0L,
    val sha256: String = ""
)

/** 设备固件版本（来自 ZTE goform） */
@Serializable
data class DeviceVersionResponse(
    val language: String = "",
    val cr_version: String = "",
    val wa_inner_version: String = ""
)

// ========== Monitor ==========

typealias DownsampledPoint = com.ufi_axis_core.util.DownsampledPoint

@Serializable
data class MonitorHistoryResponse(
    val type: String,
    val points: List<DownsampledPoint>,
    val count: Int,
    val raw_count: Int,
    val period_hours: Int,
    val bucket_seconds: Int,
    /**
     * 服务端实际生效的桶宽（毫秒，2026-09-03 精度重写新增）。
     *
     * 有了它，UI 才能判断「相邻两点差得远 = 中间是空桶」并把折线断开，而不是把几小时的
     * 数据空洞连成一条直线。默认 0 是为了兼容还没升级的 core（字段缺失时不解析失败，
     * 0 表示未知 → 图表退回不断线的旧行为）。
     */
    val bucket_ms: Long = 0L
)

@Serializable
data class TableStorageInfo(
    val name: String,
    val count: Int,
    val size_kb: Double
)

@Serializable
data class MonitorStorageResponse(
    val tables: List<TableStorageInfo>,
    val total_kb: Double,
    val total_display: String
)

@Serializable
data class CleanHistoryRequest(
    val type: String? = null,
    val days: Int? = null
)

@Serializable
data class CleanHistoryResponse(
    val deleted: Map<String, Int>,
    val cutoff_days: Int
)

// ========== 配置备份与恢复（core 出包，客户端只递交自己那一段偏好） ==========

/** `GET /api/backup/info`：包格式与口令约束，以及本次请求是从局域网还是隧道进来的。 */
@Serializable
data class BackupInfoResponse(
    val format: Int = 0,
    /** `lan` / `tunnel`。隧道 + 明文 HTTP 时 UI 要提示"链路上是明文的"。 */
    val origin: String = "lan",
    val kdf_iterations: Int = 0,
    val min_passphrase_length: Int = 12,
    val max_upload_bytes: Long = 0L,
    val sensitive_keys: List<String> = emptyList()
)

/**
 * `POST /api/backup/export` 的请求体。
 *
 * [client] 的键必须是 core 认的客户端名（手机端固定 [BACKUP_CLIENT_APP]），值是这一端
 * 自己的偏好 JSON —— core 原样转存、不解析，恢复时原样回来由客户端自己落地。
 *
 * [acknowledge_plaintext]：`encrypted=false` 时 core 要求带 `true` 才肯出包，
 * 意思是「调用方已经让用户看见过明文风险」。本端由导出弹窗里那段 [UfiDialogWarning]
 * 承担提示职责，用户关掉「加密备份内容」开关即视为确认。
 */
@Serializable
data class BackupExportRequest(
    val encrypted: Boolean = true,
    val passphrase: String = "",
    val acknowledge_plaintext: Boolean = false,
    val client: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class BackupSectionSummary(
    val path: String = "",
    val label: String = "",
    val items: Int = 0,
    val sensitive_items: Int = 0
)

/** `POST /api/backup/preview`：只读清单，不落任何配置。 */
@Serializable
data class BackupPreviewResponse(
    val format: Int = 0,
    val created_at: Long = 0L,
    val device_id: String = "",
    val encrypted: Boolean = false,
    /** 备份包里的 device_id 与当前设备一致 —— 跨设备恢复时给用户一句提醒。 */
    val same_device: Boolean = false,
    val sections: List<BackupSectionSummary> = emptyList()
)

/** `POST /api/backup/import`：`client_app` / `client_web` 是**未解析的 JSON 文本**。 */
@Serializable
data class BackupImportResponse(
    val success: Boolean = false,
    val mode: String = BACKUP_MODE_MERGE,
    val applied: List<String> = emptyList(),
    val failed: Map<String, String> = emptyMap(),
    val needs_restart: Boolean = false,
    val client_app: String? = null,
    val client_web: String? = null
)

const val BACKUP_CLIENT_APP = "app"
const val BACKUP_MODE_MERGE = "merge"
const val BACKUP_MODE_REPLACE = "replace"
