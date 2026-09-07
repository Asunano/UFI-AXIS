package com.ufi_axis_core.controller.goform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * `SEND_SMS` 参数构造。
 *
 * 存在的理由：短信「能收不能发」的根因就在这张参数表上 —— 缺 `sms_time`、
 * `encode_type` 声明成 `0`（不在固件枚举里）却按 UCS2 编正文、少 `notCallback`。
 * 这三条都是**发不出去但读取毫无影响**的错误，只有断言才拦得住回退。
 */
class GoformSmsSendParamsTest {

    private fun params(number: String = "13800138000", message: String = "hello") =
        GoformSmsClient.buildSendParams(number, message, "26;09;01;23;19;39;+8")

    @Test
    fun `参数表与 ZTE 固件要求逐项对齐`() {
        val p = params()
        assertEquals("false", p["isTest"])
        assertEquals("SEND_SMS", p["goformId"])
        assertEquals("true", p["notCallback"])
        assertEquals("13800138000", p["Number"])
        assertEquals("-1", p["ID"])
        // 正文按 UCS2 编，encode_type 必须是 UNICODE；写 "0"/"2" 这类数值固件不认
        assertEquals("UNICODE", p["encode_type"])
        assertEquals("26;09;01;23;19;39;+8", p["sms_time"])
        // AD 由 GoformClient 统一追加，不能出现在参数表里（否则会被编码一次）
        assertFalse(p.containsKey("AD"))
    }

    @Test
    fun `正文是 UTF-16BE 裸 hex 无 BOM`() {
        // "hi" → 0068 0069
        assertEquals("00680069", GoformSmsClient.toUcs2Hex("hi"))
        // "你好" → 4F60 597D
        assertEquals("4f60597d", GoformSmsClient.toUcs2Hex("你好"))
        assertFalse("不能带 BOM", GoformSmsClient.toUcs2Hex("hi").startsWith("feff"))
    }

    @Test
    fun `sms_time 是两位补零的分号串加小时时区`() {
        // 1788276110000 = 2026-09-01T15:21:50Z → GMT+8 的 23:21:50
        val t = GoformSmsClient.formatSmsTime(FIXED_MILLIS, TimeZone.getTimeZone("GMT+8"))
        assertEquals("26;09;01;23;21;50;+8", t)
        assertEquals(7, t.split(";").size)
    }

    @Test
    fun `半小时制时区给小数 负偏移给负号`() {
        assertEquals("+5.5", GoformSmsClient.formatSmsTime(FIXED_MILLIS, TimeZone.getTimeZone("GMT+5:30")).split(";").last())
        assertEquals("-3", GoformSmsClient.formatSmsTime(FIXED_MILLIS, TimeZone.getTimeZone("GMT-3")).split(";").last())
    }

    @Test
    fun `日志里的号码不出现完整号码`() {
        val masked = GoformSmsClient.maskNumber("13800138000")
        assertTrue(masked.startsWith("138"))
        assertTrue(masked.endsWith("00"))
        assertFalse(masked.contains("13800138000"))
        assertEquals("***", GoformSmsClient.maskNumber("123"))
    }

    private companion object {
        /** 固定时刻，避免断言依赖运行时的当前时间：2026-09-01T15:21:50Z。 */
        const val FIXED_MILLIS = 1_788_276_110_000L
    }
}
