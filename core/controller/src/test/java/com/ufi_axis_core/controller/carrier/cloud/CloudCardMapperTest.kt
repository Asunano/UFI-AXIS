package com.ufi_axis_core.controller.carrier.cloud

import com.ufi_axis_core.controller.carrier.CarrierKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 云上卡字段口径（2026-11-08）。
 *
 * 这些断言直接对应接入文档里最容易踩的几条：
 * - MB → GB ÷1024；
 * - `free=null` 是不限量，不是 0；
 * - `state=0` 正常、`officialRealName=0` 已实名；
 * - 总量 = used + free（不是 flowVirtual）；
 * - 套餐的 total/used 用 flowVirtual / flowUsedVirtual，剩余自己相减。
 */
class CloudCardMapperTest {

    private val now = LocalDateTime.of(2026, 11, 8, 12, 0)

    private fun cardJson(
        used: Double = 244630.73,
        free: Double? = 267369.27,
        state: Int = 0,
        realName: Int = 0,
    ) = Json.parseToJsonElement(
        """
        {
          "card": "1287655",
          "iccid": "898608851925D1287655",
          "expirationTime": "2026-11-30 23:59:59",
          "used": $used,
          ${if (free == null) "\"free\": null," else "\"free\": $free,"}
          "realUsed": 97852.29,
          "state": $state,
          "officialRealName": $realName,
          "balance": 0.0,
          "flowRefreshTime": "2026-11-08 11:50:59"
        }
        """.trimIndent()
    ).jsonObject

    private val plansJson = Json.parseToJsonElement(
        """
        {
          "list": [
            {
              "setMealName": "500G套餐包",
              "flowVirtual": 512000.0,
              "flowUsedVirtual": 244630.73,
              "effectiveTime": "2026-11-06 20:22:18",
              "expirationTime": "2026-11-30 23:59:59",
              "card": { "series": { "name": "湖北启源移动" } }
            }
          ]
        }
        """.trimIndent()
    ).jsonObject

    @Test
    fun `MB to GB and state dictionaries`() {
        val snapshot = cardJson().toSnapshot()
        val plans = plansJson["list"]!!.let { list ->
            list.toString().let { Json.parseToJsonElement(it) }
        }
        // 上面的 list 是 JsonArray，直接复用 parser 里的 asArray 逻辑
        val planList = (plans as kotlinx.serialization.json.JsonArray)
            .mapNotNull { it.jsonObject.toPlan() }
        val bill = mapCloudCardBill(
            series = CloudSeries(name = "移动", showName = "移动"),
            snapshot = snapshot,
            plans = planList,
            realOrders = listOf(
                CloudRealOrder(
                    name = "500G套餐包",
                    flowRealMb = 204800.0,
                    flowUsedMb = 97852.29,
                    effectiveType = 1,
                    state = 2,
                    expirationTime = "2026-11-30 23:59:59",
                )
            ),
            orders = listOf(
                CloudOrder(
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
            now = now,
        )

        assertEquals(CarrierKind.IOT_CARD, bill.carrier)
        assertEquals("iot_card", bill.accessMethod)
        assertEquals(238.897, bill.flow.generalUsedGb, 0.001)
        assertEquals(500.0, bill.flow.generalTotalGb, 0.001)
        assertEquals(261.103, bill.flow.generalRemainingGb, 0.001)
        assertTrue(!bill.flow.generalUnlimited)
        assertEquals("正常", bill.cloudCard?.statusText)
        assertEquals("已实名", bill.cloudCard?.realNameText)
        assertEquals(22, bill.cloudCard?.daysLeft)
        assertEquals("湖北启源移动", bill.cloudCard?.seriesName)
        assertEquals(1, bill.packages.size)
        assertEquals("500G套餐包", bill.packages[0].name)
        assertEquals(500.0, bill.packages[0].totalGb, 0.001)
        assertEquals(261.103, bill.packages[0].remainingGb, 0.001)
        assertEquals("2026-11-06T20:22:18", bill.packages[0].validFrom)
        assertEquals("2026-11-30T23:59:59", bill.packages[0].validTo)
        assertEquals("****7655", bill.phoneMasked)
        assertEquals(95.558, bill.cloudCard?.realUsedGb ?: 0.0, 0.01)
        assertEquals(1, bill.cloudCard?.orders?.size)
        assertEquals("成功", bill.cloudCard?.orders?.first()?.statusText)
        assertEquals(200.0, bill.cloudCard?.realTotalGb ?: 0.0, 0.01)
        assertEquals(104.442, bill.cloudCard?.realRemainingGb ?: 0.0, 0.01)
    }

    @Test
    fun `free null is unlimited not zero`() {
        val bill = mapCloudCardBill(
            series = null,
            snapshot = cardJson(free = null).toSnapshot(),
            plans = emptyList(),
            now = now,
        )
        assertTrue("free=null 必须标记不限量", bill.flow.generalUnlimited)
        assertEquals(238.897, bill.flow.generalUsedGb, 0.001)
        assertEquals(238.897, bill.flow.generalTotalGb, 0.001)
        assertEquals(0.0, bill.flow.generalRemainingGb, 0.001)
        assertNull(bill.cloudCard?.seriesName)
    }

    @Test
    fun `state zero is normal and realname zero is verified`() {
        val bill = mapCloudCardBill(
            series = null,
            snapshot = cardJson(state = 0, realName = 0).toSnapshot(),
            plans = emptyList(),
            now = now,
        )
        assertEquals("正常", bill.cloudCard?.statusText)
        assertEquals("已实名", bill.cloudCard?.realNameText)
    }

    @Test
    fun `stopped card maps to stopped text`() {
        val bill = mapCloudCardBill(
            series = null,
            snapshot = cardJson(state = 4, realName = 1).toSnapshot(),
            plans = emptyList(),
            now = now,
        )
        assertEquals("已停机", bill.cloudCard?.statusText)
        assertEquals("未实名", bill.cloudCard?.realNameText)
    }

    @Test
    fun `provider is carried into the bill`() {
        val bill = mapCloudCardBill(
            series = null,
            snapshot = cardJson().toSnapshot(),
            plans = emptyList(),
            provider = IotProvider.XJ,
            now = now,
        )
        assertEquals("xj", bill.cloudCard?.providerId)
        assertEquals("小鲸卡", bill.cloudCard?.providerName)
    }
}
