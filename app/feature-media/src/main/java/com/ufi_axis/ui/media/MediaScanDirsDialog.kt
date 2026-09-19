package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.LocalUfiDialogClose
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiDivider
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiListRowCard
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 扫描目录选择器（无状态弹窗，`visible + onConfirm/onDismiss`）。
 *
 * 2026-09-16 从 `MediaLibraryPage.kt` 拆出：按文件管理器那套边界，**弹窗各自成文件**，
 * 页壳只持有"开关"这一个状态。
 *
 * 走已有的 `/api/files/list` 浏览设备目录（不给"选目录"新造接口）。选中的目录列在上方，
 * 确定时整份提交给 core。**空列表是合法选择**且含义明确：不限目录 = 整个媒体库里的这一类，
 * 所以给了一颗「清空（整个媒体库）」，而不是让人逐条删到空。
 *
 * 目录是**按类型各存一份**的（core `/api/media/config?type=`），所以三页各开这个弹窗
 * 改的是三份不同配置，不存在互相覆盖。
 */
@Composable
internal fun MediaScanDirsDialog(
    visible: Boolean,
    typeLabel: String,
    initial: List<String>,
    browse: suspend (String) -> Pair<List<String>, String?>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    if (!visible) return
    val palette = LocalResolvedPalette.current
    var selected by remember(initial) { mutableStateOf(initial) }
    var path by remember { mutableStateOf(MEDIA_DIR_PICKER_ROOT) }
    var parent by remember { mutableStateOf<String?>(null) }
    var children by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(path) {
        loading = true
        val (dirs, up) = browse(path)
        children = dirs
        parent = up
        loading = false
    }

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "$typeLabel 扫描目录",
        confirmButton = {
            // 关闭动作交给 shell 排时序：离场 backdrop 要播完才卸载窗口，见 LocalUfiDialogClose。
            // local 必须在弹窗自己的 slot 内部读，在弹窗外面读拿到的是"直接执行"的默认实现。
            val close = LocalUfiDialogClose.current
            UfiButton(text = "保存", onClick = { close { onConfirm(selected) } })
        },
        dismissButton = {
            val close = LocalUfiDialogClose.current
            UfiButton(text = "取消", onClick = { close(onDismiss) }, variant = UfiButtonVariant.Subtle)
        }
    ) {
        // 间距统一到 UfiDialogBody（12dp）
        UfiDialogBody {
            Text(
                if (selected.isEmpty()) {
                    "当前：整个媒体库（不限目录）"
                } else {
                    "已选 ${selected.size} 个目录"
                },
                style = UfiTextStyles.note,
                color = palette.textSecondary
            )
            if (selected.isNotEmpty()) {
                selected.forEach { dir ->
                    UfiListRowCard(
                        title = dir.substringAfterLast('/').ifBlank { dir },
                        subtitle = dir,
                        selected = true,
                        onClick = { selected = selected - dir },
                        leading = {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
                UfiButton(
                    text = "清空（整个媒体库）",
                    onClick = { selected = emptyList() },
                    variant = UfiButtonVariant.Subtle,
                    size = UfiButtonSize.Small
                )
            }

            UfiDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { parent?.let { path = it } },
                    enabled = parent != null
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "上一级",
                        tint = if (parent != null) palette.accent else palette.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    path,
                    style = UfiTextStyles.note,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                UfiButton(
                    text = "加入此目录",
                    onClick = { if (path !in selected) selected = selected + path },
                    variant = UfiButtonVariant.Subtle,
                    size = UfiButtonSize.Small,
                    icon = Icons.Default.Add
                )
            }

            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) { UfiLoadingIndicator() }

                children.isEmpty() -> Box(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                    UfiListEmptyState(text = "这个目录下没有子目录")
                }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    contentPadding = PaddingValues(vertical = Spacing.Small),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small)
                ) {
                    items(children, key = { it }) { dir ->
                        UfiListRowCard(
                            title = dir.substringAfterLast('/').ifBlank { dir },
                            selected = dir in selected,
                            onClick = { path = dir },
                            leading = {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailing = {
                                UfiButton(
                                    text = if (dir in selected) "已选" else "选择",
                                    onClick = {
                                        selected = if (dir in selected) selected - dir else selected + dir
                                    },
                                    variant = UfiButtonVariant.Subtle,
                                    size = UfiButtonSize.Small
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 目录选择器的起点：用户存储根（core 的白名单前缀之一，文件管理器也是从这里起）。 */
private const val MEDIA_DIR_PICKER_ROOT = "/storage/emulated/0"
