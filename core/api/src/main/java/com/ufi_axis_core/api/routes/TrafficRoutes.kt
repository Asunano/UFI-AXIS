package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.routes.RouteContext
import com.ufi_axis_core.contract.ErrorCode
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
                    // 未预热：保持 HTTP 200，仅把 body 换成统一失败信封（code=NO_DATA_YET）
                    call.respondFail(io.ktor.http.HttpStatusCode.OK, ErrorCode.NO_DATA_YET, "No data yet")
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
            // 字段由 TrafficSummaryMapper 单点产出，与 /api/dashboard/summary 的
            // traffic_summary 完全一致 —— 以前两边各写一份，导致仪表盘缺 today_*。
            get("/summary") {
                val totalRecords = database.trafficDao().getCount()

                // 全部使用 goform 月流量数据（Modem 固件，准确的蜂窝流量）
                val cached = dataScheduler.goformTraffic.value
                var monthRx = 0L; var monthTx = 0L
                if (cached != null && (cached.first > 0 || cached.second > 0)) {
                    monthRx = cached.first; monthTx = cached.second
                }
                val (todayRx, todayTx) = dataScheduler.todayTraffic.value

                call.respond(TrafficSummaryMapper.build(monthRx, monthTx, todayRx, todayTx, totalRecords))
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
}
