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
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.BinaryComponentStore
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
    private val settings: AppSettings
) {

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
         * 自行抽帧时取的时间点（1 秒）。
         *
         * 不取 0：很多视频首帧是黑场或渐入，抽出来是一张纯黑图 —— 那和没有缩略图没区别。
         * 比视频还短时 `getFrameAtTime` 会退到最近的关键帧，不会失败。
         */
        private const val VIDEO_FRAME_POSITION_US = 1_000_000L

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


        /** 允许作为扫描目录的前缀：与 FileRoutes 的用户存储白名单同一口径。 */
        private val ALLOWED_DIR_PREFIXES = listOf("/storage/", "/sdcard", "/mnt/media_rw/")
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
    private fun itemOf(c: Cursor, kind: Kind): Map<String, Any?> {
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
     * 缩略图字节（JPEG）。三级：系统缩略图 → MediaMetadataRetriever → **ffmpeg-kit 软解**。
     *
     * 2026-09-16 加第二级，2026-09-19 加第三级。原来只调 `contentResolver.loadThumbnail()`，
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
     * 第二级不依赖 MediaProvider 但仍依赖系统 codec；**第三级（仅视频）用 ffmpeg 自带的
     * 软解码器**，完全绕开系统 codec 栈，这是在本机唯一能出图的路。三级都失败才回 404，
     * 此时由手机端抽帧回传（`PUT /thumbnail`）兜底。
     *
     * 三级都慢（读文件 + 软解一帧），但**只在前一级失败时才走**，
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

        // 第三级（仅视频）：ffmpeg-kit 软解抽帧。
        // 系统 API 和 MMR 都拿不到画面时，靠 ffmpeg 的内建软解码器绕过 ROM 的缺陷。
        if (kind == Kind.VIDEO) {
            val path = runCatching {
                appContext.contentResolver.query(
                    uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) c.stringOr(MediaStore.MediaColumns.DATA) else null
                }
            }.getOrNull()
            if (!path.isNullOrBlank()) {
                val ffResult = ffmpegFrameThumbnail(path, size)
                ffResult.getOrNull()?.let { return ThumbnailAttempt(it, "") }
                val ffReason = ffResult.exceptionOrNull()
                    ?.let { "ffmpeg-kit ${it.javaClass.simpleName}: ${it.message}" }
                    ?: "ffmpeg-kit 返回空"
                val reason = listOfNotNull(systemReason, generatedReason, ffReason).joinToString(" / ")
                AppLogger.w(TAG, "缩略图三级全部失败(${kind.key}/$id): $reason")
                return ThumbnailAttempt(null, reason)
            }
        }

        val reason = listOfNotNull(systemReason, generatedReason).joinToString(" / ")
        AppLogger.w(TAG, "缩略图生成失败(${kind.key}/$id): $reason")
        return ThumbnailAttempt(null, reason)
    }

    /** 视频：抽第 1 秒附近的关键帧（首帧常是黑场），再按 [size] 等比缩小。 */
    private fun videoFrameThumbnail(uri: android.net.Uri, size: Int): Result<ByteArray?> = runCatching {
        appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(pfd.fileDescriptor)
                val frame = retriever.getFrameAtTime(
                    VIDEO_FRAME_POSITION_US,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: retriever.frameAtTime
                frame?.scaledDown(size)?.toJpegBytes(THUMB_JPEG_QUALITY)
            } finally {
                retriever.release()
            }
        }
    }

    /**
     * 视频抽帧第三级：用 ffmpeg-kit 软解一帧。
     *
     * 2026-09-19 新增。展锐 VPU 虽然在 `/vendor/lib/modules/` 里，但 ROM 层的
     * `MediaMetadataRetriever` 桥接不到它（`getFrameAtTime` 返回 null），ffmpeg 的纯软解码
     * 不依赖系统 codec 栈，所以成了最后一条能出图的路。
     *
     * 思路：`ffmpeg -ss 1 -i <path> -frames:v 1 -q:v 2 <output.jpg>` ——
     * 同步执行，抽一帧就停，输出 JPEG 文件再读进来。
     *
     * 放在 [thumbnailBytes] 链路里，位置在系统 API 和 MMR 之后：
     * 系统 API → MMR → **ffmpeg-kit** → 404（让手机端抽帧回传）。
     *
     * .so 加载策略（插件化后）：优先从 `filesDir/components/ffmpeg/` 按绝对路径
     * `System.load()`，不再依赖 `System.loadLibrary()`。
     */
    private fun ffmpegFrameThumbnail(path: String, size: Int): Result<ByteArray?> = runCatching {
        // 先确保 native 库已加载（插件式：从组件目录按绝对路径加载）
        if (!ensureFfmpegLoaded()) {
            AppLogger.d(TAG, "FFmpegKit 不可用（.so 未安装或加载失败），跳过 ffmpeg 抽帧")
            return@runCatching null
        }

        // 守卫：反射确认 Java 类存在（compileOnly 在编译期可见，运行时靠 classes.jar 或反射）
        val kitClass = try {
            Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
        } catch (e: ClassNotFoundException) {
            AppLogger.d(TAG, "FFmpegKit 不可用（类未加载），跳过 ffmpeg 抽帧")
            return@runCatching null
        }

        val output = File(thumbCacheDir(), "ffkit_tmp_${System.nanoTime()}.jpg")
        try {
            // -y 覆盖输出; -ss 1 seek 到 1s; -frames:v 1 只取一帧; -vf scale 按最长边缩放
            val cmd = "-y -ss 1 -i \"$path\" -frames:v 1 " +
                "-vf \"scale='if(gt(iw,ih),$size,-2)':'if(gt(iw,ih),-2,$size)'\" " +
                "-q:v 2 \"${output.absolutePath}\""

            AppLogger.i(TAG, "ffmpeg-kit 抽帧: $cmd")

            // FFmpegKit.execute(String) 返回 FFmpegSession
            val executeMethod = kitClass.getMethod("execute", String::class.java)
            val session = executeMethod.invoke(null, cmd)

            // session.getReturnCode().isValueSuccess()
            val getReturnCode = session.javaClass.getMethod("getReturnCode")
            val returnCode = getReturnCode.invoke(session)
            val isSuccess = returnCode?.javaClass?.getMethod("isValueSuccess")?.invoke(returnCode) as? Boolean ?: false

            if (!isSuccess) {
                // 取日志看看怎么失败的
                val getAllLogs = session.javaClass.getMethod("getAllLogsAsString")
                val logs = (getAllLogs.invoke(session) as? String)?.takeLast(500) ?: "无日志"
                AppLogger.w(TAG, "ffmpeg-kit 抽帧失败(rc=$returnCode): $logs")
                return@runCatching null
            }

            if (!output.isFile || output.length() <= 0) {
                AppLogger.w(TAG, "ffmpeg-kit 抽帧命令成功但输出文件为空")
                return@runCatching null
            }

            output.readBytes()
        } finally {
            output.delete()
        }
    }

    // ── ffmpeg-kit .so 加载（插件式：从 filesDir/components/ffmpeg/ 按绝对路径加载）──

    /** 依赖顺序：前面的库是后面的前提 */
    private val FFMPEG_SO_LOAD_ORDER = listOf(
        "libavutil.so", "libswresample.so", "libavcodec.so", "libavformat.so",
        "libswscale.so", "libavfilter.so", "libavdevice.so",
        "libffmpegkit_abidetect.so", "libffmpegkit.so"
    )

    @Volatile
    private var ffmpegSoLoaded = false

    /** 尝试从组件目录加载所有 .so，成功返回 true。已加载过直接返回缓存结果。 */
    @Synchronized
    private fun ensureFfmpegLoaded(): Boolean {
        if (ffmpegSoLoaded) return true
        val soDir = File(appContext.filesDir, "components/${BinaryComponentStore.ID_FFMPEG}")
        if (!soDir.isDirectory) return false
        try {
            for (soName in FFMPEG_SO_LOAD_ORDER) {
                val soFile = File(soDir, soName)
                if (!soFile.exists()) {
                    AppLogger.w(TAG, "ffmpeg .so 缺失: $soName")
                    return false
                }
                System.load(soFile.absolutePath)
            }
            ffmpegSoLoaded = true
            AppLogger.i(TAG, "ffmpeg-kit .so 全部加载成功（从 ${soDir.absolutePath}）")
            return true
        } catch (e: Throwable) {
            AppLogger.w(TAG, "ffmpeg .so 加载失败: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
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
                val (selection, selectionArgs) = dirSelection(scanDirs(kind))

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
                        resolver.query(uri, projectionOf(kind), args, null)?.use { c ->
                            while (c.moveToNext()) {
                                items += itemOf(c, kind)
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
                        resolver.query(uri, projectionOf(kind), args, null)?.use { c ->
                            while (c.moveToNext()) items += itemOf(c, kind)
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
             * ffmpeg-kit 自检（2026-09-19）。
             *
             * 存在的理由：设备端日志要连 ADB 才看得到，而"缩略图出不来"在客户端只表现为一个
             * 占位图标。这个端点把三件事一次说清：库有没有打进来、ffmpeg 能不能跑、
             * 对某个具体文件抽帧要多久。
             *
             * 带 `path` 参数时真的抽一帧并计时（不落盘、不写缓存），只回报结果与耗时；
             * 不带就只回库的可用性与版本。
             */
            get("/ffmpeg-status") {
                val soDir = File(appContext.filesDir, "components/${BinaryComponentStore.ID_FFMPEG}")
                val soInstalled = soDir.isDirectory && (soDir.listFiles()?.isNotEmpty() == true)

                // 先尝试加载 .so
                val loaded = ensureFfmpegLoaded()

                val kitAvailable = loaded && runCatching {
                    Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
                    true
                }.getOrDefault(false)

                if (!soInstalled) {
                    call.respond(
                        toJsonElement(
                            mapOf(
                                "available" to false,
                                "reason" to "FFmpeg 组件未安装（请在「可选组件」页面安装）"
                            )
                        )
                    )
                    return@get
                }

                if (!kitAvailable) {
                    call.respond(
                        toJsonElement(
                            mapOf(
                                "available" to false,
                                "reason" to if (!loaded) "FFmpeg .so 加载失败" else "FFmpegKit 类不在 classpath"
                            )
                        )
                    )
                    return@get
                }

                val path = call.request.queryParameters["path"]?.trim()?.takeIf { it.isNotBlank() }

                withContext(Dispatchers.IO) {
                    // .so 已经由 ensureFfmpegLoaded 从组件目录加载完毕，逐个报告状态
                    var version = ""
                    var versionOk = false
                    val loadReport = StringBuilder()
                    for (soName in FFMPEG_SO_LOAD_ORDER) {
                        val soFile = File(soDir, soName)
                        if (soFile.exists()) {
                            loadReport.appendLine("  ${soName.removeSuffix(".so").removePrefix("lib")}: OK (${soFile.length()} bytes)")
                        } else {
                            loadReport.appendLine("  ${soName.removeSuffix(".so").removePrefix("lib")}: MISSING")
                        }
                    }

                    // 调 FFmpegKit.execute("-version") 取版本
                    runCatching {
                        val kitClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
                        val session = kitClass.getMethod("execute", String::class.java)
                            .invoke(null, "-version")
                        val rc = session.javaClass.getMethod("getReturnCode").invoke(session)
                        versionOk = rc?.javaClass?.getMethod("isValueSuccess")
                            ?.invoke(rc) as? Boolean ?: false
                        version = (session.javaClass.getMethod("getAllLogsAsString")
                            .invoke(session) as? String)
                            ?.lineSequence()
                            ?.firstOrNull { it.contains("ffmpeg version", ignoreCase = true) }
                            ?.trim()
                            .orEmpty()
                    }.onFailure { e ->
                        val real = if (e is java.lang.reflect.InvocationTargetException) {
                            e.targetException ?: e.cause ?: e
                        } else {
                            e
                        }
                        version = "execute 失败: ${real.javaClass.simpleName}: ${real.message}"
                        if (real is ExceptionInInitializerError) {
                            real.cause?.let { root ->
                                version += " → ${root.javaClass.simpleName}: ${root.message}"
                            }
                        }
                    }

                    val result = mutableMapOf<String, Any?>(
                        "available" to true,
                        "native_ok" to versionOk,
                        "version" to version,
                        "load_report" to loadReport.toString().trim(),
                        "so_dir" to soDir.absolutePath
                    )

                    if (path != null) {
                        val started = System.currentTimeMillis()
                        val attempt = ffmpegFrameThumbnail(path, DEFAULT_THUMB_SIZE)
                        val elapsed = System.currentTimeMillis() - started
                        result["probe_path"] = path
                        result["probe_elapsed_ms"] = elapsed
                        result["probe_ok"] = attempt.getOrNull() != null
                        result["probe_bytes"] = attempt.getOrNull()?.size ?: 0
                        attempt.exceptionOrNull()?.let {
                            result["probe_error"] = "${it.javaClass.simpleName}: ${it.message}"
                        }
                    }

                    call.respond(toJsonElement(result))
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
