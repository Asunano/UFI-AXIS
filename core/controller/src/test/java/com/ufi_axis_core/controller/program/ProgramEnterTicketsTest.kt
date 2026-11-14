package com.ufi_axis_core.controller.program

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gateway 一次性入口票据（2026-11-10）。
 *
 * 这三条是安全边界：能重复用 = 票据等于永久口令；不绑程序 = 拿 A 的票进 B 的门；
 * 空票能过 = 公开入口直接裸奔。
 */
class ProgramEnterTicketsTest {

    @Test
    fun `票据只能消费一次`() {
        val token = ProgramEnterTickets.issue("openlist")
        assertTrue(ProgramEnterTickets.consume("openlist", token))
        assertFalse(ProgramEnterTickets.consume("openlist", token))
    }

    @Test
    fun `票据绑定程序`() {
        val token = ProgramEnterTickets.issue("openlist")
        assertFalse(ProgramEnterTickets.consume("ddns-go", token))
        // 上一条消费失败：票据必须还在，正确的程序仍能用
        assertTrue(ProgramEnterTickets.consume("openlist", token))
    }

    @Test
    fun `空票据和未知票据都拒绝`() {
        assertFalse(ProgramEnterTickets.consume("openlist", ""))
        assertFalse(ProgramEnterTickets.consume("openlist", "not-a-ticket"))
    }

    @Test
    fun `不同次签发得到不同票据`() {
        assertTrue(ProgramEnterTickets.issue("openlist") != ProgramEnterTickets.issue("openlist"))
    }
}
