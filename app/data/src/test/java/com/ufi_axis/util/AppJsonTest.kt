package com.ufi_axis.util

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppJson (kotlinx.serialization.Json) 行为单测（C1：序列化统一 / lenient）。
 *
 * 验证：
 *  - lenient 能将 Goform 透传的字符串数字（如 "rsrp":"99"）解析为 Int
 *  - 原生数字 + 默认值填充正常
 *  - ignoreUnknownKeys 容忍后端多返回的字段
 *  - round-trip（encode -> decode）保持一致
 *
 * 注：使用 SignalSample.serializer() + Json 的 StringFormat 成员函数
 * （encodeToString / decodeFromString 的 (serializer, value) 形式），
 * 避免对 kotlinx 顶层扩展函数的脆弱依赖。
 */
class AppJsonTest {

    @Serializable
    data class SignalSample(
        val rsrp: Int,
        val sinr: Double = 0.0,
        val carrier: String = "UNKNOWN"
    )

    @Test
    fun `lenient decodes goform string-number into Int field`() {
        // Goform 透传的 rsrp 常为字符串形态 "99"
        val json = """{"rsrp":"99"}"""
        val sample = AppJson.decodeFromString(SignalSample.serializer(), json)
        assertEquals(99, sample.rsrp)
    }

    @Test
    fun `lenient also accepts native number and fills defaults`() {
        val json = """{"rsrp":-70,"sinr":12.5}"""
        val sample = AppJson.decodeFromString(SignalSample.serializer(), json)
        assertEquals(-70, sample.rsrp)
        assertEquals(12.5, sample.sinr, 0.0001)
        assertEquals("UNKNOWN", sample.carrier)
    }

    @Test
    fun `ignoreUnknownKeys tolerates extra server fields`() {
        val json = """{"rsrp":"23","extra_unknown":"drop_me","sinr":"3.5"}"""
        val sample = AppJson.decodeFromString(SignalSample.serializer(), json)
        assertEquals(23, sample.rsrp)
        assertEquals(3.5, sample.sinr, 0.0001)
    }

    @Test
    fun `round-trip encode then decode is stable`() {
        val original = SignalSample(rsrp = -65, sinr = 8.0, carrier = "CMCC")
        val json = AppJson.encodeToString(SignalSample.serializer(), original)
        val decoded = AppJson.decodeFromString(SignalSample.serializer(), json)
        assertEquals(original, decoded)
    }
}
