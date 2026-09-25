package com.ufi_axis_core.devicespi.adapter

import com.ufi_axis_core.deviceschema.NormalizedFields
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * signal 域（信号 / 设备身份 / 流量统计 / 小区 / LAN / 设备设置查询）的设备适配接口
 * （2026-09-25 批 B2）。
 *
 * 由 [DeviceAdapter.signal] 交付；goform 系的实现在 `:core:device-plugins` 里委派给
 * `GoformSignalClient`，非 goform 设备（飞猫等）另写一份实现，上层调用点一个字都不用改。
 *
 * 覆盖面 = `GoformSignalClient` 的全部查询方法（16 个）加 [profileId]。方法签名、返回类型与
 * KDoc 逐字照抄那边，本批是纯接缝迁移，行为与语义不变。
 *
 * ## 返回类型的两类，**原样沿用批 B1 之后的现状**
 *
 * - 返回 [NormalizedFields]（8 个）= **过了归一化闸门**。那个类型在实现侧的模块里
 *   造不出来（构造入口是 `internal`，唯一途径是
 *   [com.ufi_axis_core.deviceschema.FieldNormalizer.normalizeToFields]），所以
 *   「返回了没归一化的数据」在类型上不可表达。消费点用 `.values` 解包成 [JsonObject]。
 * - 仍返回 [JsonObject] / [JsonArray] = **原样透传、形状不同、或本身就是一份报告**，
 *   每个都在自己的 KDoc 里写清是哪一种。
 *
 * 这里**刻意不统一**这两类：统一就不是纯接缝迁移了 —— 8 个里任何一个降级成裸
 * [JsonObject] 会丢掉批 B1 刚立起来的类型保证，而透传那几个升成 [NormalizedFields]
 * 是**谎报**（它们里面确实有设备原名，理由逐个写在方法上）。
 *
 * ## ⚠ 本域有一处与其它域**相反**的不对称：第 1 层字段映射在消费方
 *
 * 其它域（`device` / `network` / `wifi` / `sim`）的口径是「设备知识全在实现侧」。
 * signal 域**不是**：`SignalCollector` 的第 1 层（设备字段名 → canonical）由**消费方**
 * 拿着 `DeviceProfile` 自己调 `FieldNormalizer.normalize(…, FieldGroup.SIGNAL, …)` 完成，
 * 而 [getSignalInfo] 出来的是**设备原始响应**。
 *
 * 这是**刻意保留**的（批 B2 只解耦、不搬迁），三条理由：
 *
 * 1. **搬走第 1 层会把三层编排拆开。** `SignalCollector` 的第 2 层（拿服务小区 PCI 去
 *    `neighbor_cell_info` 数组里匹配）与第 3 层（Android Telephony 兜底）**需要原始响应与
 *    归一化结果同时在场** —— 第 2 层读的是原始响应里的邻区数组，PCI 却取自第 1 层的
 *    归一化结果。把第 1 层搬到实现侧，这两份就得分两次跨接口传回来。
 * 2. **「消费方拿 profile 做映射」本身不是 goform 专属设计。** profile 是**按设备一份**的
 *    （`DevicePlugin.profile()`），飞猫那份同样由装配层注入到 `SignalCollector`，
 *    所以这个形状对第二台设备照样成立，不是非 goform 设备插不进来的障碍。
 * 3. **风险不对称。** 搬迁会动对外 signal JSON 的字段名（WS `signal` 频道与 REST
 *    共用同一份输出，key 集合是既有契约），收益只是「看起来与其它五个域整齐」。
 *
 * 同一条例外也记在 `DataHub` 类 KDoc 的「归一化在设备客户端层做」那条纪律下面。
 *
 * ## 三个排障 / 诊断方法为什么也在本接口上
 *
 * [getFullStatus] / [getFullStatusMasked] / [diagnoseFieldCoverage] 不是业务读取，
 * 而是「dump 设备原名」与「字段覆盖率报告」。它们仍然进来，因为：
 *
 * - 语义是**按设备**的排障出口，不是 goform 专有：任何设备都能回答「你后台到底有哪些
 *   字段」（[getFullStatus]）与「本 profile 登记的 canonical 命中了哪些 source」
 *   （[diagnoseFieldCoverage]，报告由归一化层按 profile 拼，与协议无关）。
 * - 不迁的代价很具体：`/api/diagnose` 的 `field_coverage` 与 `GET /api/device/goform`
 *   这两条路会各留一根 `GoformSignalClient` 的具体类绑带，本批就等于没解耦干净。
 * - 适配新设备时这两份输出就是 TODO 清单 —— 对第二台设备的价值比对第一台更大。
 */
interface SignalSource {

    /** 生效中的 profile id；null = 归一化已关（诊断用，见计划书 10.2）。 */
    val profileId: String?

    // ==================== 信号信息 ====================

    /**
     * 获取网络信息 + 实时吞吐量（合并为 1 条查询，原 2 条信号 + 1 条 thrpt）
     *
     * 合并字段：
     * - 信号 primary: network_type, network_provider, rssi, signalbar, ppp_status
     * - 信号 secondary: network_information, lte_rsrp, Lte_snr, lte_rsrq, lte_rssi,
     *                   cell_id, Lte_pci, neighbor_cell_info, Lte_ca_status
     * - 实时吞吐量: realtime_tx_thrpt, realtime_rx_thrpt
     *
     * ppp_status 必须在此查询，DataHub.getNetworkTypeInfo() 依赖它判断蜂窝连接状态
     *
     * **原样透传，未归一化**（批 B1：返回类型是裸 [JsonObject] 而不是 [NormalizedFields]）。
     * 本方法是 SIGNAL 组的**原始**取数口，归一化在两处下游各做一次，针对的分组不同：
     * [getConnectionInfo]（CONNECTION 组）与 `SignalCollector.collectLayer1GoformFields`
     * （SIGNAL 组，直接调 `FieldNormalizer.normalize`）。所以这里不能自己先归一化一次 ——
     * 那两条下游要的是同一份**设备原始**输入（goform 系的 2s 快照也存原始）。
     */
    suspend fun getSignalInfo(): JsonObject?

    /**
     * 连接状态（`network_type` / `network_provider` / `ppp_status`），供
     * `/api/dashboard/summary`、`/api/dashboard/network`、`/api/device/info`、
     * `/api/network/status` 共用。
     *
     * 已归一化（计划书 1.8）：`network_type` 出来就是**可读文案**（`"5G"` 而不是 `"20"`），
     * 值映射由 profile 的 `NETWORK_TYPE_DECODER` 负责。这样 route 层不必再做制式翻译 ——
     * 那是设备知识出现在 api 模块，且与 profile 的映射表构成两份。
     *
     * 复用 [getSignalInfo] 的那一次查询（实现侧的短 TTL 快照会命中，不会多打设备）。
     */
    suspend fun getConnectionInfo(): NormalizedFields?

    /**
     * 单独获取 network_information（供需要仅获取 NR 信息时使用）
     * 返回字段：Nr_fcn, Nr_pci, Nr_bands, Nr_band_widths, Nr_cell_id,
     *           Nr_signal_strength, Nr_snr, nr_rsrp, nr_rsrq, nr_rssi, network_type
     *
     * **原样透传，未归一化**（批 B1：返回裸 [JsonObject]）。NR 字段归 SIGNAL 组，
     * 而这条查询只取 2 项 —— 归一化在下游按 SIGNAL 组做
     * （`SignalCollector.collectLayer1GoformFields`，它还负责摊平 `network_information` 这个
     * 嵌套容器；摊平规则在 profile 的结构解码器里）。
     */
    suspend fun getNetworkInformation(): JsonObject?

    // ==================== 设备信息 ====================

    /**
     * IMEI / IMSI / ICCID / LAN IP / MAC 的轻量查询。
     *
     * **原样透传，未归一化**（批 B1：返回裸 [JsonObject]）。这 5 个字段属 IDENTITY 组，
     * 归一化的那条路是 [getDeviceIdentity]（走该组的完整查询）。
     * 本方法刻意保持透传 + 轻量，**没有任何 route 直接用它**（`signalQuery { getDeviceInfo() }`
     * 在 `DataHub` 的 KDoc 里被列过，但全仓没有调用点），改动它请先确认消费方。
     */
    suspend fun getDeviceInfo(): JsonObject?

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
    suspend fun getDeviceIdentity(): NormalizedFields?

    /**
     * 设备固件版本（`/api/device/version`）。
     *
     * 与 [getDeviceIdentity] 同属 IDENTITY 分组，归一化后 key 仍是 `Language` /
     * `cr_version` / `wa_inner_version`（`DeviceRoutes` 用它们拼 camelCase 响应）。
     * 实现侧刻意只查这 3 个字段，是轻量查询、不走分组命令表。
     */
    suspend fun getDeviceVersion(): NormalizedFields?

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
     * 所以实现侧先铺原始响应再用归一化结果覆盖 —— 未登记字段原样保留，月累计取掰正后的值。
     *
     * ## 批 B1：为什么这个方法**不**返回 [NormalizedFields]
     *
     * 因为它的返回值**不是归一化层的产出**，而是「设备原始响应 + 归一化结果覆盖上去」的
     * **混合体** —— 上一段那个覆盖就是为了留住 `realtime_*`（它们刻意没登记在
     * TRAFFIC_LIMIT 里）。把混合体套上「过了归一化层」的类型是谎报：里面确实有一半
     * 是设备原名。所以这里保持 [JsonObject]，归一化只覆盖 TRAFFIC_LIMIT 登记的那些键。
     *
     * 需要**纯**归一化出口的是 [getDataUsage]（`GET /api/device/traffic-limit` 走它）。
     */
    suspend fun getTrafficStats(): JsonObject?

    /**
     * 获取完整设备状态（goform 系实测 **96** 字段，分 3 批查询合并）。
     *
     * **为什么不做成一个新的 `FieldGroup`**：`coverageReport()` 遍历 `FieldGroup.entries`，
     * 加枚举值会让 `/api/diagnose?fields=1` 的 `field_coverage` 多一个块，
     * 直接冲掉计划书 §16 的真机基线（详见 `DeviceProfile.fullStatusCmds` 的 KDoc）。
     *
     * **为什么仍是多次请求**：批次边界是设备事实，一次发 96 项会被设备截断/返回空
     * （同类先例见 `station_list`）。批次内容与顺序由 profile 定，后一批的同名键覆盖前一批。
     *
     * **原样透传，未归一化**（批 B1：返回裸 [JsonObject]）—— 不过 allowlist 正是这份 dump
     * 的用途：看设备后台到底有哪些字段。它对外只经 [getFullStatusMasked] 出去（脱敏在那里）。
     */
    suspend fun getFullStatus(): JsonObject?

    /**
     * 完整设备状态的**脱敏版**，给诊断端点 `GET /api/device/goform` 用（计划书 9.2）。
     *
     * 这份 dump 不过 allowlist（那正是它的用途：看设备后台到底有什么字段），所以
     * PII 与凭据必须在实现侧打掉：登记过的按 `Sensitivity`，没登记的按字段名兜底
     * （见 `FieldNormalizer.maskDump`）。想看真值请走对应的业务端点。
     *
     * **未归一化**（批 B1：返回裸 [JsonObject]）。脱敏 ≠ 归一化：`maskDump` 只把敏感值换成
     * `***`，**字段名一个都不动**（而且它连闸门都不走 —— profile 为 null 时原样返回）。
     */
    suspend fun getFullStatusMasked(): JsonObject?

    /**
     * 字段覆盖率诊断（计划书 10.1）：每个分组「登记了哪些 canonical、本设备命中了哪个 source、
     * 哪些一个都没命中」。适配新设备时这份输出就是 TODO 清单。
     *
     * **会逐分组向设备发查询**（最多 10 组，soloCmd 另发），因此只适合按需调用，
     * 不要放进任何轮询路径。输出只含字段名不含值。
     *
     * **返回的不是设备字段而是一份报告**（批 B1：所以是 [JsonObject]，不是 [NormalizedFields]）：
     * 键是 `normalization_enabled` / `profile_id` / `groups` 这类诊断字段，它们由
     * 归一化层的覆盖率报告自己拼，根本不经过归一化闸门。
     */
    suspend fun diagnoseFieldCoverage(): JsonObject

    // ==================== 基站信息 ====================

    /**
     * 获取小区信息（邻区 + 已锁定基站 + 当前服务小区），单次查询。
     *
     * 已归一化（计划书 1.6）：`neighbor_cell_info` / `locked_cell_info` 一律是**真数组**
     * （设备可能返回数组的 JSON 字符串），元素键统一成 `pci`/`earfcn`/`rsrp`/`rsrq`/`sinr`/`rat`。
     * 顶层只保留 `DeviceFields.CellInfo` 登记的字段 —— `network_information` 是查询用的容器命令，
     * 它自己不对外透出（NR 字段走 `signal` 频道与 `/api/network/signal`）。
     */
    suspend fun getCellInfo(): NormalizedFields?

    /**
     * 仅查询邻区信息，用于快速刷新邻区列表。
     *
     * 归一化后 `neighbor_cell_info` 恒为真数组，所以调用点不再需要"字符串 or 数组"两路解析。
     *
     * **归一化过，但返回类型仍是 [JsonArray]**（批 B1）：它归一化的是 CELL_INFO 组，
     * 然后**从结果里取出一个成员**再返回 —— 出去的是数组本身，不是字段集合，
     * [NormalizedFields] 包的是「一份字段对象」，套在这里名不副实。
     * 元素键（`pci` / `earfcn` / `rsrp` / …）的 canonical 保证来自那次归一化。
     */
    suspend fun getNeighborCellInfo(): JsonArray?

    // ==================== LAN/DHCP ====================

    /**
     * LAN / DHCP 状态。
     *
     * 已归一化（计划书 1.3）：别名链取 web 与 app 的并集（app 侧还认 `ipaddr` / `DhcpStatus`
     * / `DhcpStartIP` 等老名字）。`dhcpLease_hour` **不做单位换算** —— 两端客户端都已固化
     * "读到后自己 ×3600"，在这里换算会变成双重换算。
     */
    suspend fun getLanSettings(): NormalizedFields?

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
    suspend fun queryDeviceSettings(): NormalizedFields?

    /**
     * 精准查询频段锁定状态（仅锁定项，避免 [queryDeviceSettings] 的十几个字段冗余）
     * 返回: {"lte_band_lock":"1,3,5,...", "nr_band_lock":"1,5,8,..."}
     *
     * 已归一化（计划书 1.1）：cmd 列表与字段名都来自 profile 的 `BAND_STATUS` 分组。
     * 值原样透出 —— `"0"` / `"all"` 表示未锁定，客户端的 `parseBands()` 已固化这个解析。
     */
    suspend fun getBandLockStatus(): NormalizedFields?

    // ==================== 流量限额 ====================

    /**
     * 流量限额配置 + 月用量。
     *
     * 已归一化（计划书 2.8 尾巴）：设备的 `data_volume_limit_size` 复合串（`"470_1024"`）由
     * profile 的结构解码器拆成 `limit_value` / `limit_unit_display` / `limit_bytes`，
     * 上层不再见到复合串，也不需要知道单位藏在乘数里。开关值统一成 `"1"`/`"0"`。
     */
    suspend fun getDataUsage(): NormalizedFields?
}
