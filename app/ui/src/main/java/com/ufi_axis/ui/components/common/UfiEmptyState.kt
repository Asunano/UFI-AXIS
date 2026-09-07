// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing

@Composable
fun UfiEmptyState(
    icon: ImageVector,
    message: String,
    hint: String? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = palette.textSecondary.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(Spacing.Medium))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary
        )
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary.copy(alpha = 0.6f)
            )
        }
    }
}