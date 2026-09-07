// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults

/**
 * 底部输入栏的「卡壳」—— 圆角卡片 + 聚焦时描边转 accent、阴影抬升。
 *
 * 2026-08-31 批 3：`SmsScreen.ChatInputBar` 与 `AdvancedConsoleScreen.ConsoleInputBar`
 * 各写了一份逐行相同的外壳（`Card` + `shadow` + 1dp 动画描边 + `cardBg` + 内层 `Row(h12,v8)`），
 * 这里只抽外壳与发送键，**文本域仍留在各页面 private** —— 两者的输入行为差异太大
 * （单行等宽命令行 + Enter 直发 vs 多行短信正文 + Enter 换行），塞进同一个组件只会造出参数怪物。
 *
 * @param focused 是否聚焦。描边色与阴影都由它驱动（调用方自己用 `onFocusChanged` 维护）
 * @param focusedElevation / [restingElevation] 聚焦 / 静置阴影。两个调用点历史值不同（8/4 与 6/2），
 *   故作为参数暴露而不是写死，避免这次收敛顺手改了观感
 * @param verticalAlignment 内容行对齐。控制台单行居中，短信多行贴底
 * @param content 输入栏内容（文本域 + [UfiSendButton]），已在 `Row` 作用域内
 */
@Composable
fun UfiInputBarCard(
    focused: Boolean,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = UfiCardDefaults.iconTileShape,
    focusedElevation: Dp = 8.dp,
    restingElevation: Dp = 4.dp,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit
) {
    val palette = LocalResolvedPalette.current
    val borderColor by animateColorAsState(
        targetValue = if (focused) palette.accent.copy(alpha = 0.55f) else palette.divider.copy(alpha = 0.3f),
        animationSpec = tween(UfiMotion.Duration.Standard), label = "inputBarBorder"
    )
    val elevation by animateDpAsState(
        targetValue = if (focused) focusedElevation else restingElevation,
        animationSpec = tween(UfiMotion.Duration.Standard), label = "inputBarElevation"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation, shape)
            .border(1.dp, borderColor, shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = palette.cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = verticalAlignment,
            content = content
        )
    }
}

/**
 * 输入栏右侧的圆角发送键：40dp 方块、accent 底、按下态由 [enabled] 驱动弹性缩放。
 *
 * 2026-08-31 批 3：与 [UfiInputBarCard] 同批从 `SmsScreen` / `AdvancedConsoleScreen` 抽出。
 *
 * @param loading true 时把图标换成转圈（控制台执行中用）
 * @param disabledAlpha 禁用时底色透明度。两个调用点历史值不同（0.45 与 0.35），保留为参数
 */
@Composable
fun UfiSendButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector = Icons.AutoMirrored.Filled.Send,
    contentDescription: String = "发送",
    size: Dp = 40.dp,
    shape: RoundedCornerShape = UfiCardDefaults.dialogShape,
    disabledAlpha: Float = 0.45f
) {
    val palette = LocalResolvedPalette.current
    val scale by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.8f,
        animationSpec = UfiMotion.sendPop(),
        label = "sendScale"
    )
    val background by animateColorAsState(
        targetValue = if (enabled) palette.accent else palette.accent.copy(alpha = disabledAlpha),
        animationSpec = tween(UfiMotion.Duration.Standard), label = "sendBg"
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(shape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = palette.onAccent,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = palette.onAccent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
