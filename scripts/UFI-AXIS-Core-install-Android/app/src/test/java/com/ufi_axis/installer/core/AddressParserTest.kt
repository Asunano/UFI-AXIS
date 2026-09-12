package com.ufi_axis.installer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 注意：hostPort 会保留原始字符串，这是刻意为之，便于日志回显用户输入
        assertEquals("192.168.0.1:abc", AddressParser.parse("192.168.0.1:abc").hostPort)
    }

    @Test
    fun `IPv6 形式取最后一个冒号作为端口分隔`() {
        val p = AddressParser.parse("fe80::1:5555")
        assertEquals("fe80::1", p.host)
        assertEquals(5555, p.port)
    }

    @Test
    fun `地址中不允许有空格`() {
        assertEquals("192.168.0.1:5555", AddressParser.parse(" 192.168.0.1 ").hostPort)
        assertEquals("192.168.0.1:5555", AddressParser.parse("192.168 .0.1").hostPort)
    }
}
