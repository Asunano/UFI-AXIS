package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.FieldNormalizer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/**
 * 设备字段归一化的接入点 —— goform 客户端与 `:core:device-schema` 之间唯一的胶水。
 *
 * ## 为什么在这一层
 *
 * 归一化必须发生在**设备客户端返回值上**，不在 route 层。这样：
 * - 上层缓存（`ResponseCache` / `DataHub` / `JsonResponseCache`）存的天然是 canonical 数据；
 * - 下层传输缓存（`GoformQoS` 的 2s 快照）存的是设备原始响应；
 * - 正确性由结构保证，不依赖每个 route 是否记得调用归一化。
 *
 * ## 回退（计划书原则 11 / D7）
 *
 * [profile] 为 `null` 时整个映射层短路成原样透传。这就是一键回退的开关本体，
 * 由 `AppSettings.fieldNormalizationEnabled` + `ComponentFactory.resolveDeviceProfile()` 决定，
 * 只在构造组件图时读一次（改配置要重启后台服务，缓存因此天然是新的）。
 *
 * ## 别名策略（阶段 4.1 已切 DROP）
 *
 * 默认 [FieldNormalizer.LegacyAliases.DROP]：响应里**只有 canonical 字段**，设备原名一律不输出。
 * 过渡期的双输出（canonical + 设备原名）已在 core / web / app 同一批改完后关掉。
 */
internal class GoformFieldMapper(
    private val profile: DeviceProfile?,
    private val legacy: FieldNormalizer.LegacyAliases = FieldNormalizer.LegacyAliases.DROP,
) {

    /** 当前是否在归一化。 */
    val enabled: Boolean get() = profile != null

    /** 生效中的 profile id；null = 归一化已关（诊断用，见计划书 10.2）。 */
    val profileId: String? get() = profile?.id

    /**
     * 归一化一次查询结果。
     *
     * `null` 进 `null` 出 —— 上层用 null 区分"查询失败"与"查到了但字段为空"，
     * 归一化不能把失败变成空对象。
     */
    fun normalize(group: FieldGroup, raw: JsonObject?): JsonObject? {
        val p = profile ?: return raw
        if (raw == null) return null
        return FieldNormalizer.normalize(raw, p, group, legacy)
    }

    /**
     * 该分组要查的 cmd 列表。
     *
     * profile 没登记（返回空）时用 [fallback]，即调用处原有的硬编码列表 ——
     * 保证迁移过程中任何一步都不会把查询打空。
     */
    fun cmds(group: FieldGroup, fallback: List<String>): List<String> =
        profile?.cmdsFor(group)?.takeIf { it.isNotEmpty() } ?: fallback

    /**
     * 给原始 dump 打码（计划书 9.2），用于 `GET /api/device/goform` 这类**不过 allowlist**的出口。
     *
     * profile 为 `null`（D7 回退）时没有敏感度登记表可查，只能原样返回 ——
     * 与"整层短路成透传"的语义一致；回退开关本来就不是长期配置。
     */
    fun maskDump(raw: JsonObject?): JsonObject? {
        val p = profile ?: return raw
        if (raw == null) return null
        return FieldNormalizer.maskDump(raw, p)
    }

    /**
     * 字段覆盖率报告（计划书 10.1）——适配新设备时的 TODO 清单。
     *
     * 逐个 [FieldGroup] 向设备查一次（[query] 就是 `GoformClient::query`），比对登记表，
     * 输出"登记了几个 / 命中了几个 / 哪些一个 source 都没命中 / 命中的是哪个 source"。
     *
     * **输出里没有任何字段值**，只有字段名，因此不需要再脱敏（值的脱敏见 [maskDump]）。
     * [soloCmds] 会单独查 —— 与其它 cmd 合并会让设备返回空，混在一起查会误报"未命中"。
     */
    suspend fun coverageReport(query: suspend (List<String>) -> JsonObject?): JsonObject {
        val p = profile ?: return buildJsonObject {
            put("normalization_enabled", JsonPrimitive(false))
            put("hint", JsonPrimitive("字段归一化已关闭（field_normalization_enabled=false），没有登记表可比对"))
        }
        // 先把设备查询全做完再拼 JSON：buildJsonObject 的 lambda 不是 suspend 的，
        // 在里面调 query 会编译失败。
        val scanned = FieldGroup.entries.map { group ->
            val cmds = p.cmdsFor(group)
            val raw = if (cmds.isEmpty()) null else queryGroup(p, group, cmds, query)
            Triple(group, cmds.isNotEmpty(), FieldNormalizer.coverage(raw, p, group))
        }
        return buildJsonObject {
            put("normalization_enabled", JsonPrimitive(true))
            put("profile_id", JsonPrimitive(p.id))
            put("profile_name", JsonPrimitive(p.displayName))
            putJsonObject("groups") {
                for ((group, queried, cov) in scanned) {
                    putJsonObject(group.name) {
                        put("queried", JsonPrimitive(queried))
                        put("registered", JsonPrimitive(cov.size))
                        put("hit", JsonPrimitive(cov.count { it.hitSource != null }))
                        putJsonArray("missing") {
                            cov.filter { it.hitSource == null }.forEach { add(JsonPrimitive(it.canonical)) }
                        }
                        putJsonObject("hit_source") {
                            cov.forEach { c -> c.hitSource?.let { put(c.canonical, JsonPrimitive(it)) } }
                        }
                    }
                }
            }
        }
    }

    private suspend fun queryGroup(
        p: DeviceProfile,
        group: FieldGroup,
        cmds: List<String>,
        query: suspend (List<String>) -> JsonObject?,
    ): JsonObject? {
        val solo = p.soloCmds(group).toSet()
        val merged = LinkedHashMap<String, JsonElement>()
        cmds.filterNot { it in solo }.takeIf { it.isNotEmpty() }?.let { query(it)?.let(merged::putAll) }
        for (cmd in cmds.filter { it in solo }) query(listOf(cmd))?.let(merged::putAll)
        return if (merged.isEmpty()) null else JsonObject(merged)
    }
}
