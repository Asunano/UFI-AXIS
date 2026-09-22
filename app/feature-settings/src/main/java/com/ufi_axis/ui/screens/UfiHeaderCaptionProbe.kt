package com.ufi_axis.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufi_axis.data.model.PoetryResponse
import com.ufi_axis.data.model.WeatherNowResponse
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiHeaderCaptionSlot
import com.ufi_axis.ui.components.common.UfiWeatherSlot
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 标题栏挂件的内容探针（2026-09-18）：**天气在右侧、今日诗词在标题下方小字**。
 *
 * Activity 级挂一次，分别注入 [UfiWeatherSlot] 与 [UfiHeaderCaptionSlot]，页壳只负责渲染。
 *
 * ## 为什么两个功能一个探针
 * 它们的取数节奏、开关来源、生命周期完全同构（都挂 core 的 config + 10 分钟节流），
 * 拆成两个探针只会让 MainActivity 多挂一层、并且两处重复同样的 `loadConfig` 时序。
 * 位置的分配（谁在右、谁在下）写在各自的 Slot KDoc 里。
 *
 * ## 刷新时机
 * 事件驱动 + 客户端节流，没有常驻定时器：挂载时各拉一次配置，开关从关变开时补一次数据，
 * 点一下挂件手动刷新（天气会真的重取，诗词受上游 10 分钟缓存约束可能还是同一句）。
 */
@Composable
fun UfiHeaderCaptionProbe(viewModel: MainViewModel) {
    val weather by viewModel.weather.state.collectAsState()
    val poetry by viewModel.poetry.state.collectAsState()

    // 天气详情弹窗状态
    var weatherDialogVisible by remember { mutableStateOf(false) }
    var weatherDialogNow by remember { mutableStateOf<WeatherNowResponse?>(null) }

    // 配置与首轮取数已由 MainViewModel.init 预加载（2026-09-19：布局可见前就完成，
    // 避免顶栏先空一拍再补上）。这里只负责"开关后来被打开"时补数据。
    LaunchedEffect(weather.config.enabled) {
        if (weather.config.enabled) viewModel.weather.refresh()
    }
    LaunchedEffect(poetry.config.enabled) {
        if (poetry.config.enabled) viewModel.poetry.refresh()
    }

    // ── 右侧：天气（点击打开弹窗详情）──
    LaunchedEffect(weather.displayable, weather.now) {
        val now = weather.now?.takeIf { weather.displayable }
        UfiWeatherSlot.active.value = now != null
        UfiWeatherSlot.content.value = if (now == null) null else {
            {
                UfiHeaderWeather(
                    now = now,
                    onClick = {
                        weatherDialogNow = now
                        weatherDialogVisible = true
                    }
                )
            }
        }
    }

    // ── 标题下方小字：今日诗词 ──
    LaunchedEffect(poetry.displayable, poetry.poem, poetry.config.show_origin) {
        val poem = poetry.poem?.takeIf { poetry.displayable }
        UfiHeaderCaptionSlot.active.value = poem != null
        UfiHeaderCaptionSlot.content.value = if (poem == null) null else {
            {
                UfiHeaderPoem(
                    poem = poem,
                    showOrigin = poetry.config.show_origin,
                    onClick = { viewModel.poetry.refresh(force = true) }
                )
            }
        }
    }

    // 离开时清干净，否则 Activity 重建后旧 lambda 还挂在单例上
    DisposableEffect(Unit) {
        onDispose {
            UfiWeatherSlot.active.value = false
            UfiWeatherSlot.content.value = null
            UfiHeaderCaptionSlot.active.value = false
            UfiHeaderCaptionSlot.content.value = null
        }
    }

    // ── 天气详情弹窗（无按钮，右上角关闭） ──
    val dialogNow = weatherDialogNow
    if (dialogNow != null) {
        WeatherDetailDialog(
            visible = weatherDialogVisible,
            now = dialogNow,
            onDismiss = { weatherDialogVisible = false }
        )
    }
}

/**
 * 天气详情弹窗：无按钮，右上角 × 关闭。
 *
 * 布局分三段：
 * 1. 2×2 读数卡片（体感 / 温区、湿度 / 风速）—— 每格自带底色与圆角，
 *    纯文字两列时相邻两格的"标签—读数"会连成一条线看不出分界；
 * 2. 日出日落合并成一张通栏卡（两者语义成对，分成两行是把一条信息拆开）；
 * 3. 24 小时温度曲线（可横向滑动 + 点选查看任意时刻）。
 */
@Composable
private fun WeatherDetailDialog(
    visible: Boolean,
    now: WeatherNowResponse,
    onDismiss: () -> Unit
) {
    val unit = if (now.unit == "fahrenheit") "℉" else "℃"
    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "${now.description}  ${"%.0f".format(now.temperature)}$unit",
        showCloseButton = true
    ) {
        UfiDialogBody {
            // 2×2 卡片网格
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WeatherStatCard("体感", "${"%.0f".format(now.apparent_temperature)}$unit", Modifier.weight(1f))
                WeatherStatCard("今日", "${now.temp_min.toInt()}° / ${now.temp_max.toInt()}°", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WeatherStatCard("湿度", "${now.humidity}%", Modifier.weight(1f))
                WeatherStatCard("风速", "${"%.1f".format(now.wind_speed)} km/h", Modifier.weight(1f))
            }
            // 日出日落通栏卡
            if (now.sunrise.isNotBlank() || now.sunset.isNotBlank()) {
                WeatherStatCard(
                    label = "日出 / 日落",
                    value = listOf(now.sunrise, now.sunset)
                        .filter { it.isNotBlank() }
                        .joinToString("  ·  ") { it.substringAfter('T') }
                )
            }
            // 24 小时温度曲线
            if (now.hourly_temperatures.size >= 2) {
                HourlyTemperatureChart(
                    temps = now.hourly_temperatures,
                    times = now.hourly_times,
                    unit = unit
                )
            }
        }
    }
}

/**
 * 24 小时温度折线图，**可横向滑动 + 点选**。
 *
 * ## 为什么要滑动
 * 24 个点挤在 ~280dp 的弹窗宽度里，点距只有 12dp，既看不出趋势也点不准。
 * 改成每小时固定 [HOUR_SLOT_WIDTH]，总宽超出弹窗后由 `horizontalScroll` 承载 ——
 * 视口里始终是"约 8 小时"的可读密度，要看后面的滑过去。
 *
 * ## 交互
 * - 点/拖任意位置 → 吸附到最近的小时，显示该时刻的竖线 + 气泡读数；
 * - 未选中时默认高亮 index 0（core 用 `forecast_hours=24` 请求，所以 `temps[0]` 就是当前小时）。
 *
 * 画法仍是 Canvas 手绘：24 点一条 Path，引图表库为它多几百 KB 不值得。
 */
@Composable
private fun HourlyTemperatureChart(
    temps: List<Double>,
    times: List<String>,
    unit: String
) {
    val palette = LocalResolvedPalette.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scrollState = rememberScrollState()

    // null = 没点过，用 index 0（当前小时）
    var selected by remember { mutableStateOf<Int?>(null) }
    val activeIdx = (selected ?: 0).coerceIn(0, temps.lastIndex)

    val minTemp = temps.min()
    val maxTemp = temps.max()
    // 全天恒温时 span=0，除法会得到 NaN；给 1 度的假跨度让曲线落在中线上
    val span = (maxTemp - minTemp).takeIf { it > 0.01 } ?: 1.0

    val lineColor = palette.accent
    val fadedText = palette.textPrimary.copy(alpha = 0.45f)
    val gridColor = palette.textPrimary.copy(alpha = 0.08f)
    val labelStyle = TextStyle(fontSize = 9.sp, color = fadedText)
    val valueStyle = TextStyle(fontSize = 10.sp, color = palette.textPrimary)
    val yAxisStyle = TextStyle(fontSize = 8.sp, color = fadedText)

    // Y 轴刻度文字：预量宽度给 Canvas 留左边距
    val maxLabel = "${maxTemp.toInt()}$unit"
    val minLabel = "${minTemp.toInt()}$unit"
    val yAxisWidth = with(density) {
        val w1 = measurer.measure(maxLabel, yAxisStyle).size.width
        val w2 = measurer.measure(minLabel, yAxisStyle).size.width
        maxOf(w1, w2).toDp() + 4.dp  // 4dp 间距
    }

    // 总宽 = 每小时一格。首尾各留半格，保证端点的圆点与标签不被裁掉。
    val chartWidth = HOUR_SLOT_WIDTH * temps.size + yAxisWidth
    val stepXPx = with(density) { HOUR_SLOT_WIDTH.toPx() }
    val halfSlotPx = stepXPx / 2f

    Column {
        // 标题行：左侧说明，右侧显示当前选中时刻的读数（点选后即时更新）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "未来 24 小时",
                style = UfiTextStyles.headerCaption,
                color = fadedText,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = buildString {
                    append(times.getOrNull(activeIdx)?.substringAfter('T')?.take(5) ?: "")
                    append("  ")
                    append("%.1f".format(temps[activeIdx]))
                    append(unit)
                },
                style = UfiTextStyles.headerSubtitle,
                color = palette.accent,
                maxLines = 1,
                softWrap = false
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(modifier = Modifier.horizontalScroll(scrollState)) {
            Canvas(
                modifier = Modifier
                    .width(chartWidth)
                    .height(96.dp)
                    // 点选：把 x 换算成最近的小时下标。用 detectTapGestures 而不是 clickable，
                    // 因为需要落点坐标；它跑在 Main pass，不会和外层 horizontalScroll 抢手势
                    // （滑动是 drag，点选是 tap，两者不冲突）。
                    .pointerInput(temps.size) {
                        val yAxisOffPx = yAxisWidth.toPx()
                        detectTapGestures { offset ->
                            selected = ((offset.x - yAxisOffPx - halfSlotPx) / stepXPx)
                                .toInt()
                                .coerceIn(0, temps.lastIndex)
                        }
                    }
            ) {
                val yAxisPx = with(density) { yAxisWidth.toPx() }
                val topPad = 16.dp.toPx()
                val bottomPad = 22.dp.toPx()
                val plotH = size.height - topPad - bottomPad
                val baseY = topPad + plotH

                fun xOf(i: Int) = yAxisPx + halfSlotPx + stepXPx * i
                fun yOf(t: Double) = topPad + plotH * (1f - ((t - minTemp) / span).toFloat())

                // Y 轴刻度：最高温（顶）和最低温（底）
                val maxLayout = measurer.measure(maxLabel, yAxisStyle)
                val minLayout = measurer.measure(minLabel, yAxisStyle)
                drawText(maxLayout, topLeft = Offset(0f, topPad - maxLayout.size.height / 2f))
                drawText(minLayout, topLeft = Offset(0f, baseY - minLayout.size.height / 2f))

                // 基线：最低温所在高度，给曲线一个视觉参照
                drawLine(
                    color = gridColor,
                    start = Offset(yAxisPx, baseY),
                    end = Offset(size.width, baseY),
                    strokeWidth = 1f
                )

                // 折线
                val path = Path().apply {
                    temps.forEachIndexed { i, t ->
                        if (i == 0) moveTo(xOf(i), yOf(t)) else lineTo(xOf(i), yOf(t))
                    }
                }
                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )

                // 每小时一个空心小点：滑动时能数出格子，也让点选有可见落点
                temps.forEachIndexed { i, t ->
                    drawCircle(
                        color = lineColor.copy(alpha = 0.35f),
                        radius = 1.5.dp.toPx(),
                        center = Offset(xOf(i), yOf(t))
                    )
                }

                // 选中时刻：竖虚线 + 实心点（白心）
                val selX = xOf(activeIdx)
                val selY = yOf(temps[activeIdx])
                drawLine(
                    color = lineColor.copy(alpha = 0.4f),
                    start = Offset(selX, selY),
                    end = Offset(selX, baseY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                )
                drawCircle(color = lineColor, radius = 4.dp.toPx(), center = Offset(selX, selY))
                drawCircle(color = palette.cardBg, radius = 1.8.dp.toPx(), center = Offset(selX, selY))

                // 选中点上方的数值
                val selLayout = measurer.measure("${temps[activeIdx].toInt()}$unit", valueStyle)
                drawText(
                    selLayout,
                    topLeft = Offset(
                        (selX - selLayout.size.width / 2f).coerceIn(0f, size.width - selLayout.size.width),
                        (selY - selLayout.size.height - 5.dp.toPx()).coerceAtLeast(0f)
                    )
                )

                // 底部时刻标签：每 3 小时一个，避免相邻标签挤在一起
                temps.indices.step(LABEL_EVERY_HOURS).forEach { i ->
                    val hour = times.getOrNull(i)?.substringAfter('T')?.take(5) ?: return@forEach
                    val layout = measurer.measure(hour, labelStyle)
                    drawText(
                        layout,
                        topLeft = Offset(
                            xOf(i) - layout.size.width / 2f,
                            size.height - layout.size.height
                        )
                    )
                }
            }
        }
    }
}

/** 每小时占的横向宽度。视口约 280dp → 一屏看到 ~8 小时，趋势看得清、点得准。 */
private val HOUR_SLOT_WIDTH = 34.dp

/** 底部时刻标签的间隔（小时）。每格 34dp，3 格 = 102dp，放得下 `05:00` 不会重叠。 */
private const val LABEL_EVERY_HOURS = 3

/**
 * 一格读数卡：上方小字标签、下方读数，带底色与圆角。
 *
 * 用卡片而不是"标签左 — 读数右"的一行：2×2 排布时两列之间没有分隔，
 * 纯文字会让左格的读数和右格的标签视觉上连成一条，分不清谁属于谁。
 */
@Composable
private fun WeatherStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(UfiCardDefaults.hairlineCornerRadius))
            .background(palette.textPrimary.copy(alpha = 0.04f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = UfiTextStyles.headerCaption,
            color = palette.textPrimary.copy(alpha = 0.45f),
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = UfiTextStyles.headerSubtitle,
            color = palette.textPrimary.copy(alpha = 0.85f),
            maxLines = 1,
            softWrap = false
        )
    }
}


/**
 * 右侧天气：两行右对齐 + 图标。
 *
 * ```
 *              24℃ 多云  [图标]
 *        北京 · 18°/28° · 62%
 * ```
 * 第一行是"现在什么天"，第二行是"在哪、今天什么范围、多潮"。右侧空间比标题下方那行宽裕，
 * 所以这里给得比之前的单行 chip 更全。
 */
@Composable
private fun UfiHeaderWeather(now: WeatherNowResponse, onClick: () -> Unit) {
    val palette = LocalResolvedPalette.current
    val unit = if (now.unit == "fahrenheit") "℉" else "℃"

    /*
     * 三行的内容先算出来：行数决定纵向排布方式（见下面 verticalArrangement）。
     * 算在 Column 外面是因为 Arrangement 是 Column 的入参，不能在它的 content 里再回头改。
     */
    val region = now.city.substringBefore(" · ").trim()
    val detail = weatherDetail(now)
    val lineCount = 1 + (if (region.isNotBlank()) 1 else 0) + (if (detail.isNotBlank()) 1 else 0)

    Row(
        modifier = Modifier
            // 撑满页壳给右侧插槽的高度 —— 那个高度是左侧"标题 + 诗句"反推出来的
            // （页壳用 Row + height(IntrinsicSize.Min) 做的，见 UfiHeader 里那段说明）
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        // 2026-09-21：去掉了原来的 padding(horizontal = 6.dp, vertical = 2.dp)。
        //
        // 横向那 6dp：本挂件**没有底色**，clip 与 ripple 都不可见，所以它不是"块内留白"而是
        // 实打实的额外外边距 —— 文字右边缘落在 16(HeaderPaddingH) + 6 = 22dp，
        // 而左侧标题从 16dp 起排，左右就差了 6dp。
        //
        // 纵向那 2dp：它让挂件内容的上下沿各比左侧标题列内缩 2dp，"顶底对齐"就做不到。
        // 文字不会贴到标题栏边缘 —— 标题栏自己有 HEADER_PADDING_TOP/BOTTOM_NOW_PLAYING。
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(
            // 撑满行高，SpaceBetween 才有余量可分
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.End,
            /*
             * 三行齐全时 **SpaceBetween**：首行贴顶、末行贴底，与左侧"标题顶沿 → 诗句底沿"
             * 占据同一段垂直范围。
             *
             * 缺行时退回 **Bottom**：两行用 SpaceBetween 会被拉到一顶一底、中间空出一大块，
             * 看着像漏了内容。贴底则保证末行底沿仍与诗句底沿齐，顶部留白是自然的。
             */
            verticalArrangement = if (lineCount >= 3) {
                Arrangement.SpaceBetween
            } else {
                Arrangement.Bottom
            }
        ) {
            AnimatedContent(
                targetState = "${now.temperature.toInt()}$unit ${now.description}".trim(),
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "header-weather-line1"
            ) { text ->
                Text(
                    text = text,
                    // lineHeight 必须显式给：这些样式都是 Typography.bodyLarge.copy(fontSize = …)，
                    // 而 bodyLarge 的 lineHeight 是**固定 24sp** —— 只改字号不改行高的话，
                    // 12sp 的字也占 24dp 一行，三行加起来 72dp，比左侧标题列高出一大截
                    // （2026-09-21 实测：右侧 76dp vs 左侧 51dp）。见 WEATHER_LINE*_HEIGHT。
                    style = UfiTextStyles.bodyLead.copy(lineHeight = WEATHER_LINE1_HEIGHT),
                    // 2026-09-21：由 alpha 0.75 改为**满色**。这一行是挂件的主信息
                    // （现在几度、什么天），压到 75% 之后在浅色配色下发灰、读起来像次要说明。
                    // 三行的层级改由下面两行退让来表达：满色 → 0.5 → 0.4。
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
            }
            /*
             * 第二行：地区名。2026-09-21 从第三行（`weatherDetail` 的第一段）拆出来单独成行。
             *
             * 原来三类信息挤在一行：`北京 · 18°/28° · 62%`。地区名长一点（"乌鲁木齐市"）
             * 就会把温区与湿度挤成省略号，而那两项恰好是这个挂件最该显示的东西。
             * 拆开之后每行一种语义：现在几度什么天 / 在哪 / 今日区间与湿度。
             *
             * 地区名为空时整行不渲染（不留空白行）—— 定位失败、或后端没回 city 都属于这种。
             */
            if (region.isNotBlank()) {
                AnimatedContent(
                    targetState = region,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label = "header-weather-region"
                ) { text ->
                    Text(
                        text = text,
                        style = UfiTextStyles.headerSubtitle.copy(
                            lineHeight = WEATHER_LINE2_HEIGHT
                        ),
                        color = palette.textPrimary.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End
                    )
                }
            }
            // 第三行：今日区间与湿度。全都缺失时整行不渲染 —— 渲染一个空 Text 仍会占一行高度，
            // 挂件会比左侧标题列莫名高出一行。
            if (detail.isNotBlank()) {
                AnimatedContent(
                    targetState = detail,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label = "header-weather-line2"
                ) { text ->
                    Text(
                        text = text,
                        // 字号再降一档（12 → 11sp）：三行要压进左侧标题列的高度，
                        // 而这一行是三行里最次要的（"顺带看一眼"的区间与湿度）。
                        style = UfiTextStyles.headerCaption.copy(
                            fontSize = 11.sp,
                            lineHeight = WEATHER_LINE3_HEIGHT
                        ),
                        color = palette.textPrimary.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End
                    )
                }
            }
        }
        Icon(
            imageVector = weatherIcon(now),
            contentDescription = now.description,
            tint = palette.accent,
            modifier = Modifier.size(34.dp)
        )
    }
}

/*
 * 天气挂件三行的行高（2026-09-21）。
 *
 * 为什么要写死：这三行的样式都源自 `Typography.bodyLarge`，而它的 lineHeight 是**固定 24sp**，
 * `copy(fontSize = …)` 不会跟着缩 —— 于是 11sp 的字也占 24dp 一行。三行 72dp + 上下 padding
 * 让挂件比左侧"标题 + 诗句"（约 51dp）高出 25dp，反过来把整条标题栏撑高、标题被垂直居中浮空。
 *
 * 三个值加起来 = 20 + 17 + 14 = 51dp，正好对上左侧标题列：
 * headerTitle(lineHeight 24) + caption 顶部 3dp + 诗句(lineHeight 24) = 51dp。
 * 改左侧任一字号或那 3dp 时，这三个数要一起重算。
 */
private val WEATHER_LINE1_HEIGHT = 20.sp
private val WEATHER_LINE2_HEIGHT = 17.sp
private val WEATHER_LINE3_HEIGHT = 14.sp


/**
 * 第三行：`18°/28° · 62%`。缺的那段自动省略，不留孤零零的分隔点。
 *
 * 2026-09-21 起**不含地区名** —— 它被提到第二行单独一行（见 [UfiHeaderWeather]）。
 * 全部缺失时回空串，那一行会渲染成一个零宽的 Text（不占额外高度，因为 Column 是贴底排的）。
 */
private fun weatherDetail(now: WeatherNowResponse): String = listOfNotNull(
    if (now.temp_max != 0.0 || now.temp_min != 0.0) {
        "${now.temp_min.toInt()}°/${now.temp_max.toInt()}°"
    } else null,
    now.humidity.takeIf { it > 0 }?.let { "$it%" }
).joinToString(" · ")

/**
 * 标题下方小字：只显示诗句，不显示作者。
 *
 * 长度由 [clampPoem] 限制：按句读点切，取排得下的完整句子。
 */
@Composable
private fun UfiHeaderPoem(poem: PoetryResponse, showOrigin: Boolean, onClick: () -> Unit) {
    val palette = LocalResolvedPalette.current
    val line = clampPoem(poem.content, POEM_BUDGET)

    AnimatedContent(
        targetState = line,
        transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(240)) },
        label = "header-poem",
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            // 2026-09-20：横向内边距去掉（原来 4dp）。诗句与标题同在一个 Column 里，
            // 多这 4dp 就等于诗句比标题右移 4dp —— 左侧两行的起笔对不齐。
            // 竖向那 1dp 无关对齐，留着给点击态一点余量。
            .padding(vertical = 1.dp)
    ) { value ->
        Text(
            text = value,
            style = UfiTextStyles.headerCaption,
            color = palette.textPrimary.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 顶栏一行能舒服放下的诗句字数。中文按字算，超出就按句读点裁。 */
private const val POEM_BUDGET = 22
private const val POEM_BUDGET_WITH_ORIGIN = 16

/**
 * 把诗句裁到 [budget] 字以内，**优先在句读点处断开**。
 *
 * jinrishici 返回的 `content` 常常是整联甚至整段，例如
 * 「忆对中秋丹桂丛，花也杯中，月也杯中。」共 18 字 —— 一行放不下时：
 * 1. 逐句累加（按 `，。；！？、` 切），取能放下的最长前缀，末尾补 `…` 表示还有后文；
 * 2. 一句都放不下（长短句里偶有单句 20+ 字）才退化为按字硬截。
 *
 * 末尾的句号会去掉：截断后留个句号会让人以为这就是全部。
 */
internal fun clampPoem(content: String, budget: Int = POEM_BUDGET): String {
    val raw = content.trim()
    if (raw.length <= budget) return raw.removeSuffix("。")

    val parts = raw.split(*POEM_BREAKS).filter { it.isNotBlank() }
    val sb = StringBuilder()
    for (part in parts) {
        // +1 预留分隔用的逗号
        if (sb.isNotEmpty() && sb.length + 1 + part.length > budget) break
        if (sb.isNotEmpty()) sb.append('，')
        sb.append(part)
        if (sb.length >= budget) break
    }
    if (sb.isEmpty()) return raw.take(budget - 1) + "…"
    return "$sb…"
}

/** 中文句读点：用来把整联切成可独立成立的短句。 */
private val POEM_BREAKS = charArrayOf('，', '。', '；', '！', '？', '、', ',', '.', ';', '!', '?')

/**
 * WMO 代码 → 图标。分档比 core 的文案粗（只有 6 类）：图标要一眼可辨，
 * 「小雨/中雨」用同一把伞更合适，具体强度看文字。
 */
private fun weatherIcon(now: WeatherNowResponse): ImageVector = when (now.weather_code) {
    0, 1 -> if (now.is_day) Icons.Default.WbSunny else Icons.Default.NightsStay
    2 -> Icons.Default.WbCloudy
    3, 45, 48 -> Icons.Default.Cloud
    in 51..57, in 61..67, in 80..82 -> Icons.Default.Umbrella
    in 71..77, 85, 86 -> Icons.Default.AcUnit
    95, 96, 99 -> Icons.Default.Grain
    else -> Icons.Default.Cloud
}
