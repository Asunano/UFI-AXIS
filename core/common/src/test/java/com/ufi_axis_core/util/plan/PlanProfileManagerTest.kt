package com.ufi_axis_core.util.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PlanProfileManager 到期运算单测（2026-10-07）。
 *
 * 用 fake settings（内存 map）替代 AppSettings（Android 依赖），锁的是
 * 纯日历运算与去重语义——不碰 Room/Context，纯 JVM 可跑。
 */
class PlanProfileManagerTest {

    /** 内存版 settings 假身：PlanProfileManager 只用 getRawString/setRawString */
    private class FakeSettings {
        val map = HashMap<String, String>()
        fun getRawString(key: String): String? = map[key]
        fun setRawString(key: String, value: String) { map[key] = value }
    }

    private fun manager(fixed: Boolean = true, start: String = "2026-10-01", days: Int = 30, notify: Int = 3): PlanProfileManager {
        val s = FakeSettings()
        val m = PlanProfileManager({ s.getRawString(it) }, { k, v -> s.setRawString(k, v) })
        if (fixed) {
            val err = m.write(PlanProfileManager.MODE_FIXED, start, days, notify)
            assertEquals(null, err)
        } else {
            m.write(PlanProfileManager.MODE_MONTHLY, null, null, notify)
        }
        return m
    }

    // ───────── 时间锚点：2026-10-15 12:00 当地时区 ─────────
    /** month 用 1-based（10 = 十月），与时区无关的天锚点 */
    private fun at(day: Int, hour: Int = 12, month: Int = 10): Long =
        java.util.Calendar.getInstance().apply {
            clear(); set(2026, month - 1, day, hour, 0, 0)
        }.timeInMillis

    @Test
    fun `monthly 模式到期状态恒为 null`() {
        val m = manager(fixed = false)
        assertNull(m.expiryState(at(15)))
        assertNull(m.dueNotification(at(15)))
    }

    @Test
    fun `fixed 30天包 第15天 剩15天 不提醒`() {
        val m = manager(start = "2026-10-01", days = 30, notify = 3)
        assertNull(m.dueNotification(at(15)))
    }

    @Test
    fun `fixed 剩3天进入提醒窗口 warning`() {
        // 10-01 + 30天 = 10-31 到期；10-28 12:00 → 剩 3 天（向上取整）
        val m = manager(start = "2026-10-01", days = 30, notify = 3)
        val n = m.dueNotification(at(28))
        assertEquals("warning", n?.first)
        assertEquals(true, n?.second?.contains("剩余 3 天") == true)
    }

    @Test
    fun `同一天不重复提醒（边沿去重）`() {
        val m = manager(start = "2026-10-01", days = 30, notify = 3)
        val first = m.dueNotification(at(28, 9))
        assertEquals("warning", first?.first)
        assertNull("同一天第二次必须 null", m.dueNotification(at(28, 18)))
    }

    @Test
    fun `到期当天 critical`() {
        val m = manager(start = "2026-10-01", days = 30, notify = 3)
        val n = m.dueNotification(at(31))
        assertEquals("critical", n?.first)
        assertEquals(true, n?.second?.contains("今天到期") == true)
    }

    @Test
    fun `过期 info 每天一条`() {
        val m = manager(start = "2026-10-01", days = 30, notify = 3)
        val d1 = m.dueNotification(at(2, 10, month = 11))   // 11-02 → 过期 2 天
        assertEquals("info", d1?.first)
        assertNull("同日第二条 null", m.dueNotification(at(2, 20, month = 11)))
        val d2 = m.dueNotification(at(3, 10, month = 11))   // 次日再发
        assertEquals("info", d2?.first)
        assertEquals(true, d2?.second?.contains("过期 3 天") == true)
    }

    @Test
    fun `切回 monthly 清掉 fixed 键`() {
        val m = manager(start = "2026-10-01", days = 30)
        m.write(PlanProfileManager.MODE_MONTHLY, null, null, null)
        assertNull(m.expiryState(at(15)))
        assertEquals("monthly", m.read().mode)
    }

    @Test
    fun `write 校验拒绝非法值`() {
        val m = PlanProfileManager({ null }, { _, _ -> })
        assertEquals(true, m.write("bogus", null, null, null)?.isNotBlank() == true)
        assertEquals(true, m.write(PlanProfileManager.MODE_FIXED, "2026/10/01", 30, null)?.isNotBlank() == true)
        assertEquals(true, m.write(PlanProfileManager.MODE_FIXED, "2026-10-01", 0, null)?.isNotBlank() == true)
        assertEquals(true, m.write(PlanProfileManager.MODE_FIXED, "2026-10-01", 30, 99)?.isNotBlank() == true)
        assertEquals(true, m.write(PlanProfileManager.MODE_FIXED, "2026-10-01", 30, null) == null)
    }

    @Test
    fun `daysLeft 跨月正确`() {
        // 9-01 + 30天 = 10-01 到期；10-15 → 过期 14 天
        val m = manager(start = "2026-09-01", days = 30)
        m.read()
        val st = m.expiryState(at(15))!!
        assertEquals(-14L, st.daysLeft)
    }
}
