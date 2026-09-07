package com.ufi_axis_core.api.routes

import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.controller.goform.GoformDeviceClient
import com.ufi_axis_core.controller.goform.WriteOutcome
import com.ufi_axis_core.util.AppSettings
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * route 级断言（计划书 0.4）：设备路由上**只由 route 层决定**的两条分支。
 *
 * 这里刻意不测"字段形状"——归一化的 golden 在 `:core:device-schema`（纯 JVM、秒级）。
 * route 层独有的行为只有两类，也正是改造中新加的两条：
 *   1. 9.3 的 dump 准入开关（关 → 403，且**在碰设备之前**就返回）；
 *   2. 9.5 的三态写结果里 `Rejected` → 400 `OUT_OF_RANGE`（值域被拒，请求根本没发出去）。
 *
 * `RouteContext` 用 relaxed mock：上面两条分支都在触达其它依赖前返回，
 * 需要真值的只有 `settings`（读开关）与 `deviceClient`（造 Rejected）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceRoutesTest {

    private lateinit var settings: AppSettings
    private lateinit var deviceClient: GoformDeviceClient
    private lateinit var routes: DeviceRoutes

    @Before
    fun setup() {
        settings = AppSettings(ApplicationProvider.getApplicationContext())
        settings.resetAll()
        deviceClient = mockk(relaxed = true)
        val ctx = mockk<RouteContext>(relaxed = true)
        every { ctx.settings } returns settings
        every { ctx.deviceClient } returns deviceClient
        routes = DeviceRoutes(ctx)
    }

    private fun withRoutes(block: suspend (client: io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { routes.register(this) }
        }
        block(client)
    }

    private fun body(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `goform dump 默认关闭时回 403 FORBIDDEN`() = withRoutes { client ->
        // 默认值就是关（resetAll 之后没人打开过）
        val res = client.get("/device/goform")
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertEquals(ErrorCode.FORBIDDEN, body(res.bodyAsText())["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `打开开关后 goform dump 不再回 403`() = withRoutes { client ->
        settings.goformDumpEnabled = true
        val res = client.get("/device/goform")
        // 打开后走的是设备查询路径（这里是 relaxed mock，只断言"不再被开关挡住"）
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `值域被拒的写操作回 400 OUT_OF_RANGE 并带上原因`() = withRoutes { client ->
        coEvery { deviceClient.cellLock(any(), any(), any()) } returns
            WriteOutcome.Rejected("pci 必须是 0..1007 的整数")

        val res = client.post("/device/cell-lock") {
            contentType(ContentType.Application.Json)
            setBody("""{"pci":"99999","earfcn":"1850","network_type":"LTE"}""")
        }

        assertEquals("参数非法是 400 而不是设备失败的 500", HttpStatusCode.BadRequest, res.status)
        val json = body(res.bodyAsText())
        assertEquals(ErrorCode.OUT_OF_RANGE, json["code"]!!.jsonPrimitive.content)
        assertEquals("拒绝原因要回传给客户端，不能只进日志", "pci 必须是 0..1007 的整数", json["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `设备执行失败沿用 200 加 success false 而不是 400`() = withRoutes { client ->
        coEvery { deviceClient.cellLock(any(), any(), any()) } returns WriteOutcome.Failed

        val res = client.post("/device/cell-lock") {
            contentType(ContentType.Application.Json)
            setBody("""{"pci":"100","earfcn":"1850","network_type":"LTE"}""")
        }

        // Failed 不被 respondRejected 接管，沿用原有 `{"success": false}` 形状
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(false, body(res.bodyAsText())["success"]!!.jsonPrimitive.content.toBoolean())
    }
}
