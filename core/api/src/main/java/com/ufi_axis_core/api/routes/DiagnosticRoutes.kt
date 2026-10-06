package com.ufi_axis_core.api.routes

import com.ufi_axis_core.contract.Endpoints
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 网络诊断工具集（2026-10-06 实用功能批·拍板第1项）。
 *
 * 无 root 方案：全部走 /system/bin 现成二进制 + Java 原生 API——
 * - ping        → /system/bin/ping -c N -W 2（Android 自带，普通 uid 可执行）
 * - DNS 解析    → InetAddress.getAllByName（系统 resolver）
 * - TCP 探测    → Socket connect（可达性 + 端口开放）
 * - traceroute  → ping -t TTL 逐跳递增（UDP 高端口法需要 raw socket，无 root 做不了；
 *                 高端口 ICMP「port unreachable」推算路径是 traceroute 无 root 变体，
 *                 Android 自带 ping 不输出 ICMP 错误类型，故用 TTL 递增 + 每跳网关回包
 *                 判定，失败跳显示 *。精度有限但零权限。）
 *
 * 非交互、有超时、有输出行数上限——不是任意 shell（那是 ShellRoutes 的事）。
 */
class DiagnosticRoutes {

    companion object {
        private const val MAX_OUTPUT_LINES = 64
        private const val DEFAULT_COUNT = 4
        private val HOST_RE = Regex("^[A-Za-z0-9._-]{1,253}$")
    }

    /** 宿主白名单校验：防注入（host 只进 argv，不进 shell，但仍收紧字符集） */
    private fun validHost(host: String): Boolean = HOST_RE.matches(host)

    private fun runCmd(argv: List<String>, timeoutSec: Long): Pair<Int, String> {
        val sb = StringBuilder()
        var code = -1
        try {
            val p = ProcessBuilder(argv).redirectErrorStream(true).start()
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            var line: String?
            var lines = 0
            while (reader.readLine().also { line = it } != null) {
                if (lines < MAX_OUTPUT_LINES) {
                    sb.appendLine(line)
                    lines++
                }
            }
            code = p.waitFor(timeoutSec + 5, TimeUnit.SECONDS).let { if (it) p.exitValue() else -1 }
            if (code == -1) p.destroyForcibly()
        } catch (e: Exception) {
            sb.appendLine("error: ${e.message}")
        }
        return code to sb.toString().trim()
    }

    fun register(route: Route) {
        route.route("/diagnostic") {
            // ── ping ──
            post("/ping") {
                val body = call.receiveJsonObject()
                val host = body["host"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                val count = (body["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: DEFAULT_COUNT).coerceIn(1, 10)
                if (!validHost(host)) {
                    call.respondFailBadRequest("invalid host"); return@post
                }
                val (code, out) = withContext(Dispatchers.IO) {
                    runCmd(listOf("/system/bin/ping", "-c", count.toString(), "-W", "2", host), count * 2L + 5)
                }
                call.respondText(
                    okJson("ping", code, out),
                    ContentType.Application.Json
                )
            }
            // ── DNS 解析 ──
            post("/dns") {
                val body = call.receiveJsonObject()
                val host = body["host"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                if (!validHost(host)) {
                    call.respondFailBadRequest("invalid host"); return@post
                }
                val start = System.currentTimeMillis()
                val addrs = try {
                    withContext(Dispatchers.IO) {
                        java.net.InetAddress.getAllByName(host).map { it.hostAddress ?: it.toString() }
                    }
                } catch (e: Exception) {
                    emptyList()
                }
                val ms = System.currentTimeMillis() - start
                val out = buildString {
                    appendLine("server: (system resolver)")
                    appendLine("query: $host took $ms ms")
                    if (addrs.isEmpty()) appendLine("error: resolution failed") else addrs.forEach { appendLine("address: $it") }
                }
                call.respondText(okJson("dns", if (addrs.isEmpty()) 1 else 0, out), ContentType.Application.Json)
            }
            // ── TCP 端口探测 ──
            post("/tcp") {
                val body = call.receiveJsonObject()
                val host = body["host"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                val port = (body["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0)
                if (!validHost(host) || port !in 1..65535) {
                    call.respondFailBadRequest("invalid host or port"); return@post
                }
                val start = System.currentTimeMillis()
                val ok = try {
                    withContext(Dispatchers.IO) {
                        java.net.Socket().use { s ->
                            s.connect(java.net.InetSocketAddress(host, port), 5000)
                            true
                        }
                    }
                } catch (e: Exception) {
                    false
                }
                val ms = System.currentTimeMillis() - start
                val out = if (ok) "tcp connect $host:$port ok ($ms ms)" else "tcp connect $host:$port failed"
                call.respondText(okJson("tcp", if (ok) 0 else 1, out), ContentType.Application.Json)
            }
            // ── traceroute（TTL 递增，无 root）──
            post("/traceroute") {
                val body = call.receiveJsonObject()
                val host = body["host"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                val maxHops = (body["max_hops"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 15).coerceIn(1, 30)
                if (!validHost(host)) {
                    call.respondFailBadRequest("invalid host"); return@post
                }
                val out = withContext(Dispatchers.IO) {
                    buildString {
                        appendLine("traceroute (TTL method, no root) to $host, max $maxHops hops")
                        for (ttl in 1..maxHops) {
                            val (code, hopOut) = runCmd(
                                listOf("/system/bin/ping", "-c", "1", "-t", ttl.toString(), "-W", "2", host), 5
                            )
                            // 从 ping 输出提往返时延
                            val rtt = Regex("""time[= ]([\d.]+) ms""").find(hopOut)?.groupValues?.get(1)
                            when {
                                rtt != null -> appendLine("$ttl: ${rtt} ms")
                                else -> appendLine("$ttl: *")
                            }
                            // 到达目标：直接 ping 目标 1 次成功即提前结束
                            if (code == 0 && rtt != null && ttl > 1) {
                                appendLine("reached $host at hop $ttl")
                                break
                            }
                        }
                    }
                }
                call.respondText(okJson("traceroute", 0, out), ContentType.Application.Json)
            }
        }
    }

    private fun okJson(tool: String, code: Int, output: String): String {
        val esc = output.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
        return """{"ok":true,"tool":"$tool","exit_code":$code,"output":"$esc"}"""
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondFailBadRequest(msg: String) {
        respondText("""{"ok":false,"error":"$msg"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
    }
}
