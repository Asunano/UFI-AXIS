package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.terminal.TtydTicketStore
import com.ufi_axis_core.api.websocket.WsAuthenticator
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.controller.screen.ScrcpyServerLauncher
import com.ufi_axis_core.controller.screen.ScreenSessionManager
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 远程屏幕（投屏）路由（2026-10-31）。
 *
 * ## 端点
 * - `POST /api/screen/session` — 签发会话票据。**默认 403**（`screen_enabled` 开关）。
 * - `GET  /api/screen/status`  — 开关与会话状态回读。
 * - `GET  /ws/screen?ticket=…&token=…&ts=…&nonce=…&sig=…` — 二进制双向泵。
 *
 * ## 为什么 WS 挂在 `/api` 之外
 * 与 `/ws/realtime`、`/ws/terminal` 同因同果：浏览器原生 WebSocket **发不了自定义头**，
 * 只能把凭据放 query。`/ws/` 在 `AuthMiddleware` 里被整体豁免，所以这条路由
 * **必须自己做握手验签**，否则它就是整条鉴权链上唯一的缺口。
 *
 * ## 两道闸
 * 1. `WsAuthenticator`：`token/ts/nonce/sig` 验签，与 `/ws/realtime` 完全同参同名。
 * 2. 一次性票据：由 `POST /api/screen/session` 签发，端点本身已过完整头部鉴权 + 开关。
 *
 * ## 与终端的关键差别
 * 终端的 WS 是**反代到本机另一个 WS**（ttyd）；这里是**反代到一条裸 TCP socket**
 * （scrcpy-server 连过来之后由 `accept()` 拿到）。所以泵的实现是
 * `Frame.Binary ⇄ InputStream/OutputStream`，不是 WS ⇄ WS。
 */
class ScreenRoutes(
    private val settings: AppSettings,
    private val manager: ScreenSessionManager,
    /**
     * 票据库直接复用 [TtydTicketStore]。
     *
     * 不为投屏另写一份的理由：它的能力（TTL、上限淘汰、滑动续期、纯 JVM 可测）与终端需求
     * 完全同构，唯一的差别是 TTL —— 投屏握手是**秒级**动作（前端拿到票立刻连），
     * 60 秒足够，用构造参数调即可。复制一份只会让"票据过期后重连"这类修复要改两处。
     */
    private val tickets: TtydTicketStore = TtydTicketStore(ttlMs = 60_000L, maxEntries = 4),
    private val wsAuthenticator: WsAuthenticator? = null,
) {

    fun register(root: Route) {
        root.route("/screen") {
            post("/session") {
                if (!settings.screenEnabled) {
                    call.respondFail(
                        HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN,
                        "远程屏幕未开启（设置中手动打开）"
                    )
                    return@post
                }
                // 会话是在 WS 握手时才真正建立的（那时才知道客户端还在不在）。
                // 这里只发票 —— 提前起 server 会让"点了但没连上"留下一个空转的进程。
                val bodyObj = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }
                    .getOrNull()
                val params = ScrcpyServerLauncher.VideoParams.sanitize(
                    size = bodyObj?.get("max_size")?.jsonPrimitive?.intOrNull,
                    fps = bodyObj?.get("max_fps")?.jsonPrimitive?.intOrNull,
                    bitRate = bodyObj?.get("bit_rate")?.jsonPrimitive?.intOrNull,
                )
                val ticket = tickets.issue(SESSION_ID)
                call.respond(
                    toJsonElement(
                        mapOf(
                            "ticket" to ticket,
                            "ws_path" to "/ws/screen",
                            "expires_in" to tickets.ttlSeconds,
                            "scrcpy_version" to ScrcpyServerLauncher.SERVER_VERSION,
                            // 尺寸**不在这里给**：真实宽高以视频流 metadata 为准
                            // （server 会把 854 对齐到 848，写死会与画布对不上）。
                            "size_source" to "video_stream_metadata",
                            // 回显**服务端钳制后**的实际值：前端滑块越界时以这里为准
                            "max_size" to params.maxSize,
                            "max_fps" to params.maxFps,
                            "bit_rate" to params.bitRate,
                        )
                    )
                )
            }

            /**
             * 设备原生像素截图（PNG 原样透传）。
             *
             * ## 为什么要有它
             * app 的「快照」模式与 web 的「截屏」按钮都靠这一条：设备只有软件编码器、
             * 部分机型上解码到 Surface 会原生崩溃，此时「1–2 fps 的静态图」是唯一稳定的
             * 画面来源。2026-11-01 排查发现**这个路由根本不存在** —— 两个客户端都拿到了
             * SPA 兜底的 `200 text/html`（`get("/{path...}")` 会吃掉未注册的 `/api/...`），
             * 表现成"截图解码失败（1877B）"，而不是一个能定位的 404。
             *
             * ## 边界
             * - 开关关 → 403（与 `/session` 同一条闸：关着时 `/api/screen/…` 全拒）。
             * - 不要求有活动会话：`screencap` 与 scrcpy 互不干扰，没连投屏时也能截。
             * - 拿回来的字节**必须**是 PNG 才发。截屏失败 / 少了 adb / 通道里混进文本时
             *   一律 503，绝不把垃圾当图片下发 —— 客户端拿到 HTML 只会说"解码失败"。
             * - 同一时刻只跑一张：`screencap` 单次要 300–800ms，轮询端手快一点就会叠起来，
             *   把 adb 通道和 CPU 一起吃满；叠了直接 429，客户端下一拍自然会重试。
             */
            get("/screenshot") {
                if (!settings.screenEnabled) {
                    call.respondFail(
                        HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN,
                        "远程屏幕未开启（设置中手动打开）"
                    )
                    return@get
                }
                if (!shotInFlight.compareAndSet(false, true)) {
                    call.respondFail(
                        HttpStatusCode.TooManyRequests, ErrorCode.TOO_MANY_REQUESTS,
                        "上一次截图尚未结束"
                    )
                    return@get
                }
                val png = try {
                    manager.screenshot()
                } finally {
                    shotInFlight.set(false)
                }
                if (png == null || !isPng(png)) {
                    AppLogger.w(TAG, "截屏失败或返回非 PNG（${png?.size ?: 0}B）")
                    call.respondFail(
                        HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "设备截图失败（adb 通道不可用或 display 未就绪）"
                    )
                    return@get
                }
                // PNG 是设备原生像素，同一张不会被复用，禁掉缓存免得客户端拿到旧画面
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondBytes(png, ContentType.Image.PNG)
            }

            get("/status") {
                val stats = manager.stats()
                call.respond(
                    toJsonElement(
                        mapOf(
                            "enabled" to settings.screenEnabled,
                            "active" to manager.isActive,
                            "scrcpy_version" to ScrcpyServerLauncher.SERVER_VERSION,
                            // 排障用：upstream_bytes 为 0 说明控制消息一条都没到 core；
                            // 有增长但设备无反应，则是 server 侧不认这些消息。
                            "downstream_bytes" to (stats?.first ?: 0L),
                            "upstream_bytes" to (stats?.second ?: 0L),
                            "pid" to (stats?.third ?: 0),
                        )
                    )
                )
            }
        }
    }

    /** WS 泵主循环（`HttpServer` 的 `webSocket("/ws/screen")` 调进来）。 */
    suspend fun proxy(clientSession: DefaultWebSocketServerSession) {
        // ── 闸一：握手验签 ──
        if (wsAuthenticator != null) {
            val q = clientSession.call.request.queryParameters
            val fp = wsAuthenticator.authenticate(
                token = q["token"],
                timestamp = q["ts"],
                nonce = q["nonce"],
                signature = q["sig"],
                path = clientSession.call.request.path(),
            )
            if (fp == null) {
                AppLogger.w(TAG, "WS screen: 握手验签失败, close=1008")
                clientSession.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication failed"))
                return
            }
        }

        // ── 闸二：票据 ──
        if (tickets.consume(clientSession.call.request.queryParameters["ticket"]) == null) {
            AppLogger.w(TAG, "WS screen: 票据无效/过期, close=1008")
            clientSession.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "screen ticket invalid or expired"))
            return
        }

        if (!settings.screenEnabled) {
            AppLogger.w(TAG, "WS screen: 开关关闭, close=1008")
            clientSession.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "screen disabled"))
            return
        }

        // ── 起会话（画面参数由 WS query 指定，服务端按区间钳制） ──
        val q = clientSession.call.request.queryParameters
        val params = ScrcpyServerLauncher.VideoParams.sanitize(
            size = q["maxsize"]?.toIntOrNull(),
            fps = q["fps"]?.toIntOrNull(),
            bitRate = q["bitrate"]?.toIntOrNull(),
        )
        val outcome = manager.start(params = params)
        val session = when (outcome) {
            is ScreenSessionManager.Outcome.Ready -> outcome.session
            is ScreenSessionManager.Outcome.Failed -> {
                // BUSY 与「设备/adb 暂不可用」都用 1013 TRY_AGAIN_LATER：对浏览器而言两者
                // 的下一步动作相同（等一会儿手动重试），区别只在给不给文案。真正的分类在
                // reason 里，写进日志；1008 留给凭据问题（重试无意义）。
                AppLogger.w(TAG, "WS screen: 起会话失败 ${outcome.reason} ${outcome.detail}, close=1013")
                // close reason 的 payload 上限是 125 **字节**（RFC 6455），中文按 UTF-8 一个字
                // 3 字节 —— 原来的 take(120) 是按字符截的，一条中文原因就能到 360 字节，
                // 超限的 close 帧会被浏览器丢掉（只剩 1006/1002），排障线索全没了。
                // 这里只放固定短码，详细原因留在上面的日志里。
                clientSession.close(
                    CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "screen start failed")
                )
                return
            }
        }

        AppLogger.w(TAG, "会话就绪 pid=${session.pid}，开始泵")
        try {
            pump(clientSession, session, )
        } finally {
            // 会话必须在这里收掉：socket 关了 server 才会退出（cleanup=true），
            // reverse 也才会被拆掉。传 session 进去，避免接管场景下误杀新会话。
            runCatching { manager.stop(session) }
        }
    }

    /**
     * 双向泵：视频下行、控制上行。
     *
     * 下行**不攒包**：`InputStream.read` 返回多少就发多少（≤32KB/帧），
     * 攒包等于给交互加延迟，这是"跟手"与否的第一决定因素。
     * `HttpServer` 的全局 `maxFrameSize = 64KB` 被 `/ws/realtime`、`/ws/terminal` 共用，
     * 这里**不改它**，靠分块满足。
     */
    /**
     * 双向泵。
     *
     * ★ 上行走**控制 socket**，下行走**视频 socket** —— 两条不同的连接
     * （scrcpy 的 DesktopConnection 在 connect 模式下为 video/control 各连一次）。
     * 写成一条的话表现是"画面正常、操作无反应"。
     */
    private suspend fun pump(
        clientSession: DefaultWebSocketServerSession,
        session: ScreenSessionManager.Session,
    ) {
        val input = session.videoSocket.getInputStream()
        val output = session.controlSocket.getOutputStream()

        val startedAt = System.currentTimeMillis()
        // 用 AtomicLong 而不是普通 var：它被两个协程（下行 / 看门狗）并发读写。
        val lastActivity = java.util.concurrent.atomic.AtomicLong(startedAt)
        // 限流：1 秒滑动窗口内的上行帧数。正常人手速 60~120 帧/秒已经很快了；
        // 超过说明客户端在刷屏（或写坏了），此时继续转发只会把设备 InputManager 打满。
        var windowStart = startedAt
        var windowFrames = 0

        var forwarded = 0L
        // 空闲断开：10 分钟双向都没有字节就收会话。
        // 注意"静态画面"本身不产生下行字节（scrcpy 只在内容变化时出帧），
        // 所以判据是**两个方向都静默**，不是"没有视频"。
        val idleWatchdog = clientSession.launch {
            while (isActive) {
                delay(IDLE_CHECK_MS)
                if (System.currentTimeMillis() - lastActivity.get() > IDLE_TIMEOUT_MS) {
                    AppLogger.w(TAG, "空闲超时，断开：pid=${session.pid}")
                    clientSession.close(
                        CloseReason(CloseReason.Codes.GOING_AWAY, "idle timeout")
                    )
                    break
                }
            }
        }

        val downstream = clientSession.launch(Dispatchers.IO) {
            val buf = ByteArray(32 * 1024)
            try {
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        clientSession.send(Frame.Binary(true, buf.copyOf(n)))
                        forwarded += n
                        session.downstreamBytes += n
                        lastActivity.set(System.currentTimeMillis())
                    }
                }
            } catch (e: Exception) {
                // ★ 这里原来只写着"正常退出路径"然后静默吞掉。实测下来，
                // 下行发不出去（帧太大 / WS 已关）与"对端正常断开"在日志里长得一模一样，
                // 而 release 构建又把 INFO 关掉了 —— 于是整条链只剩"没画面"三个字。
                // WARN 是唯一能穿透 release 日志开关的级别。
                AppLogger.w(TAG, "下行异常（已发 $forwarded 字节）：${e.javaClass.simpleName}: ${e.message}")
            }
            // 关闭原因里带上字节数：这是唯一一条不依赖日志开关的排障通道
            // （release 构建把 INFO 关了、这台设备的 AppLogger 连 WARN 都没落地）。
            // 0 字节 = 服务端连上就没说话；64 = 只发了设备名；≥76 = 流头完整。
            runCatching {
                clientSession.close(
                    CloseReason(CloseReason.Codes.GOING_AWAY, "scrcpy-eof bytes=$forwarded")
                )
            }
        }

        try {
            for (frame in clientSession.incoming) {
                if (frame !is Frame.Binary) {
                    // 只接受二进制。文本帧说明客户端不是我们的实现，直接拒。
                    AppLogger.w(TAG, "WS screen: 收到非二进制帧，close=1008")
                    clientSession.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "binary frames only"))
                    break
                }
                val now = System.currentTimeMillis()
                if (now - windowStart >= 1_000L) {
                    windowStart = now
                    windowFrames = 0
                }
                if (++windowFrames > MAX_INPUT_FRAMES_PER_SEC) {
                    AppLogger.w(TAG, "上行超过 $MAX_INPUT_FRAMES_PER_SEC 帧/秒，断开：pid=${session.pid}")
                    clientSession.close(
                        CloseReason(CloseReason.Codes.VIOLATED_POLICY, "input rate exceeded")
                    )
                    break
                }
                // 控制帧只放行"操作设备"的那几类。
                //
                // 风险：scrcpy 的控制类型里还有 UHID（虚拟键鼠）、START_APP（拉起任意应用）、
                // SET_DISPLAY_POWER（关屏）。它们到不了 shell，但持票客户端不该有这些能力。
                // 类型编号**跨 scrcpy 版本会变**，所以这里只拒绝少数几个已知危险值，
                // 命中就丢弃并计数 —— 宁可漏拦也不要误杀触控/键盘（0–4 自 v1 起没变过）。
                val type = frame.data.firstOrNull()?.toInt()?.and(0xFF)
                if (type != null && type in DENIED_CONTROL_TYPES) {
                    deniedControlFrames++
                    continue
                }
                withContext(Dispatchers.IO) {
                    output.write(frame.data)
                    output.flush()
                    session.upstreamBytes += frame.data.size
                }
                lastActivity.set(now)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "上行异常：${e.javaClass.simpleName}: ${e.message}")
        } finally {
            idleWatchdog.cancel()
            downstream.cancel()
            // 审计：会话时长与双向流量。不记画面内容（plan §4.1）。
            AppLogger.i(
                TAG,
                "投屏会话结束 pid=${session.pid} 时长=${(System.currentTimeMillis() - startedAt) / 1000}s " +
                    "上行=${session.upstreamBytes}B 下行=${session.downstreamBytes}B " +
                    "拒绝控制帧=$deniedControlFrames"
            )
        }
    }

    /**
     * 截图互斥位。
     *
     * 为什么是布尔而不是限流窗口：`/screenshot` 的客户端是"每 700ms 拉一张"的轮询，
     * 真正的风险不是频率而是**叠加**（一张没跑完又来一张，adb 通道被两条 `screencap` 抢）。
     * 用"同一时刻只跑一张"表达这个约束，比按秒计数更准确，也不会误伤正常的 1–2 fps。
     */
    private val shotInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 被拒绝的控制帧类型计数（排障用；日志在会话结束时打一条）。 */
    @Volatile
    private var deniedControlFrames = 0L

    /** 只认 PNG 魔数（89 50 4E 47 0D 0A 1A 0A）：拿到的必须真是图片才下发。 */
    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > PNG_MAGIC.size && PNG_MAGIC.indices.all { bytes[it] == PNG_MAGIC[it] }

    companion object {
        private const val TAG = "ScreenRoutes"

        /** 票据绑定的逻辑会话标识；投屏是单会话，固定值即可 */
        private const val SESSION_ID = "screen"

        /**
         * 拒绝的控制消息类型（scrcpy 基础编号）。
         *
         * 10 = SET_DISPLAY_POWER（关屏）、12/13/14 = UHID CREATE/INPUT/DESTROY（虚拟键鼠）、
         * 16 = START_APP（拉起任意应用）。**其余一律放行**：编号跨版本会漂移，
         * 用白名单误杀触控的代价比漏拦一条大得多。
         */
        private val DENIED_CONTROL_TYPES = setOf(10, 12, 13, 14, 16)

        /** 上行帧数上限（1 秒窗口）。60~120 已是极快的手速，超过就是客户端有问题 */
        private const val MAX_INPUT_FRAMES_PER_SEC = 240

        /** 空闲断开阈值：双向静默 10 分钟 */
        private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val IDLE_CHECK_MS = 30_000L

        private val PNG_MAGIC = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }
}
