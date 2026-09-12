package com.ufi_axis_core.controller.notify

import com.ufi_axis_core.util.AppLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction


/**
 * HTTP 类通知渠道**共用**的薄发送封装。
 *
 * 只做一件事：按给定的 method / headers / body 发一次请求，回来一个状态码 + 截断后的响应体。
 * **不判可重试、不渲染模板、不读配置** —— 那些是渠道的事（见 [WebhookDelivery]）。
 * 阶段 4 的预设渠道（Bark / ntfy / 企业微信…）复用本类，不再各写一个 client。
 *
 * ## 为什么不复用 `GoformClient` 的那个 HttpClient
 *
 * 它的 base url 绑死在内网设备上、每个请求强制带 goform 的 Referer / Origin / Cookie，
 * 还要先过 `GoformQoS` 的许可闸。往**用户填的公网 URL** 发一条通知时，这三件事
 * 分别意味着：地址不对、把设备 session 泄漏给第三方、通知被设备侧限流拖住。
 *
 * ## 生命周期
 *
 * 一个实例持一个 [HttpClient]（连接池随之复用），**随渠道生命周期**，停服时由
 * `BackendService.stopAllComponents` 调 [close]。每次投递新建 client 会在 UFI 这种
 * 弱网设备上把 TLS 握手成本乘以通知条数。
 */
internal class HttpNotifier {

    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            // 超时按请求逐条给（不同渠道配的 timeoutMs 不同），这里只装插件。
            install(HttpTimeout)
            // **不跟随重定向**：一是"跟到哪儿去了"对用户不可见，二是 Location 可以指向
            // 非 http(s) 的 scheme。3xx 直接当失败并在错误里说清楚，让用户把 URL 改成最终地址 ——
            // 比悄悄把通知投到别处安全，也比自己写一套 scheme 白名单重定向器简单。
            followRedirects = false
            // 4xx/5xx 不抛异常：状态码本身就是我们要的判据（见 WebhookDelivery.classify）。
            expectSuccess = false
        }
    }

    /**
     * 一次请求的结果。
     *
     * 网络层失败（超时 / 连不上 / DNS / TLS）不在这里表示 —— 那些**原样抛出**，
     * 由渠道用 [WebhookDelivery.isRetryableException] 分类。异常类型是唯一的判据来源，
     * 在这里提前压成字符串会把它丢掉。
     */
    data class Result(
        val code: Int,
        /** 响应体前 [BODY_SUMMARY_CHARS] 字（用于 `/test` 端点回显与失败原因）。 */
        val bodySummary: String
    )

    /**
     * 发一次请求。**不重试**（重试循环在 `NotificationDispatcher`）。
     *
     * @param method 只允许 [ALLOWED_METHODS] 里的三个；GET 不带 body。
     * @param headers 头部值里的折行字符已由调用方清掉（见 [WebhookDelivery.renderHeaders]）。
     * @throws IllegalArgumentException url scheme 不是 http/https，或 method 不认识。
     */
    suspend fun send(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String,
        contentType: String,
        timeoutMs: Long
    ): Result {
        require(isAllowedUrl(url)) { "只允许 http/https 的 URL" }
        val verb = ALLOWED_METHODS[method.uppercase()]
            ?: throw IllegalArgumentException("不支持的 method: $method")

        val response = client.request(url) {
            this.method = verb
            timeout {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
            headers.forEach { (k, v) -> header(k, v) }
            // GET 不带 body：有些服务端见到 GET + body 直接 400，而"用 GET 触发通知"的
            // 用法本来就是把参数写在 URL 里（Server酱的旧接口就是这样）。
            if (verb != HttpMethod.Get && body.isNotEmpty()) {
                contentType(parseContentTypeOrJson(contentType))
                setBody(body)
            }
        }
        return Result(response.status.value, readBodySummary(response))
    }

    /**
     * 只读响应体的前 [BODY_READ_LIMIT_BYTES] 字节。
     *
     * `bodyAsText()` 会把整个响应读进内存 —— 通知目标是用户填的任意 URL，
     * 指向一个几百 MB 的文件也完全可能，而我们只需要几行错误信息用来排错。
     */
    private suspend fun readBodySummary(response: HttpResponse): String {
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(BODY_READ_LIMIT_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            val n = channel.readAvailable(buffer, filled, buffer.size - filled)
            if (n <= 0) break
            filled += n
        }
        // 读够了就把剩下的丢掉，不等对端发完（否则大响应仍会拖住整个超时预算）。
        channel.cancel()
        return decodeUtf8(buffer, filled).trim().take(BODY_SUMMARY_CHARS)
    }

    /**
     * UTF-8 解码，**丢掉结尾那串不完整的字节序列**。
     *
     * `String(bytes, 0, len, UTF_8)` 在 8 KB 边界正好切在一个多字节字符中间时会补一个
     * U+FFFD 替换字符（`�`）—— 而这段摘要会进 `/test` 响应和 `mail_send_records.error`
     * （用户可导出）。一个凭空出现的乱码符号看起来像"响应体坏了"，
     * 而事实只是"我们只读了前 8 KB"。用 [CharsetDecoder] 把 IGNORE 策略显式定下来：
     * 残缺的尾部字节直接扔掉，前面的内容一个字都不动。
     */
    private fun decodeUtf8(buffer: ByteArray, length: Int): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.IGNORE)
            .onUnmappableCharacter(CodingErrorAction.IGNORE)
            .decode(ByteBuffer.wrap(buffer, 0, length))
            .toString()


    /** 解析用户填的 Content-Type，认不出来回落 JSON（比让整条投递崩掉好）。 */
    private fun parseContentTypeOrJson(raw: String): ContentType = try {
        ContentType.parse(raw)
    } catch (e: Exception) {
        AppLogger.w(TAG, "Content-Type 无法解析（按 application/json 处理）：$raw")
        ContentType.Application.Json
    }

    /** 停服收尾：关连接池。之后再调 [send] 会抛，但那时渠道注册表已经清空了。 */
    fun close() {
        try {
            client.close()
        } catch (e: Exception) {
            AppLogger.w(TAG, "HTTP 通知客户端关闭失败：${e.message}")
        }
    }

    companion object {
        private const val TAG = "HttpNotifier"

        /**
         * 允许的请求方法。
         *
         * 只有这三个：通知是"把一条消息交出去"，DELETE / PATCH 没有对应的目标服务，
         * 而放开任意 method 等于让配置项变成一个通用 HTTP 客户端。
         */
        private val ALLOWED_METHODS: Map<String, HttpMethod> = mapOf(
            "POST" to HttpMethod.Post,
            "GET" to HttpMethod.Get,
            "PUT" to HttpMethod.Put
        )

        /** 方法名白名单（给配置校验用，与 [ALLOWED_METHODS] 同一份真源）。 */
        val METHOD_NAMES: Set<String> = ALLOWED_METHODS.keys

        /**
         * 响应体最多读多少字节。8 KB 足够装下任何服务端的错误 JSON，
         * 又不至于让一个"URL 填错指到大文件"的配置把进程内存打爆。
         */
        private const val BODY_READ_LIMIT_BYTES = 8 * 1024

        /** 落进 `/test` 响应与失败原因的响应体长度（`mail_send_records.error` 上限 200 字）。 */
        const val BODY_SUMMARY_CHARS = 200

        /**
         * URL scheme 白名单判定。**全仓一份**（配置校验、`isConfigured()`、[send] 都用它）。
         *
         * 只允许 http / https：URL 是用户填的，放开 scheme 就等于允许
         * `file://` / `content://` 这类本机资源出现在一个"往外发通知"的配置项里。
         */
        fun isAllowedUrl(url: String): Boolean {
            val u = url.trim().lowercase()
            return u.startsWith("http://") || u.startsWith("https://")
        }
    }
}
