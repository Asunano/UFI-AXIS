package com.ufi_axis_core.contract

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 信号量值域（2026-11-09）：现场出现过 sinr=30,000,000 的固件异常值。 */
class SignalSanityTest {

    @Test
    fun `SINR 拒绝量级异常但保留负数`() {
        assertTrue(SignalSanity.isValid(DeviceFields.Signal.SINR, -10))
        assertTrue(SignalSanity.isValid(DeviceFields.Signal.SINR, 0))
        assertTrue(SignalSanity.isValid(DeviceFields.Signal.SINR, 30))
        assertFalse("现场固件异常值", SignalSanity.isValid(DeviceFields.Signal.SINR, 30_000_000))
        assertFalse("CellInfo.UNAVAILABLE", SignalSanity.isValid(DeviceFields.Signal.SINR, Int.MAX_VALUE))
        assertFalse("Int.MIN_VALUE", SignalSanity.isValid(DeviceFields.Signal.SINR, Int.MIN_VALUE))
    }

    @Test
    fun `RSRP RSRQ RSSI 的哨兵与越界值被拒绝`() {
        assertTrue(SignalSanity.isValid(DeviceFields.Signal.RSRP, -85))
        assertFalse(SignalSanity.isValid(DeviceFields.Signal.RSRP, 30_000_000))
        assertFalse(SignalSanity.isValid(DeviceFields.Signal.RSRP, 0))

        assertTrue(SignalSanity.isValid(DeviceFields.Signal.RSRQ, -11))
        assertFalse(SignalSanity.isValid(DeviceFields.Signal.RSRQ, -999))

        assertTrue(SignalSanity.isValid(DeviceFields.Signal.RSSI, -72))
        assertFalse(SignalSanity.isValid(DeviceFields.Signal.RSSI, -999))
    }

    @Test
    fun `NR LTE 的分制式 SNR 也按 SINR 区间校验`() {
        assertFalse(SignalSanity.isValid(DeviceFields.Signal.NR_SNR, 30_000_000))
        assertFalse(SignalSanity.isValid(DeviceFields.Signal.LTE_SNR, Int.MAX_VALUE))
        assertTrue(SignalSanity.isValid(DeviceFields.Signal.NR_SNR, 17))
        assertNull(SignalSanity.takeIfValid(DeviceFields.Signal.SINR, 30_000_000))
        assertTrue(SignalSanity.takeIfValid(DeviceFields.Signal.SINR, 17) == 17)
    }
}
