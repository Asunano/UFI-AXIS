package com.ufi_axis_core.controller.sms

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import javax.mail.SendFailedException
import javax.mail.internet.AddressException
import javax.net.ssl.SSLException

/**
 * 邮件投递里**只有邮件懂**的那几条判定：闸门取值、SMTP 异常分类、失败文案。
 *
 * 单独成对象是为了可测：[SmsForwardController] 要 Android `Context`（prefs / PowerManager），
 * 在 `:core:controller` 的 JVM 单测里根本构造不出来，而"账号密码错会不会连打三次"
 * 这种语义恰恰最需要钉住。
 *
 * **这里没有重试循环**（2026-09-08 阶段 1 上移）：通用重试归 `NotificationDispatcher`，
 * 由 `DeliveryOutcome.Failed(retryable)` 驱动。两个循环并存会把一次失败放大成 3 × 3 次尝试，
 * 投递记录也会各写一份。留在邮件侧的只有 [isRetryable] 这个判据 ——
 * 分发器不该也不能知道 `AuthenticationFailedException` 意味着什么。
 */
internal object MailDelivery {

    // ══════════ 闸门 ══════════

    /**
     * 闸门取值。**只读查询，不是强制点。**
     *
     * 判据本体（`master_enabled + 免打扰`）在 `NotificationRoutes.notifyAllowed`，全仓一份；
     * 本函数只负责"没装配算放行"和"manual 跳过"这两条取值规则。
     *
     * 唯一调用方是 [SmsForwardController.isMailGateOpen]，它唯一的用途是给
     * `POST /api/sms-forward/test` 的响应回一个 `auto_notify_enabled`（"这封测试发出去了，
     * 自动通知却是关的"）。**拦投递的强制点只有 `NotificationDispatcher.emit` 一处**，
     * 而且它是按渠道判的：邮件受总闸约束 → `Skipped(GATE)`，推送不受约束照投。
     *
     * @param gate 判据本体，**null = 未装配 = 放行** —— 降级装配下按放行显示，
     *   不能因为装配层漏接就在界面上报"通知已关闭"。
     * @param manual 手动触发（`POST /api/sms-forward/test`）跳过闸门；[isMailGateOpen]
     *   固定传 false，因为它问的正是"自动通知放不放行"。
     */
    fun allowed(gate: (() -> Boolean)?, manual: Boolean): Boolean {
        if (manual) return true
        return gate?.invoke() != false
    }


    // ══════════ 单次尝试预算 ══════════

    /**
     * 单次尝试的最坏耗时：`mail.smtp.connectiontimeout` 10s + `mail.smtp.timeout` 10s
     * （两个值在 [SmsForwardController.buildMessage] 的 props 里设定，改那里要一起改这里）。
     * 供 WakeLock 预算计算，见 `SmsForwardController.SMTP_WAKELOCK_TIMEOUT_MS`。
     */
    const val ATTEMPT_BUDGET_MS = 20_000L

    /** 摊平异常链时最多看几层。 */
    private const val CAUSE_DEPTH = 4

    // ══════════ 可重试判定 ══════════

    /**
     * 这个异常值不值得再试一次。**分发器的重试循环唯一的判据来源。**
     *
     * 判据（逐层拆 `nextException` / `cause`，JavaMail 的外层异常常常只是
     * `MessagingException: IOException while sending message`，根因挂在内层）：
     * - **不可重试**：`AuthenticationFailedException`（账号密码/授权码错）、
     *   `AddressException`（地址语法错）、收件人**全部**无效的 `SendFailedException`。
     *   这三类重试改不了结果，只会对着一个永久错误连打三次。
     * - **可重试**：`SocketTimeoutException` / `ConnectException` / `UnknownHostException` /
     *   `SSLException` 这些网络类 —— UFI 设备的上行本就不稳，一次瞬时失败不该让通知永久丢失。
     * - **拿不准一律按不可重试**：宁可少发一次，也不要把一个永久错误放大成三次投递 + 三份日志。
     */
    fun isRetryable(e: Throwable): Boolean {
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < CAUSE_DEPTH) {
            when (cur) {
                is AuthenticationFailedException -> return false
                is AddressException -> return false
                is SendFailedException -> {
                    // validUnsent 为空 = 没有任何"有效但还没投出去"的地址 = 收件人全无效，重试无意义。
                    // 还有有效地址时不直接判可重试，继续往内层找真正的原因（可能只是超时）。
                    if (cur.validUnsentAddresses?.isNotEmpty() != true) return false
                }
                is SocketTimeoutException,
                is ConnectException,
                is UnknownHostException,
                is SSLException -> return true
            }
            cur = (cur as? MessagingException)?.nextException ?: cur.cause
            depth++
        }
        return false
    }

    // ══════════ 失败文案 ══════════

    /**
     * 把异常链摊平成一行。
     *
     * JavaMail 的外层异常经常毫无信息量 —— `MessagingException: IOException while sending message`
     * 只说明"写流的时候炸了"，真正原因（`UnsupportedDataTypeException: no object DCH for MIME
     * type multipart/alternative`、`SSLHandshakeException`、`SocketTimeoutException`…）
     * 挂在 `nextException` 或 `cause` 上。只记外层等于把根因扔了。
     */
    fun describeFailure(e: Throwable): String {
        val parts = mutableListOf<String>()
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < CAUSE_DEPTH) {
            parts.add("${cur.javaClass.simpleName}: ${cur.message ?: "-"}")
            cur = (cur as? MessagingException)?.nextException ?: cur.cause
            depth++
        }
        return parts.joinToString(" <- ")
    }

    /**
     * 写进投递记录的失败原因：**尝试次数放最前面**，后面才是已经摊平的异常链。
     *
     * 顺序是刻意的 —— `mail_send_records.error` 有列长上限（调用方传 [maxChars]），
     * 异常链动辄超长，把次数放后面会被截掉，而"重试过没有"恰恰是排查时第一个要看的。
     *
     * 入参是**字符串**而不是 `Throwable`：整轮重试后拿到的是渠道给的
     * `DeliveryOutcome.Failed.error`（已经过 [describeFailure]），不再持有异常对象。
     */
    fun describeAttempts(attempts: Int, error: String, maxChars: Int): String =
        "尝试 $attempts 次后失败: $error".take(maxChars)
}
