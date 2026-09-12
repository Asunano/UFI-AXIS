package com.ufi_axis.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.screens.textviewer.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.ui.theme.UfiAnimSpecs
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.TextFileContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

/**
 * 文本文件编辑页（v4 2026-09-11）。
 *
 * 本文件只负责**编排**：状态、顶栏、弹窗、与 ViewModel 的往来。正文渲染、路径条、操作栏、
 * 查找浮层、状态条、撤销栈、编码与换行符处理都在 `screens/textviewer/` 下各自成文件。
 *
 * ## v4 相对 v3 的改动
 * - **删除预览态**：页面只剩"可编辑"一种渲染，`EditorMode` 与整条 `LazyColumn` 只读实现一起删掉。
 *   只读态唯一不可替代的价值（看大文件）改由「下载到手机缓存后再编辑」承担；
 * - 顶栏右侧的图标按钮（保存/撤销/重做/查找）下移到独立操作栏，**图标下带文字**，
 *   形态与文件管理器工具栏一致；顶栏只留返回、文件名与「更多」；
 * - 完整路径从"顶栏第二行末尾"挪出来独占一条并允许折行 —— 之前它排在一行末尾，
 *   一长就先被省略号砍掉，用户反而看不到文件在哪；
 * - 查找栏从"嵌进 Column 流"改成**浮层**：开关它不再把正文推来推去；
 * - 编码 / 换行符改用同一个公共选择面板（`UfiChoiceSheet`）并在菜单里补上图标；
 * - 大文件（服务端只回 `reason=too_large`）支持整份下载到手机缓存后就地编辑，
 *   配套 LRU 缓存治理与「清理编辑缓存」入口。
 *
 * ## v3 已经修掉的（保留说明，避免回退）
 * - 保存是 fire-and-forget：点下去立刻显示"已保存"并清脏标记，写失败时用户看到的是成功，
 *   退出守卫也一并失效。现在等回包，失败保留脏标记；
 * - 错误弹窗关不掉：dismiss 调的函数只清 `operationMessage` 而错误在 `errorMessage`。
 *   现在读取错误是本页私有状态，不再与其它文件操作共用槽位；
 * - 正文状态是 `String`，拿不到光标，所以底栏那个「第 N 行」一直显示的是总行数；
 * - 「过大 / 二进制 / 不存在」三种情况共用一句「服务端已截断（>256KB）」，而真实上限是 512KB。
 *   现在由服务端回 `reason` 区分，三种文案与兜底动作各不相同。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    filePath: String
) {
    val palette = LocalResolvedPalette.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val state = rememberTextViewerState(filePath, MAX_READ_KB)

    val fileName = remember(filePath) { filePath.substringAfterLast('/') }

    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var showLeaveDialog by rememberSaveable(filePath) { mutableStateOf(false) }
    var showGoToLine by rememberSaveable(filePath) { mutableStateOf(false) }
    var showHugeEditConfirm by rememberSaveable(filePath) { mutableStateOf(false) }
    var showEncodingSheet by rememberSaveable(filePath) { mutableStateOf(false) }
    var showEolSheet by rememberSaveable(filePath) { mutableStateOf(false) }

    // ── 大文件的缓存下载状态 ──
    // 只订阅自己关心的三项，并且写进 mutableStateOf —— 等值写入不触发重组，
    // 所以正文字段不会被每 300ms 一次的进度回报刷屏；进度再按 5% 取整进一步降频。
    var fetching by remember { mutableStateOf(false) }
    var fetchPercent by remember { mutableIntStateOf(-1) }
    var cacheLabel by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.files.refreshEditorCacheUsage()
        viewModel.files.state.collect { s ->
            fetching = s.editorFetching
            fetchPercent = if (s.editorFetchProgress < 0f) {
                -1
            } else {
                (s.editorFetchProgress * 20).toInt().coerceIn(0, 20) * 5
            }
            cacheLabel = if (s.editorCacheCount == 0) {
                ""
            } else {
                "清理编辑缓存（${FormatUtils.formatSize(s.editorCacheBytes)}）"
            }
        }
    }

    /**
     * 读文件。本地副本已建立就读本地，否则走设备端 API。
     *
     * [encoding] 变化时也走这里 —— 「以 GBK 重新打开」就是换个编码再读一次。
     */
    val load: (String) -> Unit = { encoding ->
        state.loading = true
        state.loadError = null
        val local = state.localPath?.let(::File)
        scope.launch {
            val result = if (local != null) {
                viewModel.files.readLocalTextFile(local, encoding)
            } else {
                viewModel.files.readTextFile(filePath, encoding)
            }
            result
                .onSuccess { state.adopt(it) }
                .onFailure {
                    state.loading = false
                    state.loadError = it.message ?: "读取失败"
                }
        }
    }

    /**
     * 大文件：整份下载到手机缓存后再打开。
     *
     * 服务端对超过单次可读上限的文件只回 `reason=too_large`、不回内容，所以只能在手机侧
     * 拿一份完整副本；下载完成后本次会话改为读写该副本（见 [TextViewerState.localPath]）。
     */
    val fetchLocalCopy: () -> Unit = {
        scope.launch {
            viewModel.files.fetchToEditorCache(filePath, fileName)
                .onSuccess { file ->
                    state.localPath = file.absolutePath
                    load(state.encoding)
                }
                .onFailure {
                    toastMessage = ToastMessage(it.message ?: "下载失败", ToastType.ERROR)
                }
        }
    }

    // 只在「换文件」或「恢复后正文是空的」时才读：带着未保存改动转屏回来不能被磁盘内容盖掉
    LaunchedEffect(filePath) {
        if (state.dirty && state.value.text.isNotEmpty()) {
            state.loading = false
        } else {
            load(state.encoding)
        }
    }

    // 超过直接编辑上限（128KB / 4000 行）时**立刻**问一次，而不是先摆一个输入不进去的页面
    LaunchedEffect(state.usable, state.loading, state.withinEditableSize) {
        if (state.usable && !state.loading && !state.withinEditableSize && !state.hugeEditConfirmed) {
            showHugeEditConfirm = true
        }
    }

    // ── 查找：debounce + 后台扫描 ──
    // 每敲一个字符全文扫一遍会掉帧，所以隔 200ms 再扫，且扫描本身挪到 Default 线程。
    LaunchedEffect(state.query, state.caseSensitive, state.value.text) {
        if (state.query.isEmpty()) {
            state.matches = emptyList()
            state.matchIndex = 0
            return@LaunchedEffect
        }
        delay(FIND_DEBOUNCE_MS)
        val text = state.value.text
        val found = withContext(Dispatchers.Default) {
            findMatches(text, state.query, state.caseSensitive)
        }
        state.matches = found
        state.matchIndex = state.matchIndex.coerceIn(0, (found.size - 1).coerceAtLeast(0))
    }

    /**
     * 把第 [index] 个命中带进视野。
     *
     * 只剩"可编辑"一种渲染，所以永远是移动光标选中命中区间 —— 光标一动，
     * 输入法自己就会把该处滚进视野，"查看态滚列表"那条分支随只读态一起删掉了。
     */
    val focusMatch: (Int) -> Unit = { index ->
        val hit = state.matches.getOrNull(index)
        if (hit != null) {
            state.matchIndex = index
            state.moveSelection(TextRange(hit.first, hit.last + 1))
        }
    }

    val save: () -> Unit = {
        state.saving = true
        scope.launch {
            val payload = restoreEol(state.value.text, state.eol)
            val local = state.localPath?.let(::File)
            if (local != null) {
                viewModel.files.writeLocalTextFile(local, payload, state.encoding)
                    .onSuccess {
                        state.onSaved(it)
                        toastMessage = ToastMessage("已保存到手机缓存", ToastType.SUCCESS)
                    }
                    .onFailure {
                        toastMessage = ToastMessage(it.message ?: "保存失败", ToastType.ERROR)
                    }
            } else {
                viewModel.files.writeTextFile(filePath, payload, state.encoding)
                    .onSuccess {
                        val charset = runCatching { Charset.forName(state.encoding) }
                            .getOrDefault(Charsets.UTF_8)
                        state.onSaved(payload.toByteArray(charset).size.toLong())
                        toastMessage = ToastMessage("已保存", ToastType.SUCCESS)
                    }
                    .onFailure {
                        // 关键：不清脏标记。保存失败后退出仍要拦一次，否则改动就这么丢了
                        toastMessage = ToastMessage(it.message ?: "保存失败", ToastType.ERROR)
                    }
            }
            state.saving = false
        }
    }

    val confirmExit: () -> Unit = {
        if (state.dirty) showLeaveDialog = true else navController.popBackStack()
    }
    BackHandler(enabled = true, onBack = confirmExit)

    val overflow = buildList {
        if (state.usable) {
            add(UfiPopupOption(
                id = "goto",
                label = "跳转行",
                icon = Icons.Default.Numbers,
                onClick = { showGoToLine = true }
            ))
            add(UfiPopupOption(
                id = "wrap",
                label = "自动换行",
                icon = Icons.AutoMirrored.Filled.WrapText,
                isSelected = state.softWrap,
                onClick = { state.softWrap = !state.softWrap }
            ))
            add(UfiPopupOption(
                id = "lineno",
                label = "行号",
                icon = Icons.Default.FormatListNumbered,
                isSelected = state.showLineNumbers,
                onClick = { state.showLineNumbers = !state.showLineNumbers }
            ))
            add(UfiPopupOption(
                id = "copy",
                label = "复制全文",
                icon = Icons.Default.ContentCopy,
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.value.text))
                    toastMessage = ToastMessage("已复制全文", ToastType.SUCCESS)
                }
            ))
        }
        // 这两项此前没有 icon，菜单里只有它们左边空一格，"缺图标"就是从这看出来的
        add(UfiPopupOption(
            id = "encoding",
            label = "编码：${encodingLabel(state.encoding)}",
            icon = Icons.Default.Translate,
            onClick = { showEncodingSheet = true }
        ))
        add(UfiPopupOption(
            id = "eol",
            label = "换行符：${state.eol.label}",
            icon = Icons.AutoMirrored.Filled.KeyboardReturn,
            onClick = { showEolSheet = true }
        ))
        add(UfiPopupOption(
            id = "reload",
            label = "重新加载",
            icon = Icons.Default.Refresh,
            onClick = {
                if (state.dirty) {
                    toastMessage = ToastMessage("有未保存的改动，请先保存或放弃", ToastType.WARNING)
                } else load(state.encoding)
            }
        ))
        if (state.localCopy) {
            add(UfiPopupOption(
                id = "export",
                label = "另存到手机",
                icon = Icons.Default.SaveAlt,
                onClick = {
                    val local = state.localPath?.let(::File)
                    if (local == null) {
                        toastMessage = ToastMessage("本地副本不存在", ToastType.WARNING)
                    } else {
                        scope.launch {
                            viewModel.files.exportLocalToDownloads(local, fileName)
                                .onSuccess {
                                    toastMessage = ToastMessage("已保存到 Download/$fileName", ToastType.SUCCESS)
                                }
                                .onFailure {
                                    toastMessage = ToastMessage(it.message ?: "保存失败", ToastType.ERROR)
                                }
                        }
                    }
                }
            ))
        } else {
            add(UfiPopupOption(
                id = "download",
                label = "下载到手机",
                icon = Icons.Default.Download,
                onClick = {
                    viewModel.files.downloadFileToPhone(filePath, fileName)
                    toastMessage = ToastMessage("已开始下载", ToastType.INFO)
                }
            ))
        }
        if (cacheLabel.isNotEmpty()) {
            add(UfiPopupOption(
                id = "clearcache",
                label = cacheLabel,
                icon = Icons.Default.DeleteSweep,
                onClick = {
                    val (bytes, count) = viewModel.files.clearEditorCache()
                    toastMessage = ToastMessage(
                        "已清理 $count 个副本，释放 ${FormatUtils.formatSize(bytes)}",
                        ToastType.SUCCESS
                    )
                }
            ))
        }
    }

    Scaffold(
        topBar = {
            // topBar 用一个 Column 承载三件事：顶栏（返回 / 文件名 / 更多）、可换行的完整路径、
            // 图标带文字的操作栏。M3 的 TopAppBar 高度固定 64dp，折行路径塞不进去，
            // 所以路径必须独占一条（见 EditorPathHeader）。
            Column {
                TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                            Text(
                                text = fileName.ifBlank { filePath },
                                fontWeight = UfiWeight.Emphasis,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = metaLine(state, fetching, fetchPercent),
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
                        // 保存 / 撤销 / 重做 / 查找已下移到 EditorToolbar（图标下带文字），
                        // 顶栏右上角只留「更多」—— 低频动作本来就该收进菜单。
                        UfiPopupMenuButton(options = overflow)
                    }
                )
                EditorToolbar(
                    path = filePath,
                    canSave = state.dirty && !state.saving,
                    canUndo = state.undo.canUndo,
                    canRedo = state.undo.canRedo,
                    findActive = state.findVisible,
                    onSave = save,
                    onUndo = { state.applyUndo() },
                    onRedo = { state.applyRedo() },
                    onFind = { state.findVisible = !state.findVisible }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.encodingSuspect && state.usable) {
                UfiNoticeCard(
                    message = "以 UTF-8 解码时出现了无法识别的字节，这个文件可能是 GBK。" +
                        "可从右上角菜单换用其它编码重新打开。"
                )
                Spacer(Modifier.height(Spacing.Small))
            }

            // 正文区与查找浮层同处一个 Box：查找栏覆盖在正文**之上**，不参与正文的布局，
            // 因此开关它、展收替换行都不会把用户正在看的那一行推走。
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.PagePadding, vertical = Spacing.Small)
                        .clip(MaterialTheme.shapes.medium)
                        .background(palette.cardBg)
                        .border(
                            Spacing.NoticeBorderWidth,
                            palette.divider.copy(alpha = BORDER_ALPHA),
                            MaterialTheme.shapes.medium
                        )
                ) {
                    when {
                        fetching -> CenteredProgress(
                            message = "正在下载到手机缓存…",
                            percent = fetchPercent
                        )
                        state.loading -> LoadingSkeleton()
                        state.loadError != null -> CenteredNotice(
                            message = state.loadError ?: "",
                            actionLabel = "重试",
                            onAction = { load(state.encoding) }
                        )
                        !state.usable -> CenteredNotice(
                            message = unusableMessage(state.reason ?: "", MAX_READ_KB),
                            actionLabel = "下载后编辑".takeIf {
                                state.reason == TextFileContent.REASON_TOO_LARGE
                            },
                            onAction = fetchLocalCopy
                        )
                        else -> TextContentView(state = state, modifier = Modifier.fillMaxSize())
                    }
                }

                // 浮层：不参与正文布局，所以开关它不会引起任何位置变动。
                // 用 AnimatedVisibility 包裹：打开/关闭带淡入淡出 + 轻微上滑，而不是硬切。
                // align(TopCenter) 放在 AnimatedVisibility 自身（它是外层 Box 的直接子节点，
                // 仍在 BoxScope 内），FindReplaceBar 内部不再带 align。
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.findVisible && state.usable,
                    enter = fadeIn(animationSpec = tween(UfiMotion.Duration.Fluid, easing = UfiAnimSpecs.emphasizedInEasing))
                        + slideInVertically(initialOffsetY = { -it }, animationSpec = tween(UfiMotion.Duration.Fluid, easing = UfiAnimSpecs.emphasizedInEasing)),
                    exit = fadeOut(animationSpec = tween(UfiMotion.Duration.Fluid, easing = UfiAnimSpecs.emphasizedOutEasing))
                        + slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(UfiMotion.Duration.Fluid, easing = UfiAnimSpecs.emphasizedOutEasing)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = Spacing.PagePadding, vertical = Spacing.Small)
                ) {
                    FindReplaceBar(
                        state = state,
                        onQueryChange = { state.query = it; state.matchIndex = 0 },
                        onPrev = {
                            val size = state.matches.size
                            if (size > 0) focusMatch((state.matchIndex - 1 + size) % size)
                        },
                        onNext = {
                            val size = state.matches.size
                            if (size > 0) focusMatch((state.matchIndex + 1) % size)
                        },
                        onReplaceCurrent = {
                            val hit = state.matches.getOrNull(state.matchIndex)
                            if (hit != null) {
                                val text = state.value.text
                                val next = text.substring(0, hit.first) +
                                    state.replacement + text.substring(hit.last + 1)
                                state.replaceAll(
                                    TextFieldValue(
                                        next,
                                        TextRange(hit.first + state.replacement.length)
                                    ),
                                    System.currentTimeMillis()
                                )
                            }
                        },
                        onReplaceAll = {
                            val (next, count) = replaceAllOccurrences(
                                state.value.text, state.query, state.replacement, state.caseSensitive
                            )
                            state.replaceAll(TextFieldValue(next, TextRange.Zero), System.currentTimeMillis())
                            toastMessage = ToastMessage("已替换 $count 处", ToastType.SUCCESS)
                        },
                        onClose = {
                            state.findVisible = false
                            state.query = ""
                            state.matches = emptyList()
                        }
                    )
                }
            }

            EditorStatusBar(state = state)
        }
    }

    if (showLeaveDialog) {
        UfiConfirmDialog(
            title = "未保存的修改",
            text = "当前有未保存的更改，离开后会丢失。",
            confirmText = "放弃修改",
            destructive = true,
            onConfirm = {
                showLeaveDialog = false
                state.dirty = false
                navController.popBackStack()
            },
            dismissText = "继续编辑",
            onDismiss = { showLeaveDialog = false }
        )
    }

    if (showGoToLine) {
        UfiInputDialog(
            title = "跳转行",
            initialValue = "",
            hint = "1 - ${state.lineCount}",
            confirmText = "跳转",
            validator = { txt ->
                val n = txt.toIntOrNull()
                when {
                    n == null -> "请输入数字"
                    n < 1 || n > state.lineCount -> "行号越界"
                    else -> null
                }
            },
            onConfirm = { txt ->
                showGoToLine = false
                val line = (txt.toIntOrNull() ?: 1).coerceIn(1, state.lineCount)
                // 只剩"可编辑"一种渲染：跳转 = 把光标挪到该行行首，输入法会自己把它滚进视野
                state.moveSelection(TextRange(state.lineStarts[line - 1]))
            },
            onDismiss = { showGoToLine = false }
        )
    }

    if (showHugeEditConfirm) {
        UfiConfirmDialog(
            title = "编辑大文件",
            text = "这个文件有 ${FormatUtils.formatSize(state.fileSize)}（${state.lineCount} 行）。" +
                "编辑器把整篇文本作为一个输入框处理，输入时可能明显卡顿。" +
                "本页只有编辑态，取消会返回文件列表；也可以先用「下载到手机」把文件取回手机。",
            confirmText = "仍要编辑",
            onConfirm = {
                showHugeEditConfirm = false
                state.hugeEditConfirmed = true
            },
            dismissText = "返回列表",
            // 取消 = 离开本页。页面只剩编辑一种能力，留在这里只能看一份改不了的文本 ——
            // 那正是这次要删掉的"预览态"。
            onDismiss = {
                showHugeEditConfirm = false
                navController.popBackStack()
            }
        )
    }

    if (showEncodingSheet) {
        UfiChoiceSheet(
            title = "以其它编码重新打开",
            options = EDITOR_ENCODINGS.map { it to encodingLabel(it) },
            selectedValue = state.encoding,
            onDismiss = { showEncodingSheet = false },
            onSelect = { picked ->
                showEncodingSheet = false
                if (state.dirty) {
                    toastMessage = ToastMessage("有未保存的改动，请先保存或放弃", ToastType.WARNING)
                } else {
                    state.encoding = picked
                    load(picked)
                }
            }
        )
    }

    // 换行符与编码同一种交互：都是"二选一/三选一"，所以共用公共的 UfiChoiceSheet，
    // 不再用一个点一下就默默翻转的菜单项（翻转没有回显，用户不知道当前到底是哪个）。
    if (showEolSheet) {
        UfiChoiceSheet(
            title = "换行符（保存时写入）",
            options = Eol.entries.map { it.name to it.label },
            selectedValue = state.eol.name,
            onDismiss = { showEolSheet = false },
            onSelect = { picked ->
                showEolSheet = false
                val next = Eol.entries.firstOrNull { it.name == picked }
                if (next != null && next != state.eol) {
                    state.eol = next
                    if (state.usable) state.dirty = true
                    toastMessage = ToastMessage("保存时将使用 ${next.label}", ToastType.INFO)
                }
            }
        )
    }

    UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
}

// ─────────────────────────────────────────────────────────────────────────────
//  局部件
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LoadingSkeleton() {
    // 首屏用骨架线而不是转圈：能预示"接下来是一屏等宽文本"，
    // 而大文件从设备读回来可能要好几秒。
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.InnerPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
    ) {
        repeat(SKELETON_LINES) { index ->
            UfiSkeletonLine(widthFraction = if (index % 4 == 3) 0.5f else 0.95f)
        }
    }
}

@Composable
private fun CenteredNotice(
    message: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.XLarge),
        verticalArrangement = Arrangement.spacedBy(Spacing.Large, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary
        )
        if (actionLabel != null) {
            UfiButton(text = actionLabel, size = UfiButtonSize.Small, onClick = onAction)
        }
    }
}

/**
 * 缓存下载中的占位。
 *
 * 大文件（>512KB）整份拉回来可能要几十秒，所以给的是**确定进度**而不是转圈：
 * [percent] 为 -1（服务端没给 `Content-Length`）时才退回不确定形态。
 */
@Composable
private fun CenteredProgress(message: String, percent: Int) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.XLarge),
        verticalArrangement = Arrangement.spacedBy(Spacing.Large, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary
        )
        if (percent in 0..100) {
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.titleMedium,
                color = palette.accent
            )
        }
        val barModifier = Modifier.fillMaxWidth().height(4.dp)
        if (percent in 0..100) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                color = palette.accent,
                trackColor = palette.divider,
                modifier = barModifier
            )
        } else {
            LinearProgressIndicator(
                color = palette.accent,
                trackColor = palette.divider,
                modifier = barModifier
            )
        }
    }
}

/**
 * 顶栏第二行：`[本地副本 · ]大小 · 编码 · 换行符`。
 *
 * 完整路径现在和操作栏（保存 / 撤销 / 重做 / 查找）共占一条，由 [EditorToolbar] 左侧的
 * folder 图标 + 单行省略文本承载（面包屑语义，过长只截中间不撑高操作栏）。这一行的
 * `metaLine` 只负责"大小 / 编码 / 换行符 / 本地副本"这类文件级信息。
 * 加载中 / 下载中 / 出错各有各的说法。
 */
private fun metaLine(state: TextViewerState, fetching: Boolean, fetchPercent: Int): String = when {
    fetching -> buildString {
        append("正在下载到手机缓存")
        if (fetchPercent in 0..100) append(" $fetchPercent%")
    }
    state.loading -> "正在加载…"
    state.loadError != null -> state.loadError ?: ""
    !state.usable -> "${FormatUtils.formatSize(state.fileSize)} · 需下载后编辑"
    else -> buildString {
        if (state.localCopy) append("本地副本 · ")
        append(FormatUtils.formatSize(state.fileSize))
        append(" · ")
        append(encodingLabel(state.encoding))
        append(" · ")
        append(state.eol.label)
    }
}

/**
 * 不重叠地替换全部命中，并返回 `(替换后文本, 实际替换处数)`。
 *
 * 不用 `String.replace`：它不支持"忽略大小写"（Kotlin 的 `replace(oldValue, newValue, ignoreCase)`
 * 支持，但会把所有大小写变体都换成同一份 replacement，这正是我们要的行为）—— 真正的原因是
 * 这里要与查找的命中口径完全一致（不重叠），所以自己走一遍。
 *
 * 返回的处数**不封顶**：查找 UI 受 `MAX_MATCHES=500` 限制只显示前 500 处，但"全部替换"应当
 * 替换所有命中，toast 也该报真实数量（旧实现报 `matches.size`，超 500 处时会少报）。
 */
private fun replaceAllOccurrences(
    text: String,
    query: String,
    replacement: String,
    caseSensitive: Boolean
): Pair<String, Int> {
    if (query.isEmpty()) return text to 0
    val sb = StringBuilder(text.length)
    var from = 0
    var count = 0
    while (true) {
        val at = text.indexOf(query, from, ignoreCase = !caseSensitive)
        if (at < 0) {
            sb.append(text, from, text.length)
            break
        }
        sb.append(text, from, at).append(replacement)
        from = at + query.length
        count++
    }
    return sb.toString() to count
}

/** 服务端单次可读上限（KB），与 `FileRoutes.MAX_READ_SIZE` 对齐，仅用于文案。 */
private const val MAX_READ_KB = 512

/** 查找输入的防抖窗口。 */
private const val FIND_DEBOUNCE_MS = 200L

private const val SKELETON_LINES = 12
private const val BORDER_ALPHA = 0.5f
