package com.ufi_axis_core.controller.carrier.unicom

import com.ufi_axis_core.controller.carrier.CarrierPackageKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 联通 mxx 解析（2026-10-29 真实样本，手机号已脱敏）。 */
class UnicomBillParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): JsonObject {
        val text = javaClass.classLoader!!
            .getResourceAsStream("carrier/unicom/$name.json")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `解析出与 probe 汇总一致的联通账单`() {
        val bill = UnicomBillParser.parse(fixture("package"), fixture("balance"), "13100138000")

        assertEquals("131****8000", bill.phoneMasked)
        assertEquals("联通沃派数字人套餐39元", bill.planName)
        assertEquals(7.12, bill.balanceYuan!!, 0.001)

        assertEquals(17.47, bill.flow.generalUsedGb, 0.001)
        assertEquals(35.42, bill.flow.generalTotalGb, 0.001)
        assertEquals(17.95, bill.flow.generalRemainingGb, 0.001)
        assertEquals(40.00, bill.flow.directedUsedGb, 0.001)
        assertEquals(540.00, bill.flow.directedTotalGb, 0.001)
        assertEquals(500.00, bill.flow.directedRemainingGb, 0.001)
        assertEquals(226.04, bill.flow.regionalUsedGb!!, 0.001)
        assertEquals(400.00, bill.flow.regionalTotalGb!!, 0.001)
        assertEquals(173.96, bill.flow.regionalRemainingGb!!, 0.001)

        // 合计含区域（与联通口径一致）
        assertEquals(283.51, bill.flow.usedGb, 0.001)
        assertEquals(975.42, bill.flow.totalGb, 0.001)
        assertEquals(691.91, bill.flow.remainingGb, 0.001)

        assertEquals(9.0, bill.voice!!.used, 0.001)
        assertEquals(100.0, bill.voice!!.total, 0.001)
        assertEquals(91.0, bill.voice!!.remaining, 0.001)
        assertEquals(0.0, bill.sms!!.used, 0.001)
        assertEquals(0.0, bill.sms!!.total, 0.001)

        assertEquals(5, bill.packages.size)
        val first = bill.packages.first()
        assertEquals(CarrierPackageKind.REGIONAL, first.kind)
        assertEquals("广东沃派专属校区流量加油包400G（立即生效）", first.name)
        assertEquals(173.96, first.remainingGb, 0.001)
        assertEquals(400.00, first.totalGb, 0.001)
        assertEquals("2027-02-28T23:59:59", first.validTo)
        assertNull(bill.lastMonthBillYuan)
        assertNull(bill.currentBillYuan)
        assertNull(bill.monthlyUsedGb)
    }
}
