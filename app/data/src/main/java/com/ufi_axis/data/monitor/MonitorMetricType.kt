package com.ufi_axis.data.monitor

/**
 * 监控指标元数据：apiKey 与后端一致、显示名、单位、默认 warn/alert 阈值、是否越小越差。
 *
 * lowerIsWorse=true 的指标（信号类）阈值判定方向取反：值 <= 阈值才超限。
 */
enum class MonitorMetricType(
    val apiKey: String,
    val label: String,
    val unit: String,
    val defaultWarn: Double?,
    val defaultAlert: Double?,
    val lowerIsWorse: Boolean
) {
    CPU("cpu", "CPU 使用率", "%", 80.0, 95.0, false),
    // 单位以后端采集口径为准：memory_history 存的是 usagePercent（0~100），不是 MB；
    // traffic_history 存的是 rxSpeed/txSpeed（bytes/s），不是 KB/s。写错单位会让阈值输入框与
    // 总览峰值卡一起骗人（曾出现总览显示"62 MB"而图表页同一时刻显示"62%"）。
    MEMORY("memory", "内存占用", "%", null, null, false),
    TRAFFIC_RX("traffic_rx", "下行速率", "B/s", null, null, false),
    TRAFFIC_TX("traffic_tx", "上行速率", "B/s", null, null, false),
    SIGNAL_RSRP("signal_rsrp", "RSRP 信号强度", "dBm", -100.0, -110.0, true),
    SIGNAL_SINR("signal_sinr", "SINR 信噪比", "dB", 10.0, 5.0, true),
    BATTERY("battery", "电池电量", "%", null, null, false),
    TEMPERATURE("temperature", "温度", "°C", 60.0, 75.0, false);

    companion object {
        /** 按后端 apiKey 反查指标类型；未知 key 返回 null */
        fun fromApiKey(key: String): MonitorMetricType? = entries.firstOrNull { it.apiKey == key }
    }
}
