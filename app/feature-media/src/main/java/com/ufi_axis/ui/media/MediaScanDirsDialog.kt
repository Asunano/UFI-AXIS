package com.ufi_axis.ui.media

import androidx.compose.runtime.Composable
import com.ufi_axis.ui.components.common.UFI_DEVICE_STORAGE_ROOT
import com.ufi_axis.ui.components.common.UfiDirectoryPickerAction
import com.ufi_axis.ui.components.common.UfiDirectoryPickerDialog

/**
 * 扫描目录选择器（无状态弹窗，`visible + onConfirm/onDismiss`）。
 *
 * 2026-09-16 从 `MediaLibraryPage.kt` 拆出：按文件管理器那套边界，**弹窗各自成文件**，
 * 页壳只持有"开关"这一个状态。
 *
 * 2026-09-20 改成薄封装：目录浏览交互（当前路径 / 上一级 / 子目录列表 / 加载与空态）已上提到
 * 公共件 [UfiDirectoryPickerDialog] —— 下载管理的新建任务也要选目录，同一份交互不能有两份实现。
 * 留在本文件的只有**媒体这边的业务语义**：
 *  · 多选（扫描范围可以是若干个目录）；
 *  · **空列表是合法选择**且含义明确：不限目录 = 整个媒体库里的这一类，所以给一颗
 *    「清空（整个媒体库）」，而不是让人逐条删到空；
 *  · 标题与提示文案带类型（图片 / 视频 / 音频）。
 *
 * 取数仍由调用方注入（[browse] = `MediaModule.browseDirs`，走已有的 `/api/files/list`，
 * 不给"选目录"新造接口）。目录是**按类型各存一份**的（core `/api/media/config?type=`），
 * 所以三页各开这个弹窗改的是三份不同配置，不存在互相覆盖。
 *
 * 签名保持不变：`MediaScanScopeSection` / `MediaLibraryPage` / `MediaImageSettingsScreen` /
 * `MediaVideoLibraryPane` 四处调用方不受本次改动影响。
 */
@Composable
internal fun MediaScanDirsDialog(
    visible: Boolean,
    typeLabel: String,
    initial: List<String>,
    browse: suspend (String) -> Pair<List<String>, String?>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    UfiDirectoryPickerDialog(
        visible = visible,
        title = "$typeLabel 扫描目录",
        root = UFI_DEVICE_STORAGE_ROOT,
        initialSelection = initial,
        multiSelect = true,
        // 历史文案，别改成"确定"：这一步是把整份目录配置写回 core，不是本地勾选。
        confirmText = "保存",
        emptySelectionHint = "当前：整个媒体库（不限目录）",
        extraAction = UfiDirectoryPickerAction(
            text = "清空（整个媒体库）",
            // 没有选中项时它等于空操作，露出来只会让人以为"清空"还能再清点什么。
            visible = { it.isNotEmpty() },
            onClick = { setSelection -> setSelection(emptyList()) }
        ),
        browse = browse,
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}
