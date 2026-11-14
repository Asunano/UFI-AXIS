package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.GoformGuard
import com.ufi_axis_core.util.GoformQoS
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
 * Goform 客户端：ZTE 设备私有协议的防腐层实现。
 *
 * 认证模型：
 *  - 每次 read / readOne / write 之前都先调用 [ensureLogin]；
 *  - 登录优先使用 goformId=LOGIN_MULTI_USER（附带 IP=localhost），失败后回退 goformId=LOGIN；
 *  - 口令摘要：encPwd = sha256Hex(sha256Hex(password) + LD)，两级均为大写十六进制；
 *  - 写操作须在请求体附带 AD = sha256Hex(sha256Hex(wa + cr) + RD)，两级均为大写；
 *  - 登录态带 TTL 缓存与 session 校验，避免频繁登录；登录失败按指数退避。
 *
 * 读与写都依赖登录态（登录响应 Set-Cookie 下发的 session），与设备 goform 的要求一致：
 * 未携带会话的读取会被设备以 "none secure connection" / "not logged in" 拒绝。
 */
class GoformClient(
    private val deviceIp: String = "192.168.0.1",
    private val port: Int = 8080,
    // 无默认值：口令必须由调用方显式注入（设备插件经 TransportConfig.password 传入设备的
    // goform 明文口令）。该值直接作为 sha256Hex(password) 的输入，因此必须是明文；传入
    // base64 等派生形式会得到与设备不一致的摘要，表现为静默登录失败，且没有明确的错误日志。
    private val password: String
) : GoformTransport {

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
    // ── 会话与退避状态一律 @Volatile ──
    // 这些字段在 loginMutex 内被读取，但 lastGiveWayAt 同时由 query/post 路径在锁外写入，
    // lastValidatedAt 同时由 invalidateSession() / resetLogin() 在锁外写入，读写双方运行在
    // 不同的 Dispatchers.IO 协程上。缺少 volatile 时写操作对读方不可见，让位窗口与指数退避
    // 会静默失效，进而引发重复登录。

    /**
     * 会话快照：cookie 与「是否已登录」必须同时变更，因此合并为一个不可变对象整体替换。
     *
     * 若二者分属两个独立的 @Volatile 字段，[ensureLogin] 的快速路径会在锁外读取登录态判定
     * 可发送请求，请求构建时再读取一次 cookie；两次读取之间 [invalidateSession] 可将 cookie
     * 置为 null，该请求便携带空 Cookie 发出，被设备判为未鉴权。使用快照后，判定与取 cookie
     * 依据同一份不可变值：判定通过即保证对应 cookie 存在，[invalidateSession] 只影响下一次判定。
     */
    private class SessionSnapshot(val cookie: String?, val loggedIn: Boolean)

    private val session = AtomicReference(SessionSnapshot(null, false))
    @Volatile private var waVersion: String? = null
    @Volatile private var crVersion: String? = null
    @Volatile private var lastLoginAttempt = 0L
    @Volatile private var lastValidatedAt = 0L
    @Volatile private var lastGiveWayAt = 0L
    // partial 响应「升级重登」的节流时间戳。部分固件过载或会话半失效时，会对多 key 查询
    // 持续返回 200 + 空体；若不做升级重登，数据会持续缺失（表现为 /api/device/traffic-limit
    // 返回 503、信号 / WiFi / 邻区字段为空）。设备重启可使该状态恢复，说明重新登录有效。
    // 每 60s 只允许一次 partial→重登升级，兼顾自愈与防止登录风暴。
    private val lastPartialRecoveryAt = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * partial 自愈的连续失败次数。
     *
     * 每次「升级重登后仍为 partial」加一，下一次升级的等待时间随之指数增长
     * （60s → 120s → 240s → …，上限 10min）；任一次完整（非 partial 的）成功响应都会将计数
     * 清零并复位退避，使设备恢复后立即回到 60s 的初始间隔。
     *
     * 由此，固件处于半失效状态时仍会持续尝试，最坏情况下每 10min 尝试一次；不使用固定 60s
     * 间隔，以免设备持续异常时每分钟重复登录，额外消耗 QoS 配额并抬高登录风险。
     */
    private val partialRecoveryFailures = java.util.concurrent.atomic.AtomicInteger(0)

    /** 首次升级等待（60s）；之后按 2^n 指数增长。 */
    private val PARTIAL_RECOVERY_BASE_MS = 60_000L

    /** 升级等待上限（10min）：设备持续异常时仍保持该间隔继续尝试。 */
    private val PARTIAL_RECOVERY_MAX_MS = 600_000L

    /** 第 [failures] 次连续失败后的升级等待：BASE × 2^n，封顶 MAX。 */
    private fun partialRecoveryWaitMs(failures: Int): Long =
        (PARTIAL_RECOVERY_BASE_MS shl failures.coerceIn(0, 16)).coerceAtMost(PARTIAL_RECOVERY_MAX_MS)
    // 最近一次 validateSession 是否因网络错误而被视为通过。仅在 loginMutex 内的
    // forceValidate 消费点读取；null 表示本次校验真实验证了会话。
    @Volatile private var lastValidateNetworkDown: String? = null

    // 会话 TTL、校验间隔、登录退避基数与上限、让位窗口这四组数值与判定均位于
    // [GoformSessionPolicy]；本类只保存状态（上方三个时间戳），判定一律委托给 policy。


    private val resolveMutex = Mutex()
    @Volatile private var baseUrlResolved = false
    private var effectiveBaseUrl: String = ""

    override fun baseUrl(): String =
        if (baseUrlResolved) effectiveBaseUrl
        else if (port == 80) "http://$deviceIp" else "http://$deviceIp:$port"

    /**
     * 解析实际可达的 base url（如 `http://<设备地址>:8080` 或 `http://127.0.0.1:8080`）。
     *
     * 文件类端点（二维码等）不经过 [ensureLogin]，但同样需要先确定 base url；否则 [baseUrl]
     * 只能返回未经验证的拼装值，宿主或端口不符时请求会直接失败。
     */
    override suspend fun ensureBaseUrlResolved() {
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

    private suspend fun isReachable(cand: String): Boolean = withContext(Dispatchers.IO) {
        // 此处使用阻塞式 HttpURLConnection。调用方的 HTTP 服务器 callGroupSize=1，若该调用
        // 运行在调用方线程上，首次解析 base url 时的阻塞会挂起整个后端的请求处理。因此必须
        // 切到 IO 线程：最坏情况下只有本次解析变慢。
        try {
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
     * 直接 GET 一个设备侧 URL（用于文件类端点：二维码图片、短信附件等）。
     *
     * 必须携带 session Cookie：设备的 `goform_get_file_process` 与 goform_get / set 一样受
     * session 保护；未携带 Cookie 时设备不返回 401，而是返回 200 的登录页 HTML，调用方按
     * 图片解析即得到无效图像。read / write 两条路径均携带 Cookie，本方法不得例外。
     */
    override suspend fun httpGet(url: String): HttpResponse {
        val base = baseUrl()
        return httpClient.get(url) {
            header("Referer", "$base/index.html")
            attachSessionCookie(session.get())
        }
    }

    override fun parseJson(body: String): JsonObject? = try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        AppLogger.w(tag, "parseJson failed: ${e.message}")
        null
    }

    /**
     * 附带指定快照中的 session Cookie。
     *
     * 快照必须由调用链上游（[ensureSession] 的返回值）传入，不得在此处重新读取 [session]：
     * 否则判定与发送依据不同的值，构成 check-then-act；判定与解引用之间若被
     * [invalidateSession] 插入，请求会携带空 Cookie 发出并被设备判为未鉴权。
     */
    private fun HttpRequestBuilder.attachSessionCookie(snapshot: SessionSnapshot?) {
        val cookie = snapshot?.cookie
        if (cookie != null) header("Cookie", cookie)
    }

    // ============ Login ============

    override suspend fun ensureLogin(): Boolean = ensureSession() != null

    /**
     * 强制校验版的 [ensureLogin]：绕过 60s 校验间隔，在请求下发前确认会话仍然有效。
     *
     * ## 用途
     *
     * `SEND_SMS` 经由 [writeSessionSafe] 发送，会话失效（登录页 / 非 200 / AD 无法计算）时会
     * 重新登录并重试一次。部分固件在会话陈旧时不返回登录页，而是返回 200 + 业务失败体
     * （判据见 [GoformWritePolicy.shouldRetryBusinessFailure]）。该响应归入
     * [GoformWriteResult.Accepted]，`isSuccess` 为假，`SendVerdict` 判为 `REJECTED` 且不重发
     * （重复计费不可接受），最终表现为首次发送失败。在下发前消除「会话陈旧」这一前提即可
     * 避免该结果。
     *
     * ## 语义边界
     *
     * 1. 只跳过校验间隔（[GoformSessionPolicy.SESSION_VALIDATION_INTERVAL_MS]），强制执行一次
     *    [validateSession]（一次轻量 `cmd=RD` GET）；
     * 2. 仅在校验失败时重新登录，不每次强制重登：重登需要 LD + LOGIN 两到三次往返，还会触发
     *    [consecutiveLoginFailures] 退避与官方后台让位窗口，反而增加首次失败的概率；
     * 3. 让位窗口与登录退避照常生效（见 [ensureSession] 中对应短路的注释）。「强制」仅指多
     *    执行一次校验，不改变这两条保护。
     *
     * ## 成本与适用范围
     *
     * 每次调用增加一次轻量 GET：一次 `cmd=RD` 往返在设备上为几十毫秒级，对用户发起的单次
     * 操作（该路径随后还需回读信箱 3 次，每次 1.2s）可以接受。
     *
     * 不得接入自动或批量路径，例如短信转发、日报、告警通知等由调度器驱动、单轮可能连续发送
     * 多条的场景。高频调用会为设备额外增加等量的 goform 请求，占满 [GoformQoS] 的许可，并使
     * 读路径一并变慢（长时间断连即由此累积）。这些路径继续使用 [ensureLogin]：它们本身处在
     * 重试流程中，多一轮延迟没有用户可感知的代价。
     *
     * 当前唯一调用点：`GoformSmsClient.sendSms`。
     */
    override suspend fun ensureFreshLogin(): Boolean = ensureSession(forceValidate = true) != null

    /**
     * 与 [ensureLogin] 使用同一套判定，但返回当次判定所依据的会话快照（null 表示不可用）。
     *
     * 请求构建必须使用该返回值取 cookie，不得回读 [session]：判定与取 cookie 之间隔着一次
     * goform 往返，`invalidateSession()` 可能在此期间将 cookie 置为 null。
     *
     * @param forceValidate 见 [ensureFreshLogin]：只跳过校验间隔，不跳过让位窗口与登录退避。
     */
    private suspend fun ensureSession(forceValidate: Boolean = false): SessionSnapshot? {
        ensureBaseUrlResolved()
        val now = System.currentTimeMillis()
        // ── 快速路径：缓存时间内且未到验证周期，直接返回 ──
        // forceValidate 时 gate 不会给出 USE_CACHED，于是一定进锁走一次 validateSession。
        val fast = session.get()
        if (GoformSessionPolicy.gate(fast.loggedIn, now - lastValidatedAt, forceValidate)
            == GoformSessionPolicy.SessionGate.USE_CACHED
        ) return fast

        // 锁外预检：若在入锁后才做退避 / 让位判定，设备断连恢复窗口内 N 个调用者会先排队、
        // 再逐个被拒，排队成本全部浪费。预检仅为快速路径优化，锁内仍保留二次检查：预检与
        // 入锁之间状态可能变化。
        val failCountPre = consecutiveLoginFailures.get()
        if (GoformSessionPolicy.inLoginBackoff(failCountPre, lastLoginAttempt, now) ||
            GoformSessionPolicy.shouldGiveWay(lastGiveWayAt, now)
        ) {
            return null
        }

        return loginMutex.withLock {
            val nowLocked = System.currentTimeMillis()
            val current = session.get()
            when (GoformSessionPolicy.gate(current.loggedIn, nowLocked - lastValidatedAt, forceValidate)) {
                // ── 二次检查 ──
                GoformSessionPolicy.SessionGate.USE_CACHED -> return@withLock current
                // ── 到达验证周期（或调用方要求强制校验）：执行 validate ──
                GoformSessionPolicy.SessionGate.VALIDATE -> {
                    // forceValidate 的三条日志是确认「下发前强制校验确实执行」的唯一痕迹：
                    // validateSession 成功时本身不输出日志（仅在网络错误或会话失效时输出）。
                    // 排障关键字：`[session] force validate`。
                    if (forceValidate) {
                        AppLogger.i(tag, "[session] force validate before dispatch " +
                                "(sinceValidated=${nowLocked - lastValidatedAt}ms)")
                    }
                    // forceValidate 时记录本次校验是否因网络错误而被视为通过。
                    lastValidateNetworkDown = null
                    if (validateSession(current) { lastValidateNetworkDown = it }) {
                        lastValidatedAt = nowLocked
                        if (forceValidate) AppLogger.i(tag, "[session] force validate passed, reusing session")
                        // forceValidate 下若校验仅因网络错误而被视为通过，则本次强制校验没有
                        // 验证任何内容（设备不可达或无响应）。按未登录返回 null，使 sendSms 在
                        // 下发前直接判为 REJECTED，而不必等 POST 超时后归入 NO_RESPONSE。
                        // 普通读路径不传回调，行为不变（仍信任上次登录态）。
                        if (forceValidate && lastValidateNetworkDown != null) {
                            AppLogger.w(tag, "[session] force validate passed only via network error " +
                                    "(device unreachable?), treating as unavailable: $lastValidateNetworkDown")
                            return@withLock null
                        }
                        return@withLock current
                    }
                    // 校验未通过：validateSession 内部已调用 invalidateSession()，继续走重登。
                    // 该日志为 WARN（release 构建仅输出 WARN 及以上）：它是「陈旧会话在下发前
                    // 被拦截」的直接证据，必须保留。
                    if (forceValidate) AppLogger.w(tag, "[session] force validate failed, re-login before dispatch")
                }
                // 没有登录态 / 连 TTL 都过了：直接重登，不浪费一次校验 GET。
                GoformSessionPolicy.SessionGate.LOGIN -> Unit
            }

            val failCount = consecutiveLoginFailures.get()

            // ── 检查让位状态 ──
            // 该判定与下方的登录退避对 forceValidate 同样生效：「强制」仅表示多校验一次，
            // 不表示抢占登录。绕过这两条保护会与官方后台正在使用的会话冲突，并触发指数退避，
            // 从而制造新的首次失败。
            if (GoformSessionPolicy.shouldGiveWay(lastGiveWayAt, nowLocked)) {
                val remainsSec = (GoformSessionPolicy.GIVE_WAY_DURATION_MS - (nowLocked - lastGiveWayAt)) / 1000
                AppLogger.d(tag, "ensureLogin: Giving way to official UI (remains ${remainsSec}s)")
                return@withLock null
            }

            // 不论当前是否处于登录态，只要存在失败记录就应用退避；否则在连续登录失败且未
            // 登录时会形成无退避的重试循环，占满 QoS。
            if (GoformSessionPolicy.inLoginBackoff(failCount, lastLoginAttempt, nowLocked)) {
                return@withLock null
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
                        AppLogger.w(tag, "Login rejected (session active), giving way for ${GoformSessionPolicy.GIVE_WAY_DURATION_MS / 1000}s")
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

    /** 仅置为未登录状态，保留 cookie。 */
    private fun markLoggedOut() {
        session.updateAndGet { SessionSnapshot(it.cookie, false) }
    }

    private suspend fun validateSession(
        snapshot: SessionSnapshot,
        networkDown: (String) -> Unit = {}
    ): Boolean {
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
            // 网络/解析异常：保留上次登录态，仅标记「不可达」，不增加 failCount。
            // 网络错误不会使 cookie 失效，下一次请求通常即可恢复；若在此处累加计数，
            // 频繁的网络抖动会把 failCount 推至退避上限，使所有调用路径被拒。
            AppLogger.w(tag, "validateSession network error (keeping login state): ${e.message}")
            // 将「网络错误不等于会话仍然有效」告知调用方，forceValidate 路径据此提前定性；
            // 普通读路径不传回调，行为不变。
            networkDown(e.message ?: "network error")
            true
        }
    }

    /**
     * 标记「下次重新登录」，不递增 [consecutiveLoginFailures]。
     *
     * 退避时长为 1500 * 2^failCount（上限 [GoformSessionPolicy.MAX_LOGIN_BACKOFF_MS]）。若在
     * 此处递增计数，长时间运行中的偶发心跳失败与 ZTE 官方后台在线导致的 "session active"
     * 会使 failCount 在数小时内累积至上限，此后所有路径（前端 DataScheduler 轮询、信号采集、
     * 登出、API 路由）都会被 loginMutex 拒绝，形成长时间断连，且需要重启核心服务才能恢复。
     *
     * 真正的登录失败（LD/AD 错误等）由 `ensureLogin()` 的 catch 分支递增计数；cookie 失效与
     * 网络抖动不得计入该计数。
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
     * 判定分两路：
     *  - body 为 JSON：仅对 `result` 字段做精确等值比较（另加显式的 `Error` 字段）；
     *  - body 非 JSON（设备对未鉴权请求返回 login.html）：退回子串匹配。
     *
     * 不得对 JSON 响应使用子串匹配：正常业务响应中出现 `redirect_url`、`session_timeout` 等
     * 字段名即会被误判为掉线，导致无谓的 invalidateSession() → 强制重登 → 撞上登录退避，
     * 表现为随机断连。HTML 走子串属于常态路径，因此仅在 body 以 `{` 开头时才调用 parseJson，
     * 避免每次 HTML 响应都输出一条 parseJson failed。
     */
    override fun isAuthFailure(body: String): Boolean {
        if (body.isBlank()) return false
        val trimmed = body.trimStart()
        if (trimmed.startsWith("<")) return true
        if (trimmed.startsWith("{")) {
            val obj = parseJson(body)
            if (obj != null) {
                val result = (obj["result"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase(Locale.ROOT)
                if (result != null && result in AUTH_FAILURE_RESULTS) return true
                // 设备也会通过 Error 字段表达失败；该字段非空即视为失败
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

    override suspend fun write(params: Map<String, String>): String? =
        (postMeasured(params, retryOnSessionLost = false) as? GoformWriteResult.Accepted)?.body

    /**
     * 幂等写操作专用入口：会话失效时重新登录并只重试一次，并将「会话失效 / 连不上 /
     * 设备已表态」三种结果分别返回（判据见 [GoformWriteResult]）。
     *
     * [write] 不得一律重试：`SEND_SMS` 这类命令非幂等，重发一次可能产生第二笔费用
     * （见 `GoformSmsClient.sendSms` 的 `NO_RESPONSE` 判据）。是否重试必须由调用方按命令语义
     * 显式选择，因此重试逻辑放在本入口，不放入公共 POST 路径。
     *
     * 设置类命令（`goformId=SET_*` / `SET_BEARER_PREFERENCE` 等）对同一取值幂等，重发安全。
     * 写路径此前没有与读路径（见 [queryInternal]）对应的重试，一次会话抖动即等于一次用户可见
     * 的失败；本入口补上该重试。
     */
    override suspend fun writeIdempotent(params: Map<String, String>): GoformWriteResult =
        postMeasured(params, retryOnSessionLost = true)

    /**
     * 非幂等但「未发出即应重发」的写操作专用入口（`SEND_SMS` 的落点）。
     *
     * 三条约束：
     *
     * 1. 仅对「确定未发出」重试。只有 [GoformWriteResult.SessionLost] 会触发重登重发：该状态
     *    表示命令未到达固件（未登录 / AD 前置查询取不到 wa、cr、RD / 设备返回登录页 / HTTP
     *    非 200，判据见 [GoformWriteResult]），重发不会产生第二笔费用。
     * 2. 业务失败绝不重试。[GoformWriteResult.Accepted] 表示设备已接收并表态；即使 body 表示
     *    失败，也无法排除命令已进入发送队列，重发可能产生第二条真实短信与第二笔费用。因此
     *    此处显式关闭 `retryOnBusinessFailure`，不得改用 [writeIdempotent]（后者已开启）。
     * 3. [GoformWriteResult.Unreachable] 同样不重试：无法获得设备表态，同样无法排除已发出。
     *
     * ## 三个写入口的分工
     *
     * | 入口 | 会话失效 | 200 + 业务失败 | 使用方 |
     * |---|---|---|---|
     * | [write] | 不重试 | 不重试 | `RetryPolicy.NEVER` 的命令（REBOOT / SHUTDOWN / FACTORY_RESET…）、删除信箱 / 标记已读 |
     * | [writeIdempotent] | 重试一次 | 重试一次 | `SET_*` 这类对同一取值幂等的设置命令 |
     * | [writeSessionSafe] | 重试一次 | 不重试 | `SEND_SMS`（非幂等，但「未发出」可安全重发） |
     *
     * `SEND_SMS` 若走 [write]，整圈重试被 `retryOnSessionLost = false` 跳过，则冷启动或会话被
     * 官方后台抢占后的首次发送必然失败，第二次发送因已重新登录而成功。常规短信读取路径由
     * ContentResolver 提供，不再持续为 goform 会话续期，该失败模式更易出现。
     */
    override suspend fun writeSessionSafe(params: Map<String, String>): GoformWriteResult =
        postMeasured(params, retryOnSessionLost = true, retryOnBusinessFailure = false)

    /**
     * 一次写操作的完整编排：重试圈 + 最终判据日志 + QoS 记账。
     *
     * @param retryOnSessionLost 会话失效（命令确定未到达固件）时重登并重试一次。该参数同时是
     *   整个重试圈的总开关：false 表示一次都不重试（[write] 即此路径）。
     * @param retryOnBusinessFailure 设备返回 200 但 body 表示失败时，也重登并重试一次。
     *   默认为 true；[write] / [writeIdempotent] 的既有调用方均不传该参数，行为不变。
     *   仅 [writeSessionSafe] 显式传 false（重发存在重复计费风险，见该方法的 KDoc）。
     */
    private suspend fun postMeasured(
        params: Map<String, String>,
        retryOnSessionLost: Boolean,
        retryOnBusinessFailure: Boolean = true
    ): GoformWriteResult = withContext(GoformGuard.dispatcher) {
        // 写路径同样使用 goform 专用调度器：写操作需要等待登录、前置查询与 POST，占用线程
        // 时间较长，与文件、数据库共用同一线程池没有收益。
        val start = System.currentTimeMillis()
        // 写路径整体预算 20s。设备半失效时一次写操作包含登录链、AD 的三次前置查询、POST 与
        // 重试圈；传输层单次超时为 10s，缺少整体预算时最坏可挂起约 40s（客户端此时已超时，
        // 用户重试还可能与该写操作并发落到设备）。
        // 超时归入 Unreachable：不能归 SessionLost（会触发上层重发），也不能归 Accepted。
        try {
            withTimeout(20_000L) {
        // 循环体位于 GoformWritePolicy，使「SessionLost 重试一次 / 业务失败绝不重发」两条规则
        // 可在不发起 HTTP 的前提下被断言；判定顺序与日志文案与该策略保持一致。
        val attempted = GoformWritePolicy.runAttempts(
            goformId = params["goformId"],
            retryOnSessionLost = retryOnSessionLost,
            retryOnBusinessFailure = retryOnBusinessFailure,
            isSuccess = { isSuccess(it) },
            invalidateSession = { invalidateSession() },
            warn = { AppLogger.w(tag, it) },
        ) { goformPostOnce(params) }
        val result = attempted.result
        val attemptNo = attempted.attempts
        // 重试后仍是业务失败 = 设备真的拒绝了这次取值，留一条 WARN 作为最终判据。
        (result as? GoformWriteResult.Accepted)?.body?.let { body ->
            if (!isSuccess(body)) {
                AppLogger.w(tag, "[goform_set] rejected after $attemptNo attempt(s): " +
                        "goformId=${params["goformId"]} body=${body.take(200)}")
            }
        }
        val cost = System.currentTimeMillis() - start
        // 任何写操作都会使 GoformQoS 的 2 秒查询快照失效。未清除时，写入后立即回读会命中旧
        // 快照并得到写入前的值（表现为开关点击后无反应或状态回退）。上层
        // ResponseCache.invalidate 位于更下游，无法覆盖这一层。写操作频率远低于查询，整体
        // 清空的代价可以接受。
        GoformQoS.clearCache()
        GoformQoS.adaptiveAdjust(
            targetQueryPermits = 6,
            targetSetPermits = 3
        )
        AppLogger.net(AppLogger.LogLevel.DEBUG, GOFORM_NET_TAG, "[QoS] goformPost cost=${cost}ms attempts=$attemptNo")
        result
        }
        } catch (e: TimeoutCancellationException) {
            // 整体预算超时。Unreachable 的语义已排除「确定未发出」，与超时性质一致；
            // NEVER 类命令（如 REBOOT）不会因此被上层重发。
            AppLogger.w(tag, "[goform_set] overall budget (20s) exceeded, goformId=${params["goformId"]}")
            GoformWriteResult.Unreachable("overall timeout 20s")
        }
    }


    /**
     * 单次 `goform_set_cmd_process` 往返。
     *
     * 传输层异常在此就地转换为 [GoformWriteResult.Unreachable]，不再向上抛出；若向上抛出，
     * 该异常会进入 Ktor 的兜底处理器，成为一个不含业务语义的 500，客户端只能显示
     * 「服务器内部错误」。
     */
    private suspend fun goformPostOnce(params: Map<String, String>): GoformWriteResult {
        val snapshot = ensureSession()
        if (snapshot == null) {
            AppLogger.e(tag, "goformPost aborted: not logged in")
            return GoformWriteResult.SessionLost
        }
        return GoformQoS.withSetPermit {
            // AD 由 wa_inner_version / cr_version / RD 三次前置查询计算得出。会话失效时这三次
            // 查询返回登录页，无法解析字段，computeAd 返回 null。因此「AD 无法计算」是会话失效
            // 最常见的外观，必须归入可重试一侧。
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

    override suspend fun read(commands: List<String>): JsonObject? {
        if (!ensureLogin()) return null
        return queryInternal(commands, retry = true)
    }

    /**
     * 重试必须在许可释放之后发起。
     *
     * 若在 `withQueryPermit { … }` 块内递归调用自身，等于持有一个许可再申请第二个。
     * `GoformQoS.querySemaphore` 的许可数会被自适应调节压到 2（DataScheduler 温度临界）甚至
     * 1（`ConfigRoutes` 调用 adaptiveAdjust，许可下限为 1），而 kotlinx 的
     * `Semaphore.acquire()` 没有超时：许可为 1 时任意一次重试即永久挂起，许可为 2 时两次并发
     * 重试即挂起，整条 goform 读路径不再恢复。
     */
    private suspend fun queryInternal(commands: List<String>, retry: Boolean): JsonObject? {
        // 熔断 → 整体预算 → 独立调度器，三者缺一不可。缺少熔断时，设备异常期间每个请求都要
        // 等待超时；缺少预算时，单次读的耗时无法约束；缺少独立调度器时，goform 会与文件操作
        // 争用同一 IO 池（无存储卡启动时文件管理不可用即由此产生）。
        if (GoformGuard.isOpen()) {
            GoformGuard.recordFastFail()
            return null
        }
        val startedAt = System.currentTimeMillis()
        val result = try {
            withContext(GoformGuard.dispatcher) {
                withTimeoutOrNull(GoformGuard.READ_BUDGET_MS) {
                    queryInternalLocked(commands, retry)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 读路径将传输异常统一收为 null（与「读失败返回 null」同一契约），路由层因此走既有
            // 的 503/空值分支，不会因设备侧异常产生未处理异常。
            AppLogger.w(tag, "[goform_get] transport error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (result == null) {
            if (System.currentTimeMillis() - startedAt >= GoformGuard.READ_BUDGET_MS) {
                GoformGuard.recordTimeout()
            }
            if (GoformGuard.recordFailure() >= GoformGuard.FAILURE_THRESHOLD) {
                // 连续失败到阈值：连地址也可能变了（换网 / DHCP），让下次重新解析 base url。
                baseUrlResolved = false
            }
        } else {
            GoformGuard.recordSuccess()
        }
        return result
    }

    /** [queryInternal] 的实际逻辑；熔断、整体预算、独立调度器都在外层。 */
    private suspend fun queryInternalLocked(commands: List<String>, retry: Boolean): JsonObject? {
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
        // 缓存边界：本层是传输层缓存，key 为 cmd 名集合，value 为设备原始响应。字段归一化位于
        // 其下游（GoformFieldMapper / GoformXxxClient），上层业务缓存（ResponseCache / DataHub）
        // 保存的才是 canonical 数据。不得在本层修改字段名或字段值：否则 QoS 快照会同时影响所有
        // 调用方，且归一化会在缓存命中时被跳过，导致处理不一致。
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

        // 响应完整性校验。部分设备在负载过高时会返回截断或非完整的 JSON：请求多个字段而返回
        // 字段数过少（且非鉴权错误）时，按 partial 处理。
        //
        // 字段数按解析后的顶层 key 数量统计，不用 body 中 ':' 的出现次数估算：字符串值中的冒号
        // （URL、"12:00"、base64 短信正文）会使计数偏高。误判为 partial 会导致无谓的
        // invalidateSession() 强制重登；漏判则会把截断的数据当作权威值上传。
        val authFailure = isAuthFailure(rawBody)
        val obj = if (status == HttpStatusCode.OK && !authFailure) parseJson(rawBody) else null
        val isPartial = status == HttpStatusCode.OK && !authFailure &&
                commands.size > 5 && obj != null && obj.size < 2

        if (status != HttpStatusCode.OK || authFailure || isPartial) {
            if (isPartial) {
                // partial 的恢复策略：保留「不每次重登」的原则，同时提供自愈路径。
                // 部分固件对多 key 查询持续返回 200 + 空体（会话半失效），设备重启后恢复，说明
                // 重新登录可修复该状态；若每次 partial 均只返回 null，该状态不会自愈
                // （/api/device/traffic-limit 返回 503、信号 / WiFi / 邻区字段为空）。
                // 折衷方案：60s 内只允许一次 partial→失效会话→重登升级（CAS 认领，并发读不会
                // 重复升级），窗口内其余 partial 仍返回 null 并保留会话。onAuthLost 交由
                // queryInternal 走既有的「许可外重登 + 重试」路径。
                val now = System.currentTimeMillis()
                val last = lastPartialRecoveryAt.get()
                val failures = partialRecoveryFailures.get()
                val waitMs = partialRecoveryWaitMs(failures)
                if (now - last >= waitMs && lastPartialRecoveryAt.compareAndSet(last, now)) {
                    // last != 0 表示上一次升级重登之后再次出现 partial，即上次未恢复，计数 +1，
                    // 下一次等待按指数增长（60s→120s→240s…，上限 10min）。首次（last==0）不计失败。
                    if (last != 0L) partialRecoveryFailures.incrementAndGet()
                    AppLogger.w(tag, "[goform_get] Partial response ($cmdParam, keys=${obj?.size ?: 0})" +
                            " — self-heal: invalidate session + relogin (attempt=${failures + 1}," +
                            " next wait=${partialRecoveryWaitMs(failures + 1)}ms)")
                    invalidateSession()
                    onAuthLost()
                } else {
                    AppLogger.w(tag, "[goform_get] Partial response ($cmdParam, keys=${obj?.size ?: 0})" +
                            " — session kept (self-heal backoff ${waitMs}ms, ${now - last}ms elapsed)")
                }
                return null
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
        // 自愈状态复位：完整响应表示设备已恢复。清零失败计数并将退避复位到 60s 档，使下次
        // 异常时立即以最短间隔尝试恢复。
        if (partialRecoveryFailures.get() != 0 || lastPartialRecoveryAt.get() != 0L) {
            partialRecoveryFailures.set(0)
            lastPartialRecoveryAt.set(0L)
            AppLogger.i(tag, "[goform_get] full response restored — partial self-heal state reset")
        }
        GoformQoS.cacheQuery(key, obj)
        return obj
    }


    override suspend fun readOne(command: String): JsonElement? {
        if (!ensureLogin()) return null
        return querySingleInternal(command, retry = true)
    }

    /** 重试在许可之外发起，理由同 [queryInternal]。 */
    private suspend fun querySingleInternal(command: String, retry: Boolean): JsonElement? {
        // 与 queryInternal 使用同一套隔离（熔断 / 预算 / 独立调度器）。
        if (GoformGuard.isOpen()) {
            GoformGuard.recordFastFail()
            return null
        }
        val result = try {
            withContext(GoformGuard.dispatcher) {
                withTimeoutOrNull(GoformGuard.READ_BUDGET_MS) {
                    querySingleInternalLocked(command, retry)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(tag, "[goform_get_single] transport error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (result == null) {
            GoformGuard.recordFailure()
        } else {
            GoformGuard.recordSuccess()
        }
        return result
    }

    private suspend fun querySingleInternalLocked(command: String, retry: Boolean): JsonElement? {
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
            val resp = write(
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

    override fun isSuccess(body: String?): Boolean {
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
     * 仅按 `result` 字段精确比较；子串匹配仅用于非 JSON 的 HTML 登录页或明文错误串。
     * 不得对整个 body 做子串匹配：合法响应中出现 `session_timeout` 等字段即会被判为登录失败，
     * 而该判定的代价是 `consecutiveLoginFailures++`，退避为 1500 * 2^n，数次误判即可使整条
     * goform 路径停在退避上限。
     *
     * 未知 result 一律不判失败（保持既有宽松语义，不新增登录失败模式），但输出一条 WARN：
     * 遇到新固件的取值时可在日志中观察到后再收紧。
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

    override fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // 写侧字符集固定为 UTF-8；读侧（[decodeDeviceText]）必须先尝试 UTF-8 以保持对称，见
    // companion 中 [Companion.decodeDeviceText] 的注释。不得改为 GBK 以迁就读侧。
    internal fun base64Encode(input: String): String =
        java.util.Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))

    /**
     * 解 base64 并将字节按设备文本字符集还原为字符串。
     *
     * 失败语义（有意保留）：input 不是合法 base64 时输出一条 ERROR 并返回空串，不抛异常、
     * 不返回 null。`/api/wifi/settings` 的读路径（`GoformWifiClient.getCurrentWifiConfig`）
     * 依赖该行为。已知限制：空串会被上层当作「口令为空」而非「解码失败」
     * （`getCurrentWifiConfig` 的 KDoc 亦有记录）。修正需同时调整上层对「空值」与「失败」的
     * 区分，不能只改本方法。
     *
     * 本方法只负责错误上报；解码逻辑位于纯函数 [Companion.base64DecodeOrEmpty] /
     * [Companion.decodeDeviceText]，单测针对这两个函数。本类一构造即启动 Ktor client，
     * [AppLogger] 又依赖 android.util.Log，实例方法在 JVM 单测中不可用，因此测试对象是委托
     * 目标，而非为可测性改动生产类型。
     */
    override fun decodeDeviceText(input: String): String =
        base64DecodeOrEmpty(input) { e -> AppLogger.e(tag, "base64Decode failed", e) }

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

    // `NETWORK_TYPE_MAP` 与 `mapNetworkType()` 已移除。数字码到制式名的映射属于设备知识，仅
    // 存在于 `ZteGoformProfile.NETWORK_TYPE_MAP` / `NETWORK_TYPE_DECODER`，语义一致，其中
    // 「纯数字才查表、非数字原样返回」的规则必须保留。读侧的 `network_type` 在归一化时已是
    // 可读文案，调用方不需要再次翻译。

    companion object {
        /** 老固件假定的设备文本字符集，仅作为 UTF-8 不成立时的回落。 */
        private val GBK: Charset = Charset.forName("GBK")

        /**
         * 将设备返回的字节按「先 UTF-8，不合法才使用 GBK」还原为字符串。
         *
         * 写侧 [base64Encode] 使用 UTF-8，读侧必须与之对称：无条件按 GBK 解码时，ASCII 内容因
         * 两种字符集编码相同而看不出差异，但非 ASCII（中文）口令在「读回明文 → base64Encode
         * 写回」的流程中会被改坏，而该流程是 WiFi 口令与配置回写的常规路径。改为先试 UTF-8 后：
         *  - ASCII：两种字符集编码相同，行为不变；
         *  - 设备存 UTF-8：正确还原；
         *  - 设备存 GBK（老固件）：UTF-8 解码不合法 → 回落 GBK → 与原有行为一致。
         *
         * 判据是「能否无损往返」：UTF-8 解码后再编码，字节与原字节逐一相等，才认定该字节串为
         * UTF-8。不使用「解码结果是否含 U+FFFD 替换字符」作为判据：
         *  - 漏判：JDK 的 `String(bytes, UTF_8)` 对非法序列不抛异常，只替换为 U+FFFD，因此
         *    「是否抛异常」不构成判据；
         *  - 误判：设备确实存储 U+FFFD（EF BF BD）时，该字节序列是合法 UTF-8，按「含替换字符
         *    即失败」会被错误地回落 GBK。
         * 往返判据同时覆盖两种情况：被替换过的字节编码后必然变为 EF BF BD，与原字节不等；
         * 真实的 U+FFFD 则往返相等。
         *
         * 已知权衡：GBK 双字节序列偶尔也构成合法 UTF-8，此类输入会被判为 UTF-8 而解码错误。
         * 接受该权衡的原因是写侧固定使用 UTF-8：保证「读回再写回」无损优先于兼容尚未在设备上
         * 观测到的边缘固件。反之（优先 GBK）会使每个 UTF-8 口令的回写都损坏。
         */
        internal fun decodeDeviceText(bytes: ByteArray): String {
            val asUtf8 = String(bytes, Charsets.UTF_8)
            if (asUtf8.toByteArray(Charsets.UTF_8).contentEquals(bytes)) return asUtf8
            return String(bytes, GBK)
        }

        /**
         * 实例方法 `decodeDeviceText(String)` 的纯函数体：合法 base64 → [decodeDeviceText] 的
         * 结果；非法 base64 → 调用一次 [onError] 后返回空串。
         *
         * 错误上报通过参数传入，使该逻辑可在 JVM 单测中执行：实例方法传入 `AppLogger.e`
         * （依赖 android.util.Log），单测传入默认的空实现。这样测试覆盖的是生产路径本身，无需
         * 构造 [GoformClient]（构造即启动 Ktor client）。
         */
        internal inline fun base64DecodeOrEmpty(
            input: String,
            onError: (Exception) -> Unit = {}
        ): String =
            try {
                decodeDeviceText(java.util.Base64.getDecoder().decode(input))
            } catch (e: Exception) {
                onError(e)
                ""
            }
    }
}

