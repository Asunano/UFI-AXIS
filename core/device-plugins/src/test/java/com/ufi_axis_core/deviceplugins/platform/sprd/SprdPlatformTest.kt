package com.ufi_axis_core.deviceplugins.platform.sprd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SprdPlatform.restartNetworkStack] 的守门测试（阶段 4 的 4.4 / 批 G）。
 *
 * 这个方法从 `NetworkController` 搬过来时的判据是「命令、顺序、等待、判据一个字都不变」，
 * 所以这里钉的不是「它能跑」，而是**逐字的那几条**：
 *
 * 1. 命令序列恰好两条、逐字是 `AT+SFUN=5` 然后 `AT+SFUN=4`；
 * 2. 两段等待是 500ms（两条之间）+ 2000ms（第二条之后）；
 * 3. 判成功的依据是回显**含** `OK`（不是等于）；`null` 与不含 `OK` 都算失败并**立即停下**；
 * 4. 执行器抛异常时返回 `false`，不往外抛。
 *
 * ## 为什么用 `runTest` 而不是 `runBlocking`
 *
 * 等待时长是被测契约的一部分，而它加起来 2.5s。`runTest` 的 `currentTime` 是
 * **虚拟时间**：既能把 500 / 2000 断言到精确值，又不真的睡 —— 不需要为了可测性把生产代码里的
 * `delay` 换成可注入的时钟（那是拿「改生产时序」换「测试好写」，方向是反的）。
 *
 * ## 为什么不测 [SprdPlatform.atTransports]
 *
 * 它 `new` 的 [ServiceCallAtExecutor] 默认参数取 `Build.VERSION.SDK_INT` ——
 * 裸 JVM 单测里那是 `RuntimeException: Stub!`（同一个坑记在 `PluginContractTest` 的文件头）。
 * 那条登记在 `ServiceCallAtExecutor` 的类注释里，等阶段 5.1 把 `sdkInt` 改成从 `ProbeEnv` 取。
 */
@OptIn(ExperimentalCoroutinesApi::class)   // TestScope.currentTime（虚拟时钟读数）仍是实验 API
class SprdPlatformTest {

    /**
     * 假执行器：按调用顺序记下收到的命令原文，按预置队列逐个回回显。
     *
     * 回显队列用完之后返回 `null`（= 发不出去），这样「多发了一条」会立刻表现为
     * 命令序列断言失败，而不是悄悄拿到一个成功回显。
     */
    private class FakeAt(private val replies: List<String?>) {
        val sent = mutableListOf<String>()
        suspend fun exec(cmd: String): String? {
            sent += cmd
            return replies.getOrNull(sent.size - 1)
        }
    }

    private fun fakeAt(vararg replies: String?) = FakeAt(replies.toList())

    // ───────────────────────── 成功路径 ─────────────────────────

    @Test
    fun `成功时命令序列逐字是 SFUN=5 然后 SFUN=4`() = runTest {
        val at = fakeAt("OK", "OK")
        val ok = SprdPlatform().restartNetworkStack(at::exec)

        assertTrue("两条都回 OK 时必须返回 true", ok)
        assertEquals(
            "命令序列必须逐字不变（搬迁判据），先关射频再开",
            listOf("AT+SFUN=5", "AT+SFUN=4"),
            at.sent,
        )
    }

    @Test
    fun `成功路径的两段等待是 500ms 加 2000ms`() = runTest {
        val at = fakeAt("OK", "OK")
        assertTrue(SprdPlatform().restartNetworkStack(at::exec))

        // 500 = 等芯片重新加载配置；2000 = 等 modem 完全稳定。两个数都是实测出来的设备事实，
        // 改小了会让频段写入不生效（而且是间歇性的 —— 最难归因的那种）。
        assertEquals("总等待必须是 500 + 2000", 2500L, currentTime)
    }

    @Test
    fun `判据是回显含 OK 而不是等于 OK`() = runTest {
        // 真机回显带 CRLF 与前导空行，原实现用的是 contains("OK")。
        // 谁把它「顺手收紧」成 trim() == "OK"，网络栈重启就会在真机上恒失败。
        val at = fakeAt("\r\nOK\r\n", "AT+SFUN=4\r\r\nOK\r\n")
        assertTrue(SprdPlatform().restartNetworkStack(at::exec))
        assertEquals(listOf("AT+SFUN=5", "AT+SFUN=4"), at.sent)
    }

    // ───────────────────────── 第一条失败 ─────────────────────────

    @Test
    fun `第一条发不出去时返回 false 且不发第二条也不等待`() = runTest {
        val at = FakeAt(listOf(null))
        val ok = SprdPlatform().restartNetworkStack(at::exec)

        assertFalse("AT+SFUN=5 返回 null 必须返回 false", ok)
        assertEquals(
            "第一条失败必须立即停下 —— 只开射频不关是把设备留在中间态",
            listOf("AT+SFUN=5"),
            at.sent,
        )
        assertEquals("第一条就失败时不该有任何等待", 0L, currentTime)
    }

    @Test
    fun `第一条回 ERROR 时返回 false 且不发第二条`() = runTest {
        val at = fakeAt("\r\nERROR\r\n")
        assertFalse(SprdPlatform().restartNetworkStack(at::exec))
        assertEquals(listOf("AT+SFUN=5"), at.sent)
        assertEquals(0L, currentTime)
    }

    // ───────────────────────── 第二条失败 ─────────────────────────

    @Test
    fun `第二条失败时返回 false 且只走完第一段 500ms 等待`() = runTest {
        val at = FakeAt(listOf("OK", null))
        val ok = SprdPlatform().restartNetworkStack(at::exec)

        assertFalse("AT+SFUN=4 返回 null 必须返回 false", ok)
        assertEquals("两条都要发出去（第一条成功了）", listOf("AT+SFUN=5", "AT+SFUN=4"), at.sent)
        // 只有两条命令之间那 500ms；第二条失败后**不走** 2000ms 稳定等待（原实现直接 return）。
        assertEquals(500L, currentTime)
    }

    @Test
    fun `第二条回 ERROR 时返回 false`() = runTest {
        val at = fakeAt("OK", "\r\nERROR\r\n")
        assertFalse(SprdPlatform().restartNetworkStack(at::exec))
        assertEquals(listOf("AT+SFUN=5", "AT+SFUN=4"), at.sent)
        assertEquals(500L, currentTime)
    }

    // ───────────────────────── 执行器抛异常 ─────────────────────────

    @Test
    fun `执行器抛异常时返回 false 而不是把异常抛给调用方`() = runTest {
        // 原实现整段包 try / catch(Exception)：通道层炸了也只算一次「操作失败」，
        // 由 route 回 network_restarted=false，不是 500。
        val ok = SprdPlatform().restartNetworkStack {
            throw IllegalStateException("AT channel blew up")
        }
        assertFalse("执行器抛异常必须被吞掉并返回 false", ok)
    }

    @Test
    fun `第二条抛异常时同样返回 false`() = runTest {
        val sent = mutableListOf<String>()
        val ok = SprdPlatform().restartNetworkStack { cmd ->
            sent += cmd
            if (cmd == "AT+SFUN=4") throw IllegalStateException("boom") else "OK"
        }
        assertFalse(ok)
        assertEquals(listOf("AT+SFUN=5", "AT+SFUN=4"), sent)
    }

    // ───────────────────────── 对外字符串 ─────────────────────────

    @Test
    fun `name 仍是对外那个 SPREADTRUM`() {
        // 4.6 会把 /api/at/platform 与 /api/at/status 的 platform 键换成本字段，
        // 取值一变就是一次没人要求的客户端适配（理由写在该字段的 KDoc 上）。
        assertEquals("SPREADTRUM", SprdPlatform().name)
    }
}
