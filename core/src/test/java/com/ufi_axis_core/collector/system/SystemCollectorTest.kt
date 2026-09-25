package com.ufi_axis_core.collector.system

import org.junit.Test
import org.junit.Assert.assertEquals


class SystemCollectorTest {

    @Test
    fun `test CpuInfo creation`() {
        val cpuInfo = CpuInfo(
            usage_percent = 45.2,
            core_count = 1,
            cores = listOf(CpuCore(core = 0, freq_mhz = 1800.0))
        )
        assertEquals(45.2, cpuInfo.usage_percent, 0.001)
        assertEquals(1, cpuInfo.cores.size)
        assertEquals(0, cpuInfo.cores[0].core)
        assertEquals(1800.0, cpuInfo.cores[0].freq_mhz, 0.001)
    }

    @Test
    fun `test MemoryInfo creation`() {
        val memoryInfo = MemoryInfo(
            used = 1024L * 1024 * 1024,
            total = 2048L * 1024 * 1024,
            available = 1024L * 1024 * 1024,
            free = 512L * 1024 * 1024,
            buffers = 128L * 1024 * 1024,
            cached = 256L * 1024 * 1024,
            usage_percent = 50.0
        )
        assertEquals(1024L * 1024 * 1024, memoryInfo.used)
        assertEquals(2048L * 1024 * 1024, memoryInfo.total)
    }

    @Test
    fun `test StorageInfo creation`() {
        val storageInfo = StorageInfo(
            used = 16L * 1024 * 1024 * 1024,
            total = 32L * 1024 * 1024 * 1024,
            available = 16L * 1024 * 1024 * 1024
        )
        assertEquals(16L * 1024 * 1024 * 1024, storageInfo.used)
        assertEquals(32L * 1024 * 1024 * 1024, storageInfo.total)
        assertEquals(16L * 1024 * 1024 * 1024, storageInfo.available)
    }

    // ── 缺陷 B：CPU 快照缓存的时钟回跳测试（2026-09-25 搬走）──
    //
    // 原来这里有四条 `isCpuSnapshotFresh 逻辑 - …` 用例，但它们把生产逻辑**抄一份**
    // 到测试里再断言抄本（`val fresh = elapsed in 0 until 5_000L`），从未调用
    // SystemCollector —— 于是把生产实现改回 `elapsed < 5_000L`（缺陷 B 的原始写法）
    // 那四条仍然全绿，等于没有回归保护。
    //
    // 根因是模块边界：本文件在 `:core`，被测代码在 `:core:collector`，跨模块拿不到
    // internal，所以当时留了一句「不值得为测试改生产签名」就抄了逻辑。
    //
    // 现在用例搬到了 `core/collector/src/test/.../SystemCollectorCpuSnapshotFreshnessTest.kt`
    // （与被测代码同模块），`isCpuSnapshotFresh` 也从 private 实例方法提到
    // internal companion object（纯函数、无行为变化），直接断言真实实现。
    // 本文件只保留三个数据类的构造用例。
}

