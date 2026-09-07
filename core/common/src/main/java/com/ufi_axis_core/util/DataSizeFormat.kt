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
