package com.ufi_axis.adbcore

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * ADB 报文头编解码。
 *
 * 报文结构（全部小端）：
 * ```
 * offset size field
 * 0      4    command       ASCII 命令字
 * 4      4    arg0
 * 8      4    arg1
 * 12     4    data_length   payload 字节数
 * 16     4    data_checksum CRC32(payload)
 * 20     4    magic_ok      magic = command xor 0xFFFFFFFF
 * 24     N    payload
 * ```
 */
object AdbProtocol {

    const val HEADER_SIZE = 24

    // ---- 命令字 ----
    const val CMD_CNXN = 0x4E584E43 // "CNXN"
    const val CMD_AUTH = 0x48545541 // "AUTH"
    const val CMD_OPEN = 0x4E45504F // "OPEN"
    const val CMD_OKAY = 0x59414B4F // "OKAY"
    const val CMD_CLSE = 0x45534C43 // "CLSE"
    const val CMD_WRTE = 0x45545257 // "WRTE"
    const val CMD_SYNC = 0x434E5953 // "SYNC"
    const val CMD_STLS = 0x534C5453 // "STLS"

    // ---- AUTH arg0 ----
    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    // ---- 版本位 ----
    const val A_VERSION_MIN = 0x00000001
    const val A_VERSION_SKIP_CHECKSUM = 0x00000002
    const val A_VERSION_MAX_PAYLOAD_V1 = 0x00100000

    /** host 侧声明的版本：最低版 + 跳过校验和 + 支持大 payload */
    const val A_VERSION = A_VERSION_MIN or A_VERSION_SKIP_CHECKSUM or A_VERSION_MAX_PAYLOAD_V1

    /** 推荐的最大分片大小（256KB），实际取与对端协商的较小值 */
    const val MAX_DATA = 256 * 1024

    const val TOKEN_SIZE = 20

    /** magic = cmd xor 0xFFFFFFFF */
    fun magicOf(cmd: Int): Int = cmd xor 0xFFFFFFFF.toInt()

    /** 命令字对应的可读名，用于日志 */
    fun cmdName(cmd: Int): String = when (cmd) {
        CMD_CNXN -> "CNXN"
        CMD_AUTH -> "AUTH"
        CMD_OPEN -> "OPEN"
        CMD_OKAY -> "OKAY"
        CMD_CLSE -> "CLSE"
        CMD_WRTE -> "WRTE"
        CMD_SYNC -> "SYNC"
        CMD_STLS -> "STLS"
        else -> "0x%08X".format(cmd)
    }

    /**
     * ADB 要求校验和按 **大端（MSB-first）** 写入报文头，
     * 与其余字段的小端相反 —— 这点极易搞错，且错了设备会直接丢弃报文。
     */
    fun crc32(payload: ByteArray): Int {
        if (payload.isEmpty()) return 0
        val crc = CRC32()
        crc.update(payload)
        val v = crc.value and 0xFFFFFFFFL
        return reverseBytes(v.toInt())
    }

    /** 32 位整数按字节翻转（用于 CRC 字段的大小端转换） */
    fun reverseBytes(value: Int): Int =
        ((value and 0xFF) shl 24) or
            ((value and 0xFF00) shl 8) or
            ((value ushr 8) and 0xFF00) or
            ((value ushr 24) and 0xFF)

    /**
     * 编码一个完整报文（头 + payload）。
     *
     * 注意：即使设置了 SKIP_CHECKSUM，checksum 字段仍必须写入有效值，
     * 部分设备会拒收 checksum 为 0 的非空 payload 报文。
     */
    fun encode(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray = EMPTY): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        putInt(out, 0, cmd)
        putInt(out, 4, arg0)
        putInt(out, 8, arg1)
        putInt(out, 12, payload.size)
        putInt(out, 16, crc32(payload))
        putInt(out, 20, magicOf(cmd))
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }

    fun decodeHeader(buf: ByteArray, offset: Int = 0): AdbHeader = AdbHeader(
        cmd = getInt(buf, offset),
        arg0 = getInt(buf, offset + 4),
        arg1 = getInt(buf, offset + 8),
        dataLength = getInt(buf, offset + 12),
        dataChecksum = getInt(buf, offset + 16),
        magic = getInt(buf, offset + 20)
    )

    /** 校验 magic 是否自洽 */
    fun verifyMagic(header: AdbHeader): Boolean = header.magic == magicOf(header.cmd)

    // ---- 小端读写 ----

    fun putInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    fun getInt(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    fun intToBytes(value: Int): ByteArray {
        val b = ByteArray(4)
        putInt(b, 0, value)
        return b
    }

    /** 小端整数的字节表示（4 字节），用于 SYNC/shell v2 子协议 */
    fun le32(value: Int): ByteArray = intToBytes(value)

    fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    /**
     * 判断字节数组是否等于某个 ASCII 常量。
     *
     * 注意：不能用 `bytes.contentEquals("STR")` —— Kotlin 会解析为与 CharSequence
     * 的跨类型比较，永远返回 false。这个坑会静默吞掉所有协议分支。
     */
    fun equalsAscii(bytes: ByteArray, offset: Int, ascii: String): Boolean {
        if (offset < 0 || offset + ascii.length > bytes.size) return false
        for (i in ascii.indices) {
            if (bytes[offset + i] != ascii[i].code.toByte()) return false
        }
        return true
    }

    fun equalsAscii(bytes: ByteArray, ascii: String): Boolean = equalsAscii(bytes, 0, ascii)

    fun toAsciiTrimmed(bytes: ByteArray): String =
        String(bytes, Charsets.US_ASCII).trim { it == '\u0000' || it == ' ' || it == '\n' || it == '\r' }

    val EMPTY = ByteArray(0)

    /** 把多个字节数组拼接 */
    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}

/** 解析后的报文头 */
data class AdbHeader(
    val cmd: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val dataChecksum: Int,
    val magic: Int
) {
    /** CNXN 报文里 arg1 是对端允许的最大分片 */
    val peerMaxData: Int get() = arg1

    /** CNXN 报文里 arg0 是对端版本号 */
    val peerVersion: Int get() = arg0
}
