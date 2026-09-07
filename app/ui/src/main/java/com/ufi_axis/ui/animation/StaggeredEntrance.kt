package com.ufi_axis.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import com.ufi_axis.ui.components.common.UfiMotion
import kotlinx.coroutines.launch

/**
 * Staggered entrance animation for list items — 现代圆润设计系统。
 *
 * 每个 item 从 16dp 上滑 + fade in，延迟 `index * `[UfiMotion.STAGGER_DELAY_MS]（35ms）。
 * 使用 spring 动画，更自然弹性。
 *
 * 与 [blurEntrance] 同样只在「首次入场 / [triggerKey] 变化」时播，见 [rememberEntrancePlayed]。
 */
fun Modifier.staggeredEntrance(
    index: Int,
    triggerKey: Any? = null
): Modifier = composed {
    var played by rememberEntrancePlayed(triggerKey)
    val alpha = remember(triggerKey) { Animatable(if (played) 1f else 0f) }
    val translationY = remember(triggerKey) { Animatable(if (played) 0f else 16f) }
    // 2026-09-04（P2a）：原为裸 `index * 35L`，与 UfiMotion.STAGGER_DELAY_MS（当时 50L）
    // 是同一个概念的两个值。统一到 token，并把 token 的值改成这里原本在跑的 35L
    // （详见 UfiMotion.STAGGER_DELAY_MS 的 KDoc：向实际行为对齐，不制造观感变化）。
    val delay = index * UfiMotion.STAGGER_DELAY_MS

    LaunchedEffect(triggerKey) {
        if (played) return@LaunchedEffect
        played = true
        launch {
            kotlinx.coroutines.delay(delay)
            alpha.animateTo(1f, UfiAnimSpecs.staggerEnter())
        }
        launch {
            kotlinx.coroutines.delay(delay)
            translationY.animateTo(0f, UfiAnimSpecs.staggerEnter())
        }
    }

    graphicsLayer {
        this.alpha = alpha.value
        this.translationY = translationY.value
    }
}

/**
 * Press-scale animation for clickable elements.
 * Scales down to [UfiMotion.PressScale.Chip]（0.97）while pressed, springs back on release.
 *
 * 2026-09-04（P2d）：0.97 改为引用 [UfiMotion.PressScale.Chip]（值不变）。本 Modifier 的定位是
 * 「给卡片 / 列表项 / chip 这类**大面积**可点区加按压反馈」，因此落在 Chip 档而不是
 * [UfiMotion.PressScale.Button]（0.96）—— 按压缩放的唯一规则是「面积越小缩得越多」，
 * 详见 [UfiMotion.PressScale] 的 KDoc。
 *
 * 2026-09-04（P2f 短按看不见）：原实现自带一份编排（`var isPressed` + `LaunchedEffect(isPressed)`
 * + [Animatable]），与全库另外 11 处 `collectIsPressedAsState` + `animateFloatAsState` 是**同一个
 * bug 的两种写法**：抬手瞬间目标翻回 1f，正在播的"缩下去"被就地反向拉回，短按走不出可见幅度
 * （原因详见 `PressFeedback.kt` 文件头）。现改为把手势翻译成 [PressInteraction] 喂给全站唯一
 * 实现 [rememberUfiPressScale]，本函数不再自带编排逻辑 —— 按压反馈只允许有一份实现。
 *
 * ⚠ 现状：本 Modifier **全库无调用点**（按压反馈都写在各组件内部）。保留是因为它是
 * `:app:ui` 的公共 API（F24 冻结），删除需要单独走一轮兼容评估。
 */
fun Modifier.clickScale(
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val scale by rememberUfiPressScale(
        interactionSource = interactionSource,
        pressedScale = UfiMotion.PressScale.Chip,
        spec = UfiAnimSpecs.clickScale()
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(onClick) {
            detectTapGestures(
                // 本 Modifier 走 pointerInput 而不是 clickable，所以按下/抬手/取消要自己
                // 翻译成 PressInteraction 发进 interactionSource，编排逻辑才用得上。
                onPress = { offset ->
                    val press = PressInteraction.Press(offset)
                    interactionSource.emit(press)
                    if (tryAwaitRelease()) {
                        interactionSource.emit(PressInteraction.Release(press))
                    } else {
                        interactionSource.emit(PressInteraction.Cancel(press))
                    }
                },
                onTap = { onClick() }
            )
        }
}

/**
 * 轻量 fade-in entrance — 替代旧版 blurEntrance。
 *
 * 旧版使用 RenderEffect blur 20→0，低端设备掉帧风险高。
 * 新版使用 fade + translateY(12dp)，时长 [UfiMotion.Duration.Fluid]（250ms），更轻量。
 *
 * **只在「首次入场 / [triggerKey] 变化」时播**，见 [rememberEntrancePlayed]。
 */
fun Modifier.blurEntrance(
    triggerKey: Any? = null
): Modifier = composed {
    var played by rememberEntrancePlayed(triggerKey)
    val alpha = remember(triggerKey) { Animatable(if (played) 1f else 0f) }
    val translationY = remember(triggerKey) { Animatable(if (played) 0f else 12f) }

    LaunchedEffect(triggerKey) {
        if (played) return@LaunchedEffect
        played = true
        // 2026-09-04（P2b）：两处裸 `tween(250)` → Duration.Fluid（同为 250，零观感变化）。
        launch {
            alpha.animateTo(1f, tween(UfiMotion.Duration.Fluid))
        }
        launch {
            translationY.animateTo(0f, tween(UfiMotion.Duration.Fluid))
        }
    }

    graphicsLayer {
        this.alpha = alpha.value
        this.translationY = translationY.value
    }
}

/**
 * 「这次入场动画播过了吗」——**跨组合销毁**存活的标记。
 *
 * 为什么需要它：Navigation Compose 在目的地离屏后会 dispose 它的组合。从二级页
 * （`detail/…` 那一组路由）返回时，Tab 宿主 [com.ufi_axis.ui.navigation.Routes.MAIN] 整个重新组合，
 * `remember { Animatable(0f) }` 全部重建 → 入场动画从 alpha=0 / +12px 再走一遍，
 * 观感就是「返回主界面抖动一下」。NavHost 层的 `hostPopEnter` 已经是
 * `EnterTransition.None`，所以剩下的抖动全部来自页面内部这层入场动画。
 *
 * `rememberSaveable` 挂在 `NavBackStackEntry` 的 `SaveableStateHolder` 上：组合被 dispose
 * 时保存、重建时还原，因此能把「播过了」这件事带过去。
 *
 * [triggerKey] 作为 input：key 变了（如文件管理器换目录）会重置成未播状态并重新播 ——
 * 那种场景下的动画表达的是「内容换了」，不是「页面进来了」，必须保留。
 */
@Composable
private fun rememberEntrancePlayed(triggerKey: Any?) =
    rememberSaveable(triggerKey) { mutableStateOf(false) }

