// TODO(F25): God-class 计划内拆分（DownloadManager 1094 / GoformClient 615 / Aria2Engine 577 / BackendService 570 / NetworkModule 921 / FileManagerScreen 879）。本类仅做最小安全抽取（见 GoformCodec），全量拆分需人工评审 + 编译验证。
package com.ufi_axis.viewmodel.module

import android.content.Context
import android.os.SystemClock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.ModeRequest
import com.ufi_axis.data.model.WifiAclResponse
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

    private val _pairingState = MutableStateFlow(PairingState())
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private var refreshJob: Job? = null

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

    // ── Network ──
    fun refreshNetwork() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            _networkState.value = _networkState.value.copy(isLoading = true, errorMessage = null)
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
                    errorMessage = failures
                )
            } catch (e: Exception) {
                if (e is CancellationException) {
                    _networkState.value = _networkState.value.copy(isLoading = false)
                    throw e
                }
                DebugLog.e("Network", "refreshNetwork failed", e)
                _networkState.value = _networkState.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}")
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
     */
    fun toggleMobileData(enabled: Boolean) {
        scope.launch {
            val previous = _networkState.value.mobileDataEnabled
            _networkState.update {
                it.copy(mobileDataEnabled = enabled, mobileDataPending = true, errorMessage = null)
            }
            try {
                api.setMobileData(mapOf("enabled" to enabled))
                waitForNetworkReady(expectConnected = enabled)
                refreshNetwork()
                _networkState.update { it.copy(mobileDataPending = false) }
            } catch (e: Exception) {
                _networkState.update {
                    it.copy(
                        mobileDataEnabled = previous, mobileDataPending = false,
                        errorMessage = "操作失败: ${e.message}", isLoading = false
                    )
                }
            }
        }
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

    fun toggleAirplaneMode(enabled: Boolean) {        scope.launch {
            try {
                val resp = api.setAirplaneMode(mapOf("enabled" to enabled))
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "操作失败")
                // 开飞行模式 = 等断开，关飞行模式 = 等连上（同 toggleMobileData 的坑②）
                waitForNetworkReady(expectConnected = !enabled); refreshNetwork()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "操作失败: ${e.message}") }
        }
    }

    fun setNetworkMode(mode: String) {
        scope.launch {
            try {
                val resp = api.setNetworkMode(ModeRequest(mode))
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "设置失败")
                waitForNetworkReady(); refreshNetwork()
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "设置失败: ${e.message}") }
        }
    }

    /** 统一锁定 LTE+NR 频段（goform + AT+SFUN 网络栈重启，无需设备重启） */
    fun lockBands(lteBands: String?, nrBands: String?) {
        scope.launch {
            try {
                val resp = if (lteBands == null && nrBands == null) {
                    api.setBandLock(buildMap { put("action", "unlock") })
                } else {
                    api.setBandLock(buildMap {
                        if (!lteBands.isNullOrBlank()) put("lte_bands", lteBands)
                        if (!nrBands.isNullOrBlank()) put("nr_bands", nrBands)
                        if (lteBands.isNullOrBlank() && nrBands.isNullOrBlank()) put("action", "unlock")
                    })
                }
                if (!resp.success) {
                    _networkState.value = _networkState.value.copy(errorMessage = "锁频失败")
                } else {
                    waitForNetworkReady()
                    loadBandStatus()
                }
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "锁频失败: ${e.message}") }
        }
    }

    fun loadBandStatus() {
        scope.launch {
            try {
                _networkState.update { it.copy(bandStatus = api.getBandStatus(), loadVersion = System.currentTimeMillis()) }
            } catch (e: Exception) {
                _networkState.update { it.copy(errorMessage = "频段状态加载失败: ${e.message}") }
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

    fun setConnectionMode(mode: String) {
        scope.launch {
            try {
                val resp = api.setConnectionMode(mapOf("mode" to mode))
                if (!resp.success) _networkState.value = _networkState.value.copy(errorMessage = "设置失败")
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
    fun loadCellInfo() {
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
                    errorMessage = rCell.exceptionOrNull()?.let { e -> "基站信息查询失败: ${e.message}" }
                        ?: it.errorMessage,
                    loadVersion = System.currentTimeMillis()
                ) }
            }
            catch (e: Exception) { _networkState.update { it.copy(errorMessage = "基站信息查询失败: ${e.message}") } }
        }
    }

    /**
     * 锁基站。networkType 只接受 core 契约值域 "LTE" / "NR"（core 会自行映射成设备侧 12/16），
     * 不要再传设备数字码或旧入参名 rat（core 仍兼容但已打 WARN，下一版会删）。
     */
    fun cellLock(pci: String, earfcn: String, networkType: String) {
        scope.launch {
            try { api.cellLock(mapOf("pci" to pci, "earfcn" to earfcn, "network_type" to networkType)); delay(500); loadCellInfo() }
            catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "锁基站失败: ${e.message}") }
        }
    }

    fun unlockAllCell() {
        scope.launch {
            try { api.unlockAllCell(); delay(500); loadCellInfo() }
            catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "解锁基站失败: ${e.message}") }
        }
    }

    // ── LAN ──
    fun loadLanSettings() {
        scope.launch {
            _networkState.update { it.copy(errorMessage = null, loadVersion = System.currentTimeMillis()) }
            try { _networkState.update { it.copy(lanSettings = api.getLanSettings(), loadVersion = System.currentTimeMillis()) } }
            catch (e: Exception) { _networkState.update { it.copy(errorMessage = "LAN设置查询失败: ${e.message}") } }
        }
    }

    fun setDhcpSetting(lanIp: String, lanNetmask: String, dhcpType: String, dhcpStart: String, dhcpEnd: String, dhcpLease: String) {
        scope.launch {
            try {
                val resp = api.setDhcpSetting(mapOf("lan_ip" to lanIp, "lan_netmask" to lanNetmask, "dhcp_type" to dhcpType, "dhcp_start" to dhcpStart, "dhcp_end" to dhcpEnd, "dhcp_lease" to dhcpLease))
                if (resp.success) { delay(500); loadLanSettings() }
                else _networkState.value = _networkState.value.copy(errorMessage = "DHCP设置失败")
            } catch (e: Exception) { _networkState.value = _networkState.value.copy(errorMessage = "DHCP设置失败: ${e.message}") }
        }
    }

    // ── Service Control（2026-08-26）──
    // 停/启的是后端"后台采集服务"（core 的 DataScheduler），HTTP 服务始终在跑，
    // 所以停止之后仍然能远程再启动。"重启服务"才是进程级完全重启。

    fun loadServiceStatus() {
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
            } catch (e: Exception) {
                // restarting 一并清掉：否则重启后若服务还没起来，徽标会永久卡在"重启中"
                _serviceState.value = _serviceState.value.copy(
                    restarting = false,
                    errorMessage = "服务状态读取失败: ${e.message}"
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
                loadServiceStatus()
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
                loadServiceStatus()
            }
        }
    }

    /** 完全重启后端服务：HTTP 会中断约 10 秒，期间所有请求都会失败，这是预期行为。 */
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
            // 服务由 AlarmManager 在约 10s 后拉起（core 的 RESTART_DELAY_MS）；留 3s 余量再回读，
            // 顺带把 restarting 标记清掉（loadServiceStatus 成功时会置 false）
            delay(13_000)
            loadServiceStatus()
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

    fun changePassword(oldPwd: String, newPwd: String) {
        scope.launch {
            try { api.changePassword(mapOf("old_password" to oldPwd, "new_password" to newPwd)) }
            catch (e: Exception) { emitDashboardError("修改密码失败: ${e.message}") }
        }
    }

    // ── WiFi Config ──
    fun setWifiConfig(config: Map<String, Any>) {
        scope.launch {
            try { api.setWifiConfig(config) }
            catch (e: Exception) { emitDashboardError("WiFi设置失败: ${e.message}") }
        }
    }

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
        scope.launch {
            val previous = _networkState.value.wifiEnabled
            _networkState.update { it.copy(wifiEnabled = enabled) }
            try {
                api.setWifiEnabled(mapOf("enabled" to enabled))
                // 写后回读：开关状态刚变，必须绕过新鲜度闸门
                refreshWifi(force = true)
            } catch (e: Exception) {
                _networkState.update {
                    it.copy(wifiEnabled = previous, errorMessage = "WiFi开关失败: ${e.message}")
                }
            }
        }
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
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "FOTA设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "FOTA设置失败: ${e.message}") }
        }
    }

    fun setPerformanceMode(mode: String) {
        scope.launch {
            try {
                val resp = api.setPerformanceMode(mapOf("mode" to mode))
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "性能模式设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "性能模式设置失败: ${e.message}") }
        }
    }

    fun setLedEnabled(enabled: Boolean) {
        scope.launch {
            try {
                val resp = api.setLedEnabled(mapOf("enabled" to enabled))
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "指示灯设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "指示灯设置失败: ${e.message}") }
        }
    }

    fun setRoamingEnabled(enabled: Boolean) {
        scope.launch {
            try {
                val resp = api.setRoamingEnabled(mapOf("enabled" to enabled))
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "漫游设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "漫游设置失败: ${e.message}") }
        }
    }

    fun setWifiSleep(time: String) {
        scope.launch {
            try {
                val resp = api.setWifiSleep(mapOf("time" to time))
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "WiFi休眠设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "WiFi休眠设置失败: ${e.message}") }
        }
    }

    fun setSambaSetting(enabled: Boolean) {
        scope.launch {
            try {
                val resp = api.setSambaSetting(mapOf("enabled" to enabled))
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "Samba设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "Samba设置失败: ${e.message}") }
        }
    }

    fun setRestartSchedule(enabled: Boolean, time: String) {
        scope.launch {
            try {
                val resp = api.setRestartSchedule(mapOf("enabled" to enabled, "time" to time))
                if (!resp.success) _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "定时重启设置失败")
                else { delay(500); loadDeviceSettings() }
            } catch (e: Exception) { _deviceSettingsState.value = _deviceSettingsState.value.copy(errorMessage = "定时重启设置失败: ${e.message}") }
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
        when {
            changedType == "network:band-status" || changedType == "network:cell-info" -> refreshNetworkLight()
            changedType.startsWith("wifi:") -> refreshWifiOnly()
            changedType == "device:lan" -> loadLanSettings()
            changedType == "device:settings" -> loadDeviceSettings()
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
                    devices = parsePairedDevices(json["devices"]),
                    isLoading = false
                )
            } catch (e: Exception) {
                DebugLog.e("Network", "loadPairingStatus failed", e)
                _pairingState.value = _pairingState.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}")
            }
        }
    }

    fun updatePairingConfig(enabled: Boolean, maxDevices: Int) {
        scope.launch {
            try {
                api.updatePairingConfig(mapOf(
                    "pairing_enabled" to enabled,
                    "pairing_max_devices" to maxDevices
                ))
                loadPairingStatus()
            } catch (e: Exception) {
                DebugLog.e("Network", "updatePairingConfig failed", e)
                _pairingState.value = _pairingState.value.copy(errorMessage = "保存失败: ${e.message}")
            }
        }
    }

    fun unpairAll() {
        scope.launch {
            try {
                api.unpairAll()
                loadPairingStatus()
            } catch (e: Exception) {
                DebugLog.e("Network", "unpairAll failed", e)
                _pairingState.value = _pairingState.value.copy(errorMessage = "解除配对失败: ${e.message}")
            }
        }
    }

    fun unpairFingerprint(fingerprint: String) {
        scope.launch {
            try {
                api.unpairFingerprint(fingerprint)
                loadPairingStatus()
            } catch (e: Exception) {
                DebugLog.e("Network", "unpairFingerprint failed", e)
                _pairingState.value = _pairingState.value.copy(errorMessage = "移除设备失败: ${e.message}")
            }
        }
    }

    // ── 配对设备管理（阶段3：2026-08-11，设备密码认证） ──

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
            } catch (e: Exception) {
                DebugLog.e("Network", "renameDevice failed", e)
                _pairingState.update { it.copy(errorMessage = "重命名失败: ${e.message}") }
            }
        }
    }

    /** 移除已配对设备（DELETE /api/pairing/devices/{fp}，需设备密码）。
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
                }
            } catch (e: Exception) {
                DebugLog.e("Network", "removeDevice failed", e)
                _pairingState.update { it.copy(errorMessage = "移除设备失败: ${e.message}") }
            }
        }
    }

    /** 修改设备密码（POST pairing/change-password，root 路径；旧密码即门禁）。 */
    fun changeDevicePassword(oldPw: String, newPw: String) {
        scope.launch {
            try {
                api.changeDevicePassword(mapOf("old_password" to oldPw, "new_password" to newPw))
                loadPairingStatus()
            } catch (e: Exception) {
                DebugLog.e("Network", "changeDevicePassword failed", e)
                _pairingState.update { it.copy(errorMessage = "修改设备密码失败: ${e.message}") }
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
            createdAt = obj.safeGetString("created_at")?.toLongOrNull() ?: 0L
        )
    }
}

// ── 安全 JSON 取值辅助（kotlinx.serialization.json API） ──

/** 安全地从 JsonObject 取字符串字段 */
private fun JsonObject.safeGetString(key: String): String? {
    return try {
        val el = this[key] ?: return null
        if (el is JsonPrimitive) el.content else null
    } catch (e: Exception) { DebugLog.w("Network", "safeGetString failed for key=$key", e); null }
}
