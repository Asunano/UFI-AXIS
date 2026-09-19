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
     * 抽帧取的时间点（1 秒）。与 core 侧同一口径：首帧常是黑场，抽出来跟没有一样。
     * 比视频还短时 `getFrameAtTime` 会退到最近的关键帧。
     */
    private const val FRAME_POSITION_US = 1_000_000L

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
     */
    suspend fun build(
        context: Context,
        media: MediaModule,
        type: String,
        item: MediaLibraryItem
    ): File? {
        cached(context, type, item.id)?.let { return it }
        if (!enabled(context)) return null
        if (coolingDown(context, type, item.id)) return null
        _everUsed.value = true
        return GATE.withPermit {
            // 拿到许可后再查一次：等待期间可能已经被另一个请求抽好了
            cached(context, type, item.id) ?: withContext(Dispatchers.IO) {
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
        val bytes = extractFrameJpeg(url)
        if (bytes == null) {
            DebugLog.w("MediaThumb", "本机抽帧也失败: ${item.name}")
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

    /** 缓存里的图片数与总字节数（`.fail` 标记不算，它们只有 0 字节）。 */
    fun cacheStats(context: Context): Pair<Int, Long> {
        val files = runCatching { cacheDir(context).listFiles() }.getOrNull().orEmpty()
            .filter { it.isFile && it.name.endsWith(".jpg") }
        return files.size to files.sumOf { it.length() }
    }

    /** 清空缓存（连失败标记一起清：用户点"清空"也是在说"重新来一遍"）。 */
    fun clearCache(context: Context) {
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
     */
    fun startBatch(
        context: Context,
        media: MediaModule,
        type: String,
        items: List<MediaLibraryItem>
    ) {
        if (batchJob?.isActive == true) return
        val pending = items.filter { cached(context, type, it.id) == null }
        if (pending.isEmpty()) {
            _batch.value = BatchProgress(total = 0, done = 0, finished = true)
            return
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
     * `MediaMetadataRetriever` 内部会按需发 Range 请求，只拉到 moov 与目标关键帧附近的数据，
     * 不会把整个文件下完 —— 前提是服务端支持 Range（core 的流式端点支持）。
     */
    private fun extractFrameJpeg(url: String): ByteArray? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, emptyMap())
            val frame = retriever.getFrameAtTime(
                FRAME_POSITION_US,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.frameAtTime
            frame?.scaledDown(TARGET_SIZE)?.toJpeg(JPEG_QUALITY)
        } finally {
            retriever.release()
        }
    }.getOrElse { e ->
        DebugLog.w("MediaThumb", "抽帧异常: ${e.javaClass.simpleName}: ${e.message}")
        null
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
