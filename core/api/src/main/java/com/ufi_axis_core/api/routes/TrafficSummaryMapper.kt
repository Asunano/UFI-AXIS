package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.util.formatDataSize
import kotlinx.serialization.json.JsonElement

/**
 * 流量汇总（traffic_summary）字段的唯一产出点。
 *
 * 背景：`/api/traffic/summary` 与 `/api/dashboard/summary` 里的 `traffic_summary`
 * 原本各写一份，结果 **只有 TrafficRoutes 输出 today_rx_display / today_tx_display**，
 * DashboardRoutes 那份没有 —— 而 Web 仪表盘和 app 的 MonitorOverview 都只读
 * `/api/dashboard/summary`，所以「今日已用」永远是 `--`。和流量限额那次是同一类漂移，
 * 因此这里也走 mapper 单点产出。
 *
 * 「今日已用」不是 goform 字段（设备只给当月累计），由 DataScheduler 自算并持久化基线，
 * 见 `DataScheduler.advanceTodayTraffic` / `DataScheduler.todayTraffic`。
 * 本 mapper 只负责组装，不参与计算，避免又出现第二套算法。
 */
object TrafficSummaryMapper {

    /**
     * 组装 traffic_summary。
     *
     * today_rx_bytes / today_tx_bytes 是新增字段：老版本只给格式化字符串，
     * 前端想做换算/百分比只能反解字符串。字符串字段保留，app 的
     * `TrafficSummary` 数据类和 Web 都还在读它们。
     */
    fun build(monthRx: Long, monthTx: Long, todayRx: Long, todayTx: Long, recordCount: Int): JsonElement =
        toJsonElement(mapOf(
            "total_rx_bytes" to monthRx,
            "total_tx_bytes" to monthTx,
            "total_bytes" to (monthRx + monthTx),
            "total_rx_display" to formatBytes(monthRx),
            "total_tx_display" to formatBytes(monthTx),
            "record_count" to recordCount,
            "today_rx_bytes" to todayRx,
            "today_tx_bytes" to todayTx,
            "today_rx_display" to formatBytes(todayRx),
            "today_tx_display" to formatBytes(todayTx),
            // 今日合计：监控中心「今日流量」要的是上下行总量。之前前端只读 today_rx_display，
            // 显示的其实是单方向，这里直接给出合计，避免客户端各算一遍。
            "today_total_bytes" to (todayRx + todayTx),
            "today_total_display" to formatBytes(todayRx + todayTx),
            "month_rx_display" to formatBytes(monthRx),
            "month_tx_display" to formatBytes(monthTx)
        ))

    /**
     * 1024 进制，与 TrafficLimitMapper 的 toBytes 口径一致。
     *
     * 2026-09-02：改为委托 core:common 的 [formatDataSize]。此前这里与
     * TrafficAutoOffGuard 各有一份同样的实现，且都只到 GB —— 告警文案与邮件文案
     * 在 TB 级累计流量上会给出不同的数字。统一之后 GB 保留 1 位小数（原为 2 位）。
     */
    fun formatBytes(bytes: Long): String = formatDataSize(bytes)
}

