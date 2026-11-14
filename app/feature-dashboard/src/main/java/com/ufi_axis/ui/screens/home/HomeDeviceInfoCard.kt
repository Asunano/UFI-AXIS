package com.ufi_axis.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * ## 2026-09-22 C1「纯层级」方案
 *
 * 三段式，靠字号差（20sp / 13sp / 11sp）和一条分隔线分层，不使用渐变、色块或图标：
 *
 * ```
 *  ZTE MU5001                    ← 20sp 标题级
 *  Android 13 · SDK 33           ← 13sp 副信息
 *  ─────────────────────────────
 *  QCI        下行       上行     ← 三列等宽 UfiInfoCell
 *  8          500 Mbps   100 Mbps
 *  ─────────────────────────────
 *  固件版本                       ← 长值独占整行
 *  MU5001_V1.0.0B05
 *  内核
 *  5.4.147-perf-g9f2c1b8
 * ```
 *
 * ## QoS 缺失时
 * AT 通道不可用（非展锐平台 / HAL 被裁）时 QoS 区**整段不渲染**（含它上面那条分隔线），
 * 卡片退化成"型号 + 副信息 + 分隔线 + 固件 + 内核"，不留空。
 *
 * ## 5G 胶囊
 * 预览里的 `5G` 描边胶囊**没有接入**：那个值来自信号那边的 `rat` 字段（`network:signal`），
 * 不在这张卡现有的数据源（`/api/dashboard/summary` + `/api/device/version` + `/api/device/qos`）里。
 * 接进来要改 `DashboardState` 的取数逻辑。留了 TODO 注释，你说要的时候我再接。
 */
@Composable
fun HomeDeviceInfoCard(
    state: DashboardState,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val cardShape = UfiCardDefaults.shape

    val device = state.deviceInfo?.device

    // ── 数据 ──

    val deviceModel = buildString {
        append(device?.brand ?: "")
        append(" ")
        append(device?.model ?: "")
    }.trim().ifEmpty { PLACEHOLDER }

    val systemVersion = device?.let { "Android ${it.android_version} · SDK ${it.sdk_version}" }
        ?: PLACEHOLDER

    val firmware = state.deviceVersion?.cr_version?.trim().orEmpty().ifEmpty { PLACEHOLDER }
    val kernel = state.deviceInfo?.kernel?.trim().orEmpty().ifEmpty { PLACEHOLDER }

    val qos = state.deviceQos

    // ── 卡片外壳 ──

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
                .padding(horizontal = 18.dp, vertical = 17.dp)
        ) {
            // ── 头部：型号 + 副信息 ──

            // TODO：5G 胶囊（从信号的 rat 字段接入后，在标题右侧放一个 accent 描边的圆角标签）
            Text(
                text = deviceModel,
                style = UfiTextStyles.bodyLeadStrong.copy(fontSize = TITLE_SIZE),
                color = palette.textPrimary,
                maxLines = 1
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = systemVersion,
                style = UfiTextStyles.noteLead,
                color = palette.textSecondary,
                maxLines = 1
            )

            // ── QoS 区（有数据才渲染）──

            if (qos != null && qos.hasData) {
                Spacer(Modifier.height(SECTION_GAP))
                Divider(palette.divider)
                Spacer(Modifier.height(SECTION_GAP))

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
                        value = qos.downlink_display.ifEmpty { PLACEHOLDER }
                    )
                    UfiInfoCell(
                        modifier = Modifier.weight(1f),
                        label = "上行",
                        value = qos.uplink_display.ifEmpty { PLACEHOLDER }
                    )
                }
            }

            // ── 固件 + 内核 ──

            Spacer(Modifier.height(SECTION_GAP))
            Divider(palette.divider)
            Spacer(Modifier.height(SECTION_GAP))

            UfiInfoCell(
                modifier = Modifier.fillMaxWidth(),
                label = "固件版本",
                value = firmware
            )
            Spacer(Modifier.height(ITEM_GAP))
            UfiInfoCell(
                modifier = Modifier.fillMaxWidth(),
                label = "内核",
                value = kernel
            )
        }
    }
}

/** 卡内分隔线。1dp 实线，不用 M3 的 `HorizontalDivider`（那个带 padding 约定，行为不受控）。 */
@Composable
private fun Divider(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

/** 标题字号。20sp 在标题层级里介于 headerTitle(26sp) 和 panelTitle(16sp) 之间。 */
private val TITLE_SIZE = 20.sp
/** 分隔线上下间距。 */
private val SECTION_GAP = 14.dp

/** 同一区内信息格之间的纵向间距。 */
private val ITEM_GAP = 12.dp

private const val PLACEHOLDER = "—"
