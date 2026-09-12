package com.ufi_axis.adbcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * 端口可达性探测的单测（纯 JVM，用 ServerSocket 模拟「端口开放 / 关闭」）。
 */
class PortProbeTest {

    @Test
    fun `端口开放时探测为可达并记录耗时`() {
        val server = ServerSocket(0)
        try {
            val port = server.localPort
            val r = PortProbe.probe("127.0.0.1", port, 2000)
            assertTrue("应可达", r.reachable)
            assertEquals(port, r.port)
            assertTrue("耗时应为非负", r.elapsedMs >= 0)
        } finally {
            server.close()
        }
    }

    @Test
    fun `端口关闭时探测为不可达且记录原因`() {
        // 先占一个端口再释放，确保此刻无人监听
        val s = ServerSocket(0)
        val port = s.localPort
        s.close()

        val r = PortProbe.probe("127.0.0.1", port, 1000)
        assertFalse("应不可达", r.reachable)
        assertEquals(port, r.port)
        assertNotNull("应记录错误原因", r.error)
    }

    @Test
    fun `probeAdb 与 probeHealth 均能命中监听端口`() {
        // 用一个临时端口同时验证两个便捷方法确实发起了探测
        val s = ServerSocket(0)
        val port = s.localPort
        try {
            assertTrue(
                "probeAdb 应可达",
                PortProbe.probeAdb("127.0.0.1", port = port, timeoutMs = 2000).reachable
            )
            assertTrue(
                "probeHealth 应可达",
                PortProbe.probeHealth("127.0.0.1", port = port, timeoutMs = 2000).reachable
            )
        } finally {
            s.close()
        }
    }
}
