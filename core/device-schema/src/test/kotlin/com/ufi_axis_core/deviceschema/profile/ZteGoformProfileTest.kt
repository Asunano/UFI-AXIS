package com.ufi_axis_core.deviceschema.profile

import com.ufi_axis_core.contract.DeviceFields
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.FieldNormalizer
import com.ufi_axis_core.deviceschema.FieldSpec
import com.ufi_axis_core.deviceschema.Sensitivity
import com.ufi_axis_core.deviceschema.ServingCell
import com.ufi_axis_core.deviceschema.SettingKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ZteGoformProfile] 映射表内容的基线。
 *
 * 这个文件的作用是**冻结对外字段名**：只要某个 canonical 被改掉或漏登记，这里就红。
 * 归一化引擎本身的语义在 `FieldNormalizerTest`。
 */
class ZteGoformProfileTest {

    private val specs = ZteGoformProfile.readSpecs()

    private fun groupOf(group: FieldGroup) = specs.filter { it.group == group }

    private fun normalize(group: FieldGroup, vararg pairs: Pair<String, String>) =
        FieldNormalizer.normalize(
            buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
            ZteGoformProfile,
            group,
        )

    /** 只要 canonical，不含过渡期别名 —— 用来断言"对外字段集合"。 */
    private fun canonicalOnly(group: FieldGroup, vararg pairs: Pair<String, String>) =
        FieldNormalizer.normalize(
            buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
            ZteGoformProfile,
            group,
            FieldNormalizer.LegacyAliases.DROP,
        )

    // ─────────────── canonical 必须来自 contract 的冻结清单 ───────────────

    @Test
    fun `每个 canonical 都登记在 DeviceFields 对应分组的 ALL 里`() {
        val allowed: Map<FieldGroup, List<String>> = mapOf(
            FieldGroup.DEVICE_SETTINGS to DeviceFields.DeviceSettings.ALL,
            FieldGroup.LAN_SETTINGS to DeviceFields.LanSettings.ALL,
            FieldGroup.WIFI_SETTINGS to DeviceFields.WifiSettings.ALL,
            FieldGroup.WIFI_CLIENTS to DeviceFields.WifiClients.ALL,
            FieldGroup.BAND_STATUS to DeviceFields.BandStatus.ALL,
            FieldGroup.CELL_INFO to DeviceFields.CellInfo.ALL,
            FieldGroup.IDENTITY to DeviceFields.Identity.ALL,
            FieldGroup.TRAFFIC_LIMIT to DeviceFields.TrafficLimit.ALL,
            FieldGroup.SIGNAL to DeviceFields.Signal.ALL,
            FieldGroup.CONNECTION to DeviceFields.Connection.ALL,
        )
        val offenders = specs.filterNot { it.canonical in (allowed[it.group] ?: emptyList()) }
            .map { "${it.group}.${it.canonical}" }
        assertEquals("canonical 必须写 DeviceFields 常量，不能是字面量或漏登记", emptyList<String>(), offenders)
    }

    @Test
    fun `同一分组内 canonical 不重复`() {
        FieldGroup.entries.forEach { g ->
            val names = groupOf(g).map { it.canonical }
            assertEquals("$g 有重复 canonical（后者会覆盖前者）", names.distinct(), names)
        }
    }

    @Test
    fun `每个 spec 的别名链自身不重复`() {
        val offenders = specs.filter { it.sources.distinct().size != it.sources.size }.map { it.canonical }
        assertEquals(emptyList<String>(), offenders)
    }

    /**
     * `network_information` 是**容器命令**：它一个 cmd 返回一整个子对象，
     * 里面的字段由 `structuralDecoder(SIGNAL)` 摊平后才会出现在顶层。
     * 所以这些 source 名不会、也不应该出现在 cmd 列表里 —— 查它们等于查容器。
     *
     * 这份清单是白名单而不是"跳过整组"：新增一个拼错的 NR 字段名仍然会红。
     */
    private val networkInformationFields = setOf(
        // NR 侧全部在容器里
        "nr_rsrp", "Z5g_rsrp", "nr_rsrq", "nr_rssi",
        "Nr_snr", "Nr_signal_strength", "Nr_fcn", "Nr_bands", "Nr_band_widths",
        "Nr_pci", "Nr_cell_id",
        // LTE 侧只有这几个在容器里，其余（lte_rsrp / Lte_snr / Lte_pci…）是顶层 cmd
        "Lte_fcn", "Lte_bands", "Lte_bands_widths", "Lte_signal_strength", "Lte_cell_id",
    )

    /**
     * 每个字段至少要有一个 source 能被查到，否则设备永远不会返回它。
     * "能被查到" = source 直接是一个 cmd，或者它是容器命令展开出来的嵌套字段。
     *
     * 例外清单里的三个字段由**别的查询**提供（cmd 列表是从现有 client 逐字符搬来的，
     * 为保证行为零变化不能在阶段 0 擅自加 cmd）：
     *   - `dhcpLease`：设备只填 `dhcpLease_hour`，秒级字段由客户端换算；
     *   - `Language`：来自 getDeviceVersion 那组查询；
     *   - `lan_station_list`：来自 DataHub 合并的另一份响应。
     */
    @Test
    fun `每个字段都能被 cmdsFor 查到`() {
        val knownGaps = setOf(
            DeviceFields.LanSettings.DHCP_LEASE,
            DeviceFields.Identity.LANGUAGE,
            DeviceFields.WifiClients.LAN_STATION_LIST,
        )
        // structuralDecoder 派生出来的键：设备侧没有同名 cmd，源头是复合串
        // data_volume_limit_size（它在 cmds 里）。派生键故意用 canonical 名，
        // 见 ZteGoformProfile.splitDataVolumeLimit。
        val derivedByDecoder = setOf(
            DeviceFields.TrafficLimit.LIMIT_VALUE,
            DeviceFields.TrafficLimit.LIMIT_UNIT_DISPLAY,
            DeviceFields.TrafficLimit.LIMIT_BYTES,
        )
        val unreachable = specs
            .filter { it.canonical !in knownGaps && it.canonical !in derivedByDecoder }
            .filter { spec ->
                val cmds = ZteGoformProfile.cmdsFor(spec.group)
                if (cmds.isEmpty()) return@filter false
                val container = if (spec.group == FieldGroup.SIGNAL) networkInformationFields else emptySet()
                spec.sources.none { it in cmds || it in container }
            }
            .map { "${it.group}.${it.canonical}" }
        assertEquals("字段登记了但没人去查它", emptyList<String>(), unreachable)
    }

    @Test
    fun `容器字段清单不含已是顶层 cmd 的名字`() {
        val cmds = ZteGoformProfile.cmdsFor(FieldGroup.SIGNAL).toSet()
        val overlap = networkInformationFields.filter { it in cmds }
        assertEquals("这些字段已经是顶层 cmd，不该再挂在容器豁免清单里", emptyList<String>(), overlap)
    }

    // ───────────────────────── 设备开关设置 ─────────────────────────

    @Test
    fun `设备开关归一成 1 与 0 字符串`() {
        val out = canonicalOnly(
            FieldGroup.DEVICE_SETTINGS,
            "indicator_light_switch" to "1",
            "performance_mode" to "0",
            "roam_setting_option" to "on",
            "dial_roam_setting_option" to "off",
        )
        assertEquals(JsonPrimitive("1"), out[DeviceFields.DeviceSettings.INDICATOR_LIGHT])
        assertEquals(JsonPrimitive("0"), out[DeviceFields.DeviceSettings.PERFORMANCE_MODE])
        assertEquals("漫游的 on 必须归一成 1", JsonPrimitive("1"), out[DeviceFields.DeviceSettings.ROAM])
        assertEquals(JsonPrimitive("0"), out[DeviceFields.DeviceSettings.DIAL_ROAM])
    }

    @Test
    fun `网络模式两个字段各自独立不互相回退`() {
        // web 读 BearerPreference、app 读 net_select，两个 canonical 都要在（冻结契约）
        val both = canonicalOnly(
            FieldGroup.DEVICE_SETTINGS,
            "BearerPreference" to "NR5G_ONLY",
            "net_select" to "only_5g",
        )
        assertEquals(JsonPrimitive("NR5G_ONLY"), both[DeviceFields.DeviceSettings.BEARER_PREFERENCE])
        assertEquals(JsonPrimitive("only_5g"), both[DeviceFields.DeviceSettings.NET_SELECT])

        val onlyOld = canonicalOnly(FieldGroup.DEVICE_SETTINGS, "net_select" to "only_5g")
        assertFalse(
            "老固件缺 BearerPreference 时该 key 省略，不能用 net_select 的值顶上",
            onlyOld.containsKey(DeviceFields.DeviceSettings.BEARER_PREFERENCE),
        )
    }

    @Test
    fun `连接模式按 connection_mode 到 conn_mode 到 dial_mode 的顺序回退`() {
        assertEquals(
            JsonPrimitive("manual"),
            canonicalOnly(FieldGroup.DEVICE_SETTINGS, "conn_mode" to "manual")[DeviceFields.DeviceSettings.CONNECTION_MODE],
        )
        assertEquals(
            "connection_mode 优先",
            JsonPrimitive("auto"),
            canonicalOnly(
                FieldGroup.DEVICE_SETTINGS,
                "dial_mode" to "hand",
                "connection_mode" to "auto",
            )[DeviceFields.DeviceSettings.CONNECTION_MODE],
        )
    }

    @Test
    fun `设备返回的未登记字段不进 settings 响应`() {
        val out = normalize(
            FieldGroup.DEVICE_SETTINGS,
            "indicator_light_switch" to "1",
            "usb_network_protocal" to "RNDIS", // 有查询但没登记 canonical
        )
        assertFalse(out.containsKey("usb_network_protocal"))
    }

    // ───────────────────────── LAN / DHCP ─────────────────────────

    @Test
    fun `dhcpEnabled 的 SERVER 编码视为开启`() {
        val out = canonicalOnly(FieldGroup.LAN_SETTINGS, "dhcpEnabled" to "SERVER")
        assertEquals(JsonPrimitive("1"), out[DeviceFields.LanSettings.DHCP_ENABLED])
    }

    @Test
    fun `LAN 别名取 web 与 app 的并集`() {
        // 只填 app 侧才认的老名字，canonical 依然要出来
        val out = canonicalOnly(
            FieldGroup.LAN_SETTINGS,
            "ipaddr" to "192.168.0.1",
            "DhcpStatus" to "1",
            "DhcpStartIP" to "192.168.0.100",
            "DhcpEndIP" to "192.168.0.200",
        )
        assertEquals(JsonPrimitive("192.168.0.1"), out[DeviceFields.LanSettings.LAN_IP])
        assertEquals(JsonPrimitive("1"), out[DeviceFields.LanSettings.DHCP_ENABLED])
        assertEquals(JsonPrimitive("192.168.0.100"), out[DeviceFields.LanSettings.DHCP_START])
        assertEquals(JsonPrimitive("192.168.0.200"), out[DeviceFields.LanSettings.DHCP_END])
    }

    @Test
    fun `dhcpLease_hour 不做单位换算`() {
        // 客户端两侧都已固化「读到后自己乘 3600」，这里换算会变成双重换算
        val out = canonicalOnly(FieldGroup.LAN_SETTINGS, "dhcpLease_hour" to "24")
        assertEquals(JsonPrimitive("24"), out[DeviceFields.LanSettings.DHCP_LEASE_HOUR])
    }

    @Test
    fun `mtu 保持字符串`() {
        val v = canonicalOnly(FieldGroup.LAN_SETTINGS, "mtu" to "1400")[DeviceFields.LanSettings.MTU]
        assertTrue("客户端按字符串解析，转成数字会打断它们", (v as JsonPrimitive).isString)
        assertEquals("1400", v.content)
    }

    // ───────────────────────── 频段与流量 ─────────────────────────

    @Test
    fun `频段锁定值原样透出`() {
        val out = canonicalOnly(
            FieldGroup.BAND_STATUS,
            "lte_band_lock" to "1,3,5",
            "nr_band_lock" to "0",
        )
        assertEquals(JsonPrimitive("1,3,5"), out[DeviceFields.BandStatus.LTE_BAND_LOCK])
        assertEquals("0 表示未锁定，不能被当成假值丢掉", JsonPrimitive("0"), out[DeviceFields.BandStatus.NR_BAND_LOCK])
    }

    @Test
    fun `设备侧的限额复合串不进对外契约`() {
        // 2.8：复合串 "470_1024" 与恒为 "MB" 的 unit 字段都不再登记，
        // 归一化后既没有 canonical 键也没有设备原名（allowlist 默认拒绝）。
        val out = normalize(
            FieldGroup.TRAFFIC_LIMIT,
            "data_volume_limit_size" to "470_1024",
            "data_volume_limit_unit" to "MB",
            "data_volume_limit_switch" to "1",
        )
        assertNull(out["limit_size"])
        assertNull(out["limit_unit"])
        assertNull("设备原名也不能漏出去", out["data_volume_limit_size"])
        assertEquals(JsonPrimitive("1"), out[DeviceFields.TrafficLimit.ENABLED])
    }

    @Test
    fun `月累计上下行按设备实际语义交叉绑定`() {
        // ZTE 固件的 monthly_rx/tx 是"从模块看 PC"的视角，与 canonical（rx=下载）相反。
        // 2026-09-01 真机实测：rx=985757259（940MB，实为上传）、tx=7530676294（7GB，实为下载）。
        // 掰正只允许发生在本 profile 一处，所以这条断言就是那个决定的守门人。
        val out = canonicalOnly(
            FieldGroup.TRAFFIC_LIMIT,
            "monthly_rx_bytes" to "985757259",
            "monthly_tx_bytes" to "7530676294",
        )
        assertEquals(
            "canonical rx = 下载 = 设备的 monthly_tx_bytes",
            JsonPrimitive("7530676294"), out[DeviceFields.TrafficLimit.MONTHLY_RX_BYTES],
        )
        assertEquals(
            "canonical tx = 上传 = 设备的 monthly_rx_bytes",
            JsonPrimitive("985757259"), out[DeviceFields.TrafficLimit.MONTHLY_TX_BYTES],
        )
    }

    @Test
    fun `流量开关的 flux 前缀别名可用`() {
        val out = canonicalOnly(FieldGroup.TRAFFIC_LIMIT, "flux_data_volume_limit_switch" to "1")
        assertEquals(JsonPrimitive("1"), out[DeviceFields.TrafficLimit.ENABLED])
    }

    @Test
    fun `限额复合串在归一化时就拆成数值 单位 字节`() {
        val out = canonicalOnly(FieldGroup.TRAFFIC_LIMIT, "data_volume_limit_size" to "470_1024")
        assertEquals(JsonPrimitive("470"), out[DeviceFields.TrafficLimit.LIMIT_VALUE])
        assertEquals(JsonPrimitive("GB"), out[DeviceFields.TrafficLimit.LIMIT_UNIT_DISPLAY])
        assertEquals(
            "470 GB 必须换算成字节，否则 app 的 GB 档流量告警永不触发",
            JsonPrimitive(470L * 1024 * 1024 * 1024),
            out[DeviceFields.TrafficLimit.LIMIT_BYTES],
        )
    }

    @Test
    fun `限额乘数决定单位而不是 unit 字段`() {
        // data_volume_limit_unit 恒为 "MB"，真实单位藏在乘数里。
        val tb = canonicalOnly(
            FieldGroup.TRAFFIC_LIMIT,
            "data_volume_limit_size" to "2_1048576",
            "data_volume_limit_unit" to "MB",
        )
        assertEquals(JsonPrimitive("TB"), tb[DeviceFields.TrafficLimit.LIMIT_UNIT_DISPLAY])
        assertEquals(JsonPrimitive(2L * 1024 * 1024 * 1024 * 1024), tb[DeviceFields.TrafficLimit.LIMIT_BYTES])

        // 没有下划线的固件按 MB 处理
        val plain = canonicalOnly(FieldGroup.TRAFFIC_LIMIT, "data_volume_limit_size" to "500")
        assertEquals(JsonPrimitive("MB"), plain[DeviceFields.TrafficLimit.LIMIT_UNIT_DISPLAY])
        assertEquals(JsonPrimitive(500L * 1024 * 1024), plain[DeviceFields.TrafficLimit.LIMIT_BYTES])
    }

    @Test
    fun `限额为空或非法时不派生字节数`() {
        val zero = canonicalOnly(FieldGroup.TRAFFIC_LIMIT, "data_volume_limit_size" to "0_1024")
        assertNull("0 不是有效限额，省略 key 交调用方按未设置处理", zero[DeviceFields.TrafficLimit.LIMIT_BYTES])

        val junk = canonicalOnly(FieldGroup.TRAFFIC_LIMIT, "data_volume_limit_size" to "_1024")
        assertNull(junk[DeviceFields.TrafficLimit.LIMIT_VALUE])
        assertNull(junk[DeviceFields.TrafficLimit.LIMIT_BYTES])

        val absent = canonicalOnly(FieldGroup.TRAFFIC_LIMIT, "data_volume_limit_switch" to "0")
        assertNull(absent[DeviceFields.TrafficLimit.LIMIT_VALUE])
        assertNull(absent[DeviceFields.TrafficLimit.LIMIT_UNIT_DISPLAY])
        assertNull(absent[DeviceFields.TrafficLimit.LIMIT_BYTES])
    }

    @Test
    fun `读侧拆出来的值能被写侧原样拼回去`() {
        val canon = canonicalOnly(FieldGroup.TRAFFIC_LIMIT, "data_volume_limit_size" to "470_1024")
        val spec = ZteGoformProfile.writeSpec(SettingKey.TRAFFIC_LIMIT)!!
        val params = spec.encode(mapOf(
            "limit_value" to (canon[DeviceFields.TrafficLimit.LIMIT_VALUE] as JsonPrimitive).content,
            "limit_unit" to (canon[DeviceFields.TrafficLimit.LIMIT_UNIT_DISPLAY] as JsonPrimitive).content,
        ))
        assertEquals("读回来的值改都不改再写回去，必须是同一个复合串", "470_1024", params["data_volume_limit_size"])
    }



    // ───────────────────────── 身份与脱敏 ─────────────────────────

    @Test
    fun `PII 字段标为 MASKED`() {
        val sensitive = groupOf(FieldGroup.IDENTITY)
            .filter { it.sensitivity == Sensitivity.MASKED }
            .map { it.canonical }
            .toSet()
        assertEquals(
            setOf(
                DeviceFields.Identity.MSISDN,
                DeviceFields.Identity.IMEI,
                DeviceFields.Identity.IMSI,
                DeviceFields.Identity.ICCID,
            ),
            sensitive,
        )
    }

    @Test
    fun `imsi 支持 sim_imsi 别名`() {
        val out = canonicalOnly(FieldGroup.IDENTITY, "sim_imsi" to "460001234567890")
        assertEquals(JsonPrimitive("460001234567890"), out[DeviceFields.Identity.IMSI])
    }

    @Test
    fun `maskSensitive 打码身份与 WiFi 密码`() {
        val identity = FieldNormalizer.maskSensitive(
            canonicalOnly(FieldGroup.IDENTITY, "msisdn" to "13800138000", "imei" to "860000000000000"),
            ZteGoformProfile,
        )
        assertEquals(JsonPrimitive("***"), identity[DeviceFields.Identity.MSISDN])
        assertEquals(JsonPrimitive("***"), identity[DeviceFields.Identity.IMEI])

        val wifi = FieldNormalizer.maskSensitive(
            // 设备侧密码是 base64，"c2VjcmV0" = "secret"
            canonicalOnly(FieldGroup.WIFI_SETTINGS, "wifi_chip1_ssid1_passphrase" to "c2VjcmV0", "wifi_chip1_ssid1_ssid" to "MyAP"),
            ZteGoformProfile,
        )
        assertEquals(JsonPrimitive("***"), wifi[DeviceFields.WifiSettings.PASSPHRASE])
        assertEquals("SSID 不是敏感字段", JsonPrimitive("MyAP"), wifi[DeviceFields.WifiSettings.SSID])
    }

    @Test
    fun `当前没有 SECRET 字段`() {
        // 有了 SECRET 字段就必须同时确认它在所有出口都被挡住，届时更新本断言
        assertEquals(emptySet<String>(), FieldNormalizer.secretKeys(ZteGoformProfile))
    }

    @Test
    fun `maskDump 打掉原始 dump 里的 PII 与凭据`() {
        // getFullStatus 的原始形状（设备原名、不过 allowlist），逐字取自 GoformSignalClient 的查询清单
        val dump = buildJsonObject {
            put("msisdn", JsonPrimitive("13800138000"))
            put("sim_msisdn", JsonPrimitive("13800138000"))
            put("imei", JsonPrimitive("860000000000000"))
            put("imsi", JsonPrimitive("460001234567890"))
            put("sim_imsi", JsonPrimitive("460001234567890"))
            put("iccid", JsonPrimitive("89860000000000000000"))
            put("wifi_chip1_ssid1_password_encode", JsonPrimitive("c2VjcmV0"))
            put("wifi_chip1_ssid1_ssid", JsonPrimitive("MyAP"))
            put("network_type", JsonPrimitive("LTE"))
        }
        val masked = FieldNormalizer.maskDump(dump, ZteGoformProfile)

        for (k in listOf("msisdn", "sim_msisdn", "imei", "imsi", "sim_imsi", "iccid")) {
            assertEquals("$k 是 PII，dump 里必须打码", JsonPrimitive("***"), masked[k])
        }
        assertEquals(
            "profile 没登记这个字段，靠名字兜底",
            JsonPrimitive("***"), masked["wifi_chip1_ssid1_password_encode"],
        )
        assertEquals(JsonPrimitive("MyAP"), masked["wifi_chip1_ssid1_ssid"])
        assertEquals(JsonPrimitive("LTE"), masked["network_type"])
        assertEquals("dump 的用途是看有哪些字段，key 不能少", dump.keys, masked.keys)
    }


    // ───────────────────────── WiFi ─────────────────────────

    /** 构造 `queryAccessPointInfo` 的响应形状。 */
    private fun accessPointInfo(vararg aps: Map<String, String>) = buildJsonObject {
        put(
            "ResponseList",
            buildJsonArray {
                aps.forEach { ap -> add(buildJsonObject { ap.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }) }
            },
        )
    }

    private fun wifiSettings(raw: JsonObject) =
        FieldNormalizer.normalize(raw, ZteGoformProfile, FieldGroup.WIFI_SETTINGS, FieldNormalizer.LegacyAliases.DROP)

    @Test
    fun `module-info 的生效 AP 覆盖扁平字段`() {
        val out = wifiSettings(
            buildJsonObject {
                put("wifi_chip1_ssid1_ssid", JsonPrimitive("FlatAP"))
                put("wifi_chip1_ssid1_auth_mode", JsonPrimitive("OPEN"))
                put("ResponseList", accessPointInfo(
                    mapOf("AccessPointSwitchStatus" to "0", "ChipIndex" to "0", "SSID" to "Idle2G"),
                    mapOf("AccessPointSwitchStatus" to "1", "ChipIndex" to "1", "SSID" to "Live5G",
                        "AuthMode" to "WPA2PSK", "EncrypType" to "AES",
                        "ApMaxStationNumber" to "16", "ApBroadcastDisabled" to "0"),
                )["ResponseList"]!!)
            },
        )
        assertEquals(JsonPrimitive("Live5G"), out[DeviceFields.WifiSettings.SSID])
        assertEquals(JsonPrimitive("WPA2PSK"), out[DeviceFields.WifiSettings.AUTH_MODE])
        assertEquals(JsonPrimitive("AES"), out[DeviceFields.WifiSettings.ENCRYPT_TYPE])
        assertEquals(JsonPrimitive("16"), out[DeviceFields.WifiSettings.MAX_STA_NUM])
        assertEquals(JsonPrimitive("0"), out[DeviceFields.WifiSettings.BROADCAST_SSID])
        assertEquals("ChipIndex=1 → chip2", JsonPrimitive("chip2"), out[DeviceFields.WifiSettings.CHIP])
    }

    @Test
    fun `没有生效 AP 时退回第一个`() {
        val out = wifiSettings(
            accessPointInfo(
                mapOf("AccessPointSwitchStatus" to "0", "ChipIndex" to "0", "SSID" to "First"),
                mapOf("AccessPointSwitchStatus" to "0", "ChipIndex" to "1", "SSID" to "Second"),
            ),
        )
        assertEquals(JsonPrimitive("First"), out[DeviceFields.WifiSettings.SSID])
        assertEquals(JsonPrimitive("chip1"), out[DeviceFields.WifiSettings.CHIP])
    }

    @Test
    fun `密码按 base64 解码`() {
        // "c2VjcmV0" = "secret"
        val out = wifiSettings(accessPointInfo(mapOf("AccessPointSwitchStatus" to "1", "Password" to "c2VjcmV0")))
        assertEquals(JsonPrimitive("secret"), out[DeviceFields.WifiSettings.PASSPHRASE])
    }

    /** 双频共享密码时固件只在其中一个 AP 上填值，生效 AP 可能是空的。 */
    @Test
    fun `生效 AP 没填密码时跨 AP 兜底`() {
        val out = wifiSettings(
            accessPointInfo(
                mapOf("AccessPointSwitchStatus" to "1", "SSID" to "Live", "Password" to ""),
                mapOf("AccessPointSwitchStatus" to "0", "SSID" to "Other", "Password" to "c2VjcmV0"),
            ),
        )
        assertEquals(JsonPrimitive("Live"), out[DeviceFields.WifiSettings.SSID])
        assertEquals(JsonPrimitive("secret"), out[DeviceFields.WifiSettings.PASSPHRASE])
    }

    @Test
    fun `密码解不开时省略 key 而不是给空串`() {
        val out = wifiSettings(accessPointInfo(mapOf("AccessPointSwitchStatus" to "1", "Password" to "!!!not base64!!!")))
        assertFalse(out.containsKey(DeviceFields.WifiSettings.PASSPHRASE))
    }

    /** 只有扁平查询成功（module-info 超时）时必须照常工作。 */
    @Test
    fun `没有 ResponseList 时扁平字段照常生效`() {
        val out = wifiSettings(
            buildJsonObject {
                put("wifi_chip1_ssid1_ssid", JsonPrimitive("FlatAP"))
                put("wifi_chip", JsonPrimitive("chip2"))
                put("WiFiModuleSwitch", JsonPrimitive("1"))
            },
        )
        assertEquals(JsonPrimitive("FlatAP"), out[DeviceFields.WifiSettings.SSID])
        assertEquals(JsonPrimitive("chip2"), out[DeviceFields.WifiSettings.CHIP])
        assertEquals(JsonPrimitive("1"), out[DeviceFields.WifiSettings.MODULE_SWITCH])
    }

    /** 提到顶层的 ZTE 原生驼峰名只是中间产物，不能出现在响应里。 */
    @Test
    fun `AP 原生字段名不对外透出`() {
        val out = wifiSettings(
            accessPointInfo(mapOf("AccessPointSwitchStatus" to "1", "SSID" to "X", "AuthMode" to "OPEN")),
        )
        listOf("ResponseList", "SSID", "AuthMode", "Password", "ChipIndex", "AccessPointSwitchStatus").forEach {
            assertFalse("$it 不该出现在归一化结果里", out.containsKey(it))
        }
    }

    @Test
    fun `WiFi 总开关的三个别名都能命中`() {
        listOf("WiFiModuleSwitch", "wifi_enable", "wifi_onoff_state").forEach { src ->
            val out = canonicalOnly(FieldGroup.WIFI_SETTINGS, src to "1")
            assertEquals("$src 未命中", JsonPrimitive("1"), out[DeviceFields.WifiSettings.MODULE_SWITCH])
        }
    }

    /**
     * 1.5：两种设备形态（真数组 / 双重编码字符串）都归一成**真数组**。
     * 这是两端已支持形态的子集（app `parseArrayField`、web `data?.stationList || ...` 都能解），
     * 所以统一形态不破坏任何消费点，反而把「两种都要兜底」的 if 从客户端拿掉了。
     */
    @Test
    fun `station_list 两种形态都归一成真数组`() {
        val asArray = FieldNormalizer.normalize(
            buildJsonObject {
                put("station_list", buildJsonArray { add(buildJsonObject { put("mac_addr", JsonPrimitive("00:11")) }) })
            },
            ZteGoformProfile,
            FieldGroup.WIFI_CLIENTS,
        )
        assertTrue(asArray[DeviceFields.WifiClients.STATION_LIST] is JsonArray)

        val doubleEncoded = canonicalOnly(FieldGroup.WIFI_CLIENTS, "station_list" to """[{"mac_addr":"00:11"}]""")
        val arr = doubleEncoded[DeviceFields.WifiClients.STATION_LIST] as JsonArray
        assertEquals(
            JsonPrimitive("00:11"),
            (arr[0] as JsonObject)[DeviceFields.WifiClients.ITEM_MAC],
        )
    }

    /** 元素键别名统一：web 里那句 `c.mac_addr ?? c.mac` 的来源。 */
    @Test
    fun `station_list 元素键别名归一`() {
        val out = canonicalOnly(
            FieldGroup.WIFI_CLIENTS,
            "station_list" to """[{"mac":"00:11","ip":"192.168.0.2","host_name":"phone","rssi":"-40"}]""",
        )
        val item = (out[DeviceFields.WifiClients.STATION_LIST] as JsonArray)[0] as JsonObject
        assertEquals(JsonPrimitive("00:11"), item[DeviceFields.WifiClients.ITEM_MAC])
        assertEquals(JsonPrimitive("192.168.0.2"), item[DeviceFields.WifiClients.ITEM_IP])
        assertEquals(JsonPrimitive("phone"), item[DeviceFields.WifiClients.ITEM_HOSTNAME])
        assertEquals("未登记的元素键保留（元素不是接口契约）", JsonPrimitive("-40"), item["rssi"])
    }

    @Test
    fun `元素已有 canonical 名时别名不覆盖`() {
        val out = canonicalOnly(
            FieldGroup.WIFI_CLIENTS,
            "station_list" to """[{"mac_addr":"AA","mac":"BB"}]""",
        )
        val item = (out[DeviceFields.WifiClients.STATION_LIST] as JsonArray)[0] as JsonObject
        assertEquals(JsonPrimitive("AA"), item[DeviceFields.WifiClients.ITEM_MAC])
    }

    @Test
    fun `不是数组也不是数组字符串时原样保留`() {
        val out = canonicalOnly(FieldGroup.WIFI_CLIENTS, "station_list" to "not a list")
        assertEquals(JsonPrimitive("not a list"), out[DeviceFields.WifiClients.STATION_LIST])
    }

    @Test
    fun `station_list 必须单独查询`() {
        // 与其它 cmd 组合时设备返回空，这是踩过的坑
        assertEquals(listOf("station_list"), ZteGoformProfile.soloCmds(FieldGroup.WIFI_CLIENTS))
        assertTrue(ZteGoformProfile.soloCmds(FieldGroup.DEVICE_SETTINGS).isEmpty())
    }

    // ───────────────────────── 小区信息 ─────────────────────────

    @Test
    fun `小区字段保留设备侧大小写`() {
        val out = canonicalOnly(
            FieldGroup.CELL_INFO,
            "Lte_pci" to "123",
            "Lte_fcn" to "1850",
            "Lte_bands" to "3",
        )
        assertEquals(JsonPrimitive("123"), out[DeviceFields.CellInfo.LTE_PCI])
        assertEquals(JsonPrimitive("1850"), out[DeviceFields.CellInfo.LTE_EARFCN])
        assertEquals(JsonPrimitive("3"), out[DeviceFields.CellInfo.LTE_BANDS])
    }

    @Test
    fun `lte_snr 大小写两种设备写法都能命中`() {
        assertEquals(JsonPrimitive("18"), canonicalOnly(FieldGroup.CELL_INFO, "lte_snr" to "18")[DeviceFields.CellInfo.LTE_SNR])
        assertEquals(JsonPrimitive("18"), canonicalOnly(FieldGroup.CELL_INFO, "Lte_snr" to "18")[DeviceFields.CellInfo.LTE_SNR])
    }

    /** 1.6：邻区 / 已锁定小区两种形态都归一成真数组（web 的 `parseCellArray()` 就是为此存在的）。 */
    @Test
    fun `邻区列表两种形态都归一成真数组`() {
        val doubleEncoded = canonicalOnly(
            FieldGroup.CELL_INFO,
            "neighbor_cell_info" to """[{"pci":"301","earfcn":"1850"}]""",
            "locked_cell_info" to """[{"pci":"7","rat":"lte"}]""",
        )
        assertTrue(doubleEncoded[DeviceFields.CellInfo.NEIGHBOR_CELL_INFO] is JsonArray)
        assertTrue(doubleEncoded[DeviceFields.CellInfo.LOCKED_CELL_INFO] is JsonArray)

        val asArray = FieldNormalizer.normalize(
            buildJsonObject {
                put("neighbor_cell_info", buildJsonArray { add(buildJsonObject { put("pci", JsonPrimitive("301")) }) })
            },
            ZteGoformProfile,
            FieldGroup.CELL_INFO,
            FieldNormalizer.LegacyAliases.DROP,
        )
        assertTrue(asArray[DeviceFields.CellInfo.NEIGHBOR_CELL_INFO] is JsonArray)
    }

    @Test
    fun `邻区元素键别名归一`() {
        val out = canonicalOnly(
            FieldGroup.CELL_INFO,
            "neighbor_cell_info" to """[{"PCI":"301","fcn":"1850","RSRP":"-95","snr":"12","band":"3"}]""",
        )
        val item = (out[DeviceFields.CellInfo.NEIGHBOR_CELL_INFO] as JsonArray)[0] as JsonObject
        assertEquals(JsonPrimitive("301"), item[DeviceFields.CellInfo.ITEM_PCI])
        assertEquals(JsonPrimitive("1850"), item[DeviceFields.CellInfo.ITEM_EARFCN])
        assertEquals(JsonPrimitive("-95"), item[DeviceFields.CellInfo.ITEM_RSRP])
        assertEquals("snr 与 sinr 是同一个量", JsonPrimitive("12"), item[DeviceFields.CellInfo.ITEM_SINR])
        assertEquals("未登记的元素键保留", JsonPrimitive("3"), item["band"])
    }

    /** `network_information` 是 CELL_INFO 的查询容器，但它自己不是登记字段，不能透出。 */
    @Test
    fun `cell-info 不透出 network_information 容器`() {
        val out = FieldNormalizer.normalize(
            buildJsonObject {
                put("Lte_pci", JsonPrimitive("123"))
                put("network_information", buildJsonObject { put("Nr_pci", JsonPrimitive("301")) })
            },
            ZteGoformProfile,
            FieldGroup.CELL_INFO,
            FieldNormalizer.LegacyAliases.DROP,
        )
        assertEquals(JsonPrimitive("123"), out[DeviceFields.CellInfo.LTE_PCI])
        assertFalse(out.containsKey("network_information"))
        assertFalse("NR 字段归 SIGNAL 分组，不在 cell-info 里", out.containsKey("Nr_pci"))
    }

    @Test
    fun `cmdsFor CELL_INFO 与 getCellInfo 的查询一致`() {
        assertEquals(
            listOf(
                "neighbor_cell_info", "locked_cell_info", "network_information",
                "network_type",
                "Lte_pci", "Lte_fcn", "Lte_bands", "lte_rsrp", "lte_rsrq", "Lte_snr",
            ),
            ZteGoformProfile.cmdsFor(FieldGroup.CELL_INFO),
        )
    }

    // ───────────────────────── 信号（阶段 1.9 / 1.10） ─────────────────────────

    /** SIGNAL 分组的 canonical 集合冻结：少一个 = 有端点会突然不返回字段。 */
    @Test
    fun `SIGNAL 分组登记了第 1 层全部字段`() {
        val expected = listOf(
            DeviceFields.Signal.RSRP, DeviceFields.Signal.SINR, DeviceFields.Signal.RSRQ,
            DeviceFields.Signal.RSSI, DeviceFields.Signal.RAT, DeviceFields.Signal.CELL_ID,
            DeviceFields.Signal.OPERATOR,
            DeviceFields.Signal.NR_ARFCN, DeviceFields.Signal.NR_BAND, DeviceFields.Signal.NR_BAND_WIDTH,
            DeviceFields.Signal.NR_SIGNAL_STRENGTH, DeviceFields.Signal.NR_SNR,
            DeviceFields.Signal.NR_PCI, DeviceFields.Signal.NR_CELL_ID,
            DeviceFields.Signal.LTE_ARFCN, DeviceFields.Signal.LTE_BAND, DeviceFields.Signal.LTE_BAND_WIDTH,
            DeviceFields.Signal.LTE_SIGNAL_STRENGTH, DeviceFields.Signal.LTE_SNR,
            DeviceFields.Signal.LTE_PCI, DeviceFields.Signal.LTE_CELL_ID,
            DeviceFields.Signal.LTE_CA_STATUS,
        )
        assertEquals(expected.sorted(), groupOf(FieldGroup.SIGNAL).map { it.canonical }.sorted())
    }

    /**
     * 派生字段和 Telephony 字段**不能**登记进 profile：
     * 一旦登记，设备侧只要出现同名字段就会绕过派生逻辑直接透出。
     */
    @Test
    fun `派生与 Telephony 字段不登记在 profile 里`() {
        val forbidden = setOf(
            DeviceFields.Signal.BAND, DeviceFields.Signal.BAND_LABEL, DeviceFields.Signal.ARFCN,
            DeviceFields.Signal.BAND_WIDTH, DeviceFields.Signal.SIGNAL_STRENGTH, DeviceFields.Signal.PCI,
            DeviceFields.Signal.NETWORK_REGISTERED,
        )
        val offenders = groupOf(FieldGroup.SIGNAL).map { it.canonical }.filter { it in forbidden }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `rsrp 按 nr 到 Z5g 到 lte 的顺序回退`() {
        assertEquals(
            JsonPrimitive(-80L),
            canonicalOnly(
                FieldGroup.SIGNAL,
                "nr_rsrp" to "-80", "Z5g_rsrp" to "-85", "lte_rsrp" to "-95",
            )[DeviceFields.Signal.RSRP],
        )
        assertEquals(
            JsonPrimitive(-85L),
            canonicalOnly(FieldGroup.SIGNAL, "Z5g_rsrp" to "-85", "lte_rsrp" to "-95")[DeviceFields.Signal.RSRP],
        )
        assertEquals(
            JsonPrimitive(-95L),
            canonicalOnly(FieldGroup.SIGNAL, "lte_rsrp" to "-95")[DeviceFields.Signal.RSRP],
        )
    }

    @Test
    fun `sinr 与 rssi 的合并顺序是 NR 优先`() {
        val out = canonicalOnly(
            FieldGroup.SIGNAL,
            "Nr_snr" to "20", "Lte_snr" to "8",
            "Nr_signal_strength" to "-60", "nr_rssi" to "-65", "lte_rssi" to "-70",
        )
        assertEquals(JsonPrimitive(20L), out[DeviceFields.Signal.SINR])
        assertEquals(JsonPrimitive(-60L), out[DeviceFields.Signal.RSSI])
        // 同一个设备字段可以喂两个 canonical
        assertEquals(JsonPrimitive(20L), out[DeviceFields.Signal.NR_SNR])
        assertEquals(JsonPrimitive(8L), out[DeviceFields.Signal.LTE_SNR])
    }

    /** 顶层 `rssi` 是 0-5 信号格数，混进 dBm 别名链会让 UI 显示 "-3 dBm" 之类。 */
    @Test
    fun `顶层 rssi 信号格数不喂给 rssi 字段`() {
        val out = canonicalOnly(FieldGroup.SIGNAL, "rssi" to "4")
        assertNull(out[DeviceFields.Signal.RSSI])
    }

    @Test
    fun `network_type 数字走映射表未知值带原文非数字原样透出`() {
        assertEquals(
            JsonPrimitive("5G"),
            canonicalOnly(FieldGroup.SIGNAL, "network_type" to "20")[DeviceFields.Signal.RAT],
        )
        assertEquals(
            JsonPrimitive("未知(999)"),
            canonicalOnly(FieldGroup.SIGNAL, "network_type" to "999")[DeviceFields.Signal.RAT],
        )
        // 固件已经给了可读文案时不要再包一层"未知(...)"
        assertEquals(
            JsonPrimitive("NSA"),
            canonicalOnly(FieldGroup.SIGNAL, "network_type" to "NSA")[DeviceFields.Signal.RAT],
        )
        assertNull("空串视为字段缺失", canonicalOnly(FieldGroup.SIGNAL, "network_type" to "")[DeviceFields.Signal.RAT])
    }

    @Test
    fun `cell_id 保持字符串以容纳超 Int 的 NCI`() {
        val out = canonicalOnly(FieldGroup.SIGNAL, "Nr_cell_id" to "1234567890123", "cell_id" to "999")
        assertEquals(JsonPrimitive("1234567890123"), out[DeviceFields.Signal.CELL_ID])
        assertEquals(JsonPrimitive("1234567890123"), out[DeviceFields.Signal.NR_CELL_ID])
    }

    // ── structuralDecoder：network_information 摊平 ──

    @Test
    fun `network_information 子对象里的 NR 字段能被命中`() {
        val out = FieldNormalizer.normalize(
            buildJsonObject {
                put("network_type", JsonPrimitive("20"))
                put(
                    "network_information",
                    buildJsonObject {
                        put("nr_rsrp", JsonPrimitive("-78"))
                        put("Nr_bands", JsonPrimitive(78))
                        put("Nr_fcn", JsonPrimitive("633984"))
                    },
                )
            },
            ZteGoformProfile,
            FieldGroup.SIGNAL,
            FieldNormalizer.LegacyAliases.DROP,
        )
        assertEquals(JsonPrimitive(-78L), out[DeviceFields.Signal.RSRP])
        assertEquals(JsonPrimitive("78"), out[DeviceFields.Signal.NR_BAND])
        assertEquals(JsonPrimitive(633984L), out[DeviceFields.Signal.NR_ARFCN])
        assertEquals(JsonPrimitive("5G"), out[DeviceFields.Signal.RAT])
        assertNull("容器本身不是登记字段，不能透出", out["network_information"])
    }

    @Test
    fun `network_information 双重编码成 JSON 字符串时也能摊平`() {
        val out = FieldNormalizer.normalize(
            buildJsonObject {
                put("network_information", JsonPrimitive("""{"nr_rsrp":"-78","Nr_pci":"301"}"""))
            },
            ZteGoformProfile,
            FieldGroup.SIGNAL,
            FieldNormalizer.LegacyAliases.DROP,
        )
        assertEquals(JsonPrimitive(-78L), out[DeviceFields.Signal.RSRP])
        assertEquals(JsonPrimitive(301L), out[DeviceFields.Signal.NR_PCI])
    }

    @Test
    fun `摊平时顶层优先不被子对象覆盖`() {
        val out = FieldNormalizer.normalize(
            buildJsonObject {
                put("lte_rsrp", JsonPrimitive("-95"))
                put("network_information", buildJsonObject { put("lte_rsrp", JsonPrimitive("-70")) })
            },
            ZteGoformProfile,
            FieldGroup.SIGNAL,
            FieldNormalizer.LegacyAliases.DROP,
        )
        assertEquals(JsonPrimitive(-95L), out[DeviceFields.Signal.RSRP])
    }

    @Test
    fun `network_information 是垃圾串时退回原对象而不是抛异常`() {
        val out = FieldNormalizer.normalize(
            buildJsonObject {
                put("network_information", JsonPrimitive("not json at all"))
                put("lte_rsrp", JsonPrimitive("-95"))
            },
            ZteGoformProfile,
            FieldGroup.SIGNAL,
            FieldNormalizer.LegacyAliases.DROP,
        )
        assertEquals(JsonPrimitive(-95L), out[DeviceFields.Signal.RSRP])
    }

    @Test
    fun `结构解码器只登记在需要结构变换的分组上`() {
        val withDecoder = FieldGroup.entries.filter { ZteGoformProfile.structuralDecoder(it) != null }
        assertEquals(
            listOf(
                FieldGroup.SIGNAL,        // network_information 摊平
                FieldGroup.WIFI_SETTINGS, // 生效 AP 提到顶层
                FieldGroup.WIFI_CLIENTS,  // station_list 统一成真数组
                FieldGroup.CELL_INFO,     // neighbor/locked_cell_info 统一成真数组
                FieldGroup.TRAFFIC_LIMIT, // data_volume_limit_size 复合串拆成三个 canonical 键
            ).sortedBy { it.name },
            withDecoder.sortedBy { it.name },
        )
    }

    // ── ServingCell：服务小区统一字段 ──

    @Test
    fun `服务小区在 NR 有值时取 NR 并拼 n 前缀`() {
        val canon = canonicalOnly(
            FieldGroup.SIGNAL,
            "Nr_bands" to "78", "Nr_fcn" to "633984", "Nr_pci" to "301", "Nr_signal_strength" to "-60",
            "Lte_bands" to "3", "Lte_fcn" to "1850", "Lte_pci" to "123", "Lte_signal_strength" to "-80",
        )
        val cell = ServingCell.derive(canon)
        assertEquals(JsonPrimitive("78"), cell[DeviceFields.Signal.BAND])
        assertEquals(JsonPrimitive("n78"), cell[DeviceFields.Signal.BAND_LABEL])
        assertEquals(JsonPrimitive(633984L), cell[DeviceFields.Signal.ARFCN])
        assertEquals(JsonPrimitive(301L), cell[DeviceFields.Signal.PCI])
        assertEquals(JsonPrimitive(-60L), cell[DeviceFields.Signal.SIGNAL_STRENGTH])
    }

    @Test
    fun `服务小区在只有 LTE 时兜底并拼 B 前缀`() {
        val cell = ServingCell.derive(
            canonicalOnly(
                FieldGroup.SIGNAL,
                "Lte_bands" to "3", "Lte_fcn" to "1850", "Lte_pci" to "123",
            ),
        )
        assertEquals(JsonPrimitive("3"), cell[DeviceFields.Signal.BAND])
        assertEquals(JsonPrimitive("B3"), cell[DeviceFields.Signal.BAND_LABEL])
        assertEquals(JsonPrimitive(1850L), cell[DeviceFields.Signal.ARFCN])
    }

    /** 固件普遍不填带宽 —— 缺失必须省略 key，不能给 0 也不能给 null。 */
    @Test
    fun `带宽为空时不输出 band_width`() {
        val cell = ServingCell.derive(
            canonicalOnly(
                FieldGroup.SIGNAL,
                "Nr_bands" to "78", "Nr_band_widths" to "", "Lte_bands_widths" to "",
            ),
        )
        assertEquals(JsonPrimitive("78"), cell[DeviceFields.Signal.BAND])
        assertFalse("band_width 缺失时不能出现在响应里", cell.containsKey(DeviceFields.Signal.BAND_WIDTH))
    }

    /** 不看 `rat` 字符串：NSA 下 network_type 是 "NSA"，两侧都有值时仍取 NR。 */
    @Test
    fun `NSA 双连接下取 NR 侧`() {
        val cell = ServingCell.derive(
            canonicalOnly(
                FieldGroup.SIGNAL,
                "network_type" to "NSA", "Nr_bands" to "78", "Lte_bands" to "3",
            ),
        )
        assertEquals(JsonPrimitive("n78"), cell[DeviceFields.Signal.BAND_LABEL])
    }

    @Test
    fun `两侧都没值时派生结果为空对象`() {
        assertTrue(ServingCell.derive(canonicalOnly(FieldGroup.SIGNAL, "network_type" to "44")).isEmpty())
    }


    // ───────────────────────── 写入侧 ─────────────────────────

    @Test
    fun `LED 与性能模式编码成 1 与 0`() {
        val led = ZteGoformProfile.writeSpec(SettingKey.LED)!!
        assertEquals("INDICATOR_LIGHT_SETTING", led.command)
        assertEquals(mapOf("indicator_light_switch" to "1"), led.encode(mapOf("value" to true)))
        assertEquals(mapOf("indicator_light_switch" to "0"), led.encode(mapOf("value" to false)))
        assertEquals("字符串真值也认", mapOf("indicator_light_switch" to "1"), led.encode(mapOf("value" to "on")))
        assertEquals("缺参数按关处理", mapOf("indicator_light_switch" to "0"), led.encode(emptyMap()))

        val perf = ZteGoformProfile.writeSpec(SettingKey.PERFORMANCE_MODE)!!
        assertEquals("PERFORMANCE_MODE_SETTING", perf.command)
        assertEquals(mapOf("performance_mode" to "1"), perf.encode(mapOf("value" to true)))
    }

    @Test
    fun `频段锁定拦住非数字输入`() {
        val lte = ZteGoformProfile.writeSpec(SettingKey.BAND_LOCK_LTE)!!
        assertEquals("LTE_BAND_LOCK", lte.command)
        assertNull("正常频段串放行", lte.validate(mapOf("value" to "1,3,5")))
        assertNull("空串 = 解锁", lte.validate(mapOf("value" to "")))
        assertNull(lte.validate(emptyMap()))
        // 设备侧表单是字符串拼接且未转义，& 与 = 会注入额外参数
        assertNotNull("& 必须被拦住", lte.validate(mapOf("value" to "1&goformId=REBOOT")))
        assertNotNull(lte.validate(mapOf("value" to "1=2")))
        assertNotNull("带 B 前缀的写法不接受", lte.validate(mapOf("value" to "B3")))
        assertNotNull(lte.validate(mapOf("value" to "1,,3")))
        assertEquals(mapOf("lte_band_lock" to "1,3"), lte.encode(mapOf("value" to "1,3")))

        val nr = ZteGoformProfile.writeSpec(SettingKey.BAND_LOCK_NR)!!
        assertEquals("NR_BAND_LOCK", nr.command)
        assertNotNull(nr.validate(mapOf("value" to "n78")))
    }

    @Test
    fun `WiFi 休眠时间只接受非负整数`() {
        val spec = ZteGoformProfile.writeSpec(SettingKey.WIFI_SLEEP_IDLE_MINUTES)!!
        assertEquals("SET_WIFI_SLEEP_INFO", spec.command)
        assertNull(spec.validate(mapOf("value" to 0)))
        assertNull(spec.validate(mapOf("value" to "10")))
        assertNotNull(spec.validate(mapOf("value" to -1)))
        assertNotNull(spec.validate(mapOf("value" to "abc")))
        assertNotNull(spec.validate(emptyMap()))
        assertEquals(mapOf("sleep_sysIdleTimeToSleep" to "10"), spec.encode(mapOf("value" to 10)))
    }

    @Test
    fun `定时重启同时下发开关与时间`() {
        val spec = ZteGoformProfile.writeSpec(SettingKey.RESTART_SCHEDULE)!!
        assertEquals("RESTART_SCHEDULE_SETTING", spec.command)
        assertEquals(
            mapOf("restart_time" to "03:30", "restart_schedule_switch" to "1"),
            spec.encode(mapOf("enabled" to true, "time" to "03:30")),
        )
        assertNull(spec.validate(mapOf("time" to "23:59")))
        assertNull("没传时间时由调用方给默认值", spec.validate(emptyMap()))
        assertNotNull(spec.validate(mapOf("time" to "24:00")))
        assertNotNull(spec.validate(mapOf("time" to "3-30")))
    }

    @Test
    fun `漫游用 on 与 off 而不是 1 与 0`() {
        // 陷阱 27：同一设备两套布尔编码，统一了设备会静默忽略写入
        val roam = ZteGoformProfile.writeSpec(SettingKey.ROAM)!!
        assertEquals("SET_CONNECTION_MODE", roam.command)
        assertEquals(
            mapOf(
                "ConnectionMode" to "auto_dial",
                "roam_setting_option" to "on",
                "dial_roam_setting_option" to "on",
            ),
            roam.encode(mapOf("value" to true)),
        )
        assertEquals("off", roam.encode(mapOf("value" to false))["roam_setting_option"])

        // 对比：Samba / USB 调试是 "1"/"0" 那一套
        assertEquals(
            mapOf("samba_switch" to "1"),
            ZteGoformProfile.writeSpec(SettingKey.SAMBA)!!.encode(mapOf("value" to true)),
        )
        assertEquals(
            mapOf("usb_port_switch" to "0"),
            ZteGoformProfile.writeSpec(SettingKey.USB_PORT)!!.encode(mapOf("value" to false)),
        )
    }

    @Test
    fun `性能模式接受 Int 入参`() {
        // route 侧算出来的是 0 / 1（Int），不能被当成"未知值"降级成 0
        val perf = ZteGoformProfile.writeSpec(SettingKey.PERFORMANCE_MODE)!!
        assertEquals(mapOf("performance_mode" to "1"), perf.encode(mapOf("value" to 1)))
        assertEquals(mapOf("performance_mode" to "0"), perf.encode(mapOf("value" to 0)))
    }

    @Test
    fun `流量限额把结构化参数拼成设备复合串`() {
        // 断言照 2026-08-30 真机抓包的 body 逐字段对齐：
        // goformId=DATA_LIMIT_SETTING&data_volume_limit_unit=data&data_volume_limit_size=472_1024
        // &data_volume_alert_percent=90&wan_auto_clear_flow_data_switch=on&traffic_clear_date=2
        // &data_volume_limit_switch=1&notify_deviceui_enable=0
        val spec = ZteGoformProfile.writeSpec(SettingKey.TRAFFIC_LIMIT)!!
        assertEquals("DATA_LIMIT_SETTING", spec.command)
        val out = spec.encode(mapOf(
            "enabled" to true,
            "limit_value" to 472,
            "limit_unit" to "GB",
            "alert_percent" to "90",
            "auto_clear" to true,
            "clear_date" to "2",
        ))
        assertEquals("472_1024", out["data_volume_limit_size"])
        assertEquals("这是限额模式（data=按流量/time=按时长），不是数据单位", "data", out["data_volume_limit_unit"])
        assertEquals("1", out["data_volume_limit_switch"])
        assertEquals("90", out["data_volume_alert_percent"])
        assertEquals("自动清零是 on/off 那一套", "on", out["wan_auto_clear_flow_data_switch"])
        assertEquals("2", out["traffic_clear_date"])
        assertEquals("0", out["notify_deviceui_enable"])
        assertEquals("off", spec.encode(mapOf("auto_clear" to false))["wan_auto_clear_flow_data_switch"])

        assertEquals("1_1", spec.encode(mapOf("limit_value" to 1, "limit_unit" to "MB"))["data_volume_limit_size"])
        assertEquals("2_1048576", spec.encode(mapOf("limit_value" to "2", "limit_unit" to "tb"))["data_volume_limit_size"])
    }

    @Test
    fun `只改开关时不下发限额大小`() {
        val spec = ZteGoformProfile.writeSpec(SettingKey.TRAFFIC_LIMIT)!!
        val out = spec.encode(mapOf("enabled" to false))
        assertEquals("0", out["data_volume_limit_switch"])
        assertNull("没给数值就不能覆盖设备上已有的限额", out["data_volume_limit_size"])
        assertNull(out["data_volume_limit_unit"])
        assertNull("没给自动清零就不覆盖设备上已有的", out["wan_auto_clear_flow_data_switch"])
        assertNull(out["traffic_clear_date"])
    }

    @Test
    fun `流量限额拦住非法值`() {
        val spec = ZteGoformProfile.writeSpec(SettingKey.TRAFFIC_LIMIT)!!
        assertNull(spec.validate(mapOf("enabled" to true, "limit_value" to 470, "limit_unit" to "GB")))
        assertNotNull(spec.validate(mapOf("limit_value" to 0)))
        assertNotNull(spec.validate(mapOf("limit_value" to "abc")))
        assertNotNull(spec.validate(mapOf("limit_unit" to "PB")))
        assertNotNull(spec.validate(mapOf("alert_percent" to "101")))
        assertNotNull(spec.validate(mapOf("clear_date" to "32")))
        assertNull(spec.validate(mapOf("alert_percent" to "80", "clear_date" to "31")))
    }

    @Test
    fun `接入控制名单四条都发全且末尾带分号`() {
        // 断言照 2026-08-30 真机抓包：
        // goformId=setDeviceAccessControlList&AclMode=2&WhiteMacList=&BlackMacList=2a:ed:87:b3:e8:29;
        // &WhiteNameList=&BlackNameList=OPPO-Find-X7;
        val spec = ZteGoformProfile.writeSpec(SettingKey.WIFI_ACL)!!
        assertEquals("setDeviceAccessControlList", spec.command)
        val out = spec.encode(mapOf(
            "black_macs" to listOf("2a:ed:87:b3:e8:29"),
            "black_names" to listOf("OPPO-Find-X7"),
        ))
        assertEquals("2", out["AclMode"])
        assertEquals("单项也要带结尾分号", "2a:ed:87:b3:e8:29;", out["BlackMacList"])
        assertEquals("OPPO-Find-X7;", out["BlackNameList"])
        // 设备是整表替换：空名单也必须带 key，漏发等于把那张表清空
        assertEquals("", out["WhiteMacList"])
        assertEquals("", out["WhiteNameList"])
    }

    @Test
    fun `取消拉黑是发空名单而不是换 AclMode`() {
        val spec = ZteGoformProfile.writeSpec(SettingKey.WIFI_ACL)!!
        val out = spec.encode(emptyMap())
        assertEquals("抓包里取消拉黑仍然是 2", "2", out["AclMode"])
        assertEquals("", out["BlackMacList"])
        assertEquals("", out["BlackNameList"])
    }

    @Test
    fun `接入控制名单拦住非法 MAC 与长度不齐`() {
        val spec = ZteGoformProfile.writeSpec(SettingKey.WIFI_ACL)!!
        assertNull(spec.validate(mapOf(
            "black_macs" to listOf("2a:ed:87:b3:e8:29"),
            "black_names" to listOf("OPPO-Find-X7"),
        )))
        assertNotNull("MAC 必须是六段十六进制", spec.validate(mapOf("black_macs" to listOf("2a-ed-87"))))
        assertNotNull(
            "Mac 与 Name 按下标一一对应，长度必须相等",
            spec.validate(mapOf("black_macs" to listOf("2a:ed:87:b3:e8:29"), "black_names" to emptyList<String>()))
        )
        assertNotNull(
            "名称不许含 goform 的分隔符",
            spec.validate(mapOf(
                "black_macs" to listOf("2a:ed:87:b3:e8:29"),
                "black_names" to listOf("bad;name"),
            ))
        )
    }




    @Test
    fun `每个 SettingKey 都登记了写入命令`() {
        // 阶段 2 收尾：ZTE profile 是主力设备，写入项一个都不能漏 —— 漏一个就是
        // GoformSettingWriter 里一句 warn + 静默失败（点了没反应但接口返回 200）。
        val missing = SettingKey.entries.filter { ZteGoformProfile.writeSpec(it) == null }
        assertEquals(emptyList<SettingKey>(), missing)
    }

    @Test
    fun `DHCP 写入补齐两个跟着模式走的隐含参数`() {
        val spec = ZteGoformProfile.writeSpec(SettingKey.LAN_DHCP)!!
        assertEquals("DHCP_SETTING", spec.command)
        val server = spec.encode(mapOf(
            "lan_ip" to "192.168.1.1", "lan_netmask" to "255.255.255.0",
            "dhcp_type" to "SERVER", "dhcp_start" to "192.168.1.100",
            "dhcp_end" to "192.168.1.200", "dhcp_lease" to "3600",
        ))
        assertEquals("192.168.1.1", server["lanIp"])
        assertEquals("SERVER", server["lanDhcpType"])
        assertEquals("3600", server["dhcpLease"])
        assertEquals("改了 LAN 段必须让设备重启 DHCP 服务，否则改了不生效", "1", server["dhcp_reboot_flag"])
        assertEquals("开 DHCP 服务器时清旧的 MAC-IP 绑定", "1", server["mac_ip_reset"])

        val disabled = spec.encode(mapOf("dhcp_type" to "disable"))
        assertEquals("大小写不敏感", "DISABLE", disabled["lanDhcpType"])
        assertEquals("0", disabled["mac_ip_reset"])
        // 缺参数时给的默认值与旧 route 一致
        assertEquals("192.168.0.1", disabled["lanIp"])
        assertEquals("86400", disabled["dhcpLease"])
    }

    @Test
    fun `DHCP 拦住非法地址与注入`() {
        val spec = ZteGoformProfile.writeSpec(SettingKey.LAN_DHCP)!!
        assertNotNull(spec.validate(mapOf("lan_ip" to "192.168.0.1&goformId=FACTORY_RESET")))
        assertNotNull(spec.validate(mapOf("lan_netmask" to "255.255.255")))
        assertNotNull(spec.validate(mapOf("dhcp_lease" to "1h")))
        assertNotNull(spec.validate(mapOf("dhcp_type" to "RELAY")))
        // 关闭 DHCP 时官方 UI 发的就是空串，不能拒
        assertNull(spec.validate(mapOf(
            "lan_ip" to "192.168.0.1", "dhcp_type" to "DISABLE",
            "dhcp_start" to "", "dhcp_end" to "",
        )))
    }

    // ───────── 2.6 / 2.7：请求体里的设备侧取值域收敛 ─────────

    @Test
    fun `FOTA 是正向语义_true 开启自动升级`() {
        val spec = ZteGoformProfile.writeSpec(SettingKey.FOTA_AUTO_UPDATE)!!
        assertEquals("SetUpgAutoSetting", spec.command)
        assertEquals("1", spec.encode(mapOf("value" to true))["UpgMode"])
        assertEquals("0", spec.encode(mapOf("value" to false))["UpgMode"])
        // 设备侧必填项不能漏（取值照抄真机抓包：UpgIntervalDay=1）
        assertEquals("1", spec.encode(mapOf("value" to true))["UpgIntervalDay"])
        assertEquals("0", spec.encode(mapOf("value" to true))["UpgRoamPermission"])
    }

    @Test
    fun `FOTA 读侧同样是 UpgMode 且归一成 1 与 0`() {
        assertEquals(
            JsonPrimitive("1"),
            canonicalOnly(FieldGroup.DEVICE_SETTINGS, "UpgMode" to "1")[DeviceFields.DeviceSettings.FOTA_AUTO_UPDATE],
        )
        assertEquals(
            JsonPrimitive("0"),
            canonicalOnly(FieldGroup.DEVICE_SETTINGS, "UpgMode" to "0")[DeviceFields.DeviceSettings.FOTA_AUTO_UPDATE],
        )
        // 查询列表里必须带 UpgMode，否则设备不会返回它，读侧登记也白登记
        assertTrue(ZteGoformProfile.cmdsFor(FieldGroup.DEVICE_SETTINGS).contains("UpgMode"))
    }

    @Test
    fun `锁基站把制式名换成设备侧数字 RAT 码`() {
        val spec = ZteGoformProfile.writeSpec(SettingKey.CELL_LOCK)!!
        val lte = spec.encode(mapOf("pci" to "301", "earfcn" to "1850", "network_type" to "LTE"))
        assertEquals("12", lte["rat"])
        assertEquals("301", lte["pci"])
        assertEquals("1850", lte["earfcn"])
        assertEquals("16", spec.encode(mapOf("network_type" to "NR"))["rat"])
        assertEquals("16", spec.encode(mapOf("network_type" to "5g"))["rat"])
        // 兼容期：app 侧历史上直接发数字码
        assertEquals("16", spec.encode(mapOf("network_type" to "16"))["rat"])
    }

    @Test
    fun `锁基站拦住空值与非法制式`() {
        val spec = ZteGoformProfile.writeSpec(SettingKey.CELL_LOCK)!!
        val ok = mapOf("pci" to "301", "earfcn" to "1850", "network_type" to "NR")
        assertNull(spec.validate(ok))
        assertNotNull(spec.validate(ok - "pci"))
        assertNotNull(spec.validate(ok + ("earfcn" to "")))
        // 设备侧表单是字符串拼接，pci 里带 & 会注入额外参数
        assertNotNull(spec.validate(ok + ("pci" to "301&goformId=FACTORY_RESET")))
        // 认不出的制式必须挡住，不能猜一个下发
        assertNotNull(spec.validate(ok + ("network_type" to "WCDMA")))
    }

    @Test
    fun `卡槽序号换成设备侧运营商预置位`() {
        val spec = ZteGoformProfile.writeSpec(SettingKey.SIM_SLOT)!!
        assertEquals("0", spec.encode(mapOf("value" to "1"))["sim_slot"])
        assertEquals("1", spec.encode(mapOf("value" to 2))["sim_slot"])
        assertEquals("2", spec.encode(mapOf("value" to "3"))["sim_slot"])
        assertEquals("11", spec.encode(mapOf("value" to "external"))["sim_slot"])
        // 旧客户端的设备原值不在这里放行（取值域与新序号重叠，换算留在 route）
        assertNotNull(spec.validate(mapOf("value" to "11")))
        assertNotNull(spec.validate(mapOf("value" to "0")))
        assertNotNull(spec.validate(mapOf("value" to "4")))
    }

    @Test
    fun `流量校准给未校准的那个量补零`() {
        val spec = ZteGoformProfile.writeSpec(SettingKey.FLOW_CALIBRATION)!!
        val data = spec.encode(mapOf("target" to "data", "value" to "1024"))
        assertEquals("data", data["calibration_way"])
        assertEquals("1024", data["data"])
        assertEquals("0", data["time"])
        val time = spec.encode(mapOf("target" to "time", "value" to "60"))
        assertEquals("60", time["time"])
        assertEquals("0", time["data"])
        assertNotNull(spec.validate(mapOf("target" to "both")))
        assertNotNull(spec.validate(mapOf("target" to "data", "value" to "1024&x=1")))
    }

    @Test
    fun `网络模式把别名映射成大小写敏感的 BearerPreference`() {
        val spec = ZteGoformProfile.writeSpec(SettingKey.NETWORK_MODE)!!
        assertEquals("Only_5G", spec.encode(mapOf("value" to "5G_ONLY"))["BearerPreference"])
        assertEquals("WL_AND_5G", spec.encode(mapOf("value" to "AUTO"))["BearerPreference"])
        // 幂等：已经映射过的调用方不会被二次映射搞坏
        assertEquals("Only_5G", spec.encode(mapOf("value" to "Only_5G"))["BearerPreference"])
        // 映射不出设备支持的值就拒绝下发，而不是把猜的值丢给设备
        assertNotNull(spec.validate(mapOf("value" to "NSA")))
        assertNull(spec.validate(mapOf("value" to "only_lte")))
    }


    @Test
    fun `已登记的写入项 command 不重复且非空`() {
        val cmds = SettingKey.entries.mapNotNull { ZteGoformProfile.writeSpec(it) }.map { it.command }
        assertTrue(cmds.all { it.isNotBlank() })
        assertEquals(cmds.distinct(), cmds)
    }

    // ───────────────────────── profile 元信息 ─────────────────────────

    @Test
    fun `profile id 稳定`() {
        // 配置里按 id 选 profile，改名会让现有部署选不到设备
        assertEquals("zte-goform", ZteGoformProfile.id)
    }

    @Test
    fun `spec 列表只构建一次`() {
        assertTrue("readSpecs 每次新建列表会让归一化在热路径上反复分配", specs === ZteGoformProfile.readSpecs())
    }

    @Test
    fun `每个非空分组都有 spec 登记`() {
        val groupsWithCmds = FieldGroup.entries.filter { ZteGoformProfile.cmdsFor(it).isNotEmpty() }
        val empty = groupsWithCmds.filter { groupOf(it).isEmpty() }
        assertEquals("有查询但没有任何字段登记 = 查了白查", emptyList<FieldGroup>(), empty)
    }

    @Test
    fun `spec 的 group 与所在分组一致`() {
        val mismatched: List<FieldSpec> = FieldGroup.entries.flatMap { g -> groupOf(g).filter { it.group != g } }
        assertTrue(mismatched.isEmpty())
    }
}
