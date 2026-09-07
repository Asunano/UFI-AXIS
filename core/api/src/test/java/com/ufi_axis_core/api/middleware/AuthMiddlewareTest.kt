package com.ufi_axis_core.api.middleware

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.DeviceAuth
import com.ufi_axis_core.util.DeviceRequestVerifier
import com.ufi_axis_core.util.PairedDeviceStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * AuthMiddleware 单元测试（严格设备独立性，2026-08-28 重写）。
 *
 * 旧版本测的是「全局 token 是否等于 settings.token」，那套凭据模型已整体删除，
 * 所以测试也是整体重写而不是改断言：现在每个请求必须带
 * `Authorization: Bearer <设备 token>` + `X-Timestamp` + `X-Nonce` + `X-Signature`，
 * 服务端用 [PairedDeviceStore] 里该设备的公钥验签。
 *
 * 关键点：测试里的签名用 JDK 的 `SHA256withECDSA`（输出 DER），
 * 与 App 侧 Keystore 行为一致；Web 侧输出 raw r||s 由 [DeviceAuth.normalizeToDer] 归一化，
 * 该分支由 DeviceAuthTest 覆盖，这里不重复。
 *
 * 拒绝状态码沿用历史上的自定义 444（客户端据此判「需重新登录」），载荷带 `code`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuthMiddlewareTest {

    private lateinit var keyPair: KeyPair
    private lateinit var spkiBase64: String
    private lateinit var fingerprint: String
    private lateinit var verifier: DeviceRequestVerifier

    private val token = "device-token-for-tests"

    @Before
    fun setUp() {
        keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        spkiBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        fingerprint = requireNotNull(DeviceAuth.fingerprintOf(spkiBase64))

        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(ctx).apply { resetAll() }
        val store = PairedDeviceStore(ctx, settings)
        store.clear()
        store.upsert(
            fingerprint = fingerprint,
            deviceName = "unit-test-device",
            lastSeen = System.currentTimeMillis(),
            pubKey = spkiBase64,
            tokenHash = DeviceAuth.sha256Hex(token.toByteArray(Charsets.UTF_8))
        )
        // 每个用例一个全新的 NonceCache，避免用例间 nonce 互相污染
        verifier = DeviceRequestVerifier(store, DeviceAuth.NonceCache())
    }

    /** 用测试密钥对规范串签名，输出 base64url（无填充）。 */
    private fun sign(method: String, uri: String, timestamp: String, nonce: String): String {
        val canonical = DeviceAuth.canonicalString(method, uri, timestamp, nonce)
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(canonical.toByteArray(Charsets.UTF_8))
            sign()
        }
        return DeviceAuth.base64UrlNoPad(der)
    }

    @Test
    fun `correctly signed request is allowed (200)`() = runBlocking {
        testApplication {
            application { configure() }
            val ts = System.currentTimeMillis().toString()
            val resp = client.get("/protected") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(AuthMiddleware.HEADER_TIMESTAMP, ts)
                header(AuthMiddleware.HEADER_NONCE, "nonce-ok")
                header(AuthMiddleware.HEADER_SIGNATURE, sign("GET", "/protected", ts, "nonce-ok"))
            }
            assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    @Test
    fun `missing authorization header is rejected (444 UNAUTHORIZED)`() = runBlocking {
        testApplication {
            application { configure() }
            val resp = client.get("/protected")
            assertEquals(444, resp.status.value)
            assertTrue(ErrorCode.UNAUTHORIZED in resp.bodyAsText())
        }
    }

    @Test
    fun `unknown token is rejected (444 UNAUTHORIZED)`() = runBlocking {
        testApplication {
            application { configure() }
            val ts = System.currentTimeMillis().toString()
            val resp = client.get("/protected") {
                header(HttpHeaders.Authorization, "Bearer not-a-paired-token")
                header(AuthMiddleware.HEADER_TIMESTAMP, ts)
                header(AuthMiddleware.HEADER_NONCE, "nonce-unknown")
                header(AuthMiddleware.HEADER_SIGNATURE, sign("GET", "/protected", ts, "nonce-unknown"))
            }
            assertEquals(444, resp.status.value)
            assertTrue(ErrorCode.UNAUTHORIZED in resp.bodyAsText())
        }
    }

    @Test
    fun `valid token without signature headers is rejected (444 INVALID_SIGNATURE)`() = runBlocking {
        // 这是本次重写最关键的一条：旧实现里"不发签名头"等于跳过签名校验，
        // 即签名机制形同不存在。现在必须被拒。
        testApplication {
            application { configure() }
            val resp = client.get("/protected") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(444, resp.status.value)
            assertTrue(ErrorCode.INVALID_SIGNATURE in resp.bodyAsText())
        }
    }

    @Test
    fun `stale timestamp is rejected (444 INVALID_SIGNATURE)`() = runBlocking {
        testApplication {
            application { configure() }
            val stale = (System.currentTimeMillis() - DeviceAuth.MAX_TIMESTAMP_DRIFT_MS - 60_000).toString()
            val resp = client.get("/protected") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(AuthMiddleware.HEADER_TIMESTAMP, stale)
                header(AuthMiddleware.HEADER_NONCE, "nonce-stale")
                header(AuthMiddleware.HEADER_SIGNATURE, sign("GET", "/protected", stale, "nonce-stale"))
            }
            assertEquals(444, resp.status.value)
            assertTrue(ErrorCode.INVALID_SIGNATURE in resp.bodyAsText())
        }
    }

    @Test
    fun `signature bound to another uri is rejected (444 INVALID_SIGNATURE)`() = runBlocking {
        // URI 在签名内，所以拿 /other 的签名请求 /protected 必须失败，
        // 否则一次抓包就能被改写成任意端点调用。
        testApplication {
            application { configure() }
            val ts = System.currentTimeMillis().toString()
            val resp = client.get("/protected") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(AuthMiddleware.HEADER_TIMESTAMP, ts)
                header(AuthMiddleware.HEADER_NONCE, "nonce-other-uri")
                header(AuthMiddleware.HEADER_SIGNATURE, sign("GET", "/other", ts, "nonce-other-uri"))
            }
            assertEquals(444, resp.status.value)
            assertTrue(ErrorCode.INVALID_SIGNATURE in resp.bodyAsText())
        }
    }

    @Test
    fun `replaying the same nonce is rejected on second attempt`() = runBlocking {
        testApplication {
            application { configure() }
            val ts = System.currentTimeMillis().toString()
            val signature = sign("GET", "/protected", ts, "nonce-replay")
            suspend fun request() = client.get("/protected") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(AuthMiddleware.HEADER_TIMESTAMP, ts)
                header(AuthMiddleware.HEADER_NONCE, "nonce-replay")
                header(AuthMiddleware.HEADER_SIGNATURE, signature)
            }
            assertEquals(HttpStatusCode.OK, request().status)
            val replayed = request()
            assertEquals(444, replayed.status.value)
            assertTrue(ErrorCode.INVALID_SIGNATURE in replayed.bodyAsText())
        }
    }

    @Test
    fun `health endpoint bypasses auth and returns 200`() = runBlocking {
        testApplication {
            application { configure() }
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
        }
    }

    /** AuthMiddleware 以 JsonElement 响应错误体，需安装 ContentNegotiation 才能序列化（否则 406）。 */
    private fun io.ktor.server.application.Application.configure() {
        install(ContentNegotiation) { json() }
        routing {
            AuthMiddleware(verifier).install(this)
            get("/protected") { call.respondText("ok") }
            get("/health") { call.respondText("health") }
        }
    }
}
