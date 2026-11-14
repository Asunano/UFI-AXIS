package com.ufi_axis.data.screen

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议层的字节级单测（2026-11-01）。
 *
 * 这层算错的典型表现是**黑屏但没有任何报错**（包头错位 → 解码器一直等数据），
 * 所以每条都用"手工拼出来的字节"对，而不是拿自己的编码器去解自己的编码器。
 */
class ScreenProtocolTest {

    private fun streamHeader(name: String, w: Int, h: Int): ByteArray {
        val bb = ByteBuffer.allocate(ScreenProtocol.HEADER_SIZE)
        val nameBytes = ByteArray(64)
        val n = name.toByteArray()
        System.arraycopy(n, 0, nameBytes, 0, minOf(n.size, 64))
        bb.put(nameBytes)
        bb.put("h264".toByteArray())
        bb.putInt(w)
        bb.putInt(h)
        return bb.array()
    }

    @Test
    fun `流头按实测格式解析`() {
        // 实测：64B 设备名（NUL 填充）+ "h264" + u32 480 + u32 848
        val h = ScreenProtocol.parseHeader(streamHeader("F50", 480, 848))
        assertEquals("F50", h?.deviceName)
        assertEquals("h264", h?.codec)
        assertEquals(480, h?.width)
        assertEquals(848, h?.height)
    }

    @Test
    fun `流头不足 76 字节时返回 null`() {
        assertNull(ScreenProtocol.parseHeader(ByteArray(75)))
        // 正好 76 字节要能解
        assertEquals(76, ScreenProtocol.HEADER_SIZE)
    }

    @Test
    fun `配置包的 pts 是最高位 1`() {
        assertEquals(Long.MIN_VALUE, ScreenProtocol.PTS_CONFIG)
        assertTrue(ScreenProtocol.MediaPacket(ScreenProtocol.PTS_CONFIG, ByteArray(0)).isConfig)
        assertFalse(ScreenProtocol.MediaPacket(0L, ByteArray(0)).isConfig)
    }

    @Test
    fun `跨帧的包要能等到收全`() {
        val payload = ByteArray(100) { it.toByte() }
        val bb = ByteBuffer.allocate(ScreenProtocol.PACKET_HEADER_SIZE + payload.size)
        bb.putLong(0L).putInt(payload.size).put(payload)
        val whole = bb.array()

        // 只给前 50 字节：一个完整包都取不出来，消费位置必须是 0
        val (partial, used) = ScreenProtocol.drainPackets(whole.copyOfRange(0, 50))
        assertEquals(0, partial.size)
        assertEquals(0, used)

        // 给全：取到一个包，消费到末尾
        val (packets, usedAll) = ScreenProtocol.drainPackets(whole)
        assertEquals(1, packets.size)
        assertEquals(whole.size, usedAll)
        assertArrayEquals(payload, packets[0].data)
    }

    @Test
    fun `一帧里多个包要按顺序全取出来`() {
        fun packet(pts: Long, data: ByteArray): ByteArray {
            val bb = ByteBuffer.allocate(8 + 4 + data.size)
            bb.putLong(pts).putInt(data.size).put(data)
            return bb.array()
        }
        val a = packet(1L, byteArrayOf(1, 2, 3))
        val b = packet(ScreenProtocol.PTS_CONFIG, byteArrayOf(9, 9))
        val buf = a + b

        val (packets, used) = ScreenProtocol.drainPackets(buf)
        assertEquals(2, packets.size)
        assertEquals(1L, packets[0].pts)
        assertArrayEquals(byteArrayOf(1, 2, 3), packets[0].data)
        assertTrue(packets[1].isConfig)
        assertEquals(buf.size, used)
    }

    @Test
    fun `触摸消息是 32 字节且字段位置正确`() {
        val msg = ScreenProtocol.touch(
            action = ScreenProtocol.ACTION_DOWN,
            x = 240,
            y = 424,
            videoWidth = 480,
            videoHeight = 848,
        )
        assertEquals(32, msg.size)
        val bb = ByteBuffer.wrap(msg)
        assertEquals(2, bb.get().toInt()) // type = INJECT_TOUCH
        assertEquals(0, bb.get().toInt()) // action = DOWN
        assertEquals(-2L, bb.long) // pointerId = finger
        assertEquals(240, bb.int)
        assertEquals(424, bb.int)
        assertEquals(480.toShort(), bb.short)
        assertEquals(848.toShort(), bb.short)
        assertEquals(0xFFFF.toShort(), bb.short) // pressure = 1.0
        assertEquals(0, bb.int) // actionButton
        assertEquals(0, bb.int) // buttons
        assertFalse(bb.hasRemaining())
    }

    @Test
    fun `按键消息是 14 字节`() {
        val msg = ScreenProtocol.key(action = 0, keyCode = 3) // KEYCODE_HOME
        assertEquals(14, msg.size)
        val bb = ByteBuffer.wrap(msg)
        assertEquals(0, bb.get().toInt())
        assertEquals(0, bb.get().toInt())
        assertEquals(3, bb.int)
        assertEquals(0, bb.int)
        assertEquals(0, bb.int)
    }

    @Test
    fun `剪贴板消息带 UTF-8 内容与长度`() {
        val msg = ScreenProtocol.clipboard("测试", paste = true)
        val bb = ByteBuffer.wrap(msg)
        assertEquals(9, bb.get().toInt()) // SET_CLIPBOARD
        assertEquals(0L, bb.long) // sequence = 0（不要回执）
        assertEquals(1, bb.get().toInt()) // paste
        val len = bb.int
        val body = ByteArray(len)
        bb.get(body)
        assertEquals("测试", String(body, Charsets.UTF_8))
        assertEquals(1 + 8 + 1 + 4 + len, msg.size)
    }

    @Test
    fun `配置包能拆成 SPS 与 PPS`() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67.toByte(), 0x42, 0, 0x1E)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68.toByte(), 0xCE.toByte(), 0x3C)
        val parts = ScreenProtocol.splitCsd(sps + pps)
        assertEquals(2, parts.size)
        assertArrayEquals(sps, parts[0])
        assertArrayEquals(pps, parts[1])
    }

    @Test
    fun `配置包前面带 AUD 时按类型挑出 SPS 和 PPS`() {
        // 真实 csd 可能长这样：AUD(9) + SPS(7) + PPS(8)。按位置取会把 AUD 当 SPS。
        val aud = byteArrayOf(0, 0, 0, 1, 0x09, 0x10)
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte())

        val (s, p) = ScreenProtocol.spsPps(aud + sps + pps)
        assertArrayEquals(sps, s)
        assertArrayEquals(pps, p)
    }

    @Test
    fun `没有 SPS 时返回 null 交给调用方兜底`() {
        val (s, p) = ScreenProtocol.spsPps(byteArrayOf(0, 0, 0, 1, 0x09, 0x10))
        assertNull(s)
        assertNull(p)
    }

    @Test
    fun `三字节起始码也能拆`() {
        val sps = byteArrayOf(0, 0, 1, 0x67.toByte(), 0x11)
        val pps = byteArrayOf(0, 0, 1, 0x68.toByte(), 0x22)
        val parts = ScreenProtocol.splitCsd(sps + pps)
        assertEquals(2, parts.size)
        assertArrayEquals(sps, parts[0])
        assertArrayEquals(pps, parts[1])
    }
}
