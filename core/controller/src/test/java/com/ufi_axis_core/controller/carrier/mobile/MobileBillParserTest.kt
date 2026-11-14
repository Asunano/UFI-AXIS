package com.ufi_axis_core.controller.carrier.mobile

import com.ufi_axis_core.controller.carrier.CarrierPackageKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 移动 App 域解析（2026-10-31 真实样本，已脱敏）。
 *
 * 样本来自 `tools/carrier-probe/out/mobile-1614-20261031-012140/raw/`；
 * 手机号/机主/分公司已替换，结构与数值未改。
 */
class MobileBillParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): JsonObject {
        val text = javaClass.classLoader!!
            .getResourceAsStream("carrier/mobile/$name.json")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return json.parseToJsonElement(text).jsonObject
    }

    private fun sample(): Map<String, JsonObject> = mapOf(
        "app-plan" to fixture("app-plan"),
        "app-fee" to fixture("app-fee"),
        "app-user" to fixture("app-user"),
        "app-planq" to fixture("app-planq"),
        "app-bills" to fixture("app-bills"),
        "app-points" to fixture("app-points"),
        "app-flow" to fixture("app-flow"),
        "app-accout" to fixture("app-accout"),
    )

    @Test
    fun `解析出与 probe 汇总一致的账单`() {
        val bill = MobileBillParser.parse(sample(), "13800138000")

        assertEquals("138****8000", bill.phoneMasked)
        assertEquals("8元自由选套餐（100MB流量）（其它品牌）", bill.planName)
        assertEquals("张*", bill.ownerName)
        assertEquals(61, bill.tenureMonths)
        assertEquals(1.0, bill.starLevel!!, 0.001)
        assertEquals(23.82, bill.balanceYuan!!, 0.001)
        assertEquals(34.87, bill.lastMonthBillYuan!!, 0.001)
        assertEquals(11.05, bill.currentBillYuan!!, 0.001)
        assertEquals("202610", bill.currentBillPeriod)
        assertEquals("2026-10-31", bill.currentBillAsOf)
        assertEquals(152, bill.points)

        assertEquals(0.46, bill.flow.generalUsedGb, 0.001)
        assertEquals(2.20, bill.flow.generalTotalGb, 0.001)
        assertEquals(1.73, bill.flow.generalRemainingGb, 0.001)
        assertEquals(0.95, bill.flow.directedUsedGb, 0.001)
        assertEquals(110.00, bill.flow.directedTotalGb, 0.001)
        assertEquals(109.05, bill.flow.directedRemainingGb, 0.001)
        assertEquals(1.41, bill.flow.usedGb, 0.001)
        assertEquals(112.20, bill.flow.totalGb, 0.001)
        assertEquals(110.78, bill.flow.remainingGb, 0.001)
        assertEquals(34.04, bill.monthlyUsedGb!!, 0.001)

        assertEquals(7, bill.packages.size)
        val first = bill.packages.first()
        assertEquals(CarrierPackageKind.GENERAL, first.kind)
        assertEquals(0.00, first.remainingGb, 0.001)
        assertEquals(0.10, first.totalGb, 0.001)
        assertEquals("2026-10-05T00:00:00", first.validFrom)
        assertEquals("2026-11-05T00:00:00", first.validTo)

        assertEquals(3, bill.packages.count { it.kind == CarrierPackageKind.DIRECTED })
        assertEquals(110.78, bill.packages.sumOf { it.remainingGb }, 0.001)
        assertEquals(112.20, bill.packages.sumOf { it.totalGb }, 0.001)
        assertNull(bill.voice)
        assertNull(bill.sms)
    }

    @Test
    fun `单个接口失败只进 warnings`() {
        val raw = sample().toMutableMap()
        raw["app-points"] = json.parseToJsonElement("""{"__error":"timeout"}""").jsonObject

        val bill = MobileBillParser.parse(raw, "13800138000")

        assertNotNull(bill.balanceYuan)
        assertEquals(7, bill.packages.size)
        assertEquals(true, bill.warnings.any { it.contains("app-points") })
    }
}
