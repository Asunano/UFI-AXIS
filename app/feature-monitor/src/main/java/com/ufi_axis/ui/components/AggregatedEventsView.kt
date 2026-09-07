package com.ufi_axis.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.model.AlertRecord
import com.ufi_axis.ui.components.common.UfiPagination
import com.ufi_axis.ui.components.common.UfiPaginationVariant
import com.ufi_axis.ui.components.common.UfiScrollableDialog
import com.ufi_axis.ui.components.common.ufiPageCount
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight

/**
 * FIX-3/FIX-4（2026-08-23）：事件中心「按类型聚合」视图 + 「具体事件明细」弹窗。
 *
 * 痛点：此前事件中心按行渲染每条 AlertRecord，500+ 条堆叠、每张卡展示完整 detail message，
 * 信息密集无法一眼扫读。
 *
 * 方案：
 * - 聚合行 [AggregateAlertRow]：按 (type, level) 聚合为一行，仅显示「类型 label + 累计次数 +
 *   未读数 + 最近相对时间 + 等级 chip」，核心展示在主列表里简洁。
 * - 点击聚合行 → [AlertTypeDetailDialog] UfiScrollableDialog 弹窗显示该 (type, level) 下所有具体
 *   AlertRecord，每条用现有 MonitorEventCard 渲染，弹窗内置 20/页分页。
 * - 弹窗 tag 上显示「流量 · 严重 · 共 N 条」，超过 [IN_DIALOG_PAGE_SIZE] 时走现有分页条。
 *
 * 模块化：data class + Composable 抽离本文件，MonitorScreen 改 compact 仅写入/读出。
 */

/** 单条聚合行（多个 AlertRecord 压缩为一行）。 */
data class AggregateAlertRow(
    val type: String,
    val level: String,
    val count: Int,
    val unread: Int,
    val lastTimestampMs: Long
)

/** 弹窗内置分页大小（与事件中心 pageSize 共用一个值由调用方传入）。 */
const val IN_DIALOG_PAGE_SIZE_DEFAULT = 20

/**
 * 聚合行渲染：与现有 MonitorEventCard 同视觉权重（图标色块 + 信息列 + 时间），但**只展示类型 + 次数**，
 * 不显示 detail message（避免主列表信息堆叠冗余）。点击触发 [onClick]。
 */
@Composable
fun AggregatedAlertRowView(
    row: AggregateAlertRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val severityColor = severityColorFor(palette, row.level)
    val typeLabel = com.ufi_axis.ui.components.alertTypeLabel(row.type)
    val severityText = severityTextFor(row.level)
    val typeIcon = typeIconFor(row.type)
    val hasUnread = row.unread > 0

    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = UfiCardDefaults.shape,
        color = palette.cardBg,
        border = BorderStroke(1.dp, palette.cardBorder)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 左侧严重度角色块（与 MonitorEventCard 一致：40dp 圆角矩形 + 严重度色 + type 图标）
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(UfiCardDefaults.subtleShape)
                    .background(if (hasUnread) severityColor else severityColor.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = typeIcon,
                    contentDescription = typeLabel,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            // 中间信息列
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = typeLabel,
                    style = UfiTextStyles.sectionTitle.copy(fontWeight = if (hasUnread) UfiWeight.Strong else UfiWeight.Medium),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // "发生 N 次 · 未读 M 条"；全部已读时隐藏「未读」。
                    text = if (hasUnread) {
                        "发生 ${row.count} 次 · 未读 ${row.unread} 条"
                    } else {
                        "发生 ${row.count} 次 · 全部已读"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                // tag chips：类型 / 严重度 / 未读计数（如有）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AggregatedTagChip(typeLabel, severityColor)
                    Spacer(Modifier.width(6.dp))
                    AggregatedTagChip(severityText, palette.textSecondary)
                    if (hasUnread) {
                        Spacer(Modifier.width(6.dp))
                        AggregatedTagChip("未读 ${row.unread}", palette.accent)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // 右上角：最近一次发生的相对时间（"1 分钟前"），与现有 MonitorEventCard 一致
            Text(
                text = relativeTimeSmart(row.lastTimestampMs),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary
            )
        }
    }
}

/**
 * FIX-7（2026-08-23）：事件中心弹窗，显示某 (type, level) 下所有具体 AlertRecord。
 *
 * - titleLeading：传 severity 色块圆 + 图标，渲染在 title 左侧（红色块 = 严重）。
 * - subtitle slot：标签行（"流量告警 · 严重 · 共 82 条"），固定在 title 下方不随滚动。
 * - bottomContent slot：DialogInlinePagination 分页条，固定在底部不参与列表滚动。
 * - 用 [UfiScrollableDialog]：列表较长时高度自适应防异常，遵循「字段>6 或超 82% 屏高必用」红线。
 * - 弹窗内每条用现有 [MonitorEventCard] 完整渲染（包含 detail message + 等级 chip），与主列表风格统一。
 * - 弹窗右上角 × 自动关闭按钮由 UfiDialogShell 提供（不需再传 dismissButton）。
 */
@Composable
fun AlertTypeDetailDialog(
    visible: Boolean,
    type: String?,
    level: String?,
    allRows: List<AlertRecord>,
    onDismiss: () -> Unit,
    onAckOne: (Long) -> Unit,
    onDeleteOne: ((Long) -> Unit)? = null,
    pageSize: Int = IN_DIALOG_PAGE_SIZE_DEFAULT
) {
    if (!visible || type == null || level == null) return
    val palette = LocalResolvedPalette.current
    val typeLabel = com.ufi_axis.ui.components.alertTypeLabel(type)
    val severityText = severityTextFor(level)
    val severityColor = severityColorFor(palette, level)
    // 同 (type, level) 过滤（同 type 不同 level 如 warning + critical 同存时分弹窗渲染）。
    val filtered = remember(type, level, allRows) { allRows.filter { it.type == type && it.level == level } }
    var page by rememberSaveable(type, level) { mutableIntStateOf(0) }
    LaunchedEffect(type, level) { page = 0 }

    val pageCount = ufiPageCount(filtered.size, pageSize)
    val start = page * pageSize
    val slice = if (start >= filtered.size) emptyList()
        else filtered.subList(start, kotlin.math.min(start + pageSize, filtered.size))

    // 严重度色块圆 + type 图标（titleLeading slot 用）。
    val leadingTypeIcon = typeIconFor(type)
    val titleLeadingBlock: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(UfiCardDefaults.subtleShape)
                .background(severityColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = leadingTypeIcon,
                contentDescription = typeLabel,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    // 副标题：类型 / 等级 / 共 N 条，固定在 title 下方不滚动。
    val subtitleChips: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AggregatedTagChip(typeLabel, severityColor)
            Spacer(Modifier.width(6.dp))
            AggregatedTagChip(severityText, palette.textSecondary)
            Spacer(Modifier.width(6.dp))
            AggregatedTagChip("共 ${filtered.size} 条", palette.accent)
        }
    }

    // 底部固定分页条（仅当多页时渲染）。
    // 2026-09-04 收口：原私有 DialogInlinePagination（TextButton 无底 + panelTitleStrong 页码）
    // 已删除，改用公共 UfiPagination 的 Inline 形态 —— 与页面级浮动分页条同一套配色/形状/排版。
    // 外层容器与位置保持不变：Column + 上下 Spacer，居中由这层 Row 负责（组件本身只管三段布局）。
    val bottomBar: @Composable () -> Unit = {
        if (pageCount > 1) {
            Column {
                Spacer(Modifier.height(Spacing.Medium))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Medium),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UfiPagination(
                        currentPage = page,
                        pageCount = pageCount,
                        onPrev = { if (page > 0) page-- },
                        onNext = { if (page < pageCount - 1) page++ },
                        variant = UfiPaginationVariant.Inline,
                        onJumpClick = null
                    )
                }
                Spacer(Modifier.height(Spacing.Small))
            }
        }
    }

    UfiScrollableDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "$typeLabel · $severityText",
        titleLeading = titleLeadingBlock,
        // FIX-7：subtitle 不再嵌在 content 内，改由公共对话框固定槽接管（不滚动）。
        subtitle = subtitleChips,
        // FIX-7：分页条改由 bottomContent 接管（固定底部，不滚动）。
        bottomContent = bottomBar,
        // 不再传 confirmButton/dismissButton，shell 自动右上角 × 关闭。
        confirmButton = null,
        dismissButton = null
    ) {
        // content() 滚动区：只放具体事件卡片列，不含 tag row / 分页条。
        if (filtered.isEmpty()) {
            Text(
                text = "暂无具体事件",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
                modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth()
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                slice.forEach { record ->
                    MonitorEventCard(
                        alert = record,
                        onAckOne = onAckOne,
                        onDelete = onDeleteOne,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// ===== 辅助函数 =====

/**
 * 本文件私有 chip 渲染：与 MonitorOverview.kt::EventTagChip 同语义（同色系 12% 底 + 同色文字），
 * 但该函数为 private 不能跨文件使用，故在聚合视图内私有拷贝一份。命名加 Aggregated 前缀
 * 避免日后 EventTagChip 开放 public 时冲突。
 */
@Composable
private fun AggregatedTagChip(text: String, color: Color) {
    Text(
        text = text,
        style = UfiTextStyles.caption.copy(fontWeight = UfiWeight.Medium),
        color = color,
        modifier = Modifier
            .clip(UfiCardDefaults.tagShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

/** 类型 → 图标（与 MonitorOverview.kt::alertTypeIcon 保持同语义，独立拷贝避免耦合 private）。 */
private fun typeIconFor(type: String): ImageVector = when (type) {
    "traffic" -> Icons.Default.DataUsage
    "temperature" -> Icons.Default.DeviceThermostat
    "battery" -> Icons.Default.BatteryFull
    "signal" -> Icons.Default.SignalCellularAlt
    "connectivity" -> Icons.Default.WifiOff
    else -> Icons.Default.Warning
}

/** 严重度 → 中文 label（沿用 MonitorOverview.kt::severityText 语义）。 */
private fun severityTextFor(level: String): String = when (level) {
    "critical" -> "严重"
    "warning" -> "警告"
    "normal" -> "提示"
    else -> "一般"
}

/** 严重度 → 调色板色（critical=error / warning=warning / normal=accent）。 */
private fun severityColorFor(palette: com.ufi_axis.ui.theme.ResolvedPalette, level: String): Color = when (level) {
    "critical" -> palette.error
    "warning" -> palette.warning
    else -> palette.accent
}

/**
 * 相对时间简版（独立实现，避免依赖 MonitorOverview.kt 私有函数）。
 * - < 1 分钟 → "刚刚"
 * - < 1 小时 → "N 分钟前"
 * - < 1 天   → "N 小时前"
 * - ≥ 1 天   → "N 天前"
 * - 跨年     → "yyyy-MM-dd"
 */
private fun relativeTimeSmart(ts: Long): String {
    if (ts <= 0) return "—"
    val now = System.currentTimeMillis()
    val diff = (now - ts).coerceAtLeast(0)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 7 * day -> "${diff / day} 天前"
        else -> {
            val date = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
            date.format(java.util.Date(ts))
        }
    }
}
