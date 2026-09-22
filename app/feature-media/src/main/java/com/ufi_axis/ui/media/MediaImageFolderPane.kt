package com.ufi_axis.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.model.MEDIA_TYPE_IMAGE
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.ui.components.common.UfiErrorBanner
import com.ufi_axis.ui.components.common.UfiGridLoadingState
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiListRowCard
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 图片页 · 文件夹那一栏（2026-09-20）。
 *
 * 数据源与视频页的媒体库栏**是同一条**：core 的 `GET /api/media/browse?type=`
 * 本来就按 type 取（`Kind.of` 收 video/audio/image，见 `MediaRoutes`），
 * 客户端侧共用 [com.ufi_axis.viewmodel.module.MediaModule.browse] 与
 * `MediaLibraryState.browse`（按类型各存一份）—— 这一栏没有新增任何取数逻辑。
 *
 * ## 为什么不直接复用 [MediaVideoLibraryPane]
 * 那个组件里写死的三样东西对图片都不成立：行内容是「16:9 缩略图 + 时长/大小」、
 * 每行尾部一颗下载按钮（落点是按视频目录镜像算的）、以及它自带一条
 * 排序 + 目录 + 重扫工具条（图片页那条已由页壳 [MediaLibraryPage] 画了，再来一条就是两条）。
 * 把它参数化成"视频也能图片也能"要先塞进四五个开关。共用的部分 —— 浏览状态、
 * [MediaThumb]、文件夹行 [UfiListRowCard]、骨架/空态 —— 全部用的是现成件，没有第二套实现。
 *
 * ## 与时间轴那一栏的分工
 * 时间轴回答"最近拍了什么"，文件夹回答"我知道它在哪个相册目录里"。
 *
 * @param gridState 滚动位置由调用方（[MediaImageScreen]）持有：切 Tab 换的是一棵子树，
 *   建在本函数里的状态会随子树销毁，切回来就回到顶部。
 */
@Composable
internal fun MediaImageFolderPane(
    viewModel: MainViewModel,
    onOpen: (MediaLibraryItem) -> Unit,
    gridState: LazyGridState
) {
    val palette = LocalResolvedPalette.current
    val media = viewModel.media
    val state by media.state.collectAsState()
    val view = state.folderView(MEDIA_TYPE_IMAGE)

    // 切到本栏才去列目录（browse 自带 loadedOnce 守卫，来回切不重复打网络）
    LaunchedEffect(Unit) { media.browse(MEDIA_TYPE_IMAGE) }
    // 在子目录里按返回是"回上一级"，不是退出图片页 —— 与文件管理器、视频页一致
    BackHandler(enabled = view.parent != null) { media.browseUp(MEDIA_TYPE_IMAGE) }

    Column(modifier = Modifier.fillMaxSize()) {
        /*
         * 当前目录 + 上一级。刻意不用 [com.ufi_axis.ui.components.common.UfiListToolbar]：
         * 页壳已经在上面画了一条 48dp 工具条（范围 caption + 排序 + 重扫），
         * 再叠一条同高的会把内容区压掉近百 dp。这里只要一行"我在哪、怎么回去"。
         */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MEDIA_IMAGE_FOLDER_BAR_HEIGHT),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (view.parent != null) {
                IconButton(onClick = { media.browseUp(MEDIA_TYPE_IMAGE) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "上一级",
                        tint = palette.textSecondary,
                        modifier = Modifier.size(Spacing.IconSizeSmall)
                    )
                }
            }
            Text(
                text = view.path.substringAfterLast('/').ifBlank { "选择目录" },
                style = UfiTextStyles.note,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.Small)
            )
        }

        view.errorMessage?.let { err ->
            UfiErrorBanner(message = err)
            Spacer(Modifier.height(Spacing.Small))
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                // 骨架判据看"还没拉到过结果"（与页壳同一口径）：切到本栏的第一帧
                // browse 还没由 LaunchedEffect 发出去，用 isLoading 判会先闪一帧空态
                !view.loadedOnce -> UfiGridLoadingState(
                    columns = MEDIA_IMAGE_GRID_COLUMNS,
                    cellHeight = MEDIA_IMAGE_GRID_CELL_HEIGHT
                )

                // 配了多个扫描目录、还没选进哪一个：把根目录列出来让用户选
                view.path.isBlank() && view.roots.size > 1 -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(MEDIA_IMAGE_GRID_COLUMNS),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                    contentPadding = PaddingValues(vertical = Spacing.Small)
                ) {
                    items(
                        view.roots,
                        key = { it },
                        span = { GridItemSpan(maxLineSpan) }
                    ) { root ->
                        UfiListRowCard(
                            title = root.substringAfterLast('/').ifBlank { root },
                            subtitle = root,
                            onClick = { media.browse(MEDIA_TYPE_IMAGE, root, force = true) },
                            leading = { FolderIcon() }
                        )
                    }
                }

                view.isEmpty -> UfiListEmptyState(
                    text = if (view.path.isBlank()) {
                        "还没有可浏览的目录：请先在设置里指定图片的扫描目录"
                    } else {
                        "这个目录里没有图片"
                    },
                    icon = mediaTypeIcon(MEDIA_TYPE_IMAGE)
                )

                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(MEDIA_IMAGE_GRID_COLUMNS),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                    contentPadding = PaddingValues(vertical = Spacing.Small)
                ) {
                    // 子目录占满整行：它是"换一层"的导航，混在三列格子里点起来像一张缩略图
                    items(
                        view.folders,
                        key = { it.path },
                        span = { GridItemSpan(maxLineSpan) }
                    ) { folder ->
                        UfiListRowCard(
                            title = folder.name,
                            subtitle = "${folder.count} 张图片",
                            onClick = { media.browse(MEDIA_TYPE_IMAGE, folder.path, force = true) },
                            leading = {
                                // 封面用子树里最新那一张；core 给 0/null 表示没有可用封面
                                val coverId = folder.cover_id
                                if (coverId != null && coverId > 0) {
                                    MediaThumb(
                                        url = media.thumbnailUrl(MEDIA_TYPE_IMAGE, coverId),
                                        fallback = Icons.Default.Folder,
                                        width = MEDIA_IMAGE_FOLDER_COVER_SIZE,
                                        height = MEDIA_IMAGE_FOLDER_COVER_SIZE
                                    )
                                } else {
                                    FolderIcon()
                                }
                            }
                        )
                    }

                    // 本层的图片：与时间轴那一栏同样的格子形状（同一组列数/格高常量）
                    items(view.items, key = { it.id }) { item ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(item) }
                        ) {
                            MediaThumb(
                                url = media.thumbnailUrl(MEDIA_TYPE_IMAGE, item.id),
                                fallback = mediaTypeIcon(MEDIA_TYPE_IMAGE),
                                height = MEDIA_IMAGE_GRID_CELL_HEIGHT,
                                fillWidth = true
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 没有封面时的文件夹图标（根目录行与子目录行两处用到，形状要一致）。 */
@Composable
private fun FolderIcon() {
    val palette = LocalResolvedPalette.current
    Icon(
        Icons.Default.Folder,
        contentDescription = null,
        tint = palette.accent,
        modifier = Modifier.size(Spacing.IconCanvas)
    )
}

/** 目录行那一条的高度：比工具条（48dp）矮一档，它只承载一行说明 + 一颗返回键。 */
private val MEDIA_IMAGE_FOLDER_BAR_HEIGHT = 40.dp

/** 文件夹行的封面尺寸：方形，与"图片"这件事的形状一致（视频那边是 16:9）。 */
private val MEDIA_IMAGE_FOLDER_COVER_SIZE = 48.dp
