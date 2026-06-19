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
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onServerConfigChanged: () -> Unit, navController: NavHostController? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val deviceSettingsState by viewModel.deviceSettingsState.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadDeviceSettings() }

    var ledOn by remember { mutableStateOf(true) }
    var perfMode by remember { mutableStateOf("balanced") }
    var wifiSleepTime by remember { mutableStateOf("0") }
    var restartScheduleOn by remember { mutableStateOf(false) }
    var restartTime by remember { mutableStateOf("00:00") }

    LaunchedEffect(deviceSettingsState.settings) {
        deviceSettingsState.settings?.asJsonObject?.let { json ->
            json.get("indicator_light_switch")?.asString?.let { ledOn = it == "1" }
            json.get("performance_mode")?.asString?.let { perfMode = if (it == "1") "performance" else "balanced" }
            json.get("sleep_sysIdleTimeToSleep")?.asString?.let { wifiSleepTime = it }
            json.get("restart_schedule_switch")?.asString?.let { restartScheduleOn = it == "1" }
            json.get("restart_time")?.asString?.let { restartTime = it }
        }
    }

    UfiScreenScaffold(title = "设置") { padding ->
        UfiScrollableColumn(modifier = Modifier.padding(padding)) {
            UfiCollapsibleGroup(title = "后端连接", subtitle = "Core IP、端口、Token") {
                var ip by remember { mutableStateOf(prefs.serverIp) }
                var port by remember { mutableStateOf(prefs.serverPort.toString()) }
                var token by remember { mutableStateOf(prefs.token) }
                UfiTextField(value = ip, onValueChange = { ip = it }, label = "Core IP")
                UfiFieldRow {
                    UfiDigitField(value = port, onValueChange = { port = it }, label = "端口", modifier = Modifier.weight(1f))
                    UfiTextField(value = token, onValueChange = { token = it }, label = "Token", modifier = Modifier.weight(1.5f))
                }
                UfiPrimaryButton(text = "保存", onClick = {
                    prefs.serverIp = ip; prefs.serverPort = port.toIntOrNull() ?: 8088; prefs.token = token; onServerConfigChanged()
                })
            }

            UfiCollapsibleGroup(title = "设备接口", subtitle = "网关 IP、管理密码") {
                var gwIp by remember { mutableStateOf(prefs.gatewayIp) }
                var gwPort by remember { mutableStateOf(prefs.goformPort.toString()) }
                var gwPwd by remember { mutableStateOf(prefs.goformPassword) }
                UfiTextField(value = gwIp, onValueChange = { gwIp = it }, label = "网关 IP")
                UfiFieldRow {
                    UfiDigitField(value = gwPort, onValueChange = { gwPort = it }, label = "端口", modifier = Modifier.weight(1f))
                    UfiPasswordField(value = gwPwd, onValueChange = { gwPwd = it }, label = "管理密码", modifier = Modifier.weight(1.5f))
                }
                UfiPrimaryButton(text = "同步到后端", onClick = {
                    prefs.gatewayIp = gwIp; prefs.goformPort = gwPort.toIntOrNull() ?: 8080; prefs.goformPassword = gwPwd
                    viewModel.syncGatewayConfig(gwIp, gwPwd, gwPort.toIntOrNull() ?: 8080)
                })
            }

            UfiSettingsGroup {
                UfiNavigationItem(title = "ADB WiFi", description = "无线调试连接", onClick = { navController?.navigate("detail/adb") })
                UfiDivider()
                UfiNavigationItem(title = "定时任务", description = "自动化脚本调度", onClick = { navController?.navigate("detail/tasks") })
                UfiDivider()
                UfiNavigationItem(title = "短信转发", description = "SMTP 邮件转发", onClick = { navController?.navigate("detail/sms-forward") })
                UfiDivider()
                UfiNavigationItem(title = "流量管理", description = "流量限额、校准与统计", onClick = { navController?.navigate("detail/traffic-management") })
            }

            UfiCollapsibleGroup(title = "设备控制", subtitle = "指示灯、性能模式、密码") {
                SettingsToggle(title = "指示灯", description = "设备 LED 开关", checked = ledOn,
                    onCheckedChange = { ledOn = it; viewModel.setLedEnabled(it) })
                UfiDivider()

                Text("性能模式", style = MaterialTheme.typography.bodyLarge)
                Text("CPU 频率策略", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.Small))
                UfiSingleChipSelector(
                    options = listOf("powersave" to "省电", "balanced" to "均衡", "performance" to "性能"),
                    selectedValue = perfMode,
                    onSelect = { perfMode = it; viewModel.setPerformanceMode(it) }
                )
                Spacer(Modifier.height(Spacing.Medium))
                UfiDivider()

                var oldPwd by remember { mutableStateOf("") }
                var newPwd by remember { mutableStateOf("") }
                var confirmPwd by remember { mutableStateOf("") }
                var pwdError by remember { mutableStateOf<String?>(null) }
                Text("修改管理密码", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("修改路由器 Web 管理密码", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.Small))
                UfiPasswordField(value = oldPwd, onValueChange = { oldPwd = it; pwdError = null }, label = "当前密码", showToggle = false)
                Spacer(Modifier.height(Spacing.Small))
                UfiPasswordField(value = newPwd, onValueChange = { newPwd = it; pwdError = null }, label = "新密码", showToggle = false)
                Spacer(Modifier.height(Spacing.Small))
                UfiPasswordField(value = confirmPwd, onValueChange = { confirmPwd = it; pwdError = null }, label = "确认新密码",
                    isError = confirmPwd.isNotEmpty() && confirmPwd != newPwd,
                    errorMessage = if (confirmPwd.isNotEmpty() && confirmPwd != newPwd) "密码不一致" else null)
                Spacer(Modifier.height(Spacing.Small))
                UfiPrimaryButton(text = "修改", onClick = {
                    when {
                        newPwd != confirmPwd -> pwdError = "两次密码不一致"
                        oldPwd.isEmpty() || newPwd.isEmpty() -> pwdError = "密码不能为空"
                        else -> { viewModel.changePassword(oldPwd, newPwd); pwdError = "已提交修改"; oldPwd = ""; newPwd = ""; confirmPwd = "" }
                    }
                })
                pwdError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.contains("不一致") || it.contains("为空")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            }

            UfiCollapsibleGroup(title = "网络功能", subtitle = "VoLTE、VoNR、SA、漫游、USB 共享") {
                var volteEnabled by remember { mutableStateOf(false) }
                var vonrEnabled by remember { mutableStateOf(false) }
                var saOn by remember { mutableStateOf(false) }
                var fotaOff by remember { mutableStateOf(false) }
                var roamOn by remember { mutableStateOf(false) }
                var tetherOn by remember { mutableStateOf(false) }
                var sambaOn by remember { mutableStateOf(false) }
                var nfcOn by remember { mutableStateOf(false) }

                LaunchedEffect(deviceSettingsState.settings) {
                    deviceSettingsState.settings?.asJsonObject?.let { json ->
                        json.get("roam_setting_option")?.asString?.let { roamOn = it == "1" || it == "on" }
                        // SA mode: 优先读 BearerPreference（与 SET_BEARER_PREFERENCE goformId 一致），fallback 到 net_select
                        val bearer = json.get("BearerPreference")?.asString
                            ?: json.get("net_select")?.asString
                        bearer?.let { saOn = it == "Only_5G" }
                        json.get("usb_port_switch")?.asString?.let { tetherOn = it == "1" }
                        json.get("samba_switch")?.asString?.let { sambaOn = it == "1" }
                        json.get("wifi_nfc_switch")?.asString?.let { nfcOn = it == "1" }
                    }
                }

                SettingsToggle(title = "VoLTE", description = "4G 高清语音", checked = volteEnabled, onCheckedChange = { volteEnabled = it; viewModel.toggleVolte(it) })
                UfiDivider()
                SettingsToggle(title = "VoNR", description = "5G 高清语音", checked = vonrEnabled, onCheckedChange = { vonrEnabled = it; viewModel.toggleVonr(it) })
                UfiDivider()
                SettingsToggle(title = "5G SA 模式", description = "独立组网模式", checked = saOn, onCheckedChange = { saOn = it; viewModel.setSaMode(it) })
                UfiDivider()
                SettingsToggle(title = "禁用 FOTA", description = "阻止运营商推送更新", checked = fotaOff, onCheckedChange = { fotaOff = it; viewModel.setFotaDisabled(it) })
                UfiDivider()
                SettingsToggle(title = "网络漫游", description = "跨基站自动切换", checked = roamOn, onCheckedChange = { roamOn = it; viewModel.setRoamingEnabled(it) })
                UfiDivider()
                SettingsToggle(title = "USB 网络共享", description = "通过 USB 共享网络", checked = tetherOn, onCheckedChange = { tetherOn = it; viewModel.setUsbTethering(it) })
                UfiDivider()
                SettingsToggle(title = "文件共享 (SAMBA)", description = "SMB 局域网文件共享", checked = sambaOn, onCheckedChange = { sambaOn = it; viewModel.setSambaSetting(it) })
                UfiDivider()
                SettingsToggle(title = "WiFi NFC", description = "NFC 触碰连接", checked = nfcOn, onCheckedChange = { nfcOn = it; viewModel.setWifiNfc(it) })
            }

            UfiCollapsibleGroup(title = "系统管理", subtitle = "WiFi 休眠、定时重启") {
                Text("WiFi 休眠", style = MaterialTheme.typography.bodyLarge)
                Text("无流量时自动关闭 WiFi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.Small))
                UfiSingleChipSelector(
                    options = listOf("0" to "永不", "5" to "5分钟", "10" to "10分钟", "15" to "15分钟", "30" to "30分钟"),
                    selectedValue = wifiSleepTime,
                    onSelect = { wifiSleepTime = it; viewModel.setWifiSleep(it) }
                )
                Spacer(Modifier.height(Spacing.Medium))
                UfiDivider()

                SettingsToggle(title = "定时重启", description = "每天自动重启设备", checked = restartScheduleOn, onCheckedChange = {
                    restartScheduleOn = it; viewModel.setRestartSchedule(it, restartTime)
                })
                if (restartScheduleOn) {
                    Spacer(Modifier.height(Spacing.Small))
                    UfiTextField(value = restartTime, onValueChange = { restartTime = it }, label = "重启时间", placeholder = "HH:MM")
                    Spacer(Modifier.height(Spacing.Small))
                    UfiSmallButton(text = "保存", onClick = { viewModel.setRestartSchedule(restartScheduleOn, restartTime) })
                }
                Spacer(Modifier.height(Spacing.Medium))
                UfiDivider()
                UfiButtonRow {
                    OutlinedButton(onClick = { viewModel.shutdownDevice() }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("关机") }
                    OutlinedButton(onClick = { viewModel.rebootDevice() }, modifier = Modifier.weight(1f)) { Text("重启") }
                }
            }

            UfiSettingsGroup {
                var debugMode by remember { mutableStateOf(prefs.debugMode) }
                SettingsToggle(title = "调试日志", description = if (debugMode) "已开启" else "关闭", checked = debugMode,
                    onCheckedChange = { debugMode = it; prefs.debugMode = it; viewModel.syncDebugMode(it) })
                UfiDivider()
                UfiNavigationItem(title = "查看日志", description = "查看后端运行日志",
                    onClick = { navController?.navigate("detail/debug-log") })
                UfiDivider()
                val alertsState by viewModel.alertsState.collectAsState()
                LaunchedEffect(Unit) { viewModel.loadAlerts() }
                alertsState.config?.let { config ->
                    var alertEnabled by remember(config) { mutableStateOf(config.enabled) }
                    SettingsToggle(title = "系统告警", description = if (alertEnabled) "已开启" else "关闭", checked = alertEnabled,
                        onCheckedChange = { viewModel.updateAlertConfig(config.copy(enabled = it)) })
                    UfiDivider()
                }
                InfoRow("版本", "1.0.0")
            }
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}