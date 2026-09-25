package com.ufi_axis_core.collector.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CPU 快照缓存的新鲜度判据（缺陷 B / 2026-09-25）。
 *
 * ## 这个文件为什么在 `:core:collector` 而不是 `:core`
 *
 * 原来这四条用例住在 `core/src/test/.../SystemCollectorTest.kt`（模块 `:core`），
 * 而被测代码在 `core/collector/src/main/`（模块 `:core:collector`）—— 跨模块，
 * 拿不到 `internal`，于是当时的写法是**把生产逻辑抄一份到测试里再断言抄本**：
 *
 *     val fresh = elapsed in 0 until 5_000L   // 抄本
 *     assertTrue(fresh)
 *
 * 后果是把生产实现改回 `elapsed < 5_000L`（缺陷 B 的原始写法、不判负差值）
 * 这四条**仍然全绿** —— 等于没有回归保护。
 *
 * 现在文件搬到与被测代码同模块，`isCpuSnapshotFresh` 也从 `private` 实例方法提到
 * `internal companion object`（纯函数、不碰实例状态、无行为变化），所以能直接断言真实实现。
 *
 * ## 判据本身
 *
 * `SystemCollector.getCpuInfo()` 的缓存命中条件是
 * `isCpuSnapshotFresh(SystemClock.elapsedRealtime(), cached.takenAt)`：
 * 正常经过 <5s 命中；>=5s 过期；**负差值一律判过期**（防御性兜底 —— 缺陷 B 就是
 * 用墙上时钟导致 NTP 回跳后差值恒负、恒命中、快照永久冻结）。
 */
class SystemCollectorCpuSnapshotFreshnessTest {

    @Test
    fun `正常经过 3s 判新鲜`() {
        val takenAt = 100_000L
        assertTrue(
            "3s 内应命中缓存",
            SystemCollector.isCpuSnapshotFresh(takenAt + 3_000L, takenAt)
        )
    }

    @Test
    fun `时钟回跳导致负差值时判过期`() {
        val takenAt = 100_000L
        // now < takenAt：这在 System.currentTimeMillis() 下现实存在（NTP 校时 / 用户改时间），
        // 也是缺陷 B 的根因。elapsedRealtime 理论上单调，但"不该出现"正是那个 bug 的写照。
        assertFalse(
            "负差值必须判过期（缺陷 B 的根因）",
            SystemCollector.isCpuSnapshotFresh(takenAt - 10_000L, takenAt)
        )
    }

    @Test
    fun `超过 5s 判过期`() {
        val takenAt = 100_000L
        assertFalse(
            "超过 5s 应过期",
            SystemCollector.isCpuSnapshotFresh(takenAt + 6_000L, takenAt)
        )
    }

    @Test
    fun `恰好 5s 判过期`() {
        val takenAt = 100_000L
        // until 不含右端：5000 已经不算新鲜
        assertFalse(
            "恰好 5s 边界应过期（until 不含右端）",
            SystemCollector.isCpuSnapshotFresh(takenAt + 5_000L, takenAt)
        )
    }

    @Test
    fun `差值为 0 判新鲜`() {
        val takenAt = 100_000L
        // 同一毫秒内连续两次取数：差值 0 在 [0, 5000) 内，应命中缓存。
        // 这条钉的是区间左端闭合 —— 若哪天改成 `elapsed > 0 until ...` 会红。
        assertTrue(
            "差值 0 应命中缓存（区间左端闭合）",
            SystemCollector.isCpuSnapshotFresh(takenAt, takenAt)
        )
    }
}
