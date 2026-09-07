package com.ufi_axis_core.deviceschema.profile

import com.ufi_axis_core.contract.DeviceFields
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.FieldNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.BufferedReader

/**
 * 固件**原始响应**（goform 键） → canonical 契约 的 golden 测试（计划书 10.4）。
 *
 * ## 为什么在 core 侧也要有一份夹具
 *
 * `app/data` 的 `ZteF50FirmwareParseTest` 用的三份 JSON 都是 **core 响应形状**
 * （`limit_value` / `limit_unit_display` / `limit_bytes`），验的是"客户端能不能解析 core 的输出"。
 * 归一化层的输入是**设备原始键**（`data_volume_limit_size: "470_1024"` 这类），那侧一份都没有 ——
 * 所以这里补的是**方向相反**的夹具：设备怎么答 → core 该输出什么。
 *
 * 两侧的期望值刻意用同一组数字（470 GB / 504658657280 字节 / 90%），
 * 任何一侧改了形状，另一侧的断言就会对不上。
 */
class ZteGoformRawCaptureTest {

    private fun loadRaw(name: String): JsonObject =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "缺少测试资源 $name" }
            .bufferedReader().use(BufferedReader::readText)
            .let { Json.parseToJsonElement(it).jsonObject }

    @Test
    fun `原始流量限额响应归一化成 canonical 契约`() {
        val raw = loadRaw("zte_f50_goform_traffic_raw.json")
        val out = checkNotNull(
            FieldNormalizer.normalize(
                raw, ZteGoformProfile, FieldGroup.TRAFFIC_LIMIT,
                FieldNormalizer.LegacyAliases.DROP,
            )
        )

        // 布尔统一成 "1"/"0" 字符串（契约的一部分，见 Decoders.BOOL_01）。
        // AUTO_CLEAR 的设备原值是 "off" —— 这一组用 on/off 编码，归一化后才是 "0"。
        assertEquals(JsonPrimitive("1"), out[DeviceFields.TrafficLimit.ENABLED])
        assertEquals(JsonPrimitive("0"), out[DeviceFields.TrafficLimit.AUTO_CLEAR])

        // 复合串 "470_1024" 拆成三条派生键（2.8）：1024 这个乘数表示 GB
        assertEquals(JsonPrimitive("470"), out[DeviceFields.TrafficLimit.LIMIT_VALUE])
        assertEquals(JsonPrimitive("GB"), out[DeviceFields.TrafficLimit.LIMIT_UNIT_DISPLAY])
        assertEquals(
            "与 app 侧夹具 zte_f50_firmware_traffic.json 的 limit_bytes 必须一致",
            JsonPrimitive(504658657280L), out[DeviceFields.TrafficLimit.LIMIT_BYTES],
        )

        assertEquals(JsonPrimitive("90"), out[DeviceFields.TrafficLimit.ALERT_PERCENT])
        assertEquals(JsonPrimitive("5"), out[DeviceFields.TrafficLimit.CLEAR_DATE])
        // 上下行**交叉绑定**：设备的 monthly_rx_bytes 其实是上传、monthly_tx_bytes 是下载
        // （2026-09-01 真机实测，见 ZteGoformProfile 的注释）。夹具里 rx=123456789012、
        // tx=9876543210，归一化后必须互换，canonical rx 才是"下载"。
        assertEquals(JsonPrimitive("9876543210"), out[DeviceFields.TrafficLimit.MONTHLY_RX_BYTES])
        assertEquals(JsonPrimitive("123456789012"), out[DeviceFields.TrafficLimit.MONTHLY_TX_BYTES])
        assertEquals(JsonPrimitive("1717000000"), out[DeviceFields.TrafficLimit.MONTHLY_TIME])

        // allowlist：固件自带的未登记字段不输出
        assertFalse("未登记字段必须被丢掉", out.containsKey("unknown_firmware_field"))
        // 复合串与恒为 "MB" 的单位字段都不进对外契约（2.8）
        assertFalse(out.containsKey("data_volume_limit_size"))
        assertFalse(out.containsKey("data_volume_limit_unit"))
    }

    @Test
    fun `覆盖率诊断能在原始响应上指出缺哪些字段`() {
        val raw = loadRaw("zte_f50_goform_traffic_raw.json")
        val cov = FieldNormalizer.coverage(raw, ZteGoformProfile, FieldGroup.TRAFFIC_LIMIT)
            .associateBy { it.canonical }

        assertEquals("data_volume_limit_switch", cov[DeviceFields.TrafficLimit.ENABLED]?.hitSource)
        assertNotNull("派生键也算命中", cov[DeviceFields.TrafficLimit.LIMIT_BYTES]?.hitSource)
        // 这份夹具里没有 used_bytes —— 覆盖率报告就该把它列成"未命中"（适配新设备时的 TODO）
        assertEquals(null, cov[DeviceFields.TrafficLimit.USED_BYTES]?.hitSource)
    }
}
