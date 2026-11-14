package com.ufi_axis_core.controller.carrier

import kotlinx.serialization.Serializable

/**
 * 运营商账单的共用模型（2026-10-31）。
 *
 * 移动/联通两家字段差异很大：
 * - 移动：通用/定向 + 包明细 + 上月/本期账单 + 积分；
 * - 联通：通用/定向/区域 + 语音/短信；没有稳定的账单金额。
 *
 * 所以这里把「两家都可能没有」的字段全部做成可空，UI 按 null 隐藏，**不填 0**。
 * 金额单位元、流量单位 GB、语音分钟、短信条。
 */
/**
 * 运营商。
 *
 * 2026-11-07：补上 [TELECOM] / [BROADNET] —— SIM 识别、账号存储、UI 的运营商列表都要能表达
 * 这两家；但**账单与登录协议目前只有移动、联通接好了**，所以 `CarrierBillService.billOf`
 * 对它们直接抛"接入中"，`CarrierSimDetector` 也只把移动标成"支持自动登录"。
 */
@Serializable
enum class CarrierKind {
    MOBILE,
    UNICOM,
    TELECOM,
    BROADNET,
    /**
     * 物联卡/云上卡（2026-11-08）。
     *
     * 它不是 SIM 运营商，只是账单账号的 provider 维度；真正区分“运营商 / 物联卡”
     * 用 [CarrierBill.accessMethod] / 账号存储的 accessMethod。放进本枚举是为了让
     * CarrierStatus / CarrierBill / 路由共用同一套 provider 序列化，避免再造一套
     * nullable carrier。
     */
    IOT_CARD,
}

@Serializable
enum class CarrierPackageKind { GENERAL, DIRECTED, REGIONAL }

@Serializable
data class CarrierFlow(
    val generalUsedGb: Double,
    val generalTotalGb: Double,
    val generalRemainingGb: Double,
    val directedUsedGb: Double,
    val directedTotalGb: Double,
    val directedRemainingGb: Double,
    val regionalUsedGb: Double? = null,
    val regionalTotalGb: Double? = null,
    val regionalRemainingGb: Double? = null,
    /** 物联卡 `free=null` = 不限量；普通运营商恒 false。 */
    val generalUnlimited: Boolean = false,
) {
    /**
     * 合计 = 通用 + 定向 + 区域（有区域时）。
     *
     * 区域流量有使用范围限制（如校园/省内），不能当通用流量用；UI 必须单独显示区域一行，
     * 并在合计旁注明「含区域」。移动没有区域，值不受影响。
     */
    val usedGb: Double get() = generalUsedGb + directedUsedGb + (regionalUsedGb ?: 0.0)
    val totalGb: Double get() = generalTotalGb + directedTotalGb + (regionalTotalGb ?: 0.0)
    val remainingGb: Double
        get() = generalRemainingGb + directedRemainingGb + (regionalRemainingGb ?: 0.0)
}

@Serializable
data class CarrierVoice(val used: Double, val total: Double, val remaining: Double)

@Serializable
data class CarrierSms(val used: Double, val total: Double, val remaining: Double)

@Serializable
data class CarrierPackage(
    val kind: CarrierPackageKind,
    val name: String,
    val remainingGb: Double,
    val totalGb: Double,
    /** ISO-8601 本地时间；没有就 null（UI 显示“未标到期”）。 */
    val validFrom: String?,
    val validTo: String?,
)

/** 物联卡专用状态；普通运营商为 null。 */
@Serializable
data class CloudCardInfo(
    /** 已脱敏的充值号。完整卡号只在 core 的加密账号里。 */
    val cardNoMasked: String,
    /** 物联卡站点：`cloud`（云上卡）/ `xj`（小鲸卡）。 */
    val providerId: String = "cloud",
    /** 站点展示名，UI 直接显示，避免前端再维护一份字典。 */
    val providerName: String = "云上卡",
    val iccid: String?,
    val seriesName: String?,
    val expiresAt: String?,
    val daysLeft: Int?,
    val statusText: String?,
    val realNameText: String?,
    /** 上游真实用量（GB）；只做展示/对账，不能和虚量相加。 */
    val realUsedGb: Double? = null,
    /** 原始订单推算的真实总额度（GB）；取不到时为 null。 */
    val realTotalGb: Double? = null,
    /** 原始订单推算的真实剩余（GB）= realTotal - realUsed。 */
    val realRemainingGb: Double? = null,
    /** 套餐充值订单详情；物联卡没有“流量包”概念，页面展示订单。 */
    val orders: List<CloudOrderInfo> = emptyList(),
    /** 设备侧最近一次流量刷新时间（原样字符串）。 */
    val refreshedAt: String?,
)

@Serializable
data class CloudOrderInfo(
    val name: String,
    val amountYuan: Double?,
    val paidYuan: Double?,
    val statusText: String,
    val payTypeText: String?,
    val createdAt: String?,
    val effectiveTime: String?,
    val expirationTime: String?,
)

@Serializable
data class CarrierBill(
    val carrier: CarrierKind,
    /** `carrier` / `iot_card`；普通运营商默认 carrier，物联卡为 iot_card。 */
    val accessMethod: String = "carrier",
    /** 物联卡专用字段；普通运营商为 null。 */
    val cloudCard: CloudCardInfo? = null,
    /** 脱敏号码，如 136****1614。 */
    val phoneMasked: String,
    val planName: String?,
    val ownerName: String?,
    /** 网龄（月）；解析不出来为 null。 */
    val tenureMonths: Int?,
    val starLevel: Double?,
    val balanceYuan: Double?,
    val lastMonthBillYuan: Double?,
    val currentBillYuan: Double?,
    val currentBillPeriod: String?,
    val currentBillAsOf: String?,
    val points: Int?,
    val flow: CarrierFlow,
    /** 本月累计，含已过期包；移动有，联通通常没有。 */
    val monthlyUsedGb: Double?,
    val voice: CarrierVoice?,
    val sms: CarrierSms?,
    val packages: List<CarrierPackage>,
    /** 部分接口失败/口径提示；不阻断整页。 */
    val warnings: List<String>,
)

/** 号码脱敏：保留前 3 后 4。 */
/**
 * 物联卡充值号脱敏。
 *
 * 7 位卡号用“前 3 + 后 4”仍会把所有数字露出来，所以物联卡只留后 4 位。
 * 完整卡号只存在 core 的加密账号里，客户端只拿这个脱敏值。
 */
fun maskIotCardNo(cardNo: String): String {
    val digits = cardNo.filter { it.isDigit() }
    return if (digits.length <= 4) "****" else "****" + digits.takeLast(4)
}

fun maskCarrierPhone(phone: String): String {
    val digits = phone.filter { it.isDigit() }
    return if (digits.length >= 7) {
        digits.substring(0, 3) + "****" + digits.takeLast(4)
    } else {
        phone
    }
}
