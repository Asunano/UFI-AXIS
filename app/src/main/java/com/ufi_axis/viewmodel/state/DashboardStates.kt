package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.model.*

// ========== Dashboard ==========

data class DashboardState(
    val deviceInfo: DeviceInfoResponse? = null,
    val cpuInfo: CpuInfo? = null,
    val memoryInfo: MemoryInfo? = null,
    val batteryInfo: BatteryInfo? = null,
    val storageInfo: StorageInfo? = null,
    val uptimeInfo: UptimeInfo? = null,
    val trafficRealtime: TrafficRealtime? = null,
    val trafficSummary: TrafficSummary? = null,
    val trafficLimitConfig: TrafficLimitConfig? = null,
    val networkStatus: NetworkStatusResponse? = null,
    val signalInfo: SignalInfo? = null,
    val cpuHistory: List<CpuHistoryRecord> = emptyList(),
    val signalHistory: List<SignalHistoryRecord> = emptyList(),
    val deviceVersion: DeviceVersionResponse? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isOffline: Boolean = false,
    val lastUpdated: Long? = null
)

// ========== Monitor ==========

data class MonitorState(
    val selectedHours: Int = 24,
    val cpuHistory: List<DownsampledPoint> = emptyList(),
    val memoryHistory: List<DownsampledPoint> = emptyList(),
    val trafficRxHistory: List<DownsampledPoint> = emptyList(),
    val trafficTxHistory: List<DownsampledPoint> = emptyList(),
    val signalRsrpHistory: List<DownsampledPoint> = emptyList(),
    val signalSinrHistory: List<DownsampledPoint> = emptyList(),
    val batteryHistory: List<DownsampledPoint> = emptyList(),
    val temperatureHistory: List<DownsampledPoint> = emptyList(),
    val storageInfo: MonitorStorageResponse? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val cleanMessage: String? = null
)

// ========== Update ==========

data class UpdateState(
    val hasUpdate: Boolean = false,
    val serverVersion: String? = null,
    val updateUrl: String? = null,
    val checking: Boolean = false,
    val errorMessage: String? = null
)
