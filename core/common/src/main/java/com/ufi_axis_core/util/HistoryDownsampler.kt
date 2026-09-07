package com.ufi_axis_core.util

import kotlinx.serialization.Serializable

/**
 * 降采样数据点
 * 每个点代表一个时间桶内的聚合统计
 */
@Serializable
data class DownsampledPoint(
    val t: Long,       // 桶中间时间戳
    val avg: Double,   // 平均值
    val min: Double,   // 最小值
    val max: Double    // 最大值
)

/**
 * 历史数据降采样器
 *
 * 将高密度时间序列压缩到指定点数，用于 API 响应轻量化。
 * 算法: 等宽时间桶 + 桶内 avg/min/max 聚合。
 */
object HistoryDownsampler {

    /**
     * 降采样
     * @param data   原始数据 (timestamp, value)，按时间升序
     * @param targetPoints  目标点数 (默认 360)
     * @return 降采样后的点列表
     *
     * 按**时间**等宽分桶，而不是按下标分桶。区别很要紧：
     * - 按下标分桶时，桶宽在时间上完全不可控 —— 采集中断过的那段会被压进某个桶，桶中点时间
     *   落在"根本没有数据的时间里"；而整除余数（最多 targetPoints-1 个样本）全塞进最后一桶，
     *   它的中点可能比前一个点晚几个小时。前端靠"点距是否远大于中位点距"判断断档，
     *   于是图的右端每次都被判成断档、稳定缺一段（即"明明有数据却断线"）。
     * - 按时间分桶后，输出时间戳恒为 bucketMs 的整数倍，中位点距 = bucketMs，
     *   只有真正没采集的时段才会出现整数倍的跳跃 —— 断档判定才有意义。
     *
     * 空桶不产出任何点（缺失即缺失），由前端决定是断线还是连线。
     */
    fun downsample(data: List<Pair<Long, Double>>, targetPoints: Int = 360): List<DownsampledPoint> {
        if (data.isEmpty()) return emptyList()
        if (data.size <= targetPoints) {
            return data.map { (t, v) -> DownsampledPoint(t, v, v, v) }
        }

        val startMs = data.first().first
        val spanMs = data.last().first - startMs
        val bucketMs = (spanMs / targetPoints).coerceAtLeast(1L)

        val result = ArrayList<DownsampledPoint>(targetPoints + 1)
        var bucketIndex = -1L
        var sum = 0.0
        var count = 0
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE

        fun flush() {
            if (count == 0) return
            // 桶中点时间：与区间聚合路径（MonitorRoutes 的 start_time/end_time 分支）一致
            val t = startMs + bucketIndex * bucketMs + bucketMs / 2
            result.add(DownsampledPoint(t, sum / count, min, max))
            sum = 0.0
            count = 0
            min = Double.MAX_VALUE
            max = -Double.MAX_VALUE
        }

        for ((t, v) in data) {
            val idx = (t - startMs) / bucketMs
            if (idx != bucketIndex) {
                flush()
                bucketIndex = idx
            }
            sum += v
            count++
            if (v < min) min = v
            if (v > max) max = v
        }
        flush()

        return result
    }
}
