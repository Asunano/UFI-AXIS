package com.ufi_axis.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.model.MEDIA_GROUP_ALBUM
import com.ufi_axis.data.model.MEDIA_GROUP_ARTIST
import com.ufi_axis.data.model.MEDIA_GROUP_FOLDER
import com.ufi_axis.data.model.MediaGroupEntry
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiListLoadingState
import com.ufi_axis.ui.components.common.UfiListRowCard
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.state.MEDIA_GROUP_KINDS
import com.ufi_axis.viewmodel.state.MediaGroupState

/**
 * 音乐页「专辑 / 歌手 / 文件夹」三个分页共用的分组列表。
 *
 * 三个维度只差**占位图标与文案**，数据形状完全一样（[MediaGroupEntry]），所以是一个组件
 * 而不是三个 —— 这与 [MediaAudioList] 和视频列表分开的理由正相反：那两者展示的信息本来不同。
 *
 * 点开一组进 [MediaAudioGroupScreen]（回查组内曲目）；封面 URL 由调用方给，
 * 因为拼 URL 要用 `media.thumbnailUrl`，而这一层是无状态展示件。
 *
 * ## 空 key 那一行为什么不可点
 * core 的分组结果里可能有一组「没有这个标签的曲目」（无专辑名 / 无歌手 / 无目录），它的 key 是空串。
 * 用空 key 回查 `/api/media/list` 等于不带过滤条件 —— 点进去看到的是整库，与行上写的数量
 * 完全对不上。所以这一行只报数、不给入口，并在副标题里说明原因，而不是让用户点了才发现不对。
 *
 * @param listState 滚动位置。默认自己 remember 一份；音乐页**必须**从外面传 ——
 *   三个维度共用本组件这一个调用点，不把状态提到切页之上的话，一是切回来滚动位置丢了，
 *   二是三个维度会共享同一份滚动位置（在专辑页滚到一半再切歌手，歌手页也停在那个偏移）。
 */
@Composable
internal fun MediaAudioGroupList(
    state: MediaGroupState,
    coverUrl: (MediaGroupEntry) -> String?,
    onClick: (MediaGroupEntry) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val palette = LocalResolvedPalette.current
    val fallbackIcon = mediaGroupIcon(state.by)

    when {
        /*
         * 首屏骨架：判据是「**还没拉到过结果**」，不是「正在加载」。
         *
         * 原来写的是 `isLoading && !loadedOnce`，漏掉了一段真实存在的空窗：切到这个维度的
         * 那一帧，请求是由 `LaunchedEffect` 在组合**之后**才发出的，此刻 isLoading 仍是 false、
         * loadedOnce 也还是 false —— 于是这一帧掉进下面的"空态"分支，观感是
         * 「空态闪一下 → 骨架 → 内容」。改成只看 loadedOnce 就没有这段空窗。
         *
         * 不会卡在骨架上：`loadGroups` 无论成功还是失败都会把 loadedOnce 置 true。
         *
         * 另一半红线仍然成立：已经有结果之后的重算（改扫描目录）**不换骨架** ——
         * loadedOnce 已经是 true，不会再走到这里，已渲染的内容不会被整块替换成灰块。
         */
        !state.loadedOnce -> UfiListLoadingState(
            leadingWidth = MEDIA_GROUP_COVER_SIZE,
            leadingHeight = MEDIA_GROUP_COVER_SIZE,
            leadingCircle = false
        )

        // 拉失败也走这里：错误话术比"没有分组"更有信息量，所以优先显示它
        state.groups.isEmpty() -> UfiListEmptyState(
            text = state.errorMessage
                ?: "还没有可以按${mediaGroupLabel(state.by)}分组的音乐",
            icon = fallbackIcon
        )

        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Spacing.Small),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small)
        ) {
            items(state.groups, key = { it.key }) { entry ->
                val unlabeled = entry.key.isBlank()
                val url = coverUrl(entry)
                UfiListRowCard(
                    title = entry.title,
                    subtitle = buildString {
                        append(entry.subtitle)
                        if (unlabeled) append(GROUP_UNLABELED_NOTE)
                    }.takeIf { it.isNotBlank() },
                    onClick = if (unlabeled) null else ({ onClick(entry) }),
                    leading = {
                        if (url == null) {
                            /*
                             * 这一组一张封面都没有（core 回的 cover_id 是 0）。
                             * 刻意不把空串喂给 [MediaThumb] —— 那会走一次注定失败的图片加载，
                             * 每一行都往 release 日志里写一条 WARN。这里只画它的占位层。
                             */
                            GroupCoverPlaceholder(fallbackIcon)
                        } else {
                            MediaThumb(
                                url = url,
                                fallback = fallbackIcon,
                                width = MEDIA_GROUP_COVER_SIZE,
                                height = MEDIA_GROUP_COVER_SIZE
                            )
                        }
                    },
                    trailing = {
                        Text(
                            "${entry.count} 首",
                            style = UfiTextStyles.monoCaption,
                            color = palette.textSecondary
                        )
                    }
                )
            }
        }
    }
}

/** 没有封面时的占位方块：形状与 [MediaThumb] 的底层完全一致，两种行不会一高一低。 */
@Composable
private fun GroupCoverPlaceholder(icon: ImageVector) {
    val palette = LocalResolvedPalette.current
    Box(
        modifier = Modifier
            .size(MEDIA_GROUP_COVER_SIZE)
            .clip(UfiCardDefaults.shape)
            .background(palette.surfaceMuted),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(Spacing.IconSizeMedium)
        )
    }
}

/** 维度的占位图标。取不到封面时它就是这一行唯一的类型提示，所以三个维度必须各不相同。 */
internal fun mediaGroupIcon(by: String): ImageVector = when (by) {
    MEDIA_GROUP_ALBUM -> Icons.Default.Album
    MEDIA_GROUP_ARTIST -> Icons.Default.Person
    MEDIA_GROUP_FOLDER -> Icons.Default.Folder
    else -> Icons.Default.MusicNote
}

/** 维度中文名，取自 [MEDIA_GROUP_KINDS] —— 界面上不再写第二份"专辑 / 歌手 / 文件夹"。 */
internal fun mediaGroupLabel(by: String): String =
    MEDIA_GROUP_KINDS.firstOrNull { it.first == by }?.second ?: "分组"

/**
 * 分组封面边长。
 *
 * 比曲目行的 44dp 大一档：分组行的封面是"这一组是什么"的主视觉（一张专辑封面），
 * 而曲目行的封面只是陪衬（那一行真正要读的是曲名）。骨架也用这个值，两者形状对齐。
 */
private val MEDIA_GROUP_COVER_SIZE = 48.dp

/** 空 key 那一行追加在副标题后的说明。理由见本文件 KDoc。 */
private const val GROUP_UNLABELED_NOTE = "（无标签，无法查看）"
