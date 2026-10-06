package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.terminal.TtydManager
import com.ufi_axis_core.api.terminal.TtydTicketStore
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.util.AppSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpStatusCode
import io.ktor.http.path
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 真 PTY 终端（ttyd 反代，2026-10-06）。
 *
 * ## 端点
 * - `POST /api/terminal/pty-ticket` — 签发 PTY 票据（走标准头部鉴权）。
 *   **默认 403**（`ttyd_enabled` 开关，AppSettings），与 goform_dump_enabled 同一防御模型：
 *   开关本身就是安全边界，默认关。签发时按需把 ttyd 拉起来（第一次打开终端页才 spawn）。
 * - `GET /api/terminal/status` — 开关与进程状态回读。
 * - `GET /ws/terminal?ticket=...` — WS 反代（挂在 `/api` 之外：浏览器原生 WebSocket
 *   **发不了自定义头**，与 /ws/realtime 同因同果，凭票代替鉴权；票据来自上面的签发端点，
 *   本身已证明调用方过了完整的头部鉴权）。
 *
 * ## 反代协议
 * ttyd 的 WS 帧是 JSON 数组指令流（`["0", ...]` 握手 / `["1", data]` 输出 / `["2", data]`
 * 输入，见 ttyd 源码 html/src/protocol.ts）。core **不理解也不改写**帧语义，只做字节搬运：
 * client ↔ ttyd 各一个协程双向泵。理由：协议翻译层一旦写错，排障要跨三端；而
 * 「透传 + 在反代层做鉴权与关闭」已经满足全部安全目标 —— ttyd 只绑 127.0.0.1，
 * 外部流量必须先过票据这道闸。
 *
 * ## 关闭语义
 * - 客户端断开 → 反代关掉与 ttyd 的 WS（shell 会话在 ttyd 侧保留，同票重连可续）；
 * - 票据失效 → 1008（复用 WebSocketManager 的语义：凭据问题，重连无意义，该重新拿票）；
 * - ttyd 起不来 → 1013 TRY_AGAIN_LATER（可重试）。
 */
class TtydRoutes(
    private val settings: AppSettings,
    private val manager: TtydManager,
    private val ticketStore: TtydTicketStore = TtydTicketStore(),
    /**
     * WS 握手鉴权器（与 /ws/realtime 的 WebSocketManager 同一实现、同一注入源）。
     * `/ws/` 前缀在 AuthMiddleware.isPublic 里被整体豁免 —— 那条豁免的本意就是
     * 「WS 靠 query 验签」，所以反代路由**必须**自己做这道验签，否则 /ws/terminal
     * 会成为整条鉴权链上唯一不设防的入口。
     */
    private val wsAuthenticator: com.ufi_axis_core.api.websocket.WsAuthenticator? = null,
) {
    private val http = HttpClient(CIO) {
        // ttyd 在本机回环，超时给小 —— 挂了就快速失败，别让页面转圈
        engine { requestTimeout = 5_000 }
    }

    fun register(root: Route) {
        // 参数名用 root 而非 route：后者会遮蔽 io.ktor.server.routing.route 扩展函数，
        // `route("/terminal")` 会被解析成对 Route 对象的调用（踩过一次）。
        root.route("/terminal") {
            post("/pty-ticket") {
                if (!settings.ttydEnabled) {
                    call.respondFail(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, "真 PTY 终端未开启（设置中手动打开）")
                    return@post
                }
                val port = try {
                    manager.ensureStarted()
                } catch (e: TtydManager.TtydStartException) {
                    call.respondFail(HttpStatusCode.InternalServerError, ErrorCode.INTERNAL_ERROR, e.message ?: "ttyd 启动失败")
                    return@post
                }
                // body 可选：带 session_id 就沿用（页面重连时保持同一逻辑会话标识）
                val bodyText = runCatching { call.receiveText() }.getOrDefault("")
                val sessionId = runCatching {
                    Json.parseToJsonElement(bodyText).jsonObject["session_id"]?.jsonPrimitive?.content
                }.getOrNull()?.take(64)
                    ?: "s-" + System.currentTimeMillis().toString(36)
                val ticket = ticketStore.issue(sessionId)
                call.respond(toJsonElement(mapOf(
                    "ticket" to ticket,
                    "session_id" to sessionId,
                    "port" to port,
                    "expires_in" to ticketStore.ttlSeconds,
                )))
            }

            get("/status") {
                call.respond(toJsonElement(mapOf(
                    "enabled" to settings.ttydEnabled,
                    // running=true = ttyd 正在设备后台常驻（退出页面不杀，直到 core 停止或手动停）
                    "running" to manager.isRunning,
                )))
            }

            // 手动停掉后台常驻的 ttyd（开关保持开着，只是杀进程；下次连接会再 spawn）
            post("/stop") {
                if (!settings.ttydEnabled) {
                    call.respond(HttpStatusCode.Forbidden, "真 PTY 未开启")
                    return@post
                }
                manager.stop()
                call.respond(toJsonElement(mapOf("stopped" to true)))
            }
        }
    }

    /** WS 反代主循环（HttpServer 的 `webSocket("/ws/terminal")` 调进来）。 */
    suspend fun proxy(clientSession: DefaultWebSocketServerSession) {
        // ── 第一道闸：WS 握手 query 验签（与 /ws/realtime 完全同参同名，web 端零新增）──
        if (wsAuthenticator != null) {
            val q = clientSession.call.request.queryParameters
            val fp = wsAuthenticator.authenticate(
                token = q["token"],
                timestamp = q["ts"],
                nonce = q["nonce"],
                signature = q["sig"],
                path = clientSession.call.request.path(),
            )
            if (fp == null) {
                clientSession.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication failed"))
                return
            }
        }
        // ── 第二道闸：PTY 票据（票据签发端点本身已过完整头部鉴权 + 开关）──
        val sessionId = ticketStore.resolve(clientSession.call.request.queryParameters["ticket"])
        if (sessionId == null) {
            clientSession.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "terminal ticket invalid or expired"))
            return
        }
        if (!settings.ttydEnabled) {
            clientSession.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "terminal disabled"))
            return
        }
        val port = try {
            manager.ensureStarted()
        } catch (e: TtydManager.TtydStartException) {
            clientSession.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, e.message ?: "ttyd unavailable"))
            return
        }

        val ttyd = try {
            http.webSocketSession("ws://127.0.0.1:$port/")
        } catch (e: Exception) {
            clientSession.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "ttyd connect failed: ${e.message}"))
            return
        }

        try {
            coroutineScope {
                // ttyd → client（终端输出）
                launch {
                    try {
                        for (frame in ttyd.incoming) {
                            if (frame is Frame.Close) break
                            clientSession.send(frame)
                        }
                    } catch (_: Exception) { /* 对端已关：走 finally 清理 */ }
                    runCatching { clientSession.close() }
                }
                // client → ttyd（键盘输入）
                launch {
                    try {
                        for (frame in clientSession.incoming) {
                            if (frame is Frame.Close) break
                            ttyd.send(frame)
                        }
                    } catch (_: Exception) { /* 对端已关 */ }
                    runCatching { ttyd.close() }
                }
            }
        } finally {
            runCatching { ttyd.close() }
            runCatching { clientSession.close() }
        }
    }

    /** 服务停止钩子（ComponentFactory.stop 时调）。 */
    fun stop() {
        manager.stop()
        http.close()
    }
}
