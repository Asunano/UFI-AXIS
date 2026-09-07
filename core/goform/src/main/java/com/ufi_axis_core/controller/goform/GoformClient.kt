package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.GoformQoS
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
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
import java.io.Closeable
import java.net.URL
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Goform 客户端（ZTE 设备私有协议防腐层实现）。
 *
 * 认证模型（与 UFI-TOOLS 对齐，已在备份版本验证可用）：
 *  - 每次 query / querySingle / goformPost 前都先 ensureLogin()；
 *  - 登录优先 goformId=LOGIN_MULTI_USER（带 IP=localhost），失败则回退 LOGIN；
 *  - 加密口令：encPwd = sha256( sha256(password) + LD )，均为大写十六进制；
 *  - 写操作需在 body 附带 AD = sha256( sha256(wa+cr) + RD )，均为大写；
 *  - 登录态带 TTL 缓存与 session 校验，避免频繁登录；失败退避。
 *
 * 注意：读/写均依赖登录态（携带 Set-Cookie 返回的 session）。这与设备 goform
 * 的要求一致；裸读会被设备以 "none secure connection" / "not logged in" 拒绝。
 */
class GoformClient(
    private val deviceIp: String = "192.168.0.1",
    private val port: Int = 8080,
    private val password: String = "YWRtaW4="
) : GoformGateway {

    private val tag = "GoformClient"

    /**
     * goform 是 core 发往设备官方 Web 后台的出向 HTTP 请求，属「网络日志」。
     * 走 [AppLogger.net] 后 tag 变成 `NET/goform`，日志页可与运行日志分开筛选。
     * 只有真正的请求/响应行用它；会话状态类 WARN 仍走 [AppLogger.w]（那是运行事件）。
     */
    private val GOFORM_NET_TAG = "goform"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10000
            connectTimeoutMillis = 5000
            socketTimeoutMillis = 10000
        }
        engine {
            endpoint {
                maxConnectionsCount = 10
                pipelineMaxSize = 1
                keepAliveTime = 30000
                connectTimeout = 5000
            }
        }
        expectSuccess = false
    }

    private val loginMutex = Mutex()
    private val consecutiveLoginFailures = AtomicInteger(0)
    private var sessionCookie: String? = null
    private var waVersion: String? = null
    private var crVersion: String? = null
    private var lastLoginAttempt = 0L
    private val baseLoginCooldownMs = 1500L
    // 2026-08-23: 退避上限从 60s 降到 10s —— 之前连续 session 失效会让退避叠到 60s，
    // 前端轮询全被拦截，造成「长时间断连，必须手动重启核心服务」体感。10s 已足够
    // 让设备 goform 释放被官方后台占用的 session，又不超出用户耐心。
    private val maxLoginBackoffMs = 10_000L
    // 2026-08-23: TTL 从 300s 缩到 90s —— ZTE 设备实际会话超时通常 1-3 分钟，
    // 太长的 TTL 会让确保登录一直走缓存路径，等到下一次真断了才一起 invalidate 一波。
    private val sessionCacheTtlMs = 90_000L
    private val sessionValidationIntervalMs = 60_000L // 每 60s 验证一次
    private var lastValidatedAt = 0L
    @Volatile private var isLoggedIn = false
    
    // 让位退避：检测到官方后台在线时主动避让 30s
    private var lastGiveWayAt = 0L
    private val giveWayDurationMs = 30_000L

    private val resolveMutex = Mutex()
    @Volatile private var baseUrlResolved = false
    private var effectiveBaseUrl: String = ""

    override fun baseUrl(): String =
        if (baseUrlResolved) effectiveBaseUrl
        else if (port == 80) "http://$deviceIp" else "http://$deviceIp:$port"

    /**
     * 解析出真正可达的 base url（`http://192.168.0.1:8080` / `http://127.0.0.1:8080` / …）。
     *
     * internal：文件类端点（二维码等）不走 [ensureLogin]，但同样要先把 base url 定下来，
     * 否则 [baseUrl] 只能返回未验证的拼装值，端口/宿主不对就直接连不上。
     */
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
            if (chosen != primary) AppLogger.w(tag, "Goform base url resolved to $chosen (primary $primary unreachable)")
            else AppLogger.d(tag, "Goform base url=$chosen")
        }
    }

    private suspend fun isReachable(cand: String): Boolean {
        return try {
            val url = URL(cand)
            withTimeout(1000) {
                val conn = url.openConnection() as java.net.HttpURLConnection
                try {
                    conn.connectTimeout = 800
                    conn.readTimeout = 800
                    conn.requestMethod = "HEAD"
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    val code = conn.responseCode
                    code in 200..599
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { false }
    }

    // ============ HTTP helpers ============

    /**
     * 直接 GET 一个设备侧 URL（文件类端点用：二维码图片、短信附件等）。
     *
     * **必须带 session Cookie**：设备的 `goform_get_file_process` 与 goform_get/set 一样受
     * session 保护，不带 Cookie 时不会返回 401，而是回一个 200 的登录页 HTML ——
     * 调用方按图片解析就得到"坏图"。这里漏带 Cookie 曾让 WiFi 分享二维码一直是错的
     * （query/goformPost 两条路径都带，只有本方法忘了）。
     */
    internal suspend fun httpGet(url: String): HttpResponse {
        val base = baseUrl()
        return httpClient.get(url) {
            header("Referer", "$base/index.html")
            if (sessionCookie != null) header("Cookie", sessionCookie!!)
        }
    }

    internal fun parseJson(body: String): JsonObject? = try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        AppLogger.w(tag, "parseJson failed: ${e.message}")
        null
    }

    // ============ Login ============

    override suspend fun ensureLogin(): Boolean {
        ensureBaseUrlResolved()
        val now = System.currentTimeMillis()
        // ── 快速路径：缓存时间内且未到验证周期，直接返回 ──
        if (isLoggedIn && (now - lastValidatedAt) < sessionValidationIntervalMs) return true
        
        return loginMutex.withLock {
            val nowLocked = System.currentTimeMillis()
            // ── 二次检查 ──
            if (isLoggedIn && (nowLocked - lastValidatedAt) < sessionValidationIntervalMs) return@withLock true
            
            // ── 到达验证周期或已失效：执行 validate ──
            if (isLoggedIn && (nowLocked - lastValidatedAt) < sessionCacheTtlMs) {
                if (validateSession()) {
                    lastValidatedAt = nowLocked
                    return@withLock true
                }
            }

            val failCount = consecutiveLoginFailures.get()
            
            // ── 检查让位状态 ──
            val giveWayElapsed = nowLocked - lastGiveWayAt
            if (lastGiveWayAt > 0 && giveWayElapsed < giveWayDurationMs) {
                AppLogger.d(tag, "ensureLogin: Giving way to official UI (remains ${ (giveWayDurationMs - giveWayElapsed)/1000 }s)")
                return@withLock false
            }

            // 2026-08-24: 不论 isLoggedIn 是否为 true，只要有失败记录就应用退避。
            // 否则在连续登录失败且 isLoggedIn=false 时会陷入无退避的死循环，压满 QoS。
            if (failCount > 0) {
                val backoffMs = (baseLoginCooldownMs * (1L shl failCount.coerceAtMost(6))).coerceAtMost(maxLoginBackoffMs)
                if (nowLocked - lastLoginAttempt < backoffMs && lastLoginAttempt > 0L) {
                    return@withLock false
                }
            }
            isLoggedIn = false
            AppLogger.i(tag, "Performing login to $deviceIp:$port... (failCount=$failCount)")
            try {
                val base = baseUrl()
                val ldResp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=LD&isTest=false&_=${System.currentTimeMillis()}") {
                    header("Referer", "$base/index.html")
                }
                val ldBody = ldResp.bodyAsText()
                val ld = try {
                    json.parseToJsonElement(ldBody).jsonObject["LD"]?.jsonPrimitive?.contentOrNull ?: ""
                } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
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
                    // 如果是 session 冲突，记录让位
                    if (loginBody.contains("\"result\":\"session\"", ignoreCase = true)) {
                        lastGiveWayAt = System.currentTimeMillis()
                        AppLogger.w(tag, "Login rejected (session active), giving way for ${giveWayDurationMs/1000}s")
                    }
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
                        if (loginBody.contains("\"result\":\"session\"", ignoreCase = true)) {
                            lastGiveWayAt = System.currentTimeMillis()
                            AppLogger.e(tag, "LOGIN fallback rejected (session active)")
                        } else {
                            AppLogger.e(tag, "LOGIN fallback also rejected: ${loginBody.take(200)}")
                        }
                        consecutiveLoginFailures.incrementAndGet()
                        isLoggedIn = false
                        lastLoginAttempt = System.currentTimeMillis()
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
                } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                isLoggedIn = true
                consecutiveLoginFailures.set(0)
                lastLoginAttempt = System.currentTimeMillis()
                lastValidatedAt = System.currentTimeMillis()
                lastGiveWayAt = 0L // 登录成功，重置让位
                AppLogger.i(tag, "Login successful. wa=$waVersion cr=$crVersion")
                true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                AppLogger.e(tag, "Login failed", e)
                consecutiveLoginFailures.incrementAndGet()
                isLoggedIn = false
                lastLoginAttempt = System.currentTimeMillis()
                false
            }
        }
    }

    private suspend fun validateSession(): Boolean {
        return try {
            val base = baseUrl()
            val resp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=RD&multi_data=1&isTest=false&_=${System.currentTimeMillis()}") {
                header("Referer", "$base/index.html")
                if (sessionCookie != null) header("Cookie", sessionCookie!!)
            }
            val body = resp.bodyAsText()
            val authFailure = isAuthFailure(body)
            val ok = !authFailure && resp.status == HttpStatusCode.OK
            if (!ok) invalidateSession()
            ok
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // 网络/解析异常：信任上次登录态，**仅短暂标记「不可达」**，不增加 failCount
            // —— 频繁网络抖动下原逻辑会把 failCount 推到退避上限 60s（虽然目前已降到 10s，
            // 但性质仍不对：网络错不会让 cookie 失效，下次请求正常就行）。
            AppLogger.w(tag, "validateSession network error (keeping login state): ${e.message}")
            true
        }
    }

    /**
     * 2026-08-23 改造：仅标记「下次重登」，不再自增 [consecutiveLoginFailures]。
     *
     * 原因（旧版实现）：连续 invalidateSession 会把 failCount ++，而退避 backoff
     * = 1500 * 2^failCount（封顶 maxLoginBackoffMs）。长会话运行 + 偶发心跳失败 +
     * ZTE 官方后台在线顶「session active」，会让 failCount 几小时内叠到接近
     * maxLoginBackoffMs。所有路径（前端 DataScheduler 轮询、信号采集、登出、
     * API 路由）都被 loginMutex 拒绝 → 「长时间断连，需要手动重启核心服务」。
     *
     * 真正**登录失败**（LD/AD 错误等）由 `ensureLogin()` 的 catch 自行递增，
     * 不应把 cookie 失效 + 网络抖动混为一谈。
     */
    override fun invalidateSession() {
        isLoggedIn = false
        sessionCookie = null
        lastValidatedAt = 0L
        // 故意不动 consecutiveLoginFailures：失败计数只跟踪「登录尝试」是否成功。
    }

    override fun resetLogin() {
        isLoggedIn = false
        consecutiveLoginFailures.set(0)
        sessionCookie = null
        lastValidatedAt = 0L
    }

    override fun updateGoformPassword(newPwd: String) {
        resetLogin()
    }

    // ============ Auth-failure detection ============

    internal fun isAuthFailure(body: String): Boolean {
        if (body.isBlank()) return false
        return body.contains("\"result\":\"not logged in\"", ignoreCase = true)
            || body.contains("\"result\":\"session\"", ignoreCase = true)
            || body.contains("login.html", ignoreCase = true)
            || body.contains("redirect", ignoreCase = true)
            || body.contains("none secure connection", ignoreCase = true)
            || body.contains("\"Error\":\"", ignoreCase = true)
            || (body.trimStart().startsWith("<!DOCTYPE") || body.trimStart().startsWith("<html"))
    }

    private fun isResponseComplete(body: String): Boolean {
        if (body.isBlank()) return false
        if (body.trimStart().startsWith("<!DOCTYPE") || body.trimStart().startsWith("<html")) return false
        if (body.contains("\"result\":\"not logged in\"", ignoreCase = true)) return false
        if (body.contains("\"result\":\"session\"", ignoreCase = true)) return false
        if (body.contains("login.html", ignoreCase = true)) return false
        if (body.contains("redirect", ignoreCase = true)) return false
        if (body.contains("none secure connection", ignoreCase = true)) return false
        if (body.contains("\"Error\":\"", ignoreCase = true)) return false
        return true
    }

    // ============ POST ============

    override suspend fun goformPost(params: Map<String, String>): String? {
        return withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val result = goformPostInternal(params)
            val cost = System.currentTimeMillis() - start
            // 任何写操作都会让 GoformQoS 的 2 秒查询快照过时。不清的话，写完立刻回读会命中
            // 旧快照拿到写入前的值 —— 表现为「开关点了没反应、状态弹回原值、要点好几次」。
            // 上层 ResponseCache.invalidate 挡不住这一层，因为它在更下游。
            // 写操作频率远低于查询，整体清空的代价可以接受。
            GoformQoS.clearCache()
            GoformQoS.adaptiveAdjust(
                targetQueryPermits = 6,
                targetSetPermits = 3
            )
            AppLogger.net(AppLogger.LogLevel.DEBUG, GOFORM_NET_TAG, "[QoS] goformPost cost=${cost}ms")
            result
        }
    }


    private suspend fun goformPostInternal(params: Map<String, String>): String? {
        if (!ensureLogin()) {
            AppLogger.e(tag, "goformPost aborted: not logged in")
            return null
        }
        return GoformQoS.withSetPermit {
            val ad = computeAd(params)
            if (ad == null) {
                AppLogger.e(tag, "goformPost aborted: AD compute failed (cookie=${sessionCookie?.take(8)})")
                invalidateSession()
                return@withSetPermit null
            }
            val base = baseUrl()
            val formBody = GoformCodec.buildSetFormBody(params, ad)
            val resp = httpClient.post("$base/goform/goform_set_cmd_process") {
                header("Referer", "$base/index.html")
                header("Origin", base)
                header("Content-Type", "application/x-www-form-urlencoded")
                if (sessionCookie != null) header("Cookie", sessionCookie!!)
                setBody(formBody)
            }
            val rawBody = GoformCodec.decodeBody(resp.readBytes())
            val status = resp.status
            AppLogger.net(AppLogger.LogLevel.DEBUG, GOFORM_NET_TAG, "[goform_set] status=$status body=${rawBody.take(200)}")
            if (status != HttpStatusCode.OK || isAuthFailure(rawBody)) {
                if (rawBody.contains("\"result\":\"session\"", ignoreCase = true)) {
                    lastGiveWayAt = System.currentTimeMillis()
                    AppLogger.w(tag, "[goform_set] session active on official UI, backing off")
                } else {
                    AppLogger.w(tag, "[goform_set] auth/session lost, invalidating; body=${rawBody.take(120)}")
                }
                invalidateSession()
                return@withSetPermit null
            }
            rawBody
        }
    }

    // ============ Query ============

    override suspend fun query(commands: List<String>): JsonObject? {
        if (!ensureLogin()) return null
        return queryInternal(commands, retry = true)
    }

    private suspend fun queryInternal(commands: List<String>, retry: Boolean): JsonObject? {
        if (!ensureLogin()) return null
        return GoformQoS.withQueryPermit {
            // 缓存边界（计划书 1.0.3）：这一层是**传输层**缓存，key 是 cmd 名集合，
            // value 是设备**原始**响应。字段归一化在它的下游（GoformFieldMapper /
            // GoformXxxClient），上层业务缓存（ResponseCache / DataHub）存的才是 canonical 数据。
            // 不要在这一层改字段名或改值 —— 否则 QoS 快照会同时污染所有调用方，
            // 而且归一化会变成"有时做有时不做"（缓存命中时被跳过）。
            val key = commands.sorted().joinToString(",")
            GoformQoS.getCachedQuery(key)?.let {
                AppLogger.d(tag, "[cache HIT] $key")
                return@withQueryPermit it
            }
            val base = baseUrl()
            val cmdParam = commands.joinToString(",")
            val url = "$base/goform/goform_get_cmd_process?cmd=$cmdParam&multi_data=1&isTest=false&_=${System.currentTimeMillis()}"
            val resp = httpClient.get(url) {
                header("Referer", "$base/index.html")
                if (sessionCookie != null) header("Cookie", sessionCookie!!)
            }
            val rawBody = GoformCodec.decodeBody(resp.readBytes())
            val status = resp.status
            AppLogger.net(AppLogger.LogLevel.DEBUG, GOFORM_NET_TAG, "[goform_get] cmd=$cmdParam status=$status body=${rawBody.take(200)}")
            
            // 2026-08-24: 增强校验。部分设备在负载过高时会返回截断/非完整的 JSON。
            // 如果请求了多个字段但返回字段数过少（且非 auth 错误），标记为失效。
            val isPartial = status == HttpStatusCode.OK && !isAuthFailure(rawBody) && 
                    commands.size > 5 && countJsonFields(rawBody) < 2
            
            if (status != HttpStatusCode.OK || isAuthFailure(rawBody) || isPartial) {
                if (isPartial) {
                    AppLogger.w(tag, "[goform_get] Partial response detected ($cmdParam), invalidating session")
                } else if (rawBody.contains("\"result\":\"session\"", ignoreCase = true)) {
                    lastGiveWayAt = System.currentTimeMillis()
                    AppLogger.w(tag, "[goform_get] session active on official UI, backing off")
                } else {
                    AppLogger.w(tag, "[goform_get] auth/session lost (status=$status), invalidating")
                }
                invalidateSession()
                if (retry && ensureLogin()) {
                    return@withQueryPermit queryInternal(commands, retry = false)
                }
                return@withQueryPermit null
            }
            val obj = parseJson(rawBody)
            if (obj == null) {
                AppLogger.w(tag, "[goform_get] parse failed: ${rawBody.take(200)}")
                return@withQueryPermit null
            }
            GoformQoS.cacheQuery(key, obj)
            obj
        }
    }

    override suspend fun querySingle(command: String): JsonElement? {
        if (!ensureLogin()) return null
        return querySingleInternal(command, retry = true)
    }

    private suspend fun querySingleInternal(command: String, retry: Boolean): JsonElement? {
        if (!ensureLogin()) return null
        return GoformQoS.withQueryPermit {
            val base = baseUrl()
            val url = "$base/goform/goform_get_cmd_process?cmd=$command&isTest=false&_=${System.currentTimeMillis()}"
            val resp = httpClient.get(url) {
                header("Referer", "$base/index.html")
                if (sessionCookie != null) header("Cookie", sessionCookie!!)
            }
            val rawBody = GoformCodec.decodeBody(resp.readBytes())
            val status = resp.status
            AppLogger.net(AppLogger.LogLevel.DEBUG, GOFORM_NET_TAG, "[goform_get_single] cmd=$command status=$status body=${rawBody.take(200)}")
            if (status != HttpStatusCode.OK || isAuthFailure(rawBody)) {
                if (rawBody.contains("\"result\":\"session\"", ignoreCase = true)) {
                    lastGiveWayAt = System.currentTimeMillis()
                    AppLogger.w(tag, "[goform_get_single] session active on official UI, backing off")
                } else {
                    AppLogger.w(tag, "[goform_get_single] auth/session lost (status=$status), invalidating")
                }
                invalidateSession()
                if (retry && ensureLogin()) {
                    return@withQueryPermit querySingleInternal(command, retry = false)
                }
                return@withQueryPermit null
            }
            try {
                json.parseToJsonElement(rawBody)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                AppLogger.w(tag, "[goform_get_single] parse failed: ${rawBody.take(200)}")
                null
            }
        }
    }

    // ============ AD / LD / RD ============

    private suspend fun fetchVersionInfo(): Pair<String?, String?> {
        val base = baseUrl()
        val resp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=wa_inner_version,cr_version&multi_data=1&isTest=false&_=${System.currentTimeMillis()}") {
            header("Referer", "$base/index.html")
            if (sessionCookie != null) header("Cookie", sessionCookie!!)
        }
        val body = resp.bodyAsText()
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["wa_inner_version"]?.jsonPrimitive?.contentOrNull to obj["cr_version"]?.jsonPrimitive?.contentOrNull
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null to null }
    }

    private suspend fun computeAd(params: Map<String, String>): String? {
        val (wa, cr) = fetchVersionInfo()
        if (wa.isNullOrBlank() || cr.isNullOrBlank()) {
            AppLogger.w(tag, "computeAd: missing wa/cr (wa=$wa cr=$cr)")
            return null
        }
        val rd = getRd()
        if (rd == null) {
            AppLogger.w(tag, "computeAd: RD is null")
            return null
        }
        val adRaw = sha256Hex("$wa$cr").uppercase() + rd
        return sha256Hex(adRaw).uppercase()
    }

    private suspend fun getRd(): String? {
        val base = baseUrl()
        val resp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=RD&multi_data=1&isTest=false&_=${System.currentTimeMillis()}") {
            header("Referer", "$base/index.html")
            if (sessionCookie != null) header("Cookie", sessionCookie!!)
        }
        val body = resp.bodyAsText()
        return try {
            json.parseToJsonElement(body).jsonObject["RD"]?.jsonPrimitive?.contentOrNull
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    // ============ Logout ============

    override suspend fun logout(): Boolean {
        return try {
            val resp = goformPost(
                mapOf(
                    "goformId" to "LOGOUT",
                    "isTest" to "false"
                )
            )
            AppLogger.d(tag, "Goform logout done")
            resp != null
        } catch (e: Exception) {
            AppLogger.e(tag, "logout failed", e)
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

    internal fun isLoginFailed(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val b = body.lowercase(Locale.ROOT)
        return b.contains("login fail") || b.contains("password error") ||
            b.contains("not logged in") || b.contains("session") ||
            b.contains("error") && b.contains("\"result\"") && !b.contains("\"result\":\"success\"")
    }

    // ============ crypto ============

    internal fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    internal fun base64Encode(input: String): String =
        java.util.Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))

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
            AppLogger.e(tag, "base64Decode failed", e)
            ""
        }
    }

    private fun countJsonFields(body: String): Int {
        if (body.isBlank() || !body.trimStart().startsWith("{")) return 0
        return body.count { it == ':' } // 粗略估算字段数，避免完整解析开销
    }

    override fun close() {
        try { httpClient.close() } catch (_: Exception) {}
        AppLogger.i(tag, "GoformClient closed")
    }

    // ============ QoS delegate ============

    override fun adjustQoS(permits: Int) {
        GoformQoS.adaptiveAdjust(
            targetQueryPermits = permits.coerceIn(1, 8),
            targetSetPermits = (permits / 2).coerceIn(1, 4)
        )
    }

    override fun getQosStatus(): Map<String, Any> = GoformQoS.getStatus()

    override fun setQosEnabled(enabled: Boolean) {
        AppLogger.d(tag, "setQosEnabled($enabled) -> delegated to GoformQoS singleton")
    }

    // 2026-08-29（计划书 13.2.4 收尾）：`NETWORK_TYPE_MAP` + `mapNetworkType()` 已删。
    // 那 44 项数字码→制式名的映射属于**设备知识**，现在只存在于
    // `ZteGoformProfile.NETWORK_TYPE_MAP` / `NETWORK_TYPE_DECODER`（逐条搬过去、语义一致，
    // 含"纯数字才查表、非数字原样透出"这条踩过坑的规则）。
    // 读侧的 `network_type` 在归一化时就已经是可读文案，调用方不需要再翻译一次。
}

