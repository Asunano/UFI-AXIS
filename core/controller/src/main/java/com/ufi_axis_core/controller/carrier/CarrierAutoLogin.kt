package com.ufi_axis_core.controller.carrier

import kotlinx.serialization.Serializable

/**
 * 移动自动登录的**账号侧**配置（2026-11-01 预写；2026-11-12 收窄为"参与位"）。
 *
 * 多账号之后，检查/重登间隔与每日额度都是设备级的（见 [CarrierBillSettings]）：
 * 一条 SIM、一个发码器、一个调度器，逐账号参数既兑现不了，也会把短信预算乘成 N 倍。
 *
 * 本类的 [enabled] 现在表示**该账号是否参与设备策略**；三个数值字段只为兼容旧客户端
 * 的 PUT 请求保留，落盘时会被设备策略的值覆盖。新客户端不要再用它们。
 */
@Serializable
data class CarrierAutoLoginConfig(
    val enabled: Boolean = false,
    /** 留空时依次回退：SIM 号码 → 已有会话手机号；都没有则状态为 phone_required。 */
    val phone: String = "",
    val checkIntervalMinutes: Int = 5,
    val reloginIntervalMinutes: Int = 25,
    val maxAttemptsPerDay: Int = 8,
    /**
     * 参与位是否被用户**显式**设置过（2026-11-12）。
     *
     * - `null` = 旧配置 / 从未设置：跟随设备总开关（移动账号在总开关打开时默认参与）；
     * - `true` = 用户在新设置页明确选过参与/不参与，之后不再被总开关覆盖。
     *
     * 没有这个字段就分不清"旧 UI 留下的 enabled=false"和"用户在新 UI 里主动关掉"，
     * 前者在总开关打开时应该参与，后者应该保持关闭。
     */
    val participationExplicit: Boolean? = null,
)

/**
 * 自动登录可用的账号手机号校验。
 *
 * 只判断「是中国移动账号」不够：账号没有手机号（或只存了脱敏号）时，
 * 自动登录既发不出验证码，也没有可回填的登录号，App 不应显示这一段。
 */
internal object CarrierPhone {
    private val MOBILE = Regex("""^1[3-9]\d{9}$""")

    fun isMobile(phone: String?): Boolean {
        val digits = phone.orEmpty().filter(Char::isDigit)
        val local = if (digits.length == 13 && digits.startsWith("86")) digits.drop(2) else digits
        return MOBILE.matches(local)
    }
}

/**
 * SIM 号码与账号手机号是否指向同一张卡。
 *
 * 2026-11-13（用户明确要求）：自动登录只允许
 * 1. 中国移动号码；
 * 2. 该 SIM 插在当前设备；
 * 3. **设备读出来的 SIM 手机号与账号手机号一致**。
 *
 * 因此这里不再做"任一侧读不到就放行"的宽松处理：SIM 号码读不到就无法确认
 * 验证码会回到哪张卡，必须按不支持处理。账号/SIM 都归一化为后 11 位比较，
 * +86 前缀不影响。
 */
internal object CarrierSimMatch {
    /** SIM 号码是否可读（至少 11 位数字）；读不到就不能确认账号归属。 */
    fun simReadable(simPhone: String?): Boolean = digits(simPhone).length >= 11

    fun matches(accountPhone: String?, simPhone: String?): Boolean {
        val account = digits(accountPhone)
        val sim = digits(simPhone)
        // 账号手机号必须完整 11 位；SIM 号码也必须读得到完整 11 位。
        if (account.length < 11 || sim.length < 11) return false
        // +86 前缀存在时取后 11 位即可对齐。
        return account.takeLast(11) == sim.takeLast(11)
    }

    private fun digits(v: String?): String = v.orEmpty().filter(Char::isDigit)
}

data class CarrierSimCapability(
    val supported: Boolean,
    val carrier: CarrierKind?,
    val simOperator: String,
    val operatorName: String,
    val phone: String?,
    val simCount: Int,
    val reason: String?,
)

data class CarrierAutoLoginState(
    val supported: Boolean,
    val carrier: CarrierKind?,
    val simOperator: String?,
    val operatorName: String?,
    /**
     * **生效中**的自动登录：设备总开关 && 本账号参与位。
     *
     * 老客户端只认这个字段；新客户端要看 [participating] 才知道"账号选了参与，
     * 但设备总开关关着"这种状态。
     */
    val enabled: Boolean,
    val phoneMasked: String?,
    val phase: String,
    val message: String?,
    val lastLoginAt: Long?,
    val nextCheckAt: Long?,
    val attemptsToday: Int,
    /** 账号自己的参与位（不受设备总开关影响）。 */
    val participating: Boolean = false,
    /** 以下三项是设备策略的只读回显，避免旧 UI 拿 5/25/8 覆盖真实值。 */
    val checkIntervalMinutes: Int = CarrierBillSettings.DEFAULT_CHECK_MINUTES,
    val reloginIntervalMinutes: Int = CarrierBillSettings.DEFAULT_RELOGIN_MINUTES,
    val maxAttemptsPerDay: Int = CarrierBillSettings.DEFAULT_DAILY_SMS,
)

/**
 * 自动登录期间对短信链路的静默标记。
 *
 * 只影响 core 自己的短信转发/通知；Android 默认短信 App 的系统通知不归 core 管。
 * 后台短信观察者据此跳过「10086 + 验证码」这一条，避免把登录码再推给用户。
 */
object CarrierAutoLoginRuntime {
    @Volatile
    private var waitingUntil: Long = 0L

    fun begin(windowMs: Long = 120_000L) {
        waitingUntil = System.currentTimeMillis() + windowMs
    }

    fun end() {
        waitingUntil = 0L
    }

    fun isWaiting(now: Long = System.currentTimeMillis()): Boolean = now < waitingUntil

    fun shouldSuppress(address: String?, body: String?): Boolean {
        if (!isWaiting()) return false
        val a = address.orEmpty()
        val b = body.orEmpty()
        return (a.contains("10086") || b.contains("中国移动")) &&
            (b.contains("验证码") || b.contains("校验码") || b.contains("动态码") || b.contains("登录"))
    }
}
