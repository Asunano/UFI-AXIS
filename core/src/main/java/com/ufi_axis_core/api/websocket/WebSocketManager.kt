package com.ufi_axis_core.api.websocket

import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket 连接管理器
 *
 * 端点: WS /ws/realtime
 *
 * 认证:
 * - 通过 HTTP Header `Authorization: Bearer xxx` 进行认证
 * - 认证失败则会立即关闭连接
 * 订阅格式:
 * { "subscribe": ["signal", "cpu", "traffic", "battery", "alert", "memory"] }
 *
 * 推送格式:
 * { "type": "signal", "data": { "rsrp": -85, "sinr": 15, "rat": "5G" }, "timestamp": 12345 }
 * { "type": "traffic", "data": { "rx_speed": 1048576, "tx_speed": 524288 }, "timestamp": 12345 }
 */
class WebSocketManager(
    private val expectedToken: String? = null
) {

    private val tag = "WebSocketManager"
    private val json = Json { ignoreUnknownKeys = true }

    // 所有活跃连接: session -> subscribed types
    private val connections = ConcurrentHashMap<WebSocketSession, MutableSet<String>>()
    private val maxConnections = 5  // 低端设备限制最大 WebSocket 连接数

    // 广播序列化缓存：同类型 100ms 内复用预序列化的 JSON 文本，减少临时对象分配
    private val broadcastCache = ConcurrentHashMap<String, Pair<String, Long>>()

    // 按订阅类型分组的连接索引，避免广播时遍历所有连接检查订阅类型
    private val subscriptions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()

    // 心跳 Job
    private var heartbeatJob: Job? = null

    private companion object {
        private const val BROADCAST_CACHE_TTL_MS = 100L
        private const val HEARTBEAT_INTERVAL_MS = 30_000L  // 30 秒心跳
    }

    /**
     * 启动周期性心跳检测，30 秒 ping 所有连接。
     * 发送失败或 session 已不活跃的连接会被立即移除。
     */
    fun startHeartbeat(scope: CoroutineScope) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val ping = buildJsonObject { put("ping", JsonPrimitive(1)) }
                val text = json.encodeToString(JsonElement.serializer(), ping)
                connections.keys.forEach { session ->
                    try {
                        session.send(Frame.Text(text))
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) {
                        connections.remove(session)
                        subscriptions.values.forEach { it.remove(session) }
                    }
                }
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * 处理新的 WebSocket 连接
     */
    suspend fun handleConnection(session: DefaultWebSocketServerSession) {
        // 认证检查: 通过 Authorization Header 验证 token（避免 token 出现在 URL 日志中）
        if (expectedToken != null) {
            val bearer = session.call.request.headers["Authorization"]
            val requestToken = bearer?.removePrefix("Bearer ")?.trim()
            if (requestToken != expectedToken) {
                AppLogger.w(tag, "WebSocket auth failed: invalid token")
                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication failed"))
                return
            }
            AppLogger.i(tag, "WebSocket auth success via Authorization header")
        }

        val subscribedTypes = mutableSetOf<String>()
        val existing = connections.putIfAbsent(session, subscribedTypes)
        if (existing != null) {
            AppLogger.w(tag, "WebSocket session already registered, skipping")
            return
        }
        if (connections.size > maxConnections) {
            AppLogger.w(tag, "WebSocket connection rejected: max connections ($maxConnections) exceeded")
            connections.remove(session)
            session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many connections"))
            return
        }

        AppLogger.i(tag, "New WebSocket connection. Total: ${connections.size}")

        try {
            // 发送欢迎消息（包含 data 字段以匹配客户端模型）
            session.send(Frame.Text(json.encodeToString(
                JsonElement.serializer(),
                buildJsonObject {
                    put("type", JsonPrimitive("connected"))
                    put("data", buildJsonObject {
                        put("message", JsonPrimitive("UFI-AXIS-Core WebSocket connected"))
                    })
                    put("timestamp", JsonPrimitive(System.currentTimeMillis()))
                }
            )))

            // 接收消息
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> handleTextMessage(session, frame.readText(), subscribedTypes)
                    is Frame.Close -> {
                        AppLogger.i(tag, "WebSocket close received")
                        break
                    }
                    else -> {}
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            AppLogger.i(tag, "WebSocket connection closed")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            AppLogger.e(tag, "WebSocket error", e)
        } finally {
            connections.remove(session)
            // 从订阅索引中移除该 session 的所有订阅
            subscriptions.values.forEach { it.remove(session) }
            AppLogger.i(tag, "WebSocket connection removed. Total: ${connections.size}")
        }
    }

    /**
     * 处理订阅消息和 ping（suspend 函数以支持发送 pong 响应）
     */
    private suspend fun handleTextMessage(
        session: WebSocketSession,
        text: String,
        subscribedTypes: MutableSet<String>
    ) {
        try {
            val jsonElement = json.parseToJsonElement(text)
            val jsonObject = jsonElement.jsonObject

            // 处理订阅请求
            jsonObject["subscribe"]?.jsonArray?.let { types ->
                // 从订阅索引中移除旧的订阅
                subscribedTypes.forEach { subscriptions[it]?.remove(session) }
                subscribedTypes.clear()
                types.forEach {
                    val type = it.jsonPrimitive.content
                    subscribedTypes.add(type)
                    subscriptions.getOrPut(type) { ConcurrentHashMap.newKeySet() }.add(session)
                }
                AppLogger.i(tag, "Client subscribed to: $subscribedTypes")
            }

            // 处理取消订阅
            jsonObject["unsubscribe"]?.jsonArray?.let { types ->
                types.forEach {
                    val type = it.jsonPrimitive.content
                    subscribedTypes.remove(type)
                    subscriptions[type]?.remove(session)
                }
                AppLogger.i(tag, "Client unsubscribed. Now: $subscribedTypes")
            }

            // 处理 ping — 现在可以正常发送 pong 响应
            if (jsonObject.containsKey("ping")) {
                val pong = json.encodeToString(JsonElement.serializer(), buildJsonObject {
                    put("type", JsonPrimitive("pong"))
                    put("data", buildJsonObject {})
                    put("timestamp", JsonPrimitive(System.currentTimeMillis()))
                })
                session.send(Frame.Text(pong))
            }

        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            AppLogger.e(tag, "Failed to parse WebSocket message: $text", e)
        }
    }

    /**
     * 广播数据变更通知 — 当配置/设备信息等发生变化时，通知前端刷新对应模块。
     * 订阅类型: "data_changed"
     * 推送: { type: "data_changed", data: { changed: "wifi|device|sim|network|lan|..." } }
     */
    suspend fun broadcastDataChanged(changedType: String) {
        if (subscriptions["data_changed"]?.isEmpty() != false) return
        broadcast("data_changed", mapOf("changed" to changedType))
    }

    /**
     * 向所有订阅了指定类型的客户端广播数据
     */
    suspend fun broadcast(type: String, data: Map<String, Any>) {
        // 广播前先清理已不活跃的连接（TCP 未关闭但客户端已失效的场景）
        connections.keys.removeAll { session ->
            if (!session.isActive) {
                subscriptions.values.forEach { it.remove(session) }
                true
            } else false
        }

        val subscribers = subscriptions[type] ?: return
        if (subscribers.isEmpty()) return

        val now = System.currentTimeMillis()

        // 100ms 内同类型广播复用上次序列化结果
        val text = broadcastCache[type]?.let { (cachedText, ts) ->
            if (now - ts < BROADCAST_CACHE_TTL_MS) cachedText else null
        } ?: run {
            val message = buildJsonObject {
                put("type", JsonPrimitive(type))
                put("data", buildJsonObject {
                    data.forEach { (key, value) ->
                        val jv = toJsonValue(value)
                        put(key, jv)
                    }
                })
                put("timestamp", JsonPrimitive(now))
            }
            json.encodeToString(JsonElement.serializer(), message).also {
                broadcastCache[type] = Pair(it, now)
            }
        }

        // 预编码为 ByteArray，多个客户端共享同一份字节数据
        val frameBytes = text.toByteArray()
        val dead = mutableListOf<WebSocketSession>()

        subscribers.forEach { session ->
            try {
                session.send(Frame.Text(true, frameBytes))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) {
                    dead.add(session)
                }
        }

        // 清理已断开的连接和订阅索引
        dead.forEach { session ->
            subscriptions[type]?.remove(session)
            connections.remove(session)
        }
    }

    /**
     * 获取当前连接数
     */
    fun getConnectionCount(): Int = connections.size

    /**
     * 关闭所有连接
     */
    suspend fun closeAll() {
        connections.keys.forEach { session ->
            try {
                session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down"))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {}
        }
        connections.clear()
        subscriptions.clear()
    }

    /**
     * 将任意值正确转为 JsonElement，保留数字/布尔类型
     */
    private fun toJsonValue(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonPrimitive -> value  // 直接透传，保持原始类型
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is List<*> -> JsonArray(value.map { toJsonValue(it) })
            is Map<*, *> -> buildJsonObject {
                value.forEach { (k, v) -> put(k.toString(), toJsonValue(v)) }
            }
            else -> JsonPrimitive(value.toString())
        }
    }
}
