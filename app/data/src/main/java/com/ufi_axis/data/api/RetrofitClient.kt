package com.ufi_axis.data.api

import com.ufi_axis.data.api.AppJsonConverterFactory
import com.ufi_axis.util.ApiErrorLogger
import com.ufi_axis.util.AppJson
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import com.ufi_axis.util.DeviceKeyStore
import com.ufi_axis.util.OkHttpClientProvider
import okhttp3.Interceptor
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
     * 收到 401（业务 /api/ 凭据失效）时回调，由 MainActivity 用于触发“重新配对”引导（边缘情况 #1/#3）。
     * 注意：配对端点走 HttpURLConnection，不经此 OkHttp 拦截器，不会误触发。
     */
    @Volatile
    var onUnauthorized: (() -> Unit)? = null
    private var unauthorizedNotified = false

    fun getApiService(prefs: AppPreferences): UfiAxisApi {
        if (apiService == null) {
            synchronized(this) {
                if (apiService == null) {
                    apiService = createApiService(prefs)
                    unauthorizedNotified = false
                }
            }
        }
        return apiService!!
    }

    fun recreate(prefs: AppPreferences): UfiAxisApi {
        synchronized(this) {
            apiService = createApiService(prefs)
            unauthorizedNotified = false
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
     * - 仅对业务 /api/ 路径的 401/444 视为凭据失效并触发一次 [onUnauthorized]（重配对）。
     *
     * `URI` 取 `encodedPath + ?encodedQuery`，与服务端 `call.request.uri` 逐字节对齐：
     * 用 encoded 形式而不是解码后的值，是因为百分号编码的大小写/保留字符处理在两端不可能
     * 保证一致，一旦不一致就是 100% 验签失败。
     */
    fun authInterceptor(prefsProvider: () -> AppPreferences): Interceptor = Interceptor { chain ->
        val prefs = prefsProvider()
        val original = chain.request()
        val builder = original.newBuilder()
            .header("Authorization", "Bearer ${prefs.token}")
        signHeaders(original.method, original.url.encodedPath, original.url.encodedQuery)
            ?.forEach { (name, value) -> builder.header(name, value) }
        if (original.body?.contentType() == null && original.header("Content-Type") == null) {
            builder.header("Content-Type", "application/json")
        }
        val request = builder.build()
        val response = chain.proceed(request)
        // 2026-08-25：兼容后端 444 (No Token) 状态码。
        if ((response.code == 401 || response.code == 444) &&
            response.request.url.encodedPath.startsWith("/api/") &&
            !unauthorizedNotified
        ) {
            unauthorizedNotified = true
            onUnauthorized?.invoke()
        }
        response
    }

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
     *      响应原样返回（Retrofit 仍按非 2xx 自行判定，401 仍会触发 [onUnauthorized]）。
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
