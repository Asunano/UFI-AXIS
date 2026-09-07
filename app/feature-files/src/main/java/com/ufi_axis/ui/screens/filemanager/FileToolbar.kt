package com.ufi_axis.ui.screens.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.ufi_axis.ui.components.common.UfiPopupMenu
import com.ufi_axis.ui.components.common.UfiPopupOption
import com.ufi_axis.ui.components.common.UfiToolbarAction
import com.ufi_axis.ui.theme.LocalResolvedPalette
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import com.ufi_axis.viewmodel.state.FileViewMode

/**
 * 文件管理器工具栏（无状态展示组件，T5）。
 *
 * 横向排列一组图标按钮：视图切换、排序、搜索、粘贴（条件显示）、更多。
 * 图标着色为 [com.ufi_axis.ui.theme.LocalResolvedPalette.textSecondary]，
 * 并均设置 contentDescription 以保证无障碍。
 *
 * 底部用一条细 [com.ufi_axis.ui.theme.LocalResolvedPalette.divider] 分隔线收口。
 * 组件完全无状态：所有行为通过回调上抛。
 *
 * @param viewMode 当前视图模式（列表/网格）
 * @param canBack 是否显示返回按钮（根目录为 false）
 * @param onBack 点击返回上一级
 * @param onToggleViewMode 点击切换视图模式
 * @param onSort 点击排序
 * @param onSearch 点击搜索
 * @param canPaste 是否显示粘贴按钮（剪贴板非空时）
 * @param onPaste 点击粘贴
 * @param moreMenuOptions 「更多」浮出菜单的选项（锚定在「更多」按钮处）
 * @param modifier 修饰符
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
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current

    // Overflow menu state + anchor bounds used by UfiPopupMenu for positioning.
    var moreExpanded by remember { mutableStateOf(false) }
    var moreAnchorBounds by remember { mutableStateOf(IntRect.Zero) }

    // 核心操作工具栏：与 BreadcrumbBar 分离，避免多重路径导致布局异常。
    // 每个操作以「图标 + 文字标签」垂直排列，提升可识别性。
    Box(modifier = modifier.fillMaxWidth().height(48.dp)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回上一级（非根目录显示）
            if (canBack) {
                UfiToolbarAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = "返回",
                    onClick = onBack
                )
            }

            // 左侧占位，把操作按钮整体推到右侧。
            Box(modifier = Modifier.weight(1f))

            // 高频操作：视图切换 / 排序 / 搜索（常驻）
            UfiToolbarAction(
                icon = if (viewMode == FileViewMode.GRID) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.ViewModule,
                label = "视图",
                onClick = onToggleViewMode
            )
            UfiToolbarAction(
                icon = Icons.AutoMirrored.Filled.Sort,
                label = "排序",
                onClick = onSort
            )
            UfiToolbarAction(
                icon = Icons.Filled.Search,
                label = "搜索",
                onClick = onSearch
            )

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

        // 细底部分隔线收口。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.divider)
        )
    }
}

/**
 * 2026-08-31：私有 ToolbarAction 已删除 —— 与 TextEditorScreen 那份合并进公共层
 * [com.ufi_axis.ui.components.common.UfiToolbarAction]（本页用默认 vertical = true 形态）。
 */
