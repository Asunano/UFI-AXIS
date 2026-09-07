// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight

/**
 * 带标题行的单值滑块：`label` / `valueLabel` 一行 + 轨道。
 *
 * ## 2026-09-04（P4e 滑块收敛）：轨道不再是 Material3 `Slider`
 * 收敛前本组件包的是 M3 `Slider`，只换了 `SliderDefaults.colors`，于是它是全站唯一
 * 视觉不受控的滑块：
 * - **thumb 形状/尺寸、轨道高度、圆角全部由 M3 默认值决定** —— Compose BOM 一升，
 *   thumb 就可能从圆点变成 M3 Expressive 的竖条 pill，而同一个页面里的
 *   [UfiRangeSlider] / [UfiValueSlider] 是自绘的实心圆 + 内嵌环，两者对不上。
 * - `steps > 0` 时 M3 在轨道内画 tick 点，而 `SliderDefaults.colors` 里
 *   **`activeTickColor` / `inactiveTickColor` 没被覆盖** —— 那两个色不跟主题（G3 的残留点）。
 * - 拖动没有 thumb 放大反馈，只有 M3 默认 ripple。
 *
 * 现在轨道直接委托给 [UfiValueSlider]（`UfiRangeSlider.kt` 里那套自绘实现），
 * 因此全站单值/双值滑块**只剩一套画法**：6dp 圆角轨道 + accent 15% 底 + 实心 accent thumb
 * 内嵌 `onAccent` 环 + 拖动放大 1.2 倍 + 刻度标签拖动时淡入。
 *
 * 同时删掉了两个死参数 `showTickLabels` / `tickLabelFormatter` —— 全库零调用点，
 * 且它们那套「轨道下方 `Row(SpaceBetween)` 排文字」与轨道上真实 tick 的像素位置本来就对不齐
 * （SpaceBetween 排的是文本盒，tick 排的是轨道分点，两端还差一个 thumb 半径的内缩）。
 * 需要刻度标签请直接用 [UfiValueSlider] 的 `tickStep` + `tickLabelFormatter`（自绘，位置精确）。
 *
 * @param steps 把 [valueRange] 切分的段数；0 表示连续拖动。>0 时同时按档吸附并画出刻度点
 *   （刻度步长由 steps 反推，见下方 `tickStep`），与收敛前 M3 的 tick 行为一致。
 * @param label 左侧标题；传空串会渲染一个零宽 Text，效果是把 [valueLabel] 顶到行尾
 *   （`AppearanceSettingsScreen` 的两处正是这么用的，故保留 `""` 与 `null` 的行为差异）。
 * @param valueLabel 右侧的当前值文案（accent 色加粗）。
 * @param enabled false 时不接手势、轨道与 thumb 一并淡化。
 * @param onValueChangeFinished 抬手时回调一次（拖动中只改草稿、松手才提交的场景）。
 */
@Composable
fun UfiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    label: String? = null,
    valueLabel: String? = null,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current

    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null || valueLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (label != null) {
                    Text(
                        text = label,
                        style = UfiTextStyles.bodyEmphasis,
                        color = palette.textPrimary
                    )
                }
                if (valueLabel != null) {
                    Text(
                        text = valueLabel,
                        style = UfiTextStyles.body.copy(fontWeight = UfiWeight.Emphasis),
                        color = palette.accent
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        UfiValueSlider(
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange,
            // ⚠ 两套组件的 `steps` 语义不同，这里必须 +1 换算，否则所有分档滑块的吸附点会错位：
            // - 本组件沿用 **M3 `Slider` 的定义**：steps = 端点之间的中间停靠点数 → 分 steps+1 段。
            //   （TunnelSettings 的 10..120 传 steps=10 就是靠 11 段得到 10/20/…/120 这一串整十。）
            // - [UfiValueSlider] 的 `snapToStep` 直接用 `(max-min)/steps` → steps = **段数**。
            //   （NotificationsGuard 的 0..23 传 steps=23 得到整点小时，正是段数语义。）
            // 原样传会让 10..120 变成 10/21/32…、150..600 变成 56.25 一档。收敛前是 M3 在算，
            // 现在由本行负责把 M3 语义翻译成段数语义，公开签名与全部调用点因此无需改动。
            steps = if (steps > 0) steps + 1 else 0,
            // 刻度步长 = 一段的长度，使刻度点正好落在每个档位上
            // （computeTickValues 还会把过密的刻度按整数倍抽稀到 ≈6 个）。
            // steps=0（连续拖动）时不画刻度，与收敛前 M3 的行为一致。
            tickStep = if (steps > 0) {
                (valueRange.endInclusive - valueRange.start) / (steps + 1)
            } else 0f,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            // 撑起触摸区：手势区 = 整个 Canvas，无刻度时轨道行只有 24dp。44dp 沿用收敛前
            // 本组件 Column 的 defaultMinSize 下限，观感与可点性都不回退。
            minTrackRowHeight = 44.dp
        )
    }
}
