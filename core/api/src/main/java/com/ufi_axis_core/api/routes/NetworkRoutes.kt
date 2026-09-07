package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.routes.RouteContext
import com.ufi_axis_core.contract.DeviceFields
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.contract.NetworkMode
import com.ufi_axis_core.core.cache.CacheTTL
import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * 网络控制路由
 */
class NetworkRoutes(
    private val ctx: RouteContext
) {
    // ── 反向兼容 getter ──
    private val telephonyCollector get() = ctx.telephonyCollector
    private val networkController get() = ctx.networkController
    private val database get() = ctx.database
    private val goformClient get() = ctx.goformClient
    private val signalClient get() = ctx.signalClient
    private val networkClient get() = ctx.networkClient
    private val dataScheduler get() = ctx.dataScheduler
    private val cache get() = ctx.responseCache
    private val dataHub get() = ctx.dataHub

    fun register(route: Route) {
        route.route("/network") {
            // 信号详情 - 统一从 DataScheduler 获取（缓存+三源优先级采集）
            get("/signal") {
                val signal = dataScheduler?.getSignalInfo()
                    ?: telephonyCollector.getSignalInfo()
                call.respond(toJsonElement(signal))
            }

            // 信号历史（使用轻量查询，仅 SELECT 需要的列）
            get("/signal/history") {
                val hoursParam = (call.request.queryParameters["hours"] ?: "24").toIntOrNull() ?: 24
                val startTime = System.currentTimeMillis() - hoursParam * 60 * 60 * 1000L
                val records = withContext(Dispatchers.IO) {
                    database.signalDao().getLightweightSince(startTime)
                }
                call.respond(toJsonElement(mapOf(
                    "records" to records,
                    "count" to records.size,
                    "period_hours" to hoursParam
                )))
            }

            // 网络状态（通过 DataHub 统一缓存，消除与 DeviceRoutes /info 的重复 goform 查询）
            get("/status") {
                // DataHub.getNetworkTypeInfo() 30s TTL 缓存，合并 network_type + provider + ppp_status 为单次 goform 查询
                val hubInfo = try {
                    dataHub?.getNetworkTypeInfo()
                } catch (e: Exception) {
                    AppLogger.w("NetworkRoutes", "Failed to get network type info: ${e.message}")
                    null
                }

                val goformType = hubInfo?.networkType?.takeIf { it.isNotBlank() }
                val goformProvider = hubInfo?.networkProvider?.takeIf { it.isNotBlank() }
                val pppStatus = hubInfo?.pppStatus
                val mobileDataEnabled = if (hubInfo != null) hubInfo.isPppConnected
                    else telephonyCollector.isMobileDataEnabled()
                call.respond(toJsonElement(mapOf(
                    "network" to networkController.getNetworkStatus(),
                    "mobile_data" to mobileDataEnabled,
                    DeviceFields.Connection.PPP_STATUS to (pppStatus ?: ""),
                    "operator" to (goformProvider ?: telephonyCollector.getOperatorName()),
                    // 值已在 core 侧统一成可读文案（profile 的 NETWORK_TYPE_DECODER），不再套 mapNetworkType
                    DeviceFields.Connection.NETWORK_TYPE to (goformType ?: telephonyCollector.getNetworkType())
                )))
            }

            // 开关移动数据
            post("/data") {
                val params = call.receiveJsonObject()
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
                val params = call.receiveJsonObject()
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
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "DataHub/Goform client not available")
                    return@get
                }
                val result = cache!!.getOrPut("network:band-status", CacheTTL.BAND_STATUS) {
                    // getBandLockStatus() 已归一化（计划书 1.1），这里读的是 canonical key
                    val data = (dh?.signalQuery { getBandLockStatus() } ?: signalClient?.getBandLockStatus()) ?: JsonObject(emptyMap())
                    toJsonElement(mapOf(
                        DeviceFields.BandStatus.LTE_BAND_LOCK to
                            (data[DeviceFields.BandStatus.LTE_BAND_LOCK]?.jsonPrimitive?.contentOrNull ?: ""),
                        DeviceFields.BandStatus.NR_BAND_LOCK to
                            (data[DeviceFields.BandStatus.NR_BAND_LOCK]?.jsonPrimitive?.contentOrNull ?: "")
                    ))
                }
                call.respond(result)
            }

            // 锁定/解锁频段（goform 写入 + AT+SFUN 网络栈重启，无需设备重启）
            post("/band") {
                val params = call.receiveJsonObject()
                val action = params["action"]?.jsonPrimitive?.contentOrNull ?: "lock"

                val result = if (action == "unlock") {
                    networkController.lockBands(null, null, unlockAll = true)
                } else {
                    // 规范格式: { "lte_bands":"1,3", "nr_bands":"41,78" }
                    // 旧格式:   { "rat":"lte", "bands":"1,3" }   → 保留一版兼容
                    val legacyRat = params["rat"]?.jsonPrimitive?.contentOrNull?.lowercase()
                    if (legacyRat != null) {
                        AppLogger.w("NetworkRoutes",
                            "POST /api/network/band 收到旧格式 rat+bands，请改用 lte_bands / nr_bands")
                    }
                    val lteBands = params["lte_bands"]?.jsonPrimitive?.contentOrNull
                        ?: (if (legacyRat == "lte") params["bands"]?.jsonPrimitive?.contentOrNull else null)
                    val nrBands = params["nr_bands"]?.jsonPrimitive?.contentOrNull
                        ?: (if (legacyRat == "nr") params["bands"]?.jsonPrimitive?.contentOrNull else null)
                    networkController.lockBands(lteBands, nrBands)
                }

                // 频段号值域非法（不是 1..255 的纯数字列表）时 profile 直接拒绝、没发请求，
                // 回 400 OUT_OF_RANGE + 原因，跟"设备写失败"区分开（计划书 9.5）。
                result.rejectedReason?.let {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.OUT_OF_RANGE, it)
                    return@post
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
                val params = call.receiveJsonObject()
                val mode = params["mode"]?.jsonPrimitive?.contentOrNull ?: NetworkMode.AUTO
                // 别名 → BearerPreference 的映射唯一实现在 profile 的 WriteSpec 里（计划书 2.6），
                // 这里只算一遍用于回显，不参与下发。
                val bearerValue = NetworkMode.toBearer(mode)
                val success = if (client != null) {
                    val outcome = client.setBearerPreference(mode)
                    if (call.respondRejected(outcome)) return@post
                    outcome.ok
                } else {
                    // goform 不可用时无法设置网络模式（AT+ZPREFMOD 在此设备不支持）
                    AppLogger.w("NetworkRoutes", "Goform client not available, cannot set network mode")
                    false
                }
                if (success) cache?.invalidate("network:band-status")
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    // bearer = 实际下发给设备的值；mode 仍回显入参，便于客户端确认映射结果
                    toJsonElement(mapOf("success" to success, "mode" to mode, "bearer" to bearerValue))
                )
            }


            // 承载偏好 (LTE/NSA/SA 等)
            //
            // 与 /mode 的区别只有回显字段名，值域是同一套 NetworkMode 别名 ——
            // 映射到设备侧 BearerPreference（大小写敏感）由 profile 的 WriteSpec 负责，
            // 映射不出来就直接拒绝下发，不再把客户端猜的值原样丢给设备（计划书 2.6）。
            post("/bearer") {
                val client = networkClient
                if (client == null) {
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "Goform client not available")
                    return@post
                }
                val params = call.receiveJsonObject()
                val preference = params["preference"]?.jsonPrimitive?.contentOrNull ?: NetworkMode.AUTO
                val bearerValue = NetworkMode.toBearer(preference)
                val outcome = client.setBearerPreference(preference)
                if (call.respondRejected(outcome)) return@post
                val success = outcome.ok
                if (success) cache?.invalidate("network:band-status")
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf(
                        "success" to success,
                        "preference" to preference,
                        "bearer" to bearerValue,
                    ))
                )
            }

            // 连接网络 (拨号)
            post("/connect") {
                val client = networkClient
                if (client == null) {
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "Goform client not available")
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
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "Goform client not available")
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
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "Goform client not available")
                    return@post
                }
                val params = call.receiveJsonObject()
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
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "DataHub/Goform client not available")
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
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "DataHub/Goform client not available")
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