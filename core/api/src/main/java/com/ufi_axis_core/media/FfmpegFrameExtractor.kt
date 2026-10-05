package com.ufi_axis_core.media

/**
 * FFmpeg 抽帧 JNI 入口（FFmpeg 接入计划书 G1）。
 *
 * 类名/方法签名必须与 libufi_ffmpeg.so 的导出符号严格一致：
 *   Java_com_ufi_axis_core_media_FfmpegFrameExtractor_extractFrame
 *   Java_com_ufi_axis_core_media_FfmpegFrameExtractor_probeDurationSeconds
 *   Java_com_ufi_axis_core_media_FfmpegFrameExtractor_lastError
 * （改包名/类名 = UnresolvedSymbol native crash，别动。）
 *
 * 加载策略（E2）：ensureLoaded() 失败（.so 缺失 / ABI 不符 / RELRO 残留）→
 * 返回 false，调用方**永久跳过** ffmpeg 2.5 级（进程内标记，不反复尝试重载），
 * 手机端抽帧兜底自动接管。首次接入期最大风险就是真机 RELRO——加载失败必须静默降级，
 * 绝不能让封面链路因 ffmpeg 崩掉。
 */
object FfmpegFrameExtractor {

    @Volatile
    private var loaded = false

    /** 加载结果。null = 尚未尝试；false = 已失败（永久跳过）；true = 可用。 */
    @Volatile
    var loadState: Boolean? = null
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
            val ok = runCatching { System.loadLibrary("ufi_ffmpeg") }.isSuccess
            loadState = ok
            return ok
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
