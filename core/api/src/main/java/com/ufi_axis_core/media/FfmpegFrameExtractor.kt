package com.ufi_axis_core.media

import com.ufi_axis_core.util.AppLogger

/**
 * FFmpeg 抽帧 JNI 入口（FFmpeg 接入计划书 G1）。
 *
 * so 来源：Asunano/FFmpeg-Android-4KB 仓库（通用桥，RegisterNatives 动态注册）。
 * 构建该 so 时 BRIDGE_CLASS 必须是 com/ufi_axis_core/media/FfmpegFrameExtractor
 * （与 loadLibrary 的库名 libffmpeg_bridge 一同构成加载契约）。
 * 方法签名与桥内 JNINativeMethod 表严格一致（改签名 = UnsatisfiedLinkError）。
 *
 * 加载策略（E2）：ensureLoaded() 失败（.so 缺失 / ABI 不符 / RELRO 残留）→
 * 返回 false，调用方**永久跳过** ffmpeg（进程内标记，不反复尝试重载）。
 * 首次接入期最大风险就是真机 RELRO——加载失败必须静默降级，
 * 绝不能让封面链路因 ffmpeg 崩掉。
 *
 * ⚠️ **2026-10-13 起没有兜底了**：系统解码两级（系统缩略图 / MMR 抽帧）与手机回传都已退役，
 * 所以 `.so` 加载失败 = **视频封面全部 404**（图片与音频封面不受影响，它们不依赖 ffmpeg）。
 *
 * 需要说明的是：**这不是本次改动引入的**。退役前那两级在本机（定制 ROM 桥接不到 VPU）
 * 本来就不出图，手机回传也自 2026-10-07 起无人调用 —— 也就是说"ffmpeg 挂了就没人兜底"
 * 这件事一直成立，本次只是把那些**实际不工作**的兜底删掉了。
 *
 * 本次补的是**可观测性**：加载失败现在会记一条 ERROR，并把原因经 [loadFailure] 透到
 * 404 的 reason 里（见 `FfmpegThumbnailService.unavailableReason`）。
 * 原先这里是"静默降级"，`.so` 加载失败时整条链路零输出，只能靠猜。
 */
object FfmpegFrameExtractor {

    private const val TAG = "FfmpegBridge"

    /** 加载结果。null = 尚未尝试；false = 已失败（永久跳过）；true = 可用。 */
    @Volatile
    var loadState: Boolean? = null
        private set

    /**
     * 加载失败的**原因文本**（`.so` 缺失 / ABI 不符 / RELRO 残留…）；未尝试或成功时为 null。
     *
     * 存在的理由：要把「ffmpeg 不可用」与「这一帧解不出来」在 404 的 reason 里**分开**。
     * 两者的处置完全不同 —— 前者要重新打包 / 换 ABI / 查 RELRO，后者换张图或换个抽帧位置就好。
     * 混成一句话就是"缩略图不可用"，而那正是本仓反复吃过亏的可观测性问题。
     */
    @Volatile
    var loadFailure: String? = null
        private set

    /**
     * 最近一次 native 错误文本（[extractFrame] 返回 <0 时有值）。
     * 必须是 `fun` 而非 `val`：JNI 导出符号是 `..._lastError`（方法名），
     * Kotlin 属性 getter 会生成 `getLastError` → 符号对不上 → UnsatisfiedLinkError。
     */
    external fun lastError(): String?

    fun ensureLoaded(): Boolean {
        loadState?.let { return it }
        synchronized(this) {
            loadState?.let { return it }
            val failure = runCatching { System.loadLibrary("ffmpeg_bridge") }.exceptionOrNull()
            loadState = failure == null
            if (failure == null) {
                AppLogger.i(TAG, "libffmpeg_bridge.so 已加载")
            } else {
                loadFailure = "${failure.javaClass.simpleName}: ${failure.message}"
                /*
                 * 只在这里记一次（双重检查锁内），**不是**每次请求都记。
                 *
                 * 原来这里是"静默降级"、一行日志都不打 —— 理由是不想刷屏，但代价是
                 * `.so` 加载失败时整条链路**零输出**：用户看到视频封面全空，
                 * 日志里什么都没有，只能靠猜。一次成功/一次失败是正确的最小粒度。
                 */
                AppLogger.e(TAG, "libffmpeg_bridge.so 加载失败，视频封面将全部不可用: $loadFailure")
            }
            return loadState == true
        }
    }

    /**
     * 抽一帧编码为 JPEG。
     * @param atSeconds 抽帧位置（秒）；<=0 由 native 侧取约 40% 处
     * @param maxSize 输出图最长边（保持比例，宽高取偶）
     * @return 0 = 成功；<0 = 失败码（配合 [lastError]）
     */
    external fun extractFrame(srcPath: String, dstPath: String, atSeconds: Double, maxSize: Int): Int

    /** 返回时长（秒）；<=0 表示未知。 */
    external fun probeDurationSeconds(srcPath: String): Double

    /**
     * 2026-10-05 G5：视频元信息探测（一次 open+find_stream_info 拿全部字段）。
     * 返回 JSON 文本（duration_s/width/height/codec/pix_fmt/bit_rate/fps_num/fps_den）；
     * 失败返回 null（原因见 [lastError]）。
     */
    external fun probeVideoInfo(srcPath: String): String?
}
