package com.ufi_axis.installer.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 健康检查单元测试。
 *
 * 用真实的 [ServerSocket] 起一个迷你 HTTP 服务来验证，
 * 而不是 mock —— 因为这里最容易出错的恰恰是
 * 「HTTP 响应怎么读、关键字怎么判」这些真实 I/O 细节。
 */
class HealthCheckerTest {

    /** 起一个只回一次响应的迷你 HTTP 服务，返回其端口 */
    private fun serveOnce(body: String, status: String = "200 OK"): Int {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            try {
                server.accept().use { s ->
                    // 把请求读完（读到空行为止），再回响应
                    val reader = s.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val head = buildString {
                        append("HTTP/1.1 $status\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${bytes.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    s.getOutputStream().apply {
                        write(head.toByteArray(Charsets.UTF_8))
                        write(bytes)
                        flush()
                    }
                }
            } catch (_: Exception) {
                // 测试结束时的收尾异常可忽略
            } finally {
                try {
                    server.close()
                } catch (_: Exception) {
                }
            }
        }
        return server.localPort
    }

    @Test
    fun `响应同时含 status 与 ok 时判定为就绪`() {
        val port = serveOnce("""{"status":"ok"}""")
        val r = HealthChecker.checkOnce("127.0.0.1", port)
        assertTrue(r.ok)
        assertTrue(r.httpCode == 200)
    }

    @Test
    fun `关键字大小写不敏感`() {
        val port = serveOnce("""{"STATUS":"OK"}""")
        assertTrue(HealthChecker.checkOnce("127.0.0.1", port).ok)
    }

    @Test
    fun `缺少 ok 关键字时判定为未就绪`() {
        val port = serveOnce("""{"status":"starting"}""")
        assertFalse(HealthChecker.checkOnce("127.0.0.1", port).ok)
    }

    @Test
    fun `缺少 status 关键字时判定为未就绪`() {
        val port = serveOnce("""{"result":"ok"}""")
        assertFalse(HealthChecker.checkOnce("127.0.0.1", port).ok)
    }

    @Test
    fun `非 2xx 响应判定为未就绪`() {
        val port = serveOnce("""{"status":"ok"}""", status = "500 Internal Server Error")
        val r = HealthChecker.checkOnce("127.0.0.1", port)
        assertFalse(r.ok)
    }

    @Test
    fun `端口未监听时给出可读的中文提示`() {
        // 先占一个端口再释放，保证该端口当前没人监听
        val probe = ServerSocket(0)
        val port = probe.localPort
        probe.close()

        val r = HealthChecker.checkOnce("127.0.0.1", port)
        assertFalse(r.ok)
        assertTrue("错误提示不应为空", r.detail.isNotBlank())
        assertNotNull(r.detail)
    }

    @Test
    fun `常量与 bat 脚本约定一致`() {
        // 6 次重试 × 5 秒间隔
        assertTrue(HealthChecker.MAX_ATTEMPTS == 6)
        assertTrue(HealthChecker.RETRY_DELAY_MS == 5_000L)
        assertTrue(HealthChecker.REQUEST_TIMEOUT_MS == 8_000)
    }
}
