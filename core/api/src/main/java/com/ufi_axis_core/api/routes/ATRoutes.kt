package com.ufi_axis_core.api.routes

import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.core.database.ConsoleHistoryRecord
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
    private val atChannel: ATChannel,
    /** 终端命令历史写入口：AT 与 Shell 共用一张表，两端共享同一份记录。 */
    private val recorder: ConsoleHistoryRecorder
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
                val params = call.receiveJsonObject()
                val command = params["command"]?.jsonPrimitive?.contentOrNull ?: ""
                // 发起端标识，仅用于历史列表展示；客户端自报，不参与任何判定。
                val source = params["source"]?.jsonPrimitive?.contentOrNull
                    ?: ConsoleHistoryRecord.SOURCE_UNKNOWN

                if (command.isEmpty()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "command is required")
                    return@post
                }

                // ── 安全过滤：拦截危险 AT 命令 ──
                if (isDangerousCommand(command)) {
                    // 拦下的命令同样入库：审计里最该看的就是这部分。
                    recorder.record(
                        channel = ConsoleHistoryRecord.CHANNEL_AT,
                        command = command,
                        ok = false,
                        stderr = "命令被安全策略拦截（可能导致基带崩溃或设备重启）",
                        source = source
                    )
                    call.respondFail(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN,
                        "Dangerous AT command blocked: $command",
                        mapOf("reason" to "This command may cause modem crash or device reboot"))
                    return@post
                }

                if (!atChannel.isConnected) {
                    // 通道没连上不入库：这不是一次"执行"，只是环境不满足，
                    // 落库只会让历史里塞满一堆无信息量的失败行。
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "AT channel not connected")
                    return@post
                }

                val startedAt = System.currentTimeMillis()
                val response = atChannel.sendCommand(command)
                val elapsed = System.currentTimeMillis() - startedAt
                // AT 没有退出码，exitCode 落 null（别用 0 顶替）；成败看有没有回响应。
                recorder.record(
                    channel = ConsoleHistoryRecord.CHANNEL_AT,
                    command = command,
                    ok = response != null,
                    stdout = response ?: "",
                    stderr = if (response == null) "无响应" else "",
                    durationMs = elapsed,
                    source = source
                )
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
