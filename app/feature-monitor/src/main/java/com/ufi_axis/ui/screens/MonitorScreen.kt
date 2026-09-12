package com.ufi_axis.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.data.model.*
import com.ufi_axis.data.monitor.*
import com.ufi_axis.ui.animation.page.isUfiPageForeground
import com.ufi_axis.ui.animation.rememberUfiPressed
import com.ufi_axis.ui.animation.ufiPressScale
import com.ufi_axis.ui.components.EventSortMode
import com.ufi_axis.ui.components.AggregateAlertRow
import com.ufi_axis.ui.components.AggregatedAlertRowView
import com.ufi_axis.ui.components.AlertTypeDetailDialog
import com.ufi_axis.ui.components.EventSummaryCard
import com.ufi_axis.ui.components.MonitorEventStatCards
import com.ufi_axis.ui.components.MonitorEventCard


import com.ufi_axis.ui.components.MonitorLevelFilter
import com.ufi_axis.ui.components.MonitorOverviewTab
import com.ufi_axis.ui.components.alertRangeLabel
import com.ufi_axis.ui.components.PeakMetricsGrid
import com.ufi_axis.ui.components.MonitorReadFilter
import com.ufi_axis.ui.components.MonitorSortButton
import com.ufi_axis.ui.components.MonitorSortDialog
import com.ufi_axis.ui.components.MonitorTopTab
import com.ufi_axis.ui.components.MonitorTypeFilter
import com.ufi_axis.ui.components.worstPeakSummary
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
// 2026-09-04（P3a）：`UfiMotion` 已迁到 ui.theme，而 components.common 仍留着同名的
// @Deprecated 转发壳。本文件同时 `*` 导入了这两个包，两个同名 object 优先级相同 →
// 「Overload resolution ambiguity」。显式单点导入优先级高于两条星号导入，既消歧义
// 又直接指向新位置（本文件因此不再产生废弃警告）。
import com.ufi_axis.ui.theme.UfiMotion

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.MonitorState
import com.ufi_axis.viewmodel.state.DashboardState
import com.ufi_axis.viewmodel.module.UiEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    showBack: Boolean = true
) {
    val monitorState by viewModel.monitorState.collectAsState()
    val dashboardState by viewModel.dashboardState.collectAsState()

    var pendingDeleteAlertId by remember { mutableStateOf<Long?>(null) }
    var customDialogVisible by remember { mutableStateOf(false) }
    var overviewTab by remember { mutableStateOf(MonitorOverviewTab.OVERVIEW) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    LaunchedEffect(Unit) {
        viewModel.dashboard.events.collect { event ->
            if (event is UiEvent.AlertDeleted) {
                toastMessage = ToastMessage("已删除", ToastType.SUCCESS)
            }
        }
    }

    val pageForeground = isUfiPageForeground()
    var hasLoadedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(pageForeground) {

        if (!pageForeground || hasLoadedOnce) return@LaunchedEffect
        hasLoadedOnce = true
        // 监控设置真源在后端：采集开关 = AppSettings.backgroundServiceEnabled，
        // 个性化 7 项 = AppSettings.monitorPreferences（T40-5）。先回读再取数：
        // 一是别端（设置页 / web）改过之后这里不能还显示自己那份旧值，
        // 二是首屏时间范围要用回读后的 defaultHours，否则会按旧值白拉一遍。
        viewModel.dashboard.refreshMonitorSettingsFromBackend()
        // ★ 2026-09-02 懒加载：首屏**只定窗口、不拉数据**。
        // 过去这里一发就是 8 类指标全量 + 告警，而首屏真正看得见的只有总览的 hero 和 6 格；
        // 图表 Tab 的 4 个区块、以及折叠以下的内容全是白拉。现在改成：
        //   - 窗口由这里定（总览的 6 格与 hero 硬性只统计"今天 00:00 起"）；
        //   - 谁需要数据谁自己拉 —— 区块在 LazyColumn 里，组合即代表可见（见各区块的
        //     LaunchedEffect 与 DashboardModule.loadMonitorTypes 的说明）。
        viewModel.dashboard.selectMonitorRange(MonitorTimeRange.today())
        viewModel.dashboard.loadStartupTime()
    }

    // 总览「今日流量」格读的是 dashboardState.trafficSummary，而它只有 refreshDashboard() 会写：
    // 全局只在 App 启动（DashboardModule.init）和仪表盘页轮询时调用。直接进监控页（深链/底栏跳转）
    // 且启动那次拉取失败，这张卡就会一直停在"暂无数据"；停留期间也不会跟着日期走。
    //
    // 性能：这个复合请求会带来 2 次 dashboardState 发射，不能和首屏组合、页面转场动画抢同一帧。
    // 所以只有「确实没有数据」时才立刻拉，其它情况等首屏稳定后（1.5s）再补一次。
    LaunchedEffect(pageForeground) {
        if (!pageForeground) return@LaunchedEffect
        if (dashboardState.trafficSummary == null) {
            viewModel.dashboard.refreshDashboard()
        } else {
            delay(1_500)
            viewModel.dashboard.refreshDashboard()
        }
    }

    var lifecycleResumed by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            lifecycleResumed = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lifecycleResumed = false
        }
    }

    LaunchedEffect(lifecycleResumed, pageForeground, monitorState.selectedRange) {
        if (!lifecycleResumed || !pageForeground) return@LaunchedEffect
        while (true) {
            delay(monitorState.settings.refreshIntervalSec.coerceIn(10, 3600) * 1000L)
            val range = monitorState.selectedRange
            when {
                // ★ 懒加载的刷新侧：只刷「已经露过面」的指标（refreshVisibleMonitorTypes），
                //   用户没看过的图表不该在后台一直被刷。告警也改成 silent —— 原来每轮都会把
                //   isLoading 翻一次 true，顶部横条跟着闪。
                range is MonitorTimeRange.Preset -> {
                    viewModel.dashboard.refreshVisibleMonitorTypes(range)
                    viewModel.dashboard.loadAlerts(silent = true)
                }
                range is MonitorTimeRange.Custom && range.isRealTime -> {
                    viewModel.dashboard.loadMonitorHistoryIncremental()
                    viewModel.dashboard.loadAlerts(silent = true)
                }
                else -> return@LaunchedEffect
            }
        }
    }

    LaunchedEffect(pageForeground) {
        if (!pageForeground && hasLoadedOnce) {
            val range = monitorState.selectedRange
            val isTodayDefault = range is MonitorTimeRange.Custom && range.startMs == MonitorTimeRange.todayStartMs()
            if (!isTodayDefault) {
                // 离页只把窗口拨回今天，不发请求：下次进来由可见区块自己拉。
                viewModel.dashboard.selectMonitorRange(MonitorTimeRange.today())
            }
        }
    }

    // 切回「总览」Tab 时把数据窗口拉回今天：总览的 6 格与 hero 硬性只统计"今天 00:00 起"，
    // 而两个 Tab 共用同一份 history。在图表页选过历史区间（如 8 月 1 日）再切回来，
    // 序列里全是那天的点 → 6 格全"暂无数据"、hero 反而说"运行正常"。
    // 只改窗口即可：窗口一变，总览里可见的区块（hero / 6 格）的 LaunchedEffect(range) 会重拉。
    LaunchedEffect(overviewTab, hasLoadedOnce) {
        if (!hasLoadedOnce || overviewTab != MonitorOverviewTab.OVERVIEW) return@LaunchedEffect
        val range = monitorState.selectedRange
        val isTodayDefault = range is MonitorTimeRange.Custom && range.startMs == MonitorTimeRange.todayStartMs()
        if (!isTodayDefault) {
            viewModel.dashboard.selectMonitorRange(MonitorTimeRange.today())
        }
    }

    // 图表 Tab 不再自作主张换窗口：默认就是「今天」（首屏与总览一致）。
    // 原来首次切到图表 Tab 会套用偏好 defaultHours（默认 24 小时 → Preset(24)），
    // 于是"默认范围"跟总览不一致，X 轴刻度也被迫带上日期。defaultHours 仍供 web 端使用。


    val palette = LocalResolvedPalette.current
    // 2026-09-05 P1：胶囊底部遮挡预留**不再在组合期读**。
    // `LocalCapsuleBottomInset` 已改为 `() -> Dp`（理由见其 KDoc：它是 staticCompositionLocalOf，
    // 值一变整棵 NavHost 子树无条件重组，而胶囊在 pop 第 2~3 帧才发布真值）。
    // 现在由两个 LazyColumn 末尾的 `Spacer(Modifier.ufiCapsuleBottomInset(Spacing.Medium))`
    // 在 **layout 阶段** 读，inset 变化只重排那一个占位节点。

    UfiScreenScaffold(
        title = "监控中心",
        navController = navController,
        showBack = showBack,
        actions = {
            // 只留一个齿轮。「告警设置」是监控设置里的一个入口（2026-09-08 从这里挪进去）——
            // 顶栏并排两个图标要用户先猜哪个是哪个，而它本身就属于"监控的设置"。
            IconButton(onClick = { navController.navigate(Routes.DETAIL_MONITOR_SETTINGS) }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "监控设置",
                    tint = palette.textSecondary
                )
            }
        }
    ) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                MonitorTopTab(selected = overviewTab, onSelect = { overviewTab = it })
                Spacer(Modifier.height(Spacing.Medium))

                when (overviewTab) {
                    MonitorOverviewTab.OVERVIEW -> {
                        MonitorOverviewTabContent(
                            monitorState = monitorState,
                            dashboardState = dashboardState,
                            viewModel = viewModel,
                            navController = navController,
                            onDeleteAlert = { pendingDeleteAlertId = it }
                        )
                    }
                    MonitorOverviewTab.CHARTS -> {
                        MonitorChartsTabContent(
                            monitorState = monitorState,
                            viewModel = viewModel,
                            onShowDateRange = { customDialogVisible = true }
                        )
                    }
                }
            }
            UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
        }
    }

    CustomRangeDialog(
        visible = customDialogVisible,
        onDismiss = { customDialogVisible = false },
        onConfirm = { custom ->
            customDialogVisible = false
            viewModel.dashboard.selectMonitorRange(custom)
        },
        minDateMs = monitorState.startupTimeMs,
        maxDateMs = System.currentTimeMillis()
    )

    val pendingDeleteAlert = pendingDeleteAlertId?.let { id ->
        monitorState.alerts.firstOrNull { it.id == id }
    }
    UfiCustomDialog(
        visible = pendingDeleteAlertId != null,
        onDismiss = { pendingDeleteAlertId = null },
        title = "删除事件",
        confirmButton = {
            UfiButton(text = "删除", onClick = {
                pendingDeleteAlertId?.let { id -> viewModel.dashboard.deleteAlert(id) }
                pendingDeleteAlertId = null
            })
        },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = { pendingDeleteAlertId = null })
        }
    ) {
        Text(
            text = "确定删除这条事件？删除后不可恢复。",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textPrimary
        )
        pendingDeleteAlert?.let { a ->
            Spacer(Modifier.height(Spacing.Small))
            Text(
                text = a.message,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── 2026-08-23 性能优化：监控中心子模块 ──

@Composable
private fun MonitorOverviewTabContent(
    monitorState: MonitorState,
    dashboardState: DashboardState,
    viewModel: MainViewModel,
    navController: NavHostController,
    onDeleteAlert: (Long) -> Unit
) {
    // 「今天」的起点要参与 remember key：否则跨过零点后，只要 alerts 内容没变（data class equals 相等）
    // 就不会重算，昨天的 midnight 会被一直沿用，hero 与 6 格仍在统计昨天。
    // 按小时分桶做 key：零点后最迟一小时内自动纠正，且不会每帧重算。
    val hourBucket = System.currentTimeMillis() / 3_600_000L
    val todayAlerts = remember(monitorState.alerts, hourBucket) {
        // todayStartMs() 每次都会 new 一个 Calendar（含 locale/时区解析），
        // 放在 filter 的 lambda 里就是每条告警算一次；提到循环外只算一次。
        val todayStart = MonitorTimeRange.todayStartMs()
        monitorState.alerts.filter { it.timestamp >= todayStart }
    }
    // hero 卡的 range 必须是稳定实例：MonitorTimeRange.today() 每次都是新的 Custom（含 currentTimeMillis），
    // 直接传就等于让这张卡每次重组都无法被跳过。
    val todayRange = remember(hourBucket) { MonitorTimeRange.today() }
    val (worstPeak, worstPeakCount) = remember(monitorState, hourBucket) { worstPeakSummary(monitorState) }
    val palette = LocalResolvedPalette.current

    // ★ 2026-09-02 懒加载：告警只在「总览 Tab 真被打开」时拉 —— 本函数只在该 Tab 下组合，
    // 所以组合本身就是"用户看到了"的信号。直接进图表 Tab 的用户不会白拉一次告警列表。
    //
    // 告警引擎开关（core `AlertConfig.enabled` 的镜像）跟着一起拉：关着时设备不检测、不入库，
    // 下面的事件列表恒为空 —— 必须把这个原因说出来，见 [AlertEngineOffCard]。
    val alertConfig by viewModel.alertPrefs.configFlow.collectAsState()
    // 镜像还没拉到（null）时不显示提示：那会儿还不知道引擎到底开着没，先说"关了"是猜的。
    val engineOff = alertConfig?.enabled == false
    LaunchedEffect(Unit) {
        viewModel.dashboard.loadAlerts()
        viewModel.tools.loadAlerts()
    }

    // FIX-6（2026-08-23 · 23:47）：监控中心「总览」Tab 改为按类型聚合视图。
    // 根因：上一次修复仅下放到了 DETAIL_EVENTS（事件中心全屏页），但用户打开 MAIN 路由下的
    // MonitorScreen 总览依然看到具体事件明细（截图 23:45 显示 500+ 条逐条堆叠），
    // 体验割裂，本质上是聚合视图没接上主屏。
    // 修复：默认走聚合（与事件中心保持一致），FilterChip 切换「按类型聚合 / 全部明细」，
    // 点击聚合行 → AlertTypeDetailDialog 显示具体事件 + 内置 10/页分页（避免主列表堆叠异常），
    // 多页时显示底部浮动分页条。
    var aggregateMode by rememberSaveable { mutableStateOf(true) }
    var currentPage by remember { mutableIntStateOf(0) }
    var detailTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    val overviewPageSize = 10

    val aggregatedRows = remember(todayAlerts) {
        todayAlerts
            .groupBy { it.type to it.level }
            .map { (key, rows) ->
                val (type, level) = key
                AggregateAlertRow(
                    type = type,
                    level = level,
                    count = rows.size,
                    unread = rows.count { !it.acknowledged },
                    lastTimestampMs = rows.maxOf { it.timestamp }
                )
            }
            .sortedByDescending { it.lastTimestampMs }
    }
    val overviewPageCount = ufiPageCount(aggregatedRows.size, overviewPageSize)
    // 行数缩小（轮询刷新 / 删除告警）时必须夹回最后一页：否则 currentPage 越界 → 列表空白，
    // 同时 pageCount 掉到 1 让分页条隐藏，用户被困在空页里翻不回来。
    LaunchedEffect(overviewPageCount) {
        currentPage = ufiClampPage(currentPage, overviewPageCount)
    }
    val overviewSlice = if (currentPage * overviewPageSize >= aggregatedRows.size) emptyList()
        else aggregatedRows.subList(
            currentPage * overviewPageSize,
            kotlin.math.min(currentPage * overviewPageSize + overviewPageSize, aggregatedRows.size)
        )

    Box(Modifier.fillMaxSize()) {
        // 列表状态提到外面：下面那条浮动分页条要按滚动方向自动隐藏，
        // 而判据（[rememberSmartPaginationBarVisible]）需要读列表的滚动位置。
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            // 与图表 Tab 对齐的两条降级提示：采集被停用时 6 格必然全是"暂无数据"，
            // 拉取失败时 hero 会平静地说"运行正常"——不给提示等于骗人。
            if (!monitorState.settings.collectEnabled) {
                item(key = "collectDisabled") {
                    Text(
                        text = "监控采集已停用",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.CardHorizontalMargin, vertical = Spacing.Small)
                    )
                }
            }
            // 2026-09-05：错误 item 已移除，改由 Activity 级全局错误浮层统一展示
            //（MainActivity 读 viewModel.globalError，MONITOR 来源的重试 = refreshCurrentRange()）。
            // 顺带修掉一个漏显：错误卡当年是个 LazyColumn item，列表滚下去它就被回收、错误也跟着没了。

            item(key = "hero") {
                // 2026-09-08：首屏空数据时的 UfiLinearLoading（M3 indeterminate 横条，无限循环动画）
                // 已删除，改成一行静态文案。横条本身没有进度信息，只是在动；数据到了卡片直接出现。
                MonitorLoadingHint(visible = monitorState.isLoading && monitorState.alerts.isEmpty())
                EventSummaryCard(
                    alerts = todayAlerts,
                    range = todayRange,
                    worstPeakLevel = worstPeak,
                    worstPeakCount = worstPeakCount,
                    onEventsClick = { navController.navigate(Routes.DETAIL_EVENTS) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item(key = "grid") {
                // ★ 懒加载：6 格（今日峰值）要用到全部启用的指标，但只有它露面才拉。
                // key 带上 range 与启用集：窗口一换 / 设置里开关了指标，可见的它会自动重拉。
                val gridTypes = remember(monitorState.settings.enabledTypes) {
                    monitorState.settings.enabledTypes.toList()
                }
                // 可见性成对上报：滚出视口后轮询就不该再刷这些指标。
                DisposableEffect(gridTypes) {
                    viewModel.dashboard.registerVisibleMonitorTypes(gridTypes)
                    onDispose { viewModel.dashboard.unregisterVisibleMonitorTypes(gridTypes) }
                }
                LaunchedEffect(monitorState.selectedRange, gridTypes) {
                    viewModel.dashboard.loadMonitorTypes(
                        types = gridTypes,
                        range = monitorState.selectedRange,
                    )
                }
                PeakMetricsGrid(
                    state = monitorState,
                    trafficSummary = dashboardState.trafficSummary,
                    modifier = Modifier.padding(top = Spacing.Medium)
                )
            }

            item { Spacer(Modifier.height(Spacing.Medium)) }

            // FIX-6：聚合/明细模式切换，与事件中心保持一致两 chip 默认聚合。
            item(key = "overviewFilterChips") {
                // 2026-08-31：两个手绘 FilterChip → 公共 UfiSingleChipSelector。
                // 选中态由「实底 accent + onAccent 文字」变为公共基线「chipSelectedBg 淡底 + accent 文字」。
                UfiSingleChipSelector(
                    options = listOf("aggregate" to "按类型聚合", "detail" to "全部明细"),
                    selectedValue = if (aggregateMode) "aggregate" else "detail",
                    onSelect = { value ->
                        aggregateMode = value == "aggregate"
                        currentPage = 0
                        detailTarget = null
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    wrapContent = true
                )
            }

            if (engineOff && todayAlerts.isEmpty()) {
                // 引擎关着时事件列表必然是空的，而空态写的是「今日暂无异常事件」——
                // 那句话在这种情况下是假的：不是没异常，是根本没在看。所以**整块换掉空态**，
                // 而不是在空态上面再叠一张提示卡（2026-09-08 改：原来是插在列表前的独立 item）。
                //
                // 有历史事件时不换：那些是引擎关之前产生的，列表本身就该显示，
                // 此时再顶一张"没开启"的卡会盖住用户真正要看的内容。
                item(key = "engineOff") {
                    AlertEngineOffCard(
                        onEnable = {
                            alertConfig?.let { viewModel.tools.updateAlertConfig(it.copy(enabled = true)) }
                        },
                        onOpenSettings = { navController.navigate(Routes.DETAIL_ALERT_SETTINGS) },
                        canEnable = alertConfig != null,
                        modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin, vertical = 4.dp)
                    )
                }
            } else if (todayAlerts.isEmpty() && !monitorState.isLoading) {
                item(key = "empty") {
                    // fillParentMaxSize() 会让这条提示自己占满一屏——它上面还有 hero + 6 格 + chips，
                    // 结果「今日暂无异常事件」被整屏空白顶到折叠以下，要再滚一屏才看得到。
                    UfiEmptyState(
                        icon = Icons.Default.CheckCircle,
                        message = "今日暂无异常事件",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp)
                    )
                }
            } else if (aggregateMode) {
                // 聚合视图：每行 = 类型 + 累计次数 + 最近时间 + 等级 chip；不显示具体 message。
                items(
                    items = overviewSlice,
                    key = { "${it.type}-${it.level}" }
                ) { row ->
                    AggregatedAlertRowView(
                        row = row,
                        onClick = { detailTarget = row.type to row.level },
                        modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin, vertical = 4.dp)
                    )
                }
            } else {
                // 明细视图：保留原 MonitorEventCard 逐条渲染（兼容用户想看详情的场景）。
                items(
                    items = todayAlerts,
                    key = { it.id }
                ) { alert ->
                    com.ufi_axis.ui.components.MonitorEventCard(
                        alert = alert,
                        onAckOne = { id -> viewModel.dashboard.ackAlert(id) },
                        onDelete = onDeleteAlert,
                        modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin, vertical = 4.dp)
                    )
                }
            }

            // 胶囊底部遮挡预留。语义等价于原来的
            // `contentPadding = PaddingValues(bottom = capsuleInset + Spacing.Medium)`
            // （都是在内容末尾追加一段可滚动空白、不缩小视口），但高度在 **layout 阶段** 读
            // ⇒ 胶囊重挂后发布真值时只重排这一个节点，不再整树重组（2026-09-05 P1）。
            item(key = "capsuleBottomInset") {
                Spacer(Modifier.ufiCapsuleBottomInset(Spacing.Medium))
            }
        }

        // 多页时显示浮动分页条（仅聚合模式生效，明细模式不分页）。
        //
        // 2026-09-05（P3）：可见性从恒真的 `AnimatedVisibility(visible = true)` 改为
        // 与事件中心同一份 [rememberSmartPaginationBarVisible]。原写法外层已有
        // `if (aggregateMode && overviewPageCount > 1)` 硬条件，里面再套 `visible = true`
        // 就是个恒真壳：exit 永不触发，enter 在首次组合时也不播（visible 初值即 true），
        // 两条 UfiMotion 规格是纯死代码。本页与事件中心的浮动分页条外观完全一致
        // （同一个 [UfiPagination] 的 Floating 形态、同一档入离场规格、同样悬在底部），
        // 可见性策略却一处自动隐藏、一处常显 —— 这正是本仓一直在收敛的那类不一致
        // （见 UfiPagination.kt 的 2026-09-04 三合一注释）。统一后规格也真正接上了驱动。
        if (aggregateMode && overviewPageCount > 1) {
            val paginationBarVisible = rememberSmartPaginationBarVisible(
                listState = listState,
                currentPage = currentPage,
                pageCount = overviewPageCount
            )
            AnimatedVisibility(
                visible = paginationBarVisible,
                // 2026-09-04（P2b）：入场原为裸 `tween(320)` → UfiMotion.Duration.Sweeping（同为 320，
                // 零观感变化）；离场那行早已走 Duration.Base，两边现在都在梯度上。
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(animationSpec = tween(UfiMotion.Duration.Sweeping)),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(animationSpec = tween(UfiMotion.Duration.Base)),
                modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-40).dp)
            ) {
                UfiPagination(
                    currentPage = currentPage,
                    pageCount = overviewPageCount,
                    onPrev = { if (currentPage > 0) currentPage-- },
                    onNext = { if (currentPage < overviewPageCount - 1) currentPage++ },
                    modifier = Modifier
                        .wrapContentWidth(Alignment.CenterHorizontally)
                        .padding(horizontal = Spacing.CardHorizontalMargin),
                    variant = UfiPaginationVariant.Floating,
                    // 总览 Tab 没有跳页弹窗（原实现传的是空 lambda）：传 null 让页码退化为纯文本，
                    // 不再是"点了没反应"的假按钮。
                    onJumpClick = null
                )
            }
        }

        // 聚合行点击 → AlertTypeDetailDialog 显示具体事件 + 内置分页。
        // 数据源必须与聚合行同口径（todayAlerts）：用全量 alerts 会出现"行上写发生 3 次、
        // 点进去弹窗说共 43 条并列出昨天的事件"。
        AlertTypeDetailDialog(
            visible = detailTarget != null,
            type = detailTarget?.first,
            level = detailTarget?.second,
            allRows = todayAlerts,
            pageSize = overviewPageSize,
            onDismiss = { detailTarget = null },
            onAckOne = { id -> viewModel.dashboard.ackAlert(id) },
            onDeleteOne = onDeleteAlert
        )
    }
}

@Composable
private fun MonitorChartsTabContent(
    monitorState: MonitorState,
    viewModel: MainViewModel,
    onShowDateRange: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val settings = monitorState.settings
    val chartColors = ChartColors(palette.isDark)

    val range = monitorState.selectedRange
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val rangeText = remember(range, dateFmt) {
        when (range) {
            is MonitorTimeRange.Preset -> when (range.hours) {
                1 -> "近 1 小时"
                6 -> "近 6 小时"
                24 -> "近 24 小时"
                168 -> "近 7 天"
                else -> "近 ${range.hours} 小时"
            }
            is MonitorTimeRange.Custom -> {
                val s = range.startMs
                val e = range.endMs
                if (s == MonitorTimeRange.todayStartMs()) "今天"
                else "${dateFmt.format(Date(s))} ~ ${dateFmt.format(Date(e))}"
            }
        }
    }
    val showReset = range is MonitorTimeRange.Custom && range.startMs != MonitorTimeRange.todayStartMs()

    // X 轴 / tooltip 时间格式：选中「今天」语义（近 24 小时内、或自定义从今日 0 点起）不显示日期，
    // 其它范围（近 7 天、跨天自定义等）显示「日期 + 时间」。直接按所选范围判定，
    // 避免「选了 7 天但设备早期无数据、实际点集只跨 1 天」时仍不显示日期（空桶被服务端跳过）。
    val timeRangeStr = remember(monitorState.selectedRange) {
        val r = monitorState.selectedRange
        when (r) {
            is MonitorTimeRange.Preset -> if (r.hours <= 24) "today" else "week"
            is MonitorTimeRange.Custom -> if (r.startMs == MonitorTimeRange.todayStartMs()) "today" else "week"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.CardHorizontalMargin)
            .clickable { onShowDateRange() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "当前范围：$rangeText",
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (showReset) {
            TextButton(onClick = { viewModel.dashboard.selectMonitorRange(MonitorTimeRange.today()) }) {
                Text("重置", style = MaterialTheme.typography.labelLarge, color = palette.accent)
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "选择时间范围",
            tint = palette.textSecondary
        )
    }
    Spacer(Modifier.height(Spacing.Medium))

    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        if (!settings.collectEnabled) {
            item {
                Text(
                    text = "监控采集已停用",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.CardHorizontalMargin, vertical = Spacing.Small)
                )
            }
        }

        item {
            MonitorLoadingHint(visible = monitorState.isLoading && monitorState.cpuHistory.isEmpty())
        }

        // 2026-09-05：错误 item 已移除，见本文件上方同类注释（全局错误浮层）。

        // 一类指标一张图（8 张）。原来是 4 张卡各塞两条线（CPU+内存、上/下行、RSRP+SINR、
        // 电池+温度）：两条线共用一根 Y 轴，量级差一点就有一条被压在轴底（电池 0~100 与
        // 温度 30~60 同轴、RSRP≈-100 与 SINR≈10 同轴都是这个毛病），Y 轴单位也只能迁就其中一条。
        // 拆开后每张图各自缩放、各自用自己的单位格式化。

        // ── 同屏各图共用一条 X 轴时间标尺（2026-09-04）──
        //
        // 现象：上下行速率图的终点与其他图对不齐。
        // 根因不在数据，而在**每张图各自取自己点集的 min/max 当轴边界，再拉满整个绘图宽度**
        // （UfiChart 的 tBounds）。而各指标"当前桶是否已经有行"本来就不同步：
        // `traffic_records` 每 tick 无条件写库，signal 全 0 会跳过、battery 30s 一轮，
        // 空桶服务端又不返回行 —— 于是速率图往往先长出最新那个桶。末桶差一个，
        // 右边缘就代表不同时刻，两张图的 px/ms 比例还不一样，横向对读时间失去意义。
        //
        // 解法：把域抬到这里算**一次**，传给所有图。取服务端那套桶心网格
        // （core 的 aggregateBetween：`center(bucket) = bucket * bucketMs + bucketMs / 2`），
        // 于是标尺与数据点天然同源；某张图数据更旧就老实停在中间，不再假装到达右端。
        // O(1)，不扫点集；在这里算一次也保证 8 张图拿到的是**同一个数**（各图自己算会
        // 因为 queryEndMs 是"现在"而各自取到不同的值）。
        // bucketMs <= 0（旧 core 不回 bucket_ms）时退回 null = 各图自适应，行为与改动前一致。
        // 注意：这里是 LazyListScope（非 @Composable）作用域，**不能用 remember** ——
        // 纯算术，一次重组算一次的开销可以忽略。
        val sharedRange = monitorState.selectedRange
        val sharedBucketMs = monitorState.bucketMs
        val sharedXDomain: Pair<Long, Long>? = run {
            if (sharedBucketMs <= 0L) return@run null
            val startMs = sharedRange.queryStartMs
            val endMs = sharedRange.queryEndMs
            if (endMs <= startMs) return@run null
            val half = sharedBucketMs / 2
            val domainStart = (startMs / sharedBucketMs) * sharedBucketMs + half
            val domainEnd = (endMs / sharedBucketMs) * sharedBucketMs + half
            if (domainEnd > domainStart) domainStart to domainEnd else null
        }

        MonitorMetricType.entries.forEach { metric ->
            if (!settings.enabledTypes.contains(metric.apiKey)) return@forEach
            item(key = metric.apiKey) {
                MonitorChartSection(
                    title = metric.label,
                    monitorState = monitorState,
                    types = listOf(metric),
                    enabledTypes = settings.enabledTypes,
                    chartColors = chartColors,
                    timeRangeStr = timeRangeStr,
                    range = monitorState.selectedRange,
                    xDomainStartMs = sharedXDomain?.first,
                    xDomainEndMs = sharedXDomain?.second,
                    onNeedTypes = { needed ->
                        viewModel.dashboard.loadMonitorTypes(needed, monitorState.selectedRange)
                    },
                    onVisibilityChanged = { types, visible ->
                        if (visible) viewModel.dashboard.registerVisibleMonitorTypes(types)
                        else viewModel.dashboard.unregisterVisibleMonitorTypes(types)
                    }
                )
            }
        }

        // 胶囊底部遮挡预留（layout 阶段读，见 Modifier.ufiCapsuleBottomInset / 2026-09-05 P1）。
        item(key = "capsuleBottomInset") {
            Spacer(Modifier.ufiCapsuleBottomInset(Spacing.Medium))
        }
    }
}


@Composable
private fun MonitorChartSection(
    title: String,
    monitorState: MonitorState,
    types: List<MonitorMetricType>,
    enabledTypes: Set<String>,
    chartColors: ChartColors,
    timeRangeStr: String,
    range: MonitorTimeRange,
    onNeedTypes: (List<String>) -> Unit,
    onVisibilityChanged: (List<String>, Boolean) -> Unit,
    unit: String? = null,
    yAxisLabelFormatter: ((Double) -> String)? = null,
    // 2026-09-04：同屏各图共用的 X 轴时间域（由 MonitorChartsTabContent 统一算一次后下发，
    // null = 图表按自己的点集自适应 = 改动前的行为）。见调用点那段说明。
    xDomainStartMs: Long? = null,
    xDomainEndMs: Long? = null
) {
    val shownTypes = remember(types, enabledTypes) { types.filter { enabledTypes.contains(it.apiKey) } }
    if (shownTypes.isEmpty()) return

    val apiKeys = remember(shownTypes) { shownTypes.map { it.apiKey } }

    // ★ 2026-09-02 懒加载：本区块在 LazyColumn 的 item 里，能执行到这儿就说明它已进入
    // 视口附近（LazyColumn 只组合可见范围的 item）—— 组合即可见性信号，不需要额外检测。
    // 于是每个区块只拉**自己那两类**指标：首屏从"8 类全拉"降到"看得见的 2~4 类"。
    // key 带上 range：切区间后仍然可见的区块会自动重拉，没露面的等它露面。
    // 重复触发（滚动来回、重组）由 DashboardModule.loadMonitorTypes 的记账去重。
    //
    // 可见性要**成对**上报：只有"进入"没有"离开"的话，轮询侧会把滚出视口的图表也一直刷，
    // 懒加载在刷新侧就等于没做。
    DisposableEffect(apiKeys) {
        onVisibilityChanged(apiKeys, true)
        onDispose { onVisibilityChanged(apiKeys, false) }
    }
    LaunchedEffect(range, apiKeys) { onNeedTypes(apiKeys) }

    val primary = shownTypes[0]

    // 2026-08-24（FIX-18）：按当前时间范围过滤数据——historyFor 返回的是完整历史序列，
    // 后端/mergePoints 可能保留窗口外（跨天）的点；图表层必须裁剪到 [queryStartMs, queryEndMs]，
    // 否则"今天"范围里会绘制出昨天/更早的数据。
    //
    // 性能（2026-08-26）：裁剪与 UfiDownsampledPoint 转换全部收进同一个 remember。
    // 原来 startMs/endMs 直接写在 remember 的 key 里，而实时区间的 queryEndMs 就是
    // System.currentTimeMillis() —— key 每次求值都不同，这个 remember 从来没生效过；
    // primary 那一份 `filter{}` 更是直接写在函数体里，每次重组都重新拷 ≤720 个点。
    // 现在 key 用 range 实例本身（selectedRange 只在切换范围/加载时才换），窗口端点在块内取一次。
    //
    // 2026-08-26（第二轮）：key 从整个 monitorState 收窄到「本区块真正用到的那几条历史序列」。
    // monitorState 任何字段变化（告警补页、isLoading 翻转、settings…）原来都会让 4 个区块
    // 各自重建 ≤240 个 UfiDownsampledPoint，并让 points 换实例 → 图表几何跟着重建。
    val rawSeries = shownTypes.map { historyFor(monitorState, it) }
    // 2026-09-04（「曲线画不到右端终点」的 app 侧根因）：裁剪上下界各放宽半个桶宽。
    // 服务端返回的 t 是**桶心**、不是桶起点 —— core/api/.../MonitorRoutes.kt 的 aggregateBetween 里
    // `center(bucket) = bucket * bucketMs + bucketMs / 2`；而实时区间的 queryEndMs 就是"现在"。
    // 当前这个桶刚开头（now 落在桶的前半段）时它的桶心必然 > now，于是 `it.t in startMs..endMs`
    // 会把**最新那个点**整点裁掉：曲线停在上一个桶，右端空出一截，看着就是"画不到终点"。
    // 只要 now 落在桶前半段就会命中，也就是大约一半的刷新都会缺最新点；越是盯着看当前值的图
    // （上行/下行速率）越容易发现，因为那张图的意义几乎全在末点上。
    // 半个桶宽正好是"桶心相对桶起点的偏移量"：加上它刚好收住当前桶，又不可能放进下一个桶。
    // 下界同理往左放半桶 —— 首个桶的桶心也可能落在 queryStartMs 之前，会被同样的方式裁掉。
    // bucketMs 进 remember key：它一变（换区间 / 首次拿到服务端桶宽）裁剪边界就得跟着重算。
    // bucketMs <= 0（旧 core 不回 bucket_ms）时 halfBucketMs=0，退回原来的硬边界，行为不变。
    val bucketMs = monitorState.bucketMs
    val chartData = remember(rawSeries, chartColors, range, bucketMs) {
        val halfBucketMs = if (bucketMs > 0L) bucketMs / 2 else 0L
        val startMs = range.queryStartMs - halfBucketMs
        val endMs = range.queryEndMs + halfBucketMs
        val primaryPoints = rawSeries[0].filter { it.t in startMs..endMs }
        val series = shownTypes.mapIndexed { index, type ->
            val raw = if (index == 0) primaryPoints else rawSeries[index].filter { it.t in startMs..endMs }
            MonitorSeries(
                points = toUfiPoints(raw),
                lineColor = when (type) {
                    MonitorMetricType.CPU -> chartColors.cpu
                    MonitorMetricType.MEMORY -> chartColors.memory
                    MonitorMetricType.TRAFFIC_RX -> chartColors.trafficRx
                    MonitorMetricType.TRAFFIC_TX -> chartColors.trafficTx
                    MonitorMetricType.SIGNAL_RSRP -> chartColors.signalRsrp
                    MonitorMetricType.SIGNAL_SINR -> chartColors.signalSinr
                    MonitorMetricType.BATTERY -> chartColors.battery
                    MonitorMetricType.TEMPERATURE -> chartColors.temperature
                },
                label = type.label,
                valueFormatter = yFormatterFor(type)
            )
        }
        primaryPoints to series
    }
    val primaryPoints = chartData.first
    val allSeries = chartData.second

    // 2026-08-24（FIX-18）：所有走 valueFormatter 的图表其 formatter 已含单位（如 pctAxisLabel→"51%"、
    // formatBytes→"389.0 KB/s"、信号→"dBm"），UfiMonitorChart 的 formatWithUnit 会再拼 unit——
    // 故这里 unit 一律传空，避免 "51%%" / "389.0 KB/sB/s" 这类双重单位。
    val effectiveUnit = ""

    // 2026-08-24：图表卡片用紧凑 padding（默认 CardPadding=20dp 偏大，让卡片上下留白过多）。
    // 紧凑到 12dp 上下 + 14dp 左右，让卡片更显瘦。
    MonitorSectionCard(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
        ChartSectionTitle(title = title) {
            SeriesLegendChips(allSeries = allSeries)
        }
        FixedMonitorChart(
            // 卡片标题（视觉上仍合并展示"系统资源"便于一眼识别）
            title = title,
            // series 名固定为 primary.label（避免与 extraSeries 中的 secondary.label 语义重叠，
            //   否则 tooltip 会把 "CPU 使用率 / 内存占用" 整体显示在 series 0 行造成重复迷惑）
            seriesLabel = primary.label,
            points = primaryPoints,
            unit = effectiveUnit,
            lineColor = allSeries[0].lineColor,
            isLoading = monitorState.isLoading,
            valueFormatter = yFormatterFor(primary),
            yAxisLabelFormatter = yAxisLabelFormatter ?: yAxisFormatterFor(primary),
            showLegend = false,
            symmetricHorizontal = true,
            extraSeries = allSeries.drop(1),
            timeRange = timeRangeStr,
            // 「Y 轴固定」只对有公认定义域的指标生效（百分比 / 摄氏度）；
            // 流量与信号没有固定量程，硬给一个只会把曲线压成一条直线。
            fixedYRange = if (monitorState.settings.fixedYAxis) fixedYRangeFor(primary) else null,
            fillAlpha = monitorState.settings.fillAlpha,
            // 桶宽来自服务端响应（state.bucketMs）：图表据此把真正的采集空洞断开，
            // 否则采集中断那几小时会被画成一条平直的假数据。
            // 注意这是**全区间一个值**，且它只是绘图分辨率（可见区间 / MAX_POINTS），不等于采集间隔；
            // 图表内部只把它当断档判定的下限，真正的基准是各序列自己的间距中位数。
            bucketMs = monitorState.bucketMs,
            // 同屏各图共用的时间标尺（2026-09-04，见 MonitorChartsTabContent 那段说明）
            xDomainStartMs = xDomainStartMs,
            xDomainEndMs = xDomainEndMs
        )
    }
}

/**
 * 「告警引擎没开」提示卡（2026-09-08）。
 *
 * 引擎关着（core `AlertConfig.enabled = false`）时设备既不检测也不入库，事件列表恒为空，
 * 而空态写的是「今日暂无异常事件」—— 那句话在这种情况下是假的：不是没异常，是根本没在看。
 * 所以把原因、一键开启、以及去调阈值的入口摆在列表最前面。
 *
 * @param canEnable 配置镜像是否已拉到。没拉到就不能写：那会用本地默认值 + 旧 version 提交，
 *                  可能把设备上的阈值整体重置成默认值（同 [AlertSettingsScreen] 的 configLoaded 守卫）。
 */
@Composable
private fun AlertEngineOffCard(
    onEnable: () -> Unit,
    onOpenSettings: () -> Unit,
    canEnable: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = UfiCardDefaults.shape,
        color = palette.warning.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, palette.warning.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(Spacing.InnerPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = palette.warning
                )
                Spacer(Modifier.width(Spacing.Small))
                Text(
                    text = "设备告警引擎未开启",
                    style = UfiTextStyles.sectionTitle,
                    color = palette.textPrimary
                )
            }
            Text(
                text = "设备当前不检测温度、电量、流量、信号等异常，所以这里不会出现任何事件。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                UfiButton(
                    text = "立即开启",
                    size = UfiButtonSize.Small,
                    enabled = canEnable,
                    onClick = onEnable
                )
                UfiButton(
                    variant = UfiButtonVariant.Secondary,
                    size = UfiButtonSize.Small,
                    text = "告警设置",
                    onClick = onOpenSettings
                )
            }
            if (!canEnable) {
                Text(
                    text = "正在读取告警配置，读取完成后才能开启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary.copy(alpha = 0.75f)
                )
            }
        }
    }
}

// ==================== 事件中心独立全屏页内容（v26 · 方案 A） ====================

@Composable
fun EventsCenterContent(viewModel: MainViewModel) {
    val monitorState by viewModel.monitorState.collectAsState()
    val palette = LocalResolvedPalette.current
    val bottomPadding = Spacing.Medium

    var pendingDeleteAlertId by remember { mutableStateOf<Long?>(null) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    // 「确认已恢复」要等 API 回来才知道改了几条，用它起协程；不进 ViewModel 状态。
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        viewModel.dashboard.events.collect { event ->
            if (event is UiEvent.AlertDeleted) {
                toastMessage = ToastMessage("已删除", ToastType.SUCCESS)
            }
        }
    }

    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(EVENTS_PREFS, Context.MODE_PRIVATE) }
    var pageSize by remember { mutableStateOf(loadEventsPageSize(prefs)) }

    var eventLevelFilter by rememberSaveable { mutableStateOf("all") }
    var eventTypeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var eventReadFilter by rememberSaveable { mutableStateOf("all") }
    var sortMode by rememberSaveable { mutableStateOf(EventSortMode.TimeDesc) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showPageSizeDialog by remember { mutableStateOf(false) }
    var showJumpPageDialog by remember { mutableStateOf(false) }
    var eventDateKey by remember { mutableStateOf("all") }
    var eventCustomRange by remember { mutableStateOf<MonitorTimeRange.Custom?>(null) }

    // FIX-3（2026-08-23）：事件中心列表"按类型聚合"模式开关。默认 true。
    // 此前每条 AlertRecord 单卡一行，导致 500+ 条堆叠、每张卡展示完整 message 冗余；
    // 改为默认按 (type, level) 聚合，每行仅显示"类型 + 累计次数 + 最近时间 + 等级 chip"，
    // 点击打开 UfiScrollableDialog 弹窗显示该类型具体事件，内置 20/页分页 + ack。
    var aggregateMode by rememberSaveable { mutableStateOf(true) }
    // 弹窗展示哪个 (type, level) 的具体事件明细（null = 关闭）
    var detailTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(eventDateKey, eventCustomRange) {
        when (eventDateKey) {
            "all" -> viewModel.dashboard.loadAllAlerts()
            "today" -> viewModel.dashboard.loadAlerts(MonitorTimeRange.today())
            "yesterday" -> viewModel.dashboard.loadAlerts(
                MonitorTimeRange.Custom(localDayStartMs(-1), localDayEndMs(-1))
            )
            "7d" -> viewModel.dashboard.loadAlerts(MonitorTimeRange.last7Days())
            "custom" -> eventCustomRange?.let { viewModel.dashboard.loadAlerts(it) }
        }
    }

    val levelCounts = mapOf(
        "all" to monitorState.alerts.size,
        "warning" to monitorState.alerts.count { it.level == "warning" || it.level == "critical" },
        "critical" to monitorState.alerts.count { it.level == "critical" }
    )
    val filteredAlerts = remember(
        monitorState.alerts, eventLevelFilter, eventTypeFilter, eventReadFilter, sortMode,
        eventDateKey, eventCustomRange
    ) {
        var list = monitorState.alerts
        when (eventDateKey) {
            "today" -> {
                // 同上：todayStartMs() 提到 filter 外，避免每条告警 new 一个 Calendar
                val todayStart = MonitorTimeRange.todayStartMs()
                list = list.filter { it.timestamp >= todayStart }
            }
            "yesterday" -> {
                val ys = localDayStartMs(-1); val ye = localDayEndMs(-1)
                list = list.filter { it.timestamp in ys..ye }
            }
            "7d" -> list = list.filter { it.timestamp >= System.currentTimeMillis() - 7L * 86_400_000 }
            "custom" -> eventCustomRange?.let { r ->
                list = list.filter { it.timestamp in r.queryStartMs..r.queryEndMs }
            }
        }
        if (eventLevelFilter == "warning") list = list.filter { it.level == "warning" || it.level == "critical" }
        else if (eventLevelFilter == "critical") list = list.filter { it.level == "critical" }
        eventTypeFilter?.let { t -> list = list.filter { it.type == t } }
        if (eventReadFilter == "unread") list = list.filter { !it.acknowledged }
        else if (eventReadFilter == "read") list = list.filter { it.acknowledged }
        when (sortMode) {
            EventSortMode.TimeDesc -> list.sortedByDescending { it.timestamp }
            EventSortMode.TimeAsc -> list.sortedBy { it.timestamp }
            EventSortMode.SeverityDesc -> list.sortedWith(compareByDescending<com.ufi_axis.data.model.AlertRecord> { eventSeverityRank(it.level) }.thenByDescending { it.timestamp })
            EventSortMode.SeverityAsc -> list.sortedWith(compareBy<com.ufi_axis.data.model.AlertRecord> { eventSeverityRank(it.level) }.thenByDescending { it.timestamp })
        }
    }
    val activeFilterCount = (if (eventLevelFilter != "all") 1 else 0) +
        (if (eventTypeFilter != null) 1 else 0) + (if (eventReadFilter != "all") 1 else 0) +
        (if (eventDateKey != "all") 1 else 0)

    var currentPage by remember { mutableIntStateOf(0) }
    LaunchedEffect(eventLevelFilter, eventTypeFilter, eventReadFilter, eventDateKey, eventCustomRange, sortMode, pageSize) {
        currentPage = 0
    }
    LaunchedEffect(pageSize) {
        prefs.edit().putInt(KEY_EVENTS_PAGE_SIZE, pageSize).apply()
    }
    val pageStart = currentPage * pageSize
    val pageAlerts = if (pageStart >= filteredAlerts.size) emptyList()
        else filteredAlerts.subList(pageStart, kotlin.math.min(pageStart + pageSize, filteredAlerts.size))
    val pageCount = ufiPageCount(filteredAlerts.size, pageSize)

    // FIX-3：按 (type, level) 聚合（核心仅显示类型 + 次数 + 最近时间 + 等级 chip）。
    // 重复 (type, level) 行核心会被 AlertEngine 聚合为 count>1，但同一 (type, normal) + (type, warning)
    // 等多等级的同一类告警可能各自独立（正常现象），所以此处保持 (type, level) 二元聚合。
    val aggregatedAlerts = remember(filteredAlerts, sortMode) {
        filteredAlerts
            .groupBy { it.type to it.level }
            .map { (key, rows) ->
                val (type, level) = key
                val mostRecent = rows.maxOf { it.timestamp }
                val unread = rows.count { !it.acknowledged }
                AggregateAlertRow(
                    type = type,
                    level = level,
                    count = rows.size,
                    unread = unread,
                    lastTimestampMs = mostRecent
                )
            }
            .let { rows ->
                when (sortMode) {
                    EventSortMode.TimeDesc -> rows.sortedByDescending { it.lastTimestampMs }
                    EventSortMode.TimeAsc -> rows.sortedBy { it.lastTimestampMs }
                    EventSortMode.SeverityDesc -> rows.sortedWith(
                        compareByDescending<AggregateAlertRow> { eventSeverityRank(it.level) }
                            .thenByDescending { it.lastTimestampMs }
                    )
                    EventSortMode.SeverityAsc -> rows.sortedWith(
                        compareBy<AggregateAlertRow> { eventSeverityRank(it.level) }
                            .thenByDescending { it.lastTimestampMs }
                    )
                }
            }
    }
    val aggPageCount = ufiPageCount(aggregatedAlerts.size, pageSize)
    val aggPageStart = currentPage * pageSize
    val aggPageSlice = if (aggPageStart >= aggregatedAlerts.size) emptyList()
        else aggregatedAlerts.subList(aggPageStart, kotlin.math.min(aggPageStart + pageSize, aggregatedAlerts.size))
    // FIX-3：当前明细模式的有效分页（明细/聚合两种模式都基于 currentPage，但分页计数不同；底部条用最大的那个）
    // 当前模式下的**真实**总页数。2026-09-04 修 bug：这个值在收敛前是**死代码** —— 算出来了但
    // 全库无人消费，分页栏/跳页弹窗/可见性/底部留白全部用的是明细口径 `pageCount`。
    // 后果：500 条明细（25 页）而聚合只有 6 行（1 页）时，聚合模式下分页栏仍显示 "1 / 25" 且能翻到
    // 第 25 页，`aggPageSlice` 从第 2 页起恒为空 → 渲染出一个空 Column（不是空态提示，因为空态判据
    // 用的也是明细口径 `pageAlerts`，它非空）→ 用户看到一片纯白。
    val effectivePageCount = if (aggregateMode) aggPageCount else pageCount
    // 越界兜底：切模式、轮询刷新、删除事件都会让总页数缩小，`currentPage` 可能停在已不存在的页上。
    // 总览 Tab 早就有这段（见 MonitorOverviewTabContent 的 overviewPageCount clamp），事件中心漏了。
    LaunchedEffect(effectivePageCount) {
        currentPage = ufiClampPage(currentPage, effectivePageCount)
    }

    Box(Modifier.fillMaxSize()) {
        // 2026-09-08：由 Column(verticalScroll) 改为 LazyColumn —— 事件卡真正懒加载。
        // 原实现把整页 pageSize 条 MonitorEventCard 一次性全部组合（默认 20 条，可调到 100），
        // 进入 Tab / 翻页 / 切筛选都要同步组合完才出画面，那正是"卡一下"的来源。
        // 头部（统计卡 + 模式切换 + 筛选行）内容是一整块、不需要各自回收，装在单个 item 里。
        val listState = rememberLazyListState()
        val paginationBarVisible = rememberSmartPaginationBarVisible(
            listState = listState,
            currentPage = currentPage,
            pageCount = effectivePageCount
        )
        LaunchedEffect(currentPage) {
            // 翻页回顶部用瞬时 scrollToItem：animateScrollToItem 会让整页从旧位置"飞"回去，
            // 观感与刷新动画无异（新一页的内容已经换好了，滚动过程只是延迟看到它）。
            listState.scrollToItem(0)
        }
        val fabReservedBottom = if (effectivePageCount > 1) 56.dp else bottomPadding
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = fabReservedBottom)
        ) {
            item(key = "eventsHeader") {
            Column {
            MonitorLoadingHint(visible = monitorState.isLoading && monitorState.alerts.isEmpty())

            // 2026-09-05：错误横幅已移除，改由全局错误浮层统一展示（见本文件上方注释）。

            MonitorEventStatCards(
                alerts = monitorState.alerts,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(6.dp))

            // FIX-3：聚合/明细模式切换。两 chip 一组，默认「按类型聚合」。
            // 聚合视图：每行仅显示类型 + 累计次数 + 最近时间 + 等级 chip，悬于 5 Tab 顶部。
            // 明细视图：恢复原有每条事件独立 MonitorEventCard 渲染（v20k 风格）。
            // 2026-08-31：两个手绘 FilterChip → 公共 UfiSingleChipSelector（与本页概览 tab 同一套）
            UfiSingleChipSelector(
                options = listOf("aggregate" to "按类型聚合", "detail" to "全部明细"),
                selectedValue = if (aggregateMode) "aggregate" else "detail",
                // 切模式必须回第 1 页：两种模式的总页数口径完全不同（明细 25 页 / 聚合 1 页），
                // 留在原页码会直接落到聚合模式不存在的页上，看到一片空白。
                // 总览 Tab 的同名切换器一直有这句（见 :463 附近），事件中心此前漏了。
                onSelect = { value ->
                    aggregateMode = value == "aggregate"
                    currentPage = 0
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                wrapContent = true
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "筛选结果 · ${filteredAlerts.size}",
                    style = UfiTextStyles.panelTitle.copy(fontWeight = UfiWeight.Emphasis),
                    color = palette.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (monitorState.alerts.any { !it.acknowledged }) {
                    // 「全部已读」原来是裸 TextButton；2026-08-30 加了「确认已恢复」之后
                    // 这一行放不下两个文字按钮 + 筛选，所以合并成一个锚点菜单。
                    UfiPopupAnchor(
                        options = listOf(
                            UfiPopupOption(
                                id = "ack-all",
                                label = "全部已读",
                                icon = Icons.Filled.DoneAll,
                                onClick = { viewModel.dashboard.ackAllAlerts() }
                            ),
                            UfiPopupOption(
                                id = "ack-resolved",
                                label = "确认已恢复",
                                icon = Icons.Filled.CheckCircle,
                                onClick = {
                                    scope.launch {
                                        // 仍在持续的告警保持未读 —— 只清"问题已经消失"的那些，
                                        // 所以不需要二次确认，它不会掩盖当前故障。
                                        val updated = viewModel.dashboard.ackResolvedAlerts()
                                        toastMessage = when {
                                            updated == null -> ToastMessage("确认失败，请重试", ToastType.ERROR)
                                            updated == 0 -> ToastMessage("没有已恢复的事件", ToastType.INFO)
                                            else -> ToastMessage("已确认 $updated 条已恢复事件", ToastType.SUCCESS)
                                        }
                                    }
                                }
                            )
                        )
                    ) { toggle ->
                        TextButton(onClick = toggle) {
                            Text("批量已读", style = MaterialTheme.typography.labelLarge, color = palette.accent)
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
                OutlinedButton(
                    onClick = { showFilterDialog = true },
                    shape = UfiCardDefaults.buttonShape,
                    border = BorderStroke(1.dp, if (activeFilterCount > 0) palette.accent else palette.divider),
                    colors = if (activeFilterCount > 0) {
                        ButtonDefaults.outlinedButtonColors(containerColor = palette.accent, contentColor = palette.onAccent)
                    } else {
                        ButtonDefaults.outlinedButtonColors(contentColor = palette.accent)
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Filled.Tune, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("筛选", style = MaterialTheme.typography.labelLarge)
                    if (activeFilterCount > 0) {
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier.size(18.dp).clip(CircleShape).background(palette.error),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (activeFilterCount > 9) "9+" else activeFilterCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                MonitorSortButton(sortMode = sortMode, onClick = { showSortDialog = true })
                Spacer(Modifier.width(6.dp))
                EventsSettingsButton(onClick = { showPageSizeDialog = true })
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = eventSortModeLabel(sortMode),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                text = "日期 · " + when (eventDateKey) {
                    "all" -> "全部"
                    "today" -> "今天"
                    "yesterday" -> "昨天"
                    "7d" -> "近 7 天"
                    "custom" -> eventCustomRange?.let { alertRangeLabel(it) } ?: "自定义"
                    else -> "全部"
                },
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonitorLevelFilter(
                    selectedValue = if (eventReadFilter == "unread") "" else eventLevelFilter,
                    onSelect = {
                        eventLevelFilter = it
                        eventReadFilter = "all"
                    },
                    counts = levelCounts
                )
                Spacer(Modifier.width(8.dp))
                EventUnreadChip(
                    unreadCount = monitorState.alerts.count { !it.acknowledged },
                    selected = eventReadFilter == "unread",
                    onSelect = {
                        eventReadFilter = if (eventReadFilter == "unread") "all" else "unread"
                        eventLevelFilter = "all"
                    }
                )
            }
            Spacer(Modifier.height(Spacing.Small))
            }   // header Column
            }   // item("eventsHeader")

            // 列表本体：每条事件一个 LazyColumn item，滚到才组合。
            // 2026-09-08：翻页的 AnimatedContent（淡入 + 0.98→1 微缩放）已删除 —— 翻页是"换一批
            // 数据"而不是导航转场，内容淡入会让首屏可见时间多等一个 Duration.Smooth，观感就是刷新动画。
            if (filteredAlerts.isEmpty() && !monitorState.isLoading) {
                item(key = "emptyAll") {
                    UfiEmptyState(
                        icon = Icons.Default.Inbox,
                        message = "暂无符合条件的事件",
                        hint = "调整筛选条件试试",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else if (pageAlerts.isEmpty()) {
                item(key = "emptyPage") {
                    UfiEmptyState(
                        icon = Icons.Default.Inbox,
                        message = "本页无事件",
                        hint = "切换到第 1 页查看",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else if (aggregateMode) {
                // FIX-3：按 (type, level) 聚合视图。每行仅显示类型 + 累计次数 + 最近时间 + 等级 chip。
                // 点击 → detailTarget = (type, level) → AlertTypeDetailDialog 弹出具体事件 + 内置分页。
                items(aggPageSlice, key = { "${it.type}|${it.level}" }) { row ->
                    AggregatedAlertRowView(
                        row = row,
                        onClick = { detailTarget = row.type to row.level },
                        modifier = Modifier
                            .padding(horizontal = Spacing.CardHorizontalMargin)
                            .padding(vertical = 4.dp)
                    )
                }
            } else {
                val start = currentPage * pageSize
                val slice = if (start >= filteredAlerts.size) emptyList()
                    else filteredAlerts.subList(start, kotlin.math.min(start + pageSize, filteredAlerts.size))
                items(slice, key = { it.id }) { alert ->
                    MonitorEventCard(
                        alert = alert,
                        onAckOne = { id -> viewModel.dashboard.ackAlert(id) },
                        onDelete = { id -> pendingDeleteAlertId = id },
                        modifier = Modifier.padding(
                            horizontal = Spacing.CardHorizontalMargin,
                            vertical = 4.dp
                        )
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = paginationBarVisible,
            // 2026-09-04（P2b）：入场原为裸 `tween(320)` → UfiMotion.Duration.Sweeping（同为 320，
            // 零观感变化）。与上方聚合模式那份分页条保持同一档。
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(animationSpec = tween(UfiMotion.Duration.Sweeping)),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(animationSpec = tween(UfiMotion.Duration.Base)),
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-40).dp)
        ) {
            UfiPagination(
                currentPage = currentPage,
                pageCount = effectivePageCount,
                onPrev = { if (currentPage > 0) currentPage-- },
                onNext = { if (currentPage < effectivePageCount - 1) currentPage++ },
                modifier = Modifier
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .padding(horizontal = Spacing.CardHorizontalMargin),
                variant = UfiPaginationVariant.Floating,
                onJumpClick = { showJumpPageDialog = true }
            )
        }

        PageSizeSettingsDialog(
            visible = showPageSizeDialog,
            current = pageSize,
            onConfirm = { newSize ->
                if (newSize != pageSize) pageSize = newSize
                showPageSizeDialog = false
            },
            onDismiss = { showPageSizeDialog = false }
        )
        JumpPageDialog(
            visible = showJumpPageDialog,
            pageCount = effectivePageCount,
            onConfirm = { target ->
                val clamped = target.coerceIn(1, pageCount)
                currentPage = clamped - 1
                showJumpPageDialog = false
            },
            onDismiss = { showJumpPageDialog = false }
        )

        MonitorSortDialog(
            visible = showSortDialog,
            current = sortMode,
            onDismiss = { showSortDialog = false },
            onSelect = { sortMode = it }
        )
        EventFilterDialog(
            visible = showFilterDialog,
            levelFilter = eventLevelFilter,
            typeFilter = eventTypeFilter,
            readFilter = eventReadFilter,
            dateKey = eventDateKey,
            customRange = eventCustomRange,
            levelCounts = levelCounts,
            minDateMs = monitorState.startupTimeMs,
            onConfirm = { l, t, r, dk, cr ->
                eventLevelFilter = l
                eventTypeFilter = t
                eventReadFilter = r
                eventDateKey = dk
                eventCustomRange = cr
                showFilterDialog = false
            },
            onDismiss = { showFilterDialog = false }
        )

        val pendingDeleteAlert = pendingDeleteAlertId?.let { id ->
            monitorState.alerts.firstOrNull { it.id == id }
        }
        UfiCustomDialog(
            visible = pendingDeleteAlertId != null,
            onDismiss = { pendingDeleteAlertId = null },
            title = "删除事件",
            confirmButton = {
                UfiButton(text = "删除", onClick = {
                    pendingDeleteAlertId?.let { id -> viewModel.dashboard.deleteAlert(id) }
                    pendingDeleteAlertId = null
                })
            },
            dismissButton = {
                UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = { pendingDeleteAlertId = null })
            }
        ) {
            Text(
                text = "确定删除这条事件？删除后不可恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPrimary
            )
            pendingDeleteAlert?.let { a ->
                Spacer(Modifier.height(Spacing.Small))
                Text(
                    text = a.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })

        // FIX-4：聚合行点开后的具体事件明细弹窗。detailTarget 非空 = 打开中。
        // 弹窗复用 monitorState.alerts 作为数据源（前端已加载，无额外 HTTP），
        // 内置 IN_DIALOG_PAGE_SIZE_DEFAULT=20/页分页，单条 ack 实时反映回主列表。
        AlertTypeDetailDialog(
            visible = detailTarget != null,
            type = detailTarget?.first,
            level = detailTarget?.second,
            allRows = monitorState.alerts,
            pageSize = pageSize,
            onDismiss = { detailTarget = null },
            onAckOne = { id -> viewModel.dashboard.ackAlert(id) },
            onDeleteOne = { id -> pendingDeleteAlertId = id }
        )
    }
}

// ==================== 事件中心辅助 ====================

private fun eventSeverityRank(level: String): Int = when (level) {
    "critical" -> 2
    "warning" -> 1
    else -> 0
}

private fun eventSortModeLabel(mode: EventSortMode): String = when (mode) {
    EventSortMode.TimeDesc -> "时间 · 最新在前"
    EventSortMode.TimeAsc -> "时间 · 最早在前"
    EventSortMode.SeverityDesc -> "严重度 · 从高到低"
    EventSortMode.SeverityAsc -> "严重度 · 从低到高"
}

@Composable
private fun EventFilterDialog(
    visible: Boolean,
    levelFilter: String,
    typeFilter: String?,
    readFilter: String,
    dateKey: String,
    customRange: MonitorTimeRange.Custom?,
    levelCounts: Map<String, Int>,
    minDateMs: Long?,
    onConfirm: (level: String, type: String?, read: String, dateKey: String, customRange: MonitorTimeRange.Custom?) -> Unit,
    onDismiss: () -> Unit
) {
    var draftLevel by remember(visible) { mutableStateOf(levelFilter) }
    var draftType by remember(visible) { mutableStateOf(typeFilter) }
    var draftRead by remember(visible) { mutableStateOf(readFilter) }
    var draftDateKey by remember(visible) { mutableStateOf(dateKey) }
    var draftCustomRange by remember(visible) { mutableStateOf(customRange) }
    var showCustomPicker by remember { mutableStateOf(false) }
    val palette = LocalResolvedPalette.current
    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "筛选事件",
        confirmButton = {
            UfiButton(text = "确定", onClick = { onConfirm(draftLevel, draftType, draftRead, draftDateKey, draftCustomRange) })
        },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss)
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("日期", style = MaterialTheme.typography.labelMedium, color = palette.textSecondary)
            UfiSingleChipSelector(
                options = listOf(
                    "all" to "全部",
                    "today" to "今天",
                    "yesterday" to "昨天",
                    "7d" to "近7天"
                ),
                selectedValue = draftDateKey,
                onSelect = { draftDateKey = it },
                wrapContent = true
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (draftDateKey == "custom" && draftCustomRange != null)
                        "自定义：${alertRangeLabel(draftCustomRange!!)}"
                    else "自定义起止日期（≤ 30 天）",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedButton(
                    onClick = { showCustomPicker = true },
                    shape = UfiCardDefaults.buttonShape,
                    border = BorderStroke(1.dp, if (draftDateKey == "custom") palette.accent else palette.divider),
                    colors = if (draftDateKey == "custom") {
                        ButtonDefaults.outlinedButtonColors(containerColor = palette.accent, contentColor = palette.onAccent)
                    } else {
                        ButtonDefaults.outlinedButtonColors(contentColor = palette.accent)
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("自定义", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text("严重度", style = MaterialTheme.typography.labelMedium, color = palette.textSecondary)
            MonitorLevelFilter(
                selectedValue = draftLevel,
                onSelect = { draftLevel = it },
                counts = levelCounts
            )
            Text("类型", style = MaterialTheme.typography.labelMedium, color = palette.textSecondary)
            MonitorTypeFilter(
                selectedType = draftType,
                types = listOf("traffic", "temperature", "battery", "signal", "connectivity")
                    .map { com.ufi_axis.ui.components.alertTypeLabel(it) },
                onSelect = { t -> draftType = t }
            )
            Text("已读状态", style = MaterialTheme.typography.labelMedium, color = palette.textSecondary)
            MonitorReadFilter(
                selected = draftRead,
                onSelect = { draftRead = it }
            )
        }
    }

    UfiDateRangePickerDialog(
        visible = showCustomPicker,
        onDismiss = { showCustomPicker = false },
        title = "自定义日期范围",
        quickRanges = remember {
            listOf(
                UfiQuickRange("今天", subtitle = "00:00 — 现在", start = { localDayStartMs() }, end = { System.currentTimeMillis() }),
                UfiQuickRange("昨天", subtitle = "全天", start = { localDayStartMs(-1) }, end = { localDayEndMs(-1) }),
                UfiQuickRange("近 7 天", subtitle = "最近 7 天", start = { System.currentTimeMillis() - 7L * 86_400_000 }, end = { System.currentTimeMillis() }),
                UfiQuickRange("近 30 天", subtitle = "最近 30 天", start = { System.currentTimeMillis() - 30L * 86_400_000 }, end = { System.currentTimeMillis() })
            )
        },
        minDateMs = minDateMs,
        maxDateMs = System.currentTimeMillis(),
        onConfirm = { result ->
            val (s, e) = when (result) {
                is UfiDateRangeResult.Picked ->
                    utcMidnightToLocalDayStart(result.startMs) to utcMidnightToLocalDayEnd(result.endMs)
                is UfiDateRangeResult.QuickRange ->
                    result.startMs to result.endMs
            }
            if (s < e) {
                draftCustomRange = MonitorTimeRange.clampCustom(s, e)
                draftDateKey = "custom"
            }
            showCustomPicker = false
        }
    )
}

@Composable
private fun EventUnreadChip(
    unreadCount: Int,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    // isPressed 现在只喂配色（按下加深底色 / 提亮文字）；缩放交给 ufiPressScale。
    // 2026-09-04（P2f 配色补齐）：不再用 collectIsPressedAsState —— 它短按只 true 一两帧，
    // 下面 200ms 的 animateColorAsState 只走出几个百分比，底色变化看不见。
    // rememberUfiPressed 把按下态保底 Duration.Micro（120ms），与按压缩放同起同落。
    val isPressed by rememberUfiPressed(interactionSource)
    val bgColor by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            selected -> palette.accent
            isPressed -> palette.cardBorder.copy(alpha = 0.25f)
            else -> palette.cardBg
        },
        animationSpec = androidx.compose.animation.core.tween(UfiMotion.Duration.Base),
        label = "unreadChipBg"
    )
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) palette.accent else palette.cardBorder.copy(alpha = 0.6f),
        animationSpec = androidx.compose.animation.core.tween(UfiMotion.Duration.Base),
        label = "unreadChipBorder"
    )
    val textColor = if (selected) palette.onAccent else if (isPressed) palette.textPrimary else palette.textSecondary
    Box(
        modifier = Modifier
            .wrapContentWidth()
            .height(32.dp)
            // 2026-09-04（P2d）：0.94 → UfiMotion.PressScale.Chip（0.97）。本控件是「未读」筛选胶囊，
            // 与 CategoryChip / EventTypeChip / MonitorFilterChip 同类，统一到 Chip 档。
            // 2026-09-04（P2f 短按看不见）：原为 animateFloatAsState + graphicsLayer。抬手瞬间目标翻回
            // 1f，0.03 的落差配 tween(120) 一帧只走完约 5%；事件列表在可滚动页面里，Press 常与
            // Release 同帧送达，可用时长为 0 —— 所以只有长按看得见。现改由 ufiPressScale 事件驱动
            // 编排 + 最短保持 Duration.Micro（120ms），档位与 spec 原样不变。
            .ufiPressScale(
                interactionSource = interactionSource,
                pressedScale = UfiMotion.PressScale.Chip,
                spec = tween(UfiMotion.Duration.Micro)
            )
            .clip(UfiCardDefaults.subtleShape)
            .background(bgColor)
            .border(1.dp, borderColor, UfiCardDefaults.subtleShape)
            .padding(horizontal = 10.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onSelect() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(if (selected) Color.White else palette.warning, CircleShape)
            )
            Spacer(Modifier.width(5.dp))
            Text("未读", style = UfiTextStyles.label.copy(fontWeight = UfiWeight.Emphasis), color = textColor)
            Spacer(Modifier.width(5.dp))
            Text(
                unreadCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) palette.onAccent.copy(alpha = 0.85f) else palette.warning
            )
        }
    }
}

// ==================== 子组件 ====================

@Composable
private fun MonitorSectionCard(
    contentPadding: PaddingValues = PaddingValues(Spacing.CardPadding),
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalResolvedPalette.current
    val cardShape = UfiCardDefaults.shape

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.CardHorizontalMargin,
                end = Spacing.CardHorizontalMargin,
                bottom = Spacing.CardBottomMargin
            )
            .ufiCardShadow(elevation = 3.dp, shape = cardShape)
            .border(1.dp, palette.divider, cardShape),
        shape = cardShape,
        color = palette.cardBg
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(contentPadding),
            content = content
        )
    }
}

@Composable
private fun FixedMonitorChart(
    title: String,
    points: List<DownsampledPoint>,
    unit: String,
    lineColor: Color,
    isLoading: Boolean,
    valueFormatter: ((Double) -> String)? = null,
    onClick: (() -> Unit)? = null,
    extraSeries: List<MonitorSeries> = emptyList(),
    yAxisLabelFormatter: ((Double) -> String)? = null,
    showLegend: Boolean = true,
    symmetricHorizontal: Boolean = false,
    timeRange: String = "week",
    // 2026-08-24：图表 series 名（用于图例 chip / tooltip），与卡片标题解耦——
    //   当卡片有合并标题（如 "CPU 使用率 / 内存占用"）时，series 名仍应是 primary.label
    //   ("CPU 使用率")，否则 tooltip 会把"两个 series 名合并"误显示成"CPU 使用率 / 内存占用 4%"。
    seriesLabel: String? = null,
    chartHeight: androidx.compose.ui.unit.Dp = 130.dp,
    // ── T40-5b 设置接入：原来这两个形参在 UfiMonitorChart 里实现完整但没有任何调用点传值，
    //    导致「Y 轴固定」「填充透明度」两个开关是纯装饰。 ──
    fixedYRange: ClosedFloatingPointRange<Double>? = null,
    fillAlpha: Float = 1f,
    // 2026-09-03：服务端实际生效的桶宽（毫秒，0=未知），透传给图表做「采集空洞断档」判定的下限参考。
    bucketMs: Long = 0L,
    // 2026-09-04：同屏各图共用的 X 轴时间域（null = 图表自己按点集取 min/max，旧行为）。
    xDomainStartMs: Long? = null,
    xDomainEndMs: Long? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center
    ) {
        if (points.isNotEmpty()) {
            // remember 住转换结果：这行原来写在函数体里，每次重组都要再分配一遍 ≤720 个
            // UfiDownsampledPoint（4 张图表卡 × 每次 state 发射）。
            val uiPoints = remember(points) { toUfiPoints(points) }
            UfiMonitorChart(
                points = uiPoints,
                label = seriesLabel ?: title,
                unit = unit,
                lineColor = lineColor,
                valueFormatter = valueFormatter,
                extraSeries = extraSeries,
                yAxisLabelFormatter = yAxisLabelFormatter,
                showLegend = showLegend,
                symmetricHorizontal = symmetricHorizontal,
                timeRange = timeRange,
                fixedYRange = fixedYRange,
                fillAlpha = fillAlpha,
                bucketMs = bucketMs,
                xDomainStartMs = xDomainStartMs,
                xDomainEndMs = xDomainEndMs,
                // 同屏 8 张图共用同一个 Y 轴列宽下限，X 轴才会对齐（见该常量的 KDoc）。
                // 各图的轴标签字符数都已压到 ≤5，所以全部撞在这个下限上。
                yAxisMinWidth = Spacing.ChartYAxisAlignedWidth,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 2026-09-08：加载态原来画 UfiLoadingIndicator（1s 一圈的呼吸弧）。
                // 它挂在 monitorState.isLoading 上，每轮轮询都会转一遍 —— 那就是用户看到的"刷新动画"。
                // 只留静态文案：图表是否有数据一眼可见，转圈提供不了额外信息。
                Text(
                    if (isLoading) "数据加载中…" else "暂无数据",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalResolvedPalette.current.textSecondary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (MonitorTimeRange.Custom) -> Unit,
    minDateMs: Long? = null,
    maxDateMs: Long? = null
) {
    val quickRanges = remember {
        listOf(
            UfiQuickRange("今天", subtitle = "00:00 — 现在", start = { localDayStartMs() }, end = { System.currentTimeMillis() }),
            UfiQuickRange("昨天", subtitle = "全天", start = { localDayStartMs(-1) }, end = { localDayEndMs(-1) }),
            UfiQuickRange("近 7 天", subtitle = "最近 7 天", start = { System.currentTimeMillis() - 7L * 86_400_000 }, end = { System.currentTimeMillis() }),
            UfiQuickRange("近 30 天", subtitle = "最近 30 天", start = { System.currentTimeMillis() - 30L * 86_400_000 }, end = { System.currentTimeMillis() })
        )
    }
    UfiDateRangePickerDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "自定义时间范围",
        startMs = null,
        endMs = null,
        quickRanges = quickRanges,
        minDateMs = minDateMs,
        maxDateMs = maxDateMs,
        onConfirm = { result ->
            val (s, e) = when (result) {
                is UfiDateRangeResult.Picked ->
                    utcMidnightToLocalDayStart(result.startMs) to utcMidnightToLocalDayEnd(result.endMs)
                is UfiDateRangeResult.QuickRange ->
                    result.startMs to result.endMs
            }
            require(s <= e) { "起始必须 ≤ 结束" }
            onConfirm(MonitorTimeRange.clampCustom(s, e))
        }
    )
}

private fun localDayStartMillis(ms: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = ms
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun localDayStartMs(dayOffset: Int = 0): Long {
    val cal = Calendar.getInstance()
    if (dayOffset != 0) cal.add(Calendar.DAY_OF_MONTH, dayOffset)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun localDayEndMs(dayOffset: Int = 0): Long {
    val cal = Calendar.getInstance()
    if (dayOffset != 0) cal.add(Calendar.DAY_OF_MONTH, dayOffset)
    cal.set(Calendar.HOUR_OF_DAY, 23)
    cal.set(Calendar.MINUTE, 59)
    cal.set(Calendar.SECOND, 59)
    cal.set(Calendar.MILLISECOND, 999)
    return cal.timeInMillis
}

private fun utcMidnightToLocalDayStart(utcMidnightMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    utc.timeInMillis = utcMidnightMillis
    val local = Calendar.getInstance()
    local.clear()
    local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    return local.timeInMillis
}

private fun utcMidnightToLocalDayEnd(utcMidnightMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    utc.timeInMillis = utcMidnightMillis
    val local = Calendar.getInstance()
    local.clear()
    local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    local.set(Calendar.HOUR_OF_DAY, 23)
    local.set(Calendar.MINUTE, 59)
    local.set(Calendar.SECOND, 59)
    local.set(Calendar.MILLISECOND, 999)
    return local.timeInMillis
}

// 2026-08-31：私有 formatBytes(Double) 已删除 —— 速率格式化统一到
// [com.ufi_axis.util.FormatUtils.formatRate]（换算口径不变，额外获得 GB/s 档与 NaN → "—"）。

private fun historyFor(state: MonitorState, type: MonitorMetricType): List<DownsampledPoint> = when (type) {
    MonitorMetricType.CPU -> state.cpuHistory
    MonitorMetricType.MEMORY -> state.memoryHistory
    MonitorMetricType.TRAFFIC_RX -> state.trafficRxHistory
    MonitorMetricType.TRAFFIC_TX -> state.trafficTxHistory
    MonitorMetricType.SIGNAL_RSRP -> state.signalRsrpHistory
    MonitorMetricType.SIGNAL_SINR -> state.signalSinrHistory
    MonitorMetricType.BATTERY -> state.batteryHistory
    MonitorMetricType.TEMPERATURE -> state.temperatureHistory
}

private fun toUfiPoints(points: List<DownsampledPoint>): List<UfiDownsampledPoint> =
    points.map { UfiDownsampledPoint(it.t, it.min, it.max, it.avg) }

private fun pctAxisLabel(v: Double): String = "${v.toInt()}%"

/**
 * 速率图的 **Y 轴**刻度文案。
 *
 * 2026-09-09 重写。原来是 `"%.1f MB/s"` / `"%.1f KB/s"` / `"%.1f B/s"`，最长 `389.0 KB/s` 共 10 字符 ——
 * 而 Y 轴列宽是按最宽刻度实测算的，绘图区左右外距又都由它推导（见 `UfiMonitorChart` 的
 * `yAxisMinWidth` 注释）。于是同屏 8 张图里只有这两张速率图的 X 轴又短又偏左，
 * 而且峰值跨过 1MB/s 时（10 字符 ↔ 9 字符）宽度还会自己抖一下。
 *
 * 现在做两件事把字符数**钉在 ≤5**：
 * 1. 单位压成单字母、去掉 `/s` —— 单位在卡片标题「上/下行速率」和 tooltip 里都还在
 *    （tooltip 走的是 [yFormatterFor] 的 `FormatUtils.formatRate`，仍是完整 "12.3 MB/s"），
 *    轴上重复一遍只是把绘图区挤窄；
 * 2. 尾数 ≥100 用整数、<100 留一位小数。尾数值域是 [0, 1024)，所以最长形态只有
 *    `1023K`（4+1）与 `99.9M`（4+1）两种，上界 5 字符。
 *
 * 改这里之前请先读 `Spacing.ChartYAxisAlignedWidth`：对齐是靠"所有图的标签都 ≤5 字符、
 * 于是全部撞在同一个宽度下限上"实现的，字符数一旦超上界，这张图会重新错开。
 */
private fun trafficYAxisLabel(v: Double): String {
    val (mantissa, suffix) = when {
        v >= 1_073_741_824 -> v / 1_073_741_824 to "G"
        v >= 1_048_576 -> v / 1_048_576 to "M"
        v >= 1024 -> v / 1024 to "K"
        else -> v to ""
    }
    return if (mantissa >= 100) {
        "%.0f%s".format(mantissa, suffix)
    } else {
        "%.1f%s".format(mantissa, suffix)
    }
}

/**
 * 「Y 轴固定」时该指标的固定量程；无公认定义域的指标返回 null（继续自适应）。
 *
 * 只有百分比类（CPU / 内存 / 电池）与摄氏度有公认区间。流量是 B/s、信号是 dBm/dB，
 * 给它们编一个固定区间会把曲线压平，比不固定更糟。
 */
private fun fixedYRangeFor(type: MonitorMetricType): ClosedFloatingPointRange<Double>? = when (type) {
    MonitorMetricType.CPU, MonitorMetricType.MEMORY, MonitorMetricType.BATTERY -> 0.0..100.0
    MonitorMetricType.TEMPERATURE -> 0.0..100.0
    else -> null
}

private fun yFormatterFor(type: MonitorMetricType): (Double) -> String = when (type) {
    MonitorMetricType.CPU, MonitorMetricType.MEMORY -> ::pctAxisLabel
    MonitorMetricType.TRAFFIC_RX, MonitorMetricType.TRAFFIC_TX -> { v -> FormatUtils.formatRate(v) }
    MonitorMetricType.SIGNAL_RSRP -> { v -> "%.0f dBm".format(v) }
    MonitorMetricType.SIGNAL_SINR -> { v -> "%.0f dB".format(v) }
    MonitorMetricType.BATTERY -> { v -> "%.0f%%".format(v) }
    MonitorMetricType.TEMPERATURE -> { v -> "%.0f°C".format(v) }
}

private fun yAxisFormatterFor(type: MonitorMetricType): (Double) -> String = when (type) {
    MonitorMetricType.CPU, MonitorMetricType.MEMORY -> ::pctAxisLabel
    MonitorMetricType.TRAFFIC_RX, MonitorMetricType.TRAFFIC_TX -> { v -> trafficYAxisLabel(v) }
    MonitorMetricType.SIGNAL_RSRP -> { v -> "%.0f".format(v) }
    MonitorMetricType.SIGNAL_SINR -> { v -> "%.0f".format(v) }
    MonitorMetricType.BATTERY -> { v -> "%.0f".format(v) }
    MonitorMetricType.TEMPERATURE -> { v -> "%.0f".format(v) }
}

@Composable
private fun ChartSectionTitle(
    title: String,
    trailing: @Composable () -> Unit = {}
) {
    val palette = LocalResolvedPalette.current
    // 2026-08-24：去掉之前的 6/6/8/6 内边距，紧凑到 2dp 上 / 4dp 下，左右 0（卡片 padding 已留）。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(palette.accent, UfiCardDefaults.pillShape)
            )
            Text(
                text = title,
                style = UfiTextStyles.panelTitleStrong,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            trailing()
        }
    }
}

private fun monitorRatLabel(raw: String?): String? {
    val r = raw?.trim().orEmpty()
    if (r.isBlank()) return null
    return when (r.lowercase()) {
        "nr", "5g", "sa", "nsa" -> "5G"
        "lte", "4g" -> "4G"
        "wcdma", "umts", "hspa", "hsdpa" -> "3G"
        "gprs", "edge", "2g" -> "2G"
        else -> r.uppercase()
    }
}

/**
 * 浮动分页条的「按滚动方向自动隐藏」判据 —— [LazyListState]（[LazyColumn]）版（2026-09-05 P3）。
 *
 * 2026-09-08：原来还有一支 [androidx.compose.foundation.ScrollState] 版（事件中心当年是
 * Column + verticalScroll）。事件中心改成 LazyColumn 之后两个 Tab 都走这一支，那支已删除。
 *
 * 总览 Tab 的内容是 LazyColumn，拿不到"从顶部滚了多少像素"这个连续量；这里用
 * `首个可见 item 下标 × 步长 + 该 item 内偏移` 合成一个**单调代理值**：
 * 下滚时下标或偏移必增、上滚必减，所以 `delta` 的**符号**与真实滚动方向一致
 * —— 而下面的判据只用符号（`> 4` / `< -4` 只是抖动死区），不依赖幅度是真实像素。
 * 步长取一个远大于任何 item 高度的值，保证"下标 +1"一定压过"偏移回到 0"。
 */
@Composable
private fun rememberSmartPaginationBarVisible(
    listState: LazyListState,
    currentPage: Int,
    pageCount: Int
): Boolean = rememberSmartPaginationBarVisible(
    currentPage = currentPage,
    pageCount = pageCount,
    scrollValue = {
        listState.firstVisibleItemIndex * LAZY_SCROLL_INDEX_STRIDE + listState.firstVisibleItemScrollOffset
    }
)

/** [rememberSmartPaginationBarVisible] 的 LazyColumn 代理值步长，见该函数 KDoc。 */
private const val LAZY_SCROLL_INDEX_STRIDE = 1_000_000

/**
 * 两个形态共用的实现。
 *
 * @param scrollValue **必须是 lambda**：`delay` 之后还要再读一次当前值来判断"这段时间里
 *   用户是不是没再滚"。传快照值进来的话那次比较恒为真，分页条会无条件隐藏。
 */
@Composable
private fun rememberSmartPaginationBarVisible(
    currentPage: Int,
    pageCount: Int,
    scrollValue: () -> Int
): Boolean {
    var visible by remember { mutableStateOf(pageCount > 1) }
    var lastScrollValue by remember { mutableIntStateOf(scrollValue()) }
    var lastFlipTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(currentPage) {
        if (pageCount > 1) {
            visible = true
            lastFlipTime = System.currentTimeMillis()
        }
    }

    val observedScroll = scrollValue()
    LaunchedEffect(observedScroll, pageCount) {
        if (pageCount <= 1) {
            visible = false
            return@LaunchedEffect
        }
        val current = observedScroll
        val delta = current - lastScrollValue
        lastScrollValue = current

        if (System.currentTimeMillis() - lastFlipTime < 1600) {
            visible = true
            return@LaunchedEffect
        }

        when {
            delta > 4 -> visible = true
            delta < -4 -> visible = false
        }

        if (visible) {
            delay(1800)
            if (scrollValue() == current) {
                visible = false
            }
        }
    }

    return visible
}

/**
 * 首屏加载提示：**静态**一行文案，替代原来的 `UfiLinearLoading`（M3 indeterminate 横条）。
 *
 * 判据一律是「isLoading **且**对应数据为空」，即只在首屏出现；轮询刷新走 `silent = true`，
 * 不会翻 isLoading，所以已有数据时这行永远不出现（见本文件顶部轮询注释）。
 */
@Composable
private fun MonitorLoadingHint(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Text(
        text = "加载中…",
        style = MaterialTheme.typography.labelSmall,
        color = LocalResolvedPalette.current.textSecondary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.CardHorizontalMargin, vertical = Spacing.Medium)
    )
}

@Composable
private fun EventsSettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        // 2026-09-04（P2d）：0.96 → UfiMotion.PressScale.Button（值不变），本控件是 OutlinedButton。
        // 2026-09-04（P2f 短按看不见）：原为 animateFloatAsState + graphicsLayer，抬手瞬间目标翻回 1f，
        // 0.04 的落差配 spring(1.0/600) 一帧只走出约 6%（40dp 上零点几个像素）；事件中心工具栏在
        // 可滚动页面里，Press 常与 Release 同帧送达，可用时长为 0。现改由 ufiPressScale 编排。
        modifier = modifier
            .size(40.dp)
            .ufiPressScale(
                interactionSource = interactionSource,
                pressedScale = UfiMotion.PressScale.Button,
                spec = UfiMotion.buttonPress()
            ),
        shape = UfiCardDefaults.buttonShape,
        border = BorderStroke(1.dp, palette.accent),
        contentPadding = PaddingValues(0.dp),
        interactionSource = interactionSource
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = "事件中心设置",
            tint = palette.accent,
            modifier = Modifier.size(20.dp)
        )
    }
}

private val EventsPageSizeOptions = listOf(10, 20, 30, 50, 100)
private const val EVENTS_PREFS = "ufi_axis_settings"
private const val KEY_EVENTS_PAGE_SIZE = "events_page_size"

private fun loadEventsPageSize(prefs: android.content.SharedPreferences): Int {
    val saved = prefs.getInt(KEY_EVENTS_PAGE_SIZE, 20)
    return if (saved in EventsPageSizeOptions) saved else 20
}

@Composable
private fun PageSizeSettingsDialog(
    visible: Boolean,
    current: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "每页显示数量",
        confirmButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "完成", onClick = onDismiss)
        }
    ) {
        val palette = LocalResolvedPalette.current
        Text(
            text = "选择列表每页显示的事件数量，重启应用后仍保留。",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary
        )
        Spacer(Modifier.height(Spacing.Medium))
        UfiOptionGrid(
            options = EventsPageSizeOptions.map { size ->
                UfiOptionItem(
                    value = size,
                    label = "$size / 页",
                    trailing = if (size == current) {
                        { Icon(Icons.Filled.CheckCircle, null) }
                    } else null
                )
            },
            selectedValue = current,
            onSelect = { onConfirm(it) },
            columns = 3
        )
    }
}

@Composable
private fun JumpPageDialog(
    visible: Boolean,
    pageCount: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember(visible) { mutableStateOf("") }
    val palette = LocalResolvedPalette.current
    val toastContext = LocalContext.current
    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "跳转到指定页",
        confirmButton = {
            UfiButton(text = "跳转", onClick = {
                val parsed = input.trim().toIntOrNull()
                if (parsed != null && parsed in 1..pageCount) onConfirm(parsed)
                else android.widget.Toast.makeText(
                    toastContext,
                    "请输入 1 到 $pageCount 的页码",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            })
        },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss)
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
            Text(
                text = "当前共 $pageCount 页。输入目标页码直接跳转：",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary
            )
            // 2026-08-31：裸 OutlinedTextField → 公共 UfiDigitField（数字过滤 + 4 位截断交给组件）
            UfiDigitField(
                value = input,
                onValueChange = { input = it },
                label = "页码 (1 - $pageCount)",
                maxLength = 4
            )
        }
    }
}
