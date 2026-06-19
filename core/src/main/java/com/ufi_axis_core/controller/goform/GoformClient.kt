package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.util.AppLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.security.MessageDigest

/**
 * Goform HTTP 客户端
 *
 * 通过 HTTP 访问 ZTE 设备 Goform 接口
 * 使用 OkHttp 风格的直接 cookie 管理 + AD 防重放参数
 *
 * 关键要求:
 * 1. 所有请求必须先完成认证 (ensureLogin)
 * 2. 认证时传递 user=admin，提取 Set-Cookie 中的 JSESSIONID
 * 3. POST 操作需要 AD 防重放参数: SHA256(SHA256(wa_inner_version+cr_version)+RD)
 * 4. 必须带 Referer 头
 * 5. 批量查询命令用逗号分隔，加 multi_data=1
 */
class GoformClient(
    private val deviceIp: String = "192.168.0.1",
    private val port: Int = 8080,
    private val password: String = "admin"
) {
    private val tag = "GoformClient"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var isLoggedIn = false
    private val loginMutex = Mutex()
    @Volatile private var lastValidatedAt: Long = 0L
    private val sessionCacheTtlMs = 30_000L
    private var lastLoginAttempt: Long = 0L
    private val loginCooldownMs = 5_000L

    // 直接管理 session cookie
    @Volatile private var sessionCookie: String? = null

    // 缓存 wa_inner_version + cr_version 用于 AD 计算
    @Volatile private var waVersion: String? = null
    @Volatile private var crVersion: String? = null

    private val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            engine {
                maxConnectionsCount = 10
                requestTimeout = 8000
                endpoint {
                    maxConnectionsPerRoute = 5
                    connectTimeout = 5000
                    keepAliveTime = 3000
                }
            }
            expectSuccess = false
        }
    }

    private fun baseUrl(): String =
        if (port == 80) "http://$deviceIp" else "http://$deviceIp:$port"

    private fun buildCookieHeader(): String = sessionCookie?.let { "Cookie" to it }?.let { (k, v) -> v } ?: ""

    // ==================== 认证 ====================

    suspend fun ensureLogin(): Boolean {
        val now = System.currentTimeMillis()
        if (isLoggedIn && (now - lastValidatedAt) < sessionCacheTtlMs) return true

        if (isLoggedIn) {
            if (validateSession()) { lastValidatedAt = now; return true }
        }
        if (now - lastLoginAttempt < loginCooldownMs && lastLoginAttempt > 0L) {
            AppLogger.w(tag, "Login cooling"); return false
        }
        return loginMutex.withLock {
            if (isLoggedIn && (System.currentTimeMillis() - lastValidatedAt) < sessionCacheTtlMs) return@withLock true

            AppLogger.i(tag, "Performing login to $deviceIp:$port...")
            try {
                val base = baseUrl()

                // 步骤 1: 获取 LD 挑战值（参考项目不访问首页，直接获取 LD）
                val ldResp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=LD&isTest=false&_=${System.currentTimeMillis()}") {
                    header("Referer", "$base/index.html")
                }
                val ldBody = ldResp.bodyAsText()
                val ld = try { json.parseToJsonElement(ldBody).jsonObject["LD"]?.jsonPrimitive?.contentOrNull ?: "" } catch (_: Exception) { "" }

                // 步骤 2: 双重 SHA256 加密密码
                val passHash = sha256Hex(password).uppercase()
                val encPwd = sha256Hex(passHash + ld).uppercase()

                // 步骤 3: 发送 LOGIN_MULTI_USER 请求（参考项目 requests.js 默认使用 login2）
                // LOGIN_MULTI_USER + IP=localhost 适用于从设备本机访问 goform 后端
                val loginResp = httpClient.post("$base/goform/goform_set_cmd_process") {
                    header("Referer", "$base/index.html")
                    header("Origin", base)
                    header("Content-Type", "application/x-www-form-urlencoded")
                    setBody("isTest=false&goformId=LOGIN_MULTI_USER&user=admin&password=$encPwd&IP=localhost")
                }
                var loginBody = loginResp.bodyAsText()

                // 提取 Set-Cookie（取第一部分 name=value）
                loginResp.headers["Set-Cookie"]?.split(";")?.firstOrNull()?.let { sessionCookie = it }

                if (loginResp.status != HttpStatusCode.OK || isLoginFailed(loginBody)) {
                    // LOGIN_MULTI_USER 失败，尝试基础 LOGIN（部分固件仅支持此方式）
                    AppLogger.w(tag, "LOGIN_MULTI_USER failed, trying LOGIN fallback...")
                    val fallbackResp = httpClient.post("$base/goform/goform_set_cmd_process") {
                        header("Referer", "$base/index.html")
                        header("Origin", base)
                        header("Content-Type", "application/x-www-form-urlencoded")
                        setBody("isTest=false&goformId=LOGIN&user=admin&password=$encPwd")
                    }
                    loginBody = fallbackResp.bodyAsText()
                    fallbackResp.headers["Set-Cookie"]?.split(";")?.firstOrNull()?.let { sessionCookie = it }

                    if (fallbackResp.status != HttpStatusCode.OK || isLoginFailed(loginBody)) {
                        AppLogger.e(tag, "LOGIN fallback also rejected: ${loginBody.take(200)}")
                        isLoggedIn = false; return@withLock false
                    }
                }

                // 步骤 4: 获取设备版本信息用于 AD 计算
                val infoResp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=wa_inner_version,cr_version&multi_data=1&isTest=false&_=${System.currentTimeMillis()}") {
                    header("Referer", "$base/index.html")
                    if (sessionCookie != null) header("Cookie", sessionCookie!!)
                }
                try {
                    val infoJson = json.parseToJsonElement(infoResp.bodyAsText()).jsonObject
                    waVersion = infoJson["wa_inner_version"]?.jsonPrimitive?.contentOrNull
                    crVersion = infoJson["cr_version"]?.jsonPrimitive?.contentOrNull
                } catch (_: Exception) {}

                isLoggedIn = true
                lastLoginAttempt = System.currentTimeMillis()
                lastValidatedAt = System.currentTimeMillis()
                AppLogger.i(tag, "Login successful. wa=$waVersion cr=$crVersion")
                true
            } catch (e: Exception) {
                AppLogger.e(tag, "Login failed", e)
                isLoggedIn = false
                lastLoginAttempt = System.currentTimeMillis()
                false
            }
        }
    }

    /**
     * AD 防重放参数: SHA256(SHA256(wa+cr)+RD)
     * 每次重新获取版本信息，与参考项目 processAD() 保持一致
     * 注意: sha256 必须返回大写 hex，ZTE 设备校验大小写
     */
    private suspend fun computeAd(): String {
        return try {
            // 每次重新获取版本信息（参考项目 processAD 每次都调 getUFIInfo）
            val base = baseUrl()
            val infoResp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=Language,cr_version,wa_inner_version&multi_data=1&isTest=false&_=${System.currentTimeMillis()}") {
                header("Referer", "$base/index.html")
                if (sessionCookie != null) header("Cookie", sessionCookie!!)
            }
            val infoJson = json.parseToJsonElement(infoResp.bodyAsText()).jsonObject
            val wa = infoJson["wa_inner_version"]?.jsonPrimitive?.contentOrNull ?: ""
            val cr = infoJson["cr_version"]?.jsonPrimitive?.contentOrNull ?: ""
            if (wa.isEmpty() || cr.isEmpty()) {
                AppLogger.w(tag, "computeAd: version fields empty wa='$wa' cr='$cr'")
                return ""
            }
            // 缓存用于调试日志
            waVersion = wa; crVersion = cr
            // SHA256 全链路大写，与参考项目 sha256() 一致
            val parsed = sha256Hex(wa + cr).uppercase()
            val rd = getRd() ?: run { AppLogger.w(tag, "computeAd: RD is null"); return "" }
            sha256Hex(parsed + rd).uppercase()
        } catch (e: Exception) {
            AppLogger.e(tag, "computeAd failed", e)
            ""
        }
    }

    private suspend fun getRd(): String? {
        return try {
            val base = baseUrl()
            val resp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=RD&isTest=false&_=${System.currentTimeMillis()}") {
                header("Referer", "$base/index.html")
                if (sessionCookie != null) header("Cookie", sessionCookie!!)
            }
            json.parseToJsonElement(resp.bodyAsText()).jsonObject["RD"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            AppLogger.e(tag, "getRd failed", e)
            null
        }
    }

    private suspend fun validateSession(): Boolean {
        return try {
            val base = baseUrl()
            val resp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=LD&isTest=false&_=${System.currentTimeMillis()}") {
                header("Referer", "$base/index.html")
                if (sessionCookie != null) header("Cookie", sessionCookie!!)
            }
            val body = resp.bodyAsText()
            // HTML 响应表示被重定向到登录页，会话已失效
            val isHtml = body.trimStart().startsWith("<")
            if (isHtml) { isLoggedIn = false; return false }
            // 检查 LD 字段是否存在（有效响应的标志）
            val hasLd = try { json.parseToJsonElement(body).jsonObject.containsKey("LD") } catch (_: Exception) { false }
            if (!hasLd) { isLoggedIn = false; return false }
            true
        } catch (e: Exception) { false }
    }

    /**
     * 标记会话已失效，下次请求时强制重新登录
     * 同时重置缓存时间戳，确保下次请求立即进入登录流程
     */
    fun invalidateSession() {
        AppLogger.w(tag, "Session invalidated")
        isLoggedIn = false
        lastValidatedAt = 0L
    }

    /**
     * 重置登录状态，下次请求时重新认证
     */
    fun resetLogin() {
        isLoggedIn = false
        lastValidatedAt = 0L
    }

    // ==================== 通用请求方法 ====================

    /**
     * 检查响应体是否表示认证/会话失败
     */
    private fun isAuthFailure(body: String): Boolean {
        if (body.isEmpty()) return false
        return body.contains("\"result\":\"not logged in\"", ignoreCase = true)
            || body.contains("\"result\":\"session\"", ignoreCase = true)
            || body.contains("login.html", ignoreCase = true)
            || body.contains("redirect", ignoreCase = true)
            // ZTE 有些固件在未登录时直接返回 HTML 登录页
            || (body.trimStart().startsWith("<!DOCTYPE") || body.trimStart().startsWith("<html"))
    }

    /**
     * POST 到 goform_set_cmd_process (用于写入/控制操作)
     * 带自动重试：首次失败后重新登录再试一次
     */
    private suspend fun goformPost(params: Map<String, String>): String? {
        return goformPostInternal(params, retry = true)
    }

    private suspend fun goformPostInternal(params: Map<String, String>, retry: Boolean): String? {
        if (!ensureLogin()) {
            AppLogger.e(tag, "goformPost: not logged in, aborting ${params["goformId"]}")
            return null
        }

        return try {
            // 添加 AD 防重放参数
            val ad = computeAd()
            val allParams = if (ad.isNotEmpty()) params + ("AD" to ad) else params
            val formBody = allParams.entries.joinToString("&") { (k, v) ->
                "${k}=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }
            val base = baseUrl()
            val response = httpClient.post("$base/goform/goform_set_cmd_process") {
                header("Referer", "$base/index.html")
                header("Origin", base)
                header("Content-Type", "application/x-www-form-urlencoded")
                if (sessionCookie != null) header("Cookie", sessionCookie!!)
                setBody(formBody)
            }

            // 更新 cookie
            response.headers["Set-Cookie"]?.split(";")?.firstOrNull()?.let { sessionCookie = it }

            val body = response.bodyAsText()
            AppLogger.i(tag, "goformPost(${params["goformId"]}) [${response.status}]: ${body.take(200)}")

            if (response.status == HttpStatusCode.Found
                || response.status == HttpStatusCode.SeeOther
                || isAuthFailure(body)
            ) {
                invalidateSession()
                if (retry) { return goformPostInternal(params, retry = false) }
                return null
            }
            if (response.status == HttpStatusCode.OK) body else null
        } catch (e: Exception) {
            AppLogger.e(tag, "goformPost failed: ${params["goformId"]}", e)
            if (retry) { invalidateSession(); return goformPostInternal(params, retry = false) }
            null
        }
    }

    // ==================== 查询接口 ====================

    /**
     * 批量查询命令 (multi_data=1)
     * 带自动重试：首次失败后重新登录再试一次
     */
    suspend fun query(commands: List<String>): JsonObject? {
        return queryInternal(commands, retry = true)
    }

    private suspend fun queryInternal(commands: List<String>, retry: Boolean): JsonObject? {
        if (!ensureLogin()) return null

        return try {
            val cmdStr = commands.joinToString(",")
            val base = baseUrl()
            val url = if (commands.size > 1) {
                "$base/goform/goform_get_cmd_process?isTest=false&multi_data=1&cmd=$cmdStr&_=${System.currentTimeMillis()}"
            } else {
                "$base/goform/goform_get_cmd_process?isTest=false&cmd=$cmdStr&_=${System.currentTimeMillis()}"
            }

            val response = httpClient.get(url) {
                header("Referer", "$base/index.html")
                if (sessionCookie != null) header("Cookie", sessionCookie!!)
            }

            response.headers["Set-Cookie"]?.split(";")?.firstOrNull()?.let { sessionCookie = it }

            val responseBody = response.bodyAsText()
            AppLogger.i(tag, "query(${commands.size}cmds) status=${response.status} body=${responseBody.take(300)}")

            val isAuthErr = response.status == HttpStatusCode.Found
                || response.status == HttpStatusCode.SeeOther
                || isAuthFailure(responseBody)

            if (response.status == HttpStatusCode.OK && !isAuthErr) {
                val parsed = json.parseToJsonElement(responseBody)
                if (parsed is JsonObject) parsed else { AppLogger.w(tag, "query: parsed non-object: $parsed"); null }
            } else if (isAuthErr && retry) {
                invalidateSession(); return queryInternal(commands, retry = false)
            } else {
                if (retry && response.status != HttpStatusCode.OK) { invalidateSession(); return queryInternal(commands, retry = false) }
                null
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Goform query failed", e)
            if (retry) { invalidateSession(); return queryInternal(commands, retry = false) }
            null
        }
    }

    /**
     * 查询单个命令
     * 带自动重试
     */
    suspend fun querySingle(command: String): JsonElement? {
        return querySingleInternal(command, retry = true)
    }

    private suspend fun querySingleInternal(command: String, retry: Boolean): JsonElement? {
        if (!ensureLogin()) return null

        return try {
            val base = baseUrl()
            val url = "$base/goform/goform_get_cmd_process?cmd=$command&isTest=false&_=${System.currentTimeMillis()}"

            val response = httpClient.get(url) {
                header("Referer", "$base/index.html")
                if (sessionCookie != null) header("Cookie", sessionCookie!!)
            }
            response.headers["Set-Cookie"]?.split(";")?.firstOrNull()?.let { sessionCookie = it }

            val responseBody = response.bodyAsText()

            if (response.status == HttpStatusCode.OK && !isAuthFailure(responseBody)) {
                json.parseToJsonElement(responseBody)
            } else {
                if (retry) { invalidateSession(); return querySingleInternal(command, retry = false) }
                null
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Goform querySingle failed: $command", e)
            invalidateSession()
            if (retry) { return querySingleInternal(command, retry = false) }
            null
        }
    }

    // ==================== 信号信息 ====================

    suspend fun getSignalInfo(): JsonObject? {
        // 分两次查询避免单次 cmd 过多导致设备返回空
        val primary = query(listOf("network_type", "network_provider", "rssi", "signalbar"))
        val secondary = query(listOf(
            "lte_rsrp", "Z5g_rsrp",
            "Nr_snr", "Lte_snr",
            "lte_rsrq", "nr_rsrq",
            "lte_rssi", "nr_rssi",
            "cell_id", "Nr_pci", "Lte_pci",
            "neighbor_cell_info"
        ))
        if (primary == null && secondary == null) return null
        val merged = JsonObject(mutableMapOf<String, JsonElement>().apply {
            // 合并时保留非 JsonPrimitive 值（如 neighbor_cell_info 返回 JsonArray）
            fun mergeEntry(k: String, v: JsonElement) {
                when (v) {
                    is JsonPrimitive -> if (v.content.isNotEmpty()) put(k, v)
                    else -> put(k, v)  // JsonArray / JsonObject 保留
                }
            }
            primary?.forEach { (k, v) -> mergeEntry(k, v) }
            secondary?.forEach { (k, v) -> mergeEntry(k, v) }
        })
        return merged.ifEmpty { null }
    }

    // ==================== 设备信息 ====================

    suspend fun getDeviceInfo(): JsonObject? {
        return query(listOf(
            "imei", "imsi", "lan_ipaddr", "mac_address"
        ))
    }

    // ==================== 流量统计 ====================

    suspend fun getTrafficStats(): JsonObject? {
        return query(listOf(
            "realtime_tx_bytes", "realtime_rx_bytes",
            "monthly_rx_bytes", "monthly_tx_bytes",
            "realtime_time", "monthly_time",
            "realtime_tx_thrpt", "realtime_rx_thrpt"
        ))
    }

    /**
     * 获取完整的设备状态，合并多个短查询结果
     * 与参考项目 requests.js getUFIData() 的 37 个字段保持一致
     */
    suspend fun getFullStatus(): JsonObject? {
        val merged = mutableMapOf<String, JsonElement>()
        // 批次 1: 信号/网络/设备基础信息（参考项目 getUFIData 主轮询）
        query(listOf(
            "network_signalbar", "network_rssi", "network_type", "network_provider",
            "ppp_status", "lan_ipaddr", "mac_address", "imei", "imsi", "iccid",
            "wifi_onoff_state", "wifi_access_sta_num", "cr_version",
            "msisdn", "sim_msisdn", "ipv6_wan_ipaddr"
        ))?.let { merged.putAll(it) }
        // 批次 2: 流量/电池/SIM/漫游/WiFi/固件
        query(listOf(
            "realtime_tx_bytes", "realtime_rx_bytes", "monthly_tx_bytes", "monthly_rx_bytes",
            "realtime_time", "monthly_time", "realtime_rx_thrpt", "realtime_tx_thrpt",
            "battery_value", "battery_vol_percent", "battery_charging",
            "sms_received_flag", "sms_unread_num", "sms_sim_unread_num",
            "data_volume_limit_switch", "data_volume_alert_percent", "data_volume_limit_size",
            "loginfo", "pin_status", "simcard_roam", "usb_port_switch",
            "wifi_chip1_ssid1_ssid", "wifi_5g_enable", "roam_setting_option",
            "Lte_ca_status", "new_version_state", "current_upgrade_state",
            "sim_slot", "dual_sim_support"
        ))?.let { merged.putAll(it) }
        return if (merged.isEmpty()) null else JsonObject(merged)
    }

    // ==================== 短信 ====================

    /**
     * 获取短信列表
     * 使用 query() 方法（已工作正常）而非独立 URL 构造
     */
    suspend fun getSmsList(page: Int = 0, perPage: Int = 50): JsonObject? {
        // 直接用 getSmsListDirect，因为需要 mem_store/tags/order_by 等额外参数
        // query() 的 generic cmd= 拼接不支持这些参数
        return getSmsListDirect(page, perPage)
    }

    private suspend fun getSmsListDirect(page: Int, perPage: Int): JsonObject? {
        if (!ensureLogin()) return null
        return try {
            val base = baseUrl()
            // 与参考项目 requests.js 保持一致: mem_store=1&tags=100&order_by=order by id desc
            val orderBy = java.net.URLEncoder.encode("order by id desc", "UTF-8")
            val url = "$base/goform/goform_get_cmd_process?isTest=false&cmd=sms_data_total&page=$page&data_per_page=$perPage&mem_store=1&tags=100&order_by=$orderBy&_=${System.currentTimeMillis()}"
            val response = httpClient.get(url) {
                header("Referer", "$base/index.html")
                if (sessionCookie != null) header("Cookie", sessionCookie!!)
            }
            val responseBody = response.bodyAsText()

            if (response.status == HttpStatusCode.OK && responseBody.isNotEmpty() && !isAuthFailure(responseBody)) {
                json.parseToJsonElement(responseBody).jsonObject
            } else {
                // 短信查询失败时尝试重新登录后重试一次
                invalidateSession()
                if (ensureLogin()) {
                    val retryResp = httpClient.get(url) {
                        header("Referer", "$base/index.html")
                        if (sessionCookie != null) header("Cookie", sessionCookie!!)
                    }
                    val retryBody = retryResp.bodyAsText()
                    if (retryResp.status == HttpStatusCode.OK && retryBody.isNotEmpty() && !isAuthFailure(retryBody)) {
                        json.parseToJsonElement(retryBody).jsonObject
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Goform getSmsList failed", e)
            null
        }
    }

    suspend fun sendSms(phoneNumber: String, message: String): Boolean {
        // gsmEncode: UTF-16BE → lowercase hex（与参考项目 requests.js gsmEncode 一致）
        val messageBody = message.toByteArray(Charsets.UTF_16BE)
            .joinToString("") { "%02x".format(it) }
        val resp = goformPost(mapOf(
            "isTest" to "false", "goformId" to "SEND_SMS",
            "Number" to phoneNumber, "MessageBody" to messageBody,
            // 参考项目 requests.js sendSms_UFI: ID=-1 (新短信), encode_type=0
            "ID" to "-1",
            "encode_type" to "0"
        ))
        return isGoformSuccess(resp)
    }

    suspend fun deleteSms(msgId: String): Boolean =
        isGoformSuccess(goformPost(mapOf(
            "isTest" to "false", "goformId" to "DELETE_SMS",
            "msg_id" to msgId, "notCallback" to "true"
        )))

    suspend fun markSmsRead(msgId: String): Boolean =
        isGoformSuccess(goformPost(mapOf(
            "isTest" to "false", "goformId" to "SET_MSG_READ",
            "msg_id" to msgId, "notCallback" to "true"
        )))

    private fun isLoginFailed(body: String): Boolean {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            val result = obj["result"]
            when {
                result == null -> false
                result.jsonPrimitive.isString -> {
                    val s = result.jsonPrimitive.content.lowercase()
                    // "3" = 参考项目专用登录失败码 (登录次数限制)
                    s == "failed" || s == "error" || s == "1" || s == "3"
                }
                else -> result.jsonPrimitive.int != 0
            }
        } catch (_: Exception) {
            body.contains("\"result\":\"failed\"", ignoreCase = true)
                || body.contains("\"result\":\"error\"", ignoreCase = true)
                || body.contains("password is wrong", ignoreCase = true)
                || body.contains("login failed", ignoreCase = true)
                || body.contains("\"result\":\"1\"", ignoreCase = true)
                || body.contains("\"result\":\"3\"", ignoreCase = true)
        }
    }

    private fun isGoformSuccess(resp: String?): Boolean {
        if (resp == null) return false
        if (resp.isEmpty()) return true
        return try {
            val obj = json.parseToJsonElement(resp).jsonObject
            val result = obj["result"]
            when {
                result == null -> resp.contains("\"success\"", ignoreCase = true)
                result.jsonPrimitive.isString -> {
                    val s = result.jsonPrimitive.content
                    s.equals("success", ignoreCase = true) || s == "0"
                }
                else -> result.jsonPrimitive.int == 0
            }
        } catch (_: Exception) {
            resp.contains("\"result\":\"success\"", ignoreCase = true)
                || resp.contains("\"result\":\"0\"", ignoreCase = true)
                || resp.contains("\"success\"", ignoreCase = true)
        }
    }

    // ==================== WiFi 管理 ====================

    /**
     * 查询 WiFi 模块状态和热点信息
     * 与参考项目 main.js queryWiFiModuleSwitch,queryAccessPointInfo 保持一致
     */
    suspend fun getWifiModuleInfo(): JsonObject? {
        return query(listOf("queryWiFiModuleSwitch", "queryAccessPointInfo"))
    }

    /**
     * 查询当前 WiFi 热点配置（AuthMode/EncrypType/Password/SSID 等）
     * 用于修改单项参数时保留其他参数不被重置
     */
    private suspend fun getCurrentWifiConfig(): Map<String, String> {
        val info = getWifiModuleInfo()
        val config = mutableMapOf<String, String>()
        if (info != null) {
            val list = info["ResponseList"]?.jsonArray
            if (list != null && list.isNotEmpty()) {
                val allAps = list.mapNotNull { try { it.jsonObject } catch (_: Exception) { null } }
                // 优先选择活跃的 AP，与 /wifi/settings 端点保持一致
                val ap = allAps.firstOrNull {
                    it["AccessPointSwitchStatus"]?.jsonPrimitive?.contentOrNull == "1"
                } ?: allAps.firstOrNull()
                if (ap != null) {
                    ap["AuthMode"]?.jsonPrimitive?.contentOrNull?.let { config["AuthMode"] = it }
                    ap["EncrypType"]?.jsonPrimitive?.contentOrNull?.let { config["EncrypType"] = it }
                    ap["SSID"]?.jsonPrimitive?.contentOrNull?.let { config["SSID"] = it }
                    ap["Password"]?.jsonPrimitive?.contentOrNull?.let { config["Password"] = it }
                    ap["ChipIndex"]?.jsonPrimitive?.contentOrNull?.let { config["ChipIndex"] = it }
                    ap["ApMaxStationNumber"]?.jsonPrimitive?.contentOrNull?.let { config["ApMaxStationNumber"] = it }
                    ap["ApBroadcastDisabled"]?.jsonPrimitive?.contentOrNull?.let { config["ApBroadcastDisabled"] = it }
                }
            }
        }
        return config
    }

    suspend fun getWifiSettings(): JsonObject? {
        return query(listOf(
            "wifi_chip1_ssid1_ssid", "wifi_onoff_state", "wifi_access_sta_num",
            "wifi_chip1_ssid1_access_sta_num", "wifi_5g_enable", "wifi_enable",
            "wifi_chip1_ssid1_passphrase", "wifi_chip",
            "wifi_chip1_ssid1_auth_mode", "wifi_chip1_ssid1_encryp_type",
            "wifi_chip1_ssid1_max_sta_num", "wifi_chip1_ssid1_broadcast_ssid"
        ))
    }

    suspend fun getConnectedClients(): JsonObject? {
        return query(listOf("wifi_access_sta_num", "station_list", "sta_ip_status", "lan_station_list", "hostNameList"))
    }

    // ==================== 基站信息 ====================

    /**
     * 查询基站信息（邻区 + 已锁定 + 当前服务小区元数据 + 信号）
     * 分两次查询: ZTE 设备在 neighbor_cell_info（大数组）与其他字段同时查询时
     * 会丢弃部分字段，因此分批查询后合并。
     */
    suspend fun getCellInfo(): JsonObject? {
        val batch1 = query(listOf("neighbor_cell_info", "locked_cell_info"))
        val batch2 = query(listOf(
            "Nr_pci", "Lte_pci", "Nr_fcn", "Lte_fcn",
            "Nr_bands", "Lte_bands", "network_type",
            "Z5g_rsrp", "lte_rsrp"
        ))
        if (batch1 == null && batch2 == null) return null
        val merged = JsonObject(mutableMapOf<String, JsonElement>().apply {
            fun mergeEntry(k: String, v: JsonElement) {
                when (v) {
                    is JsonPrimitive -> if (v.content.isNotEmpty()) put(k, v)
                    else -> put(k, v)  // JsonArray / JsonObject 保留
                }
            }
            batch1?.forEach { (k, v) -> mergeEntry(k, v) }
            batch2?.forEach { (k, v) -> mergeEntry(k, v) }
        })
        return merged.ifEmpty { null }
    }

    // ==================== LAN/DHCP 状态 ====================

    /**
     * 查询 LAN/DHCP 配置状态
     * 参考项目 main.js L4897: lan_ipaddr, lan_netmask, mac_address, dhcpEnabled, dhcpStart, dhcpEnd, dhcpLease_hour, mtu, tcp_mss
     */
    suspend fun getLanSettings(): JsonObject? {
        return query(listOf(
            "lan_ipaddr", "lan_netmask", "mac_address", "dhcpEnabled",
            "dhcpStart", "dhcpEnd", "dhcpLease_hour", "mtu", "tcp_mss"
        ))
    }

    // ==================== MAC 黑名单 ====================

    /**
     * 查询设备访问控制列表（MAC 黑名单）
     * 参考项目 main.js L2480: queryDeviceAccessControlList
     */
    suspend fun queryDeviceAccessControlList(): JsonObject? {
        return query(listOf("queryDeviceAccessControlList"))
    }

    /**
     * 设置设备访问控制列表（添加/移除 MAC 黑名单）
     * 参考项目 main.js L2622-2653: setDeviceAccessControlList
     * @param aclMode "0"=关闭, "1"=白名单, "2"=黑名单
     * @param macList MAC 地址列表，分号分隔
     * @param nameList 设备名列表，分号分隔（与 macList 一一对应）
     */
    suspend fun setDeviceAccessControlList(aclMode: String, macList: String = "", nameList: String = ""): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "setDeviceAccessControlList",
            "AclMode" to aclMode,
            "BlackMacList" to if (aclMode == "2") macList else "",
            "BlackNameList" to if (aclMode == "2") nameList else "",
            "WhiteMacList" to if (aclMode == "1") macList else "",
            "WhiteNameList" to if (aclMode == "1") nameList else ""
        )))
    }

    /**
     * setAccessPointInfo — WiFi 基础设置（SSID、加密、密码、最大连接数）
     * 与参考项目 main.js setAccessPointInfo 保持一致
     *
     * 关键逻辑：
     * - AuthMode=OPEN 时 EncrypType 必须为 NONE，且不发送 Password
     * - AuthMode≠OPEN 时 EncrypType 自动推导为 CCMP
     * - Password 必须 Base64 编码
     */
    suspend fun setWifiConfig(
        ssid: String? = null,
        authMode: String? = null,
        encrypType: String? = null,
        passphrase: String? = null,
        maxStaNum: Int? = null,
        broadcastDisabled: Int? = null,
        chipIndex: String? = null
    ): Boolean {
        val params = mutableMapOf("isTest" to "false", "goformId" to "setAccessPointInfo")

        // 关键修复: 当 authMode 为 null 时，读取当前配置以保留 AuthMode/EncrypType
        // 与 setWifiPassword() / setWifiSSID() 的保留逻辑保持一致
        // ZTE setAccessPointInfo 会重置省略字段为默认值，必须发送完整参数
        val current = if (authMode == null || ssid == null) getCurrentWifiConfig() else emptyMap()

        // SSID: 优先使用传入值，否则保留当前 SSID
        val effectiveSsid = ssid?.trim() ?: current["SSID"]?.trim()
        effectiveSsid?.let { params["SSID"] = it }

        // AuthMode / EncrypType 联动（参考项目 main.js L2375-2377）
        val effectiveAuth = authMode ?: current["AuthMode"]
        if (effectiveAuth != null) {
            params["AuthMode"] = effectiveAuth
            if (effectiveAuth == "OPEN") {
                params["EncrypType"] = "NONE"
            } else {
                params["EncrypType"] = encrypType ?: current["EncrypType"] ?: "CCMP"
            }
        } else {
            // 最后保底: 默认 WPA2PSK + CCMP
            params["AuthMode"] = "WPA2PSK"
            params["EncrypType"] = encrypType ?: "CCMP"
        }

        // Password: Base64 编码，OPEN 模式不发送密码（参考项目 main.js L2407-2409）
        val effectiveEncryp = params["EncrypType"]
        if (effectiveAuth != "OPEN" && effectiveEncryp != "NONE") {
            val effectivePwd = passphrase ?: current["Password"]?.let { base64Decode(it) }
            effectivePwd?.let { params["Password"] = base64Encode(it) }
        }

        maxStaNum?.let { params["ApMaxStationNumber"] = it.toString() }
        params["ApBroadcastDisabled"] = (broadcastDisabled ?: current["ApBroadcastDisabled"]?.toIntOrNull() ?: 0).toString()
        params["ApIsolate"] = "0"
        params["AccessPointIndex"] = "0"
        params["ChipIndex"] = chipIndex ?: current["ChipIndex"] ?: "0"
        AppLogger.i(tag, "setWifiConfig: SSID=${params["SSID"]} Auth=${params["AuthMode"]} Enc=${params["EncrypType"]} Chip=${params["ChipIndex"]}")
        return isGoformSuccess(goformPost(params))
    }

    /**
     * SET_WIFI_ADV_CONFIG — WiFi 高级设置（信道、带宽、模式、国家码）
     */
    suspend fun setWifiAdvConfig(
        channel: String? = null,
        bandwidth: Int? = null,
        mode: String? = null,
        countryCode: String? = null
    ): Boolean {
        val params = mutableMapOf("isTest" to "false", "goformId" to "SET_WIFI_ADV_CONFIG")
        channel?.let { params["wifiChannel"] = it }
        bandwidth?.let { params["wifiBandwidth"] = it.toString() }
        mode?.let { params["wifiMode"] = it }
        countryCode?.let { params["wifiCountryCode"] = it }
        return isGoformSuccess(goformPost(params))
    }

    /**
     * SET_WIFI_POWER — WiFi 发射功率
     */
    suspend fun setWifiPower(level: Int): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "SET_WIFI_POWER",
            "wifiPowerLevel" to level.toString()
        )))
    }

    /**
     * SET_WIFI_GUEST — 访客 WiFi 设置
     */
    suspend fun setWifiGuest(
        enabled: Boolean? = null,
        ssid: String? = null,
        authMode: String? = null
    ): Boolean {
        val params = mutableMapOf("isTest" to "false", "goformId" to "SET_WIFI_GUEST")
        enabled?.let { params["wifiGuestEnable"] = if (it) "1" else "0" }
        ssid?.let { params["wifiGuestSSID"] = it }
        authMode?.let { params["wifiGuestAuthMode"] = it }
        return isGoformSuccess(goformPost(params))
    }

    // ==================== 控制操作 ====================

    /**
     * 开关移动数据
     * 优先使用文档定义的 CONNECT_NETWORK / DISCONNECT_NETWORK，
     * 部分固件使用 SET_DATA_ENABLED，作为 fallback
     */
    suspend fun setMobileData(enabled: Boolean): Boolean {
        val primaryId = if (enabled) "CONNECT_NETWORK" else "DISCONNECT_NETWORK"
        if (isGoformSuccess(goformPost(mapOf("isTest" to "false", "goformId" to primaryId)))) return true
        // fallback: 部分固件使用 SET_DATA_ENABLED
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false", "goformId" to "SET_DATA_ENABLED",
            "data" to if (enabled) "1" else "0"
        )))
    }

    /**
     * 修改 WiFi SSID
     * 使用 setAccessPointInfo goformId（参考项目 main.js）
     *
     * 关键修复: 必须先查询当前 AuthMode/EncrypType/Password，
     * 否则设备会用默认值重置加密配置，导致热点失效
     */
    suspend fun setWifiSSID(ssid: String): Boolean {
        val current = getCurrentWifiConfig()
        val authMode = current["AuthMode"] ?: "WPA2PSK"
        val encrypType = current["EncrypType"] ?: "CCMP"
        val chipIndex = current["ChipIndex"] ?: "0"

        val params = mutableMapOf(
            "isTest" to "false",
            "goformId" to "setAccessPointInfo",
            "SSID" to ssid.trim(),
            "AuthMode" to authMode,
            "EncrypType" to encrypType,
            "AccessPointIndex" to "0",
            "ChipIndex" to chipIndex,
            "ApBroadcastDisabled" to (current["ApBroadcastDisabled"] ?: "0"),
            "ApIsolate" to "0"
        )
        if (authMode != "OPEN" && encrypType != "NONE") {
            current["Password"]?.let { params["Password"] = it }
        }
        AppLogger.i(tag, "setWifiSSID: SSID=$ssid Chip=$chipIndex Auth=$authMode")
        return isGoformSuccess(goformPost(params))
    }

    /**
     * 开关 WiFi 模块
     *
     * 关键修复: 参考项目 main.js 的实现方式：
     * - 关闭 WiFi: switchWiFiModule + SwitchOption=0
     * - 开启 WiFi: switchWiFiChip + ChipEnum=chip1 (默认 2.4G)，而不是 switchWiFiModule + SwitchOption=1
     */
    suspend fun setWifiEnabled(enabled: Boolean): Boolean {
        if (enabled) {
            // 开启 WiFi 用 switchWiFiChip（参考项目 main.js L1463-1468）
            return isGoformSuccess(goformPost(mapOf(
                "isTest" to "false",
                "goformId" to "switchWiFiChip",
                "ChipEnum" to "chip1",
                "GuestEnable" to "0"
            )))
        } else {
            // 关闭 WiFi 用 switchWiFiModule（参考项目 main.js L1458-1462）
            return isGoformSuccess(goformPost(mapOf(
                "isTest" to "false",
                "goformId" to "switchWiFiModule",
                "SwitchOption" to "0"
            )))
        }
    }

    /**
     * 修改 WiFi 密码
     * 使用 setAccessPointInfo goformId（参考项目 main.js）
     *
     * 关键修复: 不硬编码 AuthMode，先查询当前配置并保留
     */
    suspend fun setWifiPassword(password: String): Boolean {
        val current = getCurrentWifiConfig()
        val authMode = current["AuthMode"] ?: "WPA2PSK"
        val encrypType = if (authMode == "OPEN") "NONE" else (current["EncrypType"] ?: "CCMP")
        val chipIndex = current["ChipIndex"] ?: "0"

        val params = mutableMapOf(
            "isTest" to "false",
            "goformId" to "setAccessPointInfo",
            "Password" to base64Encode(password),
            "AuthMode" to authMode,
            "EncrypType" to encrypType,
            "ApBroadcastDisabled" to (current["ApBroadcastDisabled"] ?: "0"),
            "ApIsolate" to "0",
            "AccessPointIndex" to "0",
            "ChipIndex" to chipIndex
        )
        current["SSID"]?.let { params["SSID"] = it }
        AppLogger.i(tag, "setWifiPassword: Chip=$chipIndex Auth=$authMode")
        return isGoformSuccess(goformPost(params))
    }

    /**
     * 重启设备
     * 使用 goform_set_cmd_process POST 方式
     */
    suspend fun rebootDevice(): Boolean {
        val resp = goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "REBOOT_DEVICE"
        ))
        return isGoformSuccess(resp)
    }

    /**
     * 恢复出厂设置
     */
    suspend fun factoryReset(): Boolean {
        val resp = goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "FACTORY_RESET"
        ))
        return isGoformSuccess(resp)
    }

    /**
     * 设置调试模式 (ADB)
     * 与参考项目 ShellKano.kt 保持一致：使用 USB_PORT_SETTING
     */
    suspend fun setDebugMode(enabled: Boolean): Boolean {
        val resp = goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "USB_PORT_SETTING",
            "usb_port_switch" to if (enabled) "1" else "0"
        ))
        return isGoformSuccess(resp)
    }

    /**
     * 切换 USB 网络协议
     * 与参考项目 main.js SET_USB_NETWORK_PROTOCAL 保持一致
     */
    suspend fun setUsbMode(mode: Int): Boolean {
        val resp = goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "SET_USB_NETWORK_PROTOCAL",
            "usb_network_protocal" to mode.toString()
        ))
        return isGoformSuccess(resp)
    }

    /**
     * 设置网络制式偏好
     */
    suspend fun setBearerPreference(preference: String): Boolean {
        val resp = goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "SET_BEARER_PREFERENCE",
            "BearerPreference" to preference
        ))
        return isGoformSuccess(resp)
    }

    /**
     * 连接网络 (拨号)
     */
    suspend fun connectNetwork(): Boolean {
        val resp = goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "CONNECT_NETWORK"
        ))
        return isGoformSuccess(resp)
    }

    /**
     * 断开网络
     */
    suspend fun disconnectNetwork(): Boolean {
        val resp = goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "DISCONNECT_NETWORK"
        ))
        return isGoformSuccess(resp)
    }

    /**
     * 设置连接模式 (自动/手动拨号)
     */
    suspend fun setConnectionMode(mode: String): Boolean {
        val resp = goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "SET_CONNECTION_MODE",
            "ConnectionMode" to mode
        ))
        return isGoformSuccess(resp)
    }

    // ==================== 频段锁定 ====================

    /**
     * LTE_BAND_LOCK — 锁定 4G 频段
     * @param bands 逗号分隔的频段号，如 "1,3,5,8,34,38,39,40,41"
     */
    suspend fun lockLteBands(bands: String): Boolean {
        val resp = goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "LTE_BAND_LOCK",
            "lte_band_lock" to bands
        ))
        return isGoformSuccess(resp)
    }

    /**
     * NR_BAND_LOCK — 锁定 5G 频段
     * @param bands 逗号分隔的频段号，如 "1,5,8,28,41,78"
     */
    suspend fun lockNrBands(bands: String): Boolean {
        val resp = goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "NR_BAND_LOCK",
            "nr_band_lock" to bands
        ))
        return isGoformSuccess(resp)
    }

    // ==================== 设备设置状态查询 ====================

    /**
     * 查询设备当前设置状态（LED、性能模式、漫游、Bearer 等）
     * 返回 goform GET 的原始 Map 供路由层解析
     */
    suspend fun queryDeviceSettings(): Map<String, JsonElement>? {
        return query(listOf(
            "indicator_light_switch",
            "performance_mode",
            "roam_setting_option",
            "dial_roam_setting_option",
            "net_select",
            "lte_band_lock",
            "nr_band_lock",
            "usb_port_switch",
            "wifi_nfc_switch",
            "samba_switch",
            "restart_schedule_switch",
            "restart_time",
            "sleep_sysIdleTimeToSleep",
            "is_support_nfc_functions",
            "usb_network_protocal",
            "BearerPreference",
            "connection_mode"
        ))
    }

    // ==================== APN 配置 ====================

    /**
     * APN_PROC_EX — APN 配置管理
     * 与参考项目 requests.js saveAPNProfile / switchAPNAuto 保持一致
     *
     * 参数说明:
     * - autoSelect=true → apn_mode=auto (自动选择 APN)
     * - autoSelect=false + name/apn 等 → apn_mode=manual, apn_action=save (手动保存 APN)
     */
    suspend fun setApnConfig(
        autoSelect: Boolean? = null,
        name: String? = null,
        apn: String? = null,
        user: String? = null,
        pass: String? = null,
        authType: Int? = null,
        pdpType: Int? = null
    ): Boolean {
        val params = mutableMapOf("isTest" to "false", "goformId" to "APN_PROC_EX")

        if (autoSelect == true) {
            // 自动模式: 只需 apn_mode=auto
            params["apn_mode"] = "auto"
        } else {
            // 手动模式: 需要完整 APN profile 参数
            params["apn_mode"] = "manual"
            params["apn_action"] = "save"
            params["apn_select"] = "manual"
            params["wan_dial"] = "*99#"
            params["apn_wan_dial"] = "*99#"
            params["apn_pdp_select"] = "auto"
            params["apn_pdp_addr"] = ""
            params["pdp_select"] = "auto"
            params["pdp_addr"] = ""
            params["dns_mode"] = "auto"
            params["prefer_dns_manual"] = ""
            params["standby_dns_manual"] = ""
            params["index"] = "0"

            name?.let {
                params["profile_name"] = it
            }
            apn?.let {
                params["apn_wan_apn"] = it
                params["wan_apn"] = it
            }
            user?.let {
                params["apn_ppp_username"] = it
                params["ppp_username"] = it
            }
            pass?.let {
                params["apn_ppp_passwd"] = it
                params["ppp_passwd"] = it
            }
            authType?.let {
                val authStr = it.toString()
                params["apn_ppp_auth_mode"] = authStr
                params["ppp_auth_mode"] = authStr
            }
            pdpType?.let {
                val pdpStr = it.toString()
                params["apn_pdp_type"] = pdpStr
                params["pdp_type"] = pdpStr
            }
        }
        return isGoformSuccess(goformPost(params))
    }

    /**
     * APN_PROC_EX — 删除 APN profile
     * 参考项目 requests.js deleteAPNProfile:
     * goformId=APN_PROC_EX, apn_mode=manual, apn_action=delete, index=N
     */
    suspend fun deleteApnProfile(index: Int): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "APN_PROC_EX",
            "apn_mode" to "manual",
            "apn_action" to "delete",
            "index" to index.toString()
        )))
    }

    /**
     * APN_PROC_EX — 手动切换 APN (选择指定 index 的 profile)
     * 参考项目 requests.js switchAPNAuto (isAuto=false):
     * goformId=APN_PROC_EX, apn_mode=manual, apn_action=set_default,
     * set_default_flag=1, index=N
     */
    suspend fun switchApnManual(index: Int = 0): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "APN_PROC_EX",
            "apn_mode" to "manual",
            "apn_action" to "set_default",
            "set_default_flag" to "1",
            "apn_pdp_type" to "",
            "index" to index.toString()
        )))
    }

    // ==================== ADB 控制 ====================

    /**
     * USB_PORT_SETTING — 开关 ADB 调试
     * goformId=USB_PORT_SETTING, usb_port_switch=1 (开启) / 0 (关闭)
     */
    suspend fun setUsbPortSwitch(enabled: Boolean): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "USB_PORT_SETTING",
            "usb_port_switch" to if (enabled) "1" else "0"
        )))
    }

    // ==================== FOTA 禁用 ====================

    /**
     * SetUpgAutoSetting — 禁用/启用 FOTA 自动更新
     */
    suspend fun setFotaEnabled(enabled: Boolean): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "SetUpgAutoSetting",
            "UpgMode" to if (enabled) "1" else "0",
            "UpgIntervalDay" to "114514",
            "UpgRoamPermission" to "0"
        )))
    }

    // ==================== 系统管理 ====================

    /**
     * CHANGE_PASSWORD — 修改管理密码
     * 参数为 SHA256(password).upper()
     */
    suspend fun changePassword(oldPassword: String, newPassword: String): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "CHANGE_PASSWORD",
            "oldPassword" to sha256Hex(oldPassword).uppercase(),
            "newPassword" to sha256Hex(newPassword).uppercase()
        )))
    }

    // ==================== TR-069 ====================

    /**
     * setTR069Config — TR-069 远程管理配置
     */
    suspend fun setTr069Config(
        enable: Boolean? = null,
        url: String? = null,
        username: String? = null,
        password: String? = null
    ): Boolean {
        val params = mutableMapOf("isTest" to "false", "goformId" to "setTR069Config")
        enable?.let { params["tr069_enable"] = if (it) "1" else "0" }
        url?.let { params["tr069_url"] = it }
        username?.let { params["tr069_username"] = it }
        password?.let { params["tr069_password"] = it }
        return isGoformSuccess(goformPost(params))
    }

    // ==================== SIM 卡槽切换 ====================

    /**
     * 切换 SIM 卡槽 (双卡设备)
     * 与参考项目 main.js SET_SIM_SLOT 保持一致
     * @param slot 0=移动, 1=电信, 2=联通, 11=外置
     */
    suspend fun switchSimSlot(slot: String): Boolean {
        val resp = goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "SET_SIM_SLOT",
            "sim_slot" to slot
        ))
        return isGoformSuccess(resp)
    }

    // ==================== 指示灯 ====================

    /**
     * INDICATOR_LIGHT_SETTING — 指示灯开关
     * 与参考项目 main.js 保持一致
     */
    suspend fun setIndicatorLight(enabled: Boolean): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "INDICATOR_LIGHT_SETTING",
            "indicator_light_switch" to if (enabled) "1" else "0"
        )))
    }

    // ==================== 性能模式 ====================

    /**
     * PERFORMANCE_MODE_SETTING — 性能模式
     * 与参考项目 main.js 保持一致
     */
    suspend fun setPerformanceMode(mode: Int): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "PERFORMANCE_MODE_SETTING",
            "performance_mode" to mode.toString()
        )))
    }

    // ==================== 漫游设置 ====================

    /**
     * SET_CONNECTION_MODE 含漫游参数
     * 与参考项目 main.js 保持一致
     */
    suspend fun setRoaming(enabled: Boolean): Boolean {
        val roamVal = if (enabled) "on" else "off"
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "SET_CONNECTION_MODE",
            "ConnectionMode" to "auto_dial",
            "roam_setting_option" to roamVal,
            "dial_roam_setting_option" to roamVal
        )))
    }

    // ==================== WiFi 芯片切换 ====================

    /**
     * switchWiFiChip — 切换 WiFi 频段 (2.4G / 5G)
     * 参考项目 main.js: goformId=switchWiFiChip, ChipEnum=chip1|chip2, GuestEnable=0
     */
    suspend fun switchWifiChip(chip: String): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "switchWiFiChip",
            "ChipEnum" to chip,
            "GuestEnable" to "0"
        )))
    }

    // ==================== SAMBA 文件共享 ====================

    /**
     * SAMBA_SETTING — SMB 文件共享开关
     * 参考项目 main.js: samba_switch = '0'|'1'
     */
    suspend fun setSambaSetting(enabled: Boolean): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "SAMBA_SETTING",
            "samba_switch" to if (enabled) "1" else "0"
        )))
    }

    // ==================== WiFi NFC ====================

    /**
     * WIFI_NFC_SET — NFC 开关
     * 参考项目 main.js: web_wifi_nfc_switch = '0'|'1'
     */
    suspend fun setWifiNfc(enabled: Boolean): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "WIFI_NFC_SET",
            "web_wifi_nfc_switch" to if (enabled) "1" else "0"
        )))
    }

    // ==================== 基站锁定 ====================

    /**
     * CELL_LOCK — 锁定特定基站 (PCI + EARFCN + RAT)
     * 参考项目 main.js: goformId=CELL_LOCK, pci, earfcn, rat
     */
    suspend fun cellLock(pci: String, earfcn: String, rat: String): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "CELL_LOCK",
            "pci" to pci,
            "earfcn" to earfcn,
            "rat" to rat
        )))
    }

    /**
     * UNLOCK_ALL_CELL — 解锁所有基站
     * 参考项目 main.js: goformId=UNLOCK_ALL_CELL (无额外参数)
     */
    suspend fun unlockAllCell(): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "UNLOCK_ALL_CELL"
        )))
    }

    // ==================== 设备关机 ====================

    /**
     * SHUTDOWN_DEVICE — 关机
     * 参考项目 main.js: goformId=SHUTDOWN_DEVICE (无额外参数)
     */
    suspend fun shutdownDevice(): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "SHUTDOWN_DEVICE"
        )))
    }

    // ==================== 定时重启 ====================

    /**
     * RESTART_SCHEDULE_SETTING — 定时重启
     * 参考项目 main.js: restart_time=HH:MM, restart_schedule_switch='0'|'1'
     */
    suspend fun setRestartSchedule(enabled: Boolean, time: String): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "RESTART_SCHEDULE_SETTING",
            "restart_time" to time,
            "restart_schedule_switch" to if (enabled) "1" else "0"
        )))
    }

    // ==================== WiFi 休眠 ====================

    /**
     * SET_WIFI_SLEEP_INFO — WiFi 休眠时间
     * 参考项目 main.js: sleep_sysIdleTimeToSleep = 时间值字符串
     */
    suspend fun setWifiSleep(time: String): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "SET_WIFI_SLEEP_INFO",
            "sleep_sysIdleTimeToSleep" to time
        )))
    }

    // ==================== 设备名称 ====================

    /**
     * EDIT_HOSTNAME — 修改连接设备的主机名
     * 参考项目 requests.js: goformId=EDIT_HOSTNAME, mac, hostname
     */
    suspend fun setHostname(mac: String, hostname: String): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "EDIT_HOSTNAME",
            "mac" to mac,
            "hostname" to hostname
        )))
    }

    // ==================== DHCP 设置 ====================

    /**
     * DHCP_SETTING — LAN/DHCP 配置
     * 参考项目 main.js: lanIp, lanNetmask, lanDhcpType, dhcpStart, dhcpEnd, dhcpLease
     */
    suspend fun setDhcpSetting(
        lanIp: String, lanNetmask: String, dhcpType: String,
        dhcpStart: String, dhcpEnd: String, dhcpLease: String
    ): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "DHCP_SETTING",
            "lanIp" to lanIp,
            "lanNetmask" to lanNetmask,
            "lanDhcpType" to dhcpType,
            "dhcpStart" to dhcpStart,
            "dhcpEnd" to dhcpEnd,
            "dhcpLease" to dhcpLease,
            "dhcp_reboot_flag" to "1",
            "mac_ip_reset" to if (dhcpType == "SERVER") "1" else "0"
        )))
    }

    // ==================== 工具方法 ====================

    /**
     * 登出（注销当前会话）
     * 与参考项目 requests.js LOGOUT 一致
     */
    suspend fun logout(): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "LOGOUT"
        )))
    }

    /**
     * 查询流量限额设置（参考项目 requests.js getDataUsage）
     */
    suspend fun getDataUsage(): JsonObject? {
        return query(listOf(
            "flux_data_volume_limit_switch", "data_volume_limit_switch",
            "data_volume_limit_unit", "data_volume_limit_size",
            "data_volume_alert_percent",
            "monthly_tx_bytes", "monthly_rx_bytes", "monthly_time",
            "wan_auto_clear_flow_data_switch", "traffic_clear_date"
        ))
    }

    /**
     * 设置流量限额（参考项目 main.js DATA_LIMIT_SETTING）
     */
    suspend fun setDataLimit(
        enabled: Boolean,
        limitSize: String? = null,
        limitUnit: String? = null,
        alertPercent: String? = null,
        autoClear: Boolean? = null,
        clearDate: String? = null
    ): Boolean {
        val params = mutableMapOf(
            "isTest" to "false",
            "goformId" to "DATA_LIMIT_SETTING",
            "data_volume_limit_switch" to if (enabled) "1" else "0",
            "flux_data_volume_limit_switch" to if (enabled) "1" else "0"
        )
        limitSize?.let { params["data_volume_limit_size"] = it; params["flux_data_volume_limit_size"] = it }
        limitUnit?.let { params["data_volume_limit_unit"] = it; params["flux_data_volume_limit_unit"] = it }
        alertPercent?.let { params["data_volume_alert_percent"] = it; params["flux_data_volume_alert_percent"] = it }
        autoClear?.let { params["wan_auto_clear_flow_data_switch"] = if (it) "1" else "0"; params["flux_auto_clear_flow_data_switch"] = params["wan_auto_clear_flow_data_switch"]!! }
        clearDate?.let { params["traffic_clear_date"] = it; params["flux_clear_date"] = it }
        params["notify_deviceui_enable"] = "0"
        params["flux_notify_deviceui_enable"] = "0"
        params["data_volume_limit_type"] = "0"
        params["flux_data_volume_limit_type"] = "0"
        params["apn_pdp_type"] = ""
        return isGoformSuccess(goformPost(params))
    }

    /**
     * 手动校准流量（参考项目 main.js FLOW_CALIBRATION_MANUAL）
     */
    suspend fun calibrateFlow(
        way: String = "data",
        data: String = "0",
        time: String = "0"
    ): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "FLOW_CALIBRATION_MANUAL",
            "calibration_way" to way,
            "time" to time,
            "data" to data
        )))
    }

    /**
     * 查询 APN 配置（参考项目 requests.js getAPNData）
     */
    suspend fun getApnConfig(): JsonObject? {
        return query(listOf(
            "apn_mode", "profile_name", "apn_wan_apn", "apn_wan_dial",
            "apn_ppp_auth_mode", "apn_ppp_username", "apn_ppp_passwd",
            "apn_pdp_type", "dns_mode", "prefer_dns_manual", "standby_dns_manual",
            "apn_Current_index", "apn_num_preset",
            "wan_apn_ui", "profile_name_ui", "pdp_type_ui"
        ))
    }

    /**
     * 查询 SIM PIN 状态（参考项目 requests.js getSimPinStatus）
     */
    suspend fun getSimPinStatus(): JsonObject? {
        return query(listOf("modem_main_state", "mc_modem_main_state", "puknumber", "pinnumber", "sim_pinnumber"))
    }

    // ==================== 网络类型映射 ====================

    companion object {
        /**
         * 将 ZTE Goform 返回的 network_type 数值码映射为可读字符串
         * 参考项目 main.js: res.network_type == '20' ? '5G' : res.network_type == '13' ? '4G' : res.network_type
         */
        fun mapNetworkType(code: String): String = when (code) {
            "20" -> "5G"
            "19" -> "5G NSA"
            "13" -> "4G"
            "9" -> "3G"
            "4" -> "2G"
            else -> code // 已经是可读格式则透传
        }
    }

    /**
     * 关闭 HttpClient，释放线程池和连接池资源
     */
    fun close() {
        try {
            httpClient.close()
            AppLogger.i(tag, "GoformClient HttpClient closed")
        } catch (e: Exception) {
            AppLogger.e(tag, "Error closing HttpClient", e)
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Base64 编码（参考项目 main.js 的 btoa()，用于 WiFi 密码） */
    private fun base64Encode(input: String): String =
        android.util.Base64.encodeToString(input.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

    internal fun base64Decode(input: String): String =
        try { String(android.util.Base64.decode(input, android.util.Base64.DEFAULT), Charsets.UTF_8) }
        catch (_: Exception) { input }
}
