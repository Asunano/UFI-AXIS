package com.ufi_axis.ui.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.components.common.UfiSettingsValue
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 「扫描范围」设置组：三类媒体的设置页都要这一组，UI 完全一样，
 * 只有确认目录后的收尾动作不同（音频要作废分组缓存、视频要重拉文件夹视图），
 * 所以那一步由调用方通过 [onDirsChanged] 接。
 *
 * ## 为什么抽出来
 * 音乐页与视频页此前各存了一份**逐字相同**的 UI（值行 + 重新扫描行 + 目录选择器），
 * 只有 `onConfirm` 末尾那一句不同。两份复制品的直接后果是"改了一页忘了另一页"——
 * 这正是本轮起因（视频设置页没跟上音乐页的形态调整）。图片页设置将来落地时直接用这个，
 * 所以 [type] 是参数而不是写死的两个分支。
 *
 * ## 为什么两行各占一张卡、且不带区块标题
 * 「扫描目录」是**配置**（点开选目录），「重新扫描」是**动作**（点一下就发请求给设备），
 * 两者只是同属一个话题，并不是一件事的两个面。全站标准设置页（外观 / 告警 / 后台守护 /
 * 监控）自 2026-09-08 起都是一项一卡，这里跟着走，页面的卡片节奏才和别处一致。
 *
 * 这一组原来在两张卡上方挂了一个 `UfiSectionHeader("扫描范围")`。2026-09-22 按用户要求撤掉：
 * **卡外标题一律不要**。两张卡自己的标题（"扫描目录" / "重新扫描"）已经说清了是什么，
 * 再压一行"扫描范围"只是把同一件事说两遍，还在卡片节奏上多插了 8dp 的断点。
 * 不要改成 `UfiGroupHeader` 塞进卡里 —— 那会把两项并成一张卡，退回已被否掉的分区形态。
 *
 * @param type core 侧的媒体类型标识（`MEDIA_TYPE_AUDIO` / `_VIDEO` / `_IMAGE`）。
 *   扫描目录在 core 是**按类型各存一份**的，所以三页改的是三份互不影响的配置。
 * @param typeLabel 文案里用的类型名（"音乐" / "视频" / "图片"，见 [mediaTypeLabel]）。
 * @param onDirsChanged 目录已提交给 core **之后**的收尾。各类型不同、且都不能省：
 *   音频要作废三个维度的分组缓存，视频要重拉文件夹视图。统一成一个动作会让另一边留下过期视图。
 */
@Composable
internal fun MediaScanScopeSection(
    type: String,
    typeLabel: String,
    viewModel: MainViewModel,
    onDirsChanged: (List<String>) -> Unit = {}
) {
    val media = viewModel.media
    val state by media.state.collectAsState()
    val tab = state.tab(type)

    var showDirPicker by remember { mutableStateOf(false) }

    UfiSettingsRowCard {
        UfiSettingsValue(
            title = "扫描目录",
            description = "设备侧配置，只影响$typeLabel；留空 = 整个媒体库",
            value = when {
                tab.scanDirs.isEmpty() -> "整个媒体库"
                tab.scanDirs.size == 1 -> tab.scanDirs.first().substringAfterLast('/')
                else -> "${tab.scanDirs.size} 个目录"
            },
            icon = Icons.Default.FolderOpen,
            onClick = { showDirPicker = true }
        )
    }
    UfiSettingsRowCard {
        UfiSettingsItem(
            title = "重新扫描",
            description = "请设备重新收录这些目录。收录是异步的，稍后回列表下拉即可看到新文件",
            icon = Icons.Default.Refresh,
            enabled = !tab.isRescanning,
            onClick = { media.rescan(type) }
        )
    }

    // 弹窗是窗口级的，摆在这里不参与上面那两张卡的排版；跟着本组件走是为了让
    // "开关状态 + 提交 + 收尾"三件事留在同一个作用域，调用方只需要给一个收尾回调。
    MediaScanDirsDialog(
        visible = showDirPicker,
        typeLabel = typeLabel,
        initial = tab.scanDirs,
        browse = { path -> media.browseDirs(path) },
        onDismiss = { showDirPicker = false },
        onConfirm = { dirs ->
            showDirPicker = false
            media.setScanDirs(type, dirs)
            onDirsChanged(dirs)
        }
    )
}
