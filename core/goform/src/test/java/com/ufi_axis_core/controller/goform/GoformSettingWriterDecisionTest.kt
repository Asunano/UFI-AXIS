package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.RetryPolicy
import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.deviceschema.WriteSpec
import com.ufi_axis_core.deviceschema.profile.ZteGoformProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GoformSettingWriter] 的决策与编排：命令选择、表单体、重试路径、三态判定、备用命令兜底。
 *
 * ## 覆盖到哪一步
 *
 * 这里测的是 **HTTP 之外的整条写路径**：给一个真实 profile 的 [WriteSpec]，断言
 * 「实际会发出去的 body」「走的是哪条传输层方法」「结果怎么收敛」「什么时候换备用命令」。
 * 传输层用 lambda 假发送器注入。
 *
 * **没有覆盖**：`GoformSettingWriter` 与真实 [GoformClient] 之间的装配（构造参数已是接口
 * [GoformTransport]，可以注入假 client，但本轮只做接口化、不新增测试），
 * 以及 `GoformCodec.buildSetFormBody` 之后的报文。
 *
 * ## 为什么值得有
 *
 * 阶段 0 批 2 把 11 个硬编码调用点的 `goformId` + 参数表搬进了 profile。搬错一个键名
 * 是「读取毫无影响、写入静默失败」的错误 —— 只有逐字断言拦得住。下面这批断言就是
 * 改造前那些 `mapOf("goformId" to …)` 字面量的镜像。
 */
class GoformSettingWriterDecisionTest {

    private fun spec(key: SettingKey): WriteSpec =
        requireNotNull(ZteGoformProfile.writeSpec(key)) { "$key 未登记 WriteSpec" }

    private fun body(key: SettingKey, params: Map<String, Any?>): Map<String, String> =
        GoformSettingWriter.buildBody(spec(key), params)

    private fun body(key: SettingKey, value: Any?): Map<String, String> =
        body(key, mapOf("value" to value))

    // ───────────── 改前 / 改后请求体逐字对照 ─────────────

    @Test
    fun `三个动作类命令只发 goformId`() {
        // 改前：mapOf("isTest" to "false", "goformId" to "REBOOT_DEVICE") 等三条。
        // isTest 由 GoformCodec.buildSetFormBody 统一补，spec 不该再发一份（发了是重复）。
        assertEquals(mapOf("goformId" to "REBOOT_DEVICE"), body(SettingKey.REBOOT, emptyMap<String, Any?>()))
        assertEquals(mapOf("goformId" to "FACTORY_RESET"), body(SettingKey.FACTORY_RESET, emptyMap<String, Any?>()))
        assertEquals(mapOf("goformId" to "SHUTDOWN_DEVICE"), body(SettingKey.SHUTDOWN, emptyMap<String, Any?>()))
    }

    @Test
    fun `连接模式与 WiFi 功率的参数键逐字不变`() {
        assertEquals(
            mapOf("goformId" to "SET_CONNECTION_MODE", "ConnectionMode" to "manual_dial"),
            body(SettingKey.CONNECTION_MODE, "manual_dial")
        )
        assertEquals(
            mapOf("goformId" to "SET_WIFI_POWER", "wifiPowerLevel" to "2"),
            body(SettingKey.WIFI_POWER, 2)
        )
    }

    @Test
    fun `改口令发的是哈希后的 oldPassword 与 newPassword`() {
        // 哈希在 GoformDeviceClient 里做（与登录握手共用 sha256Hex），profile 只映射字段名。
        val old = "A".repeat(64)
        val new = "B".repeat(64)
        assertEquals(
            mapOf("goformId" to "CHANGE_PASSWORD", "oldPassword" to old, "newPassword" to new),
            body(SettingKey.BACKEND_PASSWORD, mapOf("old_hash" to old, "new_hash" to new))
        )
        // 忘了哈希直接传明文 → 拒绝下发（那会把明文口令原样发给设备）
        assertTrue(spec(SettingKey.BACKEND_PASSWORD).validate(
            mapOf("old_hash" to "admin", "new_hash" to "admin123")
        ) != null)
    }

    @Test
    fun `commandOf 按取值选命令 —— 移动数据`() {
        assertEquals(
            mapOf("goformId" to "CONNECT_NETWORK", "notCallback" to "true"),
            body(SettingKey.MOBILE_DATA, true)
        )
        assertEquals(
            mapOf("goformId" to "DISCONNECT_NETWORK", "notCallback" to "true"),
            body(SettingKey.MOBILE_DATA, false)
        )
    }

    @Test
    fun `commandOf 按取值选命令 —— 手动拨号与 WiFi 总开关`() {
        // PPP_DIAL：命令名与参数与 MOBILE_DATA 主命令完全相同，区别只在于没有 fallback
        assertEquals(
            mapOf("goformId" to "CONNECT_NETWORK", "notCallback" to "true"),
            body(SettingKey.PPP_DIAL, true)
        )
        assertEquals(
            mapOf("goformId" to "DISCONNECT_NETWORK", "notCallback" to "true"),
            body(SettingKey.PPP_DIAL, false)
        )
        assertNull("PPP_DIAL 不许有兜底命令", spec(SettingKey.PPP_DIAL).fallback)

        // WiFi 开关：开与关连参数集都不同
        assertEquals(
            mapOf("goformId" to "switchWiFiChip", "ChipEnum" to "chip1", "GuestEnable" to "0"),
            body(SettingKey.WIFI_ENABLED, true)
        )
        assertEquals(
            mapOf("goformId" to "switchWiFiModule", "SwitchOption" to "0"),
            body(SettingKey.WIFI_ENABLED, false)
        )
    }

    @Test
    fun `移动数据的备用命令是 SET_DATA_ENABLED 且不带 notCallback`() {
        // 改前第二条：mapOf("isTest" to "false", "goformId" to "SET_DATA_ENABLED", "data" to "1"/"0")
        val fallback = requireNotNull(spec(SettingKey.MOBILE_DATA).fallback)
        assertEquals(
            mapOf("goformId" to "SET_DATA_ENABLED", "data" to "1"),
            GoformSettingWriter.buildBody(fallback, mapOf("value" to true))
        )
        assertEquals(
            mapOf("goformId" to "SET_DATA_ENABLED", "data" to "0"),
            GoformSettingWriter.buildBody(fallback, mapOf("value" to false))
        )
    }

    // ───────────── 重试路径 ─────────────

    /** 记录两条传输层方法各被调了几次、发出去的 body 是什么。 */
    private class FakeTransport(
        private val idempotentResult: GoformWriteResult = GoformWriteResult.Accepted(OK_BODY),
        private val plainResult: String? = OK_BODY,
    ) {
        val idempotentBodies = mutableListOf<Map<String, String>>()
        val plainBodies = mutableListOf<Map<String, String>>()
        val warns = mutableListOf<String>()

        fun send(key: SettingKey, spec: WriteSpec, params: Map<String, Any?>): WriteOutcome =
            runBlocking {
                GoformSettingWriter.sendBySpec(
                    key = key,
                    spec = spec,
                    params = params,
                    postIdempotent = { body -> idempotentBodies += body; idempotentResult },
                    postPlain = { body -> plainBodies += body; plainResult },
                    isSuccess = { body -> body == OK_BODY },
                    warn = { warns += it },
                )
            }
    }

    @Test
    fun `retry 为 RETRY_ON_SESSION_LOSS 时走可重试路径`() {
        val spec = spec(SettingKey.WIFI_POWER)
        assertEquals(RetryPolicy.RETRY_ON_SESSION_LOSS, spec.retry)
        val transport = FakeTransport()
        val outcome = transport.send(SettingKey.WIFI_POWER, spec, mapOf("value" to 1))
        assertEquals(WriteOutcome.Ok, outcome)
        assertEquals(1, transport.idempotentBodies.size)
        assertTrue("不重试路径一次都不该被调用", transport.plainBodies.isEmpty())
        assertEquals("SET_WIFI_POWER", transport.idempotentBodies[0]["goformId"])
    }

    @Test
    fun `retry 为 NEVER 时走不重试路径`() {
        val spec = spec(SettingKey.REBOOT)
        assertEquals(RetryPolicy.NEVER, spec.retry)
        val transport = FakeTransport()
        val outcome = transport.send(SettingKey.REBOOT, spec, emptyMap())
        assertEquals(WriteOutcome.Ok, outcome)
        assertEquals(1, transport.plainBodies.size)
        assertTrue("重启不许走重试路径（重发一次就是再重启一次）", transport.idempotentBodies.isEmpty())
    }

    @Test
    fun `不重试路径的 null 收敛成不可用而不是失败`() {
        // goformPost 的 null 把「会话失效 / 连不上 / AD 算不出」压成了一个值，
        // 报 Failed 会让 route 回 500（用户无从判断该不该再点一次）。
        val transport = FakeTransport(plainResult = null)
        val outcome = transport.send(SettingKey.SHUTDOWN, spec(SettingKey.SHUTDOWN), emptyMap())
        assertEquals(WriteOutcome.Unavailable(GoformSettingWriter.UNREACHABLE_MESSAGE), outcome)
    }

    @Test
    fun `两条路径的三态判定`() {
        val ok: (String) -> Boolean = { it == OK_BODY }
        // 可重试路径
        assertEquals(
            WriteOutcome.Ok,
            GoformSettingWriter.interpretRetryable(GoformWriteResult.Accepted(OK_BODY), ok)
        )
        assertEquals(
            WriteOutcome.Failed,
            GoformSettingWriter.interpretRetryable(GoformWriteResult.Accepted(FAIL_BODY), ok)
        )
        assertEquals(
            WriteOutcome.Unavailable(GoformSettingWriter.SESSION_LOST_MESSAGE),
            GoformSettingWriter.interpretRetryable(GoformWriteResult.SessionLost, ok)
        )
        assertEquals(
            WriteOutcome.Unavailable(GoformSettingWriter.UNREACHABLE_MESSAGE),
            GoformSettingWriter.interpretRetryable(GoformWriteResult.Unreachable("timeout"), ok)
        )
        // 不重试路径
        assertEquals(WriteOutcome.Ok, GoformSettingWriter.interpretPlain(OK_BODY, ok))
        assertEquals(WriteOutcome.Failed, GoformSettingWriter.interpretPlain(FAIL_BODY, ok))
        assertEquals(
            WriteOutcome.Unavailable(GoformSettingWriter.UNREACHABLE_MESSAGE),
            GoformSettingWriter.interpretPlain(null, ok)
        )
    }

    @Test
    fun `不可达时把英文 detail 写进日志`() {
        val transport = FakeTransport(idempotentResult = GoformWriteResult.Unreachable("Connect timed out"))
        transport.send(SettingKey.WIFI_POWER, spec(SettingKey.WIFI_POWER), mapOf("value" to 0))
        assertTrue(transport.warns.any { it.contains("Connect timed out") })
    }

    // ───────────── 备用命令兜底 ─────────────

    /** 驱动 [GoformSettingWriter.writeWithFallback]，按顺序返回预置结果并记录发了哪些命令。 */
    private class FallbackDriver(private vararg val outcomes: WriteOutcome) {
        val sent = mutableListOf<String>()
        val warns = mutableListOf<String>()

        fun run(spec: WriteSpec, params: Map<String, Any?>): WriteOutcome = runBlocking {
            var i = 0
            GoformSettingWriter.writeWithFallback(
                key = SettingKey.MOBILE_DATA,
                spec = spec,
                params = params,
                warn = { warns += it },
            ) { each ->
                sent += GoformSettingWriter.resolveCommand(each, params)
                outcomes[i++]
            }
        }
    }

    @Test
    fun `主命令成功时不发备用命令`() {
        val driver = FallbackDriver(WriteOutcome.Ok)
        val outcome = driver.run(spec(SettingKey.MOBILE_DATA), mapOf("value" to true))
        assertEquals(WriteOutcome.Ok, outcome)
        assertEquals(listOf("CONNECT_NETWORK"), driver.sent)
        assertTrue("没兜底就不该有兜底日志", driver.warns.isEmpty())
    }

    @Test
    fun `主命令 Failed 触发兜底`() {
        assertFallbackTriggered(WriteOutcome.Failed)
    }

    @Test
    fun `主命令 SessionLost 触发兜底`() {
        // 现状「第一条返回 null 就发第二条」里的一种，漏掉它就是行为变更
        assertFallbackTriggered(WriteOutcome.Unavailable(GoformSettingWriter.SESSION_LOST_MESSAGE))
    }

    @Test
    fun `主命令 Unreachable 触发兜底`() {
        assertFallbackTriggered(WriteOutcome.Unavailable(GoformSettingWriter.UNREACHABLE_MESSAGE))
    }

    private fun assertFallbackTriggered(primary: WriteOutcome) {
        val driver = FallbackDriver(primary, WriteOutcome.Ok)
        val outcome = driver.run(spec(SettingKey.MOBILE_DATA), mapOf("value" to true))
        // 备用命令的结果就是最终结果：主失败 + 备成功 = Ok（与现状 setMobileData 一致）
        assertEquals(WriteOutcome.Ok, outcome)
        assertEquals(listOf("CONNECT_NETWORK", "SET_DATA_ENABLED"), driver.sent)
        // 可观测性是硬要求：主命令名 + 结果类型 + 备用命令名都要在日志里
        val log = driver.warns.first()
        assertTrue(log.contains("CONNECT_NETWORK"))
        assertTrue(log.contains("SET_DATA_ENABLED"))
        assertTrue(log.contains(GoformSettingWriter.outcomeTag(primary)))
    }

    @Test
    fun `备用命令失败时最终结果是备用命令的结果`() {
        val driver = FallbackDriver(WriteOutcome.Failed, WriteOutcome.Unavailable("备用命令也没发出去"))
        val outcome = driver.run(spec(SettingKey.MOBILE_DATA), mapOf("value" to false))
        assertEquals(WriteOutcome.Unavailable("备用命令也没发出去"), outcome)
        assertEquals(listOf("DISCONNECT_NETWORK", "SET_DATA_ENABLED"), driver.sent)
    }

    @Test
    fun `没有登记 fallback 的项失败就是失败`() {
        val driver = FallbackDriver(WriteOutcome.Failed)
        val outcome = driver.run(spec(SettingKey.PPP_DIAL), mapOf("value" to true))
        assertEquals(WriteOutcome.Failed, outcome)
        assertEquals(listOf("CONNECT_NETWORK"), driver.sent)
    }

    @Test
    fun `嵌套 fallback 只试一层并留 warn`() {
        val nested = WriteSpec(
            command = "PRIMARY",
            encode = { emptyMap() },
            fallback = WriteSpec(
                command = "SECOND",
                encode = { emptyMap() },
                fallback = WriteSpec(command = "THIRD", encode = { emptyMap() }),
            ),
        )
        val driver = FallbackDriver(WriteOutcome.Failed, WriteOutcome.Failed)
        val outcome = driver.run(nested, emptyMap())
        assertEquals(WriteOutcome.Failed, outcome)
        assertEquals("兜底只试一次，THIRD 不许发出去", listOf("PRIMARY", "SECOND"), driver.sent)
        assertTrue(driver.warns.any { it.contains("已忽略") })
    }

    @Test
    fun `备用命令不跑 validate`() {
        // 主 spec 的 validate 已经对同一份 params 跑过，params 没变；而现状的第二条命令
        // 根本没有校验 —— 在这里补一次就可能把今天必发的第二条拦下来。
        var fallbackValidateCalls = 0
        val spec = WriteSpec(
            command = "PRIMARY",
            encode = { emptyMap() },
            fallback = WriteSpec(
                command = "SECOND",
                encode = { emptyMap() },
                validate = { fallbackValidateCalls++; "备用命令的校验不该被调用" },
            ),
        )
        val driver = FallbackDriver(WriteOutcome.Failed, WriteOutcome.Ok)
        assertEquals(WriteOutcome.Ok, driver.run(spec, emptyMap()))
        assertEquals(listOf("PRIMARY", "SECOND"), driver.sent)
        assertEquals(0, fallbackValidateCalls)
    }

    @Test
    fun `shouldTryFallback 的判据是「非 Ok 且登记了 fallback」`() {
        val withFallback = spec(SettingKey.MOBILE_DATA)
        val withoutFallback = spec(SettingKey.PPP_DIAL)
        assertFalse(GoformSettingWriter.shouldTryFallback(WriteOutcome.Ok, withFallback))
        assertTrue(GoformSettingWriter.shouldTryFallback(WriteOutcome.Failed, withFallback))
        assertTrue(GoformSettingWriter.shouldTryFallback(WriteOutcome.Unavailable("x"), withFallback))
        assertFalse(GoformSettingWriter.shouldTryFallback(WriteOutcome.Failed, withoutFallback))
    }

    private companion object {
        /** `isGoformSuccess` 认的成功体之一。 */
        const val OK_BODY = """{"result":"success"}"""
        const val FAIL_BODY = """{"result":"failure"}"""
    }
}
