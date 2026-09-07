package com.ufi_axis_core.api.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.api.TestDeviceIdentity
import com.ufi_axis_core.api.middleware.AuthMiddleware
import com.ufi_axis_core.api.pairing.PairingManager
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.DeviceRequestVerifier
import com.ufi_axis_core.util.PairedDeviceStore
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
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
 * PairedDevicesRoutes 路由测试。
 *
 * `/api/pairing/devices` 挂在鉴权块内，所以每个请求都必须像真实客户端一样带
 * Bearer(设备 token) + X-Timestamp/X-Nonce/X-Signature（见 [TestDeviceIdentity]）。
 *
 * 2026-08-28（严格设备独立性）：删掉了「最后一台移除 → 轮换全局 token」两条用例。
 * 那个机制本身已经不存在：token 现在按设备签发，移除设备就是删掉它的 token 记录，
 * 所以「被移除设备的 token 立刻失效」与「其他设备不受影响」才是要断言的行为，
 * 也正好是原来那套全局轮换做不到的定向吊销。
 *
 * `PairedDevicesRoutes.register()` 自带 `/pairing/devices` 前缀（线上再由 `route("/api")` 包一层），
 * 因此这里请求 `/pairing/devices` 即为正确挂载。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairedDevicesRoutesTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettings
    private lateinit var manager: PairingManager
    private lateinit var store: PairedDeviceStore
    private lateinit var routes: PairedDevicesRoutes
    private lateinit var authMiddleware: AuthMiddleware

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = AppSettings(context)
        settings.resetAll()
        settings.setDevicePassword("secret123")

        store = PairedDeviceStore(context, settings)
        store.clear()
        manager = PairingManager(settings, store)
        routes = PairedDevicesRoutes(manager)
        authMiddleware = AuthMiddleware(DeviceRequestVerifier(store))
    }

    private fun Application.registerApi() {
        install(ContentNegotiation) { json() }
        routing {
            authMiddleware.install(this)
            // 必须与线上一致地包一层 /api：AuthMiddleware 把 `/pairing/**` 视为免鉴权
            // （那是 root 上的配对端点），设备管理接口的真实路径是 /api/pairing/devices。
            // 少包这层会让鉴权用例全部"意外放行"。
            route("/api") { routes.register(this) }
        }
    }

    /** 经真实配对流程预置一台设备（含挑战验签），返回其身份句柄。 */
    private fun pairDevice(name: String): TestDeviceIdentity {
        val device = TestDeviceIdentity(name)
        val result = device.pair(manager, settings, "secret123")
        assertTrue("预置设备应配对成功，实际=$result", result is PairingManager.PairingConfirmResult.Success)
        return device
    }

    private suspend fun HttpClient.getSigned(uri: String, device: TestDeviceIdentity): HttpResponse =
        get(uri) { device.authorize(this, "GET", uri) }

    private suspend fun HttpClient.patchSigned(
        uri: String,
        device: TestDeviceIdentity,
        body: String
    ): HttpResponse = patch(uri) {
        device.authorize(this, "PATCH", uri)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.deleteSigned(
        uri: String,
        device: TestDeviceIdentity,
        body: String
    ): HttpResponse = delete(uri) {
        device.authorize(this, "DELETE", uri)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.json(): JsonObject =
        Json.parseToJsonElement(bodyAsText()) as JsonObject

    @Test
    fun `GET devices without token is rejected (444)`() {
        testApplication {
            application { registerApi() }
            val resp = client.get("/api/pairing/devices")
            assertEquals(444, resp.status.value)
        }
    }

    @Test
    fun `GET devices with token but no signature is rejected (444)`() {
        val device = pairDevice("Device One")
        testApplication {
            application { registerApi() }
            val resp = client.get("/api/pairing/devices") {
                header(HttpHeaders.Authorization, "Bearer ${device.token}")
            }
            assertEquals(444, resp.status.value)
        }
    }

    @Test
    fun `GET devices returns paired records with signed request`() {
        val one = pairDevice("Device One")
        pairDevice("Device Two")
        testApplication {
            application { registerApi() }
            val resp = client.getSigned("/api/pairing/devices", one)
            assertEquals(HttpStatusCode.OK, resp.status)

            val devices = resp.json()["devices"]?.jsonArray
            assertTrue("应返回 devices 数组", devices != null)
            assertEquals(2, devices!!.size)
            val names = devices.mapNotNull { it as? JsonObject }
                .mapNotNull { it["device_name"]?.jsonPrimitive?.content }
            assertTrue("应包含 Device One", "Device One" in names)
            assertTrue("应包含 Device Two", "Device Two" in names)
        }
    }

    @Test
    fun `PATCH rename device succeeds and keeps its credentials`() {
        val device = pairDevice("Device One")
        testApplication {
            application { registerApi() }
            val resp = client.patchSigned(
                "/api/pairing/devices/${device.fingerprint}", device, """{"device_name":"新名字"}"""
            )
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("新名字", store.list().first().deviceName)
            // 改名不能抹掉公钥/token 哈希，否则设备会被无声踢下线
            val record = store.findByFingerprint(device.fingerprint)!!
            assertTrue("改名后应保留公钥", record.pubKey.isNotBlank())
            assertTrue("改名后应保留 token 哈希", record.tokenHash.isNotBlank())
            assertEquals(HttpStatusCode.OK, client.getSigned("/api/pairing/devices", device).status)
        }
    }

    @Test
    fun `PATCH with invalid name returns 400`() {
        val device = pairDevice("Device One")
        testApplication {
            application { registerApi() }
            val resp = client.patchSigned(
                "/api/pairing/devices/${device.fingerprint}", device, """{"device_name":""}"""
            )
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    @Test
    fun `PATCH non-existent device returns 404`() {
        val device = pairDevice("Device One")
        testApplication {
            application { registerApi() }
            val resp = client.patchSigned("/api/pairing/devices/nope", device, """{"device_name":"x"}""")
            assertEquals(HttpStatusCode.NotFound, resp.status)
            assertEquals("DEVICE_NOT_FOUND", resp.json()["code"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `DELETE with wrong password returns 401 and keeps device`() {
        val device = pairDevice("Device One")
        testApplication {
            application { registerApi() }
            val resp = client.deleteSigned(
                "/api/pairing/devices/${device.fingerprint}", device, """{"password":"wrongpw"}"""
            )
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertTrue("设备应仍在", store.list().isNotEmpty())
        }
    }

    @Test
    fun `DELETE without password returns 401 MISSING_PASSWORD`() {
        val device = pairDevice("Device One")
        testApplication {
            application { registerApi() }
            val resp = client.deleteSigned("/api/pairing/devices/${device.fingerprint}", device, "{}")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals("MISSING_PASSWORD", resp.json()["code"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `DELETE locked after 5 wrong passwords returns 429`() {
        val device = pairDevice("Device One")
        val uri = "/api/pairing/devices/${device.fingerprint}"
        testApplication {
            application { registerApi() }
            repeat(4) {
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.deleteSigned(uri, device, """{"password":"wrongpw"}""").status
                )
            }
            val fifth = client.deleteSigned(uri, device, """{"password":"wrongpw"}""")
            assertEquals(HttpStatusCode.TooManyRequests, fifth.status)
            assertEquals("PASSWORD_LOCKED", fifth.json()["code"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `removing a device invalidates only that device token`() {
        // 定向吊销：这是每设备 token 取代全局 token 的核心收益。
        val victim = pairDevice("Device One")
        val survivor = pairDevice("Device Two")
        testApplication {
            application { registerApi() }
            val resp = client.deleteSigned(
                "/api/pairing/devices/${victim.fingerprint}", survivor, """{"password":"secret123"}"""
            )
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(true, resp.json()["success"]?.jsonPrimitive?.boolean)
            assertFalse("被删设备应从记录中消失", store.fingerprints().contains(victim.fingerprint))

            // 被删设备的 token 立刻失效
            assertEquals(444, client.getSigned("/api/pairing/devices", victim).status.value)
            // 其余设备不受影响
            val list = client.getSigned("/api/pairing/devices", survivor)
            assertEquals(HttpStatusCode.OK, list.status)
            assertEquals(1, list.json()["devices"]?.jsonArray?.size)
        }
    }

    @Test
    fun `DELETE non-existent device returns 404`() {
        val device = pairDevice("Device One")
        testApplication {
            application { registerApi() }
            val resp = client.deleteSigned(
                "/api/pairing/devices/nope", device, """{"password":"secret123"}"""
            )
            assertEquals(HttpStatusCode.NotFound, resp.status)
            assertEquals("DEVICE_NOT_FOUND", resp.json()["code"]?.jsonPrimitive?.content)
        }
    }
}
