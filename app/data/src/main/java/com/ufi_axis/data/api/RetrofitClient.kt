package com.ufi_axis.data.api

import com.ufi_axis.data.api.AppJsonConverterFactory
import com.ufi_axis.util.ApiErrorLogger
import com.ufi_axis.util.AppJson
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import com.ufi_axis.util.DeviceKeyStore
import com.ufi_axis.util.OkHttpClientProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.Invocation
import retrofit2.http.Streaming
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Coroutine-safe retry wrapper for suspend API calls.
 * Uses non-blocking [delay] instead of [Thread.sleep], safe for any dispatcher.
 *
 * Usage:
 *   val result = retryIO { api.getDeviceInfo() }
 */
suspend fun <T> retryIO(times: Int = 2, initialDelay: Long = 200, block: suspend () -> T): T {
    repeat(times - 1) { attempt ->
        try { return block() } catch (_: IOException) {
            delay(initialDelay * (1L shl attempt))  // exponential backoff: 200, 400
        }
    }
    return block()
}

object RetrofitClient {

    @Volatile
    private var apiService: UfiAxisApi? = null

    /**
     * 「凭据**确证**失效」回调，由 MainActivity/ConnectionBootstrap 用于清 token 并回配对引导。
     *
     * 触发条件全部集中在 [AuthRejectionPolicy]（连击次数 / 时间跨度 / 传输失败豁免都在那里），
     * 这里只是一个出口 —— 不要在调用侧再加第二套阈值。
     * 注意：配对端点走 HttpURLConnection，不经此 OkHttp 拦截器，不会误触发。
     */
    @Volatile
    var onUnauthorized: (() -> Unit)? = null

    fun getApiService(prefs: AppPreferences): UfiAxisApi {
        if (apiService == null) {
            synchronized(this) {
                if (apiService == null) {
                    apiService = createApiService(prefs)
                    AuthRejectionPolicy.reset()
                }
            }
        }
        return apiService!!
    }

    fun recreate(prefs: AppPreferences): UfiAxisApi {
        synchronized(this) {
            apiService = createApiService(prefs)
            AuthRejectionPolicy.reset()
            return apiService!!
        }
    }

    /**
     * 用**显式** baseUrl / token 构建一个独立实例（不写入单例）。
     *
     * 专供 `:ufi_notify` 进程使用：`SharedPreferences` 是 `MODE_PRIVATE`，主进程重新配对写入的
     * 新 token 在该进程读不到（无跨进程 reload），继续用旧 token 会一直 401/444 → 通知静默失效。
     * 因此由主进程把最新参数经 Intent 传过来，这里显式构建。
     */
    @Suppress("DEPRECATION")
    fun createStandaloneApiService(baseUrl: String, token: String): UfiAxisApi {
        val client = OkHttpClientProvider.shared.newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder().header("Authorization", "Bearer $token")
                // 通知守护进程与主进程共享同一 Keystore 条目（同一 UID），签名同样有效
                signHeaders(original.method, original.url.encodedPath, original.url.encodedQuery)
                    ?.forEach { (name, value) -> builder.header(name, value) }
                if (original.body?.contentType() == null && original.header("Content-Type") == null) {
                    builder.header("Content-Type", "application/json")
                }
                chain.proceed(builder.build())
            }
            .addInterceptor(errorCaptureInterceptor(baseUrl))
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(AppJsonConverterFactory.create(AppJson))
            .build()
            .create(UfiAxisApi::class.java)
    }

    /**
     * 统一的 Bearer + 设备签名拦截器，被 Retrofit 与 [com.ufi_axis.util.AppHttpClient] 共用。
     *
     * - 注入 `Authorization: Bearer ${token}`（惰性读取最新 token）；
     * - 注入 `X-Timestamp` / `X-Nonce` / `X-Signature`：用 [DeviceKeyStore] 里**不可导出**的
     *   设备私钥对 `METHOD\nURI\nTS\nNONCE` 签名。core 的 `AuthMiddleware` 强制校验这三个头，
     *   缺一即 444——**这不是可选项**（历史上签名分支写成"客户端不发就跳过"，等于形同虚设）；
     * - 仅在请求未自带 Content-Type 时补 `application/json`，以保护 multipart 上传原有的 Content-Type。
     * - 业务 /api/ 路径上的鉴权拒绝交由 [AuthRejectionPolicy] 定性：瞬时问题原地重签重试，
     *   只有确证吊销且满足全部阈值才触发一次 [onUnauthorized]。
     *
     * `URI` 取 `encodedPath + ?encodedQuery`，与服务端 `call.request.uri` 逐字节对齐：
     * 用 encoded 形式而不是解码后的值，是因为百分号编码的大小写/保留字符处理在两端不可能
     * 保证一致，一旦不一致就是 100% 验签失败。
     */
    fun authInterceptor(prefsProvider: () -> AppPreferences): Interceptor =
        Interceptor { chain -> proceedWithAuth(chain, prefsProvider()) }

    /**
     * 发请求 + 按 [AuthRejectionPolicy] 处置鉴权结果。
     *
     * 重试在拦截器内做（而不是交给调用方的 [retryIO]）：`retryIO` 只认 [IOException]，
     * 而"时间戳过期 / 签名不被接受"是**带响应体的 401**，走不到那条路；而且重试必须**重新签名**，
     * 只有在这一层才能拿到原始请求重签。此处 [Thread.sleep] 跑在 OkHttp 自己的网络线程上
     * （Retrofit suspend 调用与 AppHttpClient 都不在主线程），不会卡 UI。
     */
    private fun proceedWithAuth(chain: Interceptor.Chain, prefs: AppPreferences): Response {
        val original = chain.request()
        // 只有业务 /api/ 才参与鉴权判定：goform 透传、配对端点等不带我们这套凭据，
        // 它们的 401/503 与"是否被吊销"无关。
        val businessApi = original.url.encodedPath.startsWith("/api/")
        var attempt = 0
        while (true) {
            val response = try {
                chain.proceed(signRequest(original, prefs))
            } catch (e: IOException) {
                // 传输层失败（连接被拒 / 超时 / DNS / SSL）= core 不可达或正在重启。
                // 这**不是**被吊销，而且要给策略打上标记：紧跟其后的那次拒绝同样不算数。
                if (businessApi) AuthRejectionPolicy.onTransportFailure()
                throw e
            }
            if (!businessApi) return response

            val verdict = AuthRejectionPolicy.judge(response.code) { parseErrorCode(response) }
            if (verdict == AuthRejectionPolicy.Verdict.RETRY) {
                val backoff = AuthRejectionPolicy.retryDelayMs(attempt)
                if (backoff != null) {
                    // 重发前必须 close 掉这条响应，否则连接不回池 = 泄漏；
                    // signRequest 会重算 X-Timestamp / X-Nonce，这正是重试能自愈的原因。
                    response.close()
                    Thread.sleep(backoff)
                    attempt++
                    continue
                }
            }
            if (verdict == AuthRejectionPolicy.Verdict.WIPE) onUnauthorized?.invoke()
            return response
        }
    }

    /** 给请求打上 Bearer + 设备签名 + 默认 Content-Type；每次调用都重算时间戳/nonce。 */
    private fun signRequest(original: Request, prefs: AppPreferences): Request {
        val builder = original.newBuilder()
            .header("Authorization", "Bearer ${prefs.token}")
        signHeaders(original.method, original.url.encodedPath, original.url.encodedQuery)
            ?.forEach { (name, value) -> builder.header(name, value) }
        if (original.body?.contentType() == null && original.header("Content-Type") == null) {
            builder.header("Content-Type", "application/json")
        }
        return builder.build()
    }

    /**
     * 取错误响应体里 core 给的 `code`（形如 `{"error":"...","code":"STALE_TIMESTAMP"}`）。
     *
     * 一律 [Response.peekBody]：`body.string()` 会把响应体消费掉，下游 Retrofit 再读就是空的
     * （[errorCaptureInterceptor] 当年踩过同一个坑）。空体 / 非 JSON / 不是对象 / code 不是字符串
     * 全部当作"没有 code"处理 —— 定性拿不准时由策略侧走保守分支，绝不因为解析失败去清凭据。
     */
    private fun parseErrorCode(response: Response): String? = runCatching {
        val raw = response.peekBody(AuthRejectionPolicy.ERROR_BODY_PEEK_BYTES).string()
        val obj = AppJson.parseToJsonElement(raw) as? JsonObject ?: return@runCatching null
        (obj["code"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    /**
     * 生成设备签名三件套；Keystore 不可用时返回 null（请求照发，由服务端回 444，
     * 让"重新配对"流程接管——在这里静默失败比抛异常炸掉整个请求链更可控）。
     */
    private fun signHeaders(method: String, encodedPath: String, encodedQuery: String?): Map<String, String>? {
        val uri = if (encodedQuery.isNullOrEmpty()) encodedPath else "$encodedPath?$encodedQuery"
        val timestamp = System.currentTimeMillis().toString()
        val nonce = DeviceKeyStore.newNonce()
        val signature = DeviceKeyStore.sign(
            DeviceKeyStore.canonicalString(method, uri, timestamp, nonce)
        ) ?: return null
        return mapOf(
            "X-Timestamp" to timestamp,
            "X-Nonce" to nonce,
            "X-Signature" to signature
        )
    }

    /**
     * 通信报错捕获拦截器：统一把「连接/协议/HTTP 错误」落到 [ApiErrorLogger]。
     *
     * - `chain.proceed` 抛异常（连接拒绝 / 超时 / DNS / SSL 等）→ 记录 [ApiErrorLogger.Stage.HTTP_REQUEST]；
     * - 非 2xx（>=400）→ 记录并附 `response.peekBody(2048)` 响应体摘要（401/403 标记为 [ApiErrorLogger.Stage.AUTH]）。
     *
     * 注意：
     *   1. 仅记录、绝不吞掉异常或改写响应——异常照常向上抛（[retryIO] / 调用方行为不变），
     *      响应原样返回（Retrofit 仍按非 2xx 自行判定；鉴权处置由 [AuthRejectionPolicy] 定性）。
     *   2. 使用 `peekBody` 而非 `body.string()`，避免消费响应体导致下游解析失败。
     *   3. 调用发生在 OkHttp 网络线程（IO），与需求「在调用方 IO 协程写」一致。
     */
    fun errorCaptureInterceptor(baseUrl: String): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val method = request.method
        val url = request.url.toString()
        val authPresent = request.header("Authorization") != null
        val requestLine = buildString {
            append("$method $url")
            if (authPresent) append(" Authorization: Bearer ***")
        }
        try {
            val response = chain.proceed(request)
            if (response.code >= 400) {
                val body = runCatching { response.peekBody(2048).string() }.getOrNull()
                val stage = if (response.code == 401 || response.code == 403) {
                    ApiErrorLogger.Stage.AUTH
                } else {
                    ApiErrorLogger.Stage.HTTP_REQUEST
                }
                ApiErrorLogger.logHttp(
                    stage = stage,
                    baseUrl = baseUrl,
                    method = method,
                    url = url,
                    errorType = "HTTP_${response.code}",
                    exception = null,
                    httpStatus = response.code,
                    responseBody = body,
                    requestLine = requestLine
                )
            }
            response
        } catch (e: Exception) {
            ApiErrorLogger.logHttp(
                stage = ApiErrorLogger.Stage.HTTP_REQUEST,
                baseUrl = baseUrl,
                method = method,
                url = url,
                errorType = classifyOkHttpError(e),
                exception = e,
                requestLine = requestLine
            )
            throw e
        }
    }

    /** 将 OkHttp 层异常归类为人可读的错误类型（用于日志 `ErrorType` 字段）。 */
    private fun classifyOkHttpError(t: Throwable): String = when (t) {
        is java.net.ConnectException -> "CONNECT_FAILED"
        is java.net.SocketTimeoutException -> "TIMEOUT"
        is java.net.UnknownHostException -> "UNKNOWN_HOST"
        is javax.net.ssl.SSLException -> "SSL_ERROR"
        is java.io.IOException -> "IO_ERROR"
        else -> t.javaClass.simpleName.uppercase(java.util.Locale.US)
    }

    @Suppress("DEPRECATION")
    private fun createApiService(prefs: AppPreferences): UfiAxisApi {
        // 复用 AppPreferences.baseUrl：serverIp 为空时回退设备网关（默认 192.168.0.1），
        // 与前端界面/WebSocket 保持一致，避免 App 默认连到自己 localhost 导致通信失败。
        val baseUrl = prefs.baseUrl

        // 复用共享单例 OkHttpClient（保留长超时 + 网络日志），叠加业务拦截器 auth / error-capture
        val client = OkHttpClientProvider.shared.newBuilder()
            .addInterceptor(authInterceptor { prefs })
            .addInterceptor(errorCaptureInterceptor(baseUrl))
            .build()

        // T9 收尾：kotlinx.serialization 独占。
        // AppJson 配置 isLenient，处理 goform 透传的字符串数字（如 "rsrp":"99"）。
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(AppJsonConverterFactory.create(AppJson))
            .build()
            .create(UfiAxisApi::class.java)
    }
}

/**
 * 鉴权拒绝的**唯一**处置策略（阈值、code 分类、豁免规则全在这里，别在调用侧再加第二套）。
 *
 * 2026-09-08 事故：core 重启，app 被一次性踢下线，用户被迫重新输密码。
 * 旧实现是「业务 /api/ 上任意一次 401/444 → 立刻清 token + isSetupComplete=false」：
 * 没有重试、没有退避、没有连续失败阈值，也不看 core 在响应体里给的 `code` ——
 * 于是 core 启动窗口里的**一次**瞬时拒绝就被当成"凭据被永久吊销"。
 *
 * 现在按 core 的契约定性（**判定在 core，app 只执行**，不在 app 里发明新的判据）：
 *   - `444`                                        = 确证吊销（token 不认识 / 设备密钥不在），才有资格进入清凭据流程；
 *   - `401` + `code` ∈ [RETRYABLE_AUTH_CODES]      = 时间戳/签名问题（时钟漂移、nonce 撞车）→ 重签重试，绝不清凭据；
 *   - `503` + `code` ∈ [AUTH_STORE_DEGRADED_CODES] = core 的配对库暂时降级 → 重试，绝不清凭据。
 * `401` 带其它 code 或没有 code 时按**说不清**处理：只记日志，不清凭据、也不计数 ——
 * 新契约里"确证吊销"只由 444 表达，凭一个语义不明的 401 抹掉配对关系是这次事故的根因；
 * 真的需要换身份有设置页的「重新配对 / 切换设备」那条显式入口。
 *
 * 即便定性为确证吊销，也要同时满足三个条件才通知上层清凭据：
 *   1. 连续 [REVOCATION_STREAK] 次 —— 中间**任何一次**已鉴权成功响应（<400）都清零；
 *   2. 这串拒绝横跨至少 [REVOCATION_MIN_SPAN_MS] —— 把 core 启动窗口里几百毫秒的密集抖动排除掉；
 *   3. 上一次请求不是传输层失败 —— 那说明 core 不可达/正在重启，不是我们被吊销。
 *
 * 「只通知一次」的语义保留（[notified]），但**跟着计数一起清零**：旧实现置位后永不复位，
 * 会话后段真的被吊销就再也提示不出来了。
 *
 * **不碰 Keystore**：这条路只清 token + 配对标记，设备私钥留着（重新配对仍是同一台设备、
 * 复用原槽位）。删密钥只有 `MainActivity.onRepairRequested` 一条显式入口。
 */
private object AuthRejectionPolicy {

    /** 一次鉴权响应的处置结论。 */
    enum class Verdict {
        /** 什么都不做：正常响应 / 与鉴权无关的错误 / 还没到阈值 */
        NONE,
        /** 瞬时鉴权问题：换一份新签名重发，绝不清凭据 */
        RETRY,
        /** 确证吊销且已满足全部阈值：通知上层清凭据并回配对引导 */
        WIPE
    }

    /** 错误体只 peek 这么多字节：`{"error":..,"code":..}` 远小于此，够了。 */
    const val ERROR_BODY_PEEK_BYTES = 1024L

    private const val REVOCATION_STREAK = 3
    private const val REVOCATION_MIN_SPAN_MS = 10_000L
    private const val TRANSIENT_RETRY_MAX = 2
    private const val TRANSIENT_RETRY_BACKOFF_MS = 300L

    private val RETRYABLE_AUTH_CODES = setOf("STALE_TIMESTAMP", "INVALID_SIGNATURE")
    private val AUTH_STORE_DEGRADED_CODES = setOf("AUTH_STORE_UNAVAILABLE")

    private val lock = Any()
    private var streak = 0
    private var streakStartedAt = 0L
    private var afterTransportFailure = false
    private var notified = false

    /** 第 [attempt] 次重试前该等多久（指数退避 300 / 600ms）；null = 不再重试。 */
    fun retryDelayMs(attempt: Int): Long? =
        if (attempt < TRANSIENT_RETRY_MAX) TRANSIENT_RETRY_BACKOFF_MS shl attempt else null

    /** 凭据 / 客户端重建后从零开始（含"只通知一次"标记）。 */
    fun reset() = synchronized(lock) { clearStreak() }

    /** 传输层失败：连击清零，并记下"下一次拒绝紧跟在一次不可达之后"。 */
    fun onTransportFailure() = synchronized(lock) {
        clearStreak()
        afterTransportFailure = true
    }

    /**
     * 定性一次业务 /api/ 响应并推进内部计数。
     *
     * [errorCode] 是**惰性**的：只有 401/503 这两类需要看 code 时才会去 peek 响应体，
     * 正常响应不付解析代价。
     */
    fun judge(httpStatus: Int, errorCode: () -> String?): Verdict = synchronized(lock) {
        when {
            // 任何一次成功的已鉴权响应都证明凭据有效 → 连击与"已通知"标记一起清零。
            httpStatus < 400 -> {
                clearStreak()
                Verdict.NONE
            }
            httpStatus == 444 -> countRevocation()
            httpStatus == 401 -> {
                val code = errorCode()
                if (code != null && code in RETRYABLE_AUTH_CODES) {
                    DebugLog.w("Auth", "401 $code：瞬时签名/时钟问题，重签重试，不动本地凭据")
                    clearStreak()
                    Verdict.RETRY
                } else {
                    DebugLog.w("Auth", "401 code=${code ?: "缺省"}：未确证吊销，保留本地凭据")
                    Verdict.NONE
                }
            }
            httpStatus == 503 -> {
                val code = errorCode()
                if (code != null && code in AUTH_STORE_DEGRADED_CODES) {
                    DebugLog.w("Auth", "503 $code：core 配对库降级，重试，不动本地凭据")
                    clearStreak()
                    Verdict.RETRY
                } else {
                    Verdict.NONE
                }
            }
            else -> Verdict.NONE
        }
    }

    /** 记一次确证吊销，并判断是否已满足全部清凭据条件。调用方已持 [lock]。 */
    private fun countRevocation(): Verdict {
        val now = System.currentTimeMillis()
        if (afterTransportFailure) {
            // 上一次请求连都没连上：core 刚回来第一句"不认识你"极可能是它自己还没装载完
            // 配对库（正是这次事故的现场）。连击从头数，这一次一定不清凭据。
            afterTransportFailure = false
            streak = 1
            streakStartedAt = now
            DebugLog.w("Auth", "444 紧跟在一次连接失败之后，按 core 重启处理，连击重新计数")
            return Verdict.NONE
        }
        if (streak == 0) streakStartedAt = now
        streak++
        val span = now - streakStartedAt
        if (streak < REVOCATION_STREAK || span < REVOCATION_MIN_SPAN_MS || notified) {
            DebugLog.w("Auth", "444 第 $streak 次（跨度 ${span}ms）未达阈值，保留本地凭据")
            return Verdict.NONE
        }
        notified = true
        DebugLog.e("Auth", "444 连续 $streak 次、跨度 ${span}ms → 判定凭据被吊销，清凭据回配对引导")
        return Verdict.WIPE
    }

    private fun clearStreak() {
        streak = 0
        streakStartedAt = 0L
        afterTransportFailure = false
        notified = false
    }
}
