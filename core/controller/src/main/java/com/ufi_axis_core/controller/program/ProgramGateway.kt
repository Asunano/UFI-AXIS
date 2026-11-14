package com.ufi_axis_core.controller.program

import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * AppGateway：给第三方程序的 Web UI 加一道门（2026-10-30 P0）。
 *
 * ## 为什么不做"子路径反代"
 *
 * OpenList / AdGuard 这类程序都以根路径提供服务，HTML 与 JS 里全是 `/assets/...`、
 * `/api/...` 这种绝对路径。挂到 core 的 `/apps/<id>/` 下必须重写 HTML/JS/302 Location，
 * 没有通用解 —— 做不干净的表现是"页面能开、按钮全 404"。
 * 而子域名方案在随身 WiFi 上没有 wildcard DNS。
 *
 * 所以这里**不改内容，只改入口**：程序只绑 `127.0.0.1`（内部），Gateway 绑
 * `0.0.0.0:<externalPort>`（对外），把通过鉴权的连接原样转发进去。
 *
 * ## 鉴权：cookie 而不是签名
 *
 * core 的 `/api` 要求每请求 ECDSA 签名，第三方 Web UI 不可能满足。所以走一条更弱的通道：
 * `POST /api/programs/{id}/open`（走完整签名）签发一个 cookie，
 * 浏览器之后访问 `:externalPort` 时会自动带上（**cookie 不区分端口**，这是方案成立的关键事实）。
 *
 * Gateway 只做三件事：读首包 → 验 cookie → 双向盲转发。
 * **不解析正文、不改写、不缓存** —— 不碰内容就不会跟 chunked/压缩/WebSocket 打架。
 *
 * ## 不安全的部分要说清楚
 *
 * cookie 是一个固定值（HMAC(secret, appId)），有效期内**任何拿到它的人**都能访问。
 * 它不是"每请求授权"，只是"进门一次"。所以：
 * - 程序自身的登录仍然是第一道门（OpenList 有 admin 口令）；
 * - cookie 24h 过期；
 * - 想立刻吊销 → 换 `program_gateway_secret`（core 重启 + 重新打开即可）。
 */
class ProgramGateway(
    private val appId: String,
    /** 对外监听端口（manifest.run.externalPort） */
    private val externalPort: Int,
    /** 程序真实监听端口（127.0.0.1） */
    private val internalPort: Int,
    /**
     * cookie 签名密钥。
     *
     * 原先这里存的是**一个固定值**（`HMAC(secret, appId)`）：服务端只做字符串相等比较，
     * 值本身不含时间戳 → 只要 secret 不变，抓到的 cookie **永久有效**，也没有吊销入口。
     * 现在存密钥，校验收到的值里的过期时间与签名（见 [ProgramGatewaySecret.verify]）。
     */
    private val secret: ByteArray,
    private val scope: CoroutineScope
) {

    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private val running = AtomicBoolean(false)
    private val activeConnections = AtomicInteger(0)

    val isRunning: Boolean get() = running.get()

    /**
     * 启动监听。端口被占时会失败并返回原因（不抛异常 —— 端口冲突不该让程序启动失败）。
     */
    fun start(): Result<Unit> = runCatching {
        if (running.get()) return@runCatching
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), externalPort), BACKLOG)
        server = ss
        running.set(true)

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && running.get()) {
                val client = try {
                    ss.accept()
                } catch (e: IOException) {
                    break
                }
                // 并发上限：低端设备上几十个闲置连接就能把内存吃掉
                if (activeConnections.get() >= MAX_CONNECTIONS) {
                    runCatching { client.close() }
                    continue
                }
                activeConnections.incrementAndGet()
                launch { handle(client) }
            }
        }
        AppLogger.i(TAG, "[$appId] Gateway 已监听 0.0.0.0:$externalPort → 127.0.0.1:$internalPort")
    }.onFailure {
        AppLogger.w(TAG, "[$appId] Gateway 启动失败（端口 $externalPort 可能被占）: ${it.message}")
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        runCatching { acceptJob?.cancel() }
        server = null
        acceptJob = null
    }

    // ── 连接处理 ──

    private suspend fun handle(client: Socket) {
        try {
            client.soTimeout = HEAD_TIMEOUT_MS
            client.tcpNoDelay = true
            val head = readHead(client.getInputStream())

            if (head == null) {
                // 首包都没读全：慢速攻击或客户端提前断开。直接关，不回应。
                return
            }
            if (!hasValidCookie(head)) {
                writeUnauthorized(client.getOutputStream())
                return
            }

            // 首包读完必须**取消**读超时：`soTimeout` 是整条 socket 的，不是单次读的。
            // 留着 5s 会让上行泵 `copyTo` 在空闲 5 秒后抛 SocketTimeoutException →
            // 下面的 `backend.shutdownOutput()` 把后端半关 —— Gateway 模式的程序
            // （OpenList / AdGuard 的 WebSocket、SSE）空闲几秒就断。

            // 鉴权通过：连后端，把已读到的首包**原样**发过去，然后双向盲转发
            client.soTimeout = 0
            Socket().use { backend ->
                backend.tcpNoDelay = true
                backend.connect(InetSocketAddress("127.0.0.1", internalPort), BACKEND_TIMEOUT_MS)
                backend.getOutputStream().apply { write(head); flush() }

                val up = scope.launch(Dispatchers.IO) {
                    runCatching { client.getInputStream().copyTo(backend.getOutputStream(), BUFFER) }
                    runCatching { backend.shutdownOutput() }
                }
                val down = scope.launch(Dispatchers.IO) {
                    runCatching { backend.getInputStream().copyTo(client.getOutputStream(), BUFFER) }
                    runCatching { client.shutdownOutput() }
                }
                // 任一方向结束就收尾：半关闭的 HTTP 连接留在那儿只会占 fd
                up.join()
                down.join()
            }
        } catch (_: Exception) {
            // 连接级异常不值得记日志（浏览器刷新、中途取消都是常态）
        } finally {
            runCatching { client.close() }
            activeConnections.decrementAndGet()
        }
    }

    /**
     * 读到 `\r\n\r\n` 为止，最多 [MAX_HEAD_BYTES]。
     *
     * 返回**包含已读到的全部字节**（不只是头）：HTTP 的 body 可能紧跟在同一批数据里，
     * 只转头部会把 body 的前几个字节吞掉，表现是"页面能开但某个请求校验失败"。
     */
    private fun readHead(input: InputStream): ByteArray? {
        val buf = ByteArray(MAX_HEAD_BYTES)
        var len = 0
        while (len < MAX_HEAD_BYTES) {
            val n = input.read(buf, len, MAX_HEAD_BYTES - len)
            if (n < 0) return if (len > 0) buf.copyOf(len) else null
            len += n
            // 找 \r\n\r\n
            var i = (len - n - 3).coerceAtLeast(0)
            while (i + 3 < len) {
                if (buf[i] == 13.toByte() && buf[i + 1] == 10.toByte() &&
                    buf[i + 2] == 13.toByte() && buf[i + 3] == 10.toByte()
                ) {
                    return buf.copyOf(len)
                }
                i++
            }
        }
        return buf.copyOf(MAX_HEAD_BYTES)
    }

    private fun hasValidCookie(head: ByteArray): Boolean {
        val text = String(head, Charsets.ISO_8859_1)
        val value = GatewayCookieHeader.find(text, cookieName(appId)) ?: return false
        return ProgramGatewaySecret.verify(secret, appId, value)
    }

    private fun writeUnauthorized(output: OutputStream) {
        val body = """
            <!doctype html><meta charset="utf-8">
            <title>需要访问凭证</title>
            <body style="font-family:system-ui;padding:40px;line-height:1.6">
            <h2>需要访问凭证</h2>
            <p>请从 UFI-AXIS 控制台的「程序」页面点击「打开」进入。</p>
            <p style="opacity:.6;font-size:13px">这个端口只接受控制台签发的访问凭证，直连会被拒绝。</p>
            </body>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val resp = buildString {
            append("HTTP/1.1 401 Unauthorized\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        runCatching {
            output.write(resp)
            output.write(body)
            output.flush()
        }
    }

    companion object {
        private const val TAG = "ProgramGateway"

        /**
         * cookie 名（按程序区分）。
         *
         * Cookie **不区分端口**（RFC 6265），而 Gateway 模式的每个程序是同一 host 的不同端口：
         * 用固定的 `__ufi_prog` 时，打开程序 B 会覆盖 A 的 cookie —— 回到 A 的标签页就 401。
         * 多程序并存是产品支持的场景，所以名字里带 appId。
         *
         * 只保留 cookie token 允许的字符（字母数字与 `_-`），避免 appId 里的怪字符
         * 让 Set-Cookie 整条失效。
         */
        fun cookieName(appId: String): String {
            val safe = appId.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
                .joinToString("")
            return "__ufi_prog_$safe"
        }

        private const val BACKLOG = 32
        private const val MAX_CONNECTIONS = 48
        private const val MAX_HEAD_BYTES = 8192
        private const val HEAD_TIMEOUT_MS = 5_000
        private const val BACKEND_TIMEOUT_MS = 3_000
        private const val BUFFER = 16 * 1024
    }
}

/**
 * 从 HTTP 头里取某个 cookie 的值。
 *
 * 两个必须做对的地方（2026-11-14 审查发现）：
 * 1. **只在头部块内查找**。`readHead()` 返回的是"头 + 已经读到的那部分 body"，
 *    原先在整段字节里 `indexOf("cookie:")` —— 请求体里出现同样的字样就会被当成已授权，
 *    授权判定越出了 header。
 * 2. **第一个 cookie 也要认得**。原先从 `cookie:` 处截取，正则要求值前是 `^` 或 `;`/`,`，
 *    于是 `Cookie: __ufi_prog_x=v1…`（只有一个 cookie，即首次访问）永远匹配不上 → 401。
 *    现在按 `Cookie:` 行 + `;` 分隔逐段比对。
 *
 * @return 匹配到的值；没有该 cookie 或格式不对返回 null
 */
internal object GatewayCookieHeader {
    fun find(headText: String, name: String): String? {
        val headerEnd = headText.indexOf("\r\n\r\n").let { if (it < 0) headText.length else it }
        val headers = headText.substring(0, headerEnd)
        for (line in headers.split("\r\n")) {
            if (!line.startsWith("Cookie:", ignoreCase = true)) continue
            val value = line.substringAfter(':', "")
            for (part in value.split(';')) {
                val kv = part.trim()
                if (!kv.startsWith("$name=")) continue
                val v = kv.substringAfter('=', "").trim()
                if (v.isNotEmpty()) return v
            }
        }
        return null
    }
}

/**
 * cookie 值 / 票据的生成。
 *
 * 用 HMAC-SHA256（JDK 自带 `javax.crypto.Mac`，不引新依赖）。
 * 值里带 appId，所以**不同程序之间不能串用**。
 */
object ProgramGatewaySecret {

    /** cookie 有效期（秒）。与路由侧 Set-Cookie 的 Max-Age 保持一致。 */
    const val TTL_SECONDS: Long = 24 * 3600

    /**
     * 生成某个程序的 cookie 值：`v1.<过期时间戳>.<HMAC(secret, "app:<id>|<ts>") 前 32 位>`。
     *
     * 带上过期时间之后，值才有"到期"这个属性 —— 固定值时代的 cookie 一旦被抓到就是长期凭证。
     */
    fun cookieValue(secret: ByteArray, appId: String, ttlSeconds: Long = TTL_SECONDS): String {
        val exp = (System.currentTimeMillis() / 1000L) + ttlSeconds
        return "v1.$exp." + hmacHex(secret, "app:$appId|$exp").take(32)
    }

    /** 校验 cookie 值：格式、过期时间、签名。任一不符即 false。 */
    fun verify(secret: ByteArray, appId: String, value: String?): Boolean {
        if (secret.isEmpty() || value.isNullOrBlank()) return false
        val parts = value.split('.')
        if (parts.size != 3 || parts[0] != "v1") return false
        val exp = parts[1].toLongOrNull() ?: return false
        if (exp <= System.currentTimeMillis() / 1000L) return false
        val expected = hmacHex(secret, "app:$appId|$exp").take(32)
        // 定长比较：避免按字节提前返回带来的时间侧信道
        return java.security.MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            parts[2].toByteArray(Charsets.US_ASCII),
        )
    }

    private fun hmacHex(secret: ByteArray, message: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
