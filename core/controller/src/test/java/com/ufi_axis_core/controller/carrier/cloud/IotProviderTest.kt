package com.ufi_axis_core.controller.carrier.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 物联卡 provider 解析（2026-11-09）。
 *
 * 最关键的兼容点：2026-11-08 之前落盘的老账号没有 `iotProvider` 字段，
 * 反序列化后是空串，必须按云上卡处理，否则老用户一升级就"未知提供商"。
 */
class IotProviderTest {

    @Test
    fun `空白 provider 回落云上卡`() {
        assertEquals(IotProvider.CLOUD, IotProvider.of(null))
        assertEquals(IotProvider.CLOUD, IotProvider.of(""))
        assertEquals(IotProvider.CLOUD, IotProvider.of("  "))
    }

    @Test
    fun `已知 provider 大小写不敏感`() {
        assertEquals(IotProvider.CLOUD, IotProvider.of("cloud"))
        assertEquals(IotProvider.XJ, IotProvider.of("XJ"))
        assertEquals("小鲸卡", IotProvider.of("xj").displayName)
        assertTrue("小鲸卡必须带 verify", IotProvider.XJ.requiresVerify)
    }

    @Test
    fun `未知 provider 直接失败而不是静默落到云上卡`() {
        val e = runCatching { IotProvider.of("typo") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e?.message?.contains("typo") == true)
    }
}
