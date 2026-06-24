package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.DownsampledPoint
import com.ufi_axis.data.model.MonitorStorageResponse
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.ui.util.rememberIsActive
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.DashboardState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(viewModel: MainViewModel, navController: NavHostController) {
    val monitorState by viewModel.monitorState.collectAsState()
    val dashState by viewModel.dashboardState.collectAsState()
    var showCleanConfirm by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    LaunchedEffect(Unit) {
        viewModel.dashboard.loadMonitorHistory(monitorState.selectedHours, force = true)
        viewModel.dashboard.loadMonitorStorage()
    }

    val isActive = rememberIsActive()
    LaunchedEffect(isActive.value) {
        if (!isActive.value) return@LaunchedEffect
        while (true) {
            delay(30_000)
            viewModel.dashboard.loadMonitorHistory(monitorState.selectedHours, force = true)
        }
    }

    UfiScreenScaffold(
        title = "监控中心",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(onClick = { showCleanConfirm = true }) {
                Icon(Icons.Default.Delete, contentDescription = "清理")
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                // ── 时间范围选择 ──
                UfiSettingsGroup {
                    UfiSingleChipSelector(
                        options = listOf("1" to "1小时", "6" to "6小时", "24" to "24小时", "168" to "7天"),
                        selectedValue = monitorState.selectedHours.toString(),
                        onSelect = { it.toIntOrNull()?.let { viewModel.dashboard.loadMonitorHistory(it, force = true) } }
                    )
                }

                UfiLinearLoading(isLoading = monitorState.isLoading)

                monitorState.errorMessage?.let { msg ->
                    UfiErrorBanner(message = msg, onRetry = { viewModel.dashboard.loadMonitorHistory(monitorState.selectedHours) })
                }

                // ── 实时指标卡片（复用 dashState 缓存，零额外开销） ──
                CurrentMetricsRow(dashState)

                // ── 系统资源组 ──
                UfiSettingsGroup {
                    UfiSectionHeader(title = "系统资源")
                    MonitorChart("CPU 使用率", monitorState.cpuHistory, "%", MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    MonitorChart("内存使用率", monitorState.memoryHistory, "%", MaterialTheme.colorScheme.tertiary)
                }

                // ── 网络流量组 ──
                UfiSettingsGroup {
                    UfiSectionHeader(title = "网络流量")
                    MonitorChart("下载速率", monitorState.trafficRxHistory, " B/s", Color(0xFF2196F3), ::formatBytes)
                    Spacer(Modifier.height(8.dp))
                    MonitorChart("上传速率", monitorState.trafficTxHistory, " B/s", Color(0xFF4CAF50), ::formatBytes)
                }

                // ── 信号质量组 ──
                UfiSettingsGroup {
                    UfiSectionHeader(title = "信号质量")
                    MonitorChart("RSRP", monitorState.signalRsrpHistory, " dBm", Color(0xFFFF9800))
                    Spacer(Modifier.height(8.dp))
                    MonitorChart("SINR", monitorState.signalSinrHistory, " dB", Color(0xFF9C27B0))
                }

                // ── 电源与温度组 ──
                UfiSettingsGroup {
                    UfiSectionHeader(title = "电源与温度")
                    MonitorChart("电池电量", monitorState.batteryHistory, "%", Color(0xFF00BCD4)) { "%.0f".format(it) }
                    Spacer(Modifier.height(8.dp))
                    MonitorChart("CPU 温度", monitorState.temperatureHistory, "°C", Color(0xFFF44336))
                }

                // ── 存储管理 ──
                monitorState.storageInfo?.let {
                    StorageSection(it, onClean7d = { showCleanConfirm = true }, onClearAll = {
                        viewModel.dashboard.cleanHistory(type = "all", days = 0)
                        toastMessage = ToastMessage("数据已清空", ToastType.SUCCESS)
                    })
                }

                Spacer(Modifier.height(Spacing.Large))
            }
            UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
        }
    }

    if (showCleanConfirm) {
        UfiConfirmDialog(
            title = "清理历史数据",
            text = "将清理 7 天前的历史数据。如需清空所有请使用「全部清空」。",
            confirmText = "确认清理",
            onConfirm = { viewModel.dashboard.cleanHistory(days = 7); showCleanConfirm = false; toastMessage = ToastMessage("数据清理完成", ToastType.SUCCESS) },
            onDismiss = { showCleanConfirm = false }
        )
    }
}

// ==================== 子组件 ====================

@Composable
private fun CurrentMetricsRow(dashState: DashboardState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.PagePadding, vertical = Spacing.Small),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        dashState.cpuInfo?.let { MetricMiniCard("CPU", "%.1f%%".format(it.usage_percent), Icons.Default.Memory, MaterialTheme.colorScheme.primary, Modifier.weight(1f)) }
        dashState.memoryInfo?.let { MetricMiniCard("内存", "%.1f%%".format(it.usage_percent), Icons.Default.Storage, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f)) }
        dashState.signalInfo?.let { sig ->
            val sigValue = sig.rsrp ?: sig.rssi ?: 0
            val sigUnit = if (sig.rsrp != null) " dBm" else if (sig.rssi != null) " dBm" else ""
            MetricMiniCard("信号", "$sigValue$sigUnit", Icons.Default.SignalCellularAlt, Color(0xFFFF9800), Modifier.weight(1f))
        }
        dashState.batteryInfo?.let { MetricMiniCard("电池", "${it.level}%", Icons.Default.BatteryStd, Color(0xFF00BCD4), Modifier.weight(1f)) }
    }
}

@Composable
private fun MetricMiniCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = UfiCardDefaults.legacyShape, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MonitorChart(title: String, points: List<DownsampledPoint>, unit: String, lineColor: Color, valueFormatter: ((Double) -> String)? = null) {
    if (points.isEmpty()) return
    UfiMonitorChart(points = points, label = title, unit = unit, lineColor = lineColor, valueFormatter = valueFormatter, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun StorageSection(storage: MonitorStorageResponse, onClean7d: () -> Unit, onClearAll: () -> Unit) {
    UfiSettingsGroup {
        UfiSectionHeader(title = "存储管理")
        val defaultBarColor = MaterialTheme.colorScheme.primary
        storage.tables.forEach { table ->
            val ratio = remember(table.size_kb, storage.total_kb) { if (storage.total_kb > 0) (table.size_kb / storage.total_kb).toFloat().coerceIn(0f, 1f) else 0f }
            val barColor = remember(ratio, defaultBarColor) {
                when { ratio > 0.5f -> Color(0xFFF44336); ratio > 0.3f -> Color(0xFFFF9800); else -> defaultBarColor }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.InnerPadding, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tableDisplayName(table.name), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("${table.count} 条", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                UfiCompactProgressBar(progress = ratio, color = barColor, modifier = Modifier.width(60.dp))
                Spacer(Modifier.width(8.dp))
                Text("${"%.1f".format(table.size_kb)} KB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        UfiDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.InnerPadding), verticalAlignment = Alignment.CenterVertically) {
            Text("总计", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(storage.total_display, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(Spacing.Medium))
        UfiButtonRow {
            UfiSmallButton(text = "清理 7 天前", onClick = onClean7d)
            UfiSmallButton(text = "全部清空", onClick = onClearAll)
        }
    }
}

private fun formatBytes(bytesPerSec: Double): String = when {
    bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576)
    bytesPerSec >= 1024 -> "%.1f KB/s".format(bytesPerSec / 1024)
    else -> "%.0f B/s".format(bytesPerSec)
}

private fun tableDisplayName(name: String): String = when (name) {
    "cpu_history" -> "CPU"; "memory_history" -> "内存"; "traffic_records" -> "流量"
    "signal_history" -> "信号"; "battery_history" -> "电池"; "alert_records" -> "告警"
    "sms_records" -> "短信"; else -> name
}