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
import androidx.compose.material.icons.filled.Info
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
 *  · **封面**：设备端预热开关/状态（ffmpeg 抽帧）；本机抽帧已于 2026-10-07 删除；
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
 * （"落点目录" → "下载落点目录"）。
 * 不要改用 `UfiGroupHeader` 挪进卡里 —— 那等于退回多项一卡的分区形态。
 *
 * ## 清单型内容一律另开页面
 * 下载队列与下载历史都搬去了 [MediaVideoDownloadHistoryScreen]。判据是**条数是否固定**：
 * 设置项的条数由代码决定（看一屏就知道全貌），而队列/历史的条数由使用量决定、还会一直长 ——
 * 留在这里就会把真正的设置项推到屏幕外。
 *
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


    // 2026-10-05 G6（FFmpeg 接入计划书 §4.2）：core 侧封面预热开关。
    // 真源在 core；null = 老版本 core 没这个键，开关禁用（不拿默认值冒充真值）。
    var prewarm by remember { mutableStateOf<Boolean?>(null) }
    var prewarmSaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        media.loadThumbPrewarm()
        prewarm = media.thumbPrewarmCurrent()
    }

    // 缓存占用要"看得见变化"：清空之后立刻重算，而不是等下次进页面
    // 2026-10-07：本机抽帧已删，封面缓存只剩设备侧一份（清空动作直接打 core 端点）。
    /** 清缓存要打一次网络（设备侧那层），按钮得有在忙的状态，否则会被连点。 */
    var clearingCache by remember { mutableStateOf(false) }
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
            // 2026-10-05 G6：core 侧 ffmpeg 封面预热（闲时后台把整库封面铺满，列表秒出）
            UfiSettingsRowCard {
                UfiSettingsToggle(
                    title = "设备后台预热封面（实验性）",
                    description = if (prewarm == null) {
                        "当前设备版本的 core 不支持此开关。"
                    } else {
                        "开启后设备在充电或电量充足（>30%）时用 ffmpeg 逐个补齐视频封面，" +
                            "打开列表时全部秒出。软解较吃 CPU，低配设备建议保持关闭。"
                    },
                    checked = prewarm == true,
                    icon = Icons.Default.History,
                    enabled = prewarm != null && !prewarmSaving,
                    onCheckedChange = { checked ->
                        prewarmSaving = true
                        scope.launch {
                            val err = media.setThumbPrewarm(checked)
                            prewarm = media.thumbPrewarmCurrent()
                            prewarmSaving = false
                            err?.let {
                                android.widget.Toast.makeText(
                                    context, it, android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )
            }
            /*
             * 2026-10-07 重做：之前这里只有一行字，点完「立即预热」退出页面再进来就什么都没了。
             * 现在三件事都补上：
             *  ① 进页面先 GET 一次快照（core 落盘的进度），所以退出重进仍能看到上一轮结果；
             *  ② 进度条 + 「正在处理 xxx.mkv」，running 时才画；
             *  ③ 轮询兜底：running 时每 3s 拉一次快照，不依赖 WS 是否连着。
             */
            UfiSettingsRowCard {
                val progress by media.thumbPrewarmProgress.collectAsState()
                var prewarmRunning by remember { mutableStateOf(false) }
                // 页面存活期间每 3s 拉一次快照：running → 看进度；非 running → 一次即止
                LaunchedEffect(Unit) {
                    while (true) {
                        media.refreshPrewarmState()
                        if (media.thumbPrewarmProgress.value?.running != true) break
                        kotlinx.coroutines.delay(3_000)
                    }
                }
                UfiSettingsItem(
                    title = when {
                        progress?.running == true ->
                            "预热中 ${progress!!.done}/${progress!!.total}" +
                                (progress!!.failed.takeIf { it > 0 }?.let { "（失败 $it）" } ?: "")
                        progress != null ->
                            "上次预热：新增 ${progress!!.done} / 失败 ${progress!!.failed} / 共 ${progress!!.total}"
                        else -> "预热状态：暂无记录"
                    },
                    description = when {
                        progress?.running == true && progress!!.currentName.isNotBlank() ->
                            "正在处理：${progress!!.currentName}"
                        progress?.running == true -> "正在处理…"
                        progress != null ->
                            "详细日志在设备的 /sdcard/Download/UFI-AXIS/log/core/ 当天目录（搜 ffmpeg 或 ThumbPrewarm）。"
                        else -> "还没有跑过预热。点右侧按钮立即跑一轮。"
                    },
                    icon = Icons.Default.Info,
                    trailing = {
                        UfiButton(
                            text = if (prewarmRunning) "已触发" else "立即预热",
                            onClick = {
                                if (!prewarmRunning) {
                                    prewarmRunning = true
                                    scope.launch {
                                        val (ok, msg) = media.runPrewarmNow()
                                        if (!ok) {
                                            android.widget.Toast.makeText(
                                                context, msg, android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            // 受理后立刻拉一次快照，让 UI 马上切到「预热中」
                                            media.refreshPrewarmState()
                                        }
                                        kotlinx.coroutines.delay(2000)
                                        prewarmRunning = false
                                    }
                                }
                            },
                            variant = UfiButtonVariant.Subtle,
                            size = UfiButtonSize.Small,
                            enabled = prewarm == true
                        )
                    }
                )
                val p = media.thumbPrewarmProgress.collectAsState().value
                if (p != null && p.total > 0) {
                    UfiCompactProgressBar(
                        progress = (p.done + p.failed).toFloat() / p.total,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
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
                                    /*
                                     * 两层都得清（本机抽帧目录已随该功能删除）：
                                     *  1. 图片加载库：按 URL 命中，而缩略图 URL 只含 (type,id)、
                                     *     core 又给 max-age=86400 → 一天内根本不重新发请求；
                                     *  2. 设备侧缓存：core 的 /thumbnail 命中即原样返回，
                                     *     覆盖安装 core 也不会清掉它。
                                     */
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
