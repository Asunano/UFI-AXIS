package com.ufi_axis.ui.components.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 混合采样节奏下的断档判定护栏：钉住「节奏变化不等于断档」。
 *
 * 存在理由：本 App 的采集节奏**在同一个窗口内部**就会跳变 —— DataScheduler.realtimeDelay 在
 * 有前端连接时约 3s、无连接时默认 60s，相差 20×，这是正常运行路径，不需要任何异常条件。
 * 于是一条序列的间距是双峰分布，而旧实现的门槛是**全局**中位数 × 3：中位数必然落进样本更多的
 * 那一峰，另一峰的每个间距都超标，**整段稀疏区被逐个切开**，用户看到满屏碎段。
 * 修法是把判据改成局部的（[chartGapLimitAt]）：每个间距只跟自己邻域的节奏比，
 * 因为"真断档"与"节奏变化"的区别只存在于邻接关系里 —— 断档周围是密的，节奏变化周围一样疏。
 *
 * 旧实现在下面三个场景上分别切成 13 / 13 / 25 段。
 */
class UfiChartMixedRateGapTest {

    /** 从「每一步的间隔」构造序列：返回 steps.size + 1 个点。 */
    private fun seriesOf(steps: List<Long>): List<UfiDownsampledPoint> {
        var t = 0L
        val out = ArrayList<UfiDownsampledPoint>()
        out += UfiDownsampledPoint(t = 0L, min = 0.0, max = 1.0, avg = 0.5)
        for (s in steps) {
            t += s
            out += UfiDownsampledPoint(t = t, min = 0.0, max = 1.0, avg = 0.5)
        }
        return out
    }

    /**
     * 先密后疏：前 40 个间距 60s，后 12 个间距 300s（前端断开连接后轮询降频就是这个形状）。
     *
     * 旧实现：全局中位数 = 60s → 门槛 180s → 后面 12 个间距个个超标 → 13 段。
     * 现在：稀疏区内部的邻域全是 300s → 门槛 900s → 不断；切换处那一个过渡间距的 5 元窗口是
     * 2 个 60s + 3 个 300s，中位数仍是 300s → 也不断。整条 1 段。
     */
    @Test
    fun `先密后疏时稀疏段不应被切碎`() {
        val points = seriesOf(List(40) { 60_000L } + List(12) { 300_000L })
        val segments = chartSegments(points, bucketMs = 15_000L)
        assertEquals("整条应是一段", 1, segments.size)
        assertEquals(listOf(0..52), segments)
    }

    /**
     * 先疏后密：设备重新被连上、采集提频。旧实现此时中位数落在密集侧，前面整段被切碎（13 段）。
     * 局部判据下前段的邻域全是 300s，门槛 900s，同样不断。
     */
    @Test
    fun `先疏后密时前段不应被切碎`() {
        val points = seriesOf(List(12) { 300_000L } + List(40) { 60_000L })
        val segments = chartSegments(points, bucketMs = 15_000L)
        assertEquals("整条应是一段", 1, segments.size)
        assertEquals(listOf(0..52), segments)
    }

    /**
     * 节奏来回切换：8 轮「6 个 60s + 3 个 240s」，稀疏段每次只持续 3 个间距。
     * 旧实现 25 段（每个 240s 都被切开）—— 这是用户看到的莫名断层里最典型的一种。
     *
     * 现在 1 段，判据是可以直说的：**一次真实断档在 dt 序列里只产生 1 个大间距**
     * （整段停采只对应相邻两点之间的一个 dt），连续出现 3 个大间距只能是"恢复→记 1 点→又停"
     * 重复三次，那种数据本身就该按稀疏节奏理解。所以 240s 连着来 3 个时，它在
     * [CHART_GAP_WINDOW_RADIUS]=2 的 5 元窗口里已占多数 → 局部中位数落到 240s → 门槛 720s → 不断。
     *
     * 与 `UfiChartGapTest` 第 7 条互为两端：那条的离群只有 1 个，在 5 元窗口里永远是少数，
     * 必须断；这条的稀疏段有 3 个，占多数，必须不断。两条一起把窗口半径夹死在 2。
     */
    @Test
    fun `节奏来回切换时不应产生碎段`() {
        val steps = ArrayList<Long>()
        repeat(8) {
            repeat(6) { steps += 60_000L }
            repeat(3) { steps += 240_000L }
        }
        val points = seriesOf(steps)
        val segments = chartSegments(points, bucketMs = 15_000L)
        assertEquals("整条应是一段", 1, segments.size)
        assertEquals(listOf(0..72), segments)
    }
}
