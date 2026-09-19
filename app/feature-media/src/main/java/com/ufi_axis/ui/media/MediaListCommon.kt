package com.ufi_axis.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.data.model.MEDIA_TYPE_IMAGE
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.DebugLog
import com.ufi_axis.viewmodel.state.MEDIA_KINDS
import kotlinx.coroutines.launch
import java.io.File

/**
 * 视频 / 音乐 / 图片三个页面共用的零件（2026-09-16）。
 *
 * 边界是"谁被三个页面同时用到"：缩略图容器、类型图标/文案、时长格式化、翻页触发。
 * 各页自己的行/格子长什么样归各自的文件（视频页的 [MediaVideoHome] / [MediaVideoLibraryPane]、
 * [MediaAudioList]、[MediaImageGrid]）—— 三者要展示的信息本来就不同，硬塞进一个"通用行"
 * 只会长出一堆 if。音乐 / 图片两页的页壳在 `MediaLibraryPage.kt`，视频页有自己的两栏壳。
 */

/**
 * 缩略图容器。
 *
 * **固定尺寸**是关键：不同缩略图长宽比不一样，不钉住尺寸会让整行/整格的高度乱跳。
 * 图标占位一直画在底层，图片加载成功后盖在上面 —— core 取不到缩略图时会回 404，
 * 此时看到的就是占位图标，不会是一张破图。
 *
 * ## 三种定尺方式
 * · 默认：[width] × [height]（列表行、网格格子）；
 * · [fillWidth]：宽度撑满、高度仍由 [height] 钉住；
 * · [aspectRatio]：宽度撑满、**高度按比例算**（海报墙用 16:9 —— 卡片宽度随列数与间距变，
 *   写死高度会在不同屏宽上出现黑边或裁切）。给了它就不看 width/height。
 *
 * ## 远端拿不到时的兜底（2026-09-16）
 * 传了 [onRemoteMissing] 就意味着"远端 404 之后可以由本机自己生成一张"：加载失败会调它一次
 * （**只一次**，避免失败重试打成循环），拿到本地文件后换成本地文件重新加载。
 * 这条路是为随身 WiFi 这类**解不出视频画面**的设备准备的，实现见 [MediaThumbnailBuilder]。
 *
 * 失败仍然只写 DebugLog，不弹错误、不画破图：缩略图缺失不该打断浏览。
 */
@Composable
internal fun MediaThumb(
    url: String,
    fallback: ImageVector,
    width: Dp = 0.dp,
    height: Dp = 0.dp,
    fillWidth: Boolean = false,
    aspectRatio: Float? = null,
    onRemoteMissing: (suspend () -> File?)? = null
) {
    val palette = LocalResolvedPalette.current
    val scope = rememberCoroutineScope()
    // key 用 url：换了一项（id 变了）就重新开始判定，不要继承上一项的"已尝试过"
    var localFile by remember(url) { mutableStateOf<File?>(null) }
    var triedLocal by remember(url) { mutableStateOf(false) }

    val sizing = when {
        aspectRatio != null -> Modifier.fillMaxWidth().aspectRatio(aspectRatio)
        fillWidth -> Modifier.fillMaxWidth().height(height)
        else -> Modifier.width(width).height(height)
    }

    Box(
        modifier = sizing
            .clip(UfiCardDefaults.shape)
            .background(palette.surfaceMuted),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            fallback,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(20.dp)
        )
        AsyncImage(
            model = localFile ?: url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { state ->
                if (onRemoteMissing != null && !triedLocal && localFile == null) {
                    triedLocal = true
                    scope.launch { localFile = onRemoteMissing() }
                } else {
                    DebugLog.w(
                        "MediaThumb",
                        "缩略图加载失败: ${localFile?.name ?: url}",
                        state.result.throwable
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** 类型名（"视频" / "音乐" / "图片"），取自 [MEDIA_KINDS]，不在这里再写一份字面量。 */
internal fun mediaTypeLabel(type: String): String =
    MEDIA_KINDS.firstOrNull { it.first == type }?.second ?: "媒体"

internal fun mediaTypeIcon(type: String): ImageVector = when (type) {
    MEDIA_TYPE_AUDIO -> Icons.Default.MusicNote
    MEDIA_TYPE_IMAGE -> Icons.Default.Image
    else -> Icons.Default.Videocam
}

/**
 * 时长文案（毫秒 → `m:ss` / `h:mm:ss`）。
 *
 * 这是**纯展示格式化**，所以留在客户端；时长本身是 core 从系统媒体库读出来的
 * （`MediaStore.MediaColumns.DURATION`），app 不去解码文件算时长。
 */
internal fun formatMediaDuration(ms: Long): String {
    if (ms <= 0L) return ""
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 触发下一页的提前量：还剩这么多项没滚到就去取，滚到底才取会看到明显的停顿。 */
private const val MEDIA_PREFETCH_AHEAD = 6

/**
 * 列表滚到接近末尾就取下一页。
 *
 * 判定包在 `derivedStateOf` 里：`visibleItemsInfo` 每帧都在变，直接在组合里读会导致
 * 每滚一像素就重组一次。这里只有"越过阈值"这一次布尔翻转会触发 [LaunchedEffect]。
 */
@Composable
internal fun MediaNearEndEffect(
    listState: LazyListState,
    itemCount: Int,
    onNearEnd: () -> Unit
) {
    val nearEnd by remember(itemCount) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            itemCount > 0 && last >= itemCount - MEDIA_PREFETCH_AHEAD
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) onNearEnd() }
}

/** 网格版，理由同上（`LazyGridState` 与 `LazyListState` 没有公共父类型，只能重载）。 */
@Composable
internal fun MediaNearEndEffect(
    gridState: LazyGridState,
    itemCount: Int,
    onNearEnd: () -> Unit
) {
    val nearEnd by remember(itemCount) {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            itemCount > 0 && last >= itemCount - MEDIA_PREFETCH_AHEAD
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) onNearEnd() }
}

/*
 * 2026-09-16：原来这里还有一个 `MediaLoadedCounter`（列表末尾的「已加载 x / y」）。
 * 它对**任何分页列表**都成立，所以上提成了公共件
 * [com.ufi_axis.ui.components.common.UfiListLoadedCounter]，本文件不再留第二份。
 */

/*
 * ───────── 音频显示成什么 ─────────
 *
 * 取值链：**core 给的标签 → 文件名拆分**。
 * core 的 `/api/media/list` 现在带 `title/artist/album`（系统扫描时解析好的内嵌标签），
 * 播放页还会用 `/api/media/tags` 再补强一次；下面这几个函数只管"拿到什么就显示什么、
 * 都没有时怎么兜底"。列表 / 播放页 / 队列面板 / 迷你条共用同一份 ——
 * 否则同一首歌会在四个地方显示成四种样子。
 */

/**
 * 显示用曲名：内嵌 [MediaLibraryItem.title] 优先，没有就用文件名（去扩展名后再按
 * [splitFileNameTitleArtist] 拆一次）。
 */
internal fun audioDisplayTitle(item: MediaLibraryItem): String {
    item.title.takeIf { it.isNotBlank() }?.let { return it }
    return splitFileNameTitleArtist(audioFileBase(item.name)).first
}

/**
 * 显示用歌手：内嵌 [MediaLibraryItem.artist] 优先，没有才从文件名右半段猜。
 *
 * 猜不出就回空串 —— 宁可这一行不显示，也不写"未知艺人"这种看着像数据的占位。
 */
internal fun audioDisplayArtist(item: MediaLibraryItem): String {
    item.artist.takeIf { it.isNotBlank() }?.let { return it }
    return splitFileNameTitleArtist(audioFileBase(item.name)).second.orEmpty()
}

/** 文件名去扩展名（没有点就原样返回）。 */
internal fun audioFileBase(name: String): String =
    name.substringBeforeLast('.', missingDelimiterValue = name)

/**
 * 从**去掉扩展名的文件名**里猜「歌名 / 歌手」，例如 `带我走-杨丞琳` → `带我走` + `杨丞琳`。
 *
 * 这是取值链里最后一级兜底，因为它确实只是**猜**：
 * · 只认第一个分隔符（`-` / `_` / `–` / `—`，两侧可带空格），后面的原样留在歌手里；
 * · 拆不出、或任一侧为空 → 整段当歌名、歌手返回 null；
 * · 顺序按中文歌常见的 `歌名-歌手` 约定；反过来命名的文件会显示颠倒，但那时候
 *   正确答案本来就不在文件名里 —— 标签才是唯一可靠来源。
 */
internal fun splitFileNameTitleArtist(base: String): Pair<String, String?> {
    val name = base.trim()
    if (name.isEmpty()) return name to null
    val index = name.indexOfFirst { it == '-' || it == '_' || it == '–' || it == '—' }
    if (index <= 0) return name to null
    val left = name.substring(0, index).trim()
    val right = name.substring(index + 1).trim()
    if (left.isEmpty() || right.isEmpty()) return name to null
    return left to right
}


