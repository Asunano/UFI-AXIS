package com.ufi_axis.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一卡片样式常量 — 现代克制设计系统。**这里是全站形状（圆角）的唯一语义层**。
 *
 * 设计原则：
 * - 圆角全部取自 `Spacing.kt` 的 `Corner*` 常量（主基准 [Spacing.CornerBase]，另有弹窗 / 微圆角 /
 *   轨道 / 标签 / 胶囊 / 气泡等专用档），下面所有 `*Shape` 都从它们派生 —— 想调圆角只改 `Spacing.kt`。
 * - 一个 `Corner*` 常量只服务一个语义，**不要跨语义借用**（2026-09-03：`chatBubbleShape` 曾借用
 *   [Spacing.CornerDialog]，导致改弹窗圆角会连带改短信气泡，已改为专用的 [Spacing.CornerChatBubble]）。
 * - 业务代码**不要写 `RoundedCornerShape(N.dp)`**，用这里的语义 shape（`shape` / `chipShape` /
 *   `dialogShape` / `microShape` …）。
 * - 柔阴影（设计稿 X:0 / Y:4 / 模糊:16 / 扩展:0 / #9CA4AC @30%）
 * - Level 1: 4dp 阴影（普通卡片）
 * - Level 2: 6dp 阴影（重要卡片）
 * - Level 3: 10dp 阴影（弹窗/Bottom Sheet）
 *
 * 2026-09-01 批 4：`Spacing` 里 8 个同值 10dp 圆角 val 已合并为一个 `CornerBase`。
 */
@Immutable
object UfiCardDefaults {

    // ═══════ 大卡片（Hero 状态卡） ═══════
    val largeCornerRadius: Dp = Spacing.CornerBase
    val largeShape: RoundedCornerShape
        get() = RoundedCornerShape(largeCornerRadius)

    // ═══════ 标准卡片（设置组、功能入口） ═══════
    val cornerRadius: Dp = Spacing.CornerBase
    val shape: RoundedCornerShape
        get() = RoundedCornerShape(cornerRadius)

    // ═══════ 小组件（chip、badge、input） ═══════
    val smallCornerRadius: Dp = Spacing.CornerBase
    val smallShape: RoundedCornerShape
        get() = RoundedCornerShape(smallCornerRadius)

    // ═══════ 阴影层级 ═══════
    // 设计稿：X:0 / Y:4 / 模糊:16 / 扩展:0 / #9CA4AC @30%
    // Compose shadow 映射：elevation 4dp ≈ blur≈16, Y≈6（最接近设计稿模糊值）
    val elevationDp: Dp = 4.dp     // Level 1: 普通卡片
    val elevationLevel2Dp: Dp = 6.dp // Level 2: 重要卡片
    val elevationLevel3Dp: Dp = 10.dp // Level 3: 弹窗

    val horizontalMargin: Dp = Spacing.CardHorizontalMargin
    val padding: Dp = Spacing.CardPadding

    // ═══════ Widget/网格入口卡片 ═══════
    val widgetCornerRadius: Dp = Spacing.CornerBase
    val widgetShape: RoundedCornerShape
        get() = RoundedCornerShape(widgetCornerRadius)

    // ═══════ 底部弹出面板 ═══════
    val bottomSheetCornerRadius: Dp = Spacing.CornerBase
    val bottomSheetTopShape: RoundedCornerShape
        get() = RoundedCornerShape(topStart = bottomSheetCornerRadius, topEnd = bottomSheetCornerRadius)

    // ═══════ Dialog 弹窗 ═══════
    val dialogCornerRadius: Dp = Spacing.CornerDialog
    val dialogShape: RoundedCornerShape
        get() = RoundedCornerShape(dialogCornerRadius)

    // ═══════ Toast 提示 ═══════
    val toastCornerRadius: Dp = Spacing.CornerBase
    val toastShape: RoundedCornerShape
        get() = RoundedCornerShape(toastCornerRadius)

    // ═══════ Input 输入框 ═══════
    val inputCornerRadius: Dp = Spacing.CornerBase
    val inputShape: RoundedCornerShape
        get() = RoundedCornerShape(inputCornerRadius)

    // ═══════ 小型徽章/标签 ═══════
    val microCornerRadius: Dp = Spacing.CornerMicro
    val microShape: RoundedCornerShape
        get() = RoundedCornerShape(microCornerRadius)

    // ═══════ 按钮 ═══════
    val buttonCornerRadius: Dp = Spacing.CornerBase
    val buttonShape: RoundedCornerShape
        get() = RoundedCornerShape(buttonCornerRadius)

    // ═══════ 芯片/标签 ═══════
    val chipCornerRadius: Dp = Spacing.CornerBase
    val chipShape: RoundedCornerShape
        get() = RoundedCornerShape(chipCornerRadius)

    // ═══════ 微量/细线（进度条轨道、信号条等） ═══════
    val hairlineCornerRadius: Dp = Spacing.CornerHairline
    val hairlineShape: RoundedCornerShape
        get() = RoundedCornerShape(hairlineCornerRadius)

    /**
     * 发丝描边宽度：全站「1dp 细线」的唯一档位。
     *
     * 卡片描边（[cardBorder] / [cardLightBorder] / [ufiStandardCard]）、悬浮提示卡描边、
     * 分段控件轨道与滑块描边都是同一条视觉语言 —— 一条刚好可见、不抢注意力的细线。
     * 各调用点原来各写一个 `1.dp` 字面量，"把细线改成 1.5dp 看看"要改几十处。
     *
     * ⚠ 与 [hairlineCornerRadius] 同值但**不同语义**（那个是圆角半径，这个是描边宽度），
     * 按本文件 KDoc 的「一个常量只服务一个语义」原则拆成两个。
     */
    val hairlineBorderWidth: Dp = 1.dp

    // ═══════ 圆角梯度（批 4 从业务字面量收敛，值见 Spacing 对应 token） ═══════
    /** 全圆（胶囊）：进度条 / 圆点 / 药丸按钮，半径永远等于高度一半。 */
    val pillShape: RoundedCornerShape
        get() = RoundedCornerShape(percent = 50)

    /** 细条轨道 / 微型分隔块。 */
    val trackCornerRadius: Dp = Spacing.CornerTrack
    val trackShape: RoundedCornerShape
        get() = RoundedCornerShape(trackCornerRadius)

    /** 紧凑标签、行内小块。 */
    val tagCornerRadius: Dp = Spacing.CornerTag
    val tagShape: RoundedCornerShape
        get() = RoundedCornerShape(tagCornerRadius)

    /** 小面板、卡内二级容器。 */
    val subtleCornerRadius: Dp = Spacing.CornerSmall
    val subtleShape: RoundedCornerShape
        get() = RoundedCornerShape(subtleCornerRadius)

    /** 中圆角：图标容器、稍大的卡内容器。 */
    val mediumCornerRadius: Dp = Spacing.CornerMedium
    val mediumShape: RoundedCornerShape
        get() = RoundedCornerShape(mediumCornerRadius)

    /** 大圆角：应用图标、装饰性大方块。 */
    val iconTileCornerRadius: Dp = Spacing.CornerLarge
    val iconTileShape: RoundedCornerShape
        get() = RoundedCornerShape(iconTileCornerRadius)

    /** 胶囊按钮（比 [buttonShape] 更圆的强调按钮）。 */
    val capsuleCornerRadius: Dp = Spacing.CornerCapsule
    val capsuleShape: RoundedCornerShape
        get() = RoundedCornerShape(capsuleCornerRadius)

    /** 控制台会话气泡（4 角不对称，尖角指向发言方）。 */
    fun consoleBubbleShape(isUser: Boolean): RoundedCornerShape {
        val r = Spacing.CornerBubble
        val tip = Spacing.CornerBubbleTip
        return RoundedCornerShape(
            topStart = r, topEnd = r,
            bottomStart = if (isUser) r else tip,
            bottomEnd = if (isUser) tip else r
        )
    }


    // ═══════ 大图标容器 (功能入口卡片) ═══════
    val largeSurfaceCornerRadius: Dp = Spacing.CornerBase
    val largeSurfaceShape: RoundedCornerShape
        get() = RoundedCornerShape(largeSurfaceCornerRadius)

    // ═══════ 旧版兼容（历史上是另一个值，现已与基准同源） ═══════
    val legacyCornerRadius: Dp = Spacing.CornerBase
    val legacyShape: RoundedCornerShape
        get() = RoundedCornerShape(legacyCornerRadius)

    // ═══════ 聊天气泡（4角不对称） ═══════
    fun chatBubbleShape(isReceived: Boolean): RoundedCornerShape {
        val r = Spacing.CornerChatBubble
        val tip = Spacing.CornerBubbleTip
        return RoundedCornerShape(
            topStart = r, topEnd = r,
            bottomStart = if (isReceived) tip else r,
            bottomEnd = if (isReceived) r else tip
        )
    }

    // ═══════ 聊天输入框（刻意比基准更圆，接近胶囊） ═══════
    val chatInputCornerRadius: Dp = Spacing.CornerChatInput
    val chatInputShape: RoundedCornerShape
        get() = RoundedCornerShape(chatInputCornerRadius)

    // ═══════ SMS 发送弹窗 ═══════
    val smsSheetCornerRadius: Dp = Spacing.CornerBase
    val smsSheetTopShape: RoundedCornerShape
        get() = RoundedCornerShape(topStart = smsSheetCornerRadius, topEnd = smsSheetCornerRadius)

    // ═══════ @Composable 主题感知方法 ═══════

    /** Level 1 阴影（普通卡片） */
    @Composable
    fun cardElevation(): CardElevation {
        return CardDefaults.cardElevation(defaultElevation = elevationDp)
    }

    /** Level 2 阴影（重要卡片，如连接状态卡） */
    @Composable
    fun cardElevationLevel2(): CardElevation {
        return CardDefaults.cardElevation(defaultElevation = elevationLevel2Dp)
    }

    /** Level 3 阴影（弹窗） */
    @Composable
    fun cardElevationLevel3(): CardElevation {
        return CardDefaults.cardElevation(defaultElevation = elevationLevel3Dp)
    }

    /** 零 elevation */
    @Composable
    fun noElevation(): CardElevation {
        return CardDefaults.cardElevation(defaultElevation = 0.dp)
    }

    /** 标准卡片主题容器颜色 */
    @Composable
    fun cardColors(): CardColors {
        val palette = LocalResolvedPalette.current
        return CardDefaults.cardColors(containerColor = palette.cardBg)
    }

    /** 标准卡片描边 */
    @Composable
    fun cardBorder(): BorderStroke {
        val palette = LocalResolvedPalette.current
        return BorderStroke(1.dp, palette.cardBorder)
    }

    /** 轻量卡片描边（alpha 更低） */
    @Composable
    fun cardLightBorder(): BorderStroke {
        val palette = LocalResolvedPalette.current
        return BorderStroke(1.dp, palette.cardBorder.copy(alpha = if (palette.isDark) 0.04f else 0.03f))
    }
}

/**
 * 全局统一阴影颜色 — #9CA4AC @ 30%（设计稿：X:0 / Y:4 / 模糊:16 / 扩展:0）。
 *
 * 注意：Material3 1.4.0 的 ColorScheme 没有 shadowColor 字段、Surface 用 graphicsLayer
 * 硬编码黑色阴影，故无法通过主题一处全局改阴影色。本 token 仅通过 [ufiCardShadow]
 * 作用于 Box 类卡片（公共组件与所有显式卡片），实现统一灰影。
 */
val cardShadowColor: Color = NeutralOutline.copy(alpha = 0.30f)

/**
 * 统一卡片阴影 Modifier — 与 [cardShadowColor] 一致，去掉黑色 spot 光源，
 * 只保留纯灰环境光投影（贴合设计稿的扁平单色阴影）。
 *
 * 用法：`Modifier.fillMaxWidth().ufiCardShadow(elevation = 4.dp, shape = cardShape)`
 */
fun Modifier.ufiCardShadow(
    elevation: Dp = 2.dp,
    shape: Shape
): Modifier = this.shadow(
    elevation = elevation,
    ambientColor = cardShadowColor,
    spotColor = Color.Transparent,
    shape = shape
)

/**
 * 统一标准卡片 Modifier — 一站式阴影 + 圆角裁剪 + 背景描边。
 *
 * 封装全站通用的「白底 + 灰影 + 1dp 描边 + 10dp 圆角」卡片样式，
 * 替代各页面手写 `.ufiCardShadow().clip().background().border()` 四链调用。
 * 自动从 [LocalResolvedPalette] 读取配色，无需手动传 palette。
 *
 * 用法（独立行卡片）：
 * ```kotlin
 * Box(Modifier.ufiStandardCard().padding(horizontal = 16.dp).padding(16.dp)) { content }
 * ```
 *
 * 用法（合一内容卡片）：
 * ```kotlin
 * Box(Modifier.ufiStandardCard().padding(Spacing.CardPadding)) { content }
 * ```
 *
 * @param elevation 阴影高度，默认 4dp（Level 1 普通卡片，匹配设计稿 Y≈4-6/模糊≈16）
 * @param shape 圆角形状，默认 [UfiCardDefaults.shape]（= 10dp）
 */
@Composable
fun Modifier.ufiStandardCard(
    elevation: Dp = 4.dp,
    shape: RoundedCornerShape = UfiCardDefaults.shape
): Modifier {
    val palette = LocalResolvedPalette.current
    return this
        .ufiCardShadow(elevation = elevation, shape = shape)
        .clip(shape)
        .background(palette.cardBg, shape)
        .border(1.dp, palette.cardBorder, shape)
}
