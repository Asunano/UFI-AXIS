package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.download.MediaDownloadQueue
import com.ufi_axis.data.download.MediaDownloadWorker
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiListRowCard
import com.ufi_axis.ui.components.common.UfiPageBackgroundBox
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSectionHeader
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.FormatUtils

/**
 * 「下载任务」页：视频下载的队列与历史（2026-09-20 从视频设置页拆出）。
 *
 * ## 为什么必须离开设置页
 * 设置页上的每一行都是"一个可配置项"，条数固定、看一眼就知道全貌。队列与历史都不是：
 * 条数由使用量决定（批量下载一次就能进来几十条），而且会随时间一直长。把它们留在设置页的
 * 直接后果是"落点目录、最近播放这些真正的设置项被一串任务行推到屏幕外"——
 * 也就是用户反馈里说的那个不美观的分区。
 *
 * ## 为什么队列和历史同页而不是两页
 * 它们是**同一条任务的前后两态**：排队 → 下载中 → 落进历史。分成两页的话，用户点了下载之后
 * 要先去队列页看进度、完成后再换到另一页确认结果，而这两页在任何时刻都有一半是空的。
 *
 * ## 壳为什么是 [UfiPageBackgroundBox]
 * 内容是 `LazyColumn`。[com.ufi_axis.ui.components.common.UfiPageBackground] 自带
 * `verticalScroll`，把懒列表套进去会拿到无限高约束直接抛异常（同 `SmsFilterRulesScreen`）。
 */
@Composable
fun MediaVideoDownloadHistoryScreen(navController: NavHostController) {
    val context = LocalContext.current
    val palette = LocalResolvedPalette.current

    // 队列与历史都持久化在本机文件里，进页先补一次读取：本页可能被深链接直接打开，
    // 不能假设一定是从视频设置页（那边也 ensureLoaded）走进来的。该调用自身幂等。
    LaunchedEffect(Unit) { MediaDownloadQueue.ensureLoaded(context) }

    val queue by MediaDownloadQueue.queue.collectAsState()
    val history by MediaDownloadQueue.history.collectAsState()

    UfiScreenScaffold(
        title = "下载任务",
        navController = navController,
        showBack = true,
        actions = {
            // 没有历史时**不渲染**这颗按钮，而不是 `enabled = false`：tint 是显式给的，
            // M3 的 disabled 着色被盖掉，禁用态看起来与可点态一模一样（同 SmsBlockedScreen）。
            if (history.isNotEmpty()) {
                var showClearHistoryConfirm by remember { mutableStateOf(false) }
                IconButton(onClick = { showClearHistoryConfirm = true }) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = "清空下载历史",
                        tint = palette.textSecondary
                    )
                }
                if (showClearHistoryConfirm) {
                    UfiConfirmDialog(
                        title = "清空下载历史",
                        text = "将清空全部 ${history.size} 条下载记录，此操作不可恢复。",
                        confirmText = "清空",
                        destructive = true,
                        onDismiss = { showClearHistoryConfirm = false },
                        onConfirm = { showClearHistoryConfirm = false; MediaDownloadQueue.clearHistory() }
                    )
                }
            }
        }
    ) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (queue.isEmpty() && history.isEmpty()) {
                UfiListEmptyState(
                    text = "还没有下载任务。在视频列表里选中文件后下载，进度与结果都会出现在这里。",
                    icon = Icons.Default.Download
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // 水平留白在这里给：[UfiListRowCard] 自己不带页面内距，
                    // 由容器统一控制才能和设置页的卡片左右边缘对齐。
                    contentPadding = PaddingValues(
                        horizontal = Spacing.CardHorizontalMargin,
                        vertical = Spacing.Large
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
                ) {
                    if (queue.isNotEmpty()) {
                        item {
                    UfiSectionHeader(
                                title = "队列（${queue.size}）",
                                trailing = {
                                    var showCancelAllConfirm by remember { mutableStateOf(false) }
                                    UfiButton(
                                        text = "全部取消",
                                        onClick = { showCancelAllConfirm = true },
                                        variant = UfiButtonVariant.Subtle,
                                        size = UfiButtonSize.Small
                                    )
                                    if (showCancelAllConfirm) {
                                        UfiConfirmDialog(
                                            title = "取消全部下载",
                                            text = "将取消队列中 ${queue.size} 个下载任务，已下载部分不会被删除。",
                                            confirmText = "全部取消",
                                            destructive = true,
                                            onDismiss = { showCancelAllConfirm = false },
                                            onConfirm = {
                                                showCancelAllConfirm = false
                                                MediaDownloadWorker.stop(context)
                                                MediaDownloadQueue.clearQueue()
                                            }
                                        )
                                    }
                                }
                            )
                        }
                        // 队列按 path 去重入队（见 MediaDownloadQueue.enqueue），path 可以当稳定 key
                        items(queue, key = { it.path }) { task ->
                            DownloadTaskRow(
                                task = task,
                                onRetry = {
                                    MediaDownloadQueue.retry(task.path)
                                    MediaDownloadWorker.kick(context)
                                },
                                onRemove = { MediaDownloadQueue.remove(task.path) }
                            )
                        }
                    }

                    if (history.isNotEmpty()) {
                        item { UfiSectionHeader(title = "历史（${history.size}）") }
                        /*
                         * 这里故意不给 key：同一个文件可以被下载多次，历史里因此会出现 path 相同的条目，
                         * 而 `path + at` 也只是"几乎"唯一 —— 懒列表遇到重复 key 是直接抛异常，
                         * 用位置索引最坏只是动画不跟手，不会崩。历史是只读的追加流，没有重排需求。
                         */
                        itemsIndexed(history.take(HISTORY_SHOWN)) { _, item ->
                            UfiListRowCard(
                                title = item.name,
                                subtitle = listOf(
                                    if (item.ok) "已完成" else "失败：${item.message}",
                                    FormatUtils.formatSize(item.size),
                                    FormatUtils.formatTimestamp(item.at)
                                ).filter { it.isNotBlank() }.joinToString(" · ")
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 队列里的一条：公共行卡（[UfiListRowCard]，媒体库列表用的就是它）。
 *
 * 副信息里带了**百分比**：公共行卡没有通栏进度槽，而"到哪儿了"是这一行唯一要传达的动态信息，
 * 换成文字比在卡外再补一根不属于任何卡的进度条诚实。
 */
@Composable
private fun DownloadTaskRow(
    task: MediaDownloadQueue.Task,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val failed = task.status == MediaDownloadQueue.STATUS_ERROR
    UfiListRowCard(
        title = task.name,
        subtitle = when {
            failed -> "失败：${task.error}"
            task.status == MediaDownloadQueue.STATUS_RUNNING -> {
                val total = if (task.total > 0) task.total else task.size
                val percent = (task.progress * 100).toInt().coerceIn(0, 100)
                "$percent% · ${FormatUtils.formatSize(task.received)} / " +
                    FormatUtils.formatSize(total)
            }
            else -> "排队中 · ${FormatUtils.formatSize(task.size)}"
        },
        trailing = {
            Row {
                if (failed) {
                    IconButton(onClick = onRetry) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "重试",
                            tint = palette.accent,
                            modifier = Modifier.size(QUEUE_ACTION_ICON)
                        )
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        if (failed) Icons.Default.Delete else Icons.Default.Close,
                        contentDescription = "移出队列",
                        tint = palette.textSecondary,
                        modifier = Modifier.size(QUEUE_ACTION_ICON)
                    )
                }
            }
        }
    )
}

/**
 * 历史最多显示这么多条。
 *
 * 存的比这更多（文件里按追加写），但一屏一屏往下翻旧记录没有实际用途 ——
 * 用户来这一页是问"刚才那几个下完了吗"。
 */
private const val HISTORY_SHOWN = 20

/** 队列行尾两颗图标的尺寸：比设置行的 24dp 小一档，行尾动作不该抢标题的注意力。 */
private val QUEUE_ACTION_ICON = 18.dp
