// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiMotion

/**
 * Custom animated toggle switch matching UFI-AXIS design system.
 * Uses spring animation for thumb movement and color transitions.
 */
@Composable
fun UfiSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalResolvedPalette.current

    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled -> palette.switchTrackOff.copy(alpha = 0.4f)
            checked -> palette.switchTrackOn
            else -> palette.switchTrackOff
        },
        animationSpec = UfiMotion.colorSettle(),
        label = "trackColor"
    )

    val thumbColor by animateColorAsState(
        targetValue = when {
            !enabled -> palette.switchThumbOff.copy(alpha = 0.5f)
            checked -> palette.switchThumbOn
            else -> palette.switchThumbOff
        },
        animationSpec = UfiMotion.colorSettle(),
        label = "thumbColor"
    )

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) Spacing.SwitchTrackWidth - Spacing.SwitchThumbSize - Spacing.SwitchThumbMargin else Spacing.SwitchThumbMargin,
        animationSpec = UfiMotion.switchThumb(),
        label = "thumbOffset"
    )

    Box(
        modifier = modifier
            .width(Spacing.SwitchTrackWidth)
            .height(Spacing.SwitchTrackHeight)
            .clip(RoundedCornerShape(Spacing.SwitchTrackHeight / 2))
            .background(trackColor)
            .then(
                // P2-1：toggleable + Role.Switch（读屏可感知开关状态），替代 clickable
                if (enabled) Modifier.toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange
                ) else Modifier
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(Spacing.SwitchThumbSize)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}
