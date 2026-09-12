package com.ufi_axis.adbcore

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ADB over TCP 连接。
 *
 * 负责：
 * 1. TCP 连接 + CNXN/AUTH 握手
 * 2. 单 reader 线程解析所有入站报文并分发到对应 [AdbStream]
 * 3. 写路径串行化（防多线程撕裂报文）
 *
 * 线程模型：reader 线程只做「解析 + 投递」，绝不阻塞在业务逻辑里。
 */
class AdbConnection(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val crypto: AdbCrypto,
    private val connectTimeoutMs: Int = 10_000,
    private val handshakeTimeoutMs: Long = 15_000,
    /** 需要用户到设备上点「允许」时的回调 */
    private val onAwaitingAuth: (() -> Unit)? = null
) {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null

    /** 写锁：保证一个报文不会被两个线程交叉写坏 */
    private val writeLock = ReentrantLock()

    private val nextLocalId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, AdbStream>()

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var closed = false

    /** 握手期用于接收 CNXN/AUTH，由 reader 线程投递 */
    private val handshakeLatch = CountDownLatch(1)

    @Volatile
    private var handshakeHeader: AdbHeader? = null
    @Volatile
    private var handshakePayload: ByteArray = AdbProtocol.EMPTY
    @Volatile
    private var handshakeError: Exception? = null

    /** 与对端协商后的最大分片大小 */
    @Volatile
    var maxData: Int = AdbProtocol.MAX_DATA
        private set

    /** 对端 banner，形如 `device::xxx` */
    @Volatile
    var peerBanner: String = ""
        private set

    /** 对端上报的能力（CNXN banner 里 `features=...` 段，逗号分隔） */
    @Volatile
    var peerFeatures: Set<String> = emptySet()
        private set

    val isConnected: Boolean get() = !closed && socket?.isConnected == true

    /** 设备是否支持 shell v2（stdin 流式安装 `pm install -S` 依赖它） */
    fun supportsShellV2(): Boolean = peerFeatures.contains("shell_v2")

    /** 从 CNXN banner 解析 `features=...` 段（逗号分隔的能力列表） */
    private fun parseFeatures(banner: String): Set<String> {
        val body = banner.substringAfter("::", banner)
        return body.split(';')
            .firstOrNull { it.startsWith("features=") }
            ?.substringAfter("features=")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    // ------------------------------------------------------------------
    // 连接与握手
    // ------------------------------------------------------------------

    /** 建立 TCP 连接并完成 ADB 握手。失败抛 [AdbConnectException] / [AdbHandshakeException]。 */
    fun connect() {
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket = s
            input = DataInputStream(s.getInputStream())
            output = s.getOutputStream()
        } catch (e: Exception) {
            throw AdbConnectException(
                "无法连接到 $host:$port —— 请检查：设备已开启 ADB over TCP、" +
                    "手机与设备在同一网络、设备上如有授权弹窗请点允许。（${e.message}）",
                e
            )
        }

        try {
            doHandshake()
        } catch (e: AdbException) {
            closeQuietly()
            throw e
        } catch (e: java.io.EOFException) {
            // 握手期对端直接断开：通常意味着鉴权未被接受（例如连续拒绝授权）
            closeQuietly()
            throw AdbAuthRejectedException(
                "设备在握手阶段断开了连接。请确认已在设备上允许 USB 调试授权，" +
                    "或该设备是否已有其他 adb 客户端占用。（${e.message}）"
            )
        } catch (e: java.net.SocketException) {
            closeQuietly()
            throw AdbHandshakeException("握手期间连接被中断：${e.message}", e)
        } catch (e: Exception) {
            closeQuietly()
            throw e
        }

        startReader()
    }

    private fun doHandshake() {
        // 1. 发 CNXN
        val banner = "host::installer\u0000"
        sendRaw(
            AdbProtocol.encode(
                AdbProtocol.CMD_CNXN,
                AdbProtocol.A_VERSION,
                AdbProtocol.MAX_DATA,
                banner.toByteArray(Charsets.UTF_8)
            )
        )

        // 2. 同步读握手报文（此时 reader 线程尚未启动，直接读 socket）
        var tokenRound = 0
        val deadline = System.currentTimeMillis() + handshakeTimeoutMs

        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw AdbHandshakeException("ADB 握手超时（${handshakeTimeoutMs}ms）")

            val frame = try {
                readFrame(remaining)
            } catch (e: java.io.EOFException) {
                if (tokenRound > 0) throw AdbAuthRejectedException()
                throw AdbHandshakeException("设备在握手阶段关闭了连接", e)
            }
            when (frame.header.cmd) {
                AdbProtocol.CMD_CNXN -> {
                    maxData = if (frame.header.peerMaxData in 1024..(4 * 1024 * 1024)) {
                        minOf(AdbProtocol.MAX_DATA, frame.header.peerMaxData)
                    } else {
                        AdbProtocol.MAX_DATA
                    }
                    peerBanner = AdbProtocol.toAsciiTrimmed(frame.payload)
                    peerFeatures = parseFeatures(peerBanner)
                    return
                }

                AdbProtocol.CMD_AUTH -> {
                    when (frame.header.arg0) {
                        AdbProtocol.AUTH_TOKEN -> {
                            tokenRound++
                            when (tokenRound) {
                                // 第一次：用私钥签名 token
                                1 -> {
                                    if (frame.payload.size != AdbProtocol.TOKEN_SIZE) {
                                        throw AdbHandshakeException(
                                            "AUTH token 长度异常：${frame.payload.size}（期望 20）"
                                        )
                                    }
                                    val signature = crypto.signToken(frame.payload)
                                    sendRaw(
                                        AdbProtocol.encode(
                                            AdbProtocol.CMD_AUTH,
                                            AdbProtocol.AUTH_SIGNATURE,
                                            0,
                                            signature
                                        )
                                    )
                                }
                                // 第二次：签名未被设备接受 → 提交公钥，等用户在设备上点允许
                                2 -> {
                                    onAwaitingAuth?.invoke()
                                    sendRaw(
                                        AdbProtocol.encode(
                                            AdbProtocol.CMD_AUTH,
                                            AdbProtocol.AUTH_RSAPUBLICKEY,
                                            0,
                                            crypto.publicKeyPayload()
                                        )
                                    )
                                }
                                // 第三次：用户拒绝或超时
                                else -> throw AdbAuthRejectedException()
                            }
                        }
                        else -> throw AdbHandshakeException(
                            "收到未知的 AUTH 类型：${frame.header.arg0}"
                        )
                    }
                }

                AdbProtocol.CMD_STLS -> throw AdbTlsRequiredException()

                AdbProtocol.CMD_CLSE -> throw AdbConnectionClosedException(
                    "设备在握手阶段关闭了连接（可能已有其他 adb 客户端占用）"
                )

                else -> {
                    // 容错：忽略未知报文
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Reader 线程
    // ------------------------------------------------------------------

    private fun startReader() {
        val t = Thread({ readerLoop() }, "adb-reader-$host:$port")
        t.isDaemon = true
        t.start()
        readerThread = t
    }

    private fun readerLoop() {
        try {
            while (!closed) {
                val frame = readFrame(0)
                dispatch(frame)
            }
        } catch (e: Exception) {
            if (!closed) {
                // 连接异常断开：唤醒所有等待者
                failAllStreams(e)
            }
        } finally {
            closed = true
            failAllStreams(AdbConnectionClosedException("ADB 连接已断开"))
            handshakeLatch.countDown()
        }
    }

    private fun dispatch(frame: AdbFrame) {
        val h = frame.header
        when (h.cmd) {
            AdbProtocol.CMD_WRTE -> {
                // arg1 = 我们的 local id
                val stream = streams[h.arg1]
                stream?.onData(frame.payload)
                // 回 OKAY 确认（arg0=我方 local id, arg1=对端 remote id），否则对端会停止发送
                if (stream != null) {
                    sendSafe(
                        AdbProtocol.encode(AdbProtocol.CMD_OKAY, stream.localId, stream.remoteId)
                    )
                }
            }

            AdbProtocol.CMD_OKAY -> {
                // 可能是对某条 OPEN 的应答，也可能是普通数据确认
                if (pending.containsKey(h.arg1)) {
                    handleOpenReply(h)
                }
                // 普通确认无需额外处理
            }

            AdbProtocol.CMD_CLSE -> {
                // 对 OPEN 的拒绝
                if (pending.containsKey(h.arg1)) {
                    handleOpenReply(h)
                    return
                }
                streams[h.arg1]?.let { s ->
                    s.onClose()
                    streams.remove(h.arg1)
                    // 回 OKAY 完成关闭握手
                    sendSafe(AdbProtocol.encode(AdbProtocol.CMD_OKAY, s.localId, s.remoteId))
                }
            }

            AdbProtocol.CMD_OPEN -> {
                // 本工具只作为客户端，不接受对端反向开流
                sendSafe(AdbProtocol.encode(AdbProtocol.CMD_CLSE, 0, h.arg1))
            }

            AdbProtocol.CMD_CNXN, AdbProtocol.CMD_AUTH -> {
                // 握手期已处理，运行期收到属异常，忽略
            }

            else -> Unit
        }
    }

    private fun failAllStreams(cause: Throwable) {
        val it = streams.values.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.onClose()
            it.remove()
        }
    }

    // ------------------------------------------------------------------
    // 报文读写
    // ------------------------------------------------------------------

    private class AdbFrame(val header: AdbHeader, val payload: ByteArray)

    /** 读一个完整报文；timeoutMs<=0 表示使用 socket 默认（阻塞） */
    private fun readFrame(timeoutMs: Long): AdbFrame {
        val ins = input ?: throw AdbConnectionClosedException("连接已关闭")
        if (timeoutMs > 0) {
            socket?.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
        }
        val headerBuf = ByteArray(AdbProtocol.HEADER_SIZE)
        ins.readFully(headerBuf)
        val header = AdbProtocol.decodeHeader(headerBuf)
        if (!AdbProtocol.verifyMagic(header)) {
            throw AdbProtocolException(
                "报文 magic 校验失败：cmd=${AdbProtocol.cmdName(header.cmd)} " +
                    "magic=0x%08X 期望=0x%08X".format(header.magic, AdbProtocol.magicOf(header.cmd))
            )
        }
        if (header.dataLength < 0 || header.dataLength > MAX_ALLOWED_PAYLOAD) {
            throw AdbProtocolException("报文长度异常：${header.dataLength}")
        }
        val payload = if (header.dataLength > 0) {
            ByteArray(header.dataLength).also { ins.readFully(it) }
        } else {
            AdbProtocol.EMPTY
        }
        return AdbFrame(header, payload)
    }

    /** 不加锁的裸发送（仅握手期单线程调用） */
    private fun sendRaw(data: ByteArray) {
        val out = output ?: throw AdbConnectionClosedException("连接已关闭")
        out.write(data)
        out.flush()
    }

    /** 加锁发送，供多线程使用 */
    private fun sendSafe(data: ByteArray) {
        try {
            writeLock.withLock {
                val out = output ?: return
                out.write(data)
                out.flush()
            }
        } catch (_: Exception) {
            // 发送失败通常意味着连接已断，由 reader 线程负责收敛状态
        }
    }

    internal fun writeToStream(stream: AdbStream, data: ByteArray, offset: Int, length: Int) {
        var sent = 0
        while (sent < length) {
            val chunk = minOf(maxData - AdbProtocol.HEADER_SIZE, length - sent)
            val piece = data.copyOfRange(offset + sent, offset + sent + chunk)
            val frame = AdbProtocol.encode(
                AdbProtocol.CMD_WRTE,
                stream.localId,
                stream.remoteId,
                piece
            )
            writeLock.withLock {
                val out = output ?: throw AdbConnectionClosedException("连接已关闭")
                out.write(frame)
                out.flush()
            }
            sent += chunk
        }
    }

    // ------------------------------------------------------------------
    // 流管理
    // ------------------------------------------------------------------

    /**
     * 打开一个服务流。service 形如 `shell,v2,raw:pm list packages -3` 或 `sync:`。
     * 若对端立即回 CLSE（服务不支持），抛 [AdbCommandException]。
     */
    fun openStream(service: String, timeoutMs: Long = 10_000): AdbStream {
        if (closed) throw AdbConnectionClosedException("连接已关闭")
        val localId = nextLocalId.getAndIncrement()
        val payload = (service + "\u0000").toByteArray(Charsets.UTF_8)

        val openedLatch = CountDownLatch(1)
        val result = arrayOfNulls<AdbStream>(1)
        val openError = arrayOfNulls<Exception>(1)

        // 预占位：reader 收到 OKAY 时会用它
        val placeholder = PendingStream(localId, service, openedLatch, result, openError)
        pending[localId] = placeholder

        val frame = AdbProtocol.encode(AdbProtocol.CMD_OPEN, localId, 0, payload)
        writeLock.withLock {
            val out = output ?: throw AdbConnectionClosedException("连接已关闭")
            out.write(frame)
            out.flush()
        }

        val ok = openedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pending.remove(localId)
        if (!ok) throw AdbTimeoutException("打开服务超时：$service")
        openError[0]?.let { throw it }
        return result[0]
            ?: throw AdbCommandException(-1, "", "", "无法打开服务：$service")
    }

    internal fun closeStreamInternal(stream: AdbStream) {
        streams.remove(stream.localId)
        sendSafe(
            AdbProtocol.encode(AdbProtocol.CMD_CLSE, stream.localId, stream.remoteId)
        )
    }

    /** 关闭连接，并唤醒所有阻塞中的读取 */
    fun close() {
        if (closed) return
        closed = true
        closeQuietly()
        failAllStreams(AdbConnectionClosedException("连接已关闭"))
        readerThread?.interrupt()
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    // 握手之后 openStream 的等待结构
    private inner class PendingStream(
        private val localId: Int,
        private val service: String,
        private val latch: CountDownLatch,
        private val result: Array<AdbStream?>,
        private val error: Array<Exception?>
    ) {
        fun complete(remoteId: Int) {
            val s = AdbStream(localId, remoteId, this@AdbConnection, service)
            streams[localId] = s
            result[0] = s
            latch.countDown()
        }

        fun fail(e: Exception) {
            error[0] = e
            latch.countDown()
        }
    }

    /** 有待打开的流（等待对端 OKAY/CLSE） */
    private val pending = ConcurrentHashMap<Int, PendingStream>()

    /**
     * 处理 OPEN 的应答。需在 dispatch 中调用。
     * 这里用内部方法暴露给 dispatch。
     */
    /**
     * 处理 OPEN 的应答。
     *
     * 按 ADB 协议约定，OKAY/CLSE/WRTE 的字段是 `(local-id, remote-id)`：
     * - `arg0` = 发送方自己的 socket id（即对端的 remote id）
     * - `arg1` = 接收方的 socket id（即我们的 local id，用来定位本地流）
     *
     * 因此这里必须用 **arg1** 去查 pending 表。
     * 注意：拒绝 OPEN 时对端发的 CLSE 形如 `CLOSE(0, remote-id)`，arg0 可能为 0。
     */
    private fun handleOpenReply(header: AdbHeader) {
        val p = pending[header.arg1] ?: return
        when (header.cmd) {
            AdbProtocol.CMD_OKAY -> p.complete(header.arg0)
            AdbProtocol.CMD_CLSE -> p.fail(
                AdbCommandException(-1, "", "", "服务不可用（对端拒绝打开该服务）")
            )
        }
    }

    companion object {
        const val DEFAULT_PORT = 5555
        /** 允许的单个报文上限，防畸形报文导致 OOM */
        private const val MAX_ALLOWED_PAYLOAD = 1024 * 1024
    }
}
