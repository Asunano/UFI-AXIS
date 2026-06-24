package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.module.parseWifiSettings
import com.ufi_axis.viewmodel.module.parseWifiClients

/** WiFi 热点设置弹窗 — 精简设备列表，仅显示数量 */
@Composable
fun WifiSettingsDialog(
    viewModel: MainViewModel,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    val state by viewModel.networkState.collectAsState()
    val palette = LocalResolvedPalette.current

    val wifi = remember(state.wifiSettings) { parseWifiSettings(state.wifiSettings) }
    var ssid by remember(wifi.ssid) { mutableStateOf(wifi.ssid) }
    var pwd by remember(wifi.passphrase) { mutableStateOf(wifi.passphrase) }

    val clientsParsed = remember(state.wifiClients) { parseWifiClients(state.wifiClients) }
    val deviceCount = remember(clientsParsed) {
        (clientsParsed.stationList?.size() ?: 0) + (clientsParsed.lanStationList?.size() ?: 0)
    }

    val powerOptions = listOf("25%" to "25%", "50%" to "50%", "75%" to "75%", "100%" to "100%")

    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "WiFi 热点设置"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.DialogPaddingH)
        ) {
            // 基本开关
            UfiSettingsToggle(
                title = "热点开关",
                description = if (state.wifiEnabled) "已开启 · ${wifi.ssid.ifBlank { "未命名" }}" else "已关闭",
                checked = state.wifiEnabled,
                onCheckedChange = { viewModel.network.setWifiEnabled(it) }
            )

            Spacer(Modifier.height(Spacing.Medium))

            // SSID + 密码
            UfiTextField(value = ssid, onValueChange = { ssid = it }, label = "网络名称 (SSID)")
            Spacer(Modifier.height(8.dp))
            UfiPasswordField(value = pwd, onValueChange = { pwd = it }, label = "连接密码")

            Spacer(Modifier.height(Spacing.Medium))

            // 频段
            Text("WiFi 频段", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = palette.textPrimary)
            Spacer(Modifier.height(4.dp))
            UfiSingleChipSelector(
                options = listOf("chip1" to "2.4 GHz", "chip2" to "5 GHz"),
                selectedValue = wifi.activeChip,
                onSelect = { viewModel.network.switchWifiChip(it) }
            )

            Spacer(Modifier.height(12.dp))

            // 发射功率
            Text("发射功率", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = palette.textPrimary)
            Spacer(Modifier.height(4.dp))
            UfiSingleChipSelector(
                options = powerOptions,
                selectedValue = "100%",
                onSelect = { level ->
                    val pct = level.removeSuffix("%").toIntOrNull() ?: 100
                    viewModel.network.setWifiPower(pct)
                }
            )

            // 设备计数（精简展示）
            if (deviceCount > 0) {
                Spacer(Modifier.height(Spacing.Medium))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DevicesOther, null, Modifier.size(18.dp), tint = palette.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text("已连接 $deviceCount 台设备", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                }
            }
        }

        Spacer(Modifier.height(Spacing.Medium))

        // 保存 + 关闭
        UfiPrimaryButton(
            text = "保存配置",
            onClick = {
                viewModel.network.setWifiConfig(mapOf(
                    "ssid" to ssid,
                    "passphrase" to pwd,
                    "chip_index" to if (wifi.activeChip == "chip2") "1" else "0"
                ))
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.DialogPaddingH)
        )
        Spacer(Modifier.height(8.dp))
    }
}