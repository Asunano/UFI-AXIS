package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.DownsampledPoint
import com.ufi_axis.ui.components.InfoRow
import com.ufi_axis.ui.components.MonitorChart
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.monitorState.collectAsState()
    var showCleanConfirm by remember { mutableStateOf(false) }
    var cleanTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadMonitorHistory(state.selectedHours)
        viewModel.loadMonitorStorage()
    }

    UfiScreenScaffold(
        title = "监控中心",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(onClick = { cleanTarget = null; showCleanConfirm = true }) {
                Icon(Icons.Default.Delete, contentDescription = "清理")
            }
        }
    ) { padding ->
        UfiScrollableColumn(modifier = Modifier.padding(padding)) {

            // Time range selector
            UfiSettingsGroup {
                UfiSingleChipSelector(
                    options = listOf(
                        "1" to "1小时",
                        "6" to "6小时",
                        "24" to "24小时",
                        "168" to "7天"
                    ),
                    selectedValue = state.selectedHours.toString(),
                    onSelect = { value ->
                        val hours = value.toIntOrNull() ?: 24
                        viewModel.loadMonitorHistory(hours)
                    }
                )
            }

            // Loading / error
            UfiLinearLoading(isLoading = state.isLoading)

            state.errorMessage?.let { msg ->
                UfiErrorBanner(message = msg, onRetry = { viewModel.loadMonitorHistory(state.selectedHours) })
            }

            state.cleanMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // CPU Usage
            ChartCard(
                title = "CPU 使用率",
                points = state.cpuHistory,
                unit = "%",
                lineColor = MaterialTheme.colorScheme.primary
            )

            // Memory Usage
            ChartCard(
                title = "内存使用率",
                points = state.memoryHistory,
                unit = "%",
                lineColor = MaterialTheme.colorScheme.tertiary
            )

            // Traffic RX
            ChartCard(
                title = "下载速率",
                points = state.trafficRxHistory,
                unit = " B/s",
                lineColor = Color(0xFF2196F3),
                valueFormatter = ::formatBytes
            )

            // Traffic TX
            ChartCard(
                title = "上传速率",
                points = state.trafficTxHistory,
                unit = " B/s",
                lineColor = Color(0xFF4CAF50),
                valueFormatter = ::formatBytes
            )

            // Signal RSRP
            ChartCard(
                title = "信号 RSRP",
                points = state.signalRsrpHistory,
                unit = " dBm",
                lineColor = Color(0xFFFF9800)
            )

            // Signal SINR
            ChartCard(
                title = "信号 SINR",
                points = state.signalSinrHistory,
                unit = " dB",
                lineColor = Color(0xFF9C27B0)
            )

            // Battery
            ChartCard(
                title = "电池电量",
                points = state.batteryHistory,
                unit = "%",
                lineColor = Color(0xFF00BCD4),
                valueFormatter = { "%.0f".format(it) }
            )

            // Temperature
            ChartCard(
                title = "CPU 温度",
                points = state.temperatureHistory,
                unit = "°C",
                lineColor = Color(0xFFF44336)
            )

            // Storage management
            state.storageInfo?.let { storage ->
                UfiSettingsGroup {
                    UfiSectionHeader(title = "存储管理")
                    storage.tables.forEach { table ->
                        InfoRow(
                            tableDisplayName(table.name),
                            "${table.count} 条 / ${"%.1f".format(table.size_kb)} KB"
                        )
                    }
                    UfiDivider()
                    InfoRow("总计", storage.total_display)
                    Spacer(Modifier.height(Spacing.Medium))
                    UfiButtonRow {
                        UfiSmallButton(text = "清理 7 天前", onClick = {
                            cleanTarget = "all"
                            showCleanConfirm = true
                        })
                        UfiSmallButton(text = "全部清空", onClick = {
                            viewModel.cleanHistory(type = "all", days = 0)
                        })
                    }
                }
            }
        }
    }

    // Clean confirmation dialog
    if (showCleanConfirm) {
        UfiConfirmDialog(
            title = if (cleanTarget == null) "清理历史数据" else "清空所有历史",
            text = if (cleanTarget == null) "将清理 7 天前的历史数据" else "将清空所有历史监控数据，此操作不可恢复。",
            confirmText = "确认清理",
            destructive = cleanTarget != null,
            onConfirm = {
                if (cleanTarget == null) {
                    viewModel.cleanHistory(days = 7)
                } else {
                    viewModel.cleanHistory(type = "all", days = 0)
                }
                showCleanConfirm = false
            },
            onDismiss = { showCleanConfirm = false }
        )
    }
}

@Composable
private fun ChartCard(
    title: String,
    points: List<DownsampledPoint>,
    unit: String,
    lineColor: Color,
    valueFormatter: ((Double) -> String)? = null
) {
    if (points.isEmpty()) return
    UfiSettingsGroup {
        MonitorChart(
            points = points,
            label = title,
            unit = unit,
            lineColor = lineColor,
            valueFormatter = valueFormatter,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun formatBytes(bytesPerSec: Double): String {
    return when {
        bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576)
        bytesPerSec >= 1024 -> "%.1f KB/s".format(bytesPerSec / 1024)
        else -> "%.0f B/s".format(bytesPerSec)
    }
}

private fun tableDisplayName(name: String): String = when (name) {
    "cpu_history" -> "CPU"
    "memory_history" -> "内存"
    "traffic_records" -> "流量"
    "signal_history" -> "信号"
    "battery_history" -> "电池"
    "alert_records" -> "告警"
    "sms_records" -> "短信"
    else -> name
}
