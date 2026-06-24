package com.ufi_axis.viewmodel.module

import android.content.Context
import com.google.gson.Gson
import com.ufi_axis.data.model.*
import com.ufi_axis.data.repository.DeviceRepository
import com.ufi_axis.data.repository.FileAppRepository
import com.ufi_axis.data.repository.NetworkRepository
import com.ufi_axis.data.repository.WebSocketRepository
import com.ufi_axis.util.*
import com.ufi_axis.viewmodel.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class DashboardModule(
    private val deviceRepo: DeviceRepository,
    private val networkRepo: NetworkRepository,
    private val fileAppRepo: FileAppRepository,
    private val webSocketRepository: WebSocketRepository,
    private val networkMonitor: NetworkMonitor,
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    // ── State ──
    private val _dashboardState = MutableStateFlow(DashboardState())
    val dashboardState: StateFlow<DashboardState> = _dashboardState.asStateFlow()

    private val _monitorState = MutableStateFlow(MonitorState())
    val monitorState: StateFlow<MonitorState> = _monitorState.asStateFlow()

    private val _updateState = MutableStateFlow(UpdateState())
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    // ── Internal ──
    private val gson = Gson()
    private val cacheManager = CacheManager(appContext.applicationContext)
    private var autoRefreshJob: Job? = null
    private var saveDebounceJob: Job? = null
    private var lastSavedTime = 0L
    @Volatile private var lastWsUpdate = 0L
    private var wasOffline = false

    /** Callback: persist initial + refreshed data for other modules (e.g. device info) */
    var onDataLoaded: ((DashboardState) -> Unit)? = null

    // ── Init ──
    fun init() {
        scope.launch { loadCachedData() }
        try { webSocketRepository.connect(scope) } catch (e: Exception) {
            DebugLog.w("Dashboard", "WebSocket connect failed: ${e.message}")
        }
        collectWebSocketMessages()
        observeNetworkState()
        scope.launch { delay(500); refreshDashboard() }
        checkForUpdate()
    }

    fun onCleared() {
        stopAutoRefresh()
        webSocketRepository.disconnect()
        cacheManager.shutdown()
    }

    // ── Cache ──
    private suspend fun loadCachedData() {
        val cached = cacheManager.getLatestData() ?: return
        _dashboardState.update { state ->
            state.copy(
                cpuInfo = cached.cpuJson?.let { gson.fromJson(it, CpuInfo::class.java) } ?: state.cpuInfo,
                memoryInfo = cached.memoryJson?.let { gson.fromJson(it, MemoryInfo::class.java) } ?: state.memoryInfo,
                trafficRealtime = cached.trafficJson?.let { gson.fromJson(it, TrafficRealtime::class.java) } ?: state.trafficRealtime,
                signalInfo = cached.signalJson?.let { gson.fromJson(it, SignalInfo::class.java) } ?: state.signalInfo,
                lastUpdated = cached.timestamp
            )
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

    private fun debounceSaveToCache() {
        val now = System.currentTimeMillis()
        saveDebounceJob?.cancel()
        if (now - lastSavedTime > 10_000) {
            saveToCache()
            lastSavedTime = now
            return
        }
        saveDebounceJob = scope.launch {
            delay(3_000)
            saveToCache()
            lastSavedTime = System.currentTimeMillis()
        }
    }

    // ── WebSocket ──
    private fun collectWebSocketMessages() {
        scope.launch {
            webSocketRepository.messages.collect { message ->
                try {
                    var changed = false
                    when (message.type) {
                        "cpu" -> try {
                            _dashboardState.update { it.copy(cpuInfo = gson.fromJson(message.data, CpuInfo::class.java)) }
                            changed = true
                        } catch (e: Exception) { DebugLog.parseError("WS", "cpu", message.data.toString(), e) }
                        "memory" -> try {
                            _dashboardState.update { it.copy(memoryInfo = gson.fromJson(message.data, MemoryInfo::class.java)) }
                            changed = true
                        } catch (e: Exception) { DebugLog.parseError("WS", "memory", message.data.toString(), e) }
                        "traffic" -> try {
                            _dashboardState.update { it.copy(trafficRealtime = gson.fromJson(message.data, TrafficRealtime::class.java)) }
                            changed = true
                        } catch (e: Exception) { DebugLog.parseError("WS", "traffic", message.data.toString(), e) }
                        "signal" -> try {
                            _dashboardState.update { it.copy(signalInfo = gson.fromJson(message.data, SignalInfo::class.java)) }
                            changed = true
                        } catch (e: Exception) { DebugLog.parseError("WS", "signal", message.data.toString(), e) }
                    }
                    if (changed) {
                        lastWsUpdate = System.currentTimeMillis()
                        debounceSaveToCache()
                    }
                } catch (e: Exception) { DebugLog.parseError("WS", "unknown", message.data?.toString() ?: "", e) }
            }
        }
    }

    // ── Network Observation ──
    private fun observeNetworkState() {
        scope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                if (wasOffline && isOnline) { webSocketRepository.reconnect(); refreshDashboard() }
                wasOffline = !isOnline
                _dashboardState.update { it.copy(isOffline = !isOnline) }
                if (!isOnline) {
                    val cached = webSocketRepository.cachedData.value
                    _dashboardState.update { s ->
                        var next = s
                        cached["cpu"]?.let { next = next.copy(cpuInfo = gson.fromJson(it.data, CpuInfo::class.java)) }
                        cached["memory"]?.let { next = next.copy(memoryInfo = gson.fromJson(it.data, MemoryInfo::class.java)) }
                        cached["traffic"]?.let { next = next.copy(trafficRealtime = gson.fromJson(it.data, TrafficRealtime::class.java)) }
                        cached["signal"]?.let { next = next.copy(signalInfo = gson.fromJson(it.data, SignalInfo::class.java)) }
                        next
                    }
                }
            }
        }
    }

    // ── Dashboard Refresh ──
    // CPU/内存/流量/信号 已由 WebSocket 实时推送，不再发送 REST 请求。
    // 减少 11→7 个并发请求，后端不再因 shell 竞争返回 429。
    fun refreshDashboard() {
        scope.launch {
            _dashboardState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                // 仅查询 WebSocket 未覆盖的一次性数据（设备信息/电池/存储/运行时间/流量统计/限额/网络状态）
                val bat = async { runCatching { deviceRepo.getBatteryInfo() } }
                val stor = async { runCatching { deviceRepo.getStorageInfo() } }
                val up = async { runCatching { deviceRepo.getUptime() } }
                val summary = async { runCatching { networkRepo.getTrafficSummary() } }
                val limit = async { runCatching { networkRepo.getTrafficLimit() } }
                val net = async { runCatching { networkRepo.getNetworkStatus() } }

                val rInfo = runCatching { deviceRepo.getDeviceInfo() }

                val rBat = bat.await(); val rStor = stor.await()
                val rUp = up.await(); val rSummary = summary.await()
                val rLimit = limit.await(); val rNet = net.await()

                val results = listOf(
                    "设备信息" to rInfo, "电池" to rBat, "存储" to rStor,
                    "运行时间" to rUp, "流量统计" to rSummary,
                    "流量限额" to rLimit, "网络状态" to rNet
                )
                val failures = results.filter { it.second.isFailure }
                val errorMsg = if (failures.isNotEmpty()) {
                    failures.joinToString("; ") { (name, result) ->
                        "$name: ${result.exceptionOrNull()?.message ?: "未知错误"}"
                    }
                } else null

                _dashboardState.update { current ->
                    current.copy(
                        deviceInfo = rInfo.getOrNull(), batteryInfo = rBat.getOrNull(),
                        storageInfo = rStor.getOrNull(), uptimeInfo = rUp.getOrNull(),
                        trafficSummary = rSummary.getOrNull(), networkStatus = rNet.getOrNull(),
                        trafficLimitConfig = rLimit.getOrNull()?.let {
                            com.google.gson.Gson().fromJson(it.toString(), com.ufi_axis.data.model.TrafficLimitConfig::class.java)
                        },
                        // CPU/内存/流量/信号 完全依赖 WebSocket 推送，不清空已有值
                        isLoading = false, errorMessage = errorMsg,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                saveToCache()
                onDataLoaded?.invoke(_dashboardState.value)
            } catch (e: Exception) {
                DebugLog.e("Dashboard", "refreshDashboard failed", e)
                _dashboardState.update { it.copy(isLoading = false, errorMessage = "连接失败: ${e.message}") }
            }
        }
    }

    // ── Auto Refresh ──
    fun startAutoRefresh(intervalMs: Long = 10_000L) {
        stopAutoRefresh()
        autoRefreshJob = scope.launch { while (isActive) { refreshDashboard(); delay(intervalMs) } }
    }

    fun stopAutoRefresh() { autoRefreshJob?.cancel(); autoRefreshJob = null }

    // ── History ──
    fun loadCpuHistory(hours: Int = 24) {
        scope.launch {
            try { _dashboardState.update { it.copy(cpuHistory = deviceRepo.getCpuHistory(hours).records) } }
            catch (e: Exception) { _dashboardState.update { it.copy(errorMessage = "CPU历史加载失败: ${e.message}") } }
        }
    }

    fun loadSignalHistory(hours: Int = 24) {
        scope.launch {
            try { _dashboardState.update { it.copy(signalHistory = networkRepo.getSignalHistory(hours).records) } }
            catch (e: Exception) { _dashboardState.update { it.copy(errorMessage = "信号历史加载失败: ${e.message}") } }
        }
    }

    // ── Update Check ──
    fun checkForUpdate() {
        scope.launch {
            _updateState.value = _updateState.value.copy(checking = true)
            try {
                val versionInfo = deviceRepo.getServerVersion()
                _updateState.value = UpdateState(
                    hasUpdate = compareVersions(versionInfo.version, "1.0") > 0,
                    serverVersion = versionInfo.version, updateUrl = versionInfo.update_url, checking = false
                )
            } catch (e: Exception) {
                _updateState.value = UpdateState(checking = false, errorMessage = "检查更新失败: ${e.message}")
            }
        }
    }

    // ── Device Version (from ZTE goform) ──
    fun loadDeviceVersion() {
        scope.launch {
            try {
                val ver = deviceRepo.getDeviceVersion()
                _dashboardState.update { it.copy(deviceVersion = ver) }
            } catch (e: Exception) {
                _dashboardState.update { it.copy(errorMessage = "设备版本加载失败: ${e.message}") }
            }
        }
    }

    // ── Monitor ──
    private var lastMonitorRefreshMs: Long = 0L
    private val MIN_REFRESH_INTERVAL_MS = 8_000L

    fun loadMonitorHistory(hours: Int = 24, force: Boolean = false) {
        val now = System.currentTimeMillis()
        // force=true 跳过限频（用户主动切换时间范围时传入）
        if (!force && now - lastMonitorRefreshMs < MIN_REFRESH_INTERVAL_MS) return
        lastMonitorRefreshMs = now

        scope.launch {
            val current = _monitorState.value
            _monitorState.update { it.copy(selectedHours = hours, isLoading = true, errorMessage = null) }
            try {
                // 无 Semaphore 限制：HTTP 请求是 I/O 密集型，8 并发不会压垮设备
                coroutineScope {
                    val types = listOf(
                        "cpu" to current.cpuHistory,
                        "memory" to current.memoryHistory,
                        "traffic_rx" to current.trafficRxHistory,
                        "traffic_tx" to current.trafficTxHistory,
                        "signal_rsrp" to current.signalRsrpHistory,
                        "signal_sinr" to current.signalSinrHistory,
                        "battery" to current.batteryHistory,
                        "temperature" to current.temperatureHistory
                    )
                    val results = types.map { (type, oldList) ->
                        async {
                            val resp = runCatching { fileAppRepo.getMonitorHistory(type, hours) }
                            val points = resp.getOrNull()?.points ?: oldList
                            type to points
                        }
                    }.awaitAll().toMap()

                    _monitorState.update {
                        it.copy(
                            cpuHistory = results["cpu"] as? List<DownsampledPoint> ?: it.cpuHistory,
                            memoryHistory = results["memory"] as? List<DownsampledPoint> ?: it.memoryHistory,
                            trafficRxHistory = results["traffic_rx"] as? List<DownsampledPoint> ?: it.trafficRxHistory,
                            trafficTxHistory = results["traffic_tx"] as? List<DownsampledPoint> ?: it.trafficTxHistory,
                            signalRsrpHistory = results["signal_rsrp"] as? List<DownsampledPoint> ?: it.signalRsrpHistory,
                            signalSinrHistory = results["signal_sinr"] as? List<DownsampledPoint> ?: it.signalSinrHistory,
                            batteryHistory = results["battery"] as? List<DownsampledPoint> ?: it.batteryHistory,
                            temperatureHistory = results["temperature"] as? List<DownsampledPoint> ?: it.temperatureHistory,
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                _monitorState.update { it.copy(isLoading = false, errorMessage = "加载监控数据失败: ${e.message}") }
            }
        }
    }

    fun loadMonitorStorage() {
        scope.launch {
            try { _monitorState.update { it.copy(storageInfo = fileAppRepo.getMonitorStorage()) } }
            catch (e: Exception) { _monitorState.update { it.copy(errorMessage = "加载存储统计失败: ${e.message}") } }
        }
    }

    fun cleanHistory(type: String? = null, days: Int? = null) {
        scope.launch {
            _monitorState.update { it.copy(cleanMessage = null) }
            try {
                val result = fileAppRepo.cleanHistory(type, days)
                val totalDeleted = result.deleted.values.sum()
                _monitorState.update { it.copy(cleanMessage = "已清理 $totalDeleted 条记录") }
                loadMonitorHistory(_monitorState.value.selectedHours)
                loadMonitorStorage()
            } catch (e: Exception) {
                _monitorState.update { it.copy(cleanMessage = "清理失败: ${e.message}") }
            }
        }
    }

    fun clearMonitorMessage() {
        _monitorState.update { it.copy(cleanMessage = null, errorMessage = null) }
    }

    // ── Error Clearing ──
    // ── Smart Refresh (data_changed 精准增量刷新) ──
    fun smartRefresh(changedType: String) {
        when {
            changedType == "device:*" -> refreshDashboard()     // 设备重启/恢复出厂 → 全量刷新
            changedType == "device:info" -> refreshDeviceInfo()  // 主机名变更 → 仅刷新设备信息
        }
    }

    /** 精准刷新设备信息（CPU/内存/流量/信号由 WebSocket 推送，无需 HTTP 请求） */
    private fun refreshDeviceInfo() {
        scope.launch {
            try {
                val info = async { runCatching { deviceRepo.getDeviceInfo() } }
                val bat = async { runCatching { deviceRepo.getBatteryInfo() } }
                val stor = async { runCatching { deviceRepo.getStorageInfo() } }
                val up = async { runCatching { deviceRepo.getUptime() } }
                val summary = async { runCatching { networkRepo.getTrafficSummary() } }
                val net = async { runCatching { networkRepo.getNetworkStatus() } }

                val rInfo = info.await(); val rBat = bat.await(); val rStor = stor.await()
                val rUp = up.await(); val rSummary = summary.await(); val rNet = net.await()

                val failures = listOfNotNull(
                    "设备信息" to rInfo, "电池" to rBat, "存储" to rStor,
                    "运行时间" to rUp, "流量统计" to rSummary, "网络状态" to rNet
                ).filter { it.second.isFailure }.joinToString("; ") { (n, r) ->
                    "$n: ${r.exceptionOrNull()?.message ?: "未知"}"
                }.ifEmpty { null }

                _dashboardState.update { current ->
                    current.copy(
                        deviceInfo = rInfo.getOrNull() ?: current.deviceInfo,
                        batteryInfo = rBat.getOrNull() ?: current.batteryInfo,
                        storageInfo = rStor.getOrNull() ?: current.storageInfo,
                        uptimeInfo = rUp.getOrNull() ?: current.uptimeInfo,
                        trafficSummary = rSummary.getOrNull() ?: current.trafficSummary,
                        networkStatus = rNet.getOrNull() ?: current.networkStatus,
                        errorMessage = failures ?: current.errorMessage,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                onDataLoaded?.invoke(_dashboardState.value)
            } catch (e: Exception) {
                _dashboardState.update { it.copy(errorMessage = "设备信息刷新失败: ${e.message}") }
            }
        }
    }

    fun clearError() {
        _dashboardState.update { it.copy(errorMessage = null) }
    }

    fun setDashboardError(msg: String?) {
        _dashboardState.update { it.copy(errorMessage = msg) }
    }

    // ── Utility ──
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
}
