package com.ufi_axis.viewmodel

import com.ufi_axis.viewmodel.gatedByTransitionGate
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.bill.BillRepositoryProvider
import com.ufi_axis.data.bill.CoreBillRepository
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 全局错误的来源。决定重试动作与清除动作分派到哪个 module（见 [MainViewModel.dismissGlobalError]）。
 *
 * 新增来源时**必须同步三处**：这个枚举、[MainViewModel.rawGlobalError] 的 combine、
 * [MainViewModel.dismissGlobalError] 的分派。漏掉 dismiss 分派的后果很具体 ——
 * 浮层收起后不清 state，切回页面又弹一次陈旧错误。
 */
enum class GlobalErrorSource { DASHBOARD, NETWORK, MONITOR, TOOLS, SERVICE, DEVICE_SETTINGS, DOWNLOAD }

/** 后端健康检查周期（毫秒）：周期访问 /health 维护在线状态（仅前台）。 */
private const val HEALTH_CHECK_INTERVAL_MS = 30_000L

/**
 * Core 更新期间豁免「后端掉线」弹窗的最长时间（毫秒）。
 *
 * 在线更新（`triggerDeviceUpdate`）与本地 APK 推送安装都会让 Core force-stop/重启，
 * 8088 断联约 1 分钟属预期。此窗口内即使 /health 确证不可达也只当「更新中」，
 * 不弹掉线对话框（更新进度 UI 已有「设备重启中…」）。
 *
 * 超时后恢复弹窗：与 ToolsModule 更新轮询失联阈值（约 5 分钟）对齐，
 * 避免更新卡死时用户永远看不到「后端不可达」。
 */
private const val CORE_UPDATE_EXEMPT_TIMEOUT_MS = 5 * 60_000L

/** core 更新占线状态（与 core 侧 UpdateManager.BUSY_STATES 对齐，小写）。 */
private val CORE_UPDATE_BUSY_STATES = setOf("downloading", "verifying", "installing", "uploading")

/**
 * 豁免窗口的重算节拍（毫秒）。
 *
 * 必须有它：豁免到期是**时间**触发的，而判据的上游全是会去重相等值的 StateFlow ——
 * 更新卡死时它们不再发值，没有节拍就永远走不到超时分支，启动页会一直停在 null 不报错。
 * 取 30s：更新开始/结束本身由上游即时触发，节拍只负责 5 分钟窗口的到期判定，
 * 与全站 /health 的 30s 周期同档。
 */
private const val CORE_UPDATE_EXEMPT_TICK_MS = 30_000L

/**
 * 启动页最长展示时间（ms）。
 *
 * 兜底路径，非常规路径：预加载正常情况下 2 秒内完成。用于防止「某一步挂起 120s
 *（OkHttp readTimeout）导致启动页永不消失」。允许用户进入数据尚未齐全的界面，
 * 但不能停留在加载页。
 */
private const val STARTUP_GATE_TIMEOUT_MS = 8_000L

/**
 * 启动页最短展示时间（ms）。
 *
 * 局域网内预加载可能在几百毫秒内完成，不设下限时启动页会一闪而过，观感差于不显示。
 */
private const val STARTUP_GATE_MIN_MS = 600L

/**
 * 全局错误：一条待展示的错误文案 + 它来自哪里。
 *
 * 刻意**不带** retry lambda —— 见 [MainViewModel.globalError] 的说明。
 * 也不带 `retryable`：展示通道改为 Toast 后，全局层不再提供重试按钮
 *（仪表盘/网络/监控本身有轮询 + 回前台重取，tools/service 没有通用重试语义）。
 */
data class GlobalError(
    val message: String,
    val source: GlobalErrorSource
)

/**
 * 写操作成功提示。与 [GlobalError] **对称**的成功通道。
 *
 * [seq] 是自增序号，唯一作用是**让同一条文案能连续触发两次**：
 * 全局错误有 30s 同文案去重（防轮询刷屏），但成功提示不能去重 ——
 * 用户连着保存两次就该看到两次确认。少了 seq，第二次的 `WriteNotice` 与第一次 equals 相等，
 * 宿主的 `LaunchedEffect(notice)` 不会重新触发，表现为「第二次点保存无反应」。
 */
data class WriteNotice(
    val message: String,
    val subtitle: String? = null,
    /** 语义类型（UI 据此选图标与配色）。默认 SUCCESS 与旧行为一致。 */
    val type: com.ufi_axis.viewmodel.module.WriteNoticeType =
        com.ufi_axis.viewmodel.module.WriteNoticeType.SUCCESS,
    val seq: Long
)

/**
 * 全局连接态。**app 里判断「能不能用」只看这一个枚举**。
 *
 * 语义分工（本类型存在的目的：三个旧信号各自独立，正是「连不上却不提示」的根因）：
 * - [CHECKING]：尚未得出结论（冷启动首屏）。**不得显示任何错误提示**，否则每次启动都会先闪现错误状态。
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
 * @param title  一句话病因（弹窗标题）；[ConnectivityStatus.ONLINE]/[ConnectivityStatus.CHECKING] 时为 null
 * @param detail 下一步动作的指引（弹窗正文）
 */
data class ConnectivityUiState(
    val status: ConnectivityStatus,
    val title: String? = null,
    val detail: String? = null
) {
    /** 是否处于「连不上」状态。CHECKING 不计入，尚未得出结论时不提示。 */
    val hasProblem: Boolean
        get() = status != ConnectivityStatus.ONLINE && status != ConnectivityStatus.CHECKING

    /** 是否尚未配置设备地址：UI 可据此把按钮从「重试」换成「去配对」。 */
    val needsSetup: Boolean get() = status == ConnectivityStatus.NOT_CONFIGURED
}

class MainViewModel(
    private val api: UfiAxisApi,
    private val webSocketRepository: WebSocketRepository,
    private val networkMonitor: NetworkMonitor,
    private val appContext: Context
) : ViewModel() {

    // ── Modules ──
    // 首屏仅 Dashboard 立即初始化；其余非首屏模块惰性加载（by lazy），
    // 延后到对应页面访问 viewModel.xxx 或 LaunchedEffect 触发，降低冷启动开销。
    // 注：network/tools 仍会因跨模块事件收集在 MainViewModel 构造期被提前初始化，
    // files/apps/downloads 等重型模块则推迟到首次访问。
    // 多端同步：告警配置仓库（core 唯一真源镜像；连接即拉取，不下发默认值）
    val alertPrefs = AlertPrefsRepository()

    /**
     * Core 自更新「进行中」标记持久化（[com.ufi_axis.data.update.CoreUpdatePersistence]）。
     * 供 [tools] 写入（用户确认更新 Core 时）与 [updatePrompt] 读取（冷启动接管进度显示）。
     */
    private val coreUpdatePersistence = SharedPreferencesCoreUpdatePersistence(appContext)

    /** 只用于读取「设备地址是否已配置」（[connectivity] 的归因分支）。构造一次并复用，不在 combine 中重复实例化。 */
    private val appPrefs = AppPreferences(appContext)

    // 跨模块 UI 事件汇聚点（eager 创建、开销极小）：各 module 构造时接收该 sink，
    // 将自己的事件转发/直投到此。collectCrossModuleEvents 仅订阅本 sink，
    // 不会在构造期触发 network / tools 的 by lazy 求值（避免冷启动 eager 加载）。
    //
    // 声明位置必须在 [dashboard] **之前**：Kotlin 按声明顺序初始化属性，
    // 而 dashboard 是 eager 的，构造时就需要该 sink；顺序颠倒时传入的是 null。
    private val crossModuleEventSink = MutableSharedFlow<UiEvent>(extraBufferCapacity = 64)

    val dashboard = DashboardModule(
        api, webSocketRepository, networkMonitor, appContext, viewModelScope, alertPrefs,
        // 监控设置四个二级页的写结果需要到达全局提示出口，因此 dashboard 也接收此 sink。
        crossModuleEventSink = crossModuleEventSink
    )

    /**
     * 后端健康检查（周期探活 + 出错时即时复查）。[viewModelScope] 内常驻，供 UI 判断后端是否在线、
     * 以及数据加载失败时是否因后端挂了。见 [HealthModule]。
     */
    val health = HealthModule(api, viewModelScope)

    // 网络页 hero 卡的信号读数必须与仪表盘同源：把 WS 消息流传入，
    // 由 NetworkModule 自行订阅 `signal` 频道（见 collectSignalFromWs 的注释）。
    val network by lazy {
        NetworkModule(api, appContext, viewModelScope, crossModuleEventSink, webSocketRepository.messages)
    }
    val tools by lazy { ToolsModule(api, appContext, viewModelScope, crossModuleEventSink, alertPrefs, coreUpdatePersistence) }
    val files by lazy { FileManagerModule(api, appContext, FileShortcutRepository(appContext), viewModelScope) }
    // 外部存储源（FTP / WebDAV）配置：只管 /api/storage/sources 这组 CRUD，
    // 源里的列目录与读写仍由 files 走 remote: 前缀路径完成（core 侧派发）。
    // by lazy：多数用户不配置任何源，不应在 ViewModel 构造期分配 state。
    val storageSources by lazy { StorageSourceModule(api, viewModelScope) }

    /**
     * 真 PTY 终端会话（app 端原生）。
     *
     * 独立 repository 而非并入 `WebSocketRepository`：后者是 /ws/realtime 推送通道，
     * 带订阅主题/重连退避/data_changed 扇出，与终端的帧协议和生命周期都不同（详见
     * `PtySessionRepository` 类文档）。`by lazy`：多数用户不会进入终端页，
     * 不应在 ViewModel 构造期创建 OkHttpClient。
     *
     * 设备地址/token 每次现取 [AppPreferences]：设备重新配对后 baseUrl/token 会变，
     * 长驻实例拿着旧值会连不上（与 RetrofitClient.recreate 同一理由）。
     */
    fun ptySession(): com.ufi_axis.data.repository.PtySessionRepository =
        ptySessionRef ?: com.ufi_axis.data.repository.PtySessionRepository(
            AppPreferences(appContext).baseUrl,
            AppPreferences(appContext).token,
        ).also { ptySessionRef = it }

    private var ptySessionRef: com.ufi_axis.data.repository.PtySessionRepository? = null
    // 媒体中心（工具 → 媒体中心）：列设备端的视频 / 音乐 / 图片。
    // 数据来自 core 的 /api/media（它查系统媒体库），播放仍走 /api/files/stream。
    val media by lazy { MediaModule(api, appContext, viewModelScope) }
    val apps by lazy { AppManagerModule(api, appContext, viewModelScope) }
    val downloads by lazy { DownloadModule(appContext, viewModelScope, crossModuleEventSink) }
    val tunnel by lazy { TunnelModule(appContext, viewModelScope) }
    val backup by lazy { BackupModule(api) }

    /**
     * 标题栏天气小功能：数据来自 core 的 `/api/weather`（代理 Open-Meteo）。
     *
     * by lazy：该功能默认关闭，仅启用后的用户会访问。
     */
    val weather by lazy { WeatherModule(api, viewModelScope, crossModuleEventSink) }

    /**
     * 标题栏今日诗词：数据来自 core 的 `/api/poetry`（代理 jinrishici v2）。
     *
     * by lazy 同上：默认关闭。挑诗的智能匹配在上游，本模块只负责取数与节流。
     */
    val poetry by lazy { PoetryModule(api, viewModelScope, crossModuleEventSink) }

    /** DLNA MediaServer：设置 → DLNA 服务。默认关闭。 */
    val dlna by lazy { DlnaModule(api, viewModelScope, crossModuleEventSink) }

    /**
     * 更新提示的唯一状态槽。
     *
     * 只聚合 [tools] 的 App 自更新状态与 [dashboard] 的 core 版本状态并持有唯一的 visible，
     * 自身不发请求。有它之后「App 有新版」和「Core 有新版」是同一个弹窗里的两块内容，
     * 而不是两个独立的 remember 布尔开关各自弹出平台 Window（后者会叠加两层 scrim）。
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

    // 后台守护调度器：WorkManager 周期任务封装，UI 经此读写开关/间隔/状态
    val backgroundGuard by lazy { GuardScheduler(appContext, api) }

    /**
     * 统一的「转场期暂缓发布」包装。
     *
     * 每条对外暴露的 StateFlow 都经过这一层：转场期间暂存、落定后只提交最新值。
     * 这样**新页面 / 新模块无需各自维护闸门**，状态从这里输出即自动生效。
     * 实现与取舍见 [gatedByTransitionGate] 的 KDoc。
     *
     * 缓存是必需的：`get()` 每次被访问都会重新求值，若每次都新建一条包装流，
     * `collectAsState()` 会在每次重组时重订阅、并留下没人取消的收集协程。
     */
    private val gatedFlows = HashMap<StateFlow<*>, StateFlow<*>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> StateFlow<T>.ui(): StateFlow<T> =
        gatedFlows.getOrPut(this) { gatedByTransitionGate(viewModelScope) } as StateFlow<T>

    // ── StateFlows (backward-compatible) ──
    val dashboardState: StateFlow<DashboardState> get() = dashboard.dashboardState.ui()
    // ── 按字段派生的 StateFlow（各 screen 按需订阅，避免全量重组） ──
    val dashboardCpuInfo: StateFlow<CpuInfo?> get() = dashboard.cpuInfoState.ui()
    val dashboardMemoryInfo: StateFlow<MemoryInfo?> get() = dashboard.memoryInfoState.ui()
    val dashboardTrafficRealtime: StateFlow<TrafficRealtime?> get() = dashboard.trafficRealtimeState.ui()
    val dashboardSignalInfo: StateFlow<SignalInfo?> get() = dashboard.signalInfoState.ui()
    val dashboardBatteryInfo: StateFlow<BatteryInfo?> get() = dashboard.batteryInfoState.ui()
    val dashboardIsLoading: StateFlow<Boolean> get() = dashboard.isLoadingState.ui()
    val networkState: StateFlow<NetworkState> get() = network.networkState.ui()
    /** 后端健康状态（周期探活 + 出错复查），UI 可据此展示「后端是否在线」。 */
    val healthState: StateFlow<HealthState> get() = health.healthState.ui()

    // ── 全局连接态 ─────────────────────────────────────────────────────────────
    //
    // 收敛前存在三个互不相关的信号，没有一个是「手机能否到达 core」的权威状态：
    //   · healthState        —— 唯一正确的判据，但**零 UI 消费者**（注释声明「供 UI 展示」，实际无人使用）
    //   · wsConnectionState  —— WS 通道状态，仅仪表盘读取
    //   · dashboardState.isOffline —— 实际语义是「手机是否有外网」，却被首页渲染为「后端服务未连接」
    //
    // 后者是「连不上设备却无任何提示」的直接原因：手机连接设备热点但 core 未运行时，
    // 手机的 INTERNET 能力通常仍为 true → isOffline=false → 永不提示；
    // 反之连接无外网的热点又会误报「后端服务未连接」。
    //
    // 因此收敛为单一权威状态：**可达性只依据 /health 探活**，[NetworkMonitor] 与「地址是否配置」
    // 仅用于失败**归因**，不参与「是否可达」的判定。
    val connectivity: StateFlow<ConnectivityUiState> = combine(
        health.healthState,
        networkMonitor.hasNetwork
    ) { hs, hasNet ->
        when {
            hs.status == HealthStatus.HEALTHY -> ConnectivityUiState(ConnectivityStatus.ONLINE)
            // 尚未得出结论（冷启动首屏）→ 不显示任何横幅，避免「打开即报错」的观感
            hs.status == HealthStatus.UNKNOWN -> ConnectivityUiState(ConnectivityStatus.CHECKING)
            // 已确证不可达，下面只是给原因排序：手机没网 > 没配地址 > 摸不到设备
            !hasNet -> ConnectivityUiState(
                ConnectivityStatus.NO_LOCAL_NETWORK,
                "手机未连接网络",
                "请连接设备的 WiFi 热点后重试"
            )
            // isConfigValid 的用途：serverIp 为空时 baseUrl 会静默回落到
            // gatewayIp(192.168.0.1)，此时探不通的原因是未配置地址，不能笼统判定为「设备离线」。
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

    /**
     * 供掉线弹窗的「重试」按钮调用：清掉各模块的陈旧错误文案 + 立刻探一次活。
     *
     * 刻意**不**关弹窗：探活结果才是答案 —— 通了 [connectivity] 自己会翻 ONLINE、
     * 弹窗随派生态消失；没通就该继续挂着。关掉再自己弹回来只会闪一下。
     */
    fun retryConnectivity() {
        clearError()
        viewModelScope.launch { runCatching { health.checkHealthNow() } }
    }

    /**
     * 接到 `RetrofitClient.onTransportFailure`：任何业务请求在传输层失败都立刻驱动一次探活。
     * 去抖在 [HealthModule.notifyTransportFailure] 里。
     */
    fun onApiTransportFailure() = health.notifyTransportFailure()

    // ── 实时连接状态转发（供 UI 展示 WS 连接状态，避免各屏重复订阅 repository） ──
    val wsConnectionState: StateFlow<ConnectionState>
        get() = webSocketRepository.connectionState

    // ── 测速取消透传：转发 NetworkModule.cancelSpeedTest() ──
    fun cancelSpeedTest() = network.cancelSpeedTest()
    val toolsState: StateFlow<ToolsState> get() = tools.toolsState.ui()
    val tunnelState: StateFlow<TunnelState> get() = tunnel.state.ui()
    val alertsState: StateFlow<AlertsState> get() = tools.alertsState.ui()
    val updateState: StateFlow<UpdateState> get() = dashboard.updateState.ui()
    /** 统一更新提示（App 项 + Core 项聚合）；MainActivity 自动提示与关于页手动检查共用此槽。 */
    val updatePromptState: StateFlow<UpdatePromptState> get() = updatePrompt.promptState.ui()
    val tasksState: StateFlow<TasksState> get() = tools.tasksState.ui()
    val smsForwardState: StateFlow<SmsForwardState> get() = tools.smsForwardState.ui()
    /** 通用 Webhook 渠道（`/api/notify/webhook`）：推送渠道页与 Webhook 配置页共读这一份。 */
    val webhookState: StateFlow<WebhookState> get() = tools.webhookState.ui()

    /** 本机短信回发渠道（`/api/notify/sms`）：推送渠道页与本机短信配置页共读这一份。 */
    val localSmsState: StateFlow<LocalSmsState> get() = tools.localSmsState.ui()
    val appManageState: StateFlow<AppManageState> get() = apps.state.ui()
    val deviceSettingsState: StateFlow<DeviceSettingsState> get() = network.deviceSettingsState.ui()
    val serviceState: StateFlow<ServiceControlState> get() = network.serviceState.ui()
    val speedTestState: StateFlow<SpeedTestState> get() = network.speedTestState.ui()

    /** 定时测速历史（NetworkModule 拉取，Screen 消费）。 */
    val speedTestHistory: StateFlow<List<SpeedTestHistoryEntry>> get() = network.speedTestHistory.ui()
    val fileManagerState: StateFlow<FileManagerState> get() = files.state.ui()
    /** 外部存储源配置页（文件管理器 → 更多 → 外部存储）的状态槽。 */
    val storageSourceState: StateFlow<StorageSourceState> get() = storageSources.state.ui()
    val debugLogState: StateFlow<DebugLogState> get() = tools.debugLogState.ui()
    val diagnoseState: StateFlow<DiagnoseState> get() = tools.diagnoseState.ui()
    val trafficManagementState: StateFlow<TrafficManagementState> get() = tools.trafficManagementState.ui()
    val monitorState: StateFlow<MonitorState> get() = dashboard.monitorState.ui()
    val downloadState: StateFlow<DownloadState> get() = downloads.state.ui()

    // ── 多端同步：连接恢复时拉取最新告警配置镜像（不下发默认值，防止设备间配置回弹） ──
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
     *
     * 同时立即拉取一次**待批准列表**。App 在后台时 WS 帧可能已被系统裁掉、进程被冻结，
     * 不做这次补拉，机主切回 App 后需再等待最多 30s 才能看到弹窗。
     * 不依赖推送是否到达：该请求幂等且开销低。
     */
    fun onAppForegrounded() {
        health.onAppForegrounded(HEALTH_CHECK_INTERVAL_MS)
        network.loadApprovals()
    }

    /**
     * 启动页闸门协程（见 [startupGate]）。
     *
     * 必须**声明在 init 之前**：init 里就会调 [startupGate] 并给它赋值，
     * 若声明在 init 之后，那句 `= null` 的初始化会把刚存的引用又抹掉。
     */
    private var startupGateJob: Job? = null

    // ── Init & Cleanup ──
    init {
        // 话费账单：生产默认走 core 的 /api/carrier/bill；演示数据只留给 Preview/无 core 调试。
        BillRepositoryProvider.repository = CoreBillRepository(api)

        // Start modules
        dashboard.init()

        // Core 升级期间跳过周期取数。
        // 升级会使设备端 HTTP 中断约 1 分钟，30s 全局告警轮询与 5s 首页刷新会持续失败并把
        // errorMessage 写入 state，界面上表现为连续的「加载告警失败」。闸门读取 ToolsModule
        // 的唯一真源（`markCoreUpdating` 同时写入 SP 并推送 StateFlow）；
        // 轮询仅「跳过一轮」，闸门放开后自然继续，无需额外负责重启。
        // Toast 出口侧另有静默，见 [globalError]。
        dashboard.pollGate = { tools.coreUpdating.value }

        // 后端健康检查：周期探活（前台 30s）+ 传输层失败时即时复查（见 HealthModule）。
        // 它是全站唯一的可达性真源 —— connectivity 与掉线弹窗都从这里派生。
        health.startPeriodicCheck(HEALTH_CHECK_INTERVAL_MS)
        collectConnectivityRecovery()
        // 启动页闸门：与预加载同一条链路的收尾判据，见 startupGate 的说明
        startupGate()

        // 收集跨模块事件，统一分发
        collectCrossModuleEvents()

        // 设备连接就绪后回读设备配置（含短信解析开关真源），覆盖冷启动 / 回前台重连的时序窗口
        collectDeviceConfigOnConnect()

        // data_changed 事件 → 精准增量刷新
        collectDataChangedEvents()

        // 凭据看门狗：实时通道断了就定期核一次身份。
        // 放这里而不是 Compose 的 LaunchedEffect：组合会被页面/分支反复创建销毁，
        // 而"我是不是已经被别的设备删掉配对"这件事必须一直有人盯着（2026-11-14）。
        startCredentialWatchdog()

        // 天气 & 诗词预加载：在 UI 布局可见之前取数据，避免出现空白顶栏。
        // loadConfig 内部已经 if (enabled) refresh()，所以一次调用足够。
        // 注：访问 weather/poetry 会触发 by lazy 初始化，代价是两次轻量 HTTP（core 本地）。
        weather.loadConfig()
        poetry.loadConfig()

        // sms_contacts WS 推送 → 工具模块联系人列表
        collectSmsContactsFromWs()

        // update WS 推送（含订阅时服务端下发的快照）→ 设备更新状态（手动推送 APK 成功/失败）
        collectUpdateFromWs()
        collectThumbPrewarmFromWs()
    }

    /**
     * WS 握手被拒之后核对一次登录状态（2026-11-14）。
     *
     * ## 为什么需要
     *
     * 机主在 A 上删掉 B 的配对后，B 的 App 仍停在仪表盘：它的数据几乎全走 WS，
     * 而 HTTP 444 的清凭据阈值（`AuthRejectionPolicy`，3 次 / 跨度 ≥10s）根本没机会攒到 ——
     * 界面上只有一个反复出现的"实时通道连接被拒"提示（`onAuthRejected`），人被留在原地。
     *
     * ## 为什么不在 `onAuthRejected` 里直接清凭据
     *
     * 那条路会被"冷启动时钟未校准"误伤（WS 先于 /health 校时发起握手 → 必然 1008），
     * 2026-10-22 修过一次同样的坑。所以这里只**发探测请求**，让唯一那份阈值继续做决定：
     * - 时钟问题：HTTP 会先自愈（校时重试），探测拿到 200，什么都不会发生；
     * - 真被删配对：探测连续吃 444，阈值到点 → `onUnauthorized` → 清凭据回配对页。
     *
     * 3 次 × 5s：既满足"≥3 次且跨度 ≥10s"的阈值，又能在 ~15s 内给出结论。
     */
    fun verifyCredentialsAfterWsRejection() {
        if (!authProbeStarted.compareAndSet(false, true)) {
            return
        }
        if (!appPrefs.isSetupComplete) {
            authProbeStarted.set(false)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 一次调用 = 一次探测。连续三次（≥10s 跨度）由调用方的周期驱动，
                // 正好喂满 `AuthRejectionPolicy` 的阈值；成功后 streak 会自己清零。
                val r = runCatching { api.getDeviceVersion() }
                // 探测通过 = 凭据没问题，那问题在实时通道那一次的握手上：
                // 把它从 `authRejected` 的终态里拉回来，否则"实时连接已断开"会永久成立
                //（`authRejected=true` 后 scheduleReconnect 直接 return）。
                if (r.isSuccess) {
                    webSocketRepository.retryAfterAuthProbe()
                }
                com.ufi_axis.util.DebugLog.w(
                    "AuthProbe",
                    "凭据探测：${if (r.isSuccess) "OK" else (r.exceptionOrNull()?.message ?: "失败")}"
                )
            } finally {
                authProbeStarted.set(false)
            }
        }
    }

    /**
     * 实时通道不在线时，每 8 秒核一次凭据（`AuthRejectionPolicy` 要 3 次 444 且跨度 ≥10s）。
     *
     * 只在"WS 不是 CONNECTED"时探测：
     * - core 重启/断网 → 探测在传输层失败，策略不计入吊销，等它回来；
     * - 配对被别的设备删了 → 连续 444 → 阈值到点 → 清凭据回配对页。
     */
    private fun startCredentialWatchdog() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(8_000)
                val st = webSocketRepository.connectionState.value
                if (st != ConnectionState.CONNECTED) {
                    verifyCredentialsAfterWsRejection()
                }
            }
        }
    }

    /** 上面那条探测的在途标记，避免 WS 反复握手失败时并发起一堆探测。 */
    private val authProbeStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 收集跨模块 UiEvent，统一分发到目标 Module。
     * 订阅 eager 创建的汇聚 sink（crossModuleEventSink），不访问 network / tools 的
     * events 流，因此不会在构建期触发这两个模块的 by lazy 求值（避免冷启动 eager 加载）。
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
                    is UiEvent.ShowWriteNotice -> {
                        _writeNotice.value = WriteNotice(
                            message = event.message,
                            subtitle = event.subtitle,
                            type = event.type,
                            seq = ++writeNoticeSeq
                        )
                    }
                }
            }
        }
    }

    // ── Smart Refresh: 订阅 data_changed 事件，精准增量刷新 ──
    private fun collectDataChangedEvents() {
        viewModelScope.launch {
            webSocketRepository.dataChanged.collect { changedType ->
                // 前缀分支统一交给 dispatchChanged：按前缀分组 + 800ms 窗口尾沿分发，
                // 见 [dispatchChanged]。
                dispatchChanged(changedType)
            }
        }
    }

    // ── data_changed 去抖合并（尾沿触发，防事件风暴）────────────────────────────
    // invalidateAll 已将单次写操作压成 1 条事件；但通配失效（device:*）等仍可能多条
    // 密集到达。按前缀分组、800ms 窗口尾沿统一分发：组内所有 key 一轮刷完，不丢 key。
    // 两个 map 均只在主线程（viewModelScope 的 collect 协程）读写，无需并发容器。
    private val pendingByGroup = mutableMapOf<String, MutableSet<String>>()
    private val debounceJobs = mutableMapOf<String, Job>()

    /** 去抖窗口（尾沿触发）。 */
    private val DISPATCH_DEBOUNCE_MS = 800L

    private fun groupOf(changed: String): String = when {
        changed == WsDataTopic.RECONNECTED -> "reconnect"
        changed.startsWith("device:") -> "device"
        changed.startsWith("wifi:") -> "wifi"
        changed.startsWith("network:") || changed.startsWith("hub:") -> "network"
        changed.startsWith(WsDataTopic.PREFIX_TASK) || changed.startsWith(WsDataTopic.PREFIX_CONSOLE) -> "tools"
        changed.startsWith("media:") -> "media"
        changed == "summary" -> "network"
        else -> "other"
    }

    private fun dispatchChanged(changedType: String) {
        // 配对待批准是**模态事件**（需立即弹窗要求机主决策），不经过 800ms 尾沿去抖；
        // 它也不属于任何既有分组（device/wifi/network/tools/media），直接短路处理。
        // core 侧 `ApprovalPushNudger` 会快速补发多帧，因此此处必须高效且幂等
        // （`loadApprovals` 内部已有在途合并）。
        if (changedType == WsDataTopic.PAIRING_APPROVALS) {
            network.loadApprovalsFromPush()
            return
        }
        // 网络相关前缀记录外部变更信号（loadNetworkAll 闸门豁免的依据）
        if (changedType == WsDataTopic.RECONNECTED ||
            changedType.startsWith(WsDataTopic.PREFIX_DEVICE) || changedType.startsWith(WsDataTopic.PREFIX_WIFI) ||
            changedType.startsWith(WsDataTopic.PREFIX_NETWORK) || changedType.startsWith("hub:") ||
            changedType == "summary"
        ) network.markExternalChange()

        val group = groupOf(changedType)
        pendingByGroup.getOrPut(group) { mutableSetOf() }.add(changedType)
        if (debounceJobs[group]?.isActive == true) return
        debounceJobs[group] = viewModelScope.launch {
            delay(DISPATCH_DEBOUNCE_MS)
            val keys = pendingByGroup.remove(group).orEmpty().toList()
            if (keys.none()) return@launch
            if (keys.contains(WsDataTopic.RECONNECTED)) {
                // sentinel 优先：全量补拉路径，不逐 key
                dashboard.smartRefresh(WsDataTopic.RECONNECTED)
                network.smartRefresh(WsDataTopic.RECONNECTED)
                tools.smartRefresh(WsDataTopic.RECONNECTED)
                media.smartRefresh(WsDataTopic.RECONNECTED)
                return@launch
            }
            keys.forEach { routeToModules(it) }
        }
    }

    /** 前缀路由（另含 hub: / summary 两条）。 */
    private fun routeToModules(changedType: String) {
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
            // 这两组分支必须存在：core 会持续推送，若分发层丢弃，
            // `ToolsModule.smartRefresh` 的 console 分支将永远不会被调用。
            changedType.startsWith(WsDataTopic.PREFIX_TASK) ||
            changedType.startsWith(WsDataTopic.PREFIX_CONSOLE) -> {
                tools.smartRefresh(changedType)
            }
            // Media: 音频歌单（另一客户端，通常为 web，创建 / 修改 / 删除歌单）。
            // core 侧 PlaylistStore 会持续推送 `media:playlists`；缺少该分支时，
            // web 端修改歌单后，app 必须退出页面再进入才能看到。
            changedType.startsWith(WsDataTopic.PREFIX_MEDIA) -> {
                media.smartRefresh(changedType)
            }
            // hub 泛型 key（core 广播）→ network + dashboard
            changedType.startsWith("hub:") -> {
                network.smartRefresh(changedType)
                dashboard.smartRefresh(changedType)
            }
            // summary 失效 → dashboard
            changedType == "summary" -> dashboard.smartRefresh(changedType)
            // 配对相关（目前只有 pairing:approvals）。正常情况下已在 [dispatchChanged] 中
            // 短路（不经过去抖）；此分支为防御性兜底，必须保留：否则短路逻辑一旦移除，
            // 整个批准弹窗链路会被静默丢弃。
            changedType.startsWith(WsDataTopic.PREFIX_PAIRING) -> network.loadApprovalsFromPush()
        }
    }

    /**
     * 设备连接就绪后回读设备配置（含短信解析开关真源 `sms_code_enabled`）。
     *
     * 回读不能只挂在 `SmsSettingsScreen` 的 `LaunchedEffect(Unit)` 上：冷启动首屏若不是
     * 短信设置页，或首轮 WS 握手/鉴权未完成导致首轮 `GET /api/config` 抛异常被静默吞掉，
     * 本地镜像会停留在默认 false，表现为「彻底关闭 app 再打开，解析开关变回关」。
     * 因此订阅 [webSocketRepository.connectionState]，每次迁移到 `CONNECTED`
     *（首连 / 回前台重连 / 断线恢复）都触发一次回读，覆盖冷启动与重连的时序窗口。
     */
    private fun collectDeviceConfigOnConnect() {
        viewModelScope.launch {
            webSocketRepository.connectionState
                .collect { state ->
                    if (state == ConnectionState.CONNECTED) {
                        tools.refreshDeviceConfig()
                        // 天气 / 诗词也要补：冷启动那次预加载只要撞上"core 还没起来 / 刚重启 /
                        // 网络还没通"，就再没有任何重试 —— 顶栏会一直空着，直到用户点进
                        // 「小功能 → 今日天气」才恢复（2026-11-14 实测：装完 core 后 App 顶栏
                        // 一直没有天气，进一次设置页就出来了）。
                        // 判据用"数据缺不缺"而不是 configLoaded：配置读到了但取数 502 时也要补。
                        if (weather.state.value.now == null) weather.loadConfig()
                        if (poetry.state.value.poem == null) poetry.loadConfig()
                    }
                }
        }
    }

    /** sms_contacts WS 推送 → 直接更新联系人列表（免 HTTP 请求） */
    private fun collectSmsContactsFromWs() {
        // Dispatchers.Default：JSON 反序列化不占用主线程。
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
     * 后端 UpdateManager 在恢复（新 Core 启动读取 RESULT 日志）后，会通过 WS 下发当前更新结果；
     * 前端订阅 "update" 主题（见 WebSocketRepository）后，连接建立/恢复时即可收到快照，
     * 保证「手动推送 APK 后能收到更新结果推送」。
     */
    /**
     * 封面预热进度。core 的 ThumbPrewarmWorker 把进度挂在
     * `data_changed` 消息里（changed = media:thumb-progress + done/total/failed/running），
     * 设置页订阅 [MediaModule.thumbPrewarmProgress] 显示「预热中 12/85（失败 3）」。
     */
    private fun collectThumbPrewarmFromWs() {
        viewModelScope.launch(Dispatchers.Default) {
            webSocketRepository.messages.collect { message ->
                if (message.type != "data_changed") return@collect
                val obj = message.data as? kotlinx.serialization.json.JsonObject ?: return@collect
                if (obj["changed"]?.jsonPrimitive?.content != "media:thumb-progress") return@collect
                val done = obj["done"]?.jsonPrimitive?.intOrNull ?: return@collect
                val total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0
                val failed = obj["failed"]?.jsonPrimitive?.intOrNull ?: 0
                val running = obj["running"]?.jsonPrimitive?.booleanOrNull ?: false
                // 带上当前条目名，设置页显示「正在处理 xxx.mkv」
                val current = obj["current_name"]?.jsonPrimitive?.contentOrNull ?: ""
                media.onThumbPrewarmProgress(done, total, failed, running, current)
            }
        }
    }

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
     * Dashboard Room 缓存必须清除：否则换设备后首屏会先闪现上一台设备的 CPU/流量。
     */
    fun prepareDeviceSwitch() {
        viewModelScope.launch {
            runCatching { dashboard.clearDashboardCache() }
            dashboard.stopAlertPolling()
            clearError()
            network.clearServiceError()
            skipConnectionNotice()
            // 新鲜度基准必须随缓存一起清：只清 Room 不清基准，新设备页面会因旧基准
            // 仍在窗口内而跳过请求，界面上保留上一台设备的信号与 WiFi 名。
            dashboard.resetFreshness()
            network.resetFreshness()
            // 播放队列快照一并清除：其中保存的是**旧设备上的文件路径**，在新设备上通常无效。
            // 不清除时换设备后音频无法播放，队列面板仍会列出上一台设备的曲目。
            AppPreferences(appContext).clearAudioQueue()
            // 让下一次 ONLINE 为新设备重新跑一轮预加载，并重新挂起启动页
            preload.reset()
            _startupPhaseDone.value = false
            startupGate()
        }
    }

    /**
     * 凭据 / 设备地址变更后重新绑定实时通道。
     *
     * HTTP 侧依靠 `RetrofitClient.dynamicHostInterceptor` 每次请求重取 baseUrl，不存在「旧地址」问题；
     * 但 WS 是**长连接**，它把 url 与 token 存为字段，必须显式替换并重连。
     *
     * 该更新必须作用于 VM 自身使用的实例：若只更新 `MainActivity` 中
     * `remember { WebSocketRepository(...) }` 取得的实例，重新配对（SetupScreen 分支重建组合）
     * 后，被更新的是从未 connect 的新实例，真正在连的实例仍使用旧 token/旧主机。
     *
     * @param wsUrl 新的 WebSocket 地址（`ws://host:port/ws/realtime` 的 base 部分）
     * @param token 新设备 token
     */
    fun onEndpointChanged(wsUrl: String, token: String) {
        webSocketRepository.updateConfig(wsUrl, token)
    }

    /**
     * 本 VM 正在使用的实时通道实例。
     *
     * Activity 的生命周期钩子（回前台重连、网络恢复重连、握手被拒回调）必须作用在**这一个**对象上：
     * 重新配对会重新组合 SetupScreen 分支，`remember { WebSocketRepository(...) }` 会创建
     * 全新且从未 connect 的实例，作用于该实例的钩子不会产生效果。
     */
    fun webSocket(): WebSocketRepository = webSocketRepository

    /**
     * 服务器地址变更后的会话刷新。
     *
     * @param hostChanged IP 或端口变化时必须丢弃缓存，避免把旧设备数据关联到新地址。
     *   同机改端口 / 重复保存同一地址时仅做轻量刷新，不出现空屏。
     */
    fun onServerEndpointChanged(hostChanged: Boolean) {
        if (hostChanged) {
            viewModelScope.launch { runCatching { dashboard.clearDashboardCache() } }
            clearError()
            // 切换的是另一台设备：与 prepareDeviceSwitch 一致，基准与预加载一并复位
            dashboard.resetFreshness()
            network.resetFreshness()
            // 队列快照里存的是旧设备的路径，换地址后必须清（见 prepareDeviceSwitch 的说明）
            AppPreferences(appContext).clearAudioQueue()
            preload.reset()
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
    // 全局错误
    // ══════════════════════════════════════════════════════════════════════
    //
    // 页面级错误提示存在三个问题：
    // 1. 「我的」页没有任何提示 —— `ServiceControlState.errorMessage`（启停/重启服务、关机、
    //    重启设备四个危险操作）持续写入但全仓零读取，失败时界面完全无反馈；
    // 2. 「工具」页自身不发请求、产不出错误，其横幅显示的是 tools 域**其它页面**的错误
    //    （ToolsState 是整个 tools 域共享的一份），无法定位错误来源；
    // 3. 同一个 errorMessage 要在 N 个页面各写一遍渲染代码，漏一个页面即静默失败。
    //
    // 因此收敛为一条流 + 一个 Activity 级宿主（见 MainActivity 的全局错误浮层）：
    // 各来源按严重度取第一个非空，浮层自动收起时回调 [dismissGlobalError] 清除该来源
    //（否则错误会一直挂在 state 上，切回页面会再次弹出陈旧错误）。
    //
    // [GlobalError] 不携带 retry lambda：否则每次 combine 都产生新实例、equals 恒为 false，
    // 宿主的 LaunchedEffect 会被每一轮轮询重启。改为只带 [GlobalErrorSource] 枚举，
    // 重试动作由来源分派 —— 值语义稳定，宿主可安全地将其作为 key。
    //
    // 采用两层 combine：`deviceSettingsState` 是第 6 个来源，而 Kotlin 的
    // `combine` 具名重载只到 5 参，第 6 个起需走 vararg 版本（元素类型被擦除为 Array<*>，
    // 取值需强转，错误无法由编译器发现）。内层先将原有 5 个按严重度排成有序列表，
    // 外层再把设备设置插入正确位置 —— 比 vararg + 下标更可读，也无需为凑足 5 个
    // 而把某个来源并入其它 state。
    private val baseGlobalErrors: Flow<List<GlobalError>> = combine(
        dashboard.dashboardState,
        network.networkState,
        dashboard.monitorState,
        tools.toolsState,
        network.serviceState
    ) { dash, net, monitor, toolsState, service ->
        // 顺序 = 严重度：设备级操作失败 > 页面数据拉取失败。
        buildList {
            service.errorMessage?.let { add(GlobalError(it, GlobalErrorSource.SERVICE)) }
            dash.errorMessage?.let { add(GlobalError(it, GlobalErrorSource.DASHBOARD)) }
            net.errorMessage?.let { add(GlobalError(it, GlobalErrorSource.NETWORK)) }
            monitor.errorMessage?.let { add(GlobalError(it, GlobalErrorSource.MONITOR)) }
            toolsState.errorMessage?.let { add(GlobalError(it, GlobalErrorSource.TOOLS)) }
        }
    }

    /**
     * 接入 `deviceSettingsState`：FOTA / 性能模式 / 指示灯 / 数据漫游 /
     * WiFi 休眠 / Samba / 定时重启这 7 个写操作会写入其 errorMessage，若无人消费，
     * 这 7 项失败时界面完全无反馈。这与 `ServiceControlState` 属于同一类缺陷。
     *
     * 严重度插在 SERVICE 之后、DASHBOARD 之前：它们都是用户主动发起的设备级写操作，
     * 比页面取数失败更需要被看到。
     *
     * 行为约定：`deviceSettingsState` 的**加载**失败（loadDeviceSettings 的 catch）
     * 同样上报 Toast。这是一致化选择 —— 其他 4 个来源的加载失败早已上报；
     * Core 升级期间的噪音由 [globalError] 出口已有的静默闸门拦截。
     *
     * 同时接入 `downloads.state`：下载模块的 14 个写操作（创建/暂停/恢复/删除/
     * 重试/重命名/清空/改配置/Tracker 刷新与保存…）会写入其 errorMessage，若无人消费，
     * 「下载设置改一项」失败时界面只显示乐观的「已保存」。
     * 新增来源时必须同步三处：枚举 + combine + dismiss 分派。
     *
     * 代价：本属性是 eager 初始化的，读 `downloads.state` 会把 `downloads` 的 by lazy 提前求值。
     * 可接受 —— `DownloadModule` 的构造只创建一个 `MutableStateFlow(DownloadState())`，
     * 不发请求、不注册监听；而 `network` / `tools` 已因同一原因在此处被提前求值。
     */
    private val rawGlobalError: Flow<GlobalError?> = combine(
        baseGlobalErrors,
        network.deviceSettingsState,
        downloads.state
    ) { base, deviceSettings, download ->
        val device = deviceSettings.errorMessage?.let {
            GlobalError(it, GlobalErrorSource.DEVICE_SETTINGS)
        }
        val downloadErr = download.errorMessage?.let {
            GlobalError(it, GlobalErrorSource.DOWNLOAD)
        }
        when {
            // SERVICE 最高（启停服务 / 关机 / 重启设备），永远优先
            base.firstOrNull()?.source == GlobalErrorSource.SERVICE -> base.first()
            device != null -> device
            // 下载排在设备设置之后、页面取数之前：也是用户主动发起的写，但不改设备配置
            downloadErr != null -> downloadErr
            else -> base.firstOrNull()
        }
    }

    /**
     * 全局错误的**唯一**出口，Core 更新期间整体静默。
     *
     * 升级 Core 时设备端 HTTP 必然中断 1 分钟左右，这期间所有取数失败
     * **均为预期结果**，不属于需要提示的故障。若出口无闸门，升级过程中会出现
     * 连续的「加载告警失败」「连接失败」—— 30s 全局告警轮询、5s 首页刷新、WS 重连
     * 各贡献一份，且 `silent=true` 只抑制 `isLoading`，不抑制 errorMessage。
     *
     * 在**出口**静默而不是修改十余个取数点：出口只有一条（此处 → MainActivity 的 Toast），
     * 取数点有十余处且会继续增加，只有改出口才不会遗漏。
     * 应停止的周期任务由 [collectCoreUpdatingGate] 暂停。
     */
    val globalError: StateFlow<GlobalError?> = combine(
        rawGlobalError,
        tools.coreUpdating
    ) { err, updating ->
        if (updating) null else err
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ── 写操作成功提示 ────────────────────────────────────────────────────────
    //
    // 与 [globalError] 同构：一条流 + 一个 Activity 级宿主。理由相同 —— 若每个页面
    // 自行维护 `var toastMessage by remember`，会重复实现 N 遍渲染代码，
    // 漏一个页面即表现为静默成功。
    //
    // 此处**不做**同文案去重（错误通道的 30s 去重用于抑制轮询刷屏）：
    // 连续保存两次应看到两次确认，去重会使第二次看起来「无反应」。由 [WriteNotice.seq] 保证。
    private val _writeNotice = MutableStateFlow<WriteNotice?>(null)
    val writeNotice: StateFlow<WriteNotice?> = _writeNotice.asStateFlow()

    /** [WriteNotice.seq] 的自增源。只在主线程（事件收集协程）里递增，不需要原子类型。 */
    private var writeNoticeSeq = 0L

    /** 宿主展示完毕后回调，清空以便下一条能进来。 */
    fun dismissWriteNotice() {
        _writeNotice.value = null
    }

    /**
     * **纯本地**写操作的成功提示入口。
     *
     * 仅用于「没有对应 module、也没有网络请求可能失败」的写操作 —— 目前只有
     * 「服务器配置 → 连接配置」的 Core IP / 端口（写入本机 `AppPreferences`）。
     * 这类操作没有失败分支，无需 module 层的成功/失败收尾，但成功提示仍必须经过
     * **同一个宿主**，否则页面内会出现私有 toast（见 [globalError] 中关于渲染代码重复的说明）。
     *
     * 有网络请求的写操作**不得**使用：它们必须在 module 中等待结果，成功后才发 notice。
     */
    fun notifyLocalWriteSucceeded(message: String, subtitle: String? = null) {
        crossModuleEventSink.tryEmit(UiEvent.ShowWriteNotice(message, subtitle))
    }

    // ── 「连不上设备」的唯一展示面：启动页 ───────────────────────────────────
    //
    // 设计约束：以下三点不得回退。
    // 1. 断线时不得同时在首页出现三处提示（顶部全局横幅 + 卡内「服务状态条」+ 模态弹窗，
    //    且横幅与弹窗来自判据不同的两套检测器）。
    // 2. 收敛为一处模态弹窗，全部派生自 [connectivity]（/health 连续探测确证，唯一权威可达性来源）。
    // 3. **不使用模态弹窗**，改由[启动页]承担：加载中显示预加载进度，连不上时在同一界面
    //    显示病因与操作入口。冷启动连不上时，一个铺满屏幕、写明病因和下一步操作的页面，
    //    比覆盖在空界面上的弹窗更清晰。
    //
    // 旧的提示链路另有缺陷：其 45s 去抖按**错误文本**分组
    //（`err.message == lastHandledMessage`），而断线时各模块会依次抛出措辞不同的错误，
    // 每换一条文本即绕过去抖，用户关闭弹窗后会立即被另一条文本再次弹出。
    // 派生状态不存在「触发」概念，同一轮掉线只有一个状态，不会重复弹出。

    /**
     * 用户选择「先进入应用」后，本轮掉线不再由启动页拦截；恢复 ONLINE 后复位，下次掉线重新拦截。
     *
     * 必须保留该出口：三种病因中有两种无法在本页解决 ——
     * 「手机未连接网络」需前往系统 WiFi 设置，「未配置设备地址」需走配对流程。
     * 缺少出口会使页面无法离开。跳过之后仪表盘卡内状态条
     * 仍显示「后端服务未连接」，信息不会丢失。
     */
    private val _connectionNoticeSkipped = MutableStateFlow(false)

    /**
     * 本轮「Core 更新中」豁免的起始时刻（0 = 当前未处于豁免）。
     *
     * 仅在 [coreUpdateExempt] 流的收集协程中读写（单协程顺序执行），
     * 不在 [startupProblem] 的变换体内修改实例字段。
     */
    private var coreUpdateExemptSince = 0L

    /**
     * 「Core 更新中」豁免当前是否生效。
     *
     * 三条上游：`coreUpdating` / `updateDeviceState` 负责「更新开始/结束」的即时响应，
     * 时间节拍负责「豁免到期」—— 更新卡死时前两条为去重的 StateFlow，不再发值，
     * 缺少节拍则永远无法算出超时（见 [CORE_UPDATE_EXEMPT_TICK_MS]）。
     */
    private val coreUpdateExempt: Flow<Boolean> = combine(
        tools.coreUpdating,
        tools.updateDeviceState,
        flow<Long> {
            while (true) {
                // 计时口径使用单调时钟（SystemClock.elapsedRealtime）而不是墙上时钟
                // （System.currentTimeMillis）：NTP 校时跳变会破坏 5 分钟豁免窗口
                //（时间回跳会导致豁免无限延长）。与 startupGate 口径一致；差值同为毫秒。
                emit(android.os.SystemClock.elapsedRealtime())
                delay(CORE_UPDATE_EXEMPT_TICK_MS)
            }
        }
    ) { _, _, nowMs ->
        if (!isCoreUpdateInterruptingBackend()) {
            coreUpdateExemptSince = 0L
            false
        } else {
            if (coreUpdateExemptSince == 0L) coreUpdateExemptSince = nowMs
            // 豁免超时（更新卡死）→ 取消豁免并照常上报，否则异常永远不会显示
            nowMs - coreUpdateExemptSince < CORE_UPDATE_EXEMPT_TIMEOUT_MS
        }
    }.distinctUntilChanged()

    /**
     * 启动页是否显示「连不上」状态及其内容。null = 正常（显示加载进度）。
     *
     * 两个条件同时满足才显示：
     * 1. [connectivity] 确证不可达（CHECKING 不计入，冷启动首屏不提示）；
     * 2. 不处于 Core 更新豁免窗口内（见 [coreUpdateExempt]）——
     *    升级 Core 时 8088 必然断联约 1 分钟，属于预期结果，不应整页上报。
     *
     * 本变换为纯函数：豁免判定与计时全在 [coreUpdateExempt] 中，
     * 因此启动页不会因「上游不再发值」而停留在某一状态。
     */
    val startupProblem: StateFlow<ConnectivityUiState?> = combine(
        connectivity,
        coreUpdateExempt
    ) { conn, exempt ->
        when {
            !conn.hasProblem -> null
            exempt -> null
            else -> conn
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Core 是否正在更新并导致后端必然不可达（在线自更新 / 本地 APK 推送安装皆算）。
     *
     * 判据两路 OR：
     * 1. 持久化标记 `core_update_in_progress`（触发更新或本地安装时写入，App 被杀仍在）；
     * 2. 内存态 [ToolsModule.updateDeviceState]：`uploading` / `installing` / `reconnecting`。
     * 任一为真即豁免；终态 done/failed 会清标记与状态（见 ToolsModule）。
     */
    private fun isCoreUpdateInterruptingBackend(): Boolean {
        if (coreUpdatePersistence.isCoreUpdating()) return true
        val st = tools.updateDeviceState.value
        // 必须包含 downloading/verifying：仅识别 uploading/installing 时，
        // 下载阶段（数十 MB，耗时最长）app 仍会弹出「无法连接到设备」。
        // 与 core 侧 UpdateManager.BUSY_STATES（DOWNLOADING/VERIFYING/INSTALLING/UPLOADING）对齐。
        return st?.state in CORE_UPDATE_BUSY_STATES || st?.reconnecting == true
    }

    /** 「先进入应用」：让启动页让开，剩下的靠仪表盘卡内状态条继续显示。 */
    fun skipConnectionNotice() {
        _connectionNoticeSkipped.value = true
    }

    /**
     * 连接恢复时复位「先进入应用」状态：否则跳过一次之后，该设备不会再由启动页拦截。
     *
     * 同时承担首屏预加载的**启动闸门**：预加载必须等 `/health` 确证在线后才能发出，
     * 否则十余个必定失败的请求会使 `HealthModule` 的「3 秒内 2 次失败」确证成立，
     * 导致启动即报连不上（见 [PreloadCoordinator] 的第 3 条约束）。
     */
    private fun collectConnectivityRecovery() {
        viewModelScope.launch {
            connectivity.collect { conn ->
                if (conn.status == ConnectivityStatus.ONLINE) {
                    _connectionNoticeSkipped.value = false
                    preload.start()
                }
            }
        }
    }

    // ── 首屏预加载 + 启动页 ───────────────────────────────────────────────────
    private val preload by lazy {
        PreloadCoordinator(viewModelScope, dashboard, network, tools)
    }

    /** 启动页那行小字与计数的数据源。 */
    val preloadProgress: StateFlow<PreloadProgress> get() = preload.progress.ui()

    /** 启动阶段（探活出结论 → 预加载发完 → 首份数据落地）是否已经走完。 */
    private val _startupPhaseDone = MutableStateFlow(false)

    /**
     * 启动页是否仍处于显示状态。
     *
     * 两种显示理由合并为一个布尔：
     * 1. **启动阶段未完成** —— 正常的「正在加载」。
     * 2. **连不上设备** —— 承担原模态弹窗的职责。用户选择「先进入应用」后
     *    可跳过（[skipConnectionNotice]），恢复 ONLINE 后自动复位。
     *
     * 该状态保存在 ViewModel 而不是 Activity 的 `remember`：VM 跨 Activity 重建存活，
     * 因此旋转屏幕 / 从最近任务返回不会重新显示启动页（参考实现以
     * `hasCompletedFirstRefresh` 达到同样效果）。
     */
    val startupOverlayVisible: StateFlow<Boolean> = combine(
        _startupPhaseDone,
        startupProblem,
        _connectionNoticeSkipped
    ) { done, problem, skipped ->
        when {
            !done -> true
            problem != null && !skipped -> true
            else -> false
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * 启动阶段的收尾条件。**该条件只负责结束「加载中」状态**，
     * 连不上时页面继续显示由 [startupOverlayVisible] 负责。
     *
     * ## 不以「预加载全部完成」为判据
     * [PreloadProgress.finished] 只代表所有步骤**已发起**（module 中的 `loadXxx()` 为
     * fire-and-forget），此时回包可能尚未落地，直接放行会显示一屏骨架。因此叠加
     * **数据就绪**判据：仪表盘已取得 `deviceInfo`（全站唯一的骨架屏判据）。
     *
     * ## 每条路径都必须有终局
     * 拦截操作既不算成功也不算失败；若某条分支静默返回，调用方无法得知本次刷新已结束，
     * **加载动画会永远停留在「请稍候」**。
     * 此处有三条终局：
     * 1. 预加载步骤发完 **且** `deviceInfo != null`；
     * 2. `connectivity` 确证**不可达**（不启动预加载，直接结束「加载中」，
     *    页面随即切换到「连不上」状态）；
     * 3. [STARTUP_GATE_TIMEOUT_MS] 兜底 —— 上述两条均未成立时也必须收尾。
     *
     * 另有 [STARTUP_GATE_MIN_MS] 最短展示：局域网内预加载可能在几百毫秒内完成，
     * 不设下限时启动页会一闪而过，观感差于不显示。
     */
    private fun startupGate() {
        // 重进闸门（切设备）必须先取消上一条：旧协程可能仍阻塞在 8s 超时或最短展示的 delay 上，
        // 其恢复后的 `_startupPhaseDone = true` 会提前关闭新设备的启动页。
        startupGateJob?.cancel()
        startupGateJob = viewModelScope.launch {
            val begunAt = SystemClock.elapsedRealtime()
            withTimeoutOrNull(STARTUP_GATE_TIMEOUT_MS) {
                // 先等探活出结论；CHECKING 阶段什么都不做（冷启动首屏本来就在这一档）
                val settled = connectivity.first { it.status != ConnectivityStatus.CHECKING }
                if (settled.status != ConnectivityStatus.ONLINE) return@withTimeoutOrNull
                preload.start()
                preload.progress.first { it.finished }
                // 步骤发完 ≠ 数据到齐，再等骨架屏判据消失
                dashboard.dashboardState.first { it.deviceInfo != null }
            }
            val elapsed = SystemClock.elapsedRealtime() - begunAt
            if (elapsed < STARTUP_GATE_MIN_MS) delay(STARTUP_GATE_MIN_MS - elapsed)
            _startupPhaseDone.value = true
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
            GlobalErrorSource.DEVICE_SETTINGS -> network.clearDeviceSettingsError()
            GlobalErrorSource.DOWNLOAD -> downloads.clearError()
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