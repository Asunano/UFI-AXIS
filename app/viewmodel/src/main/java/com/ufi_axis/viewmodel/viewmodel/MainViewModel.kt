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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 全局错误的来源。决定重试动作与清除动作分派到哪个 module（见 [MainViewModel.retryGlobalError]）。
 */
enum class GlobalErrorSource { DASHBOARD, NETWORK, MONITOR, TOOLS, SERVICE }

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