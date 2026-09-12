// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.animation.ufiPressScale
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.UfiMotion

@Composable
fun UfiCustomDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    icon: Painter? = null,
    // FIX-7：titleLeading Composable slot 透传给 shell，让调用方传 title 左侧任意组件。
    titleLeading: (@Composable () -> Unit)? = null,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true,
    showCloseButton: Boolean = true,
    contentEnter: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    // 2026-08-11：按钮参数由 RowScope.() -> Unit 改为 @Composable () -> Unit。
    // 修复"确认/取消并排"bug：Standard 档的 UfiButton 默认 fillWidth = true，若作为 Row 直接子项
    // 会占满整行并把 confirmButton 挤出屏幕（导致弹窗只剩"取消"）。改为在 weight(1f) Box
    // 内包裹，两按钮各占半行。普通 lambda 调用方（{ UfiButton(...) }）对两种 receiver 均兼容。
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    UfiDialogShell(
        visible = visible,
        onDismiss = onDismiss,
        dismissOnClickOutside = dismissOnClickOutside,
        dismissOnBackPress = dismissOnBackPress,
        title = title,
        icon = icon,
        titleLeading = titleLeading,
        trailingContent = trailingContent,
        // FIX-7（2026-08-23）：原底部"关闭"按钮改为由 shell 在 title 行右上角自动渲染 ×。
        // 仅当调用方同时不传 confirmButton/dismissButton 且 showCloseButton=true 时启用，
        // 保留完全自定义关闭方式的能力。
        showCloseIcon = showCloseButton && confirmButton == null && dismissButton == null
    ) {
        if (contentEnter) {
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically { it / 8 } + fadeIn(tween(UfiMotion.Duration.Reveal)),
                exit = ExitTransition.None
            ) {
                // FIX-10（2026-08-24）：防御性约束。Dialog 出现在滚动页(LazyColumn/verticalScroll)
                // 时外层可能给 Infinity 高度约束，verticalScroll 在 Infinity 下直接抛 IllegalStateException。
                // 外层包 heightIn(max=maxH) Box 把约束 clamp 成有限值（maxH = screenH*0.7）。
                // FIX-25（2026-08-24）：把 fillMaxSize 改为 fillMaxWidth——之前 fillMaxSize 让 Column
                // 强制撑满 maxH，内容少时底部出现 ~几百 dp 空白；改为自然高度后 Box 也按内容高度收拢。
                // Box 的 heightIn(max) 仍作为「内容超出时 clamp 到 maxH 触发滚动」的天花板，
                // FIX-10 的防 Infinity 职责由 Box 继续承担。
                val maxH = with(androidx.compose.ui.platform.LocalConfiguration.current) {
                    (screenHeightDp * 0.7f).dp
                }
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxH)) {
                    Column(Modifier.fillMaxWidth()) {
                        content()
                    }
                }
            }
        } else {
            // FIX-25：移除 Column.fillMaxSize() → fillMaxWidth()。同 FIX-25 contentEnter 分支说明。
            content()
        }
        when {
            // 自定义确认/取消按钮：放在 content 之后。
            // 对齐旧 Material AlertDialog 习惯：dismissButton 居左、confirmButton 居右。
            confirmButton != null -> {
                // 2026-09-04：内容→按钮的间距原为 DialogPaddingH(18) + Row 的 vertical 12 = 30dp，
                // 而标题→内容只有 12dp，同一个弹窗里两处"块间距"差了 2.5 倍。
                // 现在统一成 Spacing.Large(12dp)：标题→内容、内容→按钮同值，按钮下方再留 12dp。
                Spacer(Modifier.height(Spacing.Large))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.Large),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 各包入 weight(1f) Box：按钮内部 fillMaxWidth 仅填满半行 Box，
                    // 避免取消按钮独占整行把确认按钮挤出屏幕（修复"只剩取消"bug）。
                    // propagateMinConstraints = true 把 Box 的 min 约束传给子按钮，
                    // 确保即便调用方传的是未铺满宽度的小按钮（如 UfiButton(size = Small)）
                    // 也能强制撑满等宽等高容器，解决"取消宽/保存窄"的视觉异常。
                    if (dismissButton != null) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(Spacing.ButtonHeight),
                            propagateMinConstraints = true,
                            contentAlignment = Alignment.Center
                        ) { dismissButton() }
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(Spacing.ButtonHeight),
                        propagateMinConstraints = true,
                        contentAlignment = Alignment.Center
                    ) { confirmButton() }
                }
            }
            // 仅 dismissButton（无 confirmButton）：全宽展示 + 底部留白。
            // FIX-7：底部关闭按钮已迁到右上角 ×（showCloseIcon），故此处不再补默认"关闭"。
            dismissButton != null -> {
                Spacer(Modifier.height(Spacing.DialogPaddingH))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.Large),
                    horizontalArrangement = Arrangement.Center
                ) {
                    dismissButton()
                }
            }
            // 两者都为 null：FIX-7 由 UfiDialogShell 在 title 右上角自动渲染 ×，
            // 这里无需额外补任何 UI（保留轻量接口、空 body）。
            else -> { /* no-op */ }
        }
    }
}

@Composable
fun UfiScrollableDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    icon: Painter? = null,
    // FIX-7：titleLeading Composable slot 透传给 shell，让调用方传 title 左侧任意组件。
    titleLeading: (@Composable () -> Unit)? = null,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true,
    showCloseButton: Boolean = true,
    contentEnter: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    // FIX-24（2026-08-24）：底部操作区 slot。caller 传入 UfiDialogActions 时不再塞进 content，
    // 而是放进 actions 槽位。dialog 内部会按 actions 实际占用预算 footerFixed，并把 actions
    // 渲染在滚动 Column 之外固定位置。修复之前"限额设置弹窗底部 80dp 空白" bug——
    // 之前 caller 把 UfiDialogActions 塞进 content，footerFixed 仍按 else 分支 32dp 算，
    // maxScrollH 多了 56dp 致滚动 Column fillMaxSize 撑高，下方留空。
    // 同时 showCloseIcon 自动识别：有 actions 时仍允许右上 × 关闭。
    actions: (@Composable () -> Unit)? = null,
    // FIX-7（2026-08-23）：subtitle slot 用于在 title 下方、内容上方插入固定不滚动的子区
    // （如 type/severity/count tag 行）。bottomContent slot 用于在内容下方、buttons 上方插入
    // 固定不滚动的子区（如分页条）。两个 slot 都不参与内容滚动 Column，规避被滑出视窗。
    subtitle: (@Composable () -> Unit)? = null,
    bottomContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val hasActions = actions != null
    UfiDialogShell(
        visible = visible,
        onDismiss = onDismiss,
        dismissOnClickOutside = dismissOnClickOutside,
        dismissOnBackPress = dismissOnBackPress,
        title = title,
        icon = icon,
        titleLeading = titleLeading,
        trailingContent = trailingContent,
        // FIX-7：原底部"关闭"按钮改为由 shell 在 title 行右上角自动渲染 ×。
        // FIX-24：加了 actions slot 后，行为对齐 UfiCustomDialog：
        //   confirmButton/dismissButton/actions 同时为 null → 自动 ×
        //   任意一个非 null → 不自动 ×，由 caller 自管关闭（UfiDialogActions 内部已带取消按钮）
        showCloseIcon = showCloseButton && confirmButton == null && dismissButton == null && !hasActions
    ) {
        val maxDialogH = with(LocalConfiguration.current) { (screenHeightDp * 0.82f).dp }
        // FIX-8（2026-08-23）：title 块约 56dp（已含 Spacing.DialogPaddingV 24dp）。
        val headerFixed = 56.dp
        // FIX-24：footer 高度预算四档：
        // - 有 confirm/dismissButton → 104dp（按钮 56dp + 上下 padding 48dp）
        // - 有 actions slot（UfiDialogActions 实际 ~88dp + 上下 padding 32dp）→ 120dp
        // - 有 bottomContent → 96dp
        // - 都没有 → 32dp
        val footerFixed = when {
            confirmButton != null || dismissButton != null -> 104.dp
            hasActions -> 120.dp
            bottomContent != null -> 96.dp
            else -> 32.dp
        }
        val subtitleFixed = if (subtitle != null) 56.dp else 0.dp  // subtitle 行 ≈ 40dp + Spacing.Medium 16dp
        val maxScrollH = (maxDialogH - headerFixed - subtitleFixed - footerFixed).coerceAtLeast(120.dp)

        // FIX-7：subtitle slot 渲染在滚动 Column 之外、滚动区之上，固定不滚动。
        if (subtitle != null) {
            val palette = LocalResolvedPalette.current
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.Medium)) {
                subtitle()
            }
        }

        if (contentEnter) {
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically { it / 8 } + fadeIn(tween(UfiMotion.Duration.Reveal)),
                exit = ExitTransition.None
            ) {
                // FIX-10（2026-08-24）：防御性约束。Dialog 出现在滚动页(LazyColumn/verticalScroll)
                // 时外层可能给 Infinity 高度约束，verticalScroll 在 Infinity 下直接抛 IllegalStateException。
                // 外层 Box 用 heightIn(max=maxScrollH) 把约束 clamp 成有限值。
                // FIX-25（2026-08-24）：滚动 Column 由 fillMaxSize() 改为 fillMaxWidth()。
                // 之前 fillMaxSize 会让 Column 强制撑满 Box 的 maxScrollH，内容少时（典型：限额弹窗只有
                // 4 个字段 + Switch）底部出现 ~几百 dp 空白。改为 fillMaxWidth() 后 Column 用内容自然高度，
                // Box 不会撑开；同时 Box 的 heightIn(max) 仍作为「内容超出时 clamp 到 maxScrollH 触发
                // verticalScroll」的天花板，FIX-10 的防 Infinity 职责由 Box 继续承担。
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxScrollH)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        content()
                    }
                }
            }
        } else {
            // FIX-25：同 contentEnter 分支说明。Column.fillMaxSize() → fillMaxWidth()。
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxScrollH)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    content()
                }
            }
        }

        // FIX-7：bottomContent slot 渲染在滚动 Column 之外、buttons 上方，固定不滚动。
        // 例如内嵌分页条（如 AlertTypeDetailDialog 的 10/页翻页）。
        if (bottomContent != null) {
            val palette = LocalResolvedPalette.current
            HorizontalDivider(color = palette.divider.copy(alpha = 0.08f))
            bottomContent()
        }

        when {
            // 自定义确认/取消按钮：固定到底部（移出滚动区），前面加分隔线。
            confirmButton != null -> {
                Spacer(Modifier.height(Spacing.Medium))
                val palette = LocalResolvedPalette.current
                HorizontalDivider(color = palette.divider.copy(alpha = 0.08f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.Large),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Large)
                ) {
                    // 各包入 weight(1f) Box：按钮内部 fillMaxWidth 仅填满半行 Box，
                    // 避免取消按钮独占整行把确认按钮挤出屏幕。propagateMinConstraints
                    // 把 Box 的 min 约束传给子按钮，确保等宽等高。
                    if (dismissButton != null) {
                        Box(
                            Modifier.weight(1f),
                            propagateMinConstraints = true,
                            contentAlignment = Alignment.Center
                        ) { dismissButton() }
                    }
                    Box(
                        Modifier.weight(1f),
                        propagateMinConstraints = true,
                        contentAlignment = Alignment.Center
                    ) { confirmButton() }
                }
            }
            // 仅 dismissButton（无 confirmButton）：全宽居中展示。
            // FIX-7：底部关闭按钮已迁到右上角 ×，故此处也不再补默认"关闭"。
            dismissButton != null -> {
                Spacer(Modifier.height(Spacing.Medium))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.Large),
                    horizontalArrangement = Arrangement.Center
                ) {
                    dismissButton()
                }
            }
            // 两者都为 null：FIX-7 由 UfiDialogShell 在 title 右上角自动渲染 ×，
            // 这里无需额外补任何 UI（保留轻量接口、空 body）。
            else -> { /* no-op */ }
        }

        // FIX-24：actions slot 渲染在 confirmButton/dismissButton 之后（互斥，建议 caller 二选一）。
        // 同样加分隔线 + 上下 Spacing.Large padding，与 confirmButton 行视觉一致。
        if (actions != null) {
            val palette = LocalResolvedPalette.current
            HorizontalDivider(color = palette.divider.copy(alpha = 0.08f))
            actions()
        }
    }
}

/**
 * 公共「双栏 / 多栏选项网格」组件（2026-08-13 需求2）。
 *
 * 把一组选项按 N 列等宽网格排布，选中项 accent 实底高亮；供排序弹窗、筛选弹窗等复用，
 * 让弹窗内的选项也能走「双栏」布局。
 *
 * 遵循公共组件冻结红线：本组件为 **新增独立公共组件**，不修改 [UfiCustomDialog] / [UfiScrollableDialog]
 * 既有签名，完全向后兼容。
 *
 * @param options 选项列表（[UfiOptionItem]）
 * @param selectedValue 当前选中值（用 == 比较，枚举 / String 均适用）
 * @param onSelect 选中回调（带回选项 value）
 * @param columns 列数，默认 2（双栏）
 *
 * 示例（排序四档 → 2×2 双栏）：
 * ```
 * UfiOptionGrid(
 *     options = EventSortMode.entries.map { mode ->
 *         UfiOptionItem(value = mode, label = mode.label,
 *             leading = { Icon(if (mode.isAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, null) },
 *             trailing = if (mode == current) { { Icon(Icons.Filled.Check, null) } } else null)
 *     },
 *     selectedValue = current,
 *     onSelect = { onSelect(it); onDismiss() },
 *     columns = 2
 * )
 * ```
 */
data class UfiOptionItem<T>(
    val value: T,
    val label: String,
    val leading: (@Composable () -> Unit)? = null,
    val trailing: (@Composable () -> Unit)? = null,
    val enabled: Boolean = true
)

@Composable
fun <T> UfiOptionGrid(
    options: List<UfiOptionItem<T>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
    columns: Int = 2,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Column(modifier = modifier.fillMaxWidth()) {
        options.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
            ) {
                rowItems.forEach { item ->
                    UfiOptionCell(
                        item = item,
                        selected = item.value == selectedValue,
                        onClick = { onSelect(item.value) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // 末行不足 columns 时补空白占位，保证各列等宽
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(Spacing.Small))
        }
    }
}

@Composable
private fun <T> UfiOptionCell(
    item: UfiOptionItem<T>,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    // 2026-08-18：选中态视觉从「实橙底 + 反白字」升级为「12% 浅橙底 + 橙字 + 橙描边」
    // - 与 DeviceControlScreen WiFi 休眠 / USB 模式 私货 FilterChip 2 列 grid 视觉同款
    // - 前者「实橙底 + 反白」偏重，「浅橙底 + 橙字」更轻盈，与截图基准统一
    // - 未选中保持 cardBg / cardBorder / textSecondary 一致，仅文字字重 Medium（选中加粗到 Bold）
    val bg by animateColorAsState(
        targetValue = if (selected) palette.accent.copy(alpha = 0.12f) else palette.cardBg,
        animationSpec = tween(UfiMotion.Duration.Base),
        label = "optionCellBg"
    )
    val border by animateColorAsState(
        targetValue = if (selected) palette.accent else palette.cardBorder,
        animationSpec = tween(UfiMotion.Duration.Base),
        label = "optionCellBorder"
    )
    val textColor = if (selected) palette.accent else palette.textSecondary
    Box(
        modifier = modifier
            .height(40.dp)
            // 2026-09-04（P2d）：0.97 → UfiMotion.PressScale.Chip（值不变）。
            // 选项格属于"大面积可点区"，与 CategoryChip 同档，规则见 PressScale 的 KDoc。
            //
            // 2026-09-04（P2f 短按看不见）：原为 animateFloatAsState + graphicsLayer。
            // 为什么原来短按看不见：那个写法只朝当前目标值走，抬手瞬间目标就翻回 1f；
            // 0.03 的落差配 tween(120)，一帧仅走完约 5%（scale ≈ 0.9985），而弹窗里选项格
            // 常处在可滚动内容中，Press 与 Release 甚至同帧到达，动画根本没时间播。
            // 现在怎么保证：ufiPressScale 事件驱动编排 + 最短保持 Duration.Micro（120ms），
            // 抬手时先把"缩到位"补播完再弹回；档位与 spec 未变，长按观感零回归。
            .ufiPressScale(
                interactionSource = interactionSource,
                pressedScale = UfiMotion.PressScale.Chip,
                spec = tween(UfiMotion.Duration.Micro)
            )
            .clip(UfiCardDefaults.shape)
            .background(bg)
            .border(1.dp, border, UfiCardDefaults.shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = item.enabled
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            if (item.leading != null) {
                item.leading!!()
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = item.label,
                style = UfiTextStyles.label.copy(
                    fontWeight = if (selected) UfiWeight.Strong else UfiWeight.Medium
                ),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.trailing != null) {
                Spacer(Modifier.width(6.dp))
                item.trailing!!()
            }
        }
    }
}
