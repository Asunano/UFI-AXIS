package com.ufi_axis_core.api.routes

import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.controller.network.NetworkController
import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import kotlinx.serialization.json.*

class WifiRoutes(
    private val goformClient: GoformClient,
    private val networkController: NetworkController
) {
    fun register(route: Route) {
        route.route("/wifi") {
            // WiFi 开关
            post("/enable") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = goformClient.setWifiEnabled(enabled)
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
                val success = goformClient.setWifiConfig(ssid, authMode, encrypType, passphrase, maxStaNum, broadcastDisabled, chipIndex)
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
                val success = goformClient.setWifiAdvConfig(channel, bandwidth, mode, countryCode)
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
                val success = goformClient.setWifiPower(level)
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "level" to level)))
            }

            // 访客 WiFi
            post("/guest") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull
                val ssid = p["ssid"]?.jsonPrimitive?.contentOrNull
                val authMode = p["auth_mode"]?.jsonPrimitive?.contentOrNull
                val success = goformClient.setWifiGuest(enabled, ssid, authMode)
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success)))
            }

            get("/settings") {
                val settings = goformClient.getWifiSettings()
                val moduleInfo = try { goformClient.getWifiModuleInfo() } catch (e: Exception) {
                    AppLogger.w("WifiRoutes", "getWifiModuleInfo failed: ${e.message}")
                    null
                }

                val result = mutableMapOf<String, JsonElement>()
                settings?.forEach { (key, value) -> result[key] = value }

                if (moduleInfo != null) {
                    moduleInfo["WiFiModuleSwitch"]?.let { result["WiFiModuleSwitch"] = it }

                    val responseList = moduleInfo["ResponseList"]?.jsonArray
                    AppLogger.i("WifiRoutes", "ResponseList size=${responseList?.size ?: 0}")

                    if (responseList != null && responseList.isNotEmpty()) {
                        val allAps = responseList.mapNotNull {
                            try { it.jsonObject } catch (_: Exception) { null }
                        }

                        // 优先找到当前活跃的 AP (AccessPointSwitchStatus == "1")
                        val activeAp = allAps.firstOrNull {
                            it["AccessPointSwitchStatus"]?.jsonPrimitive?.contentOrNull == "1"
                        } ?: allAps.firstOrNull()

                        if (activeAp != null) {
                            activeAp["SSID"]?.jsonPrimitive?.contentOrNull?.let {
                                result["wifi_chip1_ssid1_ssid"] = JsonPrimitive(it)
                            }

                            // Password: Base64 解码后返回明文
                            // 修复: 如果 activeAp 密码为空，尝试从其他 AP 获取（设备可能只在特定 chip 上返回密码）
                            val activePwd = activeAp["Password"]?.jsonPrimitive?.contentOrNull
                            val effectivePwd = if (!activePwd.isNullOrBlank()) activePwd
                                else allAps.firstNotNullOfOrNull {
                                    it["Password"]?.jsonPrimitive?.contentOrNull?.takeIf { pwd -> pwd.isNotBlank() }
                                }
                            if (!effectivePwd.isNullOrBlank()) {
                                result["wifi_chip1_ssid1_passphrase"] =
                                    JsonPrimitive(goformClient.base64Decode(effectivePwd))
                            }

                            activeAp["ChipIndex"]?.jsonPrimitive?.contentOrNull?.let { chipIdx ->
                                result["wifi_chip"] = JsonPrimitive(
                                    if (chipIdx == "0") "chip1" else "chip2"
                                )
                            }

                            activeAp["AuthMode"]?.jsonPrimitive?.contentOrNull?.let {
                                result["wifi_chip1_ssid1_auth_mode"] = JsonPrimitive(it)
                            }
                            activeAp["EncrypType"]?.jsonPrimitive?.contentOrNull?.let {
                                result["wifi_chip1_ssid1_encryp_type"] = JsonPrimitive(it)
                            }
                            activeAp["ApMaxStationNumber"]?.jsonPrimitive?.contentOrNull?.let {
                                result["wifi_chip1_ssid1_max_sta_num"] = JsonPrimitive(it)
                            }
                            activeAp["ApBroadcastDisabled"]?.jsonPrimitive?.contentOrNull?.let {
                                result["wifi_chip1_ssid1_broadcast_ssid"] = JsonPrimitive(it)
                            }
                        }

                        // 额外返回每个 chip 的数据（用于频段切换时显示正确信息）
                        allAps.forEach { ap ->
                            val chipIdx = ap["ChipIndex"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            val prefix = if (chipIdx == "0") "chip1" else "chip2"
                            ap["SSID"]?.jsonPrimitive?.contentOrNull?.let {
                                result["wifi_${prefix}_ssid"] = JsonPrimitive(it)
                            }
                            ap["Password"]?.jsonPrimitive?.contentOrNull?.let { b64pwd ->
                                if (b64pwd.isNotBlank()) {
                                    result["wifi_${prefix}_passphrase"] = JsonPrimitive(goformClient.base64Decode(b64pwd))
                                }
                            }
                            ap["AccessPointSwitchStatus"]?.jsonPrimitive?.contentOrNull?.let {
                                result["wifi_${prefix}_active"] = JsonPrimitive(it == "1")
                            }
                        }
                    }
                } else {
                    AppLogger.w("WifiRoutes", "moduleInfo is null, relying on flat fields only")
                }

                call.respond(toJsonElement(result))
            }

            // WiFi 模块详细信息（包含 AuthMode/EncrypType/Password 等，与参考项目一致）
            get("/module-info") {
                val info = goformClient.getWifiModuleInfo()
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
                val success = goformClient.switchWifiChip(chip)
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "chip" to chip)))
            }

            // WiFi NFC 开关
            post("/nfc") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = goformClient.setWifiNfc(enabled)
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "enabled" to enabled)))
            }

            // WiFi 休眠时间
            post("/sleep") {
                val p = call.receive<JsonObject>()
                val time = p["time"]?.jsonPrimitive?.contentOrNull ?: "0"
                val success = goformClient.setWifiSleep(time)
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "time" to time)))
            }

            get("/clients") {
                val clients = goformClient.getConnectedClients()
                call.respond(toJsonElement(clients ?: emptyMap<String, Any>()))
            }
        }
    }
}