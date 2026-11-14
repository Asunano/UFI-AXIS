package com.ufi_axis.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `/api/carrier/status` 等登录端点的响应（2026-10-31）。 */
@Serializable
data class CarrierStatusResponse(
    val state: String = "not_configured",
    val carrier: String? = null,
    val phone: String? = null,
    val message: String? = null,
    @SerialName("expires_in") val expiresIn: Int = 0,
    @SerialName("retry_after") val retryAfter: Int = 0,
    @SerialName("retry_after_ms") val retryAfterMs: Int = 2000,
    @SerialName("qr_png_base64") val qrPngBase64: String? = null,
    @SerialName("last_fetched_at") val lastFetchedAt: Long? = null,
    /** 2026-11-05：`GET /status` 追加的账号列表（老 core 没有 → 空）。 */
    @SerialName("active_id") val activeId: String? = null,
    val accounts: List<CarrierAccountDto> = emptyList(),
)

@Serializable
data class CarrierBillResponse(
    val state: String = "ready",
    val bill: CarrierBillDto? = null,
    val warnings: List<String> = emptyList(),
    /** core 没有有效会话，但返回了磁盘缓存里的最近一次数据。 */
    @SerialName("login_required") val loginRequired: Boolean = false,
    /** 实时刷新失败，返回的是旧缓存。 */
    val stale: Boolean = false,
    /** 缓存拉取时刻（epoch ms）。 */
    @SerialName("cached_at") val cachedAt: Long? = null,
    /** stale 时的上游错误。 */
    val message: String? = null,
)

@Serializable
data class CarrierBillDto(
    val carrier: String = "mobile",
    /** `carrier` / `iot_card`（2026-11-08）。老 core 不返回 → 默认 carrier。 */
    @SerialName("access_method") val accessMethod: String = "carrier",
    @SerialName("cloud_card") val cloudCard: CloudCardDto? = null,
    val phone: String = "",
    @SerialName("plan_name") val planName: String? = null,
    @SerialName("owner_name") val ownerName: String? = null,
    @SerialName("tenure_months") val tenureMonths: Int? = null,
    @SerialName("star_level") val starLevel: Double? = null,
    @SerialName("balance_yuan") val balanceYuan: Double? = null,
    @SerialName("last_month_bill_yuan") val lastMonthBillYuan: Double? = null,
    @SerialName("current_bill_yuan") val currentBillYuan: Double? = null,
    @SerialName("current_bill_period") val currentBillPeriod: String? = null,
    @SerialName("current_bill_as_of") val currentBillAsOf: String? = null,
    val points: Int? = null,
    val flow: CarrierFlowDto,
    @SerialName("monthly_used_gb") val monthlyUsedGb: Double? = null,
    val voice: CarrierAmountDto? = null,
    val sms: CarrierAmountDto? = null,
    val packages: List<CarrierPackageDto> = emptyList(),
)

@Serializable
data class CarrierFlowDto(
    @SerialName("general_used_gb") val generalUsedGb: Double = 0.0,
    @SerialName("general_total_gb") val generalTotalGb: Double = 0.0,
    @SerialName("general_remaining_gb") val generalRemainingGb: Double = 0.0,
    @SerialName("directed_used_gb") val directedUsedGb: Double = 0.0,
    @SerialName("directed_total_gb") val directedTotalGb: Double = 0.0,
    @SerialName("directed_remaining_gb") val directedRemainingGb: Double = 0.0,
    @SerialName("regional_used_gb") val regionalUsedGb: Double? = null,
    @SerialName("regional_total_gb") val regionalTotalGb: Double? = null,
    @SerialName("regional_remaining_gb") val regionalRemainingGb: Double? = null,
    /** 物联卡 free=null = 不限量；普通运营商 false。 */
    @SerialName("general_unlimited") val generalUnlimited: Boolean = false,
)

/** 物联卡专用状态（2026-11-08）。普通运营商为 null。 */
@Serializable
data class CloudCardDto(
    /** `cloud`（云上卡）/ `xj`（小鲸卡）。 */
    @SerialName("provider_id") val providerId: String = "cloud",
    @SerialName("provider_name") val providerName: String = "云上卡",
    @SerialName("card_no") val cardNo: String = "",
    val iccid: String? = null,
    @SerialName("series_name") val seriesName: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("days_left") val daysLeft: Int? = null,
    @SerialName("status_text") val statusText: String? = null,
    @SerialName("real_name_text") val realNameText: String? = null,
    @SerialName("real_used_gb") val realUsedGb: Double? = null,
    @SerialName("real_total_gb") val realTotalGb: Double? = null,
    @SerialName("real_remaining_gb") val realRemainingGb: Double? = null,
    val orders: List<CloudOrderDto> = emptyList(),
    @SerialName("refreshed_at") val refreshedAt: String? = null,
)

@Serializable
data class CloudOrderDto(
    val name: String = "",
    @SerialName("amount_yuan") val amountYuan: Double? = null,
    @SerialName("paid_yuan") val paidYuan: Double? = null,
    @SerialName("status_text") val statusText: String = "",
    @SerialName("pay_type_text") val payTypeText: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("effective_time") val effectiveTime: String? = null,
    @SerialName("expiration_time") val expirationTime: String? = null,
)

@Serializable
data class CarrierAmountDto(
    val used: Double = 0.0,
    val total: Double = 0.0,
    val remaining: Double = 0.0,
)

@Serializable
data class CarrierPackageDto(
    val kind: String = "general",
    val name: String = "",
    @SerialName("remaining_gb") val remainingGb: Double = 0.0,
    @SerialName("total_gb") val totalGb: Double = 0.0,
    @SerialName("valid_from") val validFrom: String? = null,
    @SerialName("valid_to") val validTo: String? = null,
)

/** `/api/carrier/auto-login`（2026-11-01 预写）。 */
@Serializable
data class CarrierAutoLoginState(
    val supported: Boolean = false,
    val carrier: String? = null,
    @SerialName("sim_operator") val simOperator: String? = null,
    @SerialName("operator_name") val operatorName: String? = null,
    val enabled: Boolean = false,
    val phone: String? = null,
    val phase: String = "disabled",
    val message: String? = null,
    @SerialName("last_login_at") val lastLoginAt: Long? = null,
    @SerialName("next_check_at") val nextCheckAt: Long? = null,
    @SerialName("attempts_today") val attemptsToday: Int = 0,
    /** 账号自己的参与位（不受设备总开关影响）。 */
    val participating: Boolean = false,
    @SerialName("check_interval_minutes") val checkIntervalMinutes: Int = 5,
    @SerialName("relogin_interval_minutes") val reloginIntervalMinutes: Int = 25,
    @SerialName("max_attempts_per_day") val maxAttemptsPerDay: Int = 8,
)

// ─────────────────────── 设备级账单设置（2026-11-12）───────────────────────

/** `GET/PUT /api/carrier/bill/settings` 的刷新段。 */
@Serializable
data class CarrierBillRefreshSettings(
    val enabled: Boolean = true,
    @SerialName("interval_minutes") val intervalMinutes: Int = 20,
)

/** 设备级自动登录策略 + 今日用量。`attemptsToday` 跨账号合计。 */
@Serializable
data class CarrierKeepAliveSettings(
    val enabled: Boolean = false,
    @SerialName("check_interval_minutes") val checkIntervalMinutes: Int = 5,
    @SerialName("relogin_interval_minutes") val reloginIntervalMinutes: Int = 25,
    @SerialName("daily_sms_limit") val dailySmsLimit: Int = 8,
    @SerialName("attempts_today") val attemptsToday: Int = 0,
    @SerialName("next_check_at") val nextCheckAt: Long? = null,
)

/** 设置页里一个运营商账号的只读状态 + 参与位。 */
@Serializable
data class CarrierKeepAliveAccount(
    val id: String,
    val label: String = "",
    val phone: String = "",
    val carrier: String? = null,
    val participating: Boolean = false,
    val supported: Boolean = false,
    val phase: String = "disabled",
    val message: String? = null,
    @SerialName("attempts_today") val attemptsToday: Int = 0,
    @SerialName("last_login_at") val lastLoginAt: Long? = null,
    @SerialName("next_check_at") val nextCheckAt: Long? = null,
) {
    /** 与账号卡同一套规则：别名优先，其次脱敏号，最后 id 后四位。 */
    val displayTitle: String
        get() = label.ifBlank { phone.ifBlank { "账号 ${id.takeLast(4)}" } }
}

@Serializable
data class CarrierBillSettingsState(
    val refresh: CarrierBillRefreshSettings = CarrierBillRefreshSettings(),
    val keepalive: CarrierKeepAliveSettings = CarrierKeepAliveSettings(),
    val accounts: List<CarrierKeepAliveAccount> = emptyList(),
)

@Serializable
data class CarrierParticipatingBody(val participating: Boolean)

/**
 * 账号列表项（2026-11-05，多账号 P2）。
 *
 * `state` 与 `CarrierStatusResponse.state` 同一套词表：
 * `ready` / `login_required` / `not_configured` / `error`。
 */
@Serializable
data class CarrierAccountDto(
    val id: String,
    /** `carrier` / `iot_card`（2026-11-08）。 */
    @SerialName("access_method") val accessMethod: String = "carrier",
    /** 物联卡站点：`cloud` / `xj`；普通运营商为空/缺省。 */
    @SerialName("iot_provider") val iotProvider: String = "",
    val carrier: String? = null,
    /** 已脱敏（`138****8000`）；联通扫码未回号时为空串。 */
    val phone: String = "",
    val label: String = "",
    val state: String = "not_configured",
    @SerialName("created_at") val createdAt: Long = 0,
) {
    /**
     * 列表主标题（决策 5）：
     * - 有别名 → 别名（手机号放到副标题的小字里）；
     * - 没别名 → 脱敏手机号；
     * - 都没有（联通扫码未回号）→ 「联通账号」+ id 后 4 位，避免一片空白。
     */
    val isIotCard: Boolean get() = accessMethod == "iot_card"

    val displayTitle: String
        get() = label.ifBlank {
            if (isIotCard) {
                "${iotProviderName(iotProvider)} ${phone.takeLast(4)}"
            } else {
                phone.ifBlank {
                    "${carrierShortName(carrier)}账号 ${id.takeLast(4)}"
                }
            }
        }

    /** 副标题：有别名时显示「脱敏号 · 运营商」；没别名时只显示运营商。 */
    val displaySubtitle: String
        get() {
            if (isIotCard) {
                return listOf(phone, "物联卡 · ${iotProviderName(iotProvider)}")
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
            }
            val carrierText = carrierFullName(carrier)
            return if (label.isNotBlank() && phone.isNotBlank()) "$phone · $carrierText" else carrierText
        }
}

/** `GET /api/carrier/accounts` 的响应。 */
@Serializable
data class CarrierAccountsResponse(
    @SerialName("active_id") val activeId: String? = null,
    val accounts: List<CarrierAccountDto> = emptyList(),
)

/** 运营商 id → 全名（2026-11-07 补电信 / 广电）。 */
internal fun carrierFullName(carrier: String?): String = when (carrier?.lowercase()) {
    "mobile" -> "中国移动"
    "unicom" -> "中国联通"
    "telecom" -> "中国电信"
    "broadnet", "cbn" -> "中国广电"
    "iot_card", "iot" -> "物联卡"
    else -> carrier.orEmpty()
}

/** 运营商 id → 短名，用于「联通账号 303f」这种兜底标题。 */
internal fun carrierShortName(carrier: String?): String = when (carrier?.lowercase()) {
    "mobile" -> "移动"
    "unicom" -> "联通"
    "telecom" -> "电信"
    "broadnet", "cbn" -> "广电"
    "iot_card", "iot" -> "云卡"
    else -> "运营商"
}

/** 物联卡 provider → 展示名；空/未知时退回“物联卡”，不把内部 id 直接露给用户。 */
fun iotProviderName(provider: String?): String = when (provider?.trim()?.lowercase()) {
    "xj" -> "小鲸卡"
    "cloud", "ysv2", null, "" -> "云上卡"
    else -> "物联卡"
}

/**
 * 自动登录配置的请求体（2026-11-08）。
 *
 * 原来是 `Map<String, Any>` —— Retrofit 会在参数校验阶段直接抛
 * `Parameter type must not include a type variable or wildcard: java.util.Map<java.lang.String, ?>`，
 * 因为 Kotlin 的 `Map<String, Any>` 在 Java 签名里是 `Map<String, ?>`。
 * 换成具体类型后既过校验，也顺便让字段名有 SerialName 可查。
 */
@Serializable
data class CarrierAutoLoginConfigBody(
    val enabled: Boolean,
    val phone: String = "",
    @SerialName("check_interval_minutes") val checkIntervalMinutes: Int = 5,
    @SerialName("relogin_interval_minutes") val reloginIntervalMinutes: Int = 25,
    @SerialName("max_attempts_per_day") val maxAttemptsPerDay: Int = 8,
)
