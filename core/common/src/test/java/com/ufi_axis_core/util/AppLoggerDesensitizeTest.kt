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
    fun `desensitize masks ticket in query string`() {
        // 2026-10-30 F6：/files/stream?ticket=… 与 /ws/terminal?ticket=… 的票据会进访问日志
        assertEquals("ticket***", AppLogger.desensitize("ticket=abc123"))
        assertEquals("/files/stream?ticket***&x=1", AppLogger.desensitize("/files/stream?ticket=abc123&x=1"))
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

    /**
     * 下划线前缀的凭据键（2026-11-14 安全审查）。
     *
     * 审查报告说"`\b` 让 `old_password` 完全不脱敏" —— 实测**不成立**：正则没有前导边界，
     * `old_password` 里的 `password` 照样命中，尾部的 `\b` 由后面的引号满足。
     * 真正漏掉的是**不在关键字表里**的键：`pairing_code` / `approval_code`
     * （6 位配对码 / 批准码，拿到就能配对新设备）。这条用例把两边都钉住。
     */
    @Test
    fun `下划线凭据键与配对码都要脱敏`() {
        val cases = listOf(
            """{"old_password":"OLDVALUE"}""",
            """{"new_password":"NEWVALUE"}""",
            """{"goform_password":"FORMVALUE"}""",
            """{"pairing_code":"123456"}""",
            """{"approval_code":"654321"}""",
        )
        for (c in cases) {
            val out = AppLogger.desensitize(c)
            val secret = Regex("\"([A-Za-z0-9]{6,})\"").findAll(c).last().groupValues[1]
            assertFalse("未脱敏：$c -> $out", out.contains(secret))
        }
    }

    /** 普通业务字段不能被误伤（`error_code` / `exit_code` 这类不是凭据）。 */
    @Test
    fun `普通 code 字段不脱敏`() {
        val out = AppLogger.desensitize("""{"error_code":"E1234","exit_code":"0"}""")
        assertTrue(out, out.contains("E1234"))
        assertTrue(out, out.contains("\"0\""))
    }
}
