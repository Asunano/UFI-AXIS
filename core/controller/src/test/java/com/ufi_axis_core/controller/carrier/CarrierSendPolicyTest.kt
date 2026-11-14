package com.ufi_axis_core.controller.carrier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发码限流策略（2026-11-05，P1）。
 *
 * ## 为什么值得单独测
 * 这是**唯一一道花钱的闸**：每次放行 = 一条真实短信。而它的规则是几条窗口叠在一起，
 * 手写时最容易错的三处：
 * 1. 全局限速（跨账号 1 条/分钟）必须**先于**单账号判断 —— 否则 A 号刚发完，
 *    B 号在同一分钟里还能发第 2 条，运营商风控照样触发；
 * 2. 小时/天窗要用**最早那条**过期时间，不是最晚 —— 用最晚会多等（用户以为坏了）；
 * 3. 未来时间戳（用户改过系统时间）要保守当成"刚刚发过"，不能放行。
 */
class CarrierSendPolicyTest {

    private val t0 = 1_000_000_000L

    @Test
    fun `空历史直接放行`() {
        assertEquals(0L, CarrierSendPolicy.waitSeconds(t0, emptyList(), emptyList()))
    }

    @Test
    fun `全局一分钟内已发过则跨账号也要等`() {
        // A 号刚发过（全局时间戳），B 号现在点发送 → 必须等。
        val wait = CarrierSendPolicy.waitSeconds(
            now = t0,
            globalSends = listOf(t0 - 20_000L),
            accountSends = emptyList(),
        )
        assertEquals("应等到 60 秒窗口结束（还差 40 秒）", 40L, wait)
    }

    @Test
    fun `单账号一分钟内已发过则等待`() {
        val wait = CarrierSendPolicy.waitSeconds(
            now = t0,
            globalSends = emptyList(),
            accountSends = listOf(t0 - 10_000L),
        )
        assertEquals(50L, wait)
    }

    @Test
    fun `单账号小时窗满 5 条后等最早那条过期`() {
        // 5 条都在一小时内，最早一条是 40 分钟前 → 还差 20 分钟。
        val sends = listOf(t0 - 40 * 60_000L, t0 - 30 * 60_000L, t0 - 20 * 60_000L, t0 - 10 * 60_000L, t0 - 5 * 60_000L)
        val wait = CarrierSendPolicy.waitSeconds(t0, emptyList(), sends)
        assertEquals(20 * 60L, wait)
    }

    @Test
    fun `单账号天窗满 10 条后等最早那条过期`() {
        // 10 条分布在天内，最早在 23 小时前 → 还差 1 小时。
        // 刻意让**小时窗只有 1 条**（50 分钟前那条）：超过 5 条会先命中小时规则。
        val sends = buildList {
            add(t0 - 23 * 3_600_000L)              // 天窗里最早
            add(t0 - 2 * 3_600_000L)               // 4 条都在 2 小时前
            add(t0 - 2 * 3_600_000L - 5 * 60_000L)
            add(t0 - 2 * 3_600_000L - 10 * 60_000L)
            add(t0 - 2 * 3_600_000L - 15 * 60_000L)
            add(t0 - 90 * 60_000L)                 // 4 条在 90 分钟前
            add(t0 - 95 * 60_000L)
            add(t0 - 100 * 60_000L)
            add(t0 - 105 * 60_000L)
            add(t0 - 50 * 60_000L)                 // 小时窗仅此 1 条
        }
        val wait = CarrierSendPolicy.waitSeconds(t0, emptyList(), sends)
        assertEquals(3_600L, wait)
    }

    @Test
    fun `未来时间戳按刚刚发过处理，不放行`() {
        // 用户把系统时间往前拨，落盘里留下"未来"的时间戳。
        // 保守处理：等满一个分钟窗，宁可多等也不要连发短信。
        val wait = CarrierSendPolicy.waitSeconds(
            now = t0,
            globalSends = listOf(t0 + 10_000L),
            accountSends = emptyList(),
        )
        assertTrue("未来时间戳必须保守拦截", wait > 0)
    }

    @Test
    fun `全局优先级高于单账号`() {
        // 两个条件同时满足时，返回的是全局的等待（更长/更早的那条规则先命中）。
        val wait = CarrierSendPolicy.waitSeconds(
            now = t0,
            globalSends = listOf(t0 - 50_000L),
            accountSends = listOf(t0 - 55_000L),
        )
        assertEquals(10L, wait)
    }
}
