package com.ufi_axis.ui.components.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.animation.ufiPressScale
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.UfiMotion

/**
 * 公共动作分类/选择胶囊 (2026-08-18 提取自 TaskScreen.kt)。
 *
 * 视觉风格统一：
 * - 未选中：accent 12% 浅底 + accent 55% 描边 + accent 文字；
 * - 选中：accent 实底 + onAccent 反色文字 + FontWeight.SemiBold。
 *
 * 2026-08-31：配色来源从 `MaterialTheme.colorScheme.primary/onPrimary` 换成
 * `palette.accent/onAccent` —— 全站其余 chip（[UfiSingleChipSelector] / [UfiMultiChipSelector] /
 * [UfiOptionGrid]）都读 palette，只有这里读 colorScheme，同一页面里出现两种主色来源。
 *
 * 动画（消除"切换很生硬"）：
 * - 背景/border/文字颜色用 `animateColorAsState` tween([UfiMotion.Duration.Standard] 220) 平滑过渡；
 * - 按下时缩放至 [UfiMotion.PressScale.Chip]（0.97f，参考 `UfiOptionCell`
 *   已有的同款反馈模式）。2026-09-04（P2d）：0.97 改为引用 token，并把监控页三个私有筛选 chip
 *   （原各写 0.94）对齐到本档 —— 同一种胶囊筛选器不该在不同页面缩不一样多。
 *   2026-09-04（P2f）：播放方式改为 `Modifier.ufiPressScale`，修掉"短按看不见缩放"（原因见
 *   `ui/animation/PressFeedback.kt` 文件头），档位与 tween 时长不变。
 *
 * 签名设计：
 * - 必传：label / selected / onClick；
 * - 可选：modifier / leadingIcon（用于"选择动作"chip 显示 action 图标）；
 * 字段全部带默认值，向后兼容（新增公共组件，不修改任何既有公共签名——项目红线 [F24]）。
 *
 * 适用场景：动作分类、动作选择、排序筛选、标签筛选等任意"互斥/多选 + 强调主色"场景。
 */
@Composable
fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null
) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        targetValue = if (selected) palette.accent else palette.accent.copy(alpha = 0.12f),
        animationSpec = tween(UfiMotion.Duration.Standard),
        label = "CategoryChipBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) palette.accent else palette.accent.copy(alpha = 0.55f),
        animationSpec = tween(UfiMotion.Duration.Standard),
        label = "CategoryChipBorder"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) palette.onAccent else palette.accent,
        animationSpec = tween(UfiMotion.Duration.Standard),
        label = "CategoryChipText"
    )
    Box(
        modifier = modifier
            // 2026-09-04（P2f 短按看不见）：原为 animateFloatAsState + graphicsLayer。
            // 为什么原来短按看不见：那是"跟随目标值"的动画，抬手瞬间目标翻回 1f 就被反向拉回；
            // chip 档落差仅 0.03 配 tween(120)，一帧只走完约 5%（scale ≈ 0.9985），
            // 且 chip 通常处在滚动列表里，Press 与 Release 常在同一帧到达，可用时长为 0。
            // 现在怎么保证：ufiPressScale 事件驱动编排，抬手时不足 120ms 会补播完"缩到位"再弹回。
            // 档位与 spec 原样（PressScale.Chip + tween(Duration.Micro)），长按观感不变。
            .ufiPressScale(
                interactionSource = interactionSource,
                pressedScale = UfiMotion.PressScale.Chip,
                spec = tween(UfiMotion.Duration.Micro)
            )
            .clip(UfiCardDefaults.chipShape)
            .background(bg)
            .border(BorderStroke(1.dp, borderColor), UfiCardDefaults.chipShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = UfiTextStyles.label.copy(
                    fontWeight = if (selected) UfiWeight.Emphasis else UfiWeight.Regular
                ),
                color = textColor
            )
        }
    }
}
