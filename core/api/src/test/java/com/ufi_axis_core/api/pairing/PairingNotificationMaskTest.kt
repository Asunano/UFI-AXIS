package com.ufi_axis_core.api.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 掩码规则的单元测试（2026-10-15）。
 *
 * 这些断言看起来琐碎，但守的是一件很实在的事：**掩码漏了不会报错**。
 * 同一份信息会出现在 App 弹窗、外部通知（邮件/Webhook/系统通知）、事件中心三处 ——
 * 各写一遍迟早出现"弹窗里遮住了、邮件里漏出去了"，而那种泄漏只会静默发生。
 * 所以规则只实现一次（[PairingNotificationMask]），并用"输出里不许出现原文"来守它。
 */
class PairingNotificationMaskTest {

    @Test
    fun `IPv4 只留前两段`() {
        assertEquals("192.168.***.***", PairingNotificationMask.source("192.168.1.23"))
        assertEquals("10.0.***.***", PairingNotificationMask.source("10.0.255.255"))
        assertFalse(
            "完整地址绝不能出现在掩码结果里",
            PairingNotificationMask.source("192.168.1.23").contains("1.23")
        )
    }

    @Test
    fun `认不出来的来源一律显示为隧道`() {
        // IPv6 / 主机名 / 空值：认不出来就按最保守的展示处理，不把可能是出口地址的东西露出去
        assertEquals("隧道", PairingNotificationMask.source("2001:db8::1"))
        assertEquals("隧道", PairingNotificationMask.source(""))
        assertEquals("隧道", PairingNotificationMask.source(null))
        assertEquals("隧道", PairingNotificationMask.source("   "))
    }

    @Test
    fun `设备名留前两个字符`() {
        assertEquals("iP***", PairingNotificationMask.deviceName("iPhone 15"))
        assertEquals("客厅***", PairingNotificationMask.deviceName("客厅的电脑"))
        assertFalse(
            "原文不能出现",
            PairingNotificationMask.deviceName("iPhone 15").contains("hone")
        )
        assertFalse(
            "中文名的第三字起也不能出现",
            PairingNotificationMask.deviceName("客厅的电脑").contains("的电脑")
        )
    }

    @Test
    fun `短名字整体遮住 —— 遮了等于没遮比不遮更糟`() {
        assertEquals("***", PairingNotificationMask.deviceName("ab"))
        assertEquals("***", PairingNotificationMask.deviceName("电"))
    }

    @Test
    fun `空名字给一个可读的占位而不是空白`() {
        assertEquals("未命名设备", PairingNotificationMask.deviceName(""))
        assertEquals("未命名设备", PairingNotificationMask.deviceName(null))
    }

    @Test
    fun `指纹只留前四位`() {
        val fp = "ab12cd34ef56"
        assertEquals("ab12", PairingNotificationMask.fingerprintPrefix(fp))
        assertFalse(
            "前缀不足以还原身份",
            PairingNotificationMask.fingerprintPrefix(fp).contains("cd34")
        )
        assertEquals("", PairingNotificationMask.fingerprintPrefix(null))
    }
}
