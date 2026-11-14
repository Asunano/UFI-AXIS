package com.ufi_axis_core.controller.carrier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备级账单策略的纯逻辑测试（2026-11-12）。
 *
 * 调度器本身需要 Android Context，这里只钉住最容易回归的两件事：
 * 1. 取值域收敛（UI 与 core 的范围必须一致）；
 * 2. 旧的每账号配置如何收拢成设备策略（升级不能静默关掉用户已开的保活）。
 */
class CarrierBillSettingsTest {

    @Test
    fun `normalized clamps every field to its documented range`() {
        val tooSmall = CarrierBillSettings(
            refreshIntervalMinutes = 0,
            checkIntervalMinutes = 0,
            reloginIntervalMinutes = 1,
            dailySmsLimit = 0,
        ).normalized()
        assertEquals(CarrierBillSettings.REFRESH_MIN, tooSmall.refreshIntervalMinutes)
        assertEquals(CarrierBillSettings.CHECK_MIN, tooSmall.checkIntervalMinutes)
        assertEquals(CarrierBillSettings.RELOGIN_MIN, tooSmall.reloginIntervalMinutes)
        assertEquals(CarrierBillSettings.DAILY_MIN, tooSmall.dailySmsLimit)

        val tooLarge = CarrierBillSettings(
            refreshIntervalMinutes = 999,
            checkIntervalMinutes = 999,
            reloginIntervalMinutes = 999,
            dailySmsLimit = 999,
        ).normalized()
        assertEquals(CarrierBillSettings.REFRESH_MAX, tooLarge.refreshIntervalMinutes)
        assertEquals(CarrierBillSettings.CHECK_MAX, tooLarge.checkIntervalMinutes)
        assertEquals(CarrierBillSettings.RELOGIN_MAX, tooLarge.reloginIntervalMinutes)
        assertEquals(CarrierBillSettings.DAILY_MAX, tooLarge.dailySmsLimit)
    }

    @Test
    fun `fromLegacy keeps defaults when no account enabled auto login`() {
        val policy = CarrierBillSettings.fromLegacy(
            listOf(
                CarrierAutoLoginConfig(enabled = false),
                CarrierAutoLoginConfig(enabled = false, checkIntervalMinutes = 30),
            )
        )
        assertFalse(policy.keepAliveEnabled)
        assertEquals(CarrierBillSettings.DEFAULT_CHECK_MINUTES, policy.checkIntervalMinutes)
        assertEquals(CarrierBillSettings.DEFAULT_DAILY_SMS, policy.dailySmsLimit)
        // 后台刷新是 2026-11-09 起的既有行为，升级不应把它关掉。
        assertTrue(policy.refreshEnabled)
    }

    @Test
    fun `fromLegacy enables keep alive and sums per-account budgets`() {
        val policy = CarrierBillSettings.fromLegacy(
            listOf(
                CarrierAutoLoginConfig(
                    enabled = true,
                    checkIntervalMinutes = 15,
                    reloginIntervalMinutes = 60,
                    maxAttemptsPerDay = 6,
                ),
                CarrierAutoLoginConfig(enabled = true, maxAttemptsPerDay = 6),
                CarrierAutoLoginConfig(enabled = false, maxAttemptsPerDay = 20),
            )
        )
        assertTrue(policy.keepAliveEnabled)
        assertEquals(15, policy.checkIntervalMinutes)
        assertEquals(60, policy.reloginIntervalMinutes)
        // 6 + 6 = 12，且忽略未参与账号的额度。
        assertEquals(12, policy.dailySmsLimit)
    }

    @Test
    fun `local day helpers use the calendar day, not a rolling 24h window`() {
        val zone = java.time.ZoneId.systemDefault()
        val late = java.time.ZonedDateTime.of(2026, 11, 13, 23, 59, 0, 0, zone)
            .toInstant().toEpochMilli()
        val start = startOfLocalDay(late)
        val startDate = java.time.Instant.ofEpochMilli(start).atZone(zone)
        assertEquals(2026, startDate.year)
        assertEquals(11, startDate.monthValue)
        assertEquals(13, startDate.dayOfMonth)
        assertEquals(0, startDate.hour)
        assertEquals(0, startDate.minute)
        // 23:59 → 距离零点最多 60 秒。
        assertTrue(secondsUntilLocalMidnight(late) in 1..60)
    }

    @Test
    fun `fromLegacy daily budget never exceeds the device cap`() {
        val policy = CarrierBillSettings.fromLegacy(
            List(5) { CarrierAutoLoginConfig(enabled = true, maxAttemptsPerDay = 20) }
        )
        assertEquals(CarrierBillSettings.DAILY_MAX, policy.dailySmsLimit)
    }
}
