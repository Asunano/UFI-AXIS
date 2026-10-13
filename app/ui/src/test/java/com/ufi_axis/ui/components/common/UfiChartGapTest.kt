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
     * 2026-10-12：桶宽与真实采集节奏**不同量级**时，门槛必须只看邻域节奏。
     *
     * 真机日志实测：`bucketMs=2520000`（42 分钟 = 可见区间/240 算出来的**绘图分辨率**）
     * 而真实采样间距 `360000`（6 分钟）—— 桶比真实节奏粗 7 倍。
     *
     * 旧实现 `max(bucketMs*2, localMedian*3)` = `max(84 分钟, 18 分钟)` = 84 分钟，
     * 于是**一段 30 分钟的空洞（相当于丢了 4~5 拍，明显不是抖动）会被连成直线** ——
     * 图在把没有采集的那段时间画成有数据。这正是用户看到的"断层"缺失。
     *
     * 新实现门槛 = `localMedian*3` = 18 分钟 → 30 分钟 > 18 分钟 → 正确断开。
     * 把 `bucketMs*2` 加回来，本条立刻红。
     *
     * 注意空洞长度是 30 分钟而不是 12 分钟：`localMedian*3` 的设计意图明确是
     * 「连漏两拍才算断，容得下一次采集晚一拍」，12 分钟（漏 1 拍）**本来就不该断**，
     * 那不是缺陷。判据必须是"明显长于本机采样节奏"才算空洞。
     */
    @Test
    fun `桶宽远大于采集节奏时不该把长空洞连成直线`() {
        val sixMin = 360_000L
        val gap30Min = 30 * 60_000L
        val points = series(
            0,
            sixMin, sixMin * 2, sixMin * 3, sixMin * 4, sixMin * 5, // 6 分钟节奏
            sixMin * 6 + gap30Min,                                  // 空洞 30 分钟
            sixMin * 7 + gap30Min,
            sixMin * 8 + gap30Min
        )
        val segs = chartSegments(points, bucketMs = 2_520_000L)
        assertEquals("30 分钟空洞（真实节奏 6 分钟）应被断开", 2, segs.size)
    }

    /**
     * 与上一条配对的反向护栏：**漏 1 拍不该断**。
     *
     * `localMedian*3` 就是「连漏两拍才算断」这条规则的编码，12 分钟（漏 1 拍）落在门槛内。
     * 这条钉住它不被后人调成 `*2` 或 `*1` —— 那会把正常的采集抖动全判成空洞，
     * 也就是 2026-09-26 修过的「满屏碎段」缺陷。
     */
    @Test
    fun `漏一拍不应被断开`() {
        val sixMin = 360_000L
        val points = series(
            0,
            sixMin, sixMin * 2, sixMin * 3, sixMin * 4,
            sixMin * 6,                // 漏 1 拍 → 12 分钟
            sixMin * 7, sixMin * 8
        )
        assertEquals("漏 1 拍属正常抖动，不该断", 1, chartSegments(points, bucketMs = 2_520_000L).size)
    }

    /**
     * 反方向：桶宽远**细于**采集节奏时，也不能把正常间隔判成空洞。
     *
     * 真机日志另一组：`bucketMs=21000` 而真实间距 `42000~63000`。旧的 `bucketMs*2`=42 秒
     * 在这里恰好不成为瓶颈（局部中位数更大），但它证明"桶宽与节奏不同量级"是常态，
     * 所以两个方向都要钉住：门槛只能来自邻域中位数，桶宽不参与。
     */
    @Test
    fun `桶宽远小于采集节奏时不应误断`() {
        val d = 60_000L
        val points = series(0, d, d * 2, d * 3, d * 4, d * 5, d * 6, d * 7)
        assertEquals("桶宽 20 秒、真实节奏 60 秒，不该断", 1, chartSegments(points, bucketMs = 20_000L).size)
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
