package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.components.common.UfiErrorBanner
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiListLoadingState
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.navigation.ufiNavigateOnce
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.AUDIO_SCOPE_PLAYLIST
import com.ufi_axis.viewmodel.state.AudioQueueOwner
import kotlinx.coroutines.delay

/**
 * 歌单详情（一个歌单里的曲目）。
 *
 * 骨架照 [MediaAudioGroupScreen]（进页拉、离开清、顶栏两颗播放键、底部挂迷你条），
 * 曲目列表直接复用 [MediaAudioList] —— core 回的 item 与 `/api/media/list` 逐字段一致，
 * 一行要显示的东西完全一样。
 *
 * ## 与分组详情的三处不同
 * 1. **顺序是用户排定的**，不套音乐页的排序偏好（core 原样给出，见 `AUDIO_SCOPE_PLAYLIST`）；
 * 2. **不分页**：core 一次给全量，所以 `onNearEnd` 是空实现、`total` 就是 `items.size`；
 * 3. **有已失效的条目**（文件被删 / 卡没插）：它们照样列出来并带 `missing`，
 *    点不动但能长按移出 —— 自动帮用户清理等于拔一次卡就丢歌。
 *
 * ## 标题从状态里取，不从路由参数
 * 路由只带 id（歌单会被重命名，而路由参数会随返回栈一直留在进程里）。名字优先取详情里那份
 * （`/items` 响应带 name），没拉到时退回歌单列表里那条，两处都没有就显示"歌单"——
 * 至少标题栏不是空白。
 */
@Composable
fun MediaPlaylistScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    id: String
) {
    val palette = LocalResolvedPalette.current
    val media = viewModel.media
    val state by media.state.collectAsState()
    val detail = state.playlistDetail(id)
    val playable = detail.playable

    LaunchedEffect(id) { media.loadPlaylistItems(id) }
    DisposableEffect(id) {
        onDispose { media.clearPlaylistItems(id) }
    }

    /** 长按选中、等待确认移出的那一首。null = 没有待确认的操作。 */
    var pendingRemove by remember { mutableStateOf<MediaLibraryItem?>(null) }

    /*
     * 歌单操作提示几秒后自动消失。
     *
     * 必须在这一页也做一份：提示挂在 `MediaLibraryState.playlists.message` 上，而音乐页的
     * 自动清理 effect 只在那一页被组合时才跑 —— 在这一页移出一首歌，那条提示会一直挂到
     * 用户返回上一页为止。
     */
    val notice = state.playlists.message ?: state.playlists.errorMessage
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(MEDIA_PLAYLIST_NOTICE_MS)
            media.clearPlaylistMessage()
        }
    }

    UfiScreenScaffold(
        title = playlistTitleOf(detail.name, state.playlists.playlists.firstOrNull { it.id == id }?.name),
        navController = navController,
        showBack = true,
        actions = {
            // 判据是"有没有**能播的**歌"，不是"有没有歌"：一份全是失效条目的歌单，
            // 这两颗点了只能什么都不做
            if (playable.isNotEmpty()) {
                /*
                 * 与分组详情页同一条理由：这一页没有 MediaController（播放器跑在跨进程的
                 * MediaSessionService 里，只有播放页持有 controller），所以「播放全部」
                 * 是"导航到播放页 + 带上歌单作用域"，装载逻辑复用播放页那一套。
                 */
                IconButton(onClick = {
                    navController.ufiNavigateOnce(
                        mediaAudioRouteOf(
                            playable.first().path,
                            scopeKind = AUDIO_SCOPE_PLAYLIST,
                            scopeKey = id
                        )
                    )
                }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "播放全部")
                }
                // 起点也要随机，否则"点了随机却还是从头放"
                IconButton(onClick = {
                    AudioQueueOwner.requestShuffle()
                    navController.ufiNavigateOnce(
                        mediaAudioRouteOf(
                            playable.random().path,
                            scopeKind = AUDIO_SCOPE_PLAYLIST,
                            scopeKey = id
                        )
                    )
                }) {
                    Icon(Icons.Default.Shuffle, contentDescription = "随机播放")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            (detail.errorMessage ?: state.playlists.errorMessage)?.let { err ->
                Spacer(Modifier.height(Spacing.Small))
                Box(modifier = Modifier.padding(horizontal = Spacing.Medium)) {
                    UfiErrorBanner(message = err)
                }
            }
            // 失效条目数单独说一句：列表里那几行自己也标了，但页头这句让人一眼知道"少了几首"
            if (detail.missingCount > 0) {
                Spacer(Modifier.height(Spacing.Small))
                Text(
                    "$MEDIA_PLAYLIST_MISSING_PREFIX${detail.missingCount} 首",
                    style = UfiTextStyles.note,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.Medium)
                )
            }
            state.playlists.message?.let { msg ->
                Spacer(Modifier.height(Spacing.Small))
                Text(
                    msg,
                    style = UfiTextStyles.note,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.Medium)
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.Medium)
            ) {
                when {
                    // 与列表页同一条红线：加载中**且**一条都没有才换骨架
                    detail.isEmpty && detail.isLoading -> UfiListLoadingState(
                        leadingWidth = MEDIA_AUDIO_THUMB_SIZE,
                        leadingHeight = MEDIA_AUDIO_THUMB_SIZE,
                        leadingCircle = false
                    )

                    detail.isEmpty -> UfiListEmptyState(
                        text = "这个歌单还是空的，在音乐列表里长按一首歌就能加进来",
                        icon = Icons.Default.QueueMusic
                    )

                    else -> MediaAudioList(
                        items = detail.items,
                        // core 一次给全量，所以 total 就是已加载条数 —— 不显示"已加载 x / y"
                        total = detail.items.size,
                        thumbUrl = { media.thumbnailUrl(MEDIA_TYPE_AUDIO, it.id) },
                        // 不分页，没有"翻到底再拉一页"这件事
                        onNearEnd = {},
                        onClick = {
                            navController.ufiNavigateOnce(
                                mediaAudioRouteOf(
                                    it.path,
                                    scopeKind = AUDIO_SCOPE_PLAYLIST,
                                    scopeKey = id
                                )
                            )
                        },
                        onLongClick = { pendingRemove = it }
                    )
                }
            }

            // 底部迷你条：从这一页点开一首再返回来继续翻，控制入口不该消失（同分组详情页）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
                verticalArrangement = Arrangement.spacedBy(Spacing.Small)
            ) {
                MediaAudioMiniBar(navController)
            }
        }
    }

    pendingRemove?.let { item ->
        UfiConfirmDialog(
            title = "移出歌单",
            text = "把「${audioDisplayTitle(item)}」从这个歌单里移出？文件本身不会被删除。",
            confirmText = "移出",
            onConfirm = {
                media.removeFromPlaylist(id, listOf(item.path))
                pendingRemove = null
            },
            onDismiss = { pendingRemove = null }
        )
    }
}

/** 标题取值链：详情里的名字 → 列表里的名字 → 泛称。理由见本文件 KDoc。 */
private fun playlistTitleOf(detailName: String, listName: String?): String =
    detailName.ifBlank { listName.orEmpty() }.ifBlank { "歌单" }

/** 页头"已失效"那句的前缀。 */
private const val MEDIA_PLAYLIST_MISSING_PREFIX = "有 "

/** 歌单操作提示的存留时长。与音乐页同一个值 —— 两页显示的是同一条提示。 */
private const val MEDIA_PLAYLIST_NOTICE_MS = 4000L
