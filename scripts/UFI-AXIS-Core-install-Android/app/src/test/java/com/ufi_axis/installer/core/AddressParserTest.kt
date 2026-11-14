package com.ufi_axis.installer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址解析单元测试。
 *
 * 这些用例直接来自 bat 脚本的行为约定，属于「回归护栏」：
 * 脚本里怎么处理用户输入，App 就必须一模一样。
 */
class AddressParserTest {

    @Test
    fun `空输入回退到默认地址`() {
        assertEquals("192.168.0.1:5555", AddressParser.parse(null).hostPort)
        assertEquals("192.168.0.1:5555", AddressParser.parse("").hostPort)
        assertEquals("192.168.0.1:5555", AddressParser.parse("   ").hostPort)
    }

    @Test
    fun `未带端口时补 5555`() {
        val p = AddressParser.parse("192.168.0.1")
        assertEquals("192.168.0.1:5555", p.hostPort)
        assertEquals("192.168.0.1", p.host)
        assertEquals(5555, p.port)
    }

    @Test
    fun `保留用户指定的端口`() {
        val p = AddressParser.parse("10.0.0.7:6000")
        assertEquals("10.0.0.7:6000", p.hostPort)
        assertEquals("10.0.0.7", p.host)
        assertEquals(6000, p.port)
    }

    @Test
    fun `健康检查 URL 固定使用 8088 且不含端口后缀`() {
        val p = AddressParser.parse("10.0.0.7:6000")
        assertEquals("http://10.0.0.7:8088/health", p.healthUrl())
        // 关键：健康检查用的是纯 host，不能把 adb 端口 6000 带进去
        assertFalse(p.healthUrl().contains("6000"))
    }

    @Test
    fun `剥离引号与多余的 adb 命令前缀`() {
        // 用户经常直接从教程里复制 "adb connect 192.168.1.5"
        assertEquals("192.168.1.5:5555", AddressParser.parse("\"adb connect 192.168.1.5\"").hostPort)
        assertEquals("192.168.1.5:5555", AddressParser.parse("adb 192.168.1.5").hostPort)
    }

    @Test
    fun `端口非法时回退到默认端口`() {
        assertEquals(5555, AddressParser.parse("192.168.0.1:abc").port)
        // hostPort 必须跟着回落后的端口重建，原始输入只放进 warning
        assertEquals("192.168.0.1:5555", AddressParser.parse("192.168.0.1:abc").hostPort)
    }

    @Test
    fun `容错处理必须带出原因而不是静默替换`() {
        val badPort = AddressParser.parse("192.168.0.1:abc")
        assertTrue("端口非法要说明已回退", badPort.warning?.contains("abc") == true)

        val empty = AddressParser.parse("")
        assertTrue("空输入要说明用了默认地址", empty.warning?.contains("默认") == true)

        // 输入被原样采用时不应产生多余提示
        assertNull(AddressParser.parse("10.0.0.7:6000").warning)
    }

    @Test
    fun `裸 IPv6 判非法，方括号形式才被接受`() {
        // 裸 IPv6 分不清哪个冒号是端口分隔符，只能判非法让用户重填
        assertThrows(AddressParser.InvalidAddressException::class.java) {
            AddressParser.parse("fe80::1:5555")
        }
        assertThrows(AddressParser.InvalidAddressException::class.java) {
            AddressParser.parse("fe80::1")
        }

        val p = AddressParser.parse("[fe80::1]:5555")
        assertEquals("[fe80::1]", p.host)
        assertEquals(5555, p.port)
        assertEquals("[fe80::1]:5555", p.hostPort)
        // healthUrl 必须带方括号，否则不是合法 URL
        assertEquals("http://[fe80::1]:8088/health", p.healthUrl())

        // 不带端口时补默认端口
        assertEquals("[::1]:5555", AddressParser.parse("[::1]").hostPort)
    }

    @Test
    fun `地址中不允许有空格`() {
        assertEquals("192.168.0.1:5555", AddressParser.parse(" 192.168.0.1 ").hostPort)
        assertEquals("192.168.0.1:5555", AddressParser.parse("192.168 .0.1").hostPort)
    }
}
