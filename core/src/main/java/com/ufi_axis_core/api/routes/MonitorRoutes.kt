package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.core.database.AppDatabase
import com.ufi_axis_core.util.HistoryDownsampler
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 监控中心路由
 *
 * GET  /api/monitor/history   — 降采样历史数据（支持多指标、多时间范围）
 * GET  /api/monitor/storage   — 各表存储统计
 * POST /api/monitor/clean     — 手动清理历史
 */
class MonitorRoutes(private val database: AppDatabase) {

    companion object {
        private const val DEFAULT_POINTS = 360
    }

    fun register(route: Route) {
        route.route("/monitor") {

            /**
             * 历史数据（降采样）
             * type: cpu | memory | traffic_rx | traffic_tx | signal_rsrp | signal_sinr | battery | temperature
             * hours: 1 | 6 | 24 | 168 (7d)
             * points: 目标点数 (默认 360)
             *
             * 性能优化：使用轻量列查询（仅 SELECT 需要的列）+ Dispatchers.IO
             */
            get("/history") {
                val type = call.request.queryParameters["type"] ?: "cpu"
                val hours = (call.request.queryParameters["hours"] ?: "24").toIntOrNull()?.coerceIn(1, 168) ?: 24
                val points = (call.request.queryParameters["points"] ?: "$DEFAULT_POINTS").toIntOrNull()?.coerceIn(10, 1000) ?: DEFAULT_POINTS

                val startTime = System.currentTimeMillis() - hours * 3600_000L

                // 所有数据库查询在 IO 调度器上执行，避免阻塞 Ktor 事件循环
                val rawData: List<Pair<Long, Double>> = withContext(Dispatchers.IO) {
                    when (type) {
                        "cpu" -> database.cpuHistoryDao()
                            .getLightweightSince(startTime)
                            .map { it.timestamp to it.usagePercent }

                        "temperature" -> database.cpuHistoryDao()
                            .getLightweightSince(startTime)
                            .map { it.timestamp to it.temperature }

                        "memory" -> database.memoryHistoryDao()
                            .getLightweightSince(startTime)
                            .map { it.timestamp to it.usagePercent }

                        "traffic_rx" -> database.trafficDao()
                            .getLightweightSince(startTime)
                            .map { it.timestamp to it.rxSpeed.toDouble() }

                        "traffic_tx" -> database.trafficDao()
                            .getLightweightSince(startTime)
                            .map { it.timestamp to it.txSpeed.toDouble() }

                        "signal_rsrp" -> database.signalDao()
                            .getLightweightSince(startTime)
                            .map { it.timestamp to it.rsrp.toDouble() }
                            .filter { it.second != 0.0 }

                        "signal_sinr" -> database.signalDao()
                            .getLightweightSince(startTime)
                            .map { it.timestamp to it.sinr.toDouble() }
                            .filter { it.second != 0.0 }

                        "battery" -> database.batteryHistoryDao()
                            .getLightweightSince(startTime)
                            .map { it.timestamp to it.level.toDouble() }

                        else -> emptyList()
                    }
                }

                val downsampled = HistoryDownsampler.downsample(rawData, points)
                val bucketSeconds = if (rawData.size > 1) {
                    ((rawData.last().first - rawData.first().first) / 1000 / downsampled.size.coerceAtLeast(1)).toInt()
                } else 0

                call.respond(toJsonElement(mapOf(
                    "type" to type,
                    "points" to downsampled,
                    "count" to downsampled.size,
                    "raw_count" to rawData.size,
                    "period_hours" to hours,
                    "bucket_seconds" to bucketSeconds
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
        }
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
