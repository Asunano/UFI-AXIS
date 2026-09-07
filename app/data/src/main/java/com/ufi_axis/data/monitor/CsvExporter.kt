package com.ufi_axis.data.monitor

import com.ufi_axis.data.model.DownsampledPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 监控数据 CSV 导出器（P4a）。
 *
 * 设计意图：
 * - 纯 Kotlin 工具：只负责把一段 [DownsampledPoint] 序列化为符合规范的 CSV 字符串，
 *   不触碰文件系统 / UI / Android 框架，便于单元测试（无 Activity / Compose 依赖）。
 * - 输出字符串以 BOM（\uFEFF）开头，调用方用 `File.writeText()` / FileOutputStream 写出
 *   即为 UTF-8 with BOM，Excel / WPS 可直接识别中文摘要。
 * - 顶部 `#` 摘要行供人工阅读（程序可跳过、Excel 直接可见），随后为固定表头
 *   `timestamp,min,avg,max,unit` 与数据行，按时间升序排列。
 */
object CsvExporter {

    /**
     * 生成监控数据 CSV 字符串（UTF-8 内容，字符串开头已前置 BOM）。
     *
     * @param metric 指标元数据（label/unit 用于摘要行与 unit 列）
     * @param points 降采样数据点（入参通常已按 t 升序，直接遍历）
     * @param startMs 区间起点（epoch millis，摘要展示用）
     * @param endMs 区间终点（epoch millis，摘要展示用）
     * @return CSV 字符串，以 BOM 开头；points 为空时仅输出摘要行 + 表头
     */
    fun export(
        metric: MonitorMetricType,
        points: List<DownsampledPoint>,
        startMs: Long,
        endMs: Long
    ): String {
        val sb = StringBuilder()

        // BOM：写文件后为 UTF-8 with BOM
        sb.append("\uFEFF")

        // ── 摘要行（# 开头，Excel 直接可见） ──
        sb.append("# 指标: ${metric.label} (${metric.unit}) | 区间: ${formatLocal(startMs)} ~ ${formatLocal(endMs)} (本地时区)\n")
        if (points.isEmpty()) {
            sb.append("# 跨度: ${formatSpan(endMs - startMs)} | 点数: 0\n")
        } else {
            val avgs = points.map { it.avg }
            val maxV = avgs.max()
            val avgV = avgs.sum() / avgs.size
            val minV = avgs.min()
            sb.append(
                "# 跨度: ${formatSpan(endMs - startMs)} | 点数: ${points.size} | " +
                    "区间内 max=${formatNum(maxV)} / avg=${formatNum(avgV)} / min=${formatNum(minV)}\n"
            )
        }

        // ── 表头 ──
        sb.append("timestamp,min,avg,max,unit\n")

        // ── 数据行（入参已按 t 升序，直接遍历） ──
        for (p in points) {
            sb.append("${formatLocal(p.t)},${formatNum(p.min)},${formatNum(p.avg)},${formatNum(p.max)},${metric.unit}\n")
        }

        return sb.toString()
    }

    /** 本地时区时间格式化：yyyy-MM-dd HH:mm:ss */
    private fun formatLocal(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

    /** 数值保留 1 位小数 */
    private fun formatNum(value: Double): String = "%.1f".format(value)

    /** 跨度可读描述：X天X小时X分（如 1天0小时0分 / 0天6小时0分） */
    private fun formatSpan(ms: Long): String {
        val totalMinutes = ms / 60_000L
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return "${days}天${hours}小时${minutes}分"
    }
}
