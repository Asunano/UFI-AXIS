package com.ufi_axis_core.controller.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SmsCodeExtractor] 回归防线。
 *
 * 2026-09-08 之前「这条短信里的验证码是什么」有两份独立实现（邮件路径的
 * `MailTemplate.extractCode` 与入库路径的 `SmsController.extractCode`），判据不同，
 * 造成三个真实故障。这里为每个故障各钉一条用例，外加边界。
 *
 * 为什么这些用例值钱：邮件路径用提取结果**决定场景**（`verification` vs `sms`），
 * 而场景又决定邮件会不会被 `scenes` 开关拦掉。提取判据一旦漂移，表现是
 * 「验证码邮件静默不来」，从日志到界面都看不出原因。
 */
class SmsCodeExtractorTest {

    // ── 故障 1：关键词靠前时，被前面的无关数字截胡 ──
    // 两份旧实现都会返回 567890：邮件版没有位置约束；入库版虽有 ±20 窗口，
    // 但关键词在第 14 字，左边界被夹到 0，567890 照样落在窗口内且位置更靠前。
    @Test
    fun `picks the code nearest to the keyword, not the first number in text`() {
        val m = SmsCodeExtractor.find("您的余额 567890 元，验证码 1234，请勿泄露")
        assertEquals("1234", m?.code)
        assertEquals("验证码", m?.keyword)
    }

    // ── 故障 2：提示词集合互有缺失，导致邮件场景判错后被开关静默丢弃 ──
    @Test
    fun `recognises keywords that only the sms-side implementation had`() {
        assertEquals("1234", SmsCodeExtractor.find("【测试】确认码 1234，请勿泄露")?.code)
        assertEquals("确认码", SmsCodeExtractor.find("【测试】确认码 1234，请勿泄露")?.keyword)
        assertEquals("654321", SmsCodeExtractor.find("您的初始密码是 654321")?.code)
    }

    @Test
    fun `recognises keywords that only the mail-side implementation had`() {
        assertEquals("8899", SmsCodeExtractor.find("Your login code is 8899")?.code)
        assertEquals("445566", SmsCodeExtractor.find("Your OTP: 445566")?.code)
        assertEquals("2468", SmsCodeExtractor.find("您的口令 2468，请勿转发")?.code)
    }

    @Test
    fun `english keywords are matched case-insensitively`() {
        assertEquals("1357", SmsCodeExtractor.find("Verification CODE: 1357")?.code)
        assertEquals("1357", SmsCodeExtractor.find("your otp is 1357")?.code)
    }

    // ── 故障 3：入库路径只认 4/6 位，银行常见的 8 位验证码被漏收 ──
    @Test
    fun `accepts 4 to 8 digit codes`() {
        assertEquals("1234", SmsCodeExtractor.find("您的验证码是 1234")?.code)
        assertEquals("12345", SmsCodeExtractor.find("您的验证码是 12345")?.code)
        assertEquals("123456", SmsCodeExtractor.find("您的验证码是 123456")?.code)
        assertEquals("1234567", SmsCodeExtractor.find("您的验证码是 1234567")?.code)
        assertEquals("12345678", SmsCodeExtractor.find("您的验证码是 12345678")?.code)
    }

    // ── 关键词优先级：靠前的提示词先尝试 ──
    @Test
    fun `keyword priority follows declaration order`() {
        // 「验证码」在「密码」之前，所以取 2222 而不是 111111
        val m = SmsCodeExtractor.find("您的初始密码是 111111，验证码 2222")
        assertEquals("2222", m?.code)
        assertEquals("验证码", m?.keyword)
    }

    // ── 语序：数字在关键词之前也要能取到 ──
    @Test
    fun `code before the keyword is still found`() {
        assertEquals("4321", SmsCodeExtractor.find("4321 是您的验证码，5 分钟内有效")?.code)
    }

    // ── 同一个提示词出现多次时继续往后找 ──
    @Test
    fun `repeated keyword occurrences are all considered`() {
        assertEquals("8888", SmsCodeExtractor.find("验证码已失效，新的验证码 8888")?.code)
    }

    // ── 边界 ──

    @Test
    fun `no hint keyword means no code`() {
        assertNull(SmsCodeExtractor.find("您的余额 567890 元，请及时充值"))
    }

    @Test
    fun `blank body returns null`() {
        assertNull(SmsCodeExtractor.find(""))
        assertNull(SmsCodeExtractor.find("   "))
    }

    @Test
    fun `keyword without any digits returns null`() {
        assertNull(SmsCodeExtractor.find("您的验证码已过期，请重新获取"))
    }

    /**
     * 不从长数字串里截一段：9 位以上连续数字整体不匹配。
     * 否则「订单号 1234567890」会被当成验证码 `12345678`。
     */
    @Test
    fun `does not slice a code out of a longer digit run`() {
        assertNull(SmsCodeExtractor.find("您的验证码相关订单号 123456789"))
        assertNull(SmsCodeExtractor.find("验证码 1234567890"))
    }

    /**
     * 窗口约束真的生效：数字离关键词太远就不算它的验证码。
     * 这是防误报的地基 —— 去掉窗口就退化成「全文第一个数字」，即故障 1。
     */
    @Test
    fun `digits far outside the window are not treated as the code`() {
        val filler = "已过期请重新获取".repeat(4) // 32 字，远超 ±20 窗口
        assertNull(SmsCodeExtractor.find("验证码$filler 5678"))
    }

    @Test
    fun `digits just inside the window are accepted`() {
        val filler = "请稍候" // 3 字，窗口内
        assertEquals("5678", SmsCodeExtractor.find("验证码$filler 5678")?.code)
    }
}
