package com.ufi_axis.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import com.ufi_axis.ui.theme.*
// 本文件同时 star-import 了 ui.components.common 与 ui.theme，两边都有 `UfiMotion`
// （前者是 P3a 留下的 @Deprecated 转发壳），星号导入同名会歧义。显式导入指向正本。
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
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

    LaunchedEffect(Unit) { viewModel.tools.loadTrafficLimit() }

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

                val limitBytes = if (enabled && limitSize.isNotBlank()) parseToBytes(limitSize, limitUnit) else 0L
                val percent = if (limitBytes > 0) (monthTotal * 100f / limitBytes).coerceIn(0f, 100f) else 0f
                val remainText = if (limitBytes > 0) {
                    val remain = (limitBytes - monthTotal).coerceAtLeast(0L)
                    FormatUtils.formatBytes(remain)
                } else "—"

                val heroShape = UfiCardDefaults.shape
                val gradColors = if (palette.isDark) {
                    listOf(palette.accent.ufiShade(-0.14f), palette.accent.ufiShade(-0.04f), palette.accent)
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
                                if (enabled && limitSize.isNotBlank()) "已用 %.0f%%".format(percent) else "未设限额",
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
                        description = if (enabled) "超出限额将触发告警 · ${limitSize}${limitUnit} / 月" else "已关闭",
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
                        }
                    )

                    UfiDivider(modifier = Modifier.padding(vertical = 2.dp))

                    UfiSettingsValue(
                        title = "告警阈值",
                        value = "${alertPercent}%",
                        onClick = if (enabled) { { showLimitDialog = true } } else null
                    )

                    UfiDivider(modifier = Modifier.padding(vertical = 2.dp))

                    UfiSettingsValue(
                        title = "自动清零",
                        value = if (autoClear) "${clearDate}日" else "手动",
                        onClick = if (enabled) { { showLimitDialog = true } } else null
                    )

                    UfiDivider(modifier = Modifier.padding(vertical = 2.dp))

                    UfiButtonRow(modifier = Modifier.padding(top = Spacing.Small)) {
                        UfiButton(
                            variant = UfiButtonVariant.Secondary,
                            text = "限额设置",
                            onClick = { showLimitDialog = true },
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
    if (showLimitDialog) {
        // 暂存-确认：弹窗内改的是 draft，**不碰页面已生效值**，点「保存」才写回并发请求。
        // 之前弹窗直接编辑页面级 state，于是点「取消」后改动其实已经留在页面上，
        // 紧接着任何一次「启用流量限额」开关（它会立刻带着当前值调 saveDataLimit）
        // 就把那些没确认的草稿一起发给了设备 —— 表现就是"取消了仍然生效"。
        // draft 声明在 if 分支内：弹窗关闭时这段组合被销毁，下次打开自动按当前值重新初始化。
        var draftSize by remember { mutableStateOf(limitSize) }
        var draftUnit by remember { mutableStateOf(limitUnit) }
        var draftAlert by remember { mutableStateOf(alertPercent) }
        var draftAutoClear by remember { mutableStateOf(autoClear) }
        var draftClearDate by remember { mutableStateOf(clearDate) }
        // 自动关网是 core 自制功能（不是设备字段），真源在 cfg.auto_off
        var draftAutoOff by remember { mutableStateOf(cfg?.auto_off?.enabled ?: false) }
        var draftAutoOffRestore by remember { mutableStateOf(cfg?.auto_off?.restore_on_reset ?: false) }

        // ── 前置校验：值域与 core 侧 profile 的 validateTrafficLimit 逐条对齐 ──
        // 越界时 core 回 400 OUT_OF_RANGE，app 只能显示一句没有原因的「保存失败」，
        // 所以这里就把不合法的输入拦住：字段标红 + 保存按钮置灰，别让请求发出去。
        // 限额数值原来是自由文本框（能输字母），非数字会被 core 的 toLongOrNull 判空 ——
        // 那种情况更坑：接口回 success:true 但限额其实没写进设备。
        val sizeNum = draftSize.trim().toLongOrNull()
        val sizeValid = sizeNum != null && sizeNum > 0
        val alertNum = draftAlert.trim().toIntOrNull()
        val alertValid = alertNum != null && alertNum in 0..100
        val dateNum = draftClearDate.trim().toIntOrNull()
        // 自动清零关闭时清零日期不参与判定（它此时不会影响用量统计，但仍随请求下发）
        val dateValid = !draftAutoClear || (dateNum != null && dateNum in 1..31)
        val formValid = sizeValid && alertValid && dateValid

        UfiScrollableDialog(
            visible = true,
            onDismiss = { showLimitDialog = false },
            title = "限额设置",
            icon = rememberVectorPainter(Icons.Filled.DataUsage),
            showCloseButton = false,
            actions = {
                UfiDialogActions(
                    onDismiss = { showLimitDialog = false },
                    onConfirm = {
                        limitSize = draftSize
                        limitUnit = draftUnit
                        alertPercent = draftAlert
                        autoClear = draftAutoClear
                        clearDate = draftClearDate
                        viewModel.tools.saveDataLimit(
                            enabled = enabled,
                            limitValue = draftSize,
                            limitUnit = draftUnit,
                            alertPercent = draftAlert,
                            autoClear = draftAutoClear,
                            clearDate = draftClearDate,
                            autoOffEnabled = draftAutoOff,
                            autoOffRestore = draftAutoOffRestore
                        )
                        showLimitDialog = false
                    },
                    confirmText = "保存",
                    dismissText = "取消",
                    enabled = formValid
                )
            }
        ) {
            UfiDialogBody {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 只收数字：原来是 UfiTextField，能输入字母/小数点，
                    // 请求发出去后由 core 判非法（或静默丢弃），报错回来才知道。
                    // maxLength=6 够到 999999 TB，够用且挡住误粘贴一长串。
                    UfiDigitField(
                        value = draftSize,
                        onValueChange = { draftSize = it },
                        label = "限额大小",
                        modifier = Modifier.weight(1f),
                        maxLength = 6,
                        placeholder = "100",
                        isError = !sizeValid,
                        errorMessage = if (!sizeValid) "请输入大于 0 的整数" else null
                    )
                    Spacer(Modifier.width(Spacing.Medium))
                    // 2026-09-04（P4e 下拉收敛）：UfiDropdown 已从 M3 ExposedDropdownMenuBox 换成
                    // 主题化实现（44dp surfaceMuted 卡 + 主题化弹层），原先偏紫的 M3 弹层色随之消失。
                    // 不再传 label："单位"作为浮动 label 已无对应槽位，本组件的 unitSuffix 是**值的后缀**
                    // （"GB 单位"读不通）。左侧「限额大小」输入框的 label 已经交代了这一行在填什么。
                    UfiDropdown(
                        selectedValue = draftUnit,
                        options = listOf("MB", "GB", "TB"),
                        onValueSelected = { draftUnit = it },
                        modifier = Modifier.width(110.dp)
                    )
                }

                UfiDigitField(
                    value = draftAlert,
                    onValueChange = { draftAlert = it },
                    label = "告警阈值 (%)",
                    maxLength = 3,
                    isError = !alertValid,
                    errorMessage = if (!alertValid) "取值 0~100" else null
                )
                Text(
                    "用量达到此百分比时触发告警",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )

                // 2026-09-04：两处"开关控制的附属块"由裸 `if` 改为 AnimatedVisibility。
                //
                // ⚠ 两个必须注意的点：
                // 1. **不能直接把 AnimatedVisibility 当 UfiDialogBody 的子项**：body 用
                //    `Arrangement.spacedBy(Spacing.Large)`，收起态的 AnimatedVisibility 虽然测量为 0 高，
                //    仍算一个子项 → 会凭空多出一道 12dp 间距（看起来就是"弹窗里莫名多一段空白"）。
                //    所以把「开关 + 附属块」包成一个 Column 当单个子项，附属块的上间距由它自己的
                //    padding 提供，收起时整块真正 0 高。
                // 2. 时长取 Duration.Standard(220)。`DownloadDialogs.kt:86` 记录过 expand/shrink 会让
                //    弹窗高度逐帧补间、输入框跟着抖，那里因此退回裸 if；这里能用是因为**附属块都在
                //    输入区下方**（限额值/阈值/清零日期都在其上），展开不会推动正在编辑的字段，
                //    且本弹窗是 UfiScrollableDialog（内容区带 verticalScroll），变高只滚动、不裁切。
                Column {
                    UfiSettingsToggle(
                        title = "自动清零",
                        description = if (draftAutoClear) "每月 $draftClearDate 日自动重置" else "手动管理",
                        checked = draftAutoClear,
                        onCheckedChange = { draftAutoClear = it }
                    )
                    AnimatedVisibility(
                        visible = draftAutoClear,
                        enter = fadeIn(tween(UfiMotion.Duration.Standard)) +
                            expandVertically(tween(UfiMotion.Duration.Standard)),
                        exit = fadeOut(tween(UfiMotion.Duration.Standard)) +
                            shrinkVertically(tween(UfiMotion.Duration.Standard))
                    ) {
                        Column(modifier = Modifier.padding(top = Spacing.Large)) {
                            UfiDigitField(
                                value = draftClearDate,
                                onValueChange = { draftClearDate = it },
                                label = "清零日期 (1-31)",
                                maxLength = 2,
                                isError = !dateValid,
                                errorMessage = if (!dateValid) "取值 1~31" else null
                            )
                        }
                    }
                }

                Column {
                    UfiSettingsToggle(
                        title = "到达阈值关闭移动数据",
                        description = "达到告警阈值后先发邮件通知，发送成功 1 分钟后关闭移动数据",
                        checked = draftAutoOff,
                        onCheckedChange = { draftAutoOff = it }
                    )
                    AnimatedVisibility(
                        visible = draftAutoOff,
                        enter = fadeIn(tween(UfiMotion.Duration.Standard)) +
                            expandVertically(tween(UfiMotion.Duration.Standard)),
                        exit = fadeOut(tween(UfiMotion.Duration.Standard)) +
                            shrinkVertically(tween(UfiMotion.Duration.Standard))
                    ) {
                        Column(
                            modifier = Modifier.padding(top = Spacing.Large),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Large)
                        ) {
                            UfiSettingsToggle(
                                title = "清零后自动重新打开",
                                description = if (draftAutoOffRestore) "流量清零后自动恢复移动数据"
                                else "只关一次，之后需手动打开",
                                checked = draftAutoOffRestore,
                                onCheckedChange = { draftAutoOffRestore = it }
                            )
                            Text(
                                "邮件发送失败时不会关闭网络（避免你以为设备故障）。需要在邮件设置里勾选「流量预警」场景。" +
                                    if (cfg?.auto_off?.triggered == true) "\n本计费周期已触发过，用量清零前不会再次关闭。" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCalibrateDialog) {
        UfiCustomDialog(
            visible = true,
            onDismiss = { showCalibrateDialog = false },
            title = "流量校准",
            icon = rememberVectorPainter(Icons.Filled.Tune),
            showCloseButton = false,
            confirmButton = {
                UfiButton(
                    text = "执行校准",
                    onClick = {
                        if (calibrateValue.isNotBlank()) {
                            val data = parseToBytes(calibrateValue, calibrateUnit).toString()
                            viewModel.tools.calibrateFlow("data", data)
                            calibrateValue = ""
                        }
                        showCalibrateDialog = false
                    },
                    enabled = calibrateValue.isNotBlank()
                )
            },
            dismissButton = {
                UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = { showCalibrateDialog = false })
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

private fun parseToBytes(value: String, unit: String): Long {
    val num = value.toDoubleOrNull() ?: return 0
    val factor = when (unit.uppercase()) {
        "KB" -> java.math.BigDecimal(1024)
        "MB" -> java.math.BigDecimal(1048576)
        "GB" -> java.math.BigDecimal(1073741824)
        "TB" -> java.math.BigDecimal(1099511627776)
        else -> return num.toLong()
    }
    return try {
        val bytes = java.math.BigDecimal(num.toString()).multiply(factor)
        if (bytes.signum() < 0) 0L
        else bytes.coerceAtMost(java.math.BigDecimal(Long.MAX_VALUE))
            .setScale(0, java.math.RoundingMode.DOWN).longValueExact()
    } catch (_: Exception) {
        Long.MAX_VALUE
    }
}
