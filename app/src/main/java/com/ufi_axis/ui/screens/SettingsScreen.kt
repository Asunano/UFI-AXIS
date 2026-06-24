package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.ufi_axis.ui.animation.blurEntrance
import com.ufi_axis.ui.animation.staggeredEntrance
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onServerConfigChanged: () -> Unit,
    navController: NavHostController? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val deviceSettingsState by viewModel.deviceSettingsState.collectAsState()

    // 首次加载 + 失败自动重试（最长 3 次，间隔 5s）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        viewModel.network.loadDeviceSettings()
    }
    var settingsRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(deviceSettingsState.errorMessage) {
        if (deviceSettingsState.errorMessage != null && settingsRetry < 3) {
            kotlinx.coroutines.delay(5_000)
            settingsRetry++
            viewModel.network.loadDeviceSettings()
        }
    }

    // Device settings state
    var ledOn by remember { mutableStateOf(true) }
    var perfOn by remember { mutableStateOf(false) }
    var wifiSleepTime by remember { mutableStateOf("-1") }
    var restartScheduleOn by remember { mutableStateOf(false) }
    var restartTime by remember { mutableStateOf("00:00") }
    LaunchedEffect(deviceSettingsState.settings) {
        deviceSettingsState.settings?.asJsonObject?.let { json ->
            json.get("indicator_light_switch")?.asString?.let { ledOn = it == "1" }
            json.get("performance_mode")?.asString?.let {
                perfOn = it == "1"
            }
            json.get("sleep_sysIdleTimeToSleep")?.asString?.let { wifiSleepTime = it }
            json.get("restart_schedule_switch")?.asString?.let { restartScheduleOn = it == "1" }
            json.get("restart_time")?.asString?.let { restartTime = it }
            // USB tethering 状态无法通过 goform 查询（svc usb setFunctions 直接操作内核）
            // usb_port_switch 是 USB 调试开关 (REF #ADB)，非 tethering
        }
    }

    UfiScreenScaffold(title = "设置") { padding ->
        PullToRefreshBox(
            isRefreshing = deviceSettingsState.isLoading,
            onRefresh = {
                viewModel.network.loadDeviceSettings()
            },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
        UfiPageBackground(modifier = Modifier.blurEntrance("settings"), useGradient = true) {
            deviceSettingsState.errorMessage?.let { err ->
                com.ufi_axis.ui.components.common.UfiErrorBanner(message = err, onRetry = { viewModel.network.loadDeviceSettings() })
            }

            // ═══════════ 1. 连接与配置 ═══════════
            UfiSectionHeader(title = "连接与配置", modifier = Modifier.staggeredEntrance(0))
            UfiSettingsGroup {
                UfiNavigationItem(title = "服务器配置", description = "Core IP、Token、网关、管理密码",
                    onClick = { navController?.navigate("detail/server-config") })
            }

            // ═══════════ 2. 设备控制 ═══════════
            UfiSectionHeader(title = "设备控制", modifier = Modifier.staggeredEntrance(1))
            UfiSettingsGroup {
                UfiSettingsToggle(title = "指示灯", description = "设备 LED 开关", checked = ledOn,
                    onCheckedChange = { ledOn = it; viewModel.network.setLedEnabled(it) })
                UfiDivider()

                UfiSettingsToggle(
                    title = "性能模式",
                    description = if (perfOn) "高性能 · CPU 最大频率" else "均衡 · 自动调节",
                    checked = perfOn,
                    onCheckedChange = {
                        perfOn = it
                        viewModel.network.setPerformanceMode(if (it) "performance" else "balanced")
                    }
                )
            }

            // ═══════════ 3. 电源管理 ═══════════
            UfiSectionHeader(title = "电源管理", modifier = Modifier.staggeredEntrance(2))
            UfiSettingsGroup {
                // --- WiFi 休眠 ---
                UfiSectionGroupTitle("WiFi 休眠", "无流量时自动关闭 WiFi")
                UfiSingleChipSelector(
                    options = listOf("-1" to "从不", "5" to "5分钟", "10" to "10分钟",
                        "20" to "20分钟", "30" to "30分钟", "60" to "1小时", "120" to "2小时"),
                    selectedValue = wifiSleepTime,
                    onSelect = { wifiSleepTime = it; viewModel.network.setWifiSleep(it) }
                )

                Spacer(Modifier.height(14.dp))
                UfiDivider()
                Spacer(Modifier.height(14.dp))

                // --- 定时重启 ---
                val timeRegex = remember { Regex("^(0?[0-9]|1[0-9]|2[0-3]):(0?[0-9]|[1-5][0-9])$") }
                var timeError by remember { mutableStateOf<String?>(null) }
                UfiSectionGroupTitle("定时重启",
                    if (restartScheduleOn) "每日 $restartTime" else "已关闭")
                UfiSettingsToggle(title = "每日定时重启", description = "每天自动重启设备",
                    checked = restartScheduleOn, onCheckedChange = {
                        restartScheduleOn = it; viewModel.network.setRestartSchedule(it, restartTime)
                    })
                if (restartScheduleOn) {
                    Spacer(Modifier.height(Spacing.Small))
                    UfiTextField(
                        value = restartTime,
                        onValueChange = {
                            restartTime = it
                            timeError = if (it.isNotEmpty() && !timeRegex.matches(it))
                                "格式: HH:MM（00:00–23:59）" else null
                        },
                        label = "重启时间",
                        placeholder = "00:00",
                        isError = timeError != null,
                        errorMessage = timeError
                    )
                    Spacer(Modifier.height(Spacing.Small))
                    UfiSmallButton(text = "保存", enabled = timeError == null,
                        onClick = {
                            if (timeError == null)
                                viewModel.network.setRestartSchedule(restartScheduleOn, restartTime)
                        })
                }
            }

            Spacer(Modifier.height(16.dp))

            // ═══════════ 4. 设备操作 ═══════════
            UfiSectionHeader(title = "设备操作", modifier = Modifier.staggeredEntrance(3))
            UfiSettingsGroup {
                UfiButtonRow {
                    OutlinedButton(
                        onClick = { viewModel.network.shutdownDevice() }, modifier = Modifier.weight(1f).height(48.dp),
                        shape = UfiCardDefaults.legacyShape,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { 
                        Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("关机") 
                    }
                    OutlinedButton(
                        onClick = { viewModel.network.rebootDevice() }, modifier = Modifier.weight(1f).height(48.dp),
                        shape = UfiCardDefaults.legacyShape
                    ) { 
                        Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("重启") 
                    }
                }
            }

            // ═══════════ 5. 调试与信息 ═══════════
            UfiSectionHeader(title = "调试与信息", modifier = Modifier.staggeredEntrance(4))
            UfiSettingsGroup {
                var debugMode by remember { mutableStateOf(prefs.debugMode) }
                UfiSettingsToggle(title = "调试日志", description = if (debugMode) "已开启" else "关闭",
                    checked = debugMode, onCheckedChange = {
                        debugMode = it; prefs.debugMode = it; viewModel.tools.syncDebugMode(it)
                    })
                UfiDivider()
                UfiNavigationItem(title = "查看日志", description = "查看后端运行日志",
                    onClick = { navController?.navigate("detail/debug-log") })
                UfiDivider()
                val alertsState by viewModel.alertsState.collectAsState()
                LaunchedEffect(Unit) { viewModel.tools.loadAlerts() }
                alertsState.config?.let { config ->
                    var alertEnabled by remember(config) { mutableStateOf(config.enabled) }
                    UfiSettingsToggle(title = "系统告警", description = if (alertEnabled) "已开启" else "关闭",
                        checked = alertEnabled, onCheckedChange = {
                            viewModel.tools.updateAlertConfig(config.copy(enabled = it))
                        })
                    UfiDivider()
                }
                // 设备固件版本（goform 实时获取）
                val dashboardState by viewModel.dashboardState.collectAsState()
                LaunchedEffect(Unit) { viewModel.dashboard.loadDeviceVersion() }
                val deviceVer = dashboardState.deviceVersion
                if (deviceVer != null && deviceVer.cr_version.isNotEmpty()) {
                    UfiInfoRow("固件版本", deviceVer.cr_version)
                    UfiDivider()
                    UfiInfoRow("基带版本", deviceVer.wa_inner_version)
                } else {
                    UfiInfoRow("版本", "获取中…")
                }
            }

            Spacer(Modifier.height(Spacing.Large))
        }
        }
    }
}
