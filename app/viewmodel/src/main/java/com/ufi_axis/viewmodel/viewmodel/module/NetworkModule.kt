// TODO(F25): God-class 计划内拆分（DownloadManager 1094 / GoformClient 615 / Aria2Engine 577 / BackendService 570 / NetworkModule 921 / FileManagerScreen 879）。本类仅做最小安全抽取（见 GoformCodec），全量拆分需人工评审 + 编译验证。
package com.ufi_axis.viewmodel.module

import android.content.Context
import android.os.SystemClock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.CellInfoResponse
import com.ufi_axis.data.model.ModeRequest
import com.ufi_axis.data.model.SignalInfo
import com.ufi_axis.data.model.WifiAclResponse
// 制式别名 ↔ 设备 BearerPreference 的换算与「切换中」回读预算都在 contract，两端同源
import com.ufi_axis_core.contract.NetworkMode
// 2026-10-05 P5：smartRefresh 消费 RECONNECTED sentinel（contract 唯一取值表）
import com.ufi_axis_core.contract.WsDataTopic
import com.ufi_axis.util.AppJson
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import com.ufi_axis.util.OkHttpClientProvider
import com.ufi_axis.viewmodel.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * 测速瞬时速率的输出间隔（毫秒）。
 * 150ms ≈ 6~7 次/秒：足够让仪表盘指针看起来连续，又不会因为每读 64KB 就发一次 State
 * 把主线程的重组压满。
 */
private const val SPEED_EMIT_INTERVAL_MS = 150L

// ── 智能时长（2026-08-26）：不再让用户挑秒数，按"速率是否稳定"自动收尾 ──
/** 最短测速时长：低于这个时间不允许早停（要跨过 TCP 慢启动） */
private const val SPEED_MIN_DURATION_SEC = 5
/** 内网硬上限：局域网起速快、抖动小，够了 */
private const val SPEED_MAX_DURATION_INTERNAL_SEC = 15
/** 外网硬上限：跨公网需要更长时间才稳 */
private const val SPEED_MAX_DURATION_EXTERNAL_SEC = 25
/** 稳定性判定窗口：最近 N 个采样（N × 150ms） */
private const val SPEED_STABLE_WINDOW = 8
/** 稳定性判定阈值：窗口内相对偏差都小于此值即认为已稳定，可以提前收尾 */
private const val SPEED_STABLE_TOLERANCE = 0.06

// ── 测量方法论（2026-08-26 第三轮，参考 LibreSpeed 的做法，未引入其代码）──
/**
 * 单方向并发流数。单条 TCP 流的吞吐受 RTT × 窗口限制，跨公网时常常只能跑到链路的一小半；
 * 多条并发流才能压满带宽。core 侧并发位已同步提到 12。
 */
private const val SPEED_STREAMS = 4

/**
 * 起速爬坡段：这段时间内的采样只画曲线，**不计入平均与峰值**。
 * TCP 慢启动 + 服务端预热会让前 1~2s 的瞬时速率明显偏低，算进平均就是系统性低估。
 */
private const val SPEED_RAMP_UP_MS = 2_000L
/** 延迟/抖动探针次数：取中位数当延迟，相邻差的平均绝对值当抖动 */
private const val SPEED_PING_COUNT = 10
/** 探针连续失败这么多次就放弃：站点不可达时不要把剩下的超时全跑完 */
private const val SPEED_PING_MAX_FAILURES = 3
/** 整个探针阶段的总预算：任何单次探针卡住都不能把测速拖死 */
private const val SPEED_PING_TOTAL_BUDGET_MS = 6_000L
/** 探针响应体最多允许读掉这么多字节（只为让连接可复用），超过就直接关闭 */
private const val PROBE_DRAIN_MAX_BYTES = 64L

// ── Web 面板资源上传 / 轮询（2026-08-30）──
/** 面板 ZIP 上传上限，与 core 的 `WebUpdateRoutes.MAX_ZIP_UPLOAD` 一致（50MB）。 */
private const val WEB_ZIP_MAX_BYTES = 50L * 1024 * 1024
/** 更新状态轮询间隔。本组端点没有 WS 推送，只能轮询。 */
private const val WEB_POLL_INTERVAL_MS = 1_500L
/** 轮询次数上限（× 间隔 ≈ 90s）：core 卡在 downloading 时不能让协程一直转下去。 */
private const val WEB_POLL_MAX_TICKS = 60
/** 探针阶段占总进度的比例 */
private const val LATENCY_PROGRESS_SPAN = 0.05f
private const val HTTP_PARTIAL_CONTENT = 206
/** 仪表盘读数的中位数平滑窗口：抹掉单个采样窗口的毛刺，又不像均值那样迟滞 */
private const val SPEED_MEDIAN_WINDOW = 5
/** 上行测速时长上限（内外网同用；外网需节点部署了 `POST /upload`） */
private const val SPEED_UPLOAD_MAX_SEC = 10
/** 上行最短时长：比下行短一些，够跨过慢启动即可 */
private const val SPEED_UPLOAD_MIN_SEC = 4
private const val SPEED_READ_BUFFER_BYTES = 64 * 1024
private const val SPEED_UPLOAD_CHUNK_BYTES = 64 * 1024
/**
 * 外网上行**单个 POST** 的字节上限（32MiB）。
 * 边缘平台对单请求体有硬上限（Cloudflare Workers/Pages 免费版 100MB 一档），超了直接 413；
 * 分段发既绕开上限又不损失吞吐（连接复用，每段只多一个 RTT）。见 [NetworkModule.uploadExternal]。
 */
private const val SPEED_UPLOAD_EXTERNAL_POST_BYTES = 32L * 1024 * 1024
/** 有上行阶段时，下行占总进度的比例（其余留给上行） */
private const val DOWNLOAD_PROGRESS_SPAN = 0.7f
private val SPEED_UPLOAD_MEDIA_TYPE = "application/octet-stream".toMediaType()

/**
 * 改 WiFi 热点配置**会踢掉当前连接**的提示文案（2026-09-22）。
 *
 * 只有一份，因为同一句话要出现在两个地方：保存**之前**弹窗里常驻的警告块，和保存成功后
 * Toast 的副标题。两处各写一遍必然会漂，而这句话的准确性直接决定用户会不会把"设备掉线"
 * 当成"保存失败"。
 *
 * 为什么必须在**点击之前**就说：用户很可能正通过这个热点连着设备 —— 热点一重启，
 * 后续的失败提示物理上就送不到他眼前了。
 */
const val WIFI_RESTART_DISCONNECT_HINT =
    "保存后热点会重启，当前通过这个 WiFi 连接的设备（包括本机）会短暂断开"

/**
 * 切换 WiFi 频段（`POST /api/wifi/band`）的后果说明（2026-09-22）。
 *
 * 与 [WIFI_RESTART_DISCONNECT_HINT] 分成两条而不是复用一条：这条动作的后果**多一项** ——
 * 设备命令 `switchWiFiChip&ChipEnum=X&GuestEnable=0` 的语义是「**在该频段上启用 WiFi**」，
 * 所以除了断连，原本关着的 WiFi 还会被打开。把这一项混进"保存热点配置"的提示里，
 * 要么漏掉、要么让只改 SSID 的用户以为自己会把 WiFi 打开。
 *
 * 只有一份，因为同一句话要出现在两个地方：切换**之前**的确认弹窗正文，和切换成功后的
 * Toast 副标题。用户很可能正通过这个热点连着设备 —— 提示必须在他点下去之前就到位。
 */
const val WIFI_BAND_SWITCH_DISCONNECT_HINT =
    "这会重启设备的 WiFi 模块并在该频段上打开 WiFi，当前通过 WiFi 连接的设备（包括本机）会断开，需要重新连接"

class NetworkModule(
    private val api: UfiAxisApi,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val crossModuleEventSink: MutableSharedFlow<UiEvent>
) {
    // ── State ──
    private val _networkState = MutableStateFlow(NetworkState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val _deviceSettingsState = MutableStateFlow(DeviceSettingsState())
    val deviceSettingsState: StateFlow<DeviceSettingsState> = _deviceSettingsState.asStateFlow()

    /** 后端服务控制（后台采集开关 / 完全重启 / 开机自启）。 */
    private val _serviceState = MutableStateFlow(ServiceControlState())
    val serviceState: StateFlow<ServiceControlState> = _serviceState.asStateFlow()

    /** core 的 Web 控制面板资源（内置 / 上传的 override）。 */
    private val _webAssetState = MutableStateFlow(WebAssetState())
    val webAssetState: StateFlow<WebAssetState> = _webAssetState.asStateFlow()

    private val _speedTestState = MutableStateFlow(SpeedTestState())
    val speedTestState: StateFlow<SpeedTestState> = _speedTestState.asStateFlow()

    /** 定时测速历史（2026-10-06 批C 对齐：web 弹窗已有，app 补消费）。空列表 = 无记录或未拉取。 */
    private val _speedTestHistory = MutableStateFlow<List<SpeedTestHistoryEntry>>(emptyList())
    val speedTestHistory: StateFlow<List<SpeedTestHistoryEntry>> = _speedTestHistory.asStateFlow()

    private val _pairingState = MutableStateFlow(PairingState())
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    // ── 设备能力集（2026-09-25 批 O，计划书 §7 阶段 3 的 3.5）───────────────────
    //
    // 放在本 module 而不是 MainViewModel：10 个功能域里有 8 个的写操作就在这个文件里
    // （FOTA / Samba / 性能模式 / 制式 / 承载偏好 / 锁频 / 锁站 / USB 调试），
    // 门禁判据跟写操作放在一起才不会出现"能力集在 A 处、开关在 B 处"的两地维护。
    //
    // 初值是 UNKNOWN（= 全部支持），所以在拿到能力集之前 UI 与 3.5 之前一字不差。
    private val _capabilityState = MutableStateFlow(DeviceCapabilityState.UNKNOWN)
    val capabilityState: StateFlow<DeviceCapabilityState> = _capabilityState.asStateFlow()

    // 能力集请求的在途协程。用来保证同一时刻只有一个请求 ——
    // 拉失败后允许下一次调用重试，没有这个闸门的话四个页面同时进来就会打四发。
    private var capabilityJob: Job? = null

    private var refreshJob: Job? = null

    /**
     * 制式切换 +「切换中」回读的协程。
     *
     * 单独持有是为了让**连点两个档位**时前一次的回读立刻停掉：不取消的话两个循环会同时
     * 往 `_deviceSettingsState` 写回读结果，界面会在两个目标档位之间跳。
     */
    private var modeSwitchJob: Job? = null

    // ── Cross-module Events ──
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    // 跨模块事件转发：模块内 _events 统一汇聚到 MainViewModel 持有的 sink，
    // 避免 collectCrossModuleEvents 在构建期触发本模块的 by lazy 求值（冷启动优化 #6）。
    // viewModelScope 随 ViewModel 销毁而取消，本 forward 协程一并结束，无泄漏。
    init {
        scope.launch {
            _events.collect { event -> crossModuleEventSink.tryEmit(event) }
        }
    }

    private fun emitDashboardError(msg: String?) {
        _events.tryEmit(UiEvent.ShowDashboardError(msg))
    }

    /**
     * 写操作成功提示（2026-09-22）。走跨模块事件 → MainViewModel 的 writeNotice 流 →
     * Activity 级 Toast 宿主，与失败提示同一个出口。
     *
     * 文案要说清**是哪一项**生效了：一个界面上往往有多个写操作，「保存成功」这种泛化文案
     * 让用户分不清生效的是哪个。
     */
    /**
     * 这次异常是不是「连不上」——即设备正在重启热点/WiFi 模块，请求等不到响应。
     *
     * 判据只看连接层（[java.net.ConnectException] / `SocketTimeout` / `UnknownHost` 等），
     * 不看 HTTP 状态码：4xx/5xx 是 core 或设备明确回了原因，那是真的失败，必须报出来。
     * 2026-10-12：设备重启热点实测要 15~20 秒，这期间任何「等确认再定成败」的窗口都会
     * 撞在重启期里，所以这类断连一律按「已提交」处理，不当成失败。
     */
    private fun isConnectivityFailure(e: Exception): Boolean {
        val chain = generateSequence<Throwable>(e) { it.cause }.take(6).toList()
        return chain.any { c ->
            c is java.net.ConnectException ||
                c is java.net.SocketTimeoutException ||
                c is java.net.UnknownHostException ||
                c is java.net.NoRouteToHostException
        } || chain.any { it.message?.contains("Failed to connect", ignoreCase = true) == true }
    }

    private fun emitWriteNotice(message: String, subtitle: String? = null) {
        _events.tryEmit(UiEvent.ShowWriteNotice(message, subtitle))
    }

    /** 清设备设置的错误（全局错误浮层收起时由 MainViewModel.dismissGlobalError 分派过来）。 */
    fun clearDeviceSettingsError() {
        _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = null)
    }

    // ── 2026-10-05 P7：写操作确认器（「执行 → 等待结果 → 回读确认 → 更新」）──
    // 状态机在 viewmodel 层（供各 Module 复用），UI 侧挂 UfiOperationProgressDialog 消费 state。
    //
    // 2026-10-14（修 G8）：把失败文案接到既有 toast 通道上。用户点「后台进行」之后弹窗就没了，
    // 超时那一刻**不能**把弹窗弹回来（那与"别挡着我"正好相反），但也不能静默 ——
    // 唯一的出口就是这里：ShowWriteNotice 与其它写操作成功提示同一个出口（Activity 级 Toast）。
    //
    // **为什么不用 `errorMessage` 那条（全局错误）通道**：① 它带 30s 同文案去重
    // （`MainActivity.kt:80,573`），而"超时不能再静默"要的正是**每次都说得出来**；
    // ② 它是 state 上的一个字段，写进去之后会被紧随其后的 refreshNetwork() 用 null 覆盖
    // （失败回读里就有一发 refreshNetwork），两个写入挤在一帧里会被 StateFlow 合并掉，
    // 变成"偶尔不弹"。ShowWriteNotice 是一次性事件流（带自增 seq），不存在这两个问题。
    // 已知代价：宿主把它渲染成 SUCCESS 样式的 Toast（`MainActivity.kt:595` 写死 ToastType.SUCCESS），
    // 而 web 同一句用的是 warning。**文案本身已经说清"未完成"**，改样式需要动 `app/src/**`
    //（本轮写权限之外），故只在此登记，不改。
    val opController = ApiOperationController(scope) { reason -> emitWriteNotice(reason) }

    // ── Network ──

    /**
     * 网络页整套数据最近一次**完整成功**落地的时刻（单调时钟，`0` = 本进程内从未成功）。
     * 判据窗口是 [NETWORK_ALL_FRESH_MS]。单调时钟的理由见 `DataFreshness.kt`。
     */
    @Volatile private var networkAllSuccessElapsed: Long = 0L

    /**
     * 2026-10-05 P6：最近一次外部变更信号的单调时刻（0 = 无）。
     * 见 [loadNetworkAll] 闸门豁免；由 MainViewModel.dispatchChanged 对网络相关前缀调用 [markExternalChange]。
     */
    @Volatile var externalChangeElapsed: Long = 0L
        private set

    /** 2026-10-05 P6：收到外部变更信号时由 MainViewModel.dispatchChanged 调用。 */
    fun markExternalChange() { externalChangeElapsed = SystemClock.elapsedRealtime() }

    /**
     * 网络页需要的那一整套数据（6 个请求）。
     *
     * ## 为什么这个扇出在 module 而不在页面里
     * 原来它是 `NetworkScreen` 里的私有 `loadNetworkAll(viewModel)`，页面每次 `pageForeground`
     * 翻 true 就无条件发 6 个请求。现在预加载协调器也要发同一批，判据（新鲜度戳）只能有一份，
     * 而戳存在 module 里才能跨组合存活 —— 放在 composable 的 `remember` 里换页往复就丢了。
     *
     * ## [force] 与 [silent]
     * - [force]：绕过新鲜度窗口。写后回读、用户手动重试走这条。
     * - [silent]：失败**不写** `errorMessage`。预加载专用 —— 那一批请求发生在用户没看着的页面上，
     *   把失败写进 `networkState.errorMessage` 会经 `MainViewModel.rawGlobalError` 冒成全局 Toast，
     *   表现为"刚开 app 就莫名弹一句加载失败"。页面自己的调用一律 `silent = false`。
     *
     * 新鲜度只看 `signalInfo`：它由 [refreshNetwork] 这条主请求产出，也是本页 hero 卡的主数据。
     * 其余 5 项任一失败时字段保持 null，页面那条"出错 5s 后重试"的效应会补。
     */
    fun loadNetworkAll(force: Boolean = false, silent: Boolean = false) {
        // 能力集借这一趟一起预热（它自带"本进程只成功拉一次"的闸门，所以摆在新鲜度门之前
        // 也不会多发请求）。放在这里的好处是：用户真正走到设置页时能力集已经在手，
        // 不会出现"页面先按全部支持画出来、半秒后某个开关忽然灰掉"的闪动。
        loadDeviceCapabilities()
        // 2026-10-05 P6：30s 闸门只防"无变化的重复拉取"；收到外部变更信号（web/另一端写操作）
        // 后，陈旧风险大于重复拉取成本（6 请求/30s 上限 1 次）——放行。成功落地后清零，
        // 不连续放行（见 refreshNetwork 的成功打戳处）。
        val externallyStale = externalChangeElapsed > networkAllSuccessElapsed
        if (!force && !externallyStale &&
            _networkState.value.signalInfo != null &&
            isForegroundDataFresh(
                networkAllSuccessElapsed,
                SystemClock.elapsedRealtime(),
                NETWORK_ALL_FRESH_MS
            )
        ) {
            return
        }
        refreshNetwork(silent = silent)
        loadBandStatus(silent = silent)
        loadCellInfo(silent = silent)
        loadDeviceSettings()
        loadLanSettings()
        loadDeviceIdentity()
    }

    /**
     * 换设备 / 换地址后丢掉所有新鲜度基准。
     *
     * 不清就会出现"新设备的页面因为旧设备的戳还在新鲜窗口内而跳过请求"，
     * 屏幕上留着上一台的信号与 WiFi 名。与 `DashboardModule.clearDashboardCache()` 同一批调用。
     */
    fun resetFreshness() {
        networkAllSuccessElapsed = 0L
        wifiSuccessElapsed = 0L
        serviceStatusSuccessElapsed = 0L
        // 能力集跟着一起丢：它是**上一台设备**的静态快照，换设备 / 换地址后必须重取。
        //
        // 复位成 UNKNOWN 而不是清成"空集合 + loaded=true"：后者会被判成「全部不支持」，
        // 于是重连的那一瞬间整页控件全灰。降级方向只有一个 —— 不确定时按全部支持。
        capabilityJob?.cancel()
        _capabilityState.value = DeviceCapabilityState.UNKNOWN
    }

    // 拉一次设备能力集（`GET /api/device/capabilities`）。
    //
    // ── 缓存策略：进程生命周期一次 ──
    // core 侧这份取值是**装配组件图时算一次的静态快照**，同一个 core 进程里永不变化，
    // 所以 app 侧只要成功拿到过（[DeviceCapabilityState.loaded]）就不再重复请求 ——
    // 判据写在 state 自己身上，不额外维护第二个布尔量，免得两者漂移。
    //
    // ── 刷新时机 ──
    // 挂在 [resetFreshness] 上，而不是某个页面的 onResume：`resetFreshness` 正是
    // MainViewModel 在 `prepareDeviceSwitch()`（换设备）与
    // `onServerEndpointChanged(hostChanged = true)`（换地址）里调的那一个，
    // 语义恰好等于"现在对面可能是另一台设备了"。选它等于复用已有的唯一判据，
    // 不必再造一条"什么算重连"的规则。
    //
    // ⚠ ── 拉取失败必须当成「全部支持」 ──
    // catch 里**只记日志、不写任何错误 state**，`_capabilityState` 保持 UNKNOWN，
    // 而 UNKNOWN 的判定结果是全部支持（见 DeviceCapabilityState）。
    // 绝不许把失败当成「全部不支持」：那会让一次网络抖动把整个设置页灰掉，
    // 并且把「通道不可用」（503，可重试）冒充成「设备不支持」（501，不可恢复）。
    // 同理不冒全局错误横幅：这条请求是后台预热，用户没在等它的结果。
    //
    // @param force 无视已缓存结果重新拉。目前无调用方，留给将来"手动重新识别设备"用。
    fun loadDeviceCapabilities(force: Boolean = false) {
        if (!force && _capabilityState.value.loaded) return
        if (capabilityJob?.isActive == true) return
        capabilityJob = scope.launch {
            try {
                val resp = api.getDeviceCapabilities()
                _capabilityState.value = DeviceCapabilityState.fromWires(resp.capabilities)
                DebugLog.d(
                    "Network",
                    "设备能力集: plugin=${resp.plugin_id} 支持 ${resp.capabilities.size} 项"
                )
            } catch (e: Exception) {
                DebugLog.w("Network", "设备能力集拉取失败，按「全部支持」处理: ${e.message}")
            }
        }
    }


    fun refreshNetwork(silent: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            // 「有数据时不写 loading 态」（同 DashboardModule.refreshDashboardInternal 的判据）：
            // isLoading 在本页没有任何可见用途，有数据时翻成 true 只是白付一次整页重组。
            _networkState.update { current ->
                when {
                    current.signalInfo == null -> current.copy(isLoading = true, errorMessage = null)
                    current.errorMessage != null -> current.copy(errorMessage = null)
                    else -> current
                }
            }
            try {
                val sig = async { runCatching { api.getSignalInfo() } }
                val net = async { runCatching { api.getNetworkStatus() } }
                val wifi = async { runCatching { api.getWifiSettings() } }
                val clients = async { runCatching { api.getWifiClients() } }

                val rSig = sig.await(); val rNet = net.await()
                val rWifi = wifi.await(); val rClients = clients.await()

                val failures = listOfNotNull(
                    "信号" to rSig, "网络状态" to rNet,
                    "WiFi" to rWifi, "客户端" to rClients
                ).filter { it.second.isFailure }.joinToString("; ") { (name, r) ->
                    "$name: ${r.exceptionOrNull()?.message ?: "未知"}"
                }.ifEmpty { null }

                val wifiSettings = rWifi.getOrNull()
                val netStatus = rNet.getOrNull()

                _networkState.value = _networkState.value.copy(
                    signalInfo = rSig.getOrNull(), networkStatus = netStatus,
                    wifiSettings = wifiSettings,
                    wifiClients = rClients.getOrNull(), wifiEnabled = wifiSettings?.enabled ?: false,
                    mobileDataEnabled = netStatus?.mobile_data ?: false, isLoading = false,
                    errorMessage = if (silent) _networkState.value.errorMessage else failures
                )
                // 只在**全部成功**时记新鲜度基准：部分失败时字段是缺的，
                // 记了戳会让下一次前台化跳过重拉、把缺字段固化成"新鲜"。
                if (failures == null) {
                    networkAllSuccessElapsed = SystemClock.elapsedRealtime()
                    wifiSuccessElapsed = SystemClock.elapsedRealtime()
                    // 2026-10-05 P6：成功落地即清零外部变更戳，避免豁免被连续放行（不退化闸门）
                    externalChangeElapsed = 0L
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    _networkState.value = _networkState.value.copy(isLoading = false)
                    throw e
                }
                DebugLog.e("Network", "refreshNetwork failed", e)
                _networkState.value = _networkState.value.copy(
                    isLoading = false,
                    errorMessage = if (silent) _networkState.value.errorMessage else "加载失败: ${e.message}"
                )
            }
        }
    }


    /**
     * 开 / 关移动数据（`POST /api/network/mobile-data`）。
     *
     * 2026-08-30 修三个反馈问题：
     * ① **乐观回显**：Switch 绑的是 [NetworkState.mobileDataEnabled]，而这个值只有等
     *    `refreshNetwork()` 回来才会变。点下去到那时之间（最长 5s + 一轮全量刷新）开关
     *    停在旧值，看起来"点了没反应、要切走再切回来才更新"。现在先就地置成目标值。
     * ② **等目标状态而不是等"已连接"**：`waitForNetworkReady()` 默认等 isCellularConnected
     *    为真 —— 关闭移动数据时这个条件永远不成立，于是每次关都白等满 5 秒。
     * ③ **不动全页 isLoading**：原来置 true 会让整页进 loading 态；改用 [NetworkState.mobileDataPending]
     *    只影响这一行。
     * 失败时把乐观值回滚，避免 UI 停在没生效的状态上。
     * （2026-10-14：回滚不再靠 catch 里的 `previous`，而是靠 onSettled(false) 的 refreshNetwork() ——
     * 设备真值比"我们记着的上一个值"更可靠，详见下面 onSettled 的注释。）
     *
     * ── 2026-10-14（修 G4）─────────────────────────────────────────────────────
     * **原来是什么样**：`api.setMobileData(...)` 之后 `waitForNetworkReady(expectConnected = enabled)`
     * —— 5 秒预算、每 500ms 一次 `GET /api/network/status`（最多 11 次），到点**直接 return，
     * 一个字段都不写**（`waitForNetworkReady` 无返回值）。设备弱信号下重新拨号动辄十几秒，
     * 于是最常见的结果是"设备其实成功了、app 什么都没说"。
     * **为什么不对**：① 超时静默 —— 用户唯一的判断依据是那一行开关有没有自己变回去；
     * ② 节奏与另一端不同源 —— 同一件事 web 端有 13 次 / 30.6s 的回读与明示
     * （`web/src/composables/useNetworkControls.ts:378-394`），同一台设备在两个客户端上
     * "多久算超时"不一样。
     * **现在怎么改**：接 [opController]（与同一屏的 WiFi 开关 [setWifiEnabled]、频段锁定
     * [lockBands] 同构，也与 web 同构）：预算由 contract 的 [NetworkMode.SwitchProbe] 给出
     * （首延 600ms + 快档 1.5s×4 + 慢档 3s×8 = 13 次 / 30.6s），超时文案取确认器那一句
     * "未在预期时间内完成，设备可能仍在后台继续"，并自带「后台进行」出口。
     *
     * **代价（产品取舍，明写在这里）**：确认器带来的是**阻塞式进度弹窗**，与原来"乐观回显 +
     * 只转一行"的轻交互不同。选它、而不是"保留轻交互只把预算提到 30.6s"，理由三条：
     * ① 同一张网络页上紧邻的「WiFi 热点」开关早就走确认器，两个相邻开关一个弹窗一个不弹，
     *    本身就是不一致；
     * ② 轻交互那条路**没有**"设备可能仍在后台继续"这句话的落点 —— 那句话的唯一出处是确认器
     *    （也是 web 的同一句），要么再抄一份文案、要么继续静默；
     * ③ 30.6s 的等待必须给用户出口，否则就是"卡住半分钟不能动"；「后台进行」只存在于确认器里。
     * 轻交互的部分**没有丢**：乐观回显与行内 [NetworkState.mobileDataPending]（"切换中…"）
     * 都保留 —— 点了「后台进行」之后那一行仍显示"切换中…"，直到 onSettled 收尾。
     */
    fun toggleMobileData(enabled: Boolean) {
        // 乐观回显（坑①）保留：先就地置成目标值，避免"点了没反应"
        _networkState.update {
            it.copy(mobileDataEnabled = enabled, mobileDataPending = true, errorMessage = null)
        }
        opController.run(object : ApiOperationController.Spec {
            override val title = "移动数据"
            override val runningText = if (enabled) "正在开启移动数据" else "正在关闭移动数据"
            override val successText = if (enabled) "移动数据已开启" else "移动数据已关闭"
            override val hint = "拨号状态更新需要几秒钟"

            override suspend fun submit(): String? {
                val resp = try { api.setMobileData(mapOf("enabled" to enabled)) }
                    catch (e: Exception) { return e.message ?: "移动数据开关失败" }
                // 「200 + success:false」在契约上随时可能出现，不检查就是一个等着被踩的假成功
                return if (resp.success) null else "移动数据开关失败"
            }

            override suspend fun verify(): Boolean {
                // 判据：蜂窝连接态 == 目标（关数据时等断开，见上面坑②）。与 web 同源 ——
                // 真源都是 ppp_status（[NetworkStatusResponse.isCellularConnected]），不是顶层 connected。
                val status = api.getNetworkStatus()
                _networkState.update {
                    it.copy(networkStatus = status, mobileDataEnabled = status.mobile_data)
                }
                return status.isCellularConnected == enabled
            }

            override fun onApplied() {
                // 写后回读：拨号状态变了，信号/WiFi/客户端都可能跟着变，保留原路径的全量刷新
                refreshNetwork()
            }

            override fun onSettled(applied: Boolean) {
                _networkState.update { it.copy(mobileDataPending = false) }
                // 超时也要回读一次：乐观值是我们自己写上去的，只有设备真值能纠正它。
                // 口径同原实现 —— 原来无论等没等到都会 refreshNetwork()，这里不变。
                // **不回滚**乐观值：设备可能仍在后台继续，回滚等于断言"没生效"，那是我们并不知道的事。
                if (!applied) refreshNetwork()
            }
        })
    }


    /**
     * 最近一次 WiFi 轻量刷新**成功落地**的时刻（单调时钟，`0` = 本进程内从未成功）。
     * 单调时钟的理由见 `DataFreshness.kt`（`currentTimeMillis` 可被用户改）。
     */
    @Volatile private var wifiSuccessElapsed: Long = 0L

    /** 轻量刷新：仅 WiFi 设置 + 客户端列表（仪表盘 hero 卡「已连接设备 / WiFi 信息」数据源）。
     *  不触碰信号/网络状态/SIM，避免与 refreshNetwork 全量刷新互相覆盖。
     *
     *  ## [force] 的分工（2026-09-05 掉帧治理）
     *  调用点之一是 `DashboardScreen` 那条以 `pageForeground` 为 key 的 `LaunchedEffect`
     *  —— 每次横滑回到首页都会再进来一次，两个 REST 回包各写一次 `networkState`，
     *  落在胶囊归位的可见运动窗口里（"顿一下"）。首页 hero 卡只用它显示"已连接设备数 +
     *  WiFi 名称/频段"，10s 内的旧值完全够看，所以默认走新鲜度闸门
     *  （[isForegroundDataFresh]，窗口 [FOREGROUND_REFRESH_INTERVAL_MS]）。
     *
     *  必须 `force = true` 的三类调用方：
     *  - **写后回读**（`setWifiEnabled` / ACL 写入）：值刚被改过，吃缓存会显示旧状态；
     *  - **在线设备页的 5s 轮询**（`OnlineDevicesScreen`）：那一页的产品要求就是"设备上下线
     *    随时可见"，被 10s 窗口节流等于降级它的刷新率。
     *  本方法**不写任何 loading 态**（原本就没有），所以除此之外无需再改。
     */
    fun refreshWifi(force: Boolean = false) {
        if (!force &&
            _networkState.value.wifiSettings != null &&
            isForegroundDataFresh(wifiSuccessElapsed, SystemClock.elapsedRealtime())
        ) {
            return
        }
        scope.launch {
            try {
                val wifi = api.getWifiSettings()
                val clients = api.getWifiClients()
                _networkState.value = _networkState.value.copy(
                    wifiSettings = wifi,
                    wifiClients = clients,
                    wifiEnabled = wifi.enabled
                )
                // 只在成功落地后记新鲜度基准，失败不记（否则一次失败会把后续重拉全跳过）
                wifiSuccessElapsed = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                DebugLog.w("Network", "refreshWifi failed", e)
            }
        }
    }

    /**
     * 读接入控制名单（`GET /api/wifi/acl`）。
     * 失败时**保留**上一次的名单（置 null 会让"已拉黑"面板在一次网络抖动后突然显示空表）。
     */
    fun loadWifiAcl() {
        scope.launch {
            try {
                _networkState.value = _networkState.value.copy(wifiAcl = api.getWifiAcl())
            } catch (e: Exception) {
                DebugLog.w("Network", "loadWifiAcl failed", e)
            }
        }
    }

    /** 拉黑一台设备。[name] 传在线列表里的 hostname（设备侧名单要 mac 与 name 一一对应）。 */
    fun blockDevice(mac: String, name: String) = writeAcl(mac) {
        api.blockWifiDevice(mapOf("mac" to mac, "name" to name))
    }

    /** 解除拉黑。 */
    fun unblockDevice(mac: String) = writeAcl(mac) {
        api.unblockWifiDevice(mapOf("mac" to mac))
    }

    /** 清空黑名单（白名单由 core 原样保留）。 */
    fun clearBlockedDevices() = writeAcl(null) { api.clearWifiAcl() }

    /**
     * 三个 ACL 写操作的公共外壳。
     *
     * 响应就是 core 写完**回读设备**的真实名单，所以直接覆盖 [NetworkState.wifiAcl] ——
     * 不要本地增删推算：设备侧是整表替换，本地推算会和 web / 设备自带 UI 的操作互相覆盖。
     * 拉黑会把已连接的设备踢下线，所以顺带刷一次在线列表。
     */
    private fun writeAcl(pendingMac: String?, block: suspend () -> WifiAclResponse) {
        scope.launch {
            _networkState.value = _networkState.value.copy(aclPendingMac = pendingMac?.lowercase())
            try {
                val acl = block()
                _networkState.value = _networkState.value.copy(
                    wifiAcl = acl,
                    aclPendingMac = null,
                    errorMessage = if (acl.success) null else "操作失败"
                )
                // 写后回读：名单刚变，必须绕过新鲜度闸门
                refreshWifi(force = true)
            } catch (e: Exception) {
                DebugLog.w("Network", "ACL 写入失败 mac=$pendingMac", e)
                _networkState.value = _networkState.value.copy(
                    aclPendingMac = null,
                    errorMessage = "操作失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 拉取 WiFi 连接二维码原始字节（`GET /api/wifi/qrcode`，PNG）。
     *
     * 不进 [NetworkState]：图片字节是 ByteArray，塞进 data class 会让 equals 退化成引用比较，
     * 每次拉取都触发全页重组；而且它只有 WiFi 设置弹窗一个消费者，用完即弃。
     * 二维码由设备生成，反映**设备当前**的 SSID/密码，改完配置要重新调一次。
     *
     * @return 失败（设备读不到 → core 503）时返回 null，由 UI 决定占位文案。
     */
    suspend fun fetchWifiQrCode(chip: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            api.getWifiQrCode(chip = if (chip == "chip2") "chip2" else "chip1").use { it.bytes() }
        } catch (e: Exception) {
            DebugLog.w("Network", "WiFi 二维码获取失败 chip=$chip", e)
            null
        }
    }

    /**
     * 开 / 关飞行模式（`POST /api/network/airplane`）。
     *
     * ── 2026-10-14（修 G4）─────────────────────────────────────────────────────
     * **原来是什么样**：下发成功后 `waitForNetworkReady(expectConnected = !enabled)` + `refreshNetwork()`
     * —— 与 [toggleMobileData] 一模一样的 5 秒静默预算，到点直接 return、不写任何字段；
     * 失败分支还写着"设备尚未完成，可稍后刷新查看"，而**真正会发生的超时那条路根本不经过它**
     * （`waitForNetworkReady` 不抛异常，catch 只兜得住下发失败）。
     * **为什么不对**：同上 —— 静默超时 + 节奏与 web 不同源
     * （`web/src/composables/useNetworkControls.ts:398-411`，13 次 / 30.6s）。
     * **现在怎么改**：与 [toggleMobileData] 同构接 [opController]。取舍理由见那边的长注释；
     * 本条多一层：飞行模式会**断开蜂窝**，30.6s 里用户很可能想先去干别的 —— 「后台进行」在这里
     * 比移动数据更必要，而不是更不必要。
     *
     * 契约补充：core 的 `/api/network/airplane` 只有写、没有读（web 侧同注释），
     * 所以回读判据只能借 `GET /api/network/status` 的蜂窝连接态。
     */
    fun toggleAirplaneMode(enabled: Boolean) {
        opController.run(object : ApiOperationController.Spec {
            override val title = "飞行模式"
            override val runningText = if (enabled) "正在开启飞行模式" else "正在关闭飞行模式"
            override val successText = if (enabled) "飞行模式已开启" else "飞行模式已关闭"
            override val hint = "拨号状态更新需要几秒钟"

            override suspend fun submit(): String? {
                val resp = try { api.setAirplaneMode(mapOf("enabled" to enabled)) }
                    catch (e: Exception) { return e.message ?: "飞行模式切换失败" }
                return if (resp.success) null else "飞行模式切换失败"
            }

            override suspend fun verify(): Boolean {
                val status = api.getNetworkStatus()
                // 飞行模式会连带关掉移动数据，所以两个字段一起对齐设备真值
                _networkState.update {
                    it.copy(networkStatus = status, mobileDataEnabled = status.mobile_data)
                }
                // 开飞行模式 = 等断开，关飞行模式 = 等连上（同 toggleMobileData 的坑②）
                return status.isCellularConnected == !enabled
            }

            override fun onApplied() { refreshNetwork() }

            override fun onSettled(applied: Boolean) {
                // 没有行内 pending 位要清（飞行模式开关不在本页），只做超时后的真值回读。
                if (!applied) refreshNetwork()
            }
        })
    }

    /**
     * 切换网络制式。
     *
     * 三个约定，缺一个就会回到 2026-09-11 真机上那份表现：
     * 1. **先进「切换中」**（[NetworkState.pendingNetworkMode]），界面立刻显示目标档位。
     *    设备重新注册期间 `GET /api/device/settings` 仍报旧档位，拿回读值渲染就会
     *    "点了 4G 界面还是仅 5G"。
     * 2. **失败文案取 core 给的原因**。core 现在把设备侧失败映射成 503 / 502 + 中文 message
     *    （会话失效 / 设备明确拒绝），只报"设置失败"等于把这份信息扔了。
     * 3. **回读有上限**（[awaitNetworkModeApplied]）。等不到就明说，不静默停在旧值上。
     */
    fun setNetworkMode(mode: String) {
        modeSwitchJob?.cancel()
        modeSwitchJob = scope.launch {
            _networkState.update {
                it.copy(pendingNetworkMode = mode, modeSwitchTimedOut = false, errorMessage = null)
            }
            val failure = try {
                if (api.setNetworkMode(ModeRequest(mode)).success) null else "设置失败"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                coreErrorMessage(e)
            }
            if (failure != null) {
                _networkState.update { it.copy(pendingNetworkMode = null, errorMessage = failure) }
                return@launch
            }
            awaitNetworkModeApplied(mode)
        }
    }

    /**
     * 下发成功后，在 `NetworkMode.SwitchProbe` 的预算内回读设备设置，等它报出目标档位。
     *
     * 上限是硬的（次数由 `shouldKeepProbing` 判定）：设备在弱信号下可能十几秒都注册不上，
     * 无上限轮询会一直打 goform 查询，把 core 侧的设置/查询许可耗在这一件事上。
     * 预算内没等到就置 [NetworkState.modeSwitchTimedOut] 并给出中性文案 ——
     * 静默退回旧档位会让用户以为切换没生效（实际上设备往往稍后就切过去了）。
     */
    private suspend fun awaitNetworkModeApplied(target: String) {
        // 设备 goform 写入到查询接口可见有延迟，第一次回读必须等一会儿，否则拿到的一定是旧值
        delay(NetworkMode.SwitchProbe.FIRST_DELAY_MS)
        var attempt = 0
        var reached = false
        while (true) {
            attempt++
            val settings = try {
                api.getDeviceSettings()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w("Network", "制式回读失败 attempt=$attempt", e)
                null
            }
            if (settings != null) {
                _deviceSettingsState.update {
                    DeviceSettingsState(settings = settings, loadVersion = System.currentTimeMillis())
                }
                // 真机：切换完成后变化的是 net_select（如 WL_AND_5G / Only_5G）。
                // 两个字段都换算成 contract 别名再比对；任一命中即视为已切换 ——
                // 只盯 BearerPreference 会在它为空/滞后时永远判超时。
                val fromNetSelect = settings.netSelect?.takeIf { it.isNotBlank() }
                    ?.let { NetworkMode.fromBearer(it) }
                val fromBearerPref = settings.bearerPreference?.takeIf { it.isNotBlank() }
                    ?.let { NetworkMode.fromBearer(it) }
                reached = target == fromNetSelect || target == fromBearerPref
            }
            if (!NetworkMode.SwitchProbe.shouldKeepProbing(attempt, reached)) break
            delay(NetworkMode.SwitchProbe.intervalMsAfter(attempt))
        }
        _networkState.update {
            it.copy(
                pendingNetworkMode = null,
                modeSwitchTimedOut = !reached,
                errorMessage = if (reached) it.errorMessage else "设备尚未完成切换，可稍后刷新查看"
            )
        }
        // 2026-09-22 补成功提示：**放在这里而不是下发成功之后** —— 制式切换的"成功"是
        // 设备真的报出了目标档位（上面这段回读的判据），下发只是被受理。
        // 超时那一支已经写了 errorMessage，不能也弹一条绿色的。
        if (reached) emitWriteNotice("网络制式已切换为 ${NetworkMode.label(target)}")
        refreshNetwork()
    }

    /**
     * 把 core 的失败信封翻译成能给用户看的一句话。
     *
     * Retrofit 的 `HttpException.message` 是 `"HTTP 503 Service Unavailable"`，直接显示等于
     * 把 core 写好的「设备后台会话已失效…」扔了。非 HTTP 异常（连不上 / 超时）没有错误体可取，
     * 落到 `e.message`。
     */
    private fun coreErrorMessage(e: Exception): String =
        coreRejectReason(e) ?: "设置失败: ${e.message ?: "未知错误"}"

    /**
     * 只取 core 失败信封里的 `error` 字段（没有就是 null）。
     *
     * 独立出来是为了让调用方自己拼前缀：失败提示要说清**是哪一项**没保存成功（§4.15），
     * 而 [coreErrorMessage] 的兜底前缀是通用的"设置失败"，套在具体文案外面会变成
     * 「WiFi 热点保存失败：设置失败: …」这种两层前缀。
     */
    private fun coreRejectReason(e: Exception): String? {
        if (e is retrofit2.HttpException) {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            val reason = runCatching {
                (AppJson.parseToJsonElement(body ?: "") as? JsonObject)
                    ?.get("error")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (!reason.isNullOrBlank()) return reason
        }
        return null
    }

    /**
     * 统一锁定 LTE+NR 频段（goform + AT+SFUN 网络栈重启，无需设备重启）。
     *
     * 2026-10-05 P7：接入确认器（原【反馈】S5）。原来下发成功只 `waitForNetworkReady()` +
     * `loadBandStatus()`（发射后不管），锁频实际要十几~二十几秒才生效，用户看不到中间态。
     * 现在走 [opController]「执行→等待→回读确认→更新」：圆环弹窗 + 集合相等判定 + 超时明示。
     *
     * **签名变更点（P7 唯一一处）**：入参由 `String?` 改为 `Set<Int>?`，判定直接用
     * [BandStatusResponse] 自带的集合解析（"0"/"all"=空集），不再在调用方拼字符串。
     *
     * @param lteBands / @param nrBands 均为 null = 全部解锁。
     */
    fun lockBands(lteBands: Set<Int>?, nrBands: Set<Int>?) {
        val unlocking = lteBands == null && nrBands == null
        opController.run(object : ApiOperationController.Spec {
            override val title = "频段锁定"
            override val runningText = if (unlocking) "正在解锁全部频段" else "正在应用频段设置"
            override val successText = if (unlocking) "频段已解锁" else "频段锁定已应用"
            override val hint = "网络栈会重启，期间信号短暂中断，属正常现象"

            override suspend fun submit(): String? {
                val body = buildMap {
                    if (unlocking) put("action", "unlock")
                    else {
                        lteBands?.takeIf { it.isNotEmpty() }?.let { put("lte_bands", it.joinToString(",")) }
                        nrBands?.takeIf { it.isNotEmpty() }?.let { put("nr_bands", it.joinToString(",")) }
                    }
                }
                val resp = try { api.setBandLock(body) } catch (e: Exception) { return e.message ?: "锁频失败" }
                return if (resp.success) null else "锁频失败"
            }

            override suspend fun verify(): Boolean {
                val status = api.getBandStatus()
                val targetLte = lteBands ?: emptySet()
                val targetNr = nrBands ?: emptySet()
                // BandStatusResponse 自带 Set<Int> 解析（"0"/"all"=空集），集合相等即命中；
                // 顺带把回读值写入 _networkState.bandStatus，UI 立即对齐设备真值
                _networkState.update { it.copy(bandStatus = status, loadVersion = System.currentTimeMillis()) }
                return status.lteBands == targetLte && status.nrBands == targetNr
            }

            override fun onApplied() {
                loadBandStatus()
                emitWriteNotice(successText)      // 复用既有 toast 通道
            }
        })
    }

    /** @param silent 失败不写 `errorMessage`（预加载专用，见 [loadNetworkAll]）。 */
    fun loadBandStatus(silent: Boolean = false) {
        scope.launch {
            try {
                _networkState.update { it.copy(bandStatus = api.getBandStatus(), loadVersion = System.currentTimeMillis()) }
            } catch (e: Exception) {
                if (silent) DebugLog.w("Network", "频段状态加载失败（静默）: ${e.message}")
                else _networkState.update { it.copy(errorMessage = "频段状态加载失败: ${e.message}") }
            }
        }
    }

    fun loadDeviceSettings() {
        scope.launch {
            _deviceSettingsState.update { it.copy(isLoading = true, loadVersion = System.currentTimeMillis()) }
            try {
                _deviceSettingsState.update { DeviceSettingsState(settings = api.getDeviceSettings(), loadVersion = System.currentTimeMillis()) }
            }
            catch (e: Exception) { _deviceSettingsState.update { it.copy(errorMessage = "加载设备设置失败: ${e.message}", isLoading = false) } }
        }
    }

    /**
     * 拉取设备身份信息（`GET /api/device/identity`）：本机号码(msisdn) / IMEI 等。
     * 只写 [NetworkState.deviceIdentity]，失败不影响其它网络数据（错误吞掉，UI 回落到 "—"）。
     */
    fun loadDeviceIdentity() {
        scope.launch {
            try {
                val identity = api.getDeviceIdentity()
                _networkState.update { it.copy(deviceIdentity = identity, loadVersion = System.currentTimeMillis()) }
            } catch (e: Exception) {
                DebugLog.w("Network", "设备身份信息加载失败: ${e.message}")
            }
        }
    }

    fun setBearerPreference(preference: String) {
        scope.launch {
            try {
                val resp = api.setBearerPreference(mapOf("preference" to preference))
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "设置失败")
                refreshNetwork()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "设置失败: ${e.message}") }
        }
    }

    fun connectNetwork() {
        scope.launch {
            try {
                val resp = api.connectNetwork()
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "连接失败")
                else _networkState.value = _networkState.value.copy(errorMessage = null)
                waitForNetworkReady(); refreshNetwork()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "连接失败: ${e.message}") }
        }
    }

    fun disconnectNetwork() {
        scope.launch {
            try {
                val resp = api.disconnectNetwork()
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "断开失败")
                else _networkState.value = _networkState.value.copy(errorMessage = null)
                delay(500); refreshNetwork()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "断开失败: ${e.message}") }
        }
    }

    /**
     * 自动 / 手动拨号。2026-09-22 补成功提示：这一项改完界面上**什么都不变**
     *（选中态是本地 radio 的即时反应，不等回包），静默成功等于没反馈。
     */
    fun setConnectionMode(mode: String) {
        scope.launch {
            try {
                val resp = api.setConnectionMode(mapOf("mode" to mode))
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "设置失败")
                else emitWriteNotice(if (mode == "auto") "已切换为自动拨号" else "已切换为手动拨号")
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "设置失败: ${e.message}") }
        }
    }

    // ── Speed Test ──
    // UID-010 (Wave 2)：持有当前测速协程，用于取消（手动取消或离开页面时 cancel()）。
    private var speedTestJob: Job? = null

    /**
     * 立刻关闭当前所有测速流的钩子集合。
     * `InputStream.read()` 是阻塞调用、不响应协程取消——只 cancel() 的话协程要等这一次 read 返回
     * 才会真正结束（弱网下可能好几秒）。取消时同时关流，read 会立即抛出、协程马上收尾。
     * 多流并发下同时存在多个流，故用并发集合（IO 线程写、主线程读）。
     */
    private val speedTestClosers = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 上行中止标志。上行是在 `RequestBody.writeTo` 里同步往 sink 写，既不响应协程取消也没有
     * 可关闭的流句柄，只能靠这个标志让写循环自己退出。
     */
    @Volatile
    private var speedTestAborted = false

    /**
     * 外网测速专用 OkHttpClient：**刻意不带** auth / trace 拦截器。
     *
     * 原来复用 `AppHttpClient.instance`，它会给每个请求注入 `Authorization: Bearer <设备 token>`
     * —— 等于把本机设备令牌发给第三方测速站（tele2 等），是实打实的凭据外泄；
     * 它的 trace 拦截器还会对响应 `peekBody(4096).string()` 并写日志，对流式响应等于白等首包、
     * 污染首字节延迟测量。
     *
     * readTimeout 收紧到 20s：外网站点中途停流时尽快失败，而不是挂在共享单例的 120s 上。
     */
    private val externalSpeedClient: OkHttpClient by lazy {
        OkHttpClientProvider.shared.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 外网延迟探针专用 client：超时全部收紧到秒级，并加 `callTimeout` 兜住整次调用。
     *
     * 探针要连发 [SPEED_PING_COUNT] 次，若沿用主测速 client 的 8s connect / 20s read，
     * 一个不可达（或被运营商丢包）的站点能把探针阶段拖到几十秒甚至更久，UI 全程停在
     * 「连接中」——这就是「外网测速点开始后卡死」的直接原因之一。
     */
    private val externalProbeClient: OkHttpClient by lazy {
        OkHttpClientProvider.shared.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /** 内网测速：从设备下载填充数据，时长由 [startSpeedTest] 自动决定 */
    fun runSpeedTest() = startSpeedTest("internal", null)

    /**
     * 拉定时测速历史（2026-10-06 批C 对齐）。失败静默：历史是增益信息，
     * 拉不到不该把测速页拖进错误态。
     */
    fun refreshSpeedTestHistory() {
        scope.launch {
            try {
                val resp = api.speedTestHistory(limit = 30)
                val items = resp["items"]?.jsonArray?.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    SpeedTestHistoryEntry(
                        timestamp = o["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null,
                        downloadMbps = o["download_mbps"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        uploadMbps = o["upload_mbps"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        latencyMs = o["latency_ms"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1,
                        jitterMs = o["jitter_ms"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1,
                        trigger = o["trigger"]?.jsonPrimitive?.content ?: "manual",
                    )
                } ?: emptyList()
                _speedTestHistory.value = items
            } catch (e: Exception) {
                DebugLog.w("Network", "speedtest history load failed: ${e.message}")
            }
        }
    }

    /**
     * 外网测速：只接受节点 id，地址一律从 [SpeedTestState.EXTERNAL_NODES] 里取。
     *
     * 2026-08-27：原来允许 UI 传任意 URL，配套一个"修改测速地址"弹窗。改成固定清单后那个入口
     * 一并删除——任意地址会带来两类无法在客户端消除的误差：被 CDN/代理按文本类型压缩后
     * 统计到解压字节（虚高），以及文件太小提前 EOF（虚低）。自建节点两点都规避了。
     * 多节点只是同一套端点契约部署在不同平台（EdgeOne / Cloudflare），用来对比出口质量。
     */
    fun runExternalSpeedTest(nodeId: String) =
        startSpeedTest("external", SpeedTestState.externalNode(nodeId))

    /**
     * 测速统一实现（2026-08-26，第三轮：按 LibreSpeed 的测量方法论重做，未引入其代码）。
     *
     * 流程：延迟/抖动探针 →（外网：上行能力探测）→ 多流并发下行 → 多流并发上行 → 出结果。
     *
     *  1. **多流并发**（[SPEED_STREAMS] 条）：单条 TCP 流的吞吐受 RTT × 窗口限制，跨公网常常
     *     只能跑到链路的一小半。所有流把字节加进同一个 [AtomicLong]，由**独立的采样协程**按固定
     *     节拍读差值——采样与搬运彻底解耦，不再像旧实现那样在读循环里顺带算速率。
     *  2. **丢弃起速爬坡段**：前 [SPEED_RAMP_UP_MS] 的采样只画曲线，不计入平均与峰值；平均值按
     *     「爬坡结束后的字节 / 爬坡结束后的时间」算，避免 TCP 慢启动系统性拉低结果。
     *  3. **中位数平滑**：仪表盘读数取最近 [SPEED_MEDIAN_WINDOW] 个采样的中位数，抹掉单窗口毛刺，
     *     又不像滑动均值那样迟滞。
     *  4. **延迟与抖动分开测**：不再把首字节延迟当延迟——用 [SPEED_PING_COUNT] 次零负载 HEAD
     *     探针，取中位数当延迟、相邻差的平均绝对值当抖动。
     *  5. **上行**：内网写 core 的丢弃汇 `POST /api/speedtest/upload`；外网自建节点写它的
     *     `POST /upload`（可选端点，先用 [probeExternalUpload] 探一次，没部署就跳过上行）；
     *     第三方文件节点没有可写入的地址，直接跳过。
     *  6. 智能时长保留：跑满最短时长且速率稳定就提前收尾，否则跑到硬上限。
     *  7. **外网续传**：外网单条流读到 EOF 会重开同一个 URL（[refillExternal]），所以测速时长
     *     不再受测速文件大小约束——1MB 的图片也能跑满时长（并发流数按节点类型给，见
     *     `SpeedTestNode.publicFile`）。
     */
    private fun startSpeedTest(testType: String, node: SpeedTestNode?) {
        if (speedTestJob?.isActive == true) return
        val url = node?.downloadUrl
        val streams = node?.streams ?: SPEED_STREAMS
        val maxDownloadSec =
            if (node == null) SPEED_MAX_DURATION_INTERNAL_SEC else SPEED_MAX_DURATION_EXTERNAL_SEC
        speedTestJob = scope.launch(Dispatchers.IO) {
            speedTestAborted = false
            speedTestClosers.clear()
            try {
                _speedTestState.value =
                    SpeedTestState(phase = SpeedTestPhase.CONNECTING, testType = testType)

                // ① 延迟 / 抖动：零负载探针，不占测速并发位
                val ping = measureLatency(testType, url)
                // ② 上行能力：必须在下行开始前定下来——下行阶段的进度条区间取决于
                // 「后面还有没有上行」，探晚了进度条会跳。
                // 内网的丢弃汇是 core 自带的，不用探；第三方文件节点根本没有上行地址，也不用探。
                val uploadUrl = node?.uploadUrl
                val uploadEnabled = when {
                    node == null -> true
                    uploadUrl == null -> false
                    else -> probeExternalUpload(uploadUrl)
                }
                var template = SpeedTestState(
                    phase = SpeedTestPhase.LATENCY,
                    testType = testType,
                    latencyMs = ping?.first,
                    jitterMs = ping?.second
                )
                _speedTestState.value = template

                // ③ 下行：多流并发
                val down = runDownloadPhase(template, url, streams, maxDownloadSec, uploadEnabled)
                template = template.copy(
                    avgMbps = down.avgMbps,
                    peakMbps = down.peakMbps,
                    streams = down.streams,
                    totalBytes = down.bytes,
                    samples = down.samples
                )

                // ④ 上行
                val up = if (uploadEnabled) runUploadPhase(template, uploadUrl) else null

                _speedTestState.value = template.copy(
                    phase = SpeedTestPhase.DONE,
                    currentMbps = down.avgMbps,
                    uploadMbps = up?.avgMbps ?: 0.0,
                    uploadMeasured = up != null,
                    streams = maxOf(down.streams, up?.streams ?: 0),
                    totalBytes = down.bytes + (up?.bytes ?: 0L),
                    elapsedSec = down.elapsedSec + (up?.elapsedSec ?: 0.0),
                    progress = 1f,
                    samples = down.samples
                )
            } catch (e: CancellationException) {
                _speedTestState.value = SpeedTestState(
                    phase = SpeedTestPhase.IDLE, testType = testType, errorMessage = "测速已取消"
                )
            } catch (e: Exception) {
                // 取消时我们会主动关流，read() 抛的是 IOException 而不是 CancellationException，
                // 这里按 isActive 区分，避免把用户主动取消报成"测速失败"。
                _speedTestState.value = if (!isActive) {
                    SpeedTestState(phase = SpeedTestPhase.IDLE, testType = testType, errorMessage = "测速已取消")
                } else {
                    SpeedTestState(phase = SpeedTestPhase.ERROR, testType = testType, errorMessage = "测速失败: ${e.message}")
                }
            } finally {
                speedTestAborted = true
                closeAllSpeedStreams()
            }
        }
    }

    /** 一个方向（下行或上行）跑完后的结果 */
    private class PhaseResult(
        val avgMbps: Double,
        val peakMbps: Double,
        val bytes: Long,
        val elapsedSec: Double,
        val samples: List<Float>,
        val streams: Int
    )

    private class DownloadStream(val input: java.io.InputStream, val release: () -> Unit)

    /**
     * 延迟与抖动：连发 [SPEED_PING_COUNT] 次零负载探针。
     * 返回 (延迟中位数 ms, 抖动 ms)；全部失败返回 null（不阻断后续吞吐测试）。
     *
     * 取中位数而不是最小值：最小值是"最好情况"，中位数才代表用户实际体验。
     * 抖动按相邻两次探针之差的平均绝对值算（RFC 3550 的思路），而不是标准差。
     *
     * 三道防线保证这里不可能把测速拖死（外网卡死的教训）：
     *  1. 探针走 [externalProbeClient]，秒级超时 + `callTimeout`；
     *  2. 整个探针阶段有 [SPEED_PING_TOTAL_BUDGET_MS] 总预算；
     *  3. 连续失败 [SPEED_PING_MAX_FAILURES] 次直接放弃，不把剩下的超时全跑完。
     */
    private suspend fun measureLatency(testType: String, url: String?): Pair<Double, Double>? {
        val rtt = ArrayList<Double>(SPEED_PING_COUNT)
        var consecutiveFailures = 0
        withTimeoutOrNull(SPEED_PING_TOTAL_BUDGET_MS) {
            repeat(SPEED_PING_COUNT) { index ->
                if (speedTestAborted) return@withTimeoutOrNull
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val ok = try {
                    if (url == null) api.speedTestPing() else probeExternal(url)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (ok) {
                    consecutiveFailures = 0
                    rtt += (android.os.SystemClock.elapsedRealtime() - startedAt).toDouble()
                } else if (++consecutiveFailures >= SPEED_PING_MAX_FAILURES) {
                    return@withTimeoutOrNull
                }
                // 探针阶段也要出状态，否则 UI 会长时间停在「连接中」看起来像卡死
                _speedTestState.value = SpeedTestState(
                    phase = SpeedTestPhase.LATENCY,
                    testType = testType,
                    latencyMs = rtt.minOrNull(),
                    progress = (index + 1f) / SPEED_PING_COUNT * LATENCY_PROGRESS_SPAN
                )
            }
        }
        if (rtt.isEmpty()) return null
        val median = rtt.sorted()[rtt.size / 2]
        var diffSum = 0.0
        for (i in 1 until rtt.size) diffSum += abs(rtt[i] - rtt[i - 1])
        return median to if (rtt.size > 1) diffSum / (rtt.size - 1) else 0.0
    }

    /**
     * 外网探针：只要首包往返，**绝不能把响应体读完**。
     *
     * 这里踩过一次坑（上一轮我自己引入的）：为了让连接进池子而写 `body.bytes()`，
     * 但如果服务器忽略 `Range` 直接回 `200` + 整个文件，`bytes()` 会把上百 MB 读进内存
     * —— 10 次探针就是十几次整文件下载，表现正是「点开始后长时间无响应、内存暴涨」。
     * 现在只在服务器确实回了 `206` **且**长度极小时才把那一两个字节读掉（连接可复用）；
     * 回 `200` 就立刻关闭，宁可牺牲 keep-alive 也不能读整个文件。
     *
     * Call 必须登记进 [speedTestClosers]：`execute()` 是阻塞调用，协程 cancel 打不断它，
     * 只有 `Call.cancel()` 能让「取消」在探针阶段立刻生效
     * ——之前取消按钮在这个阶段完全没反应就是因为漏了这一步。
     */
    private fun probeExternal(url: String) {
        val call = externalProbeClient.newCall(
            Request.Builder().url(url).header("Range", "bytes=0-0").build()
        )
        val closer: () -> Unit = { runCatching { call.cancel() } }
        speedTestClosers += closer
        try {
            call.execute().use { response ->
                val body = response.body ?: return@use
                val len = body.contentLength()
                if (response.code == HTTP_PARTIAL_CONTENT && len in 0..PROBE_DRAIN_MAX_BYTES) {
                    body.bytes()
                }
            }
        } finally {
            speedTestClosers -= closer
        }
    }

    private suspend fun runDownloadPhase(
        template: SpeedTestState,
        url: String?,
        streams: Int,
        maxSec: Int,
        uploadFollows: Boolean
    ): PhaseResult = coroutineScope {
        val counter = AtomicLong(0)
        val liveStreams = AtomicInteger(0)
        // 每条流请求的块数：按硬上限 × 40MB/s 估一个肯定够用的量（千兆内网也压得住），
        // 真正的结束条件是时间 / 稳定性判定。
        val chunksPerStream = (maxSec * 40).coerceIn(10, 4096)
        // 外网续传开关：见 [refillExternal]。必须**先**置位再关流，否则关流让当前 read 抛错、
        // 续传循环会立刻重开一个新请求，跨阶段占带宽污染上行测量。
        val stopDownload = AtomicBoolean(false)

        // 第 0 条流先同步开：连不上 / 401 / URL 写错要在这里就抛出来，
        // 而不是被并发流的 catch 吞掉、最后只看到一个"速率 0"的假结果。
        val firstStream = openDownloadStream(url, chunksPerStream)
        liveStreams.incrementAndGet()

        val readers = ArrayList<Job>(streams)
        readers += launch(Dispatchers.IO) {
            drainStream(firstStream, counter)
            if (url != null) refillExternal(url, counter, stopDownload)
        }
        for (i in 1 until streams) {
            readers += launch(Dispatchers.IO) {
                val handle = try {
                    openDownloadStream(url, chunksPerStream)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 并发流开不起来（例如服务端 429）就少一条，不影响整体结果
                    DebugLog.w("NetworkModule", "测速并发流 #$i 打开失败: ${e.message}")
                    return@launch
                }
                liveStreams.incrementAndGet()
                drainStream(handle, counter)
                if (url != null) refillExternal(url, counter, stopDownload)
            }
        }

        val result = sampleThroughput(
            template = template,
            phase = SpeedTestPhase.DOWNLOAD,
            counter = counter,
            minSec = SPEED_MIN_DURATION_SEC,
            maxSec = maxSec,
            progressBase = 0f,
            progressSpan = if (uploadFollows) DOWNLOAD_PROGRESS_SPAN else 1f,
            streamsProvider = { liveStreams.get() }
        )
        stopDownload.set(true)
        // 关流打断阻塞 read，再等搬运协程收尾——否则它们会跨阶段继续抢带宽、污染上行测量
        closeAllSpeedStreams()
        readers.forEach { it.cancel() }
        result
    }

    /**
     * 外网续传：一个 URL 读到 EOF 后重开同一个 GET，直到采样协程叫停（2026-08-27）。
     *
     * 为什么需要：外网模式下每条流就是一个普通 `GET url`，读到 EOF 那条流就结束了。文件不够大
     * 时（静态托管常有单文件上限，例如 Cloudflare Pages / EdgeOne Pages 的 25MB），4 条流
     * 各读完 25MB 就没数据可读，剩下的时间里速率归零，平均值被系统性拉低——表现就是"外网测速
     * 数字明显偏小"。续传后测速时长不再受文件大小约束：25MB 的文件也能压满千兆，只是每读完
     * 一遍会多花一个 RTT 重发请求（连接是读到 EOF 后正常归还连接池的，可以复用，不需要重握手）。
     *
     * 只对外网做：内网 `/api/speedtest?ckSize=N` 的 N 是按硬上限 × 40MB/s 算的，一定读不完。
     *
     * 终止条件三重保险：[stop]（阶段结束）、[speedTestAborted]（整次测速结束）、协程取消。
     * 重开失败（限流 / 服务端掐连接）就直接退出这条流，不重试——少一条流由 `streamsProvider`
     * 如实反映，比在错误里死磕更好。
     *
     * 已知取舍：每次重开都会往 [speedTestClosers] 追加两个关闭钩子且不摘除，一次测速累计几百个
     * 空 lambda；它们在阶段结束时统一 clear，重复 close 已关闭的流是幂等的，所以不额外维护。
     */
    private suspend fun refillExternal(url: String, counter: AtomicLong, stop: AtomicBoolean) {
        val ctx = currentCoroutineContext()
        while (ctx.isActive && !stop.get() && !speedTestAborted) {
            val handle = try {
                openDownloadStream(url, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w("NetworkModule", "外网续传重开失败: ${e.message}")
                return
            }
            drainStream(handle, counter)
        }
    }

    private suspend fun openDownloadStream(url: String?, chunksPerStream: Int): DownloadStream {
        if (url == null) {
            val body = api.speedTest(chunksPerStream)
            val input = body.byteStream()
            speedTestClosers += { runCatching { input.close() } }
            return DownloadStream(input) {
                closeQuietly("speedtest stream") { input.close() }
                closeQuietly("speedtest response body") { body.close() }
            }
        }
        // 外网：把 Call 也登记进关闭钩子——execute() 是阻塞调用，协程 cancel 打不断它，
        // 只有 Call.cancel() 能立刻中断正在建连/收包的请求。
        val call = externalSpeedClient.newCall(Request.Builder().url(url).build())
        speedTestClosers += { runCatching { call.cancel() } }
        val response = call.execute()
        val body = response.body
        if (!response.isSuccessful || body == null) {
            closeQuietly("speedtest response") { response.close() }
            throw IllegalStateException(
                if (body == null) "响应无数据体 (HTTP ${response.code})" else "HTTP ${response.code}"
            )
        }
        val input = body.byteStream()
        speedTestClosers += { runCatching { input.close() } }
        return DownloadStream(input) {
            closeQuietly("speedtest stream") { input.close() }
            closeQuietly("speedtest response") { response.close() }
        }
    }

    /** 纯搬运：只把读到的字节数累加进 [counter]，速率由采样协程算。 */
    private suspend fun drainStream(handle: DownloadStream, counter: AtomicLong) {
        val ctx = currentCoroutineContext()
        try {
            val buf = ByteArray(SPEED_READ_BUFFER_BYTES)
            while (ctx.isActive && !speedTestAborted) {
                val n = handle.input.read(buf)
                if (n == -1) break
                counter.addAndGet(n.toLong())
            }
        } catch (e: Exception) {
            // 关流 / 取消 / 服务端断开都会走到这里：搬运协程静默收尾，结论由采样协程给出
        } finally {
            handle.release()
        }
    }

    /**
     * 外网上行能力探测：往节点的 `POST /upload` 发 1 字节，看它到底受不受。
     *
     * 为什么要探：`/upload` 是节点的**可选**端点（下行 `/speedtest` 与 `/ping` 是必需的）。
     * 没部署时静态托管平台对 POST 一律回 `405`（实测 Cloudflare Pages 对任意路径的 POST
     * 都是 405），直接开 4 条上行流的话用户看到的是"上行 0 Mbps"这种假结果。
     *
     * 1 字节而不是 0 字节：有的边缘运行时对空 body 的 POST 走另一条快速路径，探不出真实行为。
     * 走 [externalProbeClient]（秒级超时 + callTimeout），最多给整次测速加 4s。
     */
    private fun probeExternalUpload(uploadUrl: String): Boolean {
        val call = externalProbeClient.newCall(
            Request.Builder()
                .url(uploadUrl)
                .post(ByteArray(1).toRequestBody(SPEED_UPLOAD_MEDIA_TYPE))
                .build()
        )
        val closer: () -> Unit = { runCatching { call.cancel() } }
        speedTestClosers += closer
        return try {
            call.execute().use { it.isSuccessful }
        } catch (e: Exception) {
            DebugLog.w("NetworkModule", "外网上行端点不可用: ${e.message}")
            false
        } finally {
            speedTestClosers -= closer
        }
    }

    /**
     * 上行阶段。[uploadUrl] = null 走内网（core 的丢弃汇），否则 POST 到这个外网地址。
     * 外网节点没有上行地址时不会走到这里（[startSpeedTest] 已经把 uploadEnabled 置 false）。
     */
    private suspend fun runUploadPhase(
        template: SpeedTestState,
        uploadUrl: String?
    ): PhaseResult = coroutineScope {
        val counter = AtomicLong(0)
        val liveStreams = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + SPEED_UPLOAD_MAX_SEC * 1000L
        val writers = (0 until SPEED_STREAMS).map { i ->
            launch(Dispatchers.IO) {
                liveStreams.incrementAndGet()
                try {
                    if (uploadUrl == null) {
                        api.speedTestUpload(uploadBody(counter, deadlineMs, stop)).close()
                    } else {
                        uploadExternal(uploadUrl, counter, deadlineMs, stop)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    liveStreams.decrementAndGet()
                    DebugLog.w("NetworkModule", "上行流 #$i 结束: ${e.message}")
                }
            }
        }
        val result = sampleThroughput(
            template = template,
            phase = SpeedTestPhase.UPLOAD,
            counter = counter,
            minSec = SPEED_UPLOAD_MIN_SEC,
            maxSec = SPEED_UPLOAD_MAX_SEC,
            progressBase = DOWNLOAD_PROGRESS_SPAN,
            progressSpan = 1f - DOWNLOAD_PROGRESS_SPAN,
            streamsProvider = { liveStreams.get() }
        ).also {
            // 必须放在 also/finally 语义里：sampleThroughput 在用户取消时会抛
            // CancellationException，若只在正常返回后 stop.set(true)，被取消的那次上行
            // 写循环就只剩全局 speedTestAborted 一个出口——而下一次测速开头会把它重置为
            // false，于是旧写循环"复活"，继续占带宽到自己的 10s 预算，污染新一次测量。
            stop.set(true)
        }
        writers.forEach { it.cancel() }
        result
    }

    /**
     * 外网上行：同一条流反复发**分段**的 chunked POST，直到时间预算到点或被 [stop] 叫停。
     *
     * 为什么要分段而不是像内网那样一个请求写到底：边缘平台对单个请求体有硬上限
     * （Cloudflare Workers/Pages 免费版 100MB 一档），超了直接 413 掐断，那条流剩下的时间
     * 全是空转、平均值被拉低。按 [SPEED_UPLOAD_EXTERNAL_POST_BYTES] 分段后，写满一段就正常
     * 收尾再发下一段——连接是复用的，代价只有每段一个 RTT。
     *
     * 非 2xx 就整条流退出、不重试：限流/掐连接的情况下重试只会把带宽让给失败的请求。
     * 少一条流由 `streamsProvider` 如实反映。
     */
    private suspend fun uploadExternal(
        uploadUrl: String,
        counter: AtomicLong,
        deadlineMs: Long,
        stop: AtomicBoolean
    ) {
        val ctx = currentCoroutineContext()
        while (ctx.isActive && !stop.get() && !speedTestAborted &&
            android.os.SystemClock.elapsedRealtime() < deadlineMs
        ) {
            val call = externalSpeedClient.newCall(
                Request.Builder()
                    .url(uploadUrl)
                    .post(uploadBody(counter, deadlineMs, stop, SPEED_UPLOAD_EXTERNAL_POST_BYTES))
                    .build()
            )
            speedTestClosers += { runCatching { call.cancel() } }
            val ok = try {
                call.execute().use { it.isSuccessful }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.w("NetworkModule", "外网上行分段失败: ${e.message}")
                return
            }
            if (!ok) return
        }
    }

    /**
     * 上行请求体：持续写填充块直到时间预算到点、写满 [maxBytes]，或被 [stop] 叫停。
     *
     * `contentLength() = -1` → chunked，不必预先知道要发多少字节；content-type 显式设成
     * octet-stream，避免 NetworkLogInterceptor 把「文本类型 + 未知长度」的请求体整体缓冲进内存
     * （该拦截器另有 isOneShot 守卫，双重保险）。
     * 每块写完立即 flush：让计数反映真正进了 socket 的字节，而不是 okio 缓冲区里的。
     *
     * [maxBytes] 只有外网用（见 [uploadExternal]）：边缘平台对单个请求体有上限，写满就正常收尾
     * 让调用方再发一段。内网写 core 自己的丢弃汇，没有这个限制，默认不设上限。
     *
     * `isOneShot() = true` 是必须的：本请求体不可重放（字节是现场生成、且已经计进 [counter]），
     * 默认的 `false` 允许 OkHttp 在连接失败时重试并**再调一次 `writeTo`**，那样这条流的字节会被
     * 重复累加，测出来的上行速率虚高。
     */
    private fun uploadBody(
        counter: AtomicLong,
        deadlineMs: Long,
        stop: AtomicBoolean,
        maxBytes: Long = Long.MAX_VALUE
    ): okhttp3.RequestBody = object : okhttp3.RequestBody() {
        override fun contentType() = SPEED_UPLOAD_MEDIA_TYPE
        override fun contentLength(): Long = -1

        /**
         * 必须声明一次性：默认 `false` 时 OkHttp 在连接失败重试（`retryOnConnectionFailure`）
         * 时会**再调一次 writeTo**，而 [counter] 已经把第一次写进去的字节算过了 → 上行速率虚高；
         * 且重试发生在时间预算到点之后时，写循环会一轮都不执行、发出一个空请求体。
         */
        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            val chunk = ByteArray(SPEED_UPLOAD_CHUNK_BYTES) { 0x66.toByte() }
            var written = 0L
            while (!stop.get() && !speedTestAborted &&
                written < maxBytes &&
                android.os.SystemClock.elapsedRealtime() < deadlineMs
            ) {
                sink.write(chunk)
                sink.flush()
                written += chunk.size
                counter.addAndGet(chunk.size.toLong())
            }
        }
    }

    /**
     * 采样协程：按固定节拍读 [counter] 的差值算瞬时速率，并发射 UI 状态。
     * 与搬运协程解耦，所以流数变化 / 某条流中途断开都不影响采样节拍。
     */
    private suspend fun sampleThroughput(
        template: SpeedTestState,
        phase: SpeedTestPhase,
        counter: AtomicLong,
        minSec: Int,
        maxSec: Int,
        progressBase: Float,
        progressSpan: Float,
        streamsProvider: () -> Int
    ): PhaseResult {
        val isUpload = phase == SpeedTestPhase.UPLOAD
        val startMs = android.os.SystemClock.elapsedRealtime()
        val deadlineMs = startMs + maxSec * 1000L
        var lastTickMs = startMs
        var lastBytes = 0L
        var rampEndMs = -1L
        var rampBytes = 0L
        var peak = 0.0
        val samples = ArrayList<Float>(SpeedTestState.SAMPLE_LIMIT)
        val smooth = ArrayDeque<Double>()
        while (currentCoroutineContext().isActive) {
            delay(SPEED_EMIT_INTERVAL_MS)
            val now = android.os.SystemClock.elapsedRealtime()
            val windowMs = now - lastTickMs
            if (windowMs <= 0) continue
            val bytes = counter.get()
            val instantMbps = (bytes - lastBytes) * 8.0 / (windowMs / 1000.0) / 1_000_000
            lastTickMs = now
            lastBytes = bytes
            val sinceStartMs = now - startMs
            if (rampEndMs < 0 && sinceStartMs >= SPEED_RAMP_UP_MS) {
                rampEndMs = now
                rampBytes = bytes
            }
            smooth.addLast(instantMbps)
            if (smooth.size > SPEED_MEDIAN_WINDOW) smooth.removeFirst()
            val smoothed = smooth.sorted()[smooth.size / 2]
            if (rampEndMs > 0 && smoothed > peak) peak = smoothed
            if (samples.size >= SpeedTestState.SAMPLE_LIMIT) samples.removeAt(0)
            samples += instantMbps.toFloat()
            val elapsedSec = sinceStartMs / 1000.0
            val avgMbps = averageMbps(bytes, rampEndMs, rampBytes, now, startMs)
            _speedTestState.value = template.copy(
                phase = phase,
                currentMbps = smoothed,
                avgMbps = if (isUpload) template.avgMbps else avgMbps,
                peakMbps = if (isUpload) template.peakMbps else maxOf(template.peakMbps, peak),
                uploadMbps = if (isUpload) avgMbps else template.uploadMbps,
                streams = streamsProvider(),
                totalBytes = template.totalBytes + bytes,
                elapsedSec = elapsedSec,
                progress = (progressBase + progressSpan * (elapsedSec / maxSec).toFloat())
                    .coerceIn(0f, 1f),
                samples = samples.toList()
            )
            if (now >= deadlineMs) break
            // 智能收尾：已过最短时长、已跨过爬坡段、且速率已稳定 → 不必把上限跑满
            if (elapsedSec >= minSec && rampEndMs > 0 && isSpeedStable(samples)) break
        }
        if (!currentCoroutineContext().isActive) throw CancellationException("测速已取消")
        val endMs = android.os.SystemClock.elapsedRealtime()
        val bytes = counter.get()
        val avgMbps = averageMbps(bytes, rampEndMs, rampBytes, endMs, startMs)
        return PhaseResult(
            avgMbps = avgMbps,
            peakMbps = maxOf(peak, avgMbps),
            bytes = bytes,
            elapsedSec = (endMs - startMs) / 1000.0,
            samples = samples.toList(),
            streams = streamsProvider()
        )
    }

    /** 平均速率：爬坡段已过就只算爬坡之后的窗口，否则退回全程平均。 */
    private fun averageMbps(
        bytes: Long,
        rampEndMs: Long,
        rampBytes: Long,
        nowMs: Long,
        startMs: Long
    ): Double {
        val postRamp = rampEndMs > 0 && nowMs > rampEndMs
        val deltaBytes = if (postRamp) bytes - rampBytes else bytes
        val deltaMs = if (postRamp) nowMs - rampEndMs else nowMs - startMs
        if (deltaMs <= 0L || deltaBytes <= 0L) return 0.0
        return deltaBytes * 8.0 / (deltaMs / 1000.0) / 1_000_000
    }

    /** 关闭当前登记的所有测速流/请求，并清空钩子表。 */
    private fun closeAllSpeedStreams() {
        val closers = speedTestClosers.toList()
        speedTestClosers.clear()
        closers.forEach { runCatching { it() } }
    }

    private inline fun closeQuietly(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { DebugLog.w("NetworkModule", "Failed to close $what: ${e.message}") }
    }

    /**
     * 速率是否已稳定：取最近 [SPEED_STABLE_WINDOW] 个采样，窗口内每个点相对均值的偏差
     * 都小于 [SPEED_STABLE_TOLERANCE] 即认为稳定。采样不足或均值过小（≈没流量）时不算稳定。
     */
    private fun isSpeedStable(samples: List<Float>): Boolean {
        if (samples.size < SPEED_STABLE_WINDOW) return false
        var sum = 0.0
        for (i in samples.size - SPEED_STABLE_WINDOW until samples.size) sum += samples[i]
        val mean = sum / SPEED_STABLE_WINDOW
        if (mean <= 0.5) return false
        for (i in samples.size - SPEED_STABLE_WINDOW until samples.size) {
            if (kotlin.math.abs(samples[i] - mean) / mean > SPEED_STABLE_TOLERANCE) return false
        }
        return true
    }

    /**
     * 取消正在进行的测速。
     *
     * 注意关流必须丢到 IO 线程：`Socket`/`InputStream.close()` 是阻塞 IO，传输中调用可能卡住调用线程；
     * 而本方法会被「取消按钮」和「离开页面的 onDispose」从**主线程**调用——在主线程关流会直接卡死
     * UI（表现为整个 App 白屏无响应）。
     */
    fun cancelSpeedTest() {
        speedTestAborted = true
        val closers = speedTestClosers.toList()
        speedTestClosers.clear()
        speedTestJob?.cancel()
        speedTestJob = null
        if (closers.isNotEmpty()) {
            scope.launch(Dispatchers.IO) { closers.forEach { runCatching { it() } } }
        }
    }

    // ── Cell ──
    /**
     * 基站页数据：小区信息 + 信号。
     *
     * **两个都要拉**：`/api/network/cell-info` 只带 LTE 侧字段与邻区/已锁定列表，
     * 当前服务小区（NR 优先的 `band_label` / `pci` / `arfcn`）在 `/api/network/signal`
     * 的服务小区统一字段里（计划书 1.10）。少拉一个，5G 驻网时基站页会显示 4G 的 PCI/频点。
     */
    /** @param silent 失败不写 `errorMessage`（预加载专用，见 [loadNetworkAll]）。 */
    fun loadCellInfo(silent: Boolean = false) {
        scope.launch {
            try {
                val cell = async { runCatching { api.getCellInfo() } }
                val signal = async { runCatching { api.getSignalInfo() } }
                // 邻区单独再拉一次：/cell-info 走 core 的 responseCache，可能是上一次的快照；
                // /neighbor-cells 是实时向设备查询，「刷新」按钮要的就是这一份。
                // 失败不写 errorMessage —— 邻区拿不到只是少一块展示，cellInfo 那份还能兜住。
                val neighbor = async { runCatching { api.getNeighborCells() } }
                val rCell = cell.await(); val rSignal = signal.await(); val rNeighbor = neighbor.await()
                rNeighbor.exceptionOrNull()?.let { DebugLog.w("Network", "邻区实时查询失败，回落 cell-info 快照", it) }
                _networkState.update { it.copy(
                    cellInfo = rCell.getOrNull() ?: it.cellInfo,
                    signalInfo = rSignal.getOrNull() ?: it.signalInfo,
                    neighborCells = rNeighbor.getOrNull() ?: it.neighborCells,
                    errorMessage = if (silent) it.errorMessage else {
                        rCell.exceptionOrNull()?.let { e -> "基站信息查询失败: ${e.message}" } ?: it.errorMessage
                    },
                    loadVersion = System.currentTimeMillis()
                ) }
            }
            catch (e: Exception) {
                if (silent) DebugLog.w("Network", "基站信息查询失败（静默）: ${e.message}")
                else _networkState.update { it.copy(errorMessage = "基站信息查询失败: ${e.message}") }
            }
        }
    }

    /**
     * 锁基站。networkType 只接受 core 契约值域 "LTE" / "NR"（core 会自行映射成设备侧 12/16），
     * 不要再传设备数字码或旧入参名 rat（core 仍兼容但已打 WARN，下一版会删）。
     *
     * ── 2026-10-14（修 G3）─────────────────────────────────────────────────────
     * **原来是什么样**：`api.cellLock(...)` 成功后 `delay(500); loadCellInfo()` —— 一次回读、
     * **无判据**，读到什么就渲染什么。
     * **为什么不对**：锁定是异步生效的（设备要重新选小区、重新驻网），500ms 后那次回读几乎必然
     * 还是旧小区，于是"点了锁定、界面显示的还是原来那个基站"，用户无从判断锁上没有；
     * 而且只有一次机会、没有预算 —— 把"命令已下发"当成了"已经锁上"。
     * **现在怎么改**：接 [opController]，判据 = **服务小区就是目标小区**（pci + earfcn 都对上），
     * 回读节奏与预算取 contract 的 [NetworkMode.SwitchProbe]（13 次 / 30.6s）。
     *
     * **契约（core 侧，字段名不猜）**：
     * - 写：`POST /api/device/cell-lock`，入参 `pci` / `earfcn` / `network_type`，
     *   回 `{"success": bool}` —— `core/api/src/main/java/com/ufi_axis_core/api/routes/DeviceRoutes.kt:625-653`。
     *   成功后 core 会清 `network:cell-info` 缓存（同文件 `:649`），所以下面的回读拿到的是**写后**
     *   快照，而不是那 20s TTL（`core/cache/.../ResponseCache.kt:590`）的旧值。
     * - 读服务小区：`GET /api/network/signal` 的统一字段 `pci` / `arfcn`
     *   （`app/data/src/main/java/com/ufi_axis/data/model/Models.kt:344-364`，core 已做 NR 优先合并）。
     * - 读 LTE 侧：`GET /api/network/cell-info` 的 `Lte_pci` / `Lte_fcn`
     *   （`DeviceFields.CellInfo`，`app/data/.../model/DeviceResponses.kt:241-253`）。
     */
    fun cellLock(pci: String, earfcn: String, networkType: String) {
        val targetPci = pci.trim()
        val targetEarfcn = earfcn.trim()
        opController.run(object : ApiOperationController.Spec {
            override val title = "基站锁定"
            override val runningText = "正在锁定基站 PCI $targetPci"
            override val successText = "已锁定基站 PCI $targetPci"
            override val hint = "设备需要重新驻网，期间信号会短暂中断"

            override suspend fun submit(): String? {
                val resp = try {
                    api.cellLock(mapOf(
                        "pci" to targetPci, "earfcn" to targetEarfcn, "network_type" to networkType
                    ))
                } catch (e: Exception) { return e.message ?: "锁基站失败" }
                return if (resp.success) null else "锁基站失败"
            }

            override suspend fun verify(): Boolean {
                // 两个来源都读：/signal 是 NR 优先的合并值，/cell-info 只有 LTE 字段，
                // 锁 LTE 小区而设备仍驻留 NR 时只有后者能确认（见 servingCellMatches）。
                val sig = runCatching { api.getSignalInfo() }.getOrNull()
                val cell = runCatching { api.getCellInfo() }.getOrNull()
                _networkState.update {
                    it.copy(signalInfo = sig ?: it.signalInfo, cellInfo = cell ?: it.cellInfo)
                }
                return servingCellMatches(sig, cell, targetPci, targetEarfcn)
            }

            override fun onApplied() {
                // 写后回读：基站页的"已锁定基站"列表与邻区都可能变了
                loadCellInfo()
                emitWriteNotice(successText)
            }
        })
    }

    /**
     * 解锁全部基站（`POST /api/device/cell-unlock`，回 `{"success": bool}`）。
     *
     * 2026-10-14（修 G3，同 [cellLock]）：原来同样是 `delay(500); loadCellInfo()` 的单次无判据回读。
     * 现在判据 = **已锁定列表为空**（`locked_cell_info`，core 已归一成真数组，见 [lockedCellCount]）。
     * 解锁同样是异步生效的，所以走同一套 13 次 / 30.6s 预算。
     */
    fun unlockAllCell() {
        opController.run(object : ApiOperationController.Spec {
            override val title = "基站解锁"
            override val runningText = "正在解锁全部基站"
            override val successText = "已解锁全部基站"
            override val hint = "设备需要重新驻网，期间信号会短暂中断"

            override suspend fun submit(): String? {
                val resp = try { api.unlockAllCell() }
                    catch (e: Exception) { return e.message ?: "解锁基站失败" }
                return if (resp.success) null else "解锁基站失败"
            }

            override suspend fun verify(): Boolean {
                val cell = runCatching { api.getCellInfo() }.getOrNull()
                if (cell != null) _networkState.update { it.copy(cellInfo = cell) }
                // lockedCellCount 返回 null = 这次没读到列表 → 按未命中继续等，不当成"已解锁"
                return lockedCellCount(cell) == 0
            }

            override fun onApplied() {
                loadCellInfo()
                emitWriteNotice(successText)
            }
        })
    }

    /**
     * 服务小区是否就是目标小区 —— [cellLock] 的 verify 判据。
     *
     * **为什么两个来源都要看**：`/api/network/signal` 的服务小区字段是 core 按"NR 优先、LTE 兜底"
     * 合并过的，而 `/api/network/cell-info` **只有 LTE 侧字段**。锁 LTE 小区时设备可能仍驻留在 NR
     * （NSA 双连接），这时只有 cell-info 那份能确认；锁 NR 小区时只有 signal 那份能确认。
     * 任一命中即算生效：两个来源的 (pci, 频点) 组合不会互相冒充 —— NR-ARFCN 与 LTE EARFCN
     * 的取值区间不重叠（NR 高频段 ARFCN 远大于 EARFCN 上限）。
     */
    private fun servingCellMatches(
        signal: SignalInfo?, cell: CellInfoResponse?, pci: String, earfcn: String
    ): Boolean {
        // pci / arfcn 是非负量，-1 是 core 的"无数据"哨兵（见 contract 的 Units），先滤掉
        val signalPci = signal?.pci?.takeIf { it >= 0 }?.toString()
        val signalFcn = signal?.arfcn?.takeIf { it >= 0 }?.toString()
        val ltePci = cell?.ltePci?.trim()?.takeIf { it.isNotEmpty() }
        val lteFcn = cell?.lteEarfcn?.trim()?.takeIf { it.isNotEmpty() }
        return (signalPci == pci && signalFcn == earfcn) || (ltePci == pci && lteFcn == earfcn)
    }

    /**
     * 已锁定基站条数 —— [unlockAllCell] 的 verify 判据。
     *
     * 容器缺失 / 空串按 **0 条**处理：与基站页 `parseLockedCellsDlg` 的显示口径一致
     * （那边 null 也画"暂无已锁定基站"）。不这么归一的话，一次**正常**的解锁会因为设备回了
     * `locked_cell_info: ""` 而被判成"从未生效"，白等满 30.6s 再报超时。
     *
     * @return null = 这次回读没拿到 / 解不出列表 —— 调用方按未命中继续等，**不要**当成"已解锁"。
     */
    private fun lockedCellCount(cell: CellInfoResponse?): Int? {
        if (cell == null) return null
        cell.lockedCells?.let { return it.size }
        val raw = cell.lockedCellInfo
        if (raw == null || raw is JsonNull) return 0
        val text = (raw as? JsonPrimitive)?.content
        return if (text.isNullOrBlank()) 0 else null
    }

    // ── LAN ──
    fun loadLanSettings() {
        scope.launch {
            _networkState.update { it.copy(errorMessage = null, loadVersion = System.currentTimeMillis()) }
            try { _networkState.update { it.copy(lanSettings = api.getLanSettings(), loadVersion = System.currentTimeMillis()) } }
            catch (e: Exception) { _networkState.update { it.copy(errorMessage = "LAN设置查询失败: ${e.message}") } }
        }
    }

    /**
     * 下发 DHCP / LAN 设置。
     *
     * 失败路径与 `resp.success` 校验本来就做对了（它是 §4.4 的范本），2026-09-22 只补成功提示：
     * 在这之前保存成功是静默的 —— 写后回读 500ms 才回来，那期间界面上什么都不变，
     * 用户分不清"点了没反应"和"存进去了"。
     */
    fun setDhcpSetting(lanIp: String, lanNetmask: String, dhcpType: String, dhcpStart: String, dhcpEnd: String, dhcpLease: String) {
        scope.launch {
            try {
                val resp = api.setDhcpSetting(mapOf("lan_ip" to lanIp, "lan_netmask" to lanNetmask, "dhcp_type" to dhcpType, "dhcp_start" to dhcpStart, "dhcp_end" to dhcpEnd, "dhcp_lease" to dhcpLease))
                if (resp.success) {
                    emitWriteNotice(
                        "DHCP 设置已保存",
                        // 改 LAN 网段会让已连设备的租约失效，这是用户会观察到的副作用
                        subtitle = "已连接的设备可能需要重新获取 IP"
                    )
                    delay(500); loadLanSettings()
                }
                else _networkState.value = _networkState.value.copy(errorMessage = "DHCP设置失败")
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "DHCP设置失败: ${e.message}") }
        }
    }

    // ── Service Control（2026-08-26）──
    // 停/启的是后端"后台采集服务"（core 的 DataScheduler），HTTP 服务始终在跑，
    // 所以停止之后仍然能远程再启动。"重启服务"才是进程级完全重启。

    /** 服务状态最近一次成功读取的时刻（单调时钟，`0` = 本进程内从未成功）。 */
    @Volatile private var serviceStatusSuccessElapsed: Long = 0L

    /**
     * 读后台采集服务状态（`GET /api/service/status`，core 侧只读本机设置、无设备 I/O）。
     *
     * 加新鲜度闸门的原因：这一个调用在冷启动时至少发两次 —— `DashboardScreen` 与
     * `SettingsScreen` 各有一条 `LaunchedEffect(Unit)`，再加预加载协调器就是三次。
     * 请求本身很轻，但重复调用会把 Logcat 里的真实请求数搅浑，排查冷启动时序时碍事。
     *
     * @param force  绕过新鲜度窗口。写后回读（开关服务 / 重启服务）必须传 true。
     * @param silent 失败不写 `errorMessage`（预加载专用）。`serviceState.errorMessage`
     *   会经 `MainViewModel.rawGlobalError` 冒成全局 Toast。
     */
    fun loadServiceStatus(force: Boolean = false, silent: Boolean = false) {
        if (!force &&
            _serviceState.value.loaded &&
            isForegroundDataFresh(serviceStatusSuccessElapsed, SystemClock.elapsedRealtime())
        ) {
            return
        }
        scope.launch {
            try {
                val s = api.getServiceStatus()
                _serviceState.value = _serviceState.value.copy(
                    loaded = true,
                    enabled = s.enabled,
                    collecting = s.collecting,
                    uptimeMs = s.uptimeMs,
                    autoStartOnBoot = s.autoStartOnBoot,
                    restarting = false,
                    errorMessage = null
                )
                serviceStatusSuccessElapsed = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                // restarting 一并清掉：否则重启后若服务还没起来，徽标会永久卡在"重启中"
                _serviceState.value = _serviceState.value.copy(
                    restarting = false,
                    errorMessage = if (silent) _serviceState.value.errorMessage
                    else "服务状态读取失败: ${e.message}"
                )
            }
        }
    }

    /** 后台采集服务开关。失败时回滚到服务端真实状态，不留下乐观的假开关。 */
    fun setBackgroundService(enabled: Boolean) {
        scope.launch {
            _serviceState.value = _serviceState.value.copy(isBusy = true, errorMessage = null)
            try {
                val s = if (enabled) api.startBackgroundService() else api.stopBackgroundService()
                _serviceState.value = _serviceState.value.copy(
                    loaded = true,
                    enabled = s.enabled,
                    collecting = s.collecting,
                    uptimeMs = s.uptimeMs,
                    autoStartOnBoot = s.autoStartOnBoot,
                    isBusy = false
                )
                emitDashboardError(if (s.enabled) "后台服务已启动" else "后台服务已停止（HTTP 服务仍在运行）")
            } catch (e: Exception) {
                _serviceState.value = _serviceState.value.copy(isBusy = false, errorMessage = "操作失败: ${e.message}")
                loadServiceStatus(force = true)
            }
        }
    }

    fun setServiceAutoStart(enabled: Boolean) {
        scope.launch {
            try {
                val s = api.setServiceAutoStart(mapOf("enabled" to enabled))
                _serviceState.value = _serviceState.value.copy(loaded = true, autoStartOnBoot = s.autoStartOnBoot)
            } catch (e: Exception) {
                _serviceState.value = _serviceState.value.copy(errorMessage = "设置失败: ${e.message}")
                loadServiceStatus(force = true)
            }
        }
    }

    /**
     * 完全重启后端服务：HTTP 会中断约 10 秒，期间所有请求都会失败，这是预期行为。
     *
     * ## 2026-09-14：从「固定等 13s 回读一次」改成「带超时的重连轮询」
     * 原来是 `delay(13_000)` 之后只 `loadServiceStatus()` 一次。设备端稍慢一点（或者
     * core 侧的重启闹钟被系统拒了）就会落在这一次之外，于是 `restarting` 被清成 false、
     * `enabled` 还是上次那个旧的 true，UI **既不提示停止也不提示失败**，就地卡住 ——
     * 用户看到的「点了之后断联」有一半是这个 UI 假象。
     *
     * 现在：每 2s 探一次直到 [RESTART_PROBE_TIMEOUT_MS]；成功即刷新并给出成功提示，
     * 超时则明确报失败并告知补救方式（core 侧仍有 shell + 闹钟两路在尝试，见
     * `BackendService.requestRestart`，所以文案是"仍未恢复"而不是"重启失败"）。
     */
    fun restartBackendService() {
        scope.launch {
            _serviceState.value = _serviceState.value.copy(isBusy = true, restarting = true, errorMessage = null)
            try {
                val resp = api.restartBackendService()
                emitDashboardError(resp.hint ?: "后端服务正在重启，约 10 秒后恢复")
            } catch (e: Exception) {
                // 重启会掐断连接，请求失败反而是常见结果 —— 不当成错误刷红。
                DebugLog.w("Network", "restart 请求未收到响应（服务可能已断开）: ${e.message}")
                emitDashboardError("后端服务正在重启，约 10 秒后恢复")
            }
            _serviceState.value = _serviceState.value.copy(isBusy = false)

            // core 侧闹钟延迟约 10s、shell 侧约 4s；先等一段再开始探，避免探到"还没停下来"的旧实例
            delay(RESTART_PROBE_INITIAL_DELAY_MS)
            val deadline = SystemClock.elapsedRealtime() + RESTART_PROBE_TIMEOUT_MS
            var recovered = false
            while (SystemClock.elapsedRealtime() < deadline) {
                val ok = runCatching { api.getServiceStatus() }.getOrNull()
                if (ok != null) {
                    _serviceState.value = _serviceState.value.copy(
                        loaded = true,
                        enabled = ok.enabled,
                        collecting = ok.collecting,
                        uptimeMs = ok.uptimeMs,
                        autoStartOnBoot = ok.autoStartOnBoot,
                        restarting = false,
                        errorMessage = null
                    )
                    recovered = true
                    emitDashboardError("后端服务已恢复")
                    break
                }
                delay(RESTART_PROBE_INTERVAL_MS)
            }
            if (!recovered) {
                _serviceState.value = _serviceState.value.copy(
                    restarting = false,
                    errorMessage = "后端服务在 ${RESTART_PROBE_TIMEOUT_MS / 1000} 秒内仍未恢复。" +
                        "设备端仍在自行重试（shell + 定时器两路）；" +
                        "若持续无响应，请在设备上手动打开 UFI-AXIS Core。"
                )
            }
        }
    }

    // ── Web 控制面板资源（2026-08-27）──
    // core 支持上传一份 web 面板产物覆盖 APK 内置版本（override 落在 filesDir/web）。
    // 一旦上传的是坏产物，面板本身就打不开了 —— 那条恢复入口在面板里等于没有，
    // 所以恢复必须由 app 提供：这就是 loadWebAssetInfo / resetWebAssets 存在的理由。

    fun loadWebAssetInfo() {
        scope.launch {
            try {
                val obj = api.getWebAssetVersion().jsonObject
                fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
                val isOverride = str("mode") == "override"
                _webAssetState.value = _webAssetState.value.copy(
                    loaded = true,
                    isOverride = isOverride,
                    version = str("version"),
                    // bundled 模式下 core 不返回 bundledVersion，此时它就是 version 本身
                    bundledVersion = str("bundledVersion") ?: str("version"),
                    hasBackup = (obj["hasBackup"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    errorMessage = null
                )
            } catch (e: Exception) {
                // 不置 loaded：读失败时 UI 要显示"未知"，不能把默认值当成"已是内置版本"
                _webAssetState.value = _webAssetState.value.copy(
                    errorMessage = "Web 资源信息读取失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 清除 web override，恢复 APK 内置面板。
     * core 删完会复核 `hasOverride()`，删不干净返回 500 → 这里会走 catch 报错，不会谎报成功。
     * 备份也会一起删掉，所以之后没有回滚可用。
     */
    fun resetWebAssets() {
        scope.launch {
            _webAssetState.value = _webAssetState.value.copy(isBusy = true, errorMessage = null)
            try {
                api.clearWebAssets()
                emitDashboardError("Web 面板已恢复内置版本")
            } catch (e: Exception) {
                _webAssetState.value = _webAssetState.value.copy(errorMessage = "恢复失败: ${e.message}")
                emitDashboardError("Web 面板恢复失败: ${e.message}")
            }
            _webAssetState.value = _webAssetState.value.copy(isBusy = false)
            // 成功与否都回读一次：失败时 override 可能仍在，UI 必须反映真实状态
            loadWebAssetInfo()
        }
    }

    // ── Web 面板：自动更新 / 回滚 / 上传（2026-08-30 补齐 status|check|update|rollback）──
    // 这一组**没有 WS 推送**，进度只能轮询 GET /api/web/status，所以下面统一走 [pollWebUpdate]。

    /** 单飞轮询任务：重复点「检查更新」不该叠加轮询协程。 */
    private var webPollJob: Job? = null

    /** 读一次更新状态快照。只读，失败不写 errorMessage（页面上还有别的信息要显示）。 */
    fun loadWebUpdateStatus() {
        scope.launch {
            try {
                _webAssetState.value = _webAssetState.value.copy(updateStatus = api.getWebUpdateStatus())
            } catch (e: Exception) {
                DebugLog.w("Network", "Web 面板更新状态读取失败", e)
            }
        }
    }

    /**
     * 触发 core 从 `version.json` 的 `web` 对象自拉取并覆盖安装。
     *
     * `POST /api/web/check` 回的是**状态快照**而不是「本次是否发起成功」：已经在跑时它不报错，
     * 只把当前状态给你 —— 所以「点了没反应」要看 state，不能当成请求失败。
     */
    fun checkWebAssetUpdate() {
        scope.launch {
            _webAssetState.value = _webAssetState.value.copy(isBusy = true, errorMessage = null)
            try {
                val status = api.checkWebAssetUpdate()
                _webAssetState.value = _webAssetState.value.copy(updateStatus = status)
                pollWebUpdate()
            } catch (e: Exception) {
                _webAssetState.value = _webAssetState.value.copy(
                    isBusy = false, errorMessage = "检查更新失败: ${e.message}")
            }
        }
    }

    /**
     * 回滚到上一版面板。**一次性**：成功后备份就没了。
     *
     * 没有可用备份时 core 回 400（Retrofit 抛异常）。注意它的判据比 [WebAssetState.hasBackup] 严
     * （要求备份里同时有 `version.json` 与 `index.html`），所以 hasBackup=true 也可能失败。
     */
    fun rollbackWebAssets() {
        scope.launch {
            _webAssetState.value = _webAssetState.value.copy(isBusy = true, errorMessage = null)
            try {
                api.rollbackWebAssets()
                emitDashboardError("Web 面板已回滚到上一版")
            } catch (e: Exception) {
                _webAssetState.value = _webAssetState.value.copy(errorMessage = "回滚失败: ${e.message}")
                emitDashboardError("Web 面板回滚失败: ${e.message}")
            }
            _webAssetState.value = _webAssetState.value.copy(isBusy = false)
            loadWebAssetInfo()
        }
    }

    /**
     * 手动上传面板 ZIP。
     *
     * 客户端先挡两道，省得把几十 MB 传上去才被 core 拒：
     * ① 大小 ≤ [WEB_ZIP_MAX_BYTES]（core 同为 50MB）；② 扩展名是 .zip。
     * ZIP **根目录**必须同时含 `index.html` 与 `version.json`，这一条只有 core 能校验（400）。
     *
     * @return 成功 = true to 提示语；失败 = false to 错误原因（给 UI 弹 Toast，不写常驻错误横幅）
     */
    suspend fun uploadWebAssets(zip: java.io.File): Pair<Boolean, String> {
        if (!zip.name.endsWith(".zip", ignoreCase = true)) return false to "只接受 .zip 文件"
        if (zip.length() > WEB_ZIP_MAX_BYTES) {
            return false to "ZIP 超过 ${WEB_ZIP_MAX_BYTES / 1024 / 1024}MB 上传限制"
        }
        _webAssetState.value = _webAssetState.value.copy(isBusy = true, errorMessage = null)
        return try {
            val body = zip.asRequestBody("application/zip".toMediaType())
            // 字段名固定 "file"：core 的 multipart 处理只认这个名字，改名会被当成没收到文件（400）
            api.uploadWebAssets(MultipartBody.Part.createFormData("file", zip.name, body))
            true to "面板已更新"
        } catch (e: Exception) {
            DebugLog.w("Network", "Web 面板上传失败", e)
            false to (e.message ?: "上传失败")
        } finally {
            _webAssetState.value = _webAssetState.value.copy(isBusy = false)
            loadWebAssetInfo()
        }
    }

    /**
     * 轮询更新状态直到终态（done / failed / need_push）或超时。
     *
     * 单飞：先取消上一个轮询任务。超时上限 [WEB_POLL_MAX_TICKS] × [WEB_POLL_INTERVAL_MS] ——
     * 没有上限的话 core 卡在 downloading 会让协程一直转到页面销毁。
     */
    private fun pollWebUpdate() {
        webPollJob?.cancel()
        webPollJob = scope.launch {
            repeat(WEB_POLL_MAX_TICKS) {
                delay(WEB_POLL_INTERVAL_MS)
                val status = try { api.getWebUpdateStatus() } catch (e: Exception) {
                    DebugLog.w("Network", "Web 面板状态轮询失败", e); null
                } ?: return@repeat
                _webAssetState.value = _webAssetState.value.copy(updateStatus = status)
                if (!status.isBusy) {
                    _webAssetState.value = _webAssetState.value.copy(isBusy = false)
                    // 装完了版本号会变，回读一次 /version 让「已上传/内置」那行同步
                    loadWebAssetInfo()
                    return@launch
                }
            }
            _webAssetState.value = _webAssetState.value.copy(isBusy = false)
        }
    }

    // ── Device Control ──
    fun rebootDevice() {        scope.launch {
            try {
                val resp = api.rebootDevice()
                if (resp.success) emitDashboardError("设备正在重启...")
                else emitDashboardError("重启失败")
            } catch (e: Exception) { emitDashboardError("重启失败: ${e.message}") }
        }
    }

    fun factoryReset() {
        scope.launch {
            try { api.factoryReset() }
            catch (e: Exception) { emitDashboardError("恢复出厂失败: ${e.message}") }
        }
    }

    fun shutdownDevice() {
        scope.launch {
            try {
                val resp = api.shutdownDevice()
                if (resp.success) emitDashboardError("设备正在关机...")
                else emitDashboardError("关机失败")
            } catch (e: Exception) { emitDashboardError("关机失败: ${e.message}") }
        }
    }

    fun setDeviceMode(enabled: Boolean) {
        scope.launch {
            try { api.setDeviceMode(mapOf("enabled" to enabled)) }
            catch (e: Exception) { emitDashboardError("ADB调试设置失败: ${e.message}") }
        }
    }

    /**
     * 改设备自带网页后台的登录密码。
     *
     * ## 2026-09-22：改成等结果并返回（阶段 3.2）
     * 原来是 fire-and-forget，而调用方（`ServerConfigScreen` 的「设备后台」弹窗）在调用完
     * 立刻 `prefs.goformPasswordSet = true` + 关窗 + 弹「已保存」。旧密码填错时 core 会拒，
     * 界面却已经关掉并且本地记成"密码已设置" —— 假成功还带一条错误的本地状态。
     *
     * @return true = core 已接受新密码。调用方据此决定关不关弹窗、要不要写本地标记。
     */
    suspend fun changePassword(oldPwd: String, newPwd: String): Boolean = scope.async {
        try {
            val resp = api.changePassword(mapOf("old_password" to oldPwd, "new_password" to newPwd))
            if (!resp.success) {
                _networkState.update { it.copy(errorMessage = "设备后台密码修改失败：设备未接受（请确认当前密码）") }
                return@async false
            }
            emitWriteNotice("设备后台密码已修改")
            true
        } catch (e: Exception) {
            // 带上 core 回的原因（如「原密码不正确」），否则用户只看到 HTTP 4xx/5xx
            val reason = coreRejectReason(e) ?: e.message ?: "未知错误"
            _networkState.update { it.copy(errorMessage = "设备后台密码修改失败：$reason") }
            false
        }
    }.await()

    // ── WiFi Config ──

    /**
     * 保存整份 WiFi 热点配置（`POST /api/wifi/config`）。
     *
     * @param willDisconnect 本次改动是否会让设备重启热点（SSID / 密码 / 加密方式 / 频段 /
     *   隐藏 SSID 任一变化）。true 时把 [WIFI_RESTART_DISCONNECT_HINT] 挂成成功提示的副标题 ——
     *   用户很可能正**通过这个热点**连着设备，得让他知道随之而来的掉线是预期的、不是出错。
     *   只改最大连接数时传 false：那一项不断连，无差别恐吓会让警告被忽略。
     * @return true = 设备已接受这份配置。调用方（弹窗）据此决定关不关窗。
     *
     * 为什么整段跑在本模块的 [scope]（viewModelScope）而不是调用方的协程里：调用方是弹窗，
     * 成功后弹窗会被卸载、它的 `rememberCoroutineScope` 随之取消。`async` + `await` 之后
     * "取消等待"只停掉等待方，写入 / 回读 / 成功提示照样跑完 —— 请求在飞时关掉弹窗
     * 不会留下"写了一半没人收尾"的状态，成功提示也仍然走全局宿主到得了用户眼前。
     */
    suspend fun setWifiConfig(config: Map<String, Any>, willDisconnect: Boolean = false): Boolean =
        scope.async {
            try {
                val resp = api.setWifiConfig(config)
                // 独立检查 success：core 目前把 false 与 500 绑在一起，所以这条分支现在踩不到；
                // 但「200 + success:false」在契约上随时可能出现，不检查就是一个等着被踩的
                // 假成功（口径同 setDhcpSetting）。
                if (!resp.success) {
                    _networkState.update { it.copy(errorMessage = "WiFi 热点保存失败：设备未接受这份配置") }
                    return@async false
                }
                emitWriteNotice(
                    "WiFi 热点已保存",
                    subtitle = if (willDisconnect) WIFI_RESTART_DISCONNECT_HINT else null
                )
                // 主动回读，不等 WS 推送：推送有三个会丢的缺口（core 缓存里没有 wifi:* 条目时
                // 不发、WS 掉线期间丢且重连只补 console:*、降级轮询还有 30s 新鲜度闸门）。
                //
                // 2026-10-12：这里**不再等回读结果**。实测设备重启热点要 15~20 秒
                // （连接 34.7s 断 → 51.3s 通），任何"等回读确认再定成败"的窗口都会撞在
                // 重启期里、必然读不到新值，于是把已经生效的配置误判成失败。
                // 既然失败后没有自愈路径，正确做法是：请求返回就报成功，界面立刻收工；
                // 真正的生效状态交给这次回读去刷新（成不成功它都会去跑）。
                delay(500)
                refreshWifi(force = true)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 2026-10-05 R2-12 修复：scope 取消（退出页面/进程后台）不是「保存失败」，
                // 原样抛回 —— 吞掉会让调用方拿到假的 false，还会让 emitWriteNotice 在取消后
                // 弹「已保存」的假成功。与 core 侧「取消不是失败」纪律对齐。
                throw e
            } catch (e: Exception) {
                // 2026-10-12（用户裁决）：**只在这一步就失败时**才提示，而且走 toast，
                // 不再挂 errorMessage 让弹窗留在屏幕上。
                //
                // 改 WiFi 配置会让设备重启热点，请求大概率等不到响应 —— 那种"失败"其实是
                // 成功。此时任何"保存失败"的提示都是错的，而且把弹窗按在屏幕上让用户以为没生效
                // （用户实测「设备已生效、app 报失败、弹窗又弹出来」）。
                // core 明确拒绝的情况（4xx，如密码长度非法）原因在这一层就有，照原样透出。
                // 分两种失败，走两条通道 —— 用户实测「设备已生效，app 却弹绿色 ✓「保存失败」」：
                // emitWriteNotice 只有 message/subtitle，没有成败标志，UI 图标写死成功，
                // 所以拿它报失败就会得到「绿色对勾 + 失败文案」这种自相矛盾的东西。
                //
                // ① 连不上 = 设备正在重启热点，请求等不到响应。**这不是失败**：用户实测过设备
                //    重启要 15~20 秒，期间设备侧照样应用了配置。这里不能报错，也不该报错。
                // ② core 明确拒绝（4xx，如密码长度非法）= 真失败，走 errorMessage，让全局
                //    错误浮层接手（它有自己的失败样式）。
                val reason = coreRejectReason(e) ?: e.message ?: "未知错误"
                if (isConnectivityFailure(e)) {
                    // 断连不是失败：设备已生效（或即将生效），等它重启完 WS 会重连并推送新状态。
                    DebugLog.i("Network") { "setWifiConfig: 连接中断（设备重启热点），按已提交处理" }
                    emitWriteNotice("WiFi 热点已保存", subtitle = WIFI_RESTART_DISCONNECT_HINT)
                    refreshWifi(force = true)
                    return@async true
                }
                _networkState.update { it.copy(errorMessage = "WiFi 热点保存失败：$reason") }
                false
            }
        }.await()


    /**
     * 切换 WiFi 频段（`POST /api/wifi/band`，`chip1` = 2.4G / `chip2` = 5G）。
     *
     * **为什么是独立的一次下发、不跟 `/config` 合并**：频段不属于热点配置的一部分 ——
     * 设备只认 `switchWiFiChip&ChipEnum=X&GuestEnable=0` 这条命令，语义是「在该频段上启用
     * WiFi」。曾经走 `/config` 的 `chip_index`（落到 `setAccessPointInfo → ChipIndex`）
     * **设备不认**，那就是用户实测「频段改了没反应」的根因，别再合回去。
     *
     * 调用方必须自己先跟用户确认（后果见 [WIFI_BAND_SWITCH_DISCONNECT_HINT]）：本方法
     * 与 core 的路由都不带确认语义。顺序上也要放在 `/config` **之后** —— 这条命令会重启
     * WiFi 模块顶掉连接，先发它，SSID / 密码就没机会落到设备上了。
     *
     * @return true = 设备已接受。失败原因（core 对非法取值回的 400 + 原因）落到全局错误通道，
     *   不压成一句「失败」；调用方据此决定关不关窗。
     *
     * 跑在本模块的 [scope] 而不是调用方协程：理由同 [setWifiConfig] —— 弹窗关掉后
     * 回读与提示仍要跑完。
     */
    suspend fun setWifiBand(chip: String): Boolean =
        scope.async {
            try {
                val resp = api.setWifiBand(mapOf("chip" to chip))
                // 独立检查 success：口径同 setWifiConfig，「200 + success:false」在契约上
                // 随时可能出现，不检查就是一个等着被踩的假成功。
                if (!resp.success) {
                    _networkState.update { it.copy(errorMessage = "WiFi 频段切换失败：设备未接受这个频段") }
                    return@async false
                }
                emitWriteNotice("WiFi 频段已切换", subtitle = WIFI_BAND_SWITCH_DISCONNECT_HINT)
                // 主动回读（force 绕过新鲜度闸门）：这一次要把两件事都反映出来 ——
                // 新的生效频段（wifi_chip），以及「WiFi 被这条命令打开」这个副作用（moduleSwitch）。
                // 500ms 同 setWifiConfig：给设备应用配置的时间，0ms 大概率读回旧值。
                delay(500)
                refreshWifi(force = true)
                true
            } catch (e: Exception) {
                // 2026-10-12：换频段会重启 WiFi 模块，连接必然中断 —— 那一发等不到响应就
                // ConnectException，但设备侧可能已经切过去了。口径同 setWifiConfig：
                // 先回读核实生效频段，再决定成败，否则用户看到的是「设备已切换、app 却报失败」。
                // 2026-10-12（用户裁决）：口径同 setWifiConfig —— 换频段同样会重启 WiFi 模块，
                // 请求等不到响应不代表没生效，所以只在**这一步就失败**时提示，且走 toast 通道
                // （[emitWriteNotice]）而不是把弹窗按在屏幕上。
                // 带出 core 回的原因（取值域非法时 profile 的 validate 会回 400 + 原因），
                // 否则用户只看到一句 HTTP 400
                val reason = coreRejectReason(e) ?: e.message ?: "未知错误"
                if (isConnectivityFailure(e)) {
                    // 同 setWifiConfig：重启 WiFi 模块期间的断连不是失败，设备侧照样生效。
                    DebugLog.i("Network") { "setWifiBand: 连接中断（设备重启 WiFi 模块），按已提交处理" }
                    emitWriteNotice("WiFi 频段已切换", subtitle = WIFI_BAND_SWITCH_DISCONNECT_HINT)
                    refreshWifi(force = true)
                    return@async true
                }
                _networkState.update { it.copy(errorMessage = "WiFi 频段切换失败：$reason") }
                false
            }
        }.await()

    fun setWifiPower(level: Int) {
        scope.launch {
            try { api.setWifiPower(mapOf("level" to level)) }
            catch (e: Exception) { emitDashboardError("功率设置失败: ${e.message}") }
        }
    }

    // ── Enhanced Device Settings ──
    /**
     * 开 / 关 WiFi 热点。
     *
     * 2026-08-30：原来这里也调 `waitForNetworkReady()` —— 那个探针判的是**蜂窝**是否连上，
     * 与 WiFi 开关毫无关系：蜂窝已连时它立刻返回（等于没等），蜂窝没连时反而白等满 5 秒。
     * 改成乐观回显 + [refreshWifi]（只拉 WiFi 设置与客户端列表，不做全量刷新）。
     */
    fun setWifiEnabled(enabled: Boolean) {
        // 2026-10-05 P7：接入确认器（原【反馈】S5）——WiFi 总开关生效需要模块重启，
        // 原乐观回显在设备拒绝/超时时只能靠 catch 兜底，现在走「执行→等待→回读确认」。
        opController.run(object : ApiOperationController.Spec {
            override val title = "WiFi 开关"
            override val runningText = if (enabled) "正在开启 WiFi 热点" else "正在关闭 WiFi 热点"
            override val successText = if (enabled) "WiFi 热点已开启" else "WiFi 热点已关闭"

            override suspend fun submit(): String? {
                val resp = try { api.setWifiEnabled(mapOf("enabled" to enabled)) }
                    catch (e: Exception) { return e.message ?: "WiFi开关失败" }
                return if (resp.success) null else "WiFi开关失败"
            }

            override suspend fun verify(): Boolean {
                // 回读判定：设备/已确认 enabled 与目标一致
                val wifi = api.getWifiSettings()
                _networkState.update { it.copy(wifiSettings = wifi, wifiEnabled = wifi.enabled) }
                return wifi.enabled == enabled
            }

            override fun onApplied() {
                // 写后回读：开关状态刚变，必须绕过新鲜度闸门（原路径行为保留）
                refreshWifi(force = true)
            }
        })
    }

    // ── 设备设置类写操作的统一收尾（2026-09-22）────────────────────────────────
    //
    // 这 7 项（FOTA / 性能模式 / 指示灯 / 漫游 / WiFi 休眠 / Samba / 定时重启）原来是
    // **完全静默**的：成功没提示，失败把文案写进 `_deviceSettingsState.errorMessage` ——
    // 而那个字段全仓零消费，等于没写。现在成功走 ShowWriteNotice，失败走已接入全局
    // 错误通道的 errorMessage（见 MainViewModel.rawGlobalError）。
    //
    // 失败**也要回读**：UI 上的开关已经被用户拨过去了，而 `settings` 没变 →
    // `LaunchedEffect(settings)` 不会重新同步，开关就停在错误位置
    //（NetworkFeaturesScreen / DeviceControlScreen 都是这个写法）。回读让 settings 变化，
    // 开关自己弹回设备真值。

    /** 写成功：报出**具体哪一项**生效了，再等设备落地后回读真实回显。 */
    private suspend fun deviceSettingSucceeded(message: String) {
        emitWriteNotice(message)
        // 500ms 是给设备应用配置的时间：立刻回读大概率拿到旧值，而这一轮之后不会再有第二次回读
        delay(500)
        loadDeviceSettings()
    }

    /** 写失败：文案交给全局错误通道，并立刻回读把界面拉回设备真值（失败时不必等 500ms）。 */
    private suspend fun deviceSettingFailed(message: String) {
        _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = message)
        loadDeviceSettings()
    }

    /**
     * 设置 FOTA。入参 disable 沿用 UI 语义（true = 禁用运营商自动升级），
     * 发给 core 时翻转成契约的正向字段 auto_update（旧反向字段 enabled core 仍兼容但已打 WARN）。
     *
     * 写成功后重载 settings 拿真实回显 —— core 已把设备侧 UpgMode 登记为读侧字段
     * `DeviceFields.DeviceSettings.FOTA_AUTO_UPDATE`，并在写成功后失效 device:settings 缓存。
     */
    fun setFotaDisabled(disable: Boolean) {
        scope.launch {
            try {
                val resp = api.setFotaDisabled(mapOf("auto_update" to !disable))
                if (resp.success) {
                    deviceSettingSucceeded(
                        if (disable) "已关闭运营商自动升级" else "已开启运营商自动升级"
                    )
                } else {
                    deviceSettingFailed("FOTA设置失败")
                }
            } catch (e: Exception) { deviceSettingFailed("FOTA设置失败: ${e.message}") }
        }
    }

    fun setPerformanceMode(mode: String) {
        scope.launch {
            try {
                val resp = api.setPerformanceMode(mapOf("mode" to mode))
                if (resp.success) deviceSettingSucceeded("性能模式已切换为 $mode")
                else deviceSettingFailed("性能模式设置失败")
            } catch (e: Exception) { deviceSettingFailed("性能模式设置失败: ${e.message}") }
        }
    }

    fun setLedEnabled(enabled: Boolean) {
        scope.launch {
            try {
                val resp = api.setLedEnabled(mapOf("enabled" to enabled))
                if (resp.success) deviceSettingSucceeded(if (enabled) "指示灯已开启" else "指示灯已关闭")
                else deviceSettingFailed("指示灯设置失败")
            } catch (e: Exception) { deviceSettingFailed("指示灯设置失败: ${e.message}") }
        }
    }

    fun setRoamingEnabled(enabled: Boolean) {
        scope.launch {
            try {
                val resp = api.setRoamingEnabled(mapOf("enabled" to enabled))
                if (resp.success) deviceSettingSucceeded(if (enabled) "数据漫游已开启" else "数据漫游已关闭")
                else deviceSettingFailed("漫游设置失败")
            } catch (e: Exception) { deviceSettingFailed("漫游设置失败: ${e.message}") }
        }
    }

    fun setWifiSleep(time: String) {
        scope.launch {
            try {
                val resp = api.setWifiSleep(mapOf("time" to time))
                // 不在文案里写单位：设备侧 time 的取值语义由固件定义，猜错单位比不写更糟
                if (resp.success) {
                    deviceSettingSucceeded(
                        if (time == "0") "已关闭 WiFi 休眠" else "WiFi 休眠时间已更新"
                    )
                } else {
                    deviceSettingFailed("WiFi休眠设置失败")
                }
            } catch (e: Exception) { deviceSettingFailed("WiFi休眠设置失败: ${e.message}") }
        }
    }

    fun setSambaSetting(enabled: Boolean) {
        scope.launch {
            try {
                val resp = api.setSambaSetting(mapOf("enabled" to enabled))
                if (resp.success) deviceSettingSucceeded(if (enabled) "Samba 已开启" else "Samba 已关闭")
                else deviceSettingFailed("Samba设置失败")
            } catch (e: Exception) { deviceSettingFailed("Samba设置失败: ${e.message}") }
        }
    }

    fun setRestartSchedule(enabled: Boolean, time: String) {
        scope.launch {
            try {
                val resp = api.setRestartSchedule(mapOf("enabled" to enabled, "time" to time))
                if (resp.success) {
                    deviceSettingSucceeded(
                        if (enabled) "定时重启已设为 $time" else "定时重启已关闭"
                    )
                } else {
                    deviceSettingFailed("定时重启设置失败")
                }
            } catch (e: Exception) { deviceSettingFailed("定时重启设置失败: ${e.message}") }
        }
    }

    // ── 轮询等待网络就绪，替代 hardcoded delay ──
    /** 轮询网络状态，等待蜂窝数据连接恢复，替代不可靠的固定 delay */
    private suspend fun waitForNetworkReady(maxWaitMs: Long = 5000, expectConnected: Boolean = true) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val status = api.getNetworkStatus()
                // 等的是**目标状态**：开数据等连上，关数据等断开。
                // 原实现只等"已连上"，关数据时条件永远不成立 → 每次关都白等满 maxWaitMs。
                if (status.isCellularConnected == expectConnected) return
            } catch (_: Exception) {}
            delay(500)
        }
    }

    fun clearError() { _networkState.value = _networkState.value.copy(errorMessage = null) }
    fun setError(message: String?) { _networkState.value = _networkState.value.copy(errorMessage = message) }

    /**
     * 清空「服务控制」（启停 / 重启服务、关机、重启设备）的错误。
     *
     * 2026-09-05 新增：这份 errorMessage 一直在写（`toggleService` / `restartService` 等四处），
     * 但全仓没有任何 UI 读它 —— 设置页那几个危险操作失败时界面**完全没有反馈**。
     * 现在由全局错误浮层统一展示（见 `MainViewModel.globalError`），浮层收起时回调本方法；
     * 不清的话错误会一直挂在 state 上，下次一进设置页又弹一次陈旧错误。
     */
    fun clearServiceError() { _serviceState.value = _serviceState.value.copy(errorMessage = null) }

    // ── Smart Refresh (data_changed 精准增量刷新) ──
    fun smartRefresh(changedType: String) {
        // 2026-10-05 P5 配套：P1 的 invalidateAll 把单次写操作压成一条 "k1,k2,k3" 合并串，
        // split(',') 对单元素恒等（旧广播兼容），此处递归拆开逐 key 分发。
        if (changedType.contains(',')) {
            changedType.split(',').forEach { smartRefresh(it.trim()) }
            return
        }
        when {
            // 2026-10-05 P5（修 G1）：WS 重连 sentinel —— 断线窗口变更不可知，
            // 按各自新鲜度策略 silent 补拉一轮（断线短则被闸门挡住，长则等效全量）。
            changedType == WsDataTopic.RECONNECTED -> {
                refreshNetwork(silent = true)
                loadBandStatus(silent = true)
                loadDeviceSettings()
                refreshWifi()
                // 2026-10-21：断线窗口里的 `pairing:approvals` 推送已经丢了（replay=0），
                // 重连必须补拉一次 —— 否则「WS 刚断过」这个场景又要退回到 30s 轮询。
                loadApprovals()
            }
            changedType == "network:band-status" || changedType == "network:cell-info" -> refreshNetworkLight()
            // 2026-10-05 P5 新增：hub:network-type-info（G2 修复后 core 开始广播此 key）
            changedType == "hub:network-type-info" -> refreshNetworkLight()
            changedType.startsWith("wifi:") -> refreshWifiOnly()
            changedType == "device:lan" -> loadLanSettings()
            changedType == "device:settings" -> loadDeviceSettings()
            // 2026-10-21：core 的批准推送（`ApprovalPushNudger`）→ 立刻拉一次待批准列表。
            // 正常情况下这条 key 在 MainViewModel.dispatchChanged 里已经短路（不吃 800ms 去抖），
            // 走到这里说明有人改过那条短路 —— 兜底成"最多慢 800ms"，别静默丢掉。
            changedType == WsDataTopic.PAIRING_APPROVALS ||
                changedType.startsWith(WsDataTopic.PREFIX_PAIRING) -> loadApprovalsFromPush()
            // 2026-10-05 P5：device:goform / device:qos 只有设备信息页/高级页消费——
            // 不在此主动拉（页面不在前台拉了白拉），靠 P6 的外部变更豁免在切页时放行重拉。
            // summary 由 DashboardModule 消费，此处忽略。
            else -> { /* 未知 key 静默忽略（与原 when 无匹配时一致） */ }
        }
    }

    /**
     * 只拉信号（`GET /api/device/signal`）并写入 [NetworkState.signalInfo]。
     *
     * 2026-10-10 为网络制式弹窗新增：弹窗顶部要显示 PCI / 频点 / SINR / RSRQ，
     * 打开时拉一次即可。用 [refreshNetwork] 会连带请求 WiFi 设置与客户端列表 ——
     * 为看 4 个信号读数付 4 个请求不划算，且这四项与 WiFi 无关。
     *
     * 失败**不写** [NetworkState.errorMessage]：信号读不到时弹窗显示 `--` 即可，
     * 不该为一个辅助读数把整页的错误条点亮（真正的失败仍由 refreshNetwork 报）。
     */
    fun refreshSignalOnly() {
        scope.launch {
            runCatching { api.getSignalInfo() }
                .onSuccess { sig -> _networkState.update { it.copy(signalInfo = sig) } }
        }
    }

    /** 轻量刷新：仅信号+网络状态+频段状态（网络模式/频段锁定变更时触发） */
    private fun refreshNetworkLight() {
        scope.launch {
            try {
                val sig = async { runCatching { api.getSignalInfo() } }
                val net = async { runCatching { api.getNetworkStatus() } }
                val band = async { runCatching { api.getBandStatus() } }
                val rSig = sig.await(); val rNet = net.await(); val rBand = band.await()

                val failures = listOfNotNull(
                    "信号" to rSig, "网络" to rNet, "频段" to rBand
                ).filter { it.second.isFailure }.joinToString("; ") { (n, r) ->
                    "$n: ${r.exceptionOrNull()?.message ?: "未知"}"
                }.ifEmpty { null }

                _networkState.update { it.copy(
                    signalInfo = rSig.getOrNull() ?: it.signalInfo,
                    networkStatus = rNet.getOrNull() ?: it.networkStatus,
                    bandStatus = rBand.getOrNull() ?: it.bandStatus,
                    errorMessage = failures ?: it.errorMessage
                )}
            } catch (e: Exception) {
                _networkState.update { it.copy(errorMessage = "刷新失败: ${e.message}") }
            }
        }
    }

    /** 精准刷新 WiFi 设置+客户端列表 */
    private fun refreshWifiOnly() {
        scope.launch {
            try {
                val wifi = async { runCatching { api.getWifiSettings() } }
                val clients = async { runCatching { api.getWifiClients() } }
                val rWifi = wifi.await(); val rClients = clients.await()

                val failures = listOfNotNull(
                    "WiFi" to rWifi, "客户端" to rClients
                ).filter { it.second.isFailure }.joinToString("; ") { (n, r) ->
                    "$n: ${r.exceptionOrNull()?.message ?: "未知"}"
                }.ifEmpty { null }

                val wifiSettings = rWifi.getOrNull()
                _networkState.update { it.copy(
                    wifiSettings = wifiSettings ?: it.wifiSettings,
                    wifiClients = rClients.getOrNull() ?: it.wifiClients,
                    wifiEnabled = wifiSettings?.enabled ?: it.wifiEnabled,
                    errorMessage = failures ?: it.errorMessage
                )}
            } catch (e: Exception) {
                _networkState.update { it.copy(errorMessage = "WiFi刷新失败: ${e.message}") }
            }
        }
    }

    // ── Pairing ──

    fun loadPairingStatus() {
        scope.launch {
            _pairingState.value = _pairingState.value.copy(isLoading = true, errorMessage = null)
            try {
                val json = api.getPairingStatus().jsonObject
                _pairingState.value = PairingState(
                    paired = json["paired"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    deviceId = json["device_id"]?.jsonPrimitive?.content ?: "",
                    deviceName = json.safeGetString("device_name") ?: "",
                    hasDefaultPassword = json["has_default_password"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                    pairedFingerprints = json["paired_fingerprints"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    pairedCount = json["paired_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    pairedAt = json["paired_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                    pairingCode = json["pairing_code"]?.jsonPrimitive?.content ?: "",
                    pairingEnabled = json["pairing_enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    pairingMaxDevices = json["pairing_max_devices"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    // 2026-10-15（安全中心）：批准策略 + 外部通知渠道。旧 core 不返回这些字段 →
                    // 一律回落 false（= 批准不生效、不通知），与"旧 core 没有这些能力"一致。
                    approvalRequired = json["pairing_approval_required"]?.jsonPrimitive?.content
                        ?.toBooleanStrictOrNull() ?: false,
                    notifySystem = json["pairing_notify_system"]?.jsonPrimitive?.content
                        ?.toBooleanStrictOrNull() ?: false,
                    notifyEmail = json["pairing_notify_email"]?.jsonPrimitive?.content
                        ?.toBooleanStrictOrNull() ?: false,
                    notifyWebhook = json["pairing_notify_webhook"]?.jsonPrimitive?.content
                        ?.toBooleanStrictOrNull() ?: false,
                    devices = parsePairedDevices(json["devices"]),
                    isLoading = false
                )
            } catch (e: Exception) {
                DebugLog.e("Network", "loadPairingStatus failed", e)
                _pairingState.value = _pairingState.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}")
            }
        }
    }

    // 2026-09-22（阶段 3.5）：下面这组写操作补成功提示。
    // 失败路径不动 —— 它们写 `pairingState.errorMessage`，`PairingConfigScreen` 已经在渲染
    // （UfiErrorBanner），不是"写了没人读"那一类，不需要改道全局通道。

    /**
     * 保存配对策略（`PUT /api/pairing/config`）。
     *
     * 2026-10-15 扩展：多出批准策略与三个外部通知开关（安全中心）。
     * 四个新参数都带默认值 → 既有调用点（只传 enabled/maxDevices）行为不变；
     * 传 null 表示"这次不动这一项"，避免把没在编辑的开关一并覆盖掉。
     */
    fun updatePairingConfig(
        enabled: Boolean,
        maxDevices: Int,
        approvalRequired: Boolean? = null,
        notifySystem: Boolean? = null,
        notifyEmail: Boolean? = null,
        notifyWebhook: Boolean? = null
    ) {
        scope.launch {
            try {
                val body = mutableMapOf<String, Any>(
                    "pairing_enabled" to enabled,
                    "pairing_max_devices" to maxDevices
                )
                approvalRequired?.let { body["pairing_approval_required"] = it }
                notifySystem?.let { body["pairing_notify_system"] = it }
                notifyEmail?.let { body["pairing_notify_email"] = it }
                notifyWebhook?.let { body["pairing_notify_webhook"] = it }
                api.updatePairingConfig(body)
                loadPairingStatus()
                // 2026-10-24：关闭批准策略时 core 会清空待批准请求并广播 pairing:approvals；
                // 这里再主动拉一次，覆盖"WS 断线/推送丢失"的情况 —— 否则根部的批准弹窗
                // 会一直挂着一条已经被取消的请求。
                loadApprovals()
                emitWriteNotice(
                    if (enabled) "已允许新设备配对" else "已停止接受新设备配对",
                    subtitle = if (enabled) "上限 $maxDevices 台" else null
                )
            } catch (e: Exception) {
                DebugLog.e("Network", "updatePairingConfig failed", e)
                _pairingState.value = _pairingState.value.copy(errorMessage = "保存失败: ${e.message}")
            }
        }
    }

    /**
     * 解除全部设备的配对（POST api/pairing/unpair）。
     *
     * 2026-10-21（审计 §B1）：**必须带配对密码**。之前这条免密码、而"移除单台"要密码，
     * 于是破坏性最强的操作反而是门槛最低的一条；core 已同步收紧，这里跟上传参。
     * 成功后自己的 token 也一起失效 —— 立刻回配对引导，别等下一次请求撞 444。
     */
    fun unpairAll(password: String, onResult: (String?) -> Unit = {}) {
        scope.launch {
            try {
                api.unpairAll(mapOf("password" to password))
                emitWriteNotice("已解除全部设备的配对", subtitle = "所有客户端需要重新配对")
                loadPairingStatus()
                onResult(null)
            } catch (e: Exception) {
                DebugLog.e("Network", "unpairAll failed", e)
                val msg = unpairErrorMessage(e, "解除配对失败")
                _pairingState.value = _pairingState.value.copy(errorMessage = msg)
                onResult(msg)
            }
        }
    }

    fun unpairFingerprint(fingerprint: String, password: String, onResult: (String?) -> Unit = {}) {
        scope.launch {
            try {
                api.unpairFingerprint(fingerprint, mapOf("password" to password))
                loadPairingStatus()
                emitWriteNotice("已解除该设备的配对")
                onResult(null)
            } catch (e: Exception) {
                DebugLog.e("Network", "unpairFingerprint failed", e)
                val msg = unpairErrorMessage(e, "移除设备失败")
                _pairingState.value = _pairingState.value.copy(errorMessage = msg)
                onResult(msg)
            }
        }
    }

    /** 解除配对类接口的错误文案（core 回 `{error, code}`，按 code 给可执行的话）。 */
    private fun unpairErrorMessage(e: Exception, fallback: String): String {
        val code = (e as? retrofit2.HttpException)
            ?.let { runCatching { org.json.JSONObject(it.response()?.errorBody()?.string().orEmpty()).optString("code") }.getOrNull() }
        return when (code) {
            "MISSING_PASSWORD" -> "请输入配对密码"
            "INVALID_PASSWORD" -> "配对密码错误"
            "PASSWORD_LOCKED" -> "密码错误次数过多，请 15 分钟后再试"
            else -> "$fallback: ${e.message}"
        }
    }

    // ── 配对设备管理（阶段3：2026-08-11，配对密码认证） ──

    /** 加载已配对设备列表（GET /api/pairing/devices）。 */
    fun loadPairedDevices() {
        scope.launch {
            try {
                val json = api.getPairedDevices().jsonObject
                _pairingState.update { it.copy(devices = parsePairedDevices(json["devices"]), errorMessage = null) }
            } catch (e: Exception) {
                DebugLog.e("Network", "loadPairedDevices failed", e)
                _pairingState.update { it.copy(errorMessage = "加载设备列表失败: ${e.message}") }
            }
        }
    }

    /** 重命名已配对设备（PATCH /api/pairing/devices/{fp}）。 */
    fun renameDevice(fingerprint: String, name: String) {
        scope.launch {
            try {
                api.renamePairedDevice(fingerprint, mapOf("device_name" to name))
                loadPairedDevices()
                emitWriteNotice("设备名称已改为「$name」")
            } catch (e: Exception) {
                DebugLog.e("Network", "renameDevice failed", e)
                _pairingState.update { it.copy(errorMessage = "重命名失败: ${e.message}") }
            }
        }
    }

    /** 移除已配对设备（DELETE /api/pairing/devices/{fp}，需配对密码）。
     * 最后一台移除会轮换 token：旧 token 失效 → 下一次请求 401 由 RetrofitClient 引导重新配对（预期行为）。 */
    fun removeDevice(fingerprint: String, password: String) {
        scope.launch {
            try {
                api.removePairedDevice(fingerprint, mapOf("password" to password))
                // 删除的是自己当前连接的设备 → 立即退出配对（回 SetupScreen）
                // core 侧删除会连带删掉该设备的 token 记录，本机后续请求必然 401；
                // 这里复用 ConnectionBootstrap 注册的 onUnauthorized 全局回调提前收尾：
                // 清空本地 token + isSetupComplete=false → MainActivity 显示 SetupScreen。
                if (fingerprint == AppPreferences(appContext).appFingerprint) {
                    RetrofitClient.onUnauthorized?.invoke()
                } else {
                    loadPairingStatus()
                    // 移除的是本机时**不发**成功提示：那一支会立刻退回配对页，
                    // 一条"已移除"的绿色 Toast 盖在配对引导上只会让人以为哪里出错了。
                    emitWriteNotice("已移除该设备")
                }
            } catch (e: Exception) {
                DebugLog.e("Network", "removeDevice failed", e)
                _pairingState.update { it.copy(errorMessage = "移除设备失败: ${e.message}") }
            }
        }
    }

    // ── 配对设备黑名单（2026-10-15） ──
    //
    // 读侧拦截不在这里：被拉黑设备的配对/密码登录由 core 的 PairingManager.confirm 直接拦
    // （403 DEVICE_BLOCKED），已鉴权请求由 444 拦。这里只是管理面（增删查）。

    /**
     * 加载黑名单（GET /api/pairing/blacklist）。
     *
     * 旧版 core 没有这个端点 → 404。**这不是错误**：把 [PairingState.blacklistAvailable]
     * 置 false 让 UI 隐藏入口即可 —— "新 App + 旧 core"是必须支持的组合，
     * 不能因为设备端版本旧就弹一个用户看不懂的报错。
     */
    fun loadBlacklist() {
        scope.launch {
            try {
                val json = api.getPairingBlacklist().jsonObject
                _pairingState.update {
                    it.copy(
                        blockedDevices = parseBlockedDevices(json["devices"]),
                        blacklistAvailable = json["available"]?.jsonPrimitive?.booleanOrNull ?: true,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                if (e.isEndpointMissing()) {
                    DebugLog.w("Network", "pairing blacklist not supported by this core (404) → 隐藏入口")
                    _pairingState.update { it.copy(blacklistAvailable = false, blockedDevices = emptyList()) }
                } else {
                    DebugLog.e("Network", "loadBlacklist failed", e)
                    _pairingState.update { it.copy(errorMessage = "加载黑名单失败: ${e.message}") }
                }
            }
        }
    }

    /**
     * 拉黑一台设备（POST /api/pairing/blacklist，需配对密码）。
     *
     * @param hwId 已配对记录里的硬件标识（辅助键，可空）。一起记下来才能挡住
     *   "清 App 数据换密钥后重新配对"这条路 —— 那是纯指纹拦截唯一的绕过口。
     */
    fun blockDevice(
        fingerprint: String,
        name: String,
        hwId: String = "",
        password: String,
        reason: String = ""
    ) {
        scope.launch {
            try {
                val body = mutableMapOf<String, Any>(
                    "fingerprint" to fingerprint,
                    "name" to name,
                    "reason" to reason,
                    "password" to password
                )
                if (hwId.isNotBlank()) body["hw_id"] = hwId
                api.blockPairedDevice(body)
                loadBlacklist()
                emitWriteNotice("已拉黑「${name.ifBlank { fingerprint.take(8) }}」")
            } catch (e: Exception) {
                DebugLog.e("Network", "blockDevice failed", e)
                _pairingState.update { it.copy(errorMessage = "拉黑失败: ${e.message}") }
            }
        }
    }

    /** 解除拉黑（DELETE /api/pairing/blacklist/{fp}，需配对密码）。 */
    fun unblockDevice(fingerprint: String, password: String) {
        scope.launch {
            try {
                api.unblockPairedDevice(fingerprint, mapOf("password" to password))
                loadBlacklist()
                emitWriteNotice("已解除拉黑")
            } catch (e: Exception) {
                DebugLog.e("Network", "unblockDevice failed", e)
                _pairingState.update { it.copy(errorMessage = "解除拉黑失败: ${e.message}") }
            }
        }
    }

    /** 全部解除（POST /api/pairing/blacklist/clear，需配对密码）。 */
    fun clearBlacklist(password: String) {
        scope.launch {
            try {
                api.clearPairingBlacklist(mapOf("password" to password))
                loadBlacklist()
                emitWriteNotice("已全部解除拉黑")
            } catch (e: Exception) {
                DebugLog.e("Network", "clearBlacklist failed", e)
                _pairingState.update { it.copy(errorMessage = "全部解除失败: ${e.message}") }
            }
        }
    }

    // ── 新设备批准（2026-10-15） ──

    /**
     * 待批准列表的「在途 / 最近一次」标记（2026-10-21）。
     *
     * core 的 `ApprovalPushNudger` 一轮会**快速补发 4 帧**唤醒帧；App 若每帧都发一个 GET，
     * 就是自己在打自己。这里只做「在途合并 + 1 秒内不重复」这一层 —— 窗口再放大，
     * 弹窗就又要多等，那就把提速的意义吃掉了。
     *
     * 只有**推送触发**的拉取走这两道闸（[loadApprovalsFromPush]）；页面进入 / 机主点允许后的
     * 主动刷新走 [loadApprovals]，不受限制（否则「点允许后立刻刷新」会被自己刚刚那一次挡住）。
     */
    @Volatile private var approvalsInFlight = false
    @Volatile private var lastApprovalsAt = 0L

    /**
     * 加载待批准列表（GET /api/pairing/approvals）。
     *
     * 与黑名单同款降级：旧 core 没有这个端点 → 404 → 标记不可用、隐藏入口（不弹错误）。
     */
    fun loadApprovals() {
        scope.launch {
            approvalsInFlight = true
            try {
                val json = api.getPairingApprovals().jsonObject
                _pairingState.update {
                    it.copy(
                        pendingApprovals = parsePendingApprovals(json["approvals"]),
                        approvalAvailable = true,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                if (e.isEndpointMissing()) {
                    DebugLog.w("Network", "pairing approvals not supported by this core (404) → 隐藏入口")
                    _pairingState.update { it.copy(approvalAvailable = false, pendingApprovals = emptyList()) }
                } else {
                    DebugLog.e("Network", "loadApprovals failed", e)
                }
            } finally {
                approvalsInFlight = false
            }
        }
    }

    /**
     * 推送触发的拉取（2026-10-21）：在途合并 + 1 秒内不重复。
     *
     * 与 [loadApprovals] 分开而不是在它里面加闸的理由见 [approvalsInFlight] 的注释 ——
     * 「机主点允许后立刻刷新」必须无条件生效。
     */
    fun loadApprovalsFromPush() {
        if (approvalsInFlight) return
        val now = System.currentTimeMillis()
        if (now - lastApprovalsAt < 1_000L) return
        lastApprovalsAt = now
        loadApprovals()
    }

    /**
     * 机主点「允许」。
     *
     * 批准只对**这一条请求**生效；请求方随后带着同一个 `sid` 重试 confirm 才会通过。
     *
     * 2026-10-21（审计 §B2）：带结果回调。之前失败只写 `pairingState.errorMessage`，
     * 而那份状态**只有「设置 → 安全中心」页在渲染**；根部的批准弹窗点完就消失，
     * 机主以为批了、其实 404（请求已过期）或网络失败 —— 必须把结果交回调用方去提示。
     *
     * @param onResult 参数为错误文案；成功传 null。
     */
    fun approveDevice(sid: String, onResult: (String?) -> Unit = {}) {
        scope.launch {
            try {
                api.approvePairingRequest(sid)
                loadApprovals()
                emitWriteNotice("已允许该设备配对")
                onResult(null)
            } catch (e: Exception) {
                DebugLog.e("Network", "approveDevice failed", e)
                val msg = approvalErrorMessage(e, "批准失败")
                _pairingState.update { it.copy(errorMessage = msg) }
                onResult(msg)
            }
        }
    }

    /**
     * 机主点「拒绝」。拒绝后该 sid 不能再被批准（服务端把 rejected 视为终态）。
     *
     * @param onResult 参数为错误文案；成功传 null（见 [approveDevice] 的说明）。
     */
    fun rejectDevice(sid: String, onResult: (String?) -> Unit = {}) {
        scope.launch {
            try {
                api.rejectPairingRequest(sid)
                loadApprovals()
                emitWriteNotice("已拒绝该设备")
                onResult(null)
            } catch (e: Exception) {
                DebugLog.e("Network", "rejectDevice failed", e)
                val msg = approvalErrorMessage(e, "拒绝失败")
                _pairingState.update { it.copy(errorMessage = msg) }
                onResult(msg)
            }
        }
    }

    /** 批准/拒绝类接口的错误文案：404 表示这条请求已经不用处理了（过期/已被处理）。 */
    private fun approvalErrorMessage(e: Exception, fallback: String): String {
        val code = (e as? retrofit2.HttpException)?.code()
        return when (code) {
            404 -> "$fallback：这条请求已过期或已被处理"
            444 -> "$fallback：本机凭据已失效，请重新配对"
            else -> "$fallback: ${e.message}"
        }
    }

    /** 修改配对密码（POST pairing/change-password，root 路径；旧密码即门禁）。 */
    fun changeDevicePassword(oldPw: String, newPw: String) {
        scope.launch {
            try {
                api.changeDevicePassword(mapOf("old_password" to oldPw, "new_password" to newPw))
                loadPairingStatus()
                emitWriteNotice("配对密码已修改")
            } catch (e: Exception) {
                DebugLog.e("Network", "changeDevicePassword failed", e)
                _pairingState.update { it.copy(errorMessage = "修改配对密码失败: ${e.message}") }
            }
        }
    }
}

// ── 统一解析（ViewModel + UI 共用） ──
//
// 阶段 4.3：WiFi 设置 / 客户端 / 频段锁定 / 小区信息 / LAN / 设备设置这六个端点的响应
// 已经是强类型 data class（见 app/data 的 DeviceResponses.kt），原来这里的
// parseWifiSettings / parseWifiClients / parseBandStatus / parseCurrentBand /
// parseDeviceSettingsField 全部删除 —— 键名只在 DeviceResponses.kt 出现一次，
// UI 直接读属性。剩下的解析函数是**没有稳定字段契约**的那些（配对设备列表等）。

/**
 * 解析已配对设备列表 JSON（/api/pairing/status 的 devices 或 /api/pairing/devices）。
 * 字段与 Core PairedDeviceStore 对齐：fingerprint / device_name / last_seen / created_at（epoch millis）。
 */
fun parsePairedDevices(el: JsonElement?): List<PairedDeviceItem> {
    if (el !is JsonArray) return emptyList()
    return el.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        PairedDeviceItem(
            fingerprint = obj.safeGetString("fingerprint") ?: "",
            deviceName = obj.safeGetString("device_name") ?: "",
            lastSeen = obj.safeGetString("last_seen")?.toLongOrNull() ?: 0L,
            createdAt = obj.safeGetString("created_at")?.toLongOrNull() ?: 0L,
            // 2026-10-15 新增：拉黑时一并带上（辅助键）。旧 core 不返回该字段 → 空串，
            // 那时只按指纹拦（弱一档，但功能仍成立）。
            hwId = obj.safeGetString("hw_id").orEmpty()
        )
    }
}

/**
 * 解析配对黑名单 JSON（`GET /api/pairing/blacklist` 的 `devices`）。
 *
 * 字段与 core 的 `BlockedDevice` 对齐：fingerprint / hw_id / name / reason / blocked_at / blocked_by。
 * 没有 fingerprint 的条目直接丢弃（它是主键，空值的条目无法展示也无法解除）。
 */
fun parseBlockedDevices(el: JsonElement?): List<BlockedDeviceItem> {
    if (el !is JsonArray) return emptyList()
    return el.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val fp = obj.safeGetString("fingerprint")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        BlockedDeviceItem(
            fingerprint = fp,
            hwId = obj.safeGetString("hw_id").orEmpty(),
            name = obj.safeGetString("name").orEmpty(),
            reason = obj.safeGetString("reason").orEmpty(),
            blockedAt = obj.safeGetString("blocked_at")?.toLongOrNull() ?: 0L,
            blockedBy = obj.safeGetString("blocked_by").orEmpty()
        )
    }
}

/**
 * 解析待批准列表（`GET /api/pairing/approvals` 的 `approvals`，2026-10-15）。
 *
 * `code` 是弹窗要大字显示的 6 位配对码 —— 它只在这个**已鉴权**的接口里出现，
 * 请求方自己的 `request-approval` 响应里永远没有它。
 *
 * 2026-10-17：**已批准/已拒绝的条目直接丢掉**。服务端已经过滤过一次，这里再滤一遍是防御性的 ——
 * 之前弹窗"点了允许关不掉、反复弹"就是因为已批准的条目还留在列表里。
 */
fun parsePendingApprovals(el: JsonElement?): List<PendingApprovalItem> {
    if (el !is JsonArray) return emptyList()
    return el.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val sid = obj.safeGetString("sid")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // 已处理过的不再算"待批准"
        val approved = obj.safeGetString("approved")?.toBooleanStrictOrNull() ?: false
        val rejected = obj.safeGetString("rejected")?.toBooleanStrictOrNull() ?: false
        if (approved || rejected) return@mapNotNull null
        PendingApprovalItem(
            sid = sid,
            code = obj.safeGetString("code").orEmpty(),
            deviceName = obj.safeGetString("device_name").orEmpty(),
            source = obj.safeGetString("source").orEmpty(),
            requestedAt = obj.safeGetString("requested_at")?.toLongOrNull() ?: 0L,
            expiresAt = obj.safeGetString("expires_at")?.toLongOrNull() ?: 0L
        )
    }
}

/** 404 = 设备端没有这个端点（旧版 core）—— 属于"功能不可用"，不是用户做错了什么。 */
private fun Exception.isEndpointMissing(): Boolean =
    this is retrofit2.HttpException && code() == 404

// ── 重启后端服务的恢复探测参数（2026-09-14，见 NetworkModule.restartBackendService）──
//
// core 侧有两路拉起：shell 约 4s、闹钟约 10s（`BackendService.SHELL_RESTART_DELAY_SEC` /
// `RESTART_DELAY_MS`），备份闹钟在 30s。所以探测窗口必须覆盖到备份闹钟之后 ——
// 45s 是「等得住最慢那一路」与「别让用户对着转圈干等」之间的折中。

/** 先等这么久再开始探：太早会探到还没停下来的旧实例，误判成"根本没重启"。 */
private const val RESTART_PROBE_INITIAL_DELAY_MS = 5_000L

/** 探测间隔。 */
private const val RESTART_PROBE_INTERVAL_MS = 2_000L

/** 总超时。超时不等于失败 —— core 侧仍在重试，所以文案是"仍未恢复"。 */
private const val RESTART_PROBE_TIMEOUT_MS = 45_000L

// ── 安全 JSON 取值辅助（kotlinx.serialization.json API） ──

/** 安全地从 JsonObject 取字符串字段 */
private fun JsonObject.safeGetString(key: String): String? {
    return try {
        val el = this[key] ?: return null
        if (el is JsonPrimitive) el.content else null
    } catch (e: Exception) { DebugLog.w("Network", "safeGetString failed for key=$key", e); null }
}
