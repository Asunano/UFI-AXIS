package com.ufi_axis.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「地址 + 端口」一次判定的行为（2026-10-14）。
 *
 * 为什么单独成一组：配对页与设置页「连接配置」现在**共用** [checkServerEndpoint]，
 * 它同时承担三件以前散在 UI 里的事 —— trim 归一、主机形状、端口范围。这三件错了都不会
 * 崩，只会让用户看到一句"格式不正确"或者一个悄悄换成 8080 的端口，所以只能靠单测钉住。
 *
 * 覆盖任务点名的四种主机形态（`nas.local` / `NAS.local` / `nas` / `192.168.0.1`），
 * 外加「粘贴」形态（带首尾空格、带 scheme/端口/路径）与端口边界。
 */
class ServerEndpointCheckTest {

    // ── 1. 域名与 IP 一视同仁 ────────────────────────────────────────────

    @Test
    fun `域名与 IPv4 都能通过并原样返回`() {
        listOf(
            "nas.local" to 8088,
            "NAS.local" to 8088,   // 大写不做归一：主机名大小写不敏感，改它只会制造不一致
            "nas" to 8088,         // 单标签（局域网 mDNS / hosts 里的常见写法）
            "192.168.0.1" to 8088,
        ).forEach { (host, port) ->
            val r = checkServerEndpoint(host, port.toString())
            assertEquals("应通过: $host", ServerEndpoint.Valid(host, port), r)
        }
    }

    @Test
    fun `端口原值返回而不是被改写`() {
        // 80 / 443 这类"默认端口"不许被省略或替换：core 的 baseUrl 永远是 host:port 形式。
        assertEquals(ServerEndpoint.Valid("nas.local", 80), checkServerEndpoint("nas.local", "80"))
        assertEquals(ServerEndpoint.Valid("nas.local", 65535), checkServerEndpoint("nas.local", "65535"))
        assertEquals(ServerEndpoint.Valid("nas.local", 1), checkServerEndpoint("nas.local", "1"))
    }

    // ── 2. trim 归一（只吃首尾，不动中间） ──────────────────────────────

    @Test
    fun `首尾空格被吃掉`() {
        // 这是「从文档 / 聊天里粘贴」最常见的形态：地址正确、只是多了空格。
        assertEquals(ServerEndpoint.Valid("nas.local", 8088), checkServerEndpoint("  nas.local  ", " 8088 "))
        assertEquals(ServerEndpoint.Valid("192.168.0.1", 8088), checkServerEndpoint("\t192.168.0.1\n", "8088"))
    }

    @Test
    fun `中间的空格仍然是格式错误`() {
        // trim 只处理首尾；`nas .local` / `192.168. 0.1` 是用户真的写错了，必须报错而不是修好。
        assertEquals(ServerEndpoint.InvalidHost, checkServerEndpoint("nas .local", "8088"))
        assertEquals(ServerEndpoint.InvalidHost, checkServerEndpoint("192.168. 0.1", "8088"))
    }

    @Test
    fun `只输空格算没填`() {
        assertEquals(ServerEndpoint.BlankHost, checkServerEndpoint("", "8088"))
        assertEquals(ServerEndpoint.BlankHost, checkServerEndpoint("   ", "8088"))
        assertEquals(ServerEndpoint.BlankHost, checkServerEndpoint("\t\n", "8088"))
    }

    // ── 3. 主机形状 ─────────────────────────────────────────────────────

    @Test
    fun `整段粘贴的 URL 不通过（不能静默截断）`() {
        // 用户可能把 `http://nas.local:8088/` 整条粘进地址框。这里**不**做自动拆解：
        // 静默截断会让人以为填的是 A、实际连的是 B。宁可当场报格式错误。
        listOf(
            "http://nas.local:8088/",
            "https://nas.local",
            "nas.local:8088",
            "192.168.0.1/24",
            "nas.local/api",
        ).forEach {
            assertEquals("应判非法: $it", ServerEndpoint.InvalidHost, checkServerEndpoint(it, "8088"))
        }
    }

    @Test
    fun `形似 IP 但不合法时不通过`() {
        listOf("256.1.1.1", "1.2.3", "192.168.0.256").forEach {
            assertEquals("应判非法: $it", ServerEndpoint.InvalidHost, checkServerEndpoint(it, "8088"))
        }
    }

    @Test
    fun `IPv6 字面量不通过（本轮明确不支持）`() {
        // 全仓的拼法都是 `"http://$host:$port/"`，IPv6 需要方括号，拼出来必然连不上。
        listOf("[::1]", "::1", "fe80::1").forEach {
            assertEquals("应判非法: $it", ServerEndpoint.InvalidHost, checkServerEndpoint(it, "8088"))
        }
    }

    // ── 4. 端口 ─────────────────────────────────────────────────────────

    @Test
    fun `端口非法与越界`() {
        listOf("", "  ", "0", "65536", "-1", "80a", "abc").forEach {
            assertEquals("应判非法端口: <$it>", ServerEndpoint.InvalidPort, checkServerEndpoint("nas.local", it))
        }
    }

    @Test
    fun `主机与端口同时错时先报主机`() {
        // 顺序固定：先看填了什么，再看端口 —— 否则用户会被"端口错"牵着去改一个本来就对的端口。
        assertEquals(ServerEndpoint.InvalidHost, checkServerEndpoint("bad host", "abc"))
    }

    // ── 5. 与 isValidHost 的关系（不重复实现，只是同一份规则） ──────────

    @Test
    fun `通过判定的主机一定也能过 isValidHost`() {
        listOf("nas.local", "NAS.local", "nas", "192.168.0.1").forEach {
            val r = checkServerEndpoint(it, "8088")
            assertTrue("应通过: $it", r is ServerEndpoint.Valid)
            assertTrue("与 isValidHost 口径应一致: $it", isValidHost((r as ServerEndpoint.Valid).host))
        }
    }
}
