package com.ufi_axis_core.api.traffic

import com.ufi_axis_core.core.database.TrafficHourlyDao
import com.ufi_axis_core.util.AppLogger
import java.util.Calendar
import java.util.TimeZone

/**
 * 流量日报/周报/月报（2026-10-06 实用功能批·拍板第4项）。
 *
 * 数据面：traffic_hourly（累加语义的小时桶，listBetween 现成）。
 * 出桶：日=自然日；周=自然周（周一为起点，用户语义「一周内每日使用情况」）；
 *      月=自然月。时区用设备默认——与 web 流量页（trafficHistoryShared）同一口径。
 *
 * 消费方：TrafficRoutes 的 GET /report?period=day|week|month（页面展示）；
 * 定时任务/通知走 `traffic_report` 动作（period 放 params），文案渲染在通知侧。
 */
class TrafficReportGenerator(private val dao: TrafficHourlyDao) {

    companion object {
        private const val TAG = "TrafficReport"
        private val TZ: TimeZone = TimeZone.getDefault()
    }

    data class DayBucket(val dayStart: Long, val rx: Long, val tx: Long) {
        val total: Long get() = rx + tx
    }

    data class Report(
        val period: String,
        val rangeStart: Long,
        val rangeEnd: Long,
        val days: List<DayBucket>,
    ) {
        val rx: Long get() = days.sumOf { it.rx }
        val tx: Long get() = days.sumOf { it.tx }
        val total: Long get() = rx + tx
    }

    private fun dayStartOf(ms: Long): Long {
        val c = Calendar.getInstance(TZ).apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    private fun addDays(dayStart: Long, n: Int): Long {
        val c = Calendar.getInstance(TZ).apply { timeInMillis = dayStart; add(Calendar.DAY_OF_MONTH, n) }
        return c.timeInMillis
    }

    /** 本周一 0 点（ISO，周一为一周起点） */
    private fun weekStartOf(ms: Long): Long {
        val d = dayStartOf(ms)
        val c = Calendar.getInstance(TZ).apply { timeInMillis = d }
        val dow = c.get(Calendar.DAY_OF_WEEK) // SUNDAY=1 .. SATURDAY=7
        val back = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
        return addDays(d, -back)
    }

    private fun monthStartOf(ms: Long): Long {
        val c = Calendar.getInstance(TZ).apply {
            timeInMillis = ms
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    /**
     * 生成报告。[period] = day（今天）/ week（本周）/ month（本月）。
     * 返回按日的桶（含当日/当周至当日/当月至今），数据缺失的日期补零桶，
     * 保证「周报里一周内每日使用情况」是完整 7 行。
     */
    suspend fun generate(period: String, now: Long = System.currentTimeMillis()): Report {
        val (start, end) = when (period) {
            "day" -> dayStartOf(now) to addDays(dayStartOf(now), 1)
            "week" -> weekStartOf(now) to addDays(weekStartOf(now), 7)
            "month" -> monthStartOf(now) to run {
                val c = Calendar.getInstance(TZ).apply { timeInMillis = monthStartOf(now); add(Calendar.MONTH, 1) }
                c.timeInMillis
            }
            else -> throw IllegalArgumentException("period must be day|week|month")
        }
        val hourly = runCatching { dao.listBetween(start, end) }
            .onFailure { AppLogger.w(TAG, "traffic report query failed: ${it.message}") }
            .getOrDefault(emptyList())

        // 聚到日桶
        val byDay = HashMap<Long, LongArray>() // [rx, tx]
        hourly.forEach { h ->
            val d = dayStartOf(h.hourStart)
            val acc = byDay.getOrPut(d) { LongArray(2) }
            acc[0] += h.rxBytes; acc[1] += h.txBytes
        }

        // 补零桶：完整覆盖区间每一天
        val days = mutableListOf<DayBucket>()
        var cur = start
        while (cur < end) {
            val acc = byDay[cur]
            days.add(DayBucket(cur, acc?.get(0) ?: 0L, acc?.get(1) ?: 0L))
            cur = addDays(cur, 1)
        }
        return Report(period, start, end, days)
    }

    /**
     * 通知/任务历史用的人话摘要（单行，用于任务历史）。
     * 周报示例：本周 12.3 GB（↓11.0 GB ↑1.3 GB），日均 1.8 GB，最高周六 4.1 GB
     */
    fun summarize(r: Report): String = summarizeLine(r)

    /**
     * 通知正文（多行）：首行摘要 + **每日明细**（用户 2026-10-06 明确要求「周报中显示
     * 一周内每日的使用情况」）。日/周/月三种都有明细；无数据的日期显示 0。
     */
    fun summarizeDetailed(r: Report): String {
        val fmt = java.text.SimpleDateFormat("MM-dd E", java.util.Locale.CHINA)
        val sb = StringBuilder(summarizeLine(r))
        r.days.forEach { d ->
            sb.append('\n').append(fmt.format(java.util.Date(d.dayStart)))
                .append("  ↓").append(unit(d.rx)).append(" ↑").append(unit(d.tx))
                .append("  合计 ").append(unit(d.total))
        }
        return sb.toString()
    }

    private fun unit(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
        else -> "$b B"
    }

    private fun summarizeLine(r: Report): String {
        val peak = r.days.maxByOrNull { it.total }
        val dayCount = maxOf(1, r.days.count { it.total > 0 })
        val sb = StringBuilder()
        when (r.period) {
            "day" -> sb.append("今日流量 ")
            "week" -> sb.append("本周流量 ")
            "month" -> sb.append("本月流量 ")
        }
        sb.append(unit(r.total)).append("（↓").append(unit(r.rx)).append(" ↑").append(unit(r.tx)).append("）")
        if (r.period != "day") {
            sb.append("，日均 ").append(unit(r.total / dayCount))
            peak?.let {
                val name = java.text.SimpleDateFormat("E", java.util.Locale.CHINA).format(java.util.Date(it.dayStart))
                if (it.total > 0) sb.append("，最高").append(name).append(" ").append(unit(it.total))
            }
        }
        return sb.toString()
    }
}
