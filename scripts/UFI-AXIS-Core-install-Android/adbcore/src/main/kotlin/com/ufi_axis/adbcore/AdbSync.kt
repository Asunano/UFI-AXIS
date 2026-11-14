package com.ufi_axis.adbcore

import java.io.File
import java.io.FileInputStream

/**
 * SYNC 子协议：把本地文件推送到设备。
 *
 * 帧格式（全小端）：
 * ```
 * SEND: [id:4="SEND"][pathLen:4][path,mode]   // path 后必须接 ",<八进制权限>"，如 "x.apk,0644"
 * DATA: [id:4="DATA"][length:4][data]
 * DONE: [id:4="DONE"][mtime:4]
 * 应答(成功): [id:4="OKAY"]                    // 部分实现为 ID_STAT + msglen + "OKAY"
 * 应答(失败): [id:4="FAIL"][msgLen:4][msg]
 * ```
 *
 * 注意 1：真实 adbd 的 `do_send` 会按逗号切分 path 与 mode，缺失 mode 会直接回 FAIL。
 * 注意 2：SYNC 推送的应答是 **OKAY / FAIL**，不是 STAT。STAT 只用于 stat 查询子命令。
 * 这是与真机联调时连续踩到的坑（`SYNC 期望 STAT，实际收到 FAIL/OKAY`），务必按 OKAY/FAIL 解析。
 *
 * 通过 `OPEN("sync:")` 打开一条流后在该流上跑本子协议。
 */
object AdbSync {

    private val ID_SEND = AdbProtocol.ascii("SEND")
    private val ID_DATA = AdbProtocol.ascii("DATA")
    private val ID_DONE = AdbProtocol.ascii("DONE")
    private val ID_STAT = AdbProtocol.ascii("STAT")
    private val ID_FAIL = AdbProtocol.ascii("FAIL")
    private val ID_OKAY = AdbProtocol.ascii("OKAY")
    private val ID_QUIT = AdbProtocol.ascii("QUIT")

    /** STAT 取值 */
    const val STAT_OKAY = 0
    const val STAT_FAIL = 1
    const val STAT_QUIT = 2

    /** 单次 DATA 的分块大小。取 64KB，远小于报文分片上限，安全。 */
    const val CHUNK_SIZE = 64 * 1024

    /**
     * 推送文件到设备。
     *
     * @param onProgress 已发送字节 / 总字节，用于 UI 进度
     * @throws AdbCommandException 设备返回 FAIL
     */
    fun push(
        connection: AdbConnection,
        localFile: File,
        remotePath: String,
        /** 八进制权限字符串（不带前导 0），默认 0644。必须带上，否则真机 do_send 回 FAIL。 */
        mode: String = "0644",
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ) {
        require(localFile.isFile) { "本地文件不存在：${localFile.absolutePath}" }
        val total = localFile.length()

        val stream = connection.openStream("sync:")
        try {
            val sink = StreamSink(stream)

            // 1. SEND（path 后必须带 ",mode"，真实 adbd 按逗号切分）
            sink.write(buildSendFrame(remotePath, mode))
            sink.flush()

            // 2. DATA（流式，整包不进内存）
            FileInputStream(localFile).use { fis ->
                val buf = ByteArray(CHUNK_SIZE)
                var sent = 0L
                while (true) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    sink.write(ID_DATA)
                    sink.write(AdbProtocol.le32(n))
                    sink.write(buf, 0, n)
                    sink.flush()
                    sent += n
                    onProgress?.invoke(sent, total)
                }
            }

            // 3. DONE
            sink.write(ID_DONE)
            sink.write(AdbProtocol.le32((localFile.lastModified() / 1000).toInt()))
            sink.flush()

            // 4. 读服务端应答。SYNC 推送的回应是 OKAY（成功）或 FAIL（失败），
            //    与 stat 查询子命令的 STAT 不同。部分标准 adb 实现会回
            //    "STAT" + msgLen + ("OKAY" / "FAIL: ...")，这里一并兼容。
            val id = stream.readFully(4, 30_000)
            when {
                AdbProtocol.equalsAscii(id, "OKAY") -> {
                    // 成功，无附加体
                }
                AdbProtocol.equalsAscii(id, "FAIL") -> {
                    val reason = tryReadFailMessage(stream)
                    throw AdbCommandException(
                        -1, "", "", "推送失败：$remotePath${if (reason != null) "（$reason）" else ""}"
                    )
                }
                AdbProtocol.equalsAscii(id, "STAT") -> {
                    // 标准 adb 变体：STAT + msgLen + "OKAY" / "FAIL: ..."
                    val len = AdbProtocol.getInt(stream.readFully(4, 30_000), 0)
                    val msg = if (len in 1..4096) String(stream.readFully(len, 30_000), Charsets.UTF_8) else ""
                    if (!msg.startsWith("OKAY")) {
                        throw AdbCommandException(-1, "", "", "推送失败：$remotePath（${msg.ifBlank { "STAT" }}）")
                    }
                }
                else -> throw AdbProtocolException(
                    "SYNC 期望 OKAY/FAIL/STAT，实际收到 ${String(id, Charsets.US_ASCII)}"
                )
            }
        } finally {
            stream.close()
        }
    }

    private fun tryReadFailMessage(stream: AdbStream): String? {
        return try {
            if (!stream.awaitData(500)) return null
            val len = AdbProtocol.getInt(stream.readFully(4, 1_000), 0)
            if (len in 1..4096) String(stream.readFully(len, 1_000), Charsets.UTF_8) else null
        } catch (_: Exception) {
            null
        }
    }

    /** 按 SYNC 协议要求组装 DATA 帧（纯函数，单测用） */
    fun buildDataFrame(data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray =
        AdbProtocol.concat(
            ID_DATA,
            AdbProtocol.le32(length),
            data.copyOfRange(offset, offset + length)
        )

    /** 组装 SEND 帧（纯函数，单测用）。默认不带 mode；推真机时务必传 mode。 */
    fun buildSendFrame(remotePath: String, mode: String? = null): ByteArray {
        val full = if (mode != null) "$remotePath,$mode" else remotePath
        val p = full.toByteArray(Charsets.UTF_8)
        return AdbProtocol.concat(ID_SEND, AdbProtocol.le32(p.size), p)
    }

    /** 组装 DONE 帧（纯函数，单测用） */
    fun buildDoneFrame(mtimeSeconds: Int): ByteArray =
        AdbProtocol.concat(ID_DONE, AdbProtocol.le32(mtimeSeconds))

    /** 简单地把字节写入流（ADB 流本身已做分片） */
    private class StreamSink(private val stream: AdbStream) {
        fun write(bytes: ByteArray) = stream.write(bytes)
        fun write(bytes: ByteArray, offset: Int, length: Int) = stream.write(bytes, offset, length)
        fun flush() {
            // AdbStream.write 内部已 flush 到底层 socket
        }
    }
}
