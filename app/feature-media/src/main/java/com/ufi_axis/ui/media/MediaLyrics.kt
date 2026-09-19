package com.ufi_axis.ui.media

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ufi_axis.data.media.UfiAudioLyrics
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.ui.theme.UfiTextStyles

/*
 * 歌词展示：封面下的滚动预览、整屏滚动。2026-09-16。
 *
 * ## 取词与解析不在这里
 * 都在 :app:data 的 [UfiAudioLyrics]（2026-09-17 抽取）：core 接口 → 旁挂 `.lrc` → 内嵌
 * USLT/SYLT/FLAC LYRICS 的优先级、以及 LRC 时间轴解析，与文件管理器的音频预览**共用同一份**。
 * 本文件只负责"怎么显示"——之前这里另有一份只会解 LRC 文本的解析，导致同一首歌在播放页
 * 读不到内嵌歌词、而在文件管理器里能读到。
 *
 * ## 没有歌词就说没有
 * 解析不出任何一行时，预览区**不占位**、整屏视图显示"没有歌词"。绝不拿曲名、文件名或
 * 「♪」之类的占位符冒充歌词。
 *
 * ## 没有时间轴的歌词也要能看
 * `.txt` 或没打时间戳的 `.lrc` 会得到一串 `timeMs = -1` 的行：这时不做高亮（没有依据），
 * 整屏视图当纯文本滚动，预览区不显示（预览的意义全在"当前这一行"）。
 */

/*
 * 整屏歌词的排版尺寸（2026-09-16 按实测反馈调大一轮）。
 *
 * 之前直接用 `sectionTitle` / `bodyLead`（≈16 / 14sp），一屏塞十来行，字小、当前句不突出。
 * 现在当前句 24sp、其余 18sp，行距与视口留白同步加大 —— 一屏只显示五六行，
 * 每一句都看得清，也更容易点准（点某句 = 跳到那一句）。
 *
 * ★ 这里写了裸字号，是本模块的**破例**（与自绘进度条同一性质）：
 *   歌词的字号是"内容主体"而不是 UI 文案层级，`UfiTextStyles` 里没有对应语义的档位；
 *   收在这一处常量里，不散落到调用点。
 */
private val LYRIC_ACTIVE_FONT_SIZE = 24.sp
private val LYRIC_ACTIVE_LINE_HEIGHT = 34.sp
private val LYRIC_IDLE_FONT_SIZE = 18.sp
private val LYRIC_IDLE_LINE_HEIGHT = 28.sp

/** 非当前句缩到这个比例：与颜色一起表达"不是这一句"，比只变灰更清楚。 */
private const val LYRIC_INACTIVE_SCALE = 0.94f

/** 行间距与视口上下留白：留白大一点，当前句滚到中部时上下都有呼吸空间。 */
private val LYRIC_LINE_GAP = 18.dp
private val LYRIC_VIEWPORT_PADDING = 40.dp

/**
 * 封面下方歌词预览的固定高度（约三行）。
 *
 * 写死高度是刻意的：预览区在封面与进度条之间，高度一变整页版式就跟着跳。
 *
 * 2026-09-19 从 80dp 加到 96dp：三行 `body` 每行约 22dp、行间 [Spacing.Small]，80dp
 * 放不下第三行 —— 表现为"上一行 1 行、下面 2 行且第二行被切一半"。
 */
private val LYRIC_PREVIEW_HEIGHT = 96.dp


/** 当前播放位置对应的行下标；-1 = 还没到第一行，或这份歌词没有时间轴。 */

internal fun ufiLyricIndexAt(lines: List<UfiAudioLyrics.Line>, positionMs: Long): Int {
    if (lines.isEmpty() || lines.first().timeMs < 0L) return -1
    var index = -1
    for (i in lines.indices) {
        if (lines[i].timeMs <= positionMs) index = i else break
    }
    return index
}

/**
 * 封面下方的歌词预览（滚动切换动画）。
 *
 * 只在**有时间轴**且已经进到第一句之后才显示内容：三行预览的全部意义就是"现在唱到这儿"，
 * 没有依据时显示三行随机歌词只会误导。
 *
 * 2026-09-19：改为微型 LazyColumn 滚动切换（与全屏歌词页 [MediaLyricsFullView] 同感觉），
 * 当前行用 `animateScrollToItem` 滚到中间，亮色高亮，其他行暗色。
 */
@Composable
internal fun MediaLyricsPreview(
    lines: List<UfiAudioLyrics.Line>,
    index: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    if (lines.isEmpty() || lines.first().timeMs < 0L) return
    val current = index.coerceAtLeast(0)
    val listState = rememberLazyListState()

    LaunchedEffect(current) {
        if (current < 0) return@LaunchedEffect
        // 居中公式见 MediaLyricsFullView 里的说明：scrollOffset = 半个 item − 半个视口
        val info = listState.layoutInfo
        val viewportH = info.viewportEndOffset - info.viewportStartOffset
        val itemSize = info.visibleItemsInfo.firstOrNull { it.index == current }?.size
            ?: info.visibleItemsInfo.firstOrNull()?.size
            ?: 0
        listState.animateScrollToItem(current, (itemSize / 2) - (viewportH / 2))
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .height(LYRIC_PREVIEW_HEIGHT)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        userScrollEnabled = false
    ) {
        itemsIndexed(lines) { i, line ->
            val active = i == current && index >= 0
            val color by animateColorAsState(
                targetValue = if (active) palette.textPrimary else palette.textSecondary.copy(alpha = 0.6f),
                label = "previewColor"
            )
            Text(
                text = line.text,
                style = if (active) UfiTextStyles.bodyLeadStrong else UfiTextStyles.body,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 整屏歌词：当前句放大加粗，自动滚到视口中部。
 *
 * 滚动用 `animateScrollToItem` 而不是每帧算偏移：歌词行高不定（有的两行），按像素推算
 * 必然与真实布局漂移，让列表自己去对齐才稳。
 *
 * ## 点一句 = 跳到那一句（2026-09-16）
 * 有时间轴时每一行都可点，点了就 [onSeek] 到该句的时间戳 —— 这是全屏歌词最实用的功能，
 * 也是它值得存在的理由。因此**退出全屏不再靠点击**（点击已经被"跳转"占了）：
 * 交给调用方的顶部 Tab 与左右滑动（见 [MediaAudioPlayerScreen]）。返回键**不**拦在这里 ——
 * 歌词页与封面页是平级的两个 Tab，按返回该直接离开播放页，而不是先退回封面再按一次。
 * 没有时间轴的纯文本歌词不给点击反馈 —— 没有可跳的目标，点了什么都不该发生。
 *
 * @param source 命中的歌词文件名，显示在底部（"词 xxx.lrc"）—— 让人知道这份歌词来自哪里。
 */
@Composable
internal fun MediaLyricsFullView(
    lines: List<UfiAudioLyrics.Line>,
    index: Int,
    source: String,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit
) {
    val palette = LocalResolvedPalette.current
    if (lines.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "没有歌词（把同名 .lrc 放到歌曲旁边即可）",
                style = UfiTextStyles.note,
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )
        }
        return
    }
    val synced = lines.first().timeMs >= 0L
    val listState = rememberLazyListState()
    LaunchedEffect(index) {
        if (index < 0) return@LaunchedEffect
        /*
         * 当前句滚到视口**正中**（2026-09-19 再修）。
         *
         * `animateScrollToItem(index, scrollOffset)` 的语义是"把该 item 的顶端放到
         * 视口起点 + scrollOffset"。所以要让它垂直居中，偏移量必须是
         *   scrollOffset = itemSize / 2 − viewportHeight / 2
         * （负值，等于把 item 往下推到中线上）。
         *
         * 之前写的是 `-(viewportHeight / 3)`：那只是"顶端落在视口 1/3 处"，与行高无关，
         * 行一高一低当前句就偏上或偏下 —— 不是居中。itemSize 取不到当前行时退而用任意
         * 一行的高度（歌词行高度相近），再不行按 0 处理（退化成顶端对齐视口中线）。
         */
        val info = listState.layoutInfo
        val viewportH = info.viewportEndOffset - info.viewportStartOffset
        val itemSize = info.visibleItemsInfo.firstOrNull { it.index == index }?.size
            ?: info.visibleItemsInfo.firstOrNull()?.size
            ?: 0
        listState.animateScrollToItem(index, (itemSize / 2) - (viewportH / 2))
    }
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(vertical = LYRIC_VIEWPORT_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LYRIC_LINE_GAP)
        ) {
            itemsIndexed(lines) { i, line ->
                val active = i == index
                val color by animateColorAsState(
                    targetValue = if (active) {
                        palette.textPrimary
                    } else {
                        palette.textSecondary.copy(alpha = 0.45f)
                    },
                    animationSpec = UfiMotion.colorSettle(),
                    label = "lyricColor"
                )
                // 切句动画：当前句放大一档并整体提亮，非当前句缩回去。
                // 只动 scale/alpha（绘制期变换），不动字号 —— 改字号会触发整列重排，
                // 每唱一句抖一下比不做动画更糟。
                val scale by animateFloatAsState(
                    targetValue = if (active) 1f else LYRIC_INACTIVE_SCALE,
                    animationSpec = UfiMotion.sliderTrack(),
                    label = "lyricScale"
                )
                Text(
                    text = line.text,
                    style = if (active) UfiTextStyles.sectionTitle.copy(
                        fontSize = LYRIC_ACTIVE_FONT_SIZE,
                        lineHeight = LYRIC_ACTIVE_LINE_HEIGHT
                    ) else UfiTextStyles.bodyLead.copy(
                        fontSize = LYRIC_IDLE_FONT_SIZE,
                        lineHeight = LYRIC_IDLE_LINE_HEIGHT
                    ),
                    color = color,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .then(
                            if (synced) {
                                Modifier.clickable { onSeek(line.timeMs) }
                            } else {
                                Modifier
                            }
                        )
                        .padding(vertical = Spacing.Small)
                )
            }
        }
        if (source.isNotBlank()) {
            Text(
                "词 · $source",
                style = UfiTextStyles.note,
                color = palette.textSecondary.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }
    }
}
