package com.ufi_axis.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.DeliveryOutcome
import com.ufi_axis.data.model.MailSendRecord
import com.ufi_axis.data.model.deliveryOutcome
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 「最近投递」——某一条通知渠道每次投递的结果。
 *
 * 一个 composable 服务三条渠道（邮件 / Webhook / 本机短信），渠道由路由参数带进来。
 *
 * 2026-09 记录页增强：
 * - 列表只显示标题 + 时间·场景 + 结果 Badge，**不再**塞 recipient / error 全文；
 * - 点击行 → [UfiScrollableDialog] 详情（完整字段 + 复制错误）；
 * - 详情底部「删除」/「关闭」；删除经 `DELETE /api/sms-forward/history/{id}`。
 *
 * 数据源：设备端 `GET /api/sms-forward/history`（core `mail_send_records`）。
 * 三态判定走 [com.ufi_axis.data.model.deliveryOutcome]（全 app 唯一一处）。
 */
@Composable
fun DeliveryHistoryScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    channel: String
) {
    val palette = LocalResolvedPalette.current
    val toolsState by viewModel.toolsState.collectAsState()
    val actionScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    var showClearConfirm by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var selectedId by rememberSaveable { mutableStateOf(-1L) }
    var pendingDeleteId by rememberSaveable { mutableStateOf(-1L) }

    val selected = toolsState.deliveryHistory.firstOrNull { it.id == selectedId }
    // resume/刷新后该行已不在列表 → 自动关详情，避免弹着一条不存在的记录
    LaunchedEffect(selectedId, toolsState.deliveryHistory) {
        if (selectedId > 0 && toolsState.deliveryHistoryLoaded && selected == null) {
            selectedId = -1L
        }
    }

    LaunchedEffect(channel) { viewModel.tools.loadDeliveryHistory(channel) }
    rememberResumeRefresh {
        viewModel.tools.loadDeliveryHistory(channel, toolsState.deliveryHistoryResult)
    }

    LaunchedEffect(listState, toolsState.deliveryHistory.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (lastVisible >= 0 &&
                    lastVisible >= toolsState.deliveryHistory.size - HISTORY_LOAD_MORE_AHEAD
                ) {
                    viewModel.tools.loadMoreDeliveryHistory()
                }
            }
    }

    UfiScreenScaffold(
        title = deliveryHistoryTitle(channel),
        navController = navController,
        showBack = true,
        actions = {
            IconButton(
                onClick = { showClearConfirm = true },
                enabled = toolsState.deliveryHistory.isNotEmpty()
            ) {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = "清空记录",
                    tint = palette.textSecondary
                )
            }
        }
    ) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                UfiSingleChipSelector(
                    options = DELIVERY_HISTORY_FILTERS,
                    selectedValue = toolsState.deliveryHistoryResult ?: HISTORY_FILTER_ALL,
                    onSelect = { value ->
                        viewModel.tools.loadDeliveryHistory(
                            channel = channel,
                            result = value.takeIf { it != HISTORY_FILTER_ALL }
                        )
                    },
                    modifier = Modifier.padding(
                        horizontal = Spacing.CardHorizontalMargin,
                        vertical = Spacing.Medium
                    )
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    DeliveryHistoryList(
                        records = toolsState.deliveryHistory,
                        channel = channel,
                        listState = listState,
                        firstLoadPending = !toolsState.deliveryHistoryLoaded,
                        hasMore = toolsState.deliveryHistoryHasMore,
                        filtered = toolsState.deliveryHistoryResult != null,
                        onClick = { selectedId = it.id }
                    )
                }
            }
        }

        if (selected != null) {
            DeliveryRecordDetailDialog(
                record = selected,
                onDismiss = { selectedId = -1L },
                onRequestDelete = { pendingDeleteId = selected.id },
                onCopy = { label, text ->
                    runCatching {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText(label, text))
                        toastMessage = ToastMessage("已复制", ToastType.SUCCESS)
                    }.onFailure {
                        toastMessage = ToastMessage("复制失败", ToastType.ERROR)
                    }
                }
            )
        }

        if (pendingDeleteId > 0) {
            UfiConfirmDialog(
                visible = true,
                title = "删除这条记录",
                text = "从设备上移除这条投递记录，其它渠道与已发出的通知本身不受影响。",
                confirmText = "删除",
                destructive = true,
                onDismiss = { pendingDeleteId = -1L },
                onConfirm = {
                    val id = pendingDeleteId
                    pendingDeleteId = -1L
                    actionScope.launch {
                        val err = viewModel.tools.deleteDeliveryHistoryItem(id)
                        toastMessage = if (err == null) {
                            if (selectedId == id) selectedId = -1L
                            ToastMessage("已删除", ToastType.SUCCESS)
                        } else {
                            ToastMessage(err, ToastType.ERROR)
                        }
                    }
                }
            )
        }

        if (showClearConfirm) {
            UfiConfirmDialog(
                visible = true,
                title = "清空记录",
                text = "删除设备上保存的「${deliveryHistoryTitle(channel)}」，" +
                    "其他渠道的记录不受影响。已经发出的通知本身也不受影响。",
                confirmText = "清空",
                destructive = true,
                onDismiss = { showClearConfirm = false },
                onConfirm = {
                    showClearConfirm = false
                    actionScope.launch {
                        toastMessage = if (viewModel.tools.clearDeliveryHistory()) {
                            ToastMessage("已清空", ToastType.SUCCESS)
                        } else {
                            ToastMessage("清空失败，请重试", ToastType.ERROR)
                        }
                    }
                }
            )
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}

private val DELIVERY_HISTORY_FILTERS = listOf(
    HISTORY_FILTER_ALL to "全部",
    RESULT_SENT to "已送达",
    RESULT_FAILED to "投递失败",
    RESULT_SKIPPED to "已拦截"
)

@Composable
private fun DeliveryHistoryList(
    records: List<MailSendRecord>,
    channel: String,
    listState: LazyListState,
    firstLoadPending: Boolean,
    hasMore: Boolean,
    filtered: Boolean,
    onClick: (MailSendRecord) -> Unit
) {
    val palette = LocalResolvedPalette.current
    val icon = deliveryChannelIcon(channel)
    when {
        records.isNotEmpty() -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
        ) {
            items(records, key = { it.id }) { record ->
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = historyListTitle(record.subject.ifBlank { sceneLabel(record.scene) }),
                        description = deliveryListSubtitle(record),
                        titleMaxLines = 1,
                        descriptionMaxLines = 1,
                        icon = icon,
                        iconTint = when (record.deliveryOutcome) {
                            DeliveryOutcome.SENT -> palette.accent
                            DeliveryOutcome.SKIPPED -> palette.textSecondary
                            DeliveryOutcome.FAILED -> palette.error
                        },
                        trailing = { DeliveryOutcomeBadge(record.deliveryOutcome) },
                        onClick = { onClick(record) }
                    )
                }
            }
            if (hasMore) historyLoadMoreRow()
        }
        firstLoadPending -> UfiSkeletonList(
            modifier = Modifier.padding(
                horizontal = Spacing.CardHorizontalMargin,
                vertical = Spacing.Large
            )
        )
        else -> UfiEmptyState(
            icon = icon,
            message = if (filtered) "这个筛选下没有记录" else "还没有投递记录",
            hint = if (filtered) {
                "「全部」档包含已送达、投递失败与已拦截三类记录"
            } else {
                "渠道配置完整后，每次触发都会记一条，含未实际发出的那些"
            }
        )
    }
}

/**
 * 投递记录详情。
 *
 * 结构：基本信息（分区标题）→ 投递目标 → 失败/说明面板（浅底 + 面板内复制）。
 * 外壳 UfiScrollableDialog（上限约 82% 屏高）+ 长文本面板内部再限高，避免整页超长。
 */
@Composable
private fun DeliveryRecordDetailDialog(
    record: MailSendRecord,
    onDismiss: () -> Unit,
    onRequestDelete: () -> Unit,
    onCopy: (label: String, text: String) -> Unit
) {
    val outcome = record.deliveryOutcome
    val reason = record.error.takeIf { it.isNotBlank() }
    UfiScrollableDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "投递详情",
        actions = {
            UfiDialogActions(
                onDismiss = onRequestDelete,
                dismissText = "删除",
                dismissDestructive = true,
                onConfirm = onDismiss,
                confirmText = "关闭"
            )
        }
    ) {
        UfiDialogBody {
            UfiDialogSectionTitle("基本信息")
            UfiDialogInfoRow(
                label = "时间",
                value = FormatUtils.formatTimestamp(record.sentAt)
            )
            UfiDialogInfoRow(label = "场景", value = sceneLabel(record.scene))
            UfiDialogInfoRow(label = "渠道", value = deliveryChannelLabel(record.channel))
            UfiDialogInfoRow(label = "结果", value = deliveryOutcomeLabel(outcome))

            UfiDialogSectionTitle("投递目标")
            val recipient = record.recipient.takeIf { it.isNotBlank() }
            if (recipient != null) {
                UfiDialogInfoRow(label = "目标", value = recipient, multiline = true)
            } else {
                UfiDialogInfoRow(label = "目标", value = "—")
            }
            val subject = record.subject.takeIf { it.isNotBlank() }
            if (subject != null) {
                UfiDialogInfoRow(label = "主题", value = subject, multiline = true)
            }

            if (reason != null) {
                UfiDialogSectionTitle(
                    if (outcome == DeliveryOutcome.FAILED) "失败详情" else "说明"
                )
                HistoryDetailTextPanel(
                    title = if (outcome == DeliveryOutcome.FAILED) "错误信息" else "跳过原因",
                    text = reason,
                    emphasis = outcome == DeliveryOutcome.FAILED,
                    onCopy = { onCopy("投递错误", reason) },
                    copyContentDescription = "复制错误详情"
                )
            }

            Text(
                text = "记录 ID ${record.id}",
                style = MaterialTheme.typography.bodySmall,
                color = LocalResolvedPalette.current.textSecondary
            )
        }
    }
}

private val DELIVERY_HISTORY_TITLES = mapOf(
    DELIVERY_CHANNEL_MAIL to "邮件投递记录",
    DELIVERY_CHANNEL_WEBHOOK to "Webhook 投递记录",
    DELIVERY_CHANNEL_LOCAL_SMS to "本机短信投递记录"
)

private fun deliveryHistoryTitle(channel: String): String =
    DELIVERY_HISTORY_TITLES[channel] ?: "投递记录"

private fun deliveryChannelIcon(channel: String): ImageVector = when (channel) {
    DELIVERY_CHANNEL_WEBHOOK -> Icons.Default.Http
    DELIVERY_CHANNEL_LOCAL_SMS -> Icons.Default.Sms
    else -> Icons.Default.MailOutline
}

private const val RESULT_SENT = "success"
private const val RESULT_FAILED = "failed"
private const val RESULT_SKIPPED = "skipped"
