package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.model.MEDIA_GROUP_ALBUM
import com.ufi_axis.data.model.MEDIA_GROUP_ARTIST
import com.ufi_axis.data.model.MEDIA_GROUP_FOLDER
import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.data.model.MEDIA_TYPE_IMAGE
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.data.model.MediaFolderEntry
import com.ufi_axis.data.model.MediaGroupEntry
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.data.model.PlaylistEntry

/** 媒体列表的排序字段与方向（与 core `/api/media/list` 的 `sort` / `order` 取值一致）。 */
const val MEDIA_SORT_DATE = "date"
const val MEDIA_SORT_NAME = "name"
const val MEDIA_SORT_SIZE = "size"
const val MEDIA_ORDER_DESC = "desc"
const val MEDIA_ORDER_ASC = "asc"

/**
 * 三类媒体及其中文名，顺序即工具页入口卡的顺序。
 *
 * 2026-09-16 从 `MEDIA_TABS` 改名：媒体中心那一页已拆成三个独立页（视频 / 音乐 / 图片），
 * 这里不再是"分栏"，而是"这台设备上有哪几类媒体"。文案仍收在一处，不在页面里各写一份。
 */
val MEDIA_KINDS: List<Pair<String, String>> = listOf(
    MEDIA_TYPE_VIDEO to "视频",
    MEDIA_TYPE_AUDIO to "音乐",
    MEDIA_TYPE_IMAGE to "图片"
)

/**
 * 一类媒体（视频 / 音乐 / 图片）的状态。
 *
 * 三类**各存一份**：它们是三份互不相干的数据，共用一个槽位会导致来回切页时互相清空
 * （与 `TrafficHistoryState.dataByKey` 同一条理由）。拆成三个页面后这条更重要 ——
 * 从视频页去音乐页再回来，视频列表必须还在。
 *
 * @param granted 这一类媒体在设备端是否已授权。false 时界面显示授权引导 ——
 *   **不许**显示空列表，那等于告诉用户"设备里没有视频"。
 * @param scanDirs 这一类自己的扫描目录（设备配置，存在 core）。空 = 这一类不限目录。
 *   2026-09-16 从全局一份改成按类型各一份：每个页面只改自己那一份，所以三页各有可写
 *   入口也不会互相覆盖；三类指向同一个目录也不冲突（MediaStore 本来分表查）。
 * @param gridView 列表 / 网格（**展示偏好**，存客户端本地 prefs，按类型各一份）。
 *   与文件管理器的 `FileViewMode` 同一口径：进程重启后要还原，但不进 core。
 * @param total 符合条件的**总数**（不是已加载条数），用来判断还有没有下一页。
 * @param isLoading 首屏加载（[items] 为空）；[isAppending] 是加载下一页，两者不能共用一个标志，
 *   否则翻页时整块会闪成骨架屏。
 */
data class MediaTabState(
    val type: String = MEDIA_TYPE_VIDEO,
    val items: List<MediaLibraryItem> = emptyList(),
    val total: Int = 0,
    val isLoading: Boolean = false,
    val isAppending: Boolean = false,
    val granted: Boolean = true,
    val loadedOnce: Boolean = false,
    val errorMessage: String? = null,
    val sort: String = MEDIA_SORT_DATE,
    val order: String = MEDIA_ORDER_DESC,
    val scanDirs: List<String> = emptyList(),
    val isRescanning: Boolean = false,
    val message: String? = null,
    val gridView: Boolean = false
) {
    val hasMore: Boolean get() = items.size < total
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * 音频分组（专辑 / 歌手 / 文件夹）的列表状态。
 *
 * 与 [MediaTabState] 分开：分组是一次性全量聚合（core 侧算好），没有分页，
 * 塞进 MediaTabState 会让那边的 offset/isAppending 出现"用不到但必须维护"的字段。
 *
 * @param groups 分组条目。其中 [MediaGroupEntry.key] 为空串的那一组（无标签文件的兜底组）
 *   只能展示不能点进去 —— 空 key 回查 `/api/media/list` 会被当成没传参数，结果是整库。
 */
data class MediaGroupState(
    val by: String = MEDIA_GROUP_ALBUM,
    val groups: List<MediaGroupEntry> = emptyList(),
    val isLoading: Boolean = false,
    val loadedOnce: Boolean = false,
    val errorMessage: String? = null
)

/**
 * 音频分组维度及其中文名，顺序即界面上切换控件的顺序。
 *
 * 文案与合法取值都收在这一处：页面不各写一份，取数层判定 `by` 合法性也用这份，
 * 免得两边各有一套"支持哪几种分组"。
 */
val MEDIA_GROUP_KINDS: List<Pair<String, String>> = listOf(
    MEDIA_GROUP_ALBUM to "专辑",
    MEDIA_GROUP_ARTIST to "歌手",
    MEDIA_GROUP_FOLDER to "文件夹"
)

/**
 * `MediaLibraryState.audioGroupItems` 的键：把分组维度和组内 key 拼在一起。
 *
 * 拼成一个 String 而不是嵌套两层 Map：状态更新时只需要一次 `+`，也不用为"这个维度还没有
 * 任何组被打开过"准备空壳。取值只写不读回（没有反向解析），所以分隔符不需要转义。
 */
fun mediaGroupItemsKey(by: String, key: String): String = "$by:$key"

/**
 * 音频歌单列表的状态（`GET /api/playlists` 的镜像）。
 *
 * 歌单本体存在 core，两端看同一份 —— 所以这里是纯镜像，没有本地增删改的中间态。
 * 不分页：歌单数有上限（core 侧 100），一次拉完。
 *
 * @param message 一次性提示（"已加入 12 首"/"有 3 首不在媒体库"），由页面消费后清空。
 */
data class MediaPlaylistState(
    val playlists: List<PlaylistEntry> = emptyList(),
    val isLoading: Boolean = false,
    val loadedOnce: Boolean = false,
    val errorMessage: String? = null,
    val message: String? = null
)

/**
 * 一个歌单的曲目（`GET /api/playlists/{id}/items` 的镜像）。
 *
 * 不复用 [MediaTabState]（分组详情页那样）：歌单曲目**不分页**（core 一次给全量，顺序是用户
 * 排定的），把它塞进那个类会多出 total/isAppending/hasMore 这些"必须维护但永远用不到"的字段，
 * 而真正需要的 [missingCount] 又没有位置。
 *
 * @param items 顺序即播放顺序。已失效的条目照样在里面（`missing = true`），不要过滤掉。
 * @param missingCount 已失效条目数，用来在页头提示"3 首已失效"。
 */
data class MediaPlaylistDetailState(
    val id: String = "",
    val name: String = "",
    val items: List<MediaLibraryItem> = emptyList(),
    val missingCount: Int = 0,
    val isLoading: Boolean = false,
    val loadedOnce: Boolean = false,
    val errorMessage: String? = null
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** 可播放的曲目（把已失效的排掉）——装播放队列时只能用这一份。 */
    val playable: List<MediaLibraryItem> get() = items.filterNot { it.missing }
}

/**
 * 文件夹视图的状态（媒体库那一栏；`GET /api/media/browse` 的镜像）。
 * 与 [MediaTabState] **并存**而不是合并：平铺列表与文件夹视图是两份互不相同的数据
 * （一个分页、一个按层），合进去会出现"进文件夹之后平铺列表被冲掉、退出来又要重拉"。
 *
 * @param path 当前所在目录（空 = 还没定位到任何目录，此时看 [roots]）。
 * @param parent 上一级；null 表示已经在根上，界面不该再给"返回上一级"。
 * @param roots 可选的根目录。仅当配了多个扫描目录且 [path] 为空时才需要让用户先选。
 */
data class MediaBrowseState(
    val path: String = "",
    val parent: String? = null,
    val roots: List<String> = emptyList(),
    val folders: List<MediaFolderEntry> = emptyList(),
    val items: List<MediaLibraryItem> = emptyList(),
    val isLoading: Boolean = false,
    val loadedOnce: Boolean = false,
    val errorMessage: String? = null
) {
    val isEmpty: Boolean get() = folders.isEmpty() && items.isEmpty()
}

/**
 * 媒体库的整体状态（视频页 / 音乐页 / 图片页共用同一份仓库层状态）。
 *
 * 2026-09-16 从 `MediaCenterState` 改名：它不再是"媒体中心那一页的状态"，而是三个页面
 * 共享的数据仓库。真正**跨类型**的只剩两样：`allFilesAccess`（一个「所有文件访问」放行
 * 三类）与 `statusLoaded`；扫描目录、重扫状态、提示文案都下沉到了 [MediaTabState]，
 * 因为它们现在按类型各一份。
 *
 * 排序 / 视图这类纯展示偏好留在 [MediaTabState] 里，不往 core 写。
 */
data class MediaLibraryState(
    val tabs: Map<String, MediaTabState> = MEDIA_KINDS.associate { (type, _) ->
        type to MediaTabState(type = type)
    },
    /** 文件夹视图（按类型各一份，目前只有视频页用）。 */
    val browse: Map<String, MediaBrowseState> = emptyMap(),
    /**
     * 音频分组列表，**每个维度各存一份**（key 是 [MEDIA_GROUP_ALBUM] 等）。
     *
     * 切换维度只改界面上"当前选的是哪个"，不清除另外两份已加载的结果 —— 在专辑/歌手之间
     * 来回切是很常见的操作，每切一次重拉一遍网络没有道理（与 [tabs] 同一条理由）。
     */
    val audioGroups: Map<String, MediaGroupState> = emptyMap(),
    /**
     * 「某一组里的曲目」列表，键由 [mediaGroupItemsKey] 拼出。
     *
     * 直接复用 [MediaTabState]：组内曲目走的就是 `/api/media/list`（带 album/artist/dir 过滤），
     * 同样要分页、同样要区分首屏与追加，再造一个几乎一样的类只会多一处要同步维护的地方。
     * 其中 `scanDirs` / `isRescanning` / `gridView` 这些字段在这里用不上（分组详情页不改配置），
     * 保持默认值即可。
     *
     * 离开分组详情页时由 `clearGroupItems` 移除对应那一份：专辑动辄上百个，
     * 逛一圈下来全留在内存里没有意义。
     */
    val audioGroupItems: Map<String, MediaTabState> = emptyMap(),
    /** 歌单列表（`/api/playlists`）。音乐页的第四个视图。 */
    val playlists: MediaPlaylistState = MediaPlaylistState(),
    /**
     * 歌单曲目，按歌单 id 各存一份。
     *
     * 与 [audioGroupItems] 同一策略：离开详情页时由 `clearPlaylistItems` 移除对应那一份，
     * 逛一圈下来全留在内存里没有意义。
     */
    val playlistItems: Map<String, MediaPlaylistDetailState> = emptyMap(),
    val allFilesAccess: Boolean = false,
    val statusLoaded: Boolean = false,
    val errorMessage: String? = null
) {
    fun tab(type: String): MediaTabState = tabs[type] ?: MediaTabState(type = type)
    fun folderView(type: String): MediaBrowseState = browse[type] ?: MediaBrowseState()

    /** 某个分组维度的列表；没拉过时回一个带正确 [MediaGroupState.by] 的空态。 */
    fun group(by: String): MediaGroupState = audioGroups[by] ?: MediaGroupState(by = by)

    /** 某一组里的曲目；没打开过时回空态（type 恒为音频 —— 分组只对音频有效）。 */
    fun groupItems(by: String, key: String): MediaTabState =
        audioGroupItems[mediaGroupItemsKey(by, key)] ?: MediaTabState(type = MEDIA_TYPE_AUDIO)

    /** 某个歌单的曲目；没打开过时回一个带正确 id 的空态。 */
    fun playlistDetail(id: String): MediaPlaylistDetailState =
        playlistItems[id] ?: MediaPlaylistDetailState(id = id)
}
