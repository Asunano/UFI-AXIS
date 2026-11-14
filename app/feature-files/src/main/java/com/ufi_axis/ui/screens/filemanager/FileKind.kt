package com.ufi_axis.ui.screens.filemanager

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 文件类别 —— 图标、菜单文案、点击分发的**唯一真源**。
 *
 * ## 为什么要有它
 * 2026-09-11 之前同一份扩展名信息在三处各存一份且互不一致：
 * - `FileIcon.kt` 的图标表（决定列表里显示什么图标）；
 * - `FileManagerRoot.resolveOpenRoute` 的分发表（决定点击后打开什么）；
 * - `FileManagerRoot.openActionLabel` 的文案表（决定长按菜单里那一项叫什么）。
 *
 * 后果是「图标显示什么」与「点击能打开什么」对不上：`.svg` 显示图片图标但点击弹详情，
 * `.go` / `.toml` 显示代码图标却打不开，`.flv` 显示视频图标也打不开。
 *
 * ## 归类原则：**按能不能打开归类，不按语义归类**
 * 类别直接决定点击行为，所以判据是"我们有没有能力打开它"：
 * - `.svg` 归 [TEXT]（它就是 XML 文本）。Coil 3 需要额外的 svg decoder，仓库里没引，
 *   归 [IMAGE] 等于给一个打不开的承诺；
 * - `.wmv` 归 [UNKNOWN]：media3 不支持，归 [VIDEO] 只会点开黑屏；
 * - `.csv` 归 [TEXT] 而不是 [DOCUMENT]：它是纯文本，编辑器能改；
 * - `.pdf` / `.doc` / `.xls` 归 [DOCUMENT]，点击给详情 + 下载引导（暂无内置预览）。
 */
enum class FileKind {
    DIRECTORY,
    IMAGE,
    VIDEO,
    AUDIO,
    /** 纯文本 / 代码 / 配置：可进文本编辑器查看与编辑。 */
    TEXT,
    ARCHIVE,
    APK,
    /** 有专门格式的文档，本端无内置预览。 */
    DOCUMENT,
    UNKNOWN
}

/** 列表里的图标。 */
val FileKind.icon: ImageVector
    get() = when (this) {
        FileKind.DIRECTORY -> Icons.Filled.Folder
        FileKind.IMAGE -> Icons.Filled.Image
        FileKind.VIDEO -> Icons.Filled.VideoFile
        FileKind.AUDIO -> Icons.Filled.AudioFile
        FileKind.TEXT -> Icons.Filled.Article
        FileKind.ARCHIVE -> Icons.Filled.FolderZip
        FileKind.APK -> Icons.Filled.Android
        FileKind.DOCUMENT -> Icons.Filled.PictureAsPdf
        FileKind.UNKNOWN -> Icons.Filled.InsertDriveFile
    }

/** 类别名，用于无障碍描述与详情弹窗。 */
val FileKind.label: String
    get() = when (this) {
        FileKind.DIRECTORY -> "文件夹"
        FileKind.IMAGE -> "图片"
        FileKind.VIDEO -> "视频"
        FileKind.AUDIO -> "音频"
        FileKind.TEXT -> "文本"
        FileKind.ARCHIVE -> "压缩包"
        FileKind.APK -> "安装包"
        FileKind.DOCUMENT -> "文档"
        FileKind.UNKNOWN -> "未知"
    }

/**
 * 长按菜单里「打开」那一项的文案 —— 说清点下去会发生什么。
 *
 * 判据是 `FileManagerRoot.resolveOpenRoute` **实际**会做什么，不是这个类型"语义上该做什么"：
 * - [FileKind.APK] 给"详情"而不是"安装"：`resolveOpenRoute` 对 apk 走 else 分支弹详情弹窗，
 *   安装在详情弹窗的主操作按钮（以及长按菜单单独的「安装APK」项）上。写"安装"是假文案；
 * - [FileKind.ARCHIVE] 同样给"详情"：2026-09-15 起单击压缩包不再直接解压（解压会立刻改设备
 *   文件系统，此前点一下就执行且无确认），改为落详情弹窗，由那里的「解压」主操作触发。
 */
val FileKind.openActionLabel: String
    get() = when (this) {
        FileKind.DIRECTORY -> "进入"
        FileKind.IMAGE -> "查看图片"
        FileKind.VIDEO -> "播放视频"
        FileKind.AUDIO -> "播放音频"
        FileKind.TEXT -> "查看 / 编辑"
        // 这四类没有内置预览/直接动作，点击落到详情弹窗
        FileKind.APK, FileKind.ARCHIVE, FileKind.DOCUMENT, FileKind.UNKNOWN -> "详情"
    }

/** 是否有内置的预览 / 播放页。为 false 时点击落到详情弹窗。 */
val FileKind.hasViewer: Boolean
    get() = this == FileKind.IMAGE || this == FileKind.VIDEO ||
        this == FileKind.AUDIO || this == FileKind.TEXT

/**
 * 是否可被后端解压：zip / tar.gz / tgz / tar / gz（与 web 端 `filesShared.canExtract` 一致）。
 *
 * rar / 7z / bz2 / xz 也归 [FileKind.ARCHIVE]（图标就该是压缩包），但后端**不支持**解压，
 * 所以"能不能解压"这件事必须单独判，不能用 `kind == ARCHIVE` 代替 —— 否则会给 rar
 * 长出一个点了就报错的解压按钮。
 *
 * 2026-09-14：本函数原是 `FileManagerRoot.kt` 的 `private fun`。详情弹窗（另一个包）也要按
 * 「能否解压」决定是否给解压按钮，所以搬到这份扩展名真源里，而不是在弹窗里再抄一份后缀表 ——
 * 两份后缀表一旦有一份忘改，就会重演本文件开头描述的"图标与行为对不上"。
 */
fun canExtract(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".zip") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ||
        lower.endsWith(".tar") || lower.endsWith(".gz")
}

/** 按文件名判类别。目录优先，不看扩展名。 */
fun fileKindOf(name: String, isDirectory: Boolean = false): FileKind {
    if (isDirectory) return FileKind.DIRECTORY
    val ext = name.substringAfterLast('.', "").lowercase()
    return EXTENSION_KINDS[ext] ?: FileKind.UNKNOWN
}

/**
 * 扩展名 → 类别。全库唯一一份。
 *
 * 新增扩展名时只改这里；三个消费点（图标 / 分发 / 菜单文案）自动跟上。
 */
private val EXTENSION_KINDS: Map<String, FileKind> = buildMap {
    // 图片：只列 Coil 3 默认解码器支持的格式
    listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
        .forEach { put(it, FileKind.IMAGE) }
    // 视频 / 音频：只列 media3 支持的容器
    listOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "flv", "avi")
        .forEach { put(it, FileKind.VIDEO) }
    listOf("mp3", "wav", "flac", "ogg", "aac", "m4a", "opus", "amr")
        .forEach { put(it, FileKind.AUDIO) }
    // 文本 / 代码 / 配置：一律进文本编辑器
    listOf(
        "txt", "log", "md", "csv", "ini", "conf", "cfg", "prop", "properties",
        "json", "xml", "yaml", "yml", "toml", "svg",
        "html", "htm", "css", "js", "ts", "kt", "kts", "java", "gradle",
        "sh", "bash", "zsh", "py", "c", "cpp", "h", "hpp", "go", "rs", "sql", "env",
        // lrc 是纯文本歌词（2026-09-14 补）。此前它落 UNKNOWN —— 音频预览会自动读它，
        // 但用户想在列表里点开改一个错字却只能看到详情弹窗，两处行为对不上。
        "lrc"
    ).forEach { put(it, FileKind.TEXT) }
    listOf("zip", "tgz", "tar", "gz", "bz2", "xz", "rar", "7z").forEach { put(it, FileKind.ARCHIVE) }
    put("apk", FileKind.APK)
    listOf("pdf", "doc", "docx", "odt", "xls", "xlsx", "ppt", "pptx")
        .forEach { put(it, FileKind.DOCUMENT) }
    // 刻意不列：wma / wmv（media3 不支持）、ico（Coil 不支持）——
    // 它们落到 UNKNOWN，点击给详情，比给一个打不开的图标诚实
}
