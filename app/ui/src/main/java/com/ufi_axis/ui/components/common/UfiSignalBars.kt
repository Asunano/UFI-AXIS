// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.*

fun signalColor(rsrp: Int?): Color = when {
    rsrp == null -> SignalDead
    rsrp >= -80 -> SignalExcellent
    rsrp >= -90 -> SignalGood
    rsrp >= -100 -> SignalFair
    rsrp >= -115 -> SignalPoor
    else -> SignalDead
}

fun signalBars(rsrp: Int?): Int = when {
    rsrp == null -> 0
    rsrp >= -80 -> 5
    rsrp >= -90 -> 4
    rsrp >= -100 -> 3
    rsrp >= -115 -> 2
    else -> 1
}

fun signalLevelText(rsrp: Int?): String = when {
    rsrp == null -> "未知"
    rsrp >= -80 -> "极好"
    rsrp >= -90 -> "好"
    rsrp >= -100 -> "一般"
    rsrp >= -115 -> "差"
    else -> "极差"
}

// 本组信号读数 API 里，[F24] 冻结范围的**豁免已登记**：
// `docs/app-theme-token-index.md`（信号 / 制式 / 电量三组域色的影响面表）记着
// networkTypeColor / batteryColor 的处置，改动或恢复前先看那处，别只看本文件。
//
// 制式与电量的域色常量（Network5G/4G/3G/2G、BatteryHigh/Medium/Low）**保留**：
// 「域色是否随皮肤」还没定，定了再一并处理。
// signalColor 保留 public：与 signalBars / signalLevelText 是同一组信号读数 API，后两者有外部调用点。

@Composable
fun UfiSignalBars(
    rsrp: Int?,
    modifier: Modifier = Modifier,
    barWidth: Dp = 4.dp,
    maxHeight: Dp = 24.dp
) {
    val bars = signalBars(rsrp)
    val color = signalColor(rsrp)
    val palette = LocalResolvedPalette.current
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        for (i in 1..5) {
            val h = (maxHeight / 5) * i
            Box(
                modifier = Modifier
                    .width(barWidth + 2.dp)
                    .height(h)
                    .padding(horizontal = 1.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = if (i <= bars) color else palette.textSecondary.copy(alpha = 0.15f),
                    shape = UfiCardDefaults.hairlineShape
                ) {}
            }
        }
    }
}

/**
 * 渐变卡背景上的「纯白 5 段梯形信号条」。
 *
 * 2026-08-31：由 `HomeConnectionCard` 与 `NetworkScreen` 两份同名私有 `GradientSignalBar`
 * 合并而来 —— 两处实现逻辑完全一致（30%/48%/66%/82%/100% 高度比 + 未激活段
 * `0.15f + index*0.05f` 递增透明度），只有尺寸与排布方式不同，故全部改为参数：
 * - [barWidth] 传 null = 各段 `weight(1f)` 平分容器宽度（NetworkScreen Hero 的用法）；
 *   传具体值 = 固定宽度（首页连接卡的用法）。
 * - [barHeight] 最高那一段的高度，其余按比例缩放。
 *
 * 与 [UfiSignalBars] 的区别：这里是「渐变卡前景色（palette.onGradient / gradientMuted，默认白）
 * + 已算好的激活根数」，用于渐变 Hero 卡；后者是「按 rsrp 自动着色（绿/黄/红）」，用于普通浅色卡片。
 */
@Composable
fun UfiGradientSignalBar(
    bars: Int,
    modifier: Modifier = Modifier,
    segmentCount: Int = 5,
    barHeight: Dp = 12.dp,
    barWidth: Dp? = 3.dp,
    barSpacing: Dp = 2.dp,
    cornerRadius: Dp = 1.5.dp
) {
    val activeBars = bars.coerceIn(0, segmentCount)
    val heightFractions = listOf(0.30f, 0.48f, 0.66f, 0.82f, 1.0f)
    // 2026-09-04（P2-中）：P1c 当时判定"这两处白刻意不接 palette"，理由是同卡还有约 40 处白
    // 独立改一处会更不一致 —— 现在那 40 处已成套收敛到 onGradient / gradientMuted，
    // 这两处随之接上：激活段是不透明前景（onGradient），未激活段是弱化装饰（gradientMuted +
    // 原有 0.15~0.35 递增 alpha）。两槽默认值都是 Color.White，观感与改造前逐位一致。
    val palette = LocalResolvedPalette.current
    Row(
        modifier = if (barWidth == null) {
            modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom)
        } else {
            modifier.height(barHeight)
        },
        horizontalArrangement = Arrangement.spacedBy(barSpacing),
        verticalAlignment = Alignment.Bottom
    ) {
        repeat(segmentCount) { index ->
            val isActive = index < activeBars
            val inactiveAlpha = 0.15f + (index * 0.05f)
            val fraction = heightFractions.getOrElse(index) { 1.0f }
            Box(
                modifier = (if (barWidth == null) Modifier.weight(1f) else Modifier.width(barWidth))
                    .height(barHeight * fraction)
                    .clip(RoundedCornerShape(cornerRadius))
                    // 2026-09-04（P2-中）：原写死 `if (isActive) Color.White else Color.White.copy(alpha = …)`。
                    // 铺底是 HomeConnectionCard / NetworkScreen Hero 的渐变卡，渐变来自 accent，
                    // 换配色时底色会变、这两处白不变 ⇒ 浅色 accent 主题下信号条糊在底上（G3 残留）。
                    // 现在与整卡其余 40 处一起接 palette：激活段 = onGradient，未激活段 = gradientMuted +
                    // 原有递增 alpha（0.15 + index*0.05，档位仍留在调用点）。默认值均为白，观感零变化。
                    .background(
                        if (isActive) palette.onGradient
                        else palette.gradientMuted.copy(alpha = inactiveAlpha)
                    )
            )
        }
    }
}
