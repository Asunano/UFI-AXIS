package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.routes.RouteContext
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.devicespi.adapter.AclEntry
import com.ufi_axis_core.devicespi.adapter.AclSnapshot
import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class WifiRoutes(
    private val ctx: RouteContext
) {
    // ── 反向兼容 getter ──
    private val goformClient get() = ctx.goformClient
    /**
     * WiFi 域的设备适配接口 —— **读写都走这里**（写侧批 A2b 迁入，
     * 读侧与 `setAccessControlList` 批 C2 迁入）。
     */
    private val wifi get() = ctx.deviceHub.wifi
    private val networkController get() = ctx.networkController
    private val dataHub get() = ctx.dataHub

    fun register(route: Route) {
        route.route("/wifi") {
            // WiFi 开关
            post("/enable") {
                val p = call.receiveJsonObject()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = wifi.setWifiEnabled(enabled)
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
                val outcome = wifi.setWifiConfig(
                    ssid, authMode, encrypType, passphrase, maxStaNum, broadcastDisabled, chipIndex
                )
                // 设备拒绝（密码位数 / 加密组合非法这类）回 400 + 原因，与 /sleep、/acl/* 同口径。
                // 原来这里把三态压成 Boolean 再一律回 500，客户端只能显示一句 HTTP 500。
                if (call.respondRejected(outcome)) return@post
                val success = outcome.ok
                if (success) dataHub?.invalidateWifi()
                call.respondWifiConfigResult(success)
            }

            // WiFi 发射功率
            post("/power") {

                val p = call.receiveJsonObject()
                val level = p["level"]?.jsonPrimitive?.intOrNull ?: 2
                if (level !in 0..2) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "level must be 0-2")
                    return@post
                }
                val success = wifi.setWifiPower(level)
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
                val info = wifi.getWifiModuleInfo()
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
                val qr = wifi.getWifiQrCode(chip, ssidIndex)
                if (qr == null) {
                    // 带上真因（HTTP 码 / 非图片 / 连接异常），否则前端只能看到一句无从下手的
                    // "无法从设备读取"，排查必须依赖 adb logcat。
                    val reason = wifi.lastQrCodeFailure.takeIf { it.isNotBlank() }
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
                val outcome = wifi.setWifiSleep(time)
                if (call.respondRejected(outcome)) return@post
                val success = outcome.ok
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "time" to time)))
            }

            // WiFi 频段切换（chip1 = 2.4G / chip2 = 5G）。
            //
            // 设备侧这条命令（switchWiFiChip&ChipEnum=X&GuestEnable=0）等于「在该频段上启用
            // WiFi」—— 与「打开 WiFi」是同一条命令，会重启 WiFi 模块，**正连着 WiFi 的客户端
            // 会掉线**。要不要先跟用户确认是 UI 侧的事，这里不加确认语义。
            //
            // 取值域判断只有一份：SettingKey.WIFI_BAND 的 validate（只收 chip1 / chip2，
            // 不收 2.4G / 5G / 0 / 1）。所以缺参数也照样往下传 —— 空串过不了 validate，
            // 由 respondRejected 回 400 + 原因，与 /config、/sleep、/acl/* 同口径。
            post("/band") {
                val p = call.receiveJsonObject()
                val chip = p["chip"]?.jsonPrimitive?.contentOrNull ?: ""
                val outcome = wifi.setWifiBand(chip)
                if (call.respondRejected(outcome)) return@post
                val success = outcome.ok
                if (success) dataHub?.invalidateWifi()
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success)))
            }

            get("/clients") {
                val dh = dataHub
                if (dh == null) {
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "DataHub not available")
                    return@get
                }
                val clients = dh.wifiQuery { getConnectedClients() }?.values
                call.respond(toJsonElement(clients ?: emptyMap<String, Any>()))
            }

            // ── 接入控制名单（拉黑）──
            // 设备侧只有「整表替换」一条命令，读-改-写因此收敛在这里：
            // 两端客户端只发单台设备的 mac/name，不需要自己拼完整名单（也就不会互相覆盖）。
            // 名单不缓存：条数极少、且拉黑后要立刻能看到结果。
            get("/acl") {
                val acl = wifi.getAccessControlList()
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
                val acl = wifi.getAccessControlList()
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
                val outcome = wifi.setAccessControlList(black = next, white = acl.white, mode = acl.mode)
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
                val acl = wifi.getAccessControlList()
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
                val outcome = wifi.setAccessControlList(black = next, white = acl.white, mode = acl.mode)
                if (call.respondRejected(outcome)) return@post
                call.respondAclResult(outcome.ok)
            }

            post("/acl/clear") {
                val acl = wifi.getAccessControlList()
                // 读不回来也照样清（清空是"发空名单"，不依赖当前内容）；白名单只在读到时才保留
                val outcome = wifi.setAccessControlList(
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
        val latest = if (success) wifi.getAccessControlList() else null
        val body = (latest?.toResponseMap() ?: emptyMap()) + mapOf("success" to success)
        respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError, toJsonElement(body))
    }

    /**
     * 写完立刻回读，返回**设备的真实 WiFi 配置**而不是我们以为写进去的那份（同 [respondAclResult]）。
     *
     * 字段名与 `GET /api/wifi/settings` 是同一套（两边都是
     * [com.ufi_axis_core.devicespi.adapter.WifiControl.getWifiSettingsMerged] 的归一化结果），
     * 所以客户端可以直接拿这份响应刷新界面，少一个来回。
     *
     * 刻意**绕开 dataHub**：`dataHub.getWifiSettingsMerged()` 会把结果写进 30s 缓存，而此刻设备
     * 可能还没应用完新配置 —— 把这份"读回来还是旧值"的结果缓存起来，客户端随后的回读会在
     * 整个 TTL 内一直拿到旧值（表现就是"保存成功但界面还是旧 SSID"）。这里只用它填响应体，
     * 上面刚 `invalidateWifi()` 清掉的缓存保持失效状态。
     *
     * 回读本身失败不影响写结果：写已经成功了，这时把整个请求变成 500 是谎报。
     */
    private suspend fun ApplicationCall.respondWifiConfigResult(success: Boolean) {
        val latest = if (success) {
            try {
                wifi.getWifiSettingsMerged().values
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("WifiRoutes", "WiFi 配置写入成功但回读失败：${e.message}")
                null
            }
        } else {
            null
        }
        val body = buildJsonObject {
            latest?.forEach { (k, v) -> put(k, v) }
            put("success", success)
        }
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
