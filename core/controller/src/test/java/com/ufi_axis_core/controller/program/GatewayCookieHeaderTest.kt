package com.ufi_axis_core.controller.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 程序 Gateway 的 cookie 头解析。
 *
 * 这段逻辑此前**零测试覆盖**，于是一次就藏了两个缺陷（2026-11-14 安全审查）：
 * ① 在"头 + 已读到的 body"整段里找 `cookie:` —— 请求体里的字样会被当成凭据；
 * ② 从 `cookie:` 处截取再要求值前是 `^`/`;`/`,` —— 只有一个 cookie 时永远判不过。
 */
class GatewayCookieHeaderTest {

    private val name = "__ufi_prog_dufs"

    @Test
    fun `只有一个 cookie 时也要认得`() {
        val head = "GET / HTTP/1.1\r\nHost: x\r\nCookie: $name=v1.123.abc\r\n\r\n"
        assertEquals("v1.123.abc", GatewayCookieHeader.find(head, name))
    }

    @Test
    fun `多个 cookie 里挑出目标`() {
        val head = "GET / HTTP/1.1\r\nCookie: other=1; $name=v1.9.ff; third=2\r\n\r\n"
        assertEquals("v1.9.ff", GatewayCookieHeader.find(head, name))
    }

    @Test
    fun `不匹配别的程序的 cookie`() {
        val head = "GET / HTTP/1.1\r\nCookie: __ufi_prog_openlist=v1.1.aa\r\n\r\n"
        assertNull(GatewayCookieHeader.find(head, name))
    }

    @Test
    fun `请求体里的 cookie 字样不算凭据`() {
        // readHead 会把已经读到的 body 一并返回：这里模拟"头里没有 cookie，body 里有"
        val head = "POST /x HTTP/1.1\r\nContent-Length: 40\r\n\r\ncookie: $name=v1.123.abc\r\n"
        assertNull("授权判定不能越出 header 块", GatewayCookieHeader.find(head, name))
    }

    @Test
    fun `没有 cookie 头返回 null`() {
        assertNull(GatewayCookieHeader.find("GET / HTTP/1.1\r\nHost: x\r\n\r\n", name))
    }

    @Test
    fun `空值不算匹配`() {
        assertNull(GatewayCookieHeader.find("GET / HTTP/1.1\r\nCookie: $name=\r\n\r\n", name))
    }
}
