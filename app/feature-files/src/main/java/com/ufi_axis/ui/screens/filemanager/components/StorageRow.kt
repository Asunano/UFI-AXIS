package com.ufi_axis.ui.screens.filemanager.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.components.common.UfiPopupMenu
import com.ufi_axis.ui.components.common.UfiPopupOption
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiStandardCard
import kotlin.math.roundToInt

/**
 * 存储列表里的一行 —— 内部存储、SD 卡、每个外部存储源，以及「添加外部存储」都用它。
 *
 * ## 为什么本地卷和远端源要长得一样
 * 2026-09-21 之前这一层是两种卡拼起来的：本地卷是「标题 + 容量 + 占用率进度条」的大卡，
 * 远端源是「图标 + 两行文字」的行，中间还夹一个「外部存储」分段标题。三种不同的视觉密度
 * 堆在同一个列表里，读起来像两个各自为政的列表被强行拼接。
 *
 * 现在统一成一行一个：**图标 + 名称 + 一行副信息 + 右箭头**。容量从进度条降级成副标题里的
 * 一句文字（信息量不变），换来的是所有行高一致、扫一眼就能数清有几个存储。
 *
 * ## 长按菜单
 * 外部存储源的「编辑 / 测试连接 / 删除」挂在长按上（[moreOptions]），与文件行的长按
 * 是同一个手势约定。这样这一层就同时承担了原来那个独立「外部存储」列表页的职责 ——
 * 那个页面已经删掉，它列的东西和这里完全重复。
 *
 * @param icon 左侧图标（本地卷用盘符类图标，远端按协议给 Cloud / Dns / CloudQueue）
 * @param title 主标题（卷标签 / 源名称）
 * @param subtitle 副信息（本地："已用 6.9G / 共 23.0G · 29%"；远端：协议名）
 * @param onClick 整行点击
 * @param dimmed 置灰（已停用的源）。只改前景色，不拦点击 —— 拦了用户会以为是坏的
 * @param loading 这一行正在加载（点了它、还在等 core 把目录列回来）。
 *   右箭头换成转圈：远端源要等 core 连过去，0.5~2s 里必须有"点到了"的回执，
 *   否则用户会反复点，每点一次又发一个请求。
 * @param statusText 右侧状态标签文字（连通性结果：`23ms` / `失败` / `测试中`）。
 *   null = 不显示。**只给外部存储源用** —— 本地卷不存在"连不上"这回事。
 * @param statusColor 标签配色。由调用方从 palette 取（success / warning / textSecondary），
 *   组件内不自己判成功失败：那样等于把业务判据埋进 UI 组件。
 * @param onEdit 右侧「编辑」按钮。null = 不显示（本地卷、"添加"那一行都没有可编辑的配置）。
 *   长按菜单里也有编辑，但那是隐藏入口 —— 配置是这一层的主要操作之一，得有个看得见的按钮。
 * @param moreOptions 长按菜单项；空列表 = 不响应长按
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StorageRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    loading: Boolean = false,
    statusText: String? = null,
    statusColor: Color? = null,
    onEdit: (() -> Unit)? = null,
    moreOptions: List<UfiPopupOption> = emptyList()
) {
    val palette = LocalResolvedPalette.current
    var expanded by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf(IntRect.Zero) }
    var longPressPoint by remember { mutableStateOf(IntOffset.Zero) }
    val titleColor = if (dimmed) palette.textSecondary else palette.textPrimary
    val iconTint = if (dimmed) palette.textSecondary else palette.accent

    Box(
        modifier = modifier
            .fillMaxWidth()
            .ufiStandardCard()
            .onGloballyPositioned { coords ->
                val r = coords.boundsInWindow()
                anchorBounds = IntRect(
                    r.left.roundToInt(),
                    r.top.roundToInt(),
                    r.right.roundToInt(),
                    r.bottom.roundToInt()
                )
            }
            .then(
                if (moreOptions.isEmpty()) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = {
                            // combinedClickable 不暴露长按 offset，用行的几何中心当长按点
                            longPressPoint = IntOffset(
                                (anchorBounds.left + anchorBounds.right) / 2,
                                (anchorBounds.top + anchorBounds.bottom) / 2
                            )
                            expanded = true
                        }
                    )
                }
            )
            .padding(horizontal = Spacing.CardPadding, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(UfiCardDefaults.shape)
                    .background(iconTint.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = UfiTextStyles.panelTitleStrong,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // 连通性标签：胶囊底 + 同色文字。放在编辑按钮左边、与行垂直居中 ——
            // 这样不会把行撑高（右侧竖排两层会），扫视时又与右侧操作区在同一条视线上。
            if (!statusText.isNullOrBlank()) {
                val tint = statusColor ?: palette.textSecondary
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(tint.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            if (onEdit != null) {
                // 不用 IconButton：它自带 48dp 触控盒会把行撑高，与"一行一个、行高一致"冲突。
                // 32dp 仍在可点范围内（图标 18dp + 内边距），且整行本身也能点。
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onEdit),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "编辑存储源",
                        tint = palette.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(2.dp))
            }
            if (loading) {
                // 用公共的呼吸弧线转圈（与更新、按钮 loading、各页首屏同一个组件）。
                // 不用 M3 的 CircularProgressIndicator：它的颜色不走 LocalResolvedPalette，
                // 换配色时会留下一个不跟着变的转圈。
                UfiLoadingIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2f
                )
            } else {
                // 右箭头：明确"这一行是可以进去的"。没有它时整行只是一块色块，
                // 用户得先点一次才知道能不能点。
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (moreOptions.isNotEmpty()) {
            UfiPopupMenu(
                visible = expanded,
                onDismiss = { expanded = false },
                anchorBounds = anchorBounds,
                anchorPoint = longPressPoint,
                options = moreOptions
            )
        }
    }
}
