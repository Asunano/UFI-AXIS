package com.ufi_axis.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.data.media.UfiAudioLyrics
import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.data.model.MediaTagsResponse
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiListRowCard
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.theme.*
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 音乐播放页（媒体中心 → 音乐 → 点某一首）。2026-09-16 第二版：对齐参考图 + 接入系统媒体控制。
 *
 * ## 版式（照参考图）
 * 顶部左对齐的曲名 / 艺术家 → 大圆角封面 → **封面下方三行歌词**（当前句亮）→ 极细进度条 →
 * 左「已播」右「总时长」→ 三个大图标 → 底部一排次要动作。点歌词区进**整屏歌词**（当前句放大、
 * 自动滚动），再点回封面。
 *
 * 与第一版的三处修正（都是用户实测反馈）：
 * 1. 封面用 `/api/media/cover`（原始内嵌封面）而不是 256px 缩略图 —— 260dp 的框喂 256px 必糊；
 * 2. 暂停时封面只缩到 [AUDIO_COVER_PAUSED_SCALE]（0.96），不是 0.88 —— 原来幅度大得像在抖；
 * 3. 右侧时间显示**总时长**，不是负的剩余时间（参考图就是总时长）。
 *
 * ## 播放器不在这一页里
 * 播放交给 [UfiAudioPlaybackService]（MediaSession 前台服务），本页只是它的一个**遥控器**
 * （[rememberUfiAudioController]）。所以：锁屏 / 通知栏 / 快捷设置里有真的媒体控制，耳机暂停键
 * 也能用，退出这一页音乐不会断。控制器连接是异步的，没连上时显示加载 —— 不画一套点不动的按钮。
 *
 * ## 底部一排只放**真有能力**的动作
 * 循环模式（关 / 列表 / 单曲）、随机、播放队列 —— 全部是播放器自带能力。参考图里的定时关闭、
 * 音效、更多没有对应能力，所以不摆图标。
 *
 * ## 破例说明：进度条是自绘的，没用 UfiSlider
 * `UfiSlider` 是"设置项里的滑块"（有标签、明显滑块、44dp 触控区），这里要的是"平时极细、
 * 按住变粗、没有滑块"。形态诉求冲突，所以在本模块内自绘（[MediaSeekBar]），颜色全部走令牌，
 * 且不放进 `app/ui` 公共组件 —— 破例范围仅限媒体中心。
 */
@Composable
fun MediaAudioPlayerScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    filePath: String
) {
    val palette = LocalResolvedPalette.current
    val media = viewModel.media
    val state by media.state.collectAsState()
    val tracks = state.tab(MEDIA_TYPE_AUDIO).items

    LaunchedEffect(Unit) { media.loadFirstPage(MEDIA_TYPE_AUDIO) }

    val controller = rememberUfiAudioController()

    var currentIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableFloatStateOf(0f) }
    var showQueue by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var shuffle by remember { mutableStateOf(false) }
    var handedOff by remember { mutableStateOf(false) }

    /**
     * 播放器给出的**当前曲目元信息**（内嵌 ID3 / FLAC 标签解析结果）。
     *
     * 曲名与歌手只能从这里来：媒体库那份记录只有文件名（`带我走-杨丞琳.flac` 这种），
     * 而 `MediaStore` 的 artist 在不少 FLAC 上是空的。播放器解容器时会读出真标签，
     * 通过 [Player.Listener.onMediaMetadataChanged] 送来 —— 这也是通知栏/锁屏显示的同一份。
     */
    var trackMeta by remember { mutableStateOf<MediaMetadata?>(null) }


    val paths = remember(tracks) { tracks.map { it.path } }

    /** 列表项 → 队列项。三处装队列（首装 / 追加 / 重装）共用，构造口径不能各写一份。 */
    val toTrack: (MediaLibraryItem) -> UfiAudioTrack = remember(media) {
        { item ->
            UfiAudioTrack(
                mediaId = item.path,
                url = media.streamUrl(item.path),
                artworkUrl = media.coverUrl(item.id)
            )
        }
    }

    /*
     * 装队列。
     *
     * ## 规则只有一条：**路由指定哪一首，就从 0 播哪一首**（2026-09-19 修）
     * [filePath] 进了 effect 的 key，所以每次路由换歌这段都会重跑；跳转一律用
     * `seekTo(index, 0L)` 而不是 `seekToDefaultPosition(index)` —— 显式从头开始。
     * 之前 key 里没有 filePath、且首跳被 `handedOff` 挡着，于是"播 A → 播 B → 再点 A"时
     * A 会从上次离开的位置接着放，而用户点的是一首歌、期望的就是这首歌的开头。
     *
     * 分支：
     *  · 列表还没拉到 → 至少把路由这一首装成单曲队列；
     *  · 队列与列表一致 → 跳到路由那一首（位置 0）；
     *  · 列表只是**分页多出了后面几首**（前缀一致）→ 追加，再跳到路由那一首（位置 0）；
     *  · 队列不是这一份且是首次进页 → 整体装成新队列，从路由那一首开始；
     *  · 其余（换了排序 / 扫描目录，队列语义真的变了）→ 重装，但以正在播的那一首为锚点、
     *    带上当前播放位置，不把人拽回开头 —— 这里没有"用户点了哪一首"这件事发生。
     *
     * 最后无条件回读播放器状态：media3 的 [Player.Listener] 只在**变化**时回调，不补发当前值。
     * 少这一步，页面在"服务已经在播"的情况下会显示暂停图标、循环/随机显示关 —— 那就是假状态。
     */
    LaunchedEffect(controller, paths, filePath) {
        val c = controller ?: return@LaunchedEffect
        val queueIds = c.ufiAudioQueueIds()
        val target = paths.indexOf(filePath).takeIf { it >= 0 } ?: 0
        when {
            // 列表还没拉到（或拉失败）：至少让路由点进来的这一首能放
            paths.isEmpty() -> if (queueIds.isEmpty()) {
                c.setUfiAudioPlaylist(
                    listOf(
                        UfiAudioTrack(
                            mediaId = filePath,
                            url = media.streamUrl(filePath),
                            artworkUrl = null
                        )
                    ),
                    0
                )
                c.playWhenReady = true
            }

            queueIds == paths -> if (c.currentMediaItemIndex != target) {
                c.seekTo(target, 0L)
                c.play()
            }

            paths.size > queueIds.size && paths.subList(0, queueIds.size) == queueIds -> {
                c.addUfiAudioTracks(tracks.drop(queueIds.size).map(toTrack))
                // 追加本身不动播放位置；路由指定的那一首仍要从头放
                if (c.currentMediaItemIndex != target) {
                    c.seekTo(target, 0L)
                    c.play()
                }
            }


            !handedOff -> {
                c.setUfiAudioPlaylist(tracks.map(toTrack), target)
                c.playWhenReady = true
            }

            else -> {
                val playingId = c.currentMediaItem?.mediaId
                val keepIndex = paths.indexOf(playingId).takeIf { it >= 0 }
                c.setUfiAudioPlaylist(
                    tracks.map(toTrack),
                    keepIndex ?: target,
                    if (keepIndex != null) c.currentPosition else 0L
                )
            }
        }
        handedOff = true
        currentIndex = c.currentMediaItemIndex
        isPlaying = c.isPlaying
        repeatMode = c.repeatMode
        shuffle = c.shuffleModeEnabled
        trackMeta = c.mediaMetadata
    }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                currentIndex = c.currentMediaItemIndex
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onRepeatModeChanged(mode: Int) {
                repeatMode = mode
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                shuffle = enabled
            }

            // 内嵌标签解析完成 / 换歌时都会回调；曲名与歌手就从这里来
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                trackMeta = mediaMetadata
            }
        }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }

    // 进度轮询 500ms：歌词高亮与进度条都不需要每帧精度
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            if (!seeking) positionMs = c.currentPosition
            durationMs = c.duration.takeIf { it > 0 } ?: 0L
            delay(500)
        }
    }

    /*
     * 当前是第几首。
     *
     * [currentIndex] 初值是 -1（"还没从播放器回读过"），这时用路由带来的那一首 —— 直接写 0
     * 会让页面在回读之前的那一帧显示**列表第一首**的曲名与封面，而用户点的是另一首。
     */
    val resolvedIndex = if (currentIndex >= 0) {
        currentIndex
    } else {
        paths.indexOf(filePath).coerceAtLeast(0)
    }
    val current = tracks.getOrNull(resolvedIndex)

    /*
     * 音乐**不做播放进度记忆**（2026-09-19 去掉）。
     *
     * 之前这里会按 `MediaPlaybackProgress` 的存档 seek 回上次离开的位置，于是
     * "播 A → 播 B → 再点 A"时 A 从中段接着放 —— 点一首歌就是要听这首歌，续播是视频的
     * 需求（一集看到一半），不是音乐的。存档与续播留给视频播放页（[MediaVideoPlayerScreen]）。
     *
     * [prefs] 仍要留着：歌词与封面的接口客户端都从它拿设备凭据。
     */
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }
    val currentPath = current?.path ?: filePath


    /*
     * 歌词：走公共实现 [UfiAudioLyrics]（core 接口 → 同目录旁挂 .lrc → 内嵌 USLT/SYLT/FLAC LYRICS），
     * 与文件管理器的音频预览**共用同一份**取词与解析。
     *
     * 播放页此前只问 core 的 `/api/media/lyrics`，也就是只认旁挂文件 —— 而大量歌曲的歌词嵌在
     * 音频里，同一首歌在文件管理器能看到歌词、在播放页看不到。优先级由公共类内部决定，
     * 这里只调一次 load：页面不该知道"先问谁"。
     *
     * OkHttp 用 [UfiStreamHttpClient]（带设备签名、进程内单例），与本模块播放器同一条口径。
     */
    val lyricsHttpClient = remember(context) { UfiStreamHttpClient.get(context.applicationContext) }
    val lyricsApi = remember(prefs) { RetrofitClient.getApiService(prefs) }
    var lyrics by remember { mutableStateOf<List<UfiAudioLyrics.Line>>(emptyList()) }
    var lyricsSource by remember { mutableStateOf("") }
    LaunchedEffect(current?.id, currentPath) {
        lyrics = emptyList()
        lyricsSource = ""
        val found = UfiAudioLyrics.load(
            api = lyricsApi,
            mediaId = current?.id,
            httpClient = lyricsHttpClient,
            prefs = prefs,
            filePath = current?.path ?: currentPath
        ) ?: return@LaunchedEffect
        lyrics = found.lines
        lyricsSource = found.source
    }
    val lyricIndex = remember(lyrics, positionMs) { ufiLyricIndexAt(lyrics, positionMs) }

    // core 侧解析的标签（`/api/media/tags`）：换歌重拉一次。失败回 null，取值链自动降级。
    var tags by remember { mutableStateOf<MediaTagsResponse?>(null) }
    LaunchedEffect(current?.id) {
        val id = current?.id
        tags = null
        if (id != null && id > 0) tags = media.tagsOf(id)
    }

    /*
     * 曲名 / 歌手的取值链（2026-09-16）。
     *
     * 之前直接显示 `item.name`，于是页面上写的是 `带我走-杨丞琳.flac` —— 那是**文件名**，
     * 不是歌名。现在按可信度排：
     *  1. **core 解析的标签**（[tags]，服务端直接读文件的 ID3 / FLAC 标签）——进页面就有，
     *     不必等播放器解容器；
     *  2. **列表里带的标签**（`/api/media/list` 的 title/artist，系统扫描时解析好的）；
     *  3. **播放器解出的元信息**（[trackMeta]）——前两级都拿不到时的兜底；
     *  4. **文件名拆分**（`歌名-歌手`，见 [splitFileNameTitleArtist]）：纯属猜测，放最后。
     */
    val fallbackBase = audioFileBase(current?.name ?: filePath.substringAfterLast('/'))
    val guessed = remember(fallbackBase) { splitFileNameTitleArtist(fallbackBase) }
    val title = tags?.title?.takeIf { it.isNotBlank() }
        ?: current?.title?.takeIf { it.isNotBlank() }
        ?: trackMeta?.title?.toString()?.takeIf { it.isNotBlank() }
        ?: guessed.first
    val artist = tags?.artist?.takeIf { it.isNotBlank() }
        ?: current?.artist?.takeIf { it.isNotBlank() }
        ?: trackMeta?.artist?.toString()?.takeIf { it.isNotBlank() }
        ?: trackMeta?.albumArtist?.toString()?.takeIf { it.isNotBlank() }
        ?: guessed.second
        ?: ""

    /*
     * 队列面板的数据。
     *
     * 列表拉到了就是列表本身；**拉不到时播放器里装的是路由那一首的单曲队列**，所以面板也要
     * 显示这一行 —— 否则会出现"面板写着 1 / 0、列表空白，但音乐在放"这种和播放器不一致的画面。
     */
    val queueRows = remember(tracks, filePath, title, artist, durationMs) {
        if (tracks.isNotEmpty()) {
            tracks.map { item ->
                AudioQueueRow(
                    key = item.path,
                    title = audioDisplayTitle(item),
                    subtitle = listOf(
                        audioDisplayArtist(item),
                        item.album

                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    duration = formatMediaDuration(item.duration_ms)
                )
            }
        } else {
            listOf(
                AudioQueueRow(
                    key = filePath,
                    title = title,
                    subtitle = artist,
                    duration = formatMediaDuration(durationMs)
                )
            )
        }
    }

    /*
     * 「封面 / 歌词」两页（2026-09-16 第三版）。
     *
     * 上一版是"点歌词进整屏、左右划切换"，两个问题：手势要和整屏歌词里的点句跳转、
     * 纵向滚动抢事件；而且没有任何可见入口，用户不知道能划。现在改成**左右 Tab + Pager**：
     * Tab 是看得见的入口，滑动由 Pager 自己处理，与页内纵向滚动天然分工。
     *
     * 状态提到这里（而不是留在 Column 里）是因为 Tab 与 Pager 分属两个子树、都要读它。
     */
    val pagerState = rememberPagerState(initialPage = AUDIO_PAGE_COVER) { AUDIO_PAGE_COUNT }
    val pagerScope = rememberCoroutineScope()

    /*
     * 返回键只拦截**队列面板**这一层浮层。
     *
     * 歌词页不再拦：Tab 一直摆在那儿（点一下就回封面），左右划也能切，用不着再占用返回键。
     * 之前在歌词页按返回是"先回封面、再按才退出"，等于要按两次才离开播放页 —— 对着
     * 一个平级的 Tab 页做返回栈语义，只会让人以为返回失灵了。现在歌词页按返回直接退出
     * 整个播放页（走页壳的 popBackStack）。
     */
    BackHandler(enabled = showQueue) {
        showQueue = false
    }


    UfiScreenScaffold(
        title = "正在播放",
        navController = navController,
        showBack = true,
        // 顶栏与内容同色（2026-09-16）：渐变交给页壳铺满整页（含标题栏与状态栏区域），
        // 内容区不再自己画背景 —— 否则标题栏只有 pageBg，与下面的 accent 渐变之间有一道色阶。
        backgroundBrush = Brush.verticalGradient(
            colors = listOf(palette.accent.copy(alpha = AUDIO_GRADIENT_ALPHA), palette.pageBg)
        )
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (controller == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    UfiLoadingIndicator()
                    Spacer(Modifier.height(Spacing.Medium))
                    Text(
                        "正在连接播放服务…",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                }
                return@Box
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AUDIO_PAGE_PADDING)
            ) {
                /*
                 * Tab 与 Pager 的状态在本 composable 顶层（返回键要用），这里只做编排。
                 */
                MediaPlayerTabs(
                    selected = pagerState.currentPage,
                    onSelect = { page ->
                        pagerScope.launch { pagerState.animateScrollToPage(page) }
                    }
                )


                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    pageSpacing = Spacing.Medium,
                    verticalAlignment = Alignment.CenterVertically
                ) { page ->
                    if (page == AUDIO_PAGE_LYRICS) {
                        MediaLyricsFullView(
                            lines = lyrics,
                            index = lyricIndex,
                            source = lyricsSource,
                            // 点某一句 = 跳到那一句（切回封面用 Tab / 左划 / 返回键）
                            onSeek = { target ->
                                controller.seekTo(target)
                                positionMs = target
                            }
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            /*
                             * 切歌过渡（2026-09-16）：封面 + 曲名/歌手整块做一次横向滑入。
                             *
                             * key 用当前曲目的 id：换歌才动，播放/暂停或进度变化不触发。
                             * 方向按"下一首往左走"：新的一首从右侧进来，旧的往左出去 ——
                             * 与列表/队列的顺序一致，用户能看出是往后翻了一首。
                             */
                            AnimatedContent(
                                targetState = current?.id ?: -1L,
                                transitionSpec = {
                                    val forward = targetState >= initialState
                                    val enter = slideInHorizontally(
                                        animationSpec = tween(UfiMotion.Duration.Fluid)
                                    ) { full -> if (forward) full / 3 else -full / 3 } +
                                        fadeIn(tween(UfiMotion.Duration.Fluid))
                                    val exit = slideOutHorizontally(
                                        animationSpec = tween(UfiMotion.Duration.Fluid)
                                    ) { full -> if (forward) -full / 3 else full / 3 } +
                                        fadeOut(tween(UfiMotion.Duration.Fluid))
                                    enter togetherWith exit
                                },
                                label = "audioTrackSwitch"
                            ) { _ ->
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    val coverScale by animateFloatAsState(
                                        targetValue = if (isPlaying) 1f else AUDIO_COVER_PAUSED_SCALE,
                                        animationSpec = UfiMotion.sliderTrack(),
                                        label = "audioCoverScale"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(AUDIO_COVER_SIZE)
                                            .graphicsLayer {
                                                scaleX = coverScale
                                                scaleY = coverScale
                                            }
                                            .shadow(18.dp, RoundedCornerShape(AUDIO_COVER_CORNER))
                                            .clip(RoundedCornerShape(AUDIO_COVER_CORNER))
                                            .background(palette.surfaceMuted),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = palette.textSecondary,
                                            modifier = Modifier.size(56.dp)
                                        )
                                        current?.let { item ->
                                            AsyncImage(
                                                model = media.coverUrl(item.id),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                // 失败仍是那个音符占位图标，但要留下原因：
                                                // 401 / 404 / 超时在界面上长得一模一样
                                                onError = { state ->
                                                    DebugLog.w(
                                                        "AudioCover",
                                                        "封面加载失败: ${media.coverUrl(item.id)}",
                                                        state.result.throwable
                                                    )
                                                },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }

                                    // ── 封面下方：歌名，其下歌手 ──
                                    Spacer(Modifier.height(Spacing.Large))
                                    Text(
                                        title,
                                        style = UfiTextStyles.sectionTitle,
                                        color = palette.textPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (artist.isNotBlank()) {
                                        Spacer(Modifier.height(Spacing.Small))
                                        Text(
                                            artist,
                                            style = UfiTextStyles.body,
                                            color = palette.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(Spacing.Large))
                            MediaLyricsPreview(
                                lines = lyrics,
                                index = lyricIndex,
                                // 点三行预览 = 翻到歌词页（与 Tab 同一个去处）
                                onClick = {
                                    pagerScope.launch {
                                        pagerState.animateScrollToPage(AUDIO_PAGE_LYRICS)
                                    }
                                }
                            )
                        }

                    }
                }


                // ── 进度条 + 时间（左已播、右总时长）──
                val fraction = when {
                    seeking -> seekFraction
                    durationMs > 0 -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    else -> 0f
                }
                MediaSeekBar(
                    fraction = fraction,
                    enabled = durationMs > 0,
                    dragging = seeking,
                    onDragStart = { seeking = true },
                    onFractionChange = { seekFraction = it },
                    onDragEnd = {
                        if (durationMs > 0) {
                            val target = (seekFraction * durationMs).toLong()
                            controller.seekTo(target)
                            // 立刻写一份：轮询是 500ms 一次，不写的话松手后进度条会先跳回旧位置
                            positionMs = target
                        }
                        seeking = false
                    }
                )
                Spacer(Modifier.height(Spacing.Small))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        formatPlaybackTime(
                            if (seeking) (seekFraction * durationMs).toLong() else positionMs
                        ),
                        style = UfiTextStyles.monoCaption,
                        color = palette.textSecondary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatPlaybackTime(durationMs),
                        style = UfiTextStyles.monoCaption,
                        color = palette.textSecondary
                    )
                }

                Spacer(Modifier.height(Spacing.Medium))

                // ── 三键：无底色、中间最大，每个键下面写清楚它是什么（2026-09-16）──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.Center
                ) {
                    MediaControlAction(
                        icon = Icons.Default.SkipPrevious,
                        label = "上一首",
                        iconSize = 34.dp,
                        enabled = controller.hasPreviousMediaItem(),
                        onClick = { controller.seekToPreviousMediaItem() }
                    )
                    Spacer(Modifier.width(Spacing.XLarge))
                    MediaControlAction(
                        icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        label = if (isPlaying) "暂停" else "播放",
                        iconSize = 52.dp,
                        onClick = { if (isPlaying) controller.pause() else controller.play() }
                    )
                    Spacer(Modifier.width(Spacing.XLarge))
                    MediaControlAction(
                        icon = Icons.Default.SkipNext,
                        label = "下一首",
                        iconSize = 34.dp,
                        enabled = controller.hasNextMediaItem(),
                        onClick = { controller.seekToNextMediaItem() }
                    )
                }


                Spacer(Modifier.height(Spacing.Medium))

                // ── 底部一排次要动作：循环 / 随机 / 队列（三个都是播放器自带能力，各带文字）──
                //
                // 三等分而不是 SpaceEvenly（2026-09-19）：这三个按钮的文字长度会随状态变
                // （"不循环" ↔ "列表循环"、"队列（3）" ↔ "队列（128）"），SpaceEvenly 按实际
                // 宽度分配剩余空间，任何一个字数一变、三个按钮就一起横向挪位。改成每个占
                // weight(1f) 的固定格子、内部居中，这样只有格子里的文字变，图标位置钉死不动。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        MediaControlAction(
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
                            iconSize = 22.dp,
                            active = repeatMode != Player.REPEAT_MODE_OFF,
                            onClick = {
                                controller.repeatMode = when (repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                }
                            }
                        )
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        MediaControlAction(
                            icon = Icons.Default.Shuffle,
                            label = if (shuffle) "随机：开" else "随机：关",
                            iconSize = 22.dp,
                            active = shuffle,
                            onClick = { controller.shuffleModeEnabled = !shuffle }
                        )
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        MediaControlAction(
                            icon = Icons.AutoMirrored.Filled.QueueMusic,
                            label = "队列（${queueRows.size}）",
                            iconSize = 22.dp,
                            onClick = { showQueue = true }
                        )
                    }
                }


                // 底部整块留白：把这一排从屏幕最底边抬起来。贴底的按钮既难点（拇指要伸到边缘）
                // 也容易撞上系统手势热区，而这一页的控件是整页最需要点得准的东西。
                Spacer(Modifier.height(AUDIO_CONTROLS_BOTTOM_LIFT))
            }

            // ── 队列：公共弹窗（[UfiCustomDialog]，标题 + 右上角 ×）──
            if (showQueue) {
                NowPlayingQueueDialog(
                    queueRows = queueRows,
                    currentIndex = resolvedIndex,
                    onSelect = { idx ->
                        controller.seekTo(idx, 0L)
                        controller.play()
                        showQueue = false
                    },
                    onDismiss = { showQueue = false }
                )
            }
        }
    }
}

/**
 * 播放队列弹窗。
 *
 * 2026-09-19 改用公共弹窗组件 [UfiCustomDialog]：标题与右上角 × 都由它给，
 * 这里只铺内容。之前是自己用 `Dialog` 画的一整页（自绘顶栏 + 返回箭头），
 * 与全 App 其他弹窗不是一套观感，而"队列"本来就是一层浮层、不是一个页面。
 *
 * 列表必须带 [AUDIO_QUEUE_LIST_MAX_HEIGHT] 上限：队列可能有上百首，
 * 不限高会把弹窗撑出屏幕。当前播放项保留 accent 竖条 + PlayArrow 标识。
 */
@Composable
private fun NowPlayingQueueDialog(
    queueRows: List<AudioQueueRow>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "播放队列",
        showCloseButton = true
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "共 ${queueRows.size} 首",
                style = UfiTextStyles.caption,
                color = palette.textSecondary
            )
            Spacer(Modifier.height(Spacing.Medium))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = AUDIO_QUEUE_LIST_MAX_HEIGHT),
                verticalArrangement = Arrangement.spacedBy(Spacing.Small)
            ) {
                itemsIndexed(queueRows, key = { _, row -> row.key }) { index, row ->
                    val active = index == currentIndex
                    UfiListRowCard(
                        title = row.title,
                        subtitle = row.subtitle,
                        selected = active,
                        onClick = { onSelect(index) },
                        leading = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 当前播放项：accent 竖条
                                Box(
                                    modifier = Modifier
                                        .width(AUDIO_QUEUE_ACTIVE_BAR_WIDTH)
                                        .height(AUDIO_QUEUE_ACTIVE_BAR_HEIGHT)
                                        .clip(
                                            RoundedCornerShape(
                                                AUDIO_QUEUE_ACTIVE_BAR_WIDTH
                                            )
                                        )
                                        .background(
                                            if (active) palette.accent else Color.Transparent
                                        )
                                )
                                Spacer(Modifier.width(Spacing.Small))
                                Icon(
                                    if (active) {
                                        Icons.Default.PlayArrow
                                    } else {
                                        Icons.Default.MusicNote
                                    },
                                    contentDescription = null,
                                    tint = if (active) palette.accent else palette.textSecondary,
                                    modifier = Modifier.size(AUDIO_QUEUE_ICON_SIZE)
                                )
                            }
                        },
                        trailing = {
                            Text(
                                row.duration,
                                style = UfiTextStyles.monoCaption,
                                color = palette.textSecondary
                            )
                        }
                    )
                }
            }
        }
    }
}

// ───────────────────────── 版式常量 ─────────────────────────

/**
 * 队列面板一行要显示的东西。
 *
 * 有这一层是因为面板的数据源不总是媒体库列表：列表拉不到时播放器里是"路由那一首"的单曲队列，
 * 面板也得显示那一行。面板与播放器显示的队列必须是同一份。
 */
private data class AudioQueueRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val duration: String
)


private val AUDIO_COVER_SIZE = 260.dp
private val AUDIO_COVER_CORNER = 20.dp

/**
 * 暂停时封面缩到这个比例。
 *
 * 2026-09-16 从 0.88 收到 0.96：0.88 在 260dp 上是 31dp 的位移，暂停/播放来回按几次像在抖，
 * 而这个动作要表达的只是"停住了"这一点点状态差。
 */
private const val AUDIO_COVER_PAUSED_SCALE = 0.96f

/** 顶部渐变里 accent 的浓度：再高就压过封面，再低就看不出来。 */
private const val AUDIO_GRADIENT_ALPHA = 0.22f

/**
 * 页面左右内边距。
 *
 * 2026-09-16 从 `Spacing.Large`（12dp）加到 24dp：12dp 在 6 英寸屏上让封面与文字几乎贴边，
 * 底部两排控件也被挤到屏幕边缘。播放页是"只看一件事"的页面，边距该比列表页更松。
 */
private val AUDIO_PAGE_PADDING = 24.dp

/** 两页的下标与总数：0 = 封面，1 = 歌词。 */
private const val AUDIO_PAGE_COVER = 0
private const val AUDIO_PAGE_LYRICS = 1
private const val AUDIO_PAGE_COUNT = 2

/** Tab 文案与选中指示条尺寸。 */
private val AUDIO_TAB_LABELS = listOf("封面", "歌词")
private val AUDIO_TAB_INDICATOR_WIDTH = 20.dp
private val AUDIO_TAB_INDICATOR_HEIGHT = 3.dp


/**
 * 底部控件区与屏幕底边之间的留白。
 *
 * 页面壳已经扣掉了导航栏/手势条的安全区，这一段是额外的**手够得着**余量：
 * 贴着屏幕最下沿的按钮拇指要伸到边缘才点得到，且容易与系统的边缘手势打架。
 */
private val AUDIO_CONTROLS_BOTTOM_LIFT = 40.dp


/** 队列弹窗里列表的最大高度：弹窗不能被长队列撑出屏幕。 */
private val AUDIO_QUEUE_LIST_MAX_HEIGHT = 420.dp

/** 队列里当前播放项的 accent 竖条尺寸。 */
private val AUDIO_QUEUE_ACTIVE_BAR_WIDTH = 3.dp
private val AUDIO_QUEUE_ACTIVE_BAR_HEIGHT = 28.dp

/** 队列行左侧那个状态图标（播放中 / 音符）的大小。 */
private val AUDIO_QUEUE_ICON_SIZE = 18.dp


/**
 * 自绘进度条。
 *
 * 平时轨道 [SEEK_TRACK_IDLE]、按住变粗到 [SEEK_TRACK_ACTIVE]，**没有滑块** ——
 * 位置靠"已播那一段"的长度表达。触控区固定 28dp 高（细轨也要点得到），
 * 但画出来只有轨道那么细。
 *
 * 两个手势分开挂：点按直接定位（[detectTapGestures]），拖动连续更新
 * （[detectHorizontalDragGestures]）。这样"点一下跳到那里"和"按住拖"都成立，
 * 且不会互相抢 —— 与 `UfiBarChart` 里的双手势思路一致。
 */
@Composable
private fun MediaSeekBar(
    fraction: Float,
    enabled: Boolean,
    dragging: Boolean,
    onDragStart: () -> Unit,
    onFractionChange: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val trackHeight by animateDpAsState(
        targetValue = if (dragging) SEEK_TRACK_ACTIVE else SEEK_TRACK_IDLE,
        animationSpec = UfiMotion.sliderTrack(),
        label = "seekTrackHeight"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SEEK_TOUCH_HEIGHT)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                /*
                 * ★ 一个手势通道处理"点"和"拖"（2026-09-16 修「点不动 / 位置错位」）。
                 *
                 * 上一版挂了两个 pointerInput：`detectTapGestures` 与
                 * `detectHorizontalDragGestures`。两者都要抢同一个 down 事件（后者默认只接
                 * **未被消费**的 down），谁先拿到取决于修饰符顺序与事件分发顺序 ——
                 * 结果就是有时点了没反应、有时按下的位置与最终 seek 的位置差一截。
                 *
                 * 现在自己收：按下即定位（所以"点一下就跳过去"成立），移动持续更新（拖动成立），
                 * 抬手结束（此时才真正 seekTo）。全程只有这一个消费者，不存在竞争。
                 */
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    onDragStart()
                    onFractionChange((down.position.x / width).coerceIn(0f, 1f))
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        onFractionChange((change.position.x / width).coerceIn(0f, 1f))
                        change.consume()
                        if (!change.pressed) break
                    }
                    onDragEnd()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(trackHeight)) {
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
 * 轨道与触控区尺寸。
 *
 * 2026-09-16 加粗一轮（6/12dp，原 4/8dp）：4dp 在真机上又难看清又难对准，
 * 而这是本页唯一需要"精确定位"的控件。触控区 48dp = 系统建议的最小可点高度。
 */
private val SEEK_TRACK_IDLE = 6.dp
private val SEEK_TRACK_ACTIVE = 12.dp
private val SEEK_TOUCH_HEIGHT = 48.dp

/**
 * 封面页 / 歌词页的 Tab 条。
 *
 * 只有两页，所以不套 M3 `TabRow`（它会带来自己的指示器动画与最小高度）：
 * 两个文字按钮 + 选中态下划线，与页壳的工具条同一种轻量做法。
 */
@Composable
private fun MediaPlayerTabs(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Small),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AUDIO_TAB_LABELS.forEachIndexed { index, label ->
            val active = index == selected
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(Spacing.Medium))
                    .clickable { onSelect(index) }
                    .padding(horizontal = Spacing.XLarge, vertical = Spacing.Medium),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    label,
                    style = if (active) UfiTextStyles.bodyLeadStrong else UfiTextStyles.body,
                    color = if (active) palette.textPrimary else palette.textSecondary
                )
                Spacer(Modifier.height(Spacing.Small))
                Box(
                    modifier = Modifier
                        .width(AUDIO_TAB_INDICATOR_WIDTH)
                        .height(AUDIO_TAB_INDICATOR_HEIGHT)
                        .clip(RoundedCornerShape(AUDIO_TAB_INDICATOR_HEIGHT))
                        .background(if (active) palette.accent else Color.Transparent)
                )
            }
        }
    }
}

/**
 * 播放控制里的一个动作：图标 + **下方文字说明**。
 *
 * 2026-09-16 加文字：纯图标对"循环 / 随机"这类三态开关尤其不友好 —— 图标只能表达
 * "有这个功能"，表达不了"现在是哪一档"。文字与图标一起变（"列表循环" / "单曲循环" / "不循环"）。
 *
 * @param active 高亮态（循环/随机开启时用 accent），与 [enabled]（能不能点）是两件事。
 */
@Composable
private fun MediaControlAction(
    icon: ImageVector,
    label: String,
    iconSize: Dp,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val tint = when {
        !enabled -> palette.textSecondary.copy(alpha = 0.4f)
        active -> palette.accent
        else -> palette.textPrimary
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Spacing.Medium))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
        Spacer(Modifier.height(Spacing.Small))
        Text(
            label,
            style = UfiTextStyles.caption,
            color = if (enabled) palette.textSecondary else palette.textSecondary.copy(alpha = 0.4f),
            maxLines = 1
        )
    }
}


/**
 * 播放时间文案。与 [formatMediaDuration] 的区别：这里 0 要显示 `0:00`（进度条两端总有数字），
 * 那个在列表里 0 就该什么都不显示。
 */
private fun formatPlaybackTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/*
 * 「文件名 → 歌名 / 歌手」的拆分已上提到 MediaListCommon.kt（`splitFileNameTitleArtist`）：
 * 列表、播放页、队列面板、迷你条都要用同一份口径，留在这里就会变成第二份。
 */


