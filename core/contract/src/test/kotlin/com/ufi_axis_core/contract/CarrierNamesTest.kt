package com.ufi_axis_core.contract

import org.junit.Assert.assertEquals
import org.junit.Test

/** core 侧运营商展示名映射（2026-11-10 从 App 下沉，修 Web 显示 CMCC）。 */
class CarrierNamesTest {

    @Test
    fun `CMCC CUCC CTCC CBN 映射到中文`() {
        assertEquals("中国移动", CarrierNames.displayName("CMCC"))
        assertEquals("中国联通", CarrierNames.displayName("CUCC"))
        assertEquals("中国电信", CarrierNames.displayName("CTCC"))
        assertEquals("中国广电", CarrierNames.displayName("CBN"))
    }

    @Test
    fun `英文全称与已中文的值保持兼容`() {
        assertEquals("中国移动", CarrierNames.displayName("China Mobile"))
        assertEquals("中国联通", CarrierNames.displayName("CHN-UNICOM"))
        assertEquals("中国电信", CarrierNames.displayName("China Telecom"))
        assertEquals("中国移动", CarrierNames.displayName("中国移动"))
    }

    @Test
    fun `未知值原样返回，空白返回空串`() {
        assertEquals("ACME", CarrierNames.displayName(" ACME "))
        assertEquals("", CarrierNames.displayName(null))
        assertEquals("", CarrierNames.displayName("   "))
    }
}
