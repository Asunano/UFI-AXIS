package com.ufi_axis_core.adb

import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [AdbChannel] 的行为测试（2026-10-31）。
 *
 * 全部跑在 [FakeAdbServer] 上，不需要真机 —— 这正是把 adbcore 搬进主构建的收益之一：
 * 投屏链路里"最容易写错的那一段"（跨报文重组、服务被拒、连接失效重连）可以在 CI 里守住。
 */
class AdbChannelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var server: FakeAdbServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun startServer(
        raw: Map<String, ByteArray> = emptyMap(),
        filter: (String) -> Boolean = { true },
    ): FakeAdbServer =
        FakeAdbServer(rawResponses = raw, serviceFilter = filter).also {
            it.start()
            server = it
        }

    /** 读到流关闭为止；[awaitData] 兜住"数据还没到"的情况，不会忙等 */
    private fun AdbStream.drainAll(timeoutMs: Long = 3_000L): ByteArray {
        val out = ByteArrayOutputStream()
        var guard = 0
        while (guard++ < 100_000) {
            if (isClosed && available() == 0) break
            if (!awaitData(timeoutMs)) break
            val chunk = readAvailable()
            if (chunk.isNotEmpty()) out.write(chunk)
        }
        return out.toByteArray()
    }

    @Test
    fun `裸字节服务按原样重组`() {
        // 200 KB 且刻意用非周期序列：任何一字节错位都会被比对出来
        val payload = ByteArray(200_000) { ((it * 31 + 7) % 251).toByte() }
        val s = startServer(mapOf("localabstract:scrcpy" to payload))

        val channel = AdbChannel(tmp.root, port = s.port)
        try {
            val stream = channel.stream("localabstract:scrcpy")
            val got = stream.drainAll()

            assertArrayEquals("服务端吐出的字节必须一字不差地到达", payload, got)
            assertEquals(listOf("localabstract:scrcpy"), s.receivedRawServices)
        } finally {
            channel.close()
        }
    }

    @Test
    fun `OKAY 之后紧跟 CLSE 的裸字节服务不算被拒`() {
        // 回归（2026-11-14）：对端回 OKAY 之后**立刻**发 CLSE 是正常收尾（服务吐完就关），
        // 不是「OPEN 被拒绝」。修复前，占位要等等待方被调度才从 pending 摘掉，那段空窗里
        // 这条 CLSE 会被 `handleOpenReply` 当成拒绝，抛「服务不可用（对端拒绝打开该服务）」——
        // 全模块跑时实测偶发（约 1/5），单跑本类几乎不中，所以这里**锁的是行为而非竞态**：
        // 修复后 `PendingStream.complete/fail` 在 reader 线程里同步摘占位，空 payload 也稳过。
        val s = startServer(raw = mapOf("localabstract:scrcpy" to ByteArray(0)))

        val channel = AdbChannel(tmp.root, port = s.port)
        try {
            val stream = channel.stream("localabstract:scrcpy")
            val got = stream.drainAll()
            assertEquals("空服务应当正常打开并立即结束，而不是被当成 OPEN 被拒", 0, got.size)
            assertEquals(listOf("localabstract:scrcpy"), s.receivedRawServices)
        } finally {
            channel.close()
        }
    }

    @Test
    fun `连接复用——连续两次开流只握手一次`() {
        val s = startServer(
            mapOf(
                "exec:screencap -p" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                "localabstract:scrcpy" to byteArrayOf(1, 2, 3),
            )
        )
        val channel = AdbChannel(tmp.root, port = s.port)
        try {
            channel.stream("exec:screencap -p").drainAll()
            channel.stream("localabstract:scrcpy").drainAll()

            assertEquals("两条流必须共用同一条 adb 连接", 1, channel.connectCount)
            assertEquals(2, s.receivedRawServices.size)
        } finally {
            channel.close()
        }
    }

    @Test
    fun `连接被作废后自动重连且只重连一次`() {
        val s = startServer(mapOf("localabstract:scrcpy" to byteArrayOf(9, 8, 7)))
        val channel = AdbChannel(tmp.root, port = s.port)
        try {
            channel.stream("localabstract:scrcpy").drainAll()
            assertEquals(1, channel.connectCount)

            channel.invalidate()
            channel.stream("localabstract:scrcpy").drainAll()

            assertEquals(2, channel.connectCount)
        } finally {
            channel.close()
        }
    }

    @Test
    fun `服务被拒绝时抛 AdbCommandException 而不是挂死`() {
        // 对端收到 OPEN 立刻回 CLSE —— scrcpy-server 没起来时就是这个表现
        val s = startServer(
            raw = emptyMap(),
            filter = { it != "localabstract:scrcpy" },
        )
        val channel = AdbChannel(tmp.root, port = s.port)
        try {
            val error = runCatching { channel.stream("localabstract:scrcpy") }.exceptionOrNull()
            assertTrue(
                "期望 AdbCommandException，实际 ${error?.javaClass?.simpleName}: ${error?.message}",
                error is AdbCommandException,
            )
        } finally {
            channel.close()
        }
    }

    @Test
    fun `连不上 adbd 时抛 AdbException 而非空指针`() {
        // 端口 1 一定没人监听；用来验证"设备侧 adbd 没开"这条常见故障的表现
        val channel = AdbChannel(tmp.root, port = 1, connectTimeoutMs = 800)
        try {
            val error = runCatching { channel.stream("localabstract:scrcpy") }.exceptionOrNull()
            assertTrue("期望 AdbException，实际 ${error?.javaClass?.simpleName}", error is AdbException)
        } finally {
            channel.close()
        }
    }
}
