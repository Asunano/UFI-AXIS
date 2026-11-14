package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.FieldSpec
import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.deviceschema.WriteSpec
import com.ufi_axis_core.deviceschema.profile.ZteGoformProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守门测试：**读命令表的内容冻结** + **排障开关不打瘫只读面**。
 *
 * ## 为什么从「两份表比对」转型成「内容冻结」（阶段 0.4b）
 *
 * 0.4a 时读命令表有**两份**：客户端里的 `*_FALLBACK_CMDS`（关掉归一化时唯一的命令来源）
 * 与 profile 的 `cmdsFor(group)`。本类当时的职责是逐组比对这两份、把分叉钉出来 ——
 * 因为分叉的后果是**同一个端点在排障模式与正常模式下向设备发不同的 cmd**。
 *
 * 0.4b 已经把 `GoformFieldMapper.cmds()` 切到非空的 `commandProfile`，并删掉了
 * 客户端那 6 个 fallback 常量与 `FALLBACK_CMDS` 汇总表 —— **比对对象消失了**。
 * 如果只是把旧断言里的 fallback 换成 `cmdsFor()`，那就变成 `cmdsFor(g) == cmdsFor(g)` 的恒真式，
 * 是一条空转的测试。
 *
 * 所以改成**内容冻结**：把每一组的 cmd 列表（含**顺序**）逐字写死在本文件里。
 * 这样收益有两层：
 *
 * 1. 命令表现在只有一份，没有「谁跟谁分叉」的问题了，但「有人手滑改了一个 cmd 名」
 *    仍然是静默的设备侧行为变更（发错 cmd 不会编译失败、不会抛异常，只会让某个字段悄悄没值）。
 *    冻结期望值是这类改动唯一的自动拦截手段。
 * 2. 期望值必须**显式更新**才能让测试重新变绿 —— 而更新的那一刻，改动者就被迫想一下
 *    「计划书 §16 的真机基线（2026-09-22，85 registered / 67 hit）要不要重抓」。
 *    命令表变了，`queried` / `hit` / `hit_source` 就可能跟着变，那是 §14.3 的判据 2/3/4。
 *
 * ## 冻结值的来源
 *
 * 这些列表就是 0.4b 搬运**之前**客户端实际发出去的那几份（`*_FALLBACK_CMDS` 与
 * `getSignalInfo()` / `getFullStatus()` 的字面量），已在搬运前逐组逐字核对与 `cmdsFor()` 一致。
 * 字段名的核对依据是计划书 §16 的真机基线（2026-09-22）。
 *
 * **看到像 bug 的东西先读 profile 的注释**，尤其这两处（掰回去就是把功能改坏，见 §16）：
 * - `CELL_INFO` / `SIGNAL` 里的 `Lte_snr` 是**设备真名**；小写 `lte_snr` 是 core 自有的
 *   canonical（`DeviceFields.CellInfo.LTE_SNR`），**不许抄进任何 cmd 列表**（P0-3 就是这么来的）。
 * - `WIFI_SETTINGS` 里**没有** `WiFiModuleSwitch`：那是设备的**响应键**，不是可发的 cmd。
 */
class GoformCommandTableGuardTest {

    /**
     * 全部 10 个 [FieldGroup] 的 cmd 列表冻结值（顺序即发送顺序）。
     *
     * 新增 `FieldGroup` 时这张表必须同步（下面有一条断言双向钉住键集），
     * 且**新增枚举值会让 `/api/diagnose?fields=1` 的 `field_coverage` 多一个块** ——
     * 那一步要先抓基线再改，见 `DeviceProfile.fullStatusCmds` 的 KDoc 与计划书 §16 末尾。
     */
    private val frozenCmdTables: Map<FieldGroup, List<String>> = mapOf(
        FieldGroup.DEVICE_SETTINGS to listOf(
            "indicator_light_switch", "performance_mode",
            "roam_setting_option", "dial_roam_setting_option",
            "net_select", "lte_band_lock", "nr_band_lock",
            "usb_port_switch", "samba_switch",
            "restart_schedule_switch", "restart_time",
            "sleep_sysIdleTimeToSleep",
            "usb_network_protocal", "BearerPreference", "connection_mode",
            "UpgMode",
        ),
        FieldGroup.LAN_SETTINGS to listOf(
            "lan_ipaddr", "lan_netmask", "mac_address", "dhcpEnabled",
            "dhcpStart", "dhcpEnd", "dhcpLease_hour", "mtu", "tcp_mss",
        ),
        FieldGroup.BAND_STATUS to listOf("lte_band_lock", "nr_band_lock"),
        FieldGroup.TRAFFIC_LIMIT to listOf(
            "flux_data_volume_limit_switch", "data_volume_limit_switch",
            "data_volume_limit_unit", "data_volume_limit_size",
            "data_volume_alert_percent",
            "monthly_tx_bytes", "monthly_rx_bytes", "monthly_time",
            "wan_auto_clear_flow_data_switch", "traffic_clear_date",
        ),
        FieldGroup.IDENTITY to listOf(
            "msisdn", "imei", "imsi", "iccid", "sim_imsi",
            "hardware_version", "web_version", "wa_version", "cr_version", "wa_inner_version",
            "lan_ipaddr", "mac_address", "wan_ipaddr", "ipv6_wan_ipaddr", "LocalDomain",
            "ppp_status", "network_type", "rssi", "pdp_type", "opms_wan_mode",
        ),
        FieldGroup.CONNECTION to listOf("network_type", "network_provider", "ppp_status"),
        // 前 12 项 = GoformWifiClient.getWifiSettings()，后 2 项 = getWifiModuleInfo() 的容器命令。
        // 线上仍是**两次独立请求**（合成一次会改请求形状，见那两个方法上的注释）；
        // 这里的并集是覆盖率诊断实际发出的那一份。
        FieldGroup.WIFI_SETTINGS to listOf(
            "wifi_chip1_ssid1_ssid", "wifi_onoff_state", "wifi_access_sta_num",
            "wifi_chip1_ssid1_access_sta_num", "wifi_5g_enable", "wifi_enable",
            "wifi_chip1_ssid1_passphrase", "wifi_chip",
            "wifi_chip1_ssid1_auth_mode", "wifi_chip1_ssid1_encryp_type",
            "wifi_chip1_ssid1_max_sta_num", "wifi_chip1_ssid1_broadcast_ssid",
            "queryWiFiModuleSwitch", "queryAccessPointInfo",
        ),
        FieldGroup.WIFI_CLIENTS to listOf("station_list"),
        FieldGroup.CELL_INFO to listOf(
            "neighbor_cell_info", "locked_cell_info", "network_information",
            "network_type",
            "Lte_pci", "Lte_fcn", "Lte_bands",
            "lte_rsrp", "lte_rsrq", "Lte_snr",
        ),
        FieldGroup.SIGNAL to listOf(
            "network_type", "network_provider", "rssi", "signalbar", "ppp_status",
            "network_information",
            "lte_rsrp", "Lte_snr", "lte_rsrq", "lte_rssi",
            "cell_id", "Lte_pci", "neighbor_cell_info", "Lte_ca_status",
            "realtime_tx_thrpt", "realtime_rx_thrpt",
        ),
    )

    /**
     * `getFullStatus()` 的三批冻结值（0.4b 从 `GoformSignalClient` 搬进
     * `ZteGoformProfile.fullStatusCmds()`，逐字照搬）。
     *
     * 外层是**批次**：线上是三次独立请求，一次发 96 项会被设备截断/返回空。
     */
    private val frozenFullStatusBatches: List<List<String>> = listOf(
        listOf(
            "network_signalbar", "network_rssi", "network_type", "network_provider",
            "ppp_status", "lan_ipaddr", "mac_address", "imei", "imsi", "iccid",
            "wifi_onoff_state", "wifi_access_sta_num", "cr_version",
            "msisdn", "sim_msisdn", "sim_imsi", "ipv6_wan_ipaddr",
            "hardware_version", "web_version", "wa_version", "wa_inner_version",
            "LocalDomain", "wan_ipaddr", "static_wan_ipaddr",
            "pdp_type", "pdp_type_ui", "ipv6_pdp_type", "ipv6_pdp_type_ui",
            "opms_wan_mode", "opms_wan_auto_mode",
        ),
        listOf(
            "realtime_tx_bytes", "realtime_rx_bytes", "monthly_tx_bytes", "monthly_rx_bytes",
            "realtime_time", "monthly_time", "realtime_rx_thrpt", "realtime_tx_thrpt",
            "battery_value", "battery_vol_percent", "battery_charging",
            "sms_received_flag", "sms_unread_num", "sms_sim_unread_num",
            "data_volume_limit_switch", "data_volume_alert_percent", "data_volume_limit_size",
            "loginfo", "pin_status", "simcard_roam", "usb_port_switch",
            "wifi_chip1_ssid1_ssid", "wifi_5g_enable", "roam_setting_option",
            "Lte_ca_status", "new_version_state", "current_upgrade_state",
            "sim_slot", "dual_sim_support",
        ),
        listOf(
            "Z5g_rsrp", "Z5g_snr", "Z5g_SINR", "rssi", "rscp",
            "wan_lte_ca", "lte_ca_pcell_band", "lte_ca_pcell_bandwidth",
            "lte_ca_scell_band", "lte_ca_scell_bandwidth",
            "lte_ca_pcell_arfcn", "lte_ca_scell_arfcn", "lte_multi_ca_scell_info",
            "wan_active_band",
            "apn_interface_version",
            "wifi_chip1_ssid1_max_access_num", "wifi_chip1_ssid1_auth_mode",
            "wifi_chip1_ssid1_password_encode", "wifi_chip1_ssid1_switch_onoff",
            "wifi_chip1_ssid1_wifi_coverage",
            "wifi_chip2_ssid1_ssid", "wifi_chip2_ssid1_auth_mode",
            "wifi_chip2_ssid1_password_encode", "wifi_chip2_ssid1_max_access_num",
            "wifi_chip2_ssid1_switch_onoff",
            "wifi_chip1_ssid2_ssid", "wifi_chip2_ssid2_ssid",
            "wifi_chip1_ssid2_max_access_num", "wifi_chip2_ssid2_max_access_num",
            "wifi_chip1_ssid2_switch_onoff", "wifi_chip2_ssid2_switch_onoff",
            "m_ssid_enable", "m_SSID2", "m_HideSSID",
            "wifi_lbd_enable", "guest_switch",
            "station_ip_addr",
        ),
    )

    // ─────────────────── 1. 命令表内容冻结 ───────────────────

    /**
     * 冻结表必须覆盖**全部** `FieldGroup`：新增一个组却忘了登记期望值，
     * 那一组的命令表就没有任何冻结保护。
     */
    @Test
    fun `冻结表必须覆盖全部 FieldGroup`() {
        assertEquals(
            "新增 FieldGroup 必须同步本测试的冻结表；同时注意它会改变 field_coverage 的组数（§16）",
            FieldGroup.entries.toSet(),
            frozenCmdTables.keys,
        )
    }

    /**
     * 逐组逐字（含顺序）冻结 `cmdsFor()`。
     *
     * 红了怎么办：**先判断改动是不是刻意的**。是刻意的 → 更新本文件的期望值，
     * 并按计划书 §14.3 重抓一份 `field_coverage` 与 §16 基线比对（判据 2/3/4）；
     * 不是刻意的 → 那就是手滑改了要发给设备的 cmd 名，回滚。
     */
    @Test
    fun `cmdsFor 逐组逐字冻结`() {
        for ((group, expected) in frozenCmdTables) {
            assertEquals("$group 的命令表变了（含顺序）—— 这是设备侧请求形状的变更", expected, ZteGoformProfile.cmdsFor(group))
        }
    }

    /**
     * `soloCmds()` 也要冻结：它表达「哪些 cmd 不能与同组其它 cmd 合并发」，
     * 改错的后果是设备对那一批返回空（`station_list` 就是这个先例）。
     */
    @Test
    fun `soloCmds 只有 WIFI_CLIENTS 的 station_list`() {
        for (group in FieldGroup.entries) {
            val expected = if (group == FieldGroup.WIFI_CLIENTS) listOf("station_list") else emptyList()
            assertEquals("$group 的 soloCmds 变了", expected, ZteGoformProfile.soloCmds(group))
        }
    }

    /**
     * `fullStatusCmds()` 的三批冻结：数量 **30 / 29 / 37 = 96**，内容与顺序逐字。
     *
     * 这条同时钉住了 0.4b 的搬运是**逐字**的（搬运前的字面量就是这份期望值）。
     */
    @Test
    fun `fullStatusCmds 三批逐字冻结`() {
        val actual = ZteGoformProfile.fullStatusCmds()
        assertEquals("批次数变了 = 设备侧请求次数变了", 3, actual.size)
        assertEquals(listOf(30, 29, 37), actual.map { it.size })
        assertEquals(96, actual.sumOf { it.size })
        assertEquals(frozenFullStatusBatches, actual)
    }

    /**
     * 把 `CELL_INFO` / `SIGNAL` 钉到**字符级**：两组末位的 SNR cmd 都必须是设备真名 `Lte_snr`。
     *
     * 只靠上面「整组逐字」的断言，在**同时**把期望值与实现抄错成同一个错名时不会红；
     * 而 `lte_snr` 恰好是个极易抄错的名字 —— 它是 core 自有的 canonical
     * （`DeviceFields.CellInfo.LTE_SNR`），设备上**没有**这个键（P0-3 的根因，见 §16）。
     */
    @Test
    fun `命令表里不许出现小写 lte_snr`() {
        val cellInfo = ZteGoformProfile.cmdsFor(FieldGroup.CELL_INFO)
        val signal = ZteGoformProfile.cmdsFor(FieldGroup.SIGNAL)
        assertEquals("Lte_snr", cellInfo.last())
        assertTrue("SIGNAL 也必须发设备真名 Lte_snr", "Lte_snr" in signal)
        for (group in FieldGroup.entries) {
            assertFalse(
                "$group 的 cmd 列表里出现了小写 lte_snr —— 那是 core 的 canonical，设备上没有这个键",
                "lte_snr" in ZteGoformProfile.cmdsFor(group),
            )
        }
    }

    // ───────────── 2. 命令表真的切到 commandProfile 了 ─────────────

    /**
     * `cmds()` 取的是 [commandProfile] —— 0.4b 的核心行为变更，这条正面证明它切过去了。
     *
     * 手法：给 `commandProfile` 传一个能被认出来的假 profile，`normalizeProfile` 那边传真的。
     * 结果必须是假 profile 的 cmd —— 如果拿到 `ZteGoformProfile` 那份，说明 `cmds()` 又被
     * 接回 `normalizeProfile` 了（那会让关掉归一化时只读面全哑）。
     */
    @Test
    fun `cmds 取 commandProfile 而不是 normalizeProfile`() {
        val marker = MarkerProfile()
        val mapper = GoformFieldMapper(normalizeProfile = ZteGoformProfile, commandProfile = marker)
        for (group in FieldGroup.entries) {
            assertEquals(
                "$group 的 cmds 没有来自 commandProfile",
                listOf(MarkerProfile.MARKER_CMD),
                mapper.cmds(group),
            )
        }
        assertEquals(
            "fullStatusCmds 也必须来自 commandProfile",
            listOf(listOf(MarkerProfile.MARKER_FULL_STATUS_CMD)),
            mapper.fullStatusCmds(),
        )
    }

    /**
     * 排障开关回归（`field_normalization_enabled=false` → `normalizeProfile = null`）：
     * 诊断口径如实报「已关」，**但 `cmds()` 仍然非空** —— 只读面不许被打瘫。
     *
     * 这就是计划书 §4「字段归一化可以关，命令表不能关」的可执行版本。
     * 0.4a 时这条断言的形状是「仍然返回客户端 fallback」，fallback 删掉之后改成
     * 「仍然返回 `commandProfile` 的登记表」—— 保护的不变量一字未变。
     */
    @Test
    fun `关掉归一化后 cmds 仍然非空且等于 commandProfile 的登记表`() {
        val mapper = GoformFieldMapper(normalizeProfile = null, commandProfile = ZteGoformProfile)
        assertFalse("normalizeProfile=null 就是「归一化已关」", mapper.enabled)
        assertNull("profileId 必须跟着报 null，否则 /api/diagnose 会永远报 true", mapper.profileId)
        for (group in FieldGroup.entries) {
            val cmds = mapper.cmds(group)
            assertTrue("$group 关归一化后拿不到 cmd = 该端点直接哑掉", cmds.isNotEmpty())
            assertEquals("$group 关归一化后必须走 commandProfile 的命令表", ZteGoformProfile.cmdsFor(group), cmds)
        }
        assertEquals(
            "关归一化后诊断 dump 也不许发不出去",
            ZteGoformProfile.fullStatusCmds(),
            mapper.fullStatusCmds(),
        )
    }

    /**
     * 关掉归一化时 `coverageReport()` 的短路必须保留：**一条查询都不许发**。
     *
     * 覆盖率报告的语义是「**当前生效的那份 profile** 登记了什么、命中了什么」，所以它
     * （以及它内部的 `soloCmds` 分批）走的是 `normalizeProfile`，与业务查询路径分开 ——
     * 见 `GoformFieldMapper.queryGroup` 的 KDoc。改成拿 `commandProfile` 兜底的话，
     * 排障模式下 `/api/diagnose?fields=1` 会开始真打设备，那是行为变更。
     */
    @Test
    fun `关掉归一化时 coverageReport 不向设备发查询`() {
        val mapper = GoformFieldMapper(normalizeProfile = null, commandProfile = ZteGoformProfile)
        var queries = 0
        val report: JsonObject = runBlocking {
            mapper.coverageReport { queries++; null }
        }
        assertEquals("关归一化时 coverageReport 必须短路，不能向设备发查询", 0, queries)
        assertEquals("false", report["normalization_enabled"]?.jsonPrimitive?.content)
        assertTrue("短路时要给出原因，不能回一个空壳", report.containsKey("hint"))
        assertFalse("短路时不该出现逐组结果", report.containsKey("groups"))
    }

    /**
     * 开着归一化时 `coverageReport()` 用的是 `normalizeProfile` 的命令表（诊断语义），
     * 与 `cmds()` 用 `commandProfile`（业务语义）分属两条路径。
     *
     * 手法：`commandProfile` 塞假 profile，真 profile 放 `normalizeProfile`；
     * 发出去的 cmd 里**不该**出现 marker，且组数必须仍是 `FieldGroup` 的数量
     * （组数 = `/api/diagnose?fields=1` 里 `field_coverage` 的块数，是 §16 基线的硬判据）。
     */
    @Test
    fun `coverageReport 走 normalizeProfile 的命令表`() {
        val mapper = GoformFieldMapper(normalizeProfile = ZteGoformProfile, commandProfile = MarkerProfile())
        val sent = mutableListOf<String>()
        val report: JsonObject = runBlocking {
            mapper.coverageReport { cmds -> sent += cmds; null }
        }
        assertFalse("覆盖率诊断把 commandProfile 的 cmd 发出去了", MarkerProfile.MARKER_CMD in sent)
        assertTrue("覆盖率诊断该发真 profile 的 cmd", "station_list" in sent)
        val groups = report["groups"] as? JsonObject
        assertEquals(
            "field_coverage 的组数变了 —— 直接冲掉 §16 的真机基线",
            FieldGroup.entries.size,
            groups?.size,
        )
    }

    /**
     * 只为「区分两个 profile」而存在的假 profile：命令表一律返回不可能来自真设备的 cmd。
     * 其余成员都是 [DeviceProfile] 的必填项，按最小实现填。
     */
    private class MarkerProfile : DeviceProfile {
        override val id: String = "marker-profile"
        override val displayName: String = "仅用于测试的标记 profile"
        override fun readSpecs(): List<FieldSpec> = emptyList()
        override fun cmdsFor(group: FieldGroup): List<String> = listOf(MARKER_CMD)
        override fun fullStatusCmds(): List<List<String>> = listOf(listOf(MARKER_FULL_STATUS_CMD))
        override fun writeSpec(key: SettingKey): WriteSpec? = null

        companion object {
            const val MARKER_CMD = "__marker_cmd__"
            const val MARKER_FULL_STATUS_CMD = "__marker_full_status_cmd__"
        }
    }
}
