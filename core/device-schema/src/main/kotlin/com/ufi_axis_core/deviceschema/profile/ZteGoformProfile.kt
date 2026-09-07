package com.ufi_axis_core.deviceschema.profile

import com.ufi_axis_core.contract.DeviceFields
import com.ufi_axis_core.contract.NetworkMode

import com.ufi_axis_core.deviceschema.Decoders
import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.FieldNormalizer
import com.ufi_axis_core.deviceschema.FieldSpec
import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.deviceschema.Sensitivity
import com.ufi_axis_core.deviceschema.WriteSpec
import com.ufi_axis_core.deviceschema.fieldOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ZTE goform 系设备（F50 等）的字段映射。
 *
 * ## 怎么加一个字段（照抄即可）
 *
 * ```kotlin
 * fieldOf(DeviceFields.DeviceSettings.FOO, FieldGroup.DEVICE_SETTINGS, "设备侧原名"),
 * // 有多个固件叫法不同时按优先级列出：
 * fieldOf(DeviceFields.Signal.RSRP, FieldGroup.SIGNAL, "nr_rsrp", "Z5g_rsrp", "lte_rsrp"),
 * // 值需要变换时挂 decode（只做必须做的变换，见 Decoders 的 KDoc）：
 * fieldOf(DeviceFields.LanSettings.MTU, FieldGroup.LAN_SETTINGS, "mtu", decode = Decoders.NUMERIC),
 * ```
 *
 * 然后：① 若属于新分组，把 cmd 名加进 [cmdsFor]；② `DeviceFields.kt` 加常量；
 * ③ `web/src/api/contract.ts` 加镜像常量（校验器会比对两侧）；④ 补一条断言。
 * **route 不用改。**
 *
 * ## 硬性约定
 *
 * - `canonical` 必须写 `DeviceFields.*` 常量，不写字面量。
 * - 设备侧原名只允许出现在本文件（守门脚本会扫 route 里的字段字面量）。
 * - 设备没有的字段就**不登记**，归一化会省略该 key；不要编一个假的 source。
 */
object ZteGoformProfile : DeviceProfile {

    override val id: String = "zte-goform"
    override val displayName: String = "ZTE goform（F50 等）"

    /**
     * 网络类型值映射（`"20"` → `"5G"`）。从 `GoformClient.NETWORK_TYPE_MAP` 逐条搬来。
     *
     * **同一个字段名在不同命令里有两种编码，必须都认**：状态类命令直接回文本（如 `"5G"`），
     * `network_information` 回数字码（如 `20`）。所以只有纯数字才查表，非数字原样透出——
     * 早期无条件查表导致过「明明返回 5G 却显示 未知(5G)」。见 [NETWORK_TYPE_DECODER]。
     */
    private val NETWORK_TYPE_MAP: Map<String, String> = mapOf(
        "0" to "无服务",
        "1" to "GSM",
        "2" to "GPRS",
        "3" to "EDGE",
        "4" to "WCDMA",
        "5" to "HSDPA",
        "6" to "HSUPA",
        "7" to "HSPA",
        "8" to "LTE(FDD)",
        "9" to "LTE(TDD)",
        "10" to "CDMA",
        "11" to "EVDO",
        "12" to "LTE",
        "13" to "TDSCDMA",
        "14" to "TDD LTE",
        "15" to "FDD LTE",
        "16" to "5G(NR)",
        "17" to "NR",
        "18" to "NR-SA",
        "19" to "NR-NSA",
        "20" to "5G",
        "21" to "5G-SA",
        "22" to "5G-NSA",
        "40" to "NR",
        "41" to "LTE-TDD",
        "42" to "LTE-FDD",
        "43" to "NR-TDD",
        "44" to "NR-FDD",
    )

    /**
     * `network_type` 的解码器，语义与 `GoformClient.mapNetworkType` 一致：
     * 空 → 视为字段缺失（省略 key）· 纯数字 → 查表，查不到给 `未知(原值)` · 非数字 → 原样透出。
     *
     * 空值这里返回 null（而不是 `"未知"`），因为原实现用的是 `goformStr()`——空字符串被当成
     * 没这个字段，压根不会写进结果。保持一致，否则前端会看到一个凭空出现的"未知"。
     */
    private val NETWORK_TYPE_DECODER: (JsonElement) -> JsonElement? = Decoders.ofString { raw ->
        val s = raw.trim()
        when {
            s.isEmpty() -> null
            s.all { it.isDigit() } -> NETWORK_TYPE_MAP[s] ?: "未知($s)"
            else -> s
        }
    }

    /**
     * WiFi 密码解码器。ZTE 把密码按 **base64(GBK)** 存放（中文密码固件行为），
     * 从 `GoformClient.base64Decode` 搬来。
     *
     * 放这里而不是留在 `DataHub`：编码方式是设备特性，换一款设备就换一套。
     * 解不开或解出空串时返回 null（= 省略 key）；原实现返回 `""`，客户端两边都写
     * `?? ''` / `?: ""`，省略更符合"缺失即省略"的契约。
     */
    private val WIFI_PASSWORD_DECODER: (JsonElement) -> JsonElement? = Decoders.ofString { raw ->
        if (raw.isBlank()) return@ofString null
        try {
            String(java.util.Base64.getDecoder().decode(raw), java.nio.charset.Charset.forName("GBK"))
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 芯片标识解码器：两种编码都要认。
     * 扁平 `wifi_chip` 直接给 `"chip1"` / `"chip2"`，而 module-info 的 `ChipIndex` 给 `"0"` / `"1"`。
     */
    private val WIFI_CHIP_DECODER: (JsonElement) -> JsonElement? = Decoders.ofString { raw ->
        when (val s = raw.trim()) {
            "" -> null
            "0" -> "chip1"
            "1" -> "chip2"
            else -> s
        }
    }

    override fun readSpecs(): List<FieldSpec> = SPECS

    private val SPECS: List<FieldSpec> = buildList {

        // ───────── 设备开关设置（GoformSignalClient.queryDeviceSettings） ─────────
        // 值域全是字符串 "1"/"0"；漫游额外接受 "on"/"off"，故统一用 BOOL_01 归一。
        add(fieldOf(DeviceFields.DeviceSettings.INDICATOR_LIGHT, FieldGroup.DEVICE_SETTINGS,
            "indicator_light_switch", decode = Decoders.BOOL_01))
        add(fieldOf(DeviceFields.DeviceSettings.PERFORMANCE_MODE, FieldGroup.DEVICE_SETTINGS,
            "performance_mode", decode = Decoders.BOOL_01))
        add(fieldOf(DeviceFields.DeviceSettings.SAMBA, FieldGroup.DEVICE_SETTINGS,
            "samba_switch", decode = Decoders.BOOL_01))
        add(fieldOf(DeviceFields.DeviceSettings.USB_PORT, FieldGroup.DEVICE_SETTINGS,
            "usb_port_switch", decode = Decoders.BOOL_01))
        add(fieldOf(DeviceFields.DeviceSettings.RESTART_SCHEDULE, FieldGroup.DEVICE_SETTINGS,
            "restart_schedule_switch", decode = Decoders.BOOL_01))
        add(fieldOf(DeviceFields.DeviceSettings.RESTART_TIME, FieldGroup.DEVICE_SETTINGS,
            "restart_time", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.DeviceSettings.WIFI_SLEEP_IDLE_MINUTES, FieldGroup.DEVICE_SETTINGS,
            "sleep_sysIdleTimeToSleep", decode = Decoders.NON_BLANK))
        // FOTA 自动检查更新：读写同一个设备字段 UpgMode，"1" = 开（写侧见 SettingKey.FOTA_AUTO_UPDATE）。
        add(fieldOf(DeviceFields.DeviceSettings.FOTA_AUTO_UPDATE, FieldGroup.DEVICE_SETTINGS,
            "UpgMode", decode = Decoders.BOOL_01))
        // 网络模式：新固件填 BearerPreference，老固件只有 net_select。
        // 两个 canonical 都登记，因为 web 与 app 各自读其中一个（冻结契约）。
        add(fieldOf(DeviceFields.DeviceSettings.BEARER_PREFERENCE, FieldGroup.DEVICE_SETTINGS,
            "BearerPreference", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.DeviceSettings.NET_SELECT, FieldGroup.DEVICE_SETTINGS,
            "net_select", decode = Decoders.NON_BLANK))
        // 连接模式值域杂（auto / manual / "1" / hand），原样透出让客户端沿用既有判定。
        add(fieldOf(DeviceFields.DeviceSettings.CONNECTION_MODE, FieldGroup.DEVICE_SETTINGS,
            "connection_mode", "conn_mode", "dial_mode", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.DeviceSettings.ROAM, FieldGroup.DEVICE_SETTINGS,
            "roam_setting_option", decode = Decoders.BOOL_01))
        add(fieldOf(DeviceFields.DeviceSettings.DIAL_ROAM, FieldGroup.DEVICE_SETTINGS,
            "dial_roam_setting_option", decode = Decoders.BOOL_01))

        // ───────── LAN / DHCP（GoformSignalClient.getLanSettings） ─────────
        // 别名集取 web 与 app 的并集：app 侧比 web 多认 ipaddr / DhcpStatus / DhcpStartIP 等。
        add(fieldOf(DeviceFields.LanSettings.LAN_IP, FieldGroup.LAN_SETTINGS,
            "lan_ipaddr", "LanIP", "lan_ip", "ipaddr", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.LanSettings.LAN_NETMASK, FieldGroup.LAN_SETTINGS,
            "lan_netmask", "LanNetmask", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.LanSettings.MAC_ADDRESS, FieldGroup.LAN_SETTINGS,
            "mac_address", decode = Decoders.NON_BLANK))
        // dhcpEnabled 的真值编码包含 "SERVER"，已在 Decoders.TRUTHY 里。
        add(fieldOf(DeviceFields.LanSettings.DHCP_ENABLED, FieldGroup.LAN_SETTINGS,
            "dhcpEnabled", "DhcpEnabled", "dhcp_enable", "DhcpStatus", decode = Decoders.BOOL_01))
        add(fieldOf(DeviceFields.LanSettings.DHCP_START, FieldGroup.LAN_SETTINGS,
            "dhcpStart", "DhcpStartIP", "dhcp_start", "start_ip", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.LanSettings.DHCP_END, FieldGroup.LAN_SETTINGS,
            "dhcpEnd", "DhcpEndIP", "dhcp_end", "end_ip", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.LanSettings.DHCP_LEASE, FieldGroup.LAN_SETTINGS,
            "dhcpLease", "DhcpLease", "dhcp_lease", decode = Decoders.NON_BLANK))
        // 单位是**小时**，客户端读到后自行 *3600。不在这里换算：web 与 app 都已固化这个语义，
        // 归一化时顺手换算会让它们的乘 3600 变成双重换算。
        add(fieldOf(DeviceFields.LanSettings.DHCP_LEASE_HOUR, FieldGroup.LAN_SETTINGS,
            "dhcpLease_hour", "dhcp_lease_hour", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.LanSettings.MTU, FieldGroup.LAN_SETTINGS,
            "mtu", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.LanSettings.TCP_MSS, FieldGroup.LAN_SETTINGS,
            "tcp_mss", decode = Decoders.NON_BLANK))

        // ───────── 频段锁定（GoformSignalClient.getBandLockStatus） ─────────
        // 值是纯数字逗号串，"0"/"all" = 未锁定。原样透出（web 的 parseBands 已固化解析）。
        add(fieldOf(DeviceFields.BandStatus.LTE_BAND_LOCK, FieldGroup.BAND_STATUS, "lte_band_lock"))
        add(fieldOf(DeviceFields.BandStatus.NR_BAND_LOCK, FieldGroup.BAND_STATUS, "nr_band_lock"))

        // ───────── 流量限额（GoformSignalClient.getDataUsage） ─────────
        // 部分固件只填 flux_* 前缀那一份，故双 source。
        add(fieldOf(DeviceFields.TrafficLimit.ENABLED, FieldGroup.TRAFFIC_LIMIT,
            "data_volume_limit_switch", "flux_data_volume_limit_switch", decode = Decoders.BOOL_01))
        // 复合串 data_volume_limit_size（"470_1024" = 470 GB）由 structuralDecoder
        // 在归一化前拆成下面三条，**派生键直接用 canonical 名** —— 这样过渡期 KEEP_PRESENT
        // 也不会把复合串当"别名"再透出一份（计划书 2.8：复合串不进对外契约）。
        // data_volume_limit_unit 是**限额模式**（"data" 按流量 / "time" 按时长），
        // 不是数据单位（真实单位藏在 size 的乘数里），对外没有意义，故不登记。
        add(fieldOf(DeviceFields.TrafficLimit.LIMIT_VALUE, FieldGroup.TRAFFIC_LIMIT,
            DeviceFields.TrafficLimit.LIMIT_VALUE, decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.TrafficLimit.LIMIT_UNIT_DISPLAY, FieldGroup.TRAFFIC_LIMIT,
            DeviceFields.TrafficLimit.LIMIT_UNIT_DISPLAY, decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.TrafficLimit.LIMIT_BYTES, FieldGroup.TRAFFIC_LIMIT,
            DeviceFields.TrafficLimit.LIMIT_BYTES, decode = Decoders.NUMERIC))
        add(fieldOf(DeviceFields.TrafficLimit.ALERT_PERCENT, FieldGroup.TRAFFIC_LIMIT,
            "data_volume_alert_percent", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.TrafficLimit.AUTO_CLEAR, FieldGroup.TRAFFIC_LIMIT,
            "wan_auto_clear_flow_data_switch", decode = Decoders.BOOL_01))
        add(fieldOf(DeviceFields.TrafficLimit.CLEAR_DATE, FieldGroup.TRAFFIC_LIMIT,
            "traffic_clear_date", decode = Decoders.NON_BLANK))
        // ⚠️ 上下行**故意交叉绑定**：ZTE 固件的 monthly_rx/tx 是从「模块看 PC」的视角命名的，
        // 与 canonical（rx = 下行/下载、tx = 上行/上传）正好相反。2026-09-01 实测同一台 F50：
        //   monthly_rx_bytes = 985,757,259（≈940 MB，实际是**上传**）
        //   monthly_tx_bytes = 7,530,676,294（≈7.0 GB，实际是**下载**）
        // 所以在唯一的适配层一次性掰正，上层（TrafficLimitMapper / 流量管理 UI / web）
        // 拿到的 canonical rx 就是下载、tx 就是上传，不需要再各自记住"这台设备是反的"。
        // 注意：诊断端点 /api/device/goform 走 getFullStatusMasked() 不过本表，
        // 那份 dump 就该保持设备原样（原名 + 原值），排障时才能对上设备后台。
        add(fieldOf(DeviceFields.TrafficLimit.MONTHLY_RX_BYTES, FieldGroup.TRAFFIC_LIMIT,
            "monthly_tx_bytes", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.TrafficLimit.MONTHLY_TX_BYTES, FieldGroup.TRAFFIC_LIMIT,
            "monthly_rx_bytes", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.TrafficLimit.MONTHLY_TIME, FieldGroup.TRAFFIC_LIMIT,
            "monthly_time", decode = Decoders.NON_BLANK))

        // ───────── 设备身份（GoformSignalClient.getDeviceIdentity / getDeviceVersion） ─────────
        // PII：手机号 / IMEI / IMSI / ICCID 标 MASKED —— 正常响应给真值，日志与诊断端点打码。
        // `sim_msisdn` 与 `msisdn` 是同一个号码的两个设备字段名（原始 dump 里两个都会出现），
        // 都登记进来才能让诊断脱敏（计划书 9.2）按名字命中 —— 否则 dump 里 `sim_msisdn` 漏真值。
        add(fieldOf(DeviceFields.Identity.MSISDN, FieldGroup.IDENTITY,
            "msisdn", "sim_msisdn", sensitivity = Sensitivity.MASKED, decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.Identity.IMEI, FieldGroup.IDENTITY,
            "imei", sensitivity = Sensitivity.MASKED, decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.Identity.IMSI, FieldGroup.IDENTITY,
            "imsi", "sim_imsi", sensitivity = Sensitivity.MASKED, decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.Identity.ICCID, FieldGroup.IDENTITY,
            "iccid", sensitivity = Sensitivity.MASKED, decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.Identity.LANGUAGE, FieldGroup.IDENTITY,
            "Language", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.Identity.CR_VERSION, FieldGroup.IDENTITY,
            "cr_version", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.Identity.INNER_VERSION, FieldGroup.IDENTITY,
            "wa_inner_version", decode = Decoders.NON_BLANK))

        // ───────── 连接状态（多端点共用） ─────────
        add(fieldOf(DeviceFields.Connection.PPP_STATUS, FieldGroup.CONNECTION,
            "ppp_status", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.Connection.NETWORK_TYPE, FieldGroup.CONNECTION,
            "network_type", decode = NETWORK_TYPE_DECODER))
        add(fieldOf(DeviceFields.Connection.NETWORK_PROVIDER, FieldGroup.CONNECTION,
            "network_provider", decode = Decoders.NON_BLANK))

        // ───────── WiFi 设置（GoformWifiClient.getWifiSettingsMerged） ─────────
        // canonical 就是 goform 扁平名本身（web 的 normalizeWifiSettings 与 app 的
        // parseWifiSettings 双侧固化），所以这里 canonical == 首个扁平 source。
        //
        // 每条别名链的**第一个**是 ZTE 原生驼峰名（`SSID` / `Password` / `AuthMode`…）：
        // 它们由 [liftActiveAccessPoint] 从 `queryAccessPointInfo` 的 `ResponseList` 里提到顶层。
        // 放在最前是为了逐字节复刻原 `DataHub.processModuleInfo` 的优先级——它在扁平字段
        // 之后执行并覆盖，即 **module-info 胜过扁平查询**。
        add(fieldOf(DeviceFields.WifiSettings.CHIP, FieldGroup.WIFI_SETTINGS,
            "ChipIndex", "wifi_chip", "chip", decode = WIFI_CHIP_DECODER))
        add(fieldOf(DeviceFields.WifiSettings.SSID, FieldGroup.WIFI_SETTINGS,
            "SSID", "wifi_chip1_ssid1_ssid", "wifi_chip1_ssid", decode = Decoders.NON_BLANK))
        // WiFi 明文密码（core 已 base64(GBK) 解码）。MASKED 而不是 SECRET：web 的二维码功能要真值。
        add(fieldOf(DeviceFields.WifiSettings.PASSPHRASE, FieldGroup.WIFI_SETTINGS,
            "Password", "wifi_chip1_ssid1_passphrase", "wifi_chip1_passphrase",
            sensitivity = Sensitivity.MASKED, decode = WIFI_PASSWORD_DECODER))
        add(fieldOf(DeviceFields.WifiSettings.AUTH_MODE, FieldGroup.WIFI_SETTINGS,
            "AuthMode", "wifi_chip1_ssid1_auth_mode", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.WifiSettings.ENCRYPT_TYPE, FieldGroup.WIFI_SETTINGS,
            "EncrypType", "wifi_chip1_ssid1_encryp_type", decode = Decoders.NON_BLANK))
        // "1" = 隐藏 SSID（语义是隐藏而不是广播），原样透出。
        add(fieldOf(DeviceFields.WifiSettings.BROADCAST_SSID, FieldGroup.WIFI_SETTINGS,
            "ApBroadcastDisabled", "wifi_chip1_ssid1_broadcast_ssid", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.WifiSettings.MAX_STA_NUM, FieldGroup.WIFI_SETTINGS,
            "ApMaxStationNumber", "wifi_chip1_ssid1_max_sta_num", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.WifiSettings.MODULE_SWITCH, FieldGroup.WIFI_SETTINGS,
            "WiFiModuleSwitch", "wifi_enable", "wifi_onoff_state", decode = Decoders.BOOL_01))

        // ───────── WiFi 客户端（GoformWifiClient.getConnectedClients） ─────────
        // 值形态在设备侧不稳定：可能是数组，也可能是数组的 JSON 字符串（双重编码）。
        // 这里只登记键名，形态统一交给 structuralDecoder(WIFI_CLIENTS) = normalizeStationLists
        // （统一成真数组 + 元素键归一）；关掉归一化开关排障时才会原样透出双重编码。
        add(fieldOf(DeviceFields.WifiClients.STATION_LIST, FieldGroup.WIFI_CLIENTS, "station_list"))
        add(fieldOf(DeviceFields.WifiClients.LAN_STATION_LIST, FieldGroup.WIFI_CLIENTS, "lan_station_list"))

        // ───────── 小区信息（GoformSignalClient.getCellInfo） ─────────
        // canonical 即设备原名（web 的 mapNeighbors/parseCellArray 已固化），原样透出。
        add(fieldOf(DeviceFields.CellInfo.NEIGHBOR_CELL_INFO, FieldGroup.CELL_INFO, "neighbor_cell_info"))
        add(fieldOf(DeviceFields.CellInfo.LOCKED_CELL_INFO, FieldGroup.CELL_INFO, "locked_cell_info"))
        add(fieldOf(DeviceFields.CellInfo.LTE_PCI, FieldGroup.CELL_INFO, "Lte_pci"))
        add(fieldOf(DeviceFields.CellInfo.LTE_EARFCN, FieldGroup.CELL_INFO, "Lte_fcn"))
        add(fieldOf(DeviceFields.CellInfo.LTE_BANDS, FieldGroup.CELL_INFO, "Lte_bands"))
        add(fieldOf(DeviceFields.CellInfo.LTE_RSRP, FieldGroup.CELL_INFO, "lte_rsrp"))
        add(fieldOf(DeviceFields.CellInfo.LTE_RSRQ, FieldGroup.CELL_INFO, "lte_rsrq"))
        add(fieldOf(DeviceFields.CellInfo.LTE_SNR, FieldGroup.CELL_INFO, "lte_snr", "Lte_snr"))

        // ───────── 信号（FieldGroup.SIGNAL） ─────────
        // 从 SignalCollector.collectLayer1GoformFields 逐条搬过来（行为必须一致）。
        // 两个要点：
        //  ① 这批 canonical 是 **core 自有的小写归一名**，同时被 WS `signal` 频道和 REST 使用，
        //     一个都不能改（`lte_snr` 看着像设备原名，设备原名其实是 `Lte_snr`）。
        //  ② goform 这组命名极不一致，必须逐个照抄固件原文：频点 LTE 是 `Lte_fcn`、
        //     带宽 LTE 是 `Lte_bands_widths`（多一个 s），而 NR 侧是 `Nr_fcn` / `Nr_band_widths`。
        //     多数字段藏在 `network_information` 子对象里，由 [structuralDecoder] 提前摊平。

        // 服务小区合并值：4G 驻网时装的就是 lte_* 的值
        add(fieldOf(DeviceFields.Signal.RSRP, FieldGroup.SIGNAL,
            "nr_rsrp", "Z5g_rsrp", "lte_rsrp", decode = Decoders.NUMERIC))
        add(fieldOf(DeviceFields.Signal.SINR, FieldGroup.SIGNAL,
            "Nr_snr", "Lte_snr", decode = Decoders.NUMERIC))
        add(fieldOf(DeviceFields.Signal.RSRQ, FieldGroup.SIGNAL,
            "nr_rsrq", "lte_rsrq", decode = Decoders.NUMERIC))
        // 注意：goform 顶层的 `rssi` 是信号条数 0-5，**不能**当 dBm 用，所以不在别名链里
        add(fieldOf(DeviceFields.Signal.RSSI, FieldGroup.SIGNAL,
            "Nr_signal_strength", "nr_rssi", "lte_rssi", decode = Decoders.NUMERIC))
        add(fieldOf(DeviceFields.Signal.RAT, FieldGroup.SIGNAL,
            "network_type", decode = NETWORK_TYPE_DECODER))
        // NCI 值域超 Int，保持字符串
        add(fieldOf(DeviceFields.Signal.CELL_ID, FieldGroup.SIGNAL,
            "Nr_cell_id", "cell_id", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.Signal.OPERATOR, FieldGroup.SIGNAL,
            "network_provider", decode = Decoders.NON_BLANK))

        // 5G(NR) 专属：只在 NR 有值时出现，缺失即表示当前不在 5G
        add(fieldOf(DeviceFields.Signal.NR_ARFCN, FieldGroup.SIGNAL, "Nr_fcn", decode = Decoders.NUMERIC))
        add(fieldOf(DeviceFields.Signal.NR_BAND, FieldGroup.SIGNAL, "Nr_bands", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.Signal.NR_BAND_WIDTH, FieldGroup.SIGNAL, "Nr_band_widths", decode = Decoders.NUMERIC))
        add(fieldOf(DeviceFields.Signal.NR_SIGNAL_STRENGTH, FieldGroup.SIGNAL, "Nr_signal_strength", decode = Decoders.NUMERIC))
        add(fieldOf(DeviceFields.Signal.NR_SNR, FieldGroup.SIGNAL, "Nr_snr", decode = Decoders.NUMERIC))
        add(fieldOf(DeviceFields.Signal.NR_PCI, FieldGroup.SIGNAL, "Nr_pci", decode = Decoders.NUMERIC))
        add(fieldOf(DeviceFields.Signal.NR_CELL_ID, FieldGroup.SIGNAL, "Nr_cell_id", decode = Decoders.NON_BLANK))

        // 4G(LTE) 专属：语义与上面完全对称
        add(fieldOf(DeviceFields.Signal.LTE_ARFCN, FieldGroup.SIGNAL, "Lte_fcn", decode = Decoders.NUMERIC))
        add(fieldOf(DeviceFields.Signal.LTE_BAND, FieldGroup.SIGNAL, "Lte_bands", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.Signal.LTE_BAND_WIDTH, FieldGroup.SIGNAL, "Lte_bands_widths", decode = Decoders.NUMERIC))
        add(fieldOf(DeviceFields.Signal.LTE_SIGNAL_STRENGTH, FieldGroup.SIGNAL, "Lte_signal_strength", decode = Decoders.NUMERIC))
        add(fieldOf(DeviceFields.Signal.LTE_SNR, FieldGroup.SIGNAL, "Lte_snr", decode = Decoders.NUMERIC))
        add(fieldOf(DeviceFields.Signal.LTE_PCI, FieldGroup.SIGNAL, "Lte_pci", decode = Decoders.NUMERIC))
        add(fieldOf(DeviceFields.Signal.LTE_CELL_ID, FieldGroup.SIGNAL, "Lte_cell_id", decode = Decoders.NON_BLANK))
        // CA 状态是原始枚举（"off" / "on"…），不在这里翻译成中文——展示层负责文案
        add(fieldOf(DeviceFields.Signal.LTE_CA_STATUS, FieldGroup.SIGNAL, "Lte_ca_status", decode = Decoders.NON_BLANK))
        // DeviceFields.Signal.NETWORK_REGISTERED 不登记：它只能由 Android Telephony 提供，
        // goform 没有对应字段（见 SignalCollector 第 3 层）。
        // BAND / BAND_LABEL / ARFCN / BAND_WIDTH / SIGNAL_STRENGTH / PCI 也不登记：
        // 它们是 core 从上面 nr_*/lte_* 派生的服务小区统一字段，见 [deriveServingCell]。
    }

    override fun cmdsFor(group: FieldGroup): List<String> = when (group) {
        // 与 GoformSignalClient.queryDeviceSettings 一致（含 usb_network_protocal：
        // 当前无字段登记，但保留在查询里以免设备端行为变化，且它是既有查询的一部分）
        FieldGroup.DEVICE_SETTINGS -> listOf(
            "indicator_light_switch", "performance_mode",
            "roam_setting_option", "dial_roam_setting_option",
            "net_select", "lte_band_lock", "nr_band_lock",
            "usb_port_switch", "samba_switch",
            "restart_schedule_switch", "restart_time",
            "sleep_sysIdleTimeToSleep",
            "usb_network_protocal", "BearerPreference", "connection_mode",
            "UpgMode",
        )

        FieldGroup.LAN_SETTINGS -> listOf(
            "lan_ipaddr", "lan_netmask", "mac_address", "dhcpEnabled",
            "dhcpStart", "dhcpEnd", "dhcpLease_hour", "mtu", "tcp_mss",
        )

        FieldGroup.BAND_STATUS -> listOf("lte_band_lock", "nr_band_lock")

        FieldGroup.TRAFFIC_LIMIT -> listOf(
            "flux_data_volume_limit_switch", "data_volume_limit_switch",
            "data_volume_limit_unit", "data_volume_limit_size",
            "data_volume_alert_percent",
            "monthly_tx_bytes", "monthly_rx_bytes", "monthly_time",
            "wan_auto_clear_flow_data_switch", "traffic_clear_date",
        )

        FieldGroup.IDENTITY -> listOf(
            "msisdn", "imei", "imsi", "iccid", "sim_imsi",
            "hardware_version", "web_version", "wa_version", "cr_version", "wa_inner_version",
            "lan_ipaddr", "mac_address", "wan_ipaddr", "ipv6_wan_ipaddr", "LocalDomain",
            "ppp_status", "network_type", "rssi", "pdp_type", "opms_wan_mode",
        )

        FieldGroup.CONNECTION -> listOf("network_type", "network_provider", "ppp_status")

        // 与 GoformWifiClient.getWifiSettingsMerged 一致：前 8 个是扁平字段查询，
        // 后 2 个是 module-info（`queryAccessPointInfo` 是**容器命令**，返回 `ResponseList` 数组，
        // 由 structuralDecoder(WIFI_SETTINGS) 挑出生效 AP 后提到顶层）。
        FieldGroup.WIFI_SETTINGS -> listOf(
            "wifi_chip1_ssid1_ssid", "WiFiModuleSwitch",
            "wifi_chip1_ssid1_passphrase", "wifi_chip",
            "wifi_chip1_ssid1_auth_mode", "wifi_chip1_ssid1_encryp_type",
            "wifi_chip1_ssid1_max_sta_num", "wifi_chip1_ssid1_broadcast_ssid",
            "queryWiFiModuleSwitch", "queryAccessPointInfo",
        )

        // station_list 必须单独查（与其它 cmd 组合时设备返回空，见 GoformWifiClient 的 2026-08-23 修复注释）
        FieldGroup.WIFI_CLIENTS -> listOf("station_list")

        // 与 GoformSignalClient.getCellInfo 一致。`network_information` / `network_type` 没有登记
        // CELL_INFO canonical（NR 字段归 SIGNAL 分组），但保留在查询里：它们是既有查询的一部分，
        // 去掉会改变设备侧请求形状。
        FieldGroup.CELL_INFO -> listOf(
            "neighbor_cell_info", "locked_cell_info", "network_information",
            "network_type",
            "Lte_pci", "Lte_fcn", "Lte_bands", "lte_rsrp", "lte_rsrq", "Lte_snr",
        )

        // 与 GoformSignalClient.getSignalInfo 一致。注意 network_information 是**容器命令**，
        // NR 与部分 LTE 字段（Nr_*/Lte_fcn/Lte_bands…）都藏在它返回的子对象里，
        // 由 structuralDecoder(SIGNAL) 摊平到顶层后才能被 spec 命中。
        // signalbar / realtime_*_thrpt 没有登记 canonical（信号条数不是 dBm，吞吐由 traffic 频道负责），
        // 但保留在查询里：它们是既有查询的一部分，去掉会改变设备侧请求形状。
        FieldGroup.SIGNAL -> listOf(
            "network_type", "network_provider", "rssi", "signalbar", "ppp_status",
            "network_information",
            "lte_rsrp", "Lte_snr", "lte_rsrq", "lte_rssi",
            "cell_id", "Lte_pci", "neighbor_cell_info", "Lte_ca_status",
            "realtime_tx_thrpt", "realtime_rx_thrpt",
        )
    }

    override fun soloCmds(group: FieldGroup): List<String> =
        if (group == FieldGroup.WIFI_CLIENTS) listOf("station_list") else emptyList()

    // ───────────────────────── 结构解码器 ─────────────────────────

    override fun structuralDecoder(group: FieldGroup): ((JsonObject) -> JsonObject)? = when (group) {
        FieldGroup.SIGNAL -> ::flattenNetworkInformation
        FieldGroup.WIFI_SETTINGS -> ::liftActiveAccessPoint
        FieldGroup.WIFI_CLIENTS -> ::normalizeStationLists
        FieldGroup.CELL_INFO -> ::normalizeCellLists
        FieldGroup.TRAFFIC_LIMIT -> ::splitDataVolumeLimit
        else -> null
    }

    /**
     * 把 `network_information` 子对象摊平到顶层（**顶层优先，不覆盖**）。
     *
     * 5G 字段（`nr_rsrp` / `Nr_snr` / `Nr_bands` …）在 multi_data 查询里以嵌套对象返回，
     * 部分固件还会把它作为 **JSON 字符串**返回（双重编码）。4G 字段通常已在顶层。
     * 没有这一步，SIGNAL 分组的 spec 一个都命中不了。
     */
    private fun flattenNetworkInformation(raw: JsonObject): JsonObject {
        val ni = raw["network_information"] ?: return raw
        val nested: JsonObject? = when {
            ni is JsonObject -> ni
            ni is JsonPrimitive -> try {
                Json.parseToJsonElement(ni.content) as? JsonObject
            } catch (_: Exception) {
                null
            }
            else -> null
        }
        if (nested == null) return raw
        // 顶层优先：顶层键是对应 cmd 的直接应答，容器里的同名键只是快照，
        // 两者不一致时（NSA 切换瞬间会出现）以直接应答为准。
        val merged = raw.toMutableMap()
        nested.forEach { (k, v) -> merged.putIfAbsent(k, v) }
        return JsonObject(merged)
    }

    /**
     * 把 `queryAccessPointInfo` 返回的 `ResponseList` 里**生效那个 AP** 的字段提到顶层。
     *
     * 取代原 `DataHub.processModuleInfo`——那是整个 core 里唯一一处字段重命名逻辑，
     * 留在 DataHub 就等于维护第二份映射表。这里只做「结构变换」三件事：
     *  1. 带谓词的元素选择：`AccessPointSwitchStatus == "1"` 的 AP，挑不到退回第一个；
     *  2. 把它的 `SSID` / `Password` / `AuthMode` / `EncrypType` / `ChipIndex` /
     *     `ApMaxStationNumber` / `ApBroadcastDisabled` 原名提到顶层；
     *  3. 密码补位：生效 AP 的 `Password` 为空时，取所有 AP 里第一个非空的
     *     （固件在双频共享密码时只在其中一个 AP 上填值）。
     *
     * **重命名与值解码都不在这里**：提上来的还是 ZTE 原名，由 [SPECS] 的别名链吃掉，
     * base64(GBK) 解码由 [WIFI_PASSWORD_DECODER] 负责。所以 allowlist 依然是唯一出口。
     *
     * 顶层同名键会被 AP 值**覆盖**（与原 processModuleInfo 的执行顺序一致）——但 ZTE 原生驼峰名
     * 与 goform 扁平名不重名，实际不会撞；真正的优先级由别名链顺序（原名在前）表达。
     */
    private fun liftActiveAccessPoint(raw: JsonObject): JsonObject {
        val list = raw["ResponseList"] as? JsonArray ?: return raw
        val aps = list.mapNotNull { it as? JsonObject }
        if (aps.isEmpty()) return raw

        val active = aps.firstOrNull { str(it["AccessPointSwitchStatus"]) == "1" } ?: aps.first()
        val merged = raw.toMutableMap()
        for (key in AP_LIFTED_KEYS) {
            active[key]?.let { merged[key] = it }
        }
        // 生效 AP 没填密码时跨 AP 兜底
        if (str(merged["Password"]).isNullOrBlank()) {
            aps.firstNotNullOfOrNull { ap -> ap["Password"]?.takeIf { !str(it).isNullOrBlank() } }
                ?.let { merged["Password"] = it }
        }
        return JsonObject(merged)
    }

    /** [liftActiveAccessPoint] 要提到顶层的 AP 字段（ZTE 原生驼峰名）。 */
    private val AP_LIFTED_KEYS = listOf(
        "SSID", "Password", "AuthMode", "EncrypType",
        "ChipIndex", "ApMaxStationNumber", "ApBroadcastDisabled",
    )

    private fun str(el: JsonElement?): String? = (el as? JsonPrimitive)?.content

    /**
     * 客户端列表：**统一成真数组** + 元素键归一。
     *
     * 设备对 `station_list` / `lan_station_list` 有两种形态：真 JSON 数组，或**数组的 JSON 字符串**
     * （双重编码，固件而定）。原来两端各自兜底（app `parseArrayField`、web `data?.stationList || ...`），
     * 现在 core 一律吐真数组 —— 这是两端已支持形态的子集，不会破坏任何现有消费点。
     *
     * 元素键也在这里对齐（`mac` → `mac_addr`）：这是 web `c.mac_addr ?? c.mac` 那段 if 的来源。
     */
    private fun normalizeStationLists(raw: JsonObject): JsonObject =
        normalizeArrayFields(raw, STATION_LIST_KEYS, STATION_ITEM_ALIASES)

    /**
     * 小区列表：与 [normalizeStationLists] 同样的处理（真数组 + 元素键归一）。
     *
     * `neighbor_cell_info` / `locked_cell_info` 也是"数组 or 数组字符串"两形态，
     * web 的 `parseCellArray()` 就是为此存在的。元素键别名见 [CELL_ITEM_ALIASES]。
     */
    private fun normalizeCellLists(raw: JsonObject): JsonObject =
        normalizeArrayFields(raw, CELL_LIST_KEYS, CELL_ITEM_ALIASES)

    /**
     * 把 [keys] 指向的值统一成真数组，并按 [aliases] 归一每个元素对象的键。
     *
     * 未登记的元素键**保留**（元素级 allowlist 会误伤固件新增的展示信息，
     * 而元素不是接口契约的一部分，客户端按需读）；已有 canonical 名时别名不覆盖。
     * 既不是数组也不是数组字符串时原样保留，交给 allowlist 决定去留。
     */
    private fun normalizeArrayFields(
        raw: JsonObject,
        keys: List<String>,
        aliases: Map<String, String>,
    ): JsonObject {
        val out = raw.toMutableMap()
        for (key in keys) {
            val arr = asJsonArray(raw[key] ?: continue) ?: continue
            out[key] = JsonArray(arr.map { normalizeArrayItem(it, aliases) })
        }
        return JsonObject(out)
    }

    private val STATION_LIST_KEYS = listOf(
        DeviceFields.WifiClients.STATION_LIST,
        DeviceFields.WifiClients.LAN_STATION_LIST,
    )

    private val CELL_LIST_KEYS = listOf(
        DeviceFields.CellInfo.NEIGHBOR_CELL_INFO,
        DeviceFields.CellInfo.LOCKED_CELL_INFO,
    )

    /** 元素键别名 → canonical。设备原名不统一，`mac` 与 `mac_addr` 在不同固件上都出现过。 */
    private val STATION_ITEM_ALIASES = mapOf(
        "mac" to DeviceFields.WifiClients.ITEM_MAC,
        "MacAddress" to DeviceFields.WifiClients.ITEM_MAC,
        "ip" to DeviceFields.WifiClients.ITEM_IP,
        "IpAddress" to DeviceFields.WifiClients.ITEM_IP,
        "host_name" to DeviceFields.WifiClients.ITEM_HOSTNAME,
        "HostName" to DeviceFields.WifiClients.ITEM_HOSTNAME,
    )

    /**
     * 小区元素键别名 → canonical。
     * `fcn`/`arfcn` 都指频点（顶层字段叫 `Lte_fcn`，元素里出现过三种写法）；
     * `snr` 与 `sinr` 是同一个量（顶层是 `Lte_snr`，元素契约名是 `sinr`）。
     */
    private val CELL_ITEM_ALIASES = mapOf(
        "fcn" to DeviceFields.CellInfo.ITEM_EARFCN,
        "arfcn" to DeviceFields.CellInfo.ITEM_EARFCN,
        "snr" to DeviceFields.CellInfo.ITEM_SINR,
        "PCI" to DeviceFields.CellInfo.ITEM_PCI,
        "RSRP" to DeviceFields.CellInfo.ITEM_RSRP,
        "RSRQ" to DeviceFields.CellInfo.ITEM_RSRQ,
    )

    private fun normalizeArrayItem(el: JsonElement, aliases: Map<String, String>): JsonElement {
        val obj = el as? JsonObject ?: return el
        val out = LinkedHashMap<String, JsonElement>()
        // 先放 canonical 名，别名只在 canonical 缺失时补位
        obj.forEach { (k, v) -> if (k !in aliases) out[k] = v }
        obj.forEach { (k, v) -> aliases[k]?.let { out.putIfAbsent(it, v) } }
        return JsonObject(out)
    }

    /** 真数组，或双重编码的数组字符串；都不是就返回 null（调用方原样保留）。 */
    private fun asJsonArray(el: JsonElement): JsonArray? = when {
        el is JsonArray -> el
        el is JsonPrimitive -> try {
            Json.parseToJsonElement(el.content) as? JsonArray
        } catch (_: Exception) {
            null
        }
        else -> null
    }

    // ───────────────────── 服务小区统一字段（派生） ─────────────────────
    // 派生规则与设备无关（输入是已归一化的 canonical 字段），所以放在
    // com.ufi_axis_core.deviceschema.ServingCell 而不是这里 —— 否则每个新 profile 都要抄一遍。

    // ───────────────────────── 写入侧 ─────────────────────────
    // 阶段 2 逐项迁移。已登记的这几项是从 GoformDeviceClient / GoformWifiClient /
    // GoformNetworkClient 原样搬来的（cmd 名与参数键逐字符照抄），未登记的返回 null。

    override fun writeSpec(key: SettingKey): WriteSpec? = WRITE_SPECS[key]

    private val WRITE_SPECS: Map<SettingKey, WriteSpec> = mapOf(
        SettingKey.LED to WriteSpec(
            command = "INDICATOR_LIGHT_SETTING",
            encode = { p -> mapOf("indicator_light_switch" to bool01(p["value"])) },
        ),
        SettingKey.PERFORMANCE_MODE to WriteSpec(
            command = "PERFORMANCE_MODE_SETTING",
            encode = { p -> mapOf("performance_mode" to bool01(p["value"])) },
        ),
        SettingKey.WIFI_SLEEP_IDLE_MINUTES to WriteSpec(
            command = "SET_WIFI_SLEEP_INFO",
            encode = { p -> mapOf("sleep_sysIdleTimeToSleep" to (p["value"]?.toString() ?: "0")) },
            validate = { p ->
                val v = (p["value"] as? Int) ?: p["value"]?.toString()?.toIntOrNull()
                if (v == null || v < 0) "休眠时间必须是非负整数（分钟）" else null
            },
        ),
        // WiFi 接入控制名单（拉黑）。参数照 2026-08-30 真机抓包：
        //   goformId=setDeviceAccessControlList&AclMode=2&WhiteMacList=&BlackMacList=2a:ed:87:b3:e8:29;
        //   &WhiteNameList=&BlackNameList=OPPO-Find-X7;
        // 三条硬事实：
        //   ① 四条名单每次都要发全（空也要带 key），设备是整表替换，漏发等于清空；
        //   ② 列表用 `;` 分隔且**末尾也带 `;`**（单项也是 "mac;"）；
        //   ③ Mac 列表与 Name 列表按下标一一对应，长度必须相等。
        // AclMode：2 = 黑名单生效（抓包里"取消拉黑"也仍然是 2，只是名单清空）。
        SettingKey.WIFI_ACL to WriteSpec(
            command = "setDeviceAccessControlList",
            encode = { p ->
                mapOf(
                    "AclMode" to (p["mode"]?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: ACL_MODE_BLACKLIST),
                    "WhiteMacList" to aclList(p["white_macs"]),
                    "BlackMacList" to aclList(p["black_macs"]),
                    "WhiteNameList" to aclList(p["white_names"]),
                    "BlackNameList" to aclList(p["black_names"]),
                )
            },
            validate = { p -> validateAcl(p) },
        ),
        // 频段锁定：值是逗号分隔的频段号；空串 = 解锁。
        // validate 挡住非数字字符 —— 设备侧表单是字符串拼接，未做转义，
        // 值里带 & 或 = 会注入额外参数（见计划书 §9.2）。
        SettingKey.BAND_LOCK_LTE to WriteSpec(
            command = "LTE_BAND_LOCK",
            encode = { p -> mapOf("lte_band_lock" to (p["value"]?.toString() ?: "")) },
            validate = { p -> validateBandList(p["value"]?.toString()) },
        ),
        SettingKey.BAND_LOCK_NR to WriteSpec(
            command = "NR_BAND_LOCK",
            encode = { p -> mapOf("nr_band_lock" to (p["value"]?.toString() ?: "")) },
            validate = { p -> validateBandList(p["value"]?.toString()) },
        ),
        // 定时重启：两个参数（开关 + "HH:mm"），所以走 params 而不是单个 value。
        SettingKey.RESTART_SCHEDULE to WriteSpec(
            command = "RESTART_SCHEDULE_SETTING",
            encode = { p ->
                mapOf(
                    "restart_time" to (p["time"]?.toString() ?: "00:00"),
                    "restart_schedule_switch" to bool01(p["enabled"]),
                )
            },
            validate = { p -> validateClockTime(p["time"]?.toString()) },
        ),
        SettingKey.SAMBA to WriteSpec(
            command = "SAMBA_SETTING",
            encode = { p -> mapOf("samba_switch" to bool01(p["value"])) },
        ),
        SettingKey.USB_PORT to WriteSpec(
            command = "USB_PORT_SETTING",
            encode = { p -> mapOf("usb_port_switch" to bool01(p["value"])) },
        ),
        // 漫游是"另一套布尔编码"的代表：同一个设备，这条命令要 "on"/"off"，
        // 上面几条要 "1"/"0"。统一了设备会静默忽略（返回 200 但值没变），
        // 所以编码规则必须逐命令登记（计划书 2.3 / 陷阱 27）。
        // 另外它复用 SET_CONNECTION_MODE 并且必须带 ConnectionMode=auto_dial。
        SettingKey.ROAM to WriteSpec(
            command = "SET_CONNECTION_MODE",
            encode = { p ->
                val v = boolOnOff(p["value"])
                mapOf(
                    "ConnectionMode" to "auto_dial",
                    "roam_setting_option" to v,
                    "dial_roam_setting_option" to v,
                )
            },
        ),
        // 流量限额：唯一有可选参数的写操作（计划书 2.4）。
        // 复合串 `"470_1024"` 只在这里拼 —— 对外只有 limit_value + limit_unit
        // 两个结构化参数（计划书 2.8：复合格式是设备侧事实，不是对外契约）。
        //
        // 参数集照 2026-08-30 的真机抓包（设备自带 UI，流量管理页点保存）：
        //   isTest=false&goformId=DATA_LIMIT_SETTING
        //   &data_volume_limit_unit=data&data_volume_limit_size=472_1024
        //   &data_volume_alert_percent=90&wan_auto_clear_flow_data_switch=on
        //   &traffic_clear_date=2&data_volume_limit_switch=1&notify_deviceui_enable=0&AD=…
        // 即：自动清零与清零日**就在这条命令里**（网上流传的参数表不全，别照它删）。
        SettingKey.TRAFFIC_LIMIT to WriteSpec(
            command = "DATA_LIMIT_SETTING",
            encode = { p ->
                val out = LinkedHashMap<String, String>()
                out["data_volume_limit_switch"] = bool01(p["enabled"])
                compoundLimitSize(p["limit_value"], p["limit_unit"])?.let {
                    out["data_volume_limit_size"] = it
                    // ⚠ 这个字段是**限额模式**，不是数据单位：`data` = 按流量限，`time` = 按时长限。
                    // 真实的 MB/GB/TB 由 size 里的乘数表示（"472_1024" = 472 GB）。
                    // 2026-08-30 修：以前发的是 "MB"（把它当成单位字段了），固件不认 → 整条保存失败。
                    // 本项目只做流量限额，故恒发 "data"。
                    out["data_volume_limit_unit"] = "data"
                }
                p["alert_percent"]?.let { out["data_volume_alert_percent"] = it.toString() }
                // 自动清零是 on/off 那一套（与漫游同组），不是 1/0
                p["auto_clear"]?.let { out["wan_auto_clear_flow_data_switch"] = boolOnOff(it) }
                p["clear_date"]?.let { out["traffic_clear_date"] = it.toString().trim() }
                // 不让设备自己弹限额提醒（提醒由本项目发）；官方 UI 也恒发 0
                out["notify_deviceui_enable"] = "0"
                out
            },
            validate = { p -> validateTrafficLimit(p) },
        ),


        // LAN / DHCP（计划书 2.2 的最后一项）。
        // 两个"跟着 dhcp_type 走"的隐含参数必须一起下发，否则设备行为和 UI 不一致：
        //   - dhcp_reboot_flag=1：改 LAN 段后设备要重启 DHCP 服务，不带这个改了不生效；
        //   - mac_ip_reset：开 DHCP 服务器时清掉旧的 MAC-IP 绑定（沿用官方 UI 的行为）。
        SettingKey.LAN_DHCP to WriteSpec(
            command = "DHCP_SETTING",
            encode = { p ->
                val type = dhcpType(p["dhcp_type"])
                mapOf(
                    "lanIp" to strOr(p["lan_ip"], "192.168.0.1"),
                    "lanNetmask" to strOr(p["lan_netmask"], "255.255.255.0"),
                    "lanDhcpType" to type,
                    "dhcpStart" to strOr(p["dhcp_start"], ""),
                    "dhcpEnd" to strOr(p["dhcp_end"], ""),
                    "dhcpLease" to strOr(p["dhcp_lease"], "86400"),
                    "dhcp_reboot_flag" to "1",
                    "mac_ip_reset" to if (type == "SERVER") "1" else "0",
                )
            },
            validate = { p -> validateDhcp(p) },
        ),
        // FOTA 自动升级（计划书 2.7）。
        // 这里是**正向**语义：value=true → UpgMode=1（允许自动升级）。
        // `POST /api/device/fota` 对外的旧字段 `enabled` 是反的（true = 禁用），
        // 那次反转只在 route 的兼容分支里做一次，不在这里再翻一遍。
        // UpgIntervalDay / UpgRoamPermission 是设备侧必填项，取值照抄真机抓包：
        // isTest=false&goformId=SetUpgAutoSetting&UpgMode=0&UpgIntervalDay=1&UpgRoamPermission=0
        SettingKey.FOTA_AUTO_UPDATE to WriteSpec(
            command = "SetUpgAutoSetting",
            encode = { p ->
                mapOf(
                    "UpgMode" to bool01(p["value"]),
                    "UpgIntervalDay" to "1",
                    "UpgRoamPermission" to "0",
                )
            },
        ),
        // 基站锁定（计划书 2.6）：对外收制式名，设备侧要数字 RAT 码。
        // 这个值域此前直接漏在 API 入参上，两个客户端各猜了一套：
        // app 发 "12"/"16"（对的），web 发 cell 列表里的 rat 且兜底 'LTE'（设备不认）。
        SettingKey.CELL_LOCK to WriteSpec(
            command = "CELL_LOCK",
            encode = { p ->
                mapOf(
                    "pci" to (p["pci"]?.toString()?.trim() ?: ""),
                    "earfcn" to (p["earfcn"]?.toString()?.trim() ?: ""),
                    "rat" to ratCode(p["network_type"]),
                )
            },
            validate = { p ->
                when {
                    p["pci"]?.toString()?.trim().isNullOrEmpty() -> "pci 不能为空"
                    p["earfcn"]?.toString()?.trim().isNullOrEmpty() -> "earfcn 不能为空"
                    !p["pci"].toString().trim().matches(NUMERIC) -> "pci 只能是数字"
                    !p["earfcn"].toString().trim().matches(NUMERIC) -> "earfcn 只能是数字"
                    ratCodeOrNull(p["network_type"]) == null ->
                        "network_type 只支持 LTE / NR（或设备侧数字码 12 / 16）"
                    else -> null
                }
            },
        ),
        SettingKey.CELL_UNLOCK to WriteSpec(
            command = "UNLOCK_ALL_CELL",
            encode = { emptyMap() },
        ),
        // SIM 卡槽（计划书 2.6）：对外是 1 起的序号或 "external"，
        // 设备侧的 0/1/2/11 是运营商预置位，映射只在这里。
        SettingKey.SIM_SLOT to WriteSpec(
            command = "SET_SIM_SLOT",
            encode = { p -> mapOf("sim_slot" to (simSlotOrNull(p["value"]) ?: "0")) },
            validate = { p ->
                if (simSlotOrNull(p["value"]) == null) "卡槽只支持 1~3 或 \"external\"" else null
            },
        ),
        // 流量手动校准（计划书 2.6）：对外只有"校准哪个量 + 校准成多少"，
        // 设备侧要 data 与 time 两个字段同时在场（未校准的填 "0"），补零规则在这里。
        SettingKey.FLOW_CALIBRATION to WriteSpec(
            command = "FLOW_CALIBRATION_MANUAL",
            encode = { p ->
                val target = p["target"]?.toString()?.trim()?.lowercase() ?: "data"
                val value = p["value"]?.toString()?.trim().orEmpty().ifEmpty { "0" }
                mapOf(
                    "calibration_way" to target,
                    "time" to (if (target == "time") value else "0"),
                    "data" to (if (target == "data") value else "0"),
                )
            },
            validate = { p ->
                val target = p["target"]?.toString()?.trim()?.lowercase()
                val value = p["value"]?.toString()?.trim()
                when {
                    target != null && target !in setOf("data", "time") -> "校准对象只支持 data / time"
                    value != null && !value.matches(NUMERIC) -> "校准值只能是数字"
                    else -> null
                }
            },
        ),
        // 网络模式 / 承载偏好（计划书 2.6）：对外是 contract 的别名（大小写不敏感），
        // 设备侧只认 BearerPreference 那 6 个**大小写敏感**的值。
        // NetworkMode.toBearer 对 Bearer 取值本身是幂等的，所以已经映射过的调用方不受影响。
        SettingKey.NETWORK_MODE to WriteSpec(
            command = "SET_BEARER_PREFERENCE",
            encode = { p -> mapOf("BearerPreference" to NetworkMode.toBearer(p["value"]?.toString().orEmpty())) },
            validate = { p ->
                val bearer = NetworkMode.toBearer(p["value"]?.toString().orEmpty())
                if (bearer in BEARER_VALUES) null
                else "网络模式 ${p["value"]} 无法映射到设备支持的 BearerPreference"
            },
        ),
    )




    /** 设备侧布尔编码：这一组命令用 `"1"` / `"0"`（另有一组用 `"on"` / `"off"`）。 */
    private fun bool01(v: Any?): String = when (v) {
        is Boolean -> if (v) "1" else "0"
        is String -> if (Decoders.TRUTHY.any { it.equals(v, ignoreCase = true) }) "1" else "0"
        is Number -> if (v.toInt() != 0) "1" else "0"
        else -> "0"
    }

    /** 另一套设备侧布尔编码：漫游/自动清零这组命令只认 `"on"` / `"off"`。 */
    private fun boolOnOff(v: Any?): String = if (bool01(v) == "1") "on" else "off"

    /** 取字符串参数，缺失/空白用 [fallback]。 */
    private fun strOr(v: Any?, fallback: String): String =
        v?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    /** DHCP 模式：设备只认大写 `SERVER` / `DISABLE`，认不出按 `SERVER`（=开启，与 route 旧默认一致）。 */
    private fun dhcpType(v: Any?): String =
        if (v?.toString()?.trim()?.uppercase() == "DISABLE") "DISABLE" else "SERVER"

    private val IPV4 = Regex("^((25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])$")

    /**
     * LAN/DHCP 参数校验。
     *
     * 这几个值会原样进 goform 表单，所以必须挡住 `192.168.0.1&goformId=FACTORY_RESET` 这类注入
     * （4.7 已给 body 做 URL 编码，这里是第二道）。`dhcp_start` / `dhcp_end` 允许为空 —— 关闭
     * DHCP 服务器时官方 UI 就是发空串。
     */
    private fun validateDhcp(p: Map<String, Any?>): String? {
        for (key in listOf("lan_ip", "lan_netmask", "dhcp_start", "dhcp_end")) {
            val s = p[key]?.toString()?.trim().orEmpty()
            if (s.isEmpty()) continue
            if (!s.matches(IPV4)) return "$key 必须是合法 IPv4 地址"
        }
        p["dhcp_lease"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!it.matches(NUMERIC)) return "dhcp_lease 只能是秒数"
        }
        p["dhcp_type"]?.toString()?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let {
            if (it != "SERVER" && it != "DISABLE") return "dhcp_type 只支持 SERVER / DISABLE"
        }
        return null
    }


    /** `"HH:mm"`；空值按调用方的默认值处理（不在这里拒绝）。 */
    private fun validateClockTime(raw: String?): String? {
        val s = raw?.trim() ?: return null
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(s) ?: return "时间格式必须是 HH:mm"
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h > 23 || min > 59) "时间超出范围" else null
    }


    private fun validateBandList(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null // 解锁
        if (!s.matches(Regex("^[0-9]+(,[0-9]+)*$"))) return "频段列表只能是逗号分隔的数字"
        return null
    }

    // ────────────────────── WiFi 接入控制名单 ──────────────────────

    /** `AclMode` 的黑名单档位。抓包里「拉黑」与「取消拉黑」都是 2，只是名单内容不同。 */
    const val ACL_MODE_BLACKLIST = "2"

    private val ACL_MAC_REGEX = Regex("^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$")

    /**
     * 拼设备侧名单串：`"a;b;"` —— 分号分隔且**末尾也带分号**（抓包形态，单项也是 `"mac;"`）。
     * 空列表 → 空串（key 仍然要发，见 [SettingKey.WIFI_ACL] 的说明）。
     */
    private fun aclList(value: Any?): String {
        val items = when (value) {
            null -> emptyList()
            is Collection<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
            // 已经是设备形态的串就原样接受（末尾分号在下面统一补）
            else -> value.toString().split(';').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return if (items.isEmpty()) "" else items.joinToString(";", postfix = ";")
    }

    private fun aclItems(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is Collection<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        else -> value.toString().split(';').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 校验四条名单。
     *
     * MAC 必须是标准 `xx:xx:xx:xx:xx:xx`，名称不许含 `;`（分隔符）与 `&` `=`（设备侧表单是
     * 字符串拼接，见计划书 §9.2）。Mac 与 Name 两条列表长度必须相等 —— 设备按下标配对，
     * 长度不齐会把名字错配到别的 MAC 上。
     */
    private fun validateAcl(p: Map<String, Any?>): String? {
        p["mode"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!it.matches(Regex("^[0-9]$"))) return "AclMode 只能是单个数字"
        }
        for ((macsKey, namesKey) in listOf("black_macs" to "black_names", "white_macs" to "white_names")) {
            val macs = aclItems(p[macsKey])
            val names = aclItems(p[namesKey])
            macs.firstOrNull { !it.matches(ACL_MAC_REGEX) }?.let { return "MAC 地址格式不合法：$it" }
            names.firstOrNull { it.contains(';') || it.contains('&') || it.contains('=') }
                ?.let { return "设备名不能包含 ; & = ：$it" }
            if (macs.size != names.size) return "MAC 列表与名称列表数量必须一致（$macsKey=${macs.size}, $namesKey=${names.size}）"
        }
        return null
    }


    /** MB / GB / TB → 设备侧乘数（1024 进制）。真实单位藏在这个乘数里。 */
    private val LIMIT_UNIT_MULTIPLIER = mapOf("MB" to "1", "GB" to "1024", "TB" to "1048576")

    /** 乘数 → 单位名（读侧用）。与 [LIMIT_UNIT_MULTIPLIER] 同一张表，避免两处各写一份。 */
    private val LIMIT_MULTIPLIER_UNIT = LIMIT_UNIT_MULTIPLIER.entries.associate { (k, v) -> v to k }

    /** 单位名 → 字节数。 */
    private val LIMIT_UNIT_BYTES = mapOf(
        "MB" to 1024L * 1024L,
        "GB" to 1024L * 1024L * 1024L,
        "TB" to 1024L * 1024L * 1024L * 1024L,
    )

    /**
     * 读侧：拆设备的 `data_volume_limit_size` 复合串，派生出 `limit_value` /
     * `limit_unit_display` / `limit_bytes` 三个 canonical 键。
     *
     * 为什么放 `structuralDecoder` 而不是给复合串登记三条 `FieldSpec`：**派生键直接用
     * canonical 名**，于是过渡期 `KEEP_PRESENT` 不会把 `data_volume_limit_size` 当别名再
     * 输出一份 —— 复合串是计划书 2.8 明确要求不出现在响应里的（它曾让 app 的 GB 档流量告警
     * 永不触发）。写侧的反向拼串在 [compoundLimitSize]。
     *
     * 没有下划线时按 MB 处理（少数固件直接给纯数字）；值非正数或解析不出时**不派生
     * `limit_bytes`**，由"缺失 = 省略 key"的语义交给调用方按默认 0 处理。
     */
    private fun splitDataVolumeLimit(raw: JsonObject): JsonObject {
        val compound = (raw["data_volume_limit_size"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (compound.isEmpty()) return raw
        val parts = compound.split('_')
        val value = parts.getOrNull(0)?.trim().orEmpty()
        val unit = LIMIT_MULTIPLIER_UNIT[parts.getOrNull(1)?.trim()]
            ?: if (parts.size < 2) "MB" else "GB"
        val merged = raw.toMutableMap()
        merged[DeviceFields.TrafficLimit.LIMIT_VALUE] = JsonPrimitive(value)
        merged[DeviceFields.TrafficLimit.LIMIT_UNIT_DISPLAY] = JsonPrimitive(unit)
        val num = value.toDoubleOrNull()
        if (num != null && num > 0) {
            val factor = LIMIT_UNIT_BYTES[unit] ?: LIMIT_UNIT_BYTES.getValue("MB")
            // 1024 TB ≈ 1.1e18，仍在 Long 范围内
            merged[DeviceFields.TrafficLimit.LIMIT_BYTES] = JsonPrimitive((num * factor).toLong())
        }
        return JsonObject(merged)
    }


    /**
     * 拼设备侧的 `"数值_乘数"` 复合串，例如 `(470, "GB")` → `"470_1024"`。
     * 数值缺失/非正数时返回 null = 本次不下发限额大小（只改开关等其它项）。
     */
    private fun compoundLimitSize(value: Any?, unit: Any?): String? {
        val n = when (value) {
            null -> return null
            is Number -> value.toLong()
            else -> value.toString().trim().toDoubleOrNull()?.toLong() ?: return null
        }
        if (n <= 0) return null
        val multiplier = LIMIT_UNIT_MULTIPLIER[unit?.toString()?.trim()?.uppercase()] ?: "1024"
        return "${n}_$multiplier"
    }

    private fun validateTrafficLimit(p: Map<String, Any?>): String? {
        p["limit_value"]?.let { v ->
            val n = (v as? Number)?.toDouble() ?: v.toString().trim().toDoubleOrNull()
            if (n == null || n <= 0) return "限额数值必须是正数"
        }
        p["limit_unit"]?.let { u ->
            val key = u.toString().trim().uppercase()
            if (key !in LIMIT_UNIT_MULTIPLIER) return "限额单位只支持 MB / GB / TB"
        }
        p["alert_percent"]?.let { a ->
            val n = a.toString().trim().toIntOrNull()
            if (n == null || n < 0 || n > 100) return "告警百分比必须在 0~100"
        }
        p["clear_date"]?.let { d ->
            val n = d.toString().trim().toIntOrNull()
            if (n == null || n < 1 || n > 31) return "清零日期必须在 1~31"
        }
        return null
    }

    private val NUMERIC = Regex("^[0-9]+$")

    /**
     * 制式名 → goform 的数字 RAT 码。取值域与 [NETWORK_TYPE_MAP] 是同一套编码
     * （12 = LTE，16 = 5G(NR)）。
     *
     * 也接受已经是数字码的入参：app 侧历史上直接发 `"12"` / `"16"`，
     * 兼容期内不能把这些请求判成非法。
     */
    private fun ratCodeOrNull(v: Any?): String? {
        val raw = v?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.matches(NUMERIC)) return raw.takeIf { it in NETWORK_TYPE_MAP.keys }
        return when (raw.uppercase()) {
            "LTE", "4G", "FDD LTE", "TDD LTE" -> "12"
            "NR", "5G", "5G(NR)", "NR-SA", "NR-NSA" -> "16"
            else -> null
        }
    }

    private fun ratCode(v: Any?): String = ratCodeOrNull(v) ?: "12"

    /**
     * 对外的卡槽序号 → 设备侧卡槽值。
     *
     * 设备侧的 0/1/2/11 是运营商预置位（移动/电信/联通/外置），不是物理槽位号；
     * 对外只暴露 1 起的序号与 `"external"`。
     *
     * **不接受设备侧原值**：旧客户端的 `goformSlot` 取值域与新序号重叠
     * （旧的 `"1"` 是电信，新的 `1` 是第一个槽位），在这里放行会把两套语义混在一起，
     * 所以旧值的换算留在 route 的兼容分支（`SimRoutes`）。
     */
    private fun simSlotOrNull(v: Any?): String? = when (v?.toString()?.trim().orEmpty()) {
        "1" -> "0"
        "2" -> "1"
        "3" -> "2"
        "external", "External", "EXTERNAL" -> "11"
        else -> null
    }

    /** 设备认的 BearerPreference 全集（大小写敏感）。 */
    private val BEARER_VALUES = setOf(
        NetworkMode.Bearer.WL_AND_5G,
        NetworkMode.Bearer.ONLY_5G,
        NetworkMode.Bearer.LTE_AND_5G,
        NetworkMode.Bearer.ONLY_LTE,
        NetworkMode.Bearer.ONLY_WCDMA,
        NetworkMode.Bearer.WCDMA_AND_LTE,
    )
}

