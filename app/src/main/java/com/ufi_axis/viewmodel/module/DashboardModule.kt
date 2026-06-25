package com.ufi_axis.viewmodel.module

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ufi_axis.data.model.*
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.CleanHistoryRequest
import com.ufi_axis.data.repository.WebSocketRepository
import com.ufi_axis.util.*
import com.ufi_axis.viewmodel.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class DashboardModule(
    private val api: UfiAxisApi,
    private val webSocketRepository: WebSocketRepository,
    private val networkMonitor: NetworkMonitor,
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    // ── State ──
    private val _dashboardState = MutableStateFlow(DashboardState())
    val dashboardState: StateFlow<DashboardState> = _dashboardState.asStateFlow()

    // ── 按字段派生的 StateFlow（各 screen 按需订阅，避免全量重组） ──
    val cpuInfoState: StateFlow<CpuInfo?> = _dashboardState.map { it.cpuInfo }.stateIn(scope, SharingStarted.Eagerly, null)
    val memoryInfoState: StateFlow<MemoryInfo?> = _dashboardState.map { it.memoryInfo }.stateIn(scope, SharingStarted.Eagerly, null)
    val trafficRealtimeState: StateFlow<TrafficRealtime?> = _dashboardState.map { it.trafficRealtime }.stateIn(scope, SharingStarted.Eagerly, null)
    val signalInfoState: StateFlow<SignalInfo?> = _dashboardState.map { it.signalInfo }.stateIn(scope, SharingStarted.Eagerly, null)
    val batteryInfoState: StateFlow<BatteryInfo?> = _dashboardState.map { it.batteryInfo }.stateIn(scope, SharingStarted.Eagerly, null)
    val deviceVersionState: StateFlow<DeviceVersionResponse?> = _dashboardState.map { it.deviceVersion }.stateIn(scope, SharingStarted.Eagerly, null)
    val isLoadingState: StateFlow<Boolean> = _dashboardState.map { it.isLoading }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _monitorState = MutableStateFlow(MonitorState())
    val monitorState: StateFlow<MonitorState> = _monitorState.asStateFlow()

    private val _updateState = MutableStateFlow(UpdateState())
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    // ── Internal ──
    private val gson = AppGson.instance
    private val cacheManager = CacheManager(appContext.applicationContext)
    private var autoRefreshJob: Job? = null
    private var saveDebounceJob: Job? = null
    @Volatile private var lastWsUpdate = 0L
    @Volatile private var dashboardSummaryCache: JsonElement? = null
    @Volatile private var dashboardSummaryCacheTime: Long = 0L
    private val DASHBOARD_CACHE_TTL_MS = 5000L
    private var wasOffline = false

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
        saveDebounceJob?.cancel()
        saveDebounceJob = scope.launch {
            delay(5_000)
            saveToCache()
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
                    val cached = webSocketRepository.cachedData
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
    // 后端聚合端点 /api/dashboard/summary — 一次性返回全部 Dashboard 数据。
    // 减少 7 个并发请求 → 1 个聚合请求 + 前端 5 秒缓存。
    fun refreshDashboard(forceRefresh: Boolean = false) {
        scope.launch {
            _dashboardState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val cache = dashboardSummaryCache
                val json: JsonElement
                val now = System.currentTimeMillis()
                if (!forceRefresh && cache != null && now - dashboardSummaryCacheTime < DASHBOARD_CACHE_TTL_MS) {
                    json = cache
                } else {
                    json = api.getDashboardSummary().also {
                        dashboardSummaryCache = it
                        dashboardSummaryCacheTime = now
                    }
                }

                val obj = json.asJsonObject
                // 每个字段独立解析，一个失败不影响其他
                val rInfo = runCatching { obj.get("device_info")?.asJsonObject?.let { gson.fromJson(it, DeviceInfoResponse::class.java) } }
                val rBat = runCatching { obj.get("battery")?.asJsonObject?.let { gson.fromJson(it, BatteryInfo::class.java) } }
                val rStor = runCatching { obj.get("storage")?.asJsonObject?.let { gson.fromJson(it, StorageInfo::class.java) } }
                val rUp = runCatching { obj.get("uptime")?.asJsonObject?.let { gson.fromJson(it, UptimeInfo::class.java) } }
                val rSummary = runCatching { obj.get("traffic_summary")?.asJsonObject?.let { gson.fromJson(it, TrafficSummary::class.java) } }
                val rLimit = runCatching { obj.get("traffic_limit")?.asJsonObject?.let { gson.fromJson(it, TrafficLimitConfig::class.java) } }
                val rNet = runCatching { obj.get("network_status")?.asJsonObject?.let { gson.fromJson(it, NetworkStatusResponse::class.java) } }

                val failures = listOfNotNull(
                    "设备信息" to rInfo, "电池" to rBat, "存储" to rStor,
                    "运行时间" to rUp, "流量统计" to rSummary,
                    "流量限额" to rLimit, "网络状态" to rNet
                ).filter { it.second.isFailure }.joinToString("; ") { (name, result) ->
                    "$name: ${result.exceptionOrNull()?.message ?: "未知错误"}"
                }.ifEmpty { null }

                _dashboardState.update { current ->
                    current.copy(
                        deviceInfo = rInfo.getOrNull(), batteryInfo = rBat.getOrNull(),
                        storageInfo = rStor.getOrNull(), uptimeInfo = rUp.getOrNull(),
                        trafficSummary = rSummary.getOrNull(),
                        trafficLimitConfig = rLimit.getOrNull(),
                        networkStatus = rNet.getOrNull(),
                        isLoading = false, errorMessage = failures,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                saveToCache()
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
    companion object {
        /** 历史记录最大条数：360 ≈ 10秒间隔 × 1小时，UI 图表不需要完整24小时数据 */
        private const val MAX_HISTORY_RECORDS = 360
    }

    fun loadCpuHistory(hours: Int = 24) {
        scope.launch {
            try {
                val records = api.getCpuHistory(hours).records
                _dashboardState.update { it.copy(cpuHistory = if (records.size > MAX_HISTORY_RECORDS) records.takeLast(MAX_HISTORY_RECORDS) else records) }
            } catch (e: Exception) { _dashboardState.update { it.copy(errorMessage = "CPU历史加载失败: ${e.message}") } }
        }
    }

    fun loadSignalHistory(hours: Int = 24) {
        scope.launch {
            try {
                val records = api.getSignalHistory(hours).records
                _dashboardState.update { it.copy(signalHistory = if (records.size > MAX_HISTORY_RECORDS) records.takeLast(MAX_HISTORY_RECORDS) else records) }
            } catch (e: Exception) { _dashboardState.update { it.copy(errorMessage = "信号历史加载失败: ${e.message}") } }
        }
    }

    // ── Update Check ──
    fun checkForUpdate() {
        scope.launch {
            _updateState.value = _updateState.value.copy(checking = true)
            try {
                val versionInfo = api.getServerVersion()
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
                val ver = api.getDeviceVersion()
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
        if (!force && now - lastMonitorRefreshMs < MIN_REFRESH_INTERVAL_MS) return
        lastMonitorRefreshMs = now

        _monitorState.update { it.copy(selectedHours = hours, isLoading = true, errorMessage = null) }
        // 串行加载 8 个类型，避免 8 并发 HTTP 请求
        scope.launch {
            try {
                val types = listOf("cpu", "memory", "traffic_rx", "traffic_tx",
                    "signal_rsrp", "signal_sinr", "battery", "temperature")
                types.forEach { type -> loadMonitorHistory(type, hours) }
                _monitorState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _monitorState.update { it.copy(isLoading = false, errorMessage = "加载监控数据失败: ${e.message}") }
            }
        }
    }

    fun loadMonitorHistory(type: String, hours: Int) {
        scope.launch {
            runCatching { api.getMonitorHistory(type, hours) }.onSuccess { resp ->
                val points = resp.points
                _monitorState.update { state ->
                    when (type) {
                        "cpu" -> state.copy(cpuHistory = points)
                        "memory" -> state.copy(memoryHistory = points)
                        "traffic_rx" -> state.copy(trafficRxHistory = points)
                        "traffic_tx" -> state.copy(trafficTxHistory = points)
                        "signal_rsrp" -> state.copy(signalRsrpHistory = points)
                        "signal_sinr" -> state.copy(signalSinrHistory = points)
                        "battery" -> state.copy(batteryHistory = points)
                        "temperature" -> state.copy(temperatureHistory = points)
                        else -> state
                    }
                }
            }
        }
    }

    fun loadMonitorStorage() {
        scope.launch {
            try { _monitorState.update { it.copy(storageInfo = api.getMonitorStorage()) } }
            catch (e: Exception) { _monitorState.update { it.copy(errorMessage = "加载存储统计失败: ${e.message}") } }
        }
    }

    fun cleanHistory(type: String? = null, days: Int? = null) {
        scope.launch {
            _monitorState.update { it.copy(cleanMessage = null) }
            try {
                val result = api.cleanHistory(CleanHistoryRequest(type, days))
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

    fun smartRefresh(changedType: String) {
        when {
            changedType.startsWith("device:") -> refreshDashboard(forceRefresh = true)
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
