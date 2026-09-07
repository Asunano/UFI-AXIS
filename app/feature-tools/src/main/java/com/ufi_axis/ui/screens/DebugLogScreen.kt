package com.ufi_axis.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.util.LogEntry
import com.ufi_axis.util.LogFileInfo
import com.ufi_axis.util.LogKind
import com.ufi_axis.util.LogLevel
import com.ufi_axis.util.LogSource
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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

    // ── 筛选态（纯展示，留在 UI；rememberSaveable 只存可入 Bundle 的基本类型）──
    var sourceIndex by rememberSaveable { mutableIntStateOf(0) }
    var kindFilter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var levelFilter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var follow by rememberSaveable { mutableStateOf(true) }
    var filterDialogVisible by remember { mutableStateOf(false) }
    var logFilesDialogVisible by remember { mutableStateOf(false) }
    // APP 侧日志文件在手机本地（AppFileLogger），直接读；core 侧走 API（ToolsModule）。
    // 两套状态刻意分开：清空只作用于当前页签那一侧，绝不会连带删掉另一侧。
    var appLogFiles by remember { mutableStateOf<List<LogFileInfo>>(emptyList()) }
    var appLogTotalBytes by remember { mutableLongStateOf(0L) }
    var appViewingName by remember { mutableStateOf<String?>(null) }
    var appViewingText by remember { mutableStateOf<String?>(null) }
    var appFilesReloadTick by remember { mutableIntStateOf(0) }
    val source = if (sourceIndex == 0) LogSource.APP else LogSource.CORE
    val filterActive = kindFilter != FILTER_ALL || levelFilter != FILTER_ALL || query.isNotBlank()

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
            toastMessage = ToastMessage(it, ToastType.ERROR, durationMs = 5000L)
            viewModel.tools.clearLogSwitchError()
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
        if (source == LogSource.CORE) viewModel.tools.loadDebugLogs()
    }
    // 进页面就从 core 回读一次四层开关：本地缓存可能已被别端（web / 另一台手机）改过，
    // 也可能上一次下发失败过。没有这一步，开关显示的仍是本机的一厢情愿。
    LaunchedEffect(Unit) {
        viewModel.tools.loadLogSwitches()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_MS)
            if (isScreenActive && !selectionMode) {
                if (source == LogSource.CORE) viewModel.tools.loadDebugLogs() else tick++
            }
        }
    }

    // ── 数据源 ──
    val appSnapshot = remember(tick) { AppLogBuffer.snapshot() }

    // APP 侧日志文件：弹窗打开时读一次（IO 线程，别在主线程碰文件——debug 构建有 StrictMode）
    LaunchedEffect(logFilesDialogVisible, source, appFilesReloadTick) {
        if (logFilesDialogVisible && source == LogSource.APP) {
            val loaded = withContext(Dispatchers.IO) {
                AppFileLogger.listFiles() to AppFileLogger.totalBytes()
            }
            appLogFiles = loaded.first
            appLogTotalBytes = loaded.second
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

    // ── 常驻在页面顶部的四个日志开关（2026-09-04 从溢出菜单搬出来）──
    // 未与 core 同步过（连不上设备 / 老 core 不返回这些字段）时不允许写，
    // 否则会把本地默认值当成用户意图发出去；点击改为重新回读一次。
    val switchesWritable = logSwitches.loaded && !logSwitches.isSaving
    val switchSuffix = when {
        !logSwitches.loaded -> "（未同步）"
        logSwitches.isSaving -> "（下发中…）"
        else -> ""
    }
    // 总开关关掉时另外三个不参与显示：它们与总开关是「与」关系，此时开着也不记录，
    // 摆在那里只会让人以为还在记（与原菜单里 `if (logEnabled)` 的处理一致）。
    val switchChipOptions = remember(logEnabled, switchSuffix) {
        buildList {
            add(SWITCH_MASTER to "总开关$switchSuffix")
            if (logEnabled) {
                add(SWITCH_APP to "手机端$switchSuffix")
                add(SWITCH_CORE to "后端$switchSuffix")
                add(SWITCH_VERBOSE to "详细$switchSuffix")
            }
        }
    }
    val enabledSwitchIds = remember(logEnabled, appLogEnabled, coreLogEnabled, debugMode) {
        buildSet {
            if (logEnabled) add(SWITCH_MASTER)
            if (appLogEnabled) add(SWITCH_APP)
            if (coreLogEnabled) add(SWITCH_CORE)
            if (debugMode) add(SWITCH_VERBOSE)
        }
    }

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
    LaunchedEffect(visible.size, follow, selectionMode) {
        if (follow && !selectionMode && visible.isNotEmpty()) {
            listState.animateScrollToItem(visible.size - 1)
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
                        exportLogs(selectedLogs(), source) { toastMessage = it }
                        exitSelectionMode()
                    }) { Icon(Icons.Default.FileDownload, "导出选中") }
                }
                IconButton(onClick = { exitSelectionMode() }) {
                    Icon(Icons.Default.Close, "取消选择")
                }
            } else {
                IconButton(onClick = { filterDialogVisible = true }) {
                    Icon(
                        Icons.Default.FilterAlt,
                        contentDescription = "筛选",
                        tint = if (filterActive) palette.accent else LocalContentColor.current
                    )
                }
                IconButton(
                    enabled = source == LogSource.CORE,
                    onClick = { viewModel.tools.loadDebugLogs() }
                ) { Icon(Icons.Default.Refresh, "刷新") }
                UfiPopupMenuButton(
                    options = buildList {
                        add(
                            UfiPopupOption(
                                id = "follow",
                                label = "跟随最新",
                                icon = Icons.Default.VerticalAlignBottom,
                                isSelected = follow,
                                onClick = { follow = !follow }
                            )
                        )
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
                            add(
                                UfiPopupOption(
                                    id = "exportAll",
                                    label = "导出全部",
                                    icon = Icons.Default.FileDownload,
                                    onClick = { exportLogs(visible, source) { toastMessage = it } }
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
                                onClick = {
                                    if (source == LogSource.APP) {
                                        AppLogBuffer.clear()
                                        tick++
                                        toastMessage = ToastMessage("已清空 APP 日志", ToastType.SUCCESS)
                                    } else {
                                        viewModel.tools.clearDebugLogs()
                                    }
                                }
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
                    Spacer(Modifier.height(Spacing.Medium))
                    // 日志开关：2026-09-04 从右上角溢出菜单搬到页面**最上方**常驻。
                    // 这四个开关是用户来这个页面最常改的东西（尤其「详细日志」），
                    // 藏在菜单里每次要点两下、还看不到当前状态。chip 的选中态本身就是开关状态。
                    // 未与 core 同步过（连不上设备 / 老 core 不返回这些字段）时不允许写：
                    // 那会把本地默认值当成用户意图发出去。点一下改为重新回读一次。
                    UfiMultiChipSelector(
                        options = switchChipOptions,
                        selectedValues = enabledSwitchIds,
                        onToggle = { id ->
                            if (!switchesWritable) {
                                viewModel.tools.loadLogSwitches()
                            } else when (id) {
                                SWITCH_MASTER -> viewModel.tools.syncLogEnabled(!logEnabled)
                                SWITCH_APP -> viewModel.tools.syncAppLogEnabled(!appLogEnabled)
                                SWITCH_CORE -> viewModel.tools.syncCoreLogEnabled(!coreLogEnabled)
                                SWITCH_VERBOSE -> viewModel.tools.syncDebugMode(!debugMode)
                            }
                        },
                        modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
                    )
                    Spacer(Modifier.height(Spacing.Small))
                    // 来源切换：手机 APP / 设备 core
                    UfiScrollableTabRow(
                        selectedTabIndex = sourceIndex,
                        onTabSelected = { sourceIndex = it },
                        tabs = listOf("手机 APP", "设备 core"),
                        modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
                    )
                    Spacer(Modifier.height(Spacing.Small))
                    // 级别筛选也放出来（带条数）：类型与关键字仍在漏斗弹窗里 ——
                    // 那两个用得少，常驻会再吃掉一行正文。
                    UfiSingleChipSelector(
                        options = levelOptions,
                        selectedValue = levelFilter,
                        onSelect = { levelFilter = it },
                        modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
                    )
                    Spacer(Modifier.height(Spacing.Small))
                }

                // 单行状态摘要：条数 / 筛选条件 / 开关关闭提示 / 加载中
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.CardHorizontalMargin),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            !logEnabled -> "日志已全局关闭"
                            !sideEnabled && source == LogSource.APP -> "手机端日志已关闭"
                            !sideEnabled -> "后端日志已关闭"
                            filterActive -> "${visible.size} 条 · ${filterSummary(kindFilter, levelFilter, query)}"
                            else -> "${visible.size} 条"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (sideEnabled) palette.textSecondary else palette.warning,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    if (coreState.isLoading && source == LogSource.CORE) {
                        Text(
                            "加载中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary
                        )
                    }
                }
                coreState.errorMessage?.takeIf { source == LogSource.CORE }?.let { err ->
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.error,
                        modifier = Modifier.padding(
                            horizontal = Spacing.CardHorizontalMargin,
                            vertical = Spacing.Small
                        )
                    )
                }

                when {
                    coreState.isLoading && source == LogSource.CORE && entries.isEmpty() ->
                        // 2026-09-03：设备端日志首屏首次加载从居中转圈改为骨架屏。
                        // 条件里带 entries.isEmpty()，即正文区此刻确实一行都没有 —— 属于整页首屏，
                        // 不是「已有日志再拉一次」的局部刷新（那种情况下这一支不成立，旧日志继续显示）。
                        // 日志是密排等宽文本行，骨架线正好预示「一屏行文本」的版式；转圈只在正中一个点。
                        // 刻意不用 UfiSkeletonGroup：日志行直接铺在页底色上、没有卡片容器，
                        // 套骨架卡会画出页面上根本不存在的三张卡，反而误导。
                        // 内边距与 LogList 的 contentPadding 保持一致，切换时行位置不跳。
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    horizontal = Spacing.CardHorizontalMargin,
                                    vertical = Spacing.Small
                                ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
                        ) {
                            // 14 行 ≈ 首屏可见日志行数；每三行插一条短行，模拟长短不一的真实日志
                            repeat(14) { index ->
                                UfiSkeletonLine(widthFraction = if (index % 3 == 2) 0.55f else 0.92f)
                            }
                        }

                    visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        UfiEmptyState(
                            icon = Icons.AutoMirrored.Filled.Article,
                            message = if (entries.isEmpty()) "暂无日志" else "没有符合筛选条件的日志",
                            hint = when {
                                entries.isNotEmpty() -> "试试放宽类型 / 级别或清空搜索词"
                                !logEnabled -> "日志总开关已关闭，两端都不再记录（上方开关可开启）"
                                !sideEnabled -> "这一侧的日志开关已关闭（上方开关可开启）"
                                !debugMode -> "详细日志已关闭，当前只记录警告与错误"
                                source == LogSource.CORE -> "设备端尚未产生日志，或服务未连接"
                                else -> null
                            }
                        )
                    }

                    else -> LogList(
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
                                copyToClipboard(listOf(entry), "已复制")
                            }
                        }
                    )
                }
            }
        }

        // 筛选弹窗：搜索 + 类型 + 带条数的级别，收起后正文占满全屏
        UfiCustomDialog(
            visible = filterDialogVisible,
            onDismiss = { filterDialogVisible = false },
            title = "筛选",
            confirmButton = {
                UfiButton(text = "完成", onClick = { filterDialogVisible = false })
            },
            dismissButton = {
                UfiButton(
                    variant = UfiButtonVariant.Secondary,
                    text = "重置",
                    onClick = {
                        kindFilter = FILTER_ALL
                        levelFilter = FILTER_ALL
                        query = ""
                    }
                )
            }
        ) {
            UfiDialogBody {
                UfiTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索",
                    placeholder = "tag 或内容关键字",
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, "清空搜索")
                            }
                        }
                    } else null
                )
                UfiSingleChipSelector(
                    options = KIND_OPTIONS,
                    selectedValue = kindFilter,
                    onSelect = { kindFilter = it }
                )
                // 级别 chip 不在这里：2026-09-04 已常驻到页面顶部（带条数），
                // 弹窗里再放一份就是两处同一状态、改哪边都要同步。
            }
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
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

    UfiCustomDialog(
        visible = logFilesDialogVisible,
        onDismiss = {
            logFilesDialogVisible = false
            appViewingName = null
            viewModel.tools.closeCoreLogFile()
        },
        title = viewingName ?: if (isAppSide) "手机日志文件" else "设备日志文件",
        confirmButton = {
            if (viewingName != null) {
                UfiButton(
                    text = "返回列表",
                    onClick = {
                        if (isAppSide) appViewingName = null else viewModel.tools.closeCoreLogFile()
                    }
                )
            } else {
                UfiButton(text = "关闭", onClick = { logFilesDialogVisible = false })
            }
        },
        dismissButton = if (viewingName == null && fileRows.isNotEmpty()) {
            {
                UfiButton(
                    variant = UfiButtonVariant.Secondary,
                    text = "全部删除",
                    onClick = {
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

                viewingName != null -> Column(
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
                    Text(
                        "共 ${fileRows.size} 个 · ${FormatUtils.formatBytes(fileTotalBytes)}（点按查看末尾 256KB）",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
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

/** 日志行列表：等宽字体 + 级别着色 + 横向滚动（长行不折行，保持可读的列对齐）。 */
@Composable
private fun LogList(
    entries: List<LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    selectionMode: Boolean,
    selectionStart: Int,
    selectionEnd: Int,
    onLineClick: (Int, LogEntry) -> Unit
) {
    val palette = LocalResolvedPalette.current
    val highlight = palette.accent.copy(alpha = 0.14f)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = Spacing.CardHorizontalMargin,
            vertical = Spacing.Small
        )
    ) {
        itemsIndexed(entries, key = { _, e -> "${e.source.name}#${e.seq}" }) { index, entry ->
            val selected = selectionStart >= 0 && when {
                selectionEnd < 0 -> index == selectionStart
                else -> index in minOf(selectionStart, selectionEnd)..maxOf(selectionStart, selectionEnd)
            }
            val color = when (entry.level) {
                LogLevel.ERROR -> palette.error
                LogLevel.WARN -> palette.warning
                LogLevel.DEBUG -> palette.textSecondary
                LogLevel.INFO -> palette.textPrimary
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (selected) Modifier.background(highlight) else Modifier)
                    .clickable { onLineClick(index, entry) }
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 2.dp, horizontal = 4.dp)
            ) {
                Text(
                    text = entry.format(),
                    style = UfiTextStyles.monoCaption,
                    color = color,
                    softWrap = false
                )
            }
        }
        if (!selectionMode) {
            // 底部留白：跟随最新滚到底时最后一行不贴边
            item { Spacer(Modifier.height(Spacing.Large)) }
        }
    }
}

// ── 常量与纯函数 ──

private const val FILTER_ALL = "ALL"
private const val REFRESH_MS = 2000L

// 顶部开关 chip 的 id（只在本页内用，与后端字段名无关）
private const val SWITCH_MASTER = "master"
private const val SWITCH_APP = "app"
private const val SWITCH_CORE = "core"
private const val SWITCH_VERBOSE = "verbose"

private val KIND_OPTIONS: List<Pair<String, String>> = listOf(
    FILTER_ALL to "全部",
    LogKind.RUNTIME.name to LogKind.RUNTIME.label,
    LogKind.NETWORK.name to LogKind.NETWORK.label
)

/** 顶部状态行用的一句话筛选摘要（筛选控件收进弹窗后，这里是唯一的可见反馈）。 */
private fun filterSummary(kind: String, level: String, query: String): String {
    val parts = buildList {
        if (kind != FILTER_ALL) add(KIND_OPTIONS.first { it.first == kind }.second)
        if (level != FILTER_ALL) add(level)
        if (query.isNotBlank()) add("“$query”")
    }
    return parts.joinToString(" · ")
}

/**
 * 导出到 Downloads。文件名带来源，避免双端日志导出后分不清是谁的。
 * 内容用 [LogEntry.format]，与 core 原始行格式一致，便于直接粘贴对照。
 */
private fun exportLogs(
    logs: List<LogEntry>,
    source: LogSource,
    onResult: (ToastMessage) -> Unit
) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "ufi_axis_${source.name.lowercase(Locale.US)}_log_$timestamp.txt"
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadsDir.mkdirs()
        File(downloadsDir, fileName).writeText(logs.joinToString("\n") { it.format() })
        onResult(ToastMessage("已导出到 Downloads/$fileName", ToastType.SUCCESS, durationMs = 5000L))
    } catch (e: Exception) {
        onResult(ToastMessage("导出失败: ${e.message}", ToastType.ERROR, durationMs = 5000L))
    }
}
