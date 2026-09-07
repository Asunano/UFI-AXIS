package com.ufi_axis.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.UfiRingProgress
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiCardShadow
import com.ufi_axis.viewmodel.state.DashboardState

/**
 * 指标卡 — 2×2 独立小卡片网格（环形进度）· 扁平凸面重设计版。
 *
 * 方案 C 数据组合：CPU / 内存 / 电池 / 存储（温度不再单列，折回各自上下文）。
 * 视觉语言（与 Hero 卡统一的"从中间凸起"扁平语言）：
 *  - 无边框、无左侧色条，靠柔影 + 中心高光凸面定义层级
 *  - 阴影比 Hero 低一级（2dp vs 3dp），形成主次层级
 *  - 语义色只由环形进度本身承载（正常蓝 / 警告橙 / 错误红），图标随语义色同步着色
 */
@Composable
fun HomeMetricsCard(
    state: DashboardState,
    onMetricsClick: (MetricsSection) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top row: CPU + Memory
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricRingCard(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onMetricsClick(MetricsSection.SYSTEM) },
                icon = Icons.Default.Memory,
                title = "CPU 负载",
                valueText = state.cpuInfo?.let { "%.0f%%".format(it.usage_percent) } ?: "--",
                percent = state.cpuInfo?.let { (it.usage_percent / 100.0).toFloat() } ?: 0f,
                color = run {
                    val u = state.cpuInfo?.usage_percent ?: 0.0
                    when {
                        u > 80 -> palette.metricCritical
                        u > 50 -> palette.metricWarning
                        else -> palette.accent
                    }
                },
                subtitle = state.cpuInfo?.let { "%.0f核 · %.0f°C".format(it.core_count.toDouble(), it.temperature) }
            )
            MetricRingCard(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onMetricsClick(MetricsSection.MEMORY) },
                icon = Icons.Default.DeveloperBoard,
                title = "内存",
                valueText = state.memoryInfo?.let { "%.0f%%".format(it.usage_percent) } ?: "--",
                percent = state.memoryInfo?.let { (it.usage_percent / 100.0).toFloat() } ?: 0f,
                color = run {
                    val u = state.memoryInfo?.usage_percent ?: 0.0
                    when {
                        u > 90 -> palette.metricCritical
                        u > 70 -> palette.metricWarning
                        else -> palette.accent
                    }
                },
                subtitle = state.memoryInfo?.let { m ->
                    val used = m.used / (1024.0 * 1024.0 * 1024.0)
                    val total = m.total / (1024.0 * 1024.0 * 1024.0)
                    "%.1f/%.1f GB".format(used, total)
                }
            )
        }
        // Bottom row: Battery + Storage
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricRingCard(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onMetricsClick(MetricsSection.BATTERY) },
                icon = Icons.Default.BatteryChargingFull,
                title = "电池",
                valueText = state.batteryInfo?.let { "${it.percent}%" } ?: "--",
                percent = state.batteryInfo?.let { (it.percent / 100f).coerceIn(0f, 1f) } ?: 0f,
                color = run {
                    val p = state.batteryInfo?.percent ?: 100
                    when {
                        p < 20 -> palette.metricCritical
                        p < 60 -> palette.metricWarning
                        else -> palette.accent
                    }
                },
                subtitle = state.batteryInfo?.let { b ->
                    "${if (b.is_charging) "充电中" else "放电中"} · %.0f°C".format(b.temperature)
                }
            )
            MetricRingCard(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onMetricsClick(MetricsSection.STORAGE) },
                icon = Icons.Default.SdStorage,
                title = "存储",
                valueText = state.storageInfo?.let { "%.0f%%".format(it.usage_percent) } ?: "--",
                percent = state.storageInfo?.let { (it.usage_percent / 100.0).toFloat() } ?: 0f,
                color = run {
                    val u = state.storageInfo?.usage_percent ?: 0.0
                    when {
                        u > 90 -> palette.metricCritical
                        u > 70 -> palette.metricWarning
                        else -> palette.accent
                    }
                },
                subtitle = state.storageInfo?.let { s ->
                    val used = s.used / (1024.0 * 1024.0 * 1024.0)
                    val total = s.total / (1024.0 * 1024.0 * 1024.0)
                    "%.1f/%.1f GB".format(used, total)
                }
            )
        }
    }
}

@Composable
private fun MetricRingCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    valueText: String,
    percent: Float,
    color: Color,
    subtitle: String?
) {
    val palette = LocalResolvedPalette.current
    val stroke = 6.dp
    val cardShape = UfiCardDefaults.shape

    Box(
        modifier = modifier
            // 外部柔和四向投影（与 Hero 同级，轻浮起；全局 token 见 UfiCardDefaults.ufiCardShadow）
            .ufiCardShadow(elevation = 4.dp, shape = cardShape)
            // 纯色底，表面干净无渐变
            .background(palette.cardBg, cardShape)
            // 1dp 自适应描边（模仿 Hero 的边缘定义；divider 随主题，亮/暗都可见）
            .border(width = 1.dp, color = palette.divider, shape = cardShape)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 环形进度（环内显示数值）
            // 2026-08-31：原为本文件自绘 Canvas + 两条 drawArc，已改用公共 UfiRingProgress
            // （animate = false 保持"跟随轮询的即时读数"手感，centerContent 保留原 titleMedium 数值文字，
            //  trackColor 传实色 palette.divider —— 三项对齐后与自绘版本像素一致）。
            UfiRingProgress(
                progress = percent,
                size = 56.dp,
                strokeWidth = stroke,
                color = color,
                trackColor = palette.divider,
                animate = false,
                centerContent = {
                    Text(
                        text = valueText,
                        style = UfiTextStyles.panelTitleStrong,
                        color = palette.textPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            )

            Spacer(Modifier.width(12.dp))

            // 标题 + 副标题
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = color
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = title,
                        style = UfiTextStyles.caption,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

enum class MetricsSection {
    SYSTEM, MEMORY, BATTERY, STORAGE
}
