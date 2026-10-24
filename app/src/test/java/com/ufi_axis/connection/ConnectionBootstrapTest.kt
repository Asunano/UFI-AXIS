package com.ufi_axis.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * WS 地址改写（2026-10-14）。
 *
 * 为什么值得单测：`rewriteWsUrl` 是**唯一**把 baseUrl 变成 WebSocket 地址的地方
 * （主进程的 `/ws/realtime` 与 `:ufi_notify` 守护进程都走它）。它只做两件事 ——
 * 换 scheme、去掉尾斜杠 —— 而这两件事都只能错在"拼出一个连不上的 ws 地址"上：
 *   · 少了 trimEnd：`ws://nas.local:8088/` + `/ws/realtime` = `...:8088//ws/realtime`；
 *   · scheme 没换：OkHttp 会以 `http://` 建 WS 请求，core 侧路由直接不匹配。
 * 支持域名之后入参多了一种形状（主机名里带 `.` 与大小写），但改写逻辑必须与 IP 完全一致。
 */
class ConnectionBootstrapTest {

    @Test
    fun `域名 baseUrl 改写成 ws 且不留尾斜杠`() {
        // AppPreferences.baseUrl 永远带尾斜杠；这里必须把它吃掉，否则拼出的路径是 `//ws/...`。
        assertEquals(
            "ws://nas.local:8088",
            ConnectionBootstrap.rewriteWsUrl("http://nas.local:8088/")
        )
    }

    @Test
    fun `IPv4 与域名的改写结果结构一致`() {
        // 同一台设备换名字（192.168.0.1 ↔ nas.local）只应影响主机段：scheme 与端口逐字相同。
        assertEquals("ws://192.168.0.1:8088", ConnectionBootstrap.rewriteWsUrl("http://192.168.0.1:8088/"))
        assertEquals("ws://nas.local:8088", ConnectionBootstrap.rewriteWsUrl("http://nas.local:8088/"))
    }

    @Test
    fun `大写主机不被改写（大小写由 URL 解析器处理）`() {
        assertEquals("ws://NAS.local:8088", ConnectionBootstrap.rewriteWsUrl("http://NAS.local:8088/"))
    }

    @Test
    fun `https 走 wss`() {
        assertEquals("wss://nas.local:8443", ConnectionBootstrap.rewriteWsUrl("https://nas.local:8443/"))
    }

    @Test
    fun `拼上 ws 路径后不会出现双斜杠`() {
        // 复现真实调用点：MainActivity / NotifyService 都是
        // `WebSocketRepository(rewriteWsUrl(prefs.baseUrl), token)`，repo 内部再拼 "/ws/realtime"。
        val wsBase = ConnectionBootstrap.rewriteWsUrl("http://nas.local:8088/")
        val url = "$wsBase/ws/realtime"
        assertEquals("ws://nas.local:8088/ws/realtime", url)
        assertFalse(url.contains("//ws"))
    }
}
