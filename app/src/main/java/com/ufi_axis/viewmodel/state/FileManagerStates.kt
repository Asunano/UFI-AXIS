package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.api.FileItem
import com.ufi_axis.data.api.FileInfoResponse

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
    val downloadStatus: String = "idle",  // idle, downloading, paused, completed, error
    val downloadBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val phoneDownloadHistory: List<PhoneDownloadHistoryItem> = emptyList(),
    val errorMessage: String? = null,
    val clipboard: ClipboardEntry? = null,
    val selectedFile: FileInfoResponse? = null,
    val fileContent: String? = null,
    val operationMessage: String? = null,
    val rootMode: Boolean = false,
    val rootStatusChecked: Boolean = false,
    val hasRootAccess: Boolean = true,
    val storagePermissionGranted: Boolean = false,
    val showStoragePermissionDialog: Boolean = false,
    val transferAllowed: Boolean = true,
    val pendingProtectedPath: String? = null,
    val diskUsage: com.google.gson.JsonElement? = null,
    val storageVolumes: List<StorageVolume> = emptyList(),
    val storageRoot: String = "",  // Navigation floor: can't go above this
    val searchResults: List<FileItem>? = null,
    val sortBy: String = "name",
    val multiSelectMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet()
)
