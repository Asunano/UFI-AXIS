package com.ufi_axis_core.deviceschema

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 设备原始字段 → canonical 字段的归一化器。
 *
 * ## 语义（三条，都是契约）
 *
 * 1. **allowlist（默认拒绝）**：只输出 [DeviceProfile.readSpecs] 登记过的字段。
 *    未登记的设备字段一律不出现在结果里——固件升级新增的字段不会自动泄漏出去。
 * 2. **字段缺失 = 省略 key**，不输出 `null`。
 * 3. **别名链按优先级取第一个命中**：`sources` 里第一个"存在且 decode 不返回 null"的胜出。
 *
 * ## 插在哪一层
 *
 * 归一化必须发生在**设备客户端层**（`GoformXxxClient` 的返回值），不在 route 层。
 * 这样上层缓存（ResponseCache / DataHub / JsonResponseCache）存的天然就是 canonical
 * 数据，而下层的传输缓存（GoformQoS 的 2s 快照）存原始数据——正确性由结构保证，
 * 不依赖每个 route 是否记得调用归一化。
 */
object FieldNormalizer {

    /**
     * 过渡期别名策略。
     *
     * **阶段 4.1（2026-08-29）起线上用 [DROP]**：响应里只有 canonical 字段。
     * [KEEP_PRESENT] 只在迁移期用过（canonical 与命中的设备原名同时输出，让新旧客户端都能工作），
     * 现在保留它是为了给"接一台新设备时临时看看原名是什么"留一条路，**不要**再用它对外发布。
     */
    enum class LegacyAliases {
        /** 同时输出 raw 里存在的、已登记的 source 原名。仍然是 allowlist——未登记字段不输出。 */
        KEEP_PRESENT,

        /** 只输出 canonical。**默认策略。** */
        DROP,
    }

    /**
     * 归一化一组字段。
     *
     * 两段式：先跑 [DeviceProfile.structuralDecoder]（若该分组登记了）把嵌套结构摊平，
     * 再按 allowlist 做字段归一化。**结构解码器的输出一定会过 allowlist**，
     * 所以它不能被用来绕过白名单。
     *
     * @param raw 设备原始响应（null / 空 → 返回空对象）。
     * @param profile 当前设备的映射规则。
     * @param group 只处理该分组的 spec。
     * @param legacy 见 [LegacyAliases]。
     */
    fun normalize(
        raw: JsonObject?,
        profile: DeviceProfile,
        group: FieldGroup,
        legacy: LegacyAliases = LegacyAliases.DROP,
    ): JsonObject {
        if (raw == null || raw.isEmpty()) return JsonObject(emptyMap())
        val flat = flatten(raw, profile, group)
        if (flat.isEmpty()) return JsonObject(emptyMap())
        val out = LinkedHashMap<String, JsonElement>()
        for (spec in profile.readSpecs()) {
            if (spec.group != group) continue
            val hit = resolve(flat, spec)
            if (hit != null) {
                out[spec.canonical] = hit.value
                if (legacy == LegacyAliases.KEEP_PRESENT) {
                    // 过渡期：把 raw 里存在的、本 spec 登记过的原名一并透出（原值，不做 decode，
                    // 否则老客户端拿到的值格式会变——它们读的就是设备原始格式）
                    for (src in spec.sources) {
                        val v = flat[src] ?: continue
                        if (v.isJsonNull()) continue
                        if (src != spec.canonical) out.putIfAbsent(src, v)
                    }
                }
            }
        }
        return JsonObject(out)
    }

    /**
     * 跑结构解码器（若有）。解码器抛异常时退回原始对象 —— 固件返回意外结构不应让整个
     * 端点 500，退化成"该分组字段缺失"更符合"缺失 = 省略 key"的语义。
     */
    fun flatten(raw: JsonObject, profile: DeviceProfile, group: FieldGroup): JsonObject {
        val decoder = profile.structuralDecoder(group) ?: return raw
        return try {
            decoder(raw)
        } catch (_: Exception) {
            raw
        }
    }

    /** 命中结果：来自哪个 source、解码后的值。 */
    data class Hit(val source: String, val value: JsonElement)

    /** 按优先级链解析单个 spec；全部未命中返回 null。 */
    fun resolve(raw: JsonObject, spec: FieldSpec): Hit? {
        for (src in spec.sources) {
            val el = raw[src] ?: continue
            if (el.isJsonNull()) continue
            val decoded = spec.decode(el) ?: continue
            return Hit(src, decoded)
        }
        return null
    }

    // ───────────────────────── 诊断：字段覆盖率 ─────────────────────────

    /** 单个字段的覆盖情况。 */
    data class FieldCoverage(
        val canonical: String,
        val group: FieldGroup,
        /** 命中的设备侧字段名；null = 全部 source 都没命中 */
        val hitSource: String?,
        val sources: List<String>,
    )

    /**
     * 统计某分组的字段覆盖率 —— 适配新设备时的 TODO 清单来源。
     *
     * 没有这个，适配一台新设备只能靠翻代码猜"还差哪些字段"。
     * 结果里不含字段值，因此不涉及敏感数据（值的脱敏见 [maskSensitive]）。
     */
    fun coverage(raw: JsonObject?, profile: DeviceProfile, group: FieldGroup): List<FieldCoverage> {
        val flat = if (raw == null) null else flatten(raw, profile, group)
        return profile.readSpecs()
            .filter { it.group == group }
            .map { spec ->
                FieldCoverage(
                    canonical = spec.canonical,
                    group = spec.group,
                    hitSource = if (flat == null) null else resolve(flat, spec)?.source,
                    sources = spec.sources,
                )
            }
    }

    // ───────────────────────── 诊断/日志脱敏 ─────────────────────────

    private const val MASK = "***"

    /**
     * 按 [Sensitivity] 给已归一化的对象打码，用于**日志与诊断端点**。
     *
     * 正常业务响应不调用本方法（[Sensitivity.MASKED] 的字段业务上需要真值，
     * 例如 WiFi 密码要用来生成二维码）。[Sensitivity.SECRET] 在任何出口都打码。
     */
    fun maskSensitive(obj: JsonObject, profile: DeviceProfile): JsonObject {
        val byCanonical = profile.readSpecs().associateBy { it.canonical }
        val bySource = HashMap<String, FieldSpec>()
        for (spec in profile.readSpecs()) spec.sources.forEach { bySource.putIfAbsent(it, spec) }
        return buildJsonObject {
            for ((k, v) in obj) {
                val spec = byCanonical[k] ?: bySource[k]
                if (spec != null && spec.sensitivity != Sensitivity.PUBLIC) {
                    put(k, JsonPrimitive(MASK))
                } else {
                    put(k, v)
                }
            }
        }
    }

    /** 任何出口都不能给真值的字段名（含其别名），供上层做二次校验。 */
    fun secretKeys(profile: DeviceProfile): Set<String> =
        profile.readSpecs()
            .filter { it.sensitivity == Sensitivity.SECRET }
            .flatMap { listOf(it.canonical) + it.sources }
            .toSet()

    /**
     * 名字里带这些片段就当凭据处理（[maskDump] 用）。
     *
     * 只用于**没经过 allowlist 的原始 dump**：那里的字段没有登记表可查，
     * 名字是唯一的线索。宁可多打码一个无关字段，也不能漏一个凭据。
     */
    private val CREDENTIAL_NAME_HINTS = listOf(
        "passphrase", "password", "passwd", "pwd", "secret", "token",
    )

    /**
     * 给**原始 dump**打码（计划书 9.2）：[maskSensitive] 之上再按字段名兜底。
     *
     * `GET /api/device/goform` 是唯一保持原样透传的读端点，固件升级新增的任何字段
     * 都会直接出现在它的响应里 —— allowlist 管不到它，所以这里必须有名字级兜底。
     * 例：`wifi_chip1_ssid1_password_encode` 没登记在 profile 里，但名字里有 `password`。
     */
    fun maskDump(obj: JsonObject, profile: DeviceProfile): JsonObject {
        val byRegistry = maskSensitive(obj, profile)
        return buildJsonObject {
            for ((k, v) in byRegistry) {
                val lower = k.lowercase()
                if (CREDENTIAL_NAME_HINTS.any { lower.contains(it) }) {
                    put(k, JsonPrimitive(MASK))
                } else {
                    put(k, v)
                }
            }
        }
    }
}

