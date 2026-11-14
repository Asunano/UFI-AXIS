package com.ufi_axis_core.api.routes

import com.ufi_axis_core.controller.carrier.CarrierBill
import com.ufi_axis_core.controller.carrier.CarrierCodeExpiredException
import com.ufi_axis_core.controller.carrier.CarrierFlow
import com.ufi_axis_core.controller.carrier.CarrierKind
import com.ufi_axis_core.controller.carrier.CarrierLoginRequiredException
import com.ufi_axis_core.controller.carrier.CarrierPackage
import com.ufi_axis_core.controller.carrier.CarrierPackageKind
import com.ufi_axis_core.controller.carrier.CarrierRateLimitedException
import com.ufi_axis_core.controller.carrier.CarrierService
import com.ufi_axis_core.controller.carrier.CarrierStatus
import com.ufi_axis_core.controller.carrier.UnicomQrPoll
import com.ufi_axis_core.controller.carrier.UnicomQrStart
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `CarrierRoutes` 的 HTTP 契约（2026-10-31）。
 *
 * 不构造 `CarrierBillService`（它要 Android Context/Keystore），用 fake 只验证：
 * 409/429/400 映射、账单 JSON 形状、二维码 base64。
 */
class CarrierRoutesTest {

    private lateinit var service: FakeCarrierService

    @Before
    fun setUp() {
        service = FakeCarrierService()
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { CarrierRoutes(service).register(this) }
        }
        block()
    }

    @Test
    fun `status 返回未配置`() = app {
        val text = client.get("/carrier/status").bodyAsText()
        assertEquals("not_configured", Json.parseToJsonElement(text).jsonObject["state"]?.jsonPrimitive?.content)
    }

    @Test
    fun `发码成功返回 code_sent`() = app {
        val text = client.post("/carrier/mobile/login/request") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"13800138000"}""")
        }.bodyAsText()
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("code_sent", obj["state"]?.jsonPrimitive?.content)
        assertEquals("138****8000", obj["phone"]?.jsonPrimitive?.content)
        assertEquals("300", obj["expires_in"]?.jsonPrimitive?.content)
    }

    @Test
    fun `验证码错误映射 409`() = app {
        service.verifyError = CarrierCodeExpiredException()
        val response = client.post("/carrier/mobile/login/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"123456"}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("CARRIER_CODE_EXPIRED"))
    }

    @Test
    fun `未登录账单映射 409`() = app {
        service.billError = CarrierLoginRequiredException()
        val response = client.get("/carrier/bill")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("CARRIER_LOGIN_REQUIRED"))
    }

    @Test
    fun `限流映射 429`() = app {
        service.requestError = CarrierRateLimitedException(42)
        val response = client.post("/carrier/mobile/login/request") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"13800138000"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertTrue(response.bodyAsText().contains("retry_after"))
    }

    @Test
    fun `账单 JSON 形状`() = app {
        val text = client.get("/carrier/bill").bodyAsText()
        val obj = Json.parseToJsonElement(text).jsonObject
        val bill = obj["bill"]?.jsonObject
        assertEquals("mobile", bill?.get("carrier")?.jsonPrimitive?.content)
        assertEquals("138****8000", bill?.get("phone")?.jsonPrimitive?.content)
        assertEquals("23.82", bill?.get("balance_yuan")?.jsonPrimitive?.content)
        val flow = bill?.get("flow")?.jsonObject
        assertEquals("1.73", flow?.get("general_remaining_gb")?.jsonPrimitive?.content)
        val packages = bill?.get("packages")?.let { Json.parseToJsonElement(it.toString()) }
        assertTrue(packages != null)
    }

    @Test
    fun `联通二维码返回 base64`() = app {
        val text = client.post("/carrier/unicom/login/qr") {
        }.bodyAsText()
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("qr_pending", obj["state"]?.jsonPrimitive?.content)
        assertEquals("AQID", obj["qr_png_base64"]?.jsonPrimitive?.content)
    }

    @Test
    fun `联通轮询 waiting`() = app {
        val text = client.get("/carrier/unicom/login/status").bodyAsText()
        assertEquals("waiting", Json.parseToJsonElement(text).jsonObject["state"]?.jsonPrimitive?.content)
    }

    @Test
    fun `换号参数错误映射 400`() = app {
        val response = client.post("/carrier/account") {
            contentType(ContentType.Application.Json)
            setBody("""{"carrier":"foo","phone":"13800138000"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    private class FakeCarrierService : CarrierService {
        var billError: Throwable? = null
        var requestError: Throwable? = null
        var verifyError: Throwable? = null

        override fun status() = CarrierStatus.NotConfigured

        override suspend fun switchAccount(carrier: CarrierKind, phone: String) = Unit

        override suspend fun requestMobileCode(phone: String): CarrierStatus.CodeSent {
            requestError?.let { throw it }
            return CarrierStatus.CodeSent("138****8000", 300, 60)
        }

        override suspend fun verifyMobileCode(code: String): CarrierStatus.Ready {
            verifyError?.let { throw it }
            return CarrierStatus.Ready(CarrierKind.MOBILE, "138****8000", null)
        }

        override suspend fun startUnicomQr(force: Boolean) = UnicomQrStart(byteArrayOf(1, 2, 3), 600)

        override suspend fun pollUnicomQr() = UnicomQrPoll("waiting")

        override suspend fun bill(force: Boolean): CarrierBill {
            billError?.let { throw it }
            return CarrierBill(
                carrier = CarrierKind.MOBILE,
                phoneMasked = "138****8000",
                planName = "8元自由选套餐（100MB流量）（其它品牌）",
                ownerName = "张*",
                tenureMonths = 61,
                starLevel = 1.0,
                balanceYuan = 23.82,
                lastMonthBillYuan = 34.87,
                currentBillYuan = 11.05,
                currentBillPeriod = "202610",
                currentBillAsOf = "2026-10-31",
                points = 152,
                flow = CarrierFlow(0.46, 2.20, 1.73, 0.95, 110.0, 109.05),
                monthlyUsedGb = 34.04,
                voice = null,
                sms = null,
                packages = listOf(
                    CarrierPackage(CarrierPackageKind.GENERAL, "国内通用流量100MB", 0.10, 0.10, "2026-10-05T00:00:00", "2026-12-05T00:00:00")
                ),
                warnings = emptyList(),
            )
        }

        override fun logout() = Unit
    }
}
