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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

    DisposableEffect(controller) {
        val c = controller
        UfiNowPlayingState.controller.value = c
        if (c == null) {
            UfiNowPlayingSlot.active.value = false
            return@DisposableEffect onDispose { }
        }
        fun sync() {
            val hasQueue = c.mediaItemCount > 0
            mediaId = c.currentMediaItem?.mediaId.orEmpty()
            meta = c.mediaMetadata
            UfiNowPlayingState.isPlaying.value = c.isPlaying
            UfiNowPlayingState.hasNext.value = c.hasNextMediaItem()
            UfiNowPlayingState.hasPrev.value = c.hasPreviousMediaItem()
            UfiNowPlayingState.mediaId.value = mediaId
            UfiNowPlayingState.repeatMode.value = c.repeatMode
            UfiNowPlayingState.shuffleEnabled.value = c.shuffleModeEnabled
            // 队列空 = 这次进程里还没播过任何东西：标题栏不该为它改版式
            UfiNowPlayingSlot.active.value = hasQueue && mediaId.isNotBlank()
        }
        sync()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) = sync()
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
        UfiNowPlayingState.artworkUrl.value = meta?.artworkUri?.toString()
    }

    val media = viewModel.media
    val mediaState by media.state.collectAsState()
    val trackId = remember(mediaId, mediaState) {
        mediaState.tab(MEDIA_TYPE_AUDIO).items.firstOrNull { it.path == mediaId }?.id
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

    // 第二行只放**歌词**：歌手已经拼在第一行了，这里再退回歌手就是同一句话说两遍。
    // 没歌词（或还没解析出来）就留空，第二行自然消失。
    LaunchedEffect(lyrics, positionMs) {
        val line = ufiLyricIndexAt(lyrics, positionMs)
            .takeIf { it >= 0 }
            ?.let { lyrics.getOrNull(it)?.text }
            ?.takeIf { it.isNotBlank() }
        UfiNowPlayingState.subtitle.value = line.orEmpty()
        UfiNowPlayingState.subtitleIsLyric.value = line != null
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
    val headline = if (artistLine.isBlank()) title else "$title · $artistLine"

    var dialogOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(CHIP_CORNER))
            .background(palette.surfaceMuted.copy(alpha = CHIP_BG_ALPHA))
            .padding(horizontal = Spacing.Small, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
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
            AnimatedContent(
                targetState = subtitle,
                transitionSpec = { chipTextTransition() },
                label = "nowPlayingLyric"
            ) { text ->
                if (text.isNotBlank()) {
                    Text(
                        text,
                        style = UfiTextStyles.caption,
                        color = if (isLyric) palette.accent else palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End
                    )
                }
            }
            AnimatedContent(
                targetState = headline,
                transitionSpec = { chipTextTransition() },
                label = "nowPlayingHeadline"
            ) { text ->
                Text(
                    text,
                    style = UfiTextStyles.caption,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
            }
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
            onPrev = { controller.seekToPreviousMediaItem() },
            onToggle = { if (isPlaying) controller.pause() else controller.play() },
            onNext = { controller.seekToNextMediaItem() },
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
     * 拖动中不让轮询回来的 positionMs 把滑块拽走：dragging 为真时用手上的 dragFraction，
     * 松手才 onSeek 并交还给播放器。少这一层，拖的过程每秒会被打断一次。
     */
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val playedFraction =
        if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownFraction = if (dragging) dragFraction else playedFraction

    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = null,
        showCloseButton = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(DIALOG_SECTION_GAP)
        ) {
            // ① 封面 + 歌名/歌手 + 展开入口
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onGoToPlayer
                )
            ) {
                Box(
                    modifier = Modifier
                        .size(DIALOG_COVER)
                        .clip(RoundedCornerShape(DIALOG_COVER_CORNER))
                        .background(palette.surfaceMuted),
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title.ifBlank { "未知曲目" },
                        style = UfiTextStyles.bodyLeadStrong,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist.ifBlank { "未知歌手" },
                        style = UfiTextStyles.caption,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.Default.OpenInFull,
                    contentDescription = "打开播放页",
                    tint = palette.textSecondary,
                    modifier = Modifier.size(DIALOG_TRAILING_ICON)
                )
            }

            // ② 可拖动进度 + 左已播 / 右总时长
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = shownFraction,
                    onValueChange = {
                        dragging = true
                        dragFraction = it
                    },
                    onValueChangeFinished = {
                        dragging = false
                        if (durationMs > 0) onSeek((dragFraction * durationMs).toLong())
                    },
                    // 时长未知时（还没 prepare 好）不给拖，拖了也算不出目标毫秒
                    enabled = durationMs > 0,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accent,
                        activeTrackColor = palette.accent,
                        inactiveTrackColor = palette.textPrimary.copy(alpha = DIALOG_TRACK_ALPHA)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DIALOG_SLIDER_HEIGHT)
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatDuration(if (dragging) (dragFraction * durationMs).toLong() else positionMs),
                        style = UfiTextStyles.caption,
                        color = palette.textSecondary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatDuration(durationMs),
                        style = UfiTextStyles.caption,
                        color = palette.textSecondary
                    )
                }
            }

            // ③ 五键等距：循环 / 上一首 / 播放暂停 / 下一首 / 随机
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCycleRepeat, modifier = Modifier.size(DIALOG_SIDE_BUTTON)) {
                    Icon(
                        if (repeatMode == Player.REPEAT_MODE_ONE) {
                            Icons.Default.RepeatOne
                        } else {
                            Icons.Default.Repeat
                        },
                        contentDescription = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> "不循环"
                            Player.REPEAT_MODE_ALL -> "列表循环"
                            else -> "单曲循环"
                        },
                        tint = if (repeatMode == Player.REPEAT_MODE_OFF) {
                            palette.textSecondary
                        } else {
                            palette.accent
                        },
                        modifier = Modifier.size(DIALOG_SIDE_ICON)
                    )
                }
                IconButton(
                    onClick = onPrev,
                    enabled = hasPrev,
                    modifier = Modifier.size(DIALOG_BUTTON)
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "上一首",
                        tint = if (hasPrev) {
                            palette.textPrimary
                        } else {
                            palette.textSecondary.copy(alpha = DIALOG_DISABLED_ALPHA)
                        },
                        modifier = Modifier.size(DIALOG_SKIP_ICON)
                    )
                }
                // 播放键是**实心** accent 圆：整块弹窗里它是唯一的主操作，
                // 跟旁边四个描边图标拉开一整级对比度，不用看图标也知道该点哪个。
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier
                        .size(DIALOG_PLAY_BUTTON)
                        .clip(CircleShape)
                        .background(palette.accent)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = palette.onAccent,
                        modifier = Modifier.size(DIALOG_PLAY_ICON)
                    )
                }
                IconButton(
                    onClick = onNext,
                    enabled = hasNext,
                    modifier = Modifier.size(DIALOG_BUTTON)
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        tint = if (hasNext) {
                            palette.textPrimary
                        } else {
                            palette.textSecondary.copy(alpha = DIALOG_DISABLED_ALPHA)
                        },
                        modifier = Modifier.size(DIALOG_SKIP_ICON)
                    )
                }
                IconButton(onClick = onToggleShuffle, modifier = Modifier.size(DIALOG_SIDE_BUTTON)) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = if (shuffleEnabled) "随机：开" else "随机：关",
                        tint = if (shuffleEnabled) palette.accent else palette.textSecondary,
                        modifier = Modifier.size(DIALOG_SIDE_ICON)
                    )
                }
            }
        }
    }
}

/** 毫秒 → `m:ss` */
private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "${totalSec / 60}:${"%02d".format(totalSec % 60)}"
}

/**
 * 标题栏那两行文字的切换动画：淡入 + 从下方轻微上移。
 */
private fun AnimatedContentTransitionScope<String>.chipTextTransition(): ContentTransform =
    (fadeIn(tween(CHIP_MOTION_MS)) + slideInVertically(tween(CHIP_MOTION_MS)) { it / 3 }) togetherWith
        (fadeOut(tween(CHIP_MOTION_MS)) + slideOutVertically(tween(CHIP_MOTION_MS)) { -it / 3 })

private val CHIP_CORNER = 14.dp
private val CHIP_COVER = 30.dp
private val CHIP_TEXT_MAX_WIDTH = 132.dp
private const val CHIP_BG_ALPHA = 0.55f
private const val CHIP_MOTION_MS = 220

// 弹窗内尺寸（迷你控制器）
private val DIALOG_SECTION_GAP = 14.dp
private val DIALOG_COVER = 64.dp
private val DIALOG_COVER_CORNER = 14.dp
private val DIALOG_COVER_PLACEHOLDER_ICON = 24.dp
private val DIALOG_TRAILING_ICON = 20.dp
private val DIALOG_SLIDER_HEIGHT = 24.dp
private const val DIALOG_TRACK_ALPHA = 0.12f
private val DIALOG_BUTTON = 44.dp
private val DIALOG_SKIP_ICON = 28.dp
private val DIALOG_SIDE_BUTTON = 40.dp
private val DIALOG_SIDE_ICON = 22.dp
private val DIALOG_PLAY_BUTTON = 56.dp
private val DIALOG_PLAY_ICON = 32.dp
private const val DIALOG_DISABLED_ALPHA = 0.4f

