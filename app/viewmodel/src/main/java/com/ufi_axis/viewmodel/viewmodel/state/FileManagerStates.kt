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
 * 已加载的文本文件 — 按 path 区分，解决切换文件时仍显示上一个文件内容的缓存 bug。
 *
 * @param path 加载目标路径，与 [FileManagerModule.readFile] 入参一一对应
 * @param content 已读取的文本（即使是 [truncated] 也保留完整 content，只是 UI 强制只读）
 * @param size 服务端 /read 返回的 size 字段（字节数）
 * @param truncated true 表示服务端在 [MAX_READ_SIZE] 处截断（见 core FileRoutes.MAX_READ_SIZE=512KB），
 *                  UI 应强制只读并提示「仅显示前 X 行 / 下载查看完整」避免 OOM
 */
data class LoadedFile(
    val path: String,
    val content: String,
    val size: Int,
    val truncated: Boolean
)

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
    val fileContent: String? = null,
    /** 当前加载的文件元数据（path/content/size/truncated），用以解决旧 [fileContent] 的「切文件不刷新」缓存 bug */
    val loadedFile: LoadedFile? = null,
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
    val cacheConfigTtlMs: Long = 30_000L
)
