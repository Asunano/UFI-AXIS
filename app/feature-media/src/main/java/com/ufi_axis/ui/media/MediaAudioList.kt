package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.ui.components.common.UfiListLoadedCounter
import com.ufi_axis.ui.components.common.UfiListRowCard
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 音乐页的列表（[MediaAudioScreen] 的内容区）。
 *
 * 一行 = 方形封面 + 曲名 + 「艺术家 · 专辑」+ 右侧时长。点开进 [MediaAudioPlayerScreen]。
 *
 * 行壳与视频/其他列表共用 [UfiListRowCard]；这一栏只决定三件事：封面是方的、副信息是
 * 艺术家与专辑、时长靠右单列。信息不同才是分栏的意义，样式不该跟着不同。
 *
 * @param listState 滚动位置。默认自己 remember 一份（分组详情页就这么用）；音乐页要从外面传 ——
 *   那一页的四个分类 Tab 是"换一棵子树"，`rememberLazyListState()` 会随子树一起没了，
 *   切回"全部"就回到顶部。
 * @param onLongClick 长按一行（"加入歌单" / "移出歌单"的入口）。null = 不挂长按手势。
 */
@Composable
internal fun MediaAudioList(
    items: List<MediaLibraryItem>,
    total: Int,
    thumbUrl: (MediaLibraryItem) -> String,
    onNearEnd: () -> Unit,
    onClick: (MediaLibraryItem) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    onLongClick: ((MediaLibraryItem) -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    MediaNearEndEffect(listState, items.size, onNearEnd)


    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.Small),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        // key 用 path 而不是 id：歌单里已失效的条目 **id 全是 0**（媒体库里查不到那一行），
        // 按 id 做 key 会出现重复键，LazyColumn 直接抛异常。path 在媒体库里本来就是唯一的。
        items(items, key = { it.path }) { item ->
            val duration = formatMediaDuration(item.duration_ms)
            UfiListRowCard(
                // 显示曲名而不是文件名（取值链见 audioDisplayTitle）
                title = audioDisplayTitle(item),
                subtitle = if (item.missing) {
                    // 已失效的行要说清是"文件不在了"，而不是让用户对着一行没有时长的歌发愣
                    MEDIA_AUDIO_MISSING_NOTE
                } else {
                    listOf(audioDisplayArtist(item), item.album)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                },
                // 失效的条目点了也放不出来（没有可取流的文件），所以不给单击入口；
                // 长按仍然保留 —— 用户要能把它移出歌单。
                onClick = if (item.missing) null else ({ onClick(item) }),
                onLongClick = onLongClick?.let { handler -> { handler(item) } },
                leading = {
                    if (item.missing) {
                        // id 是 0，缩略图 URL 注定 404 —— 不发那次请求（见 MediaThumbPlaceholder）
                        MediaThumbPlaceholder(Icons.Default.MusicNote, MEDIA_AUDIO_THUMB_SIZE)
                    } else {
                        MediaThumb(
                            url = thumbUrl(item),
                            fallback = Icons.Default.MusicNote,
                            width = MEDIA_AUDIO_THUMB_SIZE,
                            height = MEDIA_AUDIO_THUMB_SIZE
                        )
                    }
                },
                trailing = if (duration.isBlank()) null else {
                    {
                        Text(
                            duration,
                            style = UfiTextStyles.monoCaption,
                            color = palette.textSecondary
                        )
                    }
                }
            )
        }
        if (items.size < total) {
            item { UfiListLoadedCounter(loaded = items.size, total = total) }
        }
    }
}

/** 歌单里文件已经不在媒体库时那一行的副标题。 */
internal const val MEDIA_AUDIO_MISSING_NOTE = "文件已不在媒体库（长按可移出）"


/**
 * 曲目行封面的边长。
 *
 * 三处共用同一个值：真实行、首屏骨架的前置槽（[MediaLibraryPage] 与 [MediaAudioGroupScreen]
 * 都要传）、底部迷你控制条的封面。分散写 `44.dp` 的话，改一处就会出现"骨架比真实行矮一截"
 * 或"控制条封面与列表不齐"这类只在真机上看得出的错位。
 */
internal val MEDIA_AUDIO_THUMB_SIZE = 44.dp
