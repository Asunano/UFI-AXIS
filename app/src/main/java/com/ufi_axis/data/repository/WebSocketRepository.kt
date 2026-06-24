package com.ufi_axis.data.repository

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.ufi_axis.data.model.SubscriptionRequest
import com.ufi_axis.data.model.WebSocketMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import com.ufi_axis.util.DebugLog
import java.util.concurrent.TimeUnit

class WebSocketRepository(
    private val baseUrl: String,
    private val token: String
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private val _messages = MutableSharedFlow<WebSocketMessage>(replay = 1)
    val messages: SharedFlow<WebSocketMessage> = _messages

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _cachedData = MutableStateFlow<Map<String, WebSocketMessage>>(emptyMap())
    val cachedData: StateFlow<Map<String, WebSocketMessage>> = _cachedData

    /** data_changed 事件流 — 精准增量刷新的数据源 */
    private val _dataChanged = MutableSharedFlow<String>(replay = 0)
    val dataChanged: SharedFlow<String> = _dataChanged

    private var retryCount = 0
    private val maxRetryCount = 10
    private var retryJob: Job? = null
    private var scope: CoroutineScope? = null

    fun connect(scope: CoroutineScope) {
        this.scope = scope
        _connectionState.value = ConnectionState.CONNECTING

        val request = Request.Builder()
            .url("$baseUrl/ws/realtime?token=$token")
            .addHeader("Authorization", "Bearer $token")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLog.d("WS", "Connected to $baseUrl/ws/realtime")
                _connectionState.value = ConnectionState.CONNECTED
                retryCount = 0
                subscribeToTopics(listOf("cpu", "memory", "traffic", "signal", "battery", "alert", "data_changed", "sms_contacts"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                DebugLog.d("WS", "Message received: ${text.take(500)}")
                scope.launch {
                    try {
                        val message = gson.fromJson(text, WebSocketMessage::class.java)
                        _cachedData.value = _cachedData.value + (message.type to message)
                        _messages.emit(message)
                        // 解析 data_changed 事件 → 精准增量刷新
                        if (message.type == "data_changed") {
                            val changed = message.data?.asJsonObject?.get("changed")?.asString
                            if (changed != null) {
                                DebugLog.d("WS", "Data changed event: $changed")
                                _dataChanged.emit(changed)
                            }
                        }
                        DebugLog.d("WS", "Parsed message type=${message.type}")
                    } catch (e: JsonSyntaxException) {
                        DebugLog.parseError("WS", "ws://realtime", text, e)
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
                scheduleReconnect(scope)
            }
        })
    }

    private fun subscribeToTopics(topics: List<String>) {
        val request = SubscriptionRequest(subscribe = topics)
        val json = gson.toJson(request)
        webSocket?.send(json)
    }

    private fun scheduleReconnect(scope: CoroutineScope) {
        if (retryCount < maxRetryCount) {
            retryJob = scope.launch {
                val delayMs = (1 shl retryCount).toLong() * 1000
                delay(delayMs.coerceAtMost(30000))
                retryCount++
                connect(scope)
            }
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

    fun disconnect() {
        retryJob?.cancel()
        webSocket?.close(1000, "Client disconnect")
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun send(message: String) {
        webSocket?.send(message)
    }
}

enum class ConnectionState {
    CONNECTED,
    DISCONNECTED,
    CONNECTING
}
