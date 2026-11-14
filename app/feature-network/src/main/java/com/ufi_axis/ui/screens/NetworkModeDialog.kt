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
import androidx.compose.ui.text.style.TextOverflow
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
 * ③ 制式选择改用公共 chip（⑤ 之后见下）；
 * ④ 顶部**不要**浅色着色块：改成左「制式名 + 信号强度」右「网络信息 2×2」的左右布局；
 * ⑤ 左右互换 + 读数改 2×2 + 制式名放大 + 下拉改 chip + 加确认/取消（2026-10-10）；
 * ⑥ chip 换成真正的 [CategoryChip]、制式名锁单行（2026-10-11，修「界面错乱、
 *    没有固定布局、chip 调用错误」）；
 * ⑦ 制式名放大到 displayMedium、信号评级改 [UfiBadge] 居中、2×2 改 [UfiInfoCell]
 *    上下排（修「频点换行、没有间隔」）；
 * ⑧ 整体间距加宽（用户「太挤了」）；
 * ⑨ 2026-11-08：**取消左右两栏**——制式名整行居中、不缩字号（45sp），
 *    原右栏的 2×2 网格挪到信号质量下方。触发的直接原因是「5G/4G/3G」在半栏里
 *    被截断；用户明确否决了「自动缩小字号」的方案。
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
                // 2026-10-24：`modeSwitchTimedOut` **不能**参与禁用判据。
                // 超时只说明"这次回读预算内没看到目标档位"，设备可能稍后自己切过去；
                // 但唯一能把该标志复位的地方就是 `setNetworkMode()`，而它的唯一调用点
                // 正是这颗按钮 —— 置灰后用户再也触发不了重试，只能重启 App。
                enabled = selectionChanged && !switching,
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
            // 2026-10-11 用户「间隔加宽、当前太挤」。
            //
            // [UfiDialogBody] 的块间距是全站统一的 Spacing.Large(12dp)，而它的 KDoc
            // 明确写了「块与块之间一律 12dp，上下边距由 shell 负责」——那是公共节奏，
            // 不该在单页私自改。所以这里在 Body 内**自己套一层 Column** 控制块间距，
            // 而不是去改公共组件或给 Body 传参（Body 也没有间距参数）。
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.XLarge)) {
            // ── 顶部：制式名 + 信号质量 + 网络信息 ──
            //
            // 2026-11-08 用户裁决：制式名不再为了塞进半屏而缩字号，改成**整行居中**；
            // 右侧的 PCI / 频点 / SINR / RSRQ 2×2 网格挪到「信号质量」下方。
            // 整行宽度（360dp 机型约 270dp）下 displayMedium 45sp 的「5G/4G/3G」
            // 也放得下，且不再有左右两栏高度互相牵连的问题。
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.Large)
            ) {
                // 制式名是这个弹窗的主角，displayMedium（45sp），整行居中。
                // 用户明确要求「不缩小了」，所以不接 TextAutoSize；maxLines = 1 +
                // Ellipsis 只作为极端窄屏的兜底。
                Text(
                    text = modeLabel,
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = UfiWeight.Strong),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                // 信号评级按 **RSRP** 判（那是跨制式都有的主指标）。RSRQ 是 LTE 指标、
                // 5G SA 下常为 null，所以**不用它判等级**，只当下面网格里的读数展示 ——
                // 否则会出现"评级来自 RSRP、旁边 RSRQ 却是--"的错配感。
                //
                // 2026-10-11：文字 → 公共 [UfiBadge]，与制式名同列居中。
                // 之前是 bodySmall 裸文字，和读数同一档、看不出是"状态"；
                // badge 有底色和描边，一眼就能和静态读数区分开。
                val rsrp = state.signalInfo?.rsrp
                UfiBadge(
                    text = if (rsrp == null) "信号未知" else "信号${signalLevelText(rsrp)}",
                    // 徽章色走 [signalBars] 的等级（1..5），**不自己判 dBm 阈值**。
                    // signalLevelText / signalBars 是唯一分档真源（UfiSignalBars.kt:20-42），
                    // 那里的 KDoc 明确写了「与外部调用点同源，别分叉」——
                    // 本轮之前我就在这里手写过一套 -90/-105 的阈值，把「差」错标成 INFO。
                    type = when (rsrp?.let { signalBars(it) }) {
                        4, 5 -> UfiBadgeType.SUCCESS
                        3 -> UfiBadgeType.INFO
                        else -> UfiBadgeType.WARNING
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                if (switching || state.modeSwitchTimedOut) {
                    UfiBadge(
                        text = if (state.modeSwitchTimedOut) "尚未完成切换" else "切换中",
                        type = UfiBadgeType.WARNING,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                // ── 网络信息 2×2（信号质量下方，整块居中）──
                //
                // 2026-11-08 用户裁决：从右栏挪到信号质量下方；随后又要求 2×2 也居中。
                // 这里不直接改公共 [UfiInfoCell]（其它页面仍是左对齐），而是：
                // 1) 网格整体收窄到 86% 宽，由父 Column 的 CenterHorizontally 居中；
                // 2) 每个格子用 [CenteredInfoCell] 把 label/value 也居中。
                Column(
                    Modifier.fillMaxWidth(0.86f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Large)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
                    ) {
                        CenteredInfoCell("PCI", state.signalInfo?.pci?.toString() ?: "--", Modifier.weight(1f))
                        CenteredInfoCell("频点", state.signalInfo?.arfcn?.toString() ?: "--", Modifier.weight(1f))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
                    ) {
                        CenteredInfoCell("SINR", state.signalInfo?.sinr?.let { "$it dB" } ?: "--", Modifier.weight(1f))
                        CenteredInfoCell("RSRQ", state.signalInfo?.rsrq?.let { "$it dB" } ?: "--", Modifier.weight(1f))
                    }
                }
            }

            // ── 切换制式：chip 单选 + 确认/取消 ──
            //
            // 2026-10-10 用户裁决：下拉改 chip 选择器，并加确认/取消按钮。
            // 两处一起改有因果：下拉是"点开浮层选中即刻生效"，没有反悔余地；
            // 而制式切换会让设备重新搜网、断流数秒。改成先选、再按「确定」才下发，
            // 「取消」能真正丢弃选择。
            //
            // 2026-10-11 用 [CategoryChip] 而**不是** [UfiSingleChipSelector]：
            // 后者名字里有 "Chip"，实际是**分段控件**——轨道底色 + [UfiSegment] + 滑块动画，
            // 滑块位置/宽度由 `onSizeChanged` 量出的段宽算出（见 UfiChipSelector.kt:135-147）。
            // 6 段在 FlowRow 里各自宽度不同 → 几何失效，滑块飞掉、整体看起来就是"错乱"。
            // [CategoryChip] 是项目里真正的 chip（描边 + 淡底 + 选中态填色），不依赖几何。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                // chip 之间 Small(4) → Medium(8)：6 个 chip 挤在一起分不清边界
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                NetworkMode.UI_OPTIONS.forEach { mode ->
                    CategoryChip(
                        label = NetworkMode.label(mode),
                        selected = mode == pendingSelection,
                        onClick = { pendingSelection = mode }
                    )
                }
            }
            // 异常态走 warning 语义（底色块），正常态走 note（弱化）。
            if (switching || state.modeSwitchTimedOut) {
                UfiDialogWarning(
                    when {
                        state.modeSwitchTimedOut -> "设备尚未完成切换，可再次点「确定」重试。"
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

/** 网络制式弹窗里的居中读数格：label + value 都居中；不修改公共 UfiInfoCell 的对齐。 */
@Composable
private fun CenteredInfoCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = UfiTextStyles.note,
            color = palette.textSecondary,
            textAlign = TextAlign.Center
        )
        Text(
            text = value,
            style = UfiTextStyles.bodyEmphasis,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
