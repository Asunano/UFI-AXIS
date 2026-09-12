package com.ufi_axis_core.api.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.api.TestDeviceIdentity
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PairingRoutes 路由测试。
 *
 * 2026-08-28（严格设备独立性）：配对改为三步握手，所有 confirm 用例都要
 * `POST /pairing/challenge` → 用设备私钥签挑战 → 带 `device_pubkey`/`challenge`/`signature`
 * 提交。客户端自报的 `app_fingerprint` 字段已删除（它正是「Web 端所有浏览器共用一个
 * 固定指纹、配对上限形同虚设」的根因），指纹现在由服务端据公钥计算。
 * 客户端行为由 [TestDeviceIdentity] 模拟。
 *
 * 可测试性 seam（PairingRoutes 构造参数，默认保留现有行为）：
 *   - `rootChecker` / `subnetChecker` / `deviceNameProvider`
 *   - `rateLimitMs`（需要连续多次请求的用例传 0 关闭速率限制）
 *
 * `PairingRoutes.register()` 不自带 `/pairing` 前缀（线上由
 * `route("/pairing") { pairingRoutes.register(this) }` 提供），测试同样包一层。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingRoutesTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettings
    private lateinit var ctx: RouteContext
    private lateinit var manager: PairingManager
    private lateinit var store: PairedDeviceStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = AppSettings(context)
        settings.resetAll()
        settings.enterPairingMode()

        // PairingRoutes 只读 ctx.settings，其余 RouteContext 字段用 mock 占位。
        ctx = mockk<RouteContext>()
        every { ctx.settings } returns settings

        store = PairedDeviceStore(context, settings)
        store.clear()
        manager = PairingManager(settings, store)
    }

    /** 构造 routes：默认注入确定性 seam。 */
    private fun buildRoutes(
        rateLimitMs: Long = 10_000L,
        deviceName: String = "UFI-AXIS-5G",
        subnetOk: Boolean = true,
        tunnelOrigin: Boolean = false
    ): PairingRoutes = PairingRoutes(
        ctx,
        manager,
        rootChecker = { true },
        subnetChecker = { subnetOk },
        tunnelChecker = { tunnelOrigin },
        deviceNameProvider = { deviceName },
        rateLimitMs = rateLimitMs
    )

    private fun Application.mount(routes: PairingRoutes) {
        install(ContentNegotiation) { json() }
        routing { route("/pairing") { routes.register(this) } }
    }

    private suspend fun HttpResponse.json(): JsonObject =
        Json.parseToJsonElement(bodyAsText()) as JsonObject

    /** 取一次性挑战。 */
    private suspend fun HttpClient.challenge(): String =
        post("/pairing/challenge").json()["challenge"]!!.jsonPrimitive.content

    /**
     * 完整 confirm：取挑战 → 签名 → 提交。
     *
     * `password` 默认给值：严格设备独立性之后 confirm **必须**带密码
     * （原先"默认密码时按 admin 放行"的兼容兜底已删除），大多数用例都要带。
     * 需要验证「不带密码」时显式传 `password = null`。
     *
     * @param challengeOverride 覆盖挑战原文（用于「过期/重复挑战」这类负面用例）
     * @param signatureOverride 覆盖签名（用于「验签失败」用例）
     */
    private suspend fun HttpClient.confirm(
        device: TestDeviceIdentity,
        code: String,
        password: String? = "secret123",
        deviceName: String? = null,
        hwId: String? = null,
        challengeOverride: String? = null,
        signatureOverride: String? = null,
        pubKeyOverride: String? = null
    ): HttpResponse {
        val challenge = challengeOverride ?: challenge()
        val body = buildJsonObject {
            put("pairing_code", code)
            put("device_pubkey", pubKeyOverride ?: device.publicKeySpki)
            put("challenge", challenge)
            put("signature", signatureOverride ?: device.sign(challenge))
            password?.let { put("password", it) }
            deviceName?.let { put("device_name", it) }
            hwId?.let { put("device_hwid", it) }
        }
        return post("/pairing/confirm") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
    }

    @Test
    fun `GET info must not expose any credential`() {
        val routes = buildRoutes()
        testApplication {
            application { mount(routes) }
            val resp = client.get("/pairing/info")
            assertEquals(HttpStatusCode.OK, resp.status)

            val obj = resp.json()
            assertFalse("info 绝不返回 token", "token" in obj)
            assertFalse("info 绝不返回 secret", "secret" in obj)
            assertTrue("info 应含 device_id", "device_id" in obj)
            assertTrue("info 应含 pairing_code", "pairing_code" in obj)
        }
    }

    @Test
    fun `GET info returns 200 even when device already paired`() {
        // 多设备模式下 info 无法识别请求方，始终返回 200。
        val routes = buildRoutes()
        testApplication {
            settings.confirmPairing(settings.pairingCode, "fp-x")
            application { mount(routes) }
            assertEquals(HttpStatusCode.OK, client.get("/pairing/info").status)
        }
    }

    @Test
    fun `GET info returns device_name and has_default_password from seams`() {
        val routes = buildRoutes(deviceName = "UFI-AXIS-5G")
        testApplication {
            application { mount(routes) }
            val obj = client.get("/pairing/info").json()
            assertEquals("UFI-AXIS-5G", obj["device_name"]?.jsonPrimitive?.content)
            assertEquals(true, obj["has_default_password"]?.jsonPrimitive?.boolean)
        }
    }

    @Test
    fun `POST challenge returns a fresh one-time challenge`() {
        val routes = buildRoutes(rateLimitMs = 0)
        testApplication {
            application { mount(routes) }
            val first = client.challenge()
            val second = client.challenge()
            assertTrue("挑战应非空", first.isNotBlank())
            assertFalse("每次挑战应不同", first == second)
        }
    }

    @Test
    fun `POST confirm with correct code returns token and server-computed fingerprint`() {
        val routes = buildRoutes()
        val device = TestDeviceIdentity()
        testApplication {
            val code = settings.pairingCode
            application { mount(routes) }
            val resp = client.confirm(device, code)
            assertEquals(HttpStatusCode.OK, resp.status)

            val obj = resp.json()
            assertTrue("确认成功应下发 token", obj["token"]!!.jsonPrimitive.content.isNotBlank())
            assertEquals(
                "fingerprint 必须是服务端据公钥算出的值",
                device.fingerprint,
                obj["fingerprint"]?.jsonPrimitive?.content
            )
            assertFalse("响应不应再包含共享 secret", "secret" in obj)
            assertTrue("确认成功后后端应置已配对", settings.paired)
            assertTrue(
                "记录应保存公钥与 token 哈希",
                store.findByFingerprint(device.fingerprint)!!.let {
                    it.pubKey.isNotBlank() && it.tokenHash.isNotBlank()
                }
            )
        }
    }

    @Test
    fun `POST confirm with wrong code returns 401`() {
        val routes = buildRoutes()
        val device = TestDeviceIdentity()
        testApplication {
            application { mount(routes) }
            assertEquals(HttpStatusCode.Unauthorized, client.confirm(device, "wrongcode").status)
        }
    }

    @Test
    fun `POST confirm without device key material returns 400`() {
        val routes = buildRoutes(rateLimitMs = 0)
        testApplication {
            application { mount(routes) }
            val code = settings.pairingCode
            val resp = client.post("/pairing/confirm") {
                contentType(ContentType.Application.Json)
                setBody("""{"pairing_code":"$code"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    @Test
    fun `POST confirm with bad challenge signature returns 401 INVALID_DEVICE_KEY`() {
        // 只有公钥没有私钥的攻击者：拿到挑战也无法证明持有私钥。
        val routes = buildRoutes(rateLimitMs = 0)
        val device = TestDeviceIdentity()
        val attacker = TestDeviceIdentity("attacker")
        testApplication {
            application { mount(routes) }
            val code = settings.pairingCode
            val challenge = client.challenge()
            val resp = client.confirm(
                device, code,
                challengeOverride = challenge,
                signatureOverride = attacker.sign(challenge)
            )
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals("INVALID_DEVICE_KEY", resp.json()["code"]?.jsonPrimitive?.content)
            assertFalse("验签失败不得写入任何记录", store.list().any { it.fingerprint == device.fingerprint })
        }
    }

    @Test
    fun `POST confirm reusing a consumed challenge returns 401 INVALID_CHALLENGE`() {
        val routes = buildRoutes(rateLimitMs = 0)
        val device = TestDeviceIdentity()
        testApplication {
            application { mount(routes) }
            val code = settings.pairingCode
            val challenge = client.challenge()
            assertEquals(
                HttpStatusCode.OK,
                client.confirm(device, code, challengeOverride = challenge).status
            )
            // 同一挑战第二次使用必须失败，否则抓包重放即可再配对一台
            settings.enterPairingMode()
            val replay = client.confirm(
                TestDeviceIdentity("second"), settings.pairingCode, challengeOverride = challenge
            )
            assertEquals(HttpStatusCode.Unauthorized, replay.status)
            assertEquals("INVALID_CHALLENGE", replay.json()["code"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `POST confirm with garbage public key returns 401 INVALID_DEVICE_KEY`() {
        val routes = buildRoutes(rateLimitMs = 0)
        val device = TestDeviceIdentity()
        testApplication {
            application { mount(routes) }
            val code = settings.pairingCode
            val resp = client.confirm(device, code, pubKeyOverride = "bm90LWEta2V5")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals("INVALID_DEVICE_KEY", resp.json()["code"]?.jsonPrimitive?.content)
        }
    }

    // ────────────────────────────────────────────────
    // 登录/配对语义拆分：已配置设备凭密码登录（免一次性配对码）
    // ────────────────────────────────────────────────

    @Test
    fun `fresh device rejects confirm with blank pairing code`() {
        // 全新设备（从未设置密码）必须凭一次性配对码完成首次配对（配对=初始化语义）。
        val routes = buildRoutes(rateLimitMs = 0)
        val device = TestDeviceIdentity()
        testApplication {
            application { mount(routes) }
            val resp = client.confirm(device, "", password = "secret123")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    @Test
    fun `configured device allows password login with consumed pairing code`() {
        settings.setDevicePassword("secret123")
        settings.confirmPairing(settings.pairingCode, "fp-first") // 消耗一次性配对码
        assertTrue("前置：配对码应已消耗", settings.pairingCode.isBlank())
        val routes = buildRoutes(rateLimitMs = 0)
        val device = TestDeviceIdentity()
        testApplication {
            application { mount(routes) }
            val resp = client.confirm(device, "", password = "secret123")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(
                "密码登录应自动绑定新设备指纹",
                settings.pairedFingerprints.contains(device.fingerprint)
            )
        }
    }

    @Test
    fun `configured device password login with wrong password returns 401`() {
        settings.setDevicePassword("secret123")
        settings.confirmPairing(settings.pairingCode, "fp-first")
        val routes = buildRoutes(rateLimitMs = 0)
        val device = TestDeviceIdentity()
        testApplication {
            application { mount(routes) }
            val resp = client.confirm(device, "", password = "wrongpw")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertFalse("密码错误不应绑定指纹", settings.pairedFingerprints.contains(device.fingerprint))
        }
    }

    @Test
    fun `configured device password login respects pairing max devices limit`() {
        settings.setDevicePassword("secret123")
        settings.pairingEnabled = true
        settings.pairingMaxDevices = 1
        settings.confirmPairing(settings.pairingCode, "fp-first") // 占用唯一配额并消耗配对码
        val routes = buildRoutes(rateLimitMs = 0)
        testApplication {
            application { mount(routes) }
            val resp = client.confirm(TestDeviceIdentity(), "", password = "secret123")
            assertEquals(HttpStatusCode.Conflict, resp.status)
        }
    }

    @Test
    fun `two distinct clients each consume a pairing slot`() {
        // 回归防线：Web 端曾用一个写死的 WEB_FINGERPRINT，于是任意多个浏览器只占一个配额，
        // 配对上限被完全绕过。现在指纹由各自密钥派生，第二个客户端必须被上限拒绝。
        settings.setDevicePassword("secret123")
        settings.pairingEnabled = true
        settings.pairingMaxDevices = 1
        val routes = buildRoutes(rateLimitMs = 0)
        testApplication {
            application { mount(routes) }
            val first = client.confirm(TestDeviceIdentity("browser-1"), settings.pairingCode, password = "secret123")
            assertEquals(HttpStatusCode.OK, first.status)
            val second = client.confirm(TestDeviceIdentity("browser-2"), "", password = "secret123")
            assertEquals(HttpStatusCode.Conflict, second.status)
            assertEquals(1, store.list().size)
        }
    }

    // ────────────────────────────────────────────────
    // 「清除应用数据后重新配对 → 设备列表无限增长」的 hwId 合并
    // ────────────────────────────────────────────────

    @Test
    fun `re-pairing same hardware with new key merges duplicate record`() {
        val routes = buildRoutes(rateLimitMs = 0)
        val before = TestDeviceIdentity("张三的手机")
        val after = TestDeviceIdentity("张三的手机")
        testApplication {
            application { mount(routes) }
            val code = settings.pairingCode
            assertEquals(
                HttpStatusCode.OK,
                client.confirm(before, code, password = "secret123", deviceName = "张三的手机", hwId = "H1").status
            )
            assertEquals(listOf(before.fingerprint), settings.pairedFingerprints)
            assertEquals(1, store.list().size)
            assertEquals("H1", store.list().first().hwId)

            // 清除应用数据 → 密钥重建（新指纹），hwId 仍是 H1；配对码已消耗 → 凭密码登录
            assertEquals(
                HttpStatusCode.OK,
                client.confirm(after, "", password = "secret123", deviceName = "张三的手机", hwId = "H1").status
            )

            val records = store.list()
            assertEquals("同一硬件重新配对不得新增设备记录", 1, records.size)
            assertEquals(after.fingerprint, records.first().fingerprint)
            assertEquals("H1", records.first().hwId)
            assertEquals(
                "pairedFingerprints 应只含新指纹，旧指纹须被解绑",
                listOf(after.fingerprint),
                settings.pairedFingerprints
            )
        }
    }

    @Test
    fun `different hardware still registers as a separate device`() {
        val routes = buildRoutes(rateLimitMs = 0)
        val a = TestDeviceIdentity("A")
        val b = TestDeviceIdentity("B")
        testApplication {
            application { mount(routes) }
            val code = settings.pairingCode
            assertEquals(HttpStatusCode.OK, client.confirm(a, code, password = "secret123", hwId = "H1").status)
            assertEquals(HttpStatusCode.OK, client.confirm(b, "", password = "secret123", hwId = "H2").status)
            assertEquals("两台不同硬件应各占一条记录", 2, store.list().size)
            assertEquals(listOf(a.fingerprint, b.fingerprint), settings.pairedFingerprints)
        }
    }

    @Test
    fun `client without device_hwid keeps previous behaviour`() {
        // Web 端不上报 hwId → 不做合并，各自入列。
        val routes = buildRoutes(rateLimitMs = 0)
        val a = TestDeviceIdentity("A")
        val b = TestDeviceIdentity("B")
        testApplication {
            application { mount(routes) }
            val code = settings.pairingCode
            assertEquals(HttpStatusCode.OK, client.confirm(a, code, password = "secret123").status)
            assertEquals(HttpStatusCode.OK, client.confirm(b, "", password = "secret123").status)
            assertEquals("缺 hwId 时各自入列", 2, store.list().size)
            assertEquals(listOf(a.fingerprint, b.fingerprint), settings.pairedFingerprints)
        }
    }

    @Test
    fun `re-keyed hardware still counts against max devices limit`() {
        // 2026-08-28 语义变更：配额检查里的 hwId 旁路已删除。
        // 旁路的收益只是"清应用数据后不占新槽位"，代价是伪造 device_hwid 即可绕过配额——
        // 而 hwId 是客户端可任意伪造的明文。现在设备身份=密钥对，新密钥就是新设备，
        // 达到上限时必须由用户先在配对界面删除旧记录。
        val routes = buildRoutes(rateLimitMs = 0)
        settings.pairingEnabled = true
        settings.pairingMaxDevices = 1
        val before = TestDeviceIdentity("A")
        val after = TestDeviceIdentity("A-rekeyed")
        testApplication {
            application { mount(routes) }
            val code = settings.pairingCode
            assertEquals(
                HttpStatusCode.OK,
                client.confirm(before, code, password = "secret123", hwId = "H1").status
            )
            // 同一硬件换密钥：配额已满 → 409（不再因 hwId 相同而放行）
            assertEquals(
                HttpStatusCode.Conflict,
                client.confirm(after, "", password = "secret123", hwId = "H1").status
            )
            assertEquals(listOf(before.fingerprint), settings.pairedFingerprints)

            // 放宽配额后重新配对：此时 hwId 合并生效，记录数仍为 1
            settings.pairingMaxDevices = 2
            assertEquals(
                HttpStatusCode.OK,
                client.confirm(after, "", password = "secret123", hwId = "H1").status
            )
            assertEquals(1, store.list().size)
            assertEquals(listOf(after.fingerprint), settings.pairedFingerprints)
        }
    }

    @Test
    fun `info and confirm use independent rate-limit counters`() {
        val routes = buildRoutes()
        val device = TestDeviceIdentity()
        testApplication {
            val code = settings.pairingCode
            application { mount(routes) }
            // 1) 先 GET /info（放行，占用 info 计数器）
            assertEquals(HttpStatusCode.OK, client.get("/pairing/info").status)
            // 2) 同 IP 立即握手 + confirm 仍应放行（各端点独立计数器）
            assertEquals(HttpStatusCode.OK, client.confirm(device, code).status)
            // 3) 再次 GET /info 应被 info 计数器限流（429），证明 confirm 未被误杀
            assertEquals(HttpStatusCode.TooManyRequests, client.get("/pairing/info").status)
        }
    }

    @Test
    fun `POST unpair clears pairing and re-enters pairing mode`() {
        val routes = buildRoutes()
        testApplication {
            settings.setDevicePassword("secret123")
            settings.confirmPairing(settings.pairingCode, "fp-x")
            application { mount(routes) }
            val resp = client.post("/pairing/unpair") {
                contentType(ContentType.Application.Json)
                setBody("""{"password":"secret123"}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertFalse("解除配对后应未配对", settings.paired)
            assertTrue("解除配对后应重新生成配对码", settings.pairingCode.isNotBlank())
            assertTrue("解除配对后配对记录应清空", store.isEmpty())
        }
    }

    @Test
    fun `POST unpair without password returns 401 and keeps pairing`() {
        // 回归：unpair 曾仅做同网段判断，同一 WiFi 下任何人都能清空全部配对。
        val routes = buildRoutes()
        testApplication {
            settings.setDevicePassword("secret123")
            settings.confirmPairing(settings.pairingCode, "fp-x")
            application { mount(routes) }
            val resp = client.post("/pairing/unpair")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals("MISSING_PASSWORD", resp.json()["code"]?.jsonPrimitive?.content)
            assertTrue("未鉴权的 unpair 不得清空配对", settings.paired)
        }
    }

    @Test
    fun `POST unpair with wrong password returns 401 and keeps pairing`() {
        val routes = buildRoutes(rateLimitMs = 0)
        testApplication {
            settings.setDevicePassword("secret123")
            settings.confirmPairing(settings.pairingCode, "fp-x")
            application { mount(routes) }
            val resp = client.post("/pairing/unpair") {
                contentType(ContentType.Application.Json)
                setBody("""{"password":"wrong"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals("INVALID_PASSWORD", resp.json()["code"]?.jsonPrimitive?.content)
            assertTrue("密码错误时不得清空配对", settings.paired)
        }
    }


    @Test
    fun `GET info returns 403 when subnet check fails`() {
        val routes = buildRoutes(subnetOk = false)
        testApplication {
            application { mount(routes) }
            assertEquals(HttpStatusCode.Forbidden, client.get("/pairing/info").status)
        }
    }

    // ────────────────────────────────────────────────
    // 隧道来源降级（frpc / cloudflared 转发进来的请求）
    // 同网段限制拦不住隧道：转发之后 remoteAddress 恒为 127.0.0.1，天然满足 isLocalSubnet。
    // 以下用例锁住"哪些操作不许从隧道做"这条策略。
    // ────────────────────────────────────────────────

    @Test
    fun `GET info from tunnel hides pairing code and device id`() {
        val routes = buildRoutes(tunnelOrigin = true)
        testApplication {
            application { mount(routes) }
            val obj = client.get("/pairing/info").json()
            assertFalse("隧道来源绝不下发配对码", "pairing_code" in obj)
            assertFalse("隧道来源不回 device_id", "device_id" in obj)
            assertFalse("隧道来源不回 storage_status", "storage_status" in obj)
            // 登录页只需要这两项
            assertEquals("UFI-AXIS-5G", obj["device_name"]?.jsonPrimitive?.content)
            assertEquals(true, obj["has_default_password"]?.jsonPrimitive?.boolean)
        }
    }

    @Test
    fun `GET info from lan still returns pairing code`() {
        val routes = buildRoutes()
        testApplication {
            application { mount(routes) }
            val obj = client.get("/pairing/info").json()
            assertTrue("局域网来源仍需拿到配对码才能完成初始化", "pairing_code" in obj)
        }
    }

    @Test
    fun `POST challenge from tunnel is allowed`() {
        // 挑战只是随机 nonce，不含秘密；拦了远端就完全无法登录。
        val routes = buildRoutes(tunnelOrigin = true)
        testApplication {
            application { mount(routes) }
            assertEquals(HttpStatusCode.OK, client.post("/pairing/challenge").status)
        }
    }

    @Test
    fun `POST confirm from tunnel is rejected before password is set`() {
        val routes = buildRoutes(tunnelOrigin = true)
        val device = TestDeviceIdentity()
        testApplication {
            val code = settings.pairingCode
            application { mount(routes) }
            val resp = client.confirm(device, code)
            assertEquals(HttpStatusCode.Forbidden, resp.status)
            assertFalse("首次配对被拒后不得留下配对态", settings.paired)
        }
    }

    @Test
    fun `POST confirm from tunnel is allowed once password is set`() {
        // 已初始化设备走的是"密码登录"分支，远端 Web 必须能登录。
        val routes = buildRoutes(tunnelOrigin = true, rateLimitMs = 0)
        val device = TestDeviceIdentity()
        testApplication {
            settings.setDevicePassword("secret123")
            application { mount(routes) }
            assertEquals(HttpStatusCode.OK, client.confirm(device, "", password = "secret123").status)
        }
    }

    @Test
    fun `POST change-password from tunnel is rejected while password is default`() {
        val routes = buildRoutes(tunnelOrigin = true)
        testApplication {
            application { mount(routes) }
            val resp = client.post("/pairing/change-password") {
                contentType(ContentType.Application.Json)
                setBody("""{"old_password":"admin","new_password":"newpass123"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, resp.status)
        }
    }

    @Test
    fun `POST change-password from tunnel is allowed once password is set`() {
        val routes = buildRoutes(tunnelOrigin = true, rateLimitMs = 0)
        testApplication {
            settings.setDevicePassword("oldpass")
            application { mount(routes) }
            val resp = client.post("/pairing/change-password") {
                contentType(ContentType.Application.Json)
                setBody("""{"old_password":"oldpass","new_password":"newpass123"}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    @Test
    fun `POST unpair from tunnel is always rejected`() {
        val routes = buildRoutes(tunnelOrigin = true)
        testApplication {
            settings.setDevicePassword("secret123")
            settings.confirmPairing(settings.pairingCode, "fp-x")
            application { mount(routes) }
            val resp = client.post("/pairing/unpair") {
                contentType(ContentType.Application.Json)
                setBody("""{"password":"secret123"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, resp.status)
            assertTrue("隧道来源不得清空配对", settings.paired)
        }
    }

    // ────────────────────────────────────────────────
    // 配对密码（以下用例关闭速率限制以便连续重试）
    // 被测符号沿用 `devicePassword*`（`devicePasswordConfigured` / `verifyDevicePassword`）：
    // 那是持久化 key 的一部分，改名会让存量设备读不出已设置的密码，所以只统一注释口径。
    // ────────────────────────────────────────────────

    @Test
    fun `first confirm sets device password and later confirms verify it`() {
        val routes = buildRoutes(rateLimitMs = 0)
        testApplication {
            application { mount(routes) }
            // 首次 confirm：passwordSet=false → 将 password 落库为配对密码
            val code = settings.pairingCode
            assertEquals(
                HttpStatusCode.OK,
                client.confirm(TestDeviceIdentity("a"), code, password = "secret123", deviceName = "张三的手机").status
            )
            assertTrue("首次 confirm 应已配置配对密码", settings.devicePasswordConfigured)
            assertTrue("设置的密码应可验证", settings.verifyDevicePassword("secret123"))
            assertFalse("默认 admin 不应再有效", settings.verifyDevicePassword("admin"))

            // 再次配对（新配对码）用新密码成功
            settings.enterPairingMode()
            assertEquals(
                HttpStatusCode.OK,
                client.confirm(TestDeviceIdentity("b"), settings.pairingCode, password = "secret123").status
            )

            // 默认 admin 应失败
            settings.enterPairingMode()
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.confirm(TestDeviceIdentity("c"), settings.pairingCode, password = "admin").status
            )
        }
    }

    @Test
    fun `confirm with wrong password returns 401 INVALID_PASSWORD`() {
        settings.setDevicePassword("secret123")
        settings.enterPairingMode()
        val routes = buildRoutes(rateLimitMs = 0)
        testApplication {
            application { mount(routes) }
            val resp = client.confirm(TestDeviceIdentity(), settings.pairingCode, password = "wrongpw")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals("INVALID_PASSWORD", resp.json()["code"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `confirm locked after 5 wrong passwords returns 429`() {
        settings.setDevicePassword("secret123")
        settings.enterPairingMode()
        val routes = buildRoutes(rateLimitMs = 0)
        val device = TestDeviceIdentity()
        testApplication {
            application { mount(routes) }
            val code = settings.pairingCode
            // 前 4 次错误 → 401（配对码未被消费，可复用同一 code；挑战每次重取）
            repeat(4) {
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.confirm(device, code, password = "wrongpw").status
                )
            }
            val fifth = client.confirm(device, code, password = "wrongpw")
            assertEquals(HttpStatusCode.TooManyRequests, fifth.status)
            assertEquals("PASSWORD_LOCKED", fifth.json()["code"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `confirm without password is rejected even on a fresh device`() {
        // 兼容兜底已删除：原先"默认密码时不带 password 按 admin 放行"等于把
        // 免鉴权配对端点变成"知道配对码即可接入"，与本次加固目标直接冲突。
        val routes = buildRoutes(rateLimitMs = 0)
        testApplication {
            application { mount(routes) }
            val resp = client.confirm(TestDeviceIdentity(), settings.pairingCode, password = null)
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("PASSWORD_REQUIRED", resp.json()["code"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `confirm without password returns 400 PASSWORD_REQUIRED after password changed`() {
        settings.setDevicePassword("secret123")
        settings.enterPairingMode()
        val routes = buildRoutes(rateLimitMs = 0)
        testApplication {
            application { mount(routes) }
            val resp = client.confirm(TestDeviceIdentity(), settings.pairingCode, password = null)
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("PASSWORD_REQUIRED", resp.json()["code"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `change-password with correct old password returns 200 and rotates password`() {
        settings.setDevicePassword("oldpass")
        val routes = buildRoutes(rateLimitMs = 0)
        testApplication {
            application { mount(routes) }
            val resp = client.post("/pairing/change-password") {
                contentType(ContentType.Application.Json)
                setBody("""{"old_password":"oldpass","new_password":"newpass123"}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue("新密码应生效", settings.verifyDevicePassword("newpass123"))
            assertFalse("旧密码应失效", settings.verifyDevicePassword("oldpass"))
        }
    }

    @Test
    fun `change-password with wrong old password returns 401 WRONG_OLD_PASSWORD`() {
        settings.setDevicePassword("oldpass")
        val routes = buildRoutes(rateLimitMs = 0)
        testApplication {
            application { mount(routes) }
            val resp = client.post("/pairing/change-password") {
                contentType(ContentType.Application.Json)
                setBody("""{"old_password":"bad","new_password":"newpass123"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals("WRONG_OLD_PASSWORD", resp.json()["code"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `change-password with short new password returns 400`() {
        settings.setDevicePassword("oldpass")
        val routes = buildRoutes(rateLimitMs = 0)
        testApplication {
            application { mount(routes) }
            val resp = client.post("/pairing/change-password") {
                contentType(ContentType.Application.Json)
                setBody("""{"old_password":"oldpass","new_password":"abc"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    @Test
    fun `change-password locked after 5 wrong old passwords returns 429`() {
        settings.setDevicePassword("oldpass")
        val routes = buildRoutes(rateLimitMs = 0)
        testApplication {
            application { mount(routes) }
            repeat(4) {
                val resp = client.post("/pairing/change-password") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"old_password":"bad","new_password":"newpass123"}""")
                }
                assertEquals(HttpStatusCode.Unauthorized, resp.status)
            }
            val fifth = client.post("/pairing/change-password") {
                contentType(ContentType.Application.Json)
                setBody("""{"old_password":"bad","new_password":"newpass123"}""")
            }
            assertEquals(HttpStatusCode.TooManyRequests, fifth.status)
            assertEquals("PASSWORD_LOCKED", fifth.json()["code"]?.jsonPrimitive?.content)
        }
    }
}
