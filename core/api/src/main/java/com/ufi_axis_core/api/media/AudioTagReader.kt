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
 *
 * ## 内嵌封面（2026-10-13，Phase 1）
 * [readCover] 另外解析 ID3v2 的 `APIC` / FLAC 的 `PICTURE`。它**不复用** [read] 的缓冲读法，
 * 而是 `RandomAccessFile` 逐帧 / 逐块 seek —— 封面常排在 tag 末尾，为它把 [MAX_HEAD_BYTES]
 * 提到 4MB 等于让每次请求都可能吃掉 4MB 常驻内存。理由与三道校验见 [readCover] 的 KDoc。
 *
 * ## 对损坏文件的健壮性（2026-10-13 修复）
 * 帧长 / 条目长度都是从文件读的**裸 32 位**，所以所有 `body + size` 一类的边界检查
 * **必须用 Long 运算**：Int 溢出会把它变成负数、让检查恒为 false，随后越界取值抛异常。
 * 调用方（`MediaRoutes.audioTagsOf`）没有 runCatching 兜底，所以这类异常会直接变成 500。
 * `AudioTagReaderTest` 有两条用例专门钉住这件事。
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

    /**
     * 一张内嵌封面（ID3v2 `APIC` / FLAC `PICTURE` 的原始字节）。
     *
     * [mime] 取自容器里声明的类型（不是靠魔数猜）；声明缺失或不可信时由 [readCover]
     * 按魔数兜底填。
     */
    data class Cover(val bytes: ByteArray, val mime: String) {
        // data class 带 ByteArray 必须手写这两个，否则比的是引用（会静默给出错误结论）
        override fun equals(other: Any?): Boolean =
            this === other || (other is Cover && mime == other.mime && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + mime.hashCode()
    }

    /** 头部读取上限：ID3 里塞了封面时 tag 可以有几 MB，1MB 足够覆盖文本帧（它们排在前面）。 */
    private const val MAX_HEAD_BYTES = 1024 * 1024

    /**
     * 单张内嵌封面的大小上限（2026-10-13）。
     *
     * 封面是**原图字节**，实测常见 50KB~500KB，个别"把 CD 内页扫进去"的能到 1~2MB。
     * 4MB 已经很宽松，而它是**必须有的封顶**：`readCover` 会把整张图读进内存，
     * 没有上限的话一个畸形文件（或声称自己有 2GB 封面的构造文件）就能顶爆这台 1.5GB 设备。
     */
    private const val MAX_EMBEDDED_ART_BYTES = 4 * 1024 * 1024

    /** 封面最小字节数：再小不可能是张图（与 MediaRoutes 的落盘校验同一口径）。 */
    private const val MIN_EMBEDDED_ART_BYTES = 512

    /** FLAC 的 `VORBIS_COMMENT` 块类型。 */
    private const val FLAC_BLOCK_VORBIS_COMMENT = 4

    /** FLAC 的 `PICTURE` 块类型（内嵌封面）。 */
    private const val FLAC_BLOCK_PICTURE = 6

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

    // ═══════════════════════ 内嵌封面（2026-10-13，Phase 1） ═══════════════════════

    /**
     * 读**内嵌封面**（ID3v2 的 `APIC`/`PIC`，或 FLAC 的 `PICTURE` 块）。
     *
     * ## 为什么需要它
     * 音频封面原本来自"系统缩略图（`MediaProvider`）→ `MediaMetadataRetriever.embeddedPicture`"。
     * 前者正是 `android.process.media` 涨到 471MB 的元凶之一，正在被整体退役；
     * 而封面本身**只是容器头部的纯字节结构**，与文本标签一样不需要任何解码能力 ——
     * 所以自己解，与 [read] 完全同一个理由（见类 KDoc）。
     *
     * ## 为什么与 [read] 分开
     * `GET /api/media/tags` 只要文本标签，为它顺带读一张几 MB 的封面是纯浪费。
     * 两个入口各读各的，调用方按需选。
     *
     * ## 与 [read] 的实现差异：按需 seek，不整段读进内存
     * [read] 把头部最多 1MB 读进 `ByteArray` 再解析 —— 对文本帧够用（它们排在前面），
     * 但封面**经常排在最后**，为它把上限提到 4MB 就等于每次请求都可能吃掉 4MB 常驻内存。
     * 这里用 `RandomAccessFile` 逐帧 / 逐块走：只把**真正要的那张图**读进内存，其余只移动指针。
     *
     * ## 校验（三道，缺一不可）
     *  · 声明长度 > [MAX_EMBEDDED_ART_BYTES] + 元数据余量 → **不读、不分配**，直接跳过；
     *  · 读回来的字节 < [MIN_EMBEDDED_ART_BYTES] → 不是图，放弃；
     *  · 魔数必须是 JPEG / PNG → 否则放弃（**容器里声明的 MIME 不可信，以魔数为准**）。
     *
     * ⚠️ **不要**改用 `FrameValidator` 校验封面：它的 `MIN_BYTES = 2048` 是为"全零视频帧"
     * 定的，会把合法的小封面判死；`DARK_THRESHOLD` 对封面也没有任何意义。
     *
     * @return 拿不到就回 null（文件不存在 / 不是 ID3·FLAC / 没有封面帧 / 校验不过）。
     */
    fun readCover(file: File): Cover? {
        if (!file.isFile || file.length() <= 0) return null
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                if (raf.read(magic) < 4) return@use null
                when {
                    hasFlacMagic(magic) -> readFlacCover(raf)
                    hasId3Magic(magic) -> readId3Cover(raf)
                    else -> null
                }
            }
        }.getOrNull()
    }

    /** `pictureType == 3` = front cover（ID3 与 FLAC 用的是同一套 APIC 图片类型编号）。 */
    private const val PICTURE_TYPE_FRONT_COVER = 3

    /** 封面帧里除图像本身之外的元数据余量（MIME + 描述 + 尺寸字段，实测远小于此）。 */
    private const val MAX_PICTURE_META_BYTES = 4096

    // ───────────────────────── 封面：ID3v2 ─────────────────────────

    /**
     * 遍历 ID3v2 帧链找 `APIC`（v2.3/2.4）/ `PIC`（v2.2）。
     *
     * 帧体结构（顺序固定）：
     * ```
     * encoding(1) + MIME(v2.2 是 3 字符如 "JPG"；其余是 NUL 结尾的 ISO-8859-1) +
     * pictureType(1) + description(按 encoding 定宽，NUL 结尾) + 图像数据
     * ```
     *
     * 有多张图时优先 [PICTURE_TYPE_FRONT_COVER]，没有则用第一张能解出来的。
     */
    private fun readId3Cover(raf: java.io.RandomAccessFile): Cover? {
        val header = ByteArray(10)
        raf.seek(0)
        if (raf.read(header) < 10) return null
        val major = header[3].toInt() and 0xFF
        val tagEnd = 10L + synchsafe(header, 6)
        val fileLen = raf.length()
        val headerLen = if (major <= 2) 6 else 10

        var pos = 10L
        var firstCover: Cover? = null
        while (pos + headerLen <= tagEnd && pos + headerLen <= fileLen) {
            raf.seek(pos)
            val fhBytes = ByteArray(headerLen)
            if (raf.read(fhBytes) < headerLen) break
            val fh = parseFrameHeader(major, fhBytes, 0) ?: break
            val bodyStart = pos + fh.headerLen
            // 帧体必须完整落在 tag 与文件之内（Long 运算防溢出，同 readId3 那条教训）
            if (bodyStart + fh.bodySize > tagEnd || bodyStart + fh.bodySize > fileLen) break

            if ((fh.id == "APIC" || fh.id == "PIC") &&
                fh.bodySize <= MAX_EMBEDDED_ART_BYTES + MAX_PICTURE_META_BYTES
            ) {
                val body = ByteArray(fh.bodySize)
                raf.seek(bodyStart)
                if (raf.read(body) == fh.bodySize) {
                    parseApic(body, major)?.let { pic ->
                        if (pic.pictureType == PICTURE_TYPE_FRONT_COVER) return pic.cover
                        if (firstCover == null) firstCover = pic.cover
                    }
                }
            }
            pos = bodyStart + fh.bodySize
        }
        return firstCover
    }

    /** 封面解析结果 + 图片类型（用来在多张图里挑 front cover）。 */
    private class Picture(val cover: Cover, val pictureType: Int)

    /**
     * 从 [from] 起找第一个 `0x00`，找不到回 -1。
     *
     * 自己写是因为 `ByteArray` **没有**带起始偏移的 `indexOf`（那个重载只有 `List` 有），
     * 而 NUL 结尾字符串的解析处处要从当前位置往后找。
     */
    private fun ByteArray.indexOfZero(from: Int): Int {
        var i = from
        while (i < size) {
            if (this[i] == 0.toByte()) return i
            i++
        }
        return -1
    }

    private fun parseApic(body: ByteArray, major: Int): Picture? {
        if (body.size < 4) return null
        val encoding = body[0].toInt() and 0xFF
        var pos = 1

        // MIME：v2.2 是 3 个字符的图像格式；v2.3+ 是 NUL 结尾的字符串。
        // 两者都只是"用来跳过"，最终类型以魔数为准 —— 声明值在真实文件里经常是错的。
        if (major <= 2) {
            if (pos + 3 > body.size) return null
            pos += 3
        } else {
            val end = body.indexOfZero(pos)
            if (end < 0) return null
            pos = end + 1
        }

        if (pos >= body.size) return null
        val pictureType = body[pos].toInt() and 0xFF
        pos += 1

        pos = skipDescription(body, pos, encoding)
        if (pos < 0 || pos >= body.size) return null

        return coverFrom(body.copyOfRange(pos, body.size))?.let { Picture(it, pictureType) }
    }

    /**
     * 跳过 description，返回图像数据的起始偏移；找不到终止符回 -1。
     *
     * ⚠️ encoding 1/2 是 UTF-16，终止符是**两字节的 `0x0000`**，且必须按 **2 字节步进** ——
     * 否则 ASCII 文本的 UTF-16 编码里那些 `0x00` 会被逐个当成终止符，偏移全错。
     * 这是 APIC 解析里最容易写错的一处。
     */
    private fun skipDescription(body: ByteArray, start: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var i = start
            while (i + 1 < body.size) {
                if (body[i] == 0.toByte() && body[i + 1] == 0.toByte()) return i + 2
                i += 2
            }
            return -1
        }
        val end = body.indexOfZero(start)
        return if (end < 0) -1 else end + 1
    }

    // ───────────────────────── 封面：FLAC ─────────────────────────

    /**
     * 遍历 FLAC 的 METADATA_BLOCK 链找 `PICTURE`（type 6）。块体结构（**全部大端**）：
     * ```
     * 4B pictureType + 4B MIME长度 + MIME(ASCII) +
     * 4B 描述长度 + 描述(UTF-8) +
     * 4B 宽 + 4B 高 + 4B 色深 + 4B 颜色数 +
     * 4B 数据长度 + 数据
     * ```
     *
     * ⚠️ 这里**不能**像 [readFlac] 那样"找到 VORBIS_COMMENT 就 return"：`VORBIS_COMMENT`(4)
     * 通常排在 `PICTURE`(6) **前面**，提前返回会让封面永远读不到。文本路径那样写是对的
     *（它只要文本），本函数必须走完整条链。
     */
    private fun readFlacCover(raf: java.io.RandomAccessFile): Cover? {
        val fileLen = raf.length()
        var pos = 4L   // 跳过 "fLaC"
        var firstCover: Cover? = null
        while (pos + 4 <= fileLen) {
            raf.seek(pos)
            val bh = ByteArray(4)
            if (raf.read(bh) < 4) break
            val isLast = (bh[0].toInt() and 0x80) != 0
            val type = bh[0].toInt() and 0x7F
            val length = ((bh[1].toInt() and 0xFF) shl 16) or
                ((bh[2].toInt() and 0xFF) shl 8) or
                (bh[3].toInt() and 0xFF)
            val bodyStart = pos + 4
            if (bodyStart + length > fileLen) break

            if (type == FLAC_BLOCK_PICTURE &&
                length <= MAX_EMBEDDED_ART_BYTES + MAX_PICTURE_META_BYTES
            ) {
                val body = ByteArray(length)
                raf.seek(bodyStart)
                if (raf.read(body) == length) {
                    parseFlacPicture(body)?.let { pic ->
                        if (pic.pictureType == PICTURE_TYPE_FRONT_COVER) return pic.cover
                        if (firstCover == null) firstCover = pic.cover
                    }
                }
            }
            if (isLast) break
            pos = bodyStart + length
        }
        return firstCover
    }

    private fun parseFlacPicture(body: ByteArray): Picture? {
        var p = 0
        fun be32(): Long {
            if (p + 4 > body.size) return -1L
            val v = ((body[p].toInt() and 0xFF).toLong() shl 24) or
                ((body[p + 1].toInt() and 0xFF).toLong() shl 16) or
                ((body[p + 2].toInt() and 0xFF).toLong() shl 8) or
                (body[p + 3].toInt() and 0xFF).toLong()
            p += 4
            return v
        }

        val pictureType = be32()
        if (pictureType < 0) return null
        val mimeLen = be32()
        if (mimeLen < 0 || p + mimeLen > body.size) return null
        p += mimeLen.toInt()                       // MIME 内容只用来跳过（类型以魔数为准）
        val descLen = be32()
        if (descLen < 0 || p + descLen > body.size) return null
        p += descLen.toInt()
        // 宽 / 高 / 色深 / 颜色数 各 4B
        if (p + 16L > body.size) return null
        p += 16
        val dataLen = be32()
        if (dataLen <= 0 || dataLen > MAX_EMBEDDED_ART_BYTES) return null
        if (p + dataLen > body.size) return null

        return coverFrom(body.copyOfRange(p, p + dataLen.toInt()))
            ?.let { Picture(it, pictureType.toInt()) }
    }

    // ───────────────────────── 封面：公共校验 ─────────────────────────

    /**
     * 原始字节 → [Cover]，或 null（不合规）。
     *
     * **类型以魔数为准，不信容器里声明的 MIME**：真实文件里声明值与实际内容不符的情况很常见，
     * 而客户端拿这个 MIME 去渲染，声明错了就是一张裂图。声明的值在解析时只用来"跳过"。
     */
    private fun coverFrom(data: ByteArray): Cover? {
        if (data.size < MIN_EMBEDDED_ART_BYTES || data.size > MAX_EMBEDDED_ART_BYTES) return null
        val mime = when {
            isJpeg(data) -> "image/jpeg"
            isPng(data) -> "image/png"
            else -> return null
        }
        return Cover(data, mime)
    }

    private fun isJpeg(b: ByteArray): Boolean =
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

    private fun isPng(b: ByteArray): Boolean =
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    // ───────────────────────── FLAC ─────────────────────────

    private fun isFlac(bytes: ByteArray): Boolean = bytes.size > 4 && hasFlacMagic(bytes)

    /** 只判 4 字节魔数，不要求后面还有内容 —— 封面路径先只读 4 字节就能用（见 [readCover]）。 */
    private fun hasFlacMagic(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() &&
            b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte()

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
        // Long 运算防溢出（同 readId3 那条）：vendorLen 也是从文件读的裸 32 位
        if (vendorLen < 0 || pos.toLong() + vendorLen > end) return Tags()
        pos += vendorLen
        val count = readLe32()
        if (count <= 0) return Tags()

        val map = HashMap<String, String>()
        /*
         * 用显式 for + break，而不是 `repeat(count)` + `return@repeat`（2026-10-13 修复）。
         *
         * `count` 同样是从文件读的裸 32 位，最大 0x7FFFFFFF。原来的写法遇到损坏的结构会
         * **空转 21 亿次**：readLe32 读不出东西时恒返回 -1，而 `return@repeat` 只是跳过
         * 本次迭代、`pos` 一动不动 —— 于是循环既不推进也不结束。音乐库里一个损坏的 FLAC
         * 就能把 core 的一个核占满。改成"读不出长度就立刻停"：结构已经坏了，
         * 继续按 count 去数剩下的条目没有意义。
         */
        for (i in 0 until count) {
            val len = readLe32()
            if (len < 0) break
            if (len > 0 && pos.toLong() + len > end) break
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

    private fun isId3v2(bytes: ByteArray): Boolean = bytes.size > 10 && hasId3Magic(bytes)

    private fun hasId3Magic(b: ByteArray): Boolean =
        b.size >= 3 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() &&
            b[2] == '3'.code.toByte()

    /**
     * 一个 ID3v2 帧头。[headerLen] 是帧头长度（v2.2 = 6，v2.3/2.4 = 10）。
     */
    private class FrameHeader(val id: String, val headerLen: Int, val bodySize: Int)

    /**
     * 解析一个 ID3v2 帧头。三个版本的规则各不相同（见 [readId3] 的 KDoc）。
     *
     * 抽成独立函数的理由：**文本路径（缓冲读）与封面路径（seek 读）必须共用同一份算术**。
     * 两处各写一遍的话，迟早有一处会在 v2.4 的 synchsafe 上写错 ——
     * 而那种错不会报错，只会让标签/封面"莫名读不出来"，是最难查的一类问题。
     *
     * @param at 帧头在 [header] 里的起始偏移。
     * @return null = 这一帧读不出来（id 非法 / 头部被截断 / 帧长为 0），调用方应停止遍历。
     */
    private fun parseFrameHeader(major: Int, header: ByteArray, at: Int): FrameHeader? {
        val idLen = if (major <= 2) 3 else 4
        if (at + idLen > header.size) return null
        val id = String(header, at, idLen, Charsets.ISO_8859_1)
        if (id.isBlank() || id[0] == '\u0000') return null

        val headerLen: Int
        val size: Int
        when {
            major <= 2 -> {
                headerLen = 6
                if (at + headerLen > header.size) return null
                size = ((header[at + 3].toInt() and 0xFF) shl 16) or
                    ((header[at + 4].toInt() and 0xFF) shl 8) or
                    (header[at + 5].toInt() and 0xFF)
            }
            major == 3 -> {
                headerLen = 10
                if (at + headerLen > header.size) return null
                size = ((header[at + 4].toInt() and 0xFF) shl 24) or
                    ((header[at + 5].toInt() and 0xFF) shl 16) or
                    ((header[at + 6].toInt() and 0xFF) shl 8) or
                    (header[at + 7].toInt() and 0xFF)
            }
            else -> {
                headerLen = 10
                if (at + headerLen > header.size) return null
                size = synchsafe(header, at + 4)
            }
        }
        if (size <= 0) return null
        return FrameHeader(id, headerLen, size)
    }

    /**
     * 遍历 ID3v2 帧链取文本帧。
     *
     * 三个版本的帧头不一样（这也是最容易写错的地方）：
     * · v2.2：3 字符 ID + 3 字节大端长度，帧体从 +6 开始；
     * · v2.3：4 字符 ID + 4 字节**大端**长度 + 2 字节 flag，帧体从 +10；
     * · v2.4：4 字符 ID + 4 字节**synchsafe**长度 + 2 字节 flag，帧体从 +10。
     *
     * 帧头算术走 [parseFrameHeader]，与封面路径共用（2026-10-13）。
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
            val fh = parseFrameHeader(major, bytes, pos) ?: break
            val body = pos + fh.headerLen
            val size = fh.bodySize
            /*
             * 边界检查必须用 Long 运算（2026-10-13 修复）。
             *
             * 原来写的是 `body + size > end`：v2.3 的帧长是从文件里读的**裸 32 位大端**，
             * 可以到 0x7FFFFFFF。此时 `body + size` 在 Int 上**溢出成负数**，
             * 于是这个检查恒为 false、拦不住任何东西，紧接着
             * `decodeTextFrame(bytes, body, size)` 就用一个远超数组长度的 offset 去取值，
             * 抛 ArrayIndexOutOfBoundsException。
             *
             * 危害不只是"读不到标签"：`MediaRoutes.audioTagsOf` 调 `read()` 时**没有**
             * runCatching 兜底，所以音乐库里放一个损坏（或故意构造）的 MP3 就能把
             * `GET /api/media/tags` 打成 500。由 `AudioTagReaderTest` 的
             * 「声明了超出实际长度的帧长时不崩溃」钉住。
             *
             * v2.4 走 synchsafe（上限 0x0FFFFFFF），本身不会溢出；但两条分支共用这一行，
             * 所以统一按 Long 判，免得将来改动时只想起一条。
             */
            if (body.toLong() + size > end) break

            val value = when (fh.id) {
                "TIT2", "TT2", "TPE1", "TP1", "TALB", "TAL" ->
                    decodeTextFrame(bytes, body, size)
                else -> null
            }
            if (!value.isNullOrBlank()) {
                when (fh.id) {
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
