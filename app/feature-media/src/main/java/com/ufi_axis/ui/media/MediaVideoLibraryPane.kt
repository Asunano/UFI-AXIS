package com.ufi_axis.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.ui.components.common.UfiChoiceSheet
import com.ufi_axis.ui.components.common.UfiErrorBanner
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiListLoadingState
import com.ufi_axis.ui.components.common.UfiListRowCard
import com.ufi_axis.ui.components.common.UfiListToolbar
import com.ufi_axis.ui.components.common.UfiSortAction
import com.ufi_axis.ui.components.common.UfiToolbarAction
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import java.io.File

/**
 * 视频页 · 媒体库（文件夹视图）。
 *
 * 照文件管理器那样一层层走：子目录在前、视频在后，混在同一个列表里
 * （数据来自 core 的 `/api/media/browse`，见 `MediaRoutes`）。
 *
 * ## 三条与"平铺列表"不同的规矩
 * 1. **不分页**：单层就那么多东西，core 侧有条数上限。要"看全部"请去首页那一栏。
 * 2. **返回键先退目录**：在子目录里按返回是"回上一级"，不是退出视频页 ——
 *    这与文件管理器一致，也是用户在深层目录里唯一符合直觉的行为。
 * 3. **空文件夹不显示**：core 已经把该类型文件数为 0 的子目录滤掉了，
 *    所以这里看到的每个文件夹点进去都有东西。
 *
 * ## 下载按钮在这一栏，而不是首页
 * 下载要决定"落到手机的哪个子目录"，而这个答案来自**当前所在的目录层级**
 * （见 [subDirOf]）。首页是平铺的，那里没有"目录"这个上下文。
 */
@Composable
internal fun MediaVideoLibraryPane(
    viewModel: MainViewModel,
    onOpen: (MediaLibraryItem) -> Unit,
    onDownload: (MediaLibraryItem, String) -> Unit,
    onThumbMissing: (suspend (MediaLibraryItem) -> File?)? = null
) {
    val palette = LocalResolvedPalette.current
    val media = viewModel.media
    val state by media.state.collectAsState()
    val tab = state.tab(MEDIA_TYPE_VIDEO)
    val view = state.folderView(MEDIA_TYPE_VIDEO)

    LaunchedEffect(Unit) { media.browse(MEDIA_TYPE_VIDEO) }
    BackHandler(enabled = view.parent != null) { media.browseUp(MEDIA_TYPE_VIDEO) }

    var showSortSheet by remember { mutableStateOf(false) }
    var showDirPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        UfiListToolbar(
            caption = view.path.substringAfterLast('/').ifBlank { "选择目录" },
            leading = if (view.parent != null) {
                {
                    IconButton(onClick = { media.browseUp(MEDIA_TYPE_VIDEO) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "上一级",
                            tint = palette.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                null
            }
        ) {
            UfiSortAction(onClick = { showSortSheet = true })
            UfiToolbarAction(
                icon = Icons.Default.FolderOpen,
                label = "目录",
                onClick = { showDirPicker = true }
            )
            UfiToolbarAction(
                icon = Icons.Default.Refresh,
                label = if (tab.isRescanning) "提交中" else "重扫",
                onClick = { media.rescan(MEDIA_TYPE_VIDEO) },
                enabled = !tab.isRescanning
            )
        }

        view.errorMessage?.let { err ->
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
                view.isEmpty && view.isLoading -> UfiListLoadingState(
                    leadingWidth = MEDIA_VIDEO_THUMB_WIDTH,
                    leadingHeight = MEDIA_VIDEO_THUMB_HEIGHT,
                    leadingCircle = false
                )

                // 配了多个扫描目录、还没选进哪一个：把根目录列出来让用户选
                view.path.isBlank() && view.roots.size > 1 -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = Spacing.Small),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small)
                ) {
                    items(view.roots, key = { it }) { root ->
                        UfiListRowCard(
                            title = root.substringAfterLast('/').ifBlank { root },
                            subtitle = root,
                            onClick = { media.browse(MEDIA_TYPE_VIDEO, root, force = true) },
                            leading = {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = palette.accent,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        )
                    }
                }

                view.isEmpty -> UfiListEmptyState(
                    text = if (view.path.isBlank()) {
                        "还没有可浏览的目录：请先在设置里指定视频的扫描目录"
                    } else {
                        "这个目录里没有视频"
                    },
                    icon = mediaTypeIcon(MEDIA_TYPE_VIDEO)
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = Spacing.Small),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small)
                ) {
                    items(view.folders, key = { it.path }) { folder ->
                        UfiListRowCard(
                            title = folder.name,
                            subtitle = "${folder.count} 个视频",
                            onClick = { media.browse(MEDIA_TYPE_VIDEO, folder.path, force = true) },
                            leading = {
                                // 文件夹封面就用子树里最新那一个视频的缩略图，没有就画文件夹图标
                                val coverId = folder.cover_id
                                if (coverId != null && coverId > 0) {
                                    MediaThumb(
                                        url = media.thumbnailUrl(MEDIA_TYPE_VIDEO, coverId),
                                        fallback = Icons.Default.Folder,
                                        width = MEDIA_VIDEO_THUMB_WIDTH,
                                        height = MEDIA_VIDEO_THUMB_HEIGHT
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = palette.accent,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        )
                    }

                    items(view.items, key = { it.id }) { item ->
                        UfiListRowCard(
                            title = item.name,
                            subtitle = listOf(
                                formatMediaDuration(item.duration_ms),
                                FormatUtils.formatSize(item.size)
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            onClick = { onOpen(item) },
                            leading = {
                                MediaThumb(
                                    url = media.thumbnailUrl(MEDIA_TYPE_VIDEO, item.id),
                                    fallback = Icons.Default.Videocam,
                                    width = MEDIA_VIDEO_THUMB_WIDTH,
                                    height = MEDIA_VIDEO_THUMB_HEIGHT,
                                    onRemoteMissing = onThumbMissing?.let { build -> { build(item) } }
                                )
                            },
                            trailing = {
                                IconButton(
                                    onClick = {
                                        onDownload(item, subDirOf(item.path, view.roots, view.path))
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = "下载到手机",
                                        tint = palette.textSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showSortSheet) {
        UfiChoiceSheet(
            title = "排序",
            options = MEDIA_SORT_OPTIONS,
            selectedValue = mediaSortKey(tab.sort, tab.order),
            onDismiss = { showSortSheet = false },
            onSelect = { key ->
                showSortSheet = false
                val (sort, order) = key.split(':')
                media.setSort(MEDIA_TYPE_VIDEO, sort, order)
                // 文件夹视图也跟着重排（排序是这一类共用的偏好）
                media.browse(MEDIA_TYPE_VIDEO, view.path.takeIf { it.isNotBlank() }, force = true)
            }
        )
    }

    MediaScanDirsDialog(
        visible = showDirPicker,
        typeLabel = mediaTypeLabel(MEDIA_TYPE_VIDEO),
        initial = tab.scanDirs,
        browse = { path -> media.browseDirs(path) },
        onDismiss = { showDirPicker = false },
        onConfirm = { dirs ->
            showDirPicker = false
            media.setScanDirs(MEDIA_TYPE_VIDEO, dirs)
            media.browse(MEDIA_TYPE_VIDEO, null, force = true)
        }
    )
}

/**
 * 下载落点的子目录：文件所在目录**相对扫描根**的那一段。
 *
 * 例：根是 `/storage/emulated/0/Movies`，文件在 `.../Movies/剧集/S01/a.mp4`，
 * 得到 `剧集/S01` —— 手机上就落到 `Download/UFI-AXIS/Movies/剧集/S01/a.mp4`。
 * 全平铺到一个目录里，几十个 `01.mp4` 会互相覆盖，这就是"如果是文件夹就额外加文件夹"的落地。
 *
 * 匹配不到任何根（理论上不会，路径就是从那棵树里列出来的）就回空串，落到 Movies 根下 ——
 * 宁可少一层目录，也不要为了凑层级把整条绝对路径拼进去。
 */
private fun subDirOf(filePath: String, roots: List<String>, currentPath: String): String {
    val parent = filePath.substringBeforeLast('/', missingDelimiterValue = "")
    val root = roots.firstOrNull { parent == it || parent.startsWith("$it/") }
        ?: currentPath.takeIf { parent == it || parent.startsWith("$it/") }
        ?: return ""
    return parent.removePrefix(root).trim('/')
}
