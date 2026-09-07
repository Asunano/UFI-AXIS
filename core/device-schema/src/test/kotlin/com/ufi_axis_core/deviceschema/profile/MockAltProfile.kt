package com.ufi_axis_core.deviceschema.profile

import com.ufi_axis_core.contract.DeviceFields
import com.ufi_axis_core.deviceschema.Decoders
import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.FieldSpec
import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.deviceschema.Sensitivity
import com.ufi_axis_core.deviceschema.WriteSpec
import com.ufi_axis_core.deviceschema.fieldOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 第二类设备后台的 profile（计划书 3.4）—— 虚构的 "acme" 型号，**只存在于测试源集**。
 *
 * ## 它为什么必须存在
 *
 * 整个改造只有一条最终验收标准：**新增一类设备 = 新写一个 profile 文件，route / web / app
 * 一行都不改**。只有真的写出第二个实现，才能证明抽象没有偷偷把 ZTE 的形状写进接口。
 *
 * ## 为什么放测试源集而不是 main
 *
 * 它没有对应的真实设备，放 main 就是发布死代码，还会出现在 `DeviceProfiles.ALL` 的可选项里
 * 误导使用者。放测试源集一样能跑契约测试（[ProfileContractTest]），需要接真设备时
 * 照着它复制到 main 并加进 `DeviceProfiles.ALL` 即可。
 *
 * ## 故意与 ZTE 处处不同（这才是有效的对照）
 *
 * - 字段名风格不同：`rsrp_dbm` / `imei_number`，不是 `nr_rsrp` / `imei`；
 * - 布尔编码不同：读侧设备给 `"on"` / `"off"`（`BOOL_01` 归一成 `"1"` / `"0"`）；
 * - 响应是**嵌套**的：信号字段在 `radio` 子对象里 → 用 `structuralDecoder` 摊平；
 * - 命令名与写入参数名完全不同；
 * - 支持的写入项**少一项**（没有定时重启），用来验证"设备不支持就返回 null"。
 */
object MockAltProfile : DeviceProfile {

    override val id: String = "acme-rest"
    override val displayName: String = "ACME REST（虚构，仅用于 profile 契约测试）"

    private val SPECS: List<FieldSpec> = listOf(
        // 信号：设备把值放在 radio 子对象里，键名也不同
        fieldOf(DeviceFields.Signal.RSRP, FieldGroup.SIGNAL, "rsrp_dbm", decode = Decoders.NUMERIC),
        fieldOf(DeviceFields.Signal.SINR, FieldGroup.SIGNAL, "sinr_db", decode = Decoders.NUMERIC),
        fieldOf(DeviceFields.Signal.OPERATOR, FieldGroup.SIGNAL, "carrier_name", decode = Decoders.TRIMMED),
        // 身份：IMEI 同样是 MASKED —— 敏感度是**对外契约**，不能由设备决定
        fieldOf(DeviceFields.Identity.IMEI, FieldGroup.IDENTITY, "imei_number", sensitivity = Sensitivity.MASKED),
        // 设备设置：设备侧是 "on"/"off"，归一成 "1"/"0"（与 ZTE 的输出一致）
        fieldOf(DeviceFields.DeviceSettings.INDICATOR_LIGHT, FieldGroup.DEVICE_SETTINGS, "led_state", decode = Decoders.BOOL_01),
        fieldOf(DeviceFields.DeviceSettings.PERFORMANCE_MODE, FieldGroup.DEVICE_SETTINGS, "perf_profile", decode = Decoders.BOOL_01),
    )

    override fun readSpecs(): List<FieldSpec> = SPECS

    override fun cmdsFor(group: FieldGroup): List<String> = when (group) {
        FieldGroup.SIGNAL -> listOf("radio")
        FieldGroup.IDENTITY -> listOf("device")
        FieldGroup.DEVICE_SETTINGS -> listOf("settings")
        else -> emptyList()
    }

    /** 信号字段在 `radio` 子对象里，先摊平再走 allowlist（两段式，与 ZTE 同一个机制）。 */
    override fun structuralDecoder(group: FieldGroup): ((JsonObject) -> JsonObject)? =
        if (group == FieldGroup.SIGNAL) { raw ->
            val out = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(raw)
            (raw["radio"] as? JsonObject)?.forEach { (k, v) -> out.putIfAbsent(k, v) }
            JsonObject(out)
        } else null

    override fun writeSpec(key: SettingKey): WriteSpec? = WRITE_SPECS[key]

    private val WRITE_SPECS: Map<SettingKey, WriteSpec> = mapOf(
        SettingKey.LED to WriteSpec(
            command = "setLed",
            encode = { p -> mapOf("led" to onOff(p["value"])) },
        ),
        SettingKey.PERFORMANCE_MODE to WriteSpec(
            command = "setPerfProfile",
            encode = { p -> mapOf("profile" to if (onOff(p["value"]) == "on") "turbo" else "eco") },
        ),
        // 故意不登记 RESTART_SCHEDULE：本设备没有这个功能
    )

    private fun onOff(v: Any?): String = when (v) {
        is Boolean -> if (v) "on" else "off"
        is Number -> if (v.toInt() != 0) "on" else "off"
        is String -> if (v.equals("on", true) || v == "1" || v.equals("true", true)) "on" else "off"
        else -> "off"
    }

    /** 测试夹具：本设备"原样"的响应形状（嵌套 + on/off + 自己的字段名）。 */
    fun rawSignal(): JsonObject = json("""{"radio":{"rsrp_dbm":"-78","sinr_db":"21","carrier_name":" ACME Mobile "}}""")

    fun rawIdentity(): JsonObject = json("""{"imei_number":"860000000000000"}""")

    fun rawSettings(): JsonObject = json("""{"led_state":"on","perf_profile":"off"}""")

    private fun json(s: String): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(s).jsonObject
}

