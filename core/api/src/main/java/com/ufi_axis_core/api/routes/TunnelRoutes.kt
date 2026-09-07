package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.controller.system.TunnelManager
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * 内网穿透 API 路由（**多实例**：FRP 通道与 CF 隧道都可以同时跑多条，两个引擎互不影响）
 *
 * 端点：
 * - GET  /api/tunnel/status        — 全部实例状态（instances 数组，不含日志正文）
 * - POST /api/tunnel/stop          — 停止两个引擎的全部实例
 * - POST /api/tunnel/logs/clear    — 清空全部实例的日志缓冲
 * - GET  /api/tunnel/frp/configs   — 列出所有 FRP 通道（含结构化 items）
 * - GET/PUT/DELETE /api/tunnel/frp/config/{name} — 读取/保存/删除单个通道
 * - POST /api/tunnel/frp/config/{name}/activate  — 设为选中通道（仅 UI 记忆）
 * - POST /api/tunnel/frp/config/{name}/start     — 启动该通道
 * - POST /api/tunnel/frp/config/{name}/stop      — 停止该通道
 * - GET  /api/tunnel/frp/config/{name}/log       — 该通道的运行日志
 * - POST /api/tunnel/frp/config/{name}/log/clear — 清空该通道日志
 * - POST /api/tunnel/frp/stop      — 停止全部 FRP 通道
 * - GET  /api/tunnel/cf/tunnels    — 列出所有 CF 隧道（含 items，不含 token）
 * - GET/PUT/DELETE /api/tunnel/cf/tunnel/{name}  — 读取（含 token）/保存/删除单条隧道
 * - POST /api/tunnel/cf/tunnel/{name}/activate   — 设为选中隧道
 * - POST /api/tunnel/cf/tunnel/{name}/start      — 启动该隧道
 * - POST /api/tunnel/cf/tunnel/{name}/stop       — 停止该隧道
 * - GET  /api/tunnel/cf/tunnel/{name}/log        — 该隧道的运行日志
 * - POST /api/tunnel/cf/tunnel/{name}/log/clear  — 清空该隧道日志
 * - POST /api/tunnel/cf/stop       — 停止全部 CF 隧道
 */
class TunnelRoutes(private val tunnelManager: TunnelManager) {

    fun register(route: Route) {
        route.route("/tunnel") {

            // ── 状态 ──
            get("/status") {
                // probeVersion 首次会 fork 进程并 waitFor 最长 5s，不能压在事件循环上
                val status = withContext(Dispatchers.IO) { tunnelManager.getStatus() }
                call.respond(toJsonElement(status))
            }

            // ── 停止全部实例（两个引擎）──
            post("/stop") {
                // success 必须反映"进程真的停了"：强杀失败时引擎会返回 false
                val ok = withContext(Dispatchers.IO) { tunnelManager.stopAll() }
                call.respond(toJsonElement(mapOf(
                    "success" to ok,
                    "message" to if (ok) "All tunnels stopped" else "部分隧道进程强制停止失败，可能仍在运行"
                )))
            }

            /** 清空全部实例的日志缓冲（日志在设备端进程内存里，客户端删不掉，只能由后端清） */
            post("/logs/clear") {
                tunnelManager.frpEngine.clearAllOutput()
                tunnelManager.cfEngine.clearAllOutput()
                call.respond(toJsonElement(mapOf("success" to true)))
            }

            // ── 看护设置（断开自动重连 / 巡检间隔 / 失败提醒）──

            /** 读取看护设置（含期望在跑的实例列表，UI 可据此解释"它为什么自己起来了"） */
            get("/settings") {
                call.respond(toJsonElement(withContext(Dispatchers.IO) { tunnelManager.guardSettings() }))
            }

            /** 更新看护设置（只改传进来的字段），返回更新后的全量设置 */
            put("/settings") {
                val body = call.receiveJsonObject()
                // 用 as? JsonPrimitive 而不是 .jsonPrimitive：对象/数组值会让后者抛异常 → 兜底成 500
                val autoReconnect = (body["auto_reconnect"] as? JsonPrimitive)?.booleanOrNull
                val notifyOnFailure = (body["notify_on_failure"] as? JsonPrimitive)?.booleanOrNull
                val interval = (body["reconnect_interval_sec"] as? JsonPrimitive)?.intOrNull
                // 字段传了却解析不出来必须报错：静默忽略再回 success:true 就是假成功
                val bad = when {
                    body.containsKey("auto_reconnect") && autoReconnect == null ->
                        "auto_reconnect must be a boolean"
                    body.containsKey("notify_on_failure") && notifyOnFailure == null ->
                        "notify_on_failure must be a boolean"
                    body.containsKey("reconnect_interval_sec") && interval == null ->
                        "reconnect_interval_sec must be an integer"
                    interval != null && interval !in 10..120 ->
                        "reconnect_interval_sec must be within 10..120"
                    else -> null
                }
                if (bad != null) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, bad)
                    return@put
                }
                val updated = withContext(Dispatchers.IO) {
                    tunnelManager.updateGuardSettings(
                        autoReconnect = autoReconnect,
                        reconnectIntervalSec = interval,
                        notifyOnFailure = notifyOnFailure
                    )
                }
                call.respond(toJsonElement(mapOf("success" to true, "settings" to updated)))
            }

            // ── FRP ──
            route("/frp") {
                /** 列出所有通道 */
                get("/configs") {
                    // activeFrpConfig() 会自愈并写 prefs，属于磁盘 IO
                    val list = withContext(Dispatchers.IO) { tunnelManager.listFrpConfigs() }
                    call.respond(toJsonElement(list))
                }

                /** 读取某个通道 TOML 文本 */
                get("/config/{name}") {
                    val name = call.parameters["name"] ?: ""
                    val toml = withContext(Dispatchers.IO) { tunnelManager.readFrpConfig(name) }
                    if (toml != null) {
                        call.respond(toJsonElement(mapOf("name" to name, "toml" to toml)))
                    } else {
                        call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "config not found")
                    }
                }

                /** 保存某个通道（TOML 文本）；名字为空或含非法字符 → 400（不静默改名，避免覆盖同名文件） */
                put("/config/{name}") {
                    val name = call.parameters["name"] ?: ""
                    if (name.isBlank()) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "name is required")
                        return@put
                    }
                    val body = call.receiveJsonObject()
                    // toml 缺失/为 null/空白 → 400。
                    // 2026-08-26 修：原来是 `?: ""`，客户端漏传 toml 会把通道配置**静默写成空文件**
                    // 且返回 success=true（对齐同文件 cf/tunnel 对空 token 的处理方式）。
                    val toml = body["toml"]?.jsonPrimitive?.contentOrNull
                    if (toml.isNullOrBlank()) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "toml is required")
                        return@put
                    }
                    val ok = withContext(Dispatchers.IO) { tunnelManager.saveFrpConfigFile(name, toml) }
                    if (!ok) {
                        call.respondFail(
                            HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                            "invalid config name or save failed"
                        )
                        return@put
                    }
                    call.respond(toJsonElement(mapOf(
                        "success" to true,
                        "message" to "FRP config [$name] saved"
                    )))
                }

                /** 删除某个通道（正在运行则先停；停不掉则不删并返回 success=false） */
                delete("/config/{name}") {
                    val name = call.parameters["name"] ?: ""
                    val ok = withContext(Dispatchers.IO) { tunnelManager.deleteFrpConfig(name) }
                    call.respond(toJsonElement(mapOf("success" to ok)))
                }

                /** 设为选中通道（通道不存在 → 404） */
                post("/config/{name}/activate") {
                    val name = call.parameters["name"] ?: ""
                    val ok = withContext(Dispatchers.IO) { tunnelManager.setActiveFrpConfig(name) }
                    if (ok) {
                        call.respond(toJsonElement(mapOf("success" to true)))
                    } else {
                        call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "config not found")
                    }
                }

                /** 启动该通道（多实例：不影响其它通道）；通道不存在 → 404 */
                post("/config/{name}/start") {
                    val name = call.parameters["name"] ?: ""
                    if (tunnelManager.readFrpConfig(name) == null) {
                        call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "config not found")
                        return@post
                    }
                    val ok = withContext(Dispatchers.IO) { tunnelManager.startFrpWithConfig(name) }
                    call.respond(toJsonElement(mapOf(
                        "success" to ok,
                        // 失败时把引擎记录的具体原因带回去（缺 serverAddr / 秒退 + frpc 输出尾部）
                        "message" to if (ok) "FRP tunnel [$name] started"
                                     else "FRP tunnel [$name] failed to start: ${failReason(
                                         tunnelManager.frpEngine.lastErrorOf(name),
                                         tunnelManager.frpEngine.outputOf(name)
                                     )}"
                    )))
                }

                /** 停止该通道 */
                post("/config/{name}/stop") {
                    val name = call.parameters["name"] ?: ""
                    val dead = withContext(Dispatchers.IO) { tunnelManager.stopFrp(name) }
                    call.respond(toJsonElement(mapOf(
                        "success" to dead,
                        "message" to if (dead) "FRP tunnel [$name] stopped"
                                     else "frpc [$name] 强制停止失败，进程可能仍在运行"
                    )))
                }

                /** 该通道的运行日志（默认内存最近 200 行；`?full=1` 读运行期日志文件尾部） */
                get("/config/{name}/log") {
                    val name = call.parameters["name"] ?: ""
                    val full = call.request.queryParameters["full"] == "1"
                    val engine = tunnelManager.frpEngine
                    val log = withContext(Dispatchers.IO) {
                        // 文件只在运行期存在，停止后回落到内存缓冲，避免"停了就啥都看不到"
                        if (full) engine.readLogTail(name).ifBlank { engine.outputOf(name) }
                        else engine.outputOf(name)
                    }
                    call.respond(toJsonElement(mapOf("name" to name, "log" to log)))
                }

                /** 清空该通道日志 */
                post("/config/{name}/log/clear") {
                    val name = call.parameters["name"] ?: ""
                    tunnelManager.frpEngine.clearOutput(name)
                    call.respond(toJsonElement(mapOf("success" to true)))
                }

                /** 停止全部 FRP 通道 */
                post("/stop") {
                    val dead = withContext(Dispatchers.IO) { tunnelManager.stopAllFrp() }
                    call.respond(toJsonElement(mapOf(
                        "success" to dead,
                        "message" to if (dead) "All FRP tunnels stopped" else "部分 frpc 强制停止失败，进程可能仍在运行"
                    )))
                }
            }

            // ── Cloudflare Tunnel（多隧道 / 仅 token 模式，无临时隧道与本地 ingress）──
            route("/cf") {
                /** 列出所有隧道（含 items，不含 token 本体） */
                get("/tunnels") {
                    // activeCfTunnel() 会自愈并可能迁移旧 token（写文件）
                    val list = withContext(Dispatchers.IO) { tunnelManager.listCfTunnels() }
                    call.respond(toJsonElement(list))
                }

                /** 读取单条隧道（含 token） */
                get("/tunnel/{name}") {
                    val name = call.parameters["name"] ?: ""
                    val cfg = withContext(Dispatchers.IO) { tunnelManager.getCfTunnel(name) }
                    if (cfg == null) {
                        call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "tunnel not found")
                    } else {
                        call.respond(toJsonElement(cfg))
                    }
                }

                /** 新建/更新单条隧道（body: {token}）；名字非法或 token 为空 → 400 */
                put("/tunnel/{name}") {
                    val name = call.parameters["name"] ?: ""
                    val body = call.receiveJsonObject()
                    val token = body["token"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (name.isBlank()) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "name is required")
                        return@put
                    }
                    if (token.isBlank()) {
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "token is required")
                        return@put
                    }
                    val ok = withContext(Dispatchers.IO) { tunnelManager.saveCfTunnel(name, token) }
                    if (!ok) {
                        call.respondFail(
                            HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                            "invalid tunnel name or save failed"
                        )
                        return@put
                    }
                    call.respond(toJsonElement(mapOf(
                        "success" to true,
                        "message" to "CF tunnel [$name] saved"
                    )))
                }

                /** 删除单条隧道（正在运行则先停）；隧道不存在 → 404 */
                delete("/tunnel/{name}") {
                    val name = call.parameters["name"] ?: ""
                    if (!tunnelManager.cfEngine.tunnelExists(name)) {
                        call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "tunnel not found")
                        return@delete
                    }
                    val ok = withContext(Dispatchers.IO) { tunnelManager.deleteCfTunnel(name) }
                    call.respond(toJsonElement(mapOf("success" to ok)))
                }

                /** 设为选中隧道（不存在 → 404） */
                post("/tunnel/{name}/activate") {
                    val name = call.parameters["name"] ?: ""
                    val ok = withContext(Dispatchers.IO) { tunnelManager.setActiveCfTunnel(name) }
                    if (ok) {
                        call.respond(toJsonElement(mapOf("success" to true)))
                    } else {
                        call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "tunnel not found")
                    }
                }

                /** 启动该隧道（多实例：不影响其它隧道）；隧道不存在 → 404 */
                post("/tunnel/{name}/start") {
                    val name = call.parameters["name"] ?: ""
                    if (!tunnelManager.cfEngine.tunnelExists(name)) {
                        call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "tunnel not found")
                        return@post
                    }
                    val ok = withContext(Dispatchers.IO) { tunnelManager.startCfTunnel(name) }
                    call.respond(toJsonElement(mapOf(
                        "success" to ok,
                        // 失败时带上引擎记录的原因（多数是 token 无效导致秒退），否则客户端无从判断
                        "message" to if (ok) "Cloudflare Tunnel [$name] started"
                                     else "Cloudflare Tunnel [$name] failed to start: ${failReason(
                                         tunnelManager.cfEngine.lastErrorOf(name),
                                         tunnelManager.cfEngine.outputOf(name)
                                     )}"
                    )))
                }

                /** 停止该隧道 */
                post("/tunnel/{name}/stop") {
                    val name = call.parameters["name"] ?: ""
                    val dead = withContext(Dispatchers.IO) { tunnelManager.stopCf(name) }
                    call.respond(toJsonElement(mapOf(
                        "success" to dead,
                        "message" to if (dead) "Cloudflare Tunnel [$name] stopped"
                                     else "cloudflared [$name] 强制停止失败，进程可能仍在运行"
                    )))
                }

                /** 该隧道的运行日志（默认内存最近 200 行；`?full=1` 读运行期日志文件尾部） */
                get("/tunnel/{name}/log") {
                    val name = call.parameters["name"] ?: ""
                    val full = call.request.queryParameters["full"] == "1"
                    val engine = tunnelManager.cfEngine
                    val log = withContext(Dispatchers.IO) {
                        if (full) engine.readLogTail(name).ifBlank { engine.outputOf(name) }
                        else engine.outputOf(name)
                    }
                    call.respond(toJsonElement(mapOf("name" to name, "log" to log)))
                }

                /** 清空该隧道日志 */
                post("/tunnel/{name}/log/clear") {
                    val name = call.parameters["name"] ?: ""
                    tunnelManager.cfEngine.clearOutput(name)
                    call.respond(toJsonElement(mapOf("success" to true)))
                }

                /** 停止全部 CF 隧道 */
                post("/stop") {
                    val dead = withContext(Dispatchers.IO) { tunnelManager.stopAllCf() }
                    call.respond(toJsonElement(mapOf(
                        "success" to dead,
                        "message" to if (dead) "All Cloudflare Tunnels stopped"
                                     else "部分 cloudflared 强制停止失败，进程可能仍在运行"
                    )))
                }
            }
        }
    }

    /**
     * 启动失败时回给客户端的原因：优先用引擎记录的失败原因，
     * 没有则退化为该实例输出缓冲的最后几行（截断，避免把整个日志塞进 message）。
     */
    private fun failReason(lastError: String, output: String): String =
        lastError.takeIf { it.isNotBlank() } ?: lastLines(output)

    private fun lastLines(output: String, count: Int = 3): String =
        output.trim().lines().filter { it.isNotBlank() }.takeLast(count)
            .joinToString(" | ").take(300)
            .ifBlank { "no output" }
}
