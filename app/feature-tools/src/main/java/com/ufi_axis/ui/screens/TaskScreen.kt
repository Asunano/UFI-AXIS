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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.tasksState.collectAsState()
    val palette = LocalResolvedPalette.current
    var selectedTab by remember { mutableStateOf(0) }   // 0=定时任务 1=条件规则
    var viewingTaskLogs by remember { mutableStateOf<ScheduledTask?>(null) }
    // 条件规则 / 定时任务编辑均走全屏路由（弹窗字段多易被裁切，2026-08-18 改为全屏）。
    var viewingRuleLogs by remember { mutableStateOf<AutomationRule?>(null) }

    LaunchedEffect(Unit) {
        viewModel.tools.loadTaskList()
        viewModel.tools.loadRuleList()
    }

    UfiScreenScaffold(title = "自动化", navController = navController, showBack = true,
        actions = {
            val hasItems = if (selectedTab == 0) state.tasks.isNotEmpty() else state.rules.isNotEmpty()
            if (hasItems) {
                IconButton(onClick = { if (selectedTab == 0) viewModel.tools.clearTasks() else viewModel.tools.clearRules() }) {
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
                if (state.tasks.isEmpty() && !state.isLoading) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .background(palette.accentContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Schedule, null, modifier = Modifier.size(48.dp), tint = palette.accent)
                            }
                            Spacer(Modifier.height(20.dp))
                            Text(
                                "暂无定时任务",
                                style = UfiTextStyles.screenTitle,
                                color = palette.textPrimary
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "点击右上角 + 创建第一个任务",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textSecondary
                            )
                            Spacer(Modifier.height(24.dp))
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
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.tasks, key = { it.id }) { task ->
                            TaskCard(task = task,
                                onEdit = { navController.navigate("detail/task-edit?id=${it.id}") },
                                onDelete = { viewModel.tools.deleteTask(it.id) },
                                onToggle = { viewModel.tools.updateTask(it.id, it.copy(enabled = !it.enabled)) },
                                onShowLogs = { viewModel.tools.loadTaskLogs(it.id); viewingTaskLogs = it }
                            )
                        }
                    }
                }
            } else {
                // ── 条件规则（当…就…）──
                if (state.rules.isEmpty() && !state.isLoading) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .background(palette.accentContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(48.dp), tint = palette.accent)
                            }
                            Spacer(Modifier.height(20.dp))
                            Text(
                                "暂无自动化规则",
                                style = UfiTextStyles.screenTitle,
                                color = palette.textPrimary
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "「当 流量达标 / 网络跳变 / 电量低 … 就 执行动作」",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textSecondary
                            )
                            Spacer(Modifier.height(24.dp))
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
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.rules, key = { it.id }) { rule ->
                            RuleCard(rule = rule,
                                onEdit = { navController.navigate("detail/rule-edit?id=${it.id}") },
                                onDelete = { viewModel.tools.deleteRule(it.id) },
                                onToggle = { viewModel.tools.updateRule(it.id, it.copy(enabled = !it.enabled)) },
                                onShowLogs = { viewModel.tools.loadRuleLogs(it.id); viewingRuleLogs = it }
                            )
                        }
                    }
                }
            }
        }
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
private fun scheduleSummary(task: ScheduledTask): String {
    val cron = task.cron
    val scheduleType = task.scheduleType
    val sv = if (cron != null && scheduleType != null)
        CronParser.toScheduleValue(cron, scheduleType)
    else
        ScheduleValue(
            scheduleType = if (task.repeatDaily) SchedulePreset.DAILY.type else "once",
            hour = task.hour,
            minute = task.minute
        )
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
            dismissText = null
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

// ============ 定时任务编辑器（全屏 Route 页面） ============
// 2026-08-18: 原 TaskEditDialog 弹窗字段多（名称+分类+动作+多参数+Shell+时分+重复+启用），
// 受 UfiScrollableDialog 82% 屏高约束，启用开关/CTA 易被裁切。改为全屏页面 detail/task-edit?id=...，
// 由 buildAppScreens 路由映射装配。复用公共组件 UfiScreenScaffold / UfiOptionGrid / UfiDialogTextField /
// UfiDialogSwitchField / CategoryChip / ScheduleSelector / RuleEditSection，公共组件 0 改动（F24 红线）。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    taskId: String
) {
    val state by viewModel.tasksState.collectAsState()
    val isNew = taskId.isBlank()
    val initial: ScheduledTask = remember(taskId, state.tasks) {
        if (isNew) ScheduledTask()
        else state.tasks.firstOrNull { it.id == taskId } ?: ScheduledTask(id = taskId)
    }

    var name by remember { mutableStateOf(initial.name) }
    var selectedCategory by remember { mutableStateOf(initial.actionType.let { ActionRegistry.getByType(it)?.category ?: "network" }) }
    var selectedActionType by remember { mutableStateOf(initial.actionType) }
    var actionParams by remember { mutableStateOf(initial.params) }
    var command by remember { mutableStateOf(initial.command) }
    // 执行周期（时间触发）：单一 ScheduleValue 承载 8 preset + 自定义 cron（v9 扩展）
    var scheduleValue by remember {
        mutableStateOf(if (isNew) ScheduleValue.DEFAULT else initial.toScheduleValue())
    }
    var enabled by remember { mutableStateOf(initial.enabled) }

    val categoryActions = ActionRegistry.getByCategory(selectedCategory)

    UfiScreenScaffold(
        title = if (isNew) "新建任务" else "编辑任务",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(
                onClick = {
                    if (selectedActionType.isNotBlank() && (selectedActionType != "custom_shell" || command.isNotBlank())) {
                        val sv = scheduleValue
                        // 由结构化选择生成 cron / repeatDaily / scheduleType（v9 时间触发扩展）
                        // 向后兼容：daily 仍走旧 repeatDaily 路径（scheduleType/cron 留空），旧任务可无损回存
                        val (cron: String?, repeatDailyOut: Boolean, scheduleTypeOut: String?) = when (sv.scheduleType) {
                            "daily" -> Triple(null, true, null) // 旧路径：保留 legacy 字段
                            "once" -> Triple(null, false, "once")
                            "custom" -> Triple(sv.customCron.takeIf { CronParser.isValid(it) }, true, "custom")
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
                        if (isNew) viewModel.tools.createTask(task)
                        else viewModel.tools.updateTask(task.id, task)
                        navController.popBackStack()
                    }
                }
            ) {
                Icon(Icons.Default.Check, "保存")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. 启用此任务（开关置顶 · 单行卡片，消除双层 label）
            EnableToggleRow(
                title = "启用此任务",
                subtitle = "创建后立即生效，无需手动开启",
                icon = Icons.Filled.FlashOn,
                checked = enabled,
                onCheckedChange = { enabled = it }
            )

            // 2. 任务名称
            RuleEditSection(title = "任务名称") {
                // 2026-08-31：裸 OutlinedTextField（自带 colorScheme 边框配色）→ 公共 UfiTextField
                UfiTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "",
                    placeholder = "如：每天 8 点重启"
                )
            }

            // 3. 执行动作（分类 + 动作双栏选择 + 参数）
            RuleEditSection(title = "执行动作") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 分类 chips（横排，全屏宽度足够 4 个）
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ActionRegistry.categories.forEach { (key, label) ->
                            CategoryChip(
                                label = label,
                                selected = selectedCategory == key,
                                onClick = {
                                    selectedCategory = key
                                    selectedActionType = ActionRegistry.getByCategory(key).firstOrNull()?.type ?: ""
                                }
                            )
                        }
                    }
                    // 动作列表（复用公共 UfiOptionGrid 双栏网格，与分类 chips 视觉一脉相承，自动获得按下 0.97 缩放 + 选中过渡）
                    UfiOptionGrid(
                        options = categoryActions.map { action ->
                            UfiOptionItem(
                                value = action.type,
                                label = action.name,
                                leading = {
                                    Icon(
                                        imageVector = action.icon,
                                        contentDescription = null,
                                        tint = LocalResolvedPalette.current.accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        },
                        selectedValue = selectedActionType,
                        onSelect = { type ->
                            selectedActionType = type
                            actionParams = ActionRegistry.getByType(type)?.params
                                ?.associate { it.key to it.default } ?: emptyMap()
                        },
                        columns = 2
                    )
                    // 动作参数（按所选动作动态渲染，AnimatedContent 平滑过渡）
                    AnimatedContent(
                        targetState = selectedActionType,
                        transitionSpec = {
                            (fadeIn(tween(UfiMotion.Duration.Standard)) + slideInVertically { it / 8 })
                                .togetherWith(fadeOut(tween(UfiMotion.Duration.Swift)) + slideOutVertically { -it / 8 })
                        },
                        label = "taskActionParams"
                    ) { actionType ->
                        val def = ActionRegistry.getByType(actionType)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            def?.params?.forEach { param ->
                                when (param.type) {
                                    ParamType.BOOLEAN -> {
                                        val currentValue = actionParams[param.key]?.content?.toBoolean()
                                            ?: (param.default.content.toBoolean())
                                        UfiDialogSwitchField(param.label, currentValue) {
                                            actionParams = actionParams + (param.key to JsonPrimitive(it))
                                        }
                                    }
                                    ParamType.STRING -> {
                                        if (param.key == "command") {
                                            UfiDialogTextField(
                                                label = param.label,
                                                value = command,
                                                onValueChange = {
                                                    command = it
                                                    actionParams = actionParams + (param.key to JsonPrimitive(it))
                                                },
                                                placeholder = "输入 Shell 命令",
                                                singleLine = false
                                            )
                                        } else param.options?.let { opts ->
                                            val currentValue = actionParams[param.key]?.content ?: param.default.content
                                            UfiDialogField(param.label) {
                                                UfiOptionGrid(
                                                    options = opts.map { (value, label) -> UfiOptionItem(value = value, label = label) },
                                                    selectedValue = currentValue,
                                                    onSelect = { actionParams = actionParams + (param.key to JsonPrimitive(it)) },
                                                    columns = opts.size.coerceAtMost(3)
                                                )
                                            }
                                        }
                                    }
                                    ParamType.INT -> {
                                        param.options?.let { opts ->
                                            val currentValue = actionParams[param.key]?.content ?: param.default.content
                                            UfiDialogField(param.label) {
                                                UfiOptionGrid(
                                                    options = opts.map { (value, label) -> UfiOptionItem(value = value, label = label) },
                                                    selectedValue = currentValue,
                                                    onSelect = { actionParams = actionParams + (param.key to JsonPrimitive(it)) },
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

            // 4+5. 执行周期（v9：合并「执行时间」+「重复」为单一 ScheduleSelector 公共组件）
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
        }
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
        UfiDialogActions(onDismiss = onDismiss, onConfirm = onDismiss, confirmText = "关闭", dismissText = null)
    }
}

// ============ 条件规则编辑器（全屏 Route 页面） ============
// 2026-08-18：弹窗字段太多（名称+触发+触发参数+动作+动作参数+启用），
// UfiScrollableDialog 即便带滚动也仍被横竖挤（5 个触发 chip 横排被裁、Shell 命令框被裁）。
// 改为全屏页面 detail/rule-edit?id=…，由 [com.ufi_axis.app.navigation.buildAppScreens] 路由映射装配。
// 复用公共组件 UfiScreenScaffold / UfiOptionGrid / UfiDialogTextField / UfiDialogSwitchField / CategoryChip，
// 公共组件 0 改动（F24 红线）。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    ruleId: String
) {
    val state by viewModel.tasksState.collectAsState()
    val isNew = ruleId.isBlank()
    // id 命中取已有规则；未命中或为空=新建。后端会自动给空 id 生成 8 字符 id（POST /rules）。
    val initial: AutomationRule = remember(ruleId, state.rules) {
        if (isNew) AutomationRule()
        else state.rules.firstOrNull { it.id == ruleId } ?: AutomationRule(id = ruleId)
    }

    // 表单 state —— 规则未变时跟随 initial 编辑态
    var name by remember { mutableStateOf(initial.name) }
    var selectedTrigger by remember { mutableStateOf(initial.triggerType) }
    var trafficGb by remember {
        mutableStateOf(initial.triggerParams["thresholdBytes"]?.content?.toLongOrNull()
            ?.let { it / (1024.0 * 1024 * 1024) }
            ?.let { String.format(Locale.US, "%.2f", it) } ?: "1.00")
    }
    var rsrp by remember { mutableStateOf(initial.triggerParams["rsrp"]?.content ?: "-110") }
    var batteryLevel by remember { mutableStateOf(initial.triggerParams["levelPercent"]?.content ?: "20") }
    var targetType by remember { mutableStateOf(initial.triggerParams["targetType"]?.content ?: "4G") }
    var selectedCategory by remember {
        mutableStateOf(initial.actionType.let { ActionRegistry.getByType(it)?.category ?: "network" })
    }
    var selectedActionType by remember { mutableStateOf(initial.actionType) }
    var actionParams by remember { mutableStateOf(initial.params) }
    var enabled by remember { mutableStateOf(initial.enabled) }

    val categoryActions = ActionRegistry.getByCategory(selectedCategory)

    fun buildTriggerParams(): Map<String, JsonPrimitive> = when (selectedTrigger) {
        "traffic_total_reached" -> mapOf("thresholdBytes" to JsonPrimitive(
            (trafficGb.toDoubleOrNull()?.times(1024 * 1024 * 1024)?.toLong() ?: 1_073_741_824L)
        ))
        "signal_below" -> mapOf("rsrp" to JsonPrimitive(rsrp.toIntOrNull() ?: -110))
        "battery_below" -> mapOf("levelPercent" to JsonPrimitive(batteryLevel.toIntOrNull() ?: 20))
        "network_type_changed" -> mapOf("targetType" to JsonPrimitive(targetType))
        else -> emptyMap()
    }

    UfiScreenScaffold(
        title = if (isNew) "新建规则" else "编辑规则",
        navController = navController,
        showBack = true,
        actions = {
            // 保存按钮（TopAppBar 右上）
            IconButton(
                onClick = {
                    if (selectedActionType.isNotBlank()) {
                        val rule = initial.copy(
                            name = name,
                            triggerType = selectedTrigger,
                            triggerParams = buildTriggerParams(),
                            actionType = selectedActionType,
                            params = actionParams,
                            enabled = enabled
                        )
                        if (isNew) viewModel.tools.createRule(rule)
                        else viewModel.tools.updateRule(rule.id, rule)
                        navController.popBackStack()
                    }
                }
            ) {
                Icon(Icons.Default.Check, "保存")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. 启用此规则（开关置顶 · 单行卡片，消除双层 label）
            EnableToggleRow(
                title = "启用此规则",
                subtitle = "规则创建后立即生效",
                icon = Icons.Filled.FlashOn,
                checked = enabled,
                onCheckedChange = { enabled = it }
            )

            // 2. 规则名称
            RuleEditSection(title = "规则名称") {
                // 2026-08-31：裸 OutlinedTextField（自带 colorScheme 边框配色）→ 公共 UfiTextField
                UfiTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "",
                    placeholder = "如：流量超 1GB 关数据"
                )
            }

            // 3. 当…触发条件（2 列 grid，每项带 hint）
            RuleEditSection(title = "当… 触发条件") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 5 项触发条件 → 复用公共 UfiOptionGrid 双栏网格（40dp 标准 cell，扁平轻盈，自动获得按下 0.97 缩放 + 选中过渡）
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
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        },
                        selectedValue = selectedTrigger,
                        onSelect = { selectedTrigger = it },
                        columns = 2
                    )
                    // 当前触发条件的内联参数
                    when (selectedTrigger) {
                        "traffic_total_reached" ->
                            UfiDialogTextField(
                                label = "流量阈值 (GB)",
                                value = trafficGb,
                                onValueChange = { trafficGb = it },
                                placeholder = "如 1",
                                singleLine = true
                            )
                        "signal_below" ->
                            UfiDialogTextField(
                                label = "RSRP 阈值 (dBm，负值越小越差)",
                                value = rsrp,
                                onValueChange = { rsrp = it },
                                placeholder = "如 -110",
                                singleLine = true
                            )
                        "battery_below" ->
                            UfiDialogTextField(
                                label = "电量阈值 (%)，未充电时生效",
                                value = batteryLevel,
                                onValueChange = { batteryLevel = it },
                                placeholder = "如 20",
                                singleLine = true
                            )
                        "network_type_changed" ->
                            UfiDialogField("变为目标网络类型") {
                                UfiOptionGrid(
                                    options = listOf("4G", "5G", "3G", "2G", "WiFi").map {
                                        UfiOptionItem(value = it, label = it)
                                    },
                                    selectedValue = targetType,
                                    onSelect = { targetType = it },
                                    columns = 3
                                )
                            }
                        "disconnect" -> Text(
                            "蜂窝网络断开的瞬间触发，无需额外参数。",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalResolvedPalette.current.textSecondary
                        )
                    }
                }
            }

            // 4. 就… 执行动作
            RuleEditSection(title = "就… 执行动作") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 分类 chips（横排，不裁——全屏宽度足够 4 个）
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ActionRegistry.categories.forEach { (key, label) ->
                            CategoryChip(
                                label = label,
                                selected = selectedCategory == key,
                                onClick = {
                                    selectedCategory = key
                                    selectedActionType = ActionRegistry.getByCategory(key).firstOrNull()?.type ?: ""
                                }
                            )
                        }
                    }
                    // 动作列表（复用公共 UfiOptionGrid 双栏网格，与分类 chips 视觉一脉相承，自动获得按下 0.97 缩放 + 选中过渡）
                    UfiOptionGrid(
                        options = categoryActions.map { action ->
                            UfiOptionItem(
                                value = action.type,
                                label = action.name,
                                leading = {
                                    Icon(
                                        imageVector = action.icon,
                                        contentDescription = null,
                                        tint = LocalResolvedPalette.current.accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        },
                        selectedValue = selectedActionType,
                        onSelect = { type ->
                            selectedActionType = type
                            actionParams = ActionRegistry.getByType(type)?.params
                                ?.associate { it.key to it.default } ?: emptyMap()
                        },
                        columns = 2
                    )
                    // 动作参数（按所选动作动态渲染，AnimatedContent 平滑过渡）
                    AnimatedContent(
                        targetState = selectedActionType,
                        transitionSpec = {
                            (fadeIn(tween(UfiMotion.Duration.Standard)) + slideInVertically { it / 8 })
                                .togetherWith(fadeOut(tween(UfiMotion.Duration.Swift)) + slideOutVertically { -it / 8 })
                        },
                        label = "ruleActionParams"
                    ) { actionType ->
                        val def = ActionRegistry.getByType(actionType)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            def?.params?.forEach { param ->
                                when (param.type) {
                                    ParamType.BOOLEAN -> {
                                        val currentValue = actionParams[param.key]?.content?.toBoolean()
                                            ?: (param.default.content.toBoolean())
                                        UfiDialogSwitchField(param.label, currentValue) {
                                            actionParams = actionParams + (param.key to JsonPrimitive(it))
                                        }
                                    }
                                    ParamType.STRING -> {
                                        if (param.key == "command") {
                                            UfiDialogTextField(
                                                label = param.label,
                                                value = actionParams[param.key]?.content ?: "",
                                                onValueChange = {
                                                    actionParams = actionParams + (param.key to JsonPrimitive(it))
                                                },
                                                placeholder = "输入内容",
                                                singleLine = false
                                            )
                                        } else param.options?.let { opts ->
                                            val currentValue = actionParams[param.key]?.content
                                                ?: param.default.content
                                            UfiDialogField(param.label) {
                                                UfiOptionGrid(
                                                    options = opts.map { (value, label) ->
                                                        UfiOptionItem(value = value, label = label)
                                                    },
                                                    selectedValue = currentValue,
                                                    onSelect = {
                                                        actionParams = actionParams + (param.key to JsonPrimitive(it))
                                                    },
                                                    columns = opts.size.coerceAtMost(3)
                                                )
                                            }
                                        }
                                    }
                                    ParamType.INT -> {
                                        param.options?.let { opts ->
                                            val currentValue = actionParams[param.key]?.content
                                                ?: param.default.content
                                            UfiDialogField(param.label) {
                                                UfiOptionGrid(
                                                    options = opts.map { (value, label) ->
                                                        UfiOptionItem(value = value, label = label)
                                                    },
                                                    selectedValue = currentValue,
                                                    onSelect = {
                                                        actionParams = actionParams + (param.key to JsonPrimitive(it))
                                                    },
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
        }
    }
}

/** 全屏编辑器段落容器（与详情页 segment card 视觉一致）。 */
@Composable
private fun RuleEditSection(title: String, content: @Composable () -> Unit) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UfiCardDefaults.mediumShape)
            .background(palette.cardBg)
            .border(1.dp, palette.divider, UfiCardDefaults.mediumShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            style = UfiTextStyles.cardTitle,
            color = palette.textPrimary
        )
        content()
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

