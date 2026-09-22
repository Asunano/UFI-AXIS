package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.model.PlaylistEntry
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiListLoadingState
import com.ufi_axis.ui.components.common.UfiListRowCard
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.state.MediaPlaylistState

/**
 * 音乐页「歌单」分页的列表。
 *
 * 形态照 [MediaAudioGroupList]（同样是"一行 = 一堆曲目 + 封面 + 条数"），但**不是**同一个组件：
 * 分组是按标签聚合出来的、只能看；歌单是用户建的实体，有重命名/删除，行为不同。
 * 强行合并的结果是一个带四五个开关的组件。
 *
 * 封面取首曲的 `cover_id`（URL 由调用方拼 —— 这一层是无状态展示件）；
 * `cover_id` 为 0 = 空歌单或首曲已失效，此时**不拼 URL**、只画占位
 * （否则每一行都会发一次注定 404 的图片请求，往 release 日志里写 WARN）。
 *
 * @param onLongClick 长按一行（重命名 / 删除的入口）。
 * @param listState 滚动位置。默认自己 remember 一份；音乐页必须从外面传 ——
 *   四个分页共用一份 state 会互相串位（理由同 [MediaAudioGroupList]）。
 */
@Composable
internal fun MediaPlaylistList(
    state: MediaPlaylistState,
    coverUrl: (PlaylistEntry) -> String?,
    onClick: (PlaylistEntry) -> Unit,
    onLongClick: (PlaylistEntry) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val palette = LocalResolvedPalette.current

    when {
        // 判据是「还没拉到过结果」而不是「正在加载」—— 理由与 MediaAudioGroupList 完全一致：
        // 请求由 LaunchedEffect 在组合之后才发，看 isLoading 会先闪一帧空态
        !state.loadedOnce -> UfiListLoadingState(
            leadingWidth = MEDIA_PLAYLIST_COVER_SIZE,
            leadingHeight = MEDIA_PLAYLIST_COVER_SIZE,
            leadingCircle = false
        )

        state.playlists.isEmpty() -> UfiListEmptyState(
            // 拉失败时错误话术比"还没有歌单"更有信息量，优先显示它
            text = state.errorMessage ?: MEDIA_PLAYLIST_EMPTY_HINT,
            icon = Icons.Default.QueueMusic
        )

        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Spacing.Small),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small)
        ) {
            items(state.playlists, key = { it.id }) { entry ->
                val url = coverUrl(entry)
                UfiListRowCard(
                    title = entry.name,
                    subtitle = if (entry.count == 0) "空歌单" else null,
                    onClick = { onClick(entry) },
                    onLongClick = { onLongClick(entry) },
                    leading = {
                        if (url == null) {
                            MediaThumbPlaceholder(
                                Icons.Default.QueueMusic,
                                MEDIA_PLAYLIST_COVER_SIZE
                            )
                        } else {
                            MediaThumb(
                                url = url,
                                fallback = Icons.Default.QueueMusic,
                                width = MEDIA_PLAYLIST_COVER_SIZE,
                                height = MEDIA_PLAYLIST_COVER_SIZE
                            )
                        }
                    },
                    trailing = {
                        Text(
                            "${entry.count} 首",
                            style = UfiTextStyles.monoCaption,
                            color = palette.textSecondary
                        )
                    }
                )
            }
        }
    }
}

/** 歌单封面边长。与分组行同一档（48dp）：两者在界面上是同一类"一堆曲目"的行。 */
private val MEDIA_PLAYLIST_COVER_SIZE = 48.dp

/** 一个歌单都没有时的引导文案。 */
internal const val MEDIA_PLAYLIST_EMPTY_HINT = "还没有歌单，点右上角「＋」新建一个"
