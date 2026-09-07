package com.ufi_axis.ui.components.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X 轴「时间 → x」映射的回归护栏。
 *
 * 存在理由（2026-09-04）：曲线按时间戳线性映射，而 X 轴刻度却是
 * `Row(SpaceBetween)` 把首点 / `points[size/2]` / 末点三个标签**等距**摊开，
 * 且刻度行的右外距（yGap）与绘图区右外距（yAxisWidth + yGap）不同。
 * 结果是曲线的几何位置与刻度所标时间系统性错开 —— 末点是 12:30，
 * 但它落在标着 12:20 的那个位置上。
 *
 * 这里钉住的是「刻度与曲线严格同源」这个契约：同一个映射函数、同一份
 * tMin/tSpan、同一个绘图区左边缘与宽度。
 */
class UfiChartAxisTest {

    private val tMin = 1_700_000_000_000L
    private val tSpan = 3_600_000L          // 1 小时
    private val plotLeft = CHART_PLOT_INSET_PX
    private val plotWidth = 600f

    /** 末点必须正好落在绘图区右边缘 —— 这是"画到终点"的定义。 */
    @Test
    fun `末点 x 等于绘图区右边缘`() {
        val x = chartXPx(tMin + tSpan, tMin, tSpan, plotLeft, plotWidth)
        assertEquals(plotLeft + plotWidth, x, 0.001f)
    }

    /** 首点必须正好落在绘图区左边缘。 */
    @Test
    fun `首点 x 等于绘图区左边缘`() {
        val x = chartXPx(tMin, tMin, tSpan, plotLeft, plotWidth)
        assertEquals(plotLeft, x, 0.001f)
    }

    /** 越界时间被夹在绘图区内，不会画到区外。 */
    @Test
    fun `越界时间被夹在绘图区内`() {
        assertEquals(plotLeft, chartXPx(tMin - tSpan, tMin, tSpan, plotLeft, plotWidth), 0.001f)
        assertEquals(
            plotLeft + plotWidth,
            chartXPx(tMin + tSpan * 2, tMin, tSpan, plotLeft, plotWidth),
            0.001f
        )
    }

    /**
     * 刻度与曲线同源：每个刻度时间过 [chartXPx] 得到的 x，必须等于它在时间轴上的等分位置。
     * 旧实现（SpaceBetween 等距摊开）只有在采样绝对均匀时才碰巧成立。
     */
    @Test
    fun `刻度时间与 x 严格同源`() {
        for (count in intArrayOf(2, 3, 5)) {
            val ticks = chartAxisTickTimes(tMin, tSpan, count)
            assertEquals(count, ticks.size)
            ticks.forEachIndexed { i, t ->
                val expected = plotLeft + plotWidth * i / (count - 1)
                val actual = chartXPx(t, tMin, tSpan, plotLeft, plotWidth)
                // 刻度时间是整数毫秒（tSpan * i / (n-1) 有截断），故留 1ms 折算成的像素余量
                assertEquals("tick #$i", expected, actual, plotWidth / tSpan.toFloat() + 0.001f)
            }
        }
    }

    /** 刻度首末必须就是 tMin / tMax，否则轴的两端在说谎。 */
    @Test
    fun `刻度首末等于时间边界`() {
        val ticks = chartAxisTickTimes(tMin, tSpan, 5)
        assertEquals(tMin, ticks.first())
        assertEquals(tMin + tSpan, ticks.last())
    }

    /**
     * 刻度取的是**时间中位**，不是下标中位。
     *
     * 采样密度不均（前半段密、后半段疏）时两者差得很远，这正是"中间那个标签对不上曲线"的来源。
     */
    @Test
    fun `刻度取时间中位而非下标中位`() {
        // 前 9 个点每 1 分钟一个，最后一个点在 1 小时处 —— 下标中位是第 5 个点（4 分钟处）
        val ts = LongArray(10) { i -> if (i < 9) tMin + i * 60_000L else tMin + tSpan }
        val indexMedian = ts[ts.size / 2]
        val timeMedian = chartAxisTickTimes(tMin, tSpan, 3)[1]
        assertEquals(tMin + tSpan / 2, timeMedian)
        assertNotEquals(indexMedian, timeMedian)
        // 下标中位落在时间轴不到 10% 的位置，却被旧实现摆在正中央
        assertTrue(chartXFraction(indexMedian, tMin, tSpan) < 0.1f)
        assertEquals(0.5f, chartXFraction(timeMedian, tMin, tSpan), 0.001f)
    }

    /**
     * 「同一时间在两处落在同一根竖线上」的代数形式：刻度行用的 plotLeft 比画布多一个左外距，
     * 而刻度行整体也被同一个左外距推开，故两者算出的绝对 x 相同。
     * 这条一旦被破坏（比如两处外距不一致），就回到本次修的那个 bug。
     */
    @Test
    fun `换算到行坐标系只相差左外距`() {
        val startPad = 34f      // yAxisWidth + yGap 的典型 px 值
        val t = tMin + tSpan / 3
        val onCanvas = chartXPx(t, tMin, tSpan, CHART_PLOT_INSET_PX, plotWidth)
        val onRow = chartXPx(t, tMin, tSpan, startPad + CHART_PLOT_INSET_PX, plotWidth)
        assertEquals(startPad, onRow - onCanvas, 0.001f)
    }

    /** 刻度个数按可用宽度退档：宽屏 5 个、中等 3 个、极窄只画首末。 */
    @Test
    fun `刻度个数按可用宽度退档`() {
        val labelW = 60f
        val gap = 8f
        assertEquals(5, chartAxisTickCount(5 * labelW + 4 * gap, labelW, gap))
        assertEquals(3, chartAxisTickCount(5 * labelW + 4 * gap - 1f, labelW, gap))
        assertEquals(3, chartAxisTickCount(3 * labelW + 2 * gap, labelW, gap))
        assertEquals(2, chartAxisTickCount(3 * labelW + 2 * gap - 1f, labelW, gap))
    }

    /** tSpan 退化成 1（只有一个时间点）时不能除零 / 不能画出区外。 */
    @Test
    fun `跨度退化时不越界`() {
        val x = chartXPx(tMin, tMin, 1L, plotLeft, plotWidth)
        assertTrue(x in plotLeft..(plotLeft + plotWidth))
        assertEquals(2, chartAxisTickTimes(tMin, 1L, 1).size)  // count 至少 2，避免除以 0
    }
}
