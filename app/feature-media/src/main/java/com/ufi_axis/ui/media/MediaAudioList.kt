package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
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
 */
@Composable
internal fun MediaAudioList(
    items: List<MediaLibraryItem>,
    total: Int,
    thumbUrl: (MediaLibraryItem) -> String,
    onNearEnd: () -> Unit,
    onClick: (MediaLibraryItem) -> Unit
) {
    val palette = LocalResolvedPalette.current
    val listState = rememberLazyListState()
    MediaNearEndEffect(listState, items.size, onNearEnd)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.Small),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        items(items, key = { it.id }) { item ->
            val duration = formatMediaDuration(item.duration_ms)
            UfiListRowCard(
                // 显示曲名而不是文件名（取值链见 audioDisplayTitle）
                title = audioDisplayTitle(item),
                subtitle = listOf(audioDisplayArtist(item), item.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                onClick = { onClick(item) },
                leading = {
                    MediaThumb(
                        url = thumbUrl(item),
                        fallback = Icons.Default.MusicNote,
                        width = 44.dp,
                        height = 44.dp
                    )
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
