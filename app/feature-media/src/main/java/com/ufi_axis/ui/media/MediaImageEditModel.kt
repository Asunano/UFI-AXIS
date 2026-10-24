package com.ufi_axis.ui.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 图片编辑的**参数模型**（2026-10-21）。
 *
 * ## 为什么不是"每一步改一张位图"
 * 上一版每次旋转 / 翻转 / 裁剪都 `Bitmap.createBitmap(...)` 出一张新图：一张 2560px 的照片
 * 约 20MB，走三步就 60MB，撤销基本没戏（只能"重置"），调色滑块更是每拖一下就要重编码一次。
 *
 * 现在把编辑结果收敛成这份**纯数据**：
 * · 预览与导出**共用同一个绘制函数** [drawEdited]（只是画布大小不同），所以"预览对了导出却错了"
 *   这类问题从结构上消失；
 * · 撤销 / 重做存的就是这份 state（几十字节），步数上限可以放到 50 而不心疼内存；
 * · 调色 / 滤镜只是 ColorMatrix，拖动滑杆不重建任何位图。
 *
 * ## 坐标约定（改之前先读）
 * · [crop] 是**旋转后**那块画布上的归一化矩形（0..1）。用户的心智是"先摆正、再框选"，
 *   所以裁剪框长在旋转结果上，而不是原图上；
 * · [mosaicStrokes] 与 [watermark] 的坐标是**输出空间**（= 裁剪后的成品）归一化坐标；
 * · 两者都只存归一化值，与位图实际像素无关 —— 换导出尺寸不需要改任何 state。
 */
internal data class MediaImageEditState(
    /** 粗旋转：0 / 90 / 180 / 270。 */
    val rotation: Int = 0,
    /** 拉直微调（度，-15..15）。非 0 时**自动内缩**裁剪框，避免露出四角空白（见 [insetScale]）。 */
    val straighten: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** 裁剪（旋转后画布上的归一化矩形）。 */
    val crop: CropBox = CropBox.FULL,
    /** 裁剪比例锁：null = 自由；1 / 4:3 / 16:9 等。只影响交互，不影响导出。 */
    val ratio: Float? = null,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val filter: ImageFilter = ImageFilter.None,
    /** 马赛克笔画（输出空间归一化矩形，按绘制顺序叠加）。 */
    val mosaicStrokes: List<CropBox> = emptyList(),
    /** 马赛克笔刷粗细（相对输出短边的比例）。 */
    val mosaicSize: Float = 0.05f,
    val watermark: WatermarkSpec = WatermarkSpec(),
    /** 圆角（成品短边的比例，0 = 直角）。 */
    val cornerRadius: Float = 0f
) {
    /** 完全没动过：用于判断"另存"时是否要提示"什么都没改"。 */
    val isIdentity: Boolean
        get() = rotation == 0 && straighten == 0f && !flipH && !flipV &&
            crop == CropBox.FULL && brightness == 0f && contrast == 0f &&
            saturation == 0f && warmth == 0f && filter == ImageFilter.None &&
            mosaicStrokes.isEmpty() && watermark.text.isBlank() && cornerRadius == 0f
}

/** 归一化矩形（0..1）。[CropBox.FULL] 表示整张。 */
internal data class CropBox(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    companion object {
        val FULL = CropBox()
    }
}

/** 水印：单行文字 + 九宫格位置（0..8，左上 → 右下）。 */
internal data class WatermarkSpec(
    val text: String = "",
    /** 成品短边的比例，0.02 ~ 0.12 左右。 */
    val sizeRatio: Float = 0.05f,
    /** 九宫格：0 左上、4 正中、8 右下。 */
    val position: Int = 8,
    /** 白字带阴影是户外/深色图最稳的组合，所以不做取色器。 */
    val alpha: Float = 0.9f
)

/** 滤镜预设：全部用 ColorMatrix 表达，零额外依赖、零位图重建。 */
internal enum class ImageFilter(val label: String) {
    None("原图"),
    Mono("黑白"),
    Warm("暖调"),
    Cool("冷调"),
    Film("胶片"),
    Fade("褪色")
}

/** 导出档位：长边像素上限。0 = 用原图尺寸（受解码上限约束）。 */
internal enum class ExportSize(val label: String, val longEdge: Int) {
    Original("原图", 0),
    P2560("2560", 2560),
    P2048("2048", 2048),
    P1080("1080", 1080)
}

/** 导出格式。[quality] 只对 JPEG / WebP 有效。 */
internal enum class ExportFormat(val label: String, val ext: String, val hasQuality: Boolean) {
    Jpeg("JPEG", "jpg", true),
    Png("PNG", "png", false),
    Webp("WebP", "webp", true)
}

/**
 * 旋转 + 拉直后，画布的外接尺寸。
 *
 * 用一个统一函数算，避免"预览按一个尺寸、导出按另一个尺寸"这种最难查的错位。
 */
private fun rotatedExtent(srcW: Float, srcH: Float, angleDeg: Float): Pair<Float, Float> {
    val rad = Math.toRadians(angleDeg.toDouble())
    val c = abs(cos(rad)).toFloat()
    val s = abs(sin(rad)).toFloat()
    return (srcW * c + srcH * s) to (srcW * s + srcH * c)
}

/**
 * 拉直后**不露角**的居中等比内缩系数（0..1]。
 *
 * 一张 W×H 的图旋转 θ 后，中心同比例缩到 s 倍仍完全落在旋转后的区域内的条件是
 * `s·(W/2)·cosθ + s·(H/2)·sinθ ≤ W/2` 与对称的另一条；两条都满足时取最小那个 s。
 * 这就是常见的"拉直后自动裁掉空角"的做法（只裁掉必要的那一圈，不额外损失）。
 */
private fun insetScale(srcW: Float, srcH: Float, angleDeg: Float): Float {
    if (angleDeg % 360f == 0f) return 1f
    val rad = Math.toRadians((angleDeg % 360f).toDouble())
    val c = abs(cos(rad)).toFloat()
    val s = abs(sin(rad)).toFloat()
    val byW = srcW / (srcW * c + srcH * s)
    val byH = srcH / (srcW * s + srcH * c)
    return min(byW, byH).coerceIn(0.2f, 1f)
}

/**
 * 实际生效的裁剪框 = 用户框选的 ∩ 拉直内缩。
 *
 * 两者都是"旋转后画布"上的归一化矩形，内缩框是**居中**的，所以交集直接取即可。
 */
internal fun MediaImageEditState.effectiveCrop(srcW: Float, srcH: Float): CropBox {
    val inset = insetScale(srcW, srcH, straighten)
    if (inset >= 0.999f) return crop
    val insetBox = CropBox(
        left = (1f - inset) / 2f,
        top = (1f - inset) / 2f,
        right = 1f - (1f - inset) / 2f,
        bottom = 1f - (1f - inset) / 2f
    )
    return CropBox(
        left = max(crop.left, insetBox.left),
        top = max(crop.top, insetBox.top),
        right = min(crop.right, insetBox.right),
        bottom = min(crop.bottom, insetBox.bottom)
    ).let { if (it.width <= 0.01f || it.height <= 0.01f) insetBox else it }
}

/** 一次绘制的几何量：把 state + 源尺寸 换算成"画到画布上要用的那几个数"。 */
internal data class EditGeometry(
    /** 旋转后画布尺寸（归一化坐标的参照系）。 */
    val rotatedW: Float,
    val rotatedH: Float,
    /** 生效裁剪框（旋转后画布归一化）。 */
    val crop: CropBox,
    /** 总旋转角（粗旋转 + 拉直）。 */
    val angle: Float
)

internal fun MediaImageEditState.geometry(srcW: Float, srcH: Float): EditGeometry {
    val angle = rotation + straighten
    val (rw, rh) = rotatedExtent(srcW, srcH, angle)
    return EditGeometry(rw, rh, effectiveCrop(srcW, srcH), angle)
}

/**
 * 把"编辑后的**成品**"画满 (vw, vh) —— 预览与导出共用这一段。
 *
 * 调用方只需给出画布尺寸：预览传屏幕预览区大小、导出传目标位图大小。同一个函数 ⇒ 不可能
 * 出现"预览与导出不一致"。
 *
 * @param includeMosaic 内部递归用：画马赛克底板时传 false（底板里不该再有马赛克）。
 */
internal fun drawEdited(
    canvas: Canvas,
    source: Bitmap,
    state: MediaImageEditState,
    vw: Int,
    vh: Int,
    includeMosaic: Boolean = true,
    /**
     * 裁剪页签用：把"生效裁剪框"临时换成传进来的框。
     *
     * 裁剪时必须看到**整张（旋转后的）图**才知道框外面还剩什么；其它页签看的是成品。
     * 这是唯一一处"预览 ≠ 导出"的地方，而且是刻意的 —— 导出永远用 state 里的真实裁剪框。
     */
    cropOverride: CropBox? = null
) {
    if (vw <= 0 || vh <= 0) return
    val srcW = source.width.toFloat()
    val srcH = source.height.toFloat()
    val geo = state.geometry(srcW, srcH)
    val shownCrop = cropOverride ?: geo.crop
    val cropW = geo.rotatedW * shownCrop.width
    val cropH = geo.rotatedH * shownCrop.height
    if (cropW <= 0f || cropH <= 0f) return

    // 成品铺满画布：取两轴里更小的那个比例，保证不越界（宽高比由裁剪框决定，理论上两轴相等）
    val scale = min(vw / cropW, vh / cropH)
    val drawW = cropW * scale
    val drawH = cropH * scale
    val left = (vw - drawW) / 2f
    val top = (vh - drawH) / 2f
    // 裁剪框中心相对"旋转后画布中心"的偏移（像素），换算到画布坐标后要把这块挪到正中
    val dcx = (shownCrop.centerX - 0.5f) * geo.rotatedW
    val dcy = (shownCrop.centerY - 0.5f) * geo.rotatedH

    canvas.save()
    // 圆角裁剪：成品短边为基准，与导出完全一致
    val radius = state.cornerRadius * min(vw, vh)
    if (radius > 0.5f) {
        canvas.clipRect(left, top, left + drawW, top + drawH)
        val rp = android.graphics.Path().apply {
            addRoundRect(
                RectF(left, top, left + drawW, top + drawH),
                radius, radius,
                android.graphics.Path.Direction.CW
            )
        }
        canvas.clipPath(rp)
    }

    canvas.translate(left + drawW / 2f - dcx * scale, top + drawH / 2f - dcy * scale)
    canvas.scale(scale, scale)
    canvas.rotate(geo.angle)
    canvas.scale(if (state.flipH) -1f else 1f, if (state.flipV) -1f else 1f)
    canvas.translate(-srcW / 2f, -srcH / 2f)

    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(state.buildColorMatrix())
    }
    canvas.drawBitmap(source, 0f, 0f, paint)
    canvas.restore()

    if (includeMosaic && state.mosaicStrokes.isNotEmpty()) {
        drawMosaic(canvas, source, state, left, top, drawW, drawH)
    }
    if (state.watermark.text.isNotBlank()) {
        drawWatermark(canvas, state.watermark, left, top, drawW, drawH)
    }
}

/**
 * 马赛克：把"整张成品"先画进一张 1/[MOSAIC_BLOCK] 的小图，再按笔画把对应区域**放大**贴回来。
 *
 * 为什么不直接对像素做方块化：那样得先拿到成品像素（预览时拿不到）。这个做法对预览与导出
 * 完全一样，且只多一张很小的位图（成品 1/12 尺寸，2600px 的图也就 200×150 量级）。
 */
private const val MOSAIC_BLOCK = 12

private fun drawMosaic(
    canvas: Canvas,
    source: Bitmap,
    state: MediaImageEditState,
    left: Float,
    top: Float,
    drawW: Float,
    drawH: Float
) {
    val coarseW = (drawW / MOSAIC_BLOCK).roundToInt().coerceAtLeast(2)
    val coarseH = (drawH / MOSAIC_BLOCK).roundToInt().coerceAtLeast(2)
    val coarse = Bitmap.createBitmap(coarseW, coarseH, Bitmap.Config.ARGB_8888)
    val coarseCanvas = Canvas(coarse)
    // 底板里不再叠加马赛克与圆角，只画"成品本体"
    drawEdited(coarseCanvas, source, state, coarseW, coarseH, includeMosaic = false)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 关掉双线性过滤 = 放大后是硬边方块，这正是马赛克要的质感
        isFilterBitmap = false
    }
    val dst = RectF()
    state.mosaicStrokes.forEach { box ->
        dst.set(
            left + box.left * drawW,
            top + box.top * drawH,
            left + box.right * drawW,
            top + box.bottom * drawH
        )
        canvas.save()
        canvas.clipRect(dst)
        canvas.drawBitmap(coarse, null, RectF(left, top, left + drawW, top + drawH), paint)
        canvas.restore()
    }
    coarse.recycle()
}

/** 水印：白字 + 半透明黑描边（任何背景上都读得出来，比取色器省事也更稳）。 */
private fun drawWatermark(canvas: Canvas, mark: WatermarkSpec, left: Float, top: Float, w: Float, h: Float) {
    val short = min(w, h)
    val textSize = (short * mark.sizeRatio).coerceAtLeast(8f)
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        color = android.graphics.Color.WHITE
        alpha = (mark.alpha.coerceIn(0f, 1f) * 255).roundToInt()
        isFakeBoldText = true
        setShadowLayer(textSize * 0.12f, 0f, textSize * 0.06f, android.graphics.Color.BLACK)
    }
    val margin = short * 0.04f
    val textW = paint.measureText(mark.text)
    val fm = paint.fontMetrics
    val textH = fm.descent - fm.ascent
    val col = (mark.position % 3)
    val row = (mark.position / 3)
    val x = when (col) {
        0 -> left + margin
        1 -> left + (w - textW) / 2f
        else -> left + w - margin - textW
    }
    val y = when (row) {
        0 -> top + margin - fm.ascent
        1 -> top + (h - textH) / 2f - fm.ascent
        else -> top + h - margin - fm.descent
    }
    canvas.drawText(mark.text, x, y, paint)
}

/**
 * 调色 + 滤镜合成一张 [ColorMatrix]。
 *
 * 顺序：先滤镜（预设基线）→ 再用户微调（亮度 / 对比度 / 饱和度 / 色温）。
 * 反过来会让"调完再换滤镜"把用户的微调吃掉，而按这个顺序两者始终可叠加。
 */
internal fun MediaImageEditState.buildColorMatrix(): ColorMatrix {
    val result = ColorMatrix()
    filter.matrix()?.let { result.postConcat(it) }

    if (brightness != 0f) {
        // 平移整个通道：±100 灰阶
        val t = brightness.coerceIn(-1f, 1f) * 100f
        result.postConcat(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, t,
                    0f, 1f, 0f, 0f, t,
                    0f, 0f, 1f, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
    if (contrast != 0f) {
        // 以 128 为轴缩放：c = 1 + k（k ∈ -1..1 映射到 0..2）
        val c = 1f + contrast.coerceIn(-1f, 1f)
        val t = 128f * (1f - c)
        result.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
    if (saturation != 0f) {
        val s = 1f + saturation.coerceIn(-1f, 1f)
        val m = ColorMatrix()
        m.setSaturation(s)
        result.postConcat(m)
    }
    if (warmth != 0f) {
        // 色温：正 = 偏暖（加 R 减 B），负 = 偏冷
        val w = warmth.coerceIn(-1f, 1f) * 36f
        result.postConcat(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, w,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, -w,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
    return result
}

/** 滤镜预设的 ColorMatrix（null = 原图，不参与合成）。 */
private fun ImageFilter.matrix(): ColorMatrix? = when (this) {
    ImageFilter.None -> null
    ImageFilter.Mono -> ColorMatrix().apply { setSaturation(0f) }
    ImageFilter.Warm -> ColorMatrix(
        floatArrayOf(
            1.08f, 0f, 0f, 0f, 12f,
            0f, 1.02f, 0f, 0f, 4f,
            0f, 0f, 0.94f, 0f, -8f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    ImageFilter.Cool -> ColorMatrix(
        floatArrayOf(
            0.94f, 0f, 0f, 0f, -8f,
            0f, 1.0f, 0f, 0f, 0f,
            0f, 0f, 1.08f, 0f, 12f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    // 胶片：轻微提亮暗部 + 略降饱和 + 暖高光，靠一组固定系数，不做逐像素曲线
    ImageFilter.Film -> ColorMatrix(
        floatArrayOf(
            1.06f, 0.02f, 0f, 0f, 10f,
            0.02f, 1.02f, 0f, 0f, 8f,
            0f, 0.03f, 0.96f, 0f, 6f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    // 褪色：整体压低对比 + 抬黑位（模拟过曝旧照片）
    ImageFilter.Fade -> ColorMatrix(
        floatArrayOf(
            0.88f, 0f, 0f, 0f, 22f,
            0f, 0.88f, 0f, 0f, 22f,
            0f, 0f, 0.88f, 0f, 22f,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

/**
 * 导出：按 state 渲染成一张新位图。
 *
 * 尺寸：先按 [ExportSize.longEdge]（0 = 保持解码尺寸）算出目标长边，再按裁剪框的宽高比定另一条边 ——
 * 长边封顶，短边随之等比，不会出现"拉扁"。
 */
internal fun MediaImageEditState.exportBitmap(source: Bitmap, targetLongEdge: Int): Bitmap {
    val srcW = source.width.toFloat()
    val srcH = source.height.toFloat()
    val geo = geometry(srcW, srcH)
    val cropW = geo.rotatedW * geo.crop.width
    val cropH = geo.rotatedH * geo.crop.height
    val longEdge = if (targetLongEdge <= 0) max(cropW, cropH) else targetLongEdge.toFloat()
    val scale = longEdge / max(cropW, cropH)
    val outW = (cropW * scale).roundToInt().coerceAtLeast(1)
    val outH = (cropH * scale).roundToInt().coerceAtLeast(1)
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    drawEdited(Canvas(out), source, this, outW, outH)
    return out
}

/** 导出文件名：原名 + `_edited`，扩展名跟随所选格式（不再固定 jpg）。 */
internal fun editedFileName(originalName: String, format: ExportFormat): String {
    val base = originalName.substringBeforeLast('.', missingDelimiterValue = originalName)
    return "${base}_edited.${format.ext}"
}

/** 预览里"原图对比"用：把源图按 fit 画满画布（不做任何编辑）。 */
internal fun drawOriginalForCompare(canvas: Canvas, source: Bitmap, vw: Int, vh: Int) {
    val srcW = source.width.toFloat()
    val srcH = source.height.toFloat()
    if (srcW <= 0f || srcH <= 0f) return
    val scale = min(vw / srcW, vh / srcH)
    val dw = srcW * scale
    val dh = srcH * scale
    val left = (vw - dw) / 2f
    val top = (vh - dh) / 2f
    canvas.drawBitmap(
        source,
        null,
        RectF(left, top, left + dw, top + dh),
        Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    )
}

/** 供 UI 复用：把归一化裁剪框换算成"预览画布上的像素矩形"。 */
internal fun cropBoxToScreen(
    crop: CropBox,
    srcW: Float,
    srcH: Float,
    state: MediaImageEditState,
    vw: Float,
    vh: Float
): RectF {
    // 预览里"生效裁剪框"总是铺满画布（drawEdited 会把它居中放大），所以裁剪框自身就是整块画布；
    // 这里给的是**未生效**的用户框选区域在屏幕上的位置 —— 拉直内缩时会略小于画布。
    val geo = state.geometry(srcW, srcH)
    val eff = geo.crop
    val scaleX = vw / eff.width
    val scaleY = vh / eff.height
    return RectF(
        (crop.left - eff.left) * scaleX,
        (crop.top - eff.top) * scaleY,
        (crop.right - eff.left) * scaleX,
        (crop.bottom - eff.top) * scaleY
    )
}

/** 组合：旋转 90° 的整数倍归一化（避免 360 / -90 之类让矩阵走远路）。 */
internal fun Int.normalizedRotation(): Int = ((this % 360) + 360) % 360

/** 逻辑与 [Matrix] 相同的旋转角度（UI 侧显示用）。 */
internal fun MediaImageEditState.displayAngle(): Float = rotation.toFloat() + straighten
