package com.ufi_axis_core.api.routes

import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.controller.network.NetworkController
import com.ufi_axis_core.core.database.AppDatabase
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import kotlinx.serialization.json.*

/**
 * 网络控制路由
 */
class NetworkRoutes(
    private val telephonyCollector: TelephonyCollector,
    private val atChannel: ATChannel,
    private val networkController: NetworkController,
    private val database: AppDatabase,
    private val goformClient: GoformClient? = null,
    private val dataScheduler: com.ufi_axis_core.core.scheduler.DataScheduler? = null
) {
    fun register(route: Route) {
        route.route("/network") {
            // 信号详情 - 统一从 DataScheduler 获取（缓存+三源优先级采集）
            get("/signal") {
                val signal = dataScheduler?.getSignalInfo()
                    ?: telephonyCollector.getSignalInfo()
                call.respond(toJsonElement(signal))
            }

            // 信号历史
            get("/signal/history") {
                val hoursParam = (call.request.queryParameters["hours"] ?: "24").toIntOrNull() ?: 24
                val startTime = System.currentTimeMillis() - hoursParam * 60 * 60 * 1000L
                val records = database.signalDao().getRecordsSince(startTime)
                call.respond(toJsonElement(mapOf(
                    "records" to records,
                    "count" to records.size,
                    "period_hours" to hoursParam
                )))
            }

            // 网络状态
            get("/status") {
                // 优先使用 Goform 数据，fallback 到 TelephonyCollector
                val goformInfo = try {
                    goformClient?.getSignalInfo()
                } catch (_: Exception) { null }
                val goformType = goformInfo?.get("network_type")?.jsonPrimitive?.contentOrNull
                val goformProvider = goformInfo?.get("network_provider")?.jsonPrimitive?.contentOrNull
                // 使用 goform ppp_status 判断实际数据连接状态（PPP 拨号），
                // 而非 Android mobile_data 系统设置（两者独立）
                val pppStatus = try {
                    goformClient?.querySingle("ppp_status")?.jsonPrimitive?.contentOrNull
                } catch (_: Exception) { null }
                val mobileDataEnabled = when {
                    pppStatus != null -> pppStatus.contains("connected", ignoreCase = true) &&
                        !pppStatus.contains("disconnected", ignoreCase = true)
                    else -> telephonyCollector.isMobileDataEnabled()
                }
                call.respond(toJsonElement(mapOf(
                    "network" to networkController.getNetworkStatus(),
                    "mobile_data" to mobileDataEnabled,
                    "ppp_status" to (pppStatus ?: ""),
                    "operator" to (if (!goformProvider.isNullOrBlank()) goformProvider else telephonyCollector.getOperatorName()),
                    "network_type" to (if (goformType != null) GoformClient.mapNetworkType(goformType) else telephonyCollector.getNetworkType())
                )))
            }

            // 开关移动数据
            post("/data") {
                val params = call.receive<JsonObject>()
                val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = networkController.setMobileData(enabled)
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "enabled" to enabled))
                )
            }

            // 开关飞行模式
            post("/airplane") {
                val params = call.receive<JsonObject>()
                val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = networkController.setAirplaneMode(enabled)
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "airplane_mode" to enabled))
                )
            }

            // 锁频 — 使用 LTE_BAND_LOCK / NR_BAND_LOCK goformId (与参考项目一致)
            post("/band") {
                val client = goformClient
                if (client == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "Goform client not available")))
                    return@post
                }
                val params = call.receive<JsonObject>()
                val rat = params["rat"]?.jsonPrimitive?.contentOrNull ?: ""
                val bands = params["bands"]?.jsonPrimitive?.contentOrNull ?: ""
                val action = params["action"]?.jsonPrimitive?.contentOrNull ?: "lock"

                val success = when {
                    action == "unlock" -> {
                        // 解锁 = 设置全部频段
                        val lteAll = "1,3,5,8,34,38,39,40,41"
                        val nrAll = "1,5,8,28,41,78"
                        client.lockLteBands(lteAll) && client.lockNrBands(nrAll)
                    }
                    rat.equals("lte", ignoreCase = true) -> client.lockLteBands(bands)
                    rat.equals("nr", ignoreCase = true) -> client.lockNrBands(bands)
                    rat.equals("all", ignoreCase = true) -> {
                        // bands 里同时包含 LTE 和 NR 频段，分别设置
                        val lteBands = bands.split(",").filter { it.trim().toIntOrNull() in 1..255 }
                            .joinToString(",")
                        val nrBands = bands.split(",").filter { it.trim().startsWith("N", ignoreCase = true) || it.trim().toIntOrNull() in 1..255 }
                            .joinToString(",")
                        var ok = true
                        if (lteBands.isNotEmpty()) ok = client.lockLteBands(lteBands) && ok
                        if (nrBands.isNotEmpty()) ok = client.lockNrBands(nrBands) && ok
                        ok
                    }
                    else -> false
                }
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                    toJsonElement(mapOf("success" to success, "action" to action))
                )
            }

            // 查询当前频段锁定状态
            get("/band-status") {
                val client = goformClient
                if (client == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "Goform client not available")))
                    return@get
                }
                val settings = client.queryDeviceSettings() ?: emptyMap()
                call.respond(toJsonElement(mapOf(
                    "lte_band_lock" to (settings["lte_band_lock"]?.jsonPrimitive?.contentOrNull ?: ""),
                    "nr_band_lock" to (settings["nr_band_lock"]?.jsonPrimitive?.contentOrNull ?: "")
                )))
            }

            // 网络模式 — 使用 SET_BEARER_PREFERENCE goformId (与参考项目一致)
            post("/mode") {
                val client = goformClient
                val params = call.receive<JsonObject>()
                val mode = params["mode"]?.jsonPrimitive?.contentOrNull ?: "AUTO"
                // 映射前端模式到参考项目的 BearerPreference 值
                val bearerValue = when (mode.uppercase()) {
                    "AUTO", "WL_AND_5G" -> "WL_AND_5G"
                    "5G_ONLY", "ONLY_5G", "5G_SA" -> "Only_5G"
                    "5G_NSA", "LTE_AND_5G" -> "LTE_AND_5G"
                    "LTE_ONLY", "ONLY_LTE", "4G_ONLY" -> "Only_LTE"
                    "WCDMA_ONLY", "ONLY_WCDMA" -> "Only_WCDMA"
                    // 参考项目 HTML select: value="WCDMA_AND_LTE" (4G/3G 模式)
                    "LTE_WCDMA", "WCDMA_AND_LTE" -> "WCDMA_AND_LTE"
                    else -> mode // 直接透传
                }
                val success = if (client != null) {
                    client.setBearerPreference(bearerValue)
                } else {
                    // fallback: 使用 AT 命令
                    val atCmd = when (mode.uppercase()) {
                        "AUTO", "WL_AND_5G" -> "AT+ZPREFMOD=1,1,1"
                        "5G_ONLY", "ONLY_5G", "5G_SA" -> "AT+ZPREFMOD=0,0,1"
                        "LTE_ONLY", "ONLY_LTE", "4G_ONLY" -> "AT+ZPREFMOD=0,1,0"
                        "4G_5G", "LTE_NR" -> "AT+ZPREFMOD=0,1,1"
                        "WCDMA_ONLY", "ONLY_WCDMA" -> "AT+ZPREFMOD=2,0,0"
                        else -> return@post call.respond(HttpStatusCode.BadRequest,
                            toJsonElement(mapOf("error" to "Unknown mode: $mode")))
                    }
                    val response = atChannel.sendCommand(atCmd)
                    response?.contains("OK") == true
                }
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "mode" to mode))
                )
            }

            // 承载偏好 (LTE/NSA/SA 等)
            post("/bearer") {
                val client = goformClient
                if (client == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "Goform client not available")))
                    return@post
                }
                val params = call.receive<JsonObject>()
                val preference = params["preference"]?.jsonPrimitive?.contentOrNull ?: "AUTO"
                val success = client.setBearerPreference(preference)
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "preference" to preference))
                )
            }

            // 连接网络 (拨号)
            post("/connect") {
                val client = goformClient
                if (client == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "Goform client not available")))
                    return@post
                }
                val success = client.connectNetwork()
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success))
                )
            }

            // 断开网络
            post("/disconnect") {
                val client = goformClient
                if (client == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "Goform client not available")))
                    return@post
                }
                val success = client.disconnectNetwork()
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success))
                )
            }

            // 连接模式 (手动/自动)
            post("/connection-mode") {
                val client = goformClient
                if (client == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "Goform client not available")))
                    return@post
                }
                val params = call.receive<JsonObject>()
                val mode = params["mode"]?.jsonPrimitive?.contentOrNull ?: "AUTO"
                val success = client.setConnectionMode(mode)
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "mode" to mode))
                )
            }

            // 高铁模式（参考项目 main.js: AT+SP5GCMDS）
            get("/high-rail") {
                val enabled = networkController.getHighRailMode()
                call.respond(toJsonElement(mapOf("enabled" to enabled)))
            }

            // 基站信息（邻区 + 已锁定基站）
            get("/cell-info") {
                val client = goformClient
                if (client == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "Goform client not available")))
                    return@get
                }
                val cellInfo = client.getCellInfo()
                call.respond(toJsonElement(cellInfo ?: emptyMap<String, Any>()))
            }

            post("/high-rail") {
                val params = call.receive<JsonObject>()
                val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = networkController.setHighRailMode(enabled)
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "enabled" to enabled))
                )
            }
        }
    }
}
