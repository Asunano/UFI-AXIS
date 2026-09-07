package com.ufi_axis.ui.animation.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.UfiExperimentalApi
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  T03 · 内置转场策略的静态可视化 Preview
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *  ⚠ 本文件**刻意不依赖 T02 的 `UfiPageSwitcher` 宿主**：
 *    T03 与 T02 并行开发，若在此引用尚未落地的宿主符号会直接打断
 *    `:app:ui` 的编译，进而阻塞整条流水线。
 *
 *  取而代之，这里直接把 `UfiPageTransition.layerAt` 的输出喂进 `graphicsLayer`，
 *  在几个关键采样点（-0.5 / 0 / +0.5）上把六种策略「定格」出来 ——
 *  这既是策略映射的可视化自检，也顺带演示了 T02 宿主该如何消费 `UfiPageLayer`。
 *
 *  待 T02 落地后，可另建 `UfiPageSwitcherLivePreview` 做真实交互演示。
 */

/** 演示卡片宽度。 */
private val SampleWidth: Dp = 62.dp

/** 演示卡片高度。按 2:3 比例贴近真实竖屏页面。 */
private val SampleHeight: Dp = 93.dp

/** 采样位置：左半屏外 / 居中 / 右半屏外。 */
private val SamplePositions: List<Float> = listOf(-0.5f, 0f, 0.5f)

/** 三个采样点各自的配色，便于一眼区分是哪一页。 */
private val SampleColors: List<Color> = listOf(
    Color(0xFF5B8DEF),
    Color(0xFF3FBF9B),
    Color(0xFFF2994A),
)

private val PreviewBackground: Color = Color(0xFFF6F7FB)
private val FrameBackground: Color = Color(0xFFE6E8F0)
private val PrimaryText: Color = Color(0xFF1B1D28)
private val SecondaryText: Color = Color(0xFF8A8FA3)

/**
 * 把纯数据的 [UfiPageLayer] 施加到 `graphicsLayer` 上。
 *
 * 单位约定与 T01 契约一致：
 * - `translationX/Y` 已是 **px**，直接赋值；
 * - `cameraDistance` 采用 Compose `graphicsLayer` 语义，**直接赋值，切勿再乘 density**。
 *   Compose UI 内部（`ViewLayer`）已执行 `View.cameraDistance = scope.cameraDistance * densityDpi`，
 *   本身即密度无关量；若外部再乘一次，2.75x 设备上 Cube3D 的 `16f` 会变成 `44f`，
 *   透视近乎退化为正交投影，3D 效果消失；
 * - `shadowElevation` 在 `GraphicsLayerScope` 里是 px，需用 `toPx()` 换算。
 *
 * 保持 `private`：真实宿主侧的等价实现由 T02 提供，此处仅供 Preview 自用，避免符号冲突。
 */
private fun Modifier.ufiPreviewPageLayer(layer: UfiPageLayer): Modifier = this.graphicsLayer {
    alpha = layer.alpha
    translationX = layer.translationX
    translationY = layer.translationY
    scaleX = layer.scaleX
    scaleY = layer.scaleY
    rotationX = layer.rotationX
    rotationY = layer.rotationY
    rotationZ = layer.rotationZ
    cameraDistance = layer.cameraDistance
    transformOrigin = layer.transformOrigin
    shadowElevation = layer.shadowElevation.toPx()
}

/**
 * 单个采样点：一个固定尺寸的「容器」内，按 [position] 定格渲染一页。
 *
 * 容器是否裁剪严格遵循 [UfiPageTransition.clipToBounds] —— 这样 3D 类策略
 * （Cube3D / Flip3D）溢出边界的透视效果才能如实呈现。
 */
@Composable
private fun TransitionSample(
    transition: UfiPageTransition,
    position: Float,
    color: Color,
) {
    val density = LocalDensity.current
    val widthPx: Int = with(density) { SampleWidth.roundToPx() }
    val heightPx: Int = with(density) { SampleHeight.roundToPx() }

    val ctx = UfiPageLayerContext(
        containerSize = IntSize(widthPx, heightPx),
        density = density,
        layoutDirection = LocalLayoutDirection.current,
        // ⚠ 不可由 position 的符号推导 isIncoming —— 符号只表示「在左还是在右」，与进/出无关。
        // 真实宿主取 `pageIndex == pagerState.targetPage`（即「本次切换最终停留的那一页」）。
        // 静态 Preview 没有「本次切换」的概念，故固定填 false（视作离场页）；
        // 六种内置策略均不读取此字段（见单测 allTransitions_ignoreIncomingFlag），不影响出图。
        isIncoming = false,
    )
    val layer: UfiPageLayer = transition.layerAt(position, ctx)

    val frame: Modifier = Modifier
        .size(width = SampleWidth, height = SampleHeight)
        .clip(UfiCardDefaults.subtleShape)
        .background(FrameBackground)
        .let { if (transition.clipToBounds) it.clipToBounds() else it }

    Box(modifier = frame, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .ufiPreviewPageLayer(layer)
                .clip(UfiCardDefaults.subtleShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(color, color.copy(alpha = 0.72f)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = formatPosition(position),
                color = Color.White,
                style = UfiTextStyles.caption.copy(fontWeight = UfiWeight.Emphasis),
            )
        }
    }
}

/** 一种策略占一行：左侧标题 + 右侧三个采样点。 */
@Composable
private fun TransitionRow(transition: UfiPageTransition) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = transition.displayName,
                color = PrimaryText,
                style = UfiTextStyles.tagStrong,
            )
            Text(
                text = "  ${transition.id}" +
                    (if (transition.requiresPairedRendering) " · paired" else "") +
                    (if (!transition.clipToBounds) " · noClip" else ""),
                color = SecondaryText,
                style = UfiTextStyles.captionTiny,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SamplePositions.forEachIndexed { index, position ->
                TransitionSample(
                    transition = transition,
                    position = position,
                    color = SampleColors[index % SampleColors.size],
                )
            }
        }
    }
}

/** 把采样位置格式化成简短标签，如 `-0.5` / `0` / `+0.5`。 */
private fun formatPosition(position: Float): String = when {
    position > 0f -> "+$position"
    else -> position.toString()
}

/**
 * 六种内置转场策略总览。
 *
 * 每行三格分别定格在 `position = -0.5 / 0 / +0.5`，可直观校验：
 * - 中间格（`0`）必须完全「原样」——所有策略在此处都不做任何变换；
 * - 左右两格体现各策略的差异化映射。
 */
@UfiExperimentalApi
@Preview(
    name = "内置转场策略总览",
    showBackground = true,
    backgroundColor = 0xFFF6F7FB,
    widthDp = 260,
    heightDp = 780,
)
@Composable
private fun UfiPageTransitionsGalleryPreview() {
    // 触发内置策略装配，保证 BuiltInTransitions.All 与注册表已就绪。
    registerBuiltInTransitions()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PreviewBackground)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "UfiPageTransition · 内置策略 (${BuiltInTransitions.All.size})",
            color = PrimaryText,
            style = UfiTextStyles.sectionTitle,
        )
        for (transition in BuiltInTransitions.All) {
            TransitionRow(transition = transition)
        }
    }
}

/**
 * Cube3D 的连续位置扫描，用于检查立方体两面在 `|p|` 递增时是否始终咬合。
 */
@UfiExperimentalApi
@Preview(
    name = "Cube3D · 位置扫描",
    showBackground = true,
    backgroundColor = 0xFFF6F7FB,
    widthDp = 320,
    heightDp = 160,
)
@Composable
private fun Cube3DSweepPreview() {
    registerBuiltInTransitions()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PreviewBackground)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "${Cube3DTransition.displayName} · ${Cube3DTransition.id}",
            color = PrimaryText,
            style = UfiTextStyles.tagStrong,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0f, 0.25f, 0.5f, 0.75f).forEachIndexed { index, position ->
                TransitionSample(
                    transition = Cube3DTransition,
                    position = position,
                    color = SampleColors[index % SampleColors.size],
                )
            }
        }
    }
}
