package com.ufi_axis.adbcore

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** shell 命令执行结果 */
data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    /**
     * exit code 是否可信。
     * shell v1 通道拿不到 exit code（只有 stdout 一条字节流），此时为 false，
     * 调用方必须改用输出文本判断成败，否则会把失败的命令当成成功。
     */
    val exitCodeKnown: Boolean = true
) {
    val isSuccess: Boolean get() = exitCode == 0

    /** 合并输出，便于日志展示 */
    fun combined(): String = buildString {
        if (stdout.isNotBlank()) append(stdout.trimEnd())
        if (stderr.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append(stderr.trimEnd())
        }
    }
}

/**
 * 通过 `shell,v2,raw:` 在设备上执行命令。
 *
 * 为什么用 v2：
 * - 能拿到**独立的 stderr** 和**精确的 exit code**（`pm install` 的成功判据是 stdout 里的 `Success`，靠文本猜不可靠）
 * - `raw:` 前缀必须加，否则会分配 pty，命令回显 + `\r\n` 转换会把输出判断搞脏
 *
 * v2 输出是**字节流**（不是报文）：
 * ```
 * [id:4][length:4][payload]   全小端
 * id ∈ STDOUT / STDERR / EXIT / CLOSE_STDIN
 * ```
 *
 * 最大陷阱：一个 WRTE 报文 ≠ 一条逻辑消息，必须靠 [AdbStream.readFully] 重新分帧。
 */
object AdbShell {

    /**
     * shell v2 包 ID（取自 AOSP `adb/shell_protocol.h` 的 `ShellProtocol::Id`）。
     * 注意是**单字节数值**，不是 ASCII 字符串。
     */
    const val ID_STDIN = 0
    const val ID_STDOUT = 1
    const val ID_STDERR = 2
    const val ID_EXIT = 3
    const val ID_CLOSE_STDIN = 4
    const val ID_WINDOW_SIZE_CHANGE = 5
    const val ID_INVALID = 255

    /** 包头长度 = 1 字节 ID + 4 字节长度 */
    const val V2_HEADER_SIZE = 5

    /**
     * 执行命令，返回结构化结果。
     *
     * 若对端不支持 shell v2（OPEN 立即被拒），自动回退到 v1（仅 stdout，exit code 用 0/非 0 粗略推断）。
     */
    fun exec(
        connection: AdbConnection,
        command: String,
        timeoutMs: Long = AdbStream.SHELL_TIMEOUT_MS
    ): ShellResult {
        return try {
            execV2(connection, command, timeoutMs)
        } catch (e: AdbCommandException) {
            // v2 服务不可用（对端拒绝 / 握手期超时）→ 回退 v1
            execV1(connection, command, timeoutMs)
        }
    }

    /**
     * 执行命令并把二进制数据喂进其 stdin（shell v2 专用）。
     *
     * 用于 `pm install -S <size>` 这类需要从标准输入接收 APK 的场景：APK 经 stdin
     * 交给 pm，由 pm 自己落到 staging 区并打上正确的 SELinux 上下文，从而**规避**
     * 直接把文件推到 `/data/local/tmp` 后 pm 做 `restorecon` 失败的问题
     * （即 `Failure [INSTALL_FAILED_MEDIA_UNAVAILABLE: Failed to restorecon]`）。
     *
     * 发送协议（shell v2 帧，全小端）：
     * ```
     * [id:1=STDIN][length:4][payload]   // 分片写入
     * [id:1=CLOSE_STDIN][length:4=0][]  // 通知对端 EOF
     * ```
     *
     * stdin 走**流式**读取，不把整包读进内存（几十 MB 的 APK 在低内存设备上会 OOM）。
     *
     * @param stdin     喂给命令 stdin 的数据源（调用方负责关闭）
     * @param stdinSize 数据总长度，仅用于进度回调
     * @param onStdinProgress 每写出一片 stdin 后的进度回调（已写字节 / 总字节）
     */
    fun execWithStdin(
        connection: AdbConnection,
        command: String,
        stdin: InputStream,
        stdinSize: Long,
        timeoutMs: Long = AdbStream.INSTALL_TIMEOUT_MS,
        onStdinProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): ShellResult {
        val stream = connection.openStream("shell,v2,raw:$command")
        try {
            // 1) 把 stdin 分片写入
            val buf = ByteArray(STDIN_CHUNK)
            var sent = 0L
            while (true) {
                val n = stdin.read(buf)
                if (n <= 0) break
                stream.write(buildV2Frame(ID_STDIN, buf.copyOfRange(0, n)))
                sent += n
                onStdinProgress?.invoke(sent, stdinSize)
            }
            // 2) 关闭 stdin，通知对端 APK 已发送完毕
            stream.write(buildV2Frame(ID_CLOSE_STDIN, AdbProtocol.EMPTY))

            // 3) 读取 stdout/stderr/exit，直到对端关闭流
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            var exitCode = -1
            var sawExit = false
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) throw AdbTimeoutException("命令执行超时（${timeoutMs}ms）：$command")
                if (stream.available() < V2_HEADER_SIZE) {
                    if (stream.isClosed) break
                    if (!stream.awaitData(remain)) {
                        if (stream.isClosed) break
                        throw AdbTimeoutException("命令执行超时（${timeoutMs}ms）：$command")
                    }
                    if (stream.available() < V2_HEADER_SIZE && stream.isClosed) break
                }
                val id = stream.readFully(1, remain)[0].toInt() and 0xFF
                val len = AdbProtocol.getInt(stream.readFully(4, remain), 0)
                if (len < 0 || len > MAX_V2_PAYLOAD) {
                    throw AdbProtocolException("shell v2 包长度异常：$len（id=$id）")
                }
                val payload = stream.readFully(len, remain)
                when (id) {
                    ID_STDOUT -> stdout.write(payload)
                    ID_STDERR -> stderr.write(payload)
                    ID_EXIT -> {
                        sawExit = true
                        exitCode = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0
                    }
                    ID_CLOSE_STDIN, ID_WINDOW_SIZE_CHANGE -> Unit
                    else -> Unit
                }
            }
            return ShellResult(
                stdout = stdout.toString(Charsets.UTF_8.name()),
                stderr = stderr.toString(Charsets.UTF_8.name()),
                exitCode = if (sawExit) exitCode else 0,
                exitCodeKnown = sawExit
            )
        } finally {
            stream.close()
        }
    }

    /** 组装一条 shell v2 帧：[id:1][length:4][payload] */
    private fun buildV2Frame(id: Int, payload: ByteArray): ByteArray =
        AdbProtocol.concat(byteArrayOf(id.toByte()), AdbProtocol.le32(payload.size), payload)

    private fun execV2(
        connection: AdbConnection,
        command: String,
        timeoutMs: Long
    ): ShellResult {
        val stream = connection.openStream("shell,v2,raw:$command")
        try {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            var exitCode = -1
            var sawExit = false

            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) throw AdbTimeoutException("命令执行超时（${timeoutMs}ms）：$command")

                // 关键：必须先把已缓冲的完整包消费完再判断结束，否则会丢掉尾部数据
                if (stream.available() < V2_HEADER_SIZE) {
                    if (stream.isClosed) break
                    if (!stream.awaitData(remain)) {
                        if (stream.isClosed) break
                        throw AdbTimeoutException("命令执行超时（${timeoutMs}ms）：$command")
                    }
                    if (stream.available() < V2_HEADER_SIZE && stream.isClosed) break
                }

                // 包头 = 1 字节数值 ID + 4 字节小端长度
                val id = stream.readFully(1, remain)[0].toInt() and 0xFF
                val len = AdbProtocol.getInt(stream.readFully(4, remain), 0)
                if (len < 0 || len > MAX_V2_PAYLOAD) {
                    throw AdbProtocolException("shell v2 包长度异常：$len（id=$id）")
                }
                val payload = stream.readFully(len, remain)

                when (id) {
                    ID_STDOUT -> stdout.write(payload)
                    ID_STDERR -> stderr.write(payload)
                    ID_EXIT -> {
                        sawExit = true
                        exitCode = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0
                    }
                    ID_CLOSE_STDIN, ID_WINDOW_SIZE_CHANGE -> Unit
                    else -> {
                        // 未知 id：容错丢弃
                    }
                }
            }

            return ShellResult(
                stdout = stdout.toString(Charsets.UTF_8.name()),
                stderr = stderr.toString(Charsets.UTF_8.name()),
                exitCode = if (sawExit) exitCode else 0,
                exitCodeKnown = sawExit
            )
        } finally {
            stream.close()
        }
    }

    private fun execV1(
        connection: AdbConnection,
        command: String,
        timeoutMs: Long
    ): ShellResult {
        val stream = connection.openStream("shell:$command")
        try {
            val out = ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) throw AdbTimeoutException("命令执行超时（${timeoutMs}ms）：$command")
                if (stream.available() == 0) {
                    if (stream.isClosed) break
                    if (!stream.awaitData(remain)) {
                        if (stream.isClosed) break
                        throw AdbTimeoutException("命令执行超时（${timeoutMs}ms）：$command")
                    }
                }
                out.write(stream.readAvailable())
                if (stream.isClosed && stream.available() == 0) break
            }
            // v1 拿不到 exit code，exitCodeKnown=false，调用方必须按文本判断成败。
            // 注意：若对端其实返回的是 v2 帧（v2 握手偶发抖动时），这里也能正确解析，
            // 避免把裸 v2 字节当成文本导致判据失效。
            val raw = out.toByteArray()
            return if (raw.isNotEmpty() && raw[0].toInt() and 0xFF in listOf(ID_STDOUT, ID_STDERR, ID_EXIT)) {
                parseV2Stream(raw)
            } else {
                ShellResult(out.toString(Charsets.UTF_8.name()), "", 0, exitCodeKnown = false)
            }
        } finally {
            stream.close()
        }
    }

    /** 便捷方法：执行命令并返回合并后的文本 */
    fun execText(connection: AdbConnection, command: String, timeoutMs: Long = AdbStream.SHELL_TIMEOUT_MS): String =
        exec(connection, command, timeoutMs).combined()

    /**
     * 解析 shell v2 字节流（**纯函数，便于单元测试**）。
     * 输入是喂给流的原始字节，输出是解析结果；用于验证跨报文分片的正确性。
     */
    fun parseV2Stream(bytes: ByteArray): ShellResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var exitCode = 0
        var sawExit = false
        var p = 0
        while (p + V2_HEADER_SIZE <= bytes.size) {
            val id = bytes[p].toInt() and 0xFF; p += 1
            val len = AdbProtocol.getInt(bytes, p); p += 4
            if (len < 0 || p + len > bytes.size) break
            val payload = bytes.copyOfRange(p, p + len); p += len
            when (id) {
                ID_STDOUT -> stdout.write(payload)
                ID_STDERR -> stderr.write(payload)
                ID_EXIT -> {
                    sawExit = true
                    exitCode = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0
                }
                ID_CLOSE_STDIN, ID_WINDOW_SIZE_CHANGE -> Unit
            }
        }
        return ShellResult(
            stdout.toString(Charsets.UTF_8.name()),
            stderr.toString(Charsets.UTF_8.name()),
            if (sawExit) exitCode else 0,
            exitCodeKnown = sawExit
        )
    }

    private const val MAX_V2_PAYLOAD = 4 * 1024 * 1024

    /** stdin 流式分片大小。64KB 与 SYNC 的 CHUNK_SIZE 对齐，避免大 APK 占内存。 */
    private const val STDIN_CHUNK = 64 * 1024
}
