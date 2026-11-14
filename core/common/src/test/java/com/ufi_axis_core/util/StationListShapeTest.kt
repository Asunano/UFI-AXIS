package com.ufi_axis_core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `station_list` 两种形态的容错解析（计划书 §15 的 P1-29 / 任务 2.11）。
 *
 * 钉三件事：真数组能解、**数组的 JSON 字符串**能解、垃圾输入**不得退化成空列表**。
 *
 * 第三条是这个任务的要害：一旦解析失败被当成「空列表」，`DataScheduler.checkDeviceEvents`
 * 会把上一轮所有设备都算成「离开」，误报一片。本类型的设计就是让那条路**在类型上不可表达**，
 * 这里的断言是为了防止将来有人「顺手」给它加一个返回空数组的兜底。
 */
class StationListShapeTest {

    private val realArray = Json.parseToJsonElement(
        """[{"mac_addr":"AA:BB:CC:00:11:22","hostname":"phone"}]"""
    ) as JsonArray

    @Test
    fun `真数组原样返回同一个对象`() {
        val shape = parseStationList(realArray)
        assertTrue("真数组必须是 Available：$shape", shape is StationListShape.Available)
        // assertSame 而不是 assertEquals：契约写明「原样返回入参，不复制」
        assertSame(realArray, (shape as StationListShape.Available).stations)
    }

    @Test
    fun `数组的 JSON 字符串能解析成真数组`() {
        // 归一化关掉（排障开关）时设备原样透出的双重编码形态
        val doubleEncoded = JsonPrimitive("""[{"mac_addr":"aa:bb:cc:00:11:22"}]""")
        val shape = parseStationList(doubleEncoded)
        assertTrue("双重编码必须能解：$shape", shape is StationListShape.Available)
        assertEquals(1, (shape as StationListShape.Available).stations.size)
    }

    @Test
    fun `空数组是设备事实而不是拿不到`() {
        // 这条区分的是「确实没有设备接入」与「本轮没有信息」——
        // 前者必须能让调用方得出「上一轮那些设备都离开了」。
        val shape = parseStationList(Json.parseToJsonElement("[]"))
        assertTrue(shape is StationListShape.Available)
        assertEquals(0, (shape as StationListShape.Available).stations.size)
    }

    @Test
    fun `字段缺失与 JSON null 与空白串都算设备没给且不该报警`() {
        assertSame(StationListShape.Missing, parseStationList(null))
        assertSame(StationListShape.Missing, parseStationList(JsonNull))
        assertSame(StationListShape.Missing, parseStationList(JsonPrimitive("")))
        assertSame(StationListShape.Missing, parseStationList(JsonPrimitive("   ")))
    }

    @Test
    fun `解不出数组的输入一律是 Malformed 且不携带任何列表`() {
        // 每一种都必须是 Malformed —— 不许悄悄退化成 Available(空数组)
        val garbage = listOf(
            JsonPrimitive("not json at all"),
            JsonPrimitive("{\"mac_addr\":\"aa\"}"),  // 合法 JSON 但是对象不是数组
            JsonPrimitive("42"),
            JsonPrimitive(42),
            JsonPrimitive(true),
            buildJsonObject { },
        )
        for (input in garbage) {
            val shape = parseStationList(input)
            assertSame("应判为 Malformed 的输入：$input", StationListShape.Malformed, shape)
            assertTrue(
                "Malformed 不许是 Available —— 那会让解析失败被当成「所有设备都离开了」",
                shape !is StationListShape.Available,
            )
        }
    }
}
