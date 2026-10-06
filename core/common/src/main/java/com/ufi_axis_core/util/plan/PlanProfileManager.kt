package com.ufi_axis_core.util.plan

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import java.util.Calendar
import java.util.TimeZone

/**
 * 套餐档案（2026-10-07 到量/到期融合方案）。
 *
 * ## 职责边界（与 goform 的分工）
 *
 * - **设备侧（goform）= 用量真源**：总量、清零日、月度计数器——固件懂的继续写固件；
 * - **core 侧 = 套餐语义层**：模式、生效日、时长——固件没有这些字段，由 core 记账。
 *
 * ## 两种模式（plan_mode）
 *
 * - **monthly**（循环月包）：现状语义不变。总量+清零日走 goform，月计数器固件自清，
 *   到量预警走固件计数器。**不填有效期 → 到期提醒自动不生效**。
 * - **fixed**（累计有效期包，如 100G×3 个月）：总量+生效日+天数存 core；
 *   - 用量 = traffic_hourly 在 [startDate, now] 的累加（**不用固件月计数器**——
 *     固件按 clear_date 清零会把累计口径打断，fixed 模式下 core 代发 auto_clear=false）；
 *   - 到期 = startDate + durationDays；到期前 N 天 warning，当天 critical，过期 info。
 *
 * 存储：AppSettings 原始键（用户 2026-10-07 拍板：一次性设置，不进数据库）。
 */
class PlanProfileManager(
    private val getRaw: (String) -> String?,
    private val setRaw: (String, String) -> Unit,
) {
    /** 生产装配的便捷构造：直接接 AppSettings。 */
    constructor(settings: AppSettings) : this(
        getRaw = settings::getRawString,
        setRaw = settings::setRawString,
    )

    companion object {
        private const val TAG = "PlanProfile"
        const val KEY_MODE = "plan_mode"                    // "monthly" | "fixed"
        const val KEY_START_DATE = "plan_start_date"        // "yyyy-MM-dd"（fixed 专用）
        const val KEY_DURATION_DAYS = "plan_duration_days"  // Int（fixed 专用）
        const val KEY_NOTIFY_DAYS = "plan_expiry_notify_days" // 到期前 N 天开始提醒，默认 3

        const val MODE_MONTHLY = "monthly"
        const val MODE_FIXED = "fixed"

        /** 提醒天数合法范围 */
        const val NOTIFY_DAYS_MIN = 0
        const val NOTIFY_DAYS_MAX = 30
        /** 天数合法范围 */
        const val DURATION_MIN = 1
        const val DURATION_MAX = 730
    }

    data class Profile(
        val mode: String,
        val startDate: String?,   // "yyyy-MM-dd"，monthly 为 null
        val durationDays: Int,    // monthly 为 0
        val notifyDays: Int,
    ) {
        val isFixed: Boolean get() = mode == MODE_FIXED
    }

    fun read(): Profile = Profile(
        mode = getRaw(KEY_MODE)?.takeIf { it == MODE_FIXED } ?: MODE_MONTHLY,
        startDate = getRaw(KEY_START_DATE)?.takeIf { it.isNotBlank() },
        durationDays = getRaw(KEY_DURATION_DAYS)?.toIntOrNull() ?: 0,
        notifyDays = getRaw(KEY_NOTIFY_DAYS)?.toIntOrNull()?.coerceIn(NOTIFY_DAYS_MIN, NOTIFY_DAYS_MAX) ?: 3,
    )

    /**
     * 写入。返回 null = 成功；非 null = 校验错误文案。
     * mode=monthly 时清掉 fixed 专属键（不留脏状态——切回 monthly 后到期逻辑必须绝迹）。
     */
    fun write(mode: String, startDate: String?, durationDays: Int?, notifyDays: Int?): String? {
        when (mode) {
            MODE_MONTHLY -> {
                setRaw(KEY_MODE, MODE_MONTHLY)
                setRaw(KEY_START_DATE, "")
                setRaw(KEY_DURATION_DAYS, "")
                if (notifyDays != null) {
                    if (notifyDays !in NOTIFY_DAYS_MIN..NOTIFY_DAYS_MAX)
                        return "到期提醒天数须在 $NOTIFY_DAYS_MIN..$NOTIFY_DAYS_MAX"
                    setRaw(KEY_NOTIFY_DAYS, notifyDays.toString())
                }
            }
            MODE_FIXED -> {
                val sd = startDate ?: return "fixed 模式必须提供 plan_start_date"
                if (!Regex("""^\d{4}-\d{2}-\d{2}$""").matches(sd)) return "plan_start_date 格式须为 yyyy-MM-dd"
                val dd = durationDays ?: return "fixed 模式必须提供 plan_duration_days"
                if (dd !in DURATION_MIN..DURATION_MAX) return "plan_duration_days 须在 $DURATION_MIN..$DURATION_MAX"
                val nd = notifyDays ?: read().notifyDays
                if (nd !in NOTIFY_DAYS_MIN..NOTIFY_DAYS_MAX)
                    return "到期提醒天数须在 $NOTIFY_DAYS_MIN..$NOTIFY_DAYS_MAX"
                setRaw(KEY_MODE, MODE_FIXED)
                setRaw(KEY_START_DATE, sd)
                setRaw(KEY_DURATION_DAYS, dd.toString())
                setRaw(KEY_NOTIFY_DAYS, nd.toString())
            }
            else -> return "plan_mode 必须是 monthly | fixed"
        }
        return null
    }

    // ───────── 到期运算（fixed 模式） ─────────

    /** "yyyy-MM-dd" → 当地时区该日 00:00 的 epoch ms；解析失败返回 null */
    private fun parseDay(s: String): Long? = try {
        val parts = s.split("-").map { it.toInt() }
        Calendar.getInstance(TimeZone.getDefault()).apply {
            clear(); set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
        }.timeInMillis
    } catch (e: Exception) {
        null
    }

    data class ExpiryState(
        /** 距到期整天数：0=今天到期，负=已过期 */
        val daysLeft: Long,
        /** 到期日 epoch ms */
        val expiryAt: Long,
    )

    /**
     * 当前到期状态。monthly 模式 / 日期非法 / 未配齐 → null（到期逻辑绝迹）。
     */
    fun expiryState(now: Long = System.currentTimeMillis()): ExpiryState? {
        val p = read()
        if (!p.isFixed) return null
        val start = parseDay(p.startDate ?: return null) ?: return null
        if (p.durationDays <= 0) return null
        val expiryAt = start + p.durationDays * 86_400_000L
        // 到期日 00:00 - now，向上取整天数
        val msLeft = expiryAt - now
        val daysLeft = if (msLeft <= 0) {
            // 已过期：向上取整（负数除法向零取整，-1.5 天会变 -1，我们想要 -2）
            -((-(msLeft - 1)) / 86_400_000L)
        } else {
            (msLeft + 86_400_000L - 1) / 86_400_000L
        }
        return ExpiryState(daysLeft, expiryAt)
    }

    /**
     * 累计用量（fixed 模式）：traffic_hourly 在 [生效日 00:00, now) 的 (rx+tx) 总和。
     * monthly 模式不适用（固件计数器是真源）。
     */
    fun fixedUsageWindow(now: Long = System.currentTimeMillis()): Pair<Long, Long>? {
        val p = read()
        if (!p.isFixed) return null
        val start = parseDay(p.startDate ?: return null) ?: return null
        return start to now
    }

    /**
     * 本轮该发什么到期提醒。返回 null = 本轮无提醒（含「今天已发过」的边沿抑制）。
     *
     * 边沿语义（2026-10-07 定案）：每天最多一条；级别跃迁（warning→critical）当天立即重发——
     * 复用 AlertEngine 的边沿思想但不走它（这里没有采集值，是纯日历运算，独立成方法便于测试）。
     */
    fun dueNotification(now: Long = System.currentTimeMillis()): Pair<String, String>? {
        val st = expiryState(now) ?: return null
        val p = read()
        val fmt = java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA)
        val expiryText = fmt.format(java.util.Date(st.expiryAt))
        val level: String
        val body: String
        when {
            st.daysLeft > p.notifyDays -> return null            // 还早，不打扰
            st.daysLeft > 0 -> {
                level = "warning"
                body = "套餐将于 $expiryText 到期，剩余 ${st.daysLeft} 天"
            }
            st.daysLeft == 0L -> {
                level = "critical"
                body = "套餐今天到期（$expiryText），请及时续订或切换套餐"
            }
            else -> {
                level = "info"
                body = "套餐已于 $expiryText 到期（过期 ${-st.daysLeft} 天），流量统计将继续按 fixed 口径累计"
            }
        }
        // 当天去重键：level+日期。同一天同级别只发一条（过期 info 每天 1 条是刻意的提醒）。
        val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA).format(java.util.Date(now))
        val dedupKey = "$level:$dayKey"
        val lastKey = getRaw("plan_expiry_last_notified").orEmpty()
        if (lastKey == dedupKey) return null
        setRaw("plan_expiry_last_notified", dedupKey)
        return level to body
    }
}
