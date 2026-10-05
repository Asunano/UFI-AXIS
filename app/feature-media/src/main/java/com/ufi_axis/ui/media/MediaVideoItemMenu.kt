package com.ufi_axis.ui.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.data.model.VideoInfoResponse
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiDialogInfoRow
import com.ufi_axis.ui.components.common.UfiDialogNote
import com.ufi_axis.ui.components.common.UfiPopupOption
import com.ufi_axis.util.FormatUtils
import kotlin.math.roundToInt

/**
 * 视频条目长按菜单的共用零件（2026-09-20）。
 *
 * ## 为什么菜单构造与弹窗都不在两栏里
 * 首页海报墙（[MediaVideoHome]）和媒体库文件夹视图（[MediaVideoLibraryPane]）对同一个文件
 * 提供的是**同一组**操作，只是行/格子的画法不同。菜单项写在各自文件里就会变成两份 ——
 * 文件管理器现在就是这样（网格视图和列表视图各有一份逐字相同的 `buildList`），
 * 加一项操作要改两处、漏一处就出现"网格里有、列表里没有"。
 *
 * 所以这里只出**数据与零件**：选项表由 [mediaVideoItemMenuOptions] 造一份，
 * 弹窗（[MediaVideoInfoDialog]）也只有一份；谁触发的长按由两栏各自上报
 * （[MediaVideoMenuTarget]），真正的菜单与弹窗挂在页壳 [MediaVideoScreen] 上。
 * 挂在页壳而不是行内，是因为 [com.ufi_axis.ui.components.common.UfiPopupMenu] 按
 * **窗口绝对坐标**定位（调用方给的 `anchorBounds` 优先于 Compose 回调里的那份），
 * 挂在哪一层都能弹对位置；而弹窗需要的"当前是哪一项"这类状态只有页壳一处持有才不会漂移。
 */

/**
 * 一次长按的全部上下文。
 *
 * @param anchorBounds 被长按的行/格子的窗口矩形，菜单据此 clamp 进屏幕。
 * @param anchorPoint 长按落点（窗口坐标），菜单中心对齐它出现。
 *   `combinedClickable` 不暴露长按的 offset，所以两栏都退而用几何中心
 *   （见 [mediaVideoAnchorCenter]），与文件管理器的行卡同一个妥协。
 * @param downloadSubDir 下载落点相对镜像子目录。**首页永远是空串** ——
 *   首页是整库平铺、没有"当前目录"这个上下文，算不出相对哪个根的哪一段；
 *   所以从首页下载会落在 `Download/UFI-AXIS/Movies` 根下，
 *   要镜像目录结构请从媒体库那一栏下载。
 */
internal data class MediaVideoMenuTarget(
    val item: MediaLibraryItem,
    val anchorBounds: IntRect,
    val anchorPoint: IntOffset,
    val downloadSubDir: String
)

/** `LayoutCoordinates` → 窗口整数矩形。两栏各写一遍 `roundToInt()` 只会抄错。 */
internal fun mediaVideoAnchorRect(coords: LayoutCoordinates): IntRect {
    val r = coords.boundsInWindow()
    return IntRect(
        r.left.roundToInt(),
        r.top.roundToInt(),
        r.right.roundToInt(),
        r.bottom.roundToInt()
    )
}

/** 锚点矩形的几何中心，用作长按点的替代（理由见 [MediaVideoMenuTarget.anchorPoint]）。 */
internal fun mediaVideoAnchorCenter(bounds: IntRect): IntOffset = IntOffset(
    (bounds.left + bounds.right) / 2,
    (bounds.top + bounds.bottom) / 2
)

/**
 * 长按菜单的选项表。
 *
 * 顺序按"用得最多的在最上、不可逆的在最下"排：播放 → 下载 → 重命名 → 复制路径 → 文件信息
 * → **分隔线** → 删除。分隔线不是装饰：删除是这张菜单里唯一不可撤销的一项，
 * 紧贴在"文件信息"下面很容易被手抖点到。
 *
 * "播放"与单击等价，仍然给出来：长按之后手指已经在菜单上了，让用户为了播放先关掉菜单
 * 再点一次是多余的一步。
 */
internal fun mediaVideoItemMenuOptions(
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onCopyPath: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit
): List<UfiPopupOption> = listOf(
    UfiPopupOption("play", "播放", icon = Icons.Default.PlayArrow, onClick = onPlay),
    UfiPopupOption("download", "下载到手机", icon = Icons.Default.Download, onClick = onDownload),
    UfiPopupOption(
        "rename",
        "重命名",
        icon = Icons.Default.DriveFileRenameOutline,
        onClick = onRename
    ),
    UfiPopupOption("copy-path", "复制路径", icon = Icons.Default.ContentCopy, onClick = onCopyPath),
    UfiPopupOption("info", "文件信息", icon = Icons.Default.Info, onClick = onInfo),
    UfiPopupOption.divider(),
    UfiPopupOption(
        "delete",
        "删除",
        icon = Icons.Default.Delete,
        isDestructive = true,
        onClick = onDelete
    )
)

/**
 * 文件信息弹窗。
 *
 * 不复用 `feature-files` 里的 `FileInfoDialog`：那个是 internal、跨模块取不到，
 * 而且它展示的是 `FileItem`（目录/权限那一套），这里要的是媒体字段（时长、分辨率）。
 * 共用的是**弹窗零件**（[UfiDialogInfoRow]），不是弹窗本身 —— 视觉口径因此仍然只有一份。
 *
 * 拿不到的字段**整行不显示**，不写"未知 / 0×0"：那种占位看着像数据。
 * 这些值来自两处：系统媒体库（core 的 `/api/media/list` 只是把 MediaStore 的列转发出来）
 * 与 2026-10-05 G7 起追加的 `/api/media/video-info`（ffmpeg 探测，补编码 / 码率 / 帧率），
 * 所以"没有"的真实含义是"两处都没记"，不是"文件没有"。
 */
@Composable
internal fun MediaVideoInfoDialog(
    visible: Boolean,
    item: MediaLibraryItem?,
    videoInfo: (suspend (Long) -> VideoInfoResponse?)?,
    onDismiss: () -> Unit
) {
    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "文件信息"
    ) {
        UfiDialogBody {
            /*
             * 本组件**常驻组合**、由 visible 控显隐（与全项目其它弹窗同一写法）：
             * 用 `item?.let { 弹窗 }` 条件卸载会让弹窗壳来不及播关闭动画。
             * 代价是关闭那一刻 item 已经是 null，所以内容这里要能接受 null。
             */
            if (item != null) {
                // 2026-10-05 G7（FFmpeg 接入计划书 §4.3）：打开弹窗时向 core 探一次视频
                // 真值（ffmpeg 探测，core 侧永久缓存——第二次打开同一条就是瞬时返回）。
                // 结果**追加**在 MediaStore 行下面：MediaStore 有值的字段优先（同源不冲突），
                // ffmpeg 才有的字段（编码/位深/码率/帧率）补齐系统没记的那部分。
                var probed by remember { mutableStateOf<VideoInfoResponse?>(null) }
                var probeDone by remember { mutableStateOf(false) }
                LaunchedEffect(item.id) {
                    // 2026-10-05 审查修复：换 item 重开弹窗时先清旧探测值并复位 probeDone，
                    // 否则在飞调用返回前会把上一条视频的编码/码率短暂渲染到新条目上（串台）
                    probed = null
                    probeDone = false
                    probed = videoInfo?.invoke(item.id)
                    probeDone = true
                }
                UfiDialogInfoRow("名称", item.name, multiline = true)
                if (item.size > 0) {
                    UfiDialogInfoRow("大小", FormatUtils.formatSize(item.size))
                }
                formatMediaDuration(item.duration_ms).takeIf { it.isNotBlank() }
                    ?: formatMediaDuration(probed?.durationMs ?: 0L).takeIf { it.isNotBlank() }
                    ?.let {
                        UfiDialogInfoRow("时长", it)
                    }
                if (item.width > 0 && item.height > 0) {
                    UfiDialogInfoRow("分辨率", "${item.width} × ${item.height}")
                } else if ((probed?.width ?: 0) > 0 && (probed?.height ?: 0) > 0) {
                    UfiDialogInfoRow("分辨率", "${probed?.width} × ${probed?.height}")
                }
                if (item.mime.isNotBlank()) {
                    UfiDialogInfoRow("类型", item.mime)
                }
                probed?.takeIf { it.codec.isNotBlank() }?.let {
                    UfiDialogInfoRow("编码", it.codec)
                }
                probed?.takeIf { it.pix_fmt.isNotBlank() }?.let {
                    UfiDialogInfoRow("像素格式", it.pix_fmt)
                }
                probed?.takeIf { it.bit_rate > 0 }?.let {
                    // bit_rate 是 bit/s，FormatUtils.formatSize 吃字节 —— 换算后自带 "bps" 语义
                    UfiDialogInfoRow("码率", "${FormatUtils.formatSize(it.bit_rate / 8)}/s")
                }
                probed?.takeIf { it.fps_num > 0 && it.fps_den > 0 }?.let {
                    UfiDialogInfoRow("帧率", "%.2f fps".format(it.fps_num.toDouble() / it.fps_den))
                }
                if (item.date_modified > 0) {
                    UfiDialogInfoRow("修改时间", FormatUtils.formatTimestamp(item.date_modified))
                }
                UfiDialogInfoRow("路径", item.path, multiline = true)
                // probeDone 之前不落任何提示，避免"加载中"闪一下就变成"解析失败"
                if (probeDone && probed == null) {
                    UfiDialogNote("设备无法解析此文件的编码信息（损坏或容器不受支持）。")
                } else {
                    UfiDialogNote(
                        "基础信息来自设备的系统媒体库；编码 / 码率 / 帧率由设备端 ffmpeg 探测，" +
                            "文件刚改过名时可能还是旧记录。"
                    )
                }
            }
        }
    }
}
