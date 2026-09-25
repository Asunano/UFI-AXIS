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
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.ufi_axis.data.model.MEDIA_GROUP_FOLDER
import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.ui.components.common.UfiErrorBanner
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiListLoadingState
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.navigation.ufiNavigateOnce
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.AudioQueueOwner

/**
 * 一个分组里的曲目（专辑 / 歌手 / 文件夹三种维度共用一页）。
 *
 * 曲目列表直接复用 [MediaAudioList]：组内曲目走的就是 `/api/media/list`（带 album/artist/dir 过滤），
 * 一行要显示的东西与"全部"那一页完全一样。
 *
 * ## 标题只认路由参数
 * 不去 `audioGroups` 里按 key 找那条 [com.ufi_axis.data.model.MediaGroupEntry] 拿 title ——
 * 从别处深链进来（或进程重建后还原返回栈）时分组列表可能一条都没加载，那样标题会是空白。
 * 路由参数是这一页唯一保证存在的输入。
 *
 * ## 为什么离开就清掉
 * 专辑动辄上百个，逛一圈把每一组的曲目都留在内存里没有意义。`onDispose` 里调
 * `clearGroupItems`，下次进来重新拉第一页。
 */
@Composable
fun MediaAudioGroupScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    by: String,
    key: String
) {
    val media = viewModel.media
    val state by media.state.collectAsState()
    val items = state.groupItems(by, key)

    /** 长按选中、待弹动作表的那一首。null = 不显示（见 MediaTrackActionHost）。 */
    var actionTarget by remember { mutableStateOf<MediaLibraryItem?>(null) }

    LaunchedEffect(by, key) { media.loadGroupItems(by, key) }
    DisposableEffect(by, key) {
        onDispose { media.clearGroupItems(by, key) }
    }

    UfiScreenScaffold(
        title = mediaAudioGroupTitle(by, key),
        navController = navController,
        showBack = true,
        actions = {
            // 列表还没拉到就不给这两颗 —— 它们需要一个起点曲目，空列表点了只能什么都不做
            if (items.items.isNotEmpty()) {
                /*
                 * 「播放全部」不直接操控播放器：这一页没有 MediaController（播放器是跨进程的
                 * MediaSessionService，只有播放页持有 controller）。改为导航到播放页并带上
                 * **本组作用域** —— 队列装载那一套逻辑已经会把整组拉齐并从指定那首开始，
                 * 这里再写一遍只会多一处要同步维护的装载口径。
                 */
                IconButton(onClick = {
                    navController.ufiNavigateOnce(
                        mediaAudioRouteOf(items.items.first().path, scopeKind = by, scopeKey = key)
                    )
                }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "播放全部")
                }
                /*
                 * 「随机播放」= 随机挑一首当起点 + 请求播放页把 shuffle 打开。
                 *
                 * 起点也要随机：只开 shuffle 而仍从第一首起播，观感是"点了随机却还是从头放"。
                 * 起点只从**已加载的这一页**里挑（真正的随机顺序由播放器的 shuffle 负责，
                 * 它作用在拉齐后的整组队列上），所以不需要为了挑一首先把几百首全拉下来。
                 */
                IconButton(onClick = {
                    AudioQueueOwner.requestShuffle()
                    navController.ufiNavigateOnce(
                        mediaAudioRouteOf(items.items.random().path, scopeKind = by, scopeKey = key)
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
            items.errorMessage?.let { err ->
                Spacer(Modifier.height(Spacing.Small))
                Box(modifier = Modifier.padding(horizontal = Spacing.Medium)) {
                    UfiErrorBanner(message = err)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.Medium)
            ) {
                when {
                    // 骨架与列表页同一条红线：在加载**且**一条都没有才换骨架
                    items.items.isEmpty() && items.isLoading -> UfiListLoadingState(
                        leadingWidth = MEDIA_AUDIO_THUMB_SIZE,
                        leadingHeight = MEDIA_AUDIO_THUMB_SIZE,
                        leadingCircle = false
                    )

                    // 有错误时错误横幅已经在上面说明了原因，这里只说"没有内容"
                    items.items.isEmpty() -> UfiListEmptyState(
                        text = "这一组里没有可播放的音乐",
                        icon = mediaGroupIcon(by)
                    )

                    else -> MediaAudioList(
                        items = items.items,
                        total = items.total,
                        thumbUrl = { media.thumbnailUrl(MEDIA_TYPE_AUDIO, it.id) },
                        onNearEnd = { media.loadMoreGroupItems(by, key) },
                        // 与"全部"那一页同一条守卫：连点两行会 push 两个播放页（见 ufiNavigateOnce）
                        // 2026-09-21：带上**播放范围**（by/key）—— 在这一页点歌，队列就该是这一组，
                        // 不带的话队列会是整库，「下一首」跑去别的专辑。
                        onClick = {
                            navController.ufiNavigateOnce(
                                mediaAudioRouteOf(it.path, scopeKind = by, scopeKey = key)
                            )
                        },
                        // 长按 = 动作表，与"全部"那一页同一个入口
                        onLongClick = { actionTarget = it }

                    )
                }
            }

            // 底部也挂一条迷你控制条：从这一页点开一首歌、返回来继续翻，控制入口不该消失。
            // 没有队列时它不渲染，spacedBy 因此也不会留下空隙。
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

    MediaTrackActionHost(
        viewModel = viewModel,
        target = actionTarget,
        actionsOf = {
            mediaTrackActionsFor(it, inPlaylist = false, canEditLibrary = true, canPlayNext = true)
        },
        onDone = { actionTarget = null }
    )
}


/**
 * 页面标题。
 *
 * 文件夹的 key 是绝对路径，整条摆在标题栏里只会被截断成看不出是哪个目录，所以取末段目录名；
 * 专辑与歌手的 key 本身就是要显示的名字。key 为空（理论上进不来，见 [MediaAudioGroupList]）
 * 时退回维度名，至少标题栏不是空白。
 */
private fun mediaAudioGroupTitle(by: String, key: String): String = when {
    key.isBlank() -> mediaGroupLabel(by)
    by == MEDIA_GROUP_FOLDER -> key.trimEnd('/').substringAfterLast('/').ifBlank { key }
    else -> key
}
