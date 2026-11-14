package com.ufi_axis_core.api.traffic

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * `/api/traffic/usage` 的区间划分**纯逻辑**（无 IO、无时钟、无 Android 依赖，可直接单测）。
 *
 * ## 为什么要单独一层
 * 前端要画的是「完整 X 轴」：本周还没到的那几天也必须有柱子（值 0），否则用户没法区分
 * 「那天没用流量」和「那天还没到」。所以桶列表必须由**日历**推导，而不是由数据库里有哪些行
 * 反推 —— 把这件事放在路由里写会让它既不可测、又要和归桶逻辑纠缠。
 *
 * ## 为什么不用等距加法
 * 桶起点一律走 [ZonedDateTime] / [LocalDate] / [YearMonth] 推导，不用 `start + n * 3600_000`：
 * 后者在有 DST 的时区会整体错位（月/年桶更是天生不等长）。国内没有 DST，但这个函数不该
 * 只在国内正确 —— core 也会跑在别的时区。
 *
 * **推论（是正确行为，不要"修"）**：DST 切换日的 [TrafficUsageRange.DAY] 会得到 23 或 25 个桶，
 * 而不是恒 24；此时小时 label 取的是**本地墙上时钟的小时数**，秋季回拨那天会出现两个同名桶
 * （如两个 "1"）。硬凑成 24 个只会让某个小时的流量落到错误的柱子上。
 *
 * ## 为什么不依赖 Locale
 * 周几名称在这里自己映射（[weekdayLabel]）。设备 locale 不确定，`TextStyle.SHORT` 在不同
 * locale 下输出完全不同的文案（"Mon" / "周一" / "月"），而这是给中文 UI 的固定契约。
 */
enum class TrafficUsageRange { DAY, WEEK, MONTH, YEAR }

/**
 * 把查询参数里的 `range` 解析成枚举。
 *
 * 非法值**回落到 [TrafficUsageRange.DAY] 而不是 400**：这个参数只影响展示粒度，
 * 客户端拼错时给一个能画出来的默认视图，比让整块图表变成错误提示有用。
 */
fun parseTrafficUsageRange(raw: String?): TrafficUsageRange =
    when (raw?.trim()?.lowercase()) {
        "week" -> TrafficUsageRange.WEEK
        "month" -> TrafficUsageRange.MONTH
        "year" -> TrafficUsageRange.YEAR
        else -> TrafficUsageRange.DAY
    }

/**
 * 一个桶的**骨架**：只有位置和文案，没有流量值。
 *
 * 流量值由路由把数据库行归进来后另算 —— 让本层保持"给定时区和 anchor 就完全确定"，
 * 可以脱离数据库断言。
 */
data class TrafficUsageBucket(
    /** 从 0 开始、按时间升序连续。前端靠它对齐 X 轴。 */
    val index: Int,
    /** 桶起点（本地时区，epoch ms，含）。 */
    val start: Long,
    /**
     * X 轴刻度文案，**带单位**：日 `"0时".."23时"` / 周 `"周日".."周六"` /
     * 月 `"1日".."31日"` / 年 `"1月".."12月"`。
     *
     * 早先这里是裸数字（`"12"`、`"11"`），结果轴上和明细里全是不知道是几号还是几点的数字。
     * 单位由 core 一次给到位，两端都不要再自己拼 —— 轴上标签本来就是抽稀显示的，宽度够。
     */
    val label: String,
    /**
     * 浮层与明细行用的完整文案：日 `"12时"` / 周 `"9月14日 周一"` / 月 `"9月14日"` / 年 `"2026年11月"`。
     *
     * 与 [label] 分开是因为两者的约束相反：轴上要短（31 个挤一行），浮层里要能独立看懂
     * （"14" 是 14 号还是 14 点？）。让 core 各给一份，省得客户端按 `bucket_unit` 分支拼字符串。
     */
    val title: String
)

/**
 * 一个查询窗口：半开区间 [[startMs], [endMs]) 加上它的桶骨架。
 *
 * 半开区间与 `TrafficHourlyDao.listBetween` 的口径一致，避免整点行被两个相邻窗口各算一次。
 */
data class TrafficUsageWindow(
    val range: TrafficUsageRange,
    /** 窗口起点，含。 */
    val startMs: Long,
    /** 窗口终点，**不含**。 */
    val endMs: Long,
    /** 窗口标题文案，如 `"9月14日 周一"` / `"9月13日-9月19日"` / `"2026年9月"` / `"2026年"`。 */
    val label: String,
    /** 桶粒度：`"hour"` | `"day"` | `"month"`。前端据此决定 tooltip 的时间格式。 */
    val bucketUnit: String,
    val buckets: List<TrafficUsageBucket>
)

/**
 * 求 [anchorMs] 所在的窗口。
 *
 * 口径（四种都是"整个自然区间"，**不截断到今天**）：
 * - [TrafficUsageRange.DAY]：所在本地日，逐小时（通常 24 桶，DST 日 23/25 桶）；
 * - [TrafficUsageRange.WEEK]：所在整周，**周日 → 周六**，7 个日桶；
 * - [TrafficUsageRange.MONTH]：所在自然月，桶数 = 当月实际天数（28/29/30/31）；
 * - [TrafficUsageRange.YEAR]：所在自然年，12 个月桶。
 *
 * 未来的桶照常生成（值为 0）。截断到"今天"会让「本周日均」这类计算在前端失去分母，
 * 也会让图表宽度每天变一次。
 */
fun resolveTrafficUsageWindow(
    range: TrafficUsageRange,
    anchorMs: Long,
    zone: ZoneId
): TrafficUsageWindow {
    val anchorDate = Instant.ofEpochMilli(anchorMs).atZone(zone).toLocalDate()
    return when (range) {
        TrafficUsageRange.DAY -> dayWindow(anchorDate, zone)
        TrafficUsageRange.WEEK -> weekWindow(anchorDate, zone)
        TrafficUsageRange.MONTH -> monthWindow(YearMonth.from(anchorDate), zone)
        TrafficUsageRange.YEAR -> yearWindow(anchorDate.year, zone)
    }
}

/**
 * 找 [hourStart] 属于哪个桶：返回**最后一个 `start <= hourStart`** 的桶下标，落在窗口外返回 -1。
 *
 * 用二分而不是 `(hourStart - startMs) / bucketMs`：月桶 28~31 天、年桶按月长度变化，
 * 除法在这两种粒度下会把月末的流量算到下一个桶。二分只依赖"桶起点升序"这一个前提。
 */
fun TrafficUsageWindow.bucketIndexOf(hourStart: Long): Int {
    if (hourStart < startMs || hourStart >= endMs) return -1
    var lo = 0
    var hi = buckets.size - 1
    var hit = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (buckets[mid].start <= hourStart) {
            hit = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return hit
}

// ── 内部实现 ──

private fun dayWindow(date: LocalDate, zone: ZoneId): TrafficUsageWindow {
    // atStartOfDay(zone) 而不是 atTime(0,0)：DST 春季跳变日的 00:00 可能不存在，
    // 前者会自动落到那天真正的第一个瞬间。
    val start = date.atStartOfDay(zone)
    val end = date.plusDays(1).atStartOfDay(zone)
    val endMs = end.toInstant().toEpochMilli()

    val buckets = ArrayList<TrafficUsageBucket>(24)
    var cursor = start
    var index = 0
    // 逐小时 plusHours：ZonedDateTime 的加法按"瞬间"推进并重算偏移，
    // 所以 DST 日自然得到 23 / 25 个桶，而 label 仍是当地墙上时钟的小时数。
    while (cursor.toInstant().toEpochMilli() < endMs) {
        val hourText = "${cursor.hour}时"
        buckets += TrafficUsageBucket(index++, cursor.toInstant().toEpochMilli(), hourText, hourText)
        cursor = cursor.plusHours(1)
    }


    return TrafficUsageWindow(
        range = TrafficUsageRange.DAY,
        startMs = start.toInstant().toEpochMilli(),
        endMs = endMs,
        label = "${date.monthValue}月${date.dayOfMonth}日 ${weekdayLabel(date.dayOfWeek)}",
        bucketUnit = UNIT_HOUR,
        buckets = buckets
    )
}

private fun weekWindow(date: LocalDate, zone: ZoneId): TrafficUsageWindow {
    // 周日开头：DayOfWeek 是 MONDAY=1..SUNDAY=7，取模 7 后周日归 0，减这个偏移即回到本周周日。
    // 不用 WeekFields.of(locale)：那会随设备 locale 在周一/周日/周六开头之间摇摆。
    val weekStart = date.minusDays((date.dayOfWeek.value % 7).toLong())
    val start = weekStart.atStartOfDay(zone)
    val end = weekStart.plusDays(7).atStartOfDay(zone)

    val buckets = (0 until 7).map { offset ->
        val day = weekStart.plusDays(offset.toLong())
        TrafficUsageBucket(
            offset,
            day.atStartOfDay(zone).toInstant().toEpochMilli(),
            weekdayLabel(day.dayOfWeek),
            "${day.monthValue}月${day.dayOfMonth}日 ${weekdayLabel(day.dayOfWeek)}"
        )
    }

    val weekEndDay = weekStart.plusDays(6)

    return TrafficUsageWindow(
        range = TrafficUsageRange.WEEK,
        startMs = start.toInstant().toEpochMilli(),
        endMs = end.toInstant().toEpochMilli(),
        // 两端都带月份：跨月/跨年的那一周不写月份会看不出是哪段（"27日-2日"）
        label = "${weekStart.monthValue}月${weekStart.dayOfMonth}日-${weekEndDay.monthValue}月${weekEndDay.dayOfMonth}日",
        bucketUnit = UNIT_DAY,
        buckets = buckets
    )
}

private fun monthWindow(month: YearMonth, zone: ZoneId): TrafficUsageWindow {
    val start = month.atDay(1).atStartOfDay(zone)
    val end = month.plusMonths(1).atDay(1).atStartOfDay(zone)

    val buckets = (1..month.lengthOfMonth()).map { day ->
        TrafficUsageBucket(
            day - 1,
            month.atDay(day).atStartOfDay(zone).toInstant().toEpochMilli(),
            "${day}日",
            "${month.monthValue}月${day}日"
        )
    }


    return TrafficUsageWindow(
        range = TrafficUsageRange.MONTH,
        startMs = start.toInstant().toEpochMilli(),
        endMs = end.toInstant().toEpochMilli(),
        label = "${month.year}年${month.monthValue}月",
        bucketUnit = UNIT_DAY,
        buckets = buckets
    )
}

private fun yearWindow(year: Int, zone: ZoneId): TrafficUsageWindow {
    val start = LocalDate.of(year, 1, 1).atStartOfDay(zone)
    val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone)

    val buckets = (1..12).map { m ->
        TrafficUsageBucket(
            m - 1,
            YearMonth.of(year, m).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            "${m}月",
            "${year}年${m}月"
        )
    }


    return TrafficUsageWindow(
        range = TrafficUsageRange.YEAR,
        startMs = start.toInstant().toEpochMilli(),
        endMs = end.toInstant().toEpochMilli(),
        label = "${year}年",
        bucketUnit = UNIT_MONTH,
        buckets = buckets
    )
}

/** 固定中文周几文案，刻意不过 Locale（见类头注释）。 */
private fun weekdayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

// 桶粒度取值，与 JSON 契约里的 `bucket_unit` 一一对应（契约字面量集中在此，别在路由里再写一遍）
private const val UNIT_HOUR = "hour"
private const val UNIT_DAY = "day"
private const val UNIT_MONTH = "month"
