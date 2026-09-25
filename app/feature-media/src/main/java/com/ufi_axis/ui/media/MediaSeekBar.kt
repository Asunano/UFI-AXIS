package com.ufi_axis.ui.media

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.navigation.ufiSharedBounds
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiMotion
import kotlinx.coroutines.delay

/*
 * 音频进度条：**播放页与底部迷你条共用这一份**（2026-09-20 提取）。
 *
 * 提取的理由不是"代码重复"，而是这套逻辑本身很容易漂移：手势通道谁消费 down、松手后
 * 用什么顶住显示、轨道按下变粗的动画规格 —— 播放页与迷你条各写一份的那一版里，
 * 两边的观感（2dp 只读细线 vs 6dp 可拖轨道）和交互（迷你条两个 pointerInput 抢事件）
 * 已经不是一个控件了，而用户看到的是"同一个进度条在两个地方长得不一样"。
 *
 * 迷你条与播放页的差异**只由 [MediaSeekBar] 的 compact 参数表达**，不另开一份实现。
 */

/**
 * 进度条的拖拽状态（拖到哪、有没有在拖、刚 seek 完的暂存位置）。
 *
 * ## 为什么要有这个状态类，而不是让组件自己管
 * 显示位置的三级优先（拖动中 > 刚 seek 完还没回读 > 播放器回读）不只进度条要用：
 * 播放页那两行时间文字、以及"轮询期间别覆盖用户正在拖的值"都读同一份判断。
 * 状态放在调用方手里（[rememberMediaSeekState]），组件只负责画和收手势。
 *
 * ## 中间那一级（[pendingSeekMs]）不能省
 * 播放位置是轮询回来的（播放页 500ms、迷你条 1s），松手瞬间回读到的还是旧值，
 * 只按它算就会"先弹回原处、下一次轮询才跳到目标"。所以 seek 之后把目标位置暂存起来
 * 顶住显示，等轮询追上再交还。
 */
@Stable
internal class MediaSeekState {
    /** 手指正按在轨道上（含按下未移动）。轨道变粗、以及"轮询别覆盖显示"都看它。 */
    var dragging by mutableStateOf(false)
        private set

    /** 拖到的进度比例 0..1。只在 [dragging] 为真时有意义。 */
    var dragFraction by mutableFloatStateOf(0f)
        private set

    /** 已下发但播放器位置还没回读上来的目标位置；null = 没有待生效的 seek。 */
    var pendingSeekMs by mutableStateOf<Long?>(null)
        private set

    /** 按下即定位：这一刻就把显示交给手指，所以同时记下比例。 */
    fun onDragStart(fraction: Float) {
        dragging = true
        dragFraction = fraction
    }

    fun onDrag(fraction: Float) {
        dragFraction = fraction
    }

    /**
     * 抬手：返回要 seek 到的毫秒，`null` = 这次不该 seek（时长还没就位）。
     *
     * 暂存在**返回之前**写好：调用方拿到值才去 `seekTo`，顺序反了会有一帧读到旧位置。
     */
    fun onDragEnd(durationMs: Long): Long? {
        dragging = false
        if (durationMs <= 0) return null
        val target = (dragFraction * durationMs).toLong()
        pendingSeekMs = target
        return target
    }

    /** 手势被系统 / 父级取消：按"没拖过"处理，不 seek，显示交回播放器。 */
    fun onDragCancel() {
        dragging = false
    }

    internal fun clearPendingSeek() {
        pendingSeekMs = null
    }

    /** 当前**该显示**的播放位置。三级优先见类注释。 */
    fun shownPositionMs(durationMs: Long, positionMs: Long): Long =
        if (dragging && durationMs > 0) {
            (dragFraction * durationMs).toLong()
        } else {
            pendingSeekMs ?: positionMs
        }

    /** [shownPositionMs] 换算成 0..1。时长未知时按 0 画，不猜。 */
    fun fraction(durationMs: Long, positionMs: Long): Float =
        if (durationMs <= 0) {
            0f
        } else {
            (shownPositionMs(durationMs, positionMs).toFloat() / durationMs).coerceIn(0f, 1f)
        }
}

/**
 * 建一个 [MediaSeekState]，并挂上"暂存位置到期自动失效"的看门狗。
 *
 * 看门狗必须在这里而不是在组件里：暂存值是给**显示**用的，而读显示的不止进度条
 * （还有时间文字）；万一某次 seek 之后播放器始终没回读（换歌、播放器断开），
 * 没有这道到期就会永远显示那个目标位置。
 */
@Composable
internal fun rememberMediaSeekState(): MediaSeekState {
    val state = remember { MediaSeekState() }
    LaunchedEffect(state.pendingSeekMs) {
        if (state.pendingSeekMs == null) return@LaunchedEffect
        delay(SEEK_PENDING_HOLD_MS)
        state.clearPendingSeek()
    }
    return state
}

/**
 * 自绘进度条。播放页与迷你条同一个组件、同一套观感。
 *
 * 平时轨道 [SEEK_TRACK_IDLE]、按住变粗到 [SEEK_TRACK_ACTIVE]，**没有滑块** ——
 * 位置靠"已播那一段"的长度表达。触控区比轨道厚得多（细轨也要点得到），画出来只有轨道那么细。
 *
 * @param compact 迷你条形态：触控区压到 [SEEK_TOUCH_HEIGHT_COMPACT]，且轨道**贴容器上沿**
 *                而不是垂直居中。轨道本身的粗细与圆角两种形态完全一致 ——
 *                "同一个进度条"这件事不该因为它出现在哪一屏而变样。
 * @param sharedKey 跨页面共享元素的 key（见 [ufiSharedBounds]）；`null` = 不参与转场。
 *                  挂在**轨道**而不是触控区上，理由见下方实现里的注释。
 * @param trackKey 当前曲目的标识。**换歌时**已播段会从旧位置平滑走到新位置，而不是瞬移。
 *                 `null` = 不要这个平滑（永远瞬移）。为什么需要它见 [rememberSeekDisplayFraction]。
 */
@Composable
internal fun MediaSeekBar(
    state: MediaSeekState,
    durationMs: Long,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    compact: Boolean = false,
    sharedKey: String? = null,
    trackKey: Any? = null
) {
    val palette = LocalResolvedPalette.current
    val enabled = durationMs > 0
    val fraction = rememberSeekDisplayFraction(
        target = state.fraction(durationMs, positionMs),
        trackKey = trackKey,
        dragging = state.dragging
    )

    val trackHeight by animateDpAsState(
        targetValue = if (state.dragging) SEEK_TRACK_ACTIVE else SEEK_TRACK_IDLE,
        animationSpec = UfiMotion.sliderTrack(),
        label = "seekTrackHeight"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) SEEK_TOUCH_HEIGHT_COMPACT else SEEK_TOUCH_HEIGHT)
            .pointerInput(enabled, durationMs) {
                if (!enabled) return@pointerInput
                /*
                 * ★ 一个手势通道同时处理"点"和"拖"（2026-09-16 修「点不动 / 位置错位」）。
                 *
                 * 挂两个检测器（detectTapGestures + detectHorizontalDragGestures）的那一版里
                 * 两者要抢同一个 down（后者默认只接未被消费的 down），谁先拿到取决于修饰符顺序，
                 * 结果是有时点了没反应、有时按下位置与最终 seek 的位置差一截。
                 *
                 * 现在自己收：按下即定位（"点一下就跳过去"成立），移动持续更新（拖动成立），
                 * 抬手才真正 seekTo。全程只有这一个消费者，不存在竞争；down 被消费掉，
                 * 也就不会漏给下面那层（迷你条主体那块整体可点的区域）。
                 */
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    state.onDragStart((down.position.x / width).coerceIn(0f, 1f))
                    down.consume()
                    var canceled = false
                    // finally 不能省：pointerInput 的 key 含 durationMs，拖动中换歌会取消这条
                    // 手势协程 —— 没有它 dragging 永久停在 true，播放页的进度与时间文字不再刷新
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                canceled = true
                                break
                            }
                            state.onDrag((change.position.x / width).coerceIn(0f, 1f))
                            change.consume()
                            if (!change.pressed) break
                        }
                        if (canceled) {
                            state.onDragCancel()
                        } else {
                            state.onDragEnd(durationMs)?.let(onSeek)
                        }
                    } finally {
                        if (state.dragging) state.onDragCancel()
                    }
                }
            },

        // compact 形态把手势区的余量全部留给**下方**：这条轨道是迷你条的上边界，
        // 上方再留几 dp 底色就会读成"进度条上面还压着一条空条"（见 MediaAudioMiniBar 的说明）。
        contentAlignment = if (compact) Alignment.TopCenter else Alignment.Center
    ) {
        /*
         * 共享元素挂在**轨道**这一层，而不是外面那个触控区。
         *
         * 触控区两端厚度差了 3 倍（播放页 48dp / 迷你条 14dp），而轨道两端**都是**
         * [SEEK_TRACK_IDLE]。sharedBounds 是按"起点框 → 终点框"缩放内容的：
         * 用触控区当节点，起点框会把 48dp 高的内容压到 14dp（Fit 取较小比例 ⇒ 宽度也跟着
         * 缩到三成），转场一开始就是一小截居中的短条；用轨道当节点，两端只差一点宽度
         * （播放页有页面左右内边距，迷你条通栏），补间几乎就是纯位移，看不出缩放。
         */
        val sharedTrack = if (sharedKey != null) Modifier.ufiSharedBounds(sharedKey) else Modifier
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .then(sharedTrack)
        ) {
            val radius = CornerRadius(size.height / 2f)
            // 轨道（剩余）
            drawRoundRect(
                color = palette.divider,
                size = size,
                cornerRadius = radius
            )
            // 已播
            val playedWidth = size.width * fraction.coerceIn(0f, 1f)
            if (playedWidth > 0f) {
                drawRoundRect(
                    color = if (enabled) palette.accent else palette.textSecondary,
                    topLeft = Offset(0f, 0f),
                    size = Size(playedWidth, size.height),
                    cornerRadius = radius
                )
            }
        }
    }
}

/**
 * 已播段该画到哪（0..1）。平时就是 [target]，**只在换歌那一下**做平滑。
 *
 * ## 为什么需要它
 * 换歌时 [target] 是瞬变的（上一首 69% → 新一首 0%），进度条会"啪"地弹回去。
 * 封面那边已经改成柔和的对焦落位之后，这一下就成了整屏最跳眼的东西。
 *
 * ## 为什么不能简单地给它套一个 tween
 * 播放位置是**轮询**回来的（播放页 500ms、迷你条 1s）。若常态带补间，每个周期都在
 * 追一个已经往前跑了的目标，观感是进度条永远慢半拍、像根橡皮筋 ——
 * 而进度条恰恰是这一页唯一要求"所见即实际位置"的控件。
 *
 * 所以平滑是**有时间窗的**：[trackKey] 一变就开窗 [SEEK_SWITCH_SMOOTH_MS]，窗内用 tween、
 * 窗外用 `snap`。窗口长度取得比一个轮询周期短，保证窗关掉时最多只经过一次回读。
 *
 * ## 三种必须瞬移的情况
 * 1. **手指正按着**（[dragging]）—— 显示必须跟手，慢一帧都算迟滞；
 * 2. **首次组合** —— 进页时 fraction 从 0 变成真实值，平滑会让进度条"长出来"一下；
 * 3. [trackKey] 为 null —— 调用方明确不要这个行为。
 */
@Composable
private fun rememberSeekDisplayFraction(
    target: Float,
    trackKey: Any?,
    dragging: Boolean
): Float {
    var smoothing by remember { mutableStateOf(false) }
    var firstPass by remember { mutableStateOf(true) }
    LaunchedEffect(trackKey) {
        if (firstPass || trackKey == null) {
            firstPass = false
            return@LaunchedEffect
        }
        smoothing = true
        delay(SEEK_SWITCH_SMOOTH_MS.toLong())
        smoothing = false
    }
    val animated by animateFloatAsState(
        targetValue = target,
        // snap 而不是 tween(0)：前者根本不起动画，后者仍会走一帧调度
        animationSpec = if (smoothing && !dragging) {
            tween(SEEK_SWITCH_SMOOTH_MS, easing = UfiMotion.Easing.Standard)
        } else {
            snap()
        },
        label = "seekDisplayFraction"
    )
    return animated
}

/**
 * 轨道粗细：平时 / 按住时。

 *
 * 2026-09-16 加粗一轮（6/12dp，原 4/8dp）：4dp 在真机上又难看清又难对准，
 * 而这是播放页唯一需要"精确定位"的控件。迷你条也用同一组值 ——
 * 两处长得不一样才是问题，而不是"迷你条应该更细"。
 */
private val SEEK_TRACK_IDLE = 6.dp
private val SEEK_TRACK_ACTIVE = 12.dp

/** 播放页的触控区高度。48dp = 系统建议的最小可点高度，这一页有空间就给足。 */
private val SEEK_TOUCH_HEIGHT = 48.dp

/**
 * 迷你条的触控区高度。
 *
 * 14dp 是这条通栏条能挤出来的上限：它上面是列表、下面是主体行，多吃的每一 dp 都是
 * 真的没了。取值本身够用 —— 手指按点的定位误差大致在 ±5dp 量级，而轨道自己已经有
 * [SEEK_TRACK_IDLE] 那么厚，余下的 8dp 全部补在轨道**下方**（上方贴边，见 compact 说明）。
 * 按住时轨道涨到 [SEEK_TRACK_ACTIVE] 仍在这个高度里放得下。
 */
private val SEEK_TOUCH_HEIGHT_COMPACT = 14.dp

/**
 * 松手之后用暂存目标位置顶住显示的时长。
 *
 * 比最慢那条位置轮询（迷你条 1s）多留半秒余量：到点时位置已经是新的了，交还显示权
 * 不会看到跳变。短于一个周期就会在中间露出旧位置 —— 那正是这一级要修掉的
 * "松手回弹一下再跳过去"。
 */
private const val SEEK_PENDING_HOLD_MS = 1_500L

/**
 * 换歌时已播段平滑过去的时长，也是"允许平滑"那个时间窗的长度。
 *
 * 与封面的基准时长（`AUDIO_SWITCH_MS` = 280）对齐：这两件事是同一次切歌的两个面，
 * 不该一个还在走、另一个已经停了。
 *
 * **上限受轮询周期约束**：必须短于最快那条位置轮询（播放页 500ms），否则窗内会经过
 * 两次以上回读，补间被反复重定向，反而看出抖动。
 */
private const val SEEK_SWITCH_SMOOTH_MS = 280

