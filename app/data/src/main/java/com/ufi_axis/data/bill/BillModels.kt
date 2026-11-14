package com.ufi_axis.data.bill

/**
 * 话费账单的数据模型（2026-10-31 新增）。
 *
 * 这一层刻意只放**纯数据**：金额用元、流量用 GB、时间用 ISO 字符串。
 * UI 负责格式化与「今天/明天到期」的换算，数据源（营业厅接口 / core 代理）只负责填值。
 *
 * 字段口径（与 2026-10-31 的查询结果一致）：
 * - [currentBillYuan] 是「本期账单」，[currentBillPeriod] 是账期（如 `202610`），
 *   [currentBillAsOf] 是截止日期 —— 月未结束时这个金额还会变；
 * - [monthlyUsedGb] 是「本月累计」，**包含已过期流量包**，与有效包的剩余量不是同一口径；
 * - [packages] 里每个包都带完整有效期，UI 自己决定是否标「即将到期」。
 */
data class BillSnapshot(
    /** 展示名：中国移动 / 中国联通 / 云上卡。 */
    val carrier: String,
    val carrierKind: BillCarrier = BillCarrier.MOBILE,
    /** `carrier` / `iot_card`；老数据默认 carrier。 */
    val accessMethod: BillAccessMethod = BillAccessMethod.CARRIER,
    /** 物联卡专用状态；普通运营商为 null。 */
    val cloudCard: CloudCardInfo? = null,
    val phone: String,
    val planName: String?,
    val ownerName: String? = null,
    /** 网龄（月）。61 → 5年1个月；联通通常没有。 */
    val tenureMonths: Int? = null,
    /** 星级，1.0 / 1.5 …；展示时去掉多余的 .0。 */
    val starLevel: Double? = null,
    val balanceYuan: Double?,
    val lastMonthBillYuan: Double? = null,
    val currentBillYuan: Double? = null,
    val currentBillPeriod: String? = null,
    val currentBillAsOf: String? = null,
    val points: Int? = null,
    val flow: BillFlow,
    /** 本月累计使用量（含已过期流量包），仅展示；联通通常没有。 */
    val monthlyUsedGb: Double? = null,
    val voice: BillVoice? = null,
    val sms: BillSms? = null,
    val packages: List<BillPackage>,
    /** 部分接口失败/口径提示；不阻断整页。 */
    val warnings: List<String> = emptyList(),
    /** 登录已失效；账单仍有值时表示"最近一次缓存数据"。 */
    val loginRequired: Boolean = false,
    /** 这份账单的拉取时刻（epoch ms）；实时拉取时由客户端补齐。 */
    val cachedAt: Long? = null,
    /** 实时刷新失败，当前展示的是旧缓存。 */
    val stale: Boolean = false,
)

enum class BillCarrier { MOBILE, UNICOM, IOT_CARD }

enum class BillAccessMethod { CARRIER, IOT_CARD }

/** 物联卡状态卡（云上卡）；普通运营商为 null。 */
data class CloudCardInfo(
    val cardNo: String,
    /** `cloud` / `xj`；账单头部用它显示“云上卡 / 小鲸卡”。 */
    val providerId: String = "cloud",
    val providerName: String = "云上卡",
    val iccid: String? = null,
    val seriesName: String? = null,
    val expiresAt: String? = null,
    val daysLeft: Int? = null,
    val statusText: String? = null,
    val realNameText: String? = null,
    /** 上游真实用量（GB）；与虚量 generalUsedGb 分开显示。 */
    val realUsedGb: Double? = null,
    /** 原始订单推算的真实总额度/剩余（GB）。 */
    val realTotalGb: Double? = null,
    val realRemainingGb: Double? = null,
    /** 套餐充值订单；物联卡没有流量包概念。 */
    val orders: List<CloudOrderInfo> = emptyList(),
    val refreshedAt: String? = null,
)

data class CloudOrderInfo(
    val name: String,
    val amountYuan: Double? = null,
    val paidYuan: Double? = null,
    val statusText: String = "",
    val payTypeText: String? = null,
    val createdAt: String? = null,
    val effectiveTime: String? = null,
    val expirationTime: String? = null,
)

data class BillVoice(val used: Double, val total: Double, val remaining: Double)

data class BillSms(val used: Double, val total: Double, val remaining: Double)

/**
 * 通用 / 定向两类的已用、总量与剩余。
 *
 * ⚠ **剩余量必须由数据源给出，不能用 `总量 − 已用` 现算**：营业厅返回的三个数各自四舍五入，
 * 2026-10-31 这份数据里 `2.20 − 0.46 = 1.74`，但口径上的剩余是 `1.73`（总量实际约 2.195）。
 * 现算会与流量包剩余合计（110.78）差 0.01，用户一眼就能看出两个数字对不上。
 * 合计只是把三类相加，不重新推导“真实值”。
 */
data class BillFlow(
    val generalUsedGb: Double,
    val generalTotalGb: Double,
    val generalRemainingGb: Double,
    val directedUsedGb: Double,
    val directedTotalGb: Double,
    val directedRemainingGb: Double,
    val regionalUsedGb: Double? = null,
    val regionalTotalGb: Double? = null,
    val regionalRemainingGb: Double? = null,
    /** 物联卡 free=null = 不限量；普通运营商 false。 */
    val generalUnlimited: Boolean = false,
) {
    /** 合计含区域（联通口径）；移动区域为 null，结果不变。 */
    val usedGb: Double get() = generalUsedGb + directedUsedGb + (regionalUsedGb ?: 0.0)
    val totalGb: Double get() = generalTotalGb + directedTotalGb + (regionalTotalGb ?: 0.0)
    val remainingGb: Double
        get() = generalRemainingGb + directedRemainingGb + (regionalRemainingGb ?: 0.0)
}

/** 流量包类型。 */
enum class BillPackageKind { GENERAL, DIRECTED, REGIONAL }

/**
 * 单个流量包。
 *
 * [validFrom] / [validTo] 用 ISO-8601 本地时间（`2026-10-31T14:16:00`）：
 * 营业厅返回的是本地时间，没有时区；用 `LocalDateTime` 解析即可，别擅自补 Zone。
 */
data class BillPackage(
    val kind: BillPackageKind,
    val name: String,
    val remainingGb: Double,
    val totalGb: Double,
    val validFrom: String? = null,
    val validTo: String? = null
)
