package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiChoiceSheet
import com.ufi_axis.ui.components.common.UfiErrorBanner
import com.ufi_axis.ui.components.common.UfiGridLoadingState
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiListLoadingState
import com.ufi_axis.ui.components.common.UfiListToolbar
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSortAction
import com.ufi_axis.ui.components.common.UfiToolbarAction
import com.ufi_axis.ui.components.common.UfiViewModeAction
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.MEDIA_ORDER_ASC
import com.ufi_axis.viewmodel.state.MEDIA_ORDER_DESC
import com.ufi_axis.viewmodel.state.MEDIA_SORT_DATE
import com.ufi_axis.viewmodel.state.MEDIA_SORT_NAME
import com.ufi_axis.viewmodel.state.MEDIA_SORT_SIZE
import com.ufi_axis.viewmodel.state.MediaTabState

/**
 * 音乐 / 图片两个页面共用的**页壳**。
 *
 * 2026-09-16 第二版：结构对齐文件管理器 —— 顶部一条 48dp 工具条（[UfiListToolbar]）、
 * 内容区三态（骨架 / 空 / 列表），三态与工具条外壳都是公共件，本文件只做**编排**。
 *
 * 视频页**不再用这个壳**（同日第三版）：它变成了"首页（海报墙）+ 媒体库（文件夹）"两栏、
 * 右上角还有独立设置页，与"单一列表 + 一条工具条"的形态已经不是一回事。硬把两栏塞进来
 * 只会让这个壳长出一堆只有视频用得上的参数。见 `MediaVideoScreen.kt`。
 *
 * ## 分层（照 `filemanager/` 那套）
 * · 本文件 = Root：状态收集、加载时机、工具条动作分派、弹窗开关；
 * · `MediaAudioList` / `MediaImageGrid` = 无状态展示件；
 * · `MediaScanDirsDialog`（独立文件）= 无状态弹窗，`visible + onConfirm/onDismiss`。
 *
 * ## 为什么"扫描范围"从独立一行改进工具条
 * 原来它是顶部一整行（标签 + 路径 + 两颗按钮），在小屏上白占 64dp，而它表达的是
 * 「这一屏在看什么范围」—— 正是工具条 caption 的语义。现在：caption 显示范围与条数，
 * 目录与重扫收成两颗 [UfiToolbarAction]，与文件管理器的工具条同形。
 *
 * ## 首屏骨架的红线（与文件管理器一致，别改）
 * 只有「正在加载 **且** 一条数据都没有」时显示骨架；已有列表时的刷新不换骨架，
 * 否则每次刷新都把已渲染内容整块替换成灰块，观感就是闪一下。
 * 骨架的形状还要跟着视图模式走：列表态用行骨架、网格态用格子骨架。
 *
 * ## 两条不许破的规矩（从媒体中心继承）
 * 1. **未授权就明说**：core 按 `READ_MEDIA_VIDEO/AUDIO/IMAGES` 三类分别授权，
 *    某类没授权时这一页显示引导，**不显示空列表** —— 空列表等于告诉用户"设备里没有视频"。
 * 2. **三类各存一份数据**（`MediaLibraryState.tabs`）：从视频页去音乐页再回来，视频列表还在。
 *
 * @param listSkeletonLeadingWidth 列表骨架前置槽宽（要与真实行的缩略图同宽）
 * @param listSkeletonLeadingHeight 列表骨架前置槽高
 * @param showViewToggle 是否显示"列表 ⇄ 网格"按钮。**只有真的两种画法都实现了才传 true** ——
 *   音乐页只有列表、图片页只有网格，给它们摆一颗按不动的按钮就是假按钮。
 * @param gridColumns 网格态列数（骨架要与真实网格同列数）
 * @param gridCellHeight 网格态格高
 * @param content 内容区：拿到本类型的状态，自己决定画列表还是网格
 */
@Composable
internal fun MediaLibraryPage(
    title: String,
    type: String,
    viewModel: MainViewModel,
    navController: NavHostController,
    listSkeletonLeadingWidth: Dp = 40.dp,
    listSkeletonLeadingHeight: Dp = 40.dp,
    showViewToggle: Boolean = false,
    gridColumns: Int = 3,
    gridCellHeight: Dp = 108.dp,
    content: @Composable (MediaTabState) -> Unit
) {
    val palette = LocalResolvedPalette.current
    val media = viewModel.media
    val state by media.state.collectAsState()
    val tab = state.tab(type)

    // 进页先拿授权状态（决定是列表还是引导），再拉第一页
    LaunchedEffect(Unit) { media.loadStatus() }
    LaunchedEffect(type) { media.loadFirstPage(type) }

    var showDirPicker by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }

    UfiScreenScaffold(title = title, navController = navController, showBack = true) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            UfiListToolbar(caption = mediaScopeCaption(tab)) {
                if (showViewToggle) {
                    UfiViewModeAction(
                        grid = tab.gridView,
                        onToggle = { media.setGridView(type, !tab.gridView) }
                    )
                }
                UfiSortAction(onClick = { showSortSheet = true })
                UfiToolbarAction(
                    icon = Icons.Default.FolderOpen,
                    label = "目录",
                    onClick = { showDirPicker = true }
                )
                UfiToolbarAction(
                    icon = Icons.Default.Refresh,
                    label = if (tab.isRescanning) "提交中" else "重扫",
                    onClick = { media.rescan(type) },
                    enabled = !tab.isRescanning
                )
            }

            (tab.errorMessage ?: state.errorMessage)?.let { err ->
                Spacer(Modifier.height(Spacing.Small))
                Box(modifier = Modifier.padding(horizontal = Spacing.Medium)) {
                    UfiErrorBanner(message = err)
                }
            }
            tab.message?.let { msg ->
                Spacer(Modifier.height(Spacing.Small))
                Text(
                    msg,
                    style = UfiTextStyles.note,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.Medium)
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.Medium)
            ) {
                when {
                    !tab.granted -> UfiListEmptyState(
                        text = "设备端还没有授予「${mediaTypeLabel(type)}」的读取权限。\n" +
                            "请在设备上授予媒体读取权限（或「所有文件访问」），然后回来重新检查。",
                        icon = mediaTypeIcon(type),
                        action = {
                            UfiButton(
                                text = "重新检查",
                                onClick = {
                                    media.loadStatus()
                                    media.loadFirstPage(type, force = true)
                                },
                                variant = UfiButtonVariant.Subtle,
                                size = UfiButtonSize.Small
                            )
                        }
                    )

                    tab.isEmpty && tab.isLoading -> if (tab.gridView) {
                        UfiGridLoadingState(
                            columns = gridColumns,
                            cellHeight = gridCellHeight
                        )
                    } else {
                        UfiListLoadingState(
                            leadingWidth = listSkeletonLeadingWidth,
                            leadingHeight = listSkeletonLeadingHeight,
                            leadingCircle = false
                        )
                    }

                    tab.isEmpty -> UfiListEmptyState(
                        text = if (tab.scanDirs.isEmpty()) {
                            "媒体库里没有${mediaTypeLabel(type)}"
                        } else {
                            "所选目录下没有${mediaTypeLabel(type)}"
                        },
                        icon = mediaTypeIcon(type),
                        action = if (tab.scanDirs.isEmpty()) {
                            null
                        } else {
                            {
                                UfiButton(
                                    text = "改扫描目录",
                                    onClick = { showDirPicker = true },
                                    variant = UfiButtonVariant.Subtle,
                                    size = UfiButtonSize.Small
                                )
                            }
                        }
                    )

                    else -> content(tab)
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
                media.setSort(type, sort, order)
            }
        )
    }

    MediaScanDirsDialog(
        visible = showDirPicker,
        typeLabel = mediaTypeLabel(type),
        initial = tab.scanDirs,
        browse = { path -> media.browseDirs(path) },
        onDismiss = { showDirPicker = false },
        onConfirm = { dirs ->
            showDirPicker = false
            media.setScanDirs(type, dirs)
        }
    )
}

/**
 * 工具条左侧那句话：范围 + 已加载/总数。
 *
 * 「重新扫描」的语义是"请系统重新收录"，收录是异步的，所以这里只报**当前查得到多少**，
 * 不写"扫描完成"之类的话。
 */
private fun mediaScopeCaption(tab: MediaTabState): String {
    val scope = if (tab.scanDirs.isEmpty()) {
        "整个媒体库"
    } else if (tab.scanDirs.size == 1) {
        tab.scanDirs.first().substringAfterLast('/')
    } else {
        "${tab.scanDirs.size} 个目录"
    }
    if (tab.total <= 0) return scope
    return "$scope · ${tab.items.size}/${tab.total}"
}

/** 排序面板的选项。字段与方向压成一个 key（`date:desc`），免得开两个面板。 */
internal val MEDIA_SORT_OPTIONS: List<Pair<String, String>> = listOf(
    "$MEDIA_SORT_DATE:$MEDIA_ORDER_DESC" to "时间（新→旧）",
    "$MEDIA_SORT_DATE:$MEDIA_ORDER_ASC" to "时间（旧→新）",
    "$MEDIA_SORT_NAME:$MEDIA_ORDER_ASC" to "名称（A→Z）",
    "$MEDIA_SORT_NAME:$MEDIA_ORDER_DESC" to "名称（Z→A）",
    "$MEDIA_SORT_SIZE:$MEDIA_ORDER_DESC" to "大小（大→小）",
    "$MEDIA_SORT_SIZE:$MEDIA_ORDER_ASC" to "大小（小→大）"
)

internal fun mediaSortKey(sort: String, order: String): String = "$sort:$order"
