package com.ufi_axis.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiCardShadow
import com.ufi_axis.viewmodel.state.DashboardState

/**
 * 设备信息紧凑列表卡 — 替换首页底部「常用功能」2×2 快捷入口网格。
 *
 * 6 行系统信息（全部取自 [DashboardState]，无需新增任何后端 API 调用）：
 *   1. 设备型号   2. 固件版本   3. 系统版本   4. 内核   5. 运行时间   6. SIM 卡
 *
 * 容器样式使用同包卡片通用的
 * `Box + ufiCardShadow(4.dp) + clip(UfiCardDefaults.shape) + background(cardBg) + border(1.dp, divider)`。
 * 卡片内部使用普通 [Column]（非滚动），仅以 `Arrangement.spacedBy` 控制行间距，
 * 以符合 `UfiPageBackground` 不得再套滚动容器的约束。
 */
@Composable
fun HomeDeviceInfoCard(
    state: DashboardState,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val cardShape = UfiCardDefaults.shape

    // ── 6 行取值（防御空值，统一降级 "—"） ──
    val device = state.deviceInfo?.device

    val deviceModel = buildString {
        append(device?.brand ?: "")
        append(" ")
        append(device?.model ?: "")
    }.trim().ifEmpty { "—" }

    val firmware = buildString {
        val cr = state.deviceVersion?.cr_version?.trim()
        val wa = state.deviceVersion?.wa_inner_version?.trim()
        if (!cr.isNullOrBlank()) append(cr)
        if (!cr.isNullOrBlank() && !wa.isNullOrBlank()) append("\n")
        if (!wa.isNullOrBlank()) append(wa)
    }.ifEmpty { "—" }

    val systemVersion = device?.let { "Android ${it.android_version} (SDK ${it.sdk_version})" } ?: "—"
    val kernel = state.deviceInfo?.kernel ?: "—"
    val uptime = state.uptimeInfo?.uptime_display ?: "—"
    val simState = state.deviceInfo?.sim?.sim_state ?: "—"

    val rows = listOf(
        DeviceInfoRow(Icons.Default.Smartphone, "设备型号", deviceModel),
        DeviceInfoRow(Icons.Default.Build, "固件版本", firmware),
        DeviceInfoRow(Icons.Default.SystemUpdate, "系统版本", systemVersion),
        DeviceInfoRow(Icons.Default.DeveloperBoard, "内核", kernel),
        DeviceInfoRow(Icons.Default.Schedule, "运行时间", uptime),
        DeviceInfoRow(Icons.Default.SimCard, "SIM 卡", simState)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .ufiCardShadow(elevation = 4.dp, shape = cardShape)
            .clip(cardShape)
            .background(palette.cardBg, cardShape)
            .border(width = 1.dp, color = palette.divider, shape = cardShape)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            rows.forEach { row ->
                DeviceInfoRowItem(row = row)
            }
        }
    }
}

/** 单行数据模型：图标 + 标签 + 值。 */
private data class DeviceInfoRow(
    val icon: ImageVector,
    val label: String,
    val value: String
)

/**
 * 单行渲染：图标（accent 着色，20dp）+ 标签（labelSmall / textSecondary，左对齐，单行防压扁）
 * + 值（bodyMedium / textPrimary，右对齐，占满剩余宽度）。值过长时折为两行并以省略号收尾。
 */
@Composable
private fun DeviceInfoRowItem(row: DeviceInfoRow) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = row.icon,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = row.label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = row.value,
            style = UfiTextStyles.bodyEmphasis,
            color = palette.textPrimary,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
