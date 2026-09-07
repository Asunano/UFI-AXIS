package com.ufi_axis.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * AppJsonConverterFactory 单测（T9 收尾后：kotlinx.serialization 独占）。
 *
 * 验证：
 *  - 对 @Serializable 的 app/data 模型类型，responseBodyConverter 返回非 null 且走 kotlinx 路径
 *  - 对 kotlinx.serialization.json.JsonElement 类型，返回非 null 的 kotlinx converter
 *  - kotlinx 路径能正确将 Goform 字符串数字 body 反序列化为模型对象（端到端）
 */
class AppJsonConverterFactoryTest {

    @Serializable
    data class DemoModel(val rsrp: Int, val name: String = "x")

    private val factory = AppJsonConverterFactory.create() // 默认 AppJson（kotlinx 独占）
    // responseBodyConverter 的 retrofit 参数为非空类型，需传入一个真实（最小）Retrofit 实例
    private val retrofit = Retrofit.Builder().baseUrl("https://localhost/").build()

    @Test
    fun `kotlinx model type yields non-null kotlinx converter and decodes correctly`() {
        val converter = factory.responseBodyConverter(DemoModel::class.java, emptyArray(), retrofit)
        assertNotNull("kotlinx 模型应返回非 null converter", converter)
        assertTrue(
            "应走 kotlinx 路径 (KotlinxResponseBodyConverter)，实际=${converter!!::class.java.name}",
            converter::class.java.name.contains("KotlinxResponseBodyConverter")
        )

        // 功能验证：字符串数字 body 经 kotlinx 路径解码为正确模型
        val body = """{"rsrp":"-70","name":"CMCC"}""".toResponseBody("application/json".toMediaType())
        val decoded = converter.convert(body)
        assertTrue("解码结果应为 DemoModel", decoded is DemoModel)
        val model = decoded as DemoModel
        assertEquals(-70, model.rsrp)
        assertEquals("CMCC", model.name)
    }

    @Test
    fun `kotlinx JsonElement type yields non-null kotlinx converter`() {
        val converter = factory.responseBodyConverter(JsonElement::class.java, emptyArray(), retrofit)
        assertNotNull("JsonElement 应返回非 null kotlinx converter", converter)
        assertTrue(
            "应走 kotlinx 路径 (KotlinxResponseBodyConverter)，实际=${converter!!::class.java.name}",
            converter::class.java.name.contains("KotlinxResponseBodyConverter")
        )

        val body = """{"foo":"bar"}""".toResponseBody("application/json".toMediaType())
        val decoded = converter.convert(body)
        assertTrue("kotlinx 应将 JsonElement body 解析为 JsonObject", decoded is JsonObject)
        val obj = decoded as JsonObject
        assertEquals("bar", obj["foo"]?.jsonPrimitive?.content)
    }
}
