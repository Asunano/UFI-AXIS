package com.ufi_axis.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ══════════════════════════════════════════════════════════════════════════════
// 在线客户端的解析结果（`GET /api/wifi/clients` 的 station_list / lan_station_list）
//
// 【原来在哪、为什么搬到这里】
// 2026-09-25 之前这三样东西（`OnlineStation` 数据类、`rememberOnlineStations` 合并去重、
// `JsonElement.toOnlineStation` 双键容错）都是
// `app/feature-network/.../screens/OnlineDevicesScreen.kt` 里的 **private** 声明。
// 本轮仪表盘 hero 卡「已连接设备」要弹一个同源的在线设备列表，而
// `:app:feature-dashboard` 并不依赖 `:app:feature-network`（两个 feature 之间没有、
// 也不该有依赖边），留在原处就只能在仪表盘再抄一份解析 —— 那是本仓明令禁止的
// 「同一份契约解析出现两份」。两个 feature 模块共同依赖的最低层是 `:app:data`，
// 且 [WifiClientsResponse] 本来就住在这里、本模块本来就有 kotlinx.serialization，
// 所以搬到本文件是唯一不引入新依赖边的落点。
//
// 【搬的时候哪些行为必须逐字不变】
// ① 双键容错：`mac_addr`/`mac`、`ip_addr`/`ip`、`hostname`/`host_name` 两套键都读，
//    顺序也不能换（前者是 core 归一化后的 canonical key，优先）。
// ② 脏数据判据：IP 与 MAC **全空**才丢弃，只缺一个仍然保留。
// ③ 去重规则：先 WiFi 后 LAN 顺序拼接，再 `distinctBy { mac.lowercase().ifEmpty { ip } }`
//    —— 「先 WiFi 后 LAN」决定了同一台设备两边都出现时保留的是 **WiFi 那条**
//    （于是 [OnlineStation.viaLan] = false，拉黑按钮才会出现）。反过来就会把
//    能拉黑的设备显示成不能拉黑的 LAN 设备。
// ④ LAN 标记来自**它在哪个数组里**，不是来自元素字段。
//
// 唯一的形态变化：原来是 `@Composable fun rememberOnlineStations(...)`（内部 `remember`），
// 本模块没有 Compose 依赖，故拆成纯函数 [parseOnlineStations]，`remember` 留在两个
// 调用点自己做（键仍是那两个 JsonArray，缓存行为等价）。
// ══════════════════════════════════════════════════════════════════════════════

/** 一台在线客户端。字段全部按"缺失即空串"处理，UI 侧再决定占位符。 */
data class OnlineStation(
    val hostname: String,
    val ip: String,
    val mac: String,
    val viaLan: Boolean
)

/**
 * 合并两个客户端容器并去重。
 *
 * 两个容器都要读：`lan_station_list` 不保证出现，但出现时是另一批客户端，只读
 * `station_list` 会漏显示；合并后按 MAC 去重（手册的参考做法），MAC 为空时退化为按 IP 去重。
 *
 * 入参直接取 [WifiClientsResponse.stations] / [WifiClientsResponse.lanStations]
 * （那两个 getter 已经处理过"数组 or 数组的 JSON 字符串"双重编码）。
 */
fun parseOnlineStations(wifiList: JsonArray?, lanList: JsonArray?): List<OnlineStation> =
    buildList {
        wifiList?.forEach { it.toOnlineStation(viaLan = false)?.let(::add) }
        lanList?.forEach { it.toOnlineStation(viaLan = true)?.let(::add) }
    }.distinctBy { it.mac.lowercase().ifEmpty { it.ip } }

/**
 * 客户端数组元素**没有稳定字段契约**（见 API 手册 `/api/wifi/clients`）：
 * 正常路径下 core 已把 `mac`→`mac_addr`、`ip`→`ip_addr`、`host_name`→`hostname` 归一，
 * 但关掉 `field_normalization_enabled` 排障时会原样透出 —— 所以两套键都读。
 * IP 与 MAC 全空的元素视为脏数据丢弃（固件偶尔塞占位对象）。
 */
private fun JsonElement.toOnlineStation(viaLan: Boolean): OnlineStation? {
    val obj = this as? JsonObject ?: return null
    fun str(vararg keys: String): String = keys.firstNotNullOfOrNull { k ->
        (obj[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    } ?: ""
    val ip = str("ip_addr", "ip")
    val mac = str("mac_addr", "mac")
    if (ip.isEmpty() && mac.isEmpty()) return null
    return OnlineStation(hostname = str("hostname", "host_name"), ip = ip, mac = mac, viaLan = viaLan)
}
