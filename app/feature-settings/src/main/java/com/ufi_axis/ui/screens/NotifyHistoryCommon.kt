package com.ufi_axis.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.model.DeliveryOutcome
import com.ufi_axis.data.model.MailSendRecord
import com.ufi_axis.data.notification.NotifyHistoryEntity
import com.ufi_axis.data.notification.NotifyHistoryStore
import com.ufi_axis.ui.components.common.UfiBadge
import com.ufi_axis.ui.components.common.UfiBadgeType
import com.ufi_axis.ui.components.common.UfiDivider
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.components.common.UfiSectionHeader
import com.ufi_axis.ui.components.common.UfiSettingsGroup
import com.ufi_axis.ui.components.common.UfiStatItem
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.util.FormatUtils

// ════════════════════════════════════════════════════
// 记录页的共用件
// ════════════════════════════════════════════════════
//
// 2026-09-08：原来「通知历史」是一个双 Tab 页，现已拆成两个独立页 ——
// [SystemNotifyHistoryScreen]（本机状态栏通知）与 [DeliveryHistoryScreen]（设备端投递记录），
// 入口分别落在「通知管理」和各条渠道的配置页里，各自与自己那半的设置放在一起。
//
// 2026-09-10：投递记录页改为按渠道进入（邮件 / Webhook / 本机短信各一个入口，同一个 composable），
// 所以共用件又多了「入口行摘要」一项 —— 三个入口的右侧文案必须逐字一致，
// 各写一遍就会分叉成三种口径。
//
// 差异部分（图标 / 标题 / 筛选口径）留在各自的页面文件里，不硬凑成一个"通用列表组件"。

/** 触底预加载提前量：倒数第 3 条进入视口就去拉下一页。两个列表一致。 */
internal const val HISTORY_LOAD_MORE_AHEAD = 3

/** 一行说明最多几行（时间 + 内容 + 结果三段，内容可能折行）。 */
internal const val HISTORY_DESCRIPTION_MAX_LINES = 4

/**
 * 「不过滤」那一档 chip 的 value。
 *
 * 两个记录页都需要一个"全部"档，而它在两边都**不是**接口/DAO 认得的取值 ——
 * 各自的映射函数负责把它翻成 null / `Filter.ALL`。
 */
internal const val HISTORY_FILTER_ALL = "all"

/**
 * 投递渠道 id（core 的 `channel` 列 / `channel` 查询参数）。
 *
 * 放共用件里而不是各页面各写一份字面量：这三个值同时出现在四个文件里
 * （记录页 + 三个渠道配置页的入口卡），写错一个的表现是"入口点进去列表是空的"，
 * 而空列表在这一页是完全正常的状态，肉眼分不出来。
 */
internal const val DELIVERY_CHANNEL_MAIL = "mail"
internal const val DELIVERY_CHANNEL_WEBHOOK = "webhook"
internal const val DELIVERY_CHANNEL_LOCAL_SMS = "local_sms"

/** 三条渠道入口卡的标题与副文案：同一件事在三处必须是同一句话。 */
internal const val DELIVERY_HISTORY_ENTRY_TITLE = "最近投递"
internal const val DELIVERY_HISTORY_ENTRY_DESCRIPTION = "这条渠道每次投递的结果，含未发出的原因"

/**
 * 投递记录入口行的右侧摘要（三条渠道共用）。
 *
 * 先说没发出、再说被跳过、最后才说总数：用户点进那一页多半是在查"哪条没送到"，
 * 总条数回答不了这个问题。失败与跳过**分两档而不是相加** ——
 * 跳过是闸门按用户自己的配置拦下的，并进失败数会让人去排一个不存在的故障。
 *
 * @param ready state 里装的就是本渠道的数据且已完成过一次加载。三条渠道共用同一个 state 槽位，
 *   为 false 时只能说"加载中"，否则会把上一个渠道的数字显示成本渠道的。
 */
internal fun deliveryHistorySummary(
    ready: Boolean,
    total: Int,
    failedTotal: Int,
    skippedTotal: Int
): String = when {
    !ready -> "加载中"
    failedTotal > 0 -> "$failedTotal 条投递失败"
    skippedTotal > 0 -> "$skippedTotal 条已拦截"
    else -> "共 $total 条"
}

// 场景 id → 中文名不在本文件：唯一那张词表在 `NotifySceneLabels.kt`（[sceneLabel]）。
// 2026-09-11 删掉了这里原有的第二张 map —— 它与那张已经分叉（sms「新短信」vs「短信正文」、
// download「下载完成」vs「下载结束」、tunnel「内网穿透」vs「隧道异常」），
// 而且缺 battery / test 两档，于是电池投递记录在这一页显示成裸的 `battery`。

/** 底部加载指示：这是真 IO 分页，不给反馈用户会以为列表到底了。 */
internal fun LazyListScope.historyLoadMoreRow() {
    item(key = "history-load-more") {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Large),
            contentAlignment = Alignment.Center
        ) {
            UfiLoadingIndicator(modifier = Modifier.size(Spacing.IconSizeLarge))
        }
    }
}

/**
 * 「最多 N 条 · 最近 M 天」——两道保留上限的一句话摘要。
 *
 * 入口行的右侧摘要与保留上限弹窗顶部共用同一份文案：两处写法不一致时，
 * 用户会以为自己看到的是两个不同的设置。`ageDays <= 0` 必须说成"不限时长"，
 * 不能省略 —— 省了就没人知道时间那道闸是关着的。
 */
internal fun historyRetentionSummary(rows: Int, ageDays: Int): String =
    if (ageDays <= 0) "最多 $rows 条 · 不限时长" else "最多 $rows 条 · 最近 $ageDays 天"

// ════════════════════════════════════════════════════
// 列表精简 / 详情弹窗 共用映射（2026-09 记录页增强）
// ════════════════════════════════════════════════════
//
// 只提供文案与 Badge 类型映射，**不**新建公共 Dialog 组件 —— 详情壳一律用
// UfiScrollableDialog + UfiDialogBody + UfiDialogInfoRow + UfiDialogActions。
// 列表 description 禁止再拼 error / message 全文（护栏 HistoryUiGuardTest 守）。

/** 投递三态 → 列表/详情 Badge。文案统一用书面口径，与筛选 chip / 详情「结果」一致。 */
@Composable
internal fun DeliveryOutcomeBadge(outcome: DeliveryOutcome) {
    val (text, type) = when (outcome) {
        DeliveryOutcome.SENT -> "已送达" to UfiBadgeType.SUCCESS
        DeliveryOutcome.FAILED -> "投递失败" to UfiBadgeType.ERROR
        DeliveryOutcome.SKIPPED -> "已拦截" to UfiBadgeType.WARNING
    }
    UfiBadge(text = text, type = type)
}

/** 系统通知两态 Badge。 */
@Composable
internal fun SystemHistoryOutcomeBadge(delivered: Boolean) {
    if (delivered) {
        UfiBadge(text = "已提醒", type = UfiBadgeType.SUCCESS)
    } else {
        UfiBadge(text = "未提醒", type = UfiBadgeType.WARNING)
    }
}

/** 投递列表副文：时间 · 场景（结果走 trailing Badge，不进 description）。 */
internal fun deliveryListSubtitle(record: MailSendRecord): String =
    "${FormatUtils.formatTimestamp(record.sentAt)} · ${sceneLabel(record.scene)}"

/** 系统通知列表副文：时间 · 场景。 */
internal fun systemListSubtitle(record: NotifyHistoryEntity): String =
    "${FormatUtils.formatTimestamp(record.ts)} · ${sceneLabel(record.sceneId)}"

/** 拦截原因用户可读文案（与历史页共用，未知值原样带出）。 */
internal fun historyBlockedReasonText(reason: String?): String = when (reason) {
    NotifyHistoryStore.REASON_MASTER -> "通知总开关关着"
    NotifyHistoryStore.REASON_CATEGORY -> "这一类通知关着"
    NotifyHistoryStore.REASON_PERMISSION -> "系统设置里没允许本应用发通知"
    NotifyHistoryStore.REASON_DEDUP -> "刚提醒过一样的内容"
    NotifyHistoryStore.REASON_RATE_LIMIT -> "同类提醒太密，这条跳过了"
    null -> "原因未记录"
    else -> reason
}

/** 投递结果中文（详情弹窗 / 筛选 chip 共用）。 */
internal fun deliveryOutcomeLabel(outcome: DeliveryOutcome): String = when (outcome) {
    DeliveryOutcome.SENT -> "已送达"
    DeliveryOutcome.FAILED -> "投递失败"
    DeliveryOutcome.SKIPPED -> "已拦截"
}

/** 投递渠道中文。 */
internal fun deliveryChannelLabel(channel: String): String = when (channel) {
    DELIVERY_CHANNEL_MAIL -> "邮件"
    DELIVERY_CHANNEL_WEBHOOK -> "Webhook"
    DELIVERY_CHANNEL_LOCAL_SMS -> "本机短信"
    else -> channel
}

/**
 * 列表标题展示用：超长截断（下载文件名等），完整文案只进详情弹窗。
 *
 * 与 [UfiSettingsItem] 的 `titleMaxLines=1` + Ellipsis 叠加：Compose 省略号按字形截断，
 * 这里再按字符上限收一刀，避免极长无空格串把行高顶歪。
 */
internal fun historyListTitle(raw: String, maxChars: Int = 48): String {
    val t = raw.trim()
    if (t.length <= maxChars) return t
    return t.take(maxChars) + "…"
}

/**
 * 三渠道配置页共用「发送/投递统计」卡（模块内组件，不进公共 UI 库）。
 *
 * 布局对齐邮件页既有统计：
 * - 标题行右侧：最近成功（紧凑 MM-dd HH:mm）
 * - 三格：由调用方给标签与数字（邮件=总发送/成功/失败；Webhook/短信=总投递/投递失败/已拦截）
 * - **不展示错误正文** —— 失败详情走「最近投递」详情弹窗
 * - 可选底部「发送测试」行
 */
@Composable
internal fun ChannelDeliveryStatsCard(
    total: Int?,
    mid: Int?,
    failed: Int?,
    lastSuccessTs: Long?,
    totalLabel: String,
    midLabel: String,
    failedLabel: String,
    loaded: Boolean,
    testRow: (@Composable () -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    UfiSettingsGroup {
        UfiSectionHeader(
            title = "发送统计",
            trailing = {
                Text(
                    text = "最近成功 · " + (
                        lastSuccessTs?.takeIf { it > 0 }?.let { formatStatsTime(it) } ?: "暂无"
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UfiStatItem(
                value = if (loaded) total?.toString() ?: "0" else "--",
                label = totalLabel,
                modifier = Modifier.weight(1f)
            )
            UfiStatItem(
                value = if (loaded) mid?.toString() ?: "0" else "--",
                label = midLabel,
                modifier = Modifier.weight(1f)
            )
            UfiStatItem(
                value = if (loaded) failed?.toString() ?: "0" else "--",
                label = failedLabel,
                modifier = Modifier.weight(1f)
            )
        }
        if (testRow != null) {
            UfiDivider()
            testRow()
        }
    }
}

internal fun formatStatsTime(ts: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ts))

/**
 * 详情弹窗内的「长文本面板」：浅底 + 可选描边，标题与复制按钮都在面板内。
 *
 * 用途：失败异常链 / 通知正文 / 拦截原因。极长文本内部滚动（heightIn 上限），
 * 避免把 UfiScrollableDialog 撑成整屏滚动条。
 * 只用基础 Compose + 主题 token，不新增公共 UI API。
 */
@Composable
internal fun HistoryDetailTextPanel(
    title: String,
    text: String,
    onCopy: (() -> Unit)? = null,
    emphasis: Boolean = false,
    copyContentDescription: String = "复制",
) {
    val palette = LocalResolvedPalette.current
    val shape = UfiCardDefaults.subtleShape
    val bg = if (emphasis) {
        palette.error.copy(alpha = 0.08f)
    } else {
        palette.surfaceMuted
    }
    val borderColor = if (emphasis) {
        palette.error.copy(alpha = if (palette.isDark) 0.35f else 0.22f)
    } else {
        palette.cardBorder.copy(alpha = 0.6f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(BorderStroke(1.dp, borderColor), shape)
            .padding(horizontal = Spacing.Medium, vertical = Spacing.Small)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = if (emphasis) palette.error else palette.textSecondary,
                modifier = Modifier.weight(1f)
            )
            if (onCopy != null) {
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = copyContentDescription,
                        tint = palette.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = HISTORY_DETAIL_PANEL_MAX_HEIGHT)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textPrimary,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 长文本面板最大高度：再长也在面板内滚，不把整张详情弹窗拉成超长页。 */
private val HISTORY_DETAIL_PANEL_MAX_HEIGHT = 180.dp
