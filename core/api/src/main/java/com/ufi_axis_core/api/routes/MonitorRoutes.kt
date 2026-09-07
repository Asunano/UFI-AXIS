package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.core.database.AppDatabase
import com.ufi_axis_core.core.scheduler.DataScheduler
import com.ufi_axis_core.util.DownsampledPoint
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull

/**
 * 监控中心个性化偏好（设备级配置，app 与 web 共享同一份）。
 *
 * 【为什么放 core】这 7 项原先只存在 app 的 `SharedPreferences("ufi_axis_prefs")` 单 key
 * `monitor_settings_v1` 里，core 没有任何端点 —— 同一台设备用 app 改了刷新间隔，从 web 进去
 * 看到的还是 web 自己的默认值，属于典型的「两端各说各话」。现在 core 是唯一真源，客户端只做缓存。
 *
 * 【边界】采集总开关 `collectEnabled` **不在本模型内**：它的真源是
 * `AppSettings.backgroundServiceEnabled`，入口是 `POST /api/monitor/control` 与
 * `POST /api/service/start|stop`，不要在这里再存一份。
 *
 * 字段默认值必须与 app 的 `com.ufi_axis.data.monitor.MonitorSettings` 保持一致，
 * 否则「全新设备第一次 GET」两端会看到不同的初值。
 */
@Serializable
data class MonitorPreferences(
    /** 单指标开关（apiKey 集合）；默认全开 */
    val enabledTypes: Set<String> = MonitorRoutes.METRIC_KEYS,
    /** 默认时间范围小时数：1 / 6 / 24 / 168 */
    val defaultHours: Int = 24,
    /** 自动刷新间隔秒：10..3600 */
    val refreshIntervalSec: Int = 30,
    /** Y 轴固定（false = 自适应） */
    val fixedYAxis: Boolean = false,
    /** 图表填充透明度 0..1 */
    val fillAlpha: Float = 1f,
    /** 导出偏好：true = 打包 zip，false = 散文件 */
    val exportZip: Boolean = false,

    // ── 采集调度（2026-09-03 新增；真源是 AppSettings 的强类型字段，不进上面那份 JSON blob）──
    // DataScheduler 在循环内直接读 AppSettings，所以改完立即生效，不需要重启采集。
    /** 监控历史保留天数 1..90（原先 DataScheduler 里硬编码 3 天） */
    val retentionDays: Int = 7,
    /** 采集缓冲区刷写间隔（秒）5..300 */
    val flushIntervalSec: Int = 30,
    /** 本地告警扫描间隔（秒）5..300 */
    val alertScanSec: Int = 15,
    /** 无前端连接时的采集间隔（秒）10..600 */
    val idleIntervalSec: Int = 60,
    /** 温控预警阈值 ℃ 50..90（到这个温度降频采集） */
    val thermalWarnC: Int = 70,
    /** 温控熔断阈值 ℃ 55..100（到这个温度暂停采集） */
    val thermalCriticalC: Int = 80,
    /** 温控熔断暂停时长（秒）5..300 */
    val thermalPauseSec: Int = 20
)


/**
 * 监控中心路由
 *
 * GET  /api/monitor/history   — 降采样历史数据（支持多指标、多时间范围）
 * GET  /api/monitor/storage   — 各表存储统计
 * POST /api/monitor/clean     — 手动清理历史
 * POST /api/monitor/control   — 监控总开关（启用/停用数据采集）
 * GET  /api/monitor/preferences — 监控个性化偏好（两端共享）
 * PUT  /api/monitor/preferences — 字段级合并更新偏好
 */
class MonitorRoutes(
    private val database: AppDatabase,
    private val dataScheduler: DataScheduler,
    /** 采集开关的持久化真源（与 `POST /api/service/start|stop` 共用，避免两个入口各写一份）。 */
    private val settings: com.ufi_axis_core.util.AppSettings,
    /**
     * 采集之外的后台服务总闸（与 `POST /api/service/start|stop` 共用同一实现）；
     * 实现方为 `BackendService.applyBackgroundServices(enabled)`。
     */
    private val onBackgroundSwitch: (Boolean) -> Unit
) {

    companion object {
        private const val DEFAULT_POINTS = 360

        /** 区间查询上限：30 天（毫秒） */
        private const val MAX_RANGE_MS = 30L * 24 * 3600_000L

        /**
         * 合法指标 apiKey 全集，与本文件 `/history` 里 `when (type)` 的分支一一对应，
         * 也与 app 的 `MonitorMetricType.apiKey` 一致。新增指标时三处必须同步改。
         */
        val METRIC_KEYS: Set<String> = setOf(
            "cpu", "memory", "traffic_rx", "traffic_tx",
            "signal_rsrp", "signal_sinr", "battery", "temperature"
        )

        /** 默认时间范围的合法取值（与两端 UI 的 chip 一致） */
        private val ALLOWED_HOURS = setOf(1, 6, 24, 168)

        private const val REFRESH_MIN_SEC = 10
        private const val REFRESH_MAX_SEC = 3600

        /**
         * encodeDefaults = true 是必需的：默认 Json 会省略等于默认值的字段，
         * 全新设备 GET 会返回 `{}`，两端只能各自兜默认值 —— 又回到「各说各话」。
         */
        private val PrefsJson = com.ufi_axis_core.util.ConfigJson

        /**
         * 字段级合并：只有 patch 里显式出现且非 null 的键会覆盖，其余保持服务端现值。
         * 不做整体替换，避免客户端漏传一个字段就把它重置成默认值。
         */
        internal fun mergePreferences(current: MonitorPreferences, patch: JsonObject): MonitorPreferences {
            val effective = patch.filterValues { it !is JsonNull }
            if (effective.isEmpty()) return current
            val base = PrefsJson.encodeToJsonElement(MonitorPreferences.serializer(), current).jsonObject
            return PrefsJson.decodeFromJsonElement(
                MonitorPreferences.serializer(),
                JsonObject(base + effective)
            )
        }

        /** 返回第一条约束违规说明；全部合法返回 null。 */
        internal fun validatePreferences(p: MonitorPreferences): String? = when {
            p.defaultHours !in ALLOWED_HOURS ->
                "defaultHours 必须是 ${ALLOWED_HOURS.sorted().joinToString("/")} 之一，收到 ${p.defaultHours}"
            p.refreshIntervalSec !in REFRESH_MIN_SEC..REFRESH_MAX_SEC ->
                "refreshIntervalSec 必须在 $REFRESH_MIN_SEC..$REFRESH_MAX_SEC 秒之间，收到 ${p.refreshIntervalSec}"
            p.fillAlpha < 0f || p.fillAlpha > 1f ->
                "fillAlpha 必须在 0..1 之间，收到 ${p.fillAlpha}"
            // ── 采集调度（2026-09-03）：范围与 AppSettings 的钳制保持一致，越界直接拒绝而不是静默夹住 ──
            p.retentionDays !in 1..90 -> "retentionDays 必须在 1..90 天之间，收到 ${p.retentionDays}"
            p.flushIntervalSec !in 5..300 -> "flushIntervalSec 必须在 5..300 秒之间，收到 ${p.flushIntervalSec}"
            p.alertScanSec !in 5..300 -> "alertScanSec 必须在 5..300 秒之间，收到 ${p.alertScanSec}"
            p.idleIntervalSec !in 10..600 -> "idleIntervalSec 必须在 10..600 秒之间，收到 ${p.idleIntervalSec}"
            p.thermalWarnC !in 50..90 -> "thermalWarnC 必须在 50..90℃ 之间，收到 ${p.thermalWarnC}"
            p.thermalCriticalC !in 55..100 -> "thermalCriticalC 必须在 55..100℃ 之间，收到 ${p.thermalCriticalC}"
            p.thermalCriticalC <= p.thermalWarnC ->
                "熔断阈值必须高于预警阈值（收到 预警 ${p.thermalWarnC}℃ / 熔断 ${p.thermalCriticalC}℃）"
            p.thermalPauseSec !in 5..300 -> "thermalPauseSec 必须在 5..300 秒之间，收到 ${p.thermalPauseSec}"
            else -> (p.enabledTypes - METRIC_KEYS).takeIf { it.isNotEmpty() }?.let {
                "enabledTypes 含未知指标：${it.sorted().joinToString(",")}"
            }
        }
    }

    fun register(route: Route) {
        route.route("/monitor") {

            /**
             * 历史数据（桶聚合）
             * type: cpu | memory | traffic_rx | traffic_tx | signal_rsrp | signal_sinr | battery | temperature
             * hours: 1 | 6 | 24 | 168 (7d)      —— 与 start_time/end_time 互斥
             * start_time / end_time: epoch millis 字符串，任意历史区间（≤30 天），两个都必需
             * points: 目标点数 (默认 360, 上限 720)
             * bucket_ms: 可选，客户端显式指定桶宽（毫秒）
             *
             * 【2026-09-03 精度重写：为什么两条路径要合并】
             * 原先 hours 路径是「全量读原始行 + HistoryDownsampler 在内存里按首条数据为原点分桶」，
             * 区间路径是「SQL `timestamp / bucketMs` 分桶，原点是 epoch 绝对网格」——同一段时间
             * 两种模式返回的 `t` 序列对不上，客户端把两批点混在一张图上就是「点数无上限增长 + 尾部时间重复」。
             * 现在两条路径都只算出 [startMs, endMs] 再走同一个 [aggregateBetween]，
             * `t` 落在同一条 epoch 绝对网格上，天然可按 t 去重合并；顺带也不再把 7 天原始行全读进内存。
             *
             * 【bucket_ms 为什么必须让客户端指定】
             * 实时增量刷新每轮只请求「上次游标 − 一桶」这么短的窗口，若服务端仍按
             * `(end-start)/points` 自算，短窗口会算出 1s 网格，与首屏几百秒的粗网格不相交。
             * 客户端拿整个可见区间算一次桶宽、首屏与增量都带上它，网格才恒定。
             */
            get("/history") {
                val type = call.request.queryParameters["type"] ?: "cpu"
                val hoursParam = call.request.queryParameters["hours"]
                val startTimeParam = call.request.queryParameters["start_time"]
                val endTimeParam = call.request.queryParameters["end_time"]
                val points = (call.request.queryParameters["points"] ?: "$DEFAULT_POINTS").toIntOrNull()?.coerceIn(10, 720) ?: DEFAULT_POINTS
                val bucketMsParam = call.request.queryParameters["bucket_ms"]?.toLongOrNull()

                // hours 与 start_time/end_time 互斥：同时传 → 400
                if (hoursParam != null && (startTimeParam != null || endTimeParam != null)) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "hours and start_time/end_time are mutually exclusive")
                    return@get
                }

                // 两条路径唯一的差别只剩「窗口端点怎么来」：hours 是滑动窗口，区间是显式端点。
                val startMs: Long
                val endMs: Long
                val periodHours: Long
                if (startTimeParam == null && endTimeParam == null) {
                    val hours = (hoursParam ?: "24").toIntOrNull()?.coerceIn(1, 168) ?: 24
                    endMs = System.currentTimeMillis()
                    startMs = endMs - hours * 3600_000L
                    periodHours = hours.toLong()
                } else {
                    // start_time / end_time 必须同时提供且为 epoch millis
                    val s = startTimeParam?.toLongOrNull()
                    val e = endTimeParam?.toLongOrNull()
                    if (s == null || e == null) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "start_time and end_time are both required (epoch millis)")
                        return@get
                    }
                    if (s >= e) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "start_time must be earlier than end_time")
                        return@get
                    }
                    if (e - s > MAX_RANGE_MS) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "range too large")
                        return@get
                    }
                    startMs = s
                    endMs = e
                    periodHours = (e - s) / 3600_000L
                }

                val bucketMs = resolveBucketMs(bucketMsParam, startMs, endMs, points)
                val downsampled = aggregateBetween(type, startMs, endMs, bucketMs)

                // raw_count = SQL 聚合行数（即桶数）；bucket_seconds/bucket_ms 一律回真实生效的桶宽 ——
                // 原来 hours 路径用「非空桶数」当分母算 bucket_seconds，有空洞时算出的桶宽被放大，是错的。
                call.respond(toJsonElement(mapOf(
                    "type" to type,
                    "points" to downsampled,
                    "count" to downsampled.size,
                    "raw_count" to downsampled.size,
                    "period_hours" to periodHours,
                    "bucket_seconds" to (bucketMs / 1000).toInt(),
                    "bucket_ms" to bucketMs
                )))
            }

            /**
             * 存储统计
             */
            get("/storage") {
                val tables = withContext(Dispatchers.IO) {
                    listOf(
                        tableInfo("cpu_history", database.cpuHistoryDao().getCount(), 60),
                        tableInfo("memory_history", database.memoryHistoryDao().getCount(), 50),
                        tableInfo("traffic_records", database.trafficDao().getCount(), 50),
                        tableInfo("signal_history", database.signalDao().getCount(), 60),
                        tableInfo("battery_history", database.batteryHistoryDao().getCount(), 45),
                        tableInfo("alert_records", runCatching { database.alertDao().getRecentAlerts(1) }.getOrNull()?.size ?: 0, 120),
                        tableInfo("sms_records", 0, 200)
                    )
                }
                val totalKb = tables.sumOf { it["size_kb"] as Double }

                call.respond(toJsonElement(mapOf(
                    "tables" to tables,
                    "total_kb" to "%.1f".format(totalKb).toDouble(),
                    "total_display" to formatStorageSize(totalKb)
                )))
            }

            /**
             * 手动清理
             * body: { "type": "all" | 表名, "days": N }
             * days 省略则用默认保留期
             */
            post("/clean") {
                val body = runCatching {
                    Json.parseToJsonElement(call.receiveText()).jsonObject
                }.getOrNull() ?: kotlinx.serialization.json.JsonObject(emptyMap())

                val type = body["type"]?.jsonPrimitive?.contentOrNull ?: "all"
                val days = body["days"]?.jsonPrimitive?.intOrNull ?: 7
                val cutoff = System.currentTimeMillis() - days * 24L * 3600_000L

                val deleted = withContext(Dispatchers.IO) {
                    val result = mutableMapOf<String, Int>()
                    when (type) {
                        "all" -> {
                            result["cpu_history"] = database.cpuHistoryDao().deleteOlderThan(cutoff)
                            result["memory_history"] = database.memoryHistoryDao().deleteOlderThan(cutoff)
                            result["traffic_records"] = database.trafficDao().deleteOlderThan(cutoff)
                            result["signal_history"] = database.signalDao().deleteOlderThan(cutoff)
                            result["battery_history"] = database.batteryHistoryDao().deleteOlderThan(cutoff)
                            result["alert_records"] = database.alertDao().deleteOlderThan(cutoff)
                            // sms_records 永久保留，不参与清理
                        }
                        "cpu_history" -> result["cpu_history"] = database.cpuHistoryDao().deleteOlderThan(cutoff)
                        "memory_history" -> result["memory_history"] = database.memoryHistoryDao().deleteOlderThan(cutoff)
                        "traffic_records" -> result["traffic_records"] = database.trafficDao().deleteOlderThan(cutoff)
                        "signal_history" -> result["signal_history"] = database.signalDao().deleteOlderThan(cutoff)
                        "battery_history" -> result["battery_history"] = database.batteryHistoryDao().deleteOlderThan(cutoff)
                        "alert_records" -> result["alert_records"] = database.alertDao().deleteOlderThan(cutoff)
                        // "sms_records" 不支持清理操作
                    }
                    result
                }

                call.respond(toJsonElement(mapOf(
                    "deleted" to deleted,
                    "cutoff_days" to days
                )))
            }

            /**
             * 监控总开关：启用/停用数据采集
             * body: { "enabled": true | false }
             *
             * 与 `POST /api/service/start|stop` 是同一个开关的两个入口，
             * 都写 `AppSettings.backgroundServiceEnabled`（持久化）后再落到 DataScheduler。
             */
            post("/control") {
                val body = runCatching {
                    Json.parseToJsonElement(call.receiveText()).jsonObject
                }.getOrNull() ?: kotlinx.serialization.json.JsonObject(emptyMap())
                val enabled = body["enabled"]?.jsonPrimitive?.booleanOrNull ?: return@post call.respondFail(
                    HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "enabled (boolean) required"
                )
                settings.backgroundServiceEnabled = enabled
                dataScheduler.setMonitorEnabled(enabled)
                onBackgroundSwitch(enabled)
                call.respond(toJsonElement(mapOf("ok" to true, "enabled" to enabled)))
            }

            /**
             * 监控个性化偏好：读取全量
             *
             * 未写入过或 JSON 损坏时回落默认值（不报错），这样全新设备第一次进页面就有可用配置。
             */
            get("/preferences") {
                call.respond(Json.parseToJsonElement(PrefsJson.encodeToString(
                    MonitorPreferences.serializer(),
                    loadPreferences()
                )))
            }

            /**
             * 监控个性化偏好：字段级合并更新
             * body: MonitorPreferences 的任意子集，例如 { "refreshIntervalSec": 60 }
             *
             * 语义与 `PUT /api/alerts/config` 一致（补丁而非整体替换），但**不做版本守门**：
             * 这些是纯展示偏好，最后写入者生效即可，不值得让客户端承担 409 重试。
             * `enabledTypes` 是完整集合语义 —— 传了就整体替换，客户端要先 GET 再改。
             */
            put("/preferences") {
                val patch = call.receiveJsonObject()
                val merged = try {
                    mergePreferences(loadPreferences(), patch)
                } catch (e: Exception) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "监控偏好字段类型不合法：${e.message}"
                    )
                    return@put
                }
                validatePreferences(merged)?.let { reason ->
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, reason)
                    return@put
                }
                settings.monitorPreferencesJson =
                    PrefsJson.encodeToString(MonitorPreferences.serializer(), merged)
                // 采集调度 7 项另存为强类型字段，DataScheduler 在循环内读它 → 改完立即生效
                applySchedulerFields(merged)
                call.respond(toJsonElement(mapOf("success" to true, "preferences" to merged)))
            }
        }
    }

    /**
     * 桶宽（毫秒）决策：客户端显式 `bucket_ms` 优先，否则按 points 均分窗口。
     *
     * 【为什么显式优先】增量刷新的请求窗口只有一两个桶那么长，让服务端自算必然算出比首屏细得多的
     * 网格；只要客户端把「整个可见区间算出来的桶宽」带上，首屏与增量就落在同一条 epoch 网格上。
     *
     * 【为什么向上取整到秒】桶宽必须是整秒，`t` 才会落在稳定的绝对网格上（客户端也按整秒算，
     * 两边公式一致才对得上）。原写法 `(end-start)/1000/points*1000` 是先整除再乘，
     * 窗口短于 points 秒时直接得 0，桶宽退化成 1s —— 这正是网格错位的根因，故改为向上取整。
     */
    private fun resolveBucketMs(explicitMs: Long?, startMs: Long, endMs: Long, points: Int): Long {
        val raw = explicitMs?.takeIf { it > 0 } ?: ((endMs - startMs) / points.coerceAtLeast(1))
        if (raw <= 1000L) return 1000L
        return ((raw + 999L) / 1000L) * 1000L
    }

    /**
     * 单指标区间桶聚合（hours 路径与区间路径**共用**的唯一取数实现）。
     *
     * 桶原点是 epoch 绝对网格（DAO 里 `timestamp / bucketMs`），桶心时间 = 桶起点 + 半桶；
     * 只要 bucketMs 相同，任意窗口取出来的 `t` 都能互相对齐 —— 这是客户端按 t 去重合并的前提。
     * 信号的 0 值（无信号哨兵）由 DAO 查询里的 `rsrp != 0` / `sinr != 0` 排除，
     * 两条路径因此有一致的均值口径，不再出现「同一区间换个模式均值就变」。
     */
    private suspend fun aggregateBetween(
        type: String,
        startMs: Long,
        endMs: Long,
        bucketMs: Long
    ): List<DownsampledPoint> = withContext(Dispatchers.IO) {
        fun center(bucket: Long): Long = bucket * bucketMs + bucketMs / 2
        when (type) {
            "cpu" -> database.cpuHistoryDao()
                .getAggregatedUsageBetween(startMs, endMs, bucketMs)
                .map { DownsampledPoint(center(it.bucket), it.avgVal, it.minVal, it.maxVal) }

            "temperature" -> database.cpuHistoryDao()
                .getAggregatedTemperatureBetween(startMs, endMs, bucketMs)
                .map { DownsampledPoint(center(it.bucket), it.avgVal, it.minVal, it.maxVal) }

            "memory" -> database.memoryHistoryDao()
                .getAggregatedUsageBetween(startMs, endMs, bucketMs)
                .map { DownsampledPoint(center(it.bucket), it.avgVal, it.minVal, it.maxVal) }

            "traffic_rx" -> database.trafficDao()
                .getAggregatedRxBetween(startMs, endMs, bucketMs)
                .map { DownsampledPoint(center(it.bucket), it.avgVal, it.minVal, it.maxVal) }

            "traffic_tx" -> database.trafficDao()
                .getAggregatedTxBetween(startMs, endMs, bucketMs)
                .map { DownsampledPoint(center(it.bucket), it.avgVal, it.minVal, it.maxVal) }

            "signal_rsrp" -> database.signalDao()
                .getAggregatedRsrpBetween(startMs, endMs, bucketMs)
                .map { DownsampledPoint(center(it.bucket), it.avgVal, it.minVal, it.maxVal) }

            "signal_sinr" -> database.signalDao()
                .getAggregatedSinrBetween(startMs, endMs, bucketMs)
                .map { DownsampledPoint(center(it.bucket), it.avgVal, it.minVal, it.maxVal) }

            "battery" -> database.batteryHistoryDao()
                .getAggregatedLevelBetween(startMs, endMs, bucketMs)
                .map { DownsampledPoint(center(it.bucket), it.avgVal, it.minVal, it.maxVal) }

            else -> emptyList()
        }
    }

    /** 读当前偏好；未写入/损坏一律回落默认值（损坏的旧值下一次 PUT 会被整份覆盖）。 */
    private fun loadPreferences(): MonitorPreferences {
        val raw = settings.monitorPreferencesJson ?: return withSchedulerFields(MonitorPreferences())
        return try {
            withSchedulerFields(PrefsJson.decodeFromString(MonitorPreferences.serializer(), raw))
        } catch (e: Exception) {
            withSchedulerFields(MonitorPreferences())
        }
    }

    /**
     * 采集调度那 7 项的真源是 AppSettings 的强类型字段（DataScheduler 直接读它，不解析 JSON），
     * 所以回读时统一用 settings 覆盖 blob 里的副本，避免两处不一致。
     */
    private fun withSchedulerFields(p: MonitorPreferences): MonitorPreferences = p.copy(
        retentionDays = settings.monitorRetentionDays,
        flushIntervalSec = settings.monitorFlushIntervalSec,
        alertScanSec = settings.monitorAlertScanSec,
        idleIntervalSec = settings.monitorIdleIntervalSec,
        thermalWarnC = settings.monitorThermalWarnC,
        thermalCriticalC = settings.monitorThermalCriticalC,
        thermalPauseSec = settings.monitorThermalPauseSec
    )

    /** 把采集调度字段落到 AppSettings（PUT 时调用） */
    private fun applySchedulerFields(p: MonitorPreferences) {
        settings.monitorRetentionDays = p.retentionDays
        settings.monitorFlushIntervalSec = p.flushIntervalSec
        settings.monitorAlertScanSec = p.alertScanSec
        settings.monitorIdleIntervalSec = p.idleIntervalSec
        settings.monitorThermalWarnC = p.thermalWarnC
        settings.monitorThermalCriticalC = p.thermalCriticalC
        settings.monitorThermalPauseSec = p.thermalPauseSec
    }

    private fun tableInfo(name: String, count: Int, bytesPerRow: Int): Map<String, Any> {
        val sizeKb = count.toDouble() * bytesPerRow / 1024.0
        return mapOf(
            "name" to name,
            "count" to count,
            "size_kb" to "%.1f".format(sizeKb).toDouble()
        )
    }

    private fun formatStorageSize(kb: Double): String {
        return when {
            kb >= 1024 -> "%.1f MB".format(kb / 1024.0)
            else -> "%.1f KB".format(kb)
        }
    }
}
