package com.ufi_axis.ui.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.components.common.UfiInputDialog
import com.ufi_axis.ui.components.common.UfiPopupMenu
import com.ufi_axis.ui.components.common.UfiPopupOption
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 图片条目长按菜单 / 灯箱底部动作条（2026-10-21）。
 *
 * ## 为什么和视频菜单不共用一张选项表
 * 两者能做的事只重叠一半：视频有"下载到手机 / 重新获取封面 / 文件信息（ffmpeg 探真值）"，
 * 图片要的是"图片编辑"，而视频没有这个概念。硬合成一张表就得在每一行上判"这一类支不支持"，
 * 那种表最后一定会出现"某个入口在某一类里点了没反应"。
 *
 * 真正共用的是**零件**：弹窗定位（[UfiPopupMenu]）、重命名/删除弹窗、编辑器、toast。
 *
 * ## 两处入口，一份状态
 * [MediaImageActionState] 把"要编辑/重命名/删除哪一张 + 当前 toast"收成一个对象：
 * · 网格长按 → [MediaImageActionHost]（弹菜单，菜单项写进 state）；
 * · 灯箱底部动作条 → 直接写 state（2026-10-21 根据用户意见把三点菜单挪到了底栏）。
 * 两条入口的**弹窗与编辑器完全是同一份实现**，不会出现"从菜单点能编辑、从底栏点不能"。
 */
internal data class MediaImageMenuTarget(
    val item: MediaLibraryItem,
    /** 触发点所在格子的窗口矩形：菜单据此 clamp 进屏幕。 */
    val anchorBounds: IntRect,
    /** 长按落点（窗口坐标）。 */
    val anchorPoint: IntOffset
)

/**
 * 图片操作的共享状态（重命名 / 删除 / 编辑 / toast）。
 *
 * 用普通类 + `mutableStateOf` 而不是散在调用点的几个 `remember`：灯箱与网格两个宿主
 * 都要驱动同一组弹窗，状态飘在两边迟早漏一个。
 */
internal class MediaImageActionState {
    var editTarget by mutableStateOf<MediaLibraryItem?>(null)
    var renameTarget by mutableStateOf<MediaLibraryItem?>(null)
    var deleteTarget by mutableStateOf<MediaLibraryItem?>(null)
    var toast by mutableStateOf<ToastMessage?>(null)

    fun notify(text: String, type: ToastType) {
        toast = ToastMessage(text, type)
    }
}

/** 「下载到手机」：两个入口同一份实现（只是弹一次提示）。 */
internal fun MediaImageActionState.downloadToPhone(viewModel: MainViewModel, item: MediaLibraryItem) {
    viewModel.files.downloadFileToPhone(item.path, item.name, source = "image")
    notify("已加入手机下载", ToastType.INFO)
}

/** 「复制路径」：顺手清掉文件管理器那条 operationMessage（它会在别的页面弹自己的 toast）。 */
internal fun MediaImageActionState.copyPath(viewModel: MainViewModel, item: MediaLibraryItem) {
    viewModel.files.copyPathToClipboard(item.path)
    viewModel.files.clearFileOperationMessage()
    notify("已复制路径", ToastType.SUCCESS)
}

/**
 * 长按菜单的选项表。
 *
 * 顺序与视频菜单同一条规矩：常用在上、不可逆的在最下，"删除"前面垫一条分隔线 ——
 * 它是这张表里唯一不能撤销的一项（设备上直接删，没有回收站）。
 */
/**
 * 图片编辑入口是否可见（2026-10-21）。
 *
 * 本轮先把入口**藏起来**，下个版本优化完再放出来：代码、数据模型、导出管线全部保留
 * （[MediaImageEditor] 仍被这两处引用），只是不给用户入口，免得上线一个自己还不满意的体验。
 * 放开时把这里改成 true 即可，不需要改别的地方。
 */
internal const val MEDIA_IMAGE_EDIT_VISIBLE = false

internal fun mediaImageItemMenuOptions(
    onOpen: () -> Unit,
    /** null = 本轮不给"图片编辑"这一项（见 [MEDIA_IMAGE_EDIT_VISIBLE]）。 */
    onEdit: (() -> Unit)?,
    onRename: () -> Unit,
    onDownload: () -> Unit,
    onCopyPath: () -> Unit,
    onDelete: () -> Unit
): List<UfiPopupOption> = buildList<UfiPopupOption> {
    add(UfiPopupOption("open", "查看", icon = Icons.Default.OpenInFull, onClick = onOpen))
    onEdit?.let {
        add(UfiPopupOption("edit", "图片编辑", icon = Icons.Default.Edit, onClick = it))
    }
    add(UfiPopupOption("rename", "重命名", icon = Icons.Default.DriveFileRenameOutline, onClick = onRename))
    add(UfiPopupOption("download", "下载到手机", icon = Icons.Default.Download, onClick = onDownload))
    add(UfiPopupOption("copy-path", "复制路径", icon = Icons.Default.ContentCopy, onClick = onCopyPath))
    add(UfiPopupOption.divider())
    add(
        UfiPopupOption(
            "delete",
            "删除",
            icon = Icons.Default.Delete,
            isDestructive = true,
            onClick = onDelete
        )
    )
}

/**
 * 网格长按那一侧：弹出菜单，菜单项写进 [MediaImageActionState]。
 *
 * 菜单/弹窗状态建在这里（而不是建在格子里）：格子会随滚动被回收，状态建在格子内会在
 * 菜单还开着的时候被销毁 —— 视频页那一轮踩过同一个坑。
 */
@Composable
internal fun MediaImageActionHost(
    viewModel: MainViewModel,
    target: MediaImageMenuTarget?,
    onTargetChange: (MediaImageMenuTarget?) -> Unit,
    onOpen: (MediaLibraryItem) -> Unit
) {
    val state = remember { MediaImageActionState() }

    val menuTarget = target
    UfiPopupMenu(
        visible = menuTarget != null,
        onDismiss = { onTargetChange(null) },
        anchorBounds = menuTarget?.anchorBounds ?: IntRect.Zero,
        anchorPoint = menuTarget?.anchorPoint,
        options = mediaImageItemMenuOptions(
            onOpen = { menuTarget?.let { onOpen(it.item) } },
            onEdit = if (MEDIA_IMAGE_EDIT_VISIBLE) {
                { state.editTarget = menuTarget?.item }
            } else {
                null
            },
            onRename = { state.renameTarget = menuTarget?.item },
            onDownload = { menuTarget?.let { state.downloadToPhone(viewModel, it.item) } },
            onCopyPath = { menuTarget?.let { state.copyPath(viewModel, it.item) } },
            onDelete = { state.deleteTarget = menuTarget?.item }
        )
    )

    MediaImageActionDialogs(viewModel, state)
}

/**
 * 重命名 / 删除确认 / 图片编辑 / toast —— 两个入口共用的一套弹窗。
 *
 * 抽出来是因为它们与"从哪儿触发"无关：灯箱底栏和网格长按菜单都要能开编辑器、能确认删除。
 */
@Composable
internal fun MediaImageActionDialogs(
    viewModel: MainViewModel,
    state: MediaImageActionState,
    /**
     * 编辑器用哪种宿主（2026-10-21）：
     * · false（默认，网格长按那条）—— 建 Dialog 窗口，能盖住整个 App（含胶囊底栏）；
     * · true（灯箱那条）—— **就地浮层**，与灯箱同一次组合，进出不会经历窗口切换的空帧。
     */
    inlineEditor: Boolean = false
) {
    val files = viewModel.files
    val context = LocalContext.current

    val renaming = state.renameTarget
    UfiInputDialog(
        visible = renaming != null,
        title = "重命名",
        initialValue = renaming?.name ?: "",
        hint = "新文件名",
        validator = { name ->
            when {
                name.isBlank() -> "名称不能为空"
                // core 侧按 parent + 新名拼全路径，带 / 会越出本目录
                name.contains('/') -> "名称不能包含 /"
                name == renaming?.name -> "名称未改变"
                else -> null
            }
        },
        onConfirm = { newName ->
            val item = renaming
            state.renameTarget = null
            if (item != null) {
                files.renameFile(item.path, newName)
                state.notify("已提交重命名（列表要等系统媒体库更新）", ToastType.SUCCESS)
            }
        },
        onDismiss = { state.renameTarget = null }
    )

    val deleting = state.deleteTarget
    UfiConfirmDialog(
        visible = deleting != null,
        title = "删除这张图片？",
        // 说清"删到哪去了"：设备上是直接删，没有回收站可以捞回来
        text = "「${deleting?.name.orEmpty()}」将从设备上直接删除，不进回收站，无法撤销。",
        confirmText = "删除",
        destructive = true,
        onConfirm = {
            val item = deleting
            state.deleteTarget = null
            if (item != null) {
                files.deleteFileOrDir(item.path)
                state.notify("已提交删除（列表要等系统媒体库更新）", ToastType.SUCCESS)
            }
        },
        onDismiss = { state.deleteTarget = null }
    )

    // 编辑器：编辑结果直接落在文件链上（见 MediaImageEditor）
    val editing = state.editTarget
    if (!inlineEditor && editing != null) {
        MediaImageEditor(
            item = editing,
            url = viewModel.media.streamUrl(editing.path),
            onDismiss = { state.editTarget = null },
            onSaved = { message, type -> state.notify(message, type) },
            exportToPhone = { file, name -> files.exportLocalToDownloads(file, name) },
            uploadCopy = { uri, dir -> files.uploadFileToServer(uri, dir) },
            fileProviderAuthority = "${context.packageName}.fileprovider"
        )
    }
    // inlineEditor = true 时编辑器由**宿主自己**渲染在自己那个整屏 Box 里（见灯箱）：
    // 就地浮层必须拿到"页面级"的约束，挂在多个兄弟节点之间会被量成不定尺寸。

    UfiToastHost(toastMessage = state.toast, onDismiss = { state.toast = null })
}
