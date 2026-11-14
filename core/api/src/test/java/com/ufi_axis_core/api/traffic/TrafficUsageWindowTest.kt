package com.ufi_axis_core.api.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [resolveTrafficUsageWindow] 的边界护栏。
 *
 * 这里断言的每一条都会直接改变前端画出来的图：
 * 1. 桶数量错 ⇒ X 轴少一根/多一根柱子，月末或闰日的流量落到隔壁；
 * 2. 首尾桶起点错 ⇒ 整条曲线相对时间轴平移（UTC/本地时区混用最常见的表现）；
 * 3. 周起点错 ⇒ 「本周」和用户日历上的本周不是同一段，用户会以为流量统计坏了；
 * 4. label 文案错 ⇒ app 端按契约直接渲染，没有二次加工的机会。
 *
 * 时区与 anchor 全部固定，不碰 `System.currentTimeMillis()` —— 否则测试会在某天凌晨/月末自己变红。
 */
class TrafficUsageWindowTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    /** 用日历构造 anchor，比手写 epoch 数字可读且不会算错。 */
    private fun at(y: Int, m: Int, d: Int, h: Int = 13, min: Int = 37): Long =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, shanghai).toInstant().toEpochMilli()

    private fun dayStart(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(shanghai).toInstant().toEpochMilli()

    // ── DAY ──

    @Test
    fun dayWindowCoversLocalDayInTwentyFourHourBuckets() {
        // 2026-09-14 是周一（label 里的周几靠自己映射，不靠 Locale）
        val w = resolveTrafficUsageWindow(TrafficUsageRange.DAY, at(2026, 9, 14), shanghai)

        assertEquals(TrafficUsageRange.DAY, w.range)
        assertEquals(dayStart(2026, 9, 14), w.startMs)
        assertEquals("终点是次日 00:00 且不含", dayStart(2026, 9, 15), w.endMs)
        assertEquals("9月14日 周一", w.label)
        assertEquals("hour", w.bucketUnit)

        assertEquals(24, w.buckets.size)
        assertEquals(w.startMs, w.buckets.first().start)
        assertEquals("0时", w.buckets.first().label)
        assertEquals("23时", w.buckets.last().label)
        // title 与 label 同值：小时本身已经带单位，不需要再拼日期（页头已经写了是哪天）
        assertEquals("0时", w.buckets.first().title)
        assertEquals(w.startMs + 23 * 3_600_000L, w.buckets.last().start)
        // index 必须 0 起连续：前端拿它当数组下标
        w.buckets.forEachIndexed { i, b -> assertEquals(i, b.index) }
    }

    @Test
    fun dayWindowIsIndependentOfTimeWithinTheDay() {
        // 同一天的任意时刻必须落到同一个窗口，否则每次刷新图表都会跳
        val early = resolveTrafficUsageWindow(TrafficUsageRange.DAY, at(2026, 9, 14, h = 0, min = 0), shanghai)
        val late = resolveTrafficUsageWindow(TrafficUsageRange.DAY, at(2026, 9, 14, h = 23, min = 59), shanghai)
        assertEquals(early, late)
    }

    @Test
    fun dayWindowFollowsCalendarAcrossDstTransitions() {
        // 桶起点由日历推导而非 start + n*3600_000 的直接后果：DST 日不是 24 桶。
        // 这是正确行为 —— 硬凑 24 会让切换点之后的每个小时都错位一格。
        val newYork = ZoneId.of("America/New_York")
        val springForward = ZonedDateTime.of(2026, 3, 8, 12, 0, 0, 0, newYork).toInstant().toEpochMilli()
        val fallBack = ZonedDateTime.of(2026, 11, 1, 12, 0, 0, 0, newYork).toInstant().toEpochMilli()

        val spring = resolveTrafficUsageWindow(TrafficUsageRange.DAY, springForward, newYork)
        assertEquals("春季跳表日只有 23 小时", 23, spring.buckets.size)
        assertEquals(23 * 3_600_000L, spring.endMs - spring.startMs)

        val fall = resolveTrafficUsageWindow(TrafficUsageRange.DAY, fallBack, newYork)
        assertEquals("秋季回拨日有 25 小时", 25, fall.buckets.size)
        assertEquals(25 * 3_600_000L, fall.endMs - fall.startMs)
        // 回拨日会出现两个同名桶（两个 "1"）：label 取的是墙上时钟，start 仍严格升序
        assertTrue(fall.buckets.zipWithNext().all { (a, b) -> a.start < b.start })
    }

    // ── WEEK ──

    @Test
    fun weekWindowStartsOnSundayAndSpansSevenDays() {
        // anchor 是周一，本周必须回退到 9-13（周日），不是"周一开头"也不是"到今天为止"
        val w = resolveTrafficUsageWindow(TrafficUsageRange.WEEK, at(2026, 9, 14), shanghai)

        assertEquals(dayStart(2026, 9, 13), w.startMs)
        assertEquals(dayStart(2026, 9, 20), w.endMs)
        assertEquals("9月13日-9月19日", w.label)
        assertEquals("day", w.bucketUnit)
        assertEquals(7, w.buckets.size)
        assertEquals(
            listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六"),
            w.buckets.map { it.label }
        )
        assertEquals(dayStart(2026, 9, 13), w.buckets.first().start)
        assertEquals("未来的日子照常出桶（值由调用方填 0），不截断到今天", dayStart(2026, 9, 19), w.buckets.last().start)
    }

    @Test
    fun weekWindowAnchoredOnSundayItselfDoesNotJumpBackAWeek() {
        val w = resolveTrafficUsageWindow(TrafficUsageRange.WEEK, at(2026, 9, 13), shanghai)
        assertEquals(dayStart(2026, 9, 13), w.startMs)
        assertEquals(dayStart(2026, 9, 20), w.endMs)
        assertEquals("9月13日-9月19日", w.label)
    }

    @Test
    fun weekWindowAnchoredOnSaturdayItselfStaysInTheSameWeek() {
        val w = resolveTrafficUsageWindow(TrafficUsageRange.WEEK, at(2026, 9, 19, h = 23, min = 59), shanghai)
        assertEquals(dayStart(2026, 9, 13), w.startMs)
        assertEquals(dayStart(2026, 9, 20), w.endMs)
        assertEquals("9月13日-9月19日", w.label)
    }

    @Test
    fun weekWindowCrossesYearBoundary() {
        // 2026-12-31 是周四 ⇒ 本周 12-27（周日）~ 2027-01-02（周六），窗口跨年
        val w = resolveTrafficUsageWindow(TrafficUsageRange.WEEK, at(2026, 12, 31), shanghai)
        assertEquals(dayStart(2026, 12, 27), w.startMs)
        assertEquals(dayStart(2027, 1, 3), w.endMs)
        assertEquals("12月27日-1月2日", w.label)
        assertEquals(7, w.buckets.size)
        assertEquals(dayStart(2027, 1, 2), w.buckets.last().start)
    }

    // ── MONTH ──

    @Test
    fun monthWindowUsesActualDayCount() {
        val w = resolveTrafficUsageWindow(TrafficUsageRange.MONTH, at(2026, 9, 14), shanghai)

        assertEquals(dayStart(2026, 9, 1), w.startMs)
        assertEquals(dayStart(2026, 10, 1), w.endMs)
        assertEquals("2026年9月", w.label)
        assertEquals("day", w.bucketUnit)
        assertEquals(30, w.buckets.size)
        assertEquals("1日", w.buckets.first().label)
        assertEquals("30日", w.buckets.last().label)
        // 浮层/明细用的完整文案要带月份，光"30日"在跨月翻页时看不出是哪个月
        assertEquals("9月30日", w.buckets.last().title)
        assertEquals(dayStart(2026, 9, 30), w.buckets.last().start)
    }

    @Test
    fun monthWindowIsStableOnFirstAndLastDay() {
        val onFirst = resolveTrafficUsageWindow(TrafficUsageRange.MONTH, at(2026, 9, 1, h = 0, min = 0), shanghai)
        val onLast = resolveTrafficUsageWindow(TrafficUsageRange.MONTH, at(2026, 9, 30, h = 23, min = 59), shanghai)
        assertEquals(onFirst, onLast)
        assertEquals(dayStart(2026, 9, 1), onFirst.startMs)
    }

    @Test
    fun februaryBucketCountFollowsLeapYear() {
        val leap = resolveTrafficUsageWindow(TrafficUsageRange.MONTH, at(2024, 2, 10), shanghai)
        assertEquals("闰年 2 月 29 桶", 29, leap.buckets.size)
        assertEquals("29日", leap.buckets.last().label)
        assertEquals(dayStart(2024, 3, 1), leap.endMs)
        assertEquals("2024年2月", leap.label)

        val common = resolveTrafficUsageWindow(TrafficUsageRange.MONTH, at(2026, 2, 10), shanghai)
        assertEquals("平年 2 月 28 桶", 28, common.buckets.size)
        assertEquals(dayStart(2026, 3, 1), common.endMs)

        val jan = resolveTrafficUsageWindow(TrafficUsageRange.MONTH, at(2026, 1, 31), shanghai)
        assertEquals(31, jan.buckets.size)
        assertEquals("月末 anchor 不能溢出到下个月", dayStart(2026, 2, 1), jan.endMs)

        val dec = resolveTrafficUsageWindow(TrafficUsageRange.MONTH, at(2026, 12, 5), shanghai)
        assertEquals("12 月的下一个月是次年 1 月", dayStart(2027, 1, 1), dec.endMs)
        assertEquals("2026年12月", dec.label)
    }

    // ── YEAR ──

    @Test
    fun yearWindowHasTwelveMonthBuckets() {
        val w = resolveTrafficUsageWindow(TrafficUsageRange.YEAR, at(2026, 9, 14), shanghai)

        assertEquals(dayStart(2026, 1, 1), w.startMs)
        assertEquals(dayStart(2027, 1, 1), w.endMs)
        assertEquals("2026年", w.label)
        assertEquals("month", w.bucketUnit)
        assertEquals(12, w.buckets.size)
        assertEquals("1月", w.buckets.first().label)
        assertEquals("12月", w.buckets.last().label)
        assertEquals("2026年12月", w.buckets.last().title)
        assertEquals(dayStart(2026, 12, 1), w.buckets.last().start)
        // 月桶不等长：3 月桶起点必须是 3-1，等距加法会算偏
        assertEquals(dayStart(2026, 3, 1), w.buckets[2].start)
    }

    @Test
    fun yearWindowOnLastInstantOfYearStaysInThatYear() {
        val w = resolveTrafficUsageWindow(TrafficUsageRange.YEAR, at(2026, 12, 31, h = 23, min = 59), shanghai)
        assertEquals(dayStart(2026, 1, 1), w.startMs)
        assertEquals(dayStart(2027, 1, 1), w.endMs)
    }

    // ── 归桶 ──

    @Test
    fun bucketIndexUsesCalendarBucketsNotEqualDivision() {
        val year = resolveTrafficUsageWindow(TrafficUsageRange.YEAR, at(2026, 6, 1), shanghai)

        assertEquals("1 月的第一个小时 → 第 0 桶", 0, year.bucketIndexOf(year.startMs))
        assertEquals("3 月 1 日 00:00 正好是桶起点 → 第 2 桶", 2, year.bucketIndexOf(dayStart(2026, 3, 1)))
        assertEquals(
            "月桶不等长，除法会把 3-31 算到 4 月",
            2,
            year.bucketIndexOf(ZonedDateTime.of(2026, 3, 31, 23, 0, 0, 0, shanghai).toInstant().toEpochMilli())
        )
        assertEquals(11, year.bucketIndexOf(dayStart(2026, 12, 31)))
        assertEquals("窗口前一小时不属于任何桶", -1, year.bucketIndexOf(year.startMs - 3_600_000L))
        assertEquals("endMs 不含", -1, year.bucketIndexOf(year.endMs))
    }

    @Test
    fun bucketIndexCoversEveryHourOfAMonthWindow() {
        val month = resolveTrafficUsageWindow(TrafficUsageRange.MONTH, at(2024, 2, 15), shanghai)
        // 逐小时扫全窗口：每个小时都必须能落到某个桶，且落点单调不回退
        var hour = month.startMs
        var last = -1
        var visited = 0
        while (hour < month.endMs) {
            val idx = month.bucketIndexOf(hour)
            assertTrue("该小时未归入任何桶: " + hour, idx >= 0)
            assertTrue("归桶必须随时间单调", idx >= last)
            last = idx
            visited++
            hour += 3_600_000L
        }
        assertEquals(29 * 24, visited)
        assertEquals(28, last)
    }

    // ── 参数解析 ──

    @Test
    fun rangeParsingFallsBackToDay() {
        assertEquals(TrafficUsageRange.DAY, parseTrafficUsageRange("day"))
        assertEquals(TrafficUsageRange.WEEK, parseTrafficUsageRange("week"))
        assertEquals(TrafficUsageRange.MONTH, parseTrafficUsageRange("month"))
        assertEquals(TrafficUsageRange.YEAR, parseTrafficUsageRange("year"))
        // 大小写与空白容错：客户端拼 URL 时最常出这两种问题
        assertEquals(TrafficUsageRange.WEEK, parseTrafficUsageRange("WEEK"))
        assertEquals(TrafficUsageRange.MONTH, parseTrafficUsageRange(" Month "))
        // 非法值一律 DAY，不报错：这个参数只影响展示粒度
        assertEquals(TrafficUsageRange.DAY, parseTrafficUsageRange(null))
        assertEquals(TrafficUsageRange.DAY, parseTrafficUsageRange(""))
        assertEquals(TrafficUsageRange.DAY, parseTrafficUsageRange("weeks"))
        assertEquals(TrafficUsageRange.DAY, parseTrafficUsageRange("hour"))
        assertEquals(TrafficUsageRange.DAY, parseTrafficUsageRange("2026-09-14"))
    }
}
