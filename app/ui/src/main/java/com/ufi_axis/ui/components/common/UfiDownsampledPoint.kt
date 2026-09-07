// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

/**
 * UI 层自包含的降采样数据点类型，用于切断 `app:ui` 对 `:core` 的反向依赖。
 *
 * 字段与后端生产端 [com.ufi_axis_core.util.DownsampledPoint] 对齐（t/min/max/avg），
 * 由调用方（MonitorScreen）在唯一边界处完成映射。
 *
 * [min] / [max]：目前仅保留数据，UI 不再展示（2026-09-03 按需求移除曲线上下的区间带，
 * 以及 tooltip 里的「峰值 / 谷值」两行）。字段**不要删** —— 这是与服务端 / core 对齐的镜像类型，
 * 服务端按桶取 SQL MIN()/MAX() 后仍在返回，删字段会让映射侧对不上、也断掉将来在详情页
 * 重新用上极值的路。
 */
data class UfiDownsampledPoint(
    val t: Long,
    val min: Double,
    val max: Double,
    val avg: Double
)
