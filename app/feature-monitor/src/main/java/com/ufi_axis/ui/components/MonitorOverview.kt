// R2+R3：监控页「总览 / 图表」双 Tab 总览面板局部组件（feature-monitor 模块内新增，不动任何公共组件）。
// 设计依据：监控界面重构方案。
// 红线：不调用 MonitorScreen 的私有 MonitorSectionCard / UfiSectionHeader（跨文件不可见/公共冻结），
//       本文件内私有复制同风格 OverviewCard 与降级卡内分区标题；不新增 Token（色值/字体/圆角）；
//       不新增 30dp 尺寸；不新增路由（顶部 Tab 内联切换）。

package com.ufi_axis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
// 2026-08-14 需求2：事件卡「长按呼出更多操作」——单击仍是标记已读，长按弹 UfiPopupMenu
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

import com.ufi_axis.data.model.AlertRecord
import com.ufi_axis.data.model.DownsampledPoint
import com.ufi_axis.data.model.TrafficSummary
import com.ufi_axis.data.monitor.MonitorMetricType
import com.ufi_axis.data.monitor.MetricThreshold
import com.ufi_axis.data.monitor.ThresholdLevel
import com.ufi_axis.data.monitor.MonitorTimeRange
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.state.MonitorState
import com.ufi_axis.ui.animation.rememberUfiPressed
import com.ufi_axis.ui.animation.ufiPressScale
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonVariant
// 2026-08-14 需求2：长按事件卡的「更多操作」菜单 —— 复用项目标准 UfiPopupMenu，
// 不用 M3 原生 DropdownMenu（见 UfiDialogParts 注释：原生在部分机型不继承自定义 colorScheme 会白底）
import com.ufi_axis.ui.components.common.UfiPopupMenu
import com.ufi_axis.ui.components.common.UfiScrollableTabRow
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.ResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextSizeLadder
import com.ufi_axis.ui.theme.UfiTextStyles

import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.ufiCardShadow
import com.ufi_axis.ui.theme.ufiShade

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 监控页顶部「总览 / 图表 / 事件」三 Tab 切换栏。
 *
 * 三界面采用**内联切换**：Tab 常驻（不随面板滚动），下方 `when(selected)` 在三个面板间切换；
 * **不新增路由**（Routes.MONITOR 内容即「Tab + 三个面板」，与现有 5 Tab 收敛进 Routes.MAIN 的架构一致）。
 *
 * UI 样式：三列等宽居中分散对齐（`weight(1f)` + `contentAlignment = Center`），
 * 选中项 accent 高亮（底部 20dp×3dp 圆角指示器 + 文字 accent/SemiBold），未选中 textSecondary，
 * 整体下方带 1dp 分隔线。
 *
 * @param selected 当前选中的面板
 * @param onSelect 切换回调（由调用方 remember 持有状态）
 */
@Composable
fun MonitorTopTab(
    selected: MonitorOverviewTab,
    onSelect: (MonitorOverviewTab) -> Unit
) {
    val entries = MonitorOverviewTab.entries
    val selectedIndex = entries.indexOf(selected).coerceAtLeast(0)
    // 复用公共 UfiScrollableTabRow（与 AppScreen / DownloadTasksUi / TaskScreen 等同款胶囊滑块），
    // 避免自绘 onSizeChanged+Row.weight 的 dp/px 换算误差。枚举对外 API 不变。
    UfiScrollableTabRow(
        selectedTabIndex = selectedIndex,
        onTabSelected = { onSelect(entries[it]) },
        tabs = entries.map { it.label },
        modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin, vertical = 8.dp)
    )
}

/**
 * 监控页顶部面板枚举：总览 / 图表。
 * v26（2026-08-20）：事件中心已迁为独立全屏页（Routes.DETAIL_EVENTS），此处收敛为 2 Tab。
 */
enum class MonitorOverviewTab(val label: String) {
    OVERVIEW("总览"),
    CHARTS("图表")
}


/**
 * 复用的日期格式化器与正则（2026-08-26 性能修复）。
 *
 * 原来 `formatAlertTimeSmart` / `isSameDay` / `formatAlertMessage` 都在函数体里 new：
 * 每渲染一张事件卡 = 1 个 SimpleDateFormat + 2 个 Calendar.getInstance() + 1 个 Regex 编译，
 * 一页 20 张卡就是 20 次 locale/时区解析和正则编译，全在主线程组合阶段。
 * DateTimeFormatter 线程安全可全局复用；Regex 编译一次即可。
 */
private val TIME_HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val TIME_MD_HM: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")
private val DAY_MD: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
private val TRAFFIC_MB_REGEX = Regex("(\\d+)MB")

/**
 * 事件中心日期范围 → 单行中文标签（与图表页「当前范围」文案同源，复用 MonitorTimeRange 模型）。
 * 2026-08-12 新增：事件中心支持按日期/区间查看，摘要卡与筛选条共用此标签。
 * - Preset 滑动窗口 → 「近 N 小时 / 近 7 天」
 * - Custom 当天 → 「今天」
 * - Custom 单日   → 「M月d日」
 * - Custom 多日  → 「M月d日 ~ M月d日」
 */
internal fun alertRangeLabel(range: MonitorTimeRange): String = when (range) {
    is MonitorTimeRange.Preset -> when (range.hours) {
        1 -> "近 1 小时"
        6 -> "近 6 小时"
        24 -> "近 24 小时"
        168 -> "近 7 天"
        else -> "近 ${range.hours} 小时"
    }
    is MonitorTimeRange.Custom -> {
        if (range.startMs == MonitorTimeRange.todayStartMs()) "今天"
        else {
            val zone = ZoneId.systemDefault()
            val s = Instant.ofEpochMilli(range.startMs).atZone(zone)
            val e = Instant.ofEpochMilli(range.endMs).atZone(zone)
            if (s.toLocalDate() == e.toLocalDate()) DAY_MD.format(s)
            else "${DAY_MD.format(s)} ~ ${DAY_MD.format(e)}"
        }
    }
}

/** hero 摘要卡需要的全部计数（单趟扫描得出，避免对同一列表反复 count{}） */
private class AlertCounts(
    val total: Int,
    val critical: Int,
    val warning: Int,
    val unread: Int,
    val unreadCritical: Int,
    val unreadWarning: Int
)

private fun countAlerts(alerts: List<AlertRecord>): AlertCounts {
    var critical = 0
    var warning = 0
    var unread = 0
    var unreadCritical = 0
    var unreadWarning = 0
    alerts.forEach { a ->
        val isCritical = a.level == "critical"
        val isWarning = a.level == "warning"
        if (isCritical) critical++
        if (isWarning) warning++
        if (!a.acknowledged) {
            unread++
            if (isCritical) unreadCritical++
            if (isWarning) unreadWarning++
        }
    }
    return AlertCounts(alerts.size, critical, warning, unread, unreadCritical, unreadWarning)
}

/**
 * 总览面板「今日摘要」卡（v2 事件中心定位，PM 方案 2 最小集）。
 *
 * 回答"今天设备发生了什么"：异常计数 + 未读角标 + 最严重一条。
 * 2026-08-12 改为「范围感知」：传入的 [range] 决定摘要口径（今天/昨天/指定日期/区间），
 * [alerts] 已是该范围后端返回的子集，文案与计数随范围联动；不再写死「今日」。
 *
 * @param alerts 当前日期范围内的全部告警事件（monitorState.alerts）
 * @param range 当前事件中心日期范围（MonitorState.alertRange，复用 MonitorTimeRange）。
 *              必须由调用方给出稳定实例：`MonitorTimeRange.today()` 内含 currentTimeMillis()，
 *              一旦作为默认值就每次求值都不 equals 上一次 → 本卡永远无法被 Compose 跳过重组。
 */
@Composable
fun EventSummaryCard(
    alerts: List<AlertRecord>,
    range: MonitorTimeRange,
    // v20h：阈值级 hero 状态——把今日 6 张 peak 卡里"最差的阈值等级"传上来，让 hero 在「无未读告警」
    // 但「有指标超阈值」（典型：CPU 90% WARN、内存峰值 ALERT）的常见场景下也给出视觉提示。
    // NORMAL=没有指标超阈值
    worstPeakLevel: ThresholdLevel,
    // 处于 [worstPeakLevel] 这一等级的指标条数（0=无）。副文案要说"N 项"，
    // 之前写死"1 项"，CPU 与温度同时超标也只说 1 项。
    worstPeakCount: Int,
    // v26（2026-08-20）：hero 卡右上角「事件中心 ›」入口按钮（方案 A：事件中心迁为独立全屏页）。
    onEventsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    // 一趟扫描出全部 5 个计数：原来 count/critical/warning/unread/unreadCritical/unreadWarning
    // 是 5 次独立 `alerts.count{}`，写在 composable 函数体里，每次重组都要把列表走 5 遍。
    val counts = remember(alerts) { countAlerts(alerts) }
    val unread = counts.unread
    val peakAlert = worstPeakLevel == ThresholdLevel.ALERT
    val peakWarn = worstPeakLevel == ThresholdLevel.WARN && !peakAlert
    // 状态色 = 最差信号驱动：严重（阈值 ALERT / critical 未读）> 警告（阈值 WARN / warning 未读）
    // > 有未读但都是提示级 > 正常。
    val accentColor = when {
        peakAlert || counts.unreadCritical > 0 -> palette.error
        peakWarn || counts.unreadWarning > 0 -> palette.warning
        unread > 0 -> palette.accent
        else -> palette.success
    }
    // 2026-09-04：状态文案从「一整条字符串」拆成「主句 + 限定语」两段。
    // 为什么拆：主句里带着结论性的数字（"N 项未读告警"），限定语只是补充定语。
    // 宽度真的不够时，排版要能把限定语降级成第二行小字（见 [HeroStatusHeader] 的"拆句档"）——
    // 拼成一整句就只剩"整体缩到很小"或"截断"两条路，而截断会把结论吃掉。
    // 语义与拆分前完全一致：unread>0 且有指标超阈值 = 主句 + "阈值告警" 限定语。
    val heroMain = when {
        unread > 0 -> "$unread 项未读告警"
        peakAlert -> "严重阈值告警"
        peakWarn -> "存在阈值告警"
        else -> "运行正常"
    }
    val heroQualifier =
        if (unread > 0 && worstPeakLevel != ThresholdLevel.NORMAL) "阈值告警" else null

    // 2026-08-09 18:44 hero 取色对齐仪表盘 HomeConnectionCard（L91-95 gradColors 逻辑）：
    // 之前用 palette.themeGradient（accent → lerp(accent, accentSecondary, 0.5f) → accentSecondary 双色渐变，
    // 会混入 accentSecondary 色相与仪表盘视觉不一致）。改为 accent 单色系渐变：
    //   深色模式 = accentColor.ufiShade(-0.14/-0.04) → accentColor
    //   浅色模式 = accentColor → accentColor.ufiShade(0.12/0.22)
    // v20h 拓展：base 色 = accentColor（unread=error / 阈值告警=warning / 正常=success），
    // 这样 hero 卡不再永远是绿底——CPU/MEM/TEMP 超阈值也会变橙底，与下方峰值卡状态圆点同语义。
    // 只依赖 accentColor + 主题明暗，remember 住：否则每次重组都要做 2 次混色并新建一个 Brush。

    //
    // 2026-09-04（P2-中）复核：这 4 个停止点**不接 onGradient**（它们算的是渐变**底色**，
    // 不是底色之上的前景），也**不收敛到 palette.accentStrong** —— 曾有判定说这是 accentStrong
    // 的第二个实现，实测不成立：
    //   1) 方向相反：accentStrong 深色朝白、浅色朝黑；这里深色朝黑、浅色朝白（要的是"渐变阶梯"不是"按压加重"）；
    //   2) 比例不同：accentStrong 只有单一 16%，这里是 14%/4% 与 12%/22% 四个停止点；
    //   3) 基色不同：accentStrong 恒取 palette.accent，这里取 accentColor（error/warning/accent/success 四态）。
    //
    // 2026-09-05（P1）：原先这里用 `androidx.compose.ui.graphics.lerp`（**Oklab 插值**）自己实现，
    // 是另三张 Hero 卡私有 `Color.shade`（sRGB 分量混合）的第四份实现。同一组比例在两个色彩空间下
    // 算出的像素并不相同 ⇒「四张 Hero 卡视觉统一」这个前提实际不成立。现统一改用主题层的
    // [com.ufi_axis.ui.theme.ufiShade]（sRGB），**本卡渐变像素会变**（这正是修复目的：四卡对齐）。
    val gradient = remember(accentColor, palette.isDark) {
        val colors = if (palette.isDark) {
            listOf(
                accentColor.ufiShade(-0.14f),
                accentColor.ufiShade(-0.04f),
                accentColor
            )
        } else {
            listOf(
                accentColor,
                accentColor.ufiShade(0.12f),
                accentColor.ufiShade(0.22f)
            )
        }
        Brush.linearGradient(colors = colors)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(UfiCardDefaults.dialogShape)
            .background(gradient)
    ) {
        // 2026-08-09 14:53 同步仪表盘 HomeConnectionCard: 整卡 padding Spacing.CardPadding(20dp 水平+垂直) →
        // padding(horizontal=16, vertical=12) — 与仪表盘 hero 一致比例（之前 20dp 让卡显得空小）
                // 2026-08-09 16:31 hero 卡增加 25%：vertical padding 18→22dp + 状态行 22→24sp + 副文案 bodyMedium→bodyLarge
        // + 标题↔副文案间距 Medium(8)→Large(12) —— 卡整体更高更饱满
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 22.dp)) {
            // 2026-09-04 重做（撤销上一轮的「maxLines = 1 + 省略号」）：
            // 省略号只是把"换行难看"换成"看不全" —— 这一行带着"N 项"这个结论，不允许被截。
            // 现在整条状态行（状态圆点 + 状态文案 + 「事件中心 ›」入口）交给 [HeroStatusHeader]：
            // 它按实测可用宽度先降字号、再改版式，每一档都不丢字。
            // 圆点与入口胶囊都搬进去了 —— 因为"入口要不要退到下一行"这件事必须和字号一起决定，
            // 拆在两层里没法联动。
            HeroStatusHeader(
                main = heroMain,
                qualifier = heroQualifier,
                dotColor = accentColor,
                onEventsClick = onEventsClick
            )

            Spacer(Modifier.height(Spacing.Large))

            // 2026-08-12 范围感知：alerts 已是后端按 range 返回的子集，计数 = 子集大小；
            // 文案前缀用 alertRangeLabel(range)（今天/昨天/8月11日/区间），不再写死「今日」。
            val rangeLabel = alertRangeLabel(range)
            val count = counts.total
            val criticalCount = counts.critical
            val warningCount = counts.warning
            val peakSuffix = when {
                peakAlert -> if (worstPeakCount > 0) " · $worstPeakCount 项严重阈值" else " · 指标超严重阈值"
                peakWarn -> if (worstPeakCount > 0) " · $worstPeakCount 项警告阈值" else " · 指标进入警告阈值"
                else -> ""
            }
            val subText = when {
                count > 0 -> "$rangeLabel $count 条告警 · 严重 $criticalCount · 警告 $warningCount$peakSuffix"
                peakAlert -> "$rangeLabel 无告警，但指标已超严重阈值，请关注下方峰值卡"
                peakWarn -> "$rangeLabel 无告警，但有指标进入警告阈值，请关注下方峰值卡"
                else -> "$rangeLabel 无异常，设备持续稳定运行"
            }
            Text(
                text = subText,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.gradientMuted.copy(alpha = 0.85f),  // 原 Color.White 85%：hero 副文案
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * hero 状态行：状态圆点 + 状态文案（"3 项未读告警 · 阈值告警" / "运行正常" / …）+ 「事件中心 ›」入口。
 *
 * ## 为什么不是 maxLines = 1 + 省略号（这是对上一轮方案的撤销）
 * 省略号只是把"换行难看"换成"看不全"：这一行带着结论性的数字，
 * "12 项未读告警 · 阈值告警" 被截成 "12 项未读告…" 等于把结论吃掉，比折行更糟。
 * 所以本函数**任何一档都不截字**，兜底手段只有"缩字号"和"改版式"两种。
 *
 * ## 三步降级（每一步都由 [rememberTextMeasurer] 实测决定，不靠经验值）
 * 1. **同行档**：圆点 + 文案 + 胶囊挤一行。字号在 [HERO_LABEL_SIZE_LADDER]
 *    （theme 层字阶 24→22→20→18→16sp）里取"第一个塞得下"的档。
 *    宽屏 + 默认字体缩放必然命中首档 24sp，与改动前观感完全一致。
 * 2. **让位档**：一行放不下 ⇒ 「事件中心 ›」胶囊退到状态行**下面**自己一行（右对齐）。
 *    为什么牺牲它：它是次要入口，而状态文案是这张卡的主句；它退位换来的整行宽度
 *    通常能把主句顶回 24/22sp。宁可多占一行高度，也不要主状态先缩成小字。
 * 3. **拆句档**：连整行都放不下 ⇒ 限定语（"阈值告警"）降级为主句下面的第二行小字
 *    （[UfiTextStyles.bodyEmphasis]）。此时"N 项未读告警"仍是完整大字，限定语一个字不少，
 *    只是层级下降 —— 这正是"标签独占一行小字、主值同行大字"的排版做法。
 *
 * ## 窄屏与大字体缩放走同一条链
 * 判据是「实测像素宽 vs 可用像素宽」；字号单位是 sp，而 TextMeasurer 用当前 density
 * （含 fontScale）测量，所以 320dp 窄屏和 fontScale 1.3~2.0 的宽屏走的是同一套降级，
 * 不需要给无障碍字号单独写分支。[LocalDensity] 的 fontScale 也进了 remember key：
 * 系统字体大小一改立刻重选档。
 *
 * 渲染时**不再写 `softWrap = false`**：万一实测与实际渲染差一两像素，结果是"折成两行"
 * 而不是"被裁掉看不见"。maxLines = 2 只是这层安全垫，正常路径永远是一行。
 *
 * ## 只动布局与字号
 * 颜色沿用本卡既有的渐变前景槽位（palette.onGradient / gradientMuted），未新增或改动任何配色。
 */
@Composable
private fun HeroStatusHeader(
    main: String,
    qualifier: String?,
    dotColor: Color,
    onEventsClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 字号只在既有 token 上 copy(fontSize=…)，档位表来自 theme 层字阶，业务层不写死 sp。
    val baseStyle = UfiTextStyles.metricValueLarge
    val entryStyle = UfiTextStyles.caption.copy(fontWeight = UfiWeight.Medium)
    // 同行/让位两档展示的完整文案："主句 · 限定语"（无限定语时就是主句本身）
    val inlineText = if (qualifier != null) "$main · $qualifier" else main

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // constraints.maxWidth 是 px，直接和 TextMeasurer 的结果比，省一次 dp↔px 换算。
        val availablePx = constraints.maxWidth
        val fontScale = density.fontScale
        // 圆点、间距、胶囊入口一共吃掉多少宽度：胶囊里的四个字也实测出来，
        // 不用固定估值 —— "事件中心"在大 fontScale 下会变宽，估值必然低估、于是又开始折行。
        val chrome = remember(measurer, density, fontScale, entryStyle) {
            val entryTextPx = measurer.measure(
                text = AnnotatedString(HERO_ENTRY_TEXT),
                style = entryStyle,
                maxLines = 1,
                softWrap = false
            ).size.width
            with(density) {
                HeroChromeWidths(
                    entryPx = entryTextPx +
                        (HERO_ENTRY_ICON + HERO_ENTRY_ICON_GAP + HERO_ENTRY_PADDING_H * 2).roundToPx(),
                    dotPx = (HERO_DOT_SIZE + Spacing.Medium).roundToPx(),
                    gapPx = Spacing.Medium.roundToPx()
                )
            }
        }
        val plan = remember(inlineText, main, availablePx, chrome, measurer, fontScale, baseStyle) {
            planHeroStatus(
                measurer = measurer,
                baseStyle = baseStyle,
                sizeLadder = HERO_LABEL_SIZE_LADDER,
                inlineText = inlineText,
                mainText = main,
                // 同行档预算 = 整宽 − 圆点(含其后间距) − 文案与胶囊之间的间距 − 胶囊
                inlineBudgetPx = availablePx - chrome.dotPx - chrome.gapPx - chrome.entryPx,
                // 让位档预算 = 整宽 − 圆点(含其后间距)，胶囊已经不在这一行了
                blockBudgetPx = availablePx - chrome.dotPx
            )
        }
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                // 拆句档是"大字 + 小字"两行的文本块，圆点要跟第一行走；
                // 继续用 CenterVertically 会让它浮到两行之间，看着像没对齐。
                verticalAlignment = if (plan.splitQualifier) Alignment.Top else Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .padding(top = if (plan.splitQualifier) HERO_DOT_FIRST_LINE_OFFSET else 0.dp)
                        .size(HERO_DOT_SIZE)
                        .background(dotColor, CircleShape)
                )
                Spacer(Modifier.width(Spacing.Medium))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (plan.splitQualifier) main else inlineText,
                        style = baseStyle.copy(fontSize = plan.fontSize),
                        color = palette.onGradient,  // 渐变底上的不透明前景（默认值 = Color.White）
                        // 刻意不给 overflow = Ellipsis：带结论的文案不允许被"…"吃掉。
                        maxLines = 2
                    )
                    if (plan.splitQualifier && qualifier != null) {
                        Text(
                            text = qualifier,
                            // 限定语降级用的是既有正文 token，不是随手挑的 sp
                            style = UfiTextStyles.bodyEmphasis,
                            color = palette.onGradient
                        )
                    }
                }
                if (!plan.entryOnOwnRow) {
                    // 与右侧入口留出固定间距：以前两者直接相邻，文案一长就贴到胶囊上。
                    Spacer(Modifier.width(Spacing.Medium))
                    HeroEventsEntry(onEventsClick)
                }
            }
            if (plan.entryOnOwnRow) {
                Spacer(Modifier.height(Spacing.Medium))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    HeroEventsEntry(onEventsClick)
                }
            }
        }
    }
}

/**
 * v26：「事件中心 ›」入口胶囊（半透明底 + chevron），点击进入独立全屏事件页。
 *
 * 抽成函数有两个原因：它有两个落点（同行 / 让位后自己一行），以及尺寸常量要和
 * [HeroStatusHeader] 的宽度预算共用同一份 —— 否则改了 padding 忘了改预算，折行就回来了。
 *
 * 2026-09-04（P2-中）：本卡原有 4 处写死 Color.White（胶囊底 22% / 胶囊文字 / chevron / 副文案 85%）
 * 已收敛到 palette 槽位 —— 卡底是 accentColor 三段渐变，换配色 / 换告警状态时底色会变，
 * 白色不变就会在浅色 accent 主题下没对比度。不透明前景 → onGradient；带 alpha 的弱化前景 → gradientMuted。
 */
@Composable
private fun HeroEventsEntry(onClick: () -> Unit) {
    val palette = LocalResolvedPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(palette.gradientMuted.copy(alpha = 0.22f))  // 原 Color.White 22%：胶囊半透明底
            .clickable { onClick() }
            .padding(horizontal = HERO_ENTRY_PADDING_H, vertical = HERO_ENTRY_PADDING_V)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HERO_ENTRY_ICON_GAP)
        ) {
            Text(
                text = HERO_ENTRY_TEXT,
                style = UfiTextStyles.caption.copy(fontWeight = UfiWeight.Medium),
                color = palette.onGradient,  // 原 Color.White：入口文字
                // 不带 weight：Row 先按内容量它的固有宽度。maxLines/softWrap 是兜底 ——
                // 极窄屏下宁可让状态文案继续降级，也不要这四个字自己折两行把卡撑高。
                maxLines = 1,
                softWrap = false
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "进入事件中心",
                tint = palette.onGradient,  // 原 Color.White：chevron
                modifier = Modifier.size(HERO_ENTRY_ICON)
            )
        }
    }
}

/** 入口胶囊 / 状态圆点的尺寸常量。与宽度预算共用一份，见 [HeroEventsEntry] 注释。 */
private const val HERO_ENTRY_TEXT = "事件中心"
private val HERO_ENTRY_ICON = 14.dp
private val HERO_ENTRY_ICON_GAP = 2.dp
private val HERO_ENTRY_PADDING_H = 10.dp
private val HERO_ENTRY_PADDING_V = 5.dp

/** 状态圆点直径（与仪表盘 hero 同款 8dp）。 */
private val HERO_DOT_SIZE = 8.dp

/** 拆句档里圆点相对文本块顶部的下移量：让它与第一行文字视觉居中，而不是贴在块顶。 */
private val HERO_DOT_FIRST_LINE_OFFSET = 6.dp

/** [HeroStatusHeader] 用到的三段固定宽度（px），一次实测后 remember 住。 */
private data class HeroChromeWidths(
    /** 「事件中心 ›」胶囊整体宽度（文字 + 图标 + 图标间距 + 左右内边距） */
    val entryPx: Int,
    /** 状态圆点直径 + 它后面那一处间距 */
    val dotPx: Int,
    /** 状态文案与胶囊之间的间距 */
    val gapPx: Int
)

/** [HeroStatusHeader] 选出的排版方案。 */
private data class HeroStatusPlan(
    /** 主文案字号，取自 theme 字阶 [HERO_LABEL_SIZE_LADDER] */
    val fontSize: TextUnit,
    /** true = 「事件中心」胶囊退到状态行下面自己一行（让位档 / 拆句档） */
    val entryOnOwnRow: Boolean,
    /** true = 限定语降级为第二行小字（拆句档） */
    val splitQualifier: Boolean
)

/**
 * 按实测宽度挑排版：同行档 → 让位档 → 拆句档（三档的取舍理由见 [HeroStatusHeader] 文档）。
 *
 * 刻意不是 @Composable：measurer 由调用方传入，这样整个结果能被一个 remember 缓存住，
 * 一次重组最多测 `档数 × 2` 次，且同样输入必然得到同一档 —— 不会出现"有时折行有时不折"。
 */
private fun planHeroStatus(
    measurer: TextMeasurer,
    baseStyle: TextStyle,
    sizeLadder: List<TextUnit>,
    inlineText: String,
    mainText: String,
    inlineBudgetPx: Int,
    blockBudgetPx: Int
): HeroStatusPlan {
    fun widthOf(text: String, size: TextUnit): Int = measurer.measure(
        text = AnnotatedString(text),
        style = baseStyle.copy(fontSize = size),
        maxLines = 1,
        softWrap = false
    ).size.width

    sizeLadder.firstOrNull { widthOf(inlineText, it) <= inlineBudgetPx }?.let {
        return HeroStatusPlan(fontSize = it, entryOnOwnRow = false, splitQualifier = false)
    }
    sizeLadder.firstOrNull { widthOf(inlineText, it) <= blockBudgetPx }?.let {
        return HeroStatusPlan(fontSize = it, entryOnOwnRow = true, splitQualifier = false)
    }
    // 最后一档：主句用能塞下的最大字号（都塞不下就取下限，剩下的交给 maxLines=2 折行，仍不截字）
    val mainSize = sizeLadder.firstOrNull { widthOf(mainText, it) <= blockBudgetPx } ?: sizeLadder.last()
    return HeroStatusPlan(fontSize = mainSize, entryOnOwnRow = true, splitQualifier = true)
}

/**
 * hero 状态行的候选字号来自 theme 层的 [UfiTextSizeLadder.metricValueLargeAutoShrink]
 * （24 → 22 → 20 → 18 → 16sp，从大到小；首档与 `metricValueLarge` 同值，宽屏观感不变）。
 * 2026-09-04：这张表原先是本文件的私有 `listOf(24.sp, …)`，但"字号阶梯"属于字阶，
 * 不该由监控页自己持有 —— 已搬到 Type.kt，本文件只引用。
 */
private val HERO_LABEL_SIZE_LADDER: List<TextUnit>
    get() = UfiTextSizeLadder.metricValueLargeAutoShrink


/**
 * 事件中心严重度筛选器（方案 C：圆点 + 计数）。
 * 全部 / 警告 / 严重 三选一；每项 = 严重度圆点 + 文字 + 数字徽标。
 * 选中态：accent 边框 + 卡面淡 accent 底 + 数字徽标反白（accent 实底 + 卡色字）。
 * 颜色全部来自 LocalResolvedPalette，无写死色。
 */
@Composable
fun MonitorLevelFilter(
    selectedValue: String,
    onSelect: (String) -> Unit,
    counts: Map<String, Int>,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    // 2026-08-13 v3：用户反馈双栏选项太小，改回单行自适应内容宽度，三 chip 横向排列不折行。
    Row(
        modifier = modifier.wrapContentWidth(Alignment.Start),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        MonitorLevelChip("all", "全部", palette.accent, selectedValue, counts["all"] ?: 0, onSelect)
        MonitorLevelChip("warning", "警告", palette.warning, selectedValue, counts["warning"] ?: 0, onSelect)
        MonitorLevelChip("critical", "严重", palette.error, selectedValue, counts["critical"] ?: 0, onSelect)
    }
}

/** 单个严重度 chip（根 Box 自包含，任意布局作用域均可调用） */
@Composable
private fun MonitorLevelChip(
    value: String,
    label: String,
    dotColor: Color,
    selectedValue: String,
    count: Int,
    onSelect: (String) -> Unit
) {
    val palette = LocalResolvedPalette.current
    val isSelected = value == selectedValue
    // 2026-08-09 16:16 点击/选中效果升级：
    // ① interactionSource 采集按压态 → 按下缩放 0.94 + 背景加深（即时触感）
    // ② animateColorAsState 让选中/未选中背景/边框/文字平滑过渡（200ms）
    // ③ 选中态 = accent 实底 + onAccent 白字 + 白圆点 + 白 20% 徽标——高对比明确"当前筛选"
    val interactionSource = remember { MutableInteractionSource() }
    // isPressed 现在只喂下面的**配色**（按下加深底色 / 提亮文字）；缩放已交给 ufiPressScale。
    // 2026-09-04（P2f 配色补齐）：不再用 collectIsPressedAsState —— 它短按只 true 一两帧，
    // 200ms 的 animateColorAsState 只走出几个百分比，底色变化看不见（与缩放同源）。
    // rememberUfiPressed 把按下态保底 Duration.Micro（120ms），与按压缩放同起同落。
    val isPressed by rememberUfiPressed(interactionSource)
    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> palette.accent
            isPressed -> palette.cardBorder.copy(alpha = 0.25f)
            else -> palette.cardBg
        },
        animationSpec = tween(UfiMotion.Duration.Base),
        label = "chipBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) palette.accent else palette.cardBorder.copy(alpha = 0.6f),
        animationSpec = tween(UfiMotion.Duration.Base),
        label = "chipBorder"
    )
    val textColor = if (isSelected) palette.onAccent else if (isPressed) palette.textPrimary else palette.textSecondary
    Box(
        modifier = Modifier
            .wrapContentWidth()
            .height(32.dp)
            // 2026-09-04（P2d）：原为 0.94f。本 chip 与公共 CategoryChip / 弹窗选项格是同一种
            // 「胶囊筛选器」，那两处一直是 0.97 —— 同类元素在不同页面缩不一样多，正是本轮要消掉的
            // 「同一种控件有不同动画表现」。统一到 UfiMotion.PressScale.Chip（0.97）。
            //
            // 2026-09-04（P2f 短按看不见）：原为 animateFloatAsState + graphicsLayer。
            // 为什么原来短按看不见：那是"跟随目标值"的动画，抬手瞬间目标翻回 1f 就被反向拉回；
            // 0.03 的落差配 tween(120)，一帧只走完约 5%（scale ≈ 0.9985）；筛选条又在可滚动
            // 页面里，Press 常与 Release 同帧送达，动画根本没时间播 —— 只有长按看得见。
            // 现在怎么保证：ufiPressScale 事件驱动编排 + 最短保持 Duration.Micro（120ms）。
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
            ) { onSelect(value) },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 2026-08-09 18:44 修复：选中时圆点保持 dotColor（level 语义色：警告橙/严重红），
            // 不改成白色——白色圆点丢失了 severity 语义，用户反馈"切换后强调色圆点变成白色"。
            Box(
                Modifier
                    .size(6.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                style = UfiTextStyles.caption.copy(fontWeight = if (isSelected) UfiWeight.Strong else UfiWeight.Medium),
                color = textColor
            )
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .height(16.dp)
                    .background(
                        if (isSelected) palette.onAccent.copy(alpha = 0.25f) else palette.divider,
                        CircleShape
                    )
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = count.toString(),
                    style = UfiTextStyles.captionStrong,
                    color = if (isSelected) palette.onAccent else palette.textSecondary
                )
            }
        }
    }
}

/**
 * 通用无圆点筛选 chip（事件类型 / 已读状态复用），动效与 [MonitorLevelChip] 一致：
 * 按压缩放 [UfiMotion.PressScale.Chip]（0.97，P2d 由 0.94 对齐）+ 选中 accent 实底 / 未选中 cardBg，
 * 200ms 颜色过渡；P2f 起缩放由 [ufiPressScale] 编排，短按也看得见。
 */
@Composable
private fun MonitorFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    // 同 MonitorLevelChip：isPressed 只留给配色，缩放交给 ufiPressScale；
    // 2026-09-04（P2f 配色补齐）按下态改由 rememberUfiPressed 保底 120ms，短按也看得见变色。
    val isPressed by rememberUfiPressed(interactionSource)
    val bgColor by animateColorAsState(
        targetValue = when {
            selected -> palette.accent
            isPressed -> palette.cardBorder.copy(alpha = 0.25f)
            else -> palette.cardBg
        },
        animationSpec = tween(UfiMotion.Duration.Base),
        label = "filterChipBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) palette.accent else palette.cardBorder.copy(alpha = 0.6f),
        animationSpec = tween(UfiMotion.Duration.Base),
        label = "filterChipBorder"
    )
    val textColor = if (selected) palette.onAccent else if (isPressed) palette.textPrimary else palette.textSecondary
    Box(
        modifier = modifier
            .wrapContentWidth()
            .height(32.dp)
            // 2026-09-04（P2d）：0.94 → UfiMotion.PressScale.Chip（0.97），与 CategoryChip /
            // EventTypeChip 等所有胶囊筛选器对齐。
            // 2026-09-04（P2f 短按看不见）：改用 ufiPressScale，原因同 MonitorLevelChip
            // （animateFloatAsState 抬手即被拉回，短按走不出可见幅度）。
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
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = UfiTextStyles.caption.copy(fontWeight = if (selected) UfiWeight.Strong else UfiWeight.Medium),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 事件中心「事件类型」筛选：全部 + 动态类型 chips（横向滚动）。
 * selectedType = null 表示全部类型；type 文案取自后端告警 type 字段（按当前告警去重排序）。
 */
@Composable
fun MonitorTypeFilter(
    selectedType: String?,
    types: List<String>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    // 2026-08-13 v3：改回单行横向滚动，双栏被反馈选项过小。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        MonitorFilterChip(
            selected = selectedType == null,
            label = "全部",
            onClick = { onSelect(null) }
        )
        types.forEach { t ->
            MonitorFilterChip(
                selected = selectedType == t,
                label = t,
                onClick = { onSelect(t) }
            )
        }
    }
}

/**
 * 事件中心「已读 / 未读」筛选：全部 / 未读 / 已读 三段。
 */
@Composable
fun MonitorReadFilter(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 2026-08-13 v3：改回单行自适应内容宽度。
    Row(
        modifier = modifier.wrapContentWidth(Alignment.Start),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        MonitorFilterChip(selected = selected == "all", label = "全部", onClick = { onSelect("all") })
        MonitorFilterChip(selected = selected == "unread", label = "未读", onClick = { onSelect("unread") })
        MonitorFilterChip(selected = selected == "read", label = "已读", onClick = { onSelect("read") })
    }
}

/**
 * 事件中心排序入口按钮（2026-08-13 v2 重做）：40dp 方形图标按钮，与搜索/筛选按钮同尺寸同圆角。
 *
 * 之前是「箭头图标 + 时间/严重度」文字胶囊，点击在两档间盲切，既占宽又看不出是否生效。
 * 现在点击打开排序弹窗（四档显式选择），按钮本身只负责表达「是否已改过排序」：
 * 非默认排序（≠ 时间·最新在前）时 accent 实心填充，一眼可辨。当前排序的完整文案由工具栏
 * 左侧副标题展示（如「时间 · 最新在前」）。
 */
@Composable
internal fun MonitorSortButton(
    sortMode: EventSortMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val active = sortMode != EventSortMode.TimeDesc
    OutlinedButton(
        onClick = onClick,
        // 2026-09-04（P2d）：0.96 → UfiMotion.PressScale.Button（值不变）。本控件是 OutlinedButton，
        // 属于按钮族，spec 也已经是 buttonPress()，与 UfiButton 那 5 个完全同款。
        // 2026-09-04（P2f 短按看不见）：原为 animateFloatAsState + graphicsLayer。
        // 为什么原来短按看不见：抬手瞬间目标翻回 1f，0.04 的落差配 spring(1.0/600) 一帧只走 6%
        // （scale ≈ 0.998）；工具栏在可滚动页面里，Press 常与 Release 同帧到达，可用时长为 0。
        // 现在怎么保证：ufiPressScale 事件驱动编排 + 最短保持 Duration.Micro（120ms）。
        modifier = modifier
            .size(40.dp)
            .ufiPressScale(
                interactionSource = interactionSource,
                pressedScale = UfiMotion.PressScale.Button,
                spec = UfiMotion.buttonPress()
            ),
        shape = UfiCardDefaults.buttonShape,
        border = BorderStroke(1.dp, palette.accent),
        colors = if (active) {
            ButtonDefaults.outlinedButtonColors(containerColor = palette.accent, contentColor = palette.onAccent)
        } else {
            ButtonDefaults.outlinedButtonColors(contentColor = palette.accent)
        },
        contentPadding = PaddingValues(0.dp),
        interactionSource = interactionSource
    ) {
        Icon(
            imageVector = Icons.Filled.SwapVert,
            contentDescription = "排序：${sortMode.label}",
            tint = if (active) palette.onAccent else palette.accent,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 排序选择弹窗（2026-08-13 v3）：四档排序显式可选，采用「草稿 + 确认」模式。
 * 弹窗内维护临时选中状态 [draft]，点击选项仅切换草稿；只有点击「确认」才通过 [onSelect] 提交并关闭，
 * 点击「取消」直接放弃修改。选项使用单行大按钮，避免双栏过小不易点击。
 */
@Composable
fun MonitorSortDialog(
    visible: Boolean,
    current: EventSortMode,
    onDismiss: () -> Unit,
    onSelect: (EventSortMode) -> Unit
) {
    var draft by remember(current) { mutableStateOf(current) }
    val palette = LocalResolvedPalette.current
    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "事件排序",
        confirmButton = {
            UfiButton(
                text = "确认",
                onClick = {
                    onSelect(draft)
                    onDismiss()
                }
            )
        },
        dismissButton = { UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss) }
    ) {
        // v3：单行大按钮，每行一个排序选项，文字/图标更大、更易点击。
        EventSortMode.entries.forEach { mode ->
            val selected = mode == draft
            val bg = if (selected) palette.accent else palette.cardBg
            val content = if (selected) palette.onAccent else palette.textSecondary
            val border = if (selected) palette.accent else palette.cardBorder.copy(alpha = 0.6f)
            val interactionSource = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    // 2026-09-04（P2d）：0.97 → UfiMotion.PressScale.Chip（值不变），可点列表行同档。
                    // 2026-09-04（P2f 短按看不见）：改用 ufiPressScale。原写法（animateFloatAsState
                    // 跟随 isPressed）在抬手瞬间就被拉回 1f，短按走不出可见幅度；弹窗里的按钮
                    // 同样中招，正是用户实测反馈的"弹窗内的按钮也一样"。
                    .ufiPressScale(
                        interactionSource = interactionSource,
                        pressedScale = UfiMotion.PressScale.Chip,
                        spec = tween(UfiMotion.Duration.Micro)
                    )
                    .clip(UfiCardDefaults.shape)
                    .background(bg)
                    .border(1.dp, border, UfiCardDefaults.shape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { draft = mode }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (mode.isAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = mode.label,
                    style = UfiTextStyles.body.copy(fontWeight = if (selected) UfiWeight.Strong else UfiWeight.Medium),
                    color = content,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "已选择",
                        tint = content,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 总览面板卡片容器：与 MonitorScreen 内私有 MonitorSectionCard 同风格
 * （ufiCardShadow(3.dp) + 1dp divider 描边 + 10dp 圆角 + cardBg + CardPadding）。
 * MonitorSectionCard 为 MonitorScreen 私有组件跨文件不可见，此处按同风格复制（红线允许）。
 */
@Composable
private fun OverviewCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalResolvedPalette.current
    val cardShape = UfiCardDefaults.shape

    // 2026-08-08 13:15 对齐仪表盘/网络 hero 风格：描边改用 palette.cardBorder（深色 accent 衍生）替代 divider（浅色），
    // 阴影 3dp → 4dp 对齐 UfiCardDefaults.elevationDp。圆角改用 UfiCardDefaults.shape（同 10dp，唯一形状源）。
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.CardHorizontalMargin,
                end = Spacing.CardHorizontalMargin,
                bottom = Spacing.CardBottomMargin
            )
            .ufiCardShadow(elevation = 4.dp, shape = cardShape)
            .border(1.dp, palette.cardBorder, cardShape),
        shape = cardShape,
        color = palette.cardBg
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.CardPadding),
            content = content
        )
    }
}

/** severity → 语义色：critical→error / warning→warning / info 及其它→success */
private fun levelColor(palette: ResolvedPalette, level: String): Color = when (level) {
    "critical" -> palette.error
    "warning" -> palette.warning
    else -> palette.success
}

/**
 * 事件列表排序（2026-08-13 扩展为四档）：时间↓/时间↑/严重度↓/严重度↑。
 *
 * 此前只有「时间↓ / 严重度↓」两档且靠一个按钮盲切，用户无法确认排序是否生效；
 * 现在四档由弹窗显式选择（当前项打勾），并且列表分组维度跟随排序维度变化（见 MonitorTimeline）。
 */
enum class EventSortMode { TimeDesc, TimeAsc, SeverityDesc, SeverityAsc }

/** 是否按严重度排序（true=严重度，false=时间） */
val EventSortMode.isSeverity: Boolean
    get() = this == EventSortMode.SeverityDesc || this == EventSortMode.SeverityAsc

/** 是否升序（时间：早→晚；严重度：低→高） */
val EventSortMode.isAscending: Boolean
    get() = this == EventSortMode.TimeAsc || this == EventSortMode.SeverityAsc

/** 排序模式中文标签，用于按钮与弹窗选项 */
val EventSortMode.label: String
    get() = when (this) {
        EventSortMode.TimeDesc -> "时间 · 最新在前"
        EventSortMode.TimeAsc -> "时间 · 最早在前"
        EventSortMode.SeverityDesc -> "严重度 · 高到低"
        EventSortMode.SeverityAsc -> "严重度 · 低到高"
    }

/** 排序模式短标签（工具栏按钮空间有限时用） */
val EventSortMode.shortLabel: String
    get() = if (isSeverity) "严重度" else "时间"

private fun severityText(level: String): String = when (level) {
    "critical" -> "严重"
    "warning" -> "警告"
    else -> "提示"
}

/**
 * 统一换算告警标题里的数据单位。
 * 历史告警的 [message] 可能由旧版 Core 直接拼出 "11201MB"，因此 UI 层再做一次兜底换算：
 * 仅对 traffic 类型，把消息中所有 "数字MB" 替换为自动换算后的 MB/GB/TB。
 * 新版 Core 已输出 human-readable 消息时，正则不会命中，原样返回。
 */
private fun formatAlertMessage(type: String, message: String): String {
    if (type != "traffic") return message
    return message.replace(TRAFFIC_MB_REGEX) { matchResult ->
        val mb = matchResult.groupValues[1].toLongOrNull()
        if (mb != null) FormatUtils.formatBytes(mb * 1024 * 1024) else matchResult.value
    }
}

/** 事件卡底部语义标签：同色系 12% 底 + 同色文字，比原来的灰底标签更易区分严重度/类型 */
@Composable
private fun EventTagChip(text: String, color: Color) {
    Text(
        text = text,
        style = UfiTextStyles.caption.copy(fontWeight = UfiWeight.Medium),
        color = color,
        modifier = Modifier
            .clip(UfiCardDefaults.tagShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

/** 事件时间智能格式：今天内只显示 HH:mm（更清爽），跨天显示 MM/dd HH:mm */
private fun formatAlertTimeSmart(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(ms).atZone(zone)
    return if (at.toLocalDate() == LocalDate.now(zone)) TIME_HM.format(at) else TIME_MD_HM.format(at)
}

// 2026-09-08：`EmptyTimelineState` 已删除 —— 它只服务于同日删掉的 MonitorEventList。
// 事件中心的空态现在统一走公共 UfiEmptyState（与筛选无命中、本页无事件同一套观感）。

/** 事件分类 type → 中文标签（时间轴卡片副文案） */
fun alertTypeLabel(type: String): String = when (type) {
    "temperature" -> "温度告警"
    "battery" -> "电量告警"
    "traffic" -> "流量告警"
    "signal" -> "信号告警"
    "connectivity" -> "连接告警"
    else -> "设备通知"
}

// ========== 总览「今日峰值」区块（2026-08-15 新增） ==========
// 6 张峰值卡（CPU / 内存 / 流量 / 信号 / 温度 / 今日流量），2×3 压扁网格。
// 数据窗口：今天 00:00 至今（与总览「今天」语义一致）。
// 单格：指标名 + 状态点 + 峰值大数字 + 单位（2026-08-26 起去掉迷你 sparkline，只留峰值数字）。
// 阈值方向按 MonitorMetricType.lowerIsWorse；优先 state.thresholds，未配置回退默认阈值。
// 不可点击下钻（纯展示）。空数据数值显示「—」并给出原因文案。

/** 单格数据（已计算好展示所需的一切，组件只负责渲染） */
private data class PeakMetricItem(
    val label: String,
    val valueText: String,            // 主数字（已格式化；未开启/无数据时为 "—"）
    val unit: String?,                // 单位（valueText 已含单位如 "1.2 GB" 时为 null）
    val level: ThresholdLevel,        // 阈值等级（决定大数字/状态点颜色）
    val enabled: Boolean = true,        // 该指标是否在设置中开启
    val emptyHint: String? = null     // 未开启/空数据时的辅助文案（null=正常）
)

/** 计算今日 00:00 的 epoch ms（本地时区） */
private fun todayStartMillis(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/**
 * 以下三个 helper 都是「零分配」的窗口扫描：直接跳过 `t < since` 的点，
 * 不再先 `filter{}` 出「今天」的子列表。原写法每次派生都要为 8 条 720 点序列各拷一份新 list
 * （约 5760 个元素），而这个派生会随 MonitorState 的每次发射重跑 —— 是切进监控页掉帧的一部分。
 */
private fun hasPointsSince(points: List<DownsampledPoint>, since: Long): Boolean {
    points.forEach { if (it.t >= since) return true }
    return false
}

/** [since] 之后的峰值：lowerIsWorse=true 取 min 最小（最差），否则取 max 最大；无有效点 → NaN */
private fun peakSince(points: List<DownsampledPoint>, since: Long, lowerIsWorse: Boolean): Double {
    var bv = if (lowerIsWorse) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
    points.forEach { p ->
        if (p.t < since) return@forEach
        val v = if (lowerIsWorse) p.min else p.max
        if (if (lowerIsWorse) v < bv else v > bv) bv = v
    }
    // 全 NaN 序列的比较全为 false，bv 会留在 ±Infinity 被原样打印成 "-Infinity"，统一收敛成 NaN
    return if (bv.isFinite()) bv else Double.NaN
}

/** [since] 之后是否有 avg≠0 的温度读数（传感器未就绪时后端常返回全 0，应视为「无数据」） */
private fun hasRealTempSince(points: List<DownsampledPoint>, since: Long): Boolean {
    points.forEach { if (it.t >= since && it.avg != 0.0) return true }
    return false
}

/**
 * 阈值等级：有配置就用配置，没配过才回落到 MonitorMetricType 的内置默认阈值。
 * 注意 `enabled = false` 表示"用户明确关掉了这条阈值"，必须直接判 NORMAL —— 旧写法
 * （cfg != null && cfg.enabled 才用 cfg，否则回落默认）会把"关掉"当成"没配过"，等于关不掉。
 * 现状补充：state.thresholds 目前还没有任何写入点，所以实际用的一直是内置默认阈值。
 * 方向按 lowerIsWorse：false=越大越糟（value>=阈值），true=越小越糟（value<=阈值）。
 */
private fun thresholdLevelOf(
    thresholds: Map<String, MetricThreshold>,
    apiKey: String,
    value: Double,
    lowerIsWorse: Boolean
): ThresholdLevel {
    if (value.isNaN()) return ThresholdLevel.NORMAL
    val mt = MonitorMetricType.fromApiKey(apiKey) ?: return ThresholdLevel.NORMAL
    val thr = thresholds[apiKey] ?: MetricThreshold(mt.defaultWarn, mt.defaultAlert, enabled = true)
    if (!thr.enabled) return ThresholdLevel.NORMAL
    thr.alertValue?.let { if (if (lowerIsWorse) value <= it else value >= it) return ThresholdLevel.ALERT }
    thr.warnValue?.let { if (if (lowerIsWorse) value <= it else value >= it) return ThresholdLevel.WARN }
    return ThresholdLevel.NORMAL
}

/** 从 monitorState 计算 6 张峰值卡；空/未开启指标用辅助文案说明原因。 */
private fun computePeakMetrics(
    state: MonitorState,
    trafficSummary: TrafficSummary?
): List<PeakMetricItem> {
    val start = todayStartMillis()
    val enabled = { key: String -> state.settings.enabledTypes.contains(key) }
    fun levelFor(apiKey: String, value: Double, lowerIsWorse: Boolean): ThresholdLevel =
        thresholdLevelOf(state.thresholds, apiKey, value, lowerIsWorse)

    // Locale.ROOT：默认 Locale 在阿拉伯语等语系下会输出非 ASCII 数字，和等宽大数字排版冲突
    fun fmt(v: Double): String = if (v.isNaN()) "—" else String.format(Locale.ROOT, "%.0f", v)

    // 2026-08-31：局部 formatTrafficRate 已删除 —— 收敛到
    // [FormatUtils.formatRate]（口径不变，额外获得 GB/s 档）。


    val items = ArrayList<PeakMetricItem>(6)

    // 1. CPU 峰值（%）
    if (!enabled("cpu")) {
        items += PeakMetricItem("CPU 峰值", "—", "%", ThresholdLevel.NORMAL, enabled = false, emptyHint = "未开启")
    } else {
        val cpuV = peakSince(state.cpuHistory, start, false)
        val hint = if (hasPointsSince(state.cpuHistory, start)) null else "暂无数据"
        items += PeakMetricItem("CPU 峰值", fmt(cpuV), "%", levelFor("cpu", cpuV, false), emptyHint = hint)
    }

    // 2. 内存峰值（%：后端 memory_history 存的是 usagePercent，不是 MB）
    if (!enabled("memory")) {
        items += PeakMetricItem("内存峰值", "—", "%", ThresholdLevel.NORMAL, enabled = false, emptyHint = "未开启")
    } else {
        val memV = peakSince(state.memoryHistory, start, false)
        val hint = if (hasPointsSince(state.memoryHistory, start)) null else "暂无数据"
        items += PeakMetricItem("内存峰值", fmt(memV), "%", levelFor("memory", memV, false), emptyHint = hint)
    }

    // 3. 流量峰值（RX+TX 合计，后端给的是 B/s，按 1024 自适应到 B/s / KB/s / MB/s，
    //    unit=null 让 valueText 自带"X B/s"等，与 charts 页 valueFormatter=::formatBytes 同口径；
    //    与"今日流量"卡的 "0 B" 视觉一致。启用条件：rx/tx 任一开启即展示，两者共用一份阈值）
    if (!enabled("traffic_rx") && !enabled("traffic_tx")) {
        items += PeakMetricItem("流量峰值", "—", null, ThresholdLevel.NORMAL, enabled = false, emptyHint = "未开启")
    } else {
        val rxHist = state.trafficRxHistory
        val txHist = state.trafficTxHistory
        val rxV = peakSince(rxHist, start, false)
        val txV = peakSince(txHist, start, false)
        val hasRx = hasPointsSince(rxHist, start)
        val hasTx = hasPointsSince(txHist, start)
        // 合计峰值必须按「同一时刻」相加后再取最大：直接 max(rx)+max(tx) 会把凌晨的下行峰值和
        // 傍晚的上行峰值拼成一个从未真实出现过的数字。按桶时间戳对齐求和，缺失的一侧记 0
        // （该桶确实没有采样），再取最大值。
        val trafficPeak = if (!hasRx && !hasTx) Double.NaN else {
            val txByT = HashMap<Long, Double>(txHist.size.coerceAtLeast(1))
            txHist.forEach { if (it.t >= start) txByT[it.t] = it.max }
            var best = Double.NaN
            rxHist.forEach { p ->
                if (p.t < start) return@forEach
                val r = if (p.max.isNaN()) 0.0 else p.max.coerceAtLeast(0.0)
                val x = txByT.remove(p.t)?.let { if (it.isNaN()) 0.0 else it.coerceAtLeast(0.0) } ?: 0.0
                val sum = r + x
                if (best.isNaN() || sum > best) best = sum
            }
            // 只有 TX 有采样的桶（remove 后剩下的）
            txByT.values.forEach { v ->
                val sum = if (v.isNaN()) 0.0 else v.coerceAtLeast(0.0)
                if (best.isNaN() || sum > best) best = sum
            }
            best
        }
        val hint = if (hasRx || hasTx) null else "暂无数据"
        // 阈值按各自的 key 判定后取最差：把"RX+TX 合计"拿去查 traffic_rx 的阈值属于口径错位
        // （阈值是给单方向速率配的），合计值只用于显示。
        val rxLevel = levelFor("traffic_rx", rxV, false)
        val txLevel = levelFor("traffic_tx", txV, false)
        val trafficLevel = if (txLevel.ordinal > rxLevel.ordinal) txLevel else rxLevel
        items += PeakMetricItem("流量峰值", FormatUtils.formatRate(trafficPeak), null, trafficLevel, emptyHint = hint)
    }

    // 4. 信号最差（RSRP，dBm，越小越差）
    if (!enabled("signal_rsrp")) {
        items += PeakMetricItem("信号最差", "—", "dBm", ThresholdLevel.NORMAL, enabled = false, emptyHint = "未开启")
    } else {
        val sigV = peakSince(state.signalRsrpHistory, start, true)
        val hint = if (hasPointsSince(state.signalRsrpHistory, start)) null else "暂无数据"
        items += PeakMetricItem("信号最差", fmt(sigV), "dBm", levelFor("signal_rsrp", sigV, true), emptyHint = hint)
    }

    // 5. 温度峰值（°C）：全 0 视为「无数据」而不是设备真的 0°C
    if (!enabled("temperature")) {
        items += PeakMetricItem("温度峰值", "—", "°C", ThresholdLevel.NORMAL, enabled = false, emptyHint = "未开启")
    } else {
        val hasReal = hasRealTempSince(state.temperatureHistory, start)
        val tempV = if (hasReal) peakSince(state.temperatureHistory, start, false) else Double.NaN
        val hint = if (hasReal) null else "暂无数据"
        items += PeakMetricItem("温度峰值", fmt(tempV), "°C", levelFor("temperature", tempV, false), emptyHint = hint)
    }

    // 6. 今日流量（累计：core 给的 today_total_display = 上行+下行合计，无阈值色）
    // 仪表盘还没拉到数据时必须显式置灰 + 给原因，否则一张"正常卡"里写着"—"，
    // 看起来像设备今天真的零流量。
    val todayDisp = trafficSummary?.today_total_display?.takeIf { it.isNotBlank() }
    items += if (todayDisp == null) {
        PeakMetricItem("今日流量", "—", null, ThresholdLevel.NORMAL, emptyHint = "暂无数据")
    } else {
        PeakMetricItem("今日流量", todayDisp, null, ThresholdLevel.NORMAL)
    }

    return items
}

/**
 * v20h：从 6 格今日峰值里挑出「最差的阈值等级」+ 处于该等级的指标条数 ——
 * 让 EventSummaryCard 在「无未读告警但指标超阈值」的场景下（典型：CPU 90% WARN）
 * 也能给出对应 hero 视觉与准确的"N 项"，而不是一直说"运行正常"或写死"1 项"。
 *
 * 返回 NORMAL to 0 = 没有指标超阈值（与 unread==0 同时成立 → hero 显示绿色"运行正常"）。
 *
 * 实现：与网格共用同一套阈值/口径判定（见 [computePeakMetricsForLevel]），避免 hero 与下方网格互相打脸。
 */
fun worstPeakSummary(state: MonitorState): Pair<ThresholdLevel, Int> {
    val items = computePeakMetricsForLevel(state)
    var worst = ThresholdLevel.NORMAL
    items.forEach { (_, _, level) ->
        if (level.ordinal > worst.ordinal) worst = level
    }
    val count = if (worst == ThresholdLevel.NORMAL) 0 else items.count { it.third == worst }
    return worst to count
}

/**
 * v20h：仅用于 [worstPeakSummary] 的轻量派生 —— 取今日窗口内 5 个有阈值指标的峰值 + 等级，
 * 不构造 PeakMetricItem（无需 label/icon/unit），减少浪费。
 * 复用 computePeakMetrics 里的 peakOf/levelFor 闭包，但拆出来避免对 dashboardsState 依赖。
 */
private fun computePeakMetricsForLevel(state: MonitorState): List<Triple<String, Double, ThresholdLevel>> {
    val start = todayStartMillis()
    fun levelFor(apiKey: String, value: Double, lowerIsWorse: Boolean): ThresholdLevel =
        thresholdLevelOf(state.thresholds, apiKey, value, lowerIsWorse)
    val out = ArrayList<Triple<String, Double, ThresholdLevel>>(5)
    // 5 个有阈值指标（跳过 today_traffic，无阈值）
    if (state.settings.enabledTypes.contains("cpu")) {
        val v = peakSince(state.cpuHistory, start, false)
        out += Triple("cpu", v, levelFor("cpu", v, false))
    }
    if (state.settings.enabledTypes.contains("memory")) {
        val v = peakSince(state.memoryHistory, start, false)
        out += Triple("memory", v, levelFor("memory", v, false))
    }
    if (state.settings.enabledTypes.contains("signal_rsrp")) {
        val v = peakSince(state.signalRsrpHistory, start, true)
        out += Triple("signal_rsrp", v, levelFor("signal_rsrp", v, true))
    }
    // 流量口径必须与网格里的「流量峰值」完全一致（rx/tx 任一开启即计入、各自按自己的阈值键判定后取最差），
    // 否则 hero 说"运行正常"而下面那格是橙的，两处自相矛盾。
    if (state.settings.enabledTypes.contains("traffic_rx") ||
        state.settings.enabledTypes.contains("traffic_tx")
    ) {
        val rxV = peakSince(state.trafficRxHistory, start, false)
        val txV = peakSince(state.trafficTxHistory, start, false)
        val rxLevel = levelFor("traffic_rx", rxV, false)
        val txLevel = levelFor("traffic_tx", txV, false)
        if (txLevel.ordinal > rxLevel.ordinal) {
            out += Triple("traffic_tx", txV, txLevel)
        } else {
            out += Triple("traffic_rx", rxV, rxLevel)
        }
    }
    if (state.settings.enabledTypes.contains("temperature")) {
        val real = if (hasRealTempSince(state.temperatureHistory, start)) {
            peakSince(state.temperatureHistory, start, false)
        } else Double.NaN
        out += Triple("temperature", real, levelFor("temperature", real, false))
    }
    return out
}

/**
 * 总览「今日峰值」区块：单卡包裹的 3×2 网格，卡内用细发丝线（1dp divider）分隔六个指标，
 * 去掉逐卡 Surface / 阴影 / 左侧彩条 + 图标三件套的 AI 模板感。分区标题已按需求移除。
 * 调用处负责把它放在摘要卡下方、事件时间轴上方。
 */
@Composable
fun PeakMetricsGrid(state: MonitorState, trafficSummary: TrafficSummary?, modifier: Modifier = Modifier) {
    val items = remember(state, trafficSummary) { computePeakMetrics(state, trafficSummary) }
    val palette = LocalResolvedPalette.current
    val cardShape = UfiCardDefaults.shape
    // 单卡容器（对齐项目 OverviewCard 风格：描边 + 阴影 + 基准圆角），内部网格靠发丝线分区
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.CardHorizontalMargin,
                end = Spacing.CardHorizontalMargin,
                top = Spacing.Small,
                bottom = Spacing.CardBottomMargin
            )
            .ufiCardShadow(elevation = 4.dp, shape = cardShape)
            .border(1.dp, palette.cardBorder, cardShape),
        shape = cardShape,
        color = palette.cardBg
    ) {
        Column(Modifier.fillMaxWidth()) {
            // computePeakMetrics 恒返回 6 项（6 个区块每支都恰好 += 1），所以这里恒为 2×3；
            // 不写补位分支（那是永不可达的死代码），改动 computePeakMetrics 时靠这条断言兜住。
            require(items.size == 6) { "峰值网格需要恰好 6 项，实际 ${items.size}" }
            items.chunked(3).forEachIndexed { rowIdx, rowItems ->
                // 行之间加一条横向发丝线（放在行前，行数变化也不会漏画/多画）
                if (rowIdx > 0) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
                }
                // height(IntrinsicSize.Min)：本网格在 LazyColumn 里，行的高度约束是无界的，
                // 无界时 fillMaxHeight() 不起作用 → 竖向发丝线会被测成 0 高度（完全看不见）。
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    rowItems.forEachIndexed { colIdx, item ->
                        if (colIdx > 0) {
                            Box(Modifier.width(1.dp).fillMaxHeight().background(palette.divider))
                        }
                        Box(Modifier.weight(1f)) { PeakMetricTile(item) }
                    }
                }
            }
        }
    }
}

/**
 * 单格峰值指标：指标名 + 状态点一行，下方峰值大数字 + 单位。
 * 无迷你趋势 / 描边 / 彩条（走势去图表页看）。空 / 未开启时显示原因并置灰。
 *
 * 2026-08-29：内容改为**整体水平居中**（原来是靠左 + 状态点被 weight(1f) 推到最右，
 * 6 格里的数字长短不一时视觉上参差不齐）。做法是 Column 加 CenterHorizontally，
 * 并把标题行的 weight(1f) 撤掉、改成 Arrangement.Center + 固定间距，
 * 这样「指标名 ● 」作为一个整体居中，而不是名字贴左、圆点贴右。
 */
@Composable
private fun PeakMetricTile(item: PeakMetricItem) {
    val palette = LocalResolvedPalette.current
    val isOk = item.enabled && item.emptyHint == null
    val statusColor = when {
        !isOk -> palette.divider
        item.level == ThresholdLevel.ALERT -> palette.error
        item.level == ThresholdLevel.WARN -> palette.warning
        else -> palette.accent
    }
    val numberColor = when {
        !isOk -> palette.textSecondary
        item.level == ThresholdLevel.ALERT -> palette.error
        item.level == ThresholdLevel.WARN -> palette.warning
        else -> palette.textPrimary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 66.dp)
            .padding(12.dp)
            .alpha(if (isOk) 1f else 0.55f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 指标名 + 状态点（阈值语义：红 / 橙 / 品牌蓝；空·未开启用发丝灰）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(Modifier.size(7.dp).background(statusColor, CircleShape))
        }
        Spacer(Modifier.height(6.dp))
        // 峰值大数字 + 单位
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = item.valueText,
                style = UfiTextStyles.valueStrong,
                color = numberColor
            )
            if (item.unit != null) {
                Text(item.unit, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
            }
        }
        if (!isOk) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.emptyHint ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}



// ==================== v20d 事件中心 UI 重写（参考用户 UI 截图） ====================

/**
 * v20d：事件中心顶部"3 张大卡"统计区（参考用户 UI 截图）。
 * 与 MonitorEventStatStrip（水平发丝线小条）不同的视觉风格：3 张独立 card，每张垂直布局
 * [● 圆点 + 标题 + 大数字]，选中态（对应 selectedLevel）边框加粗变色（参考图中"严重 1"红边框示意）。
 *
 * @param alerts 当前范围告警全量（用于计算 3 个计数）
 * @param selectedLevel 当前严重度筛选（"all" → 全部卡边框；"critical" → 严重卡红边框；其他 → 无边框）
 */
@Composable
fun MonitorEventStatCards(
    alerts: List<AlertRecord>,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val allCount = alerts.size
    val criticalCount = alerts.count { it.level == "critical" }
    val unreadCount = alerts.count { !it.acknowledged }
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(label = "全部", value = allCount, dotColor = palette.accent, modifier = Modifier.weight(1f))
        StatCard(label = "严重", value = criticalCount, dotColor = palette.error, modifier = Modifier.weight(1f))
        StatCard(label = "未读", value = unreadCount, dotColor = palette.warning, modifier = Modifier.weight(1f))
    }
}

/** 单个统计卡（圆点 + 标题 + 大数字）：统一 1dp 边框，着色装饰靠圆点 + 数字语义色 */
@Composable
private fun StatCard(
    label: String,
    value: Int,
    dotColor: Color,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = modifier
            .clip(UfiCardDefaults.shape)
            .background(palette.cardBg)
            .border(BorderStroke(1.dp, palette.cardBorder), UfiCardDefaults.shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = palette.textSecondary)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            value.toString(),
            style = UfiTextStyles.heroValue.copy(fontWeight = UfiWeight.Strong),
            color = dotColor
        )
    }
}

/**
 * v20d：单事件卡（参考用户 UI 截图）：
 * - 40dp 圆角色块（"严"/"警"/"提" 字符 + severity 色实底，已读淡化为 55%）
 * - 标题（titleSmall Bold 未读 / Medium 已读）
 * - 完整描述（bodySmall 灰，2 行 Ellipsis）
 * - meta 行：分类 · 严重度 + 未读时绿底"未读"胶囊
 * - 右上角时间（labelSmall）
 * - 点击 → onAckOne(id)；长按 → onDelete(id)（保留 v20c 删除入口）
 */
@Composable
fun MonitorEventCard(
    alert: AlertRecord,
    onAckOne: (Long) -> Unit,
    onDelete: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val severity = levelColor(palette, alert.level)
    val unread = !alert.acknowledged
    val typeLabel = alertTypeLabel(alert.type)
    val severityTag = severityText(alert.level)
    val typeIcon = alertTypeIcon(alert.type)
    // 2026-08-19 v20k：去掉与主标题重复的 alert.message 行（之前主标题"流量严重超额：10.9 GB" + 副标题
    // alert.message "流量严重超额：11201MB" 同时显示同一信息）。替换为「阈值 vs 当前」对比行，
    // 让用户在卡片里一眼看出「超了多少」；解析不到 value/threshold 时降级到 alert.message 单行显示。
    val thresholdLine = alertThresholdLine(alert.value, alert.threshold, alert.message)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onAckOne(alert.id) },
                onLongClick = onDelete?.let { cb -> { cb(alert.id) } }
            ),
        shape = UfiCardDefaults.shape,
        color = palette.cardBg,
        border = BorderStroke(1.dp, palette.cardBorder)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 2026-08-19 v20k：左侧 40dp 严重度角色块由"严/警/提"汉字 → M3 type 图标（DataUsage / DeviceThermostat / BatteryFull / SignalCellularAlt / WifiOff）。
            // 改进：1）一眼区分告警类型（之前所有卡都是红底"严"字，完全分不清流量/温度/电池/信号）；
            //      2）以图形代替单汉字视觉，去掉"emoji 占位符"观感；
            //      3）保留原色块视觉权重（红/橙/蓝 = 严重度等级；已读透明度降到 0.55 与旧逻辑一致）。
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(UfiCardDefaults.subtleShape)
                    .background(if (unread) severity else severity.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = typeIcon,
                    contentDescription = typeLabel,
                    // 2026-09-04（P2-中）不动：底色是 severity 实底（error/warning/accent），不是渐变，
                    // 归不到 onGradient；语义上应接 palette.onError 一类，但那是"语义实底之上的前景"
                    // 另一议题，本次只收敛渐变 Hero 卡。
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            // 中间信息列
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatAlertMessage(alert.type, alert.message),
                    style = UfiTextStyles.sectionTitle.copy(fontWeight = if (unread) UfiWeight.Strong else UfiWeight.Medium),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = thresholdLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                // 2026-08-19 v20k：底部 tag 由 "$typeLabel · $severityTag" 字符串改成两个独立 EventTagChip（流量/严重），
                // 同色系 12% 底 + 同色文字，已有的 EventTagChip 函数。视觉权重从「整行灰字」降为「两个小 chip」，
                // 与左侧色块共同表达"类型 + 严重度"——更紧凑、更易扫读。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EventTagChip(typeLabel, severity)
                    Spacer(Modifier.width(6.dp))
                    EventTagChip(severityTag, palette.textSecondary)
                    if (unread) {
                        Spacer(Modifier.width(6.dp))
                        EventTagChip("未读", palette.accent)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // 右上角时间
            Text(
                text = formatAlertTimeSmart(alert.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary
            )
        }
    }
}

/**
 * 2026-08-19 v20k：按 alert.type 选 M3 图标，作为事件卡左侧色块的图形内容。
 * 原实现用「严/警/提」单汉字作为图标，占位符观感，且完全无法区分告警类型。
 */
private fun alertTypeIcon(type: String): ImageVector = when (type) {
    "traffic" -> Icons.Default.DataUsage
    "temperature" -> Icons.Default.DeviceThermostat
    "battery" -> Icons.Default.BatteryFull
    "signal" -> Icons.Default.SignalCellularAlt
    "connectivity" -> Icons.Default.WifiOff
    else -> Icons.Default.Warning
}

/**
 * 2026-08-19 v20k：从 alert.value / alert.threshold 解析同单位对比行。
 * 优先形式：「阈值 X · 当前 Y · 超 Z%」（上下行单位一致时显示「超 ±Z%」，异单位时只显数值对比）。
 * 解析失败时降级为 alert.message 单行（保留原行为）。
 */
private val NUM_UNIT_REGEX = Regex("([-+]?[0-9]*\\.?[0-9]+)\\s*([A-Za-z%]+)")

private fun alertThresholdLine(valueStr: String, thresholdStr: String, fallback: String): String {
    fun parse(s: String): Pair<Double, String>? {
        val m = NUM_UNIT_REGEX.find(s.trim()) ?: return null
        val num = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2]
        // 字节类统一到 bytes，其它原样保留单位
        return when (unit.uppercase()) {
            "B" -> num to "B"
            "KB" -> num * 1024.0 to "B"
            "MB" -> num * 1024.0 * 1024.0 to "B"
            "GB" -> num * 1024.0 * 1024.0 * 1024.0 to "B"
            "TB" -> num * 1024.0 * 1024.0 * 1024.0 * 1024.0 to "B"
            else -> num to unit
        }
    }
    val v = parse(valueStr) ?: return fallback
    val t = parse(thresholdStr) ?: return fallback
    if (v.second != t.second) {
        // 不同单位（不应在同一条告警里出现，但保底）
        return "阈值 $thresholdStr · 当前 $valueStr"
    }
    val (vn, tu) = v
    val (tn, _) = t
    return if (tu == "B") {
        val humanCur = com.ufi_axis.util.FormatUtils.formatBytes(vn.toLong())
        val humanThr = com.ufi_axis.util.FormatUtils.formatBytes(tn.toLong())
        if (tn <= 0.0) "阈值 $humanThr · 当前 $humanCur"
        else {
            val pct = ((vn - tn) / tn) * 100.0
            val sign = if (pct >= 0) "+" else ""
            "阈值 $humanThr · 当前 $humanCur · $sign${"%.0f".format(pct)}%"
        }
    } else {
        // 非字节单位（如 %、°C）：保持原值 + 加减百分比
        if (tn <= 0.0) "阈值 $thresholdStr · 当前 $valueStr"
        else {
            val pct = ((vn - tn) / tn) * 100.0
            val sign = if (pct >= 0) "+" else ""
            "阈值 ${tn.toLong()} ${tu} · 当前 ${vn.toLong()} ${tu} · $sign${"%.0f".format(pct)}%"
        }
    }
}

// 2026-09-08：`MonitorEventList`（扁平 Column + forEach）已删除 —— 事件中心改成 LazyColumn，
// 每条事件是一个 items(...)，直接调 MonitorEventCard，空态由调用点的 UfiEmptyState 提供。
// 那个包装正是它注释里想避免的问题本身：整页事件一次性全部组合。


