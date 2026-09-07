package com.ufi_axis_core.controller.goform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `goform_set_cmd_process` 表单体编码（计划书 4.7）。
 *
 * 这是 `:core:goform` 的第一个测试（陷阱 9：传输层此前完全没有测试）。
 * 覆盖计划书 4.7 的回归清单：`&` / `=` / `+` / 中文 / 注入尝试。
 *
 * 依赖：`testImplementation(libs.junit)`（见 core/goform/build.gradle.kts）。
 */
class GoformCodecFormBodyTest {

    private fun body(vararg params: Pair<String, String>) =
        GoformCodec.buildSetFormBody(params.toMap(), "ADVALUE")

    @Test
    fun `普通值原样拼接`() {
        assertEquals(
            "isTest=false&goformId=INDICATOR_LIGHT_SETTING&indicator_light_switch=1&AD=ADVALUE",
            body("goformId" to "INDICATOR_LIGHT_SETTING", "indicator_light_switch" to "1"),
        )
    }

    @Test
    fun `调用方自带的 isTest 不会重复出现`() {
        val b = body("isTest" to "false", "goformId" to "REBOOT_DEVICE")
        assertEquals("isTest=false&goformId=REBOOT_DEVICE&AD=ADVALUE", b)
        assertEquals(1, Regex("isTest=").findAll(b).count())
    }

    @Test
    fun `SSID 里的与号不会变成新参数`() {
        val b = body("goformId" to "setAccessPointInfo", "SSID" to "home&goformId=REBOOT_DEVICE")
        assertTrue(b.contains("SSID=home%26goformId%3DREBOOT_DEVICE"))
        // 注入的 goformId 必须只剩一个（真正的那个）
        assertEquals(1, Regex("&goformId=").findAll(b).count())
    }

    @Test
    fun `密码里的等号被编码`() {
        assertTrue(body("Password" to "a=b").contains("Password=a%3Db"))
    }

    @Test
    fun `base64 里的加号被编码而不是当成空格`() {
        // base64 结果里出现 + 很常见；不编码时设备侧会解成空格 → 密码存错
        val b = body("Password" to "YWJj+ZGVm==")
        assertTrue("加号必须编码", b.contains("Password=YWJj%2BZGVm%3D%3D"))
        assertFalse("不能出现裸加号", b.contains("YWJj+ZGVm"))
    }

    @Test
    fun `中文 SSID 按 UTF-8 百分号编码`() {
        // "家" = E5 AE B6
        assertTrue(body("SSID" to "家").contains("SSID=%E5%AE%B6"))
    }

    @Test
    fun `空格编码成加号`() {
        assertTrue(body("SSID" to "my wifi").contains("SSID=my+wifi"))
    }

    @Test
    fun `AD 固定拼在最后且不编码`() {
        assertTrue(GoformCodec.buildSetFormBody(mapOf("a" to "b"), "AB+CD").endsWith("&AD=AB+CD"))
    }
}
