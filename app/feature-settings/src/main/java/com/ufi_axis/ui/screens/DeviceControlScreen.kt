package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.ufi_axis.ui.components.common.DialogButtonRow
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiOptionGrid
import com.ufi_axis.ui.components.common.UfiOptionItem
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsChevron
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.components.common.UfiSettingsToggle
import com.ufi_axis.ui.components.common.UfiSwitch
import com.ufi_axis.ui.components.common.UfiTextField
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiCardShadow
import androidx.navigation.NavHostController
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 设备控制页（v4 2026-08-23：开关型入口右侧 Switch，去弹窗）
 *
 * 历史：v3 4 个设置项以"入口卡片"展示，点击行 → 弹 UfiCustomDialog 显示详细操作
 *   （Switch / ChipSelector / TimePicker）。v4 按用户偏好精简——
 *   ① 指示灯 / 性能模式：右侧直接 Switch，立即生效，不再弹 Dialog；
 *   ② 定时重启：右侧 Switch 控制总开关（立即生效），行非 Switch 区域点击进入
 *      Dialog 调整时间（总开关已开启后才可调时间）；
 *   ③ WiFi 休眠：保留右侧 chevron + Dialog（7 个时间选项，参数化）。
 *
 * 视觉风格走公共设置行卡（UfiSettingsRowCard + UfiSettingsItem / UfiSettingsToggle）。
 */
@Composable
fun DeviceControlScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    showHeader: Boolean = true
) {
    val deviceSettingsState by viewModel.deviceSettingsState.collectAsState()

    // Device settings state
    var ledOn by remember { mutableStateOf(true) }
    var perfOn by remember { mutableStateOf(false) }
    // core 契约：wifi 休眠单位是分钟，"0" = 不休眠；负数会被 core 的 validate 拒成 400 OUT_OF_RANGE
    var wifiSleepTime by remember { mutableStateOf("0") }
    var restartScheduleOn by remember { mutableStateOf(false) }
    var restartTime by remember { mutableStateOf("00:00") }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        viewModel.network.loadDeviceSettings()
    }

    LaunchedEffect(deviceSettingsState.settings) {
        deviceSettingsState.settings?.let { s ->
            ledOn = s.indicatorLightOn
            perfOn = s.performanceModeOn
            s.wifiSleepIdleMinutes?.let { wifiSleepTime = it }
            restartScheduleOn = s.restartScheduleOn
            s.restartTime?.let { restartTime = it }
        }
    }

    // v3：当前打开的弹窗类型（null = 无弹窗）
    var openDialog by remember { mutableStateOf<DeviceDialog?>(null) }

    // v3 滑块样式：左右 padding 16dp + track 背景着色（关闭时淡 brand 色）
    // 2026-08-31：原先在这里 remember 一份 SwitchDefaults.colors 供入口行卡与 Dialog 共用，
    // 现在开关统一走公共 UfiSwitch（内部直接读 palette.switch* token），这份配色不再需要。
    val palette = LocalResolvedPalette.current


    UfiScreenScaffold(title = "设备控制", navController = navController, showBack = true, showHeader = showHeader) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 行卡容器与行布局统一走公共组件（2026-08-30：原来本页手搓 rowModifier 四链 +
                // Row/Icon/Column/Text/Switch，与其它设置页各写一份）。
                // ═══ 入口 1：指示灯（v4 右侧 Switch，立即生效，无弹窗）═══
                UfiSettingsRowCard {
                    UfiSettingsToggle(
                        icon = AppIconLed,
                        title = "指示灯",
                        description = if (ledOn) "已开启" else "已关闭",
                        checked = ledOn,
                        onCheckedChange = {
                            ledOn = it
                            viewModel.network.setLedEnabled(it)
                        }
                    )
                }

                // ═══ 入口 2：性能模式（v4 右侧 Switch，立即生效，无弹窗）═══
                UfiSettingsRowCard {
                    UfiSettingsToggle(
                        icon = AppIconPerformance,
                        title = "性能模式",
                        description = if (perfOn) "高性能 · CPU 最大频率" else "均衡 · 自动调节",
                        checked = perfOn,
                        onCheckedChange = {
                            perfOn = it
                            viewModel.network.setPerformanceMode(if (it) "performance" else "balanced")
                        }
                    )
                }

                // ═══ 入口 3：WiFi 休眠（保留 chevron + Dialog：7 个时间选项）═══
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = AppIconWifiSleep,
                        title = "WiFi 休眠",
                        description = when (wifiSleepTime) {
                            "0" -> "从不"
                            // 旧设备/旧缓存里可能还残留 -1，一并显示成「从不」（写回时只用 "0"）
                            "-1" -> "从不"
                            "5" -> "5 分钟后"
                            "10" -> "10 分钟后"
                            "20" -> "20 分钟后"
                            "30" -> "30 分钟后"
                            "60" -> "1 小时后"
                            "120" -> "2 小时后"
                            else -> "$wifiSleepTime 分钟后"
                        },
                        onClick = { openDialog = DeviceDialog.WIFI_SLEEP },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                // ═══ 入口 4：定时重启（右侧 Switch 总开关 + 点行进 Dialog 调时间）═══
                UfiSettingsRowCard {
                    UfiSettingsToggle(
                        icon = AppIconScheduleRestart,
                        title = "定时重启",
                        description = if (restartScheduleOn) "每日 $restartTime" else "已关闭",
                        checked = restartScheduleOn,
                        onCheckedChange = {
                            restartScheduleOn = it
                            viewModel.network.setRestartSchedule(it, restartTime)
                        },
                        onClick = { openDialog = DeviceDialog.RESTART_SCHEDULE }
                    )
                }

                Spacer(Modifier.height(Spacing.Large))
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 弹窗区（v4：仅保留 WiFi 休眠 / 定时重启两个 Dialog；
    //   指示灯 / 性能模式已改为行内 Switch 即时生效，不再弹窗）
    // ════════════════════════════════════════════════════════════════

    when (openDialog) {
        DeviceDialog.WIFI_SLEEP -> {
            // v3：暂存-确认模式
            var wifiSleepDraft by remember { mutableStateOf(wifiSleepTime) }
            UfiCustomDialog(
                visible = true,
                onDismiss = { openDialog = null },
                title = "WiFi 休眠",
                icon = rememberVectorPainter(Icons.Filled.Wifi),
                confirmButton = null,
                dismissButton = null,
                showCloseButton = false
            ) {
                Text(
                    "设置无数据传输后多久关闭 WiFi 节省电量。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
                Spacer(Modifier.height(Spacing.Medium))
                // 2026-08-18：私货回收为公共组件 UfiOptionGrid（视觉升级：橙描边+橙字+12% 浅橙底选中，
                // 与 TaskScreen「模式」段完全一致；替代原先 30 行自绘 FilterChip 双栏 grid）
                val wifiSleepOptions = listOf(
                    "0" to "从不", "5" to "5 分钟", "10" to "10 分钟",
                    "20" to "20 分钟", "30" to "30 分钟", "60" to "1 小时", "120" to "2 小时"
                )
                UfiOptionGrid(
                    options = wifiSleepOptions.map { (value, label) -> UfiOptionItem(value = value, label = label) },
                    selectedValue = wifiSleepDraft,
                    onSelect = { wifiSleepDraft = it },
                    columns = 2
                )
                Spacer(Modifier.height(Spacing.Medium))
                DialogButtonRow(
                    confirmText = "确认",
                    onConfirm = {
                        if (wifiSleepDraft != wifiSleepTime) {
                            wifiSleepTime = wifiSleepDraft
                            viewModel.network.setWifiSleep(wifiSleepDraft)
                        }
                        openDialog = null
                    },
                    dismissText = "取消",
                    onDismiss = { openDialog = null }
                )
            }
        }

        DeviceDialog.RESTART_SCHEDULE -> {
            // v3：暂存-确认模式（Switch + 时间都暂存，确认才生效）
            val timeRegex = remember { Regex("^(0?[0-9]|1[0-9]|2[0-3]):(0?[0-9]|[1-5][0-9])$") }
            var restartOnDraft by remember { mutableStateOf(restartScheduleOn) }
            var restartTimeDraft by remember { mutableStateOf(restartTime) }
            var timeError by remember { mutableStateOf<String?>(null) }
            UfiCustomDialog(
                visible = true,
                onDismiss = { openDialog = null },
                title = "定时重启",
                icon = rememberVectorPainter(Icons.Filled.Schedule),
                confirmButton = null,
                dismissButton = null,
                showCloseButton = false
            ) {
                Text(
                    "开启后设备每日定时自动重启。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
                Spacer(Modifier.height(Spacing.Medium))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("启用", style = UfiTextStyles.listItemTitle, color = palette.textPrimary)
                    // 2026-08-31：M3 Switch + switchColors → 公共 UfiSwitch
                    UfiSwitch(
                        checked = restartOnDraft,
                        onCheckedChange = { restartOnDraft = it }
                    )
                }
                if (restartOnDraft) {
                    Spacer(Modifier.height(Spacing.Small))
                    UfiTextField(
                        value = restartTimeDraft,
                        onValueChange = {
                            restartTimeDraft = it
                            timeError = if (it.isNotEmpty() && !timeRegex.matches(it))
                                "格式: HH:MM（00:00–23:59）" else null
                        },
                        label = "重启时间",
                        placeholder = "00:00",
                        isError = timeError != null,
                        errorMessage = timeError
                    )
                    // v3（2026-08-11）：删除冗余"保存时间"按钮——点击底部"完成"统一触发保存+关闭
                }
                Spacer(Modifier.height(Spacing.Medium))
                DialogButtonRow(
                    confirmText = "确认",
                    onConfirm = {
                        if (timeError == null) {
                            if (restartOnDraft != restartScheduleOn || restartTimeDraft != restartTime) {
                                restartScheduleOn = restartOnDraft
                                restartTime = restartTimeDraft
                                viewModel.network.setRestartSchedule(restartOnDraft, restartTimeDraft)
                            }
                            openDialog = null
                        }
                    },
                    dismissText = "取消",
                    onDismiss = { openDialog = null },
                    enabled = timeError == null
                )
            }
        }

        null -> Unit
    }
}

/** v4 设备控制页弹窗类型枚举（v4：仅保留 WiFi 休眠 / 定时重启；指示灯 / 性能模式已用行内 Switch 替代） */
private enum class DeviceDialog { WIFI_SLEEP, RESTART_SCHEDULE }


// ───────────────────────────────────────────────────────────
// v3（2026-08-11）：手绘 SVG 矢量图标（替代 Material 图标，风格统一）
// 单色 path（Color.Black 会被 Icon tint 覆盖），24×24 viewport
// ───────────────────────────────────────────────────────────

/** LED 指示灯：灯泡 + 底座 */
private val AppIconLed: ImageVector = ImageVector.Builder(
    name = "AppIconLed", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 2f)
        curveTo(9f, 2f, 7f, 4.6f, 7f, 7.5f)
        curveTo(7f, 9.5f, 7.9f, 10.7f, 8.8f, 11.9f)
        curveTo(9.3f, 12.6f, 10f, 13f, 10f, 14f)
        lineTo(14f, 14f)
        curveTo(14f, 13f, 14.7f, 12.6f, 15.2f, 11.9f)
        curveTo(16.1f, 10.7f, 17f, 9.5f, 17f, 7.5f)
        curveTo(17f, 4.6f, 15f, 2f, 12f, 2f)
        close()
    }
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
        moveTo(9f, 18f); lineTo(15f, 18f)
        moveTo(10f, 21f); lineTo(14f, 21f)
    }
}.build()

/** 性能模式：仪表盘（弧 + 指针） */
private val AppIconPerformance: ImageVector = ImageVector.Builder(
    name = "AppIconPerformance", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round) {
        moveTo(4f, 15f)
        arcTo(8f, 8f, 0f, false, false, 20f, 15f)
    }
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round) {
        moveTo(12f, 15f); lineTo(15f, 9f)
        moveTo(12f, 15f); lineTo(10f, 15f)
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 15f)
        lineTo(15f, 9f)
        lineTo(13f, 15f)
        close()
    }
}.build()

/** WiFi 休眠：3 条 WiFi 弧 + 右上月牙 */
private val AppIconWifiSleep: ImageVector = ImageVector.Builder(
    name = "AppIconWifiSleep", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round) {
        moveTo(2f, 6f)
        arcTo(18f, 18f, 0f, false, true, 22f, 6f)
        moveTo(5f, 10f)
        arcTo(12f, 12f, 0f, false, true, 19f, 10f)
        moveTo(8f, 14f)
        arcTo(6f, 6f, 0f, false, true, 16f, 14f)
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 17f)
        arcToRelative(1.5f, 1.5f, 0f, true, false, 0f, 3f)
        arcToRelative(1.5f, 1.5f, 0f, true, false, 0f, -3f)
        close()
    }
    // 月牙（右上）
    path(fill = SolidColor(Color.Black)) {
        moveTo(17f, 1f)
        arcTo(4f, 4f, 0f, true, false, 23f, 5f)
        arcTo(4f, 4f, 0f, true, false, 17f, 1f)
        close()
    }
}.build()

/** 定时重启：时钟 + 循环箭头 */
private val AppIconScheduleRestart: ImageVector = ImageVector.Builder(
    name = "AppIconScheduleRestart", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round) {
        moveTo(12f, 21f)
        arcTo(8f, 8f, 0f, true, true, 20f, 13f)
    }
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round) {
        moveTo(12f, 9f); lineTo(12f, 13f); lineTo(15f, 14.5f)
    }
    // 顶部循环箭头
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round) {
        moveTo(20f, 1f); lineTo(20f, 5f); lineTo(16f, 5f)
    }
}.build()

/** 右箭头 chevron */
private val AppIconChevron: ImageVector = ImageVector.Builder(
    name = "AppIconChevron", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round) {
        moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f)
    }
}.build()
