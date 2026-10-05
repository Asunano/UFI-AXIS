package com.ufi_axis_core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
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
 * - **位置策略**：首帧取 40%（对齐 core `VIDEO_FRAME_RATIOS[0]`），暗帧时由调用方按
 *   0.55/0.25/0.70 再试（复用 core 现有数组，这里不重复定义位置策略）。
 * - **有效性判定不在这里**：黑帧/全零帧/占位图判定统一走 [FrameValidator]（G3，三来源共用），
 *   本类只负责"解出一帧 JPEG"，不负责"这一帧有没有意义"（F5：exit code ≠ 内容有效）。
 */
object FfmpegThumbnailService {

    private const val TAG = "FfmpegThumb"

    /** 软解串行化（E4）：全局 1 个许可，预热（G6，未实装）与前台请求共用同一信号量。 */
    private val decodeSlots = Semaphore(1)

    /** 单次抽帧外层超时（E5）。超时 → 该次放弃 → 上层走手机兜底。 */
    private const val EXTRACT_TIMEOUT_MS = 20_000L

    /** 外层超时（非解码失败）的 id 级短冷却：30min 内不重试同一条（超时多为偶发负载）。 */
    private const val TIMEOUT_COOLDOWN_MS = 30L * 60 * 1000

    private val timeoutCooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 从视频抽一帧 JPEG。
     * @param atSeconds 抽帧位置秒（<=0 取 40% 处）；暗帧重试时由调用方换位置再调
     * @return 成功 = JPEG 字节；失败 = null（原因见日志；加载失败/超时/解码失败统一 null）
     */
    suspend fun extractJpeg(context: Context, uri: Uri, size: Int, atSeconds: Double = 0.0): ByteArray? {
        if (!FfmpegFrameExtractor.ensureLoaded()) {
            // E2：加载失败永久跳过，不再打日志刷屏（loadState 已缓存结果）
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
