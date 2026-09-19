package com.ufi_axis.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.navigation.NavHostController
import com.ufi_axis.data.notification.NotifyHistoryEntity
import com.ufi_axis.data.notification.NotifyHistoryStore
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 「系统通知记录」——本机每条状态栏通知的结果，含**没提醒的原因**。
 *
 * 2026-09 记录页增强：列表精简（标题 + 时间·场景 + Badge），点击看完整详情，
 * 详情内可删除单条并复制正文/原因。
 *
 * 数据源本机 Room（`notify_history`），写入方 `:ufi_notify`；进页/回前台各查一次。
 */
@Composable
fun SystemNotifyHistoryScreen(
    viewModel: MainViewModel,
    navController: NavHostController
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

    val selected = toolsState.notifyHistory.firstOrNull { it.id == selectedId }
    LaunchedEffect(selectedId, toolsState.notifyHistory) {
        if (selectedId > 0 && toolsState.notifyHistoryLoaded && selected == null) {
            selectedId = -1L
        }
    }

    LaunchedEffect(Unit) { viewModel.tools.loadNotifyHistory() }
    rememberResumeRefresh { viewModel.tools.loadNotifyHistory() }

    LaunchedEffect(listState, toolsState.notifyHistory.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (lastVisible >= 0 &&
                    lastVisible >= toolsState.notifyHistory.size - HISTORY_LOAD_MORE_AHEAD
                ) {
                    viewModel.tools.loadMoreNotifyHistory()
                }
            }
    }

    UfiScreenScaffold(
        title = "系统通知记录",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(
                onClick = { showClearConfirm = true },
                enabled = toolsState.notifyHistory.isNotEmpty()
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
                    options = SYSTEM_HISTORY_FILTERS,
                    selectedValue = toolsState.notifyHistoryFilter.toChipValue(),
                    onSelect = { viewModel.tools.loadNotifyHistory(it.toSystemFilter()) },
                    modifier = Modifier.padding(
                        horizontal = Spacing.CardHorizontalMargin,
                        vertical = Spacing.Medium
                    )
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    SystemHistoryList(
                        records = toolsState.notifyHistory,
                        listState = listState,
                        firstLoadPending = !toolsState.notifyHistoryLoaded,
                        hasMore = toolsState.notifyHistoryHasMore,
                        filtered = toolsState.notifyHistoryFilter != NotifyHistoryStore.Filter.ALL,
                        onClick = { selectedId = it.id }
                    )
                }
            }
        }

        if (selected != null) {
            SystemHistoryDetailDialog(
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
                text = "删除本机保存的这条提醒记录。已经弹过的通知本身不受影响。",
                confirmText = "删除",
                destructive = true,
                onDismiss = { pendingDeleteId = -1L },
                onConfirm = {
                    val id = pendingDeleteId
                    pendingDeleteId = -1L
                    actionScope.launch {
                        val err = viewModel.tools.deleteNotifyHistoryItem(id)
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
                title = "清空系统通知记录",
                text = "删除本机保存的提醒记录。已经弹过的通知本身不受影响，" +
                    "只是之后查不到「当时为什么没提醒」。",
                confirmText = "清空",
                destructive = true,
                onDismiss = { showClearConfirm = false },
                onConfirm = {
                    showClearConfirm = false
                    actionScope.launch {
                        toastMessage = if (viewModel.tools.clearNotifyHistory()) {
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

private val SYSTEM_HISTORY_FILTERS = listOf(
    HISTORY_FILTER_ALL to "全部",
    "delivered" to "已提醒",
    "blocked" to "未提醒"
)

@Composable
private fun SystemHistoryList(
    records: List<NotifyHistoryEntity>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    firstLoadPending: Boolean,
    hasMore: Boolean,
    filtered: Boolean,
    onClick: (NotifyHistoryEntity) -> Unit
) {
    val palette = LocalResolvedPalette.current
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
                        title = historyListTitle(record.title.ifBlank { sceneLabel(record.sceneId) }),
                        description = systemListSubtitle(record),
                        titleMaxLines = 1,
                        descriptionMaxLines = 1,
                        icon = if (record.delivered) {
                            Icons.Default.NotificationsActive
                        } else {
                            Icons.Default.NotificationsOff
                        },
                        iconTint = if (record.delivered) palette.accent else palette.textSecondary,
                        trailing = { SystemHistoryOutcomeBadge(record.delivered) },
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
            icon = Icons.Default.NotificationsActive,
            message = if (filtered) "这个筛选下没有记录" else "还没有通知记录",
            hint = if (filtered) "换成「全部」看看" else "每条提醒的结果都会记在这里，包括没提醒成功的"
        )
    }
}

@Composable
private fun SystemHistoryDetailDialog(
    record: NotifyHistoryEntity,
    onDismiss: () -> Unit,
    onRequestDelete: () -> Unit,
    onCopy: (label: String, text: String) -> Unit
) {
    val bodyText = record.message.takeIf { it.isNotBlank() }
    val reasonText = if (record.delivered) null else historyBlockedReasonText(record.blockedBy)
    val reasonRaw = record.blockedBy?.takeIf { it.isNotBlank() && it != reasonText }
    UfiScrollableDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "通知详情",
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
            UfiDialogInfoRow(label = "时间", value = FormatUtils.formatTimestamp(record.ts))
            UfiDialogInfoRow(label = "场景", value = sceneLabel(record.sceneId))
            UfiDialogInfoRow(
                label = "结果",
                value = if (record.delivered) "已提醒" else "未提醒"
            )
            UfiDialogInfoRow(
                label = "标题",
                value = record.title.ifBlank { sceneLabel(record.sceneId) },
                multiline = true
            )

            if (bodyText != null) {
                UfiDialogSectionTitle("通知内容")
                HistoryDetailTextPanel(
                    title = "正文",
                    text = bodyText,
                    onCopy = { onCopy("通知内容", bodyText) },
                    copyContentDescription = "复制内容"
                )
            }
            if (reasonText != null) {
                UfiDialogSectionTitle("拦截原因")
                val panelText = reasonText + (reasonRaw?.let { "\n（$it）" } ?: "")
                HistoryDetailTextPanel(
                    title = "原因",
                    text = panelText,
                    onCopy = { onCopy("未提醒原因", panelText) },
                    copyContentDescription = "复制原因"
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

private fun NotifyHistoryStore.Filter.toChipValue(): String = when (this) {
    NotifyHistoryStore.Filter.ALL -> "all"
    NotifyHistoryStore.Filter.DELIVERED -> "delivered"
    NotifyHistoryStore.Filter.BLOCKED -> "blocked"
}

private fun String.toSystemFilter(): NotifyHistoryStore.Filter = when (this) {
    "delivered" -> NotifyHistoryStore.Filter.DELIVERED
    "blocked" -> NotifyHistoryStore.Filter.BLOCKED
    else -> NotifyHistoryStore.Filter.ALL
}
