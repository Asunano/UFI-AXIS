package com.ufi_axis.ui.screens.filemanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.viewmodel.module.FileManagerModule
import com.ufi_axis.viewmodel.state.StorageVolume

/** 远端路径前缀，真源在 [FileManagerModule.REMOTE_PREFIX]（这里只是别名，不另立一份字面量）。 */
private const val REMOTE_PATH_PREFIX = FileManagerModule.REMOTE_PREFIX

/**
 * 面包屑路径栏（无状态展示组件，T5）。
 *
 * Home 与「返回上一级」按钮已移至工具栏（见 [FileToolbar]），本组件仅负责展示路径文本。
 *
 * 将 [currentPath] 按 "/" 切分为可点击的分段，每段展示为可点击的 [Text]：
 *  - 可点击分段使用 [com.ufi_axis.ui.theme.LocalResolvedPalette.accent]；
 *  - 分隔符 "/" 使用 [com.ufi_axis.ui.theme.LocalResolvedPalette.textSecondary]。
 * 点击某分段会导航到该前缀路径（经 [onNavigate]）；根 crumb 经 [onRoot] 回到父级。
 *
 * 卷根（[currentPath] 精确等于某卷挂载点）只显示该卷标签，不再拆分挂载路径；
 * 子目录则在卷标签后展示「相对挂载点的分段」，点击分别导航到对应前缀路径；
 * 未匹配到任何卷时卷标签兜底为「内部存储」。
 *
 * 组件完全无状态：所有状态通过参数传入，不持有任何 ViewModel 或可变状态。
 *
 * @param currentPath 当前路径（如 "/storage/emulated/0/Download"）
 * @param volumes 已知存储卷列表（用于匹配卷标签）
 * @param onNavigate 点击某路径前缀时的回调，参数为目标前缀路径
 * @param onRoot 点击根 crumb 时的回调（回到父级/虚拟根）
 * @param remoteSourceLabel 当前路径所在**远端源**的显示名（本地路径传 null）。
 *   2026-09-21 新增：远端路径形如 `remote:abc123/photos/2024`，原样切分会把
 *   `remote:abc123` 这串内部 id 当成第一段渲染出来 —— 那是给 core 看的，不是给人看的。
 *   有这个参数时第一段显示源标签，其余段照旧按 `/` 切。
 * @param modifier 修饰符
 */
@Composable
fun BreadcrumbBar(
    currentPath: String,
    volumes: List<StorageVolume>,
    onNavigate: (String) -> Unit,
    onRoot: () -> Unit,
    modifier: Modifier = Modifier,
    remoteSourceLabel: String? = null
) {
    val palette = LocalResolvedPalette.current
    val isRoot = currentPath.isEmpty()

    // 远端路径单独一条渲染分支：卷匹配对它完全无意义（它没有挂载点）。
    val isRemote = currentPath.startsWith(REMOTE_PATH_PREFIX)

    // 最长前缀匹配：找到 currentPath 所属卷（mountPath 最长者），用于卷根/子目录判定与卷标签。
    val matchedVolume = volumes
        .filter { currentPath.startsWith(it.mountPath) }
        .maxByOrNull { it.mountPath.length }
    val rootLabel = matchedVolume?.label ?: "内部存储"

    // 卷根判定：currentPath 精确等于某卷 mountPath（不再拆分挂载路径为可点击分段）。
    val isVolumeRoot = matchedVolume != null && currentPath == matchedVolume.mountPath

    // 路径文本区：占满宽度、可横向滚动；Home/返回已移至工具栏（见 FileToolbar）。
        Box(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 虚拟根层级：路径区无内容（由工具栏主页键回退）。
            if (!isRoot) {
                // 前置位置图标：让空旷的地址栏有视觉锚点，同时与卷标签/路径保持同色系。
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(22.dp),
                    tint = palette.accent
                )
                if (isRemote) {
                    // ── 远端源分支 ──
                    // 第一段是源标签（点它回源根），其余按 `/` 切；累加前缀时必须带上
                    // `remote:<id>/` 这一头，否则点中间那一段会跳到一条 core 认不出的路径。
                    val sourceId = currentPath.removePrefix(REMOTE_PATH_PREFIX).substringBefore('/')
                    val sourceRoot = "$REMOTE_PATH_PREFIX$sourceId/"
                    val atSourceRoot = currentPath.trimEnd('/') == sourceRoot.trimEnd('/')
                    Text(
                        text = remoteSourceLabel ?: sourceId,
                        color = palette.accent,
                        style = UfiTextStyles.pathSegment.copy(fontWeight = UfiWeight.Medium),
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            // 已经在源根上：再点它只能是"回到能挑别的存储那一层"。
                            .clickable(onClick = if (atSourceRoot) onRoot else ({ onNavigate(sourceRoot) }))
                    )
                    val relative = currentPath.removePrefix(sourceRoot).trimStart('/')
                    var remoteAccumulated = sourceRoot.trimEnd('/')
                    relative.split("/").filter { it.isNotEmpty() }.forEach { segment ->
                        remoteAccumulated = "$remoteAccumulated/$segment"
                        // 同上：迭代内用 val 捕获，否则每一段都会跳到最终路径。
                        val target = remoteAccumulated
                        Text(
                            text = " / ",
                            color = palette.textSecondary,
                            style = UfiTextStyles.pathSegment,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        Text(
                            text = segment,
                            color = palette.accent,
                            style = UfiTextStyles.pathSegment.copy(fontWeight = UfiWeight.Medium),
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .clickable(onClick = { onNavigate(target) })
                        )
                    }
                } else if (isVolumeRoot) {
                    // 卷根：只渲染卷标签（点击回到虚拟根），不拆分挂载路径。
                    Text(
                        text = rootLabel,
                        color = palette.accent,
                        style = UfiTextStyles.pathSegment.copy(fontWeight = UfiWeight.Medium),
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .clickable(onClick = onRoot)
                    )
                } else {
                    // 子目录：卷标签可点击回到该卷根；相对分段点击导航至对应前缀路径。
                    val mountPath = matchedVolume?.mountPath ?: ""
                    val rootClick: () -> Unit = if (matchedVolume != null) {
                        { onNavigate(mountPath) }
                    } else {
                        onRoot
                    }
                    Text(
                        text = rootLabel,
                        color = palette.accent,
                        style = UfiTextStyles.pathSegment.copy(fontWeight = UfiWeight.Medium),
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .clickable(onClick = rootClick)
                    )

                    val relative = currentPath.removePrefix(mountPath).trimStart('/')
                    val relSegments = relative.split("/").filter { it.isNotEmpty() }
                    var accumulated = mountPath
                    relSegments.forEach { segment ->
                        accumulated = "$accumulated/$segment"
                        // 关键修复：在迭代内用 val 捕获目标路径，避免 clickable 闭包按引用捕获
                        // accumulated，导致所有分段点击都跳到最终路径（当前路径）而失效。
                        val target = accumulated
                        Text(
                            text = " / ",
                            color = palette.textSecondary,
                            style = UfiTextStyles.pathSegment,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        Text(
                            text = segment,
                            color = palette.accent,
                            style = UfiTextStyles.pathSegment.copy(fontWeight = UfiWeight.Medium),
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .clickable(onClick = { onNavigate(target) })
                        )
                    }
                }
            }
        }
    }
}
