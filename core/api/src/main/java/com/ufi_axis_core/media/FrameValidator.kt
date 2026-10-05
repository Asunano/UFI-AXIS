package com.ufi_axis_core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 帧有效性统一判定（FFmpeg 接入计划书 G3 / §3.1）。
 *
 * ## 为什么需要它（修 F3/F4/F5）
 * 接入 ffmpeg 后 core 有三级帧来源（MMR / ffmpeg / 手机回传），历史上各自为政：
 * - MMR 级只查亮度阈值，**没有全零帧防护**（F3：10bit/HEVC Ma10p 实测会产出全零 JPEG）；
 * - PUT 回传只验 3 字节魔数，**1×1 黑 JPEG 也合法入库**（F4）；
 * - ffmpeg exit 0 只保证"解出了一帧"，不保证"帧有意义"（F5）。
 *
 * 四判据（合并 app 端 MediaThumbnailBuilder 的实测经验，core 侧补齐）：
 * 1. 字节数 > [MIN_BYTES]——1.6KB 是全零帧 JPEG 的实测大小（app 侧 :134 记录）；
 * 2. 能解码且宽高 ≥ [MIN_DIM]——防 1×1 占位图；
 * 3. 采样亮度 > 0——全零帧判据（YUV 缓冲未被解码器填充时编码出来就是全零 JPEG）；
 * 4. 亮度 ≥ [DARK_THRESHOLD] 或"多候选里最亮"——暗场电影给暗图可以，纯黑不行。
 *
 * 与 app 端 BLANK_LUMA/DARK_LUMA_THRESHOLD 同源（18），三端口径一致。
 */
object FrameValidator {

    /** 全零帧 JPEG 实测 ~1.6KB，2KB 以下基本没救。 */
    const val MIN_BYTES = 2048

    /** 1×1 占位图防线。 */
    const val MIN_DIM = 16

    /** 与 MediaRoutes.DARK_LUMA_THRESHOLD / app BLANK_LUMA 同一口径。 */
    const val DARK_THRESHOLD = 18

    /** 判定结果。调用方按分支决定"拒绝入库 / 降级为最亮候选 / 直接用"。 */
    sealed class Verdict {
        /** 有效，可直接使用。 */
        data object Valid : Verdict()

        /** 过暗但可作"最亮候选"兜底（多候选场景）。 */
        data class TooDark(val luma: Int) : Verdict()

        /** 全零帧 / 亮度过零：解码器没填充内容，绝不能用。 */
        data object Blank : Verdict()

        /** 尺寸不足：占位图。 */
        data object TooSmall : Verdict()

        /** 解不出图 / 字节数可疑。 */
        data object Undecodable : Verdict()
    }

    /**
     * 判定一份 JPEG 候选。
     * @param allowDark true = 暗图（非黑）也算 Valid；false = 暗图给 [Verdict.TooDark]
     *   （多候选场景由调用方挑最亮后再用一次 allowDark=true 复验）。
     */
    fun validate(jpeg: ByteArray?, allowDark: Boolean = true): Verdict {
        if (jpeg == null || jpeg.size < MIN_BYTES) return Verdict.Undecodable
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        if (opts.outWidth < MIN_DIM || opts.outHeight < MIN_DIM) return Verdict.TooSmall

        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return Verdict.Undecodable
        val luma = try { bmp.averageLuma() } finally { bmp.recycle() }
        return when {
            luma <= 0 -> Verdict.Blank
            luma < DARK_THRESHOLD && !allowDark -> Verdict.TooDark(luma)
            else -> Verdict.Valid
        }
    }

    /** 网格采样感知亮度——口径与 MediaRoutes.averageLuma 一致（2R+5G+B)/8，16² 采样。 */
    private fun Bitmap.averageLuma(): Int {
        val grid = 16
        val stepX = (width / grid).coerceAtLeast(1)
        val stepY = (height / grid).coerceAtLeast(1)
        var sum = 0L; var count = 0
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
}
