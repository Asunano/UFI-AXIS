package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.MEDIA_TYPE_IMAGE
import com.ufi_axis.ui.components.common.UfiScrollableTabRow
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.navigation.ufiNavigateOnce
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.MEDIA_SORT_DATE

/**
 * 图片页（工具 → 图片）。2026-09-16 从"媒体中心"三栏之一拆成独立页。
 *
 * 页壳在 [MediaLibraryPage]；本文件决定分几栏、每栏画什么、点开去哪
 * （[MediaImageViewerScreen]，那里才取原图）。
 *
 * ## 两栏（2026-09-20 新增，版式照音乐页）
 * · **时间轴** [MediaImageGrid]：整库按时间倒序平铺，网格里按天插段头（今天 / 昨天 / 9月18日）。
 *   这是"最近拍了什么"的入口。
 * · **文件夹** [MediaImageFolderPane]：一层层走扫描目录，回答"它在哪个相册目录里"。
 *
 * Tab 放在页壳的 `topContent` 槽（工具条正下方）而不是底部：它是"我在看哪一类"的导航，
 * 与工具条（范围 caption + 排序/重扫）连成一块"这一屏在看什么"的控制区。
 *
 * ## 排序默认值：**本来就是**时间倒序，这一轮没有改它
 * [com.ufi_axis.viewmodel.state.MediaTabState] 的缺省是 `date` + `desc`，
 * `AppPreferences.mediaSort/mediaOrder` 的缺省也是这两个值，所以图片页第一次打开就是
 * 时间倒序。段头因此只在 `sort == date` 时画 —— 用户切成按名称/大小排序后相邻两项的
 * 日期是乱的，插段头会得到一串只含一项的段。
 *
 * ## 首屏骨架的形状也不需要改
 * 骨架按 `tab.gridView` 选行 / 格两种形状，而图片的 `gridView` 缺省就是 true
 * （`AppPreferences.mediaGridView` 默认值 = `type == "image"`，`MediaModule.initialState`
 * 用的就是它），所以图片页首屏本来就是三列格子骨架，不需要额外传参或调 `setGridView`。
 *
 * ## 两栏的滚动位置都提到切栏之上
 * 内容区是 `if (pane == …)` 换子树，建在子树里的 `rememberLazyGridState()` 会随子树销毁 ——
 * 音乐页那一轮踩过：切回来回到顶部，且新页首帧要重新测量。
 */
@Composable
fun MediaImageScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val palette = LocalResolvedPalette.current
    val media = viewModel.media

    var pane by rememberSaveable { mutableIntStateOf(PANE_TIMELINE) }

    // 两栏各一份滚动位置：都是网格，但内容与层级不同，共用一份会让"在时间轴滚到一半再切
    // 文件夹"时文件夹停在同一个偏移上
    val timelineGridState = rememberLazyGridState()
    val folderGridState = rememberLazyGridState()

    val onOpen: (String) -> Unit = { path ->
        // 网格里手快连点两格会叠出两个查看页（返回要按两次），守卫理由见 ufiNavigateOnce
        navController.ufiNavigateOnce(mediaRouteOf("media/image", path))
    }

    MediaLibraryPage(
        title = "图片",
        type = MEDIA_TYPE_IMAGE,
        viewModel = viewModel,
        navController = navController,
        // 图片页只有网格一种画法：骨架用格子形状，视图切换按钮不摆（摆了也按不动）
        gridColumns = MEDIA_IMAGE_GRID_COLUMNS,
        gridCellHeight = MEDIA_IMAGE_GRID_CELL_HEIGHT,
        // 目录入口只留设置页一处（与音乐页一致）：两处都能改会出现"改完这边那边不知道"的错觉
        showDirAction = false,
        actions = {
            IconButton(onClick = { navController.navigate(Routes.MEDIA_IMAGE_SETTINGS) }) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "图片设置",
                    tint = palette.textSecondary
                )
            }
        },
        topContent = {
            // 左右内边距要自己写：本槽在页壳的水平 padding 之外，不补会比下面的网格宽出两边
            UfiScrollableTabRow(
                selectedTabIndex = pane,
                onTabSelected = { pane = it },
                tabs = MEDIA_IMAGE_PANES,
                modifier = Modifier.padding(
                    horizontal = Spacing.Medium,
                    vertical = Spacing.Small
                )
            )
        }
    ) { tab ->
        if (pane == PANE_TIMELINE) {
            MediaImageGrid(
                items = tab.items,
                total = tab.total,
                thumbUrl = { media.thumbnailUrl(MEDIA_TYPE_IMAGE, it.id) },
                onNearEnd = { media.loadMore(MEDIA_TYPE_IMAGE) },
                onClick = { onOpen(it.path) },
                gridState = timelineGridState,
                // 只有按时间排序时段头才有意义（理由见本文件 KDoc）
                groupByDate = tab.sort == MEDIA_SORT_DATE
            )
        } else {
            MediaImageFolderPane(
                viewModel = viewModel,
                onOpen = { onOpen(it.path) },
                gridState = folderGridState
            )
        }
    }
}

/** "时间轴"那一栏的下标；另一栏是文件夹。 */
private const val PANE_TIMELINE = 0

/** 两栏切换的标签，顺序即下标（[PANE_TIMELINE] 对应第 0 个）。 */
private val MEDIA_IMAGE_PANES: List<String> = listOf("时间轴", "文件夹")
