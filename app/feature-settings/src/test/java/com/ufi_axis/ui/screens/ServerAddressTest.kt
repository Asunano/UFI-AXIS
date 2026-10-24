package com.ufi_axis.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「服务器地址」离线校验的正反面（2026-10-14）。
 *
 * 为什么值得单测：这一条判定决定用户能不能走进连接流程，但它**错了也不会崩** ——
 * 要么把合法域名挡在门外（用户只看到一句"格式不正确"），要么放进一个解析不了的名字
 * （用户只看到"设备不可达"）。两种都只能靠真机才能发现，所以在纯函数层把规则钉住。
 *
 * 分三组：
 *  1. 合法主机：IPv4 与域名（含单标签、大小写、边界长度）；
 *  2. 非法主机：**形似 IP 但不合法**、**形似域名但不合法**、**地址框不该出现的字符**；
 *  3. IPv4 原逻辑的回归：放宽域名时不能把 `0–255` 分段校验弄丢。
 */
class ServerAddressTest {

    // ── 1. 合法：IPv4 ──────────────────────────────────────────────

    @Test
    fun `合法 IPv4 通过`() {
        listOf("192.168.0.1", "10.0.0.1", "0.0.0.0", "255.255.255.255").forEach {
            assertTrue("应通过: $it", isValidHost(it))
        }
    }

    // ── 1. 合法：域名 ──────────────────────────────────────────────

    @Test
    fun `单标签主机名通过（局域网 nas localhost）`() {
        // 单标签是本次刻意放行的：`nas` / `localhost` 在局域网和 hosts 文件里都很常见，
        // 强行要求带点会把真实场景挡在门外。
        listOf("nas", "localhost", "router", "a").forEach {
            assertTrue("应通过: $it", isValidHost(it))
        }
    }

    @Test
    fun `常见域名通过`() {
        listOf(
            "nas.local",
            "my-device.lan",
            "a.b.c.d.example.com",
            "tplogin.cn",
            "router.local",
            "abc-123.router.lan",
        ).forEach { assertTrue("应通过: $it", isValidHost(it)) }
    }

    @Test
    fun `大写字母通过（主机名大小写不敏感）`() {
        // OkHttp / java.net.URL 自己会规范化主机名；这里拒绝大写只会制造
        // "看起来对却连不上"的假错误。
        listOf("NAS.local", "My-Router.LAN", "LOCALHOST").forEach {
            assertTrue("应通过: $it", isValidHost(it))
        }
    }

    @Test
    fun `标签 63 字符与总长 253 的边界通过`() {
        val label63 = "a".repeat(63)
        assertTrue(isValidHost(label63))
        assertTrue(isValidHost("$label63.$label63"))

        // 63 + 63 + 63 + 61 + 3 个点 = 253，恰好是 RFC 1035 的上限。
        val host253 = listOf(label63, label63, label63, "a".repeat(61)).joinToString(".")
        assertTrue("长度 ${host253.length}", isValidHost(host253))
    }

    // ── 2. 非法：空 / 形似 IP 但不合法 ─────────────────────────────

    @Test
    fun `空与空白不通过`() {
        listOf("", " ", "   ", "\t").forEach { assertFalse("不应通过: <$it>", isValidHost(it)) }
    }

    @Test
    fun `形似 IPv4 但分段越界或段数不对时不通过`() {
        // 关键一条：`256.1.1.1` / `1.2.3` 的字符集是"数字+点"，
        // 若走"单标签主机名"规则会被放过去，所以必须强制先过 IPv4 校验。
        listOf("256.1.1.1", "1.2.3", "1.2.3.4.5", "192.168.0.256", "999.999.999.999")
            .forEach { assertFalse("不应通过: $it", isValidHost(it)) }
    }

    // ── 2. 非法：形似域名但不合法 ─────────────────────────────────

    @Test
    fun `标签首尾连字符与连续点不通过`() {
        listOf("-bad.com", "bad-.com", "a..b", "a...b", ".nas", "nas.", "-").forEach {
            assertFalse("不应通过: $it", isValidHost(it))
        }
    }

    @Test
    fun `含空白或非法字符不通过`() {
        listOf(
            "has space",
            " nas.local",     // 首尾空格不做静默修正：调用方负责 trim
            "nas.local ",
            "nas .local",
            "_nas.local",     // 下划线不是合法主机名字符（见 isValidHost 规则 5）
            "_tcp",
        ).forEach { assertFalse("不应通过: <$it>", isValidHost(it)) }
    }

    @Test
    fun `地址框不该带 scheme 路径或端口`() {
        // 端口是另一个输入框、scheme 由代码固定；这些输入若放过去，
        // 拼出来的是 `http://http://1.2.3.4:8088/` 这类必然失败的地址。
        listOf(
            "http://1.2.3.4",
            "https://nas.local",
            "1.2.3.4:8088",
            "nas.local:8088",
            "192.168.0.1/24",
            "nas.local/api",
        ).forEach { assertFalse("不应通过: $it", isValidHost(it)) }
    }

    @Test
    fun `IPv6 字面量明确不支持`() {
        // 本轮刻意不支持：App 里全是 `"http://$host:$port/"` 字符串拼接，
        // IPv6 在 URL 里必须写成 `http://[::1]:8088/`，拼接会坏成 `http://::1:8088/`。
        // 与其"能过校验但连不上"，不如在校验层就拒绝（错误文案说明不支持）。
        listOf("[::1]", "::1", "fe80::1", "[fe80::1]").forEach {
            assertFalse("不应通过: $it", isValidHost(it))
        }
    }

    @Test
    fun `超长主机与超长标签不通过`() {
        val label64 = "a".repeat(64)
        assertFalse(isValidHost(label64))

        val label63 = "a".repeat(63)
        // 63 + 63 + 63 + 62 + 3 个点 = 254，超总长上限
        val host254 = listOf(label63, label63, label63, "a".repeat(62)).joinToString(".")
        assertFalse("长度 ${host254.length}", isValidHost(host254))
    }

    // ── 3. IPv4 原逻辑回归 ────────────────────────────────────────

    @Test
    fun `IPv4 仍然逐段校验 0 到 255`() {
        // 放宽域名的同时必须保住原规则：这是 UID-011 那条修复的实质内容，
        // 只匹配正则不校验分段的话 `999.1.1.1` 会被当成合法地址。
        assertTrue(isValidIpv4("192.168.0.1"))
        assertTrue(isValidIpv4("255.255.255.255"))
        assertFalse(isValidIpv4("256.1.1.1"))
        assertFalse(isValidIpv4("1.2.3"))
        assertFalse(isValidIpv4("nas.local"))
        assertFalse(isValidIpv4("1.2.3.4.5"))
    }
}
