package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.ui.theme.UfiTextStyles
import kotlin.math.max

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 单根柱的数据。[totalBytes] 由两段相加得出、不接受外部传入，避免"合计 ≠ 两段之和"。 */
data class UfiBarChartBar(
    val label: String,
    val rxBytes: Long,
    val txBytes: Long
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

/**
 * 堆叠柱状图（下行 + 上行），Canvas 自绘，无第三方依赖。
 *
 * 为什么新开文件而不是加进 `UfiChart.kt`：那个文件头上有 `[F24] STABLE-UI-API` 签名冻结
 * 声明，只允许追加带默认值的形参，塞不进一个全新的图表类型。
 *
 * ## 三条几何约定（都为了「所有刻度必须看得见」）
 *
 * 1. **柱宽自适应**：先按 gap 取上限算一版柱宽，用它反推真实 gap（柱窄时 gap 同步缩），
 *    再用真实 gap 定稿。柱数最大 31（月视图），窄到 [UfiBarChartMinBarWidth] 为止。
 *    **不做横向滚动** —— 一屏看不全的柱状图等于没有"这一段的全貌"这个信息。
 * 2. **刻度线全部画出，标签抽稀**：31 个日期标签必然挤成一团，但刻度线不能省，
 *    省了就数不出"这是第几根"。抽稀规则由调用方通过 [showLabelAt] 决定 ——
 *    组件不认识"日/周/月/年"这些业务概念。
 * 3. **Y 轴单位提到轴头、刻度只写数值**（见 [ufiBarChartAxis]）：读图的人拿网格线当尺子用，
 *    257.5 MB 这种刻度没法心算，逐条刻度都拖着单位又会出现"1000 MB / 1 GB"混排。
 *    单位按**峰值**定（1.03 GB 的图就是 GB，不会是 2000 MB），四等分后每格在该单位下
 *    都是 0.5 / 1 / 100 这类整齐数。X 轴同理：单位画在轴右端，刻度只写数字。
 *
 * ## 依赖方向
 * `app:ui` **不依赖** `app:data`，所以字节格式化必须由调用方以 [valueText] 注入，
 * 组件内不能调 `FormatUtils`。
 *
 * @param bars 完整的桶序列（调用方保证不缺桶，组件不补桶）
 * @param valueText 字节数 → 展示文案，用于浮层里的三行数值
 * @param selectedIndex 当前选中的柱（`null` = 不显示浮层）。**状态由调用方持有**：
 *   页面需要在"点击图表以外的任何地方"时收起浮层，那个手势不在本组件的边界内。
 * @param onSelectedIndexChange 点击/按住拖动命中的柱；点同一根或点空白回传 `null`
 * @param onPageChange 图内横滑翻页（`true` = 更晚的一段）。**只在没有浮层时生效** ——
 *   有浮层时同一个手势用于在柱之间擦扫。传 `null` 则图内横滑不做任何事。
 *   空态（没有任何记录）也挂这个手势：翻不动的空段是死路。
 * @param pageThreshold 触发翻页的横向位移阈值。太小会把"想擦扫却没浮层"的小动作误判成翻页
 * @param axisValueText Y 轴刻度的**裸数值**文案：`(字节数, 该刻度的显示单位)`。
 *   第二个参数是 [ufiBarChartAxis] 定下的单位（1 / 1024 / 1024² …），
 *   调用方按该单位渲染、**不要带单位名** —— 单位由 [axisUnitText] 写在轴头一次。
 *   默认退回 [valueText]（带单位、带小数），仅为兼容不传轴头的调用点。
 * @param axisUnitText Y 轴轴头的单位名（如 `"GB"`），画在最高刻度之上。
 *   传 `null` 则不画轴头，此时 [axisValueText] 应当自带单位。
 * @param xAxisUnitText X 轴右端的单位（如 `"时"` / `"日"`）。有它时刻度文案可以只写数字。
 *   与最后一个刻度标签冲突时**单位优先**、那个标签不画。
 * @param showLabelAt 第 index 个（共 count 个）刻度是否画标签文字
 * @param bucketTitle 浮层标题，默认取该柱的 [UfiBarChartBar.label]
 */
@Composable
fun UfiBarChart(
    bars: List<UfiBarChartBar>,
    valueText: (Long) -> String,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    onPageChange: ((forward: Boolean) -> Unit)? = null,
    pageThreshold: Dp = UfiBarChartPageThreshold,
    // 下行绿 / 上行黄：原来是 accent + accentSecondary，两者同为品牌绿系、只差一点明度，
    // 堆叠在一根柱子上根本分不出分界线（2026-09-15 用户反馈）。改用**不同色相**的两个
    // 既有语义色，堆叠边界一眼可辨；两者在明暗两态都有足够对比（warning 是黄橙）。
    rxColor: Color = LocalResolvedPalette.current.accent,
    txColor: Color = LocalResolvedPalette.current.warning,
    rxLabel: String = "下行",
    txLabel: String = "上行",
    totalLabel: String = "合计",
    emptyText: String = "这一段还没有任何流量记录",
    chartHeight: Dp = UfiBarChartDefaultHeight,
    axisValueText: (bytes: Long, unitBytes: Long) -> String = { b, _ -> valueText(b) },
    axisUnitText: ((unitBytes: Long) -> String)? = null,
    xAxisUnitText: String? = null,
    showLabelAt: (index: Int, count: Int) -> Boolean = { _, _ -> true },
    bucketTitle: (index: Int) -> String = { bars.getOrNull(it)?.label.orEmpty() }
) {
    val palette = LocalResolvedPalette.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val peakBytes = remember(bars) { bars.maxOfOrNull { it.totalBytes } ?: 0L }

    val axis = remember(peakBytes) { ufiBarChartAxis(peakBytes) }
    val axisLabels = remember(axis, axisValueText) {
        // 刻度只写数值（单位在轴头）。0 基线固定写 "0"，不走格式化。
        (0..UFI_BAR_CHART_GRID_DIVISIONS).map { i ->
            if (i == 0) "0" else axisValueText(
                axis.peakBytes / UFI_BAR_CHART_GRID_DIVISIONS * i,
                axis.unitBytes
            )
        }
    }
    val axisUnitLabel = axisUnitText?.invoke(axis.unitBytes)

    val labelStyle = UfiTextStyles.note.copy(color = palette.textSecondary)
    val xLabelHeight = remember(labelStyle) {
        with(density) { measurer.measure("0", labelStyle).size.height.toDp() }
    }

    /**
     * Y 轴宽度：**在组合期算**，不再由绘制期回写 state（2026-09-15 修）。
     *
     * 上一版是 `Canvas` 的 draw lambda 里量完文字后写进 `mutableFloatStateOf` 给手势用。
     * 后果是每次换数据（切区间/翻页/刷新）都会：第 1 帧用 0 宽度画一遍（柱子更宽、整体左移）
     * → 写 state → 重组 → 第 2 帧才用真宽度画对。这**就是用户看到的"刷新时闪一下 + 位移"**。
     * 文字测量本来不需要 draw 上下文，挪到组合期后几何从第一帧就是定稿值，
     * 顺带修掉了"首帧点击命中会偏一根"的老问题。
     */
    val axisWidthPx = remember(axisLabels, labelStyle, density) {
        val widest = axisLabels.maxOfOrNull { measurer.measure(it, labelStyle).size.width } ?: 0
        widest.toFloat() + with(density) { Spacing.Small.toPx() }
    }

    /**
     * 图表区总高度。空态与有数据时**必须完全一致** —— 否则数据到位的那一刻整块高度变化，
     * 外层 Column 重排，用户看到的就是"图往上跳一下"（这也是本轮"还是闪"的另一半原因）。
     *
     * 有 Y 轴轴头（单位）时顶部多留一行字的高度：轴头画在最高刻度**之上**，
     * 挤在原来的 14dp 留白里会和峰值刻度粘住。
     */
    val topInset = if (axisUnitLabel != null) UfiBarChartTopInset + xLabelHeight + 2.dp
    else UfiBarChartTopInset
    val plotBoxHeight = chartHeight + xLabelHeight + Spacing.Small + topInset

    val selected = selectedIndex?.takeIf { it in bars.indices }

    /**
     * 手势里要用的"当下值"（2026-09-16）。
     *
     * 这三个量都是**每次交互都会变**的（选中态每点一次就变，回调是调用点的内联 lambda），
     * 一旦放进 `pointerInput` 的 key，每次点击都会把手势节点连同协程重建一次 ——
     * 重建时机紧跟在这次点击的状态写入之后，新识别器会错过紧接着的那段触摸序列。
     * 症状：第二次点柱子之后"点别处收不起浮层"，关掉弹窗重开也只好一次。
     * 用 `rememberUpdatedState` 在手势体内读，既避开旧值捕获、又不必重建节点。
     */
    val selectedNow = rememberUpdatedState(selected)
    val onSelectedChangeNow = rememberUpdatedState(onSelectedIndexChange)
    val onPageChangeNow = rememberUpdatedState(onPageChange)

    // 选中柱的中心 x（px）：柱心依赖测量宽度，仍由绘制期回写 —— 它只被浮层的 offset 读，
    // 不参与几何计算，写晚一帧不会造成位移（浮层此刻还没显示）。
    val selectedCenterPx = remember(bars) { mutableFloatStateOf(0f) }

    /**
     * 柱子上升动画的进度（0 → 1），绘制时乘在柱高上。
     *
     * 用 `Animatable` 而不是 `animateFloatAsState`：需要"换一批数据就从 0 重新长起来"，
     * 而 `animateFloatAsState` 只会从当前值补间到新目标（同为 1f 时根本不动）。
     * `remember(bars)` 的 key 是**内容相等**的列表：定时刷新拿回一样的数据不会重播动画，
     * 只有真的换了区间/翻了页才重新长（这也是"切区间的过渡动画"——所以不再需要转圈）。
     *
     * 进度值只在 `Canvas` 的 draw lambda 里读 → 绘制期读，动画期间零重组。
     */
    val rise = remember(bars) { Animatable(0f) }
    LaunchedEffect(bars) {
        rise.animateTo(1f, tween(UfiMotion.Duration.Sweeping, easing = UfiMotion.Easing.Standard))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 图例放在图的**右上角**：图内左上角是 Y 轴峰值刻度的位置，图下方留给 X 轴标签，
        // 只有这里既不挡数据又在视线进入图表的第一落点上。
        // 空态也保留图例：它属于"这张图的说明"，跟着数据闪进闪出反而是抖动源。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            UfiBarChartLegendItem(rxColor, rxLabel)
            Spacer(Modifier.width(Spacing.Large))
            UfiBarChartLegendItem(txColor, txLabel)
        }
        Spacer(Modifier.height(Spacing.Small))

        // 空态：全 0 时画一排 0 高柱子只会让人以为图坏了，不如直说这一段没有记录。
        // 高度与有数据时一致（plotBoxHeight），所以数据到位时只换内容、不动布局。
        // 翻页手势必须也挂在这里：空段是最需要"划走看下一段"的地方，
        // 之前这一支直接 return，横滑没有任何反应（2026-09-15 修）。
        if (bars.isEmpty() || peakBytes <= 0L) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(plotBoxHeight)
                    .ufiBarChartPageDrag(onPageChange, pageThreshold),
                contentAlignment = Alignment.Center
            ) {
                Text(emptyText, style = UfiTextStyles.note, color = palette.textSecondary)
            }
            return@Column
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(plotBoxHeight)
        ) {
            val maxGapPx = with(density) { UfiBarChartMaxGap.toPx() }
            val minBarPx = with(density) { UfiBarChartMinBarWidth.toPx() }
            val smallPx = with(density) { Spacing.Small.toPx() }
            val tickPx = with(density) { UfiBarChartTickLength.toPx() }
            val topInsetPx = with(density) { topInset.toPx() }
            val xLabelBandPx = with(density) { (xLabelHeight + Spacing.Small).toPx() }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    // key 里**不放 selected / 回调**（2026-09-16 修）：
                    // key 一变，整个 pointerInput 节点连同手势协程一起重建，而选中态是"每点一次
                    // 就变"的量 —— 重建正好发生在这次点击的状态写入之后，新识别器错过接下来的
                    // 那一段触摸序列。表现就是用户报的"第二次点柱子之后，点别处收不起浮层，
                    // 必须关弹窗重开、而重开后第二次又坏"。
                    // 选中态改用 rememberUpdatedState 在手势里**读当下的值**：既没有旧值捕获
                    // （那是 09-15 修过的另一个 bug），也不需要重建节点。
                    // key 只留真正改变几何的量：柱子列表、量程、两条带宽。
                    .pointerInput(bars, axis, topInsetPx, xLabelBandPx) {
                        detectTapGestures { tap ->
                            val geom = ufiBarChartGeometry(
                                totalWidthPx = size.width.toFloat(),
                                yAxisWidthPx = axisWidthPx,
                                barCount = bars.size,
                                maxGapPx = maxGapPx,
                                minBarWidthPx = minBarPx
                            )
                            val column = geom.indexAt(tap.x)
                            // 命中**要看 y**：浮层的语义是"看这根柱子"，在柱子上方一大片空白里
                            // 也能点出来就不对了（数据为 0 的桶尤其明显 —— 一根都没画出来的柱子
                            // 却能在半屏高的地方点出浮层）。2026-09-15 加上纵向判定：
                            //  · 柱高超过量程一半 → 命中区就是柱子本身的高度（点柱子）
                            //  · 否则 → 统一给 30% 的下限（3px 宽的矮柱按柱高判等于点不中）
                            val hit = column?.takeIf { i ->
                                val plotBottom = size.height - xLabelBandPx
                                val gridHeight = (plotBottom - topInsetPx).coerceAtLeast(1f)
                                val ratio = ratioOf(bars[i].totalBytes, axis)
                                val zone = if (ratio > UFI_BAR_CHART_HIT_TALL_RATIO) ratio
                                else UFI_BAR_CHART_HIT_MIN_RATIO
                                tap.y <= plotBottom && tap.y >= plotBottom - gridHeight * zone
                            }
                            // 点同一根 = 收起；点空白 = 收起。浮层只有一个，不需要多选。
                            val next = if (hit == null || hit == selectedNow.value) null else hit
                            next?.let { selectedCenterPx.floatValue = geom.centerOf(it) }
                            onSelectedChangeNow.value(next)
                        }
                    }
                    // 图内横向拖动有**两种**含义，按拖动开始那一刻有没有浮层区分：
                    //  · 已有浮层 → 在柱之间擦扫（scrub），手指压到哪根看哪根
                    //  · 没有浮层 → 翻页，交给 [onPageChange]（超过 [pageThreshold] 才算）
                    // 模式在 onDragStart 时定死、整个手势不再改变，否则中途擦出浮层会突然变成翻页。
                    // "有没有浮层"同样是**手势里现读**、不进 key（理由同上一段）。
                    // 与 tap 共存：detectTapGestures 只在无位移抬手时触发。
                    .pointerInput(bars, axisWidthPx) {
                        val thresholdPx = pageThreshold.toPx()
                        var scrubbing = false
                        var scrubX = 0f
                        var acc = 0f
                        fun geom() = ufiBarChartGeometry(
                            totalWidthPx = size.width.toFloat(),
                            yAxisWidthPx = axisWidthPx,
                            barCount = bars.size,
                            maxGapPx = maxGapPx,
                            minBarWidthPx = minBarPx
                        )
                        fun selectAt(x: Float) {
                            val g = geom()
                            val hit = g.indexAt(x) ?: return
                            selectedCenterPx.floatValue = g.centerOf(hit)
                            onSelectedChangeNow.value(hit)
                        }
                        detectHorizontalDragGestures(
                            onDragStart = { pos ->
                                scrubbing = selectedNow.value != null
                                scrubX = pos.x
                                acc = 0f
                                if (scrubbing) selectAt(scrubX)
                            },
                            onDragEnd = {
                                if (!scrubbing) {
                                    // 右滑看更早（内容跟着手指往右走），左滑看更晚，与列表翻页直觉一致
                                    if (acc > thresholdPx) onPageChangeNow.value?.invoke(false)
                                    else if (acc < -thresholdPx) onPageChangeNow.value?.invoke(true)
                                }
                                acc = 0f
                            }
                        ) { _, dragAmount ->
                            if (scrubbing) {
                                scrubX = (scrubX + dragAmount).coerceIn(0f, size.width.toFloat())
                                selectAt(scrubX)
                            } else {
                                acc += dragAmount
                            }
                        }
                    }
            ) {
                val plotBottom = size.height - xLabelBandPx
                val gridHeight = (plotBottom - topInsetPx).coerceAtLeast(1f)


                val yLayouts = axisLabels.map { measurer.measure(it, labelStyle) }
                // 轴宽在组合期已算好（axisWidthPx），这里只复用。绝不能在 draw 里重算再回写
                // state —— 那正是刷新时「第一帧用零宽度画一遍、第二帧才画对」的位移来源。
                val axisWidth = axisWidthPx

                val geom = ufiBarChartGeometry(
                    totalWidthPx = size.width,
                    yAxisWidthPx = axisWidth,
                    barCount = bars.size,
                    maxGapPx = maxGapPx,
                    minBarWidthPx = minBarPx
                )

                // 网格线 + Y 轴标签（索引 0 = 0 基线，在最下）
                yLayouts.forEachIndexed { i, layout ->
                    val y = plotBottom - gridHeight * i / UFI_BAR_CHART_GRID_DIVISIONS
                    drawLine(
                        color = palette.divider,
                        start = Offset(axisWidth, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            x = axisWidth - smallPx - layout.size.width,
                            y = y - layout.size.height / 2f
                        )
                    )
                }

                // Y 轴轴头：单位只在这里写一次（"GB"），刻度全是裸数字。
                // 位置与刻度同一右边界，落在最高刻度之上的留白里（topInset 已为它加过高度）。
                axisUnitLabel?.let { unit ->
                    val layout = measurer.measure(unit, labelStyle)
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            x = (axisWidth - smallPx - layout.size.width).coerceAtLeast(0f),
                            y = 0f
                        )
                    )
                }

                // X 轴右端单位（"时" / "日"）：有它之后刻度不用逐个带单位。
                // 先算它的左边界，下面的刻度标签越过这条线就不画 —— 单位优先，
                // 否则末位标签会和它糊在一起。
                val xUnitLayout = xAxisUnitText?.takeIf { it.isNotBlank() }
                    ?.let { measurer.measure(it, labelStyle) }
                val xLabelLimit = xUnitLayout?.let { size.width - it.size.width - smallPx }
                    ?: size.width
                xUnitLayout?.let { layout ->
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(size.width - layout.size.width, plotBottom + smallPx)
                    )
                }

                bars.forEachIndexed { i, bar ->
                    val left = geom.leftOf(i)
                    val center = geom.centerOf(i)
                    // rise 是绘制期读取的动画进度：柱子从 0 长到实高，刻度线与标签不参与
                    val grow = rise.value
                    val rxH = gridHeight * ratioOf(bar.rxBytes, axis) * grow
                    val txH = gridHeight * ratioOf(bar.txBytes, axis) * grow
                    val dim = selected != null && selected != i

                    // 下行在下、上行在上。为 0 的那一段不画，避免 0.x px 的脏边。
                    if (rxH > 0f) {
                        drawRect(
                            color = if (dim) rxColor.copy(alpha = UFI_BAR_CHART_DIM_ALPHA) else rxColor,
                            topLeft = Offset(left, plotBottom - rxH),
                            size = Size(geom.barWidthPx, rxH)
                        )
                    }
                    if (txH > 0f) {
                        drawRect(
                            color = if (dim) txColor.copy(alpha = UFI_BAR_CHART_DIM_ALPHA) else txColor,
                            topLeft = Offset(left, plotBottom - rxH - txH),
                            size = Size(geom.barWidthPx, txH)
                        )
                    }

                    drawLine(
                        color = palette.divider,
                        start = Offset(center, plotBottom),
                        end = Offset(center, plotBottom + tickPx),
                        strokeWidth = 1f
                    )
                    if (showLabelAt(i, bars.size)) {
                        val layout = measurer.measure(bar.label, labelStyle)
                        val maxX = (size.width - layout.size.width).coerceAtLeast(axisWidth)
                        val x = (center - layout.size.width / 2f).coerceIn(axisWidth, maxX)
                        if (x + layout.size.width <= xLabelLimit) {
                            drawText(
                                textLayoutResult = layout,
                                topLeft = Offset(x, plotBottom + smallPx)
                            )
                        }
                    }
                }
            }

            // 收起时不能直接把浮层从子树里摘掉，否则淡出动画没有机会播（子树已不存在）。
            // 这里保留"最后一次选中"的索引，可见性交给 visible 参数，与 UfiChart 的浮层同一套做法。
            var lastSelected by remember(bars) { mutableStateOf<Int?>(null) }
            LaunchedEffect(selected) { selected?.let { lastSelected = it } }
            (selected ?: lastSelected)?.let { idx ->
                bars.getOrNull(idx)?.let { bar ->
                    UfiBarChartTooltip(
                        title = bucketTitle(idx),
                        rows = listOf(
                            rxLabel to valueText(bar.rxBytes),
                            txLabel to valueText(bar.txBytes),
                            totalLabel to valueText(bar.totalBytes)
                        ),
                        centerPx = selectedCenterPx.floatValue,
                        visible = selected != null,
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                }
            }
        }
    }
}

@Composable
private fun UfiBarChartLegendItem(color: Color, label: String) {
    val palette = LocalResolvedPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(Spacing.Small))
        Text(label, style = UfiTextStyles.note, color = palette.textSecondary)
    }
}

/**
 * 点击柱子后的浮层。
 *
 * 用同一个 Box 里的 offset 而不是 `Popup`：`Popup` 是独立窗口，父级滚动时会脱开、
 * 还要自己处理 dismiss，而这里只需要贴在图内。靠边时用 `coerceIn` 内收，不越界。
 *
 * 与图面的隔离靠三件事，缺一件就会和柱子/卡面糊在一起（2026-09-15 修）：
 *  · `shadow(clip = false)` 给出层次 —— 浮层与卡片都是 cardBg，没有投影就分不出前后
 *  · 1dp `toastBorder` 发丝线兜住边界 —— 它是 alpha 叠加型令牌（深色白 10% / 浅色黑 8%），
 *    语义就是"小型浮层的描边"；不能用 divider，那是各配色写死的具体深灰，深色下又是一道暗环
 *  · alpha + 轻微缩放的进出动画 —— 直接闪现会让人以为是渲染故障
 * 动画用手动 alpha/scale 而不是 AnimatedVisibility：后者增删子树会引起布局跳变，
 * 且收起瞬间子树就没了、淡出无从播放（调用点因此保留了 lastSelected）。
 */
@Composable
private fun UfiBarChartTooltip(
    title: String,
    rows: List<Pair<String, String>>,
    centerPx: Float,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val density = LocalDensity.current
    val tooltipWidthPx = remember { mutableIntStateOf(0) }
    val containerWidthPx = remember { mutableIntStateOf(0) }
    val shape = RoundedCornerShape(8.dp)
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(UfiMotion.Duration.Swift),
        label = "barTooltipAlpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.94f,
        animationSpec = UfiMotion.tooltipPop(),
        label = "barTooltipScale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { containerWidthPx.intValue = it.width }
    ) {
        val w = tooltipWidthPx.intValue
        val cw = containerWidthPx.intValue
        val leftPx = if (w == 0 || cw == 0) {
            centerPx
        } else {
            (centerPx - w / 2f).coerceIn(0f, (cw - w).coerceAtLeast(0).toFloat())
        }
        Column(
            modifier = Modifier
                .offset(x = with(density) { leftPx.roundToInt().toDp() })
                // 关键：**不能**给里面的行加 fillMaxWidth。之前那一版加了，Column 就被撑到
                // 整个图宽，浮层看起来像一条横幅。现在宽度由内容决定，只加一个上限兜住异常长文案。
                .widthIn(max = UfiBarChartTooltipMaxWidth)
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                    // 以自身中心为缩放原点，弹出感来自"从柱子上方长出来"而不是从左上角拉开
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                // shadow 必须在 background 之前、且 clip = false，否则投影会被自己的圆角裁掉
                .shadow(6.dp, shape, clip = false)
                .background(palette.cardBg, shape)
                .border(1.dp, palette.toastBorder, shape)
                .clip(shape)
                .padding(horizontal = Spacing.Medium, vertical = Spacing.Small)
                .onSizeChanged { tooltipWidthPx.intValue = it.width }
        ) {
            Text(title, style = UfiTextStyles.note, color = palette.textPrimary)
            Spacer(Modifier.height(2.dp))
            rows.forEach { (name, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = UfiTextStyles.captionTiny, color = palette.textSecondary)
                    Spacer(Modifier.width(Spacing.Medium))
                    Text(value, style = UfiTextStyles.captionTiny, color = palette.textPrimary)
                }
            }
        }

    }
}

// ───────────────────────── 几何与刻度（纯计算，可单测） ─────────────────────────

/**
 * 纯翻页横滑（空态用）。
 *
 * 有数据那一支的手势是"擦扫 / 翻页"双模式，判定要看有没有浮层，所以没法共用；
 * 空态没有柱子可擦，只剩翻页这一种含义。[onPageChange] 为 null 时不挂手势 ——
 * 挂一个什么都不做的识别器会把横滑从父级（弹窗滚动）手里抢走。
 */
private fun Modifier.ufiBarChartPageDrag(
    onPageChange: ((forward: Boolean) -> Unit)?,
    threshold: Dp
): Modifier {
    if (onPageChange == null) return this
    return pointerInput(onPageChange, threshold) {
        val thresholdPx = threshold.toPx()
        var acc = 0f
        detectHorizontalDragGestures(
            onDragStart = { acc = 0f },
            onDragEnd = {
                // 与有数据时同向：右滑看更早，左滑看更晚
                if (acc > thresholdPx) onPageChange(false)
                else if (acc < -thresholdPx) onPageChange(true)
                acc = 0f
            }
        ) { _, dragAmount -> acc += dragAmount }
    }
}


/** 柱宽下限：再窄就只剩抗锯齿的灰边，既点不到也看不清。 */
private val UfiBarChartMinBarWidth = 2.dp

/** 图内横滑翻页阈值。太小会把"想擦扫但当时没浮层"的小动作误判成翻页。 */
private val UfiBarChartPageThreshold = 56.dp

/** 柱间距上限。柱变窄时 gap 按 [UFI_BAR_CHART_GAP_RATIO] 同步缩小。 */
private val UfiBarChartMaxGap = Spacing.Small

/** X 轴刻度线长度。 */
private val UfiBarChartTickLength = 2.dp

/**
 * 绘图区顶部留白。
 *
 * 两个用处：最高的那根柱不会顶到卡片边缘（否则整张图看着很挤），
 * 以及点击最高柱时浮层有地方落。
 */
private val UfiBarChartTopInset = 14.dp

/** 浮层宽度上限，防止异常长的数值把它拉成横幅。 */
private val UfiBarChartTooltipMaxWidth = 240.dp


private val UfiBarChartDefaultHeight = 148.dp

private const val UFI_BAR_CHART_GAP_RATIO = 0.3f

/** 4 段 = 5 条线（含 0 基线），落在"4~5 条网格线"这个可读区间。 */
private const val UFI_BAR_CHART_GRID_DIVISIONS = 4

/** 有选中柱时其余柱的淡出程度：让被点中的那根跳出来，但不至于看不见其他柱。 */
private const val UFI_BAR_CHART_DIM_ALPHA = 0.35f

/** 全 0 时的兜底峰值（10 B）：不能用 0，否则柱高归一时会除出 NaN。 */
private const val UFI_BAR_CHART_EMPTY_PEAK_BYTES = 10L

/**
 * 一格刻度允许的"好数"（单位是 [UfiBarChartAxisSpec.unitBytes]）。
 *
 * 只收 1/2/5×10^n 与 2.5×10^n 两族，外加 0.25 / 0.5 两个半档和收尾的 256。
 * · 半档是必须的：单位按**峰值**定（1.03 GB 的图单位就是 GB），一格若只能取整数，
 *   1.03 GB 的轴会被抬到 4 GB，柱子只剩四分之一高。有 0.5 之后是 2 GB，
 *   刻度 0.5/1/1.5/2 —— 半档在轴上仍然是"能当尺子用"的整齐数。
 * · 256 收尾：峰值在 (1000,1024) 个单位之间时（如 1010 MB 而单位仍是 MB）才用到，
 *   4×256 = 1024 = 正好一个上级单位。
 */
private val UFI_BAR_CHART_NICE_STEPS = listOf(
    0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 125.0, 200.0, 250.0, 256.0
)

/** 柱高超过量程这个比例时，点击命中区就等于柱子本身的高度。 */
private const val UFI_BAR_CHART_HIT_TALL_RATIO = 0.5f

/**
 * 矮柱的点击命中区下限（占量程的比例）。
 *
 * 按柱高判会让 0 值 / 极矮的桶点不中；按整列判又会让"柱子上方一大片空白"也能点出浮层
 * （2026-09-15 用户反馈）。折中：矮柱统一给这个高度的命中带，贴着基线。
 */
private const val UFI_BAR_CHART_HIT_MIN_RATIO = 0.30f




private fun ratioOf(bytes: Long, axis: UfiBarChartAxisSpec): Float =
    if (axis.peakBytes <= 0L) 0f else (bytes.toDouble() / axis.peakBytes).toFloat().coerceIn(0f, 1f)


private class UfiBarChartGeometry(
    private val plotLeftPx: Float,
    val barWidthPx: Float,
    private val stridePx: Float,
    private val barCount: Int
) {
    fun leftOf(index: Int): Float = plotLeftPx + stridePx * index
    fun centerOf(index: Int): Float = leftOf(index) + barWidthPx / 2f

    /** 命中按整条 stride 带判，而不是柱体本身：柱只有 3px 宽时按柱体几乎点不中。 */
    fun indexAt(x: Float): Int? {
        if (x < plotLeftPx) return null
        if (barCount == 1) return 0
        if (stridePx <= 0f) return null
        val idx = ((x - plotLeftPx) / stridePx).toInt()
        return if (idx in 0 until barCount) idx else null
    }
}

private fun ufiBarChartGeometry(
    totalWidthPx: Float,
    yAxisWidthPx: Float,
    barCount: Int,
    maxGapPx: Float,
    minBarWidthPx: Float
): UfiBarChartGeometry {
    if (barCount <= 0) return UfiBarChartGeometry(yAxisWidthPx, 0f, 0f, 0)
    val plotWidth = max(totalWidthPx - yAxisWidthPx, 1f)
    // 两步收敛，不需要迭代：先按 gap 上限算一版柱宽，用它反推真实 gap，再用真实 gap 定稿。
    val roughBar = (plotWidth - maxGapPx * (barCount - 1)) / barCount
    val gap = min(maxGapPx, max(roughBar, 0f) * UFI_BAR_CHART_GAP_RATIO)
    val barWidth = max((plotWidth - gap * (barCount - 1)) / barCount, minBarWidthPx)
    val stride = if (barCount > 1) (plotWidth - barWidth) / (barCount - 1) else 0f
    return UfiBarChartGeometry(yAxisWidthPx, barWidth, stride, barCount)
}

internal data class UfiBarChartAxisSpec(
    /** 峰值（字节）。刻度按它等分，柱高按它归一。 */
    val peakBytes: Long,
    /** 刻度的显示单位（1 = B、1024 = KB、1024² = MB …）。每一格在这个单位下都是整数。 */
    val unitBytes: Long
)

/**
 * Y 轴峰值（字节）+ 刻度单位。
 *
 * ## 单位按**峰值**定，不按一格定（2026-09-15 二次修订）
 * 上一版是拿"一格的生高度"去挑单位，于是 1.03 GB 的图会得到 500/1000/1500/2000 **MB** ——
 * 用户的原话是"2000MB 是什么东西"。现在先取"不超过峰值的最大 1024 档"当单位，
 * 所以 1.03 GB 的图单位一定是 GB。
 *
 * ## 一格取整规则
 * 峰值 / 4 向上取到 [UFI_BAR_CHART_NICE_STEPS] 里的下一个好数（含 0.25 / 0.5 两个半档，
 * 详见那里的注释），峰值 = 好数 × 4。于是四条刻度在**同一个单位**下都是整齐数：
 * 算例 1.03 GB → 一格 0.5 GB → 轴 0.5 / 1 / 1.5 / 2（轴头写 "GB"）；
 * 算例 300 MB → 一格 100 MB → 轴 100 / 200 / 300 / 400。
 *
 * 单位是 B 时不给半档：0.25 B 这种刻度没有意义，且 `peakBytes / 4` 会整除成 0。
 *
 * **刻意不在这里做单位名与文案**：单位阶梯与小数位在 `core:common` 的 `formatDataSize` /
 * `dataSizeUnitLabel` 里已经有唯一口径，组件再写一份就会出现"轴写 20GB、浮层写 20.0 GB"。
 * 所以刻度文案走 `axisValueText(值, unitBytes)`、轴头走 `axisUnitText(unitBytes)`，
 * 本函数只负责算数值和定单位。
 *
 * 全 0 时返回一个固定的小峰值而不是 0 —— 否则后面除以它会得到 NaN。
 */
internal fun ufiBarChartAxis(maxBytes: Long): UfiBarChartAxisSpec {
    if (maxBytes <= 0L) return UfiBarChartAxisSpec(UFI_BAR_CHART_EMPTY_PEAK_BYTES, 1L)
    var unit = 1L
    while (unit <= Long.MAX_VALUE / 1024 && maxBytes >= unit * 1024) unit *= 1024
    val rawStep = maxBytes.toDouble() / unit / UFI_BAR_CHART_GRID_DIVISIONS
    val candidates =
        if (unit == 1L) UFI_BAR_CHART_NICE_STEPS.filter { it >= 1.0 } else UFI_BAR_CHART_NICE_STEPS
    val nice = candidates.firstOrNull { it >= rawStep } ?: candidates.last()
    return UfiBarChartAxisSpec(
        (nice * unit * UFI_BAR_CHART_GRID_DIVISIONS).roundToLong(),
        unit
    )
}



