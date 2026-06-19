package com.ufi_axis_core.collector.system

import org.junit.Test
import org.junit.Assert.*

class SystemCollectorTest {

    @Test
    fun `test CpuInfo creation`() {
        val cpuInfo = CpuInfo(
            usage = 45.2f,
            cores = listOf(CpuCore(id = 0, freq_mhz = 1800))
        )
        assertEquals(45.2f, cpuInfo.usage)
        assertEquals(1, cpuInfo.cores.size)
        assertEquals(0, cpuInfo.cores[0].id)
        assertEquals(1800L, cpuInfo.cores[0].freq_mhz)
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
