package com.ufi_axis_core.controller.sms

import com.ufi_axis_core.core.database.MailSendRecord
import com.ufi_axis_core.notify.ChannelRules
import com.ufi_axis_core.notify.DeliveryAttempt
import com.ufi_axis_core.notify.DeliveryOutcome

import com.ufi_axis_core.notify.NotifyChannel
import com.ufi_axis_core.notify.NotifyEvent
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.NotifyScenes
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CancellationException

/**
 * 邮件渠道：[SmsForwardController] 的**薄适配层**。
 *
 * SMTP 实现原地不动 —— 会话构建、mailcap 注册、`Transport.send`、HTML/纯文本双分段
 * 全部还在控制器里。本类只做三件事：
 * 1. 决定主题（[subjectOf]）；
 * 2. 把异常按 [MailDelivery.isRetryable] 分类成 [DeliveryOutcome.Failed]；
 * 3. 整轮结束后请控制器写一条投递记录。
 *
 * ## 不查闸门
 *
 * 走的是控制器的内部入口 [SmsForwardController.deliverMailOnce]，它**不查总开关与免打扰** ——
 * 全仓唯一的闸门强制点是 `NotificationDispatcher.emit`，本渠道只用
 * [respectsMasterGate] 声明"总闸管得着我"。这里再查一遍不会出错，但那才是真正的第二份判定。
 */
class MailChannel(
    private val controller: SmsForwardController
) : NotifyChannel {

    private val tag = "MailChannel"

    override val id: String = ID

    /** 邮件是唯一有"逐封投递结果"的渠道，所以只有它留 `mail_send_records`。 */
    override val recordsHistory: Boolean = true

    /**
     * **受全局总闸与免打扰约束。**
     *
     * 邮件是设备主动发出去的终态投递：发出去就收不回来，也没有"客户端再决定弹不弹"这一层。
     * 用户关掉总开关或设了静默时段，指的就是"别给我发邮件"。
     * 判定在分发器（`NotificationRoutes.notifyAllowed` 是全仓唯一的谓词实现，
     * 免打扰那一半由 `mail_respect_dnd` 决定）。
     */
    override val respectsMasterGate: Boolean = true

    /**
     * 有送达确认：[deliver] 里的 `Transport.send` 要等 SMTP 服务器的 250 应答才返回，
     * 所以 `Sent` 是"真的交给邮件服务器了"。`TrafficAutoOffGuard` 靠这个位把
     * "邮件确实发出去了"与"推送 fire-and-forget 交出去了"区分开
     * （见 `DeliveryReport.anyConfirmedSent`）。
     */
    override val hasDeliveryConfirmation: Boolean = true

    /** 真源就是控制器那一份判定（`enabled` + SMTP 四项必填齐全），这里不复制条件。 */
    override fun isConfigured(): Boolean = controller.isSendable()

    /**
     * 级别门槛与每日配额的取值口（判定在分发器，见 [ChannelRules]）。
     *
     * 每次现读 prefs：用户改完设置必须立刻生效，缓存一份就成了"改了要重启 core"的假开关。
     * 计数器是**邮件独占**的（`sms_forward` 的 `quota_day` / `quota_count`），
     * 与 Webhook、本机短信那两份物理隔离。
     */
    override val rules: ChannelRules = object : ChannelRules {
        override fun minLevel(): NotifyLevel = controller.loadConfig().minLevel
        override fun dailyLimit(): Int = controller.loadConfig().dailyLimit
        override fun sentToday(): Int = controller.quotaSentToday()
    }



    /** 场景勾选的唯一真源是 `SmsForwardConfig.scenes`（邮件通知页那排 chip 写的就是它）。 */
    override fun accepts(scene: String): Boolean = scene in controller.loadConfig().scenes

    /**
     * 整轮投递持一把 WakeLock。
     *
     * 交给控制器实现（它有 `Context`）。锁必须覆盖**全部重试与退避** ——
     * 退避那几秒正是最容易被 CPU 深睡吞掉的部分，而 core 唯一那把保活锁只在前端连接时续期。
     */
    override suspend fun <T> withRound(block: suspend () -> T): T =
        controller.withSmtpWakeLock(block)

    override suspend fun deliver(event: NotifyEvent): DeliveryAttempt {
        return try {
            controller.deliverMailOnce(subjectOf(event), event)
            // 配额只在成功时计一封：`Transport.send` 返回意味着拿到了 SMTP 的 250 应答，
            // 这是**确定结论**。记账留在渠道里（判定在分发器，见 ChannelRules）。
            controller.consumeQuota()
            // 不带诊断：邮件的 /test 只需要"成功没有 + 失败原因"，前者在 outcome 里、
            // 后者在 Failed.error 里，没有第三样只有渠道知道的东西（对比 Webhook 的状态码）。
            DeliveryAttempt(DeliveryOutcome.Sent)
        } catch (e: CancellationException) {
            // 取消不是投递失败（本仓纪律）
            throw e
        } catch (e: Exception) {
            // 异常分类只有邮件懂：授权码错 / 地址非法 = 永久错，握手超时 / DNS 失败 = 瞬时错。
            // 分发器只认这个布尔，不认 JavaMail 的异常类型。
            DeliveryAttempt(
                DeliveryOutcome.Failed(
                    error = MailDelivery.describeFailure(e),
                    retryable = MailDelivery.isRetryable(e)
                )
            )
        }
    }


    override suspend fun recordHistory(event: NotifyEvent, outcome: DeliveryOutcome, attempts: Int) {
        val subject = subjectOf(event)
        when (outcome) {
            is DeliveryOutcome.Sent -> {
                AppLogger.i(tag, "mail sent（scene=${event.scene}，第 $attempts 次尝试成功）")
                controller.recordMailOutcome(
                    event.scene, subject, MailSendRecord.OUTCOME_SENT, detail = ""
                )
            }
            is DeliveryOutcome.Failed -> {
                val detail = MailDelivery.describeAttempts(attempts, outcome.error, ERROR_CHARS)
                AppLogger.e(tag, "SMTP send failed: $detail")
                controller.recordMailOutcome(
                    event.scene, subject, MailSendRecord.OUTCOME_FAILED, detail
                )
            }
            // DB v12 起跳过也留档：「我开着通知，这条为什么没收到」以前只能从 INFO 日志里找，
            // 而 release 不留 INFO。分发器只在**渠道已配置**时才把 Skipped 送进来
            // （见 NotificationDispatcher.recordIfNeeded），所以 SMTP 没填的用户不会被刷满。
            // 日志不在这里打 —— 分发器已经为每条 Skipped 打过一行（打两遍等于同一件事查两处）。
            is DeliveryOutcome.Skipped -> controller.recordMailOutcome(
                event.scene, subject, MailSendRecord.OUTCOME_SKIPPED, outcome.reason.label
            )
        }
    }

    /**
     * 邮件主题。**纯函数**（只看事件），因为 [deliver] 与 [recordHistory] 各要算一次，
     * 两次算出不同主题的话历史里记的就不是真正发出去的那封。
     *
     * 三条规则：
     * - 有 [NotifyEvent.highlight]（验证码）→ 直接把码放进主题：收件箱列表只显示主题，
     *   截断正文会把码挤掉（"【抖音】你的账号正在新设备上登录，验证码2028，请勿转发…"
     *   前 [SUBJECT_SUMMARY_CHARS] 字未必含码）；
     * - 短信正文场景 → 取正文摘要：放"新短信"这种固定词等于没信息；
     * - 其余场景 → `[UFI-AXIS] 标题`，标题本身已经是一句话（"下载完成: x.iso"）。
     */
    private fun subjectOf(event: NotifyEvent): String {
        val code = event.highlight?.takeIf { it.isNotBlank() }
        return when {
            code != null -> "验证码：$code"
            event.scene == NotifyScenes.SMS -> summarize(event.body)
            else -> "[UFI-AXIS] ${event.title}"
        }
    }

    /**
     * 短信正文 → 主题摘要，**不切开代理对**。
     *
     * 一个 emoji 是两个 UTF-16 码元，正好卡在第 [SUBJECT_SUMMARY_CHARS] 位上被切一半的话，
     * 收件箱里那行主题末尾就是一个乱码方框 —— 而短信正文转发恰恰是最容易带 emoji 的场景。
     * 做法照 `LocalSmsDelivery.compose` 那份既有处理（末位是高位代理就少留一个码元）。
     */
    private fun summarize(body: String): String {
        if (body.length <= SUBJECT_SUMMARY_CHARS) return body
        var keep = SUBJECT_SUMMARY_CHARS
        if (keep > 0 && body[keep - 1].isHighSurrogate()) keep -= 1
        return body.substring(0, keep) + "..."
    }


    companion object {
        /** 渠道 id。`NotifyEvent.channels` 限定集合与 `mail_send_records.channel` 列都用它。 */
        const val ID = "mail"

        /**
         * 短信正文摘要进主题时的截断长度。
         *
         * 37 是沿用值：再长手机邮件客户端的列表也显示不完，反而把发件人挤掉。
         */
        private const val SUBJECT_SUMMARY_CHARS = 37

        /** 失败原因写进 `mail_send_records.error` 的长度上限（与实体注释一致）。 */
        private const val ERROR_CHARS = 200
    }
}
