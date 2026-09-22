package com.ufi_axis.viewmodel.module

import android.content.Context
import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.MEDIA_GROUP_ALBUM
import com.ufi_axis.data.model.MEDIA_GROUP_ARTIST
import com.ufi_axis.data.model.MEDIA_GROUP_FOLDER
import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.data.model.MediaDirsRequest
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.data.model.MediaSubtitleEntry
import com.ufi_axis.data.model.MediaTagsResponse
import com.ufi_axis.data.model.PlaylistAddResponse
import com.ufi_axis.data.model.PlaylistNameRequest
import com.ufi_axis.data.model.PlaylistPathsRequest
import com.ufi_axis.data.model.StreamTicketRequest
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.encodeUriComponent
import com.ufi_axis_core.contract.WsDataTopic
import com.ufi_axis.viewmodel.state.AudioQueueScope
import com.ufi_axis.viewmodel.state.MEDIA_GROUP_KINDS
import com.ufi_axis.viewmodel.state.MEDIA_KINDS
import com.ufi_axis.viewmodel.state.MEDIA_ORDER_DESC
import com.ufi_axis.viewmodel.state.MEDIA_SORT_DATE
import com.ufi_axis.viewmodel.state.MediaBrowseState
import com.ufi_axis.viewmodel.state.MediaGroupState
import com.ufi_axis.viewmodel.state.MediaLibraryState
import com.ufi_axis.viewmodel.state.MediaPlaylistDetailState
import com.ufi_axis.viewmodel.state.MediaPlaylistState
import com.ufi_axis.viewmodel.state.MediaTabState
import com.ufi_axis.viewmodel.state.mediaGroupItemsKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

        /**
         * 点了"无标签"那一组时给用户的说法。
         *
         * core 侧把没有专辑/歌手标签的文件聚成 key 为空串的一组，而空 key 送回
         * `/api/media/list` 会被当成"没传过滤条件"—— 那样点进去看到的是整库，比报错更糟。
         * 所以这里拦下来并解释原因，而不是静默什么都不做（用户只会以为 App 卡了）。
         */
        const val GROUP_KEY_MISSING_MESSAGE = "这一组没有标签信息，无法单独查看"
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

    /**
     * 取某个**播放作用域**下的全部曲目（拉齐所有分页），供装载播放队列用。
     *
     * 与 [loadGroupItems] 的区别：后者只拉一页、写进 `audioGroupItems` 给**浏览**用；
     * 这里要的是"这个范围里到底有哪些歌"，必须拉齐 —— 否则专辑第 101 首之后的歌
     * 进不了队列，「下一首」会在第 100 首处莫名停下。
     *
     * 排序沿用音乐页的排序偏好（与 [loadGroupItems] 同口径）：从列表点进专辑，
     * 曲目顺序突然换一套只会让人困惑。
     *
     * 失败或范围为空时回空列表 —— 调用方（播放页）自己有单曲兜底分支。
     */
    suspend fun queueItemsOf(scope: AudioQueueScope): List<MediaLibraryItem> {
        if (scope.isAll) return allItems(MEDIA_TYPE_AUDIO)
        // 歌单必须在分组判定之前：它不走 `/api/media/list`，顺序也不是音乐页那套排序偏好，
        // 而是用户在歌单里排定的顺序（core 原样给出）。已失效的条目排掉 ——
        // 它们没有可播地址，留在队列里会让播放器在那一首上报错卡住。
        if (scope.isPlaylist) {
            return try {
                api.getPlaylistItems(scope.key).items.filterNot { it.missing }
            } catch (e: Exception) {
                emptyList()
            }
        }
        if (!scope.isGroup) return emptyList()

        val (album, artist, dir) = groupFilters(scope.kind, scope.key)
        val tab = _state.value.tab(MEDIA_TYPE_AUDIO)
        val collected = mutableListOf<MediaLibraryItem>()
        var offset = 0
        while (collected.size < ALL_ITEMS_LIMIT) {
            val resp = try {
                api.getMediaList(
                    type = MEDIA_TYPE_AUDIO,
                    sort = tab.sort,
                    order = tab.order,
                    limit = PAGE_SIZE,
                    offset = offset,
                    album = album,
                    artist = artist,
                    dir = dir
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

    // ── 音频分组（专辑 / 歌手 / 文件夹）──
    //
    // 聚合由 core 一次算完（`/api/media/groups`），app 不在本地对整库做 groupBy ——
    // 那要先把上千首全拉下来，且分组口径会和 core / Web 端对不上。
    // 点进某一组之后列曲目仍走 `/api/media/list`（带 album/artist/dir 过滤），
    // 不新造"列某组曲目"的接口。

    private fun updateGroup(by: String, transform: (MediaGroupState) -> MediaGroupState) {
        _state.update { s ->
            s.copy(audioGroups = s.audioGroups + (by to transform(s.group(by))))
        }
    }

    private fun updateGroupItems(
        by: String,
        key: String,
        transform: (MediaTabState) -> MediaTabState
    ) {
        _state.update { s ->
            val slot = mediaGroupItemsKey(by, key)
            s.copy(audioGroupItems = s.audioGroupItems + (slot to transform(s.groupItems(by, key))))
        }
    }

    /** `by` 是不是 core 支持的分组维度。非法值一律当"没这个功能"，不发请求。 */
    private fun isGroupBy(by: String): Boolean = MEDIA_GROUP_KINDS.any { (value, _) -> value == by }

    /**
     * 把组的 key 翻成 `/api/media/list` 的过滤参数。
     *
     * 三个维度对应三个不同的 query，映射只写在这一处：写在调用点会在"加载首页"和"加载更多"
     * 各来一份，改起来必然漏一个。
     *
     * ## 值要**先编码**（2026-09-21 修"专辑里没有歌"）
     * `getMediaList` 的这三个参数声明成 `encoded = true`，因为 Retrofit/OkHttp 在 query 里
     * 不编码 `+`，而 Ktor 解 query 时把 `+` 当空格 —— 专辑名 `万岁2001 新曲+精选`
     * 会在服务端变成 `万岁2001 新曲 精选`，一条都匹配不上。
     * [encodeUriComponent] 把 `+` 编成 `%2B`、空格编成 `%20`，两端就对得上了。
     */
    private fun groupFilters(by: String, key: String): Triple<String?, String?, String?> {
        val encoded = encodeUriComponent(key)
        return when (by) {
            MEDIA_GROUP_ALBUM -> Triple(encoded, null, null)
            MEDIA_GROUP_ARTIST -> Triple(null, encoded, null)
            MEDIA_GROUP_FOLDER -> Triple(null, null, encoded)
            else -> Triple(null, null, null)
        }
    }

    /**
     * 取某个维度的分组列表（专辑 / 歌手 / 文件夹）。
     *
     * 不分页：core 一次给全量分组。[force] = false 且已经拉过就跳过 —— 分组页会随着
     * 维度切换反复进入，每次都打一次网络没有必要（媒体库变了由下拉刷新 force 重来）。
     */
    fun loadGroups(by: String, force: Boolean = false) {
        if (!isGroupBy(by)) return
        val current = _state.value.group(by)
        if (!force && current.loadedOnce) return
        if (current.isLoading) return
        updateGroup(by) { it.copy(by = by, isLoading = true, errorMessage = null) }
        scope.launch {
            try {
                val resp = api.getMediaGroups(MEDIA_TYPE_AUDIO, by)
                updateGroup(by) {
                    it.copy(
                        by = by,
                        groups = resp.groups,
                        isLoading = false,
                        loadedOnce = true,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                updateGroup(by) {
                    it.copy(
                        isLoading = false,
                        loadedOnce = true,
                        errorMessage = "加载分组失败: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * 取某一组里的第一页曲目。
     *
     * 排序沿用音乐页的排序偏好：从列表点进专辑，曲目顺序突然换一套只会让人困惑。
     * [key] 为空串（无标签兜底组）时不发请求，改成给一条能看懂的提示 —— 见
     * [GROUP_KEY_MISSING_MESSAGE]。
     */
    fun loadGroupItems(by: String, key: String, force: Boolean = false) {
        if (!isGroupBy(by)) return
        if (key.isEmpty()) {
            updateGroupItems(by, key) {
                it.copy(isLoading = false, loadedOnce = true, errorMessage = GROUP_KEY_MISSING_MESSAGE)
            }
            return
        }
        val current = _state.value.groupItems(by, key)
        if (!force && current.loadedOnce) return
        if (current.isLoading) return
        val tab = _state.value.tab(MEDIA_TYPE_AUDIO)
        updateGroupItems(by, key) { it.copy(isLoading = true, errorMessage = null) }
        scope.launch {
            try {
                val (album, artist, dir) = groupFilters(by, key)
                val resp = api.getMediaList(
                    type = MEDIA_TYPE_AUDIO,
                    sort = tab.sort,
                    order = tab.order,
                    limit = PAGE_SIZE,
                    offset = 0,
                    album = album,
                    artist = artist,
                    dir = dir
                )
                updateGroupItems(by, key) {
                    it.copy(
                        items = resp.items,
                        total = resp.total,
                        isLoading = false,
                        loadedOnce = true,
                        granted = true,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                updateGroupItems(by, key) {
                    it.copy(
                        isLoading = false,
                        loadedOnce = true,
                        errorMessage = "加载失败: ${e.message}"
                    )
                }
            }
        }
    }

    /** 组内曲目的下一页。守卫与 [loadMore] 一致：首屏加载中、正在追加、已到底都不发请求。 */
    fun loadMoreGroupItems(by: String, key: String) {
        if (!isGroupBy(by) || key.isEmpty()) return
        val current = _state.value.groupItems(by, key)
        if (current.isLoading || current.isAppending || !current.hasMore) return
        val tab = _state.value.tab(MEDIA_TYPE_AUDIO)
        updateGroupItems(by, key) { it.copy(isAppending = true) }
        scope.launch {
            try {
                val (album, artist, dir) = groupFilters(by, key)
                val resp = api.getMediaList(
                    type = MEDIA_TYPE_AUDIO,
                    sort = tab.sort,
                    order = tab.order,
                    limit = PAGE_SIZE,
                    offset = current.items.size,
                    album = album,
                    artist = artist,
                    dir = dir
                )
                updateGroupItems(by, key) { slot ->
                    // 与 loadMore 同理：翻页期间媒体库可能增删，offset 会错位，按 id 去重
                    val existing = slot.items.mapTo(HashSet()) { it.id }
                    slot.copy(
                        items = slot.items + resp.items.filter { it.id !in existing },
                        total = resp.total,
                        isAppending = false
                    )
                }
            } catch (e: Exception) {
                updateGroupItems(by, key) {
                    it.copy(isAppending = false, errorMessage = "加载更多失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 丢掉某一组的曲目缓存（离开分组详情页时调）。
     *
     * 分组列表本身留着（那只有几十条，且切回来就要用），但"每个逛过的专辑都留一份曲目"
     * 会在内存里堆出几十份没人再看的列表。
     */
    fun clearGroupItems(by: String, key: String) {
        _state.update { s ->
            s.copy(audioGroupItems = s.audioGroupItems - mediaGroupItemsKey(by, key))
        }
    }

    // ── 音频歌单（2026-09-21）──
    //
    // 歌单存在 core（`/api/playlists`），app 只做展示与转发：这里没有任何"哪首歌该在哪个
    // 歌单里"的判定，去重、上限、失效标记全在 core 算完。写操作成功后重拉受影响的那一份，
    // 不在本地拼乐观更新 —— 加歌的实际结果（跳过了几条、截断没截断）只有 core 知道。

    private fun updatePlaylists(transform: (MediaPlaylistState) -> MediaPlaylistState) {
        _state.update { s -> s.copy(playlists = transform(s.playlists)) }
    }

    private fun updatePlaylistDetail(
        id: String,
        transform: (MediaPlaylistDetailState) -> MediaPlaylistDetailState
    ) {
        _state.update { s ->
            s.copy(playlistItems = s.playlistItems + (id to transform(s.playlistDetail(id))))
        }
    }

    /**
     * 拉歌单列表。
     *
     * [force] = false 且已经拉过就跳过（同 [loadGroups]：音乐页会反复在几个视图间切换）。
     * 写操作与 WS 的 `media:playlists` 信号都走 force = true。
     */
    fun loadPlaylists(force: Boolean = false) {
        val current = _state.value.playlists
        if (!force && current.loadedOnce) return
        if (current.isLoading) return
        updatePlaylists { it.copy(isLoading = true, errorMessage = null) }
        scope.launch {
            try {
                val resp = api.getPlaylists()
                updatePlaylists {
                    it.copy(
                        playlists = resp.playlists,
                        isLoading = false,
                        loadedOnce = true,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                updatePlaylists {
                    it.copy(
                        isLoading = false,
                        loadedOnce = true,
                        errorMessage = "歌单加载失败: ${e.message}"
                    )
                }
            }
        }
    }

    /** 拉某个歌单的曲目。不分页 —— core 一次给全量，顺序就是用户排定的顺序。 */
    fun loadPlaylistItems(id: String, force: Boolean = false) {
        if (id.isBlank()) return
        val current = _state.value.playlistDetail(id)
        if (!force && current.loadedOnce) return
        if (current.isLoading) return
        updatePlaylistDetail(id) { it.copy(id = id, isLoading = true, errorMessage = null) }
        scope.launch {
            try {
                val resp = api.getPlaylistItems(id)
                updatePlaylistDetail(id) {
                    it.copy(
                        id = resp.id.ifBlank { id },
                        name = resp.name,
                        items = resp.items,
                        missingCount = resp.missing_count,
                        isLoading = false,
                        loadedOnce = true,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                updatePlaylistDetail(id) {
                    it.copy(
                        isLoading = false,
                        loadedOnce = true,
                        errorMessage = "歌单曲目加载失败: ${e.message}"
                    )
                }
            }
        }
    }

    /** 丢掉某个歌单的曲目缓存（离开歌单详情页时调）。理由同 [clearGroupItems]。 */
    fun clearPlaylistItems(id: String) {
        _state.update { s -> s.copy(playlistItems = s.playlistItems - id) }
    }

    /**
     * 新建歌单，可选地立刻把 [thenAddPaths] 加进去。
     *
     * 两步合成一个入口是为了「新建歌单并加入」那个常见动作：拆成两次调用会让页面自己去串
     * "创建成功 → 拿到 id → 再加歌"，而中途失败时用户会得到一个空歌单却不知道歌没进去。
     */
    fun createPlaylist(name: String, thenAddPaths: List<String> = emptyList()) {
        if (name.isBlank()) return
        scope.launch {
            try {
                val created = api.createPlaylist(PlaylistNameRequest(name.trim()))
                if (thenAddPaths.isNotEmpty() && created.id.isNotBlank()) {
                    val added = api.addPlaylistItems(
                        created.id,
                        PlaylistPathsRequest(thenAddPaths)
                    )
                    updatePlaylists { it.copy(message = addResultMessage(created.name, added)) }
                } else {
                    updatePlaylists { it.copy(message = "已创建歌单「${created.name}」") }
                }
                loadPlaylists(force = true)
            } catch (e: Exception) {
                updatePlaylists { it.copy(errorMessage = playlistErrorText("创建歌单", e)) }
            }
        }
    }

    fun renamePlaylist(id: String, name: String) {
        if (id.isBlank() || name.isBlank()) return
        scope.launch {
            try {
                api.renamePlaylist(id, PlaylistNameRequest(name.trim()))
                updatePlaylists { it.copy(message = "已重命名为「${name.trim()}」") }
                loadPlaylists(force = true)
                // 详情页的标题也要跟着变；没打开过的歌单不会有这一份，force 重拉是安全的
                if (_state.value.playlistItems.containsKey(id)) loadPlaylistItems(id, force = true)
            } catch (e: Exception) {
                updatePlaylists { it.copy(errorMessage = playlistErrorText("重命名", e)) }
            }
        }
    }

    /**
     * 删除歌单。
     *
     * **不动播放器**：正在播的队列已经在 `MediaSessionService` 里，删掉歌单不该让音乐停下来
     * （用户只是整理列表）。下一次进播放页时作用域取不到曲目，会自然落到单曲兜底分支。
     */
    fun deletePlaylist(id: String) {
        if (id.isBlank()) return
        scope.launch {
            try {
                api.deletePlaylist(id)
                clearPlaylistItems(id)
                updatePlaylists { it.copy(message = "歌单已删除") }
                loadPlaylists(force = true)
            } catch (e: Exception) {
                updatePlaylists { it.copy(errorMessage = playlistErrorText("删除歌单", e)) }
            }
        }
    }

    /** 把曲目加进歌单。跳过与截断的口径由 core 给出，这里只把结果翻成一句话。 */
    fun addToPlaylist(playlistId: String, paths: List<String>) {
        if (playlistId.isBlank() || paths.isEmpty()) return
        scope.launch {
            try {
                val resp = api.addPlaylistItems(playlistId, PlaylistPathsRequest(paths))
                val name = _state.value.playlists.playlists
                    .firstOrNull { it.id == playlistId }?.name ?: "歌单"
                updatePlaylists { it.copy(message = addResultMessage(name, resp)) }
                loadPlaylists(force = true)
                if (_state.value.playlistItems.containsKey(playlistId)) {
                    loadPlaylistItems(playlistId, force = true)
                }
            } catch (e: Exception) {
                updatePlaylists { it.copy(errorMessage = playlistErrorText("加入歌单", e)) }
            }
        }
    }

    /** 把曲目移出歌单。已失效的条目也走这条 —— 用户手动清理，不是 App 自动删。 */
    fun removeFromPlaylist(playlistId: String, paths: List<String>) {
        if (playlistId.isBlank() || paths.isEmpty()) return
        scope.launch {
            try {
                // path 必须先编码：这条走的是重复 query 参数，Retrofit 声明成 encoded = true
                // （OkHttp 不编码 `+`，Ktor 又把 `+` 当空格 —— 不编的话含 `+` 的曲目移不掉）
                val resp = api.removePlaylistItems(playlistId, paths.map(::encodeUriComponent))
                updatePlaylists { it.copy(message = "已移出 ${resp.removed} 首") }
                loadPlaylistItems(playlistId, force = true)
                loadPlaylists(force = true)
            } catch (e: Exception) {
                updatePlaylists { it.copy(errorMessage = playlistErrorText("移出歌单", e)) }
            }
        }
    }

    /** 页面显示过提示/错误之后调用，避免重组时反复弹同一条。 */
    fun clearPlaylistMessage() {
        updatePlaylists { it.copy(message = null, errorMessage = null) }
    }

    /**
     * WS `data_changed` 的精准刷新入口（由 `MainViewModel.collectDataChangedEvents` 分发）。
     *
     * 目前只认 [WsDataTopic.MEDIA_PLAYLISTS]：另一端（通常是 web）建 / 改 / 删歌单、加歌移歌
     * 都会推这一条。已经打开过的歌单曲目一并重拉 —— 只刷列表的话，正在看的那个歌单
     * 里被另一端加的歌不会出现。
     *
     * 认不出的 topic 直接忽略：媒体库本身（`/api/media/list`）不走 WS，core 也没在推。
     */
    fun smartRefresh(changedType: String) {
        if (changedType != WsDataTopic.MEDIA_PLAYLISTS) return
        loadPlaylists(force = true)
        _state.value.playlistItems.keys.forEach { loadPlaylistItems(it, force = true) }
    }

    /**
     * 加歌结果 → 一句话。
     *
     * 把 `skipped` / `truncated` 说出来而不是只报"已加入"：用户选了 20 首却只进去 3 首时，
     * 一句笼统的成功提示会让人以为是 App 丢了歌。
     */
    private fun addResultMessage(playlistName: String, resp: PlaylistAddResponse): String {
        val head = "已加入「$playlistName」${resp.added} 首"
        val tail = buildList {
            if (resp.skipped > 0) add("${resp.skipped} 首已在歌单里或不在媒体库")
            if (resp.truncated) add("歌单条数已达上限")
        }
        return if (tail.isEmpty()) head else "$head（${tail.joinToString("，")}）"
    }

    /**
     * 歌单写操作的失败文案。
     *
     * 409 与 400 各有明确的下一步（换个名字 / 删几个歌单），所以不能都说成"操作失败"。
     * 判据用 HTTP 状态码而不是 core 的 message —— 后者是给人看的，会改。
     */
    private fun playlistErrorText(action: String, e: Exception): String {
        val code = (e as? retrofit2.HttpException)?.code()
        return when (code) {
            409 -> "已有同名歌单，换个名字再试"
            404 -> "歌单不存在（可能已在别处被删除）"
            403 -> "媒体权限未授权，无法读取歌单曲目"
            else -> "$action 失败: ${e.message}"
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
     * 字幕内容 URL，**直接塞给播放器**（`MediaItem.SubtitleConfiguration`）。
     *
     * 为什么不用 [streamUrl]（`/api/files/stream`）：那条给的是原始字节，而播放器的字幕
     * 解析器按 UTF-8 解 —— 中文字幕大量是 GB18030/Big5，直接喂过去就是一屏乱码，
     * 且播放器没有"换编码重试"的入口。`/api/media/subtitle` 在服务端统一转好 UTF-8，
     * 并按后缀给准确的字幕 MIME。
     *
     * 鉴权：这条 URL 由播放器的 OkHttpDataSource 拉取，那个 client 带了设备签名拦截器
     * （见 `UfiStreamHttpClient`），所以和视频流走同一套鉴权，不需要额外的票据。
     */
    fun subtitleUrl(path: String): String {
        val prefs = AppPreferences(appContext)
        return "http://${prefs.effectiveHost}:${prefs.serverPort}" +
            "/api/media/subtitle?path=${URLEncoder.encode(path, "UTF-8")}"
    }

    /**
     * 拉某个视频的外挂字幕列表。
     *
     * [folderScope] = true 时回同目录全部字幕（"手动选字幕文件"用）；
     * 否则只回 core 判定属于这个视频的（自动挂载用）。
     *
     * 失败回空列表而不是抛：字幕是增强项，拉不到就当没有，不该把播放页搞崩。
     */
    suspend fun subtitlesOf(path: String, folderScope: Boolean = false): List<MediaSubtitleEntry> =
        try {
            api.getMediaSubtitles(path, if (folderScope) "folder" else null).items
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * 让**设备侧**丢掉已缓存的缩略图。
     *
     * ## 为什么需要这个
     * 缩略图有三层缓存，只清一层等于没清：
     *  1. 图片加载库（按 URL 命中的磁盘 + 内存缓存）；
     *  2. 本机抽帧的成果（`MediaThumbnailBuilder` 的目录）；
     *  3. **设备上客户端回传的成果** ← 本方法清的是这层。
     *
     * `/thumbnail` 的 URL 只含 (type, id)、不含内容指纹，命中即原样返回。
     * 所以一张算错的图（典型：抽到黑场）会一直被发下去；只清 1、2 层的话，
     * 下一次请求立刻被设备上的旧图命中，用户看到的是"清了没反应"。
     *
     * 失败回 false 不抛：清缓存是便利操作，设备没响应不该让界面崩。
     */
    suspend fun clearRemoteThumbnails(type: String? = null): Boolean = try {
        api.clearMediaThumbnailCache(type)
        true
    } catch (e: Exception) {
        false
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
