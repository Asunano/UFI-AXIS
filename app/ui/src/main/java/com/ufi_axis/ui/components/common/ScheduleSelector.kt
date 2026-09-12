package com.ufi_axis.ui.components.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import com.ufi_axis.ui.animation.rememberUfiPressScale
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ufi_axis_core.util.CronParser
import com.ufi_axis_core.util.CronParser.SchedulePreset
import com.ufi_axis_core.util.CronParser.ScheduleValue
import java.util.Calendar
import com.ufi_axis.ui.theme.UfiMotion

/**
 * 定时任务「执行周期」选择器（公共组件，v9 时间触发扩展）。
 *
 * 单卡片结构（视觉对齐 [RuleEditSection]）：
 * 1. 类型下拉：8 选项网格（仅一次/每天/每周/每月/每 N 日/每 N 时/每 N 分/自定义；
 *    v10 移除「每 N 秒」，5 段 cron 无秒字段且后端 TaskScheduler 不支持秒级触发）
 * 2. 基于类型动态切换条件输入（[AnimatedContent]）
 * 3. 底部常驻「下次执行」预览行（由 [CronParser.nextAfter] 计算，单行）
 *
 * 回调 [onChange] 始终回传完整的 [ScheduleValue]，父级据此写回 ScheduledTask 的
 * scheduleType/cron/scheduleParams/hour/minute/repeatDaily。
 */
@Composable
fun ScheduleSelector(
    scheduleType: String?,
    hour: Int,
    minute: Int,
    weekDays: Set<Int>,
    monthDays: Set<Int>,
    intervalN: Int,
    customCron: String,
    onChange: (ScheduleValue) -> Unit,
    modifier: Modifier = Modifier
) {
    // 2026-09-01：配色源由 MaterialTheme.colorScheme 换成 palette（公共层不再读 colorScheme）。
    // colorScheme 本身就是 Theme.kt 从 palette 派生的，本次替换为等价改写，观感不变。
    val palette = LocalResolvedPalette.current
    val state = remember {
        mutableStateOf(
            ScheduleValue(
                scheduleType = scheduleType ?: SchedulePreset.DAILY.type,
                hour = hour,
                minute = minute,
                weekDays = weekDays,
                monthDays = monthDays,
                intervalN = intervalN,
                customCron = customCron
            )
        )
    }

    fun commit(v: ScheduleValue) {
        state.value = v
        onChange(v)
    }

    val previewCron = remember(state.value) {
        CronParser.previewCron(
            state.value.scheduleType,
            state.value.hour,
            state.value.minute,
            state.value.weekDays,
            state.value.monthDays,
            state.value.intervalN,
            state.value.customCron
        )
    }
    val previewText = remember(previewCron, state.value.scheduleType) {
        when {
            previewCron == null -> "cron 无效"
            else -> {
                val next = CronParser.nextAfter(previewCron, System.currentTimeMillis())
                if (next == null) "无可用执行时间" else "下次执行: ${formatNextTime(next, System.currentTimeMillis())}"
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(UfiCardDefaults.mediumShape)
            .background(palette.cardBg)
            .border(1.dp, palette.divider, UfiCardDefaults.mediumShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "执行周期",
            style = UfiTextStyles.cardTitle,
            color = palette.textPrimary
        )

        // ① 类型选择（9 选项，3 列网格，复用公共 UfiOptionGrid）
        UfiOptionGrid(
            options = SCHEDULE_OPTIONS,
            selectedValue = state.value.scheduleType,
            onSelect = { type -> commit(state.value.copy(scheduleType = type)) },
            columns = 3
        )

        // ② 动态条件输入
        AnimatedContent(
            targetState = state.value.scheduleType,
            transitionSpec = {
                (fadeIn(tween(UfiMotion.Duration.Quick)) togetherWith fadeOut(tween(UfiMotion.Duration.Micro)))
            },
            label = "scheduleCondition"
        ) { type ->
            when (type) {
                "daily", "once" -> TimeRow(
                    hour = state.value.hour,
                    minute = state.value.minute,
                    onHour = { commit(state.value.copy(hour = it)) },
                    onMinute = { commit(state.value.copy(minute = it)) }
                )

                "weekly" -> WeekdayPicker(
                    selected = state.value.weekDays,
                    onToggle = { day ->
                        val set = if (state.value.weekDays.contains(day)) state.value.weekDays - day
                        else state.value.weekDays + day
                        commit(state.value.copy(weekDays = set))
                    },
                    hour = state.value.hour,
                    minute = state.value.minute,
                    onHour = { commit(state.value.copy(hour = it)) },
                    onMinute = { commit(state.value.copy(minute = it)) }
                )

                "monthly" -> MonthDayPicker(
                    selected = state.value.monthDays,
                    onToggle = { d ->
                        val set = if (state.value.monthDays.contains(d)) state.value.monthDays - d
                        else state.value.monthDays + d
                        commit(state.value.copy(monthDays = set))
                    },
                    hour = state.value.hour,
                    minute = state.value.minute,
                    onHour = { commit(state.value.copy(hour = it)) },
                    onMinute = { commit(state.value.copy(minute = it)) }
                )

                "every_n_days" -> IntervalRow(
                    label = "每",
                    unit = "日",
                    value = state.value.intervalN,
                    range = (1..30),
                    onValue = { commit(state.value.copy(intervalN = it)) },
                    hour = state.value.hour,
                    minute = state.value.minute,
                    onHour = { commit(state.value.copy(hour = it)) },
                    onMinute = { commit(state.value.copy(minute = it)) },
                    showTime = true
                )

                // 每 N 时：cron = 0 */n * * *，只用到分钟（固定 0），小时/分钟选择器无效，故不显示。
                "every_n_hours" -> IntervalRow(
                    label = "每",
                    unit = "时",
                    value = state.value.intervalN,
                    range = (1..23),
                    onValue = { commit(state.value.copy(intervalN = it)) },
                    hour = state.value.hour,
                    minute = state.value.minute,
                    onHour = { commit(state.value.copy(hour = it)) },
                    onMinute = { commit(state.value.copy(minute = it)) },
                    showTime = false
                )

                // 每 N 分：cron = */n * * * *，不依赖小时/分钟，故不显示时间选择器。
                "every_n_minutes" -> IntervalRow(
                    label = "每",
                    unit = "分",
                    value = state.value.intervalN,
                    range = (1..59),
                    onValue = { commit(state.value.copy(intervalN = it)) },
                    hour = state.value.hour,
                    minute = state.value.minute,
                    onHour = { commit(state.value.copy(hour = it)) },
                    onMinute = { commit(state.value.copy(minute = it)) },
                    showTime = false
                )

                "custom" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.value.customCron,
                        onValueChange = { commit(state.value.copy(customCron = it.trim())) },
                        placeholder = { Text("分 时 日 月 周（如 30 8 * * 1-5）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.accent,
                            // 2026-09-04（P3）注释更正：桥接层已把 colorScheme.outline 从
                            // palette.textSecondary 改接到 palette.inputBorder，这里显式写的
                            // textSecondary 于是**不再等价于 outline**，成了全仓唯一用中灰实色描边的
                            // 输入框。本次只改桥接层、不动观感；要与其余输入框一致就换成
                            // palette.inputBorder（12%），留作后续 UI 一致性收敛项。
                            unfocusedBorderColor = palette.textSecondary
                        )
                    )
                    Text(
                        "语法：分(0-59) 时(0-23) 日(1-31) 月(1-12) 周(0-7，0/7=周日)。支持 * , - / 与 */N 步进。",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                }
            }
        }

        // ③ 预览行（常驻）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(UfiCardDefaults.shape)
                .background(palette.surfaceMuted.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = palette.accent
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "⏱ $previewText",
                style = MaterialTheme.typography.labelMedium,
                color = palette.textSecondary
            )
        }
    }
}

// ──────────── 子组件 ────────────

private val SCHEDULE_OPTIONS = SchedulePreset.entries.map { preset ->
    UfiOptionItem(value = preset.type, label = preset.label)
}

@Composable
private fun TimeRow(
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // 2026-09-04（P4e 下拉收敛）：原为本文件私有的 TimeField，现已合并进公共 [UfiDropdown]
        // （本组件的观感就是合并基准）。"%02d" 补零由 optionLabel 承担，原 format 形参因此消失。
        UfiDropdown(
            selectedValue = hour,
            options = (0..23).toList(),
            onValueSelected = onHour,
            modifier = Modifier.weight(1f),
            unitSuffix = "小时",
            optionLabel = { "%02d".format(it) }
        )
        UfiDropdown(
            selectedValue = minute,
            options = (0..59).toList(),
            onValueSelected = onMinute,
            modifier = Modifier.weight(1f),
            unitSuffix = "分钟",
            optionLabel = { "%02d".format(it) }
        )
    }
}

@Composable
private fun WeekdayPicker(
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val days = listOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { (v, label) ->
                TagCell(
                    value = label,
                    selected = selected.contains(v),
                    onClick = { onToggle(v) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        TimeRow(hour, minute, onHour, onMinute)
    }
}

/**
 * 统一的标签单元（星期 / 每月日期共用）：
 * - Box contentAlignment = Center 保证数字/单字精确居中（两位数如 10-31 与单字视觉重心一致）
 * - 按下缩放至 [UfiMotion.PressScale.Cell]（0.94）、选中态 1.04f 弹跳（Spring.DampingRatioMediumBouncy）；
 *   2026-09-04（P2f）按压缩放改由 [rememberUfiPressScale] 编排，短按也看得见（原因见
 *   `ui/animation/PressFeedback.kt` 文件头）
 * - 背景/文字色 tween 过渡；v12 移除选中态 ✓ 图标，选中态仅靠底色 + 橙字 + 加粗区分，
 *   文本因此始终精确居中（此前 ✓ 出现会把数字挤向左侧造成视觉抖动）
 */
@Composable
private fun TagCell(
    value: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    // 按下反馈：轻微收缩
    // 2026-09-04（P2d）：0.94 → UfiMotion.PressScale.Cell（值不变）。7 列密排日期格是全站
    // 面积最小的一类点选目标（FAB 除外），按"面积越小缩得越多"占 Cell 档。
    //
    // 2026-09-04（P2f 短按看不见）：原为 animateFloatAsState(if (isPressed) …)。
    // 为什么原来短按看不见：那是"跟随目标值"的动画，抬手瞬间目标翻回 1f 就被就地拉回；
    // Cell 档落差 0.06 配 tween(120)，一帧只走完约 5%（scale ≈ 0.997），在 36dp 的格子上
    // 不到半个像素；排程面板本身在可滚动弹窗里，Press 常与 Release 同帧到达，可用时长为 0。
    // 现在怎么保证：rememberUfiPressScale 事件驱动编排 + 最短保持 Duration.Micro（120ms），
    // 抬手时先补播完"缩到位"再弹回。
    //
    // 这里用返回 State 的 rememberUfiPressScale 而不是 Modifier.ufiPressScale：本格子要把
    // 按压缩放与下面选中态的 popScale **相乘**后一起交给同一个 graphicsLayer，
    // 拆成两层 graphicsLayer 会各自建图层、且选中弹跳与按压回弹的复合观感会变。
    val scale by rememberUfiPressScale(
        interactionSource = interactionSource,
        pressedScale = UfiMotion.PressScale.Cell,
        spec = tween(UfiMotion.Duration.Micro)
    )
    // 选中态数字弹跳：1.0 → 1.04（bouncy 过冲后落定）
    // v12：由 1.08f 降一档到 1.04f —— 移除 ✓ 后选中反馈已由底色/字重承担，
    // 过大的弹跳在 7 列密排网格里显得夸张且易与相邻格重叠。
    val popScale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 1f,
        animationSpec = UfiMotion.tagPop(),
        label = "tagPop"
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) palette.accent.copy(alpha = 0.85f) else palette.surfaceMuted.copy(alpha = 0.4f),
        animationSpec = tween(UfiMotion.Duration.Standard),
        label = "tagBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) palette.onAccent else palette.textSecondary,
        animationSpec = tween(UfiMotion.Duration.Standard),
        label = "tagFg"
    )
    Box(
        modifier = modifier
            .height(36.dp)
            .graphicsLayer {
                scaleX = scale * popScale
                scaleY = scale * popScale
            }
            .clip(UfiCardDefaults.subtleShape)
            .background(containerColor)
            .border(1.dp, if (selected) palette.accent else palette.divider, UfiCardDefaults.subtleShape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = value,
            style = UfiTextStyles.label.copy(
                fontWeight = if (selected) UfiWeight.Emphasis else UfiWeight.Medium
            ),
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

/**
 * 每月日期选择（v11 轻盈化：改用 [TagCell] 统一样式）：
 * 7 列月历网格，每格宽度自适应（weight(1f)）、高度固定 36dp，
 * 两位数自然居中不折行；末周不足 7 格时用 Spacer 占位保持对齐。
 * 保留多日选择能力（onToggle 单日切换），选中项通过 [TagCell] 高亮 primary。
 */
@Composable
private fun MonthDayPicker(
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit
) {
    val palette = LocalResolvedPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "每月",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPrimary
            )
            Text(
                if (selected.isEmpty()) "（请选择日期）"
                else "已选 ${selected.size} 日：${selected.sorted().joinToString("、")}",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary
            )
        }
        // 7 列月历网格：每格宽度自适应（weight(1f)），高度 36dp，
        // 两位数自然居中，不再因写死宽度而折行成 "2/0"、"2/1"…
        val weeks = (1..31).chunked(7)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            weeks.forEach { week ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    week.forEach { d ->
                        TagCell(
                            value = d.toString(),
                            selected = selected.contains(d),
                            onClick = { onToggle(d) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(7 - week.size) {
                        Spacer(Modifier.weight(1f).height(36.dp))
                    }
                }
            }
        }
        TimeRow(hour, minute, onHour, onMinute)
    }
}

@Composable
private fun IntervalRow(
    label: String,
    unit: String,
    value: Int,
    range: IntRange,
    onValue: (Int) -> Unit,
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
    showTime: Boolean = true
) {
    val palette = LocalResolvedPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 布局：左侧"每" + 中部数值选择器（公共 [UfiDropdown]）+ 右侧单位。
        // 弹层与上方时/分选择器同源（同一个组件），自动继承向上/向下弹出定位修复。
        // showTime=false（每 N 时 / 每 N 分）时不渲染时间选择器：对应 cron 不依赖小时/分钟，
        // 避免暴露无效的 小时/分钟 选择器造成逻辑混乱。
        // v12：整行由 fillMaxWidth 改为 wrapContentWidth，「每 [N] 时」自然收缩到内容宽度。
        // 数值选择器不再 weight(1f) 撑满整行，而是 widthIn(88..112dp)：
        // 既容得下两位数 + 下拉箭头，又不会让一个 1-59 的小数字占满屏宽。
        // 弹层宽度 = 组件内部 BoxWithConstraints.maxWidth = trigger 实宽，会自动跟着收窄。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.wrapContentWidth()
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPrimary
            )
            UfiDropdown(
                selectedValue = value,
                options = range.toList(),
                onValueSelected = onValue,
                modifier = Modifier.widthIn(min = 88.dp, max = 112.dp)
            )
            Text(
                unit,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textPrimary
            )
        }
        if (showTime) {
            TimeRow(hour, minute, onHour, onMinute)
        }
    }
}

/** 将下次执行时间戳格式化为友好中文短串（今天/明天/周X/月日/年月日 + HH:mm）。 */
private fun formatNextTime(nextMillis: Long, nowMillis: Long): String {
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val next = Calendar.getInstance().apply { timeInMillis = nextMillis }
    val hm = "%02d:%02d".format(next.get(Calendar.HOUR_OF_DAY), next.get(Calendar.MINUTE))

    val sameDay = now.get(Calendar.YEAR) == next.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == next.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "今天 $hm"

    val tomorrow = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        add(Calendar.DAY_OF_YEAR, 1)
    }
    val isTomorrow = tomorrow.get(Calendar.YEAR) == next.get(Calendar.YEAR) &&
        tomorrow.get(Calendar.DAY_OF_YEAR) == next.get(Calendar.DAY_OF_YEAR)
    if (isTomorrow) return "明天 $hm"

    val diffDays = (nextMillis - nowMillis) / (24L * 3600 * 1000)
    if (diffDays in 2..6) {
        val names = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        return "${names[next.get(Calendar.DAY_OF_WEEK) - 1]} $hm"
    }

    val sameYear = now.get(Calendar.YEAR) == next.get(Calendar.YEAR)
    return if (sameYear) {
        "%02d月%02d日 %s".format(next.get(Calendar.MONTH) + 1, next.get(Calendar.DAY_OF_MONTH), hm)
    } else {
        "%04d年%02d月%02d日 %s".format(
            next.get(Calendar.YEAR),
            next.get(Calendar.MONTH) + 1,
            next.get(Calendar.DAY_OF_MONTH),
            hm
        )
    }
}
