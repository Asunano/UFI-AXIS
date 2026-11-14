package com.ufi_axis.data.screen

import java.net.URLEncoder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * 投屏 WS 客户端（2026-11-01，原生实现）。
 *
 * ## 职责边界
 * 只做「连接 + 搬运」：连 `/ws/screen`，字节交给 [ScreenStreamParser] 装配。
 * 装配（纯逻辑、可单测）、解码（[ScreenVideoDecoder]）、渲染（页面）三者互不知道对方。
 *
 * ## 一个必须记住的前提
 * 服务端把**一条** WS 当成两条 socket 用（视频下行 / 控制上行），因为 core 在中间做泵。
 * 所以控制消息直接 `send()` 到同一个 WS 即可，不需要第二条连接。
 *
 * ## 重连语义
 * 票据是**一次性**的：连接断了必须重新 `POST /api/screen/session` 换票再连。
 * 本类不自动重连 —— 由页面决定（用户点连接时重走一次票据流程）。
 */
class ScreenStreamClient(
    private val baseUrl: String,
    private val token: String,
    private val ticket: String,
    private val listener: Listener,
    /** 画面参数（会话启动参数，走 WS query；服务端按区间钳制） */
    private val params: VideoParams = VideoParams.DEFAULT,
) {

    interface Listener {
        /** 流头解析完成（设备名 / 编解码 / 分辨率） */
        fun onHeader(header: ScreenProtocol.StreamHeader)

        /** 编解码器配置（SPS/PPS），解码器要用它初始化 */
        fun onConfig(csd: ByteArray)

        /** 一帧画面。ptsUs 已剥掉标记位；keyframe = 这是 IDR（可用于丢帧后重同步） */
        fun onFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean)

        fun onClosed(code: Int, reason: String)

        fun onError(cause: Throwable)
    }

    /**
     * 画面参数。
     *
     * 这三项是**会话启动参数**，改了要重开会话。默认取设备原生上限（该机型 480×854，
     * 长边 848）+ 20fps + 2Mbps，与 core 侧 `ScrcpyServerLauncher` 的默认一致；
     * 只有硬解选择"降档"时才覆盖。
     */
    data class VideoParams(
        val maxSize: Int = 848,
        val maxFps: Int = 20,
        val bitRate: Int = 2_000_000,
    ) {
        companion object {
            val DEFAULT = VideoParams()

            /**
             * 硬解降档。
             *
             * 2026-11-01 在真机（ZTE F50）上实测：408×720 一帧的 YUV→ARGB 在本机 CPU 上
             * 要 **28ms**（20 次平均，纯 Kotlin，已复用 IntArray）。解码端是软件实现，
             * 转码端是纯 CPU，两项都不便宜，所以默认不拉满：
             * 640 长边（384×640 ≈ 24 万像素，约 60% 的像素量）+ 12fps + 1Mbps。
             *
             * 换算：手机端通常比这台随身 WiFi 快 2–5 倍，同样的循环约 6–14ms/帧，
             * 12fps 下占 8%–17% 的一个核；拉到 848 长边 @20fps 会翻倍还多。
             */
            val DECODE_SAVER = VideoParams(maxSize = 640, maxFps = 12, bitRate = 1_000_000)
        }
    }

    /** 字节装配器（页面/日志可读它做诊断：收到多少字节、几个包） */
    val parser = ScreenStreamParser(
        object : ScreenStreamParser.Sink {
            override fun onHeader(header: ScreenProtocol.StreamHeader) = listener.onHeader(header)
            override fun onConfig(csd: ByteArray) = listener.onConfig(csd)
            override fun onFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean) =
                listener.onFrame(data, ptsUs, keyframe)
            override fun onCorrupt(reason: String) {
                // 结构已错乱：继续收只会一直丢帧，断开并把原因交给上层（重连换票即可）
                listener.onError(IllegalStateException("视频流解析失败：$reason"))
                close()
            }
        }
    )

    /**
     * `readTimeout` 给 0：WS 是长连接，正常时可能几十秒没有数据（静态画面 scrcpy 不出帧）。
     * 靠 `pingInterval` 探活，而不是靠读超时把好连接掐掉。
     */
    // 复用**进程级**的一个 client：原先每个 `ScreenStreamClient` 实例都新建一个
    // OkHttpClient（自带 Dispatcher + ConnectionPool），切换投屏模式会叠加多个连接池，
    // 且 `close()` 只是关 WS，池与线程一直留着。
    private val client: OkHttpClient get() = SharedClient.instance

    private var ws: WebSocket? = null

    @Volatile
    private var closed = false

    val isClosed: Boolean get() = closed

    fun connect() {
        closed = false
        val path = "/ws/screen"
        // 与 PtySessionRepository 完全同一套：签名只覆盖 path，query 不参与
        val ts = com.ufi_axis.data.api.RetrofitClient.signedTimestampMs().toString()
        val nonce = com.ufi_axis.util.DeviceKeyStore.newNonce()
        val sig = com.ufi_axis.util.DeviceKeyStore
            .sign(com.ufi_axis.util.DeviceKeyStore.canonicalString("GET", path, ts, nonce))
            .orEmpty()
        // ★ baseUrl 以 "/" 结尾（`buildBaseUrl` 固定这么拼），这里必须先 trimEnd，
        // 否则得到 `ws://host:8088//ws/screen` —— 双斜杠匹配不上 WS 路由，
        // 握手退回 SPA 兜底（200 HTML），客户端表现为"一直连着但永远没数据"。
        val url = baseUrl.trimEnd('/').replace("http://", "ws://").replace("https://", "wss://") + path +
            "?token=${URLEncoder.encode(token, "UTF-8")}" +
            "&ts=$ts" +
            "&nonce=${URLEncoder.encode(nonce, "UTF-8")}" +
            "&sig=${URLEncoder.encode(sig, "UTF-8")}" +
            "&ticket=${URLEncoder.encode(ticket, "UTF-8")}" +
            // 画质参数在握手时定（会话启动参数），服务端钳制后按实际值回显
            "&maxsize=${params.maxSize}&fps=${params.maxFps}&bitrate=${params.bitRate}"

        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = parser.feed(bytes.toByteArray())

            override fun onMessage(webSocket: WebSocket, text: String) =
                // 我们只发二进制；收到文本说明对面不是我们的 core（或落在 SPA 兜底上）
                listener.onError(IllegalStateException("收到文本帧，服务端只应发二进制"))

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed) listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed) listener.onError(t)
            }
        })
    }

    /** 发一条控制消息。返回 false = 连接已不可用（调用方不要攒着）。 */
    fun send(payload: ByteArray): Boolean {
        if (closed) return false
        val socket = ws ?: return false
        return socket.send(payload.toByteString())
    }

    fun close() {
        closed = true
        ws?.let { runCatching { it.close(1000, "client closed") } }
        ws = null
        parser.reset()
    }

    /**
     * 投屏专用的共享 client（进程内一份）。
     *
     * `readTimeout = 0`：WS 是长连接，静态画面时 scrcpy 不出帧，几十秒没有数据是正常的，
     * 靠 `pingInterval` 探活，不能靠读超时把好连接掐掉。
     */
    private object SharedClient {
        val instance: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }
}
