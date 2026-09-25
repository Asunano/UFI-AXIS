package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
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
import coil3.SingletonImageLoader
import androidx.navigation.NavHostController
import com.ufi_axis.data.download.MediaDownloadQueue
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiCompactProgressBar
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsChevron
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.components.common.UfiSettingsToggle
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 视频页设置（右上角齿轮进来）。
 *
 * 收进这一页的都是"配一次就不动"的东西 —— 摆在浏览界面上只会挤掉内容：
 *  · **扫描目录**：设备侧配置，写 core（按类型各一份），UI 走共用件 [MediaScanScopeSection]；
 *  · **本机抽帧**开关、封面缓存、批量生成：缩略图这一件事的三个面；
 *  · **下载**：落点目录（只读说明）+ 通往 [MediaVideoDownloadHistoryScreen] 的入口。
 *
 * ## 页壳与卡形态（2026-09-20 对齐全站标准设置页）
 * `UfiScreenScaffold` + [UfiPageBackground] + **一项一张** `UfiSettingsRowCard`，与外观 /
 * 告警 / 后台守护 / 监控四页同构。[UfiPageBackground] 自带滚动与 16dp 卡间距，
 * 水平留白由卡片自己带 —— 页面里不要再套 `verticalScroll`、不要再加水平内距、
 * **卡之间也不要手加 Spacer**。
 *
 * 本页一处 `UfiSettingsGroup`（多项一卡）都不留：用户反馈明确说分区组件不美观，
 * 而"同一件事的两个面"这种理由在界面上看不出来，只体现在代码注释里。
 *
 * 同理，2026-09-22 起本页**卡外区块标题（`UfiSectionHeader`）一处不留**。原来有四个
 * （视频封面 / 批量生成封面 / 下载到手机 / 其他），加上共用件带的"扫描范围"共五行；
 * 它们只是把下面那张卡的 `title` 换个说法再说一遍，还各自在卡片节奏上插了 8dp 的断点。
 * 撤掉之后信息没有丢：少数原本靠区块标题才说得清的行，标题自己补全了
 * （"落点目录" → "下载落点目录"，"为缺封面的视频抽帧" → "…批量抽帧"）。
 * 不要改用 `UfiGroupHeader` 挪进卡里 —— 那等于退回多项一卡的分区形态。
 *
 * ## 清单型内容一律另开页面
 * 下载队列与下载历史都搬去了 [MediaVideoDownloadHistoryScreen]。判据是**条数是否固定**：
 * 设置项的条数由代码决定（看一屏就知道全貌），而队列/历史的条数由使用量决定、还会一直长 ——
 * 留在这里就会把真正的设置项推到屏幕外。
 *
 * ## 为什么开关是"真开关"
 * [AppPreferences.mediaPhoneFrameExtraction] 关掉之后：列表不再触发抽帧
 * （[MediaThumbnailBuilder.build] 第一件事就是查它），批量入口一并禁用。
 * 不存在"关了还在偷偷抽"或"关了但按钮还能点"的情况。
 */
@Composable
fun MediaVideoSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val context = LocalContext.current
    val media = viewModel.media
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { media.loadStatus() }
    LaunchedEffect(Unit) { MediaDownloadQueue.ensureLoaded(context) }

    var frameExtraction by remember {
        mutableStateOf(
            runCatching { AppPreferences(context).mediaPhoneFrameExtraction }.getOrDefault(true)
        )
    }

    // 缓存占用要"看得见变化"：清空之后立刻重算，而不是等下次进页面
    var cacheStats by remember { mutableStateOf(0 to 0L) }
    var statsVersion by remember { mutableStateOf(0) }
    /** 清缓存要打一次网络（设备侧那层），按钮得有在忙的状态，否则会被连点。 */
    var clearingCache by remember { mutableStateOf(false) }
    LaunchedEffect(statsVersion) {
        cacheStats = runCatching { MediaThumbnailBuilder.cacheStats(context) }.getOrDefault(0 to 0L)
    }

    val batch by MediaThumbnailBuilder.batch.collectAsState()
    val queue by MediaDownloadQueue.queue.collectAsState()
    val history by MediaDownloadQueue.history.collectAsState()

    var recentCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        recentCount = runCatching { AppPreferences(context).mediaRecentPlays().size }.getOrDefault(0)
    }

    UfiScreenScaffold(title = "视频设置", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // ── 扫描范围 ──（与音乐页共用同一个组件，只有收尾动作不同）
            MediaScanScopeSection(
                type = MEDIA_TYPE_VIDEO,
                typeLabel = mediaTypeLabel(MEDIA_TYPE_VIDEO),
                viewModel = viewModel,
                onDirsChanged = {
                    // 视频页的收尾是重拉**文件夹视图**：目录变了，文件夹树与首层列表都不再成立。
                    // 音乐页那边要作废的是三个维度的分组缓存 —— 两者各自都对，不要统一。
                    media.browse(MEDIA_TYPE_VIDEO, null, force = true)
                }
            )

            // ── 缩略图 ──
            /*
             * 抽帧开关与封面缓存曾经合在一张卡里（理由是"同一件事的两个面"）。
             * 那个理由只在代码里成立：界面上它们一个是开关、一个带清空按钮，
             * 合卡之后与本页其余一项一卡的节奏对不上。现在各自一卡。
             */
            UfiSettingsRowCard {
                UfiSettingsToggle(
                    title = "用本机抽帧生成封面",
                    description = "设备端解不出画面时，由手机抽一帧并回传设备（局域网传输，" +
                        "每个视频只需一次）。关掉后只显示设备能给出的封面。",
                    checked = frameExtraction,
                    icon = Icons.Default.Videocam,
                    onCheckedChange = { checked ->
                        frameExtraction = checked
                        runCatching {
                            AppPreferences(context).mediaPhoneFrameExtraction = checked
                        }
                        if (!checked) MediaThumbnailBuilder.cancelBatch()
                    }
                )
            }
            UfiSettingsRowCard {
                UfiSettingsItem(
                    title = "封面缓存",
                    description = "本机已缓存 ${cacheStats.first} 张 · " +
                        FormatUtils.formatSize(cacheStats.second) +
                        "（清空会一并丢掉设备上那份与图片加载缓存，下次浏览重新抽帧）",
                    icon = Icons.Default.Image,
                    trailing = {
                        UfiButton(
                            text = if (clearingCache) "清理中…" else "清空",
                            onClick = {
                                if (clearingCache) return@UfiButton
                                clearingCache = true
                                scope.launch {
                                    /*
                                     * 三层都得清，少一层就等于没清 —— 这是"改了抽帧策略却看不到变化"的根因：
                                     *  1. 图片加载库：按 URL 命中，而缩略图 URL 只含 (type,id)、
                                     *     core 又给 max-age=86400 → 一天内根本不重新发请求；
                                     *  2. 本机抽帧成果：MediaThumbnailBuilder 的目录；
                                     *  3. 设备上客户端回传的成果：core 的 /thumbnail 命中即原样返回，
                                     *     覆盖安装 core 也不会清掉它。
                                     */
                                    MediaThumbnailBuilder.clearCache(context)
                                    media.clearRemoteThumbnails(MEDIA_TYPE_VIDEO)
                                    SingletonImageLoader.get(context).let { loader ->
                                        loader.memoryCache?.clear()
                                        loader.diskCache?.clear()
                                    }
                                    statsVersion++
                                    clearingCache = false
                                }
                            },
                            variant = UfiButtonVariant.Subtle,
                            size = UfiButtonSize.Small
                        )
                    }
                )
            }

            // 批量生成：可暂停 / 继续 / 取消，退出这一页仍在跑
            BatchThumbSection(
                enabled = frameExtraction,
                progress = batch,
                onStart = {
                    scope.launch {
                        val all = media.allItems(MEDIA_TYPE_VIDEO)
                        MediaThumbnailBuilder.startBatch(context, media, MEDIA_TYPE_VIDEO, all)
                    }
                },
                onPause = { MediaThumbnailBuilder.pauseBatch() },
                onResume = { MediaThumbnailBuilder.resumeBatch() },
                onCancel = {
                    MediaThumbnailBuilder.cancelBatch()
                    statsVersion++
                }
            )

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

            // ── 最近播放 ──
            UfiSettingsRowCard {
                UfiSettingsItem(
                    title = "最近播放",
                    description = "首页那条横向列表，最多 6 条；记在本机，不上传设备",
                    trailing = {
                        UfiButton(
                            text = "清空（$recentCount）",
                            onClick = {
                                runCatching { AppPreferences(context).clearMediaRecentPlays() }
                                recentCount = 0
                            },
                            variant = UfiButtonVariant.Subtle,
                            size = UfiButtonSize.Small,
                            enabled = recentCount > 0
                        )
                    }
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

/**
 * 批量生成缩略图 —— 一项一卡，和本页其余设置行同构。
 *
 * 2026-09-20 从手写的 `Text(cardTitle) + Spacer + Text(note) + Row{按钮}` 换成
 * [UfiSettingsItem]：原来那份自己搓标题字号与行距，和同一页上下的文字不是同一套字。
 * 状态说明进 `description`、按钮组进 `trailing`，语义各归其位。
 *
 * 2026-09-22 撤掉上方的 `UfiSectionHeader("批量生成封面")`：本页不再用卡外标题，
 * 卡内 `title` 已经说清这张卡是干什么的。
 *
 * 按钮组随状态变（没跑 → 开始；在跑 → 暂停 + 取消；暂停中 → 继续 + 取消；跑完 → 再来一次），
 * 不摆按不动的按钮。已经有缓存的会被跳过，所以"再来一次"只处理剩下的那些。
 *
 * 进度条排在设置行**下方**、同一张卡内：它是通栏的，塞进 `trailing` 会与按钮抢那一小块宽度。
 */
@Composable
private fun BatchThumbSection(
    enabled: Boolean,
    progress: MediaThumbnailBuilder.BatchProgress?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    UfiSettingsRowCard {
        UfiSettingsItem(
            title = "为缺封面的视频批量抽帧",
            description = when {
                !enabled -> "本机抽帧已关闭，批量生成不可用"
                progress == null -> "为还没有封面的视频逐个抽帧。可以随时暂停，退出这一页也会继续跑。"
                progress.finished -> "已完成 ${progress.done}/${progress.total}" +
                    if (progress.failed > 0) "，其中 ${progress.failed} 个失败" else ""
                progress.paused -> "已暂停：${progress.done}/${progress.total}"
                else -> "正在生成：${progress.done}/${progress.total}" +
                    if (progress.failed > 0) "（失败 ${progress.failed}）" else ""
            },
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    when {
                        progress == null || progress.finished -> UfiButton(
                            text = if (progress?.finished == true) "再来一次" else "开始",
                            onClick = onStart,
                            variant = UfiButtonVariant.Subtle,
                            size = UfiButtonSize.Small,
                            enabled = enabled
                        )

                        progress.paused -> {
                            UfiButton(
                                text = "继续",
                                onClick = onResume,
                                variant = UfiButtonVariant.Subtle,
                                size = UfiButtonSize.Small
                            )
                            UfiButton(
                                text = "取消",
                                onClick = onCancel,
                                variant = UfiButtonVariant.Subtle,
                                size = UfiButtonSize.Small
                            )
                        }

                        else -> {
                            UfiButton(
                                text = "暂停",
                                onClick = onPause,
                                variant = UfiButtonVariant.Subtle,
                                size = UfiButtonSize.Small
                            )
                            UfiButton(
                                text = "取消",
                                onClick = onCancel,
                                variant = UfiButtonVariant.Subtle,
                                size = UfiButtonSize.Small
                            )
                        }
                    }
                }
            }
        )
        if (progress != null && progress.total > 0) {
            UfiCompactProgressBar(
                progress = progress.done.toFloat() / progress.total,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
