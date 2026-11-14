package com.ufi_axis.data.api

import com.ufi_axis.data.api.AuthRejectionPolicy.Verdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 「什么情况下才清凭据」的阈值表。
 *
 * 这是"删配对后被踢出登录"的唯一判据，此前**零测试**：把 `span` 判断写反、
 * 把次数从 3 改成 1、或者让 `notified` 粘滞，都不会有任何测试变红。
 * 这里把契约钉死（含 2026-09-08 / 2026-10-22 两次事故的教训）。
 */
class AuthRejectionPolicyTest {

    @Before
    @After
    fun resetPolicy() = AuthRejectionPolicy.reset()

    private fun hit444(times: Int, gapMs: Long = 0) = repeat(times) {
        if (gapMs > 0) Thread.sleep(gapMs)
        AuthRejectionPolicy.judge(444) { null }
    }

    @Test
    fun `两次 444 不清凭据`() {
        // 两次 444：次数就不够，无论跨度
        assertEquals(Verdict.NONE, AuthRejectionPolicy.judge(444) { null })
        assertEquals(Verdict.NONE, AuthRejectionPolicy.judge(444) { null })
    }

    @Test
    fun `三次 444 但跨度不足 10 秒不清凭据`() {
        // 连续三次（毫秒级）→ 冷启动时 core 还没装载完配对库也是这个形态
        assertEquals(Verdict.NONE, AuthRejectionPolicy.judge(444) { null })
        assertEquals(Verdict.NONE, AuthRejectionPolicy.judge(444) { null })
        assertEquals(Verdict.NONE, AuthRejectionPolicy.judge(444) { null })
    }

    @Test
    fun `三次 444 且跨度超过 10 秒才判定吊销`() {
        assertEquals(Verdict.NONE, AuthRejectionPolicy.judge(444) { null })
        Thread.sleep(5_000)
        assertEquals(Verdict.NONE, AuthRejectionPolicy.judge(444) { null })
        Thread.sleep(5_500)
        assertEquals(Verdict.WIPE, AuthRejectionPolicy.judge(444) { null })
    }

    @Test
    fun `紧跟一次连接失败之后的 444 不算数`() {
        // core 刚重启：紧跟连接失败的那一句"不认识你"不算数，计数从它重新开始
        AuthRejectionPolicy.onTransportFailure()
        assertEquals(Verdict.NONE, AuthRejectionPolicy.judge(444) { null })
        // 之后恢复常规计数：再攒两次、跨度够 10s 才判吊销
        Thread.sleep(5_000)
        assertEquals(Verdict.NONE, AuthRejectionPolicy.judge(444) { null })
        Thread.sleep(5_500)
        assertEquals(Verdict.WIPE, AuthRejectionPolicy.judge(444) { null })
    }

    @Test
    fun `一次成功响应把连击清零`() {
        repeat(2) { AuthRejectionPolicy.judge(444) { null } }
        assertEquals(Verdict.NONE, AuthRejectionPolicy.judge(200) { null })
        // 清零后再来两次仍不该踢
        assertEquals(Verdict.NONE, AuthRejectionPolicy.judge(444) { null })
        assertEquals(Verdict.NONE, AuthRejectionPolicy.judge(444) { null })
    }

    @Test
    fun `401 的瞬时签名问题只重试不清凭据`() {
        assertEquals(Verdict.RETRY, AuthRejectionPolicy.judge(401) { "STALE_TIMESTAMP" })
        assertEquals(Verdict.RETRY, AuthRejectionPolicy.judge(401) { "INVALID_SIGNATURE" })
        // 其他 401（例如密码错）既不清凭据也不重试
        assertEquals(Verdict.NONE, AuthRejectionPolicy.judge(401) { "INVALID_PASSWORD" })
        assertEquals(Verdict.NONE, AuthRejectionPolicy.judge(401) { null })
    }

    @Test
    fun `配对库降级回 503 时重试而不清凭据`() {
        assertEquals(Verdict.RETRY, AuthRejectionPolicy.judge(503) { "AUTH_STORE_UNAVAILABLE" })
        assertEquals(Verdict.NONE, AuthRejectionPolicy.judge(503) { "OTHER" })
    }

    @Test
    fun `重试退避只有两档`() {
        assertEquals(300L, AuthRejectionPolicy.retryDelayMs(0))
        assertEquals(600L, AuthRejectionPolicy.retryDelayMs(1))
        assertEquals(null, AuthRejectionPolicy.retryDelayMs(2))
    }
}
