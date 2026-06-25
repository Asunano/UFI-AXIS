package com.ufi_axis_core.core.server

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.middleware.AuthMiddleware
import com.ufi_axis_core.api.middleware.QoSMiddleware
import com.ufi_axis_core.api.routes.*
import com.ufi_axis_core.api.websocket.WebSocketManager
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.application.call
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.ChannelOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.Duration

class HttpServer(
    private val port: Int = 8088,
    private val ctx: RouteContext,
    private val authMiddleware: AuthMiddleware,
    private val qosMiddleware: QoSMiddleware,
    private val webSocketManager: WebSocketManager,
    private val deviceRoutes: DeviceRoutes,
    private val networkRoutes: NetworkRoutes,
    private val systemRoutes: SystemRoutes,
    private val trafficRoutes: TrafficRoutes,
    private val simRoutes: SimRoutes,
    private val atRoutes: ATRoutes,
    private val alertRoutes: AlertRoutes,
    private val wifiRoutes: WifiRoutes,
    private val configRoutes: ConfigRoutes,
    private val appRoutes: AppRoutes,
    private val shellRoutes: ShellRoutes,
    private val rootSmsRoutes: RootSmsRoutes,
    private val fileRoutes: FileRoutes,
    private val dashboardRoutes: DashboardRoutes,
    private val adbRoutes: AdbRoutes? = null,
    private val smsForwardRoutes: SmsForwardRoutes? = null,
    private val taskRoutes: TaskRoutes? = null,
    private val speedTestRoutes: SpeedTestRoutes? = null,
    private val debugLogRoutes: DebugLogRoutes? = null,
    private val advancedRoutes: AdvancedRoutes? = null,
    private val qosRoutes: QoSRoutes? = null,
    private val monitorRoutes: MonitorRoutes? = null,
    private val downloadRoutes: DownloadRoutes? = null
) {
    companion object {
        private const val MAX_REQUEST_BODY_SIZE = 512 * 1024L  // 512KB
    }

    private var server: ApplicationEngine? = null
    private val tag = "HttpServer"

    // 请求体过大异常
    private class RequestBodyTooLargeException(message: String) : Exception(message)

    // 自定义请求体大小限制插件（Ktor 2.3.x 无内置 RequestBodyLimit）
    private val RequestBodyLimit = createApplicationPlugin(name = "RequestBodyLimit") {
        onCall { call ->
            val cl = call.request.contentLength()
            if (cl != null && cl > MAX_REQUEST_BODY_SIZE) {
                throw RequestBodyTooLargeException(
                    "Request body ($cl bytes) exceeds ${MAX_REQUEST_BODY_SIZE / 1024}KB limit"
                )
            }
        }
    }

    fun start(): Boolean {
        AppLogger.i(tag, "Starting HTTP server on port $port")

        return try {
            // 禁用 Netty native transport（Android 不支持 epoll/kqueue）
            System.setProperty("io.netty.transport.noNative", "true")

            server = embeddedServer(Netty, port = port, configure = {
                // Netty 调优：
                // ① 提升 accept backlog（默认 100 → 1024），应对高并发连接
                // ② TCP 心跳保活 | 禁用 Nagle 低延迟 | 显式池化内存分配器
                configureBootstrap = {
                    option(ChannelOption.SO_BACKLOG, 1024)
                    childOption(ChannelOption.SO_KEEPALIVE, true)
                    childOption(ChannelOption.TCP_NODELAY, true)
                    childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                }
            }) {
                configurePlugins()
                configureRouting()
            }.start(wait = false)

            AppLogger.i(tag, "HTTP server started on :$port")
            true
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to start HTTP server: ${e.javaClass.name}: ${e.message}", e)
            server = null
            false
        }
    }

    fun stop() {
        AppLogger.i(tag, "Stopping HTTP server")
        server?.stop(1000, 5000)
        server = null
    }

    private fun Application.configurePlugins() {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = false
                isLenient = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }

        install(RequestBodyLimit)

        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(30)
            maxFrameSize = 64 * 1024L  // 64KB（仅传小 JSON，无需 1MB）
            masking = false
            contentConverter = KotlinxWebsocketSerializationConverter(Json)
        }

        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowHeader("X-Timestamp")
            allowHeader("X-Signature")
        }

        install(StatusPages) {
            exception<RequestBodyTooLargeException> { call, cause ->
                AppLogger.w("StatusPages", "413 Request body too large: ${cause.message}")
                call.respond(
                    HttpStatusCode(413, "Request Entity Too Large"),
                    toJsonElement(mapOf("error" to cause.message))
                )
            }
            exception<Throwable> { call, cause ->
                AppLogger.e("StatusPages", "Unhandled exception", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("error" to (cause.message ?: "Internal Server Error")))
                )
            }
        }
    }

    private fun Application.configureRouting() {
        routing {
            get("/health") {
                call.respond(toJsonElement(mapOf(
                    "status" to "ok",
                    "timestamp" to System.currentTimeMillis().toString()
                )))
            }

            route("/api") {
                authMiddleware.install(this)
                qosMiddleware.install(this)

                // 缓存管理 — 查看缓存状态和手动清理
                route("/cache") {
                    get("/stats") {
                        call.respond(toJsonElement(ctx.responseCache.getStats()))
                    }
                    post("/clear") {
                        ctx.responseCache.clear()
                        call.respond(toJsonElement(mapOf("success" to true, "message" to "Cache cleared")))
                    }
                    post("/invalidate") {
                        val p = call.receive<kotlinx.serialization.json.JsonObject>()
                        val pattern = p["pattern"]?.jsonPrimitive?.contentOrNull ?: "*"
                        ctx.responseCache.invalidate(pattern)
                        call.respond(toJsonElement(mapOf("success" to true, "pattern" to pattern)))
                    }
                }

                // 诊断端点 - 需要认证
                get("/diagnose") {
                    val diag = mutableMapOf<String, Any>()
                    diag["server_time"] = System.currentTimeMillis()
                    diag["app_version"] = "0.1"
                    // 耗时 shell 操作切到 IO 线程池，避免阻塞 Netty Worker 线程
                    val shellResult = withContext(Dispatchers.IO) {
                        mutableMapOf<String, Any>().apply {
                            try { put("root", ShellExecutor.executeAsRoot("id").stdout.contains("uid=0")) } catch (e: Exception) { AppLogger.w("HttpServer", "Diagnose root check failed: ${e.message}"); put("root", false) }
                            try { put("adbd", ShellExecutor.executeAsRoot("getprop init.svc.adbd").stdout.trim()) } catch (e: Exception) { AppLogger.w("HttpServer", "Diagnose adbd check failed: ${e.message}"); put("adbd", "unknown") }
                            try { put("mobile_data", ShellExecutor.executeAsRoot("settings get global mobile_data").stdout.trim()) } catch (e: Exception) { AppLogger.w("HttpServer", "Diagnose mobile_data check failed: ${e.message}"); put("mobile_data", "unknown") }
                            try {
                                var gw = ""
                                val ipRoute = ShellExecutor.execute("ip route 2>/dev/null | grep default").stdout
                                val m = Regex("default via (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(ipRoute)
                                if (m != null) gw = m.groupValues[1]
                                if (gw.isBlank()) gw = ShellExecutor.execute("getprop dhcp.wlan0.gateway 2>/dev/null").stdout.trim()
                                if (gw.isBlank()) gw = ShellExecutor.execute("getprop dhcp.wlan.gateway 2>/dev/null").stdout.trim()
                                if (gw.isBlank()) gw = "192.168.0.1"
                                put("gateway", gw)
                            } catch (e: Exception) { AppLogger.w("HttpServer", "Diagnose gateway check failed: ${e.message}"); put("gateway", "unknown") }
                        }
                    }
                    diag.putAll(shellResult)
                    call.respond(toJsonElement(diag))
                }

                deviceRoutes.register(this)
                networkRoutes.register(this)
                systemRoutes.register(this)
                trafficRoutes.register(this)
                rootSmsRoutes.register(this)
                simRoutes.register(this)
                atRoutes.register(this)
                alertRoutes.register(this)
                wifiRoutes.register(this)
                configRoutes.register(this)
                appRoutes.register(this)
                shellRoutes.register(this)
                fileRoutes.register(this)
                dashboardRoutes.register(this)
                adbRoutes?.register(this)
                smsForwardRoutes?.register(this)
                taskRoutes?.register(this)
                speedTestRoutes?.register(this)
                debugLogRoutes?.register(this)
                advancedRoutes?.register(this)
                qosRoutes?.register(this)
                monitorRoutes?.register(this)
                downloadRoutes?.register(this)
            }

            webSocket("/ws/realtime") {
                webSocketManager.handleConnection(this)
            }
        }
    }
}
