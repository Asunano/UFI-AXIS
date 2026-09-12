package com.ufi_axis.adbcore

import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地假 ADB 设备，用于在没有真机的情况下验证协议实现。
 *
 * 支持：CNXN 握手、AUTH(TOKEN/SIGNATURE/RSAPUBLICKEY)、OPEN/OKAY/WRTE/CLSE、
 * shell v2 输出、SYNC 接收 SEND/DATA/DONE。
 *
 * 通过 [mode] 控制鉴权行为，覆盖正常路径与各错误路径。
 */
class FakeAdbServer(
    private val mode: AuthMode = AuthMode.ACCEPT_SIGNATURE,
    /** shell v2 命令 -> (stdout, stderr, exitCode) */
    private val shellResponses: Map<String, Triple<String, String, Int>> = emptyMap(),
    /** 返回 false 表示拒绝该服务 */
    private val serviceFilter: (String) -> Boolean = { true }
) {

    enum class AuthMode {
        /** 设备不再要求鉴权，首个回包即 CNXN（ro.adb.secure=0） */
        NO_AUTH,
        /** 正常路径：TOKEN → 签名通过 → CNXN */
        ACCEPT_SIGNATURE,
        /** 签名不被接受：TOKEN → 要求提交公钥 → 用户允许 → CNXN */
        REQUIRE_PUBLIC_KEY,
        /** 用户拒绝：一直回 TOKEN */
        REJECT_ALL,
        /** 要求 TLS（无线调试） */
        REQUIRE_TLS,
        /** 直接关闭连接 */
        CLOSE_IMMEDIATELY
    }

    /** CNXN banner，携带 features 列表（含 shell_v2，使 stdIn 流式安装可被触发） */
    private val DEVICE_BANNER =
        "device::ro.product.model=fake;features=shell_v2,cmd,stat_v2,sendrecv_v2\u0000"

    private val serverSocket = ServerSocket(0)
    val port: Int get() = serverSocket.localPort

    private val running = AtomicBoolean(true)
    private var serverThread: Thread? = null
    private var clientThread: Thread? = null

    /** 记录收到的 AUTH 类型，供测试断言 */
    val receivedAuthTypes = mutableListOf<Int>()
    val receivedCommands = mutableListOf<Int>()

    /** 收到的 SYNC 数据（远端路径 -> 字节） */
    val receivedFiles = ConcurrentHashMap<String, ByteArray>()

    /** 记录客户端发来的所有 shell 命令 */
    val receivedShellCommands = mutableListOf<String>()

    @Volatile
    var lastClientOutput: OutputStream? = null
        private set

    private val clientConnected = CountDownLatch(1)

    private val nextRemoteId = AtomicInteger(100)

    fun start() {
        val t = Thread({ acceptLoop() }, "fake-adb-server-$port")
        t.isDaemon = true
        t.start()
        serverThread = t
    }

    fun awaitClient(timeoutMs: Long = 5000): Boolean =
        clientConnected.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun stop() {
        running.set(false)
        try {
            serverSocket.close()
        } catch (_: Exception) {
        }
        try {
            clientThread?.interrupt()
        } catch (_: Exception) {
        }
    }

    private fun acceptLoop() {
        try {
            val client = serverSocket.accept()
            clientConnected.countDown()
            clientThread = Thread({ serve(client) }, "fake-adb-client").also {
                it.isDaemon = true
                it.start()
            }
        } catch (_: Exception) {
        }
    }

    private fun serve(client: Socket) {
        try {
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            lastClientOutput = output

            // ---- 握手 ----
            val first = readFrame(input)
            receivedCommands += first.header.cmd
            if (first.header.cmd != AdbProtocol.CMD_CNXN) {
                client.close(); return
            }

            when (mode) {
                AuthMode.CLOSE_IMMEDIATELY -> {
                    client.close(); return
                }
                AuthMode.REQUIRE_TLS -> {
                    send(output, AdbProtocol.CMD_STLS, AdbProtocol.A_VERSION, 0, AdbProtocol.EMPTY)
                    client.close(); return
                }
                AuthMode.NO_AUTH -> {
                    send(output, AdbProtocol.CMD_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_DATA,
                        DEVICE_BANNER.toByteArray())
                }
                else -> {
                    if (!handshakeAuth(input, output)) {
                        client.close(); return
                    }
                }
            }

            // ---- 命令循环 ----
            commandLoop(input, output, client)
        } catch (_: Exception) {
            // 测试结束时的正常断开
        }
    }

    /** 返回 true 表示鉴权通过 */
    private fun handshakeAuth(input: DataInputStream, output: OutputStream): Boolean {
        val token = ByteArray(AdbProtocol.TOKEN_SIZE) { (it + 1).toByte() }
        var round = 0

        while (round < 5) {
            round++
            send(output, AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_TOKEN, 0, token)
            val reply = readFrame(input)
            receivedCommands += reply.header.cmd
            receivedAuthTypes += reply.header.arg0

            if (reply.header.cmd != AdbProtocol.CMD_AUTH) return false

            when (reply.header.arg0) {
                AdbProtocol.AUTH_SIGNATURE -> {
                    when (mode) {
                        AuthMode.ACCEPT_SIGNATURE -> {
                            send(output, AdbProtocol.CMD_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_DATA,
                                DEVICE_BANNER.toByteArray())
                            return true
                        }
                        AuthMode.REQUIRE_PUBLIC_KEY -> {
                            // 拒绝签名，等客户端提交公钥
                            continue
                        }
                        AuthMode.REJECT_ALL -> continue
                        else -> return false
                    }
                }
                AdbProtocol.AUTH_RSAPUBLICKEY -> {
                    if (mode == AuthMode.REQUIRE_PUBLIC_KEY) {
                        send(output, AdbProtocol.CMD_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_DATA,
                            DEVICE_BANNER.toByteArray())
                        return true
                    }
                    return false
                }
                else -> return false
            }
        }
        return false
    }

    private fun commandLoop(input: DataInputStream, output: OutputStream, client: Socket) {
        val syncBuffers = ConcurrentHashMap<Int, SyncState>()

        while (running.get() && !client.isClosed) {
            val frame = try {
                readFrame(input)
            } catch (_: Exception) {
                return
            }
            receivedCommands += frame.header.cmd

            when (frame.header.cmd) {
                AdbProtocol.CMD_OPEN -> {
                    val service = AdbProtocol.toAsciiTrimmed(frame.payload)
                    val localId = frame.header.arg0
                    if (!serviceFilter(service)) {
                        send(output, AdbProtocol.CMD_CLSE, 0, localId, AdbProtocol.EMPTY)
                        continue
                    }
                    val remoteId = nextRemoteId.incrementAndGet()
                    send(output, AdbProtocol.CMD_OKAY, remoteId, localId, AdbProtocol.EMPTY)

                    when {
                        service.startsWith("shell") -> handleShell(output, localId, remoteId, service)
                        service.startsWith("sync") -> syncBuffers[localId] = SyncState(remoteId)
                    }
                }

                AdbProtocol.CMD_WRTE -> {
                    // WRTE(arg0=发送方 id, arg1=接收方 id)。客户端发来时：
                    // arg0 = 客户端 localId，arg1 = 服务端自己的 id
                    val serverSelfId = frame.header.arg1
                    val clientLocalId = frame.header.arg0
                    val entry = syncBuffers.entries.firstOrNull { it.value.remoteId == serverSelfId }
                    if (entry != null) {
                        handleSyncData(output, frame.payload, entry.value, clientLocalId)
                    }
                    // 回 OKAY 确认，否则客户端会因流控停止发送
                    send(output, AdbProtocol.CMD_OKAY, serverSelfId, clientLocalId, AdbProtocol.EMPTY)
                }

                AdbProtocol.CMD_CLSE -> {
                    val remoteId = frame.header.arg1
                    syncBuffers.entries.removeIf { it.value.remoteId == remoteId }
                }

                else -> Unit
            }
        }
    }

    /** 模拟 shell v2 输出。clientLocalId 是客户端的 id，serverSelfId 是服务端自己的 id。 */
    private fun handleShell(output: OutputStream, clientLocalId: Int, serverSelfId: Int, service: String) {
        val cmd = service.substringAfter("shell,v2,raw:", "")
            .ifEmpty { service.substringAfter("shell:", "") }

        synchronized(receivedShellCommands) { receivedShellCommands += cmd }

        val (stdout, stderr, code) = shellResponses[cmd] ?: defaultResponse(cmd)

        // 刻意把 STDOUT 拆成两个包发出，验证客户端的跨报文重组能力
        if (stdout.isNotEmpty()) {
            val bytes = stdout.toByteArray(Charsets.UTF_8)
            val mid = bytes.size / 2
            if (mid > 0) {
                sendStream(output, clientLocalId, serverSelfId, AdbShell.ID_STDOUT, bytes.copyOfRange(0, mid))
                sendStream(output, clientLocalId, serverSelfId, AdbShell.ID_STDOUT, bytes.copyOfRange(mid, bytes.size))
            } else {
                sendStream(output, clientLocalId, serverSelfId, AdbShell.ID_STDOUT, bytes)
            }
        }
        if (stderr.isNotEmpty()) {
            sendStream(output, clientLocalId, serverSelfId, AdbShell.ID_STDERR, stderr.toByteArray(Charsets.UTF_8))
        }
        sendStream(output, clientLocalId, serverSelfId, AdbShell.ID_EXIT, byteArrayOf(code.toByte()))

        // 关闭流：CLSE(arg0=服务端自己的 id, arg1=客户端 id)
        send(output, AdbProtocol.CMD_CLSE, serverSelfId, clientLocalId, AdbProtocol.EMPTY)
    }

    private fun defaultResponse(cmd: String): Triple<String, String, Int> = when {
        cmd.startsWith("pm list packages") ->
            Triple("package:com.android.chrome\npackage:com.example.newapp\n", "", 0)
        cmd.startsWith("pm path") -> Triple("package:/data/app/base.apk\n", "", 0)
        cmd.startsWith("pm install") -> Triple("Success\n", "", 0)
        cmd.startsWith("pidof") -> Triple("12345\n", "", 0)
        cmd.startsWith("cmd package resolve-activity") ->
            Triple("com.ufi_axis_core/.MainActivity\n", "", 0)
        cmd.startsWith("am start") -> Triple("Starting: Intent { }\n", "", 0)
        cmd.startsWith("monkey") -> Triple("Events injected: 1\n", "", 0)
        else -> Triple("", "", 0)
    }

    /**
     * 发送 shell v2 的 [id:1][len:4][payload] 数据块（id 是单字节数值）。
     *
     * ADB 报文里 WRTE 的字段是 (发送方 local-id, 接收方 local-id)，
     * 即 `(服务端自己的 id, 客户端的 id)`。
     */
    private fun sendStream(
        output: OutputStream,
        clientLocalId: Int,
        serverSelfId: Int,
        id: Int,
        payload: ByteArray
    ) {
        val body = AdbProtocol.concat(
            byteArrayOf(id.toByte()),
            AdbProtocol.le32(payload.size),
            payload
        )
        send(output, AdbProtocol.CMD_WRTE, serverSelfId, clientLocalId, body)
    }

    /**
     * 处理 SYNC 子协议数据。
     *
     * 两个关键点（与真实 adbd 行为一致）：
     * 1. 客户端会把 SEND/DATA/DONE 拆在**不同的 WRTE 报文**里，甚至同一帧的
     *    字段也可能跨报文到达 → 必须把字节**累积到会话缓冲**再增量解析。
     * 2. 数据必须用字节缓冲，不能用字符串中转（会破坏非 UTF-8 字节）。
     */
    private fun handleSyncData(
        output: OutputStream,
        payload: ByteArray,
        state: SyncState,
        clientLocalId: Int
    ) {
        state.pending.write(payload)
        val buf = state.pending.toByteArray()

        var p = 0
        loop@ while (true) {
            if (p + 4 > buf.size) break
            val id = String(buf, p, 4, Charsets.US_ASCII)
            when (id) {
                "SEND" -> {
                    if (p + 8 > buf.size) break@loop
                    val len = AdbProtocol.getInt(buf, p + 4)
                    if (p + 8 + len > buf.size) break@loop
                    // SEND 帧里的 path 形如 "x.apk,0644"，按真机 do_send 的规则用逗号切分。
                    // 这里把 mode 尾巴剥掉，只保留真实路径作为 receivedFiles 的 key。
                    state.path = String(buf, p + 8, len, Charsets.UTF_8).substringBefore(",")
                    state.data = java.io.ByteArrayOutputStream()
                    p += 8 + len
                }
                "DATA" -> {
                    if (p + 8 > buf.size) break@loop
                    val len = AdbProtocol.getInt(buf, p + 4)
                    if (p + 8 + len > buf.size) break@loop
                    state.data.write(buf, p + 8, len)
                    p += 8 + len
                }
                "DONE" -> {
                    if (p + 8 > buf.size) break@loop
                    p += 8 // 跳过 mtime
                    val path = state.path
                    if (path != null) {
                        receivedFiles[path] = state.data.toByteArray()
                        // SYNC 推送成功：回 ID_OKAY（与真实 adbd 一致）。
                        // 标准 adb 会回 "STAT"+msgLen+"OKAY"，这里用更通用的 OKAY。
                        val ok = AdbProtocol.ascii("OKAY")
                        send(output, AdbProtocol.CMD_WRTE, state.remoteId, clientLocalId, ok)
                    }
                }
                "STAT", "OKAY", "FAIL", "QUIT" -> break@loop
                else -> break@loop
            }
        }

        // 把已消费部分移出缓冲，保留未完整解析的尾部
        state.pending = java.io.ByteArrayOutputStream()
        if (p < buf.size) state.pending.write(buf, p, buf.size - p)
    }

    private class SyncState(
        val remoteId: Int,
        var path: String? = null,
        /** 跨报文累积的 SYNC 字节流 */
        var pending: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream(),
        /** 当前文件的已接收数据 */
        var data: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
    )

    private fun send(output: OutputStream, cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        synchronized(output) {
            output.write(AdbProtocol.encode(cmd, arg0, arg1, payload))
            output.flush()
        }
    }

    private class Frame(val header: AdbHeader, val payload: ByteArray)

    private fun readFrame(input: DataInputStream): Frame {
        val hb = ByteArray(AdbProtocol.HEADER_SIZE)
        input.readFully(hb)
        val header = AdbProtocol.decodeHeader(hb)
        val payload = if (header.dataLength > 0) {
            ByteArray(header.dataLength).also { input.readFully(it) }
        } else AdbProtocol.EMPTY
        return Frame(header, payload)
    }
}
