package com.ufi_axis_core.api.routes

import android.util.Log
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.core.database.ConsoleHistoryRecord
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

class ShellRoutes(
    /**
     * 终端命令历史的写入口。执行结果落库后两端共享同一份记录，
     * 同时补上 [TAG] 只写 logcat 的审计缺口（release 构建捞不回来）。
     */
    private val recorder: ConsoleHistoryRecorder
) {

    companion object {
        private const val TAG = "ShellAudit"
        // 白名单：只允许字母、数字、点号、下划线，防止命令注入
        private val PROP_KEY_PATTERN = Regex("^[a-zA-Z0-9._]+$")

        /**
         * `timeout` 的合法区间（秒）。
         *
         * 下限 1：负数或 0 会让 `withTimeout` 立刻抛 TimeoutCancellationException，
         * 请求还没执行就变成 500。
         * 上限 120：超大值（如 Int.MAX_VALUE）折算出来是几十年，会把一个 IO 线程和
         * 一个 root 信号量许可永久钉住 —— 单个请求就能把特权 shell 通道占满。
         */
        private const val MIN_EXEC_TIMEOUT_SEC = 1
        private const val MAX_EXEC_TIMEOUT_SEC = 120
        private const val DEFAULT_EXEC_TIMEOUT_SEC = 10

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
                val body = call.receiveJsonObject()
                val cmd = body["command"]?.jsonPrimitive?.contentOrNull ?: ""
                val asRoot = body["as_root"]?.jsonPrimitive?.booleanOrNull ?: true
                val timeout = (body["timeout"]?.jsonPrimitive?.intOrNull ?: DEFAULT_EXEC_TIMEOUT_SEC)
                    .coerceIn(MIN_EXEC_TIMEOUT_SEC, MAX_EXEC_TIMEOUT_SEC) * 1000L
                // 发起端标识，仅用于历史列表展示"这条是谁发的"。客户端自报、不参与任何判定。
                val source = body["source"]?.jsonPrimitive?.contentOrNull
                    ?: ConsoleHistoryRecord.SOURCE_UNKNOWN
                if (cmd.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "command is required")
                    return@post
                }
                // ── 安全过滤：拦截危险 Shell 命令 ──
                if (isDangerousShellCommand(cmd)) {
                    Log.w(TAG, "[BLOCKED] asRoot=$asRoot remote=${call.request.local.remoteAddress} command=$cmd")
                    // 被拦下的命令**同样入库**：审计里最该看的就是这部分，
                    // 而它恰恰是唯一不会产生退出码的分支（exitCode = null）。
                    recorder.record(
                        channel = ConsoleHistoryRecord.CHANNEL_SHELL,
                        command = cmd,
                        ok = false,
                        asRoot = asRoot,
                        stderr = "命令被安全策略拦截（可能导致内核崩溃或设备重启）",
                        source = source
                    )
                    call.respondFail(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN,
                        "Dangerous shell command blocked",
                        mapOf("reason" to "This command may cause kernel panic or device reboot"))
                    return@post
                }
                // 审计日志：记录完整命令、是否 root、来源 IP
                val remoteHost = call.request.local.remoteAddress
                Log.w(TAG, "[EXEC] asRoot=$asRoot remote=$remoteHost command=$cmd")
                // 必须通过 ShellQoS 执行 — 防止多请求并发的 su -c 进程
                // 超出 root 信号量上限，压垮 sprd_ipc_probe 驱动导致内核 panic
                val startedAt = System.currentTimeMillis()
                val result = withContext(Dispatchers.IO) {
                    if (asRoot) ShellQoS.executeAsRoot(cmd, timeout)
                    else ShellQoS.execute(cmd, timeout)
                }
                // 耗时只能在这里量：ShellResult 本身不带耗时，加进去要牵动 BatchResult
                // 与全仓所有构造点，代价远大于收益。
                val elapsed = System.currentTimeMillis() - startedAt
                Log.w(TAG, "[EXEC-RESULT] exitCode=${result.exitCode} success=${result.isSuccess}")
                recorder.record(
                    channel = ConsoleHistoryRecord.CHANNEL_SHELL,
                    command = cmd,
                    ok = result.isSuccess,
                    asRoot = asRoot,
                    exitCode = result.exitCode,
                    stdout = result.stdout,
                    stderr = result.stderr,
                    durationMs = elapsed,
                    source = source
                )
                call.respond(toJsonElement(mapOf(
                    "exit_code" to result.exitCode,
                    "stdout" to result.stdout,
                    "stderr" to result.stderr,
                    "success" to result.isSuccess
                )))
            }

            // 检查特权 shell 可用性（ADB shell 或 root）
            get("/root") {
                val (hasPrivileged, uid) = withContext(Dispatchers.IO) {
                    val available = ShellExecutor.hasRootAccess()
                    val idOut = ShellExecutor.executeAsRoot("id").stdout
                    available to idOut
                }
                call.respond(toJsonElement(mapOf(
                    "root" to hasPrivileged,
                    "uid" to uid,
                    "method" to if (com.ufi_axis_core.util.AdbShellExecutor.isAvailable) "adb_shell" else "shell"
                )))
            }

            // 系统属性
            get("/prop/{key}") {
                val key = call.parameters["key"] ?: ""
                // 白名单校验 key，防止命令注入
                if (key.isBlank() || !PROP_KEY_PATTERN.matches(key)) {
                    call.respondFail(
                        HttpStatusCode.BadRequest,
                        ErrorCode.BAD_REQUEST,
                        "Invalid property key. Only [a-zA-Z0-9._] allowed."
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