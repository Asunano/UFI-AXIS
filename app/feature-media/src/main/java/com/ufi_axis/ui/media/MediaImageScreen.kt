package com.ufi_axis.ui.media

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.MEDIA_TYPE_IMAGE
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 图片页（工具 → 图片）。2026-09-16 从"媒体中心"三栏之一拆成独立页。
 *
 * 页壳在 [MediaLibraryPage]；本文件只决定网格形态（[MediaImageGrid]）与点开去哪
 * （[MediaImageViewerScreen]，那里才取原图）。
 */
@Composable
fun MediaImageScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val media = viewModel.media
    MediaLibraryPage(
        title = "图片",
        type = MEDIA_TYPE_IMAGE,
        viewModel = viewModel,
        navController = navController,
        // 图片页只有网格一种画法：骨架用格子形状，视图切换按钮不摆（摆了也按不动）
        gridColumns = MEDIA_IMAGE_GRID_COLUMNS,
        gridCellHeight = MEDIA_IMAGE_GRID_CELL_HEIGHT
    ) { tab ->
        MediaImageGrid(
            items = tab.items,
            total = tab.total,
            thumbUrl = { media.thumbnailUrl(MEDIA_TYPE_IMAGE, it.id) },
            onNearEnd = { media.loadMore(MEDIA_TYPE_IMAGE) },
            onClick = { navController.navigate(mediaRouteOf("media/image", it.path)) }
        )
    }
}
