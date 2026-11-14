package com.ufi_axis_core.api.routes

import com.ufi_axis_core.controller.carrier.CarrierAccountNotFoundException
import com.ufi_axis_core.controller.carrier.CarrierAccountSummary
import com.ufi_axis_core.controller.carrier.CarrierBill
import com.ufi_axis_core.controller.carrier.CarrierBillResult
import com.ufi_axis_core.controller.carrier.CarrierFlow
import com.ufi_axis_core.controller.carrier.CarrierKind
import com.ufi_axis_core.controller.carrier.CarrierService
import com.ufi_axis_core.controller.carrier.CarrierStatus
import com.ufi_axis_core.controller.carrier.CloudCardInfo
import com.ufi_axis_core.controller.carrier.CloudOrderInfo
import com.ufi_axis_core.controller.carrier.UnicomQrPoll
import com.ufi_axis_core.controller.carrier.UnicomQrStart
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 账号维度路由的 HTTP 契约（2026-11-05，P1）。
 *
 * 与 [CarrierRoutesTest] 分开的理由：那边测的是**老接口**（语义 = 当前账号），
 * 这里的 fake 额外实现了账号维度方法。两边都能跑，正好也验证了
 * 「只实现老方法的 fake 仍然编译并工作」—— `CarrierService` 的账号方法全是默认实现。
 */
class CarrierAccountRoutesTest {

    private lateinit var service: FakeAccountService

    @Before
    fun setUp() {
        service = FakeAccountService()
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { CarrierRoutes(service).register(this) }
        }
        block()
    }

    @Test
    fun `账目列表带 active_id`() = app {
        val text = client.get("/carrier/accounts").bodyAsText()
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("h-1", obj["active_id"]?.jsonPrimitive?.content)
        val list = obj["accounts"]!!.jsonArray
        assertEquals(2, list.size)
        assertEquals("主卡", list[0].jsonObject["label"]?.jsonPrimitive?.content)
        assertEquals("ready", list[0].jsonObject["state"]?.jsonPrimitive?.content)
        assertEquals("login_required", list[1].jsonObject["state"]?.jsonPrimitive?.content)
    }

    @Test
    fun `status 响应追加 accounts 字段，老字段不变`() = app {
        val text = client.get("/carrier/status").bodyAsText()
        val obj = Json.parseToJsonElement(text).jsonObject
        // 老字段
        assertEquals("not_configured", obj["state"]?.jsonPrimitive?.content)
        // 新字段
        assertEquals("h-1", obj["active_id"]?.jsonPrimitive?.content)
        assertEquals(2, obj["accounts"]!!.jsonArray.size)
    }

    @Test
    fun `新增账号返回摘要`() = app {
        val text = client.post("/carrier/accounts") {
            contentType(ContentType.Application.Json)
            setBody("""{"carrier":"unicom","phone":"","label":"备用"}""")
        }.bodyAsText()
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("新增", obj["label"]?.jsonPrimitive?.content)
        assertEquals("unicom", service.lastAddedCarrier?.name?.lowercase())
    }

    @Test
    fun `删除账号成功`() = app {
        val response = client.delete("/carrier/accounts/h-1")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(service.removed.contains("h-1"))
    }

    @Test
    fun `改名落到仓库`() = app {
        client.patch("/carrier/accounts/h-1") {
            contentType(ContentType.Application.Json)
            setBody("""{"label":"主力卡"}""")
        }
        assertEquals("h-1" to "主力卡", service.renamed)
    }

    @Test
    fun `账号账单走账号维度`() = app {
        val text = client.get("/carrier/accounts/h-1/bill").bodyAsText()
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("ready", obj["state"]?.jsonPrimitive?.content)
        assertEquals("mobile", obj["bill"]!!.jsonObject["carrier"]?.jsonPrimitive?.content)
        assertEquals("h-1", service.billedAccount)
    }

    @Test
    fun `账号不存在映射 404 而不是 409`() = app {
        // 409 在客户端语义里是「需要登录」，会被 App 弹成登录卡；
        // 账号被删/拼错必须是 404，让它把这张卡标成「已删除」。
        val response = client.get("/carrier/accounts/missing/bill")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `激活账号`() = app {
        val response = client.post("/carrier/accounts/h-2/activate")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("h-2", service.activeAccountId())
    }

    @Test
    fun `新增物联卡账号带 access_method，列表也返回这个维度`() = app {
        val text = client.post("/carrier/accounts") {
            contentType(ContentType.Application.Json)
            setBody("""{"access_method":"iot_card","provider":"xj","card":"1287655","label":"小鲸卡"}""")
        }.bodyAsText()
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("iot_card", obj["access_method"]?.jsonPrimitive?.content)
        assertEquals("xj", obj["iot_provider"]?.jsonPrimitive?.content)
        assertEquals("****7655", obj["phone"]?.jsonPrimitive?.content)
        assertEquals("1287655", service.lastIotCard)
        assertEquals("xj", service.lastIotProvider)
        val listText = client.get("/carrier/accounts").bodyAsText()
        val list = Json.parseToJsonElement(listText).jsonObject["accounts"]!!.jsonArray
        assertEquals("carrier", list[0].jsonObject["access_method"]?.jsonPrimitive?.content)
    }

    @Test
    fun `物联卡卡号登录走 accounts id 路由`() = app {
        val text = client.post("/carrier/accounts/h-iot/login/cloud") {
            contentType(ContentType.Application.Json)
            setBody("""{"card":"1287655","verify":"1614"}""")
        }.bodyAsText()
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("ready", obj["state"]?.jsonPrimitive?.content)
        assertEquals("iot_card", obj["carrier"]?.jsonPrimitive?.content)
        assertEquals("1287655", service.lastIotLoginCard)
        assertEquals("1614", service.lastIotVerify)
    }

    @Test
    fun `物联卡账单返回 cloud_card 与 access_method`() = app {
        val text = client.get("/carrier/accounts/h-iot/bill").bodyAsText()
        val obj = Json.parseToJsonElement(text).jsonObject
        val bill = obj["bill"]!!.jsonObject
        assertEquals("iot_card", bill["access_method"]?.jsonPrimitive?.content)
        assertEquals("iot_card", bill["carrier"]?.jsonPrimitive?.content)
        assertEquals("****7655", bill["cloud_card"]!!.jsonObject["card_no"]?.jsonPrimitive?.content)
        assertEquals("xj", bill["cloud_card"]!!.jsonObject["provider_id"]?.jsonPrimitive?.content)
        assertEquals("小鲸卡", bill["cloud_card"]!!.jsonObject["provider_name"]?.jsonPrimitive?.content)
        assertEquals("正常", bill["cloud_card"]!!.jsonObject["status_text"]?.jsonPrimitive?.content)
        assertEquals("95.558", bill["cloud_card"]!!.jsonObject["real_used_gb"]?.jsonPrimitive?.content)
        assertEquals("200.0", bill["cloud_card"]!!.jsonObject["real_total_gb"]?.jsonPrimitive?.content)
        assertEquals("104.442", bill["cloud_card"]!!.jsonObject["real_remaining_gb"]?.jsonPrimitive?.content)
        assertEquals(1, bill["cloud_card"]!!.jsonObject["orders"]!!.jsonArray.size)
        assertTrue(bill["flow"]!!.jsonObject["general_unlimited"]?.jsonPrimitive?.content == "false")
    }

    @Test
    fun `会话失效但有缓存时仍返回 bill 和需要登录标签`() = app {
        service.forcedSnapshot = service.iotBillSnapshot(
            loginRequired = true,
            stale = true,
            cachedAt = 12_345L,
            error = "小鲸卡会话已失效，请重新登录",
        )
        val text = client.get("/carrier/accounts/h-iot/bill").bodyAsText()
        val obj = Json.parseToJsonElement(text).jsonObject

        assertEquals("login_required", obj["state"]?.jsonPrimitive?.content)
        assertEquals(true, obj["login_required"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, obj["stale"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(12_345L, obj["cached_at"]?.jsonPrimitive?.longOrNull)
        assertEquals("小鲸卡会话已失效，请重新登录", obj["message"]?.jsonPrimitive?.content)
        assertEquals(
            "小鲸卡",
            obj["bill"]!!.jsonObject["cloud_card"]!!.jsonObject["provider_name"]?.jsonPrimitive?.content,
        )
        assertTrue(obj["bill"] != null)
    }

    private class FakeAccountService : CarrierService {
        private var active = "h-1"
        val removed = mutableListOf<String>()
        var renamed: Pair<String, String>? = null
        var billedAccount: String? = null
        var lastAddedCarrier: CarrierKind? = null
        var lastIotCard: String? = null
        var lastIotProvider: String? = null
        var lastIotLoginCard: String? = null
        var lastIotVerify: String? = null
        var forcedSnapshot: CarrierBillResult? = null

        fun iotBillSnapshot(
            loginRequired: Boolean,
            stale: Boolean,
            cachedAt: Long?,
            error: String?,
        ): CarrierBillResult = CarrierBillResult(
            bill = sampleIotBill(),
            loginRequired = loginRequired,
            stale = stale,
            cachedAt = cachedAt,
            error = error,
        )

        private val accounts = mutableListOf(
            CarrierAccountSummary("h-1", CarrierKind.MOBILE, "138****8000", "主卡", "ready", 1),
            CarrierAccountSummary("h-2", CarrierKind.UNICOM, "", "", "login_required", 2),
        )

        // 老接口：只为满足接口，不参与这些用例
        override fun status() = CarrierStatus.NotConfigured
        override suspend fun switchAccount(carrier: CarrierKind, phone: String) = Unit
        override suspend fun requestMobileCode(phone: String): CarrierStatus.CodeSent =
            CarrierStatus.CodeSent("138****8000", 300, 60)
        override suspend fun verifyMobileCode(code: String): CarrierStatus.Ready =
            CarrierStatus.Ready(CarrierKind.MOBILE, "138****8000", null)
        override suspend fun startUnicomQr(force: Boolean) = UnicomQrStart(byteArrayOf(1), 600)
        override suspend fun pollUnicomQr() = UnicomQrPoll("waiting")
        override suspend fun bill(force: Boolean): CarrierBill = sampleBill()
        override fun logout() = Unit

        // 账号维度
        override fun accounts(): List<CarrierAccountSummary> = accounts.toList()
        override fun activeAccountId(): String = active

        override suspend fun activateAccount(accountId: String): CarrierStatus {
            if (accounts.none { it.id == accountId }) throw CarrierAccountNotFoundException()
            active = accountId
            return CarrierStatus.Ready(CarrierKind.MOBILE, "138****8000", null)
        }

        override suspend fun addIotAccount(
            cardNo: String,
            label: String,
            provider: String,
        ): CarrierAccountSummary {
            lastIotCard = cardNo
            lastIotProvider = provider
            return CarrierAccountSummary(
                id = "h-iot",
                carrier = CarrierKind.IOT_CARD,
                phoneMasked = "****7655",
                label = label.ifBlank { "云卡" },
                state = "login_required",
                createdAt = 4,
                accessMethod = "iot_card",
                iotProvider = provider,
            )
        }

        override suspend fun loginCloudCardFor(
            accountId: String,
            cardNo: String,
            verify: String?,
        ): CarrierStatus {
            lastIotLoginCard = cardNo
            lastIotVerify = verify
            return CarrierStatus.Ready(CarrierKind.IOT_CARD, "****7655", null)
        }

        override suspend fun addAccount(
            carrier: CarrierKind,
            phone: String,
            label: String,
        ): CarrierAccountSummary {
            lastAddedCarrier = carrier
            return CarrierAccountSummary("h-new", carrier, phone, "新增", "not_configured", 3)
        }

        override suspend fun renameAccount(accountId: String, label: String) {
            renamed = accountId to label
        }

        override suspend fun removeAccount(accountId: String) {
            removed += accountId
        }

        override fun statusOf(accountId: String): CarrierStatus =
            if (accounts.none { it.id == accountId }) CarrierStatus.NotConfigured
            else CarrierStatus.Ready(CarrierKind.MOBILE, "138****8000", null)

        override suspend fun billSnapshotOf(accountId: String, force: Boolean): CarrierBillResult =
            forcedSnapshot ?: CarrierBillResult(billOf(accountId, force))

        override suspend fun billOf(accountId: String, force: Boolean): CarrierBill {
            if (accountId == "h-iot") {
                billedAccount = accountId
                return sampleIotBill()
            }
            if (accountId != "h-1") throw CarrierAccountNotFoundException()
            billedAccount = accountId
            return sampleBill()
        }

        private fun sampleIotBill() = CarrierBill(
            carrier = CarrierKind.IOT_CARD,
            accessMethod = "iot_card",
            cloudCard = CloudCardInfo(
                cardNoMasked = "****7655",
                providerId = "xj",
                providerName = "小鲸卡",
                iccid = "898608851925D1287655",
                seriesName = "湖北启源移动",
                expiresAt = "2026-11-30 23:59:59",
                daysLeft = 22,
                statusText = "正常",
                realNameText = "已实名",
                realUsedGb = 95.558,
                realTotalGb = 200.0,
                realRemainingGb = 104.442,
                orders = listOf(
                    CloudOrderInfo(
                        name = "500G套餐包",
                        amountYuan = 23.5,
                        paidYuan = 23.5,
                        statusText = "成功",
                        payTypeText = "支付宝",
                        createdAt = "2026-11-06 20:23:46",
                        effectiveTime = "2026-11-06 20:22:18",
                        expirationTime = "2026-11-30 23:59:59",
                    )
                ),
                refreshedAt = "2026-11-08 11:50:59",
            ),
            phoneMasked = "****7655",
            planName = "500G套餐包",
            ownerName = null,
            tenureMonths = null,
            starLevel = null,
            balanceYuan = 0.0,
            lastMonthBillYuan = null,
            currentBillYuan = null,
            currentBillPeriod = null,
            currentBillAsOf = null,
            points = null,
            flow = CarrierFlow(
                generalUsedGb = 238.897,
                generalTotalGb = 500.0,
                generalRemainingGb = 261.103,
                directedUsedGb = 0.0,
                directedTotalGb = 0.0,
                directedRemainingGb = 0.0,
            ),
            monthlyUsedGb = null,
            voice = null,
            sms = null,
            packages = emptyList(),
            warnings = emptyList(),
        )

        private fun sampleBill() = CarrierBill(
            carrier = CarrierKind.MOBILE,
            phoneMasked = "138****8000",
            planName = "8元自由选套餐",
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
            packages = emptyList(),
            warnings = emptyList(),
        )
    }
}
