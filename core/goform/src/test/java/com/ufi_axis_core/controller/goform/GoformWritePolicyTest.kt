package com.ufi_axis_core.controller.goform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 幂等写的重试与分类策略（2026-09-11 网络制式缺陷修复）。
 *
 * 钉住的是缺陷本身的三条判据，而不是实现细节：
 *  1. 会话失效 → **重试一次**（原来一次都不重试，于是"第一次点必失败"）；
 *  2. 设备明确拒绝 → **不重试**（重发只是白等一个往返）；
 *  3. 重试**有上限**（无界重试会把设备登录退避一路叠满，历史上表现为长时间断连）。
 */
class GoformWritePolicyTest {

    private val deviceRejected = GoformWriteResult.Accepted("""{"result":"failure"}""")
    private val deviceOk = GoformWriteResult.Accepted("""{"result":"success"}""")

    // ── 1. 会话失效重试一次 ──

    @Test
    fun `会话失效在第一次尝试后要重试`() {
        assertTrue(GoformWritePolicy.shouldRetry(1, GoformWriteResult.SessionLost))
    }

    @Test
    fun `会话失效只重试一次`() {
        // 第 2 次尝试仍然会话失效时**不再**继续 —— 这就是"只重试一次"的硬边界
        assertFalse(GoformWritePolicy.shouldRetry(2, GoformWriteResult.SessionLost))
    }

    // ── 2. 明确拒绝不重试 ──

    @Test
    fun `设备明确回失败不重试`() {
        assertFalse(GoformWritePolicy.shouldRetry(1, deviceRejected))
    }

    @Test
    fun `设备回成功不重试`() {
        assertFalse(GoformWritePolicy.shouldRetry(1, deviceOk))
    }

    @Test
    fun `连不上设备不重试`() {
        assertFalse(GoformWritePolicy.shouldRetry(1, GoformWriteResult.Unreachable("connect timed out")))
    }

    // ── 3. 重试有上限 ──

    @Test
    fun `重试上限是两次尝试`() {
        assertEquals(2, GoformWritePolicy.MAX_ATTEMPTS)
    }

    @Test
    fun `一路会话失效时总尝试次数不超过上限`() {
        // 模拟真实调用循环：一直会话失效，看它会发几次请求
        var attempts = 0
        var result: GoformWriteResult = GoformWriteResult.SessionLost
        attempts++
        while (GoformWritePolicy.shouldRetry(attempts, result)) {
            attempts++
            result = GoformWriteResult.SessionLost
        }
        assertEquals(GoformWritePolicy.MAX_ATTEMPTS, attempts)
        assertTrue(result is GoformWriteResult.SessionLost)
    }

    @Test
    fun `重试后成功时不再继续尝试`() {
        var attempts = 0
        // 第一次会话失效、第二次被受理：这正是真机上"第一次 500、再点就好"的场景，
        // 修好之后一次点击内部就完成了
        val results = listOf(GoformWriteResult.SessionLost, deviceOk)
        var result: GoformWriteResult = results[0]
        attempts++
        while (GoformWritePolicy.shouldRetry(attempts, result)) {
            result = results[attempts]
            attempts++
        }
        assertEquals(2, attempts)
        assertEquals(deviceOk, result)
    }

    // ── 分类判据 ──

    @Test
    fun `HTTP 200 且非鉴权失败体算设备表过态`() {
        val body = """{"result":"success"}"""
        assertEquals(GoformWriteResult.Accepted(body), GoformWritePolicy.classify(200, false, body))
    }

    @Test
    fun `HTTP 200 但是登录页算会话失效`() {
        // isAuthFailure 已在 GoformClient 判过，这里只验策略把它归到可重试一侧
        assertEquals(
            GoformWriteResult.SessionLost,
            GoformWritePolicy.classify(200, true, "<html>login.html</html>"),
        )
    }

    @Test
    fun `非 200 算会话失效`() {
        // 固件会话失效时既可能回 200 + 登录页，也可能直接回非 200；
        // 而业务拒绝一定是 200 + 业务体，所以非 200 归到可重试一侧不会误判拒绝
        assertEquals(GoformWriteResult.SessionLost, GoformWritePolicy.classify(302, false, ""))
        assertEquals(GoformWriteResult.SessionLost, GoformWritePolicy.classify(500, false, "oops"))
    }
}
