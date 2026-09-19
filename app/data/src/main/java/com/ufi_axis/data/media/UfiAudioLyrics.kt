package com.ufi_axis.data.media

import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * 音频歌词：取词 + 解析的**唯一一份实现**（2026-09-17 抽取）。
 *
 * ## 为什么放在 :app:data
 * 在此之前同一件事有两份代码：文件预览浮层（feature-files）自己实现了「旁挂 .lrc + 内嵌
 * USLT/SYLT/FLAC」的全套字节级解析，音乐播放页（feature-media）另有一份只会解 LRC 文本的实现。
 * 结果是同一首歌在两个入口显示的歌词不一样 —— 文件管理器能读到内嵌歌词，播放页读不到；
 * 播放页能正确处理一行多句的紧凑格式，文件管理器会把整首歌粘成一句。
 * 取词是纯逻辑（HTTP + 字节解析），feature 模块都依赖 :app:data，放这里两边共用一份，
 * 不会再出现「修了一边忘了另一边」。
 *
 * ## 取词的优先级
 * 1. **core 的 `/api/media/lyrics`**（仅音频库里的曲目有 id）：服务端直接在本地磁盘上找旁挂
 *    文件，一次请求就拿到原文，比客户端自己按路径猜文件名再下载快得多；
 * 2. **同目录同名 `.lrc`**：文件管理器里的任意音频文件（不在音频库里、没有 id）走这条；
 * 3. **内嵌歌词**（ID3 USLT/SYLT、FLAC VORBIS_COMMENT 的 `LYRICS`）：大量歌曲根本没有旁挂
 *    文件，歌词就嵌在音频里。
 *
 * 三级都拿不到才返回 null —— 调用方据此显示「没有歌词」，**不许拿曲名/文件名冒充歌词**。
 */
object UfiAudioLyrics {

    /** 一行歌词。[timeMs] < 0 = 这一行没有时间戳（纯文本歌词，不高亮、不可点击跳转）。 */
    data class Line(val timeMs: Long, val text: String)

    /**
     * 一次取词的结果。
     *
     * [source] 是命中来源的可读说明（旁挂文件名，或内嵌时的「内嵌歌词」），用于在界面上告诉
     * 用户这份歌词来自哪里 —— 歌词对不上时，先要知道读的是哪一份。
     */
    data class Result(val source: String, val lines: List<Line>)

    /** 日志 tag：命中/未命中/降级各记一条，出问题时不必接 adb 逐级猜。 */
    private const val TAG = "AudioLyrics"

    /**
     * 头部探测长度 64KB。ID3 的文本帧通常排在 APIC 封面之前，64KB 足以覆盖绝大多数文件，
     * 又不会把几百 KB 的封面白拉一遍。
     */
    private const val HEAD_PROBE_BYTES = 64 * 1024

    /**
     * FLAC 补取上限 1MB：FLAC 的 VORBIS_COMMENT 是独立 metadata block，头部没有「标签总长」
     * 可算（ID3 有），只能给个固定上限兜住。1MB 已远超正常标签段体积，再大就是在拉音频数据了。
     */
    private const val FLAC_TAG_PROBE_MAX = 1024 * 1024

    /** ID3 补取硬上限 4MB：标签里声明的长度来自文件本身，畸形值不能直接信。 */
    private const val ID3_TAG_PROBE_MAX = 4 * 1024 * 1024

    /**
     * 取歌词：core 接口（有 [mediaId] 时）→ 同目录同名 `.lrc` → 内嵌 USLT/SYLT/FLAC LYRICS。
     *
     * 每一级都以「解析后至少有一行」为命中标准，而不是「请求成功」：拿到一个只有 `[ti:]`
     * 元信息头的空壳文件，等于没有歌词，应当继续往下找。
     *
     * @param api 有就先问 core（服务端读本地磁盘，最快一条）；调用方没有 api 时传 null。
     * @param mediaId 音频库曲目 id。文件管理器里的文件不在库里，传 null。
     */
    suspend fun load(
        api: UfiAxisApi?,
        mediaId: Long?,
        httpClient: OkHttpClient,
        prefs: AppPreferences,
        filePath: String
    ): Result? {
        loadFromCore(api, mediaId)?.let { return it }
        loadSidecarLrc(httpClient, prefs, filePath)?.let { return it }
        return loadEmbedded(httpClient, prefs, filePath)
    }

    /**
     * 解析 LRC / 纯文本歌词。
     *
     * ## 按时间戳**切段**，不是整行剥标签
     * 遇到紧凑格式 `[00:01]第一句[00:05]第二句…`（有的歌词文件把整首歌写在一行），「把整行的
     * 时间标签一次性 replace 掉、剩下的当这一行歌词」会把全篇粘成一句，再给每个时间戳各塞一份
     * 同样的长文本 —— 界面上就是「所有歌词挤在一起」，点某句跳转也失去意义（每句文本都一样）。
     * 现在每个时间标签只取**它后面、下一个标签之前**的那段文本；标签后紧跟另一个标签（中间没有
     * 文字）时与后面的标签共享同一段，这正是标准 LRC 里 `[00:12][01:30]副歌` 的写法。
     * 两种格式因此用同一套规则覆盖，不必先猜是哪种。
     *
     * ## 没有时间轴也要能看
     * 一个时间标签都没有时降级为纯文本歌词（每行 `timeMs = -1`）：网上下载的 `.lrc` 有相当比例
     * 只是纯文字，内嵌 USLT 更是常常没有时间轴。宁可不滚动、不高亮，也不能当成「没有歌词」。
     * 元信息行（`[ti:]` `[ar:]` `[by:]`…）不是歌词内容，两条路径上都要剔掉。
     */
    fun parse(text: String): List<Line> {
        if (text.isBlank()) return emptyList()
        val timed = mutableListOf<Line>()
        val plain = mutableListOf<Line>()
        var offsetMs = 0L
        text.lineSequence().forEach { rawLine ->
            // BOM 有工具会写在每一行开头，不只文件首
            val line = rawLine.replace("\uFEFF", "").trim()
            if (line.isEmpty()) return@forEach
            LRC_OFFSET_TAG.matchEntire(line)?.let { m ->
                offsetMs = m.groupValues[1].toLongOrNull() ?: 0L
                return@forEach
            }
            if (LRC_META_TAG.matches(line)) return@forEach
            val tags = LRC_TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) {
                plain += Line(TIME_UNSYNCED, line)
                return@forEach
            }
            // 连续标签先攒着，等遇到真正的文本再一起落地
            var pending = mutableListOf<Long>()
            tags.forEachIndexed { i, m ->
                pending += lrcTimeMsOf(m) + offsetMs
                val from = m.range.last + 1
                val to = tags.getOrNull(i + 1)?.range?.first ?: line.length
                val segment = if (to > from) line.substring(from, to).trim() else ""
                if (segment.isNotEmpty()) {
                    pending.forEach { t -> timed += Line(t, segment) }
                    pending = mutableListOf()
                }
            }
        }
        return if (timed.isEmpty()) plain else timed.sortedBy { it.timeMs }
    }

    /** 无时间轴歌词行的时间戳哨兵值。 */
    private const val TIME_UNSYNCED = -1L

    private val LRC_TIME_TAG = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** 元信息行（`[ti:]` `[ar:]` `[by:]`…）——不是歌词，别显示。`[offset:]` 例外，见下。 */
    private val LRC_META_TAG = Regex("""^\[[a-zA-Z#]+:.*]$""")

    /** 全局时间偏移（毫秒，可为负）。有的歌词文件靠它对齐，忽略它会整篇差几百毫秒。 */
    private val LRC_OFFSET_TAG = Regex("""^\[offset:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)

    /** `[mm:ss.xx]` → 毫秒。两位小数是百分之一秒、三位才是毫秒，补齐后再算，不然 `.5` 会被当成 5ms。 */
    private fun lrcTimeMsOf(match: MatchResult): Long {
        val min = match.groupValues[1].toLongOrNull() ?: 0L
        val sec = match.groupValues[2].toLongOrNull() ?: 0L
        val fracRaw = match.groupValues[3]
        val frac = when (fracRaw.length) {
            0 -> 0L
            1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
            2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
            else -> fracRaw.take(3).toLongOrNull() ?: 0L
        }
        return min * 60_000L + sec * 1000L + frac
    }

    /**
     * 问 core 要旁挂歌词原文（`/api/media/lyrics`）。
     *
     * 只有音频库里的曲目才有 id；服务端在本地磁盘上找同名 `.lrc`/`.txt`，比客户端按路径猜文件名
     * 再下载少一轮往返、也不受路径编码差异影响。时间轴解析仍留在客户端 —— 那是纯展示格式化，
     * 而且高亮要跟着播放进度每秒变，让 core 参与只会多一次网络请求。
     */
    private suspend fun loadFromCore(api: UfiAxisApi?, mediaId: Long?): Result? {
        if (api == null || mediaId == null || mediaId <= 0) return null
        return try {
            val resp = api.getMediaLyrics(mediaId)
            if (!resp.found || resp.text.isBlank()) {
                DebugLog.d(TAG) { "core lyrics miss id=$mediaId" }
                return null
            }
            val lines = parse(resp.text)
            DebugLog.d(TAG) {
                "core lyrics hit id=$mediaId source=${resp.source} lines=${lines.size} " +
                    "synced=${lines.any { it.timeMs != TIME_UNSYNCED }}"
            }
            lines.takeIf { it.isNotEmpty() }?.let { Result(resp.source, it) }
        } catch (e: Exception) {
            DebugLog.d(TAG) { "core lyrics error id=$mediaId ${e.javaClass.simpleName}: ${e.message}" }
            null
        }
    }

    /** 同目录同名 `.lrc`（经 `/api/files/download` 全量拉取）——文件管理器里没有 id 的文件走这条。 */
    private suspend fun loadSidecarLrc(
        httpClient: OkHttpClient,
        prefs: AppPreferences,
        filePath: String
    ): Result? {
        val lrcPath = filePath.substringBeforeLast('.', missingDelimiterValue = filePath) + ".lrc"
        if (lrcPath.equals(filePath, ignoreCase = true)) return null
        val url = "${fileEndpoint(prefs, "download")}${URLEncoder.encode(lrcPath, "UTF-8")}"
        return try {
            val resp = withContext(Dispatchers.IO) {
                httpClient.newCall(Request.Builder().url(url).build()).execute()
            }
            // 必须 use{}：非 2xx 直接返回 null 时响应体既不读也不关，连接会一直挂在 OkHttp
            // 连接池里（"没配 .lrc" 是常态，这个分支命中率很高）。
            resp.use { r ->
                if (!r.isSuccessful) {
                    DebugLog.d(TAG) { "sidecar lrc miss path=$lrcPath http=${r.code}" }
                    return@use null
                }
                // 取 bytes 而不是 string()：后者按 Content-Type 的 charset（这里是
                // application/octet-stream → UTF-8）硬解，GBK 编码的 .lrc 会整篇变乱码。
                // 国内网上下载的歌词文件相当一部分是 GBK。
                val bytes = r.body?.bytes() ?: return@use null
                val lines = parse(decodeTextBytes(bytes))
                DebugLog.d(TAG) {
                    "sidecar lrc hit path=$lrcPath bytes=${bytes.size} lines=${lines.size} " +
                        "synced=${lines.any { it.timeMs != TIME_UNSYNCED }}"
                }
                lines.takeIf { it.isNotEmpty() }
                    ?.let { Result(lrcPath.substringAfterLast('/'), it) }
            }
        } catch (e: Exception) {
            DebugLog.d(TAG) { "sidecar lrc error path=$lrcPath ${e.javaClass.simpleName}: ${e.message}" }
            null
        }
    }

    /**
     * 音频文件自带的内嵌歌词：经 `/api/files/stream` 用 HTTP Range **只读标签段**，不拉整首歌。
     *
     * 分两次读是权衡：先读头部 [HEAD_PROBE_BYTES]，多数文件的文本帧就在这段里；命中不了再按
     * 标签实际大小补一次（ID3 头里有声明长度，FLAC 只能用 [FLAC_TAG_PROBE_MAX] 兜）。
     * 一上来就按上限拉，会在每次打开一首没有内嵌歌词的歌时白下载几 MB。
     *
     * 只对 mp3 / flac 试探：其余容器（wav/ogg/m4a…）这里没有对应的解析器，探了也只是白花一次请求。
     */
    private suspend fun loadEmbedded(
        httpClient: OkHttpClient,
        prefs: AppPreferences,
        filePath: String
    ): Result? {
        if (!filePath.endsWith(".mp3", ignoreCase = true) &&
            !filePath.endsWith(".flac", ignoreCase = true)
        ) return null
        val url = "${fileEndpoint(prefs, "stream")}${URLEncoder.encode(filePath, "UTF-8")}"
        return try {
            val headBytes = rangeGet(httpClient, url, HEAD_PROBE_BYTES - 1) ?: return null
            embeddedLyrics(headBytes)?.let { return embeddedResult(it, filePath, "head") }
            // 头部没命中：ID3 能算出确切要读多少；FLAC 算不出（VORBIS_COMMENT 是独立 block）→ 用固定上限兜
            val wanted = id3TagSize(headBytes) ?: FLAC_TAG_PROBE_MAX
            if (wanted <= HEAD_PROBE_BYTES) return null
            val end = (wanted - 1).coerceAtMost(ID3_TAG_PROBE_MAX - 1)
            val moreBytes = rangeGet(httpClient, url, end) ?: return null
            embeddedLyrics(moreBytes)?.let { return embeddedResult(it, filePath, "extended") }
            DebugLog.d(TAG) { "embedded lyrics absent path=$filePath probed=${end + 1}B" }
            null
        } catch (e: Exception) {
            DebugLog.d(TAG) { "embedded lyrics error path=$filePath ${e.javaClass.simpleName}: ${e.message}" }
            null
        }
    }

    /** `http://host:port/api/files/{action}?path=` 前缀。两个取词分支都直连 core 的文件接口。 */
    private fun fileEndpoint(prefs: AppPreferences, action: String): String =
        "http://${prefs.effectiveHost}:${prefs.serverPort}/api/files/$action?path="

    /** 读 `[0, lastByte]` 这一段。歌词只藏在文件头部，用 Range 而不是整取。 */
    private suspend fun rangeGet(httpClient: OkHttpClient, url: String, lastByte: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            httpClient.newCall(
                Request.Builder().url(url).header("Range", "bytes=0-$lastByte").build()
            ).execute().use { it.body?.bytes() }
        }

    /** 内嵌歌词文本 → 歌词行。USLT 常常是纯文本，所以走 [parse] 的无时间轴分支也算命中。 */
    private fun embeddedResult(text: String, filePath: String, stage: String): Result? {
        val lines = parse(text)
        DebugLog.d(TAG) {
            "embedded lyrics hit stage=$stage lines=${lines.size} " +
                "synced=${lines.any { it.timeMs != TIME_UNSYNCED }}"
        }
        return lines.takeIf { it.isNotEmpty() }
            ?.let { Result("内嵌歌词 · ${filePath.substringAfterLast('/')}", it) }
    }

    /**
     * 猜编码解码文本（UTF-8 → GBK → Latin-1）。
     *
     * 严格模式（[java.nio.charset.CodingErrorAction.REPORT]）是关键：宽松模式会把非法字节替换成
     * U+FFFD 并「成功」返回，那就没法用来判断「这份字节到底是不是这个编码」。
     * 顺序上 UTF-8 的字节结构校验最严、误判概率最低，GBK 次之，都不成立才落 Latin-1（永不失败的兜底）。
     *
     * 与 web 端 `id3Lyrics.ts` 的 `decodeLatin1Frame` 同一口径 —— 两端对同一个 `.lrc` 必须解出同样的字。
     */
    private fun decodeTextBytes(bytes: ByteArray): String {
        for (name in listOf("UTF-8", "GBK")) {
            val decoded = runCatching {
                java.nio.charset.Charset.forName(name).newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString()
            }.getOrNull()
            if (decoded != null) return decoded
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    /** 按魔数分流：FLAC 读 VORBIS_COMMENT，其余按 ID3 读 USLT/SYLT。 */
    private fun embeddedLyrics(bytes: ByteArray): String? =
        if (isFlac(bytes)) flacLyrics(bytes) else id3Lyrics(bytes)

    private fun isFlac(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte()

    /**
     * FLAC VORBIS_COMMENT 里的歌词。
     *
     * 结构：`fLaC` 之后是一串 METADATA_BLOCK —— header 4 字节
     * （byte0 = [last-block 1bit][type 7bit]，byte1..3 = 24bit **大端**长度），type 4 = VORBIS_COMMENT。
     * 而 VORBIS_COMMENT **内部**的长度是 32bit **小端**（Vorbis 规范如此，与 FLAC 自己的大端相反）——
     * 这是这段代码最容易写错的一处。
     *
     * `guard` 是防呆：block 链的长度来自文件本身，畸形/截断文件可能让 pos 不前进而死循环。
     */
    private fun flacLyrics(bytes: ByteArray): String? {
        var pos = 4
        var guard = 0
        while (guard++ < 128 && pos + 4 <= bytes.size) {
            val header = bytes[pos].toInt() and 0xFF
            val isLast = (header and 0x80) != 0
            val type = header and 0x7F
            val len = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val bodyStart = pos + 4
            val bodyEnd = (bodyStart + len).coerceAtMost(bytes.size)
            if (type == 4 && bodyEnd > bodyStart) {
                vorbisLyrics(bytes, bodyStart, bodyEnd)?.let { return it }
            }
            if (isLast) return null
            pos = bodyStart + len
        }
        return null
    }

    /**
     * 从 VORBIS_COMMENT 块体里找歌词字段。
     *
     * 字段名大小写不敏感（不同打标签工具写法不一），值一律 UTF-8。三个候选键都要认：
     * `LYRICS` 是最常见的，`UNSYNCEDLYRICS` / `UNSYNCED LYRICS` 是从 ID3 转码过来的文件常带的写法。
     */
    private fun vorbisLyrics(bytes: ByteArray, start: Int, end: Int): String? {
        // Vorbis Comment 里的长度是 **32bit 无符号小端**。必须先转成 Long 再判界：
        // 按有符号 Int 读的话，长度 ≥ 0x80000000 的畸形/加密文件会算出**负的** pos，
        // 而 `pos + 4 > end` 对负值恒为 false ⇒ 越界抛异常，被外层 catch 吞掉，
        // 表现是「这首 FLAC 的内嵌歌词永远读不出来」而不是报错。web 端用 `>>> 0` 转无符号。
        fun u32le(at: Int): Long =
            ((bytes[at].toLong() and 0xFF) or ((bytes[at + 1].toLong() and 0xFF) shl 8) or
                ((bytes[at + 2].toLong() and 0xFF) shl 16) or ((bytes[at + 3].toLong() and 0xFF) shl 24))

        var pos = start
        if (pos + 4 > end) return null
        val vendorLen = u32le(pos)
        if (vendorLen > end - pos - 4) return null   // 用减法比较，避免 pos + vendorLen 溢出
        pos += 4 + vendorLen.toInt()                 // vendor string
        if (pos + 4 > end) return null
        val count = u32le(pos).coerceIn(0L, 512L).toInt()   // count 来自文件，畸形值会让循环跑飞
        pos += 4
        repeat(count) {
            if (pos + 4 > end) return null
            val len = u32le(pos)
            pos += 4
            if (len <= 0L || len > end - pos) return null
            val size = len.toInt()
            val entry = String(bytes, pos, size, Charsets.UTF_8)
            pos += size
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).lowercase()
                if (key == "lyrics" || key == "unsyncedlyrics" || key == "unsynced lyrics") {
                    return entry.substring(eq + 1).takeIf { it.isNotBlank() }
                }
            }
        }
        return null
    }

    /** 读 ID3v2 标签声明大小（synchsafe 整数：每字节只用低 7 位）。非 ID3v2 返回 null。 */
    private fun id3TagSize(bytes: ByteArray): Int? {
        if (bytes.size < 10) return null
        if (!isId3v2(bytes)) return null
        return ((bytes[6].toInt() and 0x7F) shl 21) or
            ((bytes[7].toInt() and 0x7F) shl 14) or
            ((bytes[8].toInt() and 0x7F) shl 7) or
            (bytes[9].toInt() and 0x7F)
    }

    private fun isId3v2(bytes: ByteArray): Boolean =
        bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 'I'.code &&
            (bytes[1].toInt() and 0xFF) == 'D'.code && (bytes[2].toInt() and 0xFF) == '3'.code

    /**
     * 从 ID3v2 标签字节里找 USLT/SYLT 帧并解出歌词文本。
     *
     * 三个大版本的帧头不同，必须分别处理，否则帧长算错就会一路跑偏到垃圾数据：
     * v2.2 是 3 字节帧 ID + 3 字节大端长度；v2.3 是 4 + 4 字节大端；v2.4 的长度改成了 synchsafe。
     * 遇到全 0 的帧 ID 说明进了 padding 区，后面没有帧了，直接停。
     */
    private fun id3Lyrics(bytes: ByteArray): String? {
        if (bytes.size < 10 || !isId3v2(bytes)) return null
        val major = bytes[3].toInt() and 0xFF
        val tagSize = id3TagSize(bytes) ?: return null
        val end = (10 + tagSize).coerceAtMost(bytes.size)
        val idLen = if (major == 2) 3 else 4
        var pos = 10
        while (pos + idLen + 6 <= end) {
            val id = runCatching { String(bytes, pos, idLen, Charsets.ISO_8859_1) }.getOrDefault("")
            if (id.isBlank() || id[0] == '\u0000') break
            val frameSize: Int
            val bodyOffset: Int
            when (major) {
                2 -> {
                    frameSize = ((bytes[pos + 3].toInt() and 0xFF) shl 16) or
                        ((bytes[pos + 4].toInt() and 0xFF) shl 8) or
                        (bytes[pos + 5].toInt() and 0xFF)
                    bodyOffset = pos + 6
                }
                3 -> {
                    frameSize = ((bytes[pos + 4].toInt() and 0xFF) shl 24) or
                        ((bytes[pos + 5].toInt() and 0xFF) shl 16) or
                        ((bytes[pos + 6].toInt() and 0xFF) shl 8) or
                        (bytes[pos + 7].toInt() and 0xFF)
                    bodyOffset = pos + 10
                }
                4 -> {
                    frameSize = ((bytes[pos + 4].toInt() and 0x7F) shl 21) or
                        ((bytes[pos + 5].toInt() and 0x7F) shl 14) or
                        ((bytes[pos + 6].toInt() and 0x7F) shl 7) or
                        (bytes[pos + 7].toInt() and 0x7F)
                    bodyOffset = pos + 10
                }
                else -> return null
            }
            if (frameSize <= 0 || bodyOffset + frameSize > end) break
            if (id == "USLT" || id == "SYLT") {
                val text = decodeLyricsFrame(bytes.copyOfRange(bodyOffset, bodyOffset + frameSize))
                if (text != null) return text
            }
            pos = bodyOffset + frameSize
        }
        return null
    }

    /**
     * 解出 USLT/SYLT 帧体内的歌词文本。
     *
     * 帧体 = `[编码字节][语言3字节][描述符(同编码，以 \0 或 \0\0 结尾)][歌词文本]`。
     * 必须跳过描述符才是真正的歌词 —— 否则会把 "eng" 之类的语言码和描述当成第一行。
     * UTF-16 分支要按两字节步进找 `\0\0`：单字节扫描会在 ASCII 字符的高位 0 上误判终止。
     */
    private fun decodeLyricsFrame(body: ByteArray): String? {
        if (body.size < 4) return null
        val enc = body[0].toInt() and 0xFF
        var textStart = -1
        when (enc) {
            // UTF-16：描述符以 \0\0 终止（文本字符是 XX 00，不会误命中两个连续 0）
            1, 2 -> {
                var j = 4
                while (j + 1 < body.size) {
                    if ((body[j].toInt() and 0xFF) == 0 && (body[j + 1].toInt() and 0xFF) == 0) {
                        textStart = j + 2
                        break
                    }
                    j += 2
                }
            }
            // 0 = ISO-8859-1，3 = UTF-8：描述符以单个 \0 终止
            else -> {
                var j = 4
                while (j < body.size) {
                    if ((body[j].toInt() and 0xFF) == 0) {
                        textStart = j + 1
                        break
                    }
                    j++
                }
            }
        }
        if (textStart < 0 || textStart >= body.size) return null
        val textBytes = body.copyOfRange(textStart, body.size)
        val charset = when (enc) {
            1, 2 -> Charsets.UTF_16   // 歌词文本自带 BOM，交给 UTF_16 自行判字节序
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(textBytes, charset).takeIf { it.isNotBlank() }
    }
}
