// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.ResolvedPalette

/** 把 Compose Color 转成平台 argb Int（按分量重建，绝不手截 ULong.value，跨版本稳定）。 */
private fun Color.toArgbInt(): Int {
    val a = (alpha * 255.0f + 0.5f).toInt().coerceIn(0, 255)
    val r = (red * 255.0f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255.0f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255.0f + 0.5f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

enum class ToastType { SUCCESS, ERROR, INFO, WARNING }

/**
 * 当前主题 → 平台层 Toast 配色。
 *
 * 提成 internal 扩展是为了让「告警 → Toast」那条链路（[UfiAlertToastBridge]）能直接复用
 * 同一套配色，而不是再抄一份 palette→argb 的映射（抄一份就会出现"普通 Toast 改了配色、
 * 告警 Toast 没跟上"的漂移）。
 */
internal fun ResolvedPalette.toUfiToastColors(): UfiToastColors = UfiToastColors(
    cardBg = cardBg.toArgbInt(),
    textPrimary = textPrimary.toArgbInt(),
    accent = accent.toArgbInt(),
    success = success.toArgbInt(),
    error = error.toArgbInt(),
    warning = warning.toArgbInt(),
    warningContainer = warningContainer.toArgbInt(),
    toastBorder = toastBorder.toArgbInt(),
    isDark = isDark
)

data class ToastMessage(
    val text: String,
    val type: ToastType = ToastType.INFO,
    val durationMs: Long = 3000L,
    val subtitle: String? = null,
    val isLoading: Boolean = false
)

/**
 * 全局 Toast 宿主（兼容外壳，签名冻结，30+ 调用点零改动）。
 *
 * 2026-08-24 重构：
 *  - 旧实现（Compose Popup）：Toast 仍挂在 Compose 视图树内，AnimatedContent 转场 /
 *    页面滚动时父容器约束变化，导致 Toast 跟随滑动 / 位置异常。
 *  - 新实现：本 Composable 仅作为「适配层」——读取当前 LocalResolvedPalette + LocalContext，
 *    把 ToastMessage 转交给平台层单例 [UfiToastOverlay]，由它把卡片 add 到
 *    Activity.decorView 顶层 FrameLayout（Gravity.TOP|CENTER_HORIZONTAL），
 *    彻底脱离 Compose 树，固定屏幕顶部居中、绝不跟随滑动。
 *
 * 调用方契约不变：传入 `toastMessage`（非 null 时显示），`onDismiss` 在 Toast 退出动画
 * 结束时被调用，调用方据此把 state 置 null（下次才能再弹）。
 */
@Composable
fun UfiToastHost(
    toastMessage: ToastMessage?,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val context = LocalContext.current

    LaunchedEffect(toastMessage) {
        if (toastMessage == null) return@LaunchedEffect
        UfiToastOverlay.show(
            context = context,
            type = toastMessage.type,
            title = toastMessage.text,
            subtitle = toastMessage.subtitle,
            isLoading = toastMessage.isLoading,
            durationMs = toastMessage.durationMs,
            colors = palette.toUfiToastColors(),
            onDismiss = onDismiss
        )
    }
}
