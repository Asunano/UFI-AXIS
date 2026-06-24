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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class ShellRoutes {

    companion object {
        private const val TAG = "ShellAudit"
        // 白名单：只允许字母、数字、点号、下划线，防止命令注入
        private val PROP_KEY_PATTERN = Regex("^[a-zA-Z0-9._]+$")

        /**
         * 危险 Shell 命令黑名单 — 阻止可能导致内核崩溃/设备重启的操作
         */
        private val DANGEROUS_SHELL_PATTERNS = listOf(
            // 直接触发内核模块操作
            Regex("\\binsmod\\b", RegexOption.IGNORE_CASE),
            Regex("\\bmodprobe\\b", RegexOption.IGNORE_CASE),
            Regex("\\brmmod\\b", RegexOption.IGNORE_CASE),
            // service call 到 sprd HIDL 服务 (绕过 AT 黑名单)
            Regex("service\\s+call\\s+.*sprd", RegexOption.IGNORE_CASE),
            // 整机重启/关机
            Regex("\\breboot\\b", RegexOption.IGNORE_CASE),
            Regex("\\bpoweroff\\b", RegexOption.IGNORE_CASE),
            Regex("\\bshutdown\\b", RegexOption.IGNORE_CASE),
            // 直接写入 sysfs（可能触发内核竞态）
            Regex("echo\\s+.*\\s*>\\s*/sys/class/(sblock|smem|smsg|mem)", RegexOption.IGNORE_CASE),
            // 杀掉关键系统进程
            Regex("kill\\s+-9\\s+.*(rild|modem|netd|servicemanager)", RegexOption.IGNORE_CASE)
        )

        fun isDangerousShellCommand(command: String): Boolean =
            DANGEROUS_SHELL_PATTERNS.any { it.containsMatchIn(command) }
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
                // ── 安全过滤：拦截危险 Shell 命令 ──
                if (isDangerousShellCommand(cmd)) {
                    Log.w(TAG, "[BLOCKED] asRoot=$asRoot remote=${call.request.local.remoteAddress} command=$cmd")
                    call.respond(HttpStatusCode.Forbidden,
                        toJsonElement(mapOf(
                            "error" to "Dangerous shell command blocked",
                            "reason" to "This command may cause kernel panic or device reboot"
                        )))
                    return@post
                }
                // 审计日志：记录完整命令、是否 root、来源 IP
                val remoteHost = call.request.local.remoteAddress
                Log.w(TAG, "[EXEC] asRoot=$asRoot remote=$remoteHost command=$cmd")
                // 必须通过 ShellQoS 执行 — 防止多请求并发的 su -c 进程
                // 超出 root 信号量上限，压垮 sprd_ipc_probe 驱动导致内核 panic
                val result = withContext(Dispatchers.IO) {
                    if (asRoot) ShellQoS.executeAsRoot(cmd, timeout)
                    else ShellQoS.execute(cmd, timeout)
                }
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
                val hasRoot = withContext(Dispatchers.IO) {
                    ShellExecutor.executeAsRoot("id").stdout.contains("uid=0")
                }
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
                val result = withContext(Dispatchers.IO) {
                    ShellExecutor.execute("getprop \"$key\"")
                }
                call.respond(toJsonElement(mapOf("key" to key, "value" to result.stdout)))
            }
        }
    }
}