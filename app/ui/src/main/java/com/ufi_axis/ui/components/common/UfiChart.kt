// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import com.ufi_axis.ui.theme.ChartAlert
import com.ufi_axis.ui.theme.ChartWarn
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 多 series 图表的单个数据序列。
 * 放 UfiChart.kt 内部，:ui 模块不依赖 :data，纯原生类型/稳定类型（UfiDownsampledPoint）。
 *
 * @param points 降采样数据点（与主 series 同一稳定类型，t/min/max/avg；UI 只用 t/avg）
 * @param lineColor 主线 / 曲线下方渐变填充 / 图例色块颜色
 * @param label 图例与 tooltip 中的显示名（如 "内存"、"上传速率"）
 * @param valueFormatter 可选：本序列数值格式化（应含单位，如 "68.2%"、"42.3°C"）；
 *                       null 时回退为主 series 的通用格式化
 */
data class MonitorSeries(
    val points: List<UfiDownsampledPoint>,
    val lineColor: Color,
    val label: String,                  // 图例/tooltip 显示名
    val valueFormatter: ((Double) -> String)? = null   // 可选：单位格式化（CPU%/内存%/°C 等不同）
)

/**
 * 监控历史数据折线图：曲线画的是每个时间桶的 avg，曲线下方一层渐变填充。
 * Y 轴（数值）刻度在左，X 轴（时间）标签在下。
 *
 * 2026-09-03：移除「时间桶内 min/max 区间带」这一层半透明填充。
 * 它叠在渐变填充上下、覆盖大片绘图区，被当成「图表上多了一层异常背景」；
 * 而它表达的信息（桶内极值）在 92dp 高的卡片里既读不准也用不上，
 * 真要看极值应该去详情页而不是靠一层背景色猜。tooltip 里的「峰值/谷值」两个数同步删掉。
 * [UfiDownsampledPoint] 的 min/max 字段保留（跨模块共用、服务端仍在返），只是 UI 不再消费。
 *
 * P7a 多 series 扩展（向后兼容）：
 * - 追加 [extraSeries] 可在同一张图中绘制多条不同颜色序列；首条仍是主 series（旧行为不变）。
 * - 多 series 模式：Y 值域联合所有 series 的 avg 极值；tooltip 逐 series 显示（多行）；
 *   阈值虚线隐藏（不同 series 单位可能不可比，详情页仍为单 series 完整阈值），
 *   超限着色仅对主 series 生效，其它 series 保持纯色；右上角显示图例。
 * - 单 series（extraSeries 为空）时行为与旧版完全一致（含阈值虚线 / 超限着色）。
 */
@Composable
fun UfiMonitorChart(
    points: List<UfiDownsampledPoint>,
    label: String,
    unit: String,
    yUnit: String = "",
    yAxisLabelFormatter: ((Double) -> String)? = null,
    modifier: Modifier = Modifier,
    lineColor: Color = LocalResolvedPalette.current.accent,
    valueFormatter: ((Double) -> String)? = null,
    // ── P3a 图表扩展（全部带默认值，旧调用零影响） ──
    thresholdWarn: Double? = null,        // 黄色阈值线（水平虚线）；null 不画
    thresholdAlert: Double? = null,       // 红色阈值线；null 不画
    lowerIsWorse: Boolean = false,        // true=值越小越差（RSRP/SINR），判定方向取反
    comparePoints: List<UfiDownsampledPoint>? = null,  // 对比基线（灰色虚线）
    // ── P5c-3 设置接入：图表参数（原生类型，向后兼容；由 feature-monitor 从 settings 读出再传） ──
    fixedYRange: ClosedFloatingPointRange<Double>? = null, // 非 null 时 Y 轴固定此范围（settings.fixedYAxis 时传）
    fillAlpha: Float = 1f,                                 // 填充透明度 0..1（默认 1 = 旧行为）
    // ── P7a 多 series 扩展（追加参数，旧调用零影响；首条仍是主 series） ──
    extraSeries: List<MonitorSeries> = emptyList(),         // 多 series 追加；空 = 单 series（旧行为）
    // ── v17：主 series 在 tooltip/图例中的 clean 显示名；合并图传 primary.label，避免用合并标题重复显示 ──
    mainSeriesLabel: String? = null,
    // ── v22：图例是否由组件内部绘制；false 时由调用方（卡片标题行）自行渲染，便于与分区标题同一行靠右 ──
    showLegend: Boolean = true,
    // ── v23：左右对称外距（true 时图表绘图区右侧外距与左侧 yAxisWidth + yGap 一致，
    //         视觉上消除「左侧 Y 轴列挤压绘图区」造成的偏左感；默认 false 保持旧行为） ──
    symmetricHorizontal: Boolean = false,
    // ── v24：X 轴时间格式（"today"=仅显示时间 HH:mm，其他=显示日期+时间 MM/dd HH:mm）──
    timeRange: String = "week",
    // ── 2026-09-03：服务端实际生效的桶宽（毫秒，0 = 未知）。
    //    用途仍是断档判定：服务端跳过空桶，相邻两点的时间差是判断「中间是不是空洞」的唯一线索，
    //    否则采集中断的几小时会被画成一条平直的假数据。
    //    2026-09-04 修正：桶宽只当**下限参考**，不再是判定基准。桶宽是「可见区间 / MAX_POINTS」
    //    算出来的绘图分辨率，跟真实采集节奏无关（近 1h 窗口桶宽 15s，而采集间隔 30~60s），
    //    拿它当基准会把每一个正常采样间隔都判成断档。真正的基准是各序列自己的间距中位数，
    //    见下方 gapLimitOf；多指标叠加（extraSeries）时各序列桶宽还可能互不相同，
    //    这也是不能只靠这一个全局值的原因。
    //    0 时退回旧行为（完全不断线），兼容不回 bucket_ms 的旧 core。
    //    只能追加带默认值的形参（文件头 STABLE-UI-API 冻结声明），故用原生 Long 传入，
    //    app:ui 也就不需要依赖 :app:data。
    bucketMs: Long = 0L,
    // ── 2026-09-04：外部指定的 X 轴时间域（毫秒，null = 沿用旧行为：取本图点集的 min/max）。
    //    为什么需要：监控页是「一类指标一张图」，每张图各自取自己点集的 min/max 当轴边界，
    //    再把它拉满整个绘图宽度。于是**同屏各图的右边缘代表的时刻并不相同** ——
    //    `traffic_records` 每 tick 无条件写库、空桶服务端不回行，所以速率图往往先长出最新那个桶，
    //    而 signal（全 0 跳过）、battery（30s 一轮）还停在上一个桶，视觉上就是「终点对不齐」，
    //    而且两张图的 px/ms 比例不同，中段也跟着歪，横向对读时间毫无意义。
    //    传入统一域后：所有图共用同一条时间标尺，末点更早的序列老实地停在中间，
    //    刻度也自动同源（chartXPx / chartAxisTickTimes 都只吃 tMin/tSpan）。
    xDomainStartMs: Long? = null,
    xDomainEndMs: Long? = null
) {
    if (points.isEmpty()) return

    val palette = LocalResolvedPalette.current
    val fmt = valueFormatter ?: { v -> "%.1f".format(v) }
    // 2026-08-24：valueFormatter 一旦提供，其结果已含单位（如 pctAxisLabel 返回 "51%"），
    // 不应再拼接 unit（避免"51% %"重复）。未提供时走默认"数字+单位"。
    val formatWithUnit: (Double) -> String = { v ->
        if (valueFormatter != null) valueFormatter.invoke(v)
        else "%.1f".format(v) + unit
    }
    // P7a：首条仍是主 series（保持旧行为），extraSeries 追加其后；Y 值域 / 绘制 / tooltip 均基于 allSeries。
    val mainLabel = mainSeriesLabel ?: label
    // 2026-08-26：allSeries 原来每次重组都新建一个 List + 一个 MonitorSeries；它同时是几何缓存
    // key 的一部分，不 remember 会让缓存永远命中不了。
    val allSeries = remember(points, extraSeries, lineColor, mainLabel) {
        listOf(MonitorSeries(points, lineColor, mainLabel, valueFormatter)) + extraSeries
    }
    // 2026-08-24（FIX-19）：Y 值域必须基于曲线实际绘制所用的值（avg），
    // 而非 min/max/avg 全量——否则流量等突发指标的某桶 max（瞬时尖峰）会把 Y 轴顶抬到远高于 avg，
    // 导致曲线（画的是 avg）永远"够不到"Y 轴顶（如 Y 轴 19MB 但曲线峰值仅 2MB）。
    // 2026-09-03 复核（删除 min/max 区间带时）：这里已经只遍历 avg，不需要跟着改 ——
    // 区间带删掉后 min/max 在绘制中彻底没有消费者，量程不可能再被"看不见的数据"撑开，
    // 曲线也不会被压扁。若日后有人想把 min/max 重新纳入量程，请先想清楚 FIX-19 的坑。
    // 2026-08-26 性能：原来是 `allSeries.flatMap { s -> s.points.map { it.avg } }` —— 每次重组
    // 都要装箱 ≤1440 个 Double 并建两个中间 List，只为取 min/max。改单次遍历、纯 Float 比较。
    var dataMin = Float.MAX_VALUE
    var dataMax = -Float.MAX_VALUE
    allSeries.forEach { s ->
        s.points.forEach { p ->
            val v = p.avg.toFloat()
            if (v < dataMin) dataMin = v
            if (v > dataMax) dataMax = v
        }
    }
    // P3a：Y 值域扩展 —— 非 null 阈值纳入 min/max（画线可见）；
    // lowerIsWorse 时 alert 比 warn 更小（如 RSRP），自动扩展下边界。
    var maxVal = fixedYRange?.endInclusive?.toFloat() ?: dataMax
    var minVal = fixedYRange?.start?.toFloat() ?: dataMin    // P5c-3：fixedYRange 非 null 时 Y 轴直接固定 —— 不取数据 min/max，也不因阈值扩域；
    //        阈值画在固定范围内即见，超出范围部分自然被裁剪。
    if (fixedYRange == null) {
        thresholdWarn?.let {
            if (it > maxVal) maxVal = it.toFloat()
            if (it < minVal) minVal = it.toFloat()
        }
        thresholdAlert?.let {
            if (it > maxVal) maxVal = it.toFloat()
            if (it < minVal) minVal = it.toFloat()
        }
    }
    val range = (maxVal - minVal).coerceAtLeast(0.01f)

    // v21：Y 轴“整齐刻度”算法 —— 让刻度值干净、间隔均匀，消除“有的大有的小、间隔不统一”。
    val nice = if (fixedYRange != null) {
        val span = (maxVal - minVal).coerceAtLeast(0.01f)
        NiceAxis(minVal, maxVal, span / 4f)
    } else {
        computeNiceAxis(minVal, maxVal)
    }
    val axisMinF = nice.min
    val axisMaxF = nice.max
    val yStepF = nice.step
    // 根据步长决定小数位，保证刻度文字整齐统一（如 0/20/40 或 0.0/0.5/1.0）
    val yDecimals = if (yStepF >= 1f) 0 else if (yStepF >= 0.1f) 1 else 2

    // Y 轴刻度文本：yAxisLabelFormatter 优先（如流量自适应 B/s·KB/s·MB/s），否则走 formatTick + yUnit
    val yTickText: (Double) -> String = { v ->
        yAxisLabelFormatter?.invoke(v) ?: buildString {
            append(formatTick(v, yDecimals))
            if (yUnit.isNotEmpty()) append(" ")
            if (yUnit.isNotEmpty()) append(yUnit)
        }
    }

    val gridColor = palette.divider.copy(alpha = 0.5f)
    // P5c-3：fillAlpha（0..1）以乘法作用于填充基础透明度（曲线下方渐变的起止色）；
    //        默认 1f 与旧行为完全一致，0f 则填充完全透明。P7a：每 series 填充色在绘制循环内按各自 lineColor 计算。
    val fillAlphaClamped = fillAlpha.coerceIn(0f, 1f)
    // 轴刻度 / 时间戳 / 阈值标注全是遥测数字，统一走等宽（同宽字形，刻度不会左右跳动）
    val labelStyle = UfiTextStyles.monoCaption
    val labelColor = palette.textSecondary
    val timeColor = labelColor.copy(alpha = 0.6f)
    val textMeasurer = rememberTextMeasurer()
    // 曲线几何缓存（跨重组存活，见 drawWithCache 内的 geomKey 说明）
    val geomCache = remember { ChartGeometryCache() }

    // ── 图表动效状态（v17 优化：5 项动效）──
    // ① 折线左→右绘入（首次加载 / 区间切换 / 清空 时重放；日常刷新不重放）。
    val drawProgress = remember { Animatable(0f) }
    // 2026-08-26：重放判定从「首点时间精确相等」放宽为「窗口起点跳变超过 2 个桶 / 跨度变化超过 5%」。
    // 原来只要首点 t 一变就重放整条绘入动画，而滑动窗口（近 N 小时）每轮询一次首点就前移一个桶，
    // 全量重拉也会让桶边界重新对齐 —— 结果每次刷新都整图擦除重画一遍，这就是「刷新一次就整体重绘」。
    // 现在：末端追加、窗口滑动一两个桶都只是懒更新（进度保持 1，曲线直接跟随新数据）；
    // 只有真正换区间（1h↔24h、切历史某天）或由空变有才重放。
    val firstT = points.first().t
    val spanT = (points.last().t - firstT).coerceAtLeast(1L)
    val bucketT = (spanT / (points.size - 1).coerceAtLeast(1)).coerceAtLeast(1L)
    var prevFirstT by remember { mutableStateOf<Long?>(null) }
    var prevSpanT by remember { mutableLongStateOf(1L) }
    val needReplay = prevFirstT.let { prev ->
        prev == null ||
            abs(firstT - prev) > 2 * bucketT ||
            abs(spanT - prevSpanT).toFloat() / prevSpanT.toFloat() > 0.05f
    }
    LaunchedEffect(firstT, spanT) {
        if (needReplay) {
            drawProgress.snapTo(0f)
            drawProgress.animateTo(1f, tween(UfiMotion.Duration.Reveal))
        } else {
            // 懒更新：确保绘入进度维持在 1（不重放），曲线直接跟随新数据
            if (drawProgress.value != 1f) drawProgress.snapTo(1f)
        }
        prevFirstT = firstT
        prevSpanT = spanT
    }
    // ③ Y 轴值域 min/max 平滑过渡（v21：改用整齐刻度边界，使刻度干净、间隔均匀）。
    // FIX-18：增量更新（首点 t 不变、仅末点后移/值微变）时值域几乎不变，animateFloatAsState 已能
    // 平滑；但为避免"每更新一次数据"引发视觉重画颤抖，仅在【区间切换】时启用 300ms 过渡，
    // 普通增量更新直接 snap 到目标值域（无动画抖动），曲线即时跟随。
    val yAnimEnabled = needReplay
    val aMaxTarget = axisMaxF
    val aMinTarget = axisMinF
    // 2026-09-04（P2b）：关闭动画那一支原为 `tween(0)`。它的语义是「禁用动画」，写成 0 时长的
    // 补间只是碰巧等效，还让 `0` 混进了全库时长值集合里（被当成一个"档位"统计）。
    // 改用 `snap()` —— Compose 里"立即到位"的官方表达，语义自证，观感完全不变。
    val aMax by animateFloatAsState(
        aMaxTarget,
        animationSpec = if (yAnimEnabled) tween(UfiMotion.Duration.Smooth) else snap(),
        label = "yMaxAnim"
    )
    val aMin by animateFloatAsState(
        aMinTarget,
        animationSpec = if (yAnimEnabled) tween(UfiMotion.Duration.Smooth) else snap(),
        label = "yMinAnim"
    )
    val aRange = (aMax - aMin).coerceAtLeast(0.01f)
    // ④ 选中相关状态（须早于 LaunchedEffect(selectedIndex) 与下方绘制）
    var dragRatio by remember { mutableFloatStateOf(-1f) }  // -1=无拖动, 0..1=位置比例
    val timeFmt = remember(timeRange) {
        SimpleDateFormat(if (timeRange == "today") "HH:mm" else "MM/dd HH:mm", Locale.getDefault())
    }
    // X 轴时间边界（所有参与绘制的 series 取并集）。2026-09-03：X 从「按下标等距」改成
    // 「按时间戳比例」后，选中判定也必须按时间反算 —— 否则有空洞时手指位置与曲线上的点会错开。
    //
    // 2026-09-04 排查「曲线画不到右端终点」时复核过这里，结论记下来免得下次再怀疑一遍：
    // ① 取并集意味着 tMax 是**最靠后**那条序列的末点，所以末点更早的序列本来就画不到右边缘。
    //    但这不是当前那个现象的原因 —— 流量上/下行（traffic_rx / traffic_tx）在服务端是
    //    **同一张表 traffic_records、同一句 `GROUP BY timestamp / bucketMs`** 出来的两列聚合
    //    （core/api/.../MonitorRoutes.kt 的 aggregateBetween + core/database/.../Daos.kt 的
    //    getAggregatedRxBetween / getAggregatedTxBetween），桶集合逐字相同，末点时间戳不可能错开；
    //    而且当前监控页是「一类指标一张图」，extraSeries 恒为空，根本没有多序列合并那条路径。
    // ② 单序列时 tMax 就是自己的末点，xForT(末点) 必然等于 padding + plotWidth，
    //    也就是绘图区右边缘 —— 数据侧不会让末点"短"在中间。真正把它切掉的是入场 reveal 的
    //    右裁边界压在末点圆心上（见下方 onDrawBehind 里的 revealFullX），以及 app 侧按
    //    queryEndMs 硬裁最新那个桶心（见 MonitorScreen 的 chartData）。两处都已修。
    // ③ 2026-09-04 第二轮（现象换成「末点的 x 对不上它的时间」）再确认一遍：
    //    tMin/tMax **取自实际点集**（就是下面这个 forEach），不是 queryStartMs/queryEndMs，
    //    也不含那半个桶宽的放宽 —— MonitorScreen 里的半桶只影响"哪些点进得来"（filter），
    //    不影响轴边界。所以末点恒在右边缘，错位不可能来自这里；真凶是 X 轴刻度那侧
    //    （见文件末尾 chartXPx 处的说明）。
    // 时间轴一律按真实时间戳映射，不做任何拉伸对齐：拉伸能让线贴住右边缘，代价是时间轴说谎。
    //
    // 2026-09-04（同屏各图终点对不齐）：新增外部时间域优先。给了 [xDomainStartMs]/[xDomainEndMs]
    // 就用它当轴边界，各图共用同一条标尺；没给才退回本图点集的 min/max（旧行为）。
    // 外部域只在合法（start < end）时生效，非法值一律忽略而不是让轴崩掉。
    val tBounds = remember(allSeries, xDomainStartMs, xDomainEndMs) {
        val externalLo = xDomainStartMs
        val externalHi = xDomainEndMs
        if (externalLo != null && externalHi != null && externalHi > externalLo) {
            externalLo to externalHi
        } else {
            var lo = Long.MAX_VALUE
            var hi = Long.MIN_VALUE
            allSeries.forEach { s ->
                s.points.forEach { p ->
                    if (p.t < lo) lo = p.t
                    if (p.t > hi) hi = p.t
                }
            }
            if (lo == Long.MAX_VALUE) 0L to 1L else lo to hi
        }
    }
    val tMinAll = tBounds.first
    val tSpanAll = (tBounds.second - tMinAll).coerceAtLeast(1L)
    // X 轴刻度格式按**实际显示跨度**自适应（2026-09-03）。
    // 原来这里无条件 `SimpleDateFormat("HH:mm")` 且 remember 不带 key，理由是"日期由卡片上方的
    // 当前范围交代"。但那处只有 `yyyy-MM-dd ~ yyyy-MM-dd`，选近 7 天 / 近 30 天后三个刻度会显示成
    // `13:26 … 01:26 … 13:26` —— 根本对不上是哪天的数据。
    //
    // 2026-09-04（X 轴错位修复）：跨度必须取 **tSpanAll**（= 绘图用的 tMin/tMax），
    // 原来写的是 `points.last().t - points.first().t`（只看主 series）。单 series 时两者相等，
    // 但多 series 取并集时就是两套边界 —— 刻度与曲线必须同源，这里不允许有第二个跨度定义。
    val axisSpanMs = if (points.size >= 2) tSpanAll else 0L
    val axisPattern = when {
        // ≤36h：同一天或跨夜，时刻足够（也覆盖"今天"这一档）
        axisSpanMs <= 36L * 3600_000L -> "HH:mm"
        // ≤7 天：日期 + 时刻
        axisSpanMs <= 7L * 86_400_000L -> "MM/dd HH:mm"
        // 更长：只给日期，避免刻度互挤
        else -> "MM/dd"
    }
    val axisTimeFmt = remember(axisPattern) { SimpleDateFormat(axisPattern, Locale.getDefault()) }
    val selectedIndex = if (dragRatio >= 0f && points.size >= 2) {
        nearestIndexByTime(points, tMinAll + (tSpanAll * dragRatio.coerceIn(0f, 1f)).toLong())
    } else -1
    // ② tooltip 数值实时读取（不再使用 count-up 动画）：直接取当前选中点 / 各 series 最近点的真实值，
    //    避免数据刷新（points 更新）或连续拖动时数值 stale 不刷新——旧实现仅在 selectedIndex 变化时重算
    //    valueAnims 的 target，导致后端推新点（index 不变）时第二行（extraSeries）显示旧值。
    // ④ 选中点脉冲光圈（循环呼吸）——只有真的选中时才创建这个无限动画。
    //    InfiniteTransition 一旦存在就会持续请求帧；而它的值又在 Canvas 的 draw lambda 里被读取，
    //    等于让整张图表每帧重绘（单 series 分支一帧要分配 719 个 Path）。条件化创建后，
    //    没按住图表时不再有常驻帧请求。
    //    2026-08-26 再修：这里保留 State 本体、把 `.value` 的读取推迟到 draw 阶段。
    //    若在组合阶段读，脉冲的每一帧都会触发【重组】，而重组会重建 drawWithCache 的
    //    构建块 → 曲线几何被逐帧重算；只在 draw 里读，脉冲就只引起重绘、不引起几何重建。
    val hasSelection = selectedIndex in points.indices
    var pulseRState: State<Float>? = null
    var pulseAlphaState: State<Float>? = null
    if (hasSelection) {
        val pulse = rememberInfiniteTransition(label = "pulse")
        pulseRState = pulse.animateFloat(12f, 22f, infiniteRepeatable(tween(UfiMotion.Duration.Ambient, easing = UfiMotion.Easing.Standard), RepeatMode.Restart))
        pulseAlphaState = pulse.animateFloat(0.35f, 0f, infiniteRepeatable(tween(UfiMotion.Duration.Ambient), RepeatMode.Restart))
    }
    // ⑤ 最新值圆点：原来挂了一个常驻 latestPulse 无限动画（光晕缩放 + alpha 呼吸），
    //    而它的值在 draw 阶段被无条件读取 → 4 张图表卡永久以屏幕刷新率整图重绘。
    //    2026-08-26 改为静态光晕：末点仍是「光晕 + 白边 + 主题色芯」，只是不再呼吸，
    //    换来图表页只在数据变化/交互时才重绘。
    val latestScale = 1.05f
    val latestGlowAlpha = 0.16f
    // ⑥ 选中指示线/点平滑跟随手指；按下瞬间吸附到手指位置（snap），不从左边缘(origin)滑入。
    // 关键修复：松手时【不】snapTo(-1)，指示线/圆点停在上次手指位置、由 indicatorAlpha 原地淡出，
    // 否则 coerceIn(0,1) 会把 -1 夹成 0（图表最左/原点），造成“向原点抽搐”。
    val selRatioAnim = remember { Animatable(-1f) }
    LaunchedEffect(dragRatio) {
        // 按下与拖动一律 snap：指示线要和手指严格同步（它是"我正在看这一时刻"的锚点，
        // 有滞后反而让人以为选错了点）。2026-09-04：原来这里写成 if/else 两个分支却都是
        // snapTo，留着只会让人以为按下与拖动走的是不同动画，合并掉。
        if (dragRatio >= 0f) selRatioAnim.snapTo(dragRatio.coerceIn(0f, 1f))
        // dragRatio < 0（松手）：保持 animSelRatio 在最后位置，指示器原地淡出（不跳回原点）
    }
    val animSelRatio = selRatioAnim.value
    val indicatorAlpha by animateFloatAsState(if (selectedIndex in points.indices) 1f else 0f, tween(UfiMotion.Duration.Swift), label = "indicatorAlpha")

    // Y 轴宽度：按最宽刻度文本自适应测量（含单位），避免 "MB/s"/"KB/s" 等长单位被截断
    // 2026-08-26 性能：5 次 textMeasurer.measure() 原来写在 composable 函数体里，
    // 每次重组（拖动、轮询、动画）都重测 5 遍文本；改为按「值域 + 小数位 + 单位」缓存。
    val density = LocalDensity.current
    val yAxisWidth = remember(axisMinF, axisMaxF, yDecimals, yUnit, labelStyle, density) {
        with(density) {
            var maxW = 0
            for (i in 0..4) {
                val value = axisMaxF.toDouble() - (axisMaxF.toDouble() - axisMinF.toDouble()) * i / 4
                val w = textMeasurer.measure(
                    text = AnnotatedString(yTickText(value)),
                    style = labelStyle.copy(fontWeight = UfiWeight.Emphasis)
                ).size.width
                if (w > maxW) maxW = w
            }
            (maxW.toDp() + 4.dp).coerceAtLeast(32.dp)
        }
    }
    // 2026-08-24：图表与 Y 轴 / 卡片边框的内距减少 50%（4dp → 2dp），图表区更舒展。
    val yGap = 2.dp
    // 绘图区左右外距：**曲线容器与 X 轴刻度容器必须用同一对值**。
    // 2026-09-04：刻度行原来写死 `end = yGap`，而曲线容器在 symmetricHorizontal=true 时
    // 右外距是 `yAxisWidth + yGap` —— 两处差了整整一个 yAxisWidth（≥32dp），
    // 这就是"末点看着落在更早的时间上"的主因。提成变量，从结构上不可能再写歪。
    val plotStartPad = yAxisWidth + yGap
    val plotEndPad = if (symmetricHorizontal) yAxisWidth + yGap else yGap
    Column(modifier = modifier) {
        // v18：标题已上移到卡片/页面层级，组件内部不再显示；
        // 图例 chip 行作为系列标识（单/多 series 均显示），尺寸更紧凑。
        // v22：showLegend=false 时（图例已由卡片标题行渲染）不再重复绘制。
        if (showLegend) {
            SeriesLegendChips(
                allSeries = allSeries,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            )
        }

        // Chart area with floating tooltip overlay
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val chartHeight = 92.dp
            val boxWidth = maxWidth

            // Y 轴标签：无背景，靠加粗深色数字 + 中性短刻度做锚点（克制、不突兀）
            // v23 提升对比：alpha 0.7 → 0.9，数字更清晰易读
            Column(
                modifier = Modifier
                    .width(yAxisWidth)
                    .height(chartHeight)
                    .padding(end = yGap),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                for (i in 0..4) {
                    val value = aMax.toDouble() - (aMax.toDouble() - aMin.toDouble()) * i / 4
                    Text(
                        text = yTickText(value),
                        style = labelStyle.copy(fontWeight = UfiWeight.Emphasis),
                        color = palette.textPrimary.copy(alpha = 0.9f),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Canvas + floating tooltip container
            Box(
                modifier = Modifier
                    // v23 对称留白：symmetricHorizontal=true 时右侧保留 yAxisWidth + yGap，
                    //              与左侧 Y 轴列等距，图表绘图区视觉居中，避免偏左感
                    // 2026-09-04：这对外距与下方 X 轴刻度行共用 plotStartPad / plotEndPad。
                    .padding(start = plotStartPad, end = plotEndPad)
                    .fillMaxWidth()
                    .height(chartHeight)
            ) {
                // 2026-08-26 性能（监控页切页卡顿的主因）：原来所有 Offset / Path 都在 draw lambda
                // 里构建 —— 单 series 分支每帧分配 n-1 个 Path 并发起 n-1 次 drawPath（720 点 = 719 个），
                // 加上当时的 min/max 区间带 / 渐变填充 / 阴影线共 ~3n 个 Offset；而进页时 drawProgress 0→1
                // 的 600ms 绘入动画会逐帧触发 draw，4 张图表卡叠加把主线程和 GC 一起打满。
                // 现在：几何构建收进 drawWithCache 的构建块（仅「画布尺寸 / 数据 / Y 值域」变化时重建），
                // 绘入动画每帧只是换一个 clipRect 重放已缓存 Path；相邻同色段合并成一条 Path，
                // 无阈值的指标整条主线只剩 1 个 Path。
                // 2026-09-03：区间带删除后每个 series 每段又少建一个 Path、少 2×段长个 Offset。
                // 副作用（可接受）：多 series 时改为「所有填充 → 所有线」的绘制顺序，
                // 线不再被后一条 series 的填充压住，视觉上更清楚。
                Spacer(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            // v26：press 即显示指示器+tooltip，move 实时跟随，up/cancel 淡出。
                            // key=Unit：不依赖 points（增量更新时 points 引用变了也不重建手势处理器，
                            // 避免每次轮询推送都重新注册 pointerInput 造成的潜在抖动/重组）。
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                dragRatio = (down.position.x / size.width).coerceIn(0f, 1f)
                                down.consume()
                                drag(down.id) { change ->
                                    dragRatio = (change.position.x / size.width).coerceIn(0f, 1f)
                                    change.consume()
                                }
                                dragRatio = -1f
                            }
                        }
                        .drawWithCache {
                            val width = size.width
                            val height = size.height
                            val padding = CHART_PLOT_INSET_PX
                            val chartH = height - 2 * padding
                            val count = points.size
                            fun yFor(v: Float) = height - padding - ((v - aMin) / aRange) * chartH
                            // X 轴映射（2026-09-03 精度重写）：所有 series 一律「时间戳 → 绘图区宽度」线性映射。
                            // 原来主 series 走的是下标等距 —— 服务端把空桶整行跳过后，一段几小时的空洞
                            // 在图上只占一个正常点距，曲线被硬拉成一条直线，看起来「那段时间数据很平稳」。
                            // 时间比例映射让空洞按真实时长占宽，配合下面的断档切分才能表达「这里没有数据」。
                            val plotWidth = width - 2 * padding
                            // 唯一的「时间 → x」入口（定义见 chartXPx）。刻度行走的是同一个函数、
                            // 同一份 tMinAll / tSpanAll / plotWidth，只是 plotLeft 换成含左外距的值。
                            fun xForT(t: Long): Float =
                                chartXPx(t, tMinAll, tSpanAll, padding, plotWidth)
                            fun xForSeriesPoint(series: MonitorSeries, i: Int): Float = xForT(series.points[i].t)

                            val alertColor = ChartAlert
                            val warnColor = ChartWarn
                            val gridDash = PathEffect.dashPathEffect(floatArrayOf(3f, 5f))
                            val tickColor = palette.textSecondary.copy(alpha = 0.45f)
                            val tickLen = 5.dp.toPx()

                            // ── 几何缓存（2026-08-26 第三轮）──
                            // drawWithCache 的构建块在【每次重组】都会重跑（lambda 捕获值一变就是新实例），
                            // 而重组的诱因很多：拖动 tooltip、轮询推送、isLoading 翻转、父级 state 变化…
                            // 这里用一个 remember 住的 key 比较，把「重组」和「几何真的变了」区分开：
                            // 尺寸 / 数据 / Y 值域 / 阈值 / 配色都没变时，直接复用上次建好的 Path。
                            val geomKey = listOf(
                                width, height, points, extraSeries, aMin, aRange,
                                fillAlphaClamped, thresholdWarn, thresholdAlert, lowerIsWorse,
                                lineColor, comparePoints, labelStyle, bucketMs
                            )
                            val geom = geomCache.getOrBuild(geomKey) {
                                val fillLayers = ArrayList<Pair<Path, Brush>>(allSeries.size)       // 渐变填充
                                val shadowLayers = ArrayList<Pair<Path, Color>>(allSeries.size)     // 主线柔光阴影
                                val strokeLayers = ArrayList<Pair<Path, Color>>(allSeries.size + 2) // 主线（同色段已合并）
                                val singleDots = ArrayList<Pair<Offset, Color>>(1)                  // 点数极少 / 全是孤立段的 series
                                val latestDots = ArrayList<Pair<Offset, Color>>(allSeries.size)     // 各 series 末点

                                // ── 断档切分（2026-09-03 引入，2026-09-04 换判定基准）──
                                // 判定规则与理由见 [chartGapLimit] / [chartSegments]（提到顶层是为了能被单测钉住，
                                // 这条规则误判一次就是满屏「短点」，值得有回归护栏）。
                                // 2026-09-03：原来分段结果由「min/max 区间带」和「填充 + 主线」两轮共用，
                                // 区间带删除后只剩后者一个消费者，仍先整体算好而不是内联进循环 ——
                                // 它是 O(n) 且 geomKey 命中时整块跳过，放在这里更容易与上面的注释对上。
                                val seriesSegments = allSeries.map { chartSegments(it.points, bucketMs) }

                                // ── v17：每 series 渐变填充 + 平滑 cubic bezier 主线（按断档分段构建）──
                                allSeries.forEachIndexed { seriesIndex, s ->
                                    val n = s.points.size
                                    if (n == 0) return@forEachIndexed
                                    // 末点圆点只画整条序列的末点（不是每段一个），语义仍是「最新值」
                                    latestDots += Offset(xForSeriesPoint(s, n - 1), yFor(s.points[n - 1].avg.toFloat())) to s.lineColor
                                    seriesSegments[seriesIndex].forEach { seg ->
                                        val pts = seg.map { i -> Offset(xForSeriesPoint(s, i), yFor(s.points[i].avg.toFloat())) }
                                        val m = pts.size
                                        if (m < 2) {
                                            // 孤立段（两侧都是空洞）。2026-09-04：不再无条件画圆点。
                                            // 长序列里出现孤立段说明断档判定仍偏敏感，此时画点等于把判定误差
                                            // 直接兑换成满屏「短点」，让用户以为数据本身是离散的。
                                            // 只有整条序列本来就没几个点（≤3）时才画 —— 那种情况不画就真什么都看不见。
                                            //
                                            // 2026-09-04 排查「画不到右端终点」时确认过**末段**这一支：
                                            // chartSegments 从不丢段（末段一定是 `segStart..lastIndex`），
                                            // 这里跳过的只是"1 个点连不成线"这件事实；而整条序列的末点在上面
                                            // 已无条件进了 latestDots，右端仍有那颗最新值圆点。
                                            // 于是末段只剩 1 点时的观感是「圆点在右边、折线停在前一段」——
                                            // 那是真有一段采集空洞，不该靠补一根线把它连上（会画出假数据）。
                                            if (s.points.size <= 3) singleDots += pts[0] to s.lineColor
                                            return@forEach
                                        }
                                        fillLayers += Path().apply {
                                            moveTo(pts[0].x, height - padding)
                                            lineTo(pts[0].x, pts[0].y)
                                            cubicSmoothLine(pts)
                                            lineTo(pts.last().x, height - padding)
                                            close()
                                        } to Brush.verticalGradient(
                                            listOf(
                                                s.lineColor.copy(alpha = 0.32f * fillAlphaClamped),
                                                s.lineColor.copy(alpha = 0.06f * fillAlphaClamped)
                                            ),
                                            startY = padding,
                                            endY = height - padding
                                        )
                                        val linePath = Path().apply {
                                            moveTo(pts[0].x, pts[0].y)
                                            cubicSmoothLine(pts)
                                        }
                                        shadowLayers += linePath to s.lineColor.copy(alpha = 0.14f)
                                        if (seriesIndex == 0 && extraSeries.isEmpty()) {
                                            // 单 series：保留段级阈值着色，但把「颜色相同的连续段」合并成一条 Path
                                            // （索引都相对本断档分段，故用 seg.first 偏移回原序列取值）
                                            fun levelAt(k: Int) = chartSegmentLevel(
                                                v1 = s.points[seg.first + k].avg,
                                                v2 = s.points[seg.first + k + 1].avg,
                                                warn = thresholdWarn,
                                                alert = thresholdAlert,
                                                lowerIsWorse = lowerIsWorse
                                            )
                                            var runStart = 0
                                            var runLevel = levelAt(0)
                                            fun flushRun(endIdx: Int) {
                                                strokeLayers += Path().apply {
                                                    moveTo(pts[runStart].x, pts[runStart].y)
                                                    for (i in runStart until endIdx) {
                                                        cubicSmoothSegment(
                                                            pts[(i - 1).coerceAtLeast(0)],
                                                            pts[i],
                                                            pts[i + 1],
                                                            pts[(i + 2).coerceIn(0, m - 1)]
                                                        )
                                                    }
                                                } to when (runLevel) {
                                                    ChartThresholdLevel.ALERT -> alertColor
                                                    ChartThresholdLevel.WARN -> warnColor
                                                    ChartThresholdLevel.NORMAL -> s.lineColor
                                                }
                                            }
                                            for (i in 1 until m - 1) {
                                                val level = levelAt(i)
                                                if (level == runLevel) continue
                                                flushRun(i)
                                                runStart = i
                                                runLevel = level
                                            }
                                            flushRun(m - 1)
                                        } else {
                                            strokeLayers += linePath to s.lineColor
                                        }
                                    }
                                }

                                // ── P3a 阈值水平虚线 + 阈值数值小标签（线右端，文字小号）。
                                //    P7a：多 series 合并视图隐藏阈值虚线（不同 series 单位可能不可比），单 series 保持完整阈值。 ──
                                val thresholdLines = ArrayList<Triple<Float, Color, TextLayoutResult>>(2)
                                if (extraSeries.isEmpty()) {
                                    thresholdWarn?.let { v ->
                                        thresholdLines += Triple(
                                            yFor(v.toFloat()),
                                            warnColor,
                                            textMeasurer.measure(AnnotatedString(formatWithUnit(v)), labelStyle.copy(color = warnColor))
                                        )
                                    }
                                    thresholdAlert?.let { v ->
                                        thresholdLines += Triple(
                                            yFor(v.toFloat()),
                                            alertColor,
                                            textMeasurer.measure(AnnotatedString(formatWithUnit(v)), labelStyle.copy(color = alertColor))
                                        )
                                    }
                                }

                                // ── P3a 对比基线：灰色虚线，按相对位置对齐主区间时间轴；不参与 tooltip ──
                                val comparePath = if (comparePoints != null && comparePoints.size >= 2) {
                                    val cStart = comparePoints.first().t
                                    val cSpan = (comparePoints.last().t - cStart).coerceAtLeast(1L)
                                    Path().apply {
                                        comparePoints.forEachIndexed { i, pt ->
                                            val x = (padding + (width - 2 * padding) * (pt.t - cStart).toFloat() / cSpan.toFloat())
                                                .coerceIn(padding, width - padding)
                                            val y = yFor(pt.avg.toFloat())
                                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                                        }
                                    }
                                } else null

                                ChartGeometry(
                                    fillLayers = fillLayers,
                                    shadowLayers = shadowLayers,
                                    strokeLayers = strokeLayers,
                                    singleDots = singleDots,
                                    latestDots = latestDots,
                                    thresholdLines = thresholdLines,
                                    comparePath = comparePath
                                )
                            }
                            val fillLayers = geom.fillLayers
                            val shadowLayers = geom.shadowLayers
                            val strokeLayers = geom.strokeLayers
                            val singleDots = geom.singleDots
                            val latestDots = geom.latestDots
                            val thresholdLines = geom.thresholdLines
                            val comparePath = geom.comparePath
                            val thresholdDash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                            val compareDash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

                            onDrawBehind {
                                // ── 入场 reveal 的右裁边界（2026-09-04 修「曲线画不到右端终点」）──
                                // 末点的 x 恰好是 xForT(tMax) = padding + plotWidth = width - padding，
                                // 而原来 revealX 在 drawProgress=1 时算出来的也正是 width - padding ——
                                // 裁剪面刚好压在末点的**圆心**上。可是这些层都是有宽度的笔触：
                                // 主线 Stroke(3f, Round) 要向右铺 1.5f，柔光 Stroke(8f) 要铺 4f，
                                // 于是末点右半边的线帽与柔光被切掉，而且不是动画期间才切 ——
                                // 动画结束后恒定被切，看起来就是「差一点没画到终点」。
                                // 这里把「进度 1」的目标定义成 width - padding + 最粗笔触半宽，
                                // 用一次 lerp 从 padding 走到它：进度到 1 就必然是整幅，不会再有
                                // off-by-one，也不会重复扣减 padding。超出画布那 1px 由画布自身裁掉。
                                val strokeOverhang = 4f     // 最粗的一层是 8f 柔光 → 半宽 4f
                                val revealFullX = width - padding + strokeOverhang
                                val revealX = padding + (revealFullX - padding) * drawProgress.value
                                for (i in 0..4) {
                                    val y = padding + chartH * i / 4
                                    drawLine(gridColor, Offset(padding, y), Offset(width - padding, y), 0.8f, pathEffect = gridDash)
                                    // 轻量刻度：从绘图区左边界向右伸出短横线，中性色，不喧宾夺主
                                    drawLine(tickColor, Offset(padding, y), Offset(padding + tickLen, y), 1.2f)
                                }
                                clipRect(left = padding, top = 0f, right = revealX, bottom = height) {
                                    // 2026-09-03：这里原来是「区间带 → 内层 clipRect{渐变填充}」两层。
                                    // 内层那道裁剪只为把填充夹在绘图区右边界内，而填充路径本身最右也只到
                                    // 末点的 x（= width - padding），区间带删掉后它就是个空操作，
                                    // 一并拍平（少一层 saveLayer）。
                                    // 2026-09-04 补：外层 revealX 现在会略微超过 width - padding（见上），
                                    // 那多出来的一点点只用于放过笔触半宽，几何上没有任何路径会画到那儿。
                                    fillLayers.forEach { (path, brush) -> drawPath(path, brush) }
                                    shadowLayers.forEach { (path, color) ->
                                        drawPath(path, color, style = Stroke(width = 8f, cap = StrokeCap.Round))
                                    }
                                    strokeLayers.forEach { (path, color) ->
                                        drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
                                    }
                                    singleDots.forEach { (center, color) -> drawCircle(color, 4.dp.toPx(), center) }
                                    thresholdLines.forEach { (y, color, layout) ->
                                        drawLine(color, Offset(padding, y), Offset(width - padding, y), 1.5f, pathEffect = thresholdDash)
                                        drawText(
                                            textLayoutResult = layout,
                                            topLeft = Offset(
                                                x = width - padding - layout.size.width,
                                                y = (y - layout.size.height - 2f).coerceAtLeast(padding)
                                            )
                                        )
                                    }
                                    comparePath?.let {
                                        drawPath(it, Color.Gray.copy(alpha = 0.7f), style = Stroke(width = 1.5f, pathEffect = compareDash))
                                    }
                                }

                                // v21：选中指示（垂直虚线 + 脉冲光环 + 主题色点），滑动平滑、淡入淡出，不闪烁
                                if (indicatorAlpha > 0.001f) {
                                    val ratio = animSelRatio.coerceIn(0f, 1f)
                                    val sx = padding + plotWidth * ratio
                                    // 沿曲线插值取 Y：按【时间】而不是下标插值 —— X 已按时间映射，
                                    // 若还按下标算，有空洞时圆点会明显离开曲线。
                                    val targetT = tMinAll + (tSpanAll * ratio).toLong()
                                    var i0 = 0
                                    while (i0 < count - 2 && points[i0 + 1].t <= targetT) i0++
                                    val i1 = (i0 + 1).coerceAtMost(count - 1)
                                    val segSpanT = (points[i1].t - points[i0].t).coerceAtLeast(1L)
                                    val frac = ((targetT - points[i0].t).toFloat() / segSpanT.toFloat()).coerceIn(0f, 1f)
                                    val selV = (points[i0].avg + (points[i1].avg - points[i0].avg) * frac).toFloat()
                                    val sy = yFor(selV)
                                    val selectDash = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))
                                    drawLine(lineColor.copy(alpha = 0.30f * indicatorAlpha), Offset(sx, padding), Offset(sx, height - padding), 1.2f, pathEffect = selectDash)
                                    drawCircle(
                                        lineColor.copy(alpha = (pulseAlphaState?.value ?: 0f) * indicatorAlpha),
                                        (pulseRState?.value ?: 12f).dp.toPx(),
                                        Offset(sx, sy)
                                    )
                                    drawCircle(lineColor.copy(alpha = 0.35f * indicatorAlpha), 8.dp.toPx(), Offset(sx, sy))
                                    // 2026-09-03（P1c）：这层"白芯"原为写死 `Color.White`。它的作用是把最内层
                                    // 主题色圆点从外圈光环里"抠"出来 —— 要的是**卡面色**（图表画在 UfiCard 上，
                                    // 下方 tooltipBg 同样取 cardBg），而不是白色本身。浅色主题 cardBg 本就接近白，
                                    // 所以这处一直看不出问题；换成深色/有色卡面的配色后，会残留一圈突兀的白。
                                    drawCircle(palette.cardBg.copy(alpha = indicatorAlpha), 5.dp.toPx(), Offset(sx, sy))
                                    drawCircle(lineColor.copy(alpha = indicatorAlpha), 3.5f.dp.toPx(), Offset(sx, sy))
                                }
                                // P7a：每 series 在各自末点画最新值圆点（静态光晕 + 卡面色隔离环 + 主题色芯）
                                latestDots.forEach { (center, color) ->
                                    drawCircle(color.copy(alpha = latestGlowAlpha), 7.5f.dp.toPx() * latestScale, center)
                                    // 2026-09-03（P1c）：同上，原写死 `Color.White` 的"白边"其实是**卡面色隔离环**，
                                    // 用来在光晕与实心芯之间留一道缝；写死白在换配色后会变成看得见的白圈。
                                    drawCircle(palette.cardBg, 5.5f.dp.toPx(), center)
                                    drawCircle(color, 3.5f.dp.toPx(), center)
                                }
                            }
                        }
                )

                // Floating tooltip bubble — 触发位置 = 手指 X(横向平滑跟随) + 选中数据点 Y(纵向跟随)；
                // 显示位置：始终放在数据带上方，允许浮到图表上方(负 top)，彻底避免遮挡折线；
                // 动画：用手动 alpha+scale 替代 AnimatedVisibility，避免 add/remove 重组导致的抽搐/布局跳变。
                val showTooltip = selectedIndex in points.indices
                // 退出时冻结：保存上一次有效 index 与 dragRatio，hide 期间位置/数据保持不变，仅透明度淡出。
                // 2026-09-04：改用普通对象持有，不再用 mutableState。原实现在【组合过程中】写这两个
                // MutableState，又在同一次组合里把它们读出来（dispIndex / effRatio）——Compose 检测到
                // "本次组合读过的状态被本次组合改写"就会再排一轮重组，于是拖动时每个触摸事件都跑两轮
                // 组合，气泡的位置与文字在两轮之间来回，这是「抽搐」的成因之一。
                // 这两个值只在淡出的那 160ms 被读，本来就不需要可观察性：驱动重组的是 dragRatio。
                val frozen = remember { TooltipFreeze() }
                if (showTooltip) {
                    frozen.index = selectedIndex
                    frozen.ratio = dragRatio
                }
                val dispIndex = if (showTooltip) selectedIndex else frozen.index.coerceIn(points.indices)
                val effRatio = if (dragRatio >= 0f) dragRatio else frozen.ratio
                val density = LocalDensity.current

                // 显示/隐藏统一走 alpha 淡入淡出 + 轻微缩放，单 series 与多 series 共用同一套 spec
                // （用手动 alpha/scale 而不是 AnimatedVisibility：后者 add/remove 子树会引发布局跳变）。
                val tooltipAlpha by animateFloatAsState(if (showTooltip) 1f else 0f, tween(UfiMotion.Duration.Swift), label = "tooltipAlpha")
                val tooltipScale by animateFloatAsState(if (showTooltip) 1f else 0.94f, UfiMotion.tooltipPop(), label = "tooltipScale")

                // 横向位置
                val labelWidthDp = yAxisWidth + yGap
                val chartAreaWidthDp = boxWidth - labelWidthDp
                val marginDp = 6.dp
                // 2026-09-04：气泡宽度**固定**，不再由内容撑开。
                // 原来是 widthIn(min=110, max=…)，实际宽度取决于最长那行文字；而文字随选中点变化
                // （"9.9%"→"100.0%"、"0.0 B/s"→"389.0 KB/s"），拖动时右边缘每帧换一个宽度，
                // 又叠上以中心为原点的 scale，看起来就是气泡在抖。固定宽度后内容变化只影响文字本身。
                // 单 / 多 series 也统一成同一个公式（原来 150dp vs 190dp 两套），两类图表几何一致。
                // 2026-09-03 收窄（0.62/190dp → 0.54/164dp）：撑出 190dp 上限的是已删除的
                // 「峰值 xxx    谷值 xxx」那行（两段文字 + SpaceBetween，是气泡里最长的内容）。
                // 现在最长一行只剩「色块 + 指标名 + 数值」，仍按 190dp 留宽会在数值右侧空出一大片，
                // 气泡看着比它承载的信息大一圈。下限保持 110dp：指标名 weight(1f) 需要余量才不至于被截。
                val tooltipWidthDp = (chartAreaWidthDp * 0.54f)
                    .coerceIn(110.dp, 164.dp)
                    .coerceAtMost((chartAreaWidthDp - marginDp * 2f).coerceAtLeast(80.dp))
                val rawCenterDp = chartAreaWidthDp * effRatio.coerceIn(0f, 1f)
                val rawLeftDp = rawCenterDp - tooltipWidthDp / 2f
                val targetLeftDp = rawLeftDp.coerceIn(marginDp, (chartAreaWidthDp - tooltipWidthDp - marginDp).coerceAtLeast(marginDp))
                // 横向平滑跟随手指；退出时 effRatio 已冻结 → 原地淡出，不滑回原点。
                // 2026-09-04：spec 从 tooltipPop（dampingRatio 0.85，会过冲）换成 sliderTrack
                // （临界阻尼 1.0 / 刚度 350，语义就是"跟随拖拽"）。过冲弹簧在被逐个触摸事件不断重定目标时
                // 会持续来回摆，尤其在 coerceIn 撞到绘图区边界那一刻——气泡贴边还在左右弹。
                val animatedLeftDp by animateDpAsState(targetLeftDp, UfiMotion.sliderTrack(), label = "tooltipX")

                // 纵向：2026-08-26 起「浮到绘图区上方外侧」。
                // 原来是在绘图区【内部】跟着选中点上下挪（上方放不下就翻到下方）——但绘图区只有 92dp 高，
                // 气泡本身 50dp+，无论放上还是放下都会压住曲线，这就是「遮挡图表」。
                // 现在：top 取负值，把气泡整体抬到绘图区上沿之外（落在卡片标题行那条留白上），
                // 横向仍跟手，纵向靠指示虚线 + 选中圆点与数据点关联，不再需要贴着点走。
                // 抬升量上限 44dp：卡片是 Surface（按圆角裁剪），抬过头会被卡片上沿切掉；
                // 44dp ≈ 图表框内上方居中留白 + 标题行高度，实测不会被裁。
                // 2026-09-04：抬升量只用**估算**高度算，不再引入 onSizeChanged 测得的真实高度 ——
                // 「测量 → 写 state → 重新布局」是一条自反馈链，行数/字号一变就先跳一次再定住；
                // 而估算高度总是大于 44dp，minOf 恒取 maxLift，测量值对结果本来就没有影响。
                val gapPx = with(density) { 4.dp.toPx() }
                val maxLiftPx = with(density) { 44.dp.toPx() }
                val estimatedTooltipHPx = with(density) {
                    // 2026-09-03：删掉「峰值/谷值」行后这里同步去掉 extremaLineH，
                    // 并把 headerH 由 16dp 改为 18dp（含时间行自带的 2dp bottom padding）、rowH 15→16dp。
                    // 为什么要顺手校准而不是只删一项：上面那段注释依赖的前提是「估算高度恒 > 44dp，
                    // minOf 永远取 maxLift」。单 series 只剩两行，按旧系数算是 16+15+8=39dp，
                    // 加 4dp 间隙也才 43dp < 44dp —— 前提被打破，抬升量会改由这个粗糙估算决定，
                    // 而它低估了真实行高，气泡底沿会压回绘图区。校准后单 series 为 18+16+12=46dp，
                    // 估算重新恒大于上限，抬升仍稳定钉在 44dp。
                    val headerH = 18.dp.toPx()
                    val rowH = 16.dp.toPx()
                    val padV = 12.dp.toPx()     // 6+6，与下方 .padding(vertical=6.dp) 一致
                    headerH + allSeries.size * rowH + padV
                }
                val displayTopDp = with(density) { (-minOf(estimatedTooltipHPx + gapPx, maxLiftPx)).toDp() }
                val cPt = points[dispIndex]

                // tooltip 背景使用纯色（强制不透明），避免被下方卡片边框/折线穿透
                val tooltipBg = palette.cardBg.copy(alpha = 1f)
                val tooltipShape = UfiCardDefaults.shape
                // 2026-09-04：描边从 `accent.copy(alpha = 0.75f)` 换成 [ResolvedPalette.toastBorder]。
                //
                // 原来那圈是**主色 75% 不透明**的实边：气泡里只有 2~3 行小字，一圈饱和主色比内容还重，
                // 深色系配色下更是直接变成一道又粗又暗的轮廓 —— 这就是"深色描边不好看"。
                //
                // 为什么取 toastBorder 而不是 divider：
                // · toastBorder 是 alpha 叠加型 token（深色 白10% / 浅色 黑8%），
                //   语义正是"小型浮层的描边"，而 tooltip 就是浮在卡上的小浮层 —— 与 UfiToast 同一类。
                //   深色态它是**极低 alpha 的白色叠加**，浅色态叠在 cardBg 上呈浅灰发丝线，两边都"浅"。
                // · divider 虽然也是浅色语义，但它是**每套配色各写一个具体色值**的存储字段，
                //   深色预设里是 #333333 / #2A3A4E 这类深灰 —— 画在深色卡面上又是一道暗环，
                //   等于把要修的问题换个地方重演。（cardBorder 同族但只有 6~8%，配 shadow 后几乎看不见边。）
                // 宽度保持 1dp：alpha 已经很低，再压到 0.5dp 就只剩若有若无的一线，
                // 气泡与卡面（两者都是 cardBg）会分不出边界。层次仍主要靠下面那层 shadow(6dp)。
                // 未新增调色板字段，也未在调用点写死 Color.White / Color.Black。
                val tooltipBorder = palette.toastBorder

                Column(
                    modifier = Modifier
                        .zIndex(1f)
                        .offset(x = animatedLeftDp, y = displayTopDp)
                        .graphicsLayer {
                            alpha = tooltipAlpha
                            scaleX = tooltipScale
                            scaleY = tooltipScale
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                        }
                        .width(tooltipWidthDp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(6.dp, tooltipShape, clip = false)
                            .background(tooltipBg, tooltipShape)
                    .border(1.dp, tooltipBorder, tooltipShape)
                    // 2026-09-03：竖向内距 4dp → 6dp。少了「峰值/谷值」那行后气泡只剩 2~3 行，
                    // 4dp 上下留白配 9dp 左右留白会显得文字上下贴边（内容少时空隙比例更显眼）。
                    .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                timeFmt.format(Date(cPt.t)),
                                style = UfiTextStyles.monoCaption.copy(fontWeight = UfiWeight.Strong),
                                color = palette.accent,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                            allSeries.forEachIndexed { sIdx, s ->
                                val sPt = if (sIdx == 0) cPt else nearestPointByTime(s.points, cPt.t)
                                if (sIdx == 0 || sPt != null) {
                                    val targetValue = if (sIdx == 0) cPt.avg else (sPt?.avg ?: 0.0)
                                    val displayValue = if (sIdx == 0) formatWithUnit(targetValue) else (s.valueFormatter ?: { v -> "%.1f".format(v) })(targetValue)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Box(Modifier.size(6.dp).background(s.lineColor, UfiCardDefaults.pillShape))
                                        Text(
                                            s.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.textSecondary,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            displayValue,
                                            style = UfiTextStyles.monoNote.copy(fontWeight = UfiWeight.Strong),
                                            color = s.lineColor,
                                            maxLines = 1,
                                            textAlign = TextAlign.End
                                        )
                                    }
                                    // 2026-09-03：此处原有一行「峰值 / 谷值」（绑定 cPt.max / cPt.min，
                                    // 即服务端按 bucketMs 分桶后的 SQL MAX()/MIN()）。随 min/max 区间带一起删除：
                                    // 气泡里同时摆均值 + 桶内两个极值，三个数在 92dp 高的卡片上只会互相干扰，
                                    // 而曲线画的就是均值，极值既对不上曲线也无从定位是桶内哪一刻。
                                    // 现在 tooltip 只剩「时间 + 各 series 均值」，一行一个指标。
                                }
                        }
                    }
                }
                }
            }
        }

        // ── X 轴时间刻度（2026-09-04 重写：与曲线严格同源）──
        // 旧实现是 `Row(padding(start = yAxisWidth + yGap, end = yGap), SpaceBetween)` 里摆
        // 首点 / points[size/2] / 末点三个 Text。三处错：
        //   ① 行宽 ≠ 绘图区宽（右外距 yGap 对 plotEndPad，symmetricHorizontal=true 时差一个 yAxisWidth）；
        //   ② SpaceBetween 把标签**等距**摊开，与"按时间戳线性映射"的曲线无关；
        //   ③ 中间标签取下标中位，采样密度不均时它的真实时间并不在正中。
        // 现在：刻度时间按 chartAxisTickTimes 在时间轴上均分，每个标签用 chartXPx 摆到自己的 x，
        // plotLeft / plotWidth / tMinAll / tSpanAll / 左右外距全部与曲线同源。
        if (points.size >= 2) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                val rowWidth = maxWidth
                val tickStyle = labelStyle.copy(fontWeight = UfiWeight.Emphasis)
                // (标签文本, 左上角 x) —— x 由 chartXPx 算出后减去半个文本宽做居中；
                // 只对**文本框**做贴边夹取（首末标签不越出卡片），刻度本身的 x 不动。
                val ticks = remember(rowWidth, plotStartPad, plotEndPad, tMinAll, tSpanAll, axisPattern, tickStyle, density) {
                    with(density) {
                        val rowWidthPx = rowWidth.toPx()
                        val plotLeft = plotStartPad.toPx() + CHART_PLOT_INSET_PX
                        val plotWidth = (rowWidthPx - plotStartPad.toPx() - plotEndPad.toPx() -
                            2 * CHART_PLOT_INSET_PX).coerceAtLeast(1f)
                        // 同一 pattern 下各刻度文本等宽（HH:mm / MM/dd HH:mm 都是定长），测一个即可
                        val sampleWidth = textMeasurer.measure(
                            AnnotatedString(axisTimeFmt.format(Date(tMinAll + tSpanAll))),
                            style = tickStyle
                        ).size.width.toFloat()
                        val count = chartAxisTickCount(plotWidth, sampleWidth, gap = 8.dp.toPx())
                        chartAxisTickTimes(tMinAll, tSpanAll, count).map { t ->
                            val label = axisTimeFmt.format(Date(t))
                            val w = textMeasurer.measure(AnnotatedString(label), style = tickStyle).size.width
                            val left = (chartXPx(t, tMinAll, tSpanAll, plotLeft, plotWidth) - w / 2f)
                                .coerceIn(0f, (rowWidthPx - w).coerceAtLeast(0f))
                            label to left.roundToInt()
                        }
                    }
                }
                ticks.forEach { (label, left) ->
                    Text(
                        text = label,
                        style = tickStyle,
                        color = palette.textPrimary.copy(alpha = 0.7f),
                        maxLines = 1,
                        modifier = Modifier.offset { IntOffset(left, 0) }
                    )
                }
            }
        }
    }
}

// ── v18：多 series 紧凑图例 chip 行（标题下方，不压折线）──
// v22：改为 public，供调用方（卡片标题行）在标题同行靠右渲染。
// 2026-08-24：图例默认靠右对齐（与卡片标题行同行时 trailing 槽位视觉一致），调用方传 modifier 也可覆盖。
@Composable
fun SeriesLegendChips(
    allSeries: List<MonitorSeries>,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val fmt = { v: Double -> "%.1f".format(v) }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        allSeries.forEach { s ->
            val cur = s.points.lastOrNull()?.avg
            val curFmt = (s.valueFormatter ?: fmt)(cur ?: 0.0)
            val chipShape = UfiCardDefaults.subtleShape
            Row(
                modifier = Modifier
                    .background(s.lineColor.copy(alpha = 0.10f), chipShape)
                    .border(0.5.dp, s.lineColor.copy(alpha = 0.45f), chipShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).background(s.lineColor, UfiCardDefaults.pillShape))
                Text(
                    text = s.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    maxLines = 1
                )
                if (cur != null) {
                    Text(
                        text = curFmt,
                        style = UfiTextStyles.monoNote.copy(fontWeight = UfiWeight.Strong),
                        color = s.lineColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ── v17：平滑曲线绘制辅助（Catmull-Rom 转 cubic bezier）──
/**
 * tooltip 淡出期间冻结的选中态（选中下标 + 手指横向比例）。
 *
 * 故意**不是** MutableState：这两个字段在组合过程中被写、又在同一次组合里被读，
 * 若用可观察状态，Compose 会为此再排一轮重组 —— 拖动时每个触摸事件跑两轮组合，
 * 气泡位置与文字在两轮之间来回，正是「tooltip 抽搐」的来源之一。
 * 它们只在松手后的淡出动画期间被读，真正驱动重组的是 dragRatio，因此不需要可观察性。
 */
private class TooltipFreeze {
    var index: Int = 0
    var ratio: Float = 0.5f
}

/**
 * 预建好的曲线几何：draw 阶段只负责重放这些 Path / Offset，不再现搭。
 * 所有字段构建后即只读，可安全跨帧、跨重组复用。
 */
private class ChartGeometry(
    val fillLayers: List<Pair<Path, Brush>>,
    val shadowLayers: List<Pair<Path, Color>>,
    val strokeLayers: List<Pair<Path, Color>>,
    val singleDots: List<Pair<Offset, Color>>,
    val latestDots: List<Pair<Offset, Color>>,
    val thresholdLines: List<Triple<Float, Color, TextLayoutResult>>,
    val comparePath: Path?
)

/**
 * 跨重组存活的几何缓存（2026-08-26 第三轮）。
 *
 * `Modifier.drawWithCache` 只能保证「同一份构建块不因重绘而重跑」，但**每次重组**都会带来
 * 新的 lambda 实例 → 缓存作废 → 几何重建。监控页的重组诱因很多（拖动 tooltip、轮询推送、
 * isLoading 翻转、父级 state 变化），于是「刷新一次就整体重建」。
 * 这里按值比较 key，把「重组」与「几何真的变了」区分开。
 */
private class ChartGeometryCache {
    private var key: List<Any?>? = null
    private var geometry: ChartGeometry? = null

    fun getOrBuild(newKey: List<Any?>, build: () -> ChartGeometry): ChartGeometry {
        val cached = geometry
        if (cached != null && key == newKey) return cached
        return build().also {
            key = newKey
            geometry = it
        }
    }
}

private fun Path.cubicSmoothLine(points: List<Offset>) {    if (points.size < 2) return
    for (i in 0 until points.size - 1) {
        val p0 = points[(i - 1).coerceAtLeast(0)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[(i + 2).coerceIn(0, points.size - 1)]
        val cp1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val cp2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, p2.x, p2.y)
    }
}

private fun Path.cubicSmoothSegment(p0: Offset, p1: Offset, p2: Offset, p3: Offset) {
    val cp1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
    val cp2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
    cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, p2.x, p2.y)
}

// ==================== 断档判定（提到顶层以便单测；见 UfiChartGapTest） ====================

/**
 * 一条序列被判为「数据空洞」的最小间隔（毫秒）。返回 [Long.MAX_VALUE] 表示不断线。
 *
 * 服务端跳过空桶，所以「相邻两点的时间差」是判断中间有没有采集空洞的唯一线索。
 *
 * 为什么基准不能是桶宽（旧实现 `> bucketMs * 2.5` 的错处）：
 * 桶宽是客户端按「可见区间 / MAX_POINTS」算出来的**绘图分辨率**，与真实采集节奏无关。
 * 近 1 小时窗口算出的桶宽只有 15s，而采集/刷写间隔是 30~60s —— 于是每一对相邻点天然
 * 相隔 2~4 个桶宽，几乎每个间隔都被判成断档，整条曲线被切成一堆单点段，
 * 再配合当时"孤立段画圆点"的行为，就是用户看到的满屏「短点」。
 *
 * 现在的基准是这条序列**实际间距的中位数**：中位数就是它的真实采样节奏，与桶宽多细无关；
 * 又因为中位数抗离群，序列里少数几小时的长空洞不会把基准抬高。
 * 门槛取两者较大者：
 * - `medianDt * 3`：连着漏掉两个采样点才算断，容得下「某次采集晚了一拍」的抖动；
 * - `bucketMs * 2`：绝对下限，防止采样比桶还密时（点数少于目标点数时服务端原样返回原始行）
 *   中位间距远小于桶宽，导致判定比「相邻桶」还敏感。
 *
 * 逐序列各算一次：多指标叠加时各 series 的采集节奏可能不同，用同一个全局 bucketMs 卡所有
 * 序列本身就是误判来源，bucketMs 在这里只剩"下限参考"的角色。
 *
 * [bucketMs] <= 0（旧 core 不回该字段）时不断线，保持旧行为。
 */
internal fun chartGapLimit(points: List<UfiDownsampledPoint>, bucketMs: Long): Long {
    // 少于 3 个点时只有 0~1 个间距，无从谈"中位数"，也就无从判断哪个间距算异常
    if (bucketMs <= 0L || points.size < 3) return Long.MAX_VALUE
    val dts = LongArray(points.size - 1) { i -> points[i + 1].t - points[i].t }
    dts.sort()
    val medianDt = dts[dts.size / 2].coerceAtLeast(1L)
    return maxOf(bucketMs * 2L, medianDt * 3L)
}

/** 按 [chartGapLimit] 把序列切成若干连续段；每段内相邻点才连线/填充。 */
internal fun chartSegments(points: List<UfiDownsampledPoint>, bucketMs: Long): List<IntRange> {
    if (points.isEmpty()) return emptyList()
    val gapLimit = chartGapLimit(points, bucketMs)
    val out = ArrayList<IntRange>(4)
    var segStart = 0
    for (i in 1 until points.size) {
        if (points[i].t - points[i - 1].t > gapLimit) {
            out += segStart..(i - 1)
            segStart = i
        }
    }
    out += segStart..(points.size - 1)
    return out
}

// ── X 轴映射的唯一定义（2026-09-04：修「曲线终点落在错误的 X 轴时间上」）──
//
// 现象：末点是 12:30，曲线上确实有这个点，但它在 X 轴上大约落在 12:20 的位置。
// 根因是同一张图里存在**两套 x 坐标系**：
//   · 曲线：Canvas 所在的 Box 有 `padding(start = yAxisWidth + yGap,
//     end = if (symmetricHorizontal) yAxisWidth + yGap else yGap)`，再在画布内缩 3px，
//     然后按时间戳线性映射；
//   · 刻度：一个 `padding(start = yAxisWidth + yGap, end = yGap)` 的 Row + SpaceBetween。
// 于是 symmetricHorizontal=true（监控页就是 true）时，刻度行的右边界比绘图区右边界
// 整整宽出一个 yAxisWidth（≥32dp）；而 SpaceBetween 又把标签**等距**摊开、中间那个还取
// `points[size/2]`（下标中位而非时间中位）。三件事叠起来，标签所指的时间与曲线的几何位置
// 系统性错开，末点看着就"提前"了。
//
// 修法：把「时间 → x」抽成下面这组纯函数，曲线与刻度都只能走它，且共用同一份
// plotLeft / plotWidth / tMin / tSpan。任何一处想自己算 x，就会在 review 时暴露出来。

/** 绘图区在画布内的左右内缩（px）。曲线与刻度必须共用同一个值。 */
internal const val CHART_PLOT_INSET_PX = 3f

/** 时间戳 → 0..1 的横向比例。[tSpan] 必须 >= 1。 */
internal fun chartXFraction(t: Long, tMin: Long, tSpan: Long): Float =
    ((t - tMin).toFloat() / tSpan.toFloat()).coerceIn(0f, 1f)

/**
 * 时间戳 → 绝对 x（px）。
 *
 * [plotLeft] 是绘图区左边缘在**目标坐标系**里的 x，[plotWidth] 是绘图区宽度：
 * 画布里传 (CHART_PLOT_INSET_PX, width - 2*inset)，刻度行里传
 * (左外距 + inset, 同一个绘图区宽度)。两者算出来的 x 相差的只是那个左外距，
 * 而刻度行整行也按同一个外距摆放，故同一时间在两处落在同一根竖线上。
 */
internal fun chartXPx(t: Long, tMin: Long, tSpan: Long, plotLeft: Float, plotWidth: Float): Float =
    plotLeft + plotWidth * chartXFraction(t, tMin, tSpan)

/**
 * X 轴刻度时间：在 [tMin, tMin + tSpan] 上**按时间**均分成 [count] 个。
 *
 * 刻意不做"取整到整点/整十分"这类美化：刻度一旦被吸附到整点，它标的时间就不再等于
 * 它所在的 x 所代表的时间，又会回到本次要修的那个坑里。
 */
internal fun chartAxisTickTimes(tMin: Long, tSpan: Long, count: Int): List<Long> {
    val n = count.coerceAtLeast(2)
    return List(n) { i -> tMin + tSpan * i / (n - 1) }
}

/**
 * 按可用宽度决定画几个刻度：优先 5 个，挤不下退 3 个，再挤不下只画首末 2 个。
 *
 * [labelWidth] 传最宽刻度文本的实测宽度；相邻标签之间至少留 [gap]（px）。
 * 换成 5 个刻度后窄屏（含 "MM/dd HH:mm" 这种长格式）确实可能挤，所以这里按宽度退档，
 * 而不是无条件 5 个。
 */
internal fun chartAxisTickCount(plotWidth: Float, labelWidth: Float, gap: Float): Int {
    if (labelWidth <= 0f) return 3
    fun fits(n: Int) = n * labelWidth + (n - 1) * gap <= plotWidth
    return when {
        fits(5) -> 5
        fits(3) -> 3
        else -> 2
    }
}

// ==================== P3a 图表阈值判定辅助（UI 层自包含，不依赖 app/data 模块） ====================

/** 段级阈值等级：语义与 data 层 MonitorMetricType.evaluate 一致，但由 UI 层用 lowerIsWorse 自行判定 */
private enum class ChartThresholdLevel { NORMAL, WARN, ALERT }

/** 单值阈值判定：alert 优先 warn；lowerIsWorse=true 时方向取反（值越小越差） */
private fun chartThresholdLevelOf(
    value: Double,
    warn: Double?,
    alert: Double?,
    lowerIsWorse: Boolean
): ChartThresholdLevel {
    if (alert != null && chartCrossed(value, alert, lowerIsWorse)) return ChartThresholdLevel.ALERT
    if (warn != null && chartCrossed(value, warn, lowerIsWorse)) return ChartThresholdLevel.WARN
    return ChartThresholdLevel.NORMAL
}

private fun chartCrossed(value: Double, threshold: Double, lowerIsWorse: Boolean): Boolean =
    if (lowerIsWorse) value <= threshold else value >= threshold

/** 段级超限：取段两端点中更严重的一个（ALERT > WARN > NORMAL） */
private fun chartSegmentLevel(
    v1: Double,
    v2: Double,
    warn: Double?,
    alert: Double?,
    lowerIsWorse: Boolean
): ChartThresholdLevel {
    val l1 = chartThresholdLevelOf(v1, warn, alert, lowerIsWorse)
    val l2 = chartThresholdLevelOf(v2, warn, alert, lowerIsWorse)
    return if (l1.ordinal >= l2.ordinal) l1 else l2
}

/**
 * 2026-09-03：按时间找最接近 targetT 的**下标**（选中判定用）。
 * X 轴改成按时间比例映射后，选中不能再用 `ratio × (size-1)` 反推下标 ——
 * 有空洞时下标与横向位置不再线性对应，tooltip 会指到别的时刻上。
 */
private fun nearestIndexByTime(points: List<UfiDownsampledPoint>, targetT: Long): Int {
    if (points.isEmpty()) return -1
    var best = 0
    var bestDist = Long.MAX_VALUE
    for (i in points.indices) {
        val t = points[i].t
        val d = if (t >= targetT) t - targetT else targetT - t
        if (d < bestDist) {
            bestDist = d
            best = i
        }
    }
    return best
}

/** P7a：在 series 中按时间找最接近 targetT 的点（多 series tooltip 用；X 轴时间以主 series 为准） */
private fun nearestPointByTime(points: List<UfiDownsampledPoint>, targetT: Long): UfiDownsampledPoint? {
    if (points.isEmpty()) return null
    var best = points[0]
    var bestDist = Long.MAX_VALUE
    for (p in points) {
        val d = if (p.t >= targetT) p.t - targetT else targetT - p.t
        if (d < bestDist) {
            bestDist = d
            best = p
        }
    }
    return best
}

// ── v21：Y 轴“整齐刻度”算法（Heckbert nice numbers）──
// 让刻度值变成干净的 round 数（如 0/20/40/60/80/100 或 0.0/0.5/1.0），
// 间隔均匀、文字宽度一致，消除“有的大有的小、间隔不统一”。
private fun niceNum(range: Double, round: Boolean): Double {
    val exp = kotlin.math.floor(kotlin.math.ln(range) / kotlin.math.ln(10.0))
    val f = range / 10.0.pow(exp)
    val nf = if (round) {
        when {
            f < 1.5 -> 1.0
            f < 3.0 -> 2.0
            f < 7.0 -> 5.0
            else -> 10.0
        }
    } else {
        when {
            f <= 1.0 -> 1.0
            f <= 2.0 -> 2.0
            f <= 5.0 -> 5.0
            else -> 10.0
        }
    }
    return nf * 10.0.pow(exp)
}

private data class NiceAxis(val min: Float, val max: Float, val step: Float)

private fun computeNiceAxis(rawMin: Float, rawMax: Float): NiceAxis {
    if (rawMax - rawMin < 1e-6f) {
        val m = if (rawMax == 0f) 1f else kotlin.math.abs(rawMax) * 0.1f
        // 非负指标（流量/CPU/内存/电量/温度等）的恒定值不应出现负刻度
        if (rawMin >= 0f) {
            val max = rawMax + m
            return NiceAxis(0f, max.toFloat(), (max / 4f).toFloat())
        }
        return NiceAxis((rawMin - m), (rawMax + m), (2f * m))
    }
    val range = niceNum((rawMax - rawMin).toDouble(), round = false)
    val step = niceNum(range / 4.0, round = true)
    var niceMin = kotlin.math.floor(rawMin / step) * step
    val niceMax = kotlin.math.ceil(rawMax / step) * step
    // 非负指标下边界至少为 0，避免流量 B/s 等出现负刻度
    if (rawMin >= 0f && niceMin < 0) {
        niceMin = 0.0
    }
    return NiceAxis(niceMin.toFloat(), niceMax.toFloat(), step.toFloat())
}

/** 按小数位四舍五入后格式化刻度值，避免出现 39.999999 这类浮点误差。 */
private fun formatTick(v: Double, decimals: Int): String {
    val scale = 10.0.pow(decimals.toDouble())
    val rounded = kotlin.math.round(v * scale) / scale
    return if (decimals == 0) "%.0f".format(rounded) else "%.${decimals}f".format(rounded)
}
