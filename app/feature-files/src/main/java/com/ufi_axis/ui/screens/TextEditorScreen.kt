package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.UfiAlertDialog
import com.ufi_axis.ui.components.common.UfiInputDialog
import com.ufi_axis.ui.components.common.UfiSkeletonLine
import com.ufi_axis.ui.components.common.UfiToolbarAction
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.LoadedFile

/**
 * 文本/代码文件预览+编辑屏（v2 2026-08-23：大文件保护 + 缓存修复 + UI 重做）。
 *
 * 旧版两个长期未解的真 bug：
 *  1. 切文件不刷新：`LaunchedEffect(state.fileContent)` 仅在 `!isLoaded` 时采纳，加载一次后
 *     `isLoaded` 永为 true → 打开 B 文件仍渲染 A 内容（用户截图复现：「我打开a文件显示内容
 *     正常，但是我退出后打开B文件时仍旧显示a文件的内容」）。
 *  2. 大文件卡死/OOM：`OutlinedTextField(value=完整文本, fillMaxSize)`，对长文本+Monospace
 *     渲染几乎 O(n²)；几 KB 就肉眼可感卡顿，几百 KB 必然 ANR，几十 MB 直接 OOM 杀进程
 *     （用户截图复现：「查看几kb大小也会出现异常卡顿」+「整个app卡住了，是不是内存溢出」）。
 *
 * v2 关键修复：
 *  - 用 [LoadedFile.path == filePath] 严格匹配：只有当前屏幕 target 命中时才采纳状态。
 *  - `editText`/`isLoaded` 用 `rememberSaveable(filePath)` 按文件分桶，BackStack 恢复不会错位。
 *  - `LaunchedEffect(filePath)` 强制重新触发 `viewModel.files.readFile`，并先把本地状态重置为加载中。
 *  - 大文件保护：服务端在 [com.ufi_axis_core.api.routes.FileRoutes.MAX_READ_SIZE=512KB]
 *    处截断；前端拿到 `loadedFile.truncated == true` 或 content 体 > 256KB → 强制只读 + LazyColumn
 *    按行虚拟化（百万行也不卡）。小文件仍保留可编辑 BasicTextField（性能可接受）。
 *  - UI 美化：顶栏「文件名 · 大小 · 父目录」、底部状态条「行 · 字符 · 截断提示 · 保存态」、
 *    工具行「复制全文 / 复制路径」紧凑排列；横向滚动（长行不被截屏）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    filePath: String
) {
    val palette = LocalResolvedPalette.current
    val state by viewModel.fileManagerState.collectAsState()
    val errorMessage = state.errorMessage
    val loadedFile = state.loadedFile

    val fileName = rememberSaveable(filePath) { filePath.substringAfterLast("/") }
    val parentDir = rememberSaveable(filePath) { filePath.substringBeforeLast("/", "/") }
    var editText by rememberSaveable(filePath) { mutableStateOf("") }
    var isLoaded by rememberSaveable(filePath) { mutableStateOf(false) }
    var showSaveSuccess by rememberSaveable(filePath) { mutableStateOf(false) }
    var hasChanges by rememberSaveable(filePath) { mutableStateOf(false) }

    var showFindDialog by remember { mutableStateOf(false) }
    var showGoToLineDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    val findQuery = remember { mutableStateOf("") }
    val lazyListState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(filePath) {
        isLoaded = false
        editText = ""
        hasChanges = false
        viewModel.files.readFile(filePath)
    }

    val isCurrentFileLoaded = loadedFile != null && loadedFile.path == filePath
    LaunchedEffect(isCurrentFileLoaded, loadedFile?.size) {
        if (isCurrentFileLoaded && loadedFile != null && !isLoaded) {
            editText = loadedFile.content
            isLoaded = true
            hasChanges = false
        }
    }

    val isHuge = isLoaded && loadedFile != null && (
        loadedFile.truncated ||
            loadedFile.size > READ_ONLY_THRESHOLD ||
            editText.length > READ_ONLY_CHAR_THRESHOLD
    )

    val confirmExit: () -> Unit = {
        if (hasChanges) showLeaveDialog = true else navController.popBackStack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = fileName.ifBlank { filePath },
                            fontWeight = UfiWeight.Emphasis,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildMetaLine(isLoaded, loadedFile, errorMessage, parentDir),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = confirmExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (isLoaded && !isHuge) {
                        IconButton(
                            onClick = {
                                viewModel.files.writeFile(filePath, editText)
                                hasChanges = false
                                showSaveSuccess = true
                            },
                            enabled = hasChanges
                        ) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = "保存",
                                tint = if (hasChanges) palette.accent
                                else palette.textSecondary
                            )
                        }
                    }
                    if (isLoaded) {
                        IconButton(onClick = { showGoToLineDialog = true }) {
                            Icon(Icons.Default.Tag, contentDescription = "跳转行")
                        }
                        IconButton(onClick = { showFindDialog = true }) {
                            Icon(Icons.Default.Search, contentDescription = "查找")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TextEditorToolbar(
                isLoaded = isLoaded,
                isHuge = isHuge,
                loadedFile = loadedFile,
                editText = editText,
                onCopyAll = { clipboard.setText(AnnotatedString(editText)) },
                onCopyPath = { clipboard.setText(AnnotatedString(filePath)) }
            )

            val bg = palette.cardBg

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = Spacing.PagePadding, vertical = 4.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(bg)
                    .border(1.dp, palette.divider.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
            ) {
                when {
                    errorMessage != null && !isLoaded -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = errorMessage,
                                color = palette.accent,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    !isLoaded -> {
                        // 2026-09-03：文件正文首次加载从居中转圈改为骨架屏。
                        // 这个 Box 就是编辑器正文区（工具栏以下占满整屏），!isLoaded 时里面完全空白，
                        // 属于整页首屏；大文件从设备读回来可能要好几秒，转圈期间用户看不出
                        // 接下来是一屏文本还是别的东西。骨架线能预示等宽文本的行版式。
                        // 保存 / 查找 / 跳转这类局部动作不走这里，仍用转圈或进度条。
                        // 12 行 ≈ 首屏可见行数；每四行插一条短行，模拟真实代码/日志的不齐行尾。
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(Spacing.InnerPadding),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
                        ) {
                            repeat(12) { index ->
                                UfiSkeletonLine(widthFraction = if (index % 4 == 3) 0.5f else 0.95f)
                            }
                        }
                    }
                    isHuge -> {
                        ReadOnlyTextView(
                            content = editText,
                            query = findQuery.value,
                            listState = lazyListState
                        )
                    }
                    else -> {
                        EditableTextView(
                            text = editText,
                            onTextChange = {
                                editText = it
                                hasChanges = true
                            }
                        )
                    }
                }
            }

            StatusBar(
                isLoaded = isLoaded,
                isHuge = isHuge,
                loadedFile = loadedFile,
                editText = editText,
                hasChanges = hasChanges,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.PagePadding, vertical = 4.dp)
            )
        }
    }

    if (showSaveSuccess) {
        UfiAlertDialog(
            title = "已保存",
            text = "文件已写入 ${fileName}（${FormatUtils.formatSize(loadedFile?.size?.toLong() ?: 0L)}）",
            onDismiss = { showSaveSuccess = false }
        )
    }

    errorMessage?.takeIf { isLoaded }?.let { msg ->
        UfiAlertDialog(
            title = "提示",
            text = msg,
            onDismiss = { viewModel.files.clearFileOperationMessage() }
        )
    }

    if (showFindDialog) {
        FindDialog(
            onConfirm = { query ->
                findQuery.value = query
                showFindDialog = false
            },
            onDismiss = { showFindDialog = false },
            initial = findQuery.value
        )
    }

    if (showGoToLineDialog && editText.isNotEmpty()) {
        val lineCount = remember(editText) { editText.count { it == '\n' } + 1 }
        GoToLineDialog(
            lineCount = lineCount,
            onConfirm = { line ->
                showGoToLineDialog = false
                val safeLine = line.coerceIn(1, lineCount)
                scope.launch {
                    try {
                        lazyListState.animateScrollToItem(safeLine - 1, scrollOffset = -16)
                    } catch (_: Exception) {
                    }
                }
            },
            onDismiss = { showGoToLineDialog = false }
        )
    }

    if (showLeaveDialog) {
        UfiAlertDialog(
            title = "未保存的修改",
            text = "当前有未保存的更改，确定要离开吗？",
            confirmText = "放弃修改",
            onConfirm = {
                showLeaveDialog = false
                hasChanges = false
                navController.popBackStack()
            },
            dismissText = "继续编辑",
            onDismiss = { showLeaveDialog = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  EditableTextView（小文件可编辑）
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EditableTextView(
    text: String,
    onTextChange: (String) -> Unit
) {
    val palette = LocalResolvedPalette.current
    val scrollState = rememberScrollState()
    val style = UfiTextStyles.monoCodeBlock.copy(color = palette.textPrimary)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        SelectionContainer {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart),
                textStyle = style,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.accent),
                maxLines = Int.MAX_VALUE
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  ReadOnlyTextView（大文件 / 截断文件）
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReadOnlyTextView(
    content: String,
    query: String,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    val palette = LocalResolvedPalette.current
    val lines = remember(content) {
        content.split('\n')
    }
    val highlightIndex = if (query.isBlank()) -1
        else lines.indexOfFirst { it.contains(query, ignoreCase = true) }

    LaunchedEffect(highlightIndex, query) {
        if (highlightIndex >= 0) {
            try {
                listState.animateScrollToItem(highlightIndex, scrollOffset = -32)
            } catch (_: Exception) {
            }
        }
    }

    val lineNumWidth = with(LocalDensity.current) { 48.sp.toDp() }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        items(count = lines.size, key = null) { idx ->
            val line = lines[idx]
            val highlighted = idx == highlightIndex
            val rowBg = when {
                highlighted -> palette.accent.copy(alpha = 0.14f)
                idx % 2 == 0 -> androidx.compose.ui.graphics.Color.Transparent
                else -> palette.divider.copy(alpha = 0.06f)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(rowBg)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = (idx + 1).toString().padStart(5),
                    style = UfiTextStyles.monoNote,
                    color = palette.textSecondary.copy(alpha = 0.55f),
                    modifier = Modifier
                        .width(lineNumWidth)
                        .padding(end = 6.dp)
                )
                Text(
                    text = line.ifEmpty { " " },
                    style = UfiTextStyles.monoCodeBlock,
                    color = palette.textPrimary
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Toolbar / StatusBar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TextEditorToolbar(
    isLoaded: Boolean,
    isHuge: Boolean,
    loadedFile: LoadedFile?,
    editText: String,
    onCopyAll: () -> Unit,
    onCopyPath: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.PagePadding, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isHuge) {
            Surface(
                color = palette.accent.copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "只读 · 服务端已截断（>${READ_ONLY_THRESHOLD / 1024}KB），完整下载请用「下载到手机」",
                    style = UfiTextStyles.noteCompact,
                    color = palette.accent,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (isLoaded) {
            // 2026-08-31：私有 ToolbarAction → 公共 UfiToolbarAction(vertical = false)（横排 TextButton 形态）
            UfiToolbarAction(
                icon = Icons.Default.ContentCopy,
                label = "复制全文",
                onClick = onCopyAll,
                enabled = editText.isNotEmpty(),
                vertical = false
            )
            UfiToolbarAction(
                icon = Icons.Default.FileCopy,
                label = "复制路径",
                onClick = onCopyPath,
                vertical = false
            )
        }
    }
}

/**
 * 2026-08-31：私有 ToolbarAction 已删除 —— 与 FileToolbar 那份合并进公共层
 * [com.ufi_axis.ui.components.common.UfiToolbarAction]（本页用 vertical = false 形态）。
 */

@Composable
private fun StatusBar(
    isLoaded: Boolean,
    isHuge: Boolean,
    loadedFile: LoadedFile?,
    editText: String,
    hasChanges: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val cursorLine = if (!isLoaded) "1" else (editText.count { it == '\n' } + 1).toString()
    val charCount = if (isLoaded) editText.length.toString() else "—"
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("第 $cursorLine 行", style = UfiTextStyles.noteCompact, color = palette.textSecondary)
        if (loadedFile != null && loadedFile.size > 0) {
            Text(
                "${loadedFile.size}B · 截断=${loadedFile.truncated}",
                style = UfiTextStyles.noteCompact,
                color = palette.textSecondary
            )
        }
        Text("$charCount 字符", style = UfiTextStyles.noteCompact, color = palette.textSecondary)
        Spacer(Modifier.weight(1f))
        if (isLoaded && !isHuge) {
            Text(
                if (hasChanges) "● 未保存" else "已同步",
                style = UfiTextStyles.noteCompact,
                color = if (hasChanges) palette.accent else palette.textSecondary
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  对话框
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FindDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String
) {
    UfiInputDialog(
        title = "查找",
        initialValue = initial,
        hint = "输入关键字（不区分大小写）",
        confirmText = "查找",
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

@Composable
private fun GoToLineDialog(
    lineCount: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    UfiInputDialog(
        title = "跳转行",
        initialValue = "",
        hint = "1 - $lineCount",
        confirmText = "跳转",
        validator = { txt ->
            val n = txt.toIntOrNull()
            if (n == null) "请输入数字" else if (n < 1 || n > lineCount) "行号越界" else null
        },
        onConfirm = { txt -> onConfirm(txt.toIntOrNull() ?: 1) },
        onDismiss = onDismiss
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  工具
// ─────────────────────────────────────────────────────────────────────────────

private fun buildMetaLine(
    isLoaded: Boolean,
    f: LoadedFile?,
    err: String?,
    parent: String
): String = when {
    err != null && !isLoaded -> err
    !isLoaded -> "正在加载…"
    f == null -> parent
    f.truncated -> "${FormatUtils.formatSize(f.size.toLong())} · 服务端已截断 · $parent"
    else -> "${FormatUtils.formatSize(f.size.toLong())} · $parent"
}

// 2026-08-31：私有 formatSize 已删除 —— 全库 4 份逐字节相同的拷贝统一收敛到
// [com.ufi_axis.util.FormatUtils.formatSize]（换算口径不变，额外获得 TB/PB 兜底）。

/** 大文件阈值：服务端 MAX_READ_SIZE=512KB 之下，UI 256KB 起切只读，避免 BasicTextField 卡死。 */
private const val READ_ONLY_THRESHOLD = 256 * 1024

/** 编辑模型下文本过长会卡，字符数也作为切换只读的兜底。 */
private const val READ_ONLY_CHAR_THRESHOLD = 200_000
