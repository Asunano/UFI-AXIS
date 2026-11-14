package com.ufi_axis_core.controller.carrier.cloud

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * 小鲸卡登录协议（2026-11-09）：第一步不带 verify 时，上游返回 HTTP 400 + code=27，
 * 客户端必须把它翻译成 [CloudCardNeedVerifyException]，而不是普通 API 失败。
 *
 * 用本地 HttpServer 而不是 MockWebServer：项目没有 mockwebserver 依赖，而这条
 * 只依赖「HTTP 400 也读 body」这一点，JDK 自带的 server 足够。
 */
class CloudCardClientVerifyTest {

    @Test
    fun `code 27 抛 NeedVerify 并带验证方式与脱敏手机号`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/app/card/login") { exchange ->
            val body = """
                {"code":27,"status":false,"msg":"登录需要验证","data":{"type":2,"phone":"1367250****"}}
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json;charset=UTF-8")
            exchange.sendResponseHeaders(400, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = CloudCardClient(baseUrl = "http://127.0.0.1:${server.address.port}")
            val error = runCatching { client.login("90506305") }.exceptionOrNull()

            assertTrue("应抛 NeedVerify，实际 $error", error is CloudCardNeedVerifyException)
            val need = error as CloudCardNeedVerifyException
            assertEquals(2, need.verifyType)
            assertEquals("1367250****", need.maskedPhone)
        } finally {
            server.stop(0)
        }
    }
}
