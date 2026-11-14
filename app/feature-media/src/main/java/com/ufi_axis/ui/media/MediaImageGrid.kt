package com.ufi_axis.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.ui.components.common.UfiListLoadedCounter
import com.ufi_axis.ui.theme.Spacing

/**
 * 图片页 · 时间轴那一栏的网格（[MediaImageScreen] 的内容区）。
 *
 * 3 列等宽、格高固定：图片长宽比五花八门，格子高度跟着图变会让整片网格参差不齐。
 * 点开进 [MediaImageViewerScreen]（左右翻页 + 双指缩放，那里才取原图）。
 *
 * 列数与格高是 `internal` 常量：首屏骨架（`UfiGridLoadingState`）要用同样的值占位，
 * 否则数据到位那一帧整片网格会重排。
 *
 * ## 按天分段（2026-09-20）
 * [groupByDate] = true 时在网格里插入日期段头（今天 / 昨天 / 9月18日 …）。
 * 段头用 `item(span = maxLineSpan)` 画，**不是** `stickyHeader` ——
 * `LazyVerticalGrid` 没有那个 API（只有 `LazyColumn` 有），自己拿 `graphicsLayer` 仿一个
 * 吸顶效果要接管整片网格的偏移量，代价远超收益。
 *
 * 分段结构用 `remember(items)` 派生：`items` 只在首屏与翻页时换实例，
 * 所以滚动过程中的重组不会重算整份分组。
 *
 * @param groupByDate 是否按天插段头。调用方只在**按时间排序**时传 true ——
 *   按名称/大小排序时相邻两项的日期是乱的，插段头会得到一串只含一项的段。
 * @param gridState 滚动位置由调用方持有（切 Tab 换子树，建在这里会随子树销毁）。
 */
@Composable
internal fun MediaImageGrid(
    items: List<MediaLibraryItem>,
    total: Int,
    thumbUrl: (MediaLibraryItem) -> String,
    onNearEnd: () -> Unit,
    onClick: (MediaLibraryItem) -> Unit,
    gridState: LazyGridState,
    groupByDate: Boolean = false
) {
    MediaNearEndEffect(gridState, items.size, onNearEnd)

    // 分组只在 items 换实例时重算；"今天"的判定按进入这一屏时的当天算
    // （跨午夜时段头文案会滞后一天，代价是每分钟重算一次整份分组，不值得）
    val sections = remember(items, groupByDate) {
        if (groupByDate) groupMediaByDay(items) else emptyList()
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(MEDIA_IMAGE_GRID_COLUMNS),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        contentPadding = PaddingValues(vertical = Spacing.Small)
    ) {
        if (sections.isEmpty()) {
            items(items, key = { it.id }) { item ->
                ImageCell(thumbUrl = thumbUrl(item), onClick = { onClick(item) })
            }
        } else {
            sections.forEach { section ->
                // 段头的 key 用 epochDay 而不是文案：文案会随"今天/昨天"变，撞键会让整段错位
                item(
                    key = "section-${section.key}",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    MediaDateSectionHeader(section.label)
                }
                items(section.items, key = { it.id }) { item ->
                    ImageCell(thumbUrl = thumbUrl(item), onClick = { onClick(item) })
                }
            }
        }

        if (items.size < total) {
            item(span = { GridItemSpan(MEDIA_IMAGE_GRID_COLUMNS) }) {
                UfiListLoadedCounter(loaded = items.size, total = total)
            }
        }
    }
}

/** 一格：只有缩略图（文件名在查看器里才有意义，网格里挤不下也没人读）。 */
@Composable
private fun ImageCell(thumbUrl: String, onClick: () -> Unit) {
    Box(modifier = Modifier.clickable(onClick = onClick)) {
        MediaThumb(
            url = thumbUrl,
            fallback = Icons.Default.Image,
            width = 0.dp,
            height = MEDIA_IMAGE_GRID_CELL_HEIGHT,
            fillWidth = true
        )
    }
}

internal const val MEDIA_IMAGE_GRID_COLUMNS = 3
internal val MEDIA_IMAGE_GRID_CELL_HEIGHT = 108.dp
