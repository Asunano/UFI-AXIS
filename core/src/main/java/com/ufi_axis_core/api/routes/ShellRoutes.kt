package com.ufi_axis_core.api.routes

import android.util.Log
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.util.ShellExecutor
import com.ufi_axis_core.util.ShellQoS
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

class ShellRoutes {

    companion object {
        private const val TAG = "ShellAudit"
        // 白名单：只允许字母、数字、点号、下划线，防止命令注入
        private val PROP_KEY_PATTERN = Regex("^[a-zA-Z0-9._]+$")
    }

    fun register(route: Route) {
        route.route("/shell") {
            // 执行命令（需 root）
            post("/exec") {
                val body = call.receive<JsonObject>()
                val cmd = body["command"]?.jsonPrimitive?.contentOrNull ?: ""
                val asRoot = body["as_root"]?.jsonPrimitive?.booleanOrNull ?: true
                val timeout = (body["timeout"]?.jsonPrimitive?.intOrNull ?: 10) * 1000L
                if (cmd.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "command is required")))
                    return@post
                }
                // 审计日志：记录完整命令、是否 root、来源 IP
                val remoteHost = call.request.local.remoteAddress
                Log.w(TAG, "[EXEC] asRoot=$asRoot remote=$remoteHost command=$cmd")
                val result = if (asRoot) ShellExecutor.executeAsRoot(cmd, timeout)
                             else ShellExecutor.execute(cmd, timeout)
                Log.w(TAG, "[EXEC-RESULT] exitCode=${result.exitCode} success=${result.isSuccess}")
                call.respond(toJsonElement(mapOf(
                    "exit_code" to result.exitCode,
                    "stdout" to result.stdout,
                    "stderr" to result.stderr,
                    "success" to result.isSuccess
                )))
            }

            // 检查 root
            get("/root") {
                val hasRoot = ShellQoS.executeAsRootCached("id").stdout.contains("uid=0")
                call.respond(toJsonElement(mapOf("root" to hasRoot)))
            }

            // 系统属性
            get("/prop/{key}") {
                val key = call.parameters["key"] ?: ""
                // 白名单校验 key，防止命令注入
                if (key.isBlank() || !PROP_KEY_PATTERN.matches(key)) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        toJsonElement(mapOf("error" to "Invalid property key. Only [a-zA-Z0-9._] allowed."))
                    )
                    return@get
                }
                val result = ShellQoS.executeCached("getprop \"$key\"")
                call.respond(toJsonElement(mapOf("key" to key, "value" to result.stdout)))
            }
        }
    }
}