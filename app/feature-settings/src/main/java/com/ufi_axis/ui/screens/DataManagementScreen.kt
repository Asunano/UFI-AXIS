package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.MonitorStorageResponse
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 数据管理页（从监控中心迁出）：展示各历史表的占用情况，并提供
 * 「清理 7 天前」与「全部清空」操作。数据来自 monitorState.storageInfo。
 *
 * 2026-09-11 追加「编辑缓存」一节：文本编辑器打开超大文件时会整份下载到手机缓存
 * （见 `FileManagerModule.fetchToEditorCache`），那是本页唯一不由历史表构成、
 * 却同样会占手机空间的数据，所以清理入口放在这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementScreen(viewModel: MainViewModel, navController: NavHostController) {
    val monitorState by viewModel.monitorState.collectAsState()
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var showClean7d by remember { mutableStateOf(false) }
    var showClearAll by remember { mutableStateOf(false) }
    var showClearEditorCache by remember { mutableStateOf(false) }

    // 只订阅缓存占用两项：直接 collect 整个 FileManagerState 会被下载进度
    // （每 300ms 一次）带着整页重组。
    val cacheUsage by remember {
        viewModel.files.state.map { it.editorCacheBytes to it.editorCacheCount }.distinctUntilChanged()
    }.collectAsState(initial = 0L to 0)

    LaunchedEffect(Unit) {
        viewModel.dashboard.loadMonitorStorage()
        viewModel.files.refreshEditorCacheUsage()
    }

    // 清理结果提示
    LaunchedEffect(monitorState.cleanMessage) {
        monitorState.cleanMessage?.let { msg ->
            toastMessage = ToastMessage(msg, if (msg.contains("失败")) ToastType.ERROR else ToastType.SUCCESS)
            viewModel.dashboard.clearMonitorMessage()
        }
    }

    UfiScreenScaffold(
        title = "数据管理",
        navController = navController,
        showBack = true
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                monitorState.isLoading && monitorState.storageInfo == null -> {
                    // 2026-09-03：首屏首次加载从 36dp 转圈改为骨架屏。
                    // 这一支专指「还没有任何数据」（storageInfo == null）：整页空白 + 一个转圈
                    // 是信息量最低的组合。后续刷新走的是下面 else 分支里的 UfiLinearLoading
                    // （顶部细进度条 + 旧数据继续显示），所以这里换骨架屏不会影响局部刷新观感。
                    // cardCount = 1 / linesPerCard = 8 对齐真实版式：本页就是一张存储管理卡，
                    // 卡内是「标题 + 各历史表行 + 分隔线 + 总计 + 按钮行」，约 8 行。
                    UfiSkeletonGroup(
                        modifier = Modifier.padding(top = Spacing.Medium),
                        cardCount = 1,
                        linesPerCard = 8
                    )
                }
                monitorState.storageInfo == null -> {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        UfiErrorBanner(
                            message = monitorState.errorMessage ?: "暂无数据",
                            onRetry = { viewModel.dashboard.loadMonitorStorage() }
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = Spacing.Large)
                    ) {
                        item { UfiLinearLoading(isLoading = monitorState.isLoading) }
                        item {
                            StorageSection(
                                storage = monitorState.storageInfo!!,
                                onClean7d = { showClean7d = true },
                                onClearAll = { showClearAll = true }
                            )
                        }
                        item {
                            EditorCacheSection(
                                usedBytes = cacheUsage.first,
                                count = cacheUsage.second,
                                onClear = { showClearEditorCache = true }
                            )
                        }
                    }
                }
            }
            UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
        }
    }

    if (showClean7d) {
        UfiConfirmDialog(
            title = "清理历史数据",
            text = "将清理 7 天前的历史数据。如需清空所有请使用「全部清空」。",
            confirmText = "确认清理",
            onConfirm = {
                viewModel.dashboard.cleanHistory(days = 7)
                showClean7d = false
            },
            onDismiss = { showClean7d = false }
        )
    }

    if (showClearAll) {
        UfiConfirmDialog(
            title = "全部清空",
            text = "将永久删除全部历史记录（CPU / 内存 / 流量 / 信号 / 电池 / 告警 / 短信），此操作不可恢复。",
            confirmText = "全部清空",
            destructive = true,
            onConfirm = {
                viewModel.dashboard.cleanHistory(type = "all", days = 0)
                showClearAll = false
            },
            onDismiss = { showClearAll = false }
        )
    }

    if (showClearEditorCache) {
        UfiConfirmDialog(
            title = "清理编辑缓存",
            text = "将删除手机上所有「大文件编辑副本」。已经改动过但还没「另存到手机」的内容会一起丢失，" +
                "设备端的原文件不受影响。",
            confirmText = "清理",
            destructive = true,
            onConfirm = {
                val (bytes, count) = viewModel.files.clearEditorCache()
                toastMessage = ToastMessage(
                    "已清理 $count 个副本，释放 ${FormatUtils.formatSize(bytes)}",
                    ToastType.SUCCESS
                )
                showClearEditorCache = false
            },
            onDismiss = { showClearEditorCache = false }
        )
    }
}

@Composable
private fun StorageSection(storage: MonitorStorageResponse, onClean7d: () -> Unit, onClearAll: () -> Unit) {
    val palette = LocalResolvedPalette.current
    // 2026-08-30：容器改用公共 UfiSettingsRowCard（原来手搓 rowModifier 四链）。
    // 顺带修掉一个真实布局 bug：原来外层是 Box，而里面是「标题 + N 行表 + 分隔线 + 总计 + 按钮」
    // 这一串纵向内容 —— Box 会把它们全部叠在同一位置。RowCard 内部是 Column，纵向排开才正确。
    UfiSettingsRowCard(contentPadding = PaddingValues(16.dp)) {
        UfiSectionHeader(title = "存储管理")
        val defaultBarColor = palette.accent
        storage.tables.forEach { table ->
            val ratio = remember(table.size_kb, storage.total_kb) { if (storage.total_kb > 0) (table.size_kb / storage.total_kb).toFloat().coerceIn(0f, 1f) else 0f }
            val barColor = remember(ratio, defaultBarColor) {
                when {
                    ratio > 0.5f -> StatusRamp.bad(palette)
                    ratio > 0.3f -> StatusRamp.warn(palette)
                    else -> defaultBarColor
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.InnerPadding, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tableDisplayName(table.name), modifier = Modifier.weight(1f), style = UfiTextStyles.body)
                Text("${table.count} 条", style = UfiTextStyles.caption, color = palette.textSecondary)
                Spacer(Modifier.width(8.dp))
                UfiCompactProgressBar(progress = ratio, color = barColor, modifier = Modifier.width(60.dp))
                Spacer(Modifier.width(8.dp))
                Text("${"%.1f".format(table.size_kb)} KB", style = UfiTextStyles.caption, color = palette.textSecondary)
            }
        }
        UfiDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.InnerPadding), verticalAlignment = Alignment.CenterVertically) {
            Text("总计", modifier = Modifier.weight(1f), style = UfiTextStyles.body.copy(fontWeight = UfiWeight.Emphasis))
            Text(storage.total_display, style = UfiTextStyles.body.copy(fontWeight = UfiWeight.Emphasis), color = palette.accent)
        }
        Spacer(Modifier.height(Spacing.Medium))
        UfiButtonRow {
            UfiButton(size = UfiButtonSize.Small, text = "清理 7 天前", onClick = onClean7d)
            UfiButton(size = UfiButtonSize.Small, text = "全部清空", onClick = onClearAll)
        }
    }
}

/**
 * 编辑缓存一节。
 *
 * 与上面的历史表卡片并列但**不同于**它们：那些是 core 设备端的数据量，
 * 这一项是**手机本地**的空间占用。所以副标题把话说清楚，避免用户以为清的是设备端文件。
 */
@Composable
private fun EditorCacheSection(
    usedBytes: Long,
    count: Int,
    onClear: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    UfiSettingsRowCard(contentPadding = PaddingValues(16.dp)) {
        UfiSectionHeader(title = "编辑缓存（手机）")
        Text(
            text = "超过服务端单次可读上限的文本文件，会整份下载到手机缓存后再用编辑器打开。" +
                "缓存按最后修改时间自动淘汰，总量上限 64MB；设备端的原文件不受影响。",
            style = UfiTextStyles.caption,
            color = palette.textSecondary
        )
        Spacer(Modifier.height(Spacing.Medium))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.InnerPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("当前占用", modifier = Modifier.weight(1f), style = UfiTextStyles.body)
            Text(
                text = if (count == 0) "无" else "${FormatUtils.formatSize(usedBytes)} · $count 个文件",
                style = UfiTextStyles.body.copy(fontWeight = UfiWeight.Emphasis),
                color = if (count == 0) palette.textSecondary else palette.accent
            )
        }
        Spacer(Modifier.height(Spacing.Medium))
        UfiButtonRow {
            UfiButton(
                size = UfiButtonSize.Small,
                text = "清理编辑缓存",
                enabled = count > 0,
                onClick = onClear
            )
        }
    }
}

private fun tableDisplayName(name: String): String = when (name) {
    "cpu_history" -> "CPU"; "memory_history" -> "内存"; "traffic_records" -> "流量"
    "signal_history" -> "信号"; "battery_history" -> "电池"; "alert_records" -> "告警"
    "sms_records" -> "短信"; else -> name
}
