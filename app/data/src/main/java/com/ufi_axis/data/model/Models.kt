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

// ========== Shell ==========

@Serializable
data class ShellExecRequest(
    val command: String,
    val as_root: Boolean = true,
    val timeout: Int = 30
)

@Serializable
data class ShellExecResponse(
    val exit_code: Int,
    val stdout: String,
    val stderr: String,
    val success: Boolean
)

// ========== AT ==========

@Serializable
data class AtCommandRequest(
    val command: String
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
    val notifyEnabled: Boolean = true,           // 投递开关（in-app + 系统通知）
    val perType: Map<String, Boolean> = emptyMap(), // 分类开关 type->enabled
    val minIntervalSec: Int = 1800,              // 同 (type,level) 最小聚合间隔
    val edgeTriggeredOnly: Boolean = true,       // 仅状态跃迁触发
    val maxRows: Int = 2000,                     // 环形上限
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
    val forward_dev_info: Boolean = false,
    val blacklist: List<String> = emptyList(),
    val scenes: List<String> = emptyList()
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
