package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.GoformQoS
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.Closeable
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Goform HTTP 客户端 — 核心基础设施
 *
 * 仅保留认证、通用查询/POST、QoS、生命周期管理。
 * 域特定方法已迁移至:
 * - GoformSignalClient  (信号/设备信息/流量/基站/LAN/DHCP)
 * - GoformWifiClient    (WiFi 管理)
 * - GoformNetworkClient (网络/频段/APN/流量限额/漫游)
 * - GoformDeviceClient  (设备控制/DHCP/黑名单/基站锁定)
 * - GoformSmsClient     (短信)
 * - GoformSimClient     (SIM 卡槽)
 */
class GoformClient(
    val deviceIp: String = "192.168.0.1",
    private val port: Int = 8080,
    private var password: String = "admin"
) : Closeable {
    private val tag = "GoformClient"
    internal val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val MAX_RESPONSE_BODY_SIZE = 262_144

    // QoS: 由 GoformQoS 单例统一管理设备通信并发（query 1-8, set 1-4 + TTL 缓存）
    // 本地 callSemaphore 已废弃，queryInternal/querySingleInternal/goformPostInternal 通过 GoformQoS 接入

    @Volatile private var isLoggedIn = false
    private val loginMutex = Mutex()
    @Volatile private var lastValidatedAt: Long = 0L
    private val sessionCacheTtlMs = 30_000L
    @Volatile private var lastLoginAttempt: Long = 0L
    private val baseLoginCooldownMs = 5_000L

    // 指数退避：连续登录失败时逐步延长冷却时间，防止 DataScheduler 11个协程同时风暴
    private val consecutiveLoginFailures = AtomicInteger(0)
    private val maxLoginBackoffMs = 60_000L  // 最大退避 60s

    @Volatile private var sessionCookie: String? = null
    @Volatile private var waVersion: String? = null
    @Volatile private var crVersion: String? = null

    private val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            engine {
                maxConnectionsCount = 8
                requestTimeout = 10_000
                endpoint {
                    maxConnectionsPerRoute = 6
                    connectTimeout = 3000
                    keepAliveTime = 30_000
                    pipelineMaxSize = 4
                }
            }
            expectSuccess = false
        }
    }

    // ==================== 公开基础设施 ====================

    fun baseUrl(): String =
        if (port == 80) "http://$deviceIp" else "http://$deviceIp:$port"

    /** 内部 HTTP GET，供域客户端使用 */
    internal suspend fun httpGet(url: String): HttpResponse {
        val base = baseUrl()
        return httpClient.get(if (url.startsWith("http")) url else "$base/$url") {
            header("Referer", "$base/index.html")
            if (sessionCookie != null) header("Cookie", sessionCookie!!)
        }
    }

    internal fun parseJson(body: String): JsonElement = json.parseToJsonElement(body)

    // ==================== 认证 ====================

    suspend fun ensureLogin(): Boolean {
        val now = System.currentTimeMillis()

        // 快速路径：TTL 内且已验证过，无需加锁
        if (isLoggedIn && (now - lastValidatedAt) < sessionCacheTtlMs) return true

        // 锁内：session 验证 + 登录原子执行，防止并发重复登录
        // backoff 检查也放在锁内，避免 TOCTOU 竞态（锁外读取 lastLoginAttempt 可能过时）
        return loginMutex.withLock {
            // 双重检查：可能在等锁期间已被其他协程登录成功
            if (isLoggedIn && (System.currentTimeMillis() - lastValidatedAt) < sessionCacheTtlMs) return@withLock true
            // 锁内验证 session，避免多协程并发验证
            if (isLoggedIn && validateSession()) {
                lastValidatedAt = System.currentTimeMillis()
                return@withLock true
            }
            // 锁内 backoff 检查：防止并发登录风暴
            // 在锁内读取 lastLoginAttempt 避免 TOCTOU 竞态，等锁期间其他协程可能已登录成功
            val nowLocked = System.currentTimeMillis()
            val failCount = consecutiveLoginFailures.get()
            if (failCount > 0) {
                val backoffMs = (baseLoginCooldownMs * (1L shl failCount.coerceAtMost(6))).coerceAtMost(maxLoginBackoffMs)
                if (nowLocked - lastLoginAttempt < backoffMs && lastLoginAttempt > 0L) {
                    AppLogger.d(tag, "Login cooling (failCount=$failCount, backoff=${backoffMs}ms, remaining=${backoffMs - (nowLocked - lastLoginAttempt)}ms)")
                    // 返回 isLoggedIn：等锁期间另一协程可能已登录成功，避免误报 false
                    return@withLock isLoggedIn
                }
            }
            // session 无效 → 标记未登录，继续执行登录流程
            isLoggedIn = false

            AppLogger.i(tag, "Performing login to $deviceIp:$port... (failCount=$failCount)")
            try {
                val base = baseUrl()
                val ldResp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=LD&isTest=false&_=${System.currentTimeMillis()}") {
                    header("Referer", "$base/index.html")
                }
                val ldBody = ldResp.bodyAsText()
                val ld = try { json.parseToJsonElement(ldBody).jsonObject["LD"]?.jsonPrimitive?.contentOrNull ?: "" } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" }
                val passHash = sha256Hex(password).uppercase()
                val encPwd = sha256Hex(passHash + ld).uppercase()

                val loginResp = httpClient.post("$base/goform/goform_set_cmd_process") {
                    header("Referer", "$base/index.html")
                    header("Origin", base)
                    header("Content-Type", "application/x-www-form-urlencoded")
                    setBody("isTest=false&goformId=LOGIN_MULTI_USER&user=admin&password=$encPwd&IP=localhost")
                }
                var loginBody = loginResp.bodyAsText()
                loginResp.headers["Set-Cookie"]?.split(";")?.firstOrNull()?.let { sessionCookie = it }

                if (loginResp.status != HttpStatusCode.OK || isLoginFailed(loginBody)) {
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
                        consecutiveLoginFailures.incrementAndGet()
                        isLoggedIn = false; lastLoginAttempt = System.currentTimeMillis()
                        return@withLock false
                    }
                }

                val infoResp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=wa_inner_version,cr_version&multi_data=1&isTest=false&_=${System.currentTimeMillis()}") {
                    header("Referer", "$base/index.html")
                    if (sessionCookie != null) header("Cookie", sessionCookie!!)
                }
                try {
                    val infoJson = json.parseToJsonElement(infoResp.bodyAsText()).jsonObject
                    waVersion = infoJson["wa_inner_version"]?.jsonPrimitive?.contentOrNull
                    crVersion = infoJson["cr_version"]?.jsonPrimitive?.contentOrNull
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}

                isLoggedIn = true
                consecutiveLoginFailures.set(0)  // 成功则重置
                lastLoginAttempt = System.currentTimeMillis()
                lastValidatedAt = System.currentTimeMillis()
                AppLogger.i(tag, "Login successful. wa=$waVersion cr=$crVersion")
                true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                AppLogger.e(tag, "Login failed", e)
                consecutiveLoginFailures.incrementAndGet()
                isLoggedIn = false
                lastLoginAttempt = System.currentTimeMillis()
                false
            }
        }
    }

    /**
     * 验证当前 session 是否有效。
     * 仅返回 boolean，不修改任何状态（调用方在 lock 内处理状态变更）。
     */
    private suspend fun validateSession(): Boolean {
        return try {
            val base = baseUrl()
            val resp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=LD&isTest=false&_=${System.currentTimeMillis()}") {
                header("Referer", "$base/index.html")
                if (sessionCookie != null) header("Cookie", sessionCookie!!)
            }
            val body = resp.bodyAsText()
            val isHtml = body.trimStart().startsWith("<")
            if (isHtml) return false
            val hasLd = try { json.parseToJsonElement(body).jsonObject.containsKey("LD") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
            hasLd
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
    }

    fun invalidateSession() {
        val newCount = consecutiveLoginFailures.incrementAndGet()
        AppLogger.w(tag, "Session invalidated (consecutive failures=$newCount)")
        isLoggedIn = false
        lastValidatedAt = 0L
    }

    fun resetLogin() {
        isLoggedIn = false
        lastValidatedAt = 0L
        consecutiveLoginFailures.set(0)
    }

    /**
     * 更新 goform 管理密码并重置登录状态。
     * 用于密码修改后立即生效，避免等待 session 过期。
     */
    fun updateGoformPassword(newPwd: String) {
        if (newPwd.isNotEmpty() && newPwd != password) {
            password = newPwd
            AppLogger.i(tag, "Goform password updated, resetting session")
            resetLogin()
        }
    }

    // ==================== 通用查询/POST ====================

    internal fun isAuthFailure(body: String): Boolean {
        if (body.isEmpty()) return false
        return body.contains("\"result\":\"not logged in\"", ignoreCase = true)
            || body.contains("\"result\":\"session\"", ignoreCase = true)
            || body.contains("login.html", ignoreCase = true)
            || body.contains("redirect", ignoreCase = true)
            || (body.trimStart().startsWith("<!DOCTYPE") || body.trimStart().startsWith("<html"))
    }

    suspend fun goformPost(params: Map<String, String>): String? {
        return goformPostInternal(params, retry = true)
    }

    private suspend fun goformPostInternal(params: Map<String, String>, retry: Boolean): String? {
        if (!ensureLogin()) {
            AppLogger.e(tag, "goformPost: not logged in, aborting ${params["goformId"]}")
            return null
        }
        return GoformQoS.withSetPermit {
            try {
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
                response.headers["Set-Cookie"]?.split(";")?.firstOrNull()?.let { sessionCookie = it }
                val body = response.bodyAsText()
                AppLogger.i(tag, "goformPost(${params["goformId"]}) [${response.status}]: ${body.take(200)}")
                if (response.status == HttpStatusCode.Found
                    || response.status == HttpStatusCode.SeeOther
                    || isAuthFailure(body)
                ) {
                    invalidateSession()
                    if (retry) { return@withSetPermit goformPostInternal(params, retry = false) }
                    return@withSetPermit null
                }
                if (response.status == HttpStatusCode.OK) body else null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                AppLogger.e(tag, "goformPost failed: ${params["goformId"]}", e)
                if (retry) { invalidateSession(); return@withSetPermit goformPostInternal(params, retry = false) }
                null
            }
        }
    }

    suspend fun query(commands: List<String>): JsonObject? {
        if (!ensureLogin()) return null
        return queryInternal(commands, retry = true)
    }

    private suspend fun queryInternal(commands: List<String>, retry: Boolean): JsonObject? {
        if (!ensureLogin()) return null

        // GoformQoS TTL 缓存：相同查询在 TTL 内返回缓存结果
        val cacheKey = commands.sorted().joinToString(",")
        if (retry) {
            GoformQoS.getCachedQuery(cacheKey)?.let { return it }
        }

        return GoformQoS.withQueryPermit {
            try {
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
                if (responseBody.length > MAX_RESPONSE_BODY_SIZE) {
                    AppLogger.w(tag, "query: response too large (${responseBody.length}B), rejecting")
                    return@withQueryPermit null
                }
                val isAuthErr = response.status == HttpStatusCode.Found
                    || response.status == HttpStatusCode.SeeOther
                    || isAuthFailure(responseBody)
                if (response.status == HttpStatusCode.OK && !isAuthErr) {
                    val parsed = json.parseToJsonElement(responseBody)
                    if (parsed is JsonObject) {
                        GoformQoS.cacheQuery(cacheKey, parsed)
                        parsed
                    } else { AppLogger.w(tag, "query: parsed non-object: $parsed"); null }
                } else if (isAuthErr && retry) {
                    invalidateSession(); return@withQueryPermit queryInternal(commands, retry = false)
                } else {
                    if (retry && response.status != HttpStatusCode.OK) { invalidateSession(); return@withQueryPermit queryInternal(commands, retry = false) }
                    null
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                AppLogger.e(tag, "Goform query failed", e)
                if (retry) { invalidateSession(); return@withQueryPermit queryInternal(commands, retry = false) }
                null
            }
        }
    }

    suspend fun querySingle(command: String): JsonElement? {
        if (!ensureLogin()) return null
        return querySingleInternal(command, retry = true)
    }

    private suspend fun querySingleInternal(command: String, retry: Boolean): JsonElement? {
        if (!ensureLogin()) return null
        return GoformQoS.withQueryPermit {
            try {
                val base = baseUrl()
                val url = "$base/goform/goform_get_cmd_process?cmd=$command&isTest=false&_=${System.currentTimeMillis()}"
                val response = httpClient.get(url) {
                    header("Referer", "$base/index.html")
                    if (sessionCookie != null) header("Cookie", sessionCookie!!)
                }
                response.headers["Set-Cookie"]?.split(";")?.firstOrNull()?.let { sessionCookie = it }
                val responseBody = response.bodyAsText()
                if (responseBody.length > MAX_RESPONSE_BODY_SIZE) {
                    AppLogger.w(tag, "querySingle: response too large (${responseBody.length}B), rejecting")
                    return@withQueryPermit null
                }
                if (response.status == HttpStatusCode.OK && !isAuthFailure(responseBody)) {
                    json.parseToJsonElement(responseBody)
                } else {
                    if (retry) { invalidateSession(); return@withQueryPermit querySingleInternal(command, retry = false) }
                    null
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                AppLogger.e(tag, "Goform querySingle failed: $command", e)
                invalidateSession()
                if (retry) { return@withQueryPermit querySingleInternal(command, retry = false) }
                null
            }
        }
    }

    // ==================== AD 防重放 ====================

    /** 当缓存缺失时，从设备获取 wa_inner_version 和 cr_version 并缓存 */
    private suspend fun fetchVersionInfo() {
        try {
            val base = baseUrl()
            val infoResp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=wa_inner_version,cr_version&multi_data=1&isTest=false&_=${System.currentTimeMillis()}") {
                header("Referer", "$base/index.html")
                if (sessionCookie != null) header("Cookie", sessionCookie!!)
            }
            val infoJson = json.parseToJsonElement(infoResp.bodyAsText()).jsonObject
            waVersion = infoJson["wa_inner_version"]?.jsonPrimitive?.contentOrNull
            crVersion = infoJson["cr_version"]?.jsonPrimitive?.contentOrNull
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {}
    }

    private suspend fun computeAd(): String {
        return try {
            // waVersion/crVersion 登录时已获取，版本号极少变化，复用缓存避免每次写操作都重复请求
            if (waVersion == null || crVersion == null) {
                fetchVersionInfo()
            }
            val wa = waVersion ?: ""; val cr = crVersion ?: ""
            if (wa.isEmpty() || cr.isEmpty()) {
                AppLogger.w(tag, "computeAd: version fields empty wa='$wa' cr='$cr'")
                return ""
            }
            val parsed = sha256Hex(wa + cr).uppercase()
            val rd = getRd() ?: run { AppLogger.w(tag, "computeAd: RD is null"); return "" }
            sha256Hex(parsed + rd).uppercase()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
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
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            AppLogger.e(tag, "getRd failed", e)
            null
        }
    }

    // ==================== 工具方法 ====================

    suspend fun logout(): Boolean {
        return isGoformSuccess(goformPost(mapOf(
            "isTest" to "false",
            "goformId" to "LOGOUT"
        )))
    }

    internal fun isGoformSuccess(resp: String?): Boolean {
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

    internal fun isLoginFailed(body: String): Boolean {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            val result = obj["result"]
            when {
                result == null -> false
                result.jsonPrimitive.isString -> {
                    val s = result.jsonPrimitive.content.lowercase(Locale.ROOT)
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

    internal fun sha256Hex(input: String): String {
        val digest = sha256Digest.get()!!
        digest.reset()
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    internal fun base64Encode(input: String): String =
        android.util.Base64.encodeToString(input.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

    internal fun base64Decode(input: String): String =
        try { String(android.util.Base64.decode(input, android.util.Base64.DEFAULT), Charsets.UTF_8) }
        catch (_: Exception) { input }

    // ==================== 生命周期/QoS ====================

    override fun close() {
        try {
            httpClient.close()
            AppLogger.i(tag, "GoformClient HttpClient closed")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            AppLogger.e(tag, "Error closing HttpClient", e)
        }
    }

    // ==================== QoS 委托给 GoformQoS ====================

    fun setQosEnabled(enabled: Boolean) {
        AppLogger.i(tag, "QoS is now managed by GoformQoS singleton (local toggle ignored)")
    }

    fun adjustQoS(permits: Int) {
        GoformQoS.adaptiveAdjust(permits.coerceIn(1, 8), (permits / 2).coerceIn(1, 4))
    }

    fun getQosStatus(): Map<String, Any> = GoformQoS.getStatus()

    companion object {
        private val sha256Digest = ThreadLocal.withInitial {
            MessageDigest.getInstance("SHA-256")
        }

        fun mapNetworkType(code: String): String = when (code) {
            "20" -> "5G"
            "19" -> "5G NSA"
            "13" -> "4G"
            "9" -> "3G"
            "4" -> "2G"
            else -> code
        }
    }
}
