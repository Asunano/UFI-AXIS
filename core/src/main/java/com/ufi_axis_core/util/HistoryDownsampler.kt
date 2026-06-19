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
     */
    fun downsample(data: List<Pair<Long, Double>>, targetPoints: Int = 360): List<DownsampledPoint> {
        if (data.isEmpty()) return emptyList()
        if (data.size <= targetPoints) {
            return data.map { (t, v) -> DownsampledPoint(t, v, v, v) }
        }

        val bucketSize = data.size / targetPoints
        val result = mutableListOf<DownsampledPoint>()

        for (i in 0 until targetPoints) {
            val start = i * bucketSize
            val end = if (i == targetPoints - 1) data.size else (i + 1) * bucketSize
            if (start >= data.size) break

            val bucket = data.subList(start, end.coerceAtMost(data.size))
            if (bucket.isEmpty()) continue

            val values = bucket.map { it.second }
            val avg = values.average()
            val min = values.min()
            val max = values.max()
            val midTimestamp = (bucket.first().first + bucket.last().first) / 2

            result.add(DownsampledPoint(midTimestamp, avg, min, max))
        }

        return result
    }
}
