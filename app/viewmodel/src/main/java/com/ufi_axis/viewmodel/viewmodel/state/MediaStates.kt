package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.data.model.MEDIA_TYPE_IMAGE
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.data.model.MediaFolderEntry
import com.ufi_axis.data.model.MediaLibraryItem

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
 * 文件夹视图的状态（媒体库那一栏；`GET /api/media/browse` 的镜像）。
 *
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
    val allFilesAccess: Boolean = false,
    val statusLoaded: Boolean = false,
    /**
     * FFmpeg 可选组件状态（`GET /api/components` 里 id=ffmpeg 那一条）。
     *
     * 放在媒体状态里而不是隧道状态里：FFmpeg 只服务视频封面抽帧，
     * 安装入口也只在媒体设置页，和内网穿透那两个组件没有任何关系。
     * null = 还没拉到（或 core 版本过旧没有这个组件）。
     */
    val ffmpegComponent: ComponentInfo? = null,
    /** FFmpeg 组件的安装任务进度（core 侧同一时刻只允许一个组件任务） */
    val ffmpegTask: ComponentTask = ComponentTask(),
    val errorMessage: String? = null
) {
    fun tab(type: String): MediaTabState = tabs[type] ?: MediaTabState(type = type)
    fun folderView(type: String): MediaBrowseState = browse[type] ?: MediaBrowseState()
}
