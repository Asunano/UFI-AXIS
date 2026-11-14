package com.ufi_axis_core.controller.carrier

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SIM 与账号手机号的匹配（2026-11-12）。
 *
 * 自动登录只应在「设备里的这张移动卡 = 当前账号」时出现；两侧号码都必须
 * 读得到完整 11 位并按后 11 位一致，否则视为不支持（用户 2026-11-13 明确要求）。
 */
class CarrierSimMatchTest {

    @Test
    fun `同号匹配`() {
        assertTrue(CarrierSimMatch.matches("13612345678", "13612345678"))
    }

    @Test
    fun `不同移动号不匹配`() {
        assertFalse(CarrierSimMatch.matches("13612345678", "13912345678"))
    }

    @Test
    fun `带国家码按后十一位匹配`() {
        assertTrue(CarrierSimMatch.matches("+86 136 1234 5678", "13612345678"))
    }

    @Test
    fun `任一侧读不到号码时判定不支持`() {
        assertFalse(CarrierSimMatch.matches("13612345678", null))
        assertFalse(CarrierSimMatch.matches(null, "13612345678"))
        assertFalse(CarrierSimMatch.matches("", ""))
        assertFalse(CarrierSimMatch.simReadable(null))
        assertFalse(CarrierSimMatch.simReadable("136****1614"))
    }

    @Test
    fun `过短的脱敏号判定不支持`() {
        // 136****1614 只剩 7 位数字，无法与完整号码可靠比对
        assertFalse(CarrierSimMatch.matches("136****1614", "13612341614"))
    }

    @Test
    fun `SIM 号码可读时要求后十一位一致`() {
        assertTrue(CarrierSimMatch.simReadable("+86 136 1234 5678"))
        assertFalse(CarrierSimMatch.matches("13612345678", "13912345678"))
    }
}
