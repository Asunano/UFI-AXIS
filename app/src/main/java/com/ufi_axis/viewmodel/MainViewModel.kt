package com.ufi_axis.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.ufi_axis.data.api.FileItem
import com.ufi_axis.data.api.FileInfoResponse
import com.ufi_axis.data.model.*
import com.ufi_axis.data.repository.UfiAxisRepository
import com.ufi_axis.data.repository.WebSocketRepository
import com.ufi_axis.util.CacheManager
import com.ufi_axis.util.CachedDashboardData
import com.ufi_axis.util.DebugLog
import com.ufi_axis.util.NetworkMonitor
import com.ufi_axis.util.BackgroundManager
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

// ========== State Classes ==========

data class DashboardState(
    val deviceInfo: DeviceInfoResponse? = null,
    val cpuInfo: CpuInfo? = null,
    val memoryInfo: MemoryInfo? = null,
    val batteryInfo: BatteryInfo? = null,
    val storageInfo: StorageInfo? = null,
    val uptimeInfo: UptimeInfo? = null,
    val trafficRealtime: TrafficRealtime? = null,
    val trafficSummary: TrafficSummary? = null,
    val networkStatus: NetworkStatusResponse? = null,
    val signalInfo: SignalInfo? = null,
    val cpuHistory: List<CpuHistoryRecord> = emptyList(),
    val signalHistory: List<SignalHistoryRecord> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isOffline: Boolean = false,
    val lastUpdated: Long? = null
)

data class NetworkState(
    val signalInfo: SignalInfo? = null,
    val networkStatus: NetworkStatusResponse? = null,
    val simInfo: SimInfoResponse? = null,
    val wifiSettings: JsonElement? = null,
    val wifiClients: JsonElement? = null,
    val wifiEnabled: Boolean = false,
    val mobileDataEnabled: Boolean = false,
    val bandStatus: JsonElement? = null,
    val cellInfo: JsonElement? = null,
    val blacklistInfo: JsonElement? = null,
    val lanSettings: JsonElement? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class ToolsState(
    val atResponse: String = "",
    val atHistory: List<String> = emptyList(),
    val smsList: List<SmsRecord> = emptyList(),
    val ussdResponse: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class AlertsState(
    val config: AlertConfig? = null,
    val alerts: List<AlertRecord> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class UpdateState(
    val hasUpdate: Boolean = false,
    val serverVersion: String? = null,
    val updateUrl: String? = null,
    val checking: Boolean = false
)

data class AdbState(
    val status: AdbStatus? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class TasksState(
    val tasks: List<ScheduledTask> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class SmsForwardState(
    val config: SmsForwardConfig? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class AppManageState(
    val apps: List<AppItem> = emptyList(),
    val selectedApp: AppDetailResponse? = null,
    val filter: String = "user",
    val hasRoot: Boolean = false,
    val isLoading: Boolean = false,
    val installLoading: Boolean = false,
    val errorMessage: String? = null
)

data class DeviceSettingsState(
    val settings: JsonElement? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class SpeedTestState(
    val isRunning: Boolean = false,
    val result: String? = null,
    val errorMessage: String? = null
)

data class StorageVolume(
    val label: String,     // "内部存储" / "SD卡" / "U盘"
    val mountPath: String, // "/storage/emulated/0"
    val totalSize: String,
    val usedSize: String,
    val availSize: String,
    val usePercent: String
)

data class PhoneDownloadHistoryItem(
    val fileName: String,
    val fileSize: Long,
    val sourcePath: String,
    val downloadedAt: Long,
    val status: String  // "completed", "cancelled", "error"
)

data class FileManagerState(
    val currentPath: String = "",
    val files: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val isUploading: Boolean = false,
    val uploadProgress: Float = -1f,
    val uploadFileName: String = "",
    val isDownloading: Boolean = false,
    val downloadProgress: Float = -1f,
    val downloadFileName: String = "",
    val downloadStatus: String = "idle",  // idle, downloading,paused,completed,error
    val downloadBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val phoneDownloadHistory: List<PhoneDownloadHistoryItem> = emptyList(),
    val errorMessage: String? = null,
    val clipboard: ClipboardEntry? = null,
    val selectedFile: FileInfoResponse? = null,
    val fileContent: String? = null,
    val operationMessage: String? = null,
    val rootMode: Boolean = false,
    val pendingProtectedPath: String? = null,
    val diskUsage: com.google.gson.JsonElement? = null,
    val storageVolumes: List<StorageVolume> = emptyList(),
    val storageRoot: String = "",  // Navigation floor: can't go above this
    val searchResults: List<FileItem>? = null,
    val sortBy: String = "name",
    val multiSelectMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet()
)

data class ClipboardEntry(
    val sourcePaths: List<String>,
    val isCut: Boolean
) {
    /** Backward-compatible single path accessor */
    val sourcePath: String get() = sourcePaths.first()
}

data class DebugLogState(
    val logs: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val filterLevel: String? = null,
    val autoRefresh: Boolean = true,
    val errorMessage: String? = null
)

data class TrafficManagementState(
    val limitConfig: TrafficLimitConfig? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

data class AdvancedState(
    val cpuCores: JsonElement? = null,
    val ttydRunning: Boolean = false,
    val iperf3Running: Boolean = false,
    val fotaStatus: JsonElement? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val operationMessage: String? = null
)

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
    val engine: String = "java",
    val protocol: String = "http",
    val connections: Int = 0,
    val seeders: Int = 0
)

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
    val fileAllocation: String = "prealloc",
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
    val trackerListLoading: Boolean = false
)

// ========== ViewModel ==========

class MainViewModel(
    private val repository: UfiAxisRepository,
    private val webSocketRepository: WebSocketRepository,
    private val networkMonitor: NetworkMonitor,
    private val appContext: android.content.Context
) : ViewModel() {

    private val _dashboardState = MutableStateFlow(DashboardState())
    val dashboardState: StateFlow<DashboardState> = _dashboardState.asStateFlow()

    private val _networkState = MutableStateFlow(NetworkState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val _toolsState = MutableStateFlow(ToolsState())
    val toolsState: StateFlow<ToolsState> = _toolsState.asStateFlow()

    private val _alertsState = MutableStateFlow(AlertsState())
    val alertsState: StateFlow<AlertsState> = _alertsState.asStateFlow()

    private val _updateState = MutableStateFlow(UpdateState())
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _adbState = MutableStateFlow(AdbState())
    val adbState: StateFlow<AdbState> = _adbState.asStateFlow()

    private val _tasksState = MutableStateFlow(TasksState())
    val tasksState: StateFlow<TasksState> = _tasksState.asStateFlow()

    private val _smsForwardState = MutableStateFlow(SmsForwardState())
    val smsForwardState: StateFlow<SmsForwardState> = _smsForwardState.asStateFlow()

    private val _appManageState = MutableStateFlow(AppManageState())
    val appManageState: StateFlow<AppManageState> = _appManageState.asStateFlow()

    private val _deviceSettingsState = MutableStateFlow(DeviceSettingsState())
    val deviceSettingsState: StateFlow<DeviceSettingsState> = _deviceSettingsState.asStateFlow()

    private val _speedTestState = MutableStateFlow(SpeedTestState())
    val speedTestState: StateFlow<SpeedTestState> = _speedTestState.asStateFlow()

    private val _fileManagerState = MutableStateFlow(FileManagerState())
    val fileManagerState: StateFlow<FileManagerState> = _fileManagerState.asStateFlow()

    private val _debugLogState = MutableStateFlow(DebugLogState())
    val debugLogState: StateFlow<DebugLogState> = _debugLogState.asStateFlow()

    private val _trafficManagementState = MutableStateFlow(TrafficManagementState())
    val trafficManagementState: StateFlow<TrafficManagementState> = _trafficManagementState.asStateFlow()

    private val _advancedState = MutableStateFlow(AdvancedState())
    val advancedState: StateFlow<AdvancedState> = _advancedState.asStateFlow()

    private val _monitorState = MutableStateFlow(MonitorState())
    val monitorState: StateFlow<MonitorState> = _monitorState.asStateFlow()

    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    val backgroundManager = BackgroundManager(appContext)

    private var autoRefreshJob: kotlinx.coroutines.Job? = null
    private var downloadJob: kotlinx.coroutines.Job? = null
    private val gson = Gson()
    private val cacheManager = CacheManager(appContext.applicationContext)
    private var wasOffline = false

    init {
        viewModelScope.launch { loadCachedData() }
        try { webSocketRepository.connect(viewModelScope) } catch (_: Exception) {}
        collectWebSocketMessages()
        observeNetworkState()
        viewModelScope.launch { delay(500); refreshDashboard() }
        checkForUpdate()
    }

    private suspend fun loadCachedData() {
        val cached = cacheManager.getLatestData() ?: return
        val state = _dashboardState.value
        _dashboardState.value = state.copy(
            cpuInfo = cached.cpuJson?.let { gson.fromJson(it, CpuInfo::class.java) } ?: state.cpuInfo,
            memoryInfo = cached.memoryJson?.let { gson.fromJson(it, MemoryInfo::class.java) } ?: state.memoryInfo,
            trafficRealtime = cached.trafficJson?.let { gson.fromJson(it, TrafficRealtime::class.java) } ?: state.trafficRealtime,
            signalInfo = cached.signalJson?.let { gson.fromJson(it, SignalInfo::class.java) } ?: state.signalInfo,
            lastUpdated = cached.timestamp
        )
    }

    private fun collectWebSocketMessages() {
        viewModelScope.launch {
            webSocketRepository.messages.collect { message ->
                try {
                    when (message.type) {
                        "cpu" -> try {
                            _dashboardState.value = _dashboardState.value.copy(cpuInfo = gson.fromJson(message.data, CpuInfo::class.java))
                            saveToCache()
                        } catch (e: Exception) { DebugLog.parseError("WS", "cpu", message.data.toString(), e) }
                        "memory" -> try {
                            _dashboardState.value = _dashboardState.value.copy(memoryInfo = gson.fromJson(message.data, MemoryInfo::class.java))
                            saveToCache()
                        } catch (e: Exception) { DebugLog.parseError("WS", "memory", message.data.toString(), e) }
                        "traffic" -> try {
                            _dashboardState.value = _dashboardState.value.copy(trafficRealtime = gson.fromJson(message.data, TrafficRealtime::class.java))
                            saveToCache()
                        } catch (e: Exception) { DebugLog.parseError("WS", "traffic", message.data.toString(), e) }
                        "signal" -> try {
                            _dashboardState.value = _dashboardState.value.copy(signalInfo = gson.fromJson(message.data, SignalInfo::class.java))
                            saveToCache()
                        } catch (e: Exception) { DebugLog.parseError("WS", "signal", message.data.toString(), e) }
                    }
                } catch (e: Exception) { DebugLog.parseError("WS", "unknown", message.data?.toString() ?: "", e) }
            }
        }
    }

    private fun saveToCache() {
        val state = _dashboardState.value
        cacheManager.saveDataAsync(CachedDashboardData(
            timestamp = System.currentTimeMillis(),
            cpuJson = state.cpuInfo?.let { gson.toJson(it) },
            memoryJson = state.memoryInfo?.let { gson.toJson(it) },
            trafficJson = state.trafficRealtime?.let { gson.toJson(it) },
            signalJson = state.signalInfo?.let { gson.toJson(it) }
        ))
    }

    private fun observeNetworkState() {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                if (wasOffline && isOnline) { webSocketRepository.reconnect(); refreshDashboard() }
                wasOffline = !isOnline
                _dashboardState.value = _dashboardState.value.copy(isOffline = !isOnline)
                if (!isOnline) {
                    val cached = webSocketRepository.cachedData.value
                    cached["cpu"]?.let { _dashboardState.value = _dashboardState.value.copy(cpuInfo = gson.fromJson(it.data, CpuInfo::class.java)) }
                    cached["memory"]?.let { _dashboardState.value = _dashboardState.value.copy(memoryInfo = gson.fromJson(it.data, MemoryInfo::class.java)) }
                    cached["traffic"]?.let { _dashboardState.value = _dashboardState.value.copy(trafficRealtime = gson.fromJson(it.data, TrafficRealtime::class.java)) }
                    cached["signal"]?.let { _dashboardState.value = _dashboardState.value.copy(signalInfo = gson.fromJson(it.data, SignalInfo::class.java)) }
                }
            }
        }
    }

    // ========== Dashboard ==========

    fun refreshDashboard() {
        viewModelScope.launch {
            _dashboardState.value = _dashboardState.value.copy(isLoading = true, errorMessage = null)
            try {
                val info = async { runCatching { repository.getDeviceInfo() } }
                val cpu = async { runCatching { repository.getCpuInfo() } }
                val mem = async { runCatching { repository.getMemoryInfo() } }
                val bat = async { runCatching { repository.getBatteryInfo() } }
                val stor = async { runCatching { repository.getStorageInfo() } }
                val up = async { runCatching { repository.getUptime() } }
                val traffic = async { runCatching { repository.getTrafficRealtime() } }
                val summary = async { runCatching { repository.getTrafficSummary() } }
                val net = async { runCatching { repository.getNetworkStatus() } }
                val sig = async { runCatching { repository.getSignalInfo() } }

                // 收集每个接口的结果，记录失败的接口及原因
                val results = listOf(
                    "设备信息" to info.await(),
                    "CPU" to cpu.await(),
                    "内存" to mem.await(),
                    "电池" to bat.await(),
                    "存储" to stor.await(),
                    "运行时间" to up.await(),
                    "实时流量" to traffic.await(),
                    "流量统计" to summary.await(),
                    "网络状态" to net.await(),
                    "信号" to sig.await()
                )
                val failures = results.filter { it.second.isFailure }
                val errorMsg = if (failures.isNotEmpty()) {
                    failures.joinToString("; ") { (name, result) ->
                        val ex = result.exceptionOrNull()
                        "$name: ${ex?.message ?: "未知错误"}"
                    }
                } else null

                _dashboardState.value = _dashboardState.value.copy(
                    deviceInfo = info.await().getOrNull(), cpuInfo = cpu.await().getOrNull(),
                    memoryInfo = mem.await().getOrNull(), batteryInfo = bat.await().getOrNull(),
                    storageInfo = stor.await().getOrNull(), uptimeInfo = up.await().getOrNull(),
                    trafficRealtime = traffic.await().getOrNull(), trafficSummary = summary.await().getOrNull(),
                    networkStatus = net.await().getOrNull(), signalInfo = sig.await().getOrNull(),
                    isLoading = false, errorMessage = errorMsg,
                    isOffline = _dashboardState.value.isOffline, lastUpdated = System.currentTimeMillis()
                )
                saveToCache()
            } catch (e: Exception) {
                DebugLog.e("Dashboard", "refreshDashboard failed", e)
                _dashboardState.value = _dashboardState.value.copy(isLoading = false, errorMessage = "连接失败: ${e.message}")
            }
        }
    }

    // ========== Network ==========

    fun refreshNetwork() {
        viewModelScope.launch {
            _networkState.value = _networkState.value.copy(isLoading = true, errorMessage = null)
            try {
                val sig = async { runCatching { repository.getSignalInfo() } }
                val net = async { runCatching { repository.getNetworkStatus() } }
                val sim = async { runCatching { repository.getSimInfo() } }
                val wifi = async { runCatching { repository.getWifiSettings() } }
                val clients = async { runCatching { repository.getWifiClients() } }

                val wifiSettingsJson = wifi.await().getOrNull()
                val netStatus = net.await().getOrNull()
                val wifiOn = try {
                    val settingsStr = wifiSettingsJson?.asJsonObject?.get("settings")?.asString
                    val innerJson = if (settingsStr != null) gson.fromJson(settingsStr, JsonElement::class.java)?.asJsonObject else null
                    innerJson?.get("wifi_onoff_state")?.asString == "1" ||
                    innerJson?.get("WiFiModuleSwitch")?.asString == "1" ||
                    wifiSettingsJson?.asJsonObject?.get("wifi_onoff_state")?.asString == "1" ||
                    wifiSettingsJson?.asJsonObject?.get("WiFiModuleSwitch")?.asString == "1" ||
                    wifiSettingsJson?.asJsonObject?.get("wifi_enable")?.asString == "1"
                } catch (_: Exception) { false }

                _networkState.value = NetworkState(
                    signalInfo = sig.await().getOrNull(), networkStatus = netStatus,
                    simInfo = sim.await().getOrNull(), wifiSettings = wifiSettingsJson,
                    wifiClients = clients.await().getOrNull(), wifiEnabled = wifiOn,
                    mobileDataEnabled = netStatus?.mobile_data ?: false, isLoading = false
                )
            } catch (e: Exception) {
                DebugLog.e("Network", "refreshNetwork failed", e)
                _networkState.value = _networkState.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}")
            }
        }
    }

    fun toggleMobileData(enabled: Boolean) {
        viewModelScope.launch {
            _networkState.value = _networkState.value.copy(isLoading = true, errorMessage = null)
            try {
                repository.setMobileData(enabled)
                delay(1500); refreshNetwork()
            } catch (e: Exception) {
                _networkState.value = _networkState.value.copy(errorMessage = "操作失败: ${e.message}", isLoading = false)
            }
        }
    }

    fun toggleAirplaneMode(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val resp = repository.setAirplaneMode(enabled)
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "操作失败")
                delay(1000); refreshDashboard(); refreshNetwork()
            } catch (e: Exception) {
                _networkState.value = _networkState.value.copy(errorMessage = "操作失败: ${e.message}")
            }
        }
    }

    fun setNetworkMode(mode: String) {
        viewModelScope.launch {
            try {
                val resp = repository.setNetworkMode(mode)
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "设置失败")
                delay(1000); refreshNetwork()
            } catch (e: Exception) {
                _networkState.value = _networkState.value.copy(errorMessage = "设置失败: ${e.message}")
            }
        }
    }

    fun lockBand(rat: String, bands: String, action: String) {
        viewModelScope.launch {
            try {
                val resp = repository.setBandLock(rat, bands, action)
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = if (action == "unlock") "解锁失败" else "锁频失败")
            } catch (e: Exception) {
                _networkState.value = _networkState.value.copy(errorMessage = "锁频失败: ${e.message}")
            }
        }
    }

    fun loadDeviceSettings() {
        viewModelScope.launch {
            _deviceSettingsState.value = _deviceSettingsState.value.copy(isLoading = true)
            try {
                val settings = repository.getDeviceSettings()
                _deviceSettingsState.value = DeviceSettingsState(settings = settings)
            } catch (e: Exception) {
                _deviceSettingsState.value = DeviceSettingsState(errorMessage = "加载设备设置失败: ${e.message}")
            }
        }
    }

    fun loadBandStatus() {
        viewModelScope.launch {
            try {
                val status = repository.getBandStatus()
                _networkState.value = _networkState.value.copy(bandStatus = status)
            } catch (_: Exception) {}
        }
    }

    fun runSpeedTest() {
        viewModelScope.launch {
            _speedTestState.value = SpeedTestState(isRunning = true)
            try {
                val start = android.os.SystemClock.elapsedRealtime()
                val responseBody = repository.speedTest()
                val buf = ByteArray(8192)
                var totalBytes = 0L; var bytesRead: Int
                while (responseBody.byteStream().read(buf).also { bytesRead = it } != -1) { totalBytes += bytesRead }
                val elapsed = android.os.SystemClock.elapsedRealtime() - start
                val seconds = elapsed / 1000.0
                val speedMbps = if (seconds > 0 && totalBytes > 0) (totalBytes * 8.0 / seconds / 1_000_000) else 0.0
                _speedTestState.value = SpeedTestState(result = "下载 ${totalBytes / 1024} KB, 耗时 ${"%.1f".format(seconds)}s, 速度 ${"%.2f".format(speedMbps)} Mbps")
            } catch (e: Exception) {
                _speedTestState.value = SpeedTestState(errorMessage = "测速失败: ${e.message}")
            }
        }
    }

    // ========== Tools ==========

    fun sendAtCommand(command: String) {
        viewModelScope.launch {
            _toolsState.value = _toolsState.value.copy(isLoading = true, errorMessage = null)
            try {
                val response = repository.sendAtCommand(command)
                val historyItem = "> ${response.command}\n${response.response}"
                _toolsState.value = _toolsState.value.copy(atResponse = response.response,
                    atHistory = listOf(historyItem) + _toolsState.value.atHistory, isLoading = false)
            } catch (e: Exception) {
                _toolsState.value = _toolsState.value.copy(atResponse = "AT 通道未连接或指令执行失败", isLoading = false, errorMessage = "AT 指令失败: ${e.message}")
            }
        }
    }

    fun sendSms(phone: String, message: String) {
        viewModelScope.launch {
            _toolsState.value = _toolsState.value.copy(isLoading = true, errorMessage = null)
            try {
                val response = repository.sendSms(phone, message)
                _toolsState.value = _toolsState.value.copy(isLoading = false, errorMessage = if (!response.success) "短信发送失败" else null)
                if (response.success) loadSmsList()
            } catch (e: Exception) {
                _toolsState.value = _toolsState.value.copy(isLoading = false, errorMessage = "发送失败: ${e.message}")
            }
        }
    }

    fun loadSmsList() {
        viewModelScope.launch {
            try { _toolsState.value = _toolsState.value.copy(smsList = repository.getSmsList().messages) }
            catch (_: Exception) {}
        }
    }

    fun sendUssd(code: String) {
        viewModelScope.launch {
            _toolsState.value = _toolsState.value.copy(isLoading = true, errorMessage = null)
            try {
                val response = repository.sendUssd(code)
                _toolsState.value = _toolsState.value.copy(ussdResponse = response.response, isLoading = false)
            } catch (e: Exception) {
                _toolsState.value = _toolsState.value.copy(ussdResponse = "USSD 请求失败", isLoading = false, errorMessage = "USSD 失败: ${e.message}")
            }
        }
    }

    // ========== Alerts ==========

    fun loadAlerts() {
        viewModelScope.launch {
            _alertsState.value = _alertsState.value.copy(isLoading = true, errorMessage = null)
            try {
                val config = async { runCatching { repository.getAlertConfig() } }
                val list = async { runCatching { repository.getAlertList(50) } }
                _alertsState.value = AlertsState(config = config.await().getOrNull(), alerts = list.await().getOrNull()?.alerts ?: emptyList(), isLoading = false)
            } catch (e: Exception) {
                _alertsState.value = _alertsState.value.copy(isLoading = false, errorMessage = "加载告警失败: ${e.message}")
            }
        }
    }

    fun updateAlertConfig(config: AlertConfig) {
        viewModelScope.launch {
            try { repository.updateAlertConfig(config); loadAlerts() }
            catch (e: Exception) { _alertsState.value = _alertsState.value.copy(errorMessage = "更新告警配置失败: ${e.message}") }
        }
    }

    fun ackAlert(id: Long) {
        viewModelScope.launch {
            try { repository.ackAlert(id); loadAlerts() }
            catch (e: Exception) { _alertsState.value = _alertsState.value.copy(errorMessage = "确认告警失败: ${e.message}") }
        }
    }

    // ========== Device Control ==========

    fun rebootDevice() {
        viewModelScope.launch {
            try {
                val resp = repository.rebootDevice()
                if (resp.success) _dashboardState.value = _dashboardState.value.copy(errorMessage = "设备正在重启...")
                else _dashboardState.value = _dashboardState.value.copy(errorMessage = "重启失败")
            } catch (e: Exception) { _dashboardState.value = _dashboardState.value.copy(errorMessage = "重启失败: ${e.message}") }
        }
    }

    fun factoryReset() {
        viewModelScope.launch {
            try { repository.factoryReset() }
            catch (e: Exception) { _dashboardState.value = _dashboardState.value.copy(errorMessage = "恢复出厂失败: ${e.message}") }
        }
    }

    fun setDeviceMode(enabled: Boolean) {
        viewModelScope.launch {
            try { repository.setDeviceMode(enabled) }
            catch (e: Exception) { _dashboardState.value = _dashboardState.value.copy(errorMessage = "ADB调试设置失败: ${e.message}") }
        }
    }

    fun setUsbMode(mode: Int) {
        viewModelScope.launch {
            try {
                val resp = repository.setUsbMode(mode)
                if (!resp.success) _dashboardState.value = _dashboardState.value.copy(errorMessage = "USB模式切换失败")
            } catch (e: Exception) { _dashboardState.value = _dashboardState.value.copy(errorMessage = "USB模式切换失败: ${e.message}") }
        }
    }

    fun setWifiConfig(config: Map<String, Any>) {
        viewModelScope.launch {
            try { repository.setWifiConfig(config) }
            catch (e: Exception) { _dashboardState.value = _dashboardState.value.copy(errorMessage = "WiFi设置失败: ${e.message}") }
        }
    }

    fun setWifiAdvConfig(config: Map<String, Any>) {
        viewModelScope.launch {
            try { repository.setWifiAdvConfig(config) }
            catch (e: Exception) { _dashboardState.value = _dashboardState.value.copy(errorMessage = "WiFi高级设置失败: ${e.message}") }
        }
    }

    fun setWifiPower(level: Int) {
        viewModelScope.launch {
            try { repository.setWifiPower(level) }
            catch (e: Exception) { _dashboardState.value = _dashboardState.value.copy(errorMessage = "功率设置失败: ${e.message}") }
        }
    }

    fun setWifiGuest(config: Map<String, Any>) {
        viewModelScope.launch {
            try { repository.setWifiGuest(config) }
            catch (e: Exception) { _dashboardState.value = _dashboardState.value.copy(errorMessage = "访客WiFi设置失败: ${e.message}") }
        }
    }

    fun changePassword(oldPwd: String, newPwd: String) {
        viewModelScope.launch {
            try { repository.changePassword(oldPwd, newPwd) }
            catch (e: Exception) { _dashboardState.value = _dashboardState.value.copy(errorMessage = "修改密码失败: ${e.message}") }
        }
    }

    fun setApnConfig(config: Map<String, Any>) {
        viewModelScope.launch {
            try { repository.setApnConfig(config) }
            catch (e: Exception) { _dashboardState.value = _dashboardState.value.copy(errorMessage = "APN设置失败: ${e.message}") }
        }
    }

    fun setTr069Config(config: Map<String, Any>) {
        viewModelScope.launch {
            try { repository.setTr069Config(config) }
            catch (e: Exception) { _dashboardState.value = _dashboardState.value.copy(errorMessage = "TR-069设置失败: ${e.message}") }
        }
    }

    // ========== Network Control ==========

    fun setBearerPreference(preference: String) {
        viewModelScope.launch {
            try {
                val resp = repository.setBearerPreference(preference)
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "设置失败")
                refreshNetwork()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "设置失败: ${e.message}") }
        }
    }

    fun connectNetwork() {
        viewModelScope.launch {
            try {
                val resp = repository.connectNetwork()
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "连接失败")
                else _networkState.value = _networkState.value.copy(errorMessage = null)
                delay(2000); refreshNetwork()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "连接失败: ${e.message}") }
        }
    }

    fun disconnectNetwork() {
        viewModelScope.launch {
            try {
                val resp = repository.disconnectNetwork()
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "断开失败")
                else _networkState.value = _networkState.value.copy(errorMessage = null)
                delay(2000); refreshNetwork()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "断开失败: ${e.message}") }
        }
    }

    fun setConnectionMode(mode: String) {
        viewModelScope.launch {
            try {
                val resp = repository.setConnectionMode(mode)
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "设置失败")
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "设置失败: ${e.message}") }
        }
    }

    // ========== Config ==========

    fun syncGatewayConfig(ip: String, password: String, port: Int = 8080) {
        viewModelScope.launch {
            try {
                DebugLog.d("Config", "syncing goform: ip=$ip port=$port")
                repository.updateConfig(mapOf("goform_ip" to ip, "goform_port" to port, "goform_password" to password))
            } catch (e: Exception) { DebugLog.w("Config", "syncGatewayConfig failed", e) }
        }
    }

    // ========== SMS / SIM ==========

    fun deleteSms(id: String) {
        viewModelScope.launch {
            try { repository.deleteSms(id); loadSmsList() }
            catch (e: Exception) { _toolsState.value = _toolsState.value.copy(errorMessage = "删除失败: ${e.message}") }
        }
    }

    fun markSmsRead(id: String) {
        viewModelScope.launch {
            try { repository.markSmsRead(id); loadSmsList() }
            catch (e: Exception) { _toolsState.value = _toolsState.value.copy(errorMessage = "操作失败: ${e.message}") }
        }
    }

    fun switchSimSlot(slot: Int) {
        viewModelScope.launch {
            try { repository.switchSimSlot(slot) }
            catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "切换卡槽失败: ${e.message}") }
        }
    }

    // ========== Auto Refresh ==========

    fun startAutoRefresh(intervalMs: Long = 5000L) {
        stopAutoRefresh()
        autoRefreshJob = viewModelScope.launch { while (isActive) { refreshDashboard(); delay(intervalMs) } }
    }

    fun stopAutoRefresh() { autoRefreshJob?.cancel(); autoRefreshJob = null }

    fun clearError() {
        _dashboardState.value = _dashboardState.value.copy(errorMessage = null)
        _networkState.value = _networkState.value.copy(errorMessage = null)
        _toolsState.value = _toolsState.value.copy(errorMessage = null)
        _alertsState.value = _alertsState.value.copy(errorMessage = null)
    }

    fun loadCpuHistory(hours: Int = 24) {
        viewModelScope.launch {
            try { _dashboardState.value = _dashboardState.value.copy(cpuHistory = repository.getCpuHistory(hours).records) }
            catch (_: Exception) {}
        }
    }

    fun loadSignalHistory(hours: Int = 24) {
        viewModelScope.launch {
            try { _dashboardState.value = _dashboardState.value.copy(signalHistory = repository.getSignalHistory(hours).records) }
            catch (_: Exception) {}
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _updateState.value = _updateState.value.copy(checking = true)
            try {
                val versionInfo = repository.getServerVersion()
                _updateState.value = UpdateState(
                    hasUpdate = compareVersions(versionInfo.version, "1.0") > 0,
                    serverVersion = versionInfo.version, updateUrl = versionInfo.update_url, checking = false
                )
            } catch (_: Exception) { _updateState.value = _updateState.value.copy(checking = false) }
        }
    }

    // ========== ADB ==========

    fun refreshAdbStatus() {
        viewModelScope.launch {
            _adbState.value = _adbState.value.copy(isLoading = true)
            try { _adbState.value = AdbState(status = repository.getAdbStatus()) }
            catch (e: Exception) { _adbState.value = AdbState(errorMessage = "获取ADB状态失败: ${e.message}") }
        }
    }

    fun startAdb(port: Int = 5555) {
        viewModelScope.launch {
            _adbState.value = _adbState.value.copy(isLoading = true, errorMessage = null)
            try {
                val resp = repository.startAdb(port)
                if (!resp.success) _adbState.value = _adbState.value.copy(errorMessage = "启动失败", isLoading = false)
                else refreshAdbStatus()
            } catch (e: Exception) { _adbState.value = _adbState.value.copy(errorMessage = "启动失败: ${e.message}", isLoading = false) }
        }
    }

    fun stopAdb() {
        viewModelScope.launch {
            _adbState.value = _adbState.value.copy(isLoading = true, errorMessage = null)
            try {
                val resp = repository.stopAdb()
                if (!resp.success) _adbState.value = _adbState.value.copy(errorMessage = "停止失败", isLoading = false)
                else refreshAdbStatus()
            } catch (e: Exception) { _adbState.value = _adbState.value.copy(errorMessage = "停止失败: ${e.message}", isLoading = false) }
        }
    }

    // ========== SMS Forward ==========

    fun loadSmsForwardConfig() {
        viewModelScope.launch {
            _smsForwardState.value = _smsForwardState.value.copy(isLoading = true)
            try { _smsForwardState.value = SmsForwardState(config = repository.getSmsForwardConfig()) }
            catch (e: Exception) { _smsForwardState.value = SmsForwardState(errorMessage = "加载失败: ${e.message}") }
        }
    }

    fun saveSmsForwardConfig(config: SmsForwardConfig) {
        viewModelScope.launch {
            _smsForwardState.value = _smsForwardState.value.copy(isLoading = true, errorMessage = null)
            try {
                val result = repository.saveSmsForwardConfig(config)
                if (result.success) {
                    _smsForwardState.value = _smsForwardState.value.copy(isLoading = false)
                    loadSmsForwardConfig()  // 重新加载以反映最新状态
                } else {
                    _smsForwardState.value = _smsForwardState.value.copy(isLoading = false, errorMessage = "保存失败：服务器返回失败")
                }
            } catch (e: Exception) {
                _smsForwardState.value = _smsForwardState.value.copy(isLoading = false, errorMessage = "保存失败: ${e.message}")
            }
        }
    }

    fun testSmsForward() {
        viewModelScope.launch {
            _smsForwardState.value = _smsForwardState.value.copy(isLoading = true, errorMessage = null)
            try {
                val result = repository.testSmsForward()
                val obj = result.getAsJsonObject()
                val success = obj.get("success")?.getAsBoolean() ?: false
                val error = obj.get("error")?.getAsString()
                _smsForwardState.value = _smsForwardState.value.copy(
                    isLoading = false,
                    errorMessage = if (success) null else (error ?: "测试发送失败")
                )
            } catch (e: Exception) {
                _smsForwardState.value = _smsForwardState.value.copy(isLoading = false, errorMessage = "测试失败: ${e.message}")
            }
        }
    }

    // ========== Scheduled Tasks ==========

    fun loadTaskList() {
        viewModelScope.launch {
            _tasksState.value = _tasksState.value.copy(isLoading = true)
            try { _tasksState.value = TasksState(tasks = repository.getTaskList().tasks) }
            catch (e: Exception) { _tasksState.value = TasksState(errorMessage = "加载失败: ${e.message}") }
        }
    }

    fun createTask(task: ScheduledTask) {
        viewModelScope.launch {
            try { repository.createTask(task); loadTaskList() }
            catch (e: Exception) { _tasksState.value = _tasksState.value.copy(errorMessage = "创建失败: ${e.message}") }
        }
    }

    fun updateTask(id: String, task: ScheduledTask) {
        viewModelScope.launch {
            try { repository.updateTask(id, task); loadTaskList() }
            catch (e: Exception) { _tasksState.value = _tasksState.value.copy(errorMessage = "更新失败: ${e.message}") }
        }
    }

    fun deleteTask(id: String) {
        viewModelScope.launch {
            try { repository.deleteTask(id); loadTaskList() }
            catch (e: Exception) { _tasksState.value = _tasksState.value.copy(errorMessage = "删除失败: ${e.message}") }
        }
    }

    fun clearTasks() {
        viewModelScope.launch {
            try { repository.clearTasks(); loadTaskList() }
            catch (e: Exception) { _tasksState.value = _tasksState.value.copy(errorMessage = "清除失败: ${e.message}") }
        }
    }

    // ========== Debug Logs ==========

    fun loadDebugLogs(level: String? = _debugLogState.value.filterLevel) {
        viewModelScope.launch {
            _debugLogState.value = _debugLogState.value.copy(isLoading = true, errorMessage = null)
            try {
                val result = repository.getDebugLogs(level, 300)
                val obj = result.getAsJsonObject()
                val logsArray = obj.getAsJsonArray("logs")
                val logs = logsArray?.map { it.asString } ?: emptyList()
                _debugLogState.value = _debugLogState.value.copy(
                    logs = logs,
                    isLoading = false,
                    filterLevel = level
                )
            } catch (e: Exception) {
                _debugLogState.value = _debugLogState.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}")
            }
        }
    }

    fun clearDebugLogs() {
        viewModelScope.launch {
            try {
                repository.clearDebugLogs()
                _debugLogState.value = DebugLogState(filterLevel = _debugLogState.value.filterLevel)
            } catch (e: Exception) {
                _debugLogState.value = _debugLogState.value.copy(errorMessage = "清除失败: ${e.message}")
            }
        }
    }

    fun setDebugLogFilter(level: String?) {
        _debugLogState.value = _debugLogState.value.copy(filterLevel = level)
        loadDebugLogs(level)
    }

    fun toggleDebugLogAutoRefresh(enabled: Boolean) {
        _debugLogState.value = _debugLogState.value.copy(autoRefresh = enabled)
    }

    fun syncDebugMode(enabled: Boolean) {
        viewModelScope.launch {
            try { repository.updateConfig(mapOf("debug_mode" to enabled)) }
            catch (_: Exception) {}
        }
    }

    // ========== Traffic Management ==========

    fun loadTrafficLimit() {
        viewModelScope.launch {
            _trafficManagementState.value = _trafficManagementState.value.copy(
                isLoading = true, errorMessage = null, successMessage = null
            )
            try {
                val result = repository.getTrafficLimit()
                val config = gson.fromJson(result, TrafficLimitConfig::class.java)
                _trafficManagementState.value = _trafficManagementState.value.copy(
                    limitConfig = config, isLoading = false
                )
            } catch (e: Exception) {
                _trafficManagementState.value = _trafficManagementState.value.copy(
                    isLoading = false, errorMessage = "加载失败: ${e.message}"
                )
            }
        }
    }

    fun saveDataLimit(enabled: Boolean, limitSize: String, limitUnit: String,
                      alertPercent: String, autoClear: Boolean, clearDate: String) {
        viewModelScope.launch {
            _trafficManagementState.value = _trafficManagementState.value.copy(
                isSaving = true, errorMessage = null, successMessage = null
            )
            try {
                val resp = repository.setDataLimit(mapOf(
                    "enabled" to enabled,
                    "limit_size" to limitSize,
                    "limit_unit" to limitUnit,
                    "alert_percent" to alertPercent,
                    "auto_clear" to autoClear,
                    "clear_date" to clearDate
                ))
                _trafficManagementState.value = _trafficManagementState.value.copy(
                    isSaving = false,
                    successMessage = if (resp.success) "设置已保存" else null,
                    errorMessage = if (!resp.success) "保存失败" else null
                )
                if (resp.success) loadTrafficLimit()
            } catch (e: Exception) {
                _trafficManagementState.value = _trafficManagementState.value.copy(
                    isSaving = false, errorMessage = "保存失败: ${e.message}"
                )
            }
        }
    }

    fun calibrateFlow(way: String, data: String, time: String = "0") {
        viewModelScope.launch {
            _trafficManagementState.value = _trafficManagementState.value.copy(
                isSaving = true, errorMessage = null, successMessage = null
            )
            try {
                val resp = repository.calibrateFlow(mapOf("way" to way, "data" to data, "time" to time))
                _trafficManagementState.value = _trafficManagementState.value.copy(
                    isSaving = false,
                    successMessage = if (resp.success) "校准成功" else null,
                    errorMessage = if (!resp.success) "校准失败" else null
                )
                if (resp.success) loadTrafficLimit()
            } catch (e: Exception) {
                _trafficManagementState.value = _trafficManagementState.value.copy(
                    isSaving = false, errorMessage = "校准失败: ${e.message}"
                )
            }
        }
    }

    fun clearTrafficMessage() {
        _trafficManagementState.value = _trafficManagementState.value.copy(
            errorMessage = null, successMessage = null
        )
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }

    override fun onCleared() { super.onCleared(); stopAutoRefresh(); webSocketRepository.disconnect() }

    // ========== App Management ==========

    fun loadAppList(filter: String = "user") {
        viewModelScope.launch {
            _appManageState.value = _appManageState.value.copy(isLoading = true, filter = filter, errorMessage = null)
            try {
                val resp = repository.getAppList(filter)
                _appManageState.value = _appManageState.value.copy(apps = resp.apps.sortedByDescending { it.isFrozen || !it.isEnabled }, hasRoot = resp.root, isLoading = false)
            } catch (e: Exception) { _appManageState.value = _appManageState.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}") }
        }
    }

    fun loadAppDetail(packageName: String) {
        viewModelScope.launch {
            try { _appManageState.value = _appManageState.value.copy(selectedApp = repository.getAppDetail(packageName)) }
            catch (e: Exception) { _appManageState.value = _appManageState.value.copy(errorMessage = "获取详情失败: ${e.message}") }
        }
    }

    fun dismissAppDetail() { _appManageState.value = _appManageState.value.copy(selectedApp = null) }

    fun performAppAction(action: String, packageName: String) {
        viewModelScope.launch {
            try {
                when (action) {
                    "uninstall" -> repository.uninstallApp(packageName)
                    "disable" -> repository.disableApp(packageName)
                    "enable" -> repository.enableApp(packageName)
                    "clear" -> repository.clearAppData(packageName)
                    "force-stop" -> repository.forceStopApp(packageName)
                    "freeze" -> repository.freezeApp(packageName)
                    "unfreeze" -> repository.unfreezeApp(packageName)
                }
                _appManageState.value = _appManageState.value.copy(selectedApp = null, errorMessage = null)
                loadAppList(_appManageState.value.filter)
            } catch (e: Exception) { _appManageState.value = _appManageState.value.copy(errorMessage = "操作失败: ${e.message}") }
        }
    }

    fun installAppFromUrl(url: String) {
        viewModelScope.launch {
            _appManageState.value = _appManageState.value.copy(installLoading = true)
            try {
                val resp = repository.installAppFromUrl(url)
                _appManageState.value = _appManageState.value.copy(installLoading = false, errorMessage = if (!resp.success) resp.message else null)
                if (resp.success) loadAppList(_appManageState.value.filter)
            } catch (e: Exception) { _appManageState.value = _appManageState.value.copy(installLoading = false, errorMessage = "安装失败: ${e.message}") }
        }
    }

    fun installAppFromPath(path: String) {
        viewModelScope.launch {
            _appManageState.value = _appManageState.value.copy(installLoading = true)
            try {
                val resp = repository.installApp(path)
                _appManageState.value = _appManageState.value.copy(installLoading = false, errorMessage = if (!resp.success) resp.message else null)
                if (resp.success) loadAppList(_appManageState.value.filter)
            } catch (e: Exception) { _appManageState.value = _appManageState.value.copy(installLoading = false, errorMessage = "安装失败: ${e.message}") }
        }
    }

    // ========== Enhanced Device (仅通过 Core API) ==========

    fun setWifiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try { repository.setWifiEnabled(enabled); delay(1000); refreshNetwork() }
            catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "WiFi开关失败: ${e.message}") }
        }
    }

    fun toggleVolte(enabled: Boolean, slot: Int = 0) {
        viewModelScope.launch {
            try { repository.setVolteStatus(enabled, slot) }
            catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "VoLTE设置失败: ${e.message}") }
        }
    }

    fun toggleVonr(enabled: Boolean, slot: Int = 0) {
        viewModelScope.launch {
            try { repository.setVonrStatus(enabled, slot) }
            catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "VoNR设置失败: ${e.message}") }
        }
    }

    fun setFotaDisabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val resp = repository.setFotaDisabled(enabled)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "FOTA设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "FOTA设置失败: ${e.message}") }
        }
    }

    fun setPerformanceMode(mode: String) {
        viewModelScope.launch {
            try {
                val resp = repository.setPerformanceMode(mode)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "性能模式设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "性能模式设置失败: ${e.message}") }
        }
    }

    fun setLedEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val resp = repository.setLedEnabled(enabled)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "指示灯设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "指示灯设置失败: ${e.message}") }
        }
    }

    fun setRoamingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val resp = repository.setRoamingEnabled(enabled)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "漫游设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "漫游设置失败: ${e.message}") }
        }
    }

    fun setUsbTethering(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val resp = repository.setUsbTethering(enabled)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "USB共享设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "USB共享设置失败: ${e.message}") }
        }
    }

    fun setSaMode(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val resp = repository.setSaMode(enabled)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "SA模式设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "SA模式设置失败: ${e.message}") }
        }
    }

    fun switchWifiChip(chip: String) {
        viewModelScope.launch {
            try {
                // 缓存用户偏好，设备重启后可自动恢复
                com.ufi_axis.util.AppPreferences(appContext).preferredWifiChip = chip
                repository.switchWifiChip(chip); delay(3000); refreshNetwork()
            }
            catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "WiFi频段切换失败: ${e.message}") }
        }
    }

    /** 检查设备当前 WiFi 芯片是否与用户偏好一致，不一致则自动切换 */
    fun restoreWifiChipPreference() {
        viewModelScope.launch {
            try {
                val prefs = com.ufi_axis.util.AppPreferences(appContext)
                val preferred = prefs.preferredWifiChip
                if (preferred.isBlank()) return@launch
                // 读取设备当前芯片
                val settings = repository.getWifiSettings()
                val json = try { settings.asJsonObject } catch (_: Exception) { return@launch }
                val currentChip = json.get("wifi_chip")?.asString ?: return@launch
                if (currentChip != preferred) {
                    repository.switchWifiChip(preferred)
                    delay(2000)
                    refreshNetwork()
                }
            } catch (_: Exception) { /* 静默失败 */ }
        }
    }

    fun setWifiNfc(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val resp = repository.setWifiNfc(enabled)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "NFC设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "NFC设置失败: ${e.message}") }
        }
    }

    fun setWifiSleep(time: String) {
        viewModelScope.launch {
            try {
                val resp = repository.setWifiSleep(time)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "WiFi休眠设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "WiFi休眠设置失败: ${e.message}") }
        }
    }

    fun setSambaSetting(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val resp = repository.setSambaSetting(enabled)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "Samba设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "Samba设置失败: ${e.message}") }
        }
    }

    fun shutdownDevice() {
        viewModelScope.launch {
            try {
                val resp = repository.shutdownDevice()
                if (resp.success) _dashboardState.value = _dashboardState.value.copy(errorMessage = "设备正在关机...")
                else _dashboardState.value = _dashboardState.value.copy(errorMessage = "关机失败")
            } catch (e: Exception) { _dashboardState.value = _dashboardState.value.copy(errorMessage = "关机失败: ${e.message}") }
        }
    }

    fun setRestartSchedule(enabled: Boolean, time: String) {
        viewModelScope.launch {
            try {
                val resp = repository.setRestartSchedule(enabled, time)
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "定时重启设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "定时重启设置失败: ${e.message}") }
        }
    }

    fun cellLock(pci: String, earfcn: String, rat: String) {
        viewModelScope.launch {
            try {
                repository.cellLock(pci, earfcn, rat)
                delay(500)
                loadCellInfo()
            }
            catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "锁基站失败: ${e.message}") }
        }
    }

    fun unlockAllCell() {
        viewModelScope.launch {
            try {
                repository.unlockAllCell()
                delay(500)
                loadCellInfo()
            }
            catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "解锁基站失败: ${e.message}") }
        }
    }

    fun loadCellInfo() {
        viewModelScope.launch {
            try {
                _networkState.value = _networkState.value.copy(cellInfo = repository.getCellInfo())
            } catch (e: Exception) {
                _networkState.value = _networkState.value.copy(errorMessage = "基站信息查询失败: ${e.message}")
            }
        }
    }

    fun loadLanSettings() {
        viewModelScope.launch {
            try {
                _networkState.value = _networkState.value.copy(lanSettings = repository.getLanSettings())
            } catch (e: Exception) {
                _networkState.value = _networkState.value.copy(errorMessage = "LAN设置查询失败: ${e.message}")
            }
        }
    }

    fun setDhcpSetting(lanIp: String, lanNetmask: String, dhcpType: String,
                       dhcpStart: String, dhcpEnd: String, dhcpLease: String) {
        viewModelScope.launch {
            try {
                val resp = repository.setDhcpSetting(lanIp, lanNetmask, dhcpType, dhcpStart, dhcpEnd, dhcpLease)
                if (resp.success) {
                    delay(500)
                    loadLanSettings()
                } else {
                    _networkState.value = _networkState.value.copy(errorMessage = "DHCP设置失败")
                }
            } catch (e: Exception) {
                _networkState.value = _networkState.value.copy(errorMessage = "DHCP设置失败: ${e.message}")
            }
        }
    }

    fun loadBlacklist() {
        viewModelScope.launch {
            try {
                _networkState.value = _networkState.value.copy(blacklistInfo = repository.getAccessControl())
            } catch (e: Exception) {
                _networkState.value = _networkState.value.copy(errorMessage = "黑名单查询失败: ${e.message}")
            }
        }
    }

    fun blockDevice(mac: String, name: String) {
        viewModelScope.launch {
            try {
                // 读取当前黑名单，追加新设备
                val current = repository.getAccessControl()
                val json = try { current.asJsonObject } catch (_: Exception) { com.google.gson.JsonObject() }
                val existingMacs = (json.get("BlackMacList")?.asString ?: "").split(";").filter { it.isNotBlank() }
                val existingNames = (json.get("BlackNameList")?.asString ?: "").split(";").filter { it.isNotBlank() }
                val aclMode = json.get("AclMode")?.asString ?: "2"
                // 追加（去重）
                val newMacs = if (mac in existingMacs) existingMacs else listOf(mac) + existingMacs
                val newNames = if (name in existingNames) existingNames else listOf(name) + existingNames
                repository.setAccessControl(aclMode, newMacs.joinToString(";"), newNames.joinToString(";"))
                loadBlacklist()
            } catch (e: Exception) {
                _networkState.value = _networkState.value.copy(errorMessage = "拉黑失败: ${e.message}")
            }
        }
    }

    fun unblockDevice(mac: String, name: String) {
        viewModelScope.launch {
            try {
                // 读取当前黑名单，移除目标设备
                val current = repository.getAccessControl()
                val json = try { current.asJsonObject } catch (_: Exception) { com.google.gson.JsonObject() }
                val existingMacs = (json.get("BlackMacList")?.asString ?: "").split(";").filter { it.isNotBlank() }
                val existingNames = (json.get("BlackNameList")?.asString ?: "").split(";").filter { it.isNotBlank() }
                val aclMode = json.get("AclMode")?.asString ?: "2"
                val newMacs = existingMacs.filter { it != mac }
                val newNames = existingNames.filter { it != name }
                repository.setAccessControl(aclMode, newMacs.joinToString(";"), newNames.joinToString(";"))
                loadBlacklist()
            } catch (e: Exception) {
                _networkState.value = _networkState.value.copy(errorMessage = "解除拉黑失败: ${e.message}")
            }
        }
    }

    fun setAccessControl(aclMode: String, macList: String = "") {
        viewModelScope.launch {
            try {
                val resp = repository.setAccessControl(aclMode, macList)
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "访问控制设置失败")
            } catch (e: Exception) {
                _networkState.value = _networkState.value.copy(errorMessage = "访问控制设置失败: ${e.message}")
            }
        }
    }

    fun setHostname(mac: String, hostname: String) {
        viewModelScope.launch {
            try { repository.setHostname(mac, hostname) }
            catch (e: Exception) { _dashboardState.value = _dashboardState.value.copy(errorMessage = "主机名设置失败: ${e.message}") }
        }
    }

    // ========== File Management ==========

    fun loadFileList(path: String) {
        viewModelScope.launch {
            _fileManagerState.value = _fileManagerState.value.copy(
                isLoading = true, errorMessage = null, operationMessage = null, pendingProtectedPath = null
            )
            try {
                val force = _fileManagerState.value.rootMode
                val resp = repository.listFiles(path, force)
                if (resp.error == "PROTECTED_PATH") {
                    // Path is protected — show warning dialog
                    _fileManagerState.value = _fileManagerState.value.copy(
                        isLoading = false, pendingProtectedPath = path
                    )
                } else {
                    _fileManagerState.value = _fileManagerState.value.copy(
                        currentPath = resp.path,
                        files = sortFiles(resp.files, _fileManagerState.value.sortBy),
                        isLoading = false,
                        errorMessage = resp.message
                    )
                }
            } catch (e: Exception) {
                _fileManagerState.value = _fileManagerState.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}")
            }
        }
    }

    fun navigateToDir(path: String) { loadFileList(path) }
    fun navigateToParent() {
        val current = _fileManagerState.value.currentPath
        val root = _fileManagerState.value.storageRoot
        val volumes = _fileManagerState.value.storageVolumes

        // At virtual root — nowhere to go
        if (current.isEmpty()) return

        // At the storage root floor (single-volume mode) — nowhere to go
        if (current == root && root.isNotEmpty()) return

        // Check if current path is a volume root
        val currentIsVolumeRoot = volumes.any { it.mountPath == current }

        if (currentIsVolumeRoot) {
            if (volumes.size > 1) {
                // Multi-volume: return to virtual root
                _fileManagerState.value = _fileManagerState.value.copy(
                    currentPath = "",
                    files = emptyList(),
                    isLoading = false
                )
            }
            // Single volume: can't go up from volume root
            return
        }

        val parentDir = current.substringBeforeLast("/", "").ifEmpty { "/" }
        loadFileList(parentDir)
    }

    fun refreshFileList() { loadFileList(_fileManagerState.value.currentPath) }

    fun toggleRootMode() {
        val newMode = !_fileManagerState.value.rootMode
        _fileManagerState.value = _fileManagerState.value.copy(rootMode = newMode)
        // Reload current path with new mode
        loadFileList(_fileManagerState.value.currentPath)
    }

    fun forceNavigate(path: String) {
        _fileManagerState.value = _fileManagerState.value.copy(rootMode = true, pendingProtectedPath = null)
        loadFileList(path)
    }

    fun dismissProtectedPath() {
        _fileManagerState.value = _fileManagerState.value.copy(pendingProtectedPath = null)
    }

    fun getFileInfo(path: String) {
        viewModelScope.launch {
            try {
                val info = repository.getFileInfo(path)
                _fileManagerState.value = _fileManagerState.value.copy(selectedFile = info)
            } catch (e: Exception) {
                _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "获取文件信息失败: ${e.message}")
            }
        }
    }

    fun dismissFileInfo() {
        _fileManagerState.value = _fileManagerState.value.copy(selectedFile = null, fileContent = null)
    }

    fun readFile(path: String) {
        viewModelScope.launch {
            try {
                val resp = repository.readFile(path)
                _fileManagerState.value = _fileManagerState.value.copy(fileContent = resp.content)
            } catch (e: Exception) {
                _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "读取文件失败: ${e.message}")
            }
        }
    }

    fun dismissFileContent() { _fileManagerState.value = _fileManagerState.value.copy(fileContent = null) }

    fun deleteFileOrDir(path: String) {
        viewModelScope.launch {
            try {
                val resp = repository.deleteFile(path)
                if (resp.success) { refreshFileList(); _fileManagerState.value = _fileManagerState.value.copy(operationMessage = "已删除") }
                else _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "删除失败")
            } catch (e: Exception) {
                _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "删除失败: ${e.message}")
            }
        }
    }

    fun renameFile(oldPath: String, newName: String) {
        viewModelScope.launch {
            try {
                val parent = oldPath.substringBeforeLast("/")
                val newPath = "$parent/$newName"
                val resp = repository.renameFile(oldPath, newPath)
                if (resp.success) { refreshFileList(); _fileManagerState.value = _fileManagerState.value.copy(operationMessage = "已重命名") }
                else _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "重命名失败")
            } catch (e: Exception) {
                _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "重命名失败: ${e.message}")
            }
        }
    }

    fun copyToClipboard(path: String, isCut: Boolean) {
        _fileManagerState.value = _fileManagerState.value.copy(
            clipboard = ClipboardEntry(listOf(path), isCut),
            operationMessage = if (isCut) "已剪切" else "已复制"
        )
    }

    fun clearClipboard() { _fileManagerState.value = _fileManagerState.value.copy(clipboard = null, operationMessage = null) }

    fun pasteFromClipboard(destinationDir: String) {
        val clip = _fileManagerState.value.clipboard ?: return
        viewModelScope.launch {
            try {
                var successCount = 0
                for (srcPath in clip.sourcePaths) {
                    val fileName = srcPath.substringAfterLast("/")
                    val destPath = "$destinationDir/$fileName"
                    val resp = if (clip.isCut) repository.moveFile(srcPath, destPath) else repository.copyFile(srcPath, destPath)
                    if (resp.success) successCount++
                }
                if (successCount > 0) {
                    val action = if (clip.isCut) "已移动" else "已粘贴"
                    val msg = if (successCount == clip.sourcePaths.size) "$action ${successCount} 项"
                              else "$action $successCount/${clip.sourcePaths.size} 项"
                    _fileManagerState.value = _fileManagerState.value.copy(clipboard = null, operationMessage = msg)
                    refreshFileList()
                } else {
                    _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "粘贴失败")
                }
            } catch (e: Exception) {
                _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "粘贴失败: ${e.message}")
            }
        }
    }

    fun createDirectory(path: String) {
        viewModelScope.launch {
            try {
                val resp = repository.createDirectory(path)
                if (resp.success) { refreshFileList(); _fileManagerState.value = _fileManagerState.value.copy(operationMessage = "已创建文件夹") }
                else _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "创建文件夹失败")
            } catch (e: Exception) {
                _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "创建文件夹失败: ${e.message}")
            }
        }
    }

    fun createFile(name: String) {
        viewModelScope.launch {
            try {
                val path = "${_fileManagerState.value.currentPath}/$name"
                val resp = repository.touchFile(path)
                if (resp.success) { refreshFileList(); _fileManagerState.value = _fileManagerState.value.copy(operationMessage = "已创建文件") }
                else _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "创建文件失败")
            } catch (e: Exception) {
                _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "创建文件失败: ${e.message}")
            }
        }
    }

    fun writeFile(path: String, content: String) {
        viewModelScope.launch {
            try {
                val resp = repository.writeFile(path, content)
                if (resp.success) _fileManagerState.value = _fileManagerState.value.copy(operationMessage = "已保存文件", fileContent = null)
                else _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "保存文件失败")
            } catch (e: Exception) {
                _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "保存文件失败: ${e.message}")
            }
        }
    }

    fun loadDiskUsage() {
        viewModelScope.launch {
            try {
                val raw = repository.getDiskUsage()
                val json = JsonParser.parseString(raw.toString()).asJsonObject
                val disksArr = json.getAsJsonArray("disks")

                val volumes = disksArr?.map { disk ->
                    val obj = disk.asJsonObject
                    StorageVolume(
                        label = obj.get("label")?.asString ?: "",
                        mountPath = obj.get("mount")?.asString ?: "",
                        totalSize = obj.get("size")?.asString ?: "",
                        usedSize = obj.get("used")?.asString ?: "",
                        availSize = obj.get("available")?.asString ?: "",
                        usePercent = obj.get("usePercent")?.asString ?: ""
                    )
                } ?: emptyList()

                val currentPath = _fileManagerState.value.currentPath
                val isInitialLoad = currentPath.isEmpty() && _fileManagerState.value.storageVolumes.isEmpty()

                if (volumes.size <= 1) {
                    val root = volumes.firstOrNull()?.mountPath?.ifEmpty { null } ?: "/storage/emulated/0"
                    val safeVolumes = if (volumes.isEmpty()) {
                        listOf(StorageVolume("内部存储", root, "", "", "", ""))
                    } else volumes
                    _fileManagerState.value = _fileManagerState.value.copy(
                        diskUsage = raw,
                        storageVolumes = safeVolumes,
                        storageRoot = root
                    )
                    // Only auto-navigate on initial load; preserve current path when returning from viewers
                    if (isInitialLoad) {
                        loadFileList(root)
                    }
                } else {
                    // Multiple volumes
                    _fileManagerState.value = _fileManagerState.value.copy(
                        diskUsage = raw,
                        storageVolumes = volumes,
                        storageRoot = if (isInitialLoad) "" else _fileManagerState.value.storageRoot,
                        currentPath = if (isInitialLoad) "" else currentPath,
                        files = if (isInitialLoad) emptyList() else _fileManagerState.value.files,
                        isLoading = if (isInitialLoad) false else _fileManagerState.value.isLoading
                    )
                }
            } catch (_: Exception) {
                val fallback = "/storage/emulated/0"
                val isInitialLoad = _fileManagerState.value.currentPath.isEmpty()
                        && _fileManagerState.value.storageVolumes.isEmpty()
                _fileManagerState.value = _fileManagerState.value.copy(
                    storageRoot = fallback,
                    storageVolumes = listOf(StorageVolume("内部存储", fallback, "", "", "", ""))
                )
                if (isInitialLoad) {
                    loadFileList(fallback)
                }
            }
        }
    }

    fun searchFiles(query: String) {
        viewModelScope.launch {
            try {
                val resp = repository.searchFiles(_fileManagerState.value.currentPath, query)
                val files = resp.asJsonObject?.getAsJsonArray("files")?.mapNotNull {
                    try {
                        val obj = it.asJsonObject
                        FileItem(
                            name = obj.get("name")?.asString ?: "",
                            path = obj.get("path")?.asString ?: "",
                            isDirectory = obj.get("isDirectory")?.asBoolean ?: false,
                            size = 0, lastModified = 0, permissions = "", isSymlink = false
                        )
                    } catch (_: Exception) { null }
                } ?: emptyList()
                _fileManagerState.value = _fileManagerState.value.copy(searchResults = files)
            } catch (e: Exception) {
                _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "搜索失败: ${e.message}")
            }
        }
    }

    fun clearSearchResults() { _fileManagerState.value = _fileManagerState.value.copy(searchResults = null) }

    fun chmodFile(path: String, mode: String) {
        viewModelScope.launch {
            try {
                val resp = repository.chmodFile(path, mode)
                if (resp.success) { refreshFileList(); _fileManagerState.value = _fileManagerState.value.copy(operationMessage = "权限已修改") }
                else _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "修改权限失败")
            } catch (e: Exception) {
                _fileManagerState.value = _fileManagerState.value.copy(errorMessage = "修改权限失败: ${e.message}")
            }
        }
    }

    fun setSortBy(sort: String) {
        _fileManagerState.value = _fileManagerState.value.copy(sortBy = sort)
        refreshFileList()
    }

    // ── 多选操控 ──

    fun toggleMultiSelectMode() {
        val newMode = !_fileManagerState.value.multiSelectMode
        _fileManagerState.value = _fileManagerState.value.copy(
            multiSelectMode = newMode,
            selectedPaths = if (newMode) emptySet() else _fileManagerState.value.selectedPaths
        )
    }

    fun toggleFileSelection(path: String) {
        val current = _fileManagerState.value.selectedPaths
        _fileManagerState.value = _fileManagerState.value.copy(
            selectedPaths = if (path in current) current - path else current + path
        )
    }

    fun selectAllFiles() {
        _fileManagerState.value = _fileManagerState.value.copy(
            selectedPaths = _fileManagerState.value.files.map { it.path }.toSet()
        )
    }

    fun batchDeleteSelected() {
        viewModelScope.launch {
            val paths = _fileManagerState.value.selectedPaths
            if (paths.isEmpty()) return@launch
            var success = 0
            for (path in paths) {
                try { if (repository.deleteFile(path).success) success++ } catch (_: Exception) {}
            }
            _fileManagerState.value = _fileManagerState.value.copy(
                multiSelectMode = false, selectedPaths = emptySet(),
                operationMessage = "已删除 $success/${paths.size} 个文件"
            )
            refreshFileList()
        }
    }

    fun batchCopySelected() {
        val paths = _fileManagerState.value.selectedPaths
        if (paths.isEmpty()) return
        _fileManagerState.value = _fileManagerState.value.copy(
            clipboard = ClipboardEntry(paths.toList(), false),
            multiSelectMode = false, selectedPaths = emptySet(),
            operationMessage = "已复制 ${paths.size} 个文件"
        )
    }

    fun batchCutSelected() {
        val paths = _fileManagerState.value.selectedPaths
        if (paths.isEmpty()) return
        _fileManagerState.value = _fileManagerState.value.copy(
            clipboard = ClipboardEntry(paths.toList(), true),
            multiSelectMode = false, selectedPaths = emptySet(),
            operationMessage = "已剪切 ${paths.size} 个文件"
        )
    }

    // ── 下载 / 安装 / 打开 ──

    fun getDownloadUrl(path: String): String {
        val prefs = com.ufi_axis.util.AppPreferences(appContext)
        return "http://${prefs.serverIp}:${prefs.serverPort}/api/files/download?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
    }

    private var _downloadPartialFile: java.io.File? = null

    private val _phoneHistoryPrefs by lazy {
        appContext.getSharedPreferences("phone_download_history", android.content.Context.MODE_PRIVATE)
    }

    fun loadPhoneDownloadHistory() {
        val jsonStr = _phoneHistoryPrefs.getString("history", null) ?: return
        try {
            val arr = JsonParser.parseString(jsonStr).asJsonArray
            val items = arr.map { elem ->
                val obj = elem.asJsonObject
                PhoneDownloadHistoryItem(
                    fileName = obj.get("fileName")?.asString ?: "",
                    fileSize = obj.get("fileSize")?.asLong ?: 0L,
                    sourcePath = obj.get("sourcePath")?.asString ?: "",
                    downloadedAt = obj.get("downloadedAt")?.asLong ?: 0L,
                    status = obj.get("status")?.asString ?: "completed"
                )
            }
            _fileManagerState.value = _fileManagerState.value.copy(phoneDownloadHistory = items)
        } catch (_: Exception) {}
    }

    private fun savePhoneDownloadHistory(item: PhoneDownloadHistoryItem) {
        val current = _fileManagerState.value.phoneDownloadHistory.toMutableList()
        current.add(0, item)
        // Keep max 100 entries
        val trimmed = if (current.size > 100) current.take(100) else current
        _fileManagerState.value = _fileManagerState.value.copy(phoneDownloadHistory = trimmed)
        try {
            val arr = com.google.gson.JsonArray()
            trimmed.forEach { h ->
                val obj = com.google.gson.JsonObject()
                obj.addProperty("fileName", h.fileName)
                obj.addProperty("fileSize", h.fileSize)
                obj.addProperty("sourcePath", h.sourcePath)
                obj.addProperty("downloadedAt", h.downloadedAt)
                obj.addProperty("status", h.status)
                arr.add(obj)
            }
            _phoneHistoryPrefs.edit().putString("history", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun clearPhoneDownloadHistory() {
        _fileManagerState.value = _fileManagerState.value.copy(phoneDownloadHistory = emptyList())
        _phoneHistoryPrefs.edit().remove("history").apply()
    }

    fun downloadFileToPhone(path: String, fileName: String) {
        // Prevent duplicate downloads
        if (_fileManagerState.value.isDownloading) return
        // Cancel any previous paused download
        downloadJob?.cancel()

        downloadJob = viewModelScope.launch {
            _fileManagerState.value = _fileManagerState.value.copy(
                isDownloading = true, downloadProgress = 0f, downloadFileName = fileName,
                downloadStatus = "downloading", downloadBytes = 0L, downloadTotalBytes = 0L,
                operationMessage = null, errorMessage = null
            )

            val result = withContext(Dispatchers.IO) {
                try {
                    val prefs = com.ufi_axis.util.AppPreferences(appContext)
                    val url = getStreamUrl(path)

                    // Check for existing partial download (resume support)
                    val partialFile = _downloadPartialFile
                    val existingBytes = if (partialFile != null && partialFile.exists()) partialFile.length() else 0L

                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    val requestBuilder = okhttp3.Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer ${prefs.token}")
                    if (existingBytes > 0L) {
                        requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                    }

                    val response = client.newCall(requestBuilder.build()).execute()

                    // Determine total size
                    val contentRange = response.header("Content-Range")  // "bytes start-end/total"
                    val body = response.body ?: throw Exception("空响应")
                    val contentLen = body.contentLength()
                    val totalSize: Long = when {
                        contentRange != null -> {
                            contentRange.substringAfterLast("/").toLongOrNull()
                                ?: (existingBytes + contentLen.coerceAtLeast(0L))
                        }
                        contentLen > 0 -> existingBytes + contentLen
                        else -> -1L
                    }

                    // If server returned 200 instead of 206 for a resume request, start over
                    val resumeFrom = if (existingBytes > 0L && response.code == 206) existingBytes else 0L
                    if (resumeFrom == 0L && existingBytes > 0L) {
                        partialFile?.delete()
                    }

                    val targetFile = partialFile?.takeIf { it.exists() }
                        ?: java.io.File(appContext.cacheDir, "dl_${System.currentTimeMillis()}_$fileName")

                    withContext(Dispatchers.Main) {
                        _downloadPartialFile = targetFile
                        _fileManagerState.value = _fileManagerState.value.copy(
                            downloadTotalBytes = totalSize, downloadBytes = resumeFrom)
                    }

                    body.byteStream().use { input ->
                        java.io.FileOutputStream(targetFile, resumeFrom > 0L).use { output ->
                            val buf = ByteArray(8192)
                            var bytesDownloaded = resumeFrom
                            var lastUpdate = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                bytesDownloaded += n
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 200) { // throttle UI updates
                                    lastUpdate = now
                                    val progress = if (totalSize > 0)
                                        (bytesDownloaded.toFloat() / totalSize).coerceIn(0f, 0.99f)
                                    else -1f
                                    withContext(Dispatchers.Main) {
                                        _fileManagerState.value = _fileManagerState.value.copy(
                                            downloadProgress = progress, downloadBytes = bytesDownloaded)
                                    }
                                }
                            }
                            output.flush()
                            withContext(Dispatchers.Main) {
                                _fileManagerState.value = _fileManagerState.value.copy(
                                    downloadProgress = 1f, downloadBytes = bytesDownloaded)
                            }
                        }
                    }

                    // Move to Downloads via MediaStore
                    saveToDownloads(appContext, targetFile, fileName, totalSize)
                    targetFile.delete()
                    withContext(Dispatchers.Main) { _downloadPartialFile = null }
                    "success"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    "cancelled"
                } catch (e: Exception) {
                    "error:${e.localizedMessage ?: e.javaClass.simpleName}"
                }
            }

            when {
                result == "success" -> {
                    _fileManagerState.value = _fileManagerState.value.copy(
                        isDownloading = false, downloadStatus = "completed",
                        operationMessage = "下载完成: $fileName")
                    // Record to history
                    savePhoneDownloadHistory(PhoneDownloadHistoryItem(
                        fileName = fileName,
                        fileSize = _fileManagerState.value.downloadTotalBytes,
                        sourcePath = path,
                        downloadedAt = System.currentTimeMillis(),
                        status = "completed"
                    ))
                }
                result == "cancelled" -> {
                    _fileManagerState.value = _fileManagerState.value.copy(
                        isDownloading = false, downloadStatus = "paused")
                }
                else -> {
                    val msg = result.substringAfter("error:")
                    _fileManagerState.value = _fileManagerState.value.copy(
                        isDownloading = false, downloadStatus = "error",
                        errorMessage = "下载失败: $msg")
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadPartialFile?.delete()
        _downloadPartialFile = null
        _fileManagerState.value = _fileManagerState.value.copy(
            isDownloading = false, downloadStatus = "idle", downloadProgress = -1f)
    }

    fun resumeDownload(path: String, fileName: String) {
        // Re-start download; existing partial file will be detected automatically
        if (_downloadPartialFile?.exists() == true) {
            downloadFileToPhone(path, fileName)
        }
    }

    private fun saveToDownloads(context: android.content.Context, file: java.io.File, fileName: String, fileSize: Long) {
        val mimeType = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(fileName.substringAfterLast('.', "")) ?: "application/octet-stream"

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Android 10+ : MediaStore
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
                put(android.provider.MediaStore.Downloads.SIZE, fileSize)
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("无法创建下载文件")
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw Exception("无法写入下载文件")
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            // Android 9- : direct file copy
            @Suppress("DEPRECATION")
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val destFile = java.io.File(downloadsDir, fileName)
            // Handle name conflicts
            var target = destFile
            var counter = 1
            while (target.exists()) {
                val nameWithoutExt = fileName.substringBeforeLast('.')
                val ext = fileName.substringAfterLast('.', "")
                target = java.io.File(downloadsDir, "${nameWithoutExt}_$counter.$ext")
                counter++
            }
            file.copyTo(target, overwrite = true)
        }
    }

    fun installApk(path: String) {
        viewModelScope.launch {
            try {
                _fileManagerState.value = _fileManagerState.value.copy(operationMessage = "正在安装 APK 到设备...")
                val prefs = com.ufi_axis.util.AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/apps/install"

                val result = withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val json = com.google.gson.JsonObject().apply { addProperty("path", path) }
                    val mediaType = "application/json".toMediaTypeOrNull()!!
                    val body = json.toString().toRequestBody(mediaType)
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .post(body)
                        .addHeader("Authorization", "Bearer ${prefs.token}")
                        .build()
                    val response = client.newCall(request).execute()
                    val respBody = response.body?.string() ?: "{}"
                    com.google.gson.JsonParser.parseString(respBody).asJsonObject
                }

                val success = result.get("success")?.asBoolean ?: false
                val message = result.get("message")?.asString ?: "未知结果"

                _fileManagerState.value = _fileManagerState.value.copy(
                    operationMessage = if (success) "安装成功: $message" else null,
                    errorMessage = if (!success) "安装失败: $message" else null
                )
            } catch (e: Exception) {
                _fileManagerState.value = _fileManagerState.value.copy(
                    operationMessage = null,
                    errorMessage = "安装失败: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }
    }

    fun getStreamUrl(path: String): String {
        val prefs = com.ufi_axis.util.AppPreferences(appContext)
        return "http://${prefs.serverIp}:${prefs.serverPort}/api/files/stream?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
    }

    fun uploadFileToServer(localUri: android.net.Uri, targetDir: String) {
        // Prevent duplicate uploads
        if (_fileManagerState.value.isUploading) return

        viewModelScope.launch {
            _fileManagerState.value = _fileManagerState.value.copy(
                isUploading = true, uploadProgress = 0f, uploadFileName = "",
                operationMessage = null, errorMessage = null
            )
            try {
                val prefs = com.ufi_axis.util.AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/files/upload"
                val token = prefs.token

                val result = withContext(Dispatchers.IO) {
                    val mimeType = appContext.contentResolver.getType(localUri) ?: "application/octet-stream"

                    // Resolve file name, ensure extension is present
                    val fileName = try {
                        val rawName = appContext.contentResolver.query(localUri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIdx >= 0) (cursor.getString(nameIdx) ?: "uploaded_file") else "uploaded_file"
                            } else "uploaded_file"
                        } ?: "uploaded_file"
                        if (!rawName.contains('.')) {
                            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                            if (ext != null) "$rawName.$ext" else rawName
                        } else rawName
                    } catch (_: Exception) { "uploaded_file" }

                    // Update UI with filename
                    withContext(Dispatchers.Main) {
                        _fileManagerState.value = _fileManagerState.value.copy(uploadFileName = fileName)
                    }

                    // Get total file size for progress tracking
                    val totalSize = try {
                        appContext.contentResolver.openFileDescriptor(localUri, "r")?.use { it.statSize } ?: -1L
                    } catch (_: Exception) { -1L }

                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .retryOnConnectionFailure(false)
                        .build()

                    fun buildRequestBody(): okhttp3.RequestBody {
                        val inputStream = appContext.contentResolver.openInputStream(localUri)
                            ?: throw Exception("无法读取文件")
                        val mediaType = mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()!!

                        val fileBody = object : okhttp3.RequestBody() {
                            override fun contentType() = mediaType
                            override fun contentLength() = totalSize
                            override fun writeTo(sink: okio.BufferedSink) {
                                inputStream.use { input ->
                                    val buf = ByteArray(8192)
                                    var bytesWritten = 0L
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n <= 0) break
                                        sink.write(buf, 0, n)
                                        bytesWritten += n
                                        // Update progress (throttle to avoid flooding main thread)
                                        if (totalSize > 0 && bytesWritten % 65536 < 8192) {
                                            val progress = (bytesWritten.toFloat() / totalSize).coerceIn(0f, 0.99f)
                                            _fileManagerState.value = _fileManagerState.value.copy(uploadProgress = progress)
                                        }
                                    }
                                }
                            }
                        }

                        return okhttp3.MultipartBody.Builder()
                            .setType(okhttp3.MultipartBody.FORM)
                            .addFormDataPart("path", targetDir)
                            .addFormDataPart("file", fileName, fileBody)
                            .build()
                    }

                    // Execute with 429 retry (up to 2 retries with backoff)
                    var lastError = ""
                    for (attempt in 0..2) {
                        if (attempt > 0) {
                            delay(1500L * attempt)
                            withContext(Dispatchers.Main) {
                                _fileManagerState.value = _fileManagerState.value.copy(uploadProgress = 0f)
                            }
                        }

                        val request = okhttp3.Request.Builder()
                            .url(url)
                            .addHeader("Authorization", "Bearer $token")
                            .post(buildRequestBody())
                            .build()

                        val response = client.newCall(request).execute()

                        if (response.isSuccessful) {
                            return@withContext "success:$fileName"
                        }

                        if (response.code == 429 && attempt < 2) {
                            response.close()
                            continue
                        }

                        val errorBody = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                        lastError = if (errorBody.contains("error")) {
                            try { Gson().fromJson(errorBody, Map::class.java)["error"]?.toString() ?: "HTTP ${response.code}" }
                            catch (_: Exception) { "HTTP ${response.code}" }
                        } else "HTTP ${response.code}"
                        break
                    }
                    "error:$lastError"
                }

                if (result.startsWith("success:")) {
                    val fileName = result.substringAfter("success:")
                    _fileManagerState.value = _fileManagerState.value.copy(
                        isUploading = false, uploadProgress = 1f, uploadFileName = "",
                        operationMessage = "上传成功: $fileName"
                    )
                    refreshFileList()
                } else {
                    val errorMsg = result.substringAfter("error:")
                    _fileManagerState.value = _fileManagerState.value.copy(
                        isUploading = false, uploadProgress = -1f, uploadFileName = "",
                        operationMessage = "上传失败: $errorMsg"
                    )
                }
            } catch (e: Exception) {
                _fileManagerState.value = _fileManagerState.value.copy(
                    isUploading = false, uploadProgress = -1f, uploadFileName = "",
                    operationMessage = "上传失败: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    fun clearFileOperationMessage() { _fileManagerState.value = _fileManagerState.value.copy(operationMessage = null) }

    private fun sortFiles(files: List<FileItem>, sortBy: String): List<FileItem> {
        val dirs = files.filter { it.isDirectory || it.isSymlink }
        val regular = files.filter { !it.isDirectory && !it.isSymlink }
        val comparator: Comparator<FileItem> = when (sortBy) {
            "size" -> compareBy { it.size }
            "date" -> compareByDescending { it.lastModified }
            "type" -> compareBy { it.name.substringAfterLast(".", "").lowercase() }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        return dirs.sortedWith(comparator) + regular.sortedWith(comparator)
    }

    // ========== Advanced Tools ==========

    fun loadAdvancedStatus() {
        viewModelScope.launch {
            _advancedState.value = _advancedState.value.copy(isLoading = true, errorMessage = null)
            try {
                val ttyd = runCatching { repository.getTtydStatus() }
                val iperf3 = runCatching { repository.getIperf3Status() }
                _advancedState.value = _advancedState.value.copy(
                    ttydRunning = ttyd.getOrNull()?.asJsonObject?.get("running")?.asBoolean ?: false,
                    iperf3Running = iperf3.getOrNull()?.asJsonObject?.get("running")?.asBoolean ?: false,
                    isLoading = false
                )
            } catch (e: Exception) {
                _advancedState.value = _advancedState.value.copy(isLoading = false, errorMessage = "状态加载失败: ${e.message}")
            }
        }
    }

    fun toggleTtyd(start: Boolean) {
        viewModelScope.launch {
            try {
                if (start) repository.startTtyd() else repository.stopTtyd()
                delay(1000); loadAdvancedStatus()
            } catch (e: Exception) {
                _advancedState.value = _advancedState.value.copy(errorMessage = "TTYD操作失败: ${e.message}")
            }
        }
    }

    fun toggleIperf3(start: Boolean) {
        viewModelScope.launch {
            try {
                if (start) repository.startIperf3() else repository.stopIperf3()
                delay(500); loadAdvancedStatus()
            } catch (e: Exception) {
                _advancedState.value = _advancedState.value.copy(errorMessage = "iperf3操作失败: ${e.message}")
            }
        }
    }

    fun loadCpuCores() {
        viewModelScope.launch {
            try {
                _advancedState.value = _advancedState.value.copy(cpuCores = repository.getCpuCores())
            } catch (e: Exception) {
                _advancedState.value = _advancedState.value.copy(errorMessage = "CPU核心查询失败: ${e.message}")
            }
        }
    }

    fun setCpuCores(enable: Boolean) {
        viewModelScope.launch {
            try {
                repository.setCpuCores(enable)
                delay(800)
                val cores = runCatching { repository.getCpuCores() }.getOrNull()
                _advancedState.value = _advancedState.value.copy(
                    cpuCores = cores ?: _advancedState.value.cpuCores,
                    operationMessage = if (enable) "小核已开启" else "小核已关闭"
                )
            } catch (e: Exception) {
                _advancedState.value = _advancedState.value.copy(errorMessage = "CPU核心设置失败: ${e.message}")
            }
        }
    }

    fun loadFotaStatus() {
        viewModelScope.launch {
            try {
                _advancedState.value = _advancedState.value.copy(fotaStatus = repository.getFotaStatus())
            } catch (e: Exception) {
                _advancedState.value = _advancedState.value.copy(errorMessage = "FOTA状态查询失败: ${e.message}")
            }
        }
    }

    fun disableFota() {
        viewModelScope.launch {
            try {
                repository.disableFotaAdvanced()
                delay(500); loadFotaStatus()
                _advancedState.value = _advancedState.value.copy(operationMessage = "FOTA已禁用")
            } catch (e: Exception) {
                _advancedState.value = _advancedState.value.copy(errorMessage = "FOTA禁用失败: ${e.message}")
            }
        }
    }

    fun netAccelerate() {
        viewModelScope.launch {
            try {
                repository.netAccelerate()
                _advancedState.value = _advancedState.value.copy(operationMessage = "网络加速已执行")
            } catch (e: Exception) {
                _advancedState.value = _advancedState.value.copy(errorMessage = "网络加速失败: ${e.message}")
            }
        }
    }

    fun disablePhantomKiller() {
        viewModelScope.launch {
            try {
                repository.disablePhantomKiller()
                _advancedState.value = _advancedState.value.copy(operationMessage = "Phantom Killer已禁用")
            } catch (e: Exception) {
                _advancedState.value = _advancedState.value.copy(errorMessage = "操作失败: ${e.message}")
            }
        }
    }

    fun clearAdvancedMessage() {
        _advancedState.value = _advancedState.value.copy(errorMessage = null, operationMessage = null)
    }

    // ========== Monitor ==========

    fun loadMonitorHistory(hours: Int = 24) {
        viewModelScope.launch {
            _monitorState.value = _monitorState.value.copy(
                selectedHours = hours, isLoading = true, errorMessage = null
            )
            try {
                // 最多 2 个并发请求，避免低端设备被 8 个并行 DB 查询压垮
                val semaphore = Semaphore(2)
                coroutineScope {
                    val jobs = listOf(
                        async {
                            semaphore.withPermit {
                                val pts = runCatching { repository.getMonitorHistory("cpu", hours).points }.getOrDefault(emptyList())
                                _monitorState.value = _monitorState.value.copy(cpuHistory = pts)
                            }
                        },
                        async {
                            semaphore.withPermit {
                                val pts = runCatching { repository.getMonitorHistory("memory", hours).points }.getOrDefault(emptyList())
                                _monitorState.value = _monitorState.value.copy(memoryHistory = pts)
                            }
                        },
                        async {
                            semaphore.withPermit {
                                val pts = runCatching { repository.getMonitorHistory("traffic_rx", hours).points }.getOrDefault(emptyList())
                                _monitorState.value = _monitorState.value.copy(trafficRxHistory = pts)
                            }
                        },
                        async {
                            semaphore.withPermit {
                                val pts = runCatching { repository.getMonitorHistory("traffic_tx", hours).points }.getOrDefault(emptyList())
                                _monitorState.value = _monitorState.value.copy(trafficTxHistory = pts)
                            }
                        },
                        async {
                            semaphore.withPermit {
                                val pts = runCatching { repository.getMonitorHistory("signal_rsrp", hours).points }.getOrDefault(emptyList())
                                _monitorState.value = _monitorState.value.copy(signalRsrpHistory = pts)
                            }
                        },
                        async {
                            semaphore.withPermit {
                                val pts = runCatching { repository.getMonitorHistory("signal_sinr", hours).points }.getOrDefault(emptyList())
                                _monitorState.value = _monitorState.value.copy(signalSinrHistory = pts)
                            }
                        },
                        async {
                            semaphore.withPermit {
                                val pts = runCatching { repository.getMonitorHistory("battery", hours).points }.getOrDefault(emptyList())
                                _monitorState.value = _monitorState.value.copy(batteryHistory = pts)
                            }
                        },
                        async {
                            semaphore.withPermit {
                                val pts = runCatching { repository.getMonitorHistory("temperature", hours).points }.getOrDefault(emptyList())
                                _monitorState.value = _monitorState.value.copy(temperatureHistory = pts)
                            }
                        }
                    )
                    jobs.forEach { it.await() }
                }
                _monitorState.value = _monitorState.value.copy(isLoading = false)
            } catch (e: Exception) {
                _monitorState.value = _monitorState.value.copy(
                    isLoading = false, errorMessage = "加载监控数据失败: ${e.message}"
                )
            }
        }
    }

    fun loadMonitorStorage() {
        viewModelScope.launch {
            try {
                _monitorState.value = _monitorState.value.copy(
                    storageInfo = repository.getMonitorStorage()
                )
            } catch (e: Exception) {
                _monitorState.value = _monitorState.value.copy(
                    errorMessage = "加载存储统计失败: ${e.message}"
                )
            }
        }
    }

    fun cleanHistory(type: String? = null, days: Int? = null) {
        viewModelScope.launch {
            _monitorState.value = _monitorState.value.copy(cleanMessage = null)
            try {
                val result = repository.cleanHistory(type, days)
                val totalDeleted = result.deleted.values.sum()
                _monitorState.value = _monitorState.value.copy(
                    cleanMessage = "已清理 $totalDeleted 条记录"
                )
                // Reload data after cleaning
                loadMonitorHistory(_monitorState.value.selectedHours)
                loadMonitorStorage()
            } catch (e: Exception) {
                _monitorState.value = _monitorState.value.copy(
                    cleanMessage = "清理失败: ${e.message}"
                )
            }
        }
    }

    fun clearMonitorMessage() {
        _monitorState.value = _monitorState.value.copy(cleanMessage = null, errorMessage = null)
    }

    // ========== Download Manager ==========

    fun loadDownloads() {
        viewModelScope.launch {
            try {
                _downloadState.value = _downloadState.value.copy(isLoading = true)
                val prefs = com.ufi_axis.util.AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads"
                val result = withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer ${prefs.token}")
                        .build()
                    val response = client.newCall(request).execute()
                    response.body?.string() ?: "{}"
                }
                val json = JsonParser.parseString(result).asJsonObject
                val taskArray = json.getAsJsonArray("tasks") ?: com.google.gson.JsonArray()
                val tasks = taskArray.map { elem ->
                    val obj = elem.asJsonObject
                    DownloadTaskItem(
                        id = obj.get("id")?.asString ?: "",
                        url = obj.get("url")?.asString ?: "",
                        fileName = obj.get("file_name")?.asString ?: "",
                        savePath = obj.get("save_path")?.asString ?: "",
                        totalSize = obj.get("total_size")?.asLong ?: -1L,
                        downloadedBytes = obj.get("downloaded_bytes")?.asLong ?: 0L,
                        progress = obj.get("progress")?.asFloat ?: 0f,
                        speed = obj.get("speed")?.asLong ?: 0L,
                        uploadSpeed = obj.get("upload_speed")?.asLong ?: 0L,
                        status = obj.get("status")?.asString ?: "pending",
                        error = obj.get("error")?.let { if (it.isJsonNull) null else it.asString },
                        createdAt = obj.get("created_at")?.asLong ?: 0L,
                        completedAt = obj.get("completed_at")?.asLong ?: 0L,
                        engine = obj.get("engine")?.asString ?: "java",
                        protocol = obj.get("protocol")?.asString ?: "http",
                        connections = obj.get("connections")?.asInt ?: 0,
                        seeders = obj.get("seeders")?.asInt ?: 0
                    )
                }
                // Parse config
                val cfgObj = json.getAsJsonObject("config")
                val cfg = if (cfgObj != null) {
                    DownloadConfigItem(
                        maxConcurrent = cfgObj.get("max_concurrent")?.asInt ?: 3,
                        maxConnectionsPerServer = cfgObj.get("max_connections_per_server")?.asInt ?: 4,
                        globalSpeedLimit = cfgObj.get("global_speed_limit")?.asLong ?: 0L,
                        perTaskSpeedLimit = cfgObj.get("per_task_speed_limit")?.asLong ?: 0L,
                        saveDir = cfgObj.get("save_dir")?.asString ?: "/storage/emulated/0/Downloads/UFI",
                        splitCount = cfgObj.get("split_count")?.asInt ?: 4,
                        minSplitSizeMb = cfgObj.get("min_split_size_mb")?.asInt ?: 1,
                        maxOverallUploadLimit = cfgObj.get("max_overall_upload_limit")?.asLong ?: 0L,
                        fileAllocation = cfgObj.get("file_allocation")?.asString ?: "prealloc",
                        btSeedRatio = cfgObj.get("bt_seed_ratio")?.asFloat ?: 1.0f,
                        btMaxPeers = cfgObj.get("bt_max_peers")?.asInt ?: 50,
                        btEnableDht = cfgObj.get("bt_enable_dht")?.asBoolean ?: true,
                        btEnableLpd = cfgObj.get("bt_enable_lpd")?.asBoolean ?: true,
                        dhtListenPort = cfgObj.get("dht_listen_port")?.asString ?: "6881-6999",
                        btTrackerConnectTimeout = cfgObj.get("bt_tracker_connect_timeout")?.asInt ?: 60,
                        btRequestPeerSpeedLimit = cfgObj.get("bt_request_peer_speed_limit")?.asLong ?: 0L,
                        btMaxOpenFiles = cfgObj.get("bt_max_open_files")?.asInt ?: 100,
                        disableIpv6 = cfgObj.get("disable_ipv6")?.asBoolean ?: true,
                        checkCertificate = cfgObj.get("check_certificate")?.asBoolean ?: false,
                        maxTries = cfgObj.get("max_tries")?.asInt ?: 5,
                        retryWait = cfgObj.get("retry_wait")?.asInt ?: 3,
                        maxResumeTries = cfgObj.get("max_resume_tries")?.asInt ?: 0,
                        lowestSpeedLimit = cfgObj.get("lowest_speed_limit")?.asLong ?: 0L,
                        logLevel = cfgObj.get("log_level")?.asString ?: "notice",
                        btTrackerAutoUpdate = cfgObj.get("bt_tracker_auto_update")?.asBoolean ?: true,
                        btTrackerUpdateIntervalHours = cfgObj.get("bt_tracker_update_interval_hours")?.asInt ?: 24,
                        btTrackerSourceUrl = cfgObj.get("bt_tracker_source_url")?.asString ?: "https://cf.trackerslist.com/best_aria2.txt",
                        btTrackerCustomList = cfgObj.get("bt_tracker_custom_list")?.asString ?: "",
                        smartThrottle = cfgObj.get("smart_throttle")?.asBoolean ?: true,
                        throttleTempWarn = cfgObj.get("throttle_temp_warn")?.asFloat ?: 55f,
                        throttleTempCritical = cfgObj.get("throttle_temp_critical")?.asFloat ?: 70f,
                        throttleCpuWarn = cfgObj.get("throttle_cpu_warn")?.asInt ?: 60,
                        throttleCpuCritical = cfgObj.get("throttle_cpu_critical")?.asInt ?: 85,
                        throttleBatteryWarn = cfgObj.get("throttle_battery_warn")?.asInt ?: 30,
                        throttleBatteryCritical = cfgObj.get("throttle_battery_critical")?.asInt ?: 15,
                        throttleMemoryWarn = cfgObj.get("throttle_memory_warn")?.asInt ?: 75,
                        throttleMemoryCritical = cfgObj.get("throttle_memory_critical")?.asInt ?: 90,
                        onlyDownloadWhenCharging = cfgObj.get("only_download_when_charging")?.asBoolean ?: false
                    )
                } else _downloadState.value.config
                // Parse tracker metadata from root response
                val trackerCount = json.get("tracker_count")?.asInt ?: 0
                val trackerStatus = json.get("tracker_status")?.asString ?: "idle"
                val trackerLastUpdated = json.get("tracker_last_updated")?.asLong ?: 0L
                _downloadState.value = _downloadState.value.copy(
                    tasks = tasks,
                    activeCount = json.get("active")?.asInt ?: 0,
                    aria2Running = json.get("aria2_running")?.asBoolean ?: false,
                    aria2Version = json.get("aria2_version")?.let { if (it.isJsonNull) null else it.asString },
                    config = cfg,
                    isLoading = false,
                    errorMessage = null,
                    trackerCount = trackerCount,
                    trackerStatus = trackerStatus,
                    trackerLastUpdated = trackerLastUpdated,
                    trackerRefreshing = false,
                    throttleState = json.get("throttle_state")?.asString ?: "normal",
                    throttleTemp = json.get("throttle_temp")?.asFloat ?: 0f,
                    throttleCpu = json.get("throttle_cpu")?.asInt ?: 0,
                    throttleBattery = json.get("throttle_battery")?.asInt ?: -1,
                    throttleMemory = json.get("throttle_memory")?.asInt ?: 0,
                    throttleCharging = json.get("throttle_charging")?.asBoolean ?: false,
                    throttleWasStopped = json.get("throttle_was_stopped")?.asBoolean ?: false
                )
            } catch (e: Exception) {
                _downloadState.value = _downloadState.value.copy(
                    isLoading = false,
                    errorMessage = "加载失败: ${e.message}"
                )
            }
        }
    }

    fun createDownload(
        url: String,
        fileName: String? = null,
        savePath: String? = null,
        speedLimit: Long? = null,
        connections: Int? = null
    ) {
        viewModelScope.launch {
            try {
                val prefs = com.ufi_axis.util.AppPreferences(appContext)
                val apiUrl = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val json = com.google.gson.JsonObject().apply {
                        addProperty("url", url)
                        if (!fileName.isNullOrBlank()) addProperty("file_name", fileName)
                        if (!savePath.isNullOrBlank()) addProperty("save_path", savePath)
                        if (speedLimit != null && speedLimit > 0) addProperty("speed_limit", speedLimit)
                        if (connections != null && connections > 0) addProperty("connections", connections)
                    }
                    val mediaType = "application/json".toMediaTypeOrNull()!!
                    val body = json.toString().toRequestBody(mediaType)
                    val request = okhttp3.Request.Builder()
                        .url(apiUrl)
                        .post(body)
                        .addHeader("Authorization", "Bearer ${prefs.token}")
                        .build()
                    client.newCall(request).execute()
                }
                loadDownloads()
            } catch (e: Exception) {
                _downloadState.value = _downloadState.value.copy(
                    errorMessage = "创建下载失败: ${e.message}")
            }
        }
    }

    fun pauseDownload(id: String) {
        viewModelScope.launch {
            try {
                val prefs = com.ufi_axis.util.AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/$id/pause"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder()
                        .url(url).post(ByteArray(0).toRequestBody(null))
                        .addHeader("Authorization", "Bearer ${prefs.token}")
                        .build()
                    client.newCall(request).execute()
                }
                loadDownloads()
            } catch (_: Exception) {}
        }
    }

    fun resumeDownload(id: String) {
        viewModelScope.launch {
            try {
                val prefs = com.ufi_axis.util.AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/$id/resume"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder()
                        .url(url).post(ByteArray(0).toRequestBody(null))
                        .addHeader("Authorization", "Bearer ${prefs.token}")
                        .build()
                    client.newCall(request).execute()
                }
                loadDownloads()
            } catch (_: Exception) {}
        }
    }

    fun deleteDownload(id: String, deleteFile: Boolean = false) {
        viewModelScope.launch {
            try {
                val prefs = com.ufi_axis.util.AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/$id?delete_file=$deleteFile"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder()
                        .url(url).delete()
                        .addHeader("Authorization", "Bearer ${prefs.token}")
                        .build()
                    client.newCall(request).execute()
                }
                loadDownloads()
            } catch (_: Exception) {}
        }
    }

    fun clearCompletedDownloads() {
        viewModelScope.launch {
            try {
                val prefs = com.ufi_axis.util.AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/clear-completed"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder()
                        .url(url).post(ByteArray(0).toRequestBody(null))
                        .addHeader("Authorization", "Bearer ${prefs.token}")
                        .build()
                    client.newCall(request).execute()
                }
                loadDownloads()
            } catch (_: Exception) {}
        }
    }

    fun updateDownloadConfig(config: DownloadConfigItem) {
        viewModelScope.launch {
            try {
                val prefs = com.ufi_axis.util.AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/config"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val json = com.google.gson.JsonObject().apply {
                        // Basic
                        addProperty("max_concurrent", config.maxConcurrent)
                        addProperty("max_connections_per_server", config.maxConnectionsPerServer)
                        addProperty("global_speed_limit", config.globalSpeedLimit)
                        addProperty("per_task_speed_limit", config.perTaskSpeedLimit)
                        addProperty("save_dir", config.saveDir)
                        addProperty("split_count", config.splitCount)
                        addProperty("min_split_size_mb", config.minSplitSizeMb)
                        addProperty("max_overall_upload_limit", config.maxOverallUploadLimit)
                        addProperty("file_allocation", config.fileAllocation)
                        // Advanced BT
                        addProperty("bt_seed_ratio", config.btSeedRatio)
                        addProperty("bt_max_peers", config.btMaxPeers)
                        addProperty("bt_enable_dht", config.btEnableDht)
                        addProperty("bt_enable_lpd", config.btEnableLpd)
                        addProperty("dht_listen_port", config.dhtListenPort)
                        addProperty("bt_tracker_connect_timeout", config.btTrackerConnectTimeout)
                        addProperty("bt_request_peer_speed_limit", config.btRequestPeerSpeedLimit)
                        addProperty("bt_max_open_files", config.btMaxOpenFiles)
                        // Advanced Network
                        addProperty("disable_ipv6", config.disableIpv6)
                        addProperty("check_certificate", config.checkCertificate)
                        addProperty("max_tries", config.maxTries)
                        addProperty("retry_wait", config.retryWait)
                        addProperty("max_resume_tries", config.maxResumeTries)
                        addProperty("lowest_speed_limit", config.lowestSpeedLimit)
                        // Log
                        addProperty("log_level", config.logLevel)
                        // BT Tracker Management
                        addProperty("bt_tracker_auto_update", config.btTrackerAutoUpdate)
                        addProperty("bt_tracker_update_interval_hours", config.btTrackerUpdateIntervalHours)
                        addProperty("bt_tracker_source_url", config.btTrackerSourceUrl)
                        addProperty("bt_tracker_custom_list", config.btTrackerCustomList)
                        // Smart Throttle
                        addProperty("smart_throttle", config.smartThrottle)
                        addProperty("throttle_temp_warn", config.throttleTempWarn)
                        addProperty("throttle_temp_critical", config.throttleTempCritical)
                        addProperty("throttle_cpu_warn", config.throttleCpuWarn)
                        addProperty("throttle_cpu_critical", config.throttleCpuCritical)
                        addProperty("throttle_battery_warn", config.throttleBatteryWarn)
                        addProperty("throttle_battery_critical", config.throttleBatteryCritical)
                        addProperty("throttle_memory_warn", config.throttleMemoryWarn)
                        addProperty("throttle_memory_critical", config.throttleMemoryCritical)
                        addProperty("only_download_when_charging", config.onlyDownloadWhenCharging)
                    }
                    val mediaType = "application/json".toMediaTypeOrNull()!!
                    val body = json.toString().toRequestBody(mediaType)
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .put(body)
                        .addHeader("Authorization", "Bearer ${prefs.token}")
                        .build()
                    client.newCall(request).execute()
                }
                loadDownloads()
            } catch (e: Exception) {
                _downloadState.value = _downloadState.value.copy(
                    errorMessage = "更新配置失败: ${e.message}")
            }
        }
    }

    fun validatePath(path: String, onResult: (Map<String, Any?>) -> Unit) {
        viewModelScope.launch {
            try {
                val prefs = com.ufi_axis.util.AppPreferences(appContext)
                val encoded = java.net.URLEncoder.encode(path, "UTF-8")
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/validate-path?path=$encoded"
                val result = withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer ${prefs.token}")
                        .build()
                    val response = client.newCall(request).execute()
                    response.body?.string() ?: "{}"
                }
                val json = JsonParser.parseString(result).asJsonObject
                onResult(json.entrySet().associate { entry ->
                    entry.key to when {
                        entry.value.isJsonNull -> null
                        entry.value.isJsonPrimitive -> {
                            val p = entry.value.asJsonPrimitive
                            when {
                                p.isBoolean -> p.asBoolean
                                p.isNumber -> p.asLong
                                else -> p.asString
                            }
                        }
                        else -> entry.value.toString()
                    }
                })
            } catch (e: Exception) {
                onResult(mapOf("valid" to false, "error" to e.message))
            }
        }
    }

    fun refreshTrackers() {
        viewModelScope.launch {
            try {
                _downloadState.value = _downloadState.value.copy(trackerRefreshing = true)
                val prefs = com.ufi_axis.util.AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/trackers/refresh"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .post(ByteArray(0).toRequestBody(null))
                        .addHeader("Authorization", "Bearer ${prefs.token}")
                        .build()
                    client.newCall(request).execute().close()
                }
                loadDownloads()
            } catch (e: Exception) {
                _downloadState.value = _downloadState.value.copy(
                    trackerRefreshing = false,
                    errorMessage = "Tracker 刷新失败: ${e.message}"
                )
            }
        }
    }

    fun loadTrackers() {
        viewModelScope.launch {
            try {
                _downloadState.value = _downloadState.value.copy(trackerListLoading = true)
                val prefs = com.ufi_axis.util.AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/trackers"
                val result = withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer ${prefs.token}")
                        .build()
                    val response = client.newCall(request).execute()
                    response.body?.string() ?: "{}"
                }
                val json = JsonParser.parseString(result).asJsonObject
                val trackers = json.get("trackers")?.let {
                    if (it.isJsonNull) "" else it.asString
                } ?: ""
                _downloadState.value = _downloadState.value.copy(
                    cachedTrackerList = trackers,
                    trackerListLoading = false
                )
            } catch (e: Exception) {
                _downloadState.value = _downloadState.value.copy(
                    trackerListLoading = false,
                    errorMessage = "加载 Tracker 列表失败: ${e.message}"
                )
            }
        }
    }

    fun saveTrackerList(trackers: String) {
        viewModelScope.launch {
            try {
                val prefs = com.ufi_axis.util.AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/trackers/save"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val json = com.google.gson.JsonObject().apply {
                        addProperty("trackers", trackers)
                    }
                    val mediaType = "application/json".toMediaTypeOrNull()!!
                    val body = json.toString().toRequestBody(mediaType)
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .post(body)
                        .addHeader("Authorization", "Bearer ${prefs.token}")
                        .build()
                    client.newCall(request).execute().close()
                }
                loadDownloads()
            } catch (e: Exception) {
                _downloadState.value = _downloadState.value.copy(
                    errorMessage = "保存 Tracker 列表失败: ${e.message}"
                )
            }
        }
    }

    // ========== Factory ==========

    companion object {
        fun provideFactory(
            repository: UfiAxisRepository,
            webSocketRepository: WebSocketRepository,
            networkMonitor: NetworkMonitor,
            appContext: android.content.Context
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(repository, webSocketRepository, networkMonitor, appContext) as T
                }
            }
        }
    }
}