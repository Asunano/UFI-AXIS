package com.ufi_axis.data.monitor

import android.content.Context
import com.ufi_axis.util.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 监控中心全部设置（个性化 14 项 + 总开关 + 单指标开关）。
 *
 * 后 7 项（保留天数 / 三个间隔 / 温控三项）不是"展示偏好"而是**采集调度参数**：
 * 真源在 core 的 `AppSettings`，`DataScheduler` 在循环体内读，所以改完下一轮生效。
 * 默认值必须与 core 一致 —— 本地缓存会在 core 回读前先喂给 UI，两边不一致时
 * 用户会看到"打开页面是 A，一秒后跳成 B"。
 */
@Serializable
data class MonitorSettings(
    val collectEnabled: Boolean = true,                    // 总开关：停用后不采集/不拉取
    val enabledTypes: Set<String> = MonitorMetricType.entries.map { it.apiKey }.toSet(), // 单指标开关（apiKey 集合）
    val defaultHours: Int = 24,                            // ① 默认时间范围（1/6/24/168）
    val refreshIntervalSec: Int = 30,                      // ② 自动刷新间隔秒（10/30/60/300）
    val fixedYAxis: Boolean = false,                       // ③ Y 轴固定（false=自适应）
    val fillAlpha: Float = 1f,                             // ④ 图表填充透明度 0..1
    val exportZip: Boolean = false,                        // ⑤ 导出偏好：true=打包 zip，false=散文件
    // ── 采集调度（2026-09-03 新增；值域由 core 的 PUT 校验兜底，越界返回 400）──
    val retentionDays: Int = 7,                            // ⑥ 监控历史保留天数（1..90），自动清理按此执行
    val flushIntervalSec: Int = 30,                        // ⑦ 采集缓冲刷写间隔秒（5..300）
    val alertScanSec: Int = 15,                            // ⑧ 本地告警扫描间隔秒（5..300）
    val idleIntervalSec: Int = 60,                         // ⑨ 无前端连接时的采集间隔秒（10..600）
    val thermalWarnC: Int = 70,                            // ⑩ 温控预警阈值 ℃（50..90）
    val thermalCriticalC: Int = 80,                        // ⑪ 温控熔断阈值 ℃（55..100，且必须 > 预警）
    val thermalPauseSec: Int = 20                          // ⑫ 熔断后暂停采集时长秒（5..300）
)

/**
 * 监控设置持久化仓储（SharedPreferences）。
 *
 * 与 [ThresholdRepository] / [com.ufi_axis.ui.theme.ThemeManager] 共用 `ufi_axis_prefs` 文件（MODE_PRIVATE），
 * 单 key `monitor_settings_v1` 存整份 JSON（kotlinx.serialization）。
 *
 * 序列化复用项目全局 [AppJson]（lenient + ignoreUnknownKeys + coerceInputValues），
 * 零新增依赖；损坏 JSON / 未写入回退默认 [MonitorSettings]（全开），读写失败静默不阻断主流程。
 */
class MonitorPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ufi_axis_prefs", Context.MODE_PRIVATE)

    /** 读取全量监控设置；未写入或 JSON 损坏时返回默认 [MonitorSettings] */
    fun load(): MonitorSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return MonitorSettings()
        return try {
            AppJson.decodeFromString<MonitorSettings>(raw)
        } catch (e: Exception) {
            MonitorSettings()
        }
    }

    /** 全量覆盖写入监控设置；序列化 / 落盘失败静默丢弃 */
    fun save(settings: MonitorSettings) {
        try {
            prefs.edit().putString(KEY_SETTINGS, AppJson.encodeToString<MonitorSettings>(settings)).apply()
        } catch (e: Exception) {
            // 落盘失败仅丢弃，不影响主流程
        }
    }

    companion object {
        private const val KEY_SETTINGS = "monitor_settings_v1"
    }
}

/**
 * 监控个性化偏好的**设备侧真源**投影（`GET/PUT /api/monitor/preferences` 的载荷）。
 *
 * 只含 core 持有的项；采集总开关 `collectEnabled` 故意不在此列 —— 它的真源是
 * `/api/service/status` + `POST /api/monitor/control`，混进来会变成两个入口抢同一个开关。
 *
 * 与 [MonitorSettings] 分开定义而不是直接复用，是为了让「core 不返回 collectEnabled」
 * 这件事在类型上就成立，避免反序列化时把默认值 `true` 当成服务端意见写回本地。
 *
 * 字段名逐字对齐 core 的 `MonitorPreferences`（无 `@SerialName` 改写）：JSON key 一旦对不上，
 * PUT 会被 core 当成"未提供"而用默认值覆盖，表现为设置静默回弹。
 * 全部带默认值，是为了让旧版 core（不返回这 7 项）也能反序列化成功。
 */
@Serializable
data class MonitorPrefsPayload(
    val enabledTypes: Set<String>,
    val defaultHours: Int,
    val refreshIntervalSec: Int,
    val fixedYAxis: Boolean,
    val fillAlpha: Float,
    val exportZip: Boolean,
    val retentionDays: Int = 7,
    val flushIntervalSec: Int = 30,
    val alertScanSec: Int = 15,
    val idleIntervalSec: Int = 60,
    val thermalWarnC: Int = 70,
    val thermalCriticalC: Int = 80,
    val thermalPauseSec: Int = 20
)

/** 把 core 回读到的偏好覆盖到本地设置上，保留本地 `collectEnabled`。 */
fun MonitorSettings.applyRemote(remote: MonitorPrefsPayload): MonitorSettings = copy(
    enabledTypes = remote.enabledTypes,
    defaultHours = remote.defaultHours,
    refreshIntervalSec = remote.refreshIntervalSec,
    fixedYAxis = remote.fixedYAxis,
    fillAlpha = remote.fillAlpha,
    exportZip = remote.exportZip,
    retentionDays = remote.retentionDays,
    flushIntervalSec = remote.flushIntervalSec,
    alertScanSec = remote.alertScanSec,
    idleIntervalSec = remote.idleIntervalSec,
    thermalWarnC = remote.thermalWarnC,
    thermalCriticalC = remote.thermalCriticalC,
    thermalPauseSec = remote.thermalPauseSec
)

/** 抽出要上行到 core 的偏好项。 */
fun MonitorSettings.toRemotePayload(): MonitorPrefsPayload = MonitorPrefsPayload(
    enabledTypes = enabledTypes,
    defaultHours = defaultHours,
    refreshIntervalSec = refreshIntervalSec,
    fixedYAxis = fixedYAxis,
    fillAlpha = fillAlpha,
    exportZip = exportZip,
    retentionDays = retentionDays,
    flushIntervalSec = flushIntervalSec,
    alertScanSec = alertScanSec,
    idleIntervalSec = idleIntervalSec,
    thermalWarnC = thermalWarnC,
    thermalCriticalC = thermalCriticalC,
    thermalPauseSec = thermalPauseSec
)

/** `PUT /api/monitor/preferences` 的响应：回显服务端合并+校验后的最终值。 */
@Serializable
data class MonitorPrefsUpdateResponse(
    val success: Boolean = false,
    val preferences: MonitorPrefsPayload? = null
)
