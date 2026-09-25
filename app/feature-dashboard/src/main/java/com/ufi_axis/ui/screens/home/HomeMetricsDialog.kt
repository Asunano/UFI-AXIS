package com.ufi_axis.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ufi_axis.ui.components.common.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.state.DashboardState

/**
 * 首页指标快捷详情弹窗。
 *
 * 取代原先「点击指标环跳转新页面」的导航方式：CPU / 内存 / 电池 / 存储
 * 四项均通过对话框展示关键明细，避免引入孤立的 detail 页面。
 *
 * - CPU：各核心列表可能较长，使用自带滚动的 [UfiScrollableDialog]。
 * - 内存 / 电池 / 存储：内容较短，使用 [UfiCustomDialog]。
 *
 * 颜色统一走 [LocalResolvedPalette]，不硬编码非白色。
 *
 * UI 约定（简洁普通 Material Design）：
 * - 四个分支均直接平铺 [UfiInfoRow] 明细行，顶部不再渲染进度条 / Hero 占用区。
 * - 关键指标（占用率 / 当前电量 / 核心数等）同样以 [UfiInfoRow] 形式置于明细列表顶部。
 * - 信息行之间依靠 [UfiInfoRow] 自带 3dp 纵向 padding；不同逻辑组之间用 8~12dp Spacer 分隔。
 */
@Composable
fun HomeMetricsDialog(
    section: MetricsSection,
    state: DashboardState,
    onDismiss: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    when (section) {
        MetricsSection.SYSTEM -> {
            // CPU 占用 — 各核心列表可能较长，使用自带滚动的弹窗。
            UfiScrollableDialog(
                visible = true,
                onDismiss = onDismiss,
                title = "CPU 占用",
                icon = rememberVectorPainter(Icons.Filled.Memory),
                showCloseButton = false
            ) {
                UfiDialogBody {
                state.cpuInfo?.let { cpu ->
                    val maxFreq = cpu.cores.maxOfOrNull { it.freq_mhz.toInt() } ?: 0

                    UfiInfoRow("当前占用", "${cpu.usage_percent.toInt()}%")
                    UfiInfoRow("核心数", "${cpu.core_count}核")
                    UfiInfoRow("最高频率", "$maxFreq MHz")
                    if (cpu.temperature > 0) {
                        UfiInfoRow("温度", FormatUtils.formatTemperature(cpu.temperature))
                    }

                    UfiSectionHeader("各核心频率")
                    cpu.cores.forEach { core ->
                        UfiInfoRow("核心 ${core.core}", core.freq_display)
                    }
                } ?: Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "暂无 CPU 数据", color = palette.textSecondary)
                }
                } // UfiDialogBody
            }
        }

        MetricsSection.MEMORY -> {
            // 内存详情 — 内容较短，使用普通弹窗。
            UfiCustomDialog(
                visible = true,
                onDismiss = onDismiss,
                title = "内存详情",
                icon = rememberVectorPainter(Icons.Filled.Info),
                showCloseButton = false
            ) {
                UfiDialogBody {
                state.memoryInfo?.let { mem ->
                    UfiInfoRow("占用率", FormatUtils.formatPercent(mem.usage_percent))
                    UfiInfoRow("总量", FormatUtils.formatBytes(mem.total))
                    UfiInfoRow("已用", FormatUtils.formatBytes(mem.used))
                    UfiInfoRow("可用", FormatUtils.formatBytes(mem.available))
                    UfiInfoRow("缓冲", FormatUtils.formatBytes(mem.buffers))
                    UfiInfoRow("缓存", FormatUtils.formatBytes(mem.cached))
                } ?: Text(text = "暂无内存数据", color = palette.textSecondary)
                } // UfiDialogBody
            }
        }

        MetricsSection.STORAGE -> {
            // 存储空间详情 — 内容较短，使用普通弹窗。
            UfiCustomDialog(
                visible = true,
                onDismiss = onDismiss,
                title = "存储空间详情",
                icon = rememberVectorPainter(Icons.Filled.Storage),
                showCloseButton = false
            ) {
                UfiDialogBody {
                state.storageInfo?.let { stor ->
                    UfiInfoRow("占用率", FormatUtils.formatPercent(stor.usage_percent))
                    UfiInfoRow("总量", FormatUtils.formatBytes(stor.total))
                    UfiInfoRow("已用", FormatUtils.formatBytes(stor.used))
                    UfiInfoRow("可用", FormatUtils.formatBytes(stor.available))
                } ?: Text(text = "暂无存储数据", color = palette.textSecondary)
                } // UfiDialogBody
            }
        }

        MetricsSection.BATTERY -> {
            // 电池详情 — 内容较短，使用普通弹窗。
            UfiCustomDialog(
                visible = true,
                onDismiss = onDismiss,
                title = "电池详情",
                icon = rememberVectorPainter(Icons.Filled.BatteryFull),
                showCloseButton = false
            ) {
                UfiDialogBody {
                state.batteryInfo?.let { b ->
                    // core 批M 起：supported == false 表示设备没有声明电池能力（如 F50）。
                    // 此时 percent / is_charging 仍是系统给的值（F50 恒报 50%、插电时可能报 true），
                    // core 对这类设备既不入库也不做电池告警 —— 下面那行说明必须把这点讲出来，
                    // 否则用户会奇怪电池历史图为什么是空的。
                    val batteryUnsupported = !b.supported

                    UfiInfoRow("当前电量", "${b.percent}%")
                    // 无电池能力时不复述系统的充电判断：getBatteryStatus 在 is_charging = true
                    // 时会直接说「充电中」，那是一台没有电池的设备最不该出现的一句话。
                    // 留着行、值给 null（UfiInfoRow 对 null 的既有渲染是长破折号），
                    // 行数与行序和有电池的设备保持一致，不会因机型不同而跳变。
                    UfiInfoRow(
                        "状态",
                        if (batteryUnsupported) null
                        else FormatUtils.getBatteryStatus(b.percent, b.is_charging)
                    )

                    // 所有信息行平铺
                    UfiInfoRow("温度", FormatUtils.formatTemperature(b.temperature))
                    UfiInfoRow("电压", FormatUtils.formatVoltage(b.voltage))
                    UfiInfoRow("充电中", if (batteryUnsupported) null else if (b.is_charging) "是" else "否")
                    UfiInfoRow("电量等级", "${b.level} / ${b.scale}")

                    if (batteryUnsupported) {
                        // 用中性说明（UfiDialogNote）而不是 UfiDialogWarning：这不是故障也不是风险，
                        // 只是这台设备读不到真实电量。橙色警告块会把它说得像出了问题。
                        UfiDialogNote(
                            "本机型可能没有电池，或系统读不到真实电量 —— 上面的数值仅供参考，不做记录与告警。"
                        )
                    }
                } ?: Text(text = "暂无电池数据", color = palette.textSecondary)
                } // UfiDialogBody
            }
        }
    }
}
