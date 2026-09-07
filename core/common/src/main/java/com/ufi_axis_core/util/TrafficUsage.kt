package com.ufi_axis_core.util

import java.util.Locale

/**
 * 流量限额判定的共享算术。
 *
 * 背景：`AlertEngine.checkTrafficLimit`（core:alert，只记录+通知）和
 * `TrafficAutoOffGuard.onUsage`（core:controller，真的关网）由同一个
 * `DataScheduler` 轮次用同一组入参调用，此前各自复制了「百分比换算 + 阈值兜底 +
 * 一位小数展示」三段代码。两边的**判定语义**故意不合并（告警多一档 100%
 * critical、去重凭据一个持久化按月、一个内存按电平、失败重试策略也不同），
 * 但算术必须同源，否则会出现「告警说 79.9%、关网按 80% 执行」这类对不上的文案。
 */

/** 告警百分比兜底：设备侧配置越界（0 / >100 / 负数）时回落 80。 */
fun normalizeAlertPercent(alertPercent: Int): Int = if (alertPercent in 1..100) alertPercent else 80

/**
 * 已用流量占限额的百分比。
 *
 * 调用方必须先自行拦掉 `limitBytes <= 0`（未配置限额），本函数不做该分支判断——
 * 返回 0 会被误当成"用量为零"。
 */
fun trafficUsagePercent(usedBytes: Long, limitBytes: Long): Double = usedBytes * 100.0 / limitBytes

/** 百分比展示口径：固定一位小数 + `Locale.US`，避免跟随系统区域变成逗号小数点。 */
fun formatUsagePercent(percent: Double): String = String.format(Locale.US, "%.1f", percent)
