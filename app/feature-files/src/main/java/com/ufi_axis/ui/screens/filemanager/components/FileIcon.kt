package com.ufi_axis.ui.screens.filemanager.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.ufi_axis.ui.theme.LocalResolvedPalette

/**
 * 通用文件图标组件。
 *
 * 按文件扩展名选择对应的 Material 图标，并根据类型着色：
 *  - 目录（文件夹）→ [Icons.Filled.Folder]，着色 [com.ufi_axis.ui.theme.LocalResolvedPalette.accent]
 *  - 文件 → 按扩展名映射的图标，统一着色 [com.ufi_axis.ui.theme.LocalResolvedPalette.accentSecondary]
 *
 * 组件完全无状态：所有数据通过参数传入，不持有任何 ViewModel 或可变状态。
 *
 * @param name 文件名（含扩展名），用于推断图标与分类
 * @param isDirectory 是否为目录，目录一律显示文件夹图标
 * @param modifier 修饰符
 */
@Composable
fun FileIcon(
    name: String,
    isDirectory: Boolean = false,
    modifier: Modifier = Modifier
) {
    val tint: Color = fileIconTint(isDirectory)
    val imageVector: ImageVector = if (isDirectory) {
        Icons.Filled.Folder
    } else {
        iconForExtension(name)
    }
    Icon(
        imageVector = imageVector,
        contentDescription = fileTypeLabel(name),
        tint = tint,
        modifier = modifier
    )
}

/**
 * 返回文件图标的着色颜色。
 *
 * 目录使用 [com.ufi_axis.ui.theme.LocalResolvedPalette.accent]，
 * 文件使用 [com.ufi_axis.ui.theme.LocalResolvedPalette.accentSecondary]。
 *
 * @param isDirectory 是否为目录
 * @return 图标着色 [Color]
 */
@Composable
fun fileIconTint(isDirectory: Boolean): Color {
    val palette = LocalResolvedPalette.current
    return if (isDirectory) palette.accent else palette.accentSecondary
}

/**
 * 根据文件名返回中文分类标签（纯函数，非 Composable，无副作用）。
 *
 * @param name 文件名
 * @return 分类标签：图片 / 视频 / 音频 / 文档 / 压缩 / 安装包 / 脚本代码 / 配置 / 文本 / 未知
 */
fun fileTypeLabel(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        // 图片
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico" -> "图片"
        // 视频
        "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "m4v" -> "视频"
        // 音频
        "mp3", "wav", "flac", "ogg", "aac", "m4a", "wma", "opus", "amr" -> "音频"
        // 文档
        "pdf", "doc", "docx", "odt", "xls", "xlsx", "csv" -> "文档"
        // 压缩
        "zip", "tar", "gz", "rar", "7z" -> "压缩"
        // 安装包
        "apk" -> "安装包"
        // 脚本代码
        "sh", "bash", "zsh", "py", "js", "kt", "java", "c", "cpp", "h", "go", "rs" -> "脚本代码"
        // 配置
        "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg" -> "配置"
        // 文本
        "txt", "log", "md", "html", "htm", "css" -> "文本"
        else -> "未知"
    }
}

/**
 * 根据文件名返回对应的 Material 图标（仅处理文件；目录由调用方处理）。
 *
 * @param name 文件名
 * @return 对应的 [ImageVector]
 */
private fun iconForExtension(name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        // 图片
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico" -> Icons.Filled.Image
        // 视频
        "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "m4v" -> Icons.Filled.VideoFile
        // 音频
        "mp3", "wav", "flac", "ogg", "aac", "m4a", "wma", "opus", "amr" -> Icons.Filled.AudioFile
        // 文档
        "pdf", "doc", "docx", "odt", "xls", "xlsx", "csv" -> Icons.Filled.PictureAsPdf
        // 压缩
        "zip", "tar", "gz", "rar", "7z" -> Icons.Filled.FolderZip
        // 安装包
        "apk" -> Icons.Filled.Android
        // 脚本代码
        "sh", "bash", "zsh", "py", "js", "kt", "java", "c", "cpp", "h", "go", "rs" -> Icons.Filled.Terminal
        // 配置
        "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg" -> Icons.Filled.Settings
        // 文本
        "txt", "log", "md", "html", "htm", "css" -> Icons.Filled.Article
        // 未知
        else -> Icons.Filled.InsertDriveFile
    }
}
