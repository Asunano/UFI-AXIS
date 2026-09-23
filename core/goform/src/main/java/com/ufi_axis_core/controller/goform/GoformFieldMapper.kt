package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.FieldNormalizer
import com.ufi_axis_core.util.AppLogger
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
 * [normalizeProfile] 为 `null` 时整个映射层短路成原样透传。这就是一键回退的开关本体，
 * 由 `AppSettings.fieldNormalizationEnabled` + `ComponentFactory.resolveDeviceProfile()` 决定，
 * 只在构造组件图时读一次（改配置要重启后台服务，缓存因此天然是新的）。
 *
 * **唯一例外是 [NORMALIZE_ALWAYS] 里的组**（今天只有 [FieldGroup.TRAFFIC_LIMIT]）：那些组的
 * 出口不是「透传」而是「按 canonical 契约重组」，跟着降级只会产出静默错数据 ——
 * 完整理由见 [NORMALIZE_ALWAYS] 的 KDoc，那里也写了往清单里加组的门槛。
 *
 * ## 为什么是**两个** profile 而不是一个（阶段 0.4a）
 *
 * 这两个字段承载的是两件性质不同的事，合成一个就必然牺牲其中一件：
 *
 * 1. **字段归一化可以关**（排障开关 `field_normalization_enabled`）。关掉时对外字段名变回
 *    设备原名，这正是排障时想看到的 —— 所以 [normalizeProfile] 必须**可空**。
 * 2. **命令表不能关**。没有 cmd 列表就一条查询都发不出去，关归一化会把整个只读面打瘫。
 *    所以 [commandProfile] **非空**，口径与写侧 `GoformSettingWriter`（那里的注释是
 *    「字段归一化可以关，写命令表不能关」）完全一致。
 *
 * ### 下一个人最可能踩的坑：别把 [profileId] 接到 [commandProfile] 上
 *
 * `/api/diagnose` 的 `device_profile.normalization_enabled` 是**从 profile 是否为 null 推出来的**，
 * 链路是三层纯委托、中间没有任何兜底：
 *
 * ```
 * GoformFieldMapper.profileId (= normalizeProfile?.id)
 *   → GoformSignalClient.profileId
 *   → DataHub.deviceProfileId
 *   → HttpServer 的 activeProfile != null  →  响应里的 normalization_enabled
 * ```
 *
 * （`HttpServer.kt` 在 **`:core:network`**，不是 `:core:api`；`DataHub` 只是透传。）
 * 把 [profileId] / [enabled] 改成读 [commandProfile]，编译照样过、测试也不一定红，
 * 但那个排障开关会永远报 `true` —— 可观测性就这么没了。所以这两个成员**只许**读
 * [normalizeProfile]。
 *
 * ## 别名策略（阶段 4.1 已切 DROP）
 *
 * 默认 [FieldNormalizer.LegacyAliases.DROP]：响应里**只有 canonical 字段**，设备原名一律不输出。
 * 过渡期的双输出（canonical + 设备原名）已在 core / web / app 同一批改完后关掉。
 */
internal class GoformFieldMapper(
    /**
     * 可空：归一化 / 脱敏 / 覆盖率诊断都用它。
     * `null` = 排障开关 `field_normalization_enabled` 把归一化关掉了。
     */
    private val normalizeProfile: DeviceProfile?,
    /**
     * 非空：命令表的最终归宿 —— [cmds] / [fullStatusCmds] 都只读它（0.4b 已切过来）。
     * 口径同 `GoformSettingWriter`——「字段归一化可以关，命令表不能关」。
     */
    private val commandProfile: DeviceProfile,
    private val legacy: FieldNormalizer.LegacyAliases = FieldNormalizer.LegacyAliases.DROP,
    /**
     * WARN 出口（[NORMALIZE_ALWAYS] 生效时用一次）。默认就是生产实现 [AppLogger.w]，
     * 所以两个调用点（`GoformSignalClient` / `GoformWifiClient`）一行都不用改。
     *
     * 为什么要能注入：[AppLogger] 一被触达就会初始化 `android.util.Log` 与外部存储日志目录，
     * 在 JVM 单测里直接抛 —— 而豁免路径恰恰必须被单测覆盖。同样的手法见
     * `GoformClient.base64DecodeOrEmpty` 的 `onError` 参数（那条注释写了同一个理由）。
     */
    private val warn: (tag: String, message: String) -> Unit = { tag, message -> AppLogger.w(tag, message) },
) {

    /** 当前是否在归一化。**只看 [normalizeProfile]**，见类注释里的那条链。 */
    val enabled: Boolean get() = normalizeProfile != null

    /**
     * 生效中的 profile id；null = 归一化已关（诊断用，见计划书 10.2）。
     *
     * **不要改成 [commandProfile]`.id`**：那会让 `/api/diagnose` 的
     * `normalization_enabled` 永远是 `true`（见类注释）。
     */
    val profileId: String? get() = normalizeProfile?.id

    /**
     * 归一化一次查询结果。
     *
     * `null` 进 `null` 出 —— 上层用 null 区分"查询失败"与"查到了但字段为空"，
     * 归一化不能把失败变成空对象。
     *
     * [normalizeProfile] 为 `null`（排障开关关掉归一化）时**原样透传** ——
     * 唯一例外是 [NORMALIZE_ALWAYS] 里的组，它们回落到非空的 [commandProfile] 继续归一化，
     * 见 [normalizeAlwaysProfile] / [NORMALIZE_ALWAYS]。
     */
    fun normalize(group: FieldGroup, raw: JsonObject?): JsonObject? {
        val p = normalizeProfile ?: normalizeAlwaysProfile(group) ?: return raw
        if (raw == null) return null
        return FieldNormalizer.normalize(raw, p, group, legacy)
    }

    /**
     * 排障开关关掉归一化后，[group] 是否仍然要归一化；要的话返回用哪份 profile。
     *
     * 返回 [commandProfile] 是因为它**非空**（口径同 [cmds]：字段归一化可以关，命令表不能关），
     * 而且它就是这一轮实际发出去的那些 cmd 的来源 —— 用同一份 profile 去解读同一批响应，
     * 不会出现「按 A 的命令表查、按 B 的登记表解」的错配。
     *
     * ## 为什么 WARN 是「按组各一次」而不是每次调用都打
     *
     * 这条路在 `DataScheduler` 的 15s 流量循环上（`collectGoformTraffic` → `getTrafficStats`），
     * 每次调用都打会在几小时内把 `app.log` 灌满，还会把 [AppLogger] 的内存缓冲
     * （崩溃 dump 的唯一现场来源，只有 500 条）挤干。
     *
     * 选「按组各一次」而不是「整个实例一次」：清单以后可能多出别的组，而每个组被豁免的
     * **理由不同**，各打一条才说得清是哪个出口在豁免；组数有上限（`FieldGroup.entries`），
     * 这个标记集合不会无界增长。用 [java.util.concurrent.ConcurrentHashMap] 的键集是因为
     * 归一化会被多个协程并发调用（15s 流量循环 + HTTP route），`add` 的返回值天然是
     * 「我是不是第一个」的原子判据，不需要额外加锁。
     */
    private fun normalizeAlwaysProfile(group: FieldGroup): DeviceProfile? {
        if (group !in NORMALIZE_ALWAYS) return null
        if (warnedNormalizeAlwaysGroups.add(group)) {
            warn(
                TAG,
                "field_normalization_enabled=false，但 $group 仍然归一化（profile=${commandProfile.id}）：" +
                    "它是按 canonical 契约**重组**的派生出口（limit_bytes 等派生键设备上不存在、" +
                    "monthly_rx/tx 方向要靠 profile 掰正），跟着排障开关降级只会产出静默错数据。" +
                    "要看设备原始字段名请走 POST /api/device/goform 或 GET /api/diagnose?fields=1。" +
                    "清单见 GoformFieldMapper.NORMALIZE_ALWAYS。",
            )
        }
        return commandProfile
    }

    /** 已经打过豁免 WARN 的组（一次性标记，见 [normalizeAlwaysProfile] 的 KDoc）。 */
    private val warnedNormalizeAlwaysGroups: MutableSet<FieldGroup> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()


    /**
     * 该分组要查的 cmd 列表。
     *
     * ## 0.4b：已切到 [commandProfile]（命令表的最终归宿）
     *
     * 为什么不再走 [normalizeProfile]：
     * - 归一化可以关（排障开关 → `normalizeProfile = null`），但「查哪些字段」不能跟着关 ——
     *   否则关掉归一化等于把整个只读面打瘫（一条查询都发不出去）。
     * - [commandProfile] **非空**，口径与写侧 `GoformSettingWriter`（「字段归一化可以关，写命令表不能关」）
     *   完全一致。现在排障模式（关归一化）下的只读面照常运行：命令来自 [commandProfile]。
     *
     * 0.4a 的 fallback 参数已删除：在那之前 fallback 是 `normalizeProfile = null` 时唯一的命令来源，
     * 而 0.4a 的 `GoformCommandTableGuardTest` 逐组证明了 fallback 与 `cmdsFor()` 逐字一致，
     * 所以删掉 fallback 不改变任何实际发出的 cmd。
     */
    fun cmds(group: FieldGroup): List<String> = commandProfile.cmdsFor(group)

    /**
     * 「一次拉全量状态」的分批命令表（`GoformSignalClient.getFullStatus()` 用）。
     *
     * 与 [cmds] 同一个归属：命令表来自非空的 [commandProfile]，所以关掉归一化时诊断 dump
     * 也照常能发出去。外层是**批次**——批次边界是设备事实，见 `DeviceProfile.fullStatusCmds`。
     */
    fun fullStatusCmds(): List<List<String>> = commandProfile.fullStatusCmds()

    /**
     * 给原始 dump 打码（计划书 9.2），用于 `GET /api/device/goform` 这类**不过 allowlist**的出口。
     *
     * profile 为 `null`（D7 回退）时没有敏感度登记表可查，只能原样返回 ——
     * 与"整层短路成透传"的语义一致；回退开关本来就不是长期配置。
     */
    fun maskDump(raw: JsonObject?): JsonObject? {
        val p = normalizeProfile ?: return raw
        if (raw == null) return null
        return FieldNormalizer.maskDump(raw, p)
    }

    /**
     * 字段覆盖率报告（计划书 10.1）——适配新设备时的 TODO 清单。
     *
     * 逐个 [FieldGroup] 向设备查一次（[query] 就是 `DeviceTransport::read`），比对登记表，
     * 输出"登记了几个 / 命中了几个 / 哪些一个 source 都没命中 / 命中的是哪个 source"。
     *
     * **输出里没有任何字段值**，只有字段名，因此不需要再脱敏（值的脱敏见 [maskDump]）。
     * [soloCmds] 会单独查 —— 与其它 cmd 合并会让设备返回空，混在一起查会误报"未命中"。
     *
     * **开头那个短路不能删**（[normalizeProfile] 为 null 时直接返回一个 `normalization_enabled:false`
     * 的对象）：没有登记表就没有可比对的东西，而且这个方法**会逐组向设备发查询** ——
     * 改成用 [commandProfile] 兜底的话，排障模式下 `/api/diagnose?fields=1` 会开始真打设备，
     * 那是行为变更（今天它一条查询都不发）。
     */
    suspend fun coverageReport(query: suspend (List<String>) -> JsonObject?): JsonObject {
        val p = normalizeProfile ?: return buildJsonObject {
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

    /**
     * 覆盖率诊断里「把一组 cmd 按 solo / 非 solo 分批发出去」的那一步。
     *
     * ## 为什么这里（诊断路径）用 [normalizeProfile]，而 [cmds]（业务路径）用 [commandProfile]
     *
     * 0.4a 留的注释说「切 `cmds()` 时必须同步切 `soloCmds`」，0.4b 落地时按语义重新判定，
     * **结论是分开**，理由是两条路径回答的是两个不同的问题：
     *
     * - **业务查询路径**（[cmds]）回答「现在要向设备发哪些 cmd」。命令表不能关，所以取
     *   非空的 [commandProfile]。
     * - **覆盖率诊断路径**（[coverageReport] → 本方法）回答「**当前生效的那份 profile**
     *   登记了什么、命中了什么」。它的产出（`/api/diagnose?fields=1` 的 `field_coverage`）
     *   是拿来跟 `registered` / `hit_source` 基线逐字比对的，比对对象必须是生效那份；
     *   `normalizeProfile == null` 时它整段短路（一条查询都不发），就更不该去碰 [commandProfile]。
     *
     * 所以**不存在 0.4a 担心的那种错配**：本方法的 `cmds` 与 `solo` 都来自同一个入参 `p`
     * （= [coverageReport] 传进来的 [normalizeProfile]），「发哪些 cmd」与「哪些 cmd 不能合并发」
     * 在这条路径内部仍然同源。错配只会发生在「一边取 `p`、一边取 [commandProfile]」的写法上 ——
     * 那种写法这里没有，也不要加。
     */
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

    private companion object {

        private const val TAG = "GoformFieldMapper"

        /**
         * 排障开关（`field_normalization_enabled=false`）**不覆盖**的组。
         *
         * ## 1. 开关的语义，以及为什么这些组不该跟着它降级
         *
         * 这个开关的语义是「读侧**原样透传**设备字段，方便排障时看原始字段名」。真正的透传出口
         * （WiFi / 小区 / LAN / 身份 … 那几组，以及 `GET /api/device/goform` 的原始 dump）关掉后
         * 确实会变回设备原名，那正是排障想看的东西。
         *
         * 但 `GET /api/device/traffic-limit` 与 `GoformSignalClient.getTrafficStats()` **不是透传出口**：
         * 它们是按 canonical 契约**重组**的派生出口 —— 值要拆、方向要掰、键在设备上根本不存在。
         * 让它们跟着降级：
         * - **帮不了排障**：想看设备原名请走 `POST /api/device/goform`（原始 dump，只脱敏不过 allowlist）
         *   或 `GET /api/diagnose?fields=1`（逐组列出 canonical ← 命中的设备原名）；
         * - **制造静默错数据**：见下面第 2 节，字段一个不缺、没有任何报错，值全是错的。
         *
         * ## 2. 关掉后具体会发生什么（逐条都已核实）
         *
         * - `monthly_rx_bytes ← monthly_tx_bytes` 是 `ZteGoformProfile` 里**刻意的交叉绑定**
         *   （ZTE 固件把上下行报反了，由 profile 掰正）。豁免之前关掉开关，
         *   `DataScheduler.collectGoformTraffic()` 拿到的就是设备原值，随后
         *   `recordHourlyUsage(rx, tx)` **把颠倒的上下行落库** —— 开关改回 true 也修不回来，
         *   库里那段历史已经反了。
         * - `limit_bytes` / `limit_value` / `limit_unit_display` 是 `ZteGoformProfile.splitDataVolumeLimit`
         *   从复合串 `data_volume_limit_size`（形如 `"470_1024"`）拆出来的**派生键，设备上不存在**。
         *   关掉归一化后，无论哪一层想兜底都拿不到 —— 因为没有任何设备字段叫这个名字。
         * - `TrafficLimitMapper`（`:core:api`）取不到就填默认值：`false` / `""` / `0` / `"80"` / `"1"`。
         *   所以症状是「**字段一个不缺、没有报错、值全错**」，最难排的那一类。
         * - `DataScheduler.checkTrafficLimitThrottled()` 拿到 `limit_bytes = 0` 后
         *   `if (limitBytes <= 0L) return` → **流量预警与到阈值自动关网静默失效**，
         *   一条日志都不会有。
         *
         * ## 3. 为什么豁免插在这一层
         *
         * 这里是**闸门本体**（`normalizeProfile ?: return raw` 那一行），一处同时覆盖
         * TRAFFIC_LIMIT 的两个调用点：`GoformSignalClient.getDataUsage()`（REST 出口）与
         * `getTrafficStats()`（`DataScheduler` 的 15s 落库路径）。插在 `TrafficLimitMapper` 里
         * 只能救 REST 那一条，**修不到落库那条路** —— 而落库才是不可逆的那个后果。
         *
         * ## 4. 为什么不给 `TrafficLimitMapper` 加「原始名兜底 + 复合串兜底解析」
         *
         * 那等于把 `splitDataVolumeLimit` 这份**设备知识**（复合串怎么拆、乘数怎么映射单位、
         * 上下行是反的）在 `:core:api` 复制第二份，直接违反「设备知识只在 profile 里有一份」。
         * 两份迟早漂移，而漂移的表现又是静默错数字。
         *
         * ## 5. 往清单里加组的门槛
         *
         * **只有「按 canonical 重组的派生出口」才有资格**：值被拆过、方向被掰过、或者键在设备上
         * 根本不存在 —— 这类出口关掉归一化不会变成"设备原样"，只会变成"错的"。
         * **纯透传出口一律不许加**（WiFi / 小区 / LAN / 身份 / 连接 / 频段 / 设备设置…）：
         * 每加一个，这个排障开关就少一块可观察面；加满了这个开关本身就废了。
         */
        private val NORMALIZE_ALWAYS = setOf(FieldGroup.TRAFFIC_LIMIT)
    }
}

