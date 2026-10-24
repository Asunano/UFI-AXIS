package com.ufi_axis_core.api.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /api/service/crash` 的新鲜度判据（2026-10-23）。
 *
 * 锁死两件事：
 * 1. **三个月前的崩溃不算「可提示」** —— 此前 `crashed` 只看 `last_crash_at > 0`，
 *    旧记录会让 App/Web 在每次启动时弹一次窗（本地标记一丢就重弹）；
 * 2. 年龄用 core 自己的时钟算，客户端不做 `now - timestamp` —— 手机与设备时钟漂移
 *    会把「刚崩」误判成「很久以前」。
 */
class ServiceCrashFreshnessTest {

    private val now = 1_760_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `从未崩溃时年龄为 -1 且不提示`() {
        val age = ServiceRoutes.crashAgeMs(lastCrashAt = 0L, nowMs = now)
        assertEquals(-1L, age)
        assertFalse(ServiceRoutes.isRecentCrash(age))
    }

    @Test
    fun `刚崩的崩溃在窗口内`() {
        val age = ServiceRoutes.crashAgeMs(lastCrashAt = now, nowMs = now)
        assertEquals(0L, age)
        assertTrue(ServiceRoutes.isRecentCrash(age))
    }

    @Test
    fun `窗口边界仍算新鲜`() {
        assertTrue(ServiceRoutes.isRecentCrash(ServiceRoutes.CRASH_NOTICE_WINDOW_MS))
    }

    @Test
    fun `超出窗口一毫秒就不再提示`() {
        assertFalse(ServiceRoutes.isRecentCrash(ServiceRoutes.CRASH_NOTICE_WINDOW_MS + 1))
    }

    @Test
    fun `三个月前的崩溃不提示`() {
        val age = ServiceRoutes.crashAgeMs(lastCrashAt = now - 90 * day, nowMs = now)
        assertEquals(90 * day, age)
        assertFalse(ServiceRoutes.isRecentCrash(age))
    }

    @Test
    fun `时钟回拨时年龄夹到 0 而不是把崩溃吞掉`() {
        // NTP 校时 / 用户改时间可能让 now 落在 lastCrashAt 之前；保守按「刚崩」处理。
        val age = ServiceRoutes.crashAgeMs(lastCrashAt = now + 5 * day, nowMs = now)
        assertEquals(0L, age)
        assertTrue(ServiceRoutes.isRecentCrash(age))
    }
}
