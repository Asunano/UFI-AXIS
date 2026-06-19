package com.ufi_axis.data.model

data class DashboardData(
    val cpuUsage: Float = 0f,
    val memoryUsed: Long = 0,
    val memoryTotal: Long = 0,
    val downloadSpeed: Long = 0,
    val uploadSpeed: Long = 0,
    val rsrp: Int = 0,
    val sinr: Int = 0,
    val rsrq: Int = 0,
    val networkType: String = "Unknown",
    val lastUpdate: Long = System.currentTimeMillis()
)
