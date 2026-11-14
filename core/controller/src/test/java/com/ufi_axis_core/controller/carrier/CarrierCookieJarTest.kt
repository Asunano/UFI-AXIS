package com.ufi_axis_core.controller.carrier

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import com.ufi_axis_core.controller.carrier.mobile.MobileCarrierClient

/** Cookie 持久化的两个关键点：快照能回放、client 真的把 CookieJar 挂上了 OkHttp。 */
class CarrierCookieJarTest {

    @Test
    fun `snapshot 后 restore 仍能匹配原域名`() {
        val url = "https://h.app.coc.10086.cn/".toHttpUrl()
        val jar = CarrierCookieJar()
        jar.saveFromResponse(
            url,
            listOf(Cookie.Builder().name("SESSION").value("abc").domain("h.app.coc.10086.cn").path("/").build())
        )
        assertEquals("abc", jar.value("SESSION"))

        val restored = CarrierCookieJar().apply { restore(jar.snapshot()) }
        assertEquals("abc", restored.value("SESSION"))
        assertEquals(1, restored.loadForRequest(url).size)
    }

    @Test
    fun `domain cookie 快照恢复时去掉前导点`() {
        val jar = CarrierCookieJar()
        // 联通的 piw / JUT 是域 Cookie，生产里常带 .10010.com。
        // Cookie.Builder.domain() 不接受前导点；快照/恢复必须先归一化。
        jar.putRaw("JUT", "abc", ".10010.com")
        assertEquals("abc", jar.value("JUT"))
        assertEquals("10010.com", jar.snapshot().single().domain)

        val restored = CarrierCookieJar().apply { restore(jar.snapshot()) }
        assertEquals("abc", restored.value("JUT"))
        assertEquals(
            1,
            restored.loadForRequest("https://m.client.10010.com/".toHttpUrl()).size,
        )
    }

    @Test
    fun `MobileCarrierClient 会把响应 Cookie 存进 CookieJar`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Set-Cookie", "SESSION=abc; Path=/")
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val old = chain.request()
                val url = old.url.newBuilder()
                    .scheme("http")
                    .host("127.0.0.1")
                    .port(server.address.port)
                    .build()
                chain.proceed(old.newBuilder().url(url).build())
            }.build()
            val client = MobileCarrierClient(http = http)
            runCatching { runBlocking { client.sendCode("13800138000") } }
            assertEquals("abc", client.cookieJar.value("SESSION"))
        } finally {
            server.stop(0)
        }
    }
}
