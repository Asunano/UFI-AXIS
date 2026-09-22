package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.api.FileItem
import com.ufi_axis.data.api.FileInfoResponse
import com.ufi_axis.data.api.ArchiveChecksumResponse
import com.ufi_axis.data.api.StorageSourceInfo
import com.ufi_axis.data.api.RemotePushJobInfo
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

/**
 * 一个可进入的存储位置。
 *
 * 2026-09-21：加了 [protocol] / [sourceId] 两项以容纳外部存储源（FTP / WebDAV）。
 * 本地卷两项都是 `"local"` —— 缺省值就是本地，既有构造点（[com.ufi_axis.viewmodel.module.FileManagerModule.loadDiskUsage]
 * 里那几处）一行都不用改。
 */
data class StorageVolume(
    val label: String,     // "内部存储" / "SD卡" / "U盘"
    val mountPath: String, // "/storage/emulated/0"
    val totalSize: String,
    val usedSize: String,
    val availSize: String,
    val usePercent: String,
    /** `"local"` / `"ftp"` / `"webdav"` / `"smb"`。 */
    val protocol: String = "local",
    /** 远端源 id；本地恒为 `"local"`。 */
    val sourceId: String = "local"
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
    /**
     * 本批共几个文件、已传完几个（2026-09-19 支持多选上传后加的）。
     *
     * `uploadProgress` / `uploadFileName` 仍然只描述**当前这一个**文件 ——
     * 进度条要反映单文件的推进，否则 10 个小文件会让条子来回跳。
     * 批次进度靠这两个计数在文案里体现（「上传中：xxx (3/7)」）。
     *
     * 0 表示没有批次在跑；单文件上传时是 1，界面据此不显示 (1/1) 这种废话。
     */
    val uploadTotalCount: Int = 0,
    val uploadDoneCount: Int = 0,
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
    /**
     * 可用的外部存储源快照（只含 `enabled` 的那些）。
     *
     * 文件管理器用它做两件事：虚拟根里列出「外部存储」入口、按 `capabilities` 决定
     * 当前目录该隐藏哪些动作。拉不到时保持空表 —— 远端源列表不该拖垮本地文件管理。
     * 配置页有自己那份全量列表（[StorageSourceState.sources]，含停用的）。
     */
    val remoteSources: List<StorageSourceInfo> = emptyList(),

    /**
     * **全部**外部存储源（含已停用的），供「选存储」那一层做管理用。
     *
     * 与 [remoteSources] 的区别：那一份只有启用的，语义是"可以进去浏览的源"；
     * 这一份是"配置里有的源"。独立的外部存储列表页已删除，管理职责搬到了存储列表，
     * 所以这一层必须能看到停用的源 —— 否则它既不能被重新启用、也不能被删掉。
     */
    val allRemoteSources: List<StorageSourceInfo> = emptyList(),


    /**
     * 远端上传第二阶段（core 暂存 → 外部存储源）的作业列表，来自
     * `GET /api/files/remote-push` 的轮询快照，新的在前。
     *
     * 为什么它必须在 state 里、而不是"传完就算了"：手机那一段传完之后，文件只在 core 上，
     * 真正落到远端还要排队 + 整份推送。不把这一段暴露出来，用户会看到"上传完成"
     * 却在远端找不到文件，而且没有任何取消 / 重试入口。
     */
    val remotePushJobs: List<RemotePushJobInfo> = emptyList(),

    /**
     * 设备端是否支持远端上传（`/api/files/status` 的 `supports_remote_upload`）。
     *
     * 默认 false：老固件没有这条链路，远端目录里的上传入口必须整个撤掉。
     */
    val remotePushSupported: Boolean = false,


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
    val editorFetchFileName: String = "",
    // ---- 校验和弹窗（2026-09-12）：checksumFile 成功后写入，UI 据此挂载 ChecksumDialog ----
    val checksumResult: ArchiveChecksumResponse? = null
)
