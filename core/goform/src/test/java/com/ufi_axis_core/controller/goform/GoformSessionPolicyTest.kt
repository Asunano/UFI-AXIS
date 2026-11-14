package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.controller.goform.GoformSessionPolicy.SessionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GoformSessionPolicy] 的会话判定（2026-09-26 随「发短信前强制刷会话」一起加）。
 *
 * ## 为什么测的是 policy 而不是 `GoformClient`
 *
 * `GoformClient` 一构造就起 Ktor client，`AppLogger` 又依赖 `android.util.Log`，
 * 裸 JVM 单测里构造不出来（同 `GoformClient` companion 那两个纯函数的注释）。
 * 所以判定被抽进本 policy，测的就是生产路径本身，不是复制品。
 *
 * ## 这四件事必须被钉住
 *
 * 1. **强制校验真的绕过了 60s 校验间隔** —— 否则「陈旧会话下第一次发短信必失败」的修法
 *    完全不起作用（刚校验过的会话会走缓存路径，一个字节都不发）；
 * 2. **默认路径仍然遵守间隔** —— 这是在钉住「其它调用点行为逐位不变」，
 *    否则每一次 goform 读写都会平白多一次 `cmd=RD`，把设备打满；
 * 3. **让位窗口**（官方后台在线时主动避让 30s）不因为"强制"被绕过；
 * 4. **登录退避**（1.5s 起、封顶 10s）同样不被绕过。
 *    3 与 4 都是踩过坑的保护：绕过去只会制造新的首次失败。
 */
class GoformSessionPolicyTest {

    // ── 1 / 2：校验间隔 ──

    @Test
    fun `默认路径遵守 60s 校验间隔 —— 刚校验过就直接用缓存`() {
        assertEquals(
            "默认路径必须走缓存：这是其它所有调用点的既有行为，多发一次 RD 就是把设备打满",
            SessionGate.USE_CACHED,
            GoformSessionPolicy.gate(loggedIn = true, sinceValidatedMs = 1_000L),
        )
    }

    @Test
    fun `强制校验绕过 60s 间隔 —— lastValidatedAt 刚更新过也会再校验一次`() {
        assertEquals(
            "强制校验必须无视间隔，否则陈旧会话下第一次发短信仍会失败",
            SessionGate.VALIDATE,
            GoformSessionPolicy.gate(loggedIn = true, sinceValidatedMs = 1_000L, forceValidate = true),
        )
    }

    @Test
    fun `刚过校验间隔时两种模式都只是校验，不是重登`() {
        val since = GoformSessionPolicy.SESSION_VALIDATION_INTERVAL_MS + 1
        assertEquals(SessionGate.VALIDATE, GoformSessionPolicy.gate(true, since))
        assertEquals(SessionGate.VALIDATE, GoformSessionPolicy.gate(true, since, forceValidate = true))
    }

    @Test
    fun `TTL 已过时强制校验也不白花一次 GET，直接重登`() {
        val since = GoformSessionPolicy.SESSION_CACHE_TTL_MS + 1
        assertEquals(
            "TTL 都过了，这份会话按陈旧处理；重登同样能保证「下发前会话是新鲜的」",
            SessionGate.LOGIN,
            GoformSessionPolicy.gate(true, since, forceValidate = true),
        )
    }

    @Test
    fun `没有登录态时两种模式都走完整登录`() {
        assertEquals(SessionGate.LOGIN, GoformSessionPolicy.gate(loggedIn = false, sinceValidatedMs = 0L))
        assertEquals(
            SessionGate.LOGIN,
            GoformSessionPolicy.gate(loggedIn = false, sinceValidatedMs = 0L, forceValidate = true),
        )
    }

    // ── 3：官方后台让位窗口 ──

    @Test
    fun `让位窗口内一律短路 —— 强制校验不许硬抢`() {
        val now = 1_000_000L
        val justGaveWay = now - 5_000L
        assertTrue(
            "撞见官方后台在线之后的 30s 内不许尝试登录（强制校验也不例外）",
            GoformSessionPolicy.shouldGiveWay(lastGiveWayAt = justGaveWay, now = now),
        )
    }

    @Test
    fun `让位窗口过期后放行`() {
        val now = 1_000_000L
        assertFalse(
            GoformSessionPolicy.shouldGiveWay(
                lastGiveWayAt = now - GoformSessionPolicy.GIVE_WAY_DURATION_MS - 1,
                now = now,
            )
        )
    }

    @Test
    fun `从没让过位时不算在让位窗口里（0 是哨兵值，不是时间戳）`() {
        // 省掉 lastGiveWayAt > 0 这个判断的话，冷启动后的前 30s 会全部被判成在让位窗口内。
        assertFalse(GoformSessionPolicy.shouldGiveWay(lastGiveWayAt = 0L, now = 10_000L))
    }

    // ── 4：登录失败指数退避 ──

    @Test
    fun `退避时长按 2 的幂增长并封顶在 10s`() {
        assertEquals(0L, GoformSessionPolicy.loginBackoffMs(0))
        assertEquals(3_000L, GoformSessionPolicy.loginBackoffMs(1))
        assertEquals(6_000L, GoformSessionPolicy.loginBackoffMs(2))
        assertEquals(
            "封顶必须是 10s：历史上叠到 60s 时前端轮询全被拦截，表现为「长时间断连」",
            GoformSessionPolicy.MAX_LOGIN_BACKOFF_MS,
            GoformSessionPolicy.loginBackoffMs(99),
        )
    }

    @Test
    fun `退避期内一律短路 —— 强制校验不许绕过退避`() {
        val now = 1_000_000L
        assertTrue(
            GoformSessionPolicy.inLoginBackoff(failCount = 3, lastLoginAttempt = now - 100L, now = now)
        )
    }

    @Test
    fun `退避期满后放行`() {
        val now = 1_000_000L
        assertFalse(
            GoformSessionPolicy.inLoginBackoff(
                failCount = 3,
                lastLoginAttempt = now - GoformSessionPolicy.loginBackoffMs(3) - 1,
                now = now,
            )
        )
    }

    @Test
    fun `一次都没尝试过时不被退避拦住（lastLoginAttempt 的 0 也是哨兵值）`() {
        assertFalse(GoformSessionPolicy.inLoginBackoff(failCount = 3, lastLoginAttempt = 0L, now = 10L))
    }

    @Test
    fun `没有失败记录就没有退避`() {
        val now = 1_000_000L
        assertFalse(GoformSessionPolicy.inLoginBackoff(failCount = 0, lastLoginAttempt = now - 1, now = now))
    }
}
