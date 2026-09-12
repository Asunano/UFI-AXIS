package com.ufi_axis_core.controller.sms

/**
 * 邮件转发链路的**窗口选取**：给定一批短信与上次的水位，算出「这一轮该发哪几条、水位推到哪」。
 *
 * ## 为什么不是"只看最新一条"
 *
 * 2026-09-09 之前 `BackendService.forwardLatestSmsIfNew` 只取 `getLatest()`，
 * 撞到 `direction == "sent"` 时把水位推到那一条就返回。于是
 * **「一条真实来信与本机发出的短信几乎同时落库、来信在先」** 时，那条来信的邮件转发被
 * 永久跳过 —— 水位已经越过它，下一轮再也不会看到它。
 * （推送侧不受影响：它按自己的 highWaterMark 过滤整个列表。）
 *
 * 本机短信回发渠道（`LocalSmsChannel`，每天最多发 5 条）让"自己发出的短信"变成常态，
 * 这个窗口于是从理论问题变成了会真实丢邮件的问题。所以改成扫窗口：
 * 取 id > 水位的**一批**，按 id 升序，只对 `received` 的逐条投递，水位推到这批的最大 id。
 *
 * ## 纯函数的理由
 *
 * 判定与 IO 分开：`BackendService` 那一侧要 `Context`、ContentResolver、SMTP 与 WakeLock，
 * 在 JVM 单测里一样都构造不出来 —— 而"来信在前、自己发的在后不能漏发"恰恰是最需要钉住的
 * 那条回归线（分法同 `LocalSmsDelivery` / `WebhookDelivery`）。
 */
object SmsForwardWindow {

    /**
     * 一轮最多投递几条邮件。
     *
     * 为什么必须有上限：首次启用邮件（水位还是 -1 之后的第一次真正扫描）或长时间离线之后，
     * 窗口里可能积着几十条未转发的短信 —— 一次性发几十封邮件会被 SMTP 服务商判成异常发信
     * （轻则限流、重则封号），而且每封都要持一次 WakeLock 走一次握手。
     * 取 20：够覆盖一次深睡唤醒后积压的正常量级，又不会把一次扫描拖成几分钟。
     */
    const val MAX_BATCH_PER_SCAN = 20

    /**
     * 一轮从短信源读多少条来构成窗口。
     *
     * 比 [MAX_BATCH_PER_SCAN] 宽一档，因为读回来的行里混着本机发出的 `sent`
     * 与超龄的历史短信，它们会占掉名额但不产生邮件。
     * 再大就只是在给 ContentResolver / goform 加无谓的负担：真积压超过 50 条时，
     * 水位照样推到最大 id，多读那些也只会被 [MAX_BATCH_PER_SCAN] 截掉。
     */
    const val SCAN_READ_LIMIT = 50

    /** `SmsController.SmsMessage.direction` 里"设备收到的"那个取值（自己发出的是 `sent`）。 */
    private const val DIRECTION_RECEIVED = "received"

    /**
     * 一轮扫描的结论。
     *
     * @param toForward 要**逐条串行**走邮件投递的短信，已按 id 升序（时间顺序 = 用户读邮件的顺序）。
     * @param highWaterMark 这一轮结束后 `lastForwardedSmsId` 该落到的值。**含 `sent` 与超龄那些** ——
     *   它们本来就不该再被考虑，留在窗口里只会让下一轮重新扫一遍。
     * @param skipped 因 [MAX_BATCH_PER_SCAN] 上限而**被放弃**的来信条数（调用方据此打 WARN）。
     *   这些短信的正文在 `/api/sms` 里一直查得到，放弃的只是邮件通知。
     */
    data class Plan(
        val toForward: List<SmsController.SmsMessage>,
        val highWaterMark: Long,
        val skipped: Int,
    )

    /**
     * 算这一轮的投递计划。
     *
     * 三道闸（与改造前逐条同义，只是从"一条"扩展到"一批"）：
     * 1. `id > lastForwardedId` —— 持久化水位判重，服务重启也认；
     * 2. `direction == "received"` —— 排除设备自己发出去的短信。这是本机短信渠道**自激循环
     *    防护**依赖的三处判据之一（见 `LocalSmsChannel` 类头），删掉它等于开始烧话费；
     * 3. `now - date <= maxAgeMs` —— 首次装机 / 换 id 体系时别把收件箱里的老短信补发一遍。
     *
     * `lastForwardedId < 0` 是**落基线**：一条都不发，只把水位推到当前最大 id。
     * 否则首次启用邮件就会把整个收件箱当成新短信发出去。
     *
     * @param messages 任意顺序的原始读取结果（**不要**预过滤：被拦截规则命中的短信也要进来，
     *   拦截判定与拦截记录在 `SmsForwardController.forwardSms` 里做）。
     * @param now 当前时间（注入而不是内部取，才能在测试里钉住超龄判定）。
     */
    fun plan(
        messages: List<SmsController.SmsMessage>,
        lastForwardedId: Long,
        now: Long,
        maxAgeMs: Long,
        maxBatch: Int = MAX_BATCH_PER_SCAN,
    ): Plan {
        val fresh = messages.filter { it.id > lastForwardedId }
        if (fresh.isEmpty()) return Plan(emptyList(), lastForwardedId, skipped = 0)

        // 水位一律推到窗口里的最大 id：sent 与超龄的那些也算"已处理"，否则每轮都要重扫。
        val highWaterMark = fresh.maxOf { it.id }
        if (lastForwardedId < 0L) return Plan(emptyList(), highWaterMark, skipped = 0)

        val eligible = fresh
            .filter { it.direction == DIRECTION_RECEIVED && now - it.date <= maxAgeMs }
            .sortedBy { it.id }
        // 超上限时留**最新的** maxBatch 条：积压时新的那几条时效性还在，老的早就过时了。
        val batch = if (eligible.size > maxBatch) eligible.takeLast(maxBatch) else eligible
        return Plan(batch, highWaterMark, skipped = eligible.size - batch.size)
    }
}
