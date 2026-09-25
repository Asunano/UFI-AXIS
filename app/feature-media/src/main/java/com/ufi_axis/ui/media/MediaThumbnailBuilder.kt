package com.ufi_axis.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import com.ufi_axis.viewmodel.module.MediaModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 手机端抽帧兜底：设备解不出画面时，由**手机**抽一帧当缩略图，并把成果回传给 core。
 *
 * ## 为什么需要它
 * core 跑在随身 WiFi 上，那台机器的定制 ROM 没有可用的视频解码器 —— 实测系统缩略图报
 * `FileNotFoundException: Failed to create thumbnail`，我们自己用 `MediaMetadataRetriever`
 * 抽帧则**拿到 null 帧**（`setDataSource` 成功，说明容器能解析，是解码器出不了画面）。
 * 那台设备上任何"服务端生成缩略图"的方案都走不通，包括换时间点、换 API、装 ffmpeg
 * （ffmpeg 软解在 8 核低频 + 1.5GB 内存上又太慢）。
 *
 * 手机的解码栈是完整的，所以把这件事挪到手机做，再把结果交回 core（[MediaModule.uploadThumbnail]）：
 * 之后 Web 端、第二台手机都能直接命中 core 的缓存，不必各自重抽。
 *
 * ## 为什么必须用票据 URL
 * `MediaMetadataRetriever` 自己发 HTTP 请求、**加不了签名头**，而设备签名里的 nonce 是一次性的
 * —— 抽帧过程中的多个 Range 请求必然从第二个起被拒。所以走 core 的
 * `/media/stream?ticket=…`（可重复使用、滑动过期、只授权这一个文件），
 * 见 [MediaModule.ticketStreamUrl]。
 *
 * ## 三条闸门（都是为了别把手机和局域网榨干）
 *  · [GATE] 限 2 个并发；
 *  · **成功**落在磁盘缓存（[cached]），下次连 core 都不问；
 *  · **失败**也记一笔（`.fail` 标记文件），[FAIL_COOLDOWN_MS] 内不再重试 ——
 *    没有这一条，一个解不出来的文件每次滚过列表都要重新拉几 MB、再失败一次。
 *
 * ## 缓存上限
 * 缓存在 `cacheDir` 下，系统在低存储时会回收，但那是"最后防线"而不是策略：
 * 每次写盘后按修改时间淘汰到 [CACHE_LIMIT_BYTES] 以内（与 core 侧同一套思路）。
 */
internal object MediaThumbnailBuilder {

    /** 抽出来的图缩到最长边这么大：足够 512dp 以内的任何用法，也够 Web 端用。 */
    private const val TARGET_SIZE = 512

    /** JPEG 质量。缩略图不需要 95，85 已经看不出差别而体积小一半。 */
    private const val JPEG_QUALITY = 85

    /** 并发闸：2 个。抽帧是「网络等待 + 软解」混合负载，再高只会互相抢带宽。 */
    private val GATE = Semaphore(2)

    private const val CACHE_DIR = "media-thumbs"

    /** 缩略图缓存上限：512px JPEG 大概 30~60KB，96MB 够放上千个。 */
    private const val CACHE_LIMIT_BYTES = 96L * 1024 * 1024

    /**
     * 失败冷却：这个时间内不再重试同一项。
     *
     * 6 小时是"用户不会觉得永远不出图，但也不会一直重试"的折中：真正修好的路子是
     * 换个能解的文件或在设置页手动重试（批量任务会清掉失败标记）。
     */
    private const val FAIL_COOLDOWN_MS = 6L * 60 * 60 * 1000

    /**
     * 是否已经走过本机抽帧这条路（= 设备端确实出不了缩略图）。
     *
     * 界面据此显示一次性说明：**在真的发生之前不提示**，否则对解码正常的设备就是无端的噪音。
     * 进程内状态，不持久化 —— 每次冷启动重新观察，设备换了 ROM / 装了新 core 之后不会
     * 一直挂着一条过时的说明。
     */
    private val _everUsed = MutableStateFlow(false)
    val everUsed: StateFlow<Boolean> = _everUsed.asStateFlow()

    /** 正在抽的数量（>0 时界面提示"本机正在干活"，别让用户以为卡住了）。 */
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    /**
     * 抽帧位置在片长中的候选比例（2026-09-20 重写）。
     *
     * ## 原来为什么全是黑屏
     * 之前固定抽**第 1 秒**。那个位置在现实片源里几乎注定是黑的：
     * 淡入、发行商 logo 前的黑场、番剧的黑底标题卡、录屏的启动瞬间 —— 全在前几秒。
     * 原注释说的「不取第 0 帧因为首帧常是黑场」只躲开了最表层那一下，
     * 1 秒和 0 秒在这件事上没有本质区别。
     *
     * ## 现在的做法
     * 首选位置落在**中段**（[FRAME_PICK_RANGE_START] ~ [FRAME_PICK_RANGE_END]），
     * 具体偏移由 URL 哈希决定 —— 于是不同视频取到片长的不同处（看起来是"随机"的），
     * 而**同一个视频永远取同一处**。
     *
     * 为什么不用真随机：清一次缓存封面就会换一张，而且随机本身并不能避开黑场
     * （夜戏、转场、片尾黑屏同样黑）。真正解决黑屏的是下面的亮度检测。
     */
    private const val FRAME_PICK_RANGE_START = 0.30f
    private const val FRAME_PICK_RANGE_END = 0.65f

    /** 首选点太暗时依次重试的比例。覆盖前中后段，仍然避开片头片尾。 */
    private val FRAME_FALLBACK_RATIOS = floatArrayOf(0.50f, 0.25f, 0.70f, 0.40f, 0.85f)

    /**
     * 判定"这帧是黑屏"的平均亮度阈值（0~255）。
     *
     * 18 是保守值：纯黑场 0~3，带噪点的黑场 10 上下，而真正的夜戏画面即便很暗也普遍在 25 以上。
     * 定高了会把正常暗调画面误判成黑屏、白白多抽几次帧（每次都是一轮 Range 请求 + 解码）。
     */
    private const val DARK_LUMA_THRESHOLD = 18

    /** 亮度采样网格边长：16×16 = 256 点。对 512px 的帧足够，比逐像素快两个数量级。 */
    private const val LUMA_SAMPLE_GRID = 16

    /** 读不到时长时的兜底抽帧点。取 10 秒而不是 1 秒 —— 理由同上。 */
    private const val FRAME_FALLBACK_POSITION_US = 10_000_000L

    /**
     * 亮度恰为 0 视为「解码器根本没填充缓冲区」，不是「这一帧很黑」。
     *
     * 实测依据（VCB-Studio 的 `[Ma10p_1080p][x265]`，HEVC Main10）：6 个不同时间点
     * 采样出来的亮度**全是 0**，编出的 JPEG 只有 1.6KB。而真实画面即便是黑幕也不会绝对为 0
     * —— YUV limited range 的黑是 16，转 RGB 后有偏移，再加编码噪点，实拍黑场落在 2~8。
     *
     * 这类帧**绝不能当结果交出去**：之前它被当成"最亮的那张"上传到设备并写进缓存，
     * 于是"清了缓存还是黑"——清完立刻又抽一张纯黑图存回去。
     * 判为 0 就当抽帧失败（显示占位图标），而不是硬交一张纯黑图当封面。

     */
    private const val BLANK_LUMA = 0



    // ── 批量任务（设置页入口）──

    /**
     * 批量生成的进度。
     *
     * @param paused 用户按了暂停：协程还活着，只是卡在等待循环里 —— 这样"继续"不需要
     *   重新枚举列表，也不会丢掉已经拿到的票据。
     * @param failed 本轮失败数。失败项会写冷却标记，但**批量任务开始时会清掉旧标记**：
     *   用户主动点"生成全部"就是在说"再试一次"。
     */
    data class BatchProgress(
        val total: Int = 0,
        val done: Int = 0,
        val failed: Int = 0,
        val paused: Boolean = false,
        val finished: Boolean = false
    ) {
        val running: Boolean get() = !finished
        val remaining: Int get() = (total - done).coerceAtLeast(0)
    }

    /**
     * 任务作用域**不是**页面作用域：用户要的是"退出这个页面之后还在跑"。
     *
     * 进程被杀就断（不用 WorkManager / 前台服务）：抽帧是纯粹的锦上添花，
     * 为它常驻一个服务不合比例；而成功与失败都落了盘，下次点"继续"直接跳过已完成的。
     */
    private val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var batchJob: Job? = null

    private val _batch = MutableStateFlow<BatchProgress?>(null)
    val batch: StateFlow<BatchProgress?> = _batch.asStateFlow()

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, CACHE_DIR).apply { if (!isDirectory) mkdirs() }

    private fun cacheFile(context: Context, type: String, id: Long): File =
        File(cacheDir(context), "${type}_$id.jpg")

    private fun failFile(context: Context, type: String, id: Long): File =
        File(cacheDir(context), "${type}_$id.fail")

    /** 本地已有就直接给（不发任何请求）。 */
    fun cached(context: Context, type: String, id: Long): File? =
        cacheFile(context, type, id).takeIf { it.isFile && it.length() > 0 }

    /** 抽帧功能是否开启（设置页的真开关，默认开）。 */
    fun enabled(context: Context): Boolean =
        runCatching { AppPreferences(context).mediaPhoneFrameExtraction }.getOrDefault(true)

    /** 这一项最近失败过、还在冷却期内吗。 */
    private fun coolingDown(context: Context, type: String, id: Long): Boolean {
        val marker = failFile(context, type, id)
        if (!marker.isFile) return false
        val age = System.currentTimeMillis() - marker.lastModified()
        if (age in 0..FAIL_COOLDOWN_MS) return true
        marker.delete()
        return false
    }

    /**
     * 确保本地有这一项的缩略图：命中缓存直接返回，否则换票 → 抽帧 → 落盘 → 回传 core。
     *
     * 三种情况直接回 null 不干活：功能被关掉、这一项在失败冷却期内、换票或抽帧失败。
     * 一律不弹错误、不自动重试 —— 缩略图缺失不该打断浏览。
     *
     * **整体**跑在 IO 线程上：前三道闸门就已经在读文件元信息与 SharedPreferences，
     * 而调用方是 Compose 的主线程作用域。
     */
    suspend fun build(
        context: Context,
        media: MediaModule,
        type: String,
        item: MediaLibraryItem
    ): File? = withContext(Dispatchers.IO) {
        cached(context, type, item.id)?.let { return@withContext it }
        if (!enabled(context)) return@withContext null
        if (coolingDown(context, type, item.id)) return@withContext null
        _everUsed.value = true
        GATE.withPermit {
            // 拿到许可后再查一次：等待期间可能已经被另一个请求抽好了
            cached(context, type, item.id) ?: run {
                _activeCount.update { it + 1 }
                try {
                    buildLocked(context, media, type, item)
                } finally {
                    _activeCount.update { (it - 1).coerceAtLeast(0) }
                }
            }
        }
    }


    private suspend fun buildLocked(
        context: Context,
        media: MediaModule,
        type: String,
        item: MediaLibraryItem
    ): File? {
        val url = media.ticketStreamUrl(item.path)
        if (url == null) {
            DebugLog.w("MediaThumb", "换票失败，无法抽帧: ${item.path}")
            markFailed(context, type, item.id)
            return null
        }
        // 抽帧只走 MediaMetadataRetriever。
        // 它对 10-bit/HEVC（VCB-Studio 那类 Ma10p 压制）会返回全零帧，那种情况下
        // extractFrameJpeg 回 null 而不是交黑图 —— 宁可显示占位图标，也不给一张假封面。
        val bytes = extractFrameJpeg(url)
        if (bytes == null) {
            DebugLog.w("MediaThumb", "本机抽帧失败: ${item.name}")
            markFailed(context, type, item.id)
            return null
        }


        val target = cacheFile(context, type, item.id)
        val saved = runCatching {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeBytes(bytes)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }.getOrDefault(false)
        if (saved) {
            failFile(context, type, item.id).delete()
            pruneCache(context)
        }

        // 顺手共享给 core：成功与否都不影响本地显示
        media.uploadThumbnail(type, item.id, bytes)

        return if (saved) target else null
    }

    private fun markFailed(context: Context, type: String, id: Long) {
        runCatching {
            val marker = failFile(context, type, id)
            marker.writeBytes(ByteArray(0))
            marker.setLastModified(System.currentTimeMillis())
        }
    }

    // ── 缓存维护（设置页显示占用 / 清空）──

    /**
     * 缓存里的图片数与总字节数（`.fail` 标记不算，它们只有 0 字节）。
     *
     * suspend + IO 线程：listFiles + 逐项 length 是真的磁盘活，调用方在 Compose 作用域里。
     */
    suspend fun cacheStats(context: Context): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val files = runCatching { cacheDir(context).listFiles() }.getOrNull().orEmpty()
            .filter { it.isFile && it.name.endsWith(".jpg") }
        files.size to files.sumOf { it.length() }
    }

    /** 清空缓存（连失败标记一起清：用户点"清空"也是在说"重新来一遍"）。 */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        runCatching { cacheDir(context).listFiles() }.getOrNull().orEmpty()
            .forEach { runCatching { it.delete() } }
    }


    /** 超出上限时按"最久没被写过"淘汰。不做 LRU：读的时候不碰 mtime，省一次写盘。 */
    private fun pruneCache(context: Context) {
        val files = runCatching { cacheDir(context).listFiles() }.getOrNull().orEmpty()
            .filter { it.isFile && it.name.endsWith(".jpg") }
        var total = files.sumOf { it.length() }
        if (total <= CACHE_LIMIT_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= CACHE_LIMIT_BYTES) return
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    // ── 批量任务 ──

    /**
     * 批量生成：跳过已有缓存的，逐项抽帧。可暂停 / 继续 / 取消，退出页面仍在跑。
     *
     * [items] 由调用方给全（视频页会先把整库拉齐）—— 本对象不发列表请求，
     * 那是 `MediaModule` 的事，混进来就成了两个地方都能决定"要处理哪些文件"。
     *
     * suspend + IO 线程：起跑前要对整库逐项 stat（查缓存、清冷却标记），主线程做不起。
     */
    suspend fun startBatch(
        context: Context,
        media: MediaModule,
        type: String,
        items: List<MediaLibraryItem>
    ) = withContext(Dispatchers.IO) {
        if (batchJob?.isActive == true) return@withContext
        val pending = items.filter { cached(context, type, it.id) == null }
        if (pending.isEmpty()) {
            _batch.value = BatchProgress(total = 0, done = 0, finished = true)
            return@withContext
        }
        // 用户主动点"生成全部"= 明确要求重试，把冷却标记清掉
        pending.forEach { failFile(context, type, it.id).delete() }
        _batch.value = BatchProgress(total = pending.size)
        batchJob = taskScope.launch {
            for (item in pending) {
                if (!isActive) break
                while (_batch.value?.paused == true && isActive) delay(200)
                if (!isActive) break
                val ok = build(context, media, type, item) != null
                _batch.update { current ->
                    current?.copy(
                        done = current.done + 1,
                        failed = current.failed + if (ok) 0 else 1
                    )
                }
            }
            _batch.update { it?.copy(finished = true, paused = false) }
        }
    }


    fun pauseBatch() {
        _batch.update { it?.copy(paused = true) }
    }

    fun resumeBatch() {
        _batch.update { it?.copy(paused = false) }
    }

    /** 取消并清掉进度条（已完成的那些当然留在缓存里）。 */
    fun cancelBatch() {
        batchJob?.cancel()
        batchJob = null
        _batch.value = null
    }

    /**
     * 从 HTTP 地址抽一帧并编码成 JPEG。
     *
     * `MediaMetadataRetriever` 内部会按需发 Range 请求，只拉到索引与目标关键帧附近的数据，
     * 不会把整个文件下完 —— 前提是服务端支持 Range（core 的流式端点支持）。
     *
     * 抽帧位置的选取见 [FRAME_PICK_RANGE_START]：先按 URL 哈希在中段取一个点，
     * 抽出来太暗就顺着 [FRAME_FALLBACK_RATIOS] 往下试，全都暗则留最亮的那张
     * （宁可给一张暗图，也比给纯黑或干脆没有强）。
     *
     * ## 为什么要记录每个位置的亮度（2026-09-20）
     * MP4 正常、MKV 全黑，指向**容器的网络 seek 能力**而不是位置选取：
     * MP4 的关键帧表（`stss`）在 faststart 时位于文件开头，MMR 一开始就能拿到全部偏移；
     * 而 MKV 的 `Cues` 索引绝大多数压制在**文件末尾**，MMR 对 HTTP 源往往不会回头去读，
     * 拿不到索引就放弃 seek、从头顺序解 —— 于是不管传 30% 还是 70%，回来的都是开头那一帧。
     *
     * 判据很干净：**seek 若无效，几个不同位置抽出的帧必然是同一张，亮度完全相同**。
     * 所以这里把每个位置的实测亮度记下来，最后一次性 WARN 出来；
     * 全部相同就直接判定"容器不支持网络随机访问"。用 WARN 而不是 DEBUG ——
     * benchmark/release 包只留 WARN/ERROR，DEBUG 级的诊断在真机上抓不到。
     */
    private fun extractFrameJpeg(url: String): ByteArray? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, emptyMap())
            val durationUs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.times(1_000L)

            val positions = framePositionsFor(url, durationUs)
            var best: Bitmap? = null
            var bestLuma = -1
            // 诊断用：每个候选位置的实测亮度，用来判断 seek 到底有没有生效
            val probes = mutableListOf<Pair<Long, Int>>()

            for ((index, positionUs) in positions.withIndex()) {
                // 首个位置若拿回偏暗的帧，用 OPTION_CLOSEST 在**同一位置**再试一次：
                // SYNC 只跳关键帧，CLOSEST 会解码到精确时间点 —— 某些容器上后者才真的动了。
                var frame = retriever.getFrameAtTime(
                    positionUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                var scaled = frame?.scaledDown(TARGET_SIZE)
                var luma = scaled?.averageLuma() ?: -1
                if (index == 0 && luma in 0 until DARK_LUMA_THRESHOLD) {
                    val exact = retriever.getFrameAtTime(
                        positionUs,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )?.scaledDown(TARGET_SIZE)
                    val exactLuma = exact?.averageLuma() ?: -1
                    if (exactLuma > luma) {
                        scaled?.recycle()
                        scaled = exact
                        luma = exactLuma
                    } else {
                        exact?.recycle()
                    }
                }
                if (scaled == null) continue

                probes += positionUs / 1000 to luma
                if (luma >= DARK_LUMA_THRESHOLD) {
                    best?.recycle()
                    return@runCatching scaled.toJpeg(JPEG_QUALITY)
                }
                // 全零帧不参与"最亮的那张"竞争：它不是暗画面，是解码失败的产物，
                // 交出去等于把纯黑图写进三层缓存（见 BLANK_LUMA）。
                if (luma > BLANK_LUMA && luma > bestLuma) {
                    best?.recycle()
                    best = scaled
                    bestLuma = luma
                } else {
                    scaled.recycle()
                }
            }

            // 到这儿说明没拿到够亮的帧。把实测数据一次性报出来，供定位用。
            val allBlank = probes.isNotEmpty() && probes.all { it.second <= BLANK_LUMA }
            DebugLog.w(
                "MediaThumb",
                buildString {
                    append("抽帧未取到有效画面 url=").append(url.substringAfterLast('/').take(60))
                    append(" duration=").append(durationUs?.div(1_000_000) ?: -1).append("s")
                    append(" probes=").append(probes.joinToString { "${it.first}ms:${it.second}" })
                    if (allBlank) {
                        append(" → 全是空白帧（解码器未填充），")
                        append("多为 10-bit/HEVC（Ma10p 那类压制）在 MediaMetadataRetriever 上的已知缺陷")
                    }
                }
            )

            // 全空白 = MMR 这条路对该编码无效。**不能**把 best（仍是纯黑）交出去 ——
            // 返回 null 让调用方走 markFailed，界面显示占位图标，比一张假封面诚实。
            if (allBlank) return@runCatching null


            best?.toJpeg(JPEG_QUALITY)
        } finally {
            retriever.release()
        }
    }.getOrElse { e ->
        DebugLog.w("MediaThumb", "抽帧异常: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /**
     * 抽帧候选位置（微秒），按尝试顺序排列。

     *
     * 首选点由 [url] 的哈希在中段内定位：同一个视频恒定（封面不会因为重建缓存而变），
     * 不同视频各取各处（避免"一个文件夹里所有封面都是同一时间点的构图"）。
     *
     * 时长未知时（流式容器读不到 duration）退化为一个固定点，
     * 由 `getFrameAtTime` 自己找最近的关键帧。
     */


    private fun framePositionsFor(url: String, durationUs: Long?): LongArray {
        if (durationUs == null || durationUs <= 0) {
            return longArrayOf(FRAME_FALLBACK_POSITION_US)
        }
        // 取 hash 的低位映射到 [START, END)，Int.MIN_VALUE 取绝对值会溢出，先转 Long
        val span = FRAME_PICK_RANGE_END - FRAME_PICK_RANGE_START
        val hashFraction = (url.hashCode().toLong() and 0xFFFF) / 0xFFFF.toFloat()
        val primary = FRAME_PICK_RANGE_START + span * hashFraction

        val ratios = floatArrayOf(primary, *FRAME_FALLBACK_RATIOS.toTypedArray().toFloatArray())
        return LongArray(ratios.size) { i -> (durationUs * ratios[i]).toLong() }
    }

    /**
     * 网格采样的平均亮度（0~255）。
     *
     * 用感知加权 `(2R + 5G + B) / 8` 而不是简单平均：人眼对绿色最敏感，
     * 简单平均会把纯绿画面算得偏暗、纯蓝画面算得偏亮。
     *
     * 只采 [LUMA_SAMPLE_GRID]² 个点：512×288 的帧有 14 万像素，逐像素读是纯浪费 ——
     * 判断"整屏是不是黑的"这件事上，256 个均匀分布的采样点和全量统计结论一致。
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
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                sum += (r * 2 + g * 5 + b) / 8
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count > 0) (sum / count).toInt() else 0
    }


    /** 等比缩到最长边 = [size]（本来更小就不动，放大只会更糊还更大）。 */
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

    private fun Bitmap.toJpeg(quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            compress(Bitmap.CompressFormat.JPEG, quality, out)
            recycle()
            out.toByteArray()
        }
}
