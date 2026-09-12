package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.api.FileItem
import com.ufi_axis.data.api.FileInfoResponse
import com.ufi_axis.data.model.DiskUsageResponse
import kotlinx.serialization.Serializable

// ========== Cached Listing ==========

/**
 * Cache entry for a directory's file listing, with a TTL timestamp.
 *
 * @param files Snapshot (already sorted) of the files under the path.
 * @param fetchedAt `SystemClock.elapsedRealtime()` when this entry was written; used for TTL expiry.
 *   刻意用单调时钟而不是 epoch millis：TTL 是一段**时长**，`currentTimeMillis` 可被用户 / NTP
 *   改，往前跳会把陈旧缓存永久判成新鲜。口径与 `DataFreshness.kt` 一致。
 *   本缓存只活在内存里，不跨进程持久化，所以 `elapsedRealtime` 重启归零不会造成误判。
 */
data class CachedListing(
    val files: List<FileItem>,
    val fetchedAt: Long
)

// ========== Storage Volume ==========

data class StorageVolume(
    val label: String,     // "内部存储" / "SD卡" / "U盘"
    val mountPath: String, // "/storage/emulated/0"
    val totalSize: String,
    val usedSize: String,
    val availSize: String,
    val usePercent: String
)

// ========== Phone Download History ==========

@Serializable
data class PhoneDownloadHistoryItem(
    val fileName: String,
    val fileSize: Long,
    val sourcePath: String,
    val downloadedAt: Long,
    val status: String  // "completed", "cancelled", "error"
)

// ========== Clipboard Entry ==========

data class ClipboardEntry(
    val sourcePaths: List<String>,
    val isCut: Boolean
) {
    /** Backward-compatible single path accessor */
    val sourcePath: String get() = sourcePaths.first()
}

// ========== File Manager View Mode ==========

enum class FileViewMode { LIST, GRID }

// ========== Bookmark & Quick Path ==========

@Serializable
data class Bookmark(
    val path: String,
    val name: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Serializable
data class QuickPath(
    val id: String,
    val path: String,
    val label: String
)

/**
 * 一次文本读取的结果。
 *
 * 2026-09-11：取代旧的 `LoadedFile` + `FileManagerState.loadedFile` 那套「读进共享 state」的做法。
 * 文本编辑器改为直接 `suspend` 拿返回值，好处有两个：
 * - 不再与其它文件操作共用 `errorMessage` 槽（旧实现里编辑器的错误弹窗关不掉，正是因为
 *   它 dismiss 时调的函数只清 `operationMessage`）；
 * - 「重新加载」天然可用 —— 旧实现靠 `!isLoaded` 一次性守卫防覆盖，导致再读也不刷新。
 *
 * @param content 文本正文。[reason] 非空时它是服务端给的说明文字，不是文件内容。
 * @param size 文件真实字节数
 * @param encoding 服务端实际使用的解码字符集
 * @param reason 内容不可用的原因：`too_large` / `binary` / `not_file`；为空表示内容完整可用
 * @param encodingSuspect 以 UTF-8 解码时出现替换字符，可能是 GBK 等其它编码
 */
data class TextFileContent(
    val content: String,
    val size: Long,
    val encoding: String,
    val reason: String?,
    val encodingSuspect: Boolean
) {
    /** 内容是否可用（可展示 / 可编辑）。 */
    val usable: Boolean get() = reason == null

    companion object {
        const val REASON_TOO_LARGE = "too_large"
        const val REASON_BINARY = "binary"
        const val REASON_NOT_FILE = "not_file"
    }
}


// ========== File Manager ==========

data class FileManagerState(
    val currentPath: String = "",
    val files: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val isUploading: Boolean = false,
    val uploadProgress: Float = -1f,
    val uploadFileName: String = "",
    val isDownloading: Boolean = false,
    val downloadProgress: Float = -1f,
    val downloadFileName: String = "",
    val downloadPath: String = "",
    val downloadStatus: String = "idle",  // idle, downloading, paused, completed, error
    val downloadBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val phoneDownloadHistory: List<PhoneDownloadHistoryItem> = emptyList(),
    val errorMessage: String? = null,
    val clipboard: ClipboardEntry? = null,
    val selectedFile: FileInfoResponse? = null,
    val operationMessage: String? = null,
    val storagePermissionGranted: Boolean = false,
    val showStoragePermissionDialog: Boolean = false,
    val transferAllowed: Boolean = true,
    // 双域存储权限：Core（设备端）与 前端（手机端）各自独立
    val coreStorageGranted: Boolean = false,
    val phoneStorageGranted: Boolean = false,
    val diskUsage: DiskUsageResponse? = null,
    val storageVolumes: List<StorageVolume> = emptyList(),
    val storageRoot: String = "",  // Navigation floor: can't go above this
    val searchResults: List<FileItem>? = null,
    val sortBy: String = "name",
    val multiSelectMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    // ---- T2 状态层扩展：书签 / 快捷路径 / 视图模式 ----
    val viewMode: FileViewMode = FileViewMode.LIST,
    val transferBarMinimized: Boolean = false,
    val bookmarks: List<Bookmark> = emptyList(),
    val quickPaths: List<QuickPath> = emptyList(),
    val filterType: String = "all",
    val searchDepth: Int = 3,
    // ---- Caching (iteration 2): TTL-based directory listing cache ----
    val cacheByPath: Map<String, CachedListing> = emptyMap(),
    val cacheConfigTtlMs: Long = 30_000L,
    // ---- 编辑器本地副本缓存（2026-09-11）----
    // 超过服务端单次可读上限的文本文件无法在线阅读，改为整份下载到手机缓存后再编辑。
    // 这四项目的是让「下载中」有进度可看、让「缓存清理」有数字可显示。
    val editorCacheBytes: Long = 0L,
    val editorCacheCount: Int = 0,
    val editorFetching: Boolean = false,
    /** 0f~1f；-1f = 总量未知（无 Content-Length 且无 Range 头）。 */
    val editorFetchProgress: Float = -1f,
    val editorFetchFileName: String = ""
)
