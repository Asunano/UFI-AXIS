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
 * 网络制式中文标签：**不再在 App 维护第二份**。
 * 文案真源是 core `NetworkMode.LABELS`；下发走 contract 别名，core `toBearer()` 换算设备值。
 * 优先读 API 回的 `network_mode_label`，缺省时用 contract 映射。
 */
internal fun networkModeLabel(alias: String): String = NetworkMode.label(alias)

/**
 * 网络制式设置弹窗。
 *
 * 2026-10-10 用户裁决：① 由独立页面 `detail/network-mode` 收进蜂窝高级设置页，
 * ② 删掉「连接模式 / 自动拨号·手动拨号」这一块 UI（**设备侧 `dial_mode` 配置不动**，
 * 设备侧 `dial_mode` 配置与 core 字段映射一律不动，见文件末尾说明），③ 制式选择改用公共下拉 `UfiDropdown`。
 *
 * 为什么是下拉而不是原来那一列 Surface 卡片：那一列是 5 个整行选项卡片，
 * 在弹窗里显得冗长；取值域就 5 项，下拉是这类「单选一组固定档位」的标准形态，
 * 且全站只有 `UfiDropdown` 一种实现（见 WifiSettingsDialog 的同款用法）。
 */
@Composable
fun NetworkModeDialog(
    viewModel: MainViewModel,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    val state by viewModel.networkState.collectAsState()
    val deviceSettingsState by viewModel.deviceSettingsState.collectAsState()
    val palette = LocalResolvedPalette.current

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
        title = "网络制式",
        icon = rememberVectorPainter(Icons.Filled.SignalCellularAlt),
        showCloseButton = false
    ) {
        UfiDialogBody {
            // ── 当前制式（概览）：切换中/超时两种异常态都要讲清，别静默停在旧值上 ──
            UfiStatItem(
                value = deviceSettingsState.settings?.networkModeLabel
                    ?: NetworkMode.label(displayMode),
                label = when {
                    switching -> "网络制式（切换中）"
                    state.modeSwitchTimedOut -> "网络制式（尚未完成切换）"
                    else -> "当前网络制式"
                },
                valueColor = when (displayMode) {
                    NetworkMode.ONLY_WCDMA, NetworkMode.ONLY_LTE -> palette.error
                    NetworkMode.LTE_AND_5G -> palette.accentSecondary
                    NetworkMode.ONLY_5G -> palette.accent
                    else -> Color.Unspecified
                }
            )

            // ── 制式选择：公共下拉 ──
            UfiDialogField("网络制式") {
                UfiDropdown(
                    selectedValue = displayMode,
                    options = NetworkMode.UI_OPTIONS,
                    onValueSelected = { key ->
                        // 选中当前档位不下发：设备会重新搜网，白跑一趟还让界面停在"切换中"。
                        if (key != displayMode) viewModel.network.setNetworkMode(key)
                    },
                    optionLabel = { NetworkMode.label(it) }
                )
            }

            // 切换后设备要重新搜网，这句提示把「为什么没有立刻生效」讲在前面。
            Text(
                when {
                    switching -> "正在切换到「${NetworkMode.label(displayMode)}」，设备重新搜网需要一点时间"
                    state.modeSwitchTimedOut -> "设备尚未完成切换，可稍后刷新查看"
                    else -> "切换后设备将重新搜网"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.modeSwitchTimedOut && !switching) palette.error else palette.textSecondary
            )
        }
    }
}

// 2026-10-10：原 `connModeOf`（读 manualConnection → auto/manual）随「连接模式」UI
// 一起删除 —— 删掉最后一个调用者（NetworkModeScreen）后它成了死代码。
// **设备侧拨号配置本身没动**：`DeviceSettingsResponse.manualConnection` 字段、
// `NetworkModule.setConnectionMode()`、`POST /api/network/connection-mode` 与
// DeviceResponsesTest 里的相关单测全部保留，恢复 UI 时把本函数按原样加回即可。

/**
 * 读取设备当前档位。
 *
 * 优先 **`net_select`**（2026-09 真机：切换后 goform 回读里真正变化的是它，
 * 形如 `WL_AND_5G` / `Only_5G`）。`BearerPreference` 是写入侧字段，可能为空或未应用，
 * 仅在 net_select 缺失时回退。再经 [NetworkMode.fromBearer] 换成 contract 别名。
 */
internal fun netModeOf(settings: DeviceSettingsResponse?): String {
    val netSelect = settings?.netSelect?.takeIf { it.isNotBlank() }
    if (netSelect != null) return netSelect
    return settings?.bearerPreference?.takeIf { it.isNotBlank() } ?: NetworkMode.AUTO
}