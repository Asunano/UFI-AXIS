package com.ufi_axis.ui.screens.filemanager.dialogs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.ufi_axis.data.api.FileItem
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogActions
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiDialogInfoRow
import com.ufi_axis.ui.screens.filemanager.FileKind
import com.ufi_axis.ui.screens.filemanager.canExtract
import com.ufi_axis.ui.screens.filemanager.fileKindOf
import com.ufi_axis.ui.screens.filemanager.hasViewer
import com.ufi_axis.ui.screens.filemanager.openActionLabel
import com.ufi_axis.util.FormatUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件信息对话框（T7）。
 *
 * 包裹 [com.ufi_axis.ui.components.common.UfiCustomDialog]，以
 * [com.ufi_axis.ui.components.common.UfiDialogBody] + 多行
 * [com.ufi_axis.ui.components.common.UfiDialogInfoRow] 展示文件元数据，
 * 底部是「左关闭 / 右主操作」两按钮。
 *
 * ## 为什么只留一个操作按钮
 * 这个弹窗有两条入口：长按菜单的「信息」，以及**没有直接动作的类型**（压缩包/apk/pdf/未知扩展名）
 * 点击后的落点。它一度在元数据下方铺了一整排按钮（下载/校验和/压缩/复制/剪切/复制路径/重命名/删除），
 * 但这些动作长按菜单里一个不少 —— 同一份清单出现两次，弹窗反而变成了第二个菜单。
 * 现在只留这个类型"最该点的那一个"（[primaryFileAction]），其余一律回长按菜单。
 *
 * ## 主操作从哪来
 * 按钮只回传 action 字符串给 [onAction]，由 `FileManagerRoot.handleAction` 统一执行，
 * 与长按菜单共用同一份分发。弹窗自己**不碰 ViewModel、不做二次确认** —— 删除的确认弹窗
 * 由 handleAction 挂 `DeleteConfirmDialog`，在这里再加一层就成了"确认你要去确认"。
 *
 * 点击顺序固定为「先 [onDismiss] 再 [onAction]」：安装 APK 会挂出自己的弹窗，
 * 本弹窗不先退场就会两层叠在一起。
 *
 * @param visible 是否显示
 * @param file 文件数据（[com.ufi_axis.data.api.FileItem]）
 * @param onDismiss 关闭回调
 * @param onAction 动作回调，参数是 `FileManagerRoot.handleAction` 认识的 action 字符串
 * @param actionAvailable 该 action 在**当前目录所在的存储源**上是否真的可用。
 *   调用方直接用长按菜单的清单来回答（菜单里没有这项 = 做不成），所以远端源里的
 *   zip 不会长出一个点了回 400 的「解压」按钮、远端的 apk 不会长出「安装APK」。
 *   默认全可用，便于预览与测试。
 */
@Composable
fun FileInfoDialog(
    visible: Boolean,
    file: FileItem,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
    actionAvailable: (String) -> Boolean = { true }
) {
    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "文件信息",
        icon = rememberVectorPainter(Icons.Filled.Info),
        showCloseButton = false
    ) {
        UfiDialogBody {
            UfiDialogInfoRow("名称", file.name)
            UfiDialogInfoRow("路径", file.path, multiline = true)
            UfiDialogInfoRow("类型", if (file.isDirectory) "目录" else "文件")
            UfiDialogInfoRow("大小", FormatUtils.formatSize(file.size))
            UfiDialogInfoRow("修改时间", formatDate(file.lastModified))
            UfiDialogInfoRow("权限", file.permissions)
            UfiDialogInfoRow("符号链接", if (file.isSymlink) "是" else "否")
        }
        val primary = primaryFileAction(file)?.takeIf { actionAvailable(it.action) }
        if (primary != null) {
            UfiDialogActions(
                onDismiss = onDismiss,
                onConfirm = {
                    onDismiss()
                    onAction(primary.action)
                },
                confirmText = primary.label,
                dismissText = "关闭"
            )
        } else {
            // 这个类型没有成立的主操作（不可解压的 rar/7z、pdf 等文档、未知扩展名）：
            // 退化成单按钮。宁可少一个按钮，也不放一个点了只会重新挂起本弹窗的假按钮。
            UfiDialogActions(
                onDismiss = onDismiss,
                onConfirm = onDismiss,
                confirmText = "关闭",
                dismissText = null
            )
        }
    }
}

/**
 * 弹窗右侧那一个主操作。
 *
 * [action] 复用 `FileManagerRoot.handleAction` 已有的字符串，本弹窗不发明新动作；
 * 文案也照抄长按菜单，避免"同一个动作两个地方两种叫法"。
 */
private data class PrimaryFileAction(val action: String, val label: String)

/**
 * 按文件类型算出唯一的主操作，算不出就返回 null。
 *
 * ## 判据：**这个动作对这个文件真的成立吗**
 * 不是"菜单里有没有"，而是 handleAction 走下去会不会做成事：
 * - 目录 → `open`（进入），文案取 [openActionLabel]；
 * - 可解压的归档（[canExtract]）→ `extract`。**单击压缩包不再直接解压**（解压会立刻改设备
 *   文件系统，单击这种一碰就触发的手势不该执行它），这个按钮是 zip 除长按菜单之外的解压入口。
 *   rar/7z 走不到这里（它们是 [FileKind.ARCHIVE] 但后端不支持解压），所以不会长出点了就报错的按钮；
 * - [FileKind.APK] → `install`（走 `POST /api/apps/install`）。它没有 `open` —— APK 在
 *   `resolveOpenRoute` 里落到 else 分支，也就是"再打开一次本弹窗"，那是个假按钮；
 * - 有内置预览的类型（[hasViewer]：图片/视频/音频/文本）→ `open`，文案取 [openActionLabel]
 *   （"查看图片"/"播放音频"/"查看 / 编辑"），说清点下去会发生什么；
 * - 其余（不可解压归档 / pdf 等文档 / 未知扩展名）→ null。它们的 `open` 只会重新挂起本弹窗。
 */
private fun primaryFileAction(file: FileItem): PrimaryFileAction? {
    val kind = fileKindOf(file.name, file.isDirectory)
    return when {
        file.isDirectory -> PrimaryFileAction("open", kind.openActionLabel)
        canExtract(file.name) -> PrimaryFileAction("extract", "解压")
        kind == FileKind.APK -> PrimaryFileAction("install", "安装APK")
        kind.hasViewer -> PrimaryFileAction("open", kind.openActionLabel)
        else -> null
    }
}

/**
 * 将毫秒时间戳格式化为 yyyy-MM-dd 的日期字符串。
 *
 * 与 [com.ufi_axis.ui.screens.filemanager.components.FileRowCard] 中的实现保持一致。
 *
 * @param millis 最后修改时间（毫秒）
 * @return 格式化后的日期；无效时间返回 "-"
 */
private fun formatDate(millis: Long): String {
    if (millis <= 0L) return "-"
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return formatter.format(Date(millis))
}
