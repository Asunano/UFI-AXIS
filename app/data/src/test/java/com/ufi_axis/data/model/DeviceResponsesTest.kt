@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ufi_axis.data.model

import com.ufi_axis.util.AppJson
import com.ufi_axis_core.contract.DeviceFields
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备类端点强类型响应的解析约定（设备适配层计划书 4.3）。
 *
 * 三件事必须锁死，坏掉都是「界面静默显示错值」而不是崩溃：
 *  ① `@SerialName` 与 [DeviceFields] 的冻结清单逐字对齐 —— 手改成字面量就会漂移；
 *  ② 字段缺失解析成 `null`（契约规定「缺失 = 省略 key」），不能抛异常；
 *  ③ 容器字段的「数组 / 数组字符串」两种形态都要能解（归一化总开关关掉时会出现双重编码）。
 */
class DeviceResponsesTest {

    // ───────── ① 键名与 contract 冻结清单对齐 ─────────

    @Test
    fun `设备设置的键与 DeviceFields 逐字对齐`() {
        val expected = DeviceFields.DeviceSettings.ALL + listOf("network_mode_label")
        assertEquals(
            expected,
            DeviceSettingsResponse.serializer().descriptor.elementNames.toList(),
        )
    }

    @Test
    fun `LAN 设置的键与 DeviceFields 逐字对齐`() {
        assertEquals(
            DeviceFields.LanSettings.ALL,
            LanSettingsResponse.serializer().descriptor.elementNames.toList(),
        )
    }

    @Test
    fun `WiFi 设置的键与 DeviceFields 逐字对齐`() {
        assertEquals(
            DeviceFields.WifiSettings.ALL,
            WifiSettingsResponse.serializer().descriptor.elementNames.toList(),
        )
    }

    @Test
    fun `WiFi 客户端 频段锁定 小区信息的键与 DeviceFields 逐字对齐`() {
        assertEquals(
            DeviceFields.WifiClients.ALL,
            WifiClientsResponse.serializer().descriptor.elementNames.toList(),
        )
        assertEquals(
            DeviceFields.BandStatus.ALL,
            BandStatusResponse.serializer().descriptor.elementNames.toList(),
        )
        assertEquals(
            DeviceFields.CellInfo.ALL,
            CellInfoResponse.serializer().descriptor.elementNames.toList(),
        )
    }

    // ───────── ② 字段缺失 = null，多余键忽略 ─────────

    @Test
    fun `空对象能解析且全部字段为 null`() {
        val s = AppJson.decodeFromString<DeviceSettingsResponse>("{}")
        assertNull(s.indicatorLight)
        assertNull(s.networkMode)
        assertFalse("布尔派生属性在缺失时是 false，不是抛异常", s.indicatorLightOn)

        assertNull(AppJson.decodeFromString<LanSettingsResponse>("{}").lanIp)
        assertNull(AppJson.decodeFromString<WifiSettingsResponse>("{}").ssid)
        assertNull(AppJson.decodeFromString<WifiClientsResponse>("{}").stationList)
        assertNull(AppJson.decodeFromString<BandStatusResponse>("{}").lteBandLock)
        assertNull(AppJson.decodeFromString<CellInfoResponse>("{}").ltePci)
    }

    @Test
    fun `响应里多出未登记的键不影响解析`() {
        // 归一化总开关关掉（D7 排障后门）时 core 会原样透出设备字段，必须容忍
        val json = """{"indicator_light_switch":"1","wifi_enable":"1","some_new_firmware_key":"x"}"""
        assertTrue(AppJson.decodeFromString<DeviceSettingsResponse>(json).indicatorLightOn)
    }

    // ───────── ③ 容器字段两种形态 ─────────

    @Test
    fun `客户端列表 真数组与数组字符串两种形态都能解`() {
        val asArray = """{"station_list":[{"hostname":"a"}],"lan_station_list":[{"hostname":"b"}]}"""
        val parsedArray = AppJson.decodeFromString<WifiClientsResponse>(asArray)
        assertEquals(1, parsedArray.stations?.size)
        assertEquals(1, parsedArray.lanStations?.size)
        assertEquals("WiFi 侧 + LAN 侧相加才是界面口径", 2, parsedArray.allStations.size)

        val doubleEncoded = """{"station_list":"[{\"hostname\":\"a\"},{\"hostname\":\"b\"}]"}"""
        val parsedString = AppJson.decodeFromString<WifiClientsResponse>(doubleEncoded)
        assertEquals(2, parsedString.stations?.size)
        assertNull("另一个列表缺失时是 null 而不是空数组", parsedString.lanStations)
    }

    @Test
    fun `容器字段是非数组字符串时不炸只当没有`() {
        val parsed = AppJson.decodeFromString<CellInfoResponse>("""{"locked_cell_info":""}""")
        assertNull(parsed.lockedCells)
    }

    @Test
    fun `小区元素键沿用 core 归一后的名字`() {
        val parsed = AppJson.decodeFromString<CellInfoResponse>(
            """{"neighbor_cell_info":[{"pci":"301","earfcn":"1850","sinr":"12"}]}"""
        )
        val item = parsed.neighbors!![0] as JsonObject
        assertTrue(item.containsKey(DeviceFields.CellInfo.ITEM_PCI))
        assertTrue(item.containsKey(DeviceFields.CellInfo.ITEM_EARFCN))
        assertTrue(item.containsKey(DeviceFields.CellInfo.ITEM_SINR))
    }

    // ───────── 派生属性的取值域 ─────────

    @Test
    fun `DHCP 开关认 SERVER 也认 1`() {
        assertTrue(AppJson.decodeFromString<LanSettingsResponse>("""{"dhcpEnabled":"SERVER"}""").dhcpOn)
        assertTrue(AppJson.decodeFromString<LanSettingsResponse>("""{"dhcpEnabled":"1"}""").dhcpOn)
        assertFalse(AppJson.decodeFromString<LanSettingsResponse>("""{"dhcpEnabled":"0"}""").dhcpOn)
    }

    @Test
    fun `租约优先读秒 只有小时字段时才乘 3600`() {
        // core 刻意不做这个换算（在 core 换会变成双重换算），换算责任在客户端
        assertEquals(
            7200,
            AppJson.decodeFromString<LanSettingsResponse>("""{"dhcpLease":"7200","dhcpLease_hour":"1"}""").leaseSeconds,
        )
        assertEquals(
            3600,
            AppJson.decodeFromString<LanSettingsResponse>("""{"dhcpLease_hour":"1"}""").leaseSeconds,
        )
        assertNull(AppJson.decodeFromString<LanSettingsResponse>("{}").leaseSeconds)
    }

    @Test
    fun `频段锁定 0 与 all 都表示未锁定`() {
        assertTrue(AppJson.decodeFromString<BandStatusResponse>("""{"lte_band_lock":"0"}""").lteBands.isEmpty())
        assertTrue(AppJson.decodeFromString<BandStatusResponse>("""{"nr_band_lock":"all"}""").nrBands.isEmpty())
        assertEquals(
            setOf(1, 3, 5),
            AppJson.decodeFromString<BandStatusResponse>("""{"lte_band_lock":"1,3,5"}""").lteBands,
        )
    }

    @Test
    fun `WiFi 总开关只看归一后的 WiFiModuleSwitch`() {
        assertTrue(AppJson.decodeFromString<WifiSettingsResponse>("""{"WiFiModuleSwitch":"1"}""").enabled)
        // 设备侧同义字段 core 已不再输出，读到也不认（阶段 4.1 DROP）
        assertFalse(AppJson.decodeFromString<WifiSettingsResponse>("""{"wifi_enable":"1"}""").enabled)
    }

    @Test
    fun `生效频段只有 chip2 算 5G`() {
        assertEquals("chip2", AppJson.decodeFromString<WifiSettingsResponse>("""{"wifi_chip":"chip2"}""").activeChip)
        // 部分固件填 "1"，按 2.4G 处理
        assertEquals("chip1", AppJson.decodeFromString<WifiSettingsResponse>("""{"wifi_chip":"1"}""").activeChip)
        assertEquals("chip1", AppJson.decodeFromString<WifiSettingsResponse>("{}").activeChip)
    }

    @Test
    fun `漫游两个字段任一为真即为开`() {
        // roam_setting_option 与 dial_roam_setting_option 是同义字段，部分固件只填后者
        val onlyDial = """{"roam_setting_option":"off","dial_roam_setting_option":"on"}"""
        assertTrue(AppJson.decodeFromString<DeviceSettingsResponse>(onlyDial).roamingEnabled)
        assertFalse(
            AppJson.decodeFromString<DeviceSettingsResponse>("""{"roam_setting_option":"off"}""").roamingEnabled,
        )
    }

    @Test
    fun `网络模式优先 net_select 无 net_select 才回退 BearerPreference`() {
        // 真机：切换后 goform 回读里变化的是 net_select；BearerPreference 是写入侧字段
        assertEquals(
            "LTE_ONLY",
            AppJson.decodeFromString<DeviceSettingsResponse>(
                """{"BearerPreference":"WCDMA_AND_LTE_AND_5G","net_select":"LTE_ONLY"}"""
            ).networkMode,
        )
        assertEquals(
            "WCDMA_AND_LTE_AND_5G",
            AppJson.decodeFromString<DeviceSettingsResponse>(
                """{"BearerPreference":"WCDMA_AND_LTE_AND_5G"}"""
            ).networkMode,
        )
        assertEquals(
            "LTE_ONLY",
            AppJson.decodeFromString<DeviceSettingsResponse>("""{"net_select":"LTE_ONLY"}""").networkMode,
        )
    }

    @Test
    fun `连接模式手动的三种固件写法都认`() {
        listOf("manual", "1", "hand", "MANUAL").forEach { v ->
            assertTrue(
                "connection_mode=$v 应判为手动",
                AppJson.decodeFromString<DeviceSettingsResponse>("""{"connection_mode":"$v"}""").manualConnection,
            )
        }
        assertFalse(
            AppJson.decodeFromString<DeviceSettingsResponse>("""{"connection_mode":"auto"}""").manualConnection,
        )
        assertFalse("缺失时按自动", AppJson.decodeFromString<DeviceSettingsResponse>("{}").manualConnection)
    }

    // ───────── 服务小区统一字段（计划书 1.10 / D2） ─────────

    @Test
    fun `信号响应带服务小区统一字段 制式由 band_label 判定`() {
        val nr = AppJson.decodeFromString<SignalInfo>(
            """{"rsrp":-85,"band":"78","band_label":"N78","arfcn":633984,"pci":301,"signal_strength":-60}"""
        )
        assertEquals("N78", nr.band_label)
        assertEquals(633984L, nr.arfcn)
        assertEquals(301, nr.pci)
        assertTrue(nr.isNr)

        val lte = AppJson.decodeFromString<SignalInfo>("""{"band":"3","band_label":"B3","arfcn":1850}""")
        assertFalse(lte.isNr)

        // 带宽固件普遍不填 → 省略 key → null（不能是 0，0 会被当成「带宽 0」显示）
        assertNull(nr.band_width)
    }

    /**
     * 2026-10-11：`bandLabelDisplay` 只在**显示层**转大写，`band_label` 原值不动。
     *
     * 这条区分很要紧：`band_label` 的小写 `n` 被当作**判据**使用 ——
     * [SignalInfo.isNr] 用 `startsWith("n")` 判 5G，core 的 `ServingCell.derive`
     * 与 SignalCollectorTest / ZteGoformProfileTest 也按 `n78` 断言。
     * 所以正确做法是"值不变、显示转大写"，而不是去改 core 拼出来的字符串。
     */
    @Test
    fun `频段由 core 拼大写 isNr 判定大小写不敏感`() {
        val nr = AppJson.decodeFromString<SignalInfo>("""{"band_label":"N78","rsrp":-85}""")
        assertEquals("N78", nr.bandLabelDisplay)
        // core 源头已拼大写（ServingCell.derive 用 "N$num"），客户端不再转大小写
        assertEquals("N78", nr.band_label)
        // 判据不挑大小写 —— 这是"在源头改大小写"能成立的前提，必须钉住
        assertTrue(nr.isNr)
        // 即便设备/旧 core 送来小写 n78，判定仍是 5G（向后兼容）
        assertTrue(AppJson.decodeFromString<SignalInfo>("""{"band_label":"n78"}""").isNr)

        // LTE 本来就是大写 B，转大写后不变，且不误判成 5G
        val lte = AppJson.decodeFromString<SignalInfo>("""{"band_label":"B3"}""")
        assertEquals("B3", lte.bandLabelDisplay)
        assertFalse(lte.isNr)

        // 空白 / 缺字段 → null，让调用方走各自的「无数据」处理，不显示成空字符串
        assertNull(AppJson.decodeFromString<SignalInfo>("""{"band_label":"  "}""").bandLabelDisplay)
        assertNull(AppJson.decodeFromString<SignalInfo>("""{}""").bandLabelDisplay)
    }
}
