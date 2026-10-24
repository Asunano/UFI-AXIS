package com.ufi_axis_core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * goform 熔断/隔离的单元测试（2026-10-17）。
 *
 * 纯 JVM：这个类只持有几个原子量与时间戳，不碰 Android。
 */
class GoformGuardTest {

    @Before
    fun setUp() {
        GoformGuard.resetForTest()
    }

    @Test
    fun `连续失败达到阈值后熔断`() {
        assertFalse("初始不熔断", GoformGuard.isOpen())
        repeat(GoformGuard.FAILURE_THRESHOLD - 1) { GoformGuard.recordFailure() }
        assertFalse("未达阈值不该熔断", GoformGuard.isOpen())

        GoformGuard.recordFailure()
        assertTrue("达到阈值必须熔断", GoformGuard.isOpen())
        assertEquals(GoformGuard.FAILURE_THRESHOLD, GoformGuard.consecutiveFailureCount())
    }

    @Test
    fun `熔断冷却结束后自动恢复`() {
        repeat(GoformGuard.FAILURE_THRESHOLD) { GoformGuard.recordFailure() }
        val now = System.currentTimeMillis()
        assertTrue(GoformGuard.isOpen(now))
        assertFalse(
            "冷却结束应放行（恢复后的第一波请求本身就是探测，再失败会立刻重新熔断）",
            GoformGuard.isOpen(now + GoformGuard.OPEN_DURATION_MS + 1)
        )
    }

    @Test
    fun `成功清零并解除熔断`() {
        repeat(GoformGuard.FAILURE_THRESHOLD) { GoformGuard.recordFailure() }
        assertTrue(GoformGuard.isOpen())

        GoformGuard.recordSuccess()
        assertFalse("成功后必须解除熔断", GoformGuard.isOpen())
        assertEquals("失败计数应清零", 0, GoformGuard.consecutiveFailureCount())
    }

    @Test
    fun `快速失败与超时分别计数`() {
        GoformGuard.recordFastFail()
        GoformGuard.recordFastFail()
        GoformGuard.recordTimeout()

        val snap = GoformGuard.snapshot()
        assertEquals(2, snap["total_fast_fails"])
        assertEquals(1, snap["total_timeouts"])
    }

    @Test
    fun `快照包含诊断所需字段`() {
        val snap = GoformGuard.snapshot()
        listOf(
            "open", "consecutive_failures", "open_until", "last_failure_at", "last_success_at",
            "total_fast_fails", "total_timeouts", "read_budget_ms", "parallelism"
        ).forEach { key ->
            assertTrue("快照缺少 $key", snap.containsKey(key))
        }
        assertEquals(GoformGuard.READ_BUDGET_MS, snap["read_budget_ms"])
        assertEquals(GoformGuard.PARALLELISM, snap["parallelism"])
    }

    @Test
    fun `读预算必须明显小于原来的最坏读耗时`() {
        // 原来的最坏一次读 ≈ 登录链 40s + 查询 10s。预算必须把它压到秒级，
        // 否则慢请求仍会长期占住客户端连接（无卡启动时"文件管理也打不开"的根因）。
        assertTrue(
            "读预算 ${GoformGuard.READ_BUDGET_MS}ms 应 ≤ 5s",
            GoformGuard.READ_BUDGET_MS <= 5_000L
        )
        assertTrue(
            "熔断阈值不应为 1（一次偶发失败就熔断会让正常抖动也进熔断）",
            GoformGuard.FAILURE_THRESHOLD >= 2
        )
    }
}
