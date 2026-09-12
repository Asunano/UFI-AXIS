package com.ufi_axis_core.api.routes

import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.contract.NetworkMode
import com.ufi_axis_core.controller.goform.WriteOutcome
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络制式切换链路的两条判定（2026-09-11 真机缺陷修复）：
 *
 * 1. **设备写失败不再一律 500**（[respondWriteFailure]）。原来 `POST /api/network/mode`
 *    只要 `outcome.ok == false` 就回 500，客户端只能显示"服务器内部错误"；而真机上最常见的
 *    那一种失败（设备后台会话失效）其实是"可重试"，必须给客户端区分得出来的状态码与错误码。
 * 2. **回读确认有上限**（[NetworkMode.SwitchProbe]）。切换后设备要重新注册，这期间回读到的
 *    还是旧档位；轮询必须有硬上限，否则弱信号下会一直打 goform。
 */
class NetworkModeSwitchContractTest {

    // ══════════════ 1. 失败映射 ══════════════

    /** 起一个只调 [respondWriteFailure] 的最小路由，验状态码 + 信封，不牵进 RouteContext。 */
    private fun assertMapping(
        outcome: WriteOutcome,
        expectedStatus: HttpStatusCode,
        expectedCode: String,
        expectedMessage: String,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/probe") {
                    val handled = call.respondWriteFailure(
                        outcome,
                        failedMessage = DEVICE_REJECTED_MESSAGE,
                        extra = mapOf("mode" to NetworkMode.ONLY_LTE),
                    )
                    if (!handled) call.respond(HttpStatusCode.OK, "unhandled")
                }
            }
        }
        val resp = client.post("/probe")
        assertEquals(expectedStatus, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(expectedCode, body["code"]?.jsonPrimitive?.contentOrNull)
        assertEquals(expectedMessage, body["message"]?.jsonPrimitive?.contentOrNull)
        // error 与 message 双写、success/ok 双写：客户端既有读法不能被这次改动打断
        assertEquals(expectedMessage, body["error"]?.jsonPrimitive?.contentOrNull)
        assertFalse(body["success"]?.jsonPrimitive?.booleanOrNull ?: true)
        assertFalse(body["ok"]?.jsonPrimitive?.booleanOrNull ?: true)
        // 失败响应也要回显目标档位：客户端的"切换中"中间态靠它对齐
        assertEquals(NetworkMode.ONLY_LTE, body["mode"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `会话失效映射成 503 UNAVAILABLE 而不是 500`() {
        // 这一条就是缺陷本体：可重试的失败被报成 500，用户只看到"服务器内部错误"
        assertMapping(
            WriteOutcome.Unavailable(SESSION_LOST_MESSAGE),
            HttpStatusCode.ServiceUnavailable,
            ErrorCode.UNAVAILABLE,
            SESSION_LOST_MESSAGE,
        )
    }

    @Test
    fun `设备明确拒绝映射成 502 OPERATION_FAILED`() {
        assertMapping(
            WriteOutcome.Failed,
            HttpStatusCode.BadGateway,
            ErrorCode.OPERATION_FAILED,
            DEVICE_REJECTED_MESSAGE,
        )
    }

    @Test
    fun `值域越界映射成 400 OUT_OF_RANGE 并带原因`() {
        assertMapping(
            WriteOutcome.Rejected(OUT_OF_RANGE_REASON),
            HttpStatusCode.BadRequest,
            ErrorCode.OUT_OF_RANGE,
            OUT_OF_RANGE_REASON,
        )
    }

    @Test
    fun `成功时不写任何失败响应`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/probe") {
                    val handled = call.respondWriteFailure(WriteOutcome.Ok, DEVICE_REJECTED_MESSAGE)
                    if (!handled) call.respond(HttpStatusCode.OK, "unhandled")
                }
            }
        }
        val resp = client.post("/probe")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("unhandled"))
    }

    // ══════════════ 2. 回读上限 ══════════════

    @Test
    fun `读到目标档位就停止回读`() {
        assertFalse(NetworkMode.SwitchProbe.shouldKeepProbing(attemptNo = 1, reachedTarget = true))
    }

    @Test
    fun `还没读到目标档位时继续回读`() {
        assertTrue(NetworkMode.SwitchProbe.shouldKeepProbing(attemptNo = 1, reachedTarget = false))
    }

    @Test
    fun `回读次数到上限即停止`() {
        val last = NetworkMode.SwitchProbe.MAX_ATTEMPTS
        assertTrue(NetworkMode.SwitchProbe.shouldKeepProbing(last - 1, reachedTarget = false))
        assertFalse(NetworkMode.SwitchProbe.shouldKeepProbing(last, reachedTarget = false))
        // 越过上限也不能重新变成 true（防止调用方多加一次就绕过上限）
        assertFalse(NetworkMode.SwitchProbe.shouldKeepProbing(last + 1, reachedTarget = false))
    }

    @Test
    fun `设备一直报旧档位时回读次数不超过上限`() {
        var attempts = 0
        attempts++
        while (NetworkMode.SwitchProbe.shouldKeepProbing(attempts, reachedTarget = false)) {
            attempts++
        }
        assertEquals(NetworkMode.SwitchProbe.MAX_ATTEMPTS, attempts)
    }

    @Test
    fun `总时长预算与次数间隔自洽`() {
        val probe = NetworkMode.SwitchProbe
        assertEquals(
            probe.FIRST_DELAY_MS + (probe.MAX_ATTEMPTS - 1) * probe.INTERVAL_MS,
            probe.TOTAL_BUDGET_MS,
        )
    }

    private companion object {
        const val SESSION_LOST_MESSAGE = "设备后台会话已失效，重新登录后仍未受理本次设置，请稍后重试"
        const val DEVICE_REJECTED_MESSAGE = "设备拒绝了本次网络制式切换"
        const val OUT_OF_RANGE_REASON = "不支持的网络制式取值"
    }
}
