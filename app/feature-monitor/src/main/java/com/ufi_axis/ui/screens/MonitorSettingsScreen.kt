package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
 * - 主设置页 = 入口卡片（数据采集 / 图表显示 / 采集调度 / 存储管理），每项 = 图标 + 标题 +
 *   副标 + 右箭头，点击进入对应二级设置页（独立路由）。
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
    DATA_COLLECTION(
        "数据采集",
        Icons.Default.Tune,
        "总开关 · 采集指标 · 默认时间范围 · 自动刷新",
        Routes.DETAIL_MONITOR_COLLECTION
    ),
    // 「告警设置」已于 2026-09-10 迁回「设置 → 通知与守护」，本页不再提供入口。
    // 迁走的理由是用户动线：调完通知总闸 / 分类 / 渠道之后，还要跳到监控设置这一级
    // 才能决定"设备检测哪几类、阈值多少"，中间隔着两级导航。
    // 同一个目标不留两个入口 —— 那只会让人问"这两个有什么区别"。
    // （监控中心首页在引擎关闭时仍有一张 `AlertEngineOffCard` 直达那一页，
    //   那是"引擎没开"时的修复入口，不是并列的设置入口。）
    // 「单指标采集」（8 类指标开关）已于 2026-09-08 并入「数据采集」页：
    // 那一页整页只有 8 个开关，而它回答的问题（"采哪些指标"）与总开关是同一件事。
    // 现在是数据采集页里的一行，点开弹窗多选。
    CHART_DISPLAY("图表显示", Icons.Default.BarChart, "Y 轴固定 · 填充透明度", Routes.DETAIL_MONITOR_CHART),
    // 排在存储管理之前：保留天数决定"数据库能长多大"，先让用户看到调度参数，再看清理动作
    SCHEDULER("采集调度", Icons.Default.Schedule, "保留天数 · 刷写/扫描间隔 · 温控档位", Routes.DETAIL_MONITOR_SCHEDULER),
    // 「默认行为」（只有一个"导出打包为 ZIP"开关）已于 2026-09-08 并入本页：
    // 单开关不值得一个二级页，而它描述的正是本页导出动作的行为。
    STORAGE("存储管理", Icons.Default.Storage, "历史表清理 · 导出 CSV", Routes.DETAIL_MONITOR_STORAGE)
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
                onRefreshInterval = { v -> viewModel.dashboard.setMonitorSettings(settings.copy(refreshIntervalSec = v.toInt())) },
                onEnabledTypes = { next -> viewModel.dashboard.setMonitorSettings(settings.copy(enabledTypes = next)) }
            )
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

// 「单指标采集」二级页已于 2026-09-08 删除，8 个指标开关并入 [DataCollectionSettingsGroup]
// 的「采集指标」一行（点开 [MetricTypesDialog] 多选）。理由见 MonitorSettingsGroup 的注释。

// 「默认行为」二级页同日删除，唯一的「导出打包为 ZIP」开关并入
// [StorageManagementSettingsGroup]（那里才是真正会用到它的地方）。

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

/** 二级页：默认行为 —— 已于 2026-09-08 合并进「存储管理」，本函数随之删除。 */

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
 * 只有这一页带自己的状态（toast / 全量导出），所以单独写全，其余五页都只是"脚手架 + 一个分组"。
 * 清理相关的状态 2026-09-08 全部下沉到 [StorageCleanDialog] 内部，本页不再持有。
 */
@Composable
fun MonitorStorageSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val monitorState by viewModel.monitorState.collectAsState()
    val scope = rememberCoroutineScope()

    // 清理的范围 / 天数 / 确认全部收进 StorageCleanDialog（2026-09-08 第五轮），
    // 本页不再持有 cleanScope / cleanDays / cleanConfirmVisible / cleanTableName 四个状态，
    // 两个 UfiConfirmDialog 也随之删除 —— 弹窗自己就是确认面，理由见该函数 KDoc。

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
     *  （专属子目录 Download/UFI-AXIS/export/monitor/{date}，zip 偏好由 settings.exportZip 决定）。 */
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
                onLoadStorage = { viewModel.dashboard.loadMonitorStorage() },
                onClean = { scopes, days ->
                    // 全选短路成一个 type="all" 请求；否则按类顺序清（见 cleanHistoryBatch 的 KDoc）
                    if (scopes.size == cleanScopeOptions.size) {
                        viewModel.dashboard.cleanHistory(type = CLEAN_SCOPE_ALL, days = days)
                    } else {
                        viewModel.dashboard.cleanHistoryBatch(types = scopes.toList(), days = days)
                    }
                },
                onExportAll = { exportAllCsv() },
                onExportZip = { v ->
                    viewModel.dashboard.setMonitorSettings(monitorState.settings.copy(exportZip = v))
                }
            )
            Spacer(Modifier.height(Spacing.Large))
        }
        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
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

/** ① 数据采集：总开关 + 采集指标 + 默认时间范围 + 自动刷新间隔 */
@Composable
private fun DataCollectionSettingsGroup(
    settings: MonitorSettings,
    onChange: (Boolean) -> Unit,
    onDefaultHours: (String) -> Unit,
    onRefreshInterval: (String) -> Unit,
    onEnabledTypes: (Set<String>) -> Unit
) {
    var metricsDialogOpen by remember { mutableStateOf(false) }

    UfiSettingsGroup {
        UfiGroupHeader("数据采集")

        UfiSettingsItem(
            title = "监控总开关",
            description = "停用后停止采集与刷新",
            trailing = {
                UfiSwitch(checked = settings.collectEnabled, onCheckedChange = onChange)
            }
        )

        // 采集指标（2026-09-08 从独立二级页并入）：整页 8 个开关不值得一级入口，
        // 而"采哪些指标"与上面的总开关是同一件事 —— 一个管全停、一个管挑哪些。
        // 用弹窗多选而不是在这里铺 8 行：铺开会把这一页从 3 项变成 11 项，
        // 而这 8 项一次配好基本不再动。
        UfiSettingsValue(
            title = "采集指标",
            description = "只采集勾选的指标，未勾选的不保存也不显示",
            value = "${settings.enabledTypes.size}/${MonitorMetricType.entries.size} 项",
            enabled = settings.collectEnabled,
            onClick = { metricsDialogOpen = true }
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

    if (metricsDialogOpen) {
        MetricTypesDialog(
            selected = settings.enabledTypes,
            onDismiss = { metricsDialogOpen = false },
            onSave = { next ->
                onEnabledTypes(next)
                metricsDialogOpen = false
            }
        )
    }
}

/**
 * 采集指标多选弹窗（8 类指标）。
 *
 * 暂存-确认：勾选只进本地 [draft]，点「确认」才写回 —— 逐项即时提交会把一次"重新挑一遍"
 * 打成七八个落盘 + PUT。
 *
 * 允许全不选（等价于把采集停掉），所以不做"至少选一项"的强制校验；
 * 摘要那行会显示 `0/8`，配合总开关就足够表达状态。
 */
@Composable
private fun MetricTypesDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    val palette = LocalResolvedPalette.current
    var draft by remember { mutableStateOf(selected) }

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "采集指标",
        confirmButton = { UfiButton(text = "确认", onClick = { onSave(draft) }) },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss)
        }
    ) {
        UfiDialogBody {
            Text(
                text = "已选 ${draft.size}/${MonitorMetricType.entries.size} 项",
                style = UfiTextStyles.cardTitle,
                color = palette.accent
            )
            Text(
                text = "未勾选的指标不采集、不保存，图表与今日峰值里也不会出现。已有的历史数据不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            UfiMultiChipSelector(
                options = MonitorMetricType.entries.map { it.apiKey to it.label },
                selectedValues = draft,
                onToggle = { key ->
                    draft = if (key in draft) draft - key else draft + key
                }
            )
        }
    }
}

/**
 * ③ 图表显示：Y 轴固定 + 填充透明度。
 *
 * 两项都是**当前生效**的（2026-09-08 核对）：
 * - `fixedYAxis` → `MonitorScreen.fixedYRangeFor(primary)` → `UfiMonitorChart(fixedYRange = ...)`
 * - `fillAlpha` → `UfiChart` 里以乘法作用于曲线下方渐变的起止透明度
 *
 * 原「Y 轴固定」的副标写着"需设置范围值，图表应用在后续轮"，那是 2026 年初还没接线时留下的，
 * 早就不准了。真正的限制是另一件事：`fixedYRangeFor` 只给**百分比类与温度**返回固定量程
 *（CPU / 内存 / 电池 / 温度 → 0..100），流量是 B/s、信号是 dBm，硬给区间会把曲线压平，
 * 所以那两类即使开着这个开关也仍然自适应。副标现在写的是这个。
 */
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
            description = "把纵轴固定为 0-100（CPU / 内存 / 电池 / 温度）；流量与信号没有固定量程，仍自适应",
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

// ④ 「默认行为」组（唯一一项「导出打包为 ZIP」）已于 2026-09-08 删除，
//    开关并入 [StorageManagementSettingsGroup] 的导出区 —— 它描述的正是那个动作的行为。

/**
 * ⑥ 采集调度：7 个 core 侧循环参数。
 *
 * ## 2026-09-08 重做：滑块下沉到弹窗
 * 上一版是「标题 + 两行取舍说明 + 全宽滑块」× 7 直接平铺 —— 一屏七条轨道加七段长文案，
 * 既看不出当前值（要去读滑块右上角的小标签），也没法一眼比较各项设置成什么样了。
 *
 * 现在页面只剩 7 行「名称 + 当前值」，点进弹窗才是滑块：
 * - 行上副标只写**这一项管什么**（一句话），取舍与代价搬进弹窗，不再挤在列表里
 * - 常用档位做成 chip，多数情况一点就好，滑块留给要精调的人
 * - 值在行右侧直接可读，7 项配置状态一屏看完
 *
 * 分了三组且保留分组头 —— 这三个头是有信息量的（「温度保护」解释了那三项为什么存在），
 * 与被删掉的「增强」那种"不属于前一组"式命名不是一回事。
 *
 * 提交时机仍是**确认**而不是拖动中的每一帧：写回会落盘 + PUT，按帧提交等于一次拖动打几十个请求。
 * 值域由 core 的 PUT 兜底校验，越界返回 400，所以温控两档在本页就先把序关系纠正好。
 */
@Composable
private fun SchedulerSettingsGroup(
    settings: MonitorSettings,
    onCommit: (MonitorSettings) -> Unit
) {
    var editing by remember { mutableStateOf<SchedulerField?>(null) }

    UfiSettingsGroup {
        UfiGroupHeader("数据留存")
        SchedulerValueRow(SchedulerField.RETENTION_DAYS, settings) { editing = it }
        UfiDivider()
        SchedulerValueRow(SchedulerField.FLUSH_INTERVAL, settings) { editing = it }
    }

    UfiSettingsGroup {
        UfiGroupHeader("采集与告警节奏")
        SchedulerValueRow(SchedulerField.ALERT_SCAN, settings) { editing = it }
        UfiDivider()
        SchedulerValueRow(SchedulerField.IDLE_INTERVAL, settings) { editing = it }
        UfiDivider()
        SchedulerValueRow(SchedulerField.TRAFFIC_LIMIT_CHECK, settings) { editing = it }
        UfiDivider()
        SchedulerValueRow(SchedulerField.DEVICE_EVENT_CHECK, settings) { editing = it }
    }

    UfiSettingsGroup {
        UfiGroupHeader("温度保护")
        SchedulerValueRow(SchedulerField.THERMAL_WARN, settings) { editing = it }
        UfiDivider()
        SchedulerValueRow(SchedulerField.THERMAL_CRITICAL, settings) { editing = it }
        UfiDivider()
        SchedulerValueRow(SchedulerField.THERMAL_PAUSE, settings) { editing = it }
    }

    editing?.let { field ->
        SchedulerValueDialog(
            field = field,
            current = field.read(settings),
            onDismiss = { editing = null },
            onConfirm = { value ->
                onCommit(field.write(settings, value))
                editing = null
            }
        )
    }
}

/** 采集调度的一行：名称 + 一句话说明 + 右侧当前值，点开弹窗改。 */
@Composable
private fun SchedulerValueRow(
    field: SchedulerField,
    settings: MonitorSettings,
    onEdit: (SchedulerField) -> Unit
) {
    UfiSettingsValue(
        title = field.title,
        description = field.summary,
        value = "${field.read(settings)} ${field.unit}",
        onClick = { onEdit(field) }
    )
}

/**
 * 采集调度的 7 个可调项。把「文案 + 值域 + 常用档位 + 读写位置」放在一处，
 * 页面与弹窗共用同一份定义 —— 拆成七段重复代码时，说明和值域很容易只改一头。
 *
 * @param summary 列表行副标：只说这一项管什么，一句话。
 * @param detail 弹窗里的取舍说明：调大 / 调小各付什么代价。
 * @param presets 常用档位，做成 chip；不含全部合法值，落在档位外时哪个 chip 都不高亮。
 */
private enum class SchedulerField(
    val title: String,
    val summary: String,
    val detail: String,
    val unit: String,
    val range: ClosedFloatingPointRange<Float>,
    val presets: List<Int>
) {
    RETENTION_DAYS(
        title = "历史保留天数",
        summary = "自动清理只保留最近 N 天",
        detail = "天数越大曲线能回看得越久，数据库也越大（占用与天数近似成正比）。" +
            "「存储管理」里的手动清理是提前执行一次清理。",
        unit = "天",
        range = 1f..90f,
        presets = listOf(3, 7, 30, 90)
    ),
    FLUSH_INTERVAL(
        title = "缓冲刷写间隔",
        summary = "采集结果先在内存暂存，再批量保存",
        detail = "间隔越大写入越少、越省闪存寿命；代价是应用被系统结束或设备断电时，尚未保存的这一段数据会丢失。",
        unit = "秒",
        range = 5f..300f,
        presets = listOf(10, 30, 60, 120)
    ),
    ALERT_SCAN(
        title = "告警扫描间隔",
        summary = "本地阈值告警的检查周期",
        detail = "间隔越小告警越及时，代价是后台被唤醒得更频繁、更耗电。",
        unit = "秒",
        range = 5f..300f,
        presets = listOf(10, 30, 60, 120)
    ),
    IDLE_INTERVAL(
        title = "空闲采集间隔",
        summary = "没有 app / 网页在看时的采集频率",
        detail = "有 app 或网页正在查看时，采集频率会自动调整，此项不生效。" +
            "间隔越大越省电，历史曲线也越粗（同一段时间里的点更少）。",
        unit = "秒",
        range = 10f..600f,
        presets = listOf(30, 60, 180, 300)
    ),
    TRAFFIC_LIMIT_CHECK(
        title = "套餐限额检查间隔",
        summary = "多久查一次套餐用量百分比",
        detail = "决定「套餐限额预警」和「到达限额自动关网」的反应速度。" +
            "下限为 60 秒：每次检查都会向设备发起一次用量查询，" +
            "与设备自带的管理网页共用同一个登录会话，间隔过短会导致两边频繁掉线。",
        unit = "秒",
        range = 60f..3600f,
        presets = listOf(60, 300, 900, 1800)
    ),
    DEVICE_EVENT_CHECK(
        title = "设备接入检查间隔",
        summary = "多久比对一次 WiFi 客户端列表",
        detail = "「设备接入提醒」与「设备离开提醒」共用这一个周期（同一次查询供两个方向）。" +
            "同样共用设备的登录会话，下限 15 秒。开关刚打开时第一轮只建立基线、不报事件，" +
            "首条提醒最迟在两个周期后出现。",
        unit = "秒",
        range = 15f..600f,
        presets = listOf(30, 60, 180, 300)
    ),
    THERMAL_WARN(
        title = "温控预警阈值",
        summary = "到这个温度就降频采集",
        detail = "机身温度达到阈值后自动拉长采集间隔以减少发热，数据仍在采、只是变稀。",
        unit = "℃",
        range = 50f..90f,
        presets = listOf(55, 60, 70, 80)
    ),
    THERMAL_CRITICAL(
        title = "温控熔断阈值",
        summary = "到这个温度直接暂停采集",
        detail = "必须高于预警阈值：调到预警值之下时，预警值会自动下调一度，" +
            "否则设备端不会保存本次修改。",
        unit = "℃",
        range = 55f..100f,
        presets = listOf(65, 75, 85, 95)
    ),
    THERMAL_PAUSE(
        title = "熔断暂停时长",
        summary = "熔断后停多久再重试采集",
        detail = "越长越有利于降温，代价是这段时间内没有任何监控数据。",
        unit = "秒",
        range = 5f..300f,
        presets = listOf(30, 60, 120, 300)
    );

    /** 从设置里读当前值。 */
    fun read(settings: MonitorSettings): Int = when (this) {
        RETENTION_DAYS -> settings.retentionDays
        FLUSH_INTERVAL -> settings.flushIntervalSec
        ALERT_SCAN -> settings.alertScanSec
        IDLE_INTERVAL -> settings.idleIntervalSec
        THERMAL_WARN -> settings.thermalWarnC
        THERMAL_CRITICAL -> settings.thermalCriticalC
        THERMAL_PAUSE -> settings.thermalPauseSec
        TRAFFIC_LIMIT_CHECK -> settings.trafficLimitCheckSec
        DEVICE_EVENT_CHECK -> settings.deviceEventCheckSec
    }

    /** 写回设置（温控两档顺带纠正序关系，见 [withThermalOrder]）。 */
    fun write(settings: MonitorSettings, value: Int): MonitorSettings = when (this) {
        RETENTION_DAYS -> settings.copy(retentionDays = value)
        FLUSH_INTERVAL -> settings.copy(flushIntervalSec = value)
        ALERT_SCAN -> settings.copy(alertScanSec = value)
        IDLE_INTERVAL -> settings.copy(idleIntervalSec = value)
        THERMAL_WARN -> settings.copy(thermalWarnC = value).withThermalOrder(warnMoved = true)
        THERMAL_CRITICAL -> settings.copy(thermalCriticalC = value).withThermalOrder(warnMoved = false)
        THERMAL_PAUSE -> settings.copy(thermalPauseSec = value)
        TRAFFIC_LIMIT_CHECK -> settings.copy(trafficLimitCheckSec = value)
        DEVICE_EVENT_CHECK -> settings.copy(deviceEventCheckSec = value)
    }
}

/**
 * 单个调度参数的编辑弹窗：大号当前值 + 取舍说明 + 常用档位 chip + 精调滑块。
 *
 * 暂存-确认：改动只进 [draft]，点「确认」才回调。
 */
@Composable
private fun SchedulerValueDialog(
    field: SchedulerField,
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val palette = LocalResolvedPalette.current
    var draft by remember(field, current) { mutableIntStateOf(current) }

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = field.title,
        confirmButton = { UfiButton(text = "确认", onClick = { onConfirm(draft) }) },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss)
        }
    ) {
        UfiDialogBody {
            Text(
                text = "$draft ${field.unit}",
                style = UfiTextStyles.cardTitle,
                color = palette.accent
            )
            Text(
                text = field.detail,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            UfiDialogChipSelector(
                label = "常用档位",
                options = field.presets.map { it.toString() to "$it ${field.unit}" },
                selectedValue = draft.toString(),
                onSelect = { v -> v.toIntOrNull()?.let { draft = it } }
            )
            UfiDialogField(
                label = "精调（${field.range.start.roundToInt()} - ${field.range.endInclusive.roundToInt()} ${field.unit}）"
            ) {
                UfiValueSlider(
                    value = draft.toFloat(),
                    valueRange = field.range,
                    onValueChange = { draft = it.roundToInt() }
                )
            }
        }
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
 * ⑤ 存储管理：占用概览 / 清理 / 导出（进入时首载统计）。
 *
 * ## 2026-09-08 重排（两轮）
 * 第一轮把"一张卡从头铺到尾"拆成三张（占用概览 / 清理 / 导出），并把合计从最后提到最前。
 * 第二轮改掉可视化本身：
 * - 原来每张表一条独立的横向占比条。七条互不相干的条要用户自己在脑子里加总，
 *   而"这七项加起来就是那个合计"这层关系一点没画出来。现在是**一个圆环**：
 *   每张表一段、颜色对应下方图例，圆心直接写合计 —— 构成关系是图形本身。
 * - **去掉条数**。用户在这一页判断的是"谁占地方"，而 `size_kb` 本来就是行数换算的，
 *   再列一遍条数只是同一个信息的两种写法。
 *
 * 估算口径（`size_kb = 行数 × 字节系数`，不含索引与页开销）不在界面上说明 ——
 * 2026-09-08 加过一句又按要求撤掉了。口径记在 `docs/UFI-AXIS-Core-API-Reference.md`
 * 的 `/api/monitor/storage` 一节，web 端的存储卡上也仍有提示。
 *
 * ## 2026-09-08 第三轮：清理入口从 8 个收成 1 个（方案 A）
 * 概览卡每行行尾各有一个「清理」小按钮，加上清理卡那个，同一页 8 个破坏性入口；
 * 其中「短信」那个还是**假按钮** —— core 的 `/clean` 没有 `sms_records` 分支、`type=all`
 * 也刻意跳过它，点了什么都不会发生。
 *
 * 现在：概览卡零按钮（只回答"谁占地方"）；清理卡加一行「清理范围」chip（全部 + 6 类可清理的表，
 * 短信不列），配合原有的天数 chip，底下**一个**按钮，标题实时拼出"清理 X"。
 * 短信仍在占用图例里如实显示 —— 它占了空间，只是清不掉。
 *
 * ## 2026-09-08 第四轮：两个 chip 组下沉到弹窗
 * 上一轮那两行 chip（7 + 4 个）折下来占三四行，把清理卡撑得比它管的事还大。改成与本页
 * 「采集调度」同一形态：列表行只显示当前值，点开选择器。
 *
 * ## 2026-09-08 第五轮：清理整块收成一行入口
 * 第四轮之后清理卡还是 4 行（自动策略 / 范围 / 天数 / 执行）。现在整块变成**一行入口**，
 * 范围、天数、警告、执行全在 [StorageCleanDialog] 里，二级确认弹窗一并合并进去。
 * 本页现在只有三块：占用概览（圆环+图例）、清理（一行）、导出（两行）。
 *
 * ## 2026-09-09：清理范围改多选
 * 弹窗里的范围由单选换成多选，一次可以清几类；全选短路成 `type="all"` 一个请求，
 * 部分选中走 `DashboardModule.cleanHistoryBatch` 顺序清。
 */
@Composable
private fun StorageManagementSettingsGroup(
    monitorState: MonitorState,
    onLoadStorage: () -> Unit,
    /** 执行清理：`scopes` 是选中的表名集合（非空），是否短路成 `type="all"` 由调用方决定。 */
    onClean: (scopes: Set<String>, days: Int) -> Unit,
    onExportAll: () -> Unit,
    onExportZip: (Boolean) -> Unit
) {
    val palette = LocalResolvedPalette.current

    // 清理弹窗的开关。范围与天数是**弹窗内部**的暂存状态（见 [StorageCleanDialog]），
    // 不再提到页面级 —— 它们只在这个弹窗里有意义，关掉就该忘掉。
    var cleanDialogOpen by remember { mutableStateOf(false) }

    // 进入存储管理页加载一次（loadMonitorStorage 唯一入口）
    LaunchedEffect(Unit) { onLoadStorage() }

    // ── 卡 1：占用概览（圆环 + 图例）──
    UfiSettingsGroup {
        UfiGroupHeader("占用概览")

        val storageInfo = monitorState.storageInfo
        if (storageInfo != null) {
            val totalKb = storageInfo.total_kb
            // 按占用从大到小排：要清理的人找的就是最大的那张表，让它排在图例第一行，
            // 同时圆环也从 12 点方向开始按大小顺时针铺开，环与图例的顺序一致。
            val sorted = storageInfo.tables.sortedByDescending { it.size_kb }
            val colors = ufiDonutPalette()

            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Medium),
                contentAlignment = Alignment.Center
            ) {
                UfiDonutChart(
                    segments = sorted.mapIndexed { index, table ->
                        UfiDonutSegment(
                            value = table.size_kb,
                            color = colors[index % colors.size]
                        )
                    }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = storageInfo.total_display,
                            style = UfiTextStyles.heroValue,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "历史表合计",
                            style = UfiTextStyles.note,
                            color = palette.textSecondary
                        )
                    }
                }
            }

            // 图例：色点 + 表名 + 大小·占比。这一卡**零按钮**（2026-09-08 方案 A）——
            // 原来每行行尾一个「清理」小按钮，7 个破坏性入口挤在图例里，而且其中短信那个点了
            // 什么都不会发生（core 不支持清它）。现在概览只回答"谁占地方"，清理动作全在下一张卡。
            sorted.forEachIndexed { index, table ->
                if (index > 0) UfiDivider()
                val share = if (totalKb > 0) (table.size_kb / totalKb).toFloat().coerceIn(0f, 1f) else 0f
                UfiDonutLegendRow(
                    label = storageTableLabel(table.name),
                    value = "${formatStorageKb(table.size_kb)} · ${(share * 100).roundToInt()}%",
                    color = colors[index % colors.size]
                )
            }
        } else {
            UfiEmptyState(
                icon = Icons.Default.Storage,
                message = "暂无存储统计",
                hint = "统计在进入本页时自动读取，可稍后重试"
            )
        }
    }

    // ── 卡 2：清理（整块收成一行入口，2026-09-08 第五轮）──
    // 只剩一行就不再套 UfiGroupHeader —— 给一行内容加标题正是前几轮删掉的那种冗余。
    UfiSettingsRowCard {
        UfiSettingsValue(
            title = "清理历史数据",
            description = "按范围与天数删除旧记录，相当于提前执行一次自动清理",
            // 右侧刻意**不显示**弹窗里那个"保留天数"—— 那是操作参数，不是页面状态。
            // 显示自动策略的真实值才是诚实的：它读的是「采集调度 → 保留天数」。
            value = "自动 ${monitorState.settings.retentionDays} 天",
            onClick = { cleanDialogOpen = true }
        )
    }

    // ── 卡 3：导出 ──
    UfiSettingsGroup {
        UfiGroupHeader("导出")

        // 2026-09-08 从「默认行为」二级页并入：单开关不值得一个二级页，
        // 而它描述的就是下面那个「导出」按钮的行为，摆在一起才看得懂。
        UfiSettingsItem(
            title = "打包为 ZIP",
            description = "开启后 8 个 CSV 压缩成一个 zip；关闭则各自单独保存",
            trailing = {
                UfiSwitch(checked = monitorState.settings.exportZip, onCheckedChange = onExportZip)
            }
        )

        UfiDivider()

        UfiSettingsItem(
            title = "导出全部指标 CSV",
            description = "8 类指标各一份，落到 Download/UFI-AXIS/export/monitor/yyyy-MM-dd/",
            trailing = {
                UfiButton(size = UfiButtonSize.Small, text = "导出", onClick = onExportAll)
            }
        )
    }

    if (cleanDialogOpen) {
        StorageCleanDialog(
            retentionDays = monitorState.settings.retentionDays,
            onDismiss = { cleanDialogOpen = false },
            onConfirm = { scope, days ->
                cleanDialogOpen = false
                onClean(scope, days)
            }
        )
    }
}

/**
 * 清理历史数据弹窗（2026-09-08 第五轮）：范围 + 天数 + 执行，全在这一个面里。
 *
 * ## 为什么这里没有第二道确认
 * 上一版是「页面选范围 → 页面选天数 → 点清理 → 再弹 UfiConfirmDialog」。既然选择本身已经
 * 收进弹窗，再套一个确认弹窗就是弹窗盖弹窗。本弹窗自己承担确认职责：
 * - [UfiDialogWarning] 把"要删哪一类、删多久以前的、不可恢复"跟着选择实时拼出来；
 * - 底部走 [UfiDialogActions] 的 `confirmDestructive = true`（红色确认键），
 *   与 `UfiConfirmDialog(destructive = true)` 是同一套危险语义。
 * 代价是破坏性操作从两次点击确认降到一次 —— 2026-09-08 与使用方确认后接受。
 *
 * ## 状态只活在弹窗里
 * 范围与天数都是本函数的局部 state，关掉即弃。天数初值每次打开都按当前 [retentionDays]
 * 重新取最近档位（[nearestCleanDays]）—— 之前提在页面级用 `rememberSaveable` 记着，
 * 用户改完「采集调度 → 保留天数」再回来清理，默认值还是旧档位。
 *
 * ## 2026-09-09：范围改多选
 * 「清理范围」由单选换成 [UfiMultiChipSelector]，补上方案 A 当初的取舍（想连清两类要开两次弹窗）。
 * 默认全选 —— 多数人来这一页是整体瘦身，看完圆环发现某一类异常才会取消其余的。
 * 一个都不选时确认键置灰，而不是让它点下去发一个空请求。
 *
 * 「保留天数」仍是单选（[UfiDialogChipSelector]）：天数互斥，套多选组件会出现"零个天数"这种
 * 无法执行的状态。代价是两行 chip 外观不同（分段轨道 vs 药丸），已知，留待统一。
 *
 * 底部按钮走 content 末尾的 [UfiDialogActions]，所以**不传** confirmButton / dismissButton 槽位
 *（两者同时用会出现两排按钮）。
 *
 * @param retentionDays core 侧的自动保留天数，用于只读说明行与天数初值
 * @param onConfirm (scopes, days)：scopes 是选中的表名集合，全选与否由调用方决定怎么发请求
 */
@Composable
private fun StorageCleanDialog(
    retentionDays: Int,
    onDismiss: () -> Unit,
    onConfirm: (scopes: Set<String>, days: Int) -> Unit
) {
    var scopes by remember { mutableStateOf(cleanScopeOptions.map { it.first }.toSet()) }
    var days by remember(retentionDays) { mutableIntStateOf(nearestCleanDays(retentionDays)) }

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "清理历史数据",
        showCloseButton = false
    ) {
        UfiDialogBody {
            // 只读说明：手动清理只是"提前触发"，长期决定数据库大小的还是这个自动保留天数。
            // 这行必须留 —— 之前自动保留 3 天、手动写死 7 天，用户点了清理基本删不到任何记录。
            UfiDialogInfoRow(label = "自动清理", value = "保留 $retentionDays 天")

            UfiDialogField("清理范围") {
                UfiMultiChipSelector(
                    options = cleanScopeOptions,
                    selectedValues = scopes,
                    onToggle = { key ->
                        scopes = if (key in scopes) scopes - key else scopes + key
                    }
                )
            }

            UfiDialogChipSelector(
                label = "保留天数",
                options = cleanDayOptions.map { it.toString() to "$it 天" },
                selectedValue = days.toString(),
                onSelect = { v -> v.toIntOrNull()?.let { days = it } }
            )

            UfiDialogWarning(
                if (scopes.isEmpty()) {
                    "请至少选择一类要清理的数据"
                } else {
                    "将删除「${cleanScopeSummary(scopes)}」$days 天前的记录，不可恢复"
                }
            )
        }
        UfiDialogActions(
            onDismiss = onDismiss,
            onConfirm = { onConfirm(scopes, days) },
            confirmText = "清理",
            confirmDestructive = true,
            // 一个都不选时不让点：空集合发出去就是一个什么都不删的请求
            enabled = scopes.isNotEmpty()
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

/** 代表"所有可清理的表"，与 core `POST /api/monitor/clean` 的 `type` 取值一致。 */
private const val CLEAN_SCOPE_ALL = "all"

/**
 * 可清理的历史表（清理弹窗的「清理范围」多选项）。
 *
 * 2026-09-08 起概览卡不再有逐表清理按钮，范围改由弹窗里的 chip 决定；
 * 2026-09-09 由单选改**多选**（[UfiMultiChipSelector]），所以这里不再有「全部」这一项 ——
 * 全选就等于全部，多一个「全部」chip 会和"6 个都选中"表达同一件事。
 *
 * **`sms_records` 故意不在这里**：core 的 `/clean` 既没有它的分支、`type=all` 也刻意跳过它
 *（`MonitorRoutes.kt` 的「sms_records 永久保留，不参与清理」）。它仍然出现在占用图例里
 *（占了空间就要如实显示），只是不提供清不掉的入口。
 */
private val cleanScopeOptions: List<Pair<String, String>> = listOf(
    "cpu_history" to "CPU",
    "memory_history" to "内存",
    "traffic_records" to "流量",
    "signal_history" to "信号",
    "battery_history" to "电池",
    "alert_records" to "告警"
)

/**
 * 已选范围的人话摘要（弹窗警告块用）。
 *
 * 全选说「全部历史表」而不是罗列 6 个名字：那一行会长到折行，而"全部"本身就是用户的意图。
 */
private fun cleanScopeSummary(scopes: Set<String>): String = when {
    scopes.isEmpty() -> "未选择"
    scopes.size == cleanScopeOptions.size -> "全部历史表"
    else -> cleanScopeOptions.filter { it.first in scopes }.joinToString("、") { it.second }
}

/** 手动清理的四个档位；把 core 的保留天数（1..90 任意值）映射到最接近的一档做初值 */
private val cleanDayOptions = listOf(1, 3, 7, 30)

private fun nearestCleanDays(retentionDays: Int): Int =
    cleanDayOptions.minByOrNull { kotlin.math.abs(it - retentionDays) } ?: 7

/** 存储大小可读化：≥1MB 显示 MB，否则 KB（P4b，迁入本页） */
private fun formatStorageKb(kb: Double): String = when {
    kb >= 1024 -> "%.1f MB".format(kb / 1024)
    else -> "%.0f KB".format(kb)
}
