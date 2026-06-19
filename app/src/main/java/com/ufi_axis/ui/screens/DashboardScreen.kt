package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.util.FormatUtils.sanitizeUnknown
import com.ufi_axis.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.dashboardState.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(state.isLoading) { if (!state.isLoading) isRefreshing = false }

    UfiScreenScaffold(title = "UFI-AXIS", actions = {
        IconButton(onClick = { viewModel.refreshDashboard() }) { Icon(Icons.Default.Refresh, "刷新") }
    }) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { isRefreshing = true; viewModel.refreshDashboard() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            UfiScrollableColumn {
                if (state.isOffline) {
                    UfiOfflineBanner(lastUpdated = state.lastUpdated?.let { FormatUtils.formatRelativeTime(it) })
                }

                state.errorMessage?.let { e -> UfiErrorBanner(message = e, onRetry = { viewModel.refreshDashboard() }) }

                if (state.isLoading && state.deviceInfo == null) {
                    ShimmerLoading()
                    return@UfiScrollableColumn
                }

                // Section 1: Device header (flat, no card)
                state.deviceInfo?.let { info ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val netType = state.networkStatus?.network_type?.sanitizeUnknown()
                                if (netType != null) {
                                    Surface(
                                        color = networkTypeColor(netType).copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.padding(end = 6.dp)
                                    ) {
                                        Text(" $netType ", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = networkTypeColor(netType), fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                state.networkStatus?.operator?.sanitizeUnknown()?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text("${info.device.model.sanitizeUnknown() ?: "设备"} · ${info.uptime.uptime_display}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.signalInfo?.rsrp?.let { SignalBars(it) }
                    }
                }

                // Section 2: Hardware stats (2-column)
                if (state.cpuInfo != null || state.batteryInfo != null) {
                    UfiSettingsGroup {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Left column: CPU + Memory
                            Column(Modifier.weight(1f)) {
                                state.cpuInfo?.let { cpu ->
                                    val cpuColor = if (cpu.usage_percent > 80) SignalDead else SignalExcellent
                                    Text("CPU", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(FormatUtils.formatPercent(cpu.usage_percent),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = cpuColor, fontWeight = FontWeight.Bold)
                                    CompactProgressBar(
                                        progress = (cpu.usage_percent / 100.0).toFloat().coerceIn(0f, 1f),
                                        color = cpuColor)
                                }
                                state.memoryInfo?.let { mem ->
                                    val memColor = if (mem.usage_percent > 80) SignalDead else MaterialTheme.colorScheme.primary
                                    if (state.cpuInfo != null) Spacer(Modifier.height(Spacing.Medium))
                                    Text("内存 ${FormatUtils.formatBytes(mem.used)}/${FormatUtils.formatBytes(mem.total)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(FormatUtils.formatPercent(mem.usage_percent),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = memColor, fontWeight = FontWeight.Bold)
                                    CompactProgressBar(
                                        progress = (mem.usage_percent / 100.0).toFloat().coerceIn(0f, 1f),
                                        color = memColor)
                                }
                            }
                            // Right column: Battery + Temperature
                            Column(Modifier.weight(1f)) {
                                state.batteryInfo?.let { battery ->
                                    val batColor = when { battery.percent <= 15 -> BatteryLow; battery.percent <= 30 -> BatteryMedium; else -> BatteryHigh }
                                    Text(if (battery.is_charging) "电池 ⚡${battery.plugged.sanitizeUnknown() ?: "充电"}" else "电池",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${battery.percent}%",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = batColor, fontWeight = FontWeight.Bold)
                                    CompactProgressBar(
                                        progress = (battery.percent / 100f).coerceIn(0f, 1f),
                                        color = batColor)
                                }
                                state.cpuInfo?.let { cpu ->
                                    if (state.batteryInfo != null) Spacer(Modifier.height(Spacing.Medium))
                                    Text("温度", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(FormatUtils.formatTemperature(cpu.temperature),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold)
                                    state.cpuInfo?.let { c ->
                                        val maxFreq = c.cores.maxOfOrNull { it.freq_mhz }?.toInt() ?: 0
                                        Text("${c.core_count}核 · ${maxFreq}MHz",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(Spacing.Small))
                        UfiNavigationItem(title = "系统详情", description = "设备、CPU、内存、电池",
                            onClick = { navController.navigate("detail/system") })
                        UfiNavigationItem(title = "监控中心", description = "CPU、内存、流量、信号历史趋势",
                            onClick = { navController.navigate("detail/monitor") })
                    }
                }

                // Section 3: Network & Traffic (2-column)
                if (state.networkStatus != null || state.trafficRealtime != null) {
                    UfiSettingsGroup {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Left: Signal
                            Column(Modifier.weight(1f)) {
                                state.signalInfo?.rsrp?.let { rsrp ->
                                    Text("RSRP", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("$rsrp dBm", style = MaterialTheme.typography.titleLarge,
                                        color = signalColor(rsrp), fontWeight = FontWeight.Bold)
                                    Text(signalLevelText(rsrp), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 2.dp)) {
                                    state.signalInfo?.sinr?.let {
                                        Text("SINR $it dB", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    state.signalInfo?.rsrq?.let {
                                        Text("RSRQ $it dB", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    state.signalInfo?.rssi?.let {
                                        Text("RSSI $it dBm", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            // Right: Speed + status
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                state.trafficRealtime?.let { traffic ->
                                    Text("↓ ${FormatUtils.formatBytes(traffic.rx_speed)}/s",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TrafficDown)
                                    Text("↑ ${FormatUtils.formatBytes(traffic.tx_speed)}/s",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TrafficUp)
                                }
                                state.networkStatus?.let { net ->
                                    Text(if (net.network.has_internet) "已连接" else "未连接",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = if (net.network.has_internet) SignalExcellent else SignalDead,
                                        modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                        }

                        state.trafficSummary?.let { s ->
                            Spacer(Modifier.height(Spacing.Medium))
                            UfiDivider()
                            Spacer(Modifier.height(Spacing.Small))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                UfiStatItem(s.total_rx_display, "总下载")
                                UfiStatItem(s.total_tx_display, "总上传")
                            }
                        }

                        Spacer(Modifier.height(Spacing.Small))
                        UfiNavigationItem(title = "网络详情",
                            description = listOfNotNull(
                                state.networkStatus?.operator?.sanitizeUnknown(),
                                state.networkStatus?.network_type?.sanitizeUnknown()
                            ).joinToString(" · ").ifBlank { "查看详情" },
                            onClick = { navController.navigate("detail/network") })
                    }
                }

                Spacer(Modifier.height(Spacing.Large))
            }
        }
    }
}