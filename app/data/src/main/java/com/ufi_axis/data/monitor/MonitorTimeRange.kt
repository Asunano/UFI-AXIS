package com.ufi_axis.data.monitor

/**
 * 监控时间范围抽象。
 *
 * Preset：最近 N 小时滑动窗口（1/6/24/168），随轮询滑动、全量重拉。
 * Custom：固定历史区间（≤30 天）；endMs < now 全历史不轮询；endMs >= now 含实时→增量刷新。
 *
 * 纯 Kotlin 模型，不依赖 Compose / Android（仅 java.util.Calendar / System.currentTimeMillis）。
 */
sealed class MonitorTimeRange {

    data class Preset(val hours: Int) : MonitorTimeRange() {
        init {
            require(hours in PRESET_HOURS) { "Preset hours must be in $PRESET_HOURS" }
        }
    }

    data class Custom(val startMs: Long, val endMs: Long) : MonitorTimeRange() {
        init {
            // 2026-08-20：放宽为 >=（允许零跨度区间），修复午夜零点 today() 构造时
            // endMs == startMs 抛 IllegalArgumentException 导致监控页闪退的问题。
            require(endMs >= startMs) { "endMs must be >= startMs" }
        }

        override val spanMs: Long get() = endMs - startMs
    }

    /** 区间跨度（毫秒）：Preset 为滑动窗口总长（hours × 1h），Custom 为 endMs - startMs */
    open val spanMs: Long
        get() = when (this) {
            is Preset -> hours * 3_600_000L
            is Custom -> endMs - startMs
        }

    /**
     * 是否含实时尾窗：Preset 恒真；Custom 仅当 endMs 落在当前时刻之后（含 1 小时容差）。
     *
     * 容差见 [REAL_TIME_TOLERANCE_MS]：last7Days()/today() 创建时 endMs = nowMs()，
     * 若立即被 nowMs() 超过，轮询协程在 delay 后才判断 isRealTime 会误判为 false，
     * 导致告警轮询永不触发；容差覆盖最长 1h 轮询间隔 + 时钟漂移。
     */
    val isRealTime: Boolean
        get() = when (this) {
            is Preset -> true
            is Custom -> endMs >= nowMs() - REAL_TIME_TOLERANCE_MS
        }

    /** 查询起点（epoch millis）：Preset 为 now - hours；Custom 为固定 startMs */
    val queryStartMs: Long
        get() = when (this) {
            is Preset -> nowMs() - hours * 3_600_000L
            is Custom -> startMs
        }

    /** 查询终点（epoch millis）：Preset 为 now；Custom 且含实时尾窗时为 now，否则为固定 endMs */
    val queryEndMs: Long
        get() = when (this) {
            is Preset -> nowMs()
            is Custom -> if (isRealTime) nowMs() else endMs
        }

    companion object {
        /**
         * isRealTime 容差：endMs 距 now 在此范围内仍视为含实时尾窗，
         * 覆盖最长 1h 轮询间隔 + 时钟漂移，避免 last7Days()/today() 创建后
         * 立即被 nowMs() 超过导致轮询失效（endMs >= now - 容差）。
         */
        const val REAL_TIME_TOLERANCE_MS = 3_600_000L

        /** 自定义区间最大跨度：30 天（毫秒） */
        const val MAX_CUSTOM_MS = 30L * 24 * 3_600_000

        /**
         * 区间模式请求点数（后端 points 上限 720）。
         *
         * 2026-08-26 性能：从 720 降到 240。图表画布宽约 1000px、高 92dp，720 点等于每点 1.4px，
         * 视觉上完全分辨不出，但要多付 3 倍 JSON 解析 / 内存 / 曲线几何构建（8 类 × 720 = 5760 点）。
         * 峰值不受影响——后端按桶聚合，每桶带 min/max，桶变宽后桶内极值仍被保留，
         * 总览的「峰值」口径（取 max）与阈值判定都不会因此变钝。
         */
        const val MAX_POINTS = 240

        /** 预设档位（小时） */
        val PRESET_HOURS = listOf(1, 6, 24, 168)

        /** 默认时间范围：最近 24 小时 */
        val DEFAULT: MonitorTimeRange = Preset(24)

        /** 超 30 天钳制：endMs 拉到 startMs + 30 天；保证最小跨度 1ms 防止零跨度异常 */
        fun clampCustom(startMs: Long, endMs: Long): Custom {
            val span = (endMs - startMs).coerceIn(1L, MAX_CUSTOM_MS)
            return Custom(startMs, startMs + span)
        }

        /** 快捷项：昨天（-24h）/ 上周同期（-7d）相对主区间平移（等长） */
        fun shiftBack(range: MonitorTimeRange, days: Long): Custom {
            val start = range.queryStartMs - days * 24L * 3_600_000
            return Custom(start, start + range.spanMs)
        }

        /** 默认范围：今天 00:00:00 ~ 当前时刻（含实时，isRealTime=true → 增量刷新） */
        fun today(): Custom {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            // 2026-08-20：保证 endMs > startMs，防止午夜零点 start==end 抛异常
            // 2026-09-02：endMs 向下取整到分钟。调用方（监控页首屏 / 离页 / 切 Tab）会反复
            // 构造 today()，若 endMs 精确到毫秒则每次都是一个"新区间"——下游按区间做的
            // 懒加载记账会被反复作废。实时判定有 1h 容差、查询终点又走 queryEndMs=now，
            // 取整到分钟不影响任何数据口径。
            val end = maxOf(start + 1L, System.currentTimeMillis() / 60_000L * 60_000L)
            return Custom(start, end)
        }

        /** 今天 00:00:00 的 epoch millis（重置按钮显示条件用） */
        fun todayStartMs(): Long {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        /** 近 7 天（结束 = now，含实时尾窗 → 增量刷新）：事件中心默认范围，与总览的"今天"区分 */
        fun last7Days(): Custom {
            val end = System.currentTimeMillis()
            val start = end - 7L * 24 * 3_600_000
            return Custom(start, end)
        }

        private fun nowMs(): Long = System.currentTimeMillis()
    }
}
