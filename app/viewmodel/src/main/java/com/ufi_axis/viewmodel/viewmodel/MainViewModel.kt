package com.ufi_axis.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.update.SharedPreferencesCoreUpdatePersistence
import com.ufi_axis.data.model.BatteryInfo
import com.ufi_axis.data.model.CpuInfo
import com.ufi_axis.data.model.MemoryInfo
import com.ufi_axis.data.model.SignalInfo
import com.ufi_axis.data.model.SmsContactListResponse
import com.ufi_axis.data.model.TrafficRealtime
import com.ufi_axis.data.repository.WebSocketRepository
import com.ufi_axis.data.repository.ConnectionState
import com.ufi_axis.data.notification.GuardScheduler
import com.ufi_axis_core.contract.WsDataTopic
import com.ufi_axis.util.AppJson
import com.ufi_axis.util.AppPreferences
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
import kotlinx.coroutines.flow.Flow
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

/** 后端健康检查周期（毫秒）：周期访问 /health 维护在线状态（仅前台）。 */
private const val HEALTH_CHECK_INTERVAL_MS = 30_000L

/**
 * 后端掉线提示去抖窗口（毫秒）。
 *
 * 2026-09：10s → 45s。切后台再回前台时网络栈可能短暂抖动，10s 去抖挡不住
 * 「回前台 → 业务失败 → 复查 /health」在 10s 内连着弹两次。
 */
private const val BACKEND_DOWN_DEDUPE_MS = 45_000L

/**
 * Core 更新期间豁免「后端掉线」弹窗的最长时间（毫秒）。
 *
 * 在线更新（`triggerDeviceUpdate`）与本地 APK 推送安装都会让 Core force-stop/重启，
 * 8088 断联约 1 分钟属预期。此窗口内业务失败 + /health 失败只当「更新中」，
 * 不弹掉线对话框（更新进度 UI 已有「设备重启中…」）。
 *
 * 超时后恢复弹窗：与 ToolsModule 更新轮询失联阈值（约 5 分钟）对齐，
 * 避免更新卡死时用户永远看不到「后端不可达」。
 */
private const val CORE_UPDATE_EXEMPT_TIMEOUT_MS = 5 * 60_000L

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

/**
 * 全局连接态。**app 里判断「能不能用」只看这一个枚举**。
 *
 * 语义分工（这是本类型存在的全部理由 —— 三个旧信号各说各话正是"连不上却不提示"的根因）：
 * - [CHECKING]：还没探出结论（冷启动首屏）。**不要显示任何错误提示**，否则每次开 app 都先红一下。
 * - [ONLINE]：`/health` 探通。这是唯一能证明 core 可达的证据。
 * - [NO_LOCAL_NETWORK]：手机压根没连网络（`NetworkMonitor.hasNetwork` 为 false）。
 * - [NOT_CONFIGURED]：还没配过设备地址（`serverIp` 为空）。
 * - [UNREACHABLE]：手机有网、地址也配了，但 `/health` 确证探不通 —— 设备离线 / core 没跑 / 不在同一网。
 *
 * 注意后三者都是**已确证不可达之后**的归因，不是独立的判定条件。
 */
enum class ConnectivityStatus {
    CHECKING, ONLINE, NO_LOCAL_NETWORK, NOT_CONFIGURED, UNREACHABLE
}

/**
 * @param title  一句话病因（横幅主文案）；[ConnectivityStatus.ONLINE]/[ConnectivityStatus.CHECKING] 时为 null
 * @param detail 下一步动作的指引（横幅副文案）
 */
data class ConnectivityUiState(
    val status: ConnectivityStatus,
    val title: String? = null,
    val detail: String? = null
) {
    /** 是否应该显示离线横幅。CHECKING 不显示 —— 没结论就别吓用户。 */
    val showBanner: Boolean
        get() = status != ConnectivityStatus.ONLINE && status != ConnectivityStatus.CHECKING

    /** 是否是「配都没配」——UI 可据此把按钮从「重试」换成「去配对」。 */
    val needsSetup: Boolean get() = status == ConnectivityStatus.NOT_CONFIGURED
}

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

    /**
     * Core 自更新「进行中」标记持久化（[com.ufi_axis.data.update.CoreUpdatePersistence]）。
     * 供 [tools] 写入（用户确认更新 Core 时）与 [updatePrompt] 读取（冷启动接管进度显示）。
     */
    private val coreUpdatePersistence = SharedPreferencesCoreUpdatePersistence(appContext)

    /** 只用来读「设备地址配过没有」（[connectivity] 的归因分支）。构造一次，别在 combine 里反复 new。 */
    private val appPrefs = AppPreferences(appContext)

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
    val tools by lazy { ToolsModule(api, appContext, viewModelScope, crossModuleEventSink, alertPrefs, coreUpdatePersistence) }
    val files by lazy { FileManagerModule(api, appContext, FileShortcutRepository(appContext), viewModelScope) }
    // 外部存储源（FTP / WebDAV）配置：只管 /api/storage/sources 这组 CRUD，
    // 源里的列目录与读写仍由 files 走 remote: 前缀路径完成（core 侧派发）。
    // by lazy：绝大多数用户一个源都不配，不该在 ViewModel 构造时就占一份 state。
    val storageSources by lazy { StorageSourceModule(api, viewModelScope) }
    // 媒体中心（工具 → 媒体中心，2026-09-16）：列设备端的视频 / 音乐 / 图片。
    // 数据来自 core 的 /api/media（它查系统媒体库），播放仍走 /api/files/stream。
    val media by lazy { MediaModule(api, appContext, viewModelScope) }
    val apps by lazy { AppManagerModule(api, appContext, viewModelScope) }
    val downloads by lazy { DownloadModule(appContext, viewModelScope) }
    val tunnel by lazy { TunnelModule(appContext, viewModelScope) }
    val backup by lazy { BackupModule(api) }

    /**
     * 标题栏天气小功能（2026-09-17）：数据来自 core 的 `/api/weather`（它代理 Open-Meteo）。
     *
     * by lazy：默认是关的，只有开了开关的用户才会有人访问它。
     */
    val weather by lazy { WeatherModule(api, viewModelScope) }

    /**
     * 标题栏今日诗词（2026-09-18）：数据来自 core 的 `/api/poetry`（它代理 jinrishici v2）。
     *
     * by lazy 同上：默认关闭。挑诗的智能匹配在上游，本模块只负责取数与节流。
     */
    val poetry by lazy { PoetryModule(api, viewModelScope) }

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
            coreInstallStatus = tools.updateDeviceState,
            coreUpdatePersistence = coreUpdatePersistence,
            onResumeCorePolling = { tools.startDeviceUpdatePolling() }
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

    // ── 全局连接态（2026-09-21）────────────────────────────────────────────────
    //
    // 在这之前 app 有三个互不相干的信号，没有一个是「手机能不能到 core」的权威态：
    //   · healthState        —— 唯一正确的那个，但**零 UI 消费者**（注释里写着"供 UI 展示"，没人展示）
    //   · wsConnectionState  —— WS 通道状态，只有仪表盘读
    //   · dashboardState.isOffline —— 其实是「手机有没有外网」，却被首页渲染成"后端服务未连接"
    //
    // 后者是「连不上设备却没有任何提示」的直接原因：手机连着设备热点但 core 没跑时，
    // 手机的 INTERNET 能力通常仍为 true → isOffline=false → 永不提示；
    // 反过来连了个没外网的热点又会误报"后端服务未连接"。
    //
    // 现在收敛成一个权威态：**可达性只信 /health 探活**，[NetworkMonitor] 与「地址是否配过」
    // 只用来给失败**归因**，不参与"是否可达"的判定。
    val connectivity: StateFlow<ConnectivityUiState> = combine(
        health.healthState,
        networkMonitor.hasNetwork
    ) { hs, hasNet ->
        when {
            hs.status == HealthStatus.HEALTHY -> ConnectivityUiState(ConnectivityStatus.ONLINE)
            // 还没探出结论（冷启动首屏）→ 不显示任何横幅，避免"打开就报错"的观感
            hs.status == HealthStatus.UNKNOWN -> ConnectivityUiState(ConnectivityStatus.CHECKING)
            // 已确证不可达，下面只是给原因排序：手机没网 > 没配地址 > 摸不到设备
            !hasNet -> ConnectivityUiState(
                ConnectivityStatus.NO_LOCAL_NETWORK,
                "手机未连接网络",
                "请连接设备的 WiFi 热点后重试"
            )
            // isConfigValid 之前是死代码（定义了但全仓无调用），这里给它一个真实用途：
            // serverIp 为空时 baseUrl 会静默回落到 gatewayIp(192.168.0.1)，
            // 探不通的真正原因是"压根没配过地址"，不能笼统说"设备离线"。
            !appPrefs.isConfigValid -> ConnectivityUiState(
                ConnectivityStatus.NOT_CONFIGURED,
                "未配置设备地址",
                "请先完成设备配对"
            )
            else -> ConnectivityUiState(
                ConnectivityStatus.UNREACHABLE,
                "无法连接到设备",
                hs.errorMessage ?: "请确认已连上设备热点、且后台服务在运行"
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectivityUiState(ConnectivityStatus.CHECKING))

    /** 供 UI 的「重试」按钮调用：立刻探一次活。 */
    fun retryConnectivity() {
        viewModelScope.launch { runCatching { health.checkHealthNow() } }
    }

    /**
     * 接到 `RetrofitClient.onTransportFailure`：任何业务请求在传输层失败都立刻驱动一次探活。
     * 去抖在 [HealthModule.notifyTransportFailure] 里。
     */
    fun onApiTransportFailure() = health.notifyTransportFailure()

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
    /** 外部存储源配置页（文件管理器 → 更多 → 外部存储）的状态槽。 */
    val storageSourceState: StateFlow<StorageSourceState> get() = storageSources.state
    val debugLogState: StateFlow<DebugLogState> get() = tools.debugLogState
    val diagnoseState: StateFlow<DiagnoseState> get() = tools.diagnoseState
    val trafficManagementState: StateFlow<TrafficManagementState> get() = tools.trafficManagementState
    val monitorState: StateFlow<MonitorState> get() = dashboard.monitorState
    val downloadState: StateFlow<DownloadState> get() = downloads.state

    // ── P2 多端同步：连接恢复时拉取最新告警配置镜像（绝不推送默认，防 ab 设备回弹） ──
    fun refreshAlertConfig() {
        viewModelScope.launch { alertPrefs.refreshFromCore(api) }
    }

    /**
     * App 进后台：暂停周期 /health（冻结/Doze 下的请求只会制造假 UNREACHABLE）。
     * 由 MainActivity `ON_PAUSE` 调用。
     */
    fun onAppBackgrounded() {
        health.onAppBackgrounded()
    }

    /**
     * App 回前台：静默探活一次后再恢复周期（见 [HealthModule.onAppForegrounded]）。
     * 由 MainActivity `ON_RESUME` 调用；与 WS 重连并行。
     */
    fun onAppForegrounded() {
        health.onAppForegrounded(HEALTH_CHECK_INTERVAL_MS)
    }

    // ── Init & Cleanup ──
    init {
        // Start modules
        dashboard.init()

        // Core 升级期间跳过周期取数（2026-09-14）。
        // 升级会让设备端 HTTP 中断约 1 分钟，30s 全局告警轮询与 5s 首页刷新会持续失败并把
        // errorMessage 写进 state —— 用户看到满屏「加载告警失败」。闸门读的是 ToolsModule
        // 那个唯一真源（`markCoreUpdating` 同时落 SP 与推 StateFlow），
        // 轮询只是"跳过一轮"，闸门放开后自然继续，不需要谁去负责重启。
        // Toast 出口那一侧另有静默，见 [globalError]。
        dashboard.pollGate = { tools.coreUpdating.value }

        // 后端健康检查：周期探活（前台 30s）+ 数据出错时复查（见 collectBackendDownSignal）
        health.startPeriodicCheck(HEALTH_CHECK_INTERVAL_MS)
        collectBackendDownSignal()
        // 周期探活**不再**因单次/未确证的 UNREACHABLE 直接弹窗（2026-09 误报治理）：
        // 弹窗只挂在「业务请求失败 + 复查 /health 也失败」上（collectBackendDownSignal）。
        // healthState 仍供 UI/调试展示。

        // 收集跨模块事件，统一分发
        collectCrossModuleEvents()

        // 设备连接就绪后回读设备配置（含短信解析开关真源），覆盖冷启动 / 回前台重连的时序窗口
        collectDeviceConfigOnConnect()

        // data_changed 事件 → 精准增量刷新
        collectDataChangedEvents()

        // 天气 & 诗词预加载（2026-09-19）：在 UI 布局可见之前就取数据，避免用户看到空白顶栏。
        // loadConfig 内部已经 if (enabled) refresh()，所以一次调用足够。
        // 注：访问 weather/poetry 会触发 by lazy 初始化，代价是两次轻量 HTTP（core 本地）。
        weather.loadConfig()
        poetry.loadConfig()

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
                    changedType.startsWith(WsDataTopic.PREFIX_DEVICE) -> {
                        dashboard.smartRefresh(changedType)
                        network.smartRefresh(changedType)
                        tools.smartRefresh(changedType)
                    }
                    // Network: WiFi / 网络类变更
                    changedType.startsWith(WsDataTopic.PREFIX_WIFI) ||
                    changedType.startsWith(WsDataTopic.PREFIX_NETWORK) -> {
                        network.smartRefresh(changedType)
                    }
                    // Tools: 定时任务 / 自动化规则（core 侧增删改、以及任务自己触发后写日志）、
                    // 控制台历史（另一端敲了命令）。
                    // 2026-09-21：这两组分支原来**完全不存在** —— core 一直在推，app 这边
                    // 分发层直接丢掉，`ToolsModule.smartRefresh` 的 console 分支从没被调用过。
                    changedType.startsWith(WsDataTopic.PREFIX_TASK) ||
                    changedType.startsWith(WsDataTopic.PREFIX_CONSOLE) -> {
                        tools.smartRefresh(changedType)
                    }
                    // Media: 音频歌单（另一端 —— 通常是 web —— 建了 / 改了 / 删了歌单）。
                    // 2026-09-21：与上面那两组一样，core 侧 PlaylistStore 一直在推
                    // `media:playlists`，但这里原来没有分支 —— web 改完歌单，app 必须
                    // 退出页面再进才看得见。
                    changedType.startsWith(WsDataTopic.PREFIX_MEDIA) -> {
                        media.smartRefresh(changedType)
                    }
                }
            }
        }
    }

    /**
     * 设备连接就绪后回读设备配置（含短信解析开关真源 `sms_code_enabled`）。
     *
     * 2026-09-13 修复：此前回读只挂在 `SmsSettingsScreen` 的 `LaunchedEffect(Unit)` 上，
     * 冷启动若首屏不是短信设置页、或首轮 WS 握手/鉴权尚未完成导致首轮 `GET /api/config`
     * 抛异常被静默吞掉，本地镜像就会停在默认 false —— 表现为「彻底关掉 app 再打开，
     * 解析开关变回关」。现改为订阅 [webSocketRepository.connectionState]，每次迁到
     * `CONNECTED`（首连 / 回前台重连 / 断线恢复）都触发一次回读，覆盖冷启动与重连的时序窗口。
     */
    private fun collectDeviceConfigOnConnect() {
        viewModelScope.launch {
            webSocketRepository.connectionState
                .collect { state ->
                    if (state == ConnectionState.CONNECTED) {
                        tools.refreshDeviceConfig()
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

    /**
     * 准备「切换 / 退出设备」：丢掉上一台设备的**会话态**（缓存 + 错误 + 周期任务）。
     *
     * 刻意不关 ViewModel 自身：回配对页后重建的是 Activity 内容树，同一 VM 仍被记住；
     * 下一次配对成功会走 [onServerEndpointChanged] 重新拉数。
     * Dashboard Room 缓存必须清 —— 否则换设备后首屏会先闪上一台的 CPU/流量。
     */
    fun prepareDeviceSwitch() {
        viewModelScope.launch {
            runCatching { dashboard.clearDashboardCache() }
            dashboard.stopAlertPolling()
            clearError()
            network.clearServiceError()
            dismissBackendDownDialog()
        }
    }

    /**
     * 服务器地址变更后的会话刷新（P2）。
     *
     * @param hostChanged IP 或端口变了 —— 必须丢缓存，避免把旧设备数据贴到新地址上。
     *   同机改端口 / 再存同一地址时只轻量刷新，不闪空屏。
     */
    fun onServerEndpointChanged(hostChanged: Boolean) {
        if (hostChanged) {
            viewModelScope.launch { runCatching { dashboard.clearDashboardCache() } }
            clearError()
        }
        // 停掉旧地址上的告警轮询，避免下一轮仍打旧 baseUrl（refresh 会再启）
        dashboard.stopAlertPolling()
        dashboard.refreshDashboard()
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
    private val rawGlobalError: Flow<GlobalError?> = combine(
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
    }

    /**
     * 全局错误的**唯一**出口，Core 更新期间整体静默。
     *
     * 2026-09-14：升级 Core 时设备端 HTTP 必然中断 1 分钟左右，这期间所有取数失败
     * **都是预期结果**，不是用户需要知道的故障。原来这里没有任何闸门，于是升级过程中
     * 满屏「加载告警失败」「连接失败」 —— 30s 全局告警轮询、5s 首页刷新、WS 重连
     * 各贡献一份，且 `silent=true` 只压 `isLoading`、压不住 errorMessage。
     *
     * 在**出口**静默而不是去改十几个取数点：出口只有一条（这里 → MainActivity 的 Toast），
     * 取数点有十几处且还会继续增加，改出口才不会漏。
     * 真正该停下来的周期任务由 [collectCoreUpdatingGate] 暂停。
     */
    val globalError: StateFlow<GlobalError?> = combine(
        rawGlobalError,
        tools.coreUpdating
    ) { err, updating ->
        if (updating) null else err
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

    /** 本轮「Core 更新中」豁免的起始时刻（0 = 当前未处于豁免）。超时后恢复掉线弹窗。 */
    private var coreUpdateExemptSince = 0L

    /**
     * Core 是否正在更新并导致后端必然不可达（在线自更新 / 本地 APK 推送安装皆算）。
     *
     * 判据两路 OR：
     * 1. 持久化标记 `core_update_in_progress`（触发更新或本地安装时写入，App 被杀仍在）；
     * 2. 内存态 [ToolsModule.updateDeviceState]：`uploading` / `installing` / `reconnecting`。
     * 任一为真即豁免掉线弹窗；终态 done/failed 会清标记与状态（见 ToolsModule）。
     */
    private fun isCoreUpdateInterruptingBackend(): Boolean {
        if (coreUpdatePersistence.isCoreUpdating()) return true
        val st = tools.updateDeviceState.value
        return st?.state in setOf("uploading", "installing") || st?.reconnecting == true
    }

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
                        // WS 仍连着：后端进程活着，HTTP 单次失败更像瞬时抖动，不弹全屏「掉线」
                        if (webSocketRepository.connectionState.value == ConnectionState.CONNECTED) {
                            return@collect
                        }
                        // Core 更新/本地安装会让 8088 断联约 1 分钟：只当「更新中」，
                        // 不弹后端掉线（更新进度 UI 已展示「设备重启中…」）。
                        // 超过 CORE_UPDATE_EXEMPT_TIMEOUT_MS 仍未恢复 → 恢复掉线弹窗，
                        // 避免更新卡死时用户永远看不到异常。
                        if (isCoreUpdateInterruptingBackend()) {
                            val nowMs = System.currentTimeMillis()
                            if (coreUpdateExemptSince == 0L) coreUpdateExemptSince = nowMs
                            if (nowMs - coreUpdateExemptSince < CORE_UPDATE_EXEMPT_TIMEOUT_MS) {
                                return@collect
                            }
                            // 豁免超时：重置计时，走后面的 backend-down；更新若仍在进行，
                            // 下一轮 collect 会重新起一轮豁免窗口（但此时已弹过掉线窗）。
                            coreUpdateExemptSince = 0L
                        } else {
                            coreUpdateExemptSince = 0L
                        }
                        val now = System.currentTimeMillis()
                        if (err.message == lastHandledMessage && now - lastBackendDownAt < BACKEND_DOWN_DEDUPE_MS) {
                            return@collect
                        }
                        lastHandledMessage = err.message
                        lastBackendDownAt = now
                        val reachable = runCatching { health.checkHealthNow() }.getOrDefault(false)
                        // 两个独立信号：业务请求已失败 + /health 也失败 → 可定性为后端不可达。
                        // 周期探活单独失败**不**走这里（见 init 注释），所以不需要 HealthModule
                        // 再叠一层「连击确证」——那会拖慢真正的掉线提示。
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
     * 2026-09 误报治理：删除「周期探活 → 直接弹窗」路径（原 collectHealthStatus）。
     *
     * 切后台再回前台时 /health 极易超时记成 UNREACHABLE，旧实现会立刻弹「后端掉线」。
     * 现在弹窗**只**由 [collectBackendDownSignal] 触发：业务请求失败 + 复查 /health 失败。
     * healthState 仍更新，供调试/设置展示，不再单独驱动全屏对话框。
     */

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