package com.ufi_axis_core.controller.notify

import com.ufi_axis_core.notify.DeliveryOutcome
import com.ufi_axis_core.notify.NotifyEvent
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.NotifyScenes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * [WebhookDelivery] 里**只有 Webhook 懂**的那几条判定：模板渲染与 JSON 注入防线、
 * 配置齐不齐、状态码/异常 → 可重试。
 *
 * 为什么测这个对象而不测 [WebhookChannel]：渠道要 `WebhookConfigStore`（`Context` → prefs）
 * 与真实 HTTP 出口，`:core:controller` 的单测源集只有 junit，两样都构造不出来。
 * 分法与 `MailDeliveryTest` 一致 —— 渠道是薄适配层，纯判定全在这个对象里。
 * 渠道级常量与 `accepts` 那几条要真实例，在 `:core:api` 的 `WebhookChannelContractTest`
 * （那边有 Robolectric，能给出真 `Context`）。
 */
class WebhookDeliveryTest {

    private companion object {
        /** 固定时间戳：`{{time}}` 的期望值不能依赖"跑测试的那一刻"。 */
        const val FIXED_TS = 1_757_000_000_000L

        /**
         * 标题里的注入载荷：引号（撑破 JSON 字符串）、反斜杠（把后一个字符吃掉）、
         * 换行与制表符（JSON 里非法的裸控制字符）。
         */
        const val NASTY_TITLE = "他说\"设备离线\"了\\反斜杠\r\n第二行\t制表符"

        /**
         * 正文里的注入载荷：先闭合当前字符串、再塞一个自己的键，最后留一个孤立反斜杠。
         * 转义没做的话解析出来会多一个 `injected` 键 —— 那就是"通知内容能改请求结构"。
         */
        const val NASTY_MESSAGE = "{\"a\":1}\"},\"injected\":\"x\n结尾反斜杠\\"

        /** `{{time}}` 的格式（真源 `NotifyTime.PATTERN`），只用来验"替换出来的确实是个时间"。 */
        val TIME_SHAPE = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")

        /** Bark 的 device key —— 它就是凭据，脱敏用例里断言它不出现。 */
        const val BARK_KEY = "AbCdEf123456"

        /** 一条真实形状的 Bark 地址：凭据在 **path** 里（这正是整条 URL 不能落地的原因）。 */
        const val BARK_URL = "https://api.day.app/$BARK_KEY/notify"
    }


    private fun event(
        title: String = "设备温度过高",
        body: String = "当前 58℃，阈值 55℃",
        level: NotifyLevel = NotifyLevel.WARNING,
        scene: String = NotifyScenes.ALERT,
        type: String = "temperature",
        timestamp: Long = FIXED_TS,
        meta: List<Pair<String, String>> = emptyList(),
        highlight: String? = null
    ): NotifyEvent = NotifyEvent(
        scene = scene,
        type = type,
        level = level,
        title = title,
        body = body,
        timestamp = timestamp,
        meta = meta,
        highlight = highlight
    )


    /** 递归收集 JSON 里所有**字符串**叶子值（值有没有逐字保住要看这些）。 */
    private fun stringLeaves(el: JsonElement): List<String> = when (el) {
        is JsonObject -> el.values.flatMap { stringLeaves(it) }
        is JsonArray -> el.flatMap { stringLeaves(it) }
        is JsonPrimitive -> if (el.isString) listOf(el.content) else emptyList()
    }

    // ══════════ 模板渲染 ══════════

    /**
     * 六个占位符**全部**被替换。
     *
     * 漏掉一个不会报错：用户收到的通知里会留着 `{{scene}}` 这样的字面量，
     * 而日志与投递记录都显示"投递成功"。
     */
    @Test
    fun `六个占位符全部被替换且不残留花括号`() {
        val body = WebhookDelivery.renderBody(
            WebhookPreset.CUSTOM.defaultBody, event(), json = true
        )

        assertFalse("渲染后仍有未替换的占位符：$body", body.contains("{{"))

        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("设备温度过高", obj.getValue("title").jsonPrimitive.content)
        assertEquals("当前 58℃，阈值 55℃", obj.getValue("message").jsonPrimitive.content)
        assertEquals("warning", obj.getValue("level").jsonPrimitive.content)
        assertEquals("alert", obj.getValue("scene").jsonPrimitive.content)
        assertEquals("temperature", obj.getValue("type").jsonPrimitive.content)

        val time = obj.getValue("time").jsonPrimitive.content
        assertEquals("{{time}} 要用 NotifyTime 的格式（与邮件正文逐字一致）", NotifyTime.format(FIXED_TS), time)
        assertTrue("{{time}} 渲染成了 $time，不是 yyyy-MM-dd HH:mm:ss", TIME_SHAPE.matches(time))
    }

    /**
     * 占位符清单**与取值实现逐键一致**（多一个少一个都算错）。
     *
     * `WebhookConfig.PLACEHOLDERS` 把它原样回给 UI（`GET /api/notify/webhook/config`）：
     * - 清单里多一个名字 = 界面上写着能用、用户填了却不替换；
     * - 取值里多一个 = 有个能用的占位符谁也不知道（也没有说明文案）。
     * 两种都不会报错，所以由这条用例挡住。顺序也钉住（它就是 UI 上按钮的排列顺序）。
     */
    @Test
    fun `占位符清单与取值实现逐键一致`() {
        assertEquals(
            listOf(
                "title", "message", "level", "level_label", "scene",
                "type", "time", "timestamp", "meta", "highlight"
            ),
            WebhookDelivery.PLACEHOLDERS.keys.toList()
        )
        assertEquals(
            "清单与 placeholderValues 的键集必须完全一致",
            WebhookDelivery.PLACEHOLDERS.keys,
            WebhookDelivery.placeholderValues(event()).keys
        )
        // 每个占位符都得有一句说明 —— 客户端要靠它渲染插入按钮的提示
        for ((name, desc) in WebhookDelivery.PLACEHOLDERS) {
            assertTrue("占位符 $name 没有说明文案", desc.isNotBlank())
        }
        assertEquals(WebhookDelivery.PLACEHOLDERS, WebhookConfig.PLACEHOLDERS)
    }

    /**
     * 后补的四个占位符各自取对了值。
     *
     * `level_label` 取 `NotifyLevel.label`（中文名的真源在枚举上，不在这里另抄一张映射表），
     * `timestamp` 是十进制毫秒串（有目标服务端拿它判时效），`meta` 摊成 `键: 值` 多行，
     * `highlight` 有值时原样给出。
     */
    @Test
    fun `新增的四个占位符取值正确`() {
        val values = WebhookDelivery.placeholderValues(
            event(
                level = NotifyLevel.CRITICAL,
                meta = listOf("发件人" to "10086", "空值" to ""),
                highlight = "482913"
            )
        )

        assertEquals("严重", values.getValue("level_label"))
        assertEquals(NotifyLevel.CRITICAL.label, values.getValue("level_label"))
        assertEquals(FIXED_TS.toString(), values.getValue("timestamp"))
        assertTrue("timestamp 必须是十进制数字串", values.getValue("timestamp").all { it.isDigit() })
        // 空值的 meta 行丢掉（口径同邮件纯文本正文）：一行"空值: "只会让人以为数据坏了
        assertEquals("发件人: 10086", values.getValue("meta"))
        assertEquals("482913", values.getValue("highlight"))
    }

    /**
     * `highlight` 为 null → **空串**，不是 "null" 字面量。
     *
     * 大多数事件没有验证码，如果替换成 "null"，用户在通知里会看到一个凭空出现的 null ——
     * 看起来就是个 bug（因为它就是）。`meta` 为空同理。
     */
    @Test
    fun `highlight 为 null 时替换成空串而不是 null 字面量`() {
        val values = WebhookDelivery.placeholderValues(event(highlight = null, meta = emptyList()))
        assertEquals("", values.getValue("highlight"))
        assertEquals("", values.getValue("meta"))

        val body = WebhookDelivery.renderBody(
            """{"code":"{{highlight}}","meta":"{{meta}}"}""", event(), json = true
        )
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("", obj.getValue("code").jsonPrimitive.content)
        assertEquals("", obj.getValue("meta").jsonPrimitive.content)
        assertFalse("渲染结果里出现了 null 字面量：$body", body.contains("null"))
    }

    /**
     * JSON 档下 `meta` 里的引号与换行照样要被转义（取值处不自己转义，统一由 renderBody 做）。
     *
     * meta 的内容来自短信发件人 / 任务名这类外部字符串，带引号完全正常 ——
     * 不转义就会把 body 撑成非法 JSON，用户只看到"有时候收不到"。
     */
    @Test
    fun `meta 与 highlight 在 JSON 档下被转义且值逐字保留`() {
        val meta = listOf("发件人" to "带\"引号\"的人", "备注" to "第一行\n第二行")
        val body = WebhookDelivery.renderBody(
            """{"meta":"{{meta}}","code":"{{highlight}}"}""",
            event(meta = meta, highlight = "9\"9"),
            json = true
        )

        // 非法 JSON 会在这里抛（目标服务端那侧就是 400）
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("发件人: 带\"引号\"的人\n备注: 第一行\n第二行", obj.getValue("meta").jsonPrimitive.content)
        assertEquals("9\"9", obj.getValue("code").jsonPrimitive.content)
        assertEquals("载荷不能变成请求结构", setOf("meta", "code"), obj.keys)
    }

    /** `{{level}}` 与 `{{level_label}}` 是两个独立占位符 —— 前者不能把后者吃掉半截。 */
    @Test
    fun `level 与 level_label 各自替换互不干扰`() {
        val body = WebhookDelivery.renderBody(
            """{"a":"{{level}}","b":"{{level_label}}"}""",
            event(level = NotifyLevel.WARNING),
            json = true
        )
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("warning", obj.getValue("a").jsonPrimitive.content)
        assertEquals("警告", obj.getValue("b").jsonPrimitive.content)
    }


    /**
     * **先替换进去的内容不会被二次展开**（一遍正则扫描，不是逐个 `replace`）。
     *
     * 短信正文里出现 `{{highlight}}` 完全正常（用户转发的短信内容就可能长这样）。
     * 逐个 `replace` 的实现会先把正文替换进去、再在同一段文本上处理 `highlight`，
     * 于是通知里那句"验证码 {{highlight}} 请勿转发"被展开成真的验证码 ——
     * 一条**由通知内容决定输出**的注入，而且不会报错。
     */
    @Test
    fun `正文里带占位符不会被二次展开`() {
        val body = WebhookDelivery.renderBody(
            """{"message":"{{message}}","code":"{{highlight}}"}""",
            event(body = "验证码 {{highlight}} 请勿转发", highlight = "482913"),
            json = true
        )
        val obj = Json.parseToJsonElement(body).jsonObject

        assertEquals("验证码 {{highlight}} 请勿转发", obj.getValue("message").jsonPrimitive.content)
        assertEquals("模板里那个占位符自己还是要替换的", "482913", obj.getValue("code").jsonPrimitive.content)
    }

    /** header 值同理（同一遍扫描）：标题里带占位符不该被后面的取值填掉。 */
    @Test
    fun `header 值里带占位符不会被二次展开`() {
        val rendered = WebhookDelivery.renderHeaders(
            mapOf("Title" to "{{title}}"),
            event(title = "有码 {{highlight}}", highlight = "9527")
        )
        assertEquals("有码 {{highlight}}", rendered.getValue("Title"))
    }

    /**
     * 认不出的占位符**原样留下**，不是替换成空串。
     *
     * 模板里打错一个名字（`{{tilte}}`）时用户在收到的通知里看得见那个错字，
     * 而换成空串只会让他以为"这个取值没了"，去查一个不存在的取值问题。
     */
    @Test
    fun `认不出的占位符原样留下`() {
        val template = """{"a":"{{tilte}}","b":"{{title}}"}"""
        val obj = Json.parseToJsonElement(
            WebhookDelivery.renderBody(template, event(title = "标题"), json = true)
        ).jsonObject

        assertEquals("{{tilte}}", obj.getValue("a").jsonPrimitive.content)
        assertEquals("标题", obj.getValue("b").jsonPrimitive.content)
    }

    /** `{{message}}` 取的是 [NotifyEvent.body]（不是 `pushTitle` 那类字段），级别取线上小写口径。 */
    @Test
    fun `message 取 event 的 body、level 取小写线上口径`() {


        val values = WebhookDelivery.placeholderValues(
            event(title = "标题", body = "正文", level = NotifyLevel.CRITICAL)
        )
        assertEquals("标题", values.getValue("title"))
        assertEquals("正文", values.getValue("message"))
        assertEquals("critical", values.getValue("level"))
    }

    // ══════════ JSON 注入防线（安全回归线） ══════════

    /**
     * **最重要的一条**：正文里的引号 / 反斜杠 / 换行 / 制表符 / 整段 JSON 都不能撑破 body。
     *
     * 转义漏一类字符的表现是"某些通知发不出去"（目标服务端回 400），用户看到的只是
     * "有时候收不到"，日志里也只有一个 HTTP 400 —— 根本对不上"那条短信里有个引号"。
     * 所以这里逐字段断言**解析回来的值与原始输入完全相等**：转义只能可逆，不能丢字符。
     */
    @Test
    fun `注入载荷不会撑破 JSON body 且值逐字保留`() {
        val body = WebhookDelivery.renderBody(
            WebhookPreset.CUSTOM.defaultBody,
            event(title = NASTY_TITLE, body = NASTY_MESSAGE),
            json = true
        )

        // 非法 JSON 会在这里抛（目标服务端那侧就是 400）
        val obj = Json.parseToJsonElement(body).jsonObject

        assertEquals(NASTY_TITLE, obj.getValue("title").jsonPrimitive.content)
        assertEquals(NASTY_MESSAGE, obj.getValue("message").jsonPrimitive.content)
        // 载荷里那段 `"},"injected":"` 不能变成请求结构：键集必须还是模板里的六个
        assertEquals(
            setOf("title", "message", "level", "scene", "type", "time"),
            obj.keys
        )
    }

    /**
     * 逐个 JSON 类预设都过一遍同样的载荷 —— 注入防线是**渲染函数**的性质，
     * 但模板形状各不相同（嵌套对象、`\n` 字面量），"在 CUSTOM 上安全"不等于"在飞书上安全"。
     *
     * 用 `entries` 遍历：将来加预设会自动进这条用例，加错了会红。
     */
    @Test
    fun `每个 JSON 类预设在注入载荷下都仍是合法 JSON`() {
        val ev = event(title = NASTY_TITLE, body = NASTY_MESSAGE)
        val jsonPresets = WebhookPreset.entries.filter { WebhookDelivery.isJson(it.defaultContentType) }
        assertEquals("JSON 类预设的个数变了，先确认新预设是否也该走转义分支", 6, jsonPresets.size)

        for (preset in jsonPresets) {
            val body = WebhookDelivery.renderBody(preset.defaultBody, ev, json = true)
            val parsed = try {
                Json.parseToJsonElement(body)
            } catch (e: Exception) {
                throw AssertionError("${preset.name} 的默认模板渲染出非法 JSON：$body", e)
            }
            val leaves = stringLeaves(parsed)
            assertTrue(
                "${preset.name} 渲染后标题没有逐字保留（转义把字符吃掉了）：$leaves",
                leaves.any { it.contains(NASTY_TITLE) }
            )
            assertTrue(
                "${preset.name} 渲染后正文没有逐字保留（转义把字符吃掉了）：$leaves",
                leaves.any { it.contains(NASTY_MESSAGE) }
            )
        }
    }

    /**
     * 企业微信 / 飞书的模板里有一个 `\n` 字面量（JSON 转义的换行），
     * 值又自带真换行 —— 两者混在一个字符串里仍然要解析成"标题 + 换行 + 正文"。
     */
    @Test
    fun `模板自带的转义换行与值里的真换行不会互相污染`() {
        val ev = event(title = "标\"题", body = "第一行\n第二行")

        val wecom = Json.parseToJsonElement(
            WebhookDelivery.renderBody(WebhookPreset.WECOM.defaultBody, ev, json = true)
        ).jsonObject
        assertEquals(
            "标\"题\n第一行\n第二行",
            wecom.getValue("text").jsonObject.getValue("content").jsonPrimitive.content
        )

        val feishu = Json.parseToJsonElement(
            WebhookDelivery.renderBody(WebhookPreset.FEISHU.defaultBody, ev, json = true)
        ).jsonObject
        assertEquals(
            "标\"题\n第一行\n第二行",
            feishu.getValue("content").jsonObject.getValue("text").jsonPrimitive.content
        )
    }

    /**
     * header 值里的折行字符必须被抹掉：CR/LF 会被下游解析成**额外的头部**（header 注入），
     * 而标题恰恰是最容易带换行的字段（从短信正文抓出来的）。
     */
    @Test
    fun `header 值里的换行被抹成空格（防 header 注入）`() {
        val rendered = WebhookDelivery.renderHeaders(
            mapOf("Title" to "{{title}}", "Priority" to "default", "Tags" to "{{level}}"),
            event(title = "行一\r\n行二")
        )

        assertEquals("行一  行二", rendered.getValue("Title"))
        // 固定值的头部不该被动过，占位符头部照常替换
        assertEquals("default", rendered.getValue("Priority"))
        assertEquals("warning", rendered.getValue("Tags"))
    }

    /** header 值**不做 JSON 转义**：那是 body 的规则，套到 header 上用户会收到 `\"` 这种字面量。 */
    @Test
    fun `header 值不做 JSON 转义`() {
        val rendered = WebhookDelivery.renderHeaders(
            mapOf("Title" to "{{title}}"), event(title = "带\"引号\"和\\反斜杠")
        )
        assertEquals("带\"引号\"和\\反斜杠", rendered.getValue("Title"))
    }

    // ══════════ Content-Type → 走不走转义 ══════════

    @Test
    fun `isJson 认 Content-Type 而不认模板长相`() {
        assertTrue(WebhookDelivery.isJson(JSON_CONTENT_TYPE))
        assertTrue("大小写不该影响判定", WebhookDelivery.isJson("Application/JSON"))
        assertTrue(WebhookDelivery.isJson("application/json"))
        assertFalse(WebhookDelivery.isJson(TEXT_CONTENT_TYPE))
        assertFalse(WebhookDelivery.isJson("text/plain"))
    }

    // ══════════ 预设默认模板 ══════════

    /**
     * 遍历**每一个**预设：渲染后不能留占位符、不能是空 body，JSON 类必须可解析。
     *
     * 刻意用遍历而不是逐个手写 —— 新增预设时如果模板写坏了（少一个引号、字段名打错），
     * 表现是"选了那个预设就永远收不到通知"，而这条用例会直接红。
     */
    @Test
    fun `每个预设的默认模板渲染后都是可用的 body（新增预设自动纳入）`() {
        val ev = event(title = NASTY_TITLE, body = NASTY_MESSAGE)

        for (preset in WebhookPreset.entries) {
            val json = WebhookDelivery.isJson(preset.defaultContentType)
            val body = WebhookDelivery.renderBody(preset.defaultBody, ev, json)

            assertFalse("${preset.name} 渲染后仍有未替换的占位符：$body", body.contains("{{"))
            assertTrue("${preset.name} 渲染出了空 body", body.isNotBlank())

            if (json) {
                try {
                    Json.parseToJsonElement(body)
                } catch (e: Exception) {
                    throw AssertionError("${preset.name} 的默认模板渲染出非法 JSON：$body", e)
                }
            } else {
                // 纯文本 body 不能被 JSON 转义，否则用户收到的正文里是 `\n` 字面量
                assertTrue("${preset.name} 是纯文本模板，正文该原样出现：$body", body.contains(NASTY_MESSAGE))
                assertFalse("${preset.name} 的纯文本 body 里出现了转义字面量：$body", body.contains("\\n"))
            }
        }
    }

    /**
     * ntfy 是唯一的纯文本预设，单独钉一条：body 就是正文原文。
     *
     * 走了转义分支的话用户在手机上看到的是 `第一行\n第二行` 这一串字面量 ——
     * 通知照样送达，所以没有任何报错，只有"显示很难看"。
     */
    @Test
    fun `ntfy 的纯文本 body 是正文原文（不走 JSON 转义）`() {
        val preset = WebhookPreset.NTFY
        assertFalse("ntfy 的 Content-Type 变成 JSON 了？", WebhookDelivery.isJson(preset.defaultContentType))

        val body = WebhookDelivery.renderBody(
            preset.defaultBody,
            event(body = "第一行\n第二行带\"引号\""),
            json = WebhookDelivery.isJson(preset.defaultContentType)
        )
        assertEquals("第一行\n第二行带\"引号\"", body)
    }

    // ══════════ 预设的「用户要填的那一样东西」 ══════════

    /**
     * **每个 `secretTarget != NONE` 的预设，它的 `secretMarker` 真的出现在对应的默认值里。**
     *
     * 这张 marker 表与默认 url / body 是两处手写的东西：改了默认 URL 却忘了改 marker，
     * 客户端就会把用户填的密钥替换到一个不存在的标记上 —— 输入框填了、配置也存下了，
     * 界面上完全看不出原因，表现只是"一条通知都收不到"。
     */
    @Test
    fun `预设的 secretMarker 真的出现在对应的默认值里`() {
        assertEquals(
            "这些预设的 secretMarker 与默认值对不上",
            emptyList<WebhookPreset>(),
            WebhookPreset.secretMarkerMismatches()
        )
    }

    /**
     * 三个字段**同进同出**：声明了位置就必须有标签与标记，声明 NONE 就三项皆空。
     *
     * 半份元数据比没有更糟 —— 客户端会渲染出一个没有标记可替换的输入框，
     * 用户填进去的值哪儿都没去。
     */
    @Test
    fun `secret 元数据三项同进同出`() {
        for (p in WebhookPreset.entries) {
            if (p.secretTarget == SecretTarget.NONE) {
                assertEquals("${p.name} 声明了 NONE 却带着标签", "", p.secretLabel)
                assertEquals("${p.name} 声明了 NONE 却带着标记", "", p.secretMarker)
            } else {
                assertTrue("${p.name} 缺少输入框标签", p.secretLabel.isNotBlank())
                assertTrue("${p.name} 缺少默认值里的标记", p.secretMarker.isNotBlank())
                // 标记必须是尖括号形式：isConfigured 靠裸 `<` 判"还没填完"
                assertTrue(
                    "${p.name} 的标记 ${p.secretMarker} 不是 <...> 形式，isConfigured 挡不住未填完的配置",
                    p.secretMarker.startsWith("<") && p.secretMarker.endsWith(">")
                )
            }
        }
    }

    /** 只有 PushPlus 把密钥放在**请求体**里 —— 其余六个都在 URL（CUSTOM 无）。 */
    @Test
    fun `只有 PushPlus 的密钥在请求体里`() {
        assertEquals(SecretTarget.NONE, WebhookPreset.CUSTOM.secretTarget)
        assertEquals(SecretTarget.BODY, WebhookPreset.PUSHPLUS.secretTarget)
        assertEquals(
            listOf(WebhookPreset.PUSHPLUS),
            WebhookPreset.entries.filter { it.secretTarget == SecretTarget.BODY }
        )
        assertEquals(
            "URL 档的预设个数变了，先确认新预设的密钥到底在哪一处",
            5,
            WebhookPreset.entries.count { it.secretTarget == SecretTarget.URL }
        )
    }

    /** REST 回给客户端的是小写口径（`secret_target`: none / url / body）。 */
    @Test
    fun `secretTarget 的线上口径是小写`() {
        assertEquals("none", SecretTarget.NONE.wireName)
        assertEquals("url", SecretTarget.URL.wireName)
        assertEquals("body", SecretTarget.BODY.wireName)
    }

    /** PushPlus 的默认地址是 https（官方站点就是 https，别把默认值指到明文链路上）。 */
    @Test
    fun `PushPlus 默认地址走 https`() {
        assertEquals("https://www.pushplus.plus/send", WebhookPreset.PUSHPLUS.defaultUrl)
    }

    // ══════════ 配置是否齐全 ══════════


    private fun cfg(
        enabled: Boolean = true,
        url: String = "https://api.day.app/abcdef",
        scenes: Set<String> = setOf(NotifyScenes.ALERT)
    ) = WebhookConfig(enabled = enabled, url = url, scenes = scenes)

    /** 开关关着就是不投 —— 别的都填好了也一样（否则"关掉了还在发"）。 */
    @Test
    fun `开关关闭时判定未配置齐全`() {
        assertFalse(WebhookDelivery.isConfigured(cfg(enabled = false)))
    }

    @Test
    fun `URL 为空时判定未配置齐全`() {
        assertFalse(WebhookDelivery.isConfigured(cfg(url = "")))
        assertFalse(WebhookDelivery.isConfigured(cfg(url = "   ")))
    }

    /**
     * 非 http(s) 的 scheme 一律拒：URL 是用户填的，放开 scheme 等于允许
     * `file://` / `javascript:` 这类东西出现在一个"往外发通知"的配置项里。
     */
    @Test
    fun `非 http 或 https 的 URL 判定未配置齐全`() {
        assertFalse("ftp", WebhookDelivery.isConfigured(cfg(url = "ftp://example.com/hook")))
        assertFalse("javascript", WebhookDelivery.isConfigured(cfg(url = "javascript:alert(1)")))
        assertFalse("没有 scheme", WebhookDelivery.isConfigured(cfg(url = "api.day.app/abcdef")))
        assertFalse("协议相对", WebhookDelivery.isConfigured(cfg(url = "//api.day.app/abcdef")))
    }

    @Test
    fun `http 与 https 且已启用时判定配置齐全`() {
        assertTrue(WebhookDelivery.isConfigured(cfg(url = "http://www.pushplus.plus/send")))
        assertTrue(WebhookDelivery.isConfigured(cfg(url = "https://api.day.app/abcdef")))
        assertTrue("大写 scheme 也该认", WebhookDelivery.isConfigured(cfg(url = "HTTPS://api.day.app/abcdef")))
    }

    /**
     * 预设里的 `<占位>` 没换掉 = 还没配完。
     *
     * 直接发出去会打到一个不存在的 device key 上，而用户看到的只是"配好了但收不到"。
     */
    @Test
    fun `URL 里留着预设的尖括号占位时判定未配置齐全`() {
        for (preset in WebhookPreset.entries) {
            val expected = !preset.defaultUrl.contains("<") && preset.defaultUrl.isNotBlank()
            assertEquals(
                "${preset.name} 的默认 URL（${preset.defaultUrl}）配置齐全判定不对",
                expected,
                WebhookDelivery.isConfigured(cfg(url = preset.defaultUrl))
            )
        }
    }

    // ══════════ 状态码 → 投递结果 ══════════

    private fun outcomeOf(code: Int): DeliveryOutcome = WebhookDelivery.classify(code, "")

    private fun retryableOf(code: Int): Boolean {
        val outcome = outcomeOf(code)
        assertTrue("HTTP $code 应该判成 Failed，实际是 $outcome", outcome is DeliveryOutcome.Failed)
        return (outcome as DeliveryOutcome.Failed).retryable
    }

    /** 2xx 就是 Webhook 的"送达确认"（`TrafficAutoOffGuard` 的通报到位判定读它）。 */
    @Test
    fun `2xx 是送达确认`() {
        assertEquals(DeliveryOutcome.Sent, outcomeOf(200))
        assertEquals(DeliveryOutcome.Sent, outcomeOf(201))
        assertEquals(DeliveryOutcome.Sent, outcomeOf(204))
        assertEquals(DeliveryOutcome.Sent, outcomeOf(299))
    }

    /** 限流与服务端故障都是"换个时间就可能成功"的，重试有意义。 */
    @Test
    fun `429 与 5xx 可重试`() {
        assertTrue("429 限流", retryableOf(429))
        assertTrue("500", retryableOf(500))
        assertTrue("502", retryableOf(502))
        assertTrue("503", retryableOf(503))
        assertTrue(WebhookDelivery.isRetryableStatus(429))
        assertTrue(WebhookDelivery.isRetryableStatus(500))
        assertTrue(WebhookDelivery.isRetryableStatus(599))
    }

    /**
     * 目标明确拒收的 4xx 不重试：URL 写错、token 失效、body 被拒，重试改不了结果，
     * 只会对着一个永久错误连打三次（口径与 `MailDelivery.isRetryable` 对授权失败一致）。
     */
    @Test
    fun `明确拒收的 4xx 不可重试`() {
        for (code in listOf(400, 401, 403, 404, 422)) {
            assertFalse("HTTP $code 不该重试", retryableOf(code))
            assertFalse("HTTP $code 不该进可重试状态码集", WebhookDelivery.isRetryableStatus(code))
        }
    }

    /** 3xx 不跟随也不重试，错误里要提示"把 URL 改成最终地址"，否则用户完全不知道发生了什么。 */
    @Test
    fun `3xx 不可重试且错误里说清没跟随重定向`() {
        val outcome = outcomeOf(302) as DeliveryOutcome.Failed
        assertFalse(outcome.retryable)
        assertTrue("错误文案要提到重定向，实际=${outcome.error}", outcome.error.contains("重定向"))
        assertTrue("错误文案要带状态码，实际=${outcome.error}", outcome.error.contains("302"))
    }

    /** 目标服务端的响应体要进错误文案：`{"code":40001,"msg":"invalid token"}` 是排查时唯一有用的信息。 */
    @Test
    fun `失败文案里带上目标的响应体摘要`() {
        val body = """{"code":40001,"msg":"invalid token"}"""
        val outcome = WebhookDelivery.classify(401, body) as DeliveryOutcome.Failed
        assertEquals("""HTTP 401 {"code":40001,"msg":"invalid token"}""", outcome.error)
    }

    // ══════════ 异常 → 可重试 ══════════

    /** 弱网设备上超时 / 连不上 / DNS / TLS 是常态，一次瞬时失败不该让通知永久丢失。 */
    @Test
    fun `网络层异常可重试`() {
        assertTrue("超时", WebhookDelivery.isRetryableException(SocketTimeoutException("Read timed out")))
        assertTrue("连不上", WebhookDelivery.isRetryableException(ConnectException("Connection refused")))
        assertTrue("DNS", WebhookDelivery.isRetryableException(UnknownHostException("api.day.app")))
        assertTrue("TLS", WebhookDelivery.isRetryableException(SSLException("handshake failed")))
        assertTrue("其余 IO", WebhookDelivery.isRetryableException(IOException("unexpected end of stream")))
    }

    /** 包在外层异常里的根因也要认出来（ktor 常把真正的原因挂在 cause 上）。 */
    @Test
    fun `异常链里的网络根因也算可重试`() {
        val wrapped = IllegalStateException("请求失败", SocketTimeoutException("Read timed out"))
        assertTrue(WebhookDelivery.isRetryableException(wrapped))
    }

    /**
     * 拿不准一律不可重试（与 `MailDelivery.isRetryable` 同口径）：
     * 宁可少发一次，也不要把一个永久错误放大成三次投递。
     */
    @Test
    fun `无法识别的异常按不可重试`() {
        assertFalse(WebhookDelivery.isRetryableException(IllegalStateException("something odd")))
        assertFalse(WebhookDelivery.isRetryableException(RuntimeException("no cause attached")))
    }

    /**
     * 配置错（scheme / method 不合法）不是链路抖动，重试三次结果一样。
     *
     * 注意判定是**从最外层往里逐层看、命中即返回**：`HttpNotifier.send` 的
     * `require(isAllowedUrl(url))` 抛出来的就是最外层那个 `IllegalArgumentException`，
     * 所以就算它裹着一个超时，也仍然不重试。
     */
    @Test
    fun `配置类异常不可重试`() {
        assertFalse(WebhookDelivery.isRetryableException(IllegalArgumentException("只允许 http/https 的 URL")))
        assertFalse(
            WebhookDelivery.isRetryableException(
                IllegalArgumentException("不支持的 method: DELETE", SocketTimeoutException("Read timed out"))
            )
        )
    }

    /** 失败原因要能看出类名与根因（写进 `mail_send_records.error` 后靠它排查）。 */
    @Test
    fun `失败描述保留根因`() {
        val text = WebhookDelivery.describeFailure(
            IllegalStateException("请求失败", SocketTimeoutException("Read timed out"))
        )
        assertTrue(text, text.contains("IllegalStateException"))
        assertTrue(text, text.contains("SocketTimeoutException"))
        assertTrue(text, text.contains("Read timed out"))
    }

    // ══════════ 脱敏 ══════════

    /**
     * 投递记录与日志里只留 scheme + host：Bark 的 device key、Server 酱的 SENDKEY、
     * 企业微信的 key 全都在 path / query 里 —— 那就是密钥，写进用户可导出的记录等于泄漏。
     */
    @Test
    fun `originOf 丢掉 path 与 query（凭据都在那里）`() {
        assertEquals("https://api.day.app", WebhookDelivery.originOf("https://api.day.app/secret_key"))
        assertEquals(
            "https://qyapi.weixin.qq.com",
            WebhookDelivery.originOf("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=SECRET")
        )
        assertEquals("http://192.168.0.2:8080", WebhookDelivery.originOf("http://192.168.0.2:8080/hook#frag"))
        assertEquals("https://ntfy.sh", WebhookDelivery.originOf("https://ntfy.sh"))
        assertEquals("认不出 scheme 时不该回半截 URL", "", WebhookDelivery.originOf("api.day.app/x"))
    }

    /** 凭据类头部的值不进日志，名字要留着（"我配了哪个头"必须看得见）。 */
    @Test
    fun `凭据类 header 的值在日志里被脱敏`() {
        val masked = WebhookDelivery.maskHeaders(
            mapOf(
                "Authorization" to "Bearer abc123",
                "X-Auth-Token" to "t0ken",
                "X-Api-Key" to "k",
                "Cookie" to "sid=1",
                "Content-Language" to "zh-CN"
            )
        )
        assertEquals("***(13)", masked.getValue("Authorization"))
        assertEquals("***(5)", masked.getValue("X-Auth-Token"))
        assertEquals("***(1)", masked.getValue("X-Api-Key"))
        assertEquals("***(5)", masked.getValue("Cookie"))
        // 不敏感的头部原样保留，否则日志里什么都看不出来
        assertEquals("zh-CN", masked.getValue("Content-Language"))
    }

    /**
     * **最重要的一条脱敏回归线**：超时异常的 message 里那条 URL 不能带着 path 落地。
     *
     * Ktor 的 `HttpRequestTimeoutException.message` 形如
     * `Request timeout has expired [url=…, request_timeout=…]`，而 [WebhookDelivery.describeFailure]
     * 原样逐层拼 `message`。那条字符串同时进三处：`/test` 响应、`mail_send_records.error`
     * （用户可导出）、`AppLogger.e` —— Bark 的 device key 与 Server 酱的 SENDKEY 就在 path 里。
     * 也就是说 [WebhookDelivery.originOf] 建立的"整条 URL 不能落地"这条不变量，
     * 在**最需要它的失败路径上**恰好失效。
     */
    @Test
    fun `超时失败原因里不会留下 URL 的 path（凭据在那里）`() {
        val raw = "Request timeout has expired [url=$BARK_URL, request_timeout=10000 ms]"
        val error = WebhookDelivery.sanitize(
            WebhookDelivery.describeFailure(IllegalStateException(raw)), BARK_URL
        )

        assertFalse("device key 落地了：$error", error.contains(BARK_KEY))
        assertFalse("path 段落地了：$error", error.contains("/notify"))
        assertTrue("投到哪个主机还是要看得见：$error", error.contains("https://api.day.app"))
        assertTrue("超时时长要留着（排查用，不是凭据）：$error", error.contains("10000"))
        assertTrue("异常类型要留着：$error", error.contains("IllegalStateException"))
    }

    /**
     * 客户端拼出来的 URL 与配置里那条**不逐字相同**时也要脱敏（大写 scheme、多一个尾斜杠）。
     *
     * 所以不能只做一次字符串等值替换 —— 那种实现在这条用例上会原样放行整条 URL。
     */
    @Test
    fun `与配置不完全一致的 URL 同样被脱敏`() {
        val configured = "https://sctapi.ftqq.com/SENDKEY123.send"
        val raw = "Connect timed out [url=HTTPS://sctapi.ftqq.com/SENDKEY123.send/, request_timeout=1000 ms]"

        val out = WebhookDelivery.sanitize(raw, configured)

        assertFalse("SENDKEY 落地了：$out", out.contains("SENDKEY123"))
        assertTrue(out, out.contains("sctapi.ftqq.com"))
    }

    /** `url=` 后面不是 http(s) 开头（认不出 scheme）时整段隐去 —— 那里面可能就是密钥。 */
    @Test
    fun `认不出 scheme 的 url 赋值片段整段隐去`() {
        val out = WebhookDelivery.sanitize("send failed [url=api.day.app/AbCdEf123456]", "")

        assertFalse(out, out.contains("AbCdEf123456"))
        assertTrue(out, out.contains("***"))
    }
}

