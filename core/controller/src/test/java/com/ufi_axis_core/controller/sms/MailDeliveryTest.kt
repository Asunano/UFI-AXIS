package com.ufi_axis_core.controller.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.mail.Address
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import javax.mail.SendFailedException
import javax.mail.internet.AddressException
import javax.mail.internet.InternetAddress
import javax.net.ssl.SSLHandshakeException

/**
 * [MailDelivery] 里**只有邮件懂**的那几条判定：闸门取值、SMTP 异常分类、失败文案。
 *
 * 为什么测这个对象而不测 [SmsForwardController]：控制器要 Android `Context`
 * （SharedPreferences / PowerManager），`:core:controller` 的单测源集只有 junit，
 * 构造不出来。所以「闸门放不放行」「哪些异常值得重试」这两件真正需要钉住的判定都抽在
 * [MailDelivery] 里。
 *
 * **重试循环的用例不在这里** —— 2026-09-08 阶段 1 把循环上移到了
 * `NotificationDispatcher`（全仓唯一一个），对应用例在
 * `:core:common` 的 `NotificationDispatcherTest`。这里只剩"值不值得重试"的判据，
 * 因为那是分发器唯一读的输入。
 */
class MailDeliveryTest {

    private companion object {
        /** `mail_send_records.error` 的列长上限（`Entities.kt`），截断行为按它验。 */
        const val ERROR_COLUMN_CHARS = 200
    }



    private fun addr(a: String): Array<Address> = arrayOf(InternetAddress(a))

    // ══════════ 闸门 ══════════

    /** 总开关关闭 / 免打扰时段内 → 整条通知丢弃（控制器在这一步之前就 return，不写记录、不计失败）。 */
    @Test
    fun `closed gate blocks automatic mail`() {
        assertFalse(MailDelivery.allowed(gate = { false }, manual = false))
    }

    @Test
    fun `open gate lets automatic mail through`() {
        assertTrue(MailDelivery.allowed(gate = { true }, manual = false))
    }

    /** 未 attach（降级装配）视为放行：不能因为装配层漏接就静默丢邮件。 */
    @Test
    fun `missing gate means pass`() {
        assertTrue(MailDelivery.allowed(gate = null, manual = false))
    }

    /**
     * 手动 `POST /api/sms-forward/test` 不受闸门约束。
     *
     * 用户刚按下「发送测试」，静默什么都不发比发出去更难排查；端点响应另外回一个
     * `auto_notify_enabled` 说明闸门状态，所以不是无声跳过。
     */
    @Test
    fun `manual test send ignores a closed gate`() {
        assertTrue(MailDelivery.allowed(gate = { false }, manual = true))
    }

    /** manual 放行时连闸门都不该被调用（省一次 prefs 读取，也说明"手动"是最高优先级）。 */
    @Test
    fun `manual send does not even evaluate the gate`() {
        var evaluated = false
        MailDelivery.allowed(gate = { evaluated = true; false }, manual = true)
        assertFalse("manual 分支不应触碰闸门", evaluated)
    }

    // ══════════ 可重试判定 ══════════

    /** 账号密码/授权码错：重试三次结果一样，只会多写两条一模一样的失败记录。 */
    @Test
    fun `authentication failure is not retryable`() {
        assertFalse(MailDelivery.isRetryable(AuthenticationFailedException("535 Login Fail")))
    }

    /** 外层是无信息量的 MessagingException 时也要认出内层的永久错误。 */
    @Test
    fun `authentication failure nested in the chain is still not retryable`() {
        val e = MessagingException("failed to send", AuthenticationFailedException("535 Login Fail"))
        assertFalse(MailDelivery.isRetryable(e))
    }

    @Test
    fun `address syntax error is not retryable`() {
        assertFalse(MailDelivery.isRetryable(AddressException("missing @", "not-an-address")))
    }

    /** 收件人全部无效：服务器已经明确拒收，重试改不了结果。 */
    @Test
    fun `send failure with no valid recipient left is not retryable`() {
        val e = SendFailedException(
            "550 invalid recipient", null,
            /* validSent = */ null, /* validUnsent = */ null, /* invalid = */ addr("nobody@example.invalid")
        )
        assertFalse(MailDelivery.isRetryable(e))
    }

    /** 还有"有效但没投出去"的地址 + 内层是超时 → 属于链路问题，值得再试。 */
    @Test
    fun `send failure with valid unsent recipients follows the inner cause`() {
        val e = SendFailedException(
            "temporary failure", SocketTimeoutException("Read timed out"),
            /* validSent = */ null, /* validUnsent = */ addr("someone@example.com"), /* invalid = */ null
        )
        assertTrue(MailDelivery.isRetryable(e))
    }

    @Test
    fun `network errors are retryable`() {
        assertTrue(MailDelivery.isRetryable(SocketTimeoutException("connect timed out")))
        assertTrue(MailDelivery.isRetryable(ConnectException("Connection refused")))
        assertTrue(MailDelivery.isRetryable(UnknownHostException("smtp.example.com")))
        assertTrue(MailDelivery.isRetryable(SSLHandshakeException("handshake failed")))
        // JavaMail 把真正的原因塞进 nextException，外层报文看不出根因
        assertTrue(MailDelivery.isRetryable(
            MessagingException("IOException while sending message", SocketTimeoutException("Read timed out"))
        ))
    }

    /** 拿不准一律按不可重试：宁可少发一次，也不要把一个永久错误放大成三次投递。 */
    @Test
    fun `unrecognised exceptions default to not retryable`() {
        assertFalse(MailDelivery.isRetryable(IllegalStateException("something odd")))
        assertFalse(MailDelivery.isRetryable(MessagingException("no cause attached")))
    }

    // ══════════ 失败文案 ══════════

    /** 异常链摊平：外层 MessagingException 毫无信息量，根因挂在 nextException 上。 */
    @Test
    fun `failure description keeps the root cause`() {
        val e = MessagingException("IOException while sending message", SocketTimeoutException("Read timed out"))
        val text = MailDelivery.describeFailure(e)
        assertTrue(text, text.contains("MessagingException"))
        assertTrue(text, text.contains("SocketTimeoutException"))
    }

    /** 永久错误的失败原因要能看出类名（写进 `mail_send_records.error` 后靠它排查）。 */
    @Test
    fun `permanent failure description names the exception`() {
        val detail = MailDelivery.describeAttempts(
            attempts = 1,
            error = MailDelivery.describeFailure(AuthenticationFailedException("535 Login Fail")),
            maxChars = ERROR_COLUMN_CHARS
        )
        assertTrue("失败原因要能看出类名，实际=$detail", detail.contains("AuthenticationFailed"))
        assertTrue("失败原因要能看出尝试次数，实际=$detail", detail.contains("尝试 1 次"))
    }

    /**
     * 截断后尝试次数必须还在：`mail_send_records.error` 只有 200 字，
     * 而"重试过没有"是排查时第一个要看的东西，所以次数放在最前面。
     */
    @Test
    fun `attempt count survives truncation to the column limit`() {
        val long = MailDelivery.describeFailure(MessagingException("x".repeat(500)))
        val text = MailDelivery.describeAttempts(3, long, ERROR_COLUMN_CHARS)
        assertEquals(ERROR_COLUMN_CHARS, text.length)

        assertTrue(text, text.startsWith("尝试 3 次后失败"))
    }
}

