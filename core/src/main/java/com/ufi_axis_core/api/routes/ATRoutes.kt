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
    companion object {
        /**
         * 危险 AT 命令黑名单 — 阻止可能导致 modem 崩溃/设备重启的指令
         *
         * 这些命令会触发基带/网络栈/整机重启，在 kernel 存在 sprd-sblock
         * 驱动 BUG 的设备上会导致 kobject_add_internal -EEXIST 内核 panic。
         */
        private val DANGEROUS_AT_PATTERNS = listOf(
            Regex("AT\\+CFUN\\s*=", RegexOption.IGNORE_CASE),     // 基带功能控制(含重启)
            Regex("AT\\+SFUN\\s*=", RegexOption.IGNORE_CASE),     // 网络栈控制(含重启)
            Regex("AT\\+RESET", RegexOption.IGNORE_CASE),         // 设备复位
            Regex("AT\\+POF", RegexOption.IGNORE_CASE),           // 关机
            Regex("AT\\*", RegexOption.IGNORE_CASE)               // 厂商私有指令(通配)
        )

        fun isDangerousCommand(command: String): Boolean =
            DANGEROUS_AT_PATTERNS.any { it.containsMatchIn(command) }
    }

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

                // ── 安全过滤：拦截危险 AT 命令 ──
                if (isDangerousCommand(command)) {
                    call.respond(HttpStatusCode.Forbidden,
                        toJsonElement(mapOf(
                            "error" to "Dangerous AT command blocked: $command",
                            "reason" to "This command may cause modem crash or device reboot"
                        )))
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
