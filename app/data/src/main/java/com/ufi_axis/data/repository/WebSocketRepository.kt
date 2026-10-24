package com.ufi_axis.data.repository

import com.ufi_axis.data.model.SubscriptionRequest
import com.ufi_axis.data.model.WebSocketMessage
import com.ufi_axis_core.contract.WsChannel
import com.ufi_axis_core.contract.WsDataTopic  // 2026-10-05 P5：RECONNECTED sentinel
import com.ufi_axis.util.ApiErrorLogger
import com.ufi_axis.util.AppJson
import com.ufi_axis.util.DebugLog
import com.ufi_axis.util.DeviceKeyStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

class WebSocketRepository(
    private var baseUrl: String,
    private var token: String
) {
    private var webSocket: WebSocket? = null
    // 2026-10-05 R3-12 修复：OkHttpClient 进程级单例，Activity 重建不再新建 client
    // （连接池 / Dispatcher 线程池 / WS ping 调度器不随组合重建而泄漏堆积）
    private val client = SHARED_CLIENT

    // 增加 buffer 容量 + DROP_OLDEST 策略，防止下游处理慢时 emit 挂起
    // extraBufferCapacity=64 可缓存约 64 条消息，DROP_OLDEST 丢弃最早消息避免 OOM
    private val _messages = MutableSharedFlow<WebSocketMessage>(
        replay = 1, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<WebSocketMessage> = _messages

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _cachedData = ConcurrentHashMap<String, WebSocketMessage>()
    val cachedData: Map<String, WebSocketMessage> get() = _cachedData

    /** data_changed 事件流 — 精准增量刷新的数据源 */
    private val _dataChanged = MutableSharedFlow<String>(replay = 0)
    val dataChanged: SharedFlow<String> = _dataChanged

    private var retryCount = 0
    // 2026-10-05 P5（修 G1）：首连/重连判据 —— 重连成功的 onOpen 才补发 RECONNECTED sentinel
    private var everConnected = false
    private var retryJob: Job? = null
    private var scope: CoroutineScope? = null

    /**
     * 用户主动断开标志（2026-10-05 R3-1 修复）。
     * disconnect() 后 OkHttp 的 onClosed 回调仍会触发，原实现无条件 scheduleReconnect
     * → 用户刚点了断开，WS 又自己连回去了。置位后回调直接放弃重连；connect() 时清零。
     */
    @Volatile
    private var userDisconnected = false

    /**
     * WS 握手被拒（重试用尽后）时的回调（2026-10-21 加，2026-10-22 修正语义）。
     *
     * ⚠ **它不是"凭据被吊销"的信号**。`1008` 只说明这一次握手没通过，而它最常见的原因是
     * 冷启动时 `clockOffsetMs` 还没被 `/health` 校准（见 [handleAuthRejection]）。
     * 上层只应据此**提示 + 暂停实时通道**；"清凭据回配对引导"的唯一判据始终是
     * HTTP 444 的连击阈值（`RetrofitClient` 里的 `AuthRejectionPolicy`）——
     * 阈值只允许有一份，否则一次启动竞态就能把用户踢回配对页。
     */
    @Volatile
    var onAuthRejected: (() -> Unit)? = null

    /**
     * 上一次连接是否因**鉴权被拒**而终止（重试用尽之后）。
     *
     * 这时不再自动重连（见 [handleAuthRejection]），回前台也跳过重连；
     * 只有 [updateConfig]（重新配对 / 改地址带来新凭据）才清零恢复。
     */
    @Volatile
    private var authRejected = false

    /** 还剩几次"被拒后重连"的机会（给 /health 校时留时间，2026-10-22）。 */
    private var authRetriesLeft = 0

    /** 当前订阅频道（重连后按此列表重新订阅）。 */
    private var subscribedTopics: List<String> = DEFAULT_TOPICS

    // ── F12：网络/前台恢复主动重连 ──
    private var hasConnectedOnce = false
    private var connectivityManager: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // 网络恢复且未处于已连接/连接中时，立即主动重连（跳过退避窗口）
            if (hasConnectedOnce &&
                _connectionState.value != ConnectionState.CONNECTED &&
                _connectionState.value != ConnectionState.CONNECTING
            ) {
                reconnect()
            }
        }
    }

    fun connect(scope: CoroutineScope, topics: List<String> = subscribedTopics) {
        this.scope = scope
        this.subscribedTopics = topics
        hasConnectedOnce = true
        userDisconnected = false // 2026-10-05 R3-1：重新 connect 即恢复自动重连资格
        authRejected = false     // 每次显式连接都是一次新尝试
        authRetriesLeft = AUTH_RETRY_MAX
        _connectionState.value = ConnectionState.CONNECTING
        openSocket()
    }

    /**
     * 真正发起握手（2026-10-22 从 [connect] 拆出）。
     *
     * 拆出来的原因：鉴权被拒后的**内部重试**不能再走 [connect] —— 那会把重试计数重置，
     * 于是设备时钟一直没校准的情况下会变成无限重试。重试只调本方法。
     */
    private fun openSocket() {
        // 拆出来之后 `scope` 变成可空字段（原来它是 connect 的非空参数），这里取一次非空副本，
        // 下面监听器里的 `scope.launch` / `scheduleReconnect(scope)` 才不需要到处判空。
        val scope = this.scope ?: return
        // 握手鉴权全部走 query（token/ts/nonce/sig）：core 的 WebSocketManager 只读 query，
        // 因为浏览器 WebSocket API 不能自定义请求头，两端保持唯一一条代码路径。
        // 签名对象只含 path（不含 query）—— 见 WebSocketManager 类文档的理由。
        val path = "/ws/realtime"
        // 2026-10-21（审计 §A5）：时间戳必须带上与 core 的观测偏差。
        // 之前这里是裸 `System.currentTimeMillis()`，而 HTTP 一路早就用 `clockOffsetMs` 补偿；
        // 设备时钟漂移 >5min 时表现成"接口全正常、实时通道永久 1008 无限重连"，极难自查。
        val timestamp = com.ufi_axis.data.api.RetrofitClient.signedTimestampMs().toString()
        val nonce = DeviceKeyStore.newNonce()
        val signature = DeviceKeyStore.sign(
            DeviceKeyStore.canonicalString("GET", path, timestamp, nonce)
        ).orEmpty()
        // 2026-10-21（审计 §B12）：签不出来（Keystore 不可用）就别连 —— 带着空签名连上去
        // 必然 1008，然后无限重连空转，还占 core 的 MAX_CONNECTIONS=4 名额。
        if (signature.isEmpty()) {
            DebugLog.e("WS", "设备签名不可用（Keystore），跳过 WS 连接")
            authRejected = true
            _connectionState.value = ConnectionState.DISCONNECTED
            onAuthRejected?.invoke()
            return
        }
        val url = "$baseUrl$path" +
            "?token=${java.net.URLEncoder.encode(token, "UTF-8")}" +
            "&ts=$timestamp" +
            "&nonce=${java.net.URLEncoder.encode(nonce, "UTF-8")}" +
            "&sig=${java.net.URLEncoder.encode(signature, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLog.d("WS", "Connected to $baseUrl/ws/realtime")
                _connectionState.value = ConnectionState.CONNECTED
                retryCount = 0
                subscribeToTopics(subscribedTopics)
                // 2026-09-12：WS（重）连后主动补一次控制台历史对齐。
                // 连上期间另一端的命令已通过 data_changed 实时同步；但若本次连接是「断线后重连」，
                // 断连窗口内另一端敲的命令不会推过来，不补拉就会显示成「两端历史不一致」。
                // 复用既有 data_changed → MainViewModel → smartRefresh 链路（console:at / console:shell 已接好）。
                scope.launch {
                    _dataChanged.emit("console:at")
                    _dataChanged.emit("console:shell")
                    // 2026-10-05 P5（修 G1）：断线窗口的 device/network/wifi/media 事件已丢
                    //（replay=0，重连只补 console），重连补发 sentinel，各 Module 按各自
                    // 新鲜度策略补拉。首连不发（页面 LaunchedEffect 与预加载协调器负责），
                    // 只有重连才发。
                    if (everConnected) _dataChanged.emit(WsDataTopic.RECONNECTED)
                    everConnected = true
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 2026-08-31 掉帧治理①：日志用 lambda 重载，关掉日志时不再为每条消息
                // 白做一次 text.take(500) 的字符串拷贝（推送约每 3s 一条，长期看是纯 GC 压力）。
                DebugLog.d("WS") { "Message received: ${text.take(500)}" }
                // 2026-08-31 掉帧治理②：解析放到 Default 线程。
                // 调用方传进来的 scope 是 viewModelScope（Dispatchers.Main.immediate），
                // 原来整条 WS 报文的 JSON 反序列化就发生在**主线程**上；正好落在页面转场
                // 那几百毫秒里时会直接吃掉帧预算。SharedFlow.emit 与下游的 MutableStateFlow
                // 都是线程安全的，Compose 也只要求「状态被读时在主线程」，故无需切回主线程。
                scope.launch(Dispatchers.Default) {
                    try {
                        val message = AppJson.decodeFromString<WebSocketMessage>(text)
                        _cachedData[message.type] = message
                        _messages.emit(message)
                        // 解析 data_changed 事件 → 精准增量刷新
                        if (message.type == "data_changed") {
                            val changed = (message.data as? JsonObject)?.get("changed")?.jsonPrimitive?.content
                            if (changed != null) {
                                DebugLog.d("WS", "Data changed event: $changed")
                                _dataChanged.emit(changed)
                            }
                        }
                        DebugLog.d("WS", "Parsed message type=${message.type}")
                    } catch (e: Exception) {
                        DebugLog.parseError("WS", "ws://realtime", text, e)
                        ApiErrorLogger.logParse(
                            baseUrl = "$baseUrl/ws/realtime",
                            url = "ws://realtime",
                            exception = e,
                            responseBody = text
                        )
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                _connectionState.value = ConnectionState.DISCONNECTED
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
                // 2026-10-21（审计 §B12）：1008 = core 拒绝了这次握手（凭据失效 / 被拉黑）。
                // 这种重连一万次也不会成功，只会空转、还占住 core 的 MAX_CONNECTIONS 名额。
                if (handleAuthRejection(code, reason)) return
                // 2026-10-05 R3-1 修复：用户主动断开时不再自动重连
                if (!userDisconnected) scheduleReconnect(scope)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.DISCONNECTED
                // 落盘详细通信报错（诊断「服务无法链接」）：错误类型归类 + 1 行 DebugLog 摘要由 ApiErrorLogger 内部处理。
                val errorType = when (t) {
                    is java.net.ConnectException -> "CONNECT_FAILED"
                    is java.net.SocketTimeoutException -> "TIMEOUT"
                    is java.net.UnknownHostException -> "UNKNOWN_HOST"
                    is javax.net.ssl.SSLException -> "SSL_ERROR"
                    is java.io.IOException -> "IO_ERROR"
                    else -> t.javaClass.simpleName.uppercase()
                }
                ApiErrorLogger.logWs(
                    baseUrl = "$baseUrl/ws/realtime",
                    errorType = errorType,
                    exception = t,
                    requestLine = "WS $baseUrl/ws/realtime Authorization: Bearer ***"
                )
                // 2026-10-05 R3-1 修复：用户主动断开后 close 引发的 onFailure 也不重连
                // 2026-10-21（审计 §B12）：HTTP 层直接拒（401/403/444）同样算鉴权失败，不重连。
                if (handleAuthRejection(response?.code ?: 0, response?.message.orEmpty())) return
                if (!userDisconnected) scheduleReconnect(scope)
            }
        })
    }

    /**
     * 判定"这次断开是鉴权被拒"（2026-10-21，审计 §B12）。
     *
     * core 的 WS 鉴权失败一律 `close(1008)`；OkHttp 侧也可能在 HTTP 握手阶段就拿到
     * 401/403/444。两者都意味着这次握手没通过，**但不等价于"凭据被吊销"** ——
     * 冷启动最常见的原因恰恰是"设备时钟偏移还没测出来"：
     * `MainViewModel.init` 里 `dashboard.init()`（连 WS）跑在 `health.startPeriodicCheck`
     * 之前，此时签名用的偏差还是 0；设备时钟漂移 >5min 时这次握手必然 StaleTimestamp → 1008。
     *
     * 所以这里的处理是：**先重试几次**（给 /health 一个往返的时间），仍然失败才停掉自动重连
     * 并回调 [onAuthRejected]（上层只提示，不再清凭据 —— 见 [onAuthRejected] 的注释）。
     *
     * @return true = 已接管（调用方不要再走普通重连逻辑）
     */
    private fun handleAuthRejection(code: Int, reason: String): Boolean {
        val rejected = code == 1008 || code == 401 || code == 403 || code == 444 ||
            reason.contains("Authentication failed", ignoreCase = true)
        if (!rejected) return false
        // 2026-10-22：先给"时钟尚未校准"留一次机会
        if (authRetriesLeft > 0) {
            authRetriesLeft--
            DebugLog.w(
                "WS",
                "WS 握手被拒（code=$code）：${AUTH_RETRY_DELAY_MS}ms 后重试（可能是时钟尚未校准），" +
                    "剩余重试 $authRetriesLeft"
            )
            val s = scope
            if (s != null) {
                s.launch {
                    delay(AUTH_RETRY_DELAY_MS)
                    if (!userDisconnected) openSocket()
                }
                return true
            }
        }
        authRejected = true
        DebugLog.e("WS", "WS 握手被拒（code=$code, reason=$reason）：停止自动重连")
        onAuthRejected?.invoke()
        return true
    }

    private fun subscribeToTopics(topics: List<String>) {
        val request = SubscriptionRequest(subscribe = topics)
        val json = AppJson.encodeToString<SubscriptionRequest>(request)
        val sent = webSocket?.send(json) ?: false
        if (!sent) {
            DebugLog.w("WS", "Failed to subscribe to topics: $topics")
        }
    }

    private fun scheduleReconnect(scope: CoroutineScope) {
        // 已经判定"握手被拒且重试用尽"时不再自动重连：等用户回前台 / 网络恢复 / 换凭据
        //（那几条路都会显式 reconnect，顺便重置计数）。避免真正的凭据失效时无限空转。
        if (authRejected) return
        // F12：移除 maxRetry=10 上限，改为无限重连；退避指数增长并钳制到 30s，
        // 用 retryCount.coerceAtMost(5) 防止 1 shl retryCount 整型溢出。
        _connectionState.value = ConnectionState.RECONNECTING
        retryJob = scope.launch {
            val attempt = retryCount.coerceAtMost(5)
            val delayMs = ((1 shl attempt).toLong() * 1000).coerceAtMost(30000)
            delay(delayMs)
            retryCount++
            connect(scope)
        }
    }

    fun reconnect() {
        retryJob?.cancel()
        retryCount = 0
        val currentScope = scope ?: return
        webSocket?.close(1000, "Reconnecting")
        webSocket = null
        connect(currentScope)
    }

    /**
     * 更新连接参数（IP/端口/Token）并立即重连。
     *
     * 用于服务端配置变更（或重新配对）后刷新实时通道，避免 WebSocket 停留在旧地址/旧凭据。
     * 注意：调用方应传入与当前 [com.ufi_axis.util.AppPreferences] 一致的 url/token；
     * 本实例通常由 [MainViewModel] 长期持有，故更新的是同一实例的实时连接。
     */
    fun updateConfig(baseUrl: String, token: String) {
        this.baseUrl = baseUrl
        this.token = token
        reconnect()
    }

    fun disconnect() {
        // 2026-10-05 R3-1 修复：先置位再关闭，onClosed/onFailure 回调据此放弃重连
        userDisconnected = true
        retryJob?.cancel()
        webSocket?.close(1000, "Client disconnect")
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * F12：绑定网络恢复监听。网络可用且当前未连接/未连接中时主动重连，跳过退避等待。
     * 须在拥有 Context 处（如 MainActivity）调用一次；解绑见 [unbindNetworkRecovery]。
     */
    fun bindNetworkRecovery(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            DebugLog.w("WS", "bindNetworkRecovery: register failed: ${e.message}")
        }
    }

    fun unbindNetworkRecovery() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            DebugLog.w("WS", "unbindNetworkRecovery: unregister failed: ${e.message}")
        }
        connectivityManager = null
    }

    /** F12：App 回到前台时主动重连（由 MainActivity 生命周期调用），仅在确已断开时触发。 */
    fun onAppForegrounded() {
        // 2026-10-22：不再因为 authRejected 而跳过 —— 那会让"设备启动慢、校时也没成功"这种
        // 一次性失败把实时通道永久钉死，只能靠重新配对恢复。回前台是用户主动行为，
        // 重连一次（内部仍带"被拒后重试 2 次"的封顶）不会造成风暴。
        if (_connectionState.value == ConnectionState.DISCONNECTED ||
            _connectionState.value == ConnectionState.RECONNECTING
        ) {
            reconnect()
        }
    }

    fun send(message: String) {
        webSocket?.send(message)
    }

    companion object {
        /**
         * 鉴权被拒后的重试次数与间隔（2026-10-22）。
         *
         * 只用来跨过"冷启动时时钟偏差还没测出来"那一个窗口：`/health` 在
         * `MainViewModel.init` 里紧跟着 `dashboard.init()` 启动，局域网往返几十毫秒，
         * 1.2 秒足够它跑完两轮。**不**用它掩盖真正的凭据失效 —— 两次都失败就停。
         */
        private const val AUTH_RETRY_MAX = 2
        private const val AUTH_RETRY_DELAY_MS = 1_200L

        // 2026-10-05 R3-12 修复：OkHttpClient 进程级单例，Activity 重建不再新建 client
        // （连接池 / Dispatcher 线程池 / WS ping 调度器不随组合重建而泄漏堆积）
        val SHARED_CLIENT: OkHttpClient = OkHttpClient.Builder()
            // 应用层心跳：NAT/移动网络静默断链时 OkHttp 会在约 20s 内触发 onFailure → 走重连退避，
            // 否则只能等下一次写失败才发现连接已死（后台进程可能几分钟都收不到推送）。
            .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        /**
         * UI 进程默认订阅频道 = 契约的 [WsChannel.UI_TOPICS] **加上** `notification`。
         *
         * 为什么必须加回 `notification`（2026-09-14）：短信 / 验证码这两类推送 core 只广播到
         * `notification` 一个频道（`PushChannel.SINGLE_TOPIC_SCENES`，不镜像到 `alert`），
         * 而订阅了 `notification` 的只有 `:ufi_notify`，那个进程又只在「前台服务保活」开启后
         * 才建 WS（`NotifyService.onStartCommand` 的闸门在 `startBackgroundChannelsIfNeeded` 之前）。
         * 于是保活关着时短信 / 验证码 / 下载完成的系统通知**一条都发不出来**，而邮件由 core 自己
         * 发、完全正常 —— 用户看到的就是「开关全开却只有邮件」。主进程订上这个频道后，
         * 业务类推送经 `NotificationCenter.notify` 的进程门转交给 `:ufi_notify` 发射
         * （广播会自动拉起那个进程），不再依赖保活。
         *
         * 不会造成告警双处理：core 对告警是 `notification` + `alert` 双发，而
         * `DashboardModule` 的 `notification` 分支**只认业务类** type（sms / verification /
         * download / tunnel），告警形状的载荷一概忽略，仍由 `alert` 分支独家处理。
         *
         * 注意：core 侧按订阅索引广播（`WebSocketManager.broadcast` 无订阅即静默丢弃），
         * 且采集循环会在 `getConnectionCount() > 0` 时从 60s 提速到 3s —— 订阅越多、连接越久，
         * 设备端采集负载越高，因此后台进程应改用 [NOTIFY_TOPICS]。
         */
        val DEFAULT_TOPICS = WsChannel.UI_TOPICS + WsChannel.NOTIFICATION

        /**
         * 通知守护进程（`:ufi_notify`）专用频道：只要告警推送（见 [WsChannel.NOTIFY_TOPICS]）。
         *
         * core 的 `WebSocketPushService` 对同一 payload 先发 `notification` 再发 `alert`（`type != "connected"` 时），
         * 两者都订阅是为了兼容 —— 去重由 `NotificationCenter` 的游标 + 签名 TTL 负责。
         */
        val NOTIFY_TOPICS = WsChannel.NOTIFY_TOPICS
    }
}

enum class ConnectionState {
    CONNECTED,
    DISCONNECTED,
    CONNECTING,
    RECONNECTING
}
