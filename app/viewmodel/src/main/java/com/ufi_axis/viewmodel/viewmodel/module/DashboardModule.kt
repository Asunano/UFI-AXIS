package com.ufi_axis.viewmodel.module

import android.content.Context
import android.os.SystemClock
import com.ufi_axis.data.model.*
import com.ufi_axis.data.monitor.*
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.CleanHistoryRequest
import com.ufi_axis.util.AppJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import com.ufi_axis.data.repository.WebSocketRepository
import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.util.*
import com.ufi_axis_core.util.UiFrameGate
import com.ufi_axis.viewmodel.repository.AlertPrefsRepository
import com.ufi_axis.viewmodel.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver

class DashboardModule(
    private val api: UfiAxisApi,
    private val webSocketRepository: WebSocketRepository,
    private val networkMonitor: NetworkMonitor,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val alertPrefs: AlertPrefsRepository
) {
    // ── State ──
    private val _dashboardState = MutableStateFlow(DashboardState())
    val dashboardState: StateFlow<DashboardState> = _dashboardState.asStateFlow()

    // ── 按字段派生的 StateFlow（各 screen 按需订阅，避免全量重组） ──
    // WhileSubscribed: 无订阅者 5s 后停止收集，新订阅者立即获取当前值
    val cpuInfoState: StateFlow<CpuInfo?> = _dashboardState.map { it.cpuInfo }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), null)
    val memoryInfoState: StateFlow<MemoryInfo?> = _dashboardState.map { it.memoryInfo }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), null)
    val trafficRealtimeState: StateFlow<TrafficRealtime?> = _dashboardState.map { it.trafficRealtime }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), null)
    val signalInfoState: StateFlow<SignalInfo?> = _dashboardState.map { it.signalInfo }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), null)
    val batteryInfoState: StateFlow<BatteryInfo?> = _dashboardState.map { it.batteryInfo }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), null)
    val isLoadingState: StateFlow<Boolean> = _dashboardState.map { it.isLoading }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), false)

    private val _monitorState = MutableStateFlow(MonitorState())
    val monitorState: StateFlow<MonitorState> = _monitorState.asStateFlow()

    // ── UI 事件出口（ViewModel → Compose；如删除成功 Toast 信号）──
    // 同构于 ToolsModule/NetworkModule 的 _events，但仅面向 UI（不转发跨模块 sink）。
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val _updateState = MutableStateFlow(UpdateState())
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    // ── Internal ──
    private val cacheManager = CacheManager(appContext.applicationContext)
    private val monitorPreferences = MonitorPreferences(appContext.applicationContext)
    private var autoRefreshJob: Job? = null
    private var refreshJob: Job? = null
    private var saveDebounceJob: Job? = null
    @Volatile private var lastWsUpdate = 0L
    @Volatile private var dashboardSummaryCache: DashboardSummaryResponse? = null
    @Volatile private var dashboardSummaryCacheTime: Long = 0L
    /**
     * 最近一次 dashboard/summary **成功落地**的时刻（单调时钟，`0` = 本进程内从未成功）。
     *
     * 只服务于「页面前台化时该不该立刻重拉」这一个判断，见 [foregroundRefreshDelayMs]。
     * 与 [dashboardSummaryCacheTime] 的分工：后者是**响应体缓存**的 TTL（3s，可以让一次
     * 重拉直接吃缓存，但仍然会走一遍状态写 + 重组）；本字段管的是**连请求都不要发**。
     *
     * 刻意用 `SystemClock.elapsedRealtime()` 而不是 `System.currentTimeMillis()`：
     * 后者可被用户改，改一下就会把陈旧数据判成新鲜（或反之）。理由详见 `DataFreshness.kt`。
     */
    @Volatile private var dashboardSuccessElapsed: Long = 0L
    private val DASHBOARD_CACHE_TTL_MS = 3000L
    private val DASHBOARD_REFRESH_TIMEOUT = 15_000L
    private val refreshSemaphore = Semaphore(1)
    private var wasOffline = false
    @Volatile private var monitorSettingsLoaded = false

    private var alertPollingJob: Job? = null

    /** 跨进程告警同步广播接收器：接收独立进程的信号，立即触发主进程刷新 */
    private val alertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NotificationCenter.ACTION_NEW_ALERTS) {
                DebugLog.d("Dashboard", "Received ACTION_NEW_ALERTS broadcast from background process")
                loadAlerts(_monitorState.value.alertRange)
            }
        }
    }

    // 通知唯一出口（data 层，懒创建；仅 loadAlerts / WS alert / 状态机路径使用）
    private val notificationCenter by lazy { NotificationCenter(appContext) }

    /**
     * 解析 WS 推送的告警 payload。
     *
     * core 的 `WebSocketPushService` 把 `id`/`value`/`threshold` 放在 `data.extra` 里，
     * 顶层只有 `type`/`level`/`title`/`message`/`timestamp`（ms）。
     * 此前从顶层取这三个字段 → 恒为 id=0、value=""、threshold=""，
     * 导致告警详情无值、且 NotificationCenter 的 (timestamp, id) 复合游标退化。
     */
    private fun parsePushedAlert(dataObj: JsonObject): AlertRecord {
        val extra = dataObj["extra"] as? JsonObject
        return AlertRecord(
            id = extra?.get("id")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            type = dataObj["type"]?.jsonPrimitive?.content ?: "unknown",
            level = dataObj["level"]?.jsonPrimitive?.content ?: "info",
            message = dataObj["message"]?.jsonPrimitive?.content ?: "",
            value = extra?.get("value")?.jsonPrimitive?.content ?: "",
            threshold = extra?.get("threshold")?.jsonPrimitive?.content ?: "",
            acknowledged = false,
            timestamp = dataObj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis()
        )
    }

    /**
     * 这条推送是不是「看着像告警但不是告警记录」的类型（`download` / `tunnel`）。
     *
     * 2026-09-07：这两种推送 core **不写 alert_records** —— 包进 [parsePushedAlert] 塞进
     * `MonitorState.alerts` 只会让事件中心多出两条列表里查不到的"告警"，还会推进告警游标。
     * 而它们的系统通知已经由 `:ufi_notify` 的 `NotifyService` 渲染
     * （`notifyDownloadResult` / `notifyTunnelFailure`），再走 `maybeNotifyNewAlerts`
     * 就是同一个事件渲染两遍：app 在前台时主进程还会把它转交给通知进程。
     *
     * 判定与「只提醒一次」的记账全在 core（`DownloadManager.notifyTaskResult` /
     * `TunnelManager.notifyGiveUp`），app 这边只是丢弃，不做任何判断。
     *
     * 两条 WS 分支都要用它：`WebSocketPushService.push` 把同一份载荷同时广播到
     * `notification` 与 `alert` 两个频道。将来若有页面需要跟着刷新（如下载列表），
     * 单独接一条明确的路径，别再把它们塞回告警流。
     */
    private fun isNonAlertPush(dataObj: JsonObject?): Boolean =
        when (dataObj?.get("type")?.jsonPrimitive?.content) {
            "download", "tunnel" -> true
            else -> false
        }

    // ── Init ──
    fun init() {
        ensureSettingsLoaded()
        scope.launch { loadCachedData() }
        try { webSocketRepository.connect(scope) } catch (e: Exception) {
            DebugLog.w("Dashboard", "WebSocket connect failed: ${e.message}")
        }
        collectWebSocketMessages()
        observeNetworkState()
        
        // 注册跨进程广播监听：由独立进程 :ufi_notify 驱动主进程实时 Toast
        // NOT_EXPORTED：ACTION_NEW_ALERTS 是应用内部 action，无需也不应接受外部应用投递
        val filter = IntentFilter(NotificationCenter.ACTION_NEW_ALERTS)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(alertReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(alertReceiver, filter)
        }

        // 首刷仅需让出启动间隙（WS 连接/缓存装配），150ms 足够；
        // 旧 500ms 叠加后端聚合串行延迟，仪表盘首屏明显慢于其他页面
        scope.launch { delay(150); refreshDashboard() }
        checkForUpdate()
    }

    fun onCleared() {
        stopAutoRefresh()
        stopAlertPolling()
        try { appContext.unregisterReceiver(alertReceiver) } catch (_: Exception) {}
        refreshJob?.cancel()
        saveDebounceJob?.cancel()
        webSocketRepository.disconnect()
        cacheManager.shutdown()
    }

    // ── Cache ──
    private suspend fun loadCachedData() {
        val cached = cacheManager.getLatestData() ?: return
        _dashboardState.update { state ->
            state.copy(
                cpuInfo = cached.cpuJson?.let { runCatching { AppJson.decodeFromString<CpuInfo>(it) }.getOrNull() } ?: state.cpuInfo,
                memoryInfo = cached.memoryJson?.let { runCatching { AppJson.decodeFromString<MemoryInfo>(it) }.getOrNull() } ?: state.memoryInfo,
                trafficRealtime = cached.trafficJson?.let { runCatching { AppJson.decodeFromString<TrafficRealtime>(it) }.getOrNull() } ?: state.trafficRealtime,
                signalInfo = cached.signalJson?.let { runCatching { AppJson.decodeFromString<SignalInfo>(it) }.getOrNull() } ?: state.signalInfo,
                deviceInfo = cached.deviceInfoJson?.let { runCatching { AppJson.decodeFromString<DeviceInfoResponse>(it) }.getOrNull() },
                batteryInfo = cached.batteryInfoJson?.let { runCatching { AppJson.decodeFromString<BatteryInfo>(it) }.getOrNull() },
                storageInfo = cached.storageInfoJson?.let { runCatching { AppJson.decodeFromString<StorageInfo>(it) }.getOrNull() },
                uptimeInfo = cached.uptimeInfoJson?.let { runCatching { AppJson.decodeFromString<UptimeInfo>(it) }.getOrNull() },
                lastUpdated = cached.timestamp
            )
        }
    }

    /**
     * 首屏兜底：用 REST 补齐 cpu/memory/signal 中仍为空的字段。
     *
     * 这三个字段的常规通路是 WebSocket 推送，`/api/dashboard/summary` 并不返回它们；
     * 只有「缓存空 + WS 首帧未到」的首连场景需要兜底。三个请求各自独立、失败静默
     * （WS 帧随后会覆盖），且只在字段仍为空时才写入，避免把后到的新鲜数据盖回旧值。
     */
    private fun primeRealtimeFieldsIfMissing() {
        val snapshot = _dashboardState.value
        if (snapshot.cpuInfo != null && snapshot.memoryInfo != null && snapshot.signalInfo != null) return
        scope.launch {
            if (_dashboardState.value.cpuInfo == null) {
                runCatching { api.getCpuInfo() }.getOrNull()?.let { info ->
                    _dashboardState.update { if (it.cpuInfo == null) it.copy(cpuInfo = info) else it }
                }
            }
            if (_dashboardState.value.memoryInfo == null) {
                runCatching { api.getMemoryInfo() }.getOrNull()?.let { info ->
                    _dashboardState.update { if (it.memoryInfo == null) it.copy(memoryInfo = info) else it }
                }
            }
            if (_dashboardState.value.signalInfo == null) {
                runCatching { api.getSignalInfo() }.getOrNull()?.let { info ->
                    _dashboardState.update { if (it.signalInfo == null) it.copy(signalInfo = info) else it }
                }
            }
        }
    }

    private fun saveToCache() {
        val state = _dashboardState.value
        cacheManager.saveDataAsync(CachedDashboardData(
            timestamp = System.currentTimeMillis(),
            cpuJson = state.cpuInfo?.let { AppJson.encodeToString<CpuInfo>(it) },
            memoryJson = state.memoryInfo?.let { AppJson.encodeToString<MemoryInfo>(it) },
            trafficJson = state.trafficRealtime?.let { AppJson.encodeToString<TrafficRealtime>(it) },
            signalJson = state.signalInfo?.let { AppJson.encodeToString<SignalInfo>(it) },
            deviceInfoJson = state.deviceInfo?.let { AppJson.encodeToString(it) },
            batteryInfoJson = state.batteryInfo?.let { AppJson.encodeToString(it) },
            storageInfoJson = state.storageInfo?.let { AppJson.encodeToString(it) },
            uptimeInfoJson = state.uptimeInfo?.let { AppJson.encodeToString(it) }
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

    /**
     * 转场期间攒下的实时指标（2026-08-31 掉帧治理③）。
     *
     * `scope` 是 viewModelScope（主线程），而页面在根节点收 `dashboardState` 这一个大对象 ——
     * 一条 WS 推送就是一次整屏重组。稳态无所谓，但落在转场那 300ms 里会与
     * 「两页同时绘制 + 全屏模糊」叠加，直接超帧预算（表现为转场随机卡一下）。
     *
     * 所以转场进行中（[UiFrameGate.isBusy]）把 cpu/内存/流量/信号这四个**可延迟**的指标
     * 攒进这里，转场结束后一次性刷入。告警 / 通知 / 配置同步不走这条路 —— 那些要求即时可见。
     */
    @Volatile private var pendingRealtime: DashboardState? = null
    private var realtimeFlushJob: Job? = null

    /**
     * 写入一个实时指标：空闲时立即生效，转场进行中先攒着。
     *
     * flush 时只覆盖四个实时字段（不是整体覆盖 `pendingRealtime`）——
     * 转场那几百毫秒里 REST 刷新也可能落地，整体覆盖会把它的结果冲掉。
     */
    private fun applyRealtime(transform: (DashboardState) -> DashboardState) {
        if (!UiFrameGate.isBusy) {
            _dashboardState.update(transform)
            return
        }
        pendingRealtime = transform(pendingRealtime ?: _dashboardState.value)
        if (realtimeFlushJob?.isActive == true) return
        realtimeFlushJob = scope.launch(Dispatchers.Default) {
            UiFrameGate.awaitIdle()
            val pending = pendingRealtime ?: return@launch
            pendingRealtime = null
            _dashboardState.update {
                it.copy(
                    cpuInfo = pending.cpuInfo,
                    memoryInfo = pending.memoryInfo,
                    trafficRealtime = pending.trafficRealtime,
                    signalInfo = pending.signalInfo,
                )
            }
        }
    }

    private fun collectWebSocketMessages() {
        // Dispatchers.Default：整条报文的 JSON 反序列化不再占主线程（见 applyRealtime 的说明）。
        // 下游 MutableStateFlow 线程安全，Compose 只要求「读状态时在主线程」。
        scope.launch(Dispatchers.Default) {
            webSocketRepository.messages.collect { message ->
                try {
                    var changed = false
                    when (message.type) {
                        "cpu" -> try {
                            message.data?.let { element ->
                                val parsed = AppJson.decodeFromJsonElement<CpuInfo>(element)
                                applyRealtime { it.copy(cpuInfo = parsed) }
                                changed = true
                            }
                        } catch (e: Exception) { DebugLog.parseError("WS", "cpu", message.data?.toString() ?: "", e) }
                        "memory" -> try {
                            message.data?.let { element ->
                                val parsed = AppJson.decodeFromJsonElement<MemoryInfo>(element)
                                applyRealtime { it.copy(memoryInfo = parsed) }
                                changed = true
                            }
                        } catch (e: Exception) { DebugLog.parseError("WS", "memory", message.data?.toString() ?: "", e) }
                        "traffic" -> try {
                            message.data?.let { element ->
                                val parsed = AppJson.decodeFromJsonElement<TrafficRealtime>(element)
                                applyRealtime { it.copy(trafficRealtime = parsed) }
                                changed = true
                            }
                        } catch (e: Exception) { DebugLog.parseError("WS", "traffic", message.data?.toString() ?: "", e) }
                        "signal" -> try {
                            message.data?.let { element ->
                                val parsed = AppJson.decodeFromJsonElement<SignalInfo>(element)
                                applyRealtime { it.copy(signalInfo = parsed) }
                                changed = true
                            }
                        } catch (e: Exception) { DebugLog.parseError("WS", "signal", message.data?.toString() ?: "", e) }
                        "alert" -> {
                            // P0-C：后端 WS alert payload 已升级为包含完整详情，
                            // 2026-08-25：优先尝试直接处理推送数据，失败则回退拉取列表
                            try {
                                val dataObj = message.data as? JsonObject
                                when {
                                    // 下载 / 隧道不是告警记录，见 [isNonAlertPush]。
                                    // 这里也要挡：`WebSocketPushService.push` 把同一份载荷广播到
                                    // `notification` **和** `alert` 两个频道，只挡下面那条分支拦不住。
                                    isNonAlertPush(dataObj) -> Unit
                                    dataObj != null && dataObj.containsKey("message") -> {
                                        val pushedAlert = parsePushedAlert(dataObj)
                                        // 直接更新本地列表状态（追加到最前面，并限制最大数量）
                                        _monitorState.update { s ->
                                            val next = (
                                                listOf(pushedAlert) +
                                                    s.alerts.filter { it.id != pushedAlert.id }
                                                ).take(500)
                                            s.copy(alerts = next)
                                        }
                                        // 触发系统通知/Toast
                                        notificationCenter.maybeNotifyNewAlerts(listOf(pushedAlert))
                                    }
                                    else -> loadAlerts(_monitorState.value.alertRange, silent = true)
                                }
                            } catch (e: Exception) {
                                DebugLog.e("Dashboard", "Parse pushed alert failed", e)
                                loadAlerts(_monitorState.value.alertRange, silent = true)
                            }
                        }
                        "notification" -> {
                            // 2026-08-25：新公共通知服务推送，逻辑与 alert 兼容
                            try {
                                val dataObj = message.data as? JsonObject
                                // 2026-09-04：短信 / 验证码分流，**不能**当告警处理。
                                // core 的 DataScheduler 在"发现新短信"的边沿推 type=sms|verification；
                                // 走 parsePushedAlert 会把短信塞进告警列表、并受告警总闸约束。
                                // 这条分支管的是「app 在前台但不在短信页」的场景；
                                // app 在后台/被回收时由 :ufi_notify 的 NotifyService 弹（同一份 payload）。
                                val pushType = dataObj?.get("type")?.jsonPrimitive?.content
                                if (pushType == "sms" || pushType == "verification") {
                                    val extra = dataObj?.get("extra") as? JsonObject
                                    val sender = extra?.get("sender")?.jsonPrimitive?.content ?: ""
                                    if (pushType == "verification") {
                                        val code = extra?.get("code")?.jsonPrimitive?.content ?: ""
                                        if (code.isNotEmpty()) notificationCenter.notifyVerificationCode(sender, code)
                                    } else {
                                        val snippet = extra?.get("snippet")?.jsonPrimitive?.content ?: ""
                                        notificationCenter.notifyNewSms(sender, snippet)
                                    }
                                } else if (isNonAlertPush(dataObj)) {
                                    // 见 [isNonAlertPush]：下载 / 隧道不是告警记录，显式丢弃。
                                } else if (dataObj != null) {
                                    val pushedAlert = parsePushedAlert(dataObj)
                                    _monitorState.update { s ->
                                        val next = (listOf(pushedAlert) + s.alerts.filter { it.id != pushedAlert.id }).take(500)
                                        s.copy(alerts = next)
                                    }
                                    notificationCenter.maybeNotifyNewAlerts(listOf(pushedAlert))
                                }
                            } catch (e: Exception) {
                                DebugLog.e("Dashboard", "Parse notification failed", e)
                            }
                        }
                        "config_changed" -> try {
                            // P2 多端同步：另一设备（或本机其他端）改了告警配置 → 即时覆盖本地镜像。
                            // payload.data.config 是 AlertConfig 的 JSON 字符串。
                            val dataObj = message.data as? JsonObject
                            val configJson = dataObj?.get("config")?.toString()
                            if (configJson != null) {
                                val remote = AppJson.decodeFromString<AlertConfig>(configJson)
                                alertPrefs.applyRemote(remote)
                            }
                        } catch (e: Exception) { DebugLog.parseError("WS", "config_changed", message.data?.toString() ?: "", e) }
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
                        cached["cpu"]?.data?.let { element -> next = next.copy(cpuInfo = AppJson.decodeFromJsonElement<CpuInfo>(element)) }
                        cached["memory"]?.data?.let { element -> next = next.copy(memoryInfo = AppJson.decodeFromJsonElement<MemoryInfo>(element)) }
                        cached["traffic"]?.data?.let { element -> next = next.copy(trafficRealtime = AppJson.decodeFromJsonElement<TrafficRealtime>(element)) }
                        cached["signal"]?.data?.let { element -> next = next.copy(signalInfo = AppJson.decodeFromJsonElement<SignalInfo>(element)) }
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

    /**
     * 手动刷新入口（下拉刷新 / 工具栏按钮 / 网络恢复）。
     * 取消前一次手动刷新，防止并发请求堆积；forceRefresh=true 绕过 5 秒缓存。
     */
    fun refreshDashboard(forceRefresh: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = scope.launch { refreshDashboardInternal(forceRefresh) }
    }

    /**
     * 实际刷新逻辑（suspend）。
     * - 15 秒超时保护，防止 API 挂起导致下拉动画卡死
     * - forceRefresh 绕过前端 5 秒缓存
     * - auto-refresh 直接调用此方法，确保等待完成后才 delay
     *
     * #15 dashboard/summary 已切到结构化模型 [DashboardSummaryResponse]（kotlinx.serialization），
     * 直接按字段取用，无需逐字段 Gson 解析。
     */
    private suspend fun refreshDashboardInternal(forceRefresh: Boolean = false) {
        // 增加并发守卫：避免 auto-refresh 与 manual-refresh 同时打向后端聚合接口
        refreshSemaphore.withPermit {
            // ── 「有数据时静默刷新」（2026-09-05 掉帧治理：横滑落定后的重组风暴）──
            //
            // 这一行原来是无条件 `it.copy(isLoading = true, errorMessage = null)`。
            // 它是**同步整页写**：`scope` 是 viewModelScope（`Dispatchers.Main.immediate`）+
            // `CoroutineStart.DEFAULT`，主线程调用时 `isDispatchNeeded == false`，协程体内联开跑，
            // 于是 `pageForeground` 翻转的那一帧里 `DashboardState` 实例就换了一份 ——
            // `DashboardScreen` 整页 + 3 张大卡 + `MonitorScreen`（也 collect 了 dashboardState）
            // 全部重组。横滑落定时屏幕上还有可见运动（胶囊归位 +80~460ms），这一下就是"顿"。
            //
            // 而 `isLoading` 唯一的可见用途是骨架屏，判据是
            // `state.isLoading && state.deviceInfo == null`（DashboardScreen）——
            // 也就是说**有数据时把它翻成 true 没有任何 UI 效果**，纯粹是白付一次整页重组。
            // 所以：没数据才写 loading（首次加载的骨架屏反馈完整保留）；有数据时只在确实
            // 需要清错误横幅时写一次；两者都不满足就原样返回 —— `MutableStateFlow` 对
            // 结构相等的新值不会发射（`DashboardState` 是 data class），零重组。
            _dashboardState.update { current ->
                when {
                    current.deviceInfo == null -> current.copy(isLoading = true, errorMessage = null)
                    current.errorMessage != null -> current.copy(errorMessage = null)
                    else -> current
                }
            }
            try {
                DebugLog.d("Dashboard", "refreshDashboardInternal start (force=$forceRefresh)")
                val response: DashboardSummaryResponse = withTimeoutOrNull(DASHBOARD_REFRESH_TIMEOUT) {
                    val cache = dashboardSummaryCache
                    val now = System.currentTimeMillis()
                    if (!forceRefresh && cache != null && now - dashboardSummaryCacheTime < DASHBOARD_CACHE_TTL_MS) {
                        DebugLog.d("Dashboard", "Using summary cache")
                        cache
                    } else {
                        api.getDashboardSummary().also {
                            dashboardSummaryCache = it
                            dashboardSummaryCacheTime = now
                        }
                    }
                } ?: run {
                    // 超时：确保 isLoading 复位，显示错误
                    DebugLog.w("Dashboard", "refreshDashboardInternal timeout")
                    _dashboardState.update { it.copy(isLoading = false, errorMessage = "请求超时，请检查网络连接") }
                    return
                }

                // 注意：参数在调用前求值，所以这里必须先判 enabled —— 否则日志关掉了也照样把
                // 整个 dashboard 响应序列化一遍（每 5s 一次自动刷新都在白烧 CPU）
                if (DebugLog.enabled) {
                    DebugLog.json("Dashboard", "Summary response", AppJson.encodeToString<DashboardSummaryResponse>(response).take(2000))
                }

                _dashboardState.update { current ->
                    current.copy(
                        deviceInfo = response.device_info,
                        batteryInfo = response.battery,
                        storageInfo = response.storage,
                        uptimeInfo = response.uptime,
                        trafficSummary = response.traffic_summary,
                        trafficLimitConfig = response.traffic_limit,
                        networkStatus = response.network_status,
                        isLoading = false, errorMessage = null,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                saveToCache()
                // 2026-09-07：cpu/memory/signal 不在 summary 里（只走 WS 推送）。首次连接时
                // Room 缓存为空，而 core 的实时采集循环在无连接期间躺在 idle 间隔上，首帧可能
                // 迟到十几秒到一分钟——表现就是「仪表盘首连 CPU/内存/hero 制式一直是占位，
                // 退出 App 重进才有」（重进是缓存命中）。这里对仍为空的字段做一次 REST 补拉。
                primeRealtimeFieldsIfMissing()
                // 新鲜度基准：只在**成功落地**后记，失败/超时/取消都不记 ——
                // 否则一次失败会把接下来 10s 的重拉全部跳过（"越坏越不刷"）。
                dashboardSuccessElapsed = SystemClock.elapsedRealtime()
                // T09：固件版本来自独立的 goform 接口，首页设备信息卡需要。
                if (_dashboardState.value.deviceVersion == null) {
                    loadDeviceVersionSilently()
                }
            } catch (e: Exception) {
                // 更加鲁棒的取消判定：只要 coroutine 被取消，或者 OkHttp 抛出 Canceled 异常，均视为正常取消。
                val isCancelled = e is CancellationException || 
                                 !currentCoroutineContext().isActive ||
                                 e.message?.contains("Canceled", ignoreCase = true) == true ||
                                 e.cause is CancellationException
                
                if (isCancelled) {
                    DebugLog.d("Dashboard", "refreshDashboardInternal cancelled")
                    _dashboardState.update { it.copy(isLoading = false) }
                    // 必须抛出 CancellationException 以维持协程传播语义，
                    // 除非确定是 OkHttp 的非 coroutine 取消（这种情况 return）。
                    if (e is CancellationException) throw e else return
                }
                DebugLog.e("Dashboard", "refreshDashboardInternal failed", e)
                _dashboardState.update { it.copy(isLoading = false, errorMessage = "连接失败: ${e.message}") }
            }
        }
    }

    // ── Auto Refresh ──
    /**
     * 启动前台轮询。
     *
     * ## 「数据新鲜就别重拉」（2026-09-05 掉帧治理）
     * 本方法的调用点是 `DashboardScreen` 里那条以 `pageForeground` 为 key 的
     * `DisposableEffect` —— 也就是说**每次横滑回到首页都会重新进来一次**。
     * 原实现无条件先跑一次 `refreshDashboardInternal()`，于是 settle 后 0~500ms 内
     * 必然有一次 REST 回包整页写（还叠着 traffic/wifi 两路），落在胶囊归位的可见运动窗口里。
     *
     * 现在先问一次 [foregroundRefreshDelayMs]：上次成功拉取仍在新鲜窗口内就**只补定时器**
     * （delay 掉剩余时长），不发这次请求。稳态节奏完全不变 —— 第一次请求恰好落在
     * "上次成功 + [FOREGROUND_REFRESH_INTERVAL_MS]"那一刻，与一直停留在本页时的时序一致。
     *
     * 期间数据并不会变旧到看得出来：cpu / 内存 / 流量 / 信号四项走 WebSocket 实时推送，
     * 被跳过的只有 dashboard/summary 那批"分钟级才会变"的字段（设备信息 / 电池 / 存储 / 运行时长）。
     */
    fun startAutoRefresh(intervalMs: Long = 5_000L) {
        stopAutoRefresh()
        autoRefreshJob = scope.launch {
            val holdOff: Long = foregroundRefreshDelayMs(
                lastSuccessElapsedMs = dashboardSuccessElapsed,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                freshWindowMs = intervalMs,
            )
            if (holdOff > 0L) delay(holdOff)
            while (isActive) {
                refreshDashboardInternal()  // 直接 suspend，等待完成后才 delay
                delay(intervalMs)
            }
        }
    }

    fun stopAutoRefresh() { autoRefreshJob?.cancel(); autoRefreshJob = null }

    /**
     * 启动全局告警轮询（UI 进程）。
     * 解决「只有在监控页才触发 Toast」的问题：只要 App 在前台运行，无论在哪个 Tab，
     * 均以 [intervalMs] 频率检查新告警。
     */
    fun startAlertPolling(intervalMs: Long = 30_000L) {
        stopAlertPolling()
        alertPollingJob = scope.launch {
            while (isActive) {
                // 2026-08-25: 使用 silent=true 避免非监控页刷新时闪烁
                loadAlerts(_monitorState.value.alertRange, silent = true)
                delay(intervalMs)
            }
        }
    }

    fun stopAlertPolling() {
        alertPollingJob?.cancel()
        alertPollingJob = null
    }

    // ── History ──
    companion object {
        /** 历史记录最大条数：360 ≈ 10秒间隔 × 1小时，UI 图表不需要完整24小时数据 */
        private const val MAX_HISTORY_RECORDS = 360
        /** T12 告警页大小：core 把 limit 钳制在 1..200，传更大只会被静默截断 */
        private const val ALERT_PAGE_SIZE = com.ufi_axis_core.contract.Alerts.ListQuery.LIMIT_MAX
        /** T12 翻页安全上限：10 × 200 = 2000 = core 环形表 `MAX_ALERT_ROWS` 容量 */
        private const val ALERT_MAX_PAGES = 10
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

    // ── Update Check（core 侧）──
    /**
     * 检查设备端 core 有没有新版本。
     *
     * 2026-09-06 修复：判据原来是 `compareVersions(versionInfo.version, "1.0") > 0` ——
     * 拿 core 自报版本去跟写死的字符串 "1.0" 比，语义根本不成立：core 0.0.x 恒为 false
     * （所以一直不弹），而版本号一旦跨过 1.0 就变成**每次冷启动都提示更新**。
     * 现在改用 `GET /api/update/backend-info` 的 `has_update`：版本比对由 core 自己对着
     * 清单 backend 对象做，App 端只消费结论，并顺带把 latest_version / changelog 带给弹窗。
     *
     * 两个请求的失败语义**刻意不同**：
     * - `GET /api/config/version` 失败 = 设备不可达 → 整体算失败（写 errorMessage）；
     * - backend-info 失败（旧 core 没这个端点 / core 拉不到更新源回 502）→ 只是"这次问不出
     *   有没有新版"，不该把关于页顶部那行「Core x.y.z」也一起打掉，所以降级为 hasUpdate=false。
     */
    fun checkForUpdate() {
        scope.launch {
            _updateState.value = _updateState.value.copy(checking = true)
            try {
                val versionInfo = api.getServerVersion()
                val info = runCatching { api.getBackendUpdateInfo() }
                    .onFailure { DebugLog.w("Dashboard", "core backend-info 不可用: ${it.message}") }
                    .getOrNull()
                _updateState.value = UpdateState(
                    hasUpdate = info?.has_update == true,
                    // current_version 由 core 从自身 packageInfo 读；拿不到时回退 config/version
                    serverVersion = info?.current_version?.takeIf { it.isNotBlank() } ?: versionInfo.version,
                    latestVersion = info?.latest_version?.takeIf { it.isNotBlank() },
                    changelog = info?.changelog.orEmpty(),
                    updateUrl = versionInfo.update_url,
                    checking = false
                )
            } catch (e: Exception) {
                _updateState.value = UpdateState(checking = false, errorMessage = "检查更新失败: ${e.message}")
            }
        }
    }

    // ── Device Version (from ZTE goform) ──
    /**
     * 静默加载固件版本：供首页仪表盘在常规刷新时调用。
     * 失败时不写 [DashboardState.errorMessage]，避免首页在设备尚未连通时误弹红色错误条
     * （固件版本属非关键展示字段，仅 HomeDeviceInfoCard 使用）。
     */
    private suspend fun loadDeviceVersionSilently() {
        try {
            val ver = api.getDeviceVersion()
            _dashboardState.update { it.copy(deviceVersion = ver) }
        } catch (_: Exception) {
            // 静默：版本加载失败仅留空，不影响首页其余信息
        }
    }

    /**
     * 加载后端 Core 进程首次启动时间（UTC epoch ms）。用于监控页"自定义时间范围"对话框 minDateMs 下限。
     * 2026-08-08 12:09 新增：app 启动时调用一次即可（startupTime 是常量），不需刷新。
     */
    fun loadStartupTime() {
        if (_monitorState.value.startupTimeMs != null) return
        scope.launch {
            try {
                val ms = api.getStartupTime()
                _monitorState.update { it.copy(startupTimeMs = ms) }
            } catch (e: Exception) {
                // 静默失败：minDateMs=null 时对话框走 fallback（不限最小日期）
            }
        }
    }

    // ── Monitor ──
    private var lastMonitorRefreshMs: Long = 0L
    private val MIN_REFRESH_INTERVAL_MS = 5_000L

    fun loadMonitorHistory(hours: Int = 24, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastMonitorRefreshMs < MIN_REFRESH_INTERVAL_MS) return
        lastMonitorRefreshMs = now

        _monitorState.update { it.copy(selectedHours = hours, isLoading = true, errorMessage = null) }
        scope.launch {
            try {
                val types = listOf("cpu", "memory", "traffic_rx", "traffic_tx",
                    "signal_rsrp", "signal_sinr", "battery", "temperature")
                // 有限并发加载 8 类历史（Semaphore(4) 限制最大并发，避免瞬时 8 并发压垮后端）；
                // 结果合并成一次 state 写入（详见 loadMonitorHistoryRange 的性能说明）
                val loaded = coroutineScope {
                    val sem = Semaphore(4)
                    types.map { type ->
                        async { sem.withPermit { type to runCatching { api.getMonitorHistory(type, hours).points }.getOrNull() } }
                    }.awaitAll()
                }
                _monitorState.update { state ->
                    var next = state
                    loaded.forEach { (type, points) -> if (points != null) next = updateSeries(next, type, points) }
                    next.copy(isLoading = false)
                }
            } catch (e: Exception) {
                _monitorState.update { it.copy(isLoading = false, errorMessage = "加载监控数据失败: ${e.message}") }
            }
        }
    }

    fun loadMonitorHistory(type: String, hours: Int) {
        scope.launch {
            val points = runCatching { api.getMonitorHistory(type, hours).points }.getOrNull() ?: return@launch
            _monitorState.update { updateSeries(it, type, points) }
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

    /**
     * 按类批量清理（2026-09-09：存储管理的清理弹窗支持多选范围）。
     *
     * core 的 `POST /api/monitor/clean` 一次只吃一个 `type`，所以多类只能逐个发。这里刻意
     * **在同一个协程里顺序发**，而不是让 UI 循环调 [cleanHistory]：
     * - 那样会同时起 N 个协程 → N 个并发 POST 打向设备那台单线程 HTTP 服务；
     * - 每个都会各自写一次 `cleanMessage`、各自触发一次历史 + 存储统计重载，
     *   最后 toast 只剩最后一条、统计被刷 N 遍。
     *
     * 现在：逐个清完汇总成一条消息，历史与统计各只重载一次。
     * 单类失败不中断后面的（用户选了 5 类，不该因为第 2 类超时就放弃剩下 3 类），
     * 失败数量并入结果文案 —— 不能只报成功数，那会把"部分失败"说成全成功。
     *
     * **全选不要走这里**：调用方应短路成 `cleanHistory(type = "all")` 一个请求
     *（是否"全选"取决于 UI 那份可清理表清单，本层不做判断）。
     */
    fun cleanHistoryBatch(types: List<String>, days: Int) {
        if (types.isEmpty()) return
        scope.launch {
            _monitorState.update { it.copy(cleanMessage = null) }
            var totalDeleted = 0
            var failedCount = 0
            types.forEach { type ->
                try {
                    totalDeleted += api.cleanHistory(CleanHistoryRequest(type, days)).deleted.values.sum()
                } catch (_: Exception) {
                    failedCount++
                }
            }
            _monitorState.update {
                it.copy(
                    cleanMessage = if (failedCount == 0) {
                        "已清理 $totalDeleted 条记录"
                    } else {
                        "已清理 $totalDeleted 条记录，$failedCount 类清理失败"
                    }
                )
            }
            loadMonitorHistory(_monitorState.value.selectedHours)
            loadMonitorStorage()
        }
    }

    fun clearMonitorMessage() {
        _monitorState.update { it.copy(cleanMessage = null, errorMessage = null) }
    }

    // ── Monitor: 告警（事件中心；后端 /api/alerts/list 支持 start_time/end_time 区间查询） ──

    /**
     * 加载告警（事件中心）。
     * - 默认 range = 今天（MonitorTimeRange.today()，00:00 ~ now，isRealTime=true → 告警轮询）。
     * - 传入历史/自定义 range 时，按 range.queryStartMs/queryEndMs 向后端区间查询（start_time/end_time），
     *   拉取的列表即该日期范围内的全部告警；limit 放大到足够大，避免区间被截断。
     * - 所有旧调用点 loadAlerts() 仍编译通过（默认今天），行为与原 limit=50 等价。
     */
    /**
     * v20c：事件中心「全部」日期筛选 —— 拉取后端保留的全部告警。
     * 不传 start_time/end_time（走后端 limit/cursor 分支）；alertRange 记 Preset(168) 近似
     * （保证告警轮询语义仍开启）。
     */
    fun loadAllAlerts() {
        scope.launch {
            runCatching { fetchAlertsPaged() }
                .onSuccess {
                    _monitorState.update { s -> s.copy(alerts = it, alertRange = MonitorTimeRange.Preset(168)) }
                    notificationCenter.maybeNotifyNewAlerts(it)
                }
                .onFailure { e -> _monitorState.update { s -> s.copy(errorMessage = "加载告警失败: ${e.message}") } }
        }
    }

    /**
     * T12：按游标逐页拉取告警。
     *
     * core 把 `limit` 钳制在 1..200（`Alerts.ListQuery`），旧代码传 `limit = 5000` 时
     * **服务端静默截断成 200 条** —— "全量"其实只有 200 条，统计与列表都偏少。
     *
     * 页大小取 contract 的 `LIMIT_MAX`，页数上限取 core 环形表容量 / 页大小：
     * core 侧 alert_records 是环形表（超过 `AlertEngine.MAX_ALERT_ROWS` = 2000 自动淘汰最旧），
     * 所以 10 页 = 2000 条已覆盖服务端可能保留的全部记录，本地按整表聚合的未读数/分类计数因此仍然准确
     * （这也是没有改用响应里 `counts` 的原因：UI 的计数还叠了客户端日期/已读过滤，
     * 直接换成服务端计数会改变语义；真正需要服务端计数的是 T40 的服务端过滤场景）。
     */
    private suspend fun fetchAlertsPaged(
        startTimeMs: Long? = null,
        endTimeMs: Long? = null,
        startCursor: String? = null
    ): List<AlertRecord> {
        val all = mutableListOf<AlertRecord>()
        var cursor: String? = startCursor
        var page = 0
        var truncated = false
        while (page < ALERT_MAX_PAGES) {
            val res = api.getAlertList(
                limit = ALERT_PAGE_SIZE,
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                cursor = cursor
            )
            all += res.alerts
            page++
            DebugLog.d("Dashboard", "alerts page=$page size=${res.alerts.size} total=${all.size} hasMore=${res.hasMore}")
            val next = res.nextCursor
            if (!res.hasMore || next == null || res.alerts.isEmpty()) break
            cursor = next
            truncated = page >= ALERT_MAX_PAGES
        }
        if (truncated) {
            DebugLog.w("Dashboard", "告警分页达到安全上限 ${ALERT_MAX_PAGES} 页（${all.size} 条），更早的记录未拉取")
        }
        return all
    }

    /**
     * 加载告警列表。
     *
     * 性能（2026-08-26）：**首页到手立刻渲染，剩余页后台补齐**。
     * 区间查询（含默认「今天」）必须翻页才能拿到完整计数（core 把单页 limit 钳在 200，
     * 见 [fetchAlertsPaged] 的说明），但如果等 N 页全部串行拉完再写 state，
     * 进监控页就要干等好几个 RTT 才出内容。所以先用第一页点亮首屏（hero + 聚合列表），
     * 只有确实 hasMore 时才在后台继续翻页，最后再补一次完整集合修正计数。
     */
    fun loadAlerts(range: MonitorTimeRange = MonitorTimeRange.today(), silent: Boolean = false) {
        scope.launch {
            if (!silent) {
                _monitorState.update { it.copy(isLoading = true) }
            }
            val start = range.queryStartMs
            val end = range.queryEndMs
            DebugLog.d("Dashboard", "loadAlerts start (range=$range, start=$start, end=$end, silent=$silent)")
            val bounded = range is MonitorTimeRange.Custom
            val firstPage = runCatching {
                if (bounded) {
                    api.getAlertList(limit = ALERT_PAGE_SIZE, startTimeMs = start, endTimeMs = end)
                } else {
                    // Preset 滑动窗口：只取最新一页做差异检测（不翻页）
                    api.getAlertList(limit = ALERT_PAGE_SIZE)
                }
            }.getOrElse { e ->
                DebugLog.e("Dashboard", "loadAlerts failed", e)
                _monitorState.update { s -> s.copy(errorMessage = "加载告警失败: ${e.message}", isLoading = false) }
                return@launch
            }
            DebugLog.d("Dashboard", "loadAlerts first page: ${firstPage.alerts.size} hasMore=${firstPage.hasMore}")
            _monitorState.update { s -> s.copy(alerts = firstPage.alerts, alertRange = range, isLoading = false) }
            // 2026-08-25: 修复多进程去重逻辑后，在此处触发主进程 Toast
            notificationCenter.maybeNotifyNewAlerts(firstPage.alerts)

            val cursor = firstPage.nextCursor
            if (!bounded || !firstPage.hasMore || cursor == null || firstPage.alerts.isEmpty()) return@launch
            val rest = runCatching { fetchAlertsPaged(start, end, cursor) }.getOrElse { e ->
                // 补齐失败不回退首屏：首页数据仍然可用，只是计数偏少
                DebugLog.w("Dashboard", "告警补齐分页失败：${e.message}")
                return@launch
            }
            if (rest.isEmpty()) return@launch
            val all = firstPage.alerts + rest
            _monitorState.update { s ->
                // 期间用户可能已经切了范围：range 不再是当前范围就丢弃这批补齐结果
                if (s.alertRange != range) s else s.copy(alerts = all)
            }
        }
    }

    /** 单条告警已读：按当前 alertRange 重拉（保持所在日期视图不回跳） */
    fun ackAlert(id: Long) {
        val range = _monitorState.value.alertRange
        scope.launch {
            runCatching { api.ackAlert(AckRequest(id)) }
                .onSuccess { loadAlerts(range) }
                .onFailure { e -> _monitorState.update { s -> s.copy(errorMessage = "标记已读失败: ${e.message}") } }
        }
    }

    /** 全部已读（总览事件卡「全部已读」按钮）：按当前 alertRange 重拉。
     *  P3-5：改用 POST /ack-all 批量接口，消除逐条 ack 的 N+1 请求。 */
    fun ackAllAlerts() {
        val pending = _monitorState.value.alerts.filter { !it.acknowledged }
        if (pending.isEmpty()) return
        val range = _monitorState.value.alertRange
        scope.launch {
            runCatching { api.ackAllAlerts(AckAllRequest(ids = pending.map { it.id }, unreadOnly = true)) }
                .onFailure { e -> _monitorState.update { s -> s.copy(errorMessage = "全部已读失败: ${e.message}") } }
            loadAlerts(range)
        }
    }

    /**
     * 只确认**已恢复**的告警（`POST /api/alerts/ack-resolved`）。
     *
     * 与 [ackAllAlerts] 的分工：ack-all 是「把看到的这一页未读清掉」，本方法是「清理已经不存在的
     * 问题」——仍在持续的告警保持未读，所以它不会掩盖当前故障。判定「已恢复」在 core 侧
     * （`alertEngine.acknowledgeResolved`），app 不参与。
     *
     * 返回实际改动条数；null = 请求失败（错误已写进 [MonitorState.errorMessage]）。
     * 0 不是失败，是「没有符合条件的告警」，UI 要区分这两种提示。
     *
     * @param minAgeSec 只确认恢复时间已超过这么多秒的；null = 不限
     */
    suspend fun ackResolvedAlerts(minAgeSec: Long? = null): Int? {
        val range = _monitorState.value.alertRange
        // core 用 receiveJsonObject() 读体，必须带 JSON 体；不限时间就发 {}
        val body = if (minAgeSec != null) mapOf("minAgeSec" to minAgeSec) else emptyMap()
        return runCatching { api.ackResolvedAlerts(body) }
            .onSuccess { loadAlerts(range) }
            .onFailure { e ->
                _monitorState.update { s -> s.copy(errorMessage = "确认已恢复事件失败: ${e.message}") }
            }
            .getOrNull()
            ?.takeIf { it.success }
            ?.updated
    }

    /**
     * P3（应用内通知）：banner 关闭时的已读处理。
     *
     * 语义：用户通过 banner 看到并关闭某条告警 → 将该类型（type）全部未读一次性置为已读，
     * 而非只 ack 单条——banner 代表的是「该类事件已注意到」。走 POST /ack-all 批量接口，
     * 一次请求完成，消除逐条 N+1。成功后按当前 alertRange 重拉对账。
     *
     * @param type banner 关闭时携带的告警类型（作为批量定位条件）
     */
    fun ackAlertByBanner(type: String) {
        val range = _monitorState.value.alertRange
        scope.launch {
            runCatching {
                api.ackAllAlerts(AckAllRequest(type = type, unreadOnly = true))
            }.onFailure { e ->
                _monitorState.update { s -> s.copy(errorMessage = "标记已读失败: ${e.message}") }
            }
            loadAlerts(range)
        }
    }

    /**
     * 删除单条告警（事件中心「长按事件卡 → 删除」，UI 侧已做二次确认）。
     *
     * 采用「乐观更新 + 失败回滚」：
     * - 先把该条从 [MonitorState.alerts] 摘掉 → 列表立刻消失，无需等网络往返（本机 Core，通常 <50ms，
     *   但轮询间隔下若等重拉会有明显迟滞感）；
     * - 成功后按当前 alertRange 重拉，与服务端对账（顺带纠正并发新增/其它端删除）；
     * - 失败则回填错误提示并重拉，把被乐观移除的那条恢复回来（数据源仍是服务端，天然不会脏）。
     *
     * @param id 告警主键
     */
    fun deleteAlert(id: Long) {
        val range = _monitorState.value.alertRange
        // 乐观移除：本地先摘掉该条（服务端失败时下面的 loadAlerts 会把它拉回来）
        _monitorState.update { s -> s.copy(alerts = s.alerts.filterNot { it.id == id }) }
        scope.launch {
            runCatching { api.deleteAlert(AlertDeleteRequest(id)) }
                .onSuccess {
                    // 对账成功：列表已在乐观移除后即时消失，此处仅做服务端对齐（纠正并发新增/其它端删除）。
                    // 成功 Toast 改由 UI 收集 _events(AlertDeleted) 后弹出，确保反馈等 API 真正成功后才出现。
                    loadAlerts(range)
                    _events.tryEmit(UiEvent.AlertDeleted(id))
                }
                .onFailure { e ->
                    _monitorState.update { s -> s.copy(errorMessage = "删除事件失败: ${e.message}") }
                    // 回滚：重拉恢复乐观移除的那条
                    loadAlerts(range)
                }
        }
    }

    // ── Monitor: P2 时间范围 / 区间查询 / 阈值 ──

    /**
     * 按时间范围加载 8 类监控历史（P2）。
     * - Preset 走旧 hours 路径（向后兼容）；Custom 走区间模式（points=720，SQL 层桶聚合）。
     * - 与旧 [loadMonitorHistory] 共享 5s 冷却与 Semaphore(4)，避免区间+预设 5s 内互刷。
     * - 切换区间即清空增量游标。
     */
    /**
     * 加载监控历史（区间）。P9：新增 silent 参数支持「无感刷新」——轮询后台静默更新数据，
     * 不置 isLoading=true（否则图表卡每次轮询闪 loading 动画 → 布局抽搐）。
     *
     * 性能（2026-08-26）：8 个指标**并发拉取、一次性合并写入**。
     * 之前每个类型成功后各自 `_monitorState.update{}`，一次进页就是 10 次 StateFlow 发射；
     * 而 MonitorState 含 9 个 List 字段（Compose 视为 unstable），监控页整棵子树因此不可跳过，
     * 每次发射都要重跑「今日峰值」的 O(8×720) 派生 —— 这是切进监控页卡顿的主因。
     * 合并后一次加载只发射 2 次（开始 isLoading=true、结束带全部序列）。
     * 另外 `ensureSettingsLoaded()` 会读 SharedPreferences + 反序列化 JSON，
     * 原来在调用方（LaunchedEffect 的同步段）跑在主线程上，现在挪进 IO 线程。
     */
    fun loadMonitorHistoryRange(range: MonitorTimeRange, force: Boolean = false, silent: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastMonitorRefreshMs < MIN_REFRESH_INTERVAL_MS) return
        lastMonitorRefreshMs = now

        scope.launch {
            val settings = withContext(Dispatchers.IO) {
                ensureSettingsLoaded()
                _monitorState.value.settings
            }
            // 总开关停用：不发任何请求（isLoading 保持 false，由调用方处理）
            if (!settings.collectEnabled) return@launch

            _monitorState.update {
                it.copy(
                    selectedHours = (range as? MonitorTimeRange.Preset)?.hours ?: it.selectedHours,
                    selectedRange = range,
                    // 2026-08-09 18:44 silent=true 时保留原 isLoading（不置 true）——无感刷新不闪 loading
                    isLoading = if (silent) it.isLoading else true,
                    errorMessage = null
                )
            }
            try {
                val types = enabledMonitorTypes(settings)
                // 懒加载记账同步：全量拉过一轮之后，这些类型在本区间就算"已加载"，
                // 轮询（refreshVisibleMonitorTypes）才知道该刷哪些。
                loadedMonitorRangeKey = monitorRangeKey(range)
                loadedMonitorTypes.clear()
                loadedMonitorTypes.addAll(types)
                monitorTypeCursorMs.clear()
                val loaded = coroutineScope {
                    val sem = Semaphore(4)
                    types.map { type ->
                        async { sem.withPermit { type to fetchMonitorHistoryRange(type, range) } }
                    }.awaitAll()
                }
                val cursorMs: Long = range.queryEndMs
                loaded.forEach { (type, resp) -> if (resp != null) monitorTypeCursorMs[type] = cursorMs }
                // 桶宽以服务端回传为准（旧 core 无该字段 → 回落客户端估算），供图表判断空洞断线。
                val serverBucketMs: Long = loaded.firstNotNullOfOrNull { (_, resp) ->
                    resp?.bucket_ms?.takeIf { it > 0 }
                } ?: estimateBucketMs(range)
                _monitorState.update { state ->
                    var next = state.copy(bucketMs = serverBucketMs)
                    loaded.forEach { (type, resp) ->
                        if (resp != null) next = updateSeries(next, type, resp.points)
                    }
                    next.copy(isLoading = false)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _monitorState.update { it.copy(isLoading = false, errorMessage = "加载监控数据失败: ${e.message}") }
            }
        }
    }

    // ── Monitor 懒加载（2026-09-02）─────────────────────────────────────────────
    //
    // 背景：原来进监控页就一次拉满 8 类指标。而图表 Tab 的 4 个区块在 LazyColumn 里，
    // 首屏通常只看得到 1~2 个 —— 其余请求纯属浪费，还全都打在切页那一刻（最容易掉帧的时候）。
    //
    // 方案：区块**可见才拉自己那几类**。LazyColumn 只组合视口附近的 item，所以「组合」本身
    // 就是可见性信号，区块里一个 `LaunchedEffect(range, types)` 就够，不需要额外的可见性检测。
    //
    // 记账：同一区间下每类指标只拉一次；区间一换整体作废 —— 此刻仍挂载的区块会因为
    // LaunchedEffect 的 key 变了自动重拉，没露面的等它露面。

    /** 当前区间下已经拉过的指标 apiKey，与 [loadedMonitorRangeKey] 一起构成懒加载记账。 */
    private val loadedMonitorTypes: MutableSet<String> = mutableSetOf()

    /** [loadedMonitorTypes] 记账所属的区间；区间变了就整体作废。 */
    private var loadedMonitorRangeKey: String? = null

    /**
     * 当前**仍挂载**的区块所需指标的引用计数。
     *
     * 「已经拉过」不等于「现在还看得见」：滚过一遍再滚回来，[loadedMonitorTypes] 只会累积，
     * 轮询于是又变成刷全部。真正的可见性由区块自己上报（组合时 register、onDispose 时
     * unregister）；多个区块可能共享同一类指标（例如都要 traffic），所以用计数而非集合。
     */
    private val visibleMonitorTypeRefs: MutableMap<String, Int> = mutableMapOf()

    /**
     * 每类指标各自的增量游标（该类已取到数据的右边界）。
     *
     * 不能共用一个全局游标：懒加载是分批发生的，晚露面的类型若沿用先到批次推高的游标，
     * 它自己 [游标, now] 之前那段历史就永远拉不到 —— 图上表现为一块固定的空洞。
     */
    private val monitorTypeCursorMs: MutableMap<String, Long> = mutableMapOf()

    /**
     * 区间代数：每次真正换区间就 +1。
     * 在途请求回来时若代数已变则整批丢弃 —— 否则旧区间的响应会覆盖新区间的数据。
     */
    private var monitorRangeGeneration: Int = 0

    /** 当前区间下所有在途的监控请求，换区间时统一取消。 */
    private val monitorLoadJobs: MutableSet<Job> = mutableSetOf()

    private fun monitorRangeKey(range: MonitorTimeRange): String = when (range) {
        is MonitorTimeRange.Preset -> "preset:${range.hours}"
        // 必须用原始 startMs/endMs：queryStartMs/queryEndMs 在 isRealTime 时返回 nowMs()，
        // 而默认区间 today() 恒为实时 —— 用它们做 key 会让每次调用都判成"换了区间"，
        // 记账被反复清空，懒加载去重与「只刷可见」同时失效。
        is MonitorTimeRange.Custom -> "custom:${range.startMs}-${range.endMs}"
    }

    /** 区间变了：作废记账 + 取消在途请求（返回 true 表示确实换了区间）。 */
    private fun syncMonitorRangeKey(range: MonitorTimeRange): Boolean {
        val key: String = monitorRangeKey(range)
        if (loadedMonitorRangeKey == key) return false
        loadedMonitorRangeKey = key
        loadedMonitorTypes.clear()
        monitorTypeCursorMs.clear()
        monitorRangeGeneration++
        // 在途请求属于旧区间：不取消的话它们回来会把旧区间的点写进新窗口，还会推高游标。
        //
        // 必须先快照再取消：scope 是 viewModelScope（Dispatchers.Main.immediate），
        // 已在主线程时 cancel() 会**同步内联**跑完被取消协程的 finally —— 而那个 finally
        // 正是 monitorLoadJobs.remove(job)。直接 monitorLoadJobs.forEach { it.cancel() }
        // 等于在遍历中结构性修改同一个 LinkedHashSet，下一次 next() 必然
        // ConcurrentModificationException（2026-09-03 线上闪退）。
        val inflight: List<Job> = monitorLoadJobs.toList()
        monitorLoadJobs.clear()
        inflight.forEach { it.cancel() }
        return true
    }

    /** 区块进入组合（= 可见）时上报它需要的指标。 */
    fun registerVisibleMonitorTypes(types: List<String>) {
        types.forEach { type ->
            visibleMonitorTypeRefs[type] = (visibleMonitorTypeRefs[type] ?: 0) + 1
        }
    }

    /** 区块离开组合时撤销上报（与 [registerVisibleMonitorTypes] 成对调用）。 */
    fun unregisterVisibleMonitorTypes(types: List<String>) {
        types.forEach { type ->
            val next: Int = (visibleMonitorTypeRefs[type] ?: 0) - 1
            if (next <= 0) visibleMonitorTypeRefs.remove(type) else visibleMonitorTypeRefs[type] = next
        }
    }

    /**
     * 只切换时间窗口，**不发任何请求**。
     *
     * 取代过去「切区间 = 立刻全量重拉 8 类」的写法：拉取交给当时真正可见的区块自己去做
     * （它们的 `LaunchedEffect` 以 range 为 key）。没露面的区块等露面时再拉。
     */
    fun selectMonitorRange(range: MonitorTimeRange) {
        syncMonitorRangeKey(range)
        _monitorState.update {
            it.copy(
                selectedHours = (range as? MonitorTimeRange.Preset)?.hours ?: it.selectedHours,
                selectedRange = range,
                errorMessage = null,
            )
        }
    }

    /**
     * 懒加载入口：某个区块露面了，拉它自己需要的那几类指标。
     *
     * @param types 该区块需要的 apiKey；未启用的会被过滤掉。
     * @param range 当前时间窗口（同时作为记账的作用域）。
     * @param force 忽略记账强制重拉（轮询 / 用户手动重试用）。
     * @param silent 不置 `isLoading`（无感刷新，避免图表卡每轮闪一次 loading）。
     */
    fun loadMonitorTypes(
        types: List<String>,
        range: MonitorTimeRange,
        force: Boolean = false,
        silent: Boolean = false,
    ) {
        syncMonitorRangeKey(range)
        val generation: Int = monitorRangeGeneration
        lateinit var job: Job
        // LAZY + 先登记后 start：Main.immediate 下 launch 会同步跑到第一个挂起点，
        // 若在此之前就走完 finally，remove 会先于 add 发生，集合里留下已死的 Job。
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val settings = withContext(Dispatchers.IO) {
                    ensureSettingsLoaded()
                    _monitorState.value.settings
                }
                // 总开关停用：不发任何请求（与 loadMonitorHistoryRange 一致）
                if (!settings.collectEnabled) return@launch
                val pending: List<String> = types.filter { type ->
                    type in settings.enabledTypes && (force || type !in loadedMonitorTypes)
                }
                if (pending.isEmpty()) return@launch
                // 先记账再发请求：两个区块共享同一类指标（例如都要 traffic）时不会各拉一遍。
                loadedMonitorTypes.addAll(pending)
                if (!silent) {
                    _monitorState.update { it.copy(isLoading = true, errorMessage = null) }
                }
                try {
                    val loaded = coroutineScope {
                        val sem = Semaphore(4)
                        pending.map { type ->
                            async { sem.withPermit { type to fetchMonitorHistoryRange(type, range) } }
                        }.awaitAll()
                    }
                    // 区间已经换过：这批结果属于旧窗口，写进去就是脏数据。
                    if (generation != monitorRangeGeneration) return@launch
                    val cursorMs: Long = range.queryEndMs
                    loaded.forEach { (type, resp) ->
                        if (resp != null) monitorTypeCursorMs[type] = cursorMs
                    }
                    // 服务端实际生效的桶宽（旧 core 不带该字段 → 0，此时退回客户端估算值）。
                    // 8 类共用一个值：同一区间同一 points，服务端算出来必然相同。
                    val serverBucketMs: Long = loaded.firstNotNullOfOrNull { (_, resp) ->
                        resp?.bucket_ms?.takeIf { it > 0 }
                    } ?: estimateBucketMs(range)
                    _monitorState.update { state ->
                        var next = state.copy(bucketMs = serverBucketMs)
                        loaded.forEach { (type, resp) ->
                            if (resp != null) next = updateSeries(next, type, resp.points)
                        }
                        // silent 刷新不碰 isLoading：它可能正被另一个非静默请求持有，
                        // 这里清掉会让那个请求的 loading 条提前消失。
                        if (silent) next else next.copy(isLoading = false)
                    }
                    // 失败的类型撤销记账：下次露面或下一轮轮询还能重试，否则会永久空着。
                    loaded.forEach { (type, resp) -> if (resp == null) loadedMonitorTypes.remove(type) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    loadedMonitorTypes.removeAll(pending.toSet())
                    if (generation != monitorRangeGeneration) return@launch
                    _monitorState.update {
                        it.copy(
                            isLoading = if (silent) it.isLoading else false,
                            errorMessage = "加载监控数据失败: ${e.message}",
                        )
                    }
                }
            } finally {
                monitorLoadJobs.remove(job)
            }
        }
        monitorLoadJobs.add(job)
        job.start()
    }

    /**
     * 轮询用：只刷「此刻仍可见」且已经拉过的指标；没有就什么都不做。
     *
     * 这是懒加载在刷新侧的对应物 —— 用户看不见的图表不该在后台一直被刷。
     * 取 [visibleMonitorTypeRefs] 与 [loadedMonitorTypes] 的交集：前者保证"还看得见"，
     * 后者保证"已经有数据了"（还没拉过的交给区块自己的首次加载，避免和它抢同一批请求）。
     */
    fun refreshVisibleMonitorTypes(range: MonitorTimeRange, silent: Boolean = true) {
        val types: List<String> = visibleMonitorTypeRefs.keys.filter { it in loadedMonitorTypes }
        if (types.isEmpty()) return
        loadMonitorTypes(types, range, force = true, silent = silent)
    }

    /**
     * 单类型区间拉取（只取数不写 state，写入由调用方合并成一次发射）；失败返回 null。
     *
     * 返回整个响应而不只是 points：桶宽（bucket_ms）要带进 state 给图表判断空洞断线用。
     * bucketMs 显式传给服务端（见 [estimateBucketMs]），保证首屏与后续增量落在同一条 epoch 网格。
     */
    private suspend fun fetchMonitorHistoryRange(type: String, range: MonitorTimeRange): MonitorHistoryResponse? =
        when (range) {
            is MonitorTimeRange.Preset -> runCatching {
                api.getMonitorHistory(
                    type = type,
                    hours = range.hours,
                    points = MonitorTimeRange.MAX_POINTS,
                    bucketMs = estimateBucketMs(range)
                )
            }.getOrNull()
            is MonitorTimeRange.Custom -> runCatching {
                api.getMonitorHistoryRange(
                    type = type,
                    startTimeMs = range.queryStartMs,
                    endTimeMs = range.queryEndMs,
                    points = MonitorTimeRange.MAX_POINTS,
                    bucketMs = estimateBucketMs(range)
                )
            }.getOrNull()
        }

    /**
     * Custom 且含实时尾窗时的增量刷新（P2）。
     * 每类指标从自己的游标 [monitorTypeCursorMs] 回退 1 桶宽起拉（重叠一桶，避免边界漏点），endMs=now；
     * 新点按 t 与旧序列合并（t 相同替换），裁剪窗口外点（< queryStartMs），按 t 升序。
     * 增量失败静默（不闪 loading）。
     */
    fun loadMonitorHistoryIncremental() {
        val range = _monitorState.value.selectedRange
        if (range !is MonitorTimeRange.Custom) return
        if (!range.isRealTime) return
        val generation: Int = monitorRangeGeneration
        lateinit var job: Job
        // 同 loadMonitorTypes：LAZY + 先登记后 start
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val settings = withContext(Dispatchers.IO) {
                    ensureSettingsLoaded()
                    _monitorState.value.settings
                }
                // 总开关停用：不发任何请求
                if (!settings.collectEnabled) return@launch
                // ★ 懒加载：只刷此刻可见且已有数据的类型。
                // 此前这里无条件取 enabledMonitorTypes(settings)（8 类全拉），而默认区间
                // today() 恒为实时 Custom —— 也就是在最常见的路径上，轮询把懒加载整个抵消掉了。
                val types: List<String> = visibleMonitorTypeRefs.keys.filter {
                    it in loadedMonitorTypes && it in settings.enabledTypes
                }
                if (types.isEmpty()) return@launch
                val bucketMs = estimateBucketMs(range)
                val endMs = System.currentTimeMillis()
                val fresh = coroutineScope {
                    val sem = Semaphore(4)
                    types.map { type ->
                        val startMs = ((monitorTypeCursorMs[type] ?: range.queryStartMs) - bucketMs)
                            .coerceAtLeast(range.queryStartMs - bucketMs)
                        async {
                            sem.withPermit {
                                type to runCatching {
                                    // bucketMs 必须显式传：这里的请求窗口只有一两个桶那么长，
                                    // 服务端若按 (end-start)/points 自算会得到 1s 网格，
                                    // 与首屏的粗网格不相交 → mergePoints 按 t 去重完全失效。
                                    api.getMonitorHistoryRange(
                                        type = type,
                                        startTimeMs = startMs,
                                        endTimeMs = endMs,
                                        points = MonitorTimeRange.MAX_POINTS,
                                        bucketMs = bucketMs
                                    ).points
                                }.getOrNull()
                            }
                        }
                    }.awaitAll()
                }
                // 区间已换：这批结果属于旧窗口，丢弃。
                if (generation != monitorRangeGeneration) return@launch
                fresh.forEach { (type, points) -> if (points != null) monitorTypeCursorMs[type] = endMs }
                // 一次性合并写入：轮询每 N 秒一次，若每类型各发一次就是每轮 8 次整页重组
                _monitorState.update { state ->
                    // 首屏若跑在旧 core（响应没有 bucket_ms）上，state.bucketMs 会是 0；
                    // 这里用本轮实际请求的桶宽补上，图表的断档判定才有依据。
                    var next = if (state.bucketMs > 0) state else state.copy(bucketMs = bucketMs)
                    fresh.forEach { (type, points) ->
                        if (points != null) {
                            next = updateSeries(next, type, mergePoints(historyOf(next, type), points, range.queryStartMs))
                        }
                    }
                    next
                }
            } finally {
                monitorLoadJobs.remove(job)
            }
        }
        monitorLoadJobs.add(job)
        job.start()
    }

    /**
     * 桶宽估算（毫秒）：整个可见区间 / [MonitorTimeRange.MAX_POINTS]，向上取整到秒，最小 1s。
     *
     * 公式必须与 core 的 `MonitorRoutes.resolveBucketMs` 完全一致（同样向上取整到整秒）：
     * 首屏与增量都把这个值作为 `bucket_ms` 传给服务端，桶原点又是 epoch 绝对网格，
     * 两批点的 `t` 才落在同一条网格上，[mergePoints] 按 t 去重才有意义。
     * （原注释写 720、代码用的是 MAX_POINTS=240，且是向下取整——短区间会算出 0 而被钳到 1s。）
     */
    private fun estimateBucketMs(range: MonitorTimeRange): Long {
        val raw = range.spanMs / MonitorTimeRange.MAX_POINTS
        return if (raw <= 1000L) 1000L else (raw + 999L) / 1000L * 1000L
    }

    /**
     * 惰性加载监控设置（P5c-1）：首次读取持久化设置写入 [MonitorState.settings]。
     * - 幂等：仅首次真正读盘，之后直接短路（@Volatile 标志 + 非对称锁）。
     * - 在 [init] 与各监控加载入口调用，保证设置先于任何加载/轮询生效。
     */
    private fun ensureSettingsLoaded() {
        if (monitorSettingsLoaded) return
        synchronized(this) {
            if (monitorSettingsLoaded) return
            _monitorState.update { it.copy(settings = monitorPreferences.load()) }
            monitorSettingsLoaded = true
        }
    }

    /**
     * 保存监控设置（P5c-1）：持久化 + 更新 [MonitorState.settings] + 下发到 core。
     * - 持久化失败静默（MonitorPreferences 内部已兜底）。
     * - **只在 `collectEnabled` 真正变化时**才下发 `POST /api/monitor/control`（T19 复核修正）：
     *   该端点现在会写持久化真源 `AppSettings.backgroundServiceEnabled`，若像原来那样每次保存
     *   任意无关设置（时长/间隔/指标开关…）都无条件重发本地默认值 `true`，会把用户在设置页
     *   或 web 端显式停掉的后台服务静默改回"已启动"。
     * - 个性化 7 项（T40-5）同理只在真正变化时 `PUT /api/monitor/preferences`，并以服务端回显覆盖本地。
     * - 后端调用 fire-and-forget，失败静默不阻断 UI。
     */
    fun setMonitorSettings(settings: MonitorSettings) {
        val previous = _monitorState.value.settings
        monitorPreferences.save(settings)
        _monitorState.update { it.copy(settings = settings) }
        if (settings.collectEnabled != previous.collectEnabled) {
            scope.launch {
                runCatching { api.setMonitorControl(mapOf("enabled" to settings.collectEnabled)) }
            }
        }
        val payload = settings.toRemotePayload()
        if (payload == previous.toRemotePayload()) return
        scope.launch {
            val result = runCatching { api.updateMonitorPreferences(payload) }
            val echoed = result.getOrNull()?.takeIf { it.success }?.preferences
            if (echoed == null) {
                // 不打扰用户，但必须留痕：写失败意味着本地缓存已经和 core 真源不一致，
                // 下次进页面回读时会被服务端值覆盖（表现为"我改的设置又变回去了"）。
                DebugLog.w("Monitor", "监控偏好下发失败，本地已改但 core 未更新: ${result.exceptionOrNull()?.message}")
                return@launch
            }
            // 以服务端回显为准：core 会做取值域校验，若本地传了越界值，服务端返回的才是真实生效值。
            applyRemotePreferences(echoed)
        }
    }

    /**
     * 从后端回读监控相关真源并覆盖本地缓存（T19 + T40-5）。
     *
     * 两件事**必须在同一个协程里串行做完**：`collectEnabled` 来自 `/api/service/status`，
     * 另外 7 项来自 `/api/monitor/preferences`。若拆成两个协程各自 `read-modify-save`，
     * 两边都基于同一份旧 `settings` 做 copy，后写的那个会把前一个的结果冲掉。
     *
     * 任一请求失败都静默沿用本地缓存，不在 UI 上加提示（core_truth_silent 策略）。
     *
     * 是 `suspend` 而不是内部 `scope.launch`：调用方（监控页）要用回读后的 `defaultHours`
     * 决定首屏时间范围，必须能等它完成，否则会先按旧值拉一遍、再按新值拉第二遍。
     */
    suspend fun refreshMonitorSettingsFromBackend() {
        val enabled = runCatching { api.getServiceStatus().enabled }.getOrNull()
        val remote = runCatching { api.getMonitorPreferences() }.getOrNull()
        if (enabled == null && remote == null) return
        ensureSettingsLoaded()
        val current = _monitorState.value.settings
        var merged = current
        if (enabled != null) merged = merged.copy(collectEnabled = enabled)
        if (remote != null) merged = merged.applyRemote(remote)
        if (merged == current) return
        monitorPreferences.save(merged)
        _monitorState.update { it.copy(settings = merged) }
    }

    /** 把 core 回显/回读的 7 项落到状态与本地缓存（保留本地 collectEnabled）。 */    private fun applyRemotePreferences(remote: MonitorPrefsPayload) {
        ensureSettingsLoaded()
        val current = _monitorState.value.settings
        val merged = current.applyRemote(remote)
        if (merged == current) return
        monitorPreferences.save(merged)
        _monitorState.update { it.copy(settings = merged) }
    }

    /** 当前启用的指标 apiKey 列表（单指标开关过滤，P5c-1） */    private fun enabledMonitorTypes(settings: MonitorSettings): List<String> =
        MonitorMetricType.entries.map { it.apiKey }.filter { it in settings.enabledTypes }

    /** 按当前 selectedRange 强制重拉（P3 下拉 / 返回刷新入口） */
    fun refreshCurrentRange() {
        loadMonitorHistoryRange(_monitorState.value.selectedRange, force = true)
    }

    /** 将单类型序列写入 MonitorState（8 类共用） */
    private fun updateSeries(state: MonitorState, type: String, points: List<DownsampledPoint>): MonitorState =
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

    /** 读取单类型序列（8 类共用） */
    private fun historyOf(state: MonitorState, type: String): List<DownsampledPoint> =
        when (type) {
            "cpu" -> state.cpuHistory
            "memory" -> state.memoryHistory
            "traffic_rx" -> state.trafficRxHistory
            "traffic_tx" -> state.trafficTxHistory
            "signal_rsrp" -> state.signalRsrpHistory
            "signal_sinr" -> state.signalSinrHistory
            "battery" -> state.batteryHistory
            "temperature" -> state.temperatureHistory
            else -> emptyList()
        }

    /**
     * 合并新旧序列：t 相同新点替换旧点；裁剪 < windowStartMs 的旧点；按 t 升序。
     *
     * 按 t 去重的前提是两批点同网格（见 [estimateBucketMs]）。即便如此仍加一道硬上限：
     * 页面长时间开着，窗口右端一直往后推，序列会单向变长（"今天"这一档窗口起点不动，
     * 老点不会被 windowStartMs 裁掉）；超过 MAX_POINTS 的 2 倍就只保留最新的那批，
     * 图表可视精度用不到更多点，多留只是白付几何构建开销。
     */
    private fun mergePoints(old: List<DownsampledPoint>, fresh: List<DownsampledPoint>, windowStartMs: Long): List<DownsampledPoint> {
        val byT = LinkedHashMap<Long, DownsampledPoint>()
        old.forEach { byT[it.t] = it }
        fresh.forEach { byT[it.t] = it }
        val merged = byT.values.filter { it.t >= windowStartMs }.sortedBy { it.t }
        val cap = MonitorTimeRange.MAX_POINTS * 2
        return if (merged.size > cap) merged.takeLast(cap) else merged
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
    // 2026-09-06：原来这里有一份 private compareVersions()，唯一调用点是 checkForUpdate()
    // 里那条写死跟 "1.0" 比的判据。判据改成 core 的 has_update 后它就没有调用方了，
    // 一并删除 —— 版本比对现在只存在两处：core 的 UpdateManager.compareVersions（core 自身/清单）
    // 与 ToolsModule.compareVersions（App 自身/清单），两者解析规则一致。
}
