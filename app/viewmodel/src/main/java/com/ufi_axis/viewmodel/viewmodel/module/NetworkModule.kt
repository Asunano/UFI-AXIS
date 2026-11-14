// TODO: NetworkModule 当前职责过多（DownloadManager / GoformClient / Aria2Engine / BackendService / NetworkModule / FileManagerScreen）。本类仅做最小安全抽取（见 GoformCodec），全量拆分需人工评审与编译验证。
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
import com.ufi_axis.data.model.DeviceSettingsResponse
import com.ufi_axis.data.model.ModeRequest
import com.ufi_axis.data.model.NetworkStatusResponse
import com.ufi_axis.data.model.SignalInfo
import com.ufi_axis.data.model.WebSocketMessage
import com.ufi_axis.data.model.WifiAclResponse
// 制式别名 ↔ 设备 BearerPreference 的换算与「切换中」回读预算都在 contract，两端同源
import com.ufi_axis_core.contract.NetworkMode
// smartRefresh 消费 RECONNECTED sentinel（contract 唯一取值表）
import com.ufi_axis_core.contract.WsChannel
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

// ── 智能时长：按「速率是否稳定」自动收尾，不要求用户选择秒数 ──
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

// ── 测量方法论（参考 LibreSpeed 的做法，未引入其代码）──
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
/** 探针连续失败这么多次就放弃：站点不可达时不得将剩余超时全部执行 */
private const val SPEED_PING_MAX_FAILURES = 3
/** 整个探针阶段的总预算：任何单次探针卡住都不能把测速拖死 */
private const val SPEED_PING_TOTAL_BUDGET_MS = 6_000L
/** 探针响应体最多允许读掉这么多字节（只为让连接可复用），超过就直接关闭 */
private const val PROBE_DRAIN_MAX_BYTES = 64L

// ── Web 面板资源上传 / 轮询 ──
/** 面板 ZIP 上传上限，与 core 的 `WebUpdateRoutes.MAX_ZIP_UPLOAD` 一致（50MB）。 */
private const val WEB_ZIP_MAX_BYTES = 50L * 1024 * 1024
/** 更新状态轮询间隔。本组端点没有 WS 推送，只能轮询。 */
private const val WEB_POLL_INTERVAL_MS = 1_500L
/** 轮询次数上限（× 间隔 ≈ 90s）：core 卡在 downloading 时不能让协程一直转下去。 */
private const val WEB_POLL_MAX_TICKS = 60
/** 探针阶段占总进度的比例 */
private const val LATENCY_PROGRESS_SPAN = 0.05f

/**
 * `GET /api/network/cell-info` 的客户端新鲜度窗口，对齐 core 的 `CacheTTL.CELL_INFO = 20s`。
 * 比 30s 短：基站信息比配置类字段变得快，窗口与 core 缓存同长，避免「界面以为新鲜、
 * core 已经要重新查设备」的错位。
 */
private const val CELL_INFO_FRESH_MS = 20_000L
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
 * 修改 WiFi 热点配置**会断开当前连接**的提示文案。
 *
 * 仅保留一份：同一句话用于保存**之前**弹窗中的常驻警告块与保存成功后的 Toast 副标题。
 * 两处各写一份会产生漂移，而文案准确性直接决定用户是否会把「设备掉线」理解为「保存失败」。
 *
 * 必须在**点击之前**提示：用户很可能正通过该热点连接设备，热点重启后，
 * 后续失败提示无法送达其眼前。
 */
const val WIFI_RESTART_DISCONNECT_HINT =
    "保存后热点会重启，当前通过这个 WiFi 连接的设备（包括本机）会短暂断开"

/**
 * 切换 WiFi 频段（`POST /api/wifi/band`）的后果说明。
 *
 * 与 [WIFI_RESTART_DISCONNECT_HINT] 分开而非复用：该动作的后果**多一项** ——
 * 设备命令 `switchWiFiChip&ChipEnum=X&GuestEnable=0` 的语义是「**在该频段上启用 WiFi**」，
 * 除断连外，原本关闭的 WiFi 也会被打开。若混入「保存热点配置」的提示，
 * 会遗漏该项，或使仅修改 SSID 的用户误以为会开启 WiFi。
 *
 * 仅保留一份：用于切换**之前**的确认弹窗正文与切换成功后的 Toast 副标题。
 * 用户很可能正通过该热点连接设备，提示必须在点击前给出。
 */
const val WIFI_BAND_SWITCH_DISCONNECT_HINT =
    "这会重启设备的 WiFi 模块并在该频段上打开 WiFi，当前通过 WiFi 连接的设备（包括本机）会断开，需要重新连接"

class NetworkModule(
    private val api: UfiAxisApi,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val crossModuleEventSink: MutableSharedFlow<UiEvent>,
    /**
     * WS 消息流（`WebSocketRepository.messages`）。
     *
     * 网络页 hero 卡的信号读数必须与仪表盘 hero 卡同源 —— 见 [collectSignalFromWs]。
     * 用 `Flow<WebSocketMessage>` 而不是直接传 repository：本模块只需要这一条流，测试也只需
     * 要一个 `MutableSharedFlow` 就能驱动它。
     */
    private val wsMessages: Flow<WebSocketMessage>
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

    /** 定时测速历史。空列表 = 无记录或未拉取。 */
    private val _speedTestHistory = MutableStateFlow<List<SpeedTestHistoryEntry>>(emptyList())
    val speedTestHistory: StateFlow<List<SpeedTestHistoryEntry>> = _speedTestHistory.asStateFlow()

    private val _pairingState = MutableStateFlow(PairingState())
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    // ── 设备能力集 ───────────────────────────────────────────────
    //
    // 放在本 module 而不是 MainViewModel：10 个功能域中有 8 个的写操作位于本文件
    // （FOTA / Samba / 性能模式 / 制式 / 承载偏好 / 锁频 / 锁站 / USB 调试），
    // 门禁判据与写操作放在一起，避免「能力集在一处、开关在另一处」的双地维护。
    //
    // 初值为 UNKNOWN（= 全部支持），因此在取得能力集之前 UI 行为与引入能力集前一致。
    private val _capabilityState = MutableStateFlow(DeviceCapabilityState.UNKNOWN)
    val capabilityState: StateFlow<DeviceCapabilityState> = _capabilityState.asStateFlow()

    // 能力集请求的在途协程。用来保证同一时刻只有一个请求 ——
    // 拉失败后允许下一次调用重试，没有这个闸门的话四个页面同时进来就会打四发。
    private var capabilityJob: Job? = null

    private var refreshJob: Job? = null

    /**
     * 轻量刷新（信号 + 网络状态 + 频段）的在途协程。
     *
     * 一次写操作会使多个 key 失效（如制式切换会同时发出 `network:band-status`
     * 与 `hub:network-type-info`），`MainViewModel.dispatchChanged` 按 key 逐条分发，
     * 同一批事件会多次进入本模块，每次都发出 3 个请求。此处做**在途合并**：同一时刻只执行一轮。
     * 这三项读取的始终是同一份当前状态，跳过重复轮次不会丢失信息。
     */
    private var lightRefreshJob: Job? = null

    /**
     * 制式切换 +「切换中」回读的协程。
     *
     * 单独持有是为了在**连续切换两个档位**时立即终止前一次回读：不取消的话两个循环会同时
     * 往 `_deviceSettingsState` 写回读结果，界面会在两个目标档位之间跳。
     */
    private var modeSwitchJob: Job? = null

    // ── Cross-module Events ──
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    // 跨模块事件转发：模块内 _events 统一汇聚到 MainViewModel 持有的 sink，
    // 避免 collectCrossModuleEvents 在构建期触发本模块的 by lazy 求值。
    // viewModelScope 随 ViewModel 销毁而取消，本 forward 协程一并结束，无泄漏。
    init {
        scope.launch {
            _events.collect { event -> crossModuleEventSink.tryEmit(event) }
        }
        collectRealtimeFromWs()
    }

    /**
     * WS 实时网络频道 → [NetworkState]。
     *
     * 消费两条：
     * - [WsChannel.SIGNAL]：信号读数（制式 / 频段 / 运营商 / RSRP 等），网络页 hero 卡由此
     *   自动跟随设备状态；
     * - [WsChannel.CONNECTION]：`ppp_status` 链路态。core 在采集 signal 的同一次 goform
     *   查询中已取得该值，零额外设备成本；仪表盘 hero 卡「已连接」与网络页连接态因此
     *   从 10s 轮询降至 3~4.5s 推送。
     *
     * ## SIGNAL 频道必须消费的原因
     *
     * core 的 `DataScheduler.collectSignal()` 每约 3~4.5s 广播一条 `signal`（前端连接期间）。
     * **仪表盘** hero 卡（[DashboardModule]）订阅该频道，因此制式 / 频段 / 运营商 / 信号持续
     * 跟随；而网络页的 `signalInfo` 仅由一次性 REST 刷新写入（`refreshNetwork` /
     * `refreshNetworkLight` / `cellLock.verify`），页面本身没有轮询。
     *
     * 因此「锁频段 / 锁基站 / 切制式」链路上存在一个失效窗口：
     * ① 写操作触发 core 的 `data_changed`（如 `network:band-status`），800ms 后
     *    [smartRefresh] → [refreshNetworkLight] 立即回读；
     * ② 此时设备正在重启网络栈 / 重新驻网，回读到 `rat = "无服务"`、RSRP 为空；
     * ③ 设备十余秒后驻网成功，core 继续推送 `signal`，但本模块未订阅，页面停留在第 ② 步；
     * ④ 用户停留在网络页（未切换 Tab），`pageForeground` 不翻转、`ON_RESUME` 不触发，
     *    30s 新鲜度闸门又刚被第 ② 步的「HTTP 成功」打戳，于是持续显示「无服务」。
     *
     * 仪表盘未出现该问题的原因是它订阅了同一条 `signal` 频道（见
     * `DashboardModule.collectWebSocketMessages` 的 `"signal"` 分支）。本方法补齐该链路，
     * 使网络页与仪表盘读取同一份真值；后续驻网恢复由推送自动纠正，无需额外轮询。
     *
     * ## 边界
     * - `CONNECTION` 仅写 `networkStatus.ppp_status`。**不得**推送 `mobile_data`：
     *   该值表示用户意图（core 的 `MobileDataIntent`），不能由链路态反推。
     * - `networkStatus` 为 null 时（首屏 summary/status 尚未返回）暂不写入：等待 REST 建立，
     *   避免以默认值清空运营商 / 开关。网络页不显示 ppp，不差这一拍。
     * - 解析失败仅记录日志 / 返回 null，不修改状态：单条坏帧不得清除上一帧正确读数。
     * - 与 `refreshNetwork` 的竞态是暂时的（REST 回包可能比推送旧数百毫秒），下一次推送
     *   即覆盖；不为此增加版本号 —— 那会使「谁更新」出现两套判据。
     */
    private fun collectRealtimeFromWs() {
        scope.launch(Dispatchers.Default) {
            wsMessages.collect { message ->
                when (message.type) {
                    WsChannel.SIGNAL -> signalFromWsMessage(message)?.let { signal ->
                        _networkState.update { it.copy(signalInfo = signal) }
                    }
                    WsChannel.CONNECTION -> pppStatusFromWsMessage(message)?.let { ppp ->
                        _networkState.update { current ->
                            current.copy(
                                networkStatus = current.networkStatus?.copy(ppp_status = ppp)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun emitDashboardError(msg: String?) {
        _events.tryEmit(UiEvent.ShowDashboardError(msg))
    }

    /**
     * 写操作成功提示。走跨模块事件 → MainViewModel 的 writeNotice 流 →
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
     * 设备重启热点实测需要 15~20 秒，此期间任何「等待确认再判定成败」的窗口都会落在
     * 重启期内，因此这类断连一律按「已提交」处理，不计为失败。
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

    private fun emitWriteNotice(
        message: String,
        subtitle: String? = null,
        type: WriteNoticeType = WriteNoticeType.SUCCESS,
    ) {
        _events.tryEmit(UiEvent.ShowWriteNotice(message, subtitle, type))
    }

    /** 清设备设置的错误（全局错误浮层收起时由 MainViewModel.dismissGlobalError 分派过来）。 */
    fun clearDeviceSettingsError() {
        _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = null)
    }

    // ── 写操作确认器（「执行 → 等待结果 → 回读确认 → 更新」）──
    // 状态机在 viewmodel 层（供各 Module 复用），UI 侧通过 UfiOperationProgressDialog 消费 state。
    //
    // 失败文案接入既有 toast 通道：用户选择「后台进行」后弹窗关闭，超时那一刻**不得**重新
    // 弹出弹窗（与「不阻塞操作」的目标相反），但也不能静默 —— 唯一出口是 ShowWriteNotice，
    // 与其它写操作成功提示同一出口（Activity 级 Toast）。
    //
    // **不使用 `errorMessage`（全局错误）通道的原因**：① 它带 30s 同文案去重
    // （`MainActivity.kt:80,573`），而「超时不得静默」要求**每次都能提示**；
    // ② 它是 state 字段，写入后会被紧随其后的 refreshNetwork() 以 null 覆盖
    // （失败回读中会调用 refreshNetwork），两个写入同帧会被 StateFlow 合并，导致「偶尔不弹」。
    // ShowWriteNotice 是一次性事件流（带自增 seq），不存在这两个问题。
    // 已知代价：宿主将其渲染为 SUCCESS 样式的 Toast（`MainActivity.kt:595` 固定
    // ToastType.SUCCESS），而 web 同一句使用 warning。文案本身已说明「未完成」；
    // 修改样式需改动 `app/src/**`，不在本模块范围内，故仅在此记录。
    val opController = ApiOperationController(scope) { reason -> emitWriteNotice(reason) }

    // ── Network ──

    /**
     * 网络页整套数据最近一次**完整成功**落地的时刻（单调时钟，`0` = 本进程内从未成功）。
     * 判据窗口是 [NETWORK_ALL_FRESH_MS]。单调时钟的理由见 `DataFreshness.kt`。
     */
    @Volatile private var networkAllSuccessElapsed: Long = 0L

    // ── 详情类子资源的新鲜度戳 ──────────────────────────────────
    //
    // 网络页首屏会预取频段 / 基站 / 设备设置 / LAN / 身份，而对应详情页或弹窗进入时
    // 又会各拉一次，使预取结果刚落地即被作废，造成重复请求。这四个戳使详情页**复用**
    // 预取结果；写后回读 / data_changed 事件显式传 force = true 绕过。
    //
    // 窗口取 30s（与 NETWORK_ALL_FRESH_MS 同源）；cellInfo 对齐 core 的 20s 缓存 TTL。
    @Volatile private var bandStatusSuccessElapsed: Long = 0L
    @Volatile private var cellInfoSuccessElapsed: Long = 0L
    @Volatile private var deviceSettingsSuccessElapsed: Long = 0L
    @Volatile private var lanSettingsSuccessElapsed: Long = 0L

    /**
     * 最近一次外部变更信号的单调时刻（0 = 无）。
     * 见 [loadNetworkAll] 闸门豁免；由 MainViewModel.dispatchChanged 对网络相关前缀调用 [markExternalChange]。
     */
    @Volatile var externalChangeElapsed: Long = 0L
        private set

    /** 收到外部变更信号时由 MainViewModel.dispatchChanged 调用。 */
    fun markExternalChange() { externalChangeElapsed = SystemClock.elapsedRealtime() }

    /**
     * 网络页需要的那一整套数据。
     *
     * ## 子资源的新鲜度
     * 频段 / 基站 / 设备设置 / LAN 各带 30s（cellInfo 20s）窗口：首屏预取过、详情页在窗口内
     * 进入时**一个请求都不发**；[force] 或外部变更信号会绕过。身份信息更简单：本进程内
     * 加载过就不再拉，只有 [force] 才重读。这样既保留「预取让详情页首帧不空」的体验，
     * 又不会使预取与详情页各请求一次设备。
     *
     * ## 该扇出位于 module 而非页面的原因
     * 该逻辑原为 `NetworkScreen` 的私有 `loadNetworkAll(viewModel)`，页面每次 `pageForeground`
     * 翻 true 都会无条件发出 6 个请求。预加载协调器也需发出同一批请求，判据（新鲜度戳）只能
     * 有一份；戳保存在 module 中才能跨组合存活，放在 composable 的 `remember` 里换页往复即丢失。
     *
     * ## [force] 与 [silent]
     * - [force]：绕过新鲜度窗口。写后回读、用户手动重试走这条。
     * - [silent]：失败**不写** `errorMessage`。预加载专用 —— 该批请求发生在用户未浏览的页面上，
     *   把失败写入 `networkState.errorMessage` 会经 `MainViewModel.rawGlobalError` 冒成全局 Toast，
     *   表现为「刚启动 App 即提示加载失败」。页面自身的调用一律 `silent = false`。
     *
     * 新鲜度只看 `signalInfo`：它由 [refreshNetwork] 这条主请求产出，也是本页 hero 卡的主数据。
     * 其余 5 项任一失败时字段保持 null，页面那条"出错 5s 后重试"的效应会补。
     */
    fun loadNetworkAll(force: Boolean = false, silent: Boolean = false) {
        // 能力集借本次调用一并预热（其自身有「本进程只成功拉取一次」的闸门，因此置于新鲜度
        // 判断之前也不会多发请求）。这样用户进入设置页时能力集已就绪，不会出现
        // 「页面先按全部支持绘制、随后某个开关变灰」的闪动。
        loadDeviceCapabilities()
        // 30s 闸门仅防止「无变化的重复拉取」；收到外部变更信号（web / 另一端写操作）后，
        // 数据陈旧的风险大于重复拉取成本（6 请求 / 30s 上限 1 次），因此放行。
        // 成功落地后清零，不连续放行（见 refreshNetwork 的成功打戳处）。
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
        // 外部变更信号意味着「另一端可能改过」，子资源也一并强制回读；普通的
        // pageForeground 重入则让各子资源自己按 30s 窗口决定是否复用。
        val subForce = force || externallyStale
        refreshNetwork(silent = silent)
        loadBandStatus(silent = silent, force = subForce)
        loadCellInfo(silent = silent, force = subForce)
        loadDeviceSettings(silent = silent, force = subForce)
        loadLanSettings(silent = silent, force = subForce)
        loadDeviceIdentity(force = subForce)
    }

    /**
     * 换设备 / 换地址后丢掉所有新鲜度基准。
     *
     * 若不清理，新设备页面会因旧设备的戳仍在新鲜窗口内而跳过请求，
     * 屏幕上保留上一台设备的信号与 WiFi 名称。与 `DashboardModule.clearDashboardCache()` 同一批调用。
     */
    fun resetFreshness() {
        networkAllSuccessElapsed = 0L
        wifiSuccessElapsed = 0L
        serviceStatusSuccessElapsed = 0L
        bandStatusSuccessElapsed = 0L
        cellInfoSuccessElapsed = 0L
        deviceSettingsSuccessElapsed = 0L
        lanSettingsSuccessElapsed = 0L
        // 能力集一并清除：它是**上一台设备**的静态快照，换设备 / 换地址后必须重新获取。
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
    // 判据由 state 自身承载，不额外维护第二个布尔量，避免两者漂移。
    //
    // ── 刷新时机 ──
    // 挂在 [resetFreshness] 上，而不是某个页面的 onResume：`resetFreshness` 正是
    // MainViewModel 在 `prepareDeviceSwitch()`（换设备）与
    // `onServerEndpointChanged(hostChanged = true)`（换地址）里调的那一个，
    // 语义恰好等于"现在对面可能是另一台设备了"。选它等于复用已有的唯一判据，
    // 不必再造一条"什么算重连"的规则。
    //
    // ── 拉取失败必须按「全部支持」处理 ──
    // catch 中**只记录日志、不写任何错误 state**，`_capabilityState` 保持 UNKNOWN，
    // 而 UNKNOWN 的判定结果为全部支持（见 DeviceCapabilityState）。
    // 不得将失败视为「全部不支持」：那会使一次网络抖动禁用整个设置页，
    // 并把「通道不可用」（503，可重试）误判为「设备不支持」（501，不可恢复）。
    // 同理不触发全局错误横幅：该请求属于后台预热，用户并未等待其结果。
    //
    // @param force 忽略已缓存结果重新拉取。当前无调用方，预留给后续「手动重新识别设备」功能。
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


    /**
     * 合并 REST 的 `networkStatus`：**空 `ppp_status` = 这轮没读到，保留上一帧**。
     *
     * core 的 `/api/network/status` KDoc 明确写过「空串不能当断」；但 `NetworkStatusResponse`
     * 的 `isCellularConnected` 只认 `connected` 子串，空串会算成未连接。轮询 / summary 里
     * 一次读超时就会让 hero 卡的「已连接」闪一下 —— 这里把上一帧的非空 ppp 补回去。
     *
     * @return fresh 为 null（请求失败）时原样返回 current。
     */
    private fun mergeNetworkStatus(
        current: NetworkStatusResponse?,
        fresh: NetworkStatusResponse?
    ): NetworkStatusResponse? {
        if (fresh == null) return current
        if (fresh.ppp_status.isNotBlank() || current == null) return fresh
        return fresh.copy(ppp_status = current.ppp_status)
    }

    fun refreshNetwork(silent: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            // 「有数据时不写 loading 态」（同 DashboardModule.refreshDashboardInternal 的判据）：
            // isLoading 在本页无可见用途，有数据时置为 true 只会造成一次无意义的整页重组。
            _networkState.update { current ->
                when {
                    current.signalInfo == null -> current.copy(isLoading = true, errorMessage = null)
                    current.errorMessage != null -> current.copy(errorMessage = null)
                    else -> current
                }
            }
            try {
                // WiFi 两项与 refreshWifi / 仪表盘前台刷新**共用**同一个 10s 新鲜度戳。
                // 刚在仪表盘刷新过 WiFi 即切到网络页时，这两项仍在窗口内，可直接复用并节省 2 个请求。
                // 这不构成跨模块耦合：戳与写入点均在本模块，仪表盘仅调用 refreshWifi()。
                val wifiFresh = _networkState.value.wifiSettings != null &&
                    isForegroundDataFresh(wifiSuccessElapsed, SystemClock.elapsedRealtime())

                val sig = async { runCatching { api.getSignalInfo() } }
                val net = async { runCatching { api.getNetworkStatus() } }
                val wifi = if (wifiFresh) null else async { runCatching { api.getWifiSettings() } }
                val clients = if (wifiFresh) null else async { runCatching { api.getWifiClients() } }

                val rSig = sig.await(); val rNet = net.await()
                val rWifi = wifi?.await(); val rClients = clients?.await()

                val failures = buildList {
                    if (rSig.isFailure) add("信号: ${rSig.exceptionOrNull()?.message ?: "未知"}")
                    if (rNet.isFailure) add("网络状态: ${rNet.exceptionOrNull()?.message ?: "未知"}")
                    rWifi?.takeIf { it.isFailure }?.let {
                        add("WiFi: ${it.exceptionOrNull()?.message ?: "未知"}")
                    }
                    rClients?.takeIf { it.isFailure }?.let {
                        add("客户端: ${it.exceptionOrNull()?.message ?: "未知"}")
                    }
                }.joinToString("; ").ifEmpty { null }

                // 必须用 update 而不是先取快照再 copy：这几个请求在飞的时候，WS `signal`
                // 可能已经写入了更新的读数；拿请求开始前的快照回写会把推送结果冲掉。
                _networkState.update { current ->
                    val netStatus = mergeNetworkStatus(current.networkStatus, rNet.getOrNull())
                    val wifiSettings = rWifi?.getOrNull() ?: current.wifiSettings
                    current.copy(
                        // REST 失败保留上一帧。signalInfo 现在主要由 WS 推送喂养，
                        // 一次失败的 REST 回读把它清成 null 会让 hero 卡闪回 “—”，
                        // 下一次推送才恢复。
                        signalInfo = rSig.getOrNull() ?: current.signalInfo,
                        networkStatus = netStatus,
                        wifiSettings = wifiSettings,
                        wifiClients = rClients?.getOrNull() ?: current.wifiClients,
                        wifiEnabled = wifiSettings?.enabled ?: current.wifiEnabled,
                        // 状态请求失败时**保留上一次的开关值**，不得写成 false。
                        // 这条 refreshNetwork 是切制式收尾的必经之路（awaitNetworkModeApplied），
                        // 网络抖动导致的那一次失败会让开关凭空显示"关闭"，正是本次要修的现象之一。
                        mobileDataEnabled = netStatus?.mobile_data ?: current.mobileDataEnabled,
                        isLoading = false,
                        errorMessage = if (silent) current.errorMessage else failures
                    )
                }
                // 只在**全部成功**时记新鲜度基准：部分失败时字段是缺的，
                // 记了戳会让下一次前台化跳过重拉、把缺字段固化成"新鲜"。
                if (failures == null) {
                    networkAllSuccessElapsed = SystemClock.elapsedRealtime()
                    // WiFi 这次没发请求时不能推进它的戳 —— 否则每次全量刷新都会把
                    // “10s 新鲜”续期，refreshWifi 自己的闸门就永远不会到期。
                    if (!wifiFresh) wifiSuccessElapsed = SystemClock.elapsedRealtime()
                    // 成功落地即清零外部变更戳，避免豁免被连续放行（不退化闸门）
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
     * 轮询用的 `runCatching`：**取消必须原样抛出**。
     *
     * 普通 `runCatching` 会把 `CancellationException` 也吞成 `Result.failure`，
     * 页面离开前台时轮询协程就不能及时结束（还会带着半份结果写状态）。
     */
    private suspend fun <T> apiCall(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * 网络页前台的 10s 轻量轮询（与 Web 网络页对齐）。
     *
     * ## 在 signal / connection 两条 WS 之外仍需轮询的原因
     * - `networkStatus` 的 `mobile_data`、`operator`、`network_type` 与 `wifiClients`
     *   在 core 中均无高频频道；设备面板直接修改（绕过 core）只能通过轮询发现；
     * - Web 网络页使用同一节奏：`refreshNetworkStatus() + loadWifiClients()` 每 10s；
     *   两端对「网络页数据最长允许陈旧多久」采用同一数值，行为才一致。
     *
     * ## 请求数
     * 常态 **2 个**：`/api/network/status` + `/api/wifi/clients`。
     * [includeSignal] = true 时增加 1 个 `/api/network/signal`，仅在 WS 未连接时由调用方传入，
     * 用于维持实时信号（与 Web 的 `wsStore.status !== 'connected'` 分支口径一致）。
     *
     * ## 设计为 suspend 而非 scope.launch 的原因
     * 调用方是页面的 `LaunchedEffect` 循环。等待本轮结束后再 delay，可避免慢请求叠加；
     * 页面离开前台时协程取消，在途请求一并取消，轮询生命周期与页面一致。
     *
     * ## 失败语义
     * 仅记录日志，不写 [NetworkState.errorMessage]：10s 一次的轮询失败不应反复触发全局错误；
     * 真正的失败由页面首屏或操作确认器报告。
     */
    suspend fun refreshNetworkLive(includeSignal: Boolean = false) = coroutineScope {
        val status = async { apiCall { api.getNetworkStatus() } }
        val clients = async { apiCall { api.getWifiClients() } }
        val signal = if (includeSignal) async { apiCall { api.getSignalInfo() } } else null

        val rStatus = status.await()
        val rClients = clients.await()
        val rSignal = signal?.await()

        rStatus.exceptionOrNull()?.let { DebugLog.w("Network", "10s 轮询网络状态失败: ${it.message}") }
        rClients.exceptionOrNull()?.let { DebugLog.w("Network", "10s 轮询 WiFi 客户端失败: ${it.message}") }
        rSignal?.exceptionOrNull()?.let { DebugLog.w("Network", "10s 轮询信号失败（WS 断线兜底）: ${it.message}") }

        _networkState.update { current ->
            val netStatus = mergeNetworkStatus(current.networkStatus, rStatus.getOrNull())
            current.copy(
                networkStatus = netStatus,
                // 开关态 = 用户意图；轮询能兜住另一端 / 设备面板的改动
                mobileDataEnabled = netStatus?.mobile_data ?: current.mobileDataEnabled,
                wifiClients = rClients.getOrNull() ?: current.wifiClients,
                signalInfo = rSignal?.getOrNull() ?: current.signalInfo
            )
        }
    }


    /**
     * 开 / 关移动数据（`POST /api/network/mobile-data`）。
     *
     * ## 三项约束
     * ① **乐观回显**：Switch 绑定 [NetworkState.mobileDataEnabled]，该值仅在
     *    `refreshNetwork()` 返回后更新。若不做乐观回显，点击后到刷新完成之间（最长 5s +
     *    一轮全量刷新）开关保持旧值，表现为「点击无响应，需切换页面后才更新」。因此先就地
     *    置为目标值。
     * ② **等待目标状态而非「已连接」**：`waitForNetworkReady()` 默认等待 isCellularConnected
     *    为真；关闭移动数据时该条件永不成立，每次关闭都会空等满 5 秒。
     * ③ **不改变全页 isLoading**：改为 [NetworkState.mobileDataPending]，仅影响当前行。
     * 失败时回滚乐观值，避免 UI 停留在未生效状态。
     *
     * 回滚由 onSettled(false) 中的 refreshNetwork() 完成，而非依赖 catch 内的 `previous`：
     * 设备真值比本地记录的上一个值更可靠，详见 onSettled 的注释。
     *
     * ## 确认器接入
     * 直接 `api.setMobileData(...)` 后调用 `waitForNetworkReady(expectConnected = enabled)` 的
     * 实现有 5 秒预算、每 500ms 一次 `GET /api/network/status`（最多 11 次），到点直接返回
     * 且不写任何字段（`waitForNetworkReady` 无返回值）。弱信号下重新拨号常需十余秒，
     * 最常见的结果是「设备实际成功、App 无任何反馈」。
     * 该实现的问题：① 超时静默，用户只能依靠开关是否自行变回判断结果；
     * ② 节奏与另一端不同源 —— web 端同一操作有 13 次 / 30.6s 的回读与明确提示
     * （`web/src/composables/useNetworkControls.ts:378-394`），同一台设备在两个客户端上的
     * 超时判定不一致。
     *
     * 因此接入 [opController]（与同屏的 WiFi 开关 [setWifiEnabled]、频段锁定 [lockBands]
     * 结构一致，也与 web 一致）：预算由 contract 的 [NetworkMode.SwitchProbe] 给出
     * （首延 600ms + 快档 1.5s×4 + 慢档 3s×8 = 13 次 / 30.6s），超时文案取确认器的
     * 「未在预期时间内完成，设备可能仍在后台继续」，并提供「后台进行」出口。
     *
     * **产品取舍**：确认器会带来**阻塞式进度弹窗**，与「乐观回显 + 仅切换一行」的轻交互不同。
     * 采用确认器而非「保留轻交互、仅将预算提高到 30.6s」的原因：
     * ① 同屏相邻的「WiFi 热点」开关已使用确认器，两个相邻开关一个弹窗一个不弹不一致；
     * ② 轻交互路径没有「设备可能仍在后台继续」这句话的落点，该文案的唯一出处是确认器
     *    （与 web 相同），否则需再复制一份文案或继续静默；
     * ③ 30.6s 的等待必须提供用户出口，否则表现为「卡住半分钟无法操作」；
     *    「后台进行」仅在确认器中提供。
     * 轻交互特性仍保留：乐观回显与行内 [NetworkState.mobileDataPending]（「切换中…」）
     * 均保留 —— 选择「后台进行」后该行仍显示「切换中…」，直至 onSettled 收尾。
     */
    fun toggleMobileData(enabled: Boolean) {
        // 乐观回显：先就地置为目标值，避免「点击无响应」。
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
                // 判据：蜂窝连接态 == 目标（关数据时等待断开，见上文约束②）。与 web 同源 ——
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
                // 超时也需回读一次：乐观值由本地写入，只有设备真值能纠正它。
                // 无论是否等到目标状态都执行 refreshNetwork()。
                // **不回滚**乐观值：设备可能仍在后台继续，回滚等于断言「未生效」，而该判断无法确知。
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
     *  ## [force] 的分工
     *  调用点之一是 `DashboardScreen` 中以 `pageForeground` 为 key 的 `LaunchedEffect`：
     *  每次横滑回到首页都会再次进入，两个 REST 回包各写一次 `networkState`，
     *  落在胶囊归位的可见运动窗口内，造成顿挫。首页 hero 卡仅用它显示「已连接设备数 +
     *  WiFi 名称 / 频段」，10s 内的旧值完全可用，因此默认经过新鲜度闸门
     *  （[isForegroundDataFresh]，窗口 [FOREGROUND_REFRESH_INTERVAL_MS]）。
     *
     *  必须 `force = true` 的三类调用方：
     *  - **写后回读**（`setWifiEnabled` / ACL 写入）：值刚被修改，使用缓存会显示旧状态；
     *  - **在线设备页的 5s 轮询**（`OnlineDevicesScreen`）：该页要求设备上下线随时可见，
     *    被 10s 窗口节流等于降低其刷新率。
     *  本方法**不写任何 loading 态**（原本即如此），除此之外无需再改。
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
     * 不得本地增删推算：设备侧是整表替换，本地推算会和 web / 设备自带 UI 的操作互相覆盖。
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
     * 下发成功后调用 `waitForNetworkReady(expectConnected = !enabled)` + `refreshNetwork()` 的
     * 实现与 [toggleMobileData] 存在相同的 5 秒静默预算问题：到点直接返回、不写任何字段。
     * 其失败分支提示「设备尚未完成，可稍后刷新查看」，而实际发生的超时路径不经过该分支
     * （`waitForNetworkReady` 不抛异常，catch 仅能捕获下发失败）。
     *
     * 因此与 [toggleMobileData] 一致接入 [opController]（web 侧同一操作使用 13 次 / 30.6s
     * 的回读预算，见 `web/src/composables/useNetworkControls.ts:398-411`）。
     * 飞行模式会**断开蜂窝**，30.6s 内用户很可能需要执行其他操作，
     * 「后台进行」出口在此比移动数据场景更必要。
     *
     * 契约补充：core 的 `/api/network/airplane` 只有写、没有读（web 侧注释相同），
     * 因此回读判据只能借用 `GET /api/network/status` 的蜂窝连接态。
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
                // 开飞行模式 = 等待断开，关飞行模式 = 等待连上（同 [toggleMobileData] 的约束②）
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
     * 三项约定缺一不可：
     * 1. **先进「切换中」**（[NetworkState.pendingNetworkMode]），界面立即显示目标档位。
     *    设备重新注册期间 `GET /api/device/settings` 仍返回旧档位，若按回读值渲染，
     *    会出现「点了 4G 界面仍显示仅 5G」。
     * 2. **失败文案取 core 给出的原因**。core 将设备侧失败映射为 503 / 502 + 中文 message
     *    （会话失效 / 设备明确拒绝），仅提示「设置失败」会丢弃该信息。
     * 3. **回读有上限**（[awaitNetworkModeApplied]）。超时须明确提示，不得静默停留在旧值。
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
                deviceSettingsSuccessElapsed = SystemClock.elapsedRealtime()
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
        // 成功提示**放在这里而不是下发成功之后** —— 制式切换的"成功"是
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
     * 独立出来是为了让调用方自行拼接前缀：失败提示需说明**哪一项**保存失败，
     * 而 [coreErrorMessage] 的兜底前缀是通用的「设置失败」，套在具体文案外会形成
     * 「WiFi 热点保存失败：设置失败: …」这类两层前缀。
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
     * 通过 [opController] 执行「执行 → 等待 → 回读确认 → 更新」：圆环弹窗 + 集合相等判定 +
     * 超时提示。仅下发后调用 `waitForNetworkReady()` + `loadBandStatus()` 而不确认结果时，
     * 锁频实际需十几至二十几秒才生效，用户无法看到中间状态。
     *
     * **签名约定**：入参为 `Set<Int>?`，判定直接使用 [BandStatusResponse] 自带的集合解析
     * （"0"/"all" = 空集），调用方不再拼接字符串。
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
                // 这次 verify 已经拿到设备真值 → 让后面的 loadBandStatus 能复用它
                bandStatusSuccessElapsed = SystemClock.elapsedRealtime()
                return status.lteBands == targetLte && status.nrBands == targetNr
            }

            override fun onApplied() {
                // 锁频 / 解锁都会让设备重启网络栈并重新驻网，而 verify 只盯
                // band-status 集合 —— 制式、频段标签、RSRP 这些 hero 卡的字段不在它的判据里；
                // 若只补 `loadBandStatus()`，hero 卡只能等待下一次
                // 推送。因此补一次轻量回读（信号 + 网络状态 + 频段），silent = true：此时
                // 设备正在重启网络栈，请求失败属于预期，不得触发全局错误浮层。
                refreshNetworkLight(silent = true)
                emitWriteNotice(successText)      // 复用既有 toast 通道
            }

            override fun onSettled(applied: Boolean) {
                // 超时 / 取消也要回读一次：设备可能仍在后台继续重启网络栈并最终生效，
                // 而那一刻不会再有任何写事件把它带回界面（口径同 toggleMobileData）。
                if (!applied) refreshNetworkLight(silent = true)
            }
        })
    }

    /**
     * @param silent 失败不写 `errorMessage`（预加载专用，见 [loadNetworkAll]）。
     * @param force 绕过 30s 新鲜度窗口（写后回读 / `network:band-status` 事件 / 手动刷新）。
     */
    fun loadBandStatus(silent: Boolean = false, force: Boolean = false) {
        if (!force &&
            _networkState.value.bandStatus != null &&
            isForegroundDataFresh(
                bandStatusSuccessElapsed, SystemClock.elapsedRealtime(), NETWORK_ALL_FRESH_MS
            )
        ) return
        scope.launch {
            try {
                val status = api.getBandStatus()
                _networkState.update { it.copy(bandStatus = status, loadVersion = System.currentTimeMillis()) }
                bandStatusSuccessElapsed = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                if (silent) DebugLog.w("Network", "频段状态加载失败（静默）: ${e.message}")
                else _networkState.update { it.copy(errorMessage = "频段状态加载失败: ${e.message}") }
            }
        }
    }

    /**
     * @param silent 失败不写 `errorMessage`（预加载专用，见 [loadNetworkAll]）。
     * @param force 绕过 30s 新鲜度窗口（写后回读 / `device:settings` 事件）。
     */
    fun loadDeviceSettings(silent: Boolean = false, force: Boolean = false) {
        if (!force &&
            _deviceSettingsState.value.settings != null &&
            isForegroundDataFresh(
                deviceSettingsSuccessElapsed, SystemClock.elapsedRealtime(), NETWORK_ALL_FRESH_MS
            )
        ) return
        scope.launch {
            _deviceSettingsState.update { it.copy(isLoading = true, loadVersion = System.currentTimeMillis()) }
            try {
                // force = 写后回读 / 外部变更：必须穿透 core 的 5 分钟读缓存，否则
                // 一次「设备还没应用完」的读会把旧快照重新缓存 5 分钟（见 API 的 KDoc）。
                val settings = if (force) api.getDeviceSettingsFresh() else api.getDeviceSettings()
                _deviceSettingsState.update { DeviceSettingsState(settings = settings, loadVersion = System.currentTimeMillis()) }
                deviceSettingsSuccessElapsed = SystemClock.elapsedRealtime()
            }
            catch (e: Exception) {
                if (silent) {
                    DebugLog.w("Network", "加载设备设置失败（静默）: ${e.message}")
                    _deviceSettingsState.update { it.copy(isLoading = false) }
                } else {
                    _deviceSettingsState.update { it.copy(errorMessage = "加载设备设置失败: ${e.message}", isLoading = false) }
                }
            }
        }
    }

    /**
     * 拉取设备身份信息（`GET /api/device/identity`）：本机号码(msisdn) / IMEI 等。
     * 只写 [NetworkState.deviceIdentity]，失败不影响其它网络数据（错误吞掉，UI 回落到 "—"）。
     *
     * @param force 绕过「已加载过」短路。身份信息是静态的，普通前台化不应重复请求；
     *   只有 [loadNetworkAll] 的 force / 外部变更才重读（换 SIM 后 msisdn 可能变）。
     */
    fun loadDeviceIdentity(force: Boolean = false) {
        if (!force && _networkState.value.deviceIdentity != null) return
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
     * 自动 / 手动拨号。成功提示：这一项改完界面上**什么都不变**
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
     * 不得复用 `AppHttpClient.instance`：它会为每个请求注入 `Authorization: Bearer <设备 token>`
     * —— 相当于将本机设备令牌发送给第三方测速站（tele2 等），构成凭据泄露；
     * 其 trace 拦截器还会对响应执行 `peekBody(4096).string()` 并写日志，对流式响应会空等首包、
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
     * 拉定时测速历史。失败静默：历史是增益信息，
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
     * 不允许 UI 传任意 URL，配套一个"修改测速地址"弹窗。改成固定清单后那个入口
     * 一并删除——任意地址会带来两类无法在客户端消除的误差：被 CDN/代理按文本类型压缩后
     * 统计到解压字节（虚高），以及文件太小提前 EOF（虚低）。自建节点两点都规避了。
     * 多节点只是同一套端点契约部署在不同平台（EdgeOne / Cloudflare），用来对比出口质量。
     */
    fun runExternalSpeedTest(nodeId: String) =
        startSpeedTest("external", SpeedTestState.externalNode(nodeId))

    /**
     * 测速统一实现（按 LibreSpeed 的测量方法论重做，未引入其代码）。
     *
     * 流程：延迟/抖动探针 →（外网：上行能力探测）→ 多流并发下行 → 多流并发上行 → 出结果。
     *
     *  1. **多流并发**（[SPEED_STREAMS] 条）：单条 TCP 流的吞吐受 RTT × 窗口限制，跨公网常常
     *     只能跑到链路的一小半。所有流把字节加进同一个 [AtomicLong]，由**独立的采样协程**按固定
     *     节拍读差值 —— 采样与搬运完全解耦，读取循环内不计算速率。
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
                // 取消时会主动关流，read() 抛的是 IOException 而不是 CancellationException，
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
     * 外网探针：只取首包往返，**不得读完响应体**。
     *
     * 为让连接进入连接池而调用 `body.bytes()` 时，若服务器忽略 `Range` 直接返回 `200` +
     * 整个文件，`bytes()` 会把上百 MB 读入内存 —— 10 次探针即十余次整文件下载，
     * 表现为「开始后长时间无响应、内存暴涨」。
     * 因此仅在服务器确实返回 `206` **且**长度极小时读取那一两个字节（以复用连接）；
     * 返回 `200` 时立即关闭，宁可放弃 keep-alive 也不读取整个文件。
     *
     * Call 必须登记进 [speedTestClosers]：`execute()` 是阻塞调用，协程 cancel 无法打断，
     * 只有 `Call.cancel()` 能让取消在探针阶段立即生效；缺少该步骤时取消按钮在此阶段无响应。
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
     * 外网续传：一个 URL 读到 EOF 后重开同一个 GET，直到采样协程叫停。
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
     *
     * @param silent 失败不写 `errorMessage`（预加载专用，见 [loadNetworkAll]）。
     * @param force 绕过 [CELL_INFO_FRESH_MS] 窗口（锁/解锁基站后回读、`network:cell-info` 事件、手动刷新）。
     */
    fun loadCellInfo(silent: Boolean = false, force: Boolean = false) {
        if (!force &&
            _networkState.value.cellInfo != null &&
            isForegroundDataFresh(
                cellInfoSuccessElapsed, SystemClock.elapsedRealtime(), CELL_INFO_FRESH_MS
            )
        ) return
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
                // 只有 cellInfo 主查询成功才打戳：邻区失败不影响「基站信息」本身的复用。
                if (rCell.isSuccess) cellInfoSuccessElapsed = SystemClock.elapsedRealtime()
            }
            catch (e: Exception) {
                if (silent) DebugLog.w("Network", "基站信息查询失败（静默）: ${e.message}")
                else _networkState.update { it.copy(errorMessage = "基站信息查询失败: ${e.message}") }
            }
        }
    }

    /**
     * 锁基站。networkType 只接受 core 契约值域 "LTE" / "NR"（core 会自行映射成设备侧 12/16），
     * 不得传入设备数字码或旧入参名 rat（core 仍兼容但已输出 WARN，后续版本将移除）。
     *
     * ──────────────────────────────────────────────────────────────
     * 仅执行 `api.cellLock(...)` 成功后 `delay(500); loadCellInfo()` 的做法只有一次回读，
     * **没有判据**，读到什么就渲染什么。锁定是异步生效的（设备需重新选小区、重新驻网），
     * 500ms 后的回读几乎必然仍是旧小区，表现为「点击锁定后界面仍显示原基站」，
     * 用户无法判断是否锁定成功；且只有一次机会、没有预算，等于把「命令已下发」当作「已锁定」。
     *
     * 因此接入 [opController]，判据为**服务小区即目标小区**（pci + earfcn 均匹配），
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
                if (cell != null) cellInfoSuccessElapsed = SystemClock.elapsedRealtime()
                return servingCellMatches(sig, cell, targetPci, targetEarfcn)
            }

            override fun onApplied() {
                // 写后回读：基站页的"已锁定基站"列表与邻区都可能变了（force 绕过 20s 窗口）
                loadCellInfo(force = true)
                // 锁基站后设备会重新驻网，hero 卡的制式/频段/RSRP 需要跟着走。
                // verify 只保证了"服务小区就是目标"，随后仍可能有短暂的重新注册窗口；
                // 补一次轻量回读 + 依赖 WS `signal` 推送继续纠正（见 [collectSignalFromWs]）。
                refreshNetworkLight(silent = true)
                emitWriteNotice(successText)
            }

            override fun onSettled(applied: Boolean) {
                if (!applied) refreshNetworkLight(silent = true)
            }
        })
    }

    /**
     * 解锁全部基站（`POST /api/device/cell-unlock`，回 `{"success": bool}`）。
     *
     * 与 [cellLock] 相同，`delay(500); loadCellInfo()` 的单次回读没有判据。
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
                if (cell != null) {
                    _networkState.update { it.copy(cellInfo = cell) }
                    cellInfoSuccessElapsed = SystemClock.elapsedRealtime()
                }
                // lockedCellCount 返回 null = 这次没读到列表 → 按未命中继续等，不当成"已解锁"
                return lockedCellCount(cell) == 0
            }

            override fun onApplied() {
                loadCellInfo(force = true)
                // 同 [cellLock]：解锁后服务小区可能变化，hero 卡不能停在旧读数上
                refreshNetworkLight(silent = true)
                emitWriteNotice(successText)
            }

            override fun onSettled(applied: Boolean) {
                if (!applied) refreshNetworkLight(silent = true)
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
     * @return null = 这次回读没拿到 / 解不出列表 —— 调用方按未命中继续等，**不得**视为「已解锁」。
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
    /**
     * @param silent 失败不写 `errorMessage`（预加载专用，见 [loadNetworkAll]）。
     * @param force 绕过 30s 新鲜度窗口（DHCP 保存后回读 / `device:lan` 事件）。
     */
    fun loadLanSettings(silent: Boolean = false, force: Boolean = false) {
        if (!force &&
            _networkState.value.lanSettings != null &&
            isForegroundDataFresh(
                lanSettingsSuccessElapsed, SystemClock.elapsedRealtime(), NETWORK_ALL_FRESH_MS
            )
        ) return
        scope.launch {
            if (!silent) {
                _networkState.update { it.copy(errorMessage = null, loadVersion = System.currentTimeMillis()) }
            }
            try {
                val settings = api.getLanSettings()
                _networkState.update { it.copy(lanSettings = settings, loadVersion = System.currentTimeMillis()) }
                lanSettingsSuccessElapsed = SystemClock.elapsedRealtime()
            }
            catch (e: Exception) {
                if (silent) DebugLog.w("Network", "LAN 设置查询失败（静默）: ${e.message}")
                else _networkState.update { it.copy(errorMessage = "LAN设置查询失败: ${e.message}") }
            }
        }
    }

    /**
     * 下发 DHCP / LAN 设置。
     *
     * 失败路径与 `resp.success` 校验无需改动；此处补充成功提示：
     * 保存成功原为静默：写后回读需 500ms 才返回，此期间界面无变化，
     * 用户无法区分「点击无响应」与「已保存」。
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
                    delay(500); loadLanSettings(force = true)
                }
                else _networkState.value = _networkState.value.copy(errorMessage = "DHCP设置失败")
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "DHCP设置失败: ${e.message}") }
        }
    }

    // ── Service Control ──
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
                emitWriteNotice(if (s.enabled) "后台服务已启动" else "后台服务已停止（HTTP 服务仍在运行）")
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
     * ## 从「固定等 13s 回读一次」改为「带超时的重连轮询」
     * 固定 `delay(13_000)` 后仅 `loadServiceStatus()` 一次时，若设备端稍慢（或 core 侧重启
     * 闹钟被系统拒绝），该次回读会落空：`restarting` 被清为 false、`enabled` 仍为上一次的 true，
     * UI **既不提示已停止也不提示失败**，停留在错误状态。
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
                emitWriteNotice(resp.hint ?: "后端服务正在重启，约 10 秒后恢复")
            } catch (e: Exception) {
                // 重启会掐断连接，请求失败反而是常见结果 —— 不当成错误刷红。
                DebugLog.w("Network", "restart 请求未收到响应（服务可能已断开）: ${e.message}")
                emitWriteNotice("后端服务正在重启，约 10 秒后恢复")
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
                    emitWriteNotice("后端服务已恢复")
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

    // ── Web 控制面板资源 ──
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
                emitWriteNotice("Web 面板已恢复内置版本")
            } catch (e: Exception) {
                _webAssetState.value = _webAssetState.value.copy(errorMessage = "恢复失败: ${e.message}")
                emitDashboardError("Web 面板恢复失败: ${e.message}")
            }
            _webAssetState.value = _webAssetState.value.copy(isBusy = false)
            // 成功与否都回读一次：失败时 override 可能仍在，UI 必须反映真实状态
            loadWebAssetInfo()
        }
    }

    // ── Web 面板：自动更新 / 回滚 / 上传（status|check|update|rollback）──
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
                emitWriteNotice("Web 面板已回滚到上一版")
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
                if (resp.success) emitWriteNotice("设备正在重启...")
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
                if (resp.success) emitWriteNotice("设备正在关机...")
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
     * ## 改为等待结果并返回
     * 原来是 fire-and-forget，而调用方（`ServerConfigScreen` 的「设备后台」弹窗）在调用完
     * 立刻 `prefs.goformPasswordSet = true` + 关窗 + 弹「已保存」。旧密码填错时 core 会拒，
     * 界面却已经关掉并且本地记成"密码已设置" —— 假成功还带一条错误的本地状态。
     *
     * @return true = core 已接受新密码。调用方据此决定是否关闭弹窗、是否写入本地标记。
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
                // 这里**不再等回读结果**。实测设备重启热点需要 15~20 秒
                // （连接 34.7s 断 → 51.3s 通），任何"等回读确认再定成败"的窗口都会撞在
                // 重启期里、必然读不到新值，于是把已经生效的配置误判成失败。
                // 既然失败后没有自愈路径，正确做法是：请求返回就报成功，界面立刻收工；
                // 真正的生效状态交给这次回读去刷新（成不成功它都会去跑）。
                delay(500)
                refreshWifi(force = true)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                // scope 取消（退出页面 / 进程进入后台）不是「保存失败」，
                // 原样抛回 —— 吞掉会使调用方拿到错误的 false，并使 emitWriteNotice 在取消后
                // 弹出「已保存」的假成功。该行为与 core 侧「取消不是失败」的约定一致。
                throw e
            } catch (e: Exception) {
                // **仅在这一步失败时**提示，且通过 toast 呈现，
                // 不再写入 errorMessage 使弹窗留在屏幕上。
                //
                // 修改 WiFi 配置会使设备重启热点，请求大概率等不到响应，该「失败」实际是
                // 成功。此时任何「保存失败」提示都是错误的，且将弹窗保持在屏幕上会让用户误以为未生效。

                // core 明确拒绝的情况（4xx，如密码长度非法）原因在本层即可取得，按原样透出。
                // 两类失败分别走两条通道：
                // emitWriteNotice 只有 message/subtitle，没有成败标志，UI 图标固定为成功，
                // 因此用它报告失败会得到「绿色对勾 + 失败文案」的矛盾呈现。
                //
                // ① 连不上 = 设备正在重启热点，请求等不到响应。**这不是失败**：设备重启需
                //    15~20 秒，期间设备侧已应用配置。此处不应报告错误。
                // ② core 明确拒绝（4xx，如密码长度非法）= 真实失败，写入 errorMessage，
                //    由全局错误浮层呈现（其具备失败样式）。
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
     * **为什么独立下发、不与 `/config` 合并**：频段不属于热点配置 —— 设备只接受
     * `switchWiFiChip&ChipEnum=X&GuestEnable=0`，语义为「在该频段上启用 WiFi」。
     * 曾经通过 `/config` 的 `chip_index`（落到 `setAccessPointInfo → ChipIndex`）下发，
     * **设备不接受**，这是「频段修改后无反应」的根因，不得合并回去。
     *
     * 调用方必须先行确认（后果见 [WIFI_BAND_SWITCH_DISCONNECT_HINT]）：本方法与 core 路由
     * 均不带确认语义。执行顺序须在 `/config` **之后** —— 该命令会重启 WiFi 模块并中断连接；
     * 若先发它，SSID / 密码将无法落到设备。
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
                // 主动回读（force 绕过新鲜度闸门）：这一次需同时反映两项 ——
                // 新的生效频段（wifi_chip），以及「WiFi 被这条命令打开」这个副作用（moduleSwitch）。
                // 500ms 同 setWifiConfig：给设备应用配置的时间，0ms 大概率读回旧值。
                delay(500)
                refreshWifi(force = true)
                true
            } catch (e: Exception) {
                // 换频段会重启 WiFi 模块，连接必然中断 —— 那一发等不到响应就
                // ConnectException，但设备侧可能已经切过去了。口径同 setWifiConfig：
                // 先回读核实生效频段，再决定成败，否则用户看到的是「设备已切换、app 却报失败」。
                // 口径同 setWifiConfig —— 换频段同样会重启 WiFi 模块，
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
     * 此处不得调用 `waitForNetworkReady()`：该探针判断的是**蜂窝**是否连上，
     * 与 WiFi 开关无关：蜂窝已连时立即返回（等于未等待），蜂窝未连时反而空等满 5 秒。
     * 因此改为乐观回显 + [refreshWifi]（仅拉取 WiFi 设置与客户端列表，不做全量刷新）。
     */
    fun setWifiEnabled(enabled: Boolean) {
        // 接入确认器：WiFi 总开关生效需要模块重启，
        // 仅靠乐观回显与 catch 无法区分设备拒绝与超时，因此改为「执行 → 等待 → 回读确认」。
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
                // verify 刚读过设备真值 → 让 refreshNetwork/refreshWifi 的 10s 闸门复用它
                wifiSuccessElapsed = SystemClock.elapsedRealtime()
                return wifi.enabled == enabled
            }

            override fun onApplied() {
                // 写后回读：开关状态刚变，必须绕过新鲜度闸门（原路径行为保留）
                refreshWifi(force = true)
            }
        })
    }

    // ── 设备设置类写操作的统一收尾 ────────────────────────────────
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
        loadDeviceSettings(force = true)
    }

    /** 写失败：文案交给全局错误通道，并立刻回读把界面拉回设备真值（失败时不必等 500ms）。 */
    private suspend fun deviceSettingFailed(message: String) {
        _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = message)
        loadDeviceSettings(force = true)
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

    /**
     * 数据漫游开关（**回读确认**）。
     *
     * 这条与普通开关不同：设备对 `SET_CONNECTION_MODE` 的编码很挑（漫游要 `on`/`off`，
     * 不是 `1`/`0`），编码不对时**返回 200 但值不变**。原来走通用的
     * `deviceSettingSucceeded()`，会在下发成功时立刻弹「已开启」，500ms 后回读又把开关
     * 弹回去 —— 用户看到的就是「报成功但没生效」。
     *
     * 现在按下发结果 + 回读值双重判定：只有设备真的报出目标状态才弹成功；
     * 否则走错误通道明说「未生效，设备仍为…」。最多读 3 次（0.5s / 0.9s / 1.3s），
     * 覆盖设备应用配置的延迟。
     */
    fun setRoamingEnabled(enabled: Boolean) {
        scope.launch {
            try {
                val resp = api.setRoamingEnabled(mapOf("enabled" to enabled))
                if (!resp.success) {
                    deviceSettingFailed("漫游设置失败")
                    return@launch
                }
                var settings: DeviceSettingsResponse? = null
                for (attempt in 0 until 3) {
                    delay(if (attempt == 0) 500 else 400)
                    val read = runCatching { api.getDeviceSettingsFresh() }.getOrNull() ?: continue
                    settings = read
                    if (read.roamingEnabled == enabled) break
                }
                val finalSettings = settings
                if (finalSettings == null) {
                    deviceSettingFailed("漫游设置已下发，但回读失败")
                    return@launch
                }
                _deviceSettingsState.update {
                    DeviceSettingsState(
                        settings = finalSettings,
                        loadVersion = System.currentTimeMillis()
                    )
                }
                deviceSettingsSuccessElapsed = SystemClock.elapsedRealtime()
                if (finalSettings.roamingEnabled == enabled) {
                    emitWriteNotice(if (enabled) "数据漫游已开启" else "数据漫游已关闭")
                } else {
                    _deviceSettingsState.update {
                        it.copy(
                            errorMessage = "漫游设置未生效：设备仍为" +
                                if (finalSettings.roamingEnabled) "开启" else "关闭"
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deviceSettingFailed("漫游设置失败: ${e.message}")
            }
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
     * 这份 errorMessage 一直在写（`toggleService` / `restartService` 等四处），
     * 但全仓没有任何 UI 读它 —— 设置页那几个危险操作失败时界面**完全没有反馈**。
     * 现在由全局错误浮层统一展示（见 `MainViewModel.globalError`），浮层收起时回调本方法；
     * 不清的话错误会一直挂在 state 上，下次一进设置页又弹一次陈旧错误。
     */
    fun clearServiceError() { _serviceState.value = _serviceState.value.copy(errorMessage = null) }

    // ── Smart Refresh (data_changed 精准增量刷新) ──
    fun smartRefresh(changedType: String) {
        // invalidateAll 把单次写操作压成一条 "k1,k2,k3" 合并串，
        // split(',') 对单元素恒等（旧广播兼容），此处递归拆开逐 key 分发。
        if (changedType.contains(',')) {
            changedType.split(',').forEach { smartRefresh(it.trim()) }
            return
        }
        when {
            // WS 重连 sentinel —— 断线窗口变更不可知，
            // 按各自新鲜度策略 silent 补拉一轮（断线短则被闸门挡住，长则等效全量）。
            changedType == WsDataTopic.RECONNECTED -> {
                refreshNetwork(silent = true)
                // 断线窗口里的变更不可知 → 子资源也 force 回读，不吃 30s 窗口
                loadBandStatus(silent = true, force = true)
                loadCellInfo(silent = true, force = true)
                loadDeviceSettings(silent = true, force = true)
                loadLanSettings(silent = true, force = true)
                refreshWifi()
                // 断线窗口里的 `pairing:approvals` 推送已经丢了（replay=0），
                // 重连必须补拉一次 —— 否则「WS 刚断过」这个场景又要退回到 30s 轮询。
                loadApprovals()
            }
            changedType == "network:band-status" -> refreshNetworkLight()
            // cell-info 不在 [refreshNetworkLight] 的三项里。另一端（web / 设备面板）
            // 锁/解锁基站时 core 广播的是这个 key，只跑 light 等于白刷新 —— 已打开的
            // CellLockDialog 会一直显示旧服务小区与旧锁定名单。这里补一次 cellInfo 回读。
            changedType == "network:cell-info" -> {
                refreshNetworkLight()
                loadCellInfo(silent = true, force = true)
            }
            // `invalidateNetwork()` 的**通配**广播。core 的 `invalidate()`
            // 只在真有缓存条目被删时才广播；当具体 key（如 hub:network-type-info）没命中缓存，
            // 客户端就只剩这一条 `network:*`。不处理它 = 手机数据 / 飞行模式的外部切换
            // 在 app 侧完全静默。这里按最保守的三项回读，并跳过错误上报（不知道具体是哪个 key）。
            changedType == "network:*" -> {
                refreshNetworkLight(silent = true)
                loadCellInfo(silent = true, force = true)
            }
            // hub:network-type-info（G2 修复后 core 开始广播此 key）
            changedType == "hub:network-type-info" -> refreshNetworkLight()
            changedType.startsWith("wifi:") -> refreshWifiOnly()
            changedType == "device:lan" -> loadLanSettings(force = true)
            changedType == "device:settings" -> loadDeviceSettings(force = true)
            // core 的批准推送（`ApprovalPushNudger`）→ 立刻拉一次待批准列表。
            // 正常情况下这条 key 在 MainViewModel.dispatchChanged 里已经短路（不吃 800ms 去抖），
            // 走到这里说明有人改过那条短路 —— 兜底成"最多慢 800ms"，别静默丢掉。
            changedType == WsDataTopic.PAIRING_APPROVALS ||
                changedType.startsWith(WsDataTopic.PREFIX_PAIRING) -> loadApprovalsFromPush()
            // device:goform / device:qos 只有设备信息页/高级页消费——
            // 不在此主动拉（页面不在前台拉了白拉），靠 P6 的外部变更豁免在切页时放行重拉。
            // summary 由 DashboardModule 消费，此处忽略。
            else -> { /* 未知 key 静默忽略（与原 when 无匹配时一致） */ }
        }
    }

    /**
     * 只拉信号（`GET /api/device/signal`）并写入 [NetworkState.signalInfo]。
     *
     * 网络制式弹窗顶部需显示 PCI / 频点 / SINR / RSRQ，
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

    /**
     * 轻量刷新：仅信号 + 网络状态 + 频段状态（网络模式 / 频段锁定变更时触发）。
     *
     * @param silent 失败不写 [NetworkState.errorMessage]。
     *
     * A 级网络操作（锁频段 / 锁基站）收敛后要补这次回读，但那一刻设备往往正在
     * 重启网络栈 —— 请求失败是**预期内**的中间态，不该冒成全局错误浮层。这类调用方传 true，
     * 失败的字段保留旧值（下面每个 `?: it.xxx` 的用途），等 WS `signal` 推送或下一次刷新覆盖。
     */
    private fun refreshNetworkLight(silent: Boolean = false) {
        // 在途合并：见 [lightRefreshJob] 的说明。失败不在这里重试，下一条事件会再次触发。
        if (lightRefreshJob?.isActive == true) return
        lightRefreshJob = scope.launch {
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
                    errorMessage = if (silent) it.errorMessage else (failures ?: it.errorMessage)
                )}
                if (rBand.isSuccess) bandStatusSuccessElapsed = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                if (silent) DebugLog.w("Network", "轻量刷新失败（静默）: ${e.message}")
                else _networkState.update { it.copy(errorMessage = "刷新失败: ${e.message}") }
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
                // 事件驱动的 WiFi 刷新成功后也要打新鲜度戳，否则紧接着的
                // refreshNetwork 会认为 WiFi 已过期、再拉一遍（两边共用同一个 10s 窗口）。
                if (failures == null) wifiSuccessElapsed = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                _networkState.update { it.copy(errorMessage = "WiFi刷新失败: ${e.message}") }
            }
        }
    }

    // ── Pairing ──

    /**
     * @param preserveError 保留当前的 errorMessage。
     *
     * 失败回滚时要**先写错误、再按服务端真实状态刷新开关**，而这里会重建整个 `PairingState`——
     * 不保留的话刚写进去的提示立刻被冲掉（2026-11-14：取消主人密码后界面上什么提示都没有）。
     */
    fun loadPairingStatus(preserveError: Boolean = false) {
        scope.launch {
            val previousError = _pairingState.value.errorMessage
            _pairingState.value = _pairingState.value.copy(isLoading = true, errorMessage = if (preserveError) previousError else null)
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
                    // 批准策略 + 外部通知渠道。旧 core 不返回这些字段 →
                    // 一律回落 false（= 批准不生效、不通知），与"旧 core 没有这些能力"一致。
                    approvalRequired = json["pairing_approval_required"]?.jsonPrimitive?.content
                        ?.toBooleanStrictOrNull() ?: false,
                    notifySystem = json["pairing_notify_system"]?.jsonPrimitive?.content
                        ?.toBooleanStrictOrNull() ?: false,
                    notifyEmail = json["pairing_notify_email"]?.jsonPrimitive?.content
                        ?.toBooleanStrictOrNull() ?: false,
                    notifyWebhook = json["pairing_notify_webhook"]?.jsonPrimitive?.content
                        ?.toBooleanStrictOrNull() ?: false,
                    // 破坏性操作的主人密码门（默认关）。旧 core 不返回 → 回落 false。
                    ownerGateEnabled = json["owner_gate_enabled"]?.jsonPrimitive?.content
                        ?.toBooleanStrictOrNull() ?: false,
                    devices = parsePairedDevices(json["devices"]),
                    isLoading = false,
                    errorMessage = if (preserveError) previousError else null
                )
            } catch (e: Exception) {
                DebugLog.e("Network", "loadPairingStatus failed", e)
                _pairingState.value = _pairingState.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}")
            }
        }
    }

    // 下面这组写操作补充成功提示。
    // 失败路径不动 —— 它们写 `pairingState.errorMessage`，`PairingConfigScreen` 已经在渲染
    // （UfiErrorBanner），不是"写了没人读"那一类，不需要改道全局通道。

    /**
     * 保存配对策略（`PUT /api/pairing/config`）。
     *
     * 包含批准策略与三个外部通知开关（安全中心）。
     * 四个新参数都带默认值 → 既有调用点（只传 enabled/maxDevices）行为不变；
     * 传 null 表示"这次不动这一项"，避免把没在编辑的开关一并覆盖掉。
     */
    fun updatePairingConfig(
        enabled: Boolean,
        maxDevices: Int,
        approvalRequired: Boolean? = null,
        notifySystem: Boolean? = null,
        notifyEmail: Boolean? = null,
        notifyWebhook: Boolean? = null,
        ownerGateEnabled: Boolean? = null
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
                ownerGateEnabled?.let { body["owner_gate_enabled"] = it }
                api.updatePairingConfig(body)
                loadPairingStatus()
                // 关闭批准策略时 core 会清空待批准请求并广播 pairing:approvals；
                // 这里再主动拉一次，覆盖"WS 断线/推送丢失"的情况 —— 否则根部的批准弹窗
                // 会一直挂着一条已经被取消的请求。
                loadApprovals()
                // 文案要说清**这一次改的是哪一项**（2026-11-14）：
                // 同一个接口被四个开关复用，原先一律报「已允许/已停止接受新设备配对」——
                // 切换「不可逆操作保护」时也这么说，用户会以为改错了东西。
                when {
                    ownerGateEnabled == true -> emitWriteNotice(
                        "已开启「不可逆操作保护」",
                        subtitle = "恢复出厂、恢复备份、root shell 等操作将需要配对密码"
                    )
                    ownerGateEnabled == false -> emitWriteNotice(
                        "已关闭「不可逆操作保护」",
                        subtitle = "破坏性操作不再要求额外输入配对密码"
                    )
                    approvalRequired != null -> emitWriteNotice(
                        if (approvalRequired) "已开启新设备批准" else "已关闭新设备批准",
                        subtitle = if (approvalRequired) "新设备需在已配对设备上确认" else "新设备凭配对密码即可接入"
                    )
                    notifySystem != null || notifyEmail != null || notifyWebhook != null ->
                        emitWriteNotice("通知渠道已更新")
                    else -> emitWriteNotice(
                        if (enabled) "已允许新设备配对" else "已停止接受新设备配对",
                        subtitle = if (enabled) "上限 $maxDevices 台" else null
                    )
                }
            } catch (e: com.ufi_axis.data.api.OwnerPasswordRequiredException) {
                // 用户取消/超时：**不是保存失败**，是这道保护门在按设计工作。
                // 之前的文案是「保存失败: HTTP 401 Unauthorized」，让人以为程序出错。
                // 横幅会被随后的状态刷新冲掉，改用**常驻 toast**：用户必须看见"这不是报错"。
                emitWriteNotice(
                    "未保存：需要配对密码",
                    subtitle = "关闭「不可逆操作保护」要先输入配对密码，可再切一次并在弹窗里填写",
                    type = WriteNoticeType.WARNING
                )
                // 本地开关是乐观更新的，失败后必须按服务端真实状态回滚，否则界面显示"已关"而设备仍开着。
                // preserveError=true：刷新会重建 PairingState，不保留就把上面的提示冲掉了。
                loadPairingStatus(preserveError = true)
            } catch (e: Exception) {
                DebugLog.e("Network", "updatePairingConfig failed", e)
                _pairingState.value = _pairingState.value.copy(errorMessage = "保存失败: ${e.message}")
                loadPairingStatus(preserveError = true)
            }
        }
    }

    /**
     * 解除全部设备的配对（POST api/pairing/unpair）。
     *
     * **必须带配对密码**。该接口原先免密码，而「移除单台」需要密码，
     * 于是破坏性最强的操作反而是门槛最低的一条；core 已同步收紧，这里跟上传参。
     * 成功后自己的 token 也一起失效 —— 立刻回配对引导，别等下一次请求撞 444。
     */
    fun unpairAll(password: String, onResult: (String?) -> Unit = {}) {
        scope.launch {
            try {
                api.unpairAll(mapOf("password" to password))
                emitWriteNotice("已解除全部设备的配对", subtitle = "所有客户端需要重新配对")
                // 已解除全部 = 本机也不再是配对设备：**当场**走吊销收尾（清 token、回配对页）。
                // 原先只 loadPairingStatus()，于是紧接着那次请求自己吃 444、
                // 界面弹一条"加载配对状态失败"，还要等 3 次 / ≥10s 的阈值才踢 ——
                // 用户在这十几秒里看着"成功"与"失败"并列，停在一个已失效的会话上。
                RetrofitClient.onUnauthorized?.invoke()
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

    // ── 配对设备管理（配对密码认证） ──

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

    /** 移除已配对设备（DELETE /api/pairing/devices/{fp}，只需 Bearer）。
     * 被删设备的 token 立即失效 → 下一次请求 444 由 RetrofitClient 引导重新配对（预期行为）。 */
    fun removeDevice(fingerprint: String) {
        scope.launch {
            try {
                api.removePairedDevice(fingerprint)
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

    // ── 配对设备黑名单 ──
    //
    // 读侧拦截不在这里：被拉黑设备的配对/密码登录由 core 的 PairingManager.confirm 直接拦
    // （403 DEVICE_BLOCKED），已鉴权请求由 444 拦。这里只是管理面（增删查）。

    /**
     * 加载黑名单（GET /api/pairing/blacklist）。
     *
     * 旧版 core 缺少该端点 → 404。**这不是错误**：把 [PairingState.blacklistAvailable]
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
     * 拉黑一台设备（POST /api/pairing/blacklist，只需 Bearer）。
     *
     * @param hwId 已配对记录里的硬件标识（辅助键，可空）。一起记下来才能挡住
     *   "清 App 数据换密钥后重新配对"这条路 —— 那是纯指纹拦截唯一的绕过口。
     */
    fun blockPairingDevice(
        fingerprint: String,
        name: String,
        hwId: String = "",
        reason: String = ""
    ) {
        scope.launch {
            try {
                val body = mutableMapOf<String, Any>(
                    "fingerprint" to fingerprint,
                    "name" to name,
                    "reason" to reason
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

    /** 解除拉黑（DELETE /api/pairing/blacklist/{fp}，只需 Bearer）。 */
    fun unblockPairingDevice(fingerprint: String) {
        scope.launch {
            try {
                api.unblockPairedDevice(fingerprint)
                loadBlacklist()
                emitWriteNotice("已解除拉黑")
            } catch (e: Exception) {
                DebugLog.e("Network", "unblockDevice failed", e)
                _pairingState.update { it.copy(errorMessage = "解除拉黑失败: ${e.message}") }
            }
        }
    }

    /** 全部解除（POST /api/pairing/blacklist/clear，只需 Bearer）。 */
    fun clearBlacklist() {
        scope.launch {
            try {
                api.clearPairingBlacklist()
                loadBlacklist()
                emitWriteNotice("已全部解除拉黑")
            } catch (e: Exception) {
                DebugLog.e("Network", "clearBlacklist failed", e)
                _pairingState.update { it.copy(errorMessage = "全部解除失败: ${e.message}") }
            }
        }
    }

    // ── 新设备批准 ──

    /**
     * 待批准列表的「在途 / 最近一次」标记。
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
     * 推送触发的拉取：在途合并 + 1 秒内不重复。
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
     * 带结果回调。失败时仅写 `pairingState.errorMessage`，
     * 而那份状态**只有「设置 → 安全中心」页在渲染**；根部的批准弹窗点完就消失，
     * 机主可能误认为已批准，而实际为 404（请求已过期）或网络失败，必须将结果交回调用方提示。
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
            // 拉黑时一并带上（辅助键）。旧 core 不返回该字段 → 空串，
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
 * 解析待批准列表（`GET /api/pairing/approvals` 的 `approvals`）。
 *
 * `code` 是弹窗要大字显示的 6 位配对码 —— 它只在这个**已鉴权**的接口里出现，
 * 请求方自己的 `request-approval` 响应里永远没有它。
 *
 * **已批准 / 已拒绝的条目直接丢弃**。服务端已经过滤过一次，这里再滤一遍是防御性的 ——
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

// ── 重启后端服务的恢复探测参数（见 NetworkModule.restartBackendService）──
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
