package com.ufi_axis.ui.media

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogNote
import kotlinx.coroutines.delay
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

/**
 * 整屏歌词的行间距。
 *
 * 视口上下留白不再是常量：改成运行时取**半个视口**（见 [MediaLyricsFullView]），
 * 这样第一句与最后一句也能被滚到正中。原来那个 40dp 的 `LYRIC_VIEWPORT_PADDING` 已删除。
 */
private val LYRIC_LINE_GAP = 18.dp

/**
 * 封面下方歌词预览的单行高度与行距。
 *
 * 视口 = `3 × 行高 + 2 × 行距`，恰好三行；当前行的滚动偏移 = `−(行高 + 行距)`。
 * 两个值都写死是刻意的：预览区夹在封面与进度条之间，高度一变整页版式就跟着跳；
 * 而且滚动居中靠的就是这两个常量，改成"按实测尺寸算"会在首帧量不到而失准。
 */
private val LYRIC_PREVIEW_ROW_HEIGHT = 26.dp
private val LYRIC_PREVIEW_ROW_GAP = 4.dp


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
 * ## 为什么行高写死
 * 2026-09-19：视口固定成**恰好三行**（`3 × 行高 + 2 × 行距`），当前行永远落在正中间 ——
 * 于是滚动偏移是个常量 `−(行高 + 行距)`，不需要去 `layoutInfo` 里量。
 * 之前按实测 item 尺寸算居中，首帧 `visibleItemsInfo` 还是空的、量不到，就退化成
 * "上一行、下两行且最后一行被裁"。行高写死之后这个时序问题不存在。
 *
 * 行高统一（当前行也不放大字号，只改字重与颜色）：字号一变，三行的总高就会跟着变，
 * 视口高度与滚动偏移这两个常量当场失效。
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

    /*
     * 居中偏移是 0，不是 −(行高 + 行距)。
     *
     * `scrollToItem(i, off)` 最终把 item 顶端放在 `beforeContentPadding − off`。
     * 这里的 contentPadding 上方正好垫了一行（行高 + 行距），所以 off = 0 时
     * item 顶端就落在第二行的位置 —— 三行视口的正中。
     *
     * 2026-09-20 修：加了 contentPadding 之后还在用旧的 −(行高 + 行距)，
     * 于是 item 被推到 `2 × (行高 + 行距)`，当前句跑到了最下面那一行。
     */
    /*
     * 首次定位用**瞬时**（`scrollToItem`），之后才用动画（2026-10-14）。
     *
     * 列表一开始停在第一句：用 `animateScrollToItem` 滚过去的话，用户看到的是"歌词从第一行
     * 一路刷下来"，而这一刻的真实语义是"这首歌已经唱到第 N 句了"。所以第一次直接落位。
     * 之后的换句仍然走动画 —— 整行竖向滚动是这一栏的常态反馈。
     */
    val firstFix = remember { mutableStateOf(true) }
    LaunchedEffect(current) {
        if (firstFix.value) {
            firstFix.value = false
            listState.scrollToItem(current, 0)
        } else {
            listState.animateScrollToItem(current, 0)
        }
    }
    /*
     * 手动滑动后的回位（2026-10-21）：手一松、停 [LYRIC_RESUME_AFTER_SCROLL_MS] 就把当前句拉回中间。
     *
     * 原来只有"换下一句"时才会回位 —— 用户滑开去看后面几句之后，得等这首歌再唱一句
     * （慢歌十几秒）才回来，主观上就是"回得很慢"。这里补一条"松手就回"，
     * 并且**只有当前句完全不在视口里**才动，避免和我们自己的滚动动画互相打架。
     */
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) return@collect
            delay(LYRIC_RESUME_AFTER_SCROLL_MS)
            if (listState.isScrollInProgress) return@collect
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == current }
            if (!visible) listState.animateScrollToItem(current, 0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .height(LYRIC_PREVIEW_ROW_HEIGHT * 3 + LYRIC_PREVIEW_ROW_GAP * 2)
            .clickable(onClick = onClick),
        // 首尾各垫一行：第一句/最后一句也能落在中间那一行，而不是贴着顶或底
        contentPadding = PaddingValues(
            vertical = LYRIC_PREVIEW_ROW_HEIGHT + LYRIC_PREVIEW_ROW_GAP
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LYRIC_PREVIEW_ROW_GAP),
        // 2026-09-20 放开手动滚动：之前锁死成"只能看"的容器，想往前/后翻几句只能进整屏歌词页。
        // 放开之后自动跟播仍然生效（下一句到点时 animateScrollToItem 把视口拉回当前句）。
        userScrollEnabled = true
    ) {
        itemsIndexed(lines) { i, line ->
            val active = i == current && index >= 0
            val color by animateColorAsState(
                targetValue = if (active) palette.textPrimary else palette.textSecondary.copy(alpha = 0.6f),
                label = "previewColor"
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LYRIC_PREVIEW_ROW_HEIGHT),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = line.text,
                    style = if (active) UfiTextStyles.bodyLeadStrong else UfiTextStyles.bodyLead,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
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
    // 首次定位瞬时落位、之后才动画，理由与 MediaLyricsPreview 里那一段逐字相同（2026-10-14）
    val firstFix = remember { mutableStateOf(true) }
    LaunchedEffect(index) {
        if (index < 0) return@LaunchedEffect
        /*
         * 当前句滚到视口**正中**（2026-09-20 再修）。
         *
         * `scrollToItem(index, off)` 最终把该 item 的顶端放在
         *   `beforeContentPadding − off`
         * 这一页的 contentPadding 上方是**半个视口**（H/2），要让 item 居中就得把顶端放在
         * `H/2 − itemSize/2`，代入得
         *   off = H/2 − (H/2 − itemSize/2) = itemSize / 2
         *
         * 之前写的是 `itemSize/2 − viewportH/2`，且 viewportH 取的是
         * `viewportEndOffset − viewportStartOffset` —— 那个差值**含 contentPadding**
         * （= H + H/2 + H/2 = 2H），于是多滚了整整一个 H，当前句被推到视口底部。
         *
         * itemSize 取不到当前行时退而用任意一行（歌词行高度相近），再不行按 0 处理
         * （退化成顶端贴中线，略偏下但不会跑飞）。
         */
        val info = listState.layoutInfo
        val itemSize = info.visibleItemsInfo.firstOrNull { it.index == index }?.size
            ?: info.visibleItemsInfo.firstOrNull()?.size
            ?: 0
        if (firstFix.value) {
            firstFix.value = false
            listState.scrollToItem(index, itemSize / 2)
        } else {
            listState.animateScrollToItem(index, itemSize / 2)
        }
    }
    /*
     * 手动滑动后的回位（2026-10-21），与三行预览那条同一条理由：
     * 手松开后停 [LYRIC_RESUME_AFTER_SCROLL_MS] 就回位，而不是等下一句歌词到点。
     * 判据同样是"当前句不在视口里才动"，所以程序性滚动结束时不会自己再触发一次。
     */
    val currentIndex by rememberUpdatedState(index)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) return@collect
            delay(LYRIC_RESUME_AFTER_SCROLL_MS)
            if (listState.isScrollInProgress) return@collect
            val idx = currentIndex
            if (idx < 0) return@collect
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == idx }
            if (!visible) {
                val info = listState.layoutInfo
                val itemSize = info.visibleItemsInfo.firstOrNull()?.size ?: 0
                listState.animateScrollToItem(idx, itemSize / 2)
            }
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        /*
         * 用 BoxWithConstraints 拿到视口高度，把 contentPadding 定成**半个视口**。
         *
         * 2026-09-20：原来是固定 40dp 的上下留白 —— 于是刚开播（index=0）时第一句只能贴在
         * 距顶 40dp 处、结尾最后一句也到不了中线，只有中间那些句子才真的居中。
         * 首尾各垫半个视口之后，任何一句（含第一句、最后一句）都能被滚到正中。
         */
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = maxHeight / 2),
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
        }
        /*
         * 歌词来源（2026-10-21 精简）：
         *
         * 原来直接把 `source` 整条丢出来（`内嵌歌词 · 某某某.flac` / `某某某.lrc`），文件名一长
         * 就把这一行撑得很乱。现在只显示**类别**（内嵌歌词 / 旁挂歌词 / 服务端歌词），
         * 点一下才展开完整来源 —— 想知道"到底从哪来的"的人一点就有，不想知道的人只看到两个字。
         */
        if (source.isNotBlank()) {
            var showSource by remember { mutableStateOf(false) }
            Text(
                "词 · ${lyricSourceCategory(source)}",
                style = UfiTextStyles.note,
                color = palette.textSecondary.copy(alpha = 0.7f),
                modifier = Modifier
                    .clickable { showSource = true }
                    .padding(top = 4.dp, bottom = 4.dp, start = 12.dp, end = 12.dp)
            )
            if (showSource) {
                UfiCustomDialog(
                    visible = true,
                    onDismiss = { showSource = false },
                    title = "歌词来源"
                ) {
                    UfiDialogNote(source)
                }
            }
        }
    }
}

/**
 * 手动滑动之后，等多久把当前句拉回视口（毫秒）。
 *
 * 600ms 是"用户抬手、视线落稳"的量级：再短会在惯性滑动还没停时抢滚动，
 * 再长就回到"要等下一句"的老观感。
 */
private const val LYRIC_RESUME_AFTER_SCROLL_MS = 600L

/**
 * 歌词来源 → **类别**（2026-10-21）。
 *
 * `UfiAudioLyrics.Result.source` 是三种形态混在一起的自由文本：
 * · 服务端：接口回的 `source` 字段；
 * · 旁挂：`.lrc` 的**文件名**；
 * · 内嵌：固定为 `内嵌歌词 · 文件名`。
 * 页脚只要"哪一类"，完整来源点开弹窗看（见 `MediaLyricsFullView` 的页脚）。
 */
internal fun lyricSourceCategory(source: String): String = when {
    source.contains("内嵌") -> "内嵌歌词"
    source.endsWith(".lrc", ignoreCase = true) -> "旁挂歌词"
    source.contains("服务端") || source.contains("core", ignoreCase = true) -> "服务端歌词"
    // 认不出的取值原样透出：至少是可检索的字符串，而不是一个空洞的"歌词"标签
    else -> source
}