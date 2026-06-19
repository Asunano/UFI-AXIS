package com.ufi_axis.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {

    /**
     * 过滤后端返回的无意义值（Unknown/UNKNOWN/未知/空串），转为 null 供 UI 显示 "—"
     */
    fun String.sanitizeUnknown(): String? =
        if (this.isBlank() || this.equals("unknown", ignoreCase = true) || this == "未知") null else this

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.1f GB", gb)
    }

    fun formatTimestamp(ms: Long): String {
        val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(ms))
    }

    fun formatPercent(percent: Double): String {
        return String.format("%.1f%%", percent)
    }

    fun formatTemperature(temp: Double): String {
        return String.format("%.1f°C", temp)
    }

    fun formatVoltage(v: Double): String {
        return String.format("%.2f V", v)
    }

    fun getSignalLevel(rsrp: Int?): String {
        if (rsrp == null) return "未知"
        return when {
            rsrp >= -80 -> "极好"
            rsrp >= -90 -> "好"
            rsrp >= -100 -> "一般"
            rsrp >= -115 -> "差"
            else -> "极差"
        }
    }

    fun getSignalBars(rsrp: Int?): Int {
        if (rsrp == null) return 0
        return when {
            rsrp >= -80 -> 5
            rsrp >= -90 -> 4
            rsrp >= -100 -> 3
            rsrp >= -115 -> 2
            else -> 1
        }
    }

    fun formatRelativeTime(ms: Long): String {
        val diff = System.currentTimeMillis() - ms
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            seconds < 60 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            days < 30 -> "${days}天前"
            else -> formatTimestamp(ms)
        }
    }

    fun getBatteryStatus(percent: Int, isCharging: Boolean): String {
        return when {
            isCharging -> "充电中"
            percent >= 80 -> "电量充足"
            percent >= 50 -> "电量良好"
            percent >= 20 -> "电量偏低"
            else -> "电量不足"
        }
    }
}
