package com.ufi_axis_core.api.routes

/**
 * 解析 `GET /api/alerts/daily_counts` 的 `start` / `end`（毫秒时间戳）。
 *
 * 抽成纯函数是为了能直接单测：参数校验是这条接口唯一的业务分支，
 * 留在 handler 里只能靠起 Ktor 测试宿主才能覆盖。
 *
 * @return 合法区间 `start to end`（`end` 为**开区间**上界）；任一非法返回 null。
 */
internal fun parseDailyCountsRange(start: String?, end: String?): Pair<Long, Long>? {
    val s = start?.trim()?.toLongOrNull() ?: return null
    val e = end?.trim()?.toLongOrNull() ?: return null
    if (e <= s) return null
    return s to e
}
