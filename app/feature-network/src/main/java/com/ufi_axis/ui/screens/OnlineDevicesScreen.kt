package com.ufi_axis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.WifiAclEntry
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 在线设备页（`GET /api/wifi/clients`）。
 *
 * 2026-08-30 从「网络设置」页尾的卡片拆成二级页：设备多时列表会一直往下长，
 * 而网络页是底部胶囊导航栏的 Tab 之一 —— 最后几台设备会被导航栏压住，
 * 且父页没有为它预留底部安全区。拆页后列表独占一屏，可以自由滚动。
 *
 * 数据仍复用 [com.ufi_axis.viewmodel.state.NetworkState.wifiClients]（与网络页、
 * 仪表盘 hero 卡同一份响应）。进页面时用 `refreshWifi()` 轻量补一次，
 * 只拉 WiFi 设置 + 客户端列表，不触碰信号/SIM 那套全量刷新。
 *
 * 2026-08-30 加拉黑（`/api/wifi/acl` 那组端点）：每行右侧一个「拉黑」按钮，顶部一个
 * 「已拉黑设备」入口。名单由 core 整表读-改-写，这里只发单台设备的 mac/name。
 */
@Composable
fun OnlineDevicesScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.networkState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.network.refreshWifi()
        viewModel.network.loadWifiAcl()
    }

    // 设备上下线是随时发生的，只在进页面拉一次会让列表一直停在进来那一刻的快照
    // （之前必须退出重进才刷新）。5s 轮询，与 TunnelScreen / CfTunnelScreen 同一套做法；
    // 离开页面时 LaunchedEffect 随组合销毁自动取消，不会在后台一直打设备。
    // 名单不跟着轮询：它只会被本 App 改，每次写完 core 都回读并覆盖，没必要 5s 一次。
    //
    // 2026-09-05：`force = true` 是必须的 —— `refreshWifi` 默认带 10s 新鲜度闸门
    // （为了消掉首页横滑落定后那次无谓重拉），不绕过它这条 5s 轮询会被节流成 10s，
    // 等于降级本页「设备上下线随时可见」的产品要求。
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            viewModel.network.refreshWifi(force = true)
        }
    }

    val stations = rememberOnlineStations(
        wifiList = state.wifiClients?.stations,
        lanList = state.wifiClients?.lanStations
    )
    val palette = LocalResolvedPalette.current
    val blocked = state.wifiAcl?.blackList.orEmpty()
    // 名单里的 MAC 一律按小写比对：设备回读是小写，客户端展示用大写。
    val blockedMacs = remember(blocked) { blocked.map { it.mac.lowercase() }.toSet() }
    var showBlockedDialog by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf<OnlineStation?>(null) }

    UfiScreenScaffold(
        title = "在线设备",
        navController = navController,
        showBack = true
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))

            // 顶部入口：已拉黑设备（进去可单个解除 / 一键全部解除）
            Box(
                Modifier
                    .fillMaxWidth()
                    .ufiStandardCard(elevation = 2.dp)
                    .clickable { showBlockedDialog = true }
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Block,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = palette.error
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "查看已拉黑设备",
                            style = UfiTextStyles.bodyEmphasis,
                            color = palette.textPrimary
                        )
                        Text(
                            text = if (blocked.isEmpty()) "暂无拉黑设备" else "${blocked.size} 台已拉黑",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = palette.textSecondary
                    )
                }
            }

            UfiSectionHeader(
                title = "已连接",
                trailing = {
                    Text(
                        text = "${stations.size} 台",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary
                    )
                }
            )
            Box(Modifier.fillMaxWidth().ufiStandardCard(elevation = 2.dp).padding(16.dp)) {
                when {
                    stations.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        stations.forEachIndexed { index, station ->
                            if (index > 0) UfiDivider()
                            val mac = station.mac.lowercase()
                            OnlineDeviceRow(
                                station = station,
                                blocked = mac in blockedMacs,
                                // MAC 为空的元素没法拉黑（设备名单以 MAC 为主键）
                                actionEnabled = mac.isNotEmpty() && state.aclPendingMac != mac,
                                onBlock = { confirmBlock = station },
                                onUnblock = { viewModel.network.unblockDevice(station.mac) }
                            )
                        }
                    }
                    // 加载中且尚无数据：不画空态，避免"暂无设备 → 列表"闪一下
                    state.isLoading -> UfiLoadingBox(isLoading = true) {}
                    else -> Text(
                        text = "暂无连接设备",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // 拉黑要确认：会把设备立刻踢下线，误点代价不小（尤其误拉黑自己这台手机）。
    val pending = confirmBlock
    UfiCustomDialog(
        visible = pending != null,
        onDismiss = { confirmBlock = null },
        title = "拉黑设备",
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = { confirmBlock = null }, modifier = Modifier)
        },
        confirmButton = {
            UfiButton(
                size = UfiButtonSize.Small,
                text = "拉黑",
                modifier = Modifier,
                onClick = {
                    pending?.let { viewModel.network.blockDevice(it.mac, it.hostname) }
                    confirmBlock = null
                }
            )
        }
    ) {
        Text(
            text = "将「${pending?.hostname?.ifEmpty { "未知设备" } ?: ""}」（${pending?.mac?.uppercase() ?: ""}）" +
                "加入 WiFi 黑名单？该设备会立刻断开，之后无法再连接本热点。",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary
        )
    }

    BlockedDevicesDialog(
        visible = showBlockedDialog,
        blocked = blocked,
        pendingMac = state.aclPendingMac,
        onDismiss = { showBlockedDialog = false },
        onUnblock = { viewModel.network.unblockDevice(it) },
        onClearAll = { viewModel.network.clearBlockedDevices() }
    )
}

/**
 * 已拉黑设备面板。
 *
 * 用弹窗而不是再拆一个二级页：条目通常只有几条，且入口就在本页顶部 ——
 * 再进一层导航反而更远。列表用 [UfiScrollableDialog] 兜住"名单变长"的情况。
 */
@Composable
private fun BlockedDevicesDialog(
    visible: Boolean,
    blocked: List<WifiAclEntry>,
    pendingMac: String?,
    onDismiss: () -> Unit,
    onUnblock: (String) -> Unit,
    onClearAll: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    UfiScrollableDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "已拉黑设备",
        actions = if (blocked.isEmpty()) null else {
            {
                UfiDialogActions(
                    onDismiss = onDismiss,
                    onConfirm = onClearAll,
                    confirmText = "全部解除",
                    dismissText = "关闭",
                    confirmDestructive = true,
                    enabled = pendingMac == null
                )
            }
        }
    ) {
        if (blocked.isEmpty()) {
            Text(
                text = "还没有拉黑任何设备。在下方在线设备列表里点「拉黑」即可。",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary
            )
            return@UfiScrollableDialog
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            blocked.forEachIndexed { index, entry ->
                if (index > 0) UfiDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = entry.name.ifEmpty { "未知设备" },
                            style = UfiTextStyles.bodyEmphasis,
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = entry.mac.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // 行内按钮用 Subtle + Small：Small 档默认 fillWidth = false，放进 Row 不会撑满整行。
                    // 2026-09-04（P4c）之前这里注释写着"必须用 UfiOutlinedActionButton，因为
                    // UfiSecondaryButton 内部写死 fillMaxWidth 传 modifier 也去不掉"——那个约束已经没了：
                    // 宽度现在是 UfiButton 的 fillWidth 参数，任何 variant 都能自适应。
                    UfiButton(
                        variant = UfiButtonVariant.Subtle, size = UfiButtonSize.Small,
                        text = "解除",
                        onClick = { onUnblock(entry.mac) },
                        enabled = pendingMac == null
                    )
                }
            }
        }
    }
}

/** 一台在线客户端。字段全部按"缺失即空串"处理，UI 侧再决定占位符。 */
private data class OnlineStation(
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
 */
@Composable
private fun rememberOnlineStations(wifiList: JsonArray?, lanList: JsonArray?): List<OnlineStation> =
    remember(wifiList, lanList) {
        buildList {
            wifiList?.forEach { it.toOnlineStation(viaLan = false)?.let(::add) }
            lanList?.forEach { it.toOnlineStation(viaLan = true)?.let(::add) }
        }.distinctBy { it.mac.lowercase().ifEmpty { it.ip } }
    }

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

@Composable
private fun OnlineDeviceRow(
    station: OnlineStation,
    blocked: Boolean,
    actionEnabled: Boolean,
    onBlock: () -> Unit,
    onUnblock: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = palette.accent.copy(alpha = 0.12f), modifier = Modifier.size(34.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (station.viaLan) Icons.Default.SettingsEthernet else Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = palette.accent
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = station.hostname.ifEmpty { "未知设备" },
                style = UfiTextStyles.bodyEmphasis,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 接入方式并到第二行：右侧要留给拉黑按钮，再摆一个 WiFi/LAN 标签会挤掉 IP/MAC。
            Text(
                text = listOf(
                    if (station.viaLan) "LAN" else "WiFi",
                    station.ip,
                    station.mac.uppercase()
                ).filter { it.isNotEmpty() }.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        // 拉黑只作用于 WiFi 接入，LAN（USB/网线）侧设备挡不住，所以那一行不给按钮。
        if (!station.viaLan) {
            if (blocked) {
                // 同样是行内按钮：Small 档默认不铺满宽度（见上方 P4c 注释）
                UfiButton(
                    variant = UfiButtonVariant.Subtle, size = UfiButtonSize.Small,
                    text = "解除",
                    onClick = onUnblock,
                    enabled = actionEnabled
                )
            } else {
                UfiButton(
                    size = UfiButtonSize.Small,
                    text = "拉黑",
                    onClick = onBlock,
                    modifier = Modifier,
                    enabled = actionEnabled
                )
            }
        } else {
            Surface(
                color = palette.accent.copy(alpha = 0.1f),
                shape = UfiCardDefaults.microShape
            ) {
                Text(
                    text = "LAN",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accent
                )
            }
        }
    }
}
