package com.ufi_axis_core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SmartLoop 节拍决策单测（2026-10-07）。
 *
 * 锁的是**行为契约**，不是具体数值巧合：档位单调性、上下限钳制、回退开关、
 * 空数据兜底。具体系数调整时这些断言仍应成立。
 */
class SmartLoopTest {

    private fun loop(
        baseMs: Long = 10_000L,
        rx: Long = 0L,
        tx: Long = 0L,
        load: Double = 0.0,
        front: Boolean = false,
        enabled: Boolean = true,
    ) = SmartLoop(
        name = "test",
        baseMs = baseMs,
        trafficSource = { rx to tx },
        loadSource = { load },
        frontConnected = { front },
        tuningProvider = {
            SmartLoopTuning.defaults().copy(enabled = enabled)
        },
    )

    @Test
    fun `零流量慢档 高于 base`() {
        val d = loop().nextDelayMs()
        // 零流量 1.5 系数 → base*1.5，仍在 [min,max] 内
        assertTrue("idle delay $d 应慢于 base", d > 10_000L)
    }

    @Test
    fun `大流量快档 低于 base`() {
        val d = loop(rx = 50L * 1024 * 1024).nextDelayMs()  // 50 MB/s
        assertTrue("heavy delay $d 应快于 base", d < 10_000L)
    }

    @Test
    fun `吞吐越高节拍越短（单调性）`() {
        val idle = loop(rx = 0).nextDelayMs()
        val mid = loop(rx = 5L * 1024 * 1024).nextDelayMs()
        val heavy = loop(rx = 100L * 1024 * 1024).nextDelayMs()
        assertTrue("应单调递减：idle=$idle mid=$mid heavy=$heavy", idle > mid && mid > heavy)
    }

    @Test
    fun `上传与下载都计入吞吐`() {
        val onlyRx = loop(rx = 30L * 1024 * 1024).nextDelayMs()
        val rxPlusTx = loop(rx = 15L * 1024 * 1024, tx = 15L * 1024 * 1024).nextDelayMs()
        assertEquals("rx+tx 与等量 rx 同档", onlyRx, rxPlusTx)
    }

    @Test
    fun `min max 钳制生效`() {
        // 极大负载 + 零流量 → 想算很大，被 maxMs 钳住（base*6=60000）
        val slow = loop(load = 0.5).nextDelayMs()
        assertTrue("不得超过 maxMs", slow <= 60_000L)
        // 大流量 + 前端在线 + 无负载 → 想算很小，被 minMs 钳住（base/2=5000）
        val fast = loop(rx = 999L * 1024 * 1024, front = true).nextDelayMs()
        assertTrue("不得低于 minMs", fast >= 5_000L)
    }

    @Test
    fun `前端在线加快节拍`() {
        val offline = loop(rx = 5L * 1024 * 1024).nextDelayMs()
        val online = loop(rx = 5L * 1024 * 1024, front = true).nextDelayMs()
        assertTrue("在线 $online 应快于离线 $offline", online < offline)
    }

    @Test
    fun `回退开关 false 时恒为 baseMs`() {
        assertEquals(10_000L, loop(rx = 100L * 1024 * 1024, enabled = false).nextDelayMs())
        assertEquals(10_000L, loop(rx = 0, load = 0.5, enabled = false).nextDelayMs())
    }

    @Test
    fun `trafficSource 为 null 按零流量处理`() {
        val l = SmartLoop(
            name = "null",
            baseMs = 10_000L,
            trafficSource = { null },
            tuningProvider = { SmartLoopTuning.defaults() },
        )
        assertEquals(loop(rx = 0).nextDelayMs(), l.nextDelayMs())
    }

    @Test
    fun `负载异常值不放大节拍`() {
        val negative = loop(rx = 5L * 1024 * 1024, load = -5.0).nextDelayMs()
        val zero = loop(rx = 5L * 1024 * 1024, load = 0.0).nextDelayMs()
        assertEquals("负负载按 0 处理", zero, negative)
    }

    @Test
    fun `Tuning 读写往返`() {
        // write/read 依赖 AppSettings（Android），这里只锁 defaults 与 copy 语义
        val d = SmartLoopTuning.defaults()
        assertEquals(SmartLoop.IDLE_BYTES_PER_SEC, d.idleBytesPerSec)
        assertEquals(SmartLoop.ACTIVE_BYTES_PER_SEC, d.activeBytesPerSec)
        assertEquals(SmartLoop.HEAVY_BYTES_PER_SEC, d.heavyBytesPerSec)
        assertTrue(d.enabled)
        assertTrue("档位阈值应递增", d.idleBytesPerSec < d.activeBytesPerSec && d.activeBytesPerSec < d.heavyBytesPerSec)
    }
}
