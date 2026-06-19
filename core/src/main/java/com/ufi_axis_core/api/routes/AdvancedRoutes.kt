package com.ufi_axis_core.api.routes

import android.content.Context
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AssetExtractor
import com.ufi_axis_core.util.ShellExecutor
import com.ufi_axis_core.util.ShellQoS
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 高级工具路由
 * /api/advanced/ — TTYD, iperf3, FOTA, Boot/Schedule 脚本,
 *                   CPU 核心, 网络加速, Phantom Killer, 带宽限制
 */
class AdvancedRoutes(private val appContext: Context) {
    private val tag = "AdvancedRoutes"

    fun register(route: Route) {
        route.route("/advanced") {

            // ==================== TTYD Web 终端 ====================

            get("/ttyd/status") {
                try {
                    val running = isProcessRunning("ttyd")
                    call.respond(toJsonElement(mapOf("running" to running, "port" to 1146)))
                } catch (e: Exception) {
                    AppLogger.e(tag, "ttyd/status failed", e)
                    call.respond(toJsonElement(mapOf("running" to false, "port" to 1146)))
                }
            }

            post("/ttyd/start") {
                try {
                    val ttydPath = AssetExtractor.getPath(appContext, "ttyd")
                    val port = call.request.queryParameters["port"]?.toIntOrNull() ?: 1146
                    val password = java.security.SecureRandom().let { rng ->
                        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
                        (1..8).map { chars[rng.nextInt(chars.length)] }.joinToString("")
                    }
                    // Stop any existing ttyd
                    ShellQoS.executeAsRoot("pkill -f 'ttyd.*-p $port'")
                    kotlinx.coroutines.delay(500)
                    val cmd = "nohup $ttydPath -W -p $port -c \"ufi:$password\" /system/bin/sh &"
                    ShellQoS.executeAsRoot(cmd)
                    kotlinx.coroutines.delay(1000)
                    val running = isProcessRunning("ttyd")
                    call.respond(
                        if (running) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                        toJsonElement(mapOf(
                            "success" to running, "port" to port,
                            "username" to "ufi", "password" to password
                        ))
                    )
                } catch (e: Exception) {
                    AppLogger.e(tag, "ttyd/start failed", e)
                    call.respond(HttpStatusCode.InternalServerError,
                        toJsonElement(mapOf("success" to false, "error" to (e.message ?: "Unknown error"))))
                }
            }

            post("/ttyd/stop") {
                try {
                    ShellQoS.executeAsRoot("pkill -f ttyd")
                    call.respond(toJsonElement(mapOf("success" to true)))
                } catch (e: Exception) {
                    AppLogger.e(tag, "ttyd/stop failed", e)
                    call.respond(toJsonElement(mapOf("success" to false, "error" to (e.message ?: "Unknown error"))))
                }
            }

            // ==================== iperf3 守护进程 ====================

            get("/iperf3/status") {
                try {
                    val running = isProcessRunning("iperf3")
                    call.respond(toJsonElement(mapOf("running" to running)))
                } catch (e: Exception) {
                    AppLogger.e(tag, "iperf3/status failed", e)
                    call.respond(toJsonElement(mapOf("running" to false)))
                }
            }

            post("/iperf3/start") {
                try {
                    val iperf3Path = AssetExtractor.getPath(appContext, "iperf3")
                    ShellQoS.executeAsRoot("pkill -f iperf3")
                    kotlinx.coroutines.delay(300)
                    ShellQoS.executeAsRoot("nohup $iperf3Path -s -D &")
                    kotlinx.coroutines.delay(500)
                    val running = isProcessRunning("iperf3")
                    call.respond(
                        if (running) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                        toJsonElement(mapOf("success" to running))
                    )
                } catch (e: Exception) {
                    AppLogger.e(tag, "iperf3/start failed", e)
                    call.respond(HttpStatusCode.InternalServerError,
                        toJsonElement(mapOf("success" to false, "error" to (e.message ?: "Unknown error"))))
                }
            }

            post("/iperf3/stop") {
                try {
                    ShellQoS.executeAsRoot("pkill -f iperf3")
                    call.respond(toJsonElement(mapOf("success" to true)))
                } catch (e: Exception) {
                    AppLogger.e(tag, "iperf3/stop failed", e)
                    call.respond(toJsonElement(mapOf("success" to false, "error" to (e.message ?: "Unknown error"))))
                }
            }

            // ==================== FOTA 禁用器 ====================

            get("/fota/status") {
                try {
                    val packages = listOf(
                        "com.zte.zdm", "cn.zte.aftersale", "com.zte.zdmdaemon",
                        "com.zte.zdmdaemon.install", "com.zte.analytics", "com.zte.neopush"
                    )
                    val statusMap = mutableMapOf<String, String>()
                    // Single shell call to list all packages
                    val allPkgs = ShellQoS.executeAsRootCached("pm list packages").stdout
                    for (pkg in packages) {
                        statusMap[pkg] = if (allPkgs.contains(pkg)) "installed" else "removed"
                    }
                    call.respond(toJsonElement(mapOf("packages" to statusMap)))
                } catch (e: Exception) {
                    AppLogger.e(tag, "fota/status failed", e)
                    call.respond(toJsonElement(mapOf(
                        "packages" to emptyMap<String, String>(),
                        "error" to (e.message ?: "Unknown error")
                    )))
                }
            }

            post("/fota/disable") {
                try {
                    val packages = listOf(
                        "com.zte.zdm", "cn.zte.aftersale", "com.zte.zdmdaemon",
                        "com.zte.zdmdaemon.install", "com.zte.analytics", "com.zte.neopush"
                    )
                    // Batch: 12 shell calls → 1
                    val cmds = packages.flatMap { pkg ->
                        listOf("pm disable $pkg", "pm uninstall -k --user 0 $pkg")
                    }
                    val batchResult = ShellQoS.batchExecuteAsRoot(cmds)
                    // Extract per-package results (every even index is disable, odd is uninstall)
                    val results = mutableMapOf<String, Boolean>()
                    packages.forEachIndexed { i, pkg ->
                        val uninstallIdx = i * 2 + 1
                        results[pkg] = batchResult.stdout(uninstallIdx).contains("Success") ||
                            batchResult.successes.getOrElse(uninstallIdx) { false }
                    }
                    call.respond(toJsonElement(mapOf("success" to true, "results" to results)))
                } catch (e: Exception) {
                    AppLogger.e(tag, "fota/disable failed", e)
                    call.respond(HttpStatusCode.InternalServerError,
                        toJsonElement(mapOf("success" to false, "error" to (e.message ?: "Unknown error"))))
                }
            }

            // ==================== CPU 核心管理 ====================

            get("/cpu-cores") {
                try {
                    val cpuCount = Runtime.getRuntime().availableProcessors()
                    // Generate core indices in Kotlin — no reliance on nproc, seq, or brace expansion
                    val indices = (0 until cpuCount).joinToString(" ")
                    val shellCmd = buildString {
                        append("for i in $indices; do ")
                        append("o=\$(cat /sys/devices/system/cpu/cpu\$i/online 2>/dev/null || echo 1); ")
                        append("f=\$(cat /sys/devices/system/cpu/cpu\$i/cpufreq/scaling_cur_freq 2>/dev/null || echo 0); ")
                        append("m=\$(cat /sys/devices/system/cpu/cpu\$i/cpufreq/cpuinfo_max_freq 2>/dev/null || echo 0); ")
                        append("echo \"\$i|\$o|\$f|\$m\"; ")
                        append("done")
                    }
                    val result = ShellQoS.executeAsRootCached(shellCmd)
                    val cores = mutableListOf<Map<String, Any>>()
                    result.stdout.lines().filter { it.contains("|") }.forEach { line ->
                        val parts = line.split("|")
                        if (parts.size >= 4) {
                            cores.add(mapOf(
                                "core" to (parts[0].trim().toIntOrNull() ?: 0),
                                "online" to (parts[1].trim() != "0"),
                                "frequency" to (parts[2].trim().toLongOrNull() ?: 0L),
                                "max_frequency" to (parts[3].trim().toLongOrNull() ?: 0L)
                            ))
                        }
                    }
                    call.respond(toJsonElement(mapOf("cores" to cores, "count" to cpuCount)))
                } catch (e: Exception) {
                    AppLogger.e(tag, "cpu-cores GET failed", e)
                    call.respond(HttpStatusCode.InternalServerError,
                        toJsonElement(mapOf("error" to (e.message ?: "Unknown error"),
                            "cores" to emptyList<Map<String, Any>>(), "count" to 0)))
                }
            }

            post("/cpu-cores") {
                try {
                    val p = call.receive<JsonObject>()
                    val enable = p["enable"]?.jsonPrimitive?.booleanOrNull ?: true
                    val value = if (enable) "1" else "0"
                    // Individual commands chained — avoids for loop + sh -c nesting issues inside su -c
                    val cmd = (0..3).joinToString(" && ") { i ->
                        "sh -c 'echo $value > /sys/devices/system/cpu/cpu$i/online'"
                    }
                    val r = ShellQoS.executeAsRoot(cmd)
                    call.respond(toJsonElement(mapOf(
                        "success" to r.isSuccess, "enable" to enable
                    )))
                } catch (e: Exception) {
                    AppLogger.e(tag, "cpu-cores POST failed", e)
                    call.respond(HttpStatusCode.InternalServerError,
                        toJsonElement(mapOf("success" to false, "error" to (e.message ?: "Unknown error"))))
                }
            }

            // ==================== 网络加速 ====================

            post("/net-accelerate") {
                try {
                    val commands = listOf(
                        "iptables -D FORWARD -j zte_fw_net_limit",
                        "tc qdisc del dev sipa_eth0 root",
                        "tc qdisc del dev br0 root",
                        "tc qdisc del dev wlan0 root"
                    )
                    // Batch: 4 shell calls → 1
                    ShellQoS.batchExecuteAsRoot(commands)
                    call.respond(toJsonElement(mapOf(
                        "success" to true, "commands_executed" to commands.size
                    )))
                } catch (e: Exception) {
                    AppLogger.e(tag, "net-accelerate failed", e)
                    call.respond(HttpStatusCode.InternalServerError,
                        toJsonElement(mapOf("success" to false, "error" to (e.message ?: "Unknown error"))))
                }
            }

            // ==================== Phantom Process Killer 禁用 ====================

            post("/disable-phantom-killer") {
                try {
                    // Batch: 2 shell calls → 1
                    val r = ShellQoS.batchExecuteAsRoot(listOf(
                        "settings put global settings_enable_monitor_phantom_procs false",
                        "settings put global max_phantom_processes 2147483647"
                    ))
                    call.respond(toJsonElement(mapOf("success" to r.allSuccess)))
                } catch (e: Exception) {
                    AppLogger.e(tag, "disable-phantom-killer failed", e)
                    call.respond(HttpStatusCode.InternalServerError,
                        toJsonElement(mapOf("success" to false, "error" to (e.message ?: "Unknown error"))))
                }
            }

            // ==================== 带宽限制 ====================

            get("/bandwidth-limit") {
                try {
                    val output = ShellQoS.executeAsRootCached("tc class show dev br0").stdout
                    val targetLine = output.lines().firstOrNull { it.contains("1:10") } ?: ""
                    val rateMatch = Regex("rate (\\d+)mbit").find(targetLine)
                    if (rateMatch != null) {
                        call.respond(toJsonElement(mapOf(
                            "enabled" to true, "mbit" to rateMatch.groupValues[1].toInt()
                        )))
                    } else {
                        call.respond(toJsonElement(mapOf("enabled" to false, "mbit" to 0)))
                    }
                } catch (e: Exception) {
                    AppLogger.e(tag, "bandwidth-limit GET failed", e)
                    call.respond(toJsonElement(mapOf("enabled" to false, "mbit" to 0)))
                }
            }

            post("/bandwidth-limit") {
                try {
                    val p = call.receive<JsonObject>()
                    val mbit = p["mbit"]?.jsonPrimitive?.contentOrNull ?: ""
                    val mbitInt = mbit.toIntOrNull()
                    if (mbitInt == null || mbitInt <= 0) {
                        call.respond(HttpStatusCode.BadRequest,
                            toJsonElement(mapOf("error" to "mbit parameter required (positive integer)")))
                        return@post
                    }
                    val iface = "br0"
                    ShellQoS.executeAsRoot("tc qdisc del dev $iface root")
                    val r1 = ShellQoS.executeAsRoot(
                        "tc qdisc add dev $iface root handle 1: htb default 10"
                    )
                    if (!r1.isSuccess) {
                        call.respond(HttpStatusCode.InternalServerError,
                            toJsonElement(mapOf("success" to false, "error" to "Cannot create root qdisc")))
                        return@post
                    }
                    ShellQoS.executeAsRoot(
                        "tc class add dev $iface parent 1: classid 1:1 htb rate ${mbitInt}mbit ceil ${mbitInt}mbit"
                    )
                    val r3 = ShellQoS.executeAsRoot(
                        "tc class add dev $iface parent 1:1 classid 1:10 htb rate ${mbitInt}mbit ceil ${mbitInt}mbit"
                    )
                    if (!r3.isSuccess) {
                        ShellQoS.executeAsRoot("tc qdisc del dev $iface root")
                        call.respond(HttpStatusCode.InternalServerError,
                            toJsonElement(mapOf("success" to false, "error" to "Cannot create traffic class")))
                        return@post
                    }
                    call.respond(toJsonElement(mapOf("success" to true, "mbit" to mbitInt)))
                } catch (e: Exception) {
                    AppLogger.e(tag, "bandwidth-limit POST failed", e)
                    call.respond(HttpStatusCode.InternalServerError,
                        toJsonElement(mapOf("success" to false, "error" to (e.message ?: "Unknown error"))))
                }
            }

            delete("/bandwidth-limit") {
                try {
                    ShellQoS.executeAsRoot("tc qdisc del dev br0 root")
                    call.respond(toJsonElement(mapOf("success" to true)))
                } catch (e: Exception) {
                    AppLogger.e(tag, "bandwidth-limit DELETE failed", e)
                    call.respond(toJsonElement(mapOf("success" to false, "error" to (e.message ?: "Unknown error"))))
                }
            }

            // ==================== 历史蜂窝流量 ====================

            get("/cellular-usage") {
                try {
                    val startStr = call.request.queryParameters["start"]
                    val endStr = call.request.queryParameters["end"]
                    val startMs = startStr?.toLongOrNull() ?: (System.currentTimeMillis() - 30L * 86400 * 1000)
                    val endMs = endStr?.toLongOrNull() ?: System.currentTimeMillis()

                    val networkStatsManager = appContext.getSystemService(Context.NETWORK_STATS_SERVICE)
                            as? android.app.usage.NetworkStatsManager
                    if (networkStatsManager == null) {
                        call.respond(HttpStatusCode.InternalServerError,
                            toJsonElement(mapOf("error" to "NetworkStatsManager not available")))
                        return@get
                    }

                    val dailyData = mutableListOf<Map<String, Any>>()
                    val cal = Calendar.getInstance()
                    cal.timeInMillis = startMs
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

                    while (cal.timeInMillis <= endMs) {
                        val dayStart = cal.timeInMillis
                        cal.add(Calendar.DAY_OF_MONTH, 1)
                        val dayEnd = minOf(cal.timeInMillis, endMs)
                        try {
                            @Suppress("DEPRECATION")
                            val summary = networkStatsManager.querySummaryForDevice(
                                android.net.ConnectivityManager.TYPE_MOBILE, null, dayStart, dayEnd
                            )
                            val rx = summary?.rxBytes ?: 0L
                            val tx = summary?.txBytes ?: 0L
                            if (rx > 0 || tx > 0) {
                                dailyData.add(mapOf(
                                    "date" to sdf.format(Date(dayStart)),
                                    "rx_bytes" to rx, "tx_bytes" to tx, "total_bytes" to (rx + tx)
                                ))
                            }
                        } catch (_: Exception) {}
                    }
                    call.respond(toJsonElement(mapOf(
                        "data" to dailyData,
                        "start" to sdf.format(Date(startMs)),
                        "end" to sdf.format(Date(endMs)),
                        "count" to dailyData.size
                    )))
                } catch (e: SecurityException) {
                    AppLogger.e(tag, "cellular-usage permission denied", e)
                    call.respond(HttpStatusCode.Forbidden,
                        toJsonElement(mapOf("error" to "PACKAGE_USAGE_STATS permission required")))
                } catch (e: Exception) {
                    AppLogger.e(tag, "cellular-usage failed", e)
                    call.respond(HttpStatusCode.InternalServerError,
                        toJsonElement(mapOf("error" to (e.message ?: "Unknown error"))))
                }
            }
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查进程是否运行（使用 ps + grep 而非 pgrep，兼容性更好）
     */
    private suspend fun isProcessRunning(processName: String): Boolean {
        return try {
            val result = ShellQoS.executeCached("ps -A | grep -v grep | grep $processName")
            result.isSuccess && result.stdout.trim().isNotEmpty()
        } catch (_: Exception) { false }
    }
}
