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
fun DeviceDetailScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.dashboardState.collectAsState()
    var showResetConfirm by remember { mutableStateOf(false) }

    UfiScreenScaffold(title = "设备详情", navController = navController, showBack = true) { padding ->
        UfiScrollableColumn(modifier = Modifier.padding(padding)) {
            state.deviceInfo?.let { info ->
                UfiSettingsGroup {
                    UfiSectionHeader(title = "设备型号")
                    InfoRow("品牌", info.device.brand)
                    InfoRow("型号", info.device.model)
                    InfoRow("设备名", info.device.device)
                    InfoRow("制造商", info.device.manufacturer)
                    InfoRow("Android", info.device.android_version)
                    InfoRow("SDK", info.device.sdk_version)
                    InfoRow("Build", info.device.build_id)
                }
                UfiSettingsGroup {
                    UfiSectionHeader(title = "系统信息")
                    InfoRow("内核", info.kernel)
                    InfoRow("运行时间", info.uptime.uptime_display)
                    InfoRow("SIM 状态", info.sim.sim_state)
                    InfoRow("电话类型", info.sim.phone_type)
                    InfoRow("AT 通道", if (info.at_channel.connected) "已连接" else "未连接")
                }
                UfiSettingsGroup {
                    UfiSectionHeader(title = "网络信息")
                    InfoRow("运营商", info.network.operator)
                    InfoRow("网络类型", info.network.type)
                    InfoRow("已连接", if (info.network.connected) "是" else "否")
                }

                UfiSettingsGroup {
                    UfiSectionHeader(title = "设备控制")
                    UfiButtonRow {
                        OutlinedButton(onClick = { viewModel.rebootDevice() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("重启")
                        }
                        OutlinedButton(onClick = { showResetConfirm = true }, modifier = Modifier.weight(1f)) { Text("恢复出厂") }
                    }
                    if (showResetConfirm) {
                        UfiConfirmDialog(title = "确认恢复出厂？", text = "此操作不可逆，将清除所有配置。",
                            confirmText = "确认重置", destructive = true,
                            onConfirm = { viewModel.factoryReset(); showResetConfirm = false },
                            onDismiss = { showResetConfirm = false })
                    }
                    Spacer(Modifier.height(Spacing.Medium))
                    UfiDivider()
                    var debugEnabled by remember { mutableStateOf(false) }
                    SettingsToggle(title = "ADB 调试", description = "远程调试模式", checked = debugEnabled,
                        onCheckedChange = { debugEnabled = it; viewModel.setDeviceMode(it) })
                    UfiDivider()
                    Text("USB 模式", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    UfiSingleChipSelector(
                        options = listOf("0" to "RNDIS", "1" to "Modem", "2" to "存储", "6" to "ADB+RNDIS"),
                        selectedValue = "0",
                        onSelect = { viewModel.setUsbMode(it.toInt()) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemDetailScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.dashboardState.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadCpuHistory(24) }

    UfiScreenScaffold(title = "系统资源", navController = navController, showBack = true) { padding ->
        UfiScrollableColumn(modifier = Modifier.padding(padding)) {
            state.cpuInfo?.let { cpu ->
                val maxFreq = cpu.cores.maxOfOrNull { it.freq_mhz }?.toInt() ?: 0
                val cpuColor = if (cpu.usage_percent > 80) SignalDead else SignalExcellent
                UfiSettingsGroup {
                    ProgressBar(progress = (cpu.usage_percent / 100.0).toFloat(), label = "CPU \u00B7 ${cpu.core_count}核",
                        value = FormatUtils.formatPercent(cpu.usage_percent), color = cpuColor)
                    Spacer(Modifier.height(4.dp))
                    InfoRow("最高频率", "$maxFreq MHz")
                    if (cpu.temperature > 0) InfoRow("温度", FormatUtils.formatTemperature(cpu.temperature))
                    Spacer(Modifier.height(8.dp))
                    cpu.cores.forEach { InfoRow("核心 ${it.core}", it.freq_display) }
                }
                if (state.cpuHistory.isNotEmpty()) {
                    UfiSettingsGroup {
                        LineChart(data = state.cpuHistory.map { it.timestamp to it.usagePercent.toFloat() },
                            label = "CPU 使用率 (24h)", unit = "%", maxValue = 100f, minValue = 0f,
                            lineColor = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            state.memoryInfo?.let { mem ->
                val memColor = if (mem.usage_percent > 80) SignalDead else MaterialTheme.colorScheme.primary
                UfiSettingsGroup {
                    ProgressBar(progress = (mem.usage_percent / 100.0).toFloat(), label = "内存",
                        value = FormatUtils.formatPercent(mem.usage_percent), color = memColor)
                    Spacer(Modifier.height(4.dp))
                    InfoRow("总量", FormatUtils.formatBytes(mem.total))
                    InfoRow("已用", FormatUtils.formatBytes(mem.used))
                    InfoRow("可用", FormatUtils.formatBytes(mem.available))
                }
            }
            state.storageInfo?.let { stor ->
                val storColor = if (stor.usage_percent > 85) SignalDead else MaterialTheme.colorScheme.primary
                UfiSettingsGroup {
                    ProgressBar(progress = (stor.usage_percent / 100.0).toFloat(), label = "存储",
                        value = FormatUtils.formatPercent(stor.usage_percent), color = storColor)
                    Spacer(Modifier.height(4.dp))
                    InfoRow("总量", FormatUtils.formatBytes(stor.total))
                    InfoRow("已用", FormatUtils.formatBytes(stor.used))
                    InfoRow("可用", FormatUtils.formatBytes(stor.available))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryDetailScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.dashboardState.collectAsState()

    UfiScreenScaffold(title = "电池详情", navController = navController, showBack = true) { padding ->
        UfiScrollableColumn(modifier = Modifier.padding(padding)) {
            state.batteryInfo?.let { battery ->
                val batColor = batteryColor(battery.percent)
                UfiSettingsGroup {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${battery.percent}%", style = MaterialTheme.typography.titleLarge,
                            color = batColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(FormatUtils.getBatteryStatus(battery.percent, battery.is_charging),
                                style = MaterialTheme.typography.bodyMedium)
                            InfoRow("温度", FormatUtils.formatTemperature(battery.temperature))
                            InfoRow("电压", FormatUtils.formatVoltage(battery.voltage))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    CompactProgressBar(progress = (battery.percent / 100f).coerceIn(0f, 1f), color = batColor)
                }
                UfiSettingsGroup {
                    InfoRow("充电中", if (battery.is_charging) "是 (${battery.plugged.sanitizeUnknown() ?: "充电"})" else "否")
                    InfoRow("电量等级", "${battery.level} / ${battery.scale}")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDetailScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.dashboardState.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadSignalHistory(24) }

    UfiScreenScaffold(title = "网络详情", navController = navController, showBack = true) { padding ->
        UfiScrollableColumn(modifier = Modifier.padding(padding)) {
            state.networkStatus?.let { net ->
                UfiSettingsGroup {
                    UfiSectionHeader(title = "网络状态")
                    InfoRow("运营商", net.operator)
                    InfoRow("网络类型", net.network_type)
                    InfoRow("互联网", if (net.network.has_internet) "已连接" else "未连接")
                    InfoRow("WiFi", if (net.network.has_wifi) "已连接" else "未连接")
                    InfoRow("蜂窝", if (net.network.has_cellular) "已连接" else "未连接")
                    InfoRow("移动数据", if (net.mobile_data) "开启" else "关闭")
                }
            }
            state.signalInfo?.let { sig ->
                UfiSettingsGroup {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        sig.rsrp?.let { rsrp ->
                            Column(Modifier.weight(1f)) {
                                Text("$rsrp dBm", style = MaterialTheme.typography.titleLarge,
                                    color = signalColor(rsrp), fontWeight = FontWeight.Bold)
                                Text(signalLevelText(rsrp), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            SignalBars(rsrp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    sig.sinr?.let { InfoRow("SINR", "$it dB") }
                    sig.rsrq?.let { InfoRow("RSRQ", "$it dB") }
                    sig.rssi?.let { InfoRow("RSSI", "$it dBm") }
                    sig.rat?.sanitizeUnknown()?.let { val nc = networkTypeColor(it); Text(it, color = nc, fontWeight = FontWeight.Bold) }
                    sig.operator?.let { InfoRow("运营商", it) }
                }
            }
            if (state.signalHistory.isNotEmpty()) {
                UfiSettingsGroup {
                    LineChart(data = state.signalHistory.map { it.timestamp to it.rsrp.toFloat() },
                        label = "RSRP 趋势 (24h)", unit = " dBm", lineColor = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficDetailScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.dashboardState.collectAsState()

    UfiScreenScaffold(title = "流量详情", navController = navController, showBack = true) { padding ->
        UfiScrollableColumn(modifier = Modifier.padding(padding)) {
            state.trafficRealtime?.let { traffic ->
                UfiSettingsGroup {
                    NetworkSpeedDisplay(downloadSpeed = traffic.rx_speed, uploadSpeed = traffic.tx_speed)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("下载速率", traffic.rx_speed_display)
                    InfoRow("上传速率", traffic.tx_speed_display)
                    InfoRow("累计下载", FormatUtils.formatBytes(traffic.rx_bytes))
                    InfoRow("累计上传", FormatUtils.formatBytes(traffic.tx_bytes))
                }
            }
            state.trafficSummary?.let { summary ->
                UfiSettingsGroup {
                    UfiSectionHeader(title = "今日流量")
                    InfoRow("今日下载", summary.today_rx_display)
                    InfoRow("今日上传", summary.today_tx_display)
                }
                UfiSettingsGroup {
                    UfiSectionHeader(title = "本月流量")
                    InfoRow("本月下载", summary.month_rx_display)
                    InfoRow("本月上传", summary.month_tx_display)
                }
                UfiSettingsGroup {
                    UfiSectionHeader(title = "累计统计")
                    InfoRow("总下载", summary.total_rx_display)
                    InfoRow("总上传", summary.total_tx_display)
                    InfoRow("总流量", FormatUtils.formatBytes(summary.total_bytes))
                    InfoRow("记录数", "${summary.record_count}")
                }
            }
            UfiSettingsGroup {
                UfiSectionHeader(title = "流量管理")
                Text(
                    "配置流量限额、告警阈值、自动清零等设置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { navController.navigate("detail/traffic-management") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("流量限额设置")
                }
            }
        }
    }
}