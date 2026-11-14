package com.ufi_axis_core.util

/**
 * 统一数据量（字节）可读格式化：B / KB / MB / GB / TB / PB。
 *
 * 与 app/data 的 [com.ufi_axis.util.FormatUtils.formatBytes] 保持同一口径
 * （后者已改为委托本函数），流量类告警的「值 / 阈值 / 标题」统一走此函数，
 * 避免"只有 MB、没有 GB/TB"的单位割裂（2026-08-13 需求4）。
 *
 * 换算基准为 1024（二进制，IEC 习惯）：1 KB = 1024 B，1 MB = 1024 KB，以此类推。
 */
fun formatDataSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    if (gb < 1024) return String.format("%.1f GB", gb)
    val tb = gb / 1024.0
    if (tb < 1024) return String.format("%.1f TB", tb)
    // PB 兜底：避免超大数据（如月度 TB 级流量累计）溢出显示为超大 GB 数字
    val pb = tb / 1024.0
    return String.format("%.1f PB", pb)
}

/**
 * 单位名（"B" / "KB" / "MB" / "GB" / "TB" / "PB"）。
 *
 * 给**图表坐标轴**用：轴上把单位提到轴头写一次（"GB"），每条刻度只写裸数字，
 * 于是不会出现"1000 MB / 1.5 GB"混排，也不会每条刻度都拖着一个单位。
 * [unitBytes] 必须是 1024 的幂；越界时退回 "B"。
 */
fun dataSizeUnitLabel(unitBytes: Long): String = when (unitBytes) {
    1L -> "B"
    1024L -> "KB"
    1024L * 1024 -> "MB"
    1024L * 1024 * 1024 -> "GB"
    1024L * 1024 * 1024 * 1024 -> "TB"
    1024L * 1024 * 1024 * 1024 * 1024 -> "PB"
    else -> "B"
}

/**
 * 指定单位下的**裸数值**文案（不带单位）：`"2"` / `"0.5"` / `"250"`。
 *
 * 与 [formatDataSize]（自动选单位 + 固定一位小数）的分工：那个是"一个数值给人看"，
 * 这个是"一根轴上的一排刻度"，单位由 [dataSizeUnitLabel] 在轴头写一次。
 * 小数只在确实需要时出现且最多两位、尾零去掉 —— 轴上的 `1.0` / `257.5` 都不该出现，
 * 但 `0.5 / 1 / 1.5 / 2` 这种整齐的半档是可以的（图表侧只会产出这类刻度，
 * 见 `UfiBarChart.ufiBarChartAxis`）。
 */
fun formatDataSizeValueInUnit(bytes: Long, unitBytes: Long): String {
    val unit = unitBytes.coerceAtLeast(1L)
    val v = bytes.toDouble() / unit
    if (v >= 100.0 || v == Math.floor(v)) return Math.round(v).toString()
    return String.format("%.2f", v).trimEnd('0').trimEnd('.')
}
