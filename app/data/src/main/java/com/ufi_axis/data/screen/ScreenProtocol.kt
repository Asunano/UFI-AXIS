package com.ufi_axis.data.screen

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * scrcpy 视频流与控制消息的二进制协议（2026-11-01，原生客户端用）。
 *
 * ## 这份东西的格式是怎么确定的
 * 不是照文档猜的，是**实测抓包**对出来的（见 `dev-docs/UFI-AXIS-远程屏幕-实施手册-v0.5.md`）：
 * 连上 `@scrcpy` 后头 76 字节是 `64B 设备名 + "h264" + u32 宽 + u32 高`，
 * 紧接着第一个包是 `pts=0x8000000000000000`（配置包，装 SPS/PPS）。
 *
 * ## 为什么单独成文件
 * 这里是**纯字节运算**，不依赖 OkHttp / MediaCodec / Android，可以直接单测 ——
 * 而这层一旦算错，表现是"黑屏但没报错"，最难受的那种。所以它必须可测。
 *
 * 全部**大端**（scrcpy 的 struct 都是 big-endian）。
 */
object ScreenProtocol {

    /** 流头长度：设备名 64 + codec 4 + 宽 4 + 高 4 */
    const val HEADER_SIZE = 76
    private const val DEVICE_NAME_LEN = 64

    /** `pts` 的最高位为 1 表示这是编解码器配置（SPS/PPS），不是画面帧 */
    const val PTS_CONFIG: Long = Long.MIN_VALUE // 1L shl 63

    /**
     * `pts` 的次高位 = **关键帧（IDR）标记**（scrcpy 2.0+ `PACKET_FLAG_KEY_FRAME`）。
     *
     * 它与 [PTS_CONFIG] 是两个位，不要合并判断：配置包的 pts **恰好**等于 `1L shl 63`，
     * 画面帧的 pts 是"真实微秒 | 可能的 bit62"。
     *
     * 这个标记的唯一用途是**丢帧后的重新同步**：H.264 的 P 帧依赖前序帧，
     * 一旦丢过帧，后续帧会一直花屏到下一个 IDR。知道哪个包是 IDR，就能"跳到它再继续"。
     */
    const val PTS_KEYFRAME: Long = 1L shl 62

    /** 剥掉"配置/关键帧"两个标记位，剩下的才是真正的微秒时间戳 */
    const val PTS_MASK: Long = 0x3FFF_FFFF_FFFF_FFFFL

    /** 每个媒体包的头：pts u64 + size u32 */
    const val PACKET_HEADER_SIZE = 12

    // 控制消息类型（与 scrcpy `ScrcpyControlMessageType` 一致）
    const val CTRL_INJECT_KEYCODE = 0
    const val CTRL_INJECT_TEXT = 1
    const val CTRL_INJECT_TOUCH = 2
    const val CTRL_INJECT_SCROLL = 3
    const val CTRL_SET_CLIPBOARD = 9

    // 触摸动作（Android MotionEvent action）
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_CANCEL = 3

    /** 手指指针 id：scrcpy 约定负数，`-2` = finger */
    const val POINTER_FINGER = -2L

    data class StreamHeader(val deviceName: String, val width: Int, val height: Int, val codec: String)

    /** 流头解析。长度不足返回 null（调用方继续攒字节）。 */
    fun parseHeader(buf: ByteArray, offset: Int = 0, length: Int = buf.size): StreamHeader? {
        if (length - offset < HEADER_SIZE) return null
        val nameBytes = buf.copyOfRange(offset, offset + DEVICE_NAME_LEN)
        // 设备名是 NUL 填充的定长字段，截到第一个 0
        val nameEnd = nameBytes.indexOf(0).let { if (it < 0) nameBytes.size else it }
        val deviceName = String(nameBytes, 0, nameEnd, StandardCharsets.UTF_8)
        val codec = String(buf, offset + DEVICE_NAME_LEN, 4, StandardCharsets.UTF_8).trim('\u0000')
        val bb = ByteBuffer.wrap(buf, offset + 68, 8)
        return StreamHeader(deviceName, bb.int, bb.int, codec)
    }

    /** 一个媒体包 */
    data class MediaPacket(val pts: Long, val data: ByteArray) {
        val isConfig: Boolean get() = pts == PTS_CONFIG
    }

    /**
     * 从 [buffer] 里按顺序取出所有完整包，返回包列表与消费到的位置。
     *
     * 用"给一段字节、吐出能解析的包、告诉我吃到哪"的形式，是因为 WS 帧边界与包边界
     * **完全无关**：一个包可能跨多个帧，一个帧也可能含多个包。
     */
    fun drainPackets(buffer: ByteArray): Pair<List<MediaPacket>, Int> {
        val out = ArrayList<MediaPacket>()
        var pos = 0
        while (buffer.size - pos >= PACKET_HEADER_SIZE) {
            val bb = ByteBuffer.wrap(buffer, pos, PACKET_HEADER_SIZE)
            val pts = bb.long
            val size = bb.int
            if (size < 0 || size > buffer.size - pos - PACKET_HEADER_SIZE) break // 还没收全
            val start = pos + PACKET_HEADER_SIZE
            out += MediaPacket(pts, buffer.copyOfRange(start, start + size))
            pos = start + size
        }
        return out to pos
    }

    /**
     * 触摸消息（32 字节）。
     *
     * 布局（大端）：type u8 / action u8 / pointerId i64 / x u32 / y u32 /
     * videoWidth u16 / videoHeight u16 / pressure u16 / actionButton u32 / buttons u32。
     *
     * `videoWidth/Height` 必须是**视频流的分辨率**，不是控件尺寸 —— 服务端用它做边界钳制，
     * 传错会让所有点击整体偏移。
     */
    fun touch(
        action: Int,
        x: Int,
        y: Int,
        videoWidth: Int,
        videoHeight: Int,
        pressure: Int = 0xFFFF,
    ): ByteArray {
        val bb = ByteBuffer.allocate(32)
        bb.put(CTRL_INJECT_TOUCH.toByte())
        bb.put(action.toByte())
        bb.putLong(POINTER_FINGER)
        bb.putInt(x)
        bb.putInt(y)
        bb.putShort(videoWidth.toShort())
        bb.putShort(videoHeight.toShort())
        bb.putShort(pressure.toShort())
        bb.putInt(0) // actionButton
        bb.putInt(0) // buttons
        return bb.array()
    }

    /** 按键消息（14 字节）：type u8 / action u8 / keyCode u32 / repeat u32 / metaState u32 */
    fun key(action: Int, keyCode: Int, repeat: Int = 0, metaState: Int = 0): ByteArray {
        val bb = ByteBuffer.allocate(14)
        bb.put(CTRL_INJECT_KEYCODE.toByte())
        bb.put(action.toByte())
        bb.putInt(keyCode)
        bb.putInt(repeat)
        bb.putInt(metaState)
        return bb.array()
    }

    /**
     * 剪贴板 + 粘贴（用于中文）：
     * type u8 / sequence u64 / paste u8 / 长度 u32 / UTF-8 内容。
     * `sequence = 0` 表示不要回执（要回执就得接设备消息通道）。
     */
    fun clipboard(text: String, paste: Boolean = true): ByteArray {
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        val bb = ByteBuffer.allocate(1 + 8 + 1 + 4 + payload.size)
        bb.put(CTRL_SET_CLIPBOARD.toByte())
        bb.putLong(0L)
        bb.put(if (paste) 1 else 0)
        bb.putInt(payload.size)
        bb.put(payload)
        return bb.array()
    }

    /** 文本注入（仅 ASCII 可靠）：type u8 / 长度 u32 / UTF-8 */
    fun text(text: String): ByteArray {
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        val bb = ByteBuffer.allocate(1 + 4 + payload.size)
        bb.put(CTRL_INJECT_TEXT.toByte())
        bb.putInt(payload.size)
        bb.put(payload)
        return bb.array()
    }

    /**
     * 从配置包里**按 NAL 类型**挑出 SPS(7) / PPS(8)，保留起始码。
     *
     * ## 为什么不能直接取"第 1、2 个 NAL"
     * 部分编码器的 csd 前面还有一个 AUD（NAL 类型 9），也有把 SEI 放在 SPS 前面的。
     * 按位置取会把 AUD/SEI 当成 `csd-0` 交给 `MediaFormat` —— 解码器要么起不来，
     * 要么每帧都当解析失败，表现同样是**黑屏且不报错**（2026-11-01 复核时补的）。
     *
     * 找不到返回 null，由调用方决定退回"按顺序取"还是直接报错。
     */
    fun spsPps(config: ByteArray): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in splitCsd(config)) {
            // 起始码可能是 3 或 4 字节，NAL header 紧跟其后
            val headerIndex = if (nal.size > 2 && nal[2] == 1.toByte()) 3 else 4
            if (headerIndex >= nal.size) continue
            when (nal[headerIndex].toInt() and 0x1F) {
                7 -> if (sps == null) sps = nal
                8 -> if (pps == null) pps = nal
            }
        }
        return sps to pps
    }

    /**
     * 把配置包拆成 SPS / PPS 两个 NAL（MediaCodec 的 csd-0 / csd-1 要分开给）。
     * 每个 NAL 保留 4 字节起始码 —— Android 的 AVC 解码器接受带起始码的 CSD。
     */
    fun splitCsd(config: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        var start = -1
        while (i <= config.size - 4) {
            val isSc4 = config[i] == 0.toByte() && config[i + 1] == 0.toByte() &&
                config[i + 2] == 0.toByte() && config[i + 3] == 1.toByte()
            val isSc3 = config[i] == 0.toByte() && config[i + 1] == 0.toByte() &&
                config[i + 2] == 1.toByte()
            if (isSc4 || isSc3) {
                if (start >= 0) out += config.copyOfRange(start, i)
                start = i
                i += if (isSc4) 4 else 3
            } else {
                i++
            }
        }
        if (start >= 0) out += config.copyOfRange(start, config.size)
        return out
    }
}
