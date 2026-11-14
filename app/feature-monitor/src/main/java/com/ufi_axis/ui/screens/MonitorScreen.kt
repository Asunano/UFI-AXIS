package com.ufi_axis.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.ufi_axis.ui.components.alertTypeLabel
import com.ufi_axis.ui.components.AlertTypeDetailDialog
import com.ufi_axis.ui.components.EventSummaryCard
import com.ufi_axis.ui.components.MonitorEventStatCards
import com.ufi_axis.ui.components.MonitorEventCard


import com.ufi_axis.ui.components.MonitorLevelFilter
import com.ufi_axis.ui.components.MonitorOverviewTab
import com.ufi_axis.ui.components.alertRangeLabel
import com.ufi_axis.ui.components.MonitorReadFilter
import com.ufi_axis.ui.components.MonitorSortButton
import com.ufi_axis.ui.components.MonitorSortDialog
import com.ufi_axis.ui.components.MonitorEventsEntry
import com.ufi_axis.ui.components.MonitorTopTab
import com.ufi_axis.ui.components.MonitorTypeFilter
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
// `UfiMotion` 位于 ui.theme，而 components.common 仍保留同名的 @Deprecated 转发壳。
// 本文件同时以 `*` 导入这两个包，两个同名 object 优先级相同，会触发
// 「Overload resolution ambiguity」。显式单点导入优先级高于星号导入，既消除歧义
// 又直接指向新位置（本文件因此不产生废弃警告）。
import com.ufi_axis.ui.theme.UfiMotion

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.MonitorState
import com.ufi_axis.viewmodel.state.DashboardState
import com.ufi_axis.viewmodel.module.UiEvent
import com.ufi_axis_core.contract.Capability
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.ufi_axis.ui.navigation.ufiNavigateOnce

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
        // 个性化 7 项 = AppSettings.monitorPreferences。先回读再取数：
        // 一是其它端（设置页 / web）修改后此处不能仍显示本地旧值，
        // 二是首屏时间范围需使用回读后的 defaultHours，否则会按旧值多拉一次。
        viewModel.dashboard.refreshMonitorSettingsFromBackend()
        // 懒加载：首屏**只确定窗口、不拉数据**。
        // 首屏可见内容仅为总览的 hero 与 6 格；图表 Tab 的 4 个区块及折叠以下内容
        // 无需在首屏拉取。规则：
        //   - 窗口在此确定（总览的 6 格与 hero 固定只统计「今天 00:00 起」）；
        //   - 需要数据的区块自行拉取 —— 区块位于 LazyColumn 中，组合即代表可见（见各区块的
        //     LaunchedEffect 与 DashboardModule.loadMonitorTypes 的说明）。
        viewModel.dashboard.selectMonitorRange(MonitorTimeRange.today())
        viewModel.dashboard.loadStartupTime()
    }

    // 总览「今日流量」格读取 dashboardState.trafficSummary，该字段只由 refreshDashboard() 写入：
    // 全局仅在 App 启动（DashboardModule.init）与仪表盘页轮询时调用。直接进入监控页（深链/底栏跳转）
    // 且启动时的拉取失败时，该卡会停留在「暂无数据」，停留期间也不会随日期更新。
    //
    // 性能：该复合请求会带来 2 次 dashboardState 发射，不能与首屏组合、页面转场动画抢占同一帧。
    // 因此仅在「确实没有数据」时立即拉取，其余情况在首屏稳定后（1.5s）补一次。
    // 使用 refreshDashboardIfStale()：本页与仪表盘 Tab 读取同一份 dashboardState，
    // 而 refreshDashboard() 会无条件取消在飞刷新。两个 Tab 均保活且都会在前台化时
    // 进入此类效应，使用取消版会互相取消对方的聚合请求。IfStale 版在已有请求在飞时直接返回，
    // 且数据处于新鲜窗口内时不发送任何请求。
    LaunchedEffect(pageForeground) {
        if (!pageForeground) return@LaunchedEffect
        if (dashboardState.trafficSummary == null) {
            viewModel.dashboard.refreshDashboardIfStale()
        } else {
            delay(1_500)
            viewModel.dashboard.refreshDashboardIfStale()
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
                // 懒加载的刷新侧：只刷「已经露过面」的指标（refreshVisibleMonitorTypes），
                //   未展示过的图表不应在后台持续刷新。告警使用 silent —— 否则每轮都会将
                //   isLoading 置为 true，导致顶部横条闪烁。
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

    // 切回「总览」Tab 时将数据窗口恢复到今天：总览的 6 格与 hero 固定只统计「今天 00:00 起」，
    // 而两个 Tab 共用同一份 history。若在图表页选择了历史区间（如 8 月 1 日）后切回，
    // 序列中全为该日的点，导致 6 格全部「暂无数据」而 hero 显示「运行正常」。
    // 仅需修改窗口：窗口变化后，总览中可见区块（hero / 6 格）的 LaunchedEffect(range) 会重新拉取。
    LaunchedEffect(overviewTab, hasLoadedOnce) {
        if (!hasLoadedOnce || overviewTab != MonitorOverviewTab.OVERVIEW) return@LaunchedEffect
        val range = monitorState.selectedRange
        val isTodayDefault = range is MonitorTimeRange.Custom && range.startMs == MonitorTimeRange.todayStartMs()
        if (!isTodayDefault) {
            viewModel.dashboard.selectMonitorRange(MonitorTimeRange.today())
        }
    }

    // 图表 Tab 不修改数据窗口：默认即「今天」（与首屏、总览一致）。
    // 若首次切到图表 Tab 时套用偏好 defaultHours（默认 24 小时 → Preset(24)），
    // 「默认范围」将与总览不一致，X 轴刻度也会附带日期。defaultHours 仍供 web 端使用。


    val palette = LocalResolvedPalette.current
    // 胶囊底部遮挡预留**不在组合期读取**。
    // `LocalCapsuleBottomInset` 为 `() -> Dp`（理由见其 KDoc：它是 staticCompositionLocalOf，
    // 值变化会使整棵 NavHost 子树无条件重组，而胶囊在 pop 的第 2~3 帧才发布真值）。
    // 该值由两个 LazyColumn 末尾的 `Spacer(Modifier.ufiCapsuleBottomInset(Spacing.Medium))`
    // 在 **layout 阶段** 读取，inset 变化只重排该占位节点。

    UfiScreenScaffold(
        title = "监控中心",
        navController = navController,
        showBack = showBack,
        // 首页 Tab：标题栏显示「正在播放」。
        //
        // 此处不放「监控设置」齿轮：它与「正在播放」争夺同一块右侧空间，
        // 且属于设置类入口，已移至设置页的入口列表（SettingsScreen）。
        showNowPlaying = true
    ) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                /*
                 * 分类 Tab。事件入口已移至下方 chip 行右侧。
                 *
                 * 该行只放**页面级导航**（总览 / 图表）。「事件中心 ›」与 Tab 不属于同一类，
                 * 置于最右侧还会占用 16dp 间距；现在它与「按类型聚合 / 全部明细」同行 ——
                 * 两者都决定「这一屏显示什么」。位置见下方 `overviewFilterChips` item。
                 *
                 * 入口移走后此处**不保留占位**：原 `Box(weight(1f))` 仅用于将入口推到右侧，
                 * 而 Tab 本身 fillMaxWidth（UfiScrollableTabRow 内部实现），左边缘不会偏移。
                 */
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
        // 自写的确认/取消按钮统一经 LocalUfiDialogClose 排时序：弹窗离场的
        // backdrop（逐渐清晰）需在窗口销毁前播放完毕，直接切换状态会使弹窗当帧卸载、动画无法播放。
        // local 必须在 slot 内部读取，在弹窗外读取得到的是「直接执行」的默认实现。
        confirmButton = {
            val close = LocalUfiDialogClose.current
            UfiButton(text = "删除", onClick = {
                close {
                    pendingDeleteAlertId?.let { id -> viewModel.dashboard.deleteAlert(id) }
                    pendingDeleteAlertId = null
                }
            })
        },
        dismissButton = {
            val close = LocalUfiDialogClose.current
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = { close { pendingDeleteAlertId = null } })
        }
    ) {
        // 间距统一到 UfiDialogBody（12dp）
        UfiDialogBody {
            Text(
                text = "确定删除这条事件？删除后不可恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPrimary
            )
            pendingDeleteAlert?.let { a ->
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
}

// ── 监控中心子模块 ──

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
    // hero 卡状态**完全由事件列表驱动**，不存在「今日阈值/峰值」状态源。
    // 阈值迟滞逻辑（进 2 轮 / 退 3 轮）的唯一输入 worstPeakSummary（指标峰值的最差阈值等级）
    // 已无消费者，故一并移除。事件列表本身为边沿触发（设备侧仅在级别跃迁时入库），
    // 不需要额外迟滞。
    val palette = LocalResolvedPalette.current

    // 懒加载：告警只在「总览 Tab 被打开」时拉取 —— 本函数只在该 Tab 下组合，
    // 因此组合本身即「已看到」的信号。直接进入图表 Tab 时不会多拉一次告警列表。
    //
    // 告警引擎开关（core `AlertConfig.enabled` 的镜像）跟着一起拉：关着时设备不检测、不入库，
    // 下面的事件列表恒为空 —— 必须把这个原因说出来，见 [AlertEngineOffCard]。
    val alertConfig by viewModel.alertPrefs.configFlow.collectAsState()
    // 镜像尚未拉取（null）时不显示提示：此时无法确定引擎状态，不能断言「已关闭」。
    val engineOff = alertConfig?.enabled == false
    LaunchedEffect(Unit) {
        viewModel.dashboard.loadAlerts()
        viewModel.tools.loadAlerts()
    }

    // 总览为**竖向时间轴**，只显示今天的事件。
    // 不包含：时间轴/按类型分类切换、聚合/明细 chip、浮动分页条。
    // 保留：按 (type, level) 聚合的行（一行 = 一类一个等级，含 count/unread/lastTimestamp），
    //       点击打开明细弹窗，仅统计今天。
    var detailTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    /** 底部详情 sheet 选中的那条事件（null = 关闭）。 */
    var selectedAlert by remember { mutableStateOf<com.ufi_axis.data.model.AlertRecord?>(null) }

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
    /** 今天的未读条数：头部徽标用，与时间轴同口径（只统计 todayAlerts）。 */
    val todayUnread = remember(todayAlerts) { todayAlerts.count { !it.acknowledged } }

    Box(Modifier.fillMaxSize()) {
        // 列表状态提到外面：下面那条浮动分页条要按滚动方向自动隐藏，
        // 而判据（[rememberSmartPaginationBarVisible]）需要读列表的滚动位置。
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            // 与图表 Tab 对齐的降级提示：采集停用时 6 格必然全为「暂无数据」，
            // 拉取失败时 hero 会显示「运行正常」—— 缺少提示会误导用户。
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
            // 错误不在此处渲染，由 Activity 级全局错误浮层统一展示
            //（MainActivity 读取 viewModel.globalError，MONITOR 来源的重试 = refreshCurrentRange()）。
            // 该约束同时避免错误卡作为 LazyColumn item 被回收后错误消失的问题。

            item(key = "hero") {
                // 首屏不显示顶部加载提示：总览页在数据到达前，**每张图表内部**已各显示一次
                // 「数据加载中…」（FixedMonitorChart）。顶部再显示笼统的「加载中…」属于冗余，
                // 且其占一行高度、数据到达即消失，会造成整列表位移。
                //
                // 懒加载：总览不再绘制任何指标曲线（峰值网格已移除，hero 也只看事件列表），
                // 但以下两条副作用**必须保留**：
                //   ① `loadMonitorTypes` 以 state 中的 range 记账，与启动预加载
                //      （PreloadCoordinator.OVERVIEW_METRIC_KEYS，同一 range 实例）对齐 ——
                //      少一次调用会使记账 key 不匹配，切到图表 Tab 时这几类被整批重拉，预热失效；
                //   ② 切到图表 Tab 时数据已处于热态。
                // 若要彻底移除，必须让图表 Tab 成为唯一登记方，并同步修改预加载。
                val heroTypes = remember(monitorState.settings.enabledTypes) {
                    monitorState.settings.enabledTypes.toList()
                }
                // 可见性成对上报：滚出视口后轮询就不该再刷这些指标。
                DisposableEffect(heroTypes) {
                    viewModel.dashboard.registerVisibleMonitorTypes(heroTypes)
                    onDispose { viewModel.dashboard.unregisterVisibleMonitorTypes(heroTypes) }
                }
                LaunchedEffect(monitorState.selectedRange, heroTypes) {
                    viewModel.dashboard.loadMonitorTypes(
                        types = heroTypes,
                        range = monitorState.selectedRange,
                    )
                }
                // hero 卡（EventSummaryCard）已移除；「事件中心 ›」入口位于下方
                // `overviewFilterChips` 行的右侧（见 MonitorEventsEntry）。
                //
                // 该 item **没有可见内容**，仅保留上述两条副作用，二者不可随卡片一起删除：
                //   ① `loadMonitorTypes` 以 state 中的 range 记账，与启动预加载对齐；
                //      少一次调用会使切到图表 Tab 时这几类被整批重拉，预热失效；
                //   ② 可见性成对上报决定轮询是否刷新这些指标。
                // 代价：item 高度为 0、始终位于视口内，因此「滚出视口即停止刷新」不再生效 ——
                // 总览 Tab 开启期间会持续刷新。图表 Tab 有独立登记，不受影响。
            }

            item { Spacer(Modifier.height(Spacing.Medium)) }

            // 「流量报表」入口位于流量管理页：它是流量的只读产物，与限额设置、流量历史同属一类。
            // 若放在监控中心总览，与只统计「今天 00:00 起」的 hero 卡并列，会要求用户自行区分时间尺度。
            // 入口位于 `TrafficManagementScreen` 的限额设置卡中（紧随「流量历史」）。

            // 头部单行展示：今天 N 条 · 未读 M · 事件中心 ›。
            // 「事件中心 ›」按自身宽度右对齐，窄屏/大字体下仅压缩左侧文字。
            item(key = "todayHeader") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.CardHorizontalMargin, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "今天",
                        style = UfiTextStyles.panelTitleStrong,
                        color = palette.textPrimary
                    )
                    Spacer(Modifier.width(Spacing.Medium))
                    Text(
                        text = "${aggregatedRows.size} 条",
                        style = UfiTextStyles.caption,
                        color = palette.textSecondary
                    )
                    if (todayUnread > 0) {
                        Spacer(Modifier.width(Spacing.Medium))
                        Surface(
                            color = palette.error.copy(alpha = 0.12f),
                            shape = UfiCardDefaults.pillShape
                        ) {
                            Text(
                                text = "未读 $todayUnread",
                                style = UfiTextStyles.captionStrong,
                                color = palette.error,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    MonitorEventsEntry(
                        onClick = { navController.ufiNavigateOnce(Routes.DETAIL_EVENTS) },
                        modifier = Modifier.defaultMinSize(minHeight = Spacing.SegmentHeight)
                    )
                }
            }

            // 以下为总览事件列表的三种状态：引擎关闭 / 空态 / 时间轴。
            if (engineOff && todayAlerts.isEmpty()) {
                // 引擎关闭时事件列表必然为空，而空态文案为「今日暂无异常事件」——
                // 该文案在此场景下不成立：并非没有异常，而是未进行检测。因此**整块替换空态**，
                // 而不是在空态之上叠加提示卡。
                //
                // 存在历史事件时不替换：这些事件产生于引擎关闭之前，列表应正常显示，
                // 此时叠加「未开启」卡会遮挡用户需要查看的内容。
                item(key = "engineOff") {
                    AlertEngineOffCard(
                        onEnable = {
                            alertConfig?.let { viewModel.tools.updateAlertConfig(it.copy(enabled = true)) }
                        },
                        onOpenSettings = { navController.ufiNavigateOnce(Routes.DETAIL_ALERT_SETTINGS) },
                        canEnable = alertConfig != null,
                        modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin, vertical = 4.dp)
                    )
                }
            // 空态判据使用 alertsLoaded（告警曾成功读取），而不是 !isLoading：
            // 后者被图表三个 loader 共用，与告警无关却会提前清除该标志，
            // 导致告警请求仍在进行时就显示「今日无告警」。
            } else if (todayAlerts.isEmpty() && monitorState.alertsLoaded) {
                item(key = "empty") {
                    // 不使用 fillParentMaxSize()：该提示上方还有 hero + 6 格 + chips，占满一屏
                    // 会把「今日暂无异常事件」推到折叠线以下，需要再滚动一屏才能看到。
                    UfiEmptyState(
                        icon = Icons.Default.CheckCircle,
                        message = "今天暂无异常事件",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp)
                    )
                }
            } else {
                // 竖向时间轴 —— 每行 = 时刻 + 轴点 + 事件卡。
                // 数据为按 (type, level) 聚合的行，按最近发生时间倒序；同一类型/等级的多条记录合并为一行。
                items(
                    items = aggregatedRows,
                    key = { "${it.type}-${it.level}" }
                ) { row ->
                    MonitorTimelineRow(
                        row = row,
                        onClick = { detailTarget = row.type to row.level }
                    )
                }

                // 收尾：明确「今天的数据到此为止」。
                item(key = "todayTail") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.CardHorizontalMargin, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = palette.divider
                        )
                        Text(
                            text = "今天 00:00 起 · 共 ${todayAlerts.size} 条",
                            style = UfiTextStyles.caption,
                            color = palette.textSecondary,
                            modifier = Modifier.padding(horizontal = Spacing.Medium)
                        )
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = palette.divider
                        )
                    }
                }
            }

            // 胶囊底部遮挡预留。语义等价于
            // `contentPadding = PaddingValues(bottom = capsuleInset + Spacing.Medium)`
            // （均在内容末尾追加一段可滚动空白、不缩小视口），但高度在 **layout 阶段** 读取，
            // 因此胶囊重挂后发布真值时只重排该节点，不触发整树重组。
            item(key = "capsuleBottomInset") {
                Spacer(Modifier.ufiCapsuleBottomInset(Spacing.Medium))
            }
        }

        // 聚合行点击 → AlertTypeDetailDialog 显示具体事件 + 内置分页。
        // 数据源必须与聚合行同口径（todayAlerts）：使用全量 alerts 会出现「行上显示发生 3 次、
        // 弹窗显示共 43 条并列出昨天事件」的不一致。
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

    // ── 电池图：「本设备是否有电池」的判据 ──
    //
    // 使用设备能力集（`Capability.BATTERY`），不使用 `BatteryInfo.supported`。
    // 两者表达同一事实 —— core 侧均取自 `Capability.BATTERY in plugin.capabilities`
    // （`supported` 只是电池读数的附带转述）。两份判据会逐渐不一致，
    // 而「是否有电池」本质上由能力集回答，因此以能力集为准。
    //
    // 本处**依赖** `DeviceCapabilityState.supports()` 在能力集未拉取时恒返回 true：
    // 「未拉取 / 拉取失败」时电池必须按**有电池**处理 —— 照常绘图，等待能力集返回。
    // 反之（不确定即视为无电池）会使一次网络抖动将正常的电量曲线判为无效数据并置灰。
    val capabilities by viewModel.network.capabilityState.collectAsState()
    val batterySupported = capabilities.supports(Capability.BATTERY)
    // 补充一次拉取：能力集自带「本进程只成功拉取一次」的闸门，重复调用不会产生额外请求。
    // 该调用用于覆盖从底栏 / 深链直接进入监控页、未经过网络页预热
    //（`NetworkModule.loadNetworkAll` 的附带拉取）的场景。
    LaunchedEffect(Unit) { viewModel.network.loadDeviceCapabilities() }

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

        // 此处不放顶部加载提示：下方每张图表自身会显示「数据加载中…」，
        // 顶部再显示一句属于冗余，且会造成一次列表位移。

        // 错误不在此处渲染，由全局错误浮层统一展示（见本文件上方说明）。

        // 一类指标一张图（共 8 张）。不使用 4 张卡各放两条线的布局（CPU+内存、上/下行、
        // RSRP+SINR、电池+温度）：两条线共用一根 Y 轴时，量级差异会使其中一条被压在轴底
        // （电池 0~100 与温度 30~60 同轴、RSRP≈-100 与 SINR≈10 同轴），Y 轴单位也只能迁就其中一条。
        // 拆开后每张图各自缩放、各自用自己的单位格式化。

        // ── 同屏各图共用一条 X 轴时间标尺 ──
        //
        // 问题：上下行速率图的终点与其他图对不齐。
        // 根因不在数据，而在**每张图各取自身点集的 min/max 作为轴边界，再拉满整个绘图宽度**
        // （UfiChart 的 tBounds）。各指标「当前桶是否已有行」并不同步：
        // `traffic_records` 每 tick 无条件写库，signal 全 0 时跳过、battery 30s 一轮，
        // 空桶服务端不返回行 —— 因此速率图通常先产生最新桶。末桶相差一个时，
        // 右边缘代表不同时刻，两张图的 px/ms 比例也不同，横向对读时间失去意义。
        //
        // 方案：时间域在此计算**一次**，传给所有图。采用服务端的桶心网格
        // （core 的 aggregateBetween：`center(bucket) = bucket * bucketMs + bucketMs / 2`），
        // 使标尺与数据点同源；数据较旧的图如实停在中间，不再表现为到达右端。
        // 计算为 O(1)，不扫描点集；在此计算一次也保证 8 张图取得**同一个值**（各图自行计算会
        // 因 queryEndMs 为「当前时刻」而得到不同结果）。
        // bucketMs <= 0（旧 core 不返回 bucket_ms）时取 null，各图自适应，行为与未引入该标尺时一致。
        // 注意：此处处于 LazyListScope（非 @Composable）作用域，**不能使用 remember** ——
        // 纯算术，一次重组的开销可以忽略。
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
                // 「本机型没有电池」时先显示说明卡，再显示图表。
                //
                // 指标选择器中的电池项**保持不变**（仍可勾选）：用户可能仍需查看旧数据，
                // 且移除该项会使「为什么没有电池图」更难排查。
                // 说明卡位于卡片外、图表正上方：`UfiNoticeCard` 自带
                // `Spacing.CardHorizontalMargin` 横向外距，与下方 `MonitorSectionCard`
                // 的外距同源，两块左右边缘对齐；放入卡片内部会多缩进一层。
                Column {
                    if (metric == MonitorMetricType.BATTERY && !batterySupported) {
                        UfiNoticeCard(
                            message = BATTERY_UNSUPPORTED_CHART_NOTE,
                            modifier = Modifier.padding(bottom = Spacing.Small)
                        )
                    }
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
                        batterySupported = batterySupported,
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
        }

        // 胶囊底部遮挡预留（layout 阶段读取，见 Modifier.ufiCapsuleBottomInset）。
        item(key = "capsuleBottomInset") {
            Spacer(Modifier.ufiCapsuleBottomInset(Spacing.Medium))
        }
    }
}


/**
 * 图表空态的**三种**成因。
 *
 * 仅以 `points.isEmpty()` 区分「有数据 / 无数据」会遗漏切区间时的中间态：
 * 已持有旧区间的点，但它们全部落在新窗口之外（`raw=12 clipped=0 isLoading=true`），
 * 此时 UI 显示「暂无数据」，而界面上明明存在历史数据；后台正在拉取新区间，数秒后恢复。
 * 三种成因在 UI 上必须给出不同文案，否则其中两种必然被误报。
 */
private enum class ChartEmptyReason {
    /** 正在取数：显示「加载中」而不是「暂无数据」，后者与事实不符。 */
    LOADING,

    /**
     * 持有数据但均不在当前窗口 —— 切区间/切指标的过渡态，不属于空态。
     * 判据 [rawCount][MonitorChartSection] > 0 且裁剪后为 0。
     */
    SWITCHING,

    /** 确实没有数据（服务端返回 0 行，或指标未开启）。 */
    NO_DATA,
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
    // 同屏各图共用的 X 轴时间域（由 MonitorChartsTabContent 统一计算一次后下发，
    // null = 图表按自身点集自适应）。见调用点说明。
    xDomainStartMs: Long? = null,
    xDomainEndMs: Long? = null,
    // 本设备是否有电池。判据与文案均在调用点，此处只消费结论。
    // 默认 true 与未引入该判据时的画法一致；它只影响 [MonitorMetricType.BATTERY] 这一张图。
    batterySupported: Boolean = true
) {
    val shownTypes = remember(types, enabledTypes) { types.filter { enabledTypes.contains(it.apiKey) } }
    if (shownTypes.isEmpty()) return

    val apiKeys = remember(shownTypes) { shownTypes.map { it.apiKey } }

    // 懒加载：本区块位于 LazyColumn 的 item 中，执行到此即说明其已进入
    // 视口附近（LazyColumn 只组合可见范围的 item）—— 组合即可见性信号，无需额外检测。
    // 因此每个区块只拉取**自身需要的**指标：首屏从「8 类全拉」降为「可见的 2~4 类」。
    // key 包含 range：切区间后仍可见的区块自动重拉，未露面的在露面时拉取。
    // 重复触发（滚动往返、重组）由 DashboardModule.loadMonitorTypes 的记账去重。
    //
    // 可见性必须**成对**上报：若只有「进入」而无「离开」，轮询侧会持续刷新滚出视口的图表，
    // 懒加载在刷新侧即失效。
    DisposableEffect(apiKeys) {
        onVisibilityChanged(apiKeys, true)
        onDispose { onVisibilityChanged(apiKeys, false) }
    }
    LaunchedEffect(range, apiKeys) { onNeedTypes(apiKeys) }

    val primary = shownTypes[0]

    // ── 电池图在「本机型没有电池」时的画法 ──
    //
    // core 对未声明 `Capability.BATTERY` 的型号**不再写入 `battery_history`**，
    // 但此前写入的行仍然存在，其值为系统占位值（F50 恒 50%）。
    // 这些点会使图表呈现「左半段 50% 曲线、右半段为空」，配合固定的 0~100 Y 轴，
    // 看起来如同电量持续下降至 0，与实际不符。
    //
    // 处理：曲线灰化 + 去除填充。既避免这些点被误认为「正在采集的实时曲线」
    // （灰色 + 无填充在本页 8 张图中是唯一的形式，不会与真实曲线混淆），
    // 又保留其可查性 —— 直接移除会使「此处曾有过数据」这一信息丢失。
    // 这些点代表什么，由调用点的说明卡说明。
    val palette = LocalResolvedPalette.current
    val batteryStale = primary == MonitorMetricType.BATTERY && !batterySupported
    val staleLineColor = palette.textSecondary.copy(alpha = 0.45f)

    // 按当前时间范围过滤数据：historyFor 返回完整历史序列，
    // 后端/mergePoints 可能保留窗口外（跨天）的点；图表层必须裁剪到 [queryStartMs, queryEndMs]，
    // 否则「今天」范围会绘制出昨天或更早的数据。
    //
    // 性能：裁剪与 UfiDownsampledPoint 转换全部收进同一个 remember。
    // 不得将 startMs/endMs 直接写入 remember 的 key：实时区间的 queryEndMs 为
    // System.currentTimeMillis()，key 每次求值都不同，remember 将永不命中；
    // 且 primary 的 `filter{}` 若写在函数体内，每次重组都会重新拷贝 ≤720 个点。
    // 因此 key 使用 range 实例本身（selectedRange 仅在切换范围/加载时更换），窗口端点在块内取一次。
    //
    // key 从整个 monitorState 收窄到「本区块实际使用的那几条历史序列」：
    // monitorState 任一字段变化（告警补页、isLoading 翻转、settings…）都会使 4 个区块
    // 各自重建 ≤240 个 UfiDownsampledPoint，并使 points 更换实例，导致图表几何重建。
    val rawSeries = shownTypes.map { historyFor(monitorState, it) }
    // 「曲线画不到右端终点」的 app 侧根因：裁剪上下界各放宽半个桶宽。
    // 服务端返回的 t 是**桶心**而非桶起点 —— core/api/.../MonitorRoutes.kt 的 aggregateBetween 中
    // `center(bucket) = bucket * bucketMs + bucketMs / 2`；而实时区间的 queryEndMs 即「当前时刻」。
    // 当前桶刚开始（now 落在桶的前半段）时，其桶心必然 > now，因此 `it.t in startMs..endMs`
    // 会把**最新那个点**整体裁掉：曲线停在上一个桶，右端留空，表现为「画不到终点」。
    // 只要 now 落在桶前半段即会命中，约一半的刷新会缺少最新点；以当前值为主要信息的图
    //（上行/下行速率）最易观察到该现象。
    // 半个桶宽等于「桶心相对桶起点的偏移量」：加上它恰好包含当前桶，又不会纳入下一个桶。
    // 下界同理向左放宽半桶 —— 首个桶的桶心也可能落在 queryStartMs 之前，会被同样方式裁掉。
    // bucketMs 进入 remember key：其变化（换区间 / 首次取得服务端桶宽）会使裁剪边界重算。
    // bucketMs <= 0（旧 core 不返回 bucket_ms）时 halfBucketMs=0，退回原硬边界，行为不变。
    val bucketMs = monitorState.bucketMs
    // batteryStale / staleLineColor 进入 key：二者决定电池曲线的取色（见上文），
    // 遗漏时能力集返回后曲线不会变灰。
    val chartData = remember(rawSeries, chartColors, range, bucketMs, batteryStale, staleLineColor) {
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
                    MonitorMetricType.BATTERY ->
                        if (batteryStale) staleLineColor else chartColors.battery
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

    // ═══ 诊断日志：区分「图表暂无数据」的三种成因 ═══
    // 记录**事实**：每类指标的原始点数、裁剪后点数、桶宽、以及 loadedMonitorTypes 视角的
    // 记账状态。三种「空」在 UI 上表现一致，但根因不同：
    //   ① 服务端未返回行（rawSeries 为空）→ 采集/接口/区间问题
    //   ② 服务端返回但被裁剪窗口滤除（rawSeries 非空、primaryPoints 为空）→ 裁剪边界计算错误
    //   ③ 桶宽未知（bucketMs <= 0）导致 X 轴域为 null，各图自适应 → 视觉上对不齐
    // ═══ 空态三态判定 ═══
    //
    // 仅依据 points.isEmpty() 会把「持有旧区间的点但均落在新窗口外」也报为「暂无数据」。
    // 该中间态已由日志确证：raw=12 clipped=0 isLoading=true —— 存在历史数据却显示
    // 「暂无数据」，而后台正在拉取新区间，数秒后恢复（切区间的正常过渡，并非缺陷）。
    // 因此区分三种状态：正在取数 / 区间切换中 / 确实无数据。
    val rawCount = rawSeries.firstOrNull()?.size ?: 0
    val emptyReason = when {
        primaryPoints.isNotEmpty() -> ChartEmptyReason.NO_DATA  // 非空时不显示，占位
        monitorState.isLoading -> ChartEmptyReason.LOADING
        rawCount > 0 -> ChartEmptyReason.SWITCHING
        else -> ChartEmptyReason.NO_DATA
    }

    // 所有使用 valueFormatter 的图表，其 formatter 已包含单位（如 pctAxisLabel→"51%"、
    // formatBytes→"389.0 KB/s"、信号→"dBm"），而 UfiMonitorChart 的 formatWithUnit 会再拼接 unit；
    // 因此此处 unit 一律传空，避免 "51%%" / "389.0 KB/sB/s" 这类双重单位。
    val effectiveUnit = ""

    // 图表卡片使用紧凑 padding：默认 CardPadding=20dp 偏大，卡片上下留白过多。
    // 改为上下 10~12dp、左右 14dp，减少纵向占用。
    MonitorSectionCard(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
        ChartSectionTitle(title = title) {
            SeriesLegendChips(allSeries = allSeries)
        }
        FixedMonitorChart(
            // 卡片标题（视觉上仍合并展示「系统资源」，便于快速识别）
            title = title,
            // series 名固定为 primary.label（避免与 extraSeries 中的 secondary.label 语义重叠，
            //   否则 tooltip 会把 "CPU 使用率 / 内存占用" 整体显示在 series 0 行，造成重复）
            seriesLabel = primary.label,
            points = primaryPoints,
            unit = effectiveUnit,
            lineColor = allSeries[0].lineColor,
            isLoading = monitorState.isLoading,
            emptyReason = emptyReason,
            valueFormatter = yFormatterFor(primary),
            yAxisLabelFormatter = yAxisLabelFormatter ?: yAxisFormatterFor(primary),
            showLegend = false,
            symmetricHorizontal = true,
            extraSeries = allSeries.drop(1),
            timeRange = timeRangeStr,
            // 「Y 轴固定」只对有公认定义域的指标生效（百分比 / 摄氏度）；
            // 流量与信号没有固定量程，硬给一个只会把曲线压成一条直线。
            fixedYRange = if (monitorState.settings.fixedYAxis) fixedYRangeFor(primary) else null,
            // 本机型没有电池时不绘制填充：填充表示「该曲线为本页主序列」，
            // 给历史遗留的无效曲线添加该信号会使其更像真实数据（见上文）。
            fillAlpha = if (batteryStale) 0f else monitorState.settings.fillAlpha,
            // 桶宽来自服务端响应（state.bucketMs）：图表据此把真正的采集空洞断开，
            // 否则采集中断那几小时会被画成一条平直的假数据。
            // 注意这是**全区间一个值**，且它只是绘图分辨率（可见区间 / MAX_POINTS），不等于采集间隔；
            // 图表内部只把它当断档判定的下限，真正的基准是各序列自己的间距中位数。
            bucketMs = monitorState.bucketMs,
            // 同屏各图共用的时间标尺（见 MonitorChartsTabContent 说明）
            xDomainStartMs = xDomainStartMs,
            xDomainEndMs = xDomainEndMs
        )
    }
}

/**
 * 「告警引擎未开启」提示卡。
 *
 * 引擎关闭（core `AlertConfig.enabled = false`）时设备既不检测也不入库，事件列表恒为空，
 * 而空态文案为「今日暂无异常事件」—— 该文案在此场景下不成立：并非没有异常，而是未进行检测。
 * 因此将原因、一键开启及阈值设置入口置于列表最前面。
 *
 * @param canEnable 配置镜像是否已拉取。未拉取时不得写入：此时会以本地默认值 + 旧 version 提交，
 *                  可能把设备上的阈值整体重置为默认值（同 [AlertSettingsScreen] 的 configLoaded 守卫）。
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

// ==================== 事件中心独立全屏页内容 ====================

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
    var eventDateKey by remember { mutableStateOf("all") }
    var eventCustomRange by remember { mutableStateOf<MonitorTimeRange.Custom?>(null) }

    /**
     * 日历中选中的日期（yyyy-MM-dd，null = 不按天过滤）。
     * 事件中心采用「日历热力 + 当天时间轴」，默认选中今天。
     */
    var selectedDayKey by rememberSaveable { mutableStateOf(localDayKey(System.currentTimeMillis())) }
    /** 月历当前显示的月份（该月 1 日 00:00 的毫秒）。 */
    var calendarMonthMs by rememberSaveable { mutableLongStateOf(localMonthStartMs(System.currentTimeMillis())) }
    /** 月历 / 周条。 */
    var calendarMode by rememberSaveable { mutableStateOf("month") }
    var weekAnchorMs by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }


    // 事件中心列表「按类型聚合」模式开关，默认 true。
    // 若每条 AlertRecord 单卡一行，500+ 条会堆叠且每张卡都展示完整 message，信息冗余；
    // 因此默认按 (type, level) 聚合，每行仅显示「类型 + 累计次数 + 最近时间 + 等级 chip」，
    // 点击打开 UfiScrollableDialog 弹窗显示该类型的具体事件，内置 20/页分页与 ack。

    // 弹窗展示哪个 (type, level) 的具体事件明细（null = 关闭）
    var detailTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    /** 底部详情 sheet 选中的那条事件（null = 关闭）。 */
    var selectedAlert by remember { mutableStateOf<com.ufi_axis.data.model.AlertRecord?>(null) }


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

    /** 兜底：按当前已加载的告警统计每天条数（接口失败时日历仍有数据）。 */
    val localDayCounts = remember(monitorState.alerts) {
        monitorState.alerts.groupingBy { localDayKey(it.timestamp) }.eachCount()
    }
    /** 服务端按天计数：准确值，覆盖已加载范围之外的月份。 */
    val remoteDayCounts by viewModel.dashboard.alertDailyCounts.collectAsState()
    val dayCounts = if (remoteDayCounts.isNotEmpty()) remoteDayCounts else localDayCounts
    LaunchedEffect(calendarMonthMs) {
        viewModel.dashboard.loadAlertDailyCounts(
            start = calendarMonthMs,
            end = shiftMonth(calendarMonthMs, 1)
        )
    }
    // 选中某天时**按该日区间拉取数据**。
    // 仅做本地过滤会遗漏数据 —— loadAllAlerts() 只取一页（≤200 条），选中上个月时
    // 该批记录不在内存中，界面会误显示「这一天没有事件」。
    LaunchedEffect(selectedDayKey) {
        selectedDayKey?.let { key ->
            val start = runCatching {
                java.time.LocalDate.parse(key)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            }.getOrNull() ?: return@let
            viewModel.dashboard.loadAlerts(
                MonitorTimeRange.Custom(start, start + 86_400_000L - 1)
            )
        }
    }
    val levelCounts = mapOf(
        "all" to monitorState.alerts.size,
        "warning" to monitorState.alerts.count { it.level == "warning" || it.level == "critical" },
        "critical" to monitorState.alerts.count { it.level == "critical" }
    )
    val filteredAlerts = remember(
        monitorState.alerts, eventLevelFilter, eventTypeFilter, eventReadFilter, sortMode,
        eventDateKey, eventCustomRange, selectedDayKey
    ) {
        var list = monitorState.alerts
        // 日历选中日优先：它是「当天列表」的数据源。
        if (selectedDayKey != null) list = list.filter { localDayKey(it.timestamp) == selectedDayKey }
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

    /** 滚动加载：不做切片分页，全部过滤结果直接交给 LazyColumn。 */
    val pageDayGroups: Map<String, List<com.ufi_axis.data.model.AlertRecord>> =
        remember(filteredAlerts) { filteredAlerts.groupBy { localDayKey(it.timestamp) } }

    Box(Modifier.fillMaxSize()) {
        // 使用 LazyColumn 而不是 Column(verticalScroll)，使事件卡真正懒加载。
        // 若一次性组合整页 pageSize 条 MonitorEventCard（默认 20 条，可调至 100），
        // 进入 Tab / 翻页 / 切筛选都需同步组合完成后才出画面，会造成可感知的卡顿。
        // 头部（统计卡 + 模式切换 + 筛选行）为整块内容，无需各自回收，放在单个 item 中。
        val listState = rememberLazyListState()
        val fabReservedBottom = bottomPadding
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()

                .padding(bottom = fabReservedBottom)
        ) {
            item(key = "eventsHeader") {
            Column {
            MonitorLoadingHint(visible = monitorState.isLoading && monitorState.alerts.isEmpty())

            // ── 日历热力：每格底色 = 当天事件量，点选某天查看当天时间轴 ──
            CalendarModeSwitch(
                mode = calendarMode,
                onToggle = { calendarMode = if (calendarMode == "month") "week" else "month" }
            )
            // 月/周切换使用淡入淡出（Crossfade），不瞬间替换。
            // key 同时包含「月/周」与当前锚点：切换月份和切换模式均有淡入淡出。
            Crossfade(
                targetState = calendarMode to
                    (if (calendarMode == "month") calendarMonthMs else weekStartMs(weekAnchorMs)),
                animationSpec = tween(220),
                label = "calendarMode"
            ) { target ->
            // 旧/新两侧必须各自按 target 渲染：读取外层 calendarMode 会使两侧都渲染新内容，
            // 动画将表现为未生效。
            val anchor = target.second
            if (target.first == "month") {
                AlertCalendarCard(
                    monthStartMs = calendarMonthMs,
                    dayCounts = dayCounts,
                    selectedKey = selectedDayKey,
                    onPrevMonth = { calendarMonthMs = shiftMonth(calendarMonthMs, -1) },
                    onNextMonth = { calendarMonthMs = shiftMonth(calendarMonthMs, 1) },
                    onSelectDay = { key ->
                        // 兜底：即使某个格子遗漏 enabled，未来日期也不接受。
                        if (key <= localDayKey(System.currentTimeMillis())) selectedDayKey = key
                    }
                )
            } else {
                AlertWeekStrip(
                    anchorMs = weekAnchorMs,
                    dayCounts = dayCounts,
                    selectedKey = selectedDayKey,
                    onPrev = { weekAnchorMs = shiftDays(weekAnchorMs, -7) },
                    onNext = { weekAnchorMs = shiftDays(weekAnchorMs, 7) },
                    onSelectDay = { key ->
                        // 兜底：即使某个格子遗漏 enabled，未来日期也不接受。
                        if (key <= localDayKey(System.currentTimeMillis())) selectedDayKey = key
                    }
                )
            }
            }

            // 当天头部：日期 + 条数 + 未读 + 全部已读 / 回到今天
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dayLabel = selectedDayKey?.let { key ->
                    val ms = java.time.LocalDate.parse(key)
                        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    localDayLabel(ms)
                } ?: "全部事件"
                Text(dayLabel, style = UfiTextStyles.panelTitleStrong, color = palette.textPrimary)
                Spacer(Modifier.width(Spacing.Medium))
                Text("${filteredAlerts.size} 条", style = UfiTextStyles.caption, color = palette.textSecondary)
                val unreadToday = filteredAlerts.count { !it.acknowledged }
                if (unreadToday > 0) {
                    Spacer(Modifier.width(Spacing.Medium))
                    Surface(color = palette.error.copy(alpha = 0.12f), shape = UfiCardDefaults.pillShape) {
                        Text(
                            text = "未读 $unreadToday",
                            style = UfiTextStyles.captionStrong,
                            color = palette.error,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (selectedDayKey != localDayKey(System.currentTimeMillis())) {
                    TextButton(onClick = { selectedDayKey = localDayKey(System.currentTimeMillis()) }) {
                        Text("回到今天", style = MaterialTheme.typography.labelLarge, color = palette.accent)
                    }
                }
            }

            // 不显示统计卡、「筛选结果」行、排序/日期说明、等级 chips
            // （与日历 + 时间轴在视觉上重复）。
            // 顶部仅保留两个图标入口：筛选（带角标）与排序；批量已读位于筛选左侧的弹层中。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                if (monitorState.alerts.any { !it.acknowledged }) {
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
                EventToolIcon(
                    icon = Icons.Filled.Tune,
                    description = "筛选",
                    active = activeFilterCount > 0,
                    badge = activeFilterCount,
                    onClick = { showFilterDialog = true }
                )
                Spacer(Modifier.width(6.dp))
                MonitorSortButton(sortMode = sortMode, onClick = { showSortDialog = true })
            }
            Spacer(Modifier.height(2.dp))
            }   // header Column
            }   // item("eventsHeader")

            // 列表本体：每条事件一个 LazyColumn item，滚动到才组合。
            // 翻页不套用 AnimatedContent（淡入 + 0.98→1 微缩放）：翻页属于「换一批数据」，
            // 不是导航转场；内容淡入会使首屏可见时间多等一个 Duration.Smooth，观感类似刷新动画。
            // 空态判据同上：使用 alertsLoaded，不使用被图表 loader 共用的 isLoading。
            if (filteredAlerts.isEmpty() && monitorState.alertsLoaded) {
                item(key = "emptyAll") {
                    UfiEmptyState(
                        icon = Icons.Default.Inbox,
                        message = when {
                            selectedDayKey != null -> "这一天没有事件"
                            activeFilterCount > 0 -> "没有符合筛选条件的事件"
                            else -> "今天暂无异常事件"
                        },
                        hint = "调整筛选条件试试",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                // 按天分组，每条事件落在自己的时刻上。
                // 不使用「聚合 / 明细」双分支：聚合行没有独立时刻，无法放入时间轴。
                pageDayGroups.forEach { (dayKey, dayAlerts) ->
                    item(key = "day-$dayKey") {
                        EventDayHeader(
                            label = localDayLabel(dayAlerts.first().timestamp),
                            count = dayAlerts.size,
                            unread = dayAlerts.count { !it.acknowledged }
                        )
                    }
                    items(dayAlerts, key = { it.id }) { alert ->
                        EventTimelineRow(
                            alert = alert,
                            onClick = { selectedAlert = alert },
                            onLongClick = { pendingDeleteAlertId = alert.id }
                        )
                    }
                }
                item(key = "eventsTail") {
                    Text(
                        text = "已显示全部 ${filteredAlerts.size} 条",
                        style = UfiTextStyles.caption,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
                    )
                }
            }
        }


        selectedAlert?.let { alert ->
            EventDetailSheet(
                alert = alert,
                onDismiss = { selectedAlert = null },
                onAck = {
                    viewModel.dashboard.ackAlert(alert.id)
                    selectedAlert = null
                },
                onDelete = {
                    pendingDeleteAlertId = alert.id
                    selectedAlert = null
                }
            )
        }

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
                val close = LocalUfiDialogClose.current
                UfiButton(text = "删除", onClick = {
                    close {
                        pendingDeleteAlertId?.let { id -> viewModel.dashboard.deleteAlert(id) }
                        pendingDeleteAlertId = null
                    }
                })
            },
            dismissButton = {
                val close = LocalUfiDialogClose.current
                UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = { close { pendingDeleteAlertId = null } })
            }
        ) {
            UfiDialogBody {
                Text(
                    text = "确定删除这条事件？删除后不可恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textPrimary
                )
                pendingDeleteAlert?.let { a ->
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

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })

        // 聚合行点开后的具体事件明细弹窗。detailTarget 非空 = 打开中。
        // 弹窗复用 monitorState.alerts 作为数据源（前端已加载，无额外 HTTP），
        // 内置 IN_DIALOG_PAGE_SIZE_DEFAULT=20/页 分页，单条 ack 会实时反映到主列表。
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
            val close = LocalUfiDialogClose.current
            UfiButton(text = "确定", onClick = { close { onConfirm(draftLevel, draftType, draftRead, draftDateKey, draftCustomRange) } })
        },
        dismissButton = {
            val close = LocalUfiDialogClose.current
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = { close(onDismiss) })
        }
    ) {
        UfiDialogBody {
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
    // isPressed 仅用于配色（按下加深底色 / 提亮文字）；缩放交由 ufiPressScale。
    // 不使用 collectIsPressedAsState：短按只在一两帧内为 true，
    // 下方 200ms 的 animateColorAsState 仅完成几个百分比，底色变化不可见。
    // rememberUfiPressed 将按下态保底维持 Duration.Micro（120ms），与按压缩放同步。
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
            // 缩放档位取 UfiMotion.PressScale.Chip（0.97）：本控件是「未读」筛选胶囊，
            // 与 CategoryChip / EventTypeChip / MonitorFilterChip 同类，统一为 Chip 档。
            // 不使用 animateFloatAsState + graphicsLayer：抬手时目标立即回到 1f，
            // 0.03 的落差配 tween(120) 一帧仅完成约 5%；事件列表位于可滚动页面，Press 常与
            // Release 同帧送达，可用时长为 0，因此只有长按可见。改由 ufiPressScale 事件驱动
            // 编排 + 最短保持 Duration.Micro（120ms），档位与 spec 不变。
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
    emptyReason: ChartEmptyReason = ChartEmptyReason.NO_DATA,
    valueFormatter: ((Double) -> String)? = null,
    onClick: (() -> Unit)? = null,
    extraSeries: List<MonitorSeries> = emptyList(),
    yAxisLabelFormatter: ((Double) -> String)? = null,
    showLegend: Boolean = true,
    symmetricHorizontal: Boolean = false,
    timeRange: String = "week",
    // 图表 series 名（用于图例 chip / tooltip），与卡片标题解耦：
    //   当卡片有合并标题（如「CPU 使用率 / 内存占用」）时，series 名仍应为 primary.label
    //   （「CPU 使用率」），否则 tooltip 会把两个 series 名合并显示为「CPU 使用率 / 内存占用 4%」。
    seriesLabel: String? = null,
    chartHeight: androidx.compose.ui.unit.Dp = 130.dp,
    // ── 设置接入：这两个形参必须在调用点传值，否则「Y 轴固定」「填充透明度」两个开关不生效。 ──
    fixedYRange: ClosedFloatingPointRange<Double>? = null,
    fillAlpha: Float = 1f,
    // 服务端实际生效的桶宽（毫秒，0=未知），透传给图表作为「采集空洞断档」判定的下限参考。
    bucketMs: Long = 0L,
    // 同屏各图共用的 X 轴时间域（null = 图表按自身点集取 min/max）。
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
            // 转换结果使用 remember 缓存：若在函数体内直接转换，每次重组都会重新分配
            // ≤720 个 UfiDownsampledPoint（4 张图表卡 × 每次 state 发射）。
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
                // 加载态不使用 UfiLoadingIndicator（1s 一圈的呼吸弧）：它绑定
                // monitorState.isLoading，每轮轮询都会旋转一次，表现为持续的「刷新动画」。
                // 仅保留静态文案：图表是否有数据可直观判断，转圈不提供额外信息。
                Text(
                    when (emptyReason) {
                        ChartEmptyReason.LOADING -> "数据加载中…"
                        ChartEmptyReason.SWITCHING -> "切换中…"
                        ChartEmptyReason.NO_DATA -> "暂无数据"
                    },
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

// 速率格式化统一到 [com.ufi_axis.util.FormatUtils.formatRate]
//（换算口径不变，额外获得 GB/s 档与 NaN → "—"）。

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
 * 不得使用 `"%.1f MB/s"` / `"%.1f KB/s"` / `"%.1f B/s"`：最长形式 `389.0 KB/s` 共 10 字符，
 * 而 Y 轴列宽按最宽刻度实测计算，绘图区左右外距又由其推导（见 `UfiMonitorChart` 的
 * `yAxisMinWidth` 注释）。字符数偏大会使同屏 8 张图中这两张速率图的 X 轴偏短、偏左，
 * 且峰值跨过 1MB/s 时（10 字符 ↔ 9 字符）宽度会发生变化。
 *
 * 因此将字符数**限制在 ≤5**：
 * 1. 单位压缩为单字母、去掉 `/s` —— 单位在卡片标题「上/下行速率」与 tooltip 中保留
 *    （tooltip 使用 [yFormatterFor] 的 `FormatUtils.formatRate`，仍为完整的 "12.3 MB/s"），
 *    轴上重复显示只会压缩绘图区；
 * 2. 尾数 ≥100 取整数、<100 保留一位小数。尾数值域为 [0, 1024)，最长形式只有
 *    `1023K`（4+1）与 `99.9M`（4+1）两种，上界为 5 字符。
 *
 * 修改前必须先阅读 `Spacing.ChartYAxisAlignedWidth`：对齐依赖「所有图的标签均 ≤5 字符、
 * 因而统一命中同一宽度下限」，字符数超出上界会使该图重新错位。
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

// ── 「本机型没有电池」时电池图说明卡的文案 ──
//
// 第一句与首页电池详情弹窗的文案保持一致：
// 「本机型可能没有电池，或系统读不到真实电量 —— 上面的数值仅供参考，不做记录与告警。」
// 沿用「本机型…没有电池」「不做记录与告警」两种表述，不另立措辞。
// 差别在于去掉「可能」与「或系统读不到真实电量」：首页的判据是电池读数附带的
// `supported` 字段（旧 core 不下发时默认 true，需保留余地），此处的判据是设备能力集
// 明确不含 `Capability.BATTERY` —— 该值为插件声明的硬件事实，不是推测。
//
// 口径与 `deviceUnsupportedNote` 一致：只说明「本设备没有该硬件」，
// 不写「未开启」「权限不足」—— 该机型没有电池，不存在可开启的地方，
// 这两种表述会误导用户进行无效排查。
//
// 第二句针对左半段的遗留曲线：core 不再写入 `battery_history`，
// 而此前记录的是系统占位值（F50 恒 50%）。不作说明会被理解为「电量降至 0」。
// 「若…」为条件表述：全新部署不存在旧行，图中只有「暂无数据」，此时不应断言存在曲线。
private const val BATTERY_UNSUPPORTED_CHART_NOTE =
    "本机型没有电池，不采集电量，也不做记录与告警。" +
        "若图中仍有灰色曲线，那是旧版本记录的假电量（恒 50%），不代表真实电量，也不会再增加。"

/**
 * 「Y 轴固定」时该指标的固定量程；无公认定义域的指标返回 null（继续自适应）。
 *
 * 只有百分比类（CPU / 内存 / 电池）与摄氏度有公认区间。流量为 B/s、信号为 dBm/dB，
 * 为其设定固定区间会把曲线压平，效果劣于自适应。
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
    // 内边距取 2dp 上 / 4dp 下、左右 0（卡片 padding 已提供横向内距），不使用 6/6/8/6。
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
 * 浮动分页条的「按滚动方向自动隐藏」判据 —— [LazyListState]（[LazyColumn]）版。
 *
 * 仅保留 LazyColumn 版：事件中心改为 LazyColumn 后，两个 Tab 均使用该版本，
 * 原 [androidx.compose.foundation.ScrollState] 版已无调用方。
 *
 * 总览 Tab 的内容为 LazyColumn，无法直接取得「从顶部滚动的像素数」这一连续量；此处用
 * `首个可见 item 下标 × 步长 + 该 item 内偏移` 合成**单调代理值**：
 * 下滚时下标或偏移必增、上滚必减，因此 `delta` 的**符号**与真实滚动方向一致。
 * 判据只使用符号（`> 4` / `< -4` 为抖动死区），不依赖幅度等于真实像素。
 * 步长取远大于任何 item 高度的值，保证「下标 +1」的增量压过「偏移回到 0」的减量。
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
 * @param scrollValue **必须是 lambda**：`delay` 之后还需再读取一次当前值，以判断
 *   该时间段内是否发生滚动。传入快照值会使该比较恒为真，分页条将无条件隐藏。
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
 * 首屏加载提示：**静态**一行文案，不使用 `UfiLinearLoading`（M3 indeterminate 横条）。
 *
 * 判据一律为「isLoading **且**对应数据为空」，即仅出现在首屏；轮询刷新使用 `silent = true`，
 * 不改变 isLoading，因此已有数据时该行不会出现（见本文件顶部轮询注释）。
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
        // 缩放档位取 UfiMotion.PressScale.Button（0.96），本控件为 OutlinedButton。
        // 不使用 animateFloatAsState + graphicsLayer：抬手时目标立即回到 1f，
        // 0.04 的落差配 spring(1.0/600) 一帧仅完成约 6%（40dp 上不足一个像素）；事件中心工具栏位于
        // 可滚动页面，Press 常与 Release 同帧送达，可用时长为 0。改由 ufiPressScale 编排。
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
            val close = LocalUfiDialogClose.current
            UfiButton(variant = UfiButtonVariant.Secondary, text = "完成", onClick = { close(onDismiss) })
        }
    ) {
        val palette = LocalResolvedPalette.current
        // 选中一档即提交并关闭（onConfirm 中会将 showPageSizeDialog 置为 false），
        // 因此此处点击也属于「关闭动作」，同样需要经离场时序。
        val close = LocalUfiDialogClose.current
        UfiDialogBody {
            Text(
                text = "选择列表每页显示的事件数量，重启应用后仍保留。",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary
            )
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
                onSelect = { size -> close { onConfirm(size) } },
                columns = 3
            )
        }
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
            val close = LocalUfiDialogClose.current
            UfiButton(text = "跳转", onClick = {
                val parsed = input.trim().toIntOrNull()
                // 仅校验通过（会实际关闭弹窗）的分支经 close 执行；校验失败时弹窗需保留，不能包裹。
                if (parsed != null && parsed in 1..pageCount) close { onConfirm(parsed) }
                else android.widget.Toast.makeText(
                    toastContext,
                    "请输入 1 到 $pageCount 的页码",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            })
        },
        dismissButton = {
            val close = LocalUfiDialogClose.current
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = { close(onDismiss) })
        }
    ) {
        UfiDialogBody {
            Text(
                text = "当前共 $pageCount 页。输入目标页码直接跳转：",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary
            )
            // 使用公共 UfiDigitField 而不是裸 OutlinedTextField（数字过滤与 4 位截断由组件处理）。
            UfiDigitField(
                value = input,
                onValueChange = { input = it },
                label = "页码 (1 - $pageCount)",
                maxLength = 4
            )
        }
    }
}
/**
 * 总览时间轴的一行：时刻列 + 轴点 + 事件卡。
 *
 * 数据为 [AggregateAlertRow]（同一类型、同一等级聚合后的行），因此只呈现
 * 类型 / 等级 / 次数 / 未读，**不推断具体值或阈值** —— 这些信息在点开的明细弹窗中。
 * 未读 = 实心圆点（颜色 = 等级），已读 = 空心点。
 */
@Composable
private fun MonitorTimelineRow(
    row: AggregateAlertRow,
    onClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val unread = row.unread > 0
    val levelColor = when (row.level) {
        "critical" -> palette.error
        "warning" -> palette.warning
        else -> palette.success
    }
    val levelText = when (row.level) {
        "critical" -> "严重"
        "warning" -> "警告"
        else -> "提示"
    }
    val timeText = remember(row.lastTimestampMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(row.lastTimestampMs))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = palette.textSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp).padding(top = 12.dp)
        )
        Box(
            modifier = Modifier.width(24.dp).fillMaxHeight(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(palette.divider))
            Box(
                Modifier
                    .padding(top = 14.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (unread) levelColor else palette.cardBg)
                    .then(
                        if (unread) Modifier
                        else Modifier.border(1.5.dp, palette.divider, CircleShape)
                    )
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = Spacing.CardHorizontalMargin, bottom = 10.dp)
                .clip(UfiCardDefaults.shape)
                .background(palette.cardBg)
                .border(1.dp, palette.divider, UfiCardDefaults.shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = alertTypeLabel(row.type),
                    style = UfiTextStyles.sectionTitle,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(Spacing.Medium))
                Surface(
                    color = levelColor.copy(alpha = 0.12f),
                    shape = UfiCardDefaults.pillShape
                ) {
                    Text(
                        text = levelText,
                        style = UfiTextStyles.caption,
                        color = levelColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (unread) {
                    "发生 ${row.count} 次 · 未读 ${row.unread} 条"
                } else {
                    "发生 ${row.count} 次 · 全部已读"
                },
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
        }
    }
}
// ───────────────────────────────────────────────────────────
// 事件中心时间轴的日期分组与行
// ───────────────────────────────────────────────────────────

/** 本地日期键（yyyy-MM-dd），仅用于分组。 */
private fun localDayKey(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))

/** 日期展示：今天 / 昨天 / M月d日。 */
private fun localDayLabel(ts: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    val today = Calendar.getInstance()
    val sameYear = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    val dayDiff = today.get(Calendar.DAY_OF_YEAR) - cal.get(Calendar.DAY_OF_YEAR)
    return when {
        today.get(Calendar.YEAR) == cal.get(Calendar.YEAR) && dayDiff == 0 -> "今天"
        today.get(Calendar.YEAR) == cal.get(Calendar.YEAR) && dayDiff == 1 -> "昨天"
        sameYear -> SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(ts))
        else -> SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(ts))
    }
}

/** 日期分组头：日期 + 条数 + 未读徽标。 */
@Composable
private fun EventDayHeader(label: String, count: Int, unread: Int) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.CardHorizontalMargin, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = UfiTextStyles.panelTitleStrong, color = palette.textPrimary)
        Spacer(Modifier.width(Spacing.Medium))
        Text("$count 条", style = UfiTextStyles.caption, color = palette.textSecondary)
        if (unread > 0) {
            Spacer(Modifier.width(Spacing.Medium))
            Surface(color = palette.error.copy(alpha = 0.12f), shape = UfiCardDefaults.pillShape) {
                Text(
                    text = "未读 $unread",
                    style = UfiTextStyles.captionStrong,
                    color = palette.error,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/**
 * 时间轴的一行：时刻列 + 轴点 + 事件卡。
 *
 * 副行只描述**该条事件自身**的内容：优先显示「当前值 / 阈值」，缺失时使用 message。
 * 不得写入当天的聚合次数 —— 时间轴一行对应一条事件，聚合信息属于总览的聚合行。
 * 未读 = 实心轴点，已读 = 空心轴点；点击标记已读，长按删除。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EventTimelineRow(
    alert: com.ufi_axis.data.model.AlertRecord,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val unread = !alert.acknowledged
    val levelColor = when (alert.level) {
        "critical" -> palette.error
        "warning" -> palette.warning
        else -> palette.success
    }
    val levelText = when (alert.level) {
        "critical" -> "严重"
        "warning" -> "警告"
        else -> "提示"
    }
    val timeText = remember(alert.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(alert.timestamp))
    }
    val detail = if (alert.value.isNotBlank() && alert.threshold.isNotBlank()) {
        "${alert.value} / 阈值 ${alert.threshold}"
    } else {
        alert.message
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = palette.textSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp).padding(top = 11.dp)
        )
        Box(
            modifier = Modifier.width(24.dp).fillMaxHeight(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(palette.divider))
            Box(
                Modifier
                    .padding(top = 13.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (unread) levelColor else palette.cardBg)
                    .then(
                        if (unread) Modifier
                        else Modifier.border(1.5.dp, palette.divider, CircleShape)
                    )
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = Spacing.CardHorizontalMargin, bottom = 8.dp)
                .clip(UfiCardDefaults.shape)
                .background(palette.cardBg)
                .border(1.dp, palette.divider, UfiCardDefaults.shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = alertTypeLabel(alert.type),
                    style = UfiTextStyles.sectionTitle,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(Spacing.Medium))
                Surface(color = levelColor.copy(alpha = 0.12f), shape = UfiCardDefaults.pillShape) {
                    Text(
                        text = levelText,
                        style = UfiTextStyles.caption,
                        color = levelColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
/** 当月 1 日 00:00 的毫秒。 */
private fun localMonthStartMs(ts: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ts
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** 月份平移（delta 为月数）。 */
private fun shiftMonth(monthStartMs: Long, delta: Int): Long =
    Calendar.getInstance().apply {
        timeInMillis = monthStartMs
        add(Calendar.MONTH, delta)
    }.timeInMillis

/**
 * 日历热力卡：每格底色 = 当天事件量（按当前已加载告警计算）。
 *
 * 不使用取模的固定色阶：最大值格需随数据缩放，否则事件少时整月偏浅、
 * 事件多时又全部偏深。此处按 maxCount 线性映射到 0..50% 的 accent 混色。
 */
@Composable
private fun AlertCalendarCard(
    monthStartMs: Long,
    dayCounts: Map<String, Int>,
    selectedKey: String?,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDay: (String) -> Unit
) {
    val palette = LocalResolvedPalette.current
    val monthCal = remember(monthStartMs) {
        Calendar.getInstance().apply {
            timeInMillis = monthStartMs
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }
    val year = monthCal.get(Calendar.YEAR)
    val month = monthCal.get(Calendar.MONTH)
    val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
    // 周一=0 … 周日=6
    val firstOffset = (monthCal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val monthPrefix = String.format(Locale.US, "%04d-%02d-", year, month + 1)
    val monthTotal = dayCounts.filterKeys { it.startsWith(monthPrefix) }.values.sum()
    val maxCount = (dayCounts.filterKeys { it.startsWith(monthPrefix) }.values.maxOrNull() ?: 0)
        .coerceAtLeast(1)
    val todayKey = localDayKey(System.currentTimeMillis())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize(animationSpec = tween(200))
            .clip(UfiCardDefaults.shape)
            .background(palette.cardBg)
            .border(1.dp, palette.divider, UfiCardDefaults.shape)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = SimpleDateFormat("yyyy 年 M 月", Locale.getDefault()).format(monthCal.time),
                style = UfiTextStyles.sectionTitle,
                color = palette.textPrimary
            )
            Spacer(Modifier.width(Spacing.Medium))
            Text("$monthTotal 条", style = UfiTextStyles.caption, color = palette.textSecondary)
            Spacer(Modifier.weight(1f))
            CalendarNavButton("‹", onPrevMonth)
            Spacer(Modifier.width(6.dp))
            CalendarNavButton(
                "›",
                onNextMonth,
                enabled = shiftMonth(monthStartMs, 1) <= localMonthStartMs(System.currentTimeMillis())
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { d ->
                Text(
                    text = d,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        val totalCells = firstOffset + daysInMonth
        // 行数按需计算 + 卡片高度动画：既不出现空行，也避免切换月份时的布局跳动。
        val weeks = (totalCells + 6) / 7
        repeat(weeks) { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                repeat(7) { i ->
                    val cellIndex = w * 7 + i
                    val day = cellIndex - firstOffset + 1
                    if (day in 1..daysInMonth) {
                        val key = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
                        // 未来日期不可选（选择后也不会产生事件，仅为空页）。
                        val isFuture = key > todayKey
                        val count = dayCounts[key] ?: 0
                        val t = if (count == 0) 0f else (count.toFloat() / maxCount) * 0.28f
                        val cellBg = when {
                            isFuture -> palette.pageBg
                            key == selectedKey -> palette.accent.copy(alpha = 0.18f)
                            else -> lerp(palette.cardBg, palette.accent, t)
                        }
                        val onDark = t > 0.2f
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .padding(horizontal = 2.dp)
                                .clip(UfiCardDefaults.smallShape)
                                .background(cellBg)
                                .then(
                                    if (key == selectedKey) {
                                        Modifier.border(2.dp, palette.accent, UfiCardDefaults.smallShape)
                                    } else Modifier
                                )
                                .clickable(enabled = !isFuture) { onSelectDay(key) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = day.toString(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = when {
                                        isFuture -> palette.textSecondary.copy(alpha = 0.35f)
                                        onDark -> palette.onAccent
                                        else -> palette.textPrimary
                                    }
                                )
                                if (count > 0) {
                                    Text(
                                        text = count.toString(),
                                        style = UfiTextStyles.captionTiny,
                                        color = if (onDark) palette.onAccent.copy(alpha = 0.85f)
                                        else palette.textSecondary
                                    )
                                } else if (key == todayKey) {
                                    Box(
                                        Modifier
                                            .padding(top = 1.dp)
                                            .size(3.dp)
                                            .clip(CircleShape)
                                            .background(palette.accent)
                                    )
                                }
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f).height(38.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarNavButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val palette = LocalResolvedPalette.current
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(UfiCardDefaults.smallShape)
            .background(palette.pageBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) palette.textSecondary else palette.textSecondary.copy(alpha = 0.3f))
    }
}
/**
 * 单条事件的详情弹窗。
 *
 * 使用**居中公共弹窗**而不是底部 sheet：内容仅 4 行 + 2 个动作，sheet 的拇指可达优势不适用；
 * 公共 `UfiScrollableDialog` 自带窗口模糊/压暗、退场时序以及「弹窗在场时隐藏胶囊底栏」的广播，
 * 自绘 sheet 需自行实现这三项，且与删除确认框（居中弹窗）形态不一致。
 */
@Composable
private fun EventDetailSheet(
    alert: com.ufi_axis.data.model.AlertRecord,
    onDismiss: () -> Unit,
    onAck: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val levelColor = when (alert.level) {
        "critical" -> palette.error
        "warning" -> palette.warning
        else -> palette.success
    }
    val timeFmt = remember { SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()) }
    UfiScrollableDialog(
        visible = true,
        onDismiss = onDismiss,
        title = alertTypeLabel(alert.type),
        showCloseButton = true,
        actions = {
            UfiDialogActions(
                onDismiss = onDismiss,
                onConfirm = onAck,
                confirmText = if (alert.acknowledged) "已读" else "标记已读",
                dismissText = "关闭",
                enabled = !alert.acknowledged
            )
        }
    ) {
        UfiDialogBody {
            SheetRow("等级", when (alert.level) {
                "critical" -> "严重"
                "warning" -> "警告"
                else -> "提示"
            })
            SheetRow("时间", timeFmt.format(Date(alert.timestamp)))
            if (alert.value.isNotBlank() || alert.threshold.isNotBlank()) {
                SheetRow("当前 / 阈值", "${alert.value.ifBlank { "—" }} / ${alert.threshold.ifBlank { "—" }}")
            }
            SheetRow("发生次数", "${alert.count} 次")
            SheetRow("状态", if (alert.acknowledged) "已读" else "未读")
            SheetRow("说明", alert.message)
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text("删除这条事件", color = palette.error, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, value: String) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, style = UfiTextStyles.note, color = palette.textSecondary, modifier = Modifier.width(88.dp))
        Text(value, style = UfiTextStyles.note, color = palette.textPrimary, modifier = Modifier.weight(1f))
    }
}
// ───────────────────────────────────────────────────────────
// 月历 / 周条切换
// ───────────────────────────────────────────────────────────

/** 平移 N 天（按本地日历年，跨月/跨年安全）。 */
private fun shiftDays(ms: Long, days: Int): Long =
    Calendar.getInstance().apply {
        timeInMillis = ms
        add(Calendar.DAY_OF_YEAR, days)
    }.timeInMillis

/** 所在周的周一 00:00。 */
private fun weekStartMs(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    val iso = get(Calendar.DAY_OF_WEEK)
    val offset = if (iso == Calendar.SUNDAY) 6 else iso - Calendar.MONDAY
    add(Calendar.DAY_OF_YEAR, -offset)
}.timeInMillis

/** 月/周分段：做成等宽两段的胶囊轨道，贴在日历卡上方（不再是一个孤零零的文字按钮）。 */
@Composable
private fun CalendarModeSwitch(mode: String, onToggle: () -> Unit) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(UfiCardDefaults.pillShape)
                .background(palette.pageBg)
                .padding(2.dp)
        ) {
            listOf("month" to "月历", "week" to "周条").forEach { (value, label) ->
                val on = mode == value
                Box(
                    modifier = Modifier
                        .clip(UfiCardDefaults.pillShape)
                        .background(if (on) palette.cardBg else Color.Transparent)
                        .clickable { if (!on) onToggle() }
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = label,
                        style = UfiTextStyles.captionStrong,
                        color = if (on) palette.textPrimary else palette.textSecondary
                    )
                }
            }
        }
    }
}

/**
 * 周条：头部一行「日期范围 + ‹ ›」，下方 7 个等宽格子。
 *
 * ‹ › 不与 7 个格子同行排列：置于格子两端会把格宽压到 40dp 以下，文字拥挤；
 * 导航与内容应分开排版。
 */
@Composable
private fun AlertWeekStrip(
    anchorMs: Long,
    dayCounts: Map<String, Int>,
    selectedKey: String?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSelectDay: (String) -> Unit
) {
    val palette = LocalResolvedPalette.current
    val start = remember(anchorMs) { weekStartMs(anchorMs) }
    val todayKey = localDayKey(System.currentTimeMillis())
    val keys = remember(start) { (0..6).map { localDayKey(start + it * 86_400_000L) } }
    val maxCount = (keys.mapNotNull { dayCounts[it] }.maxOrNull() ?: 0).coerceAtLeast(1)
    val weekTotal = keys.sumOf { dayCounts[it] ?: 0 }
    val rangeText = runCatching {
        val fmt = SimpleDateFormat("M月d日", Locale.getDefault())
        "${fmt.format(Date(start))} – ${fmt.format(Date(start + 6 * 86_400_000L))}"
    }.getOrDefault("本周")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(UfiCardDefaults.shape)
            .background(palette.cardBg)
            .border(1.dp, palette.divider, UfiCardDefaults.shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(rangeText, style = UfiTextStyles.sectionTitle, color = palette.textPrimary)
            Spacer(Modifier.width(Spacing.Medium))
            Text("$weekTotal 条", style = UfiTextStyles.caption, color = palette.textSecondary)
            Spacer(Modifier.weight(1f))
            CalendarNavButton("‹", onPrev)
            Spacer(Modifier.width(6.dp))
            CalendarNavButton(
                "›",
                onNext,
                enabled = shiftDays(weekStartMs(anchorMs), 7) <= System.currentTimeMillis()
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            keys.forEachIndexed { index, key ->
                val count = dayCounts[key] ?: 0
                val isFuture = key > todayKey
                val selected = !isFuture && key == selectedKey
                val t = if (count == 0) 0f else (count.toFloat() / maxCount) * 0.28f
                val cellBg = when {
                    selected -> palette.accent
                    count == 0 -> palette.pageBg
                    else -> lerp(palette.cardBg, palette.accent, t)
                }
                val contentColor = when {
                    isFuture -> palette.textSecondary.copy(alpha = 0.35f)
                    selected -> palette.onAccent
                    t > 0.2f -> palette.onAccent
                    else -> palette.textPrimary
                }
                val subColor = when {
                    isFuture -> palette.textSecondary.copy(alpha = 0.3f)
                    selected -> palette.onAccent.copy(alpha = 0.85f)
                    else -> palette.textSecondary
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                        .clip(UfiCardDefaults.smallShape)
                        .background(cellBg)
                        .clickable(enabled = !isFuture) { onSelectDay(key) }
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = listOf("一", "二", "三", "四", "五", "六", "日")[index],
                        style = MaterialTheme.typography.labelSmall,
                        color = subColor
                    )
                    Text(
                        text = key.takeLast(2),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = contentColor
                    )
                    Text(
                        text = if (count > 0) "$count" else if (key == todayKey) "·" else " ",
                        style = UfiTextStyles.captionTiny,
                        color = subColor
                    )
                }
            }
        }
    }
}
/** 事件中心右上角的工具图标（筛选）：激活时实底 + 数字角标。 */
@Composable
private fun EventToolIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    badge: Int,
    onClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(UfiCardDefaults.smallShape)
            .background(if (active) palette.accent else palette.pageBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (active) palette.onAccent else palette.textSecondary,
            modifier = Modifier.size(18.dp)
        )
        if (badge > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(palette.error),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (badge > 9) "9" else badge.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = Color.White
                )
            }
        }
    }
}
