package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.data.model.DeviceSettingsResponse
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis_core.contract.NetworkMode


/**
 * 网络制式档位的中文标签。键是 **contract 别名**（`NetworkMode.UI_OPTIONS`），
 * 顺序由 `UI_OPTIONS` 决定；下发走别名，core 侧 `toBearer()` 负责换算成设备取值。
 * `NetworkModeScreen` 与 `NetworkModeDialog` 共用这一份，避免两处漂移。
 */
internal val NETWORK_MODE_LABELS: Map<String, String> = mapOf(
    NetworkMode.AUTO to "自动 (4G/5G)",
    NetworkMode.ONLY_5G to "5G SA",
    NetworkMode.LTE_AND_5G to "4G/5G NSA",
    NetworkMode.ONLY_LTE to "仅 4G",
    NetworkMode.WCDMA_AND_LTE to "4G/3G",
    NetworkMode.ONLY_WCDMA to "仅 3G"
)

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

    val connModeJson = connModeOf(deviceSettingsState.settings)
    val netModeJson = netModeOf(deviceSettingsState.settings)

    // T15：档位键统一为 contract 别名（下发时由 core 的 NetworkMode.toBearer 换算成设备取值）。
    // 设备回读的 net_select 是 Bearer 取值域，比对前先经 fromBearer 换算。
    val currentMode = NetworkMode.fromBearer(netModeJson)

    // 2026-09-11：「切换中」优先于回读值。设备在重新注册期间 /api/device/settings 仍报**旧**档位，
    // 直接按回读值高亮就会出现"点了 4G，界面还是仅 5G，过一会儿才自己变对"。
    val pendingMode = state.pendingNetworkMode
    val displayMode = pendingMode ?: currentMode
    val switching = pendingMode != null


    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "连接与网络模式",
        icon = rememberVectorPainter(Icons.Filled.SignalCellularAlt),
        showCloseButton = false
    ) {
        UfiDialogBody {
            // ── 当前状态概览 ──
            Row(Modifier.fillMaxWidth()) {
                UfiStatItem(
                    value = if (connModeJson == "auto") "自动拨号" else "手动拨号",
                    label = "连接模式",
                    modifier = Modifier.weight(1f)
                )
                UfiStatItem(
                    value = NETWORK_MODE_LABELS[displayMode] ?: netModeJson,
                    label = if (switching) "网络制式（切换中）" else "网络制式",
                    modifier = Modifier.weight(1f),
                    valueColor = when (displayMode) {
                        NetworkMode.ONLY_WCDMA, NetworkMode.ONLY_LTE -> palette.error
                        NetworkMode.LTE_AND_5G -> palette.accentSecondary
                        NetworkMode.ONLY_5G -> palette.accent
                        else -> Color.Unspecified
                    }
                )
            }

            // ── 连接模式 ──
            UfiDialogField("连接模式") {
                // 2026-08-31：两个手绘 FilterChip → 公共 UfiOptionGrid（双栏，leading 槽位保留勾选图标）
                val isAuto = connModeJson == "auto"
                UfiOptionGrid(
                    options = listOf(
                        UfiOptionItem(
                            value = "auto",
                            label = "自动拨号",
                            leading = {
                                Icon(
                                    if (isAuto) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    null, Modifier.size(18.dp)
                                )
                            }
                        ),
                        UfiOptionItem(
                            value = "manual",
                            label = "手动拨号",
                            leading = {
                                Icon(
                                    if (!isAuto) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    null, Modifier.size(18.dp)
                                )
                            }
                        )
                    ),
                    selectedValue = if (isAuto) "auto" else "manual",
                    onSelect = { viewModel.network.setConnectionMode(it) },
                    columns = 2
                )
            }

            // ── 网络制式 ──
            UfiDialogField("网络制式") {
                Text(
                    when {
                        // 切换中：说清"设备在搜网"，让用户知道要等
                        switching -> "正在切换到「${NETWORK_MODE_LABELS[displayMode] ?: displayMode}」，设备重新搜网需要一点时间"
                        // 回读预算用尽仍未报出目标档位：明说，不静默停在旧值上
                        state.modeSwitchTimedOut -> "设备尚未完成切换，可稍后刷新查看"
                        else -> "切换后设备将重新搜网"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.modeSwitchTimedOut && !switching) palette.error else palette.textSecondary
                )
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth()) {
                    NetworkMode.UI_OPTIONS.forEach { key ->
                        val label = NETWORK_MODE_LABELS[key] ?: key
                        val isSelected = key == displayMode
                        val chipColor = when {
                            isSelected -> palette.accent
                            key == NetworkMode.ONLY_WCDMA || key == NetworkMode.ONLY_LTE -> palette.error.copy(alpha = 0.7f)
                            key == NetworkMode.ONLY_5G -> palette.accent.copy(alpha = 0.7f)
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
                                    label,
                                    style = UfiTextStyles.body.copy(
                                        fontWeight = if (isSelected) UfiWeight.Strong else UfiWeight.Regular
                                    ),
                                    color = palette.textPrimary
                                )
                                Spacer(Modifier.weight(1f))
                                if (isSelected) {
                                    Surface(
                                        color = palette.accent.copy(alpha = 0.15f),
                                        shape = UfiCardDefaults.trackShape
                                    ) {
                                        Text(
                                            if (switching) "切换中" else "当前",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall, color = palette.accent
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun connModeOf(settings: DeviceSettingsResponse?): String =
    if (settings?.manualConnection == true) "manual" else "auto"

/**
 * 读取设备当前档位。**优先 `BearerPreference`**：写入侧（`POST /api/network/mode` → goform
 * `SET_BEARER_PREFERENCE`）改的就是这个字段，web 的 `NetworkView` 也读它；`net_select` 是老字段，
 * 取值域未经证实，仅作回退（优先级判断在 [DeviceSettingsResponse.networkMode] 里）。
 * 交给 `NetworkMode.fromBearer()` 换算（未识别值原样返回，因此老设备回 `AUTO` 这类
 * 别名同名值时仍能正确高亮）。
 */
internal fun netModeOf(settings: DeviceSettingsResponse?): String =
    settings?.networkMode ?: NetworkMode.AUTO