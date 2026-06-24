package com.ufi_axis_core.api.routes

import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

class SpeedTestRoutes {
    private val tag = "SpeedTest"
    private val limiter = Semaphore(6)
    private val buffer = ByteArray(1024 * 1024) { 0x66.toByte() }

    fun register(route: Route) {
        route.route("/speedtest") {
            get {
                if (!limiter.tryAcquire()) {
                    call.respond(HttpStatusCode.TooManyRequests, "请求频率过多")
                    return@get
                }
                try {
                    withContext(Dispatchers.IO) {
                        val chunks = (call.request.queryParameters["ckSize"]?.toIntOrNull()
                            ?: 10).coerceIn(1, 4096)
                        val totalBytes = buffer.size.toLong() * chunks

                        call.response.headers.append(HttpHeaders.ContentLength, totalBytes.toString())
                        call.response.headers.append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=random.dat")
                        call.response.headers.append(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate")

                        call.respondOutputStream(ContentType.Application.OctetStream) {
                            repeat(chunks) { write(buffer) }
                            flush()
                        }
                    }
                } finally {
                    limiter.release()
                }
            }
        }
    }
}