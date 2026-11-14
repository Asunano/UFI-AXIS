package com.ufi_axis_core.controller.carrier.cloud

import com.ufi_axis_core.controller.carrier.CarrierBill
import com.ufi_axis_core.controller.carrier.CarrierFlow
import com.ufi_axis_core.controller.carrier.CarrierKind
import com.ufi_axis_core.controller.carrier.CarrierPackage
import com.ufi_axis_core.controller.carrier.CarrierPackageKind
import com.ufi_axis_core.controller.carrier.CloudCardInfo
import com.ufi_axis_core.controller.carrier.CloudOrderInfo
import com.ufi_axis_core.controller.carrier.maskIotCardNo
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val MB_PER_GB = 1024.0
private val IOT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * 云上卡 → 通用 CarrierBill（2026-11-08）。
 *
 * 统一模型里：
 * - 余额/流量/套餐直接映射；
 * - 卡状态/实名/到期/ICCID 放 [CloudCardInfo]；
 * - `used` 是虚量口径，按文档展示；`realUsed` 只保留在 snapshot，不对外；
 * - `free=null` 标记 `generalUnlimited=true`，不能当 0 剩余。
 */
internal fun mapCloudCardBill(
    series: CloudSeries?,
    snapshot: CloudCardSnapshot,
    plans: List<CloudPlan>,
    orders: List<CloudOrder> = emptyList(),
    realOrders: List<CloudRealOrder> = emptyList(),
    warnings: List<String> = emptyList(),
    /** 物联卡站点；默认云上卡，保持既有调用/测试兼容。 */
    provider: IotProvider = IotProvider.CLOUD,
    now: LocalDateTime = LocalDateTime.now(),
): CarrierBill {
    val usedMb = snapshot.usedMb ?: 0.0
    val freeMb = snapshot.freeMb
    val unlimited = freeMb == null
    val totalMb = if (unlimited) usedMb else usedMb + freeMb
    val seriesName = plans.firstOrNull()?.seriesName?.takeIf { it.isNotBlank() }
        ?: series?.name?.takeIf { it.isNotBlank() }
        ?: series?.showName?.takeIf { it.isNotBlank() }

    val realUsedMb = snapshot.realUsedMb
    // 只统计生效中的原始订单；flowReal 是真实额度，不是客户端展示的 500G 虚量。
    val activeRealOrders = realOrders.filter {
        (it.effectiveType == null || it.effectiveType == 1) && (it.state == null || it.state == 2)
    }
    val realTotalMb = activeRealOrders.mapNotNull { it.flowRealMb }.takeIf { it.isNotEmpty() }?.sum()
    val realRemainingMb = realTotalMb?.let { total ->
        realUsedMb?.let { used -> (total - used).coerceAtLeast(0.0) }
    }

    return CarrierBill(
        carrier = CarrierKind.IOT_CARD,
        accessMethod = "iot_card",
        cloudCard = CloudCardInfo(
            cardNoMasked = maskIotCardNo(snapshot.cardNo.orEmpty()),
            providerId = provider.id,
            providerName = provider.displayName,
            iccid = snapshot.iccid,
            seriesName = seriesName,
            expiresAt = snapshot.expiresAt,
            daysLeft = daysLeft(snapshot.expiresAt, now),
            statusText = statusText(snapshot.state),
            realNameText = realNameText(snapshot.officialRealName),
            realUsedGb = realUsedMb?.div(MB_PER_GB),
            realTotalGb = realTotalMb?.div(MB_PER_GB),
            realRemainingGb = realRemainingMb?.div(MB_PER_GB),
            orders = orders.map { order ->
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
            refreshedAt = snapshot.flowRefreshTime,
        ),
        phoneMasked = maskIotCardNo(snapshot.cardNo.orEmpty()),
        planName = plans.firstOrNull()?.name,
        ownerName = null,
        tenureMonths = null,
        starLevel = null,
        balanceYuan = snapshot.balanceYuan,
        lastMonthBillYuan = null,
        currentBillYuan = null,
        currentBillPeriod = null,
        currentBillAsOf = null,
        points = null,
        flow = CarrierFlow(
            generalUsedGb = usedMb / MB_PER_GB,
            generalTotalGb = totalMb / MB_PER_GB,
            generalRemainingGb = (freeMb ?: 0.0) / MB_PER_GB,
            directedUsedGb = 0.0,
            directedTotalGb = 0.0,
            directedRemainingGb = 0.0,
            generalUnlimited = unlimited,
        ),
        monthlyUsedGb = null,
        voice = null,
        sms = null,
        packages = plans.map { plan ->
            val total = plan.totalMb ?: plan.usedMb ?: 0.0
            val used = plan.usedMb ?: 0.0
            CarrierPackage(
                kind = CarrierPackageKind.GENERAL,
                name = plan.name,
                remainingGb = ((total - used).coerceAtLeast(0.0)) / MB_PER_GB,
                totalGb = total / MB_PER_GB,
                validFrom = isoLocal(plan.effectiveTime),
                validTo = isoLocal(plan.expirationTime),
            )
        },
        warnings = warnings,
    )
}

private fun daysLeft(expiresAt: String?, now: LocalDateTime): Int? {
    val end = parseLocal(expiresAt) ?: return null
    return Duration.between(now, end).toDays().toInt()
}

private fun parseLocal(raw: String?): LocalDateTime? = runCatching {
    raw?.trim()?.takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it, IOT_TIME) }
}.getOrNull()

/** 统一模型/前端用 ISO-8601 本地时间；接口给的是空格分隔。 */
private fun isoLocal(raw: String?): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() }?.replace(' ', 'T')

private fun statusText(state: Int?): String? = when (state) {
    -1 -> "未知"
    0 -> "正常"
    1 -> "库存"
    2 -> "待激活"
    3 -> "风险停机"
    4 -> "已停机"
    5 -> "可测试"
    6 -> "机卡分离"
    7 -> "已销户"
    else -> null
}

private fun realNameText(code: Int?): String? = when (code) {
    -2 -> "未实名"
    -1 -> "未知"
    0 -> "已实名"
    1 -> "未实名"
    else -> null
}
