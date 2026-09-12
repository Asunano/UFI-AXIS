package com.ufi_axis_core.api.routes

import com.ufi_axis_core.alert.AlertEngine
import com.ufi_axis_core.contract.Alerts
import com.ufi_axis_core.contract.ErrorCode
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import kotlinx.serialization.json.*

/**
 * 告警管理路由
 * GET  /api/alerts/config  - 获取告警配置
 * PUT  /api/alerts/config  - 更新告警配置
 * GET  /api/alerts/list    - 获取告警记录
 * POST /api/alerts/ack     - 确认告警
 */
class AlertRoutes(
    private val alertEngine: AlertEngine
) {
    fun register(route: Route) {
        route.route("/alerts") {
            // 获取告警配置
            // 用 AlertEngine.ConfigJson（encodeDefaults=true）：默认 Json 会省略等于默认值的字段，
            // 全新设备会返回 `{}` —— 两端拿不到阈值也拿不到 configVersion（第一次 PUT 必然 409）。
            get("/config") {
                val configJson = AlertEngine.ConfigJson.encodeToString(
                    AlertEngine.AlertConfig.serializer(),
                    alertEngine.config.value
                )
                call.respond(Json.parseToJsonElement(configJson))
            }


            // 更新告警配置（P2 多端同步：版本守门 + 广播 config_changed）
            // C02：请求体是**补丁**——只有显式出现的键会覆盖，其余保持服务端现值。
            // 旧的 receive<AlertConfig>() 会把缺省字段当成"写回默认值"，少传 perType 就会清空别端分类开关。
            put("/config") {
                val patch = call.receiveJsonObject()
                val current = alertEngine.getConfig()
                // 版本守门：入参 version 必须与当前一致，否则视为陈旧写（ab 设备并发回弹防御）。
                // 缺省 configVersion 一律按陈旧写处理（null != current），否则守门形同虚设。
                val incomingVersion = patch["configVersion"]?.jsonPrimitive?.longOrNull
                if (incomingVersion != current.configVersion) {
                    // C01：统一信封。注意这里 `error` 历史上放的是**机器码**而非文案
                    // （客户端按 `error == "config_version_conflict"` 判定），
                    // 因此用 extra 显式保留原值，不让 fail() 的默认 `error=message` 覆盖它。
                    call.respondFail(
                        HttpStatusCode.Conflict,
                        ErrorCode.CONFIG_VERSION_CONFLICT,
                        "配置已被其他端修改，请刷新后重新保存",
                        mapOf(
                            "error" to ErrorCode.CONFIG_VERSION_CONFLICT,
                            "currentVersion" to current.configVersion,
                            "config" to current
                        )
                    )
                    return@put
                }
                val incoming = try {
                    AlertEngine.mergeConfigPatch(current, patch)
                } catch (e: Exception) {
                    // 类型不匹配（如 enabled: "yes"）在旧实现里由 ContentNegotiation 转成 400，
                    // 合并路径要自己保持这个语义，否则会经 StatusPages 变成 500。
                    call.respondFail(
                        HttpStatusCode.BadRequest,
                        ErrorCode.BAD_REQUEST,
                        "告警配置字段类型不合法：${e.message}"
                    )
                    return@put
                }
                val saved = alertEngine.replaceConfig(incoming)
                alertEngine.broadcastConfigChanged()
                call.respond(toJsonElement(mapOf("success" to true, "config" to saved)))
            }


            // 获取告警记录（P1 去重改造：游标分页 + 多维过滤 + 聚合计数）
            // 兼容旧前端：不传 cursor 时回退为最新 limit 条；传 start_time+end_time 仍支持区间查询。
            get("/list") {
                val limit = Alerts.ListQuery.clampLimit(
                    call.request.queryParameters["limit"]?.toIntOrNull()
                        ?: Alerts.ListQuery.LIMIT_DEFAULT
                )
                val level = call.request.queryParameters["level"]?.takeIf { it.isNotBlank() }
                val type = call.request.queryParameters["type"]?.takeIf { it.isNotBlank() }
                val unreadOnly = call.request.queryParameters["unread"]?.toBooleanStrictOrNull() ?: false
                val startTime = call.request.queryParameters["start_time"]?.toLongOrNull()
                val endTime = call.request.queryParameters["end_time"]?.toLongOrNull()
                val (cTs, cId) = decodeCursor(call.request.queryParameters["cursor"])

                val alerts = alertEngine.getPaged(level, type, unreadOnly, startTime, endTime, cTs, cId, limit)
                val counts = alertEngine.getCounts(level, type, unreadOnly, startTime, endTime)
                val nextCursor = alerts.lastOrNull()?.let { encodeCursor(it.timestamp, it.id) }

                call.respond(toJsonElement(mapOf(
                    "alerts" to alerts,
                    "count" to alerts.size,
                    "total" to counts.total,
                    "counts" to counts,
                    "nextCursor" to nextCursor,
                    "hasMore" to (alerts.size >= limit)
                )))
            }

            // 确认告警
            post("/ack") {
                val params = call.receiveJsonObject()
                val id = params["id"]?.jsonPrimitive?.longOrNull
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest,
                        toJsonElement(mapOf("error" to "id is required")))
                    return@post
                }
                alertEngine.acknowledgeAlert(id)
                call.respond(toJsonElement(mapOf("success" to true, "id" to id)))
            }

            // 删除告警（事件中心「更多操作 → 删除」；物理删除单条，前端已二次确认）
            // 与 /ack 完全同构：同样收 { "id": Long }，同样的缺参 400 语义，便于前端复用错误处理。
            post("/delete") {
                val params = call.receiveJsonObject()
                val id = params["id"]?.jsonPrimitive?.longOrNull
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest,
                        toJsonElement(mapOf("error" to "id is required")))
                    return@post
                }
                val deleted = alertEngine.deleteAlert(id)
                // deleted=0 表示该 id 已不存在（例如并发删除/已被清理），对前端仍算成功：
                // 目标状态「该条不在列表里」已达成，避免用户看到无意义的失败提示。
                call.respond(toJsonElement(mapOf("success" to true, "id" to id, "deleted" to deleted)))
            }

            // 批量确认告警（事件中心「全部已读」/分类已读）。消除逐条 POST 的 N+1 请求。
            post("/ack-all") {
                val params = call.receiveJsonObject()
                // 2026-08-24 修复：params["ids"] 可能为 JsonNull（前端传 "ids": null 或空值），
                // 直接 .jsonArray 会在 JsonNull 上抛 IllegalArgumentException → StatusPages 500。
                // 改为类型安全：仅 JsonArray 才解析，JsonNull/缺省/字符串均视为「全部」。
                val ids = (params["ids"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.longOrNull }
                val type = params["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val level = params["level"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val unreadOnly = params["unreadOnly"]?.jsonPrimitive?.booleanOrNull ?: true
                val updated = alertEngine.acknowledgeAll(ids, type, level, unreadOnly)
                call.respond(toJsonElement(mapOf("success" to true, "updated" to updated)))
            }

            // 确认已恢复的旧告警（事件中心「一键清理已恢复」）。
            post("/ack-resolved") {
                val params = call.receiveJsonObject()
                val minAgeSec = params["minAgeSec"]?.jsonPrimitive?.longOrNull
                val updated = alertEngine.acknowledgeResolved(minAgeSec)
                call.respond(toJsonElement(mapOf("success" to true, "updated" to updated)))
            }
        }
    }

    // ---- 游标编解码（用于列表 keyset 分页） ----
    private fun encodeCursor(ts: Long, id: Long): String =
        java.util.Base64.getEncoder().encodeToString("$ts.$id".toByteArray())

    private fun decodeCursor(cursor: String?): Pair<Long?, Long?> {
        if (cursor.isNullOrBlank()) return null to null
        return try {
            val raw = String(java.util.Base64.getDecoder().decode(cursor))
            val (ts, id) = raw.split(".")
            ts.toLongOrNull() to id.toLongOrNull()
        } catch (_: Exception) {
            null to null
        }
    }
}
