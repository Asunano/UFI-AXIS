// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.animation.ufiPressScale
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiMotion

@Composable
fun UfiFloatingActionButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    containerColor: androidx.compose.ui.graphics.Color? = null,
    // 2026-09-25（批 O / 设备能力集 3.5）：`false` 时点击被吞掉、整钮按 DISABLED_ALPHA 淡化。
    //
    // ⚠ M3 的 `FloatingActionButton` **没有** `enabled` 参数（设计上 FAB 的主操作
    // 理应始终可用），所以这里只能自己实现：onClick 换成空 lambda + 底色/图标降透明度。
    // 之所以不改成「不显示」：FAB 消失会让用户以为功能被挪走了、去别处找；
    // 淡化留在原位才表达得出"这台设备没有这个能力"。原因文案由调用页另行给出
    // （本组件只有一个圆钮，没有放文字的位置）。
    enabled: Boolean = true
) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }

    FloatingActionButton(
        onClick = { if (enabled) onClick() },
        // 2026-09-04（P2d）：0.92 → UfiMotion.PressScale.Fab（值不变）。FAB 是全站唯一的浮起
        // 圆钮、面积最小，按"面积越小缩得越多"的规则占最深那一档，配 controlPop 的回弹。
        //
        // 2026-09-04（P2f 短按看不见）：原为 collectIsPressedAsState + animateFloatAsState +
        // graphicsLayer。为什么原来短按看不见：animateFloatAsState 只朝"当前目标"走，
        // 抬手瞬间目标翻回 1f 就被反向拉回；即便 FAB 是全站落差最大的一档（0.08），
        // controlPop（spring 0.5/600）在一两帧内也只走出个位数百分比，肉眼无感。
        // 现在怎么保证：ufiPressScale 事件驱动编排，按下相位不足 Duration.Micro（120ms）时
        // 先补播完"缩到位"再弹回，短按也能看见完整的缩→弹回；档位与 spec 原样不变。
        modifier = modifier.ufiPressScale(
            interactionSource = interactionSource,
            pressedScale = UfiMotion.PressScale.Fab,
            spec = UfiMotion.controlPop()
        ),
        shape = UfiCardDefaults.largeSurfaceShape,
        containerColor = (containerColor ?: palette.accent)
            .copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
        contentColor = palette.onAccent.copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
        interactionSource = interactionSource
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * FAB 禁用态的透明度。取 0.4f 与 `UfiButton` 的 `SUBTLE_DISABLED_CONTENT_ALPHA` 同值 ——
 * 同一套界面里"不可用"的浓淡只该有一种，两处数值分叉的话用户会以为是两种不同的状态。
 */
private const val DISABLED_ALPHA = 0.4f
