package com.ufi_axis.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

import com.ufi_axis.data.model.TrafficUsageResponse
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.TRAFFIC_HISTORY_RANGES

/**
 * 「流量历史」弹窗（工具 → 流量管理 → 流量历史）。
 *
 * 2026-09-15 从独立页改成弹窗：这一屏的内容就是「分段控件 + 一张图 + 一行合计」，
 * 撑不起一整页；而它本来就是流量管理页的下钻动作，弹窗看完直接回到限额设置那一屏，
 * 不必再走一次返回导航。同期 web 端做了同样的改动（`TrafficHistoryModal.vue`）。
 * 原来的 `Routes.DETAIL_TRAFFIC_HISTORY` 路由与 `TrafficHistoryScreen` 已一并删除。
 *
 * 数据全部由 core `/api/traffic/usage` 给出（区间文案、X 轴刻度文案、浮层文案、翻页锚点），
 * 本弹窗**不自己算任何日期文案、不补桶、不做日历运算** —— 月长度 / 闰年 / DST 都在 core 侧
 * 用 `java.time` 处理过一遍了，客户端再算一遍就是等着两边算出不同结果。
 *
 * 另外两条口径（2026-09-15）：
 *  · **布局固定、数据后填**：加载中不换分支、不叠转圈，切区间/翻页只重播柱子上升动画
 *  · 图内横滑：没有浮层时翻页，有浮层时在柱之间擦扫（判定见 [UfiBarChart]）
 */
@Composable
fun TrafficHistoryDialog(
    visible: Boolean,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "流量历史",
        icon = rememberVectorPainter(Icons.Default.BarChart)
    ) {
        TrafficHistoryDialogBody(viewModel)
    }
}

@Composable
private fun TrafficHistoryDialogBody(viewModel: MainViewModel) {
    val palette = LocalResolvedPalette.current
    val history by viewModel.tools.trafficHistoryState.collectAsState()
    val data = history.data

    /**
     * **展示用**的数据（stale-while-revalidate，2026-09-16）。
     *
     * 切到一个还没打开过的区间时 `history.data` 是 null（四个区间各存一份），
     * 上一版会先掉成"—" + 空图、等接口回来再填一次 —— 用户看到的是**两跳**，
     * 那正是"没打开过的区间就会重组"的观感来源。现在没数据时沿用上一次成功的那份，
     * 新数据到达后整块一次性换过去（文案滚动 + 柱子重新长起来）。
     *
     * 持有者是普通字段而不是 `MutableState`：只在组合期读写、不建立 snapshot 依赖，
     * 免掉一次多余的重组（也避免用 LaunchedEffect 回写导致晚一帧）。
     * 与之配套的是 `viewModel.tools.prefetchTrafficHistory()`，让这个 stale 窗口
     * 在大多数情况下根本不会出现。
     *
     * ⚠ 只有**展示**走 stale：翻页按钮的可用性仍然看真实 `data`，
     * 否则 stale 期间按钮点了什么都不会发生 —— 那就是"假按钮"。
     */
    val shownHolder = remember { TrafficHistoryShownHolder() }
    if (data != null) shownHolder.value = data
    val shown = data ?: shownHolder.value

    /**
     * 选中的柱（浮层）。**状态放在这里而不是图表组件里**：需求是"点图表以外的地方也能收起"，
     * 而那个手势区域比图表大。换区间/翻页时按 key 自动清空 —— 桶变了，
     * 旧的下标指向的已经是另一天。
     */
    var selectedBar by remember(history.range, history.anchor) { mutableStateOf<Int?>(null) }

    // 弹窗内容只在打开时组合，所以这就是"打开才取数"。不带 force：
    // 新鲜窗口内反复开关不会反复发请求（切区间 / 翻页时才 force 重拉）。
    // 顺带预热另外三个区间的当前段，切过去时就不用等（详见 prefetchTrafficHistory）。
    LaunchedEffect(Unit) {
        viewModel.tools.loadTrafficHistory()
        viewModel.tools.prefetchTrafficHistory()
    }

    /**
     * 浮层开着时，图表**以外**的任何一次触摸都收起它，并**吃掉这一下**。
     *
     * 2026-09-16：上一版是在整块 Column 上挂 `detectTapGestures`，靠"子节点先消费"来避让
     * 图表。但那套只对"不消费触摸的子节点"成立 —— 分段控件、翻页按钮都会消费自己的点击，
     * 于是点它们时浮层不收；用户的原话是"还是需要点击图表内才能关闭浮层"。
     * 现在改成主动拦截：在 Initial 之后的 Main 通道拿到 down 就消费掉并清空选中，
     * 且这个 modifier **只加在图表以外的两组内容上** —— 图表自己要保留点柱子切换、
     * 按住横向擦扫、以及空/有数据时的横滑翻页，不能被这层吃掉。
     *
     * 代价是"第一下只用来收浮层"（不会顺带按到按钮）—— 这与全站弹窗/菜单的
     * 点外部先关闭是同一套预期。浮层没开时这层不存在，零额外手势节点。
     */
    val dismissOnTouch = if (selectedBar != null) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                selectedBar = null
            }
        }
    } else {
        Modifier
    }

    // 间距统一到 UfiDialogBody（12dp）：body 的三个子级（图上 / 图 / 图下）之间由外壳排 12dp，
    // 每个子级**内部**的紧凑间距（同一组里的标题↔控件、分隔线上下）保留原值。
    UfiDialogBody {
        // ── 图表上方（错误条 + 区间文案 + 分段控件）──
        Column(modifier = Modifier.fillMaxWidth().then(dismissOnTouch)) {
            history.errorMessage?.let { err ->
                UfiErrorBanner(message = err)
                Spacer(Modifier.height(Spacing.Medium))
            }

            // ── 区间分段控件 ──
            // 从下拉换成分段控件：只有 4 个固定选项，下拉要"点开→再点一次"才能切，
            // 而这是本弹窗里最高频的操作。
            // 区间文案用滚动刷新：切区间 / 翻页时只有变化的那几个字滚过去
            //（"9月16日 周三" → "9月17日 周三" 只滚 6→7），整句不闪。
            // 文案本体仍是 core 下发的，组件只负责逐字对齐与动画。
            UfiRollingText(
                text = shown?.label ?: "—",
                style = UfiTextStyles.sectionTitle,
                color = palette.textPrimary
            )
            Spacer(Modifier.height(Spacing.Medium))
            UfiSingleChipSelector(
                options = TRAFFIC_HISTORY_RANGES,
                selectedValue = history.range,
                onSelect = { viewModel.tools.setTrafficHistoryRange(it) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── 图表区 ──
        // **布局固定**：柱子列表可以为空（组件自己画空态文案），这一块的高度与
        // "有没有数据""在不在加载"无关；加载中不叠任何指示器、也不写提示文案。
        //
        // X 轴单位（"时" / "日" / "月"）从 core 给的刻度文案里**提**出来，剥掉后刻度只剩数字：
        // 25 个 "12时" 排一行会糊成一片，单位写在轴右端一次就够。
        // 这不是日期运算 —— 只是把"数字 + 同一个后缀"的那截后缀摘下来，
        // 周视图那种没有数字前缀的文案（"周一"）会原样保留、不画单位。
        val xUnit = remember(shown) {
            trafficHistoryAxisUnit(shown?.buckets?.map { it.label }.orEmpty())
        }
        val bars = remember(shown, xUnit) {
            shown?.buckets?.map {
                UfiBarChartBar(
                    label = if (xUnit != null) it.label.removeSuffix(xUnit) else it.label,
                    rxBytes = it.rx_bytes,
                    txBytes = it.tx_bytes
                )
            }.orEmpty()
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            UfiBarChart(
                bars = bars,
                valueText = { FormatUtils.formatSize(it) },
                // Y 轴：刻度只写数值、单位写在轴头（"GB"），单位由图表按峰值定。
                // 浮层继续走上面的 valueText（自动单位 + 一位小数），两处口径的差异是刻意的。
                axisValueText = { bytes, unit -> FormatUtils.formatSizeValueInUnit(bytes, unit) },
                axisUnitText = { unit -> FormatUtils.dataSizeUnit(unit) },
                xAxisUnitText = xUnit,
                selectedIndex = selectedBar,
                onSelectedIndexChange = { selectedBar = it },
                // 图内横滑：**没有浮层时**翻页（与按钮同一个动作），
                // 有浮层时同一个手势归"在柱之间擦扫"，判定在组件里按拖动起点决定。
                onPageChange = { forward ->
                    viewModel.tools.shiftTrafficHistory(forward = forward)
                },
                showLabelAt = ::trafficHistoryShowLabel,
                // 浮层用 title（"12时" / "9月14日 周一"）；老版本 core 不带这个
                // 字段时退回轴文案，不要显示空白
                bucketTitle = { i ->
                    shown?.buckets?.getOrNull(i)
                        ?.let { it.title.ifBlank { it.label } }
                        .orEmpty()
                },
                // 加载中**什么都不显示**：不转圈、也不写"正在读取"。切区间/翻页时图表本身
                // 就有"柱子重新长起来"的动画；没数据的那一段会沿用上一段的图（stale），
                // 只有冷启动第一次才可能出现空白（2026-09-16）。
                emptyText = if (history.isLoading) "" else "这一段还没有任何流量记录"
            )
        }

        // ── 图表下方（翻页行 + 合计）──
        Column(modifier = Modifier.fillMaxWidth().then(dismissOnTouch)) {
            // ── 翻页行：上一页 | 回到当前 | 下一页，整排放在图的下面 ──
            // 中间那格固定占位，「回到当前」出现/消失不会把两侧按钮推来推去。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UfiButton(
                    text = "上一页",
                    onClick = { viewModel.tools.shiftTrafficHistory(forward = false) },
                    variant = UfiButtonVariant.Subtle,
                    size = UfiButtonSize.Small,
                    enabled = data != null,
                    icon = Icons.Default.ChevronLeft
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = Spacing.Small),
                    contentAlignment = Alignment.Center
                ) {
                    if (history.anchor != null) {
                        UfiButton(
                            text = "回到当前",
                            onClick = { viewModel.tools.resetTrafficHistoryAnchor() },
                            variant = UfiButtonVariant.Subtle,
                            size = UfiButtonSize.Small
                        )
                    }
                }
                // has_next 为 false 时置灰：翻过去只有一屏空柱，
                // 让按钮可点但什么都不发生就是"假按钮"。
                UfiButton(
                    text = "下一页",
                    onClick = { viewModel.tools.shiftTrafficHistory(forward = true) },
                    variant = UfiButtonVariant.Subtle,
                    size = UfiButtonSize.Small,
                    enabled = data?.has_next == true,
                    icon = Icons.Default.ChevronRight
                )
            }

            Spacer(Modifier.height(Spacing.Large))
            UfiDivider()
            Spacer(Modifier.height(Spacing.Medium))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    // 用 core 给的段落文案拼出"9月14日 周一 总用 / 9月13日-9月19日 总用"，
                    // 比"本段合计"更能说明这个数字统计的是哪一段。
                    // 文案本体仍由 core 生成，这里只加后缀，不做任何日期运算。
                    shown?.label?.let { "$it 总用" } ?: "本段总用",
                    style = UfiTextStyles.note,
                    color = palette.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                // 2026-09-21：右侧字节数也接上滚轮（左边的 label 早就是 UfiRollingText）。
                // formatSize 会自适应单位，所以走 UfiRollingMetric 把 "MB"/"GB" 剥成独立 Text，
                // 换档时只有单位跳一下，数字段照样逐位滚。
                UfiRollingMetric(
                    text = shown?.let { FormatUtils.formatSize(it.total_bytes) } ?: "--",
                    style = UfiTextStyles.bodyEmphasis,
                    unitStyle = UfiTextStyles.bodyEmphasis,
                    color = palette.textPrimary,
                    unitSpacing = 3.dp
                )

            }
        }
    }
}

/**
 * `shown` 的持有者：上一次成功拿到的那份数据。
 *
 * 刻意**不是** `MutableState` —— 它只在组合期读写，不参与 snapshot，
 * 所以不会因为写它而多跑一次重组（详见 [TrafficHistoryDialogBody] 里的注释）。
 */
private class TrafficHistoryShownHolder(var value: TrafficUsageResponse? = null)

/**
 * X 轴标签抽稀：刻度线全画，标签按桶数抽。
 *
 * 判据只看**桶数**，不看 range 名 —— 图表组件与这里都不该认识"日/周/月/年"这些业务概念。
 * 日视图在 DST 切换日是 23 或 25 个桶（core 的 `TrafficUsageWindow` 明确如此），
 * 所以这里写的是区间而不是 `== 24`。
 *
 * 标签带单位（"12时" / "14日" / "11月"）比裸数字宽，所以年视图也要抽稀 ——
 * 12 个 "10月" 排一行在窄屏上会糊成一片。
 */
private fun trafficHistoryShowLabel(index: Int, count: Int): Boolean = when {
    count <= 7 -> true                                   // 周：7 个全显
    count <= 12 -> index % 2 == 0 || index == count - 1  // 年：1/3/5/7/9/11 + 12月
    count <= 25 -> index % 6 == 0 || index == count - 1  // 日：0/6/12/18 + 末位
    else -> index == 0 || (index + 1) % 5 == 0 || index == count - 1  // 月：1/5/10/… + 末日
}

/**
 * 从 core 给的刻度文案里提出 X 轴单位（"12时" → "时"，"14日" → "日"）。
 *
 * 只做**字符串**处理，不认识"日/周/月/年"：要求每条文案都是「数字 + 同一个后缀」，
 * 否则返回 null（周视图的 "周一" 没有数字前缀，年视图若某天 core 换了文案也会自动退回）。
 * 提出来之后刻度只画数字、单位画在轴右端一次 —— 25 个 "12时" 排一行会糊成一片。
 */
private fun trafficHistoryAxisUnit(labels: List<String>): String? {
    if (labels.isEmpty()) return null
    val suffix = labels.first().dropWhile { it.isDigit() }
    if (suffix.isBlank()) return null
    val ok = labels.all { label ->
        label.takeWhile { it.isDigit() }.isNotEmpty() && label.dropWhile { it.isDigit() } == suffix
    }
    return suffix.takeIf { ok }
}
