package com.ufi_axis.data.screen

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字节装配器的单测（2026-11-01）。
 *
 * ## 为什么这一层必须测
 * WS 帧边界、流头、媒体包三者互不相干：头可能被拆成两帧，一个帧里可能有三个包加半个包。
 * 这层算错的表现是**黑屏且不报错**（包头错位 → 解码器永远等不到完整帧），真机上极难定位。
 * 这里把每一种切法都显式造出来，让错位在 `./gradlew test` 就红。
 */
class ScreenStreamParserTest {

    private class Recorder : ScreenStreamParser.Sink {
        var header: ScreenProtocol.StreamHeader? = null
        var headerCount = 0
        val configs = mutableListOf<ByteArray>()
        /** (ptsUs, keyframe, data) */
        val frames = mutableListOf<Triple<Long, Boolean, ByteArray>>()
        val corrupt = mutableListOf<String>()

        override fun onHeader(header: ScreenProtocol.StreamHeader) {
            this.header = header
            headerCount++
        }

        override fun onConfig(csd: ByteArray) {
            configs += csd
        }

        override fun onFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
            frames += Triple(ptsUs, keyframe, data)
        }

        override fun onCorrupt(reason: String) {
            corrupt += reason
        }
    }

    // ── 造字节 ──

    private fun header(name: String, w: Int, h: Int): ByteArray {
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

    private fun packet(pts: Long, payload: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(ScreenProtocol.PACKET_HEADER_SIZE + payload.size)
        bb.putLong(pts).putInt(payload.size).put(payload)
        return bb.array()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    @Test
    fun `流头被拆成两段也能解析`() {
        val sink = Recorder()
        val parser = ScreenStreamParser(sink)
        val h = header("F50", 480, 848)

        parser.feed(h.copyOfRange(0, 40))
        assertEquals("不足 76 字节时不该回调", 0, sink.headerCount)

        parser.feed(h.copyOfRange(40, h.size))
        assertEquals(1, sink.headerCount)
        assertEquals("F50", sink.header?.deviceName)
        assertEquals(480, sink.header?.width)
        assertEquals(848, sink.header?.height)
    }

    @Test
    fun `一个 feed 里的多个包按顺序全部取出`() {
        val sink = Recorder()
        val parser = ScreenStreamParser(sink)
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5)

        parser.feed(concat(header("F50", 480, 848), packet(100L, a), packet(200L, b)))

        assertEquals(2, sink.frames.size)
        assertEquals(100L, sink.frames[0].first)
        assertArrayEquals(a, sink.frames[0].third)
        assertEquals(200L, sink.frames[1].first)
        assertArrayEquals(b, sink.frames[1].third)
    }

    @Test
    fun `被拆到两个 feed 的包要等收全再吐`() {
        val sink = Recorder()
        val parser = ScreenStreamParser(sink)
        val payload = ByteArray(100) { it.toByte() }
        val whole = concat(header("F50", 480, 848), packet(7L, payload))

        // 头 + 12 字节包头 + 50 字节数据：包还没收全
        parser.feed(whole.copyOfRange(0, ScreenProtocol.HEADER_SIZE + ScreenProtocol.PACKET_HEADER_SIZE + 50))
        assertEquals(0, sink.frames.size)

        parser.feed(whole.copyOfRange(ScreenProtocol.HEADER_SIZE + ScreenProtocol.PACKET_HEADER_SIZE + 50, whole.size))
        assertEquals(1, sink.frames.size)
        assertArrayEquals(payload, sink.frames[0].third)
        assertEquals(7L, sink.frames[0].first)
    }

    @Test
    fun `配置包走 onConfig 且 pts 不进帧回调`() {
        val sink = Recorder()
        val parser = ScreenStreamParser(sink)
        val csd = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0xCE.toByte())

        parser.feed(concat(header("F50", 480, 848), packet(ScreenProtocol.PTS_CONFIG, csd)))

        assertEquals(1, sink.configs.size)
        assertArrayEquals(csd, sink.configs[0])
        assertEquals("配置包不是画面帧", 0, sink.frames.size)
    }

    @Test
    fun `半个包留在缓冲里 不影响下一帧的完整性`() {
        val sink = Recorder()
        val parser = ScreenStreamParser(sink)
        val p1 = packet(1L, ByteArray(30) { 1 })
        val p2 = packet(2L, ByteArray(30) { 2 })
        val all = concat(header("F50", 480, 848), p1, p2)

        // 切在第二个包的中间
        val cut = ScreenProtocol.HEADER_SIZE + p1.size + 20
        parser.feed(all.copyOfRange(0, cut))
        assertEquals(1, sink.frames.size)
        assertEquals("只该吐出完整的第一个包", 1L, sink.frames[0].first)

        parser.feed(all.copyOfRange(cut, all.size))
        assertEquals(2, sink.frames.size)
        assertEquals(2L, sink.frames[1].first)
        assertArrayEquals(ByteArray(30) { 2 }, sink.frames[1].third)
    }

    @Test
    fun `包头长度离谱时判定流错位并复位`() {
        val sink = Recorder()
        val parser = ScreenStreamParser(sink)
        // 手造一个 size = 8MB 的包头（远超 maxPacketBytes）
        val bb = ByteBuffer.allocate(ScreenProtocol.PACKET_HEADER_SIZE)
        bb.putLong(1L).putInt(8 * 1024 * 1024)

        parser.feed(concat(header("F50", 480, 848), bb.array()))

        assertEquals(1, sink.corrupt.size)
        assertNull("复位后必须重新等流头", parser.header)
    }

    @Test
    fun `收不到完整头时一字节一字节喂也不崩`() {
        val sink = Recorder()
        val parser = ScreenStreamParser(sink)
        val whole = concat(header("F50", 480, 848), packet(9L, byteArrayOf(42)))

        whole.forEach { parser.feed(byteArrayOf(it)) }

        assertEquals(1, sink.headerCount)
        assertEquals(1, sink.frames.size)
        assertArrayEquals(byteArrayOf(42), sink.frames[0].third)
        assertEquals(whole.size.toLong(), parser.receivedBytes)
        assertEquals(1L, parser.frameCount)
    }

    @Test
    fun `关键帧标记要从 pts 里剥掉`() {
        // scrcpy 2.0+ 用 bit62 标关键帧：pts = 真实微秒 | (1L shl 62)。
        // 只掩 bit63 的话，喂给解码器的 pts 会是 2^62 微秒量级 —— 时间戳完全失真。
        val sink = Recorder()
        val parser = ScreenStreamParser(sink)
        val realPts = 123_456L
        val keyPts = realPts or ScreenProtocol.PTS_KEYFRAME

        parser.feed(
            concat(
                header("F50", 480, 848),
                packet(keyPts, byteArrayOf(1)),
                packet(realPts, byteArrayOf(2)),
            )
        )

        assertEquals(2, sink.frames.size)
        assertEquals("关键帧的真实 pts 必须还原", realPts, sink.frames[0].first)
        assertTrue("bit62 要翻译成 keyframe 标志", sink.frames[0].second)
        assertEquals(realPts, sink.frames[1].first)
        assertFalse("普通帧不能误报关键帧", sink.frames[1].second)
    }

    @Test
    fun `reset 之后旧残包不会拼到新会话的头前面`() {
        val sink = Recorder()
        val parser = ScreenStreamParser(sink)
        val h = header("F50", 480, 848)

        parser.feed(h.copyOfRange(0, 10)) // 半个头
        parser.reset()
        parser.feed(h)

        assertEquals(1, sink.headerCount)
        assertTrue("重连后的第一个头必须能被识别", sink.header != null)
    }
}
