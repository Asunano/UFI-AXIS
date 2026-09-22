package com.ufi_axis_core.api.routes

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.media.AudioTagReader
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.core.cache.CacheTTL
import com.ufi_axis_core.core.cache.ResponseCache
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.MimeTypes
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.Locale

/**
 * 媒体库路由 —— 「媒体中心」的数据源（2026-09-16 新增）。
 *
 * ## 为什么不复用文件接口（`/api/files/list` 与 `/api/files/search`）
 * 媒体中心要的是「这台设备上所有视频 / 音乐 / 图片」，而文件接口只有两种能力：
 * `/files/list` 单层列目录，`/files/search` 按**文件名**子串递归找（`MAX_SEARCH_RESULTS = 50`
 * 硬顶、深度 ≤ 8、10s 墙钟）。拿它们凑媒体库要么漏、要么慢到不可用。
 *
 * ## 数据来源：系统媒体库（MediaStore），不自建索引
 * 系统已经把整卡的媒体索引好了（含时长、分辨率、专辑、艺术家，还有缓存好的缩略图）。
 * 自己写扫描器 + Room 索引表意味着要重做一遍增量与失效，而且会和系统媒体库两份数据长期打架。
 * 所以这里只做三件事：查 MediaStore、按配置的目录过滤、把缩略图转 JPEG 吐出去。
 *
 * 代价（必须让客户端能表达出来，不许假装没有）：
 *  · Android 13+ 起 MediaStore 查询按 `READ_MEDIA_VIDEO/AUDIO/IMAGES` **分别**授权，
 *    所以 `/status` 是三态回报，某一类没授权时对应分栏要明说，而不是显示"没有媒体"；
 *  · `.nomedia` 屏蔽的目录、刚 push 进来还没被扫到的文件，媒体库里没有 —— 这是
 *    `POST /rescan` 的用途：请系统去收录用户指定的目录（不是自己建库）。
 *
 * ## 播放 / 查看仍走 `/api/files/stream`
 * 那个端点已经支持 Range、`inline`、无大小上限，app 端 ExoPlayer 走 OkHttp 自带签名。
 * 本文件不再造第二条取字节流的路径。
 */
class MediaRoutes(
    /**
     * 刻意不叫 `context`：Ktor 的路由 lambda 自带一个 `context` 成员（就是 `ApplicationCall`），
     * 同名会在 `get("/list") { ... }` 里把它遮蔽掉，然后 `context.contentResolver` 无法解析。
     */
    private val appContext: Context,
    private val settings: AppSettings,
    /**
     * `/groups` 的聚合结果缓存。
     *
     * 分组要**整表扫一遍**（几千首就是几千行游标推进），而客户端在音乐页里会反复来回切
     * 专辑 / 歌手 / 文件夹三个视图 —— 每次切换都重扫一遍是纯浪费。
     * 只有聚合类端点用它：`/list` 是分页查询，SQL 侧就把工作量限住了，不需要再叠一层内存缓存。
     */
    private val cache: ResponseCache
) : com.ufi_axis_core.api.media.AudioItemLookup {

    companion object {
        private const val TAG = "MediaRoutes"

        /** 一页的条数：默认 100，上限 500。媒体库上千项，不分页会把整份 JSON 一次性怼给客户端。 */
        private const val DEFAULT_PAGE_SIZE = 100
        private const val MAX_PAGE_SIZE = 500

        /**
         * 缩略图边长夹取范围。
         *
         * 上限 2026-09-16 由 512 提到 1024：音乐播放页的封面容器是 260dp，在 xxhdpi 上约 780 物理像素，
         * 请求 512 会被放大 1.5 倍 —— 这就是"封面很模糊"的原因。列表用的默认值仍是 256。
         */
        private const val MIN_THUMB_SIZE = 96
        private const val MAX_THUMB_SIZE = 1024
        private const val DEFAULT_THUMB_SIZE = 256
        private const val THUMB_JPEG_QUALITY = 82

        /**
         * 自行抽帧的候选位置（占片长的比例，2026-09-20 重写）。
         *
         * 原来固定抽**第 1 秒**，抽出来基本都是黑的：淡入、发行商 logo 前的黑场、
         * 番剧的黑底标题卡全在前几秒 —— 原注释说的「不取第 0 帧」只躲开了最表层那一下，
         * 1 秒和 0 秒在这件事上没有本质区别。
         *
         * 现在按片长比例取中段，并对抽到的帧做亮度检测（[averageLuma]），太暗就换下一个比例。
         * 与 app 侧 `MediaThumbnailBuilder` 同一套口径，只是那边的首选点还按 URL 哈希
         * 做了"每个视频取不同位置"，core 这条是备用路径（本机 ROM 解不出画面），不值得加。
         */
        private val VIDEO_FRAME_RATIOS = floatArrayOf(0.40f, 0.55f, 0.25f, 0.70f, 0.85f)

        /** 读不到时长时的兜底抽帧点。取 10 秒而不是 1 秒 —— 理由同上。 */
        private const val VIDEO_FRAME_FALLBACK_US = 10_000_000L

        /** 判定黑帧的平均亮度阈值（0~255）。纯黑 0~3，噪点黑场 ~10，夜戏画面普遍 25+。 */
        private const val DARK_LUMA_THRESHOLD = 18

        /** 亮度采样网格边长：16×16 = 256 点，够判断"整屏是不是黑的"，比逐像素快两个数量级。 */
        private const val LUMA_SAMPLE_GRID = 16

        /**
         * 缩略图缓存目录（`filesDir` 下）。
         *
         * 2026-09-16 加这一层的原因：本机（随身 WiFi 的定制 ROM）**解不出视频画面** ——
         * `MediaProvider` 与 `MediaMetadataRetriever` 走同一套 codec，双双失败
         * （实测 `FileNotFoundException: Failed to create thumbnail` + 抽帧返回 null 帧）。
         * 于是改成「谁有解码能力谁干活」：手机端抽好帧回传（`PUT /api/media/thumbnail`），
         * core 只负责**存和发**，这样 Web 端与第二台手机也能直接拿到。
         *
         * 放 `filesDir` 而不是 `cacheDir`：这是别人算好交上来的结果，被系统清掉就得再算一次
         * （还要再拉一遍视频头部）。容量由 [THUMB_CACHE_LIMIT_BYTES] 自己管。
         */
        private const val THUMB_CACHE_DIR = "thumbs"

        /** 单张上传上限。512px 的 JPEG 一般 20~80KB，512KB 已经很宽松，超了就是客户端在乱传。 */
        private const val MAX_UPLOADED_THUMB_BYTES = 512 * 1024

        /** 缓存总量上限，超了按修改时间从旧到新删（不是 LRU：读命中不 touch，否则 ETag 会跟着变）。 */
        private const val THUMB_CACHE_LIMIT_BYTES = 64L * 1024 * 1024

        /** `/lyrics` 读取的歌词旁挂文件后缀与大小上限（歌词就是几 KB，超过就不是歌词了）。 */
        private val LYRICS_EXTENSIONS = listOf("lrc", "txt")
        private const val MAX_LYRICS_BYTES = 256 * 1024

        /**
         * 外挂字幕的后缀 → MIME（`/subtitles` 与 `/subtitle` 共用）。
         *
         * 分两类，**都列出来**但标记清楚，理由见 `/subtitles` 的 KDoc：
         *  · 键在 [PLAYABLE_SUBTITLE_MIMES] 里的 = 客户端播放器（media3）能解析；
         *  · 其余（MicroDVD `.sub`、SAMI `.smi`）能被发现、能被下载，但播放器解不了，
         *    回给客户端时 `supported = false`，让它显示"格式不支持"而不是装作没这个文件。
         *
         * `.sub` 的歧义：既可能是 MicroDVD 文本，也可能是 VobSub 的二进制图形字幕
         * （配 `.idx`）。两者都不被 media3 的外挂字幕路径支持，所以统一算不支持。
         */
        private val PLAYABLE_SUBTITLE_MIMES = mapOf(
            "srt" to "application/x-subrip",
            "ass" to "text/x-ssa",
            "ssa" to "text/x-ssa",
            "vtt" to "text/vtt",
            "webvtt" to "text/vtt",
            "ttml" to "application/ttml+xml",
            "dfxp" to "application/ttml+xml",
            "xml" to "application/ttml+xml"
        )

        /** 会被当成字幕列出的全部后缀（含 media3 解不了的，见 [PLAYABLE_SUBTITLE_MIMES]）。 */
        private val SUBTITLE_EXTENSIONS =
            PLAYABLE_SUBTITLE_MIMES.keys + setOf("sub", "smi", "sami", "idx")

        /**
         * 单个字幕文件的大小上限。
         *
         * 一部两小时电影的 ASS 带全套特效也就几百 KB；8MB 已经宽松到只会挡住
         * "后缀写成 .srt 的其它东西"（比如被误命名的视频），而那种文件转码会吃光内存。
         */
        private const val MAX_SUBTITLE_BYTES = 8L * 1024 * 1024

        /** `/subtitles?scope=folder` 单次返回条数上限：目录里字幕再多也不该一口气全推给客户端。 */
        private const val MAX_SUBTITLE_ENTRIES = 100

        /** 扫描目录条数上限：这是"选几个目录"，不是"把整卡加进来"。 */
        private const val MAX_SCAN_DIRS = 16

        /** `/rescan` 单次提交给系统扫描器的文件数与递归深度上限。 */
        private const val MAX_RESCAN_FILES = 5000
        private const val MAX_RESCAN_DEPTH = 12

        /**
         * `/browse` 单层返回的子目录条数上限。
         *
         * 每个子目录都要一次 MediaStore count 查询（为了给出"里面有几个"和文件夹封面），
         * 一层里几百个目录时那是几百次查询 —— 上限比"让它慢慢转"诚实。
         */
        private const val MAX_BROWSE_FOLDERS = 200

        /**
         * [audioItemsByPaths] 单次查询绑定的路径条数上限。
         *
         * SQLite 的绑定变量硬顶是 999（`SQLITE_MAX_VARIABLE_NUMBER`），超了整条查询直接抛异常。
         * 取 200 而不是贴着上限：`DATA IN (...)` 的 OR 展开在几百项时已经开始拖慢，分批反而更稳，
         * 而歌单上限 2000 首也就是 10 批。
         */
        private const val PATH_LOOKUP_CHUNK = 200


        /** 允许作为扫描目录的前缀：与 FileRoutes 的用户存储白名单同一口径。 */
        private val ALLOWED_DIR_PREFIXES = listOf("/storage/", "/sdcard", "/mnt/media_rw/")

        // ─────────────────── 音频分组聚合（/groups，2026-09-20） ───────────────────

        /**
         * `/groups` 的缓存 key 前缀。
         *
         * 带 `media:` 域前缀是为了能被 `invalidate("media:*")` 一次性清掉（`ResponseCache`
         * 的模式失效按 `域:子键` 匹配），重扫媒体库之后不需要逐个 key 去点。
         */
        private const val GROUPS_CACHE_PREFIX = "media:groups"

        /**
         * 分组取不到封面时回的 id。
         *
         * 用 0 而不是 null：`_ID` 在 MediaStore 里从 1 开始，0 天然是"无效 id"，
         * 客户端只要判 `cover_id > 0` 就知道该画占位图，不必再处理一种 nullable 分支。
         */
        private const val NO_COVER_ID = 0L

        /**
         * 空标签的展示名。
         *
         * MediaStore 对没有内嵌标签的文件会把 ALBUM/ARTIST 填成空串或 `<unknown>`，
         * 直接透给客户端会得到一个"没有名字的分组"。展示名在 core 侧统一兜底，
         * 免得 app 与 Web 端各写一份、两边文案还不一样。
         */
        private const val UNKNOWN_ALBUM_TITLE = "未知专辑"
        private const val UNKNOWN_ARTIST_TITLE = "未知歌手"

        /**
         * MediaStore 对未知标签写入的占位值。
         *
         * 它是**字面量字符串**（不是 null），所以必须显式识别，否则会出现一个叫
         * `<unknown>` 的专辑分组和一个叫"未知专辑"的分组并存。
         */
        private const val MEDIASTORE_UNKNOWN = "<unknown>"

        /**
         * `/groups` 的 projection：只取聚合真正用得到的四列。
         *
         * 不复用 `projectionOf(AUDIO)`：那份为了列表项带上了 SIZE / DURATION / TITLE 等，
         * 而分组要**整表扫一遍**，每多一列就是几千行乘一次取值。这里的四列各有用途 ——
         * `_ID` 给 `cover_id`、`ALBUM`/`ARTIST` 是分组维度与副标题、`DATA` 用来推目录。
         */
        private val GROUPS_PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.MediaColumns.DATA
        )
    }

    /**
     * 三类媒体。每一类的 uri / 权限 / 可用字段都不同，所以不做"一个通用查询"——
     * 那会退化成把 `MediaStore.Files` 全表拉出来再在内存里筛。
     */
    private enum class Kind(val key: String) {
        VIDEO("video"), AUDIO("audio"), IMAGE("image");

        companion object {
            fun of(raw: String?): Kind? = entries.firstOrNull { it.key == raw }
        }
    }

    private fun contentUriOf(kind: Kind): Uri = when (kind) {
        Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        Kind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        Kind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun permissionOf(kind: Kind): String =
        if (Build.VERSION.SDK_INT >= 33) {
            when (kind) {
                Kind.VIDEO -> "android.permission.READ_MEDIA_VIDEO"
                Kind.AUDIO -> "android.permission.READ_MEDIA_AUDIO"
                Kind.IMAGE -> "android.permission.READ_MEDIA_IMAGES"
            }
        } else {
            // 13 以下没有细分权限，读媒体库靠 READ_EXTERNAL_STORAGE
            "android.permission.READ_EXTERNAL_STORAGE"
        }

    /**
     * 这一类媒体现在能不能读。
     *
     * `MANAGE_EXTERNAL_STORAGE`（All Files Access）也算放行 —— core 本来就是靠它做文件管理的，
     * 有它时 MediaStore 查询同样可读。两个条件是 or：只要有一条成立就不该对用户说"没权限"。
     */
    private fun isGranted(kind: Kind): Boolean {
        val allFiles = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        if (allFiles) return true
        return appContext.checkSelfPermission(permissionOf(kind)) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 这一类已配置的扫描目录（规范化后的绝对路径）。空 = 这一类不限目录。
     *
     * 2026-09-16 起**按类型各存一份**：媒体中心拆成三个独立页后，每页只改自己那一份，
     * 所以三页各有可写入口也不会互相覆盖。三类扫同一个目录不冲突（MediaStore 分表查）。
     */
    private fun scanDirs(kind: Kind): List<String> {
        val raw = settings.mediaScanDirsJson(kind.key) ?: return emptyList()
        return try {
            (Json.parseToJsonElement(raw) as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.map { it.trimEnd('/') }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        } catch (e: Exception) {
            AppLogger.w(TAG, "扫描目录配置解析失败(${kind.key})，按不限目录处理: ${e.message}")
            emptyList()
        }
    }


    /**
     * 把扫描目录转成 MediaStore 的 selection。
     *
     * 用 `DATA LIKE '<dir>/%'`：`MediaStore.MediaColumns.DATA` 在 Android 10+ 名义上废弃，
     * 但**仍然可读可查**，而且媒体中心的整条链路（`/api/files/stream?path=` 播放、
     * 文件管理器跳转）都是以真实路径为标识的，换成 `RELATIVE_PATH` 反而要在两种标识之间来回翻译。
     */
    private fun dirSelection(dirs: List<String>): Pair<String?, Array<String>?> {
        if (dirs.isEmpty()) return null to null
        val clause = dirs.joinToString(" OR ") { "${MediaStore.MediaColumns.DATA} LIKE ?" }
        val args = dirs.map { "$it/%" }.toTypedArray()
        return "($clause)" to args
    }

    /**
     * 查询串里的可选过滤值。空白 = 没传。
     *
     * 前端清空一个输入框 / 退出专辑详情时，参数往往还挂在 URL 上但值是空串。
     * 把空串当成"筛一个名字为空的专辑"会得到一个永远空的列表，那看起来就是接口坏了。
     */
    private fun filterParam(raw: String?): String? = raw?.trim()?.takeIf { it.isNotBlank() }

    /**
     * `/list` 的完整 selection：扫描目录过滤 **AND** 音频维度过滤（album / artist / dir）。
     *
     * 两者是"并且"而不是"替换"：扫描目录是用户在设置里划定的媒体库边界，专辑 / 歌手 / 目录
     * 只是在这个边界内再筛一层 —— 让 `album=` 绕过边界，等于从"用户没加进媒体库的目录"
     * 里往外吐文件。
     *
     * 值一律走 `?` 占位 + selectionArgs，不拼进 SQL 字符串：这三个参数直接来自查询串，
     * 而 MediaStore 的 selection 最终由 SQLite 解析，拼串就是一个注入点。
     *
     * @param dir 只要**直接位于**该目录下的文件。`DATA LIKE '<dir>/%'` 会把整棵子树都捞出来，
     *   所以再叠一条 `NOT LIKE '<dir>/%/%'` 排掉更深的层级 —— 客户端的"文件夹分组"点进去
     *   要的是这一层的曲目，不是子目录里的。
     */
    private fun listSelection(
        kind: Kind,
        album: String?,
        artist: String?,
        dir: String?
    ): Pair<String?, Array<String>?> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        val (dirClause, dirArgs) = dirSelection(scanDirs(kind))
        if (dirClause != null && dirArgs != null) {
            clauses += dirClause
            args += dirArgs
        }

        // 三个过滤只对音频生效。ALBUM / ARTIST 是 `MediaStore.Audio` 独有的列，
        // 拿去查 video / image 表会直接抛「unknown column」。
        //
        // 这里选择**忽略**而不是报错：媒体中心三个分栏共用同一套请求构造，从"专辑详情"
        // 切到视频页时参数可能还挂在 URL 上 —— 为一个无害的多余参数让整页 400，是把
        // 客户端的状态残留升级成故障。忽略之后返回的仍是"这一类的完整列表"，语义没错。
        if (kind == Kind.AUDIO) {
            if (album != null) {
                clauses += "${MediaStore.Audio.Media.ALBUM} = ?"
                args += album
            }
            if (artist != null) {
                clauses += "${MediaStore.Audio.Media.ARTIST} = ?"
                args += artist
            }
            if (dir != null) {
                clauses += "(${MediaStore.MediaColumns.DATA} LIKE ? AND " +
                    "${MediaStore.MediaColumns.DATA} NOT LIKE ?)"
                args += "$dir/%"
                args += "$dir/%/%"
            }
        }

        if (clauses.isEmpty()) return null to null
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    private fun sortColumnOf(sort: String?): String = when (sort) {
        "name" -> MediaStore.MediaColumns.DISPLAY_NAME
        "size" -> MediaStore.MediaColumns.SIZE
        else -> MediaStore.MediaColumns.DATE_MODIFIED
    }

    /**
     * 游标一行 → 客户端媒体项。
     *
     * `/list`（整库分页）与 `/browse`（单层目录）必须给出**同一种**形状的 item ——
     * 两边各写一份的话，加字段时总会漏一处，客户端就会出现"列表模式有时长、
     * 文件夹模式没有"这种莫名差异。
     */
    private fun itemOf(
        c: Cursor,
        kind: Kind,
        subtitleCache: MutableMap<String, List<String>>? = null
    ): Map<String, Any?> {
        val path = c.stringOr(MediaStore.MediaColumns.DATA)
        val name = c.stringOr(MediaStore.MediaColumns.DISPLAY_NAME)
            .ifBlank { path.substringAfterLast('/') }
        return buildMap {
            put("id", c.longOr(MediaStore.MediaColumns._ID))
            put("name", name)
            put("path", path)
            put("size", c.longOr(MediaStore.MediaColumns.SIZE))
            // MediaStore 的 DATE_MODIFIED 是**秒**，客户端统一按毫秒处理
            put("date_modified", c.longOr(MediaStore.MediaColumns.DATE_MODIFIED) * 1000L)
            put("mime", c.stringOr(MediaStore.MediaColumns.MIME_TYPE))
            if (kind != Kind.IMAGE) {
                put("duration_ms", c.longOr(MediaStore.MediaColumns.DURATION))
            }
            if (kind != Kind.AUDIO) {
                put("width", c.intOr(MediaStore.MediaColumns.WIDTH))
                put("height", c.intOr(MediaStore.MediaColumns.HEIGHT))
            }
            if (kind == Kind.AUDIO) {
                put("album", c.stringOr(MediaStore.Audio.Media.ALBUM))
                put("artist", c.stringOr(MediaStore.Audio.Media.ARTIST))
                // 内嵌曲名。系统扫描时已解析好；**没有标签时系统会把文件名
                // 填进 TITLE**，那不是曲名，要按"没有"处理（见 realTitleOf）。
                // 列表这一层刻意不逐首解字节 —— 一页几十首就是几十次开文件；
                // 需要精确标签的场合走 `/api/media/tags`（单首、可缓存）。
                put("title", realTitleOf(c.stringOr(MediaStore.Audio.Media.TITLE), name))
            }
            // 视频：有几个外挂字幕（给列表打"CC"标用，客户端不必逐条问 /subtitles）。
            // 只在调用方传了 [subtitleCache] 时才算 —— 那个 map 让同一目录只 listFiles 一次，
            // 否则一层里几十个视频就是几十次目录扫描。
            if (kind == Kind.VIDEO && subtitleCache != null && path.isNotBlank()) {
                val file = File(path)
                val base = file.nameWithoutExtension
                val count = subtitleNamesIn(file.parentFile, subtitleCache)
                    .count { subtitleBelongsTo(it, base) }
                put("subtitle_count", count)
            }
        }
    }

    /**
     * 目录是否落在允许范围内（配置的扫描目录，或未配置时的外置存储根）。
     *
     * 这是 `/browse` 的唯一防线：路径来自客户端，不校验就等于开放整机文件系统的目录结构。
     * 同时挡掉 `..`（规范化后再比前缀，`realpath` 语义交给 [File.getCanonicalPath]）。
     */
    private fun browseRootsOf(kind: Kind): List<String> {
        val configured = scanDirs(kind).filter { File(it).isDirectory }
        if (configured.isNotEmpty()) return configured
        return listOfNotNull(
            runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()
        )
    }


    private fun projectionOf(kind: Kind): Array<String> {
        val base = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        when (kind) {
            Kind.VIDEO -> {
                base += MediaStore.MediaColumns.DURATION
                base += MediaStore.MediaColumns.WIDTH
                base += MediaStore.MediaColumns.HEIGHT
            }
            Kind.AUDIO -> {
                base += MediaStore.MediaColumns.DURATION
                base += MediaStore.Audio.Media.ALBUM
                base += MediaStore.Audio.Media.ARTIST
                // 2026-09-16 补 TITLE：这是**内嵌标签里的曲名**（系统扫描时就解析好了），
                // 与 DISPLAY_NAME（文件名）是两回事。缺它的后果是客户端只能拿文件名当歌名，
                // 播放页上显示的就是 `带我走-杨丞琳.flac` 这种东西。
                base += MediaStore.Audio.Media.TITLE
            }

            Kind.IMAGE -> {
                base += MediaStore.MediaColumns.WIDTH
                base += MediaStore.MediaColumns.HEIGHT
            }
        }
        return base.toTypedArray()
    }

    // ─────────────────── AudioItemLookup（供 PlaylistRoutes 回查歌单曲目） ───────────────────

    override fun audioReadable(): Boolean = isGranted(Kind.AUDIO)

    /**
     * 按绝对路径批量回查音频曲目。
     *
     * ## 为什么不叠扫描目录过滤（与 `/list` 不同）
     * `/list` 是"列媒体库"，扫描目录是用户划定的库边界，必须遵守。这里是"用户点名要的这几首"——
     * 歌加进歌单时就已经在库里了，之后用户去设置里把扫描范围收窄，不代表这些歌该显示成"已失效"。
     * 拿边界去过滤会让歌单莫名少一半，而用户完全不知道是哪一步造成的。
     *
     * 安全上不需要这层过滤兜底：路径能进歌单是因为 `PlaylistRoutes` 在加歌时用本方法验证过
     * 它确实是 MediaStore 里的一行音频，而 MediaStore 只索引外置存储上的媒体文件，
     * 任意路径（`/data/...`）根本查不出结果；真正的取流仍由 `/api/files/stream-ticket`
     * 的 `safeResolveForRead` 把关。
     *
     * @return 只包含**查到的**路径；查不到的一律不出现，调用方据此判 `missing`。
     */
    override suspend fun audioItemsByPaths(paths: List<String>): Map<String, Map<String, Any?>> {
        val wanted = paths.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            val uri = contentUriOf(Kind.AUDIO)
            val resolver = appContext.contentResolver
            val projection = projectionOf(Kind.AUDIO)
            val out = HashMap<String, Map<String, Any?>>(wanted.size)
            wanted.chunked(PATH_LOOKUP_CHUNK).forEach { batch ->
                val placeholders = batch.joinToString(",") { "?" }
                val args = Bundle().apply {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.MediaColumns.DATA} IN ($placeholders)"
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        batch.toTypedArray()
                    )
                }
                runCatching {
                    resolver.query(uri, projection, args, null)?.use { c ->
                        while (c.moveToNext()) {
                            val item = itemOf(c, Kind.AUDIO)
                            val path = item["path"] as? String ?: continue
                            out[path] = item
                        }
                    }
                }.onFailure {
                    AppLogger.w(TAG, "按路径回查音频失败(${batch.size} 条): ${it.message}")
                }
            }
            out
        }
    }

    private fun Cursor.longOr(column: String, fallback: Long = 0L): Long {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getLong(idx) else fallback
    }

    private fun Cursor.intOr(column: String, fallback: Int = 0): Int {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getInt(idx) else fallback
    }

    /**
     * 缩略图字节（JPEG）。两级：系统缩略图 → **自己生成**。
     *
     * 2026-09-16 加第二级。原来只调 `contentResolver.loadThumbnail()`，失败就 404，而且异常被
     * `runCatching{}.getOrNull()` 整个吞掉、一行日志都没有 —— 表现就是"列表有内容、缩略图全是
     * 占位图标"，且从任何一侧都查不出是哪一层挂了。
     *
     * 系统那条会失败的常见原因：
     *  · MediaProvider 交付缩略图按**细分媒体读权限**判（`READ_MEDIA_VIDEO` 等），
     *    「所有文件访问」不顶用（core 的运行时申请清单里原来漏了这三个权限）；
     *  · 这个文件从没被系统生成过缩略图缓存（刚 adb push / 刚下载的视频），
     *    而生成又要回到上一条权限；
     *  · 部分机型 ROM 的 MediaProvider 对非标准容器直接抛 IOException。
     *
     * 所以第二级不依赖 MediaProvider：视频用 [MediaMetadataRetriever] 抽一帧、图片自己降采样解码、
     * 音频取内嵌封面。这一级慢（要读文件、软解一帧），但**只在第一级失败时走**，
     * 且结果有强 ETag + `max-age=86400`，同一张只会算一次。
     */
    // ─────────────────── 缩略图缓存（客户端回传的成果） ───────────────────

    private fun thumbCacheDir(): File =
        File(appContext.filesDir, THUMB_CACHE_DIR).apply { if (!isDirectory) mkdirs() }

    /**
     * 缓存文件名只按 (type, id) 编，**不含 size**。
     *
     * 客户端交上来的是一张够大的图（512px），列表要 256、网格要 108 都能用它缩 ——
     * 按 size 分开存会让同一个视频存出好几份，缓存上限很快被自己撑满。
     */
    private fun thumbCacheFile(kind: Kind, id: Long): File =
        File(thumbCacheDir(), "${kind.key}_$id.jpg")

    /**
     * 按修改时间从旧到新删，直到总量回到上限以内。
     *
     * 不做 LRU（读命中时不去 touch 文件）：一 touch，基于 `lastModified` 的 ETag 就跟着变，
     * 客户端缓存全部作废 —— 为了淘汰得更准而让每次访问都重下一遍图，不划算。
     */
    private fun pruneThumbCache() {
        runCatching {
            val files = thumbCacheDir().listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= THUMB_CACHE_LIMIT_BYTES) return
            files.sortedBy { it.lastModified() }.forEach { f ->
                if (total <= THUMB_CACHE_LIMIT_BYTES) return
                val len = f.length()
                if (f.delete()) total -= len
            }
            AppLogger.i(TAG, "缩略图缓存已清理，当前 ${total / 1024} KB")
        }
    }

    /**
     * 一次缩略图尝试的结果。
     * [reason] 是**给人看的失败原因**（两级各自的异常类型与消息），会随 404 一起回给客户端。
     * 2026-09-16 加它的理由：设备端日志要连 ADB 才能看，而客户端只看到一个"缩略图不可用"，
     * 权限问题 / 解码栈缺失 / 文件损坏在两侧都长得一样。原因带回去之后，抓一份 app 的 HTTP
     * 日志就能定位到具体是哪一层。
     *
     * 只包含异常类名与消息，不含路径、token 之类的敏感信息。
     */
    private data class ThumbnailAttempt(val bytes: ByteArray?, val reason: String)

    /**
     * 缩略图字节（JPEG）。两级：系统缩略图 → MediaMetadataRetriever。
     *
     * 2026-09-16 加第二级。原来只调 `contentResolver.loadThumbnail()`，
     * 失败就 404，而且异常被 `runCatching{}.getOrNull()` 整个吞掉、一行日志都没有 ——
     * 表现就是"列表有内容、缩略图全是占位图标"，且从任何一侧都查不出是哪一层挂了。
     *
     * 系统那条会失败的常见原因：
     *  · MediaProvider 交付缩略图按**细分媒体读权限**判（`READ_MEDIA_VIDEO` 等），
     *    「所有文件访问」不顶用（core 的运行时申请清单里原来漏了这三个权限）；
     *  · 这个文件从没被系统生成过缩略图缓存（刚 adb push / 刚下载的视频），
     *    而生成又要回到上一条权限；
     *  · 定制 ROM（随身 WiFi 这类精简系统）的 codec 栈桥接不到硬件 VPU —— 实测本机
     *    `getFrameAtTime` 恒返回 null，所以第二级也出不了图。
     *
     * 第二级不依赖 MediaProvider 但仍依赖系统 codec。两级都失败就回 404，
     * 此时由**手机端抽帧回传**（`PUT /thumbnail`）兜底 —— 这是本机视频封面唯一可靠的来路。
     *
     * ## 决策：core 侧不做 ffmpeg 软解（2026-09-19 放弃，代码已移除）
     * 曾把 ffmpeg-kit 作为第三级（插件式下发 .so），**不要再直接重试这条路** ——
     * 现有预编译产物（arthenica 原版已从 Maven 下架，maintained fork 全系列）的 .so
     * 都按 16KB page size 链接（`PT_LOAD p_align = 16384`），而本机是 Android 12 / 4KB 页，
     * linker 给 RELRO 段做 mprotect 时会落到未映射区间，报
     * `can't enable GNU RELRO protection: Out of memory`。
     *
     * 这个报错极具误导性：与内存余量、加载方式（APK 内 mmap / 磁盘绝对路径 `System.load`）
     * 都无关，换版本也无效 —— 排查成本很高，所以把结论留在这里。
     *
     * **重启条件**：拿到按 `-Wl,-z,max-page-size=4096` 重编的 .so，或设备升到 16KB 页的
     * Android 版本。在那之前投入产出不划算 —— 手机 CPU 抽帧本来就比这台设备快得多，
     * 所以封面兜底走 `PUT /thumbnail`（手机端抽帧回传）。
     *
     * 第二级慢（读文件 + 解一帧），但**只在第一级失败时才走**，
     * 且结果有强 ETag + `max-age=86400`，同一张只会算一次。
     */
    private fun thumbnailBytes(kind: Kind, id: Long, size: Int): ThumbnailAttempt {
        val uri = ContentUris.withAppendedId(contentUriOf(kind), id)

        val systemThumb = runCatching {
            appContext.contentResolver.loadThumbnail(uri, Size(size, size), null)
        }
        val systemReason = systemThumb.exceptionOrNull()?.let { e ->
            val text = "系统缩略图 ${e.javaClass.simpleName}: ${e.message}"
            AppLogger.w(TAG, "系统缩略图不可用(${kind.key}/$id)，转自行生成: $text")
            text
        } ?: if (systemThumb.getOrNull() == null) "系统缩略图返回空" else null
        systemThumb.getOrNull()?.let {
            return ThumbnailAttempt(it.toJpegBytes(THUMB_JPEG_QUALITY), "")
        }

        val generated = when (kind) {
            Kind.VIDEO -> videoFrameThumbnail(uri, size)
            Kind.IMAGE -> downscaledImageThumbnail(uri, size)
            Kind.AUDIO -> runCatching { audioCoverBytes(id)?.first }
        }
        generated.getOrNull()?.let { return ThumbnailAttempt(it, "") }

        val generatedReason = generated.exceptionOrNull()
            ?.let { "自行生成 ${it.javaClass.simpleName}: ${it.message}" }
            ?: "自行生成返回空（解码器没吐出画面）"

        val reason = listOfNotNull(systemReason, generatedReason).joinToString(" / ")
        AppLogger.w(TAG, "缩略图生成失败(${kind.key}/$id): $reason")
        return ThumbnailAttempt(null, reason)
    }

    /**
     * 视频：在片长中段抽一帧，黑屏就换位置重试，再按 [size] 等比缩小。
     *
     * 位置选取与黑帧判定见 [VIDEO_FRAME_RATIOS] / [DARK_LUMA_THRESHOLD]。
     * 所有候选都偏暗时交**最亮的那张** —— 宁可给一张暗图，也比给纯黑或干脆没有强。
     */
    private fun videoFrameThumbnail(uri: android.net.Uri, size: Int): Result<ByteArray?> = runCatching {
        appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(pfd.fileDescriptor)
                val durationUs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?.times(1_000L)

                val positions = if (durationUs == null) {
                    longArrayOf(VIDEO_FRAME_FALLBACK_US)
                } else {
                    LongArray(VIDEO_FRAME_RATIOS.size) { i ->
                        (durationUs * VIDEO_FRAME_RATIOS[i]).toLong()
                    }
                }

                var best: Bitmap? = null
                var bestLuma = -1
                for (positionUs in positions) {
                    val frame = retriever.getFrameAtTime(
                        positionUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    ) ?: continue
                    val scaled = frame.scaledDown(size)
                    val luma = scaled.averageLuma()
                    if (luma >= DARK_LUMA_THRESHOLD) {
                        best?.recycle()
                        return@use scaled.toJpegBytes(THUMB_JPEG_QUALITY)
                    }
                    if (luma > bestLuma) {
                        best?.recycle()
                        best = scaled
                        bestLuma = luma
                    } else {
                        scaled.recycle()
                    }
                }
                best?.toJpegBytes(THUMB_JPEG_QUALITY)
                    ?: retriever.frameAtTime?.scaledDown(size)?.toJpegBytes(THUMB_JPEG_QUALITY)
            } finally {
                retriever.release()
            }
        }
    }

    /**
     * 网格采样的平均亮度（0~255），用来判断"这帧是不是黑屏"。
     *
     * 用感知加权 `(2R + 5G + B) / 8` 而不是简单平均：人眼对绿最敏感，
     * 简单平均会把纯绿画面算偏暗、纯蓝画面算偏亮。
     * 只采 [LUMA_SAMPLE_GRID]² 个点 —— 判断整屏明暗，均匀采样与全量统计结论一致。
     */
    private fun Bitmap.averageLuma(): Int {
        val stepX = (width / LUMA_SAMPLE_GRID).coerceAtLeast(1)
        val stepY = (height / LUMA_SAMPLE_GRID).coerceAtLeast(1)
        var sum = 0L
        var count = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val p = getPixel(x, y)
                sum += (((p shr 16) and 0xFF) * 2 + ((p shr 8) and 0xFF) * 5 + (p and 0xFF)) / 8
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count > 0) (sum / count).toInt() else 0
    }

    /**
     * 图片：先量尺寸再按 `inSampleSize` 降采样解码。
     *
     * 不整图解码再缩：一张 4000×3000 的照片整图进内存是 48MB，为了一个 256px 的格子不值得，
     * 大图还会直接 OOM。
     */
    private fun downscaledImageThumbnail(uri: android.net.Uri, size: Int): Result<ByteArray?> = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@runCatching null
        var sample = 1
        while (longest / (sample * 2) >= size) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
                ?.scaledDown(size)
                ?.toJpegBytes(THUMB_JPEG_QUALITY)
        }
    }

    /** 等比缩到最长边 = [size]（已经更小就原样返回，不放大：放大只会更糊还更费流量）。 */
    private fun Bitmap.scaledDown(size: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= size || longest <= 0) return this
        val scale = size.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== this) recycle()
        return scaled
    }

    private fun Bitmap.toJpegBytes(quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            compress(Bitmap.CompressFormat.JPEG, quality, out)
            recycle()
            out.toByteArray()
        }

    private fun Cursor.stringOr(column: String, fallback: String = ""): String {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getString(idx) ?: fallback else fallback
    }

    /** 音频 id → 真实路径（歌词旁挂文件要按路径找同名文件）。 */
    private fun audioPathOf(id: Long): String? = runCatching {
        appContext.contentResolver.query(
            ContentUris.withAppendedId(contentUriOf(Kind.AUDIO), id),
            arrayOf(MediaStore.MediaColumns.DATA),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.stringOr(MediaStore.MediaColumns.DATA).ifBlank { null } else null
        }
    }.getOrNull()

    // ── 外挂字幕（2026-09-19）──────────────────────────────────────────────

    /**
     * 目录 → 该目录下的字幕文件名列表。
     *
     * 存在的理由：`/list` 和 `/browse` 要给每条视频算 `subtitle_count`，一层里几十个视频
     * 如果各自 `listFiles()` 一遍，同一个目录就被扫了几十次。按目录缓存后每目录只扫一次。
     *
     * **只在单次请求内有效**：调用方自己建 map 传进来，不做跨请求缓存 ——
     * 用户随时可能往目录里拷字幕，缓存住反而要处理失效。
     */
    private fun subtitleNamesIn(dir: File?, cache: MutableMap<String, List<String>>): List<String> {
        val key = dir?.absolutePath ?: return emptyList()
        cache[key]?.let { return it }
        val names = runCatching {
            dir.listFiles()
                ?.asSequence()
                ?.filter { it.isFile }
                ?.map { it.name }
                ?.filter { it.substringAfterLast('.', "").lowercase(Locale.ROOT) in SUBTITLE_EXTENSIONS }
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
        cache[key] = names
        return names
    }

    /**
     * 判断字幕文件名是否属于某个视频。
     *
     * 规则：字幕名去掉后缀后，必须等于视频名（`movie.srt`），
     * 或以「视频名 + 分隔符」开头（`movie.zh.srt`、`movie.chs&eng.ass`、`movie - 中文.srt`）。
     *
     * 为什么不用 `startsWith(base)` 了事：那会让 `movie2.srt` 命中 `movie`，
     * 同一目录下有 `movie.mp4` / `movie2.mp4` 时字幕就串台了。必须卡住紧跟其后的分隔符。
     */
    private fun subtitleBelongsTo(subtitleName: String, videoBase: String): Boolean {
        val stem = subtitleName.substringBeforeLast('.', subtitleName)
        if (!stem.startsWith(videoBase, ignoreCase = true)) return false
        if (stem.length == videoBase.length) return true
        return stem[videoBase.length] in charArrayOf('.', '_', '-', ' ', '[', '(')
    }

    /**
     * 从字幕文件名里猜语言标记：`movie.zh-CN.srt` → `zh-CN`，`movie.srt` → 空串。
     *
     * 只是给客户端在字幕轨列表里显示个标签用，猜错了不影响播放（播放看的是 mime）。
     * 不做语言码白名单校验：`chs` / `简体` / `forced` 这些都是现实中存在的标记，
     * 一律原样带出去比"认不出就丢掉"有用。
     */
    private fun subtitleLabelOf(subtitleName: String, videoBase: String): String {
        val stem = subtitleName.substringBeforeLast('.', subtitleName)
        if (stem.length <= videoBase.length) return ""
        return stem.substring(videoBase.length).trim('.', '_', '-', ' ', '[', ']', '(', ')')
    }

    /**
     * 这个字幕文件 core 认不认（能给出 MIME 且大小在范围内）。
     *
     * 抽出来是因为它有两个调用方且必须同口径：[subtitleEntryOf] 的 `supported` 字段，
     * 与 `/subtitles` 的排序（能解析的排前面，见那里的注释）。
     */
    private fun subtitlePlayable(file: File): Boolean {
        val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return PLAYABLE_SUBTITLE_MIMES.containsKey(ext) && file.length() in 1..MAX_SUBTITLE_BYTES
    }

    /**
     * 一条字幕条目的对外形状（`/subtitles` 的 items 元素）。
     *
     * ## `supported` 的确切语义 —— 客户端**不能**直接拿它当"我能播"
     * 它的含义是「**core 能把这个文件转成 UTF-8 文本并给出字幕 MIME**」，
     * 判据是 [PLAYABLE_SUBTITLE_MIMES] + 大小范围，对齐的是 **app 端 media3** 的解析能力。
     *
     * 各端的真实能力并不一致，客户端必须按自身情况**再过一层**：
     *  · app（media3）：srt / ass / ssa / vtt / ttml 都能解 —— 与 `supported` 基本等价；
     *  · web（浏览器 `<track>`）：**只认 WebVTT**。srt 要靠播放器内部转换（ArtPlayer 会），
     *    ass/ssa 要额外的渲染插件（libass），ttml 则没有通用方案。
     *
     * 所以 web 侧另有一份自己的能力映射（`web/src/composables/subtitleFormat.ts`），
     * 与这里**刻意不同**。不要为了"统一"把两边合成一份 —— 合了之后必然有一端在说谎：
     * 要么 web 把 ttml 当能播（选了却什么都不显示），要么 app 把能播的 ass 标成不支持。
     */
    private fun subtitleEntryOf(file: File, videoBase: String): Map<String, Any?> {
        val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = PLAYABLE_SUBTITLE_MIMES[ext]
        return mapOf(
            "name" to file.name,
            "path" to file.absolutePath,
            "ext" to ext,
            "size" to file.length(),
            // supported = core 认这个格式（见 KDoc）。false 的仍然列出来，
            // 否则用户看到目录里明明有 .sub 却在 App 里查无此文件，只会以为是 bug。
            "supported" to subtitlePlayable(file),
            "mime" to (mime ?: ""),
            "label" to subtitleLabelOf(file.name, videoBase)
        )
    }

    /**
     * 把客户端给的路径收敛成"确实在用户存储里的真实文件"。
     *
     * 两道关必须都过：
     *  1. canonical 化（解掉 `..` 与符号链接）**之后**再比前缀 —— 只查原始字符串的话，
     *     `/sdcard/../data/data/...` 这种能骗过前缀检查；
     *  2. 前缀白名单与 [ALLOWED_DIR_PREFIXES] 同口径，和 `/config` 那边保持一致。
     *
     * 返回 null = 不合法或不存在，调用方一律回 400/404，不区分（区分了就是在告诉
     * 外部"这个路径存在但你不能访问"，等于送出一个目录探测器）。
     */
    private fun safeUserFile(path: String?): File? {
        val raw = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val file = runCatching { File(raw).canonicalFile }.getOrNull() ?: return null
        val abs = file.absolutePath
        if (!ALLOWED_DIR_PREFIXES.any { abs.startsWith(it) }) return null
        return file.takeIf { it.isFile }
    }

    /**
     * 把字幕文件读成 UTF-8 文本。
     *
     * ## 为什么必须由 core 转码
     * 客户端播放器（media3）的字幕解析器按 **UTF-8** 解字节，而中文字幕现实中大量是
     * GB18030/Big5 —— 直接把原始文件喂给播放器，出来的就是一屏乱码，且播放器不提供
     * "换个编码重试"的入口。core 在这里一次转干净，客户端永远只见 UTF-8。
     *
     * 探测顺序（没有第三方依赖，靠解码器自身的合法性判定）：
     *  1. BOM：UTF-8 / UTF-16LE / UTF-16BE 直接认（BOM 是明示，不用猜）；
     *  2. 严格 UTF-8（`REPORT` 而非默认的 `REPLACE`）：能整段解通就是 UTF-8。
     *     这一步必须严格 —— 默认的替换模式会把非法字节变成 `�` 然后"成功"，
     *     于是所有 GBK 文件都会被误判成 UTF-8；
     *  3. GB18030：单/双/四字节全覆盖，是 GBK/GB2312 的超集，中文场景命中率最高；
     *  4. Big5：繁体字幕；
     *  5. 兜底 Latin-1（永不失败，至少时间轴和数字还能用）。
     */
    private fun decodeSubtitleText(file: File): String {
        val bytes = file.readBytes()
        // BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        for (name in listOf("UTF-8", "GB18030", "Big5")) {
            val decoded = runCatching {
                val decoder = Charset.forName(name).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            }.getOrNull()
            if (decoded != null) return decoded
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    /**
     * 单首音频的标签：**自己解字节的结果优先**，MediaStore 那份只作为底。
     *
     * 三级，从可信到不可信：
     *  1. [AudioTagReader]：直接读容器头部的 FLAC Vorbis Comment / ID3v2 帧。不依赖 codec，
     *     所以在本机（解不出视频画面的定制 ROM）上照样准 —— 这是 2026-09-16 加它的原因：
     *     此前只用 `MediaMetadataRetriever`，FLAC 上经常拿回空标签；
     *  2. `MediaMetadataRetriever`：容器本身不认识（m4a 等）时还能捞一把；
     *  3. `MediaStore` 的 title/artist/album：系统扫描的结果，可能是空或 `<unknown>`。
     *
     * 返回 null = 这个 id 在音频库里不存在（调用方回 404）。
     * 曲名取不到时**回空串**，不拿文件名冒充 —— 显示层的兜底策略由客户端决定。
     */
    private fun audioTagsOf(id: Long): Map<String, Any?>? {
        val uri = ContentUris.withAppendedId(contentUriOf(Kind.AUDIO), id)
        var path = ""
        var name = ""
        var title = ""
        var artist = ""
        var album = ""
        var durationMs = 0L

        val exists = runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DURATION,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM
                ),
                null, null, null
            )?.use { c ->
                if (!c.moveToFirst()) return@use false
                path = c.stringOr(MediaStore.MediaColumns.DATA)
                name = c.stringOr(MediaStore.MediaColumns.DISPLAY_NAME)
                    .ifBlank { path.substringAfterLast('/') }
                durationMs = c.longOr(MediaStore.MediaColumns.DURATION)
                // MediaStore 在没有标签时会把**文件名**填进 TITLE，那不是曲名
                title = realTitleOf(c.stringOr(MediaStore.Audio.Media.TITLE), name)
                artist = sanitizeTag(c.stringOr(MediaStore.Audio.Media.ARTIST))
                album = sanitizeTag(c.stringOr(MediaStore.Audio.Media.ALBUM))
                true
            } ?: false
        }.getOrDefault(false)
        if (!exists) return null

        // ① 自己解字节（最准）
        if (path.isNotBlank()) {
            val parsed = AudioTagReader.read(File(path))
            sanitizeTag(parsed.title).takeIf { it.isNotEmpty() }?.let { title = it }
            sanitizeTag(parsed.artist).takeIf { it.isNotEmpty() }?.let { artist = it }
            sanitizeTag(parsed.album).takeIf { it.isNotEmpty() }?.let { album = it }
        }

        // ② 还缺什么，再让系统 retriever 试一次（m4a / 老格式）
        if (title.isBlank() || artist.isBlank() || album.isBlank() || durationMs <= 0) {
            runCatching {
                appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(pfd.fileDescriptor)
                        fun key(k: Int): String = sanitizeTag(retriever.extractMetadata(k).orEmpty())
                        if (title.isBlank()) {
                            key(MediaMetadataRetriever.METADATA_KEY_TITLE)
                                .takeIf { it.isNotEmpty() }?.let { title = it }
                        }
                        if (artist.isBlank()) {
                            key(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                .ifEmpty { key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) }
                                .takeIf { it.isNotEmpty() }?.let { artist = it }
                        }
                        if (album.isBlank()) {
                            key(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                                .takeIf { it.isNotEmpty() }?.let { album = it }
                        }
                        if (durationMs <= 0) {
                            key(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                .toLongOrNull()?.takeIf { it > 0 }?.let { durationMs = it }
                        }
                    } finally {
                        retriever.release()
                    }
                }
            }.onFailure {
                AppLogger.w(TAG, "读音频标签失败(id=$id): ${it.message}")
            }
        }

        return mapOf(
            "id" to id,
            "name" to name,
            "path" to path,
            "title" to title,
            "artist" to artist,
            "album" to album,
            "duration_ms" to durationMs
        )
    }

    /**
     * 判断 `MediaStore.Audio.Media.TITLE` 是不是**真的曲名**。
     *
     * 系统扫描到没有标签的文件时，会把去掉扩展名的**文件名**填进 TITLE。原样透出去，
     * 客户端就会把 `带我走 - 杨丞琳` 当成曲名显示（还压掉了"按分隔符拆成歌名+歌手"的兜底）。
     * 所以与文件名一致时按"没有标签"处理，回空串。
     */
    private fun realTitleOf(rawTitle: String, displayName: String): String {
        val title = sanitizeTag(rawTitle)
        if (title.isEmpty()) return ""
        val base = displayName.substringBeforeLast('.', missingDelimiterValue = displayName).trim()
        return if (title.equals(base, ignoreCase = true)) "" else title
    }


    /**
     * 标签值清洗。
     *
     * `<unknown>` 是 MediaStore 在艺术家/专辑缺失时填的**占位符**，不是真值 —— 原样透出去，
     * 客户端就会把它当歌手显示出来。这里统一按"没有"处理。
     */
    private fun sanitizeTag(raw: String): String {
        val v = raw.trim()
        return if (v.isEmpty() || v.equals("<unknown>", ignoreCase = true)) "" else v
    }

    /**
     * 音频封面字节 + 内容类型。
     *
     * 先取内嵌封面（原图字节，不重新编码 → 最高画质），失败再退回 `loadThumbnail(1024)`。
     * 两条都拿不到就回 null，由调用方回 404 —— 客户端画占位图标。
     */
    private fun audioCoverBytes(id: Long): Pair<ByteArray, ContentType>? {
        val uri = ContentUris.withAppendedId(contentUriOf(Kind.AUDIO), id)
        val embedded = runCatching {
            appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(pfd.fileDescriptor)
                    retriever.embeddedPicture
                } finally {
                    retriever.release()
                }
            }
        }.getOrNull()
        if (embedded != null && embedded.isNotEmpty()) {
            // 内嵌封面常见是 JPEG，也可能是 PNG：按魔数判，别一律声明成 JPEG
            val isPng = embedded.size > 8 && embedded[0] == 0x89.toByte() &&
                embedded[1] == 'P'.code.toByte()
            return embedded to (if (isPng) ContentType.Image.PNG else ContentType.Image.JPEG)
        }
        return runCatching {
            val bitmap = appContext.contentResolver.loadThumbnail(
                uri, Size(MAX_THUMB_SIZE, MAX_THUMB_SIZE), null
            )
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { it to ContentType.Image.JPEG }
    }

    // ═══════════════════ 音频分组聚合（/groups，2026-09-20） ═══════════════════

    /**
     * 分组维度。
     *
     * 只有音频有这三个维度：album / artist 来自内嵌标签，folder 来自路径。
     * 视频与图片没有等价的标签层（"文件夹视图"它们走 `/browse`），所以 `/groups` 不收其它类型。
     */
    private enum class GroupBy(val key: String) {
        ALBUM("album"), ARTIST("artist"), FOLDER("folder");

        companion object {
            fun of(raw: String?): GroupBy? = entries.firstOrNull { it.key == raw }
        }
    }

    /**
     * 聚合过程中的可变累加器。
     *
     * 一次游标遍历就把三种维度需要的东西都攒齐，**不对每个分组再回查一次库** ——
     * 那是 N+1：500 个专辑就是 501 次 MediaStore 查询，在这台设备上是秒级的事。
     */
    private class GroupAccumulator {
        var count = 0
        var coverId = NO_COVER_ID

        /**
         * album 维度用：组内每个 artist 出现了几次。
         * 副标题要的是"这张专辑的主要歌手"，而合辑里每首的 artist 都不同 ——
         * 取第一首会随排序漂移，所以按出现次数投票。
         */
        val artistHistogram = HashMap<String, Int>()

        /** artist 维度用：组内出现过的专辑名，去重后的个数就是"N 张专辑"的 N。 */
        val albums = HashSet<String>()
    }

    /** 一个分组的最终形态。用具名类型而不是 Map，是为了让排序键不必到处强转。 */
    private data class AudioGroup(
        val key: String,
        val title: String,
        val subtitle: String,
        val count: Int,
        val coverId: Long
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "key" to key,
            "title" to title,
            "subtitle" to subtitle,
            "count" to count,
            "cover_id" to coverId
        )
    }

    /**
     * 标签归一化：去首尾空白，并把 MediaStore 的 [MEDIASTORE_UNKNOWN] 占位当成"没有标签"。
     *
     * 不做这一步会同时出现一个叫 `<unknown>` 的分组和一个叫「未知专辑」的分组，
     * 而它们本来就是同一堆没有标签的文件。
     */
    private fun normalizedTag(raw: String): String =
        raw.trim().takeUnless { it.equals(MEDIASTORE_UNKNOWN, ignoreCase = true) } ?: ""

    /**
     * 按 [by] 把音频表聚合成分组列表。
     *
     * 目录过滤沿用 [scanDirs]（与 `/list` 同一口径）：分组视图与列表视图看到的必须是同一个
     * 媒体库范围，否则会出现"分组里有 12 首、点进去只有 8 首"。
     *
     * 排序在 Kotlin 侧做（count 降序 → title 升序），不交给 SQL：`GROUP BY` 的结果集在
     * MediaStore 的 `QUERY_ARG_SQL_*` 上并不保证可用（各 ROM 的 MediaProvider 对
     * 分组语句支持不一），而这里的数据量本来就是"几百个分组"级别，内存排序毫无压力。
     */
    private fun audioGroupsPayload(by: GroupBy): Map<String, Any?> {
        val (selection, selectionArgs) = dirSelection(scanDirs(Kind.AUDIO))
        val buckets = HashMap<String, GroupAccumulator>()

        runCatching {
            val queryArgs = Bundle().apply {
                if (selection != null) {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                }
            }
            appContext.contentResolver.query(
                contentUriOf(Kind.AUDIO), GROUPS_PROJECTION, queryArgs, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val album = normalizedTag(c.stringOr(MediaStore.Audio.Media.ALBUM))
                    val artist = normalizedTag(c.stringOr(MediaStore.Audio.Media.ARTIST))
                    val path = c.stringOr(MediaStore.MediaColumns.DATA)
                    val key = when (by) {
                        GroupBy.ALBUM -> album
                        GroupBy.ARTIST -> artist
                        // folder 的 key 是目录**绝对路径**：客户端要拿它去 `/list?dir=` 回查，
                        // 目录名会重名（几十个 Music/xxx 都叫 Download），只有全路径唯一。
                        GroupBy.FOLDER -> path.substringBeforeLast('/', "")
                    }
                    // 推不出目录（DATA 为空或不含分隔符）时直接跳过：key 是要回查的，
                    // 一个空 key 的文件夹分组点进去必然是空列表 —— 不如不给。
                    if (by == GroupBy.FOLDER && key.isBlank()) continue

                    val bucket = buckets.getOrPut(key) { GroupAccumulator() }
                    bucket.count++
                    // 组内任意一首都能当封面来源（同专辑/同目录的内嵌封面基本一致），
                    // 所以第一个有效 id 就收手，不为"挑一张最好的"再去读文件。
                    if (bucket.coverId == NO_COVER_ID) {
                        bucket.coverId = c.longOr(MediaStore.MediaColumns._ID)
                    }
                    when (by) {
                        GroupBy.ALBUM -> if (artist.isNotBlank()) {
                            bucket.artistHistogram[artist] =
                                (bucket.artistHistogram[artist] ?: 0) + 1
                        }
                        GroupBy.ARTIST -> if (album.isNotBlank()) bucket.albums += album
                        GroupBy.FOLDER -> Unit
                    }
                }
            }
        }.onFailure {
            AppLogger.w(TAG, "音频分组聚合失败(by=${by.key}): ${it.message}")
        }

        val groups = buckets.map { (key, bucket) ->
            AudioGroup(
                key = key,
                title = when (by) {
                    // folder 展示目录名就够了（父路径放 subtitle），全路径会把列表撑爆
                    GroupBy.FOLDER -> key.substringAfterLast('/').ifBlank { key }
                    GroupBy.ALBUM -> key.ifBlank { UNKNOWN_ALBUM_TITLE }
                    GroupBy.ARTIST -> key.ifBlank { UNKNOWN_ARTIST_TITLE }
                },
                subtitle = when (by) {
                    GroupBy.ALBUM -> bucket.artistHistogram.maxByOrNull { it.value }?.key ?: ""
                    GroupBy.ARTIST -> "${bucket.albums.size} 张专辑"
                    // 父路径：同名目录（两张卡各有一个 Music）靠它区分
                    GroupBy.FOLDER -> key.substringBeforeLast('/', "")
                },
                count = bucket.count,
                coverId = bucket.coverId
            )
        }.sortedWith(
            // count 降序（听得最多的专辑通常也是曲目最全的那张），同数量再按 title 升序 ——
            // 少了第二级排序，同 count 的分组顺序会跟着 HashMap 的迭代顺序随机漂移，
            // 客户端每次刷新看到的排列都不一样。
            compareByDescending<AudioGroup> { it.count }.thenBy { it.title }
        )

        return mapOf(
            "type" to Kind.AUDIO.key,
            "by" to by.key,
            "groups" to groups.map { it.toMap() },
            // total 是**分组总数**（不是曲目总数，曲目数在每组的 count 里）：
            // 客户端用它显示"共 37 张专辑"，也用来判断是不是一个都没聚合出来。
            "total" to groups.size
        )
    }

    fun register(route: Route) {
        route.route("/media") {

            /**
             * 三类媒体各自的授权状态 + 当前扫描目录。
             *
             * 客户端据此决定"这个分栏是显示列表、还是显示未授权引导" —— 不许在没权限时
             * 显示一个空列表（那就是骗用户"设备里没有视频"）。
             */
            get("/status") {
                call.respond(
                    toJsonElement(
                        mapOf(
                            "all_files_access" to runCatching { Environment.isExternalStorageManager() }
                                .getOrDefault(false),
                            "granted" to Kind.entries.associate { it.key to isGranted(it) },
                            "scan_dirs" to Kind.entries.associate { it.key to scanDirs(it) },
                            "sdk_int" to Build.VERSION.SDK_INT
                        )
                    )
                )
            }

            /**
             * 列某一类媒体。分页 + 排序 + 目录过滤都在 SQL 侧完成，不把整表拉进内存。
             *
             * `total` 单独数一次（同 selection、只取 _ID）：客户端要用它算"还有没有下一页"，
             * 而 `items.size` 只能说明这一页有多少。
             *
             * ## 可选过滤：album / artist / dir（2026-09-20）
             * 给「音频分组」用的回查入口 —— `/groups` 给出分组的 `key`，客户端把它原样送回
             * 对应参数就能拿到组内曲目，core 侧不需要为"专辑详情"再开一个端点。
             * 三个参数只对 `type=audio` 生效（理由见 [listSelection]），与扫描目录过滤是 AND。
             *
             * 本端点不走 [ResponseCache]：它是分页查询，工作量已经被 SQL 的 LIMIT 限住了，
             * 叠内存缓存只会多出一份要失效的状态 —— 所以过滤参数也不存在"串缓存"的问题。
             */
            get("/list") {
                val kind = Kind.of(call.request.queryParameters["type"]) ?: run {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "type 必须是 video / audio / image"
                    )
                    return@get
                }
                if (!isGranted(kind)) {
                    call.respondFail(
                        HttpStatusCode.Forbidden, ErrorCode.BAD_REQUEST,
                        "媒体权限未授权：${permissionOf(kind)}",
                        mapOf("permission" to permissionOf(kind), "type" to kind.key)
                    )
                    return@get
                }

                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PAGE_SIZE)
                    .coerceIn(1, MAX_PAGE_SIZE)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0)
                    .coerceAtLeast(0)
                val sortColumn = sortColumnOf(call.request.queryParameters["sort"])
                val desc = call.request.queryParameters["order"]?.lowercase() != "asc"
                val albumFilter = filterParam(call.request.queryParameters["album"])
                val artistFilter = filterParam(call.request.queryParameters["artist"])
                // 末尾斜杠要去掉：客户端可能传 `/sdcard/Music/`，不去掉会拼出 `//%` 而一条都匹配不上
                val dirFilter = filterParam(call.request.queryParameters["dir"])?.trimEnd('/')
                val (selection, selectionArgs) =
                    listSelection(kind, albumFilter, artistFilter, dirFilter)

                withContext(Dispatchers.IO) {
                    val uri = contentUriOf(kind)
                    val resolver = appContext.contentResolver

                    val total = runCatching {
                        val args = Bundle().apply {
                            if (selection != null) {
                                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                                putStringArray(
                                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                                    selectionArgs
                                )
                            }
                        }
                        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), args, null)
                            ?.use { it.count } ?: 0
                    }.getOrElse {
                        AppLogger.w(TAG, "媒体计数失败(${kind.key}): ${it.message}")
                        0
                    }

                    val items = mutableListOf<Map<String, Any?>>()
                    runCatching {
                        val args = Bundle().apply {
                            if (selection != null) {
                                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                                putStringArray(
                                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                                    selectionArgs
                                )
                            }
                            putString(
                                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                                "$sortColumn ${if (desc) "DESC" else "ASC"}"
                            )
                            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                        }
                        // 字幕计数的目录缓存：只活在这一次查询内（用户随时会往目录拷字幕，
                        // 跨请求缓存就得处理失效，不值得）
                        val subtitleCache = mutableMapOf<String, List<String>>()
                        resolver.query(uri, projectionOf(kind), args, null)?.use { c ->
                            while (c.moveToNext()) {
                                items += itemOf(c, kind, subtitleCache)
                            }
                        }
                    }.onFailure {
                        AppLogger.w(TAG, "媒体查询失败(${kind.key}): ${it.message}")
                    }

                    call.respond(
                        toJsonElement(
                            mapOf(
                                "type" to kind.key,
                                "items" to items,
                                "total" to total,
                                "limit" to limit,
                                "offset" to offset,
                                "scan_dirs" to scanDirs(kind)
                            )
                        )
                    )
                }
            }

            /**
             * 音频分组聚合：按专辑 / 歌手 / 文件夹把整个音乐库归堆。
             *
             * ## 为什么放在 core 而不是客户端自己分
             * 客户端要分组就得先把**整库**拉下来（`/list` 分页拉完几千首）才能统计，
             * 而它真正想显示的只是"37 张专辑"这一屏。聚合在 core 做，一次游标遍历就够，
             * 网络上只走分组结果；app 与 Web 两端也不必各写一份口径可能不同的统计逻辑。
             *
             * ## 只支持音频
             * album / artist 是 `MediaStore.Audio` 独有的标签维度，视频与图片没有等价物
             * （它们的"文件夹视图"走 `/browse`）。所以其它 type 一律 400，而不是回一个空分组列表
             * —— 空列表会被客户端理解成"这台设备没有视频"。
             *
             * ## 客户端怎么用回查
             * 拿 `groups[].key` 送回 `/list`：album → `?album=`、artist → `?artist=`、
             * folder → `?dir=`。封面走 `/thumbnail?type=audio&id=<cover_id>`。
             */
            get("/groups") {
                // 先判 type：非 audio 的语义是"这个维度不存在"，比 by 的取值问题更靠前
                val kind = Kind.of(call.request.queryParameters["type"])
                if (kind != Kind.AUDIO) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "分组仅支持音频：type 必须是 audio"
                    )
                    return@get
                }
                val by = GroupBy.of(call.request.queryParameters["by"]) ?: run {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "by 必须是 album / artist / folder"
                    )
                    return@get
                }
                // 未授权的处理与 /list 一致：明确告诉客户端缺哪个权限，让它去引导授权，
                // 而不是回一个空列表假装"设备里没有音乐"
                if (!isGranted(kind)) {
                    call.respondFail(
                        HttpStatusCode.Forbidden, ErrorCode.BAD_REQUEST,
                        "媒体权限未授权：${permissionOf(kind)}",
                        mapOf("permission" to permissionOf(kind), "type" to kind.key)
                    )
                    return@get
                }

                // 缓存 key 含 type 与 by（三个视图各存一份，否则切换维度会拿到上一次的分组），
                // 再含扫描目录 —— 用户在设置里改了媒体库范围之后，同一个 by 已经不是同一份数据了。
                val cacheKey = "$GROUPS_CACHE_PREFIX:${kind.key}:${by.key}:" +
                    scanDirs(kind).joinToString("|")
                val payload = cache.getOrPut(cacheKey, CacheTTL.MEDIA_GROUPS) {
                    withContext(Dispatchers.IO) { toJsonElement(audioGroupsPayload(by)) }
                }
                call.respond(payload)
            }

            /**
             * 按目录列一层（媒体库的"文件夹视图"）。
             *
             * 与 `/list` 的分工：`/list` 是"整库平铺 + 分页"，本端点是"这一层里有什么" ——
             * 子目录和这一层的媒体文件一起给，客户端照文件管理器那样画。
             *
             * ## 目录来源与边界
             * `path` 为空时：只配了一个扫描目录就**直接进那一个**（多包一层"根目录"没有意义），
             * 配了多个则回一份 roots 列表让客户端先选，一个都没配就用外置存储根。
             * `path` 非空时必须落在这些根之内 —— 校验用规范化路径比前缀，否则 `..` 能爬出去，
             * 那就等于把整机目录结构开放给了客户端。
             *
             * ## 为什么子目录走 File 而文件走 MediaStore
             * 目录本身不在 MediaStore 里（它只索引文件），所以子目录必须列文件系统；
             * 而文件要的是 id / 时长 / 尺寸这些**只有媒体库才有**的字段。两边各取所长。
             * 子目录的计数与封面用一次 count 查询解决（`DATA LIKE '<sub>/%'`），
             * 空文件夹（该类型一个文件都没有）直接不显示 —— 点进去只会是一片空白。
             */
            get("/browse") {
                val kind = Kind.of(call.request.queryParameters["type"]) ?: run {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "type 必须是 video / audio / image"
                    )
                    return@get
                }
                if (!isGranted(kind)) {
                    call.respondFail(
                        HttpStatusCode.Forbidden, ErrorCode.BAD_REQUEST,
                        "媒体权限未授权：${permissionOf(kind)}",
                        mapOf("permission" to permissionOf(kind), "type" to kind.key)
                    )
                    return@get
                }

                val roots = browseRootsOf(kind)
                val requested = call.request.queryParameters["path"]?.trim()?.trimEnd('/')
                    ?.takeIf { it.isNotBlank() }
                val target = requested ?: roots.singleOrNull()

                // 多个根：先让客户端选一个，不假装有"共同父目录"
                if (target == null) {
                    call.respond(
                        toJsonElement(
                            mapOf(
                                "type" to kind.key,
                                "path" to "",
                                "parent" to null,
                                "roots" to roots,
                                "folders" to emptyList<Map<String, Any?>>(),
                                "items" to emptyList<Map<String, Any?>>()
                            )
                        )
                    )
                    return@get
                }

                val canonical = runCatching { File(target).canonicalPath }.getOrNull()
                val inRoots = canonical != null && roots.any { root ->
                    val rootCanonical = runCatching { File(root).canonicalPath }.getOrNull() ?: root
                    canonical == rootCanonical || canonical.startsWith("$rootCanonical/")
                }
                if (!inRoots) {
                    call.respondFail(
                        HttpStatusCode.Forbidden, ErrorCode.BAD_REQUEST,
                        "目录不在允许范围内：请先把它加进本类型的扫描目录",
                        mapOf("path" to target, "roots" to roots)
                    )
                    return@get
                }
                val dir = File(canonical!!)
                if (!dir.isDirectory) {
                    call.respondFail(
                        HttpStatusCode.NotFound, ErrorCode.NOT_FOUND,
                        "目录不存在或不可读: $canonical"
                    )
                    return@get
                }

                val sortColumn = sortColumnOf(call.request.queryParameters["sort"])
                val desc = call.request.queryParameters["order"]?.lowercase() != "asc"

                withContext(Dispatchers.IO) {
                    val resolver = appContext.contentResolver
                    val uri = contentUriOf(kind)

                    // 这一层的文件：DATA 以本目录开头，但**不再往下一层**（排掉 '<dir>/x/y'）
                    val items = mutableListOf<Map<String, Any?>>()
                    runCatching {
                        val args = Bundle().apply {
                            putString(
                                ContentResolver.QUERY_ARG_SQL_SELECTION,
                                "${MediaStore.MediaColumns.DATA} LIKE ? AND " +
                                    "${MediaStore.MediaColumns.DATA} NOT LIKE ?"
                            )
                            putStringArray(
                                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                                arrayOf("$canonical/%", "$canonical/%/%")
                            )
                            putString(
                                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                                "$sortColumn ${if (desc) "DESC" else "ASC"}"
                            )
                            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_PAGE_SIZE)
                        }
                        // 本层是同一个目录，字幕的 listFiles 只会发生一次
                        val subtitleCache = mutableMapOf<String, List<String>>()
                        resolver.query(uri, projectionOf(kind), args, null)?.use { c ->
                            while (c.moveToNext()) items += itemOf(c, kind, subtitleCache)
                        }
                    }.onFailure {
                        AppLogger.w(TAG, "目录列表查询失败(${kind.key}, $canonical): ${it.message}")
                    }

                    // 子目录：数一次该子树里的同类文件，顺便取一个 id 当文件夹封面
                    val folders = mutableListOf<Map<String, Any?>>()
                    val children = runCatching { dir.listFiles() }.getOrNull().orEmpty()
                        .filter { it.isDirectory && !it.name.startsWith(".") }
                        .sortedBy { it.name.lowercase() }
                        .take(MAX_BROWSE_FOLDERS)
                    for (sub in children) {
                        val subPath = sub.absolutePath.trimEnd('/')
                        var count = 0
                        var coverId: Long? = null
                        runCatching {
                            val args = Bundle().apply {
                                putString(
                                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                                    "${MediaStore.MediaColumns.DATA} LIKE ?"
                                )
                                putStringArray(
                                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                                    arrayOf("$subPath/%")
                                )
                                putString(
                                    ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                                )
                            }
                            resolver.query(
                                uri, arrayOf(MediaStore.MediaColumns._ID), args, null
                            )?.use { c ->
                                count = c.count
                                if (c.moveToFirst()) {
                                    coverId = c.longOr(MediaStore.MediaColumns._ID)
                                }
                            }
                        }
                        if (count <= 0) continue
                        folders += mapOf(
                            "name" to sub.name,
                            "path" to subPath,
                            "count" to count,
                            "cover_id" to coverId,
                            "date_modified" to sub.lastModified()
                        )
                    }

                    // parent：已经在根上就是 null（客户端据此决定还能不能往上退）
                    val parent = if (roots.any {
                            (runCatching { File(it).canonicalPath }.getOrNull() ?: it) == canonical
                        }) {
                        null
                    } else {
                        dir.parentFile?.absolutePath
                    }

                    call.respond(
                        toJsonElement(
                            mapOf(
                                "type" to kind.key,
                                "path" to canonical,
                                "parent" to parent,
                                "roots" to roots,
                                "folders" to folders,
                                "items" to items
                            )
                        )
                    )
                }
            }

            /**
             * 缩略图（JPEG）。三级：**客户端回传的缓存** → 系统缩略图 → 自行抽帧。
             *
             * 缓存排第一是因为本机很可能根本解不出画面（随身 WiFi 的定制 ROM 没有可用的视频
             * 解码器，实测系统与自抽双双失败）。这种设备上唯一能出图的办法就是让**有解码能力
             * 的客户端**（手机 App）抽好帧回传（`PUT /api/media/thumbnail`），core 只管存与发。
             * 好处是回传一次之后，Web 端、第二台手机都能直接看到。
             *
             * 三级都拿不到就回 404 **并带上失败原因**，让客户端画占位图标 ——
             * 绝不回一张破图或空 200。
             */
            get("/thumbnail") {
                val kind = Kind.of(call.request.queryParameters["type"]) ?: run {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "type 必须是 video / audio / image"
                    )
                    return@get
                }
                val id = call.request.queryParameters["id"]?.toLongOrNull() ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "缺少 id")
                    return@get
                }
                if (!isGranted(kind)) {
                    call.respondFail(
                        HttpStatusCode.Forbidden, ErrorCode.BAD_REQUEST,
                        "媒体权限未授权：${permissionOf(kind)}"
                    )
                    return@get
                }

                // ① 客户端回传的缓存：命中就直接发，不再碰解码器
                val cached = thumbCacheFile(kind, id)
                if (cached.isFile && cached.length() > 0) {
                    val cachedEtag = "\"media-${kind.key}-$id-c${cached.lastModified()}\""
                    if (call.request.header(HttpHeaders.IfNoneMatch) == cachedEtag) {
                        call.respond(HttpStatusCode.NotModified)
                        return@get
                    }
                    call.response.header(HttpHeaders.ETag, cachedEtag)
                    call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
                    call.respondBytes(
                        withContext(Dispatchers.IO) { cached.readBytes() },
                        ContentType.Image.JPEG
                    )
                    return@get
                }

                val size = (call.request.queryParameters["size"]?.toIntOrNull() ?: DEFAULT_THUMB_SIZE)
                    .coerceIn(MIN_THUMB_SIZE, MAX_THUMB_SIZE)

                // 缩略图对同一 (type,id,size) 是稳定的 → 给强 ETag，让客户端与 Coil 磁盘缓存复用
                val etag = "\"media-${kind.key}-$id-$size\""
                if (call.request.header(HttpHeaders.IfNoneMatch) == etag) {
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }

                val attempt = withContext(Dispatchers.IO) { thumbnailBytes(kind, id, size) }
                val bytes = attempt.bytes
                if (bytes == null || bytes.isEmpty()) {
                    /*
                     * 失败原因随 404 一起回去（2026-09-16）。
                     *
                     * 设备端日志要连 ADB 才看得到，而客户端原来只拿到一句"缩略图不可用" ——
                     * 权限被拒、解码栈缺失、文件损坏在两侧长得完全一样，等于没有可观测性。
                     * 现在 message 里带异常类型与消息（不含路径与凭据），抓一份客户端 HTTP 日志
                     * 就能定位到是哪一层。
                     */
                    call.respondFail(
                        HttpStatusCode.NotFound, ErrorCode.BAD_REQUEST,
                        "缩略图不可用: ${attempt.reason.ifBlank { "未知原因" }}",
                        mapOf("type" to kind.key, "id" to id, "reason" to attempt.reason)
                    )
                    return@get
                }
                call.response.header(HttpHeaders.ETag, etag)
                call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
                call.respondBytes(bytes, ContentType.Image.JPEG)
            }

            /**
             * 接收客户端抽好的缩略图（raw body，JPEG 字节）。
             *
             * ## 为什么这条接口存在
             * core 跑在随身 WiFi 上，那台机器的定制 ROM **解不出视频画面**（系统缩略图与自行抽帧
             * 都失败）。手机端的解码栈是完整的，所以让手机把帧抽好交上来：
             * 「谁有解码能力谁干活」。交上来之后 [get] 直接命中缓存，Web 端与其它客户端
             * 也就一并有了缩略图 —— 这是选它而不是"只在 app 本地缓存"的唯一理由。
             *
             * ## 这不是"允许客户端随便塞图"
             * 校验四条：type/id 合法、id 在媒体库里真实存在、大小 ≤ [MAX_UPLOADED_THUMB_BYTES]、
             * 必须是 JPEG（魔数 `FF D8 FF`）。加上 `/api` 本身的设备签名鉴权（只有已配对设备能调），
             * 攻击面就是"已配对设备可以给自己库里的某个媒体塞一张 ≤512KB 的 JPEG"——
             * 与它本来就能改文件的权限相比没有放大。
             *
             * 覆盖是允许的（同 id 再传一次就换掉），因为客户端可能抽到更好的一帧。
             */
            put("/thumbnail") {
                val kind = Kind.of(call.request.queryParameters["type"]) ?: run {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "type 必须是 video / audio / image"
                    )
                    return@put
                }
                val id = call.request.queryParameters["id"]?.toLongOrNull() ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "缺少 id")
                    return@put
                }

                val bytes = call.receive<ByteArray>()
                if (bytes.size > MAX_UPLOADED_THUMB_BYTES) {
                    call.respondFail(
                        HttpStatusCode.PayloadTooLarge, ErrorCode.BAD_REQUEST,
                        "缩略图过大（${bytes.size / 1024} KB），上限 ${MAX_UPLOADED_THUMB_BYTES / 1024} KB"
                    )
                    return@put
                }
                val isJpeg = bytes.size > 3 &&
                    bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
                if (!isJpeg) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "只接受 JPEG"
                    )
                    return@put
                }
                // id 必须真的在媒体库里：否则缓存目录会被塞进一堆永远不会被读到的孤儿文件
                val exists = withContext(Dispatchers.IO) {
                    runCatching {
                        appContext.contentResolver.query(
                            ContentUris.withAppendedId(contentUriOf(kind), id),
                            arrayOf(MediaStore.MediaColumns._ID),
                            null, null, null
                        )?.use { it.moveToFirst() } ?: false
                    }.getOrDefault(false)
                }
                if (!exists) {
                    call.respondFail(
                        HttpStatusCode.NotFound, ErrorCode.BAD_REQUEST,
                        "媒体库里没有这一项(${kind.key}/$id)"
                    )
                    return@put
                }

                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val target = thumbCacheFile(kind, id)
                        // 先写临时文件再 rename：中途断开不会留下半张图被当成缓存命中
                        val tmp = File(target.parentFile, "${target.name}.tmp")
                        tmp.writeBytes(bytes)
                        if (target.exists()) target.delete()
                        val ok = tmp.renameTo(target)
                        if (!ok) tmp.delete()
                        ok
                    }.getOrDefault(false)
                }
                if (!saved) {
                    call.respondFail(
                        HttpStatusCode.InternalServerError, ErrorCode.INTERNAL_ERROR, "缩略图写入失败"
                    )
                    return@put
                }
                withContext(Dispatchers.IO) { pruneThumbCache() }
                AppLogger.i(TAG, "已收下客户端缩略图(${kind.key}/$id): ${bytes.size / 1024} KB")
                call.respond(
                    toJsonElement(
                        mapOf("success" to true, "type" to kind.key, "id" to id, "size" to bytes.size)
                    )
                )
            }

            /**
             * 清空**设备侧**的缩略图缓存（2026-09-20）。
             *
             * ## 为什么必须有这条
             * 缩略图一共有三层缓存，缺了这条就有一层永远清不掉：
             *  1. 客户端的图片加载库（磁盘 + 内存，按 URL 命中）；
             *  2. 客户端自己抽帧的成果（App 的 `media-thumbs/`）；
             *  3. **本目录**（`filesDir/thumbs/`，客户端回传的成果）。
             *
             * `/thumbnail` 是"缓存命中就直接返回"，而 URL 只含 (type, id) 不含内容指纹 ——
             * 于是一旦某张图算错了（典型：抽到黑场），它会一直被原样发下去。
             * 之前客户端只能清掉第 2 层，清完再请求又被本层的旧图命中，
             * 表现就是"怎么清都还是那张黑图"。
             *
             * `type` 省略则清全部；给了就只清那一类（换了抽帧策略时通常只需要重算视频）。
             */
            delete("/thumbnail-cache") {
                val typeParam = call.request.queryParameters["type"]?.trim().orEmpty()
                val kind = if (typeParam.isBlank()) null else Kind.of(typeParam)
                if (typeParam.isNotBlank() && kind == null) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "type 只能是 video / audio / image"
                    )
                    return@delete
                }
                val result = withContext(Dispatchers.IO) {
                    val prefix = kind?.let { "${it.key}_" }
                    var removed = 0
                    var freed = 0L
                    thumbCacheDir().listFiles()?.forEach { f ->
                        if (!f.isFile) return@forEach
                        if (prefix != null && !f.name.startsWith(prefix)) return@forEach
                        val len = f.length()
                        if (f.delete()) {
                            removed++
                            freed += len
                        }
                    }
                    removed to freed
                }
                AppLogger.i(
                    TAG,
                    "已清空缩略图缓存(${kind?.key ?: "全部"}): ${result.first} 张, ${result.second / 1024} KB"
                )
                call.respond(
                    toJsonElement(
                        mapOf(
                            "success" to true,
                            "type" to (kind?.key ?: ""),
                            "removed" to result.first,
                            "freed_bytes" to result.second
                        )
                    )
                )
            }

            /**
             * 音频封面（原始内嵌图，取不到时回退到大尺寸缩略图）。
             *
             * 与 `/thumbnail` 分开的理由：列表要的是"小而快"（256px、可缓存、能糊），
             * 播放页要的是"尽量清楚"。内嵌封面（ID3 APIC）本身就是原图字节，直接透传**不重新编码**，
             * 这是能拿到的最高画质；没有内嵌封面时才退回 `loadThumbnail(1024)`。
             */
            get("/cover") {
                val id = call.request.queryParameters["id"]?.toLongOrNull() ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "缺少 id")
                    return@get
                }
                if (!isGranted(Kind.AUDIO)) {
                    call.respondFail(
                        HttpStatusCode.Forbidden, ErrorCode.BAD_REQUEST,
                        "媒体权限未授权：${permissionOf(Kind.AUDIO)}"
                    )
                    return@get
                }
                val etag = "\"media-cover-$id\""
                if (call.request.header(HttpHeaders.IfNoneMatch) == etag) {
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }

                val cover = withContext(Dispatchers.IO) { audioCoverBytes(id) }
                if (cover == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.BAD_REQUEST, "没有封面")
                    return@get
                }
                call.response.header(HttpHeaders.ETag, etag)
                call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
                call.respondBytes(cover.first, cover.second)
            }

            /**
             * 歌词（旁挂 `.lrc` / `.txt`，同名同目录）。
             *
             * **只找旁挂文件**：MediaStore 不索引歌词，`MediaMetadataRetriever` 也没有歌词字段，
             * 想读 ID3 的 USLT 帧就得在 core 里塞一个 ID3 解析器 —— 那是另一件事。
             * 找不到就如实回 `found = false`，让客户端显示"没有歌词"，而不是拿文件名假装一行歌词。
             *
             * 时间轴解析（`[mm:ss.xx]`）留在客户端：那是纯展示格式化，且高亮要跟着播放进度走。
             */
            get("/lyrics") {
                val id = call.request.queryParameters["id"]?.toLongOrNull() ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "缺少 id")
                    return@get
                }
                if (!isGranted(Kind.AUDIO)) {
                    call.respondFail(
                        HttpStatusCode.Forbidden, ErrorCode.BAD_REQUEST,
                        "媒体权限未授权：${permissionOf(Kind.AUDIO)}"
                    )
                    return@get
                }
                val found = withContext(Dispatchers.IO) {
                    val audio = audioPathOf(id) ?: return@withContext null
                    val file = File(audio)
                    val base = file.nameWithoutExtension
                    LYRICS_EXTENSIONS.asSequence()
                        .map { File(file.parentFile, "$base.$it") }
                        .firstOrNull { it.isFile && it.length() in 1..MAX_LYRICS_BYTES }
                        ?.let { it.name to runCatching { it.readText() }.getOrNull() }
                }
                val text = found?.second
                call.respond(
                    toJsonElement(
                        mapOf(
                            "found" to (text != null),
                            "source" to (found?.first ?: ""),
                            "text" to (text ?: "")
                        )
                    )
                )
            }

            /**
             * 某个视频可用的**外挂字幕列表**（2026-09-19）。
             *
             * ## 为什么必须由 core 来列
             * `/list` 与 `/browse` 的数据来自 MediaStore 的视频集合，而 `.srt` 不是视频，
             * **永远不会出现在那个集合里** —— 客户端靠媒体库接口发现不了字幕。
             * 而 `/files/list` 虽然能看见真实目录条目，却要客户端自己把"哪个字幕属于哪个视频"
             * 的匹配规则实现一遍（还得在两个播放入口各写一份）。判定放在 core，客户端只渲染。
             *
             * ## 两种取法
             *  · `scope=matched`（默认）：只回**按文件名判定属于这个视频**的（见 [subtitleBelongsTo]），
             *    给"打开就自动挂上字幕"用；
             *  · `scope=folder`：回同目录下**所有**字幕文件，给"手动选字幕文件"的列表用 ——
             *    现实里字幕名和视频名经常对不上（压制组命名、单独下载的字幕包）。
             *
             * `supported = false` 的条目照样返回（MicroDVD `.sub`、SAMI `.smi`）：
             * 让客户端显示"格式不支持"，而不是让用户对着目录里明明存在的文件怀疑 App 瞎了。
             */
            get("/subtitles") {
                val videoPath = call.request.queryParameters["path"]
                if (!isGranted(Kind.VIDEO)) {
                    call.respondFail(
                        HttpStatusCode.Forbidden, ErrorCode.BAD_REQUEST,
                        "媒体权限未授权：${permissionOf(Kind.VIDEO)}"
                    )
                    return@get
                }
                // 远端存储源（`remote:<id>/…`）：回**空列表**而不是 400。
                // 2026-09-21：播放器打开任何视频都会先探一次字幕，远端路径过不了
                // `safeUserFile`（它只认本机用户存储），于是每次播远端视频都在日志里
                // 留一条 `HTTP 400 path 必须是用户存储内的真实文件` —— 那看起来像是
                // 播放失败的原因，实际只是探测本身不适用，真正的失败在别处。
                // 远端字幕要能用，得先有"读远端字幕内容"的链路（`/media/subtitle` 同样只认本地），
                // 那是另一件事；在它到位之前，诚实地回"没有可用字幕"。
                if (videoPath?.startsWith("remote:") == true) {
                    call.respond(
                        toJsonElement(
                            mapOf(
                                "path" to videoPath,
                                "scope" to "matched",
                                "items" to emptyList<Any>(),
                                "remote_unsupported" to true
                            )
                        )
                    )
                    return@get
                }
                val video = safeUserFile(videoPath) ?: run {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "path 必须是用户存储内的真实文件"
                    )
                    return@get
                }
                val folderScope = call.request.queryParameters["scope"]
                    .equals("folder", ignoreCase = true)

                val items = withContext(Dispatchers.IO) {
                    val base = video.nameWithoutExtension
                    val dir = video.parentFile
                    subtitleNamesIn(dir, mutableMapOf())
                        .asSequence()
                        .filter { folderScope || subtitleBelongsTo(it, base) }
                        .mapNotNull { name -> File(dir, name).takeIf { it.isFile } }
                        // 排序三档，顺序不能换：
                        //  1. **能解析的优先** —— 客户端"打开就自动挂第一条"，如果让
                        //     `movie.sub`（MicroDVD，supported=false）靠同名排到第一，
                        //     自动挂上的就是一条放不出来的字幕，用户只会以为字幕功能坏了；
                        //  2. 同名优先 —— `movie.srt` 比 `movie.zh.srt` 更贴切；
                        //  3. 名字字典序 —— 让同批次多语言字幕的顺序稳定。
                        .sortedWith(
                            compareByDescending<File> { subtitlePlayable(it) }
                                .thenByDescending { it.nameWithoutExtension.equals(base, true) }
                                .thenBy { it.name.lowercase(Locale.ROOT) }
                        )
                        .take(MAX_SUBTITLE_ENTRIES)
                        .map { subtitleEntryOf(it, base) }
                        .toList()
                }
                call.respond(
                    toJsonElement(
                        mapOf(
                            "path" to video.absolutePath,
                            "scope" to (if (folderScope) "folder" else "matched"),
                            "items" to items
                        )
                    )
                )
            }

            /**
             * 字幕文件内容，**统一转成 UTF-8** 后原样输出（2026-09-19）。
             *
             * ## 为什么不让客户端直接拉 `/files/stream`
             * 那样拿到的是原始字节，而 media3 的字幕解析器按 UTF-8 解 ——
             * 中文字幕大量是 GB18030/Big5，结果就是一屏乱码，且播放器没有"换编码重试"的入口。
             * 这条端点把编码探测与转码收在服务端（见 [decodeSubtitleText]），
             * 客户端把本 URL 直接塞进播放器的字幕轨配置即可，永远只会见到 UTF-8。
             *
             * Content-Type 按后缀给准确的字幕 MIME（播放器靠它选解析器），
             * 并显式带 `charset=utf-8` —— 这是本端点存在的全部意义，不能省。
             *
             * 缓存：字幕文件内容只随文件本身变，给一天的 `max-age`；
             * 不做 ETag —— 字幕就几十 KB，省的那点带宽不值得多一轮条件请求。
             */
            get("/subtitle") {
                if (!isGranted(Kind.VIDEO)) {
                    call.respondFail(
                        HttpStatusCode.Forbidden, ErrorCode.BAD_REQUEST,
                        "媒体权限未授权：${permissionOf(Kind.VIDEO)}"
                    )
                    return@get
                }
                val file = safeUserFile(call.request.queryParameters["path"]) ?: run {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "path 必须是用户存储内的真实文件"
                    )
                    return@get
                }
                val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val mime = PLAYABLE_SUBTITLE_MIMES[ext] ?: run {
                    call.respondFail(
                        HttpStatusCode.UnsupportedMediaType, ErrorCode.BAD_REQUEST,
                        "不支持的字幕格式：.$ext"
                    )
                    return@get
                }
                if (file.length() !in 1..MAX_SUBTITLE_BYTES) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "字幕文件大小异常（${file.length()} 字节，上限 ${MAX_SUBTITLE_BYTES / 1024 / 1024}MB）"
                    )
                    return@get
                }
                val text = withContext(Dispatchers.IO) {
                    runCatching { decodeSubtitleText(file) }.getOrNull()
                }
                if (text == null) {
                    call.respondFail(
                        HttpStatusCode.InternalServerError, ErrorCode.INTERNAL_ERROR,
                        "字幕读取失败"
                    )
                    return@get
                }
                call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
                call.respondText(
                    text = text,
                    contentType = ContentType.parse(mime).withCharset(Charsets.UTF_8)
                )
            }

            /**
             * 单首音频的**标签信息**（曲名 / 艺术家 / 专辑 / 时长）。
             *
             * ## 为什么要有这一条
             * `/list` 已经带了 MediaStore 的 `title/artist/album`（系统扫描时解析的），绝大多数
             * 文件够用。但 MediaStore 偶尔会漏（刚拷进来还没扫、或某些 FLAC/APE 只填了部分列），
             * 这时客户端只能退回文件名。本接口在**服务端**直接对文件本身取一次标签，
             * 让客户端"进页面就能显示正确的歌名"，而不是等播放器解出容器元数据才更新。
             *
             * 用 [MediaMetadataRetriever]：它只读容器头部的标签，不需要音频解码能力
             * （本机 ROM 解不出视频画面，但读标签没问题；真失败就回落 MediaStore 那份）。
             * 歌词不在这里 —— 歌词有独立的 `/lyrics`（旁挂文件），两件事不要混。
             */
            get("/tags") {
                val id = call.request.queryParameters["id"]?.toLongOrNull() ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "缺少 id")
                    return@get
                }
                if (!isGranted(Kind.AUDIO)) {
                    call.respondFail(
                        HttpStatusCode.Forbidden, ErrorCode.BAD_REQUEST,
                        "媒体权限未授权：${permissionOf(Kind.AUDIO)}"
                    )
                    return@get
                }
                val tags = withContext(Dispatchers.IO) { audioTagsOf(id) }
                if (tags == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.BAD_REQUEST, "没有这首音频")
                    return@get
                }
                // 同一 id 的标签只随文件改动而变，可以放心让客户端缓存一天
                call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
                call.respond(toJsonElement(tags))
            }

            /** 读某一类的扫描目录配置。空数组 = 这一类不限目录（列整个媒体库里的该类型）。 */
            get("/config") {
                val kind = Kind.of(call.request.queryParameters["type"]) ?: run {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "type 必须是 video / audio / image"
                    )
                    return@get
                }
                call.respond(
                    toJsonElement(mapOf("type" to kind.key, "dirs" to scanDirs(kind)))
                )
            }

            /**
             * 写某一类的扫描目录配置。
             *
             * 校验三条：必须在用户存储白名单内（与文件接口同一口径）、去重、不超过
             * [MAX_SCAN_DIRS] 条。**不要求目录当前存在** —— SD 卡拔出时配置不该被清掉。
             *
             * 按类型分开写（2026-09-16）：视频页改视频的、音乐页改音乐的，互不覆盖；
             * 三类指向同一个目录也没问题，MediaStore 本来就是分表查的。
             */
            put("/config") {
                val kind = Kind.of(call.request.queryParameters["type"]) ?: run {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "type 必须是 video / audio / image"
                    )
                    return@put
                }
                val body = call.receiveJsonObject()
                val raw = body["dirs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                if (raw == null) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "缺少 dirs 数组")
                    return@put
                }
                val cleaned = raw.map { it.trim().trimEnd('/') }
                    .filter { it.isNotBlank() }
                    .distinct()
                val illegal = cleaned.filterNot { dir ->
                    ALLOWED_DIR_PREFIXES.any { dir.startsWith(it) } &&
                        !dir.contains("..")
                }
                if (illegal.isNotEmpty()) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "目录不在用户存储范围内: ${illegal.first()}"
                    )
                    return@put
                }
                if (cleaned.size > MAX_SCAN_DIRS) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "扫描目录最多 $MAX_SCAN_DIRS 个"
                    )
                    return@put
                }
                settings.setMediaScanDirsJson(
                    kind.key,
                    buildJsonArray {
                        cleaned.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    }.toString()
                )
                AppLogger.i(TAG, "媒体扫描目录已更新(${kind.key}): ${cleaned.size} 个")
                call.respond(
                    toJsonElement(
                        mapOf("success" to true, "type" to kind.key, "dirs" to cleaned)
                    )
                )
            }

            /**
             * 请系统**重新收录**某一类的目录（默认用该类型已配置的目录）。
             *
             * 这是给"媒体库漏收"兜底的：`.nomedia` 目录、刚 adb push 进来的文件，MediaStore 里
             * 查不到。这里不自建索引，只把文件路径交给系统扫描器（`MediaScannerConnection`），
             * 收录完成后照常走 `/list`。
             *
             * 只提交**本类型**的文件（2026-09-16）：在视频页点重扫就该只重扫视频，
             * 顺手把同目录下的音乐图片也塞给扫描器会让这颗按钮的语义变成"整卡重扫"。
             *
             * 提交量有上限（[MAX_RESCAN_FILES] / 深度 [MAX_RESCAN_DEPTH]）——
             * 把整卡塞给扫描器会让系统忙上几分钟。
             */
            post("/rescan") {
                val kind = Kind.of(call.request.queryParameters["type"]) ?: run {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "type 必须是 video / audio / image"
                    )
                    return@post
                }
                val body = call.receiveJsonObject()
                val requested = body["dirs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                /*
                 * 目录来源三级回退：请求体 → 该类型已配置目录 → 主外置存储根。
                 *
                 * 第三级是 2026-09-16 补的：`/list` 的口径是「未配置目录 = 整个媒体库」，
                 * 而 rescan 原来在这种情况下直接回 400「请先选择目录」——于是默认状态下
                 * 「重新扫描」这颗按钮点下去必然报错，等于一颗假按钮。既然列表不限目录，
                 * 重扫的默认范围就该是整卡。
                 */
                val configured = requested?.takeIf { it.isNotEmpty() } ?: scanDirs(kind)
                val candidates = configured.ifEmpty {
                    listOfNotNull(
                        runCatching { Environment.getExternalStorageDirectory()?.absolutePath }
                            .getOrNull()
                    )
                }
                val dirs = candidates
                    .map { it.trimEnd('/') }
                    .filter { dir -> ALLOWED_DIR_PREFIXES.any { dir.startsWith(it) } && !dir.contains("..") }
                if (dirs.isEmpty()) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "没有可扫描的目录：外置存储不可用，请在本页指定目录"
                    )
                    return@post
                }

                val mimePrefix = when (kind) {
                    Kind.VIDEO -> "video/"
                    Kind.AUDIO -> "audio/"
                    Kind.IMAGE -> "image/"
                }
                val paths = withContext(Dispatchers.IO) {
                    val collected = mutableListOf<String>()
                    for (dir in dirs) {
                        val root = File(dir)
                        if (!root.isDirectory) continue
                        for (f in root.walkTopDown().maxDepth(MAX_RESCAN_DEPTH)) {
                            if (collected.size >= MAX_RESCAN_FILES) break
                            if (!f.isFile) continue
                            if (MimeTypes.fromFileName(f.name).startsWith(mimePrefix)) {
                                collected += f.absolutePath
                            }
                        }
                        if (collected.size >= MAX_RESCAN_FILES) break
                    }
                    collected
                }

                if (paths.isNotEmpty()) {
                    // 异步提交：扫描器自己排队收录，这里不等它完成（可能几十秒）
                    MediaScannerConnection.scanFile(appContext, paths.toTypedArray(), null, null)
                }
                AppLogger.i(
                    TAG,
                    "已提交媒体扫描(${kind.key}): ${paths.size} 个文件, ${dirs.size} 个目录"
                )
                call.respond(
                    toJsonElement(
                        mapOf(
                            "success" to true,
                            "type" to kind.key,
                            "dirs" to dirs,
                            "submitted" to paths.size,
                            "truncated" to (paths.size >= MAX_RESCAN_FILES)
                        )
                    )
                )
            }
        }
    }
}
