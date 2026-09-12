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
import java.io.File
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
 * 拒绝状态码分三档（2026-09-08 拆分，此前一律 444）：
 * 444 = 凭据真的不作数（没 token / token 不认识 / 记录没公钥）→ 客户端清凭据重新配对；
 * 401 = 这次请求本身有问题（时间戳超窗 / 签名缺失或不对 / 重放）→ 保留凭据重试；
 * 503 = 服务端配对存储读不出来（降级）→ 保留凭据退避重试。
 * 拆分的动因见 `PairedDeviceStore` 类注释里的事故复盘：把可自愈的临时故障映射成 444，
 * 等于让客户端自毁凭据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuthMiddlewareTest {

    private lateinit var keyPair: KeyPair
    private lateinit var spkiBase64: String
    private lateinit var fingerprint: String
    private lateinit var verifier: DeviceRequestVerifier
    private lateinit var context: Context
    private lateinit var store: PairedDeviceStore

    private val token = "device-token-for-tests"

    @Before
    fun setUp() {
        keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        spkiBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        fingerprint = requireNotNull(DeviceAuth.fingerprintOf(spkiBase64))

        context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(context).apply { resetAll() }
        store = PairedDeviceStore(context, settings)
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
    fun `valid token without signature headers is rejected (401 INVALID_SIGNATURE)`() = runBlocking {
        // 这是本次重写最关键的一条：旧实现里"不发签名头"等于跳过签名校验，
        // 即签名机制形同不存在。现在必须被拒 —— 但拒的是**这次请求**，不是凭据，
        // 所以是 401 而不是 444（444 会让客户端清掉 token）。
        testApplication {
            application { configure() }
            val resp = client.get("/protected") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertTrue(ErrorCode.INVALID_SIGNATURE in resp.bodyAsText())
        }
    }

    @Test
    fun `stale timestamp is rejected (401 STALE_TIMESTAMP)`() = runBlocking {
        // 时钟漂移是可自愈的：客户端校时重试即可。回 444 会让它误判"凭据失效"并要求重新配对。
        testApplication {
            application { configure() }
            val stale = (System.currentTimeMillis() - DeviceAuth.MAX_TIMESTAMP_DRIFT_MS - 60_000).toString()
            val resp = client.get("/protected") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(AuthMiddleware.HEADER_TIMESTAMP, stale)
                header(AuthMiddleware.HEADER_NONCE, "nonce-stale")
                header(AuthMiddleware.HEADER_SIGNATURE, sign("GET", "/protected", stale, "nonce-stale"))
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertTrue(ErrorCode.STALE_TIMESTAMP in resp.bodyAsText())
        }
    }

    @Test
    fun `signature bound to another uri is rejected (401 INVALID_SIGNATURE)`() = runBlocking {
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
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
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
            assertEquals(HttpStatusCode.Unauthorized, replayed.status)
            assertTrue(ErrorCode.INVALID_SIGNATURE in replayed.bodyAsText())
        }
    }

    @Test
    fun `degraded store answers retryable 503 instead of 444`() = runBlocking {
        // 2026-09-08 事故的核心修复：配对存储读不出来时，服务端**不知道**这个 token 认不认识。
        // 此时回 444（"你没配对"）会让客户端清空本地凭据 —— 一次文件损坏放大成全员重新配对。
        // 正确答案是 503 + AUTH_STORE_UNAVAILABLE：保留凭据，退避重试。
        val target = File(context.filesDir, "paired_devices.json")
        // .bak 是第一顺位恢复源，要造"完全读不出来"就得连它一起去掉
        File(context.filesDir, "paired_devices.json.bak").delete()
        File(context.filesDir, "paired_devices.json.corrupt").delete()
        target.writeText("[{\"fingerprint\":\"fp-1\"")   // 截断写留下的半截 JSON
        store.load()
        assertTrue("前置条件：存储应进入降级态", store.degraded)

        testApplication {
            application { configure() }
            val ts = System.currentTimeMillis().toString()
            val resp = client.get("/protected") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(AuthMiddleware.HEADER_TIMESTAMP, ts)
                header(AuthMiddleware.HEADER_NONCE, "nonce-degraded")
                header(AuthMiddleware.HEADER_SIGNATURE, sign("GET", "/protected", ts, "nonce-degraded"))
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
            assertTrue(ErrorCode.AUTH_STORE_UNAVAILABLE in resp.bodyAsText())
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
