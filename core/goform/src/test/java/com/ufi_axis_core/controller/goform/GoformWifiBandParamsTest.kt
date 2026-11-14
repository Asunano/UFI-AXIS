package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.deviceschema.WriteSpec
import com.ufi_axis_core.deviceschema.profile.ZteGoformProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「开 WiFi 要带当前频段」与「切频段」两条路径的逐字报文断言。
 *
 * ## 为什么值得有
 *
 * `switchWiFiChip&ChipEnum=X&GuestEnable=0` 的语义是**「在频段 X 上启用 WiFi」**
 * （2026-09-22 真机抓包）。于是「打开 WiFi」只要漏传当前频段，就会命中 profile 的兜底
 * `chip1`，把在 5G 上的设备静默切到 2.4G —— 用户实测过的真 bug。这里断言的正是
 * 「读到 chip2 就发 chip2 / 读失败退回 chip1 并留 WARN / 关分支一次都不读」。
 *
 * 读路径与日志出口用 lambda 替身注入（本类持有的是接口 [GoformTransport]，已经可以注入假对象，
 * 但这种写法不经过传输层就能断言），风格照 `GoformSettingWriterDecisionTest`。
 */
class GoformWifiBandParamsTest {

    private fun spec(key: SettingKey): WriteSpec =
        requireNotNull(ZteGoformProfile.writeSpec(key)) { "$key 未登记 WriteSpec" }

    /** 记录读路径被调了几次、日志出口收到了什么。 */
    private class FakeChipReader(private val chip: String?) {
        var reads = 0
        val warns = mutableListOf<String>()

        fun params(enabled: Boolean): Map<String, Any?> = runBlocking {
            GoformWifiClient.wifiEnableParams(
                enabled = enabled,
                readChip = { reads++; chip },
                warn = { warns += it },
            )
        }
    }

    private fun body(key: SettingKey, params: Map<String, Any?>): Map<String, String> =
        GoformSettingWriter.buildBody(spec(key), params)

    // ───────────── setWifiEnabled(true)：带上设备当前频段 ─────────────

    @Test
    fun `开 —— 设备在 5G 就发 chip2`() {
        val reader = FakeChipReader("chip2")
        val params = reader.params(enabled = true)
        assertEquals(mapOf("value" to true, "chip" to "chip2"), params)
        assertEquals("当前频段必须读且只读一次", 1, reader.reads)
        assertTrue("读到了就不该有 WARN", reader.warns.isEmpty())
        // 抓包原文：goformId=switchWiFiChip&isTest=false&ChipEnum=chip2&GuestEnable=0
        assertEquals(
            mapOf("goformId" to "switchWiFiChip", "ChipEnum" to "chip2", "GuestEnable" to "0"),
            body(SettingKey.WIFI_ENABLED, params)
        )
        assertNull("带上的频段必须过得了 validate", spec(SettingKey.WIFI_ENABLED).validate(params))
    }

    @Test
    fun `开 —— 设备在 2point4G 就发 chip1`() {
        val reader = FakeChipReader("chip1")
        val params = reader.params(enabled = true)
        assertEquals(mapOf("value" to true, "chip" to "chip1"), params)
        assertTrue(reader.warns.isEmpty())
        assertEquals(
            mapOf("goformId" to "switchWiFiChip", "ChipEnum" to "chip1", "GuestEnable" to "0"),
            body(SettingKey.WIFI_ENABLED, params)
        )
    }

    @Test
    fun `开 —— 读失败退回 chip1 并留 WARN`() {
        val reader = FakeChipReader(null)
        val params = reader.params(enabled = true)
        assertEquals(mapOf("value" to true, "chip" to "chip1"), params)
        assertEquals(1, reader.reads)
        // 不许静默：这条路径会把 5G 上的设备切到 2.4G
        val warn = reader.warns.singleOrNull()
        assertNotNull("读不到当前频段必须打 WARN", warn)
        assertTrue("WARN 要写明「没读到当前频段、按 2.4G 开」", warn!!.contains("没读到"))
        assertTrue(warn.contains("2.4G"))
        assertTrue(warn.contains("chip1"))
        assertEquals(
            mapOf("goformId" to "switchWiFiChip", "ChipEnum" to "chip1", "GuestEnable" to "0"),
            body(SettingKey.WIFI_ENABLED, params)
        )
    }

    @Test
    fun `开 —— 读到取值域外的值同样退回 chip1 并留 WARN`() {
        // 界面文案（2.4G / 5G）与读侧原始编码（0 / 1）都不是 ChipEnum 的合法取值：
        // 原样透传会被 profile 的 validate 拒掉，于是「打开 WiFi」整个动作失败。
        for (bogus in listOf("5G", "2.4G", "0", "1", "chip3", "")) {
            val reader = FakeChipReader(bogus)
            val params = reader.params(enabled = true)
            assertEquals("读到 $bogus 时必须退回 chip1", mapOf("value" to true, "chip" to "chip1"), params)
            assertEquals("读到 $bogus 时必须有 WARN", 1, reader.warns.size)
        }
    }

    // ───────────── setWifiEnabled(false)：不做任何多余读取 ─────────────

    @Test
    fun `关 —— 一次都不读当前频段，报文逐字未变`() {
        val reader = FakeChipReader("chip2")
        val params = reader.params(enabled = false)
        assertEquals(mapOf<String, Any?>("value" to false), params)
        assertEquals("switchWiFiModule 只认 SwitchOption，为「关」读频段是纯浪费", 0, reader.reads)
        assertTrue(reader.warns.isEmpty())
        // 抓包原文：goformId=switchWiFiModule&isTest=false&SwitchOption=0
        assertEquals(
            mapOf("goformId" to "switchWiFiModule", "SwitchOption" to "0"),
            body(SettingKey.WIFI_ENABLED, params)
        )
    }

    // ───────────── setWifiBand：独立的切频段入口 ─────────────

    @Test
    fun `切频段发的是 switchWiFiChip 加 ChipEnum 与 GuestEnable`() {
        assertEquals(
            mapOf("goformId" to "switchWiFiChip", "ChipEnum" to "chip1", "GuestEnable" to "0"),
            body(SettingKey.WIFI_BAND, mapOf("value" to "chip1"))
        )
        assertEquals(
            mapOf("goformId" to "switchWiFiChip", "ChipEnum" to "chip2", "GuestEnable" to "0"),
            body(SettingKey.WIFI_BAND, mapOf("value" to "chip2"))
        )
    }

    @Test
    fun `切频段只收设备词汇，非法取值不下发`() {
        val spec = spec(SettingKey.WIFI_BAND)
        assertNull(spec.validate(mapOf("value" to "chip1")))
        assertNull(spec.validate(mapOf("value" to "chip2")))
        // 界面文案 / 读侧编码 / 缺参数（route 把缺失的 chip 传成空串）都必须被拒
        for (bogus in listOf("2.4G", "5G", "0", "1", "", " chip1", "chip3")) {
            assertNotNull("$bogus 不该被受理", spec.validate(mapOf("value" to bogus)))
        }
        assertNotNull("缺参数要拒", spec.validate(emptyMap()))
    }
}
