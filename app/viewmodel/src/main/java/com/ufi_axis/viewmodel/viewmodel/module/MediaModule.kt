package com.ufi_axis.viewmodel.module

import android.content.Context
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.data.model.MediaDirsRequest
import com.ufi_axis.data.model.MediaFfmpegStatusResponse
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.data.model.MediaTagsResponse
import com.ufi_axis.data.model.StreamTicketRequest
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.state.ComponentInfo
import com.ufi_axis.viewmodel.state.ComponentTask
import com.ufi_axis.viewmodel.state.MEDIA_KINDS
import com.ufi_axis.viewmodel.state.MEDIA_ORDER_DESC
import com.ufi_axis.viewmodel.state.MEDIA_SORT_DATE
import com.ufi_axis.viewmodel.state.MediaBrowseState
import com.ufi_axis.viewmodel.state.MediaLibraryState
import com.ufi_axis.viewmodel.state.MediaTabState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * 媒体库（工具 → 视频 / 音乐 / 图片三个独立页）的取数与状态。
 *
 * 数据全部来自 core 的 `/api/media` 一组端点 —— core 查系统媒体库（MediaStore），
 * app 只做展示与分页。**这里不做任何"什么算视频""扫哪些目录"的判定**：
 * 扩展名归类在 MediaStore、目录范围是 core 侧的设备配置。
 *
 * 三个页面共用本模块**一份**状态（[MediaLibraryState]），每类各占一个槽
 * （[MediaLibraryState.tabs]）：从视频页去音乐页再回来，视频列表还在，不重新拉。
 *
 * 2026-09-16（媒体中心拆成三页）：扫描目录、重扫状态、提示文案都变成**按类型各一份**，
 * 所以 [setScanDirs] / [rescan] 都要带 type —— 视频页只改视频那一份。
 * 排序是**展示偏好**，只放内存 / 客户端，不往 core 写；扫描目录才是设备配置。
 */
class MediaModule(
    private val api: UfiAxisApi,
    private val appContext: Context,
    private val scope: CoroutineScope
) {

    companion object {
        /** 一页条数。与 core 的默认值一致；媒体库上千项，靠分页而不是一次拉完。 */
        const val PAGE_SIZE = 100

        /** 缩略图默认边长（core 侧夹在 96..1024）。 */
        const val THUMB_SIZE = 256

        /** [allItems] 的枚举上限：批量抽帧一次处理上万项本身就不合理。 */
        const val ALL_ITEMS_LIMIT = 2000

        /** FFmpeg 可选组件的 id（与 core `BinaryComponentStore.ID_FFMPEG` 一致） */
        const val COMPONENT_FFMPEG = "ffmpeg"
    }

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<MediaLibraryState> = _state.asStateFlow()

    /**
     * 从本地 prefs 还原三页各自的展示偏好（视图 / 排序）。
     *
     * 与文件管理器 `FileManagerModule` 同款做法：进程启动时读一次，不做异步 —— 这是几个
     * boolean/string，走 `SharedPreferences` 同步读比引入一次状态跃迁便宜得多。
     * 扫描目录**不**在这里还原：那是 core 侧的设备配置，由 [loadStatus] 拉。
     */
    private fun initialState(): MediaLibraryState {
        val prefs = AppPreferences(appContext)
        return MediaLibraryState(
            tabs = MEDIA_KINDS.associate { (type, _) ->
                type to MediaTabState(
                    type = type,
                    sort = runCatching { prefs.mediaSort(type) }.getOrDefault(MEDIA_SORT_DATE),
                    order = runCatching { prefs.mediaOrder(type) }.getOrDefault(MEDIA_ORDER_DESC),
                    gridView = runCatching { prefs.mediaGridView(type) }.getOrDefault(type == "image")
                )
            }
        )
    }

    private fun updateTab(type: String, transform: (MediaTabState) -> MediaTabState) {
        _state.update { s ->
            val current = s.tab(type)
            s.copy(tabs = s.tabs + (type to transform(current)))
        }
    }

    /**
     * 读三类媒体的授权状态与各自的扫描目录。
     *
     * 进入任一媒体页时调一次。授权状态**必须**先拿到：没权限时界面要显示引导，
     * 而不是先显示一个空列表再解释（那是"假空态"）。
     */
    fun loadStatus() {
        scope.launch {
            try {
                val status = api.getMediaStatus()
                _state.update { s ->
                    s.copy(
                        allFilesAccess = status.all_files_access,
                        statusLoaded = true,
                        errorMessage = null,
                        tabs = s.tabs.mapValues { (type, tab) ->
                            tab.copy(
                                granted = status.granted[type] ?: status.all_files_access,
                                scanDirs = status.scan_dirs[type] ?: tab.scanDirs
                            )
                        }
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(statusLoaded = true, errorMessage = "读取媒体库状态失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 取某一类的第一页（[force] = false 且已经加载过则跳过）。
     *
     * 与 [loadMore] 分开是刻意的：首屏用 `isLoading`、翻页用 `isAppending`，
     * 共用一个标志会让翻页时整块退回骨架屏。
     */
    fun loadFirstPage(type: String, force: Boolean = false) {
        val tab = _state.value.tab(type)
        if (!force && tab.loadedOnce) return
        if (tab.isLoading) return
        updateTab(type) { it.copy(isLoading = true, errorMessage = null) }
        scope.launch {
            try {
                val resp = api.getMediaList(
                    type = type,
                    sort = tab.sort,
                    order = tab.order,
                    limit = PAGE_SIZE,
                    offset = 0
                )
                updateTab(type) {
                    it.copy(
                        items = resp.items,
                        total = resp.total,
                        isLoading = false,
                        loadedOnce = true,
                        granted = true,
                        errorMessage = null,
                        scanDirs = resp.scan_dirs
                    )
                }
            } catch (e: Exception) {
                // 403 = 这一类没授权：把 granted 落到 false，界面切成授权引导
                val denied = e.message?.contains("403") == true
                updateTab(type) {
                    it.copy(
                        isLoading = false,
                        loadedOnce = true,
                        granted = if (denied) false else it.granted,
                        errorMessage = if (denied) null else "加载失败: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * 文件夹视图：列某一层（[path] 为空时由 core 决定起点）。
     *
     * 不分页 —— core 单层有条数上限，而"一个目录里几百个文件还要翻页"这种目录结构本身
     * 就该整理一下。排序沿用该类型的排序偏好，与平铺列表保持一致。
     */
    fun browse(type: String, path: String? = null, force: Boolean = false) {
        val current = _state.value.folderView(type)
        val target = path ?: current.path.takeIf { it.isNotBlank() }
        if (!force && current.loadedOnce && target == current.path.takeIf { it.isNotBlank() }) return
        if (current.isLoading) return
        val tab = _state.value.tab(type)
        updateBrowse(type) { it.copy(isLoading = true, errorMessage = null) }
        scope.launch {
            try {
                val resp = api.browseMedia(
                    type = type,
                    path = target,
                    sort = tab.sort,
                    order = tab.order
                )
                updateBrowse(type) {
                    it.copy(
                        path = resp.path,
                        parent = resp.parent,
                        roots = resp.roots,
                        folders = resp.folders,
                        items = resp.items,
                        isLoading = false,
                        loadedOnce = true,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                updateBrowse(type) {
                    it.copy(
                        isLoading = false,
                        loadedOnce = true,
                        errorMessage = "打开目录失败: ${e.message}"
                    )
                }
            }
        }
    }

    /** 回到上一级；已经在根上就什么都不做（界面那颗按钮此时也不该出现）。 */
    fun browseUp(type: String) {
        val parent = _state.value.folderView(type).parent ?: return
        browse(type, parent, force = true)
    }

    /**
     * 设备端 ffmpeg 自检（设置页那颗「检测」按钮）。
     *
     * [path] 非空时 core 会**真的对那个文件抽一帧并计时** —— 这是判断
     * "设备自己生成封面" 这条路实不实用的唯一可靠依据（能不能出图 + 一帧要多久）。
     *
     * 失败回 null 而不是抛：老版本 core 没有这个端点（404），那不是错误而是
     * "这个 core 不支持"，由调用方显示对应文案。
     */
    suspend fun ffmpegStatus(path: String? = null): MediaFfmpegStatusResponse? = try {
        api.getMediaFfmpegStatus(path)
    } catch (e: Exception) {
        null
    }

    // ── FFmpeg 可选组件（GET/POST /api/components，只取 id=ffmpeg 那一条）──
    //
    // 为什么不复用 TunnelModule 那套：那里的组件列表是"内网穿透的核心依赖"，
    // 语义上和媒体无关；FFmpeg 只服务视频封面抽帧，安装入口也只在媒体设置页。
    // 两边调的是同一组通用端点，各自只关心自己那一条，互不干扰。

    /**
     * 拉 FFmpeg 组件状态。
     *
     * [refresh] = true 时让 core 重新拉 version.json（会打外网，「检查更新」用）；
     * 省略则用 core 进程内缓存。core 版本过旧（没有 ffmpeg 组件）时留 null。
     */
    fun loadFfmpegComponent(refresh: Boolean = false) {
        scope.launch {
            try {
                val element = api.listComponents(if (refresh) "true" else null)
                val obj = element as? JsonObject ?: return@launch
                val info = obj["components"]?.jsonArray
                    ?.mapNotNull { parseComponent(it) }
                    ?.firstOrNull { it.id == COMPONENT_FFMPEG }
                _state.update {
                    it.copy(ffmpegComponent = info, ffmpegTask = parseComponentTask(obj))
                }
            } catch (e: Exception) {
                // 拉不到不报错：老 core 没有这个端点，UI 显示"不可用"即可
            }
        }
    }

    /** 只刷新安装进度（任务进行中时高频轮询，落终态后补拉一次完整状态） */
    fun refreshFfmpegTask() {
        scope.launch {
            try {
                val obj = api.getComponentStatus() as? JsonObject ?: return@launch
                val task = parseComponentTask(obj)
                val was = _state.value.ffmpegTask.active
                _state.update { it.copy(ffmpegTask = task) }
                if (was && !task.active) loadFfmpegComponent()
            } catch (e: Exception) {
                // 轮询失败静默
            }
        }
    }

    /** 触发下载安装（core 侧异步执行，进度经 [refreshFfmpegTask] 轮询） */
    fun installFfmpegComponent() {
        scope.launch {
            try {
                val obj = api.installComponent(COMPONENT_FFMPEG) as? JsonObject
                if (obj != null) _state.update { it.copy(ffmpegTask = parseComponentTask(obj)) }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "触发 FFmpeg 安装失败: ${e.message}") }
            }
        }
    }

    /** 卸载 FFmpeg 组件（core 侧会删掉 filesDir/components/ffmpeg 整个目录） */
    fun uninstallFfmpegComponent() {
        scope.launch {
            try {
                api.uninstallComponent(COMPONENT_FFMPEG)
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "卸载 FFmpeg 失败: ${e.message}") }
            } finally {
                loadFfmpegComponent()
            }
        }
    }

    private fun parseComponent(element: JsonElement): ComponentInfo? {
        val obj = element as? JsonObject ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        return ComponentInfo(
            id = id,
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id,
            description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
            installed = obj["installed"]?.jsonPrimitive?.booleanOrNull ?: false,
            installedVersion = obj["installed_version"]?.jsonPrimitive?.contentOrNull ?: "",
            installedSize = obj["installed_size"]?.jsonPrimitive?.longOrNull ?: 0L,
            source = obj["source"]?.jsonPrimitive?.contentOrNull ?: "",
            latestVersion = obj["latest_version"]?.jsonPrimitive?.contentOrNull ?: "",
            downloadSize = obj["download_size"]?.jsonPrimitive?.longOrNull ?: 0L,
            available = obj["available"]?.jsonPrimitive?.booleanOrNull ?: false,
            updateAvailable = obj["update_available"]?.jsonPrimitive?.booleanOrNull ?: false,
            upstream = obj["upstream"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }

    private fun parseComponentTask(obj: JsonObject) = ComponentTask(
        id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
        state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "idle",
        percent = obj["percent"]?.jsonPrimitive?.intOrNull ?: 0,
        message = obj["message"]?.jsonPrimitive?.contentOrNull ?: ""
    )



    /**
     * 把某一类**整库**拉齐（分页循环），给"批量生成缩略图"这种要先知道全集的操作用。
     *
     * 不进 [state]：这是一次性的枚举结果，塞进列表状态会和分页浏览打架
     * （用户明明只滚到第 2 页，列表却突然有了 800 项）。
     * 上限 [ALL_ITEMS_LIMIT] 是防呆：真有上万个视频时，批量抽帧本身就不该一次做完。
     */
    suspend fun allItems(type: String): List<MediaLibraryItem> {
        val tab = _state.value.tab(type)
        val collected = mutableListOf<MediaLibraryItem>()
        var offset = 0
        while (collected.size < ALL_ITEMS_LIMIT) {
            val resp = try {
                api.getMediaList(
                    type = type,
                    sort = tab.sort,
                    order = tab.order,
                    limit = PAGE_SIZE,
                    offset = offset
                )
            } catch (e: Exception) {
                break
            }
            if (resp.items.isEmpty()) break
            collected += resp.items
            offset += resp.items.size
            if (offset >= resp.total) break
        }
        return collected.distinctBy { it.id }
    }


    private fun updateBrowse(type: String, transform: (MediaBrowseState) -> MediaBrowseState) {
        _state.update { s ->
            s.copy(browse = s.browse + (type to transform(s.folderView(type))))
        }
    }

    /** 追加下一页。没有更多、正在加载、或正在首屏加载时都不发请求。 */
    fun loadMore(type: String) {
        val tab = _state.value.tab(type)
        if (tab.isLoading || tab.isAppending || !tab.hasMore) return
        updateTab(type) { it.copy(isAppending = true) }
        scope.launch {
            try {
                val resp = api.getMediaList(
                    type = type,
                    sort = tab.sort,
                    order = tab.order,
                    limit = PAGE_SIZE,
                    offset = tab.items.size
                )
                updateTab(type) { current ->
                    // 按 id 去重：翻页期间媒体库可能新增/删除，offset 会错位
                    val existing = current.items.mapTo(HashSet()) { it.id }
                    current.copy(
                        items = current.items + resp.items.filter { it.id !in existing },
                        total = resp.total,
                        isAppending = false
                    )
                }
            } catch (e: Exception) {
                updateTab(type) {
                    it.copy(isAppending = false, errorMessage = "加载更多失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 改排序（展示偏好，不写 core，但**持久化到本地 prefs**）。
     *
     * 立刻重拉第一页 —— 排序变了，已加载的页码没有意义（core 是按 offset 分页的）。
     */
    fun setSort(type: String, sort: String = MEDIA_SORT_DATE, order: String = MEDIA_ORDER_DESC) {
        val tab = _state.value.tab(type)
        if (tab.sort == sort && tab.order == order) return
        runCatching { AppPreferences(appContext).setMediaSort(type, sort, order) }
        updateTab(type) {
            it.copy(sort = sort, order = order, items = emptyList(), total = 0, loadedOnce = false)
        }
        loadFirstPage(type, force = true)
    }

    /**
     * 切换列表 / 网格（展示偏好，持久化到本地 prefs，按类型各一份）。
     *
     * **不重拉数据**：视图只是同一份数据的两种画法。重拉会让"切一下视图"变成一次网络往返，
     * 而且会把已经滚到的位置丢掉。
     */
    fun setGridView(type: String, grid: Boolean) {
        if (_state.value.tab(type).gridView == grid) return
        runCatching { AppPreferences(appContext).setMediaGridView(type, grid) }
        updateTab(type) { it.copy(gridView = grid) }
    }

    /**
     * 写**这一类**的扫描目录（设备配置，存在 core）。
     *
     * 只作废这一类的列表：视频的扫描范围变了，音乐和图片的列表没有任何理由被清空。
     * 这也是把 scanDirs 下沉到 [MediaTabState] 之后才能做到的事。
     */
    fun setScanDirs(type: String, dirs: List<String>) {
        scope.launch {
            try {
                val resp = api.putMediaConfig(type, MediaDirsRequest(dirs))
                updateTab(type) {
                    it.copy(
                        scanDirs = resp.dirs,
                        items = emptyList(),
                        total = 0,
                        loadedOnce = false,
                        message = if (resp.dirs.isEmpty()) {
                            "已改为扫描整个媒体库"
                        } else {
                            "已更新扫描目录"
                        }
                    )
                }
                loadFirstPage(type, force = true)
            } catch (e: Exception) {
                updateTab(type) { it.copy(errorMessage = "保存扫描目录失败: ${e.message}") }
            }
        }
    }

    /**
     * 请系统重新收录**这一类**的目录（默认用该类型已配置的目录）。
     *
     * 收录是**异步**的：这里成功返回只代表已提交给系统扫描器，所以完成后重拉这一类的列表，
     * 但可能仍看不到最新文件 —— 文案要说"已提交"，不能说"已完成"。
     */
    fun rescan(type: String, dirs: List<String> = emptyList()) {
        if (_state.value.tab(type).isRescanning) return
        updateTab(type) { it.copy(isRescanning = true, errorMessage = null, message = null) }
        scope.launch {
            try {
                val resp = api.rescanMedia(type, MediaDirsRequest(dirs))
                updateTab(type) {
                    it.copy(
                        isRescanning = false,
                        message = if (resp.submitted == 0) {
                            "没有找到可收录的文件"
                        } else {
                            "已提交 ${resp.submitted} 个文件给系统收录" +
                                if (resp.truncated) "（数量过多，已截断，可再执行一次）" else ""
                        }
                    )
                }
                loadFirstPage(type, force = true)
            } catch (e: Exception) {
                updateTab(type) { it.copy(isRescanning = false, errorMessage = "扫描失败: ${e.message}") }
            }
        }
    }

    /** 清掉某一类页面上的提示与错误（离开页面 / 用户已读）。 */
    fun clearMessages(type: String) {
        updateTab(type) { it.copy(message = null, errorMessage = null) }
    }

    /**
     * 目录选择器用：列出某个目录下的**子目录**，顺带回它的父目录。
     *
     * 走已有的 `/api/files/list`，不给"选目录"这件事新造一条列目录的接口。
     * 失败回空列表 + null 父目录，让界面显示"这个目录读不到"，不抛到全局错误浮层。
     */
    suspend fun browseDirs(path: String): Pair<List<String>, String?> = try {
        val resp = api.listFiles(path)
        resp.files.filter { it.isDirectory }.map { it.path }.sorted() to resp.parent
    } catch (e: Exception) {
        emptyList<String>() to null
    }

    /** 清掉全部提示（三类 + 全局），仅在需要整体复位时用。 */
    fun clearAllMessages() {
        _state.update { s ->
            s.copy(
                errorMessage = null,
                tabs = s.tabs.mapValues { (_, tab) -> tab.copy(message = null, errorMessage = null) }
            )
        }
    }

    /** 三类都还没拉过时用得上：把没加载过的那几类各拉一页（不动已有数据）。 */
    fun prefetchAll() {
        MEDIA_KINDS.forEach { (type, _) -> loadFirstPage(type) }
    }

    // ── URLs ──
    //
    // 与 FileManagerModule.getStreamUrl 同一口径（同一个 host/port 来源）。
    // 播放 / 查看原文件走 /api/files/stream；缩略图与封面是 core 生成/取出的图片。

    /** 原文件字节流（支持 Range，播放器与图片查看器都用它）。 */
    fun streamUrl(path: String): String {
        val prefs = AppPreferences(appContext)
        return "http://${prefs.effectiveHost}:${prefs.serverPort}" +
            "/api/files/stream?path=${URLEncoder.encode(path, "UTF-8")}"
    }

    /** 缩略图（JPEG）。同一 (type,id,size) 稳定可缓存，core 侧带了 ETag。 */
    fun thumbnailUrl(type: String = MEDIA_TYPE_VIDEO, id: Long, size: Int = THUMB_SIZE): String {
        val prefs = AppPreferences(appContext)
        return "http://${prefs.effectiveHost}:${prefs.serverPort}" +
            "/api/media/thumbnail?type=$type&id=$id&size=$size"
    }

    /**
     * 音频封面（原始内嵌图）。播放页用这个而不是 [thumbnailUrl]：
     * 260dp 的容器在 xxhdpi 上约 780 物理像素，喂 256px 的缩略图必然是糊的。
     */
    fun coverUrl(id: Long): String {
        val prefs = AppPreferences(appContext)
        return "http://${prefs.effectiveHost}:${prefs.serverPort}/api/media/cover?id=$id"
    }

    /**
     * 取单首音频的标签（曲名 / 艺术家 / 专辑），失败或取不到回 null。
     *
     * 与 [MediaLibraryState] 无关：这是**当前这一首**的东西，跟着播放页生命周期走，
     * 放进全局状态后换歌、退出页面都要额外清理。列表里的 `title/artist` 已经够用，
     * 本接口只在它们缺失时补强（core 侧会直接读文件标签）—— 所以调用方拿 null 时
     * 应当继续用列表里的值。
     */
    suspend fun tagsOf(id: Long): MediaTagsResponse? = try {
        api.getMediaTags(id)
    } catch (e: Exception) {
        null
    }

    /**
     * 换一张**免鉴权**播放地址（`/media/stream?ticket=…` 的完整 URL）。
     *
     * 只给 [MediaMetadataRetriever][android.media.MediaMetadataRetriever] 这类"自己发 HTTP、
     * 加不了请求头"的调用方用：`/api/files/stream` 要设备签名，而签名里的 nonce 是一次性的，
     * 抽帧过程中的多个 Range 请求必然从第二个起被拒。播放器（ExoPlayer）不需要它 ——
     * 那条链路走 OkHttp，能逐请求重签。
     */
    suspend fun ticketStreamUrl(path: String): String? = try {
        val resp = api.createStreamTicket(StreamTicketRequest(path))
        resp.url.takeIf { it.isNotBlank() }?.let { rel ->
            val prefs = AppPreferences(appContext)
            "http://${prefs.effectiveHost}:${prefs.serverPort}$rel"
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 把手机抽好的缩略图交给 core 缓存。
     *
     * 为什么要回传而不是只存本地：core 的 `/media/thumbnail` 第一级就是这份缓存，交上去之后
     * **Web 端、第二台手机**都能直接看到同一张图；只存本地的话每个客户端都要自己重抽一遍。
     *
     * 失败只回 false 不抛：这是"顺手把成果共享出去"，失败了本地缓存仍然有效，
     * 不该因为它让缩略图显示不出来。
     */
    suspend fun uploadThumbnail(type: String, id: Long, jpeg: ByteArray): Boolean = try {
        val body = jpeg.toRequestBody("image/jpeg".toMediaType())
        api.putMediaThumbnail(type, id, body).success
    } catch (e: Exception) {
        false
    }
}
