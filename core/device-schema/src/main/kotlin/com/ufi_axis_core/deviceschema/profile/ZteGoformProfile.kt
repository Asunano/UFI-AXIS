package com.ufi_axis_core.deviceschema.profile

import com.ufi_axis_core.contract.DeviceFields
import com.ufi_axis_core.contract.NetworkMode

import com.ufi_axis_core.deviceschema.Decoders
import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.FieldNormalizer
import com.ufi_axis_core.deviceschema.FieldSpec
import com.ufi_axis_core.deviceschema.RetryPolicy
import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.deviceschema.Sensitivity
import com.ufi_axis_core.deviceschema.SmsSpec
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
 * ## 新增字段的步骤
 *
 * ```kotlin
 * fieldOf(DeviceFields.DeviceSettings.FOO, FieldGroup.DEVICE_SETTINGS, "设备侧原名"),
 * // 多个固件叫法不同时按优先级列出：
 * fieldOf(DeviceFields.Signal.RSRP, FieldGroup.SIGNAL, "nr_rsrp", "Z5g_rsrp", "lte_rsrp"),
 * // 值需要变换时挂 decode（只做必须做的变换，见 Decoders 的 KDoc）：
 * fieldOf(DeviceFields.LanSettings.MTU, FieldGroup.LAN_SETTINGS, "mtu", decode = Decoders.NUMERIC),
 * ```
 *
 * 随后：① 若属于新分组，把 cmd 名加入 [cmdsFor]；② 在 `DeviceFields.kt` 增加常量；
 * ③ 在 `web/src/api/contract.ts` 增加镜像常量（校验器会比对两侧）；④ 补充一条断言。
 * route 无需改动。
 *
 * ## 硬性约定
 *
 * - `canonical` 必须使用 `DeviceFields.*` 常量，不得写字面量。
 * - 设备侧原名只允许出现在本文件（守门脚本会扫描 route 中的字段字面量）。
 * - 设备没有的字段不登记，归一化会省略该 key；不得编造 source。
 */
object ZteGoformProfile : DeviceProfile {

    override val id: String = "zte-goform"
    override val displayName: String = "ZTE goform（F50 等）"

    /**
     * 网络类型数字码 → 可读文案。
     *
     * 同一字段名在不同命令中有两种编码，必须都支持：状态类命令直接返回文本
     * （`cmd=network_type,...` 返回 `"5G"`），`network_information` 容器内返回数字码。
     * 因此仅对纯数字查表，非数字原样透出；若无条件查表，会把文本形式的 `5G` 误译为「未知(5G)」。
     * 见 [NETWORK_TYPE_DECODER]。
     *
     * ## 该表只保留有依据的项
     *
     * 完整厂商编码表包含 44 项（`8/9` = LTE(FDD)/LTE(TDD)、`12` = LTE、`13` = TDSCDMA、
     * `14/15` = TDD/FDD LTE、`16..22` 为 5G 变体、`40..44` 为另一套编码）。按整表映射时，
     * 真机上 4G/5G 上报的 `13` 被译为 TDSCDMA，App 与 Web 原样显示 ——
     * 客户端按契约不再做二次映射（见 `docs/UFI-AXIS-Core-API-Reference.md` 中关于
     * 数字码→文案映射的说明）。
     *
     * 现仅保留有文档依据的项（`20`→5G、`19`→5G NSA、`13`→4G、`9`→3G、`4`→2G）。
     * 未登记的数字码由 [NETWORK_TYPE_DECODER] 输出 `未知(原值)`，界面可直接看到、响应中可取证；
     * 补充一项只需一次真机对照。把未验证的编码写入表内会再次产生上述误译。
     */
    private val NETWORK_TYPE_MAP: Map<String, String> = mapOf(
        "0" to "无服务",
        "4" to "2G",
        "9" to "3G",
        "13" to "4G",
        "19" to "5G NSA",
        "20" to "5G",
    )

    /**
     * 非数字的 RAT 别名 → 可读文案。
     *
     * 设备侧会把 NSA 组网报成 `ENDC` / `EN-DC` / `NSA`（E-UTRAN New Radio Dual
     * Connectivity）：它是 LTE 锚点 + 5G NR 的双连接，不是独立制式。App 在
     * `HomeConnectionCard.mapRat` 中已做这层映射，但 Web 读取的是 core 的 `network_type`
     * 原值，页面上会直接显示 "ENDC"。此处把同一张表下沉到 core，App 与 Web 不必各自再译。
     *
     * 只做别名归一化，不做数字码→文案（后者是 [NETWORK_TYPE_MAP] 的职责）。表内没有的
     * 值原样返回：不猜测、不丢弃，界面可直接看到，补充一项只需一次真机对照。
     */
    private fun normalizeRatLabel(raw: String): String = when (raw.trim().lowercase()) {
        "nr", "5g", "sa" -> "5G"
        // NSA 双连接：EN-DC / ENDC / NSA 都是 LTE 锚点 + NR，统一显示 "5G NSA"
        "nsa", "endc", "en-dc", "en_dc", "nr-nsa", "nr_nsa" -> "5G NSA"
        "lte", "4g" -> "4G"
        "wcdma", "umts", "hspa", "hsdpa" -> "3G"
        "gprs", "edge", "2g" -> "2G"
        else -> raw.trim()
    }

    /**
     * `network_type` 的解码器：
     * 空 → 视为字段缺失（省略 key）· 纯数字 → 查表，查不到给 `未知(原值)`
     * · 非数字 → RAT 别名归一化（未知值原样透出，见 [normalizeRatLabel]）。
     *
     * 空值此处返回 null（而不是 `"未知"`）：空字符串必须视为字段不存在，
     * 否则前端会出现凭空产生的「未知」。
     */
    private val NETWORK_TYPE_DECODER: (JsonElement) -> JsonElement? = Decoders.ofString { raw ->
        val s = raw.trim()
        when {
            s.isEmpty() -> null
            s.all { it.isDigit() } -> NETWORK_TYPE_MAP[s] ?: "未知($s)"
            else -> normalizeRatLabel(s)
        }
    }

    /**
     * WiFi 密码解码器：base64 解码后优先按 UTF-8 解析，UTF-8 非法时才回落 GBK。
     *
     * 放在此处而不留在 `DataHub`：编码方式属于设备特性，换一款设备即换一套。
     * 无法解码或解出空串时返回 null（= 省略 key）：客户端两侧均以 `?? ''` / `?: ""` 兜底空值，
     * 省略更符合「缺失即省略」的契约。
     *
     * ## 为什么以 UTF-8 优先
     *
     * 写侧使用 UTF-8（[base64Utf8] 与 `GoformClient.base64Encode`），若读侧只用 GBK，
     * 该不对称会在「读回当前口令 → 原样 base64 编码写回」这条路径上损坏非 ASCII 口令
     * （`GoformWifiClient.setWifiSSID` / `setWifiConfig` 走的就是这条路径）。四种情形：
     * - ASCII 口令：两种字符集结果一致，无影响；
     * - 设备存 UTF-8：解码正确（若按 GBK 解会得到乱码）；
     * - 设备存 GBK（旧固件）：UTF-8 解码非法 → 回落 GBK，结果与仅按 GBK 解码时一致；
     * - 两者都无法解出可读文本：仍走 GBK 分支（GBK 解码使用替换字符，不抛异常），语义不变。
     *
     * ## 判据是「能否无损往返」，不是「是否含替换字符」
     *
     * 判据为 `String(bytes, UTF_8).toByteArray(UTF_8) == bytes`：合法 UTF-8 往返后字节完全相同，
     * 非法序列会被替换为 U+FFFD（编码回去是 `EF BF BD`），从而不相等。
     * 以「结果中是否含 U+FFFD」为判据是错误的 —— 口令本身可以包含该字符，
     * 那样会把合法口令误判为非法。
     *
     * GBK 双字节序列偶有可能恰好构成合法 UTF-8 序列，这是刻意接受的权衡：写侧为 UTF-8，
     * 因此「读回再写回无损」优先于兼容一个尚未观测到的边缘固件。
     *
     * ⚠ 另一份同类实现位于 `GoformClient.base64Decode`（读侧），两者的判据必须一致。
     * 判据不一致时，`/api/wifi/settings` 读出的口令与写回的口令会不同，
     * 且没有任何测试能同时观察两侧。
     */
    private val WIFI_PASSWORD_DECODER: (JsonElement) -> JsonElement? = Decoders.ofString { raw ->
        if (raw.isBlank()) return@ofString null
        val bytes = try {
            java.util.Base64.getDecoder().decode(raw)
        } catch (_: Exception) {
            // 不是合法 base64：保持现有语义，返回 null（省略 key）。
            // 不得改为返回空串 —— 空串会被下游视为「口令为空」，语义不同。
            return@ofString null
        }
        decodeWifiPassword(bytes).takeIf { it.isNotBlank() }
    }

    /**
     * base64 解出的字节 → 明文口令。UTF-8 能无损往返就用 UTF-8，否则按 GBK 解（老固件）。
     *
     * 判据与权衡见 [WIFI_PASSWORD_DECODER] 的 KDoc。
     */
    private fun decodeWifiPassword(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (utf8.toByteArray(Charsets.UTF_8).contentEquals(bytes)) return utf8
        return String(bytes, GBK)
    }

    /** 老固件把中文口令按 GBK 存（[WIFI_PASSWORD_DECODER] 的回落分支）。 */
    private val GBK: java.nio.charset.Charset = java.nio.charset.Charset.forName("GBK")

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
        // 单位是小时，客户端读取后自行乘 3600。不在此处换算：web 与 app 已固化该语义，
        // 归一化时换算会导致它们再次乘 3600，形成双重换算。
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
        // 在归一化前拆成下面三条，派生键直接使用 canonical 名 —— 这样 KEEP_PRESENT
        // 也不会把复合串当「别名」再透出一份（复合串不进入对外契约）。
        // data_volume_limit_unit 是限额模式（"data" 按流量 / "time" 按时长），
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
        // ⚠️ 上下行**故意交叉绑定**：ZTE 固件的 monthly_rx/tx 按「模块看 PC」的视角命名，
        // 与 canonical（rx = 下行/下载、tx = 上行/上传）相反。同一台 F50 的实测值：
        //   monthly_rx_bytes = 985,757,259（≈940 MB，实际为上传）
        //   monthly_tx_bytes = 7,530,676,294（≈7.0 GB，实际为下载）
        // 因此在唯一的适配层一次性纠正，上层（TrafficLimitMapper / 流量管理 UI / web）
        // 拿到的 canonical rx 即下载、tx 即上传，无需再各自记住「这台设备是反的」。
        // 注意：诊断端点 /api/device/goform 走 getFullStatusMasked()，不经过本表，
        // 该 dump 保持设备原样（原名 + 原值），排障时才能与设备后台对照。
        add(fieldOf(DeviceFields.TrafficLimit.MONTHLY_RX_BYTES, FieldGroup.TRAFFIC_LIMIT,
            "monthly_tx_bytes", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.TrafficLimit.MONTHLY_TX_BYTES, FieldGroup.TRAFFIC_LIMIT,
            "monthly_rx_bytes", decode = Decoders.NON_BLANK))
        add(fieldOf(DeviceFields.TrafficLimit.MONTHLY_TIME, FieldGroup.TRAFFIC_LIMIT,
            "monthly_time", decode = Decoders.NON_BLANK))

        // ───────── 设备身份（GoformSignalClient.getDeviceIdentity / getDeviceVersion） ─────────
        // PII：手机号 / IMEI / IMSI / ICCID 标 MASKED —— 正常响应给真值，日志与诊断端点打码。
        // `sim_msisdn` 与 `msisdn` 是同一号码的两个设备字段名（原始 dump 中两者都会出现），
        // 都登记才能让诊断脱敏按名字命中 —— 否则 dump 中的 `sim_msisdn` 会漏出真值。
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
        // canonical 即 goform 扁平名本身（web 的 normalizeWifiSettings 与 app 的
        // parseWifiSettings 双侧固化），因此此处 canonical 与首个扁平 source 相同。
        //
        // 每条别名链的第一项是 ZTE 原生驼峰名（`SSID` / `Password` / `AuthMode`…）：
        // 它们由 [liftActiveAccessPoint] 从 `queryAccessPointInfo` 的 `ResponseList` 提到顶层。
        // 置于最前是为了保持与原 `DataHub.processModuleInfo` 相同的优先级 ——
        // 它在扁平字段之后执行并覆盖，即 module-info 优先于扁平查询。
        add(fieldOf(DeviceFields.WifiSettings.CHIP, FieldGroup.WIFI_SETTINGS,
            "ChipIndex", "wifi_chip", "chip", decode = WIFI_CHIP_DECODER))
        add(fieldOf(DeviceFields.WifiSettings.SSID, FieldGroup.WIFI_SETTINGS,
            "SSID", "wifi_chip1_ssid1_ssid", "wifi_chip1_ssid", decode = Decoders.NON_BLANK))
        // WiFi 明文密码（core 已做 base64 解码，UTF-8 优先、GBK 回落）。MASKED 而不是 SECRET：web 的二维码功能需要真值。
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
        // ⚠️ LTE_SNR 的别名链第一项 `lte_snr` 不是设备原名，而是 canonical 自身
        // （`DeviceFields.CellInfo.LTE_SNR` == "lte_snr"）。设备原名只有大写 `Lte_snr`
        // （真机 dump：4G 驻网返回 `Lte_snr`，小区参数首字母大写、
        // 信号质量指标 `lte_rsrp`/`lte_rsrq`/`lte_rssi` 全小写）。
        // 保留它仅用于读侧容错（归一化过的输入再次进入也能命中），
        // 不得把它加入任何 cmd 列表 —— 否则会发出一个设备上不存在的 cmd。
        add(fieldOf(DeviceFields.CellInfo.LTE_SNR, FieldGroup.CELL_INFO, "lte_snr", "Lte_snr"))

        // ───────── 信号（FieldGroup.SIGNAL） ─────────
        // 与 SignalCollector.collectLayer1GoformFields 的取值保持一致。
        // 两个要点：
        //  ① 这批 canonical 是 core 自有的小写归一名，同时被 WS `signal` 频道和 REST 使用，
        //     一个都不能改（`lte_snr` 看似设备原名，设备原名实为 `Lte_snr`）。
        //  ② goform 命名极不一致，必须逐个核对固件原文：频点 LTE 是 `Lte_fcn`、
        //     带宽 LTE 是 `Lte_bands_widths`（多一个 s），而 NR 侧是 `Nr_fcn` / `Nr_band_widths`。
        //     多数字段位于 `network_information` 子对象内，由 [structuralDecoder] 提前展平。

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

        // 与 GoformWifiClient.getWifiSettingsMerged 一致，顺序按其实际发送顺序排列：
        // 前 12 个是 `getWifiSettings()` 的扁平字段查询，后 2 个是 `getWifiModuleInfo()`
        // 的 module-info（`queryAccessPointInfo` 是容器命令，返回 `ResponseList` 数组，
        // 由 structuralDecoder(WIFI_SETTINGS) = liftActiveAccessPoint 挑出生效 AP 后提到顶层）。
        // 顺序与客户端一致，便于比对。
        //
        // **`WiFiModuleSwitch` 不能出现在此处**：它是设备的响应键，是 MODULE_SWITCH 别名链的首项
        // （见上面 readSpecs），由 `queryWiFiModuleSwitch` 这条容器命令返回 —— 它本身不是可发送的
        // cmd 名，发送只会产生一次无效查询。应当发送的是链上另外两个扁平 cmd
        // （`wifi_enable` / `wifi_onoff_state`），缺少它们会使覆盖率报告中
        // `module_switch` 始终为 missing（诊断误报，不是功能缺陷 —— 线上走的是客户端那 12+2 项）。
        FieldGroup.WIFI_SETTINGS -> listOf(
            "wifi_chip1_ssid1_ssid", "wifi_onoff_state", "wifi_access_sta_num",
            "wifi_chip1_ssid1_access_sta_num", "wifi_5g_enable", "wifi_enable",
            "wifi_chip1_ssid1_passphrase", "wifi_chip",
            "wifi_chip1_ssid1_auth_mode", "wifi_chip1_ssid1_encryp_type",
            "wifi_chip1_ssid1_max_sta_num", "wifi_chip1_ssid1_broadcast_ssid",
            "queryWiFiModuleSwitch", "queryAccessPointInfo",
        )

        // station_list 必须单独查（与其它 cmd 组合时设备返回空，见 GoformWifiClient 的相应注释）
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

    /**
     * 全量状态 dump 的三批查询（与 `GoformSignalClient.getFullStatus()` 一致）。
     *
     * 三批的内容与顺序不得改动：这是 F50 上实际发出的三次请求，
     * 改内容即改变设备侧请求形状，改分批即假设设备能一次接收 96 项（合并查询已被设备丢弃过）。
     * 实测数量为 30 / 29 / 37 = 96。
     *
     * 这份 dump 不经过归一化（调用点为 `getFullStatus()` / `getFullStatusMasked()`，
     * 供诊断端点查看设备后台的全部字段），因此其中相当一部分字段名没有登记 canonical ——
     * 这是刻意的，不能用 `readSpecs()` 去核对它。
     */
    override fun fullStatusCmds(): List<List<String>> = FULL_STATUS_CMD_BATCHES

    /**
     * [fullStatusCmds] 的实际取值。外层三个元素 = 三次独立的 goform 查询，顺序即发送顺序
     * （调用点按顺序 `putAll`，因此后一批的同名键会覆盖前一批）。
     *
     * 批内分行方式与原客户端代码保持一致，便于逐字比对；`GoformCommandTableGuardTest`
     * 将三批的数量（30 / 29 / 37）与内容整体冻结。
     */
    private val FULL_STATUS_CMD_BATCHES: List<List<String>> = listOf(
        // Batch 1: 设备身份 + 网络基础 (30 字段)
        listOf(
            "network_signalbar", "network_rssi", "network_type", "network_provider",
            "ppp_status", "lan_ipaddr", "mac_address", "imei", "imsi", "iccid",
            "wifi_onoff_state", "wifi_access_sta_num", "cr_version",
            "msisdn", "sim_msisdn", "sim_imsi", "ipv6_wan_ipaddr",
            "hardware_version", "web_version", "wa_version", "wa_inner_version",
            "LocalDomain", "wan_ipaddr", "static_wan_ipaddr",
            "pdp_type", "pdp_type_ui", "ipv6_pdp_type", "ipv6_pdp_type_ui",
            "opms_wan_mode", "opms_wan_auto_mode"
        ),
        // Batch 2: 流量/电池/短信/设置 (29 字段)
        listOf(
            "realtime_tx_bytes", "realtime_rx_bytes", "monthly_tx_bytes", "monthly_rx_bytes",
            "realtime_time", "monthly_time", "realtime_rx_thrpt", "realtime_tx_thrpt",
            "battery_value", "battery_vol_percent", "battery_charging",
            "sms_received_flag", "sms_unread_num", "sms_sim_unread_num",
            "data_volume_limit_switch", "data_volume_alert_percent", "data_volume_limit_size",
            "loginfo", "pin_status", "simcard_roam", "usb_port_switch",
            "wifi_chip1_ssid1_ssid", "wifi_5g_enable", "roam_setting_option",
            "Lte_ca_status", "new_version_state", "current_upgrade_state",
            "sim_slot", "dual_sim_support"
        ),
        // Batch 3: WiFi 芯片 / CA / 信号 / APN 补充 (37 字段)
        listOf(
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
        ),
    )

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
     * 把 `queryAccessPointInfo` 返回的 `ResponseList` 中生效 AP 的字段提到顶层。
     *
     * 该变换取代原先集中在 DataHub 的字段重命名逻辑：把重命名留在 DataHub 会使同一份映射表
     * 存在两个维护点。此处只做三项结构变换：
     *  1. 带谓词的元素选择：取 `AccessPointSwitchStatus == "1"` 的 AP，找不到时退回第一个；
     *  2. 把该 AP 的 `SSID` / `Password` / `AuthMode` / `EncrypType` / `ChipIndex` /
     *     `ApMaxStationNumber` / `ApBroadcastDisabled` 按原名提到顶层；
     *  3. 密码补位：生效 AP 的 `Password` 为空时，取所有 AP 中第一个非空值
     *     （固件在双频共享密码时只在其中一个 AP 上填值）。
     *
     * 重命名与值解码都不在此处：提到顶层的仍是 ZTE 原名，由 [SPECS] 的别名链处理，
     * base64 解码由 [WIFI_PASSWORD_DECODER] 负责。allowlist 因此仍是唯一出口。
     *
     * 顶层同名键会被 AP 值覆盖。ZTE 原生驼峰名与 goform 扁平名不重名，实际不会冲突；
     * 真正的优先级由别名链顺序（原名在前）表达。
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
     * 客户端列表：统一为真正的 JSON 数组，并归一元素键。
     *
     * 设备对 `station_list` / `lan_station_list` 有两种形态：JSON 数组，或数组的 JSON 字符串
     * （双重编码，取决于固件）。客户端两侧对此各自兜底（app 的 `parseArrayField`、
     * web 的 `data?.stationList || ...`）；core 统一输出真正的数组，
     * 该形态是两端已支持形态的子集，不会破坏现有消费点。
     *
     * 元素键也在此对齐（`mac` → `mac_addr`），这是 web 侧 `c.mac_addr ?? c.mac` 判定的来源。
     */
    private fun normalizeStationLists(raw: JsonObject): JsonObject =
        normalizeArrayFields(raw, STATION_LIST_KEYS, STATION_ITEM_ALIASES)

    /**
     * 小区列表：处理方式与 [normalizeStationLists] 相同（真正的数组 + 元素键归一）。
     *
     * `neighbor_cell_info` / `locked_cell_info` 同样存在「数组或数组字符串」两种形态，
     * web 的 `parseCellArray()` 即为此而设。元素键别名见 [CELL_ITEM_ALIASES]。
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
    // 派生规则与设备无关（输入是已归一化的 canonical 字段），因此放在
    // com.ufi_axis_core.deviceschema.ServingCell 而非本文件，避免每个新 profile 重复实现。

    // ───────────────── WiFi 连接二维码的文件名（读侧） ─────────────────

    /**
     * 二维码图片文件名的公共后缀。
     *
     * 整个名字的形状是 `<频段>_ssid<序号>` + 本后缀，由 goform 侧拼进
     * `/goform/goform_get_file_process/<名字>`（路径前缀在传输层，不在本文件）。
     */
    private const val QR_CODE_FILE_SUFFIX = "_qrcode_wifikey"

    /**
     * 「已知一定会生成」的那一张二维码对应的频段与 SSID 序号，用作第二候选。
     *
     * 取值与 [WIFI_CHIP_FALLBACK] 的 `chip1` 相同，但两者不是同一件事，刻意不合并为一个常量：
     * 后者是写侧「开 WiFi 时读不到当前频段的兜底」，本常量是读侧「取二维码图失败时退回哪一张」。
     * 合并会使两条互不相关的判据被同一次修改牵动。
     */
    private const val QR_CODE_FALLBACK_STEM = "chip1_ssid1"

    /**
     * WiFi 二维码图片的文件名候选。
     *
     * 两个候选及其顺序与 `GoformWifiClient.getWifiQrCode` 一致：
     * 1. `<chip>_ssid<ssidIndex>` + [QR_CODE_FILE_SUFFIX]：调用方请求的那一张；
     * 2. [QR_CODE_FALLBACK_STEM] + [QR_CODE_FILE_SUFFIX]：兜底。部分固件只生成 2.4G 的那一张
     *    （`chip2_ssid1_…` 直接返回 404），此时返回一张可用的图片优于不显示：
     *    二维码内容为 SSID 与口令，两个频段通常使用同一口令。
     *
     * `chip == "chip1" && ssidIndex == 1` 时两个候选是同一个名字，必须去重（此处用
     * `linkedSetOf` 保序去重）。契约规定调用方按返回顺序原样逐个发请求、不做任何过滤，
     * 列表中的重复项会导致对同一文件重复发起 HTTP 请求。
     *
     * 此处刻意不校验取值域：`chip` 不在 `{chip1, chip2}` 内时仍会拼出一个名字。
     * 该项校验位于 `WifiRoutes`（`chip must be chip1 or chip2`）；在此再加一道会把
     * 「取不到那张图」变成「返回空列表 = 该设备不支持取二维码」，语义即发生改变。
     */
    override fun qrCodeFileNames(chip: String, ssidIndex: Int): List<String> = linkedSetOf(
        "${chip}_ssid${ssidIndex}$QR_CODE_FILE_SUFFIX",
        "$QR_CODE_FALLBACK_STEM$QR_CODE_FILE_SUFFIX",
    ).toList()

    // ───────────────────────── 写入侧 ─────────────────────────
    // 已登记的写命令由 GoformDeviceClient / GoformWifiClient / GoformNetworkClient
    // 迁入（cmd 名与参数键逐字符一致），未登记的返回 null。
    //
    // retry 必须逐项显式写出（[WriteSpec.retry] 的默认值是安全侧的 NEVER）：
    // 下列设置类命令原本走 GoformSettingWriter 的 goformPostIdempotent，
    // 漏标一项会使「切换网络制式首次必定失败、再次触发才成功」的缺陷复发
    // （见 GoformSettingWriter 的类注释）。动作类与修改口令的命令则必须是 NEVER。

    override fun writeSpec(key: SettingKey): WriteSpec? = WRITE_SPECS[key]

    /**
     * 「解锁全部频段」时下发的频段全集。
     *
     * 取值与 GoformNetworkClient 的 companion 常量一致（ZTE MU300 全 LTE 频段，解锁时使用），
     * 频段号未作调整。该值由 profile 提供的目的是消除「同一串值存在三份拷贝，且 `core/controller`
     * 跨模块直接读取」的情况，不是重新核对频段表。
     *
     * 该值不是 `BAND_LOCK_LTE` 的 encode 的一部分：`encode` 仍只做
     * `value → lte_band_lock` 的字段名映射，空串仍被 `validateBandList` 视为「解锁」
     * （= 不发限制）。把全集放进 encode 会使空串的语义从「不发限制」变为「下发全频段」，
     * 属于对外语义变更。
     */
    override fun lteAllBandsMask(): String = "1,3,5,8,34,38,39,40,41"

    /** 全 NR 频段（解锁时使用）。取值与迁入理由同 [lteAllBandsMask]。 */
    override fun nrAllBandsMask(): String = "1,5,8,28,41,78"

    /**
     * ACL 默认档位 = [ACL_MODE_BLACKLIST]（`"2"`，黑名单）。
     *
     * 依据来自抓包：F50 的「拉黑」与「取消拉黑」下发的都是 `AclMode=2`，区别只在名单内容；
     * 读回时设备偶尔不带 `AclMode` 键，此时按 2 理解与设备实际行为一致。
     *
     * 该取值由本方法提供而非由客户端引用常量，使更换 profile 即可更换默认档位。
     */
    override fun aclDefaultMode(): String = ACL_MODE_BLACKLIST

    /**
     * 短信规则由独立的 [ZteSmsSpec] 提供（短信规则自成一体，与字段映射相互独立）。
     *
     * 短信不纳入 [WRITE_SPECS] 的原因：`sms_time` 使 encode 不纯、下发后还需回读确认、
     * 编码为短信专有，三条理由见 [DeviceProfile.smsSpec] 的 KDoc。
     */
    override fun smsSpec(): SmsSpec? = ZteSmsSpec


    private val WRITE_SPECS: Map<SettingKey, WriteSpec> = mapOf(
        SettingKey.LED to WriteSpec(
            command = "INDICATOR_LIGHT_SETTING",
            encode = { p -> mapOf("indicator_light_switch" to bool01(p["value"])) },
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        SettingKey.PERFORMANCE_MODE to WriteSpec(
            command = "PERFORMANCE_MODE_SETTING",
            encode = { p -> mapOf("performance_mode" to bool01(p["value"])) },
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        SettingKey.WIFI_SLEEP_IDLE_MINUTES to WriteSpec(
            command = "SET_WIFI_SLEEP_INFO",
            encode = { p -> mapOf("sleep_sysIdleTimeToSleep" to (p["value"]?.toString() ?: "0")) },
            validate = { p ->
                val v = (p["value"] as? Int) ?: p["value"]?.toString()?.toIntOrNull()
                if (v == null || v < 0) "休眠时间必须是非负整数（分钟）" else null
            },
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        // WiFi 接入控制名单（拉黑）。参数取自真机抓包：
        //   goformId=setDeviceAccessControlList&AclMode=2&WhiteMacList=&BlackMacList=aa:bb:cc:dd:ee:ff;
        //   &WhiteNameList=&BlackNameList=Example-Device;
        // 三项设备事实：
        //   ① 四条名单每次都要发全（空也要带 key），设备为整表替换，漏发即清空；
        //   ② 列表以 `;` 分隔且末尾也带 `;`（单项同样为 "mac;"）；
        //   ③ Mac 列表与 Name 列表按下标一一对应，长度必须相等。
        // AclMode：2 = 黑名单生效（抓包中「取消拉黑」同样为 2，只是名单清空）。
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
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        // 频段锁定：值为逗号分隔的频段号；空串 = 解锁。
        // validate 拦截非数字字符 —— 设备侧表单为字符串拼接且未做转义，
        // 值中带 & 或 = 会注入额外参数。
        SettingKey.BAND_LOCK_LTE to WriteSpec(
            command = "LTE_BAND_LOCK",
            encode = { p -> mapOf("lte_band_lock" to (p["value"]?.toString() ?: "")) },
            validate = { p -> validateBandList(p["value"]?.toString()) },
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        SettingKey.BAND_LOCK_NR to WriteSpec(
            command = "NR_BAND_LOCK",
            encode = { p -> mapOf("nr_band_lock" to (p["value"]?.toString() ?: "")) },
            validate = { p -> validateBandList(p["value"]?.toString()) },
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
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
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        SettingKey.SAMBA to WriteSpec(
            command = "SAMBA_SETTING",
            encode = { p -> mapOf("samba_switch" to bool01(p["value"])) },
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        SettingKey.USB_PORT to WriteSpec(
            command = "USB_PORT_SETTING",
            encode = { p -> mapOf("usb_port_switch" to bool01(p["value"])) },
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        // 漫游使用另一套布尔编码：同一设备上，本命令要求 "on"/"off"，
        // 而上面若干命令要求 "1"/"0"。编码混用后设备会静默忽略（返回 200 但值未变），
        // 因此编码规则必须逐命令登记。此外本项复用 SET_CONNECTION_MODE 且必须带 ConnectionMode=auto_dial。
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
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        // 流量限额：唯一带可选参数的写操作。
        // 复合串 `"470_1024"` 只在此处拼接 —— 对外只有 limit_value 与 limit_unit
        // 两个结构化参数（复合格式属于设备侧事实，不是对外契约）。
        //
        // 参数集取自真机抓包（设备自带 UI，流量管理页保存）：
        //   isTest=false&goformId=DATA_LIMIT_SETTING
        //   &data_volume_limit_unit=data&data_volume_limit_size=472_1024
        //   &data_volume_alert_percent=90&wan_auto_clear_flow_data_switch=on
        //   &traffic_clear_date=2&data_volume_limit_switch=1&notify_deviceui_enable=0&AD=…
        // 即：自动清零与清零日同属这条命令，不得省略。
        SettingKey.TRAFFIC_LIMIT to WriteSpec(
            command = "DATA_LIMIT_SETTING",
            encode = { p ->
                val out = LinkedHashMap<String, String>()
                out["data_volume_limit_switch"] = bool01(p["enabled"])
                compoundLimitSize(p["limit_value"], p["limit_unit"])?.let {
                    out["data_volume_limit_size"] = it
                    // ⚠ 该字段是限额模式，不是数据单位：`data` = 按流量限，`time` = 按时长限。
                    // 真实的 MB/GB/TB 由 size 中的乘数表示（"472_1024" = 472 GB）。
                    // 若把它当作单位字段发送（例如 "MB"），固件不识别，整条保存失败。
                    // 本项目只做流量限额，故恒发 "data"。
                    out["data_volume_limit_unit"] = "data"
                }
                p["alert_percent"]?.let { out["data_volume_alert_percent"] = it.toString() }
                // 自动清零是 on/off 那一套（与漫游同组），不是 1/0
                p["auto_clear"]?.let { out["wan_auto_clear_flow_data_switch"] = boolOnOff(it) }
                p["clear_date"]?.let { out["traffic_clear_date"] = it.toString().trim() }
                // 不允许设备自身弹出限额提醒（提醒由本项目发送）；官方 UI 同样恒发 0
                out["notify_deviceui_enable"] = "0"
                out
            },
            validate = { p -> validateTrafficLimit(p) },
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),


        // LAN / DHCP。
        // 两个随 dhcp_type 变化的隐含参数必须一起下发，否则设备行为与 UI 不一致：
        //   - dhcp_reboot_flag=1：修改 LAN 段后设备需要重启 DHCP 服务，不带该字段修改不生效；
        //   - mac_ip_reset：开启 DHCP 服务器时清除旧的 MAC-IP 绑定（与官方 UI 行为一致）。
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
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        // FOTA 自动升级。
        // 此处为正向语义：value=true → UpgMode=1（允许自动升级）。
        // 对外 `POST /api/device/fota` 的旧字段 `enabled` 是反向的（true = 禁用），
        // 该反转只在 route 的兼容分支中执行一次，不在此处再次翻转。
        // UpgIntervalDay / UpgRoamPermission 是设备侧必填项，取值取自真机抓包：
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
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        // 基站锁定：对外接收制式名，设备侧要求数字 RAT 码。
        // 该值域必须由 profile 统一：app 发送 "12"/"16"（已是设备码），
        // web 发送 cell 列表中的 rat 并以 'LTE' 兜底（设备不识别）。
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
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        SettingKey.CELL_UNLOCK to WriteSpec(
            command = "UNLOCK_ALL_CELL",
            encode = { emptyMap() },
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        // SIM 卡槽：对外为从 1 开始的序号或 "external"，
        // 设备侧的 0/1/2/11 是运营商预置位，映射仅在此处。
        SettingKey.SIM_SLOT to WriteSpec(
            command = "SET_SIM_SLOT",
            encode = { p -> mapOf("sim_slot" to (simSlotOrNull(p["value"]) ?: "0")) },
            validate = { p ->
                if (simSlotOrNull(p["value"]) == null) "卡槽只支持 1~3 或 \"external\"" else null
            },
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        // 流量手动校准：对外只有「校准哪个量 + 校准成多少」，
        // 设备侧要求 data 与 time 两个字段同时在场（未校准的一项填 "0"），补零规则在此处。
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
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        // 网络模式 / 承载偏好：对外是 contract 的别名（大小写不敏感），
        // 设备侧只认 BearerPreference 的 6 个大小写敏感取值。
        // NetworkMode.toBearer 对 Bearer 取值本身幂等，已映射过的调用方不受影响。
        SettingKey.NETWORK_MODE to WriteSpec(
            command = "SET_BEARER_PREFERENCE",
            encode = { p -> mapOf("BearerPreference" to NetworkMode.toBearer(p["value"]?.toString().orEmpty())) },
            validate = { p ->
                val bearer = NetworkMode.toBearer(p["value"]?.toString().orEmpty())
                if (bearer in BEARER_VALUES) null
                else "网络模式 ${p["value"]} 无法映射到设备支持的 BearerPreference"
            },
            // 该项是「切换网络制式首次必定失败」缺陷的对应项，不能是 NEVER
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),

        // ───────── 动作类与设置类写命令 ─────────

        // 三个动作类命令：设备侧均为无参，只有 goformId。
        // retry = NEVER 的依据：重启 / 关机 / 恢复出厂重发一次的后果分别是再次重启、
        // 向已经断电的设备重复发送，以及在出厂口令下执行第二次擦除。
        SettingKey.REBOOT to WriteSpec(
            command = "REBOOT_DEVICE",
            encode = { emptyMap() },
            retry = RetryPolicy.NEVER,
        ),
        SettingKey.FACTORY_RESET to WriteSpec(
            command = "FACTORY_RESET",
            encode = { emptyMap() },
            retry = RetryPolicy.NEVER,
        ),
        SettingKey.SHUTDOWN to WriteSpec(
            command = "SHUTDOWN_DEVICE",
            encode = { emptyMap() },
            retry = RetryPolicy.NEVER,
        ),
        // 后台口令修改。
        // **哈希不在此处执行**：设备要求的 SHA256 大写十六进制与登录握手共用 GoformClient.sha256Hex，
        // 在 profile 中复制第二份实现会产生两个真源（登录侧更换算法时此处不报错，只会静默登录失败）；
        // 明文口令不进入 device-schema 亦可减少泄露面。因此 encode 只做字段名映射。
        // validate 拦截「未哈希直接传明文」—— 否则明文口令会被原样发送给设备，属于安全问题而非格式问题。
        // 校验文案不得包含参数值（该文案会进入 GoformSettingWriter 的 warn 日志）。
        // 调用点的副作用（updateGoformPassword() 与 resetLogin()）无法由 WriteSpec 表达。
        SettingKey.BACKEND_PASSWORD to WriteSpec(
            command = "CHANGE_PASSWORD",
            encode = { p ->
                mapOf(
                    "oldPassword" to (p["old_hash"]?.toString()?.trim() ?: ""),
                    "newPassword" to (p["new_hash"]?.toString()?.trim() ?: ""),
                )
            },
            validate = { p ->
                when {
                    !isSha256UpperHex(p["old_hash"]) -> "旧口令必须是调用方算好的 SHA256 大写十六进制"
                    !isSha256UpperHex(p["new_hash"]) -> "新口令必须是调用方算好的 SHA256 大写十六进制"
                    else -> null
                }
            },
            // 改完口令旧会话必然失效，重试只会拿着旧口令再登一次（必然失败），还会多发一次改密请求
            retry = RetryPolicy.NEVER,
        ),
        // 连接模式：与 ROAM 共用 SET_CONNECTION_MODE（设备侧把漫游开关放在该命令中），
        // 但参数集不同 —— 此处只发 ConnectionMode，不带 roam_setting_option。
        // 取值域与 NetworkRoutes 的归一结果一致（auto_dial / manual_dial），
        // 设备大小写敏感，因此不做大小写兼容，无法识别时直接拒绝（同时阻挡表单注入）。
        SettingKey.CONNECTION_MODE to WriteSpec(
            command = "SET_CONNECTION_MODE",
            encode = { p -> mapOf("ConnectionMode" to (p["value"]?.toString()?.trim() ?: "")) },
            validate = { p ->
                val v = p["value"]?.toString()?.trim()
                if (v in CONNECTION_MODES) null else "连接模式只支持 auto_dial / manual_dial"
            },
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        // WiFi 发射功率。0~2 的取值域与 WifiRoutes 的入参校验（level must be 0-2）一致，
        // 在此登记使「档位上限」这项设备事实只有一份。
        SettingKey.WIFI_POWER to WriteSpec(
            command = "SET_WIFI_POWER",
            encode = { p -> mapOf("wifiPowerLevel" to (p["value"]?.toString()?.trim() ?: "0")) },
            validate = { p ->
                val v = (p["value"] as? Int) ?: p["value"]?.toString()?.trim()?.toIntOrNull()
                if (v == null || v !in 0..2) "WiFi 功率档位只支持 0~2" else null
            },
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        // ───── 开与关分属两条命令的写入项（WriteSpec.commandOf）─────
        //
        // 下列三项（WIFI_ENABLED / MOBILE_DATA / PPP_DIAL）的共同点：一个用户动作（开 / 关），
        // 设备侧却是两条不同的 goformId。命令选择写在 commandOf 中，调用点只传一个布尔值；
        // 若没有该字段，「开发 A 命令、关发 B 命令」这段设备知识会以 if 的形式漏在客户端。
        // （WIFI_BAND 不属于这一类：它只有一条命令，列在此处是因为它与 WIFI_ENABLED 的
        //   「开」分支是同一条设备命令，两项并列阅读更易理解。）

        // WiFi 总开关：开与关是两条不同的命令，参数集也完全不同。
        // 真机抓包（逐字）：
        //   关闭 WiFi：goformId=switchWiFiModule&isTest=false&SwitchOption=0&AD=...
        //   开启 WiFi：goformId=switchWiFiChip&isTest=false&ChipEnum=chip2&GuestEnable=0&AD=...
        // 抓包时设备处于 5G，因此「开」下发的是 chip2。结合已确认的 chip1=2.4G / chip2=5G，
        // switchWiFiChip&ChipEnum=X&GuestEnable=0 的语义是「在频段 X 上启用 WiFi」，
        // 既是「开」也是「切频段」，同属一条命令。切频段的独立入口是 SettingKey.WIFI_BAND。
        //
        // chip 参数（可选，取值 chip1 / chip2 —— 设备词汇，与读侧 wifi_chip 的取值域一致）：
        //   有值 → 原样作为 ChipEnum 下发；
        //   缺失 → 退回 chip1。这是调用方未提供当前频段时的兜底，会把设备切到 2.4G，
        //          属于已知的不理想分支：调用方（GoformWifiClient）有责任传入设备当前频段。
        //          该兜底仅在缺少参数时生效，不会覆盖调用方提供的频段。
        //   关分支不读 chip：switchWiFiModule 只认 SwitchOption，传入 chip 无副作用、
        //          不影响编码结果（因此调用方无需为「关」读取当前频段）。
        //
        // 实现约束：开启 WiFi 只能使用 switchWiFiChip（带频段），
        // 不得使用 switchWiFiModule&SwitchOption=1 —— 该组合从未发送过，抓包中亦不存在，
        // 无任何实测依据。switchWiFiChip 不是纯开关，而是「带频段启用」。
        SettingKey.WIFI_ENABLED to WriteSpec(
            command = "switchWiFiChip",
            commandOf = { p -> if (isOn(p["value"])) "switchWiFiChip" else "switchWiFiModule" },
            encode = { p ->
                if (isOn(p["value"])) mapOf(
                    "ChipEnum" to (p["chip"]?.toString() ?: WIFI_CHIP_FALLBACK),
                    "GuestEnable" to "0",
                )
                else mapOf("SwitchOption" to "0")
            },
            // 只校验 chip（value 是布尔语义，沿用 isOn 的宽松判定 —— 与 commandOf/encode 同一判据）。
            // 不 trim：encode 发的是 toString() 原值，校验若 trim 就会放过 " chip1" 再原样下发。
            validate = { p ->
                val chip = p["chip"]?.toString()
                if (chip != null && chip !in WIFI_CHIPS) WIFI_CHIP_REJECT_REASON else null
            },
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        // WiFi 频段选择：与上一项的「开」分支是同一条命令（switchWiFiChip），
        // 因此该命令同时会打开 WiFi —— 设备侧不存在「只切频段、不改开关」的形态。
        // 取值只接收设备词汇 chip1 / chip2：不接受界面文案（2.4G / 5G），也不接受读侧
        // ChipIndex 的原始编码（0 / 1），否则三套取值域会在此互相透传。
        // 没有 fallback：该命令没有「旧固件不识别、改发另一条」的实测依据。
        SettingKey.WIFI_BAND to WriteSpec(
            command = "switchWiFiChip",
            encode = { p ->
                mapOf(
                    "ChipEnum" to (p["value"]?.toString() ?: ""),
                    "GuestEnable" to "0",
                )
            },
            validate = { p ->
                if (p["value"]?.toString() in WIFI_CHIPS) null else WIFI_CHIP_REJECT_REASON
            },
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
        // 移动数据：与 GoformNetworkClient.setMobileData 一致
        //   主命令 → goformId=CONNECT_NETWORK（开）/ DISCONNECT_NETWORK（关）& notCallback=true
        //   主命令失败才发送 → goformId=SET_DATA_ENABLED & data=1/0（不带 notCallback，与现有代码一致）
        // notCallback=true 属于 encode 的输出，不放进 GoformSettingWriter 的公共 body
        // （只有这几条命令携带该参数，放进公共 body 会给所有写命令加上）。
        // isTest=false 由 GoformCodec.buildSetFormBody 统一补充，两条 spec 都不应重复发送。
        SettingKey.MOBILE_DATA to WriteSpec(
            command = "CONNECT_NETWORK",
            commandOf = { p -> if (isOn(p["value"])) "CONNECT_NETWORK" else "DISCONNECT_NETWORK" },
            encode = { mapOf("notCallback" to "true") },
            // 同一取值幂等（已经连上了再发一次连接无害，已断开再断一次也无害），而且这一项接进
            // writer 后走的就是 goformPostIdempotent 那条路径 —— 标 NEVER 才是行为变更。
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
            fallback = WriteSpec(
                command = "SET_DATA_ENABLED",
                encode = { p -> mapOf("data" to bool01(p["value"])) },
                // 独立判定（不继承主命令）：它是一条把数据开关设为 1/0 的纯设置类命令，
                // 同值幂等、无计费副作用，与主命令同走 goformPostIdempotent，因此同样可重试。
                retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
            ),
        ),
        // 手动拨号 / 挂断：GoformNetworkClient.connectNetwork() / disconnectNetwork()。
        // 命令名与参数与 MOBILE_DATA 的主命令完全一致，但没有 SET_DATA_ENABLED 兜底 ——
        // 这两个方法失败即为失败。合并进 MOBILE_DATA 相当于给这两个入口增加一条
        // 它们从未发送过的命令（见 SettingKey.PPP_DIAL 的 KDoc），属于行为变更。
        SettingKey.PPP_DIAL to WriteSpec(
            command = "CONNECT_NETWORK",
            commandOf = { p -> if (isOn(p["value"])) "CONNECT_NETWORK" else "DISCONNECT_NETWORK" },
            encode = { mapOf("notCallback" to "true") },
            // 与 MOBILE_DATA 主命令同一条命令、同样幂等，重试判据也一样
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),

        // ───── 整份 WiFi 热点配置（setAccessPointInfo）─────
        //
        // 设备侧该命令是整表替换：漏发一个键，该项即按设备默认值写入。已知两类故障均由此产生
        // （修改 SSID 时写坏口令、未传口令修改配置时清空口令），因此这里的原则是
        // 「该发的键一个都不能少、不该发的键一个都不能多」，每条缺省值均在下方给出。
        //
        // 读-改-写留在 GoformWifiClient：profile 只接收合并后的完整参数集。
        // 三个入口（修改整份配置 / 只改 SSID / 只改口令）的差异全部体现在「向 params 中放入哪几个键」。
        //
        // Password 的发送条件只看 passphrase 键是否存在，不在此处判断 auth / encryp ——
        // 「只改口令」入口对 OPEN 也会发送 Password，这属于调用方意图而非设备事实。
        // 把该条件写进 encode 会使三处无法共用同一份实现（见 SettingKey.WIFI_AP_CONFIG 的 KDoc）。
        //
        // validate 只覆盖 auth_mode 与 max_sta_num（权威取值表见 validateApConfig）。
        // SSID 与口令仍然不校验：它们允许任意字符（含 & 和 =），body 由 GoformCodec 统一 URL 编码；
        // 在此为 SSID / 口令增加格式校验会把当前可设置的值变为 Rejected，属于对外行为变更。
        SettingKey.WIFI_AP_CONFIG to WriteSpec(
            command = "setAccessPointInfo",
            encode = { p ->
                val out = LinkedHashMap<String, String>()
                // SSID 不做 trim：调用方已 trim，profile 再次 trim 会使「是否去空格」存在两处判断
                p["ssid"]?.let { out["SSID"] = it.toString() }
                val auth = p["auth_mode"]?.toString() ?: AP_AUTH_DEFAULT
                out["AuthMode"] = auth
                // OPEN 必须配 NONE：设备侧不允许「开放热点 + 加密算法」组合
                out["EncrypType"] =
                    if (auth == AP_AUTH_OPEN) AP_ENCRYP_NONE
                    else (p["encrypt_type"]?.toString() ?: AP_ENCRYP_DEFAULT)
                // base64(UTF-8)：与 GoformClient.base64Encode 逐字等价（标准字母表、带 = 填充）。
                // 写侧是 UTF-8，所以读侧解码也必须先试 UTF-8（见 WIFI_PASSWORD_DECODER）。
                p["passphrase"]?.let { out["Password"] = base64Utf8(it.toString()) }
                // 最大接入数是唯一"没传就真的不发"的可选项（现有客户端只有改整份配置时会带它）
                p["max_sta_num"]?.let { out["ApMaxStationNumber"] = it.toString() }
                out["ApBroadcastDisabled"] = p["broadcast_disabled"]?.toString() ?: "0"
                // 两个恒发的固定值：现有三个调用点都写死 "0"，不是可配置项
                out["ApIsolate"] = "0"
                out["AccessPointIndex"] = "0"
                out["ChipIndex"] = p["chip_index"]?.toString() ?: AP_CHIP_INDEX_DEFAULT
                out
            },
            validate = ::validateApConfig,
            // 设置类命令、同一取值幂等（同一份配置发送两次结果相同）
            retry = RetryPolicy.RETRY_ON_SESSION_LOSS,
        ),
    )

    // ────────────────────── WiFi 热点配置的设备侧缺省值 ──────────────────────
    // 这些常量定义「调用方未提供取值时设备侧应收到什么」，取值与 GoformWifiClient 的三个调用点一致。
    // 提为常量而非内联字面量：encode 与测试引用同一份，修改缺省值时不会遗漏。

    /** 认证方式缺省档：现有三个调用点在读不到当前值时都填这个。 */
    private const val AP_AUTH_DEFAULT = "WPA2PSK"

    /** 开放热点（无密码）的认证方式取值。 */
    private const val AP_AUTH_OPEN = "OPEN"

    /** 加密方式缺省档。 */
    private const val AP_ENCRYP_DEFAULT = "CCMP"

    /** [AP_AUTH_OPEN] 时唯一合法的加密方式。 */
    private const val AP_ENCRYP_NONE = "NONE"

    /** 芯片序号缺省值（单芯片机型恒为 "0"）。 */
    private const val AP_CHIP_INDEX_DEFAULT = "0"

    /**
     * `AuthMode` 的完整取值域（真机抓包，界面四个选项逐条对齐设备参数）：
     *
     * | 界面选项 | AuthMode | EncrypType | Password |
     * |---|---|---|---|
     * | `OPEN` | `OPEN` | `NONE` | 不发 |
     * | `WPA2(AES)-PSK` | `WPA2PSK` | `CCMP` | 发 |
     * | `WPA3-PSK` | `WPA3PSK` | `CCMP` | 发 |
     * | `WPA2-PSK/WPA3-PSK` | `WPA2PSKWPA3PSK` | `CCMP` | 发 |
     *
     * 设备大小写敏感，因此不做大小写兼容（`wpa2psk` 直接拒绝）。
     * 缺省档 [AP_AUTH_DEFAULT] 必须属于该集合 —— 因此它是集合成员而不是另写的字面量：
     * 调用方不传 `auth_mode` 时 encode 会填入缺省档，缺省档若不在白名单内就会「校验放过、设备拒收」。
     */
    private val AP_AUTH_MODES = setOf(AP_AUTH_OPEN, AP_AUTH_DEFAULT, "WPA3PSK", "WPA2PSKWPA3PSK")

    /**
     * `max_sta_num` 的合法区间：闭区间 `1..10`。
     *
     * 依据：中兴 F50 的最大接入设备数为 10（规格结论）。上限确定后，超过 10 的值应在下发前拒绝，
     * 而不是让设备按自身上限静默截断。
     */
    private val AP_MAX_STA_NUM_RANGE = 1..10

    /**
     * WiFi 热点配置的值域校验：只覆盖 `auth_mode` 与 `max_sta_num`。
     *
     * ## 为什么只覆盖这两项
     *
     * 只有这两项具备权威取值依据（[AP_AUTH_MODES] 的取值表 / [AP_MAX_STA_NUM_RANGE] 的 F50 结论）。
     * SSID 与 `passphrase` 不做任何校验：它们允许任意字符（含 `&` 和 `=`），
     * 增加格式校验会把当前可设置的值变为 Rejected。
     *
     * ## 三条实现约定
     *
     * 1. **null 表示未提供**（而不是「提供了空值」）：与 encode 的 `p["auth_mode"]?.toString() ?: 缺省`
     *    一致。用 `containsKey` 判定会使「显式传 null」被拒绝，而该路径当前走缺省值。
     * 2. 不 trim：encode 发送的是 `toString()` 原值，此处 trim 后比对会使 `" WPA2PSK "`
     *    通过校验、再被原样发送给设备 —— 校验与下发必须针对同一个字符串。
     * 3. `max_sta_num` 判定闭区间 [AP_MAX_STA_NUM_RANGE]（`1..10`）。越界在下发前拒绝，
     *    优于让设备按自身上限静默截断，用户可看到拒绝原因。
     *
     * 返回文案不包含参数值：这份 params 中含明文口令，拒绝原因会进入日志。
     */
    private fun validateApConfig(p: Map<String, Any?>): String? {
        p["auth_mode"]?.let { raw ->
            if (raw.toString() !in AP_AUTH_MODES) {
                return "认证方式只支持 OPEN / WPA2PSK / WPA3PSK / WPA2PSKWPA3PSK（大小写敏感）"
            }
        }
        p["max_sta_num"]?.let { raw ->
            val n = raw.toString().toIntOrNull()
            if (n == null || n !in AP_MAX_STA_NUM_RANGE) {
                return "最大接入设备数必须是 1~10 的整数（中兴 F50 最大支持 10 个）"
            }
        }
        // chip_index 必须与 wifi_chip 写项同取值域（WIFI_CHIPS）：缺少校验时它会原样透传为
        // ChipIndex，"chip9"、空串等非法值只会得到设备侧不明确的失败。
        p["chip_index"]?.let { raw ->
            if (raw.toString().trim() !in WIFI_CHIPS) {
                return "chip_index 只支持 chip1 / chip2"
            }
        }
        return null
    }


    /**
     * WiFi 口令的写侧编码：base64(UTF-8)。
     *
     * 与 `GoformClient.base64Encode` 逐字等价（标准字母表、带 `=` 填充、UTF-8）——
     * device-schema 是纯 JVM 模块，可以直接用 `java.util.Base64`。
     * 读侧的解码在 [WIFI_PASSWORD_DECODER]，**两者的字符集必须一致**，否则非 ASCII 口令
     * 会在「读回当前值 → 再编码写回」这条路径上被改坏。
     */
    private fun base64Utf8(plain: String): String =
        java.util.Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))




    /** 设备侧布尔编码：这一组命令用 `"1"` / `"0"`（另有一组用 `"on"` / `"off"`）。 */
    private fun bool01(v: Any?): String = when (v) {
        is Boolean -> if (v) "1" else "0"
        is String -> if (Decoders.TRUTHY.any { it.equals(v, ignoreCase = true) }) "1" else "0"
        is Number -> if (v.toInt() != 0) "1" else "0"
        else -> "0"
    }

    /** 另一套设备侧布尔编码：漫游/自动清零这组命令只认 `"on"` / `"off"`。 */
    private fun boolOnOff(v: Any?): String = if (bool01(v) == "1") "on" else "off"

    /**
     * 「这个取值是不是开」——只给 [WriteSpec.commandOf] 与按取值分叉的 encode 用。
     *
     * 复用 [bool01] 的判定而不是另写一套 `v == true`：命令选择与参数编码必须看同一个判据，
     * 否则会出现「按 chip1 的参数发给 switchWiFiModule」这种错位（两边各自判断布尔的后果）。
     */
    private fun isOn(v: Any?): Boolean = bool01(v) == "1"

    /** 设备侧 `ConnectionMode` 的取值域（大小写敏感，无法识别时不下发猜测值）。 */
    private val CONNECTION_MODES = setOf("auto_dial", "manual_dial")

    // ────────────────────── WiFi 频段（switchWiFiChip 的 ChipEnum） ──────────────────────

    /**
     * `ChipEnum` 的取值域：`chip1` = 2.4G、`chip2` = 5G（真机抓包）。
     *
     * 该词汇与读侧 canonical 字段 `wifi_chip` 的取值域一致（见 WIFI_CHIP_DECODER：
     * `ChipIndex=0` → `chip1`、`1` → `chip2`）。刻意不接受 `"2.4G"` / `"5G"`（界面文案）与
     * `"0"` / `"1"`（读侧原始编码）：一旦接受别名，读侧编码就会被当作设备值透传。
     */
    private val WIFI_CHIPS = setOf("chip1", "chip2")

    /**
     * [SettingKey.WIFI_ENABLED] 的「开」分支在调用方未提供 chip 时使用的兜底频段。
     *
     * 它会把设备切到 2.4G，是已知的不理想分支，不是推荐路径（调用方有责任传入当前频段）。
     * 该兜底仅在缺少参数时生效。
     */
    private const val WIFI_CHIP_FALLBACK = "chip1"

    /** 频段取值被拒的统一文案（不含参数值：被拒原因会进日志）。 */
    private const val WIFI_CHIP_REJECT_REASON = "WiFi 频段只支持 chip1（2.4G）/ chip2（5G）"



    private val SHA256_UPPER_HEX = Regex("^[0-9A-F]{64}$")

    /**
     * 口令字段必须是调用方已计算的 SHA256 大写十六进制。
     *
     * 该校验的目的不是格式而是安全：若调用点未做哈希，明文口令会被原样拼进 goform 表单发出，
     * 而设备只返回失败，日志中无法看出泄露已经发生。大写是设备侧要求
     * （现有调用点为 `client.sha256Hex(x).uppercase()`），小写无实测依据，因此一并拒绝：
     * 在下发前拒绝优于静默失败。
     */
    private fun isSha256UpperHex(v: Any?): Boolean =
        v?.toString()?.trim()?.matches(SHA256_UPPER_HEX) == true


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
     * 这些值会原样进入 goform 表单，因此必须拦截 `192.168.0.1&goformId=FACTORY_RESET`
     * 这类注入（body 已做 URL 编码，此处是第二道防线）。`dhcp_start` / `dhcp_end` 允许为空 ——
     * 关闭 DHCP 服务器时官方 UI 同样发送空串。
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


    /**
     * 频段号列表校验。
     *
     * 值域 1..255，与 [GoformNetworkClient] 的 `lockLteBands` / `lockNrBands` 注释以及
     * 路由层 `NetworkRoutes` 的约定一致：非法值必须在此处被拒绝（返回原因 → 路由返回
     * 400 OUT_OF_RANGE），不能只校验「是否为数字」就放行到基带。
     *
     * 空串仍表示「解锁」（与 [lteAllBandsMask] 的语义同侧，见 GoformAllBandsMaskTest）。
     */
    private fun validateBandList(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null // 解锁
        if (!s.matches(Regex("^[0-9]+(,[0-9]+)*$"))) return "频段列表只能是逗号分隔的数字"
        for (part in s.split(',')) {
            val band = part.toIntOrNull() ?: return "频段号必须是数字：$part"
            if (band !in 1..255) return "频段号超出范围（1..255）：$part"
        }
        return null
    }

    // ────────────────────── WiFi 接入控制名单 ──────────────────────

    /** `AclMode` 的黑名单档位。抓包里「拉黑」与「取消拉黑」都是 2，只是名单内容不同。 */
    const val ACL_MODE_BLACKLIST = "2"

    private val ACL_MAC_REGEX = Regex("^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$")

    /**
     * 拼设备侧名单串：`"a;b;"` —— 分号分隔且末尾也带分号（抓包形态，单项同样为 `"mac;"`）。
     * 空列表 → 空串（key 仍然要发送，见 [SettingKey.WIFI_ACL] 的说明）。
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
     * MAC 必须是标准 `xx:xx:xx:xx:xx:xx`，名称不得包含 `;`（分隔符）与 `&` `=`（设备侧表单为
     * 字符串拼接）。Mac 与 Name 两条列表长度必须相等 —— 设备按下标配对，
     * 长度不一致会把名称错配到其他 MAC 上。
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
     * 读侧：拆分设备的 `data_volume_limit_size` 复合串，派生出 `limit_value` /
     * `limit_unit_display` / `limit_bytes` 三个 canonical 键。
     *
     * 之所以放在 `structuralDecoder` 而不为复合串登记三条 `FieldSpec`：派生键直接使用
     * canonical 名，因此 `KEEP_PRESENT` 不会把 `data_volume_limit_size` 当作别名再输出一份 ——
     * 复合串不应出现在响应中：它会使 app 的 GB 档流量告警失效。
     * 写侧的反向拼串见 [compoundLimitSize]。
     *
     * 没有下划线时按 MB 处理（少数固件直接给纯数字）；值非正数或无法解析时不派生
     * `limit_bytes`，由「缺失 = 省略 key」的语义交给调用方按默认 0 处理。
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
     * 制式名 → 小区锁定命令的数字 RAT 码（`12` = LTE，`16` = NR）。
     *
     * 这是写侧的独立取值域，与 [NETWORK_TYPE_MAP]（读侧 `network_type` 的数字码）不是同一套编码。
     * 若以 `NETWORK_TYPE_MAP.keys` 充作白名单，两套码会被绑定，读侧表一变写侧校验就随之漂移。
     *
     * 同时接受已是数字码的入参：app 侧历史上直接发送 `"12"` / `"16"`，兼容期内不能判为非法
     * （契约见 `docs/UFI-AXIS-Core-API-Reference.md` 的 `/api/device/cell-lock`）。
     */
    private val CELL_LOCK_RAT_CODES = setOf("12", "16")

    private fun ratCodeOrNull(v: Any?): String? {
        val raw = v?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.matches(NUMERIC)) return raw.takeIf { it in CELL_LOCK_RAT_CODES }
        return when (raw.uppercase()) {
            "LTE", "4G", "FDD LTE", "TDD LTE" -> "12"
            "NR", "5G", "5G(NR)", "NR-SA", "NR-NSA", "5G NSA", "5G SA" -> "16"
            else -> null
        }
    }

    private fun ratCode(v: Any?): String = ratCodeOrNull(v) ?: "12"

    /**
     * 对外的卡槽序号 → 设备侧卡槽值。
     *
     * 设备侧的 0/1/2/11 是运营商预置位（移动 / 电信 / 联通 / 外置），不是物理槽位号；
     * 对外只暴露从 1 开始的序号与 `"external"`。
     *
     * 不接受设备侧原值：旧客户端的 `goformSlot` 取值域与新序号重叠
     * （旧的 `"1"` 是电信，新的 `1` 是第一个槽位），在此放行会把两套语义混在一起，
     * 因此旧值的换算留在 route 的兼容分支（`SimRoutes`）。
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

