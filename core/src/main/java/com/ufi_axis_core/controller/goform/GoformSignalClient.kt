package com.ufi_axis_core.controller.goform

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
 */
class GoformSignalClient(private val client: GoformClient) {

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
     * 单独获取 network_information（供需要仅获取 NR 信息时使用）
     * 返回字段：Nr_fcn, Nr_pci, Nr_bands, Nr_band_widths, Nr_cell_id,
     *           Nr_signal_strength, Nr_snr, nr_rsrp, nr_rsrq, nr_rssi, network_type
     */
    suspend fun getNetworkInformation(): JsonObject? {
        return client.query(listOf("network_information", "Lte_ca_status"))
    }

    // ==================== 设备信息 ====================

    suspend fun getDeviceInfo(): JsonObject? {
        return client.query(listOf("imei", "imsi", "lan_ipaddr", "mac_address"))
    }

    /**
     * 获取设备身份信息（单批 20 字段）
     * 包含：msisdn, imei, imsi, iccid, sim_imsi,
     *       hardware_version, web_version, wa_version, cr_version, wa_inner_version,
     *       lan_ipaddr, mac_address, wan_ipaddr, ipv6_wan_ipaddr, LocalDomain,
     *       ppp_status, network_type, rssi, pdp_type, opms_wan_mode
     */
    suspend fun getDeviceIdentity(): JsonObject? {
        val data = client.query(listOf(
            "msisdn", "imei", "imsi", "iccid", "sim_imsi",
            "hardware_version", "web_version", "wa_version", "cr_version", "wa_inner_version",
            "lan_ipaddr", "mac_address", "wan_ipaddr", "ipv6_wan_ipaddr", "LocalDomain",
            "ppp_status", "network_type", "rssi", "pdp_type", "opms_wan_mode"
        ))
        return data?.ifEmpty { null }
    }

    /**
     * 获取设备固件版本信息
     * 返回字段: Language (语言), cr_version (固件版本), wa_inner_version (基带/Modem版本)
     */
    suspend fun getDeviceVersion(): JsonObject? {
        return client.query(listOf("Language", "cr_version", "wa_inner_version"))
    }

    // ==================== 流量统计 ====================

    suspend fun getTrafficStats(): JsonObject? {
        return client.query(listOf(
            "realtime_tx_bytes", "realtime_rx_bytes",
            "monthly_rx_bytes", "monthly_tx_bytes",
            "realtime_time", "monthly_time",
            "realtime_tx_thrpt", "realtime_rx_thrpt"
        ))
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

    // ==================== 基站信息 ====================

    /**
     * 获取小区信息（邻区 + 已锁定基站 + 当前服务小区），单次 goform 查询。
     *
     * 替代原来 2×HTTP（batch1: neighbor_cell_info+locked_cell_info+network_information；
     * batch2: Lte_pci/Lte_fcn/Lte_bands/lte_rsrp），减少 HTTP 往返。
     *
     * 查询字段映射：
     * - neighbor_cell_info   → JSON 数组 [{band,earfcn,pci,rsrp,rsrq,sinr}]  邻区列表
     * - locked_cell_info     → JSON 数组 [{earfcn,pci,rat}]                   已锁定基站
     * - network_information  → 结构化字段 Nr_fcn/Nr_pci/Nr_bands/nr_rsrp/Nr_snr/network_type
     * - Lte_pci/Lte_fcn/Lte_bands/lte_rsrp/lte_rsrq/lte_snr → LTE 服务小区补充
     */
    suspend fun getCellInfo(): JsonObject? {
        val data = client.query(listOf(
            "neighbor_cell_info", "locked_cell_info", "network_information",
            "network_type",
            "Lte_pci", "Lte_fcn", "Lte_bands",
            "lte_rsrp", "lte_rsrq", "lte_snr"
        )) ?: return null
        return data.ifEmpty { null }
    }

    /**
     * 仅查询邻区信息（neighbor_cell_info），用于快速刷新邻区列表。
     * 返回完整的 neighbor_cell_info JSON 数组。
     */
    suspend fun getNeighborCellInfo(): JsonArray? {
        val data = client.query(listOf("neighbor_cell_info")) ?: return null
        val raw = data["neighbor_cell_info"] ?: return null
        return when (raw) {
            is JsonArray -> raw
            is JsonPrimitive -> try { Json.parseToJsonElement(raw.content).jsonArray } catch (_: Exception) { null }
            else -> null
        }
    }

    // ==================== LAN/DHCP ====================

    suspend fun getLanSettings(): JsonObject? {
        return client.query(listOf(
            "lan_ipaddr", "lan_netmask", "mac_address", "dhcpEnabled",
            "dhcpStart", "dhcpEnd", "dhcpLease_hour", "mtu", "tcp_mss"
        ))
    }

    // ==================== 设备设置状态查询 ====================

    suspend fun queryDeviceSettings(): Map<String, JsonElement>? {
        return client.query(listOf(
            "indicator_light_switch", "performance_mode",
            "roam_setting_option", "dial_roam_setting_option",
            "net_select", "lte_band_lock", "nr_band_lock",
            "usb_port_switch", "web_wifi_nfc_switch", "samba_switch",
            "restart_schedule_switch", "restart_time",
            "sleep_sysIdleTimeToSleep", "is_support_nfc_functions",
            "usb_network_protocal", "BearerPreference", "connection_mode"
        ))
    }

    /**
     * 精准查询频段锁定状态（仅 lte_band_lock + nr_band_lock，避免 queryDeviceSettings 16 字段冗余）
     * 返回: {"lte_band_lock":"1,3,5,...", "nr_band_lock":"1,5,8,..."}
     */
    suspend fun getBandLockStatus(): JsonObject? {
        return client.query(listOf("lte_band_lock", "nr_band_lock"))
    }

    // ==================== MAC 黑名单 ====================

    suspend fun queryDeviceAccessControlList(): JsonObject? {
        return client.query(listOf("queryDeviceAccessControlList"))
    }

    // ==================== 流量限额 ====================

    suspend fun getDataUsage(): JsonObject? {
        return client.query(listOf(
            "flux_data_volume_limit_switch", "data_volume_limit_switch",
            "data_volume_limit_unit", "data_volume_limit_size",
            "data_volume_alert_percent",
            "monthly_tx_bytes", "monthly_rx_bytes", "monthly_time",
            "wan_auto_clear_flow_data_switch", "traffic_clear_date"
        ))
    }

    // ==================== APN 配置 ====================

    suspend fun getApnConfig(): JsonObject? {
        // 与实际 ZTE goform APN 查询字段完全一致
        val fields = mutableListOf(
            // 基本状态字段
            "apn_interface_version",
            "apn_mode", "apn_select", "apn_num_preset",
            "m_profile_name", "profile_name",
            "wan_dial", "wan_apn",
            "pdp_type", "pdp_select", "pdp_addr",
            "ppp_auth_mode", "ppp_username", "ppp_passwd",
            "dns_mode", "prefer_dns_manual", "standby_dns_manual",
            "index", "Current_index",
            "apn_auto_config",
            // IPv6 对应字段
            "ipv6_apn_auto_config", "ipv6_pdp_type",
            "ipv6_wan_apn", "ipv6_ppp_auth_mode", "ipv6_ppp_username", "ipv6_ppp_passwd",
            "ipv6_dns_mode", "ipv6_prefer_dns_manual", "ipv6_standby_dns_manual",
            // APN 配置槽位 (0..19)
        )
        for (i in 0..19) { fields.add("APN_config$i") }
        for (i in 0..19) { fields.add("ipv6_APN_config$i") }
        // UI 展示用字段（可能脱敏后的值）
        fields.addAll(listOf(
            "wan_apn_ui", "profile_name_ui", "pdp_type_ui",
            "ppp_auth_mode_ui", "ppp_username_ui", "ppp_passwd_ui",
            "dns_mode_ui", "prefer_dns_manual_ui", "standby_dns_manual_ui",
            "ipv6_wan_apn_ui", "ipv6_ppp_auth_mode_ui", "ipv6_ppp_username_ui",
            "ipv6_ppp_passwd_ui", "ipv6_dns_mode_ui",
            "ipv6_prefer_dns_manual_ui", "ipv6_standby_dns_manual_ui"
        ))
        return client.query(fields)
    }

    // ==================== SIM PIN 状态 ====================

    suspend fun getSimPinStatus(): JsonObject? {
        return client.query(listOf("modem_main_state", "mc_modem_main_state", "puknumber", "pinnumber", "sim_pinnumber"))
    }
}
