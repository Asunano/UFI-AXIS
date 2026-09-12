package com.ufi_axis_core.api.routes

import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.controller.sms.SmsController
import com.ufi_axis_core.controller.sms.SmsFilter
import com.ufi_axis_core.controller.sms.SmsRuleStore
import com.ufi_axis_core.core.database.SmsBlockedLog
import com.ufi_axis_core.core.database.SmsRule
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `/api/sms/rules` 与 `/api/sms/blocked` 的路由层测试。
 *
 * 三件最容易静默出错的事：
 *
 * 1. **路由被 `/sms/{id}` 抢走**。`RootSmsRoutes` 里 `get("/{id}")` 注册在 `get("/rules")`
 *    之前，靠的是 Ktor「常量段评分高于参数段」这条规则。这不是显式代码，一旦 Ktor 换版本
 *    或有人调整注册顺序，表现是 `GET /api/sms/rules` 回 400 invalid id —— 看起来像客户端写错了路径。
 * 2. **入参校验漏掉空 pattern**。`contains ""` 会命中每一条短信，等于一键静默全部消息。
 * 3. **PUT 变成全量覆盖**。客户端只想切 `enabled`，结果把 pattern/scope 重置成默认值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmsRuleRoutesTest {

    private lateinit var smsController: SmsController
    private lateinit var store: SmsRuleStore

    private val sampleRule = SmsRule(
        id = 3L, enabled = true, scope = SmsFilter.SCOPE_BODY,
        match_type = SmsFilter.MATCH_CONTAINS, pattern = "中奖", note = "营销",
        hit_count = 12, last_hit_at = 999L, created_at = 100L
    )

    @Before
    fun setup() {
        smsController = mockk(relaxed = true)
        store = mockk(relaxed = true)
        coEvery { store.listRules() } returns listOf(sampleRule)
        coEvery { store.findRule(3L) } returns sampleRule
        coEvery { store.findRule(404L) } returns null
        coEvery { store.addRule(any()) } returns 7L
        coEvery { store.countBlocked() } returns 1
        coEvery { store.deleteRule(any()) } returns 1
        coEvery { store.deleteBlocked(any()) } returns 1
        coEvery { store.listBlocked(any(), any(), any()) } returns listOf(
            SmsBlockedLog(
                id = 8L, msg_id = 1024L, sender = "10086", snippet = "恭喜您中奖了",
                rule_id = 3L, rule_pattern = "中奖", rule_scope = SmsFilter.SCOPE_BODY,
                rule_match = SmsFilter.MATCH_CONTAINS, blocked_path = "push,vc", blocked_at = 5000L
            )
        )
    }

    private fun withRoutes(
        ruleStore: SmsRuleStore? = store,
        block: suspend (io.ktor.client.HttpClient) -> Unit
    ) = testApplication {
        val routes = RootSmsRoutes(smsController, null, ruleStore)
        application {
            install(ContentNegotiation) { json() }
            routing { routes.register(this) }
        }
        block(client)
    }

    private suspend fun HttpResponse.json(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    // ══════════ 路由解析：常量段不能被 /{id} 抢走 ══════════

    @Test
    fun `GET rules is not swallowed by the id route`() = withRoutes { client ->
        val res = client.get("/sms/rules")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.json()
        assertEquals(1, body["count"]!!.jsonPrimitive.int)
        assertEquals("中奖", body["rules"]!!.jsonArray[0].jsonObject["pattern"]!!.jsonPrimitive.content)
        // 枚举随响应下发，客户端不必硬编码第二份
        assertEquals(3, body["scopes"]!!.jsonArray.size)
        assertEquals(4, body["match_types"]!!.jsonArray.size)
    }

    @Test
    fun `GET blocked is not swallowed by the id route`() = withRoutes { client ->
        val res = client.get("/sms/blocked")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.json()
        assertEquals(1, body["count"]!!.jsonPrimitive.int)
        assertEquals(1, body["total"]!!.jsonPrimitive.int)
        assertEquals("push,vc", body["records"]!!.jsonArray[0].jsonObject["blocked_path"]!!.jsonPrimitive.content)
    }

    /** 游标是 keyset（cursor_ts + cursor_id），**不是 offset**。 */
    @Test
    fun `GET blocked passes keyset cursor through`() = withRoutes { client ->
        client.get("/sms/blocked?limit=20&cursor_ts=5000&cursor_id=8")
        coVerify { store.listBlocked(5000L, 8L, 20) }
    }

    @Test
    fun `GET blocked clamps limit`() = withRoutes { client ->
        client.get("/sms/blocked?limit=9999")
        coVerify { store.listBlocked(null, null, 200) }
    }

    // ══════════ 规则写入校验 ══════════

    @Test
    fun `POST rejects blank pattern`() = withRoutes { client ->
        val res = client.post("/sms/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"pattern":"   ","scope":"body","match_type":"contains"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(ErrorCode.BAD_REQUEST, res.json()["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `POST rejects scope and match_type outside the enums`() = withRoutes { client ->
        val badScope = client.post("/sms/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"pattern":"中奖","scope":"subject"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badScope.status)

        val badMatch = client.post("/sms/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"pattern":"中奖","match_type":"regex"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badMatch.status)
    }

    @Test
    fun `POST applies defaults and trims the pattern`() = withRoutes { client ->
        val captured = slot<SmsRule>()
        coEvery { store.addRule(capture(captured)) } returns 7L

        val res = client.post("/sms/rules") {
            contentType(ContentType.Application.Json)
            setBody("""{"pattern":"  中奖  "}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(7L, res.json()["id"]!!.jsonPrimitive.long)

        val rule = captured.captured
        assertEquals("中奖", rule.pattern)
        // 新规则默认只匹配正文（决策 2）
        assertEquals(SmsFilter.SCOPE_BODY, rule.scope)
        assertEquals(SmsFilter.MATCH_CONTAINS, rule.match_type)
        assertTrue(rule.enabled)
    }

    // ══════════ PUT 字段级合并 ══════════

    @Test
    fun `PUT with only enabled keeps every other field`() = withRoutes { client ->
        val captured = slot<SmsRule>()
        coEvery { store.updateRule(capture(captured)) } returns Unit

        val res = client.put("/sms/rules/3") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)

        val rule = captured.captured
        assertEquals(3L, rule.id)
        assertTrue(!rule.enabled)
        assertEquals("中奖", rule.pattern)
        assertEquals(SmsFilter.SCOPE_BODY, rule.scope)
        assertEquals(SmsFilter.MATCH_CONTAINS, rule.match_type)
        assertEquals("营销", rule.note)
        // 统计字段不该被写入路径重置
        assertEquals(12, rule.hit_count)
        assertEquals(100L, rule.created_at)
    }

    @Test
    fun `PUT on a missing id returns 404`() = withRoutes { client ->
        val res = client.put("/sms/rules/404") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertEquals(ErrorCode.NOT_FOUND, res.json()["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `PUT still validates merged values`() = withRoutes { client ->
        val res = client.put("/sms/rules/3") {
            contentType(ContentType.Application.Json)
            setBody("""{"pattern":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    // ══════════ 删除 ══════════

    @Test
    fun `DELETE rule reports deleted count and still succeeds when already gone`() = withRoutes { client ->
        coEvery { store.deleteRule(3L) } returns 0
        val res = client.delete("/sms/rules/3")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.json()
        assertTrue(body["success"]!!.jsonPrimitive.boolean)
        assertEquals(0, body["deleted"]!!.jsonPrimitive.int)
    }

    @Test
    fun `DELETE blocked clears everything`() = withRoutes { client ->
        val res = client.delete("/sms/blocked")
        assertEquals(HttpStatusCode.OK, res.status)
        coVerify { store.clearBlocked() }
    }

    @Test
    fun `DELETE blocked by id`() = withRoutes { client ->
        val res = client.delete("/sms/blocked/8")
        assertEquals(HttpStatusCode.OK, res.status)
        coVerify { store.deleteBlocked(8L) }
    }

    @Test
    fun `invalid ids are rejected before touching the store`() = withRoutes { client ->
        assertEquals(HttpStatusCode.BadRequest, client.delete("/sms/rules/0").status)
        assertEquals(HttpStatusCode.BadRequest, client.delete("/sms/blocked/abc").status)
    }

    // ══════════ 降级装配 ══════════

    /** 没有 Room DAO 时回 503 而不是 404：客户端要能分清「能力缺失」和「路径写错」。 */
    @Test
    fun `filter endpoints report 503 when the module is not wired`() = withRoutes(ruleStore = null) { client ->
        for (res in listOf(client.get("/sms/rules"), client.get("/sms/blocked"), client.delete("/sms/blocked"))) {
            assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
            assertEquals(ErrorCode.UNAVAILABLE, res.json()["code"]!!.jsonPrimitive.content)
        }
    }
}
