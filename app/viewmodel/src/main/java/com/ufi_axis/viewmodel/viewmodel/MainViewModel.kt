package com.ufi_axis.viewmodel

import android.content.Context
import android.os.SystemClock
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
 * 兜底用，不是常规路径 —— 预加载正常情况下 2 秒内跑完。它防的是"某一步挂住 120s
 *（OkHttp readTimeout）导致启动页永远不消失"。宁可让用户进到一个数据还没齐的界面，
 * 也不能把人锁在加载页上。
 */
private const val STARTUP_GATE_TIMEOUT_MS = 8_000L

/**
 * 启动页最短展示时间（ms）。
 *
 * 局域网上预加载可能几百毫秒就完事，不设下限时启动页会"闪一下" —— 比不显示更难看。
 */
private const val STARTUP_GATE_MIN_MS = 600L

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
 * 写操作成功提示（2026-09-22）。与 [GlobalError] **对称**的成功通道。
 *
 * [seq] 是自增序号，唯一作用是**让同一条文案能连续触发两次**：
 * 全局错误有 30s 同文案去重（防轮询刷屏），但成功提示不能去重 ——
 * 用户连着保存两次就该看到两次确认。少了 seq，第二次的 `WriteNotice` 与第一次 equals 相等，
 * 宿主的 `LaunchedEffect(notice)` 不会重新触发，表现就是"第二次点保存没反应"。
 */
data class WriteNotice(
    val message: String,
    val subtitle: String? = null,
    val seq: Long
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
 * @param title  一句话病因（弹窗标题）；[ConnectivityStatus.ONLINE]/[ConnectivityStatus.CHECKING] 时为 null
 * @param detail 下一步动作的指引（弹窗正文）
 */
data class ConnectivityUiState(
    val status: ConnectivityStatus,
    val title: String? = null,
    val detail: String? = null
) {
    /** 是否处于「连不上」状态。CHECKING 不算 —— 没结论就别吓用户。 */
    val hasProblem: Boolean
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

    // 跨模块 UI 事件汇聚点（eager 创建、开销极小）：各 module 构造时接收该 sink，
    // 把自己的事件转发/直投到此。collectCrossModuleEvents 仅订阅本 sink，
    // 不会在构造期触发 network / tools 的 by lazy 求值（修复冷启动 eager 加载，风险 #6）。
    //
    // 声明位置必须在 [dashboard] **之前**：Kotlin 按声明顺序初始化属性，
    // 而 dashboard 是 eager 的，构造时就要拿到这个 sink；顺序反了传进去的是 null。
    private val crossModuleEventSink = MutableSharedFlow<UiEvent>(extraBufferCapacity = 64)

    val dashboard = DashboardModule(
        api, webSocketRepository, networkMonitor, appContext, viewModelScope, alertPrefs,
        // 2026-09-22：监控设置四个二级页的写结果要能到全局提示出口，所以 dashboard 也拿这个 sink。
        crossModuleEventSink = crossModuleEventSink
    )

    /**
     * 后端健康检查（周期探活 + 出错时即时复查）。[viewModelScope] 内常驻，供 UI 判断后端是否在线、
     * 以及数据加载失败时是否因后端挂了。见 [HealthModule]。
     */
    val health = HealthModule(api, viewModelScope)

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
    val downloads by lazy { DownloadModule(appContext, viewModelScope, crossModuleEventSink) }
    val tunnel by lazy { TunnelModule(appContext, viewModelScope) }
    val backup by lazy { BackupModule(api) }

    /**
     * 标题栏天气小功能（2026-09-17）：数据来自 core 的 `/api/weather`（它代理 Open-Meteo）。
     *
     * by lazy：默认是关的，只有开了开关的用户才会有人访问它。
     */
    val weather by lazy { WeatherModule(api, viewModelScope, crossModuleEventSink) }

    /**
     * 标题栏今日诗词（2026-09-18）：数据来自 core 的 `/api/poetry`（它代理 jinrishici v2）。
     *
     * by lazy 同上：默认关闭。挑诗的智能匹配在上游，本模块只负责取数与节流。
     */
    val poetry by lazy { PoetryModule(api, viewModelScope, crossModuleEventSink) }

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

    /**
     * 启动页闸门协程（见 [startupGate]）。
     *
     * 必须**声明在 init 之前**：init 里就会调 [startupGate] 并给它赋值，
     * 若声明在 init 之后，那句 `= null` 的初始化会把刚存的引用又抹掉。
     */
    private var startupGateJob: Job? = null

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
                    is UiEvent.ShowWriteNotice -> {
                        _writeNotice.value = WriteNotice(
                            message = event.message,
                            subtitle = event.subtitle,
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
            skipConnectionNotice()
            // 新鲜度戳必须跟着缓存一起清：只清 Room 不清戳，新设备的页面会因为
            // 旧设备的戳还在窗口内而跳过请求，屏幕上留着上一台的信号与 WiFi 名。
            dashboard.resetFreshness()
            network.resetFreshness()
            // 播放队列快照一起清：里面存的是**旧设备上的文件路径**，在新设备上基本不成立。
            // 不清的话换设备后音乐全放不出来，队列面板还列着一堆上一台设备的歌。
            AppPreferences(appContext).clearAudioQueue()
            // 让下一次 ONLINE 为新设备重新跑一轮预加载，并重新挂起启动页
            preload.reset()
            _startupPhaseDone.value = false
            startupGate()
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
            // 换的是另一台设备：同 prepareDeviceSwitch，戳与预加载一并复位
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
    // 重试动作由来源分派 —— 值语义干净，宿主可以安心用它当 key。
    //
    // 2026-09-22：拆成两层 combine。`deviceSettingsState` 是第 6 个来源，而 Kotlin 的
    // `combine` 具名重载只到 5 参，第 6 个起要走 vararg 版本（元素类型被擦成 Array<*>，
    // 取值得强转，写错了编译器也不报）。内层先把原来的 5 个按严重度排成有序列表，
    // 外层再把设备设置插到正确位置 —— 比 vararg + 下标可读得多，也不必为了凑 5 个
    // 把某个来源塞进别人的 state。
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
     * 2026-09-22 新接入 `deviceSettingsState`：FOTA / 性能模式 / 指示灯 / 数据漫游 /
     * WiFi 休眠 / Samba / 定时重启这 7 个写操作**一直在往它的 errorMessage 写、却零消费** ——
     * 全仓没有一处读它，于是这 7 项失败时界面完全没反馈。这正是本段注释开头说的
     * `ServiceControlState` 同一个坑，换了个 state 复发。
     *
     * 严重度插在 SERVICE 之后、DASHBOARD 之前：它们都是用户主动发起的设备级写操作，
     * 比页面取数失败更需要被看到。
     *
     * 附带的行为变化：`deviceSettingsState` 的**加载**失败（loadDeviceSettings 的 catch）
     * 原来也是静默的，现在会一起冒 Toast。这是刻意的一致化 —— 其他 4 个来源的加载失败
     * 早就在弹了；Core 升级期间的噪音由 [globalError] 出口已有的静默闸门兜住。
     *
     * 2026-09-22 同轮接入 `downloads.state`：下载模块的 14 个写操作（创建/暂停/恢复/删除/
     * 重试/重命名/清空/改配置/Tracker 刷新与保存…）**一直在写它的 errorMessage、同样零消费**
     * —— 全仓没有一处读，于是「下载设置改一项」失败时界面只弹一句乐观的「已保存」。
     * 这是 `deviceSettingsState` 那个坑的第三次复发，按 §4.8 一次接全（枚举 + combine +
     * dismiss 分派三处同步）。
     *
     * 代价：本属性是 eager 初始化的，读 `downloads.state` 会把 `downloads` 的 by lazy 提前求值。
     * 可接受 —— `DownloadModule` 的构造只是 new 一个 `MutableStateFlow(DownloadState())`，
     * 不发请求、不注册监听；而 `network` / `tools` 早就因为同一个原因被这里提前求值了。
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

    // ── 写操作成功提示（2026-09-22）──────────────────────────────────────────
    //
    // 与 [globalError] 同构：一条流 + 一个 Activity 级宿主。理由也一样 —— 让每个页面
    // 自己 `var toastMessage by remember` 就会抄 N 遍渲染代码，漏一个页面就变成静默成功。
    //
    // 这里刻意**不做**同文案去重（错误通道有 30s 去重是为了压住轮询刷屏）：
    // 连续保存两次就该看到两次确认，去重会让第二次看起来"没反应"。由 [WriteNotice.seq] 保证。
    private val _writeNotice = MutableStateFlow<WriteNotice?>(null)
    val writeNotice: StateFlow<WriteNotice?> = _writeNotice.asStateFlow()

    /** [WriteNotice.seq] 的自增源。只在主线程（事件收集协程）里递增，不需要原子类型。 */
    private var writeNoticeSeq = 0L

    /** 宿主展示完毕后回调，清空以便下一条能进来。 */
    fun dismissWriteNotice() {
        _writeNotice.value = null
    }

    /**
     * **纯本地**写操作的成功提示入口（2026-09-22）。
     *
     * 只给"没有任何 module 负责、也没有网络请求可以失败"的那类写用 —— 目前只有
     * 「服务器配置 → 连接配置」的 Core IP / 端口（它写的是本机 `AppPreferences`）。
     * 这类操作没有失败分支，所以不需要 module 层的成功/失败收尾，但成功提示仍然要走
     * **同一个宿主**，否则又会在页面里长出一份私有 toast（见 [globalError] 那段注释里
     * 反复出现的"抄 N 遍就会漏"）。
     *
     * 有网络请求的写操作**不要**用它：那些必须在 module 里等结果，成功才发 notice。
     */
    fun notifyLocalWriteSucceeded(message: String, subtitle: String? = null) {
        crossModuleEventSink.tryEmit(UiEvent.ShowWriteNotice(message, subtitle))
    }

    // ── 「连不上设备」的唯一展示面：启动页（2026-09-22 二次收敛）────────────────
    //
    // 演进三步，后人别再倒退回前两步：
    // 1. 断线时首页同时出现三处提示 —— 顶部全局横幅 + 卡内「服务状态条」+ 模态弹窗，
    //    且横幅与弹窗来自两套判据不同的检测器。
    // 2. 收敛成一处模态弹窗，全部派生自 [connectivity]（/health 连击确证，唯一权威可达性）。
    // 3. **弹窗也撤掉**，改由[启动页]承担：加载中显示预加载进度，连不上就在同一个面上
    //    显示病因 + 动作。理由是用户的原话「我更需要这个来显示」——
    //    冷启动连不上时，一个铺满屏幕、写清病因和下一步的页面比一个盖在空界面上的弹窗清楚。
    //
    // 顺带修掉旧链的一个真实缺陷：它的 45s 去抖按**错误文本**分组
    //（`err.message == lastHandledMessage`），而断线时各模块会依次抛出措辞不同的错误，
    // 每换一条文本就绕过去抖 —— 用户点掉弹窗后马上会被另一条文本再弹一次。
    // 派生态没有"触发"的概念，同一轮掉线只有一个状态，不存在重复弹。

    /**
     * 用户点了「先进入应用」：本轮掉线不再用启动页挡着。恢复 ONLINE 后复位，下次掉线重新挡。
     *
     * 必须留这个出口：三种病因里有两种不是在这个页面里能解决的 ——
     * 「手机未连接网络」要去系统 WiFi 设置、「未配置设备地址」要走配对流程。
     * 没有出口就等于把用户锁在一个按不动的页面上。让开之后仪表盘卡内状态条
     * 仍然显示「后端服务未连接」，信息不会丢。
     */
    private val _connectionNoticeSkipped = MutableStateFlow(false)

    /**
     * 本轮「Core 更新中」豁免的起始时刻（0 = 当前未处于豁免）。
     *
     * 只在 [coreUpdateExempt] 那条流的收集协程里读写（单协程顺序执行），
     * 不再在 [startupProblem] 的变换体里改实例字段。
     */
    private var coreUpdateExemptSince = 0L

    /**
     * 「Core 更新中」豁免此刻是否仍然生效。
     *
     * 三条上游：`coreUpdating` / `updateDeviceState` 负责"更新开始/结束"的即时反应，
     * 时间节拍负责"豁免到期"—— 更新卡死时前两条是去重的 StateFlow，不会再发值，
     * 没有节拍就永远算不出超时（见 [CORE_UPDATE_EXEMPT_TICK_MS]）。
     */
    private val coreUpdateExempt: Flow<Boolean> = combine(
        tools.coreUpdating,
        tools.updateDeviceState,
        flow<Long> {
            while (true) {
                emit(System.currentTimeMillis())
                delay(CORE_UPDATE_EXEMPT_TICK_MS)
            }
        }
    ) { _, _, nowMs ->
        if (!isCoreUpdateInterruptingBackend()) {
            coreUpdateExemptSince = 0L
            false
        } else {
            if (coreUpdateExemptSince == 0L) coreUpdateExemptSince = nowMs
            // 豁免超时（更新卡死）→ 不再豁免，照常报，否则用户永远看不到异常
            nowMs - coreUpdateExemptSince < CORE_UPDATE_EXEMPT_TIMEOUT_MS
        }
    }.distinctUntilChanged()

    /**
     * 启动页当前要不要显示「连不上」那一态，以及显示什么。null = 正常（显示加载进度）。
     *
     * 两道闸：
     * 1. [connectivity] 确证不可达（CHECKING 不算，冷启动首屏不吓人）；
     * 2. 不在 Core 更新豁免窗内（见 [coreUpdateExempt]）——
     *    升级 Core 时 8088 必然断联约 1 分钟，那是预期结果，不该拿一整页去报。
     *
     * 这里的变换是纯函数：豁免的判定与计时全在 [coreUpdateExempt] 里，
     * 所以启动页不会因为"上游不再发值"而卡在某一态。
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
        return st?.state in setOf("uploading", "installing") || st?.reconnecting == true
    }

    /** 「先进入应用」：让启动页让开，剩下的靠仪表盘卡内状态条继续显示。 */
    fun skipConnectionNotice() {
        _connectionNoticeSkipped.value = true
    }

    /**
     * 连接恢复时复位「先进入应用」：否则让开过一次之后，这台设备再也不会用启动页挡。
     *
     * 顺带承担首屏预加载的**启动闸门**：预加载必须等 `/health` 确证在线才能发，
     * 否则十来个必定失败的请求会自己把 `HealthModule` 的"3 秒内 2 次失败"确证坐实，
     * 变成开机就报连不上（见 [PreloadCoordinator] 的第 3 条约束）。
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

    // ── 首屏预加载 + 启动页（2026-09-22）──────────────────────────────────────
    private val preload by lazy {
        PreloadCoordinator(viewModelScope, dashboard, network, tools)
    }

    /** 启动页那行小字与计数的数据源。 */
    val preloadProgress: StateFlow<PreloadProgress> get() = preload.progress

    /** 启动阶段（探活出结论 → 预加载发完 → 首份数据落地）是否已经走完。 */
    private val _startupPhaseDone = MutableStateFlow(false)

    /**
     * 启动页是否还挡着。
     *
     * 两种挡的理由，合并成一个布尔：
     * 1. **启动阶段没走完** —— 正常的"正在加载"。
     * 2. **连不上设备** —— 这一条就是原来那个模态弹窗的职责。用户点「先进入应用」
     *    可以让开（[skipConnectionNotice]），恢复 ONLINE 后自动复位。
     *
     * 存在 ViewModel 而不是 Activity 的 `remember`：VM 跨 Activity 重建存活，
     * 所以旋转屏幕 / 从最近任务回来不会再看一遍启动页（参考实现那边用
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
     * 启动阶段的收尾条件。**注意它只负责「加载中」那一态的结束**，
     * 连不上时页面继续挡着是 [startupOverlayVisible] 那边的事。
     *
     * ## 为什么不是"等预加载全部完成"
     * [PreloadProgress.finished] 只代表所有步骤**已发起**（module 里那些 `loadXxx()` 是
     * fire-and-forget），此刻回包可能还没落地，放行就会看到一屏骨架。所以再叠一个
     * **数据就绪**判据：仪表盘拿到 `deviceInfo`（那是全站唯一的骨架屏判据）。
     *
     * ## 为什么每条路都必须有终局
     * 参考实现（UFITOOLS-Widget）在 ViewModel 注释里记过这个坑：拦停既不算成功也不算失败，
     * 若某条分支静默返回，调用方就无从得知本次刷新已结束，**加载动画会永远停在"请稍候"**。
     * 这里的三条终局：
     * 1. 预加载步骤发完 **且** `deviceInfo != null`；
     * 2. `connectivity` 确证**不可达**（不启动预加载，直接结束"加载中"，
     *    页面随即切到「连不上」那一态）；
     * 3. [STARTUP_GATE_TIMEOUT_MS] 兜底 —— 上面两条都没成立也必须收尾。
     *
     * 另有 [STARTUP_GATE_MIN_MS] 最短展示：局域网上预加载可能几百毫秒就完事，
     * 不设下限的话启动页会"闪一下"，比不显示更难看。
     */
    private fun startupGate() {
        // 重进闸门（切设备）必须先取消上一条：旧协程可能还卡在 8s 超时或最短展示的 delay 上，
        // 它醒来后那句 `_startupPhaseDone = true` 会把新设备的启动页提前放掉。
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