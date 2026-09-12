package com.ufi_axis.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.BatteryInfo
import com.ufi_axis.data.model.CpuInfo
import com.ufi_axis.data.model.MemoryInfo
import com.ufi_axis.data.model.SignalInfo
import com.ufi_axis.data.model.SmsContactListResponse
import com.ufi_axis.data.model.TrafficRealtime
import com.ufi_axis.data.repository.WebSocketRepository
import com.ufi_axis.data.repository.ConnectionState
import com.ufi_axis.data.notification.GuardScheduler
import com.ufi_axis.util.AppJson
import com.ufi_axis.util.BackgroundManager
import kotlinx.serialization.json.decodeFromJsonElement
import com.ufi_axis.util.DebugLog
import com.ufi_axis.util.NetworkMonitor
import com.ufi_axis.viewmodel.module.*
import com.ufi_axis.viewmodel.persistence.FileShortcutRepository
import com.ufi_axis.viewmodel.repository.AlertPrefsRepository
import com.ufi_axis.viewmodel.state.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 全局错误的来源。决定重试动作与清除动作分派到哪个 module（见 [MainViewModel.retryGlobalError]）。
 */
enum class GlobalErrorSource { DASHBOARD, NETWORK, MONITOR, TOOLS, SERVICE }

/** 后端健康检查周期（毫秒）：周期访问 /health 维护在线状态。 */
private const val HEALTH_CHECK_INTERVAL_MS = 30_000L

/** 后端掉线提示去抖窗口（毫秒）：同一句错误在此窗口内不重复复查 /health。 */
private const val BACKEND_DOWN_DEDUPE_MS = 10_000L

/**
 * 全局错误：一条待展示的错误文案 + 它来自哪里。
 *
 * 刻意**不带** retry lambda —— 见 [MainViewModel.globalError] 的说明。
 * 也不带 `retryable`：2026-09-05 第二版把展示通道换成 Toast 之后，全局层不再提供重试按钮
 *（仪表盘/网络/监控本来就有轮询 + 回前台重取，tools/service 从来就没有通用重试语义）。
 */
data class GlobalError(
    val message: String,
    val source: GlobalErrorSource
)

/**
 * 后端掉线提示对话框状态。
 *
 * 由「数据加载出错 → 复查 /health 也失败」触发：此时基本可判定不是偶发网络抖动，而是后端
 * 服务挂了 / 地址不可达。UI 据此弹出一个带「重试 / 进入服务器设置」的对话框，比笼统的
 * 「加载失败」Toast 更有 actionable 的指引。
 *
 * @param errorMessage        最初触发此对话框的那条数据加载错误文案（主动探活路径为 null，无前置数据错误）
 * @param healthErrorMessage 复查 /health 失败的细节（null = 未取到）
 */
data class BackendDownDialogState(
    val errorMessage: String?,
    val healthErrorMessage: String?
)

class MainViewModel(
    private val api: UfiAxisApi,
    private val webSocketRepository: WebSocketRepository,
    private val networkMonitor: NetworkMonitor,
    private val appContext: Context
) : ViewModel() {

    // ── Modules ──
    // 首屏仅 Dashboard 立即初始化；其余非首屏模块改为惰性加载（by lazy），
    // 延后到对应页面访问 viewModel.xxx 或 LaunchedEffect 触发，降低冷启动负担。
    // 注：network/tools 仍会在 MainViewModel 构造期因跨模块事件收集被提前初始化，
    // 但 files/apps/downloads 等重型模块已真正推迟到首次访问。
    // P2 多端同步：告警配置仓库（core 唯一真源镜像；连接即拉取，绝不推送默认）
    val alertPrefs = AlertPrefsRepository()

    val dashboard = DashboardModule(api, webSocketRepository, networkMonitor, appContext, viewModelScope, alertPrefs)

    /**
     * 后端健康检查（周期探活 + 出错时即时复查）。[viewModelScope] 内常驻，供 UI 判断后端是否在线、
     * 以及数据加载失败时是否因后端挂了。见 [HealthModule]。
     */
    val health = HealthModule(api, viewModelScope)

    // 跨模块错误事件汇聚点（eager 创建、开销极小）：NetworkModule / ToolsModule 构造时
    // 接收该 sink，其 events 转发到此。collectCrossModuleEvents 仅订阅本 sink，
    // 不会在构造期触发 network / tools 的 by lazy 求值（修复冷启动 eager 加载，风险 #6）。
    private val crossModuleEventSink = MutableSharedFlow<UiEvent>(extraBufferCapacity = 64)

    val network by lazy { NetworkModule(api, appContext, viewModelScope, crossModuleEventSink) }
    val tools by lazy { ToolsModule(api, appContext, viewModelScope, crossModuleEventSink, alertPrefs) }
    val files by lazy { FileManagerModule(api, appContext, FileShortcutRepository(appContext), viewModelScope) }
    val apps by lazy { AppManagerModule(api, appContext, viewModelScope) }
    val downloads by lazy { DownloadModule(appContext, viewModelScope) }
    val tunnel by lazy { TunnelModule(appContext, viewModelScope) }
    val backup by lazy { BackupModule(api) }

    /**
     * 更新提示的唯一状态槽（2026-09-06）。
     *
     * 只聚合 [tools] 的 App 自更新状态与 [dashboard] 的 core 版本状态并持有唯一的 visible，
     * 自身不发请求。有它之后「App 有新版」和「Core 有新版」是同一个弹窗里的两块内容，
     * 不再是两个各自 remember 布尔开关的独立平台 Window（原来会叠加两层 scrim）。
     *
     * by lazy：首帧就会被 MainActivity 订阅，等于随首屏初始化；写成 eager 只会把
     * tools 的构造提前到 MainViewModel 构造期，没有收益。
     */
    val updatePrompt by lazy {
        UpdatePromptModule(
            scope = viewModelScope,
            appUpdate = tools.frontendUpdateState,
            coreUpdate = dashboard.updateState,
            coreInstallStatus = tools.updateDeviceState
        )
    }

    val backgroundManager = BackgroundManager(appContext)

    // 后台守护调度器（T05 Part 3.4 S6）：WorkManager 周期任务封装，UI 经此读写开关/间隔/状态
    val backgroundGuard by lazy { GuardScheduler(appContext, api) }

    // ── StateFlows (backward-compatible) ──
    val dashboardState: StateFlow<DashboardState> get() = dashboard.dashboardState
    // ── 按字段派生的 StateFlow（各 screen 按需订阅，避免全量重组） ──
    val dashboardCpuInfo: StateFlow<CpuInfo?> get() = dashboard.cpuInfoState
    val dashboardMemoryInfo: StateFlow<MemoryInfo?> get() = dashboard.memoryInfoState
    val dashboardTrafficRealtime: StateFlow<TrafficRealtime?> get() = dashboard.trafficRealtimeState
    val dashboardSignalInfo: StateFlow<SignalInfo?> get() = dashboard.signalInfoState
    val dashboardBatteryInfo: StateFlow<BatteryInfo?> get() = dashboard.batteryInfoState
    val dashboardIsLoading: StateFlow<Boolean> get() = dashboard.isLoadingState
    val networkState: StateFlow<NetworkState> get() = network.networkState
    /** 后端健康状态（周期探活 + 出错复查），UI 可据此展示"后端是否在线"。 */
    val healthState: StateFlow<HealthState> get() = health.healthState

    // ── 实时连接状态转发（供 UI 展示 WS 连接状态，避免各屏重复订阅 repository，T01/UID-006） ──
    val wsConnectionState: StateFlow<ConnectionState>
        get() = webSocketRepository.connectionState

    // ── 测速取消透传（UID-010, Wave 2）：转发 NetworkModule.cancelSpeedTest() ──
    fun cancelSpeedTest() = network.cancelSpeedTest()
    val toolsState: StateFlow<ToolsState> get() = tools.toolsState
    val tunnelState: StateFlow<TunnelState> get() = tunnel.state
    val alertsState: StateFlow<AlertsState> get() = tools.alertsState
    val updateState: StateFlow<UpdateState> get() = dashboard.updateState
    /** 统一更新提示（App 项 + Core 项聚合）；MainActivity 自动提示与关于页手动检查共用此槽。 */
    val updatePromptState: StateFlow<UpdatePromptState> get() = updatePrompt.promptState
    val tasksState: StateFlow<TasksState> get() = tools.tasksState
    val smsForwardState: StateFlow<SmsForwardState> get() = tools.smsForwardState
    /** 通用 Webhook 渠道（`/api/notify/webhook`）：推送渠道页与 Webhook 配置页共读这一份。 */
    val webhookState: StateFlow<WebhookState> get() = tools.webhookState

    /** 本机短信回发渠道（`/api/notify/sms`）：推送渠道页与本机短信配置页共读这一份。 */
    val localSmsState: StateFlow<LocalSmsState> get() = tools.localSmsState
    val appManageState: StateFlow<AppManageState> get() = apps.state
    val deviceSettingsState: StateFlow<DeviceSettingsState> get() = network.deviceSettingsState
    val serviceState: StateFlow<ServiceControlState> get() = network.serviceState
    val speedTestState: StateFlow<SpeedTestState> get() = network.speedTestState
    val fileManagerState: StateFlow<FileManagerState> get() = files.state
    val debugLogState: StateFlow<DebugLogState> get() = tools.debugLogState
    val diagnoseState: StateFlow<DiagnoseState> get() = tools.diagnoseState
    val trafficManagementState: StateFlow<TrafficManagementState> get() = tools.trafficManagementState
    val monitorState: StateFlow<MonitorState> get() = dashboard.monitorState
    val downloadState: StateFlow<DownloadState> get() = downloads.state

    // ── P2 多端同步：连接恢复时拉取最新告警配置镜像（绝不推送默认，防 ab 设备回弹） ──
    fun refreshAlertConfig() {
        viewModelScope.launch { alertPrefs.refreshFromCore(api) }
    }

    // ── Init & Cleanup ──
    init {
        // Start modules
        dashboard.init()

        // 后端健康检查：周期探活（30s）+ 数据出错时复查（见 collectBackendDownSignal）
        health.startPeriodicCheck(HEALTH_CHECK_INTERVAL_MS)
        collectBackendDownSignal()
        // 主动探活：healthState 一旦从「可达」转「不可达」立即弹窗，无需依赖页面切换或数据错误
        //（globalError 是 distinctUntilChanged 的合并流，停留在同一页时错误文案不变、不再发新值，
        // 旧实现因此只能切页才弹）。两条路径共享 lastBackendDownAt 去抖，互不刷屏。
        collectHealthStatus()

        // 收集跨模块事件，统一分发
        collectCrossModuleEvents()

        // data_changed 事件 → 精准增量刷新
        collectDataChangedEvents()

        // sms_contacts WS 推送 → 工具模块联系人列表
        collectSmsContactsFromWs()

        // update WS 推送（含订阅时服务端下发的快照）→ 设备更新状态（手动推送 APK 成功/失败）
        collectUpdateFromWs()
    }

    /** 收集跨模块 UiEvent，统一分发到目标 Module。
     * 订阅 eager 创建的汇聚 sink（crossModuleEventSink），不再访问 network / tools 的
     * events 流，因此不会在构建期触发这两个模块的 by lazy 求值（修复冷启动 eager 加载，风险 #6）。
     * NetworkModule / ToolsModule 在构造时接收同一 sink，并把自己的 _events 转发到该 sink。 */
    private fun collectCrossModuleEvents() {
        viewModelScope.launch {
            crossModuleEventSink.collect { event ->
                when (event) {
                    is UiEvent.ShowDashboardError -> dashboard.setDashboardError(event.message)
                    is UiEvent.ShowNetworkError -> network.setError(event.message)
                    // 单条事件删除成功(AlertDeleted)由 MonitorScreen 直接收集 dashboard.events 处理，
                    // 不经过跨模块汇聚 sink，分发层忽略即可。
                    is UiEvent.AlertDeleted -> { }
                }
            }
        }
    }

    // ── Smart Refresh: 订阅 data_changed 事件，精准增量刷新 ──
    private fun collectDataChangedEvents() {
        viewModelScope.launch {
            webSocketRepository.dataChanged.collect { changedType ->
                when {
                    // Dashboard: 设备信息类变更
                    changedType.startsWith("device:") -> {
                        dashboard.smartRefresh(changedType)
                        network.smartRefresh(changedType)
                        tools.smartRefresh(changedType)
                    }
                    // Network: WiFi / 网络类变更
                    changedType.startsWith("wifi:") ||
                    changedType.startsWith("network:") -> {
                        network.smartRefresh(changedType)
                    }
                }
            }
        }
    }

    /** sms_contacts WS 推送 → 直接更新联系人列表（免 HTTP 请求） */
    private fun collectSmsContactsFromWs() {
        // Dispatchers.Default：JSON 反序列化不占主线程（2026-08-31 转场掉帧治理）。
        viewModelScope.launch(Dispatchers.Default) {
            webSocketRepository.messages.collect { message ->
                if (message.type == "sms_contacts") {
                    try {
                        val data = message.data ?: return@collect
                        val contacts = AppJson.decodeFromJsonElement<SmsContactListResponse>(data)
                        tools.updateSmsContactsFromWs(contacts.contacts)
                    } catch (e: Exception) {
                        DebugLog.w("MainViewModel", "Failed to parse sms_contacts from WebSocket: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * update WS 推送 → 设备更新状态。
     * 后端 UpdateManager 在恢复（新 Core 启动读 RESULT 日志）后，会通过 WS 下发当前更新结果；
     * 前端订阅 "update" 主题（见 WebSocketRepository）后，连接建立/恢复时即可收到快照，
     * 解决「手动推送 APK 后未收到更新成功推送」的问题。
     */
    private fun collectUpdateFromWs() {
        viewModelScope.launch(Dispatchers.Default) {
            webSocketRepository.messages.collect { message ->
                if (message.type == "update") {
                    tools.onUpdateWsEvent(message.data)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        dashboard.onCleared()
    }

    // ── Common ──
    fun clearError() {
        dashboard.clearError()
        network.clearError()
        tools.clearError()
    }

    // ══════════════════════════════════════════════════════════════════════
    // 全局错误（2026-09-05）
    // ══════════════════════════════════════════════════════════════════════
    //
    // 在此之前错误提示是**页面级**的：每个页面自己 `state.errorMessage?.let { UfiErrorBanner(...) }`。
    // 后果有三：
    // 1. 「我的」页一条都没有 —— 而 `ServiceControlState.errorMessage`（启停/重启服务、关机、
    //    重启设备四个危险操作）一直在写、全仓零读取，失败时界面完全没反馈；
    // 2. 「工具」页自己不发请求、产不出错误，它那条横幅显示的其实是 tools 域**别的页面**的错误
    //    （ToolsState 是整个 tools 域共享的一份），谁在哪报的错说不清；
    // 3. 同一个 errorMessage 要在 N 个页面各写一遍渲染代码，漏一个页面就静默失败。
    //
    // 现在收敛成一条流 + 一个 Activity 级宿主（见 MainActivity 的全局错误浮层）：
    // 五个来源按严重度取第一个非空，浮层自动收起时回调 [dismissGlobalError] 把该来源清干净
    //（否则错误会一直挂在 state 上，切回页面又弹一次陈旧错误）。
    //
    // 为什么 [GlobalError] 里不放 retry lambda：那样每次 combine 都产生新实例、equals 恒为 false，
    // 宿主的 LaunchedEffect 会被每一轮轮询重启。改成只带 [GlobalErrorSource] 枚举，
    // 重试动作由 [retryGlobalError] 按来源分派 —— 值语义干净，宿主可以安心用它当 key。
    val globalError: StateFlow<GlobalError?> = combine(
        dashboard.dashboardState,
        network.networkState,
        dashboard.monitorState,
        tools.toolsState,
        network.serviceState
    ) { dash, net, monitor, toolsState, service ->
        // 顺序 = 严重度：设备级操作失败 > 页面数据拉取失败。
        val service0 = service.errorMessage
        val dash0 = dash.errorMessage
        val net0 = net.errorMessage
        val monitor0 = monitor.errorMessage
        val tools0 = toolsState.errorMessage
        when {
            service0 != null -> GlobalError(service0, GlobalErrorSource.SERVICE)
            dash0 != null -> GlobalError(dash0, GlobalErrorSource.DASHBOARD)
            net0 != null -> GlobalError(net0, GlobalErrorSource.NETWORK)
            monitor0 != null -> GlobalError(monitor0, GlobalErrorSource.MONITOR)
            tools0 != null -> GlobalError(tools0, GlobalErrorSource.TOOLS)
            else -> null
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ── 后端掉线提示（2026-09-12）──
    //
    // 触发链：任意数据加载出错（globalError 非空）→ 即时复查 /health。
    // 若探活也失败，说明不是偶发抖动、而是后端服务挂了 / 地址不可达，弹出带
    // 「重试 / 进入服务器设置」的对话框；否则只走常规错误 Toast（后端其实在线，错误另有原因）。
    //
    // 去抖：对话框已弹出时不再重复触发；同一句错误 10s 内不重复复查，避免轮询失败把
    // /health 打成一波随抖动走的轮询。
    private val _backendDownDialog = MutableStateFlow<BackendDownDialogState?>(null)
    val backendDownDialogState: StateFlow<BackendDownDialogState?> = _backendDownDialog.asStateFlow()

    /** 后端掉线弹窗去抖时间戳：两条触发路径（数据错误 / 主动探活）共用，避免互相刷屏。 */
    private var lastBackendDownAt = 0L

    /** 关闭后端掉线提示（用户点了重试 / 服务器设置 / 返回键 / 点遮罩）。 */
    fun dismissBackendDownDialog() {
        _backendDownDialog.value = null
    }

    /**
     * 「重试」：关闭对话框 + 清掉各模块的错误文案（下次轮询会重新拉取，若后端已恢复即正常），
     * 并立刻复查一次健康状态——若仍不可达，下一次数据加载出错会再次弹窗；若已恢复则不再弹。
     */
    fun retryFromBackendDown() {
        _backendDownDialog.value = null
        clearError()
        viewModelScope.launch { health.checkHealthNow() }
    }

    /**
     * 订阅 globalError：出现新错误且对话框未弹出时，复查后端健康，失败则弹窗。
     *
     * ⚠️ 健壮性（2026-09-12 修复）：收集协程必须「长生不老」。
     * 旧实现用 try/catch 把异常（含 viewModelScope 取消竞态导致的 NPE）吞掉后协程直接结束——
     * 一旦在运行期（约 19s 的取消竞态）崩一次，之后所有后端掉线都不再弹窗，只剩 Toast 照常，
     * 表现为「Toast 提示无法连接后端、对话框却不出现」。
     * 现改用 while(isActive) 包住 collect：单次 collect 抛异常仅记日志并 delay 后重新订阅 globalError，
     * 探测器始终在线；仅 CancellationException 原样上抛以正常结束协程。
     * 顺带兜住初始化顺序潜在问题：若首轮 globalError 尚未就绪导致 collect 抛 NPE，重试时它已初始化。
     */
    private fun collectBackendDownSignal() {
        viewModelScope.launch {
            var lastHandledMessage: String? = null
            while (isActive) {
                try {
                    globalError.collect { err ->
                        if (err == null) {
                            lastHandledMessage = null
                            return@collect
                        }
                        if (_backendDownDialog.value != null) return@collect
                        val now = System.currentTimeMillis()
                        if (err.message == lastHandledMessage && now - lastBackendDownAt < BACKEND_DOWN_DEDUPE_MS) {
                            return@collect
                        }
                        lastHandledMessage = err.message
                        lastBackendDownAt = now
                        val reachable = runCatching { health.checkHealthNow() }.getOrDefault(false)
                        if (!reachable) {
                            _backendDownDialog.value = BackendDownDialogState(
                                errorMessage = err.message,
                                healthErrorMessage = health.healthState.value.errorMessage
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.w(
                        "MainViewModel",
                        "collectBackendDownSignal 收集异常（已忽略，1s 后重试）: ${e.message}"
                    )
                    delay(1000)
                }
            }
        }
    }

    /**
     * 主动探活驱动的后端掉线提示（2026-09-12 补充）。
     *
     * [collectBackendDownSignal] 依赖 globalError —— 但 globalError 是 distinctUntilChanged 的合并流，
     * 后端掉线后若停留在同一页面、数据不再重新拉取，errorMessage 不再变化、也就不再发新值，
     * 表现为「只有切页才弹窗」。这里改为直接订阅 [HealthModule.healthState]：
     * 周期探活一旦发现后端从「可达」转成「不可达」，立即主动弹窗，无需依赖页面切换或数据错误。
     *
     * 去抖：用 [lastBackendDownAt] 与弹窗已弹出判定做闸门，避免两次连续探活都失败就刷屏；
     * UNKNOWN 状态不翻转 [wasDown]，避免探活间隙的短暂 UNKNOWN 造成误判。
     */
    private fun collectHealthStatus() {
        viewModelScope.launch {
            var wasDown = false
            while (isActive) {
                try {
                    health.healthState.collect { state ->
                        when (state.status) {
                            HealthStatus.UNREACHABLE -> {
                                if (!wasDown) {
                                    // 新一次掉线：主动弹窗（去抖，避免连续两次探活失败刷屏）。
                                    val now = System.currentTimeMillis()
                                    if (_backendDownDialog.value == null &&
                                        now - lastBackendDownAt > BACKEND_DOWN_DEDUPE_MS
                                    ) {
                                        lastBackendDownAt = now
                                        _backendDownDialog.value = BackendDownDialogState(
                                            errorMessage = null,
                                            healthErrorMessage = state.errorMessage
                                        )
                                    }
                                }
                                wasDown = true
                            }
                            HealthStatus.HEALTHY -> wasDown = false
                            HealthStatus.UNKNOWN -> { /* 保持 wasDown 不变，避免短暂 UNKNOWN 误判 */ }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.w(
                        "MainViewModel",
                        "collectHealthStatus 收集异常（已忽略，1s 后重试）: ${e.message}"
                    )
                    delay(1000)
                }
            }
        }
    }

    /** 展示完毕后清掉该来源的错误，避免陈旧错误在切页 / 重组时被当成新错误再弹一次。 */
    fun dismissGlobalError(source: GlobalErrorSource) {
        when (source) {
            GlobalErrorSource.DASHBOARD -> dashboard.clearError()
            GlobalErrorSource.NETWORK -> network.clearError()
            GlobalErrorSource.MONITOR -> dashboard.clearMonitorMessage()
            GlobalErrorSource.TOOLS -> tools.clearError()
            GlobalErrorSource.SERVICE -> network.clearServiceError()
        }
    }

    // ==================== Factory ====================
    companion object {
        fun provideFactory(
            api: UfiAxisApi,
            webSocketRepository: WebSocketRepository,
            networkMonitor: NetworkMonitor,
            appContext: Context
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(
                        api,
                        webSocketRepository,
                        networkMonitor,
                        appContext
                    ) as T
                }
            }
        }
    }
}