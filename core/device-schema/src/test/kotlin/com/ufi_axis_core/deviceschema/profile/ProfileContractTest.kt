package com.ufi_axis_core.deviceschema.profile

import com.ufi_axis_core.contract.DeviceFields
import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.FieldNormalizer
import com.ufi_axis_core.deviceschema.SettingKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * profile 契约测试（计划书 3.5）——**整个改造的最终验收标准**。
 *
 * 断言的形式统一是：喂两个设备各自的原始形状，归一化后的 canonical 输出**逐键相等**。
 * 只要有人把 ZTE 的形状假设写进了归一化引擎（而不是留在 profile 里），
 * [MockAltProfile] 这一侧就会红。
 *
 * 这里不测"某个字段叫什么"——那是 `ZteGoformProfileTest` 的职责（冻结对外字段名）。
 */
class ProfileContractTest {

    private fun canonical(profile: DeviceProfile, group: FieldGroup, raw: JsonObject): JsonObject =
        FieldNormalizer.normalize(raw, profile, group, FieldNormalizer.LegacyAliases.DROP)

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    // ───────────────────────── 读侧 ─────────────────────────

    @Test
    fun `两个设备的信号响应归一化成同一份 canonical`() {
        // ZTE：扁平 + 大小写混杂的字段名；ACME：嵌套在 radio 里 + 完全不同的字段名
        val zte = canonical(
            ZteGoformProfile, FieldGroup.SIGNAL,
            json("""{"nr_rsrp":"-78","Nr_snr":"21","network_provider":"ACME Mobile"}"""),
        )
        val acme = canonical(MockAltProfile, FieldGroup.SIGNAL, MockAltProfile.rawSignal())

        assertEquals(JsonPrimitive(-78L), zte[DeviceFields.Signal.RSRP])
        assertEquals("两个设备的 rsrp 必须一模一样", zte[DeviceFields.Signal.RSRP], acme[DeviceFields.Signal.RSRP])
        assertEquals(zte[DeviceFields.Signal.SINR], acme[DeviceFields.Signal.SINR])
        // ACME 的原值带首尾空格，TRIMMED 之后应与 ZTE 一致
        assertEquals(JsonPrimitive("ACME Mobile"), acme[DeviceFields.Signal.OPERATOR])
        assertEquals(zte[DeviceFields.Signal.OPERATOR], acme[DeviceFields.Signal.OPERATOR])
    }

    @Test
    fun `两套布尔编码归一化成同一份 canonical`() {
        // 设备侧一边是 "1"/"0"，一边是 "on"/"off" —— 对外必须都是 "1"/"0"（冻结契约）
        val zte = canonical(
            ZteGoformProfile, FieldGroup.DEVICE_SETTINGS,
            json("""{"indicator_light_switch":"1","performance_mode":"0"}"""),
        )
        val acme = canonical(MockAltProfile, FieldGroup.DEVICE_SETTINGS, MockAltProfile.rawSettings())

        assertEquals(JsonPrimitive("1"), acme[DeviceFields.DeviceSettings.INDICATOR_LIGHT])
        assertEquals(JsonPrimitive("0"), acme[DeviceFields.DeviceSettings.PERFORMANCE_MODE])
        assertEquals(zte[DeviceFields.DeviceSettings.INDICATOR_LIGHT], acme[DeviceFields.DeviceSettings.INDICATOR_LIGHT])
        assertEquals(zte[DeviceFields.DeviceSettings.PERFORMANCE_MODE], acme[DeviceFields.DeviceSettings.PERFORMANCE_MODE])
    }

    @Test
    fun `敏感度由契约决定而不是由设备决定`() {
        // 同一个 canonical 字段（imei）在两个 profile 里都必须是 MASKED，否则换设备就会漏 PII
        val zte = FieldNormalizer.maskSensitive(
            canonical(ZteGoformProfile, FieldGroup.IDENTITY, json("""{"imei":"860000000000000"}""")),
            ZteGoformProfile,
        )
        val acme = FieldNormalizer.maskSensitive(
            canonical(MockAltProfile, FieldGroup.IDENTITY, MockAltProfile.rawIdentity()),
            MockAltProfile,
        )
        assertEquals(JsonPrimitive("***"), zte[DeviceFields.Identity.IMEI])
        assertEquals(zte[DeviceFields.Identity.IMEI], acme[DeviceFields.Identity.IMEI])
    }

    @Test
    fun `设备原名一个都不对外透出`() {
        // 归一化是 allowlist：设备侧的键名（含容器键）不该出现在输出里
        val acme = canonical(MockAltProfile, FieldGroup.SIGNAL, MockAltProfile.rawSignal())
        listOf("radio", "rsrp_dbm", "sinr_db", "carrier_name").forEach { deviceKey ->
            assertNull("设备原名 $deviceKey 泄漏到对外响应了", acme[deviceKey])
        }
    }

    // ───────────────────────── 写侧 ─────────────────────────

    @Test
    fun `同一个 SettingKey 在两个设备上发不同的命令与参数`() {
        val zte = ZteGoformProfile.writeSpec(SettingKey.LED)!!
        val acme = MockAltProfile.writeSpec(SettingKey.LED)!!

        assertNotEquals("命令名本来就该不同，相同说明抄错了", zte.command, acme.command)
        assertEquals(mapOf("indicator_light_switch" to "1"), zte.encode(mapOf("value" to true)))
        assertEquals(mapOf("led" to "on"), acme.encode(mapOf("value" to true)))
        // 调用方（route / 客户端方法）传的是同一个 Kotlin Boolean —— 这才是抽象成立的意义
    }

    @Test
    fun `设备不支持的写入项返回 null 而不是发空命令`() {
        // ACME 没有定时重启功能
        assertNull(MockAltProfile.writeSpec(SettingKey.RESTART_SCHEDULE))
        // ZTE 有
        assertTrue(ZteGoformProfile.writeSpec(SettingKey.RESTART_SCHEDULE) != null)
    }

    // ───────────────────────── 注册表 ─────────────────────────

    @Test
    fun `注册表按 id 查得到且认不出时返回 null`() {
        assertEquals(ZteGoformProfile, DeviceProfiles.byId("zte-goform"))
        assertEquals("id 匹配不该大小写敏感", ZteGoformProfile, DeviceProfiles.byId("ZTE-Goform"))
        assertNull(DeviceProfiles.byId(""))
        assertNull(DeviceProfiles.byId(null))
        // 认不出时返回 null，由调用方决定兜底（ComponentFactory 回落 DEFAULT + WARN）
        assertNull(DeviceProfiles.byId("acme-rest"))
        assertEquals(ZteGoformProfile, DeviceProfiles.DEFAULT)
    }

    @Test
    fun `注册表里的 id 不重复`() {
        val ids = DeviceProfiles.ALL.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertTrue("id 不能为空", ids.all { it.isNotBlank() })
    }
}
