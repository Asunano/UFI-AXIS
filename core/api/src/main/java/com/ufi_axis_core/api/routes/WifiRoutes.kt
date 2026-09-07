package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.routes.RouteContext
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.controller.goform.AclEntry
import com.ufi_axis_core.controller.goform.AclSnapshot
import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.server.application.*
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
                val p = call.receiveJsonObject()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = wifiClient.setWifiEnabled(enabled)
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "enabled" to enabled)))
            }
            post("/ssid") {
                val params = call.receiveJsonObject()
                val ssid = params["ssid"]?.jsonPrimitive?.contentOrNull ?: ""
                val password = params["password"]?.jsonPrimitive?.contentOrNull
                if (ssid.isEmpty()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "ssid is required")
                    return@post
                }
                val success = networkController.setWifiSSID(ssid, password)
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "ssid" to ssid)))
            }

            post("/password") {
                val params = call.receiveJsonObject()
                val password = params["password"]?.jsonPrimitive?.contentOrNull ?: ""
                if (password.isEmpty()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "password is required")
                    return@post
                }
                val success = networkController.setWifiPassword(password)
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success)))
            }

            // WiFi 完整配置（SSID + 加密 + 密码 + 最大连接数 + 广播 + 芯片）
            post("/config") {
                val p = call.receiveJsonObject()
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

            // WiFi 发射功率
            post("/power") {

                val p = call.receiveJsonObject()
                val level = p["level"]?.jsonPrimitive?.intOrNull ?: 2
                if (level !in 0..2) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "level must be 0-2")
                    return@post
                }
                val success = wifiClient.setWifiPower(level)
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "level" to level)))
            }

            get("/settings") {

                val dh = dataHub
                if (dh == null) {
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "DataHub not available")
                    return@get
                }
                val merged = dh.getWifiSettingsMerged()
                call.respond(toJsonElement(merged))
            }

            // WiFi 模块详细信息（包含 AuthMode/EncrypType/Password 等，与参考项目一致）
            get("/module-info") {
                val info = wifiClient.getWifiModuleInfo()
                call.respond(toJsonElement(info ?: emptyMap<String, Any>()))
            }

            // WiFi 连接二维码（图片字节流，非 JSON）
            // 由设备按当前 SSID/密码实时生成，所以不缓存 —— 改完 WiFi 立刻重新拉就是新的。
            get("/qrcode") {
                val chip = call.request.queryParameters["chip"] ?: "chip1"
                val ssidIndex = call.request.queryParameters["ssid_index"]?.toIntOrNull() ?: 1
                if (chip !in listOf("chip1", "chip2")) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "chip must be chip1 or chip2")
                    return@get
                }
                val qr = wifiClient.getWifiQrCode(chip, ssidIndex)
                if (qr == null) {
                    // 带上真因（HTTP 码 / 非图片 / 连接异常），否则前端只能看到一句无从下手的
                    // "无法从设备读取"，排查必须依赖 adb logcat。
                    val reason = wifiClient.lastQrCodeFailure.takeIf { it.isNotBlank() }
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        if (reason != null) "无法从设备读取 WiFi 二维码：$reason" else "无法从设备读取 WiFi 二维码")
                    return@get
                }
                val (bytes, contentType) = qr
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondBytes(bytes, ContentType.parse(contentType))
            }

            // WiFi 休眠时间
            post("/sleep") {

                val p = call.receiveJsonObject()
                val time = p["time"]?.jsonPrimitive?.contentOrNull ?: "0"
                val outcome = wifiClient.setWifiSleep(time)
                if (call.respondRejected(outcome)) return@post
                val success = outcome.ok
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "time" to time)))
            }

            get("/clients") {
                val dh = dataHub
                if (dh == null) {
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "DataHub not available")
                    return@get
                }
                val clients = dh.wifiQuery { getConnectedClients() }
                call.respond(toJsonElement(clients ?: emptyMap<String, Any>()))
            }

            // ── 接入控制名单（拉黑）──
            // 设备侧只有「整表替换」一条命令，读-改-写因此收敛在这里：
            // 两端客户端只发单台设备的 mac/name，不需要自己拼完整名单（也就不会互相覆盖）。
            // 名单不缓存：条数极少、且拉黑后要立刻能看到结果。
            get("/acl") {
                val acl = wifiClient.getAccessControlList()
                if (acl == null) {
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "无法从设备读取接入控制名单")
                    return@get
                }
                call.respond(toJsonElement(acl.toResponseMap()))
            }

            post("/acl/block") {
                val p = call.receiveJsonObject()
                val mac = p["mac"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val name = p["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (mac.isEmpty()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "mac is required")
                    return@post
                }
                val acl = wifiClient.getAccessControlList()
                if (acl == null) {
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "无法从设备读取接入控制名单")
                    return@post
                }
                // 已在名单里 → 幂等成功（不重复下发，也不把名字改掉）
                if (acl.black.any { it.mac.equalsIgnoreCase(mac) }) {
                    call.respond(toJsonElement(acl.toResponseMap() + mapOf("success" to true)))
                    return@post
                }
                val next = acl.black + AclEntry(mac = mac, name = name)
                val outcome = wifiClient.setAccessControlList(black = next, white = acl.white, mode = acl.mode)
                if (call.respondRejected(outcome)) return@post
                call.respondAclResult(outcome.ok)
            }

            post("/acl/unblock") {
                val p = call.receiveJsonObject()
                val mac = p["mac"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (mac.isEmpty()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "mac is required")
                    return@post
                }
                val acl = wifiClient.getAccessControlList()
                if (acl == null) {
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "无法从设备读取接入控制名单")
                    return@post
                }
                val next = acl.black.filterNot { it.mac.equalsIgnoreCase(mac) }
                if (next.size == acl.black.size) {
                    // 本来就不在名单里 → 幂等成功
                    call.respond(toJsonElement(acl.toResponseMap() + mapOf("success" to true)))
                    return@post
                }
                val outcome = wifiClient.setAccessControlList(black = next, white = acl.white, mode = acl.mode)
                if (call.respondRejected(outcome)) return@post
                call.respondAclResult(outcome.ok)
            }

            post("/acl/clear") {
                val acl = wifiClient.getAccessControlList()
                // 读不回来也照样清（清空是"发空名单"，不依赖当前内容）；白名单只在读到时才保留
                val outcome = wifiClient.setAccessControlList(
                    black = emptyList(),
                    white = acl?.white ?: emptyList(),
                    mode = acl?.mode,
                )
                if (call.respondRejected(outcome)) return@post
                call.respondAclResult(outcome.ok)
            }
        }
    }

    /** 写完立刻回读，返回**设备的真实名单**而不是我们以为写进去的那份。 */
    private suspend fun ApplicationCall.respondAclResult(success: Boolean) {
        val latest = if (success) wifiClient.getAccessControlList() else null
        val body = (latest?.toResponseMap() ?: emptyMap()) + mapOf("success" to success)
        respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError, toJsonElement(body))
    }
}

/** 设备侧的 CamelCase 键名不出 goform 模块，这里转成对外契约的 snake_case。 */
private fun AclSnapshot.toResponseMap(): Map<String, Any?> = mapOf(
    "mode" to mode,
    "black_list" to black.map { mapOf("mac" to it.mac, "name" to it.name) },
    "white_list" to white.map { mapOf("mac" to it.mac, "name" to it.name) },
)

/** MAC 大小写不敏感（设备回读是小写，客户端可能传大写）。 */
private fun String.equalsIgnoreCase(other: String) = equals(other, ignoreCase = true)
