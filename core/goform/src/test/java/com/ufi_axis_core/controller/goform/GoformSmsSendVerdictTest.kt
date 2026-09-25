package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.profile.ZteGoformProfile
import com.ufi_axis_core.devicespi.adapter.SendOutcome
import com.ufi_axis_core.devicespi.adapter.SendVerdict
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GoformSmsClient.sendSms` 的**设备表态 → [SendVerdict] 映射**（2026-09-26 收紧）。
 *
 * ## 为什么这三条必须逐档钉住
 *
 * 上层（`LocalSmsDelivery`）拿 [SendVerdict] 同时决定**要不要自动重发**与**要不要计配额**，
 * 而重发一条短信等于可能再花一笔钱。判据只有一条：**我们知不知道设备没收到**。
 *  - [GoformWriteResult.SessionLost] → 知道没收到 → [SendVerdict.REJECTED]（可重发、不计配额）；
 *  - [GoformWriteResult.Unreachable] → 不知道 → [SendVerdict.NO_RESPONSE]（不许重发、要计配额）；
 *  - [GoformWriteResult.Accepted] + body 说失败 → 设备明确拒收 → [SendVerdict.REJECTED]。
 *
 * 改造前 `SessionLost` 和 `Unreachable` 一起被 `write()` 压成 `null` → 全落 `NO_RESPONSE`，
 * 于是「会话陈旧导致 SEND_SMS 一个字节都没发出」会被当成"可能已发出"：既不重发、又照扣配额，
 * 一条 CRITICAL 通知就这么白丢了。
 *
 * ## 顺带钉住「发送不许再走 `write()`」
 *
 * 假传输层的 [StubTransport.write] 直接抛 —— 哪天有人把 `writeSessionSafe` 改回 `write`，
 * 本类三个用例会一起红，而不是等真机上再出现「第一次发必 500」。
 */
class GoformSmsSendVerdictTest {

    /**
     * 最小假传输层：只让 `sendSms` 真正会走到的成员可用，其余一律抛。
     *
     * [httpGet] **故意抛异常**：`sendSms` 发送前会读一次信箱取 baseline id，
     * 抛出去正好让它拿到 -1（`getSmsList` 自己 catch 住），用例因此不需要伪造 [HttpResponse]
     * （那玩意在裸 JVM 单测里构造不出来）。
     */
    private class StubTransport(private val result: GoformWriteResult) : GoformTransport {

        /** 被调到的写入口，按发生顺序。 */
        val writeCalls = mutableListOf<String>()

        /**
         * 被调到的**会话入口**，按发生顺序。
         *
         * 用来钉住「下发前走的是**强制校验**」：`sendSms` 的第一步必须是 [ensureFreshLogin]。
         * 哪天有人把它改回 [ensureLogin]，下面那条用例会红，而不是等真机上再出现
         * 「陈旧会话下第一次发必失败（固件回 200 + 业务失败体那一档）」。
         */
        val loginCalls = mutableListOf<String>()

        private fun nope(): Nothing = error("本用例不该触达这个成员")

        override fun baseUrl(): String = "http://192.168.0.1"

        override suspend fun ensureLogin(): Boolean {
            loginCalls += "ensureLogin"
            return true
        }

        override suspend fun ensureFreshLogin(): Boolean {
            loginCalls += "ensureFreshLogin"
            return true
        }


        override suspend fun httpGet(url: String): HttpResponse =
            throw IllegalStateException("fake: 本用例不联网（信箱读一律失败）")

        override suspend fun writeSessionSafe(params: Map<String, String>): GoformWriteResult {
            writeCalls += "writeSessionSafe"
            return result
        }

        override suspend fun write(params: Map<String, String>): String? {
            writeCalls += "write"
            error("SEND_SMS 不许走 write()：整圈重试会被跳过，会话一旦陈旧第一次发必失败")
        }

        override fun isSuccess(body: String?): Boolean =
            body != null && body.contains("\"result\":\"success\"")

        override fun invalidateSession() = nope()
        override fun resetLogin() = nope()
        override fun updateGoformPassword(newPwd: String) = nope()
        override suspend fun read(commands: List<String>): JsonObject? = nope()
        override suspend fun readOne(command: String): JsonElement? = nope()
        override suspend fun logout(): Boolean = nope()
        override fun decodeDeviceText(input: String): String = nope()
        override fun adjustQoS(permits: Int) = nope()
        override fun getQosStatus(): Map<String, Any> = nope()
        override fun setQosEnabled(enabled: Boolean) = nope()
        override fun close() = nope()
        override suspend fun ensureBaseUrlResolved() = nope()
        override fun parseJson(body: String): JsonObject? = nope()
        override fun isAuthFailure(body: String): Boolean = nope()
        override suspend fun writeIdempotent(params: Map<String, String>): GoformWriteResult = nope()
        override fun sha256Hex(input: String): String = nope()
    }

    private fun send(result: GoformWriteResult): Pair<StubTransport, SendOutcome> {
        val transport = StubTransport(result)
        val client = GoformSmsClient(transport, ZteGoformProfile)
        val outcome = runBlocking { client.sendSms("13800138000", "测试") }
        return transport to outcome
    }

    @Test
    fun `陈旧会话下首次发送 —— 会话失效映射成 REJECTED 并说明未发出`() {
        val (transport, outcome) = send(GoformWriteResult.SessionLost)

        assertEquals(SendVerdict.REJECTED, outcome.verdict)
        // 文案是用户唯一能看到的东西：必须说清"没发出、可以重发、不扣配额"，
        // 否则用户看到干巴巴一个"失败"会去手动重发（那才是真花钱）。
        assertTrue("文案要说明未发出：${outcome.detail}", outcome.detail.contains("未发出"))
        assertTrue("文案要说明可安全重发：${outcome.detail}", outcome.detail.contains("重发"))
        assertTrue("文案要说明不计配额：${outcome.detail}", outcome.detail.contains("配额"))
        assertEquals(listOf("writeSessionSafe"), transport.writeCalls)
    }

    @Test
    fun `连不上设备映射成 NO_RESPONSE —— 无法确认是否已发出`() {
        val (transport, outcome) = send(GoformWriteResult.Unreachable("Connect timed out"))

        assertEquals(SendVerdict.NO_RESPONSE, outcome.verdict)
        assertTrue("文案要说明无法确认：${outcome.detail}", outcome.detail.contains("无法确认"))
        assertFalse(
            "这一档不许说「可安全重发」（排除不了已发出）：${outcome.detail}",
            outcome.detail.contains("可安全重发"),
        )
        assertEquals(listOf("writeSessionSafe"), transport.writeCalls)
    }

    @Test
    fun `设备回业务失败体映射成 REJECTED —— 设备明确拒收`() {
        val (transport, outcome) = send(GoformWriteResult.Accepted("""{"result":"failure"}"""))

        assertEquals(SendVerdict.REJECTED, outcome.verdict)
        assertTrue(outcome.detail.contains("拒收"))
        assertEquals(listOf("writeSessionSafe"), transport.writeCalls)
    }

    @Test
    fun `下发前先走强制校验入口，不是普通 ensureLogin`() {
        val (transport, _) = send(GoformWriteResult.Accepted("""{"result":"failure"}"""))

        // 第一步必须是强制校验：writeSessionSafe 只能救「回登录页 / 非 200」那一档，
        // 「200 + 业务失败体」那一档不重发（重复计费红线），只能靠下发前先把会话刷新。
        assertEquals(
            "sendSms 的第一个会话动作必须是 ensureFreshLogin（实际：${transport.loginCalls}）",
            "ensureFreshLogin",
            transport.loginCalls.firstOrNull(),
        )
        // 只有下发这一次是强制的；发送前读 baseline 信箱那次仍走普通 ensureLogin
        // （它是读路径，没必要为它多付一次 RD）。
        assertEquals(
            "强制校验只许发生一次（多了就是把用户的一次点击放大成多次 goform 往返）",
            1,
            transport.loginCalls.count { it == "ensureFreshLogin" },
        )
    }
}

