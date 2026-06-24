package com.ufi_axis_core.api.routes

import com.ufi_axis_core.controller.goform.GoformSignalClient
import com.ufi_axis_core.controller.goform.GoformSimClient
import com.ufi_axis_core.controller.sim.SimController
import com.ufi_axis_core.core.database.AppDatabase
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.ufi_axis_core.api.DataHub
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.core.cache.CacheTTL
import com.ufi_axis_core.core.cache.ResponseCache
import kotlinx.serialization.json.*

/**
 * SIM 卡路由
 * GET  /api/sim/info    - SIM 卡信息
 * POST /api/sim/ussd    - USSD 查询
 * POST /api/sim/switch  - SIM 卡槽切换
 *
 * 注意: SMS 路由(/api/sms/)统一由 RootSmsRoutes 管理
 */
class SimRoutes(
    private val simController: SimController,
    private val database: AppDatabase,
    private val signalClient: GoformSignalClient? = null,
    private val simClient: GoformSimClient? = null,
    private val cache: ResponseCache? = null,
    private val dataHub: DataHub? = null
) {
    fun register(route: Route) {
        // 注意: SMS 路由(/api/sms/*)统一由 RootSmsRoutes 管理，此处仅注册 /sim 路由
        route.route("/sim") {
            // SIM 卡信息
            get("/info") {
                val result = cache!!.getOrPut("sim:info", CacheTTL.SIM_INFO) {
                    val simInfo = simController.getSimInfo()
                    val goformData = try { dataHub?.signalQuery { getDeviceInfo() } ?: signalClient?.getDeviceInfo() } catch (_: Exception) { null }
                    val imsi = simInfo["imsi"]?.toString()?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
                        ?: goformData?.get("imsi")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    val imei = simInfo["imei"]?.toString()?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
                        ?: goformData?.get("imei")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    val iccid = goformData?.get("iccid")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    toJsonElement(mapOf(
                        "sim_state" to (simInfo["sim_state"]?.toString()?.takeIf { !it.equals("UNKNOWN", ignoreCase = true) } ?: ""),
                        "phone_type" to (simInfo["phone_type"] ?: "GSM"),
                        "imei" to (imei ?: ""),
                        "imsi" to (imsi ?: ""),
                        "iccid" to (iccid ?: "")
                    ))
                }
                call.respond(result)
            }

            // SIM 卡槽切换
            post("/switch") {
                val client = simClient
                if (client == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "Goform client not available")))
                    return@post
                }
                val params = call.receive<JsonObject>()
                // 支持两种方式:
                // 1. goformSlot 直接传 goform 值 (0=移动, 1=电信, 2=联通, 11=外置)
                // 2. slot 传 UI 索引 (1/2), 兼容旧客户端
                val goformSlot = params["goformSlot"]?.jsonPrimitive?.contentOrNull
                val slot = params["slot"]?.jsonPrimitive?.intOrNull
                val targetSlot = goformSlot ?: (if (slot != null && slot >= 1) (slot - 1).toString() else null)
                if (targetSlot == null) {
                    call.respond(HttpStatusCode.BadRequest,
                        toJsonElement(mapOf("error" to "slot must be 1-2 or goformSlot must be 0/1/2/11")))
                    return@post
                }
                val success = client.switchSimSlot(targetSlot)
                if (success) cache?.invalidate("sim:*")
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "slot" to targetSlot))
                )
            }
        }
    }

}
