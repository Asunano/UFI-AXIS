package com.ufi_axis_core.api.routes

import com.ufi_axis_core.collector.at.ATChannel
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import kotlinx.serialization.json.*

/**
 * AT 指令透传路由
 * POST /api/at/command  - 发送任意 AT 指令
 * GET  /api/at/status   - AT 通道状态
 * GET  /api/at/platform - 平台信息
 */
class ATRoutes(
    private val atChannel: ATChannel
) {
    fun register(route: Route) {
        route.route("/at") {
            // 发送 AT 指令
            post("/command") {
                val params = call.receive<JsonObject>()
                val command = params["command"]?.jsonPrimitive?.contentOrNull ?: ""

                if (command.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest,
                        toJsonElement(mapOf("error" to "command is required")))
                    return@post
                }

                if (!atChannel.isConnected) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "AT channel not connected")))
                    return@post
                }

                val response = atChannel.sendCommand(command)
                call.respond(toJsonElement(mapOf(
                    "command" to command,
                    "response" to (response ?: "No response"),
                    "success" to (response != null)
                )))
            }

            // AT 通道状态
            get("/status") {
                call.respond(toJsonElement(mapOf(
                    "connected" to atChannel.isConnected,
                    "platform" to atChannel.getPlatformInfo()
                )))
            }

            // 平台信息
            get("/platform") {
                call.respond(toJsonElement(atChannel.getPlatformInfo()))
            }
        }
    }
}
