package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.requireCapability
import com.ufi_axis_core.api.routes.RouteContext
import com.ufi_axis_core.contract.Capability
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
    // 批 B2 起走 signal 域的设备适配接口（原来是 ctx.signalClient = GoformSignalClient）。
    // 名字刻意不改：路由体里的调用点一行没动，方法名与语义逐字相同。
    private val signalClient get() = ctx.deviceHub.signal
    // 批 A2b 起走 network 域的设备适配接口（原来是 ctx.networkClient = GoformNetworkClient）。
    private val network get() = ctx.deviceHub.network
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
                    val data = (dh?.signalQuery { getBandLockStatus() } ?: signalClient.getBandLockStatus())
                        ?.values ?: JsonObject(emptyMap())
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
                // 能力门禁（3.3）：缺 band_lock → 501 NOT_SUPPORTED。
                // lock 与 unlock 都在这一个端点上，所以这一处就覆盖了整个频段锁定域。
                dataHub.deviceCapabilities.requireCapability(Capability.BAND_LOCK)
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
            //
            // 失败不再一律回 500：设备侧的三种失败（参数越界 / 会话失效可重试 / 设备明确拒绝）
            // 由 respondWriteFailure 映射成 400 / 503 / 502 + 明确的 ErrorCode + 中文文案。
            // 之前一律 500 让客户端只能显示"服务器内部错误"，而真机上最常见的那一种
            // （会话失效）其实是"再点一次就好"，用户完全无从判断 —— 这就是"第一次必定 500"
            // 被当成 core 故障的由来。
            post("/mode") {
                // 能力门禁（3.3）：缺 network_mode → 501 NOT_SUPPORTED。
                //
                // 本域的另一个写入口 /bearer **同样要拦**：Capability 是功能域，
                // 域内所有写入口都必须被同一个门禁覆盖，漏一个就等于留了一条绕过门禁的路。
                // 判据不是「这条设备命令拦过了没有」，而是「这台设备支不支持这个功能域」。
                dataHub.deviceCapabilities.requireCapability(Capability.NETWORK_MODE)
                // 2026-09-25 P3-11：这里原来还有一段 `val client = network; if (client == null) 503`。
                // `network` 是 `ctx.deviceHub.network`，类型 `NetworkControl` **非空** ——
                // 那个分支永不成立，是一段死代码；而它回的那句 503 文案还把协议名
                // （"Goform client not available"）吐给了客户端，对外不该出现（同批 SimRoutes 的
                // KDoc 已记录这条理由）。通道级不可用现在由 `respondWriteFailure` 按
                // WriteOutcome 映射（会话失效 → 503 可重试），不需要这一层前置判空。
                val params = call.receiveJsonObject()
                val mode = params["mode"]?.jsonPrimitive?.contentOrNull ?: NetworkMode.AUTO
                // 别名 → BearerPreference 的映射唯一实现在 profile 的 WriteSpec 里（计划书 2.6），
                // 这里只算一遍用于回显，不参与下发。
                val bearerValue = NetworkMode.toBearer(mode)
                // bearer = 实际下发给设备的值；mode 仍回显入参；mode_label = contract 中文名
                // （App/Web 展示统一读它，不再各存一份文案表）。
                val echo = mapOf<String, Any?>(
                    "mode" to mode,
                    "bearer" to bearerValue,
                    "mode_label" to NetworkMode.label(mode)
                )
                val outcome = network.setBearerPreference(mode)
                if (call.respondWriteFailure(outcome, "设备拒绝了本次网络制式切换", echo)) return@post
                invalidateAfterModeWrite()
                call.respond(toJsonElement(buildMap<String, Any?> {
                    put("success", true)
                    putAll(echo)
                }))
            }


            // 承载偏好 (LTE/NSA/SA 等)
            //
            // 与 /mode 的区别只有回显字段名，值域是同一套 NetworkMode 别名 ——
            // 映射到设备侧 BearerPreference（大小写敏感）由 profile 的 WriteSpec 负责，
            // 映射不出来就直接拒绝下发，不再把客户端猜的值原样丢给设备（计划书 2.6）。
            // 失败映射与 /mode 共用 respondWriteFailure，两个端点的错误码保持一致。
            post("/bearer") {
                // 能力门禁（3.3）：缺 network_mode → 501 NOT_SUPPORTED。
                //
                // 与 /mode 各拦一次，**不是重复**：Capability 是**功能域**，
                // 域内**所有写入口**都必须被同一个门禁覆盖。放行其中一个入口，
                // 等于给前端留了一条绕过门禁、把请求打到设备再失败的路 ——
                // 那正是阶段 3 要消除的失败模式。
                // 「同一条设备命令只拦一次」在这里不成立：拦的不是命令（这两个端点确实共用
                // SET_BEARER_PREFERENCE），而是**这台设备支不支持「切换网络制式」这个功能域**。
                dataHub.deviceCapabilities.requireCapability(Capability.NETWORK_MODE)
                // P3-11：与 /mode 同一处死分支（`network` 非空），一起删掉，理由见那里。
                val params = call.receiveJsonObject()
                val preference = params["preference"]?.jsonPrimitive?.contentOrNull ?: NetworkMode.AUTO
                val echo = mapOf<String, Any?>(
                    "preference" to preference,
                    "bearer" to NetworkMode.toBearer(preference)
                )
                val outcome = network.setBearerPreference(preference)
                if (call.respondWriteFailure(outcome, "设备拒绝了本次承载偏好设置", echo)) return@post
                invalidateAfterModeWrite()
                call.respond(toJsonElement(buildMap<String, Any?> {
                    put("success", true)
                    putAll(echo)
                }))
            }

            // 连接网络 (拨号)
            post("/connect") {
                // P3-11：原来这里有一段 `if (network == null) 503 "Goform client not available"`。
                // `network` 非空 → 分支永不成立，且那句文案把协议名吐给了客户端。
                val success = network.connectNetwork()
                if (success) dataHub.invalidateNetwork()
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success))
                )
            }

            // 断开网络
            post("/disconnect") {
                // P3-11：同 /connect，死分支 + 泄漏协议名的文案一起删。
                val success = network.disconnectNetwork()
                if (success) dataHub.invalidateNetwork()
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success))
                )
            }

            // 连接模式 (手动/自动)
            post("/connection-mode") {
                // P3-11：同 /connect，死分支 + 泄漏协议名的文案一起删。
                val params = call.receiveJsonObject()
                val requested = params["mode"]?.jsonPrimitive?.contentOrNull
                // 设备侧 ConnectionMode 只认 auto_dial / manual_dial。两端历史上各发一套
                // （web 发 auto_dial/manual_dial，app 发 auto/manual），而这里原来原样透传，
                // 于是 app 那条静默无效；缺省值还写着 "AUTO"，同样不是设备取值。
                // 归一化只放这一处（core 负责业务判定，客户端不必再各自映射）。
                val deviceMode = when (requested?.trim()?.lowercase()) {
                    "manual", "manual_dial", "hand", "1" -> "manual_dial"
                    else -> "auto_dial"
                }
                val success = network.setConnectionMode(deviceMode)
                // connection_mode 就在 device:settings 里，写完必须清，否则客户端回读到的是
                // 最长 5 分钟前的旧值（与制式切换同一个坑）。
                if (success) cache?.invalidate("device:settings")
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "mode" to deviceMode))
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
                    val cellInfo = (dh?.signalQuery { getCellInfo() } ?: signalClient.getCellInfo())?.values
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
                val neighbors = dh?.signalQuery { getNeighborCellInfo() } ?: signalClient.getNeighborCellInfo()
                call.respond(toJsonElement(mapOf(
                    "neighbor_cell_info" to (neighbors ?: JsonArray(emptyList()))
                )))
            }

        }
    }

    /**
     * 制式/承载偏好写成功后要清掉的读缓存。
     *
     * 2026-09-15 缺陷：原来只清了 `network:band-status`，而客户端确认切换是否生效读的是
     * `GET /api/device/settings`（`device:settings`，TTL 5 分钟）与 `GET /api/network/status`
     * （`hub:network-type-info`，TTL 30 秒）。两者都没清，于是 App/Web 的回读确认在整个
     * 预算窗口内拿到的都是**写入前的快照**，必然报「设备尚未完成切换」，而设备其实早切完了，
     * 等缓存自然过期界面才自己变对 —— 这就是用户看到的「切换成功却提示失败」。
     *
     * 三个 key 一起清是硬要求：少清任何一个，对应那个端点就会继续回旧值。
     */
    private suspend fun invalidateAfterModeWrite() {
        cache?.invalidate("device:settings")
        cache?.invalidate("network:band-status")
        cache?.invalidateAny("hub:network-type-info")
    }
}