package com.ufi_axis.ui.components.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 断档判定回归护栏。
 *
 * 存在理由：这条规则曾经以「桶宽 × 2.5」为基准，而桶宽只是绘图分辨率（可见区间 / 目标点数），
 * 比真实采集间隔细得多，结果几乎每个正常采样间隔都被判成断档，曲线被切成一堆单点段 ——
 * 用户看到的是满屏「短点」。这里钉住的是"正常采样节奏不断线、真长空洞才断线"这个契约，
 * 而不是某个具体系数。
 */
class UfiChartGapTest {

    private fun series(vararg tsMs: Long): List<UfiDownsampledPoint> =
        tsMs.map { UfiDownsampledPoint(t = it, min = 0.0, max = 1.0, avg = 0.5) }

    /** 等间隔 60s 采样、桶宽 15s（近 1h 窗口的真实取值）：整条必须是一段，一个点都不能被切出来。 */
    @Test
    fun `采样间隔远大于桶宽时不应断线`() {
        val points = series(*LongArray(40) { it * 60_000L })
        val segments = chartSegments(points, bucketMs = 15_000L)
        assertEquals(listOf(0..39), segments)
    }

    /** 单个采样晚了一拍（60s → 90s）属于正常抖动，不该断。 */
    @Test
    fun `单次采集延迟不应断线`() {
        val points = series(0, 60_000, 150_000, 210_000, 270_000, 330_000)
        val segments = chartSegments(points, bucketMs = 15_000L)
        assertEquals(listOf(0..5), segments)
    }

    /** 中间空了两小时（采集真的停了）：必须切成两段，且两段都还是连续折线，不是散点。 */
    @Test
    fun `真实长空洞应切成两段`() {
        val points = series(
            0, 60_000, 120_000, 180_000,
            7_380_000, 7_440_000, 7_500_000, 7_560_000
        )
        val segments = chartSegments(points, bucketMs = 15_000L)
        assertEquals(listOf(0..3, 4..7), segments)
    }

    /** bucketMs=0（旧 core 不回 bucket_ms）：无论间距多离谱都不断线。 */
    @Test
    fun `桶宽未知时完全不断线`() {
        val points = series(0, 60_000, 86_400_000, 86_460_000)
        assertEquals(listOf(0..3), chartSegments(points, bucketMs = 0L))
        assertEquals(Long.MAX_VALUE, chartGapLimit(points, bucketMs = 0L))
    }

    /** 点数 < 3 时只有 0~1 个间距，算不出中位数，宁可不断。 */
    @Test
    fun `点数过少时不做断档判定`() {
        assertEquals(Long.MAX_VALUE, chartGapLimit(series(0, 86_400_000), bucketMs = 15_000L))
        assertEquals(listOf(0..1), chartSegments(series(0, 86_400_000), bucketMs = 15_000L))
    }

    /** 采样比桶还密（服务端原样返回原始行）时，bucketMs*2 作为下限生效，判定不比"相邻桶"更敏感。 */
    @Test
    fun `采样比桶密时桶宽下限生效`() {
        val points = series(0, 1_000, 2_000, 3_000, 4_000)
        assertEquals(120_000L, chartGapLimit(points, bucketMs = 60_000L))
    }

    /**
     * 末段永不被丢：哪怕它只剩 1 个点，也必须作为独立段出现在结果里。
     *
     * 2026-09-04 排查「曲线画不到右端终点」时，「分段逻辑把只剩 1~2 点的末段丢了」是三个
     * 候选原因之一。这条钉住的就是"不是分段的问题"：切分只负责报告哪些点连续，
     * 末段 `segStart..lastIndex` 是无条件追加的。绘制层跳过 1 点段（一个点连不成线）是另一回事，
     * 那条序列的末点仍由 latestDots 单独画出来。
     */
    @Test
    fun `末段只剩一个点时仍作为独立段返回`() {
        // 前 4 点 60s 等间隔（中位间距 60s → 门槛 180s），最后一点隔了 1 小时
        val points = series(0, 60_000, 120_000, 180_000, 3_780_000)
        assertEquals(listOf(0..3, 4..4), chartSegments(points, bucketMs = 15_000L))
    }
}
