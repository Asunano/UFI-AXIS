// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.animation.ufiPressScale
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.ResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
// 本包内有一个同名的 @Deprecated 转发壳 `UfiMotion`（Ufi.kt）；不加别名会优先命中它 → 无谓的废弃告警。
import com.ufi_axis.ui.theme.UfiMotion as ThemeMotion

/**
 * 分段控件里单个"段"的高度见 [Spacing.SegmentHeight]（2026-09-05 P3 令牌纪律：
 * 它是 5+ 个调用点共同依赖的"一行 32dp 控件"占位契约，不是本文件的私事）。
 */

/**
 * 选中段/滑块的填色浓度（accent 在 `cardBg` 上的混合比例）。
 *
 * 2026-09-05 由 `accentContainer`（accent 的 10%/20%）提到 30%：用户反馈"选中项着色太浅"。
 * 原值在浅色主题下几乎与卡面同色，选中态只能靠文字色分辨。30% 是"一眼看得出被选中、
 * 又不至于压过 accent 文字"的档位（accent 文字压在 30% 自混底上仍有足够明度差）；
 * 再配一道 [segmentSelectedBorder] 描边定形，不靠单一明度差撑可见性。
 */
private const val SEGMENT_FILL_ALPHA: Float = 0.30f

/**
 * 选中段/滑块的填色。
 *
 * 半透明色直接铺在无实底的轨道上会透出页面内容而混色 —— 与 [UfiPagination] 按钮同一个坑、
 * 同一份解法：先 `compositeOver(cardBg)` 压成不透明实色，观感仍是"accent 淡底"，
 * 但不再受下层内容影响。全站保持同一份处理。
 */
private val ResolvedPalette.segmentSelectedFill: Color
    get() = accent.copy(alpha = SEGMENT_FILL_ALPHA).compositeOver(cardBg)

/** 选中段描边：给滑块一个明确边界，不让可见性只押在填色明度上。 */
private val ResolvedPalette.segmentSelectedBorder: Color
    get() = accent.copy(alpha = 0.45f)

/**
 * 单选分段控件（segmented control）。
 *
 * 2026-09-04 重做：原实现是 M3 [FilterChip]（`chipSelectedBg` 12% 淡底 + 1dp 描边 + ripple），
 * 观感是"一排安卓原生 chip"，与本项目"卡片 + 令牌化阴影 + 无 ripple 缩放反馈"的语言不一致。
 * 现在改成分段控件：
 * - 轨道：**完全透明**底 + 1dp [com.ufi_axis.ui.theme.ThemePalette.inputBorder] 描边 + `pillShape`
 *   + [Spacing.Small] 内边距；
 * - 选中段：`accentContainer.compositeOver(cardBg)` 实色 + `pillShape` + `accent` 文字
 *   + [UfiWeight.Emphasis]，**无阴影**；
 * - 未选中段：完全透明底 + `textSecondary` 常规字重；
 * - 按压：每段各一份 `MutableInteractionSource` + `ufiPressScale(PressScale.Chip)`，无 ripple。
 *
 * ## 为什么轨道靠描边而不是实底（2026-09-04 可见性返工）
 * 上一版是"`surfaceMuted` 轨道 + `cardBg` 选中段"，两端主题下都看不清：浅色
 * `surfaceMuted == pageBg`（[com.ufi_axis.ui.theme.ThemePalette.surfaceMuted]），轨道与页面底同色，
 * 而选中段 `cardBg` 是白的，与卡面同色；深色 `surfaceMuted == cardBg × 0.7`，选中段与轨道只差一点
 * 透明度。这套配色的可见性全押在"明度差"上，而明度差在两端都被压掉了。现在改成**靠色相区分**：
 * 轨道用描边（描边在任何底色上都可见，不依赖 `surfaceMuted` 究竟等于哪个槽），选中段用带 accent
 * 色相的实色（与 [UfiPagination] 按钮完全同一份 `compositeOver` 处理，全站一致）。
 * 阴影一并去掉——轨道已无实底，"浮起"没有依托，阴影只会显脏。
 *
 * 选中态的移动动画分两种（取决于布局形态）：
 * - 单行（`wrap = false`，无论等宽还是 `wrapContent` 内容宽）：真滑块 —— 段宽由布局回报，
 *   滑块的 x/宽两个量各跑一条 [ThemeMotion.tabSlider] 弹簧，在 `drawBehind` 里绘制；
 * - 折行（`wrap = true`）：段跨行，位移无意义，退化为选中段底色 + 文字色的
 *   `animateColorAsState`([ThemeMotion.colorSwap]) 交叉淡入。
 *
 * 公开签名与 2026-08-31 版完全一致（[F24] STABLE-UI-API），只换内部实现与视觉。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UfiSingleChipSelector(
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    wrapContent: Boolean = false,
    /**
     * 2026-08-31 新增：选项多到一行放不下时换成 [FlowRow] 自动折行
     * （如下载设置的快捷路径 chip 组）。默认 false = 单行 [Row]，保持既有页面不变。
     */
    wrap: Boolean = false,
    /** 2026-08-31 新增：整组置灰不可点（如后台守护未开启时的巡检间隔 chip 组）。 */
    enabled: Boolean = true
) {
    val palette = LocalResolvedPalette.current
    // wrapContent = true 表示「按内容宽度排布」，此时轨道也不必撑满
    // 轨道无实底：只有 1dp inputBorder 描边（12% 黑/白），所以放在 pageBg 还是 cardBg 上都看得见轮廓。
    val trackModifier = (if (wrapContent) modifier else modifier.fillMaxWidth())
        .border(UfiCardDefaults.hairlineBorderWidth, palette.inputBorder, UfiCardDefaults.pillShape)
        .padding(Spacing.Small)

    when {
        // 折行形态：段跨行，位移无意义，只能退化为各段底色 + 文字色交叉淡入。
        wrap -> FlowRow(
            modifier = trackModifier,
            verticalArrangement = Arrangement.spacedBy(Spacing.Small)
        ) {
            options.forEach { (value, label) ->
                UfiSegment(
                    label = label,
                    selected = value == selectedValue,
                    enabled = enabled,
                    paintSelectedBackground = true,
                    onClick = { onSelect(value) }
                )
            }
        }

        // 单行形态（等宽 / 内容宽**都走这里**）：滑块几何由布局回报的段宽算出。
        //
        // 2026-09-05（「监控中心总览切聚合/明细丢动画、抽搐」）：
        // 旧实现只在「等宽单行」画滑块，`wrapContent = true` 的调用点（监控中心两处模式切换、
        // 事件筛选弹窗…）**根本没有滑块**，切换时只有底色淡入淡出 —— 这就是"丢失动画"。
        // 而"抽搐"是另一回事：选中段原来会从 caption 换成 captionEmphasis，字重一变文本宽度就变，
        // 内容宽形态下整行立刻重新排版，两段同时左右跳一下（字重问题见 UfiSegment）。
        // 现在两种形态共用一条动画路径：段宽由 onSizeChanged 回报，滑块的 x = 前面各段宽度之和、
        // 宽 = 选中段宽，两个量各自跑 tabSlider 弹簧。
        !wrap -> {
            val selectedIndex = options.indexOfFirst { it.first == selectedValue }
            // key 用 options.size 而不是 options：调用点普遍是内联 listOf(...)，
            // 每次重组都是新实例，用它当 key 会让下面的 Animatable 每帧重建 = 永远不动。
            val segmentWidths = remember(options.size) {
                mutableStateListOf<Float>().apply { repeat(options.size) { add(0f) } }
            }
            val thumbX = remember(options.size) { Animatable(0f) }
            val thumbWidth = remember(options.size) { Animatable(0f) }
            val targetX = segmentWidths.take(selectedIndex.coerceAtLeast(0)).sum()
            val targetWidth = segmentWidths.getOrElse(selectedIndex) { 0f }
            LaunchedEffect(targetX, targetWidth) {
                if (targetWidth <= 0f) return@LaunchedEffect
                if (thumbWidth.value <= 0f) {
                    // 首次拿到测量结果：直接就位，别让滑块从 0 宽"长"出来。
                    thumbX.snapTo(targetX)
                    thumbWidth.snapTo(targetWidth)
                } else {
                    coroutineScope {
                        launch { thumbX.animateTo(targetX, ThemeMotion.tabSlider()) }
                        launch { thumbWidth.animateTo(targetWidth, ThemeMotion.tabSlider()) }
                    }
                }
            }

            val fill = palette.segmentSelectedFill
            val stroke = palette.segmentSelectedBorder
            Row(
                // 滑块走 drawBehind：只在绘制阶段读 Animatable，逐帧不重组也不重新布局
                //（若改成 offset/width 的 Dp 状态，每帧都要重新测量整行）。
                // 没有任何选项命中（脏值/空串）时 targetWidth 为 0 ⇒ 不画，而不是错误高亮第一段。
                modifier = trackModifier.drawBehind {
                    val w = thumbWidth.value
                    if (w <= 0f) return@drawBehind
                    val radius = CornerRadius(size.height / 2f)
                    val topLeft = Offset(thumbX.value, 0f)
                    val thumbSize = Size(w, size.height)
                    drawRoundRect(color = fill, topLeft = topLeft, size = thumbSize, cornerRadius = radius)
                    drawRoundRect(
                        color = stroke,
                        topLeft = topLeft,
                        size = thumbSize,
                        cornerRadius = radius,
                        style = Stroke(width = UfiCardDefaults.hairlineBorderWidth.toPx())
                    )
                }
            ) {
                options.forEachIndexed { index, (value, label) ->
                    UfiSegment(
                        label = label,
                        selected = value == selectedValue,
                        enabled = enabled,
                        // 底由滑块统一画，段自己不画（否则切换时两处底同时出现 = 双层底）
                        paintSelectedBackground = false,
                        modifier = (if (wrapContent) Modifier else Modifier.weight(1f))
                            .onSizeChanged { segmentWidths[index] = it.width.toFloat() },
                        onClick = { onSelect(value) }
                    )
                }
            }
        }
    }
}

/**
 * 分段控件的单个段。
 *
 * @param paintSelectedBackground 选中时是否自己画选中底。等宽单行形态由外层滑块统一画，这里传 false。
 */
@Composable
private fun UfiSegment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    paintSelectedBackground: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val background by animateColorAsState(
        targetValue = if (selected && paintSelectedBackground) {
            palette.segmentSelectedFill
        } else {
            Color.Transparent
        },
        animationSpec = ThemeMotion.colorSwap(),
        label = "ufiSegmentBg"
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            // 整组置灰：不套 Modifier.alpha（那会连轨道底一起变淡），只压文字。
            !enabled -> palette.textSecondary.copy(alpha = 0.38f)
            selected -> palette.accent
            else -> palette.textSecondary
        },
        animationSpec = ThemeMotion.colorSwap(),
        label = "ufiSegmentLabel"
    )
    Box(
        modifier = modifier
            .height(Spacing.SegmentHeight)
            // 无阴影：轨道无实底，浮起感没有依托（见 UfiSingleChipSelector 的 KDoc）。
            .clip(UfiCardDefaults.pillShape)
            .background(background, UfiCardDefaults.pillShape)
            // 与 CategoryChip / UfiOptionCell 同一档：ufiPressScale 事件驱动，短按也能看到缩放。
            .ufiPressScale(
                interactionSource = interactionSource,
                pressedScale = ThemeMotion.PressScale.Chip,
                spec = tween(ThemeMotion.Duration.Micro)
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = Spacing.Medium),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            // 与原 FilterChip 的 labelSmall 同字号（UfiTextStyles.caption == Typography.labelSmall）。
            // 2026-09-05：选中态**不再换字重**。原来是 caption → captionEmphasis，字重一变文本宽度就变，
            // 在内容宽（wrapContent）形态下整行立刻重排 —— 那正是用户说的"切换时抽搐"。
            // 两态统一用 captionEmphasis（宽度恒定），只靠颜色区分选中；
            // 滑块本身已经把"选中的是哪一段"说得很清楚，字重差属于多余信号。
            style = UfiTextStyles.captionEmphasis,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun UfiMultiChipSelector(
    options: List<Pair<String, String>>,
    selectedValues: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 2026-08-31 新增：选中态强调色，默认 `palette.accent`。
     * 供「一组 chip 自带语义色」的场景使用（如 NetworkScreen 的 LTE 绿 / NR 紫频段组），
     * 避免各页面为了换个颜色又手绘一遍 FilterChip。
     */
    accentColor: Color? = null
) {
    val palette = LocalResolvedPalette.current
    val accent = accentColor ?: palette.accent
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value in selectedValues
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                shape = UfiCardDefaults.chipShape,
                border = BorderStroke(1.dp, if (isSelected) accent else palette.chipUnselectedBorder),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = if (accentColor != null) accent.copy(alpha = 0.14f) else palette.chipSelectedBg,
                    selectedLabelColor = accent,
                    selectedLeadingIconColor = accent
                )
            )
        }
    }
}
