package com.ufi_axis.viewmodel.module

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import com.ufi_axis.data.api.FileItem
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.util.AppJson
import com.ufi_axis.util.DebugLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.ufi_axis.util.AppHttpClient
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.state.*
import com.ufi_axis.viewmodel.persistence.FileShortcutRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID

class FileManagerModule(
    private val api: UfiAxisApi,
    private val appContext: Context,
    private val shortcutRepo: FileShortcutRepository,
    private val scope: CoroutineScope
) {
    // ── State ──
    private val _initialViewMode: FileViewMode = runCatching {
        FileViewMode.valueOf(AppPreferences(appContext).fileViewMode)
    }.getOrElse { FileViewMode.LIST }

    private val _state = MutableStateFlow(FileManagerState(viewMode = _initialViewMode))
    val state: StateFlow<FileManagerState> = _state.asStateFlow()

    // ── Download to phone internals ──
    private var downloadJob: Job? = null

    // ── 书签 / 快捷路径持久化仓储（由构造注入，见 FileShortcutRepository）──
    private var uploadJob: Job? = null
    @Volatile private var _downloadPartialFile: File? = null
    private val _phoneHistoryPrefs by lazy {
        appContext.getSharedPreferences("phone_download_history", Context.MODE_PRIVATE)
    }

    // ── 存储访问能力检测（双域：Core 设备端 + 前端手机端）──
    // Core 端：授权在设备上完成，手机无法跨设备代开 → 仅经 HTTP 查询状态。
    // 前端手机端：下载到本机需要手机自身「所有文件访问权限」→ 在手机侧本地检测，可在本机设置页授权。
    fun checkStorageAccess() {
        scope.launch {
            val coreGranted = try {
                api.getStorageStatus().isExternalStorageManager
            } catch (e: Exception) {
                DebugLog.w("FileManager", "Failed to query Core storage status", e)
                false
            }
            val phoneGranted = isPhoneStorageManager()
            val allGranted = coreGranted && phoneGranted
            _state.update { it.copy(
                coreStorageGranted = coreGranted,
                phoneStorageGranted = phoneGranted,
                storagePermissionGranted = allGranted,
                showStoragePermissionDialog = !allGranted,
                transferAllowed = allGranted
            ) }
        }
    }

    /** 检测前端（手机端）自身是否已授予「所有文件访问权限」。 */
    private fun isPhoneStorageManager(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        // Android 10 及以下：写外部存储由 WRITE_EXTERNAL_STORAGE 覆盖，视为已具备
        true
    }

    fun dismissStoragePermissionDialog() {
        _state.update { it.copy(showStoragePermissionDialog = false) }
    }

    // ── File List ──
    private val cacheTtlMs = 30_000L

    /**
     * 读目录列表。
     *
     * ## 30s TTL 缓存的时钟（2026-09-05 改为单调时钟）
     * 原实现用 `System.currentTimeMillis()` 量 TTL。它可被用户 / NTP 随时改：往后跳会把
     * 刚写的缓存判成过期（白打请求），往前跳会把陈旧缓存判成新鲜（目录内容再也不刷新，
     * 而且偏移多大就锁多久）。缓存年龄是一段**时长**，必须用 `SystemClock.elapsedRealtime()`，
     * 口径与 `DataFreshness.kt` 一致。缓存只活在内存里（`FileManagerState.cacheByPath`），
     * 不跨进程持久化，所以 `elapsedRealtime` 重启归零不会造成误判。
     *
     * ## 为什么这里的 `isLoading = true` **不**收窄成"有数据时不写"
     * 与 Download / AppManager 那两处不同，本方法的数据是**按路径**的：现有 `files` 属于
     * 上一个目录。切目录时不置 `isLoading`，界面就会继续列着上一个目录的内容好几百毫秒
     * （在 core 慢或目录很大时更久），那是"点进 A 却显示 B 的内容"，比闪一下严重得多。
     * 而它也不构成"每次进页面都重播一次" —— 进页面链路是
     * `FileManagerRoot` 首帧 → `loadDiskUsage()`（本身不写 `isLoading`）→ 只有
     * `isInitialLoad`（`currentPath` 空且 `storageVolumes` 空）时才转到这里，
     * 也就是本进程真正的第一次；此后再进页面走的是 TTL 缓存那条早退分支。
     */
    fun loadFileList(path: String, force: Boolean = false) {
        // Serve from TTL cache without hitting the API while the entry is still fresh.
        val cached = _state.value.cacheByPath[path]
        if (!force && cached != null) {
            val now = SystemClock.elapsedRealtime()
            if (cached.fetchedAt + cacheTtlMs > now) {
                _state.update {
                    it.copy(
                        currentPath = path,
                        files = sortFiles(cached.files, _state.value.sortBy),
                        isLoading = false,
                        errorMessage = null
                    )
                }
                return
            }
        }
        scope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null, operationMessage = null) }
            try {
                val resp = api.listFiles(path)
                val sorted = sortFiles(resp.files, _state.value.sortBy)
                _state.update { it.copy(
                    currentPath = resp.path,
                    files = sorted,
                    isLoading = false,
                    // Backend error field is "error" (e.g. dir not found); "message" carries success info only
                    errorMessage = resp.error ?: resp.message
                ) }
                // Cache only on a successful (non-error) listing.
                if (resp.error == null) {
                    _state.update {
                        it.copy(
                            cacheByPath = it.cacheByPath +
                                (path to CachedListing(sorted, SystemClock.elapsedRealtime()))
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, errorMessage = "加载失败: ${e.message}") }
            }
        }
    }

    /** Drop a single path from the directory-listing TTL cache. */
    private fun evict(path: String) {
        _state.update { it.copy(cacheByPath = it.cacheByPath - path) }
    }

    fun navigateToDir(path: String) { loadFileList(path) }

    fun navigateToParent() {
        try {
            val current = _state.value.currentPath ?: ""
            val root = _state.value.storageRoot ?: ""
            val volumes = _state.value.storageVolumes ?: emptyList()
            if (current.isEmpty()) return
            val currentIsVolumeRoot = volumes.any { it.mountPath == current }
            if (currentIsVolumeRoot) {
                if (volumes.size > 1) {
                    // 多卷设备：回到虚拟根（卷列表）。
                    _state.update { it.copy(currentPath = "", files = emptyList(), isLoading = false) }
                } else if (root.isNotEmpty()) {
                    // 单卷设备：回到并刷新内部存储根，消除陈旧内容。
                    _state.update { it.copy(currentPath = "", files = emptyList(), isLoading = false) }
                    loadFileList(root)
                } else {
                    _state.update { it.copy(currentPath = "", files = emptyList(), isLoading = false) }
                }
                return
            }
            val parentDir = if (current.contains("/")) {
                current.substringBeforeLast("/", "").ifEmpty { "/" }
            } else {
                "/"
            }
            loadFileList(parentDir)
        } catch (e: Exception) {
            DebugLog.e("FileManager", "navigateToParent failed", e)
            _state.update { it.copy(isLoading = false, errorMessage = "返回上级失败: ${e.message}") }
        }
    }

    fun navigateToRoot() {
        try {
            val volumes = _state.value.storageVolumes
            val root = _state.value.storageRoot
            if (volumes.size > 1) {
                // 多卷设备：回到虚拟根（卷列表），不加载。
                _state.update { it.copy(currentPath = "", searchResults = null, isLoading = false, errorMessage = null) }
            } else if (root.isNotEmpty()) {
                // 单卷设备：刷新内部存储根，消除陈旧内容。
                _state.update { it.copy(currentPath = "", searchResults = null, isLoading = false, errorMessage = null) }
                loadFileList(root)
            } else {
                // 容错：无卷且无根，仅置空不加载。
                _state.update { it.copy(currentPath = "", searchResults = null, isLoading = false, errorMessage = null) }
            }
        } catch (e: Exception) {
            DebugLog.e("FileManager", "navigateToRoot failed", e)
            _state.update { it.copy(isLoading = false, errorMessage = "返回根目录失败: ${e.message}") }
        }
    }

    fun refreshFileList() { loadFileList(_state.value.currentPath) }

    // ── File Info / Content ──
    fun getFileInfo(path: String) {
        scope.launch {
            try { _state.update { it.copy(selectedFile = api.getFileInfo(path)) } }
            catch (e: Exception) { _state.update { it.copy(errorMessage = "获取文件信息失败: ${e.message}") } }
        }
    }

    fun dismissFileInfo() { _state.update { it.copy(selectedFile = null, fileContent = null) } }

    fun readFile(path: String) {
        scope.launch {
            try {
                val resp = api.readFile(mapOf("path" to path))
                // 服务端约定的截断标记：content 以 "[文件过大:" / "[二进制文件" 等前缀开头，
                // 且 size > content 字节数；为简化判断直接用 size > 0 && size > MAX_TRUNCATE_HINT
                val truncated = resp.size > 0 && resp.content.startsWith("[文件过大")
                _state.update { it.copy(
                    loadedFile = LoadedFile(
                        path = path,
                        content = resp.content,
                        size = resp.size,
                        truncated = truncated
                    ),
                    // 兼容旧 ImageViewerScreen 等使用 fileContent 的下游：
                    fileContent = resp.content
                ) }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "读取文件失败: ${e.message}") }
            }
        }
    }

    fun dismissFileContent() {
        _state.update { it.copy(fileContent = null, loadedFile = null) }
    }

    // ── CRUD ──
    fun deleteFileOrDir(path: String) {
        scope.launch {
            try {
                val resp = api.deleteFile(mapOf("path" to path))
                if (resp.success) { evict(_state.value.currentPath); refreshFileList(); _state.update { it.copy(operationMessage = "已删除") } }
                else _state.update { it.copy(errorMessage = "删除失败") }
            } catch (e: Exception) { _state.update { it.copy(errorMessage = "删除失败: ${e.message}") } }
        }
    }

    fun renameFile(oldPath: String, newName: String) {
        scope.launch {
            try {
                val parent = oldPath.substringBeforeLast("/")
                val newPath = "$parent/$newName"
                val resp = api.renameFile(mapOf("old_path" to oldPath, "new_path" to newPath))
                if (resp.success) { evict(parent); evict(_state.value.currentPath); refreshFileList(); _state.update { it.copy(operationMessage = "已重命名") } }
                else _state.update { it.copy(errorMessage = "重命名失败") }
            } catch (e: Exception) { _state.update { it.copy(errorMessage = "重命名失败: ${e.message}") } }
        }
    }

    fun copyToClipboard(path: String, isCut: Boolean) {
        _state.update { it.copy(clipboard = ClipboardEntry(listOf(path), isCut), operationMessage = if (isCut) "已剪切" else "已复制") }
    }

    fun clearClipboard() { _state.update { it.copy(clipboard = null, operationMessage = null) } }

    fun pasteFromClipboard(destinationDir: String) {
        val clip = _state.value.clipboard ?: return
        scope.launch {
            try {
                var successCount = 0
                for (srcPath in clip.sourcePaths) {
                    val fileName = srcPath.substringAfterLast("/")
                    val destPath = "$destinationDir/$fileName"
                    val resp = if (clip.isCut) api.moveFile(mapOf("source" to srcPath, "destination" to destPath)) else api.copyFile(mapOf("source" to srcPath, "destination" to destPath))
                    if (resp.success) successCount++
                }
                if (successCount > 0) {
                    val action = if (clip.isCut) "已移动" else "已粘贴"
                    val msg = if (successCount == clip.sourcePaths.size) "$action $successCount 项" else "$action $successCount/${clip.sourcePaths.size} 项"
                    // Invalidate cache for the destination dir and each source dir.
                    evict(_state.value.currentPath)
                    for (srcPath in clip.sourcePaths) evict(srcPath.substringBeforeLast("/"))
                    _state.update { it.copy(clipboard = null, operationMessage = msg) }
                    refreshFileList()
                } else { _state.update { it.copy(errorMessage = "粘贴失败") } }
            } catch (e: Exception) { _state.update { it.copy(errorMessage = "粘贴失败: ${e.message}") } }
        }
    }

    fun createDirectory(path: String) {
        scope.launch {
            try {
                val resp = api.createDirectory(mapOf("path" to path))
                if (resp.success) { evict(_state.value.currentPath); refreshFileList(); _state.update { it.copy(operationMessage = "已创建文件夹") } }
                else _state.update { it.copy(errorMessage = "创建文件夹失败") }
            } catch (e: Exception) { _state.update { it.copy(errorMessage = "创建文件夹失败: ${e.message}") } }
        }
    }

    fun createFile(name: String) {
        scope.launch {
            try {
                val path = "${_state.value.currentPath}/$name"
                val resp = api.touchFile(mapOf("path" to path))
                if (resp.success) { evict(_state.value.currentPath); refreshFileList(); _state.update { it.copy(operationMessage = "已创建文件") } }
                else _state.update { it.copy(errorMessage = "创建文件失败") }
            } catch (e: Exception) { _state.update { it.copy(errorMessage = "创建文件失败: ${e.message}") } }
        }
    }

    fun writeFile(path: String, content: String) {
        scope.launch {
            try {
                val resp = api.writeFile(mapOf("path" to path, "content" to content))
                if (resp.success) _state.update { it.copy(operationMessage = "已保存文件", fileContent = null) }
                else _state.update { it.copy(errorMessage = "保存文件失败") }
            } catch (e: Exception) { _state.update { it.copy(errorMessage = "保存文件失败: ${e.message}") } }
        }
    }

    // ── Disk Usage ──
    fun loadDiskUsage() {
        scope.launch {
            try {
                val resp = api.getDiskUsage()
                val volumes = resp.disks.map { disk ->
                    StorageVolume(
                        label = disk.label,
                        mountPath = disk.mount,
                        totalSize = disk.size,
                        usedSize = disk.used,
                        availSize = disk.available,
                        usePercent = disk.usePercent
                    )
                }
                val currentPath = _state.value.currentPath
                val isInitialLoad = currentPath.isEmpty() && _state.value.storageVolumes.isEmpty()

                if (volumes.size <= 1) {
                    val root = volumes.firstOrNull()?.mountPath?.ifEmpty { null } ?: "/storage/emulated/0"
                    val safeVolumes = if (volumes.isEmpty()) listOf(StorageVolume("内部存储", root, "", "", "", "")) else volumes
                    _state.update { it.copy(diskUsage = resp, storageVolumes = safeVolumes, storageRoot = root) }
                    if (isInitialLoad) loadFileList(root)
                } else {
                    _state.update { it.copy(diskUsage = resp, storageVolumes = volumes,
                        storageRoot = if (isInitialLoad) "" else _state.value.storageRoot,
                        currentPath = if (isInitialLoad) "" else currentPath,
                        files = if (isInitialLoad) emptyList() else _state.value.files,
                        isLoading = if (isInitialLoad) false else _state.value.isLoading) }
                }
            } catch (e: Exception) {
                DebugLog.w("FileManager", "loadDiskUsage failed, using fallback", e)
                val fallback = "/storage/emulated/0"
                val isInitialLoad = _state.value.currentPath.isEmpty() && _state.value.storageVolumes.isEmpty()
                _state.update { it.copy(storageRoot = fallback, storageVolumes = listOf(StorageVolume("内部存储", fallback, "", "", "", ""))) }
                if (isInitialLoad) loadFileList(fallback)
            }
        }
    }

    // ── Search ──
    fun searchFiles(query: String, depth: Int = _state.value.searchDepth) {
        scope.launch {
            try {
                val resp = api.searchFiles(_state.value.currentPath, query, depth)
                val files = resp.files
                _state.update { it.copy(searchResults = files) }
            } catch (e: Exception) { _state.update { it.copy(errorMessage = "搜索失败: ${e.message}") } }
        }
    }

    fun clearSearchResults() { _state.update { it.copy(searchResults = null) } }

    fun setSortBy(sort: String) {
        _state.update { it.copy(sortBy = sort) }
        refreshFileList()
    }

    // ── Multi-select ──
    fun toggleMultiSelectMode() {
        val newMode = !_state.value.multiSelectMode
        _state.update { it.copy(multiSelectMode = newMode, selectedPaths = emptySet()) }
    }

    fun toggleFileSelection(path: String) {
        _state.update { it.copy(selectedPaths = if (path in _state.value.selectedPaths) _state.value.selectedPaths - path else _state.value.selectedPaths + path) }
    }

    fun selectAllFiles() {
        _state.update { it.copy(selectedPaths = _state.value.files.map { f -> f.path }.toSet()) }
    }

    fun batchDeleteSelected() {
        scope.launch {
            val paths = _state.value.selectedPaths
            if (paths.isEmpty()) return@launch
            var success = 0
            for (path in paths) { try { if (api.deleteFile(mapOf("path" to path)).success) success++ } catch (e: Exception) { DebugLog.w("FileManager", "batchDelete: failed to delete $path", e) } }
            _state.update { it.copy(multiSelectMode = false, selectedPaths = emptySet(), operationMessage = "已删除 $success/${paths.size} 个文件") }
            evict(_state.value.currentPath)
            refreshFileList()
        }
    }

    fun batchCopySelected() {
        val paths = _state.value.selectedPaths
        if (paths.isEmpty()) return
        _state.update { it.copy(clipboard = ClipboardEntry(paths.toList(), false), multiSelectMode = false, selectedPaths = emptySet(), operationMessage = "已复制 ${paths.size} 个文件") }
    }

    fun batchCutSelected() {
        val paths = _state.value.selectedPaths
        if (paths.isEmpty()) return
        _state.update { it.copy(clipboard = ClipboardEntry(paths.toList(), true), multiSelectMode = false, selectedPaths = emptySet(), operationMessage = "已剪切 ${paths.size} 个文件") }
    }

    // ── URLs ──
    fun getDownloadUrl(path: String): String {
        val prefs = AppPreferences(appContext)
        return "http://${prefs.effectiveHost}:${prefs.serverPort}/api/files/download?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
    }

    fun getStreamUrl(path: String): String {
        val prefs = AppPreferences(appContext)
        return "http://${prefs.effectiveHost}:${prefs.serverPort}/api/files/stream?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
    }

    // ── Phone Download History ──
    fun loadPhoneDownloadHistory() {
            val jsonStr = _phoneHistoryPrefs.getString("history", null) ?: return
        try {
            val arr = AppJson.parseToJsonElement(jsonStr).jsonArray
            val items = arr.map { elem ->
                val obj = elem.jsonObject
                PhoneDownloadHistoryItem(
                    fileName = obj["fileName"]?.jsonPrimitive?.content ?: "",
                    fileSize = obj["fileSize"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    sourcePath = obj["sourcePath"]?.jsonPrimitive?.content ?: "",
                    downloadedAt = obj["downloadedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    status = obj["status"]?.jsonPrimitive?.content ?: "completed"
                )
            }
            _state.update { it.copy(phoneDownloadHistory = items) }
        } catch (e: Exception) { DebugLog.w("FileManager", "loadPhoneDownloadHistory: failed to parse", e) }
    }

    private fun savePhoneDownloadHistory(item: PhoneDownloadHistoryItem) {
        val current = _state.value.phoneDownloadHistory.toMutableList()
        current.add(0, item)
        val trimmed = if (current.size > 100) current.take(100) else current
        _state.update { it.copy(phoneDownloadHistory = trimmed) }
        try {
            _phoneHistoryPrefs.edit().putString("history", AppJson.encodeToString<List<PhoneDownloadHistoryItem>>(trimmed)).apply()
        } catch (e: Exception) { DebugLog.w("FileManager", "savePhoneDownloadHistory: failed to serialize", e) }
    }

    fun clearPhoneDownloadHistory() {
        _state.update { it.copy(phoneDownloadHistory = emptyList()) }
        _phoneHistoryPrefs.edit().remove("history").apply()
    }

    // ── Download to Phone ──
    fun downloadFileToPhone(path: String, fileName: String) {
        if (_state.value.isDownloading) return
        downloadJob?.cancel()
        downloadJob = scope.launch {
            _state.update { it.copy(isDownloading = true, downloadProgress = 0f, downloadFileName = fileName, downloadPath = path,
                downloadStatus = "downloading", downloadBytes = 0L, downloadTotalBytes = 0L,
                operationMessage = null, errorMessage = null) }
            val result = withContext(Dispatchers.IO) {
                try {
                    val prefs = AppPreferences(appContext)
                    val url = getStreamUrl(path)
                    val partialFile = _downloadPartialFile
                    val existingBytes = if (partialFile != null && partialFile.exists()) partialFile.length() else 0L
                    val client = AppHttpClient.instance
                    val requestBuilder = okhttp3.Request.Builder().url(url)
                    if (existingBytes > 0L) requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                    val response = client.newCall(requestBuilder.build()).execute()
                    val contentRange = response.header("Content-Range")
                    val body = response.body ?: throw Exception("空响应")
                    val contentLen = body.contentLength()
                    val totalSize: Long = when {
                        contentRange != null -> contentRange.substringAfterLast("/").toLongOrNull() ?: (existingBytes + contentLen.coerceAtLeast(0L))
                        contentLen > 0 -> existingBytes + contentLen
                        else -> -1L
                    }
                    val resumeFrom = if (existingBytes > 0L && response.code == 206) existingBytes else 0L
                    if (resumeFrom == 0L && existingBytes > 0L) partialFile?.delete()
                    val targetFile = partialFile?.takeIf { it.exists() } ?: File(appContext.cacheDir, "dl_${System.currentTimeMillis()}_$fileName")
                    withContext(Dispatchers.Main) {
                        _downloadPartialFile = targetFile
                        _state.update { it.copy(downloadTotalBytes = totalSize, downloadBytes = resumeFrom) }
                    }
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(targetFile, resumeFrom > 0L).use { output ->
                            val buf = ByteArray(8192)
                            var bytesDownloaded = resumeFrom
                            var lastUpdate = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                bytesDownloaded += n
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 500) {
                                    lastUpdate = now
                                    val progress = if (totalSize > 0) (bytesDownloaded.toFloat() / totalSize).coerceIn(0f, 0.99f) else -1f
                                    // MutableStateFlow.update 线程安全，直接在 IO 上下文更新，去掉每 500ms 的 Dispatchers.Main 跳变
                                    _state.update { it.copy(downloadProgress = progress, downloadBytes = bytesDownloaded) }
                                }
                            }
                            output.flush()
                            _state.update { it.copy(downloadProgress = 1f, downloadBytes = bytesDownloaded) }
                        }
                    }
                    try {
                        saveToDownloads(appContext, targetFile, fileName, totalSize)
                    } finally {
                        // 无论 MediaStore 写入成功与否，缓存的临时分片文件都必须清理，否则泄漏
                        targetFile.delete()
                    }
                    withContext(Dispatchers.Main) { _downloadPartialFile = null }
                    "success"
                } catch (e: CancellationException) { "cancelled" }
                catch (e: Exception) { "error:${e.localizedMessage ?: e.javaClass.simpleName}" }
            }
            when {
                result == "success" -> {
                    _state.update { it.copy(isDownloading = false, downloadStatus = "completed", operationMessage = "下载完成: $fileName") }
                    savePhoneDownloadHistory(PhoneDownloadHistoryItem(fileName = fileName, fileSize = _state.value.downloadTotalBytes, sourcePath = path, downloadedAt = System.currentTimeMillis(), status = "completed"))
                }
                result == "cancelled" -> _state.update { it.copy(isDownloading = false, downloadStatus = "paused") }
                else -> _state.update { it.copy(isDownloading = false, downloadStatus = "error", errorMessage = "下载失败: ${result.substringAfter("error:")}") }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel(); downloadJob = null
        _downloadPartialFile?.delete(); _downloadPartialFile = null
        _state.update { it.copy(isDownloading = false, downloadStatus = "idle", downloadProgress = -1f) }
    }

    fun resumeDownload(path: String, fileName: String) {
        if (_downloadPartialFile?.exists() == true) downloadFileToPhone(path, fileName)
    }

    private fun saveToDownloads(context: Context, file: File, fileName: String, fileSize: Long) {
        val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "")) ?: "application/octet-stream"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
            put(android.provider.MediaStore.Downloads.SIZE, fileSize)
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw Exception("无法创建下载文件")
        resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { input -> input.copyTo(output) } } ?: throw Exception("无法写入下载文件")
        values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    // ── Upload ──
    fun uploadFileToServer(localUri: Uri, targetDir: String) {
        if (_state.value.isUploading) return
        uploadJob = scope.launch {
            _state.update { it.copy(isUploading = true, uploadProgress = 0f, uploadFileName = "", operationMessage = null, errorMessage = null) }
            try {
                val prefs = AppPreferences(appContext)
                val url = "http://${prefs.effectiveHost}:${prefs.serverPort}/api/files/upload"
                val token = prefs.token
                val result = withContext(Dispatchers.IO) {
                    val mimeType = appContext.contentResolver.getType(localUri) ?: "application/octet-stream"
                    val fileName = try {
                        val rawName = appContext.contentResolver.query(localUri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) { val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (idx >= 0) cursor.getString(idx) ?: "uploaded_file" else "uploaded_file" } else "uploaded_file"
                        } ?: "uploaded_file"
                        if (!rawName.contains('.')) { val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType); if (ext != null) "$rawName.$ext" else rawName } else rawName
                    } catch (e: Exception) { DebugLog.w("FileManager", "upload: failed to resolve file name", e); "uploaded_file" }
                    withContext(Dispatchers.Main) { _state.update { it.copy(uploadFileName = fileName) } }
                    val totalSize = try { appContext.contentResolver.openFileDescriptor(localUri, "r")?.use { it.statSize } ?: -1L } catch (e: Exception) { DebugLog.w("FileManager", "upload: failed to get file size", e); -1L }
                    val client = AppHttpClient.instance
                    val mediaType = mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()!!
                    // 先复制到临时文件，避免重试时多次打开 ContentProvider
                    val tempFile = File(appContext.cacheDir, "upload_${System.currentTimeMillis()}_$fileName")
                    appContext.contentResolver.openInputStream(localUri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw Exception("无法读取文件")
                    fun buildRequestBody(): okhttp3.RequestBody {
                        val fileBody = object : okhttp3.RequestBody() {
                            override fun contentType() = mediaType
                            override fun contentLength() = totalSize
                            override fun writeTo(sink: okio.BufferedSink) = tempFile.inputStream().use { input ->
                                val buf = ByteArray(8192); var written = 0L
                                while (true) { val n = input.read(buf); if (n <= 0) break; sink.write(buf, 0, n); written += n; if (totalSize > 0 && written % 65536 < 8192) _state.update { it.copy(uploadProgress = (written.toFloat() / totalSize).coerceIn(0f, 0.99f)) } }
                            }
                        }
                        return okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM).addFormDataPart("path", targetDir).addFormDataPart("file", fileName, fileBody).build()
                    }
                    var lastError = ""
                    try {
                        for (attempt in 0..2) {
                            if (attempt > 0) { delay(1500L * attempt); withContext(Dispatchers.Main) { _state.update { it.copy(uploadProgress = 0f) } } }
                            val request = okhttp3.Request.Builder().url(url).post(buildRequestBody()).build()
                            val response = client.newCall(request).execute()
                            if (response.isSuccessful) return@withContext "success:$fileName"
                            if (response.code == 429 && attempt < 2) { response.close(); continue }
                            lastError = try { val errBody = response.body?.string() ?: ""; if (errBody.contains("error")) AppJson.parseToJsonElement(errBody).jsonObject["error"]?.jsonPrimitive?.content ?: "HTTP ${response.code}" else "HTTP ${response.code}" } catch (e: Exception) { DebugLog.w("FileManager", "upload: failed to parse error response", e); "HTTP ${response.code}" }
                            break
                        }
                    } finally {
                        tempFile.delete()
                    }
                    "error:$lastError"
                }
                if (result.startsWith("success:")) {
                    val fn = result.substringAfter("success:")
                    _state.update { it.copy(isUploading = false, uploadProgress = 1f, uploadFileName = "", operationMessage = "上传成功: $fn") }
                    evict(_state.value.currentPath)
                    refreshFileList()
                } else {
                    _state.update { it.copy(isUploading = false, uploadProgress = -1f, uploadFileName = "", operationMessage = "上传失败: ${result.substringAfter("error:")}") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isUploading = false, uploadProgress = -1f, uploadFileName = "", operationMessage = "上传失败: ${e.localizedMessage ?: e.javaClass.simpleName}") }
            }
        }
    }

    // ── Install APK ──
    fun installApk(path: String) {
        scope.launch {
            try {
                _state.update { it.copy(operationMessage = "正在安装 APK 到设备...") }
                val prefs = AppPreferences(appContext)
                val url = "http://${prefs.effectiveHost}:${prefs.serverPort}/api/apps/install"
                val result = withContext(Dispatchers.IO) {
                    val client = AppHttpClient.instance
                    val json = buildJsonObject { put("path", path) }
                    val mediaType = "application/json".toMediaTypeOrNull()!!
                    val body = json.toString().toRequestBody(mediaType)
                    val request = okhttp3.Request.Builder().url(url).post(body).addHeader("Authorization", "Bearer ${prefs.token}").build()
                    val response = client.newCall(request).execute()
                    AppJson.parseToJsonElement(response.body?.string() ?: "{}").jsonObject
                }
                val success = result["success"]?.jsonPrimitive?.content?.toBoolean() ?: false
                val message = result["message"]?.jsonPrimitive?.content ?: "未知结果"
                _state.update { it.copy(operationMessage = if (success) "安装成功: $message" else null, errorMessage = if (!success) "安装失败: $message" else null) }
            } catch (e: Exception) {
                _state.update { it.copy(operationMessage = null, errorMessage = "安装失败: ${e.localizedMessage ?: e.javaClass.simpleName}") }
            }
        }
    }

    // ── Misc ──
    fun clearFileOperationMessage() { _state.update { it.copy(operationMessage = null) } }

    // View Mode
    /** 切换列表/网格视图，并持久化到 SharedPreferences，避免退出重进后网格视图丢失。 */
    fun setViewMode(mode: FileViewMode) {
        _state.update { it.copy(viewMode = mode) }
        runCatching { AppPreferences(appContext).fileViewMode = mode.name }
            .onFailure { DebugLog.w("FileManager", "persist viewMode failed", it) }
    }

    // ── 书签 / 快捷路径（持久化到 SharedPreferences，经 FileShortcutRepository）──
    fun addBookmark(path: String) {
        val name = path.substringAfterLast("/").ifEmpty { path }
        val list = (_state.value.bookmarks + Bookmark(path, name)).distinctBy { it.path }
        _state.update { it.copy(bookmarks = list) }
        scope.launch { shortcutRepo.saveBookmarks(list) }
    }

    fun removeBookmark(path: String) {
        val list = _state.value.bookmarks.filter { it.path != path }
        _state.update { it.copy(bookmarks = list) }
        scope.launch { shortcutRepo.saveBookmarks(list) }
    }

    fun addQuickPath(path: String, label: String) {
        val id = UUID.randomUUID().toString()
        val list = (_state.value.quickPaths + QuickPath(id, path, label)).distinctBy { it.path }
        _state.update { it.copy(quickPaths = list) }
        scope.launch { shortcutRepo.saveQuickPaths(list) }
    }

    fun removeQuickPath(id: String) {
        val list = _state.value.quickPaths.filter { it.id != id }
        _state.update { it.copy(quickPaths = list) }
        scope.launch { shortcutRepo.saveQuickPaths(list) }
    }

    fun setFilterType(type: String) { _state.update { it.copy(filterType = type) } }

    fun setSearchOptions(depth: Int) { _state.update { it.copy(searchDepth = depth) } }

    fun cancelUpload() {
        uploadJob?.cancel(); uploadJob = null
        _state.update { it.copy(isUploading = false, uploadProgress = 0f, uploadFileName = "") }
    }

    private fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun filterByCategory(items: List<FileItem>, category: String): List<FileItem> {
        if (category == "all") return items
        val match: (String) -> Boolean = when (category) {
            "image" -> { e -> e in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico") }
            "video" -> { e -> e in setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "m4v") }
            "audio" -> { e -> e in setOf("mp3", "wav", "flac", "ogg", "aac", "m4a", "wma", "opus", "amr") }
            "document" -> { e -> e in setOf("pdf", "doc", "docx", "odt", "xls", "xlsx", "csv") }
            "archive" -> { e -> e in setOf("zip", "tar", "gz", "rar", "7z") }
            "apk" -> { e -> e == "apk" }
            "text" -> { e -> e in setOf("txt", "log", "md", "html", "htm", "css") }
            else -> { _ -> false }
        }
        return items.filter { it.isDirectory || match(extOf(it.name)) }
    }

    fun loadShortcuts() {
        scope.launch {
            val existingQuick = shortcutRepo.loadQuickPaths()
            if (existingQuick.isEmpty()) {
                // §8.2 首跑种子：6 条默认路径，逐条探测可达性，仅保留可达的；失败不阻塞首屏
                val seeds = listOf(
                    "/storage/emulated/0" to "内部存储",
                    "/storage/emulated/0/Download" to "下载",
                    "/storage/emulated/0/DCIM" to "相册",
                    "/storage/emulated/0/Documents" to "文档",
                    "/storage/emulated/0/Android/data" to "应用数据",
                    "/data" to "系统数据"
                )
                val reachable = seeds.mapNotNull { (p, lbl) ->
                    try {
                        val r = api.listFiles(p)
                        if (r.error == null) QuickPath(UUID.randomUUID().toString(), p, lbl) else null
                    } catch (e: Exception) { null }
                }
                shortcutRepo.saveQuickPaths(reachable)
                _state.update { it.copy(quickPaths = reachable) }
            } else {
                _state.update { it.copy(quickPaths = existingQuick) }
            }
            val bms = shortcutRepo.loadBookmarks()
            _state.update { it.copy(bookmarks = bms) }
        }
    }

    private fun sortFiles(files: List<FileItem>, sortBy: String): List<FileItem> {
        val dirs = files.filter { it.isDirectory || it.isSymlink }
        val regular = files.filter { !it.isDirectory && !it.isSymlink }
        val comparator: Comparator<FileItem> = when (sortBy) {
            "size" -> compareBy { it.size }
            "date" -> compareByDescending { it.lastModified }
            "type" -> compareBy { it.name.substringAfterLast(".", "").lowercase() }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        return dirs.sortedWith(comparator) + regular.sortedWith(comparator)
    }
}