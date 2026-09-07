package com.ufi_axis.ui.animation

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.ufi_axis.ui.animation.page.LocalUfiReduceMotion
import com.ufi_axis.ui.theme.UfiMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║  按压缩放反馈 —— 全站唯一实现（2026-09-04 P2f）                        ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * ## 为什么原来短按看不见（用户实测：只有长按才有按压动画，chip / 弹窗按钮同样）
 *
 * 收口前全库 12 处按压缩放都是同一个写法：
 * ```
 * val isPressed by interactionSource.collectIsPressedAsState()
 * val scale by animateFloatAsState(if (isPressed) PressScale.X else 1f, spec)
 * ```
 * [androidx.compose.animation.core.animateFloatAsState] 是**跟随目标值**的动画：目标一变就
 * 从「当前值」朝新目标走，**不保证走完**。两件事叠加把短按的可见幅度吃到 0：
 *
 * 1. **短按的按下态只存在一两帧**。抬手后 `isPressed` 立刻翻回 false，动画被就地反向拉回。
 *    按钮档的落差只有 0.04（1f → 0.96f），配 `spring(dampingRatio = 1f, stiffness = 600f)`：
 *    一帧（16ms）后进度约 6%，两帧（33ms）约 20% —— 即 scale ≈ 0.998 / 0.992，
 *    在 48dp 的按钮上是零点几个像素，肉眼根本看不见。chip 档（0.97 + `tween(120)`）更糟，
 *    一帧只走完 5%。
 * 2. **可点区在滚动容器里时，Compose 会推迟发出按下事件**。`Modifier.clickable` 在
 *    可滚动父级中要先确认这不是一次滑动，才补发 [PressInteraction.Press]；短按往往在确认
 *    之前就抬手了，于是 Press 与 Release **在同一帧内先后送达** —— 动画得到的时间不是
 *    「太少」而是**字面上的 0**。本仓几乎所有页面都是 `verticalScroll` / `LazyColumn`，
 *    所以症状是"大部分按钮都这样"；而长按超过滑动判定后按下态会稳定驻留，所以只有长按看得见。
 *
 * 结论：这不是"spring 刚度太高"能调出来的，把 stiffness 调低只会让长按变拖沓、短按依旧看不见。
 * 只要动画的**播放时长由手指决定**，短按就永远没有可见幅度。
 *
 * ## 现在怎么保证的
 *
 * 改成**事件驱动 + 最短保持**的手动编排（[rememberUfiPressScale]）：监听
 * [PressInteraction] 的 Press / Release / Cancel，用一个 [Animatable] 手动排
 * 「缩下去 → 至少保持 [UfiMotion.Duration.Micro]（120ms）→ 弹回」。
 * 抬手时若按下相位不足 120ms，先用一条补时长的 tween 把「缩到位」这件事**播完**再弹回，
 * 因此短按也有完整可见的一次缩放，长按则与收口前观感完全一致（按住不放就停在缩小态）。
 *
 * 四个档位（[UfiMotion.PressScale] 的 Fab .92 / Cell .94 / Button .96 / Chip .97）与各族
 * 配套的 spec 都**由调用点原样传入**，本文件不碰分层规则，只负责"让它播得出来"。
 */

/**
 * 按压缩放的驱动状态 —— 全站按压反馈的唯一编排实现。
 *
 * 返回值直接喂给 [androidx.compose.ui.graphics.graphicsLayer] 的 scaleX/scaleY；
 * 只需要缩放本身的调用点请用更短的 [ufiPressScale]，需要把按压缩放与别的缩放**相乘**
 * （如排程选择器日期格的选中弹跳）才用本函数。
 *
 * @param interactionSource 必须是**同时传给 `clickable` / `Button` 的那一个**实例，
 *   否则收不到按下事件（收口前的写法也有这个前提，行为未变）。
 * @param pressedScale 按下时的目标缩放，取 [UfiMotion.PressScale] 的四档之一。
 *   分层规则是「元素面积越小缩得越多」，见该对象的 KDoc，**不要在调用点自选数值**。
 * @param spec 缩下去与弹回共用的动画规格，按族取值（FAB → `controlPop()`、按钮族 →
 *   `buttonPress()`、chip / 选项格 / 日期格 → `tween(Duration.Micro)`）。
 *   保留成参数而不是按 [pressedScale] 反推：档位讲的是"缩多少"、spec 讲的是"怎么缩"，
 *   焊死会导致想换回弹手感时只能去改档位值。
 * @param minPressMillis 按下相位的**最短可见时长**。默认 [UfiMotion.Duration.Micro]（120ms）——
 *   该档的语义本来就是"按压 / chip 选中等微反馈"，不新增令牌档位。
 */
@Composable
fun rememberUfiPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float,
    spec: AnimationSpec<Float> = tween(UfiMotion.Duration.Micro),
    minPressMillis: Int = UfiMotion.Duration.Micro,
): State<Float> {
    // 尊重「减少动效」：关闭动效时整段编排不启动，元素恒定 1f（不是"更快的动画"，是没有动画）。
    val reduceMotion = LocalUfiReduceMotion.current
    val scale = remember { Animatable(1f) }

    // 这三项用 rememberUpdatedState 而不是当 LaunchedEffect 的 key：
    // spec 多为 `UfiMotion.buttonPress()` 这类**每次重组新建实例**的返回值，当 key 会让
    // 编排协程被反复重启，正在播的按压动画随之丢帧。
    val target by rememberUpdatedState(pressedScale)
    val animSpec by rememberUpdatedState(spec)
    val minHold by rememberUpdatedState(minPressMillis)

    LaunchedEffect(interactionSource, reduceMotion) {
        if (reduceMotion) {
            if (scale.value != 1f) scale.snapTo(1f)
            return@LaunchedEffect
        }
        // 按下计数用栈而不是 Boolean：多指同时按同一个元素时会收到多个 Press，
        // 只有最后一个 Release/Cancel 才算真正抬手。
        val pressStack = mutableListOf<PressInteraction.Press>()
        // 全程只有这一个动画协程在写 scale：新相位启动前先取消上一个相位，
        // 因此连续快点也只是"重新开始一次编排"，不会叠出互相拉扯的多条动画。
        // （即便 cancel 尚未生效，Animatable 内部的 MutatorMutex 也会让后来的 animateTo 抢占。）
        var phase: Job? = null
        var downAtMillis = 0L
        try {
            interactionSource.interactions.collect { interaction ->
                val wasDown = pressStack.isNotEmpty()
                when (interaction) {
                    is PressInteraction.Press -> pressStack.add(interaction)
                    is PressInteraction.Release -> pressStack.remove(interaction.press)
                    is PressInteraction.Cancel -> pressStack.remove(interaction.press)
                    else -> return@collect   // hover / focus 等与按压无关，忽略
                }
                val isDown = pressStack.isNotEmpty()
                if (isDown == wasDown) return@collect

                phase?.cancel()
                phase = if (isDown) {
                    // 用 uptimeMillis 而不是 currentTimeMillis：后者会被系统改时间影响，
                    // 校时正好落在按压中间会算出负的或极大的"已按住时长"。
                    downAtMillis = SystemClock.uptimeMillis()
                    launch { scale.animateTo(target, animSpec) }
                } else {
                    val heldMillis = SystemClock.uptimeMillis() - downAtMillis
                    val makeUpMillis = (minHold - heldMillis).coerceAtLeast(0L).toInt()
                    launch {
                        // 短按补播：抬手时缩放往往只走了个位数百分比，这里用一条"补足到
                        // minHold"的 tween 把缩到位这件事播完，短按因此也看得见完整一次缩放。
                        // 长按（heldMillis ≥ minHold）时 makeUpMillis = 0，直接弹回，观感与收口前一致。
                        if (makeUpMillis > 0) scale.animateTo(target, tween(makeUpMillis))
                        scale.animateTo(1f, animSpec)
                    }
                }
            }
        } finally {
            // 兜底回弹：手势被取消、interactionSource 换实例、组件在动画中途离开组合，
            // 任何一条退出路径都不能把元素留在缩小态。NonCancellable 让 snapTo 在协程
            // 已被取消后仍能落地。
            if (scale.value != 1f) {
                withContext(NonCancellable) { scale.snapTo(1f) }
            }
        }
    }

    return scale.asState()
}

/**
 * 按压缩放 [Modifier] —— [rememberUfiPressScale] + `graphicsLayer` 的两行合一版本。
 *
 * 收口前每个调用点都要写「`animateFloatAsState` + `graphicsLayer { scaleX = scale; scaleY = scale }`」
 * 两段，本 Modifier 把它压成一行；参数含义与 [rememberUfiPressScale] 逐一相同。
 *
 * 摆放位置：**放在 `clip` / `background` / `border` 之前**（与收口前 `graphicsLayer` 的位置一致），
 * 这样底色与描边跟着一起缩，而不是只缩内容。
 */
fun Modifier.ufiPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float,
    spec: AnimationSpec<Float> = tween(UfiMotion.Duration.Micro),
    minPressMillis: Int = UfiMotion.Duration.Micro,
): Modifier = composed {
    val scale by rememberUfiPressScale(
        interactionSource = interactionSource,
        pressedScale = pressedScale,
        spec = spec,
        minPressMillis = minPressMillis
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * 按下态标志 —— 带**最短保持时长**的 `collectIsPressedAsState` 替代品。
 *
 * ## 为什么需要它（2026-09-04）
 * 按压缩放的短按问题已由 [rememberUfiPressScale] 解决，但**按压态配色**是另一条同源链路：
 * ```
 * val isPressed by interactionSource.collectIsPressedAsState()
 * val bg by animateColorAsState(if (isPressed) 加深色 else 常态色, tween(Duration.Base))
 * ```
 * `collectIsPressedAsState` 忠实反映手指状态 —— 短按时它只 true 一两帧，
 * `animateColorAsState` 的目标随即翻回常态色，200ms 的过渡只走出几个百分比，
 * 底色变化肉眼看不见（成因与"缩放看不见"完全相同，详见本文件头部）。
 *
 * 本函数把手指状态**加长**到至少 [minPressMillis]：按下即为 true，抬手时若按下相位不足
 * 该时长，则延后到满足时长再翻回 false。调用点只需把 `collectIsPressedAsState()`
 * 换成 `rememberUfiPressed(interactionSource)`，下游的 `animateColorAsState` 一行都不用改。
 *
 * 与 [rememberUfiPressScale] 的分工：缩放是"把动画补播完"（改的是播放），
 * 本函数是"把状态držet住"（改的是输入）。颜色过渡无法像缩放那样补播 —— 它的起点是
 * 当前色而非固定值，补播会造成二次跳色 —— 所以配色走延长状态这条路。
 *
 * 不接 `LocalUfiReduceMotion`：减少动效针对的是位移/缩放类前庭刺激，
 * 按压变色属于必要的操作反馈，关掉反而让人不确定有没有点到。
 *
 * @param interactionSource 必须是同时传给 `clickable` / `Button` 的那一个实例。
 * @param minPressMillis 按下态的最短存续时长，默认 [UfiMotion.Duration.Micro]（120ms），
 *   与按压缩放同档，两者因此同时开始、同时结束。
 */
@Composable
fun rememberUfiPressed(
    interactionSource: InteractionSource,
    minPressMillis: Int = UfiMotion.Duration.Micro,
): State<Boolean> {
    val pressed = remember { mutableStateOf(false) }
    val minHold by rememberUpdatedState(minPressMillis)

    LaunchedEffect(interactionSource) {
        // 与 rememberUfiPressScale 同款的按下计数栈：多指同按时只有最后一次抬手才算松开。
        val pressStack = mutableListOf<PressInteraction.Press>()
        // 延后翻假的协程。新一次按下要先取消它，否则上一次的延时到点会把本次按下态清掉。
        var releaseJob: Job? = null
        var downAtMillis = 0L
        try {
            interactionSource.interactions.collect { interaction ->
                val wasDown = pressStack.isNotEmpty()
                when (interaction) {
                    is PressInteraction.Press -> pressStack.add(interaction)
                    is PressInteraction.Release -> pressStack.remove(interaction.press)
                    is PressInteraction.Cancel -> pressStack.remove(interaction.press)
                    else -> return@collect
                }
                val isDown = pressStack.isNotEmpty()
                if (isDown == wasDown) return@collect

                releaseJob?.cancel()
                if (isDown) {
                    // uptimeMillis 而不是 currentTimeMillis：后者会被系统改时间影响。
                    downAtMillis = SystemClock.uptimeMillis()
                    pressed.value = true
                } else {
                    val remaining = minHold - (SystemClock.uptimeMillis() - downAtMillis)
                    if (remaining <= 0) {
                        pressed.value = false
                    } else {
                        releaseJob = launch {
                            delay(remaining)
                            pressed.value = false
                        }
                    }
                }
            }
        } finally {
            // 手势取消 / interactionSource 换实例 / 组件离场：不能把元素留在按下配色上。
            // 只是一次状态写入，无需 NonCancellable。
            pressed.value = false
        }
    }

    return pressed
}

