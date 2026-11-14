// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.UfiMotion

/**
 * Themed checkbox with label and optional description.
 */
@Composable
fun UfiCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    description: String? = null,
    enabled: Boolean = true,
    /**
     * 方框画在文字**右侧**（2026-11-08 新增，默认 false = 老布局）。
     *
     * 用于「须知/同意」这类长文案：方框跟在一段话后面会显得像正文的一部分，
     * 放到右端和全站的"右侧控件"（开关、箭头）对齐更好扫读。
     */
    checkboxAtEnd: Boolean = false
) {
    val palette = LocalResolvedPalette.current
    val hasText = label != null || description != null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                // 有文字时整行可点（点文字也能勾选）；方框自己仍会消费自己的点击。
                if (hasText && enabled) {
                    Modifier.clickable { onCheckedChange(!checked) }
                } else {
                    Modifier
                }
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val box: @Composable () -> Unit = {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = palette.accent,
                    uncheckedColor = palette.textSecondary,
                    checkmarkColor = palette.onAccent,
                    disabledCheckedColor = palette.accent.copy(alpha = 0.4f),
                    disabledUncheckedColor = palette.textSecondary.copy(alpha = 0.4f)
                )
            )
        }

        if (!checkboxAtEnd) box()
        if (hasText) {
            if (!checkboxAtEnd) Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                if (label != null) {
                    Text(
                        text = label,
                        style = UfiTextStyles.bodyLead.copy(fontWeight = UfiWeight.Medium),
                        color = palette.textPrimary
                    )
                }
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                }
            }
        }
        if (checkboxAtEnd) {
            Spacer(Modifier.width(8.dp))
            box()
        }
    }
}

/**
 * Themed checkbox with animated scale on toggle.
 */
@Composable
fun UfiAnimatedCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true
) {
    val palette = LocalResolvedPalette.current
    val scale by animateFloatAsState(
        targetValue = if (checked) 1f else 0.85f,
        animationSpec = UfiMotion.controlPop(),
        label = "checkboxScale"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = palette.accent,
                    uncheckedColor = palette.textSecondary,
                    checkmarkColor = palette.onAccent
                )
            )
        }
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = UfiTextStyles.bodyLead.copy(fontWeight = UfiWeight.Medium),
                color = palette.textPrimary
            )
        }
    }
}
