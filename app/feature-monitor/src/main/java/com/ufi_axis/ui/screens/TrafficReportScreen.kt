package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.data.model.TrafficReportDay
import com.ufi_axis.data.model.TrafficReportResponse
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiCompactProgressBar
import com.ufi_axis.ui.components.common.UfiDivider
import com.ufi_axis.ui.components.common.UfiEmptyState
import com.ufi_axis.ui.components.common.UfiGroupHeader
import com.ufi_axis.ui.components.common.UfiLoadingBox
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsGroup
import com.ufi_axis.ui.components.common.UfiSettingsValue
import com.ufi_axis.ui.components.common.UfiSingleChipSelector
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.util.NetworkErrorClassifier
import kotlinx.coroutines.CancellationException

/**
 * 流量报表（日报 / 周报 / 月报）—— `GET /api/traffic/report?period=day|week|month`。
 *
 * ## 这一页回答什么问题
 * 「这段时间一共用了多少、哪一天最多」。它是**只读的一次性查询**：core 侧每次现查
 * `traffic_hourly` 再按自然日聚合，没有缓存、没有 WS 推送。
 *
 * ## 为什么单独一页，而不是塞进「流量管理」或监控总览的卡片里
 * - 「流量管理」管的是**限额与套餐**（改配置 + 看当前周期用量），报表是**回看历史**，
 *   两者时间尺度与动作性质都不同；
 * - 监控总览的 hero 与 6 格硬性只统计"今天 00:00 起"，把周/月塞进去会让"这个数字是哪一段的"
 *   变得要猜。所以总览只放一个**入口**（见 `MonitorScreen` 的「流量报表」行）。
 *
 * ## 为什么状态只活在本页，不新建 Module / 不进 MainViewModel
 * `MainViewModel` 那套 Module 是为"多页共享 + 轮询"设计的（state 里存的是全局时间窗口，
 * 多个页面同时读）。本页的 `period` 只在这一页有意义、换档必然重拉，没有任何第二个消费者，
 * 进全局 state 只会多一份需要清理的常驻状态。所以状态收在页面内（与 `MediaVideoSettingsCoversScreen`
 * 等页同一手法：页面自己的数据自己拉）。
 *
 * ## 否决过的替代方案
 * 1. **复用 `/api/traffic/usage`（range=day|week|month）**：它的桶粒度是小时（日视图）与月（年视图），
 *    没有"按自然日出桶"的档，也不回 core 那句摘要 —— 周报要的"一周内每日"它给不了。
 * 2. **在 app 侧按 `days` 自己算合计 / 日均 / 峰值**：core 的 `TrafficReportGenerator.summarize()`
 *    已经算好整句（`summary`），两端各拼一份必然分叉。本页只**显示**它。
 * 3. **引入图表库画趋势**：本页只需要"谁高谁低"，一行 `UfiCompactProgressBar` 就够；
 *    精确读数就在同一行的文字里，引依赖不值（本仓明令不新增依赖）。
 * 4. **自己格式化日期**：`day_label` 是 core 用 `MM-dd` 格式化好的（时区口径在 core 统一），
 *    直接显示；本页只在 core 没给标签时（`days` 为空）才回退到 [FormatUtils.formatTimestamp]。
 *
 * ## 字节格式化
 * 一律走 [FormatUtils.formatBytes]（它委托 `core:common` 的 `formatDataSize`），
 * **不新写换算** —— 全库曾有 6 份私有实现，收敛的经过见该函数的 KDoc。
 *
 * ## 400 与网络错误分开处理
 * core 对非法的 `period` 回 **400**（不像 `/usage` 那样静默回落），那说明"两端对参数的理解不一致"，
 * 与"设备连不上"是完全不同的两件事，文案必须分开（见 [describeReportError]）。
 */
@Composable
fun TrafficReportScreen(navController: NavHostController) {
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }

    // period 进 saveable：转屏/进程恢复后不该把用户选的档位忘掉（重进页面重拉一次即可）。
    var period by rememberSaveable { mutableStateOf(PERIOD_WEEK) }
    var uiState by remember { mutableStateOf<ReportUiState>(ReportUiState.Loading) }
    // 重试计数当 key：值一变就重跑下面的 LaunchedEffect。用它而不是把"重试"做成一个 suspend 函数，
    // 是为了让"加载中"这一态只有一处赋值点（换档与重试走同一条路）。
    var retryTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(period, retryTick) {
        uiState = ReportUiState.Loading
        uiState = try {
            ReportUiState.Ready(RetrofitClient.getApiService(prefs).getTrafficReport(period))
        } catch (e: CancellationException) {
            // 换档/离页会取消在飞的请求：取消不是失败，必须原样抛出，否则会把
            // CancellationException 显示成一条错误文案，并且吞掉协程的取消语义。
            throw e
        } catch (e: Throwable) {
            ReportUiState.Failed(describeReportError(e))
        }
    }

    UfiScreenScaffold(
        title = "流量报表",
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 三档切换放最上面：它是这一页唯一的输入，换档会整页重取。
            UfiSingleChipSelector(
                options = listOf(
                    PERIOD_DAY to "日报",
                    PERIOD_WEEK to "周报",
                    PERIOD_MONTH to "月报"
                ),
                selectedValue = period,
                onSelect = { period = it },
                modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
            )

            when (val state = uiState) {
                ReportUiState.Loading -> {
                    // 与仓库既有写法一致（UfiLoadingBox）：加载态只占内容区，不换掉整页壳。
                    UfiLoadingBox(isLoading = true) {}
                }

                is ReportUiState.Failed -> {
                    UfiEmptyState(
                        icon = Icons.Default.Warning,
                        message = "报表加载失败",
                        hint = state.message,
                        action = {
                            UfiButton(text = "重试", onClick = { retryTick++ })
                        }
                    )
                }

                is ReportUiState.Ready -> {
                    ReportBody(period = period, report = state.report)
                }
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

/**
 * 报表正文：本期概览 + 逐日明细。
 *
 * 抽成独立函数只是为了让上面那个 `when` 保持三行；状态仍然只在 [TrafficReportScreen] 里。
 */
@Composable
private fun ReportBody(period: String, report: TrafficReportResponse) {
    val palette = LocalResolvedPalette.current
    val hasData = report.total_bytes > 0L

    // 概览卡：core 的整句摘要 + 区间 + 合计 / 下行 / 上行。
    UfiSettingsGroup {
        UfiGroupHeader("本期概览")

        // summary 是 core 生成的**整句**（"本周流量 12.3 GB（↓11.0 GB ↑1.3 GB），日均 1.8 GB，最高周六 4.1 GB"），
        // 原样显示。空串时（老 core 没这个键）不显示这一行，而不是画一个空行。
        if (report.summary.isNotBlank()) {
            Text(
                text = report.summary,
                style = UfiTextStyles.body,
                color = palette.textPrimary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
            )
            UfiDivider()
        }

        // 「今天/本周/本月」天然是尚未走完的一段（range_end 是下一段的起点），
        // 日报尤其明显：不提示的话用户会把"才用了 200MB"读成"今天流量掉了"。
        // 周报/月报同理，只是没那么刺眼 —— 所以只有日报给这一行，避免三档都挂一句废话。
        if (period == PERIOD_DAY) {
            Text(
                text = "「今天」是尚未过完的一天，数字还会随采集继续增长。",
                style = UfiTextStyles.note,
                color = palette.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
            )
            UfiDivider()
        }

        UfiSettingsValue(
            title = "区间合计",
            description = reportRangeLabel(report),
            value = FormatUtils.formatBytes(report.total_bytes)
        )
        UfiDivider()
        UfiSettingsValue(title = "下行（接收）", value = FormatUtils.formatBytes(report.rx_bytes))
        UfiDivider()
        UfiSettingsValue(title = "上行（发送）", value = FormatUtils.formatBytes(report.tx_bytes))
    }

    if (!hasData) {
        // 全 0 的两种情况必须说清，否则用户分不清"没用到流量"还是"没采到数据"：
        // 前者是正常结果，后者是采集/时间窗的问题。
        UfiSettingsGroup {
            UfiEmptyState(
                icon = Icons.Default.BarChart,
                message = "本期没有记录到流量",
                hint = "这段时间内设备端的流量小时表没有数据。刚跨过零点或周一、后台采集被停用、" +
                    "或设备刚重启过都会这样 —— 数字会随后续采集补上。"
            )
        }
        return
    }

    // 逐日明细。maxTotal 是画趋势条的基准（= 本期用量最高的一天），
    // 0 时（理论上不会走到这里，hasData 已挡住）退化为不画条。
    val maxTotal = report.days.maxOfOrNull { it.total_bytes } ?: 0L
    UfiSettingsGroup {
        UfiGroupHeader("逐日明细")
        report.days.forEachIndexed { index, day ->
            if (index > 0) UfiDivider()
            ReportDayRow(day = day, maxTotal = maxTotal)
        }
    }
}

/**
 * 单日一行：日期标签 + 合计 + 上下行 + 趋势条。
 *
 * 趋势条用 [UfiCompactProgressBar]（仓库既有组件，3dp 高）而不是引入图表库：
 * 它要回答的只是"这天比那天多"，精确值就在同一行的文字里。
 *
 * 日期显示 core 给的 [TrafficReportDay.day_label]，**不自己格式化**（理由见文件头）。
 */
@Composable
private fun ReportDayRow(day: TrafficReportDay, maxTotal: Long) {
    val palette = LocalResolvedPalette.current
    val fraction = if (maxTotal > 0L) (day.total_bytes.toDouble() / maxTotal).toFloat() else 0f

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = day.day_label,
                style = UfiTextStyles.listItemTitle,
                color = palette.textPrimary,
                modifier = Modifier.width(52.dp)
            )
            Text(
                text = FormatUtils.formatBytes(day.total_bytes),
                style = UfiTextStyles.body,
                // 0 的那天走次要色：一周里没用到流量的日子不该和用得最多的那天一样醒目。
                color = if (day.total_bytes > 0L) palette.textPrimary else palette.textSecondary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "↓${FormatUtils.formatBytes(day.rx_bytes)}  ↑${FormatUtils.formatBytes(day.tx_bytes)}",
                style = UfiTextStyles.note,
                color = palette.textSecondary
            )
        }
        Spacer(Modifier.height(Spacing.Small))
        UfiCompactProgressBar(progress = fraction, modifier = Modifier.fillMaxWidth())
    }
}

/** 报表页的加载状态机：三态各自独占一屏，不叠在一起。 */
private sealed interface ReportUiState {
    /** 请求在飞（换档 / 重试都走这一态）。 */
    data object Loading : ReportUiState

    /** 拿到报表（`ok=false` 或全 0 都算 Ready —— 空数据不是错误）。 */
    data class Ready(val report: TrafficReportResponse) : ReportUiState

    /** 请求失败，[message] 是已经翻成人话的原因。 */
    data class Failed(val message: String) : ReportUiState
}

/** 统计周期取值，与 core `TrafficReportGenerator.generate` 的 `when (period)` 逐字一致。 */
private const val PERIOD_DAY = "day"
private const val PERIOD_WEEK = "week"
private const val PERIOD_MONTH = "month"

/**
 * 区间文案：优先用 core 给的 [TrafficReportDay.day_label] 首尾拼（**不自己格式化日期**），
 * 只有 `days` 为空时才回退到 [FormatUtils.formatTimestamp]。
 *
 * 为什么不直接显示 `range_start` / `range_end` 的时间戳：那两个是 epoch 毫秒，
 * 格式化就要自己决定时区与格式，而时区口径统一在 core（`TimeZone.getDefault()`）。
 * 用 core 的标签等于复用它已经定好的口径。
 */
private fun reportRangeLabel(report: TrafficReportResponse): String {
    val first = report.days.firstOrNull()?.day_label
    val last = report.days.lastOrNull()?.day_label
    return when {
        first.isNullOrBlank() -> "${FormatUtils.formatTimestamp(report.range_start)} 起"
        last.isNullOrBlank() || first == last -> first
        else -> "$first ~ $last"
    }
}

/**
 * 把报表请求的异常翻成一句能读懂的话，**并把 400 与网络错误分开**。
 *
 * ## 为什么按类名识别 `HttpException` 而不是 `catch (e: HttpException)`
 * `:app:feature-monitor` 的**编译类路径上没有 retrofit**：`:app:data` 与 `:app:viewmodel`
 * 都以 `implementation` 声明它，不透传给下游模块，而本仓禁止为了一条错误分支新增依赖。
 * 这里只依赖 `HttpException.code()` 这一个稳定公开方法，用类名 + 反射取即可。
 *
 * 判定顺序刻意是「传输层 → HTTP 状态码 → 兜底」：`NetworkErrorClassifier` 的口径是
 * **只有确凿的传输层异常**才算连不上（HTTP 4xx/5xx 说明连接是通的），与这里一致。
 */
private fun describeReportError(e: Throwable): String {
    NetworkErrorClassifier.describe(e)?.let { return it }
    return when (val code = httpStatusOf(e)) {
        400 -> "设备端不认识这个统计周期（period）。这通常是 App 与设备端 Core 版本不一致 —— " +
            "App 已经带上了新参数，而设备上的 Core 还没有这条端点。"
        null -> e.message?.takeIf { it.isNotBlank() } ?: "未知错误"
        else -> "设备端返回 HTTP $code"
    }
}

/**
 * 取 Retrofit 的 HTTP 状态码；不是 `HttpException` 或取不到时返回 null。
 *
 * 只在错误分支上跑一次，反射开销可以忽略；失败一律返回 null（宁可少一条精确文案，
 * 也不要在错误处理里再抛一个异常把页面打崩）。
 */
private fun httpStatusOf(e: Throwable): Int? {
    if (e.javaClass.name != "retrofit2.HttpException") return null
    return runCatching { e.javaClass.getMethod("code").invoke(e) as? Int }.getOrNull()
}
