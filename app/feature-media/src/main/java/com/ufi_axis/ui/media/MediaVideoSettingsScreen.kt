package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Image
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
import com.ufi_axis.data.download.MediaDownloadQueue
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsChevron
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 视频页设置（右上角齿轮进来）—— **入口列表页**（2026-10-14 由"一页装全部"拆成入口 + 三个子页）。
 *
 * ## 为什么拆
 * 这一页此前把四件互不相干的事装在同一屏：扫描范围（core 侧配置）、封面预热（开关 + 进度 +
 * 轮询）、封面缓存（清空动作）、下载（落点说明 + 队列入口）+ 最近播放。它们的**刷新节奏完全不同**
 * —— 预热要每 3s 拉一次快照，其余三项一辈子不用轮询；结果就是"只想清一次封面缓存"也要
 * 先滚过预热进度条。一页一件事之后，每页只拉自己那份数据。
 *
 * ## 本页只做入口
 * 一行一个子页（[UfiSettingsItem] + [UfiSettingsChevron]），行内描述带上"点进去能看到什么"的
 * 即时状态（扫描目录数 / 预热开关 / 队列与历史条数）—— 不带信息的死入口与带信息的入口
 * 在界面上分不出来。
 *
 * ## 为什么保留 [Routes.MEDIA_VIDEO_SETTINGS] 这条路由
 * 视频页工具条那颗齿轮指向的就是它。路由不变 ⇒ 调用点不动；三个子页各自一条新路由，
 * 返回一律回到本页（再由本页返回视频页）。
 *
 * ## 数据加载
 * 本页仍照原样调 `media.loadStatus()` / `MediaDownloadQueue.ensureLoaded()` /
 * `media.loadThumbPrewarm()`（幂等，且都带新鲜度或内存缓存）：三行入口的描述分别要用
 * 扫描目录、队列与历史条数、预热开关态，不拉就是三行静态字。子页各自再拉自己需要的那一份。
 *
 * ## 页壳与卡形态（2026-09-20 对齐全站标准设置页）
 * `UfiScreenScaffold` + [UfiPageBackground] + **一项一张** `UfiSettingsRowCard`，与外观 /
 * 告警 / 后台守护 / 监控四页同构。[UfiPageBackground] 自带滚动；卡间距由内容最外层这层
 * `Column` 用 `Spacing.SettingsCardGap`（10dp）统一给（与网络设置一致），
 * 水平留白由卡片自己带 —— 页面里不要再套 `verticalScroll`、不要再加水平内距、
 * **卡之间也不要手加 Spacer**。
 *
 * 本页一处 `UfiSettingsGroup`（多项一卡）都不留，也**不要**改用 `UfiGroupHeader`
 * 把几行入口并回一张卡 —— 那等于退回多项一卡的分区形态。
 */
@Composable
fun MediaVideoSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val context = LocalContext.current
    val media = viewModel.media

    // 2026-10-05 G6（FFmpeg 接入计划书 §4.2）：core 侧封面预热开关。
    // 真源在 core；null = 老版本 core 没这个键，入口行的描述不能拿默认值冒充真值。
    var prewarm by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) { media.loadStatus() }
    LaunchedEffect(Unit) { MediaDownloadQueue.ensureLoaded(context) }
    LaunchedEffect(Unit) {
        media.loadThumbPrewarm()
        prewarm = media.thumbPrewarmCurrent()
    }

    val mediaState by media.state.collectAsState()
    val queue by MediaDownloadQueue.queue.collectAsState()
    val history by MediaDownloadQueue.history.collectAsState()
    val scanDirs = mediaState.tab(MEDIA_TYPE_VIDEO).scanDirs

    UfiScreenScaffold(title = "视频设置", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 卡间距与网络设置页一致（10dp / Spacing.SettingsCardGap）：平级单行卡靠间距分隔
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)) {

                // ── 扫描范围 ──（与音乐页共用同一个组件，只有收尾动作不同）
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "扫描范围",
                        description = when {
                            scanDirs.isEmpty() -> "设备侧扫描整个媒体库；可指定目录或让设备重新收录"
                            scanDirs.size == 1 -> "设备侧只扫 ${scanDirs.first().substringAfterLast('/')}；可改目录或重新收录"
                            else -> "设备侧扫描 ${scanDirs.size} 个目录；可改目录或重新收录"
                        },
                        icon = Icons.Default.FolderOpen,
                        trailing = { UfiSettingsChevron() },
                        onClick = { navController.navigate(Routes.MEDIA_VIDEO_SETTINGS_SCAN) }
                    )
                }

                // ── 播放与预热 ──
                // 预热开关与"立即预热"必须同页：开关关着时那颗按钮是禁用的，
                // 两者分开会让人在另一页对着灰按钮找原因。
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "播放与预热",
                        description = when (prewarm) {
                            null -> "设备端不支持后台预热封面；最近播放记在本机"
                            true -> "后台预热封面已开启；最近播放记在本机"
                            false -> "后台预热封面已关闭；最近播放记在本机"
                        },
                        icon = Icons.Default.History,
                        trailing = { UfiSettingsChevron() },
                        onClick = { navController.navigate(Routes.MEDIA_VIDEO_SETTINGS_PLAYBACK) }
                    )
                }

                // ── 显示与字幕（2026-10-21）──
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "显示与字幕",
                        description = "海报墙列数、字幕显示与样式。",
                        icon = Icons.Default.Subtitles,
                        trailing = { UfiSettingsChevron() },
                        onClick = { navController.navigate(Routes.MEDIA_VIDEO_SETTINGS_DISPLAY) }
                    )
                }

                // ── 最近播放（2026-10-21）──
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "最近播放",
                        description = "本机记录，最多 6 条，可逐条续播。",
                        icon = Icons.Default.History,
                        trailing = { UfiSettingsChevron() },
                        onClick = { navController.navigate(Routes.MEDIA_VIDEO_RECENT) }
                    )
                }

                // ── 封面与下载 ──
                // 队列与历史本身不在本页（见 [MediaVideoDownloadHistoryScreen]），这里只显示条数 + 入口。
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "封面与下载",
                        description = when {
                            queue.isEmpty() && history.isEmpty() ->
                                "封面缓存、下载落点目录与下载任务"
                            queue.isEmpty() ->
                                "封面缓存与下载落点目录 · 历史 ${history.size} 条"
                            else ->
                                "封面缓存与下载落点目录 · 队列 ${queue.size} 个 / 历史 ${history.size} 条"
                        },
                        icon = Icons.Default.Image,
                        trailing = { UfiSettingsChevron() },
                        onClick = { navController.navigate(Routes.MEDIA_VIDEO_SETTINGS_COVERS) }
                    )
                }
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}
