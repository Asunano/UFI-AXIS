package com.ufi_axis.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * baseUrl 拼装与「有效主机」回落（2026-10-14）。
 *
 * 为什么值得单测：这两条是**所有**消费 host 的路径的唯一出口 —— Retrofit、WebSocket
 * （`ConnectionBootstrap.rewriteWsUrl` 的入参）、通知守护进程、文件流 / 上传 / 媒体直链
 * 全部从 `baseUrl` / `effectiveHost` 取。它们错了不会抛异常，只会拼出一个连不上的地址
 * （多一个斜杠、少一个端口、或域名被静默换成网关），真机上表现为"设备明明在线却连不上"。
 *
 * 覆盖 `nas.local` / `NAS.local` / `nas` / `192.168.0.1` 四种主机，外加"空 → 回落网关"。
 */
class BaseUrlAssemblyTest {

    // ── 1. 拼装：host 原样、端口原样、尾斜杠固定 ─────────────────────────

    @Test
    fun `域名与 IPv4 拼出的 baseUrl 形状一致`() {
        assertEquals("http://nas.local:8088/", buildBaseUrl("nas.local", 8088))
        assertEquals("http://192.168.0.1:8088/", buildBaseUrl("192.168.0.1", 8088))
    }

    @Test
    fun `大写域名不做归一（交给 URL 解析器）`() {
        // 主机名大小写不敏感，OkHttp / java.net.URL 自己会规范化；这里若擅自 lowercase，
        // 日志与界面上显示的主机就会和用户输入不一致，排障时对不上。
        assertEquals("http://NAS.local:8088/", buildBaseUrl("NAS.local", 8088))
    }

    @Test
    fun `单标签主机照常拼装`() {
        assertEquals("http://nas:8088/", buildBaseUrl("nas", 8088))
    }

    @Test
    fun `端口 80 不被省略`() {
        // 省略端口会让 URL 变成 `http://nas.local/`，而 core 的 ws 路径 / Retrofit 基址
        // 都按显式端口约定；省略后行为随 OkHttp 默认端口走，等于隐式改协议端点。
        assertEquals("http://nas.local:80/", buildBaseUrl("nas.local", 80))
    }

    @Test
    fun `尾斜杠恰好一个`() {
        // 尾斜杠是 Retrofit baseUrl 的硬要求，也是各直连点 `baseUrl + path` 的约定：
        // 少一个会丢路径段，多一个会拼出 `//api/...`。
        val url = buildBaseUrl("nas.local", 8088)
        assertTrue(url.endsWith("/"))
        assertFalse(url.endsWith("//"))
    }

    // ── 2. 有效主机：只判空，不判"像不像 IP" ────────────────────────────

    @Test
    fun `配了域名时有效主机就是域名`() {
        // 关键一条：域名绝不能被回落逻辑吃掉。历史上这里回落到 127.0.0.1 / 网关出过事故，
        // 而"看起来不像 IP 就回落"是同一类故障的另一种写法 —— 用户只看到"连不上"。
        assertEquals("nas.local", resolveEffectiveHost("nas.local", "192.168.0.1"))
        assertEquals("NAS.local", resolveEffectiveHost("NAS.local", "192.168.0.1"))
        assertEquals("nas", resolveEffectiveHost("nas", "192.168.0.1"))
        assertEquals("192.168.0.1", resolveEffectiveHost("192.168.0.1", "10.0.0.1"))
    }

    @Test
    fun `没配地址时才回落到网关`() {
        assertEquals("192.168.0.1", resolveEffectiveHost("", "192.168.0.1"))
        assertEquals("192.168.0.1", resolveEffectiveHost("   ", "192.168.0.1"))
    }

    @Test
    fun `回落目标是网关而不是 localhost`() {
        // 后端跑在设备自身，回落 127.0.0.1 会让 App 连自己（"前后端通信失败"的老诱因）。
        val host = resolveEffectiveHost("", "10.1.2.3")
        assertEquals("10.1.2.3", host)
        assertEquals("http://10.1.2.3:8088/", buildBaseUrl(host, 8088))
    }
}
