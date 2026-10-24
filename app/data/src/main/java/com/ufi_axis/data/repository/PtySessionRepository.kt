package com.ufi_axis.data.repository

import com.ufi_axis.util.DebugLog
import com.ufi_axis.util.DeviceKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okio.ByteString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 真 PTY 终端会话（app 端原生，2026-10-10）。
 *
 * ## 定位
 * app 此前只有 `DeviceControlScreen` 里的开关 + 状态，没有交互终端（ttyd 那套仅 web 端有）。
 * 本类是 app 侧的最小实现：**不做 ANSI 渲染器**，只把 ttyd 的输出字节流按行累积成纯文本，
 * 输入走一个单行输入框 + 发送键。够跑命令、看输出，不模拟全屏程序（vim/top 不适用）。
 *
 * 刻意**不复用** `WebSocketRepository`：那是 /ws/realtime 的推送通道，带订阅主题、
 * 重连退避、data_changed 扇出等一堆与终端无关的状态；终端的协议（帧指令）、生命周期
 * （页面级开关）、错误语义（1008=重签、1013=可重试）都不同。塞进去会变成两头都难维护的
 * 巨类，这正是 2026-10-10 用户裁决「别堆屎山」要避免的。
 *
 * ## 协议（ttyd 1.7.x，权威定义见 ttyd 源码 `src/server.h`）
 * 帧是「1 个 ASCII 命令码 + 载荷」的**二进制**帧，不是 JSON：
 * - client → ttyd：`'{'` JSON_DATA 握手（`{"AuthToken":"","columns":N,"rows":N}`）
 *   → `'0'` INPUT（原始按键字节）→ `'1'` RESIZE_TERMINAL（`{"columns":N,"rows":N}`）
 * - ttyd → client：`'0'` OUTPUT（终端输出字节）→ `'1'` SET_WINDOW_TITLE → `'2'` SET_PREFERENCES
 *
 * ⚠️ 2026-10-10 修正：此前本类按 `["1", data]` / `["2", data]` 的 **JSON 数组**帧实现 ——
 * 那是 ttyd **1.6 及更早**的老协议。本机 ttyd 是 **1.7.7**，数组帧被它当垃圾丢弃，
 * 表现是连接建立但终端全空白、完全无法输入（r32 真机实锤）。
 * 尺寸字段名是 `columns`（不是老代码里的 `cols`）。与 web 端 `PtyPane.vue` 同源。
 *
 * ## 鉴权
 * `/ws/` 前缀在 core 的 AuthMiddleware 里整体豁免，路由自己做验签：
 * ① query 签名（token/ts/nonce/sig，与 /ws/realtime 同参同名，DeviceKeyStore 同算法）
 * ② PTY 票据（`POST /api/terminal/pty-ticket` 换来的，走标准头部鉴权）
 * ③ `ttyd_enabled` 开关。任一不过 → close 1008/1013。
 *
 * @param baseUrl core 的根 URL（含 scheme+host+port，尾斜杠可有无）
 * @param token  设备 API token（与 Retrofit 同一份）
 */
class PtySessionRepository(
    private val baseUrl: String,
    private val token: String,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        // 终端是长连接：读写超时必须放大（默认 60s 读超时会把空闲终端判死并静默断开）。
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val closing = AtomicBoolean(false)

    /** 输出缓冲（纯文本，按行累积）。上限 [MAX_LINES]，超出丢最旧 —— 长时间会话不能无限涨。 */
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var job: Job? = null

    /** 已连接的会话是否还有效（供 UI 决定要不要重连）。 */
    fun isOpen(): Boolean = webSocket != null && !closing.get()

    /**
     * 连接：先换票据再握手。
     *
     * @param ticket `POST /api/terminal/pty-ticket` 的返回值；为空表示还没签发，UI 应先调签发端点。
     */
    fun connect(scope: CoroutineScope, ticket: String) {
        disconnect()
        closing.set(false)
        _error.value = null
        _lines.value = emptyList()

        val path = "/ws/terminal"
        // 2026-10-21（审计 §A5）：与 HTTP / WS 用同一个"本机 + 观测偏差"的时间戳。
        // core 的握手验签对这条通道用的是同一个 ±5 分钟窗口，裸本机时钟在设备时钟漂移时必失败。
        val ts = com.ufi_axis.data.api.RetrofitClient.signedTimestampMs().toString()
        val nonce = DeviceKeyStore.newNonce()
        val sig = DeviceKeyStore.sign(DeviceKeyStore.canonicalString("GET", path, ts, nonce)).orEmpty()
        val url = baseUrl.replace("http://", "ws://").replace("https://", "wss://") + path +
            "?token=${java.net.URLEncoder.encode(token, "UTF-8")}" +
            "&ts=$ts" +
            "&nonce=${java.net.URLEncoder.encode(nonce, "UTF-8")}" +
            "&sig=${java.net.URLEncoder.encode(sig, "UTF-8")}" +
            "&ticket=${java.net.URLEncoder.encode(ticket, "UTF-8")}"

        DebugLog.d("PTY", "connecting $url")
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                _connected.value = true
                _error.value = null
                // 握手帧：'{' + {"AuthToken":"","columns":..,"rows":..}。ttyd 收到它才 fork pty。
                // 尺寸必须给 —— 缺了 shell 会按 0×0 起（提示符错位 / 无回显）。
                // AuthToken 留空：鉴权已在 core 反代层完成，ttyd 只绑 127.0.0.1。
                sendFrame("""{"AuthToken":"","columns":80,"rows":24}""")
            }

            /**
             * ttyd 下行帧回调。
             *
             * ⚠️ 2026-10-10 修（r33 真机实锤：连接成功但终端全空白、零输出）：
             * ttyd 用 `LWS_WRITE_BINARY` 发帧 —— OkHttp 走的是 **ByteString 重载**，
             * 不会触发 `onMessage(ws, text: String)`。我们之前只实现了 String 版，
             * 于是所有 OUTPUT 帧被 OkHttp 静默丢弃：状态显示「已连接」，但一个字都没有。
             *
             * 两个重载都实现：binary 走实际路径，text 版留作兼容（万一将来有代理把帧转成文本）。
             * 字节要按 UTF-8 解码 —— ttyd 直接吐 pty 的原始字节，非法 UTF-8 序列用替换符兜底
             * （不能抛异常，否则一条坏字节就中断整个会话）。
             */
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val output = parseOutputFrame(bytes.toByteArray().toString(Charsets.UTF_8)) ?: return
                if (output.isEmpty()) return
                appendOutput(output)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val output = parseOutputFrame(text) ?: return
                if (output.isEmpty()) return
                appendOutput(output)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _connected.value = false
                DebugLog.d("PTY", "closed code=$code reason=$reason")
                if (code == 1008) _error.value = "鉴权或票据失效（code 1008），请重新进入终端"
                else if (code == 1013) _error.value = "设备端 ttyd 未就绪（code 1013），稍后重试"
                else if (code != 1000) _error.value = "连接已断开（code $code）"
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _connected.value = false
                DebugLog.d("PTY", "failure ${t.javaClass.simpleName}: ${t.message}")
                _error.value = "连接失败：${t.message ?: t.javaClass.simpleName}"
            }
        })
    }

    /** 发送一行输入（自动补回车）。UI 的「发送」按钮走这里。 */
    fun sendLine(scope: CoroutineScope, line: String) {
        if (!isOpen()) {
            _error.value = "未连接"
            return
        }
        // ttyd 输入指令：["2", data]，回车由调用方拼进 line（Compose 单行输入框收不到裸 Enter）
        sendInput(line + "\r")
    }

    /** 发送裸按键序列（方向键 / Ctrl-C 等控制字符用）。UI 的控制按钮走这里。 */
    fun sendRaw(data: String) {
        if (!isOpen()) return
        sendInput(data)
    }

    /**
     * 发送一条 INPUT 帧：`'0'` 命令码 + 原始按键字节。
     *
     * ttyd 1.7.x 的 client INPUT 就是 `'0'` + **原始字节**，不做任何 JSON 包装
     * （老协议 `["2", data]` 的数组包装是 1.6 的做法，在 1.7.7 上是无效帧）。
     * 所以这里**不要**转义引号/反斜杠——回车、Ctrl-C(0x03)、Tab(0x09) 都是裸字节。
     */
    private fun sendInput(data: String) {
        sendFrame(TTYD_INPUT + data)
    }

    /** 清屏（本地清缓冲，不动设备侧）。 */
    fun clearScreen() {
        _lines.value = emptyList()
    }

    fun disconnect() {
        closing.set(true)
        webSocket?.close(NORMAL_CLOSURE, "bye")
        webSocket = null
        _connected.value = false
    }

    private fun sendFrame(frame: String) {
        val ws = webSocket
        if (ws == null) {
            _error.value = "未连接"
            return
        }
        ws.send(frame)
    }

    /**
     * 解析下行帧，返回 OUTPUT 的载荷（首字符是命令码，载荷从第 2 字符起）。
     *
     * ttyd 1.7.x 下行帧：`'0'` OUTPUT（终端输出字节，要写进终端）
     * / `'1'` SET_WINDOW_TITLE / `'2'` SET_PREFERENCES（后两者不消费，丢弃）。
     *
     * ⚠️ 老实现按 `["1", data]` JSON 数组解析 —— 那是 ttyd 1.6 的协议，本机 1.7.7
     * 根本不发这种帧，导致终端永远空白（2026-10-10 r32 真机实锤）。
     */
    private fun parseOutputFrame(text: String): String? {
        if (text.isEmpty()) return null
        return when (text[0]) {
            '0' -> text.substring(1) // OUTPUT
            '1', '2' -> null // SET_WINDOW_TITLE / SET_PREFERENCES：不消费
            else -> null // 未知命令码：丢弃，不让一条脏帧炸掉整个会话
        }
    }

    /**
     * 累积输出：ttyd 送的是**字节流片段**，一次 `["1", data]` 可能只有半个行、
     * 也可能一次带几百行。所以先按 `\n` 切并把不完整尾行留作前缀（见 pending），
     * 而不是每次都 `lines + data`（那样控制字符/回车覆盖不回去，且行会被截断）。
     */
    private var pending = StringBuilder()

    private fun appendOutput(chunk: String) {
        pending.append(chunk)
        val text = pending.toString()
        // ANSI 转义序列（CSI/OSC）先剥掉再分行：不做终端渲染，但残留的 ESC 字符
        // 会让 Compose 文本显示成乱码方块。**只做这一件最小处理**，不做光标定位/颜色。
        val cleaned = stripAnsi(text)
        val parts = cleaned.split("\n")
        // 最后一段没有换行符 = 还没收完，留作前缀等下一片
        pending = StringBuilder(parts.last())
        val completed = parts.dropLast(1)
        val complete = completed.filter { it.isNotEmpty() }
        if (complete.isEmpty()) return
        _lines.value = (_lines.value + complete).takeLast(MAX_LINES)
    }

    /** 剥 ANSI 控制序列（ESC [ … 字母 / OSC … BEL）。 */
    private fun stripAnsi(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\u001B') {
                i++
                if (i < s.length && s[i] == '[') {
                    i++
                    while (i < s.length && !s[i].isLetter()) i++
                    if (i < s.length) i++ // 吃掉终结字母
                } else if (i < s.length && s[i] == ']') {
                    // OSC：直到 BEL(0x07) 或 ST(ESC \)
                    i++
                    while (i < s.length && s[i] != '\u0007') {
                        if (s[i] == '\u001B' && i + 1 < s.length && s[i + 1] == '\\') { i++; break }
                        i++
                    }
                    if (i < s.length) i++
                } else {
                    // 单字符转义（如 ESC(B），吃掉紧随的一个字符
                    if (i < s.length) i++
                }
            } else if (c == '\r') {
                // 回车：行内覆盖（进度条类）。我们无光标模型，直接忽略（保留后续字符）
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    companion object {
        private const val MAX_LINES = 500
        private const val NORMAL_CLOSURE = 1000

        /**
         * ttyd 1.7.x client→server 命令码（权威定义见 ttyd `src/server.h`）：
         * `'0'` INPUT / `'1'` RESIZE_TERMINAL / `'2'` PAUSE / `'3'` RESUME / `'{'` JSON_DATA。
         * server→client 是另一套同数字的码（`'0'` OUTPUT / `'1'` SET_WINDOW_TITLE /
         * `'2'` SET_PREFERENCES）——**两边数字撞车但含义不同**，别混用。
         */
        private const val TTYD_INPUT = '0'
    }
}