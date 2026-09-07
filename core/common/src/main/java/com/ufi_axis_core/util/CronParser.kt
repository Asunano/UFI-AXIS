package com.ufi_axis_core.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 定时任务周期计算核心（时间与条件触发共用）。
 *
 * 设计要点：
 * - 5 段标准 cron：`分 时 日 月 周`（与 v9 §3.2 / §6.1 一致）。
 * - 该实现同时被后端 [com.ufi_axis_core.core.scheduler.TaskScheduler] 与前端
 *   [com.ufi_axis.ui.components.common.ScheduleSelector] 复用（置于 :core:common，
 *   两端模块均可达），保证「同样 cron 字符串 + 同样时间戳 → 同样 nextAfter 结果」。
 * - 不支持秒级（5 段 cron 无秒字段）。v10 决定不暴露「每 N 秒」选项 UI，直接从枚举删除。
 *
 * 字段范围：
 * - 分：0-59，时：0-23，日：1-31，月：1-12，周：0-7（0/7=周日，内部归一为 0）。
 */
object CronParser {

    /** 单次 nextAfter 扫描的最远前瞻天数（5 年，覆盖闰年 2/29 与跨年边界）。 */
    private const val MAX_LOOKAHEAD_DAYS = 366 * 5L

    private val ZONE: ZoneId = ZoneId.systemDefault()

    // ──────────── 预设类型枚举（与 v9 §3.2 8 preset 对齐）────────────

    /**
     * 7 种周期预设 + 自定义（v10 移除 EVERY_N_SECONDS：5 段 cron 无秒字段，且后端
     * TaskScheduler 不支持秒级触发，故 UI 不再暴露该选项）。
     * @property type 序列化/路由使用的字符串标识
     * @property label UI 展示文案
     */
    enum class SchedulePreset(val type: String, val label: String) {
        ONCE("once", "仅一次"),
        DAILY("daily", "每天"),
        WEEKLY("weekly", "每周"),
        MONTHLY("monthly", "每月"),
        EVERY_N_DAYS("every_n_days", "每 N 日"),
        EVERY_N_HOURS("every_n_hours", "每 N 时"),
        EVERY_N_MINUTES("every_n_minutes", "每 N 分"),
        CUSTOM("custom", "自定义");

        companion object {
            /** 由 type 字符串解析，未知/空一律回退 DAILY，保证向后兼容（旧任务可能存有「every_n_seconds」）。 */
            fun fromType(t: String?): SchedulePreset =
                entries.firstOrNull { it.type == t } ?: DAILY
        }
    }

    // ──────────── 结构化调度值（UI 状态 ↔ cron 的桥梁）────────────

    /**
     * 一次完整周期选择的快照。
     * 既作为 [ScheduleSelector] 的回调载体，也作为 cron 双向映射的中间表示。
     */
    data class ScheduleValue(
        val scheduleType: String = SchedulePreset.DAILY.type,
        val hour: Int = 8,
        val minute: Int = 30,
        val weekDays: Set<Int> = emptySet(),   // 1-7（1=周一 … 7=周日）
        val monthDays: Set<Int> = emptySet(),  // 1-31
        val intervalN: Int = 1,                // 每 N 日/时/分/秒
        val customCron: String = ""            // 自定义 cron 原文
    ) {
        companion object {
            val DEFAULT = ScheduleValue(
                scheduleType = SchedulePreset.DAILY.type,
                hour = 8,
                minute = 30
            )
        }
    }

    // ──────────── 解析结果 ────────────

    /**
     * 解析后的 cron，字段以谓词函数表示（支持 * , - / 与 * /N 步进）。
     * @param rawFields 原始 5 段字符串，供反向重建使用
     */
    data class ParsedCron(
        val minute: (Int) -> Boolean,
        val hour: (Int) -> Boolean,
        val day: (Int) -> Boolean,
        val month: (Int) -> Boolean,
        val weekday: (Int) -> Boolean,
        val dayIsWildcard: Boolean,
        val weekdayIsWildcard: Boolean,
        val monthIsWildcard: Boolean,
        val rawFields: List<String>
    )

    // ──────────── 公共 API ────────────

    /** cron 字符串是否合法（5 段且每段可解析）。 */
    fun isValid(cron: String?): Boolean = cron != null && parse(cron) != null

    /** 解析 cron，失败返回 null。 */
    fun parse(cron: String): ParsedCron? {
        val fields = cron.trim().split(Regex("\\s+"))
        if (fields.size != 5) return null
        val minute = parseField(fields[0], 0, 59) ?: return null
        val hour = parseField(fields[1], 0, 23) ?: return null
        val day = parseField(fields[2], 1, 31) ?: return null
        val month = parseField(fields[3], 1, 12) ?: return null
        // 周字段范围 0-7（7 归一为 0），匹配时恒以 dow%7 入参，故 7 不会出现
        val rawWeekday = parseField(fields[4], 0, 7) ?: return null
        val weekday: (Int) -> Boolean = { v -> rawWeekday(if (v == 7) 0 else v) }
        return ParsedCron(
            minute = minute,
            hour = hour,
            day = day,
            month = month,
            weekday = weekday,
            dayIsWildcard = fields[2].trim() == "*",
            weekdayIsWildcard = fields[4].trim() == "*",
            monthIsWildcard = fields[3].trim() == "*",
            rawFields = fields
        )
    }

    /** 给定时间戳是否匹配 cron（用于后端触发判定）。 */
    fun matches(cron: String, timeMillis: Long): Boolean {
        val parsed = parse(cron) ?: return false
        return matches(parsed, timeMillis)
    }

    /** 返回 [fromMillis] 之后（不含当分钟）的首次触发时间戳；无可达匹配返回 null。 */
    fun nextAfter(cron: String, fromMillis: Long): Long? {
        val parsed = parse(cron) ?: return null
        return nextAfter(parsed, fromMillis)
    }

    /**
     * 由结构化选择生成 5 段 cron；秒级返回 null（不支持）。
     * 周字段值：weekDays 1-7 → cron 值（7→0）。
     */
    fun toCron(
        scheduleType: String,
        hour: Int,
        minute: Int,
        weekDays: Set<Int>,
        monthDays: Set<Int>,
        intervalN: Int
    ): String? {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val n = intervalN.coerceIn(1, 365)
        return when (scheduleType) {
            "daily" -> "$m $h * * *"
            "weekly" -> {
                val wd = if (weekDays.isEmpty()) "1" else weekDays.sorted().joinToString(",") { (it % 7).toString() }
                "$m $h * * $wd"
            }
            "monthly" -> {
                val md = if (monthDays.isEmpty()) "1" else monthDays.sorted().joinToString(",")
                "$m $h $md * *"
            }
            "every_n_days" -> "$m $h */$n * *"
            "every_n_hours" -> "0 */$n * * *"
            "every_n_minutes" -> "*/$n * * * *"
            else -> null
        }
    }

    /**
     * 反向：由 cron 字符串重建结构化 [ScheduleValue]（供编辑旧任务时还原 UI）。
     * 基于 scheduleType 提取对应字段；无法识别时回退默认值。
     */
    fun toScheduleValue(cron: String, scheduleType: String): ScheduleValue {
        val parsed = parse(cron) ?: return ScheduleValue.DEFAULT
        val fields = parsed.rawFields
        val m = fieldNumbers(fields[0]).firstOrNull() ?: 0
        val h = fieldNumbers(fields[1]).firstOrNull() ?: 0
        return when (scheduleType) {
            "weekly" -> {
                val wd = fieldNumbers(fields[4]).map { if (it == 0) 7 else it }.toSet()
                ScheduleValue("weekly", h, m, wd, emptySet(), 1, "")
            }
            "monthly" -> {
                val md = fieldNumbers(fields[2]).toSet()
                ScheduleValue("monthly", h, m, emptySet(), md, 1, "")
            }
            "every_n_days" ->
                ScheduleValue("every_n_days", h, m, emptySet(), emptySet(), fieldStep(fields[2]) ?: 1, "")
            "every_n_hours" ->
                ScheduleValue("every_n_hours", h, m, emptySet(), emptySet(), fieldStep(fields[1]) ?: 1, "")
            "every_n_minutes" ->
                ScheduleValue("every_n_minutes", h, m, emptySet(), emptySet(), fieldStep(fields[0]) ?: 1, "")
            "custom" ->
                ScheduleValue("custom", h, m, emptySet(), emptySet(), 1, cron)
            else ->
                ScheduleValue("daily", h, m, emptySet(), emptySet(), 1, "")
        }
    }

    /**
     * 预览用的 cron：
     * - 仅一次 → 用「每天」cron 计算下次出现时刻（显示用）
     * - 自定义 → 校验后原样返回
     * - 其余 → [toCron]
     */
    fun previewCron(
        scheduleType: String,
        hour: Int,
        minute: Int,
        weekDays: Set<Int>,
        monthDays: Set<Int>,
        intervalN: Int,
        customCron: String
    ): String? = when (scheduleType) {
        "once" -> "%02d %02d * * *".format(minute.coerceIn(0, 59), hour.coerceIn(0, 23))
        "custom" -> if (isValid(customCron)) customCron else null
        else -> toCron(scheduleType, hour, minute, weekDays, monthDays, intervalN)
    }

    // ──────────── 内部实现 ────────────

    private fun matches(parsed: ParsedCron, timeMillis: Long): Boolean {
        val ldt = Instant.ofEpochMilli(timeMillis).atZone(ZONE).toLocalDateTime()
        if (!parsed.minute(ldt.minute)) return false
        if (!parsed.hour(ldt.hour)) return false
        if (!parsed.month(ldt.monthValue)) return false
        val domOk = parsed.day(ldt.dayOfMonth)
        val dowOk = parsed.weekday(ldt.dayOfWeek.value % 7) // 1-7 → 0-6（周日=0）
        val dayOk = when {
            parsed.dayIsWildcard && parsed.weekdayIsWildcard -> true
            parsed.dayIsWildcard -> dowOk
            parsed.weekdayIsWildcard -> domOk
            else -> domOk || dowOk // 日与周同时限定 → 取并集（标准 cron 语义）
        }
        return dayOk
    }

    private fun dateFeasible(parsed: ParsedCron, ldt: LocalDateTime): Boolean {
        if (!parsed.month(ldt.monthValue)) return false
        val domOk = parsed.day(ldt.dayOfMonth)
        val dowOk = parsed.weekday(ldt.dayOfWeek.value % 7)
        return when {
            parsed.dayIsWildcard && parsed.weekdayIsWildcard -> true
            parsed.dayIsWildcard -> dowOk
            parsed.weekdayIsWildcard -> domOk
            else -> domOk || dowOk
        }
    }

    private fun nextAfter(parsed: ParsedCron, fromMillis: Long): Long? {
        var cursor = Instant.ofEpochMilli(fromMillis).atZone(ZONE).toLocalDateTime()
            .withSecond(0).withNano(0).plusMinutes(1)
        val end = cursor.plusDays(MAX_LOOKAHEAD_DAYS)
        while (!cursor.isAfter(end)) {
            if (dateFeasible(parsed, cursor)) {
                // 扫描当天所有分钟，返回首个满足 分/时 的时刻
                var m = cursor
                while (m.dayOfMonth == cursor.dayOfMonth && !m.isAfter(end)) {
                    if (parsed.minute(m.minute) && parsed.hour(m.hour)) {
                        return m.atZone(ZONE).toInstant().toEpochMilli()
                    }
                    m = m.plusMinutes(1)
                }
                cursor = m.withSecond(0).withNano(0)
            } else {
                cursor = cursor.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
            }
        }
        return null
    }

    // ── 字段解析 ──

    private fun parseField(field: String, min: Int, max: Int): ((Int) -> Boolean)? {
        val tokens = field.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val predicates = tokens.mapNotNull { parseToken(it, min, max) }
        if (predicates.isEmpty()) return null
        return { v -> predicates.any { it(v) } }
    }

    private fun parseToken(token: String, min: Int, max: Int): ((Int) -> Boolean)? {
        return when {
            token == "*" -> ({ _ -> true })
            token.startsWith("*/") -> {
                val step = token.substring(2).toIntOrNull() ?: return null
                if (step <= 0) return null
                ({ v -> v in min..max && ((v - min) % step == 0) })
            }
            token.contains('/') -> {
                val parts = token.split('/')
                val step = parts.getOrNull(1)?.toIntOrNull() ?: return null
                if (step <= 0) return null
                val (lo, hi) = parseRange(parts[0], min, max) ?: return null
                ({ v -> v in lo..hi && ((v - lo) % step == 0) })
            }
            token.contains('-') -> {
                val (lo, hi) = parseRange(token, min, max) ?: return null
                ({ v -> v in lo..hi })
            }
            else -> {
                val exact = token.toIntOrNull() ?: return null
                if (exact !in min..max) return null
                ({ v -> v == exact })
            }
        }
    }

    private fun parseRange(range: String, min: Int, max: Int): Pair<Int, Int>? {
        if (!range.contains('-')) return null
        val (a, b) = range.split('-')
        val lo = a.toIntOrNull() ?: return null
        val hi = b.toIntOrNull() ?: return null
        return lo.coerceAtMost(hi).coerceIn(min, max) to hi.coerceAtLeast(lo).coerceIn(min, max)
    }

    /** 从字段串中提取所有显式数字（列表/范围展开，忽略步进基数 *）。 */
    private fun fieldNumbers(field: String): List<Int> {
        val out = mutableListOf<Int>()
        field.split(',').forEach { token ->
            val base = token.split('/')[0]
            if (base == "*") return@forEach
            if (base.contains('-')) {
                val (a, b) = base.split('-')
                val lo = a.toIntOrNull() ?: 0
                val hi = b.toIntOrNull() ?: 0
                for (i in lo..hi) out.add(i)
            } else {
                base.toIntOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    /** 从字段串中提取步进 N（首个含 / 的 token）。 */
    private fun fieldStep(field: String): Int? =
        field.split(',').firstNotNullOfOrNull { token ->
            if (token.contains('/')) token.split('/')[1].toIntOrNull() else null
        }
}
