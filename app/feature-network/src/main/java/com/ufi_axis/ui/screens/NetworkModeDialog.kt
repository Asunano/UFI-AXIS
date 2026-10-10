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
 * 见文件末尾说明），③ 制式选择改用公共下拉 `UfiDropdown`。
 *
 * ## 布局为什么是三段式（2026-10-10 重做）
 * 初版把 `UfiStatItem`（当前制式）和 `UfiDropdown`（选择）上下直排，两个控件都是
 * 灰底小字，视觉权重一样、label 还都叫「网络制式」——用户既看不出"哪个是现在的状态、
 * 哪个是要改的操作"，也被两坨同色块挤在一起（真机反馈：太普通、都挤在一起）。
 *
 * 改成三段，各有职责、彼此拉开：
 * 1. **英雄区**（Surface 卡片）：制式名用 `dialogTitle` 大字居中，下面挂状态徽章
 *    （5G / 5G·4G / 仅 4G 色相不同，异常态另有说法）。"现在是什么"一眼可见。
 * 2. **分区标题** `UfiDialogSectionTitle("切换制式")`：把操作区和状态区明确分开，
 *    顺带消除上面 label 的重复感。
 * 3. **下拉** `UfiDropdown`：唯一需要动手的地方，紧跟标题。
 * 异常态（切换中 / 尚未完成切换）走 `UfiDialogWarning`，颜色统一到 warning 语义，
 * 不再用 bodySmall 灰字 —— 那种灰字和下拉的灰底混在一起，正是"挤在一起"的另一半。
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

    val modeLabel = deviceSettingsState.settings?.networkModeLabel
        ?: NetworkMode.label(displayMode)
    // 制式色相：5G 全开=主色、混合=次色、锁 4G/WCDMA=警示色（与旧页面同一口径）。
    val modeColor = when (displayMode) {
        NetworkMode.ONLY_WCDMA, NetworkMode.ONLY_LTE -> palette.error
        NetworkMode.LTE_AND_5G -> palette.accentSecondary
        NetworkMode.ONLY_5G -> palette.accent
        else -> palette.textPrimary
    }

    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "网络制式",
        icon = rememberVectorPainter(Icons.Filled.SignalCellularAlt),
        showCloseButton = false
    ) {
        UfiDialogBody {
            // ── 1. 英雄区：当前制式（大字）+ 状态徽章 ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = UfiCardDefaults.largeShape,
                color = modeColor.copy(alpha = 0.08f)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        modeLabel,
                        style = UfiTextStyles.dialogTitle.copy(fontWeight = UfiWeight.Strong),
                        color = modeColor
                    )
                    Spacer(Modifier.height(10.dp))
                    UfiBadge(
                        text = when {
                            switching -> "切换中"
                            state.modeSwitchTimedOut -> "尚未完成切换"
                            else -> "当前生效"
                        },
                        type = when {
                            switching -> UfiBadgeType.WARNING
                            state.modeSwitchTimedOut -> UfiBadgeType.WARNING
                            else -> UfiBadgeType.SUCCESS
                        }
                    )
                }
            }

            // ── 2+3. 操作区：标题与下拉绑成一个组 ──
            //
            // 不用 UfiDialogSectionTitle：它自带 4dp 内距 + 一条分隔线，而 UfiDialogBody 的
            // 块间距是 12dp —— 标题会离下拉 16dp、离英雄卡 12dp，两侧不等；而且一道横线
            // 把已经是两段式（浅色块 + 控件）的界面再切一刀，显得更碎而不是更清楚。
            // 这里自己排：标题紧贴下拉（4dp），与英雄卡之间仍是 Body 的 12dp。
            Column {
                Text(
                    "切换制式",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.textSecondary
                )
                Spacer(Modifier.height(Spacing.Small))
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

            // 异常态走 warning 语义（底色块），正常态的提示走 note（弱化）——
            // 二者不再都是灰字，避免和下拉的灰底糊在一起。
            if (switching || state.modeSwitchTimedOut) {
                UfiDialogWarning(
                    when {
                        state.modeSwitchTimedOut -> "设备尚未完成切换，可稍后刷新查看。"
                        else -> "正在切换到「${NetworkMode.label(displayMode)}」，设备重新搜网需要一点时间。"
                    }
                )
            } else {
                UfiDialogNote("切换后设备将重新搜网，短时间内可能断流。")
            }
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