package com.ufi_axis.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.AppLogBuffer
import com.ufi_axis.util.AppFileLogger
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.LogExportMeta
import com.ufi_axis.util.buildLogBundleZip
import com.ufi_axis.util.buildLogExportText
import com.ufi_axis.util.buildLogFileExportText
import com.ufi_axis.util.defaultLogBundleFileName
import com.ufi_axis.util.defaultLogExportFileName
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.util.LogEntry
import com.ufi_axis.util.LogFileInfo
import com.ufi_axis.util.LogUsage
import com.ufi_axis.util.LogKind
import com.ufi_axis.util.LogLevel
import com.ufi_axis.util.LogSource
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 日志页（2026-08-27 重构为双端日志查看器）。
 *
 * 两个来源同构（都是 [LogEntry]）：
 * - **手机 APP** ← [AppLogBuffer]（进程内 500 条环形缓冲，`DebugLog` 与 `NetworkLogInterceptor` 写入）；
 * - **设备 core** ← `ToolsModule.loadDebugLogs()` 拉回字符串行后解析。
 *
 * 筛选维度：来源（页签）× 类型（运行/网络）× 级别 × 关键字，全部本地做 —— 切筛选不发请求。
 * 类型靠 tag 前缀 `NET` 判定（两端约定，见 [LogKind.of]）。
 *
 * **屏幕预算**：日志正文优先，但**开关必须看得见**。
 * 顶部常驻两行 chip —— 四个日志开关（总开关 / 手机端 / 后端 / 详细）与带条数的级别筛选；
 * 类型与关键字仍收在 [UfiCustomDialog]（顶栏漏斗图标，激活时高亮），
 * 批量操作与日志文件收在 [UfiPopupMenuButton]。
 *
 * 2026-09-04 调整原因：开关原来全在溢出菜单里，「详细日志」这种来这页就是为了改的东西
 * 要点两下才够到，且菜单不打开就看不出当前是开还是关。chip 的选中态本身就是状态显示。
 * 更早（2026-08-27）曾把搜索框 + 两行 chip 全常驻，正文只剩小半屏 —— 所以只放出这两行。
 *
 * 刷新用**定时轮询**而非订阅缓冲区写入事件：网络日志默认常开，
 * 逐条订阅会让每写一条日志都触发一次 500 元素快照 + 过滤重组。
 */
@Composable
fun DebugLogScreen(viewModel: MainViewModel, navController: NavHostController) {
    val context = LocalContext.current
    val palette = LocalResolvedPalette.current
    val scope = rememberCoroutineScope()
    val coreState by viewModel.debugLogState.collectAsState()
    val logFilesState by viewModel.tools.coreLogFilesState.collectAsState()

    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var showClearRealtimeConfirm by remember { mutableStateOf(false) }
    var showDeleteAllFilesConfirm by remember { mutableStateOf(false) }
    // ── 方案 C（2026-10-30）：状态卡 + 表格 + 两个底部弹层 ──
    var filterSheetVisible by remember { mutableStateOf(false) }
    var exportSheetVisible by remember { mutableStateOf(false) }
    var detailEntry by remember { mutableStateOf<LogEntry?>(null) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var coreUptimeMs by remember { mutableStateOf<Long?>(null) }
    var lastExport by remember { mutableStateOf<LastExport?>(null) }

    // ── 筛选态（纯展示，留在 UI；rememberSaveable 只存可入 Bundle 的基本类型）──
    var sourceIndex by rememberSaveable { mutableIntStateOf(0) }
    var kindFilter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var levelFilter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var follow by rememberSaveable { mutableStateOf(true) }
    var logFilesDialogVisible by remember { mutableStateOf(false) }
    // APP 侧日志文件在手机本地（AppFileLogger），直接读；core 侧走 API（ToolsModule）。
    // 两套状态刻意分开：清空只作用于当前页签那一侧，绝不会连带删掉另一侧。
    var appLogFiles by remember { mutableStateOf<List<LogFileInfo>>(emptyList()) }
    var appLogTotalBytes by remember { mutableLongStateOf(0L) }
    // 2026-10-30：app 侧占用摘要（总量/今日/跨度/剩余），与 core 侧同一组字段
    var appLogUsage by remember { mutableStateOf(LogUsage()) }
    var appViewingName by remember { mutableStateOf<String?>(null) }
    var appViewingText by remember { mutableStateOf<String?>(null) }
    var appFilesReloadTick by remember { mutableIntStateOf(0) }
    val source = if (sourceIndex == 0) LogSource.APP else LogSource.CORE

    // ── 四层开关：唯一真源在 core，UI 只读 ToolsModule 的镜像 ──
    // 2026-09-04：这里原来是 `remember { mutableStateOf(prefs.xxx) }` —— 本地 SharedPreferences
    // 直接当真值用，点一下先改本地再 best-effort 下发。下发失败时手机端显示"已关闭"、
    // core 仍然是开、web 读 core 显示"开启"，且没有任何提示。现在镜像只在 PUT 被 core 采纳后
    // 才变，所以「界面上的开关位置」== 「core 里的实际值」。
    val logSwitches by viewModel.tools.logSwitchState.collectAsState()
    val logEnabled = logSwitches.logEnabled
    val appLogEnabled = logSwitches.appLogEnabled
    val coreLogEnabled = logSwitches.coreLogEnabled
    val debugMode = logSwitches.debugMode
    // 当前页签这一侧是否真在记录：总闸 + 对应子开关
    val sideEnabled = logEnabled && if (source == LogSource.APP) appLogEnabled else coreLogEnabled

    // 开关下发失败 / 回读失败统一走 toast。清掉 state 里的错误，否则每次重组都会再弹一次。
    LaunchedEffect(logSwitches.errorMessage) {
        logSwitches.errorMessage?.let {
            toastMessage = ToastMessage(it, ToastType.ERROR, durationMs = TOAST_LONG_MS)
        }
    }

    // ── 多选（区间复制/导出）──
    var selectionMode by remember { mutableStateOf(false) }
    var selectionStart by remember { mutableIntStateOf(-1) }
    var selectionEnd by remember { mutableIntStateOf(-1) }
    // 进多选时冻结列表快照：否则后台定时刷新会让已选下标漂到别的行上
    var frozen by remember { mutableStateOf<List<LogEntry>?>(null) }

    fun exitSelectionMode() {
        selectionMode = false
        selectionStart = -1
        selectionEnd = -1
        frozen = null
    }

    // ── 日志导出（2026-10-30 M1：SAF 选位置，默认导出“记录”而不是落盘文件）──
    //
    // 文本在 launch 之前拼好并冻结：SAF 回调是异步的，期间用户可能切页签、改筛选、
    // 或后台定时刷新把列表换掉 —— 那些都不该改变“我点导出那一刻看到的东西”。
    var pendingExport by remember { mutableStateOf<LogExportPending?>(null) }
    var exportResult by remember { mutableStateOf<LogExportResult?>(null) }
    var exportBusy by remember { mutableStateOf(false) }

    /** 分享一个 SAF uri（结果对话框与导出工作表共用）。 */
    fun shareUri(uri: Uri, mime: String) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "分享日志"))
        }.onFailure {
            toastMessage = ToastMessage("没有可用的分享应用", ToastType.ERROR)
        }
    }

    /** App 版本名：每个导出文件都要写；取不到就 unknown（不能因为读版本失败而不让导出）。 */
    fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    /** 两个 SAF 选择器（文本 / ZIP）共用的写入逻辑。 */
    fun finishExport(uri: Uri?) {
        val pending = pendingExport
        pendingExport = null
        if (uri == null || pending == null) {
            toastMessage = ToastMessage("已取消导出", ToastType.INFO)
            return
        }
        scope.launch {
            exportBusy = true
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(pending.bytes)
                    } ?: error("无法打开所选位置")
                }
            }
            exportBusy = false
            outcome.fold(
                onSuccess = {
                    exportResult = LogExportResult(
                        uri = uri,
                        count = pending.count,
                        bytes = pending.bytes.size,
                        unit = pending.unit,
                        skipped = pending.skipped,
                        mime = pending.mime
                    )
                    lastExport = LastExport(
                        uri = uri,
                        exportedAt = System.currentTimeMillis(),
                        bytes = pending.bytes.size,
                        count = pending.count,
                        unit = pending.unit,
                        mime = pending.mime
                    )
                    // 只有“记录导出”且成功才退出多选；取消/失败保留选择，用户可以直接重试
                    if (pending.exitSelectionOnSuccess) exitSelectionMode()
                },
                onFailure = { e ->
                    toastMessage = ToastMessage(
                        "写入失败：${e.message ?: "未知原因"}",
                        ToastType.ERROR,
                        durationMs = TOAST_LONG_MS
                    )
                }
            )
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> finishExport(uri) }
    val bundleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> finishExport(uri) }

    fun startLogExport(entries: List<LogEntry>, isSelection: Boolean) {
        if (exportBusy) return
        if (entries.isEmpty()) {
            toastMessage = ToastMessage("当前筛选没有日志，未导出", ToastType.INFO)
            return
        }
        scope.launch {
            exportBusy = true
            // 设备型号要额外一次请求：失败就省略该行，绝不阻塞导出
            val model = if (source == LogSource.CORE) {
                runCatching { viewModel.tools.fetchDeviceModelForExport() }.getOrNull()
            } else {
                null
            }
            val meta = LogExportMeta(
                exportedAt = System.currentTimeMillis(),
                appVersionName = appVersionName(),
                source = source,
                deviceBaseUrl = if (source == LogSource.CORE) AppPreferences(context).baseUrl else "",
                deviceModel = model,
                kind = if (kindFilter == FILTER_ALL) {
                    null
                } else {
                    runCatching { LogKind.valueOf(kindFilter) }.getOrNull()
                },
                level = if (levelFilter == FILTER_ALL) {
                    null
                } else {
                    runCatching { LogLevel.valueOf(levelFilter) }.getOrNull()
                },
                query = query,
                entries = entries.toList(),
                isSelection = isSelection
            )
            val bytes = withContext(Dispatchers.Default) {
                buildLogExportText(meta).toByteArray(Charsets.UTF_8)
            }
            pendingExport = LogExportPending(
                bytes = bytes,
                count = entries.size,
                unit = "条",
                exitSelectionOnSuccess = isSelection,
                mime = "text/plain"
            )
            exportBusy = false
            exportLauncher.launch(defaultLogExportFileName(meta))
        }
    }

    // ── 刷新节拍：前台 + 非多选时才走，切到后台/进多选立刻停 ──
    var isScreenActive by remember { mutableStateOf(true) }
    val lifecycleOwner = remember(context) { context as? androidx.lifecycle.LifecycleOwner }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isScreenActive = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(source) {
        if (source == LogSource.CORE) {
            viewModel.tools.loadDebugLogs()
            // 设备卡要显示今日写盘/剩余空间/运行时长（方案 C）；失败就显示 —
            viewModel.tools.refreshCoreLogUsage()
            coreUptimeMs = viewModel.tools.fetchServiceUptimeMs()
        } else {
            appLogUsage = withContext(Dispatchers.IO) { AppFileLogger.usage() }
        }
    }
    // 进页面就从 core 回读一次四层开关：本地缓存可能已被别端（web / 另一台手机）改过，
    // 也可能上一次下发失败过。没有这一步，开关显示的仍是本机的一厢情愿。
    LaunchedEffect(Unit) {
        viewModel.tools.loadLogSwitches()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_MS)
            if (isScreenActive && !selectionMode && !paused) {
                if (source == LogSource.CORE) viewModel.tools.loadDebugLogs() else tick++
            }
        }
    }

    // ── 数据源 ──
    val appSnapshot = remember(tick) { AppLogBuffer.snapshot() }

    // APP 侧日志文件：弹窗打开时读一次（IO 线程，别在主线程碰文件——debug 构建有 StrictMode）
    LaunchedEffect(logFilesDialogVisible, exportSheetVisible, source, appFilesReloadTick) {
        if ((logFilesDialogVisible || exportSheetVisible) && source == LogSource.APP) {
            val loaded = withContext(Dispatchers.IO) {
                Triple(AppFileLogger.listFiles(), AppFileLogger.totalBytes(), AppFileLogger.usage())
            }
            appLogFiles = loaded.first
            appLogTotalBytes = loaded.second
            appLogUsage = loaded.third
        }
    }
    LaunchedEffect(appViewingName) {
        val name = appViewingName
        if (name == null) {
            appViewingText = null
        } else {
            appViewingText = withContext(Dispatchers.IO) { AppFileLogger.readTail(name) } ?: "（读取失败）"
        }
    }
    val liveEntries = if (source == LogSource.APP) appSnapshot else coreState.coreEntries
    val entries = frozen ?: liveEntries

    // 类型筛选先过一遍：级别 chip 上的条数要反映「当前类型下」的分布，否则数字对不上眼前的列表
    val kindMatched = remember(entries, kindFilter) {
        if (kindFilter == FILTER_ALL) entries else entries.filter { it.kind.name == kindFilter }
    }
    val levelCounts = remember(kindMatched) { kindMatched.groupingBy { it.level }.eachCount() }
    val visible = remember(kindMatched, levelFilter, query) {
        kindMatched.filter { e ->
            (levelFilter == FILTER_ALL || e.level.name == levelFilter) &&
                (query.isBlank() ||
                    e.message.contains(query, ignoreCase = true) ||
                    e.tag.contains(query, ignoreCase = true))
        }
    }
    val levelOptions = remember(levelCounts, kindMatched) {
        listOf(FILTER_ALL to "全部 ${kindMatched.size}") +
            LogLevel.entries.map { it.name to "${it.label}·${levelCounts[it] ?: 0}" }
    }

    // ── 四个日志开关：2026-10-30 全部放在设备卡里（用户反馈“设置太分散”）──
    // 总开关控制“记不记”，三个子开关控制“哪一侧 / 多详细”；总开关关闭时子开关禁用。
    // 不再要求 loaded：未同步时也允许写，写入后由 ToolsModule 回读确认。
    val switchesWritable = !logSwitches.isSaving

    val selectedCount = when {
        selectionStart < 0 -> 0
        selectionEnd < 0 -> 1
        else -> maxOf(selectionStart, selectionEnd) - minOf(selectionStart, selectionEnd) + 1
    }

    fun selectedLogs(): List<LogEntry> {
        if (selectionStart < 0) return emptyList()
        val start = minOf(selectionStart, selectionEnd.takeIf { it >= 0 } ?: selectionStart)
        val end = maxOf(selectionStart, selectionEnd.takeIf { it >= 0 } ?: selectionStart)
        if (start >= visible.size) return emptyList()
        return visible.subList(start, (end + 1).coerceAtMost(visible.size))
    }

    fun copyToClipboard(logs: List<LogEntry>, hint: String) {
        val text = logs.joinToString("\n") { it.format() }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ufi-axis-log", text))
        toastMessage = ToastMessage(hint, ToastType.SUCCESS)
    }

    // ── 跟随最新：可关闭，否则往上翻历史会被下一次刷新拽回底部 ──
    val listState = rememberLazyListState()
    // ★ 2026-11-03（进本页转场掉 2 帧、最差 50ms）：首次落位必须**瞬移**，不能走动画。
    //
    // 进页时缓冲里已有 500 条（AppLogBuffer 上限），`visible.size - 1` = 499：
    // 从 0 动画滚到 499 会让 LazyColumn 在转场那几帧里边滚边量几百个条目。真机日志里
    // 这次转场的窗口内**零**网络请求（不是数据砸下来），掉帧只可能来自这次滚动。
    // 稳态的"跟随最新"（新日志一条条进来）仍然走动画。
    var settledOnce by remember { mutableStateOf(false) }
    LaunchedEffect(visible.size, follow, selectionMode) {
        if (follow && !selectionMode && visible.isNotEmpty()) {
            val target = visible.size - 1
            if (settledOnce) listState.animateScrollToItem(target) else listState.scrollToItem(target)
            settledOnce = true
        }
    }

    UfiScreenScaffold(
        title = if (selectionMode) "已选 $selectedCount 条" else "日志",
        navController = navController,
        showBack = !selectionMode,
        actions = {
            if (selectionMode) {
                if (selectedCount > 0) {
                    IconButton(onClick = {
                        copyToClipboard(selectedLogs(), "已复制 $selectedCount 条")
                        exitSelectionMode()
                    }) { Icon(Icons.Default.ContentCopy, "复制选中") }
                    IconButton(onClick = {
                        // 2026-10-30：导出走 SAF；成功后才退出多选（取消/失败保留选择）
                        startLogExport(selectedLogs(), isSelection = true)
                    }) { Icon(Icons.Default.FileDownload, "导出选中") }
                }
                IconButton(onClick = { exitSelectionMode() }) {
                    Icon(Icons.Default.Close, "取消选择")
                }
            } else {
                IconButton(
                    enabled = source == LogSource.CORE,
                    onClick = { viewModel.tools.loadDebugLogs() }
                ) { Icon(Icons.Default.Refresh, "刷新") }
                UfiPopupMenuButton(
                    options = buildList {
                        // 落盘日志文件：按当前页签看对应那一侧（手机本地 / 设备 core），
                        // 体积与清空都各自独立
                        add(
                            UfiPopupOption(
                                id = "logFiles",
                                label = if (source == LogSource.APP) "手机日志文件" else "设备日志文件",
                                icon = Icons.Default.Folder,
                                onClick = {
                                    logFilesDialogVisible = true
                                    if (source == LogSource.APP) {
                                        appViewingName = null
                                        appFilesReloadTick++
                                    } else {
                                        viewModel.tools.loadCoreLogFiles()
                                    }
                                }
                            )
                        )
                        if (visible.isNotEmpty()) {
                            add(UfiPopupOption.divider())
                            add(
                                UfiPopupOption(
                                    id = "select",
                                    label = "多选",
                                    icon = Icons.Default.SelectAll,
                                    onClick = {
                                        selectionMode = true
                                        selectionStart = -1
                                        selectionEnd = -1
                                        frozen = liveEntries
                                    }
                                )
                            )
                            add(
                                UfiPopupOption(
                                    id = "copyAll",
                                    label = "复制全部",
                                    icon = Icons.Default.ContentCopy,
                                    onClick = { copyToClipboard(visible, "已复制 ${visible.size} 条") }
                                )
                            )
                        }
                        add(UfiPopupOption.divider())
                        add(
                            UfiPopupOption(
                                id = "clear",
                                label = if (source == LogSource.APP) "清空实时日志（内存）" else "清空实时日志（core 缓冲）",
                                icon = Icons.Default.Delete,
                                isDestructive = true,
                                onClick = { showClearRealtimeConfirm = true }
                            )
                        )
                    }
                )
            }
        }
    ) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (selectionMode) {
                    Text(
                        text = when {
                            selectionStart < 0 -> "点击起始行"
                            selectionEnd < 0 -> "点击结束行"
                            else -> "点击重新选择"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.accent,
                        modifier = Modifier.padding(
                            horizontal = Spacing.CardHorizontalMargin,
                            vertical = Spacing.Medium
                        )
                    )
                } else {
                    // 2026-10-30 方案 C：顶部先交代状态（来源/连接/运行时长/条数/今日写盘/剩余），
                    // 控件压缩成一行，正文留给日志。
                    DeviceStatusCard(
                        source = source,
                        logEnabled = logEnabled,
                        writable = switchesWritable,
                        count = entries.size,
                        uptimeMs = coreUptimeMs,
                        todayBytes = if (source == LogSource.APP) appLogUsage.todayBytes else logFilesState.todayBytes,
                        freeBytes = if (source == LogSource.APP) appLogUsage.freeBytes else logFilesState.diskFreeBytes,
                        degraded = source == LogSource.CORE && logFilesState.degraded,
                        loading = coreState.isLoading,
                        hasError = coreState.errorMessage != null,
                        synced = logSwitches.loaded,
                        switchError = logSwitches.errorMessage,
                        appLogEnabled = appLogEnabled,
                        coreLogEnabled = coreLogEnabled,
                        debugMode = debugMode,
                        busy = logSwitches.isSaving,
                        onToggleLog = { viewModel.tools.syncLogEnabled(!logEnabled) },
                        onToggleApp = { viewModel.tools.syncAppLogEnabled(!appLogEnabled) },
                        onToggleCore = { viewModel.tools.syncCoreLogEnabled(!coreLogEnabled) },
                        onToggleVerbose = { viewModel.tools.syncDebugMode(!debugMode) },
                        modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
                    )
                    Spacer(Modifier.height(Spacing.Small))
                    LogControlsRow(
                        sourceIndex = sourceIndex,
                        onSourceSelected = { sourceIndex = it },
                        filterCount = activeFilterCount(kindFilter, levelFilter, query),
                        onSearch = { filterSheetVisible = true },
                        onFilter = { filterSheetVisible = true },
                        modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
                    )
                    Spacer(Modifier.height(Spacing.Small))
                    if (!logEnabled || !sideEnabled) {
                        Text(
                            text = when {
                                !logEnabled -> "日志已关闭（仅保留警告/错误）。点上方「记录」开启。"
                                source == LogSource.APP -> "手机端日志已关闭。"
                                else -> "后端日志已关闭。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.warning,
                            maxLines = 2,
                            modifier = Modifier.padding(
                                horizontal = Spacing.CardHorizontalMargin,
                                vertical = 2.dp
                            )
                        )
                    }
                }

                if (coreState.isLoading && source == LogSource.CORE && entries.isNotEmpty()) {
                    Text(
                        "加载中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin, vertical = 2.dp)
                    )
                }
                coreState.errorMessage?.takeIf { source == LogSource.CORE }?.let { err ->
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.error,
                        maxLines = 2,
                        modifier = Modifier.padding(
                            horizontal = Spacing.CardHorizontalMargin,
                            vertical = Spacing.Small
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when {
                        coreState.isLoading && source == LogSource.CORE && entries.isEmpty() ->
                            // 首屏骨架：与表格行高、内边距一致，加载完不跳行
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        horizontal = Spacing.CardHorizontalMargin,
                                        vertical = Spacing.Small
                                    ),
                                verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
                            ) {
                                repeat(14) { index ->
                                    UfiSkeletonLine(widthFraction = if (index % 3 == 2) 0.55f else 0.94f)
                                }
                            }

                        visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            UfiEmptyState(
                                icon = Icons.AutoMirrored.Filled.Article,
                                message = if (entries.isEmpty()) "暂无日志" else "没有符合筛选条件的日志",
                                hint = when {
                                    entries.isNotEmpty() -> "试试放宽类型 / 级别或清空搜索词"
                                    !logEnabled -> "日志总开关已关闭：仅保留警告与错误（上方「记录」开关可开启）"
                                    !sideEnabled -> "这一侧的日志开关已关闭（右上角菜单 → 日志设置）"
                                    !debugMode -> "详细日志已关闭，当前只记录警告与错误"
                                    source == LogSource.CORE -> "设备端尚未产生日志，或服务未连接"
                                    else -> null
                                }
                            )
                        }

                        else -> LogTable(
                            entries = visible,
                            listState = listState,
                            selectionMode = selectionMode,
                            selectionStart = selectionStart,
                            selectionEnd = selectionEnd,
                            onLineClick = { index, entry ->
                                if (selectionMode) {
                                    when {
                                        selectionStart < 0 -> selectionStart = index
                                        selectionEnd < 0 -> selectionEnd = index
                                        else -> {
                                            selectionStart = index
                                            selectionEnd = -1
                                        }
                                    }
                                } else {
                                    // 方案 C：点一行看详情（复制/导出在详情里），不再直接复制
                                    detailEntry = entry
                                }
                            }
                        )
                    }
                }

                if (!selectionMode) {
                    LogBottomBar(
                        follow = follow,
                        paused = paused,
                        onToggleFollow = {
                            val next = !follow
                            follow = next
                            // 「回到底部」不只是打开跟随：立刻滚到最新，否则用户还要手动滑
                            if (next && visible.isNotEmpty()) {
                                scope.launch { listState.animateScrollToItem(visible.size - 1) }
                            }
                        },
                        onTogglePause = { paused = !paused },
                        onExport = {
                            exportSheetVisible = true
                            if (source == LogSource.CORE) {
                                viewModel.tools.loadCoreLogFiles()
                            } else {
                                appFilesReloadTick++
                            }
                        },
                        modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
                    )
                }
            }
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })

        // ── 二次确认弹窗：清空实时日志 ──
        if (showClearRealtimeConfirm) {
            UfiConfirmDialog(
                title = "清空实时日志",
                text = if (source == LogSource.APP) "将清空内存中的 APP 日志缓冲，此操作不可恢复。" else "将清空 core 侧日志缓冲，此操作不可恢复。",
                confirmText = "清空",
                destructive = true,
                onDismiss = { showClearRealtimeConfirm = false },
                onConfirm = {
                    showClearRealtimeConfirm = false
                    if (source == LogSource.APP) {
                        AppLogBuffer.clear()
                        tick++
                        toastMessage = ToastMessage("已清空 APP 日志", ToastType.SUCCESS)
                    } else {
                        viewModel.tools.clearDebugLogs()
                    }
                }
            )
        }
    }

    // 落盘日志文件浏览：列表 → 点开看尾部正文。
    // 手机侧读本地 AppFileLogger，设备侧读 /api/debug-logs/files —— 两侧的「全部删除」
    // 各自只删自己那一侧的文件。
    val isAppSide = source == LogSource.APP
    val viewingName = if (isAppSide) appViewingName else logFilesState.viewingName
    val viewingText = if (isAppSide) appViewingText else logFilesState.viewingText
    val fileRows: List<LogFileInfo> = if (isAppSide) {
        appLogFiles
    } else {
        logFilesState.files.map { LogFileInfo(it.name, it.size, it.modified) }
    }
    val fileTotalBytes = if (isAppSide) appLogTotalBytes else logFilesState.totalBytes
    val fileDir = if (isAppSide) AppFileLogger.dirPath() else logFilesState.dir
    val filesLoading = if (isAppSide) false else logFilesState.isLoading

    /**
     * 单文件导出（2026-10-30 M2）：把当前查看的文件内容写成 txt（SAF 选位置）。
     *
     * core 侧查看器只有尾部（接口上限 2MB），app 侧按 2MB 读；文件更大时头部会标注
     * 「仅文件尾部」——不标注会让拿到文件的人以为原文就这么短。
     */
    fun startFileExport() {
        val name = viewingName ?: return
        if (exportBusy) return
        scope.launch {
            exportBusy = true
            val text = if (isAppSide) {
                withContext(Dispatchers.IO) { AppFileLogger.readTail(name, 2 * 1024 * 1024) }
            } else {
                // 查看器只加载 256KB；导出单独取 2MB 尾部（接口上限），不打扰当前查看状态
                withContext(Dispatchers.IO) { viewModel.tools.readCoreLogFileTail(name) }
            }
            if (text == null) {
                exportBusy = false
                toastMessage = ToastMessage("读取文件失败，未导出", ToastType.ERROR)
                return@launch
            }
            val size = fileRows.firstOrNull { it.name == name }?.size ?: 0L
            // 大小未知（0）时保守标注为尾部；已知且 ≤2MB 才算完整
            val truncated = size <= 0L || size > 2L * 1024 * 1024
            val bytes = withContext(Dispatchers.Default) {
                buildLogFileExportText(
                    fileName = name,
                    exportedAt = System.currentTimeMillis(),
                    appVersionName = appVersionName(),
                    sourceLabel = if (isAppSide) "手机 APP" else "设备 core",
                    content = text,
                    truncated = truncated
                ).toByteArray(Charsets.UTF_8)
            }
            pendingExport = LogExportPending(bytes = bytes, count = 1, unit = "个文件", mime = "text/plain")
            exportBusy = false
            exportLauncher.launch(name.substringAfterLast('/'))
        }
    }

    /**
     * 打包全部（2026-10-30 M2）：把这一侧所有落盘文件打成 ZIP（SAF 选位置）。
     *
     * 单文件最多 2MB 尾部、总量最多 32MB（`collect*` 里实现）—— ZIP 需要一次性写进
     * 用户选的位置，不能把几百 MB 全塞内存。被跳过的文件会在结果对话框里如实报告。
     */
    fun startBundleExport() {
        if (exportBusy) return
        scope.launch {
            exportBusy = true
            val (files, skipped) = withContext(Dispatchers.IO) {
                if (isAppSide) {
                    AppFileLogger.collectForBundle()
                } else {
                    viewModel.tools.collectCoreLogFilesForBundle()
                }
            }
            if (files.isEmpty()) {
                exportBusy = false
                toastMessage = ToastMessage("没有可导出的文件", ToastType.INFO)
                return@launch
            }
            val exportedAt = System.currentTimeMillis()
            val bytes = withContext(Dispatchers.Default) {
                buildLogBundleZip(
                    sourceLabel = if (isAppSide) "手机 APP" else "设备 core",
                    exportedAt = exportedAt,
                    appVersionName = appVersionName(),
                    files = files
                )
            }
            pendingExport = LogExportPending(
                bytes = bytes,
                count = files.size,
                unit = "个文件",
                skipped = skipped,
                mime = "application/zip"
            )
            exportBusy = false
            bundleLauncher.launch(defaultLogBundleFileName(source, exportedAt))
        }
    }

    // ── 方案 C 的覆盖层：筛选 / 详情 / 导出工作表 / 日志设置 ──
    if (filterSheetVisible) {
        LogFilterSheet(
            query = query,
            onQueryChange = { query = it },
            kindFilter = kindFilter,
            onKindChange = { kindFilter = it },
            levelFilter = levelFilter,
            onLevelChange = { levelFilter = it },
            levelOptions = levelOptions,
            matchedCount = visible.size,
            onReset = {
                kindFilter = FILTER_ALL
                levelFilter = FILTER_ALL
                query = ""
            },
            onDismiss = { filterSheetVisible = false }
        )
    }

    detailEntry?.let { entry ->
        LogDetailSheet(
            entry = entry,
            onDismiss = { detailEntry = null },
            onCopy = {
                copyToClipboard(listOf(entry), "已复制")
                detailEntry = null
            },
            onExport = {
                detailEntry = null
                startLogExport(listOf(entry), isSelection = false)
            }
        )
    }

    if (exportSheetVisible) {
        LogExportSheet(
            source = source,
            recordCount = visible.size,
            recordBytes = visible.sumOf { it.format().length.toLong() },
            todayBytes = if (isAppSide) appLogUsage.todayBytes else logFilesState.todayBytes,
            days = if (isAppSide) appLogUsage.days else logFilesState.days,
            freeBytes = if (isAppSide) appLogUsage.freeBytes else logFilesState.diskFreeBytes,
            degraded = !isAppSide && logFilesState.degraded,
            files = fileRows,
            totalBytes = fileTotalBytes,
            lastExport = lastExport,
            busy = exportBusy,
            onExportRecords = { startLogExport(visible, isSelection = false) },
            onCopyAll = { copyToClipboard(visible, "已复制 ${visible.size} 条") },
            onExportBundle = { startBundleExport() },
            onOpenFiles = {
                exportSheetVisible = false
                logFilesDialogVisible = true
                if (isAppSide) {
                    appViewingName = null
                    appFilesReloadTick++
                } else {
                    viewModel.tools.loadCoreLogFiles()
                }
            },
            onShareLast = { lastExport?.let { shareUri(it.uri, it.mime) } },
            onDismiss = { exportSheetVisible = false }
        )
    }

    // ── 二次确认：全部删除日志文件 ──
    if (showDeleteAllFilesConfirm) {
        UfiConfirmDialog(
            title = "删除全部日志文件",
            text = "将删除 ${if (isAppSide) "手机端" else "设备端"} 全部 ${fileRows.size} 个日志文件（${FormatUtils.formatBytes(fileTotalBytes)}），此操作不可恢复。",
            confirmText = "全部删除",
            destructive = true,
            onDismiss = { showDeleteAllFilesConfirm = false },
            onConfirm = {
                showDeleteAllFilesConfirm = false
                if (isAppSide) {
                    scope.launch {
                        val freed = withContext(Dispatchers.IO) { AppFileLogger.deleteAll() }
                        appFilesReloadTick++
                        toastMessage = ToastMessage(
                            "已删除手机日志文件，释放 ${FormatUtils.formatBytes(freed)}",
                            ToastType.SUCCESS
                        )
                    }
                } else {
                    viewModel.tools.deleteCoreLogFiles()
                }
            }
        )
    }

    // ── 导出完成：结果对话框（2026-10-30 M1）──
    // 不只回一个 toast：用户需要知道“导出了几条、多大”，并且能一键分享出去；
    // SAF 返回的 uri 本身就是可分享的 content://，加读权限即可交给其它应用。
    val dialogExportResult = rememberLastNonNull(exportResult)
    if (dialogExportResult != null) {
        val result = dialogExportResult
        UfiCustomDialog(
            visible = exportResult != null,
            onDismiss = { exportResult = null },
            title = "导出完成",
            confirmButton = {
                UfiButton(
                    text = "分享",
                    onClick = { shareUri(result.uri, result.mime) }
                )
            },
            dismissButton = {
                UfiButton(
                    text = "完成",
                    variant = UfiButtonVariant.Secondary,
                    onClick = { exportResult = null }
                )
            }
        ) {
            Text(
                "已导出 ${result.count} ${result.unit} · ${FormatUtils.formatBytes(result.bytes.toLong())}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (result.skipped > 0) {
                Spacer(Modifier.height(Spacing.Small))
                Text(
                    "有 ${result.skipped} 个文件因超限或读取失败被跳过",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.warning
                )
            }
            Spacer(Modifier.height(Spacing.Small))
            Text(
                "日志可能包含设备标识、IP、号码；请确认接收方可信。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
        }
    }

    UfiCustomDialog(
        visible = logFilesDialogVisible,
        onDismiss = {
            logFilesDialogVisible = false
            appViewingName = null
            viewModel.tools.closeCoreLogFile()
        },
        title = viewingName ?: if (isAppSide) "手机日志文件" else "设备日志文件",
        confirmButton = {
            val close = LocalUfiDialogClose.current
            if (viewingName != null) {
                // 「返回列表」只是退回文件列表、弹窗还在，所以不走 close。
                UfiButton(
                    text = "返回列表",
                    onClick = {
                        if (isAppSide) appViewingName = null else viewModel.tools.closeCoreLogFile()
                    }
                )
            } else {
                UfiButton(text = "关闭", onClick = { close { logFilesDialogVisible = false } })
            }
        },
        dismissButton = if (viewingName == null && fileRows.isNotEmpty()) {
            {
                UfiButton(
                    variant = UfiButtonVariant.Secondary,
                    text = "全部删除",
                    onClick = { showDeleteAllFilesConfirm = true }
                )
            }
        } else null
    ) {
        UfiDialogBody {
            logFilesState.errorMessage?.takeIf { !isAppSide }?.let { err ->
                Text(err, style = MaterialTheme.typography.bodySmall, color = palette.error)
            }
            when {
                filesLoading && viewingText == null ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        UfiLoadingIndicator()
                    }

                viewingName != null -> Column(modifier = Modifier.fillMaxWidth()) {
                    // 2026-10-30 M2：单文件也能导出了（SAF 选位置），不再只能看
                    UfiButton(
                        text = "导出此文件",
                        enabled = !exportBusy,
                        onClick = { startFileExport() },
                        variant = UfiButtonVariant.Secondary,
                        size = UfiButtonSize.Small
                    )
                    Spacer(Modifier.height(Spacing.Small))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = viewingText?.takeIf { it.isNotBlank() } ?: "（空文件）",
                            style = UfiTextStyles.monoTiny,
                            color = palette.textPrimary
                        )
                    }
                }

                fileRows.isEmpty() -> Column(Modifier.fillMaxWidth()) {
                    Text(
                        if (isAppSide) "手机上还没有日志文件" else "设备上还没有日志文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                    if (fileDir.isNotBlank()) {
                        Spacer(Modifier.height(Spacing.Small))
                        Text(
                            fileDir,
                            style = UfiTextStyles.monoNote,
                            color = palette.textSecondary
                        )
                    }
                }

                else -> Column(modifier = Modifier.fillMaxWidth()) {
                    // 2026-10-30：占用提示（总量 / 今日 / 跨度 / 剩余空间）。
                    // 日志不按天删除（用户决定取消保留上限），所以这里只展示、不提供“按天清理”。
                    val fileDays = if (isAppSide) appLogUsage.days else logFilesState.days
                    val fileTodayBytes = if (isAppSide) appLogUsage.todayBytes else logFilesState.todayBytes
                    val fileFreeBytes = if (isAppSide) appLogUsage.freeBytes else logFilesState.diskFreeBytes
                    val fileSummary = buildString {
                        append("共 ${fileRows.size} 个 · ${FormatUtils.formatBytes(fileTotalBytes)}")
                        if (fileDays > 0) append(" · 跨度 $fileDays 天")
                        if (fileTodayBytes > 0) {
                            append(" · 今日 ${FormatUtils.formatBytes(fileTodayBytes)}")
                        }
                        if (fileFreeBytes >= 0) {
                            append(" · 剩余 ${FormatUtils.formatBytes(fileFreeBytes)}")
                        }
                        append("（点按查看末尾 256KB）")
                    }
                    Text(
                        fileSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                    if (!isAppSide && logFilesState.degraded) {
                        Text(
                            "存储空间不足，core 已暂停写日志文件（内存日志仍可查看）",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.warning
                        )
                    }
                    Spacer(Modifier.height(Spacing.Small))
                    // 2026-10-30 M2：全部打包为 ZIP（单文件 2MB 尾部 / 总量 32MB 上限）
                    UfiButton(
                        text = "打包全部（ZIP）",
                        enabled = !exportBusy && fileRows.isNotEmpty(),
                        onClick = { startBundleExport() },
                        variant = UfiButtonVariant.Secondary,
                        size = UfiButtonSize.Small
                    )
                    if (fileDir.isNotBlank()) {
                        Text(
                            fileDir,
                            style = UfiTextStyles.monoNote,
                            color = palette.textSecondary
                        )
                    }
                    Spacer(Modifier.height(Spacing.Small))
                    fileRows.forEach { f ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isAppSide) {
                                        appViewingName = f.name
                                    } else {
                                        viewModel.tools.loadCoreLogFileTail(f.name)
                                    }
                                }
                                .padding(vertical = Spacing.Small),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    f.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.textPrimary,
                                    maxLines = 1
                                )
                                Text(
                                    FormatUtils.formatTimestamp(f.modified),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.textSecondary
                                )
                            }
                            Text(
                                FormatUtils.formatBytes(f.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 时间列固定宽度：11sp 等宽下 `HH:mm:ss.SSS` 约 79dp，留 80dp 保证不挤级别列。 */
private val TimeColumnWidth = 80.dp

/** 级别列固定宽度：色点 + 单字母标签。 */
private val LevelColumnWidth = 46.dp

/**
 * 日志表格（方案 C，2026-10-30）。
 *
 * 与旧 [LogList] 的区别：
 * - 旧实现每行是一个 `entry.format()` 字符串 + **行内**横向滚动；
 * - 新实现三列对齐（时间 / 级别 / 内容），正文换行两行封顶，行内不再横滚。
 *
 * 表头用 `stickyHeader`：长列表滚动时列名常驻，扫读不用回忆列含义。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogTable(
    entries: List<LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    selectionMode: Boolean,
    selectionStart: Int,
    selectionEnd: Int,
    onLineClick: (Int, LogEntry) -> Unit
) {
    val palette = LocalResolvedPalette.current

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        stickyHeader {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.pageBg)
                    .padding(horizontal = Spacing.CardHorizontalMargin, vertical = 6.dp)
            ) {
                Text("时间", Modifier.width(TimeColumnWidth), style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                Text("级别", Modifier.width(LevelColumnWidth), style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                Text("内容", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
            }
            HorizontalDivider(color = palette.divider)
        }

        itemsIndexed(entries, key = { _, e -> "${e.source.name}#${e.seq}" }) { index, entry ->
            val selected = selectionStart >= 0 && when {
                selectionEnd < 0 -> index == selectionStart
                else -> index in minOf(selectionStart, selectionEnd)..maxOf(selectionStart, selectionEnd)
            }
            val levelColor = when (entry.level) {
                LogLevel.ERROR -> palette.error
                LogLevel.WARN -> palette.warning
                LogLevel.DEBUG -> palette.textSecondary
                LogLevel.INFO -> palette.textPrimary
            }
            val messageColor = when (entry.level) {
                LogLevel.ERROR -> palette.error
                LogLevel.WARN -> palette.warning
                LogLevel.DEBUG -> palette.textSecondary
                LogLevel.INFO -> palette.textPrimary
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (selected) Modifier.background(palette.accent.copy(alpha = 0.14f)) else Modifier)
                    .clickable { onLineClick(index, entry) }
                    .padding(horizontal = Spacing.CardHorizontalMargin, vertical = 6.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = entry.time.ifBlank { "—" },
                    modifier = Modifier.width(TimeColumnWidth),
                    style = UfiTextStyles.monoCaption,
                    color = palette.textSecondary,
                    maxLines = 1
                )
                Row(
                    modifier = Modifier.width(LevelColumnWidth),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(6.dp)
                            .background(levelColor, CircleShape)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = entry.level.label,
                        style = UfiTextStyles.monoCaption,
                        color = levelColor,
                        maxLines = 1
                    )
                }
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = palette.textSecondary)) {
                            append("[${entry.tag.ifBlank { "-" }}] ")
                        }
                        append(entry.message)
                    },
                    modifier = Modifier.weight(1f),
                    style = UfiTextStyles.monoCaption,
                    color = messageColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider(color = palette.divider)
        }

        if (!selectionMode) {
            item { Spacer(Modifier.height(Spacing.Small)) }
        }
    }
}

/** 设备/来源状态卡：连接、运行时长、条数、今日写盘、剩余空间（方案 C）。 */
@Composable
private fun DeviceStatusCard(
    source: LogSource,
    logEnabled: Boolean,
    writable: Boolean,
    count: Int,
    uptimeMs: Long?,
    todayBytes: Long,
    freeBytes: Long,
    degraded: Boolean,
    loading: Boolean,
    hasError: Boolean,
    synced: Boolean,
    switchError: String?,
    appLogEnabled: Boolean,
    coreLogEnabled: Boolean,
    debugMode: Boolean,
    busy: Boolean,
    onToggleLog: () -> Unit,
    onToggleApp: () -> Unit,
    onToggleCore: () -> Unit,
    onToggleVerbose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Spacing.CornerBase),
        color = palette.cardBg,
        border = BorderStroke(1.dp, palette.divider)
    ) {
        Column(Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Medium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (source == LogSource.APP) "手机 APP" else "设备 core",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary
                )
                Spacer(Modifier.width(Spacing.Small))
                val statusText = when {
                    source == LogSource.APP -> "本机"
                    loading && count == 0 -> "连接中…"
                    hasError -> "未连接"
                    else -> "已连接"
                }
                val statusColor = when (statusText) {
                    "已连接" -> palette.success
                    "未连接" -> palette.warning
                    else -> palette.textSecondary
                }
                StatusPill(text = statusText, color = statusColor)
                Spacer(Modifier.weight(1f))
                Text(
                    if (synced) "记录" else "记录（未同步）",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )
                Spacer(Modifier.width(Spacing.Small))
                UfiSwitch(
                    checked = logEnabled,
                    onCheckedChange = { onToggleLog() },
                    enabled = writable
                )
            }
            Spacer(Modifier.height(6.dp))
            val meta = buildList {
                if (source == LogSource.CORE) add("运行 ${formatUptime(uptimeMs)}")
                add("$count 条")
                if (todayBytes > 0) add("今日 ${FormatUtils.formatBytes(todayBytes)}")
                if (freeBytes >= 0) add("可用 ${FormatUtils.formatBytes(freeBytes)}")
            }.joinToString("  ·  ")
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 2
            )
            Spacer(Modifier.height(Spacing.Medium))
            HorizontalDivider(color = palette.divider)
            Spacer(Modifier.height(Spacing.Small))
            // 2026-10-30：四个开关全部搬到页面上（用户反馈“设置太分散”）。
            // 子开关与总开关是「与」关系：总开关关掉时它们即使开着也不记录，所以禁用并降透明度。
            Row(modifier = Modifier.fillMaxWidth()) {
                CardSwitch(
                    label = "手机端",
                    checked = appLogEnabled,
                    enabled = logEnabled && !busy,
                    onToggle = onToggleApp,
                    modifier = Modifier.weight(1f)
                )
                CardSwitch(
                    label = "后端",
                    checked = coreLogEnabled,
                    enabled = logEnabled && !busy,
                    onToggle = onToggleCore,
                    modifier = Modifier.weight(1f)
                )
                CardSwitch(
                    label = "详细",
                    checked = debugMode,
                    enabled = logEnabled && !busy,
                    onToggle = onToggleVerbose,
                    modifier = Modifier.weight(1f)
                )
            }
            if (!logEnabled) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "总开关关闭时，以下子开关不生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    maxLines = 1
                )
            }
            if (degraded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "存储空间不足，已暂停写日志文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.warning,
                    maxLines = 1
                )
            }
            switchError?.let { err ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "开关同步失败：$err",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.error,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun CardSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary.copy(alpha = if (enabled) 1f else 0.5f),
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        UfiSwitch(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled
        )
    }
}

@Composable
private fun StatusPill(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.13f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** 控件行：来源分段 + 搜索 / 筛选（方案 C 把三行控件压到这里）。 */
@Composable
private fun LogControlsRow(
    sourceIndex: Int,
    onSourceSelected: (Int) -> Unit,
    filterCount: Int,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 2026-10-31：来源切换是「换一页数据」，用页签组件 UfiScrollableTabRow，
        // 不是筛选用的 UfiSingleChipSelector（上一版把它当分段筛选器用，语义与观感都不对）。
        UfiScrollableTabRow(
            selectedTabIndex = sourceIndex,
            onTabSelected = onSourceSelected,
            tabs = listOf("手机", "设备"),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(Spacing.Small))
        UfiButton(
            text = "搜索",
            onClick = onSearch,
            variant = UfiButtonVariant.Secondary,
            size = UfiButtonSize.Small,
            fillWidth = false
        )
        UfiButton(
            text = if (filterCount > 0) "筛选 $filterCount" else "筛选",
            onClick = onFilter,
            variant = if (filterCount > 0) UfiButtonVariant.Primary else UfiButtonVariant.Secondary,
            size = UfiButtonSize.Small,
            fillWidth = false
        )
    }
}

/** 底部固定三动作：跟随 / 暂停 / 导出（方案 C）。 */
@Composable
private fun LogBottomBar(
    follow: Boolean,
    paused: Boolean,
    onToggleFollow: () -> Unit,
    onTogglePause: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Small),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        UfiButton(
            text = if (follow) "跟随中" else "回到底部",
            onClick = onToggleFollow,
            variant = if (follow) UfiButtonVariant.Primary else UfiButtonVariant.Secondary,
            size = UfiButtonSize.Small,
            fillWidth = false,
            modifier = Modifier.weight(1f)
        )
        UfiButton(
            text = if (paused) "继续" else "暂停",
            onClick = onTogglePause,
            variant = UfiButtonVariant.Secondary,
            size = UfiButtonSize.Small,
            fillWidth = false,
            modifier = Modifier.weight(1f)
        )
        UfiButton(
            text = "导出",
            onClick = onExport,
            size = UfiButtonSize.Small,
            fillWidth = false,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 筛选底部弹层：搜索 + 类型 + 级别，条件即时生效。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogFilterSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    kindFilter: String,
    onKindChange: (String) -> Unit,
    levelFilter: String,
    onLevelChange: (String) -> Unit,
    levelOptions: List<Pair<String, String>>,
    matchedCount: Int,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardBg,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.CardHorizontalMargin)
                .padding(bottom = Spacing.XLarge)
        ) {
            Text("筛选", style = MaterialTheme.typography.titleMedium)
            Text(
                "条件即时生效，收起后正文占满全屏",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            Spacer(Modifier.height(Spacing.Medium))
            UfiTextField(
                value = query,
                onValueChange = onQueryChange,
                label = "搜索",
                placeholder = "tag 或内容关键字",
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, "清空搜索")
                        }
                    }
                } else null
            )
            Spacer(Modifier.height(Spacing.Medium))
            Text("类型", style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
            Spacer(Modifier.height(Spacing.Small))
            UfiSingleChipSelector(
                options = KIND_OPTIONS,
                selectedValue = kindFilter,
                onSelect = onKindChange,
                wrapContent = true
            )
            Spacer(Modifier.height(Spacing.Medium))
            Text("级别", style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
            Spacer(Modifier.height(Spacing.Small))
            UfiSingleChipSelector(
                options = levelOptions,
                selectedValue = levelFilter,
                onSelect = onLevelChange,
                wrapContent = true
            )
            Spacer(Modifier.height(Spacing.Large))
            Row(verticalAlignment = Alignment.CenterVertically) {
                UfiButton(
                    text = "重置",
                    onClick = onReset,
                    variant = UfiButtonVariant.Secondary,
                    size = UfiButtonSize.Small,
                    fillWidth = false
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "匹配 $matchedCount 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )
                Spacer(Modifier.width(Spacing.Medium))
                UfiButton(
                    text = "查看结果",
                    onClick = onDismiss,
                    size = UfiButtonSize.Small,
                    fillWidth = false
                )
            }
        }
    }
}

/** 单条日志详情：元数据 + 完整消息 + 复制/导出。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogDetailSheet(
    entry: LogEntry,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardBg,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.CardHorizontalMargin)
                .padding(bottom = Spacing.XLarge)
        ) {
            Text(
                "日志详情",
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary
            )
            Text(
                "${entry.level.name} · ${entry.time.ifBlank { "—" }} · ${entry.source.label}",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            Spacer(Modifier.height(Spacing.Medium))
            DetailRow("来源", entry.tag.ifBlank { "-" })
            DetailRow("类型", entry.kind.label)
            DetailRow("级别", entry.level.name)
            Spacer(Modifier.height(Spacing.Medium))
            // 堆栈可能几十行：给一个上限并允许滚动，别把弹层顶出屏幕
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(entry.message, style = UfiTextStyles.monoTiny, color = palette.textPrimary)
                }
            }
            Spacer(Modifier.height(Spacing.Large))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                UfiButton(
                    text = "复制",
                    onClick = onCopy,
                    variant = UfiButtonVariant.Secondary,
                    size = UfiButtonSize.Small,
                    fillWidth = false,
                    modifier = Modifier.weight(1f)
                )
                UfiButton(
                    text = "导出这条",
                    onClick = onExport,
                    size = UfiButtonSize.Small,
                    fillWidth = false,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val palette = LocalResolvedPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = palette.textPrimary
        )
    }
}

/** 导出与文件工作表：记录 / 落盘文件两个页签 + 上次导出（方案 C）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogExportSheet(
    source: LogSource,
    recordCount: Int,
    recordBytes: Long,
    todayBytes: Long,
    days: Int,
    freeBytes: Long,
    degraded: Boolean,
    files: List<LogFileInfo>,
    totalBytes: Long,
    lastExport: LastExport?,
    busy: Boolean,
    onExportRecords: () -> Unit,
    onCopyAll: () -> Unit,
    onExportBundle: () -> Unit,
    onOpenFiles: () -> Unit,
    onShareLast: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    var tab by remember { mutableStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardBg,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.CardHorizontalMargin)
                .padding(bottom = Spacing.XLarge)
        ) {
            UfiSingleChipSelector(
                options = listOf("0" to "记录", "1" to "落盘文件"),
                selectedValue = tab.toString(),
                onSelect = { tab = it.toIntOrNull() ?: 0 },
                wrapContent = true
            )
            Spacer(Modifier.height(Spacing.Medium))

            if (tab == 0) {
                Text(
                    "当前筛选 $recordCount 条 · 约 ${FormatUtils.formatBytes(recordBytes)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "文件头会写来源、筛选、条数与时间范围；落盘历史请切到「落盘文件」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )
                Spacer(Modifier.height(Spacing.Large))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                    UfiButton(
                        text = "复制全部",
                        onClick = onCopyAll,
                        variant = UfiButtonVariant.Secondary,
                        size = UfiButtonSize.Small,
                        fillWidth = false,
                        modifier = Modifier.weight(1f)
                    )
                    UfiButton(
                        text = if (busy) "正在导出…" else "导出记录",
                        onClick = onExportRecords,
                        enabled = !busy && recordCount > 0,
                        size = UfiButtonSize.Small,
                        fillWidth = false,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Text(
                    "今日 ${FormatUtils.formatBytes(todayBytes)} · ${files.size} 个文件" +
                        if (totalBytes > 0) " · 共 ${FormatUtils.formatBytes(totalBytes)}" else "",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    if (days > 0) {
                        "跨度 $days 天" + if (freeBytes >= 0) " · 剩余 ${FormatUtils.formatBytes(freeBytes)}" else ""
                    } else {
                        "按天分目录保存；不自动按天删除，剩余空间不足时暂停写入。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )
                if (degraded) {
                    Text(
                        "存储空间不足，已暂停写日志文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.warning
                    )
                }
                Spacer(Modifier.height(Spacing.Medium))
                if (files.isEmpty()) {
                    Text(
                        "还没有落盘文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                } else {
                    files.take(5).forEach { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                file.name,
                                modifier = Modifier.weight(1f),
                                style = UfiTextStyles.monoNote,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                FormatUtils.formatBytes(file.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.Medium))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                    UfiButton(
                        text = "查看文件",
                        onClick = onOpenFiles,
                        variant = UfiButtonVariant.Secondary,
                        size = UfiButtonSize.Small,
                        fillWidth = false,
                        modifier = Modifier.weight(1f)
                    )
                    UfiButton(
                        text = if (busy) "正在打包…" else "打包 ZIP",
                        onClick = onExportBundle,
                        enabled = !busy && files.isNotEmpty(),
                        size = UfiButtonSize.Small,
                        fillWidth = false,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            lastExport?.let { last ->
                Spacer(Modifier.height(Spacing.Large))
                HorizontalDivider(color = palette.divider)
                Spacer(Modifier.height(Spacing.Medium))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "上次导出 ${last.count} ${last.unit} · ${FormatUtils.formatBytes(last.bytes.toLong())}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                    UfiButton(
                        text = "分享",
                        onClick = onShareLast,
                        variant = UfiButtonVariant.Secondary,
                        size = UfiButtonSize.Small,
                        fillWidth = false
                    )
                }
            }
        }
    }
}

/** 顶部状态行里的筛选计数（类型/级别/关键字各算一个）。 */
private fun activeFilterCount(kind: String, level: String, query: String): Int =
    (if (kind != FILTER_ALL) 1 else 0) +
        (if (level != FILTER_ALL) 1 else 0) +
        (if (query.isNotBlank()) 1 else 0)

/** 运行时长：`3h 12m` / `12m`；未知返回 `—`（不编造 0）。 */
private fun formatUptime(ms: Long?): String {
    if (ms == null || ms <= 0) return "—"
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

// ── 常量与纯函数 ──

private const val FILTER_ALL = "ALL"
private const val REFRESH_MS = 2000L

/** 需要用户看清内容的 toast（导出路径、开关下发失败）用长时长，默认时长读不完一条路径。 */
private const val TOAST_LONG_MS = 5000L

private val KIND_OPTIONS: List<Pair<String, String>> = listOf(
    FILTER_ALL to "全部",
    LogKind.RUNTIME.name to LogKind.RUNTIME.label,
    LogKind.NETWORK.name to LogKind.NETWORK.label
)


// 导出相关的纯函数已迁到 :app:data 的 `com.ufi_axis.util.LogExport`（2026-10-30 M1）：
// `LogExportMeta` / `buildLogExportText` / `defaultLogExportFileName`。
// 放 data 层是为了能用固定时钟/时区逐字单测文件头；UI 只负责 SAF 与写文件。

/** SAF 回调期间暂存的导出内容（点击时冻结，之后列表变化不影响本次结果）。 */
private class LogExportPending(
    val bytes: ByteArray,
    val count: Int,
    /** 结果文案的单位：记录导出是「条」，文件/打包是「个文件」。 */
    val unit: String = "条",
    /** 打包时被跳过的文件数（超限/读取失败）。 */
    val skipped: Int = 0,
    /** 仅“记录导出”需要：成功才退出多选。 */
    val exitSelectionOnSuccess: Boolean = false,
    /** 分享用的 MIME：文本 / ZIP。 */
    val mime: String = "text/plain",
)

/** 写入成功后的结果（uri 用于“分享”）。 */
private class LogExportResult(
    val uri: Uri,
    val count: Int,
    val bytes: Int,
    val unit: String = "条",
    val skipped: Int = 0,
    val mime: String = "text/plain",
)

/** 最近一次成功导出（方案 C 的「导出与文件」工作表里显示 + 分享）。 */
private class LastExport(
    val uri: Uri,
    val exportedAt: Long,
    val bytes: Int,
    val count: Int,
    val unit: String,
    val mime: String,
)
