package com.ufi_axis.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.data.model.MediaLibraryItem
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
     * 装 playlist。
     *
     * key 是"路径列表"而不是 videos 本身：翻页追加新项时列表长度会变，这时重装 playlist
     * 会打断正在播的那一条。所以只在**当前播放项仍在新列表里**时重装，并保持它继续播。
     */
    val paths = remember(videos) { videos.map { it.path } }
    LaunchedEffect(paths) {
        if (paths.isEmpty()) {
            // 列表还没到：先单曲播路由带来的那个文件，等列表到了再整体换成 playlist
            player.setUfiPlaylist(listOf(media.streamUrl(filePath)), 0)
            player.playWhenReady = true
            return@LaunchedEffect
        }
        val playingPath = paths.getOrNull(currentIndex)
        val start = when {
            playingPath != null && player.currentMediaItem != null ->
                paths.indexOf(playingPath).takeIf { it >= 0 } ?: 0
            else -> paths.indexOf(filePath).takeIf { it >= 0 } ?: 0
        }
        if (player.mediaItemCount != paths.size) {
            val position = player.currentPosition
            player.setUfiPlaylist(paths.map { media.streamUrl(it) }, start)
            if (player.currentMediaItem != null && position > 0) player.seekTo(start, position)
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

    val title = videos.getOrNull(currentIndex)?.name
        ?: filePath.substringAfterLast('/')

    UfiScreenScaffold(
        title = title,
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

            if (!fullscreen) {
                /*
                 * 下半屏是**两页**，左右滑动切换：左「播放列表」、右「视频详情」。
                 * 详情说的是"当前正在播的那一个"，所以它跟着 currentIndex 走，不需要额外的选中态。
                 * 顶上的分段控件与滑动双向同步 —— 只给手势不给可点标签，等于把功能藏起来。
                 */
                val pagerState = rememberPagerState(pageCount = { 2 })
                val scope = rememberCoroutineScope()
                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.currentPage }.collect { bottomTab = it }
                }
                Spacer(Modifier.height(Spacing.Medium))
                UfiScrollableTabRow(
                    selectedTabIndex = bottomTab,
                    onTabSelected = { index ->
                        bottomTab = index
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    tabs = listOf("播放列表（${videos.size}）", "视频详情"),
                    modifier = Modifier.padding(horizontal = Spacing.Medium)
                )
                Spacer(Modifier.height(Spacing.Small))
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    if (page == 0) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                horizontal = Spacing.Medium,
                                vertical = Spacing.Small
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Small)
                        ) {
                            itemsIndexed(videos, key = { _, it -> it.id }) { index, item ->
                                UfiListRowCard(
                                    title = item.name,
                                    subtitle = listOf(
                                        formatMediaDuration(item.duration_ms),
                                        FormatUtils.formatSize(item.size)
                                    ).filter { it.isNotBlank() }.joinToString(" · "),
                                    selected = index == currentIndex,
                                    onClick = {
                                        currentIndex = index
                                        player.seekToDefaultPosition(index)
                                        player.play()
                                    },
                                    leading = {
                                        Icon(
                                            if (index == currentIndex) {
                                                Icons.Default.PlayArrow
                                            } else {
                                                Icons.Default.Videocam
                                            },
                                            contentDescription = null,
                                            tint = if (index == currentIndex) palette.accent
                                            else palette.textSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                            }
                        }
                    } else {
                        MediaVideoDetailPane(item = videos.getOrNull(currentIndex), fallbackPath = filePath)
                    }
                }
            }
        }
    }

    // 续播提示：只在真的 seek 过之后弹一次，让人知道"不是从头开始"不是 bug
    UfiToastHost(toastMessage = resumeToast, onDismiss = { resumeToast = null })
}

/**
 * 「视频详情」页：当前正在播的这一个视频的元信息。
 *
 * 数据全部来自 `/api/media/list` 已经返回的字段（core 从系统媒体库读的），这里**不额外发请求**
 * 也不自己解码文件去算分辨率 —— 列表里没有的字段就不显示，不拿占位符假装有值。
 */
@Composable
private fun MediaVideoDetailPane(item: MediaLibraryItem?, fallbackPath: String) {
    val palette = LocalResolvedPalette.current
    if (item == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "列表还没到，暂时只有路径：$fallbackPath",
                style = UfiTextStyles.note,
                color = palette.textSecondary,
                modifier = Modifier.padding(Spacing.Medium)
            )
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Medium, vertical = Spacing.Small)
    ) {
        UfiInfoRow(label = "文件名", value = item.name)
        UfiInfoRow(label = "路径", value = item.path)
        UfiInfoRow(label = "大小", value = FormatUtils.formatSize(item.size))
        UfiInfoRow(
            label = "时长",
            value = formatMediaDuration(item.duration_ms).ifBlank { null }
        )
        UfiInfoRow(
            label = "分辨率",
            value = if (item.width > 0 && item.height > 0) "${item.width} × ${item.height}" else null
        )
        UfiInfoRow(label = "类型", value = item.mime.ifBlank { null })
        UfiInfoRow(
            label = "修改时间",
            value = if (item.date_modified > 0) {
                FormatUtils.formatTimestamp(item.date_modified)
            } else {
                null
            }
        )
    }
}
