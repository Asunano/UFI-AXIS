package com.ufi_axis.util

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okio.Buffer
import retrofit2.Invocation
import retrofit2.http.Streaming
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * 网络日志拦截器（2026-08-27 由 `SafeHttpLoggingInterceptor` 重构而来）。
 *
 * 重构动因：
 * - 旧版只在 DEBUG 构建安装，且输出走 OkHttp 的 `HttpLoggingInterceptor.Logger.DEFAULT`
 *   直接进 Logcat —— App 内的日志页看不到一条网络日志。现在改为写 [DebugLog.net]，
 *   进 [AppLogBuffer]，日志页「网络」筛选即可查看，release 构建同样安装。
 * - 旧版逐行打请求行 / 每个 header / 正文，一次请求就能刷掉十几条；500 条环形缓冲会被
 *   瞬间冲掉。现在**一次请求只产出一条**日志，正文与关键 header 折进同一条的后续行。
 *
 * 保留旧版的 OOM 防护（这些是为真实崩溃写的，见 crash_2026-07-24_11-09-34.log：
 * `HttpLoggingInterceptor(BODY)` 会把 `/api/speedtest` 的 ~100MB `octet-stream` 整体缓冲）：
 * - 只有「文本类型 + 长度 ≤ [maxBodyBytes]」的正文才读取；
 * - `@Streaming` 方法的响应体一律跳过；
 * - 用 `peekBody` 不消费原始响应体。
 *
 * 级别：5xx / IO 异常 → ERROR，4xx → WARN，其余 → INFO。因此**关掉调试日志时仍会记录失败请求**
 * （[DebugLog.net] 对 WARN 以上无条件记录），这正是排查「服务连不上」最需要的那部分。
 */
class NetworkLogInterceptor(
    private val maxBodyBytes: Long = 64 * 1024
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // app 侧日志关闭（总开关或 app 子开关）→ 完全旁通：不拼 URL、不计时、不 peek 正文
        if (!DebugLog.active) return chain.proceed(request)

        val target = "${request.method} ${request.url.host}${request.url.encodedPath}" +
            (request.url.encodedQuery?.let { "?$it" } ?: "")
        val startMs = System.currentTimeMillis()

        val response: Response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            val cost = System.currentTimeMillis() - startMs
            DebugLog.net(
                LogLevel.ERROR,
                "HTTP",
                "$target → FAILED ${cost}ms ${e.javaClass.simpleName}: ${e.message}"
            )
            throw e
        }

        val cost = System.currentTimeMillis() - startMs
        val level = when {
            response.code >= 500 -> LogLevel.ERROR
            response.code >= 400 -> LogLevel.WARN
            else -> LogLevel.INFO
        }
        // 详细日志关闭时只记失败：提前返回，省掉下面的正文 peek 开销
        if (level == LogLevel.INFO && !DebugLog.verbose) return response

        val sb = StringBuilder()
        sb.append(target).append(" → ").append(response.code)
        if (response.message.isNotBlank()) sb.append(' ').append(response.message)
        sb.append(' ').append(cost).append("ms")
        bodySize(response)?.let { sb.append(' ').append(it) }

        if (DebugLog.verbose) {
            requestBody(request.body)?.let { sb.append("\n--> ").append(it) }
            responseBody(response)?.let { sb.append("\n<-- ").append(it) }
        }

        DebugLog.net(level, "HTTP", sb.toString())
        return response
    }

    private fun bodySize(response: Response): String? {
        val len = response.body?.contentLength() ?: -1L
        return if (len >= 0) FormatUtils.formatSize(len) else null
    }

    private fun requestBody(body: okhttp3.RequestBody?): String? {
        if (body == null) return null
        val ct = body.contentType()
        val len = body.contentLength()
        // one-shot / duplex 的 body 只能写一次：读来打日志会把真正的请求内容吃掉
        // （测速上行的 body 就是 isOneShot()=true）。这类一律只打摘要。
        if (body.isOneShot() || body.isDuplex() || !isLoggableText(ct, len)) {
            return "body omitted: ${ct ?: "unknown"}${if (len >= 0) ", ${FormatUtils.formatSize(len)}" else ""}"
        }
        return runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            val charset = ct?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
            buffer.readString(charset).take(maxBodyBytes.toInt())
        }.getOrElse { "body log failed: ${it.message}" }
    }

    private fun responseBody(response: Response): String? {
        val body = response.body ?: return null
        val ct = body.contentType()
        val len = body.contentLength()
        val isStreaming = response.request.tag(Invocation::class.java)
            ?.method()?.isAnnotationPresent(Streaming::class.java) == true
        if (isStreaming) return "body omitted: streaming"
        if (!isLoggableText(ct, len)) {
            return "body omitted: ${ct ?: "unknown"}${if (len >= 0) ", ${FormatUtils.formatSize(len)}" else ""}"
        }
        return runCatching {
            // peekBody 不消费原始 body（OkHttp 设计如此），调用方仍可正常流式读取
            val str = response.peekBody(maxBodyBytes).string()
            if (len > maxBodyBytes) "$str\n... (truncated, total ${FormatUtils.formatSize(len)})" else str
        }.getOrElse { "body log failed: ${it.message}" }
    }

    /** 只有文本类且体积可控才读正文 —— 这道判断就是 OOM 防线，不要放宽。 */
    private fun isLoggableText(ct: MediaType?, len: Long): Boolean {
        if (ct == null) return false
        val sub = ct.subtype.lowercase(Locale.US)
        val isText = ct.type.lowercase(Locale.US) == "text" ||
            sub.contains("json") ||
            sub.contains("xml") ||
            sub.contains("html") ||
            sub.contains("javascript") ||
            sub.contains("x-www-form-urlencoded") ||
            sub == "x-ndjson"
        return isText && (len < 0 || len <= maxBodyBytes)
    }

    // 2026-08-31：私有 formatBytes 已删除 —— 统一到 [FormatUtils.formatSize]
    //（GB 段由两位小数改为一位，另获得 TB/PB 兜底；仅影响调试日志文本）。
}
