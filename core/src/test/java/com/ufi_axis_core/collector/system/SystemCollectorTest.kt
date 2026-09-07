package com.ufi_axis_core.collector.system

import org.junit.Test
import org.junit.Assert.*

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
}
