package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.download.MediaDownloadQueue
import com.ufi_axis.data.download.MediaDownloadWorker
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiCompactProgressBar
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDivider
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSectionHeader
import com.ufi_axis.ui.components.common.UfiSettingsGroup
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsToggle
import com.ufi_axis.ui.components.common.UfiSettingsValue
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.module.MediaModule
import com.ufi_axis.viewmodel.state.MediaTabState
import kotlinx.coroutines.launch

/**
 * 视频页设置（右上角齿轮进来）。
 *
 * 收进这一页的都是"配一次就不动"或"偶尔来看一眼"的东西 —— 摆在浏览界面上只会挤掉内容：
 *  · **扫描目录**：设备侧配置，写 core（按类型各一份）；
 *  · **本机抽帧**开关 + 批量生成 + 缓存占用：都是缩略图这一件事的三个面；
 *  · **下载**：队列（可取消/重试）+ 历史 + 落点说明。
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
    val palette = LocalResolvedPalette.current
    val context = LocalContext.current
    val media = viewModel.media
    val scope = rememberCoroutineScope()
    val state by media.state.collectAsState()
    val tab = state.tab(MEDIA_TYPE_VIDEO)

    LaunchedEffect(Unit) { media.loadStatus() }
    LaunchedEffect(Unit) { MediaDownloadQueue.ensureLoaded(context) }
    // FFmpeg 组件状态：进页面拉一次（用 core 缓存，不打外网）
    LaunchedEffect(Unit) { media.loadFfmpegComponent() }

    val ffmpegComponent = state.ffmpegComponent
    val ffmpegTask = state.ffmpegTask
    var pendingFfmpegUninstall by remember { mutableStateOf(false) }

    // 安装进度只在有任务时 1s 轮询；落终态后 refreshFfmpegTask 会自己补拉完整状态
    LaunchedEffect(ffmpegTask.active) {
        while (ffmpegTask.active) {
            kotlinx.coroutines.delay(1000)
            media.refreshFfmpegTask()
        }
    }


    var frameExtraction by remember {
        mutableStateOf(
            runCatching { AppPreferences(context).mediaPhoneFrameExtraction }.getOrDefault(true)
        )
    }
    var showDirPicker by remember { mutableStateOf(false) }

    // FFmpeg 检测：状态提升到这里，弹窗不能放在 UfiSettingsGroup 内部（会被裁剪）
    var ffmpegLoading by remember { mutableStateOf(false) }
    var ffmpegResultOk by remember { mutableStateOf<Boolean?>(null) }
    var ffmpegDialogText by remember { mutableStateOf<String?>(null) }

    // 缓存占用要"看得见变化"：清空之后立刻重算，而不是等下次进页面
    var cacheStats by remember { mutableStateOf(0 to 0L) }
    var statsVersion by remember { mutableStateOf(0) }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
            verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
        ) {
            // ── 扫描范围 ──
            UfiSectionHeader(title = "扫描范围")
            UfiSettingsGroup {
                UfiSettingsValue(
                    title = "扫描目录",
                    description = "设备侧配置，只影响视频；留空 = 整个媒体库",
                    value = when {
                        tab.scanDirs.isEmpty() -> "整个媒体库"
                        tab.scanDirs.size == 1 -> tab.scanDirs.first().substringAfterLast('/')
                        else -> "${tab.scanDirs.size} 个目录"
                    },
                    icon = Icons.Default.FolderOpen,
                    onClick = { showDirPicker = true }
                )
                UfiSettingsItem(
                    title = "重新扫描",
                    description = "请设备重新收录这些目录。收录是异步的，稍后回列表下拉即可看到新文件",
                    icon = Icons.Default.Refresh,
                    enabled = !tab.isRescanning,
                    onClick = { media.rescan(MEDIA_TYPE_VIDEO) }
                )
            }

            // ── 缩略图 ──
            UfiSectionHeader(title = "视频封面")
            UfiSettingsGroup {
                // FFmpeg 组件：装了设备才能自己软解出封面，不装就只能靠手机抽帧回传。
                // 入口放这里而不是隧道设置页的「核心组件」—— 它只服务视频封面这一件事。
                UfiSettingsItem(
                    title = "FFmpeg 组件（设备端）",
                    description = ffmpegComponentDetail(ffmpegComponent),
                    icon = Icons.Default.Extension,
                    iconTint = if (ffmpegComponent?.installed == true) palette.success else Color.Unspecified,
                    trailing = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (ffmpegComponent == null || !ffmpegComponent.installed || ffmpegComponent.updateAvailable) {
                                UfiButton(
                                    text = if (ffmpegComponent?.installed == true) "更新" else "下载安装",
                                    onClick = { media.installFfmpegComponent() },
                                    enabled = ffmpegComponent?.available == true && !ffmpegTask.active,
                                    variant = UfiButtonVariant.Subtle,
                                    size = UfiButtonSize.Small
                                )
                            }
                            if (ffmpegComponent?.installed == true) {
                                UfiButton(
                                    text = "卸载",
                                    onClick = { pendingFfmpegUninstall = true },
                                    enabled = !ffmpegTask.active,
                                    variant = UfiButtonVariant.Subtle,
                                    size = UfiButtonSize.Small
                                )
                            }
                        }
                    }
                )
                // 进度只在"这个组件正在装"时出现，空闲态不留占位行（行高才与同组其它行对齐）
                if (ffmpegTask.id == MediaModule.COMPONENT_FFMPEG && ffmpegTask.active) {
                    if (ffmpegTask.message.isNotBlank()) {
                        Text(
                            ffmpegTask.message,
                            style = UfiTextStyles.note,
                            color = palette.accent,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    UfiCompactProgressBar(
                        progress = ffmpegTask.percent / 100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                UfiDivider()
                FfmpegProbeCard(
                    viewModel = viewModel,
                    tab = tab,
                    loading = ffmpegLoading,
                    onLoadingChange = { ffmpegLoading = it },
                    resultOk = ffmpegResultOk,
                    onResult = { ok, text ->
                        ffmpegResultOk = ok
                        ffmpegDialogText = text
                    }
                )
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
                UfiSettingsItem(
                    title = "封面缓存",
                    description = "已缓存 ${cacheStats.first} 张 · ${FormatUtils.formatSize(cacheStats.second)}" +
                        "（本机抽的那份；设备与网页端各自还有一份）",
                    icon = Icons.Default.Image,
                    trailing = {
                        UfiButton(
                            text = "清空",
                            onClick = {
                                MediaThumbnailBuilder.clearCache(context)
                                statsVersion++
                            },
                            variant = UfiButtonVariant.Subtle,
                            size = UfiButtonSize.Small
                        )
                    }
                )
            }

            // 批量生成：可暂停 / 继续 / 取消，退出这一页仍在跑
            BatchThumbCard(
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
            UfiSectionHeader(title = "下载到手机")
            UfiSettingsGroup {
                UfiSettingsItem(
                    title = "落点目录",
                    description = "内部存储 / ${MediaDownloadQueue.RELATIVE_DIR}" +
                        "（源目录结构会原样带上，同名文件不会互相覆盖）",
                    icon = Icons.Default.Download
                )
            }

            if (queue.isNotEmpty()) {
                UfiSectionHeader(
                    title = "队列（${queue.size}）",
                    trailing = {
                        UfiButton(
                            text = "全部取消",
                            onClick = {
                                MediaDownloadWorker.stop(context)
                                MediaDownloadQueue.clearQueue()
                            },
                            variant = UfiButtonVariant.Subtle,
                            size = UfiButtonSize.Small
                        )
                    }
                )
                UfiSettingsGroup {
                    queue.forEach { task ->
                        DownloadTaskRow(
                            task = task,
                            onRetry = {
                                MediaDownloadQueue.retry(task.path)
                                MediaDownloadWorker.kick(context)
                            },
                            onRemove = { MediaDownloadQueue.remove(task.path) }
                        )
                    }
                }
            }

            UfiSectionHeader(
                title = "下载历史",
                trailing = if (history.isEmpty()) {
                    null
                } else {
                    {
                        UfiButton(
                            text = "清空",
                            onClick = { MediaDownloadQueue.clearHistory() },
                            variant = UfiButtonVariant.Subtle,
                            size = UfiButtonSize.Small
                        )
                    }
                }
            )
            UfiSettingsGroup {
                if (history.isEmpty()) {
                    Text(
                        "还没有下载记录",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(vertical = Spacing.Small)
                    )
                } else {
                    history.take(HISTORY_SHOWN).forEach { item ->
                        UfiSettingsItem(
                            title = item.name,
                            description = listOf(
                                if (item.ok) "已完成" else "失败：${item.message}",
                                FormatUtils.formatSize(item.size),
                                FormatUtils.formatTimestamp(item.at)
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            titleMaxLines = 1
                        )
                    }
                }
            }

            // ── 最近播放 ──
            UfiSectionHeader(title = "其他")
            UfiSettingsGroup {
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

    MediaScanDirsDialog(
        visible = showDirPicker,
        typeLabel = mediaTypeLabel(MEDIA_TYPE_VIDEO),
        initial = tab.scanDirs,
        browse = { path -> media.browseDirs(path) },
        onDismiss = { showDirPicker = false },
        onConfirm = { dirs ->
            showDirPicker = false
            media.setScanDirs(MEDIA_TYPE_VIDEO, dirs)
            media.browse(MEDIA_TYPE_VIDEO, null, force = true)
        }
    )

    // 卸载要确认：重装得再走一次几十 MB 的下载，误触代价不小
    UfiConfirmDialog(
        visible = pendingFfmpegUninstall,
        title = "卸载 FFmpeg 组件",
        text = "卸载后设备将无法自行解出视频封面，只能靠手机抽帧回传。重新安装需再下载约 " +
            FormatUtils.formatSize(ffmpegComponent?.downloadSize ?: 0L) + "。",
        confirmText = "卸载",
        destructive = true,
        onConfirm = {
            pendingFfmpegUninstall = false
            media.uninstallFfmpegComponent()
        },
        onDismiss = { pendingFfmpegUninstall = false }
    )


    // FFmpeg 检测结果弹窗 —— 必须放在 UfiScreenScaffold 之外（同级），
    // 否则会被 UfiSettingsGroup 的卡片裁剪吞掉。
    if (ffmpegDialogText != null) {
        val clipboard = LocalClipboardManager.current
        UfiCustomDialog(
            visible = true,
            onDismiss = { ffmpegDialogText = null },
            title = "FFmpeg 检测结果",
            confirmButton = {
                UfiButton(
                    text = "复制",
                    onClick = {
                        clipboard.setText(AnnotatedString(ffmpegDialogText.orEmpty()))
                    },
                    variant = UfiButtonVariant.Subtle,
                    size = UfiButtonSize.Small
                )
            },
            dismissButton = {
                UfiButton(
                    text = "关闭",
                    onClick = { ffmpegDialogText = null },
                    variant = UfiButtonVariant.Subtle,
                    size = UfiButtonSize.Small
                )
            }
        ) {
            Text(
                text = ffmpegDialogText.orEmpty(),
                style = UfiTextStyles.monoReadout,
                color = palette.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

/**
 * 批量生成缩略图。
 *
 * 按钮组随状态变（没跑 → 开始；在跑 → 暂停 + 取消；暂停中 → 继续 + 取消；跑完 → 再来一次），
 * 不摆按不动的按钮。已经有缓存的会被跳过，所以"再来一次"只处理剩下的那些。
 */
@Composable
private fun BatchThumbCard(
    enabled: Boolean,
    progress: MediaThumbnailBuilder.BatchProgress?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    UfiSettingsGroup {
        Text("批量生成封面", style = UfiTextStyles.cardTitle, color = palette.accent)
        Spacer(Modifier.height(Spacing.Small))
        Text(
            when {
                !enabled -> "本机抽帧已关闭，批量生成不可用"
                progress == null -> "为还没有封面的视频逐个抽帧。可以随时暂停，退出这一页也会继续跑。"
                progress.finished -> "已完成 ${progress.done}/${progress.total}" +
                    if (progress.failed > 0) "，其中 ${progress.failed} 个失败" else ""
                progress.paused -> "已暂停：${progress.done}/${progress.total}"
                else -> "正在生成：${progress.done}/${progress.total}" +
                    if (progress.failed > 0) "（失败 ${progress.failed}）" else ""
            },
            style = UfiTextStyles.note,
            color = palette.textSecondary
        )
        if (progress != null && progress.total > 0) {
            Spacer(Modifier.height(Spacing.Small))
            UfiCompactProgressBar(
                progress = progress.done.toFloat() / progress.total,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(Spacing.Small))
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
}

/** 队列里的一条：名称 + 进度 / 错误 + 重试（仅失败时）与移除。 */
@Composable
private fun DownloadTaskRow(
    task: MediaDownloadQueue.Task,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                task.name,
                style = UfiTextStyles.body,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when (task.status) {
                    MediaDownloadQueue.STATUS_ERROR -> "失败：${task.error}"
                    MediaDownloadQueue.STATUS_RUNNING ->
                        "${FormatUtils.formatSize(task.received)} / " +
                            FormatUtils.formatSize(if (task.total > 0) task.total else task.size)
                    else -> "排队中 · ${FormatUtils.formatSize(task.size)}"
                },
                style = UfiTextStyles.note,
                color = if (task.status == MediaDownloadQueue.STATUS_ERROR) {
                    palette.error
                } else {
                    palette.textSecondary
                }
            )
            if (task.status == MediaDownloadQueue.STATUS_RUNNING && task.total > 0) {
                Spacer(Modifier.height(Spacing.Small))
                UfiCompactProgressBar(
                    progress = task.progress,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (task.status == MediaDownloadQueue.STATUS_ERROR) {
            IconButton(onClick = onRetry) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "重试",
                    tint = palette.accent,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                if (task.status == MediaDownloadQueue.STATUS_ERROR) {
                    Icons.Default.Delete
                } else {
                    Icons.Default.Close
                },
                contentDescription = "移出队列",
                tint = palette.textSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 历史最多显示这么多条（存的更多，但设置页不是账本）。 */
private const val HISTORY_SHOWN = 20

/**
 * FFmpeg 组件副标题：已装看版本与占用，未装看需下载多少。
 *
 * [info] 为 null = `/api/components` 里没有 ffmpeg 这一条（core 版本过旧），
 * 这时不该说"未安装"（会让用户以为点一下就能装），要直接说 core 不支持。
 */
private fun ffmpegComponentDetail(info: com.ufi_axis.viewmodel.state.ComponentInfo?): String {
    if (info == null) return "当前 core 版本不支持此组件，升级 core 后可用"
    val parts = mutableListOf<String>()
    if (info.installed) {
        parts += "已安装 " + info.installedVersion.ifBlank { "版本未知" }
        if (info.installedSize > 0) parts += FormatUtils.formatSize(info.installedSize)
        if (info.source == "manual") parts += "本地上传"
        if (info.updateAvailable) parts += "有新版 ${info.latestVersion}"
    } else {
        parts += "未安装（设备将无法自行解出视频封面）"
        if (info.downloadSize > 0) parts += "需下载约 " + FormatUtils.formatSize(info.downloadSize)
        if (!info.available) parts += "更新源无可用版本"
    }
    return parts.joinToString(" · ")
}


/**
 * FFmpeg 自检行（嵌在 UfiSettingsGroup 内部）。
 * 状态全部由外部持有 —— 弹窗在外层（UfiScreenScaffold 之后）渲染，
 * 否则 UfiSettingsGroup 的裁剪会吞掉弹窗。
 */
@Composable
private fun FfmpegProbeCard(
    viewModel: MainViewModel,
    tab: MediaTabState,
    loading: Boolean,
    onLoadingChange: (Boolean) -> Unit,
    resultOk: Boolean?,
    onResult: (Boolean?, String) -> Unit
) {
    val palette = LocalResolvedPalette.current
    val scope = rememberCoroutineScope()

    UfiSettingsItem(
        title = "设备端抽帧能力",
        description = when (resultOk) {
            true -> "上次检测：可用（点击查看详情）"
            false -> "上次检测：不可用（点击查看详情）"
            null -> "检测 core 端 FFmpeg 组件是否已安装且可用"
        },
        icon = Icons.Default.Videocam,
        iconTint = when (resultOk) {
            true -> palette.success
            false -> palette.error
            null -> Color.Unspecified
        },
        trailing = {
            UfiButton(
                text = if (loading) "检测中" else "检测",
                onClick = {
                    if (loading) return@UfiButton
                    onLoadingChange(true)
                    scope.launch {
                        val sb = StringBuilder()
                        var ok: Boolean? = null
                        try {
                            val media = viewModel.media
                            sb.appendLine("── 第一步：查 FFmpeg 库 ──")
                            val status = media.ffmpegStatus()
                            if (status == null) {
                                sb.appendLine("请求失败（设备不在线或 core 版本过旧）")
                                ok = false
                                return@launch
                            }
                            sb.appendLine("available = ${status.available}")
                            sb.appendLine("native_ok = ${status.native_ok}")
                            sb.appendLine("version   = ${status.version}")
                            if (status.reason.isNotBlank()) sb.appendLine("reason    = ${status.reason}")
                            if (!status.available) {
                                sb.appendLine("\n结论：FFmpeg 组件未安装或加载失败")
                                sb.appendLine("请前往「设置 → 可选组件」安装 FFmpeg 组件")
                                ok = false
                                return@launch
                            }
                            if (!status.native_ok) {
                                sb.appendLine("\n结论：FFmpeg native 库加载失败")
                                ok = false
                                return@launch
                            }
                            val testPath = tab.items.firstOrNull()?.path
                            if (testPath.isNullOrBlank()) {
                                sb.appendLine("\n视频列表为空，无法试抽帧")
                                sb.appendLine("结论：FFmpeg 可用，但需要有视频才能验证抽帧")
                                ok = true
                                return@launch
                            }
                            sb.appendLine("\n── 第二步：试抽一帧 ──")
                            sb.appendLine("path = $testPath")
                            val probe = media.ffmpegStatus(testPath)
                            if (probe == null) {
                                sb.appendLine("抽帧请求失败")
                                ok = false
                                return@launch
                            }
                            sb.appendLine("probe_ok      = ${probe.probe_ok}")
                            sb.appendLine("elapsed       = ${probe.probe_elapsed_ms} ms")
                            sb.appendLine("output_bytes  = ${probe.probe_bytes}")
                            if (probe.probe_error.isNotBlank()) {
                                sb.appendLine("error         = ${probe.probe_error}")
                            }
                            sb.appendLine(
                                "\n结论：${if (probe.probe_ok) "设备端可以自行生成缩略图" else "抽帧失败，仍需手机端兜底"}"
                            )
                            ok = probe.probe_ok
                        } catch (e: Exception) {
                            sb.appendLine("\n异常：${e.javaClass.simpleName}: ${e.message}")
                            ok = false
                        } finally {
                            onResult(ok, sb.toString())
                            onLoadingChange(false)
                        }
                    }
                },
                loading = loading,
                variant = UfiButtonVariant.Subtle,
                size = UfiButtonSize.Small
            )
        }
    )
}
