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
    containerColor: androidx.compose.ui.graphics.Color? = null
) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }

    FloatingActionButton(
        onClick = onClick,
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
        containerColor = containerColor ?: palette.accent,
        contentColor = palette.onAccent,
        interactionSource = interactionSource
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp)
        )
    }
}
