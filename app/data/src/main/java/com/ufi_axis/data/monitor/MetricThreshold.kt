package com.ufi_axis.data.monitor

import kotlinx.serialization.Serializable

/** 单指标阈值配置：warn=黄线、alert=红线 */
@Serializable
data class MetricThreshold(
    val warnValue: Double? = null,
    val alertValue: Double? = null,
    val enabled: Boolean = false
)

/** 阈值等级：正常 / 警告（黄线）/ 告警（红线） */
enum class ThresholdLevel { NORMAL, WARN, ALERT }

/** 阈值触发事件（通知模块预留载荷） */
data class ThresholdEvent(
    val metricType: MonitorMetricType,
    val level: ThresholdLevel,
    val value: Double,
    val timestampMs: Long,
    val thresholdValue: Double
)

/** 通知触发点预留接口：本轮注入空实现，后续通知模块替换 */
fun interface ThresholdNotifier {
    fun onThresholdBreached(event: ThresholdEvent)
}

/**
 * 阈值判定：方向按 [MonitorMetricType.lowerIsWorse]。
 * - lowerIsWorse=false（越大越差）：value >= warnValue → WARN；value >= alertValue → ALERT
 * - lowerIsWorse=true（越小越差）：value <= warnValue → WARN；value <= alertValue → ALERT
 * - alert 优先于 warn；阈值未配置或 disabled → NORMAL
 */
fun MonitorMetricType.evaluate(value: Double, threshold: MetricThreshold?): ThresholdLevel {
    if (threshold == null || !threshold.enabled) return ThresholdLevel.NORMAL
    val alert = threshold.alertValue
    if (alert != null && crossed(value, alert)) return ThresholdLevel.ALERT
    val warn = threshold.warnValue
    if (warn != null && crossed(value, warn)) return ThresholdLevel.WARN
    return ThresholdLevel.NORMAL
}

private fun MonitorMetricType.crossed(value: Double, threshold: Double): Boolean =
    if (lowerIsWorse) value <= threshold else value >= threshold
