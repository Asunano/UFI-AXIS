package com.ufi_axis.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.ui.components.common.UfiListLoadedCounter
import com.ufi_axis.ui.theme.Spacing

/**
 * 图片页的网格（[MediaImageScreen] 的内容区）。
 *
 * 3 列等宽、格高固定：图片长宽比五花八门，格子高度跟着图变会让整片网格参差不齐。
 * 点开进 [MediaImageViewerScreen]（左右翻页 + 双指缩放，那里才取原图）。
 *
 * 列数与格高是 `internal` 常量：首屏骨架（`UfiGridLoadingState`）要用同样的值占位，
 * 否则数据到位那一帧整片网格会重排。
 */
@Composable
internal fun MediaImageGrid(
    items: List<MediaLibraryItem>,
    total: Int,
    thumbUrl: (MediaLibraryItem) -> String,
    onNearEnd: () -> Unit,
    onClick: (MediaLibraryItem) -> Unit
) {
    val gridState = rememberLazyGridState()
    MediaNearEndEffect(gridState, items.size, onNearEnd)

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(MEDIA_IMAGE_GRID_COLUMNS),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        contentPadding = PaddingValues(vertical = Spacing.Small)
    ) {
        items(items, key = { it.id }) { item ->
            Box(modifier = Modifier.clickable { onClick(item) }) {
                MediaThumb(
                    url = thumbUrl(item),
                    fallback = Icons.Default.Image,
                    width = 0.dp,
                    height = MEDIA_IMAGE_GRID_CELL_HEIGHT,
                    fillWidth = true
                )
            }
        }
        if (items.size < total) {
            item(span = { GridItemSpan(MEDIA_IMAGE_GRID_COLUMNS) }) {
                UfiListLoadedCounter(loaded = items.size, total = total)
            }
        }
    }
}

internal const val MEDIA_IMAGE_GRID_COLUMNS = 3
internal val MEDIA_IMAGE_GRID_CELL_HEIGHT = 108.dp
