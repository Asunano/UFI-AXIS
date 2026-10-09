package com.ufi_axis.data.repository

import com.ufi_axis.util.DebugLog
import com.ufi_axis.util.DeviceKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
 * ## 协议（ttyd 1.7 协议，JSON 数组指令流）
 * - 上行握手：`["0", "{}", ""]` → 请求初始输出（auth token 留空，鉴权在 core 反代层已完成）
 * - 上行输入：`["2", "<按键数据>"]`
 * - 下行输出：`["1", "<文本>"]`
 * - 下行其它指令（4/5/6 等扩展）不消费，丢弃。
 * 与 web 端 `PtyPane.vue` 完全同源（同一份协议，不是另发明一套）。
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
        val ts = System.currentTimeMillis().toString()
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
                // 握手指令：请求初始输出。auth token 传空串 —— 鉴权已在反代层完成。
                sendFrame("""["0","{}",""]""")
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
     * 构造并发送一条 ttyd 输入指令 `["2", data]`。
     * 手工拼串而非 kotlinx JsonArray：协议形态固定两个元素，手拼可读性更好，
     * 只需对 data 做 JSON 字符串转义（控制字符按 \u00XX 输出）。
     */
    private fun sendInput(data: String) {
        sendFrame("""["2",${jsonString(data)}]""")
    }

    /** JSON 字符串字面量转义（引号/反斜杠/控制字符/换行回车）。 */
    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\r' -> sb.append("\\r")
                ch == '\n' -> sb.append("\\n")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
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
     * 解析下行帧，取出 `["1", data]` 里的 data。
     * 非 JSON / 非输出帧（4/5/6 等扩展指令）返回 null 丢弃 —— 与 web 端同一处理策略。
     */
    private fun parseOutputFrame(text: String): String? = runCatching {
        val arr = kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray
            ?: return null
        if (arr.size < 2) return null
        val cmd = (arr[0] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (cmd != "1") return null
        (arr[1] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }.getOrNull()

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
    }
}