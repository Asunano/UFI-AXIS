package com.ufi_axis.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 音乐页（工具 → 音乐）。2026-09-16 从"媒体中心"三栏之一拆成独立页。
 *
 * 页壳在 [MediaLibraryPage]；本文件只决定列表形态（[MediaAudioList]）与点开去哪
 * （[MediaAudioPlayerScreen]，播放本身跑在 [UfiAudioPlaybackService] 里）。
 */
@Composable
fun MediaAudioScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val media = viewModel.media
    MediaLibraryPage(
        title = "音乐",
        type = MEDIA_TYPE_AUDIO,
        viewModel = viewModel,
        navController = navController,
        // 行里的封面是 44×44 方形（不是圆形头像），骨架要占成同样的形状
        listSkeletonLeadingWidth = 44.dp,
        listSkeletonLeadingHeight = 44.dp
        // showViewToggle 保持 false：音乐页只有列表一种画法，摆一颗按不动的视图按钮就是假按钮
    ) { tab ->
        MediaAudioList(
            items = tab.items,
            total = tab.total,
            thumbUrl = { media.thumbnailUrl(MEDIA_TYPE_AUDIO, it.id) },
            onNearEnd = { media.loadMore(MEDIA_TYPE_AUDIO) },
            onClick = { navController.navigate(mediaRouteOf("media/audio", it.path)) }
        )
    }
}
