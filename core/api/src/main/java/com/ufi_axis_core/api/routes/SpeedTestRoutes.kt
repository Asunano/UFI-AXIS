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

/**
 * 测速端点：下行填充流、上行丢弃汇、零负载延迟探针。
 *
 * - `GET /api/speedtest?ckSize=N` — 按 N 个 1MiB 块流式吐填充数据（下行吞吐）。
 * - `POST /api/speedtest/upload` — 收下请求体后直接丢弃，只回报收到的字节数（上行吞吐）。
 * - `HEAD /api/speedtest` — 只回响应头，用于测延迟/抖动而不产生任何负载。
 *
 * 2026-08-26 加固：客户端在跑够时长后会直接关闭连接（这是正常流程，不是错误），
 * 原实现会让 `write` 抛 IOException 一路冒到 Ktor，日志里堆一片栈；同时 [limiter] 的
 * 令牌要等这次写彻底失败才释放。现在：读写失败即静默收尾，并加时间/字节预算，
 * 保证「慢客户端 + 大 ckSize」不会长时间占着并发位。
 *
 * 并发位从 6 提到 [MAX_CONCURRENT]：客户端改用多流并发测速（单方向 4 条），
 * 6 个位置会让「4 条下行 + web 端同时点一次」直接撞 429。
 */
class SpeedTestRoutes {
    private val tag = "SpeedTest"
    private val limiter = Semaphore(MAX_CONCURRENT)
    private val buffer = ByteArray(Units.SPEEDTEST_CHUNK_BYTES) { 0x66.toByte() }

    fun register(route: Route) {
        route.route("/speedtest") {
            // ── 零负载延迟探针：只回头，不产生任何字节 ──
            // 用 GET+立即断开来测延迟会白白搬运 1MiB，还会占一个并发位；HEAD 两者都不占。
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
                                // 客户端测够了主动断开：正常路径，只记一行 debug，不要往上抛
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
            // 客户端按时间预算持续写（chunked，无 Content-Length），所以这里以「读到 EOF /
            // 撞字节上限 / 撞时间预算」三者之一为终止条件，不依赖 Content-Length。
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
                    // 客户端跑够时长后直接掐掉上传连接：同下行，属正常收尾
                    AppLogger.d(tag, "上传连接提前断开，已收 $received 字节: ${e.message}")
                } finally {
                    limiter.release()
                }
            }
        }
    }

    private companion object {
        /** 单方向 4 条并发流 + 留余量给 web/第二客户端 */
        const val MAX_CONCURRENT = 12

        /** 服务端单次传输的时间预算：超过就收尾，避免慢客户端长期占用并发位 */
        const val TRANSFER_BUDGET_MS = 60_000L

        /** 上行单次最多接收 2 GiB，防止被当成免费丢弃汇无限灌 */
        const val UPLOAD_MAX_BYTES = 2L * 1024 * 1024 * 1024

        const val SINK_BUFFER_BYTES = 64 * 1024

        const val NO_STORE = "no-store, no-cache, must-revalidate"
    }
}
