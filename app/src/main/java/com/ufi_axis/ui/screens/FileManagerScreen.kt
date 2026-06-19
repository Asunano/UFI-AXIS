package com.ufi_axis.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.api.FileItem
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.fileManagerState.collectAsState()
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<FileItem?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<FileItem?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showQuickPaths by remember { mutableStateOf(false) }
    var fileActionDialog by remember { mutableStateOf<FileItem?>(null) }
    var showDownloadHistory by remember { mutableStateOf(false) }

    // File upload picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.uploadFileToServer(it, state.currentPath) }
    }

    // Smart file open: media files open directly, others show action dialog
    fun smartOpenFile(file: FileItem) {
        val ext = file.name.substringAfterLast(".", "").lowercase()
        val encodedPath = URLEncoder.encode(file.path, "UTF-8")
        when {
            ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg") ->
                navController.navigate("file/image?path=$encodedPath")
            ext in listOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "m4v") ->
                navController.navigate("file/media?path=$encodedPath&type=video")
            ext in listOf("mp3", "wav", "flac", "ogg", "aac", "m4a", "wma", "opus", "amr") ->
                navController.navigate("file/media?path=$encodedPath&type=audio")
            else -> fileActionDialog = file
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadDiskUsage()  // Handles auto-navigation based on volume count
        viewModel.dismissFileContent() // Clear any residual file content from TextEditorScreen
        viewModel.loadPhoneDownloadHistory()
    }

    UfiScreenScaffold(
        title = if (state.multiSelectMode) "已选 ${state.selectedPaths.size} 项" else "文件管理",
        navController = navController, showBack = true,
        actions = {
            if (state.multiSelectMode) {
                IconButton(onClick = { viewModel.selectAllFiles() }) { Icon(Icons.Default.SelectAll, "全选") }
                IconButton(onClick = { viewModel.batchCopySelected() }) { Icon(Icons.Default.ContentCopy, "复制") }
                IconButton(onClick = { viewModel.batchCutSelected() }) { Icon(Icons.Default.ContentCut, "剪切") }
                IconButton(onClick = { viewModel.batchDeleteSelected() }) {
                    Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                }
                IconButton(onClick = { viewModel.toggleMultiSelectMode() }) { Icon(Icons.Default.Close, "取消") }
            } else {
                if (state.clipboard != null) {
                    IconButton(onClick = { viewModel.pasteFromClipboard(state.currentPath) }) { Icon(Icons.Default.ContentPaste, "粘贴") }
                }
                IconButton(onClick = { viewModel.toggleMultiSelectMode() }) { Icon(Icons.Default.Checklist, "操控模式") }
                IconButton(onClick = { showDownloadHistory = true }) { Icon(Icons.Default.DownloadDone, "下载历史") }
                IconButton(onClick = { showSearchDialog = true }) { Icon(Icons.Default.Search, "搜索") }
                IconButton(onClick = { viewModel.toggleRootMode() }) {
                    Icon(Icons.Default.Shield, "Root 模式",
                        tint = if (state.rootMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { viewModel.refreshFileList() }) { Icon(Icons.Default.Refresh, "刷新") }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val isAtVirtualRoot = state.currentPath.isEmpty()
            val isAtVolumeRoot = state.currentPath == state.storageRoot
                    || state.storageVolumes.any { it.mountPath == state.currentPath }

            // 路径栏 + 排序 + 快捷路径
            Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Column {
                    Row(
                        Modifier.padding(horizontal = Spacing.PagePadding, vertical = Spacing.Small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isAtVirtualRoot && !(isAtVolumeRoot && state.storageVolumes.size <= 1)) {
                            IconButton(onClick = { viewModel.navigateToParent() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.ArrowBack, "上级", Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            if (isAtVirtualRoot) "选择存储" else state.currentPath,
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (state.rootMode) {
                            Spacer(Modifier.width(4.dp))
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.error) {
                                Text("ROOT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        Box {
                            IconButton(onClick = { showSortMenu = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Sort, "排序", Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                listOf("name" to "按名称", "size" to "按大小", "date" to "按日期", "type" to "按类型").forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, color = if (state.sortBy == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                                        onClick = { viewModel.setSortBy(key); showSortMenu = false },
                                        leadingIcon = { if (state.sortBy == key) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) else Spacer(Modifier.width(24.dp)) }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { showQuickPaths = !showQuickPaths }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Bookmark, "快捷路径", Modifier.size(18.dp))
                        }
                    }

                    // 快捷路径面板
                    if (showQuickPaths) {
                        val quickPaths = listOf(
                            "/storage/emulated/0" to "内部存储",
                            "/storage/emulated/0/Download" to "下载",
                            "/storage/emulated/0/DCIM" to "相册",
                            "/storage/emulated/0/Documents" to "文档",
                            "/storage/emulated/0/Android/data" to "应用数据",
                            "/data" to "系统数据"
                        )
                        Row(
                            Modifier.padding(horizontal = Spacing.PagePadding, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            quickPaths.forEach { (path, label) ->
                                SuggestionChip(
                                    onClick = { viewModel.loadFileList(path); showQuickPaths = false },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }

            // 磁盘用量 (hidden at virtual root — volume cards show storage info there)
            val diskUsageData = remember(state.diskUsage) {
                try {
                    state.diskUsage?.let {
                        JsonParser.parseString(it.toString()).asJsonObject
                            .getAsJsonArray("disks")
                    }
                } catch (_: Exception) { null }
            }
            if (!isAtVirtualRoot && diskUsageData != null && diskUsageData.size() > 0) {
                Row(
                    Modifier.padding(horizontal = Spacing.PagePadding, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    diskUsageData.forEach { disk ->
                        val obj = disk.asJsonObject
                        val label = obj.get("label")?.asString ?: obj.get("mount")?.asString ?: ""
                        val avail = obj.get("available")?.asString ?: ""
                        val pct = obj.get("usePercent")?.asString ?: ""
                        Text("$label ${avail}可用($pct)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            state.errorMessage?.let { UfiErrorBanner(message = it, modifier = Modifier.padding(horizontal = Spacing.PagePadding, vertical = Spacing.Small)) }
            state.operationMessage?.let {
                UfiAlertDialog(
                    title = if (it.contains("失败")) "错误" else "提示",
                    text = it,
                    onDismiss = { viewModel.clearFileOperationMessage() }
                )
            }

            // 搜索结果或文件列表
            val displayFiles = state.searchResults ?: state.files
            val isSearchMode = state.searchResults != null

            if (isAtVirtualRoot) {
                // ── 虚拟根视图：显示存储卷卡片 ──
                if (state.isLoading || state.storageVolumes.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (state.isLoading) CircularProgressIndicator()
                        else Text("正在检测存储...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = Spacing.PagePadding, vertical = Spacing.Medium),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.storageVolumes.size) { idx ->
                            val vol = state.storageVolumes[idx]
                            val isSd = vol.label.contains("SD") || vol.mountPath.startsWith("/storage/") && !vol.mountPath.contains("emulated")
                            Card(
                                onClick = { viewModel.navigateToDir(vol.mountPath) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(Spacing.CardCorner),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (isSd) Icons.Default.SdCard else Icons.Default.Storage,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(vol.label,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.height(4.dp))
                                        Text("${vol.totalSize} 总计 · ${vol.usedSize} 已用 · ${vol.availSize} 可用",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(2.dp))
                                        // Usage bar
                                        val pctNum = vol.usePercent.replace("%", "").toIntOrNull() ?: 0
                                        LinearProgressIndicator(
                                            progress = { pctNum / 100f },
                                            modifier = Modifier.fillMaxWidth().height(4.dp),
                                            color = if (pctNum > 90) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        )
                                    }
                                    Icon(Icons.Default.ChevronRight, null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            } else if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                if (isSearchMode) {
                    // 搜索结果头部
                    Row(Modifier.padding(horizontal = Spacing.PagePadding, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("搜索结果 (${displayFiles.size})", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearSearchResults() }) { Text("返回", style = MaterialTheme.typography.labelSmall) }
                    }
                }

                // 新建按钮栏
                if (!isSearchMode) {
                    Row(Modifier.padding(horizontal = Spacing.PagePadding, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuggestionChip(onClick = { showNewFolderDialog = true },
                            label = { Text("新建文件夹", style = MaterialTheme.typography.labelSmall) },
                            icon = { Icon(Icons.Default.CreateNewFolder, null, Modifier.size(16.dp)) })
                        SuggestionChip(onClick = { showNewFileDialog = true },
                            label = { Text("新建文件", style = MaterialTheme.typography.labelSmall) },
                            icon = { Icon(Icons.Default.NoteAdd, null, Modifier.size(16.dp)) })
                        SuggestionChip(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            enabled = !state.isUploading,
                            label = { Text(if (state.isUploading) "上传中..." else "上传文件", style = MaterialTheme.typography.labelSmall) },
                            icon = {
                                if (state.isUploading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Upload, null, Modifier.size(16.dp))
                            })
                    }
                }

                if (displayFiles.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (isSearchMode) "未找到匹配文件" else "此目录为空",
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = Spacing.PagePadding, vertical = Spacing.Small),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(displayFiles, key = { it.path }) { file ->
                            FileItemRow(
                                file = file,
                                multiSelectMode = state.multiSelectMode,
                                isSelected = file.path in state.selectedPaths,
                                onToggleSelect = { viewModel.toggleFileSelection(file.path) },
                                onOpen = { smartOpenFile(file) },
                                onNavigate = {
                                    if (isSearchMode) viewModel.clearSearchResults()
                                    viewModel.navigateToDir(it.path)
                                },
                                onInfo = { viewModel.getFileInfo(it.path) },
                                onCopy = { viewModel.copyToClipboard(it.path, false) },
                                onCut = { viewModel.copyToClipboard(it.path, true) },
                                onRename = { showRenameDialog = it },
                                onDelete = { showDeleteConfirm = it },
                                onEdit = { filePath ->
                                    val encodedPath = URLEncoder.encode(filePath, "UTF-8")
                                    navController.navigate("file/editor?path=$encodedPath")
                                },
                                onDownload = { viewModel.downloadFileToPhone(file.path, file.name) }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 上传进度对话框 ──
    if (state.isUploading) {
        AlertDialog(
            onDismissRequest = { /* non-dismissible during upload */ },
            title = { Text("上传中") },
            text = {
                Column {
                    Text(state.uploadFileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    if (state.uploadProgress >= 0f) {
                        LinearProgressIndicator(
                            progress = { state.uploadProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("${(state.uploadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                    }
                }
            },
            confirmButton = { /* no button */ }
        )
    }

    // ── 下载进度对话框 ──
    if (state.isDownloading) {
        AlertDialog(
            onDismissRequest = { /* non-dismissible during download */ },
            title = { Text("下载中") },
            text = {
                Column {
                    Text(state.downloadFileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    if (state.downloadProgress >= 0f) {
                        LinearProgressIndicator(
                            progress = { state.downloadProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${(state.downloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Text(
                                "${FormatUtils.formatBytes(state.downloadBytes)} / ${if (state.downloadTotalBytes > 0) FormatUtils.formatBytes(state.downloadTotalBytes) else "计算中..."}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("${FormatUtils.formatBytes(state.downloadBytes)} 已下载",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelDownload() }) {
                    Text("取消")
                }
            }
        )
    }

    // ── 下载到手机历史对话框 ──
    if (showDownloadHistory) {
        AlertDialog(
            onDismissRequest = { showDownloadHistory = false },
            title = { Text("下载到手机历史") },
            text = {
                val history = state.phoneDownloadHistory
                if (history.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.DownloadDone, contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(8.dp))
                            Text("暂无下载记录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(history) { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (item.status == "completed") Icons.Default.CheckCircle
                                        else Icons.Default.Error,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (item.status == "completed")
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                        else MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            item.fileName,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "${FormatUtils.formatBytes(item.fileSize)} · ${
                                                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                                    .format(Date(item.downloadedAt))
                                            }",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (state.phoneDownloadHistory.isNotEmpty()) {
                    TextButton(onClick = {
                        viewModel.clearPhoneDownloadHistory()
                    }) { Text("清空") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadHistory = false }) { Text("关闭") }
            }
        )
    }

    // ── 对话框 ──

    if (showNewFolderDialog) {
        UfiInputDialog(
            title = "新建文件夹", initialValue = "", hint = "文件夹名称", confirmText = "创建",
            onConfirm = { viewModel.createDirectory("${state.currentPath}/$it"); showNewFolderDialog = false },
            onDismiss = { showNewFolderDialog = false },
            validator = { if (it.isBlank()) "名称不能为空" else null }
        )
    }

    if (showNewFileDialog) {
        UfiInputDialog(
            title = "新建文件", initialValue = "", hint = "文件名 (如 note.txt)", confirmText = "创建",
            onConfirm = { viewModel.createFile(it); showNewFileDialog = false },
            onDismiss = { showNewFileDialog = false },
            validator = { if (it.isBlank()) "名称不能为空" else null }
        )
    }

    if (showSearchDialog) {
        UfiInputDialog(
            title = "搜索文件", initialValue = "", hint = "输入文件名关键词", confirmText = "搜索",
            onConfirm = { viewModel.searchFiles(it); showSearchDialog = false },
            onDismiss = { showSearchDialog = false },
            validator = { if (it.isBlank()) "请输入搜索关键词" else null }
        )
    }

    showRenameDialog?.let { file ->
        UfiInputDialog(
            title = "重命名", initialValue = file.name, hint = "新文件名", confirmText = "确定",
            onConfirm = { viewModel.renameFile(file.path, it); showRenameDialog = null },
            onDismiss = { showRenameDialog = null },
            validator = { if (it.isBlank()) "名称不能为空" else null }
        )
    }

    showDeleteConfirm?.let { file ->
        UfiConfirmDialog(
            title = "确认删除",
            text = "确定要删除 \"${file.name}\" 吗？${if (file.isDirectory) "目录内所有内容将一并删除。" else ""}此操作不可撤销。",
            confirmText = "删除", destructive = true,
            onConfirm = { viewModel.deleteFileOrDir(file.path); showDeleteConfirm = null },
            onDismiss = { showDeleteConfirm = null }
        )
    }

    // 文件操作选择对话框
    fileActionDialog?.let { file ->
        val ext = file.name.substringAfterLast(".", "").lowercase()
        val isText = ext in listOf("txt", "log", "md", "json", "xml", "yaml", "yml", "toml", "ini",
            "conf", "cfg", "properties", "csv", "html", "htm", "css", "js", "ts", "kt", "kts",
            "java", "py", "rb", "go", "rs", "c", "cpp", "h", "sh", "bash", "sql", "gradle",
            "pro", "env", "gitignore", "mk", "cmake", "bat", "ps1", "lua", "php", "swift", "r")
        val isApk = ext == "apk"
        val encodedPath = remember(file) { URLEncoder.encode(file.path, "UTF-8") }

        ModalBottomSheet(
            onDismissRequest = { fileActionDialog = null },
            shape = RoundedCornerShape(topStart = Spacing.CardCorner, topEnd = Spacing.CardCorner)
        ) {
            Column(Modifier.padding(Spacing.InnerPadding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(fileIcon(file.name, false), null,
                        tint = fileIconTint(file.name, false), modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${FormatUtils.formatBytes(file.size)} · ${fileTypeLabel(file.name)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(Spacing.Medium))

                // Action buttons
                if (isText) {
                    UfiPrimaryButton(text = "编辑", onClick = {
                        fileActionDialog = null
                        navController.navigate("file/editor?path=$encodedPath")
                    }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                if (isApk) {
                    UfiPrimaryButton(text = "安装", onClick = {
                        fileActionDialog = null
                        viewModel.installApk(file.path)
                    }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UfiSecondaryButton(text = "下载", onClick = {
                        fileActionDialog = null
                        viewModel.downloadFileToPhone(file.path, file.name)
                    }, modifier = Modifier.weight(1f))
                    UfiSecondaryButton(text = "信息", onClick = {
                        fileActionDialog = null
                        viewModel.getFileInfo(file.path)
                    }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                UfiSecondaryButton(text = "取消", onClick = { fileActionDialog = null }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Spacing.Large))
            }
        }
    }

    state.pendingProtectedPath?.let { protectedPath ->
        UfiConfirmDialog(
            title = "系统目录访问警告",
            text = "\"$protectedPath\" 是系统目录，普通模式下无法访问。\n\n启用 Root 模式可能影响系统稳定性，确定要继续吗？",
            confirmText = "启用 Root 模式", destructive = true,
            onConfirm = { viewModel.forceNavigate(protectedPath) },
            onDismiss = { viewModel.dismissProtectedPath() }
        )
    }

    // 文件信息底栏
    state.selectedFile?.let { fileInfo ->
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissFileInfo() },
            shape = RoundedCornerShape(topStart = Spacing.CardCorner, topEnd = Spacing.CardCorner)
        ) {
            Column(Modifier.padding(Spacing.InnerPadding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(fileIcon(fileInfo.name, fileInfo.isDirectory), null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(fileInfo.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(Spacing.Medium))
                UfiInfoCard(label = "路径", value = fileInfo.path)
                UfiInfoCard(label = "大小", value = FormatUtils.formatBytes(fileInfo.size))
                UfiInfoCard(label = "类型", value = if (fileInfo.isDirectory) "文件夹" else fileTypeLabel(fileInfo.name))
                UfiInfoCard(label = "权限", value = fileInfo.permissions)
                UfiInfoCard(label = "所有者", value = fileInfo.owner)
                UfiInfoCard(label = "修改时间", value = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(fileInfo.lastModified)))
                Spacer(Modifier.height(Spacing.Medium))
                if (!fileInfo.isDirectory) {
                    Spacer(Modifier.height(Spacing.Medium))
                    UfiSecondaryButton(text = "下载", onClick = {
                        viewModel.downloadFileToPhone(fileInfo.path, fileInfo.name)
                        viewModel.dismissFileInfo()
                    }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Spacing.Medium))
                }
                UfiSecondaryButton(text = "关闭", onClick = { viewModel.dismissFileInfo() })
                Spacer(Modifier.height(Spacing.Large))
            }
        }
    }

    state.clipboard?.let { clip ->
        val actionLabel = if (clip.isCut) "已剪切" else "已复制"
        val desc = if (clip.sourcePaths.size > 1) "${clip.sourcePaths.size} 个文件"
                   else clip.sourcePaths.first().substringAfterLast("/")
        Snackbar(
            modifier = Modifier.padding(Spacing.PagePadding),
            action = { TextButton(onClick = { viewModel.clearClipboard() }) { Text("取消") } }
        ) { Text("$actionLabel: $desc — 导航到目标目录后点击粘贴") }
    }
}

@Composable
private fun FileItemRow(
    file: FileItem,
    multiSelectMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: (FileItem) -> Unit,
    onNavigate: (FileItem) -> Unit,
    onInfo: (FileItem) -> Unit,
    onCopy: (FileItem) -> Unit,
    onCut: (FileItem) -> Unit,
    onRename: (FileItem) -> Unit,
    onDelete: (FileItem) -> Unit,
    onEdit: (String) -> Unit,
    onDownload: (FileItem) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val isNavigable = file.isDirectory || file.isSymlink

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            if (multiSelectMode) onToggleSelect()
            else if (isNavigable) onNavigate(file)
            else onOpen(file)
        },
        shape = RoundedCornerShape(Spacing.CardCorner),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (multiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                imageVector = fileIcon(file.name, isNavigable),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = fileIconTint(file.name, isNavigable)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    Text(
                        if (isNavigable) fileTypeLabel(file.name) else "${FormatUtils.formatBytes(file.size)}  \u00B7  ${fileTypeLabel(file.name)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, "更多", Modifier.size(20.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (!isNavigable) {
                        DropdownMenuItem(text = { Text("打开") }, onClick = { onOpen(file); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.OpenInNew, null) })
                        DropdownMenuItem(text = { Text("编辑") }, onClick = { onEdit(file.path); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.Edit, null) })
                        DropdownMenuItem(text = { Text("下载") }, onClick = { onDownload(file); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.Download, null) })
                    }
                    DropdownMenuItem(text = { Text("信息") }, onClick = { onInfo(file); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Info, null) })
                    DropdownMenuItem(text = { Text("复制") }, onClick = { onCopy(file); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
                    DropdownMenuItem(text = { Text("剪切") }, onClick = { onCut(file); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.ContentCut, null) })
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { onRename(file); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) })
                    DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = { onDelete(file); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
                }
            }
        }
    }
}

// ── 文件图标 ──

private fun fileIcon(name: String, isDir: Boolean): androidx.compose.ui.graphics.vector.ImageVector {
    if (isDir) return Icons.Default.Folder
    val ext = name.substringAfterLast(".", "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico" -> Icons.Default.Image
        "mp4", "mkv", "avi", "mov", "webm", "flv" -> Icons.Default.VideoFile
        "mp3", "wav", "flac", "ogg", "aac", "m4a" -> Icons.Default.AudioFile
        "pdf" -> Icons.Default.PictureAsPdf
        "doc", "docx", "odt" -> Icons.Default.Description
        "xls", "xlsx", "csv" -> Icons.Default.TableChart
        "zip", "tar", "gz", "rar", "7z" -> Icons.Default.FolderZip
        "apk" -> Icons.Default.Android
        "sh", "bash", "zsh" -> Icons.Default.Terminal
        "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg" -> Icons.Default.Settings
        "txt", "log", "md" -> Icons.Default.Article
        "py", "js", "kt", "java", "c", "cpp", "h", "go", "rs" -> Icons.Default.Code
        "html", "css", "htm" -> Icons.Default.Web
        else -> Icons.Default.InsertDriveFile
    }
}

@Composable
private fun fileIconTint(name: String, isDir: Boolean): androidx.compose.ui.graphics.Color {
    if (isDir) return MaterialTheme.colorScheme.primary
    val ext = name.substringAfterLast(".", "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "bmp", "webp" -> MaterialTheme.colorScheme.tertiary
        "mp4", "mkv", "avi", "mov" -> MaterialTheme.colorScheme.tertiary
        "mp3", "wav", "flac", "ogg" -> MaterialTheme.colorScheme.secondary
        "apk" -> MaterialTheme.colorScheme.primary
        "sh", "bash" -> MaterialTheme.colorScheme.error
        "zip", "tar", "gz", "rar" -> MaterialTheme.colorScheme.secondary
        "pdf" -> MaterialTheme.colorScheme.error
        "json", "xml", "yaml", "yml" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun fileTypeLabel(name: String): String {
    val ext = name.substringAfterLast(".", "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "bmp", "webp" -> "图片"
        "mp4", "mkv", "avi", "mov" -> "视频"
        "mp3", "wav", "flac", "ogg" -> "音频"
        "pdf" -> "PDF"
        "doc", "docx" -> "文档"
        "xls", "xlsx", "csv" -> "表格"
        "zip", "tar", "gz", "rar", "7z" -> "压缩包"
        "apk" -> "安装包"
        "sh", "bash" -> "脚本"
        "json", "xml", "yaml", "yml" -> "配置"
        "txt", "log", "md" -> "文本"
        "py", "js", "kt", "java" -> "代码"
        "html", "css" -> "网页"
        "" -> ""
        else -> ext.uppercase()
    }
}
