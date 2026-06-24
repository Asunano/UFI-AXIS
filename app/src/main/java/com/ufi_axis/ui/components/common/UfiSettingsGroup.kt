package com.ufi_axis.ui.components.common

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults

@Composable
fun UfiSettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalResolvedPalette.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.CardHorizontalMargin, end = Spacing.CardHorizontalMargin, bottom = Spacing.CardBottomMargin),
        shape = UfiCardDefaults.shape,
        colors = CardDefaults.cardColors(containerColor = palette.cardBg),
        elevation = UfiCardDefaults.cardElevation(),
        border = UfiCardDefaults.cardBorder()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.CardPadding).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            content()
        }
    }
}

@Composable
fun UfiCollapsibleGroup(
    title: String,
    subtitle: String? = null,
    initialExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initialExpanded) }
    val palette = LocalResolvedPalette.current

    UfiSettingsGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = Spacing.Small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = palette.textPrimary
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                              else Icons.Default.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = palette.textSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                UfiDivider()
                Spacer(Modifier.height(Spacing.Medium))
                content()
            }
        }
    }
}

@Composable
fun UfiGroupHeader(title: String) {
    val palette = LocalResolvedPalette.current
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = palette.accent,
        modifier = Modifier.fillMaxWidth()
            .padding(start = 4.dp, bottom = Spacing.GroupSpacing)
    )
}