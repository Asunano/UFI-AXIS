// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 日志面板：标题行（自动刷新开关 + 手动刷新）+ 等宽滚动日志区 + 可选"查看完整日志"。
 *
 * 只负责展示，轮询由调用方用 `LaunchedEffect(autoRefreshing)` 驱动（组件内不起协程，
 * 避免离屏后仍在轮询）。取色与圆角统一走 [LocalResolvedPalette] + [UfiCardDefaults]。
 */
@Composable
fun UfiLogPanel(
    log: String,
    modifier: Modifier = Modifier,
    title: String = "运行日志",
    autoRefreshing: Boolean = false,
    onToggleAutoRefresh: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onViewFull: (() -> Unit)? = null,
    emptyHint: String = "（暂无日志）",
    maxHeight: Dp = 160.dp,
    maxChars: Int = 1500
) {
    val palette = LocalResolvedPalette.current
    val scroll = rememberScrollState()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = UfiCardDefaults.shape,
        color = palette.cardBg,
        border = BorderStroke(1.dp, palette.cardBorder)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.InnerPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = UfiTextStyles.sectionTitle,
                    color = palette.textPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    onToggleAutoRefresh?.let {
                        UfiButton(
                            variant = UfiButtonVariant.Subtle, size = UfiButtonSize.Small,
                            text = if (autoRefreshing) "暂停" else "自动",
                            onClick = it
                        )
                    }
                    onRefresh?.let {
                        UfiButton(variant = UfiButtonVariant.Subtle, size = UfiButtonSize.Small, text = "刷新", onClick = it)
                    }
                }
            }
            Surface(
                color = palette.pageBg,
                shape = UfiCardDefaults.smallShape,
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)
            ) {
                Text(
                    text = if (log.isBlank()) emptyHint else log.takeLast(maxChars),
                    modifier = Modifier.padding(Spacing.Medium).verticalScroll(scroll),
                    style = UfiTextStyles.monoCaption,
                    color = palette.textSecondary
                )
            }
            if (log.isNotBlank() && onViewFull != null) {
                UfiButton(
                    variant = UfiButtonVariant.Subtle, size = UfiButtonSize.Small,
                    text = "查看完整日志",
                    onClick = onViewFull,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

/**
 * 完整日志弹窗：等宽滚动全文 + 复制 + 关闭。与 [UfiLogPanel] 的"查看完整日志"配对使用。
 */
@Composable
fun UfiLogDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    log: String,
    title: String = "完整日志",
    onCopy: (() -> Unit)? = null,
    maxHeight: Dp = 400.dp
) {
    val palette = LocalResolvedPalette.current
    val scroll = rememberScrollState()
    UfiScrollableDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        dismissButton = onCopy?.let { copy -> { UfiButton(variant = UfiButtonVariant.Secondary, text = "复制", onClick = copy) } },
        confirmButton = { UfiButton(text = "关闭", onClick = onDismiss) }
    ) {
        Surface(
            color = palette.pageBg,
            shape = UfiCardDefaults.smallShape,
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)
        ) {
            Text(
                text = if (log.isBlank()) "（空）" else log,
                modifier = Modifier.padding(Spacing.Large).verticalScroll(scroll),
                style = UfiTextStyles.monoCaption,
                color = palette.textPrimary
            )
        }
    }
}
