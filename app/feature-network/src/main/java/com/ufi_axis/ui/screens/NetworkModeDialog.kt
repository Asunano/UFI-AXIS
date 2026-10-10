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
 * 2026-10-10 用户裁决（三轮迭代）：
 * ① 由独立页面 `detail/network-mode` 收进蜂窝高级设置页；
 * ② 删掉「连接模式 / 自动拨号·手动拨号」这一块 UI（**设备侧 `dial_mode` 配置不动**，
 * 见文件末尾说明）；
 * ③ 制式选择改用公共下拉 `UfiDropdown`；
 * ④ 顶部**不要**浅色着色块：改成左「网络信息」右「制式名 + 信号强度」的左右布局。
 *
 * ## 数据来源（④ 新增）
 * PCI / 频点 / SINR / RSRQ 全部读 [NetworkState.signalInfo]（`GET /api/device/signal` 的
 * `SignalInfo`），与基站信息页 / CellLockDialog 同一个数据源、同一批字段 ——
 * core 已按「NR 优先、LTE 兜底」合并过（band_label / pci / arfcn / signal_strength），
 * 所以**客户端不要按制式 if**，直接读统一字段。
 *
 * 信号强度文案与颜色复用 `signalLevelText` / `signalColor`（`UfiSignalBars.kt`），
 * 不在本页自建第二套分档 —— 那些函数注释里明确写了「与外部调用点同源，别分叉」。
 *
 * ## 刷新时机（用户裁决：开时拉一次，不轮询）
 * `LaunchedEffect(visible)`：弹窗打开才`refreshNetwork(silent = true)`，关闭不轮询 ——
 * 弹窗是短时查看场景，常驻轮询只会白耗电。
 * 切换制式后**不需要**本弹窗再拉：`NetworkModule.awaitNetworkModeApplied` 尾部已经调了
 * `refreshNetwork()`（全量，含 signalInfo），重新搜网后的信号值会自动跟上。
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

    // 开弹窗拉一次信号数据；关闭时不轮询（见 KDoc「刷新时机」）。
    // 用 refreshSignalOnly 而不是 refreshNetwork —— 后者会连带拉 WiFi 设置与客户端列表，
    // 为看 4 个信号读数付 4 个请求不划算。
    LaunchedEffect(visible) {
        if (visible) viewModel.network.refreshSignalOnly()
    }

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

    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "网络制式",
        icon = rememberVectorPainter(Icons.Filled.SignalCellularAlt),
        showCloseButton = false
    ) {
        UfiDialogBody {
            // ── 顶部左右布局：左「网络信息」四行，右「制式名 + 信号强度」──
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                // 左：PCI / 频点 / SINR / RSRQ
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small)
                ) {
                    UfiDialogInfoRow("PCI", state.signalInfo?.pci?.toString() ?: "--")
                    UfiDialogInfoRow("频点", state.signalInfo?.arfcn?.toString() ?: "--")
                    UfiDialogInfoRow("SINR", state.signalInfo?.sinr?.let { "$it dB" } ?: "--")
                    UfiDialogInfoRow("RSRQ", state.signalInfo?.rsrq?.let { "$it dB" } ?: "--")
                }
                // 右：制式名（大字）+ 紧邻其下的信号强度评级
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small)
                ) {
                    Text(
                        modeLabel,
                        style = UfiTextStyles.dialogTitle.copy(fontWeight = UfiWeight.Strong),
                        color = palette.textPrimary
                    )
                    // 信号评级按 **RSRP** 判（那是跨制式都有的主指标）。RSRQ 是 LTE 指标、
                    // 5G SA 下常为 null，所以**不用它判等级**，只当左侧那行读数展示 ——
                    // 否则会出现"评级来自 RSRP、旁边 RSRQ 却是--"的错配感。
                    val rsrp = state.signalInfo?.rsrp
                    Text(
                        text = if (rsrp == null) "信号未知" else "信号${signalLevelText(rsrp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = signalColor(rsrp)
                    )
                    if (switching || state.modeSwitchTimedOut) {
                        UfiBadge(
                            text = if (state.modeSwitchTimedOut) "尚未完成切换" else "切换中",
                            type = UfiBadgeType.WARNING
                        )
                    }
                }
            }

            // ── 切换制式：整行可点的选择器，不用「一行文字 + 一个按钮」──
            //
            // 初版是 `UfiDialogField("切换制式") { UfiDropdown(...) }`，那个组件是**同行**布局
            //（`Row`：左 label 右控件），视觉上退化成"左边一个文字标签、右边一个控件"的
            // 表单行，用户反馈「太丑」。改成**上下两块**：标题独立一行，下拉独占整行宽度 ——
            // 控件有足够宽度显示完整制式名，也不再和标签挤在同一行。
            Column(Modifier.fillMaxWidth()) {
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

            // 异常态走 warning 语义（底色块），正常态走 note（弱化）。
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