package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
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
 * 入口在「通知管理」页（与全局通知/分类开关同一屏），因为这份记录回答的正是
 * 那些开关造成的结果：关着哪个闸门，这里就写着"没提醒：xx 关着"。
 *
 * 数据源是本机 Room（`notify_history`），写入方是 `:ufi_notify` 进程 ——
 * 所以进页面查一次、回到前台再查一次，不用 Flow（跨进程失效通知不可靠，
 * 约定见 `NotifyHistoryStore` 头注释）。
 *
 * 壳必须用 [UfiPageBackgroundBox]（Box 版）：本页是 LazyColumn，而 [UfiPageBackground]
 * 自带 verticalScroll，把 LazyColumn 套进去会收到 infinity 最大高度直接抛异常。
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

    var showClearConfirm by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    LaunchedEffect(Unit) { viewModel.tools.loadNotifyHistory() }
    rememberResumeRefresh { viewModel.tools.loadNotifyHistory() }

    // 触底加载：并发闸门在 ViewModel 里（hasMore / loadingMore 双判断），这里只发信号。
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
                // 筛选放服务端（DAO 层）而不是筛内存：只看"没提醒"时用户要翻的是**全部**
                // 被拦下的记录，本地筛当前页会出现「明明还有更早的，列表只显示两条」。
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
                        filtered = toolsState.notifyHistoryFilter != NotifyHistoryStore.Filter.ALL
                    )
                }
            }
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

/** 结果筛选。value 与 [NotifyHistoryStore.Filter] 一一对应。 */
private val SYSTEM_HISTORY_FILTERS = listOf(
    HISTORY_FILTER_ALL to "全部",
    "delivered" to "已提醒",
    "blocked" to "没提醒"
)

@Composable
private fun SystemHistoryList(
    records: List<NotifyHistoryEntity>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    firstLoadPending: Boolean,
    hasMore: Boolean,
    filtered: Boolean
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
                        title = record.title.ifBlank { sceneLabel(record.sceneId) },
                        description = systemDescription(record),
                        descriptionMaxLines = HISTORY_DESCRIPTION_MAX_LINES,
                        icon = if (record.delivered) {
                            Icons.Default.NotificationsActive
                        } else {
                            Icons.Default.NotificationsOff
                        },
                        iconTint = if (record.delivered) palette.accent else palette.textSecondary
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

/**
 * 一行的说明：时间 · 分类 → 内容 →（没提醒时）原因。
 *
 * 原因用用户语言写，不写 `master` / `category` 这些常量名 —— 那是给代码看的。
 */
private fun systemDescription(record: NotifyHistoryEntity): String {
    val head = "${FormatUtils.formatTimestamp(record.ts)} · ${sceneLabel(record.sceneId)}"
    val body = record.message.takeIf { it.isNotBlank() }
    val tail = if (record.delivered) null else "没提醒：${blockedReasonText(record.blockedBy)}"
    return listOfNotNull(head, body, tail).joinToString("\n")
}

/** 拦截原因的用户可读文案。未知值原样带出来，方便对照代码查。 */
private fun blockedReasonText(reason: String?): String = when (reason) {
    NotifyHistoryStore.REASON_MASTER -> "通知总开关关着"
    NotifyHistoryStore.REASON_CATEGORY -> "这一类通知关着"
    NotifyHistoryStore.REASON_PERMISSION -> "系统设置里没允许本应用发通知"
    NotifyHistoryStore.REASON_DEDUP -> "刚提醒过一样的内容"
    NotifyHistoryStore.REASON_RATE_LIMIT -> "同类提醒太密，这条跳过了"
    null -> "原因未记录"
    else -> reason
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
