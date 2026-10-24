package com.ufi_axis_core.api.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.api.TestDeviceIdentity
import com.ufi_axis_core.api.pairing.PairedDeviceApprovalStore
import com.ufi_axis_core.api.pairing.PairingManager
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.PairedDeviceStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * 批准流程公开端点的**路径**测试（2026-10-17）。
 *
 * ## 为什么单独写这一条
 * 上线后实测到："Web 端申请验证码，显示**配对申请已过期**"。
 * 根因是 `registerPublic` 里的路径写成了 `/pairing/request-approval`，而它在
 * `HttpServer` 里被 `route("/pairing") { ... }` 包着调用 —— 于是真实路径变成
 * `/pairing/pairing/request-approval`，Web 端拿到 **404**，轮询又把任何失败当成"过期"。
 *
 * 之前的装配守卫只断言"路由必须注册"（有那行代码就绿），**没有断言真实 URL 能命中** ——
 * 这类"路径拼接错误"只有真的发一次请求才能发现。所以这里用 `route("/pairing")` 复刻
 * 生产挂法，直接打完整路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairedDeviceApprovalRoutesTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettings
    private lateinit var pairedStore: PairedDeviceStore
    private lateinit var approvalStore: PairedDeviceApprovalStore
    private lateinit var manager: PairingManager
    private lateinit var routes: PairedDeviceApprovalRoutes

    /** 2026-10-21：「新请求已登记」推送回调收到的 sid（顺序即调用顺序）。 */
    private val pushedSids = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = AppSettings(context)
        settings.resetAll()
        settings.setDevicePassword("secret123")
        // 2026-10-24：本组用例验的是"批准开启时"的登记/限速/推送；关闭策略下的 501
        // 由专门用例覆盖。不置 true 的话，新的策略闸门会让所有 request-approval 直接 501。
        settings.pairingApprovalRequired = true

        pairedStore = PairedDeviceStore(context, settings)
        pairedStore.clear()

        approvalStore = PairedDeviceApprovalStore()
        manager = PairingManager(settings, pairedStore, approvalStore = approvalStore)
        routes = PairedDeviceApprovalRoutes(
            manager,
            // 2026-10-21：推送回调只记录，不真的发 WS —— 这里验的是**触发时机**，
            // 帧数与退避在 ApprovalPushNudgerTest 里单独验。
            onApprovalPending = { pushedSids += it.sid }
        )
    }

    /** 与生产一致：公开端点被 `route("/pairing")` 包着注册（前缀由那一层加）。 */
    private fun Application.registerPublicUnderPairing() {
        install(ContentNegotiation) { json() }
        routing {
            route("/pairing") { routes.registerPublic(this) }
        }
    }

    private suspend fun HttpResponse.json(): JsonObject =
        Json.parseToJsonElement(bodyAsText()) as JsonObject

    private suspend fun HttpClient.requestApproval(sid: String, device: TestDeviceIdentity): HttpResponse =
        post("/pairing/request-approval") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"sid":"$sid","device_pubkey":"${device.publicKeySpki}","device_name":"Chrome"}"""
            )
        }

    @Test
    fun `公开端点挂在 pairing 前缀下 —— 不能出现双前缀`() = testApplication {
        application { registerPublicUnderPairing() }
        val resp = client.get("/pairing/approval-status?sid=whatever")
        assertEquals(
            "必须能命中。写成 /pairing/pairing/... 时这里是 404（线上就是这么炸的）",
            HttpStatusCode.OK,
            resp.status
        )
    }

    @Test
    fun `登记后状态 pending，机主批准后变 approved`() = testApplication {
        application { registerPublicUnderPairing() }
        val device = TestDeviceIdentity("web-browser")

        val created = client.requestApproval("sid-1", device)
        assertEquals(HttpStatusCode.OK, created.status)
        val createdBody = created.json()
        assertFalse(
            "响应里绝不能有配对码 —— 有的话请求方就能自己批准自己",
            "code" in createdBody
        )
        assertEquals("pending", createdBody["status"]?.jsonPrimitive?.content)

        val polling = client.get("/pairing/approval-status?sid=sid-1")
        assertEquals(HttpStatusCode.OK, polling.status)
        assertEquals("pending", polling.json()["status"]?.jsonPrimitive?.content)

        assertTrue("机主点允许", manager.approveDevice("sid-1"))
        val after = client.get("/pairing/approval-status?sid=sid-1")
        assertEquals("approved", after.json()["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `同一 sid 重复登记是幂等的 —— 码不变、不新建`() = testApplication {
        application { registerPublicUnderPairing() }
        val device = TestDeviceIdentity("web-browser")

        client.requestApproval("sid-1", device)
        val codeFirst = approvalStore.pendingList().single().code
        client.requestApproval("sid-1", device)

        assertEquals("仍只有一条", 1, approvalStore.pendingList().size)
        assertEquals("码不能换（否则机主手上那个就废了）", codeFirst, approvalStore.pendingList().single().code)
    }

    @Test
    fun `批准关闭时 request-approval 回 501 且不登记`() = testApplication {
        settings.pairingApprovalRequired = false
        application { registerPublicUnderPairing() }
        val device = TestDeviceIdentity("web-browser")

        val resp = client.requestApproval("sid-off", device)
        assertEquals(
            "501 是 Web 端约定的『未启用批准，继续正常配对』信号",
            HttpStatusCode.NotImplemented,
            resp.status
        )
        assertEquals("不能留下待批准记录", 0, approvalStore.pendingList().size)
    }

    @Test
    fun `未知 sid 的状态是 expired`() = testApplication {
        application { registerPublicUnderPairing() }
        val resp = client.get("/pairing/approval-status?sid=nope")
        assertEquals("expired", resp.json()["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `拒绝后状态是 rejected`() = testApplication {
        application { registerPublicUnderPairing() }
        val device = TestDeviceIdentity("web-browser")
        client.requestApproval("sid-1", device)

        assertTrue(manager.rejectDevice("sid-1"))
        val resp = client.get("/pairing/approval-status?sid=sid-1")
        assertEquals("rejected", resp.json()["status"]?.jsonPrimitive?.content)
    }

    // ── 主动推送的**触发时机**（2026-10-21） ──────────────────────────────────
    //
    // 这三条守的是"什么时候推"：新请求必须推一次；幂等重发（Web 刷页面）不能重推；
    // 登记失败（公钥解析不了）不能推。推得对不对、推几帧，见 ApprovalPushNudgerTest。

    @Test
    fun `新请求登记成功后触发一次推送`() = testApplication {
        application { registerPublicUnderPairing() }
        val device = TestDeviceIdentity("web-browser")

        val created = client.requestApproval("sid-push-1", device)

        assertEquals(HttpStatusCode.OK, created.status)
        assertEquals("新 sid 必须触发一轮唤醒（否则 App 只能等 30s 轮询）", listOf("sid-push-1"), pushedSids)
    }

    @Test
    fun `同一 sid 重复登记不重推 —— 否则刷新页面就把机主手机刷屏`() = testApplication {
        application { registerPublicUnderPairing() }
        val device = TestDeviceIdentity("web-browser")

        repeat(3) { client.requestApproval("sid-push-2", device) }

        assertEquals("幂等命中只算一次", listOf("sid-push-2"), pushedSids)
    }

    @Test
    fun `被拒绝后重新申请同一个 sid 会重建并再次推送`() = testApplication {
        application { registerPublicUnderPairing() }
        val device = TestDeviceIdentity("web-browser")

        client.requestApproval("sid-push-4", device)
        assertTrue(manager.rejectDevice("sid-push-4"))
        assertEquals(
            "拒绝后状态必须是 rejected（前端据此提示）",
            "rejected",
            client.get("/pairing/approval-status?sid=sid-push-4").json()["status"]?.jsonPrimitive?.content
        )

        // 2026-10-21（审计 §A2）：同一个 sid 重新申请必须是一次**新**请求 ——
        // 回到 pending、并且再推一轮（否则机主手机上再也不会有反应，15 分钟内死胡同）。
        val again = client.requestApproval("sid-push-4", device)

        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals("pending", again.json()["status"]?.jsonPrimitive?.content)
        assertEquals("必须重新推送一轮", listOf("sid-push-4", "sid-push-4"), pushedSids)
    }

    @Test
    fun `公钥解析失败时不推送 —— 请求根本没登记成功`() = testApplication {
        application { registerPublicUnderPairing() }

        val resp = client.post("/pairing/request-approval") {
            contentType(ContentType.Application.Json)
            setBody("""{"sid":"sid-push-3","device_pubkey":"not-a-key","device_name":"Chrome"}""")
        }

        assertEquals(HttpStatusCode.NotImplemented, resp.status)
        assertTrue("没登记成功就没有「新请求」可推", pushedSids.isEmpty())
    }
}
