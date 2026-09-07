package com.ufi_axis.data.model

import com.ufi_axis.util.AppJson
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader

/**
 * F3 — 真实 ZTE F50 固件（goform）JSON → 模型解析端到端测试。
 *
 * ZTE F50 通过 goform 接口返回计费/流量/连接状态，且数值常以**字符串**形态透传
 * （如 `"monthly_rx_bytes":"123456789012"`）。解析管线为 `AppJson`（kotlinx.serialization，
 * isLenient + ignoreUnknownKeys + coerceInputValues）。本测试将抓包级固件 JSON 喂入该管线，
 * 断言字段正确映射到 [TrafficLimitConfig] / [TrafficRealtime] / [NetworkStatusResponse]。
 *
 * 注意（根因）：固件 goform 原始键（realtime_rx_bytes / data_volume_limit_unit /
 * data_volume_alert_percent 等）由**服务端**（core/goform、core/scheduler、core/api）在透传前
 * 重命名为规范客户端键（rx_bytes / limit_unit / alert_percent）并做类型转换（如
 * data_volume_limit_switch "0"/"1" → enabled:Boolean）。客户端 DTO 接收的是规范键。
 * 为使本测试直接用固件原始键驱动模型并断言真实映射值，模型对可兼容字段加了 `@JsonNames`
 * 别名（保留规范键，生产路径不受影响）；`enabled` 由服务端字符串→Boolean 转型，故测试中
 * 用规范 Boolean 值断言（固件字符串无法直接映射 Boolean）。
 *
 * 覆盖正常映射 + 三种异常：
 *  - 缺失字段（coerceInputValues 回落默认值）
 *  - 类型错配（**抛 JsonDecodingException**，coerceInputValues 不覆盖此形态，见对应用例注释）
 *  - 多余字段（ignoreUnknownKeys 容忍）
 *
 * 注：不依赖真机，纯 JVM 单测（app:data testDebugUnitTest）。
 */
class ZteF50FirmwareParseTest {

    private fun loadResource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "缺少测试资源 $name"
        }.bufferedReader().use(BufferedReader::readText)

    // ========== 正常：计费流量限额（含 monthly_rx_bytes / monthly_tx_bytes） ==========

    @Test
    // 名称里不能出现 `/`：JVM 方法名非法字符，会让整个 app:data 测试源集编译失败（2026-08-26 修）
    fun `firmware traffic-limit JSON maps monthly rx and tx bytes correctly`() {
        val json = loadResource("zte_f50_firmware_traffic.json")
        val cfg = AppJson.decodeFromString(TrafficLimitConfig.serializer(), json)

        // ZTE 透传为字符串，isLenient 解析为 Long
        assertEquals(123456789012L, cfg.monthly_rx_bytes)
        assertEquals(9876543210L, cfg.monthly_tx_bytes)
        assertEquals(1717000000L, cfg.monthly_time)
        // 以下使用 goform 原始键（data_volume_alert_percent），经模型 @JsonNames 别名映射到
        // 规范字段；取值刻意区别于默认值（90/true），验证是真实映射而非回落默认值。
        // enabled 为服务端将 data_volume_limit_switch "0"/"1" 字符串转型为 Boolean 后的规范值。
        assertEquals("90", cfg.alert_percent) // data_volume_alert_percent → 默认 "80"，非默认 → 真实命中
        assertEquals(true, cfg.enabled)       // 服务端转型后的规范 Boolean 值

        // core 2.8：限额不再以复合串（"470_1024"）下发，而是拆好的三个字段。
        // 这条断言钉住的是一个真实 bug：客户端曾自己解析复合串，470_1024 解析成 null → 限额按 0
        // 处理 → 流量告警在 GB 档位永不触发。现在限额字节由 core 给出，客户端不做单位换算。
        assertEquals("470", cfg.limit_value)
        assertEquals("GB", cfg.limit_unit_display)
        assertEquals(504658657280L, cfg.limit_bytes)          // 470 GB（1024 进制）
        assertTrue("限额 MB 必须 > 0，否则告警判定会直接 return", cfg.limit_bytes / (1024L * 1024L) > 0L)
        assertEquals(133333332222L, cfg.used_bytes)
    }

    // ========== 正常：实时吞吐 ==========

    @Test
    fun `firmware realtime JSON maps throughput fields correctly`() {
        val json = loadResource("zte_f50_realtime.json")
        val rt = AppJson.decodeFromString(TrafficRealtime.serializer(), json)

        assertEquals(2097152L, rt.rx_bytes)
        assertEquals(1048576L, rt.tx_bytes)
        assertEquals(524288L, rt.realtime_rx_thrpt)
        assertEquals(262144L, rt.realtime_tx_thrpt)
    }

    // ========== 正常：连接状态（ppp_status 判定蜂窝在线） ==========

    @Test
    fun `firmware network-status JSON maps ppp_status to cellular connection`() {
        val json = loadResource("zte_f50_network_status.json")
        val status = AppJson.decodeFromString(NetworkStatusResponse.serializer(), json)

        assertEquals("ppp_connected", status.ppp_status)
        assertEquals("中国移动", status.operator)
        assertTrue(status.isCellularConnected)
    }

    // ========== 异常 1：缺失字段 → 回落默认值 ==========

    @Test
    fun `missing monthly_tx_bytes coerces to default zero`() {
        // 仅提供 rx，缺 tx / time
        val json = """{"monthly_rx_bytes":"500"}"""
        val cfg = AppJson.decodeFromString(TrafficLimitConfig.serializer(), json)

        assertEquals(500L, cfg.monthly_rx_bytes)
        assertEquals(0L, cfg.monthly_tx_bytes)   // 默认值
        assertEquals(0L, cfg.monthly_time)        // 默认值
    }

    // ========== 异常 2：类型错配 → 抛 JsonDecodingException（不是回落默认值） ==========

    @Test
    fun `type mismatch on monthly_rx_bytes throws instead of coercing`() {
        // 2026-08-26 修正断言：原用例期望"回落默认 0"，但 kotlinx 的 coerceInputValues
        // **只覆盖 null → 默认值与未知枚举值**，不处理 Long 字段收到 bool/数组这类类型错配；
        // isLenient 也只放宽引号数字（"500" → 500L），非数字字面量仍抛异常。
        // 因此真实行为是抛 JsonDecodingException，由调用方（ToolsModule.loadTrafficLimit 的 try/catch）兜。
        // 是否需要让模型容忍设备的畸形类型另开任务（T17），不在本用例范围内。
        val jsonBool = """{"monthly_rx_bytes":true,"monthly_tx_bytes":"10"}"""
        assertThrows(SerializationException::class.java) {
            AppJson.decodeFromString(TrafficLimitConfig.serializer(), jsonBool)
        }

        val jsonArr = """{"monthly_rx_bytes":[1,2,3]}"""
        assertThrows(SerializationException::class.java) {
            AppJson.decodeFromString(TrafficLimitConfig.serializer(), jsonArr)
        }
    }

    // ========== 异常 3：多余字段 → 忽略并正确解析 ==========

    @Test
    fun `extra unknown fields are ignored and known fields map correctly`() {
        val json = """{
            "monthly_rx_bytes":"999",
            "monthly_tx_bytes":"111",
            "unknown_firmware_field":"DROP_ME",
            "another_extra":42
        }"""
        val cfg = AppJson.decodeFromString(TrafficLimitConfig.serializer(), json)

        assertEquals(999L, cfg.monthly_rx_bytes)
        assertEquals(111L, cfg.monthly_tx_bytes)
    }

    // ========== 健壮性：ppp_status 离线变体 ==========

    @Test
    fun `ppp_status disconnected yields offline`() {
        val json = """{"ppp_status":"ppp_disconnected","operator":"CMCC"}"""
        val status = AppJson.decodeFromString(NetworkStatusResponse.serializer(), json)
        assertFalse(status.isCellularConnected)
    }
}
