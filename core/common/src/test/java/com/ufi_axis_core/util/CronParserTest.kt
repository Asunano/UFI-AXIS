package com.ufi_axis_core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * CronParser 单元测试（v9 §6.1）：
 * - 7 preset 各自测一次 nextAfter
 * - 边界：闰年 2/29、月末 31、跨年 12/31 → 1/1
 * - matches 字段匹配边界
 * - toCron / toScheduleValue 双向映射
 * - v10 移除 EVERY_N_SECONDS 后增加回退测试（fromType("every_n_seconds")→DAILY，
 *   旧任务的 scheduleType 字段会安全降级）
 */
class CronParserTest {

    private val zone = ZoneId.systemDefault()

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi, 0).atZone(zone).toInstant().toEpochMilli()

    private fun nextFields(cron: String, from: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(CronParser.nextAfter(cron, from)!!), zone)

    @Test
    fun daily_next() {
        val next = nextFields("30 8 * * *", at(2026, 8, 18, 9, 0))
        assertEquals(8, next.hour)
        assertEquals(30, next.minute)
        assertEquals(19, next.dayOfMonth) // 9:00 之后下一个 08:30 是明天
    }

    @Test
    fun weekly_next() {
        // 2026-8-18 为周二；周一三五(1,3,5) 下一个为周三 8-19 19:30
        val next = nextFields("30 19 * * 1,3,5", at(2026, 8, 18, 9, 0))
        assertTrue(next.dayOfWeek.value in listOf(1, 3, 5))
        assertEquals(19, next.hour)
        assertEquals(30, next.minute)
    }

    @Test
    fun monthly_next() {
        val next = nextFields("0 12 1,15 * *", at(2026, 8, 18, 9, 0))
        assertTrue(next.dayOfMonth == 1 || next.dayOfMonth == 15)
        assertEquals(12, next.hour)
    }

    @Test
    fun every_n_days() {
        // */3 日：从 8-18 之后第一个满足 (d-1)%3==0 的是 8-19
        val next = nextFields("0 0 */3 * *", at(2026, 8, 18, 9, 0))
        assertEquals(19, next.dayOfMonth)
        assertEquals(0, next.hour)
    }

    @Test
    fun every_n_hours() {
        val next = nextFields("0 */6 * * *", at(2026, 8, 18, 9, 0))
        assertEquals(12, next.hour)
        assertEquals(0, next.minute)
    }

    @Test
    fun every_n_minutes() {
        val next = nextFields("*/15 * * * *", at(2026, 8, 18, 9, 0))
        assertEquals(9, next.hour)
        assertEquals(15, next.minute)
    }

    @Test
    fun custom_preset() {
        // 工作日 08:30，从 2026-8-18(周二) 09:00 → 次日(周三) 08:30
        val next = nextFields("30 8 * * 1-5", at(2026, 8, 18, 9, 0))
        assertTrue(next.dayOfWeek.value in 1..5)
        assertEquals(8, next.hour)
    }

    @Test
    fun leap_feb29() {
        // 2025-03-01 之后，下一个 2/29 是 2028-02-29
        val next = CronParser.nextAfter("0 0 29 2 *", at(2025, 3, 1, 0, 0))
        assertNotNull(next)
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(next!!), zone)
        assertEquals(2028, ldt.year)
        assertEquals(2, ldt.monthValue)
        assertEquals(29, ldt.dayOfMonth)
    }

    @Test
    fun month_end_31() {
        // 每月 31 日，从 2026-01-15 → 2026-01-31
        val next = nextFields("0 0 31 * *", at(2026, 1, 15, 0, 0))
        assertEquals(1, next.monthValue)
        assertEquals(31, next.dayOfMonth)
    }

    @Test
    fun year_end_to_next() {
        // 每周日 00:00，从 2026-12-31(周四) 01:00 → 2027-01-03(周日)
        val next = nextFields("0 0 * * 0", at(2026, 12, 31, 1, 0))
        assertEquals(2027, next.year)
        assertEquals(1, next.monthValue)
        assertEquals(3, next.dayOfMonth)
    }

    @Test
    fun matches_boundary() {
        val t = at(2026, 8, 18, 8, 30)
        assertTrue(CronParser.matches("30 8 * * *", t))
        assertFalse(CronParser.matches("31 8 * * *", t))
        assertFalse(CronParser.matches("30 9 * * *", t))
    }

    @Test
    fun isValid_boundary() {
        assertTrue(CronParser.isValid("30 8 * * 1-5"))
        assertFalse(CronParser.isValid("30 8 * *"))
        assertFalse(CronParser.isValid("99 8 * * *"))
    }

    @Test
    fun toCron_roundtrip_weekly() {
        val cron = CronParser.toCron("weekly", 19, 30, setOf(1, 3, 5), emptySet(), 1)
        assertEquals("30 19 * * 1,3,5", cron)
        val sv = CronParser.toScheduleValue(cron!!, "weekly")
        assertEquals(setOf(1, 3, 5), sv.weekDays)
        assertEquals(19, sv.hour)
        assertEquals(30, sv.minute)
    }

    @Test
    fun toCron_roundtrip_every_n_days() {
        val cron = CronParser.toCron("every_n_days", 8, 0, emptySet(), emptySet(), 3)
        assertEquals("0 8 */3 * *", cron)
        val sv = CronParser.toScheduleValue(cron!!, "every_n_days")
        assertEquals(3, sv.intervalN)
        assertEquals(8, sv.hour)
    }

    @Test
    fun preset_fromType_legacySecondsFallsBackToDaily() {
        // v10 移除 EVERY_N_SECONDS；旧任务的 scheduleType="every_n_seconds" 必须安全降级
        assertEquals(CronParser.SchedulePreset.DAILY, CronParser.SchedulePreset.fromType("every_n_seconds"))
        assertEquals(CronParser.SchedulePreset.DAILY, CronParser.SchedulePreset.fromType(null))
        assertEquals(CronParser.SchedulePreset.DAILY, CronParser.SchedulePreset.fromType(""))
        assertEquals(CronParser.SchedulePreset.MONTHLY, CronParser.SchedulePreset.fromType("monthly"))
    }
}
