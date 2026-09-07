package com.ufi_axis.data.model

import com.ufi_axis.util.AppJson
import com.ufi_axis.util.DebugLog
import com.ufi_axis_core.contract.DeviceFields
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

// ══════════════════════════════════════════════════════════════════════════════
// 设备类端点的强类型响应（设备适配层计划书 4.3）
//
// 【为什么现在能建 data class】
// 这些端点历史上是 goform 原样透传，字段名随固件漂移，所以 app 只能收裸 JsonElement
// + 到处硬编码键名。阶段 1~4.1 之后 core 已做**默认拒绝的白名单归一化**：响应里只可能
// 出现 DeviceFields 登记的 canonical key，设备侧别名一律被 core 吸收。键集合既然冻结，
// 就该用类型表达 —— 键名只在本文件出现一次（@SerialName 直接引用 DeviceFields 常量），
// UI 只读属性。
//
// 【三条约定】
// ① 全部字段可空、默认 null。契约规定「字段缺失 = 省略 key」，加上 AppJson 的
//    ignoreUnknownKeys，缺字段解析成 null 而不是抛异常。
// ② 值域是字符串（goform 时代遗留），布尔是 "1"/"0"，个别字段 "on"/"off" —— 所以布尔
//    语义统一走 DeviceFields.Bool.isTrue 的派生属性，不要在 UI 里各写一遍比较。
// ③ 容器字段保持 JsonElement。station_list / locked_cell_info 这类值「可能是数组、也可能
//    是数组的 JSON 字符串」，**形状本身是契约**。正常路径下 core 已统一成真数组，但归一化
//    总开关（field_normalization_enabled=false，决策 D7 的排障后门）关掉时会原样透出双重
//    编码，所以两种形态都得能解 → asArray。
// ══════════════════════════════════════════════════════════════════════════════

/** 容器字段取数组：真数组直接返回，"数组的 JSON 字符串"（双重编码）解一层。 */
private fun asArray(el: JsonElement?): JsonArray? {
    if (el == null) return null
    return try {
        when {
            el is JsonArray -> el
            el is JsonPrimitive && el.isString ->
                el.content.takeIf { it.trimStart().startsWith("[") }
                    ?.let { AppJson.parseToJsonElement(it).jsonArray }
            else -> null
        }
    } catch (e: Exception) {
        DebugLog.w("DeviceResponses", "asArray failed", e)
        null
    }
}

// ─────────────────── GET /api/device/settings ───────────────────

/**
 * 设备开关类设置（[DeviceFields.DeviceSettings]）。
 *
 * [bearerPreference] 与 [netSelect] **不是别名**：前者是写入侧真正改的字段
 * （`SET_BEARER_PREFERENCE`），后者是老固件字段，取值域未经证实，仅作回退 → [networkMode]。
 * [roam] 与 [dialRoam] 同理（部分固件只填其中一个）→ [roamingEnabled]。
 */
@Serializable
data class DeviceSettingsResponse(
    @SerialName(DeviceFields.DeviceSettings.INDICATOR_LIGHT) val indicatorLight: String? = null,
    @SerialName(DeviceFields.DeviceSettings.PERFORMANCE_MODE) val performanceMode: String? = null,
    @SerialName(DeviceFields.DeviceSettings.SAMBA) val samba: String? = null,
    @SerialName(DeviceFields.DeviceSettings.USB_PORT) val usbPort: String? = null,
    @SerialName(DeviceFields.DeviceSettings.RESTART_SCHEDULE) val restartSchedule: String? = null,
    @SerialName(DeviceFields.DeviceSettings.RESTART_TIME) val restartTime: String? = null,
    @SerialName(DeviceFields.DeviceSettings.WIFI_SLEEP_IDLE_MINUTES) val wifiSleepIdleMinutes: String? = null,
    @SerialName(DeviceFields.DeviceSettings.BEARER_PREFERENCE) val bearerPreference: String? = null,
    @SerialName(DeviceFields.DeviceSettings.NET_SELECT) val netSelect: String? = null,
    @SerialName(DeviceFields.DeviceSettings.CONNECTION_MODE) val connectionMode: String? = null,
    @SerialName(DeviceFields.DeviceSettings.ROAM) val roam: String? = null,
    @SerialName(DeviceFields.DeviceSettings.DIAL_ROAM) val dialRoam: String? = null,
    @SerialName(DeviceFields.DeviceSettings.FOTA_AUTO_UPDATE) val fotaAutoUpdate: String? = null,
) {
    val indicatorLightOn: Boolean get() = DeviceFields.Bool.isTrue(indicatorLight)
    val performanceModeOn: Boolean get() = DeviceFields.Bool.isTrue(performanceMode)
    val sambaOn: Boolean get() = DeviceFields.Bool.isTrue(samba)
    val restartScheduleOn: Boolean get() = DeviceFields.Bool.isTrue(restartSchedule)

    /**
     * FOTA 自动检查更新（设备侧 `UpgMode`）。字段缺失时为 `null` = 未知，
     * 不要塌成 false —— 那会把「读不到」显示成「已禁用」。
     */
    val fotaAutoUpdateOn: Boolean?
        get() = fotaAutoUpdate?.takeIf { it.isNotBlank() }?.let { DeviceFields.Bool.isTrue(it) }

    /** 数据漫游：两个字段任一为真即为开。 */
    val roamingEnabled: Boolean
        get() = DeviceFields.Bool.isTrue(roam) || DeviceFields.Bool.isTrue(dialRoam)

    /** 网络模式（Bearer 取值域），优先写入侧字段，老固件回落 [netSelect]。 */
    val networkMode: String?
        get() = bearerPreference?.takeIf { it.isNotBlank() } ?: netSelect?.takeIf { it.isNotBlank() }

    /** 连接模式，归一成 `"auto"` / `"manual"`（部分固件用 `"1"` / `"hand"` 表示手动）。 */
    val manualConnection: Boolean
        get() = connectionMode?.trim()?.lowercase() in listOf("manual", "1", "hand")
}

// ───────────────── GET /api/device/lan-settings ─────────────────

/** LAN / DHCP 设置（[DeviceFields.LanSettings]）。 */
@Serializable
data class LanSettingsResponse(
    @SerialName(DeviceFields.LanSettings.LAN_IP) val lanIp: String? = null,
    @SerialName(DeviceFields.LanSettings.LAN_NETMASK) val lanNetmask: String? = null,
    @SerialName(DeviceFields.LanSettings.MAC_ADDRESS) val macAddress: String? = null,
    @SerialName(DeviceFields.LanSettings.DHCP_ENABLED) val dhcpEnabled: String? = null,
    @SerialName(DeviceFields.LanSettings.DHCP_START) val dhcpStart: String? = null,
    @SerialName(DeviceFields.LanSettings.DHCP_END) val dhcpEnd: String? = null,
    @SerialName(DeviceFields.LanSettings.DHCP_LEASE) val dhcpLease: String? = null,
    @SerialName(DeviceFields.LanSettings.DHCP_LEASE_HOUR) val dhcpLeaseHour: String? = null,
    @SerialName(DeviceFields.LanSettings.MTU) val mtu: String? = null,
    @SerialName(DeviceFields.LanSettings.TCP_MSS) val tcpMss: String? = null,
) {
    /** DHCP 服务开关（`"SERVER"` 也算开，见 [DeviceFields.Bool.TRUTHY]）。 */
    val dhcpOn: Boolean get() = DeviceFields.Bool.isTrue(dhcpEnabled)

    /**
     * 租约时长（秒）。
     *
     * `dhcpLease_hour` **不是** `dhcpLease` 的别名而是小时版本 —— 部分固件只填这个，
     * core 刻意不做换算（在 core 换会变成双重换算），换算责任在客户端。
     */
    val leaseSeconds: Int?
        get() = dhcpLease?.trim()?.toIntOrNull()
            ?: dhcpLeaseHour?.trim()?.toIntOrNull()?.times(3600)
}

// ────────────────── GET /api/wifi/settings ──────────────────

/**
 * WiFi 设置（[DeviceFields.WifiSettings]）。
 *
 * 设备侧的 `wifi_enable` / `wifi_onoff_state` 已由 core 归一到 [moduleSwitch]，
 * 客户端不再做别名探测；[passphrase] 也已由 core 完成 base64 解码。
 */
@Serializable
data class WifiSettingsResponse(
    @SerialName(DeviceFields.WifiSettings.CHIP) val chip: String? = null,
    @SerialName(DeviceFields.WifiSettings.SSID) val ssid: String? = null,
    @SerialName(DeviceFields.WifiSettings.PASSPHRASE) val passphrase: String? = null,
    @SerialName(DeviceFields.WifiSettings.AUTH_MODE) val authMode: String? = null,
    @SerialName(DeviceFields.WifiSettings.ENCRYPT_TYPE) val encryptType: String? = null,
    @SerialName(DeviceFields.WifiSettings.BROADCAST_SSID) val broadcastSsid: String? = null,
    @SerialName(DeviceFields.WifiSettings.MAX_STA_NUM) val maxStaNum: String? = null,
    @SerialName(DeviceFields.WifiSettings.MODULE_SWITCH) val moduleSwitch: String? = null,
) {
    val enabled: Boolean get() = DeviceFields.Bool.isTrue(moduleSwitch)

    /** 生效频段。`chip2` = 5G，其余（含固件填 `"1"` 的情况）按 2.4G 处理。 */
    val activeChip: String get() = if (chip == "chip2") "chip2" else "chip1"
}

// ─────────────────── GET /api/wifi/clients ───────────────────

/** 已连接客户端（[DeviceFields.WifiClients]）。WiFi 侧与 LAN 侧并列，UI 口径是两者相加。 */
@Serializable
data class WifiClientsResponse(
    @SerialName(DeviceFields.WifiClients.STATION_LIST) val stationList: JsonElement? = null,
    @SerialName(DeviceFields.WifiClients.LAN_STATION_LIST) val lanStationList: JsonElement? = null,
) {
    val stations: JsonArray? get() = asArray(stationList)
    val lanStations: JsonArray? get() = asArray(lanStationList)

    /** WiFi + LAN 全部客户端；两个列表都缺就是空表。 */
    val allStations: List<JsonElement>
        get() = buildList {
            stations?.let { addAll(it) }
            lanStations?.let { addAll(it) }
        }
}

// ──────────── GET /api/wifi/acl · POST /api/wifi/acl/* ────────────

/** 接入控制名单里的一台设备。`name` 可能是空串（设备没记住主机名）。 */
@Serializable
data class WifiAclEntry(
    @SerialName("mac") val mac: String = "",
    @SerialName("name") val name: String = "",
)

/**
 * 接入控制名单（拉黑）。三个写端点（block/unblock/clear）回的是**同一形状 + `success`**，
 * 且内容是 core 写完**回读设备**的真实名单 —— 直接拿它覆盖本地状态，不要自己推算。
 *
 * `mode` 是设备 ACL 档位原样字符串（`"2"` = 黑名单生效）。**清空后它仍是 `"2"`**，
 * 所以"有没有拉黑"只看 [blackList] 是否为空。
 */
@Serializable
data class WifiAclResponse(
    @SerialName("mode") val mode: String = "",
    @SerialName("black_list") val blackList: List<WifiAclEntry> = emptyList(),
    @SerialName("white_list") val whiteList: List<WifiAclEntry> = emptyList(),
    @SerialName("success") val success: Boolean = true,
)

// ──────────────── GET /api/network/band-status ────────────────

/** 频段锁定状态（[DeviceFields.BandStatus]）。值是纯数字逗号串，`"0"` / `"all"` = 未锁定。 */
@Serializable
data class BandStatusResponse(
    @SerialName(DeviceFields.BandStatus.LTE_BAND_LOCK) val lteBandLock: String? = null,
    @SerialName(DeviceFields.BandStatus.NR_BAND_LOCK) val nrBandLock: String? = null,
) {
    private fun bands(raw: String?): Set<Int> {
        if (raw == null || raw.trim() in DeviceFields.BandStatus.UNLOCKED_VALUES) return emptySet()
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    val lteBands: Set<Int> get() = bands(lteBandLock)
    val nrBands: Set<Int> get() = bands(nrBandLock)
}

// ───────────────── GET /api/network/cell-info ─────────────────

/**
 * 小区信息（[DeviceFields.CellInfo]）。
 *
 * **只有 LTE 侧字段 + 两个列表。** NR 与服务小区字段归 `FieldGroup.SIGNAL`，
 * 由 `/api/network/signal` 与 WS `signal` 频道提供（`SignalInfo` 的
 * `band` / `bandLabel` / `arfcn` / `pci` / `signalStrength` 已是 NR 优先的合并值）。
 * 想显示"当前服务小区"就读 [SignalInfo]，**不要**在这里找 `Nr_*`：本端点不返回。
 */
@Serializable
data class CellInfoResponse(
    @SerialName(DeviceFields.CellInfo.NEIGHBOR_CELL_INFO) val neighborCellInfo: JsonElement? = null,
    @SerialName(DeviceFields.CellInfo.LOCKED_CELL_INFO) val lockedCellInfo: JsonElement? = null,
    @SerialName(DeviceFields.CellInfo.LTE_PCI) val ltePci: String? = null,
    @SerialName(DeviceFields.CellInfo.LTE_EARFCN) val lteEarfcn: String? = null,
    @SerialName(DeviceFields.CellInfo.LTE_BANDS) val lteBands: String? = null,
    @SerialName(DeviceFields.CellInfo.LTE_RSRP) val lteRsrp: String? = null,
    @SerialName(DeviceFields.CellInfo.LTE_RSRQ) val lteRsrq: String? = null,
    @SerialName(DeviceFields.CellInfo.LTE_SNR) val lteSnr: String? = null,
) {
    val neighbors: JsonArray? get() = asArray(neighborCellInfo)
    val lockedCells: JsonArray? get() = asArray(lockedCellInfo)
}

// ─────────────── GET /api/network/neighbor-cells ───────────────

/**
 * 邻区列表（[DeviceFields.CellInfo.NEIGHBOR_CELL_INFO]）。
 *
 * 与 [CellInfoResponse] 的分工：cell-info 是「服务小区 LTE 字段 + 两个列表」的**缓存**快照，
 * 本端点是**实时**向设备发查询、只回邻区这一个列表（core `NetworkRoutes` 里没有走 responseCache）。
 * 所以邻区卡要拿到刷新时的真实邻区，必须调这个而不是复用 cell-info。
 *
 * 数组元素的键集**是开放的**：core 只保证登记过的 [DeviceFields.CellInfo.ITEM_ALL]
 * （pci / earfcn / rsrp / rsrq / sinr / rat）会被归一化，不同固件给的字段多寡不同，
 * 少给某个键是正常情况 —— 所以元素维持 JsonElement，由 UI 按存在性逐个降级显示。
 * 设备侧不支持时 core 回 `{"neighbor_cell_info": []}`（不是 404），空列表 = 无邻区。
 */
@Serializable
data class NeighborCellsResponse(
    @SerialName(DeviceFields.CellInfo.NEIGHBOR_CELL_INFO) val neighborCellInfo: JsonElement? = null,
) {
    val neighbors: JsonArray? get() = asArray(neighborCellInfo)
}
