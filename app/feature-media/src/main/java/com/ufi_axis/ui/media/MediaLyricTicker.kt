package com.ufi_axis.ui.media

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一行**随歌走**的歌词：换句时整行竖向滚动切换，长句按这句的剩余停留时间横向跑一趟。
 *
 * ## 为什么抽成公共组件
 * 标题栏挂件（[UfiAudioNowPlayingChip]）与音乐页迷你条（[MediaAudioMiniBar]）都要这一行。
 * 这套逻辑里有两个已经踩过的坑（见下面 [LaunchedEffect] 的注释与 [lyricTickerTransition]），
 * 复制两份的必然结果是其中一份日后被改歪、两处表现不一致 —— 所以只留这一份。
 *
 * ## 为什么不用 `basicMarquee`
 * 它是固定速度 + 无限循环：要么这句还没跑完就换了下一句，要么跑完了还在原地绕圈。
 * 歌词要的是"跑完的时刻 ≈ 这句唱完的时刻"，所以时长必须由歌词时间轴给。
 *
 * @param text   当前要显示的那一句；空串时这一行不画任何东西（不占高度）。
 * @param holdMs 这一句还会停留多久（毫秒，通常来自 [UfiNowPlayingState.lyricHoldMs]）。
 *               `<= 0`（没时间轴 / 不知道）时不跑横向滚动，只保留手动横拖。
 *               它**只在开跑那一刻被读一次**，理由见下面的注释。
 * @param style  字体样式由调用方给：挂件那行是 `headerCaption`、迷你条第一行是 `bodyEmphasis`，
 *               本组件不替调用方决定层级。
 */
@Composable
internal fun MediaLyricTickerText(
    text: String,
    holdMs: Long,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val scroll = rememberScrollState()

    /*
     * ⚠ key 只能是 text（这一句的内容）。
     *
     * 2026-09-20 修（标题栏那行最先发现）：原来把"剩余时长"也写进了 key —— 它随位置轮询
     * 每秒变一次，于是每秒把这个 effect 重启一遍：scrollTo(0) 闪回开头、再用"剩下更少的
     * 时间"重跑，表现就是"滚不到末尾 → 闪一下 → 重滚 → 越来越快"，只有短句（不溢出）正常。
     *
     * 所以 [holdMs] 走**参数捕获**：LaunchedEffect 的 block 只在 key 变化时重新捕获，
     * 后续重组（每秒一次）不会更新它，也就天然是"开跑那一刻读一次"。
     */
    LaunchedEffect(text) {
        scroll.scrollTo(0)
        if (text.isBlank()) return@LaunchedEffect
        // 等新句子测量完：maxValue 在首帧还是上一句的（或 0），拿早了会按错的溢出量跑
        val overflow = withTimeoutOrNull(LYRIC_TICKER_MEASURE_TIMEOUT_MS) {
            snapshotFlow { scroll.maxValue }.first { it > 0 }
        } ?: return@LaunchedEffect
        if (holdMs <= 0L) return@LaunchedEffect
        // 留一截尾巴：提前跑到头、留时间让人读完，而不是在换句那一刻才刚好到终点
        val ms = (holdMs - LYRIC_TICKER_TAIL_MS)
            .coerceIn(LYRIC_TICKER_MIN_MS, LYRIC_TICKER_MAX_MS)
            .toInt()
        scroll.animateScrollTo(overflow, tween(ms, easing = LinearEasing))
    }

    AnimatedContent(
        targetState = text,
        transitionSpec = { lyricTickerTransition() },
        label = "mediaLyricTicker",
        modifier = modifier,
    ) { line ->
        if (line.isNotBlank()) {
            Text(
                line,
                style = style,
                color = color,
                maxLines = 1,
                // softWrap = false 是横向滚动的前提：允许折行的话文本会先按宽度换行，
                // 永远不会溢出，maxValue 恒为 0，横向跑那段代码等于不存在。
                softWrap = false,
                textAlign = textAlign,
                modifier = Modifier.horizontalScroll(scroll),
            )
        }
    }
}

/**
 * 换句动画：**整行高度**的向上滚动 —— 旧句整句往上走、新句整句从下方来。
 *
 * 位移量刻意给满一行（而不是常见的 1/3 行）：这一行在版式里是"唱到哪了"的快变量，
 * 走满一整行读起来就是"唱到下一句了"；挪一小截会被读成"同一句抖了一下"。
 */
private fun AnimatedContentTransitionScope<String>.lyricTickerTransition(): ContentTransform =
    (fadeIn(tween(LYRIC_TICKER_MOTION_MS)) +
        slideInVertically(tween(LYRIC_TICKER_MOTION_MS)) { it }) togetherWith
        (fadeOut(tween(LYRIC_TICKER_MOTION_MS)) +
            slideOutVertically(tween(LYRIC_TICKER_MOTION_MS)) { -it })

/** 换句滚动的时长。走满一整行高度，比"挪 1/3 行"的文字切换给得长一档才不显得甩。 */
private const val LYRIC_TICKER_MOTION_MS = 320

/**
 * 横向跑一趟的目标时长 = 这句的剩余停留时间 − [LYRIC_TICKER_TAIL_MS]：
 * 留出尾巴让人读完最后几个字，而不是在换句那一刻才刚好跑到终点。
 */
private const val LYRIC_TICKER_TAIL_MS = 600L

/**
 * 时长下限。歌词时间轴偶尔给出 0.2s 的极短句，按原值跑起来像抽搐。
 */
private const val LYRIC_TICKER_MIN_MS = 600L

/**
 * 时长上限。整段几十秒的留白（间奏里的一句）按原值跑等于几乎不动，看不出在滚。
 */
private const val LYRIC_TICKER_MAX_MS = 8_000L

/** 等新句子测量出溢出量的上限；等不到就当这句不需要滚。 */
private const val LYRIC_TICKER_MEASURE_TIMEOUT_MS = 400L
