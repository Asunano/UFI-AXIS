package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.navigation.NavHostController
import androidx.compose.ui.graphics.vector.ImageVector
import com.ufi_axis.data.model.AutomationRule
import com.ufi_axis.data.model.ExecutionLog
import com.ufi_axis.data.model.ScheduledTask
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiCardShadow
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis_core.util.CronParser
import com.ufi_axis_core.util.CronParser.SchedulePreset
import com.ufi_axis_core.util.CronParser.ScheduleValue
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.*
import com.ufi_axis.ui.theme.UfiMotion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.tasksState.collectAsState()
    val palette = LocalResolvedPalette.current
    var selectedTab by remember { mutableStateOf(0) }   // 0=定时任务 1=条件规则
    var viewingTaskLogs by remember { mutableStateOf<ScheduledTask?>(null) }
    // 条件规则 / 定时任务编辑均走全屏路由（弹窗字段多易被裁切，2026-08-18 改为全屏）。
    var viewingRuleLogs by remember { mutableStateOf<AutomationRule?>(null) }

    // ── 二次确认状态（2026-09-21：审计补全） ──
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var pendingDeleteTask by remember { mutableStateOf<ScheduledTask?>(null) }
    var pendingDeleteRule by remember { mutableStateOf<AutomationRule?>(null) }


    /*
     * 进页面拉一次，**回前台再拉一次**（2026-09-20 补）。
     *
     * 这一页的数据在 Web 面板那边也能改，而 `/ws/realtime` 原来没有任务相关的推送 ——
     * 于是"Web 新建任务 → 切回 App"永远看不到，除非退出页面重新进。
     * 补 core 侧 WS 推送的同时，这里也加上 resume 刷新：其它十个数据页
     * （短信 / 网络 / 投递历史 / 通知管理…）早就这么做了，本页是唯一漏的。
     */
    LaunchedEffect(Unit) {
        viewModel.tools.loadTaskList()
        viewModel.tools.loadRuleList()
    }
    rememberResumeRefresh {
        viewModel.tools.loadTaskList()
        viewModel.tools.loadRuleList()
    }


    UfiScreenScaffold(title = "自动化", navController = navController, showBack = true,
        actions = {
            val hasItems = if (selectedTab == 0) state.tasks.isNotEmpty() else state.rules.isNotEmpty()
            if (hasItems) {
                IconButton(onClick = { showClearAllConfirm = true }) {
                    Icon(Icons.Default.DeleteSweep, "清除全部")
                }
            }
            IconButton(onClick = {
                if (selectedTab == 0) navController.navigate("detail/task-edit")
                else navController.navigate("detail/rule-edit")
            }) {
                Icon(Icons.Default.Add, if (selectedTab == 0) "新建定时任务" else "新建条件规则")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 双触发 Tab（UfiScrollableTabRow 滑块分段，与下载管理同款视觉）
            UfiScrollableTabRow(
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                tabs = listOf("定时任务", "条件规则"),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            state.errorMessage?.let { err ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .ufiCardShadow(elevation = 4.dp, shape = UfiCardDefaults.legacyShape)
                        .clip(UfiCardDefaults.legacyShape)
                        .background(palette.errorContainer, UfiCardDefaults.legacyShape)
                ) {
                    Text(err, modifier = Modifier.padding(12.dp), color = palette.error)
                }
            }

            if (selectedTab == 0) {
                // ── 定时任务 ──
                // 空态门看 tasksLoaded（成功读到过）而不是 !isLoading：
                // 后者在"请求还在飞"和"请求失败了"时都是 false，于是首次进页面
                // 会抢在响应到达前宣布"暂无任务"——这正是 Web 端新建的任务看不到的原因。
                if (state.tasks.isEmpty() && state.tasksLoaded) {
                    // 2026-09-08：这里原来是手搓的「圆底图标 + 两级文字 + 胶囊按钮」整块。
                    // 那套版式已经收进公共组件 [UfiEmptyState]（连尺寸档位一起提到 Spacing），
                    // 本页改为调用它 —— 观感不变，全站空态从此只有一份实现。
                    UfiEmptyState(
                        icon = Icons.Default.Schedule,
                        message = "暂无定时任务",
                        hint = "点击右上角 + 创建第一个任务",
                        modifier = Modifier.fillMaxSize(),
                        action = {
                            Button(
                                onClick = { navController.navigate("detail/task-edit") },
                                shape = UfiCardDefaults.capsuleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("新建任务")
                            }
                        }
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.tasks, key = { it.id }) { task ->
                            TaskCard(task = task,
                                onEdit = { navController.navigate("detail/task-edit?id=${it.id}") },
                                onDelete = { pendingDeleteTask = it },
                                onToggle = { viewModel.tools.updateTask(it.id, it.copy(enabled = !it.enabled)) },
                                onShowLogs = { viewModel.tools.loadTaskLogs(it.id); viewingTaskLogs = it }
                            )
                        }
                    }
                }
            } else {
                // ── 条件规则（当…就…）──
                // 同上：看 rulesLoaded 而不是 !isLoading。
                // 这一侧原来还有第二重伤害 —— loadTaskList 会把 rules 一起清空，
                // 于是"动一下任务开关"就让规则 Tab 显示「暂无自动化规则」。
                if (state.rules.isEmpty() && state.rulesLoaded) {
                    UfiEmptyState(
                        icon = Icons.Default.AutoAwesome,
                        message = "暂无自动化规则",
                        hint = "当流量达标 / 网络切换 / 电量过低时自动执行动作",
                        modifier = Modifier.fillMaxSize(),
                        action = {
                            Button(
                                onClick = { navController.navigate("detail/rule-edit") },
                                shape = UfiCardDefaults.capsuleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("新建规则")
                            }
                        }
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.rules, key = { it.id }) { rule ->
                            RuleCard(rule = rule,
                                onEdit = { navController.navigate("detail/rule-edit?id=${it.id}") },
                                onDelete = { pendingDeleteRule = it },
                                onToggle = { viewModel.tools.updateRule(it.id, it.copy(enabled = !it.enabled)) },
                                onShowLogs = { viewModel.tools.loadRuleLogs(it.id); viewingRuleLogs = it }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 二次确认弹窗（2026-09-21 审计补全：清空全部 / 删除单个任务 / 删除单个规则） ──
    if (showClearAllConfirm) {
        val isTask = selectedTab == 0
        val count = if (isTask) state.tasks.size else state.rules.size
        UfiConfirmDialog(
            title = if (isTask) "清空全部定时任务" else "清空全部条件规则",
            text = "将删除当前全部 $count 条${if (isTask) "定时任务" else "条件规则"}，此操作不可恢复。",
            confirmText = "全部删除",
            destructive = true,
            onDismiss = { showClearAllConfirm = false },
            onConfirm = {
                showClearAllConfirm = false
                if (isTask) viewModel.tools.clearTasks() else viewModel.tools.clearRules()
            }
        )
    }
    pendingDeleteTask?.let { task ->
        UfiConfirmDialog(
            title = "删除任务",
            text = "确定删除「${task.name.ifBlank { "未命名任务" }}」？删除后不可恢复。",
            confirmText = "删除",
            destructive = true,
            onDismiss = { pendingDeleteTask = null },
            onConfirm = { pendingDeleteTask = null; viewModel.tools.deleteTask(task.id) }
        )
    }
    pendingDeleteRule?.let { rule ->
        UfiConfirmDialog(
            title = "删除规则",
            text = "确定删除「${rule.name.ifBlank { "未命名规则" }}」？删除后不可恢复。",
            confirmText = "删除",
            destructive = true,
            onDismiss = { pendingDeleteRule = null },
            onConfirm = { pendingDeleteRule = null; viewModel.tools.deleteRule(rule.id) }
        )
    }

    viewingTaskLogs?.let { task ->
        TaskLogDialog(
            task = task,
            logs = state.taskLogs[task.id] ?: emptyList(),
            onDismiss = { viewingTaskLogs = null }
        )
    }

    viewingRuleLogs?.let { rule ->
        RuleLogDialog(
            rule = rule,
            logs = state.ruleLogs[rule.id] ?: emptyList(),
            onDismiss = { viewingRuleLogs = null }
        )
    }
}

/**
 * 任务卡片
 */
@Composable
private fun TaskCard(task: ScheduledTask, onEdit: (ScheduledTask) -> Unit, onDelete: (ScheduledTask) -> Unit, onToggle: (ScheduledTask) -> Unit, onShowLogs: (ScheduledTask) -> Unit) {
    val actionDef = ActionRegistry.getByType(task.actionType)
    val palette = LocalResolvedPalette.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UfiCardDefaults.legacyShape)
            .clickable { onEdit(task) }
            .ufiCardShadow(elevation = 4.dp, shape = UfiCardDefaults.legacyShape)
            .background(palette.cardBg, UfiCardDefaults.legacyShape)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：柔和橙底圆形图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(palette.accentContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    actionDef?.icon ?: Icons.Default.Terminal,
                    null,
                    modifier = Modifier.size(22.dp),
                    tint = palette.accent
                )
            }
            Spacer(Modifier.width(12.dp))
            // 中间：任务名 + 副标题（重复 时间）
            Column(Modifier.weight(1f)) {
                Text(
                    task.name.ifBlank { actionDef?.name ?: task.command.take(30) },
                    style = UfiTextStyles.panelTitleStrong,
                    color = palette.textPrimary,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    scheduleSummary(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1
                )
            }
            // 右侧：日志 / 删除低调图标 + 橙色开关
            @Suppress("DEPRECATION")
            IconButton(onClick = { onShowLogs(task) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Article, "日志", Modifier.size(18.dp), tint = palette.textSecondary)
            }
            @Suppress("DEPRECATION")
            IconButton(onClick = { onDelete(task) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, "删除", Modifier.size(18.dp), tint = palette.error)
            }
            // 2026-08-31：M3 Switch + SwitchDefaults(colorScheme) → 公共 UfiSwitch，
            // 配色改由 palette.switch* token 统一（原来这里用 scheme.primary，与全站其余开关不同源）
            UfiSwitch(
                checked = task.enabled,
                onCheckedChange = { onToggle(task) }
            )
        }
    }
}

// ============ 执行周期：ScheduledTask ↔ ScheduleValue / 列表文案 ============

/** 将 ScheduledTask 还原为 UI 调度值（有 cron 精确还原，否则按旧字段回退）。 */
private fun ScheduledTask.toScheduleValue(): ScheduleValue {
    val cron = cron
    val scheduleType = scheduleType
    return if (cron != null && scheduleType != null) {
        CronParser.toScheduleValue(cron, scheduleType)
    } else {
        ScheduleValue(
            scheduleType = if (repeatDaily) SchedulePreset.DAILY.type else "once",
            hour = hour,
            minute = minute
        )
    }
}

/** 列表副标题：每天 08:30 / 每周一三五 19:30 / 每月1,15日 08:30 等。 */
private fun scheduleSummary(task: ScheduledTask): String = scheduleValueSummary(task.toScheduleValue())

/**
 * 把 [ScheduleValue] 渲染成一句中文周期描述。
 *
 * 2026-09-21 从 [scheduleSummary] 里提出来：向导的确认页要对**编辑中的** ScheduleValue
 * 出同一句话，而那时还没有 ScheduledTask 对象。两处共用一个实现，措辞不会漂。
 */
private fun scheduleValueSummary(sv: ScheduleValue): String {
    val hm = "%02d:%02d".format(sv.hour, sv.minute)
    return when (sv.scheduleType) {
        "once" -> "仅一次 $hm"
        "daily" -> "每天 $hm"
        "weekly" -> "每周${sv.weekDays.sorted().joinToString("") { weekdayName(it) }.ifBlank { "一" }} $hm"
        "monthly" -> "每月${sv.monthDays.sorted().joinToString(",").ifBlank { "1" }}日 $hm"
        "every_n_days" -> "每 ${sv.intervalN} 日 $hm"
        "every_n_hours" -> "每 ${sv.intervalN} 时"
        "every_n_minutes" -> "每 ${sv.intervalN} 分"
        "custom" -> "自定义 $hm"
        else -> hm
    }
}

private fun weekdayName(d: Int): String = when (d) {
    1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; 7 -> "日"; else -> ""
}

@Composable
private fun TaskLogDialog(task: ScheduledTask, logs: List<ExecutionLog>, onDismiss: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "任务日志 - ${task.name.ifBlank { task.command.take(20) }}",
        icon = rememberVectorPainter(Icons.Filled.Schedule),
        showCloseButton = false
    ) {
        UfiDialogBody {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Info, null, tint = LocalResolvedPalette.current.textSecondary, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("暂无执行记录", style = MaterialTheme.typography.bodyMedium, color = LocalResolvedPalette.current.textSecondary)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logs.reversed(), key = { it.id }) { log ->
                        LogEntryItem(log = log, timeFormat = timeFormat)
                    }
                }
            }
        }
        UfiDialogActions(
            onDismiss = onDismiss,
            onConfirm = onDismiss,
            confirmText = "关闭",
            dismissText = null,
            topSpacing = Spacing.Large
        )
    }
}

@Composable
private fun LogEntryItem(log: ExecutionLog, timeFormat: SimpleDateFormat) {
    val palette = LocalResolvedPalette.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (log.success) palette.accentContainer.copy(alpha = 0.3f)
            else palette.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (log.success) Icons.Default.CheckCircle else Icons.Default.Error,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = if (log.success) palette.accent else palette.error
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    timeFormat.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary
                )
            }
            if (log.output.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    log.output,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textPrimary,
                    maxLines = 5
                )
            }
        }
    }
}

// ============ 定时任务编辑器（全屏 Route 页面 · 引导式 4 步） ============
// 2026-08-18: 原 TaskEditDialog 弹窗字段多（名称+分类+动作+多参数+Shell+时分+重复+启用），
// 受 UfiScrollableDialog 82% 屏高约束，启用开关/CTA 易被裁切。改为全屏页面 detail/task-edit?id=...，
// 由 buildAppScreens 路由映射装配。
//
// 2026-09-21: 全屏长滚动单页 → 引导式 4 步（公共组件 [UfiWizard]）。为什么再改一次：
//   1. 六组字段一次铺开，用户得读完整页才知道哪几项必填；
//   2. 保存按钮在标题栏右上角，校验不通过时**静默无反应** —— 原实现是
//      `if (动作已选 && (非 custom_shell || 命令非空)) { …保存… }`，条件不成立就什么都不做：
//      没有 toast、按钮也不置灰，用户只看到"点了没反应"。
// 现在每步只暴露一组字段，「能不能往下走」由 UfiWizardStep.validate 显式回答并给出原因；
// 保存动作移到向导底部操作栏（末步主按钮），标题栏不再有 ✓ —— 那条静默路径整条删掉了。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    taskId: String
) {
    val state by viewModel.tasksState.collectAsState()
    val isNew = taskId.isBlank()
    // 深链 / 进程重建时 state.tasks 可能是空的（列表页的 LaunchedEffect 没跑过），本页自己拉一次
    LaunchedEffect(Unit) { viewModel.tools.loadTaskList() }
    val initial: ScheduledTask = remember(taskId, state.tasks) {
        if (isNew) ScheduledTask()
        else state.tasks.firstOrNull { it.id == taskId } ?: ScheduledTask(id = taskId)
    }
    /*
     * 编辑态且列表还没到：渲染加载态，不给保存按钮。
     *
     * 否则 initial 退化成空对象、表单一片空白，而保存会把这份空值覆盖到设备上的任务。
     * 判据带 `!state.tasksLoaded` —— 列表已经成功读过但仍找不到这个 id（被别处删了），
     * 就按原有兜底走（空表单 + 保留 id），不要在这里卡死。
     */
    val awaitingTask = !isNew && state.tasks.none { it.id == taskId } && !state.tasksLoaded

    var currentStep by remember { mutableStateOf(0) }
    // 字段都跟着 initial 走：列表晚到时（深链）这些 remember 会重建并回填，
    // 无 key 的 remember 会把空表单一直留在屏幕上（参考 StorageSourceEditScreen 的 remember(source)）
    var name by remember(initial) { mutableStateOf(initial.name) }
    var selectedCategory by remember(initial) { mutableStateOf(initial.actionType.let { ActionRegistry.getByType(it)?.category ?: "network" }) }
    var selectedActionType by remember(initial) { mutableStateOf(initial.actionType) }
    var actionParams by remember(initial) { mutableStateOf(initial.params) }
    var command by remember(initial) { mutableStateOf(initial.command) }
    // 执行周期（时间触发）：单一 ScheduleValue 承载 8 preset + 自定义 cron（v9 扩展）
    var scheduleValue by remember(initial) {
        mutableStateOf(if (isNew) ScheduleValue.DEFAULT else initial.toScheduleValue())
    }
    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    var toast by remember { mutableStateOf<ToastMessage?>(null) }
    var saveDone by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }


    // 这两个判据同时喂给 validate（决定能否往下走）与字段的 isError（行内标红），
    // 写成一处才不会出现"按钮灰了但没有哪个字段标红"的错位。
    fun actionError(): String? = when {
        selectedActionType.isBlank() -> "请先选择要执行的动作"
        selectedActionType == "custom_shell" && command.isBlank() -> "自定义命令不能为空"
        else -> null
    }

    fun scheduleError(): String? = scheduleValue.let { sv ->
        when {
            // 原实现是 `takeIf { isValid }`：非法 cron 被静默丢成 null，任务照样保存成"没有周期"。
            sv.scheduleType == "custom" && !CronParser.isValid(sv.customCron) ->
                "自定义 cron 无效，格式为「分 时 日 月 周」，如 30 8 * * 1-5"
            // 原实现空集合由 CronParser.toCron 兜底成"周一 / 1 日"，等于替用户做了个没说过的决定。
            sv.scheduleType == "weekly" && sv.weekDays.isEmpty() -> "请至少选择一个星期"
            sv.scheduleType == "monthly" && sv.monthDays.isEmpty() -> "请至少选择一个日期"
            else -> null
        }
    }

    fun submit() {
        val sv = scheduleValue
        // 由结构化选择生成 cron / repeatDaily / scheduleType（v9 时间触发扩展）
        // 向后兼容：daily 仍走旧 repeatDaily 路径（scheduleType/cron 留空），旧任务可无损回存
        val (cron: String?, repeatDailyOut: Boolean, scheduleTypeOut: String?) = when (sv.scheduleType) {
            "daily" -> Triple(null, true, null) // 旧路径：保留 legacy 字段
            "once" -> Triple(null, false, "once")
            "custom" -> Triple(sv.customCron, true, "custom") // 合法性已由 scheduleError 拦在前面
            else -> Triple(
                CronParser.toCron(sv.scheduleType, sv.hour, sv.minute, sv.weekDays, sv.monthDays, sv.intervalN),
                true,
                sv.scheduleType
            )
        }
        val scheduleParams = buildMap<String, JsonPrimitive> {
            if (sv.weekDays.isNotEmpty()) put("weekDays", JsonPrimitive(sv.weekDays.sorted().joinToString(",")))
            if (sv.monthDays.isNotEmpty()) put("monthDays", JsonPrimitive(sv.monthDays.sorted().joinToString(",")))
            put("intervalN", JsonPrimitive(sv.intervalN))
        }
        val task = initial.copy(
            name = name,
            actionType = selectedActionType,
            params = actionParams,
            command = command,
            hour = sv.hour.coerceIn(0, 23),
            minute = sv.minute.coerceIn(0, 59),
            repeatDaily = repeatDailyOut,
            triggerMode = if (scheduleTypeOut == null) null else "schedule",
            scheduleType = scheduleTypeOut,
            cron = cron,
            scheduleParams = if (scheduleTypeOut == null) emptyMap() else scheduleParams,
            enabled = enabled
        )
        // 成功才 toast + 退页：createTask/updateTask 是 fire-and-forget，
        // 原来提交完立刻报"已创建"并 pop，网络失败 / 400 时用户看到的是成功提示
        submitting = true
        val onDone: (Boolean, String) -> Unit = { ok, msg ->
            submitting = false
            toast = ToastMessage(msg, if (ok) ToastType.SUCCESS else ToastType.ERROR)
            if (ok) saveDone = true
        }
        if (isNew) {
            viewModel.tools.createTask(task, onDone)
        } else {

            viewModel.tools.updateTask(task.id, task, onDone)
        }
    }


    // 列表还没到就只给加载态：表单与「保存」一起不出现，比灰着一个按钮更明确
    if (awaitingTask) {
        UfiScreenScaffold(title = "编辑任务", navController = navController, showBack = true) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                UfiLoadingIndicator()
            }
        }
        return
    }

    UfiScreenScaffold(
        title = if (isNew) "新建任务" else "编辑任务",
        navController = navController,
        showBack = true
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {

            UfiWizard(
                steps = listOf(
                    UfiWizardStep(
                        label = "基本信息",
                        heading = "给任务起个名字",
                        description = "名称只用于列表展示；留空时列表会显示所选动作名。"
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            EnableToggleRow(
                                title = "启用此任务",
                                subtitle = "创建后立即生效，无需手动开启",
                                icon = Icons.Filled.FlashOn,
                                checked = enabled,
                                onCheckedChange = { enabled = it }
                            )
                            UfiDialogTextField(
                                label = "任务名称",
                                value = name,
                                onValueChange = { name = it },
                                placeholder = "如：每天 8 点重启"
                            )
                        }
                    },
                    UfiWizardStep(
                        label = "执行动作",
                        heading = "任务要做什么",
                        description = "先选分类，再选具体动作；部分动作还需要补一个参数。",
                        validate = ::actionError
                    ) {
                        ActionPickerSection(
                            selectedCategory = selectedCategory,
                            onCategoryChange = { selectedCategory = it },
                            selectedActionType = selectedActionType,
                            onActionChange = { selectedActionType = it },
                            params = actionParams,
                            onParamsChange = { actionParams = it },
                            commandValue = command,
                            onCommandChange = { command = it },
                            commandError = selectedActionType == "custom_shell" && command.isBlank()
                        )
                    },
                    UfiWizardStep(
                        label = "执行周期",
                        heading = "什么时候执行",
                        description = "选好后，卡片底部会实时算出下一次执行时间。",
                        validate = ::scheduleError
                    ) {
                        // v9：合并「执行时间」+「重复」为单一 ScheduleSelector 公共组件
                        ScheduleSelector(
                            scheduleType = scheduleValue.scheduleType,
                            hour = scheduleValue.hour,
                            minute = scheduleValue.minute,
                            weekDays = scheduleValue.weekDays,
                            monthDays = scheduleValue.monthDays,
                            intervalN = scheduleValue.intervalN,
                            customCron = scheduleValue.customCron,
                            onChange = { scheduleValue = it }
                        )
                    },
                    UfiWizardStep(
                        label = "确认",
                        heading = "核对一遍再保存",
                        description = "需要改动时点「上一步」，或直接点上方步骤条跳到任意一步。"
                    ) {
                        val previewCron = CronParser.previewCron(
                            scheduleValue.scheduleType,
                            scheduleValue.hour,
                            scheduleValue.minute,
                            scheduleValue.weekDays,
                            scheduleValue.monthDays,
                            scheduleValue.intervalN,
                            scheduleValue.customCron
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            UfiWizardReviewCard(
                                title = "基本信息",
                                icon = Icons.Filled.Schedule,
                                rows = listOf(
                                    UfiWizardReviewRow("任务名称", name.ifBlank { "未填写" }),
                                    UfiWizardReviewRow("创建后状态", if (enabled) "启用" else "停用")
                                )
                            )
                            UfiWizardReviewCard(
                                title = "执行动作",
                                icon = ActionRegistry.getByType(selectedActionType)?.icon ?: Icons.Filled.Bolt,
                                rows = actionReviewRows(selectedCategory, selectedActionType, actionParams, command)
                            )
                            UfiWizardReviewCard(
                                title = "执行周期",
                                icon = Icons.Filled.Event,
                                rows = listOf(
                                    UfiWizardReviewRow("周期", scheduleValueSummary(scheduleValue)),
                                    UfiWizardReviewRow("cron", previewCron ?: "无（按旧字段执行）")
                                )
                            )
                        }
                    }
                ),
                currentStep = currentStep,
                onStepChange = { currentStep = it },
                onFinish = { submit() },
                finishText = if (isNew) "创建任务" else "保存修改",
                finishLoading = submitting,
                onStepBlocked = { toast = ToastMessage(it, ToastType.WARNING) }

            )
            UfiToastHost(toastMessage = toast, onDismiss = { toast = null })
        }
        // 先让 toast 挂出去再退页：UfiToastHost 的 effect 与本 effect 同批派发、按声明顺序执行，
        // 而 toast 卡片是 add 到 Activity 的 decorView（脱离 Compose 树），所以 pop 掉本页也不会带走它。
        LaunchedEffect(saveDone) { if (saveDone) navController.popBackStack() }
    }
}

// ============ 条件规则（当…就…）相关 composables ============
// 与定时任务（ScheduledTask）同款视觉，复用同一套公共 UI 组件（UfiScrollableDialog / UfiOptionGrid /
// CategoryChip / UfiDialog*），公共组件 0 改动（F24 红线）。

/**
 * 触发条件目录（v1）。图标 + 名称，与设计语言统一。
 * 与后端 ConditionEngine 的 triggerType 严格对应：
 *  - traffic_total_reached → thresholdBytes（字节）
 *  - network_type_changed  → targetType（"4G"/"5G"/...）
 *  - signal_below          → rsrp（dBm，负值）
 *  - battery_below         → levelPercent（int %）
 *  - disconnect            → 无参数
 */
private data class RuleTriggerDef(
    val type: String,
    val name: String,
    val icon: ImageVector,
    val hint: String
)

private val RULE_TRIGGERS = listOf(
    RuleTriggerDef("traffic_total_reached", "流量达标", Icons.Default.DataUsage, "当月累计流量达到阈值"),
    RuleTriggerDef("network_type_changed", "网络跳变", Icons.Default.SwapHoriz, "网络类型变为目标（如 5G→4G）"),
    RuleTriggerDef("signal_below", "信号弱", Icons.Default.SignalCellularAlt, "RSRP 低于阈值"),
    RuleTriggerDef("battery_below", "电量低", Icons.Default.BatteryAlert, "未充电且电量低于阈值"),
    RuleTriggerDef("disconnect", "断网", Icons.Default.WifiOff, "蜂窝网络断开瞬间")
)

/** 把规则的触发条件渲染成一句中文副标题，用于卡片副标题「当 … 就 …」。 */
private fun ruleTriggerSummary(rule: AutomationRule): String {
    return when (rule.triggerType) {
        "traffic_total_reached" -> {
            val gb = rule.triggerParams["thresholdBytes"]?.content?.toLongOrNull()?.let { it / (1024.0 * 1024 * 1024) }
            "流量累计 ≥ ${gb?.let { String.format(Locale.US, "%.1fGB", it) } ?: "?"}"
        }
        "network_type_changed" -> "网络变为 ${rule.triggerParams["targetType"]?.content ?: "?"}"
        "signal_below" -> "信号 RSRP ≤ ${rule.triggerParams["rsrp"]?.content ?: "?"} dBm"
        "battery_below" -> "电量 ≤ ${rule.triggerParams["levelPercent"]?.content ?: "?"}% 且未充电"
        "disconnect" -> "蜂窝网络断开"
        else -> rule.triggerType
    }
}

@Composable
private fun RuleCard(rule: AutomationRule, onEdit: (AutomationRule) -> Unit, onDelete: (AutomationRule) -> Unit, onToggle: (AutomationRule) -> Unit, onShowLogs: (AutomationRule) -> Unit) {
    val actionDef = ActionRegistry.getByType(rule.actionType)
    val palette = LocalResolvedPalette.current
    val triggerText = ruleTriggerSummary(rule)
    val actionText = actionDef?.name ?: rule.actionType

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UfiCardDefaults.legacyShape)
            .clickable { onEdit(rule) }
            .ufiCardShadow(elevation = 4.dp, shape = UfiCardDefaults.legacyShape)
            .background(palette.cardBg, UfiCardDefaults.legacyShape)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(palette.accentContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(22.dp), tint = palette.accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    rule.name.ifBlank { "$triggerText · $actionText" },
                    style = UfiTextStyles.panelTitleStrong,
                    color = palette.textPrimary,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "当 $triggerText 时，就 $actionText",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 2
                )
            }
            @Suppress("DEPRECATION")
            IconButton(onClick = { onShowLogs(rule) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Article, "日志", Modifier.size(18.dp), tint = palette.textSecondary)
            }
            @Suppress("DEPRECATION")
            IconButton(onClick = { onDelete(rule) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, "删除", Modifier.size(18.dp), tint = palette.error)
            }
            // 2026-08-31：M3 Switch + SwitchDefaults(colorScheme) → 公共 UfiSwitch
            UfiSwitch(
                checked = rule.enabled,
                onCheckedChange = { onToggle(rule) }
            )
        }
    }
}

@Composable
private fun RuleLogDialog(rule: AutomationRule, logs: List<ExecutionLog>, onDismiss: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "规则日志 - ${rule.name.ifBlank { rule.id }}",
        icon = rememberVectorPainter(Icons.Filled.AutoAwesome),
        showCloseButton = false
    ) {
        UfiDialogBody {
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Info, null, tint = LocalResolvedPalette.current.textSecondary, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("暂无执行记录", style = MaterialTheme.typography.bodyMedium, color = LocalResolvedPalette.current.textSecondary)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(logs.reversed(), key = { it.id }) { log -> LogEntryItem(log = log, timeFormat = timeFormat) }
                }
            }
        }
        UfiDialogActions(
            onDismiss = onDismiss,
            onConfirm = onDismiss,
            confirmText = "关闭",
            dismissText = null,
            topSpacing = Spacing.Large
        )
    }
}

// ============ 条件规则编辑器（全屏 Route 页面 · 引导式 4 步） ============
// 2026-08-18：弹窗字段太多（名称+触发+触发参数+动作+动作参数+启用），
// UfiScrollableDialog 即便带滚动也仍被横竖挤（5 个触发 chip 横排被裁、Shell 命令框被裁）。
// 改为全屏页面 detail/rule-edit?id=…，由 [com.ufi_axis.app.navigation.buildAppScreens] 路由映射装配。
//
// 2026-09-21：与 [TaskEditScreen] 一起改成引导式 4 步（公共组件 [UfiWizard]）。
// 本页原来还多一个毛病：三个阈值输入框（流量 GB / RSRP / 电量 %）是**裸文本框**，
// 既没有输入过滤也没有 isError —— 输入「abc」会在提交时被静默回落成 1GB / -110 / 20%，
// 用户毫无察觉；也不校验范围（可以存下 levelPercent = 999）。
// 现在这三个值由 triggerError 统一判定：非法时字段标红、底部给出原因、「下一步」置灰。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    ruleId: String
) {
    val state by viewModel.tasksState.collectAsState()
    val isNew = ruleId.isBlank()
    // 深链 / 进程重建时 state.rules 可能是空的（列表页的 LaunchedEffect 没跑过），本页自己拉一次
    LaunchedEffect(Unit) { viewModel.tools.loadRuleList() }
    // id 命中取已有规则；未命中或为空=新建。后端会自动给空 id 生成 8 字符 id（POST /rules）。
    val initial: AutomationRule = remember(ruleId, state.rules) {
        if (isNew) AutomationRule()
        else state.rules.firstOrNull { it.id == ruleId } ?: AutomationRule(id = ruleId)
    }
    // 编辑态且列表还没到：见 [TaskEditScreen] 同名判据的说明
    val awaitingRule = !isNew && state.rules.none { it.id == ruleId } && !state.rulesLoaded

    // 表单 state —— 全部跟着 initial 走，列表晚到时会重建并回填
    var currentStep by remember { mutableStateOf(0) }
    var name by remember(initial) { mutableStateOf(initial.name) }
    var selectedTrigger by remember(initial) { mutableStateOf(initial.triggerType) }
    var trafficGb by remember(initial) {
        mutableStateOf(initial.triggerParams["thresholdBytes"]?.content?.toLongOrNull()
            ?.let { it / (1024.0 * 1024 * 1024) }
            ?.let { String.format(Locale.US, "%.2f", it) } ?: "1.00")
    }
    var rsrp by remember(initial) { mutableStateOf(initial.triggerParams["rsrp"]?.content ?: "-110") }
    var batteryLevel by remember(initial) { mutableStateOf(initial.triggerParams["levelPercent"]?.content ?: "20") }
    var targetType by remember(initial) { mutableStateOf(initial.triggerParams["targetType"]?.content ?: "4G") }
    var selectedCategory by remember(initial) {
        mutableStateOf(initial.actionType.let { ActionRegistry.getByType(it)?.category ?: "network" })
    }
    var selectedActionType by remember(initial) { mutableStateOf(initial.actionType) }
    var actionParams by remember(initial) { mutableStateOf(initial.params) }
    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    var toast by remember { mutableStateOf<ToastMessage?>(null) }
    var saveDone by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }


    val triggerDef = RULE_TRIGGERS.firstOrNull { it.type == selectedTrigger }

    /** 当前触发条件的参数是否可用；同时喂给 validate 与对应输入框的 isError。 */
    fun triggerError(): String? = when (selectedTrigger) {
        "traffic_total_reached" -> {
            val v = trafficGb.toDoubleOrNull()
            if (v == null || v <= 0) "流量阈值需为大于 0 的数字（单位 GB）" else null
        }
        "signal_below" -> {
            val v = rsrp.toIntOrNull()
            if (v == null || v < RSRP_MIN_DBM || v > RSRP_MAX_DBM)
                "RSRP 阈值需在 $RSRP_MIN_DBM ~ $RSRP_MAX_DBM dBm 之间（负值）" else null
        }
        "battery_below" -> {
            val v = batteryLevel.toIntOrNull()
            if (v == null || v !in 1..100) "电量阈值需为 1 ~ 100 的整数" else null
        }
        "network_type_changed" -> if (targetType.isBlank()) "请选择目标网络类型" else null
        else -> null
    }

    fun actionError(): String? = when {
        selectedActionType.isBlank() -> "请先选择要执行的动作"
        selectedActionType == "custom_shell" && actionParams["command"]?.content.isNullOrBlank() ->
            "自定义命令不能为空"
        else -> null
    }

    // 兜底值只在"理论上到不了"的路径上生效：向导保证走到末步时 triggerError() 已为 null。
    fun buildTriggerParams(): Map<String, JsonPrimitive> = when (selectedTrigger) {
        "traffic_total_reached" -> mapOf("thresholdBytes" to JsonPrimitive(
            (trafficGb.toDoubleOrNull()?.times(1024 * 1024 * 1024)?.toLong() ?: 1_073_741_824L)
        ))
        "signal_below" -> mapOf("rsrp" to JsonPrimitive(rsrp.toIntOrNull() ?: -110))
        "battery_below" -> mapOf("levelPercent" to JsonPrimitive(batteryLevel.toIntOrNull() ?: 20))
        "network_type_changed" -> mapOf("targetType" to JsonPrimitive(targetType))
        else -> emptyMap()
    }

    fun buildRule(): AutomationRule = initial.copy(
        name = name,
        triggerType = selectedTrigger,
        triggerParams = buildTriggerParams(),
        actionType = selectedActionType,
        params = actionParams,
        enabled = enabled
    )

    // 列表还没到就只给加载态：见 [TaskEditScreen]
    if (awaitingRule) {
        UfiScreenScaffold(title = "编辑规则", navController = navController, showBack = true) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                UfiLoadingIndicator()
            }
        }
        return
    }

    UfiScreenScaffold(
        title = if (isNew) "新建规则" else "编辑规则",
        navController = navController,
        showBack = true
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {

            UfiWizard(
                steps = listOf(
                    UfiWizardStep(
                        label = "基本信息",
                        heading = "给规则起个名字",
                        description = "名称只用于列表展示；留空时列表会显示「当 … 就 …」的摘要。"
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            EnableToggleRow(
                                title = "启用此规则",
                                subtitle = "规则创建后立即生效",
                                icon = Icons.Filled.FlashOn,
                                checked = enabled,
                                onCheckedChange = { enabled = it }
                            )
                            UfiDialogTextField(
                                label = "规则名称",
                                value = name,
                                onValueChange = { name = it },
                                placeholder = "如：流量超 1GB 关数据"
                            )
                        }
                    },
                    UfiWizardStep(
                        label = "触发条件",
                        heading = "什么情况下触发",
                        description = "选一个条件；除「断网」外都需要填一个阈值。",
                        validate = ::triggerError
                    ) {
                        val error = triggerError()
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                            // 5 项触发条件 → 公共 UfiOptionGrid 双栏网格（自动带按下缩放 + 选中过渡）
                            UfiOptionGrid(
                                options = RULE_TRIGGERS.map { t ->
                                    UfiOptionItem(
                                        value = t.type,
                                        label = t.name,
                                        leading = {
                                            Icon(
                                                imageVector = t.icon,
                                                contentDescription = null,
                                                tint = LocalResolvedPalette.current.accent,
                                                modifier = Modifier.size(Spacing.IconSizeSmall)
                                            )
                                        }
                                    )
                                },
                                selectedValue = selectedTrigger,
                                onSelect = { selectedTrigger = it },
                                columns = 2
                            )
                            triggerDef?.let {
                                Text(
                                    it.hint,
                                    style = UfiTextStyles.note,
                                    color = LocalResolvedPalette.current.textSecondary
                                )
                            }
                            // 当前触发条件的内联参数
                            when (selectedTrigger) {
                                "traffic_total_reached" ->
                                    UfiDialogTextField(
                                        label = "流量阈值 (GB)",
                                        value = trafficGb,
                                        onValueChange = { trafficGb = it },
                                        placeholder = "如 1",
                                        singleLine = true,
                                        isError = error != null,
                                        errorMessage = error
                                    )
                                "signal_below" ->
                                    UfiDialogTextField(
                                        label = "RSRP 阈值 (dBm，负值越小越差)",
                                        value = rsrp,
                                        onValueChange = { rsrp = it },
                                        placeholder = "如 -110",
                                        singleLine = true,
                                        isError = error != null,
                                        errorMessage = error
                                    )
                                "battery_below" ->
                                    UfiDialogTextField(
                                        label = "电量阈值 (%)，未充电时生效",
                                        value = batteryLevel,
                                        onValueChange = { batteryLevel = it },
                                        placeholder = "如 20",
                                        singleLine = true,
                                        isError = error != null,
                                        errorMessage = error
                                    )
                                "network_type_changed" ->
                                    UfiDialogField("变为目标网络类型") {
                                        UfiOptionGrid(
                                            options = NETWORK_TARGET_TYPES.map {
                                                UfiOptionItem(value = it, label = it)
                                            },
                                            selectedValue = targetType,
                                            onSelect = { targetType = it },
                                            columns = 3
                                        )
                                    }
                            }
                        }
                    },
                    UfiWizardStep(
                        label = "执行动作",
                        heading = "触发后做什么",
                        description = "先选分类，再选具体动作；部分动作还需要补一个参数。",
                        validate = ::actionError
                    ) {
                        ActionPickerSection(
                            selectedCategory = selectedCategory,
                            onCategoryChange = { selectedCategory = it },
                            selectedActionType = selectedActionType,
                            onActionChange = { selectedActionType = it },
                            params = actionParams,
                            onParamsChange = { actionParams = it },
                            commandValue = actionParams["command"]?.content ?: "",
                            onCommandChange = { actionParams = actionParams + ("command" to JsonPrimitive(it)) },
                            commandError = selectedActionType == "custom_shell" &&
                                actionParams["command"]?.content.isNullOrBlank()
                        )
                    },
                    UfiWizardStep(
                        label = "确认",
                        heading = "核对一遍再保存",
                        description = "需要改动时点「上一步」，或直接点上方步骤条跳到任意一步。"
                    ) {
                        val draft = buildRule()
                        val actionName = ActionRegistry.getByType(selectedActionType)?.name ?: selectedActionType
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            UfiWizardReviewCard(
                                title = "基本信息",
                                icon = Icons.Filled.AutoAwesome,
                                rows = listOf(
                                    UfiWizardReviewRow("规则名称", name.ifBlank { "未填写" }),
                                    UfiWizardReviewRow("创建后状态", if (enabled) "启用" else "停用"),
                                    UfiWizardReviewRow("摘要", "当 ${ruleTriggerSummary(draft)}，就 $actionName")
                                )
                            )
                            UfiWizardReviewCard(
                                title = "触发条件",
                                icon = triggerDef?.icon ?: Icons.Filled.Bolt,
                                rows = triggerReviewRows(
                                    triggerType = selectedTrigger,
                                    triggerName = triggerDef?.name ?: selectedTrigger,
                                    trafficGb = trafficGb,
                                    rsrp = rsrp,
                                    batteryLevel = batteryLevel,
                                    targetType = targetType
                                )
                            )
                            UfiWizardReviewCard(
                                title = "执行动作",
                                icon = ActionRegistry.getByType(selectedActionType)?.icon ?: Icons.Filled.Bolt,
                                rows = actionReviewRows(
                                    category = selectedCategory,
                                    actionType = selectedActionType,
                                    params = actionParams,
                                    command = actionParams["command"]?.content ?: ""
                                )
                            )
                        }
                    }
                ),
                currentStep = currentStep,
                onStepChange = { currentStep = it },
                onFinish = {
                    val rule = buildRule()
                    // 成功才 toast + 退页（理由同 TaskEditScreen.submit）
                    submitting = true
                    val onDone: (Boolean, String) -> Unit = { ok, msg ->
                        submitting = false
                        toast = ToastMessage(msg, if (ok) ToastType.SUCCESS else ToastType.ERROR)
                        if (ok) saveDone = true
                    }
                    if (isNew) {
                        viewModel.tools.createRule(rule, onDone)
                    } else {
                        viewModel.tools.updateRule(rule.id, rule, onDone)
                    }
                },
                finishText = if (isNew) "创建规则" else "保存修改",
                finishLoading = submitting,
                onStepBlocked = { toast = ToastMessage(it, ToastType.WARNING) }

            )
            UfiToastHost(toastMessage = toast, onDismiss = { toast = null })
        }
        LaunchedEffect(saveDone) { if (saveDone) navController.popBackStack() }
    }
}

/**
 * 「执行动作」选择块：分类 chips + 动作网格 + 动作参数。
 *
 * 2026-09-21：定时任务与条件规则原先各有一份**逐行相同**的实现（约 70 行 × 2），
 * 改引导式时合并到这里。两边唯一的差别是 custom_shell 的命令值存在哪：
 * 任务有独立的 `command` 字段，规则只存在 params 里 —— 由 [commandValue] / [onCommandChange] 表达。
 *
 * 顺带修掉一处原有的漏重置：原实现切换**分类**时只改 actionType，不重置 params，
 * 于是上一个动作的参数会残留在新动作的 params 里（后端按 key 取值，多余 key 被忽略，
 * 但"看不见的旧值"会跟着任务一起存盘）。现在切分类与切动作都落到同一套重置逻辑。
 */
@Composable
private fun ActionPickerSection(
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    selectedActionType: String,
    onActionChange: (String) -> Unit,
    params: Map<String, JsonPrimitive>,
    onParamsChange: (Map<String, JsonPrimitive>) -> Unit,
    commandValue: String,
    onCommandChange: (String) -> Unit,
    commandError: Boolean
) {
    val palette = LocalResolvedPalette.current
    val categoryActions = ActionRegistry.getByCategory(selectedCategory)

    /** 选中某个动作：同时把参数重置为该动作的默认值。 */
    fun selectAction(type: String) {
        onActionChange(type)
        onParamsChange(
            ActionRegistry.getByType(type)?.params?.associate { it.key to it.default } ?: emptyMap()
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
        // 分类 chips（横排，全屏宽度足够 4 个）
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
            modifier = Modifier.fillMaxWidth()
        ) {
            ActionRegistry.categories.forEach { (key, label) ->
                CategoryChip(
                    label = label,
                    selected = selectedCategory == key,
                    onClick = {
                        onCategoryChange(key)
                        selectAction(ActionRegistry.getByCategory(key).firstOrNull()?.type ?: "")
                    }
                )
            }
        }
        // 动作列表（公共 UfiOptionGrid 双栏网格，与分类 chips 视觉一脉相承）
        UfiOptionGrid(
            options = categoryActions.map { action ->
                UfiOptionItem(
                    value = action.type,
                    label = action.name,
                    leading = {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(Spacing.IconSizeSmall)
                        )
                    }
                )
            },
            selectedValue = selectedActionType,
            onSelect = { selectAction(it) },
            columns = 2
        )
        // 动作参数（按所选动作动态渲染，AnimatedContent 平滑过渡）
        AnimatedContent(
            targetState = selectedActionType,
            transitionSpec = {
                (fadeIn(tween(UfiMotion.Duration.Standard)) + slideInVertically { it / PARAM_SLIDE_DIVISOR })
                    .togetherWith(fadeOut(tween(UfiMotion.Duration.Swift)) + slideOutVertically { -it / PARAM_SLIDE_DIVISOR })
            },
            label = "actionParams"
        ) { actionType ->
            val def = ActionRegistry.getByType(actionType)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                def?.params?.forEach { param ->
                    when (param.type) {
                        ParamType.BOOLEAN -> {
                            val currentValue = params[param.key]?.content?.toBoolean()
                                ?: param.default.content.toBoolean()
                            UfiDialogSwitchField(param.label, currentValue) {
                                onParamsChange(params + (param.key to JsonPrimitive(it)))
                            }
                        }
                        ParamType.STRING -> {
                            if (param.key == "command") {
                                UfiDialogTextField(
                                    label = param.label,
                                    value = commandValue,
                                    onValueChange = onCommandChange,
                                    placeholder = "输入 Shell 命令",
                                    singleLine = false,
                                    isError = commandError,
                                    errorMessage = if (commandError) "命令不能为空" else null
                                )
                            } else param.options?.let { opts ->
                                val currentValue = params[param.key]?.content ?: param.default.content
                                UfiDialogField(param.label) {
                                    UfiOptionGrid(
                                        options = opts.map { (value, label) -> UfiOptionItem(value = value, label = label) },
                                        selectedValue = currentValue,
                                        onSelect = { onParamsChange(params + (param.key to JsonPrimitive(it))) },
                                        columns = opts.size.coerceAtMost(3)
                                    )
                                }
                            }
                        }
                        ParamType.INT -> {
                            param.options?.let { opts ->
                                val currentValue = params[param.key]?.content ?: param.default.content
                                UfiDialogField(param.label) {
                                    UfiOptionGrid(
                                        options = opts.map { (value, label) -> UfiOptionItem(value = value, label = label) },
                                        selectedValue = currentValue,
                                        onSelect = { onParamsChange(params + (param.key to JsonPrimitive(it))) },
                                        columns = opts.size.coerceAtMost(3)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 确认页「执行动作」卡的行：动作 + 分类 + 该动作的每个参数（取值翻成中文标签）。 */
private fun actionReviewRows(
    category: String,
    actionType: String,
    params: Map<String, JsonPrimitive>,
    command: String
): List<UfiWizardReviewRow> {
    val def = ActionRegistry.getByType(actionType)
    return buildList {
        add(UfiWizardReviewRow("动作", def?.name ?: actionType.ifBlank { "未选择" }))
        add(UfiWizardReviewRow(
            "分类",
            ActionRegistry.categories.firstOrNull { it.first == category }?.second ?: category
        ))
        def?.params?.forEach { param ->
            val raw = if (param.key == "command") command else params[param.key]?.content ?: param.default.content
            // options 是别的模块的 public 属性，智能转换不成立，先落成局部 val
            val options = param.options
            val shown = when {
                param.type == ParamType.BOOLEAN -> if (raw.toBoolean()) "开启" else "关闭"
                options != null -> options.firstOrNull { it.first == raw }?.second ?: raw
                raw.isBlank() -> "未填写"
                else -> raw
            }
            add(UfiWizardReviewRow(param.label, shown))
        }
    }
}

/** 确认页「触发条件」卡的行：条件名 + 该条件的阈值参数。 */
private fun triggerReviewRows(
    triggerType: String,
    triggerName: String,
    trafficGb: String,
    rsrp: String,
    batteryLevel: String,
    targetType: String
): List<UfiWizardReviewRow> = buildList {
    add(UfiWizardReviewRow("条件", triggerName))
    when (triggerType) {
        "traffic_total_reached" -> add(UfiWizardReviewRow("流量阈值", "$trafficGb GB"))
        "signal_below" -> add(UfiWizardReviewRow("RSRP 阈值", "$rsrp dBm"))
        "battery_below" -> add(UfiWizardReviewRow("电量阈值", "$batteryLevel %"))
        "network_type_changed" -> add(UfiWizardReviewRow("目标网络类型", targetType))
        "disconnect" -> add(UfiWizardReviewRow("参数", "无"))
    }
}

/**
 * 方案 A：启用开关单行卡片（图标 + 主标题 + 副说明 + 右侧 Switch），
 * 替代原先 RuleEditSection 包裹 UfiDialogSwitchField 造成的「卡片标题 + 内文 label」双层重复。
 * 图标底色/色调随开关状态 spring 过渡（开启=primary 浅底，关闭=surfaceVariant 中性）。
 */
@Composable
private fun EnableToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val iconBg by animateColorAsState(
        targetValue = if (checked) palette.accent.copy(alpha = 0.12f) else palette.surfaceMuted,
        animationSpec = UfiMotion.colorSwap()
    )
    val iconTint by animateColorAsState(
        targetValue = if (checked) palette.accent else palette.textSecondary,
        animationSpec = UfiMotion.colorSwap()
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(UfiCardDefaults.mediumShape)
            .background(palette.cardBg)
            .border(1.dp, palette.divider, UfiCardDefaults.mediumShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(UfiCardDefaults.shape)
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = UfiTextStyles.cardTitle,
                color = palette.textPrimary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
        }
        // 2026-08-31：M3 Switch → 公共 UfiSwitch（全站开关统一 42×24dp）
        UfiSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ──────────── 校验口径与常量 ────────────

/**
 * RSRP 阈值的可填下界（dBm）。
 *
 * -140 是 3GPP 为 LTE/NR 定义的 RSRP 量程下限（RSRP_00），再低的数字没有物理含义。
 */
private const val RSRP_MIN_DBM = -140

/**
 * RSRP 阈值的可填上界（dBm）。
 *
 * -40 是量程上限（贴着基站也到不了 0）。卡住上界是为了拦下"填了正数"这类笔误 ——
 * `rsrp <= threshold` 在正数阈值下会**永真**，规则每轮都触发。
 */
private const val RSRP_MAX_DBM = -40

/** 「网络跳变」的目标类型候选。与 core 侧 ConditionEngine 比较的网络类型字符串一致。 */
private val NETWORK_TARGET_TYPES = listOf("4G", "5G", "3G", "2G", "WiFi")

/** 动作参数切换时的纵向位移量 = 容器高度 / 8（沿用改造前的取值，观感不变）。 */
private const val PARAM_SLIDE_DIVISOR = 8


