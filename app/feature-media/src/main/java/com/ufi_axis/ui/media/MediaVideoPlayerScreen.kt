package com.ufi_axis.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.data.model.MediaSubtitleEntry
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 播放进度落盘间隔（毫秒）。再短纯属浪费写入，再长则杀进程后丢掉的进度过多。 */
private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L

/**
 * 视频播放页（媒体中心 → 视频 → 点某一项，2026-09-16）。
 *
 * 上方播放器、下方**当前这批视频的清单**，点哪个播哪个 —— 这是它取代文件预览浮层的理由：
 * 浮层撑不起一个播放列表，换片必须关掉再开。
 *
 * ## 一个播放器 + 一条 playlist
 * `rememberUfiExoPlayer(key = "media-video")` 整页只建**一次**；换片走
 * `seekToDefaultPosition(index)`，不重建播放器（重建 = 黑一下 + 重新连接 + 重新签名握手）。
 * 上/下一集按钮因此免费可用（media3 控制条自带，playlist 场景打开即生效）。
 *
 * ## 播完停住
 * `pauseAtEndOfMediaItems = true`：用户明确要求"放完不自动跳下一个"。
 * 想连播的人可以用控制条上的循环三态按钮。
 *
 * ## 生命周期
 * `ON_STOP` 暂停，回前台**不自动续播** —— 自动续播会在用户切回来时突然出声。
 */
@Composable
fun MediaVideoPlayerScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    filePath: String
) {
    val palette = LocalResolvedPalette.current
    val media = viewModel.media
    val state by media.state.collectAsState()
    val videos = state.tab(MEDIA_TYPE_VIDEO).items

    // 直接进来（进程重建 / 深链）时列表可能还没拉过：拉一次，期间先播路由参数那一个
    LaunchedEffect(Unit) { media.loadFirstPage(MEDIA_TYPE_VIDEO) }

    var fullscreen by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableStateOf(0) }
    // 下半屏的两页：0 播放列表 / 1 视频详情（与 pager 双向同步）
    var bottomTab by remember { mutableStateOf(0) }
    val player = rememberUfiExoPlayer(key = "media-video")

    // 播完停住（不自动下一个）
    LaunchedEffect(player) {
        player.pauseAtEndOfMediaItems = true
    }

    /**
     * 外挂字幕：path → 要挂给播放器的字幕轨。
     *
     * 只装"要用的"那几条，不是"目录里有的全部" —— 手动选过之后这里就只剩用户选的那个，
     * 于是重装 playlist 时不会又把自动匹配的一堆塞回去。
     */
    var subtitlesByPath by remember { mutableStateOf<Map<String, List<UfiSubtitleTrack>>>(emptyMap()) }

    /** 「选择字幕」弹窗开关（详情页那一行点开）。 */
    var showSubtitlePicker by remember { mutableStateOf(false) }

    /**
     * 自动匹配：为**当前要播的那一条**拉字幕。
     *
     * 刻意不给整条 playlist 都拉：一个文件夹几十集就是几十个请求，而用户当下只看一集。
     * 切集时本 effect 会因 [currentPath] 变化重跑，那时再拉下一集的。
     *
     * `subtitle_count == 0` 的项直接跳过 —— core 在 `/browse`/`/list` 里已经数过了，
     * 没有就不必再问一次（列表来源不带这个字段时 count 为 0，那就靠下面的 `?: true` 兜底问一次）。
     */
    val autoSubtitlePath = videos.getOrNull(currentIndex)?.path ?: filePath
    val autoSubtitleCount = videos.getOrNull(currentIndex)?.subtitle_count
    LaunchedEffect(autoSubtitlePath, autoSubtitleCount) {
        if (autoSubtitlePath.isBlank()) return@LaunchedEffect
        // 已经有（自动匹配过或用户手选过）就别覆盖用户的选择
        if (subtitlesByPath.containsKey(autoSubtitlePath)) return@LaunchedEffect
        if (autoSubtitleCount == 0) return@LaunchedEffect
        val tracks = media.subtitlesOf(autoSubtitlePath)
            .filter { it.supported }
            .map { it.toTrack(media::subtitleUrl) }
        if (tracks.isNotEmpty()) {
            subtitlesByPath = subtitlesByPath + (autoSubtitlePath to tracks)
        }
    }

    /**
     * 装 playlist。
     *
     * key 是"路径列表"而不是 videos 本身：翻页追加新项时列表长度会变，这时重装 playlist
     * 会打断正在播的那一条。所以只在**当前播放项仍在新列表里**时重装，并保持它继续播。
     *
     * [subtitlesByPath] 也是 key：字幕是异步拉回来的（以及用户手选），拿到之后必须重装
     * 当前项才能让播放器看见新字幕轨 —— MediaItem 的字幕配置在 setMediaItems 时就定死了。
     */
    val paths = remember(videos) { videos.map { it.path } }
    LaunchedEffect(paths, subtitlesByPath) {
        fun sourceOf(path: String) = UfiVideoSource(
            url = media.streamUrl(path),
            subtitles = subtitlesByPath[path].orEmpty()
        )
        if (paths.isEmpty()) {
            // 列表还没到：先单曲播路由带来的那个文件，等列表到了再整体换成 playlist
            val position = player.currentPosition
            player.setUfiSources(listOf(sourceOf(filePath)), 0)
            if (position > 0) player.seekTo(0, position)
            player.playWhenReady = true
            return@LaunchedEffect
        }
        val playingPath = paths.getOrNull(currentIndex)
        val start = when {
            playingPath != null && player.currentMediaItem != null ->
                paths.indexOf(playingPath).takeIf { it >= 0 } ?: 0
            else -> paths.indexOf(filePath).takeIf { it >= 0 } ?: 0
        }
        // 条数没变但字幕变了也要重装：否则刚拉到的字幕轨永远不会出现在齿轮菜单里
        val subtitlesChanged = player.currentMediaItem
            ?.localConfiguration?.subtitleConfigurations?.size !=
            subtitlesByPath[paths.getOrNull(start)].orEmpty().size
        if (player.mediaItemCount != paths.size || subtitlesChanged) {
            val position = player.currentPosition
            player.setUfiSources(paths.map { sourceOf(it) }, start)
            if (position > 0) player.seekTo(start, position)
            player.playWhenReady = true
        }
        currentIndex = start
    }

    // 当前播放项：控制条的上/下一集、播放结束都会改它，列表高亮跟着走
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                currentIndex = player.currentMediaItemIndex
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // ── 播放进度记忆（2026-09-16）──
    //
    // 三个写入时机缺一不可：定时（进程被杀也留得下）、切片时（旧片的进度得先落地）、
    // 页面销毁时（最后一次补齐）。判定口径统一在 MediaPlaybackProgress.kt。
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }
    val currentPath = videos.getOrNull(currentIndex)?.path ?: filePath
    var resumeToast by remember { mutableStateOf<ToastMessage?>(null) }

    // 续播：每换一片就查一次。只在"这一片刚开始播"时 seek，之后不再干涉用户的手动拖动
    var resumedPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentPath, player.mediaItemCount) {
        if (currentPath.isBlank() || resumedPath == currentPath) return@LaunchedEffect
        if (player.mediaItemCount == 0) return@LaunchedEffect
        resumedPath = currentPath
        val saved = mediaResumePosition(prefs, currentPath)
        if (saved > 0) {
            player.seekTo(saved)
            resumeToast = ToastMessage(
                text = "已从 ${formatResumeClock(saved)} 继续播放",
                type = ToastType.INFO
            )
        }
    }

    // 定时落盘：5 秒一次。间隔再短纯属浪费写入，再长则杀进程后会丢掉一段
    LaunchedEffect(player, currentPath) {
        while (true) {
            delay(PROGRESS_SAVE_INTERVAL_MS)
            mediaSaveProgress(prefs, currentPath, player.currentPosition, player.duration)
        }
    }

    // 切片 / 离开页面时补一次（DisposableEffect 的 key 带 currentPath，切片时就会先跑 onDispose）
    DisposableEffect(currentPath, player) {
        onDispose {
            mediaSaveProgress(prefs, currentPath, player.currentPosition, player.duration)
        }
    }


    // 退到后台暂停；回前台不自动续播（避免突然出声）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    UfiFullscreenSystemUiEffect(enabled = fullscreen)

    /*
     * 全屏时先吃掉一次返回：全屏是**页内状态**，返回键的第一层语义应该是"退出全屏"，
     * 而不是直接退出整个播放页（用户反馈：全屏再返回就直接回列表了）。
     * 非全屏时不拦截，返回照常由 UfiScreenScaffold 的返回键 / 系统手势退页。
     */
    BackHandler(enabled = fullscreen) { fullscreen = false }

    /**
     * 当前播放项的文件名。
     *
     * **不放进标题栏** —— 视频文件名动辄 `[Group] Title - 01 [1080p][HEVC][CHS&JPN].mkv`，
     * 而标题栏（44dp 高、还要扣掉返回键与右侧动作槽）横向只剩一半多，必然截成
     * 「[Group] Titl…」这种毫无信息量的残句。改为交给播放器正下方那条全宽标题条
     * （[MediaVideoTitleBar]，可占两行），标题栏本身固定「正在播放」，与音乐播放页一致。
     */
    val currentName = videos.getOrNull(currentIndex)?.name
        ?: filePath.substringAfterLast('/')


    /*
     * pager 状态必须建在 `if (!fullscreen)` **外面**。
     *
     * 放里面的话每次进出全屏整棵子树被移出组合，`rememberPagerState` 随之丢失 ——
     * 表现是「看到第 20 集 → 切个全屏 → 回来列表跳回顶部」。这不是观感问题而是功能缺陷。
     */
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { bottomTab = it }
    }
    // LazyColumn 的滚动位置同理，也要活过全屏切换
    val playlistListState = rememberLazyListState()

    UfiScreenScaffold(
        title = "正在播放",
        navController = navController,
        showBack = true,
        // 全屏时连标题栏一起收掉：那一刻整屏都该是画面
        showHeader = !fullscreen
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (fullscreen) PaddingValues(0.dp) else padding)
                .background(if (fullscreen) Color.Black else palette.pageBg)
        ) {
            UfiVideoSurface(
                player = player,
                modifier = if (fullscreen) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black)
                },
                // playlist 场景：上/下一集按钮打开（浮层单文件时是关的）
                showNextPrevious = true,
                onToggleFullscreen = { fullscreen = !fullscreen }
            )

            /*
             * 下半屏是**两页**，左右滑动切换：左「播放列表」、右「视频详情」。
             * 详情说的是"当前正在播的那一个"，所以它跟着 currentIndex 走，不需要额外的选中态。
             * 顶上的分段控件与滑动双向同步 —— 只给手势不给可点标签，等于把功能藏起来。
             *
             * 用 AnimatedVisibility 而不是裸 `if`：全屏切换原先是单帧硬跳（标题栏、下半屏
             * 同时消失/出现），在一个以"看画面"为主的页面上这种突变最扎眼。
             * 竖向展开/收起配淡入淡出，和画面放大的方向一致。
             */
            AnimatedVisibility(
                visible = !fullscreen,
                enter = expandVertically(animationSpec = UfiMotion.panelEnter()) +
                    fadeIn(animationSpec = tween(UfiMotion.Duration.Swift)),
                exit = shrinkVertically(animationSpec = UfiMotion.panelEnter()) +
                    fadeOut(animationSpec = tween(UfiMotion.Duration.Micro))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 文件名放这里而不是标题栏：全宽 + 可占两行，才装得下带标签的长文件名
                    MediaVideoTitleBar(
                        name = currentName,
                        index = currentIndex,
                        total = videos.size
                    )
                    UfiScrollableTabRow(
                        selectedTabIndex = bottomTab,
                        onTabSelected = { index ->
                            bottomTab = index
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        tabs = listOf("播放列表（${videos.size}）", "视频详情"),
                        // 与全站卡片同一条左边线（CardHorizontalMargin=16dp）。
                        // 原先用 Spacing.Medium(8dp)，这一页的列表比 App 里任何列表都更贴边。
                        modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
                    )
                    Spacer(Modifier.height(Spacing.Small))
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        if (page == 0) {
                            LazyColumn(
                                state = playlistListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    horizontal = Spacing.CardHorizontalMargin,
                                    vertical = Spacing.Small
                                ),
                                verticalArrangement = Arrangement.spacedBy(Spacing.Small)
                            ) {
                                if (videos.isEmpty()) {
                                    // 列表未到时给骨架而不是空白：这一页打开就在播了，
                                    // 下半屏一片空白会让人以为"只有这一个文件"。
                                    items(PLAYLIST_SKELETON_COUNT) {
                                        UfiSkeletonCard(lineCount = 2, showAvatar = true)
                                    }
                                } else {
                                    itemsIndexed(videos, key = { _, it -> it.id }) { index, item ->
                                        MediaVideoPlaylistRow(
                                            item = item,
                                            playing = index == currentIndex,
                                            thumbUrl = media.thumbnailUrl(
                                                MEDIA_TYPE_VIDEO,
                                                item.id
                                            ),
                                            onClick = {
                                                currentIndex = index
                                                player.seekToDefaultPosition(index)
                                                player.play()
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            MediaVideoDetailPane(
                                item = videos.getOrNull(currentIndex),
                                fallbackPath = filePath,
                                subtitleSummary = subtitleSummaryOf(subtitlesByPath[currentPath]),
                                onPickSubtitle = { showSubtitlePicker = true }
                            )
                        }
                    }
                }
            }
        }
    }


    // 续播提示：只在真的 seek 过之后弹一次，让人知道"不是从头开始"不是 bug
    UfiToastHost(toastMessage = resumeToast, onDismiss = { resumeToast = null })

    // 手动选字幕：列同目录**全部**字幕（不只是同名匹配的）——
    // 压制组命名、单独下载的字幕包经常和视频名对不上，只给自动匹配等于没给。
    MediaSubtitlePickerDialog(
        visible = showSubtitlePicker,
        videoPath = currentPath,
        selectedPath = subtitlesByPath[currentPath]?.firstOrNull()?.let { picked ->
            // track 里只留了 URL，反查原始路径太绕；用 label 作为"当前选中"的判据够用
            picked.label
        },
        load = { media.subtitlesOf(it, folderScope = true) },
        onDismiss = { showSubtitlePicker = false },
        onPick = { entry ->
            showSubtitlePicker = false
            subtitlesByPath = subtitlesByPath +
                (currentPath to listOf(entry.toTrack(media::subtitleUrl)))
        },
        onClear = {
            showSubtitlePicker = false
            // 空列表而不是移除 key：移除的话自动匹配的 effect 会立刻再挂回去，
            // "关掉字幕"就变成了点不掉的假按钮
            subtitlesByPath = subtitlesByPath + (currentPath to emptyList())
        }
    )
}

/** 详情页那一行的副标题：挂了几条、叫什么。 */
private fun subtitleSummaryOf(tracks: List<UfiSubtitleTrack>?): String = when {
    tracks == null -> "正在查找同名字幕…"
    tracks.isEmpty() -> "未挂载（可手动选择）"
    tracks.size == 1 -> tracks.first().label
    else -> "${tracks.size} 条 · ${tracks.first().label} 等"
}

/** 列表未到时铺几条骨架。6 条刚好填满一屏下半区，再多是白做。 */
private const val PLAYLIST_SKELETON_COUNT = 6

/**
 * 播放器正下方的标题条：文件名 + 序号。
 *
 * ## 为什么文件名不放标题栏
 * 标题栏高 44dp（`Spacing.HeaderHeight`），横向还要扣掉返回键与右侧动作槽，实际留给标题的
 * 不到屏宽一半。而视频文件名的现实形态是 `[Group] Title - 01 [1080p][HEVC][CHS&JPN].mkv`，
 * 塞进去只会截成「[Group] Titl…」——**恰好把最没用的前缀留下，把集数和画质切掉**。
 *
 * 这里是全宽、允许两行，且字号用 `sectionTitle` 与音乐播放页的曲名同级。
 * 标题栏改为固定「正在播放」，与音乐页一致（那边也是这么处理的）。
 *
 * ## 序号
 * playlist 有多项时右侧显示 `3 / 24`。用等宽体（`monoCaption`）—— 切集时数字不会左右跳动。
 * 只有一项就不显示：`1 / 1` 是噪音。
 */
@Composable
private fun MediaVideoTitleBar(name: String, index: Int, total: Int) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Spacing.CardHorizontalMargin,
                vertical = Spacing.Large
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = UfiTextStyles.sectionTitle,
            color = palette.textPrimary,
            maxLines = TITLE_BAR_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (total > 1) {
            Spacer(Modifier.width(Spacing.Medium))
            Text(
                text = "${index + 1} / $total",
                style = UfiTextStyles.monoCaption,
                color = palette.textSecondary
            )
        }
    }
}

/** 标题条最多两行：一行装不下长文件名，三行就把下半屏挤掉了。 */
private const val TITLE_BAR_MAX_LINES = 2


/** 播放列表缩略图尺寸（16:9）。视频的核心信息就是画面，列表里不给图等于浪费。 */
private val PLAYLIST_THUMB_WIDTH = 64.dp
private val PLAYLIST_THUMB_HEIGHT = 36.dp

/**
 * 播放列表的一行。
 *
 * 相比原来的「20dp 图标 + 文件名」，这里把**缩略图**提为主视觉 —— 一屏十几集光看文件名
 * （常常是 `[Group] Title - 01 [1080p].mkv` 这种）根本分不出哪集是哪集。
 * 缩略图链路（四级回落 + 磁盘缓存）本来就为媒体库建好了，播放列表白不用。
 *
 * 正在播的那一条：缩略图右上角压一个 accent 播放标，而不是把整行文字染色 ——
 * 染色会和「选中态背景」打架（UfiListRowCard 已经用背景表达选中了）。
 * 标记的出现/消失走 [AnimatedContent]，切集时不是硬跳。
 */
@Composable
private fun MediaVideoPlaylistRow(
    item: MediaLibraryItem,
    playing: Boolean,
    thumbUrl: String,
    onClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    UfiListRowCard(
        title = item.name,
        subtitle = listOf(
            formatMediaDuration(item.duration_ms),
            FormatUtils.formatSize(item.size),
            // 有外挂字幕就标出来：core 在 /list、/browse 里已经数过，这里零成本
            if (item.subtitle_count > 0) "字幕 ${item.subtitle_count}" else ""
        ).filter { it.isNotBlank() }.joinToString(" · "),
        selected = playing,
        onClick = onClick,
        leading = {
            Box(
                modifier = Modifier
                    .width(PLAYLIST_THUMB_WIDTH)
                    .height(PLAYLIST_THUMB_HEIGHT)
            ) {
                MediaThumb(
                    url = thumbUrl,
                    fallback = Icons.Default.Videocam,
                    width = PLAYLIST_THUMB_WIDTH,
                    height = PLAYLIST_THUMB_HEIGHT
                )
                // 播放标只在当前项上出现，用动画进出（原先是 PlayArrow↔Videocam 瞬间替换）
                AnimatedContent(
                    targetState = playing,
                    transitionSpec = {
                        fadeIn(tween(UfiMotion.Duration.Swift)) togetherWith
                            fadeOut(tween(UfiMotion.Duration.Micro))
                    },
                    label = "playlist-playing-badge"
                ) { isPlaying ->
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(palette.accent.copy(alpha = PLAYING_SCRIM_ALPHA)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "正在播放",
                                tint = Color.White,
                                modifier = Modifier.size(Spacing.IconSizeMedium)
                            )
                        }
                    }
                }
            }
        }
    )
}

/** 正在播放那条缩略图上的 accent 蒙层透明度：够看出高亮，又不至于把画面盖没。 */
private const val PLAYING_SCRIM_ALPHA = 0.55f


/**
 * 「手动选字幕文件」弹窗。
 *
 * 打开时才去拉 `scope=folder`（同目录全部字幕）—— 这是个相对重的目录扫描，
 * 不该在每次进播放页时都发生。
 *
 * `supported = false` 的条目照样列出但点不动：core 会把 MicroDVD `.sub`、SAMI `.smi`
 * 一并返回，界面上直接藏掉的话，用户对着目录里明明存在的文件只会以为 App 瞎了。
 */
@Composable
private fun MediaSubtitlePickerDialog(
    visible: Boolean,
    videoPath: String,
    selectedPath: String?,
    load: suspend (String) -> List<MediaSubtitleEntry>,
    onDismiss: () -> Unit,
    onPick: (MediaSubtitleEntry) -> Unit,
    onClear: () -> Unit
) {
    if (!visible) return
    val palette = LocalResolvedPalette.current
    var entries by remember(videoPath) { mutableStateOf<List<MediaSubtitleEntry>?>(null) }
    LaunchedEffect(videoPath) { entries = load(videoPath) }

    UfiScrollableDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "选择字幕",
        confirmButton = {
            UfiButton(
                text = "关闭字幕",
                onClick = onClear,
                variant = UfiButtonVariant.Subtle,
                size = UfiButtonSize.Small
            )
        },
        dismissButton = {
            UfiButton(
                text = "取消",
                onClick = onDismiss,
                variant = UfiButtonVariant.Subtle,
                size = UfiButtonSize.Small
            )
        }
    ) {
        val list = entries
        when {
            list == null -> Text(
                "正在扫描目录…",
                style = UfiTextStyles.note,
                color = palette.textSecondary
            )
            list.isEmpty() -> Text(
                "这个目录里没有字幕文件。把 .srt / .ass / .vtt 放到视频旁边即可。",
                style = UfiTextStyles.note,
                color = palette.textSecondary
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                list.forEach { entry ->
                    UfiListRowCard(
                        title = entry.name,
                        subtitle = listOf(
                            entry.label.ifBlank { entry.ext.uppercase() },
                            FormatUtils.formatSize(entry.size),
                            if (entry.supported) "" else "格式不支持"
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        selected = entry.label == selectedPath,
                        onClick = { if (entry.supported) onPick(entry) }
                    )
                }
            }
        }
    }
}

/**
 * 「视频详情」页：当前正在播的这一个视频的元信息。
 *
 * 数据全部来自 `/api/media/list` 已经返回的字段（core 从系统媒体库读的），这里**不额外发请求**
 * 也不自己解码文件去算分辨率 —— 列表里没有的字段就不显示，不拿占位符假装有值。
 */
@Composable
private fun MediaVideoDetailPane(
    item: MediaLibraryItem?,
    fallbackPath: String,
    subtitleSummary: String,
    onPickSubtitle: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    if (item == null) {
        // 用公共空态而不是一行居中 Text：这一页原先是全站唯一还在手搓空态的地方。
        // 文案给"为什么"而不只是"没有"——列表是异步来的，路径先显示出来让人知道播的是哪个文件。
        UfiEmptyState(
            icon = Icons.Default.Videocam,
            message = "列表还没到",
            hint = "当前播放：${fallbackPath.substringAfterLast('/')}",
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.Small)
    ) {
        // 字幕放最前面且是**可点的**：这是详情页里唯一能操作的东西，埋在信息行中间会找不到。
        // 自动匹配只认同名，而现实里字幕名和视频名经常对不上，必须留手动入口。
        UfiListRowCard(
            title = "字幕",
            subtitle = subtitleSummary,
            onClick = onPickSubtitle,
            leading = {
                Icon(
                    Icons.Default.Subtitles,
                    contentDescription = null,
                    tint = palette.accent
                )
            },
            modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
        )
        Spacer(Modifier.height(Spacing.SettingsCardGap))

        // 信息分两组卡片，而不是七行平铺 —— 与 MediaVideoSettingsScreen 的版式对齐。
        // 「文件」是用户改得动的东西（名字、位置、大小），「媒体规格」是文件本身的属性。
        UfiSectionHeader(title = "文件")
        UfiSettingsGroup {
            UfiInfoRow(label = "文件名", value = item.name)
            UfiInfoRow(label = "路径", value = item.path)
            UfiInfoRow(label = "大小", value = FormatUtils.formatSize(item.size))
            UfiInfoRow(
                label = "修改时间",
                value = if (item.date_modified > 0) {
                    FormatUtils.formatTimestamp(item.date_modified)
                } else {
                    null
                }
            )
        }
        UfiSectionHeader(title = "媒体规格")
        UfiSettingsGroup {
            UfiInfoRow(
                label = "时长",
                value = formatMediaDuration(item.duration_ms).ifBlank { null }
            )
            UfiInfoRow(
                label = "分辨率",
                value = if (item.width > 0 && item.height > 0) {
                    "${item.width} × ${item.height}"
                } else {
                    null
                }
            )
            UfiInfoRow(label = "类型", value = item.mime.ifBlank { null })
        }

    }
}
