package com.ufi_axis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
// 2026-09-25：OnlineStation 与它的解析（双键容错 / 合并去重）已搬到 :app:data
// —— 仪表盘弹窗要用同一份，而两个 feature 模块之间没有依赖边。搬移理由见该文件头注释。
import com.ufi_axis.data.model.OnlineStation
import com.ufi_axis.data.model.parseOnlineStations
import com.ufi_axis.data.model.WifiAclEntry
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.delay

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

    // ── lifecycle 门控（2026-09-21）──
    var lifecycleResumed by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            lifecycleResumed = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lifecycleResumed = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.network.refreshWifi()
        viewModel.network.loadWifiAcl()
    }

    // 设备上下线是随时发生的，只在进页面拉一次会让列表一直停在进来那一刻的快照
    // （之前必须退出重进才刷新）。5s 轮询，与 TunnelScreen / CfTunnelScreen 同一套做法；
    // 2026-09-21：加 lifecycleResumed 门控——后台不继续打设备。
    //
    // 2026-09-05：`force = true` 是必须的 —— `refreshWifi` 默认带 10s 新鲜度闸门
    // （为了消掉首页横滑落定后那次无谓重拉），不绕过它这条 5s 轮询会被节流成 10s，
    // 等于降级本页「设备上下线随时可见」的产品要求。
    LaunchedEffect(lifecycleResumed) {
        if (!lifecycleResumed) return@LaunchedEffect
        while (true) {
            delay(5_000)
            viewModel.network.refreshWifi(force = true)
        }
    }

    // 2026-09-25：原来是本文件私有的 `rememberOnlineStations(...)`（@Composable + 内部 remember）。
    // 解析搬去 :app:data 之后那边是纯函数，`remember` 留在这里做 —— key 仍是同样那两个
    // JsonArray，缓存命中行为与搬之前等价。
    val stations = remember(state.wifiClients?.stations, state.wifiClients?.lanStations) {
        parseOnlineStations(
            wifiList = state.wifiClients?.stations,
            lanList = state.wifiClients?.lanStations
        )
    }
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
                            // 2026-09-25：行视觉搬到公共层 [UfiOnlineDeviceRow]（原本文件私有的
                            // OnlineDeviceRow），与仪表盘 hero 卡的在线设备弹窗共用同一份。入参是
                            // 裸值 —— app/ui 看不到 OnlineStation，字段映射就落在这里。
                            UfiOnlineDeviceRow(
                                hostname = station.hostname,
                                ip = station.ip,
                                mac = station.mac,
                                viaLan = station.viaLan,
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
            // 关闭动作交给 shell 排时序：离场 backdrop 要播完才卸载窗口，见 LocalUfiDialogClose。
            // local 必须在弹窗自己的 slot 内部读，在弹窗外面读会拿到"直接执行"的默认实现。
            val close = LocalUfiDialogClose.current
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = { close { confirmBlock = null } }, modifier = Modifier)
        },
        confirmButton = {
            val close = LocalUfiDialogClose.current
            UfiButton(
                size = UfiButtonSize.Small,
                text = "拉黑",
                modifier = Modifier,
                onClick = {
                    close {
                        pending?.let { viewModel.network.blockDevice(it.mac, it.hostname) }
                        confirmBlock = null
                    }
                }
            )
        }
    ) {
        // 间距统一到 UfiDialogBody（12dp）
        UfiDialogBody {
            Text(
                text = "将「${pending?.hostname?.ifEmpty { "未知设备" } ?: ""}」（${pending?.mac?.uppercase() ?: ""}）" +
                    "加入 WiFi 黑名单？该设备会立刻断开，之后无法再连接本热点。",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary
            )
        }
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
        UfiDialogBody {
            if (blocked.isEmpty()) {
                Text(
                    text = "还没有拉黑任何设备。在下方在线设备列表里点「拉黑」即可。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
                return@UfiDialogBody
            }
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

// ═══════════════════════════════════════════════════════════════════════
// 2026-09-25：以下四样东西已从本文件搬走，**不要在这里重建**：
//   · `OnlineStation` 数据类          → :app:data `com.ufi_axis.data.model.OnlineStation`
//   · `rememberOnlineStations`        → 同上文件的纯函数 `parseOnlineStations`
//                                       （`remember` 留在本页调用点，key 不变）
//   · `JsonElement.toOnlineStation`   → 同上文件（private，双键容错逐字保留）
//   · `OnlineDeviceRow`               → app/ui `UfiOnlineDeviceRow`
// 搬移原因：仪表盘 hero 卡「已连接设备」要弹一个同源的在线设备列表，而
// :app:feature-dashboard 不依赖 :app:feature-network —— 留在本文件就只能抄第二份解析，
// 那是本仓明令禁止的。详细的「哪些行为必须逐字不变」写在 OnlineStation.kt 头注释里。
// ═══════════════════════════════════════════════════════════════════════

