package com.ufi_axis_core.core.server

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.middleware.AuthMiddleware
import com.ufi_axis_core.api.pairing.PairingManager
import com.ufi_axis_core.api.routes.*
import com.ufi_axis_core.api.routes.PairingRoutes
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
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.http.content.*
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.ChannelOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.Duration

class HttpServer(
    private val port: Int = 8088,
    private val ctx: RouteContext,
    private val androidContext: android.content.Context,
    private val authMiddleware: AuthMiddleware,
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
    private val updateRoutes: com.ufi_axis_core.api.routes.UpdateRoutes,
    private val appRoutes: AppRoutes,
    private val shellRoutes: ShellRoutes,
    private val rootSmsRoutes: RootSmsRoutes,
    private val fileRoutes: FileRoutes,
    private val dashboardRoutes: DashboardRoutes,
    private val pairingRoutes: PairingRoutes,
    private val pairingManager: PairingManager,
    private val pairedDevicesRoutes: PairedDevicesRoutes,
    private val webResourceManager: com.ufi_axis_core.util.WebResourceManager,
    private val webUpdateRoutes: com.ufi_axis_core.api.routes.WebUpdateRoutes,
    private val smsForwardRoutes: SmsForwardRoutes? = null,
    private val taskRoutes: TaskRoutes? = null,
    private val speedTestRoutes: SpeedTestRoutes? = null,
    private val debugLogRoutes: DebugLogRoutes? = null,
    private val qosRoutes: QoSRoutes? = null,
    private val monitorRoutes: MonitorRoutes? = null,
    private val notificationRoutes: com.ufi_axis_core.api.routes.NotificationRoutes? = null,
    private val downloadRoutes: DownloadRoutes? = null,
    private val serviceRoutes: com.ufi_axis_core.api.routes.ServiceRoutes? = null,
    private val tunnelRoutes: com.ufi_axis_core.api.routes.TunnelRoutes? = null,
    private val componentRoutes: com.ufi_axis_core.api.routes.ComponentRoutes? = null
) {
    companion object {
        private const val MAX_REQUEST_BODY_SIZE = 512 * 1024L  // 512KB（普通路由：防滥用 + 内存安全）
        private const val FILE_UPLOAD_BODY_SIZE = 200L * 1024 * 1024  // 文件管理器上传上限 200MB
        private const val FALLBACK_UPLOAD_BODY_SIZE = 100L * 1024 * 1024  // 兜底 100MB（清单未知时）
        private const val WEB_UPDATE_BODY_SIZE = 50L * 1024 * 1024  // Web 资源 ZIP 上传上限 50MB
        // 可选二进制组件本地上传上限：cloudflared 裸二进制约 36MB，留足余量
        private const val COMPONENT_UPLOAD_BODY_SIZE = 96L * 1024 * 1024
        private val VERSIONED_ASSET_REGEX = Regex("[_-][A-Za-z0-9]{6,}\\.")
    }

    private var server: ApplicationEngine? = null
    private val tag = "HttpServer"

    // 请求体过大异常
    private class RequestBodyTooLargeException(message: String) : Exception(message)

    // 自定义请求体大小限制插件（Ktor 2.3.x 无内置 RequestBodyLimit）
    // 路由级限制：
    //  - /api/update/upload（APK 推送兜底）用动态上限 = 版本清单 apkSize×1.2+10MB 缓冲（未知回落 100MB）；
    //  - /api/web/update（Web 资源 ZIP 手动上传）用固定 50MB 上限；
    //  - /api/files/upload（文件管理器上传）用 200MB 上限；
    //  - 其余路由保持 512KB（防滥用/非预期 body）。
    //
    // 2026-09-02 安全修复：原实现 `contentLength() ?: return` —— 没有 Content-Length 就**整段跳过**。
    // 而 `Transfer-Encoding: chunked` 正是合法的无 Content-Length 请求，于是任何上限都能被一行
    // 请求头绕过（在 256MB RAM 的设备上足以打爆内存）。现在改为：带 body 的请求必须给出
    // Content-Length，否则直接拒。我们自己的客户端（OkHttp/Retrofit、axios）都会带上。
    private val RequestBodyLimit = createApplicationPlugin(name = "RequestBodyLimit") {
        onCall { call ->
            val path = call.request.path()
            val limit = when {
                path.startsWith("/api/update/upload") -> updateRoutes.uploadLimitBytes()
                path.startsWith("/api/web/update") -> WEB_UPDATE_BODY_SIZE
                path.startsWith("/api/files/upload") -> FILE_UPLOAD_BODY_SIZE
                path.startsWith("/api/components/") && path.endsWith("/upload") -> COMPONENT_UPLOAD_BODY_SIZE
                else -> MAX_REQUEST_BODY_SIZE
            }
            val cl = call.request.contentLength()
            if (cl == null) {
                // 无 Content-Length：只有确实带 body 的请求才拒（GET/HEAD/DELETE 等无 body 请求不受影响）。
                val chunked = call.request.header(HttpHeaders.TransferEncoding)
                    ?.contains("chunked", ignoreCase = true) == true
                if (chunked) {
                    throw RequestBodyTooLargeException(
                        "Chunked request body is not accepted; Content-Length is required (limit ${limit / 1024}KB)"
                    )
                }
                return@onCall
            }
            if (cl > limit) {
                throw RequestBodyTooLargeException(
                    "Request body ($cl bytes) exceeds ${limit / 1024}KB limit"
                )
            }
        }
    }

    fun start(): Boolean {
        AppLogger.i(tag, "Starting HTTP server on port $port")

        return try {
            // 禁用 Netty native transport（Android 不支持 epoll/kqueue）
            System.setProperty("io.netty.transport.noNative", "true")
            // 禁用 Netty unsafe 路径（Android 上 native unsafe 不可用，bind 时会抛异常导致启动失败）
            System.setProperty("io.netty.noUnsafe", "true")

            server = embeddedServer(Netty, host = "0.0.0.0", port = port, configure = {
                // Netty 线程池显式配置 — 针对低端设备（256MB/512MB RAM）极致优化：
                // ① workerGroupSize: 强制为 1，减少上下文切换和内存占用
                // ② connectionGroupSize: 强制为 1
                // ③ callGroupSize: 强制为 1
                // ④ SO_BACKLOG 128: 进一步降低内核内存占用
                connectionGroupSize = 1
                workerGroupSize = 1
                callGroupSize = 1
                configureBootstrap = {
                    option(ChannelOption.SO_BACKLOG, 128)
                    option(ChannelOption.SO_REUSEADDR, true)
                    childOption(ChannelOption.SO_KEEPALIVE, true)
                    childOption(ChannelOption.TCP_NODELAY, true)
                    // 显式限制接收/发送缓冲区，减少单连接内存占用
                    childOption(ChannelOption.SO_RCVBUF, 32 * 1024)
                    childOption(ChannelOption.SO_SNDBUF, 32 * 1024)
                    // 使用 Unpooled 分配器减少内存碎片
                    childOption(ChannelOption.ALLOCATOR, io.netty.buffer.UnpooledByteBufAllocator.DEFAULT)
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
        // ── 网络访问日志（2026-08-27）──
        // 之前 core 没有任何请求日志：唯一近似的是 AuthMiddleware 里一行 `METHOD uri from IP`，
        // 既没有状态码也没有耗时，还只覆盖 /api/**（静态资源 / /health / /pairing 全无记录）。
        // 放在 Monitoring 阶段：proceed() 返回后响应状态已确定，能同时拿到状态码与耗时；
        // 且它在 routing 之外，未命中路由的 404 也会被记录。
        intercept(ApplicationCallPipeline.Monitoring) {
            val startMs = System.currentTimeMillis()
            try {
                proceed()
            } finally {
                val cost = System.currentTimeMillis() - startMs
                val code = call.response.status()?.value
                val level = when {
                    code == null -> AppLogger.LogLevel.DEBUG   // 未命中路由/静态兜底，非异常
                    code >= 500 -> AppLogger.LogLevel.ERROR
                    code >= 400 -> AppLogger.LogLevel.WARN
                    else -> AppLogger.LogLevel.DEBUG
                }
                AppLogger.net(
                    level,
                    "HTTP",
                    "${call.request.httpMethod.value} ${call.request.uri} → ${code ?: "-"} " +
                        "${cost}ms from ${call.request.local.remoteAddress}"
                )
            }
        }

        install(ContentNegotiation) {
            json(
                Json {
                    prettyPrint = false
                    isLenient = true
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                },
                contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8)
            )
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
            // X-Nonce 是 AuthMiddleware 的必填头（防重放），漏在这里会让跨域客户端
            // 在预检阶段就被浏览器挡掉：内置 web 面板同源所以看不出来，跨域调试才暴露。
            allowHeader("X-Nonce")
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
            // 请求体非法（如 JSON 语法错误、不是 JSON 对象）→ 400 而不是落到下面的 500 兜底。
            // 历史教训：请求体解析失败曾被 exception<Throwable> 统一吞成 500，
            // 前端只看到"操作无反应"，排查成本极高。
            exception<io.ktor.server.plugins.BadRequestException> { call, cause ->
                AppLogger.w("StatusPages", "400 Bad request: ${cause.message}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    toJsonElement(mapOf("error" to (cause.message ?: "Bad Request")))
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

        // ── P2 交付层压缩（2026-09-04）──
        // 运行时按客户端 Accept-Encoding 对可压缩响应做 gzip / deflate，省去传输体积。
        // 选运行时方案而非「构建期预生成 .br/.gz」：零构建期成本、零 assets/zip 契约改动、
        // 不增加 WebResourceManager 与手动更新 ZIP 的复杂度，对单用户管理面板足够。
        // 覆盖范围：静态资源（JS/CSS/JSON/SVG/HTML/woff2）与 /api 的 JSON 响应。
        // minimumSize=1024 让极小的文件跳过压缩，避免无谓的 CPU 开销（低端设备友好）。
        install(Compression) {
            gzip {
                priority = 1.0
                minimumSize(1024)
                matchContentType(
                    ContentType.Application.JavaScript,
                    ContentType.Text.CSS,
                    ContentType.Application.Json,
                    ContentType.Image.SVG,
                    ContentType.Text.Html,
                    ContentType.Font.Woff2
                )
            }
            deflate {
                priority = 0.5
                minimumSize(1024)
                matchContentType(
                    ContentType.Application.JavaScript,
                    ContentType.Text.CSS,
                    ContentType.Application.Json,
                    ContentType.Image.SVG,
                    ContentType.Text.Html,
                    ContentType.Font.Woff2
                )
            }
        }
    }

    private fun Application.configureRouting() {
        routing {
            get("/health") {
                val ws = webSocketManager.getStats()
                call.respond(toJsonElement(mapOf(
                    "status" to "ok",
                    "timestamp" to System.currentTimeMillis().toString(),
                    // F26 / F27：暴露 WS 解析失败计数、WS stale、缓存 stale 标志
                    "ws_connections" to (ws["connections"] as? Number ?: 0),
                    "ws_parse_failures" to (ws["parse_failures"] as? Number ?: 0),
                    "ws_stale" to (ws["stale"] as? Boolean ?: false),
                    "cache_stale" to ctx.responseCache.isStale()
                )))
            }

            // ── 配对模式端点（免鉴权，独立于 /api 鉴权块）──
            // 这里是**唯一**按请求频率拦客户端的地方（PairingRoutes 自带的每 IP 500ms 限频 +
            // 密码错误锁定），因为它免鉴权、是唯一的暴力破解入口。
            // 2026-08-25：`QoSMiddleware`（对客户端请求数限流并回 429）已整体删除，见下面 /api 块的说明。
            route("/pairing") {
                pairingRoutes.register(this)
            }

            // ── Web 前端静态资源 + SPA fallback（通过 WebResourceManager 实现 override + assets 回退）──
            // index.html / version.json 一律 no-store：它们的 URL 不带 hash，浏览器的启发式缓存
            // 会让「换了 core 包但页面还是旧的」变成常态（旧 index 引用旧 assets，而旧 assets 因为
            // immutable 头被长期缓存，整套旧前端能一直活着）。带 hash 的 assets 才允许长期缓存。
            get("/") {
                val bytes = webResourceManager.readAsset("index.html")
                if (bytes != null) {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondBytes(bytes, ContentType.Text.Html)
                } else {
                    AppLogger.w(tag, "Web index.html not found (neither override nor assets)")
                    call.respond(HttpStatusCode.NotFound)
                }
            }
            get("/{path...}") {
                val rawPath = call.parameters.getAll("path")?.joinToString("/") ?: ""
                val requestPath = java.net.URLDecoder.decode(rawPath, "UTF-8")

                val bytes = webResourceManager.readAsset(requestPath)
                if (bytes != null) {
                    val contentType = when {
                        requestPath.endsWith(".js") -> ContentType.parse("application/javascript")
                        requestPath.endsWith(".css") -> ContentType.Text.CSS
                        requestPath.endsWith(".svg") -> ContentType.Image.SVG
                        requestPath.endsWith(".png") -> ContentType.Image.PNG
                        requestPath.endsWith(".ico") -> ContentType.parse("image/x-icon")
                        requestPath.endsWith(".html") -> ContentType.Text.Html
                        requestPath.endsWith(".json") -> ContentType.Application.Json
                        requestPath.endsWith(".woff2") -> ContentType.parse("font/woff2")
                        requestPath.endsWith(".woff") -> ContentType.parse("font/woff")
                        else -> ContentType.Application.OctetStream
                    }
                    // 带 hash 的静态资源可长期缓存
                    val isVersioned = requestPath.contains("/assets/") &&
                        VERSIONED_ASSET_REGEX.containsMatchIn(requestPath)
                    if (isVersioned) {
                        call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                    } else if (requestPath.endsWith(".html") || requestPath.endsWith("version.json")) {
                        call.response.header(HttpHeaders.CacheControl, "no-store")
                    }
                    call.respondBytes(bytes, contentType)
                } else {
                    // 文件不存在 → SPA fallback
                    if (!requestPath.isBlank() && requestPath != "favicon.ico") {
                        AppLogger.w(tag, "Web asset not found: $requestPath")
                    }
                    val fallbackBytes = webResourceManager.readAsset("index.html")
                    if (fallbackBytes != null) {
                        call.response.header(HttpHeaders.CacheControl, "no-store")
                        call.respondBytes(fallbackBytes, ContentType.Text.Html)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }

            route("/api") {
                authMiddleware.install(this)
                // 【不变量】QoS 只作用于 core **往下**打的量，不限制客户端 → core 的请求。
                //
                // 已鉴权接口一律不限流：web 打开一个页面会并发发十几个请求（dashboard + 设备信息 +
                // 信号 + 流量…），任何请求级限流都会把正常的批量加载判成滥用（历史上就是这么表现的：
                // 首屏部分卡片空白 / 转圈）。防滥用靠鉴权（未授权直接 444），不靠限频。
                // 真正的过载保护在两个朝下的自适应信号量里，各自只管自己那段链路：
                //   · GoformQoS —— core → 设备 goform HTTP（查询/写入分开计许可 + 2s 查询缓存）
                //   · ShellQoS  —— core → 本机 shell（root / 普通分开计许可 + 缓存 + 批量合并）
                // 它们是**阻塞等许可**而不是拒绝请求：客户端只会感觉慢一点，不会拿到 429 或空数据。
                // 唯一按频率拦客户端的地方是免鉴权的 /pairing（见上）与 /api/speedtest 的并发位。

                // 配对状态 — 查看当前配对设备信息（需认证）
                route("/pairing") {
                    get("/status") {
                        call.respond(toJsonElement(pairingManager.statusPayload()))
                    }
                    post("/unpair") {
                        pairingManager.unpairAll()
                        pairingRoutes.clearRateLimits(call.request.local.remoteAddress)
                        call.respond(toJsonElement(mapOf("success" to true, "message" to "All devices unpaired, re-entered pairing mode")))
                    }
                    post("/unpair/{fingerprint}") {
                        val fp = call.parameters.getAll("fingerprint")?.joinToString("/") ?: ""
                        pairingManager.unpairFingerprint(fp)
                        pairingRoutes.clearRateLimits(call.request.local.remoteAddress)
                        call.respond(toJsonElement(mapOf("success" to true, "message" to "Device unpaired")))
                    }
                    put("/config") {
                        val body = call.receiveJsonObject()
                        val enabled = body["pairing_enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                        val maxDevices = body["pairing_max_devices"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                        if (enabled != null) ctx.settings.pairingEnabled = enabled
                        if (maxDevices != null) ctx.settings.pairingMaxDevices = maxDevices
                        call.respond(toJsonElement(mapOf(
                            "success" to true,
                            "pairing_enabled" to ctx.settings.pairingEnabled,
                            "pairing_max_devices" to ctx.settings.pairingMaxDevices
                        )))
                    }
                }

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
                        val p = call.receiveJsonObject()
                        val pattern = p["pattern"]?.jsonPrimitive?.contentOrNull ?: "*"
                        ctx.responseCache.invalidate(pattern)
                        call.respond(toJsonElement(mapOf("success" to true, "pattern" to pattern)))
                    }
                }

                // 诊断端点 - 需要认证
                get("/diagnose") {
                    val diag = mutableMapOf<String, Any>()
                    diag["server_time"] = System.currentTimeMillis()
                    // 版本从已安装包读取（:core:network 是库模块，拿不到 :core 的 BuildConfig），
                    // 原来写死 "0.1"，和 /api/config/version 对不上。
                    diag["app_version"] = try {
                        androidContext.packageManager
                            .getPackageInfo(androidContext.packageName, 0).versionName ?: "unknown"
                    } catch (_: Exception) { "unknown" }
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

                    // 设备 profile 状态（计划书 10.2）：便宜的元信息，不发设备查询。
                    // status 的四态是排障用的：型号填错时 core 会静默回落默认 profile，
                    // 没有这一行就只能去翻启动日志里的 WARN。
                    val configuredProfile = ctx.settings.deviceProfileId
                    val activeProfile = ctx.dataHub.deviceProfileId
                    diag["device_profile"] = mapOf(
                        "active" to (activeProfile ?: ""),
                        "configured" to configuredProfile,
                        "normalization_enabled" to (activeProfile != null),
                        "status" to when {
                            activeProfile == null -> "disabled"        // field_normalization_enabled=false
                            configuredProfile.isBlank() -> "default"   // 没配，用注册表默认
                            configuredProfile == activeProfile -> "configured"
                            else -> "fallback"                          // 配了但注册表里没有，已回落
                        }
                    )

                    // 字段覆盖率（计划书 10.1）：只在显式要求时算，它会逐分组向设备发查询。
                    if (call.request.queryParameters["fields"] == "1") {
                        diag["field_coverage"] = try {
                            ctx.dataHub.fieldCoverage()
                        } catch (e: Exception) {
                            AppLogger.w("HttpServer", "字段覆盖率诊断失败: ${e.message}")
                            mapOf("error" to (e.message ?: "unknown"))
                        }
                    }

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
                updateRoutes.register(this)
                appRoutes.register(this)
                shellRoutes.register(this)
                fileRoutes.register(this)
                dashboardRoutes.register(this)
                pairedDevicesRoutes.register(this)
                smsForwardRoutes?.register(this)
                taskRoutes?.register(this)
                speedTestRoutes?.register(this)
                debugLogRoutes?.register(this)
                qosRoutes?.register(this)
                monitorRoutes?.register(this)
                notificationRoutes?.register(this)
                downloadRoutes?.register(this)
                serviceRoutes?.register(this)
                tunnelRoutes?.register(this)
                componentRoutes?.register(this)
                webUpdateRoutes.register(this)
            }

            webSocket("/ws/realtime") {
                webSocketManager.handleConnection(this)
            }
        }
    }
}
