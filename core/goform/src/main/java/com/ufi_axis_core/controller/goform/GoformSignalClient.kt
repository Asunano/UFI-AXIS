package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
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
 * 详见 `docs/plans/DEVICE-ADAPTER-PLAN.md`。
 *
 * [profile] 传 `null` 即整层短路成原样透传（一键回退，见计划书原则 11）。
 * 尚未迁移的方法仍是透传，迁移进度见计划书 §4。
 */
class GoformSignalClient(
    private val client: GoformClient,
    profile: DeviceProfile?,
) {

    private val fields = GoformFieldMapper(profile)

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
     */
    suspend fun getSignalInfo(): JsonObject? {
        return client.query(listOf(
            "network_type", "network_provider", "rssi", "signalbar", "ppp_status",
            "network_information",
            "lte_rsrp", "Lte_snr", "lte_rsrq", "lte_rssi",
            "cell_id", "Lte_pci", "neighbor_cell_info", "Lte_ca_status",
            "realtime_tx_thrpt", "realtime_rx_thrpt"
        ))
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
    suspend fun getConnectionInfo(): JsonObject? {
        val raw = getSignalInfo() ?: return null
        return fields.normalize(FieldGroup.CONNECTION, raw)
    }

    /**
     * 单独获取 network_information（供需要仅获取 NR 信息时使用）
     * 返回字段：Nr_fcn, Nr_pci, Nr_bands, Nr_band_widths, Nr_cell_id,
     *           Nr_signal_strength, Nr_snr, nr_rsrp, nr_rsrq, nr_rssi, network_type
     */
    suspend fun getNetworkInformation(): JsonObject? {
        return client.query(listOf("network_information", "Lte_ca_status"))
    }

    // ==================== 设备信息 ====================

    suspend fun getDeviceInfo(): JsonObject? {
        return client.query(listOf("imei", "imsi", "iccid", "lan_ipaddr", "mac_address"))
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
    suspend fun getDeviceIdentity(): JsonObject? {
        val data = client.query(fields.cmds(FieldGroup.IDENTITY, listOf(
            "msisdn", "imei", "imsi", "iccid", "sim_imsi",
            "hardware_version", "web_version", "wa_version", "cr_version", "wa_inner_version",
            "lan_ipaddr", "mac_address", "wan_ipaddr", "ipv6_wan_ipaddr", "LocalDomain",
            "ppp_status", "network_type", "rssi", "pdp_type", "opms_wan_mode"
        ))) ?: return null
        return fields.normalize(FieldGroup.IDENTITY, data)?.ifEmpty { null }
    }

    /**
     * 设备固件版本（`/api/device/version`）。
     *
     * 与 [getDeviceIdentity] 同属 IDENTITY 分组，归一化后 key 仍是 `Language` /
     * `cr_version` / `wa_inner_version`（`DeviceRoutes` 用它们拼 camelCase 响应）。
     * 这里不用 `fields.cmds`：本方法只查 3 个字段，是刻意的轻量查询。
     */
    suspend fun getDeviceVersion(): JsonObject? {
        val data = client.query(listOf("Language", "cr_version", "wa_inner_version")) ?: return null
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
     */
    suspend fun getTrafficStats(): JsonObject? {
        val raw = client.query(listOf(
            "monthly_rx_bytes", "monthly_tx_bytes",
            "realtime_time", "monthly_time",
            "realtime_tx_thrpt", "realtime_rx_thrpt"
        )) ?: return null
        val normalized = fields.normalize(FieldGroup.TRAFFIC_LIMIT, raw) ?: return raw
        val merged = LinkedHashMap<String, JsonElement>(raw)
        merged.putAll(normalized)
        return JsonObject(merged)
    }

    /**
     * 获取完整设备状态（75+ 字段，分 3 批查询合并）
     *
     * Batch 1 – 设备身份 + 基础连接
     * Batch 2 – 流量/电池/短信/设置
     * Batch 3 – WiFi 芯片配置 / CA 信息 / 信号补充 / APN 状态
     */
    suspend fun getFullStatus(): JsonObject? {
        val merged = mutableMapOf<String, JsonElement>()

        // Batch 1: 设备身份 + 网络基础 (30 字段)
        client.query(listOf(
            "network_signalbar", "network_rssi", "network_type", "network_provider",
            "ppp_status", "lan_ipaddr", "mac_address", "imei", "imsi", "iccid",
            "wifi_onoff_state", "wifi_access_sta_num", "cr_version",
            "msisdn", "sim_msisdn", "sim_imsi", "ipv6_wan_ipaddr",
            "hardware_version", "web_version", "wa_version", "wa_inner_version",
            "LocalDomain", "wan_ipaddr", "static_wan_ipaddr",
            "pdp_type", "pdp_type_ui", "ipv6_pdp_type", "ipv6_pdp_type_ui",
            "opms_wan_mode", "opms_wan_auto_mode"
        ))?.let { merged.putAll(it) }

        // Batch 2: 流量/电池/短信/设置 (28 字段)
        client.query(listOf(
            "realtime_tx_bytes", "realtime_rx_bytes", "monthly_tx_bytes", "monthly_rx_bytes",
            "realtime_time", "monthly_time", "realtime_rx_thrpt", "realtime_tx_thrpt",
            "battery_value", "battery_vol_percent", "battery_charging",
            "sms_received_flag", "sms_unread_num", "sms_sim_unread_num",
            "data_volume_limit_switch", "data_volume_alert_percent", "data_volume_limit_size",
            "loginfo", "pin_status", "simcard_roam", "usb_port_switch",
            "wifi_chip1_ssid1_ssid", "wifi_5g_enable", "roam_setting_option",
            "Lte_ca_status", "new_version_state", "current_upgrade_state",
            "sim_slot", "dual_sim_support"
        ))?.let { merged.putAll(it) }

        // Batch 3: WiFi 芯片 / CA / 信号 / APN 补充 (30+ 字段)
        client.query(listOf(
            // 5G/LTE 信号补充
            "Z5g_rsrp", "Z5g_snr", "Z5g_SINR", "rssi", "rscp",
            // CA (载波聚合)
            "wan_lte_ca", "lte_ca_pcell_band", "lte_ca_pcell_bandwidth",
            "lte_ca_scell_band", "lte_ca_scell_bandwidth",
            "lte_ca_pcell_arfcn", "lte_ca_scell_arfcn", "lte_multi_ca_scell_info",
            "wan_active_band",
            // APN 版本
            "apn_interface_version",
            // WiFi Chip1
            "wifi_chip1_ssid1_max_access_num", "wifi_chip1_ssid1_auth_mode",
            "wifi_chip1_ssid1_password_encode", "wifi_chip1_ssid1_switch_onoff",
            "wifi_chip1_ssid1_wifi_coverage",
            // WiFi Chip2
            "wifi_chip2_ssid1_ssid", "wifi_chip2_ssid1_auth_mode",
            "wifi_chip2_ssid1_password_encode", "wifi_chip2_ssid1_max_access_num",
            "wifi_chip2_ssid1_switch_onoff",
            // SSID2 (访客)
            "wifi_chip1_ssid2_ssid", "wifi_chip2_ssid2_ssid",
            "wifi_chip1_ssid2_max_access_num", "wifi_chip2_ssid2_max_access_num",
            "wifi_chip1_ssid2_switch_onoff", "wifi_chip2_ssid2_switch_onoff",
            // SSID 高级
            "m_ssid_enable", "m_SSID2", "m_HideSSID",
            // 其他 WiFi
            "wifi_lbd_enable", "guest_switch",
            // IP
            "station_ip_addr"
        ))?.let { merged.putAll(it) }

        return if (merged.isEmpty()) null else JsonObject(merged)
    }

    /**
     * 完整设备状态的**脱敏版**，给诊断端点 `GET /api/device/goform` 用（计划书 9.2）。
     *
     * 这份 dump 不过 allowlist（那正是它的用途：看设备后台到底有什么字段），所以
     * PII 与凭据必须在这里打掉：登记过的按 `Sensitivity`，没登记的按字段名兜底
     * （见 `FieldNormalizer.maskDump`）。想看真值请走对应的业务端点。
     */
    suspend fun getFullStatusMasked(): JsonObject? = fields.maskDump(getFullStatus())

    /**
     * 字段覆盖率诊断（计划书 10.1）：每个分组「登记了哪些 canonical、本设备命中了哪个 source、
     * 哪些一个都没命中」。适配新设备时这份输出就是 TODO 清单。
     *
     * **会逐分组向设备发查询**（最多 10 组，soloCmd 另发），因此只适合按需调用，
     * 不要放进任何轮询路径。输出只含字段名不含值。
     */
    suspend fun diagnoseFieldCoverage(): JsonObject = fields.coverageReport { client.query(it) }



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
    suspend fun getCellInfo(): JsonObject? {
        val data = client.query(fields.cmds(FieldGroup.CELL_INFO, listOf(
            "neighbor_cell_info", "locked_cell_info", "network_information",
            "network_type",
            "Lte_pci", "Lte_fcn", "Lte_bands",
            "lte_rsrp", "lte_rsrq", "lte_snr"
        ))) ?: return null
        return fields.normalize(FieldGroup.CELL_INFO, data)?.ifEmpty { null }
    }

    /**
     * 仅查询邻区信息，用于快速刷新邻区列表。
     *
     * 归一化后 `neighbor_cell_info` 恒为真数组，所以这里不再需要"字符串 or 数组"两路解析。
     */
    suspend fun getNeighborCellInfo(): JsonArray? {
        val data = client.query(listOf("neighbor_cell_info")) ?: return null
        val normalized = fields.normalize(FieldGroup.CELL_INFO, data) ?: return null
        return normalized["neighbor_cell_info"] as? JsonArray
    }

    // ==================== LAN/DHCP ====================

    /**
     * LAN / DHCP 状态。
     *
     * 已归一化（计划书 1.3）：别名链取 web 与 app 的并集（app 侧还认 `ipaddr` / `DhcpStatus`
     * / `DhcpStartIP` 等老名字）。`dhcpLease_hour` **不做单位换算** —— 两端客户端都已固化
     * "读到后自己 ×3600"，在这里换算会变成双重换算。
     */
    suspend fun getLanSettings(): JsonObject? {
        val cmds = fields.cmds(FieldGroup.LAN_SETTINGS, listOf(
            "lan_ipaddr", "lan_netmask", "mac_address", "dhcpEnabled",
            "dhcpStart", "dhcpEnd", "dhcpLease_hour", "mtu", "tcp_mss"
        ))
        return fields.normalize(FieldGroup.LAN_SETTINGS, client.query(cmds))
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
    suspend fun queryDeviceSettings(): Map<String, JsonElement>? {
        val cmds = fields.cmds(FieldGroup.DEVICE_SETTINGS, listOf(
            "indicator_light_switch", "performance_mode",
            "roam_setting_option", "dial_roam_setting_option",
            "net_select", "lte_band_lock", "nr_band_lock",
            "usb_port_switch", "samba_switch",
            "restart_schedule_switch", "restart_time",
            "sleep_sysIdleTimeToSleep",
            "usb_network_protocal", "BearerPreference", "connection_mode",
            "UpgMode"
        ))
        return fields.normalize(FieldGroup.DEVICE_SETTINGS, client.query(cmds))
    }

    /**
     * 精准查询频段锁定状态（仅 lte_band_lock + nr_band_lock，避免 queryDeviceSettings 16 字段冗余）
     * 返回: {"lte_band_lock":"1,3,5,...", "nr_band_lock":"1,5,8,..."}
     *
     * 已归一化（计划书 1.1）：cmd 列表与字段名都来自 profile 的 `BAND_STATUS` 分组。
     * 值原样透出 —— `"0"` / `"all"` 表示未锁定，客户端的 `parseBands()` 已固化这个解析。
     */
    suspend fun getBandLockStatus(): JsonObject? {
        val cmds = fields.cmds(FieldGroup.BAND_STATUS, listOf("lte_band_lock", "nr_band_lock"))
        return fields.normalize(FieldGroup.BAND_STATUS, client.query(cmds))
    }

    // ==================== 流量限额 ====================

    /**
     * 流量限额配置 + 月用量。
     *
     * 已归一化（计划书 2.8 尾巴）：设备的 `data_volume_limit_size` 复合串（`"470_1024"`）由
     * profile 的结构解码器拆成 `limit_value` / `limit_unit_display` / `limit_bytes`，
     * 上层不再见到复合串，也不需要知道单位藏在乘数里。开关值统一成 `"1"`/`"0"`。
     */
    suspend fun getDataUsage(): JsonObject? {
        val cmds = fields.cmds(FieldGroup.TRAFFIC_LIMIT, listOf(
            "flux_data_volume_limit_switch", "data_volume_limit_switch",
            "data_volume_limit_unit", "data_volume_limit_size",
            "data_volume_alert_percent",
            "monthly_tx_bytes", "monthly_rx_bytes", "monthly_time",
            "wan_auto_clear_flow_data_switch", "traffic_clear_date"
        ))
        return fields.normalize(FieldGroup.TRAFFIC_LIMIT, client.query(cmds))
    }
}

