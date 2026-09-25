package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiListRowCard
import com.ufi_axis.ui.components.common.UfiLoadingBox
import com.ufi_axis.ui.components.common.UfiPageBackgroundBox
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSectionHeader
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 「已从音乐库移除的歌曲」管理页。
 *
 * ## 为什么这一页是必需的
 * 「从音乐库移除」把路径写进 core 的排除名单，那一首就从所有音乐列表里消失了。没有这一页，
 * 名单只存在于 core 的 prefs 文件里 —— 界面上看不见、也无从撤销，误触一次就等于永久丢歌。
 * 所以它不是排除功能的附属品，而是那个功能能存在的前提。
 *
 * ## 为什么每行只有文件名和路径
 * 被排除的曲目已经查不到了（core 就是把它们从 `/api/media/list` 里滤掉的），没有
 * `MediaLibraryItem` 可用，曲名 / 歌手 / 专辑都取不到。要显示标签就得为一批"不在库里"的文件
 * 再开一条读元数据的接口，而这一页的用途只是把误删的那一首找回来 —— 路径足够认人。
 *
 * 页壳用 [UfiPageBackgroundBox] 而不是 `UfiPageBackground`：内容是 `LazyColumn`，
 * 后者自带 `verticalScroll`，套懒列表会拿到无限高约束直接抛异常。
 */
@Composable
fun MediaExcludedScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val media = viewModel.media
    val palette = LocalResolvedPalette.current
    val state by media.state.collectAsState()
    val excluded = state.excluded
    val coroutineScope = rememberCoroutineScope()

    // 每次进页都重拉：另一端（web）也能改这份名单，缓存下来的旧列表会让"恢复"点到不存在的项
    LaunchedEffect(Unit) { media.loadExcludedMedia(force = true) }

    /** 正在提交恢复请求的那一条（禁掉它的按钮，避免连点发两次）。 */
    var restoring by remember { mutableStateOf<String?>(null) }

    /** 「全部恢复」的二次确认。 */
    var confirmRestoreAll by remember { mutableStateOf(false) }

    /** 失败原因。成功不弹窗 —— 那一行已经从列表里消失了，就是最好的反馈。 */
    var failure by remember { mutableStateOf<String?>(null) }

    UfiScreenScaffold(
        title = "已从音乐库移除",
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize().padding(padding)) {
            UfiLoadingBox(isLoading = excluded.isLoading && !excluded.loadedOnce) {
                if (excluded.paths.isEmpty()) {
                    UfiListEmptyState(
                        text = excluded.errorMessage
                            ?: "没有被移除的歌曲。在音乐列表里长按一首歌，选「从音乐库移除」，它会出现在这里。",
                        icon = Icons.Default.MusicOff
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // 水平留白由容器给：UfiListRowCard 自己不带页面内距
                        contentPadding = PaddingValues(
                            horizontal = Spacing.CardHorizontalMargin,
                            vertical = Spacing.Large
                        ),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
                    ) {
                        item {
                            UfiSectionHeader(
                                title = "共 ${excluded.total} 首",
                                trailing = {
                                    UfiButton(
                                        text = "全部恢复",
                                        onClick = { confirmRestoreAll = true },
                                        variant = UfiButtonVariant.Subtle,
                                        size = UfiButtonSize.Small,
                                        enabled = restoring == null
                                    )
                                }
                            )
                        }

                        if (excluded.full) {
                            item {
                                Text(
                                    // 说清"已满"的后果：再点移除会静默只生效一部分
                                    text = "名单已达上限 ${excluded.max} 条，再移除歌曲不会生效。" +
                                        "先恢复几首再继续。",
                                    style = UfiTextStyles.caption,
                                    color = palette.error
                                )
                            }
                        }

                        // 路径在名单里天然唯一（core 侧去重），可以直接当 key
                        items(excluded.paths, key = { it }) { path ->
                            UfiListRowCard(
                                title = path.substringAfterLast('/').ifBlank { path },
                                subtitle = path,
                                trailing = {
                                    IconButton(
                                        enabled = restoring == null,
                                        onClick = {
                                            restoring = path
                                            coroutineScope.launch {
                                                failure = media.restoreToLibrary(listOf(path))
                                                restoring = null
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Undo,
                                            contentDescription = "恢复到音乐库",
                                            tint = palette.accent,
                                            modifier = Modifier.size(EXCLUDED_ACTION_ICON)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmRestoreAll) {
        UfiCustomDialog(
            visible = true,
            onDismiss = { confirmRestoreAll = false },
            title = "全部恢复",
            confirmButton = {
                UfiButton(
                    text = "全部恢复",
                    onClick = {
                        val all = excluded.paths
                        confirmRestoreAll = false
                        coroutineScope.launch {
                            failure = media.restoreToLibrary(all)
                        }
                    }
                )
            },
            dismissButton = {
                UfiButton(
                    variant = UfiButtonVariant.Secondary,
                    text = "暂不恢复",
                    onClick = { confirmRestoreAll = false }
                )
            }
        ) {
            UfiDialogBody {
                Text(
                    text = "这 ${excluded.total} 首会重新出现在音乐库列表里。这一步本身也能再撤销" +
                        "（重新移除），所以不用担心点错。",
                    style = UfiTextStyles.note,
                    color = palette.textSecondary
                )
            }
        }
    }

    failure?.let { msg ->
        UfiCustomDialog(
            visible = true,
            onDismiss = { failure = null },
            title = "恢复失败",
            confirmButton = { UfiButton(text = "知道了", onClick = { failure = null }) }
        ) {
            UfiDialogBody {
                Text(msg, style = UfiTextStyles.note, color = palette.textSecondary)
            }
        }
    }
}

/** 行尾动作图标尺寸。与下载任务页一致（18dp）：行尾动作不该抢标题的注意力。 */
private val EXCLUDED_ACTION_ICON = 18.dp
