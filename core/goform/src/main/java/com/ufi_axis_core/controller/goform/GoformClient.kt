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
import java.util.concurrent.atomic.AtomicReference

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
    // 无默认值：口令必须由调用方显式注入（唯一构造点 ComponentFactory.buildNetworkGraph
    // 传的是 settings.goformPassword）。此前默认值是 "YWRtaW4="（base64 of admin），
    // 而它会被**原样**当作 sha256Hex(password) 的输入，与 AppSettings.DEFAULT_GOFORM_PASSWORD
    // （明文 "admin"）算出的 hash 不一致 —— 依赖默认值的调用方会静默登录失败。
    private val password: String
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
    // ── 会话/退避状态一律 @Volatile ──
    // 这些字段在 loginMutex 内被读，却同时被 query/post 路径（`lastGiveWayAt`）和
    // invalidateSession()/resetLogin()（`lastValidatedAt`）在**锁外**写，
    // 而且写方与读方跑在不同的 Dispatchers.IO 协程上。非 volatile 时写可能对读方不可见，
    // 让位窗口与指数退避会静默失效 —— 正是 ensureLogin 那段注释想防的登录风暴。

    /**
     * 会话快照：cookie 与「是否已登录」**必须一起换**，所以合成一个不可变对象整体替换。
     *
     * 2026-09-08：原来这是两个独立的 `@Volatile` 字段。`ensureLogin()` 的快速路径在锁外
     * 读 `isLoggedIn` 判定「可以发请求了」，请求构建时再回头读一次 `sessionCookie` ——
     * 这两次读之间 `invalidateSession()` 可以把 cookie 置 null，于是这一次请求带着**空 Cookie**
     * 发出去，必然被设备判未鉴权。改成快照之后，判定与取 cookie 用的是同一份不可变值：
     * 判定通过就一定有当时那个 cookie，[invalidateSession] 只影响**下一次**判定。
     */
    private class SessionSnapshot(val cookie: String?, val loggedIn: Boolean)

    private val session = AtomicReference(SessionSnapshot(null, false))
    @Volatile private var waVersion: String? = null
    @Volatile private var crVersion: String? = null
    @Volatile private var lastLoginAttempt = 0L
    private val baseLoginCooldownMs = 1500L
    // 2026-08-23: 退避上限从 60s 降到 10s —— 之前连续 session 失效会让退避叠到 60s，
    // 前端轮询全被拦截，造成「长时间断连，必须手动重启核心服务」体感。10s 已足够
    // 让设备 goform 释放被官方后台占用的 session，又不超出用户耐心。
    private val maxLoginBackoffMs = 10_000L
    // 2026-08-23: TTL 从 300s 缩到 90s —— ZTE 设备实际会话超时通常 1-3 分钟，
    // 太长的 TTL 会让确保登录一直走缓存路径，等到下一次真断了才一起 invalidate 一波。
    private val sessionCacheTtlMs = 90_000L
    private val sessionValidationIntervalMs = 60_000L // 每 60s 验证一次
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
            attachSessionCookie(session.get())
        }
    }

    internal fun parseJson(body: String): JsonObject? = try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        AppLogger.w(tag, "parseJson failed: ${e.message}")
        null
    }

    /**
     * 附带**指定快照**里的 session Cookie。
     *
     * 快照必须由调用链上游（[ensureSession] 的返回值）传进来，不允许在这里回头读
     * [session] —— 那就又回到「判定用一份、发请求用另一份」的 check-then-act：
     * 判空与解引用之间被 [invalidateSession] 插空，请求会带着空 Cookie 发出去被设备判未鉴权。
     */
    private fun HttpRequestBuilder.attachSessionCookie(snapshot: SessionSnapshot?) {
        val cookie = snapshot?.cookie
        if (cookie != null) header("Cookie", cookie)
    }

    // ============ Login ============

    override suspend fun ensureLogin(): Boolean = ensureSession() != null

    /**
     * 与 [ensureLogin] 同一套判定，但返回**当次判定所依据的会话快照**（null = 不可用）。
     *
     * 请求构建必须用这个返回值取 cookie，不要回头再读 [session]：判定与取 cookie 之间
     * 隔着一次 goform 往返，`invalidateSession()` 完全来得及把 cookie 置 null。
     */
    private suspend fun ensureSession(): SessionSnapshot? {
        ensureBaseUrlResolved()
        val now = System.currentTimeMillis()
        // ── 快速路径：缓存时间内且未到验证周期，直接返回 ──
        val fast = session.get()
        if (fast.loggedIn && (now - lastValidatedAt) < sessionValidationIntervalMs) return fast

        return loginMutex.withLock {
            val nowLocked = System.currentTimeMillis()
            val current = session.get()
            // ── 二次检查 ──
            if (current.loggedIn && (nowLocked - lastValidatedAt) < sessionValidationIntervalMs) return@withLock current

            // ── 到达验证周期或已失效：执行 validate ──
            if (current.loggedIn && (nowLocked - lastValidatedAt) < sessionCacheTtlMs) {
                if (validateSession(current)) {
                    lastValidatedAt = nowLocked
                    return@withLock current
                }
            }

            val failCount = consecutiveLoginFailures.get()
            
            // ── 检查让位状态 ──
            val giveWayElapsed = nowLocked - lastGiveWayAt
            if (lastGiveWayAt > 0 && giveWayElapsed < giveWayDurationMs) {
                AppLogger.d(tag, "ensureLogin: Giving way to official UI (remains ${ (giveWayDurationMs - giveWayElapsed)/1000 }s)")
                return@withLock null
            }

            // 2026-08-24: 不论 isLoggedIn 是否为 true，只要有失败记录就应用退避。
            // 否则在连续登录失败且 isLoggedIn=false 时会陷入无退避的死循环，压满 QoS。
            if (failCount > 0) {
                val backoffMs = (baseLoginCooldownMs * (1L shl failCount.coerceAtMost(6))).coerceAtMost(maxLoginBackoffMs)
                if (nowLocked - lastLoginAttempt < backoffMs && lastLoginAttempt > 0L) {
                    return@withLock null
                }
            }
            markLoggedOut()
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
                loginResp.headers["Set-Cookie"]?.split(";")?.firstOrNull()?.let { storeCookie(it) }
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
                    fallbackResp.headers["Set-Cookie"]?.split(";")?.firstOrNull()?.let { storeCookie(it) }
                    if (fallbackResp.status != HttpStatusCode.OK || isLoginFailed(loginBody)) {
                        if (loginBody.contains("\"result\":\"session\"", ignoreCase = true)) {
                            lastGiveWayAt = System.currentTimeMillis()
                            AppLogger.e(tag, "LOGIN fallback rejected (session active)")
                        } else {
                            AppLogger.e(tag, "LOGIN fallback also rejected: ${loginBody.take(200)}")
                        }
                        consecutiveLoginFailures.incrementAndGet()
                        markLoggedOut()
                        lastLoginAttempt = System.currentTimeMillis()
                        return@withLock null
                    }
                }
                // 登录成功：把 cookie 与「已登录」一次性合成新快照，后续请求都用它。
                val loggedIn = session.updateAndGet { SessionSnapshot(it.cookie, true) }
                val infoResp = httpClient.get("$base/goform/goform_get_cmd_process?cmd=wa_inner_version,cr_version&multi_data=1&isTest=false&_=${System.currentTimeMillis()}") {
                    header("Referer", "$base/index.html")
                    attachSessionCookie(loggedIn)
                }
                try {
                    val infoJson = json.parseToJsonElement(infoResp.bodyAsText()).jsonObject
                    waVersion = infoJson["wa_inner_version"]?.jsonPrimitive?.contentOrNull
                    crVersion = infoJson["cr_version"]?.jsonPrimitive?.contentOrNull
                } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                consecutiveLoginFailures.set(0)
                lastLoginAttempt = System.currentTimeMillis()
                lastValidatedAt = System.currentTimeMillis()
                lastGiveWayAt = 0L // 登录成功，重置让位
                AppLogger.i(tag, "Login successful. wa=$waVersion cr=$crVersion")
                loggedIn
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                AppLogger.e(tag, "Login failed", e)
                consecutiveLoginFailures.incrementAndGet()
                markLoggedOut()
                lastLoginAttempt = System.currentTimeMillis()
                null
            }
        }
    }

    /** 只换 cookie，登录态不动（登录流程中途拿到 Set-Cookie 时用）。 */
    private fun storeCookie(cookie: String) {
        session.updateAndGet { SessionSnapshot(cookie, it.loggedIn) }
    }

    /** 只落「未登录」，**保留 cookie** —— 与改造前 `isLoggedIn = false` 的语义逐字一致。 */
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
        session.set(SessionSnapshot(null, false))
        lastValidatedAt = 0L
        // 故意不动 consecutiveLoginFailures：失败计数只跟踪「登录尝试」是否成功。
    }

    override fun resetLogin() {
        session.set(SessionSnapshot(null, false))
        consecutiveLoginFailures.set(0)
        lastValidatedAt = 0L
    }

    override fun updateGoformPassword(newPwd: String) {
        resetLogin()
    }

    // ============ Auth-failure detection ============

    /**
     * 设备在 `result` 字段里表达的会话/鉴权失败取值（全小写比较）。
     * 只放**确定**语义的值 —— 这个集合是「精确等值」用的，不是子串。
     */
    private val AUTH_FAILURE_RESULTS = setOf("not logged in", "session", "none secure connection")

    /**
     * 会话/鉴权失败判定。
     *
     * 2026-09-08：原实现是一串 `body.contains(...)`，其中 `contains("redirect")`
     * 与 `contains("\"result\":\"session\"")` 会把**正常业务响应**误判成掉线：
     * 设备 JSON 里只要出现 `redirect_url`、`session_timeout` 这类字段名就中招，
     * 后果是白白 invalidateSession() → 强制重登 → 撞上登录退避，表现成随机断连。
     * 现在的口径分两路：
     *  - body 是 JSON：只按 `result` 字段**精确等值**比较（外加显式的 `Error` 字段）；
     *  - body 不是 JSON（设备把未鉴权请求返回 login.html，回的是 HTML）：才退回子串匹配。
     * 判 HTML 走子串这一路是常态而非异常，所以只在「看起来像 JSON」时才调 parseJson，
     * 免得 HTML 每次都在日志里刷一条 parseJson failed。
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
                // 设备也会把错误塞进 Error 字段（历史上见过 "Error":"..."），非空即失败
                val err = (obj["Error"] as? JsonPrimitive)?.contentOrNull
                return !err.isNullOrBlank()
            }
            // 声称是 JSON 却解析不出来：不在这里下结论，交给调用方的完整性校验/解析失败分支
            return false
        }
        val lower = body.lowercase(Locale.ROOT)
        return lower.contains("login.html") ||
            lower.contains("redirect") ||
            lower.contains("none secure connection") ||
            lower.contains("not logged in")
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

    override suspend fun goformPost(params: Map<String, String>): String? =
        (postMeasured(params, retryOnSessionLost = false) as? GoformWriteResult.Accepted)?.body

    /**
     * **幂等**写操作专用入口：会话失效时重登并只重试一次，并把
     * 「会话失效 / 连不上 / 设备表过态」三件事分开返回（判据见 [GoformWriteResult]）。
     *
     * 为什么不让 [goformPost] 一律重试：`SEND_SMS` 这类命令**非幂等**，重发一次可能就是
     * 第二笔话费（见 `GoformSmsClient.sendSms` 的 `NO_RESPONSE` 判据）。重试与否必须由
     * 调用方按命令语义显式选择，所以这里另开一条路，而不是把重试塞进公共 POST。
     *
     * 设置类命令（`goformId=SET_*` / `SET_BEARER_PREFERENCE` 等）对同一取值幂等，
     * 重发安全 —— 这也是修「切换网络制式第一次必定失败」的落点：读路径早就有这套重试
     * （见 [queryInternal]），写路径一直没有，于是一次会话抖动就等于一次用户可见的失败。
     */
    internal suspend fun goformPostIdempotent(params: Map<String, String>): GoformWriteResult =
        postMeasured(params, retryOnSessionLost = true)

    private suspend fun postMeasured(
        params: Map<String, String>,
        retryOnSessionLost: Boolean
    ): GoformWriteResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var attemptNo = 1
        var result = goformPostOnce(params)
        // 重试**必须**在 set 许可归还之后发起（goformPostOnce 内部的 withSetPermit 已退出），
        // 理由与 queryInternal 那段注释一致：持一个许可再去申请第二个，在许可被自适应调节
        // 压到 1 时就是永久挂死。上限由 GoformWritePolicy.MAX_ATTEMPTS 兜，不会无界重试。
        while (retryOnSessionLost && GoformWritePolicy.shouldRetry(attemptNo, result)) {
            attemptNo++
            AppLogger.w(tag, "[goform_set] session lost, re-login and retry (attempt=$attemptNo/${GoformWritePolicy.MAX_ATTEMPTS})")
            result = goformPostOnce(params)
        }
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
        AppLogger.net(AppLogger.LogLevel.DEBUG, GOFORM_NET_TAG, "[QoS] goformPost cost=${cost}ms attempts=$attemptNo")
        result
    }


    /**
     * 单次 `goform_set_cmd_process` 往返。
     *
     * 传输层异常在这里就地收成 [GoformWriteResult.Unreachable]，**不再往上抛** ——
     * 原来它会一路冒到 Ktor 的兜底处理器变成一个没有 body 语义的 500，
     * 客户端只能显示「服务器内部错误」。
     */
    private suspend fun goformPostOnce(params: Map<String, String>): GoformWriteResult {
        val snapshot = ensureSession()
        if (snapshot == null) {
            AppLogger.e(tag, "goformPost aborted: not logged in")
            return GoformWriteResult.SessionLost
        }
        return GoformQoS.withSetPermit {
            // AD 由 wa_inner_version / cr_version / RD 三次前置查询算出来。会话已死时这三次
            // 查询拿回来的是登录页，解析不出字段 → computeAd 返回 null。所以「AD 算不出来」
            // 在实测里就是「会话失效」最常见的外观，必须归到可重试一侧。
            val ad = try {
                computeAd(params, snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(tag, "[goform_set] AD precheck transport error: ${e.message}")
                return@withSetPermit GoformWriteResult.Unreachable(e.message ?: e::class.java.simpleName)
            }
            if (ad == null) {
                AppLogger.e(tag, "goformPost aborted: AD compute failed (cookie=${snapshot.cookie?.take(8)})")
                invalidateSession()
                return@withSetPermit GoformWriteResult.SessionLost
            }
            val base = baseUrl()
            val formBody = GoformCodec.buildSetFormBody(params, ad)
            val rawBody: String
            val status: HttpStatusCode
            try {
                val resp = httpClient.post("$base/goform/goform_set_cmd_process") {
                    header("Referer", "$base/index.html")
                    header("Origin", base)
                    header("Content-Type", "application/x-www-form-urlencoded")
                    attachSessionCookie(snapshot)
                    setBody(formBody)
                }
                rawBody = GoformCodec.decodeBody(resp.readBytes())
                status = resp.status
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(tag, "[goform_set] transport error: ${e.message}")
                return@withSetPermit GoformWriteResult.Unreachable(e.message ?: e::class.java.simpleName)
            }
            AppLogger.net(AppLogger.LogLevel.DEBUG, GOFORM_NET_TAG, "[goform_set] status=$status body=${rawBody.take(200)}")
            val verdict = GoformWritePolicy.classify(status.value, isAuthFailure(rawBody), rawBody)
            if (verdict is GoformWriteResult.SessionLost) {
                if (rawBody.contains("\"result\":\"session\"", ignoreCase = true)) {
                    lastGiveWayAt = System.currentTimeMillis()
                    AppLogger.w(tag, "[goform_set] session active on official UI, backing off")
                } else {
                    AppLogger.w(tag, "[goform_set] auth/session lost, invalidating; body=${rawBody.take(120)}")
                }
                invalidateSession()
            }
            verdict
        }
    }

    // ============ Query ============

    override suspend fun query(commands: List<String>): JsonObject? {
        if (!ensureLogin()) return null
        return queryInternal(commands, retry = true)
    }

    /**
     * 重试**必须**在许可释放之后发起。
     *
     * 早期实现直接在 `withQueryPermit { … }` 块内递归调用自身，等于「持有一个许可再去申请
     * 第二个」。`GoformQoS.querySemaphore` 的许可数会被自适应调节压到 2（DataScheduler 温度
     * 临界）甚至 1（`ConfigRoutes` 的 adaptiveAdjust，minPermits=1），而 kotlinx 的
     * `Semaphore.acquire()` 没有超时 —— 许可为 1 时任意一次重试即永久挂死，许可为 2 时两个
     * 并发重试即挂死，整条 goform 读路径再也不恢复（表现为「长时间断连，必须重启核心服务」）。
     */
    private suspend fun queryInternal(commands: List<String>, retry: Boolean): JsonObject? {
        val snapshot = ensureSession() ?: return null
        var authLost = false
        val first = GoformQoS.withQueryPermit { queryOnce(commands, snapshot) { authLost = true } }
        if (first != null || !authLost) return first
        // 会话失效：此刻许可已归还，重登与重试都在许可之外，不会自我阻塞。
        if (!retry) return null
        // 重登会换 cookie，必须**重新取一份快照**再发第二次，不能复用上面那份已失效的。
        val renewed = ensureSession() ?: return null
        return GoformQoS.withQueryPermit { queryOnce(commands, renewed) { } }
    }

    /**
     * 单次 goform 读取。返回 null 时通过 [onAuthLost] 区分「会话失效（可重试）」与
     * 「解析失败（重试无意义）」，避免把两种失败混成同一个 null。
     */
    private suspend fun queryOnce(
        commands: List<String>,
        snapshot: SessionSnapshot,
        onAuthLost: () -> Unit
    ): JsonObject? {
        // 缓存边界（计划书 1.0.3）：这一层是**传输层**缓存，key 是 cmd 名集合，
        // value 是设备**原始**响应。字段归一化在它的下游（GoformFieldMapper /
        // GoformXxxClient），上层业务缓存（ResponseCache / DataHub）存的才是 canonical 数据。
        // 不要在这一层改字段名或改值 —— 否则 QoS 快照会同时污染所有调用方，
        // 而且归一化会变成"有时做有时不做"（缓存命中时被跳过）。
        val key = commands.sorted().joinToString(",")
        GoformQoS.getCachedQuery(key)?.let {
            AppLogger.d(tag, "[cache HIT] $key")
            return it
        }
        val base = baseUrl()
        val cmdParam = commands.joinToString(",")
        val url = "$base/goform/goform_get_cmd_process?cmd=$cmdParam&multi_data=1&isTest=false&_=${System.currentTimeMillis()}"
        val resp = httpClient.get(url) {
            header("Referer", "$base/index.html")
            attachSessionCookie(snapshot)
        }
        val rawBody = GoformCodec.decodeBody(resp.readBytes())
        val status = resp.status
        AppLogger.net(AppLogger.LogLevel.DEBUG, GOFORM_NET_TAG, "[goform_get] cmd=$cmdParam status=$status body=${rawBody.take(200)}")

        // 2026-08-24: 增强校验。部分设备在负载过高时会返回截断/非完整的 JSON。
        // 如果请求了多个字段但返回字段数过少（且非 auth 错误），标记为失效。
        //
        // 2026-09-08：字段数原来用 `countJsonFields()`（数 body 里 ':' 的个数）估算，
        // 而字符串值里的冒号（URL、"12:00"、base64 短信正文）会把计数虚高。
        // 误判成 partial 就白白 invalidateSession() 强制重登；漏判则把截断的数据
        // 当权威值往上游灌。反正下面就要解析，直接数**解析出来的顶层 key**，两处共用一次解析。
        val authFailure = isAuthFailure(rawBody)
        val obj = if (status == HttpStatusCode.OK && !authFailure) parseJson(rawBody) else null
        val isPartial = status == HttpStatusCode.OK && !authFailure &&
                commands.size > 5 && obj != null && obj.size < 2

        if (status != HttpStatusCode.OK || authFailure || isPartial) {
            if (isPartial) {
                AppLogger.w(tag, "[goform_get] Partial response detected ($cmdParam, keys=${obj?.size ?: 0}), invalidating session")
            } else if (rawBody.contains("\"result\":\"session\"", ignoreCase = true)) {
                lastGiveWayAt = System.currentTimeMillis()
                AppLogger.w(tag, "[goform_get] session active on official UI, backing off")
            } else {
                AppLogger.w(tag, "[goform_get] auth/session lost (status=$status), invalidating")
            }
            invalidateSession()
            onAuthLost()
            return null
        }
        if (obj == null) {
            AppLogger.w(tag, "[goform_get] parse failed: ${rawBody.take(200)}")
            return null
        }
        GoformQoS.cacheQuery(key, obj)
        return obj
    }


    override suspend fun querySingle(command: String): JsonElement? {
        if (!ensureLogin()) return null
        return querySingleInternal(command, retry = true)
    }

    /** 重试在许可之外发起，理由同 [queryInternal]。 */
    private suspend fun querySingleInternal(command: String, retry: Boolean): JsonElement? {
        val snapshot = ensureSession() ?: return null
        var authLost = false
        val first = GoformQoS.withQueryPermit { querySingleOnce(command, snapshot) { authLost = true } }
        if (first != null || !authLost) return first
        if (!retry) return null
        // 重登换了 cookie，第二次必须用新快照（理由同 [queryInternal]）。
        val renewed = ensureSession() ?: return null
        return GoformQoS.withQueryPermit { querySingleOnce(command, renewed) { } }
    }

    private suspend fun querySingleOnce(
        command: String,
        snapshot: SessionSnapshot,
        onAuthLost: () -> Unit
    ): JsonElement? {
        val base = baseUrl()
        val url = "$base/goform/goform_get_cmd_process?cmd=$command&isTest=false&_=${System.currentTimeMillis()}"
        val resp = httpClient.get(url) {
            header("Referer", "$base/index.html")
            attachSessionCookie(snapshot)
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
            onAuthLost()
            return null
        }
        return try {
            json.parseToJsonElement(rawBody)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            AppLogger.w(tag, "[goform_get_single] parse failed: ${rawBody.take(200)}")
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
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null to null }
    }

    private suspend fun computeAd(params: Map<String, String>, snapshot: SessionSnapshot): String? {
        val (wa, cr) = fetchVersionInfo(snapshot)
        if (wa.isNullOrBlank() || cr.isNullOrBlank()) {
            AppLogger.w(tag, "computeAd: missing wa/cr (wa=$wa cr=$cr)")
            return null
        }
        val rd = getRd(snapshot)
        if (rd == null) {
            AppLogger.w(tag, "computeAd: RD is null")
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
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
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

    /** 登录响应里代表成功的 `result` 取值（全小写精确比较）。 */
    private val LOGIN_SUCCESS_RESULTS = setOf("0", "success")

    /** 登录响应里代表失败的 `result` 取值（全小写精确比较）。 */
    private val LOGIN_FAILURE_RESULTS = setOf(
        "failure", "login fail", "login failed", "password error",
        "not logged in", "session", "none secure connection"
    )

    /**
     * 登录响应是否表示失败。
     *
     * 2026-09-08：原实现是对整个 body 做小写子串匹配，其中 `b.contains("session")`
     * 会把任何**恰好带 session 字样**的合法响应（如含 `session_timeout` 字段）判成登录失败 ——
     * 而这个判定的代价很重：直接 `consecutiveLoginFailures++`，退避是 1500 * 2^n，
     * 几次误判就把整条 goform 路径按到退避上限，表现成「长时间断连」。
     * 现在只按 `result` 字段精确比较；子串匹配仅保留给**非 JSON**的 HTML 登录页/明文错误串。
     * 未知 result 一律不判失败（保持既有宽松语义，不新增「登不进去」的失败模式），
     * 但打一条 WARN 留证据 —— 真遇到新固件的取值，日志里能看见再收紧。
     */
    internal fun isLoginFailed(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val trimmed = body.trimStart()
        // HTML：设备把未鉴权/出错的登录请求返回成页面，这一路只能靠形状与子串判
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
            AppLogger.w(tag, "isLoginFailed: unknown login result='$result', treating as success")
            return false
        }
        // 没有 result 字段：只认显式的 Error 字段
        val err = (obj["Error"] as? JsonPrimitive)?.contentOrNull
        return !err.isNullOrBlank()
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

