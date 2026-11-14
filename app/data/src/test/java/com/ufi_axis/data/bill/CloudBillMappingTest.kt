package com.ufi_axis.data.bill

import com.ufi_axis.data.model.CarrierBillDto
import com.ufi_axis.data.model.CarrierFlowDto
import com.ufi_axis.data.model.CarrierPackageDto
import com.ufi_axis.data.model.CloudCardDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** core 云上卡 JSON → BillSnapshot 的映射（2026-11-08）。 */
class CloudBillMappingTest {

    @Test
    fun `iot card maps to cloud snapshot without carrier-only fields`() {
        val dto = CarrierBillDto(
            carrier = "iot_card",
            accessMethod = "iot_card",
            cloudCard = CloudCardDto(
                cardNo = "****7655",
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
                    com.ufi_axis.data.model.CloudOrderDto(
                        name = "500G套餐包",
                        amountYuan = 23.5,
                        paidYuan = 23.5,
                        statusText = "成功",
                        payTypeText = "支付宝",
                        createdAt = "2026-11-06 20:23:46",
                        expirationTime = "2026-11-30 23:59:59",
                    )
                ),
                refreshedAt = "2026-11-08 11:50:59",
            ),
            phone = "****7655",
            planName = "500G套餐包",
            balanceYuan = 0.0,
            flow = CarrierFlowDto(
                generalUsedGb = 238.897,
                generalTotalGb = 500.0,
                generalRemainingGb = 261.103,
                directedUsedGb = 0.0,
                directedTotalGb = 0.0,
                directedRemainingGb = 0.0,
                generalUnlimited = false,
            ),
            packages = listOf(
                CarrierPackageDto(
                    kind = "general",
                    name = "500G套餐包",
                    remainingGb = 261.103,
                    totalGb = 500.0,
                    validFrom = "2026-11-06T20:22:18",
                    validTo = "2026-11-30T23:59:59",
                )
            ),
        )

        val bill = dto.toBillSnapshot(emptyList())

        assertEquals("云上卡", bill.carrier)
        assertEquals(BillCarrier.IOT_CARD, bill.carrierKind)
        assertEquals(BillAccessMethod.IOT_CARD, bill.accessMethod)
        assertEquals("****7655", bill.phone)
        assertEquals("湖北启源移动", bill.cloudCard?.seriesName)
        assertEquals(22, bill.cloudCard?.daysLeft)
        assertEquals("正常", bill.cloudCard?.statusText)
        assertEquals("已实名", bill.cloudCard?.realNameText)
        assertEquals(95.558, bill.cloudCard?.realUsedGb ?: 0.0, 0.01)
        assertEquals(200.0, bill.cloudCard?.realTotalGb ?: 0.0, 0.01)
        assertEquals(104.442, bill.cloudCard?.realRemainingGb ?: 0.0, 0.01)
        assertEquals(1, bill.cloudCard?.orders?.size)
        assertEquals("成功", bill.cloudCard?.orders?.first()?.statusText)
        assertEquals(238.897, bill.flow.generalUsedGb, 0.001)
        assertEquals(500.0, bill.flow.generalTotalGb, 0.001)
        assertEquals(261.103, bill.flow.generalRemainingGb, 0.001)
        assertTrue(!bill.flow.generalUnlimited)
        assertEquals(1, bill.packages.size)
        assertNull(bill.currentBillYuan)
        assertNull(bill.voice)
        assertNull(bill.sms)
    }

    @Test
    fun `unlimited flag survives mapping`() {
        val dto = CarrierBillDto(
            carrier = "iot_card",
            accessMethod = "iot_card",
            phone = "****7655",
            flow = CarrierFlowDto(
                generalUsedGb = 10.0,
                generalTotalGb = 10.0,
                generalRemainingGb = 0.0,
                directedUsedGb = 0.0,
                directedTotalGb = 0.0,
                directedRemainingGb = 0.0,
                generalUnlimited = true,
            ),
        )
        assertTrue(dto.toBillSnapshot(emptyList()).flow.generalUnlimited)
    }

    @Test
    fun `login required cached bill keeps data and flags`() {
        val dto = CarrierBillDto(
            carrier = "iot_card",
            accessMethod = "iot_card",
            cloudCard = CloudCardDto(
                cardNo = "****6305",
                providerId = "xj",
                providerName = "小鲸卡",
                statusText = "正常",
            ),
            phone = "****6305",
            flow = CarrierFlowDto(
                generalUsedGb = 0.649,
                generalTotalGb = 1000.0,
                generalRemainingGb = 999.351,
                directedUsedGb = 0.0,
                directedTotalGb = 0.0,
                directedRemainingGb = 0.0,
            ),
        )

        val bill = dto.toBillSnapshot(
            warnings = emptyList(),
            loginRequired = true,
            cachedAt = 123L,
            stale = true,
        )

        assertEquals("小鲸卡", bill.carrier)
        assertTrue(bill.loginRequired)
        assertTrue(bill.stale)
        assertEquals(123L, bill.cachedAt)
        assertEquals(1000.0, bill.flow.generalTotalGb, 0.001)
    }

    @Test
    fun `old carrier json defaults to carrier access`() {
        val dto = CarrierBillDto(
            carrier = "mobile",
            phone = "138****8000",
            flow = CarrierFlowDto(
                generalUsedGb = 1.0,
                generalTotalGb = 2.0,
                generalRemainingGb = 1.0,
                directedUsedGb = 0.0,
                directedTotalGb = 0.0,
                directedRemainingGb = 0.0,
            ),
        )
        val bill = dto.toBillSnapshot(emptyList())
        assertEquals(BillAccessMethod.CARRIER, bill.accessMethod)
        assertEquals(BillCarrier.MOBILE, bill.carrierKind)
        assertNull(bill.cloudCard)
    }
}
