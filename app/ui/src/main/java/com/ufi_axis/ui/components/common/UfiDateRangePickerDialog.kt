// [F24] STABLE-UI-API：本文件为**新增**公共组件（首次出现），暂不冻结签名、不标注 @UfiStableApi；
//       后续冻结签名时需加 @UfiStableApi（见 UfiStableApi.kt）。命名/KDoc 遵守现有公共组件风格（Ufi 前缀）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.ufiCardShadow
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 弹窗内部两视图：Presets（预设大卡片，点选即应用） / Custom（步骤引导选起止） */
private enum class RangePickerMode { Presets, Custom }

/** Custom 视图的当前步骤：先选开始日（Step 1），再选结束日（Step 2）——全程无半完成态 */
private enum class CustomStep { Start, End }

/**
 * [UfiDateRangePickerDialog] 的 [onConfirm][UfiDateRangePickerDialog] 回吐结果：两条输入路径显式分流，
 * 杜绝「按值猜路径」的启发式（旧实现用「startMs 是否可被 24 小时整除」猜路径，是脆性反模式，已废除）。
 *
 *  - [Picked]：用户在自定义步骤引导里手动选的起止 —— 原样回吐下拉选择器选中值的 **UTC 午夜 epoch 毫秒**
 *    （开始日 / 结束日两个 state；下拉选择器按所选 LocalDate 在 UTC 时区构造当日 00:00，与旧 M3 日历
 *    state 的 `selectedDateMillis` 语义一致）；调用方如需「用户本地当天边界」，须自行把 UTC 午夜解析为
 *    本地时区 00:00 / 23:59:59.999（如 MonitorScreen 的 utcMidnightToLocalDayStart/End）。
 *  - [QuickRange]：用户点预设大卡片触发的 —— 原样回吐 `quickRanges` 的 `start()/end()` 返回值，
 *    即 **本地壁钟毫秒**（预定义区间语义：今天 00:00~now / 昨天 00:00~23:59 等），调用方直接使用、无需换算。
 */
sealed class UfiDateRangeResult {
    /** 区间起始毫秒（具体语义见各子类 KDoc） */
    abstract val startMs: Long

    /** 区间结束毫秒（具体语义见各子类 KDoc） */
    abstract val endMs: Long

    /** 用户在自定义步骤引导里手动选的起止 —— 两端是 **UTC 午夜 epoch 毫秒**（下拉选择器原值，非本地当日 00:00） */
    data class Picked(override val startMs: Long, override val endMs: Long) : UfiDateRangeResult()

    /** 用户点预设大卡片触发的 —— 两端是 **本地壁钟毫秒**（quickRanges lambda 返回值，已表示本地当天边界） */
    data class QuickRange(override val startMs: Long, override val endMs: Long) : UfiDateRangeResult()
}

/**
 * 主题化的「日期范围选择」弹窗公共组件（两视图：预设大卡片 + 自定义步骤引导）。
 *
 * 结构（方案 A + B 结合，替代原「chips + 半完成态日历」设计）：
 *  - **Presets 视图（默认）**：4 个常用间隔大卡片 2×2（今天 / 昨天 / 近 7 天 / 近 30 天，
 *    主标 + 副文案说明含义），**点卡片立即应用**（无需再碰日历）；底部「自定义起止日期」入口切到 Custom 视图；
 *  - **Custom 视图（步骤引导）**：第 1 步选开始日 → 第 2 步选结束日，全程**无半完成态**——
 *    每步只做一件事、有明确引导文案、步骤卡横排（开始→结束两张卡，active/pending 两态 +
 *    已选日期短串）、返回/上一步/下一步/确定按钮；确定按钮校验结束日不可早于开始日（含等于 = 同天区间合法）。
 *
 * 日期实现：**年/月/日 下拉选择器（[YearMonthDaySelector]）**，完全不用 M3 的日期选择器组件——
 * M3 1.4.0 的月份导航行由 private MonthsNavigation 内部渲染、无「隐藏自带导航行」公开参数
 * （javap 实证），下拉选择器彻底规避 M3 日历。每组日期「年 ▼ 月 ▼ 日 ▼」三个下拉菜单横排，
 * 任一字段变化立即回调组合后的 [LocalDate]（选年/月时自动 clamp 日到月末）；
 * 选中值存 **UTC 午夜 epoch 毫秒**（[localDateToUtcMidnightMs]），与旧 M3 日历 state 语义一致，端点不回吐变化。
 *
 * 主题化：`title = null` 干掉默认大紫头，改由 [UfiCustomDialog] 统一标题栏承载。
 * 步骤指示：Custom 顶部用 [StepCard] 步骤卡横排替代旧 ●───○ 圆点细线（active 卡 accent 边框/底色+数字
 * 高亮+已选日期短串；pending 卡灰色描边+「待选」文案）。
 *
 * 跨模块 **public**：feature 模块可直接 `import com.ufi_axis.ui.components.common.UfiDateRangePickerDialog`。
 *
 * ## 端点语义（onConfirm 回吐 [UfiDateRangeResult]，两条路径显式分流，无启发式）
 *  - **自定义「确定」路径** → [UfiDateRangeResult.Picked]：`startMs/endMs` 是 **UTC 午夜 epoch 毫秒**
 *    （下拉选择器按所选 LocalDate 在 UTC 时区构造当日 00:00，与旧 M3 日历 state 的 `selectedDateMillis`
 *    语义一致）——调用方如需「用户本地当天边界」，须自行把 UTC 午夜解析为本地时区 00:00 / 23:59:59.999；
 *  - **快捷卡片路径** → [UfiDateRangeResult.QuickRange]：`startMs/endMs` 是 `quickRanges` 的
 *    `start()/end()` 返回值（默认快捷区间返回**本地壁钟毫秒**，语义已表示本地当天边界，如「今天」= 本地 00:00 ~ now）——
 *    调用方直接使用，不要再做任何时区换算；
 *  - **初始值** [startMs]/[endMs] 仍按**本地壁钟午夜毫秒**传入，内部经 [localWallClockMidnightToUtcMillis]
 *    转为所选日期的 **UTC 午夜**毫秒作为选中初值（与旧 M3 日历 state 语义一致）。
 *
 * @param visible 是否显示（false 时 UfiCustomDialog 自动播放退出动画后卸载）
 * @param onDismiss 取消 / 关闭回调
 * @param onConfirm 点预设卡片 / 自定义点确定 回吐 [UfiDateRangeResult]（Picked=UTC 午夜 / QuickRange=本地壁钟）；语义见上文「端点语义」
 * @param title 弹窗标题（Presets 视图显示；Custom 视图固定「自定义时间范围」）
 * @param startMs 初始起始毫秒（本地壁钟午夜毫秒；null = 未选）
 * @param endMs 初始结束毫秒（本地壁钟午夜毫秒；null = 未选）
 * @param quickRanges 预设区间卡片列表（Presets 视图点选即应用）；默认 [UfiDateRangePickerDefaults.QuickRanges]
 * @param confirmLabel 确定按钮文案（本轮 Presets 无底部按钮、Custom 步骤按钮写死中文；保留参数仅为向后兼容签名）
 * @param dismissLabel 取消按钮文案（同上，保留参数仅为向后兼容签名）
 * @param showModeToggle 是否显示「年份/月份切换」按钮（年月日下拉选择器已无 M3 切换钮；保留参数仅为向后兼容签名，不再生效）
 */
@Composable
fun UfiDateRangePickerDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (UfiDateRangeResult) -> Unit,
    title: String = "自定义时间范围",
    startMs: Long? = null,
    endMs: Long? = null,
    quickRanges: List<UfiQuickRange> = UfiDateRangePickerDefaults.QuickRanges,
    confirmLabel: String = "确定",
    dismissLabel: String = "取消",
    showModeToggle: Boolean = false,
    // 2026-08-08 12:09 新增：日期选择上下限。minDateMs=后端 Core 首次启动时间（防选早于后端记录的日期）；
    // maxDateMs=今天（截止当天）。任一为 null 时对应方向不限。两者都是 UTC 午夜 epoch ms（与 state 内部表示一致）。
    minDateMs: Long? = null,
    maxDateMs: Long? = null
) {
    // ── 内部两视图状态机：Presets（预设大卡片）/ Custom（步骤引导） ──
    // 状态重置由 LaunchedEffect(visible) 保证：UfiDialogShell 在 visible=false 时卸载的只是 Dialog 容器，
    // 本组件函数体的 remember 会跨 visible 变化保留（否则重进会卡在 Custom/Step1），
    // 故 visible 变 false 时显式把 mode/step/选中值 复位到默认值，下次打开自动回到默认视图。
    var mode by remember { mutableStateOf(RangePickerMode.Presets) }
    var step by remember { mutableStateOf(CustomStep.Start) }

    // ── 日期选中状态（State Hoisting 到本层，CustomStepContent 只负责渲染）──
    // 选中值统一存 **UTC 午夜 epoch 毫秒**（与旧 M3 日历 state 语义一致）；startMs/endMs 是本地壁钟
    // 午夜毫秒 → 先转 UTC 午夜。
    var startSelectedMs by remember { mutableStateOf(startMs?.let { localWallClockMidnightToUtcMillis(it) }) }
    var endSelectedMs by remember { mutableStateOf(endMs?.let { localWallClockMidnightToUtcMillis(it) }) }

    // visible=false 时复位内部状态；visible=true 重进时 LaunchedEffect 重启但不进 if 分支，状态留在默认值。
    // 注意：不能用 LaunchedEffect(true) 在初始化时重置——会在首次重组时立刻清空用户刚进入的 Custom 状态；
    // 也不能用 remember(visible) 重建选中值 state——会随每次 visible 变化丢失本次会话内的选择。
    // 复位时重新读 startMs/endMs 参数闭包值（每次 visible 变化启动新协程，读到的是最新参数）。
    LaunchedEffect(visible) {
        if (!visible) {
            mode = RangePickerMode.Presets
            step = CustomStep.Start
            startSelectedMs = startMs?.let { localWallClockMidnightToUtcMillis(it) }
            endSelectedMs = endMs?.let { localWallClockMidnightToUtcMillis(it) }
        }
    }

    // ── 底部按钮的文案与动作（在 UfiCustomDialog 之前算好，交给它的 confirmButton/dismissButton 槽位）──
    // 2026-09-03 修复「自定义起止日期按钮换行」：
    // 原实现把按钮区自拼在 content 里（Box(padding=Large) + Row(spacedBy(Large)) + 两个 weight(1f)
    // + 手写 Spacer(22dp) 收尾），等于把公共弹窗的按钮区重做了一遍：shell 的 DialogPaddingH 之上
    // 又叠一层缩进，每颗按钮只剩 ~110dp，「自定义起止日期」7 个中文字在 14sp 下放不下就折成两行，
    // 把按钮撑高、和右边的高度对不齐。
    // 现在交回 UfiCustomDialog 的槽位：weight(1f) + ButtonHeight + propagateMinConstraints + 统一底距，
    // 与全站其它弹窗一致；文案同时收短为「自定义范围」，灰化改用按钮自带的 enabled（disabled 配色），
    // 不再用 alpha 假灰（alpha 会让文字也一起变淡，且按钮仍可点）。
    val isConfirmEnabled = when {
        mode == RangePickerMode.Presets -> true
        step == CustomStep.Start -> startSelectedMs != null
        step == CustomStep.End -> {
            val s = startSelectedMs
            val e = endSelectedMs
            s != null && e != null && e >= s
        }
        else -> true
    }
    val (confirmText, onConfirmAction) = when {
        // Presets 视图：confirm「自定义范围」= 切到 Custom 步骤引导
        mode == RangePickerMode.Presets -> "自定义范围" to {
            mode = RangePickerMode.Custom
            step = CustomStep.Start
        }
        step == CustomStep.Start -> "下一步" to {
            if (startSelectedMs != null) step = CustomStep.End
        }
        else -> "确定" to {
            val s = startSelectedMs
            val e = endSelectedMs
            if (s != null && e != null && e >= s) {
                onConfirm(UfiDateRangeResult.Picked(s, e))
            }
        }
    }
    val (dismissText, onDismissAction) = when {
        mode == RangePickerMode.Presets -> "关闭" to { onDismiss() }
        step == CustomStep.Start -> "返回" to { mode = RangePickerMode.Presets }
        else -> "上一步" to { step = CustomStep.Start }
    }

    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = if (mode == RangePickerMode.Presets) title else "自定义时间范围",
        icon = rememberVectorPainter(Icons.Filled.DateRange),
        // showCloseButton=false：底部已有「关闭/返回」，右上角再加个 × 是重复出口
        showCloseButton = false,
        dismissButton = { UfiButton(variant = UfiButtonVariant.Secondary, text = dismissText, onClick = onDismissAction) },
        confirmButton = {
            UfiButton(text = confirmText, onClick = onConfirmAction, enabled = isConfirmEnabled)
        }
    ) {
        // content：UfiDialogBody 统一外壳（顶部 Large + spacedBy(Large)）。
        // 2026-09-03 更正注释：水平 padding 不在 UfiDialogBody，而由 UfiDialogShell 的内容列
        // 统一提供（唯一来源 Spacing.DialogPaddingH），内件不要再各自叠一层。
        UfiDialogBody {
            if (mode == RangePickerMode.Presets) {
                PresetsContent(
                    quickRanges = quickRanges,
                    onConfirm = onConfirm
                )
            } else {
                // 日期选中状态全部 State Hoisting 到本层（UfiDateRangePickerDialog），CustomStepContent 只渲染 +
                // 回调：选开始日/结束日 → 写 startSelectedMs/endSelectedMs。
                CustomStepContent(
                    step = step,
                    startSelectedMs = startSelectedMs,
                    endSelectedMs = endSelectedMs,
                    onStartDateChange = { ld -> startSelectedMs = localDateToUtcMidnightMs(ld) },
                    onEndDateChange = { ld -> endSelectedMs = localDateToUtcMidnightMs(ld) },
                    // 2026-08-08 12:09 透传日期范围（来自调用方，minDateMs=后端 Core 启动时间，maxDateMs=今天）
                    minDateMs = minDateMs,
                    maxDateMs = maxDateMs
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────
// Presets 视图：2×2 预设大卡片（底部「自定义起止日期」按钮已迁移到弹窗底部按钮 Row）
// ─────────────────────────────────────────────────

@Composable
private fun PresetsContent(
    quickRanges: List<UfiQuickRange>,
    onConfirm: (UfiDateRangeResult) -> Unit
) {
    // 水平 padding 由 UfiDialogBody 统一提供（DialogPaddingH），PresetsContent 不再自带
    Column(modifier = Modifier.fillMaxWidth()) {
        // 2×2 大卡片：每行两卡等宽（weight 1f），卡片高度 ~72dp，副文案可为 null（不显示副行）
        quickRanges.chunked(2).forEachIndexed { index, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                rowItems.forEach { qr ->
                    QuickRangeCard(
                        qr = qr,
                        onClick = { onConfirm(UfiDateRangeResult.QuickRange(qr.start(), qr.end())) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // 奇数个卡片时用占位补齐右侧，保持左对齐
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
            if (index < quickRanges.chunked(2).size - 1) {
                Spacer(Modifier.height(Spacing.Medium))
            }
        }
        // ── 底部「自定义起止日期」入口已迁移到弹窗底部按钮 Row（与「关闭」并排）──
        // 2026-08-08 12:09 UX 整改：原 OutlinedButton 在内容区独占一行，底部「关闭」单独另一行 → 视觉分散且点 Custom 路径太长。
        // 改为底部按钮 Row 左 dismiss「关闭」+ 右 confirm「自定义起止日期」，符合用户操作从预设/自定义二选一的直觉。
    }
}

/** 单个预设大卡片：行卡风格（柔阴影 + 卡片底色 + 细描边），主标 + 副文案，点击立即应用 */
@Composable
private fun QuickRangeCard(
    qr: UfiQuickRange,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val shape = UfiCardDefaults.shape
    Column(
        modifier = modifier
            .height(72.dp)
            .ufiCardShadow(elevation = 2.dp, shape = shape)
            .clip(shape)
            .background(palette.cardBg)
            .border(1.dp, palette.divider.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Large, vertical = Spacing.Medium),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = qr.label,
            style = MaterialTheme.typography.titleSmall,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (qr.subtitle != null) {
            Spacer(Modifier.height(Spacing.Small))
            Text(
                text = qr.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─────────────────────────────────────────────────
// Custom 视图：步骤指示条 + 引导文案 + 年月日下拉选择器（每步只做一件事）
// ─────────────────────────────────────────────────

@Composable
private fun CustomStepContent(
    step: CustomStep,
    startSelectedMs: Long?,
    endSelectedMs: Long?,
    onStartDateChange: (LocalDate) -> Unit,
    onEndDateChange: (LocalDate) -> Unit,
    // 2026-08-08 12:09 新增：日期上下限（UTC 午夜 epoch ms；null=不限）；传递到 YearMonthDaySelector 限制下拉范围
    minDateMs: Long? = null,
    maxDateMs: Long? = null
) {
    val palette = LocalResolvedPalette.current

    Column(modifier = Modifier.fillMaxWidth()) {
        // ── 步骤卡横排（Step 1 开始 → Step 2 结束；active/pending 两态 + 已选日期短串）──
        // 替代旧圆点细线指示条：卡片更有层次、当前步骤一目了然（accent 边框/底色 + 数字高亮）。
        // 水平对齐由 UfiDialogShell 的内容列统一提供（Spacing.DialogPaddingH=18dp，与标题同基线）。
        // 2026-09-03 更正注释：此前写成"由 UfiDialogBody 提供、DialogPaddingH=24dp"，两处都不对。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
        ) {
            StepCard(
                step = 1,
                label = "开始",
                // 2026-08-08 11:52 BUG 修复：原 formatMonthDay 只显示 MM-dd（如"08-08"）用户看不出是 2026 还是 2035 年，
                // 改用 formatLocal 显示完整 yyyy-MM-dd（如"2026-08-08"），避免日期选择 BUG（用户误判 start/end 顺序）。
                selectedValue = startSelectedMs?.let { formatLocal(it) },
                active = step == CustomStep.Start,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier.size(Spacing.IconSizeSmall)
            )
            StepCard(
                step = 2,
                label = "结束",
                // 同上 Step 1：显示完整 yyyy-MM-dd 避免年份歧义
                selectedValue = endSelectedMs?.let { formatLocal(it) },
                active = step == CustomStep.End,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(Spacing.Medium))

        // ── 引导文案（每步只做一件事、实时反馈：未选/无效=warning 警告色+图标提示，已选=accent 成功色）──
        // 2026-08-08 11:36 UX 修复：进入 Step 1 后「下一步」按钮看着可点但点了无效——没有提示为什么。
        // 加条件 warning 提示（palette.warning + Icons.Outlined.WarningAmber），明确告诉用户「请先选择日期」；
        // 已选时切 accent 色 + ✓ 图标给正反馈。底部按钮 alpha 灰化作为次要视觉（UfiDialogActions 无 enabled 参数）。
        when (step) {
            CustomStep.Start -> {
                Text(
                    "第 1 步 · 请选择开始日期",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary
                )
                Spacer(Modifier.height(Spacing.Small))
                if (startSelectedMs != null) {
                    // 已选：success 反馈
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(Spacing.IconSizeSmall)
                        )
                        Spacer(Modifier.width(Spacing.Small))
                        Text(
                            "已选开始：${formatLocal(startSelectedMs!!)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.accent
                        )
                    }
                } else {
                    // 未选：warning 提示
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = palette.warning,
                            modifier = Modifier.size(Spacing.IconSizeSmall)
                        )
                        Spacer(Modifier.width(Spacing.Small))
                        Text(
                            "请先选择开始日期后再下一步",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.warning
                        )
                    }
                }
            }
            CustomStep.End -> {
                Text(
                    "第 2 步 · 请选择结束日期",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary
                )
                Spacer(Modifier.height(Spacing.Small))
                val start = startSelectedMs
                val end = endSelectedMs
                val endValid = start != null && end != null && end >= start
                val endEarlierThanStart = start != null && end != null && end < start
                when {
                    endValid -> {
                        // 已选且合法：success 反馈
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(Spacing.IconSizeSmall)
                            )
                            Spacer(Modifier.width(Spacing.Small))
                            Text(
                                "已选结束：${formatLocal(end!!)}（开始于 ${formatLocal(start!!)}）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.accent
                            )
                        }
                    }
                    endEarlierThanStart -> {
                        // 已选但 end < start：warning 提示（显示完整日期帮用户定位哪个字段错了）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.WarningAmber,
                                contentDescription = null,
                                tint = palette.warning,
                                modifier = Modifier.size(Spacing.IconSizeSmall)
                            )
                            Spacer(Modifier.width(Spacing.Small))
                            Text(
                                "结束日期（${formatLocal(end!!)}）不能早于开始日期（${formatLocal(start!!)}）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.warning
                            )
                        }
                    }
                    else -> {
                        // 未选 end：warning 提示
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.WarningAmber,
                                contentDescription = null,
                                tint = palette.warning,
                                modifier = Modifier.size(Spacing.IconSizeSmall)
                            )
                            Spacer(Modifier.width(Spacing.Small))
                            Text(
                                "请先选择结束日期后再确定",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.warning
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.Medium))

        // 当前步骤展示的日期：已选 ms（UTC 午夜）→ LocalDate；未选 → 今天（下拉默认值）
        // 2026-08-08 12:09 限定：今天可能超过 maxDate（理论上 maxDate 不会 < 今天，但保险起见做 clamp）；
        // 若今天 < minDate（极端：minDate 是未来日期，不可能）也走 clamp。
        // 用 coerceAtLeast/coerceAtMost 而非 stdlib coerceIn(T?, T?)——后者重载在 nullable 边界下编译器歧义。
        val minBound: LocalDate = minDateMs?.toLocalDate() ?: LocalDate.MIN
        val maxBound: LocalDate = maxDateMs?.toLocalDate() ?: LocalDate.MAX
        val defaultDate = LocalDate.now().coerceAtLeast(minBound).coerceAtMost(maxBound)
        val currentDate = (if (step == CustomStep.Start) startSelectedMs else endSelectedMs)
            ?.toLocalDate() ?: defaultDate

        // ── 年月日下拉选择器（替代原自建日历网格）──
        // 用户要求「两个选择菜单分别选年、月、日」：年 ▼ 月 ▼ 日 ▼ 三个下拉菜单横排，
        // 选完立即回调组合后的 LocalDate；完全不用 M3 日期选择器，彻底规避 M3 日历。
        // 2026-08-08 12:09 限定日期范围：minDate = 后端 Core 启动时间（防选早于后端记录的日期），maxDate = 今天（截止当天）。
        YearMonthDaySelector(
            date = currentDate,
            onDateChange = { localDate ->
                if (step == CustomStep.Start) onStartDateChange(localDate) else onEndDateChange(localDate)
            },
            // UTC ms → LocalDate 转换（与 state 内部表示一致）
            minDate = minDateMs?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() },
            maxDate = maxDateMs?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        )
    }
}

/**
 * 横排步骤卡 - Step 1 或 Step 2（Custom 视图顶部状态卡，替代旧 ●───○ 圆点细线指示条）。
 *
 *  - active = 当前步骤：accent 色描边 + accent 低透明底色 + 数字徽标高亮 accent + 主标加粗，
 *    [selectedValue] 非空时显示已选日期短串（accent 色）；
 *  - pending = 未到步骤：divider 描边 + 透明底 + 数字/主标次级色 + 状态文案「待选」。
 *
 * @param step 步骤号（1 或 2，显示为圆形数字徽标）
 * @param label 步骤名（「开始」/「结束」）
 * @param selectedValue 已选日期短串（如「08-12」，下拉选择器选中值的 UTC 午夜经 [formatMonthDay] 转本地壁钟后
 *   按 MM-dd 格式化）；未选传 null → 状态文案显示「待选」
 * @param active 是否当前步骤
 */
@Composable
private fun StepCard(
    step: Int,
    label: String,
    selectedValue: String?,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val shape = UfiCardDefaults.shape
    Row(
        modifier = modifier
            .height(64.dp)
            .clip(shape)
            .background(if (active) palette.accent.copy(alpha = 0.08f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (active) palette.accent else palette.divider,
                shape = shape
            )
            .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 步骤数字徽标：active = accent 低透明圆底 + accent 数字；pending = 透明圆底 + 次级色数字
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    color = if (active) palette.accent.copy(alpha = 0.15f) else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step.toString(),
                style = UfiTextStyles.tagStrong,
                color = if (active) palette.accent else palette.textSecondary
            )
        }
        Spacer(Modifier.width(Spacing.Medium))
        Column {
            Text(
                text = label,
                style = UfiTextStyles.sectionTitle.copy(
                    fontWeight = if (active) UfiWeight.Emphasis else UfiWeight.Regular
                ),
                color = if (active) palette.textPrimary else palette.textSecondary
            )
            Text(
                text = selectedValue ?: "待选",
                style = MaterialTheme.typography.bodySmall,
                color = if (active) palette.accent else palette.textSecondary.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 年月日下拉选择器：年 / 月 / 日 三个下拉菜单横排，选完立即回调组合后的 LocalDate。
 * 替代原自建日历网格（方案 B 再简化——用户要求"两个选择菜单分别选年、月、日"）。
 *
 * @param date 当前展示的日期（UTC 语义，见调用方）
 * @param onDateChange 任一字段变化时回调组合后的新日期（选年/月时自动 clamp 日到月末）
 * @param minDate 下限日期（UTC 语义；null=不限；2026-08-08 12:09 新增：限最小日期=后端 Core 启动时间）
 * @param maxDate 上限日期（UTC 语义；null=不限；2026-08-08 12:09 新增：限最大日期=今天）
 */
@Composable
private fun YearMonthDaySelector(
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    minDate: LocalDate? = null,
    maxDate: LocalDate? = null
) {
    val palette = LocalResolvedPalette.current
    // 年范围：以当前 date 年为中心 ±10，截断到 minDate/maxDate 的年范围（按年比较：minDate.year..maxDate.year）
    val baseYearLow = (minDate?.year ?: (date.year - 10))
    val baseYearHigh = (maxDate?.year ?: (date.year + 10))
    val years = (baseYearLow..baseYearHigh).toList()
    val months = (1..12).toList()
    val days = (1..YearMonth.of(date.year, date.monthValue).lengthOfMonth()).toList()
    // 工具函数：组合日期时按 min/max 截断（防跨边界选到无效日期，如上限月份的最后一天）
    fun clampToBounds(ld: LocalDate): LocalDate {
        var l = ld
        if (minDate != null && l.isBefore(minDate)) l = minDate
        if (maxDate != null && l.isAfter(maxDate)) l = maxDate
        return l
    }

    // 2026-09-03 修复「弹窗内输入框左右边距与其它内容对不齐」：
    // 这里原来自带 `.padding(horizontal = Spacing.DialogPaddingH)`，而本组件的调用链是
    // UfiDialogShell 内容列（已 18dp）→ UfiDialogBody（只管纵向）→ CustomStepContent → 本组件，
    // 于是年/月/日三个下拉字段实际缩进 18+18=36dp，比上方 StepCard 行、引导文案多缩 18dp。
    // 横向内距的唯一来源是弹窗壳，这里去掉自带的那一层。
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
    ) {
        // 2026-09-04（P4e 下拉收敛）：原为本文件私有的 UfiDropdownField（与 ScheduleSelector 的
        // TimeField 高度重复），现统一走公共 [UfiDropdown]。观感基准取 TimeField：值居中、
        // 弹层 7 项限高、下方空间不足时向上弹、fade + scale 进场；选中项不再带右侧 ✓
        // （靠 accent 底色 + accent 文字 + 加粗区分，避免 ✓ 出现时把文本挤得左右抖动）。
        UfiDropdown(
            selectedValue = date.year,
            options = years,
            onValueSelected = { y ->
                val maxDay = YearMonth.of(y, date.monthValue).lengthOfMonth()
                val candidate = LocalDate.of(y, date.monthValue, minOf(date.dayOfMonth, maxDay))
                onDateChange(clampToBounds(candidate))
            },
            // 年份是 4 位数、月/日是 2 位，等分三份会把"2026"挤到只剩一两个字宽（实测被截成"202"）。
            // 给年份 1.4 倍权重，与触发器内距/箭头瘦身一起解决截断。
            modifier = Modifier.weight(1.4f),
            unitSuffix = "年"
        )
        UfiDropdown(
            selectedValue = date.monthValue,
            options = months,
            onValueSelected = { m ->
                val maxDay = YearMonth.of(date.year, m).lengthOfMonth()
                val candidate = LocalDate.of(date.year, m, minOf(date.dayOfMonth, maxDay))
                onDateChange(clampToBounds(candidate))
            },
            modifier = Modifier.weight(1f),
            unitSuffix = "月"
        )
        UfiDropdown(
            selectedValue = date.dayOfMonth,
            options = days,
            onValueSelected = { d -> onDateChange(clampToBounds(LocalDate.of(date.year, date.monthValue, d))) },
            modifier = Modifier.weight(1f),
            unitSuffix = "日"
        )
    }
}

/**
 * 快捷区间卡片（Presets 视图点选即应用）。
 *
 * [start] / [end] 是**延迟块**——每次弹窗打开 / 每次点卡片时才求值，
 * 避免固定时间戳过期；返回值语义由调用方与 [UfiDateRangePickerDialog] 的 KDoc 约定
 * （默认快捷区间返回**本地壁钟毫秒**，已表示本地当天边界）。
 */
data class UfiQuickRange(
    val label: String,
    /** 主标下方的小字说明（卡片显示用），null 则不显示副行 */
    val subtitle: String? = null,
    /** 计算 startMs 的延迟块（每次点卡片时按 now 实时计算，避免固定时间戳过期） */
    val start: () -> Long,
    /** 计算 endMs 的延迟块 */
    val end: () -> Long
)

/** [UfiDateRangePickerDialog] 的默认值容器。 */
object UfiDateRangePickerDefaults {
    /**
     * 默认 4 个快捷区间（Presets 大卡片，每次点卡片时按 now 实时计算）。
     *
     * 注意：全部毫秒是**本地时区壁钟时间**（Calendar 本地化）——「今天/昨天」用 Calendar 精确到
     * 当天 00:00:00.000 / 23:59:59.999，「近 N 天」是 now - N*24h ~ now；与下拉选择器选中值
     * （UTC 午夜毫秒）不同，调用方按 [UfiDateRangePickerDialog] KDoc 的路径语义区分处理。
     */
    val QuickRanges: List<UfiQuickRange> = listOf(
        UfiQuickRange(
            label = "今天",
            subtitle = "00:00 — 现在",
            start = {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            },
            end = { System.currentTimeMillis() }
        ),
        UfiQuickRange(
            label = "昨天",
            subtitle = "全天",
            start = {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_MONTH, -1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            },
            end = {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_MONTH, -1)
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.set(Calendar.MILLISECOND, 999)
                cal.timeInMillis
            }
        ),
        UfiQuickRange(
            label = "近 7 天",
            subtitle = "最近 7 天",
            start = { System.currentTimeMillis() - 7L * 86_400_000 },
            end = { System.currentTimeMillis() }
        ),
        UfiQuickRange(
            label = "近 30 天",
            subtitle = "最近 30 天",
            start = { System.currentTimeMillis() - 30L * 86_400_000 },
            end = { System.currentTimeMillis() }
        )
    )
}

/**
 * 本地壁钟午夜毫秒 → 所选日期的 **UTC 午夜** 毫秒（用于下拉选择器选中值的初始值）。
 *
 * 下拉选择器选中值统一存 **UTC 午夜 epoch 毫秒**（与旧 M3 日历 state 语义一致，
 * 保证 [UfiDateRangeResult.Picked] 端点语义不回吐变化）：取 localMs 的**本地日期**
 * （year/month/day），再在 UTC 日历上构造该日 00:00:00.000。
 * 注意：不能用「localMs - 偏移」的简单算术（对 UTC+8 会落到前一天，正是旧实现 off-by-one 的根源）。
 */
private fun localWallClockMidnightToUtcMillis(localMs: Long): Long {
    val local = java.util.Calendar.getInstance()
    local.timeInMillis = localMs
    val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    utc.clear()
    utc.set(
        local.get(java.util.Calendar.YEAR),
        local.get(java.util.Calendar.MONTH),
        local.get(java.util.Calendar.DAY_OF_MONTH)
    )
    return utc.timeInMillis
}

/**
 * 下拉选择器选中值的 UTC 午夜毫秒 → 用户本地当天 00:00 的本地壁钟毫秒。
 *
 * 选中值的 `utcTimeMillis` 是所选日期的 **UTC 午夜**；直接 + 本地时区当日偏移，得到「用户所见那天」的
 * 本地 epoch（供 [formatLocal] 按本地时区格式化成 yyyy-MM-dd 展示）。仅用于界面引导文案，
 * 不改变 [UfiDateRangeResult.Picked] 的回吐语义（回吐仍用选中值原值 = UTC 午夜）。
 */
private fun utcMidnightToLocalWallClock(utcMidnightMs: Long): Long =
    utcMidnightMs + TimeZone.getDefault().getOffset(utcMidnightMs)

/** [utcMidnightToLocalWallClock] 后按本地时区格式化为 yyyy-MM-dd（仅用于界面文案，不回吐）。 */
private fun formatLocal(utcMidnightMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(utcMidnightToLocalWallClock(utcMidnightMs)))

/** [utcMidnightToLocalWallClock] 后按本地时区格式化为 MM-dd（步骤卡已选日期短串，仅用于界面文案，不回吐）。 */
private fun formatMonthDay(utcMidnightMs: Long): String =
    SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(utcMidnightToLocalWallClock(utcMidnightMs)))

/** UTC 午夜 epoch 毫秒 → 该 UTC 时刻对应的 LocalDate（选中值本身就是 UTC 午夜，直接按 UTC 解析）。 */
private fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** LocalDate → UTC 午夜 epoch 毫秒（下拉选择器 → state 内部表示；与旧 M3 日历 state 语义一致）。 */
private fun localDateToUtcMidnightMs(localDate: LocalDate): Long =
    localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
