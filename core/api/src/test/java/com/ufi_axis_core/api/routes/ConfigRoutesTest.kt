package com.ufi_axis_core.api.routes

import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.contract.ConfigLimits
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.util.AppSettings
import io.ktor.client.request.put
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C03：`PUT /api/config` 的 `rejected_fields` 语义测试。
 *
 * 改造前越界/空串/脱敏回写都是「静默丢弃 + success:true」，客户端只能靠 `updated_fields`
 * 反推、且拿不到原因。这里锁定三件事：被拒字段带 reason、整型字段带 min/max、
 * 合法字段仍照常写入（旧客户端的 `updated_fields` 用法不回归）。
 *
 * 只挑不触碰 QoS 单例的字段做「写入成功」断言，避免测试受全局限流状态影响。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConfigRoutesTest {

    private lateinit var settings: AppSettings
    private lateinit var routes: ConfigRoutes

    @Before
    fun setup() {
        settings = AppSettings(ApplicationProvider.getApplicationContext())
        settings.resetAll()
        routes = ConfigRoutes(settings)
    }

    private fun putConfig(body: String, assert: (JsonObject) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { routes.register(this) }
        }
        val res = client.put("/config") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assert(Json.parseToJsonElement(res.bodyAsText()).jsonObject)
    }

    private fun JsonObject.rejected(field: String): JsonObject? =
        this["rejected_fields"]!!.jsonArray.map { it.jsonObject }
            .firstOrNull { it["field"]?.jsonPrimitive?.content == field }

    private fun JsonObject.updatedFields(): List<String> =
        this["updated_fields"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `out of range int reports reason with min and max`() {
        putConfig("""{"qos_goform_set_max":50}""") { body ->
            val r = body.rejected("qos_goform_set_max")
            assertNotNull("qos_goform_set_max 应出现在 rejected_fields 里", r)
            assertEquals(ErrorCode.OUT_OF_RANGE, r!!["reason"]!!.jsonPrimitive.content)
            assertEquals(ConfigLimits.QOS_GOFORM_SET_MAX.first, r["min"]!!.jsonPrimitive.int)
            assertEquals(ConfigLimits.QOS_GOFORM_SET_MAX.last, r["max"]!!.jsonPrimitive.int)
            assertTrue(body.updatedFields().isEmpty())
        }
    }

    @Test
    fun `masked and blank secrets are rejected with distinct reasons`() {
        // token/secret 字段已随全局凭据一并删除，现在唯一的脱敏字段是 goform_password
        putConfig("""{"goform_password":"ab***yz","goform_ip":"  "}""") { body ->
            assertEquals(ErrorCode.MASKED_VALUE, body.rejected("goform_password")!!["reason"]!!.jsonPrimitive.content)
            assertEquals(ErrorCode.BLANK_VALUE, body.rejected("goform_ip")!!["reason"]!!.jsonPrimitive.content)
            assertTrue(body.updatedFields().isEmpty())
        }
    }

    @Test
    fun `wrong type is reported instead of silently dropped`() {
        putConfig("""{"port":"not-a-number","qos_enabled":"yes"}""") { body ->
            assertEquals(ErrorCode.WRONG_TYPE, body.rejected("port")!!["reason"]!!.jsonPrimitive.content)
            assertEquals(ErrorCode.WRONG_TYPE, body.rejected("qos_enabled")!!["reason"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `valid fields still apply and rejected_fields stays empty`() {
        putConfig("""{"sms_code_cleanup_hours":24,"sms_code_enabled":true}""") { body ->
            assertTrue(body["rejected_fields"]!!.jsonArray.isEmpty())
            assertTrue(body.updatedFields().containsAll(listOf("sms_code_cleanup_hours", "sms_code_enabled")))
            assertEquals(24, settings.smsCodeCleanupHours)
            assertTrue(settings.smsCodeEnabled)
        }
    }

    @Test
    fun `partial write reports both applied and rejected fields`() {
        putConfig("""{"sms_code_cleanup_hours":9999,"sms_code_enabled":false}""") { body ->
            assertEquals(listOf("sms_code_enabled"), body.updatedFields())
            assertEquals(
                ErrorCode.OUT_OF_RANGE,
                body.rejected("sms_code_cleanup_hours")!!["reason"]!!.jsonPrimitive.content
            )
        }
    }
}
