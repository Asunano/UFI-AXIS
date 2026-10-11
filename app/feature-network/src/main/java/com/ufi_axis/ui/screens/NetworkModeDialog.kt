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
import androidx.compose.ui.text.style.TextAlign
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

    // 2026-10-10：下拉改成 [UfiSingleChipSelector] + 确认/取消按钮后，选择不再即时下发 ——
    // 弹窗内先选、再按「确定」才下发，「取消」直接丢弃。
    // 与旧下拉的关键差别：下拉是"点开→选中即刻生效"，而制式切换会让设备重新搜网、断流数秒，
    // 让用户在浮层里一按就触发副作用、又没有反悔余地。
    // 每次打开弹窗重置为设备当前档位（见下面的 LaunchedEffect(visible)）。
    var pendingSelection by remember(visible) { mutableStateOf(displayMode) }
    val selectionChanged = pendingSelection != displayMode

    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "网络制式",
        icon = rememberVectorPainter(Icons.Filled.SignalCellularAlt),
        showCloseButton = false,
        // 按钮走壳子自带的 confirmButton / dismissButton 槽位，**不塞进 content**。
        // 两个坑（都写在 UfiCustomDialog 的 KDoc 里）：
        // 1. 塞进 content 的话按钮不会吸底，弹窗内容一多就跟着滚走；
        // 2. 壳子已在 weight(1f) Box 内包裹两键（2026-08-11 修的「Standard 档UfiButton
        //    默认 fillWidth=true 会占满整行、把确认键挤出屏幕，只剩取消」）。
        // 所以这里只给按钮本体，weight / 分隔线 / 间距都由壳子负责。
        confirmButton = {
            UfiButton(
                text = "确定",
                enabled = selectionChanged && !switching && !state.modeSwitchTimedOut,
                // 无改动时置灰：没有要下发的东西，别让用户以为已经生效。
                onClick = {
                    // 只在真有改动时下发，避免"按确定"却跑一趟无意义的重新搜网。
                    if (selectionChanged) viewModel.network.setNetworkMode(pendingSelection)
                    onDismiss()
                }
            )
        },
        dismissButton = {
            UfiButton(
                text = "取消",
                variant = UfiButtonVariant.Secondary,
                // 取消**不跟随**确认键的 enabled：enabled 表达的是"确认动作当前能否执行"，
                // 而"放弃并关闭"在任何状态下都应点得动（见 UfiDialogActions 的同名注释）。
                onClick = onDismiss
            )
        }
    ) {
        UfiDialogBody {
            // ── 顶部：左「制式名 + 信号强度」，右「网络信息 2×2」──
            //
            // 2026-10-10 用户裁决：左右互换（制式在左），四行读数改 2×2 网格，
            // 制式名字号放大到与 2×2 那一块的上下宽度对齐。
            //
            // 为什么左右两边都 weight(1f)：制式名「5G/4G/3G」这类最长文案要能撑住左半，
            // 而右边 2×2 每格label+值也要够宽 —— 任一边固定宽度都会让另一边挤。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                // 左：制式名（大字）+ 紧邻其下的信号强度评级
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small)
                ) {
                    // 用 displaySmall 而不是 dialogTitle：制式名是这个弹窗的主角，
                    // 而 dialogTitle(headlineSmall) 与右侧读数字号同档、撑不起"主角"的观感。
                    // fillMaxWidth + 居中让长文案（如「5G/4G/3G」）也能占满左半并视觉居中，
                    // 与右边 2×2 的上下宽度对齐。
                    Text(
                        modeLabel,
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = UfiWeight.Strong),
                        color = palette.textPrimary,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    // 信号评级按 **RSRP** 判（那是跨制式都有的主指标）。RSRQ 是 LTE 指标、
                    // 5G SA 下常为 null，所以**不用它判等级**，只当右侧那格读数展示 ——
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
                // 右：PCI / 频点 / SINR / RSRQ —— 2×2 网格
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small)
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        UfiDialogInfoRow("PCI", state.signalInfo?.pci?.toString() ?: "--", modifier = Modifier.weight(1f))
                        UfiDialogInfoRow("频点", state.signalInfo?.arfcn?.toString() ?: "--", modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        UfiDialogInfoRow("SINR", state.signalInfo?.sinr?.let { "$it dB" } ?: "--", modifier = Modifier.weight(1f))
                        UfiDialogInfoRow("RSRQ", state.signalInfo?.rsrq?.let { "$it dB" } ?: "--", modifier = Modifier.weight(1f))
                    }
                }
            }

            // ── 切换制式：chip 单选 + 确认/取消 ──
            //
            // 2026-10-10 用户裁决：下拉改 chip 选择器，并加确认/取消按钮。
            // 两处一起改是有因果的：下拉是"点开浮层选中即刻生效"，没有反悔余地；
            // 而制式切换会让设备重新搜网、断流数秒。改成 chip 后 6 个档位平铺可见、
            // 先选后确认，「取消」能真正丢弃选择。
            //
            // 用 [UfiSingleChipSelector]（chip 本体）而不是 [UfiDialogChipSelector]：
            // 后者自带 `UfiDialogField` 的同行标签布局，而这里标签要独占一行。
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "切换制式",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.textSecondary
                )
                Spacer(Modifier.height(Spacing.Small))
                UfiSingleChipSelector(
                    options = NetworkMode.UI_OPTIONS.map { it to NetworkMode.label(it) },
                    selectedValue = pendingSelection,
                    onSelect = { pendingSelection = it },
                    // 6 项超过一行：靠 wrap 折行，而不是让 chip 横向挤成一排看不清的字。
                    wrap = true
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
            } else if (selectionChanged) {
                // 选了新档位但还没确认：把"将要发生什么"讲在确认键之前。
                UfiDialogNote("确定后将切换到「${NetworkMode.label(pendingSelection)}」，设备会重新搜网并短暂断流。")
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