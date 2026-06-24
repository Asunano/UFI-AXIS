package com.ufi_axis.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.debugLogState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    // 多选模式状态
    var selectionMode by remember { mutableStateOf(false) }
    var selectionStart by remember { mutableStateOf(-1) }
    var selectionEnd by remember { mutableStateOf(-1) }

    fun resetSelection() {
        selectionStart = -1
        selectionEnd = -1
    }

    fun exitSelectionMode() {
        selectionMode = false
        resetSelection()
    }

    fun getSelectedLogs(): List<String> {
        if (selectionStart < 0) return emptyList()
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        if (start < 0 || end < 0 || start >= state.logs.size) return emptyList()
        return state.logs.subList(start, (end + 1).coerceAtMost(state.logs.size))
    }

    LaunchedEffect(Unit) { viewModel.tools.loadDebugLogs() }

    // 自动刷新：仅在前台时每 3s 拉取，退到后台自动暂停
    var isScreenActive by remember { mutableStateOf(true) }
    // 使用 lifecycle-aware observer 监听前后台切换
    val lifecycleOwner = remember { (context as? androidx.lifecycle.LifecycleOwner) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isScreenActive = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            if (isScreenActive && !selectionMode) viewModel.tools.loadDebugLogs()
        }
    }

    // 新日志到达时自动滚动到底部（非选择模式下）
    LaunchedEffect(state.logs.size) {
        if (!selectionMode && state.logs.isNotEmpty()) {
            listState.animateScrollToItem(state.logs.size - 1)
        }
    }

    val levels = listOf(null to "全部", "DEBUG" to "D", "INFO" to "I", "WARN" to "W", "ERROR" to "E")
    val selectedCount = if (selectionStart >= 0 && selectionEnd >= 0) {
        maxOf(selectionStart, selectionEnd) - minOf(selectionStart, selectionEnd) + 1
    } else if (selectionStart >= 0) 1 else 0

    UfiScreenScaffold(
        title = if (selectionMode) "已选 $selectedCount 条" else "调试日志 (${state.logs.size})",
        navController = navController,
        showBack = !selectionMode,
        actions = {
            if (selectionMode) {
                // 选择模式下的操作按钮
                if (selectedCount > 0) {
                    IconButton(onClick = {
                        val logs = getSelectedLogs()
                        val text = logs.joinToString("\n")
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("debug-logs", text))
                        Toast.makeText(context, "已复制 $selectedCount 条", Toast.LENGTH_SHORT).show()
                        exitSelectionMode()
                    }) {
                        Icon(Icons.Default.ContentCopy, "复制选中")
                    }
                    IconButton(onClick = {
                        val logs = getSelectedLogs()
                        exportLogsToFile(context, logs)
                        exitSelectionMode()
                    }) {
                        Icon(Icons.Default.FileDownload, "导出选中")
                    }
                }
                IconButton(onClick = { exitSelectionMode() }) {
                    Icon(Icons.Default.Close, "取消选择")
                }
            } else {
                // 正常模式
                if (state.logs.isNotEmpty()) {
                    IconButton(onClick = {
                        selectionMode = true
                        resetSelection()
                    }) {
                        Icon(Icons.Default.SelectAll, "多选")
                    }
                    IconButton(onClick = { exportLogsToFile(context, state.logs) }) {
                        Icon(Icons.Default.FileDownload, "导出")
                    }
                }
                IconButton(onClick = { viewModel.tools.loadDebugLogs() }) {
                    Icon(Icons.Default.Refresh, "刷新")
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "更多")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("复制全部") },
                            onClick = {
                                showMenu = false
                                val text = state.logs.joinToString("\n")
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("debug-logs", text))
                                Toast.makeText(context, "已复制 ${state.logs.size} 条日志", Toast.LENGTH_SHORT).show()
                            },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("清空日志") },
                            onClick = {
                                showMenu = false
                                viewModel.tools.clearDebugLogs()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 过滤芯片行（选择模式下隐藏）
            if (!selectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.PagePadding, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    levels.forEach { (level, label) ->
                        FilterChip(
                            selected = state.filterLevel == level,
                            onClick = { viewModel.tools.setDebugLogFilter(level) },
                            label = { Text(label, fontSize = 12.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            } else {
                // 选择模式提示
                Text(
                    text = if (selectionStart < 0) "点击起始行"
                           else if (selectionEnd < 0) "点击结束行"
                           else "点击重新选择",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = Spacing.PagePadding, vertical = 6.dp)
                )
            }

            state.errorMessage?.let { err ->
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = Spacing.PagePadding, vertical = 4.dp)
                )
            }

            if (state.isLoading && state.logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    UfiLoadingIndicator()
                }
            } else if (state.logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val highlightColor = MaterialTheme.colorScheme.primaryContainer

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.PagePadding, vertical = 4.dp)
                ) {
                    itemsIndexed(state.logs) { index, line ->
                        val isSelected = selectionStart >= 0 && selectionEnd >= 0 &&
                                index in minOf(selectionStart, selectionEnd)..maxOf(selectionStart, selectionEnd)
                        val isAnchor = index == selectionStart
                        val highlighted = isSelected || isAnchor

                        val color = when {
                            "[ERROR]" in line -> MaterialTheme.colorScheme.error
                            "[WARN]" in line -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (highlighted) Modifier.background(highlightColor)
                                    else Modifier
                                )
                                .clickable {
                                    if (selectionMode) {
                                        if (selectionStart < 0) {
                                            selectionStart = index
                                        } else if (selectionEnd < 0) {
                                            selectionEnd = index
                                        } else {
                                            selectionStart = index
                                            selectionEnd = -1
                                        }
                                    } else {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("log-line", line))
                                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 2.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                text = line,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = color,
                                lineHeight = 14.sp,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun exportLogsToFile(context: Context, logs: List<String>) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ufi_axis_log_$timestamp.txt"
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadsDir.mkdirs()
        val file = File(downloadsDir, fileName)
        file.writeText(logs.joinToString("\n"))
        Toast.makeText(context, "已导出到 Downloads/$fileName", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
