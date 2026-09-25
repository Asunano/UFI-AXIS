package com.ufi_axis_core.controller.goform

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三个写入口的**重试语义**（2026-09-26 短信「第一次必 500」修复）。
 *
 * ## 钉住的是什么
 *
 * [GoformWritePolicyTest] 断言的是**单条判定**（`shouldRetry` / `shouldRetryBusinessFailure`
 * 各自怎么回答），本类断言的是**真正那圈循环**（`GoformClient.postMeasured` 调的
 * [GoformWritePolicy.runAttempts]）在三种旗标组合下**实际发了几次**。
 * 区别很要命：判定全对、循环把它门控错了，缺陷照样在。
 *
 * | 被测组合 | 对应入口 | SessionLost | Accepted + body 说失败 |
 * |---|---|---|---|
 * | `(false, true)` | `write` | 不重试 | 不重试（总闸关着） |
 * | `(true, true)` | `writeIdempotent` | 重试一次 | 重试一次 |
 * | `(true, false)` | `writeSessionSafe` | 重试一次 | **不重试** |
 *
 * 最后一行那个「不重试」是**红线**：`SEND_SMS` 走这条路，而 [GoformWriteResult.Accepted]
 * 意味着命令已经进过固件 —— 重发一次就可能是第二条真短信、第二笔话费。
 *
 * ## 为什么不用真 [GoformClient]
 *
 * 那条路要起 Ktor + 真设备。循环体本身没有 HTTP，所以 2026-09-26 把它搬进
 * [GoformWritePolicy.runAttempts]，一次往返用 lambda 注入（同
 * `GoformSettingWriterDecisionTest` 的 `FakeTransport` 分法）。
 */
class GoformWriteRetrySemanticsTest {

    /**
     * 按预置顺序交还设备表态，并记下发了几次 / 作废了几次会话 / 打了哪些 WARN。
     *
     * 预置结果用完之后**一直返回最后一个** —— 上限由 [GoformWritePolicy.MAX_ATTEMPTS] 兜，
     * 真的越界了应该表现为断言失败，而不是数组下标异常（那种失败看不出是谁越界）。
     */
    private class Device(private vararg val results: GoformWriteResult) {
        var sends = 0
            private set
        var invalidateCalls = 0
            private set
        val warns = mutableListOf<String>()

        fun run(
            retryOnSessionLost: Boolean,
            retryOnBusinessFailure: Boolean,
        ): GoformWritePolicy.WriteAttempts = runBlocking {
            GoformWritePolicy.runAttempts(
                goformId = "SEND_SMS",
                retryOnSessionLost = retryOnSessionLost,
                retryOnBusinessFailure = retryOnBusinessFailure,
                isSuccess = { body -> body == OK_BODY },
                invalidateSession = { invalidateCalls++ },
                warn = { warns += it },
            ) {
                val i = sends.coerceAtMost(results.size - 1)
                sends++
                results[i]
            }
        }
    }

    // ───────────── writeSessionSafe：会话失效重试一次 ─────────────

    @Test
    fun `writeSessionSafe 首次会话失效时重登重试一次 第二次成功即整体成功`() {
        // 这就是真机上「冷启动后第一次发短信必 500、再点一次就好」的场景 ——
        // 修好之后一次点击内部就把两次都跑完了，用户看到的是一次成功。
        val device = Device(GoformWriteResult.SessionLost, GoformWriteResult.Accepted(OK_BODY))
        val attempted = device.run(retryOnSessionLost = true, retryOnBusinessFailure = false)

        assertEquals("必须真的发了两次", 2, device.sends)
        assertEquals(2, attempted.attempts)
        assertEquals(GoformWriteResult.Accepted(OK_BODY), attempted.result)
        assertTrue(
            "重登重试这件事必须留在 WARN 里，否则真机上没法判断是第几次成的：${device.warns}",
            device.warns.any { it.contains("session lost") && it.contains("retry") },
        )
    }

    @Test
    fun `writeSessionSafe 两次都会话失效时如实报 SessionLost 且不超上限`() {
        val device = Device(GoformWriteResult.SessionLost)
        val attempted = device.run(retryOnSessionLost = true, retryOnBusinessFailure = false)

        assertEquals(GoformWritePolicy.MAX_ATTEMPTS, device.sends)
        assertEquals(GoformWriteResult.SessionLost, attempted.result)
    }

    // ───────────── writeSessionSafe：业务失败绝不重发（红线） ─────────────

    @Test
    fun `writeSessionSafe 在设备回业务失败体时绝不重发 —— 不重复计费的红线`() {
        // Accepted 的语义是「命令已经进过固件」。body 说失败也排除不了「其实已进发送队列」，
        // 重发一次就可能是第二条真短信。这条断言塌了就意味着用户可能被扣两笔话费。
        val device = Device(GoformWriteResult.Accepted(FAIL_BODY), GoformWriteResult.Accepted(OK_BODY))
        val attempted = device.run(retryOnSessionLost = true, retryOnBusinessFailure = false)

        assertEquals("只许发一次", 1, device.sends)
        assertEquals(1, attempted.attempts)
        assertEquals(GoformWriteResult.Accepted(FAIL_BODY), attempted.result)
        assertEquals("不重发就不该作废会话", 0, device.invalidateCalls)
        assertFalse(
            "不该出现业务失败重试的日志：${device.warns}",
            device.warns.any { it.contains("business failure") },
        )
    }

    @Test
    fun `writeSessionSafe 连不上设备时不重试`() {
        // Unreachable 同样排除不了「其实已经发出去了」，所以也不许重发。
        val device = Device(GoformWriteResult.Unreachable("Connect timed out"), GoformWriteResult.Accepted(OK_BODY))
        val attempted = device.run(retryOnSessionLost = true, retryOnBusinessFailure = false)

        assertEquals(1, device.sends)
        assertTrue(attempted.result is GoformWriteResult.Unreachable)
    }

    // ───────────── 现有两条入口的行为逐位不变 ─────────────

    @Test
    fun `writeIdempotent 的旗标组合仍然为业务失败重登重试一次`() {
        // 2026-09-15 修「冷启动后第一次切制式必失败」时定下的行为，本次改造不许动它。
        val device = Device(GoformWriteResult.Accepted(FAIL_BODY), GoformWriteResult.Accepted(OK_BODY))
        val attempted = device.run(retryOnSessionLost = true, retryOnBusinessFailure = true)

        assertEquals(2, device.sends)
        assertEquals(GoformWriteResult.Accepted(OK_BODY), attempted.result)
        assertEquals("业务失败重发前必须先作废会话（陈旧会话是这一路最常见的成因）", 1, device.invalidateCalls)
        assertTrue(device.warns.any { it.contains("business failure") && it.contains("SEND_SMS") })
    }

    @Test
    fun `write 的旗标组合一次都不重试`() {
        // retryOnSessionLost=false 是整圈重试的总闸。REBOOT / SHUTDOWN 这类命令靠它，
        // 重发一次就是再重启一次。
        val device = Device(GoformWriteResult.SessionLost, GoformWriteResult.Accepted(OK_BODY))
        val attempted = device.run(retryOnSessionLost = false, retryOnBusinessFailure = true)

        assertEquals(1, device.sends)
        assertEquals(GoformWriteResult.SessionLost, attempted.result)
        assertTrue("一次都不重试就不该有任何重试日志：${device.warns}", device.warns.isEmpty())
    }

    private companion object {
        /** `GoformClient.isSuccess` 认的成功体之一（口径同 `GoformSettingWriterDecisionTest`）。 */
        const val OK_BODY = """{"result":"success"}"""
        const val FAIL_BODY = """{"result":"failure"}"""
    }
}
