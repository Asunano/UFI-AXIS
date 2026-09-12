package com.ufi_axis.installer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.ufi_axis.installer.R
import kotlin.math.min

/**
 * 安装进度环形指示器：对齐 UFI-AXIS 主应用更新弹窗的 [UfiRingProgress] 视觉语言。
 *
 * - 116dp 圆环 + 中心百分比（确定进度时）；
 * - 无百分比的阶段（推送 / 安装）进入不确定态：以 1/4 圆弧持续旋转。
 * 颜色取自当前皮肤的 brand / divider / text_primary，自动跟随浅色 / 暗色。
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val strokeWidthPx: Float
    private val bounds = RectF()

    private var progress = 0
    private var indeterminate = false
    private var spin = 0f
    private var spinAnim: ValueAnimator? = null

    init {
        strokeWidthPx = dp(10f)
        trackPaint.strokeWidth = strokeWidthPx
        trackPaint.strokeCap = Paint.Cap.ROUND
        trackPaint.color = ContextCompat.getColor(context, R.color.divider)
        arcPaint.strokeWidth = strokeWidthPx
        arcPaint.strokeCap = Paint.Cap.ROUND
        arcPaint.color = ContextCompat.getColor(context, R.color.brand)
        textPaint.color = ContextCompat.getColor(context, R.color.text_primary)
        textPaint.textSize = sp(22f)
        textPaint.typeface = Typeface.DEFAULT_BOLD
    }

    fun setProgress(value: Int) {
        val clamped = value.coerceIn(0, 100)
        if (clamped != progress || indeterminate) {
            progress = clamped
            indeterminate = false
            stopSpin()
            invalidate()
        }
    }

    fun setIndeterminate(on: Boolean) {
        if (on == indeterminate) return
        indeterminate = on
        if (on) startSpin() else {
            stopSpin()
            invalidate()
        }
    }

    private fun startSpin() {
        if (spinAnim?.isRunning == true) return
        spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1100L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                spin = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopSpin() {
        spinAnim?.cancel()
        spinAnim = null
        spin = 0f
    }

    override fun onDetachedFromWindow() {
        stopSpin()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        val size = min(w, h).toFloat()
        if (size <= 0f) return
        val cx = paddingLeft + w / 2f
        val cy = paddingTop + h / 2f
        val radius = (size - strokeWidthPx) / 2f
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // 轨道
        canvas.drawCircle(cx, cy, radius, trackPaint)

        // 进度弧
        val sweep = if (indeterminate) 90f else progress / 100f * 360f
        val start = if (indeterminate) spin - 90f else -90f
        canvas.drawArc(bounds, start, sweep, false, arcPaint)

        // 中心百分比（仅确定进度时）
        if (!indeterminate) {
            val text = "$progress%"
            val fm = textPaint.fontMetrics
            val textY = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(text, cx, textY, textPaint)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
