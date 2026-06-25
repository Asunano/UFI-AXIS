package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.routes.RouteContext
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.core.cache.CacheTTL
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import com.ufi_axis_core.util.ShellQoS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设备信息路由
 * GET /api/device/info - 完整设备信息
 * GET /api/device/goform - Goform 设备状态
 */
class DeviceRoutes(
    private val ctx: RouteContext
) {
    // ── 反向兼容 getter，使已有方法无需修改 ──
    private val systemCollector get() = ctx.systemCollector
    private val telephonyCollector get() = ctx.telephonyCollector
    private val atChannel get() = ctx.atChannel
    private val goformClient get() = ctx.goformClient
    private val signalClient get() = ctx.signalClient
    private val networkClient get() = ctx.networkClient
    private val deviceClient get() = ctx.deviceClient
    private val systemController get() = ctx.systemController
    private val cache get() = ctx.responseCache
    private val dataHub get() = ctx.dataHub
    private val settings get() = ctx.settings

    fun register(route: Route) {
        route.route("/device") {
            get("/info") {
                suspend fun fetch(): JsonElement {
                    val deviceInfo = systemController.getDeviceModel()
                    val simInfo = telephonyCollector.getSimInfo()
                    val storageInfo = systemCollector.getStorageInfo()
                    val uptime = systemCollector.getUptime()
                    val atInfo = atChannel.getPlatformInfo()
                    val kernelVersion = systemController.getKernelVersion()
                    // 通过 DataHub 获取网络类型信息（10s TTL 缓存，与 NetworkRoutes /status 共享）
                    val hubInfo = try { dataHub?.getNetworkTypeInfo() } catch (e: Exception) { AppLogger.w("DeviceRoutes", "Failed to get network type info: ${e.message}"); null }
                    val goformType = hubInfo?.networkType?.takeIf { it.isNotBlank() }
                    val goformProvider = hubInfo?.networkProvider?.takeIf { it.isNotBlank() }
                    // 从 DataHub 获取设备身份信息（已通过 ResponseCache 缓存）
                    val identity = try { dataHub?.getDeviceIdentity() } catch (e: Exception) { AppLogger.w("DeviceRoutes", "Failed to get device identity: ${e.message}"); null }
                    return toJsonElement(mapOf(
                        "device" to deviceInfo, "sim" to simInfo, "storage" to storageInfo,
                        "uptime" to uptime, "at_channel" to atInfo, "kernel" to kernelVersion,
                        "network" to mapOf(
                            "operator" to (goformProvider ?: telephonyCollector.getOperatorName()),
                            "type" to (if (goformType != null) GoformClient.mapNetworkType(goformType) else telephonyCollector.getNetworkType()),
                            "connected" to telephonyCollector.isNetworkAvailable()
                        ),
                        "identity" to identity
                    ))
                }
                call.respond(cache?.let { it.getOrPut("device:info", CacheTTL.DEVICE_INFO) { fetch() } } ?: fetch())
            }

            // Goform 设备状态（通过 DataHub 获取准确数据）
            get("/goform") {
                suspend fun f(): JsonElement {
                    val data = dataHub?.signalQuery { getFullStatus() } ?: signalClient.getFullStatus()
                    return toJsonElement(data ?: emptyMap<String, Any>())
                }
                call.respond(if (cache != null) cache.getOrPut("device:goform", CacheTTL.GOFORM_FULL_STATUS) { f() } else f())
            }

            // 设备身份信息（通过 DataHub 获取，与 NetworkRoutes 共享缓存）
            get("/identity") {
                suspend fun f(): JsonElement {
                    val data = dataHub?.getDeviceIdentity() ?: signalClient.getDeviceIdentity()
                    return toJsonElement(data ?: emptyMap<String, Any>())
                }
                call.respond(if (cache != null) cache.getOrPut("device:identity", CacheTTL.DEVICE_IDENTITY) { f() } else f())
            }

            // 设备固件版本（通过 DataHub 获取）
            get("/version") {
                suspend fun f(): JsonElement {
                    val data = dataHub?.signalQuery { getDeviceVersion() } ?: signalClient.getDeviceVersion()
                    return toJsonElement(mapOf(
                        "language" to (data?.get("Language")?.jsonPrimitive?.contentOrNull ?: ""),
                        "cr_version" to (data?.get("cr_version")?.jsonPrimitive?.contentOrNull ?: ""),
                        "wa_inner_version" to (data?.get("wa_inner_version")?.jsonPrimitive?.contentOrNull ?: "")
                    ))
                }
                call.respond(if (cache != null) cache.getOrPut("device:version", CacheTTL.DEVICE_VERSION) { f() } else f())
            }

            get("/model") {
                call.respond(toJsonElement(systemController.getDeviceModel()))
            }

            get("/magisk") {
                call.respond(toJsonElement(systemController.getMagiskStatus()))
            }

            // 重启设备
            post("/reboot") {
                cache?.invalidate("device:*")
                val success = systemController.reboot()
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success))
                )
            }

            // 恢复出厂设置
            post("/factory-reset") {
                cache?.invalidate("*")
                val success = deviceClient.factoryReset()
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success))
                )
            }

            // 调试模式 (ADB)
            post("/debug") {
                val params = call.receive<JsonObject>()
                val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = deviceClient.setDebugMode(enabled)
                if (success) cache?.invalidate("device:settings")
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "enabled" to enabled))
                )
            }

            // USB 模式切换
            post("/usb-mode") {
                val params = call.receive<JsonObject>()
                val mode = params["mode"]?.jsonPrimitive?.intOrNull ?: 0
                val success = deviceClient.setUsbMode(mode)
                if (success) cache?.invalidate("device:settings")
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "mode" to mode))
                )
            }

            // 修改管理密码
            post("/password") {
                val p = call.receive<JsonObject>()
                val oldPwd = p["old_password"]?.jsonPrimitive?.contentOrNull ?: ""
                val newPwd = p["new_password"]?.jsonPrimitive?.contentOrNull ?: ""
                if (oldPwd.isEmpty() || newPwd.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest,
                        toJsonElement(mapOf("error" to "old_password and new_password are required")))
                    return@post
                }
                val success = deviceClient.changePassword(oldPwd, newPwd)
                if (success) {
                    // 同步更新后端本地存储的 goform 密码，使后续请求无需重启即可生效
                    settings?.goformPassword = newPwd
                    goformClient.updateGoformPassword(newPwd)
                    cache?.invalidate("device:*")
                }
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success)))
            }

            // APN 配置（完整字段，与参考项目 saveAPNProfile + switchAPNAuto 一致）
            post("/apn") {
                val p = call.receive<JsonObject>()
                val autoSelect = p["auto_select"]?.jsonPrimitive?.booleanOrNull
                val index = p["index"]?.jsonPrimitive?.intOrNull ?: 0
                val profileName = p["profile_name"]?.jsonPrimitive?.contentOrNull
                val apn = p["apn"]?.jsonPrimitive?.contentOrNull
                val username = p["username"]?.jsonPrimitive?.contentOrNull
                val password = p["password"]?.jsonPrimitive?.contentOrNull
                // auth_type: "none" | "pap" | "chap"（字符串）
                val authType = p["auth_type"]?.jsonPrimitive?.contentOrNull
                // pdp_type: "IP" | "IPv6" | "IPv4v6"（字符串，非整数）
                val pdpType = p["pdp_type"]?.jsonPrimitive?.contentOrNull
                val success = networkClient.setApnConfig(
                    autoSelect = autoSelect,
                    index = index,
                    profileName = profileName,
                    apn = apn,
                    username = username,
                    password = password,
                    authType = authType,
                    pdpType = pdpType
                )
                if (success) cache?.invalidate("device:apn")
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success)))
            }

            // APN 删除 (与参考项目 requests.js deleteAPNProfile 一致)
            delete("/apn/{index}") {
                val index = call.parameters["index"]?.toIntOrNull() ?: 0
                val success = networkClient.deleteApnProfile(index)
                if (success) cache?.invalidate("device:apn")
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success)))
            }

            // APN 手动切换 (与参考项目 requests.js switchAPNAuto isAuto=false 一致)
            post("/apn/switch") {
                val p = call.receive<JsonObject>()
                val index = p["index"]?.jsonPrimitive?.intOrNull ?: 0
                val success = networkClient.switchApnManual(index)
                if (success) cache?.invalidate("device:apn")
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success)))
            }

            // TR-069 远程管理配置
            post("/tr069") {
                val p = call.receive<JsonObject>()
                val enable = p["enable"]?.jsonPrimitive?.booleanOrNull
                val url = p["url"]?.jsonPrimitive?.contentOrNull
                val username = p["username"]?.jsonPrimitive?.contentOrNull
                val password = p["password"]?.jsonPrimitive?.contentOrNull
                val success = deviceClient.setTr069Config(enable, url, username, password)
                if (success) cache?.invalidate("device:settings")
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success)))
            }

            // 热区温度
            get("/thermal") {
                val zones = systemCollector.getThermalZones()
                call.respond(toJsonElement(mapOf("zones" to zones, "count" to zones.size)))
            }

            // 连接数统计
            get("/connections") {
                call.respond(toJsonElement(systemCollector.getConnectionCounts()))
            }

            // 数据用量
            get("/data-usage") {
                val now = System.currentTimeMillis()
                val zone = java.time.ZoneId.systemDefault()
                val todayStart = java.time.LocalDate.now(zone)
                    .atStartOfDay(zone).toInstant().toEpochMilli()
                val monthStart = java.time.LocalDate.now(zone).withDayOfMonth(1)
                    .atStartOfDay(zone).toInstant().toEpochMilli()
                val today = systemCollector.getCellularDataUsage(todayStart, now)
                val month = systemCollector.getCellularDataUsage(monthStart, now)
                call.respond(toJsonElement(mapOf("today" to today, "month" to month)))
            }

            // 流量限额配置查询（通过 DataHub 从 goform 读取）
            get("/traffic-limit") {
                val raw = dataHub?.signalQuery { getDataUsage() } ?: signalClient.getDataUsage()
                if (raw == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable,
                        toJsonElement(mapOf("error" to "无法查询设备流量限额配置，请检查设备连接")))
                    return@get
                }
                // 从缓存读取（成功时不缓存错误），优先走 ResponseCache
                val cached = if (cache != null) {
                    cache.getOrPut("device:traffic-limit", CacheTTL.TRAFFIC_LIMIT) {
                        buildTrafficLimitResponse(raw)
                    }
                } else {
                    buildTrafficLimitResponse(raw)
                }
                call.respond(cached)
            }

            // 性能模式 (ZTE 设备 goform PERFORMANCE_MODE_SETTING: 0=均衡, 1=高性能)
            // 接受两种入参格式：
            //   {"mode": "performance"} ↔ perfVal=1
            //   {"mode": "balanced"}    ↔ perfVal=0
            //   {"enabled": true}       ↔ perfVal=1
            post("/performance") {
                val p = call.receive<JsonObject>()
                val perfVal = when {
                    p.containsKey("enabled") -> if (p["enabled"]?.jsonPrimitive?.booleanOrNull == true) 1 else 0
                    else -> {
                        val mode = p["mode"]?.jsonPrimitive?.contentOrNull ?: "balanced"
                        if (mode == "performance") 1 else 0
                    }
                }
                val success = deviceClient.setPerformanceMode(perfVal)
                if (success) cache?.invalidate("device:settings")
                call.respond(toJsonElement(mapOf("success" to success, "performance_mode" to perfVal)))
            }

            // 指示灯控制
            post("/led") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                val success = deviceClient.setIndicatorLight(enabled)
                if (success) cache?.invalidate("device:settings")
                call.respond(toJsonElement(mapOf("success" to success, "enabled" to enabled)))
            }

            // 网络漫游
            post("/roaming") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = networkClient.setRoaming(enabled)
                if (success) cache?.invalidate("device:settings")
                call.respond(toJsonElement(mapOf("success" to success, "enabled" to enabled)))
            }

            // USB 网络共享 (tethering) [已禁用]
            if (false) post("/usb-tether") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val cmd = if (enabled) "svc usb setFunctions rndis" else "svc usb setFunctions none"
                val success = withContext(Dispatchers.IO) {
                    ShellExecutor.executeAsRoot(cmd).isSuccess
                }
                if (success) cache?.invalidate("device:settings")
                call.respond(toJsonElement(mapOf("success" to success, "enabled" to enabled)))
            }

            // 查询设备设置状态（通过 DataHub 获取）
            get("/settings") {
                suspend fun f(): JsonElement {
                    val data = dataHub?.signalQuery { queryDeviceSettings() } ?: signalClient.queryDeviceSettings()
                    return toJsonElement(data ?: emptyMap<String, Any>())
                }
                call.respond(if (cache != null) cache.getOrPut("device:settings", CacheTTL.DEVICE_SETTINGS) { f() } else f())
            }

            // FOTA 禁用（语义：enabled=true → 禁用 FOTA → UpgMode=0）
            // 与 REF goform SetUpgAutoSetting UpgMode="0" 禁用 一致
            post("/fota") {
                val p = call.receive<JsonObject>()
                val disable = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                // disable=true → setFotaEnabled(false) → UpgMode=0 (禁用 FOTA)
                val success = deviceClient.setFotaEnabled(!disable)
                if (success) cache?.invalidate("device:settings")
                call.respond(toJsonElement(mapOf("success" to success, "fota_disabled" to disable)))
            }

            // SELinux 状态
            get("/selinux") {
                val status = withContext(Dispatchers.IO) {
                    ShellExecutor.execute("getenforce 2>/dev/null").stdout.trim().ifEmpty { "Unknown" }
                }
                call.respond(toJsonElement(mapOf("selinux" to status)))
            }

            // SAMBA 文件共享
            post("/samba") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = deviceClient.setSambaSetting(enabled)
                if (success) cache?.invalidate("device:settings")
                call.respond(toJsonElement(mapOf("success" to success, "enabled" to enabled)))
            }

            // 基站锁定 (PCI + EARFCN + RAT)
            post("/cell-lock") {
                val p = call.receive<JsonObject>()
                val pci = p["pci"]?.jsonPrimitive?.contentOrNull ?: ""
                val earfcn = p["earfcn"]?.jsonPrimitive?.contentOrNull ?: ""
                val rat = p["rat"]?.jsonPrimitive?.contentOrNull ?: ""
                if (pci.isEmpty() || earfcn.isEmpty() || rat.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest,
                        toJsonElement(mapOf("error" to "pci, earfcn, rat are required")))
                    return@post
                }
                val success = deviceClient.cellLock(pci, earfcn, rat)
                if (success) {
                    cache?.invalidate("network:cell-info")
                    cache?.invalidate("device:settings")
                }
                call.respond(toJsonElement(mapOf("success" to success)))
            }

            // 解锁所有基站
            post("/cell-unlock") {
                val success = deviceClient.unlockAllCell()
                if (success) {
                    cache?.invalidate("network:cell-info")
                    cache?.invalidate("device:settings")
                }
                call.respond(toJsonElement(mapOf("success" to success)))
            }

            // 设备关机
            post("/shutdown") {
                cache?.invalidate("*")
                val success = deviceClient.shutdownDevice()
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success))
                )
            }

            // 定时重启
            post("/restart-schedule") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val time = p["time"]?.jsonPrimitive?.contentOrNull ?: "00:00"
                val success = deviceClient.setRestartSchedule(enabled, time)
                if (success) cache?.invalidate("device:settings")
                call.respond(toJsonElement(mapOf("success" to success, "enabled" to enabled, "time" to time)))
            }

            // 修改设备主机名
            post("/hostname") {
                val p = call.receive<JsonObject>()
                val mac = p["mac"]?.jsonPrimitive?.contentOrNull ?: ""
                val hostname = p["hostname"]?.jsonPrimitive?.contentOrNull ?: ""
                if (mac.isEmpty() || hostname.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest,
                        toJsonElement(mapOf("error" to "mac and hostname are required")))
                    return@post
                }
                val success = deviceClient.setHostname(mac, hostname)
                if (success) cache?.invalidate("device:info")
                call.respond(toJsonElement(mapOf("success" to success)))
            }

            // DHCP 设置
            post("/dhcp") {
                val p = call.receive<JsonObject>()
                val lanIp = p["lan_ip"]?.jsonPrimitive?.contentOrNull ?: "192.168.0.1"
                val lanNetmask = p["lan_netmask"]?.jsonPrimitive?.contentOrNull ?: "255.255.255.0"
                val dhcpType = p["dhcp_type"]?.jsonPrimitive?.contentOrNull ?: "SERVER"
                val dhcpStart = p["dhcp_start"]?.jsonPrimitive?.contentOrNull ?: ""
                val dhcpEnd = p["dhcp_end"]?.jsonPrimitive?.contentOrNull ?: ""
                val dhcpLease = p["dhcp_lease"]?.jsonPrimitive?.contentOrNull ?: "86400"
                val success = deviceClient.setDhcpSetting(lanIp, lanNetmask, dhcpType, dhcpStart, dhcpEnd, dhcpLease)
                if (success) cache?.invalidate("device:lan")
                call.respond(toJsonElement(mapOf("success" to success)))
            }

            // 流量限额设置
            post("/data-limit") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val limitSize = p["limit_size"]?.jsonPrimitive?.contentOrNull
                val limitUnit = p["limit_unit"]?.jsonPrimitive?.contentOrNull
                val alertPercent = p["alert_percent"]?.jsonPrimitive?.contentOrNull
                val autoClear = p["auto_clear"]?.jsonPrimitive?.booleanOrNull
                val clearDate = p["clear_date"]?.jsonPrimitive?.contentOrNull
                val success = networkClient.setDataLimit(enabled, limitSize, limitUnit, alertPercent, autoClear, clearDate)
                if (success) cache?.invalidate("device:traffic-limit")
                call.respond(toJsonElement(mapOf("success" to success)))
            }

            // 流量校准
            post("/flow-calibration") {
                val p = call.receive<JsonObject>()
                val way = p["way"]?.jsonPrimitive?.contentOrNull ?: "data"
                val data = p["data"]?.jsonPrimitive?.contentOrNull ?: "0"
                val time = p["time"]?.jsonPrimitive?.contentOrNull ?: "0"
                val success = networkClient.calibrateFlow(way, data, time)
                if (success) cache?.invalidate("device:traffic-limit")
                call.respond(toJsonElement(mapOf("success" to success)))
            }

            // APN 配置查询（通过 DataHub 获取）
            get("/apn") {
                val result = cache!!.getOrPut("device:apn", CacheTTL.APN_CONFIG) {
                    val apn = dataHub?.signalQuery { getApnConfig() } ?: signalClient.getApnConfig()
                    toJsonElement(apn ?: emptyMap<String, Any>())
                }
                call.respond(result)
            }

            // SIM PIN 状态（通过 DataHub 获取）
            get("/sim-pin") {
                val pin = dataHub?.signalQuery { getSimPinStatus() } ?: signalClient.getSimPinStatus()
                call.respond(toJsonElement(pin ?: emptyMap<String, Any>()))
            }

            // LAN/DHCP 状态查询（通过 DataHub 获取）
            get("/lan-settings") {
                suspend fun f(): JsonElement {
                    val data = dataHub?.signalQuery { getLanSettings() } ?: signalClient.getLanSettings()
                    return toJsonElement(data ?: emptyMap<String, Any>())
                }
                call.respond(if (cache != null) cache.getOrPut("device:lan", CacheTTL.LAN_SETTINGS) { f() } else f())
            }

            // MAC 访问控制列表查询（通过 DataHub 获取）
            get("/access-control") {
                val result = cache!!.getOrPut("device:acl", CacheTTL.ACCESS_CONTROL) {
                    val acl = dataHub?.signalQuery { queryDeviceAccessControlList() } ?: signalClient.queryDeviceAccessControlList()
                    toJsonElement(acl ?: emptyMap<String, Any>())
                }
                call.respond(result)
            }

            // 重置 Telephony Provider（紧急恢复：SIM 卡无限重置时使用）
            post("/telephony-reset") {
                val result = withContext(Dispatchers.IO) {
                    ShellExecutor.executeAsRoot("pm clear com.android.providers.telephony 2>/dev/null")
                }
                call.respond(toJsonElement(mapOf(
                    "success" to result.isSuccess,
                    "exit_code" to result.exitCode,
                    "stdout" to result.stdout,
                    "stderr" to result.stderr
                )))
            }

            // MAC 访问控制列表设置
            post("/access-control") {
                val p = call.receive<JsonObject>()
                val aclMode = p["acl_mode"]?.jsonPrimitive?.contentOrNull ?: "0"
                val macList = p["mac_list"]?.jsonPrimitive?.contentOrNull ?: ""
                val nameList = p["name_list"]?.jsonPrimitive?.contentOrNull ?: ""
                val success = deviceClient.setDeviceAccessControlList(aclMode, macList, nameList)
                if (success) cache?.invalidate("device:acl")
                call.respond(toJsonElement(mapOf("success" to success)))
            }
        }
    }

    // ──────────── 辅助方法 ────────────

    private fun buildTrafficLimitResponse(raw: JsonObject): JsonElement {
        val enabled = raw["data_volume_limit_switch"]?.jsonPrimitive?.contentOrNull == "1"
        val limitSize = raw["data_volume_limit_size"]?.jsonPrimitive?.contentOrNull ?: ""
        val limitUnit = raw["data_volume_limit_unit"]?.jsonPrimitive?.contentOrNull ?: "MB"
        val alertPercent = raw["data_volume_alert_percent"]?.jsonPrimitive?.contentOrNull ?: "80"
        val autoClear = raw["wan_auto_clear_flow_data_switch"]?.jsonPrimitive?.contentOrNull == "1"
        val clearDate = raw["traffic_clear_date"]?.jsonPrimitive?.contentOrNull ?: "1"
        val monthlyRx = raw["monthly_rx_bytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val monthlyTx = raw["monthly_tx_bytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val monthlyTime = raw["monthly_time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        return toJsonElement(mapOf(
            "enabled" to enabled,
            "limit_size" to limitSize,
            "limit_unit" to limitUnit,
            "alert_percent" to alertPercent,
            "auto_clear" to autoClear,
            "clear_date" to clearDate,
            "monthly_rx_bytes" to monthlyRx,
            "monthly_tx_bytes" to monthlyTx,
            "monthly_time" to monthlyTime
        ))
    }
}
