package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.DownsampledPoint
import com.ufi_axis.data.monitor.CsvExporter
import com.ufi_axis.data.monitor.MonitorMetricType
import com.ufi_axis.data.monitor.MonitorSettings
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.MonitorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 监控设置页（v2：入口 + 二级设置界面组合，参考网络/主设置页模式）。
 *
 * - 主设置页 = 6 个入口卡片（数据采集 / 单指标采集 / 图表显示 / 默认行为 / 采集调度 / 存储管理），
 *   每项 = 图标 + 标题 + 副标 + 右箭头，点击进入对应二级设置页（独立路由）。
 * - 二级页 = 原平铺的各组设置内容各居一页（即改即存，无保存按钮）。
 * - 存储管理（P7b）：进入存储管理二级页经 `LaunchedEffect(Unit)` 首载一次存储统计
 *   （主页已删该卡 → 此处是 loadMonitorStorage 唯一入口）。
 * - 布局：UfiScreenScaffold + UfiPageBackground（自带 verticalScroll）+
 *   UfiSettingsGroup 分组卡片；chip 行采用全宽布局（详见 [MonitorSettingsChipRow]）。
 *
 * @param viewModel 全局 ViewModel（[MainViewModel.dashboard] 承载监控设置读写）。
 * @param navController 导航控制器（供返回）。
 * @param showBack 是否显示返回键，默认 true。
 */

/** 监控设置二级分组枚举：标题 + 图标 + 入口副标 + 对应的独立路由 */
enum class MonitorSettingsGroup(
    val title: String,
    val icon: ImageVector,
    val subtitle: String,
    val route: String
) {
    DATA_COLLECTION("数据采集", Icons.Default.Tune, "总开关 · 默认时间范围 · 自动刷新", Routes.DETAIL_MONITOR_COLLECTION),
    METRIC_TYPES("单指标采集", Icons.Default.List, "CPU / 内存 / 流量 / 信号 / 电池 / 温度", Routes.DETAIL_MONITOR_METRICS),
    CHART_DISPLAY("图表显示", Icons.Default.BarChart, "Y 轴固定 · 填充透明度", Routes.DETAIL_MONITOR_CHART),
    DEFAULT_BEHAVIOR("默认行为", Icons.Default.Settings, "导出 ZIP", Routes.DETAIL_MONITOR_BEHAVIOR),
    // 排在存储管理之前：保留天数决定"数据库能长多大"，先让用户看到调度参数，再看清理动作
    SCHEDULER("采集调度", Icons.Default.Schedule, "保留天数 · 刷写/扫描间隔 · 温控档位", Routes.DETAIL_MONITOR_SCHEDULER),
    STORAGE("存储管理", Icons.Default.Storage, "历史表清理 · 全部导出 CSV", Routes.DETAIL_MONITOR_STORAGE)
}

/**
 * 监控设置入口页：6 张入口卡，点击 navigate 到独立的二级路由。
 *
 * 2026-09-03：二级页从"同一页内的 `activeGroup` 局部状态 + AnimatedContent"改成**独立路由**，
 * 与「设置 → 服务器 → 服务器配置」完全同构（见 `ServerScreen.kt`）：
 * 转场、返回键、系统返回手势、进程恢复全部由导航栈接管，页面自己不再管层级。
 * 局部状态那套的问题不只是动画：它不在返回栈里，得靠 BackHandler 补，标题/返回键还要自己分叉。
 */
@Composable
fun MonitorSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    showBack: Boolean = true
) {
    UfiScreenScaffold(
        title = "监控设置",
        navController = navController,
        showBack = showBack
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MonitorSettingsGroup.entries.forEach { group ->
                    MonitorSettingsEntryCard(
                        group = group,
                        onClick = { navController.navigate(group.route) }
                    )
                }
            }
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

/** 二级页：数据采集 */
@Composable
fun MonitorCollectionSettingsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val monitorState by viewModel.monitorState.collectAsState()
    val settings = monitorState.settings
    UfiScreenScaffold(
        title = MonitorSettingsGroup.DATA_COLLECTION.title,
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            DataCollectionSettingsGroup(
                settings = settings,
                onChange = { v -> viewModel.dashboard.setMonitorSettings(settings.copy(collectEnabled = v)) },
                onDefaultHours = { v -> viewModel.dashboard.setMonitorSettings(settings.copy(defaultHours = v.toInt())) },
                onRefreshInterval = { v -> viewModel.dashboard.setMonitorSettings(settings.copy(refreshIntervalSec = v.toInt())) }
            )
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

/** 二级页：单指标采集 */
@Composable
fun MonitorMetricsSettingsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val monitorState by viewModel.monitorState.collectAsState()
    val settings = monitorState.settings
    UfiScreenScaffold(
        title = MonitorSettingsGroup.METRIC_TYPES.title,
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            MetricTypesSettingsGroup(
                settings = settings,
                onToggleType = { apiKey, enabled ->
                    val next = if (enabled) settings.enabledTypes + apiKey else settings.enabledTypes - apiKey
                    viewModel.dashboard.setMonitorSettings(settings.copy(enabledTypes = next))
                }
            )
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

/** 二级页：图表显示 */
@Composable
fun MonitorChartSettingsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val monitorState by viewModel.monitorState.collectAsState()
    val settings = monitorState.settings
    UfiScreenScaffold(
        title = MonitorSettingsGroup.CHART_DISPLAY.title,
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            ChartDisplaySettingsGroup(
                settings = settings,
                onFixedYAxis = { v -> viewModel.dashboard.setMonitorSettings(settings.copy(fixedYAxis = v)) },
                onFillAlpha = { v ->
                    viewModel.dashboard.setMonitorSettings(
                        settings.copy(fillAlpha = (v * 100).roundToInt() / 100f)
                    )
                }
            )
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

/** 二级页：默认行为 */
@Composable
fun MonitorBehaviorSettingsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val monitorState by viewModel.monitorState.collectAsState()
    val settings = monitorState.settings
    UfiScreenScaffold(
        title = MonitorSettingsGroup.DEFAULT_BEHAVIOR.title,
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            DefaultBehaviorSettingsGroup(
                settings = settings,
                onExportZip = { v -> viewModel.dashboard.setMonitorSettings(settings.copy(exportZip = v)) }
            )
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

/**
 * 二级页：采集调度。
 *
 * 这 7 项与前四页的"展示偏好"不同：它们直接改 core 的采集/告警/温控循环参数
 * （真源 `AppSettings`，`DataScheduler` 在循环体内读，改完下一轮生效、不用重启采集）。
 * 值域由 core 的 PUT 兜底校验，越界返回 400，所以温控两档在本页就先把序关系纠正好。
 */
@Composable
fun MonitorSchedulerSettingsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val monitorState by viewModel.monitorState.collectAsState()
    val settings = monitorState.settings
    UfiScreenScaffold(
        title = MonitorSettingsGroup.SCHEDULER.title,
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            SchedulerSettingsGroup(
                settings = settings,
                onCommit = { next -> viewModel.dashboard.setMonitorSettings(next) }
            )
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

/**
 * 二级页：存储管理。
 *
 * 只有这一页带自己的状态（清理天数选择 / 清理确认弹窗 / toast / 全量导出），所以单独写全，
 * 其余五页都只是"脚手架 + 一个分组"。
 */
@Composable
fun MonitorStorageSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val monitorState by viewModel.monitorState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 整卡清理确认对话框开关（存储管理组「清理 N 天前数据」） ──
    var cleanConfirmVisible by remember { mutableStateOf(false) }

    // ── 单表清理确认对话框（存储管理组每行「清理」→ 记录待清理表名，null=未打开） ──
    var cleanTableName by remember { mutableStateOf<String?>(null) }

    // ── 手动清理的天数（1/3/7/30 四档） ──
    // 初值取「采集调度 → 保留天数」里最接近的一档：手动清理的意义是"提前触发自动清理"，
    // 默认对齐自动策略，用户才不会像以前那样（自动保留 3 天、手动写死 7 天）点了按钮删不掉任何东西。
    var cleanDays by rememberSaveable {
        mutableStateOf(nearestCleanDays(monitorState.settings.retentionDays))
    }

    // ── 全局 Toast 反馈（UfiToastHost；导出 onResult 已 withContext(Main)，赋 state 安全） ──
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    // 2026-09-03：存储清理/导出的结果消息统一走本页已有的 toast 通道。
    // 原来存储管理组里另开了一条「UfiSettingsGroup + Row + TextButton(知道了)」手搓横幅，
    // 同一个页面两套反馈 UI，样式还和公共组件对不上。
    val cleanMessage = monitorState.cleanMessage
    LaunchedEffect(cleanMessage) {
        if (!cleanMessage.isNullOrBlank()) {
            toastMessage = ToastMessage(
                cleanMessage,
                if (cleanMessage.contains("失败")) ToastType.ERROR else ToastType.SUCCESS
            )
            viewModel.dashboard.clearMonitorMessage()
        }
    }

    /** 全量导出 8 类指标 CSV（P7b）：构造 8 个 [ExportFile] 交给共享工具 [MonitorExport] 落盘
     *  （专属子目录 Downloads/UFI/Monitor/{date}，zip 偏好由 settings.exportZip 决定）。 */
    fun exportAllCsv() {
        val state = monitorState
        val range = state.selectedRange
        val startMs = range.queryStartMs
        val endMs = range.queryEndMs
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault())
        val files = MonitorMetricType.entries.map { metric ->
            ExportFile(
                fileName = "UFI_Monitor_${metric.apiKey}_${stamp.format(Date(startMs))}_${stamp.format(Date(endMs))}.csv",
                content = CsvExporter.export(metric, historyFor(state, metric), startMs, endMs)
            )
        }
        val zip = state.settings.exportZip
        scope.launch(Dispatchers.IO) {
            MonitorExport.exportCsvFiles(
                context = context,
                files = files,
                zip = zip,
                onResult = { msg ->
                    withContext(Dispatchers.Main) {
                        toastMessage = ToastMessage(
                            msg,
                            if (msg.contains("失败")) ToastType.ERROR else ToastType.SUCCESS
                        )
                    }
                }
            )
        }
    }

    UfiScreenScaffold(
        title = MonitorSettingsGroup.STORAGE.title,
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            StorageManagementSettingsGroup(
                monitorState = monitorState,
                cleanDays = cleanDays,
                onCleanDaysChange = { cleanDays = it },
                onLoadStorage = { viewModel.dashboard.loadMonitorStorage() },
                onCleanTable = { name -> cleanTableName = name },
                onCleanAll = { cleanConfirmVisible = true },
                onExportAll = { exportAllCsv() }
            )
            Spacer(Modifier.height(Spacing.Large))
        }
        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }

    // ── 清理 N 天前数据确认对话框（存储管理组；N = 用户选的档位，不再写死 7） ──
    if (cleanConfirmVisible) {
        UfiConfirmDialog(
            title = "清理历史数据",
            text = "将删除各历史表 $cleanDays 天前的记录，不可恢复",
            confirmText = "确认清理",
            destructive = true,
            onConfirm = {
                cleanConfirmVisible = false
                viewModel.dashboard.cleanHistory(days = cleanDays)
            },
            onDismiss = { cleanConfirmVisible = false }
        )
    }

    // ── 单表清理确认对话框（存储管理组每行；沿用同一个天数档位，避免同页两套口径） ──
    cleanTableName?.let { tableName ->
        UfiConfirmDialog(
            title = "清理历史数据",
            text = "将删除「${storageTableLabel(tableName)}」$cleanDays 天前的记录，不可恢复",
            confirmText = "确认清理",
            destructive = true,
            onConfirm = {
                viewModel.dashboard.cleanHistory(type = tableName, days = cleanDays)
                cleanTableName = null
            },
            onDismiss = { cleanTableName = null }
        )
    }
}

// ==================== 入口卡片 ====================

/**
 * 监控设置入口卡（对齐主设置页 SettingsScreen 模式）：
 * 图标(24dp primary) + 标题/副标 + 右箭头，点击进入二级页。
 */
@Composable
private fun MonitorSettingsEntryCard(
    group: MonitorSettingsGroup,
    onClick: () -> Unit
) {
    // 2026-08-30：容器 + 行都改用公共组件（原来手搓 rowModifier 四链 + Row/Icon/Column/Text）。
    // 副标题原先 maxLines=1 + Ellipsis，现由 UfiSettingsItem 统一渲染（长文案折行而非截断）。
    UfiSettingsRowCard {
        UfiSettingsItem(
            icon = group.icon,
            title = group.title,
            description = group.subtitle,
            onClick = onClick,
            trailing = { UfiSettingsChevron() }
        )
    }
}

// ==================== 二级设置页 ====================

/** ① 数据采集：总开关 + 默认时间范围 + 自动刷新间隔 */
@Composable
private fun DataCollectionSettingsGroup(
    settings: MonitorSettings,
    onChange: (Boolean) -> Unit,
    onDefaultHours: (String) -> Unit,
    onRefreshInterval: (String) -> Unit
) {
    UfiSettingsGroup {
        UfiGroupHeader("数据采集")

        UfiSettingsItem(
            title = "监控总开关",
            description = "停用后停止采集与刷新",
            trailing = {
                UfiSwitch(checked = settings.collectEnabled, onCheckedChange = onChange)
            }
        )

        MonitorSettingsChipRow(
            title = "默认时间范围",
            subtitle = "进入监控页默认显示",
            options = listOf("1" to "1小时", "6" to "6小时", "24" to "24小时", "168" to "7天"),
            selectedValue = settings.defaultHours.toString(),
            onSelect = onDefaultHours
        )

        MonitorSettingsChipRow(
            title = "自动刷新间隔",
            subtitle = null,
            options = listOf("10" to "10秒", "30" to "30秒", "60" to "1分", "300" to "5分"),
            selectedValue = settings.refreshIntervalSec.toString(),
            onSelect = onRefreshInterval
        )
    }
}

/** ② 单指标采集：8 类指标开关 */
@Composable
private fun MetricTypesSettingsGroup(
    settings: MonitorSettings,
    onToggleType: (apiKey: String, enabled: Boolean) -> Unit
) {
    UfiSettingsGroup {
        UfiGroupHeader("单指标采集")

        MonitorMetricType.entries.forEach { type ->
            UfiSettingsItem(
                title = type.label,
                description = "单位：${type.unit}",
                trailing = {
                    UfiSwitch(
                        checked = type.apiKey in settings.enabledTypes,
                        onCheckedChange = { enabled -> onToggleType(type.apiKey, enabled) }
                    )
                }
            )
        }
    }
}

/** ③ 图表显示：Y 轴固定 + 填充透明度 */
@Composable
private fun ChartDisplaySettingsGroup(
    settings: MonitorSettings,
    onFixedYAxis: (Boolean) -> Unit,
    onFillAlpha: (Float) -> Unit
) {
    UfiSettingsGroup {
        UfiGroupHeader("图表显示")

        UfiSettingsItem(
            title = "Y 轴固定",
            description = "固定坐标范围（需设置范围值，图表应用在后续轮）",
            trailing = {
                UfiSwitch(checked = settings.fixedYAxis, onCheckedChange = onFixedYAxis)
            }
        )

        UfiSettingsItem(
            title = "填充透明度",
            description = "折线下方填充色的不透明度"
        )
        // 2026-09-03：原来把 UfiSlider 塞进 UfiSettingsItem 的 trailing 槽并硬编码 Box(width=160.dp)，
        // 既挤标题列又没有数值显示。改为公共组件的标准用法：标题行在上，slider 全宽在下，
        // 用 UfiSlider 自带的 valueLabel 显示当前值（对照 TunnelSettingsScreen / DownloadSettingsUi）。
        UfiSlider(
            value = settings.fillAlpha,
            onValueChange = onFillAlpha,
            valueRange = 0.1f..1f,
            valueLabel = "${(settings.fillAlpha * 100).roundToInt()}%"
        )
    }
}

/** ④ 默认行为：导出 ZIP */
@Composable
private fun DefaultBehaviorSettingsGroup(
    settings: MonitorSettings,
    onExportZip: (Boolean) -> Unit
) {
    UfiSettingsGroup {
        UfiGroupHeader("默认行为")

        UfiSettingsItem(
            title = "导出打包为 ZIP",
            description = "导出时压缩为单个 zip 文件",
            trailing = {
                UfiSwitch(checked = settings.exportZip, onCheckedChange = onExportZip)
            }
        )
    }
}

/**
 * ⑥ 采集调度：保留天数 + 三个间隔 + 温控三项。
 *
 * 每项都是"省电/省空间"与"数据完整性"的取舍，所以说明文案写代价而不是写字段名。
 * 提交时机是**松手**（`onValueChangeFinished`）而不是拖动中的每一帧：
 * [MainViewModel.dashboard] 的写回会落盘 + PUT，按帧提交等于一次拖动打几十个请求。
 */
@Composable
private fun SchedulerSettingsGroup(
    settings: MonitorSettings,
    onCommit: (MonitorSettings) -> Unit
) {
    UfiSettingsGroup {
        UfiGroupHeader("采集调度")

        SchedulerIntSlider(
            title = "历史保留天数",
            description = "自动清理只保留最近 N 天：天数越大曲线能回看得越久，数据库也越大（占用与天数近似成正比）",
            value = settings.retentionDays,
            valueRange = 1f..90f,
            unit = "天",
            onCommit = { v -> onCommit(settings.copy(retentionDays = v)) }
        )

        UfiDivider()

        SchedulerIntSlider(
            title = "缓冲刷写间隔",
            description = "采集结果先攒在内存再批量落库：间隔越大写入越少、越省闪存寿命，但进程被杀或断电时丢的就是这一段",
            value = settings.flushIntervalSec,
            valueRange = 5f..300f,
            unit = "秒",
            onCommit = { v -> onCommit(settings.copy(flushIntervalSec = v)) }
        )

        UfiDivider()

        SchedulerIntSlider(
            title = "告警扫描间隔",
            description = "本地阈值告警的检查周期：间隔越小告警越及时，代价是后台被唤醒得更频繁",
            value = settings.alertScanSec,
            valueRange = 5f..300f,
            unit = "秒",
            onCommit = { v -> onCommit(settings.copy(alertScanSec = v)) }
        )

        UfiDivider()

        SchedulerIntSlider(
            title = "空闲采集间隔",
            description = "仅在没有前端连接时生效（有 app/网页在看时，采集频率由 QoS 自适应决定）：间隔越大越省电，历史曲线也越粗",
            value = settings.idleIntervalSec,
            valueRange = 10f..600f,
            unit = "秒",
            onCommit = { v -> onCommit(settings.copy(idleIntervalSec = v)) }
        )

        UfiDivider()

        SchedulerIntSlider(
            title = "温控预警阈值",
            description = "机身温度到这个值就降频采集（拉长间隔）以减少发热",
            value = settings.thermalWarnC,
            valueRange = 50f..90f,
            unit = "℃",
            onCommit = { v -> onCommit(settings.copy(thermalWarnC = v).withThermalOrder(warnMoved = true)) }
        )

        UfiDivider()

        SchedulerIntSlider(
            title = "温控熔断阈值",
            description = "到这个温度直接暂停采集，必须高于预警阈值",
            value = settings.thermalCriticalC,
            valueRange = 55f..100f,
            unit = "℃",
            onCommit = { v -> onCommit(settings.copy(thermalCriticalC = v).withThermalOrder(warnMoved = false)) }
        )

        UfiDivider()

        SchedulerIntSlider(
            title = "熔断暂停时长",
            description = "熔断后停止采集多久再重试：越长越有利于降温，期间没有任何监控数据",
            value = settings.thermalPauseSec,
            valueRange = 5f..300f,
            unit = "秒",
            onCommit = { v -> onCommit(settings.copy(thermalPauseSec = v)) }
        )
    }
}

/**
 * 保证"熔断 > 预警"，把被动的那一档顶开一度。
 *
 * core 的 PUT 会校验这个序关系并对越界返回 400，若放任用户把预警拖到熔断之上，
 * 结果是本地已改、下发被拒、下次回读又跳回去 —— 用户只会看到"设置自己变回来了"。
 *
 * @param warnMoved true=用户刚动的是预警（顶熔断），false=用户刚动的是熔断（压预警）
 */
private fun MonitorSettings.withThermalOrder(warnMoved: Boolean): MonitorSettings {
    if (thermalCriticalC > thermalWarnC) return this
    return if (warnMoved) {
        copy(thermalCriticalC = (thermalWarnC + 1).coerceIn(55, 100))
    } else {
        copy(thermalWarnC = (thermalCriticalC - 1).coerceIn(50, 90))
    }
}

/**
 * 「标题 + 说明 + 全宽整数 slider」行（采集调度专用）。
 *
 * 拖动中只更新本地 [draft]，松手才 [onCommit]；[draft] 以传入的 [value] 为 key 重建，
 * 于是 core 回显/回读把值改了之后 UI 会跟着回正，而不是卡在用户上次拖到的位置。
 */
@Composable
private fun SchedulerIntSlider(
    title: String,
    description: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String,
    onCommit: (Int) -> Unit
) {
    var draft by remember(value) { mutableStateOf(value.toFloat()) }
    UfiSettingsItem(title = title, description = description)
    UfiSlider(
        value = draft,
        onValueChange = { draft = it },
        valueRange = valueRange,
        valueLabel = "${draft.roundToInt()} $unit",
        onValueChangeFinished = { onCommit(draft.roundToInt()) }
    )
}

/** ⑤ 存储管理：逐表清理 / 合计 / 最大表占比 / 整卡清理 / 全部导出（进入时首载统计）。
 *  操作结果消息由调用方转投页面级 toast，本组件不再自带横幅。 */
@Composable
private fun StorageManagementSettingsGroup(
    monitorState: MonitorState,
    cleanDays: Int,
    onCleanDaysChange: (Int) -> Unit,
    onLoadStorage: () -> Unit,
    onCleanTable: (String) -> Unit,
    onCleanAll: () -> Unit,
    onExportAll: () -> Unit
) {
    val palette = LocalResolvedPalette.current

    // 进入存储管理页加载一次（loadMonitorStorage 唯一入口）
    LaunchedEffect(Unit) { onLoadStorage() }

    UfiSettingsGroup {
        UfiGroupHeader("存储管理")

        val storageInfo = monitorState.storageInfo
        if (storageInfo != null) {
            // 2026-09-03：行尾按钮由原生 TextButton 换成公共小按钮（size = Small，与内网穿透/下载设置一致）；
            // 表与表之间补 UfiDivider；进度条/字号/空态全部换成公共组件，不再走 M3 默认配色。
            storageInfo.tables.forEachIndexed { index, table ->
                if (index > 0) UfiDivider()
                UfiSettingsItem(
                    title = storageTableLabel(table.name),
                    description = "${table.count} 条 · ${formatStorageKb(table.size_kb)}",
                    trailing = {
                        UfiButton(size = UfiButtonSize.Small, text = "清理", onClick = { onCleanTable(table.name) })
                    }
                )
            }
            UfiDivider()
            // 合计：纯展示型键值行用 UfiInfoRow，而不是拿 UfiSettingsItem 的 title/description 硬凑
            UfiInfoRow(label = "合计", value = storageInfo.total_display)
            // 最大表占比进度条
            val totalKb = storageInfo.total_kb
            val maxKb = storageInfo.tables.maxOfOrNull { it.size_kb } ?: 0.0
            val maxRatio = if (totalKb > 0) (maxKb / totalKb).toFloat().coerceIn(0f, 1f) else 0f
            UfiCompactProgressBar(
                progress = maxRatio,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = Spacing.Small)
            )
            Text(
                text = "最大表占比 ${(maxRatio * 100).toInt()}%",
                style = UfiTextStyles.note,
                color = palette.textSecondary
            )
        } else {
            UfiEmptyState(
                icon = Icons.Default.Storage,
                message = "暂无存储统计",
                hint = "统计在进入本页时自动读取，可稍后重试"
            )
        }

        UfiDivider()

        // 自动清理的口径说明：手动清理只是"提前触发"，真正长期决定 DB 大小的是保留天数。
        // 这行必须留着 —— 之前自动保留 3 天、手动写死 7 天，用户点了清理按钮基本删不到任何记录。
        UfiSettingsItem(
            title = "自动清理策略",
            description = "自动清理按「采集调度 → 保留天数」执行（当前 ${monitorState.settings.retentionDays} 天）；下面的手动清理只是提前触发一次"
        )

        UfiDivider()

        // 手动清理：天数可选（chip 行不能塞进 UfiSettingsItem 的 trailing，理由见 MonitorSettingsChipRow）
        MonitorSettingsChipRow(
            title = "手动清理天数",
            subtitle = "清理动作删除「早于所选天数」的记录，逐表清理也用这个档位",
            options = listOf("1" to "1天", "3" to "3天", "7" to "7天", "30" to "30天"),
            selectedValue = cleanDays.toString(),
            onSelect = { v -> v.toIntOrNull()?.let(onCleanDaysChange) }
        )

        UfiSettingsItem(
            title = "清理 $cleanDays 天前数据",
            description = "删除各历史表 $cleanDays 天前记录",
            trailing = {
                UfiButton(size = UfiButtonSize.Small, text = "清理", onClick = onCleanAll)
            }
        )

        UfiDivider()

        // 全部导出 CSV
        UfiSettingsItem(
            title = "全部导出 CSV",
            description = "导出到 Downloads/UFI/Monitor/yyyy-MM-dd/",
            trailing = {
                UfiButton(size = UfiButtonSize.Small, text = "导出", onClick = onExportAll)
            }
        )
    }
}

/**
 * 设置分组内的「标题 + 说明 + 全宽 chip 选择器」行。
 *
 * 不用 [UfiSettingsItem] 的 trailing 槽放 [UfiSingleChipSelector]：
 * chip 内部 Row 带 fillMaxWidth 且每个 chip 是 weight(1f)，放进 trailing 的
 * 非权重 Box 会占满整行，标题列被挤成 0 宽；改为全宽堆叠（与 AppearanceSettingsScreen
 * 的 chip 用法一致），标题/说明样式与 [UfiSettingsItem] 完全对齐。
 */
@Composable
private fun MonitorSettingsChipRow(
    title: String,
    subtitle: String?,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit
) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Text(
            text = title,
            style = UfiTextStyles.listItemTitle,
            color = palette.textPrimary,
            maxLines = 2
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = UfiTextStyles.note,
                color = palette.textSecondary,
                maxLines = 2,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.height(Spacing.Medium))
        UfiSingleChipSelector(
            options = options,
            selectedValue = selectedValue,
            onSelect = onSelect
        )
    }
}

// ==================== 存储管理私有辅助（P7b，自主页 P5e 迁入；两文件各自实现，避免跨文件共享私有） ====================

/** 从 8 类 history 中按 apiKey 取对应序列（与 MonitorScreen/DetailScreen.historyFor 逻辑一致；全部导出复用） */
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

/** 存储表名 → 可读化中文名；未映射的表显示原名（P4b，迁入本页） */
private val storageTableLabels: Map<String, String> = mapOf(
    "cpu_history" to "CPU历史",
    "memory_history" to "内存历史",
    "traffic_records" to "流量记录",
    "signal_history" to "信号历史",
    "battery_history" to "电池历史",
    "alert_records" to "告警记录",
    "sms_records" to "短信"
)

private fun storageTableLabel(name: String): String = storageTableLabels[name] ?: name

/** 手动清理的四个档位；把 core 的保留天数（1..90 任意值）映射到最接近的一档做初值 */
private val cleanDayOptions = listOf(1, 3, 7, 30)

private fun nearestCleanDays(retentionDays: Int): Int =
    cleanDayOptions.minByOrNull { kotlin.math.abs(it - retentionDays) } ?: 7

/** 存储大小可读化：≥1MB 显示 MB，否则 KB（P4b，迁入本页） */
private fun formatStorageKb(kb: Double): String = when {
    kb >= 1024 -> "%.1f MB".format(kb / 1024)
    else -> "%.0f KB".format(kb)
}
