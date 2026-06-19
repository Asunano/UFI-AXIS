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
import com.ufi_axis.viewmodel.MainViewModel
import com.google.gson.JsonParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.networkState.collectAsState()
    val deviceSettingsState by viewModel.deviceSettingsState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshNetwork()
        viewModel.loadBandStatus()
        viewModel.loadDeviceSettings()
        viewModel.loadBlacklist()
        viewModel.restoreWifiChipPreference()
    }

    UfiScreenScaffold(title = "网络", actions = { IconButton(onClick = { viewModel.refreshNetwork() }) { Icon(Icons.Default.Refresh, null) } }) { padding ->
        UfiScrollableColumn(modifier = Modifier.padding(padding)) {
            state.errorMessage?.let { e -> UfiErrorBanner(message = e) }

            state.signalInfo?.let { sig ->
                UfiSettingsGroup {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        sig.rsrp?.let { rsrp ->
                            Column(Modifier.weight(1f)) {
                                Text("RSRP", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$rsrp dBm", style = MaterialTheme.typography.titleLarge,
                                    color = signalColor(rsrp), fontWeight = FontWeight.Bold)
                                Text(signalLevelText(rsrp), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            SignalBars(rsrp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        sig.sinr?.let { UfiStatItem("$it dB", "SINR") }
                        sig.rsrq?.let { UfiStatItem("$it dB", "RSRQ") }
                        sig.rssi?.let { UfiStatItem("$it dBm", "RSSI") }
                    }
                    if (sig.rat != null || sig.operator != null) {
                        Spacer(Modifier.height(8.dp))
                        UfiDivider()
                        Spacer(Modifier.height(4.dp))
                        sig.rat?.let { InfoRow("制式", it) }
                        sig.operator?.let { InfoRow("运营商", it) }
                    }
                }
            }

            UfiCollapsibleGroup(title = "移动网络", subtitle = "拨号、连接模式、数据开关") {
                UfiLinearLoading(isLoading = state.isLoading)

                Text("蜂窝数据连接", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                UfiButtonRow {
                    OutlinedButton(onClick = { viewModel.connectNetwork() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("拨号")
                    }
                    OutlinedButton(onClick = { viewModel.disconnectNetwork() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.LinkOff, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("断开")
                    }
                }
                Spacer(Modifier.height(8.dp))
                UfiDivider()

                var showConnMode by remember { mutableStateOf(false) }
                var connMode by remember { mutableStateOf("auto") }
                LaunchedEffect(deviceSettingsState.settings) {
                    deviceSettingsState.settings?.asJsonObject?.get("connection_mode")?.asString?.let {
                        connMode = if (it == "AUTO" || it == "auto" || it == "0") "auto" else "manual"
                    }
                }
                SettingsValue(title = "连接模式", description = "自动或手动拨号",
                    value = if (connMode == "auto") "自动拨号" else "手动拨号", onClick = { showConnMode = true })
                if (showConnMode) {
                    UfiChoiceSheet(title = "连接模式", options = listOf("auto" to "自动拨号", "manual" to "手动拨号"),
                        selectedValue = connMode, onDismiss = { showConnMode = false },
                        onSelect = { connMode = it; viewModel.setConnectionMode(it) })
                }
                UfiDivider()
                SettingsToggle(title = "移动数据", description = if (state.mobileDataEnabled) "已开启" else "已关闭",
                    checked = state.mobileDataEnabled, onCheckedChange = { viewModel.toggleMobileData(it) })
                UfiDivider()

                var showMode by remember { mutableStateOf(false) }
                var selectedMode by remember { mutableStateOf("AUTO") }
                val modeLabels = mapOf("AUTO" to "自动", "LTE_AND_5G" to "4G/5G NSA", "Only_5G" to "5G SA",
                    "Only_LTE" to "仅 4G", "Only_WCDMA" to "仅 3G", "LTE_WCDMA" to "4G/3G")
                LaunchedEffect(deviceSettingsState.settings) {
                    val json = deviceSettingsState.settings?.asJsonObject
                    val bearer = json?.get("BearerPreference")?.asString
                        ?: json?.get("net_select")?.asString
                    bearer?.let {
                        selectedMode = when (it) {
                            "WL_AND_5G", "AUTO" -> "AUTO"
                            "Only_5G" -> "Only_5G"
                            "LTE_AND_5G" -> "LTE_AND_5G"
                            "Only_LTE" -> "Only_LTE"
                            "Only_WCDMA" -> "Only_WCDMA"
                            "WCDMA_AND_LTE" -> "LTE_WCDMA"
                            else -> it
                        }
                    }
                }
                SettingsValue(title = "网络模式", description = "选择接入技术",
                    value = modeLabels[selectedMode] ?: selectedMode, onClick = { showMode = true })
                if (showMode) {
                    UfiChoiceSheet(title = "网络模式", options = modeLabels.entries.map { it.key to it.value },
                        selectedValue = selectedMode, onDismiss = { showMode = false },
                        onSelect = { selectedMode = it; viewModel.setNetworkMode(it) })
                }
            }

            UfiCollapsibleGroup(title = "频段锁定", subtitle = "LTE / NR 频段选择") {
                val lteBands = listOf(1, 3, 5, 8, 34, 38, 39, 40, 41)
                val nrBands = listOf(1, 5, 8, 28, 41, 78)
                var selectedLte by remember { mutableStateOf(setOf<Int>()) }
                var selectedNr by remember { mutableStateOf(setOf<Int>()) }

                LaunchedEffect(state.bandStatus) {
                    state.bandStatus?.asJsonObject?.let { json ->
                        json.get("lte_band_lock")?.asString?.let {
                            selectedLte = it.split(",").mapNotNull { v -> v.trim().toIntOrNull() }.toSet()
                        }
                        json.get("nr_band_lock")?.asString?.let {
                            selectedNr = it.split(",").mapNotNull { v -> v.trim().toIntOrNull() }.toSet()
                        }
                    }
                }

                Text("LTE 频段", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                UfiIntChipSelector(values = lteBands, selectedValues = selectedLte, onToggle = { band ->
                    selectedLte = if (band in selectedLte) selectedLte - band else selectedLte + band
                }, prefix = "B")
                Spacer(Modifier.height(8.dp))
                Text("NR 频段", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                UfiIntChipSelector(values = nrBands, selectedValues = selectedNr, onToggle = { band ->
                    selectedNr = if (band in selectedNr) selectedNr - band else selectedNr + band
                }, prefix = "N")
                Spacer(Modifier.height(8.dp))
                UfiButtonRow {
                    Button(onClick = {
                        if (selectedLte.isNotEmpty()) viewModel.lockBand("lte", selectedLte.sorted().joinToString(","), "lock")
                        if (selectedNr.isNotEmpty()) viewModel.lockBand("nr", selectedNr.sorted().joinToString(","), "lock")
                    }, modifier = Modifier.weight(1f)) { Text("锁定") }
                    OutlinedButton(onClick = { viewModel.lockBand("all", "", "unlock"); selectedLte = emptySet(); selectedNr = emptySet() },
                        modifier = Modifier.weight(1f)) { Text("解锁全部") }
                }
                Spacer(Modifier.height(8.dp))
                UfiDivider()
                Spacer(Modifier.height(4.dp))

                // 基站管理入口
                UfiNavigationItem(title = "基站管理", description = "邻区列表、基站锁定",
                    onClick = { navController.navigate("detail/cell-lock") })
            }

            val wifiJson = state.wifiSettings?.asJsonObject
            val wifiInner = try {
                wifiJson?.get("settings")?.asString?.let { JsonParser.parseString(it)?.asJsonObject } ?: wifiJson
            } catch (_: Exception) { wifiJson }
            val currentSsid = wifiInner?.get("wifi_chip1_ssid1_ssid")?.asString ?: ""
            val currentPwd = wifiInner?.get("wifi_chip1_ssid1_passphrase")?.asString ?: ""
            val activeChip = wifiInner?.get("wifi_chip")?.asString ?: "chip1"

            UfiCollapsibleGroup(title = "热点", subtitle = if (state.wifiEnabled) "已开启 \u00B7 $currentSsid" else "已关闭") {
                SettingsToggle(title = "热点开关", description = if (state.wifiEnabled) "已开启 \u00B7 $currentSsid" else "已关闭",
                    checked = state.wifiEnabled, onCheckedChange = { viewModel.setWifiEnabled(it) })
                UfiDivider()

                Text("WiFi 频段", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                UfiSingleChipSelector(
                    options = listOf("chip1" to "2.4 GHz", "chip2" to "5 GHz"),
                    selectedValue = activeChip,
                    onSelect = { viewModel.switchWifiChip(it) }
                )
                Spacer(Modifier.height(8.dp))
                UfiDivider()

                var ssid by remember(currentSsid) { mutableStateOf(currentSsid) }
                var pwd by remember(currentPwd) { mutableStateOf(currentPwd) }
                var saveMsg by remember { mutableStateOf<String?>(null) }
                UfiTextField(value = ssid, onValueChange = { ssid = it; saveMsg = null }, label = "热点名称 (SSID)",
                    supportingText = { Text("当前: $currentSsid") })
                Spacer(Modifier.height(4.dp))
                UfiPasswordField(value = pwd, onValueChange = { pwd = it; saveMsg = null }, label = "热点密码",
                    placeholder = if (currentPwd.isNotEmpty()) "当前: ${"*".repeat(currentPwd.length)} (留空不修改)" else "留空则不修改")
                Spacer(Modifier.height(8.dp))
                saveMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(4.dp)) }
                UfiButtonRow {
                    UfiPrimaryButton(text = "保存", onClick = {
                        val config = mutableMapOf<String, Any>("ssid" to ssid)
                        if (pwd.isNotBlank() && pwd != currentPwd) config["passphrase"] = pwd
                        // 传递当前芯片 index 确保写入正确的 AP
                        val chipIdx = if (activeChip == "chip2") "1" else "0"
                        config["chip_index"] = chipIdx
                        viewModel.setWifiConfig(config); viewModel.refreshNetwork(); saveMsg = "热点设置已保存"
                    }, modifier = Modifier.weight(1f))
                    UfiSecondaryButton(text = "刷新", onClick = { viewModel.refreshNetwork(); ssid = currentSsid; pwd = currentPwd; saveMsg = null }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
                UfiButtonRow {
                    OutlinedButton(onClick = { viewModel.setWifiPower(2); viewModel.refreshNetwork() }, modifier = Modifier.weight(1f)) { Text("高功率") }
                    OutlinedButton(onClick = { viewModel.setWifiPower(0); viewModel.refreshNetwork() }, modifier = Modifier.weight(1f)) { Text("低功率") }
                    OutlinedButton(onClick = { viewModel.setWifiPower(1); viewModel.refreshNetwork() }, modifier = Modifier.weight(1f)) { Text("中功率") }
                }
            }

            val clientsJson = state.wifiClients?.asJsonObject
            val wifiStations = try { clientsJson?.getAsJsonArray("station_list") } catch (_: Exception) { null }
            val lanStations = try { clientsJson?.getAsJsonArray("lan_station_list") } catch (_: Exception) { null }
            val allStations = buildList {
                wifiStations?.forEach { add(it) }
                lanStations?.forEach { add(it) }
            }
            val clientCount = allStations.size

            // 解析黑名单
            val blackJson = state.blacklistInfo?.let {
                try { JsonParser.parseString(it.toString()).asJsonObject } catch (_: Exception) { null }
            }
            val blackMacs = (blackJson?.get("BlackMacList")?.asString ?: "")
                .split(";").filter { it.isNotBlank() }.toSet()
            val blackNames = (blackJson?.get("BlackNameList")?.asString ?: "")
                .split(";").filter { it.isNotBlank() }
            val blackNameByMac = blackMacs.zip(
                blackNames + List((blackMacs.size - blackNames.size).coerceAtLeast(0)) { "" }
            ).toMap()

            if (clientCount > 0) {
                UfiSettingsGroup {
                    Text("已连接设备 ($clientCount)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.Small))
                    val stations = allStations.mapNotNull { element ->
                        try {
                            val obj = element.asJsonObject
                            Triple(obj.get("hostname")?.asString ?: "未知", obj.get("ip_addr")?.asString ?: "-", obj.get("mac_addr")?.asString ?: "-")
                        } catch (_: Exception) { null }
                    }
                    stations.forEachIndexed { index, (hostname, ip, mac) ->
                        if (index > 0) UfiDivider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(hostname, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text("$ip  \u00B7  $mac", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (mac.isNotBlank() && mac != "-") {
                                val isBlocked = mac in blackMacs
                                TextButton(onClick = {
                                    if (isBlocked) viewModel.unblockDevice(mac, hostname)
                                    else viewModel.blockDevice(mac, hostname)
                                }) {
                                    Text(
                                        if (isBlocked) "解除拉黑" else "拉黑",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isBlocked) MaterialTheme.colorScheme.error
                                                else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 已拉黑设备列表
            if (blackMacs.isNotEmpty()) {
                UfiSettingsGroup {
                    Text("已拉黑设备 (${blackMacs.size})", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = Spacing.Small))
                    blackMacs.forEachIndexed { index, mac ->
                        if (index > 0) UfiDivider()
                        val name = blackNameByMac[mac] ?: ""
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name.ifBlank { "未知设备" }, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                                Text(mac, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { viewModel.unblockDevice(mac, name) }) {
                                Text("解除拉黑", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            UfiCollapsibleGroup(title = "APN", subtitle = "接入点名称配置") {
                var autoSelect by remember { mutableStateOf(true) }
                var apnName by remember { mutableStateOf("") }
                var apnValue by remember { mutableStateOf("") }
                SettingsToggle(title = "自动选择", checked = autoSelect, onCheckedChange = { autoSelect = it })
                if (!autoSelect) {
                    Spacer(Modifier.height(Spacing.Small))
                    UfiTextField(value = apnName, onValueChange = { apnName = it }, label = "APN 名称")
                    Spacer(Modifier.height(Spacing.Small))
                    UfiTextField(value = apnValue, onValueChange = { apnValue = it }, label = "APN")
                }
                Spacer(Modifier.height(Spacing.Medium))
                UfiPrimaryButton(text = "保存", onClick = { viewModel.setApnConfig(mapOf("auto_select" to autoSelect, "name" to apnName, "apn" to apnValue)) })
            }

            UfiCollapsibleGroup(title = "局域网", subtitle = "LAN / DHCP 配置") {
                UfiButtonRow {
                    OutlinedButton(onClick = { viewModel.loadLanSettings() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("查询当前配置")
                    }
                }

                // 当前配置只读展示
                state.lanSettings?.asJsonObject?.let { lan ->
                    Spacer(Modifier.height(4.dp))
                    lan.get("lan_ipaddr")?.asString?.takeIf { it.isNotBlank() }?.let { InfoRow("LAN IP", it) }
                    lan.get("lan_netmask")?.asString?.takeIf { it.isNotBlank() }?.let { InfoRow("子网掩码", it) }
                    lan.get("mac_address")?.asString?.takeIf { it.isNotBlank() }?.let { InfoRow("MAC 地址", it) }
                    lan.get("dhcpEnabled")?.asString?.takeIf { it.isNotBlank() }?.let {
                        InfoRow("DHCP", if (it == "1" || it.equals("true", true)) "已开启" else "已关闭")
                    }
                    lan.get("dhcpStart")?.asString?.takeIf { it.isNotBlank() }?.let { InfoRow("DHCP 起始", it) }
                    lan.get("dhcpEnd")?.asString?.takeIf { it.isNotBlank() }?.let { InfoRow("DHCP 结束", it) }
                    lan.get("dhcpLease_hour")?.asString?.takeIf { it.isNotBlank() }?.let { InfoRow("租约时间", "${it}h") }
                    lan.get("mtu")?.asString?.takeIf { it.isNotBlank() }?.let { InfoRow("MTU", it) }
                    lan.get("tcp_mss")?.asString?.takeIf { it.isNotBlank() }?.let { InfoRow("TCP MSS", it) }
                }

                // ---- 编辑表单 ----
                var dhcpEnabled by remember { mutableStateOf(true) }
                var lanIp by remember { mutableStateOf("") }
                var lanNetmask by remember { mutableStateOf("") }
                var dhcpStart by remember { mutableStateOf("") }
                var dhcpEnd by remember { mutableStateOf("") }
                var dhcpLease by remember { mutableStateOf("86400") }

                // 查询结果回填到表单
                state.lanSettings?.asJsonObject?.let { lan ->
                    LaunchedEffect(lan) {
                        lan.get("lan_ipaddr")?.asString?.let { lanIp = it }
                        lan.get("lan_netmask")?.asString?.let { lanNetmask = it }
                        val dhcpOn = lan.get("dhcpEnabled")?.asString
                        dhcpEnabled = dhcpOn == "1" || dhcpOn.equals("true", ignoreCase = true)
                        lan.get("dhcpStart")?.asString?.let { dhcpStart = it }
                        lan.get("dhcpEnd")?.asString?.let { dhcpEnd = it }
                        // dhcpLease_hour 是小时数，转为秒
                        val leaseHour = lan.get("dhcpLease_hour")?.asString?.toIntOrNull()
                        dhcpLease = if (leaseHour != null && leaseHour > 0) (leaseHour * 3600).toString() else "86400"
                    }
                }

                Spacer(Modifier.height(Spacing.Medium))
                UfiDivider()
                Spacer(Modifier.height(Spacing.Medium))

                SettingsToggle(
                    title = "DHCP 服务器",
                    description = if (dhcpEnabled) "自动分配 IP 地址" else "手动配置 IP",
                    checked = dhcpEnabled,
                    onCheckedChange = { dhcpEnabled = it }
                )

                Spacer(Modifier.height(Spacing.Small))
                UfiTextField(value = lanIp, onValueChange = { lanIp = it }, label = "LAN IP 地址")
                Spacer(Modifier.height(Spacing.Small))
                UfiTextField(value = lanNetmask, onValueChange = { lanNetmask = it }, label = "子网掩码")

                if (dhcpEnabled) {
                    Spacer(Modifier.height(Spacing.Small))
                    UfiTextField(value = dhcpStart, onValueChange = { dhcpStart = it }, label = "DHCP 起始 IP")
                    Spacer(Modifier.height(Spacing.Small))
                    UfiTextField(value = dhcpEnd, onValueChange = { dhcpEnd = it }, label = "DHCP 结束 IP")
                    Spacer(Modifier.height(Spacing.Small))
                    UfiDigitField(
                        value = dhcpLease, onValueChange = { dhcpLease = it },
                        label = "租约时间（秒）", modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(Spacing.Medium))
                UfiPrimaryButton(
                    text = "保存",
                    onClick = {
                        viewModel.setDhcpSetting(
                            lanIp = lanIp,
                            lanNetmask = lanNetmask,
                            dhcpType = if (dhcpEnabled) "SERVER" else "DISABLE",
                            dhcpStart = if (dhcpEnabled) dhcpStart else "",
                            dhcpEnd = if (dhcpEnabled) dhcpEnd else "",
                            dhcpLease = if (dhcpEnabled) dhcpLease else "86400"
                        )
                    }
                )
            }

            state.simInfo?.let { sim ->
                UfiSettingsGroup {
                    Text("SIM 卡", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.Small))
                    InfoRow("状态", sim.sim_state ?: "未知")
                    InfoRow("类型", sim.phone_type ?: "未知")
                    sim.imei?.let { InfoRow("IMEI", it) }
                    sim.imsi?.let { InfoRow("IMSI", it) }
                    Spacer(Modifier.height(8.dp))
                    UfiButtonRow {
                        OutlinedButton(onClick = { viewModel.switchSimSlot(1); viewModel.refreshNetwork() }, modifier = Modifier.weight(1f)) { Text("卡槽 1") }
                        OutlinedButton(onClick = { viewModel.switchSimSlot(2); viewModel.refreshNetwork() }, modifier = Modifier.weight(1f)) { Text("卡槽 2") }
                    }
                }
            }

            UfiSettingsGroup {
                UfiNavigationItem(title = "短信", description = "查看和发送短信", onClick = { navController.navigate("detail/sms") })
            }

            UfiLoadingBox(isLoading = state.isLoading) {}
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}