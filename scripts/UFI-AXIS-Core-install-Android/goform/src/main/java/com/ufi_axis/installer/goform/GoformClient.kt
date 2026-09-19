package com.ufi_axis.installer.goform

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URL
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/**
 * Goform 客户端（ZTE 设备私有协议防腐层，移植自 core/goform 的 [GoformClient]）。
 *
 * 认证模型（与 UFI-TOOLS 对齐）：
 *  - 每次 query / querySingle / goformPost 前都先 ensureLogin()；
 *  - 登录用 goformId=LOGIN（LAN 连接），[loginIp] 可选；加密口令
 *    encPwd = sha256( sha256(password) + LD )，均为大写十六进制，LD 来自 goform_get_cmd_process?cmd=LD；
 *  - 写操作需在 body 附带 AD = sha256( sha256(wa+cr) + RD )，均为大写；wa/cr 来自
 *    wa_inner_version/cr_version，RD 来自 goform_get_cmd_process?cmd=RD；
 *  - 登录态带 TTL 缓存与 session 校验，避免频繁登录；失败指数退避（封顶 10s）；
 *  - 会话失效（auth failure）时自动 invalidate，幂等写操作会重登并重试一次。
 *
 * 读/写均依赖登录态（携带 Set-Cookie 返回的 session）；裸读会被设备以
 * "none secure connection" / "not logged in" 拒绝。
 */
class GoformClient(
    private val deviceIp: String = "192.168.0.1",
    private val port: Int = 8080,
    // 无默认值：口令必须由调用方显式注入（依赖默认值会静默登录失败）。
    private var password: String,
    private val username: String = "admin",
    // LAN 连接用 LOGIN；如需多用户登录可传 "LOGIN_MULTI_USER"。
    private val loginGoformId: String = "LOGIN",
    // 可选：部分固件带 IP 区分多用户（如 localhost）。
    private val loginIp: String? = null,
) : GoformGateway {

    private val tag = "GoformClient"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    private val loginMutex = Mutex()
    private val consecutiveLoginFailures = AtomicInteger(0)

    /**
     * cookie 与「是否已登录」**必须一起换**，合成一个不可变对象整体替换，避免
     * 「判定用一份、发请求用另一份」的 check-then-act 竞态（判定通过就一定有当时那个 cookie）。
     */
    private class SessionSnapshot(val cookie: String?, val loggedIn: Boolean)

    private val session = AtomicReference(SessionSnapshot(null, false))
    @Volatile private var waVersion: String? = null
    @Volatile private var crVersion: String? = null
    @Volatile private var lastLoginAttempt = 0L
    private val baseLoginCooldownMs = 1_500L
    private val maxLoginBackoffMs = 10_000L
    private val sessionCacheTtlMs = 90_000L
    private val sessionValidationIntervalMs = 60_000L
    @Volatile private var lastValidatedAt = 0L

    // 让位退避：检测到官方后台在线时主动避让 30s
    @Volatile private var lastGiveWayAt = 0L
    private val giveWayDurationMs = 30_000L

    private val resolveMutex = Mutex()
    @Volatile private var baseUrlResolved = false
    private var effectiveBaseUrl: String = ""

    override fun baseUrl(): String =
        if (baseUrlResolved) effectiveBaseUrl
        else if (port == 80) "http://$deviceIp" else "http://$deviceIp:$port"

    internal suspend fun ensureBaseUrlResolved() {
        if (baseUrlResolved) return
        resolveMutex.withLock {
            if (baseUrlResolved) return
            val primary = if (port == 80) "http://$deviceIp" else "http://$deviceIp:$port"
            val candidates = linkedSetOf(primary, "http://127.0.0.1:$port", "http://localhost:$port")
            var chosen = primary
            for (cand in candidates) {
                if (isReachable(cand)) { chosen = cand; break }
            }
            effectiveBaseUrl = chosen
            baseUrlResolved = true
            if (chosen != primary) GoformLog.w(tag, "Goform base url resolved to $chosen (primary $primary unreachable)")
            else GoformLog.d(tag, "Goform base url=$chosen")
        }
    }

    private suspend fun isReachable(cand: String): Boolean {
        return try {
            val url = URL(cand)
            withTimeout(1_000) {
                val conn = url.openConnection() as java.net.HttpURLConnection
                try {
                    conn.connectTimeout = 800
                    conn.readTimeout = 800
                    conn.requestMethod = "HEAD"
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.responseCode in 200..599
                } finally {
                    conn.disconnect()
                }
            }
        } catch (_: Exception) { false }
    }

    // ============ HTTP helpers ============

    internal suspend fun httpGet(url: String): HttpResponse {
        val base = baseUrl()
        return httpClient.get(url) {
            header("Referer", "$base/index.html")
            attachSessionCookie(session.get())
        }
    }

    internal fun parseJson(body: String): JsonObject? = try {
        json.parseToJsonElement(body).jsonObject
    } catch (_: Exception) {
        GoformLog.w(tag, "parseJson failed: ${body.take(120)}")
        null
    }

    private fun io.ktor.client.request.HttpRequestBuilder.attachSessionCookie(snapshot: SessionSnapshot?) {
        val cookie = snapshot?.cookie
        if (cookie != null) header("Cookie", cookie)
    }

    // ============ Login ============

    override suspend fun ensureLogin(): Boolean = ensureSession() != null

    private suspend fun ensureSession(): SessionSnapshot? {
        ensureBaseUrlResolved()
        val now = System.currentTimeMillis()
        val fast = session.get()
        if (fast.loggedIn && (now - lastValidatedAt) < sessionValidationIntervalMs) return fast

        return loginMutex.withLock {
            val nowLocked = System.currentTimeMillis()
            val current = session.get()
            if (current.loggedIn && (nowLocked - lastValidatedAt) < sessionValidationIntervalMs) return@withLock current

            if (current.loggedIn && (nowLocked - lastValidatedAt) < sessionCacheTtlMs) {
                if (validateSession(current)) {
                    lastValidatedAt = nowLocked
                    return@withLock current
                }
            }

            val failCount = consecutiveLoginFailures.get()
            val giveWayElapsed = nowLocked - lastGiveWayAt
            if (lastGiveWayAt > 0 && giveWayElapsed < giveWayDurationMs) {
                GoformLog.d(tag, "ensureLogin: Giving way to official UI (remains ${(giveWayDurationMs - giveWayElapsed) / 1000}s)")
                return@withLock null
            }

            if (failCount > 0) {
                val backoffMs = (baseLoginCooldownMs * (1L shl failCount.coerceAtMost(6))).coerceAtMost(maxLoginBackoffMs)
                if (nowLocked - lastLoginAttempt < backoffMs && lastLoginAttempt > 0L) return@withLock null
            }
            markLoggedOut()
            GoformLog.i(tag, "Performing login to $deviceIp:$port... (failCount=$failCount)")
            try {
                val base = baseUrl()
                val ldResp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=LD&isTest=false&_=${System.currentTimeMillis()}") {
                    header("Referer", "$base/index.html")
                }
                val ldBody = ldResp.bodyAsText()
                val ld = try {
                    json.parseToJsonElement(ldBody).jsonObject["LD"]?.jsonPrimitive?.contentOrNull ?: ""
                } catch (_: Exception) { "" }
                val passHash = sha256Hex(password).uppercase()
                val encPwd = sha256Hex(passHash + ld).uppercase()
                val ipPart = if (!loginIp.isNullOrBlank()) "&IP=$loginIp" else ""
                val loginResp = httpClient.post("$base/goform/goform_set_cmd_process") {
                    header("Referer", "$base/index.html")
                    header("Origin", base)
                    header("Content-Type", "application/x-www-form-urlencoded")
                    setBody("isTest=false&goformId=$loginGoformId&user=$username&password=$encPwd$ipPart")
                }
                var loginBody = loginResp.bodyAsText()
                loginResp.headers["Set-Cookie"]?.split(";")?.firstOrNull()?.let { storeCookie(it) }
                if (loginResp.status != HttpStatusCode.OK || isLoginFailed(loginBody)) {
                    if (loginBody.contains("\"result\":\"session\"", ignoreCase = true)) {
                        lastGiveWayAt = System.currentTimeMillis()
                        GoformLog.w(tag, "Login rejected (session active), giving way for ${giveWayDurationMs / 1000}s")
                    } else {
                        GoformLog.w(tag, "Login rejected: ${loginBody.take(200)}")
                    }
                    consecutiveLoginFailures.incrementAndGet()
                    markLoggedOut()
                    lastLoginAttempt = System.currentTimeMillis()
                    return@withLock null
                }
                val loggedIn = session.updateAndGet { SessionSnapshot(it.cookie, true) }
                val infoResp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=wa_inner_version,cr_version&multi_data=1&isTest=false&_=${System.currentTimeMillis()}") {
                    header("Referer", "$base/index.html")
                    attachSessionCookie(loggedIn)
                }
                try {
                    val infoJson = json.parseToJsonElement(infoResp.bodyAsText()).jsonObject
                    waVersion = infoJson["wa_inner_version"]?.jsonPrimitive?.contentOrNull
                    crVersion = infoJson["cr_version"]?.jsonPrimitive?.contentOrNull
                } catch (_: Exception) { }
                consecutiveLoginFailures.set(0)
                lastLoginAttempt = System.currentTimeMillis()
                lastValidatedAt = System.currentTimeMillis()
                lastGiveWayAt = 0L
                GoformLog.i(tag, "Login successful. wa=$waVersion cr=$crVersion")
                loggedIn
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                GoformLog.e(tag, "Login failed", e)
                consecutiveLoginFailures.incrementAndGet()
                markLoggedOut()
                lastLoginAttempt = System.currentTimeMillis()
                null
            }
        }
    }

    private fun storeCookie(cookie: String) {
        session.updateAndGet { SessionSnapshot(cookie, it.loggedIn) }
    }

    private fun markLoggedOut() {
        session.updateAndGet { SessionSnapshot(it.cookie, false) }
    }

    private suspend fun validateSession(snapshot: SessionSnapshot): Boolean {
        return try {
            val base = baseUrl()
            val resp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=RD&multi_data=1&isTest=false&_=${System.currentTimeMillis()}") {
                header("Referer", "$base/index.html")
                attachSessionCookie(snapshot)
            }
            val body = resp.bodyAsText()
            val authFailure = isAuthFailure(body)
            val ok = !authFailure && resp.status == HttpStatusCode.OK
            if (!ok) invalidateSession()
            ok
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // 网络/解析异常：信任上次登录态，仅短暂标记「不可达」，不增加 failCount
            GoformLog.w(tag, "validateSession network error (keeping login state): ${e.message}")
            true
        }
    }

    override fun invalidateSession() {
        session.set(SessionSnapshot(null, false))
        lastValidatedAt = 0L
    }

    override fun resetLogin() {
        session.set(SessionSnapshot(null, false))
        consecutiveLoginFailures.set(0)
        lastValidatedAt = 0L
    }

    override fun updateGoformPassword(newPwd: String) {
        password = newPwd
        resetLogin()
    }

    // ============ Auth-failure detection ============

    private val AUTH_FAILURE_RESULTS = setOf("not logged in", "session", "none secure connection")

    /**
     * 会话/鉴权失败判定。
     *  - body 是 JSON：只按 `result` 字段**精确等值**比较（外加显式的 `Error` 字段）；
     *  - body 不是 JSON（设备把未鉴权请求返回 login.html，回的是 HTML）：才退回子串匹配。
     */
    internal fun isAuthFailure(body: String): Boolean {
        if (body.isBlank()) return false
        val trimmed = body.trimStart()
        if (trimmed.startsWith("<")) return true
        if (trimmed.startsWith("{")) {
            val obj = parseJson(body)
            if (obj != null) {
                val result = (obj["result"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase(Locale.ROOT)
                if (result != null && result in AUTH_FAILURE_RESULTS) return true
                val err = (obj["Error"] as? JsonPrimitive)?.contentOrNull
                return !err.isNullOrBlank()
            }
            return false
        }
        val lower = body.lowercase(Locale.ROOT)
        return lower.contains("login.html") ||
            lower.contains("redirect") ||
            lower.contains("none secure connection") ||
            lower.contains("not logged in")
    }

    // ============ POST ============

    override suspend fun goformPost(params: Map<String, String>): String? =
        (goformPostIdempotent(params) as? GoformWriteResult.Accepted)?.body

    /**
     * **幂等**写操作专用入口：会话失效时重登并只重试一次，返回 [GoformWriteResult]，
     * 把「会话失效 / 连不上 / 设备表过态」分开，便于调用方按语义处理。
     */
    suspend fun goformPostIdempotent(params: Map<String, String>): GoformWriteResult =
        postMeasured(params, retryOnSessionLost = true)

    private suspend fun postMeasured(
        params: Map<String, String>,
        retryOnSessionLost: Boolean
    ): GoformWriteResult = withContext(Dispatchers.IO) {
        var attemptNo = 1
        var result = goformPostOnce(params)
        while (retryOnSessionLost && GoformWritePolicy.shouldRetry(attemptNo, result)) {
            attemptNo++
            GoformLog.w(tag, "[goform_set] session lost, re-login and retry (attempt=$attemptNo/${GoformWritePolicy.MAX_ATTEMPTS})")
            result = goformPostOnce(params)
        }
        result
    }

    private suspend fun goformPostOnce(params: Map<String, String>): GoformWriteResult {
        val snapshot = ensureSession()
        if (snapshot == null) {
            GoformLog.e(tag, "goformPost aborted: not logged in")
            return GoformWriteResult.SessionLost
        }
        // AD 由 wa/cr/RD 三次前置查询算出来。会话已死时这三次拿回登录页，解析不出字段
        // → computeAd 返回 null，这正是「会话失效」最常见的外观，归到可重试一侧。
        val ad = try {
            computeAd(params, snapshot)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            GoformLog.w(tag, "[goform_set] AD precheck transport error: ${e.message}")
            return GoformWriteResult.Unreachable(e.message ?: e::class.java.simpleName)
        }
        if (ad == null) {
            GoformLog.e(tag, "goformPost aborted: AD compute failed")
            invalidateSession()
            return GoformWriteResult.SessionLost
        }
        val base = baseUrl()
        val formBody = GoformCodec.buildSetFormBody(params, ad)
        val (rawBody, status) = try {
            val resp = httpClient.post("$base/goform/goform_set_cmd_process") {
                header("Referer", "$base/index.html")
                header("Origin", base)
                header("Content-Type", "application/x-www-form-urlencoded")
                attachSessionCookie(snapshot)
                setBody(formBody)
            }
            GoformCodec.decodeBody(resp.readBytes()) to resp.status
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            GoformLog.w(tag, "[goform_set] transport error: ${e.message}")
            return GoformWriteResult.Unreachable(e.message ?: e::class.java.simpleName)
        }
        GoformLog.d(tag, "[goform_set] status=$status body=${rawBody.take(200)}")
        val verdict = classifyWrite(status.value, isAuthFailure(rawBody), rawBody)
        if (verdict is GoformWriteResult.SessionLost) {
            if (rawBody.contains("\"result\":\"session\"", ignoreCase = true)) {
                lastGiveWayAt = System.currentTimeMillis()
                GoformLog.w(tag, "[goform_set] session active on official UI, backing off")
            } else {
                GoformLog.w(tag, "[goform_set] auth/session lost, invalidating; body=${rawBody.take(120)}")
            }
            invalidateSession()
        }
        return verdict
    }

    private fun classifyWrite(statusCode: Int, authFailure: Boolean, body: String): GoformWriteResult = when {
        authFailure -> GoformWriteResult.SessionLost
        statusCode != 200 -> GoformWriteResult.Unreachable("HTTP $statusCode")
        isGoformSuccess(body) -> GoformWriteResult.Accepted(body)
        else -> GoformWriteResult.Rejected(body)
    }

    // ============ Query ============

    override suspend fun query(commands: List<String>): JsonObject? {
        if (!ensureLogin()) return null
        val first = queryOnce(commands, ensureSession() ?: return null)
        if (first != null) return first
        // 会话失效：重登换 cookie，用新快照再发一次
        return queryOnce(commands, ensureSession() ?: return null)
    }

    private suspend fun queryOnce(commands: List<String>, snapshot: SessionSnapshot): JsonObject? {
        val base = baseUrl()
        val cmdParam = commands.joinToString(",")
        val url = "$base/goform/goform_get_cmd_process?cmd=$cmdParam&multi_data=1&isTest=false&_=${System.currentTimeMillis()}"
        val resp = httpClient.get(url) {
            header("Referer", "$base/index.html")
            attachSessionCookie(snapshot)
        }
        val rawBody = GoformCodec.decodeBody(resp.readBytes())
        val status = resp.status
        GoformLog.d(tag, "[goform_get] cmd=$cmdParam status=$status body=${rawBody.take(200)}")
        val authFailure = isAuthFailure(rawBody)
        if (status != HttpStatusCode.OK || authFailure) {
            if (rawBody.contains("\"result\":\"session\"", ignoreCase = true)) {
                lastGiveWayAt = System.currentTimeMillis()
                GoformLog.w(tag, "[goform_get] session active on official UI, backing off")
            } else {
                GoformLog.w(tag, "[goform_get] auth/session lost (status=$status), invalidating")
            }
            invalidateSession()
            return null
        }
        return try {
            json.parseToJsonElement(rawBody).jsonObject
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            GoformLog.w(tag, "[goform_get] parse failed: ${rawBody.take(200)}")
            null
        }
    }

    override suspend fun querySingle(command: String): JsonElement? {
        if (!ensureLogin()) return null
        val first = querySingleOnce(command, ensureSession() ?: return null)
        if (first != null) return first
        return querySingleOnce(command, ensureSession() ?: return null)
    }

    private suspend fun querySingleOnce(command: String, snapshot: SessionSnapshot): JsonElement? {
        val base = baseUrl()
        val url = "$base/goform/goform_get_cmd_process?cmd=$command&isTest=false&_=${System.currentTimeMillis()}"
        val resp = httpClient.get(url) {
            header("Referer", "$base/index.html")
            attachSessionCookie(snapshot)
        }
        val rawBody = GoformCodec.decodeBody(resp.readBytes())
        val status = resp.status
        GoformLog.d(tag, "[goform_get_single] cmd=$command status=$status body=${rawBody.take(200)}")
        if (status != HttpStatusCode.OK || isAuthFailure(rawBody)) {
            if (rawBody.contains("\"result\":\"session\"", ignoreCase = true)) {
                lastGiveWayAt = System.currentTimeMillis()
            } else {
                GoformLog.w(tag, "[goform_get_single] auth/session lost (status=$status), invalidating")
            }
            invalidateSession()
            return null
        }
        return try {
            json.parseToJsonElement(rawBody)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            GoformLog.w(tag, "[goform_get_single] parse failed: ${rawBody.take(200)}")
            null
        }
    }

    // ============ AD / LD / RD ============

    private suspend fun fetchVersionInfo(snapshot: SessionSnapshot): Pair<String?, String?> {
        val base = baseUrl()
        val resp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=wa_inner_version,cr_version&multi_data=1&isTest=false&_=${System.currentTimeMillis()}") {
            header("Referer", "$base/index.html")
            attachSessionCookie(snapshot)
        }
        val body = resp.bodyAsText()
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["wa_inner_version"]?.jsonPrimitive?.contentOrNull to obj["cr_version"]?.jsonPrimitive?.contentOrNull
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null to null }
    }

    private suspend fun computeAd(params: Map<String, String>, snapshot: SessionSnapshot): String? {
        val (wa, cr) = fetchVersionInfo(snapshot)
        if (wa.isNullOrBlank() || cr.isNullOrBlank()) {
            GoformLog.w(tag, "computeAd: missing wa/cr (wa=$wa cr=$cr)")
            return null
        }
        val rd = getRd(snapshot)
        if (rd == null) {
            GoformLog.w(tag, "computeAd: RD is null")
            return null
        }
        val adRaw = sha256Hex("$wa$cr").uppercase() + rd
        return sha256Hex(adRaw).uppercase()
    }

    private suspend fun getRd(snapshot: SessionSnapshot): String? {
        val base = baseUrl()
        val resp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=RD&multi_data=1&isTest=false&_=${System.currentTimeMillis()}") {
            header("Referer", "$base/index.html")
            attachSessionCookie(snapshot)
        }
        val body = resp.bodyAsText()
        return try {
            json.parseToJsonElement(body).jsonObject["RD"]?.jsonPrimitive?.contentOrNull
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    // ============ Logout ============

    override suspend fun logout(): Boolean {
        return try {
            val resp = goformPost(
                mapOf("goformId" to "LOGOUT", "isTest" to "false")
            )
            GoformLog.d(tag, "Goform logout done")
            resp != null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            GoformLog.e(tag, "logout failed", e)
            false
        }
    }

    // ============ Success / Failure helpers ============

    internal fun isGoformSuccess(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val b = body.trim()
        if (b.startsWith("<") || b.contains("login.html")) return false
        if (b.contains("\"result\":\"success\"", ignoreCase = true)) return true
        if (b.contains("\"success\":true", ignoreCase = true)) return true
        if (b.contains("\"result\":\"0\"", ignoreCase = true)) return true
        if (b.contains("\"code\":0", ignoreCase = true)) return true
        if (b.contains("\"error\":0", ignoreCase = true)) return true
        return false
    }

    private val LOGIN_SUCCESS_RESULTS = setOf("0", "success")
    private val LOGIN_FAILURE_RESULTS = setOf(
        "failure", "login fail", "login failed", "password error",
        "not logged in", "session", "none secure connection"
    )

    internal fun isLoginFailed(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val trimmed = body.trimStart()
        if (trimmed.startsWith("<")) return true
        if (!trimmed.startsWith("{")) {
            val lower = body.lowercase(Locale.ROOT)
            return lower.contains("login fail") || lower.contains("password error") ||
                lower.contains("not logged in") || lower.contains("login.html")
        }
        val obj = parseJson(body) ?: return false
        val result = (obj["result"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase(Locale.ROOT)
        if (result != null) {
            if (result in LOGIN_SUCCESS_RESULTS) return false
            if (result in LOGIN_FAILURE_RESULTS) return true
            GoformLog.w(tag, "isLoginFailed: unknown login result='$result', treating as success")
            return false
        }
        val err = (obj["Error"] as? JsonPrimitive)?.contentOrNull
        return !err.isNullOrBlank()
    }

    // ============ crypto ============

    internal fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun base64Decode(input: String): String {
        return try {
            val bytes = java.util.Base64.getDecoder().decode(input)
            val decoded = String(bytes, Charset.forName("GBK"))
            try {
                String(decoded.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
            } catch (_: Exception) {
                decoded
            }
        } catch (e: Exception) {
            GoformLog.e(tag, "base64Decode failed", e)
            ""
        }
    }

    override fun close() {
        try { httpClient.close() } catch (_: Exception) {}
        GoformLog.i(tag, "GoformClient closed")
    }
}
