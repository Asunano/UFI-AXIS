package com.ufi_axis_core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脱敏逻辑单测（对应 AppLogger.desensitize，internal 可见性，同模块 JVM 单测可直接调用）。
 *
 * 覆盖规则：
 *  - 大小写不敏感（TOKEN/token/Bearer/bearer 均命中）
 *  - 命中关键字后保留关键字前缀，仅把其后的值整体替换为 ***
 *  - JSON 形态 "token":"abc" → "token***"
 *  - 多命中全局替换
 *  - 空串 / 不含关键字内容原样返回
 *
 * 使用 JUnit 4 assertEquals。本文件与 DebugLogDesensitizeTest 断言集合保持一致。
 *
 * 2026-08-26（T16）：其中 2 个用例（json 引号形态 / & 分隔多命中）曾因主源码
 * AppLogger.SENSITIVE_PATTERN 的正则缺陷失败（前者会把凭据原文写进日志），当时按"不改主源码"
 * 的约定挂 @Ignore。现主源码已修复（正则与 DebugLog 对齐），@Ignore 已移除，断言未做任何削弱。
 */
class AppLoggerDesensitizeTest {

    @Test
    fun `desensitize masks token equals value`() {
        assertEquals("token***", AppLogger.desensitize("token=abc123"))
    }

    @Test
    fun `desensitize masks Bearer space value`() {
        assertEquals("Bearer***", AppLogger.desensitize("Bearer xyz"))
    }

    @Test
    fun `desensitize masks password equals value`() {
        assertEquals("password***", AppLogger.desensitize("password=secret123"))
    }

    @Test
    fun `desensitize masks json token quoted value`() {
        assertEquals("\"token***\"", AppLogger.desensitize("\"token\":\"abc\""))
    }

    @Test
    fun `desensitize masks all multiple hits globally`() {
        assertEquals("secret***&token***", AppLogger.desensitize("secret=def&token=ghi"))
    }

    @Test
    fun `desensitize is case insensitive`() {
        assertEquals("TOKEN***", AppLogger.desensitize("TOKEN=UPPER"))
    }

    @Test
    fun `desensitize returns non sensitive text unchanged`() {
        assertEquals("hello world", AppLogger.desensitize("hello world"))
    }

    @Test
    fun `desensitize returns empty string unchanged`() {
        assertEquals("", AppLogger.desensitize(""))
    }

    @Test
    fun `desensitize masks mixed real log while preserving non sensitive text`() {
        val input = "login success Bearer eyJabc.def token=abc123 secret=xyz789 level=info"
        val expected = "login success Bearer*** token*** secret*** level=info"
        assertEquals(expected, AppLogger.desensitize(input))
    }

    /** T16 回归锁：真实 JSON 日志形态下凭据值不得出现在输出里，非敏感键值不受影响。 */
    @Test
    fun `desensitize does not leak credential value in json log`() {
        val out = AppLogger.desensitize("""{"token":"abc123","secret":"s3cr3t","port":8080}""")
        assertFalse("凭据原文泄漏: $out", out.contains("abc123"))
        assertFalse("凭据原文泄漏: $out", out.contains("s3cr3t"))
        assertTrue("非敏感键值被误吞: $out", out.contains("\"port\":8080"))
    }
}
