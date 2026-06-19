package com.ufi_axis_core.api.routes

import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.controller.system.SystemController
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.util.ShellExecutor
import com.ufi_axis_core.util.ShellQoS

/**
 * 设备信息路由
 * GET /api/device/info - 完整设备信息
 * GET /api/device/goform - Goform 设备状态
 */
class DeviceRoutes(
    private val systemCollector: SystemCollector,
    private val telephonyCollector: TelephonyCollector,
    private val atChannel: ATChannel,
    private val goformClient: GoformClient,
    private val systemController: SystemController
) {
    fun register(route: Route) {
        route.route("/device") {
            get("/info") {
                val deviceInfo = systemController.getDeviceModel()
                val simInfo = telephonyCollector.getSimInfo()
                val storageInfo = systemCollector.getStorageInfo()
                val uptime = systemCollector.getUptime()
                val atInfo = atChannel.getPlatformInfo()
                val kernelVersion = systemController.getKernelVersion()

                // 优先使用 goform 数据获取网络类型和运营商，避免 TelephonyCollector 在无 SIM 设备上返回 "Unknown"
                val goformInfo = try { goformClient.getSignalInfo() } catch (_: Exception) { null }
                val rawType = goformInfo?.get("network_type")?.jsonPrimitive?.contentOrNull
                val goformProvider = goformInfo?.get("network_provider")?.jsonPrimitive?.contentOrNull

                call.respond(toJsonElement(mapOf(
                    "device" to deviceInfo,
                    "sim" to simInfo,
                    "storage" to storageInfo,
                    "uptime" to uptime,
                    "at_channel" to atInfo,
                    "kernel" to kernelVersion,
                    "network" to mapOf(
                        "operator" to (if (!goformProvider.isNullOrBlank()) goformProvider else telephonyCollector.getOperatorName()),
                        "type" to (if (rawType != null) GoformClient.mapNetworkType(rawType) else telephonyCollector.getNetworkType()),
                        "connected" to telephonyCollector.isNetworkAvailable()
                    )
                )))
            }

            // Goform 设备状态（直接从设备获取准确数据）
            get("/goform") {
                val goformData = goformClient.getFullStatus()
                call.respond(toJsonElement(goformData ?: emptyMap<String, Any>()))
            }

            get("/model") {
                call.respond(toJsonElement(systemController.getDeviceModel()))
            }

            get("/magisk") {
                call.respond(toJsonElement(systemController.getMagiskStatus()))
            }

            // 重启设备
            post("/reboot") {
                val success = systemController.reboot()
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success))
                )
            }

            // 恢复出厂设置
            post("/factory-reset") {
                val success = goformClient.factoryReset()
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success))
                )
            }

            // 调试模式 (ADB)
            post("/debug") {
                val params = call.receive<JsonObject>()
                val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = goformClient.setDebugMode(enabled)
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "enabled" to enabled))
                )
            }

            // USB 模式切换
            post("/usb-mode") {
                val params = call.receive<JsonObject>()
                val mode = params["mode"]?.jsonPrimitive?.intOrNull ?: 0
                val success = goformClient.setUsbMode(mode)
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
                val success = goformClient.changePassword(oldPwd, newPwd)
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success)))
            }

            // APN 配置
            post("/apn") {
                val p = call.receive<JsonObject>()
                val autoSelect = p["auto_select"]?.jsonPrimitive?.booleanOrNull
                val name = p["name"]?.jsonPrimitive?.contentOrNull
                val apn = p["apn"]?.jsonPrimitive?.contentOrNull
                val user = p["user"]?.jsonPrimitive?.contentOrNull
                val pass = p["pass"]?.jsonPrimitive?.contentOrNull
                val authType = p["auth_type"]?.jsonPrimitive?.intOrNull
                val pdpType = p["pdp_type"]?.jsonPrimitive?.intOrNull
                val success = goformClient.setApnConfig(autoSelect, name, apn, user, pass, authType, pdpType)
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success)))
            }

            // APN 删除 (与参考项目 requests.js deleteAPNProfile 一致)
            delete("/apn/{index}") {
                val index = call.parameters["index"]?.toIntOrNull() ?: 0
                val success = goformClient.deleteApnProfile(index)
                call.respond(if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success)))
            }

            // APN 手动切换 (与参考项目 requests.js switchAPNAuto isAuto=false 一致)
            post("/apn/switch") {
                val p = call.receive<JsonObject>()
                val index = p["index"]?.jsonPrimitive?.intOrNull ?: 0
                val success = goformClient.switchApnManual(index)
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
                val success = goformClient.setTr069Config(enable, url, username, password)
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

            // 流量限额配置查询（从 ZTE 设备 goform 读取）
            get("/traffic-limit") {
                val raw = goformClient.getDataUsage()
                if (raw == null) {
                    call.respond(toJsonElement(mapOf("error" to "查询失败")))
                    return@get
                }
                val enabled = raw["data_volume_limit_switch"]?.jsonPrimitive?.contentOrNull == "1"
                val limitSize = raw["data_volume_limit_size"]?.jsonPrimitive?.contentOrNull ?: ""
                val limitUnit = raw["data_volume_limit_unit"]?.jsonPrimitive?.contentOrNull ?: "MB"
                val alertPercent = raw["data_volume_alert_percent"]?.jsonPrimitive?.contentOrNull ?: "80"
                val autoClear = raw["wan_auto_clear_flow_data_switch"]?.jsonPrimitive?.contentOrNull == "1"
                val clearDate = raw["traffic_clear_date"]?.jsonPrimitive?.contentOrNull ?: "1"
                val monthlyRx = raw["monthly_rx_bytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val monthlyTx = raw["monthly_tx_bytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val monthlyTime = raw["monthly_time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                call.respond(toJsonElement(mapOf(
                    "enabled" to enabled,
                    "limit_size" to limitSize,
                    "limit_unit" to limitUnit,
                    "alert_percent" to alertPercent,
                    "auto_clear" to autoClear,
                    "clear_date" to clearDate,
                    "monthly_rx_bytes" to monthlyRx,
                    "monthly_tx_bytes" to monthlyTx,
                    "monthly_time" to monthlyTime
                )))
            }

            // 性能模式 (ZTE 设备)
            post("/performance") {
                val p = call.receive<JsonObject>()
                val mode = p["mode"]?.jsonPrimitive?.contentOrNull ?: "balanced"
                val perfVal = when (mode) {
                    "performance" -> 1
                    else -> 0
                }
                val success = goformClient.setPerformanceMode(perfVal)
                call.respond(toJsonElement(mapOf("success" to success, "mode" to mode)))
            }

            // 指示灯控制
            post("/led") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                val success = goformClient.setIndicatorLight(enabled)
                call.respond(toJsonElement(mapOf("success" to success, "enabled" to enabled)))
            }

            // 网络漫游
            post("/roaming") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = goformClient.setRoaming(enabled)
                call.respond(toJsonElement(mapOf("success" to success, "enabled" to enabled)))
            }

            // USB 网络共享 (tethering)
            post("/usb-tether") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val cmd = if (enabled) "svc usb setFunctions rndis" else "svc usb setFunctions none"
                val success = ShellQoS.executeAsRoot(cmd).isSuccess
                call.respond(toJsonElement(mapOf("success" to success, "enabled" to enabled)))
            }

            // 5G SA 模式 — 通过 SET_BEARER_PREFERENCE 实现
            post("/sa-mode") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                // 参考项目: SA = "Only_5G", 关闭时恢复 AUTO = "WL_AND_5G"
                val preference = if (enabled) "Only_5G" else "WL_AND_5G"
                val success = goformClient.setBearerPreference(preference)
                call.respond(toJsonElement(mapOf("success" to success, "sa_mode" to enabled)))
            }

            // 查询设备设置状态（LED、性能模式、漫游、Bearer、频段锁等）
            get("/settings") {
                val settings = goformClient.queryDeviceSettings() ?: emptyMap()
                call.respond(toJsonElement(settings))
            }

            // FOTA 禁用
            post("/fota") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = goformClient.setFotaEnabled(enabled)
                call.respond(toJsonElement(mapOf("success" to success, "enabled" to enabled)))
            }

            // SELinux 状态
            get("/selinux") {
                val status = ShellQoS.executeCached("getenforce 2>/dev/null").stdout.trim().ifEmpty { "Unknown" }
                call.respond(toJsonElement(mapOf("selinux" to status)))
            }

            // SAMBA 文件共享
            post("/samba") {
                val p = call.receive<JsonObject>()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = goformClient.setSambaSetting(enabled)
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
                val success = goformClient.cellLock(pci, earfcn, rat)
                call.respond(toJsonElement(mapOf("success" to success)))
            }

            // 解锁所有基站
            post("/cell-unlock") {
                val success = goformClient.unlockAllCell()
                call.respond(toJsonElement(mapOf("success" to success)))
            }

            // 设备关机
            post("/shutdown") {
                val success = goformClient.shutdownDevice()
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
                val success = goformClient.setRestartSchedule(enabled, time)
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
                val success = goformClient.setHostname(mac, hostname)
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
                val success = goformClient.setDhcpSetting(lanIp, lanNetmask, dhcpType, dhcpStart, dhcpEnd, dhcpLease)
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
                val success = goformClient.setDataLimit(enabled, limitSize, limitUnit, alertPercent, autoClear, clearDate)
                call.respond(toJsonElement(mapOf("success" to success)))
            }

            // 流量校准
            post("/flow-calibration") {
                val p = call.receive<JsonObject>()
                val way = p["way"]?.jsonPrimitive?.contentOrNull ?: "data"
                val data = p["data"]?.jsonPrimitive?.contentOrNull ?: "0"
                val time = p["time"]?.jsonPrimitive?.contentOrNull ?: "0"
                val success = goformClient.calibrateFlow(way, data, time)
                call.respond(toJsonElement(mapOf("success" to success)))
            }

            // APN 配置查询
            get("/apn") {
                val apn = goformClient.getApnConfig()
                call.respond(toJsonElement(apn ?: emptyMap<String, Any>()))
            }

            // SIM PIN 状态
            get("/sim-pin") {
                val pin = goformClient.getSimPinStatus()
                call.respond(toJsonElement(pin ?: emptyMap<String, Any>()))
            }

            // LAN/DHCP 状态查询
            get("/lan-settings") {
                val lan = goformClient.getLanSettings()
                call.respond(toJsonElement(lan ?: emptyMap<String, Any>()))
            }

            // MAC 访问控制列表查询（黑名单）
            get("/access-control") {
                val acl = goformClient.queryDeviceAccessControlList()
                call.respond(toJsonElement(acl ?: emptyMap<String, Any>()))
            }

            // MAC 访问控制列表设置
            post("/access-control") {
                val p = call.receive<JsonObject>()
                val aclMode = p["acl_mode"]?.jsonPrimitive?.contentOrNull ?: "0"
                val macList = p["mac_list"]?.jsonPrimitive?.contentOrNull ?: ""
                val nameList = p["name_list"]?.jsonPrimitive?.contentOrNull ?: ""
                val success = goformClient.setDeviceAccessControlList(aclMode, macList, nameList)
                call.respond(toJsonElement(mapOf("success" to success)))
            }
        }
    }
}
