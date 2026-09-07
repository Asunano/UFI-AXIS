package com.ufi_axis.util

import com.ufi_axis_core.util.formatDataSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {

    /**
     * 过滤后端返回的无意义值（Unknown/UNKNOWN/未知/空串），转为 null 供 UI 显示 "—"
     */
    fun String.sanitizeUnknown(): String? =
        if (this.isBlank() || this.equals("unknown", ignoreCase = true) || this == "未知") null else this

    /**
     * 字节可读格式化：B / KB / MB / GB / TB / PB。
     * 2026-08-13 需求4：统一委托 core:common 的 [formatDataSize]，避免多处各写一份换算导致口径漂移。
     */
    fun formatBytes(bytes: Long): String = formatDataSize(bytes)

    /**
     * 文件大小 / 用量展示：与 [formatBytes] 同一换算口径，只多一个「非正数」兜底。
     *
     * 2026-08-31：全库原有 6 份私有实现合并到这里 ——
     * `FileManagerRoot` / `FileInfoDialog` / `FileRowCard` / `TextEditorScreen` 四份同名
     * `formatSize`（逐字节一致的拷贝）、`SpeedTestScreen.formatBytes`（zeroText = "—"）、
     * `NetworkLogInterceptor.formatBytes`（日志用量）。
     *
     * 与那几份私有实现的差异（都是往好的方向收）：
     * - 原实现最大只到 GB，超大值会显示成「10240.0 GB」；这里继承 core 的 TB / PB 兜底。
     * - `SpeedTestScreen` 原来 KB 段不带小数（`%.0f KB`）、GB 段两位小数（`%.2f GB`），
     *   现在统一为一位小数。
     * - `NetworkLogInterceptor` 原来 GB 段是 `%.2f`，同样统一为一位小数（仅影响调试日志文本）。
     */
    fun formatSize(bytes: Long, zeroText: String = "0 B"): String =
        if (bytes <= 0L) zeroText else formatDataSize(bytes)

    /**
     * 速率展示（字节/秒）：B/s → KB/s → MB/s → GB/s。
     *
     * 2026-08-31：合并 `MonitorScreen.formatBytes(Double)`、
     * `MonitorOverview.formatTrafficRate(Double)`、`HomeConnectionCard.formatSpeed(Long)` 三份实现。
     * 三者换算完全一致，差异仅在：Monitor 两份没有 GB/s 档（超过 1GB/s 会显示成四位数 MB/s），
     * 首页那份 KB/s 段用整数（"512 KB/s"）而其余用一位小数 —— 现统一为一位小数 + GB/s 档。
     *
     * [naText] 用于 NaN（`MonitorOverview` 无数据时的 "—"）。
     */
    fun formatRate(bytesPerSec: Double, naText: String = "—"): String = when {
        bytesPerSec.isNaN() -> naText
        bytesPerSec >= 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.1f GB/s", bytesPerSec / (1024.0 * 1024 * 1024))
        bytesPerSec >= 1024.0 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024))
        bytesPerSec >= 1024.0 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
        else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
    }

    /** [formatRate] 的 Long 重载（首页/网络页拿到的瞬时速率是 Long）。 */
    fun formatRate(bytesPerSec: Long): String = formatRate(bytesPerSec.toDouble())

    /**
     * 测速读数（Mbps）：< 10 保留两位、≥ 10 保留一位，与 Speedtest 的读数密度一致。
     * 固定 `Locale.US` 避免某些语言环境把小数点写成逗号。
     *
     * 2026-08-31：由 `SpeedTestScreen.formatSpeed` 提上来（原实现逐字符保留）。
     */
    fun formatMbps(mbps: Double): String =
        if (mbps < 10) String.format(Locale.US, "%.2f", mbps) else String.format(Locale.US, "%.1f", mbps)


    // ========== 业务规则纯函数（F16：阈值 / 流量聚合 / 在线离线判定） ==========
    // 从调用方抽取的纯函数，便于单测；调用方签名不变。

    /** 月度流量聚合：上行 + 下行总字节数。 */
    fun monthlyTrafficTotal(rxBytes: Long, txBytes: Long): Long = rxBytes + txBytes

    /**
     * 流量阈值判定：已用字节是否达到（>=）限额的告警百分比。
     * limitBytes <= 0 视为未配置限额 → 永不触发告警。
     * 边界：used*100 == limit*alertPercent 视为已达阈值（触发）。
     */
    fun isTrafficAlertExceeded(usedBytes: Long, limitBytes: Long, alertPercent: Int): Boolean {
        if (limitBytes <= 0) return false
        return usedBytes * 100 >= limitBytes * alertPercent
    }

    /**
     * 蜂窝在线判定（等价 NetworkStatusResponse.isCellularConnected 的纯函数版）。
     * ZTE 设备上 ConnectivityManager 不反映 modem PPP 链路，必须以 ppp_status 为准。
     */
    fun isCellularConnected(pppStatus: String): Boolean =
        pppStatus.contains("connected", ignoreCase = true) &&
            !pppStatus.contains("disconnected", ignoreCase = true)

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
