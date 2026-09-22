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
 * 分段流量用量（`GET /api/traffic/usage?range=day|week|month|year&anchor=<epochMs>`）。
 *
 * 契约要点（core 侧保证，app **不自己算文案、也不补桶**）：
 * - [buckets] 一定是完整的（日 24 / 周 7 / 月 28~31 / 年 12），没数据的桶三个字节数都是 0，
 *   [TrafficUsageBucket.index] 从 0 连续、按时间升序 —— 客户端不需要"补空桶"这类逻辑。
 *   注意日桶在 DST 切换日是 23 / 25 个（core 的 `TrafficUsageWindow` 明确如此，硬凑 24 会让
 *   切换点之后每小时的流量错位一格），所以界面**不得假设恒 24**。
 * - [label] 是这一段的中文文案（"9月14日 周一" / "9月13日-9月19日" / "2026年9月" / "2026年"），
 *   [TrafficUsageBucket.label] 是 X 轴刻度文案，两者都直接展示。同一份文案两端各拼一遍的
 *   下场见 [TrafficLimitConfig] 的注释。
 * - [peak_bytes] = 所有桶 [TrafficUsageBucket.total_bytes] 的最大值（Y 轴峰值用）。
 * - [earliest_data_at] **可空**：null = 表里还没有任何记录。非 null 且晚于 [range_start] 时
 *   界面必须明说，否则用户会把"没记录"读成"那天没用流量"。
 */
@Serializable
data class TrafficUsageResponse(
    val range: String = "day",
    val range_start: Long = 0,
    val range_end: Long = 0,
    val label: String = "",
    /** 桶粒度：`hour` / `day` / `month`。 */
    val bucket_unit: String = "hour",
    val buckets: List<TrafficUsageBucket> = emptyList(),
    val total_rx_bytes: Long = 0,
    val total_tx_bytes: Long = 0,
    val total_bytes: Long = 0,
    val peak_bytes: Long = 0,
    /** 上一段的锚点（本段起点前 1ms）。左右翻页直接用它，客户端不做日历运算。 */
    val prev_anchor: Long = 0,
    /** 下一段的锚点（本段终点）。 */
    val next_anchor: Long = 0,
    /** 下一段是否已经开始。false 时不该让用户往未来翻 —— 翻过去只有一屏空柱。 */
    val has_next: Boolean = false,
    /** 有记录的最早时刻；null = 一条记录都还没有（**不是** 0，别拿 0 当哨兵）。 */
    val earliest_data_at: Long? = null
)

/** [TrafficUsageResponse] 的单个时间桶。 */
@Serializable
data class TrafficUsageBucket(
    val index: Int = 0,
    /** 桶起点（epoch ms，含）。 */
    val start: Long = 0,
    /** X 轴刻度文案，**带单位**（"12时" / "周一" / "14日" / "11月"），core 算好，直接用。 */
    val label: String = "",
    /**
     * 浮层与明细行用的完整文案（"12时" / "9月14日 周一" / "9月14日" / "2026年11月"）。
     *
     * 与 [label] 分开的理由见 core 的 `TrafficUsageBucket`：轴上要短，浮层里要能独立看懂。
     */
    val title: String = "",
    val rx_bytes: Long = 0,
    val tx_bytes: Long = 0,
    val total_bytes: Long = 0
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
    /** 回显入参别名；旧 core 可能不带，给默认空串避免整包解析失败。 */
    val mode: String = ""
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
    // 2026-09-21：删掉 minIntervalSec —— 它自称「同 (type,level) 最小聚合间隔秒」，
    // 但 core 的聚合走 `bumpExisting`（同 type+level 未确认行就累加），压根没有时间窗，
    // 引擎从来不读这个字段。第三个被清掉的假开关（前两个是 edgeTriggeredOnly / maxRows）。
    val configVersion: Long = 1L,                // 多端同步版本号（单调递增）
    // 温度阈值 2026-09-21 从 45/55 重定为 65/75：旧值与 core 温控熔断的
    // monitorThermalWarnC=70 自相矛盾，而 Unisoc 稳态就在 50~60°C（开机后即恒 critical）。
    // 必须与 core AlertEngine.AlertConfig 逐字一致 —— 这份是镜像，不是第二真源。
    val temperatureWarning: Double = 65.0,
    val temperatureCritical: Double = 75.0,
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
    // ── 短信四项（2026-09-14 全部改为可空，理由同下面的日志四层开关）──
    // 这四项真源在 core，`refreshDeviceConfig()` 会用它们覆盖本地镜像。原来非空 + 有默认值，
    // 于是「core 响应里没有这个 key」与「core 明确说它是这个值」不可区分 —— 老 core / 裁剪过的
    // 响应会把用户刚开的开关按默认值刷回去。null 现在的语义是「core 没给，别动本地镜像」。
    val sms_code_enabled: Boolean? = null,
    val sms_code_cleanup_hours: Int? = null,
    /** 自动复制验证码到剪贴板（需先开启系统无障碍服务，否则不生效） */
    val sms_code_auto_copy: Boolean? = null,
    /**
     * 验证码豁免关键词拦截（core 真源 `AppSettings.smsFilterExemptVerificationCode`，默认 **true**）。
     *
     * 语义：被判定为验证码的短信跳过 `scope=body` 的关键词规则，但**仍受 `scope=sender`
     * 的号码黑名单约束** —— 拉黑号码是明确意图，不该被豁免绕过。
     *
     * 本地镜像的默认值在 `AppPreferences.smsFilterExemptVerificationCode`（true），
     * 必须与 core 一致：不一致会让开关一进页面就显示成关，
     * 用户「打开」它其实什么都没改，正是「假开关」。
     */
    val sms_filter_exempt_verification_code: Boolean? = null,
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

/** 删单条投递记录响应（`DELETE /api/sms-forward/history/{id}`）。 */
@Serializable
data class MailHistoryDeleteResponse(
    val success: Boolean = false,
    /** 受影响行数：0 = 目标已不存在（仍视为 success）。 */
    val deleted: Int = 0
)

/**
 * 渠道投递统计（`GET /api/sms-forward/history/stats?channel=`）。
 *
 * 与邮件 `/diagnose` 的 `sent_*` 不同：这里**含 skipped**，口径与投递记录列表一致，
 * 给三条渠道配置页的统计卡用。
 */
@Serializable
data class MailHistoryStatsResponse(
    val total: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    @SerialName("last_sent_at") val lastSentAt: Long = 0L
)

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

/**
 * EPS 承载 QoS（`GET /api/device/qos`，2026-09-22）。
 *
 * core 侧用 `AT+CGEQOSRDP` 查当前承载协商到的 QoS，取默认承载（优先 cid=1）。
 * 仅供仪表盘设备信息卡显示。
 *
 * ## 失败也是 200
 * AT 通道在很多设备上根本不存在（非展锐平台 / HAL 被裁），那是预期状态而不是错误。
 * 此时 core 回的仍是这个形状，只是 [available] = false 且各值为 0/空 ——
 * UI 只需要显示占位，不必写第二套分支。
 *
 * @param available AT 通道可用（false = 这台设备不支持，不是"这次查失败了"）。
 * @param cid 承载 id。排查"为什么 QCI 是 5 不是 8"时要靠它确认取的是哪条承载。
 * @param downlink_display / uplink_display 展示文案由 **core 生成**（如 `500 Mbps`）——
 *   两端各写一份 kbps→Mbps 换算迟早出现"一个 500 一个 500.0"。
 */
@Serializable
data class DeviceQosResponse(
    val available: Boolean = false,
    val cid: Int = 0,
    val qci: Int = 0,
    val downlink_kbps: Long = 0,
    val uplink_kbps: Long = 0,
    val downlink_display: String = "",
    val uplink_display: String = ""
) {
    /** 有没有真正解析出一条承载。QCI 合法值从 1 起，0 表示没查到。 */
    val hasData: Boolean get() = available && qci > 0
}

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

// ========== Media Center（媒体中心，2026-09-16）==========
//
// 数据全部由 core 的 `/api/media` 一组端点给出（它查系统媒体库 MediaStore）。
// **播放 / 查看仍走 `/api/files/stream?path=`** —— 那条链路已经支持 Range 与签名鉴权，
// 本组模型里不出现第二种取字节流的方式。

/** 媒体类型（与 core 的 `type` 参数取值一致）。 */
const val MEDIA_TYPE_VIDEO = "video"
const val MEDIA_TYPE_AUDIO = "audio"
const val MEDIA_TYPE_IMAGE = "image"

/**
 * 音频分组维度（`GET /api/media/groups` 的 `by` 取值）。
 *
 * 只对 [MEDIA_TYPE_AUDIO] 有效 —— 视频 / 图片在 MediaStore 里没有专辑、歌手这类标签，
 * core 侧对其它 type 直接拒绝，所以不必为它们留取值。
 */
const val MEDIA_GROUP_ALBUM = "album"
const val MEDIA_GROUP_ARTIST = "artist"
const val MEDIA_GROUP_FOLDER = "folder"

/**
 * 媒体库里的一项。
 *
 * 刻意叫 `MediaLibraryItem` 而不是 `MediaItem`：后者是 media3 的核心类型
 * （`androidx.media3.common.MediaItem`），播放页里两者会同时出现。
 *
 * [id] 是 MediaStore 的行 id，缩略图接口按它取（`/api/media/thumbnail?id=`）；
 * [path] 是设备上的真实路径，播放 / 跳文件管理器按它走。
 * [durationMs] 对图片恒为 0，[width]/[height] 对音频恒为 0 —— core 按类型只回有意义的字段，
 * 缺的字段由这里的默认值补上（不是"该项没有时长"，而是"这个类型没有这个概念"）。
 */
@Serializable
data class MediaLibraryItem(
    val id: Long = 0,
    val name: String = "",
    val path: String = "",
    val size: Long = 0,
    /** 修改时间（**毫秒**；core 已把 MediaStore 的秒换算过了）。 */
    val date_modified: Long = 0,
    val mime: String = "",
    val duration_ms: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val album: String = "",
    val artist: String = "",
    /**
     * 内嵌标签里的曲名（**仅音频**，core 从 `MediaStore.Audio.Media.TITLE` 取）。
     *
     * 与 [name] 的区别：[name] 是文件名（`带我走-杨丞琳.flac`），这个才是歌名（`带我走`）。
     * 取不到时是空串 —— core 不拿文件名冒充曲名，兜底显示由客户端决定
     * （见 `audioDisplayTitle`）。
     */
    val title: String = "",
    /**
     * 这个视频有几个外挂字幕（**仅视频**，core 按文件名判定，见 `/api/media/subtitles`）。
     *
     * 只用来在列表上打个"CC"标 —— 想知道具体有哪些、走哪个 URL，得单独问 `/subtitles`。
     * 0 不等于"一定没字幕"：内嵌在容器里的字幕轨不算在这里（那个由播放器自己发现）。
     */
    val subtitle_count: Int = 0,
    /**
     * 这一项在媒体库里**已经查不到了**（仅出现在歌单曲目响应里，见 [PlaylistItemsResponse]）。
     *
     * 歌单存的是路径，文件被删 / 卡没插 / 还没被系统扫到时 core 就回不出完整信息，
     * 于是带上这个标记 + 加入歌单时的快照字段（[title]/[artist]/[album]/[duration_ms]），
     * 此时 [id] 恒为 0。UI 要画"已失效"占位并禁止播放，**不要自动帮用户移出** ——
     * 拔一次卡就清空歌单是不可接受的。
     *
     * 其它端点（`/list`、`/browse`）永远是 false。
     */
    val missing: Boolean = false
)

/**
 * 一条外挂字幕（`GET /api/media/subtitles` 的 items 元素）。
 *
 * [supported] = 播放器能不能解析这个格式。false 的条目 core 也会返回
 * （MicroDVD `.sub`、SAMI `.smi`）—— 目录里明明有文件却在界面上查无此项，
 * 用户只会以为 App 瞎了，所以照常列出但标成"格式不支持"。
 *
 * [label] 是从文件名里猜的语言/版本标记（`movie.zh-CN.srt` → `zh-CN`），
 * 猜错了不影响播放（播放看 [mime]），只影响字幕轨列表上显示的那个名字。
 */
@Serializable
data class MediaSubtitleEntry(
    val name: String = "",
    val path: String = "",
    val ext: String = "",
    val size: Long = 0,
    val supported: Boolean = false,
    val mime: String = "",
    val label: String = ""
)

/**
 * `GET /api/media/subtitles` 的结果。
 *
 * [scope] 回显请求的取法：`matched` = 只有判定属于该视频的（自动挂载用），
 * `folder` = 同目录全部字幕（手动选择用）。
 */
@Serializable
data class MediaSubtitlesResponse(
    val path: String = "",
    val scope: String = "matched",
    val items: List<MediaSubtitleEntry> = emptyList()
)

/** `GET /api/media/list` 的一页。[total] 是**符合条件的总数**，不是这一页的条数。 */
@Serializable
data class MediaListResponse(
    val type: String = MEDIA_TYPE_VIDEO,
    val items: List<MediaLibraryItem> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val scan_dirs: List<String> = emptyList()
)

/**
 * 媒体库文件夹视图里的一个子目录（`GET /api/media/browse`）。
 *
 * [count] 是**整棵子树**里该类型的文件数（不只这一层），所以"里面有 12 个"这种文案是准的；
 * [cover_id] 是子树里最新那一个文件的 MediaStore id —— 文件夹卡片直接拿它当封面，
 * 不必再为目录另造一套缩略图。没有可用封面时是 null。
 */
@Serializable
data class MediaFolderEntry(
    val name: String = "",
    val path: String = "",
    val count: Int = 0,
    val cover_id: Long? = null,
    val date_modified: Long = 0
)

/**
 * `GET /api/media/browse?type=&path=`：某一层目录里的子目录 + 媒体文件。
 *
 * 与 [MediaListResponse] 的分工：那个是"整库平铺 + 分页"，这个是"这一层有什么"（不分页，
 * 单层条数有上限）。[parent] 为 null 表示已经在根上，界面不该再显示"返回上一级"。
 * [roots] 非空且 [path] 为空 = 配了多个扫描目录，先让用户选一个根。
 */
@Serializable
data class MediaBrowseResponse(
    val type: String = MEDIA_TYPE_VIDEO,
    val path: String = "",
    val parent: String? = null,
    val roots: List<String> = emptyList(),
    val folders: List<MediaFolderEntry> = emptyList(),
    val items: List<MediaLibraryItem> = emptyList()
)

/**
 * 一个音频分组（`GET /api/media/groups` 的 groups 元素）。
 *
 * [key] 是**回查用的原值**，不是给人看的：album / artist 是标签原始字符串，folder 是目录
 * 绝对路径。要列这一组的曲目，就把它按维度送回 `/api/media/list` 的 album / artist / dir。
 *
 * [key] 为**空串**的那一组是"无标签文件的聚合"（[title] 会是"未知专辑"这类兜底文案）：
 * 它**只能展示、不能回查** —— 空串送回 `/list` 会被当成"没传这个参数"，结果是整库而不是
 * 这一组，所以界面上不该让它可点。
 *
 * [count] 是这一组里的曲目数；[cover_id] 是组内某一首的 MediaStore id，分组卡片直接拿它
 * 当封面（走 `/api/media/cover?id=`），0 表示没有可用封面。
 */
@Serializable
data class MediaGroupEntry(
    val key: String = "",
    val title: String = "",
    val subtitle: String = "",
    val count: Int = 0,
    val cover_id: Long = 0
)

/**
 * `GET /api/media/groups?type=audio&by=`：按专辑 / 歌手 / 文件夹聚合的分组列表。
 *
 * [total] 是**分组总数**，不是曲目总数 —— 拿它显示"共 N 首"是错的（那要把
 * [MediaGroupEntry.count] 累加）。
 *
 * 不分页：core 一次算完整库的聚合结果，所以这里没有 limit / offset。
 * 与 [MediaListResponse] 的分工：那个是"某一组/整库里有哪些曲目（分页）"，这个是"有哪些组"。
 */
@Serializable
data class MediaGroupsResponse(
    val type: String = MEDIA_TYPE_AUDIO,
    val by: String = MEDIA_GROUP_ALBUM,
    val groups: List<MediaGroupEntry> = emptyList(),
    val total: Int = 0
)

/**
 * `GET /api/media/status`：三类媒体各自的授权状态与扫描范围。 *
 * [granted] 与 [scan_dirs] 的 key 都是 [MEDIA_TYPE_VIDEO] 等。某一类 granted 为 false 时
 * 对应页面必须显示未授权引导 —— 显示空列表等于告诉用户"设备里没有视频"。
 *
 * [scan_dirs] 自 2026-09-16 起**按类型各一份**（媒体中心拆成三个独立页，每页管自己的范围）；
 * 某一类为空数组 = 这一类不限目录。
 */
@Serializable
data class MediaStatusResponse(
    val all_files_access: Boolean = false,
    val granted: Map<String, Boolean> = emptyMap(),
    val scan_dirs: Map<String, List<String>> = emptyMap(),
    val sdk_int: Int = 0
)

/** `GET/PUT /api/media/config?type=`：某一类的扫描目录（空 = 这一类不限目录）。 */
@Serializable
data class MediaConfigResponse(
    val success: Boolean = false,
    val type: String = MEDIA_TYPE_VIDEO,
    val dirs: List<String> = emptyList()
)

/** `PUT /api/media/config` 与 `POST /api/media/rescan` 的请求体。 */
@Serializable
data class MediaDirsRequest(
    val dirs: List<String> = emptyList()
)

/**
 * `POST /api/media/rescan`：请系统重新收录这些目录。
 *
 * [submitted] 是提交给系统扫描器的文件数；[truncated] 为 true 说明撞到了 core 的单次上限，
 * 需要再点一次或缩小目录范围。收录是异步的 —— 返回成功不代表 `/list` 立刻就能查到。
 */
@Serializable
data class MediaRescanResponse(
    val success: Boolean = false,
    val type: String = MEDIA_TYPE_VIDEO,
    val dirs: List<String> = emptyList(),
    val submitted: Int = 0,
    val truncated: Boolean = false
)

/**
 * `GET /api/media/lyrics`：旁挂歌词文件（同名 `.lrc` / `.txt`）。
 *
 * [found] 为 false 时 [text] 是空串 —— 播放页要显示"没有歌词"，不许拿别的文本凑。
 * [source] 是命中的文件名（例如 `xxx.lrc`），用于在界面上说明歌词来自哪里。
 */
@Serializable
data class MediaLyricsResponse(
    val found: Boolean = false,
    val source: String = "",
    val text: String = ""
)

/**
 * `GET /api/media/tags` 的响应：单首音频的标签。
 *
 * core 端已经把「MediaStore 那份 + 直接读文件解出来的」合并过了（文件里的优先），
 * 所以客户端拿到就能显示，不必等播放器解容器。取不到的字段是空串 ——
 * **不拿文件名冒充曲名**，兜底显示由 UI 层决定。
 */
@Serializable
data class MediaTagsResponse(
    val id: Long = 0,
    val name: String = "",
    val path: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration_ms: Long = 0
)

// ========== 音频歌单（2026-09-21）==========
//
// core 侧 `PlaylistRoutes`（`/api/playlists`）。歌单**存在 core**，两端看到同一份。
// 曲目以真实路径为标识（不是 MediaStore id —— 那个重扫会变，歌单会整份失效），
// 所有写操作的 body 都是 `{ paths: [...] }`。

/** 歌单列表里的一项（不含曲目明细）。 */
@Serializable
data class PlaylistEntry(
    val id: String = "",
    val name: String = "",
    /** 曲目数，**含已失效的** —— 拔一次卡不该让"共 23 首"变少。 */
    val count: Int = 0,
    /**
     * 首曲的 MediaStore id，给封面用（走 `/api/media/cover?id=`）。
     * 0 = 没有可用封面（空歌单，或首曲已不在媒体库）。与 [MediaGroupEntry.cover_id] 同一约定。
     */
    val cover_id: Long = 0,
    val created_at: Long = 0,
    val updated_at: Long = 0
)

/** `GET /api/playlists`：全部歌单。 */
@Serializable
data class PlaylistsResponse(
    val playlists: List<PlaylistEntry> = emptyList(),
    val count: Int = 0
)

/**
 * `GET /api/playlists/{id}/items`：歌单曲目。
 *
 * [items] 的顺序**就是播放顺序**（用户手排的）—— 不要再按音乐页的排序偏好重排一次。
 * 已失效的条目照样在列表里，带 [MediaLibraryItem.missing] = true，[missing_count] 是它们的条数。
 */
@Serializable
data class PlaylistItemsResponse(
    val id: String = "",
    val name: String = "",
    val items: List<MediaLibraryItem> = emptyList(),
    val total: Int = 0,
    val missing_count: Int = 0,
    val created_at: Long = 0,
    val updated_at: Long = 0
)

/** `POST /api/playlists`（新建）与 `PUT /api/playlists/{id}`（重命名）的请求体。 */
@Serializable
data class PlaylistNameRequest(val name: String)

/** `POST /api/playlists`：新建成功后回的 id。 */
@Serializable
data class PlaylistCreateResponse(
    val success: Boolean = false,
    val id: String = "",
    val name: String = ""
)

/**
 * 歌单曲目的写请求体：加歌 / 移出 / 整表重排共用。
 *
 * [position] 只对加歌有效（null = 追加到尾部）。移出与重排忽略它。
 */
@Serializable
data class PlaylistPathsRequest(
    val paths: List<String>,
    val position: Int? = null
)

/**
 * 加歌的结果。
 *
 * 三个计数对应三种"没进去"的原因，客户端据此给准确文案：
 * [added] 真加进去的、[skipped] 已在歌单里**或**不在媒体库里的、[truncated] 撞到单歌单条数上限。
 */
@Serializable
data class PlaylistAddResponse(
    val success: Boolean = false,
    val added: Int = 0,
    val skipped: Int = 0,
    val truncated: Boolean = false,
    val total: Int = 0
)

/** 移出曲目的结果。[removed] = 实际命中并移出的条数。 */
@Serializable
data class PlaylistRemoveResponse(
    val success: Boolean = false,
    val removed: Int = 0
)

/** `POST /api/files/stream-ticket` 的请求体。 */
@Serializable
data class StreamTicketRequest(val path: String)
/**
 * `POST /api/files/stream-ticket`：换一张**免鉴权**的播放票据。
 *
 * 用途（2026-09-16）：手机端给设备解不出画面的视频抽缩略图时，要把地址交给
 * `MediaMetadataRetriever`，而它自己发 HTTP 请求、**加不了签名头**；签名里的 nonce 又是
 * 一次性的，多个 Range 请求必然从第二个开始被拒。票据正是为这种客户端准备的：
 * 可重复使用、滑动过期、只授权这一个文件。
 *
 * [url] 是 core 给出的相对路径（`/media/stream?ticket=…`），客户端拼上 host:port 即可。
 */
@Serializable
data class StreamTicketResponse(
    val ticket: String = "",
    val url: String = "",
    val expires_in: Long = 0,
    val size: Long = 0,
    val mime: String = ""
)

/** `PUT /api/media/thumbnail`：客户端把抽好的缩略图交给 core 缓存。 */
@Serializable
data class MediaThumbUploadResponse(
    val success: Boolean = false,
    val type: String = MEDIA_TYPE_VIDEO,
    val id: Long = 0,
    val size: Int = 0
)

// ══════════════════════════ 天气（2026-09-17）══════════════════════════
//
// core 侧代理 Open-Meteo，app 只做展示。字段口径与 WeatherRoutes.parseForecast 一一对应，
// **描述文案与 weather_code → 中文的映射都在 core**，app 不再自己翻一份。

/**
 * `GET /api/weather`。
 *
 * [configured]=false 表示设备还没设置城市 —— 这不是错误态，UI 该引导去设置而不是报错，
 * 此时除 [enabled] / [message] 外其余字段都是默认值。
 */
@Serializable
data class WeatherNowResponse(
    val configured: Boolean = false,
    val enabled: Boolean = false,
    val message: String = "",
    val city: String = "",
    val temperature: Double = 0.0,
    val apparent_temperature: Double = 0.0,
    val humidity: Int = 0,
    val precipitation: Double = 0.0,
    val wind_speed: Double = 0.0,
    /** WMO 天气代码，用于选图标；文案直接用 [description]。 */
    val weather_code: Int = -1,
    val description: String = "",
    val is_day: Boolean = true,
    val temp_max: Double = 0.0,
    val temp_min: Double = 0.0,
    /** ISO8601 本地时间字符串（`2026-09-17T05:42`），core 已按设备时区换算。 */
    val sunrise: String = "",
    val sunset: String = "",
    /** 逐小时温度曲线（从当前小时起的 24 个点，ISO8601 本地时间戳）。 */
    val hourly_times: List<String> = emptyList(),
    /** 逐小时温度（与 [hourly_times] 等长，单位跟随 [unit]）。 */
    val hourly_temperatures: List<Double> = emptyList(),
    val unit: String = "celsius",
    val timezone: String = "",
    val updated_at: Long = 0
)

/** `GET /api/weather/config` / `PUT /api/weather/config`。坐标 `0,0` = 未设置。 */
@Serializable
data class WeatherConfigResponse(
    val enabled: Boolean = false,
    val city: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val unit: String = "celsius"
)

/** `PUT /api/weather/config` 的请求体：字段级合并，只传要改的。 */
@Serializable
data class WeatherConfigRequest(
    val enabled: Boolean? = null,
    val city: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val unit: String? = null
)

/** `PUT /api/weather/config` 的响应。 */
@Serializable
data class WeatherConfigUpdateResponse(
    val success: Boolean = false,
    val config: WeatherConfigResponse = WeatherConfigResponse()
)

/** `GET /api/weather/search` 的一条结果。[admin1] 是省/州，用来区分同名城市。 */
@Serializable
data class WeatherCity(
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val country: String = "",
    val admin1: String = "",
    val timezone: String = ""
)

/** `GET /api/weather/search?name=`。 */
@Serializable
data class WeatherSearchResponse(
    val results: List<WeatherCity> = emptyList(),
    val total: Int = 0
)

// ══════════════════════════ 今日诗词（2026-09-18）══════════════════════════
//
// core 侧代理 jinrishici v2。标签匹配（季节 / 天气 / 时辰 / 地理）由**上游按设备 IP
// 自动完成**，命中的标签在 [PoetryResponse.match_tags] 里回传 —— 我们既不传 tag、
// 也不自己算季节，v2 接口本身没有 tag 参数。

/** `GET /api/poetry`。 */
@Serializable
data class PoetryResponse(
    /** 推荐的那一句：标题栏下方小字显示的就是它。 */
    val content: String = "",
    val title: String = "",
    val dynasty: String = "",
    val author: String = "",
    /** 全篇原文，可能多段。详情用。 */
    val full_content: List<String> = emptyList(),
    /** 整诗翻译，部分诗词才有。 */
    val translate: List<String> = emptyList(),
    /** 上游据此推荐的标签，可当"推荐理由"显示，如 `["桂花","秋","晚上"]`。 */
    val match_tags: List<String> = emptyList(),
    val popularity: Int = 0,
    val updated_at: Long = 0
) {
    /** `《夜雨寄北》· 唐代 · 李商隐`；出处字段缺失时自动省略那一段。 */
    val originLine: String
        get() = listOfNotNull(
            title.takeIf { it.isNotBlank() }?.let { "《$it》" },
            dynasty.takeIf { it.isNotBlank() },
            author.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
}

/** `GET /api/poetry/config` / `PUT /api/poetry/config`。 */
@Serializable
data class PoetryConfigResponse(
    val enabled: Boolean = false,
    val show_origin: Boolean = true
)

/** `PUT /api/poetry/config` 的请求体：字段级合并，只传要改的。 */
@Serializable
data class PoetryConfigRequest(
    val enabled: Boolean? = null,
    val show_origin: Boolean? = null
)

/** `PUT /api/poetry/config` 的响应。 */
@Serializable
data class PoetryConfigUpdateResponse(
    val success: Boolean = false,
    val config: PoetryConfigResponse = PoetryConfigResponse()
)

// ══════════════════════════ 出网国家/地区（2026-09-18）══════════════════════════
//
// 检测在 core（`/api/geo`）：判据是出网 IP 的归属，而设备才是出网点。app 只读结果，
// 唯一消费方是更新源自动选择（见 [com.ufi_axis.util.UpdateSource]）。

/** `GET /api/geo` / `POST /api/geo/detect`。 */
@Serializable
data class GeoResponse(
    /** ISO 3166-1 alpha-2（如 `CN` / `US`）；`""` = core 也没测出来。 */
    val country: String = "",
    /** 上次成功检测的时刻（epoch ms），0 = 从未测过。 */
    val detected_at: Long = 0,
    /** `cache`（读的是落盘结果）/ `fresh`（本次刚出网测过）/ `unknown`（从未测出结果）。 */
    val source: String = ""
)

