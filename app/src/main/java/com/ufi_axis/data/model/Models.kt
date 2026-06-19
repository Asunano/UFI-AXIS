package com.ufi_axis.data.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

// ========== Device ==========

data class DeviceInfoResponse(
    val device: DeviceModel,
    val sim: SimState,
    val storage: StorageInfo,
    val uptime: UptimeInfo,
    val at_channel: AtChannelState,
    val kernel: String,
    val network: NetworkInfo
)

data class DeviceModel(
    val brand: String,
    val model: String,
    val device: String,
    val manufacturer: String,
    val android_version: String,
    val sdk_version: String,
    val build_id: String
)

data class SimState(
    val sim_state: String,
    val phone_type: String
)

data class AtChannelState(
    val connected: Boolean
)

// ========== System ==========

data class CpuInfo(
    val usage_percent: Double,
    val core_count: Int,
    val cores: List<CpuCore>,
    val temperature: Double = 0.0
)

data class CpuCore(
    val core: Int,
    val freq_mhz: Double,
    val freq_display: String
)

data class MemoryInfo(
    val total: Long,
    val available: Long,
    val free: Long,
    val buffers: Long,
    val cached: Long,
    val used: Long,
    val usage_percent: Double
)

data class BatteryInfo(
    val level: Int,
    val scale: Int,
    val percent: Int,
    val temperature: Double,
    val voltage: Double,
    val is_charging: Boolean,
    val plugged: String
)

data class StorageInfo(
    val total: Long,
    val available: Long,
    val used: Long,
    val usage_percent: Double
)

data class UptimeInfo(
    val uptime_seconds: Long,
    val uptime_display: String
)

// ========== Traffic ==========

data class TrafficRealtime(
    val rx_speed: Long,
    val tx_speed: Long,
    val rx_bytes: Long,
    val tx_bytes: Long,
    val rx_speed_display: String = "",
    val tx_speed_display: String = "",
    val timestamp: Long = 0L
)

data class TrafficHistoryResponse(
    val records: List<TrafficRecord>,
    val count: Int,
    val period_hours: Int
)

data class TrafficRecord(
    val id: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val rxSpeed: Long,
    val txSpeed: Long,
    val timestamp: Long
)

data class TrafficSummary(
    val total_rx_bytes: Long = 0,
    val total_tx_bytes: Long = 0,
    val total_bytes: Long = 0,
    val total_rx_display: String = "0 B",
    val total_tx_display: String = "0 B",
    val record_count: Int = 0,
    val today_rx_display: String = "0 B",
    val today_tx_display: String = "0 B",
    val month_rx_display: String = "0 B",
    val month_tx_display: String = "0 B"
)

data class TrafficLimitConfig(
    val enabled: Boolean = false,
    val limit_size: String = "",
    val limit_unit: String = "MB",
    val alert_percent: String = "80",
    val auto_clear: Boolean = false,
    val clear_date: String = "1",
    val monthly_rx_bytes: Long = 0,
    val monthly_tx_bytes: Long = 0,
    val monthly_time: Long = 0
)

// ========== Network ==========

data class SignalInfo(
    val rsrp: Int? = null,
    val sinr: Int? = null,
    val rsrq: Int? = null,
    val rssi: Int? = null,
    val rat: String? = null,
    val operator: String? = null,
    val network_registered: Boolean? = null
)

data class NetworkStatusResponse(
    val network: NetworkState,
    val mobile_data: Boolean,
    val operator: String,
    val network_type: String
)

data class NetworkState(
    val is_connected: Boolean,
    val has_internet: Boolean,
    val has_cellular: Boolean,
    val has_wifi: Boolean
)

data class NetworkInfo(
    val operator: String,
    val type: String,
    val connected: Boolean
)

data class SuccessResponse(
    val success: Boolean
)

data class EnabledResponse(
    val success: Boolean,
    val enabled: Boolean
)

data class AirplaneResponse(
    val success: Boolean,
    val airplane_mode: Boolean
)

data class BandRequest(
    val rat: String,
    val bands: String,
    val action: String = "lock"
)

data class ModeRequest(
    val mode: String
)

data class ModeResponse(
    val success: Boolean,
    val mode: String
)

// ========== SIM / SMS ==========

data class SimInfoResponse(
    val sim_state: String,
    val phone_type: String,
    val imei: String? = null,
    val imsi: String? = null
)

data class SmsSendRequest(
    val phone: String,
    val message: String
)

data class SmsSendResponse(
    val success: Boolean,
    val message: String? = null,
    val phone: String
)

data class SmsListResponse(
    val messages: List<SmsRecord>,
    val count: Int
)

data class SmsRecord(
    val id: Long,
    val direction: String,
    val phoneNumber: String,
    val content: String,
    val timestamp: Long,
    val source: String? = null,
    val read: Boolean = true
)

data class UssdRequest(
    val code: String
)

data class UssdResponse(
    val code: String,
    val response: String
)

// ========== AT ==========

data class AtCommandRequest(
    val command: String
)

data class AtCommandResponse(
    val command: String,
    val response: String,
    val success: Boolean
)

data class AtStatusResponse(
    val connected: Boolean,
    val platform: PlatformInfo? = null
)

data class PlatformInfo(
    val connected: Boolean
)

// ========== Alerts ==========

data class AlertConfig(
    val enabled: Boolean,
    val temperatureWarning: Double,
    val temperatureCritical: Double,
    val batteryWarning: Int,
    val batteryCritical: Int,
    val trafficWarningMb: Long,
    val trafficCriticalMb: Long,
    val signalWarningRsrp: Int,
    val signalCriticalRsrp: Int
)

data class AlertConfigUpdateResponse(
    val success: Boolean,
    val config: AlertConfig
)

data class AlertListResponse(
    val alerts: List<AlertRecord>,
    val count: Int
)

data class AlertRecord(
    val id: Long,
    val type: String,
    val level: String,
    val message: String,
    val value: String,
    val threshold: String,
    val acknowledged: Boolean,
    val timestamp: Long
)

data class AckRequest(
    val id: Long
)

// ========== WiFi ==========

data class WifiSsidRequest(
    val ssid: String,
    val password: String? = null
)

data class WifiPasswordRequest(
    val password: String,
    val encryption: String = "WPA2-PSK"
)

// WiFi 设置: 服务端直接返回 Goform 原始 JSON 对象
// 使用 JsonElement 兼容任意结构
typealias WifiSettingsResponse = JsonElement

// WiFi 客户端列表: 服务端直接返回 Goform 原始 JSON 对象
typealias WifiClientsResponse = JsonElement

// ========== Device Control ==========

data class DeviceControlResponse(
    val success: Boolean
)

data class DeviceDebugResponse(
    val success: Boolean,
    val enabled: Boolean
)

data class UsbModeResponse(
    val success: Boolean,
    val mode: Int
)

// ========== Network Control (new) ==========

data class BearerPreferenceResponse(
    val success: Boolean,
    val preference: String
)

data class ConnectionModeResponse(
    val success: Boolean,
    val mode: String
)

// ========== SMS Action ==========

data class SmsActionResponse(
    val success: Boolean,
    val id: Long
)

// ========== SIM Switch ==========

data class SimSwitchResponse(
    val success: Boolean,
    val slot: Int
)

// ========== Config ==========

data class AppConfig(
    val token: String,
    val secret: String,
    val port: Int,
    val auto_start_on_boot: Boolean,
    val rate_limit_max: Int,
    val rate_limit_window_sec: Int,
    val goform_ip: String = "192.168.0.1",
    val goform_port: Int = 8080,
    val goform_password: String = "admin"
)

data class ConfigUpdateResponse(
    val success: Boolean,
    val updated_fields: List<String>,
    val needs_restart: Boolean,
    val hint: String? = null
)

data class ConfigResetResponse(
    val success: Boolean,
    val message: String
)

// ========== ADB ==========

data class AdbStatus(
    val enabled: Boolean,
    val connected: Boolean,
    val port: Int,
    val last_ping_ms: Long
)

// ========== SMS Forward ==========

data class SmsForwardConfig(
    val enabled: Boolean = false,
    val method: String = "disabled",
    val smtp_host: String = "",
    val smtp_port: Int = 465,
    val smtp_user: String = "",
    val smtp_pass: String = "",
    val smtp_pass_set: Boolean = false,
    val smtp_from: String = "",
    val smtp_to: String = "",
    val curl_url: String = "",
    val curl_template: String = "from={{sms-from}}&content={{sms-body}}&time={{sms-time}}",
    val dingtalk_token: String = "",
    val dingtalk_secret: String = "",
    val dingtalk_secret_set: Boolean = false,
    val forward_dev_info: Boolean = false,
    val blacklist: List<String> = emptyList()
)

data class SmsForwardSaveResponse(val success: Boolean)

// ========== Scheduled Tasks ==========

data class ScheduledTask(
    val id: String = "",
    val name: String = "",
    val command: String = "",
    val hour: Int = 0,
    val minute: Int = 0,
    val repeatDaily: Boolean = true,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

data class TaskListResponse(
    val tasks: List<ScheduledTask>,
    val count: Int
)

data class TaskCreateResponse(
    val success: Boolean,
    val id: String = ""
)

// ========== Common ==========

data class HealthResponse(
    val status: String,
    val timestamp: String
)

data class ErrorResponse(
    val error: String
)

// ========== History ==========

data class CpuHistoryRecord(
    val id: Long,
    val usagePercent: Double,
    val coreCount: Int,
    val maxFreqMhz: Double,
    val temperature: Double,
    val timestamp: Long
)

data class CpuHistoryResponse(
    val records: List<CpuHistoryRecord>,
    val count: Int,
    val period_hours: Int
)

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

data class SignalHistoryResponse(
    val records: List<SignalHistoryRecord>,
    val count: Int,
    val period_hours: Int
)

// ========== Version ==========

data class ServerVersionInfo(
    val version: String,
    val min_client_version: String,
    val update_url: String
)

// ========== Monitor ==========

data class DownsampledPoint(
    val t: Long,
    val avg: Double,
    val min: Double,
    val max: Double
)

data class MonitorHistoryResponse(
    val type: String,
    val points: List<DownsampledPoint>,
    val count: Int,
    val raw_count: Int,
    val period_hours: Int,
    val bucket_seconds: Int
)

data class TableStorageInfo(
    val name: String,
    val count: Int,
    val size_kb: Double
)

data class MonitorStorageResponse(
    val tables: List<TableStorageInfo>,
    val total_kb: Double,
    val total_display: String
)

data class CleanHistoryRequest(
    val type: String? = null,
    val days: Int? = null
)

data class CleanHistoryResponse(
    val deleted: Map<String, Int>,
    val cutoff_days: Int
)
