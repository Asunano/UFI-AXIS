package com.ufi_axis.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing

// ══════════════════════════════════════════════════════════════
// 弹窗内容统一外壳
// ══════════════════════════════════════════════════════════════

/**
 * 弹窗 content 的统一外壳。
 *
 * 解决历史痛点：UfiDialogShell 的 content 容器只给标题行加了水平 padding、
 * content 自身无水平 padding，且标题行无底部间距 → 每个弹窗都要手写
 * `Column(padding horizontal=DialogPaddingH)` +「标题↔首字段」Spacer，忘了就贴边。
 *
 * 本组件统一兜底：
 *  - 顶部 `Spacing.Large`：标题与第一个内容块的呼吸间距
 *  - 字段组 `verticalArrangement = spacedBy(Spacing.Large)`
 *
 * 2026-09-03 更正文档：本组件**不再**自带水平内边距（历史上这里写过 `DialogPaddingH`）。
 * 横向内距的唯一来源是 UfiDialogShell 的内容列，标题行 / body / 底部按钮区共用同一条基线；
 * 内件（输入框、chip、开关行等）再叠一层就会出现 18+18=36dp 的"输入框比标题窄一圈"。
 *
 * 用法：所有走 UfiCustomDialog / UfiScrollableDialog 的弹窗，content 内首行包此组件。
 */
@Composable
fun UfiDialogBody(content: @Composable ColumnScope.() -> Unit) {
    // 水平 padding 由 UfiDialogShell 内容列统一提供（单一来源 DialogPaddingH），这里只留垂直节奏
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.Large),
        verticalArrangement = Arrangement.spacedBy(Spacing.Large),
        horizontalAlignment = Alignment.Start
    ) {
        content()
    }
}

// ══════════════════════════════════════════════════════════════
// 通用「标签 + 控件」块（底层原语，供高级快捷组件复用）
// ══════════════════════════════════════════════════════════════

/**
 * 统一的「标签 + 控件」纵向块。
 *
 * 标签使用 `labelSmall` + `textSecondary`（低调、非粗体非 accent）；
 * 标签与控件间距 4dp（`Spacing.Small`）。
 *
 * 当内置快捷组件（如 UfiDialogTextField）不够用时，用此原语包裹任意控件。
 */
@Composable
fun UfiDialogField(label: String, content: @Composable () -> Unit) {
    val palette = LocalResolvedPalette.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary
        )
        Spacer(Modifier.height(Spacing.Small))
        content()
    }
}

// ══════════════════════════════════════════════════════════════
// 常用字段快捷组件 —— 只需调用，不需知道内部控件细节
// ══════════════════════════════════════════════════════════════

/**
 * 文本输入框字段（最常用）。
 *
 * 自动套用 labelSmall 标签 + UfiTextField 控件，label 传空（标签已由本组件提供）。
 */
@Composable
fun UfiDialogTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    isError: Boolean = false,
    errorMessage: String? = null
) {
    UfiDialogField(label) {
        UfiTextField(
            value = value,
            onValueChange = onValueChange,
            label = "",
            placeholder = placeholder.ifEmpty { null },
            enabled = enabled,
            singleLine = singleLine,
            isError = isError,
            errorMessage = errorMessage
        )
    }
}

/**
 * 密码输入框字段（带眼睛切换图标）。
 */
@Composable
fun UfiDialogPasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    enabled: Boolean = true
) {
    UfiDialogField(label) {
        UfiPasswordField(
            value = value,
            onValueChange = onValueChange,
            label = "",
            placeholder = placeholder.ifEmpty { null },
            enabled = enabled
        )
    }
}

/**
 * Chip 选择器字段（二选一 / 多选一）。
 *
 * options: List<Pair<valueKey, displayLabel>>，如 listOf("chip1" to "2.4 GHz", "chip2" to "5 GHz")
 *
 * @param wrap 2026-09-08 新增（默认 false，行为不变）：选项多到一行放不下时折行。
 *             弹窗宽度比设置行更窄，5 个以上的 chip 基本都要折 —— 如存储管理的「清理范围」。
 */
@Composable
fun UfiDialogChipSelector(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    wrap: Boolean = false
) {
    UfiDialogField(label) {
        UfiSingleChipSelector(
            options = options,
            selectedValue = selectedValue,
            onSelect = onSelect,
            wrap = wrap
        )
    }
}

/**
 * 「数值 + 单位」字段：左边一个数字输入框、右边一组单位分段选择。
 *
 * 2026-09-07 新增，为流量阈值弹窗而生：流量额度因人而异（1 GB / 30 GB / 500 GB 都常见），
 * 用固定值域的滑块必然要么精度不够要么上限不够，所以那类阈值改成「直接输入 + 选单位」。
 *
 * 这是一层**薄封装**，自身不画任何控件：
 * - 标签 = [UfiDialogField]（labelSmall + textSecondary，与其它字段同一条基线）；
 * - 数值 = [UfiDigitField]（只收数字、`fillMaxWidth = false` 走 `weight(1f)` 占满剩余宽度）；
 * - 单位 = [UfiSingleChipSelector]（`wrapContent = true`，按内容宽排布，不抢输入框的宽度）。
 *
 * 校验交给调用方：本组件只把 [isError] / [errorMessage] 透传给 [UfiDigitField]，
 * 因为「警告必须小于严重」这类跨字段规则只有调用方知道。
 *
 * @param unitOptions 单位选项 `List<Pair<value, label>>`，如 `listOf("MB" to "MB", "GB" to "GB")`
 */
@Composable
fun UfiDialogValueUnitField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    unitOptions: List<Pair<String, String>>,
    selectedUnit: String,
    onUnitChange: (String) -> Unit,
    placeholder: String = "",
    enabled: Boolean = true,
    isError: Boolean = false,
    errorMessage: String? = null
) {
    UfiDialogField(label) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
            // 输入框比 chip 轨道高，顶对齐会让单位吊在半空
            verticalAlignment = Alignment.CenterVertically
        ) {
            UfiDigitField(
                value = value,
                onValueChange = onValueChange,
                label = "",
                modifier = Modifier.weight(1f),
                placeholder = placeholder.ifEmpty { null },
                enabled = enabled,
                isError = isError,
                errorMessage = errorMessage,
                fillMaxWidth = false
            )
            UfiSingleChipSelector(
                options = unitOptions,
                selectedValue = selectedUnit,
                onSelect = onUnitChange,
                wrapContent = true,
                enabled = enabled
            )
        }
    }
}

/**
 * Switch 开关行字段。
 *
 * 标签在左、Switch 在右，对齐到行两端。
 */
@Composable
fun UfiDialogSwitchField(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val palette = LocalResolvedPalette.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textPrimary
        )
        // 2026-08-31：M3 Switch（52×32dp）→ 自绘 UfiSwitch（42×24dp），
        // 全站开关统一到一种几何。
        UfiSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

/**
 * 只读信息展示行（标签 : 值）。
 *
 * 用于不需要编辑的字段展示（如设备型号、IP 地址等）。
 */
@Composable
fun UfiDialogInfoRow(label: String, value: String, multiline: Boolean = false) {
    val palette = LocalResolvedPalette.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            modifier = if (multiline) Modifier.padding(top = 2.dp, end = 8.dp).weight(1f, fill = false) else Modifier
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textPrimary,
            textAlign = if (multiline) TextAlign.End else TextAlign.Unspecified,
            modifier = if (multiline) Modifier.weight(1f, fill = false) else Modifier
        )
    }
}

/**
 * 弹窗内的说明文字（次要色小字）。
 *
 * 2026-09-11 新增。此前每个弹窗都在 content 里手写
 * `Text(..., style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)`，
 * 同一句"说明"在不同弹窗里字号/颜色靠各自抄一遍维持一致 —— 抄漏一处就看得出来。
 * 与 [UfiDialogWarning] 是同一族的两档：本组件是**中性说明**（次要色小字、无底），
 * [UfiDialogWarning] 是**风险提示**（警告色 + 淡底）。别用后者说普通的话，
 * 满屏橙块会让真正的风险提示失去重量。
 */
@Composable
fun UfiDialogNote(text: String) {
    val palette = LocalResolvedPalette.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = palette.textSecondary
    )
}

/**
 * 警告提示块（橙色/警告色背景 + 文字）。
 *
 * 用于操作前风险提示（如基站锁定、危险操作确认等）。
 */
@Composable
fun UfiDialogWarning(message: String) {
    val palette = LocalResolvedPalette.current
    Surface(
        color = palette.warning.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = palette.warning,
            modifier = Modifier.padding(12.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 弹窗内容区块辅助组件（分组标题 / 空态 / 单选列表）
// ══════════════════════════════════════════════════════════════

/**
 * 分组标题（小标题 + 分隔线），用于弹窗内划分区块。
 */
@Composable
fun UfiDialogSectionTitle(title: String) {
    val palette = LocalResolvedPalette.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = palette.textSecondary
        )
        Spacer(Modifier.height(Spacing.Small))
        HorizontalDivider(color = palette.divider)
    }
}

// ══════════════════════════════════════════════════════════════
// 弹窗底部操作区（标准双按钮 / 单按钮 / 警告+按钮）
// ══════════════════════════════════════════════════════════════

/**
 * 标准弹窗底部操作区：「取消(描边左) + 确认(实色主色右)」等宽双按钮。
 *
 * 放在 [UfiDialogBody] 之后（作为 content 的最后一个子元素），
 * 或配合 `UfiCustomDialog(showCloseButton=false)` 使用并**不传** confirmButton/dismissButton 槽位。
 *
 * 本组件自带底边距 22dp（对齐参考项目 UFITOOLS-Widget 的 paddingBottom）和顶部间距。
 *
 * 两种"危险操作"摆法，别混：
 * - [confirmDestructive]：**右侧确认键**本身就是危险动作（「确定删除？」这类二次确认弹窗）；
 * - [dismissDestructive]：**左侧槽位**是危险动作、右侧确认键只是关闭（如详情弹窗「删除 / 确认」）。
 *   2026-08-29 加入 —— 此前这种布局只能在调用处手搓 `Row { Danger 按钮; Primary 按钮 }`，
 *   间距和底边距全靠抄，容易和标准弹窗对不齐。
 */
@Composable
fun UfiDialogActions(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String = "确定",
    dismissText: String? = "取消",
    confirmDestructive: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
    dismissDestructive: Boolean = false
) {
    val palette = LocalResolvedPalette.current
    Spacer(Modifier.height(Spacing.DialogPaddingH))
    // 水平 padding 由 UfiDialogShell 内容列统一提供，这里对齐即可
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dismissText != null) {
            // 取消/删除侧**不跟随 [enabled]**：enabled 表达的是"确认动作当前是否可执行"
            // （表单没填完、没有改动等），而"放弃并关闭"在任何状态下都应该点得动。
            // 之前跟着 enabled 一起置灰，导致 enabled=false 时整行两个按钮全灰 ——
            // 弹窗刚打开就像坏掉了，也和其它弹窗的观感不一致。
            // 仅在 [loading]（确认请求在飞）时禁用，避免中途取消留下半完成状态。
            val dismissEnabled = !loading
            if (dismissDestructive) {
                // 左侧槽位放破坏性操作（如「删除」）：仍用 variant = Danger 出红色，
                // 但不占用 confirm 槽 —— 用于「左删除 / 右确认」这种确认键不是危险操作的布局。
                UfiButton(
                    variant = UfiButtonVariant.Danger,
                    text = dismissText,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    enabled = dismissEnabled
                )
            } else {
                UfiButton(
                    variant = UfiButtonVariant.Secondary,
                    text = dismissText,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    enabled = dismissEnabled
                )
            }
        }
        if (confirmDestructive) {
            // 危险操作（如「解锁全部」「删除」）走项目既有的 variant = Danger（红），
            // 与 UfiConfirmDialog(destructive=true) 的危险语义一致。
            UfiButton(
                variant = UfiButtonVariant.Danger,
                text = confirmText,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = enabled
            )
        } else {
            UfiButton(
                text = confirmText,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                loading = loading
            )
        }
    }
    Spacer(Modifier.height(Spacing.DialogActionsBottom))
}

// ══════════════════════════════════════════════════════════════
// 悬浮选项菜单（UfiPopupMenu）
// ══════════════════════════════════════════════════════════════
// 采用 Compose Popup 实现的「点击锚点后浮出」悬浮菜单，
// 定位参考 M3 原生 DropdownMenu（锚点下方优先，空间不足翻到上方；
// 水平越界则右对齐锚点右缘向左展开），但 UI 完全自定义、100% 继承主题色——
// 规避原生 DropdownMenu 在部分机型不继承自定义 colorScheme、containerColor
// 被忽略导致白底的问题。
//
// 用法（触发控件作为锚点，同级调用，并采集其窗口坐标传入 anchorBounds）：
//   Box(Modifier.onGloballyPositioned { anchor = it.boundsInWindow().roundToIntRect() }) {
//       IconButton(onClick = { show = true }) { Icon(...) }
//       UfiPopupMenu(
//           visible = show,
//           onDismiss = { show = false },
//           anchorBounds = anchor,        // 触发控件的窗口坐标 (IntRect)
//           title = "排序方式",          // 可选标题
//           options = listOf(...)
//       )
//   }

