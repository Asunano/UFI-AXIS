package com.ufi_axis.util

import org.junit.Test
import org.junit.Assert.*

class FormatUtilsTest {

    // ========== formatBytes ==========

    @Test
    fun `formatBytes - bytes`() {
        assertEquals("512 B", FormatUtils.formatBytes(512))
    }

    @Test
    fun `formatBytes - kilobytes`() {
        assertEquals("1.5 KB", FormatUtils.formatBytes(1536))
    }

    @Test
    fun `formatBytes - megabytes`() {
        assertEquals("1.0 MB", FormatUtils.formatBytes(1048576))
    }

    @Test
    fun `formatBytes - gigabytes`() {
        assertEquals("1.0 GB", FormatUtils.formatBytes(1073741824))
    }

    @Test
    fun `formatBytes - zero`() {
        assertEquals("0 B", FormatUtils.formatBytes(0))
    }

    // ========== formatPercent ==========

    @Test
    fun `formatPercent - normal value`() {
        assertEquals("45.2%", FormatUtils.formatPercent(45.2))
    }

    @Test
    fun `formatPercent - zero`() {
        assertEquals("0.0%", FormatUtils.formatPercent(0.0))
    }

    @Test
    fun `formatPercent - full`() {
        assertEquals("100.0%", FormatUtils.formatPercent(100.0))
    }

    // ========== formatTemperature ==========

    @Test
    fun `formatTemperature - normal`() {
        assertEquals("36.5°C", FormatUtils.formatTemperature(36.5))
    }

    @Test
    fun `formatTemperature - zero`() {
        assertEquals("0.0°C", FormatUtils.formatTemperature(0.0))
    }

    // ========== formatVoltage ==========

    @Test
    fun `formatVoltage - normal`() {
        assertEquals("3.85 V", FormatUtils.formatVoltage(3.85))
    }

    // ========== getSignalLevel ==========
    //
    // 2026-10-14：这一段（6 条用例）已**整体移除**，不是漏了。
    // `FormatUtils.getSignalLevel` 在 2026-10-11 措辞改正式时被删掉并迁到了
    // `com.ufi_axis.ui.components.common.signalLevelText`（`UfiSignalBars.kt`），
    // 而这里的用例没跟着走 —— 于是 `:app:testDebugUnitTest` 从那天起就编译不过
    // （调一个不存在的函数会让**整个测试任务**挂掉，不只是这一类）。
    // 覆盖已迁到函数真正所在的模块：`app/ui/src/test/.../SignalLevelTextTest.kt`，
    // 那里按现行措辞断言，并补上了五档的分界点。

    // ========== getSignalBars ==========

    @Test
    fun `getSignalBars - 5 bars`() {
        assertEquals(5, FormatUtils.getSignalBars(-75))
    }

    @Test
    fun `getSignalBars - 4 bars`() {
        assertEquals(4, FormatUtils.getSignalBars(-85))
    }

    @Test
    fun `getSignalBars - 3 bars`() {
        assertEquals(3, FormatUtils.getSignalBars(-95))
    }

    @Test
    fun `getSignalBars - 2 bars`() {
        assertEquals(2, FormatUtils.getSignalBars(-110))
    }

    @Test
    fun `getSignalBars - 1 bar`() {
        assertEquals(1, FormatUtils.getSignalBars(-120))
    }

    @Test
    fun `getSignalBars - null`() {
        assertEquals(0, FormatUtils.getSignalBars(null))
    }

    // ========== getBatteryStatus ==========

    @Test
    fun `getBatteryStatus - charging`() {
        assertEquals("充电中", FormatUtils.getBatteryStatus(50, true))
    }

    @Test
    fun `getBatteryStatus - full`() {
        assertEquals("电量充足", FormatUtils.getBatteryStatus(85, false))
    }

    @Test
    fun `getBatteryStatus - good`() {
        assertEquals("电量良好", FormatUtils.getBatteryStatus(60, false))
    }

    @Test
    fun `getBatteryStatus - low`() {
        assertEquals("电量偏低", FormatUtils.getBatteryStatus(30, false))
    }

    @Test
    fun `getBatteryStatus - critical`() {
        assertEquals("电量不足", FormatUtils.getBatteryStatus(10, false))
    }
}
