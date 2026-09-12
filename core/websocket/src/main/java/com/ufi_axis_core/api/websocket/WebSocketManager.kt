package com.ufi_axis_core.api.websocket

import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * WebSocket 连接管理器
 *
 * 端点: WS /ws/realtime
 *
 * ## 认证（2026-08-28 起：设备签名，与 /api 同一套密钥）
 * 浏览器的 WebSocket API 不能自定义请求头，所以握手参数全部走 query：
 * ```
 * /ws/realtime?token=<设备token>&ts=<毫秒时间戳>&nonce=<随机串>&sig=<签名>
 * ```
 * 签名对象是 `DeviceAuth.canonicalString("GET", <path>, ts, nonce)`——**只含 path，不含 query**。
 * 原因很直接：签名没法覆盖自己，把 query 纳入就必须约定"去掉 sig 之后再拼"，
 * 两端各实现一遍这种剪裁规则是典型的踩坑点；而 token/ts/nonce 本身都被服务端独立校验，
 * 签名只需要绑定 ts+nonce 就已经把「持有私钥」和「非重放」两件事证明完了。
 *
 * App 侧同样走 query（而不是 Authorization 头），保证两端唯一一条代码路径。
 *
 * 鉴权失败 → 1008 VIOLATED_POLICY；连接数超限 → 1013 TRY_AGAIN_LATER。
 * 这两个码是 web 端 `stores/websocket.ts` 判「不要重连」与「稍后重试」的依据，不要改。
 *
 * 订阅格式:
 * { "subscribe": ["signal", "cpu", "traffic", "alert", "memory"] }
 *
 * 推送格式:
 * { "type": "signal", "data": { "rsrp": -85, "sinr": 15, "rat": "5G" }, "timestamp": 12345 }
 * { "type": "traffic", "data": { "rx_speed": 1048576, "tx_speed": 524288 }, "timestamp": 12345 }
 */
class WebSocketManager(
    /**
     * 握手鉴权器：返回设备指纹表示通过，返回 null 表示拒绝。
     *
     * 以函数注入而非直接依赖 `PairedDeviceStore`，让 `:core:websocket` 不必知道配对存储的存在，
     * 也让本类可在纯 JVM 测试里注入确定性实现。传 null = 不鉴权（仅测试用）。
     *
     * **没有默认值是故意的**：`/ws/` 在 `AuthMiddleware.isPublic` 里被整体豁免，鉴权完全靠
     * 这里的握手检查；服务又绑在 0.0.0.0。一旦给它一个 `= null` 默认值，漏传就等于把
     * 实时通道（signal / traffic / cpu / alert）无鉴权暴露到局域网，且不会有任何编译或运行报错。
     */
    private val authenticator: WsAuthenticator?
) {

    private val tag = "WebSocketManager"
    private val json = Json { ignoreUnknownKeys = true }

    // ── 可观测指标 / 数据新鲜度（F26 解析失败计数、F27 stale 标记）──
    private val parseFailures = AtomicLong(0)
    private val lastMessageTime = AtomicLong(0)
    /** 所有 WS 客户端断开后，手机端缓存视为可能过期（stale） */
    private val stale = AtomicBoolean(false)
    /** 全部连接断开回调：供上层（如 ResponseCache）标记 stale */
    var onConnectionsEmpty: (() -> Unit)? = null
    /** 新连接建立回调：供上层清除 stale 标记 */
    var onConnectionRestored: (() -> Unit)? = null
    /**
     * "update" 主题快照提供者：客户端订阅 "update" 时，服务端立即把当前更新结果（UpdateManager.statusToMap()）
     * 作为快照下发给该连接。这是「手动推送 APK 后客户端收到更新成功推送」的可靠通道——
     * 因安装脚本会重启 Core（旧连接已断），客户端重连后只要重新订阅 "update" 即可得到真实终态。
     */
    var updateSnapshotProvider: (suspend () -> Map<String, Any?>?)? = null

    // 所有活跃连接: session -> subscribed types
    private val connections = ConcurrentHashMap<WebSocketSession, MutableSet<String>>()
    private val maxConnections = com.ufi_axis_core.contract.WsChannel.MAX_CONNECTIONS  // 支持 App + Web 多客户端同时连接（契约常量，双端共用）

    // 广播序列化缓存：同类型 500ms 内复用预序列化的 JSON 文本
    private val broadcastCache = ConcurrentHashMap<String, Pair<String, Long>>()

    // 按订阅类型分组的连接索引，避免广播时遍历所有连接检查订阅类型
    private val subscriptions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()

    private companion object {
        private const val BROADCAST_CACHE_TTL_MS = 500L
        /** 单个客户端 send 的上限；超时只丢帧，不摘连接（见 [broadcast]）。 */
        private const val SEND_TIMEOUT_MS = 3000L
    }

    // 心跳由 Ktor WebSocket 插件自动管理（pingPeriod=15s, timeout=30s），
    // 协议层 Frame.Ping/Pong 比自定义 JSON 心跳更高效（2字节 vs ~30字节 JSON）。

    /**
     * 处理新的 WebSocket 连接
     */
    suspend fun handleConnection(session: DefaultWebSocketServerSession) {
        // 握手鉴权：token + ts + nonce + sig 全部从 query 取（浏览器 WS 不支持自定义头）
        var fingerprint: String? = null
        if (authenticator != null) {
            val q = session.call.request.queryParameters
            fingerprint = authenticator.authenticate(
                token = q["token"],
                timestamp = q["ts"],
                nonce = q["nonce"],
                signature = q["sig"],
                path = session.call.request.path()
            )
            if (fingerprint == null) {
                AppLogger.w(tag, "WebSocket 鉴权失败 from ${session.call.request.local.remoteAddress}")
                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication failed"))
                return
            }
            AppLogger.i(tag, "WebSocket 鉴权通过 fp=$fingerprint")
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

        AppLogger.i(tag, "New WebSocket connection${fingerprint?.let { " fp=$it" } ?: ""}. Total: ${connections.size}")
        // 新连接建立：数据通道恢复，清除 stale 标记并通知上层
        stale.set(false)
        onConnectionRestored?.invoke()

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
                    is Frame.Text -> {
                        lastMessageTime.set(System.currentTimeMillis())
                        handleTextMessage(session, frame.readText(), subscribedTypes)
                    }
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
            // 全部客户端断开 → 手机端缓存视为可能过期（F27）
            if (connections.isEmpty()) {
                stale.set(true)
                onConnectionsEmpty?.invoke()
            }
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
                    // computeIfAbsent 而非 getOrPut：后者是 get-then-put 两步，不是原子操作。
                    // 两个客户端并发订阅同一 type 时会各自建一个 Set，其中一个被覆盖，
                    // 那个客户端从此静默收不到该 type 的任何广播。
                    subscriptions.computeIfAbsent(type) { ConcurrentHashMap.newKeySet() }.add(session)
                }
                AppLogger.i(tag, "Client subscribed to: $subscribedTypes")
            }

            // 新订阅 update 主题 → 立即下发当前更新状态快照（解决手动推送 APK 重启后客户端未收到结果）
            if (subscribedTypes.contains("update")) {
                val snap = runCatching { updateSnapshotProvider?.invoke() }.getOrNull()
                if (snap != null) {
                    runCatching {
                        session.send(Frame.Text(json.encodeToString(
                            JsonElement.serializer(),
                            buildJsonObject {
                                put("type", JsonPrimitive("update"))
                                put("data", buildJsonObject {
                                    snap.forEach { (k, v) -> put(k, toJsonValue(v)) }
                                })
                                put("timestamp", JsonPrimitive(System.currentTimeMillis()))
                            }
                        )))
                    }
                }
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

        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            // F26：累计解析失败次数（通过 /health 或日志暴露，便于观测客户端协议异常）
            parseFailures.incrementAndGet()
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
    suspend fun broadcast(type: String, data: Map<String, Any?>) {
        // 死连接清理由 Ktor 协议层 ping/pong 统一处理，无需每次广播遍历检查
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
                // 每个 send 单独限时：`send` 在背压下会挂起，一个卡住的客户端
                // （半开 TCP、息屏的手机）会把整轮广播和调用方的 DataScheduler 循环一起拖住。
                // 超时只丢这一帧、不摘连接 —— 连接活性交给 Ktor 协议层的 ping/pong 判定，
                // 免得一次瞬时慢发就把正常客户端踢下线。
                val sent = kotlinx.coroutines.withTimeoutOrNull(SEND_TIMEOUT_MS) {
                    session.send(Frame.Text(true, frameBytes))
                }
                if (sent == null) {
                    AppLogger.w(tag, "broadcast[$type] send timeout (${SEND_TIMEOUT_MS}ms), frame dropped for one slow client")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) {
                    dead.add(session)
                }
        }

        // 清理已断开的连接：统一走 dropSession
        dead.forEach { dropSession(it) }
    }

    /**
     * 摘掉一条**发送已经抛异常**的连接。
     *
     * 两件事都必须做：
     * - 遍历 [subscriptions] 的**全部** topic 移除它。原来只从 `subscriptions[type]`
     *   移除当前这一个 topic，它在别的 topic 的订阅集合里还留着 —— 后续每个 topic 的广播
     *   都会再对同一条死连接抛一次异常，日志刷满、每轮广播白白多几次超时。
     * - 主动 `close()`。不关的话对端已经没了，但它的 [handleConnection] 还挂在
     *   `for (frame in session.incoming)` 上，`connections` 里那一项永远不会被清掉。
     *
     * **不在这里动 [connections]，也不在这里判 stale**：这两件事由 [handleConnection] 的
     * finally 单一负责。这里提前 `connections.remove(session)` 的话，另一条连接断开时
     * 它的 finally 会看到一个被提前减小的 `connections` —— 明明还有连接在，却误判
     * `connections.isEmpty()` 并置 stale（前端显示「数据可能过期」）。
     * close() 之后死连接自己的 finally 会跑，账由它来记。
     */
    private suspend fun dropSession(session: WebSocketSession) {
        subscriptions.values.forEach { it.remove(session) }
        try {
            session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Broadcast send failed"))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            AppLogger.w(tag, "死连接 close 失败（已从全部订阅索引摘除）：${e.message}")
        }
    }

    /**
     * 获取当前连接数
     */
    fun getConnectionCount(): Int = connections.size

    /**
     * WS 层可观测指标（F26 解析失败计数 / F27 stale 标记）。
     * 通过 /health 或日志暴露，便于观测客户端协议异常与数据新鲜度。
     */
    fun getStats(): Map<String, Any> = mapOf(
        "connections" to connections.size,
        "parse_failures" to parseFailures.get(),
        "last_message_time" to lastMessageTime.get(),
        "stale" to stale.get()
    )

    fun getParseFailureCount(): Long = parseFailures.get()
    fun isStale(): Boolean = stale.get()
    fun markStale() { stale.set(true) }

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
        // 服务端主动关闭全部连接 → 手机端缓存视为可能过期（F27）
        stale.set(true)
        onConnectionsEmpty?.invoke()
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
