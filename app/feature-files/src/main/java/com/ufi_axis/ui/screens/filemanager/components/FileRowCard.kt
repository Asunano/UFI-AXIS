package com.ufi_axis.ui.screens.filemanager.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.ufi_axis.data.api.FileItem
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.ui.components.common.UfiPopupMenu
import com.ufi_axis.ui.components.common.UfiPopupOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件列表行卡片（无状态展示组件）。
 *
 * 布局：在 [com.ufi_axis.ui.theme.ufiStandardCard] 的 Box 内放一行
 * [FileIcon] + 名称/副信息列 + 弹性间距 + 右侧操作（复选框或多选菜单按钮）。
 *
 * 选中态会在 Box 上叠加一层 [com.ufi_axis.ui.theme.LocalResolvedPalette.accent]
 * 的 8% 透明底色。所有交互通过回调上抛，不持有任何业务状态。
 *
 * @param item 文件/目录数据
 * @param isSelected 是否处于选中态
 * @param showCheckbox 是否显示多选复选框（为 false 时允许长按呼出操作菜单）
 * @param onOpen 点击卡片打开的回调
 * @param onToggleSelect 切换选中的回调
 * @param moreOptions 长按呼出的浮出菜单选项（用 UfiPopupMenu 弹出，菜单跟随长按点位置出现）
 * @param modifier 修饰符
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRowCard(
    item: FileItem,
    isSelected: Boolean,
    showCheckbox: Boolean = false,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    moreOptions: List<UfiPopupOption>,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    // 长按菜单状态 + 锚点矩形（容器窗口坐标，供 UfiPopupMenu 换算相对偏移）
    // + 长按点的窗口绝对坐标（让菜单跟随手指位置出现，而非固定在卡片右缘）。
    var expanded by remember { mutableStateOf(false) }
    var moreAnchorBounds by remember { mutableStateOf(IntRect.Zero) }
    var longPressPoint by remember { mutableStateOf(IntOffset.Zero) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .ufiStandardCard()
            .then(
                if (isSelected) {
                    Modifier
                        .background(palette.accent.copy(alpha = 0.08f))
                        .border(1.dp, palette.accent.copy(alpha = 0.5f))
                } else {
                    Modifier
                }
            )
            .onGloballyPositioned { coords ->
                val r = coords.boundsInWindow()
                moreAnchorBounds = IntRect(
                    r.left.roundToInt(),
                    r.top.roundToInt(),
                    r.right.roundToInt(),
                    r.bottom.roundToInt()
                )
            }
            .combinedClickable(
                onClick = onOpen,
                onLongClick = if (showCheckbox) null else {
                    {
                        // combinedClickable 不暴露长按 offset，退而用卡片几何中心作为长按点
                        longPressPoint = IntOffset(
                            (moreAnchorBounds.left + moreAnchorBounds.right) / 2,
                            (moreAnchorBounds.top + moreAnchorBounds.bottom) / 2
                        )
                        expanded = true
                    }
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 40dp 圆角图标容器：目录 accent 8% / 文件 accentSecondary 12%，提升类型识别。
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(UfiCardDefaults.shape)
                    .background(
                        if (item.isDirectory) palette.accent.copy(alpha = 0.08f)
                        else palette.accentSecondary.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                FileIcon(
                    name = item.name,
                    isDirectory = item.isDirectory,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = item.name,
                    style = UfiTextStyles.bodyLeadStrong,
                    color = palette.textPrimary
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = rowSubInfo(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )
            }
            Spacer(Modifier.weight(1f))
            if (showCheckbox) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = palette.accent,
                        checkmarkColor = palette.onAccent
                    )
                )
            }
        }

        // 长按呼出的操作菜单（跟随长按点位置出现）。
        if (!showCheckbox) {
            UfiPopupMenu(
                visible = expanded,
                onDismiss = { expanded = false },
                anchorBounds = moreAnchorBounds,
                anchorPoint = longPressPoint,
                options = moreOptions
            )
        }
    }
}

/**
 * 构造行卡片的副信息文本：目录显示「文件夹」，文件显示「大小 · 日期」。
 */
private fun rowSubInfo(item: FileItem): String {
    return if (item.isDirectory) {
        "文件夹"
    } else {
        "${FormatUtils.formatSize(item.size)} · ${formatDate(item.lastModified)}"
    }
}

/**
 * 2026-08-31：私有 formatSize 已删除 —— 收敛到 [com.ufi_axis.util.FormatUtils.formatSize]。
 */

/**
 * 将毫秒时间戳格式化为 yyyy-MM-dd 的日期字符串。
 *
 * @param millis 最后修改时间（毫秒）
 * @return 格式化后的日期；无效时间返回 "-"
 */
private fun formatDate(millis: Long): String {
    if (millis <= 0L) return "-"
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return formatter.format(Date(millis))
}
