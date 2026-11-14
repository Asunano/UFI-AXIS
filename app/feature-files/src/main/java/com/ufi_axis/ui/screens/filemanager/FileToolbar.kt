package com.ufi_axis.ui.screens.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.ufi_axis.ui.components.common.UfiListToolbar
import com.ufi_axis.ui.components.common.UfiPopupMenu
import com.ufi_axis.ui.components.common.UfiPopupOption
import com.ufi_axis.ui.components.common.UfiSortAction
import com.ufi_axis.ui.components.common.UfiToolbarAction
import com.ufi_axis.ui.components.common.UfiViewModeAction
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.state.FileViewMode

/**
 * 文件管理器工具栏（无状态展示组件，T5）。
 *
 * 横向排列一组图标按钮：视图切换、排序、搜索、粘贴（条件显示）、更多。
 * 图标着色为 [com.ufi_axis.ui.theme.LocalResolvedPalette.textSecondary]，
 * 并均设置 contentDescription 以保证无障碍。
 *
 * 2026-09-16：外壳（48dp 行高、右对齐动作、底部 1dp 分隔线）与「视图 / 排序」两颗按钮
 * 转发公共层（[UfiListToolbar] / [UfiViewModeAction] / [UfiSortAction]）——媒体库拆页后
 * 也要同一条工具条，形状与图标语义只该有一份。本文件保留的是**文件管理器专属**的部分：
 * 返回上一级、粘贴板、更多菜单。
 *
 * 组件完全无状态：所有行为通过回调上抛。
 *
 * @param viewMode 当前视图模式（列表/网格）
 * @param canBack 是否显示返回按钮（根目录为 false）
 * @param onBack 点击返回上一级
 * @param onToggleViewMode 点击切换视图模式
 * @param onSort 点击排序
 * @param onSearch 点击搜索
 * @param showSearch 是否显示搜索按钮。2026-09-21 新增（默认 true，本地与既有调用点不变）：
 *        FTP / WebDAV 没有"递归查找"这种原语，core 的能力清单里没有 `SEARCH` 时这颗按钮
 *        必须整个撤掉 —— 留着点了只会回空结果，用户会读成"这个文件不在这儿"。
 * @param canPaste 是否显示粘贴按钮（剪贴板非空时）
 * @param onPaste 点击粘贴
 * @param moreMenuOptions 「更多」浮出菜单的选项（锚定在「更多」按钮处）
 * @param modifier 修饰符
 * @param onPushTasks 「推送任务」入口。**只在外部存储源里传非 null**（2026-09-21）：
 *        上传到远端是两段链路，第二段（core → 远端）的状态必须能从**当前这个源**的界面直接进，
 *        而不是回到别处翻一个全局列表。传 null 时这颗按钮不出现（本地目录没有推送这回事）。
 * @param pushTaskBadge 按钮上要显示的角标数字（在途 + 失败）。0 = 不显示角标。
 */
@Composable
fun FileToolbar(
    viewMode: FileViewMode,
    canBack: Boolean,
    onBack: () -> Unit,
    onToggleViewMode: () -> Unit,
    onSort: () -> Unit,
    onSearch: () -> Unit,
    canPaste: Boolean,
    onPaste: () -> Unit,
    moreMenuOptions: List<UfiPopupOption>,
    modifier: Modifier = Modifier,
    showSearch: Boolean = true,
    onPushTasks: (() -> Unit)? = null,
    pushTaskBadge: Int = 0
) {
    val palette = LocalResolvedPalette.current

    // Overflow menu state + anchor bounds used by UfiPopupMenu for positioning.
    var moreExpanded by remember { mutableStateOf(false) }
    var moreAnchorBounds by remember { mutableStateOf(IntRect.Zero) }

    UfiListToolbar(
        modifier = modifier,
        leading = if (!canBack) {
            null
        } else {
            {
                UfiToolbarAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = "返回",
                    onClick = onBack
                )
            }
        }
    ) {
        // 高频操作：视图切换 / 排序 / 搜索（常驻）
        UfiViewModeAction(grid = viewMode == FileViewMode.GRID, onToggle = onToggleViewMode)
        UfiSortAction(onClick = onSort)
        if (showSearch) {
            UfiToolbarAction(
                icon = Icons.Filled.Search,
                label = "搜索",
                onClick = onSearch
            )
        }

        // 推送任务：只在外部存储源里出现。带角标时说明有在途或失败的作业 ——
        // 那是"文件到底上去了没有"的唯一答案，不能只藏在更多菜单里。
        if (onPushTasks != null) {
            Box {
                UfiToolbarAction(
                    icon = Icons.Filled.CloudUpload,
                    label = "推送任务",
                    onClick = onPushTasks
                )
                if (pushTaskBadge > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 4.dp)
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(palette.accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (pushTaskBadge > 9) "9+" else pushTaskBadge.toString(),
                            style = UfiTextStyles.caption,
                            color = palette.onAccent
                        )
                    }
                }
            }
        }

        // 粘贴：仅在剪贴板存在时显示，直接暴露为工具栏按钮。
        if (canPaste) {
            UfiToolbarAction(
                icon = Icons.Filled.ContentPaste,
                label = "粘贴",
                onClick = onPaste,
                tint = palette.accent
            )
        }

        // 更多：低频操作（多选/新建/上传/刷新/存储权限）收入浮出菜单。
        Box(
            modifier = Modifier.onGloballyPositioned { coords ->
                val r = coords.boundsInWindow()
                moreAnchorBounds = IntRect(
                    r.left.roundToInt(),
                    r.top.roundToInt(),
                    r.right.roundToInt(),
                    r.bottom.roundToInt()
                )
            }
        ) {
            UfiToolbarAction(
                icon = Icons.Filled.MoreVert,
                label = "更多",
                onClick = { moreExpanded = !moreExpanded }
            )
            UfiPopupMenu(
                visible = moreExpanded,
                onDismiss = { moreExpanded = false },
                anchorBounds = moreAnchorBounds,
                options = moreMenuOptions
            )
        }
    }
}

/**
 * 2026-08-31：私有 ToolbarAction 已删除 —— 与 TextEditorScreen 那份合并进公共层
 * [com.ufi_axis.ui.components.common.UfiToolbarAction]（本页用默认 vertical = true 形态）。
 */
