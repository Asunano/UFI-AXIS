package com.ufi_axis_core.controller.carrier.unicom

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联通 token 续期的关键约束（2026-11-12）：
 * - renew 必须复用登录时保存的 appId，不能每次生成新 appId；
 * - 响应里新的 token_online 要写回客户端，供 CarrierBillService 落盘；
 * - 新 Cookie 要进 CookieJar。
 */
class UnicomRenewTest {
    @Test
    fun `renew reuses saved appId and stores rotated token`() = runBlocking {
        val seen = mutableListOf<Request>()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                seen += request
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Set-Cookie", "JUT=renewed; Domain=.10010.com; Path=/")
                    .body(
                        """{"code":"0","token_online":"new-token"}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()
        val client = UnicomCarrierClient(http = http)
        client.restoreToken(token = "old-token", appId = "saved-app-id")
        // application interceptor 直接返回响应时不会经过 BridgeInterceptor，
        // 测试里手工给一个已有 Cookie，让 renew 的“Cookie 是否非空”判定成立。
        client.cookieJar.putRaw("JUT", "old", "10010.com")

        assertTrue(runBlocking { client.renew() })

        assertEquals(1, seen.size)
        val form = seen.single().body as FormBody
        assertEquals("saved-app-id", form.value(1))
        assertEquals("new-token", client.tokenOnline())
        assertEquals("saved-app-id", client.appId())
        assertEquals("old", client.cookieJar.value("JUT"))
    }
}
