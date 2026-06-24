package com.ufi_axis_core.api.routes

import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.controller.goform.GoformNetworkClient
import com.ufi_axis_core.controller.goform.GoformSignalClient
import com.ufi_axis_core.controller.network.NetworkController
import com.ufi_axis_core.core.database.AppDatabase
import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.ufi_axis_core.api.DataHub
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.core.cache.CacheTTL
import com.ufi_axis_core.core.cache.ResponseCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * 网络控制路由
 */
class NetworkRoutes(
    private val telephonyCollector: TelephonyCollector,
    private val networkController: NetworkController,
    private val database: AppDatabase,
    private val goformClient: GoformClient? = null,
    private val signalClient: GoformSignalClient? = null,
    private val networkClient: GoformNetworkClient? = null,
    private val dataScheduler: com.ufi_axis_core.core.scheduler.DataScheduler? = null,
    private val cache: ResponseCache? = null,
    private val dataHub: DataHub? = null
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
                val records = withContext(Dispatchers.IO) {
                    database.signalDao().getRecordsSince(startTime)
                }
                call.respond(toJsonElement(mapOf(
                    "records" to records,
                    "count" to records.size,
                    "period_hours" to hoursParam
                )))
            }

            // 网络状态（通过 DataHub 统一缓存，消除与 DeviceRoutes /info 的重复 goform 查询）
            get("/status") {
                // DataHub.getNetworkTypeInfo() 10s TTL 缓存，合并 network_type + provider + ppp_status 为单次 goform 查询
                val hubInfo = try {
                    dataHub?.getNetworkTypeInfo()
                } catch (_: Exception) { null }

                val goformType = hubInfo?.networkType?.takeIf { it.isNotBlank() }
                val goformProvider = hubInfo?.networkProvider?.takeIf { it.isNotBlank() }
                val pppStatus = hubInfo?.pppStatus
                val mobileDataEnabled = if (hubInfo != null) hubInfo.isPppConnected
                    else telephonyCollector.isMobileDataEnabled()
                call.respond(toJsonElement(mapOf(
                    "network" to networkController.getNetworkStatus(),
                    "mobile_data" to mobileDataEnabled,
                    "ppp_status" to (pppStatus ?: ""),
                    "operator" to (goformProvider ?: telephonyCollector.getOperatorName()),
                    "network_type" to (if (goformType != null) GoformClient.mapNetworkType(goformType) else telephonyCollector.getNetworkType())
                )))
            }

            // 开关移动数据
            post("/data") {
                val params = call.receive<JsonObject>()
                val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = networkController.setMobileData(enabled)
                if (success) dataHub?.invalidateNetwork()  // 清除 ppp_status 缓存，使下次 /status 查询到最新连接状态
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

            // ═══════════ 频段锁定（goform + AT+SFUN 网络栈重启，无需设备重启）═══════════
            // GET  /network/band-status → 查询当前 lte_band_lock / nr_band_lock（goform 只读）
            // POST /network/band          → 通过 goform 写入 + AT+SFUN 重启网络协议栈立即生效
            //   返回: { success, mode, needs_reboot: false, network_restarted }

            // 查询当前频段锁定状态（通过 DataHub 统一获取）
            get("/band-status") {
                val dh = dataHub
                if (dh == null && signalClient == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "DataHub/Goform client not available")))
                    return@get
                }
                val result = cache!!.getOrPut("network:band-status", CacheTTL.BAND_STATUS) {
                    val data = (dh?.signalQuery { getBandLockStatus() } ?: signalClient?.getBandLockStatus()) ?: JsonObject(emptyMap())
                    toJsonElement(mapOf(
                        "lte_band_lock" to (data["lte_band_lock"]?.jsonPrimitive?.contentOrNull ?: ""),
                        "nr_band_lock" to (data["nr_band_lock"]?.jsonPrimitive?.contentOrNull ?: "")
                    ))
                }
                call.respond(result)
            }

            // 锁定/解锁频段（goform 写入 + AT+SFUN 网络栈重启，无需设备重启）
            post("/band") {
                val params = call.receive<JsonObject>()
                val action = params["action"]?.jsonPrimitive?.contentOrNull ?: "lock"

                val result = if (action == "unlock") {
                    networkController.lockBands(null, null, unlockAll = true)
                } else {
                    // 支持两种请求格式：
                    // 新格式: { "lte_bands":"1,3", "nr_bands":"41,78" }
                    // 旧格式: { "rat":"lte", "bands":"1,3" }   → 兼容旧客户端
                    val lteBands = params["lte_bands"]?.jsonPrimitive?.contentOrNull
                        ?: (if (params["rat"]?.jsonPrimitive?.contentOrNull?.lowercase() == "lte")
                            params["bands"]?.jsonPrimitive?.contentOrNull else null)
                    val nrBands = params["nr_bands"]?.jsonPrimitive?.contentOrNull
                        ?: (if (params["rat"]?.jsonPrimitive?.contentOrNull?.lowercase() == "nr")
                            params["bands"]?.jsonPrimitive?.contentOrNull else null)
                    networkController.lockBands(lteBands, nrBands)
                }

                if (result.success) {
                    cache?.invalidate("network:band-status")
                }
                call.respond(
                    if (result.success) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                    toJsonElement(mapOf(
                        "success" to result.success,
                        "action" to action,
                        "mode" to result.mode,
                        "needs_reboot" to false,
                        "network_restarted" to result.stackRestarted
                    ))
                )
            }

            // 网络模式 — 使用 SET_BEARER_PREFERENCE goformId (与参考项目一致)
            post("/mode") {
                val client = networkClient
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
                    // goform 不可用时无法设置网络模式（AT+ZPREFMOD 在此设备不支持）
                    AppLogger.w("NetworkRoutes", "Goform client not available, cannot set network mode")
                    false
                }
                if (success) cache?.invalidate("network:band-status")
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "mode" to mode))
                )
            }

            // 承载偏好 (LTE/NSA/SA 等)
            post("/bearer") {
                val client = networkClient
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
                val client = networkClient
                if (client == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "Goform client not available")))
                    return@post
                }
                val success = client.connectNetwork()
                if (success) dataHub?.invalidateNetwork()
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success))
                )
            }

            // 断开网络
            post("/disconnect") {
                val client = networkClient
                if (client == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "Goform client not available")))
                    return@post
                }
                val success = client.disconnectNetwork()
                if (success) dataHub?.invalidateNetwork()
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success))
                )
            }

            // 连接模式 (手动/自动)
            post("/connection-mode") {
                val client = networkClient
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

            // ═══════════ 基站/小区信息 ═══════════
            // GET /network/cell-info      → 完整基站信息（邻区 + 已锁定基站 + 当前服务小区）
            // GET /network/neighbor-cells → 仅邻区列表（快速刷新，无缓存）

            // 基站信息（通过 DataHub 统一获取）
            get("/cell-info") {
                val dh = dataHub
                if (dh == null && signalClient == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "DataHub/Goform client not available")))
                    return@get
                }
                val result = cache!!.getOrPut("network:cell-info", CacheTTL.CELL_INFO) {
                    val cellInfo = dh?.signalQuery { getCellInfo() } ?: signalClient?.getCellInfo()
                    toJsonElement(cellInfo ?: emptyMap<String, Any>())
                }
                call.respond(result)
            }

            // 仅邻区列表（不缓存，每次实时查询）
            get("/neighbor-cells") {
                val dh = dataHub
                if (dh == null && signalClient == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "DataHub/Goform client not available")))
                    return@get
                }
                val neighbors = dh?.signalQuery { getNeighborCellInfo() } ?: signalClient?.getNeighborCellInfo()
                call.respond(toJsonElement(mapOf(
                    "neighbor_cell_info" to (neighbors ?: JsonArray(emptyList()))
                )))
            }

        }
    }
}
