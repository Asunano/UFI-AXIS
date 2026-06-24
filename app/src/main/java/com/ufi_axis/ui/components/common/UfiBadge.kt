package com.ufi_axis.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults

enum class UfiBadgeType { DEFAULT, SUCCESS, WARNING, ERROR, INFO }

@Composable
fun UfiBadge(
    text: String,
    modifier: Modifier = Modifier,
    type: UfiBadgeType = UfiBadgeType.DEFAULT,
    icon: @Composable (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current

    val (bgColor, textColor, borderColor) = when (type) {
        UfiBadgeType.SUCCESS -> Triple(palette.success.copy(alpha = 0.12f), palette.success, palette.success.copy(alpha = 0.3f))
        UfiBadgeType.WARNING -> Triple(palette.warning.copy(alpha = 0.12f), palette.warning, palette.warning.copy(alpha = 0.3f))
        UfiBadgeType.ERROR -> Triple(palette.error.copy(alpha = 0.12f), palette.error, palette.error.copy(alpha = 0.3f))
        UfiBadgeType.INFO -> Triple(palette.accent.copy(alpha = 0.12f), palette.accent, palette.accent.copy(alpha = 0.3f))
        UfiBadgeType.DEFAULT -> Triple(palette.accentSecondary.copy(alpha = 0.15f), palette.textSecondary, palette.divider.copy(alpha = 0.2f))
    }

    Row(
        modifier = modifier
            .clip(UfiCardDefaults.chipShape)
            .background(bgColor)
            .border(Dp.Hairline, borderColor, UfiCardDefaults.chipShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        icon?.invoke()
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}

@Composable
fun UfiTag(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current

    val bgColor = if (selected) palette.chipSelectedBg else Color.Transparent
    val borderColor = if (selected) palette.accent.copy(alpha = 0.3f) else palette.chipUnselectedBorder
    val textColor = if (selected) palette.accent else palette.textSecondary

    val baseModifier = modifier
        .clip(UfiCardDefaults.chipShape)
        .background(bgColor)
        .border(Dp.Hairline, borderColor, UfiCardDefaults.chipShape)
        .padding(horizontal = 12.dp, vertical = 6.dp)

    val clickableModifier = if (onClick != null) {
        baseModifier.then(
            Modifier.clip(UfiCardDefaults.chipShape)
        ).then(
            Modifier.clickable(onClick = onClick)
        )
    } else baseModifier

    Box(modifier = clickableModifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor
        )
    }
}
