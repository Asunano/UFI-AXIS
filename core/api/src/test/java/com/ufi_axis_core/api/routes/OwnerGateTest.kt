package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.pairing.OwnerAuth
import io.ktor.client.request.delete as httpDelete
import io.ktor.client.request.header
import io.ktor.client.request.post as httpPost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主人密码门（`OwnerGate`）的判定测试。
 *
 * 要锁住的核心事实只有两条：
 *
 * 1. **开关关着时一个字节都不多要** —— 这是本版与 2026-10-30 那版（40 个端点全覆盖）的
 *    关键区别，用户反馈「装个程序也要输密码」正是从那里来的。默认关且关着时放行，
 *    任何「顺手在校验里加一层」的改动都会让这条红。
 * 2. **设备没设过配对密码时也放行** —— 否则全新设备第一次恢复出厂就把自己锁在门外。
 *
 * 认证失败的三态（缺密码 / 密码错 / 被锁定）与 0.1.0 的 `ConfigRoutes./reset` 逐字一致：
 * 401 `MISSING_PASSWORD` / 401 `INVALID_PASSWORD` / 429 `PASSWORD_LOCKED`。
 */
class OwnerGateTest {

    private var enabled = false
    private var passwordSet = true
    private var verifyResult: OwnerAuth = OwnerAuth.Ok
    private var throttleMs = 0L

    /** 最近一次过门时拿到的密码与 IP（断言「取的是 body / 头里的那个字段」）。 */
    private var seenPassword: String? = null
    private var verifiedCount = 0

    @After
    fun tearDown() {
        OwnerGate.resetForTests()
    }

    private fun installGate() {
        OwnerGate.install(
            enabled = { enabled },
            passwordSet = { passwordSet },
            verify = { password, _ ->
                verifiedCount++
                seenPassword = password
                verifyResult
            },
            throttleDelayMs = { throttleMs },
        )
    }

    private fun Application.mountGate() {
        install(ContentNegotiation) { json() }
        routing {
            post("/gated-body") {
                val body = runCatching { call.receiveJsonObject() }.getOrNull()
                if (!call.requireOwner(body.ownerPassword())) return@post
                call.respond("ok")
            }
            delete("/gated-header") {
                if (!call.requireOwnerFromHeader()) return@delete
                call.respond("ok")
            }
            post("/echo-raw") {
                call.respond(call.receiveText())
            }
        }
    }

    @Test
    fun `开关关着时不需要密码`() {
        enabled = false
        passwordSet = true
        installGate()
        testApplication {
            application { mountGate() }
            val res: HttpResponse = client.httpPost("/gated-body") {
                contentType(ContentType.Application.Json)
                setBody("""{"foo":1}""")
            }
            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals(0, verifiedCount)
        }
    }

    @Test
    fun `开关打开但设备没设过密码时放行`() {
        enabled = true
        passwordSet = false
        installGate()
        testApplication {
            application { mountGate() }
            val res = client.httpPost("/gated-body") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals(0, verifiedCount)
        }
    }

    @Test
    fun `开关打开且设过密码时缺密码返回 401 MISSING_PASSWORD`() {
        enabled = true
        passwordSet = true
        verifyResult = OwnerAuth.Missing
        installGate()
        testApplication {
            application { mountGate() }
            val res = client.httpPost("/gated-body") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertTrue(res.bodyAsText(), res.bodyAsText().contains("MISSING_PASSWORD"))
        }
    }

    @Test
    fun `密码错误返回 401 INVALID_PASSWORD`() {
        enabled = true
        passwordSet = true
        verifyResult = OwnerAuth.Wrong
        installGate()
        testApplication {
            application { mountGate() }
            val res = client.httpPost("/gated-body") {
                contentType(ContentType.Application.Json)
                setBody("""{"password":"nope"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertTrue(res.bodyAsText().contains("INVALID_PASSWORD"))
            assertEquals("nope", seenPassword)
        }
    }

    @Test
    fun `尝试次数过多返回 429 PASSWORD_LOCKED`() {
        enabled = true
        passwordSet = true
        verifyResult = OwnerAuth.Locked
        installGate()
        testApplication {
            application { mountGate() }
            val res = client.httpPost("/gated-body") {
                contentType(ContentType.Application.Json)
                setBody("""{"password":"nope"}""")
            }
            assertEquals(HttpStatusCode.TooManyRequests, res.status)
            assertTrue(res.bodyAsText().contains("PASSWORD_LOCKED"))
        }
    }

    @Test
    fun `密码正确时放行且把 body 里的 password 交给校验器`() {
        enabled = true
        passwordSet = true
        verifyResult = OwnerAuth.Ok
        installGate()
        testApplication {
            application { mountGate() }
            val res = client.httpPost("/gated-body") {
                contentType(ContentType.Application.Json)
                setBody("""{"password":"secret123"}""")
            }
            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals("secret123", seenPassword)
        }
    }

    @Test
    fun `请求头取密码的端点同样受开关控制`() {
        enabled = true
        passwordSet = true
        verifyResult = OwnerAuth.Ok
        installGate()
        testApplication {
            application { mountGate() }
            // 无头 → 校验器拿到 null → Missing
            verifyResult = OwnerAuth.Missing
            val missing = client.httpDelete("/gated-header")
            assertEquals(HttpStatusCode.Unauthorized, missing.status)
            assertTrue(missing.bodyAsText().contains("MISSING_PASSWORD"))

            // 带头 → 校验器拿到头里的值
            verifyResult = OwnerAuth.Ok
            val ok = client.httpDelete("/gated-header") {
                header(HEADER_OWNER_PASSWORD, "secret123")
            }
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals("secret123", seenPassword)
        }
    }

    @Test
    fun `body 没带密码时回落到 X-Owner-Password 头`() {
        // 客户端的统一重试路径只加这个头，不改请求体形状（端点 body 可能是 JSON、也可能是文件流）。
        enabled = true
        passwordSet = true
        verifyResult = OwnerAuth.Ok
        installGate()
        testApplication {
            application { mountGate() }
            val res = client.httpPost("/gated-body") {
                contentType(ContentType.Application.Json)
                header(HEADER_OWNER_PASSWORD, "secret123")
                setBody("{\"foo\":1}")
            }
            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals("secret123", seenPassword)
        }
    }

    @Test
    fun `body 里的密码优先于请求头`() {
        enabled = true
        passwordSet = true
        verifyResult = OwnerAuth.Ok
        installGate()
        testApplication {
            application { mountGate() }
            val res = client.httpPost("/gated-body") {
                contentType(ContentType.Application.Json)
                header(HEADER_OWNER_PASSWORD, "from-header")
                setBody("{\"password\":\"from-body\"}")
            }
            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals("from-body", seenPassword)
        }
    }

    @Test
    fun `isActive 只在开关打开且设过密码时为真`() {
        installGate()
        enabled = false; passwordSet = true
        assertFalse(OwnerGate.isActive())
        enabled = true; passwordSet = false
        assertFalse(OwnerGate.isActive())
        enabled = true; passwordSet = true
        assertTrue(OwnerGate.isActive())
    }

    @Test
    fun `未装配时视为关闭且放行`() {
        OwnerGate.resetForTests()
        assertFalse(OwnerGate.isInstalled)
        assertFalse(OwnerGate.isActive())
        testApplication {
            application { mountGate() }
            val res = client.httpPost("/gated-body") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.OK, res.status)
        }
    }
}
