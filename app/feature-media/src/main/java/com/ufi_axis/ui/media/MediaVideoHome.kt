package com.ufi_axis.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.ui.components.common.UfiGridLoadingState
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiListLoadedCounter
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.state.MediaTabState
import java.io.File

/**
 * 视频页 · 首页（海报墙）。
 *
 * ## 只有视频，不显示文件夹
 * 这一栏回答的是"想看点什么"，所以按当前排序把整库平铺；"它在哪个目录"是媒体库那一栏的事。
 *
 * ## 为什么最终用 16:9 而不是竖版海报
 * 竖版（2:3）要靠裁切一张 16:9 的抽帧图来凑，左右砍掉一半、还要放大 → 又糊又丢内容。
 * 现在的做法是**保持 16:9、把格子做大做松**：两列 + [POSTER_SPACING] 的间距，
 * 图下一行文件名。信息密度低一点，但每一格都看得清画面 —— 这才是海报墙的目的。
 *
 * ## 顶部"最近播放"
 * 横向一条，最多 6 条（[AppPreferences.mediaRecentPlays]，本地记录）。它是**整片网格的第一项**
 * （占满一行的 span），而不是外面套一层 Column —— 后者会让它常驻屏幕、把海报墙压成一条窄缝。
 */
@Composable
internal fun MediaVideoHome(
    tab: MediaTabState,
    thumbUrl: (MediaLibraryItem) -> String,
    onNearEnd: () -> Unit,
    onOpen: (MediaLibraryItem) -> Unit,
    onThumbMissing: (suspend (MediaLibraryItem) -> File?)? = null,
    onOpenRecent: (path: String, name: String, id: Long) -> Unit
) {
    val palette = LocalResolvedPalette.current
    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    MediaNearEndEffect(gridState, tab.items.size, onNearEnd)

    // 最近播放：每次进这一栏读一次（它由播放页写入，不走 ViewModel 状态）
    var recent by remember { mutableStateOf(emptyList<Triple<String, String, Long>>()) }
    LaunchedEffect(Unit) {
        recent = runCatching { AppPreferences(context).mediaRecentPlays() }.getOrDefault(emptyList())
    }

    if (tab.isEmpty) {
        if (tab.isLoading) {
            UfiGridLoadingState(
                columns = POSTER_COLUMNS,
                cellHeight = POSTER_SKELETON_CELL_HEIGHT
            )
        } else {
            UfiListEmptyState(
                text = if (tab.scanDirs.isEmpty()) "媒体库里没有视频" else "所选目录下没有视频",
                icon = mediaTypeIcon(MEDIA_TYPE_VIDEO)
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(POSTER_COLUMNS),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.Medium,
            end = Spacing.Medium,
            top = Spacing.Small,
            bottom = Spacing.Large
        ),
        horizontalArrangement = Arrangement.spacedBy(POSTER_SPACING),
        verticalArrangement = Arrangement.spacedBy(POSTER_SPACING)
    ) {
        if (recent.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(
                        "最近播放",
                        style = UfiTextStyles.cardTitle,
                        color = palette.accent
                    )
                    Spacer(Modifier.height(Spacing.Small))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
                    ) {
                        items(recent, key = { it.first }) { (path, name, id) ->
                            RecentCard(
                                name = name,
                                thumbUrl = thumbUrl(MediaLibraryItem(id = id, path = path, name = name)),
                                onClick = { onOpenRecent(path, name, id) }
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.Small))
                    Text(
                        "全部视频",
                        style = UfiTextStyles.cardTitle,
                        color = palette.accent
                    )
                }
            }
        }

        items(tab.items, key = { it.id }) { item ->
            PosterCell(
                item = item,
                thumbUrl = thumbUrl(item),
                onClick = { onOpen(item) },
                onThumbMissing = onThumbMissing?.let { build -> { build(item) } }
            )
        }

        if (tab.items.size < tab.total) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                UfiListLoadedCounter(loaded = tab.items.size, total = tab.total)
            }
        }
    }
}

/**
 * 海报墙的一格：16:9 画面 + 文件名（最多两行）+ 时长。
 *
 * 不用 [com.ufi_axis.ui.components.common.UfiListRowCard]：那是"横向一行"的形态，
 * 这里要的是"图在上、字在下"。共用的只有缩略图容器（[MediaThumb]）。
 */
@Composable
private fun PosterCell(
    item: MediaLibraryItem,
    thumbUrl: String,
    onClick: () -> Unit,
    onThumbMissing: (suspend () -> File?)? = null
) {
    val palette = LocalResolvedPalette.current
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        MediaThumb(
            url = thumbUrl,
            fallback = Icons.Default.Videocam,
            aspectRatio = POSTER_ASPECT,
            onRemoteMissing = onThumbMissing
        )
        Spacer(Modifier.height(Spacing.Small))
        Text(
            item.name,
            style = UfiTextStyles.body,
            color = palette.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val duration = formatMediaDuration(item.duration_ms)
        if (duration.isNotBlank()) {
            Text(duration, style = UfiTextStyles.note, color = palette.textSecondary)
        }
    }
}

/** 最近播放的一张卡：固定宽度，横向滚动。 */
@Composable
private fun RecentCard(
    name: String,
    thumbUrl: String,
    onClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = Modifier
            .width(RECENT_CARD_WIDTH)
            .clickable(onClick = onClick)
    ) {
        MediaThumb(
            url = thumbUrl,
            fallback = Icons.Default.Videocam,
            aspectRatio = POSTER_ASPECT
        )
        Spacer(Modifier.height(Spacing.Small))
        Text(
            name,
            style = UfiTextStyles.note,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 海报墙：两列。 */
private const val POSTER_COLUMNS = 2

/** 画面比例：抽帧图本来就是 16:9，按它排版不裁不糊。 */
private const val POSTER_ASPECT = 16f / 9f

/** 格间距：比列表的 8dp 大一档，"不要那么挤"是用户的原话。 */
private val POSTER_SPACING = 16.dp

/** 首屏骨架的格高：约等于一格的真实高度（图 + 两行字），数据到位时不重排。 */
private val POSTER_SKELETON_CELL_HEIGHT = 148.dp

/** 最近播放卡宽：一屏能露出两张多一点，暗示"可以横滑"。 */
private val RECENT_CARD_WIDTH = 160.dp
