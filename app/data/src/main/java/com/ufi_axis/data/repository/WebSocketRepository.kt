package com.ufi_axis.data.repository

import com.ufi_axis.data.model.SubscriptionRequest
import com.ufi_axis.data.model.WebSocketMessage
import com.ufi_axis_core.contract.WsChannel
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
    private val client = OkHttpClient.Builder()
        // 应用层心跳：NAT/移动网络静默断链时 OkHttp 会在约 20s 内触发 onFailure → 走重连退避，
        // 否则只能等下一次写失败才发现连接已死（后台进程可能几分钟都收不到推送）。
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

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
    private var retryJob: Job? = null
    private var scope: CoroutineScope? = null

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
        _connectionState.value = ConnectionState.CONNECTING

        // 握手鉴权全部走 query（token/ts/nonce/sig）：core 的 WebSocketManager 只读 query，
        // 因为浏览器 WebSocket API 不能自定义请求头，两端保持唯一一条代码路径。
        // 签名对象只含 path（不含 query）—— 见 WebSocketManager 类文档的理由。
        val path = "/ws/realtime"
        val timestamp = System.currentTimeMillis().toString()
        val nonce = DeviceKeyStore.newNonce()
        val signature = DeviceKeyStore.sign(
            DeviceKeyStore.canonicalString("GET", path, timestamp, nonce)
        ).orEmpty()
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
                scheduleReconnect(scope)
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
                scheduleReconnect(scope)
            }
        })
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
         * UI 进程默认订阅频道 —— 直接用契约的 [WsChannel.UI_TOPICS]（= core 广播全集去掉
         * `notification`，因为 core 对同一告警会 `notification` + `alert` 双发，UI 只认 `alert`）。
         *
         * 注意：core 侧按订阅索引广播（`WebSocketManager.broadcast` 无订阅即静默丢弃），
         * 且采集循环会在 `getConnectionCount() > 0` 时从 60s 提速到 3s —— 订阅越多、连接越久，
         * 设备端采集负载越高，因此后台进程应改用 [NOTIFY_TOPICS]。
         */
        val DEFAULT_TOPICS = WsChannel.UI_TOPICS

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
