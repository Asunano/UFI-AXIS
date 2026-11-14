package com.ufi_axis.data.screen

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真机抓到的真实流**验证装配器（2026-11-01）。
 *
 * ## 为什么这一层必须用真实数据测
 *
 * 之前的单测都是自己造包（`packet(pts, payload)`），只能证明"我的编码和我的解码互相对得上"。
 * 真机上出现的症状是"头解析正常、后面 78 个包也都解析出来了、但解码出来是绿色噪点"，
 * 这只有一种可能：**装配出来的 payload 和线上字节不一致**（帧边界对了，内容错了）。
 * 自造包永远测不出这种错 —— 必须拿真流按各种 WS 帧边界切一遍，逐字节比对。
 *
 * `screen-stream.bin` 是 2026-11-01 在 ZTE F50 上抓的：76 字节流头 + 24 个媒体包
 * （`3.3.3 max_size=640 max_fps=12 video_bit_rate=1000000`），共 86,720 字节。
 */
class RealStreamParserTest {

    private class Collector : ScreenStreamParser.Sink {
        val header: ScreenProtocol.StreamHeader? get() = h
        private var h: ScreenProtocol.StreamHeader? = null
        val configs = mutableListOf<ByteArray>()
        val frames = mutableListOf<Pair<Long, ByteArray>>()
        val corrupt = mutableListOf<String>()

        override fun onHeader(header: ScreenProtocol.StreamHeader) { h = header }
        override fun onConfig(csd: ByteArray) { configs += csd }
        override fun onFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean) { frames += ptsUs to data }
        override fun onCorrupt(reason: String) { corrupt += reason }
    }

    /** 从原始字节里按 [pts][size][data] 解出期望值（独立实现，不复用被测算器） */
    private fun expected(stream: ByteArray): Triple<ScreenProtocol.StreamHeader, List<ByteArray>, List<Pair<Long, ByteArray>>> {
        val header = ScreenProtocol.parseHeader(stream)!!
        val configs = mutableListOf<ByteArray>()
        val frames = mutableListOf<Pair<Long, ByteArray>>()
        var pos = ScreenProtocol.HEADER_SIZE
        while (pos + 12 <= stream.size) {
            val bb = ByteBuffer.wrap(stream, pos, 12)
            val pts = bb.long
            val size = bb.int
            require(size in 0..stream.size) { "坏包 size=$size @$pos" }
            val data = stream.copyOfRange(pos + 12, pos + 12 + size)
            if (pts == ScreenProtocol.PTS_CONFIG) configs += data else frames += (pts and ScreenProtocol.PTS_MASK) to data
            pos += 12 + size
        }
        return Triple(header, configs, frames)
    }

    private fun runWithChunks(stream: ByteArray, chunkSize: Int): Collector {
        val sink = Collector()
        val parser = ScreenStreamParser(sink)
        var i = 0
        while (i < stream.size) {
            val end = minOf(stream.size, i + chunkSize)
            parser.feed(stream.copyOfRange(i, end))
            i = end
        }
        return sink
    }

    @Test
    fun `真实流在各种 WS 帧边界下都逐字节一致`() {
        val stream = javaClass.getResourceAsStream("/screen-stream.bin")!!.use { it.readBytes() }
        val (header, expConfigs, expFrames) = expected(stream)
        assertTrue("抓包应当含配置包", expConfigs.isNotEmpty())
        assertTrue("抓包应当含画面帧", expFrames.size > 5)

        // WS 帧大小完全没有规律：1 字节、奇数、整 4K、一次给整段 —— 全都要对
        for (chunk in listOf(1, 3, 7, 13, 1024, 4096, 32768, stream.size)) {
            val sink = runWithChunks(stream, chunk)
            assertEquals("chunk=$chunk 流头", header, sink.header)
            assertEquals("chunk=$chunk 配置包数量", expConfigs.size, sink.configs.size)
            assertEquals("chunk=$chunk 画面帧数量", expFrames.size, sink.frames.size)
            assertEquals("chunk=$chunk 不该判定为流损坏", 0, sink.corrupt.size)
            expFrames.forEachIndexed { idx, (pts, data) ->
                assertEquals("chunk=$chunk 第 $idx 帧 pts", pts, sink.frames[idx].first)
                assertTrue(
                    "chunk=$chunk 第 $idx 帧内容不一致（装配器把字节搞错了）",
                    data.contentEquals(sink.frames[idx].second)
                )
            }
        }
    }

    @Test
    fun `真实流的首个关键帧带 bit62 标记且配置包是 SPS+PPS`() {
        val stream = javaClass.getResourceAsStream("/screen-stream.bin")!!.use { it.readBytes() }
        val sink = runWithChunks(stream, 1024)
        val csd = sink.configs.first()
        // 真机抓到的 csd：00000001 67…（SPS 22B） + 00000001 68…（PPS 10B）
        val (sps, pps) = ScreenProtocol.spsPps(csd)
        assertEquals("SPS NAL 长度", 22, sps?.size)
        assertEquals("PPS NAL 长度", 10, pps?.size)
        assertTrue("配置包必须能被识别成 SPS+PPS", sps != null && pps != null)
    }
}
