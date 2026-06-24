package com.ufi_axis.viewmodel.state

// ========== Duplicate Download Info ==========

/**
 * 重复下载检测结果，前端据此弹出提示对话框
 */
data class DuplicateInfo(
    val existingTask: DownloadTaskItem,
    val newUrl: String,
    val suggestedFileName: String,
    val fileName: String? = null,
    val savePath: String? = null,
    val speedLimit: Long? = null,
    val connections: Int? = null
)

// ========== Download Task Item ==========

data class DownloadTaskItem(
    val id: String = "",
    val url: String = "",
    val fileName: String = "",
    val savePath: String = "",
    val totalSize: Long = -1L,
    val downloadedBytes: Long = 0L,
    val progress: Float = 0f,
    val speed: Long = 0L,
    val uploadSpeed: Long = 0L,
    val status: String = "pending",
    val error: String? = null,
    val createdAt: Long = 0L,
    val completedAt: Long = 0L,
    val engine: String = "aria2",
    val protocol: String = "http",
    val connections: Int = 0,
    val seeders: Int = 0
)

// ========== Download Config Item ==========

data class DownloadConfigItem(
    // Basic
    val maxConcurrent: Int = 3,
    val maxConnectionsPerServer: Int = 4,
    val globalSpeedLimit: Long = 0L,
    val perTaskSpeedLimit: Long = 0L,
    val saveDir: String = "/storage/emulated/0/Downloads/UFI",
    val splitCount: Int = 4,
    val minSplitSizeMb: Int = 1,
    val maxOverallUploadLimit: Long = 0L,
    val fileAllocation: String = "none",
    // Advanced BT
    val btSeedRatio: Float = 1.0f,
    val btMaxPeers: Int = 50,
    val btEnableDht: Boolean = true,
    val btEnableLpd: Boolean = true,
    val dhtListenPort: String = "6881-6999",
    val btTrackerConnectTimeout: Int = 60,
    val btRequestPeerSpeedLimit: Long = 0L,
    val btMaxOpenFiles: Int = 100,
    // Advanced Network
    val disableIpv6: Boolean = true,
    val checkCertificate: Boolean = false,
    val maxTries: Int = 5,
    val retryWait: Int = 3,
    val maxResumeTries: Int = 0,
    val lowestSpeedLimit: Long = 0L,
    // Log
    val logLevel: String = "notice",
    // BT Tracker Management
    val btTrackerAutoUpdate: Boolean = true,
    val btTrackerUpdateIntervalHours: Int = 24,
    val btTrackerSourceUrl: String = "https://cf.trackerslist.com/best_aria2.txt",
    val btTrackerCustomList: String = "",
    // Smart Throttle
    val smartThrottle: Boolean = true,
    val throttleTempWarn: Float = 55f,
    val throttleTempCritical: Float = 70f,
    val throttleCpuWarn: Int = 60,
    val throttleCpuCritical: Int = 85,
    val throttleBatteryWarn: Int = 30,
    val throttleBatteryCritical: Int = 15,
    val throttleMemoryWarn: Int = 75,
    val throttleMemoryCritical: Int = 90,
    val onlyDownloadWhenCharging: Boolean = false
)

// ========== Download State ==========

data class DownloadState(
    val tasks: List<DownloadTaskItem> = emptyList(),
    val isLoading: Boolean = false,
    val activeCount: Int = 0,
    val aria2Running: Boolean = false,
    val aria2Version: String? = null,
    val config: DownloadConfigItem = DownloadConfigItem(),
    val errorMessage: String? = null,
    // Tracker state
    val trackerCount: Int = 0,
    val trackerStatus: String = "idle",
    val trackerLastUpdated: Long = 0L,
    val trackerRefreshing: Boolean = false,
    // Throttle state
    val throttleState: String = "normal",
    val throttleTemp: Float = 0f,
    val throttleCpu: Int = 0,
    val throttleBattery: Int = -1,
    val throttleMemory: Int = 0,
    val throttleCharging: Boolean = false,
    val throttleWasStopped: Boolean = false,
    // Cached tracker list for editing
    val cachedTrackerList: String = "",
    val trackerListLoading: Boolean = false,
    // Duplicate download dialog
    val duplicateInfo: DuplicateInfo? = null
)
