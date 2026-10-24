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
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.media.AudioTagReader
import com.ufi_axis_core.contract.ErrorCode
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
import kotlinx.coroutines.sync.withPermit
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
    private val cache: ResponseCache,
    /**
     * 「从音乐库移除」的排除名单。可空 + 默认 null：老的组装代码不传它也能编过，
     * 传 null 就是"没有任何排除"（[filterListItems] 里 `orEmpty()` 兜住）。
     */
    private val exclusions: com.ufi_axis_core.api.media.MediaExclusionStore? = null,
    /**
     * 2026-10-07：手动预热钩子（ComponentFactory 注入 `ThumbPrewarmWorker.runOnce(forceIdle=true)`，
     * 广播闭包与调度侧同一份）。null = 老装配（无此能力）时端点回 501。
     */
    private val manualPrewarm: (suspend () -> Unit)? = null,
    /**
     * 媒体索引缓存（2026-10-13）。装配层（`ComponentFactory`）注入同一个实例，
     * 这样 `BackendService` 能按服务生命周期起停它的定时刷新。
     *
     * 不传（单测 / 老装配）时就地建一个功能等价的实例：缓存与单飞语义完全一致，
     * 只是没有定时刷新 —— 读路径靠 TTL 与冷启动同步拉取照样正确。
     */
    indexStore: com.ufi_axis_core.api.media.MediaIndexStore? = null
) : com.ufi_axis_core.api.media.AudioItemLookup {

    /** 索引缓存本体。见 [indexStore] 参数说明。 */
    private val index: com.ufi_axis_core.api.media.MediaIndexStore =
        indexStore ?: com.ufi_axis_core.api.media.MediaIndexStore()

    init {
        // 把「怎么查 MediaStore」注册进缓存：索引层不认识 Kind / projection / 游标，
        // 那些知识全部留在本文件（见 MediaIndexStore.attachLoader 的说明）。
        index.attachLoader(KIND_KEYS) { key ->
            val kind = Kind.of(key)
            if (kind == null) null else loadKindUncached(kind)
        }
    }

    /*
     * 这里刻意**不**再暴露 startPeriodicRefresh / invalidate / stats 之类的转发方法：
     * 装配层（ComponentFactory）拿到的是同一个 MediaIndexStore 实例，已经直接把它给了
     * ServerGraph（生命周期起停）与 FileRoutes（删除后失效），多一层转发只是多一条
     * "两处都能改同一个东西"的路径。MediaRoutes 只负责**注册 loader**与**读快照**。
     */

    companion object {
        private const val TAG = "MediaRoutes"

        /**
         * 索引缓存要拉取的 kind 键。与 [Kind.key] 一一对应，顺序即拉取顺序。
         *
         * 放这里而不是让 `MediaIndexStore` 自己写死：三个 key 属于媒体中心的对外契约
         *（`?type=video|audio|image`），权威在本文件。
         */
        private val KIND_KEYS = listOf("video", "audio", "image")

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

        /*
         * 2026-10-13 删掉了 `VIDEO_FRAME_RATIOS` / `VIDEO_FRAME_FALLBACK_US` /
         * `DARK_LUMA_THRESHOLD` / `LUMA_SAMPLE_GRID`：它们是 MMR 自行抽帧那条路的
         * "候选位置 + 黑帧判定"参数，随 `videoFrameThumbnail` 一起删除。
         * 黑帧/全零帧的判定并没有丢 —— ffmpeg 的输出仍然过 `FrameValidator`（G3 四判据）。
         */

        /**
         * 缩略图缓存目录（`filesDir` 下）。
         *
         * 加这一层的理由（2026-09-16，2026-10-13 更新）：本机（随身 WiFi 的定制 ROM）
         * **解不出视频画面** —— `MediaProvider` 与 `MediaMetadataRetriever` 走同一套 codec，
         * 双双失败（实测 `FileNotFoundException: Failed to create thumbnail` + 抽帧返回 null 帧）。
         * 所以封面必须**算一次就存住**：core 侧 ffmpeg 抽帧是秒级开销，
         * 客户端回传（历史路径）也是别人的算力成果，都不该被重复支付。
         * 存住之后 Web 端与第二台手机也能直接拿到。
         *
         * 放 `filesDir` 而不是 `cacheDir`：这是算出来的结果，被系统清掉就得再算一次
         * （还要再读一遍视频头部）。容量由 [THUMB_CACHE_LIMIT_BYTES] 自己管。
         */
        private const val THUMB_CACHE_DIR = "thumbs"

        /** 缓存总量上限，超了按修改时间从旧到新删（不是 LRU：读命中不 touch，否则 ETag 会跟着变）。 */
        private const val THUMB_CACHE_LIMIT_BYTES = 64L * 1024 * 1024

        /**
         * 封面取不到时的失败冷却（6h）。
         *
         * 与 `ThumbPrewarmWorker.FAIL_COOLDOWN_MS` 取同一个值：都是"这条确实出不了图"的
         * 同一类判据，口径一致才好解释。没有它，一张无封面的歌每次列表刷新都会重跑
         * MMR + 解码 —— 那是"滑动时解码风暴"的另一个来源。
         */
        private const val COVER_FAIL_COOLDOWN_MS = 6L * 60 * 60 * 1000

        /*
         * 2026-10-13 删掉了 `thumbSystemSemaphore`（对 MediaProvider 调用的闸门）。
         * 它保护的唯一对象是 `loadThumbnail`，而那个调用已整体退役。
         * 解码闸门没有消失，只是换了语义并搬到 `MediaDecodeGate`：
         * heavy = ffmpeg 抽帧，light = 图片降采样 / 封面字节读取。
         */

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

        /**
         * 索引缓存单个 kind 的条目上限（2026-10-13）。
         *
         * 这是一个**内存保护**，不是产品语义：整库常驻内存是本批次刻意的取舍
         *（见 `MediaIndexStore` 类 KDoc），代价必须有个封顶。30000 条 × 约 400B ≈ 12MB/类，
         * 三类最坏约 36MB —— 在 1.5GB 的设备上可接受，而"单类超过 3 万个媒体文件"
         * 对这台随身 WiFi 来说已经是极反常的库。
         *
         * 触顶时**记 WARN 并截断**（而不是抛错或静默）：截断会让列表少一部分内容，
         * 所以日志必须能看出这件事发生过，排查时才知道"少了"是这里造成的。
         */
        private const val MAX_INDEX_ITEMS_PER_KIND = 30_000
        /**
         * 单次 `MediaScannerConnection.scanFile` 的路径数上限（2026-10-12）。
         *
         * 5000 个绝对路径一次性跨进程传递会把系统媒体进程顶到 500MB —— 接收端要把整批
         * 读进内存做归类与聚合。200 个约 12KB，Binder 侧安全，接收端峰值内存也可控。
         */
        private const val RESCAN_CHUNK_SIZE = 200
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
        private val ALLOWED_DIR_PREFIXES = listOf("/storage", "/sdcard", "/mnt/media_rw")

        /**
         * 前缀判定必须分隔符敏感：裸 `startsWith("/sdcard")` 会放过 `/sdcardXYZ/…` 这类同前缀旁路目录。
         */
        private fun isAllowedDir(abs: String): Boolean =
            ALLOWED_DIR_PREFIXES.any { abs == it || abs.startsWith("$it/") }

        // ─────────────────── 音频分组聚合（/groups，2026-09-20） ───────────────────

        /*
         * 2026-10-13 删掉了 `GROUPS_CACHE_PREFIX = "media:groups"`：
         * `/groups` 不再走 ResponseCache（改为读 [index] 索引快照 + 内存聚合），
         * 那个 key 已经没有写入点，留着只会让人以为还存在一层缓存。
         */

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

        /*
         * 2026-10-13 删掉了 `GROUPS_PROJECTION`（原来给 `/groups` 用的"只取四列"窄投影）。
         * 分组现在读索引快照，而快照是按 [projectionOf] 取的完整条目 —— 保留那份窄投影
         * 只会留下"两个投影必须保持一致"的隐患，而它已经没有任何调用点了。
         */
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

    /** G5：探测结果扁平化响应（审查修复时从主路径抽出复用）。 */
    private suspend fun respondVideoInfo(
        call: io.ktor.server.application.ApplicationCall,
        id: Long,
        obj: kotlinx.serialization.json.JsonObject,
        source: String
    ) {
        fun d(k: String): Double =
            (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
        fun l(k: String): Long =
            (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        fun s(k: String): String =
            (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        call.respond(
            toJsonElement(
                mapOf(
                    "id" to id,
                    "duration_ms" to (d("duration_s") * 1000).toLong(),
                    "width" to l("width"),
                    "height" to l("height"),
                    "codec" to s("codec"),
                    "pix_fmt" to s("pix_fmt"),
                    "bit_rate" to l("bit_rate"),
                    "fps_num" to l("fps_num"),
                    "fps_den" to l("fps_den"),
                    "source" to source,
                )
            )
        )
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
     * 查询串里的可选过滤值。空白 = 没传。
     *
     * 前端清空一个输入框 / 退出专辑详情时，参数往往还挂在 URL 上但值是空串。
     * 把空串当成"筛一个名字为空的专辑"会得到一个永远空的列表，那看起来就是接口坏了。
     */
    private fun filterParam(raw: String?): String? = raw?.trim()?.takeIf { it.isNotBlank() }

    /*
     * 2026-10-13 删掉了 `dirSelection` 与 `listSelection`（原来把扫描目录 / album / artist /
     * dir / 排除名单拼成 MediaStore selection 的那两个函数）。
     *
     * 列表与分组现在读 [index] 内存快照，过滤全在内存做，所以这两个函数**没有任何调用点**了。
     * 保留它们只会留下两条必须保持一致的过滤实现（SQL 一份、内存一份），而其中一份永远不会
     * 被测试覆盖到 —— 那正是最容易长出"列表和分组看到的范围不一样"这类 bug 的地方。
     *
     * 语义搬到了 [filterListItems]（/list）与 `audioGroupsPayload`（/groups），
     * 两处都逐条注明了对应的是原来哪一句 SQL。**排除名单那个 300 条上限的由来**
     *（SQLite 绑定变量 999，超了直接抛异常）记在 `MediaExclusionStore` 的类 KDoc 里，
     * 那条约束在内存过滤路径上已不存在，但上限本身作为产品约束仍然保留。
     */

    /**
     * 排序键（2026-10-13 改为内存比较器）。
     *
     * 改造前这里返回 MediaStore 的**列名**交给 SQL 排（`sortColumnOf`）。现在数据在内存快照里，
     * 排序也在内存里做，所以直接给出比较器。刻意不再保留"列名"这一层中间表示：
     * 那样 SQL 与内存两条路会对同一个 `sort` 参数各解释一遍，迟早不一致。
     *
     * 取值与改造前的列映射一一对应：`name` → DISPLAY_NAME、`size` → SIZE、其它 → DATE_MODIFIED。
     * 字符串比较用 Kotlin 默认的码元序，与 SQLite 默认的 BINARY 排序对 ASCII 是一致的。
     */
    private fun listComparator(sort: String?): Comparator<Map<String, Any?>> = when (sort) {
        "name" -> compareBy { (it["name"] as? String) ?: "" }
        "size" -> compareBy { (it["size"] as? Long) ?: 0L }
        else -> compareBy { (it["date_modified"] as? Long) ?: 0L }
    }

    /** 按 [sort] 排序；[desc] = 倒序。 */
    private fun sortListItems(
        items: List<Map<String, Any?>>,
        sort: String?,
        desc: Boolean
    ): List<Map<String, Any?>> {
        val cmp = listComparator(sort)
        return items.sortedWith(if (desc) cmp.reversed() else cmp)
    }

    /**
     * `/list` 的过滤（2026-10-13 由 SQL 改为内存，语义逐条对齐原来的 `listSelection`）。
     *
     * 四层条件与原来的 SQL 完全一致：
     *  1. **扫描目录边界**（所有类型）：`DATA LIKE '<dir>/%'`，**任意深度**。
     *     空列表 = 这一类不限目录。这是用户在设置里划定的媒体库边界。
     *  2. **album / artist 等值**（只对音频）：`ALBUM / ARTIST` 是 `MediaStore.Audio` 独有的列，
     *     拿去查 video / image 表会直接抛「unknown column」。这里选择**忽略**而不是报错 ——
     *     三个分栏共用同一套请求构造，从"专辑详情"切到视频页时参数可能还挂在 URL 上，
     *     为一个无害的多余参数让整页 400 是把客户端的状态残留升级成故障。
     *  3. **dir = 只要直接位于该目录下的文件**：原来靠
     *     `LIKE 'dir/%' AND NOT LIKE 'dir/%/%'` 表达，这里用"相对路径不含分隔符"等价实现。
     *  4. **排除名单**（只对音频）：`DATA NOT IN (…)`，即"从音乐库移除"的那些路径。
     *
     * 与 `MediaExclusionStore` 的 KDoc 呼应：原来那 300 条上限是 **SQLite 绑定变量上限**
     * 逼出来的（超了直接抛异常）。改成内存 `Set` 判成员之后这个约束在过滤路径上不再存在，
     * 但上限本身仍保留 —— 它同时是"管理页别无限膨胀"的产品约束，不该因为实现变了就悄悄放开。
     */
    private fun filterListItems(
        kind: Kind,
        items: List<Map<String, Any?>>,
        album: String?,
        artist: String?,
        dir: String?
    ): List<Map<String, Any?>> {
        val dirs = scanDirs(kind)
        val excluded = if (kind == Kind.AUDIO) exclusions?.excluded().orEmpty() else emptySet()
        return items.filter { item ->
            val path = item["path"] as? String ?: return@filter false
            if (path.isBlank()) return@filter false

            // ① 扫描目录边界（任意深度）
            if (dirs.isNotEmpty() && dirs.none { path.startsWith("$it/") }) return@filter false

            if (kind == Kind.AUDIO) {
                // ② 专辑 / 歌手
                if (album != null && (item["album"] as? String) != album) return@filter false
                if (artist != null && (item["artist"] as? String) != artist) return@filter false
                // ③ 只要直接位于该目录下的文件
                if (dir != null) {
                    val rest = path.removePrefix("$dir/")
                    if (rest == path || rest.contains('/')) return@filter false
                }
                // ④ 排除名单
                if (excluded.isNotEmpty() && path in excluded) return@filter false
            }
            true
        }
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

    // ═══════════════════ 索引缓存的取数侧（2026-10-13） ═══════════════════

    /**
     * 拉取**一个 kind 的整库条目**（索引缓存的 loader，由 `init` 注册进 [index]）。
     *
     * ## 刻意不加任何过滤
     * 扫描目录、排除名单、album / artist / dir 全部留到**读时**在内存里做。理由见
     * `MediaIndexStore` 的类 KDoc：用户在设置里改扫描目录 / 移除一首歌要立刻生效，
     * 而 `PlaylistRoutes` 的按路径回查**刻意不受扫描目录约束**（见 [audioItemsByPaths]）。
     * 快照存过滤后的结果会让这三件事同时出错。
     *
     * ## 返回值语义（与 `MediaIndexStore` 的契约）
     *  · `null`   = 这一类当前**不可用**（没授权 / 查询抛异常）→ 索引层沿用上一份数据，
     *               连上一份都没有就不放这个 key，读路径改走实时查询；
     *  · `emptyList` = 这一类**真的一个文件都没有**（已授权但库里为空）。
     * 两者必须区分：把"没授权"当成空列表，客户端会显示成"这台设备没有媒体"。
     *
     * ## 只查元数据，不解码
     * 这是与 [thumbnailBytes] 那条路的根本分工 —— 本函数绝不调用任何会解码的 API，
     * 所以它不参与 471MB 那件事，只是把跨进程查询次数从"每次请求"降到"每 5 分钟"。
     * （2026-10-13 之前这里写的是"与 systemThumbnail 分工"，那个函数已随系统解码退役而删除。）
     */
    private suspend fun loadKindUncached(kind: Kind): List<Map<String, Any?>>? =
        withContext(Dispatchers.IO) {
            if (!isGranted(kind)) {
                AppLogger.i(TAG, "媒体索引跳过未授权类型(${kind.key})，读路径将走实时查询")
                return@withContext null
            }
            runCatching {
                val rows = mutableListOf<Map<String, Any?>>()
                val args = Bundle().apply {
                    // 按 _ID 升序：顺序稳定，客户端分页时同一份数据每次翻页顺序一致
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                        "${MediaStore.MediaColumns._ID} ASC"
                    )
                }
                // 字幕计数的目录缓存：整库构建时一个目录只 listFiles 一次。
                // 改造前这个 map 只活在单次请求内，滑动列表时同一个目录会被反复扫。
                val subtitleCache = mutableMapOf<String, List<String>>()
                appContext.contentResolver.query(
                    contentUriOf(kind), projectionOf(kind), args, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        if (rows.size >= MAX_INDEX_ITEMS_PER_KIND) {
                            AppLogger.w(
                                TAG,
                                "媒体索引(${kind.key})已达上限 $MAX_INDEX_ITEMS_PER_KIND 条，" +
                                    "后续条目本轮不收录（内存保护，非常规情况）"
                            )
                            break
                        }
                        rows += itemOf(c, kind, subtitleCache)
                    }
                }
                rows
            }.getOrElse {
                AppLogger.w(TAG, "媒体索引查询失败(${kind.key}): ${it.message}")
                null
            }
        }

    /**
     * 读这一类媒体的条目：**优先内存快照，快照不覆盖时才实时查**。
     *
     * 正常路径（服务起来预热过、或定时刷新跑过）永远走第 1 条 —— 零跨进程调用。
     * 第 2 条只覆盖"建快照时这一类还没授权、之后才授权"这个窗口，
     * 走的是**与改造前完全一致**的实时查询，所以既不会退化成空列表，
     * 也不会在下次刷新（≤5 分钟）或索引失效之后继续走它。
     */
    private suspend fun indexItems(kind: Kind): List<Map<String, Any?>> {
        val snap = index.snapshotOrLoad()
        if (snap != null && snap.covers(kind.key)) return snap.of(kind.key)
        return loadKindUncached(kind).orEmpty()
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
            val out = HashMap<String, Map<String, Any?>>(wanted.size)

            /*
             * ① 先用索引快照（2026-10-13）。
             *
             * 快照存的是**整库未过滤**条目，正好符合本方法"不受扫描目录约束"的口径
             *（见上方 KDoc），所以可以直接拿来回答。命中率通常接近 100%，
             * 于是打开歌单不再打 MediaProvider。
             *
             * 用 freshSnapshot 而不是 anySnapshot：过期快照里可能还留着**已经被删掉**的歌，
             * 那会让歌单把一首不存在的歌显示成可播放（点下去才失败）。宁可过期就走实时查询 ——
             * 定时刷新每 5 分钟一次、TTL 10 分钟，正常情况下根本碰不到这条边界。
             */
            val snap = index.freshSnapshot()
            if (snap != null && snap.covers(Kind.AUDIO.key)) {
                val byPath = snap.of(Kind.AUDIO.key).associateBy { it["path"] as? String ?: "" }
                for (p in wanted) byPath[p]?.let { out[p] = it }
            }

            /*
             * ② 快照里没找到的走实时查询兜底。
             *
             * 必须有这条：刚拷进来 / 刚入库的歌还没进快照，没有兜底就会被显示成"已失效"，
             * 而用户完全不知道是哪一步造成的。只在**有缺口时**才查（纯元数据查询，不解码），
             * 所以正常路径上这条一次都不会执行。
             */
            val missing = wanted.filter { it !in out }
            if (missing.isNotEmpty()) {
                val uri = contentUriOf(Kind.AUDIO)
                val resolver = appContext.contentResolver
                val projection = projectionOf(Kind.AUDIO)
                missing.chunked(PATH_LOOKUP_CHUNK).forEach { batch ->
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

    /*
     * ═══ 2026-10-13：系统解码入口整体退役 ═══
     *
     * 这里原本是 `systemThumbnail(uri, size)` —— 全文件唯一允许出现
     * `contentResolver.loadThumbnail` 的地方，靠 `thumbSystemSemaphore`（Mutex）串行化，
     * 用来压住 `android.process.media` 涨到 471MB。
     *
     * 现在它和那把闸门都删了，因为**根本不再需要**：ffmpeg 已接入且稳定，图片与音频封面
     * 本来就不需要 VPU。详见 [thumbnailBytes] 的 KDoc。
     *
     * 留下的教训（值得记住，因为它是上一版修复失败的原因）：闸门加在**调用点**上时，
     * 每新增一个调用点就漏一次 —— 上一版只包了 `thumbnailBytes`，漏了 `audioCoverBytes`
     * （音频列表热路径、恒定请求 1024px），于是 471MB 照旧复现。
     * 所以现在的做法不是"给每个调用点加锁"，而是**把那条调用整个删掉**。
     *
     * 闸门本身没有消失，只是换了语义：见 `MediaDecodeGate`（heavy = ffmpeg 抽帧，
     * light = 图片降采样 / 封面字节读取）。
     */

    // ─────────────────── 缩略图缓存 ───────────────────

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
     *
     * [reason] 是**给人看的失败原因**，会随 404 一起回给客户端。加它的理由（2026-09-16）：
     * 设备端日志要连 ADB 才能看，而客户端只看到一个"缩略图不可用" ——
     * 权限问题 / 解码栈缺失 / 文件损坏在两侧都长得一样。原因带回去之后，
     * 抓一份 app 的 HTTP 日志就能定位到具体是哪一层。
     *
     * 只包含异常类名与消息，不含路径、token 之类的敏感信息。
     */
    // source = 供 `X-Thumb-Source` 响应头。2026-10-13 起取值收敛为：
    //   cache（落盘命中）| ffmpeg（软解抽帧）| image（图片降采样）|
    //   tag（容器内嵌封面字节解析）| extractor（MMR 读封面原子）
    private data class ThumbnailAttempt(
        val bytes: ByteArray?,
        val reason: String,
        val source: String = "image",
    )

    /**
     * 缩略图字节（JPEG）。**每种类型一条 core 自有解码路径，不再调用系统解码**。
     *
     * | 类型 | 来源 | 闸门 |
     * |---|---|---|
     * | 视频 | ffmpeg 软解（`FfmpegThumbnailService`） | `MediaDecodeGate.heavy`（在服务内部取） |
     * | 图片 | `BitmapFactory` 按 `inSampleSize` 降采样 | `MediaDecodeGate.light` |
     * | 音频 | 内嵌封面（`AudioTagReader`）→ MMR 提取器兜底 | `MediaDecodeGate.light` |
     *
     * ## 为什么不再用系统解码（2026-10-13 的结论，取代此前那条已作废的"不做 ffmpeg"决策）
     * 此前第一级是 `contentResolver.loadThumbnail()`（MediaProvider），第二级是
     * `MediaMetadataRetriever` 抽帧。两者走的是**同一套系统 codec 栈**，而本机（随身 WiFi 的
     * 定制 ROM）桥接不到硬件 VPU：实测 `getFrameAtTime` 恒返回 null、`loadThumbnail` 报
     * `Failed to create thumbnail`。也就是说这两级在这台设备上**必然失败**，唯一的"收益"是
     * 让 `android.process.media` 白解一次 —— 那正是它涨到 471MB 且**不回落**的原因。
     *
     * ffmpeg 现已接入且稳定（4KB 页版本的 `libffmpeg_bridge.so`，见 `FfmpegFrameExtractor`），
     * 而图片与音频封面**本来就不需要 VPU**：前者是纯软件解码（libjpeg/skia），
     * 后者只是**读文件头部的字节结构**（见 `AudioTagReader` 的类 KDoc）。所以两级系统解码整体删除。
     *
     * ## 删掉它们之后还剩什么
     *  · 视频：ffmpeg 出图；`.so` 不可用时回 404（见 `FfmpegFrameExtractor.ensureLoaded`）。
     *  · 图片：core 自己降采样。`inSampleSize` 保证不会整图进内存
     *    （4000×3000 按 sample=8 解出 500×375 ≈ 750KB，而不是 48MB）。
     *  · 音频：`AudioTagReader` 解 `APIC` / `PICTURE`；读不到才退到
     *    `MediaMetadataRetriever.embeddedPicture` —— 那是**提取器**不是解码器，
     *    只读容器里的封面原子、不碰 codec，且是 M4A 封面的唯一来路。
     *
     * 收益不只是省内存：**缩略图这条路上对 MediaProvider 的解码调用归零**，
     * 471MB 从"被闸门压住"变成"不会发生"。
     *
     * 失败一律回 404 + reason，客户端画占位图标 —— 绝不回一张破图或空 200。
     */
    private suspend fun thumbnailBytes(kind: Kind, id: Long, size: Int): ThumbnailAttempt {
        val uri = ContentUris.withAppendedId(contentUriOf(kind), id)

        /*
         * 每条路径**只在一层**取闸门（`Semaphore` 不可重入，见 MediaDecodeGate 的使用规则）：
         *  · 视频：FfmpegThumbnailService 内部已取 heavy —— 这里**不得**再包一层，否则同一
         *    协程里二次取同一把信号量会永久挂住；
         *  · 图片 / 音频：在这里取 light，各自的具体解码函数内部不再取。
         */
        var source = when (kind) {
            Kind.VIDEO -> "ffmpeg"
            Kind.IMAGE -> "image"
            Kind.AUDIO -> "tag"
        }
        val generated: Result<ByteArray?> = when (kind) {
            Kind.VIDEO -> runCatching { ffmpegThumbnail(uri, size) }

            Kind.IMAGE -> runCatching {
                com.ufi_axis_core.media.MediaDecodeGate.light.withPermit {
                    downscaledImageThumbnail(uri, size).getOrNull()
                }
            }

            Kind.AUDIO -> runCatching {
                com.ufi_axis_core.media.MediaDecodeGate.light.withPermit { audioCoverBytes(id) }
                    ?.also { source = it.source }
                    ?.bytes
            }
        }

        generated.getOrNull()?.let { bytes ->
            // 落盘（与 PUT 路径同一套 tmp+rename 原子写法）：首刷慢是一次性的，
            // 之后全部秒回缓存命中。失败不影响本次响应。
            runCatching {
                val target = thumbCacheFile(kind, id)
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeBytes(bytes)
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) tmp.delete()
            }
            return ThumbnailAttempt(bytes, "", source = source)
        }

        /*
         * 失败原因要能区分"ffmpeg 不可用"与"这一帧解不出来"（2026-10-13）。
         *
         * 前者是**整类**失败（视频封面会全空，要重新打包 / 换 ABI / 查 RELRO），
         * 后者只影响这一条。混成同一句话，用户与排查者都只能靠猜 ——
         * 而这正是本仓反复吃过亏的地方（见 `/thumbnail` 路由 KDoc 里那段可观测性说明）。
         */
        val reason = generated.exceptionOrNull()
            ?.let { "${kind.key} 生成失败 ${it.javaClass.simpleName}: ${it.message}" }
            ?: buildString {
                append("${kind.key} 生成返回空（解码器没吐出画面 / 没有内嵌封面）")
                if (kind == Kind.VIDEO) {
                    com.ufi_axis_core.media.FfmpegThumbnailService.unavailableReason()?.let {
                        append("；ffmpeg 不可用：$it")
                    }
                }
            }
        AppLogger.w(TAG, "缩略图生成失败(${kind.key}/$id): $reason")
        return ThumbnailAttempt(null, reason)
    }

    /**
     * 视频封面：**ffmpeg 软解抽帧**（2026-10-13 起是唯一来源）。
     *
     * 挂起、内部串行（`MediaDecodeGate.heavy`）+ 20s 超时 + 超时冷却；
     * 失败返回 null（不是异常），由 [thumbnailBytes] 统一转成 404 + reason。
     *
     * 位置策略：首次传 0，由 JNI 内部取片长 40% 处 —— 不取第 0 帧是因为很多视频首帧是
     * 黑场或渐入，抽出来就是一张纯黑图，那和没有封面没区别。
     * 暗帧/全零帧的判定不在这里，统一走 `FrameValidator`（G3 四判据），
     * 与"抽帧成功"分开 —— ffmpeg exit 0 只保证解出了一帧，不保证这帧有意义。
     *
     * 早于 2026-10-13 的版本在本函数之前还有两级：系统缩略图（`loadThumbnail`）与
     * `MediaMetadataRetriever` 抽帧。两者走同一套坏掉的系统 codec 栈，已整体退役，
     * 理由见 [thumbnailBytes] 的 KDoc。
     */
    private suspend fun ffmpegThumbnail(uri: android.net.Uri, size: Int): ByteArray? {
        val bytes = com.ufi_axis_core.media.FfmpegThumbnailService
            .extractJpeg(appContext, uri, size, atSeconds = 0.0)
        if (bytes == null) return null
        // 2026-10-05 G3（修 F5）：ffmpeg exit 0 ≠ 内容有效 —— 全零帧/占位图在这里拦截
        when (val v = com.ufi_axis_core.media.FrameValidator.validate(bytes)) {
            is com.ufi_axis_core.media.FrameValidator.Verdict.Valid -> {
                AppLogger.i(TAG, "ffmpeg 抽帧成功(${bytes.size / 1024}KB)")
                return bytes
            }
            is com.ufi_axis_core.media.FrameValidator.Verdict.Blank -> {
                AppLogger.w(TAG, "ffmpeg 抽出全零帧（10bit/HEVC Ma10p 已知问题），拒绝: $uri")
                return null
            }
            else -> {
                AppLogger.w(TAG, "ffmpeg 抽帧无效($v)，拒绝: $uri")
                return null
            }
        }
    }

    /*
     * 2026-10-13 删掉了 `videoFrameThumbnail`（MediaMetadataRetriever 抽帧）与配套的
     * `Bitmap.averageLuma` / `VIDEO_FRAME_RATIOS` / `VIDEO_FRAME_FALLBACK_US` /
     * `DARK_LUMA_THRESHOLD` / `LUMA_SAMPLE_GRID`。
     *
     * 删它的理由不止"在这台设备上必然失败"（定制 ROM 桥接不到 VPU，`getFrameAtTime` 恒返回
     * null）——**即使它哪天能用了，它也比 ffmpeg 那条路危险得多**：
     *  · `getFrameAtTime(OPTION_CLOSEST_SYNC)` 返回的是**全分辨率**帧
     *    （4K = 3840×2160 ARGB ≈ 33MB），而且原实现对最多 5 个位置循环尝试；
     *  · 它当时**没有任何并发闸门**，几十个并发请求就是几十份 33MB。
     * 相比之下 ffmpeg 路径有 `MediaDecodeGate.heavy` 串行、且直接输出目标尺寸。
     *
     * 也就是说 MMR 抽帧此前是"没闸门 + 可能一次吃 33MB"，只是靠"本机必然失败"侥幸没事 ——
     * 那不是设计，是运气。黑帧/全零帧的判定并没有丢：ffmpeg 的输出仍然过 `FrameValidator`。
     */

    /**
     * 图片：先量尺寸再按 `inSampleSize` 降采样解码。
     *
     * 不整图解码再缩：一张 4000×3000 的照片整图进内存是 48MB，为了一个 256px 的格子不值得，
     * 大图还会直接 OOM。
     *
     * ⚠️ **调用方必须持有 `MediaDecodeGate.light`**（见 [thumbnailBytes]）。本函数内部
     * **不取**闸门 —— 两处都取会死锁（`Semaphore` 不可重入）。
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
        if (!isAllowedDir(abs)) return null
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
        if (title.equals(base, ignoreCase = true)) return ""
        // 2026-10-13：MediaStore 里的陈旧标题（用户真机报"歌名后面跟一串英文乱码"）。
        //
        // 真实样本：
        //   磁盘上真正的文件名：  阳光下的星星-金海心.flac
        //   MediaStore 里的 TITLE：阳光下的星星 -金海心.flac.29894902._CEujVT6XmHgTkI2
        //
        // 那个 `29894902` 是下载分片的字节数、`_CEujVT6XmHgTkI2` 是网易云音乐的临时文件标记 ——
        // 所以这**不是编码乱码**（不是 GBK/UTF-8 嗅探错），而是：文件下载时以临时名被扫进
        // MediaStore，之后被改名成正常名字，但 MediaStore 没有重新扫，那条 TITLE 就烂在库里了。
        //
        // 老逻辑只挡"TITLE == 文件名基名"，这条烂标题与基名不一致（多了 `.flac.29894902._CE…`），
        // 于是被当成真曲名原样透出去，显示成"阳光下的星星 -金海心.flac.29894902._CEujVT6XmHgTkI2"。
        //
        // 这里按"陈旧扫描"特征识别并丢弃，回空串让客户端走文件名兜底
        // （`splitFileNameTitleArtist` 能正确拆出 `阳光下的星星` + `金海心`）。
        //
        // 判据只有一条：**归一化后以基名开头、且比基名更长** —— 多出来的那截正是下载残留
        //（`.flac.29894902._CEujVT6XmHgTkI2`）。归一化是必需的：样本里基名是
        // `阳光下的星星-金海心` 而 TITLE 是 `阳光下的星星 -金海心…`，**多了一个空格**。
        //
        // 刻意**没有**采用"点数 ≥ 2 就当陈旧"这种更宽的规则：它会把
        // `Dr. A. Livingstone` 这类真实存在的曲名判掉。宁可漏判（多显示几秒残缺名，
        // 下次重扫 MediaStore 就好了）也不能误伤真曲名。
        val t = title.normalizedTitleKey()
        val b = base.normalizedTitleKey()
        val looksStale = b.isNotEmpty() && t.length > b.length && t.startsWith(b)
        return if (looksStale) "" else title
    }

    /**
     * 曲名比较用的归一化：去空白 + 转小写 + 全角转半角。
     *
     * 样本里基名是 `阳光下的星星-金海心` 而TITLE 是 `阳光下的星星 -金海心.flac.29894902._CE…`
     * ——**多了一个空格**。MediaStore 与磁盘名不一致的空格/全角在中文歌名上很常见，
     * 不归一化就永远判不出"它是同一个名字的旧版本"。
     */
    private fun String.normalizedTitleKey(): String =
        trim().lowercase().replace('\u3000', ' ').replace(Regex("\\s+"), "")


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
     * 一次封面尝试的结果：字节 + 内容类型 + 来源标记（来源进 `X-Thumb-Source`）。
     */
    private class CoverAttempt(
        val bytes: ByteArray,
        val contentType: ContentType,
        val source: String
    )

    /**
     * 音频封面（2026-10-13 重写：**不再调用系统解码**）。
     *
     * ## 三级（顺序即优先级）
     *  1. **落盘缓存**（[coverCacheRead]）→ 零解码、零文件读。这一层是本次修复的关键：
     *     `/cover` 原来是**纯实时**的，而列表是**按行**请求封面的
     *     （`MediaAudioGroupList` 的 LazyColumn 每行一个 `MediaThumb(url = coverUrl(entry))`），
     *     滑动就是几十个并发解码。
     *  2. **[AudioTagReader.readCover]** → 解 ID3v2 `APIC` / FLAC `PICTURE`。
     *     封面只是**容器头部的字节结构**，与文本标签一样不需要任何解码能力
     *     （见那个类的 KDoc）。这是 core 自有的主路径。
     *  3. **`MediaMetadataRetriever.embeddedPicture`** → 兜底。它是系统的**提取器**，
     *     只读容器里的封面原子、不碰 codec —— MP4/M4A 的 `covr` 走的就是这条，
     *     而 `AudioTagReader` 目前不解析 MP4 原子，所以没有它 M4A 封面就没有来路。
     *
     * ## 删掉了什么
     * 原来的第 3 级是 `contentResolver.loadThumbnail()`（MediaProvider **解码**），
     * 而且因为 `thumbnailBytes` 也会先调一次，**一次音频缩略图请求最多能触发两次**
     * MediaProvider 解码。那是 471MB 的元凶之一，现已整体退役。
     *
     * ## 为什么不再有 size 参数
     * 内嵌封面是**原图字节**，不重新编码 —— 要"小图"是客户端缩放的事（它本来就要按不同尺寸
     * 显示），core 侧缩放只会平白损失画质并多做一次编码。原来那个 `size` 只服务于
     * `loadThumbnail`，随它一起消失。
     *
     * ⚠️ **调用方必须持有 `MediaDecodeGate.light`**（见 [thumbnailBytes] 与 `/cover`）。
     * 本函数内部**不取**闸门 —— 两处都取会死锁（`Semaphore` 不可重入）。
     *
     * @return null = 确实拿不到封面，由调用方回 404 并写冷却标记。
     */
    private fun audioCoverBytes(id: Long): CoverAttempt? {
        // ① 落盘缓存：命中就完全不必碰 codec、也不必读文件
        coverCacheRead(id)?.let { return it }

        val uri = ContentUris.withAppendedId(contentUriOf(Kind.AUDIO), id)

        // ② core 自己解容器头部的字节结构（ID3 APIC / FLAC PICTURE），不依赖任何 codec
        val path = audioPathOf(id)
        if (path != null) {
            AudioTagReader.readCover(File(path))?.let { cover ->
                val type = if (cover.mime == "image/png") ContentType.Image.PNG
                else ContentType.Image.JPEG
                coverCacheWrite(id, cover.bytes)
                return CoverAttempt(cover.bytes, type, source = "tag")
            }
        }

        // ③ MMR 提取器兜底（MP4/M4A 的 covr 原子）。注意它是**提取器不是解码器**：
        //    只读容器里的封面原子，不碰 codec、不申请 VPU。
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
            val type = if (isPng) ContentType.Image.PNG else ContentType.Image.JPEG
            coverCacheWrite(id, embedded)
            return CoverAttempt(embedded, type, source = "extractor")
        }

        return null
    }

    // ─────────────────── 音频封面的落盘缓存（2026-10-13） ───────────────────

    /**
     * 封面缓存文件。按 (id, 扩展名) 编 —— 扩展名就是**内容类型**的载体：
     * 内嵌封面可能是 JPEG 也可能是 PNG，读回来时得能还原正确的 `Content-Type`
     *（存成一个固定后缀会让 PNG 被声明成 JPEG）。
     *
     * 与 [thumbCacheFile] 共用 `filesDir/thumbs` 目录与同一套容量淘汰（[pruneThumbCache]），
     * 所以不需要第二份配额逻辑。
     */
    private fun coverCacheFile(id: Long, ext: String): File =
        File(thumbCacheDir(), "audio_cover_$id.$ext")

    /** 封面缓存是否处于失败冷却期。冷却中直接 404，不重复跑 MMR + 解码。 */
    private fun coverCoolingDown(id: Long): Boolean {
        val mark = File(thumbCacheDir(), "audio_cover_$id.fail")
        if (!mark.isFile) return false
        val until = runCatching { mark.readText().trim().toLongOrNull() ?: 0L }.getOrDefault(0L)
        if (System.currentTimeMillis() >= until) {
            mark.delete()   // 冷却过了顺手清掉，目录不积垃圾
            return false
        }
        return true
    }

    /**
     * 记一次"这张确实取不到封面"。
     *
     * 没有它的话，一张无封面的歌每次列表刷新都会重跑一遍
     * `AudioTagReader.readCover` + MMR 提取 —— 那是"滑动时解码风暴"的另一个来源。
     * 冷却期与 `ThumbPrewarmWorker.FAIL_COOLDOWN_MS` 取同一个值（6h），口径一致。
     *
     * 原子写：半写的 `.fail` 会把时间戳写坏 → `toLongOrNull()` 得 0 → 冷却失效。
     */
    private fun markCoverFailed(id: Long) {
        runCatching {
            val mark = File(thumbCacheDir(), "audio_cover_$id.fail")
            val tmp = File(mark.parentFile, mark.name + "." + java.util.UUID.randomUUID() + ".tmp")
            tmp.writeText((System.currentTimeMillis() + COVER_FAIL_COOLDOWN_MS).toString())
            if (!tmp.renameTo(mark)) tmp.delete()
        }
    }

    /** 读封面落盘缓存。先试 jpg 再试 png；文件为空视为未命中（半写残留）。 */
    private fun coverCacheRead(id: Long): CoverAttempt? {
        val candidates = listOf(
            "jpg" to ContentType.Image.JPEG,
            "png" to ContentType.Image.PNG
        )
        for ((ext, type) in candidates) {
            val f = coverCacheFile(id, ext)
            if (!f.isFile || f.length() <= 0) continue
            val bytes = runCatching { f.readBytes() }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                return CoverAttempt(bytes, type, source = "cache")
            }
        }
        return null
    }

    /**
     * 封面落盘。原子写（tmp + rename）：中途被杀不会留下半张图被 GET 当缓存命中 ——
     * 与 `ThumbPrewarmWorker` 同一套口径。
     *
     * 写完不 prune：淘汰交给 `PUT /thumbnail`（预热落盘）与 `DELETE /thumbnail-cache`
     * 那两处的既有节奏，免得每存一张就全目录 `listFiles` + 求和一次（几百个文件时是可观开销）。
     */
    private fun coverCacheWrite(id: Long, bytes: ByteArray) {
        runCatching {
            val isPng = bytes.size > 8 && bytes[0] == 0x89.toByte() &&
                bytes[1] == 'P'.code.toByte()
            val target = coverCacheFile(id, if (isPng) "png" else "jpg")
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeBytes(bytes)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) tmp.delete()
        }.onFailure { AppLogger.w(TAG, "封面落盘失败($id): ${it.message}") }
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
     * 按 [by] 把音频聚合成分组列表。
     *
     * ## 2026-10-13：从「每次整表扫」改成「读索引快照」
     * 原来每次调用都现场 `query` 整张音频表（几千行游标推进），而客户端在音乐页里来回切
     * 专辑 / 歌手 / 文件夹三个视图就是三次整表扫描。现在数据来自 [index] 的内存快照
     *（后台每 5 分钟刷一次），这里只做纯内存聚合。
     *
     * 顺带不再需要原来那份"只取四列"的窄投影 `GROUPS_PROJECTION` —— 快照本来就是为了
     * 列表页取的完整条目，聚合直接用同一份；少一套投影就少一处会跟 `projectionOf` 写岔的地方。
     *
     * 目录过滤沿用 [scanDirs]（与 `/list` 同一口径）：分组视图与列表视图看到的必须是同一个
     * 媒体库范围，否则会出现"分组里有 12 首、点进去只有 8 首"。
     *
     * 排序在 Kotlin 侧做（count 降序 → title 升序），不交给 SQL：`GROUP BY` 的结果集在
     * MediaStore 的 `QUERY_ARG_SQL_*` 上并不保证可用（各 ROM 的 MediaProvider 对
     * 分组语句支持不一），而这里的数据量本来就是"几百个分组"级别，内存排序毫无压力。
     */
    private suspend fun audioGroupsPayload(by: GroupBy): Map<String, Any?> {
        val buckets = HashMap<String, GroupAccumulator>()
        val dirs = scanDirs(Kind.AUDIO)
        val items = runCatching { indexItems(Kind.AUDIO) }.getOrElse {
            AppLogger.w(TAG, "音频分组取索引失败(by=${by.key}): ${it.message}")
            emptyList()
        }

        for (item in items) {
            val path = item["path"] as? String ?: ""
            // 目录边界：与 /list 同口径（DATA LIKE '<dir>/%'，任意深度）。
            // dirs 为空 = 这一类不限目录，此时连 path 为空的条目也照收（与改造前一致）。
            if (dirs.isNotEmpty() && (path.isBlank() || dirs.none { path.startsWith("$it/") })) {
                continue
            }
            val album = normalizedTag(item["album"] as? String ?: "")
            val artist = normalizedTag(item["artist"] as? String ?: "")
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
                bucket.coverId = (item["id"] as? Long) ?: NO_COVER_ID
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

            // ── 「从音乐库移除」排除名单（2026-09-23）────────────────────────
            //
            // 语义边界：这组接口**只动名单，永远不动文件**。删文件是
            // `POST /api/files/delete` 的事，两者刻意分开 —— 一个可撤销、一个不可逆，
            // 混在一个接口里用参数区分，迟早有客户端传错那个参数。

            /** 当前排除名单。客户端用它渲染「已从音乐库移除的歌曲」管理页。 */
            get("/excluded") {
                val store = exclusions
                if (store == null) {
                    call.respondFail(
                        HttpStatusCode.ServiceUnavailable, ErrorCode.NOT_FOUND,
                        "排除名单未启用"
                    )
                    return@get
                }
                val paths = store.excluded().toList()
                call.respond(toJsonElement(mapOf(
                    "paths" to paths,
                    "total" to paths.size,
                    // 名单有硬上限（SQL 绑定变量），客户端据此提前提示而不是让用户点了才发现没生效
                    "max" to com.ufi_axis_core.api.media.MediaExclusionStore.MAX_EXCLUSIONS,
                    "full" to store.isFull()
                )))
            }

            /**
             * 加入 / 移出排除名单。
             *
             * body: `{ "paths": [...], "action": "add" | "remove" | "clear" }`
             * `action` 缺省为 `add`。
             */
            post("/exclude") {
                val store = exclusions
                if (store == null) {
                    call.respondFail(
                        HttpStatusCode.ServiceUnavailable, ErrorCode.NOT_FOUND,
                        "排除名单未启用"
                    )
                    return@post
                }
                // receiveJsonObject 而不是 receive<JsonObject>()：后者在 Android 上
                // 走反射会失败（全仓统一口径）
                val body = call.receiveJsonObject()
                val action = body["action"]?.jsonPrimitive?.contentOrNull ?: "add"
                val paths = body["paths"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                    .orEmpty()

                if (action != "clear" && paths.isEmpty()) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BLANK_VALUE,
                        "paths 不能为空"
                    )
                    return@post
                }

                val affected = when (action) {
                    "add" -> store.add(paths)
                    "remove" -> store.remove(paths)
                    "clear" -> store.clear()
                    else -> {
                        call.respondFail(
                            HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                            "action 必须是 add / remove / clear"
                        )
                        return@post
                    }
                }

                // 名单变了，列表结果就变了。不失效的话客户端刷出来还是旧的那一份，
                // 用户会以为"移除没生效"。
                //
                // 2026-10-13：主失效对象改成 [index] 快照 —— 列表与分组都从它读。
                // 快照存的是**未过滤**的整库条目，排除名单是读时才套的，所以严格说
                // 连失效都不必要（下一次读自然就用上新名单了）；这里仍然清一次是保守做法，
                // 代价只是下一次读重拉一轮。`media:*` 那行保留：它还被 FileRoutes 的删除路径共用。
                index.invalidate()
                cache.invalidate("media:*")

                call.respond(toJsonElement(mapOf(
                    "success" to true,
                    "action" to action,
                    "affected" to affected,
                    // 请求了 N 条只生效了 M 条 = 撞上上限被截断，客户端据此提示
                    "requested" to paths.size,
                    "total" to store.excluded().size,
                    "full" to store.isFull()
                )))
            }

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
             * 三个参数只对 `type=audio` 生效（理由见 [filterListItems]），与扫描目录过滤是 AND。
             *
             * 本端点不走 [ResponseCache]：它是分页查询，工作量已经被 SQL 的 LIMIT 限住了，
             * 叠内存缓存只会多出一份要失效的状态 —— 所以过滤参数也不存在"串缓存"的问题。
             */
            /**
             * 2026-10-05 G5（FFmpeg 接入计划书 §4.1）：视频元信息探测。
             *
             * ## 为什么需要它
             * `/list` 的 duration/width/height 全来自 MediaStore 列，而本机 MediaProvider
             * 同样依赖缺失的 codec 栈 —— 经常没扫描出来或为 0。ffmpeg 引入后 core 第一次
             * 有了自主探测真值的能力。
             *
             * ## 缓存策略
             * 媒体文件不可变：结果落 filesDir/video_info/ 缓存（单条 <1KB）。探测走 ffmpeg
             * （fd 通道 + 20s 超时）。注意：缓存目录不做 rescan 清理（2026-10-05 审查修正，
             * 此前注释误称会清理）——MediaStore id 复用时旧结果会一直命中，量级可忽略
             * （单文件 <1KB）；DELETE /thumbnail-cache 在清视频/全量时连带清 video_info/。
             *
             * 404：无法解析（损坏/不支持容器/ffmpeg 不可用）——reason 随 404 带回；
             * 同时写 .fail 冷却标记（6h，与缩略图预热同一口径），避免坏文件每次请求
             * 都重新占用 20s 的 ffmpeg 探测槽位。
             */
            get("/video-info") {
                val id = call.request.queryParameters["id"]?.toLongOrNull() ?: run {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "缺少 id")
                    return@get
                }
                val uri = ContentUris.withAppendedId(contentUriOf(Kind.VIDEO), id)
                val cacheFile = File(File(appContext.filesDir, "video_info"), "$id.json")

                var source = "cache"
                val failMark = File(cacheFile.parentFile, "${cacheFile.name}.fail")
                val json = if (cacheFile.isFile) {
                    withContext(Dispatchers.IO) { cacheFile.readBytes().decodeToString() }
                } else {
                    // 404 冷却（审查修复）：冷却期内直接回 404，不再重探
                    if (failMark.isFile) {
                        val until = withContext(Dispatchers.IO) {
                            runCatching { failMark.readText().trim().toLongOrNull() }.getOrNull()
                        }
                        if (until != null && until > System.currentTimeMillis()) {
                            call.respondFail(
                                HttpStatusCode.NotFound, ErrorCode.NOT_FOUND,
                                "无法解析此视频（冷却中，稍后自动重试）"
                            )
                            return@get
                        }
                        withContext(Dispatchers.IO) { runCatching { failMark.delete() } }
                    }
                    val probed = com.ufi_axis_core.media.FfmpegThumbnailService
                        .probeVideoInfo(appContext, uri)
                    if (probed == null) {
                        // 2026-10-05 审查修复：404 也落冷却标记（6h），坏文件不再反复打满探测槽位
                        withContext(Dispatchers.IO) {
                            runCatching {
                                failMark.parentFile?.mkdirs()
                                failMark.writeText(
                                    (System.currentTimeMillis() + 6L * 60 * 60 * 1000).toString()
                                )
                            }
                        }
                        call.respondFail(
                            HttpStatusCode.NotFound, ErrorCode.NOT_FOUND,
                            "无法解析此视频（损坏/不支持容器/ffmpeg 不可用）"
                        )
                        return@get
                    }
                    withContext(Dispatchers.IO) {
                        runCatching {
                            cacheFile.parentFile?.mkdirs()
                            // 唯一临时名（审查修复）：并发 miss 不互踩同一 tmp
                            val tmp = File(
                                cacheFile.parentFile,
                                cacheFile.name + "." + java.util.UUID.randomUUID() + ".tmp"
                            )
                            tmp.writeBytes(probed.encodeToByteArray())
                            tmp.renameTo(cacheFile)
                        }
                    }
                    source = "ffmpeg"
                    probed
                }

                // 解析 JNI JSON → 扁平响应（计划书 §4.1 口径：duration_ms/width/... + source）
                val obj = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(json)
                        .let { it as kotlinx.serialization.json.JsonObject }
                }.getOrNull()
                if (obj == null) {
                    // 2026-10-05 审查修复：坏缓存自愈——删除后重新探测，不再永久 500
                    withContext(Dispatchers.IO) { runCatching { cacheFile.delete() } }
                    val probed = com.ufi_axis_core.media.FfmpegThumbnailService
                        .probeVideoInfo(appContext, uri)
                    if (probed == null) {
                        // 与主失败路径同口径：自愈重探也失败就落冷却标记（测试锁定的行为）
                        withContext(Dispatchers.IO) {
                            runCatching {
                                failMark.parentFile?.mkdirs()
                                failMark.writeText(
                                    (System.currentTimeMillis() + 6L * 60 * 60 * 1000).toString()
                                )
                            }
                        }
                        call.respondFail(
                            HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "元信息解析失败"
                        )
                        return@get
                    }
                    val retry = runCatching {
                        kotlinx.serialization.json.Json.parseToJsonElement(probed)
                            .let { it as kotlinx.serialization.json.JsonObject }
                    }.getOrNull()
                    if (retry == null) {
                        call.respondFail(
                            HttpStatusCode.InternalServerError, ErrorCode.INTERNAL_ERROR, "元信息解析失败"
                        )
                        return@get
                    }
                    withContext(Dispatchers.IO) {
                        runCatching {
                            cacheFile.parentFile?.mkdirs()
                            val tmp = File(
                                cacheFile.parentFile,
                                cacheFile.name + "." + java.util.UUID.randomUUID() + ".tmp"
                            )
                            tmp.writeBytes(probed.encodeToByteArray())
                            tmp.renameTo(cacheFile)
                        }
                    }
                    respondVideoInfo(call, id, retry, "ffmpeg")
                    return@get
                }
                fun d(k: String): Double =
                    (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
                fun l(k: String): Long =
                    (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                fun s(k: String): String =
                    (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                respondVideoInfo(call, id, obj, source)
            }

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
                val sort = call.request.queryParameters["sort"]
                val desc = call.request.queryParameters["order"]?.lowercase() != "asc"
                val albumFilter = filterParam(call.request.queryParameters["album"])
                val artistFilter = filterParam(call.request.queryParameters["artist"])
                // 末尾斜杠要去掉：客户端可能传 `/sdcard/Music/`，不去掉会拼出 `//%` 而一条都匹配不上
                val dirFilter = filterParam(call.request.queryParameters["dir"])?.trimEnd('/')

                /*
                 * 2026-10-13：数据源从「现场查 MediaStore」改成 [index] 内存快照。
                 *
                 * 改造前这里每次请求都要跨进程跑**两条**查询（一条 `_ID` 计数拿 total、
                 * 一条带排序与分页拿当页），滑动列表就是几十次 IPC 打到 android.process.media。
                 * 现在取一次快照（后台每 5 分钟刷新），过滤 / 排序 / 分页全在内存里做。
                 *
                 * 语义逐条对齐了原来的 SQL：
                 *  · 扫描目录 = `DATA LIKE '<dir>/%'`（任意深度）；
                 *  · 排除名单 = `DATA NOT IN (…)`，只对音频生效；
                 *  · album / artist = 等值，只对音频生效（视频/图片忽略，与改造前一致）；
                 *  · dir = 只要**直接位于**该目录下的文件（原 `LIKE 'dir/%' AND NOT LIKE 'dir/%/%'`）；
                 *  · total = 过滤后的**总数**（不是当页条数），分页在排序之后做。
                 */
                withContext(Dispatchers.IO) {
                    val matched = filterListItems(
                        kind, indexItems(kind), albumFilter, artistFilter, dirFilter
                    )
                    val total = matched.size
                    val items = sortListItems(matched, sort, desc).drop(offset).take(limit)

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

                // 2026-10-13：去掉了原来叠在这里的 ResponseCache（`media:groups:*`）。
                // 聚合的数据源已经是 [index] 的内存快照（后台每 5 分钟刷一次），
                // 聚合本身是纯内存遍历（几百个分组），再套一层 JSON 缓存只多一处失效点：
                // 排除名单变更 / 扫描目录变更 / 索引刷新三件事都得记得把它清掉，
                // 漏一个用户就看到陈旧分组。直接算，反正不比查表慢。
                //
                // `by` 仍要区分（三个视图各算一份），但那是入参，不再是缓存 key 的一部分。
                call.respond(withContext(Dispatchers.IO) { toJsonElement(audioGroupsPayload(by)) })
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

                val sort = call.request.queryParameters["sort"]
                val desc = call.request.queryParameters["order"]?.lowercase() != "asc"

                withContext(Dispatchers.IO) {
                    /*
                     * 2026-10-13：与 /list 一样改为读 [index] 内存快照。
                     *
                     * 改造前这里最坏要跑 1 + 200 条跨进程查询（本层文件 1 条，每个子目录再 1 条
                     * 拿计数与封面）—— MAX_BROWSE_FOLDERS 就是 200，进一次文件夹视图
                     * 就是两百次 IPC 打到 android.process.media。现在按路径前缀在内存里聚合，
                     * 一次跨进程调用都没有。
                     */
                    val all = indexItems(kind)

                    // 这一层的文件：路径以本目录开头，但**不再往下一层**（排掉 '<dir>/x/y'）
                    val here = all.filter { item ->
                        val p = item["path"] as? String ?: return@filter false
                        p.startsWith("$canonical/") && !p.removePrefix("$canonical/").contains('/')
                    }
                    val items = sortListItems(here, sort, desc).take(MAX_PAGE_SIZE)

                    // 子目录：数一次该子树里的同类文件，顺便取一个 id 当文件夹封面。
                    // 计数含**所有深度**（原 SQL 是 `DATA LIKE '<sub>/%'`，没有排深层）；
                    // 封面取子树里**最近修改**的那一条（原 SQL 按 DATE_MODIFIED DESC 取首行）。
                    val children = runCatching { dir.listFiles() }.getOrNull().orEmpty()
                        .filter { it.isDirectory && !it.name.startsWith(".") }
                        .sortedBy { it.name.lowercase() }
                        .take(MAX_BROWSE_FOLDERS)
                    val folders = mutableListOf<Map<String, Any?>>()
                    for (sub in children) {
                        val subPath = sub.absolutePath.trimEnd('/')
                        val prefix = "$subPath/"
                        var count = 0
                        var coverId: Long? = null
                        var newest = Long.MIN_VALUE
                        for (item in all) {
                            val p = item["path"] as? String ?: continue
                            if (!p.startsWith(prefix)) continue
                            count++
                            val dm = (item["date_modified"] as? Long) ?: 0L
                            if (dm > newest) {
                                newest = dm
                                coverId = item["id"] as? Long
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
             * 缩略图（JPEG）。两级：**落盘缓存** → core 自行解码。
             *
             * 缓存排第一是刻意的：core 侧出图是秒级开销（ffmpeg 软解一帧），
             * 而同一张图会被反复请求（滑动列表、换客户端、Web 端与第二台手机）。
             * 命中就发，不碰任何解码器。
             *
             * 没命中时按类型走各自的自有解码路径 —— 视频 ffmpeg、图片降采样、
             * 音频读内嵌封面。**不再调用系统解码**，理由见 [thumbnailBytes] 的 KDoc。
             *
             * 两级都拿不到就回 404 **并带上失败原因**，让客户端画占位图标 ——
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
                    // 2026-10-05 G4：来源标记 —— 客户端可区分"真出图"与"404 兜底"，
                    // app 端据此决定是否触发本机抽帧回传（X-Thumb-Source: cache = 已有图，不抽）
                    call.response.header("X-Thumb-Source", "cache")
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
                // 2026-10-05 G4：system=系统缩略图 / mmr=自行抽帧 / ffmpeg=软解（attempt.source 由链路带回）
                call.response.header("X-Thumb-Source", attempt.source)
                call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
                call.respondBytes(bytes, ContentType.Image.JPEG)
            }

            /*
             * ═══ 2026-10-13：删除了 `PUT /thumbnail`（客户端回传抽好的帧）═══
             *
             * 它存在的理由是「谁有解码能力谁干活」：core 那台设备解不出视频画面，
             * 所以让手机把帧抽好交上来，core 只存与发。ffmpeg 接入并稳定之后这个理由消失了
             *（core 自己就能抽），而**两个客户端都已不再调用它** ——
             * app 侧注释写明「本机抽帧兜底已整体删除」，web 侧从来没有过。
             *
             * 于是它只剩成本：一个接收 ≤512KB 任意 JPEG 的写入口、一套内容校验、
             * 以及"缓存目录会被塞进孤儿文件"的风险面。留着是纯负债，直接删。
             *
             * 覆盖式写入的能力没有丢：`ThumbPrewarmWorker` 与 GET 路径都会按 (type,id)
             * 原子落盘同一批 `filesDir/thumbs/<type>_<id>.jpg`，需要重抽时用
             * `DELETE /thumbnail-cache?type=&id=` 清掉再拉即可（app 的「手动重抽封面」走的就是它）。
             */

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
                // 2026-10-07：可选 id —— 传了就只清那一条（手动"重新获取封面"用），
                // 不带 id 保持整类/全部清空的旧语义，老客户端不受影响。
                val singleId = call.request.queryParameters["id"]?.toLongOrNull()
                // video_info/ 一并清（2026-10-05 审查遗留补口）：探测缓存与 .fail 冷却标记
                // 都随"换抽帧策略/排查黑图"的场景该重置；只在清视频（或不带 type）时清，
                // 音频/图片场景与它无关。
                val clearVideoInfo = kind == null || kind == Kind.VIDEO
                val result = if (singleId != null) {
                    // 2026-10-07：单条路径 —— 封面缓存 + video_info（探测缓存/冷却标记）各删一份，
                    // 汇总口径与整类清除一致（removed=0 时不报错，调用方按语义当"本来就没有"）。
                    withContext(Dispatchers.IO) {
                        val cache = thumbCacheDir()
                        var removed = 0
                        var freed = 0L
                        val prefix = (kind ?: Kind.VIDEO).key + "_"
                        cache.listFiles()?.forEach { f ->
                            if (f.isFile && f.name == "$prefix$singleId.jpg") {
                                freed = f.length()
                                if (f.delete()) removed++
                            }
                        }
                        var viRemoved = 0
                        if (clearVideoInfo) {
                            val vi = File(appContext.filesDir, "video_info")
                            listOf("$singleId.json", "$singleId.json.fail").forEach { name ->
                                val f = File(vi, name)
                                if (f.isFile && f.delete()) viRemoved++
                            }
                        }
                        Triple(removed, freed, viRemoved)
                    }
                } else {
                withContext(Dispatchers.IO) {
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
                    var viRemoved = 0
                    if (clearVideoInfo) {
                        File(appContext.filesDir, "video_info").listFiles()?.forEach { f ->
                            // <id>.json（探测缓存）与 <id>.json.fail（404 冷却）都归这里管
                            if (f.isFile && (f.name.endsWith(".json") || f.name.endsWith(".fail"))) {
                                if (f.delete()) viRemoved++
                            }
                        }
                    }
                    Triple(removed, freed, viRemoved)
                }
                }
                AppLogger.i(
                    TAG,
                    "已清空缩略图缓存(${kind?.key ?: "全部"}): ${result.first} 张, ${result.second / 1024} KB, video_info ${result.third} 条"
                )
                call.respond(
                    toJsonElement(
                        mapOf(
                            "success" to true,
                            "type" to (kind?.key ?: ""),
                            "removed" to result.first,
                            "freed_bytes" to result.second,
                            "video_info_removed" to result.third
                        )
                    )
                )
            }

            /**
             * 2026-10-07：手动跑一轮封面预热（app 设置页「立即预热」按钮）。
             * 忽略闲时门（手动=现在就要跑），总开关仍需开启；重复点击被 Worker 的
             * start/stop 互斥挡住（返回 busy）。
             */
            post("/thumbnail-prewarm") {
                if (!settings.thumbPrewarmEnabled) {
                    call.respondFail(
                        HttpStatusCode.Conflict, ErrorCode.BAD_REQUEST,
                        "预热开关未开启（先在设置中打开）"
                    )
                    return@post
                }
                val hook = manualPrewarm
                if (hook == null) {
                    call.respondFail(HttpStatusCode.NotImplemented, ErrorCode.INTERNAL_ERROR, "当前装配不支持手动预热")
                    return@post
                }
                call.respond(toJsonElement(mapOf("started" to true)))
                // 响应先回，预热在后台跑（进度走 WS 广播）
                // respond 已把响应刷给客户端；预热在同一协程里继续跑（进度走 WS 广播）。
                // 不用 launch：本文件的协程作用域不持有 Job，且请求协程本就挂在 core 的
                // application 作用域上，生命周期与后台服务一致。
                runCatching { hook() }
            }

            /**
             * 2026-10-07：封面预热状态查询（GET /thumbnail-prewarm）。
             *
             * 解决"点击立即预热后退出页面、重进看不到任何状态"——之前进度只活在
             * WS 广播里，页面不在就丢了。现在 Worker 每处理一条都落盘快照，
             * 这里读回快照 + 内存实时值：`snapshot=false` 表示 core 重启后还没跑过任何一轮。
             */
            get("/thumbnail-prewarm") {
                val p = com.ufi_axis_core.media.ThumbPrewarmWorker.readPersisted(appContext)
                if (p == null) {
                    call.respond(toJsonElement(mapOf("snapshot" to false)))
                    return@get
                }
                call.respond(
                    toJsonElement(
                        mapOf(
                            "snapshot" to mapOf(
                                "done" to p.done,
                                "total" to p.total,
                                "failed" to p.failed,
                                "running" to p.running,
                                "current_name" to p.currentName,
                                "started_at" to p.startedAt,
                                "updated_at" to p.updatedAt
                            )
                        )
                    )
                )
            }

            /**
             * 音频封面（**原始内嵌图字节**，不重新编码）。
             *
             * 与 `/thumbnail?type=audio` 分开的理由：那个是给**列表**用的（可缓存、能糊），
             * 这个是给**播放页**用的，要的是尽量清楚。内嵌封面本身就是原图字节，
             * 直接透传不重新编码是能拿到的最高画质 —— 也因此它**不再有 size 参数**：
             * 要小图是客户端缩放的事，core 侧缩放只会白丢画质还多一次编码。
             *
             * 取不到就 404（并写 6h 冷却标记），客户端画占位图标。
             * 来源与层级见 [audioCoverBytes] 的 KDoc。
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

                // 2026-10-13 改动：
                //  ① 冷却中的"确实没封面"直接 404，不重复跑字节解析 + MMR 提取；
                //  ② 取不到时写冷却标记，否则每次列表刷新都会重跑一遍；
                //  ③ 过 MediaDecodeGate.light（音频封面读取属于"轻解码"）——
                //     audioCoverBytes 内部不再取闸门，避免不可重入的 Semaphore 死锁。
                val cover = withContext(Dispatchers.IO) {
                    if (coverCoolingDown(id)) {
                        null
                    } else {
                        com.ufi_axis_core.media.MediaDecodeGate.light.withPermit {
                            audioCoverBytes(id).also { if (it == null) markCoverFailed(id) }
                        }
                    }
                }
                if (cover == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.BAD_REQUEST, "没有封面")
                    return@get
                }
                call.response.header(HttpHeaders.ETag, etag)
                call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
                call.respondBytes(cover.bytes, cover.contentType)
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
                    // 先 canonical 化再比前缀：符号链接指出白名单外时，原始字符串是看不出来的
                    val abs = runCatching { File(dir).canonicalPath }.getOrNull()
                    abs != null && isAllowedDir(abs) && !dir.contains("..")
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
                    .filter { dir ->
                        // 与 /config 同一判据：canonical 化之后再比分隔符敏感的前缀
                        val abs = runCatching { File(dir).canonicalPath }.getOrNull()
                        abs != null && isAllowedDir(abs) && !dir.contains("..")
                    }
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
                    // ═══ 分批提交扫描（2026-10-12，修 android.process.media 内存暴涨）═══
                    //
                    // 现象：点一次「重新扫描」，系统媒体进程 `android.process.media` 内存涨到
                    // 500MB，要重启 core 才释放。
                    //
                    // 根因：[MediaScannerConnection.scanFile] 的路径数组是**一次性跨进程传递**的
                    // —— 整个 String[] 序列化进 Binder 事务；5000 个平均 60 字符的绝对路径
                    // 就是 300KB+ 的单个 String[]，加上 Binder 1MB 上限被撑到极限，
                    // 而接收端 `MediaProvider.scanFile` 还要把全部路径**读进内存**做扩展名归类、
                    // 建 pending 队列、按目录聚合。两端同时持有 → 内存暴涨。
                    //
                    // 为什么分批有效：接收端每次只处理一小批，pending 队列与路径集合的峰值内存
                    // 被压到单批的量级；而且 MediaProvider 内部本身就是**按批串行 drain** 的，
                    // 分批提交与它的消费模型一致，不会丢项。
                    //
                    // 批大小取 200：一次 200 个路径约 12KB，远在 Binder 安全范围内；
                    // 又不会太碎（批数 = 5000/200 = 25 次，串行提交耗时可忽略）。
                    paths.chunked(RESCAN_CHUNK_SIZE).forEach { chunk ->
                        // 异步提交：扫描器自己排队收录，这里不等它完成（可能几十秒）
                        MediaScannerConnection.scanFile(appContext, chunk.toTypedArray(), null, null)
                    }
                }

                /*
                 * ═══ 提交后追赶重建索引（2026-10-13，用户明确要求）═══
                 *
                 * 为什么不能"提交完立刻重建一次"就完事：`MediaScannerConnection.scanFile`
                 * 是**异步 fire-and-forget**，它返回时文件还没入库 —— 立刻重建拿到的必然是旧库，
                 * 用户点了「重新扫描」却看不到任何变化，会认为按钮坏了。
                 *
                 * 所以按沉降窗口追赶三次（+3s / +15s / +45s，见 `CHASE_DELAYS_MS`），
                 * 覆盖小批量、常规批量、大库慢卡三种入库延迟。
                 *
                 * 竞态：连点 N 次「重新扫描」不会叠出 3N 次刷新 ——
                 * `scheduleChaseRefresh` 每次调用都会取消上一组追赶协程（见其 KDoc）。
                 * 而且追赶刷新与定时刷新共用同一把单飞锁，同一时刻只有一次跨进程查询在飞。
                 *
                 * 注意 paths 为空（目录里没有该类文件）时**也要**安排：
                 * 那种情况往往是"文件刚拷进来但还没入库"，正是最需要重建的时候。
                 */
                index.scheduleChaseRefresh()
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
