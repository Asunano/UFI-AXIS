package com.ufi_axis.adbcore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * shell v2 与 SYNC 子协议的纯字节级验证。
 * 这里不依赖网络，专门盯「最容易写错的分帧逻辑」。
 */
class AdbShellParserTest {

    /**
     * 按 shell v2 规范组装一个数据块。
     * 包头 = 1 字节数值 ID + 4 字节小端长度（见 AOSP adb/shell_protocol.h）。
     */
    private fun block(id: Int, payload: ByteArray): ByteArray =
        AdbProtocol.concat(byteArrayOf(id.toByte()), AdbProtocol.le32(payload.size), payload)

    private fun block(id: Int, text: String) = block(id, text.toByteArray(Charsets.UTF_8))

    @Test
    fun `解析标准 STDOUT STDERR EXIT 序列`() {
        val bytes = AdbProtocol.concat(
            block(AdbShell.ID_STDOUT, "line1\n"),
            block(AdbShell.ID_STDERR, "warn\n"),
            block(AdbShell.ID_EXIT, byteArrayOf(0))
        )
        val r = AdbShell.parseV2Stream(bytes)
        assertEquals("line1\n", r.stdout)
        assertEquals("warn\n", r.stderr)
        assertEquals(0, r.exitCode)
        assertTrue(r.isSuccess)
    }

    @Test
    fun `exit code 被正确读取为无符号字节`() {
        val bytes = AdbProtocol.concat(block(AdbShell.ID_EXIT, byteArrayOf(127.toByte())))
        val r = AdbShell.parseV2Stream(bytes)
        assertEquals(127, r.exitCode)
    }

    @Test
    fun `多个 STDOUT 块被顺序拼接`() {
        val bytes = AdbProtocol.concat(
            block(AdbShell.ID_STDOUT, "abc"),
            block(AdbShell.ID_STDOUT, "def"),
            block(AdbShell.ID_STDOUT, "ghi"),
            block(AdbShell.ID_EXIT, byteArrayOf(0))
        )
        val r = AdbShell.parseV2Stream(bytes)
        assertEquals("abcdefghi", r.stdout)
    }

    @Test
    fun `CLOSE_STDIN 块被忽略不影响输出`() {
        val bytes = AdbProtocol.concat(
            block(AdbShell.ID_STDOUT, "data"),
            block(AdbShell.ID_CLOSE_STDIN, ByteArray(0)),
            block(AdbShell.ID_EXIT, byteArrayOf(0))
        )
        val r = AdbShell.parseV2Stream(bytes)
        assertEquals("data", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `截断的字节流不会崩溃且保留已解析内容`() {
        val full = AdbProtocol.concat(
            block(AdbShell.ID_STDOUT, "complete"),
            // 故意截断的块
            byteArrayOf(AdbShell.ID_STDOUT.toByte()), AdbProtocol.le32(100), "short".toByteArray()
        )
        val r = AdbShell.parseV2Stream(full)
        assertEquals("complete", r.stdout)
    }

    @Test
    fun `空流解析为空结果`() {
        val r = AdbShell.parseV2Stream(ByteArray(0))
        assertEquals("", r.stdout)
        assertEquals("", r.stderr)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `UTF-8 多字节字符跨块时按字节拼接后正确解码`() {
        // "中文" 的 UTF-8 是 6 字节，拆成两块
        val text = "中文测试"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val mid = bytes.size / 2
        val stream = AdbProtocol.concat(
            block(AdbShell.ID_STDOUT, bytes.copyOfRange(0, mid)),
            block(AdbShell.ID_STDOUT, bytes.copyOfRange(mid, bytes.size)),
            block(AdbShell.ID_EXIT, byteArrayOf(0))
        )
        val r = AdbShell.parseV2Stream(stream)
        assertEquals(text, r.stdout)
    }
}

/** SYNC 帧布局验证 */
class AdbSyncTest {

    @Test
    fun `SEND 帧布局正确`() {
        val frame = AdbSync.buildSendFrame("/data/local/tmp/ufi_core.apk")
        val path = "/data/local/tmp/ufi_core.apk"

        assertEquals("SEND", String(frame, 0, 4, Charsets.US_ASCII))
        assertEquals(path.length, AdbProtocol.getInt(frame, 4))
        assertEquals(path, String(frame, 8, path.length, Charsets.UTF_8))
        assertEquals(4 + 4 + path.length, frame.size)
    }

    @Test
    fun `DATA 帧布局正确且携带原始字节`() {
        val data = ByteArray(100) { (it * 3 % 256).toByte() }
        val frame = AdbSync.buildDataFrame(data)

        assertEquals("DATA", String(frame, 0, 4, Charsets.US_ASCII))
        assertEquals(100, AdbProtocol.getInt(frame, 4))
        assertArrayEquals(data, frame.copyOfRange(8, frame.size))
    }

    @Test
    fun `DATA 帧支持偏移与长度切片`() {
        val data = ByteArray(50) { it.toByte() }
        val frame = AdbSync.buildDataFrame(data, offset = 10, length = 20)

        assertEquals(20, AdbProtocol.getInt(frame, 4))
        assertArrayEquals(data.copyOfRange(10, 30), frame.copyOfRange(8, frame.size))
    }

    @Test
    fun `DONE 帧布局正确`() {
        val frame = AdbSync.buildDoneFrame(1700000000)
        assertEquals("DONE", String(frame, 0, 4, Charsets.US_ASCII))
        assertEquals(1700000000, AdbProtocol.getInt(frame, 4))
        assertEquals(8, frame.size)
    }

    @Test
    fun `STAT 常量取值符合协议`() {
        assertEquals(0, AdbSync.STAT_OKAY)
        assertEquals(1, AdbSync.STAT_FAIL)
        assertEquals(2, AdbSync.STAT_QUIT)
    }

    @Test
    fun `分块大小不超过报文分片上限`() {
        assertTrue(
            "64KB 分块必须远小于报文上限，需为头部留空间",
            AdbSync.CHUNK_SIZE + AdbProtocol.HEADER_SIZE < AdbProtocol.MAX_DATA
        )
    }

    @Test
    fun `完整推送序列的帧顺序为 SEND DATA DONE`() {
        val out = ByteArrayOutputStream()
        out.write(AdbSync.buildSendFrame("/tmp/a.apk"))
        out.write(AdbSync.buildDataFrame(ByteArray(4) { 1 }))
        out.write(AdbSync.buildDoneFrame(0))
        val all = out.toByteArray()

        var p = 0
        val ids = mutableListOf<String>()
        while (p + 4 <= all.size) {
            val id = String(all, p, 4, Charsets.US_ASCII)
            ids += id
            p += 4
            when (id) {
                "SEND" -> {
                    val len = AdbProtocol.getInt(all, p); p += 4 + len
                }
                "DATA" -> {
                    val len = AdbProtocol.getInt(all, p); p += 4 + len
                }
                "DONE" -> p += 4
            }
        }
        assertEquals(listOf("SEND", "DATA", "DONE"), ids)
        assertEquals("帧序列应被完整消费", all.size, p)
    }
}
