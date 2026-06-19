package com.ufi_axis_core.api.routes

import com.ufi_axis_core.alert.AlertEngine
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
            get("/config") {
                val configJson = Json.encodeToString(
                    AlertEngine.AlertConfig.serializer(),
                    alertEngine.config.value
                )
                call.respond(Json.parseToJsonElement(configJson))
            }

            // 更新告警配置
            put("/config") {
                val newConfig = call.receive<AlertEngine.AlertConfig>()
                alertEngine.updateConfig(newConfig)
                call.respond(toJsonElement(mapOf("success" to true, "config" to alertEngine.config.value)))
            }

            // 获取告警记录
            get("/list") {
                val limit = (call.request.queryParameters["limit"] ?: "20").toIntOrNull() ?: 20
                val alerts = alertEngine.getRecentAlerts(limit)
                call.respond(toJsonElement(mapOf("alerts" to alerts, "count" to alerts.size)))
            }

            // 确认告警
            post("/ack") {
                val params = call.receive<JsonObject>()
                val id = params["id"]?.jsonPrimitive?.longOrNull
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest,
                        toJsonElement(mapOf("error" to "id is required")))
                    return@post
                }
                alertEngine.acknowledgeAlert(id)
                call.respond(toJsonElement(mapOf("success" to true, "id" to id)))
            }
        }
    }
}
