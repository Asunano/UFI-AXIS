package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.routes.RouteContext
import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

class WifiRoutes(
    private val ctx: RouteContext
) {
    // ── 反向兼容 getter ──
    private val goformClient get() = ctx.goformClient
    private val wifiClient get() = ctx.wifiClient
    private val networkController get() = ctx.networkController
    private val dataHub get() = ctx.dataHub

    fun register(route: Route) {
        route.route("/wifi") {
            // WiFi 开关
            post("/enable") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = wifiClient.setWifiEnabled(enabled)
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "enabled" to enabled)))
            }
            post("/ssid") {
                val params = call.receive<JsonObject>()
                val ssid = params["ssid"]?.jsonPrimitive?.contentOrNull ?: ""
                val password = params["password"]?.jsonPrimitive?.contentOrNull
                if (ssid.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "ssid is required")))
                    return@post
                }
                val success = networkController.setWifiSSID(ssid, password)
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "ssid" to ssid)))
            }

            post("/password") {
                val params = call.receive<JsonObject>()
                val password = params["password"]?.jsonPrimitive?.contentOrNull ?: ""
                if (password.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "password is required")))
                    return@post
                }
                val success = networkController.setWifiPassword(password)
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success)))
            }

            // WiFi 完整配置（SSID + 加密 + 密码 + 最大连接数 + 广播 + 芯片）
            post("/config") {
                val p = call.receive<JsonObject>()
                val ssid = p["ssid"]?.jsonPrimitive?.contentOrNull
                val authMode = p["auth_mode"]?.jsonPrimitive?.contentOrNull
                val encrypType = p["encryp_type"]?.jsonPrimitive?.contentOrNull
                val passphrase = p["passphrase"]?.jsonPrimitive?.contentOrNull
                val maxStaNum = p["max_sta_num"]?.jsonPrimitive?.intOrNull
                val broadcastDisabled = p["broadcast_disabled"]?.jsonPrimitive?.intOrNull
                val chipIndex = p["chip_index"]?.jsonPrimitive?.contentOrNull
                val success = wifiClient.setWifiConfig(ssid, authMode, encrypType, passphrase, maxStaNum, broadcastDisabled, chipIndex)
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success)))
            }

            // WiFi 高级设置（信道、带宽、模式、国家码）
            post("/adv-config") {
                val p = call.receive<JsonObject>()
                val channel = p["channel"]?.jsonPrimitive?.contentOrNull
                val bandwidth = p["bandwidth"]?.jsonPrimitive?.intOrNull
                val mode = p["mode"]?.jsonPrimitive?.contentOrNull
                val countryCode = p["country_code"]?.jsonPrimitive?.contentOrNull
                val success = wifiClient.setWifiAdvConfig(channel, bandwidth, mode, countryCode)
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success)))
            }

            // WiFi 发射功率
            post("/power") {
                val p = call.receive<JsonObject>()
                val level = p["level"]?.jsonPrimitive?.intOrNull ?: 2
                if (level !in 0..2) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "level must be 0-2")))
                    return@post
                }
                val success = wifiClient.setWifiPower(level)
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "level" to level)))
            }

            // 访客 WiFi
            post("/guest") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull
                val ssid = p["ssid"]?.jsonPrimitive?.contentOrNull
                val authMode = p["auth_mode"]?.jsonPrimitive?.contentOrNull
                val success = wifiClient.setWifiGuest(enabled, ssid, authMode)
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success)))
            }

            get("/settings") {
                val dh = dataHub
                if (dh == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "DataHub not available")))
                    return@get
                }
                val merged = dh.getWifiSettingsMerged(goformClient::base64Decode)
                call.respond(toJsonElement(merged))
            }

            // WiFi 模块详细信息（包含 AuthMode/EncrypType/Password 等，与参考项目一致）
            get("/module-info") {
                val info = wifiClient.getWifiModuleInfo()
                call.respond(toJsonElement(info ?: emptyMap<String, Any>()))
            }

            // WiFi 芯片切换 (2.4G / 5G)
            post("/chip") {
                val p = call.receive<JsonObject>()
                val chip = p["chip"]?.jsonPrimitive?.contentOrNull ?: "chip1"
                if (chip !in listOf("chip1", "chip2")) {
                    call.respond(HttpStatusCode.BadRequest,
                        toJsonElement(mapOf("error" to "chip must be chip1 or chip2")))
                    return@post
                }
                val success = wifiClient.switchWifiChip(chip)
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "chip" to chip)))
            }

            // WiFi NFC 开关
            post("/nfc") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = wifiClient.setWifiNfc(enabled)
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "enabled" to enabled)))
            }

            // WiFi 休眠时间
            post("/sleep") {
                val p = call.receive<JsonObject>()
                val time = p["time"]?.jsonPrimitive?.contentOrNull ?: "0"
                val success = wifiClient.setWifiSleep(time)
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "time" to time)))
            }

            get("/clients") {
                val dh = dataHub
                if (dh == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "DataHub not available")))
                    return@get
                }
                val clients = dh.wifiQuery { getConnectedClients() }
                call.respond(toJsonElement(clients ?: emptyMap<String, Any>()))
            }
        }
    }
}