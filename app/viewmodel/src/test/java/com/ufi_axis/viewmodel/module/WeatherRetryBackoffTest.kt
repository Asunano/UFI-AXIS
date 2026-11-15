package com.ufi_axis.viewmodel.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 天气取数失败的退避表。
 *
 * 之前失败一次就一直是空白，直到下次冷启动 —— 而"冷启动那次恰好失败"正是
 * 顶栏天气整块消失的直接原因（到设备的链路实测 356ms / 50% 丢包）。
 */
class WeatherRetryBackoffTest {

    @Test
    fun `三次退避 30s 2min 5min`() {
        assertEquals(30_000L, weatherRetryDelayMs(0))
        assertEquals(120_000L, weatherRetryDelayMs(1))
        assertEquals(300_000L, weatherRetryDelayMs(2))
    }

    @Test
    fun `用尽之后不再重试`() {
        assertNull(weatherRetryDelayMs(3))
        assertNull(weatherRetryDelayMs(99))
        assertNull(weatherRetryDelayMs(-1))
    }

    @Test
    fun `退避是递增的`() {
        val delays = (0..2).mapNotNull { weatherRetryDelayMs(it) }
        assertEquals(delays.sorted(), delays)
        assertEquals(3, delays.size)
    }
}
