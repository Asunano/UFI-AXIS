package com.ufi_axis.util

import com.ufi_axis.data.model.CoreCrashResponse

/**
 * core 崩溃提醒的**展示判据**（2026-10-23）。
 *
 * 从 `ToolsModule.loadCoreCrashNotice` 里抽出来的理由只有一条：这是"同一条旧崩溃会不会
 * 反复弹"的唯一判据，必须有单测钉住；而 `ToolsModule` 依赖 Android Context，JVM 测不了。
 *
 * 判据分三层，缺一层都会回归成"每次启动弹一次"：
 * 1. [CoreCrashResponse.crashed] 且时间戳有效 —— 没崩过不提示；
 * 2. [CoreCrashResponse.recent] —— core 用**同一时钟**算出的 7 天新鲜度。
 *    `crashed` 对三个月前的记录也恒为 true，只有这一层能把它挡掉；
 * 3. 与 `AppPreferences.coreCrashShownAt` 比对 —— 同一次崩溃只提示一次。
 *
 * 老 core（没有 `recent` / `age_ms` 字段）走默认值 `recent = true`，行为与升级前一致；
 * 真正的"旧崩溃不再提示"要等 core 一起升级。
 */
object CoreCrashPolicy {

    /**
     * @param report   `GET /api/service/crash` 的响应
     * @param shownAt  本地"已提示过"的时间戳（0 = 从未提示）
     */
    fun shouldPrompt(report: CoreCrashResponse, shownAt: Long): Boolean {
        if (!report.crashed || report.timestamp <= 0L) return false
        if (!report.recent) return false
        // 同一时间戳只提示一次。放在最后判：上面两条是"该不该提示"，这条是"提示过没有"。
        return shownAt != report.timestamp
    }
}
