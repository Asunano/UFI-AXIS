package com.ufi_axis.viewmodel

import com.ufi_axis.data.model.*
import org.junit.Test
import org.junit.Assert.*

class DashboardViewModelTest {

    @Test
    fun `test DashboardState default values`() {
        val state = DashboardState()
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertFalse(state.isOffline)
        assertNull(state.cpuInfo)
        assertNull(state.memoryInfo)
        assertNull(state.trafficRealtime)
        assertNull(state.signalInfo)
    }

    @Test
    fun `test DashboardState copy`() {
        val state = DashboardState()
        val cpuInfo = CpuInfo(
            usage_percent = 45.2,
            core_count = 4,
            cores = listOf(CpuCore(core = 0, freq_mhz = 1800.0, freq_display = "1.8 GHz"))
        )
        val updatedState = state.copy(cpuInfo = cpuInfo, isLoading = true)

        assertNotNull(updatedState.cpuInfo)
        assertEquals(45.2, updatedState.cpuInfo?.usage_percent ?: 0.0, 0.01)
        assertTrue(updatedState.isLoading)
        assertNull(updatedState.memoryInfo)
    }

    @Test
    fun `test CpuInfo creation`() {
        val cores = listOf(
            CpuCore(core = 0, freq_mhz = 1800.0, freq_display = "1.8 GHz"),
            CpuCore(core = 1, freq_mhz = 2400.0, freq_display = "2.4 GHz")
        )
        val cpuInfo = CpuInfo(usage_percent = 50.0, core_count = 2, cores = cores)
        assertEquals(50.0, cpuInfo.usage_percent, 0.01)
        assertEquals(2, cpuInfo.core_count)
        assertEquals(2, cpuInfo.cores.size)
    }

    @Test
    fun `test MemoryInfo creation`() {
        val memoryInfo = MemoryInfo(
            total = 4096,
            available = 2048,
            free = 1024,
            buffers = 512,
            cached = 1024,
            used = 2048,
            usage_percent = 50.0
        )
        assertEquals(4096, memoryInfo.total)
        assertEquals(2048, memoryInfo.used)
        assertEquals(2048, memoryInfo.available)
        assertEquals(50.0, memoryInfo.usage_percent, 0.01)
    }

    @Test
    fun `test TrafficRealtime creation`() {
        val traffic = TrafficRealtime(
            rx_speed = 1048576,
            tx_speed = 524288,
            rx_bytes = 10485760,
            tx_bytes = 5242880,
            rx_speed_display = "1.0 MB/s",
            tx_speed_display = "512.0 KB/s",
            timestamp = System.currentTimeMillis()
        )
        assertEquals(1048576, traffic.rx_speed)
        assertEquals(524288, traffic.tx_speed)
    }

    @Test
    fun `test SignalInfo creation`() {
        val signal = SignalInfo(rsrp = -85, sinr = 15, rsrq = -12, rat = "5G")
        assertEquals(-85, signal.rsrp)
        assertEquals(15, signal.sinr)
        assertEquals(-12, signal.rsrq)
        assertEquals("5G", signal.rat)
    }
}
