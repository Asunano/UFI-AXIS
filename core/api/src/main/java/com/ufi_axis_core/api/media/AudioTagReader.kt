package com.ufi_axis_core.api.media

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer

/**
 * 音频标签读取器（2026-09-16）：**自己解字节**，不依赖 codec。
 *
 * ## 为什么不用 MediaMetadataRetriever
 * 它走系统 extractor / codec 那一套。本机（随身 WiFi 的定制 ROM）连视频画面都解不出来，
 * FLAC 的 Vorbis Comment 也时常读不到 —— 于是 `/tags` 拿回空标签，客户端只能退回文件名，
 * 表现就是"改了接口，歌名还是 `带我走 - 杨丞琳`"。
 *
 * 标签本身是**容器头部的纯字节结构**，不需要任何解码能力：
 * · FLAC → `fLaC` 魔数 + METADATA_BLOCK 链，取 `VORBIS_COMMENT`（type 4）；
 * · MP3 / 带 ID3 的文件 → `ID3` 头 + 帧链，取 `TIT2/TPE1/TALB`（v2.2 是 `TT2/TP1/TAL`）。
 * 所以这里手写解析，与 Web 端 `id3Lyrics.ts` 同一套口径（含 UTF-8 → GBK → Latin-1 嗅探）。
 *
 * ## 只读头部
 * 标签都在文件开头（ID3v1 尾标不管：那是 30 字节定长的老格式，且信息比 v2 差）。
 * 最多读 [MAX_HEAD_BYTES]，读不到就当没有 —— 不为了标签把整个几十 MB 的无损文件拉进内存。
 */
internal object AudioTagReader {

    /** 一首歌的标签。字段取不到就是空串（**不拿文件名冒充**，兜底交给显示层）。 */
    data class Tags(
        val title: String = "",
        val artist: String = "",
        val album: String = ""
    ) {
        val isEmpty: Boolean get() = title.isBlank() && artist.isBlank() && album.isBlank()
    }

    /** 头部读取上限：ID3 里塞了封面时 tag 可以有几 MB，1MB 足够覆盖文本帧（它们排在前面）。 */
    private const val MAX_HEAD_BYTES = 1024 * 1024

    /** FLAC 的 `VORBIS_COMMENT` 块类型。 */
    private const val FLAC_BLOCK_VORBIS_COMMENT = 4

    /** 读文件头并解析标签；文件不存在 / 格式不认识 / 没有标签都回 [Tags] 的空值。 */
    fun read(file: File): Tags {
        if (!file.isFile || file.length() <= 0) return Tags()
        val head = runCatching {
            file.inputStream().buffered().use { input ->
                val size = minOf(file.length(), MAX_HEAD_BYTES.toLong()).toInt()
                val buffer = ByteArray(size)
                var read = 0
                while (read < size) {
                    val n = input.read(buffer, read, size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == size) buffer else buffer.copyOf(read)
            }
        }.getOrNull() ?: return Tags()

        return when {
            isFlac(head) -> readFlac(head)
            isId3v2(head) -> readId3(head)
            else -> Tags()
        }
    }

    // ───────────────────────── FLAC ─────────────────────────

    private fun isFlac(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte()

    /**
     * 遍历 METADATA_BLOCK 链找 `VORBIS_COMMENT`。
     *
     * 块头 4 字节：最高位 = 是否最后一块，低 7 位 = 类型，后 3 字节 = 大端长度。
     */
    private fun readFlac(bytes: ByteArray): Tags {
        var pos = 4
        while (pos + 4 <= bytes.size) {
            val header = bytes[pos].toInt() and 0xFF
            val isLast = header and 0x80 != 0
            val type = header and 0x7F
            val length = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val body = pos + 4
            if (length < 0 || body + length > bytes.size) return Tags()
            if (type == FLAC_BLOCK_VORBIS_COMMENT) {
                return vorbisTags(bytes, body, length)
            }
            if (isLast) return Tags()
            pos = body + length
        }
        return Tags()
    }

    /**
     * Vorbis Comment：vendor 串 + 条目数 + 逐条 `KEY=value`，长度都是**小端** 32bit。
     *
     * 值一律 UTF-8（规范如此）。artist 缺失时退 `albumartist` / `album artist` ——
     * 有些转码工具只写后者。
     */
    private fun vorbisTags(bytes: ByteArray, offset: Int, length: Int): Tags {
        var pos = offset
        val end = offset + length
        fun readLe32(): Int {
            if (pos + 4 > end) return -1
            val v = (bytes[pos].toInt() and 0xFF) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        val vendorLen = readLe32()
        if (vendorLen < 0 || pos + vendorLen > end) return Tags()
        pos += vendorLen
        val count = readLe32()
        if (count <= 0) return Tags()

        val map = HashMap<String, String>()
        repeat(count) {
            val len = readLe32()
            if (len < 0 || pos + len > end) return@repeat
            val entry = String(bytes, pos, len, Charsets.UTF_8)
            pos += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).lowercase()
                val value = entry.substring(eq + 1).trim()
                if (value.isNotEmpty() && !map.containsKey(key)) map[key] = value
            }
        }
        return Tags(
            title = map["title"].orEmpty(),
            artist = map["artist"] ?: map["albumartist"] ?: map["album artist"].orEmpty(),
            album = map["album"].orEmpty()
        )
    }

    // ───────────────────────── ID3v2 ─────────────────────────

    private fun isId3v2(bytes: ByteArray): Boolean =
        bytes.size > 10 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte()

    /**
     * 遍历 ID3v2 帧链取文本帧。
     *
     * 三个版本的帧头不一样（这也是最容易写错的地方）：
     * · v2.2：3 字符 ID + 3 字节大端长度，帧体从 +6 开始；
     * · v2.3：4 字符 ID + 4 字节**大端**长度 + 2 字节 flag，帧体从 +10；
     * · v2.4：4 字符 ID + 4 字节**synchsafe**长度 + 2 字节 flag，帧体从 +10。
     */
    private fun readId3(bytes: ByteArray): Tags {
        val major = bytes[3].toInt() and 0xFF
        val tagSize = synchsafe(bytes, 6)
        val end = minOf(bytes.size, 10 + tagSize)
        var pos = 10
        var title = ""
        var artist = ""
        var album = ""

        while (pos + 6 < end) {
            val idLen = if (major <= 2) 3 else 4
            if (pos + idLen > end) break
            val id = String(bytes, pos, idLen, Charsets.ISO_8859_1)
            if (id.isBlank() || id[0] == '\u0000') break

            val size: Int
            val body: Int
            when {
                major <= 2 -> {
                    size = ((bytes[pos + 3].toInt() and 0xFF) shl 16) or
                        ((bytes[pos + 4].toInt() and 0xFF) shl 8) or
                        (bytes[pos + 5].toInt() and 0xFF)
                    body = pos + 6
                }
                major == 3 -> {
                    size = ((bytes[pos + 4].toInt() and 0xFF) shl 24) or
                        ((bytes[pos + 5].toInt() and 0xFF) shl 16) or
                        ((bytes[pos + 6].toInt() and 0xFF) shl 8) or
                        (bytes[pos + 7].toInt() and 0xFF)
                    body = pos + 10
                }
                else -> {
                    size = synchsafe(bytes, pos + 4)
                    body = pos + 10
                }
            }
            if (size <= 0 || body + size > end) break

            val value = when (id) {
                "TIT2", "TT2", "TPE1", "TP1", "TALB", "TAL" ->
                    decodeTextFrame(bytes, body, size)
                else -> null
            }
            if (!value.isNullOrBlank()) {
                when (id) {
                    "TIT2", "TT2" -> if (title.isBlank()) title = value
                    "TPE1", "TP1" -> if (artist.isBlank()) artist = value
                    "TALB", "TAL" -> if (album.isBlank()) album = value
                }
            }
            if (title.isNotBlank() && artist.isNotBlank() && album.isNotBlank()) break
            pos = body + size
        }
        return Tags(title = title, artist = artist, album = album)
    }

    /** synchsafe 整数：4 字节，每字节只用低 7 位。 */
    private fun synchsafe(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    /**
     * 文本帧：首字节是编码标志，其后是内容。
     *
     * 0 = ISO-8859-1（**实际上一堆国产工具在这里塞 GBK 或 UTF-8**，所以要嗅探）、
     * 1 = UTF-16 带 BOM、2 = UTF-16BE、3 = UTF-8。结尾可能带 `\u0000` 填充，要削掉。
     */
    private fun decodeTextFrame(bytes: ByteArray, offset: Int, size: Int): String {
        if (size <= 1) return ""
        val encoding = bytes[offset].toInt() and 0xFF
        val from = offset + 1
        val len = size - 1
        val text = when (encoding) {
            1, 2 -> decodeUtf16(bytes, from, len)
            3 -> String(bytes, from, len, Charsets.UTF_8)
            else -> sniffLegacy(bytes, from, len)
        }
        return text.replace("\u0000", "").trim()
    }

    private fun decodeUtf16(bytes: ByteArray, offset: Int, length: Int): String {
        if (length >= 2) {
            val b0 = bytes[offset].toInt() and 0xFF
            val b1 = bytes[offset + 1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                return String(bytes, offset + 2, length - 2, Charsets.UTF_16LE)
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return String(bytes, offset + 2, length - 2, Charsets.UTF_16BE)
            }
        }
        return String(bytes, offset, length, Charsets.UTF_16BE)
    }

    /**
     * 标注成 Latin-1 的字节，实际编码嗅探：UTF-8 → GBK → Latin-1。
     *
     * 全 ASCII 时三者结果一致，直接按 Latin-1 走；出现高位字节才逐个严格解码试探
     * （`CodingErrorAction.REPORT` 保证"能解通"才算命中，避免拿到一串问号）。
     */
    private fun sniffLegacy(bytes: ByteArray, offset: Int, length: Int): String {
        var hasHighByte = false
        for (i in offset until offset + length) {
            if ((bytes[i].toInt() and 0xFF) >= 0x80) {
                hasHighByte = true
                break
            }
        }
        if (!hasHighByte) return String(bytes, offset, length, Charsets.ISO_8859_1)
        strictDecode("UTF-8", bytes, offset, length)?.let { return it }
        strictDecode("GBK", bytes, offset, length)?.let { return it }
        return String(bytes, offset, length, Charsets.ISO_8859_1)
    }

    private fun strictDecode(charset: String, bytes: ByteArray, offset: Int, length: Int): String? =
        runCatching {
            Charset.forName(charset).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()
        }.getOrNull()
}
