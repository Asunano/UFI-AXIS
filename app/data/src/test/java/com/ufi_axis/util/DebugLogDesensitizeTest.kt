package com.ufi_axis.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脱敏逻辑单测（对应 DebugLog.desensitize，internal 可见性，同模块 JVM 单测可直接调用）。
 *
 * 覆盖规则：
 *  - 大小写不敏感（TOKEN/token/Bearer/bearer 均命中）
 *  - 命中关键字后保留关键字前缀，仅把其后的值整体替换为 ***
 *  - JSON 形态 "token":"abc" → "token***"
 *  - 多命中全局替换
 *  - 空串 / 不含关键字内容原样返回
 *
 * 使用 JUnit 4 assertEquals。本文件与 AppLoggerDesensitizeTest 断言集合保持一致。
 */
class DebugLogDesensitizeTest {

    @Test
    fun `desensitize masks token equals value`() {
        assertEquals("token***", DebugLog.desensitize("token=abc123"))
    }

    @Test
    fun `desensitize masks Bearer space value`() {
        assertEquals("Bearer***", DebugLog.desensitize("Bearer xyz"))
    }

    @Test
    fun `desensitize masks password equals value`() {
        assertEquals("password***", DebugLog.desensitize("password=secret123"))
    }

    @Test
    fun `desensitize masks json token quoted value`() {
        assertEquals("\"token***\"", DebugLog.desensitize("\"token\":\"abc\""))
    }

    @Test
    fun `desensitize masks all multiple hits globally`() {
        assertEquals("secret***&token***", DebugLog.desensitize("secret=def&token=ghi"))
    }

    @Test
    fun `desensitize is case insensitive`() {
        assertEquals("TOKEN***", DebugLog.desensitize("TOKEN=UPPER"))
    }

    @Test
    fun `desensitize returns non sensitive text unchanged`() {
        assertEquals("hello world", DebugLog.desensitize("hello world"))
    }

    @Test
    fun `desensitize returns empty string unchanged`() {
        assertEquals("", DebugLog.desensitize(""))
    }

    @Test
    fun `desensitize masks mixed real log while preserving non sensitive text`() {
        val input = "login success Bearer eyJabc.def token=abc123 secret=xyz789 level=info"
        val expected = "login success Bearer*** token*** secret*** level=info"
        assertEquals(expected, DebugLog.desensitize(input))
    }

    /** T16 回归锁：真实 JSON 日志形态下凭据值不得出现在输出里，非敏感键值不受影响。 */
    @Test
    fun `desensitize does not leak credential value in json log`() {
        val out = DebugLog.desensitize("""{"token":"abc123","secret":"s3cr3t","port":8080}""")
        assertFalse("凭据原文泄漏: $out", out.contains("abc123"))
        assertFalse("凭据原文泄漏: $out", out.contains("s3cr3t"))
        assertTrue("非敏感键值被误吞: $out", out.contains("\"port\":8080"))
    }

    /**
     * T18 回归锁：`ApiErrorLogger` 曾自带第三份正则（`token\s*[=:]`），JSON 形态匹配不上，
     * 凭据明文会落到 api_error 日志文件。现在它必须复用 DebugLog 的同一份实现。
     */
    @Test
    fun `api error logger reuses shared desensitize for json and url forms`() {
        val json = ApiErrorLogger.redactAuth("""{"token":"abc123","secret":"s3cr3t","password":"p4ss"}""")
        assertFalse("凭据原文泄漏: $json", json.contains("abc123"))
        assertFalse("凭据原文泄漏: $json", json.contains("s3cr3t"))
        assertFalse("凭据原文泄漏: $json", json.contains("p4ss"))

        val url = ApiErrorLogger.redactAuth("http://192.168.0.1:8080/api/files/stream?token=abc123&path=/a")
        assertFalse("URL 中的凭据泄漏: $url", url.contains("abc123"))
        assertTrue("非敏感部分被误吞: $url", url.contains("/api/files/stream"))
    }
}
