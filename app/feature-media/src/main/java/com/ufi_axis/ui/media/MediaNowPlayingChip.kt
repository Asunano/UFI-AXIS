package com.ufi_axis.ui.media

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.data.media.UfiAudioLyrics
import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiNowPlayingSlot
import com.ufi_axis.ui.components.common.UfiSlider
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.delay

/**
 * 「正在播放」的当前状态（进程级单例）。
 *
 * ## 为什么需要它，而不是让控件自己连播放器
 * 这块内容渲染在**页面标题栏**里（[UfiNowPlayingSlot]），而标题栏要先知道"有没有东西要显示"
 * 才能决定版式（有 → 标题靠左让出右侧；没有 → 标题居中）。如果探测逻辑写在控件内部，
 * 就成了死结：没探测过 ⇒ 不渲染控件 ⇒ 永远探测不到。
 *
 * 所以拆成两半：[UfiAudioNowPlayingProbe] 常驻在 Activity 顶层负责连播放器、写这里的状态；
 * [UfiAudioNowPlayingChip] 只读这里的状态渲染。顺带避免了两个地方各连一个 MediaController。
 */
internal object UfiNowPlayingState {
    /** 遥控器。控件上的按钮直接用它 —— 全 App 只有这一个连接。 */
    val controller: MutableState<Player?> = mutableStateOf(null)
    val title: MutableState<String> = mutableStateOf("")
    /** 副标题：有歌词时是**当前这一句**，否则是歌手。 */
    val subtitle: MutableState<String> = mutableStateOf("")
    /** 副标题此刻是不是歌词（决定用不用 accent 色）。 */
    val subtitleIsLyric: MutableState<Boolean> = mutableStateOf(false)
    val artworkUrl: MutableState<String?> = mutableStateOf(null)
    val isPlaying: MutableState<Boolean> = mutableStateOf(false)
    val hasNext: MutableState<Boolean> = mutableStateOf(false)
    val hasPrev: MutableState<Boolean> = mutableStateOf(false)
    /** 歌手单独给出：chip 里第一行要拼「歌名 · 歌手」，第二行才是歌词。 */
    val artist: MutableState<String> = mutableStateOf("")
    /** 当前曲目的文件路径（= MediaItem 的 mediaId），点进播放页要用它。 */
    val mediaId: MutableState<String> = mutableStateOf("")
    /** 播放位置与总长（毫秒）：浮层里那条进度用。0 = 还不知道。 */
    val positionMs: MutableState<Long> = mutableStateOf(0L)
    val durationMs: MutableState<Long> = mutableStateOf(0L)
    /** 循环模式与随机开关：迷你控制器要把这两个真实能力摆出来（见 [Player.RepeatMode]）。 */
    val repeatMode: MutableState<Int> = mutableStateOf(Player.REPEAT_MODE_OFF)
    val shuffleEnabled: MutableState<Boolean> = mutableStateOf(false)
    /**
     * 当前这句歌词还会停留多久（毫秒）。
     *
     * 给标题栏那行长歌词做"按时间横向跑"用：跑完的时间对上这句唱完的时间，
     * 而不是用固定速度的跑马灯 —— 后者要么没跑完就换句、要么跑完还在原地绕圈。
     * 0 = 不知道（没歌词 / 没时间轴），此时不跑。
     */
    val lyricHoldMs: MutableState<Long> = mutableStateOf(0L)
}

/**
 * 「正在播放」探测器：不画任何东西，只连播放服务并把状态写进 [UfiNowPlayingState]。
 *
 * 挂在 Activity 顶层（`MainActivity`），因此与页面切换无关 —— 音乐在放，任何页面的标题栏
 * 都能显示；音乐停了，标题栏自动回到原来的居中版式。
 *
 * 歌词与播放页共用 [UfiAudioLyrics]：能在媒体库列表里按路径找到 id 就先问 core（服务端直读），
 * 找不到就按路径找旁挂 `.lrc` 与内嵌歌词。
 */
@Composable
fun UfiAudioNowPlayingProbe(viewModel: MainViewModel) {
    val controller = rememberUfiAudioController()
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }
    val httpClient = remember(context) { UfiStreamHttpClient.get(context.applicationContext) }
    val api = remember(prefs) { RetrofitClient.getApiService(prefs) }

    var meta by remember { mutableStateOf<MediaMetadata?>(null) }
    var mediaId by remember { mutableStateOf("") }
    var positionMs by remember { mutableStateOf(0L) }

    /*
     * 标题栏这块地要不要继续占着 —— 三个输入，判定在下面那个 LaunchedEffect 里。
     *
     * 2026-09-20：原来只看"队列非空"，于是暂停、甚至整张歌单放完之后挂件仍然赖在标题栏，
     * 天气永远被压着。现在改成"还在放 / 刚暂停一会儿"才占着。
     */
    var hasContent by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    /** 队列跑到底：没有下一首、且不循环 —— 单曲/列表循环都会继续放，不算放完。 */
    var queueExhausted by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        val c = controller
        UfiNowPlayingState.controller.value = c
        if (c == null) {
            UfiNowPlayingSlot.active.value = false
            return@DisposableEffect onDispose { }
        }
        fun sync() {
            mediaId = c.currentMediaItem?.mediaId.orEmpty()
            meta = c.mediaMetadata
            // 播放/暂停图标读**播放意图**而不是 isPlaying：切歌那一下 playbackState 会掉进
            // BUFFERING，isPlaying 随之短暂为 false，图标会抽一下（见 Player.ufiPlayIntent）。
            UfiNowPlayingState.isPlaying.value = c.ufiPlayIntent()
            // 两颗键的可用性与 ufiSkipToNext/Previous 用同一条判据：hasNextMediaItem() 在
            // 单曲循环下恒为 true（"下一项"是自己），键亮着但按下去只会重播当前这首
            UfiNowPlayingState.hasNext.value = c.ufiCanSkipToNext()
            UfiNowPlayingState.hasPrev.value = c.ufiCanSkipToPrevious()
            UfiNowPlayingState.mediaId.value = mediaId
            UfiNowPlayingState.repeatMode.value = c.repeatMode
            UfiNowPlayingState.shuffleEnabled.value = c.shuffleModeEnabled
            // 队列空 = 这次进程里还没播过任何东西：标题栏不该为它改版式
            hasContent = c.mediaItemCount > 0 && mediaId.isNotBlank()
            // 这一个刻意仍用 isPlaying：它决定标题栏挂件要不要让位，
            // 那是"此刻真的在出声吗"的问题，不是"用户想不想播"。
            playing = c.isPlaying
            ended = c.playbackState == Player.STATE_ENDED
            queueExhausted = !c.hasNextMediaItem() && c.repeatMode == Player.REPEAT_MODE_OFF
        }
        sync()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) = sync()

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = sync()
            override fun onPlaybackStateChanged(state: Int) = sync()
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) = sync()
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = sync()
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = sync()
            override fun onRepeatModeChanged(repeatMode: Int) = sync()
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = sync()
        }
        c.addListener(listener)
        onDispose {
            c.removeListener(listener)
            UfiNowPlayingState.controller.value = null
            UfiNowPlayingSlot.active.value = false
        }
    }

    /*
     * 标题栏右侧这块地的占用判定（2026-09-20 新增让位规则）。
     *
     * - 还在放 → 一直占着；**一按播放立刻回来**（这个 effect 会因 playing 变化立即重跑）
     * - 播完最后一首且不循环 → 立刻让位：已经没有"待续"了，留着只是挡住天气
     *   （单曲/列表循环时 queueExhausted 为 false —— 那只是这一首结束，歌还会继续）
     * - 暂停 → 给 [NOW_PLAYING_PAUSE_YIELD_MS] 的缓冲再让位：
     *   随手暂停几秒又继续很常见，立刻让位会让标题栏来回跳版式
     */
    LaunchedEffect(hasContent, playing, ended, queueExhausted) {
        if (!hasContent) {
            UfiNowPlayingSlot.active.value = false
            return@LaunchedEffect
        }
        if (playing) {
            UfiNowPlayingSlot.active.value = true
            return@LaunchedEffect
        }
        if (ended && queueExhausted) {
            UfiNowPlayingSlot.active.value = false
            return@LaunchedEffect
        }
        delay(NOW_PLAYING_PAUSE_YIELD_MS)
        UfiNowPlayingSlot.active.value = false
    }

    // 位置轮询：只用来挑"当前是哪一句歌词"，1s 足够
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            positionMs = c.currentPosition
            // 同步到全局状态：弹窗里的进度条和时间标签读的是这两个值
            UfiNowPlayingState.positionMs.value = c.currentPosition
            UfiNowPlayingState.durationMs.value = c.duration.coerceAtLeast(0)
            delay(1_000)
        }
    }

    // 曲名 / 歌手：播放器解出的标签优先，没有才从文件名猜（与播放页同一套口径）
    LaunchedEffect(meta, mediaId) {
        val guessed = splitFileNameTitleArtist(audioFileBase(mediaId.substringAfterLast('/')))
        UfiNowPlayingState.title.value =
            meta?.title?.toString()?.takeIf { it.isNotBlank() } ?: guessed.first
        UfiNowPlayingState.artist.value = meta?.artist?.toString()?.takeIf { it.isNotBlank() }
            ?: meta?.albumArtist?.toString()?.takeIf { it.isNotBlank() }
            ?: guessed.second
            ?: ""
    }

    val media = viewModel.media
    val mediaState by media.state.collectAsState()
    val trackId = remember(mediaId, mediaState) {
        mediaState.tab(MEDIA_TYPE_AUDIO).items.firstOrNull { it.path == mediaId }?.id
    }

    /*
     * 封面：队列项自带的 `artworkUri` 优先，拿不到才用媒体库那份记录的 id 现拼一个。
     *
     * 为什么要这个兜底（2026-09-23「迷你条/标题栏挂件不显示封面」）：这两处读的都是**队列项**的
     * artworkUri，而队列不是只有播放页装的那一种 —— 进程重启后从快照恢复的、以及"这首歌不在
     * 当前范围里"的单曲兜底队列，都可能没带封面 URL。播放页自己是按 `MediaLibraryItem.id`
     * 查封面的，所以那一屏永远正常，这个 bug 就这么溜过去了。两条路都取不到才真的没有封面。
     */
    LaunchedEffect(meta, trackId) {
        UfiNowPlayingState.artworkUrl.value = meta?.artworkUri?.toString()
            ?: trackId?.let { media.coverUrl(it) }
    }
    var lyrics by remember { mutableStateOf<List<UfiAudioLyrics.Line>>(emptyList()) }
    LaunchedEffect(trackId, mediaId) {
        lyrics = emptyList()
        if (mediaId.isBlank()) return@LaunchedEffect
        lyrics = UfiAudioLyrics.load(
            api = api,
            mediaId = trackId,
            httpClient = httpClient,
            prefs = prefs,
            filePath = mediaId
        )?.lines.orEmpty()
    }

    // 第二行只放**歌词**：歌手已经单独占一行了，这里再退回歌手就是同一句话说两遍。
    // 没歌词（或还没解析出来）就留空，这一行自然消失。
    LaunchedEffect(lyrics, positionMs) {
        val idx = ufiLyricIndexAt(lyrics, positionMs)
        val line = idx.takeIf { it >= 0 }
            ?.let { lyrics.getOrNull(it)?.text }
            ?.takeIf { it.isNotBlank() }
        UfiNowPlayingState.subtitle.value = line.orEmpty()
        UfiNowPlayingState.subtitleIsLyric.value = line != null
        /*
         * 这一句还会停留多久 = 下一句的时间戳 − 当前播放位置。
         * 最后一句没有"下一句"，退回整首的剩余时长。
         *
         * 精度受位置轮询（1s）限制，够用：这个值只用来定"横向跑多快"，
         * 差个几百毫秒看不出来，但比固定速度的跑马灯准得多。
         */
        UfiNowPlayingState.lyricHoldMs.value = if (idx < 0) {
            0L
        } else {
            val end = lyrics.getOrNull(idx + 1)?.timeMs
                ?: UfiNowPlayingState.durationMs.value.takeIf { it > 0 }
            (end?.minus(positionMs) ?: 0L).coerceAtLeast(0L)
        }
    }
}

/**
 * 标题栏右侧的「正在播放」控件：小封面 + 曲名/当前歌词。
 *
 * 点击封面打开迷你播放器弹窗（[NowPlayingMiniDialog]），替代原来的控制气泡。
 * 点文字区进全屏播放页。
 */
@Composable
fun UfiAudioNowPlayingChip(navController: NavHostController) {
    val palette = LocalResolvedPalette.current
    val controller = UfiNowPlayingState.controller.value ?: return
    val mediaId = UfiNowPlayingState.mediaId.value
    if (mediaId.isBlank()) return

    val title = UfiNowPlayingState.title.value
    val subtitle = UfiNowPlayingState.subtitle.value
    val isLyric = UfiNowPlayingState.subtitleIsLyric.value
    val isPlaying = UfiNowPlayingState.isPlaying.value
    val hasNext = UfiNowPlayingState.hasNext.value
    val hasPrev = UfiNowPlayingState.hasPrev.value
    val artwork = UfiNowPlayingState.artworkUrl.value
    val artistLine = UfiNowPlayingState.artist.value

    var dialogOpen by remember { mutableStateOf(false) }

    /*
     * 2026-09-21：去掉整块的 clip + background + 横向内边距 + 纵向内边距。
     *
     * 底色与横向内边距：原来是一个半透明圆角块（surfaceMuted @ 0.55）+ 8dp 内边距。
     * 块的外边缘确实落在 16dp（HeaderPaddingH），但**块里最靠右的是 48dp 封面** ——
     * 封面距屏幕边缘 16 + 8 = 24dp，比左侧标题文字的 16dp 多 8dp，看着就是整块偏左。
     * 而天气挂件本来就没有底色，两者轮流占同一个位置却一个有块一个没块，切换时也不齐。
     *
     * 纵向那 2dp：它让挂件内容的上下沿各比左侧标题列内缩 2dp，"顶底对齐"就做不到
     * （与天气挂件同一处理，见 UfiHeaderWeather）。文字不会贴到标题栏边缘 ——
     * 标题栏自己有 HEADER_PADDING_TOP/BOTTOM_NOW_PLAYING。
     *
     * 现在与天气挂件、左侧标题统一：无底色、无内边距，内容直接从 16dp 起排。
     * 封面自己仍有圆角与底色（见下面那个 Box），所以不会看起来"裸"。
     */
    Row(
        modifier = Modifier
            // 撑满页壳给右侧插槽的高度 —— 那个高度是**左侧"标题 + 诗句"反推**出来的
            // （页壳用 Row + height(IntrinsicSize.Min) 做的，见 UfiHeader 里那段说明），
            // 所以这里不需要任何写死的 dp，改字号也不会错位。
            .fillMaxHeight(),
        // 内容**贴底**而不是居中：左侧最后一行是诗句、它的底沿就是标题列的底沿，
        // 右侧两行加起来比左侧矮，居中的话最后一行会浮在诗句上方一截。
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = CHIP_TEXT_MAX_WIDTH)
                .clip(RoundedCornerShape(Spacing.Small))
                .clickable { navController.navigate(mediaRouteOf("media/audio", mediaId)) }
                .padding(horizontal = 2.dp),
            horizontalAlignment = Alignment.End
        ) {
            // 第一行：歌名
            AnimatedContent(
                targetState = title,
                transitionSpec = { chipTextTransition() },
                label = "nowPlayingTitle"
            ) { text ->
                Text(
                    text,
                    style = UfiTextStyles.headerSubtitle,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
            }
            // 第二行：歌手（比歌名小一档 —— 一眼先看到的应该是歌名）。
            // 前缀一个「- 」把它与上面的歌名区分开，不然两行同色小字读起来像一句被折行了。
            // 颜色比 textSecondary 再浅一档（[CHIP_ARTIST_ALPHA]）：三行要有三级层级，
            // 歌手与歌词同用 textSecondary 时中间那一档等于不存在。
            AnimatedContent(
                targetState = artistLine,
                transitionSpec = { chipTextTransition() },
                label = "nowPlayingArtist"
            ) { text ->
                if (text.isNotBlank()) {
                    Text(
                        "- $text",
                        style = UfiTextStyles.caption,
                        color = palette.textSecondary.copy(alpha = CHIP_ARTIST_ALPHA),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End
                    )
                }
            }
            /*
             * 第三行：当前歌词。与上面的歌手之间留一道 [CHIP_LYRIC_GAP] ——
             * 上两行是"这首歌是什么"（慢变量），这一行是"唱到哪了"（快变量），
             * 挨在一起会读成三行同类信息。
             *
             * 换句动效与长句横向滚动都在 [MediaLyricTickerText] 里（音乐页迷你条第一行
             * 用的是同一个组件）。非歌词的副标题（目前只有"没解析出歌词"这一种情况，
             * 此时 subtitle 为空）不会走到这里，所以颜色恒用 accent 之外还留了 textSecondary
             * 兜底，语义与改动前一致。
             */
            MediaLyricTickerText(
                text = subtitle,
                holdMs = UfiNowPlayingState.lyricHoldMs.value,
                style = UfiTextStyles.headerCaption,
                color = if (isLyric) palette.accent else palette.textSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(top = CHIP_LYRIC_GAP),
            )
        }

        // 封面：点击打开迷你播放器弹窗
        Box(
            modifier = Modifier
                .size(CHIP_COVER)
                .clip(RoundedCornerShape(Spacing.Small))
                .background(palette.surfaceMuted)
                .clickable { dialogOpen = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = "播放控制",
                tint = palette.textSecondary,
                modifier = Modifier.size(16.dp)
            )
            AnimatedContent(
                targetState = artwork,
                transitionSpec = {
                    fadeIn(tween(CHIP_MOTION_MS)) togetherWith fadeOut(tween(CHIP_MOTION_MS))
                },
                label = "nowPlayingCover"
            ) { url ->
                if (url != null) {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    // 迷你播放器弹窗
    if (dialogOpen) {
        NowPlayingMiniDialog(
            visible = true,
            title = title,
            artist = artistLine,
            artwork = artwork,
            isPlaying = isPlaying,
            hasPrev = hasPrev,
            hasNext = hasNext,
            positionMs = UfiNowPlayingState.positionMs.value,
            durationMs = UfiNowPlayingState.durationMs.value,
            repeatMode = UfiNowPlayingState.repeatMode.value,
            shuffleEnabled = UfiNowPlayingState.shuffleEnabled.value,
            onPrev = { controller.ufiSkipToPrevious() },
            onToggle = { if (isPlaying) controller.pause() else controller.play() },
            onNext = { controller.ufiSkipToNext() },
            onSeek = { controller.seekTo(it) },
            // 三态循环：关 → 列表 → 单曲 → 关（与播放页同一套顺序）
            onCycleRepeat = {
                controller.repeatMode = when (controller.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            },
            onToggleShuffle = { controller.shuffleModeEnabled = !controller.shuffleModeEnabled },
            onDismiss = { dialogOpen = false },
            onGoToPlayer = {
                dialogOpen = false
                navController.navigate(mediaRouteOf("media/audio", mediaId))
            }
        )
    }
}

/**
 * 迷你播放器弹窗 —— 公共弹窗组件里的一个**紧凑控制器**。
 *
 * ## 版式（2026-09-19 按参考图重排）
 * ```
 * ┌──────────────────────────────────────┐
 * │ [封面64]  歌名                    ⤢  │  ← 整行可点进播放页；右端是展开入口
 * │           歌手                       │
 * │ ●━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │  ← 可拖动定位
 * │ 0:32                          3:45  │
 * │  🔁     ⏮     ( ▶ )     ⏭      🔀   │  ← 五键等距，播放键是实心圆
 * └──────────────────────────────────────┘
 * ```
 *
 * ## 为什么五个键都是真能力
 * 参考图右上那颗 ♡ 和右下那个 ☰ 在本项目里没有对应能力（没有收藏、队列要列表页才放得下），
 * 摆上去就是假按钮。换成**循环模式**与**随机**——两者都是播放器自带、点了立刻生效，
 * 而且与播放页底部那一排同源，两处状态永远一致。
 *
 * 歌词那一行去掉了：参考版式里这一格是留给进度的，歌词在标题栏 chip 和播放页都已经有。
 */
@Composable
private fun NowPlayingMiniDialog(
    visible: Boolean,
    title: String,
    artist: String,
    artwork: String?,
    isPlaying: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    positionMs: Long,
    durationMs: Long,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onDismiss: () -> Unit,
    onGoToPlayer: () -> Unit
) {
    val palette = LocalResolvedPalette.current

    /*
     * 滑块显示值的三级优先：拖动中 > 刚 seek 完还没回读 > 播放器回读。
     *
     * 中间那一级是为了修掉"松手回弹一下再跳过去"：位置是 1s 轮询回来的，松手瞬间
     * positionMs 还是旧值，只按它算就会先弹回原处、下一次轮询才跳到目标。
     * 所以 seek 之后把目标位置暂存在 pendingSeekMs，显示用它顶住，等轮询追上再交还。
     */
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(pendingSeekMs) {
        if (pendingSeekMs == null) return@LaunchedEffect
        // 比一个轮询周期（1s）多留半秒余量，到点时 positionMs 已经是新位置了
        delay(1_500)
        pendingSeekMs = null
    }

    val shownPositionMs = when {
        dragging && durationMs > 0 -> (dragFraction * durationMs).toLong()
        else -> pendingSeekMs ?: positionMs
    }
    val shownFraction =
        if (durationMs > 0) (shownPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = null,
        showCloseButton = false
    ) {
        // Box 而不是直接 Column：右上角那颗"去播放页"要按**弹窗边框**定位
        // （与标准关闭按钮同一口径），不能跟着第一行文字走。
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DIALOG_SECTION_GAP)
            ) {
                /*
                 * ① 封面（左）+ 歌名/歌手/进度（右），整块高度锁死成封面边长。
                 *
                 * 右侧三行的高度预算：`UfiSlider` 的轨道行有 44dp 的触摸下限（见该组件的
                 * `minTrackRowHeight`，不是随便能压的），歌名 bodyEmphasis ≈ 20dp、
                 * 歌手 caption ≈ 16dp，加两道 2dp 行距 ≈ 84dp —— 所以封面定 88dp，
                 * 三行刚好装得下，余量由 SpaceBetween 摊到两端。
                 *
                 * 封面尺寸与这个预算是**绑死**的：调小封面就得同时压字号或换掉滑块，
                 * 只改一边必然出现"第三行被挤出封面下沿"。
                 */
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DIALOG_COVER)
                ) {
                    Box(
                        modifier = Modifier
                            .size(DIALOG_COVER)
                            .clip(RoundedCornerShape(DIALOG_COVER_CORNER))
                            .background(palette.surfaceMuted)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onGoToPlayer
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = palette.textSecondary,
                            modifier = Modifier.size(DIALOG_COVER_PLACEHOLDER_ICON)
                        )
                        if (artwork != null) {
                            AsyncImage(
                                model = artwork,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 歌名 + 歌手打包成一组：让 SpaceBetween 只在「文字组 ↔ 进度行」之间分余量。
                        // 原来三者平级，余量被平均成两个约 2dp 的缝，歌名一换大档余量就被吃掉、
                        // 歌手会贴在歌名底下；组内间距显式给才稳。
                        Column {
                            Text(
                                text = title.ifBlank { "未知曲目" },
                                style = UfiTextStyles.panelTitle,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // 只有歌名这一行要给右上角那颗图标让位；
                                // 进度条不让，否则滑块右端会莫名短一截
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = DIALOG_TRAILING_RESERVE)
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick = onGoToPlayer
                                    )
                            )
                            Spacer(Modifier.height(DIALOG_TITLE_ARTIST_GAP))
                            Text(
                                text = artist.ifBlank { "未知歌手" },
                                style = UfiTextStyles.caption,
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // 进度：左已播 — 公共滑块 — 右总时长（时间贴在滑块两端，不另起一行）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
                        ) {
                            Text(
                                text = formatDuration(shownPositionMs),
                                style = UfiTextStyles.caption,
                                color = palette.textSecondary,
                                maxLines = 1
                            )
                            UfiSlider(
                                value = shownFraction,
                                onValueChange = {
                                    dragging = true
                                    dragFraction = it
                                },
                                onValueChangeFinished = {
                                    dragging = false
                                    if (durationMs > 0) {
                                        val target = (dragFraction * durationMs).toLong()
                                        pendingSeekMs = target
                                        onSeek(target)
                                    }
                                },
                                // 时长未知时（还没 prepare 好）不给拖，拖了也算不出目标毫秒
                                enabled = durationMs > 0,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = formatDuration(durationMs),
                                style = UfiTextStyles.caption,
                                color = palette.textSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }

                // ③ 五键等距，各带文字：循环 / 上一首 / 播放暂停 / 下一首 / 随机
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    MiniControlAction(
                        icon = if (repeatMode == Player.REPEAT_MODE_ONE) {
                            Icons.Default.RepeatOne
                        } else {
                            Icons.Default.Repeat
                        },
                        label = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> "不循环"
                            Player.REPEAT_MODE_ALL -> "列表循环"
                            else -> "单曲循环"
                        },
                        iconSize = DIALOG_SIDE_ICON,
                        active = repeatMode != Player.REPEAT_MODE_OFF,
                        modifier = Modifier.weight(1f),
                        onClick = onCycleRepeat
                    )
                    MiniControlAction(
                        icon = Icons.Default.SkipPrevious,
                        label = "上一首",
                        iconSize = DIALOG_SKIP_ICON,
                        enabled = hasPrev,
                        modifier = Modifier.weight(1f),
                        onClick = onPrev
                    )
                    MiniControlAction(
                        icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        label = if (isPlaying) "暂停" else "播放",
                        iconSize = DIALOG_PLAY_ICON,
                        filled = true,
                        modifier = Modifier.weight(1f),
                        onClick = onToggle
                    )
                    MiniControlAction(
                        icon = Icons.Default.SkipNext,
                        label = "下一首",
                        iconSize = DIALOG_SKIP_ICON,
                        enabled = hasNext,
                        modifier = Modifier.weight(1f),
                        onClick = onNext
                    )
                    MiniControlAction(
                        icon = Icons.Default.Shuffle,
                        label = if (shuffleEnabled) "随机：开" else "随机：关",
                        iconSize = DIALOG_SIDE_ICON,
                        active = shuffleEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = onToggleShuffle
                    )
                }
            }

            /*
             * 右上角"去播放页"：与标准弹窗关闭按钮同一口径 —— 触控区 44dp、图标 22dp，
             * 用 offset 把触控区多出来的那 11dp padding 抵消掉，让**图标**（不是触控区）
             * 到右/上边框的距离与内容边距一致。
             */
            IconButton(
                onClick = onGoToPlayer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = DIALOG_TRAILING_INSET, y = -DIALOG_TRAILING_INSET)
                    .size(DIALOG_TRAILING_TOUCH)
            ) {
                Icon(
                    Icons.Default.OpenInFull,
                    contentDescription = "打开播放页",
                    tint = palette.textSecondary,
                    modifier = Modifier.size(DIALOG_TRAILING_ICON)
                )
            }
        }
    }
}

/**
 * 迷你控制器里的一颗按钮：图标在上、文字在下，整格宽度由调用方 `weight(1f)` 给定。
 *
 * 用 weight 等分而不是 `SpaceEvenly`：文字会随状态变长变短（"不循环" ↔ "列表循环"、
 * "随机：开" ↔ "随机：关"），靠排布算间距会让整排图标左右横跳。等分之后文字只在自己
 * 那一格里伸缩，图标的横向位置钉死。
 *
 * @param filled 主操作（播放/暂停）用实心 accent 圆底，与旁边四个描边图标拉开一整级对比。
 */
@Composable
private fun MiniControlAction(
    icon: ImageVector,
    label: String,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val tint = when {
        !enabled -> palette.textSecondary.copy(alpha = DIALOG_DISABLED_ALPHA)
        filled -> palette.onAccent
        active -> palette.accent
        else -> palette.textPrimary
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Spacing.Medium))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = Spacing.Small),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(DIALOG_ACTION_SLOT)
                .then(
                    if (filled) {
                        Modifier.clip(CircleShape).background(palette.accent)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(iconSize)
            )
        }
        Spacer(Modifier.height(Spacing.Small))
        Text(
            text = label,
            style = UfiTextStyles.caption,
            color = if (enabled) palette.textSecondary else {
                palette.textSecondary.copy(alpha = DIALOG_DISABLED_ALPHA)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** 毫秒 → `m:ss` */
private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "${totalSec / 60}:${"%02d".format(totalSec % 60)}"
}

/**
 * 标题栏那两行文字的切换动画：淡入 + 从下方轻微上移。
 *
 * 位移只给 1/3 行高（歌词那行走满一整行，见 [MediaLyricTickerText]）：标题栏里这两行挨得近，
 * 挪多了会互相撞。
 */
private fun AnimatedContentTransitionScope<String>.chipTextTransition(): ContentTransform =
    (fadeIn(tween(CHIP_MOTION_MS)) + slideInVertically(tween(CHIP_MOTION_MS)) { it / 3 }) togetherWith
        (fadeOut(tween(CHIP_MOTION_MS)) + slideOutVertically(tween(CHIP_MOTION_MS)) { -it / 3 })

/**
 * 2026-09-20 封面 38dp → 48dp、两行文字 caption(11sp) → headerCaption(12sp)/headerSubtitle(14sp)。
 * 挂件块的高度早就与左侧标题列对齐了，但里面的字还是 11sp，整块看着"空而小"。
 * 文字最大宽度跟着放到 150dp，否则字号一大就立刻省略号。
 *
 * [CHIP_TEXT_MAX_WIDTH] 这个上限要留着：挂件宽度必须与歌词长短**无关**，
 * 否则每换一行歌词整条标题栏的分配就变一次，左侧标题与诗句会跟着来回伸缩。
 * 长句由 `MediaLyricTickerText` 在 150dp 内横向滚动，不靠加宽解决。
 */
private val CHIP_COVER = 48.dp
private val CHIP_TEXT_MAX_WIDTH = 150.dp
/**
 * 歌手那一行的文字不透明度（叠在 `textSecondary` 上）。
 *
 * 2026-09-20：三行原来只有两级颜色 —— 歌名 `textPrimary`、歌手与兜底副标题都是
 * `textSecondary`，于是中间那一档在视觉上不存在。想要的层级是
 * 歌名（最重）> 歌手（说明）> 歌词（accent，另一套语义），所以歌手要比 secondary 再退一档。
 *
 * 为什么不加 `textTertiary` 令牌：调色板（[com.ufi_axis.ui.theme.ResolvedPalette]）只有
 * `textPrimary` / `textSecondary` 两级正文色，加第三级要给全部 7 套预设各配两个新值
 * （明暗各一），成本远超"标题栏挂件第二行"这一个用途。
 *
 * 0.65 是能同时满足两件事的档：比 secondary 明显浅一档看得出层级，又不低到让 12sp 小字
 * 在浅色主题的卡面上糊掉（secondary 本身在浅色态是接近 #444 的深色，乘 0.65 后
 * 对 pageBg 仍在 4:1 以上）。再往下（0.5）暗色主题里就开始发灰不可读。
 */
private const val CHIP_ARTIST_ALPHA = 0.65f
private const val CHIP_MOTION_MS = 220
/**
 * 暂停后还占着标题栏多久才让位给天气。
 *
 * 30s：短到不会长期挡着天气，长到足以覆盖"暂停一下接个话又继续"这种常见动作 ——
 * 立刻让位会让标题栏在几秒内来回换两次版式。
 */
private const val NOW_PLAYING_PAUSE_YIELD_MS = 30_000L
/** 歌词与上方"歌手"之间的间隔：把"唱到哪了"与"这是什么歌"隔开一档。 */
private val CHIP_LYRIC_GAP = 3.dp

// 弹窗内尺寸（迷你控制器）
/**
 * 三段之间的间距。
 *
 * 2026-09-19 从 14dp 收到 4dp：`UfiSlider` 底下的 M3 Slider 自带 48dp 触控高度，
 * 轨道居中放在里面，上下已经各有十几 dp 的空白。再叠 14dp 的段间距，看着就是
 * "进度条孤零零悬在中间"。
 */
private val DIALOG_SECTION_GAP = 4.dp
/**
 * 歌名 → 歌手的间距（2026-09-23）。
 *
 * 原来三行平级靠 `SpaceBetween` 自动分余量，缝隙只有约 2dp；歌名换成 16sp 档之后余量更少，
 * 歌手几乎贴在歌名底下。现在歌名+歌手打包成一组、组内间距写死，
 * `SpaceBetween` 只负责「文字组 ↔ 进度行」那一段。
 */
private val DIALOG_TITLE_ARTIST_GAP = 3.dp
/**
 * 封面边长，同时也是"歌名 + 歌手 + 进度"这一列的**高度上限**。
 *
 * 92dp 的来历：`UfiSlider` 的轨道行有 44dp 触摸下限，歌名 `panelTitle`(16sp) ≈ 23dp、
 * 组内间距 3dp、歌手 `caption` ≈ 16dp，三行合计 ≈ 86dp，留 6dp 给 SpaceBetween 分。
 * 2026-09-23 从 88dp 提到 92dp：歌名由 14sp 换成 16sp 后 88dp 只剩 2dp 余量，
 * 字体渲染差一点就会把进度行挤出封面下沿。
 * 也别再往上加 —— 上一版让封面跟着内容长到 110dp，弹窗宽度被吃掉一小半。
 */
private val DIALOG_COVER = 92.dp
private val DIALOG_COVER_CORNER = 14.dp
private val DIALOG_COVER_PLACEHOLDER_ICON = 24.dp
/** 右上角图标：触控区 44dp / 图标 22dp，与标准弹窗关闭按钮同一口径。 */
private val DIALOG_TRAILING_TOUCH = 44.dp
private val DIALOG_TRAILING_ICON = 22.dp
/** 触控区比图标多出来的单边留白，用它做 offset 才能让**图标**对齐边框。 */
private val DIALOG_TRAILING_INSET = 11.dp
/** 第一行右侧给这颗图标让出的宽度（触控区宽度，避免长歌名钻到图标底下）。 */
private val DIALOG_TRAILING_RESERVE = 44.dp
/** 每颗控制键的图标槽：五键等宽时槽位一致，图标大小不同也不会让文字基线错开。 */
private val DIALOG_ACTION_SLOT = 40.dp
private val DIALOG_SKIP_ICON = 26.dp
private val DIALOG_SIDE_ICON = 20.dp
private val DIALOG_PLAY_ICON = 26.dp
private const val DIALOG_DISABLED_ALPHA = 0.4f

