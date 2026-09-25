package com.ufi_axis.ui.screens

// 2026-09-25：animation 那 6 个 import（AnimatedVisibility / tween / expand·shrinkVertically /
// fadeIn·fadeOut）随限额弹窗一起搬走了 —— 本文件已无使用点，见文件尾部
// UfiDataLimitDialog 调用处的说明。
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.*
// 2026-09-25：原先这里有一行 `import com.ufi_axis.ui.theme.UfiMotion`，用来消解
// ui.components.common（P3a 留下的 @Deprecated 转发壳）与 ui.theme 两个星号导入的同名歧义。
// UfiMotion 的唯一使用点（限额弹窗的两处 AnimatedVisibility）已搬去 UfiDataLimitDialog，
// 本文件不再引用它，歧义也就不存在了 —— 显式导入随之删除。
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.deviceUnsupportedNote
import com.ufi_axis_core.contract.Capability
import kotlin.math.roundToInt

// 2026-09-05：私有 `Color.shade` 已删除 —— 与 HomeConnectionCard / NetworkScreen 是逐字节
// 相同的三份拷贝（MonitorOverview 还有一份 Oklab lerp 版，像素并不相同）。现统一走主题层
// [com.ufi_axis.ui.theme.ufiShade]，它同时是 `onGradient` 判据（`heroGradientBrightestStop`）
// 的输入 —— 运行时画的和测试判的只有一份实现。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficManagementScreen(viewModel: MainViewModel, navController: NavHostController) {
    val palette = LocalResolvedPalette.current
    val state by viewModel.trafficManagementState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.tools.loadTrafficLimit()
        // 能力集自带"本进程只成功拉一次"的闸门，无条件调不会每次进页面都发请求。
        viewModel.network.loadDeviceCapabilities()
    }

    // 设备能力集（批 O / 3.5）：core 在 `POST /api/device/data-limit` 上有一处门禁
    // （`DeviceRoutes.kt:665`），那是限额域的唯一写入口，`saveDataLimit` 的三个调用点
    // （本页的总开关、本页的限额弹窗、首页 hero 卡的同一个弹窗）最终都走它。
    //
    // 本页只灰**会调 saveDataLimit** 的入口。刻意不灰的：
    // - 「流量校准」→ `POST /api/device/traffic-calibrate`，不在这个域里、没有门禁；
    // - 「流量历史」与上面三张统计卡 → 纯读侧，「不支持设限额」不等于「不能看用了多少」。
    //
    // ⚠ 能力集未拉到 / 拉失败时 supports() 恒为 true —— 保持现状，照旧可设。
    val capabilities by viewModel.network.capabilityState.collectAsState()
    val limitSupported = capabilities.supports(Capability.TRAFFIC_LIMIT)

    val cfg = state.limitConfig

    // ── core 已把设备复合串拆好，这里直接用（不再解析任何复合格式） ──
    // 设备原值先过一遍值域再进页面状态：「启用流量限额」开关会**立刻带着这些值**调 saveDataLimit，
    // 而 core 的 validateTrafficLimit 对 alert_percent(0~100) / clear_date(1~31) 是硬校验 ——
    // 设备在自动清零关闭时常给 traffic_clear_date=0，照原样发回去每次都是 400「保存失败」。
    fun inRange(v: String?, range: IntRange): String? =
        v?.takeIf { (it.trim().toIntOrNull() ?: Int.MIN_VALUE) in range }

    var enabled by remember(cfg) { mutableStateOf(cfg?.enabled ?: false) }
    var limitSize by remember(cfg) { mutableStateOf(inRange(cfg?.limit_value, 1..999999) ?: "100") }
    var limitUnit by remember(cfg) { mutableStateOf(cfg?.limit_unit_display?.takeIf { it.isNotBlank() } ?: "GB") }
    var alertPercent by remember(cfg) { mutableStateOf(inRange(cfg?.alert_percent, 0..100) ?: "80") }
    var autoClear by remember(cfg) { mutableStateOf(cfg?.auto_clear ?: false) }
    var clearDate by remember(cfg) { mutableStateOf(inRange(cfg?.clear_date, 1..31) ?: "1") }

    // 校准
    var calibrateValue by remember { mutableStateOf("") }
    var calibrateUnit by remember { mutableStateOf("GB") }

    // 弹窗控制
    var showLimitDialog by remember { mutableStateOf(false) }
    var showCalibrateDialog by remember { mutableStateOf(false) }
    /** 流量历史弹窗（2026-09-15 由独立页改成弹窗，入口是下面那行「流量历史」） */
    var showTrafficHistory by remember { mutableStateOf(false) }

    LaunchedEffect(state.successMessage) {
        if (state.successMessage != null) {
            kotlinx.coroutines.delay(2000)
            viewModel.tools.clearTrafficMessage()
        }
    }

    UfiScreenScaffold(
        title = "流量管理",
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // ── 提示区域 ──
            state.errorMessage?.let { err ->
                UfiErrorBanner(message = err, modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin))
            }
            state.successMessage?.let { msg ->
                UfiSettingsGroup {
                    Text(msg, color = palette.accent, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (state.isLoading && cfg == null) {
                UfiSettingsGroup {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        UfiLoadingIndicator()
                    }
                }
            }

            // ── 内容主体：监控与设置（统一使用 12dp 间距） ──
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.Large) // 12.dp 统一间距
            ) {
                // ── Card 1: Hero 用量概览（现代圆润 Hero 风格） ──
                val monthRx = cfg?.monthly_rx_bytes ?: 0L
                val monthTx = cfg?.monthly_tx_bytes ?: 0L
                val monthTotal = monthRx + monthTx
                val monthlyTime = cfg?.monthly_time ?: 0L
                val hours = monthlyTime / 3600
                val mins = (monthlyTime % 3600) / 60

                // 限额字节数用 core 给的权威值 limit_bytes（Models.kt 明确要求客户端不做单位换算）；
                // 本地 parseToBytes 只用于用户正在编辑的草稿提交值。
                val limitBytes = if (enabled) cfg?.limit_bytes ?: 0L else 0L
                val percent = if (limitBytes > 0) (monthTotal * 100f / limitBytes).coerceIn(0f, 100f) else 0f
                val remainText = if (limitBytes > 0) {
                    val remain = (limitBytes - monthTotal).coerceAtLeast(0L)
                    FormatUtils.formatBytes(remain)
                } else "—"

                val heroShape = UfiCardDefaults.shape
                // 深色档系数改自 GRADIENT_MID_LIGHTEN_DARK / GRADIENT_TOP_LIGHTEN_DARK 常量，理由见常量定义。
                val gradColors = if (palette.isDark) {
                    listOf(
                        palette.accent,
                        palette.accent.ufiShade(GRADIENT_MID_LIGHTEN_DARK),
                        palette.accent.ufiShade(GRADIENT_TOP_LIGHTEN_DARK)
                    )
                } else {
                    listOf(palette.accent, palette.accent.ufiShade(0.12f), palette.accent.ufiShade(0.22f))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.CardHorizontalMargin)
                        .ufiCardShadow(elevation = 6.dp, shape = heroShape)
                        .clip(heroShape)
                        .background(Brush.linearGradient(colors = gradColors))
                        // 2026-09-04（P2-中）：本 Hero 卡原有 10 处写死 Color.White[.copy(alpha)]。
                        // 卡底是 accent 明暗三段渐变（见上方 gradColors），换配色时底色跟着 accent 变、
                        // 这套白色内容不变 ⇒ 浅色 accent 主题下白字/白进度条没对比度，正是 G3 要治的残留。
                        // 现在：不透明前景 → palette.onGradient；带 alpha 的弱化前景/装饰 → palette.gradientMuted。
                        // 两槽默认值都是 Color.White，预设不填 ⇒ 观感零变化；alpha 档位仍留在调用点（20%~90% 不等）。
                        .border(1.dp, palette.gradientMuted.copy(alpha = 0.2f), heroShape)  // 原 Color.White 20%：1px 高光描边
                        .padding(Spacing.CardPadding - 4.dp, 16.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "本月已用流量",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.gradientMuted.copy(alpha = 0.8f)  // 原 Color.White 80%：卡标题
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = "%.1f".format(monthTotal.toDouble() / 1_073_741_824.0),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = UfiWeight.Hero,
                                color = palette.onGradient  // 原 Color.White：已用流量大数字
                            )
                            Text(
                                text = " GB",
                                style = UfiTextStyles.valueStrong,
                                color = palette.onGradient,  // 原 Color.White：单位（与大数字同档，不弱化）
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "/ ${if (enabled && limitSize.isNotBlank()) "${limitSize}${limitUnit}" else "无上限"}",
                                style = UfiTextStyles.bodyLead.copy(fontWeight = UfiWeight.Medium),
                                color = palette.gradientMuted.copy(alpha = 0.85f),  // 原 Color.White 85%：限额说明
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(UfiCardDefaults.pillShape)
                                .background(palette.gradientMuted.copy(alpha = 0.2f))  // 原 Color.White 20%：进度条未填充槽
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth((percent / 100f).coerceIn(0f, 1f))
                                    .height(8.dp)
                                    .clip(UfiCardDefaults.pillShape)
                                    .background(palette.onGradient)  // 原 Color.White：进度条已填充段（不透明前景）
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (limitBytes > 0) "已用 %.0f%%".format(percent) else "未设限额",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.gradientMuted.copy(alpha = 0.9f)  // 原 Color.White 90%：百分比脚注
                            )
                            Text(
                                "剩余 $remainText",
                                style = UfiTextStyles.captionStrong,
                                color = palette.onGradient  // 原 Color.White：剩余量（脚注里被强调的那一项）
                            )
                            Text(
                                if (autoClear && enabled) "${clearDate}日自动重置" else "手动重置",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.gradientMuted.copy(alpha = 0.9f)  // 原 Color.White 90%：重置策略脚注
                            )
                        }
                    }
                }

                // ── Card 2: 流量详情网格 (上传/下载) ──
                // 2026-09-04：顺序改为**上传在前**（原为下载在前）。
                // 占比用**一条**堆叠条表达（不是每格各一条）：上传段 + 下载段拼成 100%，
                // 谁占大头一眼可见；合计已由上方 Hero 卡给出，这里的信息增量就是这个构成比。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.CardHorizontalMargin)
                        .ufiStandardCard()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            UfiTrafficTile(
                                icon = Icons.Default.ArrowUpward,
                                label = "上传",
                                value = FormatUtils.formatBytes(monthTx),
                                valueColor = TrafficUp,
                                modifier = Modifier.weight(1f),
                                embedded = true
                            )
                            UfiTrafficTile(
                                icon = Icons.Default.ArrowDownward,
                                label = "下载",
                                value = FormatUtils.formatBytes(monthRx),
                                valueColor = TrafficDown,
                                modifier = Modifier.weight(1f),
                                embedded = true
                            )
                        }
                        // 无数据时整条都不画：画一条空槽会看着像"两个方向都是 0"，而不是"还没有数据"。
                        if (monthTotal > 0L) {
                            val upShare = (monthTx.toFloat() / monthTotal).coerceIn(0f, 1f)
                            TrafficSplitBar(
                                upShare = upShare,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 12.dp)
                            )
                        }
                    }
                }

                // ── Card 3: 设置区域 ──
                UfiSettingsGroup {
                    UfiSettingsToggle(
                        title = "启用流量限额",
                        // 置灰时保留原说明、原因另起一行追加。checked 仍是设备真值 ——
                        // 「不支持」不等于「关着」，把它画成关就是假开关。
                        description = (if (enabled) "超出限额将触发告警 · ${limitSize}${limitUnit} / 月" else "已关闭") +
                            (if (limitSupported) "" else "\n" + deviceUnsupportedNote("流量限额")),
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            viewModel.tools.saveDataLimit(
                                enabled = it,
                                limitValue = limitSize,
                                limitUnit = limitUnit,
                                alertPercent = alertPercent,
                                autoClear = autoClear,
                                clearDate = clearDate
                            )
                        },
                        enabled = limitSupported
                    )

                    UfiDivider(modifier = Modifier.padding(vertical = 2.dp))

                    UfiSettingsValue(
                        title = "告警阈值",
                        value = "${alertPercent}%",
                        onClick = if (enabled && limitSupported) { { showLimitDialog = true } } else null
                    )

                    UfiDivider(modifier = Modifier.padding(vertical = 2.dp))

                    UfiSettingsValue(
                        title = "自动清零",
                        value = if (autoClear) "${clearDate}日" else "手动",
                        onClick = if (enabled && limitSupported) { { showLimitDialog = true } } else null
                    )

                    UfiDivider(modifier = Modifier.padding(vertical = 2.dp))

                    // 流量历史是**弹窗**而不是独立页（2026-09-15 改）：内容只有
                    // 「分段控件 + 一张图 + 一行合计」，撑不起一整页；弹窗看完直接回到限额设置。
                    UfiSettingsValue(
                        title = "流量历史",
                        description = "按日 / 周 / 月 / 年查看用量",
                        value = "查看",
                        icon = Icons.Default.BarChart,
                        onClick = { showTrafficHistory = true }
                    )

                    UfiDivider(modifier = Modifier.padding(vertical = 2.dp))

                    UfiButtonRow(modifier = Modifier.padding(top = Spacing.Small)) {
                        UfiButton(
                            variant = UfiButtonVariant.Secondary,
                            text = "限额设置",
                            onClick = { showLimitDialog = true },
                            enabled = limitSupported,
                            modifier = Modifier.weight(1f)
                        )
                        UfiButton(
                            variant = UfiButtonVariant.Secondary,
                            text = "流量校准",
                            onClick = { showCalibrateDialog = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    // ── 弹窗定义 ──
    // 流量历史：内容较重（分段控件 + 柱状图），自身在 visible=false 时不组合内容，
    // 所以常驻在这里挂着即可 —— 打开才取数（弹窗体内的 LaunchedEffect）。
    TrafficHistoryDialog(
        visible = showTrafficHistory,
        viewModel = viewModel,
        onDismiss = { showTrafficHistory = false }
    )

    // 限额设置弹窗。2026-09-25：那 180 行 UI + draft 暂存 + 输入校验已整体搬到公共层
    // [UfiDataLimitDialog]（`app/ui`）—— 仪表盘 hero 卡「本月流量」要复用同一个弹窗，
    // 而 :app:feature-dashboard 不依赖 :app:feature-tools，留在本文件就只能抄第二份。
    //
    // 留在页面侧的只有**映射**：`cfg`（TrafficLimitConfig，data 层类型）→ 纯值入参，
    // 以及保存时把 `enabled`（本页的限额总开关，不属于弹窗）补进 saveDataLimit。
    // 行为与搬之前逐项一致：
    //  - 三个打开入口（「告警阈值」行 / 「自动清零」行 / 「限额设置」按钮）都只置 showLimitDialog；
    //  - 「保存」仍是「写回页面 5 个 state → saveDataLimit(8 参) → 关闭」这同一条路径；
    //  - 「取消」仍只关闭、不写回（暂存-确认，见 UfiDataLimitDialog 的 KDoc ①）。
    UfiDataLimitDialog(
        visible = showLimitDialog,
        limitSize = limitSize,
        limitUnit = limitUnit,
        alertPercent = alertPercent,
        autoClear = autoClear,
        clearDate = clearDate,
        // 自动关网是 core 自制功能（不是设备字段），真源在 cfg.auto_off
        autoOffEnabled = cfg?.auto_off?.enabled ?: false,
        autoOffRestore = cfg?.auto_off?.restore_on_reset ?: false,
        autoOffTriggered = cfg?.auto_off?.triggered == true,
        onDismiss = { showLimitDialog = false },
        onConfirm = { size, unit, alert, clear, date, autoOff, autoOffRestore ->
            limitSize = size
            limitUnit = unit
            alertPercent = alert
            autoClear = clear
            clearDate = date
            viewModel.tools.saveDataLimit(
                enabled = enabled,
                limitValue = size,
                limitUnit = unit,
                alertPercent = alert,
                autoClear = clear,
                clearDate = date,
                autoOffEnabled = autoOff,
                autoOffRestore = autoOffRestore
            )
            showLimitDialog = false
        }
    )

    if (showCalibrateDialog) {
        UfiCustomDialog(
            visible = true,
            onDismiss = { showCalibrateDialog = false },
            title = "流量校准",
            icon = rememberVectorPainter(Icons.Filled.Tune),
            showCloseButton = false,
            // 2026-09-18：自写的确认/取消按钮改经 LocalUfiDialogClose 排时序 —— 弹窗离场的
            // backdrop（逐渐清晰）要在窗口销毁前播完；local 必须在 slot 内部读才能拿到 shell 的实现。
            confirmButton = {
                val close = LocalUfiDialogClose.current
                UfiButton(
                    text = "执行校准",
                    onClick = {
                        close {
                            if (calibrateValue.isNotBlank()) {
                                val data = parseToBytes(calibrateValue, calibrateUnit).toString()
                                viewModel.tools.calibrateFlow("data", data)
                                calibrateValue = ""
                            }
                            showCalibrateDialog = false
                        }
                    },
                    enabled = calibrateValue.isNotBlank()
                )
            },
            dismissButton = {
                val close = LocalUfiDialogClose.current
                UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = { close { showCalibrateDialog = false } })
            }
        ) {
            UfiDialogBody {
                Text(
                    "手动设置设备已用流量值，修正统计偏差",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UfiDigitField(
                        value = calibrateValue,
                        onValueChange = { calibrateValue = it },
                        label = "已用流量",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.Medium))
                    UfiDropdown(
                        selectedValue = calibrateUnit,
                        options = listOf("MB", "GB"),
                        onValueSelected = { calibrateUnit = it },
                        modifier = Modifier.width(100.dp)
                    )
                }
            }
        }
    }
}

/**
 * 上传 / 下载构成条：**一条** 100% 堆叠条，左段上传、右段下载。
 *
 * 2026-09-04：取代"每格各一条占比条"的方案 —— 两条各自 0..100% 的条要对比才知道谁大，
 * 而拼成一条之后"谁占大头"是长度直接给出的，且总量恒为 100% 不需要读数字去加。
 *
 * 百分比只在段宽 ≥ 18% 时画在段内（窄段塞不下字，硬塞会变成省略号）；
 * 下载段的百分比用 `100 - 上传` 而不是各自四舍五入，否则会出现 63% + 38% = 101% 这种读数。
 */
@Composable
private fun TrafficSplitBar(upShare: Float, modifier: Modifier = Modifier) {
    val up = upShare.coerceIn(0f, 1f)
    val down = 1f - up
    val upPercent = (up * 100).roundToInt()
    Row(
        modifier = modifier
            .height(14.dp)
            .clip(UfiCardDefaults.pillShape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // weight 必须 > 0，某一方向为 0 时整段不渲染（另一段自然铺满）
        if (up > 0f) TrafficSplitSegment(TrafficUp, up, "$upPercent%")
        if (down > 0f) TrafficSplitSegment(TrafficDown, down, "${100 - upPercent}%")
    }
}

@Composable
private fun RowScope.TrafficSplitSegment(color: Color, share: Float, percentText: String) {
    val palette = LocalResolvedPalette.current
    Box(
        modifier = Modifier
            .weight(share)
            .fillMaxHeight()
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        if (share >= 0.18f) {
            Text(
                text = percentText,
                style = UfiTextStyles.captionTiny,
                color = palette.onGradient,
                maxLines = 1
            )
        }
    }
}

/**
 * 数值 + 单位 → 字节。**只用于用户正在编辑的草稿提交值**（限额弹窗、流量校准）；
 * 展示侧的限额一律用 core 给的 `limit_bytes`，见 Models.kt 的 TrafficLimitConfig。
 *
 * 解析不出来回 0 让调用方按"未设限额"处理 —— 原来回 Long.MAX_VALUE，
 * 而 "NaN" / "Infinity" 能过 toDoubleOrNull、BigDecimal 却会抛，于是限额被当成无上限、
 * 校准会提交一个天文数字。
 */
private fun parseToBytes(value: String, unit: String): Long {
    val num = value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return 0L
    val factor = when (unit.uppercase()) {
        "KB" -> java.math.BigDecimal(1024)
        "MB" -> java.math.BigDecimal(1048576)
        "GB" -> java.math.BigDecimal(1073741824)
        "TB" -> java.math.BigDecimal(1099511627776)
        // 未知单位按 GB 算：单位只可能来自本页的下拉（MB/GB/TB）或设备回的显示名，
        // 按字节算会把 "100" 变成 100B，等于"几乎没有限额"——那是最危险的一种猜法。
        else -> java.math.BigDecimal(1073741824)
    }
    return try {
        val bytes = java.math.BigDecimal(num.toString()).multiply(factor)
        if (bytes.signum() < 0) 0L
        else bytes.coerceAtMost(java.math.BigDecimal(Long.MAX_VALUE))
            .setScale(0, java.math.RoundingMode.DOWN).longValueExact()
    } catch (_: Exception) {
        0L
    }
}
