package com.ufi_axis.data.model

import com.google.gson.JsonParser
import org.junit.Test
import org.junit.Assert.*

class ModelsTest {

    // ========== CpuInfo ==========

    @Test
    fun `CpuInfo - create with all fields`() {
        val cores = listOf(
            CpuCore(core = 0, freq_mhz = 1800.0, freq_display = "1.8 GHz"),
            CpuCore(core = 1, freq_mhz = 2400.0, freq_display = "2.4 GHz")
        )
        val cpuInfo = CpuInfo(usage_percent = 45.2, core_count = 2, cores = cores)

        assertEquals(45.2, cpuInfo.usage_percent, 0.01)
        assertEquals(2, cpuInfo.core_count)
        assertEquals(2, cpuInfo.cores.size)
    }

    @Test
    fun `CpuInfo - empty cores`() {
        val cpuInfo = CpuInfo(usage_percent = 50.0, core_count = 4, cores = emptyList())
        assertEquals(50.0, cpuInfo.usage_percent, 0.01)
        assertEquals(4, cpuInfo.core_count)
        assertTrue(cpuInfo.cores.isEmpty())
    }

    // ========== MemoryInfo ==========

    @Test
    fun `MemoryInfo - create`() {
        val memoryInfo = MemoryInfo(
            total = 4096,
            used = 2048,
            available = 2048,
            free = 1024,
            buffers = 512,
            cached = 1024,
            usage_percent = 50.0
        )

        assertEquals(4096, memoryInfo.total)
        assertEquals(2048, memoryInfo.used)
        assertEquals(2048, memoryInfo.available)
        assertEquals(50.0, memoryInfo.usage_percent, 0.01)
    }

    // ========== TrafficRealtime ==========

    @Test
    fun `TrafficRealtime - create`() {
        val traffic = TrafficRealtime(
            rx_speed = 1048576,
            tx_speed = 524288,
            rx_bytes = 10485760,
            tx_bytes = 5242880,
            rx_speed_display = "1.0 MB/s",
            tx_speed_display = "512.0 KB/s",
            timestamp = 1234567890L
        )

        assertEquals(1048576, traffic.rx_speed)
        assertEquals(524288, traffic.tx_speed)
        assertEquals(10485760, traffic.rx_bytes)
        assertEquals(5242880, traffic.tx_bytes)
        assertEquals("1.0 MB/s", traffic.rx_speed_display)
        assertEquals("512.0 KB/s", traffic.tx_speed_display)
        assertEquals(1234567890L, traffic.timestamp)
    }

    // ========== SignalInfo ==========

    @Test
    fun `SignalInfo - create with all fields`() {
        val signal = SignalInfo(
            rsrp = -85,
            sinr = 15,
            rsrq = -12,
            rssi = -75,
            rat = "5G",
            operator = "China Mobile",
            network_registered = true
        )

        assertEquals(-85, signal.rsrp)
        assertEquals(15, signal.sinr)
        assertEquals(-12, signal.rsrq)
        assertEquals(-75, signal.rssi)
        assertEquals("5G", signal.rat)
        assertEquals("China Mobile", signal.operator)
        assertTrue(signal.network_registered ?: false)
    }

    @Test
    fun `SignalInfo - nullable fields`() {
        val signal = SignalInfo()
        assertNull(signal.rsrp)
        assertNull(signal.sinr)
        assertNull(signal.rsrq)
        assertNull(signal.rat)
    }

    // ========== BatteryInfo ==========

    @Test
    fun `BatteryInfo - create`() {
        val battery = BatteryInfo(
            level = 85,
            scale = 100,
            percent = 85,
            temperature = 36.5,
            voltage = 3.85,
            is_charging = false,
            plugged = "None"
        )

        assertEquals(85, battery.level)
        assertEquals(100, battery.scale)
        assertEquals(85, battery.percent)
        assertEquals(36.5, battery.temperature, 0.01)
        assertEquals(3.85, battery.voltage, 0.01)
        assertFalse(battery.is_charging)
        assertEquals("None", battery.plugged)
    }

    // ========== AlertConfig ==========

    @Test
    fun `AlertConfig - default values`() {
        val config = AlertConfig(
            enabled = true,
            temperatureWarning = 45.0,
            temperatureCritical = 55.0,
            batteryWarning = 20,
            batteryCritical = 10,
            trafficWarningMb = 1024,
            trafficCriticalMb = 2048,
            signalWarningRsrp = -100,
            signalCriticalRsrp = -115
        )

        assertTrue(config.enabled)
        assertEquals(45.0, config.temperatureWarning, 0.01)
        assertEquals(20, config.batteryWarning)
        assertEquals(1024, config.trafficWarningMb)
        assertEquals(-100, config.signalWarningRsrp)
    }

    // ========== WebSocketMessage ==========

    @Test
    fun `WebSocketMessage - create`() {
        val data = JsonParser.parseString("{\"usage_percent\": 45.2}").asJsonObject
        val message = WebSocketMessage(
            type = "cpu",
            data = data
        )

        assertEquals("cpu", message.type)
        assertEquals(45.2, message.data.asJsonObject.get("usage_percent").asDouble, 0.01)
    }
}
