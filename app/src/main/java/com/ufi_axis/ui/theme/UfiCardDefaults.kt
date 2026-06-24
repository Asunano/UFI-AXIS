package com.ufi_axis.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一卡片样式常量，所有屏幕的 Card 复用同一套参数。
 * 设计风格：方形微圆角 + 浅淡边框 + 柔和阴影
 *
 * 用法：
 * ```
 * Card(
 *     shape = UfiCardDefaults.shape,
 *     elevation = UfiCardDefaults.cardElevation(),
 *     border = UfiCardDefaults.cardBorder(),
 * ) { ... }
 * ```
 *
 * 使用 noElevation() 替代硬编码的 CardDefaults.cardElevation(0.dp)
 */
@Immutable
object UfiCardDefaults {

    // ═══════ 标准卡片 ═══════
    val cornerRadius: Dp = Spacing.CardCornerRadius // 8dp
    val shape: RoundedCornerShape
        get() = RoundedCornerShape(cornerRadius)
    val elevationDp: Dp = 1.5.dp
    val borderWidth: Dp = 0.5.dp
    val horizontalMargin: Dp = Spacing.CardHorizontalMargin
    val padding: Dp = Spacing.CardPadding

    // ═══════ Widget/网格入口卡片 (10dp, 略大于标准) ═══════
    val widgetCornerRadius: Dp = 10.dp
    val widgetShape: RoundedCornerShape
        get() = RoundedCornerShape(widgetCornerRadius)

    // ═══════ 底部弹出面板 ═══════
    val bottomSheetCornerRadius: Dp = 10.dp
    val bottomSheetTopShape: RoundedCornerShape
        get() = RoundedCornerShape(topStart = bottomSheetCornerRadius, topEnd = bottomSheetCornerRadius)

    // ═══════ Dialog 弹窗 ═══════
    val dialogCornerRadius: Dp = Spacing.DialogCornerRadius // 10dp
    val dialogShape: RoundedCornerShape
        get() = RoundedCornerShape(dialogCornerRadius)

    // ═══════ Toast 提示 ═══════
    val toastCornerRadius: Dp = Spacing.ToastCornerRadius // 8dp
    val toastShape: RoundedCornerShape
        get() = RoundedCornerShape(toastCornerRadius)

    // ═══════ Input 输入框 ═══════
    val inputCornerRadius: Dp = Spacing.InputCornerRadius // 8dp
    val inputShape: RoundedCornerShape
        get() = RoundedCornerShape(inputCornerRadius)

    // ═══════ 小型徽章/标签 ═══════
    val smallCornerRadius: Dp = 4.dp
    val smallShape: RoundedCornerShape
        get() = RoundedCornerShape(smallCornerRadius)

    // ═══════ 按钮 ═══════
    val buttonCornerRadius: Dp = Spacing.ButtonCornerRadius // 8dp
    val buttonShape: RoundedCornerShape
        get() = RoundedCornerShape(buttonCornerRadius)

    // ═══════ 芯片/标签（速度指标等） ═══════
    val chipCornerRadius: Dp = 6.dp
    val chipShape: RoundedCornerShape
        get() = RoundedCornerShape(chipCornerRadius)

    // ═══════ 微量/细线（进度条轨道、信号条等） ═══════
    val microCornerRadius: Dp = 2.dp
    val microShape: RoundedCornerShape
        get() = RoundedCornerShape(microCornerRadius)
    val hairlineCornerRadius: Dp = 1.dp
    val hairlineShape: RoundedCornerShape
        get() = RoundedCornerShape(hairlineCornerRadius)

    // ═══════ 大图标容器 (16dp, QuickGridCell) ═══════
    val largeSurfaceCornerRadius: Dp = 16.dp
    val largeSurfaceShape: RoundedCornerShape
        get() = RoundedCornerShape(largeSurfaceCornerRadius)

    // ═══════ 旧版 Spacing.CardCorner (12dp) 兼容 ═══════
    val legacyCornerRadius: Dp = Spacing.CardCorner // 12dp
    val legacyShape: RoundedCornerShape
        get() = RoundedCornerShape(legacyCornerRadius)

    // ═══════ 聊天气泡（4角不对称） ═══════
    fun chatBubbleShape(isReceived: Boolean): RoundedCornerShape {
        return RoundedCornerShape(
            topStart = 12.dp, topEnd = 12.dp,
            bottomStart = if (isReceived) 4.dp else 12.dp,
            bottomEnd = if (isReceived) 12.dp else 4.dp
        )
    }

    // ═══════ 聊天输入框（超大圆角 20dp） ═══════
    val chatInputCornerRadius: Dp = 20.dp
    val chatInputShape: RoundedCornerShape
        get() = RoundedCornerShape(chatInputCornerRadius)

    // ═══════ SMS 发送弹窗（16dp 顶部圆角） ═══════
    val smsSheetCornerRadius: Dp = 16.dp
    val smsSheetTopShape: RoundedCornerShape
        get() = RoundedCornerShape(topStart = smsSheetCornerRadius, topEnd = smsSheetCornerRadius)

    // ═══════ @Composable 主题感知方法 ═══════

    /** 标准卡片 elevation */
    @Composable
    fun cardElevation(): CardElevation {
        return CardDefaults.cardElevation(defaultElevation = elevationDp)
    }

    /** 零 elevation（替代 CardDefaults.cardElevation(0.dp)） */
    @Composable
    fun noElevation(): CardElevation {
        return CardDefaults.cardElevation(defaultElevation = 0.dp)
    }

    /** 标准卡片边框（使用当前主题颜色） */
    @Composable
    fun cardBorder(): BorderStroke {
        val palette = LocalResolvedPalette.current
        return BorderStroke(borderWidth, palette.cardBorder)
    }

    /** 用于非 setting 卡片的边框（比 cardBorder 更淡） */
    @Composable
    fun cardLightBorder(): BorderStroke {
        val palette = LocalResolvedPalette.current
        return BorderStroke(borderWidth, palette.cardBorder.copy(alpha = 0.5f))
    }

    /** 标准卡片主题容器颜色 */
    @Composable
    fun cardColors(): CardColors {
        val palette = LocalResolvedPalette.current
        return CardDefaults.cardColors(containerColor = palette.cardBg)
    }
}
