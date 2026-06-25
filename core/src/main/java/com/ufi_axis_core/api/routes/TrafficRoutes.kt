package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.routes.RouteContext
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * 流量统计路由
 * GET /api/traffic/realtime  - 实时网速
 * GET /api/traffic/history   - 流量历史
 * GET /api/traffic/summary   - 流量汇总（优先使用 Goform 月流量数据）
 */
class TrafficRoutes(
    private val ctx: RouteContext
) {
    // ── 反向兼容 getter ──
    private val dataScheduler get() = ctx.dataScheduler
    private val database get() = ctx.database

    // 每日基线缓存：记录每天首次查询时的月累计值，用于计算今日流量
    @Volatile private var dailyBaselineDate: String? = null
    @Volatile private var dailyBaselineRx: Long = 0L
    @Volatile private var dailyBaselineTx: Long = 0L
    fun register(route: Route) {
        route.route("/traffic") {
            // 实时网速（从调度器缓存）
            get("/realtime") {
                val latest = dataScheduler.latestTraffic.value
                if (latest != null) {
                    call.respond(toJsonElement(mapOf(
                        "rx_speed" to latest.rxSpeed,
                        "tx_speed" to latest.txSpeed,
                        "rx_bytes" to latest.rxBytes,
                        "tx_bytes" to latest.txBytes,
                        "rx_speed_display" to formatSpeed(latest.rxSpeed),
                        "tx_speed_display" to formatSpeed(latest.txSpeed),
                        "timestamp" to latest.timestamp
                    )))
                } else {
                    call.respond(toJsonElement(mapOf("error" to "No data yet")))
                }
            }

            // 流量历史（使用轻量查询，仅 SELECT 需要的列）
            get("/history") {
                val hoursParam = (call.request.queryParameters["hours"] ?: "24").toIntOrNull() ?: 24
                val startTime = System.currentTimeMillis() - hoursParam * 60 * 60 * 1000L
                val records = database.trafficDao().getLightweightSince(startTime)
                call.respond(toJsonElement(mapOf(
                    "records" to records,
                    "count" to records.size,
                    "period_hours" to hoursParam
                )))
            }

            // 流量汇总（使用 DataScheduler 缓存的 Goform 月流量）
            get("/summary") {
                val latest = dataScheduler.latestTraffic.value
                val totalRecords = database.trafficDao().getCount()

                var monthRx = 0L; var monthTx = 0L
                var todayRx = 0L; var todayTx = 0L
                val cached = dataScheduler.goformTraffic.value
                if (cached != null && (cached.first > 0 || cached.second > 0)) {
                    monthRx = cached.first; monthTx = cached.second
                    val todayKey = java.time.LocalDate.now().toString()
                    if (dailyBaselineDate != todayKey) {
                        dailyBaselineDate = todayKey
                        dailyBaselineRx = monthRx
                        dailyBaselineTx = monthTx
                    }
                    todayRx = (monthRx - dailyBaselineRx).coerceAtLeast(0)
                    todayTx = (monthTx - dailyBaselineTx).coerceAtLeast(0)
                }

                // Goform 缓存不可用时回退到 Android 累计差值
                if (monthRx == 0L && monthTx == 0L) {
                    val zone = java.time.ZoneId.systemDefault()
                    val todayStart = java.time.LocalDate.now(zone)
                        .atStartOfDay(zone).toInstant().toEpochMilli()
                    val monthStart = java.time.LocalDate.now(zone).withDayOfMonth(1)
                        .atStartOfDay(zone).toInstant().toEpochMilli()
                    val todayRecords = database.trafficDao().getRecordsSince(todayStart)
                    val monthRecords = database.trafficDao().getRecordsSince(monthStart)
                    todayRx = if (todayRecords.size >= 2) (todayRecords.last().rxBytes - todayRecords.first().rxBytes).coerceAtLeast(0) else 0L
                    todayTx = if (todayRecords.size >= 2) (todayRecords.last().txBytes - todayRecords.first().txBytes).coerceAtLeast(0) else 0L
                    monthRx = if (monthRecords.size >= 2) (monthRecords.last().rxBytes - monthRecords.first().rxBytes).coerceAtLeast(0) else 0L
                    monthTx = if (monthRecords.size >= 2) (monthRecords.last().txBytes - monthRecords.first().txBytes).coerceAtLeast(0) else 0L
                }

                // 累计总量：优先使用 goform 月流量（准确的蜂窝数据），
                // 回退到 Android TrafficStats（仅追踪系统层流量，ZTE 热点上远低于实际值）
                val totalRx = if (monthRx > 0) monthRx else (latest?.rxBytes ?: 0)
                val totalTx = if (monthTx > 0) monthTx else (latest?.txBytes ?: 0)

                call.respond(toJsonElement(mapOf(
                    "total_rx_bytes" to totalRx,
                    "total_tx_bytes" to totalTx,
                    "total_bytes" to (totalRx + totalTx),
                    "total_rx_display" to formatBytes(totalRx),
                    "total_tx_display" to formatBytes(totalTx),
                    "record_count" to totalRecords,
                    "today_rx_display" to formatBytes(todayRx),
                    "today_tx_display" to formatBytes(todayTx),
                    "month_rx_display" to formatBytes(monthRx),
                    "month_tx_display" to formatBytes(monthTx)
                )))
            }
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond >= 1_048_576 -> "%.1f MB/s".format(bytesPerSecond / 1_048_576.0)
            bytesPerSecond >= 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024.0)
            else -> "$bytesPerSecond B/s"
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
