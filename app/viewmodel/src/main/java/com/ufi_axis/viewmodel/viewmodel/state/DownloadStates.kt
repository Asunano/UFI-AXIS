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
    // 与 core 侧 DownloadManager.PUBLIC_DOWNLOAD_DIR 保持一致：一级目录必须是 Android
    // 标准的 "Download"（单数，MediaStore RELATIVE_PATH 只认它），二级收敛到 UFI-AXIS/
    val saveDir: String = "/storage/emulated/0/Download/UFI-AXIS/Download",
    val splitCount: Int = 4,
    val maxOverallUploadLimit: Long = 0L,
    // Advanced BT（file-allocation/dht-listen-port/bt-max-open-files/
    // bt-tracker-connect-timeout/bt-request-peer-speed-limit/max-resume-tries
    // 已在后端 Aria2Engine 固化为字面量，前端不再维护）
    val btSeedRatio: Float = 1.0f,
    val btMaxPeers: Int = 50,
    val btEnableDht: Boolean = true,
    val btEnableLpd: Boolean = true,
    // Advanced Network
    val disableIpv6: Boolean = true,
    val checkCertificate: Boolean = false,
    val maxTries: Int = 5,
    val retryWait: Int = 3,
    // BT Tracker Management
    val btTrackerAutoUpdate: Boolean = true,
    val btTrackerUpdateIntervalHours: Int = 24,
    val btTrackerSourceUrl: String = "https://cf.trackerslist.com/best_aria2.txt",
    val btTrackerCustomList: String = "",
    // Smart Throttle（阈值由后端固件调参固定，仅保留开关与用户偏好）
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
    /**
     * 本进程内是否**至少成功落地过一次**下载列表（单调：false → true，永不回退）。
     *
     * 存在的唯一理由是空态文案的分档（见 [downloadEmptyPhase]）：`tasks` 为空既可能是
     * "首屏还没拉到"，也可能是"确实一个任务都没有"，只看 `tasks` 分不出来，
     * 而拿 [isLoading] 当"首屏"用会随手动刷新 / 写后回读反复翻转 ⇒ 空态图标文案闪一下。
     * 由 `DownloadModule.loadDownloads` 在成功分支里随现有的那次 `copy(...)` 一并置 true，
     * 不额外产生重组。
     */
    val hasLoadedOnce: Boolean = false,
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
    // 启动期配置改过但 aria2 正忙没重启：设置页据此提示"下次引擎启动生效"
    val pendingEngineRestart: Boolean = false,
    // Cached tracker list for editing
    val cachedTrackerList: String = "",
    val trackerListLoading: Boolean = false,
    // Duplicate download dialog
    val duplicateInfo: DuplicateInfo? = null
)

// ========== Download Empty Phase ==========

/**
 * 下载任务列表**空**时，那条常驻空态 item 该说什么（2026-09-05）。
 *
 * 分档而不是"加载中就把空态摘掉"：空态 item 一旦随加载态挂/卸，图标与文案就会**闪一下**
 * （用户反馈"进下载管理时中间那块闪一下，添加任务后就正常了" —— 有任务时空态本来就不挂，
 * 所以只有空列表才闪）。现在容器与 item 都恒定，只有这行文案按档位换。
 */
enum class DownloadEmptyPhase {
    /** 首屏还没成功拉到过列表，请求在路上（或还没发出）：文案说"正在读取"。 */
    LOADING,

    /** 首屏拉取失败且从未成功过：不能继续说"正在读取"（那是假加载），指向顶部错误横幅。 */
    LOAD_FAILED,

    /** 已成功拉过列表，此刻确实没有（该分类下的）任务：这才是真正的空态文案。 */
    EMPTY,
}

/**
 * 空态档位判据（纯函数，可单测）。
 *
 * 关键性质：[hasLoadedOnce] 为 true 时**直接返回 [DownloadEmptyPhase.EMPTY]，不再看
 * [isLoading]**。这样手动刷新、写后回读、任何将来会写 loading 的路径都无法再改变空态的
 * 外观 —— 空态闪烁在结构上被排除，而不是靠"确保只有首屏才写 loading"的约定。
 *
 * @param hasLoadedOnce 本进程是否成功落地过一次列表（[DownloadState.hasLoadedOnce]）。
 * @param isLoading     当前是否有非静默请求在飞（[DownloadState.isLoading]）。
 * @param hasError      是否有待显示的错误（[DownloadState.errorMessage] != null）。
 */
fun downloadEmptyPhase(
    hasLoadedOnce: Boolean,
    isLoading: Boolean,
    hasError: Boolean,
): DownloadEmptyPhase = when {
    hasLoadedOnce -> DownloadEmptyPhase.EMPTY
    isLoading -> DownloadEmptyPhase.LOADING
    hasError -> DownloadEmptyPhase.LOAD_FAILED
    // 还没加载过、没在加载、也没报错 = 进页面首帧（LaunchedEffect 还没跑到 loadDownloads）。
    // 归到 LOADING 而不是 EMPTY：否则首帧会先说"还没有下载任务"，下一帧改口说"正在读取"，
    // 又是一次文案闪动。
    else -> DownloadEmptyPhase.LOADING
}

