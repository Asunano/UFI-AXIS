package com.ufi_axis.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.UfiInfoCell
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiCardShadow
import com.ufi_axis.viewmodel.state.DashboardState

/**
 * 设备信息卡（首页四环下方）。
 *
 * ## 2026-09-22 改版：从「图标 + 标签 + 值」单行左右分列改为 **2 列信息格**
 *
 * 旧版每行都是 `Icon | label | ………… value`，左右分列。问题不在于好不好看，而在于
 * **值那一侧只剩半屏宽**：固件版本、内核这种长值一进来就被挤成省略号。当时的应对是
 * 往值里塞一个 `\n` 把固件版本拆成两行 + `maxLines = 2`，结果第二段（`wa_inner_version`）
 * 照样被截断，还把那一行撑成别人的两倍高。
 *
 * 现在按公共件 [UfiInfoCell] 的既有分工来排（那份 KDoc 里写明了这条）：
 * - **短值**（型号 / 系统版本 / QoS 三项）→ 2~3 列并排，标签在上值在下，每格独占整格宽度；
 * - **长值**（固件版本 / 内核）→ **独占一整行**，拿到整卡宽度。
 *
 * 顺带去掉了图标：8 个 accent 色图标竖着排下来比数据本身更抢眼，而"设备型号""内核"
 * 这些标签本身已经说清了是什么，图标没有增加信息。
 *
 * ## 删掉了「运行时间」与「SIM 卡」两行（2026-09-22）
 * 按需求移除。数据源（`state.uptimeInfo` / `state.deviceInfo.sim`）仍在 summary 里，
 * 别处要用不受影响。
 *
 * ## 新增「承载 QoS」一组
 * QCI / 下行 / 上行 来自 `AT+CGEQOSRDP`（core 侧 `/api/device/qos` + `CgeqosrdpParser`）。
 * 单位换算与文案格式化都在 core 做，这里只渲染 —— 两端各写一份 kbps→Mbps 迟早对不上。
 * AT 通道不可用（非展锐平台等）时整组不渲染，而不是显示三个"—" —— 三个空格子只会让人
 * 以为是加载失败，而它其实是"这台设备没这个能力"。
 *
 * 容器样式沿用同包卡片的
 * `Box + ufiCardShadow(4.dp) + clip + background(cardBg) + border(1.dp, divider)`；
 * 内部是普通 [Column]（非滚动），符合 `UfiPageBackground` 不得再套滚动容器的约束。
 */
@Composable
fun HomeDeviceInfoCard(
    state: DashboardState,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val cardShape = UfiCardDefaults.shape

    val device = state.deviceInfo?.device

    val deviceModel = buildString {
        append(device?.brand ?: "")
        append(" ")
        append(device?.model ?: "")
    }.trim().ifEmpty { PLACEHOLDER }

    // 2026-09-22：只留 cr_version。原来把 wa_inner_version 用 "\n" 接在后面，
    // 而值那一侧当时只有半屏宽 + maxLines=2 —— 第二段必然被省略号截断，
    // 等于用双倍行高换来一段读不全的文字。内部版本号对首页读者没有意义。
    val firmware = state.deviceVersion?.cr_version?.trim().orEmpty().ifEmpty { PLACEHOLDER }

    val systemVersion = device?.let { "Android ${it.android_version} (SDK ${it.sdk_version})" }
        ?: PLACEHOLDER
    val kernel = state.deviceInfo?.kernel?.trim().orEmpty().ifEmpty { PLACEHOLDER }

    val qos = state.deviceQos

    Box(
        modifier = modifier
            .fillMaxWidth()
            .ufiCardShadow(elevation = 4.dp, shape = cardShape)
            .clip(cardShape)
            .background(palette.cardBg, cardShape)
            .border(width = 1.dp, color = palette.divider, shape = cardShape)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Large, vertical = Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Large)
        ) {
            // ── 设备信息 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Large)
            ) {
                UfiInfoCell(
                    modifier = Modifier.weight(1f),
                    label = "设备型号",
                    value = deviceModel
                )
                UfiInfoCell(
                    modifier = Modifier.weight(1f),
                    label = "系统版本",
                    value = systemVersion
                )
            }
            // 长值独占整行：这两项在半屏宽下必然省略号（见本文件 KDoc）
            UfiInfoCell(
                modifier = Modifier.fillMaxWidth(),
                label = "固件版本",
                value = firmware
            )
            UfiInfoCell(
                modifier = Modifier.fillMaxWidth(),
                label = "内核",
                value = kernel
            )

            // ── 承载 QoS（AT+CGEQOSRDP）──
            //
            // 查不到就整组不渲染：AT 通道在很多设备上根本不存在，那时显示三个"—"
            // 会被当成加载失败，而它其实是"这台设备没这个能力"。
            if (qos != null && qos.hasData) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.divider)
                )
                Text(
                    text = "承载 QoS",
                    style = UfiTextStyles.note,
                    color = palette.textSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Large)
                ) {
                    UfiInfoCell(
                        modifier = Modifier.weight(1f),
                        label = "QCI",
                        value = qos.qci.toString()
                    )
                    UfiInfoCell(
                        modifier = Modifier.weight(1f),
                        label = "下行",
                        // 文案由 core 生成（"500 Mbps"）；0 / 未协商时 core 给空串
                        value = qos.downlink_display.ifEmpty { PLACEHOLDER }
                    )
                    UfiInfoCell(
                        modifier = Modifier.weight(1f),
                        label = "上行",
                        value = qos.uplink_display.ifEmpty { PLACEHOLDER }
                    )
                }
            }
        }
    }
}

/** 取不到值时的占位。与仓库其它只读信息一致（`UfiInfoRow` 的 `sanitizeUnknown` 也用它）。 */
private const val PLACEHOLDER = "—"
