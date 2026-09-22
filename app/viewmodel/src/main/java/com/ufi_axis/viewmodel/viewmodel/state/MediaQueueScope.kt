package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.model.MEDIA_GROUP_ALBUM
import com.ufi_axis.data.model.MEDIA_GROUP_ARTIST
import com.ufi_axis.data.model.MEDIA_GROUP_FOLDER

/** [AudioQueueScope.kind] 取值：整库。 */
const val AUDIO_SCOPE_ALL = "all"

/**
 * [AudioQueueScope.kind] 取值：某个歌单（[AudioQueueScope.key] = 歌单 id）。
 *
 * 与三个分组维度的关键区别：分组是"按标签回查 `/api/media/list`"，歌单是"core 里存着的一份
 * 有序列表"，顺序由用户排定。所以取队列时走 `/api/playlists/{id}/items` 而不是 list，
 * 且**不套用音乐页的 sort/order** —— 手排好的单子被按修改时间重排是纯粹的破坏。
 */
const val AUDIO_SCOPE_PLAYLIST = "playlist"


/**
 * 音频播放队列的**作用域**。
 *
 * ## 为什么需要它
 *
 * 在这之前播放队列只有一种可能：全局「所有音乐」的第一页（100 首）。分组详情页
 * （专辑 / 歌手 / 文件夹）点歌与「全部」页点歌走的是**同一条路由、同一个参数**，
 * 路由里只有 `path`，不带任何范围信息。后果是：
 *
 * - 「只播这个歌手的歌」做不到 —— 从歌手页点第一首，队列就已经是整库了；
 * - 「下一首」永远是整库里的下一首，与你点进来的那个专辑/目录无关；
 * - shuffle 是在整库里随机；
 * - 更糟的是，如果你点的那首**不在已加载的前 100 条内**，`indexOf` 返回 -1 被
 *   `?: 0` 兜成 0，于是播的是整库第一首 —— 点第 300 首播第 1 首，UI 也跟着显示错的。
 *
 * 引入作用域后，「这个队列是从哪来的」成为一等信息：同作用域才复用队列，换作用域就整体重装。
 *
 * @param kind 范围类型。取 [AUDIO_SCOPE_ALL]、[MEDIA_GROUP_ALBUM]、[MEDIA_GROUP_ARTIST]、
 *   [MEDIA_GROUP_FOLDER] 之一。刻意复用分组维度的常量，这样"浏览维度"与"播放范围"
 *   是同一套词表，不会出现两边对不上的情况。
 * @param key  范围内的标识：专辑名 / 歌手名 / 目录绝对路径；[AUDIO_SCOPE_ALL] 时为空串。
 */
data class AudioQueueScope(
    val kind: String = AUDIO_SCOPE_ALL,
    val key: String = ""
) {
    val isAll: Boolean get() = kind == AUDIO_SCOPE_ALL

    /** 是否为分组维度（专辑 / 歌手 / 文件夹）—— 这三种共用 `/api/media/list` 的过滤参数。 */
    val isGroup: Boolean
        get() = kind == MEDIA_GROUP_ALBUM || kind == MEDIA_GROUP_ARTIST || kind == MEDIA_GROUP_FOLDER

    /** 是否为歌单（[key] = 歌单 id，走 `/api/playlists/{id}/items`）。 */
    val isPlaylist: Boolean get() = kind == AUDIO_SCOPE_PLAYLIST

    /**
     * 供 UI 显示"正在播放：XXX"。
     *
     * 歌单只回一个泛称：这里只有 id，拿名字要发一次网络请求，而值对象不该持有 IO。
     * 需要显示歌单名的地方（歌单详情页）本来就已经有那份数据。
     */
    fun label(): String = when (kind) {
        AUDIO_SCOPE_ALL -> "全部音乐"
        MEDIA_GROUP_ALBUM -> "专辑 · $key"
        MEDIA_GROUP_ARTIST -> "歌手 · $key"
        MEDIA_GROUP_FOLDER -> "文件夹 · ${key.substringAfterLast('/')}"
        AUDIO_SCOPE_PLAYLIST -> "歌单"
        else -> key.ifBlank { kind }
    }

    companion object {
        val ALL = AudioQueueScope(AUDIO_SCOPE_ALL, "")

        /**
         * 从路由参数还原播放范围。
         *
         * ## 返回 null 的含义是「**没指定**范围」，不是「整库」（2026-09-21 改）
         * 这两件事必须分开：
         * - 迷你条 / 标题栏挂件 / 通知栏深链接只知道"正在播这首歌"，**不知道**当前队列是按
         *   歌单还是专辑装的。它们不带 scope，语义是"打开播放页看一眼"——
         *   播放页收到 null 就**不动队列**。
         * - 「全部」页点歌才是"请按整库重建队列"，它显式带 `scope=all`。
         *
         * 混成一个 [ALL] 的那一版有个很难自己发现的后果：从歌单点「播放全部」之后，
         * 只要再从迷你条/挂件点回播放页，队列就被静默重装成整库 —— 歌还在放同一首，
         * 但「下一首」已经跑出歌单了。
         *
         * 非法 kind 也回 null（而不是 ALL）：宁可保留用户当前的队列，也不要按一个看不懂的
         * 参数去重建。
         */
        fun of(kind: String?, key: String?): AudioQueueScope? {
            val k = kind?.takeIf { it.isNotBlank() } ?: return null
            if (k == AUDIO_SCOPE_ALL) return ALL
            val valid = k == MEDIA_GROUP_ALBUM || k == MEDIA_GROUP_ARTIST ||
                k == MEDIA_GROUP_FOLDER || k == AUDIO_SCOPE_PLAYLIST
            if (!valid) return null
            val v = key.orEmpty()
            // key 为空的分组是"无标签兜底组"，回查会被当成没传过滤参数 = 整库，语义完全不同。
            // 歌单同理：空 id 取不到任何歌单。这种情况按"没指定"处理，不碰队列。
            if (v.isBlank()) return null
            return AudioQueueScope(k, v)
        }
    }
}

/**
 * 「播放器里那份队列当前属于哪个作用域」——**进程内**单例。
 *
 * 为什么不放进 `MediaLibraryState`：音频播放跑在 `MediaSessionService` 里，离开媒体页、
 * 甚至整个 ViewModel 被回收之后仍在播。把它放进某个页面的 state 会在"退出页面再进来"时丢失，
 * 于是每次回到播放页都会被判成"换了作用域"而整体重装队列（把正在听的歌从头开始）。
 *
 * 与 `UfiNowPlayingState` 同级、同理由：那份也是进程内常驻的播放态。
 */
object AudioQueueOwner {
    @Volatile
    private var scope: AudioQueueScope? = null

    /** null = 进程启动后还没装过队列（此时任何作用域都应整体装载）。 */
    fun current(): AudioQueueScope? = scope

    fun set(value: AudioQueueScope) {
        scope = value
    }

    /** 队列被清空/失效时调用（目前只有单曲兜底那条路径需要）。 */
    fun clear() {
        scope = null
    }

    @Volatile
    private var shuffleRequested = false

    /**
     * 「随机播放本组」的一次性请求。
     *
     * 分组页没有 `MediaController`（播放器是跨进程的 MediaSessionService，
     * 只有播放页持有 controller），所以"开随机"这个动作没法在分组页当场执行。
     * 这里放一个一次性标记，播放页装完队列后 [consumeShuffleRequest] 取走并落到
     * `shuffleModeEnabled` 上 —— 与作用域同属"队列的意图"，放在一起而不是再造一个单例。
     */
    fun requestShuffle() {
        shuffleRequested = true
    }

    /** 读取并清除。没请求过返回 false。 */
    fun consumeShuffleRequest(): Boolean {
        val v = shuffleRequested
        shuffleRequested = false
        return v
    }
}
