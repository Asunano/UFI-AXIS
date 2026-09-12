package com.ufi_axis_core.controller.notify

import com.ufi_axis_core.controller.sms.MailDelivery
import com.ufi_axis_core.core.database.MailSendRecord
import com.ufi_axis_core.notify.ChannelRules
import com.ufi_axis_core.notify.DeliveryAttempt
import com.ufi_axis_core.notify.DeliveryOutcome

import com.ufi_axis_core.notify.NotifyChannel
import com.ufi_axis_core.notify.NotifyEvent
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CancellationException

/**
 * 通用 Webhook 渠道：把一条 [NotifyEvent] 按用户配的模板 POST 到用户配的 URL。
 *
 * ## 一条投递路径，七个预设
 *
 * [WebhookPreset] 只是"帮用户把模板填好"的默认值表，**投递代码里没有 `when (preset)`** ——
 * 存储里存的是最终的 url / method / headers / body，用户改了模板就以他改的为准。
 * 按预设分支的话七个预设就是七套各自的投递代码，而且改了模板还会跟 UI 显示的不一致。
 *
 * ## 与邮件渠道的对照
 *
 * 结构照 `MailChannel`：渠道是薄适配层，纯判定（渲染 / 分类 / 脱敏）在 [WebhookDelivery]，
 * 传输在 [HttpNotifier]，闸门在分发器。这里**不查总开关与免打扰** ——
 * 全仓唯一的闸门强制点是 `NotificationDispatcher.emit`。
 */
class WebhookChannel(
    private val store: WebhookConfigStore,
    /**
     * 投递记录写入口（实现在 `SmsForwardController`，装配层接线）。
     *
     * null = 未接入 = 只是不留记录，投递照走 —— 记录是自查手段，不该成为投递的前置条件
     * （与 `SmsForwardController.mailHistoryDao` 同口径）。
     */
    private val history: DeliveryHistoryRecorder? = null
) : NotifyChannel {

    private val tag = "WebhookChannel"

    /** 随渠道生命周期的 HTTP 客户端。停服时由 [close] 收（接在 `BackendService` 的停服路径上）。 */
    private val http = HttpNotifier()

    override val id: String = ID

    /** HTTP 有状态码，"这条投出去了没有"是有答案的，所以留投递记录（表与邮件共用，`channel` 列区分）。 */
    override val recordsHistory: Boolean = true

    /**
     * **受全局总闸约束**（恒 true，与 `MailChannel` 同）。
     *
     * Webhook 是设备主动发出去的终态投递：请求打出去就收不回来，也没有"客户端再决定弹不弹"
     * 这一层。用户关掉总开关就是不想收。
     *
     * 注意与 [WebhookConfig.respectDnd] 的区别：本字段是**渠道级常量**（总闸管不管我），
     * 那个是**用户配置**（免打扰要不要连我一起静默）。免打扰的取值由装配层的闸门 lambda
     * 按渠道 id 去问（见 `NotificationRoutes.notifyAllowed`）。
     */
    override val respectsMasterGate: Boolean = true

    /**
     * 有送达确认：2xx 是目标服务端**收下了**这条通知。
     *
     * 后果要清楚：`TrafficAutoOffGuard` 的"通报到位"判定（`DeliveryReport.anyConfirmedSent`）
     * 从此也认 Webhook 的成功 —— 只配了 Bark、没配 SMTP 的用户，自动关网会照常执行。
     * 这正是那个判定想要的语义（"用户确实收到通报了"），不是副作用。
     */
    override val hasDeliveryConfirmation: Boolean = true

    /** 判定在 [WebhookDelivery.isConfigured]（开关 + URL scheme + 预设占位没填完）。 */
    override fun isConfigured(): Boolean = WebhookDelivery.isConfigured(store.load())

    /**
     * 级别门槛与每日配额的取值口（判定在分发器，见 [ChannelRules]）。
     *
     * 每次现读 prefs：用户改完设置必须立刻生效，缓存一份就成了"改了要重启 core"的假开关。
     * 一次判定要问这里三次、还要问 [isConfigured] 与 [accepts]，所以**读得便宜**这件事由
     * [WebhookConfigStore.load] 那侧的快照保证（按写入失效，不是按时间过期，
     * 所以"立刻生效"不受影响）—— 渠道这边保持"每次现读"的写法不变。
     * 计数器是**本渠道独占**的（`notify_webhook` 的 `quota_day` / `quota_count`），
     * 与邮件、本机短信那两份物理隔离。
     */
    override val rules: ChannelRules = object : ChannelRules {
        override fun minLevel(): NotifyLevel = store.load().minLevel
        override fun dailyLimit(): Int = store.load().dailyLimit
        override fun sentToday(): Int = store.sentToday()
    }


    /** 场景勾选的唯一真源是 [WebhookConfig.scenes]。 */
    override fun accepts(scene: String): Boolean = scene in store.load().scenes

    override suspend fun deliver(event: NotifyEvent): DeliveryAttempt {
        val cfg = store.load()
        val origin = WebhookDelivery.originOf(cfg.url)
        return try {
            val headers = WebhookDelivery.renderHeaders(cfg.headers, event)
            // GET 不带 body：见 HttpNotifier.send。渲染也一并跳过，省一次转义。
            val body = if (cfg.method.equals(METHOD_GET, ignoreCase = true)) {
                ""
            } else {
                WebhookDelivery.renderBody(cfg.bodyTemplate, event, WebhookDelivery.isJson(cfg.contentType))
            }
            // 日志里 header 值脱敏（Authorization / token 是凭据），URL 只留 scheme+host。
            AppLogger.i(
                tag,
                "webhook 投递 ${cfg.method} $origin（scene=${event.scene}，headers=${WebhookDelivery.maskHeaders(headers)}）"
            )
            val result = http.send(
                url = cfg.url,
                method = cfg.method,
                headers = headers,
                body = body,
                contentType = cfg.contentType,
                timeoutMs = cfg.timeoutMs
            )
            val outcome = WebhookDelivery.classify(result.code, result.bodySummary)
            // 配额只在 Sent 时计一条：HTTP 2xx 是**确定结论**（目标收下了），
            // 不存在本机短信那种"可能已经发出去了"的中间态，所以不需要那侧
            // "排除不了已经发出去"的宽口径。记账留在渠道里（判定在分发器，见 ChannelRules）。
            if (outcome is DeliveryOutcome.Sent) store.consume()
            DeliveryAttempt(
                outcome = outcome,
                diagnostics = Attempt(
                    at = System.currentTimeMillis(),
                    statusCode = result.code,
                    bodySummary = result.bodySummary,
                    error = (outcome as? DeliveryOutcome.Failed)?.error ?: ""
                )
            )
        } catch (e: CancellationException) {
            // 取消不是投递失败（本仓纪律）
            throw e
        } catch (e: Exception) {
            // 脱敏必须在这一处、且只在这一处：下面这个 error 同时进 /test 响应、
            // `mail_send_records.error`（用户可导出）与 AppLogger.e ——
            // 三处共享同一份字符串，所以也共享同一份脱敏（理由见 WebhookDelivery.sanitize）。
            val error = WebhookDelivery.sanitize(WebhookDelivery.describeFailure(e), cfg.url)
            // 异常分类只有本渠道懂（超时/DNS/TLS = 瞬时，scheme 非法 = 永久）。
            DeliveryAttempt(
                outcome = DeliveryOutcome.Failed(error, retryable = WebhookDelivery.isRetryableException(e)),
                diagnostics = Attempt(
                    at = System.currentTimeMillis(),
                    statusCode = null,
                    bodySummary = "",
                    error = error
                )
            )
        }
    }


    override suspend fun recordHistory(event: NotifyEvent, outcome: DeliveryOutcome, attempts: Int) {
        val recorder = history ?: return
        val target = WebhookDelivery.originOf(store.load().url)
        when (outcome) {
            is DeliveryOutcome.Sent -> {
                AppLogger.i(tag, "webhook sent（scene=${event.scene}，第 $attempts 次尝试成功）")
                recorder.record(
                    ID, event.scene, event.title, target, MailSendRecord.OUTCOME_SENT, ""
                )
            }
            is DeliveryOutcome.Failed -> {
                // 文案借邮件那份：投递记录的 error 列是**多渠道共用**的一列，
                // 两个渠道各写一种格式会让历史页里同一列出现两种句式。
                val detail = MailDelivery.describeAttempts(attempts, outcome.error, ERROR_CHARS)
                AppLogger.e(tag, "webhook 投递失败: $detail")
                recorder.record(
                    ID, event.scene, event.title, target, MailSendRecord.OUTCOME_FAILED, detail
                )
            }
            // DB v12 起跳过也留档（口径同 MailChannel）：分发器只在渠道已配置时才送进来，
            // 日志也由分发器统一打，这里只负责落库。
            is DeliveryOutcome.Skipped -> recorder.record(
                ID, event.scene, event.title, target,
                MailSendRecord.OUTCOME_SKIPPED, outcome.reason.label
            )
        }
    }

    /**
     * 一次尝试的诊断快照，随 [DeliveryAttempt.diagnostics] 交回给调用方，
     * **只给 `POST /api/notify/webhook/test` 的响应用。**
     *
     * 为什么要有它：`DeliveryOutcome` 只有 `Sent` / `Failed(error)` 两态，回不出"HTTP 几多、
     * 目标说了什么" —— 而那两样恰恰是用户排 Webhook 时唯一有用的信息（`{"code":40001,
     * "msg":"invalid token"}` 这种）。把状态码塞进 `DeliveryOutcome` 是改分发器的公共契约，
     * 只为一个诊断端点不值得（口径同 `SmsForwardController.isMailGateOpen`：只读查询，不拦投递）。
     *
     * 它**不再是渠道上的一个可变字段** —— 那种写法在 `Skipped`（一次都没投）与并发投递两种
     * 时序下都会让 `/test` 读到别人那一次的结果，理由与收口方式见 [DeliveryAttempt]。
     *
     * @param statusCode null = 请求没走完（超时 / 连不上 / DNS / TLS），此时看 [error]。
     */
    data class Attempt(
        val at: Long,
        val statusCode: Int?,
        val bodySummary: String,
        val error: String
    )


    /** 停服收尾：关 HTTP 连接池。分发器只清注册表，渠道自己的资源由 owner 收（既有分工）。 */
    fun close() {
        http.close()
    }

    companion object {
        /** 渠道 id。`NotifyEvent.channels` 限定集合与 `mail_send_records.channel` 列都用它。 */
        const val ID = "webhook"

        private const val METHOD_GET = "GET"

        /** 失败原因写进 `mail_send_records.error` 的长度上限（与实体注释一致）。 */
        private const val ERROR_CHARS = 200
    }
}
