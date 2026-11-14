package com.ufi_axis.data.screen

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * scrcpy 视频流的**字节装配器**（2026-11-01 从 `ScreenStreamClient` 抽出来）。
 *
 * ## 为什么单独成类
 * WS 的帧边界、scrcpy 的包边界、流头这三者**完全无关**：
 * - 流头 76 字节可能被拆成 2 个 WS 帧；
 * - 一个 WS 帧可能装 3 个完整包 + 半个包；
 * - 半包必须留到下一次 `feed()` 再拼。
 *
 * 这段状态机是最容易算错、且算错后**表现为"黑屏但不报错"**的地方（包头错 12 字节，
 * 解码器就一直等不到完整帧）。它不该埋在 WS 回调里：那样只能靠真机试，出问题也无法复现。
 * 现在它是纯函数式的（喂字节 → 吐回调），可以直接单测每一种切分方式。
 *
 * ## 线程
 * 不是线程安全的：约定**只在 WS 回调线程上调用**（OkHttp 保证同一连接的 onMessage 串行）。
 */
class ScreenStreamParser(
    private val sink: Sink,
    /** 单个媒体包上限。超过就当成流错位（848p 一帧正常 < 200KB） */
    private val maxPacketBytes: Int = DEFAULT_MAX_PACKET_BYTES,
) {

    interface Sink {
        /** 流头解析完成（只回调一次） */
        fun onHeader(header: ScreenProtocol.StreamHeader)

        /** 编解码器配置（SPS/PPS），解码器要用它初始化 */
        fun onConfig(csd: ByteArray)

        /**
         * 一帧画面。`ptsUs` 已剥掉配置位与关键帧位（真正的微秒）；
         * `keyframe` = 这一帧是 IDR（丢过帧后可以靠它重新同步）。
         */
        fun onFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean)

        /** 流结构已错乱（包头里的长度离谱）：调用方应断开重连，攒再多也拼不回来 */
        fun onCorrupt(reason: String)
    }

    /** 未消费的字节（含半个包 / 半个流头） */
    private val acc = ByteArrayOutputStream()

    /** 已解析的流头；null = 还没攒够 76 字节 */
    var header: ScreenProtocol.StreamHeader? = null
        private set

    /** 收到的字节总数（排障用：为 0 说明一条都没到，为 64 说明只到了设备名） */
    var receivedBytes: Long = 0L
        private set

    /** 已吐出的媒体包数（不含配置包） */
    var frameCount: Long = 0L
        private set

    /** 连接断开 / 重连时清空。不清的话新会话的头会拼在旧会话残包后面。 */
    @Synchronized
    fun reset() {
        acc.reset()
        header = null
    }

    /**
     * 喂一段字节。
     *
     * `@Synchronized`：OkHttp 的回调线程与页面线程（断开/切模式调 [reset]）会并发碰同一个
     * `ByteArrayOutputStream`。它不是线程安全的，一次并发 `reset()` 就能把积累缓冲写乱，
     * 表现是"流里凭空多出/少了几个字节"—— 这正是 2026-11-01 真机上那种
     * 「头解析正常、紧接着包长度离谱」的样子。
     */
    @Synchronized
    fun feed(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        receivedBytes += chunk.size
        acc.write(chunk)
        val buf = acc.toByteArray()

        var offset = 0
        if (header == null) {
            val h = ScreenProtocol.parseHeader(buf) ?: return // 还不足 76 字节
            header = h
            sink.onHeader(h)
            offset = ScreenProtocol.HEADER_SIZE
        }

        val headerJustParsed = offset > 0
        var consumed = offset
        while (buf.size - consumed >= ScreenProtocol.PACKET_HEADER_SIZE) {
            val bb = ByteBuffer.wrap(buf, consumed, ScreenProtocol.PACKET_HEADER_SIZE)
            val pts = bb.long
            val size = bb.int
            // 长度离谱 = 已经错位。继续按它读只会把后面的数据当垃圾，直接重连更省事。
            if (size < 0 || size > maxPacketBytes) {
                // 诊断信息一次给全：偏移、是否刚解析完流头、以及出错位置前后 24 字节的十六进制。
                // 没有 hex 的话，"包长度离谱"只能靠猜（是断流、错位，还是上游把别的东西塞进来）。
                val detail = buildString {
                    append("包长度异常 size=")
                    append(size)
                    append(" @")
                    append(consumed)
                    append("（已收 ")
                    append(receivedBytes)
                    append("B，头刚解析=")
                    append(if (headerJustParsed) "是" else "否")
                    append("，hex=")
                    append(hexAround(buf, consumed, 24))
                    append("）")
                }
                acc.reset()
                header = null
                sink.onCorrupt(detail)
                return
            }
            if (size > buf.size - consumed - ScreenProtocol.PACKET_HEADER_SIZE) break // 还没收全
            val start = consumed + ScreenProtocol.PACKET_HEADER_SIZE
            val data = buf.copyOfRange(start, start + size)
            consumed = start + size

            if (pts == ScreenProtocol.PTS_CONFIG) {
                sink.onConfig(data)
            } else {
                frameCount++
                // ★ 必须同时剥掉 bit62（关键帧标记）。只掩 bit63 的话，关键帧的 pts 会是
                // 2^62 微秒量级，喂给 MediaCodec 之后时间戳完全失真（有些实现会因此不吐帧）。
                sink.onFrame(data, pts and ScreenProtocol.PTS_MASK, (pts and ScreenProtocol.PTS_KEYFRAME) != 0L)
            }
        }

        // 把没消费完的部分（半包）留在缓冲里等下一次
        if (consumed > 0) {
            acc.reset()
            if (consumed < buf.size) acc.write(buf, consumed, buf.size - consumed)
        }
    }

    /** 把 [offset] 起 [n] 个字节打成十六进制（越界就截断），只在出错时调，不在热路径上 */
    private fun hexAround(buf: ByteArray, offset: Int, n: Int): String {
        val end = minOf(buf.size, offset + n)
        val sb = StringBuilder()
        var i = offset
        while (i < end) {
            sb.append(String.format("%02x", buf[i].toInt() and 0xFF))
            i++
            if (i < end && (i - offset) % 4 == 0) sb.append(' ')
        }
        return sb.toString()
    }

    companion object {
        /** 单包上限 2MB：正常 848p 帧 < 200KB，超过就是流错位了 */
        const val DEFAULT_MAX_PACKET_BYTES = 2 * 1024 * 1024
    }
}
