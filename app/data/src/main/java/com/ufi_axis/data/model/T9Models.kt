package com.ufi_axis.data.model

import com.ufi_axis.data.api.FileItem
import kotlinx.serialization.Serializable

// =========================================================================
// T9 — Gson → kotlinx.serialization 统一迁移：模式 B 结构化响应模型
//
// 全部使用 `AppJson`（isLenient=true / ignoreUnknownKeys=true / coerceInputValues=true）
// 配置进行序列化（isLenient / ignoreUnknownKeys / coerceInputValues），可无缝替换原 Gson 解析。
// 这些模型由 `AppJsonConverterFactory` 的 kotlinx 分支直接反序列化，
// 不再经过 Gson 反射。
// =========================================================================

// ========== Dashboard ==========

/**
 * 仪表盘聚合响应（#15 api/dashboard/summary）。
 * 内嵌复用已存在的 `@Serializable` 模型，全部可空以容忍后端缺字段（lenient 解析）。
 */
@Serializable
data class DashboardSummaryResponse(
    val device_info: DeviceInfoResponse? = null,
    val battery: BatteryInfo? = null,
    val storage: StorageInfo? = null,
    val uptime: UptimeInfo? = null,
    val traffic_summary: TrafficSummary? = null,
    val traffic_limit: TrafficLimitConfig? = null,
    val network_status: NetworkStatusResponse? = null
)

// ========== SMS Forward ==========

/** #17 debug-logs 响应。 */
@Serializable
data class DebugLogsResponse(
    val logs: List<String> = emptyList()
)

/** core 落盘日志文件条目（`GET /api/debug-logs/files`）。 */
@Serializable
data class DebugLogFileItem(
    val name: String = "",
    val size: Long = 0,
    val modified: Long = 0
)

/**
 * `GET /api/debug-logs/files` 响应。
 *
 * 日志文件在 core 的私有目录（0700）下，未 root 的设备用文件管理器读不到，
 * 只能经这个接口列目录、再用 `/files/{name}` 读尾部。
 */
@Serializable
data class DebugLogFilesResponse(
    val files: List<DebugLogFileItem> = emptyList(),
    val total_bytes: Long = 0,
    /** core 侧日志目录绝对路径，UI 上直接显示，用户可拿文件管理器去同一路径查看。 */
    val dir: String = ""
)

/** #11 sms-forward/test 响应。 */
@Serializable
data class SmsForwardTestResponse(
    val success: Boolean = false,
    val error: String? = null
)

/**
 * sms-forward/diagnose 响应。
 *
 * 全是**派生屏蔽位**（`*_set`）而不是原值 —— core 不回传凭据。
 * [sendable] 是 core 内部真正的发信闸门：`enabled` 且服务器/用户名/密码/收件地址四项齐全；
 * 它为 false 时无论怎么点「测试发送」都不会发出，所以比逐项 `*_set` 更值得优先展示。
 */
@Serializable
data class SmsForwardDiagnose(
    val config_enabled: Boolean = false,
    val smtp_host_set: Boolean = false,
    val smtp_port: Int = 0,
    val smtp_user_set: Boolean = false,
    val smtp_pass_set: Boolean = false,
    val smtp_to_set: Boolean = false,
    val sendable: Boolean = false,
    val scene_count: Int = 0,
    /**
     * 发信计数（core 侧累计，跨重启保留）。
     *
     * 只计**真正发起过 SMTP 投递**的次数：黑名单拦截、场景未勾选、配置不全都不计
     * —— 那些不是"发送失败"，混进来会让失败数虚高。
     * `sent_total` 由 core 用 success+failed 派生，app 直接用，不要自己再加一遍。
     */
    val sent_total: Int = 0,
    val sent_success: Int = 0,
    val sent_failed: Int = 0,
    /** 最近一次**成功**发出的时间戳（毫秒）；0 = 从未成功过。 */
    val last_sent_at: Long = 0L,
    /** 最近一次失败原因（`异常类名: message`，已截断到 200 字）；空串 = 无失败或已被成功覆盖。 */
    val last_error: String = ""
)

/**
 * sms-forward/notify 响应。
 *
 * `success` 表示请求被受理，`sent` 才表示真的发了邮件 ——
 * 场景没勾选 / 未启用都是 `success=true, sent=false`，属正常情况不是错误。
 */
@Serializable
data class SmsForwardNotifyResponse(
    val success: Boolean = false,
    val sent: Boolean = false
)

// ========== File Manager ==========

/**
 * #19 files/search 响应。复用 `FileItem`（定义在 data/api/UfiAxisApi.kt）。
 */
@Serializable
data class SearchFilesResponse(
    val files: List<FileItem> = emptyList(),
    val path: String? = null,
    val error: String? = null
)

/**
 * 磁盘卷原始结构（#20 files/disk-usage 的内嵌元素）。
 * 后端 key：label / mount / size / used / available / usePercent（均为字符串）。
 */
@Serializable
data class StorageVolumeRaw(
    val label: String = "",
    val mount: String = "",
    val size: String = "",
    val used: String = "",
    val available: String = "",
    val usePercent: String = ""
)

/** #20 files/disk-usage 响应。 */
@Serializable
data class DiskUsageResponse(
    val disks: List<StorageVolumeRaw> = emptyList()
)

/** #21 files/status 响应：Core 后端的存储管理权限状态。 */
@Serializable
data class StorageStatusResponse(
    val isExternalStorageManager: Boolean = false
)

