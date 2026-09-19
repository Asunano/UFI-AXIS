package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.routes.RouteContext
import com.ufi_axis_core.api.traffic.bucketIndexOf
import com.ufi_axis_core.api.traffic.parseTrafficUsageRange
import com.ufi_axis_core.api.traffic.resolveTrafficUsageWindow
import com.ufi_axis_core.contract.ErrorCode
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.ZoneId

/**
 * 流量统计路由
 * GET /api/traffic/realtime  - 实时网速
 * GET /api/traffic/history   - 流量历史
 * GET /api/traffic/summary   - 流量汇总（优先使用 Goform 月流量数据）
 * GET /api/traffic/usage     - 日/周/月/年用量分桶（数据源 traffic_hourly）
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

            // 日/周/月/年用量分桶（数据源 traffic_hourly，由 DataScheduler 按小时累加增量）
            //
            // 三处刻意的取舍：
            // 1. 空桶照样返回。前端要画完整 X 轴，缺桶会让"还没到的日子"和"那天没用流量"长得一样；
            // 2. peak_bytes 在核心侧算。两端各算一遍迟早不一致，而它直接决定柱子高度；
            // 3. earliest_data_at 表空时给 null 而不是 0。给 0 会被当成 1970 年的真实时间戳，
            //    前端就没法诚实提示"历史只从某天起才有数据"。
            get("/usage") {
                // 与写入侧 TrafficHourlyAccumulator.hourStartOf 同一口径：设备本地时区。
                // 换成 UTC 会让每天的第一个小时桶落到前一天，日/周/月/年边界全部错位。
                val zone = ZoneId.systemDefault()
                // 两个参数都不回 400：range 非法 → day，anchor 解析不出数字 → now。
                // 这是纯展示型查询，给一个能画出来的窗口比让整块图表变成错误提示有用。
                val range = parseTrafficUsageRange(call.request.queryParameters["range"])
                val anchor = call.request.queryParameters["anchor"]?.toLongOrNull()
                    ?: System.currentTimeMillis()
                val window = resolveTrafficUsageWindow(range, anchor, zone)

                // 一次取回整窗口的小时行再在内存归桶：按桶各查一次在"年"视图下是 12 趟 IO，
                // 而一年的行数上限只有 8760 行，一次取回更省。
                val rows = database.trafficHourlyDao().listBetween(window.startMs, window.endMs)
                val rxByBucket = LongArray(window.buckets.size)
                val txByBucket = LongArray(window.buckets.size)
                rows.forEach { row ->
                    // 二分查找归桶（月/年桶不等长，除法会把月末算到下一个桶）。
                    // listBetween 用的是同一个半开区间，理论上不会落在窗口外；这里仍显式丢弃 -1 ——
                    // 万一口径漂移，宁可少一行，也不要静默塞进 0 号桶造出假柱子。
                    val idx = window.bucketIndexOf(row.hourStart)
                    if (idx >= 0) {
                        rxByBucket[idx] += row.rxBytes
                        txByBucket[idx] += row.txBytes
                    }
                }

                var totalRx = 0L
                var totalTx = 0L
                var peak = 0L
                val buckets = window.buckets.map { bucket ->
                    val rx = rxByBucket[bucket.index]
                    val tx = txByBucket[bucket.index]
                    val total = rx + tx
                    totalRx += rx
                    totalTx += tx
                    if (total > peak) peak = total
                    mapOf(
                        "index" to bucket.index,
                        "start" to bucket.start,
                        "label" to bucket.label,
                        "title" to bucket.title,
                        "rx_bytes" to rx,
                        "tx_bytes" to tx,
                        "total_bytes" to total
                    )
                }

                call.respond(toJsonElement(mapOf(
                    "range" to range.name.lowercase(),
                    "range_start" to window.startMs,
                    "range_end" to window.endMs,
                    "label" to window.label,
                    "bucket_unit" to window.bucketUnit,
                    "buckets" to buckets,
                    "total_rx_bytes" to totalRx,
                    "total_tx_bytes" to totalTx,
                    "total_bytes" to (totalRx + totalTx),
                    "peak_bytes" to peak,
                    // 左右翻页用的锚点：客户端不必自己做日历运算（月长度、闰年、DST 都在这里已经算对了）。
                    // prev 取"窗口起点前 1ms"、next 取"窗口终点"，都落在相邻窗口内部。
                    "prev_anchor" to (window.startMs - 1),
                    "next_anchor" to window.endMs,
                    // 下一段是否已经开始：为 false 时客户端不该让用户往未来翻（翻过去只有空柱）
                    "has_next" to (window.endMs <= System.currentTimeMillis()),
                    // 表空时是 null，toJsonElement 会写成 JSON null
                    "earliest_data_at" to database.trafficHourlyDao().earliestHourStart()
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
}
