package com.ufi_axis.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 运营商名展示（2026-11-07）。 */
class CarrierNamesTest {

    @Test
    fun `设备返回的英文缩写翻成中文`() {
        assertEquals("中国移动", CarrierNames.displayName("CMCC"))
        assertEquals("中国联通", CarrierNames.displayName("CUCC"))
        assertEquals("中国电信", CarrierNames.displayName("CTCC"))
        assertEquals("中国广电", CarrierNames.displayName("CBN"))
    }

    @Test
    fun `大小写与前后空格不影响`() {
        assertEquals("中国移动", CarrierNames.displayName(" cmcc "))
        assertEquals("中国联通", CarrierNames.displayName("Cucc"))
    }

    @Test
    fun `英文全称也认`() {
        assertEquals("中国移动", CarrierNames.displayName("China Mobile"))
        assertEquals("中国联通", CarrierNames.displayName("China Unicom"))
        assertEquals("中国电信", CarrierNames.displayName("China Telecom"))
        assertEquals("中国广电", CarrierNames.displayName("China BroadNet"))
    }

    @Test
    fun `已经是中文就原样返回`() {
        assertEquals("中国移动", CarrierNames.displayName("中国移动"))
        assertEquals("中国广电", CarrierNames.displayName("中国广电"))
    }

    @Test
    fun `认不出时原样返回，不吞掉信息`() {
        assertEquals("Vodafone", CarrierNames.displayName("Vodafone"))
        assertEquals("460 20", CarrierNames.displayName("460 20"))
        assertEquals("", CarrierNames.displayName(null))
        assertEquals("", CarrierNames.displayName("   "))
    }
}
