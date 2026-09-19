// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiMotion

/**
 * 标题栏挂件的**下拉浮层**（2026-09-19 第四版：抽屉式下拉）。
 *
 * 点击标题栏上的天气后，从标题栏下方**向下拉出**一小块面板，覆盖在内容上方。
 *
 * ## 四版演化
 * - v1：嵌入式 expandVertically —— "不是浮层"；
 * - v2：全屏遮罩 + 从顶部滑入 —— "遮罩太重"；
 * - v3：小面板 scaleIn 从右上角落出 —— "不像抽屉、缺下拉感"；
 * - v4（现在）：**从标题栏正下方向下拉出**（`slideInVertically { -it }`），宽度自适应内容、
 *   圆角只在底部；点外部收起用透明捕获层。视觉上是"标题栏打开了一个抽屉"。
 */
object UfiHeaderDrawer {
    val content: MutableState<(@Composable () -> Unit)?> = mutableStateOf(null)
    val expanded: MutableState<Boolean> = mutableStateOf(false)
    val ownerKey: MutableState<String?> = mutableStateOf(null)

    fun toggle(key: String, body: @Composable () -> Unit) {
        if (expanded.value && ownerKey.value == key) {
            collapse()
        } else {
            ownerKey.value = key
            content.value = body
            expanded.value = true
        }
    }

    fun collapse() {
        expanded.value = false
    }

    fun clear(key: String) {
        if (ownerKey.value == key) {
            expanded.value = false
            content.value = null
            ownerKey.value = null
        }
    }
}

/**
 * 浮层的渲染宿主。由页壳在内容区 Box 内、`content()` 之后调用（叠在页面内容上面）。
 *
 * 动画：**从顶部向下滑入**（`slideInVertically { -it }`），收起时向上滑出 ——
 * 看起来像是标题栏的一部分被拉了出来。展开 280ms / 收起 200ms，节奏对齐弹窗 backdrop。
 */
@Composable
internal fun UfiHeaderDrawerHost() {
    val body = UfiHeaderDrawer.content.value
    val show = UfiHeaderDrawer.expanded.value && body != null

    // Host 必须在 content 被赋值后常驻，即使关闭也不退出——
    // 否则 AnimatedVisibility 没有 visible=false 的基线帧，首次打开入场动画被吞。
    // 用 mounted 记住"曾经有过 body"，只要 mounted 过就保持 Composable 活着。
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(body) { if (body != null) mounted = true }
    if (!mounted) return

    Box(modifier = Modifier.fillMaxSize()) {
        // 透明捕获层：不画颜色，只负责"点浮层外面收起"
        if (show) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { UfiHeaderDrawer.collapse() }
            )
        }

        // 内容：从顶部向下拉出
        AnimatedVisibility(
            visible = show,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = tween(UfiMotion.Duration.Gentle, easing = UfiMotion.Easing.EmphasizedIn)
            ) + fadeIn(tween(UfiMotion.Duration.Base)),
            exit = slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(UfiMotion.Duration.Standard, easing = UfiMotion.Easing.Standard)
            ) + fadeOut(tween(UfiMotion.Duration.Quick)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = Spacing.PagePadding)
        ) {
            body?.invoke()
        }
    }
}

/** 底部圆角（顶部直角贴着标题栏底边，不留缝）。 */
private val DrawerShape = RoundedCornerShape(
    topStart = 0.dp, topEnd = 0.dp,
    bottomStart = 16.dp, bottomEnd = 16.dp
)

/**
 * 浮层内容的标准外观：宽度自适应内容、底部圆角、背景色跟随卡片令牌。
 *
 * 视觉效果：像标题栏被"拉开"了一截，多出来的那截就是这块面板。
 * 挂件只管往里放读数行，不要自己画背景/圆角 —— 那会让天气和音乐的浮层长得不一样。
 */
@Composable
fun UfiHeaderDrawerCard(content: @Composable () -> Unit) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = Modifier
            .width(IntrinsicSize.Min)
            .widthIn(min = 140.dp, max = 200.dp)
            .clip(DrawerShape)
            .background(palette.pageBg)
            .padding(horizontal = Spacing.PagePadding)
            .padding(top = Spacing.Medium, bottom = Spacing.Large)
    ) {
        content()
    }
}
