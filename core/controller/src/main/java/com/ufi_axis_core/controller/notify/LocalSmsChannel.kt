package com.ufi_axis_core.controller.notify

import com.ufi_axis_core.controller.goform.GoformSmsClient
import com.ufi_axis_core.controller.sms.MailDelivery
import com.ufi_axis_core.core.database.MailSendRecord
import com.ufi_axis_core.notify.ChannelRules
import com.ufi_axis_core.notify.DeliveryAttempt
import com.ufi_axis_core.notify.DeliveryOutcome

import com.ufi_axis_core.notify.NotifyChannel
import com.ufi_axis_core.notify.NotifyEvent
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.SkipReason
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CancellationException

/**
 * 本机短信回发渠道：把一条 [NotifyEvent] 用**设备自己的 SIM** 发成一条短信。
 *
 * ## 这条渠道存在的理由：链路互补
 *
 * 邮件与 Webhook 都走**数据网**，本渠道走**信令网**。而"数据断了 / 套餐用尽 /
 * 自动关网"这几类**最该通知**的场景，恰恰是前两条发不出去的时候 ——
 * 那时用户收到的不是告警，而是"设备突然不见了"。
 *
 * 这是本项目独有的能力：设备本身就是 UFI，有 SIM、有发短信通道
 * （`GoformSmsClient.sendSms`）。任何第三方推送方案都给不了这一条。
 *
 * ## 它会花钱，所以有三道刹车（判定全在分发器，顺序即优先级）
 *
 * 1. **最低级别** [LocalSmsConfig.minLevel]（默认 CRITICAL）→ 不够就 `Skipped(LEVEL_TOO_LOW)`；
 * 2. **每日条数上限** [LocalSmsConfig.dailyLimit]（默认 5，本地日期跨天重置）→ 用尽就
 *    `Skipped(`[SkipReason.QUOTA_EXCEEDED]`)`，分发器为它打 **WARN**（真的漏了一条通知）。
 *    **测试发送也计入配额** —— 否则"发送测试"就是一个可以无限点的烧钱按钮；
 * 3. **场景勾选** [LocalSmsConfig.scenes] → 由分发器问 [accepts]。
 *
 * 三道都由分发器按同一份顺序判（2026-09-10「第二步：规则同构」：前两道从 [deliver] 里
 * 搬走了，本渠道只用 [rules] 把取值交出去）。**记账仍留在本渠道** ——
 * 只有它知道"这次到底算不算发出去了"（见 [LocalSmsDelivery.countsTowardQuota]）。

 *
 * ## 自激循环防护（这条渠道最危险的失效模式）
 *
 * 设备发出去的短信会**落进自己的信箱**。如果"新短信"链路把它当成新消息，就会再触发一次
 * 通知 → 再发一条短信 → 无限循环，直到话费烧光。
 *
 * **现状核实（2026-09-09）：既有代码已经按方向排除了自己发出的短信，三处判据一致。**
 * 本渠道**依赖**这三处，改动它们时必须想到这条渠道：
 *
 * | 依赖点 | 判据 |
 * |---|---|
 * | `BackendService.forwardLatestSmsIfNew`（邮件链路） | `SmsForwardWindow.plan` 只挑 `direction == "received"` 的投递，其余只推水位 |
 * | `DataScheduler.collectSmsCache`（WS 推送链路） | `latest.filter { it.direction == "received" && … }` |
 * | `DataScheduler.scanVerificationCodes`（验证码入库） | `if (m.direction != "received") continue` |
 *
 * **2026-09-10 起这条依赖变紧了**：`SmsForwardController.forwardSms` 从
 * `channels = {mail}` 改成 `exclude = {push}`，于是本渠道**也会收到 `sms` /
 * `verification` 场景**的事件 —— 即"用短信通知你收到了一条短信"。上表那三处判据因此
 * 从"防止重复通知"升级成**防止无限循环烧话费**的必要条件，一处都不能删。
 * 默认配置下这条路径不通电：`sms` / `verification` 是 INFO，而 [LocalSmsConfig.minLevel]
 * 默认 CRITICAL → `Skipped(LEVEL_TOO_LOW)`，用户得自己把门槛调到 INFO 并勾上这两个场景。
 *
 * 而 `direction` 的两条来源都把自己发出的短信判成 `"sent"`：
 * `SmsController.smsDirectionFromType`（ContentResolver，`type == 2`）与
 * `SmsController.smsDirectionFromTag`（goform 信箱，`tag == "2"/"3"`）。
 * 那两个判据由 `SmsSelfSentDirectionTest` 钉住 —— **它红了就说明这条渠道会开始烧话费**。
 *
 * 第二道标记：本渠道发出的正文一律带 [LocalSmsDelivery.BODY_PREFIX] 前缀。它目前**不参与
 * 任何判定**（方向判据已经够了，再加一处就是第二份真源），存在的意义是万一将来方向判据
 * 失效，日志与信箱里能一眼认出"这条是设备自己发的"。
 *
 * 另外，[LocalSmsConfig.dailyLimit] 本身就是这条循环的**兜底闸**：即使方向判据被改坏，
 * 一天最多也只烧 [LocalSmsConfig.DEFAULT_DAILY_LIMIT] 条，而不是烧光余额。
 *
 * ## 与另两条渠道的对照
 *
 * 结构照 [WebhookChannel]：渠道是薄适配层，纯判定（号码 / 裁剪 / 结论映射）在
 * [LocalSmsDelivery]，传输在 `GoformSmsClient`，闸门在分发器。这里**不查总开关与免打扰** ——
 * 全仓唯一的闸门强制点是 `NotificationDispatcher.emit`。
 */
class LocalSmsChannel(
    private val store: LocalSmsConfigStore,
    /**
     * 发送出口。**全仓唯一的短信发送实现**（用户在短信页手动发送走的也是它），
     * 本渠道不另造一条发信路径。
     *
     * 它会在 goform 受理之后**回读信箱 tag** 拿真实结论（2=已发送 / 3=失败），
     * 所以这条渠道有货真价实的送达确认（见 [hasDeliveryConfirmation]）。
     */
    private val smsClient: GoformSmsClient,
    /**
     * 投递记录写入口（实现在 `SmsForwardController`，装配层接线）。
     *
     * null = 未接入 = 只是不留记录，投递照走 —— 口径同 [WebhookChannel.history]。
     */
    private val history: DeliveryHistoryRecorder? = null
) : NotifyChannel {

    private val tag = "LocalSmsChannel"

    override val id: String = ID

    /** 有真实结论（信箱 tag），而且这条渠道**花钱** —— "今天到底发出去几条"必须查得到。 */
    override val recordsHistory: Boolean = true

    /**
     * **受全局总闸约束**（恒 true，与邮件 / Webhook 同）。
     *
     * 短信是设备主动发出去的终态投递：发出去就收不回来、还产生了费用，
     * 也没有"客户端再决定弹不弹"这一层。用户关掉总开关就是不想收。
     *
     * 与 [LocalSmsConfig.respectDnd] 的区别：本字段是**渠道级常量**（总闸管不管我），
     * 那个是**用户配置**（免打扰要不要连我一起静默，默认 true）。
     * 免打扰的取值由装配层的闸门 lambda 按渠道 id 去问。
     */
    override val respectsMasterGate: Boolean = true

    /**
     * 有送达确认：信箱 `tag=2` 是**设备确认已经发出去了**。
     *
     * 后果要清楚：`TrafficAutoOffGuard` 的"通报到位"判定
     * （`DeliveryReport.anyConfirmedSent`）从此也认本渠道的成功 —— 只配了短信、
     * 没配 SMTP 的用户，自动关网会照常执行。这正是那个判定想要的语义。
     *
     * 反过来，[LocalSmsDelivery.classify] 里 `PENDING` **不能**报 `Sent`，
     * 否则这个位就成了谎言（理由写在那个函数上）。
     */
    override val hasDeliveryConfirmation: Boolean = true

    /** 判定在 [LocalSmsDelivery.isConfigured]（开关 + 号码合法）。空号码 = 没配完。 */
    override fun isConfigured(): Boolean = LocalSmsDelivery.isConfigured(store.load())

    /**
     * 级别门槛与每日配额的取值口（判定在分发器，见 [ChannelRules]）。
     *
     * 每次现读 prefs：用户在设置页把上限从 5 改成 20 之后必须立刻生效，
     * 缓存一份就成了"改了要重启 core"的假开关。
     *
     * 计数器是**本渠道独占**的（`notify_local_sms` 的 `quota_day` / `quota_count`）——
     * 与邮件、Webhook 那两份物理隔离，谁也吃不掉谁的额度。
     */
    override val rules: ChannelRules = object : ChannelRules {
        override fun minLevel(): NotifyLevel = store.load().minLevel
        override fun dailyLimit(): Int = store.load().dailyLimit
        override fun sentToday(): Int = store.sentToday()
    }


    /** 场景勾选的唯一真源是 [LocalSmsConfig.scenes]。 */
    override fun accepts(scene: String): Boolean = scene in store.load().scenes

    override suspend fun deliver(event: NotifyEvent): DeliveryAttempt {
        val cfg = store.load()

        val number = LocalSmsDelivery.normalizeNumber(cfg.targetNumber)
        val body = LocalSmsDelivery.compose(event.title, event.body)
        val masked = LocalSmsDelivery.maskNumber(cfg.targetNumber)
        return try {
            AppLogger.i(
                tag,
                "本机短信投递 → $masked（scene=${event.scene}，level=${event.level.wireName}，" +
                    "${body.length}/${LocalSmsDelivery.SINGLE_SMS_CHARS} 字，" +
                    "今日已用 ${store.sentToday()}/${cfg.dailyLimit}）"
            )
            val outcome = smsClient.sendSms(number, body)
            // 计不计配额的口径是"排除不了已经发出去"（见 LocalSmsDelivery.countsTowardQuota）：
            // 只有"设备明确拒收"那一档不计，而它恰好是唯一可重试的一档。
            val counted = LocalSmsDelivery.countsTowardQuota(outcome)
            val usedToday = if (counted) store.consume() else store.sentToday()
            val result = LocalSmsDelivery.classify(outcome)
            DeliveryAttempt(
                outcome = result,
                diagnostics = Attempt(
                    at = System.currentTimeMillis(),
                    verdict = outcome.verdict.name,
                    detail = outcome.detail,
                    countedTowardQuota = counted,
                    sentToday = usedToday,
                    quotaRemaining = (cfg.dailyLimit - usedToday).coerceAtLeast(0),
                    error = (result as? DeliveryOutcome.Failed)?.error ?: ""
                )
            )
        } catch (e: CancellationException) {
            // 取消不是投递失败（本仓纪律）
            throw e
        } catch (e: Exception) {
            // 异常发生在"有没有交给固件"之前还是之后无从得知 —— 按**不计配额、不重试**处理：
            // 不计是因为多半没发出去（真发出去了配额少算一条，用户不会因此多花钱）；
            // 不重试是因为"可能已经发出去了"，重试就是可能的第二条话费。
            val error = LocalSmsDelivery.describeFailure(e)
            DeliveryAttempt(
                outcome = DeliveryOutcome.Failed(error, retryable = false),
                diagnostics = Attempt(
                    at = System.currentTimeMillis(),
                    verdict = "EXCEPTION",
                    detail = error,
                    countedTowardQuota = false,
                    sentToday = store.sentToday(),
                    quotaRemaining = store.quotaRemaining(cfg.dailyLimit),
                    error = error
                )
            )
        }
    }


    override suspend fun recordHistory(event: NotifyEvent, outcome: DeliveryOutcome, attempts: Int) {
        val recorder = history ?: return
        // 号码脱敏后才落库：投递记录是用户可以导出、可以贴出来排错的（口径同 Webhook 只写 origin）。
        val target = LocalSmsDelivery.maskNumber(store.load().targetNumber)
        when (outcome) {
            is DeliveryOutcome.Sent -> {
                AppLogger.i(tag, "本机短信已发出（scene=${event.scene}，第 $attempts 次尝试成功）")
                recorder.record(
                    ID, event.scene, event.title, target, MailSendRecord.OUTCOME_SENT, ""
                )
            }
            is DeliveryOutcome.Failed -> {
                // 文案借邮件那份：投递记录的 error 列是多渠道共用的一列，
                // 各写一种格式会让历史页里同一列出现几种句式。
                val detail = MailDelivery.describeAttempts(attempts, outcome.error, ERROR_CHARS)
                AppLogger.e(tag, "本机短信投递失败: $detail")
                recorder.record(
                    ID, event.scene, event.title, target, MailSendRecord.OUTCOME_FAILED, detail
                )
            }
            // DB v12 起跳过也留档（口径同 MailChannel）。本渠道尤其需要：级别不够与配额用尽
            // 都是**本渠道特有**的跳过原因，而"这条告警为什么没发短信"以前只能从 INFO 日志里找。
            is DeliveryOutcome.Skipped -> recorder.record(
                ID, event.scene, event.title, target,
                MailSendRecord.OUTCOME_SKIPPED, outcome.reason.label
            )
        }
    }

    /**
     * 一次尝试的诊断快照，随 [DeliveryAttempt.diagnostics] 交回给调用方，
     * **只给 `POST /api/notify/sms/test` 的响应用。**
     *
     * 为什么要有它：[DeliveryOutcome] 回不出"这次算不算进配额""还剩几条" ——
     * 而那恰恰是用户按下测试按钮后最想知道的两件事（这条渠道花钱）。
     * 把它们塞进 `DeliveryOutcome` 是改分发器的公共契约，只为一个诊断端点不值得
     * （口径同 [WebhookChannel.Attempt]）。
     *
     * 它**不再是渠道上的一个可变字段**：那种写法下 `Skipped(QUOTA_EXCEEDED)`（一次都没投）
     * 时 `/test` 会回读上一次的快照，于是响应里出现 `success=false` 配 `verdict=SENT`
     * 这种自相矛盾的组合 —— 而这条渠道上"上一次"的配额数字还会误导用户去手动重发。
     * 收口方式见 [DeliveryAttempt]。
     *
     * @param verdict 固件结论枚举名（`SENT` / `FAILED` / `PENDING` / `REJECTED` / `NO_RESPONSE` / `EXCEPTION`）。
     * @param countedTowardQuota 这一次有没有计进今日配额（= 排除不了已经发出去）。
     */
    data class Attempt(
        val at: Long,
        val verdict: String,
        val detail: String,
        val countedTowardQuota: Boolean,
        val sentToday: Int,
        val quotaRemaining: Int,
        val error: String
    )


    companion object {
        /**
         * 渠道 id。`NotifyEvent.channels` 限定集合与 `mail_send_records.channel` 列都用它。
         *
         * **刻意不叫 `sms`** —— 那是**场景** id（`NotifyScenes.SMS`，"设备收到新短信"）。
         * 两套字符串撞名之后，`channels = setOf("sms")` 与 `scenes.contains("sms")`
         * 会在阅读时混成一件事，而它们一个是"投给谁"、一个是"投什么"。
         */
        const val ID = "local_sms"

        /** 失败原因写进 `mail_send_records.error` 的长度上限（与实体注释一致）。 */
        private const val ERROR_CHARS = 200
    }
}
