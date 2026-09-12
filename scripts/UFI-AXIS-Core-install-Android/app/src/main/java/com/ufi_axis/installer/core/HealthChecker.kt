package com.ufi_axis.installer.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * 服务健康检查：`GET http://<ip>:8088/health`。
 *
 * 判定标准与 bat 脚本一致：响应正文同时包含 `status` 和 `ok`。
 * 界面只展示成功/失败状态，不展开原始响应体。
 */
object HealthChecker {

    /** 单次请求超时（秒），对应 bat 的 `curl --max-time 8` */
    const val REQUEST_TIMEOUT_MS = 8_000

    /** 最大尝试次数，对应 bat 的 6 次 */
    const val MAX_ATTEMPTS = 6

    /** 每次重试间隔，对应 bat 的 5 秒 */
    const val RETRY_DELAY_MS = 5_000L

    data class Attempt(
        val index: Int,
        val total: Int,
        val ok: Boolean,
        val detail: String
    )

    /**
     * 执行一次健康检查。
     *
     * @return ok=true 表示服务已就绪
     */
    fun checkOnce(host: String, port: Int = AddressParser.DEFAULT_HEALTH_PORT): AttemptResult {
        val urlText = "http://$host:$port/health"
        var conn: HttpURLConnection? = null
        // 默认认定失败；只有确认 HTTP 2xx 且正文命中关键字才置为成功
        var httpCode = -1
        return try {
            val url = URI(urlText).toURL()
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = REQUEST_TIMEOUT_MS
                readTimeout = REQUEST_TIMEOUT_MS
                instanceFollowRedirects = true
                useCaches = false
            }
            httpCode = conn.responseCode

            // 关键：非 2xx 时不能读 inputStream。
            // HttpURLConnection 会抛 IOException，异常分支里既拿不到真实状态码、
            // 也读不到 errorStream —— 曾导致「HTTP 500 + 正文含 status/ok」被误判为成功。
            if (httpCode !in 200..299) {
                val errBody = readBodyQuietly(conn.errorStream)
                return AttemptResult(
                    ok = false,
                    httpCode = httpCode,
                    body = errBody,
                    detail = "服务返回 HTTP $httpCode（服务未就绪）"
                )
            }

            val body = readBody(conn.inputStream)
            val lower = body.lowercase()
            val ok = lower.contains("status") && lower.contains("ok")
            AttemptResult(
                ok = ok,
                httpCode = httpCode,
                body = body,
                detail = if (ok) "服务状态正常" else "服务返回内容不含 status/ok"
            )
        } catch (e: Exception) {
            AttemptResult(
                ok = false,
                httpCode = httpCode,
                body = "",
                detail = readableError(e)
            )
        } finally {
            conn?.disconnect()
        }
    }

    data class AttemptResult(
        val ok: Boolean,
        val httpCode: Int,
        val body: String,
        val detail: String
    )

    /** 把异常翻译成用户能懂的排查提示 */
    private fun readableError(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when (e) {
            is java.net.SocketTimeoutException -> "请求超时（服务可能尚未启动）"
            is java.net.ConnectException -> "无法建立连接（端口未监听或设备防火墙拦截）"
            is java.net.UnknownHostException -> "无法解析主机地址"
            else -> msg
        }
    }

    private fun readBody(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            // 健康检查响应体很小，最多读 64KB 防异常
            var total = 0
            while (total < 64 * 1024) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    /**
     * 读错误流且绝不抛异常。
     * errorStream 在部分情况下会返回 null，或读取中途被对端关闭，
     * 这些都不应该污染主流程的错误判定。
     */
    private fun readBodyQuietly(stream: InputStream?): String = try {
        readBody(stream)
    } catch (_: Exception) {
        ""
    }
}
