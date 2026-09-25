package com.ufi_axis.viewmodel.module

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import com.ufi_axis.data.api.FileItem
import com.ufi_axis.data.api.RemotePushJobInfo
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.AppInstallRequest
import com.ufi_axis.data.upload.ChunkedFileUploader
import com.ufi_axis.util.AppJson
import com.ufi_axis.util.DebugLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.ufi_axis.util.AppHttpClient
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.state.*
import com.ufi_axis.viewmodel.persistence.FileShortcutRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import java.util.UUID

/** 编辑器本地副本的缓存目录名（位于 `appContext.cacheDir` 之下）。 */
private const val EDITOR_CACHE_DIR = "editor"

/** 本地副本缓存总量上限（64MB），超出时按最后修改时间 LRU 淘汰。 */
private const val EDITOR_CACHE_MAX_BYTES = 64L * 1024 * 1024

/**
 * 单个文件允许缓存的上限（32MB）。
 *
 * 不是磁盘限制而是**编辑器**限制：编辑态是单个 BasicTextField，
 * 再大的文本进去只会卡死，下载它纯属白占空间。
 */
private const val EDITOR_CACHE_MAX_ITEM_BYTES = 32L * 1024 * 1024

/** 下载进度回写 state 的最小间隔，避免每 64KB 就踢一次重组。 */
private const val EDITOR_CACHE_PROGRESS_TICK_MS = 300L

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

    companion object {
        /**
         * 远端路径前缀。完整形态是 `remote:<sourceId>/<相对路径>`。
         *
         * 这个前缀是 **core 定的契约**，不是 app 的内部约定：同一批 `/api/files/…` 端点

         * 靠它判断该走本地文件系统还是某个 FTP / WebDAV provider。改它必须两端一起改。
         */
        const val REMOTE_PREFIX = "remote:"

        /**
         * 「下载到本机」的落点子目录（相对 `Download/`）。
         *
         * 收进自己的子目录而不是 Download 根：Download 根是各家应用的公共垃圾场，
         * 从远端源一次拉回来几个文件混进去就再也找不着。
         */
        const val DOWNLOAD_SUBDIR = "UFI-AXIS"

        // ── 远端推送作业状态（与 core 的 `RemotePushManager.State` 小写形态一一对应）──
        const val PUSH_STATE_QUEUED = "queued"
        const val PUSH_STATE_PUSHING = "pushing"
        const val PUSH_STATE_SUCCESS = "success"
        const val PUSH_STATE_FAILED = "failed"
        const val PUSH_STATE_CANCELLED = "cancelled"

        /**
         * 推送作业的轮询间隔。
         *
         * 1.5s 的取值：这一段的进度是"整份文件流式推送"，比手机那一段慢得多，
         * 再密的采样只是多打请求；再稀就会让"推送完成"的提示明显滞后于事实。
         */
        private const val PUSH_POLL_INTERVAL_MS = 1500L

        /** 连续拉取失败时的退避上限：设备离线时不再按 1.5s 空转。 */
        private const val PUSH_POLL_MAX_INTERVAL_MS = 30_000L

        /** 连续拉取失败到这个次数就停止轮询并报"无法获取推送进度"。 */
        private const val PUSH_POLL_MAX_FAILURES = 8
    }

    /** 判断路径是否指向远端存储源。 */
    fun isRemotePath(path: String): Boolean = path.startsWith(REMOTE_PREFIX)

    /** 从远端路径提取 sourceId。`remote:abc123/photos` → `abc123`；取不到时返回 null。 */
    fun remoteSourceIdOf(path: String): String? {
        if (!isRemotePath(path)) return null
        val id = path.removePrefix(REMOTE_PREFIX).substringBefore('/')
        return id.ifEmpty { null }
    }

    /** 远端源的根路径。**带尾斜杠**——`remote:abc123` 与 `remote:abc123/` 在 core 侧不等价。 */
    fun remoteRootOf(sourceId: String): String = "$REMOTE_PREFIX$sourceId/"

    /**
     * 把「目录 + 条目名」拼成子路径，保证**不出现双斜杠**。
     *
     * 为什么必须有这个函数：远端源根的规范形态是 `remote:<id>/`（带尾斜杠，见 [remoteRootOf]），
     * 直接 `"$dir/$name"` 会得到 `remote:<id>//name`。core 侧 `FileProviderRegistry.resolve`
     * 只切第一个 `/`，于是 provider 拿到的相对路径是 `//name` —— WebDAV 拼出非规范 URL、
     * FTP/SMB 则当成另一层空目录，用户看到的是莫名的失败。本地路径同理（`/sdcard/` 结尾）。
     */
    fun childPath(dir: String, name: String): String =
        "${dir.trimEnd('/')}/${name.trim('/')}"

    /**
     * 当前路径所在源是否支持某能力（`EXTRACT` / `COMPRESS` / `CHECKSUM` / `SEARCH` …）。
     *
     * 本地源恒为 true —— 本地什么都能做，能力清单只是远端 provider 的概念。
     * 远端源在 [FileManagerState.remoteSources] 里找不到时返回 false（保守侧）：
     * 那意味着源列表还没拉到或该源已被停用，此刻放行只会让用户点到一个必然失败的动作。
     */
    fun supportsCapability(path: String, capability: String): Boolean {
        if (!isRemotePath(path)) return true
        val sourceId = remoteSourceIdOf(path) ?: return false
        val src = _state.value.remoteSources.firstOrNull { it.id == sourceId } ?: return false
        return capability in src.capabilities
    }

    /**
     * 拉一份外部存储源快照，存**两份**：
     *  - [com.ufi_axis.viewmodel.state.FileManagerState.remoteSources]：只有 `enabled` 的，
     *    用于虚拟根的浏览入口、存储切换条与能力判定 —— 停用的源点进去也连不上；
     *  - [com.ufi_axis.viewmodel.state.FileManagerState.allRemoteSources]：全部，
     *    用于「选存储」那一层的**管理**（长按编辑 / 测试 / 删除）。
     *
     * 为什么要第二份：独立的「外部存储」列表页已经删掉（它列的东西和存储列表完全重复），
     * 管理职责搬到了存储列表这一层。如果这里只拿 `enabled` 的，停用的源就彻底没有入口 ——
     * 既看不到、也没法重新启用或删除，只能靠重装。
     */
    fun loadRemoteSources() {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { api.listStorageSources() }
                _state.update {
                    it.copy(
                        remoteSources = resp.sources.filter { s -> s.enabled },
                        allRemoteSources = resp.sources
                    )
                }
            } catch (_: Exception) {
                // 远端源列表拉不到不该影响本地文件管理，静默失败
            }
        }
    }


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
            val status = try {
                api.getStorageStatus()
            } catch (e: Exception) {
                DebugLog.w("FileManager", "Failed to query Core storage status", e)
                null
            }
            val coreGranted = status?.isExternalStorageManager == true
            val phoneGranted = isPhoneStorageManager()
            val allGranted = coreGranted && phoneGranted
            _state.update { it.copy(
                coreStorageGranted = coreGranted,
                phoneStorageGranted = phoneGranted,
                storagePermissionGranted = allGranted,
                showStoragePermissionDialog = !allGranted,
                transferAllowed = allGranted,
                // 查不到状态时按"不支持"处理：远端上传入口宁可不出现，也不能点了回 400
                remotePushSupported = status?.supports_remote_upload == true
            ) }
        }
        // 外部存储源与本地权限无关（它连的是别的机器），所以不等上面那次查询、也不看它的结果。
        loadRemoteSources()
        // 进页面就拉一次推送作业：上一次上传的推送可能还在跑（core 侧作业不随 app 前后台走）
        loadRemotePushJobs()
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
        // 换目录必须丢掉旧选择集：否则多选态下切走，批量删除/复制作用的还是上一个目录的路径。
        // 只在真的切路径时清（refreshFileList 走同一入口，同目录刷新不该把用户的勾选抹掉）。
        val switching = path != _state.value.currentPath
        // Serve from TTL cache without hitting the API while the entry is still fresh.
        val cached = _state.value.cacheByPath[path]
        if (!force && cached != null) {
            val now = SystemClock.elapsedRealtime()
            if (cached.fetchedAt + cacheTtlMs > now) {
                _state.update {
                    it.copy(
                        currentPath = path,
                        files = sortFiles(cached.files, _state.value.sortBy),
                        selectedPaths = if (switching) emptySet() else it.selectedPaths,
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
                    selectedPaths = if (switching) emptySet() else it.selectedPaths,
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

    /**
     * 回到「能挑存储」的那一层。
     *
     * 虚拟根（卷列表 + 外部存储列表）只在**确实有东西可挑**时才渲染
     * （见 `FileManagerRoot.isVirtualRoot`：卷数 > 1 或有远端源）。单卷设备上若远端源列表
     * 一条都没拉到（`loadRemoteSources` 静默失败），把 `currentPath` 置空会得到一个
     * 既不是虚拟根、列表也为空的白页；而 `canBack`（`currentPath != storageRoot`）此刻同样是
     * false —— 连返回按钮都没有，人被卡在空页里只能退出整个文件管理器。
     * 所以这种情况直接落到本地 `storageRoot`，而不是置空。
     */
    private fun resetToLanding() {
        val volumes = _state.value.storageVolumes
        val root = _state.value.storageRoot
        val hasVirtualRoot = volumes.isNotEmpty() &&
            (volumes.size > 1 || _state.value.remoteSources.isNotEmpty())
        _state.update { it.copy(
            currentPath = "",
            files = emptyList(),
            selectedPaths = emptySet(),
            searchResults = null,
            isLoading = false,
            errorMessage = null
        ) }
        if (!hasVirtualRoot && root.isNotEmpty()) loadFileList(root)
    }

    fun navigateToParent() {
        try {
            val current = _state.value.currentPath ?: ""
            val volumes = _state.value.storageVolumes ?: emptyList()
            if (current.isEmpty()) return
            // 远端路径：到源根（remote:xxx/）就回虚拟根，不再往上切最后一段 ——
            // 那样会得到 `remote:abc` 这种把 sourceId 削掉一半的垃圾路径，
            // core 会按"另一个不存在的源"去查，用户看到的是莫名其妙的加载失败。
            if (isRemotePath(current)) {
                val sourceId = remoteSourceIdOf(current)
                if (sourceId == null) {
                    // 连 sourceId 都取不出来（路径被改坏）：直接回落地页，别继续切。
                    resetToLanding()
                    return
                }
                val sourceRoot = remoteRootOf(sourceId)
                if (current.trimEnd('/') == sourceRoot.trimEnd('/')) {
                    resetToLanding()
                    return
                }
                // 其余按普通路径切最后一段；切到只剩 `remote:<id>` 时补回带尾斜杠的源根形态。
                val remoteParent = current.trimEnd('/').substringBeforeLast('/')
                loadFileList(
                    if (remoteParent.trimEnd('/') == "$REMOTE_PREFIX$sourceId") sourceRoot else remoteParent
                )
                return
            }
            val currentIsVolumeRoot = volumes.any { it.mountPath == current }
            if (currentIsVolumeRoot) {
                resetToLanding()
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
            // 人在远端源里点「主页」，想回的是"能挑别的存储"那一层，不是某个远端目录 ——
            // 本地多卷同理。落地页的形态（虚拟根 / 直接进单卷根）由 resetToLanding 统一判定。
            resetToLanding()
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

    fun dismissFileInfo() { _state.update { it.copy(selectedFile = null) } }

    /**
     * 读文本文件。**不写共享 state** —— 结果直接返回给调用方（文本编辑器）。
     *
     * 为什么不沿用旧的「读进 `state.loadedFile`」：那样错误会落进 `errorMessage` 这个
     * 被所有文件操作共用的槽，编辑器既无法单独清除它（`clearFileOperationMessage` 只清
     * `operationMessage`），也无法区分「这次读取失败」与「上一次删除失败」。
     *
     * @param encoding 解码字符集。老版本 core 会忽略这个参数并始终按 UTF-8 解码，
     *   此时响应里的 `encoding` 会回显它自己用的那个，UI 据此判断请求是否被采纳。
     */
    suspend fun readTextFile(path: String, encoding: String = "utf-8"): Result<TextFileContent> =
        runCatching {
            val resp = api.readFile(mapOf("path" to path, "encoding" to encoding))
            TextFileContent(
                content = resp.content,
                size = resp.size,
                encoding = resp.encoding,
                // 兼容老 core：它只给 truncated=true 而没有 reason 字段。此时无法区分
                // 过大 / 二进制 / 不存在，统一按「过大」处理（这是最常见的一种）。
                reason = resp.reason ?: TextFileContent.REASON_TOO_LARGE.takeIf { resp.truncated },
                encodingSuspect = resp.encoding_suspect
            )
        }.onFailure { DebugLog.w("FileManager", "读取文本失败: $path", it) }

    /** 写文本文件。等回包再返回 —— 调用方据此决定是否清除脏标记。 */
    suspend fun writeTextFile(
        path: String,
        content: String,
        encoding: String = "utf-8"
    ): Result<Unit> = runCatching {
        val resp = api.writeFile(
            mapOf("path" to path, "content" to content, "encoding" to encoding)
        )
        if (!resp.success) error("设备端拒绝了写入请求")
        evict(path.substringBeforeLast('/', "/"))
    }.onFailure { DebugLog.w("FileManager", "写入文本失败: $path", it) }

    // ═══════════════════════════════════════════════════════════════════════
    //  编辑器本地副本缓存（2026-09-11）
    //
    //  为什么需要它：预览态删除后，文本文件唯一的渲染方式是**单个** BasicTextField，
    //  而服务端 `readFile` 对超过单次可读上限的文件只回 `reason=too_large`、不回内容 ——
    //  大文件于是在页内彻底打不开。改成「整份下载到手机缓存 → 就地编辑」后大文件重新可用，
    //  代价是必须自己管缓存（这正是需求里点名的"缓存垃圾"问题）。
    //
    //  治理规则三条：
    //   1. 落在 `appContext.cacheDir/editor` —— 系统在存储紧张时本身就能回收；
    //   2. 每次写入前按「最后修改时间」LRU 淘汰，总量封顶 [EDITOR_CACHE_MAX_BYTES]；
    //   3. 单文件超过 [EDITOR_CACHE_MAX_ITEM_BYTES] 直接拒绝下载并引导「保存到手机」——
    //      再大的文本塞进单个输入框本来就编辑不了，下载只会白占空间。
    //  手动入口两个：编辑器溢出菜单、数据管理页的「清理编辑缓存」。
    // ═══════════════════════════════════════════════════════════════════════

    /** 编辑器副本的缓存目录（懒创建）。 */
    private fun editorCacheDir(): File =
        File(appContext.cacheDir, EDITOR_CACHE_DIR).apply { if (!exists()) mkdirs() }

    /** 读一次缓存占用（字节数 + 文件个数）。 */
    private fun measureEditorCache(): Pair<Long, Int> {
        val files = editorCacheDir().listFiles()?.filter { it.isFile } ?: return 0L to 0
        return files.sumOf { it.length() } to files.size
    }

    /** 把缓存占用同步进 state，供 UI 显示「已占用 x / n 个文件」。 */
    fun refreshEditorCacheUsage() {
        val (bytes, count) = measureEditorCache()
        _state.update { it.copy(editorCacheBytes = bytes, editorCacheCount = count) }
    }

    /**
     * 清理编辑器副本缓存，返回 (释放字节数, 删除文件数)。
     *
     * 只删 [editorCacheDir] 内的文件 —— 绝不触碰 `cacheDir` 下的其它目录
     * （那里有推送 APK、下载分片等别的功能在用）。
     */
    fun clearEditorCache(): Pair<Long, Int> {
        var freed = 0L
        var deleted = 0
        editorCacheDir().listFiles()?.forEach { f ->
            if (f.isFile) {
                val len = f.length()
                if (f.delete()) { freed += len; deleted++ }
            }
        }
        refreshEditorCacheUsage()
        DebugLog.i("FileManager", "清理编辑器缓存: 释放 $freed 字节 / $deleted 个文件")
        return freed to deleted
    }

    /** LRU 淘汰到总量上限内。[keep] 是当前正在编辑、绝不可删的那一个。 */
    private fun evictEditorCache(keep: File?) {
        val files = editorCacheDir().listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= EDITOR_CACHE_MAX_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= EDITOR_CACHE_MAX_BYTES) return@forEach
            if (keep != null && f.absolutePath == keep.absolutePath) return@forEach
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    /** 远程路径 → 本地缓存文件。前缀带路径哈希，避免不同目录下的同名文件互相覆盖。 */
    private fun editorCacheFile(path: String, fileName: String): File {
        val safe = fileName.replace(Regex("[^A-Za-z0-9._\\-]"), "_").take(80).ifBlank { "unnamed" }
        return File(editorCacheDir(), "${path.hashCode().toUInt().toString(16)}_$safe")
    }

    /**
     * 大文件专用：把设备端整份文件下载到本地缓存并返回该文件。
     *
     * 已存在且非空时直接复用（再次进入不必重下）；**没有**断点续传 —— 分片文件只活在
     * 这次调用内，任何一步失败都会在 finally 里被清掉，不留半截垃圾。
     */
    suspend fun fetchToEditorCache(path: String, fileName: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = editorCacheFile(path, fileName)
                if (target.exists() && target.length() > 0L) {
                    target.setLastModified(System.currentTimeMillis())
                    evictEditorCache(keep = target)
                    refreshEditorCacheUsage()
                    return@runCatching target
                }
                _state.update {
                    it.copy(editorFetching = true, editorFetchProgress = -1f, editorFetchFileName = fileName)
                }
                val tooBig = "文件超过 ${EDITOR_CACHE_MAX_ITEM_BYTES / 1024 / 1024}MB，" +
                    "请改用「保存到手机」后用系统应用打开"
                try {
                    val request = okhttp3.Request.Builder().url(getStreamUrl(path)).build()
                    AppHttpClient.instance.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        val body = response.body ?: error("空响应")
                        val declared = body.contentLength()
                        if (declared > EDITOR_CACHE_MAX_ITEM_BYTES) error(tooBig)
                        val tmp = File(editorCacheDir(), "${target.name}.part")
                        var written = 0L
                        var lastTick = 0L
                        body.byteStream().use { input ->
                            java.io.FileOutputStream(tmp).use { output ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    output.write(buf, 0, n)
                                    written += n
                                    if (written > EDITOR_CACHE_MAX_ITEM_BYTES) error(tooBig)
                                    val now = System.currentTimeMillis()
                                    if (now - lastTick > EDITOR_CACHE_PROGRESS_TICK_MS) {
                                        lastTick = now
                                        val p = if (declared > 0) {
                                            (written.toFloat() / declared).coerceIn(0f, 0.99f)
                                        } else {
                                            -1f
                                        }
                                        _state.update { it.copy(editorFetchProgress = p) }
                                    }
                                }
                                output.flush()
                            }
                        }
                        if (target.exists()) target.delete()
                        if (!tmp.renameTo(target)) {
                            tmp.copyTo(target, overwrite = true)
                            tmp.delete()
                        }
                    }
                    evictEditorCache(keep = target)
                    refreshEditorCacheUsage()
                    target
                } finally {
                    // 失败路径留下的 .part 一并清掉，别让它算进缓存占用
                    editorCacheDir().listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
                    _state.update {
                        it.copy(editorFetching = false, editorFetchProgress = -1f, editorFetchFileName = "")
                    }
                }
            }.onFailure { DebugLog.w("FileManager", "下载编辑器副本失败: $path", it) }
        }

    /**
     * 读手机上的本地副本（编辑器「本地副本」模式）。
     *
     * 与 [readTextFile] 的区别只在于解码发生在手机侧：core 的 `encodingSuspect` 判据是
     * "UTF-8 解码出现替换字符"，这里用同一条判据，保证两种来源的提示行为一致。
     */
    suspend fun readLocalTextFile(file: File, encoding: String = "utf-8"): Result<TextFileContent> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = file.readBytes()
                val charset = runCatching { java.nio.charset.Charset.forName(encoding) }
                    .getOrDefault(Charsets.UTF_8)
                val text = String(bytes, charset)
                TextFileContent(
                    content = text,
                    size = bytes.size.toLong(),
                    encoding = encoding,
                    reason = null,
                    encodingSuspect = text.contains('\uFFFD')
                )
            }
        }.onFailure { DebugLog.w("FileManager", "读取本地副本失败: ${file.absolutePath}", it) }

    /** 写手机上的本地副本，返回写入字节数（顶栏据此更新大小）。 */
    suspend fun writeLocalTextFile(file: File, content: String, encoding: String = "utf-8"): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val charset = runCatching { java.nio.charset.Charset.forName(encoding) }
                    .getOrDefault(Charsets.UTF_8)
                val bytes = content.toByteArray(charset)
                file.writeBytes(bytes)
                bytes.size.toLong()
            }
        }.onFailure { DebugLog.w("FileManager", "写入本地副本失败: ${file.absolutePath}", it) }

    /** 把本地副本另存到手机 Download 根（编辑完想把结果取回时用）。 */
    suspend fun exportLocalToDownloads(file: File, fileName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { saveToDownloads(appContext, file, fileName, file.length()) }
        }.onFailure { DebugLog.w("FileManager", "另存到手机失败: ${file.absolutePath}", it) }


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
                val newPath = childPath(parent, newName)
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
                    val destPath = childPath(destinationDir, fileName)
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
                val path = childPath(_state.value.currentPath, name)
                val resp = api.touchFile(mapOf("path" to path))
                if (resp.success) { evict(_state.value.currentPath); refreshFileList(); _state.update { it.copy(operationMessage = "已创建文件") } }
                else _state.update { it.copy(errorMessage = "创建文件失败") }
            } catch (e: Exception) { _state.update { it.copy(errorMessage = "创建文件失败: ${e.message}") } }
        }
    }

    // ── Archive ops: 解压 / 压缩 / 校验和 / 复制路径 ──
    fun extractArchive(path: String, fileName: String) {
        scope.launch {
            try {
                val resp = api.extractArchive(mapOf("path" to path))
                if (resp.success) {
                    evict(_state.value.currentPath)
                    refreshFileList()
                    _state.update { it.copy(operationMessage = "已解压到 ${resp.destination}") }
                } else _state.update { it.copy(errorMessage = resp.error ?: "解压失败") }
            } catch (e: Exception) { _state.update { it.copy(errorMessage = "解压失败: ${e.message}") } }
        }
    }

    fun compressFiles(paths: List<String>) {
        val safePaths = paths.filter { it.isNotBlank() }
        if (safePaths.isEmpty()) return
        scope.launch {
            try {
                val resp = api.compressFiles(mapOf("paths" to safePaths))
                if (resp.success) {
                    evict(_state.value.currentPath)
                    refreshFileList()
                    _state.update { it.copy(operationMessage = "已生成压缩包 ${resp.path}") }
                } else _state.update { it.copy(errorMessage = resp.error ?: "压缩失败") }
            } catch (e: Exception) { _state.update { it.copy(errorMessage = "压缩失败: ${e.message}") } }
        }
    }

    fun checksumFile(path: String) {
        scope.launch {
            try {
                val resp = api.checksumFile(mapOf("path" to path))
                if (resp.success) _state.update { it.copy(checksumResult = resp) }
                else _state.update { it.copy(errorMessage = resp.error ?: "校验失败") }
            } catch (e: Exception) { _state.update { it.copy(errorMessage = "校验失败: ${e.message}") } }
        }
    }

    fun clearChecksumResult() { _state.update { it.copy(checksumResult = null) } }

    fun copyPathToClipboard(path: String) {
        runCatching {
            val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("file-path", path))
        }
        _state.update { it.copy(operationMessage = "已复制路径") }
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
        // 按**可见集合**取：搜索态下 UI 渲染的是 searchResults，
        // 若按 files 全选，用户勾的是看不见的当前目录条目（随后批量删除就删错东西）。
        _state.update { s ->
            s.copy(selectedPaths = (s.searchResults ?: s.files).map { f -> f.path }.toSet())
        }
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
                    // 必须先看状态码。core 的 `/api/files/stream` 对目录与不存在的路径回 404
                    // （远端 provider 读失败回 500），响应体是一段 JSON 错误说明。
                    // 不查状态码就会把那段 JSON 当文件内容写进 Download 目录，并且一路走到
                    // "success" 分支提示"下载完成: Download/<名字>" —— 用户拿到一个以目录名命名的
                    // 垃圾文件，却看不到任何失败提示。
                    if (!response.isSuccessful) {
                        val code = response.code
                        response.close()
                        throw Exception(
                            when (code) {
                                404 -> "文件不存在或该路径不可下载"
                                416 -> "断点续传位置无效，请重新下载"
                                else -> "设备端返回 HTTP $code"
                            }
                        )
                    }
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
                    // 提示里带上落点：文件收在 Download/UFI-AXIS/ 下（见 saveToDownloads），
                    // 用户看到文件名却不知道去哪找是最常见的一句追问
                    _state.update { it.copy(isDownloading = false, downloadStatus = "completed", operationMessage = "下载完成: Download/$DOWNLOAD_SUBDIR/$fileName") }

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

    /**
     * 用户主动「下载到本机」：走 MediaStore 落到 `Download/UFI-AXIS` 子目录。
     *
     * （注意这行里不要写出 `/` 紧跟两个星号的形式 —— Kotlin 的块注释**可嵌套**，
     * 那会在 KDoc 内部再开一层注释，把后面整段代码都吃进注释里。）
     *
     * 2026-09-21：改为带 `RELATIVE_PATH`。此前刻意落在 Download 根（理由是"用户点下来的
     * 单个文件落根目录才是系统下载器的常规行为"），实测的问题是 Download 根本来就是各家
     * 应用的公共垃圾场，从远端源拉回来的一批文件混进去根本找不着。收进自己的子目录后，
     * 「下载历史」里那些条目也才有一个稳定的落点可指。
     *
     * `RELATIVE_PATH` 必须是相对公共目录的路径，前缀用 `Environment.DIRECTORY_DOWNLOADS`
     * 而不是字面量 "Download"：这个目录名在部分定制系统上被本地化过。
     */
    private fun saveToDownloads(context: Context, file: File, fileName: String, fileSize: Long) {

        val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "")) ?: "application/octet-stream"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
            put(android.provider.MediaStore.Downloads.SIZE, fileSize)
            put(
                android.provider.MediaStore.Downloads.RELATIVE_PATH,
                "${android.os.Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR"
            )
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw Exception("无法创建下载文件")
        resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { input -> input.copyTo(output) } } ?: throw Exception("无法写入下载文件")
        values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    // ── Upload ──

    /**
     * 单文件上传入口（保留给只选一个文件的调用方）。内部委托给批量版本，
     * 不再单独维护一条上传实现 —— 两份实现里必然有一份漏改。
     */
    fun uploadFileToServer(localUri: Uri, targetDir: String) =
        uploadFilesToServer(listOf(localUri), targetDir)

    /**
     * 批量上传：**串行**逐个传。
     *
     * ## 为什么是串行
     * 对端是一台跑着 Ktor 的手机，并发多路只会互相抢上行带宽、还更容易撞上 core 的
     * QoS 限流（429）。web 端的队列（`web/src/views/files/useFileUpload.ts`）
     * 也是串行，两端口径一致。
     *
     * ## 2026-09-19：修「多选只传第一个」
     * 原来只有 `uploadFileToServer(uri, dir)`，而且开头是
     * `if (_state.value.isUploading) return` —— 这个守卫是**按文件**判的，
     * 于是循环调用时第 2..N 个会被静默丢弃。现在守卫提到**批次**级别：
     * 一批开始时置位，整批结束才复位，批内逐个传不再自我阻塞。
     *
     * 选择器那侧同步从 `GetContent()`（回 `Uri?`）换成 `GetMultipleContents()`（回 `List<Uri>`）。
     */
    fun uploadFilesToServer(localUris: List<Uri>, targetDir: String) {
        if (localUris.isEmpty()) return
        // 批次级守卫：整批跑完才放开。按文件判会让第 2..N 个被吞掉（见 KDoc）
        if (_state.value.isUploading) return
        uploadJob = scope.launch {
            _state.update {
                it.copy(
                    isUploading = true,
                    uploadProgress = 0f,
                    uploadFileName = "",
                    uploadTotalCount = localUris.size,
                    uploadDoneCount = 0,
                    operationMessage = null,
                    errorMessage = null
                )
            }
            val failed = mutableListOf<String>()
            var succeeded = 0
            var renamedAny = false
            var stagedAny = false
            try {
                val prefs = AppPreferences(appContext)
                val baseUrl = "http://${prefs.effectiveHost}:${prefs.serverPort}"
                val uploader = ChunkedFileUploader(baseUrl) { p ->
                    _state.update { it.copy(uploadProgress = p) }
                }
                // 能力位每批探一次：设备端在一批上传期间不会变，而每个文件探一次是白打
                val caps = uploader.loadCaps()

                for ((index, uri) in localUris.withIndex()) {
                    // 取消后剩下的不再传（cancelUpload 取消整个 uploadJob，
                    // 这里再判一次是为了 launch 体内的协作式取消点）
                    if (!isActive) break
                    _state.update { it.copy(uploadDoneCount = index, uploadProgress = 0f) }
                    val outcome = uploadOne(uri, targetDir, uploader, caps)
                    when (outcome) {
                        is ChunkedFileUploader.Result.Success -> {
                            succeeded++
                            if (outcome.renamed) renamedAny = true
                            if (outcome.staged) stagedAny = true
                        }
                        is ChunkedFileUploader.Result.Failure -> failed += outcome.reason
                    }
                }
                // 只要有成功的就刷一次列表：放在循环里会让每传完一个都打一次 /list。
                // 远端目标例外 —— 文件这会儿还在 core 暂存区，远端目录里还看不到它，
                // 刷新只会让用户以为"传了但没上去"。等推送成功后由轮询那边刷。
                if (succeeded > 0 && !stagedAny) {
                    evict(_state.value.currentPath)
                    refreshFileList()
                }
                if (stagedAny) startRemotePushPolling()
                _state.update {
                    it.copy(
                        isUploading = false,
                        uploadProgress = if (failed.isEmpty()) 1f else -1f,
                        uploadFileName = "",
                        uploadTotalCount = 0,
                        uploadDoneCount = 0,
                        operationMessage = uploadSummary(succeeded, failed, renamedAny, stagedAny)
                    )
                }
            } catch (e: CancellationException) {
                // 取消是用户主动行为，不是失败。状态与列表刷新交给 cancelUpload() 同步处理
                // （它不在被取消的协程里，可以安全地 evict + refreshFileList）。
                //
                // 这里必须**原样抛出**：吞掉 CancellationException 会破坏协作式取消，
                // 表现为"点了取消，后面的文件还在继续传"。
                //
                // 而且这个 catch 必须排在下面 catch(Exception) 之前 ——
                // CancellationException 是 Exception 的子类，顺序反了就会被当成普通失败，
                // 弹出一句莫名的"上传失败: StandaloneCoroutine was cancelled"。
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isUploading = false,
                        uploadProgress = -1f,
                        uploadFileName = "",
                        uploadTotalCount = 0,
                        uploadDoneCount = 0,
                        operationMessage = "上传失败: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    /**
     * 批次结果文案。
     *
     * 单个文件时不报数字（"上传成功" 比 "成功 1 个" 自然）；
     * 有失败时把**第一条原因**带出来 —— 只说"失败 3 个"用户无从下手，
     * 而把 3 条原因全铺开又会撑破提示条。
     *
     * `renamedAny` 时补一句：目标已存在会被服务端自动改名（`video (1).mp4`），
     * 不说出来用户会以为覆盖了原文件。
     *
     * `stagedAny`（远端目标）时**绝不能说"上传成功"**：那会让用户以为文件已经在
     * FTP/WebDAV 上了，而实际上它只到了设备暂存区，还要排队推送、而且可能失败。
     * 文案必须点明"还在推送"并指向任务面板。
     */
    private fun uploadSummary(
        succeeded: Int,
        failed: List<String>,
        renamedAny: Boolean,
        stagedAny: Boolean = false
    ): String {
        val verb = if (stagedAny) "已送达设备" else "上传成功"
        val base = when {
            failed.isEmpty() && succeeded <= 1 -> verb
            failed.isEmpty() -> "$verb $succeeded 个"
            succeeded == 0 && failed.size == 1 -> "上传失败: ${failed.first()}"
            succeeded == 0 -> "上传失败 ${failed.size} 个：${failed.first()}"
            else -> "成功 $succeeded 个，失败 ${failed.size} 个：${failed.first()}"
        }
        val withRename = if (renamedAny && succeeded > 0) "$base（有同名文件，已自动改名）" else base
        return if (stagedAny && succeeded > 0) "$withRename，正在推送到外部存储" else withRename
    }

    /**
     * 传一个文件：把 `Uri` 落成本地临时文件后交给 [ChunkedFileUploader]。
     *
     * 为什么必须先落临时文件（不是直接流 `ContentProvider`）：
     * ① 重试要能**重读**同一份内容，而 `InputStream` 只能过一遍；
     * ② 分片要按偏移**随机读**（`RandomAccessFile.seek`），`ContentProvider` 的流不保证可 seek。
     *
     * 入队前先按能力位挡掉超限的文件：设备端的 413 虽然在 `onCall` 阶段就抛、并没有真的
     * 收下整个文件，但客户端在没有 `Expect: 100-continue` 协商时请求头发出后就无条件开始
     * 推 body —— 不预检就是白烧一遍上行流量（手机上还是用户的电量）。
     */
    private suspend fun uploadOne(
        localUri: Uri,
        targetDir: String,
        uploader: ChunkedFileUploader,
        caps: ChunkedFileUploader.Caps
    ): ChunkedFileUploader.Result = withContext(Dispatchers.IO) {
        val mimeType = appContext.contentResolver.getType(localUri) ?: "application/octet-stream"
        val fileName = try {
            val rawName = appContext.contentResolver.query(localUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) { val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (idx >= 0) cursor.getString(idx) ?: "uploaded_file" else "uploaded_file" } else "uploaded_file"
            } ?: "uploaded_file"
            if (!rawName.contains('.')) { val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType); if (ext != null) "$rawName.$ext" else rawName } else rawName
        } catch (e: Exception) { DebugLog.w("FileManager", "upload: failed to resolve file name", e); "uploaded_file" }
        withContext(Dispatchers.Main) { _state.update { it.copy(uploadFileName = fileName) } }

        val declaredSize = try {
            appContext.contentResolver.openFileDescriptor(localUri, "r")?.use { it.statSize } ?: -1L
        } catch (e: Exception) {
            DebugLog.w("FileManager", "upload: failed to get file size", e); -1L
        }
        // 能拿到大小就先挡一道；拿不到（-1）时放行，交给服务端拦
        if (declaredSize > 0 && declaredSize > caps.effectiveLimit) {
            val limitMb = caps.effectiveLimit / 1024 / 1024
            return@withContext ChunkedFileUploader.Result.Failure("$fileName 超过单文件上限 ${limitMb}MB")
        }

        val tempFile = File(appContext.cacheDir, "upload_${System.currentTimeMillis()}_$fileName")
        try {
            appContext.contentResolver.openInputStream(localUri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext ChunkedFileUploader.Result.Failure("$fileName 无法读取")
            uploader.upload(tempFile, fileName, targetDir, caps)
        } finally {
            tempFile.delete()
        }
    }



    // ═══════════════════════════════════════════════════════════════════════
    //  远端上传第二阶段：core 暂存 → 外部存储源（2026-09-21）
    //
    //  分工：第一阶段（手机 → core，可断点续传）由 ChunkedFileUploader 跑完；
    //  第二阶段完全在设备上（core 的后台作业），app 在这里只做两件事 ——
    //  **观察**（轮询作业列表）与**下指令**（取消 / 重试 / 清除记录）。
    //
    //  为什么要轮询而不是"传完就当成功"：这一段会排队、会因为远端不可达而失败，
    //  而它发生在用户看到"已送达设备"之后。不观察就等于把失败藏起来。
    //
    //  轮询在**全部作业结束后自动停**（不是定时器常驻），与仓库里"事件驱动 + 按需"
    //  的后台口径一致；协程挂在 ViewModel 的 scope 上，app 切后台它仍在跑，所以
    //  另一条收敛保障是连续拉取失败后退避并放弃（见 startRemotePushPolling）。
    // ═══════════════════════════════════════════════════════════════════════
    private var remotePushPollJob: Job? = null

    /** 拉一次推送作业快照（进页面、执行动作后各一次）。 */
    fun loadRemotePushJobs() {
        scope.launch { fetchRemotePushJobs() }
    }

    /** 拉一次快照；**失败返回 null**，让轮询侧能区分"拉取失败"与"拿到快照"。 */
    private suspend fun fetchRemotePushJobs(): List<RemotePushJobInfo>? = try {
        val resp = api.listRemotePushJobs()
        _state.update { it.copy(remotePushJobs = resp.jobs) }
        resp.jobs
    } catch (e: Exception) {
        // 拉不到不清空已有快照：网络抖一下就把整块任务面板清空比显示旧数据更糟
        DebugLog.w("FileManager", "拉取远端推送作业失败", e)
        null
    }

    /**
     * 开始轮询推送作业，直到没有在途的为止。
     *
     * 期间负责两件用户可见的事：
     * ① 某个作业**刚刚**推送成功 ⇒ 远端目录里这会儿才真的有这个文件，刷一次列表并提示；
     * ② 某个作业**刚刚**失败 ⇒ 立刻把原因摆出来（而不是等用户自己去翻面板）。
     */
    fun startRemotePushPolling() {
        if (remotePushPollJob?.isActive == true) return
        remotePushPollJob = scope.launch {
            var prev = _state.value.remotePushJobs.associate { it.id to it.state }
            var failures = 0
            var backoff = PUSH_POLL_INTERVAL_MS
            while (true) {
                val jobs = fetchRemotePushJobs()
                if (jobs == null) {
                    // 拉不到就没有新状态可判：设备离线时旧快照永远满足"仍有在途"，
                    // 只能靠失败计数退避收场，否则就是 1.5s 一次的无限空转。
                    failures++
                    if (failures >= PUSH_POLL_MAX_FAILURES) {
                        _state.update { it.copy(errorMessage = "无法获取推送进度：设备连不上，请稍后在任务面板刷新") }
                        break
                    }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(PUSH_POLL_MAX_INTERVAL_MS)
                    continue
                }
                failures = 0
                backoff = PUSH_POLL_INTERVAL_MS
                // 首次见到就已经是 success 的也算"刚完成"（推得比一次轮询还快），

                // 所以判据是"上一轮它不是 success"，null 也满足。
                val justDone = jobs.filter { it.state == PUSH_STATE_SUCCESS && prev[it.id] != PUSH_STATE_SUCCESS }
                val justFailed = jobs.filter { it.state == PUSH_STATE_FAILED && prev[it.id] != PUSH_STATE_FAILED }
                if (justDone.isNotEmpty()) {
                    // 只有当前正看着那个源的目录时才刷新 —— 在别处刷一次是白打请求
                    if (justDone.any { remoteSourceIdOf(_state.value.currentPath) == it.source_id }) {
                        evict(_state.value.currentPath)
                        refreshFileList()
                    }
                    val first = justDone.first()
                    _state.update {
                        it.copy(
                            operationMessage = if (justDone.size == 1) {
                                "已上传到 ${first.source_label}: ${first.file_name}"
                            } else {
                                "已上传 ${justDone.size} 个文件到外部存储"
                            }
                        )
                    }
                }
                if (justFailed.isNotEmpty()) {
                    val first = justFailed.first()
                    _state.update {
                        it.copy(
                            errorMessage = "推送到 ${first.source_label} 失败：" +
                                (first.error ?: "未知原因") +
                                if (first.retryable) "（可在任务面板重试）" else ""
                        )
                    }
                }
                prev = jobs.associate { it.id to it.state }
                if (jobs.none { it.state == PUSH_STATE_QUEUED || it.state == PUSH_STATE_PUSHING }) break
                delay(PUSH_POLL_INTERVAL_MS)
            }
        }
    }

    /** 取消一个在途推送（core 侧会打断阻塞写并删暂存文件）。 */
    fun cancelRemotePush(jobId: String) {
        scope.launch {
            try {
                val resp = api.cancelRemotePushJob(jobId)
                _state.update {
                    it.copy(
                        operationMessage = if (resp.success) "已取消推送" else "该任务已结束，无需取消"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "取消推送失败: ${e.localizedMessage ?: e.javaClass.simpleName}") }
            }
            fetchRemotePushJobs()
        }
    }

    /**
     * 重试失败/已取消的推送。
     *
     * 只要暂存文件还在，重试就**不用重传手机那一段** —— 那是整条链路最贵的部分。
     * core 在暂存文件已被清理时回 409，这里翻成"请重新上传"。
     */
    fun retryRemotePush(jobId: String) {
        scope.launch {
            var ok = false
            try {
                api.retryRemotePushJob(mapOf("job_id" to jobId))
                ok = true
                _state.update { it.copy(operationMessage = "已重新排队推送") }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "无法重试：设备上的暂存文件已清理，请重新上传") }
            }
            fetchRemotePushJobs()
            if (ok) startRemotePushPolling()
        }
    }

    /** 清掉已结束的推送记录（失败作业的暂存文件一并删，之后就只能重新上传）。 */
    fun clearFinishedRemotePush() {
        scope.launch {
            try {
                api.clearRemotePushJobs()
                _state.update { it.copy(operationMessage = "已清除完成记录") }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "清除失败: ${e.localizedMessage ?: e.javaClass.simpleName}") }
            }
            fetchRemotePushJobs()
        }
    }

    // ── Install APK ──
    // 2026-09-14：改走 Retrofit（api.installApp）。原实现在这里手搓了一份 OkHttp 调用 ——
    // 自己拼 `http://host:port/api/apps/install`、自己 addHeader("Authorization", "Bearer …")、
    // 自己 parseToJsonElement 取 success/message，而 UfiAxisApi 早就有这个端点的声明。
    // 问题不在于它跑不通，而在于它绕开了 Retrofit 客户端统一持有的鉴权头与 baseUrl 解析：
    // 换 token 形态、改 effectiveHost/端口拼法、调超时或统一错误处理时，这条手搓路径不会跟着变，
    // 属于"同一个请求两套写法"里那份迟早漏改的。响应模型 AppInstallResponse 本身就有
    // success/message 两个字段，手写 JSON 解析连带的 toBoolean() 兜底也一并没了。
    fun installApk(path: String) {
        scope.launch {
            try {
                _state.update { it.copy(operationMessage = "正在安装 APK 到设备...") }
                // Retrofit 的 suspend 方法自带 IO 调度，不必再包 withContext(Dispatchers.IO)
                val result = api.installApp(AppInstallRequest(path))
                _state.update { it.copy(
                    operationMessage = if (result.success) "安装成功: ${result.message}" else null,
                    errorMessage = if (!result.success) "安装失败: ${result.message}" else null
                ) }
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

    /**
     * 取消当前上传批次。
     *
     * 2026-09-19 起 UI 上真的能点到它了（上限从 200MB 提到 2GB 之后，「上传通常很快、
     * 不提供取消」这个前提不再成立）。所以这里要把批次状态**清干净**：
     *
     * - `uploadTotalCount` / `uploadDoneCount` 必须归零。不归零的话
     *   `TransferBar` 的 `showUpload` 判据（`uploadProgress >= 0f && < 1f`）配上
     *   `uploadProgress = 0f` 恒为真，横幅会一直挂着显示「上传中 (3/7)」。
     * - `uploadProgress` 置 **-1f** 而不是 0f —— -1 是"没有传输"的约定值，0f 是"刚开始传"。
     * - 已经传完的那几个文件是真的在设备上了，所以要刷一次列表；
     *   放在这里而不是协程的 catch 里，是因为那个协程已经被取消，
     *   在里面再调 suspend 函数会立刻抛。
     *
     * 在途的分片会话由 `ChunkedFileUploader` 在 `CancellationException` 路径上
     * `DELETE session` 清掉，不会在设备端留 `.ufipart`。
     */
    fun cancelUpload() {
        val wasUploading = _state.value.isUploading
        val partialDone = _state.value.uploadDoneCount
        uploadJob?.cancel(); uploadJob = null
        _state.update {
            it.copy(
                isUploading = false,
                uploadProgress = -1f,
                uploadFileName = "",
                uploadTotalCount = 0,
                uploadDoneCount = 0,
                operationMessage = if (wasUploading) {
                    if (partialDone > 0) "已取消上传（已完成 $partialDone 个）" else "已取消上传"
                } else it.operationMessage
            )
        }
        // 取消前已传完的文件确实落盘了，列表得跟上
        if (partialDone > 0) {
            evict(_state.value.currentPath)
            refreshFileList()
        }
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