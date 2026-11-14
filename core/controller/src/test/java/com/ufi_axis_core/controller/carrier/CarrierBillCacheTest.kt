package com.ufi_axis_core.controller.carrier

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 账单磁盘缓存（2026-11-09）。
 *
 * 缓存跨 core 重启，序列化形状必须能原样读回；`@Serializable` 漏在某个嵌套类上
 * （Flow / Package / CloudCardInfo）会先在这里红。
 */
class CarrierBillCacheTest {

    @Test
    fun `cached bill survives json round trip`() {
        val bill = CarrierBill(
            carrier = CarrierKind.IOT_CARD,
            accessMethod = "iot_card",
            cloudCard = CloudCardInfo(
                cardNoMasked = "****6305",
                providerId = "xj",
                providerName = "小鲸卡",
                iccid = "89860490192590506305",
                seriesName = "广东-4G-30天",
                expiresAt = "2026-12-09 17:29:10",
                daysLeft = 29,
                statusText = "正常",
                realNameText = "已实名",
                realUsedGb = 0.649,
                realTotalGb = 1000.0,
                realRemainingGb = 999.351,
                orders = listOf(
                    CloudOrderInfo(
                        name = "1000G-30天",
                        amountYuan = 29.9,
                        paidYuan = 29.9,
                        statusText = "成功",
                        payTypeText = "微信",
                        createdAt = "2026-11-09 17:29:20",
                        effectiveTime = "2026-11-09 17:29:11",
                        expirationTime = "2026-12-09 17:29:10",
                    )
                ),
                refreshedAt = "2026-11-09 19:37:44",
            ),
            phoneMasked = "****6305",
            planName = "1000G-30天",
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
                generalUsedGb = 0.649,
                generalTotalGb = 1000.0,
                generalRemainingGb = 999.351,
                directedUsedGb = 0.0,
                directedTotalGb = 0.0,
                directedRemainingGb = 0.0,
            ),
            monthlyUsedGb = null,
            voice = null,
            sms = null,
            packages = listOf(
                CarrierPackage(
                    kind = CarrierPackageKind.GENERAL,
                    name = "1000G-30天",
                    remainingGb = 999.351,
                    totalGb = 1000.0,
                    validFrom = "2026-11-09T17:29:11",
                    validTo = "2026-12-09T17:29:10",
                )
            ),
            warnings = listOf("上游刷新失败"),
        )
        val cached = CachedCarrierBill(bill, cachedAt = 1_762_700_000_000L)
        val json = Json { ignoreUnknownKeys = true }

        val back = json.decodeFromString(
            CachedCarrierBill.serializer(),
            json.encodeToString(CachedCarrierBill.serializer(), cached),
        )

        assertEquals(cached, back)
        assertEquals("小鲸卡", back.bill.cloudCard?.providerName)
        assertEquals(1000.0, back.bill.flow.generalTotalGb, 0.001)
    }
}
