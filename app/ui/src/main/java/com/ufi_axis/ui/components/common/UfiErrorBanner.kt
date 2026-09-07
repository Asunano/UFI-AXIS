// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiBannerDefaults
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiCardShadow

/**
 * 悬浮卡入场曲线。
 *
 * 走 `theme.UfiMotion` 的公共档位（Standard 220ms + M3 emphasized decelerate），
 * **必须全限定**：本文件所在包里还有一个同名的过渡层 `UfiMotion`（已 @Deprecated），
 * 不限定会解析到那一个。
 */
private val ENTER_SPEC: AnimationSpec<Float> = tween(
    durationMillis = com.ufi_axis.ui.theme.UfiMotion.Duration.Standard,
    easing = com.ufi_axis.ui.theme.UfiMotion.Easing.EmphasizedIn
)

/**
 * 悬浮错误提示卡的尺寸令牌见 [UfiBannerDefaults]（2026-09-05 P3 令牌纪律：
 * 原先三个文件级 `private val` 描述的是「顶部悬浮层停在哪、多大」这一跨组件版式约定，
 * 与平台层 toast 必须同位，因此归属令牌层而不是本文件）。
 */

/**
 * 错误提示卡 —— **悬浮**在屏幕顶部，不占布局空间。
 *
 * ## 2026-09-05 由「行内 Card」改为「Popup 悬浮」
 * 旧实现是普通 `Card`，直接排在调用点所在的 Column / LazyColumn 里：错误一出现就把
 * 下方内容整段顶下去，消失时又弹回来，页面在报错前后跳两次。现在挂 [Popup]：
 * 组合树里只留一个零尺寸锚点，卡片画在独立窗口上，内容布局完全不动。
 *
 * ## 为什么自定义 [PopupPositionProvider] 而不用 `Popup(alignment = …)`
 * `alignment` 是**相对父布局**定位的。本组件的 20 个调用点分布在 Column、
 * `LazyColumn { item { } }`、甚至弹窗内部，用相对定位会出现"错误卡跟着列表滚动跑"。
 * 这里改成只看 `windowSize`：横向居中、纵向固定 [UfiBannerDefaults.topMargin]，与 toast 同位。
 *
 * ## 配色
 * 全部走 [ResolvedPalette] 令牌（`cardBg` 底 + `error` 描边/图标 + `textPrimary` 正文），
 * 与全站卡片同一套语言；换主题配色时一起变。旧实现是 `accent` 8% 淡底配 `error` 文字 ——
 * 底色取自强调色、文字取自错误色，两个语义混在一张卡上，观感偏「系统原生提示条」。
 *
 * @param message 错误文案。
 * @param onRetry 非 null 时右侧显示「重试」按钮（Subtle/Small 档，与全站按钮同源）。
 * @param modifier 施加在**悬浮卡本体**上（旧调用点传的横向 padding 仍然安全，只是不再影响布局）。
 */
@Composable
fun UfiErrorBanner(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val density = LocalDensity.current
    val topMarginPx = with(density) { UfiBannerDefaults.topMargin.roundToPx() }
    val sideMarginPx = with(density) { UfiBannerDefaults.sideMargin.roundToPx() }
    val maxCardWidth = LocalConfiguration.current.screenWidthDp.dp - UfiBannerDefaults.sideMargin * 2

    val positionProvider = remember(topMarginPx, sideMarginPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset = IntOffset(
                x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(sideMarginPx),
                y = topMarginPx
            )
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        // 不抢焦点：错误卡不该吞返回键，也不该弹/收输入法。
        properties = PopupProperties(focusable = false)
    ) {
        // 入场只做一次：alpha + 轻微下落。读值放在 graphicsLayer 的 lambda 里，逐帧不重组。
        val enter = remember { Animatable(0f) }
        LaunchedEffect(Unit) { enter.animateTo(1f, ENTER_SPEC) }
        Row(
            modifier = modifier
                .widthIn(max = maxCardWidth)
                .graphicsLayer {
                    val p = enter.value
                    alpha = p
                    translationY = (p - 1f) * UfiBannerDefaults.rise.toPx()
                }
                .ufiCardShadow(elevation = UfiCardDefaults.elevationLevel3Dp, shape = UfiCardDefaults.shape)
                .clip(UfiCardDefaults.shape)
                .background(palette.cardBg)
                .border(
                    UfiCardDefaults.hairlineBorderWidth,
                    palette.error.copy(alpha = 0.35f),
                    UfiCardDefaults.shape
                )
                .padding(Spacing.InnerPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(UfiBannerDefaults.iconBadgeSize)
                    .clip(CircleShape)
                    .background(palette.error.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = palette.error,
                    modifier = Modifier.size(UfiBannerDefaults.iconSize)
                )
            }
            Spacer(Modifier.width(UfiBannerDefaults.iconTextGap))
            Text(
                message,
                modifier = Modifier.weight(1f, fill = false),
                style = UfiTextStyles.bodyEmphasis,
                color = palette.textPrimary,
                maxLines = 3
            )
            if (onRetry != null) {
                Spacer(Modifier.width(Spacing.Medium))
                UfiButton(
                    text = "重试",
                    onClick = onRetry,
                    variant = UfiButtonVariant.Subtle,
                    size = UfiButtonSize.Small
                )
            }
        }
    }
}

@Composable
fun UfiOfflineBanner(
    lastUpdated: String?,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = UfiCardDefaults.shape,
        colors = CardDefaults.cardColors(containerColor = palette.accent.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.2f))
    ) {
        Row(
            Modifier.padding(Spacing.InnerPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "后端服务未连接",
                    style = UfiTextStyles.bodyEmphasis,
                    color = palette.error
                )
                if (lastUpdated != null) {
                    Text(
                        "最后更新: $lastUpdated",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                }
            }
        }
    }
}

/**
 * 实时连接状态横幅（UID-006）。
 *
 * 入参为 [String?] 而非 `ConnectionState` 枚举，确保 `app/ui` 不依赖 `app/data`，
 * 文案映射由屏幕侧完成（见各 Screen 对 [message] 的赋值）。
 *
 * - `message == null`：不渲染（如 CONNECTED 状态）。
 * - `message != null`：以警示色 [Card] + [Row] + [Icons.Default.Warning] + [Text] 常驻展示。
 *
 * @param message 待展示文案；为 null 时不渲染。
 * @param modifier 外部布局修饰（如外边距）。
 */
@Composable
fun UfiRealtimeStatusBanner(
    message: String?,
    modifier: Modifier = Modifier
) {
    if (message == null) return
    val palette = LocalResolvedPalette.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = UfiCardDefaults.shape,
        colors = CardDefaults.cardColors(containerColor = palette.warning.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, palette.warning.copy(alpha = 0.3f))
    ) {
        Row(
            Modifier.padding(Spacing.InnerPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = palette.warning,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.warning
            )
        }
    }
}