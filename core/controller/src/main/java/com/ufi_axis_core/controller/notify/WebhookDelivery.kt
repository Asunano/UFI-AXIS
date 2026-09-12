package com.ufi_axis_core.controller.notify

import com.ufi_axis_core.notify.DeliveryOutcome
import com.ufi_axis_core.notify.NotifyEvent
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Webhook 投递里**纯逻辑**的那一半：模板渲染、配置是否齐全、结果分类、脱敏。
 *
 * 单独成对象是为了可测：[WebhookChannel] 要 `Context`（prefs）与真实 HTTP，
 * 在 `:core:controller` 的 JVM 单测里两样都构造不出来，而"正文里带个引号会不会把 JSON 撑破"
 * 「500 该不该重试」恰恰是最需要钉住的两条。同样的分法见 `MailDelivery`。
 */
internal object WebhookDelivery {

    // ══════════ 模板渲染 ══════════

    /**
     * 模板里可用的占位符 **名 → 一句话说明**（**不含 URL** —— URL 不做替换，理由见 [renderBody]）。
     *
     * ## 为什么带说明、为什么是有序 Map
     *
     * 客户端要把这份清单渲染成**可点击插入**的按钮，光有名字用户分不出 `message` 与 `meta`、
     * `level` 与 `level_label` 的区别。说明文案只在这一处定义并由
     * `GET /api/notify/webhook/config` 回给 UI —— 客户端手抄第二张表的表现是"界面上写着能用、
     * 填了却不替换"或者说明与实际取值对不上，两样都看不出原因。
     *
     * 用有序 `Map` 而不是 `List<Placeholder>`：需要的两件事恰好是 Map 的原生能力 ——
     * ① 与 [placeholderValues] 的键集**逐键比对**（单测钉住"多一个少一个都算错"）；
     * ② 稳定的展示顺序（`mapOf` 是 LinkedHashMap，按声明序）。为此新造一个数据类只会多一个
     * 需要跨模块导出的公开类型，而它除了两个字段什么也不做。
     *
     * 与 `NotifyEvent` 的对应见 [placeholderValues]。
     */
    val PLACEHOLDERS: Map<String, String> = mapOf(
        "title" to "通知标题",
        "message" to "通知正文",
        "level" to "级别的英文口径（info / warning / critical）",
        "level_label" to "级别的中文名（提示 / 警告 / 严重）",
        "scene" to "触发场景（如 alert、sms）",
        "type" to "事件类型（如 temperature、connectivity）",
        "time" to "本地时间（yyyy-MM-dd HH:mm:ss）",
        "timestamp" to "毫秒时间戳（十进制）",
        "meta" to "附加信息（每行一条，如「发件人: 10086」）",
        "highlight" to "需要突出的短值（如验证码），没有时为空"
    )

    /**
     * 占位符 → 原始值（未转义）。渲染时按目标格式各自转义，见 [renderBody] / [renderHeaders]。
     *
     * 键集必须与 [PLACEHOLDERS] **完全一致**（单测钉住）：清单里多一个名字 = 界面上写着能用
     * 却不替换；取值里多一个 = 有个能用的占位符谁也不知道。
     *
     * - `message` 取 [NotifyEvent.body]（不是 `pushTitle` 那类字段）；
     * - `meta` 摊成 `键: 值` 的多行文本，口径与邮件纯文本正文（`MailTemplate.text`）一致，
     *   空值的行照那边一样丢掉 —— 通知里出现一行"发件人: "只会让人以为数据坏了；
     * - `highlight` 为 null 时给**空串**：换成 "null" 字面量的话用户会在通知里收到一个
     *   凭空出现的 null，而它看起来像 bug（因为它就是）；
     * - `timestamp` 是十进制毫秒串（有些目标要它自己判时效），`time` 是给人读的本地时间。
     */
    fun placeholderValues(event: NotifyEvent): Map<String, String> = mapOf(
        "title" to event.title,
        "message" to event.body,
        "level" to event.level.wireName,
        "level_label" to event.level.label,
        "scene" to event.scene,
        "type" to event.type,
        "time" to NotifyTime.format(event.timestamp),
        "timestamp" to event.timestamp.toString(),
        "meta" to event.meta.filter { it.second.isNotBlank() }
            .joinToString("\n") { (k, v) -> "$k: $v" },
        "highlight" to (event.highlight ?: "")
    )


    /**
     * 渲染请求体。
     *
     * ## JSON 注入防线（[json] = true 时）
     *
     * 值**先做 JSON 字符串转义再替换**：短信正文里一个引号就能把
     * `{"content":"{{message}}"}` 撑成非法 JSON（目标服务端回 400，用户只看到"通知没收到"），
     * 而换行、反斜杠、控制字符同样会。转义用 kotlinx.serialization 编一个字符串再剥掉外层引号，
     * **不手写 replace 表** —— 手写表漏掉一类字符时不会报错，只会在某条特定通知上静默失败。
     *
     * 判据是 [json]（由 Content-Type 决定，见 [isJson]）而不是"模板长得像 JSON"：
     * ntfy 那种纯文本 body 不能转义，否则用户收到的正文里会出现 `\n` 字面量。
     *
     * ## URL 不做替换
     *
     * 占位符只在 body 与 header 值里生效。把标题拼进 URL 需要 percent-encoding 的另一套规则，
     * 而且会让"通知内容"参与决定"请求发到哪里" —— 那是给自己开一个 SSRF 的口子。
     *
     * ## 为什么是**一遍正则扫描**而不是逐个 `replace`
     *
     * 逐个 `replace` 会把"先替换进去的值"当成模板再扫一遍：短信正文里带一个
     * `{{highlight}}`（完全正常，用户转发的短信内容就可能长这样）会在后一轮被展开成验证码。
     * 一遍扫描下每个 `{{…}}` 只被看一次，替换进去的内容不再参与后续匹配。
     * 认不出的占位符**原样留下**（模板里写错一个名字时用户看得见 `{{tilte}}`，
     * 而不是变成空串让人以为"取值没了"）。
     */
    fun renderBody(template: String, event: NotifyEvent, json: Boolean): String {
        val values = placeholderValues(event)
        return PLACEHOLDER_TOKEN.replace(template) { m ->
            val raw = values[m.groupValues[1]] ?: return@replace m.value
            if (json) escapeJsonString(raw) else raw
        }
    }

    /**
     * 渲染 header 值。
     *
     * 折行字符（CR / LF）一律换成空格：header 值里的换行会被下游解析成**额外的头部**
     * （经典的 header 注入），而通知标题恰恰是最容易带换行的字段（短信正文抓出来的标题）。
     * header 名不替换占位符 —— 头部名字是配置的一部分，不该随每条通知变化。
     *
     * 一遍扫描的理由同 [renderBody]。
     */
    fun renderHeaders(headers: Map<String, String>, event: NotifyEvent): Map<String, String> {
        val values = placeholderValues(event)
        return headers.mapValues { (_, template) ->
            PLACEHOLDER_TOKEN.replace(template) { m -> values[m.groupValues[1]] ?: m.value }
                .replace('\r', ' ').replace('\n', ' ')
        }
    }

    /** Content-Type 是不是 JSON 类（决定 [renderBody] 走不走转义分支）。 */
    fun isJson(contentType: String): Boolean = contentType.contains("json", ignoreCase = true)

    /** `{{key}}`。占位符定界符只有这一处，改法式样时不会有第二份。 */
    private val PLACEHOLDER_TOKEN = Regex("""\{\{(\w+)}}""")


    /**
     * JSON 字符串字面量的转义（不含外层引号）。
     *
     * 借 kotlinx.serialization 编码一个 `String` 再剥掉首尾引号：它处理 `"` `\` 与全部
     * 控制字符（换行 → `\n`），比手写 replace 表可靠。
     */
    private fun escapeJsonString(raw: String): String {
        val quoted = Json.encodeToString(String.serializer(), raw)
        return quoted.substring(1, quoted.length - 1)
    }


    // ══════════ 配置是否齐全 ══════════

    /**
     * 能不能投。三条：开关打开、URL 非空且是 http(s)、URL 里没有留着预设的 `<占位>`。
     *
     * 第三条是给预设兜底的：`https://api.day.app/<device_key>` 是**示例**，
     * 直接发出去会打到一个不存在的 device key 上，而用户看到的只是"配好了但收不到"。
     */
    fun isConfigured(cfg: WebhookConfig): Boolean =
        cfg.enabled &&
            cfg.url.isNotBlank() &&
            HttpNotifier.isAllowedUrl(cfg.url) &&
            !cfg.url.contains(PLACEHOLDER_MARK) &&
            // body 模板也要查：PushPlus / Server酱 这类预设把 token 放在 **body** 里而不是 URL 里，
            // 只查 URL 会让「token 还是 <your_token>」被判成配置完整 —— 于是开关显示"已启用"、
            // 每条通知都被目标拒收，用户在 UI 上看不出哪里不对。这正是本仓不许出现的假成功。
            !cfg.bodyTemplate.contains(PLACEHOLDER_MARK)

    /**
     * 预设里"这里要你自己填"的记号（见 [WebhookPreset.defaultUrl] / [WebhookPreset.defaultBody]）。
     *
     * 用裸 `<` 而不是 `<...>` 正则：模板本身是 JSON 或纯文本，出现 `<` 的正当理由几乎没有，
     * 而漏判一个没填完的占位比误判一个含 `<` 的正常模板代价大得多（后者用户改一个字符就能绕开，
     * 前者要靠"为什么一条都收不到"去反查）。
     */
    private const val PLACEHOLDER_MARK = "<"

    // ══════════ 结果分类 ══════════

    /**
     * HTTP 状态码 → 投递结果。**分发器唯一的重试判据来源。**
     *
     * - `2xx` → [DeliveryOutcome.Sent]：Webhook 的"送达确认"就是这个（见
     *   [WebhookChannel.hasDeliveryConfirmation]）。
     * - `429` / `5xx` → 可重试：限流与服务端故障都是**换个时间就可能成功**的。
     * - 其余 `4xx` → **不可重试**：URL 写错、token 失效、body 被目标拒收，重试改不了结果，
     *   只会对着一个永久错误连打三次（口径与 `MailDelivery.isRetryable` 对授权失败的处理一致）。
     * - `3xx` → 不可重试：客户端配了 `followRedirects = false`，跟不跟随由用户改 URL 决定。
     */
    fun classify(code: Int, bodySummary: String): DeliveryOutcome = when {
        code in 200..299 -> DeliveryOutcome.Sent
        code in 300..399 -> DeliveryOutcome.Failed(
            "HTTP $code 重定向（未跟随，需将请求地址改为最终地址）${suffix(bodySummary)}",
            retryable = false
        )
        isRetryableStatus(code) -> DeliveryOutcome.Failed(
            "HTTP $code${suffix(bodySummary)}", retryable = true
        )
        else -> DeliveryOutcome.Failed("HTTP $code${suffix(bodySummary)}", retryable = false)
    }

    /** 429（限流）与 5xx（服务端故障）值得再试；其余状态码不值得。 */
    fun isRetryableStatus(code: Int): Boolean = code == TOO_MANY_REQUESTS || code in 500..599

    /**
     * 网络层异常值不值得再试。
     *
     * 超时 / 连不上 / DNS 解析不了 / TLS 握手失败在 UFI 这种设备上是**常态**（上行本就不稳），
     * 一次瞬时失败不该让通知永久丢失。判据与 `MailDelivery.isRetryable` 的网络类分支同口径。
     *
     * 其余 [IOException]（连接被重置、流提前结束…）也按可重试处理：它们都是链路问题，
     * 与"目标明确拒收"（那是 4xx，走 [classify]）性质不同。
     * 拿不准的非 IO 异常一律不可重试 —— 宁可少发一次，也不要把一个永久错误放大成三次。
     */
    fun isRetryableException(e: Throwable): Boolean {
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < CAUSE_DEPTH) {
            when (cur) {
                is SocketTimeoutException,
                is ConnectException,
                is UnknownHostException,
                is SSLException -> return true
                // IllegalArgumentException 走不到这里（scheme / method 不合法在配置校验就拒了），
                // 但真走到也不该重试 —— 那是配置错，不是链路抖动。
                is IllegalArgumentException -> return false
                is IOException -> return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    /** 异常摊平成一行（含根因）。口径同 `MailDelivery.describeFailure`。 */
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

    private fun suffix(bodySummary: String): String =
        if (bodySummary.isBlank()) "" else " ${bodySummary.take(HttpNotifier.BODY_SUMMARY_CHARS)}"

    /** 摊平异常链时最多看几层（同 `MailDelivery.CAUSE_DEPTH`）。 */
    private const val CAUSE_DEPTH = 4

    /** 429 Too Many Requests。 */
    private const val TOO_MANY_REQUESTS = 429

    // ══════════ 脱敏 ══════════

    /**
     * URL 的 scheme + host（+ 端口），**丢掉 path 与 query**。
     *
     * 用途是日志与投递记录的"投到哪儿了"。整条 URL 不能落地：Bark 的 device key、
     * Server 酱的 SENDKEY、企业微信的 key 全都在 path / query 里 —— 那就是密钥，
     * 写进用户可导出的投递记录等于把它泄漏出去（沿用 `ConfigRoutes.maskSecret` 的思路：
     * 敏感值只留够辨认的部分）。
     */
    fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return ""
        val hostStart = schemeEnd + 3
        val hostEnd = url.indexOfFirst(hostStart) { it == '/' || it == '?' || it == '#' }
        return url.substring(0, hostEnd)
    }

    /**
     * 把一段**要落地的文本**（失败原因 / 日志行）里的 URL 换成 [originOf] 的结果。
     *
     * ## 为什么必须有这道出口
     *
     * [originOf] 建立的不变量是"整条 URL 不能落地"，但异常 message 会绕过它：Ktor 的
     * `HttpRequestTimeoutException` 长这样 ——
     * `Request timeout has expired [url=https://api.day.app/<device key>/x, request_timeout=10000 ms]`，
     * 而 [describeFailure] 原样逐层拼 `message`。那条字符串同时进三处：
     * `/test` 响应、`mail_send_records.error`（用户可导出）、`AppLogger.e`。
     * Bark 的 device key 与 Server 酱的 SENDKEY 就在 path / query 里 —— 于是"只留 origin"
     * 这条承诺在**最需要它的失败路径上**恰好失效。
     *
     * ## 为什么不只做一次字符串等值替换
     *
     * 客户端拼出来的 URL 与配置里那条**不一定逐字相同**（尾部斜杠、大小写 scheme、
     * 编码后的 query）。所以三步都做，顺序即从"最准"到"最宽"：
     * 1. 配置里那条 URL 的**等值**替换（最准，也覆盖 origin 为空的畸形 URL）；
     * 2. 正则扫掉文本里**所有**裸 `http(s)://…` 片段，各自换成自己的 origin ——
     *    这一步顺带处理了 `[url=…]`，因为那对括号里装的就是一条裸 URL；
     * 3. 兜住 `url=` 后面**不是** http(s) 开头的情形（第 2 步扫不到），整段换成 origin。
     *
     * `[url=…, request_timeout=…]` 里的 `request_timeout` **刻意保留**：它是排"是不是超时
     * 设太短"时唯一有用的数字，而它不是凭据。
     */
    fun sanitize(text: String, url: String): String {
        val origin = originOf(url).ifBlank { REDACTED }
        var out = if (url.isNotBlank()) text.replace(url, origin) else text
        out = URL_LITERAL.replace(out) { m -> originOf(m.value).ifBlank { REDACTED } }
        return URL_ASSIGNMENT.replace(out) { "url=$origin" }
    }

    /**
     * 文本里的裸 URL 片段。
     *
     * 终止符包含 `,` 与 `]`：Ktor 那条 message 是 `[url=…, request_timeout=…]`，
     * 不停在逗号上就会把整段括号连 `request_timeout` 一起吞掉（那个数字要留给排查）。
     */
    private val URL_LITERAL = Regex("""https?://[^\s"'<>\])},]+""", RegexOption.IGNORE_CASE)

    /** `url=…` 赋值片段（值不是 http(s) 开头时 [URL_LITERAL] 扫不到，由它兜住）。 */
    private val URL_ASSIGNMENT = Regex("""url=[^,\]\s]*""", RegexOption.IGNORE_CASE)

    /** URL 认不出 scheme（拿不到 origin）时的替代物。整条都不能留，那里面可能就是密钥。 */
    private const val REDACTED = "***"

    /**
     * header 值脱敏后的 map，**只给日志用**。
     *
     * Authorization / token / key / secret / cookie 这几类头部的值是凭据，
     * 打进日志之后日志导出、贴给别人排错就都带着它。名字保留（"我配了哪个头"要看得见），
     * 值一律换成长度提示。
     */
    fun maskHeaders(headers: Map<String, String>): Map<String, String> =

        headers.mapValues { (name, value) ->
            if (SENSITIVE_HEADER_HINTS.any { name.contains(it, ignoreCase = true) }) {
                "***(${value.length})"
            } else {
                value
            }
        }

    /** 头部名里出现这些词就按凭据处理（宁可多脱敏一个，也不要漏一个）。 */
    private val SENSITIVE_HEADER_HINTS = listOf("authorization", "token", "key", "secret", "cookie", "auth")

    /** `indexOfFirst` 的带起点版本（`String` 自带的那个不接 fromIndex）。 */
    private inline fun String.indexOfFirst(fromIndex: Int, predicate: (Char) -> Boolean): Int {
        for (i in fromIndex until length) {
            if (predicate(this[i])) return i
        }
        return length
    }
}
