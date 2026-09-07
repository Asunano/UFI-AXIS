// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 工具栏图标动作按钮。
 *
 * 2026-08-31：由 `FileToolbar` 与 `TextEditorScreen` 两份私有 `ToolbarAction` 合并而来。
 * 两处形态本来就不同，这里用 [vertical] 区分，实现只留一份：
 * - `vertical = true`（默认，文件管理器工具栏）：图标在上、文字在下的方块格，
 *   最小宽 48dp / 高 36dp，满足触摸目标；默认 tint 为 `palette.textSecondary`。
 * - `vertical = false`（文本编辑器工具行）：`TextButton` 里图标 + 文字横排，
 *   14dp 图标 + 12sp 文字，默认 tint 为 `palette.accent`，禁用时降为 40% 灰。
 *
 * [tint] 传 null 时按上面的默认取色；显式传值可覆盖（如"粘贴"用 accent 强调）。
 */
@Composable
fun UfiToolbarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    enabled: Boolean = true,
    vertical: Boolean = true
) {
    val palette = LocalResolvedPalette.current
    val resolvedTint = tint ?: if (vertical) {
        palette.textSecondary
    } else {
        if (enabled) palette.accent else palette.textSecondary.copy(alpha = 0.4f)
    }

    if (vertical) {
        Column(
            modifier = modifier
                .widthIn(min = 48.dp)
                .height(36.dp)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = resolvedTint
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = resolvedTint,
                maxLines = 1
            )
        }
    } else {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(icon, contentDescription = label, tint = resolvedTint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = resolvedTint, style = UfiTextStyles.note)
        }
    }
}
