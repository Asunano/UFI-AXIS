package com.ufi_axis_core.api.routes

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.controller.notify.WebhookChannel
import com.ufi_axis_core.controller.notify.WebhookConfig
import com.ufi_axis_core.controller.notify.WebhookConfigStore
import com.ufi_axis_core.controller.notify.WebhookPreset
import com.ufi_axis_core.notify.DeliveryReport
import com.ufi_axis_core.notify.NotifyScenes
import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `PUT /api/notify/webhook/config` 的**预设语义**：配置按预设各存一份之后，
 * "切换"与"切换并填好密钥"分别该写哪些键。
 *
 * 这几条都不会以报错的形式出现，只会表现成用户那句「配置怎么又没了」：
 * 1. 只带 `preset` 的 PUT 如果顺手把 per-preset 字段也写一遍，就还是"切一次覆盖一次"；
 * 2. 同时带 `preset` 与字段的 PUT 如果拿**当前**预设那一份当合并的底，
 *    旧预设的 url / 模板就会被 copy 进新预设的槽位（用户切到 Bark、填好 device key，
 *    body 模板却还是企业微信那套 —— Bark 收到一份它不认识的 JSON）；
 * 3. 目标预设自己上次存的字段如果没被当成底，patch 没提到的那几个会退回默认值。
 *
 * 走真 HTTP + 真 prefs（Robolectric）而不是直接调 `merge`：要钉住的恰恰是
 * "**合并的底选哪一份**"这个决定，而它在 `merge` 的外面（路由里）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebhookConfigRoutesTest {

    private lateinit var store: WebhookConfigStore
    private lateinit var channel: WebhookChannel
    private lateinit var prefs: SharedPreferences

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        store = WebhookConfigStore(context)
        channel = WebhookChannel(store)
    }

    private companion object {
        /**
         * prefs 文件名**故意手抄**（同 `WebhookConfigStoreTest`）：有用例钉的是"这次请求
         * 一个键都没写盘"，跟着实现里的常量走的话实现改名用例也改名，就什么都没测到。
         */
        const val PREFS_NAME = "notify_webhook"

        const val CUSTOM_URL = "https://my.own.hook/endpoint"
        const val CUSTOM_BODY = """{"custom":"{{message}}"}"""
        const val BARK_URL = "https://api.day.app/bark_device_key"
        const val BARK_BODY = """{"title":"{{title}}","body":"{{message}}"}"""
    }

    private fun withRoutes(block: suspend (HttpClient) -> Unit) = testApplication {
        val routes = WebhookRoutes(
            store = store,
            channel = channel,
            // PUT 用不到分发器（只有 /test 会用），给一个空报告 —— 这里不该顺手真发一条通知出去
            notifier = { DeliveryReport(emptyMap(), emptySet()) },
            gate = { true }
        )
        application {
            install(ContentNegotiation) { json() }
            routing { routes.register(this) }
        }
        block(client)
    }

    /**
     * 用序列化器拼 patch，不手写 JSON 字面量：模板值自带引号（`{"title":"{{title}}"}`），
     * 手写字符串拼接的话转义漏一处就变成 400，而用例会以"语义错"的样子红掉。
     */
    private fun patch(vararg fields: Pair<String, String>): String =
        JsonObject(fields.associate { (k, v) -> k to JsonPrimitive(v) }).toString()

    private suspend fun HttpClient.putConfig(body: String): HttpResponse =
        put("/notify/webhook/config") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.config(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("config").jsonObject

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    /** 先把"自定义"那一份配满，作为"切换前的现状"。 */
    private fun seedCustom() = store.save(
        WebhookConfig(
            enabled = true,
            preset = WebhookPreset.CUSTOM,
            url = CUSTOM_URL,
            bodyTemplate = CUSTOM_BODY,
            headers = mapOf("X-Custom" to "1"),
            scenes = setOf(NotifyScenes.ALERT)
        )
    )

    /**
     * **patch 同时带 `preset` 与 per-preset 字段 → 那些字段写到新预设那一份下。**
     *
     * 客户端一次提交"切到 Bark 并填好 device key"就是这个形状。写错预设的表现是
     * device key 落进了自定义那一栏，而 Bark 那栏还留着 `<device_key>` 占位。
     */
    @Test
    fun `PUT 同时带 preset 与字段时写到新预设那一份下`() = withRoutes { client ->
        seedCustom()

        val res = client.putConfig(
            patch("preset" to "BARK", "url" to BARK_URL, "body_template" to BARK_BODY)
        )
        assertEquals(HttpStatusCode.OK, res.status)

        val echoed = res.config()
        assertEquals("BARK", echoed.str("preset"))
        assertEquals(BARK_URL, echoed.str("url"))

        // 新预设那一份收下了这两个字段
        val bark = store.load(WebhookPreset.BARK)
        assertEquals(BARK_URL, bark.url)
        assertEquals(BARK_BODY, bark.bodyTemplate)

        // 旧预设那一份一个字都没动（这就是「切换不再清除」）
        val custom = store.load(WebhookPreset.CUSTOM)
        assertEquals(CUSTOM_URL, custom.url)
        assertEquals(CUSTOM_BODY, custom.bodyTemplate)
        assertEquals(mapOf("X-Custom" to "1"), custom.headers)
    }

    /**
     * patch 没提到的 per-preset 字段取**目标预设**那一份的现值，不是当前预设的。
     *
     * 拿当前预设当底的话，用户切到 Bark 只改了 URL，Bark 的 body 模板就会被换成
     * 自定义那套 —— Bark 收到一份它不认识的 JSON，而界面上什么都看不出来。
     */
    @Test
    fun `切换时未提到的字段取目标预设自己存的值`() = withRoutes { client ->
        // Bark 那一份先存好（模板是 Bark 自己的）
        store.save(
            WebhookConfig(
                enabled = true, preset = WebhookPreset.BARK,
                url = BARK_URL, bodyTemplate = BARK_BODY, headers = mapOf("X-Bark" to "1")
            )
        )
        // 再切回自定义并配一份完全不同的
        seedCustom()

        val newKey = "https://api.day.app/another_key"
        val res = client.putConfig(patch("preset" to "BARK", "url" to newKey))
        assertEquals(HttpStatusCode.OK, res.status)

        val bark = store.load()
        assertEquals(WebhookPreset.BARK, bark.preset)
        assertEquals(newKey, bark.url)
        assertEquals("patch 没提到 body_template，不能拿自定义那套盖过来", BARK_BODY, bark.bodyTemplate)
        assertEquals(mapOf("X-Bark" to "1"), bark.headers)
    }

    /**
     * **只带 `preset` 的 PUT = 纯切换**：顶层换一个"当前选中"，per-preset 那一份原样躺着。
     *
     * 顶层那几个（enabled / scenes / min_level / daily_limit）跨预设共享，切换不该动它们；
     * 目标预设那一份是"从未配过"的话，切完之后仍然是"从未配过"（读到的是它的默认值）。
     */
    @Test
    fun `只带 preset 的 PUT 只切换不覆盖`() = withRoutes { client ->
        seedCustom()

        val res = client.putConfig(patch("preset" to "NTFY"))
        assertEquals(HttpStatusCode.OK, res.status)

        val echoed = res.config()
        assertEquals("NTFY", echoed.str("preset"))
        // 从未配过的 ntfy → 回显它的默认值（含 <topic> 占位，configured 因此为 false）
        assertEquals(WebhookPreset.NTFY.defaultUrl, echoed.str("url"))
        assertEquals(WebhookPreset.NTFY.defaultBody, echoed.str("body_template"))
        assertFalse("URL 里还留着 <topic>，不该判成配置齐全", echoed.bool("configured"))
        // 顶层共享字段没被切换动过
        assertTrue(echoed.bool("enabled"))
        assertEquals(
            listOf(NotifyScenes.ALERT),
            echoed.getValue("scenes").jsonArray.map { it.jsonPrimitive.content }
        )

        // 自定义那一份还在，切回来一字不差
        assertEquals(CUSTOM_URL, store.load(WebhookPreset.CUSTOM).url)
        assertEquals(CUSTOM_BODY, store.load(WebhookPreset.CUSTOM).bodyTemplate)
    }

    /** 不带 `preset` 的 PUT 就是改当前那一份（旧行为不变）。 */
    @Test
    fun `不带 preset 的 PUT 改的是当前预设那一份`() = withRoutes { client ->
        seedCustom()

        val res = client.putConfig(patch("url" to "https://my.own.hook/v2"))
        assertEquals(HttpStatusCode.OK, res.status)

        assertEquals("CUSTOM", res.config().str("preset"))
        assertEquals("https://my.own.hook/v2", store.load(WebhookPreset.CUSTOM).url)
        // 同一次 patch 没提到的字段照旧保留
        assertEquals(CUSTOM_BODY, store.load(WebhookPreset.CUSTOM).bodyTemplate)
    }

    /**
     * **认不出的 `preset` 名 → 400，而且一个键都没写盘。**
     *
     * 回落 CUSTOM 的老口径在配置分层之后是有害的：客户端把名字打成 `Barkk`，用户以为切到了
     * Bark，值却静默落进 `custom.*` 那一份 —— 他看到的是"我配好的 Bark 不见了"，
     * 日志里一个异常都没有。写盘必须发生在校验之后，所以这里连 `barkk.url` 都不该出现。
     *
     * `WebhookPreset.fromName` 的回落**不动**：那是给 `load()` 读坏 prefs 用的容错。
     */
    @Test
    fun `认不出的 preset 名回 400 且什么都没写盘`() = withRoutes { client ->
        // 先配好 Bark，模拟"用户已经有一份能用的配置"
        store.save(
            WebhookConfig(
                enabled = true, preset = WebhookPreset.BARK,
                url = BARK_URL, bodyTemplate = BARK_BODY
            )
        )
        val before = prefs.all.toMap()

        val res = client.putConfig(patch("preset" to "Barkk", "url" to "https://api.day.app/typo"))

        assertEquals(HttpStatusCode.BadRequest, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertFalse(body.getValue("success").jsonPrimitive.boolean)
        val message = body.getValue("message").jsonPrimitive.content
        assertTrue("错误文案要指出是 preset 的问题：$message", message.contains("preset"))
        assertTrue("错误文案要列出合法取值：$message", message.contains("BARK"))
        assertTrue("错误文案要回显收到的值：$message", message.contains("Barkk"))

        // 一个键都没变（尤其是没有凭空多出一份 barkk.*，原来那份 Bark 也没被动）
        assertEquals("400 之后不该有任何写盘", before, prefs.all.toMap())
        assertFalse(prefs.contains("barkk.url"))
        assertEquals(BARK_URL, store.load().url)
        assertEquals(WebhookPreset.BARK, store.load().preset)
    }

    /**
     * 每个预设名都被接受，大小写不敏感。
     *
     * 与上一条是同一个判据的另一面：严起来之后不能把**合法**的名字也拒掉 ——
     * 新增预设时如果漏了什么，这条会红（遍历 `entries`，新预设自动纳入）。
     * 大小写那一格钉的是客户端可能发小写（回显值是大写，但 web 那侧的下拉曾用小写口径）。
     */
    @Test
    fun `所有预设名都被接受且大小写不敏感`() = withRoutes { client ->
        for (preset in WebhookPreset.entries) {
            val res = client.putConfig(patch("preset" to preset.name))
            assertEquals("${preset.name} 被拒了", HttpStatusCode.OK, res.status)
            assertEquals(preset.name, res.config().str("preset"))
        }
        val lower = client.putConfig(patch("preset" to WebhookPreset.BARK.name.lowercase()))
        assertEquals(HttpStatusCode.OK, lower.status)
        assertEquals("BARK", lower.config().str("preset"))
    }

    /**
     * 响应形状没变：客户端不必因为存储改了而改 DTO。
     *
     * 这几个键是 app / web 直接读的（`configured` 决定"测试"按钮能不能点，
     * `sent_today` / `quota_remaining` 是那行"今日 2/5"，`presets` 是预设表）。
     */
    @Test
    fun `响应形状不变`() = withRoutes { client ->
        seedCustom()

        val echoed = client.putConfig(patch("preset" to "BARK", "url" to BARK_URL)).config()

        for (key in listOf(
            "enabled", "preset", "url", "method", "headers", "body_template", "content_type",
            "timeout_ms", "min_level", "daily_limit", "scenes", "respect_dnd",
            "configured", "sent_today", "quota_remaining", "levels",
            "daily_limit_min", "daily_limit_max", "placeholders", "presets"
        )) {
            assertTrue("响应里少了 $key", echoed.containsKey(key))
        }
        assertEquals(WebhookPreset.entries.size, echoed.getValue("presets").jsonArray.size)
        assertEquals(0, echoed.getValue("sent_today").jsonPrimitive.content.toInt())
    }
}
