package com.ufi_axis_core.controller.carrier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SIM 运营商识别（2026-11-07）。
 *
 * 这些字符串直接决定「自动登录能不能开」「账号列表显示哪家」——
 * 一台设备的 `simOperatorName` 可能是 "CMCC" 也可能是 "中国移动"，两者都得认。
 */
class CarrierSimCatalogTest {

    @Test
    fun `号段优先 —— 四家的 MNC 都能认出来`() {
        assertEquals(CarrierKind.MOBILE, CarrierSimCatalog.of("46000", ""))
        assertEquals(CarrierKind.MOBILE, CarrierSimCatalog.of("46002", ""))
        assertEquals(CarrierKind.UNICOM, CarrierSimCatalog.of("46001", ""))
        assertEquals(CarrierKind.UNICOM, CarrierSimCatalog.of("46009", ""))
        assertEquals(CarrierKind.TELECOM, CarrierSimCatalog.of("46003", ""))
        assertEquals(CarrierKind.TELECOM, CarrierSimCatalog.of("46011", ""))
        assertEquals(CarrierKind.BROADNET, CarrierSimCatalog.of("46015", ""))
    }

    @Test
    fun `号段带后缀也能认（460001234567890 这种）`() {
        assertEquals(CarrierKind.MOBILE, CarrierSimCatalog.of("4600012345", ""))
    }

    @Test
    fun `名字兜底 —— 中文与英文别名都要认`() {
        assertEquals(CarrierKind.MOBILE, CarrierSimCatalog.of("", "中国移动"))
        assertEquals(CarrierKind.MOBILE, CarrierSimCatalog.of("", "CMCC"))
        assertEquals(CarrierKind.MOBILE, CarrierSimCatalog.of("", "China Mobile"))
        assertEquals(CarrierKind.UNICOM, CarrierSimCatalog.of("", "中国联通"))
        assertEquals(CarrierKind.UNICOM, CarrierSimCatalog.of("", "CUCC"))
        assertEquals(CarrierKind.TELECOM, CarrierSimCatalog.of("", "中国电信"))
        assertEquals(CarrierKind.TELECOM, CarrierSimCatalog.of("", "CTCC"))
        assertEquals(CarrierKind.BROADNET, CarrierSimCatalog.of("", "中国广电"))
        assertEquals(CarrierKind.BROADNET, CarrierSimCatalog.of("", "CBN"))
    }

    @Test
    fun `号段与名字冲突时以号段为准`() {
        // SIM 的号段是运营商写死的，比 ROM 填的名字可信。
        assertEquals(CarrierKind.MOBILE, CarrierSimCatalog.of("46000", "中国联通"))
    }

    @Test
    fun `不认识就返回 null —— 别把虚拟运营商猜成四家之一`() {
        assertNull(CarrierSimCatalog.of("", ""))
        assertNull(CarrierSimCatalog.of("46020", ""))
        assertNull(CarrierSimCatalog.of("", "Vodafone"))
        assertNull(CarrierSimCatalog.of("00101", "AT&T"))
    }

    @Test
    fun `展示名齐全`() {
        assertEquals("中国移动", CarrierSimCatalog.displayName(CarrierKind.MOBILE))
        assertEquals("中国联通", CarrierSimCatalog.displayName(CarrierKind.UNICOM))
        assertEquals("中国电信", CarrierSimCatalog.displayName(CarrierKind.TELECOM))
        assertEquals("中国广电", CarrierSimCatalog.displayName(CarrierKind.BROADNET))
        assertEquals("未知运营商", CarrierSimCatalog.displayName(null))
    }
}
