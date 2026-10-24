package com.ufi_axis_core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * FFmpeg 抽帧服务（FFmpeg 接入计划书 G2）——封 [FfmpegFrameExtractor]，提供 URI 进、JPEG 出。
 *
 * ## 设计要点（对齐 MediaRoutes 现有口径）
 * - **fd 传递**：`openFileDescriptor("r")` → `/proc/self/fd/N` 传给 JNI。Android 10+ 的
 *   Scoped Storage 下直接把 MediaStore 路径给 native 在部分 ROM 会 EACCES，fd 是稳定通道；
 *   JNI 侧 in-process 解码（不 fork），fd 在调用期间有效（withContext 块内 pfd 保持打开）。
 * - **串行 + 超时（E4/E5）**：软解是 CPU 大户，1.5GB 内存设备并发两张 4K HEVC 就可能 OOM，
 *   `Semaphore(1)` 全局串行；外层 `withTimeoutOrNull(20_000)` 是最终防线——JNI 不可中断，
 *   超时只能放弃等待（fd 关闭后 native 读到 EOF 会退出），放弃后调用方走手机端兜底。
 * - **位置策略**：首帧取 40%（`MediaRoutes.ffmpegThumbnail` 传 `atSeconds = 0.0`，由 JNI 内部换算），
 *   暗帧时由调用方按 0.55/0.25/0.70 再试。不取第 0 帧是因为很多视频首帧是黑场或渐入，
 *   抽出来就是纯黑图 —— 与"没有封面"没有区别。
 *   （2026-10-13 之前这里写的是"对齐 core `VIDEO_FRAME_RATIOS[0]`"，那个常量已随
 *   MMR 抽帧路径一起删除，比例值直接落在本说明里。）
 * - **有效性判定不在这里**：黑帧/全零帧/占位图判定统一走 [FrameValidator]（G3，三来源共用），
 *   本类只负责"解出一帧 JPEG"，不负责"这一帧有没有意义"（F5：exit code ≠ 内容有效）。
 */
object FfmpegThumbnailService {

    private const val TAG = "FfmpegThumb"

    /**
     * 软解串行化（E4）：前台请求与后台预热**共用**同一把全局信号量。
     *
     * 2026-10-13 起改为引用 [MediaDecodeGate.heavy]（单一真源）。原先这里是本类私有的
     * `Semaphore(1)`，与 `MediaRoutes` 里那把"MediaProvider 闸门"各管一摊 ——
     * 系统解码退役后前者消失，闸门的语义也从"对 MediaProvider 的调用串行化"
     * 变成"所有解码工作串行化"，所以必须收拢到一个对象上，否则又会出现
     * "两条解码路径各有一把锁、谁也不认识谁"的局面。
     *
     * ⚠️ 调用方**不得**在已持有 [MediaDecodeGate.heavy] 时再调本类：`Semaphore` 不可重入，
     * 那会在同一协程里二次取许可并永久挂住。详见 [MediaDecodeGate] 的使用规则。
     */
    private val decodeSlots = MediaDecodeGate.heavy

    /** 单次抽帧外层超时（E5）。超时 → 该次放弃 → 上层走手机兜底。 */
    private const val EXTRACT_TIMEOUT_MS = 20_000L

    /** 外层超时（非解码失败）的 id 级短冷却：30min 内不重试同一条（超时多为偶发负载）。 */
    private const val TIMEOUT_COOLDOWN_MS = 30L * 60 * 1000

    private val timeoutCooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * ffmpeg 当前是否可用；不可用时给出**原因文本**，可用时返回 null（2026-10-13）。
     *
     * 供上层把 404 的 reason 写清楚。`extractJpeg` 返回 null 有两种截然不同的原因：
     *  · **ffmpeg 不可用**（`.so` 加载失败）→ 视频封面会**全部**失败，要重新打包 / 换 ABI；
     *  · 这个文件解不出帧（损坏 / 不支持的容器 / 超时）→ 只影响这一条。
     * 不区分的话，用户和排查者看到的都是同一句"缩略图不可用"。
     */
    fun unavailableReason(): String? =
        if (FfmpegFrameExtractor.ensureLoaded()) null
        else FfmpegFrameExtractor.loadFailure ?: "libffmpeg_bridge.so 不可用（原因未知）"

    /**
     * 从视频抽一帧 JPEG。
     * @param atSeconds 抽帧位置秒（<=0 取 40% 处）；暗帧重试时由调用方换位置再调
     * @return 成功 = JPEG 字节；失败 = null（原因见日志；加载失败/超时/解码失败统一 null）
     */
    suspend fun extractJpeg(context: Context, uri: Uri, size: Int, atSeconds: Double = 0.0): ByteArray? {
        if (!FfmpegFrameExtractor.ensureLoaded()) {
            // E2：加载失败永久跳过。日志与原因在 FfmpegFrameExtractor.ensureLoaded() 里记一次，
            // 这里不再重复（每条请求都记就是刷屏）。
            return null
        }
        val key = "$uri"
        val now = System.currentTimeMillis()
        if ((timeoutCooldownUntil[key] ?: 0L) > now) return null

        return withContext(Dispatchers.IO) {
            decodeSlots.withPermit {
                withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
                    runCatching { extractViaFd(context, uri, size, atSeconds) }
                        .onFailure { AppLogger.w(TAG, "ffmpeg 抽帧异常: ${it.javaClass.simpleName}: ${it.message}") }
                        .getOrNull()
                } ?: run {
                    // E5：超时 → 30min 冷却，避免同一个 4K 大文件把每次请求都拖满 20s
                    timeoutCooldownUntil[key] = now + TIMEOUT_COOLDOWN_MS
                    AppLogger.w(TAG, "ffmpeg 抽帧超时(${EXTRACT_TIMEOUT_MS / 1000}s)，30min 冷却: $key")
                    null
                }
            }
        }
    }

    /** fd 通道实现。pfd 必须在 native 调用期间保持打开。 */
    private fun extractViaFd(context: Context, uri: Uri, size: Int, atSeconds: Double): ByteArray? {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val fdPath = "/proc/self/fd/${pfd.fd}"
            val out = File.createTempFile("ufi_ff_", ".jpg", context.cacheDir)
            try {
                val rc = FfmpegFrameExtractor.extractFrame(fdPath, out.absolutePath, atSeconds, size)
                if (rc != 0) {
                    AppLogger.w(TAG, "ffmpeg extractFrame rc=$rc err=${FfmpegFrameExtractor.lastError() ?: "?"}")
                    return null
                }
                val bytes = out.readBytes()
                return if (bytes.isEmpty()) null else bytes
            } finally {
                out.delete()
            }
        }
        return null   // openFileDescriptor 失败（E8：文件可能已被删）
    }

    /**
     * 2026-10-05 G5：探测视频元信息（JSON 文本），供 video-info 端点。
     * fd 通道 + 串行 + 20s 超时，与抽帧共用 [decodeSlots]。失败返回 null。
     */
    suspend fun probeVideoInfo(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        if (!FfmpegFrameExtractor.ensureLoaded()) return@withContext null
        decodeSlots.withPermit {
            withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        FfmpegFrameExtractor.probeVideoInfo("/proc/self/fd/${pfd.fd}")
                    }
                }.getOrNull()
            }
        }
    }

    /**
     * 探测视频信息（时长秒）。供 G5（video-info）与暗帧重试时判断可用位置。
     * <=0 = 未知（加载失败/解析失败）。
     */
    suspend fun probeDuration(context: Context, uri: Uri): Double = withContext(Dispatchers.IO) {
        if (!FfmpegFrameExtractor.ensureLoaded()) return@withContext 0.0
        decodeSlots.withPermit {
            withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        FfmpegFrameExtractor.probeDurationSeconds("/proc/self/fd/${pfd.fd}")
                    } ?: 0.0
                }.getOrDefault(0.0)
            } ?: 0.0
        }
    }
}
