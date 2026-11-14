package com.ufi_axis_core.controller.carrier

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自动登录要求账号本身有可用的 11 位手机号；空号/脱敏号不应通过。 */
class CarrierPhoneTest {

    @Test
    fun `完整移动号可用`() {
        assertTrue(CarrierPhone.isMobile("13612345678"))
        assertTrue(CarrierPhone.isMobile("+86 136 1234 5678"))
    }

    @Test
    fun `空号不可用`() {
        assertFalse(CarrierPhone.isMobile(null))
        assertFalse(CarrierPhone.isMobile(""))
        assertFalse(CarrierPhone.isMobile("   "))
    }

    @Test
    fun `脱敏号不可用`() {
        assertFalse(CarrierPhone.isMobile("136****1614"))
    }

    @Test
    fun `位数不对或非移动号段不可用`() {
        assertFalse(CarrierPhone.isMobile("1361234567"))
        assertFalse(CarrierPhone.isMobile("12612345678"))
        assertFalse(CarrierPhone.isMobile("136123456789"))
    }
}
