package com.ufi_axis_core.api.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 黑名单命中冷却的单元测试（2026-10-15）。
 *
 * 纯 JVM：这个类刻意不碰 Context（内存态，理由见类注释）。
 * 时间参数全部可注入，所以用例不 sleep 十分钟 —— 直接构造一个"毫秒级冷却"的实例。
 */
class PairingBlockCooldownTest {

    /** 毫秒级冷却，便于测阶梯与过期；衰减窗口给大，避免用例被 24h 规则干扰。 */
    private fun fast() = PairingBlockCooldown(
        firstCooldownMs = 60L,
        escalateAfter = 3,
        escalatedCooldownMs = 300L,
        countResetMs = 60_000L
    )

    @Test
    fun `没有命中记录时不冷却`() {
        val c = fast()
        assertEquals(0L, c.remainingMs("fp-a", "hw-a"))
        assertEquals(0, c.hitCount("fp-a", "hw-a"))
    }

    @Test
    fun `首次命中进入短冷却`() {
        val c = fast()
        assertFalse("第 1 次命中不该算升级", c.recordHit("fp-a", null))
        assertTrue("应进入冷却", c.remainingMs("fp-a", null) > 0)
        assertEquals(1, c.hitCount("fp-a", null))
    }

    @Test
    fun `连击达到阈值后升到长冷却档`() {
        val c = fast()
        assertFalse(c.recordHit("fp-a", null))
        assertFalse(c.recordHit("fp-a", null))
        assertTrue("第 3 次命中（阈值）应返回升级标志", c.recordHit("fp-a", null))
        assertEquals(3, c.hitCount("fp-a", null))
        // 升级档是 300ms，明显长于首档 60ms
        assertTrue("升级后冷却应明显更长", c.remainingMs("fp-a", null) > 100L)
    }

    @Test
    fun `冷却会随时间过期`() {
        val c = fast()
        c.recordHit("fp-a", null)
        assertTrue("刚命中应在冷却中", c.remainingMs("fp-a", null) > 0)
        Thread.sleep(120L)
        assertEquals("超过冷却时长后应归零", 0L, c.remainingMs("fp-a", null))
    }

    @Test
    fun `两条键取最严 —— 换了指纹但 hwId 还在冷却时照样拒`() {
        val c = fast()
        c.recordHit("fp-old", "hw-same")
        assertTrue(
            "清 App 数据换了指纹，hwId 仍带着冷却 → 仍应被拒",
            c.remainingMs("fp-new", "hw-same") > 0
        )
    }

    @Test
    fun `空键不参与冷却`() {
        val c = fast()
        c.recordHit("", null)
        assertEquals(0L, c.remainingMs("", null))
        assertEquals(0, c.hitCount("", null))
    }

    /**
     * 节流：L2 是**每次请求**都命中，重复命中只续冷却、不重复上报（否则事件中心被刷爆）。
     */
    @Test
    fun `带节流的命中记录只在上报窗口外返回 true`() {
        val c = fast()
        assertTrue("首次命中应上报", c.recordHitThrottled("fp-a", null, minIntervalMs = 80L))
        assertFalse(
            "窗口内的重复命中不该上报（但冷却仍要续期）",
            c.recordHitThrottled("fp-a", null, minIntervalMs = 80L)
        )
        assertTrue("冷却仍被续期", c.remainingMs("fp-a", null) > 0)
        Thread.sleep(100L)
        assertTrue(
            "过了上报窗口后应再次上报",
            c.recordHitThrottled("fp-a", null, minIntervalMs = 80L)
        )
    }

    @Test
    fun `超过衰减窗口后连击计数重新开始`() {
        // countResetMs 极小：模拟"隔了一天再来一次"
        val c = PairingBlockCooldown(
            firstCooldownMs = 10L,
            escalateAfter = 3,
            escalatedCooldownMs = 20L,
            countResetMs = 30L
        )
        c.recordHit("fp-a", null)
        c.recordHit("fp-a", null)
        Thread.sleep(60L) // 超过衰减窗口
        assertEquals("衰减窗口之外历史计数不算数", 0, c.hitCount("fp-a", null))
        assertFalse("衰减后重新从第 1 次开始，不该直接升级", c.recordHit("fp-a", null))
        assertEquals(1, c.hitCount("fp-a", null))
    }
}
