package com.ufi_axis.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.DownloadConfigItem
import com.ufi_axis.viewmodel.state.DownloadState
import com.ufi_axis.viewmodel.state.DownloadTaskItem
import com.ufi_axis.viewmodel.state.DuplicateInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.downloadState.collectAsState()
    var showNewDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    // Smart polling: 2s when active downloads, 5s otherwise
    LaunchedEffect(Unit) {
        viewModel.downloads.loadDownloads()
        while (isActive) {
            val hasActive = state.tasks.any { it.status in listOf("downloading", "pending", "meta") }
            delay(if (hasActive) 2000L else 5000L)
            if (state.tasks.any { it.status in listOf("downloading", "pending", "meta") }) {
                viewModel.downloads.loadDownloads()
            }
        }
    }

    UfiScreenScaffold(
        title = "下载管理",
        navController = navController,
        showBack = true
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = { viewModel.downloads.loadDownloads() },
                modifier = Modifier.padding(padding).fillMaxSize()
            ) {
                Column(Modifier.fillMaxSize()) {
                    PrimaryTabRow(
                        selectedTabIndex = selectedTab,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("任务") },
                            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("设置") },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }

                    state.errorMessage?.let { error ->
                        UfiErrorBanner(message = error)
                    }

                    when (selectedTab) {
                        0 -> DownloadTasksTab(state, viewModel, showNewDialog = { showNewDialog = true }, toastMessage = { toastMessage = it })
                        1 -> DownloadSettingsTab(state, viewModel)
                    }
                }
            }
            UfiToastHost(
                toastMessage = toastMessage,
                onDismiss = { toastMessage = null }
            )
        }
    }

    if (showNewDialog) {
        NewDownloadDialog(
            config = state.config,
            onDismiss = { showNewDialog = false },
            onConfirm = { url, fileName, savePath, speedLimit, connections ->
                viewModel.downloads.createDownload(url, fileName, savePath, speedLimit, connections)
                showNewDialog = false
            }
        )
    }

    state.duplicateInfo?.let { info ->
        UfiAlertDialog(
            title = "重复下载",
            text = "已有相同下载记录「${info.existingTask.fileName.ifBlank { info.existingTask.url.substringAfterLast("/") }}」（${info.existingTask.status}，${FormatUtils.formatBytes(info.existingTask.totalSize)}），是否将文件另存为「${info.suggestedFileName}」继续下载？",
            confirmText = "另存为",
            onConfirm = {
                viewModel.downloads.createDownloadForce(
                    url = info.newUrl, fileName = info.suggestedFileName,
                    savePath = info.savePath, speedLimit = info.speedLimit, connections = info.connections
                )
            },
            dismissText = "取消",
            onDismissAction = { viewModel.downloads.dismissDuplicate() },
            onDismiss = { viewModel.downloads.dismissDuplicate() }
        )
    }
}

// ==================== Tasks Tab ====================

@Composable
private fun DownloadTasksTab(
    state: DownloadState,
    viewModel: MainViewModel,
    showNewDialog: () -> Unit,
    toastMessage: (ToastMessage) -> Unit = {}
) {
    val activeTasks = state.tasks.filter {
        it.status == "downloading" || it.status == "pending" || it.status == "paused" || it.status == "error" || it.status == "meta"
    }
    val failedTasks = state.tasks.filter { it.status == "error" }
    val completedTasks = state.tasks.filter { it.status == "completed" }
    val hasCompleted = completedTasks.isNotEmpty()
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // Action bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.PagePadding, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UfiPrimaryButton(
                    text = "新建下载",
                    onClick = showNewDialog,
                    modifier = Modifier.weight(1f)
                )
                if (failedTasks.isNotEmpty()) {
                    UfiSecondaryButton(
                        text = "重试失败",
                        onClick = {
                            failedTasks.forEach { viewModel.downloads.retryDownload(it.id) }
                            toastMessage(ToastMessage("已重试 ${failedTasks.size} 个失败任务", ToastType.INFO))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (hasCompleted) {
                    UfiSecondaryButton(
                        text = "清除已完成",
                        onClick = { showBatchDeleteConfirm = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 批量删除确认
        if (showBatchDeleteConfirm) {
            UfiConfirmDialog(
                title = "清除已完成",
                text = "确定删除所有 ${completedTasks.size} 个已完成任务${if (completedTasks.any { it.savePath.isNotBlank() }) "及下载文件？\n如需保留文件，请在单个任务中删除" else "？"}",
                confirmText = "全部删除",
                onConfirm = {
                    showBatchDeleteConfirm = false
                    val count = completedTasks.size
                    viewModel.downloads.clearCompletedDownloads()
                    toastMessage(ToastMessage("已清除 $count 个任务", ToastType.SUCCESS))
                },
                onDismiss = { showBatchDeleteConfirm = false },
                destructive = true
            )
        }

        // aria2 status bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.PagePadding, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // aria2 status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (state.aria2Running)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (state.aria2Running) "aria2 运行中" else "aria2 未启动",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.aria2Version != null) {
                        Text(
                            " v${state.aria2Version}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // Throttle badge
                if (state.throttleState != "normal") {
                    Surface(
                        shape = UfiCardDefaults.smallShape,
                        color = when (state.throttleState) {
                            "stopped", "critical" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                            "warning" -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        }
                    ) {
                        Text(
                            when (state.throttleState) {
                                "stopped" -> "■ 已暂停"
                                "critical" -> "⚠ 节流"
                                "warning" -> "⚡ 限速"
                                else -> state.throttleState
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = when (state.throttleState) {
                                "stopped", "critical" -> MaterialTheme.colorScheme.error
                                "warning" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                // Summary stats
                Text(
                    "${state.activeCount} 活跃 / ${state.tasks.size} 总计",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.tasks.isEmpty() && !state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "暂无下载任务",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "点击上方按钮添加新的下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.PagePadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (activeTasks.isNotEmpty()) {
                    item {
                        Text(
                            "进行中 (${activeTasks.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(activeTasks, key = { it.id }) { task ->
                        DownloadTaskCard(task, viewModel, isCompleted = false)
                    }
                }

                if (completedTasks.isNotEmpty()) {
                    item {
                        if (activeTasks.isNotEmpty()) Spacer(Modifier.height(8.dp))
                        Text(
                            "已完成 (${completedTasks.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(completedTasks, key = { it.id }) { task ->
                        DownloadTaskCard(task, viewModel, isCompleted = true)
                    }
                }

                item { Spacer(Modifier.height(Spacing.Large)) }
            }
        }
    }
}

// ==================== Task Card ====================

@Composable
private fun DownloadTaskCard(
    task: DownloadTaskItem,
    viewModel: MainViewModel,
    isCompleted: Boolean
) {
    val statusBadgeType = when (task.status) {
        "downloading" -> UfiBadgeType.INFO
        "meta" -> UfiBadgeType.WARNING
        "paused" -> UfiBadgeType.DEFAULT
        "pending" -> UfiBadgeType.DEFAULT
        "error" -> UfiBadgeType.ERROR
        "completed" -> UfiBadgeType.SUCCESS
        else -> UfiBadgeType.DEFAULT
    }
    val statusLabel = when (task.status) {
        "downloading" -> "下载中"
        "meta" -> "获取种子"
        "paused" -> "已暂停"
        "pending" -> "等待中"
        "error" -> "失败"
        "completed" -> "已完成"
        else -> task.status
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteWithFile by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = UfiCardDefaults.legacyShape
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UfiBadge(text = task.protocol.uppercase(), type = UfiBadgeType.DEFAULT)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        task.fileName.ifBlank { task.url.substringAfterLast("/").take(40) },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(6.dp))
                UfiBadge(text = statusLabel, type = statusBadgeType)
            }

            if (task.status in listOf("downloading", "paused", "pending", "meta")) {
                Spacer(Modifier.height(8.dp))
                if (task.status == "meta") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Color(0xFFE65100))
                        Text("获取种子信息中...", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE65100))
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (task.progress >= 0f && task.totalSize > 0) {
                    UfiCompactProgressBar(progress = task.progress)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp).clip(UfiCardDefaults.microShape))
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val sizeText = buildString {
                    append(FormatUtils.formatBytes(task.downloadedBytes))
                    if (task.totalSize > 0) append(" / ${FormatUtils.formatBytes(task.totalSize)}")
                    if (task.progress >= 0f && task.totalSize > 0) append("  (${(task.progress * 100).toInt()}%)")
                }
                Text(sizeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.speed > 0 && task.status == "downloading") {
                        Text("${FormatUtils.formatBytes(task.speed)}/s ↓", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (task.uploadSpeed > 0) {
                        Text("${FormatUtils.formatBytes(task.uploadSpeed)}/s ↑", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium, color = Color(0xFFE65100))
                    }
                }
            }

            if (task.status == "downloading") {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (task.connections > 0) Text("连接: ${task.connections}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    if (task.seeders > 0) Text("做种: ${task.seeders}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }

            if (task.status == "error" && task.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(task.error!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                when (task.status) {
                    "downloading", "meta" -> SmallIconButton(Icons.Default.Pause, "暂停") { viewModel.downloads.pauseDownload(task.id) }
                    "paused", "error" -> SmallIconButton(Icons.Default.PlayArrow, "继续") { viewModel.downloads.resumeDownload(task.id) }
                }
                if (task.status == "error") {
                    SmallIconButton(Icons.Default.Refresh, "重试", tint = Color(0xFFFF9800)) { viewModel.downloads.retryDownload(task.id) }
                }
                SmallIconButton(Icons.Default.ContentCopy, "复制链接", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) { viewModel.downloads.copyLinkToClipboard(task.url) }
                SmallIconButton(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error) { showDeleteConfirm = true }
                if (isCompleted) {
                    SmallIconButton(Icons.Default.DeleteForever, "删除文件", tint = MaterialTheme.colorScheme.error) { deleteWithFile = true; showDeleteConfirm = true }
                }
                Spacer(Modifier.weight(1f))
                Text(task.savePath.substringBeforeLast("/"), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
            }

            if (showDeleteConfirm) {
                UfiConfirmDialog(
                    title = if (deleteWithFile) "删除任务及文件" else "删除任务",
                    text = if (deleteWithFile) "确定要删除「${task.fileName.ifBlank { task.url.take(30) }}」及其下载文件吗？此操作不可撤销。" else "确定要删除下载任务「${task.fileName.ifBlank { task.url.take(30) }}」吗？",
                    confirmText = "删除",
                    onConfirm = { viewModel.downloads.deleteDownload(task.id, deleteFile = deleteWithFile); showDeleteConfirm = false; deleteWithFile = false },
                    onDismiss = { showDeleteConfirm = false; deleteWithFile = false },
                    destructive = true
                )
            }
        }
    }
}

// ==================== Settings Tab ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSettingsTab(
    state: DownloadState,
    viewModel: MainViewModel
) {
    var config by remember(state.config) { mutableStateOf(state.config) }
    var hasChanges by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var showTrackerEditDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.PagePadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.GroupSpacing)
    ) {
        // ── 1. aria2 Engine Status ──
        item {
            UfiSettingsGroup {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (state.aria2Running) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("aria2 引擎", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            if (state.aria2Running) "运行中 · v${state.aria2Version ?: "?"}" else "未启动",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.aria2Running) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = if (state.aria2Running) Color(0xFF2E7D32) else Color(0xFF9E9E9E),
                        modifier = Modifier.size(10.dp)
                    ) {}
                }
            }
        }

        // ── 2. Normal Config ──
        item {
            UfiCollapsibleGroup(
                title = "普通配置",
                subtitle = "并发 ${config.maxConcurrent} · 连接 ${config.maxConnectionsPerServer} · 分片 ${config.splitCount}",
                initialExpanded = true
            ) {
                // Subsection: Concurrency & Connections
                UfiDivider()
                Text(
                    "并发与连接",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
                SettingSliderRow(
                    label = "最大并发下载数",
                    value = config.maxConcurrent,
                    range = 1..10,
                    onValueChange = { config = config.copy(maxConcurrent = it); hasChanges = true }
                )
                UfiDivider()
                SettingSliderRow(
                    label = "每服务器最大连接数",
                    value = config.maxConnectionsPerServer,
                    range = 1..16,
                    onValueChange = { config = config.copy(maxConnectionsPerServer = it); hasChanges = true }
                )
                UfiDivider()
                SettingSliderRow(
                    label = "文件分片数",
                    value = config.splitCount,
                    range = 1..16,
                    onValueChange = { config = config.copy(splitCount = it); hasChanges = true }
                )
                UfiDivider()
                SettingSliderRow(
                    label = "最小分片大小 (MB)",
                    value = config.minSplitSizeMb,
                    range = 1..20,
                    onValueChange = { config = config.copy(minSplitSizeMb = it); hasChanges = true }
                )

                // Subsection: Speed Limits
                UfiDivider()
                Text(
                    "速度限制",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
                SpeedLimitRow(
                    label = "全局速度限制",
                    valueBytes = config.globalSpeedLimit,
                    onValueChange = { config = config.copy(globalSpeedLimit = it); hasChanges = true }
                )
                UfiDivider()
                SpeedLimitRow(
                    label = "单任务速度限制",
                    valueBytes = config.perTaskSpeedLimit,
                    onValueChange = { config = config.copy(perTaskSpeedLimit = it); hasChanges = true }
                )
                UfiDivider()
                SpeedLimitRow(
                    label = "全局上传限制",
                    valueBytes = config.maxOverallUploadLimit,
                    onValueChange = { config = config.copy(maxOverallUploadLimit = it); hasChanges = true }
                )

                // Subsection: File Allocation
                UfiDivider()
                Spacer(Modifier.height(8.dp))
                ChipSelectorRow(
                    label = "文件分配方式",
                    options = listOf(
                        "none" to "none",
                        "prealloc" to "prealloc",
                        "trunc" to "trunc",
                        "falloc" to "falloc"
                    ),
                    selectedValue = config.fileAllocation,
                    onSelect = { config = config.copy(fileAllocation = it); hasChanges = true }
                )
            }
        }

        // ── 3. Save Path ──
        item {
            UfiSettingsGroup {
                Text(
                    "保存路径",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                OutlinedTextField(
                    value = config.saveDir,
                    onValueChange = { config = config.copy(saveDir = it); hasChanges = true },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("默认保存路径") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Folder, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "快捷路径",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val presets = listOf(
                        "/storage/emulated/0/Downloads/UFI",
                        "/storage/emulated/0/Downloads",
                        "/storage/emulated/0/Movies",
                        "/storage/emulated/0/Documents"
                    )
                    presets.forEach { path ->
                        FilterChip(
                            selected = config.saveDir == path,
                            onClick = { config = config.copy(saveDir = path); hasChanges = true },
                            label = {
                                Text(
                                    path.substringAfterLast("/"),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
                // Path validation
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        if (config.saveDir.startsWith("/storage") || config.saveDir.startsWith("/"))
                            Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (config.saveDir.startsWith("/storage") || config.saveDir.startsWith("/"))
                            Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                    )
                    Text(
                        if (config.saveDir.startsWith("/storage") || config.saveDir.startsWith("/"))
                            "路径格式有效" else "路径可能无效",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            viewModel.downloads.validatePath(config.saveDir) { result ->
                                // Validation result handled via state update
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("验证路径", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // ── 4. Advanced Config ──
        item {
            UfiCollapsibleGroup(
                title = "高级配置",
                subtitle = "BT / 网络 / 日志等进阶选项",
                initialExpanded = false
            ) {
                // Subsection: BT Settings
                UfiDivider()
                Text(
                    "BT 设置",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
                // btSeedRatio text field
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.Small),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("做种分享比率", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "达到此比率后停止做种",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    var ratioText by remember(config.btSeedRatio) { mutableStateOf(String.format("%.1f", config.btSeedRatio)) }
                    OutlinedTextField(
                        value = ratioText,
                        onValueChange = {
                            ratioText = it
                            it.toFloatOrNull()?.let { v ->
                                if (v in 0f..100f) {
                                    config = config.copy(btSeedRatio = v)
                                    hasChanges = true
                                }
                            }
                        },
                        modifier = Modifier.width(80.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
                UfiDivider()
                SettingSliderRow(
                    label = "BT 最大节点数",
                    value = config.btMaxPeers,
                    range = 10..200,
                    onValueChange = { config = config.copy(btMaxPeers = it); hasChanges = true }
                )
                UfiDivider()
                SettingSwitchRow(
                    label = "启用 DHT",
                    description = "分布式哈希表，用于 BT 节点发现",
                    checked = config.btEnableDht,
                    onCheckedChange = { config = config.copy(btEnableDht = it); hasChanges = true }
                )
                UfiDivider()
                SettingSwitchRow(
                    label = "启用 LPD",
                    description = "本地节点发现，局域网内 BT 节点搜索",
                    checked = config.btEnableLpd,
                    onCheckedChange = { config = config.copy(btEnableLpd = it); hasChanges = true }
                )
                UfiDivider()
                // DHT listen port text field
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.Small),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("DHT 监听端口", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "DHT 网络监听端口范围",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    var dhtPortText by remember(config.dhtListenPort) { mutableStateOf(config.dhtListenPort) }
                    OutlinedTextField(
                        value = dhtPortText,
                        onValueChange = {
                            dhtPortText = it
                            config = config.copy(dhtListenPort = it)
                            hasChanges = true
                        },
                        modifier = Modifier.width(120.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }
                UfiDivider()
                SettingSliderRow(
                    label = "BT Tracker 连接超时 (秒)",
                    value = config.btTrackerConnectTimeout,
                    range = 10..120,
                    onValueChange = { config = config.copy(btTrackerConnectTimeout = it); hasChanges = true }
                )
                UfiDivider()
                SettingSliderRow(
                    label = "BT 最大打开文件数",
                    value = config.btMaxOpenFiles,
                    range = 10..500,
                    onValueChange = { config = config.copy(btMaxOpenFiles = it); hasChanges = true }
                )

                // Subsection: Network & Retry
                UfiDivider()
                Text(
                    "网络与重试",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
                SettingSwitchRow(
                    label = "禁用 IPv6",
                    description = "仅使用 IPv4 连接",
                    checked = config.disableIpv6,
                    onCheckedChange = { config = config.copy(disableIpv6 = it); hasChanges = true }
                )
                UfiDivider()
                SettingSwitchRow(
                    label = "校验证书",
                    description = "HTTPS 连接时验证服务器证书",
                    checked = config.checkCertificate,
                    onCheckedChange = { config = config.copy(checkCertificate = it); hasChanges = true }
                )
                UfiDivider()
                SettingSliderRow(
                    label = "最大重试次数",
                    value = config.maxTries,
                    range = 1..20,
                    onValueChange = { config = config.copy(maxTries = it); hasChanges = true }
                )
                UfiDivider()
                SettingSliderRow(
                    label = "重试等待 (秒)",
                    value = config.retryWait,
                    range = 0..60,
                    onValueChange = { config = config.copy(retryWait = it); hasChanges = true }
                )
                UfiDivider()
                // maxResumeTries with special "0=unlimited" label
                Column(modifier = Modifier.padding(vertical = Spacing.Small)) {
                    var resumeSlider by remember(config.maxResumeTries) { mutableFloatStateOf(config.maxResumeTries.toFloat()) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("断点续传重试", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (resumeSlider.toInt() == 0) "无限" else resumeSlider.toInt().toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "0 = 无限重试",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = resumeSlider,
                        onValueChange = { resumeSlider = it },
                        onValueChangeFinished = {
                            config = config.copy(maxResumeTries = resumeSlider.toInt())
                            hasChanges = true
                        },
                        valueRange = 0f..100f,
                        steps = 99,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                UfiDivider()
                SpeedLimitRow(
                    label = "最低速度限制",
                    valueBytes = config.lowestSpeedLimit,
                    onValueChange = { config = config.copy(lowestSpeedLimit = it); hasChanges = true }
                )

                // Subsection: Log Level
                UfiDivider()
                Spacer(Modifier.height(8.dp))
                ChipSelectorRow(
                    label = "日志级别",
                    options = listOf(
                        "debug" to "debug",
                        "info" to "info",
                        "notice" to "notice",
                        "warn" to "warn",
                        "error" to "error"
                    ),
                    selectedValue = config.logLevel,
                    onSelect = { config = config.copy(logLevel = it); hasChanges = true }
                )
            }
        }

        // ── 5. BT Tracker Management ──
        item {
            UfiCollapsibleGroup(
                title = "BT Tracker 管理",
                subtitle = "${state.trackerCount} 条 · ${state.trackerStatus}",
                initialExpanded = false
            ) {
                UfiDivider()
                SettingSwitchRow(
                    label = "自动更新 Tracker",
                    description = "定期从远程源拉取最新 Tracker 列表",
                    checked = config.btTrackerAutoUpdate,
                    onCheckedChange = { config = config.copy(btTrackerAutoUpdate = it); hasChanges = true }
                )
                UfiDivider()
                // Update interval chips
                ChipSelectorRow(
                    label = "更新间隔",
                    options = listOf(
                        "12" to "12 小时",
                        "24" to "24 小时",
                        "48" to "48 小时"
                    ),
                    selectedValue = config.btTrackerUpdateIntervalHours.toString(),
                    onSelect = {
                        config = config.copy(btTrackerUpdateIntervalHours = it.toInt())
                        hasChanges = true
                    }
                )
                UfiDivider()
                // Source URL
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = config.btTrackerSourceUrl,
                    onValueChange = { config = config.copy(btTrackerSourceUrl = it); hasChanges = true },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tracker 源地址") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                // Custom tracker list
                OutlinedTextField(
                    value = config.btTrackerCustomList,
                    onValueChange = { config = config.copy(btTrackerCustomList = it); hasChanges = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    label = { Text("自定义 Tracker (每行一个)") },
                    placeholder = { Text("udp://tracker.example.com:80\nhttp://tracker2.example.com/announce") },
                    textStyle = MaterialTheme.typography.bodySmall,
                    maxLines = 6
                )
                // Status display
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = UfiCardDefaults.inputShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("当前状态", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            if (state.trackerRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tracker 数量: ${state.trackerCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.trackerLastUpdated > 0L) {
                            Text(
                                "上次更新: ${dateFormat.format(Date(state.trackerLastUpdated))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "状态: ${state.trackerStatus}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Refresh button
                Spacer(Modifier.height(8.dp))
                UfiSecondaryButton(
                    text = if (state.trackerRefreshing) "刷新中…" else "立即刷新",
                    onClick = { viewModel.downloads.refreshTrackers() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                UfiSecondaryButton(
                    text = "编辑 Tracker 列表",
                    onClick = {
                        viewModel.downloads.loadTrackers()
                        showTrackerEditDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ── 6. Smart Throttle ──
        item {
            UfiCollapsibleGroup(
                title = "智能性能控制",
                subtitle = buildString {
                    when (state.throttleState) {
                        "stopped" -> append("■ 已暂停")
                        "critical" -> append("⚠ 节流中")
                        "warning" -> append("⚡ 限速中")
                        else -> append("正常")
                    }
                    append(" · ${state.throttleTemp}°C · CPU ${state.throttleCpu}%")
                    if (state.throttleBattery >= 0) {
                        append(" · ")
                        if (state.throttleCharging) append("⚡")
                        append("${state.throttleBattery}%")
                    }
                    append(" · MEM ${state.throttleMemory}%")
                },
                initialExpanded = false
            ) {
                UfiDivider()
                SettingSwitchRow(
                    label = "启用智能限速",
                    description = "根据温度、CPU、电量和内存动态调整下载性能",
                    checked = config.smartThrottle,
                    onCheckedChange = { config = config.copy(smartThrottle = it); hasChanges = true }
                )
                UfiDivider()
                SettingSwitchRow(
                    label = "仅充电时下载",
                    description = "设备未充电时自动暂停所有下载，充电后自动恢复",
                    checked = config.onlyDownloadWhenCharging,
                    onCheckedChange = { config = config.copy(onlyDownloadWhenCharging = it); hasChanges = true }
                )

                if (config.smartThrottle) {
                    // Temperature thresholds
                    UfiDivider()
                    Text(
                        "温度阈值",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                    SettingSliderRow(
                        label = "警告温度 (°C)",
                        value = config.throttleTempWarn.toInt(),
                        range = 40..80,
                        onValueChange = { config = config.copy(throttleTempWarn = it.toFloat()); hasChanges = true }
                    )
                    UfiDivider()
                    SettingSliderRow(
                        label = "临界温度 (°C)",
                        value = config.throttleTempCritical.toInt(),
                        range = 50..90,
                        onValueChange = { config = config.copy(throttleTempCritical = it.toFloat()); hasChanges = true }
                    )

                    // CPU thresholds
                    UfiDivider()
                    Text(
                        "CPU 阈值",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                    SettingSliderRow(
                        label = "CPU 警告 (%)",
                        value = config.throttleCpuWarn,
                        range = 30..95,
                        onValueChange = { config = config.copy(throttleCpuWarn = it); hasChanges = true }
                    )
                    UfiDivider()
                    SettingSliderRow(
                        label = "CPU 临界 (%)",
                        value = config.throttleCpuCritical,
                        range = 40..100,
                        onValueChange = { config = config.copy(throttleCpuCritical = it); hasChanges = true }
                    )

                    // Battery thresholds
                    UfiDivider()
                    Text(
                        "电量阈值 (充电时不触发)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                    SettingSliderRow(
                        label = "电量警告 (≤ %)",
                        value = config.throttleBatteryWarn,
                        range = 10..50,
                        onValueChange = { config = config.copy(throttleBatteryWarn = it); hasChanges = true }
                    )
                    UfiDivider()
                    SettingSliderRow(
                        label = "电量临界 (≤ %)",
                        value = config.throttleBatteryCritical,
                        range = 5..30,
                        onValueChange = { config = config.copy(throttleBatteryCritical = it); hasChanges = true }
                    )

                    // Memory thresholds
                    UfiDivider()
                    Text(
                        "内存阈值",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                    SettingSliderRow(
                        label = "内存警告 (%)",
                        value = config.throttleMemoryWarn,
                        range = 50..95,
                        onValueChange = { config = config.copy(throttleMemoryWarn = it); hasChanges = true }
                    )
                    UfiDivider()
                    SettingSliderRow(
                        label = "内存临界 (%)",
                        value = config.throttleMemoryCritical,
                        range = 60..98,
                        onValueChange = { config = config.copy(throttleMemoryCritical = it); hasChanges = true }
                    )

                    // ── 限速级别说明 ──
                    UfiDivider()
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = UfiCardDefaults.inputShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "限速级别说明",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            // Warning level
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(8.dp)
                                ) {}
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    buildString {
                                        append("警告 — 并发数减半，速度降至 50%")
                                        if (config.globalSpeedLimit > 0) {
                                            append(" (${config.globalSpeedLimit / 2} KB/s)")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            // Critical level
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(8.dp)
                                ) {}
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    buildString {
                                        append("严重 — 并发降至 1，速度降至 20%")
                                        if (config.globalSpeedLimit > 0) {
                                            append(" (${config.globalSpeedLimit / 5} KB/s)")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            // Stopped level
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = UfiCardDefaults.microShape,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(8.dp)
                                ) {}
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "极端 — 暂停所有下载，等待指标恢复正常后自动恢复",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "充电时不触发电量限速。系统会取所有指标中的最高级别执行，" +
                                "取最高温度区域读数。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // Current throttle status display
                    UfiDivider()
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = UfiCardDefaults.inputShape,
                        color = when (state.throttleState) {
                            "stopped" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                            "critical" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            "warning" -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        }
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "当前状态",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "限速级别: ${
                                    when (state.throttleState) {
                                        "stopped" -> "已暂停 (等待条件恢复)"
                                        "critical" -> "严重 (降至 20%)"
                                        "warning" -> "警告 (降至 50%)"
                                        "disabled" -> "已禁用"
                                        else -> "正常"
                                    }
                                }",
                                style = MaterialTheme.typography.labelSmall,
                                color = when (state.throttleState) {
                                    "stopped", "critical" -> MaterialTheme.colorScheme.error
                                    "warning" -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(Modifier.height(2.dp))
                            // Metrics grid: 2 columns
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    ThrottleMetricRow(
                                        "温度",
                                        "${state.throttleTemp}°C",
                                        state.throttleTemp >= config.throttleTempCritical,
                                        state.throttleTemp >= config.throttleTempWarn
                                    )
                                    ThrottleMetricRow(
                                        "CPU",
                                        "${state.throttleCpu}%",
                                        state.throttleCpu >= config.throttleCpuCritical,
                                        state.throttleCpu >= config.throttleCpuWarn
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    if (state.throttleBattery >= 0) {
                                        val batteryLabel = buildString {
                                            if (state.throttleCharging) append("⚡ ")
                                            append("${state.throttleBattery}%")
                                        }
                                        ThrottleMetricRow(
                                            "电量",
                                            batteryLabel,
                                            !state.throttleCharging && state.throttleBattery in 1..config.throttleBatteryCritical,
                                            !state.throttleCharging && state.throttleBattery in 1..config.throttleBatteryWarn
                                        )
                                    } else {
                                        ThrottleMetricRow("电量", "未知", false, false)
                                    }
                                    ThrottleMetricRow(
                                        "内存",
                                        "${state.throttleMemory}%",
                                        state.throttleMemory >= config.throttleMemoryCritical,
                                        state.throttleMemory >= config.throttleMemoryWarn
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "策略: 警告→50%限速，严重→80%限速，极端→暂停下载；充电时跳过电量限速",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        // ── 7. Performance Tips ──
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = UfiCardDefaults.legacyShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(Spacing.InnerPadding)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("性能提示", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "aria2 支持 HTTP/HTTPS、FTP、磁力链接和 BT 种子等多种协议，具备多连接分片下载和 P2P 加速能力。" +
                        "并发数和连接数过高会增加设备 CPU 和内存消耗，建议根据设备性能适当调整。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── 8. Save Button ──
        if (hasChanges) {
            item {
                UfiPrimaryButton(
                    text = "保存配置",
                    onClick = {
                        viewModel.downloads.updateDownloadConfig(config)
                        hasChanges = false
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "部分配置需重启 aria2 后生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(Spacing.Large))
            }
        } else {
            item { Spacer(Modifier.height(Spacing.Large)) }
        }
    }

    // Tracker Editing Dialog
    if (showTrackerEditDialog) {
        TrackerEditDialog(
            cachedTrackers = state.cachedTrackerList,
            isLoading = state.trackerListLoading,
            onDismiss = { showTrackerEditDialog = false },
            onSave = { trackers ->
                viewModel.downloads.saveTrackerList(trackers)
                showTrackerEditDialog = false
            }
        )
    }
}

// ==================== Setting Helpers ====================

@Composable
private fun SettingSliderRow(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(modifier = Modifier.padding(vertical = Spacing.Small)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                sliderValue.toInt().toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChange(sliderValue.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SpeedLimitRow(
    label: String,
    valueBytes: Long,
    onValueChange: (Long) -> Unit
) {
    // Display in MB/s, 0 = unlimited
    val valueMb = if (valueBytes > 0) (valueBytes / (1024 * 1024)).toInt() else 0
    var textValue by remember(valueMb) { mutableStateOf(if (valueMb > 0) valueMb.toString() else "") }
    val isUnlimited = valueBytes <= 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (isUnlimited) "无限制" else "${FormatUtils.formatBytes(valueBytes)}/s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedTextField(
            value = textValue,
            onValueChange = { newText ->
                textValue = newText
                val mb = newText.toIntOrNull()
                if (mb != null && mb >= 0) {
                    onValueChange(if (mb == 0) 0L else mb.toLong() * 1024 * 1024)
                } else if (newText.isEmpty()) {
                    onValueChange(0L)
                }
            },
            modifier = Modifier.width(100.dp),
            singleLine = true,
            placeholder = { Text("MB/s", style = MaterialTheme.typography.labelSmall) },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = {
                if (!isUnlimited) {
                    Text("MB/s", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp))
                }
            }
        )
    }
}

// ==================== Switch Row ====================

@Composable
private fun SettingSwitchRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ==================== Chip Selector Row ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipSelectorRow(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { (value, displayLabel) ->
                FilterChip(
                    selected = selectedValue == value,
                    onClick = { onSelect(value) },
                    label = { Text(displayLabel, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

// ==================== Small Icon Button ====================

@Composable
private fun SmallIconButton(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        modifier = Modifier.height(28.dp)
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(16.dp), tint = tint)
        Spacer(Modifier.width(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

// ==================== Throttle Metric Row ====================

@Composable
private fun ThrottleMetricRow(
    label: String,
    value: String,
    isCritical: Boolean,
    isWarning: Boolean
) {
    val color = when {
        isCritical -> MaterialTheme.colorScheme.error
        isWarning -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = color,
            modifier = Modifier.size(6.dp)
        ) {}
        Spacer(Modifier.width(4.dp))
        Text(
            "$label: $value",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

// ==================== New Download Dialog ====================

@Composable
private fun NewDownloadDialog(
    config: DownloadConfigItem,
    onDismiss: () -> Unit,
    onConfirm: (url: String, fileName: String?, savePath: String?, speedLimit: Long?, connections: Int?) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var savePath by remember { mutableStateOf("") }
    var speedLimitText by remember { mutableStateOf("") }
    var connectionsText by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }

    // Auto-detect protocol
    val detectedProtocol = when {
        url.startsWith("magnet:", ignoreCase = true) -> "magnet"
        url.startsWith("ftp://", ignoreCase = true) -> "ftp"
        url.contains(".torrent", ignoreCase = true) -> "torrent"
        url.startsWith("https://", ignoreCase = true) -> "https"
        url.startsWith("http://", ignoreCase = true) -> "http"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建下载") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("下载链接") },
                    placeholder = { Text("https:// / magnet: / ftp:// ...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        if (detectedProtocol != null) {
                            Surface(
                                shape = UfiCardDefaults.smallShape,
                                color = Color(0xFFE65100).copy(alpha = 0.12f)
                            ) {
                                Text(
                                    "aria2",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE65100)
                                )
                            }
                        }
                    }
                )

                if (detectedProtocol != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "检测到: ${detectedProtocol.uppercase()} → aria2 引擎",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(8.dp))

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Icon(
                        if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (showAdvanced) "收起选项" else "高级选项",
                        style = MaterialTheme.typography.labelMedium)
                }

                AnimatedVisibility(visible = showAdvanced) {
                    Column {
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            label = { Text("文件名 (可选)") },
                            placeholder = { Text("自动检测") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = savePath,
                            onValueChange = { savePath = it },
                            label = { Text("保存路径 (可选)") },
                            placeholder = { Text(config.saveDir) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = speedLimitText,
                                onValueChange = { speedLimitText = it },
                                label = { Text("限速 (MB/s)") },
                                placeholder = { Text("不限") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = connectionsText,
                                onValueChange = { connectionsText = it },
                                label = { Text("连接数") },
                                placeholder = { Text("默认") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (url.isNotBlank()) {
                        val speedLimit = speedLimitText.toLongOrNull()?.let {
                            if (it > 0) it * 1024 * 1024 else null
                        }
                        val connections = connectionsText.toIntOrNull()?.let {
                            if (it > 0) it else null
                        }
                        onConfirm(
                            url,
                            fileName.ifBlank { null },
                            savePath.ifBlank { null },
                            speedLimit,
                            connections
                        )
                    }
                },
                enabled = url.isNotBlank()
            ) { Text("开始下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ==================== Tracker Edit Dialog ====================

@Composable
private fun TrackerEditDialog(
    cachedTrackers: String,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    // Convert comma-separated to newline-separated for easier editing
    val initialText = cachedTrackers.replace(",", "\n").trim()
    var trackerText by remember { mutableStateOf(initialText) }
    val trackerCount = trackerText.split("\n").count { it.trim().isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 Tracker 列表") },
        text = {
            Column {
                Text(
                    "当前 $trackerCount 条 Tracker",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedTextField(
                    value = trackerText,
                    onValueChange = { trackerText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 250.dp, max = 400.dp),
                    label = { Text("Tracker 列表 (每行一个)") },
                    placeholder = {
                        Text(
                            "udp://tracker.example.com:80/announce\n" +
                            "http://tracker2.example.com/announce\n" +
                            "ws://tracker3.example.com/announce",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    maxLines = 20
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "保存后会自动热加载到运行中的 aria2",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Convert newlines back to comma-separated for API
                val commaSeparated = trackerText
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(",")
                onSave(commaSeparated)
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
