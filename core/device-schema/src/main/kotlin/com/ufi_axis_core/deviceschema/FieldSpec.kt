package com.ufi_axis_core.deviceschema

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 字段分组 —— 对应一次设备查询/一个 API 端点的字段集合。
 *
 * 分组存在的意义有两个：① `DeviceProfile.cmdsFor(group)` 决定要向设备查哪些命令；
 * ② 归一化时只在本组的 spec 里找，避免跨端点误命中同名字段。
 */
enum class FieldGroup {
    /** GET /api/device/settings */
    DEVICE_SETTINGS,

    /** GET /api/device/lan-settings */
    LAN_SETTINGS,

    /** GET /api/wifi/settings */
    WIFI_SETTINGS,

    /** GET /api/wifi/clients */
    WIFI_CLIENTS,

    /** GET /api/network/band-status */
    BAND_STATUS,

    /** GET /api/network/cell-info · /api/network/neighbor-cells */
    CELL_INFO,

    /** GET /api/device/identity */
    IDENTITY,

    /** GET /api/device/traffic-limit */
    TRAFFIC_LIMIT,

    /** 信号（REST 与 WS `signal` 频道共用） */
    SIGNAL,

    /** 连接状态（ppp_status / network_type，多端点共用） */
    CONNECTION,
}

/**
 * 字段敏感度。默认 [PUBLIC]，凭据与 PII 必须显式声明。
 *
 * 归一化本身是 allowlist（未登记的设备字段不输出），这里进一步控制**已登记字段**
 * 在日志与诊断出口的可见性。
 */
enum class Sensitivity {
    /** 正常输出。 */
    PUBLIC,

    /** 正常响应给真值，但日志与诊断端点必须打码（如 WiFi 密码、手机号、IMEI）。 */
    MASKED,

    /** 任何出口都不给真值。当前无字段使用，预留给将来可能出现的凭据类字段。 */
    SECRET,
}

/**
 * 一个 canonical 字段的映射规则。
 *
 * @param canonical 对外字段名，取自 `core:contract` 的 `DeviceFields`。**不要写字面量。**
 * @param group 所属分组。
 * @param sources 设备侧字段名的**优先级链**：按顺序取第一个存在且解码成功的。
 *   例如 rsrp 的链是 `nr_rsrp` > `Z5g_rsrp` > `lte_rsrp`——不同固件填的字段不一样。
 * @param sensitivity 见 [Sensitivity]。
 * @param decode 值变换。默认原样透出（**保持字符串就是字符串**——历史上客户端按字符串
 *   解析 `"1"`/`"0"`，擅自转成 Boolean 会打断它们）。
 */
data class FieldSpec(
    val canonical: String,
    val group: FieldGroup,
    val sources: List<String>,
    val sensitivity: Sensitivity = Sensitivity.PUBLIC,
    val decode: (JsonElement) -> JsonElement? = Decoders.AS_IS,
) {
    init {
        require(canonical.isNotBlank()) { "canonical 不能为空" }
        require(sources.isNotEmpty()) { "$canonical 至少要有一个 source（设备侧字段名）" }
    }
}

/**
 * 常用值变换。
 *
 * **原则：只做"必须做"的变换。** 归一化的目标是稳定字段名，不是顺手美化数据——
 * 每一次值格式变化都是一次潜在的客户端解析中断。
 */
object Decoders {

    /** 原样透出（默认）。 */
    val AS_IS: (JsonElement) -> JsonElement? = { it }

    /** 空字符串视为"字段缺失"（省略该 key），避免前端把 `""` 当成有效值渲染。 */
    val NON_BLANK: (JsonElement) -> JsonElement? = { el ->
        val s = el.asStringOrNull()
        if (s.isNullOrBlank()) null else JsonPrimitive(s)
    }

    /** 去首尾空白后透出；空则视为缺失。 */
    val TRIMMED: (JsonElement) -> JsonElement? = { el ->
        val s = el.asStringOrNull()?.trim()
        if (s.isNullOrEmpty()) null else JsonPrimitive(s)
    }

    /**
     * 数值字段：能解析成 Long 就给数字，否则视为缺失。
     * 只用于本来就是数字语义的字段（如 `mtu`），**不要**用在 `"1"`/`"0"` 开关上。
     */
    val NUMERIC: (JsonElement) -> JsonElement? = { el ->
        val s = el.asStringOrNull()?.trim()
        s?.toLongOrNull()?.let { JsonPrimitive(it) }
    }

    /**
     * 把设备侧的多种布尔编码统一成 `"1"` / `"0"` 字符串。
     *
     * 为什么统一成字符串而不是 JSON Boolean：web 现在写 `String(v) === '1'`、
     * app 写 `== "1"`，改成 Boolean 会让两端同时读不到。**这是冻结契约的一部分。**
     */
    val BOOL_01: (JsonElement) -> JsonElement? = { el ->
        val s = el.asStringOrNull()?.trim()
        when {
            s == null -> null
            TRUTHY.any { it.equals(s, ignoreCase = true) } -> JsonPrimitive("1")
            FALSY.any { it.equals(s, ignoreCase = true) } -> JsonPrimitive("0")
            else -> null
        }
    }

    /** 按映射表翻译值域（如网络类型 `"20"` → `"5G"`）；表里没有的原样透出。 */
    fun mapValues(table: Map<String, String>): (JsonElement) -> JsonElement? = { el ->
        val s = el.asStringOrNull()
        if (s == null) null else JsonPrimitive(table[s] ?: s)
    }

    /** 自定义字符串变换；返回 null 视为缺失。 */
    fun ofString(transform: (String) -> String?): (JsonElement) -> JsonElement? = { el ->
        val s = el.asStringOrNull()
        if (s == null) null else transform(s)?.let { JsonPrimitive(it) }
    }

    internal val TRUTHY = listOf("1", "on", "true", "yes", "SERVER")
    internal val FALSY = listOf("0", "off", "false", "no")
}

/**
 * JsonElement → String。
 *
 * goform 的值几乎全是字符串，但个别固件会返回真正的数字/布尔；数组与对象不参与
 * 字符串化（返回 null），由调用方按结构处理。
 */
internal fun JsonElement.asStringOrNull(): String? = when {
    this is JsonNull -> null
    this is JsonPrimitive -> content
    else -> null
}

/** 取出原始值用于结构型字段（数组 / 对象 / 双重编码的 JSON 字符串）。 */
internal fun JsonElement.isJsonNull(): Boolean = this is JsonNull

/** 便捷构造：单一 source。 */
fun fieldOf(
    canonical: String,
    group: FieldGroup,
    source: String,
    sensitivity: Sensitivity = Sensitivity.PUBLIC,
    decode: (JsonElement) -> JsonElement? = Decoders.AS_IS,
): FieldSpec = FieldSpec(canonical, group, listOf(source), sensitivity, decode)

/** 便捷构造：多 source 优先级链。 */
fun fieldOf(
    canonical: String,
    group: FieldGroup,
    vararg sources: String,
    sensitivity: Sensitivity = Sensitivity.PUBLIC,
    decode: (JsonElement) -> JsonElement? = Decoders.AS_IS,
): FieldSpec = FieldSpec(canonical, group, sources.toList(), sensitivity, decode)
