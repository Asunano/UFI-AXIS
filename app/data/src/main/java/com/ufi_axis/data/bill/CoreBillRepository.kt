package com.ufi_axis.data.bill

import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.CarrierBillDto
import com.ufi_axis.data.model.CarrierPackageDto

/**
 * 从 core 的 `/api/carrier/bill` 取账单（2026-10-31）。
 *
 * 会话/Cookie/完整手机号都在 core；这里只做 JSON → [BillSnapshot] 映射。
 * 未登录时 core 回 409，由 `ToolsModule` 转成 `BillUiState.NeedsLogin`。
 */
class CoreBillRepository(private val api: UfiAxisApi) : BillRepository {
    override suspend fun loadBill(accountId: String?, force: Boolean): BillSnapshot {
        val refresh = if (force) 1 else 0
        val response = if (accountId.isNullOrBlank()) {
            api.getCarrierBill(refresh)
        } else {
            api.getCarrierBillOf(accountId, refresh)
        }
        val dto = response.bill ?: error("core 没有返回账单数据")
        return dto.toBillSnapshot(
            warnings = response.warnings,
            loginRequired = response.loginRequired,
            cachedAt = response.cachedAt,
            stale = response.stale,
        )
    }
}

internal fun CarrierBillDto.toBillSnapshot(
    warnings: List<String>,
    loginRequired: Boolean = false,
    cachedAt: Long? = null,
    stale: Boolean = false,
): BillSnapshot = BillSnapshot(
        carrier = when (carrier) {
            "unicom" -> "中国联通"
            "iot_card", "iot" -> cloudCard?.providerName ?: "物联卡"
            else -> "中国移动"
        },
        carrierKind = when (carrier) {
            "unicom" -> BillCarrier.UNICOM
            "iot_card", "iot" -> BillCarrier.IOT_CARD
            else -> BillCarrier.MOBILE
        },
        accessMethod = if (accessMethod == "iot_card") {
            BillAccessMethod.IOT_CARD
        } else {
            BillAccessMethod.CARRIER
        },
        cloudCard = cloudCard?.let {
            CloudCardInfo(
                cardNo = it.cardNo,
                providerId = it.providerId,
                providerName = it.providerName,
                iccid = it.iccid,
                seriesName = it.seriesName,
                expiresAt = it.expiresAt,
                daysLeft = it.daysLeft,
                statusText = it.statusText,
                realNameText = it.realNameText,
                realUsedGb = it.realUsedGb,
                realTotalGb = it.realTotalGb,
                realRemainingGb = it.realRemainingGb,
                orders = it.orders.map { order ->
                    CloudOrderInfo(
                        name = order.name,
                        amountYuan = order.amountYuan,
                        paidYuan = order.paidYuan,
                        statusText = order.statusText,
                        payTypeText = order.payTypeText,
                        createdAt = order.createdAt,
                        effectiveTime = order.effectiveTime,
                        expirationTime = order.expirationTime,
                    )
                },
                refreshedAt = it.refreshedAt,
            )
        },
        phone = phone,
        planName = planName,
        ownerName = ownerName,
        tenureMonths = tenureMonths,
        starLevel = starLevel,
        balanceYuan = balanceYuan,
        lastMonthBillYuan = lastMonthBillYuan,
        currentBillYuan = currentBillYuan,
        currentBillPeriod = currentBillPeriod,
        currentBillAsOf = currentBillAsOf,
        points = points,
        flow = BillFlow(
            generalUsedGb = flow.generalUsedGb,
            generalTotalGb = flow.generalTotalGb,
            generalRemainingGb = flow.generalRemainingGb,
            directedUsedGb = flow.directedUsedGb,
            directedTotalGb = flow.directedTotalGb,
            directedRemainingGb = flow.directedRemainingGb,
            regionalUsedGb = flow.regionalUsedGb,
            regionalTotalGb = flow.regionalTotalGb,
            regionalRemainingGb = flow.regionalRemainingGb,
            generalUnlimited = flow.generalUnlimited,
        ),
        monthlyUsedGb = monthlyUsedGb,
        voice = voice?.let { BillVoice(it.used, it.total, it.remaining) },
        sms = sms?.let { BillSms(it.used, it.total, it.remaining) },
        packages = packages.map { it.toModel() },
        warnings = warnings,
        loginRequired = loginRequired,
        cachedAt = cachedAt,
        stale = stale,
    )

private fun CarrierPackageDto.toModel(): BillPackage = BillPackage(
    kind = when (kind) {
        "directed" -> BillPackageKind.DIRECTED
        "regional" -> BillPackageKind.REGIONAL
        else -> BillPackageKind.GENERAL
    },
    name = name,
    remainingGb = remainingGb,
    totalGb = totalGb,
    validFrom = validFrom,
    validTo = validTo,
)
