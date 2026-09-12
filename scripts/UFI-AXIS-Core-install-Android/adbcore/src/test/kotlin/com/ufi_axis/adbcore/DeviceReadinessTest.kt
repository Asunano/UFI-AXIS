package com.ufi_axis.adbcore

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 设备就绪检测 [AdbClient.checkReady] 的单测（走本地假设备）。
 */
class DeviceReadinessTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var server: FakeAdbServer? = null
    private var client: AdbClient? = null

    @After
    fun tearDown() {
        client?.close()
        server?.stop()
    }

    private fun newCrypto() = AdbCrypto(tempFolder.newFolder())

    @Test
    fun `设备正常响应 echo 时判定为就绪`() {
        val s = FakeAdbServer(
            mode = FakeAdbServer.AuthMode.ACCEPT_SIGNATURE,
            shellResponses = mapOf("echo __ufi_ready__" to Triple("__ufi_ready__\n", "", 0))
        )
        s.start()
        server = s

        val c = AdbClient(newCrypto())
        client = c
        c.connect("127.0.0.1", s.port)

        assertEquals(AdbClient.DeviceReadiness.Ready, c.checkReady())
    }

    @Test
    fun `未连接时判定为离线`() {
        val c = AdbClient(newCrypto())
        client = c
        assertTrue(
            "未连接应判定为离线",
            c.checkReady() is AdbClient.DeviceReadiness.Offline
        )
    }

    @Test
    fun `设备要求 TLS 配对时连接抛出明确异常`() {
        val s = FakeAdbServer(mode = FakeAdbServer.AuthMode.REQUIRE_TLS)
        s.start()
        server = s

        val c = AdbClient(newCrypto())
        client = c
        try {
            c.connect("127.0.0.1", s.port)
            throw AssertionError("应当抛出 AdbTlsRequiredException")
        } catch (e: AdbTlsRequiredException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }
}
