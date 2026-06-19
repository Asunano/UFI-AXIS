package com.ufi_axis_core.api.routes

import android.content.Context
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.core.database.AppDatabase
import com.ufi_axis_core.core.scheduler.DataScheduler
import com.ufi_axis_core.util.ShellExecutor
import com.ufi_axis_core.util.ShellQoS
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import kotlinx.serialization.json.*

/**
 * 系统资源路由
 * GET /api/system/cpu      - CPU 使用率 + 各核频率
 * GET /api/system/memory   - 内存信息
 * GET /api/system/battery  - 电池信息
 * GET /api/system/storage  - 存储信息
 */
class SystemRoutes(
    private val systemCollector: SystemCollector,
    private val dataScheduler: DataScheduler,
    private val database: AppDatabase,
    private val atChannel: ATChannel? = null,
    private val context: Context? = null
) {
    private val prefs by lazy {
        context?.getSharedPreferences("ufi_axis_vo", Context.MODE_PRIVATE)
    }
    fun register(route: Route) {
        route.route("/system") {
            // CPU 信息（优先从调度器缓存读取，减少开销）
            get("/cpu") {
                val cached = dataScheduler.latestCpu.value
                val cpuInfo = cached ?: systemCollector.getCpuInfo()
                call.respond(toJsonElement(cpuInfo))
            }

            // CPU 历史
            get("/cpu/history") {
                val hoursParam = (call.request.queryParameters["hours"] ?: "24").toIntOrNull() ?: 24
                val startTime = System.currentTimeMillis() - hoursParam * 60 * 60 * 1000L
                val records = database.cpuHistoryDao().getRecordsSince(startTime)
                call.respond(toJsonElement(mapOf(
                    "records" to records,
                    "count" to records.size,
                    "period_hours" to hoursParam
                )))
            }

            // 内存信息
            get("/memory") {
                call.respond(toJsonElement(systemCollector.getMemoryInfo()))
            }

            // 电池信息（优先缓存）
            get("/battery") {
                val cached = dataScheduler.latestBattery.value
                val batteryInfo = if (cached.isNotEmpty()) cached else systemCollector.getBatteryInfo()
                call.respond(toJsonElement(batteryInfo))
            }

            // 存储信息
            get("/storage") {
                call.respond(toJsonElement(systemCollector.getStorageInfo()))
            }

            // 系统运行时间
            get("/uptime") {
                call.respond(toJsonElement(systemCollector.getUptime()))
            }

            // VoLTE 状态 (与参考项目 KanoUtils.kt 一致: AT+CAVIMS?)
            get("/volte") {
                val slot = call.request.queryParameters["slot"]?.toIntOrNull() ?: 0
                val at = atChannel
                val enabled = if (at != null && at.isConnected) {
                    val raw = at.sendCommand("AT+CAVIMS?", 5000) ?: ""
                    // 解析: +CAVIMS: <state>  取第一个逗号分隔值
                    val body = raw.substringBefore("OK")
                        .substringAfter(":", "")
                        .replace("\"", "")
                        .replace("\r", " ")
                        .replace("\n", " ")
                        .trim()
                    body.split(",").map { it.trim() }.getOrNull(0)?.toIntOrNull() == 1
                } else {
                    // fallback: settings
                    val result = ShellQoS.executeAsRootCached("settings get global volte_status_$slot 2>/dev/null")
                    result.stdout.trim() == "1"
                }
                call.respond(toJsonElement(mapOf("enabled" to enabled, "slot" to slot)))
            }

            // 设置 VoLTE (与参考项目 KanoUtils.kt 一致: AT+CAVIMS=$state)
            post("/volte") {
                val p = call.receive<JsonObject>()
                val enabledElem = p["enabled"]?.jsonPrimitive
                val enabledStr = when {
                    enabledElem == null -> "1"
                    enabledElem.isString -> enabledElem.content
                    else -> if (enabledElem.boolean) "1" else "0"
                }
                val enabled = if (enabledStr == "0") "0" else "1"
                val slot = p["slot"]?.jsonPrimitive?.intOrNull ?: 0
                val at = atChannel
                val success = if (at != null && at.isConnected) {
                    at.sendCommand("AT+CAVIMS=$enabled", 5000)?.contains("OK") == true
                } else {
                    ShellQoS.executeAsRoot(
                        "settings put global volte_status_$slot $enabled 2>/dev/null"
                    ).isSuccess
                }
                // 持久化到 SharedPreferences（commit 同步写盘）
                if (success) {
                    prefs?.edit()?.putBoolean("voLte_slot$slot", enabled == "1")?.commit()
                }
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "enabled" to (enabled == "1"), "slot" to slot))
                )
            }

            // VoNR 状态 (与参考项目 KanoUtils.kt 一致: AT+SP5GCMDS="get nr synch_param",42)
            get("/vonr") {
                val slot = call.request.queryParameters["slot"]?.toIntOrNull() ?: 0
                val at = atChannel
                val enabled = if (at != null && at.isConnected) {
                    val raw = at.sendCommand("AT+SP5GCMDS=\"get nr synch_param\",42", 5000) ?: ""
                    // 解析: 取第三个逗号分隔值
                    val body = raw.substringBefore("OK")
                        .substringAfter(":", "")
                        .replace("\"", "")
                        .replace("\r", " ")
                        .replace("\n", " ")
                        .trim()
                    body.split(",").map { it.trim() }.getOrNull(2)?.toIntOrNull() == 1
                } else {
                    val result = ShellQoS.executeAsRootCached("settings get global vonr_status_$slot 2>/dev/null")
                    result.stdout.trim() == "1"
                }
                call.respond(toJsonElement(mapOf("enabled" to enabled, "slot" to slot)))
            }

            // 设置 VoNR (与参考项目 KanoUtils.kt 一致: AT+SP5GCMDS="set nr param",45,$state)
            post("/vonr") {
                val p = call.receive<JsonObject>()
                val enabledElem = p["enabled"]?.jsonPrimitive
                val enabledStr = when {
                    enabledElem == null -> "1"
                    enabledElem.isString -> enabledElem.content
                    else -> if (enabledElem.boolean) "1" else "0"
                }
                val enabled = if (enabledStr == "0") "0" else "1"
                val slot = p["slot"]?.jsonPrimitive?.intOrNull ?: 0
                val at = atChannel
                val success = if (at != null && at.isConnected) {
                    at.sendCommand("AT+SP5GCMDS=\"set nr param\",45,$enabled", 5000)?.contains("OK") == true
                } else {
                    ShellQoS.executeAsRoot(
                        "settings put global vonr_status_$slot $enabled 2>/dev/null"
                    ).isSuccess
                }
                // 持久化到 SharedPreferences（commit 同步写盘）
                if (success) {
                    prefs?.edit()?.putBoolean("voNr_slot$slot", enabled == "1")?.commit()
                }
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "enabled" to (enabled == "1"), "slot" to slot))
                )
            }
        }
    }
}
