package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiChoiceSheet
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 长按一首歌能做的事。
 *
 * ## 为什么是枚举 + 调用方传列表，而不是一个固定菜单
 * 各入口能做的事不一样：歌单页多一项「移出歌单」，整库列表没有；失效（`missing`）的歌不能播、
 * 不能下载。把菜单项写死在组件里，就只能靠一堆布尔开关去开合，调用点反而更难读。
 *
 * 顺序即菜单顺序，[MediaTrackActionSheet] 按传入的列表原样排。
 */
internal enum class MediaTrackAction(val label: String) {
    /** 插到当前播放项之后。 */
    PlayNext("下一首播放"),
    AddToPlaylist("加入歌单"),
    Info("歌曲信息"),
    Download("下载到手机"),

    /** 仅歌单页：把这一首从**这个歌单**里去掉，不动文件、不动音乐库。 */
    RemoveFromPlaylist("移出歌单"),

    /** 从音乐库移除 = 加入排除名单、以后不再扫描。文件仍在设备上，可在管理页恢复。 */
    ExcludeFromLibrary("从音乐库移除"),

    /** 删除文件本身，不可逆。 */
    DeleteFile("删除文件")
}

/**
 * 长按动作表。直接复用 [UfiChoiceSheet]。
 *
 * ## 为什么复用选择面板而不是另画一个菜单
 * 这两者在交互上是同一件事：从几项里点一项。`UfiChoiceSheet` 已经统一过整行可点、间距、
 * 拖拽把手与底部安全区，另画一个只会得到第二套这些东西。传 `selectedValue = null`
 * 就没有选中态，看起来就是纯动作表。
 *
 * ## 禁用态怎么处理
 * `UfiChoiceSheet` 是冻结签名的公共件，不支持"某一项灰掉"。所以失效的歌**不把那些项传进来**
 *（见 `mediaTrackActionsFor`）——少一项比摆一个点不动的项更清楚，也不用去改公共件。
 */
@Composable
internal fun MediaTrackActionSheet(
    visible: Boolean,
    songTitle: String,
    actions: List<MediaTrackAction>,
    onDismiss: () -> Unit,
    onPick: (MediaTrackAction) -> Unit
) {
    if (!visible || actions.isEmpty()) return
    UfiChoiceSheet(
        title = songTitle.ifBlank { "歌曲操作" },
        options = actions.map { it.name to it.label },
        onDismiss = onDismiss,
        onSelect = { value ->
            // enumValueOf 而不是按下标取：options 的顺序将来可能被调用方改
            runCatching { MediaTrackAction.valueOf(value) }.getOrNull()?.let(onPick)
        }
    )
}

/**
 * 按曲目状态与所在页面决定菜单项。
 *
 * @param inPlaylist 当前是在某个歌单里长按（多一项「移出歌单」）。
 * @param canEditLibrary 是否允许出现「从音乐库移除 / 删除文件」。默认 false：新的调用点
 *   必须自己确认宿主挂了 [MediaTrackActionHost]（那两项的二次确认弹窗在那里），
 *   菜单里出现点了没反应的项比没有这一项糟得多。
 */
internal fun mediaTrackActionsFor(
    item: MediaLibraryItem,
    inPlaylist: Boolean,
    canEditLibrary: Boolean = false,
    canPlayNext: Boolean = false
): List<MediaTrackAction> = buildList {
    // 失效条目（core 报 missing：文件已不在原路径）不能播也不能下载，
    // 但仍然要能把它从歌单 / 音乐库里清掉 —— 那正是用户此刻最想做的事
    if (!item.missing) {
        if (canPlayNext) add(MediaTrackAction.PlayNext)
        add(MediaTrackAction.AddToPlaylist)
    }
    add(MediaTrackAction.Info)
    if (!item.missing) add(MediaTrackAction.Download)
    if (inPlaylist) add(MediaTrackAction.RemoveFromPlaylist)
    if (canEditLibrary) {
        add(MediaTrackAction.ExcludeFromLibrary)
        if (!item.missing) add(MediaTrackAction.DeleteFile)
    }
}

/**
 * 歌曲信息弹窗。
 *
 * 字段全部来自**已有的** [MediaLibraryItem]，不额外打接口 —— 这一页要的东西
 * （曲名 / 艺术家 / 专辑 / 时长 / 格式 / 大小 / 修改时间 / 路径）列表接口里都有。
 *
 * 路径单独做成可复制：这是整个弹窗里唯一拿出去还有用的信息
 *（贴进文件管理器、贴进日志、发给自己）。其余几行看完就完了。
 */
@Composable
internal fun MediaTrackInfoDialog(
    item: MediaLibraryItem,
    onDismiss: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "歌曲信息",
        confirmButton = {
            UfiButton(text = "关闭", onClick = onDismiss)
        },
        dismissButton = {
            UfiButton(
                variant = UfiButtonVariant.Secondary,
                text = if (copied) "已复制" else "复制路径",
                onClick = {
                    clipboard.setText(AnnotatedString(item.path))
                    copied = true
                }
            )
        }
    ) {
        UfiDialogBody {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                InfoLine("曲名", audioDisplayTitle(item))
                InfoLine("艺术家", item.artist.ifBlank { "—" })
                InfoLine("专辑", item.album.ifBlank { "—" })
                InfoLine("时长", mediaTrackDurationText(item.duration_ms))
                InfoLine("格式", item.mime.ifBlank { "—" })
                InfoLine("大小", FormatUtils.formatSize(item.size))
                InfoLine("修改时间", FormatUtils.formatTimestamp(item.date_modified))
                // 路径不限行数：截断了就失去了"能拿出去用"的意义
                InfoLine("路径", item.path, singleLine = false)
                if (item.missing) {
                    Text(
                        text = "文件已不在这个路径上（core 报 missing）",
                        style = UfiTextStyles.caption,
                        color = palette.error
                    )
                }
            }
        }
    }
}

/** 信息弹窗里的一行：左侧固定宽度的标签 + 右侧值。固定宽度是为了让几行的值左边缘对齐。 */
@Composable
private fun InfoLine(label: String, value: String, singleLine: Boolean = true) {
    val palette = LocalResolvedPalette.current
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = UfiTextStyles.caption,
            color = palette.textSecondary,
            modifier = Modifier.width(INFO_LABEL_WIDTH)
        )
        Text(
            text = value,
            style = UfiTextStyles.note,
            color = palette.textPrimary,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE
        )
    }
}

private val INFO_LABEL_WIDTH = 68.dp

/**
 * 毫秒 → `m:ss`。
 *
 * `MediaNowPlayingChip` 里有一个同样的私有实现。没有合并是因为那一个服务的是进度条
 * （每 500ms 调一次），这一个只在弹窗里调一次；真要合并应该等第三个调用点出现时
 * 一起提到 `MediaListCommon`，而不是现在就为两处建一个公共件。
 */
private fun mediaTrackDurationText(ms: Long): String {
    if (ms <= 0L) return "—"
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/**
 * 长按动作的带状态外壳：动作表 + 歌曲信息弹窗 + 「加入歌单」面板 + 下载，全收在这里。
 *
 * ## 为什么要这么一层
 * 三个入口（整库列表、分组详情、歌单内）长按要做的事几乎一样。不收口的话每个入口都得自己
 * 摆四个状态变量、四个弹窗、四条接线 —— 三份手写副本迟早有一份忘了某个分支
 *（`MediaAddToPlaylistHost` 当初就是为同样的理由抽出来的）。
 *
 * 本组件**不自己决定菜单项**，由调用方传 [actionsOf]，因为各入口能做的事不同（见
 * [mediaTrackActionsFor]）。
 *
 * @param target 长按选中的那一首。null = 什么都不显示。
 * @param onDone 流程结束（取消 / 已执行）时调用，调用方据此把 target 置回 null。
 * @param onRemoveFromPlaylist 歌单页传入；其余入口传 null 并且别把那一项放进 [actionsOf]。
 */
@Composable
internal fun MediaTrackActionHost(
    viewModel: MainViewModel,
    target: MediaLibraryItem?,
    actionsOf: (MediaLibraryItem) -> List<MediaTrackAction>,
    onDone: () -> Unit,
    onRemoveFromPlaylist: ((MediaLibraryItem) -> Unit)? = null
) {
    val media = viewModel.media

    /*
     * 自己连一个 controller：「下一首播放」要往播放器队列里插歌，而列表页本身没有 controller
     *（播放器是 MediaSessionService，只有播放页与迷你条持有）。
     * 这三个页面底部本来就挂着 MediaAudioMiniBar、各自已经有一个 controller，
     * 所以多一个是与既有做法一致的；MediaController 是个轻代理，不是播放器实例。
     *
     * null（还没连上 / 服务没起）时「下一首播放」不会出现在菜单里 —— 见 canPlayNext。
     */
    val controller = rememberUfiAudioController()

    /** 已从动作表里选了「加入歌单」的那一首 —— 交给 [MediaAddToPlaylistHost]。 */
    var addTarget by remember { mutableStateOf<MediaLibraryItem?>(null) }

    /** 已选「歌曲信息」的那一首。 */
    var infoTarget by remember { mutableStateOf<MediaLibraryItem?>(null) }

    /** 下载被挡下的提示（单下载槽位，见下方说明）。 */
    var blockedMessage by remember { mutableStateOf<String?>(null) }

    /** 等二次确认的「从音乐库移除」/「删除文件」。两个分开存：确认文案与后果完全不同。 */
    var excludeTarget by remember { mutableStateOf<MediaLibraryItem?>(null) }
    var deleteTarget by remember { mutableStateOf<MediaLibraryItem?>(null) }

    /** 正在请求 core（禁掉确认按钮，避免连点发两次删除）。 */
    var busy by remember { mutableStateOf(false) }

    /** 执行结果里**必须告诉用户的那句话**（失败原因或只部分生效）；成功且无话可说时为 null。 */
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()

    MediaTrackActionSheet(
        visible = target != null,
        songTitle = target?.let { audioDisplayTitle(it) }.orEmpty(),
        // 还没连上播放服务时把「下一首播放」摘掉：那一项此刻点了必然无效。
        // 调用方不该关心 controller 有没有就绪，所以这个过滤放在这里而不是 actionsOf 里。
        actions = target?.let(actionsOf).orEmpty()
            .filter { it != MediaTrackAction.PlayNext || controller != null },
        onDismiss = onDone,
        onPick = { action ->
            val item = target
            // 先把动作表关掉：它是 ModalBottomSheet，留着会盖住接下来要弹的东西
            onDone()
            if (item == null) return@MediaTrackActionSheet
            when (action) {
                MediaTrackAction.AddToPlaylist -> addTarget = item
                MediaTrackAction.Info -> infoTarget = item
                MediaTrackAction.Download -> {
                    // FileManagerModule.downloadFileToPhone 开头就是
                    // `if (isDownloading) return` —— 单槽位。不自己判一次的话，
                    // 下载中再点会**静默无反应**，用户只会觉得这个菜单项坏了。
                    if (viewModel.files.state.value.isDownloading) {
                        blockedMessage = "已有下载任务在进行中，完成后再试"
                    } else {
                        viewModel.files.downloadFileToPhone(item.path, item.name)
                    }
                }

                MediaTrackAction.RemoveFromPlaylist -> onRemoveFromPlaylist?.invoke(item)

                MediaTrackAction.PlayNext -> controller?.ufiPlayNext(
                    UfiAudioTrack(
                        mediaId = item.path,
                        url = media.streamUrl(item.path),
                        artworkUrl = media.coverUrl(item.id)
                    )
                )

                MediaTrackAction.ExcludeFromLibrary -> excludeTarget = item
                MediaTrackAction.DeleteFile -> deleteTarget = item
            }
        }
    )

    MediaAddToPlaylistHost(media = media, target = addTarget, onDone = { addTarget = null })

    infoTarget?.let { item ->
        MediaTrackInfoDialog(item = item, onDismiss = { infoTarget = null })
    }

    blockedMessage?.let { msg ->
        UfiCustomDialog(
            visible = true,
            onDismiss = { blockedMessage = null },
            title = "暂时无法下载",
            confirmButton = {
                UfiButton(text = "知道了", onClick = { blockedMessage = null })
            }
        ) {
            UfiDialogBody {
                Text(msg, style = UfiTextStyles.note, color = LocalResolvedPalette.current.textSecondary)
            }
        }
    }

    // 「从音乐库移除」的确认。它是可撤销的，所以这一档不用 Danger 按钮 ——
    // 把两种破坏性程度画成同一个红色，用户就学不会区分了。
    excludeTarget?.let { item ->
        UfiCustomDialog(
            visible = true,
            onDismiss = { if (!busy) excludeTarget = null },
            title = "从音乐库移除",
            confirmButton = {
                UfiButton(
                    text = if (busy) "移除中…" else "移除",
                    enabled = !busy,
                    onClick = {
                        busy = true
                        coroutineScope.launch {
                            val message = media.excludeFromLibrary(listOf(item.path))
                            busy = false
                            excludeTarget = null
                            resultMessage = message
                        }
                    }
                )
            },
            dismissButton = {
                UfiButton(
                    variant = UfiButtonVariant.Secondary,
                    text = "暂不移除",
                    enabled = !busy,
                    onClick = { excludeTarget = null }
                )
            }
        ) {
            UfiDialogBody {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    Text(
                        text = "「${audioDisplayTitle(item)}」不再出现在音乐库列表里。",
                        style = UfiTextStyles.note,
                        color = LocalResolvedPalette.current.textPrimary
                    )
                    Text(
                        // 说清"文件还在"是这一档确认的全部意义：否则用户会把它当成删除而不敢点
                        text = "文件仍然留在设备上，可以在 音乐页 → 右上角设置 → 已从音乐库移除的歌曲 里恢复。",
                        style = UfiTextStyles.caption,
                        color = LocalResolvedPalette.current.textSecondary
                    )
                }
            }
        }
    }

    // 「删除文件」的确认。不可逆，所以必须写清文件名**与完整路径** ——
    // 同名文件在不同目录里很常见，只给曲名的确认框等于让用户凭猜确认。
    deleteTarget?.let { item ->
        UfiCustomDialog(
            visible = true,
            onDismiss = { if (!busy) deleteTarget = null },
            title = "永久删除文件",
            confirmButton = {
                UfiButton(
                    variant = UfiButtonVariant.Danger,
                    text = if (busy) "删除中…" else "永久删除",
                    enabled = !busy,
                    onClick = {
                        busy = true
                        coroutineScope.launch {
                            // 先从播放队列里摘掉再删：留着的话播到它会抛文件不存在，
                            // 用户看到的是"播放失败"而不是"这首是我删的"
                            controller?.ufiRemoveFromQueueByMediaId(item.path)
                            val message = media.deleteMediaFile(item.path)
                            busy = false
                            deleteTarget = null
                            resultMessage = message
                        }
                    }
                )
            },
            dismissButton = {
                UfiButton(
                    variant = UfiButtonVariant.Secondary,
                    text = "暂不删除",
                    enabled = !busy,
                    onClick = { deleteTarget = null }
                )
            }
        ) {
            UfiDialogBody {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    Text(
                        text = "「${item.name}」将从设备上永久删除，无法恢复。",
                        style = UfiTextStyles.note,
                        color = LocalResolvedPalette.current.textPrimary
                    )
                    Text(
                        text = item.path,
                        style = UfiTextStyles.caption,
                        color = LocalResolvedPalette.current.textSecondary
                    )
                    Text(
                        text = "只想让它不再出现在列表里，就用「从音乐库移除」。",
                        style = UfiTextStyles.caption,
                        color = LocalResolvedPalette.current.textSecondary
                    )
                }
            }
        }
    }

    // 执行结果。只有"有话要说"时才弹：干净成功的情况下列表里那一行已经消失了，
    // 再弹一个"已移除"只是多一次点击。
    resultMessage?.let { msg ->
        UfiCustomDialog(
            visible = true,
            onDismiss = { resultMessage = null },
            title = "没能全部完成",
            confirmButton = {
                UfiButton(text = "知道了", onClick = { resultMessage = null })
            }
        ) {
            UfiDialogBody {
                Text(msg, style = UfiTextStyles.note, color = LocalResolvedPalette.current.textSecondary)
            }
        }
    }
}
