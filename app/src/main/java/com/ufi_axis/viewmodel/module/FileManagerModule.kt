package com.ufi_axis.viewmodel.module

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.google.gson.JsonParser
import com.ufi_axis.data.api.FileItem
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.util.AppGson
import com.ufi_axis.util.DebugLog
import com.ufi_axis.util.AppHttpClient
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class FileManagerModule(
    private val api: UfiAxisApi,
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private val gson = AppGson.instance
    // ── State ──
    private val _state = MutableStateFlow(FileManagerState())
    val state: StateFlow<FileManagerState> = _state.asStateFlow()

    // ── Download to phone internals ──
    private var downloadJob: Job? = null
    private var _downloadPartialFile: File? = null
    private val _phoneHistoryPrefs by lazy {
        appContext.getSharedPreferences("phone_download_history", Context.MODE_PRIVATE)
    }

    // ── Root 权限检测 ──
    fun checkRootAccess() {
        scope.launch {
            try {
                val resp = api.checkRootAccess()
                val hasRoot = resp.hasRoot
                _state.update { it.copy(
                    rootStatusChecked = true,
                    hasRootAccess = hasRoot,
                    transferAllowed = hasRoot
                ) }
                if (!hasRoot) {
                    checkStoragePermission()
                }
            } catch (e: Exception) {
                DebugLog.w("FileManager", "checkRootAccess failed, defaulting to allowed", e)
                _state.update { it.copy(rootStatusChecked = true) }
            }
        }
    }

    private fun checkStoragePermission() {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        _state.update { it.copy(
            storagePermissionGranted = granted,
            showStoragePermissionDialog = !granted,
            transferAllowed = granted
        ) }
    }

    fun dismissStoragePermissionDialog() {
        _state.update { it.copy(showStoragePermissionDialog = false) }
    }

    // ── File List ──
    fun loadFileList(path: String) {
        scope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null, operationMessage = null, pendingProtectedPath = null) }
            try {
                val force = _state.value.rootMode
                val resp = api.listFiles(path, force)
                if (resp.error == "PROTECTED_PATH") {
                    _state.update { it.copy(isLoading = false, pendingProtectedPath = path) }
                } else {
                    _state.update { it.copy(
                        currentPath = resp.path,
                        files = sortFiles(resp.files, _state.value.sortBy),
                        isLoading = false,
                        errorMessage = resp.message
                    ) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, errorMessage = "加载失败: ${e.message}") }
            }
        }
    }

    fun navigateToDir(path: String) { loadFileList(path) }

    fun navigateToParent() {
        val current = _state.value.currentPath
        val root = _state.value.storageRoot
        val volumes = _state.value.storageVolumes
        if (current.isEmpty()) return
        if (current == root && root.isNotEmpty()) return
        val currentIsVolumeRoot = volumes.any { it.mountPath == current }
        if (currentIsVolumeRoot) {
            if (volumes.size > 1) {
                _state.update { it.copy(currentPath = "", files = emptyList(), isLoading = false) }
            }
            return
        }
        val parentDir = current.substringBeforeLast("/", "").ifEmpty { "/" }
        loadFileList(parentDir)
    }

    fun refreshFileList() { loadFileList(_state.value.currentPath) }

    fun toggleRootMode() {
        val newMode = !_state.value.rootMode
        _state.update { it.copy(rootMode = newMode) }
        loadFileList(_state.value.currentPath)
    }

    fun forceNavigate(path: String) {
        _state.update { it.copy(rootMode = true, pendingProtectedPath = null) }
        loadFileList(path)
    }

    fun dismissProtectedPath() { _state.update { it.copy(pendingProtectedPath = null) } }

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
            try { _state.update { it.copy(fileContent = api.readFile(mapOf("path" to path)).content) } }
            catch (e: Exception) { _state.update { it.copy(errorMessage = "读取文件失败: ${e.message}") } }
        }
    }

    fun dismissFileContent() { _state.update { it.copy(fileContent = null) } }

    // ── CRUD ──
    fun deleteFileOrDir(path: String) {
        scope.launch {
            try {
                val resp = api.deleteFile(mapOf("path" to path))
                if (resp.success) { refreshFileList(); _state.update { it.copy(operationMessage = "已删除") } }
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
                if (resp.success) { refreshFileList(); _state.update { it.copy(operationMessage = "已重命名") } }
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
                if (resp.success) { refreshFileList(); _state.update { it.copy(operationMessage = "已创建文件夹") } }
                else _state.update { it.copy(errorMessage = "创建文件夹失败") }
            } catch (e: Exception) { _state.update { it.copy(errorMessage = "创建文件夹失败: ${e.message}") } }
        }
    }

    fun createFile(name: String) {
        scope.launch {
            try {
                val path = "${_state.value.currentPath}/$name"
                val resp = api.touchFile(mapOf("path" to path))
                if (resp.success) { refreshFileList(); _state.update { it.copy(operationMessage = "已创建文件") } }
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

    fun chmodFile(path: String, mode: String) {
        scope.launch {
            try {
                val resp = api.chmodFile(mapOf("path" to path, "mode" to mode))
                if (resp.success) { refreshFileList(); _state.update { it.copy(operationMessage = "权限已修改") } }
                else _state.update { it.copy(errorMessage = "修改权限失败") }
            } catch (e: Exception) { _state.update { it.copy(errorMessage = "修改权限失败: ${e.message}") } }
        }
    }

    // ── Disk Usage ──
    fun loadDiskUsage() {
        scope.launch {
            try {
                val raw = api.getDiskUsage()
                val json = JsonParser.parseString(raw.toString()).asJsonObject
                val disksArr = json.getAsJsonArray("disks")
                val volumes = disksArr?.map { disk ->
                    val obj = disk.asJsonObject
                    StorageVolume(
                        label = obj.get("label")?.asString ?: "",
                        mountPath = obj.get("mount")?.asString ?: "",
                        totalSize = obj.get("size")?.asString ?: "",
                        usedSize = obj.get("used")?.asString ?: "",
                        availSize = obj.get("available")?.asString ?: "",
                        usePercent = obj.get("usePercent")?.asString ?: ""
                    )
                } ?: emptyList()
                val currentPath = _state.value.currentPath
                val isInitialLoad = currentPath.isEmpty() && _state.value.storageVolumes.isEmpty()

                if (volumes.size <= 1) {
                    val root = volumes.firstOrNull()?.mountPath?.ifEmpty { null } ?: "/storage/emulated/0"
                    val safeVolumes = if (volumes.isEmpty()) listOf(StorageVolume("内部存储", root, "", "", "", "")) else volumes
                    _state.update { it.copy(diskUsage = raw, storageVolumes = safeVolumes, storageRoot = root) }
                    if (isInitialLoad) loadFileList(root)
                } else {
                    _state.update { it.copy(diskUsage = raw, storageVolumes = volumes,
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
    fun searchFiles(query: String) {
        scope.launch {
            try {
                val resp = api.searchFiles(_state.value.currentPath, query)
                val files = resp.asJsonObject?.getAsJsonArray("files")?.mapNotNull {
                    try {
                        val obj = it.asJsonObject
                        FileItem(name = obj.get("name")?.asString ?: "", path = obj.get("path")?.asString ?: "",
                            isDirectory = obj.get("isDirectory")?.asBoolean ?: false,
                            size = 0, lastModified = 0, permissions = "", isSymlink = false)
                    } catch (e: Exception) { DebugLog.w("FileManager", "searchFiles: failed to parse file item", e); null }
                } ?: emptyList()
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
        _state.update { it.copy(multiSelectMode = newMode, selectedPaths = if (newMode) emptySet() else _state.value.selectedPaths) }
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
        return "http://${prefs.serverIp}:${prefs.serverPort}/api/files/download?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
    }

    fun getStreamUrl(path: String): String {
        val prefs = AppPreferences(appContext)
        return "http://${prefs.serverIp}:${prefs.serverPort}/api/files/stream?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
    }

    // ── Phone Download History ──
    fun loadPhoneDownloadHistory() {
        val jsonStr = _phoneHistoryPrefs.getString("history", null) ?: return
        try {
            val arr = JsonParser.parseString(jsonStr).asJsonArray
            val items = arr.map { elem ->
                val obj = elem.asJsonObject
                PhoneDownloadHistoryItem(
                    fileName = obj.get("fileName")?.asString ?: "",
                    fileSize = obj.get("fileSize")?.asLong ?: 0L,
                    sourcePath = obj.get("sourcePath")?.asString ?: "",
                    downloadedAt = obj.get("downloadedAt")?.asLong ?: 0L,
                    status = obj.get("status")?.asString ?: "completed"
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
            val arr = com.google.gson.JsonArray()
            trimmed.forEach { h ->
                val obj = com.google.gson.JsonObject()
                obj.addProperty("fileName", h.fileName)
                obj.addProperty("fileSize", h.fileSize)
                obj.addProperty("sourcePath", h.sourcePath)
                obj.addProperty("downloadedAt", h.downloadedAt)
                obj.addProperty("status", h.status)
                arr.add(obj)
            }
            _phoneHistoryPrefs.edit().putString("history", arr.toString()).apply()
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
            _state.update { it.copy(isDownloading = true, downloadProgress = 0f, downloadFileName = fileName,
                downloadStatus = "downloading", downloadBytes = 0L, downloadTotalBytes = 0L,
                operationMessage = null, errorMessage = null) }
            val result = withContext(Dispatchers.IO) {
                try {
                    val prefs = AppPreferences(appContext)
                    val url = getStreamUrl(path)
                    val partialFile = _downloadPartialFile
                    val existingBytes = if (partialFile != null && partialFile.exists()) partialFile.length() else 0L
                    val client = AppHttpClient.instance
                    val requestBuilder = okhttp3.Request.Builder().url(url).addHeader("Authorization", "Bearer ${prefs.token}")
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
                                    withContext(Dispatchers.Main) { _state.update { it.copy(downloadProgress = progress, downloadBytes = bytesDownloaded) } }
                                }
                            }
                            output.flush()
                            withContext(Dispatchers.Main) { _state.update { it.copy(downloadProgress = 1f, downloadBytes = bytesDownloaded) } }
                        }
                    }
                    saveToDownloads(appContext, targetFile, fileName, totalSize)
                    targetFile.delete()
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
        scope.launch {
            _state.update { it.copy(isUploading = true, uploadProgress = 0f, uploadFileName = "", operationMessage = null, errorMessage = null) }
            try {
                val prefs = AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/files/upload"
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
                            val request = okhttp3.Request.Builder().url(url).addHeader("Authorization", "Bearer $token").post(buildRequestBody()).build()
                            val response = client.newCall(request).execute()
                            if (response.isSuccessful) return@withContext "success:$fileName"
                            if (response.code == 429 && attempt < 2) { response.close(); continue }
                            lastError = try { val errBody = response.body?.string() ?: ""; if (errBody.contains("error")) gson.fromJson(errBody, Map::class.java)["error"]?.toString() ?: "HTTP ${response.code}" else "HTTP ${response.code}" } catch (e: Exception) { DebugLog.w("FileManager", "upload: failed to parse error response", e); "HTTP ${response.code}" }
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
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/apps/install"
                val result = withContext(Dispatchers.IO) {
                    val client = AppHttpClient.instance
                    val json = com.google.gson.JsonObject().apply { addProperty("path", path) }
                    val mediaType = "application/json".toMediaTypeOrNull()!!
                    val body = json.toString().toRequestBody(mediaType)
                    val request = okhttp3.Request.Builder().url(url).post(body).addHeader("Authorization", "Bearer ${prefs.token}").build()
                    val response = client.newCall(request).execute()
                    com.google.gson.JsonParser.parseString(response.body?.string() ?: "{}").asJsonObject
                }
                val success = result.get("success")?.asBoolean ?: false
                val message = result.get("message")?.asString ?: "未知结果"
                _state.update { it.copy(operationMessage = if (success) "安装成功: $message" else null, errorMessage = if (!success) "安装失败: $message" else null) }
            } catch (e: Exception) {
                _state.update { it.copy(operationMessage = null, errorMessage = "安装失败: ${e.localizedMessage ?: e.javaClass.simpleName}") }
            }
        }
    }

    // ── Misc ──
    fun clearFileOperationMessage() { _state.update { it.copy(operationMessage = null) } }

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