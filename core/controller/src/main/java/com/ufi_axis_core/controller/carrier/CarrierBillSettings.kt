package com.ufi_axis_core.controller.carrier

import android.content.Context
import kotlinx.serialization.Serializable

/**
 * 话费账单的设备级策略（2026-11-12）。
 *
 * ## 为什么是设备级而不是账号级
 *
 * 账单子系统的三个真实约束都只存在于设备维度：
 * 1. **一条短信通道**：自动登录用的是设备里的那张 SIM，验证码也只回到这张卡；
 * 2. **一个调度器**：`CarrierAutoLoginManager` 用全局 mutex 串行等码，多账号只能排队；
 * 3. **一个后台总闸**：账单刷新与自动登录共用 `start/stop`，没有"某个账号单独跑"的入口。
 *
 * 之前把检查/重登间隔和每日上限放在每个账号上，会产生两个后果：
 * - 每个账号各 8 条/日 → N 个账号就是 N 倍真实短信，UI 却看起来像 8 条总预算；
 * - 调度器按账号排队，单账号的"检查间隔"在多账号下根本兑现不了。
 *
 * 因此参数收敛到这里；账号侧只保留 [CarrierAutoLoginConfig.enabled] 作为"参与/不参与"。
 *
 * ## 取值口径
 * - 刷新间隔 5–240 分钟：后台刷新只影响缓存新鲜度，不产生短信；
 * - 检查间隔 1–60 分钟：与发码器全局 1 条/分钟的下限对齐；
 * - 重登间隔 10–720 分钟：低于 10 分钟会连续烧短信；
 * - 每日短信 1–20 条：**设备总额度**，跨所有参与账号合计；单账号仍受
 *   [CarrierSendPolicy] 的 10 条/日硬保护。
 */
@Serializable
data class CarrierBillSettings(
    /** 后台定时刷新所有有会话的账号；默认开启，保持 2026-11-09 的既有行为。 */
    val refreshEnabled: Boolean = true,
    val refreshIntervalMinutes: Int = DEFAULT_REFRESH_MINUTES,
    /** 自动登录总开关；关闭时所有账号都不跑。 */
    val keepAliveEnabled: Boolean = false,
    val checkIntervalMinutes: Int = DEFAULT_CHECK_MINUTES,
    val reloginIntervalMinutes: Int = DEFAULT_RELOGIN_MINUTES,
    /** 设备级每日短信总上限（跨账号合计）。 */
    val dailySmsLimit: Int = DEFAULT_DAILY_SMS,
) {
    fun normalized(): CarrierBillSettings = copy(
        refreshIntervalMinutes = refreshIntervalMinutes.coerceIn(REFRESH_MIN, REFRESH_MAX),
        checkIntervalMinutes = checkIntervalMinutes.coerceIn(CHECK_MIN, CHECK_MAX),
        reloginIntervalMinutes = reloginIntervalMinutes.coerceIn(RELOGIN_MIN, RELOGIN_MAX),
        dailySmsLimit = dailySmsLimit.coerceIn(DAILY_MIN, DAILY_MAX),
    )

    companion object {
        const val REFRESH_MIN = 5
        const val REFRESH_MAX = 240
        const val CHECK_MIN = 1
        const val CHECK_MAX = 60
        const val RELOGIN_MIN = 10
        const val RELOGIN_MAX = 720
        const val DAILY_MIN = 1
        const val DAILY_MAX = 20

        const val DEFAULT_REFRESH_MINUTES = 20
        const val DEFAULT_CHECK_MINUTES = 5
        const val DEFAULT_RELOGIN_MINUTES = 25
        const val DEFAULT_DAILY_SMS = 8

        /**
         * 从旧的"每账号一份自动登录配置"收拢出一份设备策略。
         *
         * - 没有任何账号开启 → 保持默认关闭；
         * - 有账号开启 → 总开关开，取第一个开启账号的间隔（它们是同一套默认值，
         *   旧 UI 也从来没有真正回读过每账号值）；
         * - 每日额度取所有开启账号之和，落到 1–20。
         *
         * 这段是纯函数，便于单测；参与位由 manager 继续读旧 `enabled`，不在这里丢。
         */
        fun fromLegacy(configs: List<CarrierAutoLoginConfig>): CarrierBillSettings {
            val enabled = configs.filter { it.enabled }
            if (enabled.isEmpty()) return CarrierBillSettings()
            val first = enabled.first()
            return CarrierBillSettings(
                refreshEnabled = true,
                refreshIntervalMinutes = DEFAULT_REFRESH_MINUTES,
                keepAliveEnabled = true,
                checkIntervalMinutes = first.checkIntervalMinutes,
                reloginIntervalMinutes = first.reloginIntervalMinutes,
                dailySmsLimit = enabled.sumOf { it.maxAttemptsPerDay }.coerceIn(DAILY_MIN, DAILY_MAX),
            ).normalized()
        }
    }
}

/** 设置页里一个账号的只读状态 + 参与位。 */
data class CarrierKeepAliveAccountState(
    val id: String,
    val label: String,
    val phoneMasked: String,
    /** `mobile` / `unicom` …；只用于展示，不参与判断。 */
    val carrier: String,
    val participating: Boolean,
    val supported: Boolean,
    val phase: String,
    val message: String?,
    val attemptsToday: Int,
    val lastLoginAt: Long?,
    val nextCheckAt: Long?,
)

/**
 * `GET/PUT /api/carrier/bill/settings` 的完整快照。
 *
 * `attemptsToday` / `nextCheckAt` 是**设备级**汇总：前者跨账号合计，后者取所有
 * 参与且受支持账号里最早的一次。
 */
data class CarrierBillSettingsSnapshot(
    val settings: CarrierBillSettings,
    val attemptsToday: Int,
    val nextCheckAt: Long?,
    val accounts: List<CarrierKeepAliveAccountState>,
)

/**
 * 设备策略的读写 + 进程内缓存。
 *
 * 存储复用 `ufi_carrier`（AES-GCM 加密，见 [CarrierSessionStore]），不新建 prefs 文件：
 * `BackupPrefsCoverageTest` 会扫描每个 prefs 文件名并要求表态，复用现有隐私域最省事。
 *
 * 首次升级时 [migrate] 会把旧的每账号配置收拢成一条策略；迁移结果立即落盘，
 * 之后即使旧配置被清掉也稳定。
 */
class CarrierBillSettingsStore(context: Context) {
    private val session = CarrierSessionStore(context)
    private val lock = Any()

    @Volatile
    private var cached: CarrierBillSettings? = null

    fun get(): CarrierBillSettings {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            // 迁移没完成（Retry）时 accounts() 可能是空索引，绝不能据此写默认策略。
            if (!session.isMigrated()) return CarrierBillSettings()
            val present = session.hasBillSettings()
            val stored = session.loadBillSettings()
            if (present && stored == null) {
                // 密文/Keystore 暂时读不出来：本次按默认值运行，但**不写盘**，
                // 否则一次可恢复的读取失败会把用户调好的策略永久覆盖。
                return CarrierBillSettings()
            }
            val value = if (stored != null) {
                stored.normalized()
            } else {
                CarrierBillSettings.fromLegacy(
                    session.accounts().mapNotNull { session.loadAutoConfigOf(it.id) }
                ).also { session.saveBillSettings(it) }
            }
            cached = value
            return value
        }
    }

    fun save(input: CarrierBillSettings): CarrierBillSettings {
        val value = input.normalized()
        synchronized(lock) {
            session.saveBillSettings(value)
            cached = value
        }
        return value
    }
}
