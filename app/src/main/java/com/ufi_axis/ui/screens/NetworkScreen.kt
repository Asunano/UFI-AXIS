package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.animation.blurEntrance
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.module.parseWifiSettings
import com.ufi_axis.viewmodel.module.parseWifiClients
import com.ufi_axis.viewmodel.module.parseCurrentBand
import com.ufi_axis.viewmodel.module.parseDeviceSettingsField
import com.google.gson.JsonElement

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.networkState.collectAsState()
    val deviceSettingsState by viewModel.deviceSettingsState.collectAsState()
    val dashboardTrafficRealtime by viewModel.dashboardTrafficRealtime.collectAsState()

    // ── 6 个弹窗状态 ──
    var showWifiDialog by remember { mutableStateOf(false) }
    var showNetworkModeDialog by remember { mutableStateOf(false) }
    var showBandLockDialog by remember { mutableStateOf(false) }
    var showApnDialog by remember { mutableStateOf(false) }
    var showDhcpDialog by remember { mutableStateOf(false) }
    var showCellLockDialog by remember { mutableStateOf(false) }
    var showNetworkFeaturesDialog by remember { mutableStateOf(false) }

    // 有 Bug: com.ufi_axis.ui.util.rememberIsActive 不可用，用手写替代
    var isScreenActive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        onDispose { isScreenActive = false }
    }

    // 首次加载 + 出错自动重试（主界面仪表盘 10s 轮询，网络界面无轮询导致出错后永久卡住）
    LaunchedEffect(isScreenActive) {
        if (!isScreenActive) return@LaunchedEffect
        kotlinx.coroutines.delay(200)
        loadNetworkAll(viewModel)
    }
    var retryCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.errorMessage, isScreenActive) {
        if (state.errorMessage != null && isScreenActive && retryCount < 3) {
            kotlinx.coroutines.delay(5_000)
            retryCount++
            loadNetworkAll(viewModel)
        }
    }

    UfiScreenScaffold(title = "网络设置", actions = {
        IconButton(onClick = { viewModel.network.refreshNetwork() }) { Icon(Icons.Default.Refresh, null) }
    }) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.network.refreshNetwork() },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            UfiPageBackground(modifier = Modifier.blurEntrance("network"), useGradient = true) {
                state.errorMessage?.let { e ->
                    UfiErrorBanner(message = e, onRetry = { viewModel.network.refreshNetwork() })
                }

                // ═══════════ 1. 蜂窝网络状态 & SIM 卡 ═══════════
                val currentBand = remember(state.cellInfo) { parseCurrentBand(state.cellInfo) }
                val downlinkSpeed by remember { derivedStateOf { dashboardTrafficRealtime?.rx_speed_display?.takeIf { it.isNotBlank() } } }
                CellularStatusCard(
                    state = state,
                    currentBand = currentBand,
                    downlinkSpeed = downlinkSpeed
                )

                Spacer(Modifier.height(Spacing.SectionTop))

                // ═══════════ 2. SIM 卡功能（合并：连接模式 + 网络制式 + 频段锁定 + APN + 基站信息） ═══════════
                val roamingEnabled = parseRoamingEnabled(deviceSettingsState.settings)
                val connModeJson = parseConnModeDlg(deviceSettingsState.settings)
                val netModeJson = parseNetModeDlg(deviceSettingsState.settings)
                val modeLabels = mapOf(
                    "AUTO" to "自动", "LTE_AND_5G" to "4G/5G NSA", "Only_5G" to "5G SA",
                    "Only_LTE" to "仅 4G", "Only_WCDMA" to "仅 3G", "LTE_WCDMA" to "4G/3G"
                )

                UfiSectionHeader(title = "SIM 卡功能")
                UfiSettingsGroup {
                    // 移动数据
                    UfiSettingsToggle(
                        title = "移动数据",
                        description = if (state.mobileDataEnabled) "已开启" else "已关闭",
                        checked = state.mobileDataEnabled,
                        onCheckedChange = { viewModel.network.toggleMobileData(it) }
                    )
                    UfiDivider()

                    // 数据漫游
                    UfiSettingsToggle(
                        title = "数据漫游",
                        description = if (roamingEnabled) "漫游中" else "已关闭",
                        checked = roamingEnabled,
                        onCheckedChange = { viewModel.network.setRoamingEnabled(it) }
                    )
                    UfiDivider()

                    // 连接模式 → 弹窗
                    UfiNavigationItem(
                        title = "连接模式",
                        description = if (connModeJson == "auto") "自动拨号" else "手动拨号",
                        onClick = { showNetworkModeDialog = true }
                    )
                    UfiDivider()

                    // 网络制式 → 弹窗
                    UfiNavigationItem(
                        title = "网络制式",
                        description = modeLabels[netModeJson] ?: netModeJson,
                        onClick = { showNetworkModeDialog = true }
                    )
                    UfiDivider()

                    // 频段锁定 → 弹窗
                    UfiNavigationItem(
                        title = "频段锁定",
                        description = "锁定指定 LTE / NR 频段",
                        onClick = { showBandLockDialog = true }
                    )
                    UfiDivider()

                    // APN 接入点 → 弹窗
                    UfiNavigationItem(
                        title = "APN 接入点",
                        description = "配置接入点名称与网络参数",
                        onClick = { showApnDialog = true }
                    )
                    UfiDivider()

                    // 基站信息 → 弹窗
                    UfiNavigationItem(
                        title = "基站信息",
                        description = "查看/锁定邻区基站",
                        onClick = { showCellLockDialog = true }
                    )
                }

                Spacer(Modifier.height(Spacing.SectionTop))

                // ═══════════ 3. 连接与共享 ═══════════
                val wifi = remember(state.wifiSettings) { parseWifiSettings(state.wifiSettings) }
                val clientsParsed = remember(state.wifiClients) { parseWifiClients(state.wifiClients) }
                val allStations = remember(clientsParsed) {
                    buildList {
                        clientsParsed.stationList?.forEach { add(it) }
                        clientsParsed.lanStationList?.forEach { add(it) }
                    }
                }

                UfiSectionHeader(title = "连接与共享")
                UfiSettingsGroup {
                    // WiFi 热点 → 弹窗
                    UfiNavigationItem(
                        title = "WiFi 热点",
                        description = if (state.wifiEnabled) {
                            "${wifi.ssid.ifBlank { "未命名" }} · ${allStations.size} 台设备 · ${if (wifi.activeChip == "chip2") "5GHz" else "2.4GHz"}"
                        } else "已关闭",
                        onClick = { showWifiDialog = true }
                    )
                    UfiDivider()

                    // DHCP 设置 → 弹窗
                    UfiNavigationItem(
                        title = "DHCP 设置",
                        description = "局域网地址分配与租约配置",
                        onClick = { showDhcpDialog = true }
                    )
                    UfiDivider()

                    // 网络功能 → 弹窗
                    UfiNavigationItem(
                        title = "网络功能",
                        description = "FOTA · USB 共享 · SAMBA · NFC",
                        onClick = { showNetworkFeaturesDialog = true }
                    )
                }

                Spacer(Modifier.height(Spacing.SectionTop))

                // ═══════════ 4. 网速测试 ═══════════
                val speedTestState by viewModel.speedTestState.collectAsState()
                UfiSectionHeader(title = "网速测试")
                UfiSettingsGroup {
                    val lastResult = speedTestState.result?.take(60)
                    UfiNavigationItem(
                        title = "网速测试",
                        description = lastResult ?: "内网/外网带宽测试 · 可自定义时长",
                        onClick = { navController.navigate("detail/speed-test") }
                    )
                }

                // ── 6 个弹窗实例 ──
                NetworkModeDialog(viewModel, showNetworkModeDialog, onDismiss = { showNetworkModeDialog = false })
                BandLockDialog(viewModel, showBandLockDialog, onDismiss = { showBandLockDialog = false })
                WifiSettingsDialog(viewModel, showWifiDialog, onDismiss = { showWifiDialog = false })
                ApnConfigDialog(viewModel, showApnDialog, onDismiss = { showApnDialog = false })
                DhcpSettingsDialog(viewModel, showDhcpDialog, onDismiss = { showDhcpDialog = false })
                CellLockDialog(viewModel, showCellLockDialog, onDismiss = { showCellLockDialog = false })
                NetworkFeaturesDialog(viewModel, showNetworkFeaturesDialog, onDismiss = { showNetworkFeaturesDialog = false })

                UfiLoadingBox(isLoading = state.isLoading) {}
                Spacer(Modifier.height(Spacing.Large))
            }
        }
    }
}

// ═══════════════════════════════════════════════
// 蜂窝网络状态主卡片
// ═══════════════════════════════════════════════

@Composable
private fun CellularStatusCard(
    state: com.ufi_axis.viewmodel.state.NetworkState,
    currentBand: String? = null,
    downlinkSpeed: String? = null
) {
    val palette = LocalResolvedPalette.current
    val sig = state.signalInfo; val net = state.networkStatus; val sim = state.simInfo
    val isConnected = net?.isCellularConnected == true
    val rawRat = sig?.rat?.takeIf { it.isNotBlank() && it != "WiFi" } ?: net?.network_type?.takeIf { it.isNotBlank() && it != "WiFi" }
    val operator = sig?.operator?.takeIf { it.isNotBlank() } ?: net?.operator?.takeIf { it.isNotBlank() } ?: "未知"
    val networkTypeLabel = when {
        rawRat == null -> if (isConnected) "已连接" else "未知"
        rawRat.contains("5G", true) || rawRat.contains("NR", true) -> "5G"
        rawRat.contains("4G", true) || rawRat.contains("LTE", true) -> "4G"
        else -> rawRat
    }
    val rsrpVal = sig?.rsrp
    val accentColor = if (isConnected) networkTypeColor(networkTypeLabel) else MaterialTheme.colorScheme.error

    UfiSectionHeader(title = "蜂窝网络")

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = UfiCardDefaults.shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = UfiCardDefaults.cardElevation(),
        border = UfiCardDefaults.cardLightBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = accentColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isConnected) Icons.Default.SignalCellularAlt else Icons.Default.SignalCellularOff,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = accentColor
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isConnected) "已连接" else "未连接",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                    Text(
                        text = operator,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    color = accentColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = " $networkTypeLabel ",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = accentColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            if (currentBand != null || downlinkSpeed != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    currentBand?.let { band ->
                        Surface(
                            color = accentColor.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = band,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                color = accentColor,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    downlinkSpeed?.let { speed ->
                        Text(
                            text = "▼ ",
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                        )
                        Text(
                            text = speed,
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (rsrpVal != null) {
                Spacer(Modifier.height(14.dp))
                val bars = signalBars(rsrpVal)
                val levelText = signalLevelText(rsrpVal)
                val barColor = signalColor(rsrpVal)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("信号", style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                        for (i in 1..5) {
                            val h = (14 + i * 5).dp
                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .height(h)
                                    .padding(horizontal = 1.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = if (i <= bars) barColor else palette.textSecondary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(2.dp)
                                ) {}
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$rsrpVal dBm",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = barColor
                    )
                    Spacer(Modifier.width(6.dp))
                    Surface(color = barColor.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp)) {
                        Text(
                            levelText,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = barColor
                        )
                    }
                }
            }

            if (sig != null) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    sig.rsrp?.let { UfiMetricBadge("RSRP", "${it}dBm", it, -120, -80, Modifier.weight(1f)) }
                    Spacer(Modifier.width(8.dp))
                    sig.sinr?.let { UfiMetricBadge("SNR", "${it}dB", it, 0, 20, Modifier.weight(1f)) }
                    Spacer(Modifier.width(8.dp))
                    sig.rsrq?.let { UfiMetricBadge("RSRQ", "${it}dB", it, -20, -10, Modifier.weight(1f)) }
                }
            }

            if (sim != null) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = palette.textSecondary.copy(alpha = 0.1f))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SimCard, null, Modifier.size(16.dp), tint = palette.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text("SIM 卡", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = palette.textSecondary)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        UfiLabelValueRow("运营商", sim.phone_type ?: "—")
                        Spacer(Modifier.height(4.dp))
                        sim.imei?.let { UfiLabelValueRow("IMEI", it) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        UfiLabelValueRow("状态", sim.sim_state ?: "—")
                        Spacer(Modifier.height(4.dp))
                        sim.iccid?.let { UfiLabelValueRow("ICCID", it) }
                    }
                }
            }
        }
    }
}



// ═══════════════════════════════════════════════
// 频段锁定组件（被 BandLockDialog 复用）
// ═══════════════════════════════════════════════

@Composable
fun BandLockSection(viewModel: MainViewModel, state: com.ufi_axis.viewmodel.state.NetworkState) {
    var selectedLte by remember { mutableStateOf(setOf<Int>()) }
    var selectedNr by remember { mutableStateOf(setOf<Int>()) }
    val supportLte = listOf(1, 3, 5, 8, 34, 38, 39, 40, 41)
    val supportNr = listOf(1, 5, 8, 28, 41, 78)

    LaunchedEffect(state.bandStatus) {
        val parsed = com.ufi_axis.viewmodel.module.parseBandStatus(state.bandStatus)
        selectedLte = parseBandSet(parsed.lteBandLock); selectedNr = parseBandSet(parsed.nrBandLock)
    }

    Column(Modifier.padding(16.dp)) {
        UfiSectionGroupTitle("频段锁定", "锁定指定 LTE/NR 频段")
        Text("LTE 频段", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        UfiIntChipSelector(values = supportLte, selectedValues = selectedLte, onToggle = { selectedLte = if (it in selectedLte) selectedLte - it else selectedLte + it }, prefix = "B")
        Spacer(Modifier.height(12.dp))
        Text("NR 频段", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        UfiIntChipSelector(values = supportNr, selectedValues = selectedNr, onToggle = { selectedNr = if (it in selectedNr) selectedNr - it else selectedNr + it }, prefix = "N")
        Spacer(Modifier.height(24.dp))
        UfiButtonRow {
            UfiPrimaryButton(text = "锁定所选", onClick = { viewModel.network.lockBands(if (selectedLte.isNotEmpty()) selectedLte.joinToString(",") else null, if (selectedNr.isNotEmpty()) selectedNr.joinToString(",") else null) }, modifier = Modifier.weight(1f))
            UfiSecondaryButton(text = "全部解锁", onClick = { viewModel.network.lockBands(null, null); selectedLte = emptySet(); selectedNr = emptySet() }, modifier = Modifier.weight(1f))
        }
    }
}

// ═══════════════════════════════════════════════
// 内部辅助函数
// ═══════════════════════════════════════════════

private suspend fun loadNetworkAll(viewModel: MainViewModel) {
    viewModel.network.refreshNetwork()
    viewModel.network.loadBandStatus()
    viewModel.network.loadCellInfo()
    viewModel.network.loadDeviceSettings()
    viewModel.network.loadBlacklist()
    viewModel.network.restoreWifiChipPreference()
}

private fun parseRoamingEnabled(settings: JsonElement?): Boolean {
    val v = parseDeviceSettingsField(settings, "roam_setting_option", "dial_roam_setting_option") ?: return false
    return v == "on" || v == "1"
}

private fun parseBandSet(raw: String?): Set<Int> = raw?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()