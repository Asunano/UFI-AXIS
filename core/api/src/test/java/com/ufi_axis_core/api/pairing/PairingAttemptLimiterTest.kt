package com.ufi_axis_core.api.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双键指数退避的单元测试（2026-10-15）。
 *
 * 纯 JVM（不需要 Android 运行时）：这个类刻意不碰 Context —— 内存态，见类注释
 * "为什么是内存态（进程重启清零）"。
 */
class PairingAttemptLimiterTest {

    private val limiter = PairingAttemptLimiter()

    @Test
    fun `无失败记录时不加延时`() {
        assertEquals("没有任何失败时不该延时（正常用户零感知）", 0L, limiter.delayMsFor("fp-a", "hw-a"))
        assertEquals("两条键都空也是 0", 0L, limiter.delayMsFor(null, null))
    }

    @Test
    fun `退避阶梯按失败次数递增并在最后一档封顶`() {
        val steps = PairingAttemptLimiter.DEFAULT_DELAYS_MS
        // 第 1 次失败 → 下一次尝试等 steps[0]（1s）
        limiter.recordFailure("fp-a", null)
        assertEquals(steps[0], limiter.delayMsFor("fp-a", null))
        // 逐次递增
        for (i in 1 until steps.size) {
            limiter.recordFailure("fp-a", null)
            assertEquals("第 ${i + 1} 次失败后的等待应是 steps[$i]", steps[i], limiter.delayMsFor("fp-a", null))
        }
        // 继续失败：停在最后一档（10 分钟），不再增长
        repeat(10) { limiter.recordFailure("fp-a", null) }
        assertEquals("超出阶梯后应封顶", steps.last(), limiter.delayMsFor("fp-a", null))
        assertEquals("最后一次的等待就是上限 10 分钟", 600_000L, limiter.delayMsFor("fp-a", null))
    }

    @Test
    fun `两条键取最严 —— 指纹干净但 hwId 已被退避时照样延时`() {
        repeat(4) { limiter.recordFailure(null, "hw-x") }
        val hwDelay = limiter.delayMsFor(null, "hw-x")
        assertTrue("hwId 侧应有退避", hwDelay > 0)
        assertEquals(
            "另一条键（指纹）完全干净，但取最严 → 仍按 hwId 的档位延时",
            hwDelay,
            limiter.delayMsFor("fp-brand-new", "hw-x")
        )
    }

    @Test
    fun `清 App 数据换了指纹、hwId 不变时仍被退避`() {
        // 攻击者清数据 = 新密钥 = 新指纹，但 hwId 跟着硬件不变（这正是双键的意义）
        repeat(3) { limiter.recordFailure("fp-old", "hw-same") }
        val delay = limiter.delayMsFor("fp-new", "hw-same")
        assertTrue("换了指纹也必须被退避（否则清数据即绕过）", delay > 0)
    }

    @Test
    fun `成功清零两条键`() {
        repeat(5) { limiter.recordFailure("fp-a", "hw-a") }
        assertTrue("前置：应已退避", limiter.delayMsFor("fp-a", "hw-a") > 0)

        limiter.reset("fp-a", "hw-a")

        assertEquals("成功后应立即恢复零延时", 0L, limiter.delayMsFor("fp-a", "hw-a"))
        assertEquals(0, limiter.failureCount("fp-a", "hw-a"))
    }

    @Test
    fun `空键不计数也不加延时`() {
        limiter.recordFailure("", "   ")
        limiter.recordFailure(null, null)
        assertEquals("空键不该产生任何退避", 0L, limiter.delayMsFor("", ""))
        assertEquals(0L, limiter.delayMsFor(null, null))
    }

    @Test
    fun `不同设备互不影响`() {
        repeat(5) { limiter.recordFailure("fp-a", "hw-a") }
        assertTrue("被爆破的设备应退避", limiter.delayMsFor("fp-a", "hw-a") > 0)
        assertEquals("另一台设备必须零影响", 0L, limiter.delayMsFor("fp-b", "hw-b"))
    }
}
