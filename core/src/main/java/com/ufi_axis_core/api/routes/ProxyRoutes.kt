package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.util.AppLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

class ProxyRoutes(private val targetIp: String, private val targetPort: Int = 80) {
    private val tag = "ProxyRoutes"
    private val client = HttpClient(CIO) { engine { requestTimeout = 30000 }; expectSuccess = false }
    private val targetBase: String get() = if (targetPort == 80) "http://$targetIp" else "http://$targetIp:$targetPort"

    fun register(route: Route) {
        route.route("/proxy") {
            get("/goform/{rest...}") { handleProxy(call) }
            post("/goform/{rest...}") { handleProxy(call) }
            get("/{rest...}") { handleProxy(call) }
            post("/{rest...}") { handleProxy(call) }
        }
    }

    private suspend fun handleProxy(call: ApplicationCall) {
        val rest = call.parameters.getAll("rest")?.joinToString("/") ?: ""
        val targetUrl = "$targetBase/$rest"
        val query = call.request.queryString()
        val fullUrl = if (query.isNotEmpty()) "$targetUrl?$query" else targetUrl

        try {
            val method = call.request.httpMethod
            val response: HttpResponse = when (method) {
                HttpMethod.Get -> client.get(fullUrl) { forwardHeaders(call) }
                HttpMethod.Post -> {
                    val body = call.receive<ByteArray>()
                    client.post(fullUrl) { forwardHeaders(call); setBody(body) }
                }
                else -> client.get(fullUrl) { forwardHeaders(call) }
            }

            // 重命名 Set-Cookie 为 kano-cookie（与参考项目 reverseProxyModule 一致，避免浏览器 Cookie 冲突）
            val setCookies = response.headers.getAll("Set-Cookie")
            val respBuilder = call.response.headers
            setCookies?.forEach { cookie ->
                try { respBuilder.append("kano-cookie", cookie) } catch (_: Exception) {}
            }
            // CORS 头
            try {
                respBuilder.append("Access-Control-Allow-Origin", "*")
                respBuilder.append("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                respBuilder.append("Access-Control-Allow-Headers", "Content-Type, X-Requested-With, Kano-Cookie")
            } catch (_: Exception) {}

            val respBody = response.readBytes()
            call.respondBytes(respBody, ContentType.parse(response.contentType()?.toString() ?: "application/octet-stream"), HttpStatusCode.fromValue(response.status.value))
        } catch (e: Exception) {
            AppLogger.e(tag, "Proxy failed: $fullUrl", e)
            if (!call.response.isCommitted) {
                call.respond(HttpStatusCode.BadGateway, toJsonElement(mapOf("error" to (e.message ?: "Proxy error"))))
            }
        }
    }

    /**
     * 转发请求头，与参考项目 reverseProxyModule.kt 保持一致：
     * - 删除 Host/Cookie 头（由代理重新设置）
     * - 设置正确的 Referer 为目标服务器地址
     * - 通过 Kano-Cookie 头传递客户端 Cookie
     */
    private fun HttpRequestBuilder.forwardHeaders(call: ApplicationCall) {
        // 设置正确的 Referer（参考项目: setRequestProperty("Referer", targetServer)）
        header("Referer", "$targetBase/index.html")

        // 传递客户端 Kano-Cookie 为 Cookie（参考项目的 Cookie 传递机制）
        val kanoCookie = call.request.headers["Kano-Cookie"]
        if (!kanoCookie.isNullOrBlank()) {
            header("Cookie", kanoCookie)
        }

        call.request.headers.forEach { name, values ->
            // 跳过由代理管理的头部
            if (name.lowercase() in setOf("host", "referer", "cookie", "content-length", "transfer-encoding")) return@forEach
            values.forEach { header(name, it) }
        }
    }
}