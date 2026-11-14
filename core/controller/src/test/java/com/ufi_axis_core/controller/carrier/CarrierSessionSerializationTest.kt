package com.ufi_axis_core.controller.carrier

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** 会话落盘前的 JSON 形状必须能原样读回；字段改名/漏 @Serializable 会先在这里红。 */
class CarrierSessionSerializationTest {

    @Test
    fun `session 与 cookie 可以完整往返`() {
        val json = Json { ignoreUnknownKeys = true }
        val session = StoredCarrierSession(
            carrier = "mobile",
            phone = "13800138000",
            cookies = listOf(
                CarrierCookie(
                    name = "SESSION",
                    value = "abc",
                    domain = "h.app.coc.10086.cn",
                    path = "/",
                    expiresAt = -1,
                    secure = true,
                    httpOnly = true,
                )
            ),
            savedAt = 123,
            lastFetchedAt = 456,
        )
        val text = json.encodeToString(StoredCarrierSession.serializer(), session)
        val back = json.decodeFromString(StoredCarrierSession.serializer(), text)
        assertEquals(session, back)
    }
}
