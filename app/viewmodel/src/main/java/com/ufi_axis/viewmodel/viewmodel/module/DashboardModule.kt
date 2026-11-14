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
import com.ufi_axis.data.notification.NotifyPrefs
import com.ufi_axis.util.*
// smartRefresh 消费 summary / RECONNECTED sentinel（取值以 contract 为准）
import com.ufi_axis_core.contract.WsChannel
import com.ufi_axis_core.contract.WsDataTopic
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
    private val alertPrefs: AlertPrefsRepository,
    /**
     * 跨模块 UI 事件出口。
     *
     * **刻意不把 [_events] 整条转发到这个 sink**（Network / Tools 是那么做的）：
     * `_events` 现在只跑 `AlertDeleted`，由 `MonitorScreen` 直接收集 `dashboard.events` 处理；
     * 整条转发会让 `AlertDeleted` 也流经 `MainViewModel.collectCrossModuleEvents`，
     * 该分支当前为空、行为不变，但仍会为一条已工作的链路增加第二个订阅者。
     * 写操作提示直接投递到 sink，两条链路相互独立，修改其一不影响另一条。
     *
     * 默认值仅用于让**单元测试**沿用六参构造（测试关注取数与并发，不涉及提示通道）。
     * 生产路径由 `MainViewModel` 显式传入真实 sink。
     */
    private val crossModuleEventSink: MutableSharedFlow<UiEvent> =
        MutableSharedFlow(extraBufferCapacity = 16)
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

    /** 按天事件计数（月历热力）：`yyyy-MM-dd` → 条数。 */
    private val _alertDailyCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val alertDailyCounts: StateFlow<Map<String, Int>> = _alertDailyCounts.asStateFlow()

    /** 拉取某个月（或任意区间）的每日事件计数。失败时保留上一次结果。 */
    fun loadAlertDailyCounts(start: Long, end: Long) {
        scope.launch {
            runCatching { api.getAlertDailyCounts(start, end) }
                .onSuccess { element ->
                    val days = element.jsonObject["days"]?.jsonObject ?: return@onSuccess
                    _alertDailyCounts.value = days.mapValues { (_, v) ->
                        runCatching { v.jsonPrimitive.int }.getOrDefault(0)
                    }
                }
                .onFailure { DebugLog.e("Dashboard", "loadAlertDailyCounts failed", it) }
        }
    }

    // ── UI 事件出口（ViewModel → Compose；如删除成功 Toast 信号）──
    // 同构于 ToolsModule/NetworkModule 的 _events，但仅面向 UI（不转发跨模块 sink）。
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    /**
     * 写操作成功提示：直接投递到 [crossModuleEventSink] → `MainViewModel.writeNotice`
     * → Activity 级 Toast 宿主，与失败提示共用同一个出口。
     *
     * 文案必须指明**生效的是哪一项**：监控设置四个二级页共用同一个 [setMonitorSettings]，
     * 泛化的「保存成功」无法区分落地的是采集间隔还是保留天数。
     */
    private fun emitWriteNotice(
        message: String,
        subtitle: String? = null,
        type: WriteNoticeType = WriteNoticeType.SUCCESS,
    ) {
        crossModuleEventSink.tryEmit(UiEvent.ShowWriteNotice(message, subtitle, type))
    }

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
     * 重拉直接命中缓存，但仍会走一遍状态写 + 重组）；本字段决定是否连请求都不发送。
     *
     * 刻意用 `SystemClock.elapsedRealtime()` 而不是 `System.currentTimeMillis()`：
     * 后者可被系统时间调整改写，会把陈旧数据判成新鲜（或反之）。理由详见 `DataFreshness.kt`。
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
     * 从顶层取这三个字段会得到恒定的 id=0、value=""、threshold=""，
     * 导致告警详情无值，并使 NotificationCenter 的 (timestamp, id) 复合游标退化。
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
     * 这两种推送 core **不写 alert_records**：包进 [parsePushedAlert] 再写入
     * `MonitorState.alerts` 只会让事件中心多出两条列表里查不到的「告警」，还会推进告警游标。
     * 而它们的系统通知已经由 `:ufi_notify` 的 `NotifyService` 渲染
     * （`notifyDownloadResult` / `notifyTunnelFailure`），再走 `maybeNotifyNewAlerts`
     * 就是同一个事件渲染两遍：app 在前台时主进程还会把它转交给通知进程。
     *
     * 判定与「只提醒一次」的记账全在 core（`DownloadManager.notifyTaskResult` /
     * `TunnelManager.notifyGiveUp`），app 这边只是丢弃，不做任何判断。
     *
     * 两条 WS 分支都必须调用该判定：`WebSocketPushService.push` 把同一份载荷同时广播到
     * `notification` 与 `alert` 两个频道。若页面需要跟随刷新（如下载列表），
     * 应接一条独立路径，不应写回告警流。
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

        // 首刷只需让出启动间隙（WS 连接/缓存装配），150ms 足够；
        // 500ms 会与后端聚合的串行延迟叠加，使仪表盘首屏明显慢于其他页面
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

    /**
     * 丢掉本机仪表盘 Room 缓存（切换/退出设备时）。
     *
     * 缓存为**全局一份**、不按设备分桶：切换到另一台 Core 后若不清除，
     * 首屏会先渲染上一台的 CPU/流量，随后被新数据覆盖，表现为「数据串台」。
     */
    suspend fun clearDashboardCache() {
        runCatching { cacheManager.clearCache() }
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
     * 转场期间暂存的实时指标。
     *
     * `scope` 是 viewModelScope（主线程），而页面在根节点收 `dashboardState` 这一个大对象 ——
     * 一条 WS 推送就是一次整屏重组。稳态下无影响，但落在转场那 300ms 内会与
     * 「两页同时绘制 + 全屏模糊」叠加，直接超出帧预算（表现为转场偶发掉帧）。
     *
     * 所以转场进行中（[UiFrameGate.isBusy]）把 cpu/内存/流量/信号/连接态这五个**可延迟**的
     * 指标攒进这里，转场结束后一次性刷入。告警 / 通知 / 配置同步不走这条路 —— 那些要求即时可见。
     */
    @Volatile private var pendingRealtime: DashboardState? = null

    /**
     * 转场期间暂存的 `ppp_status`。
     *
     * 单独存一个字段而不是复用 [pendingRealtime] 的 `networkStatus`：flush 时只能把
     * `ppp_status` 合并到**当前**的 `networkStatus` 上，不能整体覆盖 —— 转场那几百毫秒里
     * summary REST 可能刚落地了更新的 operator / mobile_data / network_type。
     */
    @Volatile private var pendingPppStatus: String? = null
    private var realtimeFlushJob: Job? = null

    /**
     * 写入一个实时指标：空闲时立即生效，转场进行中先暂存。
     *
     * flush 时只覆盖实时字段（不是整体覆盖 `pendingRealtime`）——
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
            val pending = pendingRealtime
            val pendingPpp = pendingPppStatus
            pendingRealtime = null
            pendingPppStatus = null
            _dashboardState.update { current ->
                var next = current
                if (pending != null) {
                    next = next.copy(
                        cpuInfo = pending.cpuInfo,
                        memoryInfo = pending.memoryInfo,
                        trafficRealtime = pending.trafficRealtime,
                        signalInfo = pending.signalInfo,
                    )
                }
                // 只合并 ppp_status，不能整体覆盖 networkStatus —— 转场期间 summary REST
                // 可能刚落地了更新的 operator / mobile_data / network_type。
                if (pendingPpp != null && next.networkStatus != null) {
                    next = next.copy(networkStatus = next.networkStatus.copy(ppp_status = pendingPpp))
                }
                next
            }
        }
    }

    /**
     * 写入一条实时连接态。
     *
     * 与 [applyRealtime] 同一套转场门控，但**不碰** `networkStatus` 的其它字段：
     * 只对 `ppp_status` 做 merge。这样 summary REST（operator / mobile_data / network_type）
     * 与 connection WS（ppp）可以并行落地，谁都不会把对方冲掉。
     */
    private fun applyConnectionRealtime(pppStatus: String) {
        if (!UiFrameGate.isBusy) {
            _dashboardState.update { state ->
                state.copy(networkStatus = state.networkStatus?.copy(ppp_status = pppStatus))
            }
            return
        }
        pendingPppStatus = pppStatus
        if (realtimeFlushJob?.isActive == true) return
        realtimeFlushJob = scope.launch(Dispatchers.Default) {
            UiFrameGate.awaitIdle()
            val pendingPpp = pendingPppStatus ?: return@launch
            pendingPppStatus = null
            _dashboardState.update { current ->
                if (current.networkStatus != null) {
                    current.copy(networkStatus = current.networkStatus.copy(ppp_status = pendingPpp))
                } else current
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
                        // 与网络页共用同一个解码函数（RealtimeSignalParser.kt）。
                        // 两处各写一份 when/decode，频道名与坏帧语义会逐渐分叉，
                        // 进而出现「仪表盘正常、网络页一直无服务」这类通路不一致问题。
                        WsChannel.SIGNAL -> signalFromWsMessage(message)?.let { parsed ->
                            applyRealtime { it.copy(signalInfo = parsed) }
                            changed = true
                        }
                        // 实时连接态。core 在采集 signal 的同一次 goform 查询中已取得 ppp_status，
                        // 该频道不产生额外设备开销；缺少它时，仪表盘 hero 卡的
                        // 「已连接/未连接」只能等待 10s 的 summary 轮询。
                        WsChannel.CONNECTION -> pppStatusFromWsMessage(message)?.let { ppp ->
                            applyConnectionRealtime(ppp)
                            // 不置 changed：networkStatus 不进 Room 缓存（saveToCache 没有它），
                            // 置了只会把 debounceSaveToCache 的 5s 计时再推后一次。
                        }
                        "alert" -> {
                            // WS alert 载荷包含完整详情：优先直接处理推送数据，
                            // 失败时回退为拉取列表。
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
                            // 业务类推送（短信 / 验证码 / 下载 / 隧道）的**主进程入口**。
                            //
                            // 短信 / 验证码只在该频道广播（`PushChannel.SINGLE_TOPIC_SCENES`），
                            // 而 `WsChannel.UI_TOPICS` 不包含 `notification`，唯一订阅方 `:ufi_notify`
                            // 也只在「前台服务保活」开启后才建立 WS；保活关闭时这类系统通知无法发出
                            // （邮件由 core 自行发送，不受影响）。
                            // 因此主进程订阅该频道并把载荷交给 NotificationCenter，由它的进程门
                            // 转交 `:ufi_notify` 发射（显式广播会自动拉起该进程）。
                            //
                            // 这里**只认业务类 type**：告警形状的载荷仍由 `alert` 分支独家处理，
                            // 否则 core 的 `notification` + `alert` 双发会让同一条告警被处理两遍。
                            try {
                                val dataObj = message.data as? JsonObject
                                val pushType = dataObj?.get("type")?.jsonPrimitive?.content
                                // 保活开着时 `:ufi_notify` 已订阅同一条推送并直接发射，主进程再转交就是双发。
                                // 判据复用保活的唯一闸门（KeepAliveGate），不引入第二套状态。
                                if (pushType != null && NotifyPrefs.keepAliveShouldRun(appContext)) {
                                    // 交给 :ufi_notify，本进程不处理
                                } else when (pushType) {
                                    "sms", "verification" -> {
                                        val extra = dataObj?.get("extra") as? JsonObject
                                        val sender = extra?.get("sender")?.jsonPrimitive?.content ?: ""
                                        if (pushType == "verification") {
                                            val code = extra?.get("code")?.jsonPrimitive?.content ?: ""
                                            if (code.isNotEmpty()) notificationCenter.notifyVerificationCode(sender, code)
                                        } else {
                                            val snippet = extra?.get("snippet")?.jsonPrimitive?.content ?: ""
                                            notificationCenter.notifyNewSms(sender, snippet)
                                        }
                                    }
                                    // 终态判定（"这次才刚变成完成/失败"、"重连到达上限才放弃"）与隧道的
                                    // `tunnel_notify_on_failure` 闸门都在 core，推送到达即代表该提醒；
                                    // 这里只把 core 给的 title/message 渲染出来，不比状态。
                                    // 它们也不进 `MonitorState.alerts` —— core 不写 alert_records，见 [isNonAlertPush]。
                                    "download", "tunnel" -> {
                                        val extra = dataObj?.get("extra") as? JsonObject
                                        val title = dataObj?.get("title")?.jsonPrimitive?.content ?: ""
                                        val body = dataObj?.get("message")?.jsonPrimitive?.content ?: ""
                                        if (pushType == "download") {
                                            notificationCenter.notifyDownloadResult(
                                                title = title.ifBlank { "下载任务" },
                                                message = body,
                                                isError = extra?.get("status")?.jsonPrimitive?.content == "error"
                                            )
                                        } else {
                                            notificationCenter.notifyTunnelFailure(
                                                kind = extra?.get("kind")?.jsonPrimitive?.content ?: "",
                                                name = extra?.get("name")?.jsonPrimitive?.content ?: "",
                                                title = title.ifBlank { "隧道异常" },
                                                message = body
                                            )
                                        }
                                    }
                                    // 其余（告警形状）忽略：由 `alert` 分支处理，见本分支开头注释。
                                    else -> Unit
                                }
                            } catch (e: Exception) {
                                DebugLog.e("Dashboard", "Parse notification failed", e)
                            }
                        }
                        "config_changed" -> try {
                            // 多端同步：另一设备（或本机其他端）修改告警配置后，立即覆盖本地镜像。
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
     * 预加载 / 前台化用的刷新入口。与 [refreshDashboard] 的**唯一**区别：
     * 不取消在飞的刷新，数据够新时直接不发请求。
     *
     * 需要独立入口的原因：[refreshDashboard] 无条件执行 `refreshJob?.cancel()`，
     * 这符合手动刷新的语义（用户已触发新请求，上一次的结果不再重要）。
     * 但预加载与页面自身的首次加载几乎同时发生，两者都调用 [refreshDashboard] 时，
     * 后发请求会取消先发请求，浪费一次聚合请求。本入口在已有请求在飞时直接返回。
     *
     * 新鲜度窗口用默认的 [FOREGROUND_REFRESH_INTERVAL_MS]（= 页面轮询周期），
     * 语义与 `startAutoRefresh` 的首轮 hold-off 一致，不会出现"轮询刚刷过、
     * 前台化又认为该拉"的自相矛盾。
     */
    fun refreshDashboardIfStale() {
        if (refreshJob?.isActive == true) return
        if (_dashboardState.value.deviceInfo != null &&
            isForegroundDataFresh(dashboardSuccessElapsed, SystemClock.elapsedRealtime())
        ) {
            return
        }
        refreshJob = scope.launch { refreshDashboardInternal(false) }
    }

    /**
     * 换设备 / 换地址后清除新鲜度基准。与 [clearDashboardCache] 同批调用：
     * 只清 Room 缓存而不清基准时，新设备首屏会因旧基准仍在窗口内而跳过请求。
     */
    fun resetFreshness() {
        dashboardSuccessElapsed = 0L
    }

    /**
     * 实际刷新逻辑（suspend）。
     * - 15 秒超时保护，避免 API 挂起导致下拉动画无法结束
     * - forceRefresh 绕过前端 5 秒缓存
     * - auto-refresh 直接调用此方法，确保等待完成后才 delay
     *
     * dashboard/summary 使用结构化模型 [DashboardSummaryResponse]（kotlinx.serialization），
     * 按字段直接取用，无需逐字段 Gson 解析。
     */
    private suspend fun refreshDashboardInternal(forceRefresh: Boolean = false) {
        // 增加并发守卫：避免 auto-refresh 与 manual-refresh 同时打向后端聚合接口
        refreshSemaphore.withPermit {
            // ── 有数据时静默刷新 ──
            //
            // 无条件执行 `it.copy(isLoading = true, errorMessage = null)` 会产生一次
            // **同步整页写**：`scope` 是 viewModelScope（`Dispatchers.Main.immediate`）+
            // `CoroutineStart.DEFAULT`，主线程调用时 `isDispatchNeeded == false`，协程体内联执行，
            // 因此 `pageForeground` 翻转的那一帧会替换 `DashboardState` 实例，
            // 使 `DashboardScreen` 整页 + 3 张大卡 + `MonitorScreen`（也 collect 了 dashboardState）
            // 全部重组。横滑落定时屏幕上仍有可见运动（胶囊归位 +80~460ms），叠加后表现为顿挫。
            //
            // `isLoading` 唯一的可见用途是骨架屏，判据为
            // `state.isLoading && state.deviceInfo == null`（DashboardScreen），
            // 即**有数据时将其置为 true 不产生任何 UI 效果**，只会多付一次整页重组。
            // 因此：无数据时才写 loading（首次加载的骨架屏反馈完整保留）；有数据时仅在
            // 需要清除错误横幅时写一次；两者都不满足则原样返回 —— `MutableStateFlow` 对
            // 结构相等的新值不发射（`DashboardState` 是 data class），不产生重组。
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

                // 参数在调用前求值，因此必须先判断 enabled；否则即使日志已关闭，仍会序列化
                // 整个 dashboard 响应（每 5s 一次自动刷新持续消耗 CPU）
                if (DebugLog.enabled) {
                    DebugLog.json("Dashboard", "Summary response", AppJson.encodeToString<DashboardSummaryResponse>(response).take(2000))
                }

                // 切 Tab 落定前的顿挫：把本次结果延后到转场结束之后再写。
                //
                // 链路：点 Tab → 入页 `pageForeground` 翻真 → 立刻发 summary，同时在
                // `primeRealtimeFieldsIfMissing()` 里再补 2~3 个请求。局域网内这批响应落在
                // **30–250ms** 之后，正好压在页面转场（≈350–420ms）的**最后 100–200ms**。
                // 而这里的 `_dashboardState.update` 是一次**同步整页写**：仪表盘整页 +
                // MonitorScreen（它也 collect 同一个 state）一起重组，叠加在 Pager 的
                // 逐帧滚动上，表现为动画结尾的顿挫。
                //
                // 转场闸门即为此存在（WS 指标路径已采用，见 `applyRealtime`），
                // 此处将同一条约束应用到 HTTP 路径：转场中先暂存，落定后立刻写。
                // 代价：数据晚 ~100–300ms 出现，而这段时间页面仍在滑入，观感上不可见。
                if (UiFrameGate.isBusy) UiFrameGate.awaitIdle()
                _dashboardState.update { current ->
                    current.copy(
                        deviceInfo = response.device_info,
                        batteryInfo = response.battery,
                        storageInfo = response.storage,
                        uptimeInfo = response.uptime,
                        trafficSummary = response.traffic_summary,
                        trafficLimitConfig = response.traffic_limit,
                        // summary 的 ppp_status 为空串表示本轮未读到，不能据此判定为断开。
                        // 保留上一帧的非空值；真正的实时值由 connection WS 持续覆盖。
                        networkStatus = response.network_status?.let { fresh ->
                            val prev = current.networkStatus
                            if (fresh.ppp_status.isBlank() && prev != null) {
                                fresh.copy(ppp_status = prev.ppp_status)
                            } else fresh
                        } ?: current.networkStatus,
                        isLoading = false, errorMessage = null,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                saveToCache()
                // cpu/memory/signal 不在 summary 里（只走 WS 推送）。首次连接时
                // Room 缓存为空，而 core 的实时采集循环在无连接期间处于 idle 间隔，首帧可能
                // 迟到十几秒到一分钟，表现为「仪表盘首连 CPU/内存/hero 制式一直是占位，
                // 退出 App 重进才有」（重进为缓存命中）。因此对仍为空的字段做一次 REST 补拉。
                primeRealtimeFieldsIfMissing()
                // 新鲜度基准：仅在**成功落地**后记录，失败/超时/取消均不记录，
                // 否则一次失败会跳过接下来 10s 的全部重拉。
                dashboardSuccessElapsed = SystemClock.elapsedRealtime()
                // 固件版本来自独立的 goform 接口，首页设备信息卡需要。
                if (_dashboardState.value.deviceVersion == null) {
                    loadDeviceVersionSilently()
                }
                // EPS 承载 QoS（AT+CGEQOSRDP）同样走独立端点。
                //
                // 仅在**尚未取得**时拉取，不跟随 10s 轮询：AT 通道全局互斥，
                // core 侧虽有 5 分钟缓存，但缓存过期那一次仍需实际发送 AT 指令，
                // 若落在 summary 的关键路径上会拖慢整张仪表盘。
                // 重新驻网后的新值依赖重进页面（或 App 重启）刷新。
                if (_dashboardState.value.deviceQos == null) {
                    loadDeviceQosSilently()
                }
            } catch (e: Exception) {
                // 取消判定：协程被取消，或 OkHttp 抛出 Canceled 异常，均视为正常取消。
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
     * ## 数据新鲜时跳过重拉
     * 调用点是 `DashboardScreen` 中那条以 `pageForeground` 为 key 的
     * `DisposableEffect`，即**每次横滑回到首页都会重新进入一次**。
     * 若无条件先执行一次 `refreshDashboardInternal()`，settle 后 0~500ms 内
     * 必然出现一次 REST 回包整页写（还叠加 traffic/wifi 两路），落在胶囊归位的可见运动窗口内。
     *
     * 因此先经 [foregroundRefreshDelayMs] 判断：上次成功拉取仍在新鲜窗口内时**只补定时器**
     * （delay 掉剩余时长），不发送本次请求。稳态节奏不变：第一次请求恰好落在
     * 「上次成功 + [FOREGROUND_REFRESH_INTERVAL_MS]」那一刻，与一直停留在本页时的时序一致。
     *
     * 期间数据不会明显变旧：cpu / 内存 / 流量 / 信号四项走 WebSocket 实时推送，
     * 被跳过的只有 dashboard/summary 中分钟级变化的字段（设备信息 / 电池 / 存储 / 运行时长）。
     */
    /**
     * 周期取数暂停闸门。返回 true 时下方两个轮询**跳过本轮请求**，仅执行 delay。
     *
     * 唯一接线方是 `MainViewModel`，接入的是「设备端 Core 正在更新」。
     * 升级期间 HTTP 必然中断约 1 分钟，这两条轮询会持续失败并把 errorMessage 写入 state，
     * 界面上表现为连续的「加载告警失败」「连接失败」。
     *
     * 采用「跳过一轮」而非 cancel job：这两个 job 的启停归 UI 宿主
     * （`MainActivity` 管告警轮询、`DashboardScreen` 管首页刷新），在此处 cancel 后
     * 由谁重启需要跨层协调，且容易在 Activity 处于后台时重新拉起轮询。
     * 跳过一轮则无需协调且能自动恢复：闸门放开后，下一轮自然继续。
     */
    var pollGate: () -> Boolean = { false }

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
                if (!pollGate()) refreshDashboardInternal()  // 直接 suspend，等待完成后才 delay
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
                // silent=true 避免非监控页刷新时闪烁；silent 只抑制 isLoading，不抑制 errorMessage，
                // 因此 Core 更新期间必须依赖 pollGate 跳过
                if (!pollGate()) loadAlerts(_monitorState.value.alertRange, silent = true)
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
        /** 历史记录最大条数：360 ≈ 10 秒间隔 × 1 小时；UI 图表不需要完整 24 小时数据 */
        private const val MAX_HISTORY_RECORDS = 360
        /** 告警页大小：core 将 limit 钳制在 1..200，传入更大值会被静默截断 */
        private const val ALERT_PAGE_SIZE = com.ufi_axis_core.contract.Alerts.ListQuery.LIMIT_MAX
        /** 翻页安全上限：10 × 200 = 2000，等于 core 环形表 `MAX_ALERT_ROWS` 容量 */
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
     * 检查设备端 core 是否有新版本。
     *
     * 判据为 `GET /api/update/backend-info` 的 `has_update`：版本比对由 core 针对清单
     * backend 对象完成，App 端只消费结论，并顺带把 latest_version / changelog 传给弹窗。
     *
     * 不得用 core 自报版本与写死的 "1.0" 比较（`compareVersions(versionInfo.version, "1.0") > 0`）：
     * core 0.0.x 恒为 false（永不提示），版本号跨过 1.0 后则**每次冷启动都提示更新**。
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
                _updateState.update {
                    it.copy(
                        hasUpdate = info?.has_update == true,
                        // current_version 由 core 从自身 packageInfo 读；拿不到时回退 config/version
                        serverVersion = info?.current_version?.takeIf { v -> v.isNotBlank() } ?: versionInfo.version,
                        latestVersion = info?.latest_version?.takeIf { v -> v.isNotBlank() },
                        changelog = info?.changelog.orEmpty(),
                        updateUrl = versionInfo.update_url,
                        checking = false,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                // 只更新失败原因，**不构造新 state**：构造会清空上一次成功读到的
                // serverVersion/latestVersion/changelog，使设备一次临时不可达就抹掉
                // 关于页的「Core x.y.z」行（与本函数 KDoc 的降级约定相反）。
                _updateState.update {
                    it.copy(checking = false, errorMessage = "检查更新失败: ${e.message}")
                }
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
            if (UiFrameGate.isBusy) UiFrameGate.awaitIdle()
            _dashboardState.update { it.copy(deviceVersion = ver) }
        } catch (_: Exception) {
            // 静默：版本加载失败仅留空，不影响首页其余信息
        }
    }

    /**
     * 静默加载 EPS 承载 QoS（`AT+CGEQOSRDP` → QCI / 上下行 AMBR）。
     *
     * 与 [loadDeviceVersionSilently] 同一手法：非关键展示字段，失败只留空，不弹错误条。
     *
     * core 侧已经保证「AT 不可用」回的是 200 + `available = false`，所以这里 catch 到的
     * 只会是真正的网络/序列化异常。两者最终都表现为卡片上显示"—"，UI 不需要区分。
     */
    private suspend fun loadDeviceQosSilently() {
        try {
            val qos = api.getDeviceQos()
            if (UiFrameGate.isBusy) UiFrameGate.awaitIdle()
            _dashboardState.update { it.copy(deviceQos = qos) }
        } catch (_: Exception) {
            // 静默：AT 通道在很多设备上本就不存在，这不是错误
        }
    }

    // 热区温度拉取（CPU 弹窗展示关键热区）。失败静默：热区读不到时弹窗不显示该区，
    // 不影响仪表盘其它数据。
    private suspend fun loadThermalZonesSilently() {
        try {
            val resp = AppJson.decodeFromJsonElement<ThermalZonesResponse>(api.getThermalZones())
            if (resp.zones.isNotEmpty()) {
                _dashboardState.update { it.copy(thermalZones = resp.zones) }
            }
        } catch (_: Exception) {
            // 静默：热区在部分固件上读不到，非致命
        }
    }

    /**
     * 加载后端 Core 进程首次启动时间（UTC epoch ms）。用于监控页"自定义时间范围"对话框 minDateMs 下限。
     * App 启动时调用一次即可（startupTime 为常量），无需刷新。
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
     * 按类批量清理（存储管理的清理弹窗支持多选范围）。
     *
     * core 的 `POST /api/monitor/clean` 一次只吃一个 `type`，所以多类只能逐个发。这里刻意
     * **在同一个协程里顺序发送**，而不是让 UI 循环调用 [cleanHistory]：
     * - 后者会同时启动 N 个协程，向设备的单线程 HTTP 服务发出 N 个并发 POST；
     * - 每个请求都会各写一次 `cleanMessage`、各触发一次历史 + 存储统计重载，
     *   导致 toast 只保留最后一条、统计被重复刷新 N 次。
     *
     * 因此逐个清理后汇总为一条消息，历史与统计各只重载一次。
     * 单类失败不中断后续类型（例如选中 5 类时，第 2 类超时不应放弃剩余 3 类），
     * 失败数量并入结果文案 —— 只报成功数会把「部分失败」表述为全部成功。
     *
     * **全选不走此路径**：调用方应短路为 `cleanHistory(type = "all")` 单个请求
     *（是否「全选」取决于 UI 的可清理表清单，本层不做判断）。
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
     * - 既有调用点 `loadAlerts()` 无需修改（默认今天），行为与 limit=50 等价。
     */
    /**
     * 事件中心「全部」日期筛选：拉取后端保留的全部告警。
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
     * 按游标逐页拉取告警。
     *
     * core 将 `limit` 钳制在 1..200（`Alerts.ListQuery`）；传入 `limit = 5000` 时
     * **服务端静默截断为 200 条**，所谓「全量」实际只有 200 条，统计与列表都会偏少。
     *
     * 页大小取 contract 的 `LIMIT_MAX`，页数上限取 core 环形表容量 / 页大小：
     * core 侧 alert_records 是环形表（超过 `AlertEngine.MAX_ALERT_ROWS` = 2000 自动淘汰最旧），
     * 所以 10 页 = 2000 条已覆盖服务端可能保留的全部记录，本地按整表聚合的未读数/分类计数因此仍然准确
     * （这也是没有改用响应里 `counts` 的原因：UI 的计数还叠了客户端日期/已读过滤，
     * 直接改用服务端计数会改变语义；服务端计数仅适用于服务端过滤场景）。
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
     * 性能：**首屏立即渲染，剩余页后台补齐**。
     * 区间查询（含默认「今天」）必须翻页才能拿到完整计数（core 把单页 limit 钳在 200，
     * 见 [fetchAlertsPaged] 的说明），但如果等 N 页全部串行拉完再写 state，
     * 进监控页就要干等好几个 RTT 才出内容。所以先用第一页点亮首屏（hero + 聚合列表），
     * 只有确实 hasMore 时才在后台继续翻页，最后再补一次完整集合修正计数。
     */
    fun loadAlerts(range: MonitorTimeRange = MonitorTimeRange.today(), silent: Boolean = false) {
        scope.launch {
            if (!silent) {
                _monitorState.update { it.copy(alertsLoading = true) }
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
                _monitorState.update { s -> s.copy(errorMessage = "加载告警失败: ${e.message}", alertsLoading = false) }
                return@launch
            }
            DebugLog.d("Dashboard", "loadAlerts first page: ${firstPage.alerts.size} hasMore=${firstPage.hasMore}")
            // 告警列表同样受转场闸门管辖（与本文件图表序列那条同一纪律）：
            // 它会一次性替换整个告警列表，落在转场里就是监控页整块重组。
            if (UiFrameGate.isBusy) UiFrameGate.awaitIdle()
            _monitorState.update { s ->
                s.copy(
                    alerts = firstPage.alerts,
                    alertRange = range,
                    alertsLoading = false,
                    alertsLoaded = true
                )
            }
            // 多进程去重由 NotificationCenter 承担，此处只触发主进程 Toast
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
     *  使用 POST /ack-all 批量接口，消除逐条 ack 的 N+1 请求。 */
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
     * 应用内通知：banner 关闭时的已读处理。
     *
     * 语义：用户通过 banner 看到并关闭某条告警时，将该类型（type）的全部未读一次性置为已读，
     * 而非只 ack 单条——banner 代表「该类事件已注意到」。走 POST /ack-all 批量接口，
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
     * - 先从 [MonitorState.alerts] 摘除该条，列表立即消失，无需等待网络往返（本机 Core 通常 <50ms，
     *   但按轮询间隔等待重拉会有明显迟滞）；
     * - 成功后按当前 alertRange 重拉，与服务端对账（同时纠正并发新增/其它端删除）；
     * - 失败则回填错误提示并重拉，恢复被乐观移除的条目（数据源仍为服务端，不存在本地不一致）。
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

    // ── Monitor: 时间范围 / 区间查询 / 阈值 ──

    /**
     * 按时间范围加载 8 类监控历史。
     * - Preset 走旧 hours 路径（向后兼容）；Custom 走区间模式（points=720，SQL 层桶聚合）。
     * - 与 [loadMonitorHistory] 共享 5s 冷却与 Semaphore(4)，避免区间与预设路径在 5s 内互相刷新。
     * - 切换区间即清空增量游标。
     */
    /**
     * 加载监控历史（区间）。silent 参数支持「无感刷新」：轮询在后台静默更新数据，
     * 不置 isLoading=true（否则图表卡每次轮询都会闪现 loading 动画，导致布局抖动）。
     *
     * 性能：8 个指标**并发拉取、一次性合并写入**。
     * 若每个类型成功后各自 `_monitorState.update{}`，一次进页会产生 10 次 StateFlow 发射；
     * 而 MonitorState 含 9 个 List 字段（Compose 视为 unstable），监控页整棵子树因此不可跳过，
     * 每次发射都要重跑「今日峰值」的 O(8×720) 派生，这是进入监控页卡顿的主因。
     * 合并后一次加载只发射 2 次（开始 isLoading=true、结束携带全部序列）。
     * 另外 `ensureSettingsLoaded()` 会读取 SharedPreferences 并反序列化 JSON，
     * 不得在调用方（LaunchedEffect 的同步段）的主线程上执行，本方法将其放入 IO 线程。
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
                    // silent=true 时保留原 isLoading（不置 true），无感刷新不闪 loading
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

    // ── Monitor 懒加载 ─────────────────────────────────────────────────────────
    //
    // 背景：进入监控页时一次性拉满 8 类指标。图表 Tab 的 4 个区块位于 LazyColumn 中，
    // 首屏通常只可见 1~2 个，其余请求没有收益，且全部集中在切页那一刻（最易掉帧）。
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
        // ConcurrentModificationException。
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
     * 不采用「切区间即立刻全量重拉 8 类」的做法：拉取交由当时真正可见的区块执行
     * （其 `LaunchedEffect` 以 range 为 key），未露面的区块在露面时再拉。
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
                // 诊断日志：区分「已记账但无数据」与「尚未请求」两种「暂无数据」状态。
                // pending 为空而界面无数据，说明记账与取数脱节，需靠该日志定位。
                DebugLog.i("ChartDbg") {
                    "loadMonitorTypes req=${types.joinToString(",")} pending=${pending.joinToString(",")} " +
                        "loaded=${loadedMonitorTypes.joinToString(",")} force=$force " +
                        "collectEnabled=${settings.collectEnabled} rangeKey=$loadedMonitorRangeKey"
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
                    // 区间已经换过：这批结果属于旧窗口，写入后即为过期数据。
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
                    // 图表数据同样受转场闸门管辖。
                    // 监控页图表是全 App 最重的子树（8 类序列 + 绘制），这批数据在拖动过程中
                    // 落地即触发一次整页重组；而 UiFrameGate 原本只覆盖「WS 高频指标」路径。
                    // 此处将同一约束应用到图表路径：转场中先暂存，落定后立刻写。
                    if (UiFrameGate.isBusy) UiFrameGate.awaitIdle()
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
                    loaded.forEach { (type, resp) ->
                        if (resp == null) loadedMonitorTypes.remove(type)
                        DebugLog.i("ChartDbg") {
                            "fetch $type ok=${resp != null} points=${resp?.points?.size ?: -1} bucketMs=${resp?.bucket_ms ?: -1} bucketSec=${resp?.bucket_seconds ?: -1} count=${resp?.count ?: -1} raw=${resp?.raw_count ?: -1}"
                        }
                    }
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
     * 这是懒加载在刷新侧的对应实现：不可见的图表不应在后台持续刷新。
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
     * Custom 且含实时尾窗时的增量刷新。
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
                // 懒加载：只刷此刻可见且已有数据的类型。
                // 无条件取 enabledMonitorTypes(settings)（8 类全拉）会抵消懒加载：默认区间
                // today() 恒为实时 Custom，即最常见的路径上每轮轮询都会拉取全部指标。
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
     * 注意必须向上取整：向下取整时短区间会算出 0，并被钳制到 1s。
     */
    private fun estimateBucketMs(range: MonitorTimeRange): Long {
        val raw = range.spanMs / MonitorTimeRange.MAX_POINTS
        return if (raw <= 1000L) 1000L else (raw + 999L) / 1000L * 1000L
    }

    /**
     * 惰性加载监控设置：首次读取持久化设置写入 [MonitorState.settings]。
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
     * 保存监控设置：持久化 + 更新 [MonitorState.settings] + 下发到 core。
     * - 持久化失败静默（MonitorPreferences 内部已兜底）。
     * - **只在 `collectEnabled` 真正变化时**才下发 `POST /api/monitor/control`：
     *   该端点会写入持久化真源 `AppSettings.backgroundServiceEnabled`。若每次保存
     *   任意无关设置（时长/间隔/指标开关…）都无条件重发本地默认值 `true`，会把用户在设置页
     *   或 web 端显式停掉的后台服务静默改回「已启动」。
     * - 个性化 7 项同理只在真正变化时 `PUT /api/monitor/preferences`，并以服务端回显覆盖本地。
     *
     * ## 写操作反馈
     * 本界面为**即改即存**，没有保存按钮可以停留在「保存中」；若请求发出后不处理结果，
     * 用户唯一能观察到的是「改过的设置过一会儿又变回去了」（下次进页面回读被服务端值覆盖）。
     *
     * 因此：成功时调用 [emitWriteNotice]（指明是哪一项），失败时写 `monitorState.errorMessage`
     *（已接入 `MainViewModel.rawGlobalError`，会显示红色 Toast）并**回读服务端真值**，
     * 使界面上已被拨动的开关/滑杆立即回到设备真值，而不是停留在未落地的状态。
     *
     * 两个请求必须在**同一个协程**中串行发送：一次保存最多只应产生一条提示；
     * 拆成两个协程时「同时修改总开关与采集间隔」会产生两条提示，失败时还会写两次
     * errorMessage 互相覆盖。
     */
    fun setMonitorSettings(settings: MonitorSettings) {
        val previous = _monitorState.value.settings
        monitorPreferences.save(settings)
        _monitorState.update { it.copy(settings = settings) }

        val collectChanged = settings.collectEnabled != previous.collectEnabled
        val payload = settings.toRemotePayload()
        val prefsChanged = payload != previous.toRemotePayload()
        if (!collectChanged && !prefsChanged) return

        // 文案在发请求之前计算：内容是「本次改了什么」，与请求结果无关
        val label = monitorSettingsChangeLabel(previous, settings)

        scope.launch {
            if (collectChanged) {
                val ok = runCatching { api.setMonitorControl(mapOf("enabled" to settings.collectEnabled)) }
                if (ok.isFailure) {
                    monitorSettingsFailed("后台采集", ok.exceptionOrNull())
                    return@launch
                }
            }
            if (prefsChanged) {
                val result = runCatching { api.updateMonitorPreferences(payload) }
                val echoed = result.getOrNull()?.takeIf { it.success }?.preferences
                if (echoed == null) {
                    monitorSettingsFailed(label, result.exceptionOrNull())
                    return@launch
                }
                // 以服务端回显为准：core 会做取值域校验，若本地传了越界值，服务端返回的才是真实生效值。
                applyRemotePreferences(echoed)
            }
            // 开关类提示「已开启/已关闭」，数值类提示「已保存」：对开关而言「已保存」无法说明
            // 当前是开还是关，而这是用户本次操作的核心信息。
            emitWriteNotice(
                if (collectChanged) {
                    if (settings.collectEnabled) "后台采集已开启" else "后台采集已关闭"
                } else {
                    "$label 已保存"
                }
            )
        }
    }

    /**
     * 监控设置下发失败的统一收尾。
     *
     * 回读放在写 errorMessage **之后**：`refreshMonitorSettingsFromBackend` 在
     * 「两个请求都失败」时直接 return（沿用本地缓存），不会覆盖刚写入的错误文案；
     * 而它一旦成功，`settings` 变化会让界面上的控件自己回到设备真值。
     */
    private suspend fun monitorSettingsFailed(label: String, cause: Throwable?) {
        val reason = cause?.message ?: "设备未接受这次修改"
        DebugLog.w("Monitor", "监控设置下发失败（$label）: $reason", cause)
        _monitorState.update { it.copy(errorMessage = "$label 保存失败：$reason") }
        // 本地缓存已做乐观更新，必须拉回 core 真值，否则界面会停留在未落地的值上
        refreshMonitorSettingsFromBackend()
    }

    /**
     * 这次改动最该被报出来的那一项（名词短语，调用方自己拼"已保存"之类的尾巴）。
     *
     * 需要挑出一项而非笼统报「监控设置」：四个二级页（采集 / 图表 / 调度 / 导出）共用
     * 本方法，每页有多个滑杆，泛化文案不提供有效信息。UI 一次交互只改一个字段，
     * 因此「第一个不相等的字段」即用户本次修改的字段；若出现多字段同时变化（指标开关弹窗的
     * 批量勾选），按声明顺序取第一个，仍指向用户正在查看的那一组。
     */
    private fun monitorSettingsChangeLabel(prev: MonitorSettings, next: MonitorSettings): String = when {
        prev.collectEnabled != next.collectEnabled -> "后台采集"
        prev.enabledTypes != next.enabledTypes -> "采集指标"
        prev.defaultHours != next.defaultHours -> "默认时间范围"
        prev.refreshIntervalSec != next.refreshIntervalSec -> "自动刷新间隔"
        prev.fixedYAxis != next.fixedYAxis -> "Y 轴固定"
        prev.fillAlpha != next.fillAlpha -> "图表填充透明度"
        prev.exportZip != next.exportZip -> "导出方式"
        prev.retentionDays != next.retentionDays -> "历史保留天数"
        prev.flushIntervalSec != next.flushIntervalSec -> "采集刷写间隔"
        prev.alertScanSec != next.alertScanSec -> "告警扫描间隔"
        prev.idleIntervalSec != next.idleIntervalSec -> "空闲采集间隔"
        prev.thermalWarnC != next.thermalWarnC -> "温控预警阈值"
        prev.thermalCriticalC != next.thermalCriticalC -> "温控熔断阈值"
        prev.thermalPauseSec != next.thermalPauseSec -> "熔断暂停时长"
        prev.trafficLimitCheckSec != next.trafficLimitCheckSec -> "套餐限额检查间隔"
        prev.deviceEventCheckSec != next.deviceEventCheckSec -> "设备接入检查间隔"
        else -> "监控设置"
    }

    /**
     * 从后端回读监控相关真源并覆盖本地缓存。
     *
     * 两项回读**必须在同一个协程里串行完成**：`collectEnabled` 来自 `/api/service/status`，
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

    /** 当前启用的指标 apiKey 列表（按单指标开关过滤） */    private fun enabledMonitorTypes(settings: MonitorSettings): List<String> =
        MonitorMetricType.entries.map { it.apiKey }.filter { it in settings.enabledTypes }

    /** 按当前 selectedRange 强制重拉（下拉刷新 / 返回刷新入口） */
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
     * 按 t 去重的前提是两批点同网格（见 [estimateBucketMs]）。即便如此仍设硬上限：
     * 页面长时间开启时，窗口右端持续后移，序列会单向变长（「今天」这一档窗口起点不动，
     * 旧点不会被 windowStartMs 裁掉）；超过 MAX_POINTS 的 2 倍时只保留最新的一批，
     * 图表可视精度用不到更多点，多保留只会增加几何构建开销。
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
            // summary 失效事件（invalidateAll 广播）复用既有 summary 拉取
            changedType == "summary" -> refreshDashboard()
            // WS 重连 sentinel：全量补拉一轮（非 force，走既有的新鲜度/轮询闸门，
            // 短暂断线时多数请求会被挡住）
            changedType == WsDataTopic.RECONNECTED -> refreshDashboard()
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
    // 本模块不提供版本比较：判据为 core 的 has_update。App 侧版本比对只存在两处：
    // core 的 UpdateManager.compareVersions（core 自身/清单）与 ToolsModule.compareVersions
    // （App 自身/清单），两者解析规则一致。
}
