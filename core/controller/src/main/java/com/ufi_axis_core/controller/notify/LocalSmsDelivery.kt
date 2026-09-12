package com.ufi_axis_core.controller.notify

import com.ufi_axis_core.controller.goform.GoformSmsClient
import com.ufi_axis_core.notify.DeliveryOutcome
import com.ufi_axis_core.notify.NotifyLevel


/**
 * 本机短信渠道里**纯逻辑**的那一半：号码校验、正文裁剪、发送结论映射、日志脱敏。
 *
 * 单独成对象是为了可测（同 [WebhookDelivery] / `MailDelivery` 的分法）：
 * [LocalSmsChannel] 要 `Context`（prefs）与真实固件，在 `:core:controller` 的 JVM 单测里
 * 两样都构造不出来 —— 而"70 字刚好不裁 / 71 字要裁 / emoji 别被切成半个代理对"
 * 「tag=3 该不该重试」恰恰是最需要钉住的几条。
 */
internal object LocalSmsDelivery {

    // ══════════ 正文 ══════════

    /**
     * 单条短信的字符预算：**70**。
     *
     * 为什么是 70：`GoformSmsClient.toUcs2Hex` 把正文按 **UCS2（UTF-16BE）** 编码发出去，
     * 而 GSM 短信单条载荷是 140 字节 → 140 / 2 = 70 个 UCS2 码元。
     * 超过就会被固件拆成多段、**按段计费**，所以这条渠道刻意只发一条：
     * 通知的价值在"我知道出事了"，为了完整正文多烧两条话费不值得。
     *
     * 注意这个预算对中英文一视同仁（UCS2 不像 GSM7 那样英文更省），所以直接数
     * `String.length`（Kotlin 的 Char 就是一个 UTF-16 码元）就是准确的字节预算，
     * 不需要再乘系数。
     */
    const val SINGLE_SMS_CHARS = 70

    /** 裁剪标记。占 1 个码元，所以裁剪目标长度是 [SINGLE_SMS_CHARS] - 1。 */
    private const val ELLIPSIS = "…"

    /**
     * 正文前缀。**同时是自激循环防护的第二道标记**（见 [LocalSmsChannel] 类头「自激循环」一节）：
     * 设备发出去的短信会落进自己的信箱，带一个固定前缀就能在需要时认出"这条是我自己发的"。
     */
    const val BODY_PREFIX = "【UFI-AXIS】"

    /** 标题与正文之间的分隔符。用全角冒号与前缀的中文括号成套。 */
    private const val TITLE_SEPARATOR = "："

    /**
     * 拼一条短信正文：`【UFI-AXIS】{title}：{body}`，超过 [SINGLE_SMS_CHARS] 按字符裁剪。
     *
     * **不拼 `meta`**：那是邮件模板的"发件人 / 接收时间"行，在 70 字的预算里它会把真正
     * 要说的事挤出去 —— 短信的职责是"通知你出事了"，细节去邮件或 app 里看。
     *
     * 裁剪时**不切开代理对**：一个 emoji 是两个 UTF-16 码元，正好卡在边界上被切一半的话
     * 收到的是一个乱码方框（而它恰恰可能出现在短信正文转发的场景里）。
     */
    fun compose(title: String, body: String): String {
        val raw = buildString {
            append(BODY_PREFIX)
            append(title)
            if (body.isNotBlank()) {
                append(TITLE_SEPARATOR)
                append(body)
            }
        }
        if (raw.length <= SINGLE_SMS_CHARS) return raw
        var keep = SINGLE_SMS_CHARS - ELLIPSIS.length
        // 末位是高位代理 → 它的低位代理会被丢掉，整个字符变成乱码；少留一个码元。
        if (keep > 0 && raw[keep - 1].isHighSurrogate()) keep -= 1
        return raw.take(keep) + ELLIPSIS
    }

    // ══════════ 号码 ══════════

    /**
     * 去掉用户习惯写的分隔符（`-` 与空格），保留可能的 `+` 国际前缀。
     *
     * 交给固件的必须是规整号码：`138-0013-8000` 里的短横线会被 modem 当成非法字符直接拒收，
     * 而用户在界面上那样填是完全正常的。
     */
    fun normalizeNumber(raw: String): String = raw.filterNot { it == '-' || it == ' ' }.trim()

    /** 号码长度区间（[normalizeNumber] 之后）。下限 3 覆盖短号（`110`）；上限 20 覆盖 `+` 国际号。 */
    const val MIN_NUMBER_CHARS = 3
    const val MAX_NUMBER_CHARS = 20

    /**
     * 号码能不能拨出去。
     *
     * 规则：去掉 `-` 与空格后，长度在 [MIN_NUMBER_CHARS]..[MAX_NUMBER_CHARS]，
     * 且除首位可选的 `+` 之外全是数字。
     *
     * 为什么在 app 与 core 两侧都校验：app 侧是为了当场给出反馈，core 侧是**唯一强制点**
     * （web / curl 也能 PUT）。这道校验也是 [isConfigured] 的一半 ——
     * 一个非法号码存进去只会让每次投递都撞一次固件拒收，而用户看到的只是"配好了却收不到"。
     */
    fun isValidNumber(raw: String): Boolean {
        val n = normalizeNumber(raw)
        if (n.length !in MIN_NUMBER_CHARS..MAX_NUMBER_CHARS) return false
        val digits = if (n.startsWith('+')) n.drop(1) else n
        return digits.isNotEmpty() && digits.all { it.isDigit() }
    }

    /** 能不能投：开关打开 + 号码合法。空号码 = 还没配完，不是"发给自己"。 */
    fun isConfigured(cfg: LocalSmsConfig): Boolean = cfg.enabled && isValidNumber(cfg.targetNumber)

    /**
     * 日志与投递记录里的号码：只留前 3 后 2。
     *
     * 口径同 `GoformSmsClient.maskNumber`（那份是 `internal` 且在另一个模块）：
     * 发失败时要看得出"是不是号码串错了"，但完整手机号是个人信息，不该写进可导出的记录。
     */
    fun maskNumber(raw: String): String {
        val n = normalizeNumber(raw)
        return if (n.length <= MASK_MIN_CHARS) "***" else n.take(MASK_HEAD) + "***" + n.takeLast(MASK_TAIL)
    }

    private const val MASK_HEAD = 3
    private const val MASK_TAIL = 2
    private const val MASK_MIN_CHARS = MASK_HEAD + MASK_TAIL

    // ══════════ 发送结论映射 ══════════

    /**
     * `GoformSmsClient.SendOutcome` → [DeliveryOutcome]。**分发器唯一的重试判据来源。**
     *
     * | 固件结论 | 结果 | 计配额 | 为什么 |
     * |---|---|---|---|
     * | `SENT`（信箱 tag=2） | [DeliveryOutcome.Sent] | 计 | 设备确认已发出，这就是本渠道的"送达确认" |
     * | `FAILED`（信箱 tag=3） | `Failed(retryable=false)` | 计 | 设备明确说发不出去（SMSC 未设 / 未开通 / 欠费 / 被拦）。重试改不了结果，只会再烧一条话费 |
     * | `PENDING` | `Failed(retryable=false)` | 计 | **可能已经发出去了**，所以绝不重试；但也没拿到确认，所以不能报 `Sent`（见下） |
     * | `NO_RESPONSE` | `Failed(retryable=false)` | 计 | 拿不到设备表态，无法排除"其实已经发出去了" —— 重试就是可能的第二笔话费 |
     * | `REJECTED` | `Failed(retryable=true)` | **不计** | 设备明确回了"没收下"，重试不会重复发，换个时间可能就成了 |
     *
     * ## PENDING 为什么算 Failed
     *
     * [LocalSmsChannel.hasDeliveryConfirmation] 是 true，意味着本渠道的 `Sent` 会被
     * `TrafficAutoOffGuard` 当成"通报到位"直接去关移动数据。把一个**没确认**的结果报成
     * `Sent` 就是把那个判据变回恒真 —— 那正是 2026-09-09 修掉的 P1。
     * 代价是投递记录里会多一条"失败"，而它可能其实发出去了 —— 所以这一档的文案必须
     * 写明"可能已发出"，否则用户看到干巴巴一个"失败"就会去手动重发，那才是真花钱。
     *
     * ## 唯一可重试的一档 ⇔ 唯一不计配额的一档
     *
     * 判据只有一个：**我们知不知道设备没收到**。只有 `REJECTED`（设备回了响应、明确拒收）
     * 属于"知道"，所以只有它可重试；而它也是唯一不计配额的一档，于是**重试永远不会重复扣
     * 配额**。这条互斥关系由 `LocalSmsDeliveryTest` 对全枚举逐档钉住。
     *
     * `NO_RESPONSE` 刻意站在另一侧：不可重试（避免重复计费）**且**计配额 ——
     * 它可能真的发出去了，漏计会让实际发送条数超过用户设的每日上限，宁可少发一条。
     */
    fun classify(outcome: GoformSmsClient.SendOutcome): DeliveryOutcome = when (outcome.verdict) {
        GoformSmsClient.SendVerdict.SENT -> DeliveryOutcome.Sent
        GoformSmsClient.SendVerdict.FAILED ->
            DeliveryOutcome.Failed("设备侧发送失败：${outcome.detail}", retryable = false)
        GoformSmsClient.SendVerdict.PENDING -> DeliveryOutcome.Failed(
            "设备未回报最终状态，可能已发出，不会自动重发：${outcome.detail}",
            retryable = false
        )
        GoformSmsClient.SendVerdict.NO_RESPONSE -> DeliveryOutcome.Failed(
            "无法确认设备是否已发出，不会自动重发：${outcome.detail}",
            retryable = false
        )
        GoformSmsClient.SendVerdict.REJECTED ->
            DeliveryOutcome.Failed("设备明确拒收：${outcome.detail}", retryable = true)
    }

    /**
     * 这一次要不要计进今日配额（= 有可能已经花了钱）。
     *
     * 口径**不是**"设备受理了"，而是"排除不了已经发出去"：
     * - `SENT` / `FAILED` / `PENDING` —— 进过发送队列，计；
     * - `NO_RESPONSE` —— 拿不到表态，可能已发出，**也计**（宁可多算一条，也不要让实际
     *   发送条数超过用户设的每日上限）；
     * - `REJECTED` —— 设备明确回了"没收下"，**不计**。
     *
     * 而 `REJECTED` 恰好是 [classify] 里唯一可重试的一档，于是**重试永远不会重复扣配额**。
     * 这条互斥关系是配额这道刹车的正确性前提，改任一侧都必须同时改另一侧。
     */
    fun countsTowardQuota(outcome: GoformSmsClient.SendOutcome): Boolean =
        outcome.verdict != GoformSmsClient.SendVerdict.REJECTED

    /** 异常摊平成一行（含根因）。口径同 [WebhookDelivery.describeFailure]。 */
    fun describeFailure(e: Throwable): String {
        val parts = mutableListOf<String>()
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < CAUSE_DEPTH) {
            parts.add("${cur.javaClass.simpleName}: ${cur.message ?: "-"}")
            cur = cur.cause
            depth++
        }
        return parts.joinToString(" <- ")
    }

    /** 摊平异常链时最多看几层（同 `MailDelivery.CAUSE_DEPTH`）。 */
    private const val CAUSE_DEPTH = 4
}

