package com.ufi_axis_core.api.routes

import com.ufi_axis_core.contract.Units
import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 测速端点：下行填充流、上行丢弃汇、零负载延迟探针、外网节点白名单转发。
 *
 * - `GET /api/speedtest?ckSize=N` — 按 N 个 1MiB 块流式吐填充数据（下行吞吐）。
 * - `POST /api/speedtest/upload` — 收下请求体后直接丢弃，只回报收到的字节数（上行吞吐）。
 * - `HEAD /api/speedtest` — 只回响应头，用于测延迟/抖动而不产生任何负载。
 * - `GET|POST /api/speedtest/relay?url=` — **仅 Web**：把外网自建节点请求经 core 转发。
 *   浏览器无法直连无 CORS 的测速站；App 仍直连节点，不走本端点。url 必须在白名单内。
 */
class SpeedTestRoutes {
    private val tag = "SpeedTest"
    private val limiter = Semaphore(MAX_CONCURRENT)
    private val buffer = ByteArray(Units.SPEEDTEST_CHUNK_BYTES) { 0x66.toByte() }

    fun register(route: Route) {
        route.route("/speedtest") {
            // ── 零负载延迟探针：只回头，不产生任何字节 ──
            head {
                call.response.headers.append(HttpHeaders.CacheControl, NO_STORE)
                call.respond(HttpStatusCode.OK)
            }

            // ── 下行：流式吐填充数据 ──
            get {
                if (!limiter.tryAcquire()) {
                    call.respond(HttpStatusCode.TooManyRequests, "请求频率过多")
                    return@get
                }
                try {
                    withContext(Dispatchers.IO) {
                        val chunks = (call.request.queryParameters["ckSize"]?.toIntOrNull()
                            ?: Units.SPEEDTEST_CHUNKS_DEFAULT)
                            .coerceIn(Units.SPEEDTEST_CHUNKS_RANGE)
                        val totalBytes = buffer.size.toLong() * chunks

                        call.response.headers.append(HttpHeaders.ContentLength, totalBytes.toString())
                        call.response.headers.append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=random.dat")
                        call.response.headers.append(HttpHeaders.CacheControl, NO_STORE)

                        call.respondOutputStream(ContentType.Application.OctetStream) {
                            val deadline = System.currentTimeMillis() + TRANSFER_BUDGET_MS
                            var sent = 0
                            try {
                                while (sent < chunks && System.currentTimeMillis() < deadline) {
                                    write(buffer)
                                    sent++
                                }
                                flush()
                            } catch (e: IOException) {
                                AppLogger.d(tag, "客户端提前断开，已发 $sent/$chunks MiB: ${e.message}")
                            }
                            if (sent < chunks) {
                                AppLogger.d(tag, "写入预算用尽或提前结束：$sent/$chunks MiB")
                            }
                        }
                    }
                } finally {
                    limiter.release()
                }
            }

            // ── 上行：读完即丢，只回报字节数 ──
            post("/upload") {
                if (!limiter.tryAcquire()) {
                    call.respond(HttpStatusCode.TooManyRequests, "请求频率过多")
                    return@post
                }
                var received = 0L
                try {
                    withContext(Dispatchers.IO) {
                        val input = call.receiveStream()
                        val sink = ByteArray(SINK_BUFFER_BYTES)
                        val deadline = System.currentTimeMillis() + TRANSFER_BUDGET_MS
                        while (received < UPLOAD_MAX_BYTES && System.currentTimeMillis() < deadline) {
                            val n = input.read(sink)
                            if (n == -1) break
                            received += n
                        }
                    }
                    call.respondText(
                        """{"success":true,"bytes":$received}""",
                        ContentType.Application.Json
                    )
                } catch (e: IOException) {
                    AppLogger.d(tag, "上传连接提前断开，已收 $received 字节: ${e.message}")
                } finally {
                    limiter.release()
                }
            }

            // ── Web 外网转发（白名单） ──
            get("/relay") {
                val target = resolveRelayTarget(call.request.queryParameters["url"])
                if (target == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid speedtest url")
                    return@get
                }
                val range = call.request.header(HttpHeaders.Range)
                if (!limiter.tryAcquire()) {
                    call.respond(HttpStatusCode.TooManyRequests, "请求频率过多")
                    return@get
                }
                try {
                    withContext(Dispatchers.IO) {
                        val conn = openUpstream(target, "GET", range)
                        try {
                            val code = conn.responseCode
                            if (code !in 200..299) {
                                call.respond(HttpStatusCode.BadGateway, "Upstream HTTP $code")
                                return@withContext
                            }
                            // 回传关键头，便于客户端识别 206 / 长度
                            call.response.header(HttpHeaders.ContentType, "application/octet-stream")
                            call.response.header(HttpHeaders.CacheControl, NO_STORE)
                            conn.getHeaderField(HttpHeaders.ContentRange)?.let {
                                call.response.header(HttpHeaders.ContentRange, it)
                            }
                            conn.getHeaderField(HttpHeaders.ContentLength)?.toLongOrNull()?.let {
                                call.response.header(HttpHeaders.ContentLength, it.toString())
                            }
                            if (code == 206) {
                                call.respondOutputStream(ContentType.Application.OctetStream, HttpStatusCode.PartialContent) {
                                    relayCopy(conn, this)
                                }
                            } else {
                                call.respondOutputStream(ContentType.Application.OctetStream) {
                                    relayCopy(conn, this)
                                }
                            }
                        } finally {
                            conn.disconnect()
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(tag, "relay GET failed: ${e.message}")
                    runCatching { call.respond(HttpStatusCode.BadGateway, "Relay failed") }
                } finally {
                    limiter.release()
                }
            }

            post("/relay") {
                val target = resolveRelayTarget(call.request.queryParameters["url"])
                if (target == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid speedtest url")
                    return@post
                }
                if (!limiter.tryAcquire()) {
                    call.respond(HttpStatusCode.TooManyRequests, "请求频率过多")
                    return@post
                }
                var sent = 0L
                try {
                    withContext(Dispatchers.IO) {
                        val conn = openUpstream(target, "POST", null)
                        try {
                            conn.doOutput = true
                            conn.setRequestProperty(HttpHeaders.ContentType, "application/octet-stream")
                            conn.outputStream.use { out ->
                                val input = call.receiveStream()
                                val sink = ByteArray(SINK_BUFFER_BYTES)
                                val deadline = System.currentTimeMillis() + UPLOAD_RELAY_BUDGET_MS
                                while (sent < UPLOAD_MAX_BYTES && System.currentTimeMillis() < deadline) {
                                    val n = input.read(sink)
                                    if (n == -1) break
                                    out.write(sink, 0, n)
                                    sent += n
                                }
                                out.flush()
                            }
                            val code = conn.responseCode
                            if (code !in 200..299) {
                                call.respond(HttpStatusCode.BadGateway, """{"success":false,"error":"Upstream HTTP $code","bytes":$sent}""")
                                return@withContext
                            }
                            call.respondText(
                                """{"success":true,"bytes":$sent}""",
                                ContentType.Application.Json
                            )
                        } finally {
                            conn.disconnect()
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(tag, "relay POST failed: ${e.message}")
                    runCatching {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            """{"success":false,"error":"Relay failed"}"""
                        )
                    }
                } finally {
                    limiter.release()
                }
            }
        }
    }

    private fun resolveRelayTarget(raw: String?): URL? {
        if (raw.isNullOrBlank()) return null
        return try {
            val url = URL(raw)
            if (url.protocol != "https" && url.protocol != "http") return null
            if (url.host.lowercase() !in ALLOWED_RELAY_HOSTS) return null
            url
        } catch (_: Exception) {
            null
        }
    }

    private fun openUpstream(url: URL, method: String, range: String?): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        conn.requestMethod = method
        conn.setRequestProperty(HttpHeaders.CacheControl, NO_STORE)
        if (range != null) conn.setRequestProperty(HttpHeaders.Range, range)
        return conn
    }

    private fun relayCopy(conn: HttpURLConnection, out: java.io.OutputStream) {
        val input = conn.inputStream
        val sink = ByteArray(SINK_BUFFER_BYTES)
        val deadline = System.currentTimeMillis() + TRANSFER_BUDGET_MS
        try {
            while (System.currentTimeMillis() < deadline) {
                val n = input.read(sink)
                if (n <= 0) break
                out.write(sink, 0, n)
            }
            out.flush()
        } catch (e: IOException) {
            AppLogger.d(tag, "relay client closed: ${e.message}")
        } finally {
            runCatching { input.close() }
        }
    }

    private companion object {
        /** 单方向 4 条并发流 + 留余量给 web/第二客户端 */
        const val MAX_CONCURRENT = 12

        /** 服务端单次传输的时间预算：超过就收尾，避免慢客户端长期占用并发位 */
        const val TRANSFER_BUDGET_MS = 60_000L

        /** 上行单次最多接收 2 GiB，防止被当成免费丢弃汇无限灌 */
        const val UPLOAD_MAX_BYTES = 2L * 1024 * 1024 * 1024

        const val UPLOAD_RELAY_BUDGET_MS = 30_000L

        const val SINK_BUFFER_BYTES = 64 * 1024

        const val NO_STORE = "no-store, no-cache, must-revalidate"

        /** Web 外网转发白名单：与 App SpeedTestState.EXTERNAL_NODES 同源 */
        val ALLOWED_RELAY_HOSTS = setOf("speedtestone.losn.cc")
    }
}
