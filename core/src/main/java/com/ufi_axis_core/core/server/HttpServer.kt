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
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import java.time.Duration

class HttpServer(
    private val port: Int = 8088,
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
    private val adbRoutes: AdbRoutes? = null,
    private val proxyRoutes: ProxyRoutes? = null,
    private val smsForwardRoutes: SmsForwardRoutes? = null,
    private val taskRoutes: TaskRoutes? = null,
    private val speedTestRoutes: SpeedTestRoutes? = null,
    private val debugLogRoutes: DebugLogRoutes? = null,
    private val advancedRoutes: AdvancedRoutes? = null,
    private val qosRoutes: QoSRoutes? = null,
    private val monitorRoutes: MonitorRoutes? = null,
    private val downloadRoutes: DownloadRoutes? = null
) {
    private var server: ApplicationEngine? = null
    private val tag = "HttpServer"

    fun start() {
        AppLogger.i(tag, "Starting HTTP server on port $port")

        try {
            // 禁用 Netty native transport（Android 不支持 epoll/kqueue）
            System.setProperty("io.netty.transport.noNative", "true")

            server = embeddedServer(Netty, port = port) {
                configurePlugins()
                configureRouting()
            }.start(wait = false)

            AppLogger.i(tag, "HTTP server started on :$port")
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to start HTTP server", e)
            throw e
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

                // 诊断端点 - 需要认证
                get("/diagnose") {
                    val diag = mutableMapOf<String, Any>()
                    diag["server_time"] = System.currentTimeMillis()
                    diag["app_version"] = "0.1"
                    diag["root"] = try { ShellExecutor.executeAsRoot("id").stdout.contains("uid=0") } catch (_: Exception) { false }
                    diag["api_routes"] = listOf("/health","/api/device/*","/api/network/*","/api/system/*","/api/traffic/*","/api/sms/*","/api/sim/*","/api/at/*","/api/alerts/*","/api/wifi/*","/api/config/*","/api/apps/*","/api/adb/*","/api/sms-forward/*","/api/tasks/*","/api/proxy/*","/api/files/*","/api/downloads/*")
                    // 测试关键子系统
                    try { diag["adbd"] = ShellExecutor.executeAsRoot("getprop init.svc.adbd").stdout.trim() } catch (_: Exception) { diag["adbd"] = "unknown" }
                    try { diag["mobile_data"] = ShellExecutor.executeAsRoot("settings get global mobile_data").stdout.trim() } catch (_: Exception) { diag["mobile_data"] = "unknown" }
                    try {
                        var gw = ""
                        val ipRoute = ShellExecutor.execute("ip route 2>/dev/null | grep default").stdout
                        val m = Regex("default via (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(ipRoute)
                        if (m != null) gw = m.groupValues[1]
                        if (gw.isBlank()) gw = ShellExecutor.execute("getprop dhcp.wlan0.gateway 2>/dev/null").stdout.trim()
                        if (gw.isBlank()) gw = ShellExecutor.execute("getprop dhcp.wlan.gateway 2>/dev/null").stdout.trim()
                        if (gw.isBlank()) gw = "192.168.0.1"
                        diag["gateway"] = gw
                    } catch (_: Exception) { diag["gateway"] = "unknown" }
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

            proxyRoutes?.register(this)
        }
    }
}
