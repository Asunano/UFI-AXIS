package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.routes.RouteContext
import com.ufi_axis_core.contract.ErrorCode
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call

import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.core.cache.CacheTTL
import com.ufi_axis_core.controller.network.TrafficAutoOffGuard

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

    /**
     * 裸 goform 命令通道的开关检查：关着就回 403 并返回 true（调用方直接 return）。
     * 默认关是三重防护的第二层，见 `/goform/query` 上方注释。
     */
    private suspend fun rejectIfCommandDisabled(call: ApplicationCall): Boolean {
        if (settings?.goformCommandEnabled == true) return false
        call.respondFail(
            HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN,
            "裸 goform 命令通道已关闭，需要排障请先打开配置项 goform_command_enabled"
        )
        return true
    }


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
                            "type" to (goformType ?: telephonyCollector.getNetworkType()),
                            "connected" to telephonyCollector.isNetworkAvailable()
                        ),
                        "identity" to identity
                    ))
                }
                call.respond(cache?.let { it.getOrPut("device:info", CacheTTL.DEVICE_INFO) { fetch() } } ?: fetch())
            }

            // Goform 设备状态（通过 DataHub 获取准确数据）
            //
            // 【9.2 / 9.3】这是唯一不过 allowlist 的读端点（用途就是看设备后台有什么字段），
            // 因此两道处理：① 默认关闭（`goform_dump_enabled`），关着回 403；
            // ② 打开时也只给脱敏版（PII/凭据按 Sensitivity + 字段名兜底打码）。
            get("/goform") {
                if (settings?.goformDumpEnabled != true) {
                    call.respondFail(
                        HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN,
                        "设备原始字段 dump 已关闭，需要排障请先打开配置项 goform_dump_enabled"
                    )
                    return@get
                }
                suspend fun f(): JsonElement {
                    val data = dataHub?.signalQuery { getFullStatusMasked() } ?: signalClient.getFullStatusMasked()
                    return toJsonElement(data ?: emptyMap<String, Any>())
                }
                call.respond(if (cache != null) cache.getOrPut("device:goform", CacheTTL.GOFORM_FULL_STATUS) { f() } else f())
            }

            // ── 裸 goform 命令通道（排障用，不属于稳定契约）────────────────────────
            //
            // 用途：把任意 goform cmd / goformId 直接转发给设备，返回设备原样的响应。
            // 覆盖 profile 白名单之外的字段与写命令，避免每加一个偏门字段都要改 core。
            //
            // 【三重防护】缺一不可：
            //   1. 路由挂在 /api 下，由 AuthMiddleware 做 Bearer 鉴权（未授权 444）；
            //   2. `goform_command_enabled` 默认 false，关着一律 403；
            //   3. 每次调用记 WARN（set 只记 goformId 与参数**键名**，不记值，避免密码进日志）。
            //
            // 【不脱敏】返回值原样透传（Password/IMEI/ICCID 都是真值）——排障时必须看到真值，
            // 这也是它必须默认关 + 鉴权的原因。与 `/api/device/goform`（只读且脱敏）是两个开关。
            //
            // 【绕过校验】set 不过 profile 的 WriteSpec.validate、不过 SettingKey 白名单，
            // 参数正确性由调用方自负；写坏设备配置的风险由调用方承担。

            // 查询：body {"cmd": ["signalbar","network_type"]} 或 {"cmd": "signalbar,network_type"}

            // 返回设备原始 JSON（未归一化、未脱敏）。走 GoformQoS 查询许可与 2s 传输层缓存。
            post("/goform/query") {
                if (rejectIfCommandDisabled(call)) return@post

                val body = call.receiveJsonObject()
                val cmds = when (val raw = body["cmd"]) {
                    is JsonArray -> raw.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                    is JsonPrimitive -> raw.contentOrNull?.split(",")
                        ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) } ?: emptyList()
                    else -> emptyList()
                }
                if (cmds.isEmpty()) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "cmd is required（字符串数组或逗号分隔的字符串）"
                    )
                    return@post
                }
                AppLogger.w("DeviceRoutes", "goform/query 裸命令: ${cmds.joinToString(",")}")
                val data = goformClient.query(cmds)
                if (data == null) {
                    call.respondFail(
                        HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "设备无响应或登录失败，无法执行 goform 查询"
                    )
                    return@post
                }
                call.respond(data)
            }

            // 写入：body {"goformId": "SET_WIFI_INFO", "params": {"ssid": "x"}}
            // params 的值统一按字符串发出（goform 表单只接受字符串），非字符串会被 toString。
            // 写成功后清空整个 ResponseCache：裸命令可能改任何东西，无法精确判断影响哪些 key。
            post("/goform/set") {
                if (rejectIfCommandDisabled(call)) return@post

                val body = call.receiveJsonObject()
                val goformId = body["goformId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (goformId.isEmpty()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "goformId is required")
                    return@post
                }
                val extraParams = (body["params"] as? JsonObject)?.mapValues { (_, v) ->
                    (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
                } ?: emptyMap()
                // 只记键名：参数值可能是 WiFi 密码 / PIN / APN 凭据
                AppLogger.w(
                    "DeviceRoutes",
                    "goform/set 裸命令: goformId=$goformId params=[${extraParams.keys.joinToString(",")}]"
                )
                val response = goformClient.goformPost(mapOf("goformId" to goformId) + extraParams)
                if (response == null) {
                    call.respondFail(
                        HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "设备无响应或登录失败，goform 命令未执行"
                    )
                    return@post
                }
                cache?.invalidate("*")
                // 设备返回的多半是 {"result":"success"}，但也有纯文本；能解析成 JSON 就给结构化的
                val parsed = try {
                    Json.parseToJsonElement(response)
                } catch (e: Exception) {
                    JsonPrimitive(response)
                }
                call.respond(toJsonElement(mapOf(
                    "success" to true,
                    "goformId" to goformId,
                    "response" to parsed
                )))
            }


            // 设备身份信息。
            //
            // 【1.0.1】这里**故意不再包一层 `cache.getOrPut("device:identity", 30min)`**：
            // 那是 `device:identity` 这个 key 的第二个写入者，且语义与 DataHub 打架 ——
            // DataHub 用「空对象 = 30s 负缓存、成功才用 30min」区分失败与成功，而路由层
            // 一旦把 `toJsonElement(null → {})` 按 30 分钟 TTL 落进 JsonResponseCache，
            // 一次 goform 超时就让本机号码/IMEI/IMSI/ICCID 空掉半小时，刷新页面也无效。
            // 缓存只由 DataHub 负责（它存的已经是归一化后的 canonical 数据）。
            get("/identity") {
                val data = dataHub?.getDeviceIdentity() ?: signalClient.getDeviceIdentity()
                call.respond(toJsonElement(data ?: emptyMap<String, Any>()))
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
                val params = call.receiveJsonObject()
                val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = deviceClient.setDebugMode(enabled)
                if (success) cache?.invalidate("device:settings")
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "enabled" to enabled))
                )
            }

            // 修改管理密码
            post("/password") {
                val p = call.receiveJsonObject()
                val oldPwd = p["old_password"]?.jsonPrimitive?.contentOrNull ?: ""
                val newPwd = p["new_password"]?.jsonPrimitive?.contentOrNull ?: ""
                if (oldPwd.isEmpty() || newPwd.isEmpty()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "old_password and new_password are required")
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
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "无法查询设备流量限额配置，请检查设备连接")
                    return@get
                }
                // 只有「配置」部分进缓存。monthly_* 是实时用量计数器，以前和配置共用一个
                // 5 分钟 TTL 的条目，导致「本月已用」最多滞后 5 分钟、与仪表盘对不上。
                val config = if (cache != null) {
                    cache.getOrPut("device:traffic-limit", CacheTTL.TRAFFIC_LIMIT) {
                        TrafficLimitMapper.buildConfig(raw)
                    }
                } else {
                    TrafficLimitMapper.buildConfig(raw)
                }
                val fresh = TrafficLimitMapper.withFreshUsage(config, raw).jsonObject
                // core 自制的「到达阈值自动关网」开关与运行状态：**不属于设备契约**，所以不进
                // TrafficLimitMapper（那里只认设备字段），在出口处拼上。放同一个响应里是因为
                // 前端就在「限额设置」这一个弹窗里改它，没必要为两个布尔再开一个端点。
                val autoOffCfg = settings?.let { TrafficAutoOffGuard.readConfig(it) }
                val autoOff = mapOf(
                    "enabled" to (autoOffCfg?.enabled ?: false),
                    "restore_on_reset" to (autoOffCfg?.restoreOnReset ?: false),
                    // 本周期是否已经因为限额关过网（true 时不会再次触发，直到用量回落或跨月）
                    "triggered" to (settings?.let {
                        TrafficAutoOffGuard.readStateJson(it)?.get("turned_off")
                            ?.jsonPrimitive?.booleanOrNull
                    } ?: false),
                )
                call.respond(JsonObject(fresh + mapOf("auto_off" to toJsonElement(autoOff))))

            }


            // 性能模式 (ZTE 设备 goform PERFORMANCE_MODE_SETTING: 0=均衡, 1=高性能)
            // 接受两种入参格式：
            //   {"mode": "performance"} ↔ perfVal=1
            //   {"mode": "balanced"}    ↔ perfVal=0
            //   {"enabled": true}       ↔ perfVal=1
            post("/performance") {
                val p = call.receiveJsonObject()
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
                val p = call.receiveJsonObject()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                val success = deviceClient.setIndicatorLight(enabled)
                if (success) cache?.invalidate("device:settings")
                call.respond(toJsonElement(mapOf("success" to success, "enabled" to enabled)))
            }

            // 网络漫游
            post("/roaming") {
                val p = call.receiveJsonObject()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = networkClient.setRoaming(enabled)
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

            // FOTA 自动升级开关。
            //
            // 对外的规范入参是 `auto_update: Boolean`（正向：true = 允许自动升级）。
            // 历史入参 `enabled` 是**反向**的（true = 禁用），保留一版兼容；
            // 设备侧的 UpgMode 编码在 profile 的 WriteSpec 里，这里只做旧字段翻转（计划书 2.7）。
            post("/fota") {
                val p = call.receiveJsonObject()
                val autoUpdate = p["auto_update"]?.jsonPrimitive?.booleanOrNull
                    ?: p["enabled"]?.jsonPrimitive?.booleanOrNull?.let { legacyDisable ->
                        AppLogger.w("DeviceRoutes",
                            "POST /api/device/fota 收到旧字段 enabled（语义是「禁用」），请改用 auto_update")
                        !legacyDisable
                    }
                    ?: true
                val success = deviceClient.setFotaEnabled(autoUpdate)
                if (success) cache?.invalidate("device:settings")
                call.respond(toJsonElement(mapOf(
                    "success" to success,
                    "auto_update" to autoUpdate,
                    // 旧客户端读的是这个键，保留一版
                    "fota_disabled" to !autoUpdate,
                )))
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
                val p = call.receiveJsonObject()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val success = deviceClient.setSambaSetting(enabled)
                if (success) cache?.invalidate("device:settings")
                call.respond(toJsonElement(mapOf("success" to success, "enabled" to enabled)))
            }

            // 基站锁定。
            //
            // 规范入参：pci + earfcn + network_type（制式名 "LTE" / "NR"）。
            // 旧入参 `rat` 直接是 goform 的数字 RAT 码（12 / 16），保留一版兼容 ——
            // 这个值域漏在 API 上的直接后果是两个客户端各猜了一套：app 发 "12"/"16"（对的），
            // web 从邻区列表取 rat 且兜底 'LTE'（设备不认，锁定静默失败）。
            // 名字→数字码的映射现在在 profile 的 WriteSpec 里（计划书 2.6）。
            post("/cell-lock") {
                val p = call.receiveJsonObject()
                val pci = p["pci"]?.jsonPrimitive?.contentOrNull ?: ""
                val earfcn = p["earfcn"]?.jsonPrimitive?.contentOrNull ?: ""
                val networkType = p["network_type"]?.jsonPrimitive?.contentOrNull
                    ?: p["rat"]?.jsonPrimitive?.contentOrNull?.also {
                        AppLogger.w("DeviceRoutes",
                            "POST /api/device/cell-lock 收到旧字段 rat（$it），请改用 network_type=LTE|NR")
                    }
                    ?: ""
                if (pci.isEmpty() || earfcn.isEmpty() || networkType.isEmpty()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "pci, earfcn, network_type are required")
                    return@post
                }
                val outcome = deviceClient.cellLock(pci, earfcn, networkType)
                if (call.respondRejected(outcome)) return@post
                val success = outcome.ok
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
                val p = call.receiveJsonObject()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                val time = p["time"]?.jsonPrimitive?.contentOrNull ?: "00:00"
                val outcome = deviceClient.setRestartSchedule(enabled, time)
                if (call.respondRejected(outcome)) return@post
                val success = outcome.ok
                if (success) cache?.invalidate("device:settings")
                call.respond(toJsonElement(mapOf("success" to success, "enabled" to enabled, "time" to time)))
            }

            // DHCP 设置
            post("/dhcp") {

                val p = call.receiveJsonObject()
                val lanIp = p["lan_ip"]?.jsonPrimitive?.contentOrNull ?: "192.168.0.1"
                val lanNetmask = p["lan_netmask"]?.jsonPrimitive?.contentOrNull ?: "255.255.255.0"
                val dhcpType = p["dhcp_type"]?.jsonPrimitive?.contentOrNull ?: "SERVER"
                val dhcpStart = p["dhcp_start"]?.jsonPrimitive?.contentOrNull ?: ""
                val dhcpEnd = p["dhcp_end"]?.jsonPrimitive?.contentOrNull ?: ""
                val dhcpLease = p["dhcp_lease"]?.jsonPrimitive?.contentOrNull ?: "86400"
                val outcome = deviceClient.setDhcpSetting(lanIp, lanNetmask, dhcpType, dhcpStart, dhcpEnd, dhcpLease)
                if (call.respondRejected(outcome)) return@post
                val success = outcome.ok
                if (success) cache?.invalidate("device:lan")
                call.respond(toJsonElement(mapOf("success" to success)))
            }

            // 流量限额设置
            //
            // 入参是结构化的 limit_value（数值）+ limit_unit（MB/GB/TB）。
            // 设备侧的 "470_1024" 复合串由 profile 的 WriteSpec 拼（计划书 2.8），
            // 客户端不该看见它 —— 历史上这个复合串漏到了 app 和 web，各自写了 5 份解析/拼串，
            // 其中一份（app NotificationCenter.parseLimitMb）解析不出复合格式，
            // 导致流量告警在 GB 档位下永不触发。
            post("/data-limit") {
                val p = call.receiveJsonObject()
                val enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                var limitValue = p["limit_value"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                var limitUnit = p["limit_unit"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.uppercase() in setOf("MB", "GB", "TB") }
                // 兼容一版：旧客户端发的是复合串 limit_size="470_1024"（+ limit_unit 恒为 "MB"）。
                // 收到就拆开，并打 warn 提示对方改用结构化参数。
                p["limit_size"]?.jsonPrimitive?.contentOrNull?.takeIf { it.contains('_') }?.let { legacy ->
                    AppLogger.w("DeviceRoutes",
                        "POST /api/device/data-limit 收到旧的 limit_size 复合串（$legacy），请改用 limit_value + limit_unit")
                    val parts = legacy.split('_')
                    limitValue = parts.getOrNull(0)?.trim()?.toLongOrNull()
                    limitUnit = when (parts.getOrNull(1)?.trim()) {
                        "1" -> "MB"
                        "1048576" -> "TB"
                        else -> "GB"
                    }
                }
                val alertPercent = p["alert_percent"]?.jsonPrimitive?.contentOrNull
                val autoClear = p["auto_clear"]?.jsonPrimitive?.booleanOrNull
                val clearDate = p["clear_date"]?.jsonPrimitive?.contentOrNull
                // core 自制的自动关网开关：与设备写入无关，**先落盘再下发** ——
                // 设备侧限额写失败（respondRejected 提前返回）不该把用户刚改的开关一起丢掉。
                // 只在字段出现时才写：老客户端不带这两个键，不能被当成"用户关掉了"。
                settings?.let { s ->
                    val cur = TrafficAutoOffGuard.readConfig(s)
                    val next = TrafficAutoOffGuard.Config(
                        enabled = p["auto_off_enabled"]?.jsonPrimitive?.booleanOrNull ?: cur.enabled,
                        restoreOnReset = p["auto_off_restore"]?.jsonPrimitive?.booleanOrNull ?: cur.restoreOnReset,
                    )
                    if (next != cur) TrafficAutoOffGuard.writeConfig(s, next)
                }
                val outcome = networkClient.setDataLimit(
                    enabled, limitValue, limitUnit, alertPercent, autoClear, clearDate)
                if (call.respondRejected(outcome)) return@post
                val success = outcome.ok
                if (success) cache?.invalidate("device:traffic-limit")
                call.respond(toJsonElement(mapOf("success" to success)))
            }


            // 流量校准。
            //
            // 规范入参：target（"data" | "time"）+ value。旧入参是设备侧的三件套
            // way / data / time（未校准的那个要自己填 "0"），保留一版兼容；
            // 补零规则现在在 profile 的 WriteSpec 里（计划书 2.6）。
            post("/flow-calibration") {
                val p = call.receiveJsonObject()
                val target = p["target"]?.jsonPrimitive?.contentOrNull
                    ?: p["way"]?.jsonPrimitive?.contentOrNull?.also {
                        AppLogger.w("DeviceRoutes",
                            "POST /api/device/flow-calibration 收到旧字段 way，请改用 target + value")
                    }
                    ?: "data"
                val value = p["value"]?.jsonPrimitive?.contentOrNull
                    ?: (if (target == "time") p["time"] else p["data"])?.jsonPrimitive?.contentOrNull
                    ?: "0"
                val outcome = networkClient.calibrateFlow(target, value)
                if (call.respondRejected(outcome)) return@post
                val success = outcome.ok
                if (success) cache?.invalidate("device:traffic-limit")
                call.respond(toJsonElement(mapOf("success" to success)))
            }

            // LAN/DHCP 状态查询（通过 DataHub 获取）

            get("/lan-settings") {
                suspend fun f(): JsonElement {
                    val data = dataHub?.signalQuery { getLanSettings() } ?: signalClient.getLanSettings()
                    return toJsonElement(data ?: emptyMap<String, Any>())
                }
                call.respond(if (cache != null) cache.getOrPut("device:lan", CacheTTL.LAN_SETTINGS) { f() } else f())
            }
        }
    }



    // ──────────── 辅助方法 ────────────

}


