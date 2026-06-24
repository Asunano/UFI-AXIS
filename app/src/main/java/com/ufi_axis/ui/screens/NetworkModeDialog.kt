package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.module.parseDeviceSettingsField
import com.google.gson.JsonElement

/** 连接模式 & 网络制式设置弹窗 */
@Composable
fun NetworkModeDialog(
    viewModel: MainViewModel,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    val state by viewModel.networkState.collectAsState()
    val deviceSettingsState by viewModel.deviceSettingsState.collectAsState()
    val palette = LocalResolvedPalette.current

    val connModeJson = remember(deviceSettingsState.settings) {
        parseConnModeDlg(deviceSettingsState.settings)
    }
    val netModeJson = remember(deviceSettingsState.settings) {
        parseNetModeDlg(deviceSettingsState.settings)
    }

    val modeLabels = mapOf(
        "AUTO" to "自动 (4G/5G)",
        "LTE_AND_5G" to "4G/5G NSA",
        "Only_5G" to "5G SA",
        "Only_LTE" to "仅 4G",
        "Only_WCDMA" to "仅 3G",
        "LTE_WCDMA" to "4G/3G"
    )

    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "连接与网络模式"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.DialogPaddingH)
        ) {
            // ── 当前状态概览 ──
            Row(Modifier.fillMaxWidth()) {
                UfiStatItem(
                    value = if (connModeJson == "auto") "自动拨号" else "手动拨号",
                    label = "连接模式",
                    modifier = Modifier.weight(1f)
                )
                UfiStatItem(
                    value = modeLabels[netModeJson] ?: netModeJson,
                    label = "网络制式",
                    modifier = Modifier.weight(1f),
                    valueColor = when(netModeJson) {
                        "Only_WCDMA", "Only_LTE" -> MaterialTheme.colorScheme.error
                        "LTE_AND_5G" -> MaterialTheme.colorScheme.tertiary
                        "Only_5G" -> MaterialTheme.colorScheme.primary
                        else -> Color.Unspecified
                    }
                )
            }

            Spacer(Modifier.height(Spacing.Medium))

            // ── 连接模式 ──
            Text("连接模式", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val isAuto = connModeJson == "auto"
                FilterChip(
                    selected = isAuto,
                    onClick = { viewModel.network.setConnectionMode("auto") },
                    label = { Text("自动拨号") },
                    leadingIcon = {
                        Icon(
                            if (isAuto) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            null, Modifier.size(18.dp)
                        )
                    },
                    shape = UfiCardDefaults.inputShape,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = !isAuto,
                    onClick = { viewModel.network.setConnectionMode("manual") },
                    label = { Text("手动拨号") },
                    leadingIcon = {
                        Icon(
                            if (!isAuto) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            null, Modifier.size(18.dp)
                        )
                    },
                    shape = UfiCardDefaults.inputShape,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(Spacing.Medium))

            // ── 网络制式 ──
            Text("网络制式", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = palette.textPrimary)
            Text("切换后设备将重新搜网", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
            Spacer(Modifier.height(8.dp))

            modeLabels.entries.forEach { (key, label) ->
                val isSelected = key == netModeJson
                val chipColor = when {
                    isSelected -> palette.accent
                    key == "Only_WCDMA" || key == "Only_LTE" -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    key == "Only_5G" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    else -> palette.textSecondary
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    shape = UfiCardDefaults.inputShape,
                    color = if (isSelected) palette.accent.copy(alpha = 0.08f) else palette.cardBg,
                    onClick = { viewModel.network.setNetworkMode(key) }
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            null, Modifier.size(18.dp), tint = chipColor
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            label, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = palette.textPrimary
                        )
                        Spacer(Modifier.weight(1f))
                        if (isSelected) {
                            Surface(
                                color = palette.accent.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text("当前", modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall, color = palette.accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun parseConnModeDlg(settings: JsonElement?): String {
    val v = parseDeviceSettingsField(settings, "connection_mode", "conn_mode", "dial_mode") ?: return "auto"
    return if (v.lowercase() in listOf("manual", "1", "hand")) "manual" else "auto"
}

internal fun parseNetModeDlg(settings: JsonElement?): String =
    parseDeviceSettingsField(settings, "net_select") ?: "AUTO"