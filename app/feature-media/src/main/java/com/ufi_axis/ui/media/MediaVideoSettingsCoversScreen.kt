package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import coil3.SingletonImageLoader
import com.ufi_axis.data.download.MediaDownloadQueue
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsChevron
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 视频设置 → **封面与下载**（2026-10-14 从 `MediaVideoSettingsScreen` 拆出，内容原样搬运）。
 *
 * 三张卡：
 *  · **封面缓存**：清空设备上的封面缓存 + 图片加载缓存（清完下次浏览重新抽帧）；
 *  · **下载落点目录**：只读说明（内部存储 / [MediaDownloadQueue.RELATIVE_DIR]）；
 *  · **下载任务**：队列 + 历史的入口行，描述里带条数，让"要不要点进去"在这一页就能判断 ——
 *    否则这一行与不带信息的死入口无法区分。
 *
 * ## 本页自己的数据加载
 * `MediaDownloadQueue.ensureLoaded(context)` + `queue` / `history` 两条流：
 * 入口行上的条数要靠它们。队列与历史本身在 [MediaVideoDownloadHistoryScreen]，
 * 判据是**条数是否固定** —— 设置项的条数写死在代码里，而队列与历史的条数由使用量决定。
 *
 * ## 清缓存为什么清两层
 *  · 图片加载库：按 URL 命中，而缩略图 URL 只含 (type,id)、core 又给 max-age=86400 →
 *    一天内根本不重新发请求；
 *  · 设备侧缓存：core 的 `/thumbnail` 命中即原样返回，覆盖安装 core 也不会清掉它。
 * 本机抽帧目录已随该功能（2026-10-07）删除，所以这里只剩这两层。
 *
 * ## 版式
 * 一项一张 `UfiSettingsRowCard`，间距走 [Spacing.SettingsCardGap]；**不要**用 `UfiGroupHeader`
 * 把这几项并回一张卡。
 */
@Composable
fun MediaVideoSettingsCoversScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val context = LocalContext.current
    val media = viewModel.media
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { MediaDownloadQueue.ensureLoaded(context) }

    /** 清缓存要打一次网络（设备侧那层），按钮得有在忙的状态，否则会被连点。 */
    var clearingCache by remember { mutableStateOf(false) }
    val queue by MediaDownloadQueue.queue.collectAsState()
    val history by MediaDownloadQueue.history.collectAsState()

    UfiScreenScaffold(title = "封面与下载", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)) {
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "封面缓存",
                        description = "清空设备上的封面缓存与图片加载缓存，下次浏览重新抽帧",
                        icon = Icons.Default.Image,
                        trailing = {
                            UfiButton(
                                text = if (clearingCache) "清理中…" else "清空",
                                onClick = {
                                    if (clearingCache) return@UfiButton
                                    clearingCache = true
                                    scope.launch {
                                        media.clearRemoteThumbnails(MEDIA_TYPE_VIDEO)
                                        SingletonImageLoader.get(context).let { loader ->
                                            loader.memoryCache?.clear()
                                            loader.diskCache?.clear()
                                        }
                                        clearingCache = false
                                    }
                                },
                                variant = UfiButtonVariant.Subtle,
                                size = UfiButtonSize.Small
                            )
                        }
                    )
                }

                // ── 下载 ──
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "下载落点目录",
                        description = "内部存储 / ${MediaDownloadQueue.RELATIVE_DIR}" +
                            "（源目录结构会原样带上，同名文件不会互相覆盖）",
                        icon = Icons.Default.Download
                    )
                }

                /*
                 * 队列与历史都不在本页了（见 [MediaVideoDownloadHistoryScreen]），这里只留一个入口行。
                 * 描述里带上条数，是为了让"要不要点进去"在设置页上就能判断 ——
                 * 否则这一行与不带信息的死入口无法区分。
                 */
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "下载任务",
                        description = if (queue.isEmpty()) {
                            "${history.size} 条记录"
                        } else {
                            "队列 ${queue.size} 个 · 历史 ${history.size} 条"
                        },
                        icon = Icons.Default.History,
                        trailing = { UfiSettingsChevron() },
                        onClick = { navController.navigate(Routes.MEDIA_VIDEO_DOWNLOADS) }
                    )
                }
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}
