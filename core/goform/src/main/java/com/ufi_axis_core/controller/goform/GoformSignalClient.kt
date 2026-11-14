package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.NormalizedFields
import kotlinx.serialization.json.*

/**
 * Goform 信号/设备信息查询客户端
 *
 * 从 GoformClient 拆分，负责：
 * - 信号质量查询（RSRP/SINR/RSRQ/RSSI/RAT/运营商）
 * - 设备基础信息（IMEI/IMSI/IP/MAC）
 * - 流量统计查询
 * - 完整设备状态
 * - 基站信息
 * - LAN/DHCP 状态
 * - 设备设置查询
 * - APN/PIN 状态查询
 * - MAC 访问控制列表查询
 *
 * ## 字段归一化
 *
 * 本类的返回值是**对外契约的一部分**：已迁移的方法返回 `DeviceFields` 里的 canonical key，
 * 设备侧字段名由 [profile] 吸收。加字段/换设备只改 profile，route 与客户端都不动。
 *
 * [profile] 传 `null` 即整层短路成原样透传（一键回退）。
 * 尚未迁移的方法仍是透传。
 *
 * ## 批 B1：从签名就能看出哪个方法有归一化保证
 *
 * 「过了归一化层」这件事以前只由方法体里那句 `fields.normalize(GROUP, raw)` 保证，
 * 返回类型一律是裸 `JsonObject?` —— 接第二台设备时漏调一次，编译照过、测试不红、
 * route 拿到的字段名全错。现在按返回类型把两类方法分开：
 *
 * - 返回 [NormalizedFields]（`:core:device-schema`）= **过了归一化闸门**。
 *   那个类型在本模块**造不出来**（构造入口是 `internal`，唯一途径是
 *   [com.ufi_axis_core.deviceschema.FieldNormalizer.normalizeToFields]），
 *   所以「返回了没归一化的数据」在类型上不可表达。消费点用 `.values` 解包成 `JsonObject`，
 *   **内容与改造前逐字一致，对外 JSON 一个字节没变**。
 * - 仍返回 `JsonObject?` / `JsonArray?` = **原样透传或形状不同**，每个都在自己的 KDoc 里
 *   写清「未归一化 / 归一化在哪一步」。
 *
 * ⚠ [NormalizedFields] 的语义是「**过了归一化层**」，不是「字段名一定是 canonical」：
 * 排障开关 `field_normalization_enabled=false` 时闸门原样透传（那正是它的用途），
 * 此时那个类型包着的是设备原名。完整说明在 [NormalizedFields] 的 KDoc。

 *
 * ## 两份 profile 的分工
 *
 * - [profile]（**可空**）只管**字段归一化**：`null` = 排障开关关掉了归一化。
 *   它决定 `GoformFieldMapper.enabled` / `profileId`，而 `profileId` 一路传到
 *   `/api/diagnose` 的 `device_profile.normalization_enabled`
 *   （链路：`GoformFieldMapper.profileId` → [profileId] → `DataHub.deviceProfileId` →
 *   `HttpServer` 的 `activeProfile != null`）。所以**不许**在本类里给它补非空兜底 ——
 *   那个排障开关会永远报 `true`。
 * - [commandProfile]（**非空**）只喂**命令表**（`cmds` / `fullStatusCmds`）：
 *   字段归一化可以关，命令表不能关 —— 没有 cmd 列表连一条查询都发不出去。
 *
 * ## 为什么 [commandProfile] 没有默认值、也不许在本类里兜底
 *
 * 这里原来写的是 `GoformFieldMapper(profile, profile ?: DeviceProfiles.DEFAULT)` ——
 * 命令表由客户端自己回落到**注册表默认插件**的 profile。单插件时两者同值，看不出问题；
 * 接第二台设备之后，「用户打开排障开关（关归一化）」就会顺带把命令表悄悄换成
 * **默认设备**的命令表 —— 排障模式下向 B 设备发 A 设备的 cmd。
 * 一个排障开关不该改变「往设备发什么命令」（同一条判据见 `DeviceRuntime.commandProfile`）。
 *
 * 所以命令表那一份由调用方（`ComponentFactory`，传 `runtime.commandProfile` =
 * **选中插件**的 profile）定下来，构造参数**不给默认值**：默认值等于把选型逻辑散进每个
 * 客户端的签名，换设备要改 N 处且漏一处不报错。口径与 [GoformSmsClient] 一致。
 *
 * @param profile 字段映射表；传 null 关闭归一化（原样透传设备字段，见 [GoformFieldMapper]）
 * @param commandProfile 命令表来源，非空；由装配层传选中插件的 profile
 */
class GoformSignalClient(
    private val client: GoformTransport,
    profile: DeviceProfile?,
    commandProfile: DeviceProfile,
) {

    // 归一化用可空的那份（排障开关 → null → 原样透传），命令表用非空的那份：
    // 「字段归一化可以关，命令表不能关」，口径同 GoformSettingWriter / GoformSmsClient。
    // 命令表**不在这里兜底** —— 兜底会让排障模式下的命令表悄悄换成默认设备的（见类 KDoc）。
    private val fields = GoformFieldMapper(profile, commandProfile)

    /** 生效中的 profile id；null = 归一化已关（诊断用，见计划书 10.2）。 */
    val profileId: String? get() = fields.profileId



    // ==================== 信号信息 ====================

    /**
     * 获取网络信息 + 实时吞吐量（合并为 1 条 goform 查询，原 2 条信号 + 1 条 thrpt）
     *
     * 合并字段：
     * - 信号 primary: network_type, network_provider, rssi, signalbar, ppp_status
     * - 信号 secondary: network_information, lte_rsrp, Lte_snr, lte_rsrq, lte_rssi,
     *                   cell_id, Lte_pci, neighbor_cell_info, Lte_ca_status
     * - 实时吞吐量: realtime_tx_thrpt, realtime_rx_thrpt
     *
     * ppp_status 必须在此查询，DataHub.getNetworkTypeInfo() 依赖它判断蜂窝连接状态
     *
     * 0.4b：原来这里是 16 个字面量，与 `cmdsFor(SIGNAL)` 逐字一致（含顺序）——
     * 核对通过后收进命令表，这里只留取值。
     *
     * **原样透传，未归一化**（批 B1：返回类型是裸 `JsonObject?` 而不是 [NormalizedFields]）。
     * 本方法是 SIGNAL 组的**原始**取数口，归一化在两处下游各做一次，针对的分组不同：
     * [getConnectionInfo]（CONNECTION 组）与 `SignalCollector.collectLayer1GoformFields`
     * （SIGNAL 组，直接调 `FieldNormalizer.normalize`）。所以这里不能自己先归一化一次 ——
     * 那两条下游要的是同一份**设备原始**输入（`GoformQoS` 的 2s 快照也存原始）。
     */
    suspend fun getSignalInfo(): JsonObject? {
        return client.read(fields.cmds(FieldGroup.SIGNAL))
    }

    /**
     * 连接状态（`network_type` / `network_provider` / `ppp_status`），供
     * `/api/dashboard/summary`、`/api/dashboard/network`、`/api/device/info`、
     * `/api/network/status` 共用。
     *
     * 已归一化（计划书 1.8）：`network_type` 出来就是**可读文案**（`"5G"` 而不是 `"20"`），
     * 值映射由 profile 的 `NETWORK_TYPE_DECODER` 负责。这样 route 层不必再调
     * `GoformClient.mapNetworkType()` —— 那是设备知识出现在 api 模块，且与 profile 的映射表构成两份。
     *
     * 复用 [getSignalInfo] 的那一次查询（`GoformQoS` 的 2s 快照会命中，不会多打设备）。
     */
    suspend fun getConnectionInfo(): NormalizedFields? {
        val raw = getSignalInfo() ?: return null
        return fields.normalize(FieldGroup.CONNECTION, raw)
    }

    /**
     * 单独获取 network_information（供需要仅获取 NR 信息时使用）
     * 返回字段：Nr_fcn, Nr_pci, Nr_bands, Nr_band_widths, Nr_cell_id,
     *           Nr_signal_strength, Nr_snr, nr_rsrp, nr_rsrq, nr_rssi, network_type
     *
     * **原样透传，未归一化**（批 B1：返回裸 `JsonObject?`）。NR 字段归 SIGNAL 组，
     * 而这条查询只取 2 项 —— 归一化在下游按 SIGNAL 组做
     * （`SignalCollector.collectLayer1GoformFields`，它还负责摊平 `network_information` 这个
     * 嵌套容器；摊平规则在 profile 的结构解码器里）。
     */
    suspend fun getNetworkInformation(): JsonObject? {
        // 刻意的轻量查询，**不走 profile 命令表**：这 2 项没有对应的 FieldGroup（NR 字段归 SIGNAL，
        // 但 SIGNAL 是 16 项），换成 fields.cmds 会把 2 项变 16 项。
        // 字段名的核对依据是计划书 §16 的真机基线（2026-09-22）。
        return client.read(listOf("network_information", "Lte_ca_status"))
    }

    // ==================== 设备信息 ====================

    /**
     * IMEI / IMSI / ICCID / LAN IP / MAC 的轻量查询。
     *
     * **原样透传，未归一化**（批 B1：返回裸 `JsonObject?`）。这 5 个字段属 IDENTITY 组，
     * 归一化的那条路是 [getDeviceIdentity]（走 `cmdsFor(IDENTITY)` 的 20 项完整查询）。
     * 本方法刻意保持透传 + 轻量，**没有任何 route 直接用它**（`signalQuery { getDeviceInfo() }`
     * 在 `DataHub` 的 KDoc 里被列过，但全仓没有调用点），改动它请先确认消费方。
     */
    suspend fun getDeviceInfo(): JsonObject? {
        // 刻意的轻量查询，**不走 profile 命令表**：IDENTITY 分组有 20 个 cmd，换过去会从 5 项变 20 项。
        // 字段名的核对依据是计划书 §16 的真机基线（2026-09-22）。
        return client.read(listOf("imei", "imsi", "iccid", "lan_ipaddr", "mac_address"))
    }

    /**
     * 设备身份信息（`/api/device/identity`，同时被 `/api/device/info` 与
     * `/api/dashboard/summary` 的 `identity` 子对象复用）。
     *
     * 已归一化（计划书 1.7）：只输出 `DeviceFields.Identity` 登记的 7 个 canonical
     * （`msisdn` / `imei` / `imsi`（回退 `sim_imsi`）/ `iccid` / `Language` /
     * `cr_version` / `wa_inner_version`）。
     *
     * **allowlist 副作用**：查询里另外 13 个字段（`hardware_version`、`web_version`、
     * `wa_version`、`lan_ipaddr`、`mac_address`、`wan_ipaddr`、`ipv6_wan_ipaddr`、
     * `LocalDomain`、`ppp_status`、`network_type`、`rssi`、`pdp_type`、`opms_wan_mode`）
     * 不再出现在响应里。核实过零消费点：web 只读 `identity.msisdn/imei/imsi/iccid`
     * （`DeviceView.vue:24-27`、`DeviceTopBar.vue:36`），app 的 `getDeviceIdentity()`
     * 只有声明没有调用点，而 `web/src/api/contract.ts` 的 `identity.all` 本来就只列了这 7 个。
     * cmds 不动（少查一个字段并不省一次 HTTP，而改查询会改变设备侧请求形状）。
     */
    suspend fun getDeviceIdentity(): NormalizedFields? {
        val data = client.read(fields.cmds(FieldGroup.IDENTITY)) ?: return null
        return fields.normalize(FieldGroup.IDENTITY, data)?.takeUnless { it.values.isEmpty() }
    }

    /**
     * 设备固件版本（`/api/device/version`）。
     *
     * 与 [getDeviceIdentity] 同属 IDENTITY 分组，归一化后 key 仍是 `Language` /
     * `cr_version` / `wa_inner_version`（`DeviceRoutes` 用它们拼 camelCase 响应）。
     * 这里不用 `fields.cmds`：本方法只查 3 个字段，是刻意的轻量查询。
     */
    suspend fun getDeviceVersion(): NormalizedFields? {
        // 刻意的轻量查询，**不走 profile 命令表**（IDENTITY 是 20 项，这里只要 3 项；
        // 注意 `Language` 刻意只在这条查询里，不在 cmdsFor(IDENTITY) 里 —— 所以它在覆盖率报告里
        // 必然 missing，那是登记态不是缺陷）。字段名的核对依据是计划书 §16 的真机基线（2026-09-22）。
        val data = client.read(listOf("Language", "cr_version", "wa_inner_version")) ?: return null
        return fields.normalize(FieldGroup.IDENTITY, data)
    }

    // ==================== 流量统计 ====================

    /**
     * 流量统计（月累计 + 实时吞吐），`DataScheduler` 的 15s 流量循环用。
     *
     * 月累计部分**必须过 TRAFFIC_LIMIT 归一化**：ZTE 固件的 `monthly_rx/tx_bytes` 上下行
     * 是反的，掰正逻辑只写在 `ZteGoformProfile` 一处（见那里的实测数据注释）。
     * 早期这里直读原始键，于是同一个月累计在 `/api/device/traffic-limit`（走归一化）
     * 和「今日流量 / traffic_summary」（走本方法）两条路上方向相反。
     *
     * `realtime_time` / `realtime_*_thrpt` 没登记在该分组，归一化会丢掉，
     * 所以先铺原始响应再用归一化结果覆盖 —— 未登记字段原样保留，月累计取掰正后的值。
     *
     * ## 批 B1：为什么这个方法**不**返回 [NormalizedFields]
     *
     * 因为它的返回值**不是归一化层的产出**，而是「设备原始响应 + 归一化结果覆盖上去」的
     * **混合体** —— 上一段那个 `merged` 就是为了留住 `realtime_*`（它们刻意没登记在
     * TRAFFIC_LIMIT 里）。把混合体套上「过了归一化层」的类型是谎报：里面确实有一半
     * 是设备原名。所以这里保持 `JsonObject?`，归一化只覆盖 TRAFFIC_LIMIT 登记的那些键。
     *
     * 需要**纯**归一化出口的是 [getDataUsage]（`GET /api/device/traffic-limit` 走它）。
     */
    suspend fun getTrafficStats(): JsonObject? {
        // 刻意的轻量查询，**不走 profile 命令表**：TRAFFIC_LIMIT 是 10 项且**不含** realtime_*，
        // 换过去会既多查限额配置又丢掉实时吞吐（本方法在 15s 轮询路径上）。
        // 字段名的核对依据是计划书 §16 的真机基线（2026-09-22）。
        val raw = client.read(listOf(
            "monthly_rx_bytes", "monthly_tx_bytes",
            "realtime_time", "monthly_time",
            "realtime_tx_thrpt", "realtime_rx_thrpt"
        )) ?: return null
        val normalized = fields.normalize(FieldGroup.TRAFFIC_LIMIT, raw) ?: return raw
        val merged = LinkedHashMap<String, JsonElement>(raw)
        merged.putAll(normalized.values)
        return JsonObject(merged)
    }

    /**
     * 获取完整设备状态（**96** 字段，分 3 批查询合并）。
     *
     * 0.4b：三批字段名收进 `DeviceProfile.fullStatusCmds()`（`ZteGoformProfile` 侧是
     * `FULL_STATUS_CMD_BATCHES`），**内容与顺序逐字照搬**。实测 **30 / 29 / 37 = 96**
     * （搬运前这里的注释写 `30 / 28 / 30+`、KDoc 写「75+ 字段」，两个都不准）。
     *
     * **为什么不做成一个新的 `FieldGroup`**：`coverageReport()` 遍历 `FieldGroup.entries`，
     * 加枚举值会让 `/api/diagnose?fields=1` 的 `field_coverage` 多一个块，
     * 直接冲掉计划书 §16 的真机基线（详见 `DeviceProfile.fullStatusCmds` 的 KDoc）。
     *
     * **为什么仍是三次请求**：批次边界是设备事实，一次发 96 项会被设备截断/返回空
     * （同类先例见 `station_list`）。所以这里逐批发，顺序即 profile 里的顺序 ——
     * 后一批的同名键覆盖前一批，与搬运前的 `putAll` 语义一致。
     *
     * **原样透传，未归一化**（批 B1：返回裸 `JsonObject?`）—— 不过 allowlist 正是这份 dump
     * 的用途：看设备后台到底有哪些字段。它对外只经 [getFullStatusMasked] 出去（脱敏在那里）。
     */
    suspend fun getFullStatus(): JsonObject? {
        val merged = mutableMapOf<String, JsonElement>()
        for (batch in fields.fullStatusCmds()) {
            client.read(batch)?.let { merged.putAll(it) }
        }
        return if (merged.isEmpty()) null else JsonObject(merged)
    }

    /**
     * 完整设备状态的**脱敏版**，给诊断端点 `GET /api/device/goform` 用（计划书 9.2）。
     *
     * 这份 dump 不过 allowlist（那正是它的用途：看设备后台到底有什么字段），所以
     * PII 与凭据必须在这里打掉：登记过的按 `Sensitivity`，没登记的按字段名兜底
     * （见 `FieldNormalizer.maskDump`）。想看真值请走对应的业务端点。
     *
     * **未归一化**（批 B1：返回裸 `JsonObject?`）。脱敏 ≠ 归一化：`maskDump` 只把敏感值换成
     * `***`，**字段名一个都不动**（而且它连闸门都不走 —— profile 为 null 时原样返回）。
     */
    suspend fun getFullStatusMasked(): JsonObject? = fields.maskDump(getFullStatus())

    /**
     * 字段覆盖率诊断（计划书 10.1）：每个分组「登记了哪些 canonical、本设备命中了哪个 source、
     * 哪些一个都没命中」。适配新设备时这份输出就是 TODO 清单。
     *
     * **会逐分组向设备发查询**（最多 10 组，soloCmd 另发），因此只适合按需调用，
     * 不要放进任何轮询路径。输出只含字段名不含值。
     *
     * **返回的不是设备字段而是一份报告**（批 B1：所以是 `JsonObject`，不是 [NormalizedFields]）：
     * 键是 `normalization_enabled` / `profile_id` / `groups` 这类诊断字段，它们由
     * `GoformFieldMapper.coverageReport` 自己拼，根本不经过归一化闸门。
     */
    suspend fun diagnoseFieldCoverage(): JsonObject = fields.coverageReport { client.read(it) }



    // ==================== 基站信息 ====================

    /**
     * 获取小区信息（邻区 + 已锁定基站 + 当前服务小区），单次 goform 查询。
     *
     * 替代原来 2×HTTP（batch1: neighbor_cell_info+locked_cell_info+network_information；
     * batch2: Lte_pci/Lte_fcn/Lte_bands/lte_rsrp），减少 HTTP 往返。
     *
     * 已归一化（计划书 1.6）：`neighbor_cell_info` / `locked_cell_info` 一律是**真数组**
     * （设备可能返回数组的 JSON 字符串），元素键统一成 `pci`/`earfcn`/`rsrp`/`rsrq`/`sinr`/`rat`。
     * 顶层只保留 `DeviceFields.CellInfo` 登记的字段 —— `network_information` 是查询用的容器命令，
     * 它自己不对外透出（NR 字段走 `signal` 频道与 `/api/network/signal`）。
     */
    suspend fun getCellInfo(): NormalizedFields? {
        val data = client.read(fields.cmds(FieldGroup.CELL_INFO)) ?: return null
        return fields.normalize(FieldGroup.CELL_INFO, data)?.takeUnless { it.values.isEmpty() }
    }

    /**
     * 仅查询邻区信息，用于快速刷新邻区列表。
     *
     * 归一化后 `neighbor_cell_info` 恒为真数组，所以这里不再需要"字符串 or 数组"两路解析。
     *
     * **归一化过，但返回类型仍是 `JsonArray?`**（批 B1）：它归一化的是 CELL_INFO 组，
     * 然后**从结果里取出一个成员**再返回 —— 出去的是数组本身，不是字段集合，
     * [NormalizedFields] 包的是「一份字段对象」，套在这里名不副实。
     * 元素键（`pci` / `earfcn` / `rsrp` / …）的 canonical 保证来自上面那次归一化。
     */
    suspend fun getNeighborCellInfo(): JsonArray? {
        // 刻意的轻量查询，**不走 profile 命令表**：CELL_INFO 是 10 项，换过去会把「单字段快速刷新」
        // 变成 10 字段查询。字段名的核对依据是计划书 §16 的真机基线（2026-09-22）。
        val data = client.read(listOf("neighbor_cell_info")) ?: return null
        val normalized = fields.normalize(FieldGroup.CELL_INFO, data) ?: return null
        return normalized.values["neighbor_cell_info"] as? JsonArray
    }

    // ==================== LAN/DHCP ====================

    /**
     * LAN / DHCP 状态。
     *
     * 已归一化（计划书 1.3）：别名链取 web 与 app 的并集（app 侧还认 `ipaddr` / `DhcpStatus`
     * / `DhcpStartIP` 等老名字）。`dhcpLease_hour` **不做单位换算** —— 两端客户端都已固化
     * "读到后自己 ×3600"，在这里换算会变成双重换算。
     */
    suspend fun getLanSettings(): NormalizedFields? {
        val cmds = fields.cmds(FieldGroup.LAN_SETTINGS)
        return fields.normalize(FieldGroup.LAN_SETTINGS, client.read(cmds))
    }

    // ==================== 设备设置状态查询 ====================

    /**
     * 设备开关设置。
     *
     * 已归一化（计划书 1.2）：布尔值统一成 `"1"` / `"0"` 字符串（漫游开关的 `"on"`/`"off"`
     * 也归一到这里），空字符串视为字段缺失并省略该 key。
     *
     * **allowlist 副作用**（已核实无消费方）：设备返回的 `lte_band_lock` / `nr_band_lock` /
     * `usb_network_protocal` 不在本分组的登记表里，因此不再出现在响应中。频段锁定由
     * `GET /api/network/band-status`（[getBandLockStatus]）提供，web 与 app 都只从那里读。
     * cmd 仍保留在查询里，避免改动设备侧的请求形状。
     */
    suspend fun queryDeviceSettings(): NormalizedFields? {
        val cmds = fields.cmds(FieldGroup.DEVICE_SETTINGS)
        return fields.normalize(FieldGroup.DEVICE_SETTINGS, client.read(cmds))
    }

    /**
     * 精准查询频段锁定状态（仅 lte_band_lock + nr_band_lock，避免 queryDeviceSettings 16 字段冗余）
     * 返回: {"lte_band_lock":"1,3,5,...", "nr_band_lock":"1,5,8,..."}
     *
     * 已归一化（计划书 1.1）：cmd 列表与字段名都来自 profile 的 `BAND_STATUS` 分组。
     * 值原样透出 —— `"0"` / `"all"` 表示未锁定，客户端的 `parseBands()` 已固化这个解析。
     */
    suspend fun getBandLockStatus(): NormalizedFields? {
        val cmds = fields.cmds(FieldGroup.BAND_STATUS)
        return fields.normalize(FieldGroup.BAND_STATUS, client.read(cmds))
    }

    // ==================== 流量限额 ====================

    /**
     * 流量限额配置 + 月用量。
     *
     * 已归一化（计划书 2.8 尾巴）：设备的 `data_volume_limit_size` 复合串（`"470_1024"`）由
     * profile 的结构解码器拆成 `limit_value` / `limit_unit_display` / `limit_bytes`，
     * 上层不再见到复合串，也不需要知道单位藏在乘数里。开关值统一成 `"1"`/`"0"`。
     */
    suspend fun getDataUsage(): NormalizedFields? {
        val cmds = fields.cmds(FieldGroup.TRAFFIC_LIMIT)
        return fields.normalize(FieldGroup.TRAFFIC_LIMIT, client.read(cmds))
    }
}


