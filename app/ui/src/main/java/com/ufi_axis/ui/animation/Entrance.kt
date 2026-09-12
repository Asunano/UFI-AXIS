package com.ufi_axis.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import com.ufi_axis.ui.theme.UfiMotion
import kotlinx.coroutines.launch

/**
 * 轻量 fade-in entrance — 替代旧版 blurEntrance。
 *
 * 旧版使用 RenderEffect blur 20→0，低端设备掉帧风险高。
 * 新版使用 fade + translateY(12dp)，时长 [UfiMotion.Duration.Fluid]（250ms），更轻量。
 *
 * **只在「首次入场 / [triggerKey] 变化」时播**，见 [rememberEntrancePlayed]。
 *
 * 2026-09-07：本文件原名 `StaggeredEntrance.kt`，另有 `Modifier.staggeredEntrance` 与
 * `Modifier.clickScale` 两个 Modifier —— 两者**全库零调用点**（按压反馈已统一由
 * `PressFeedback.kt` 提供，列表交错入场从未被采用），按 D6「不留废弃薄封装」直接删除，
 * 文件随之改名。若日后需要列表交错入场，请基于 `UfiAnimSpecs.staggerEnter()` 与
 * [UfiMotion.STAGGER_DELAY_MS] 重新实现，不要恢复旧代码。
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
