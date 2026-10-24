package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiListRowCard
import com.ufi_axis.ui.components.common.UfiPageBackgroundBox
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSectionHeader
import com.ufi_axis.ui.navigation.ufiNavigateOnce
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.PhoneDownloadHistoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 音乐的两本"账"：**最近播放** 与 **下载记录**（2026-10-21）。
 *
 * ## 为什么这两页要分开做
 * 音乐设置页此前只有「扫描范围」和「已从音乐库移除」。但音乐有两个动作**早就存在、结果却无处可查**：
 * · 点歌就播 —— 播过什么没有入口（视频那边首页有「最近播放」横向列表，音乐没有）；
 * · 长按「下载到手机」—— 记录其实写进了本机偏好（`phone_download_history`），
 *   但全仓没有任何页面读它，用户下完就再也找不到。
 *
 * ## 两页各自的数据来源
 * · **最近播放**：`AppPreferences.mediaRecentPlays(MEDIA_TYPE_AUDIO)`，由常驻探针在换歌时写入
 *   （见 `UfiAudioNowPlayingProbe`）—— 这样通知栏、迷你条、面板里切歌都能记到。
 * · **下载记录**：`FileManagerModule.phoneDownloadHistory`（文件链写的那份，与文件管理器共用），
 *   这里按**扩展名**筛出音频。不新增字段：老记录里本来就没有类型，靠扩展名分类既不用迁移，
 *   也不会把历史里的旧条目变成"未知类型"。
 *
 * 两页都用 `UfiPageBackgroundBox`（不是 `UfiPageBackground`）：内容是 `LazyColumn`，
 * 后者自带 `verticalScroll`，套懒列表会拿到无限高约束直接抛异常。
 */

@Composable
fun MediaAudioRecentScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }

    // 每次进页重读一次：播放是随时发生的，缓存一份会在回来时看到旧列表
    var recents by remember { mutableStateOf(prefs.mediaRecentPlays(MEDIA_TYPE_AUDIO)) }
    var confirmClear by remember { mutableStateOf(false) }

    UfiScreenScaffold(title = "最近播放", navController = navController, showBack = true) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (recents.isEmpty()) {
                UfiListEmptyState(
                    text = "还没有播放记录。播一首歌，它就会出现在这里。",
                    icon = Icons.Default.History
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = Spacing.CardHorizontalMargin,
                        vertical = Spacing.Large
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)
                ) {
                    item {
                        UfiSectionHeader(
                            title = "最近 ${recents.size} 首",
                            trailing = {
                                UfiButton(
                                    text = "清空",
                                    onClick = { confirmClear = true },
                                    variant = UfiButtonVariant.Subtle,
                                    size = UfiButtonSize.Small
                                )
                            }
                        )
                    }
                    items(recents.size) { index ->
                        val (path, name, _) = recents[index]
                        UfiListRowCard(
                            title = name.ifBlank { audioFileBase(path.substringAfterLast('/')) },
                            subtitle = path,
                            // 点一行 = 放这一首（不带播放范围：只"看一眼/听一首"，不动当前队列）
                            onClick = { navController.ufiNavigateOnce(mediaAudioRouteOf(path)) }
                        )
                    }
                }
            }
        }
    }

    UfiConfirmDialog(
        visible = confirmClear,
        title = "清空最近播放？",
        text = "只清这份记录，不影响歌单、播放进度与文件本身。",
        confirmText = "清空",
        onConfirm = {
            confirmClear = false
            prefs.clearMediaRecentPlays(MEDIA_TYPE_AUDIO)
            recents = emptyList()
        },
        onDismiss = { confirmClear = false }
    )

}

@Composable
fun MediaAudioDownloadsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val files = viewModel.files
    val state by files.state.collectAsState()
    val palette = LocalResolvedPalette.current

    // 记录写在文件链那份偏好里；本页只读，所以进页拉一次就够
    LaunchedEffect(Unit) { files.loadPhoneDownloadHistory() }

    // 按扩展名筛音频：不新增字段也不需要迁移（理由见文件头）
    val audioItems = remember(state.phoneDownloadHistory) {
        state.phoneDownloadHistory.filter { it.isAudioDownload() }
    }
    var confirmClear by remember { mutableStateOf(false) }

    UfiScreenScaffold(title = "下载记录", navController = navController, showBack = true) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (audioItems.isEmpty()) {
                UfiListEmptyState(
                    text = "还没有下载记录。在音乐列表里长按一首歌，选择「下载到手机」。",
                    icon = Icons.Default.Download
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = Spacing.CardHorizontalMargin,
                        vertical = Spacing.Large
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)
                ) {
                    item {
                        UfiSectionHeader(
                            title = "最近 ${audioItems.size} 条",
                            trailing = {
                                UfiButton(
                                    text = "清空",
                                    onClick = { confirmClear = true },
                                    variant = UfiButtonVariant.Subtle,
                                    size = UfiButtonSize.Small
                                )
                            }
                        )
                    }
                    items(audioItems.size) { index ->
                        val item = audioItems[index]
                        UfiListRowCard(
                            title = item.fileName,
                            subtitle = listOf(
                                downloadStatusLabel(item.status),
                                FormatUtils.formatSize(item.fileSize),
                                formatDownloadTime(item.downloadedAt)
                            ).filter { it.isNotBlank() }.joinToString(" · ")
                        )
                    }
                    item {
                        Text(
                            /*
                             * 说明为什么要写这句：这份历史是文件链共用的（文件 / 音乐 / 图片的
                             * 「下载到手机」都往里写一条），而这里的「清空」只删音频那几条 ——
                             * 不说清楚用户会以为点了之后别的记录也没了（或者反过来，以为没清干净）。
                             */
                            text = "下载记录只保存从音乐里发起的下载；文件管理器与图片的下载不记历史。",
                            style = UfiTextStyles.caption,
                            color = palette.textSecondary,
                            modifier = Modifier.padding(top = Spacing.Small, start = Spacing.Small)
                        )
                    }
                }
            }
        }
    }

    UfiConfirmDialog(
        visible = confirmClear,
        title = "清空音频下载记录？",
        // 说清"只清这一类"：这份历史与文件/图片共用，全清会让那两类的记录一起消失
        text = "清空音乐的 ${audioItems.size} 条下载记录；" +
            "已下载的文件不会被删除。",
        confirmText = "清空",
        onConfirm = {
            confirmClear = false
            // 按扩展名过滤着删（见 FileManagerModule.clearPhoneDownloadHistory(predicate)）
            files.clearPhoneDownloadHistory { item -> item.isAudioDownload() }
        },
        onDismiss = { confirmClear = false }
    )
}


/**
 * 这条下载记录是不是"从音乐里发起的"（2026-10-21）。
 *
 * 现在**只有音乐来源会产生记录**（写入点在 `FileManagerModule.downloadFileToPhone`：
 * 非 `"audio"` 一律不写；`loadPhoneDownloadHistory` 还会把旧版遗留的其它来源一并清掉），
 * 所以判据就是 `source == "audio"` —— 不再按扩展名猜：一份 mp3 从文件管理器下载**不会**
 * 出现在这里，这正是"彻底独立"的含义。
 */
internal fun PhoneDownloadHistoryItem.isAudioDownload(): Boolean = source == "audio"

private fun downloadStatusLabel(status: String): String = when (status) {
    "completed" -> "已完成"
    "cancelled" -> "已取消"
    "error" -> "失败"
    else -> status
}

private fun formatDownloadTime(at: Long): String {
    if (at <= 0L) return ""
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))
}
