package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 将设备返回的 "SIZE_MULTIPLIER" 格式解析为 (数值, 单位) 对。
 * 例: "470_1024" → ("470", "GB")，multiplier 映射: 1=MB, 1024=GB, 1048576=TB
 */
private fun parseLimitSize(raw: String?): Pair<String, String> {
    if (raw.isNullOrBlank()) return "100" to "GB"
    val parts = raw.split("_")
    val size = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "100"
    val mult = parts.getOrNull(1)?.toIntOrNull() ?: 1024
    val unit = when (mult) { 1 -> "MB"; 1024 -> "GB"; 1048576 -> "TB"; else -> "GB" }
    return size to unit
}

/**
 * 将用户输入的 (数值, 单位) 重构为设备需要的 "SIZE_MULTIPLIER" 格式。
 */
private fun buildCompoundSize(size: String, unit: String): String {
    val mult = when (unit) { "MB" -> "1"; "GB" -> "1024"; "TB" -> "1048576"; else -> "1024" }
    return "${size}_$mult"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficManagementScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.trafficManagementState.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadTrafficLimit() }

    val cfg = state.limitConfig

    // ── 解析设备返回的复合格式，全部在 remember(cfg) 内完成 ──
    val parsed = remember(cfg) { parseLimitSize(cfg?.limit_size) }
    var enabled by remember(cfg) { mutableStateOf(cfg?.enabled ?: false) }
    var limitSize by remember(parsed) { mutableStateOf(parsed.first) }
    var limitUnit by remember(parsed) { mutableStateOf(parsed.second) }
    var alertPercent by remember(cfg) { mutableStateOf(cfg?.alert_percent ?: "80") }
    var autoClear by remember(cfg) { mutableStateOf(cfg?.auto_clear ?: false) }
    var clearDate by remember(cfg) { mutableStateOf(cfg?.clear_date ?: "1") }

    // 校准
    var calibrateWay by remember { mutableStateOf("data") }
    var calibrateValue by remember { mutableStateOf("") }
    var calibrateUnit by remember { mutableStateOf("GB") }

    LaunchedEffect(state.successMessage) {
        if (state.successMessage != null) {
            kotlinx.coroutines.delay(2000)
            viewModel.clearTrafficMessage()
        }
    }

    UfiScreenScaffold(title = "流量管理", navController = navController, showBack = true) { padding ->
        UfiScrollableColumn(modifier = Modifier.padding(padding)) {
            state.errorMessage?.let { err -> UfiErrorBanner(message = err) }
            state.successMessage?.let { msg ->
                UfiSettingsGroup {
                    Text(msg, color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (state.isLoading && cfg == null) {
                UfiSettingsGroup {
                    Box(Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            // ── 卡片 1：本月用量（Dashboard 风格） ──
            UfiSettingsGroup {
                UfiSectionHeader(title = "本月用量")

                val monthRx = cfg?.monthly_rx_bytes ?: 0L
                val monthTx = cfg?.monthly_tx_bytes ?: 0L
                val monthTotal = monthRx + monthTx
                val monthlyTime = cfg?.monthly_time ?: 0L
                val hours = monthlyTime / 3600
                val mins = (monthlyTime % 3600) / 60

                // 双栏统计：下载 / 上传
                Row(modifier = Modifier.fillMaxWidth()) {
                    UfiStatItem(
                        value = FormatUtils.formatBytes(monthRx),
                        label = "下载",
                        valueColor = TrafficDown,
                        modifier = Modifier.weight(1f)
                    )
                    UfiStatItem(
                        value = FormatUtils.formatBytes(monthTx),
                        label = "上传",
                        valueColor = TrafficUp,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(Spacing.Medium))
                UfiDivider()
                Spacer(Modifier.height(Spacing.Small))

                // 双栏统计：总计 / 连接时间
                Row(modifier = Modifier.fillMaxWidth()) {
                    UfiStatItem(
                        value = FormatUtils.formatBytes(monthTotal),
                        label = "总计",
                        modifier = Modifier.weight(1f)
                    )
                    UfiStatItem(
                        value = if (monthlyTime > 0) "${hours}h ${mins}m" else "\u2014",
                        label = "连接时间",
                        modifier = Modifier.weight(1f)
                    )
                }

                // 用量进度条
                if (enabled && limitSize.isNotBlank()) {
                    val limitBytes = parseToBytes(limitSize, limitUnit)
                    if (limitBytes > 0) {
                        val percent = (monthTotal * 100 / limitBytes).toInt().coerceAtMost(100)
                        val threshold = alertPercent.toIntOrNull() ?: 80

                        Spacer(Modifier.height(Spacing.Medium))
                        UfiDivider()
                        Spacer(Modifier.height(Spacing.Small))

                        ProgressBar(
                            progress = percent / 100f,
                            label = "月用量",
                            value = "${percent}% (${FormatUtils.formatBytes(monthTotal)} / ${limitSize} ${limitUnit})",
                            color = if (percent >= threshold) SignalDead else SignalExcellent
                        )
                    }
                }
            }

            // ── 卡片 2：限额设置（合并单卡片 + 分区） ──
            UfiSettingsGroup {
                SettingsToggle(
                    title = "启用流量限额",
                    description = if (enabled) "已开启流量限额管理" else "已关闭",
                    checked = enabled,
                    onCheckedChange = { enabled = it }
                )

                if (enabled) {
                    Spacer(Modifier.height(Spacing.Medium))
                    UfiDivider()
                    Spacer(Modifier.height(Spacing.Small))

                    UfiSectionHeader(title = "套餐配置")
                    UfiTextField(
                        value = limitSize,
                        onValueChange = { limitSize = it },
                        label = "限额大小"
                    )
                    Spacer(Modifier.height(Spacing.Small))
                    UfiSingleChipSelector(
                        options = listOf("MB" to "MB", "GB" to "GB", "TB" to "TB"),
                        selectedValue = limitUnit,
                        onSelect = { limitUnit = it }
                    )

                    Spacer(Modifier.height(Spacing.Medium))
                    UfiDivider()
                    Spacer(Modifier.height(Spacing.Small))

                    UfiSectionHeader(title = "告警阈值")
                    UfiDigitField(
                        value = alertPercent,
                        onValueChange = { alertPercent = it },
                        label = "告警百分比 (0-100)",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.Small))
                    Text(
                        "当用量达到此百分比时触发告警",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(Spacing.Medium))
                    UfiDivider()
                    Spacer(Modifier.height(Spacing.Small))

                    SettingsToggle(
                        title = "自动清零",
                        description = if (autoClear) "每月自动重置流量统计" else "手动管理",
                        checked = autoClear,
                        onCheckedChange = { autoClear = it }
                    )
                    if (autoClear) {
                        Spacer(Modifier.height(Spacing.Medium))
                        UfiDigitField(
                            value = clearDate,
                            onValueChange = { clearDate = it },
                            label = "清零日期 (1-31)",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.Medium))
                UfiDivider()
                Spacer(Modifier.height(Spacing.Medium))

                UfiPrimaryButton(
                    text = "保存设置",
                    loading = state.isSaving,
                    onClick = {
                        viewModel.saveDataLimit(
                            enabled = enabled,
                            limitSize = buildCompoundSize(limitSize, limitUnit),
                            limitUnit = "MB",
                            alertPercent = alertPercent,
                            autoClear = autoClear,
                            clearDate = clearDate
                        )
                    }
                )
            }

            // ── 卡片 3：流量校准 ──
            UfiSettingsGroup {
                UfiSectionHeader(title = "流量校准")
                Text(
                    "手动设置设备已用流量值",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.Medium))

                UfiSingleChipSelector(
                    options = listOf("data" to "流量", "time" to "时间"),
                    selectedValue = calibrateWay,
                    onSelect = { calibrateWay = it }
                )
                Spacer(Modifier.height(Spacing.Medium))

                if (calibrateWay == "data") {
                    UfiDigitField(
                        value = calibrateValue,
                        onValueChange = { calibrateValue = it },
                        label = "已用流量",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.Medium))
                    UfiSingleChipSelector(
                        options = listOf("MB" to "MB", "GB" to "GB"),
                        selectedValue = calibrateUnit,
                        onSelect = { calibrateUnit = it }
                    )
                } else {
                    UfiDigitField(
                        value = calibrateValue,
                        onValueChange = { calibrateValue = it },
                        label = "已用时间 (秒)",
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(Spacing.Medium))
                UfiDivider()
                Spacer(Modifier.height(Spacing.Medium))

                UfiSecondaryButton(
                    text = "执行校准",
                    enabled = !state.isSaving && calibrateValue.isNotBlank(),
                    onClick = {
                        val data = if (calibrateWay == "data") {
                            parseToBytes(calibrateValue, calibrateUnit).toString()
                        } else calibrateValue
                        viewModel.calibrateFlow(calibrateWay, data,
                            if (calibrateWay == "time") calibrateValue else "0")
                    }
                )
            }
        }
    }
}

private fun parseToBytes(value: String, unit: String): Long {
    val num = value.toDoubleOrNull() ?: return 0
    return when (unit.uppercase()) {
        "KB" -> (num * 1024).toLong()
        "MB" -> (num * 1048576).toLong()
        "GB" -> (num * 1073741824).toLong()
        "TB" -> (num * 1099511627776).toLong()
        else -> num.toLong()
    }
}
