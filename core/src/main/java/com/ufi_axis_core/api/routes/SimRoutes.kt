package com.ufi_axis_core.api.routes

import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.controller.sim.SimController
import com.ufi_axis_core.core.database.AppDatabase
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
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
    private val goformClient: GoformClient? = null
) {
    fun register(route: Route) {
        // 注意: SMS 路由(/api/sms/*)统一由 RootSmsRoutes 管理，此处仅注册 /sim 路由
        route.route("/sim") {
            // SIM 卡信息
            get("/info") {
                val simInfo = simController.getSimInfo()
                // 尝试从 goform 获取补充 SIM 数据
                val goformData = try { goformClient?.getDeviceInfo() } catch (_: Exception) { null }
                val imsi = simInfo["imsi"]?.toString()?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
                    ?: goformData?.get("imsi")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val imei = simInfo["imei"]?.toString()?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
                    ?: goformData?.get("imei")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val iccid = goformData?.get("iccid")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                call.respond(toJsonElement(mapOf(
                    "sim_state" to (simInfo["sim_state"]?.toString()?.takeIf { !it.equals("UNKNOWN", ignoreCase = true) } ?: ""),
                    "phone_type" to (simInfo["phone_type"] ?: "GSM"),
                    "imei" to (imei ?: ""),
                    "imsi" to (imsi ?: ""),
                    "iccid" to (iccid ?: "")
                )))
            }

            // USSD 查询
            post("/ussd") {
                val params = call.receive<JsonObject>()
                val code = params["code"]?.jsonPrimitive?.contentOrNull ?: ""
                if (code.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest,
                        toJsonElement(mapOf("error" to "code is required")))
                    return@post
                }

                val result = simController.sendUssd(code)
                call.respond(toJsonElement(mapOf(
                    "code" to code,
                    "response" to (result ?: "No response")
                )))
            }

            // SIM 卡槽切换
            post("/switch") {
                val client = goformClient
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
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "slot" to targetSlot))
                )
            }
        }
    }

}
