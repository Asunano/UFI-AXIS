package com.ufi_axis.adbcore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 报文编解码与常量正确性 */
class AdbProtocolTest {

    @Test
    fun `magic 是命令字按位取反`() {
        assertEquals(0x4E584E43, AdbProtocol.CMD_CNXN)
        assertEquals(AdbProtocol.CMD_CNXN xor -1, AdbProtocol.magicOf(AdbProtocol.CMD_CNXN))
        // 0x4E584E43 xor 0xFFFFFFFF = 0xB1A7B1BC
        assertEquals(0xB1A7B1BC.toInt(), AdbProtocol.magicOf(AdbProtocol.CMD_CNXN))
    }

    @Test
    fun `命令字小端字节序与官方一致`() {
        // adb 官方把 4 字节 ASCII 常量按小端整数定义，
        // 因此 0x4E584E43 在报文里恰好写出 "CNXN"
        assertEquals("CNXN", String(AdbProtocol.intToBytes(AdbProtocol.CMD_CNXN), Charsets.US_ASCII))
        assertEquals("AUTH", String(AdbProtocol.intToBytes(AdbProtocol.CMD_AUTH), Charsets.US_ASCII))
        assertEquals("OPEN", String(AdbProtocol.intToBytes(AdbProtocol.CMD_OPEN), Charsets.US_ASCII))
        assertEquals("OKAY", String(AdbProtocol.intToBytes(AdbProtocol.CMD_OKAY), Charsets.US_ASCII))
        assertEquals("CLSE", String(AdbProtocol.intToBytes(AdbProtocol.CMD_CLSE), Charsets.US_ASCII))
        assertEquals("WRTE", String(AdbProtocol.intToBytes(AdbProtocol.CMD_WRTE), Charsets.US_ASCII))
        assertEquals("STLS", String(AdbProtocol.intToBytes(AdbProtocol.CMD_STLS), Charsets.US_ASCII))
    }

    @Test
    fun `编码后长度等于头加 payload 且 magic 自洽`() {
        val payload = "host::installer\u0000".toByteArray(Charsets.UTF_8)
        val frame = AdbProtocol.encode(AdbProtocol.CMD_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_DATA, payload)

        assertEquals(AdbProtocol.HEADER_SIZE + payload.size, frame.size)

        val header = AdbProtocol.decodeHeader(frame)
        assertEquals(AdbProtocol.CMD_CNXN, header.cmd)
        assertEquals(AdbProtocol.A_VERSION, header.arg0)
        assertEquals(AdbProtocol.MAX_DATA, header.arg1)
        assertEquals(payload.size, header.dataLength)
        assertTrue(AdbProtocol.verifyMagic(header))
    }

    @Test
    fun `CRC32 字段有值且对空 payload 为零`() {
        val payload = "hello".toByteArray()
        val frame = AdbProtocol.encode(AdbProtocol.CMD_WRTE, 1, 1, payload)
        val header = AdbProtocol.decodeHeader(frame)
        assertEquals(AdbProtocol.crc32(payload), header.dataChecksum)
        assertTrue("非空 payload 的 CRC 不应为 0", header.dataChecksum != 0)

        val empty = AdbProtocol.encode(AdbProtocol.CMD_OKAY, 1, 1, AdbProtocol.EMPTY)
        assertEquals(0, AdbProtocol.decodeHeader(empty).dataChecksum)
        assertEquals(AdbProtocol.HEADER_SIZE, empty.size)
    }

    @Test
    fun `payload 被原样写入报文尾部`() {
        val payload = ByteArray(64) { it.toByte() }
        val frame = AdbProtocol.encode(AdbProtocol.CMD_WRTE, 7, 9, payload)
        val actual = frame.copyOfRange(AdbProtocol.HEADER_SIZE, frame.size)
        assertArrayEquals(payload, actual)
    }

    @Test
    fun `magic 校验能识别被篡改的报文`() {
        val frame = AdbProtocol.encode(AdbProtocol.CMD_OKAY, 1, 1, AdbProtocol.EMPTY)
        frame[20] = (frame[20] + 1).toByte() // 破坏 magic
        val header = AdbProtocol.decodeHeader(frame)
        assertFalse(AdbProtocol.verifyMagic(header))
    }

    @Test
    fun `小端整数值往返一致`() {
        val values = listOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE, 0x00100003, 262144, 5555)
        for (v in values) {
            val b = AdbProtocol.intToBytes(v)
            assertEquals("值 $v 往返失败", v, AdbProtocol.getInt(b, 0))
        }
    }

    @Test
    fun `版本位组合符合预期`() {
        assertEquals(0x00100003, AdbProtocol.A_VERSION)
        val v = AdbProtocol.A_VERSION
        assertTrue(v and AdbProtocol.A_VERSION_MIN != 0)
        assertTrue(v and AdbProtocol.A_VERSION_SKIP_CHECKSUM != 0)
        assertTrue(v and AdbProtocol.A_VERSION_MAX_PAYLOAD_V1 != 0)
    }

    @Test
    fun `命令名字用于日志可读`() {
        assertEquals("CNXN", AdbProtocol.cmdName(AdbProtocol.CMD_CNXN))
        assertEquals("AUTH", AdbProtocol.cmdName(AdbProtocol.CMD_AUTH))
        assertEquals("WRTE", AdbProtocol.cmdName(AdbProtocol.CMD_WRTE))
        assertEquals("STLS", AdbProtocol.cmdName(AdbProtocol.CMD_STLS))
    }

    @Test
    fun `token 长度为官方定义的 20 字节`() {
        assertEquals(20, AdbProtocol.TOKEN_SIZE)
    }

    @Test
    fun `CNXN 报文头各字段布局与官方一致`() {
        val banner = "host::\u0000".toByteArray(Charsets.UTF_8)
        val frame = AdbProtocol.encode(
            AdbProtocol.CMD_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_DATA, banner
        )
        val header = frame.copyOfRange(0, AdbProtocol.HEADER_SIZE)

        assertEquals("banner 长度应为 7", 7, banner.size)

        // [0..4) command: "CNXN"
        assertEquals("CNXN", String(header, 0, 4, Charsets.US_ASCII))
        // [4..8) arg0 = A_VERSION (0x00100003)，小端 → 03 00 10 00
        assertEquals(0x03.toByte(), header[4])
        assertEquals(0x00.toByte(), header[5])
        assertEquals(0x10.toByte(), header[6])
        assertEquals(0x00.toByte(), header[7])
        // [8..12) arg1 = 262144 (0x00040000)，小端 → 00 00 04 00
        assertEquals(AdbProtocol.MAX_DATA, AdbProtocol.getInt(header, 8))
        assertEquals(0x00.toByte(), header[8])
        assertEquals(0x00.toByte(), header[9])
        assertEquals(0x04.toByte(), header[10])
        assertEquals(0x00.toByte(), header[11])
        // [12..16) data_length = 7，小端 → 07 00 00 00
        assertEquals(7, AdbProtocol.getInt(header, 12))
        // [20..24) magic = CNXN xor 0xFFFFFFFF
        assertEquals(0xB1A7B1BC.toInt(), AdbProtocol.getInt(header, 20))
    }

    @Test
    fun `CRC 字段按大端写入而非小端`() {
        val banner = "host::\u0000".toByteArray(Charsets.UTF_8)
        val frame = AdbProtocol.encode(
            AdbProtocol.CMD_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_DATA, banner
        )
        val crcBytes = frame.copyOfRange(16, 20)

        val raw = java.util.zip.CRC32().apply { update(banner) }.value
        val expectedMsbFirst = byteArrayOf(
            ((raw ushr 24) and 0xFF).toByte(),
            ((raw ushr 16) and 0xFF).toByte(),
            ((raw ushr 8) and 0xFF).toByte(),
            (raw and 0xFF).toByte()
        )
        assertArrayEquals(
            "CRC 必须按大端（MSB-first）写入，这是 adb 中唯一大小端相反的头字段",
            expectedMsbFirst,
            crcBytes
        )

        // 确认实现返回的 crc32() 是翻转后的值
        assertEquals(AdbProtocol.reverseBytes(raw.toInt()), AdbProtocol.crc32(banner))
    }
}
