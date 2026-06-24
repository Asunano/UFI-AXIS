package com.ufi_axis_core.api.websocket

import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket 连接管理器
 *
 * 端点: WS /ws/realtime
 *
 * 认证:
 * - 通过 URL 查询参数 ?token=xxx 或首条消息进行认证
 *
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

    /**
     * 处理新的 WebSocket 连接
     */
    suspend fun handleConnection(session: DefaultWebSocketServerSession) {
        // 认证检查: 通过 URL 查询参数验证 token
        if (expectedToken != null) {
            val requestToken = session.call.request.queryParameters["token"]
            if (requestToken != expectedToken) {
                AppLogger.w(tag, "WebSocket auth failed: invalid token")
                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication failed"))
                return
            }
        }

        val subscribedTypes = mutableSetOf<String>()
        // 原子地检查连接上限并注册（防止竞态导致连接数超过 maxConnections）
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
                subscribedTypes.clear()
                types.forEach { subscribedTypes.add(it.jsonPrimitive.content) }
                AppLogger.i(tag, "Client subscribed to: $subscribedTypes")
            }

            // 处理取消订阅
            jsonObject["unsubscribe"]?.jsonArray?.let { types ->
                types.forEach { subscribedTypes.remove(it.jsonPrimitive.content) }
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
        if (connections.isEmpty()) return
        broadcast("data_changed", mapOf("changed" to changedType))
    }

    /**
     * 向所有订阅了指定类型的客户端广播数据
     */
    suspend fun broadcast(type: String, data: Map<String, Any>) {
        if (connections.isEmpty()) return

        val message = buildJsonObject {
            put("type", JsonPrimitive(type))
            put("data", buildJsonObject {
                data.forEach { (key, value) ->
                    val jv = toJsonValue(value)
                    put(key, jv)
                }
            })
            put("timestamp", JsonPrimitive(System.currentTimeMillis()))
        }

        val text = json.encodeToString(JsonElement.serializer(), message)
        val deadSessions = mutableListOf<WebSocketSession>()

        connections.forEach { (session, subscribedTypes) ->
            if (type in subscribedTypes) {
                try {
                    session.send(Frame.Text(text))
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        deadSessions.add(session)
                    }
            }
        }

        // 清理已断开的连接
        deadSessions.forEach { connections.remove(it) }
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
