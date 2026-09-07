package com.ufi_axis.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ══════════════════════════════════════════════════════════════════════════════
// 运维 / 诊断类端点的响应模型（2026-08-30 端点补全）
//
// 这一组端点的共同点：**core 自己的状态**，不经过设备 goform，所以键名由 core 源码固定、
// 不会随固件漂移，可以放心写强类型。字段全部给默认值：core 版本比 app 旧时缺字段要能解，
// 不能让整页因为一个新字段/旧字段解析失败而空白。
// ══════════════════════════════════════════════════════════════════════════════

// ─────────────────────── GET /api/sms/count ───────────────────────

/**
 * 短信条数（`RootSmsRoutes` 的 `/count`）。
 *
 * **这才是全局总数/未读数的正确来源。** `GET /api/sms/list` 响应里的 `total` 是
 * 「本次查询条件下的条数」（带 `phone` 过滤时只是那个会话的），拿它当设备总量会偏小；
 * 联系人列表的 `unread` 之和也只覆盖有会话记录的号码。要展示容量/未读只读本端点。
 */
@Serializable
data class SmsCountResponse(
    val total: Int = 0,
    val unread: Int = 0
)

// ─────────────────── POST /api/alerts/ack-resolved ───────────────────

/**
 * 一键确认「已恢复」告警的结果。
 *
 * 请求体 `{ "minAgeSec": 3600 }` 可选：只确认「恢复时间已超过 N 秒」的，用来避免把刚刚
 * 恢复、用户还没看到的告警一起标掉；不传 = 不限时间，全部已恢复的都确认。
 * [updated] 是实际改动条数，0 表示没有符合条件的告警（不是失败）。
 */
@Serializable
data class AlertAckResolvedResponse(
    val success: Boolean = false,
    val updated: Int = 0
)

// ──────────── GET /api/web/status · POST /api/web/check ────────────

/**
 * Web 控制面板自动更新状态（`WebUpdateManager.statusToMap()`）。
 *
 * 与 `GET /api/update/status`（core APK 自身更新）**是两套状态机**，别复用
 * `UpdateStatusResponse`：本端点没有 `apk_path`，[state] 也没有 `uploading`。
 * [state] 取值：`idle` / `checking` / `downloading` / `verifying` / `installing` /
 * `done` / `failed` / `need_push`。
 *
 * 两个坑：
 * - **没有 WS 推送**，进度只能轮询 `GET /api/web/status`；
 * - `POST /api/web/check` 与本端点**返回同一个快照**，并发触发时 check 不报错、只是把
 *   当前状态回给你 —— 所以「点了检查但 state 还是 idle」不代表请求失败。
 */
@Serializable
data class WebUpdateStatusResponse(
    val state: String = "idle",
    val progress: Int = 0,
    val message: String = "",
    val current_version: String? = null,
    val latest_version: String? = null
) {
    /** 正在跑（进度条要转、按钮要置灰）。 */
    val isBusy: Boolean get() = state in BUSY_STATES

    companion object {
        val BUSY_STATES = setOf("checking", "downloading", "verifying", "installing")
    }
}

// ─────────────────────── GET /api/diagnose ───────────────────────

/**
 * core 诊断快照。
 *
 * 逐字段的坑（都来自 core `HttpServer` 里那段 shell 探测）：
 * - [app_version] 在 core 里是**硬编码 `"0.1"`**，不能用来判断 core 版本，版本看
 *   `GET /api/config/version`；
 * - 只有 [root] 是布尔，[adbd] / [mobile_data] 是 **shell stdout 原文**（取不到时是
 *   `"unknown"`），不要 `== "true"` 这样比；
 * - [gateway] 三级兜底（`ip route` → `dhcp.wlan0.gateway` → `dhcp.wlan.gateway`），
 *   全失败时是**硬编码 `192.168.0.1`** —— 它不代表真的探到了网关；
 * - [field_coverage] 只在带 `fields=1` 时才有，而那会**逐分组向设备发查询**（最多 10 组），
 *   绝不能放进轮询；失败时它退化成 `{"error": "..."}` 但 HTTP 仍是 200。
 */
@Serializable
data class DiagnoseResponse(
    val server_time: Long = 0L,
    val app_version: String = "",
    val root: Boolean = false,
    val adbd: String = "unknown",
    val mobile_data: String = "unknown",
    val gateway: String = "unknown",
    val device_profile: DeviceProfileDiagnose? = null,
    val field_coverage: JsonElement? = null
)

/**
 * 设备 profile 归一化状态。[status] 四态：
 * `configured` 配了且命中 / `default` 没配用注册表默认 / `fallback` 配了但注册表没有已回落 /
 * `disabled` 归一化总开关关掉了。**`fallback` 是型号填错的唯一线索**（core 只在启动日志里 WARN）。
 */
@Serializable
data class DeviceProfileDiagnose(
    val active: String = "",
    val configured: String = "",
    val normalization_enabled: Boolean = false,
    val status: String = ""
)

// ────────────────────── GET /api/qos/status ──────────────────────

/**
 * QoS / 线程池状态。
 *
 * 两个坑：
 * - [enabled] 在 core 里**硬编码 true**，不是真的开关状态（真值在 `GET /api/config`）；
 * - [cpu_temp] 是 `/sys/class/thermal/thermal_zone0/temp` 的**原始值（毫摄氏度）**，
 *   读失败为 0。当摄氏度直接显示会得到「45000 ℃」。
 */
@Serializable
data class QosStatusResponse(
    val enabled: Boolean = false,
    val shell_qos: ShellQosStatus? = null,
    val goform_qos: JsonElement? = null,
    val cpu_temp: Int = 0,
    val dynamic_pool: DynamicPoolStatus? = null
) {
    /** 毫摄氏度 → 摄氏度；0（读取失败）返回 null，让 UI 显示「不可用」而不是 0 ℃。 */
    val cpuTempCelsius: Double? get() = if (cpu_temp <= 0) null else cpu_temp / 1000.0
}

@Serializable
data class ShellQosStatus(
    val root: QosPermits? = null,
    val normal: QosPermits? = null,
    val cache: QosCacheStatus? = null,
    /** `ShellQoS.batchStats`：**累计计数**，不是 available/total 这类瞬时值，别做占用率。 */
    val batch: JsonElement? = null
)

/** 信号量许可。[target] 只有 root 侧有（动态调整目标值），normal 侧不返回。 */
@Serializable
data class QosPermits(
    val available: Int = 0,
    val total: Int = 0,
    val target: Int? = null
)

@Serializable
data class QosCacheStatus(
    val entries: Int = 0,
    val ttl_ms: Long = 0L
)

@Serializable
data class DynamicPoolStatus(
    val core: Int = 0,
    val current: Int = 0,
    val max: Int = 0
)

// ────────────────────── GET /api/cache/stats ──────────────────────

/**
 * 响应缓存统计（`ResponseCache.getStats()`）。
 *
 * - [any_cache_count] 是另一张表，**不计入** [count]；
 * - [total_bytes_estimate] 是估算值且**不含 any_cache**；
 * - [stale] = 缓存可能过期（如 WS 客户端全部断开）；
 * - [entries] 按 `age_ms` 倒序、**只回前 50 条**，所以它的长度不等于 [count]。
 */
@Serializable
data class CacheStatsResponse(
    val count: Int = 0,
    val max_entries: Int = 0,
    val any_cache_count: Int = 0,
    val total_bytes_estimate: Long = 0L,
    val stale: Boolean = false,
    val entries: List<CacheEntryStat> = emptyList()
)

@Serializable
data class CacheEntryStat(
    val key: String = "",
    val age_ms: Long = 0L,
    val ttl_ms: Long = 0L,
    val expired: Boolean = false,
    val size_bytes: Long = 0L
)

/**
 * `POST /api/cache/clear` / `POST /api/cache/invalidate` 的响应。
 *
 * invalidate 会**回显生效的 pattern**，但**不回删除条数** —— 想知道少了多少条只能前后各读一次
 * `/api/cache/stats`。
 */
@Serializable
data class CacheActionResponse(
    val success: Boolean = false,
    val message: String? = null,
    val pattern: String? = null
)

// ──────── GET /api/system/root-check · GET /api/shell/root ────────

/** `{ "hasRoot": true }`。只探 root 可用性，不返回方式。 */
@Serializable
data class RootCheckResponse(
    @SerialName("hasRoot") val hasRoot: Boolean = false
)

/**
 * `{ "root": true, "uid": "0", "method": "adb_shell" }`。
 *
 * 比 [RootCheckResponse] 多了 [method]（`adb_shell` / `shell`），是判断「特权来源」的唯一途径
 * —— 文档里的 `api/adb/status` 是幻影端点，core 从来没有，别再去调。
 */
@Serializable
data class ShellRootResponse(
    val root: Boolean = false,
    val uid: String = "",
    val method: String = ""
)
