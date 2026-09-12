package com.ufi_axis.installer.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 高度自适应日志面板：内容少时按内容高度，内容多时封顶 [android.R.attr.maxHeight] 后内部滚动。
 * 避免固定高度在矮屏挤压、在高屏浪费。
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private val maxHeight: Int

    init {
        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxHeight))
        maxHeight = a.getDimensionPixelSize(0, Int.MAX_VALUE)
        a.recycle()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val h = if (maxHeight < Int.MAX_VALUE) {
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        } else heightSpec
        super.onMeasure(widthSpec, h)
    }
}
