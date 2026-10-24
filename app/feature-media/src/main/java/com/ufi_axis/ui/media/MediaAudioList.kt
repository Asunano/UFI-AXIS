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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.ui.components.common.UfiListLoadedCounter
import com.ufi_axis.ui.components.common.UfiListRowCard
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 音乐页的列表（[MediaAudioScreen] 的内容区）。
 *
 * 一行 = 方形封面 + 曲名 + 「艺术家 · 专辑」+ 右侧时长。点开进 [MediaAudioPlayerScreen]。
 *
 * 行壳与视频/其他列表共用 [UfiListRowCard]；这一栏只决定三件事：封面是方的、副信息是
 * 艺术家与专辑、时长靠右单列。信息不同才是分栏的意义，样式不该跟着不同。
 *
 * ## 「正在播放」标记（2026-10-21）
 * 正在播的那一首行卡打 accent 选中态（与队列弹窗的当前项同一条口径，由 [UfiListRowCard]
 * 的 `selected` 负责），封面上再压一层 accent 蒙层 + [UfiPlayingBars] 律动条 —— 用律动条
 * 而不是只在文案上打色，是为了**隔着半屏扫一眼**也能认出哪首在放。
 *
 * 状态取自 [UfiNowPlayingState]：它由常驻在 Activity 顶层的探针维护，与"当前在哪个页面"无关，
 * 所以列表、歌单、分组详情三处共用同一份（这三处都用本组件）。
 *
 * @param listState 滚动位置。默认自己 remember 一份（分组详情页就这么用）；音乐页要从外面传 ——
 *   那一页的四个分类 Tab 是"换一棵子树"，`rememberLazyListState()` 会随子树一起没了，
 *   切回"全部"就回到顶部。
 * @param onLongClick 长按一行（"加入歌单" / "移出歌单"的入口）。null = 不挂长按手势。
 */
@Composable
internal fun MediaAudioList(
    items: List<MediaLibraryItem>,
    total: Int,
    thumbUrl: (MediaLibraryItem) -> String,
    onNearEnd: () -> Unit,
    onClick: (MediaLibraryItem) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    onLongClick: ((MediaLibraryItem) -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    MediaNearEndEffect(listState, items.size, onNearEnd)

    /*
     * 列表**首屏那几首**的封面提前解好（2026-10-21，条数见 [UfiAudioCoverPrefetch.LIST_HEAD]）。
     *
     * 不做整页：core 是按需从文件里抽封面的，一页几十首会把它的文件 IO 灌满，反而拖慢
     * 用户正在看的那几张。滚动时真正被展开的行反正也会自己去取，这里只管"打开列表第一屏
     * 就一起出现"的观感。
     */
    val thumbContext = LocalPlatformContext.current
    val thumbDensity = LocalDensity.current
    val headUrls = remember(items.size, items.firstOrNull()?.path) {
        items.asSequence()
            .filter { !it.missing }
            .take(UfiAudioCoverPrefetch.LIST_HEAD)
            .map { thumbUrl(it) }
            .toList()
    }
    LaunchedEffect(headUrls) {
        val px = with(thumbDensity) { MEDIA_AUDIO_THUMB_SIZE.roundToPx() }
        UfiAudioCoverPrefetch.thumbs(thumbContext, headUrls, px)
    }

    // 正在播的那一首：路径比对（媒体库里 path 唯一），暂停时仍然标出来，只是律动条不动了
    val playingPath = UfiNowPlayingState.mediaId.value
    val playing = UfiNowPlayingState.isPlaying.value

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.Small),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        // key 用 path 而不是 id：歌单里已失效的条目 **id 全是 0**（媒体库里查不到那一行），
        // 按 id 做 key 会出现重复键，LazyColumn 直接抛异常。path 在媒体库里本来就是唯一的。
        items(items, key = { it.path }) { item ->
            val duration = formatMediaDuration(item.duration_ms)
            val active = !item.missing && item.path == playingPath
            UfiListRowCard(
                // 显示曲名而不是文件名（取值链见 audioDisplayTitle）
                title = audioDisplayTitle(item),
                subtitle = if (item.missing) {
                    // 已失效的行要说清是"文件不在了"，而不是让用户对着一行没有时长的歌发愣
                    MEDIA_AUDIO_MISSING_NOTE
                } else {
                    listOf(audioDisplayArtist(item), item.album)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                },
                // 正在播的这一首：与队列弹窗的当前项同一套选中态（accent 底 + 描边 + 主色标题）
                selected = active,
                // 失效的条目点了也放不出来（没有可取流的文件），所以不给单击入口；
                // 长按仍然保留 —— 用户要能把它移出歌单。
                onClick = if (item.missing) null else ({ onClick(item) }),
                onLongClick = onLongClick?.let { handler -> { handler(item) } },
                leading = {
                    Box(contentAlignment = Alignment.Center) {
                        if (item.missing) {
                            // id 是 0，缩略图 URL 注定 404 —— 不发那次请求（见 MediaThumbPlaceholder）
                            MediaThumbPlaceholder(Icons.Default.MusicNote, MEDIA_AUDIO_THUMB_SIZE)
                        } else {
                            MediaThumb(
                                url = thumbUrl(item),
                                fallback = Icons.Default.MusicNote,
                                width = MEDIA_AUDIO_THUMB_SIZE,
                                height = MEDIA_AUDIO_THUMB_SIZE
                            )
                        }
                        /*
                         * 正在播的角标：accent 蒙层 + 律动条。
                         *
                         * 蒙层用 accent 而不是黑色 scrim：与播放页/视频页"正在播放"那条缩略图的
                         * 高亮同一种语言（那边也是 accent 蒙层），四处的强调色才统一。
                         * 条本身用 pageBg —— 它和 accent 是配色表里天然的一对对比色，
                         * 深浅两个主题下都不用另配一个颜色。
                         */
                        if (active) {
                            Box(
                                modifier = Modifier
                                    .size(MEDIA_AUDIO_THUMB_SIZE)
                                    .clip(UfiCardDefaults.shape)
                                    .background(palette.accent.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center
                            ) {
                                UfiPlayingBars(color = palette.pageBg, playing = playing)
                            }
                        }
                    }
                },
                trailing = if (duration.isBlank()) null else {
                    {
                        Text(
                            duration,
                            style = UfiTextStyles.monoCaption,
                            // 当前这首的时长也跟着走 accent：队列弹窗里就是这口径
                            color = if (active) palette.accent else palette.textSecondary
                        )
                    }
                }
            )
        }
        if (items.size < total) {
            item { UfiListLoadedCounter(loaded = items.size, total = total) }
        }
    }
}

/** 歌单里文件已经不在媒体库时那一行的副标题。 */
internal const val MEDIA_AUDIO_MISSING_NOTE = "文件已不在媒体库（长按可移出）"


/**
 * 曲目行封面的边长。
 *
 * 三处共用同一个值：真实行、首屏骨架的前置槽（[MediaLibraryPage] 与 [MediaAudioGroupScreen]
 * 都要传）、底部迷你控制条的封面。分散写 `44.dp` 的话，改一处就会出现"骨架比真实行矮一截"
 * 或"控制条封面与列表不齐"这类只在真机上看得出的错位。
 */
internal val MEDIA_AUDIO_THUMB_SIZE = 44.dp
