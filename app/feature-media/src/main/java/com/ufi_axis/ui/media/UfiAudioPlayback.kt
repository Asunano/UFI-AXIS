package com.ufi_axis.ui.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import com.ufi_axis.viewmodel.state.AudioQueueOwner
import com.ufi_axis.viewmodel.state.AudioQueueScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * 音乐播放的**系统侧存在感**（2026-09-16）：MediaSession + 前台服务。
 *
 * ## 为什么音乐必须走服务，视频不用
 * 音乐是"放着不看"的：锁屏、通知栏、快捷设置里的媒体控制、耳机上的暂停键都要能用。
 * 这些全部来自一个**活着的 MediaSession**，而 session 要活过页面就必须挂在服务上。
 * 视频反过来 —— 离开画面就没有继续播的意义，所以视频页仍然是页内 [ExoPlayer]（见
 * `rememberUfiExoPlayer`），不进这个服务。
 *
 * ## 为什么不自己发 MediaStyle 通知
 * [MediaSessionService] 自带通知与播放状态同步、媒体按键路由、音频焦点让位。自己搓一份
 * 通知，播放状态一变就要手动重发，漂移是必然的（暂停了通知还显示在播这种）。
 *
 * ## 鉴权
 * 播放地址是 `/api/files/stream`，挂在 AuthMiddleware 之后且 nonce 防重放，所以播放器与
 * **通知里的封面加载**都要走 [UfiStreamHttpClient]（逐请求重签）。封面用
 * [DataSourceBitmapLoader] + 同一个 OkHttp 工厂：用 media3 默认的 loader 会走
 * `DefaultHttpDataSource`，那条路没有签名，通知上就是一张空封面。
 */
@OptIn(UnstableApi::class)
class UfiAudioPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext
        val dataSourceFactory: DataSource.Factory =
            OkHttpDataSource.Factory(UfiStreamHttpClient.get(app))
        val player = ExoPlayer.Builder(app)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(4))
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // 拔耳机就暂停（系统行为，不是我们自己猜的"用户可能想停"）
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 音乐走 HTTP 流，默认 WAKE_MODE_NONE 在息屏 / Doze 下会让缓冲断供（锁屏听歌卡顿）。
        // WAKE_MODE_NETWORK = 播放期间持 PARTIAL_WAKE_LOCK + WifiLock，停播自动释放。
        player.setWakeMode(C.WAKE_MODE_NETWORK)


        // ── 循环 / 随机模式的恢复与持久化（2026-09-22）────────────────────────
        //
        // 修的是"循环功能时好时坏"：这两个模式原来**只活在这个 ExoPlayer 里**，而
        // onTaskRemoved 在没播放时 stopSelf()、onDestroy 里 release 掉 player。
        // 下一次连上拿到的是全新实例，默认 REPEAT_MODE_OFF / shuffle=false，
        // 用户选的单曲循环就这么静默消失了，界面上也没有任何提示。
        //
        // 落在**服务**里而不是播放页：写入方不止一个 —— 播放页、标题栏挂件的迷你弹窗、
        // 通知栏、外接媒体键、Android Auto 都能改这两个值。挂在 player 的 Listener 上是
        // 唯一能把它们全兜住的位置（所有写入最终都落到这个 player）。写在某个页面里就会
        // 变成"只有从那个页面改才记得住"。
        val playbackPrefs = AppPreferences(app)
        player.repeatMode = playbackPrefs.audioRepeatMode
        player.shuffleModeEnabled = playbackPrefs.audioShuffleEnabled
        player.addListener(object : Player.Listener {
            override fun onRepeatModeChanged(repeatMode: Int) {
                playbackPrefs.audioRepeatMode = repeatMode
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                playbackPrefs.audioShuffleEnabled = shuffleModeEnabled
            }
        })


        // ── 播放队列的恢复与持久化（2026-09-23）────────────────────────────────
        //
        // 与上面两个模式同理由、同位置：队列可以被播放页、队列弹窗、通知栏、外接媒体键
        // 从多处改动，挂在 player 的 Listener 上是唯一能全兜住的地方。
        //
        // 恢复**必须 playWhenReady = false**：开 app 自己响是重大体验事故。
        // 恢复是"把上次的队列摆回来"，不是"继续播放"。
        UfiAudioQueueStore.restoreInto(app, player)
        player.addListener(object : Player.Listener {
            // timeline 变化 = 装/插/删/重排；位置变化单独记，否则重启后从头开始
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                UfiAudioQueueStore.scheduleSave(app, player)
            }

            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                UfiAudioQueueStore.scheduleSave(app, player)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                UfiAudioQueueStore.scheduleSave(app, player)
            }
        })

        session = MediaSession.Builder(this, player)

            .setBitmapLoader(
                CacheBitmapLoader(
                    DataSourceBitmapLoader(
                        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
                        dataSourceFactory
                    )
                )
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * 任务被划掉：**没在播就把自己收掉**。
     *
     * 正在播时保留（用户明确在听，划掉任务栏不等于停止播放，通知上有停止入口）；
     * 没在播还常驻就是白占一个前台服务 —— 与「后台唤醒策略：事件驱动 + 按需持锁」同一口径。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.let { s ->
            // 落盘必须在 release 之前：release 之后 player 的 timeline / 位置就读不到了。
            // 用 flush 而不是 scheduleSave —— 去抖的 2s 等不到，进程马上就没了。
            UfiAudioQueueStore.flush(applicationContext, s.player)
            s.player.release()
            s.release()
        }
        session = null

        super.onDestroy()
    }
}

/**
 * 播放队列里的一首歌。
 *
 * [mediaId] 用**文件路径**：服务跨页面活着，页面回来时要判断"播放器里的队列还是不是现在这一份"
 * （见 [MediaAudioPlayerScreen]）。地址里带签名参数、会随时间变，拿 URL 比对不可靠；路径才是
 * 这首歌的身份。
 *
 * ## 为什么**不带**曲名 / 艺术家（2026-09-16）
 * media3 的 `Player.mediaMetadata` 是「[MediaItem] 自带的 metadata」与「流内解析出的
 * ID3 / FLAC 标签」的合并结果，且 **MediaItem 的字段优先**。原来这里塞了 `title = 文件名`，
 * 于是内嵌的真曲名永远被文件名压住 —— 页面与通知栏显示的都是 `带我走-杨丞琳.flac` 这种东西。
 * 现在只交出地址与封面，曲名/艺术家/专辑一律由播放器从文件里解析（页面侧再按
 * 「内嵌 → 媒体库字段 → 文件名」兜底显示）。
 *
 * [artworkUrl] 仍然给：它指向 core 的 `/api/media/cover`（core 已经把内嵌封面取出来了），
 * 比让播放器等到解析完标签才有图更快，且两者是同一张图。
 */
data class UfiAudioTrack(
    val mediaId: String,
    val url: String,
    val artworkUrl: String?
)

/**
 * 连接音乐播放服务，拿到一个 [MediaController]（它本身就是 [Player]）。
 *
 * 连接是异步的（跨进程 binder），所以返回可空：null = 还没连上，界面这时不该假装有播放器。
 * 离开组合只释放**控制器**，不停播放 —— 服务与 session 继续活着，这正是"退出页面音乐还在放、
 * 通知栏还能控"的来源。
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberUfiAudioController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val token = SessionToken(
            context,
            ComponentName(context, UfiAudioPlaybackService::class.java)
        )
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { controller = runCatching { future.get() }.getOrNull() },
            MoreExecutors.directExecutor()
        )
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }
    return controller
}

/**
 * 装音乐播放队列（带元信息）。
 *
 * 与 `ExoPlayer.setUfiPlaylist` 的区别不只是"带了标题"：那个是给视频用的裸地址列表，
 * 而音乐要把曲名/艺术家/封面交给系统去显示。接收类型是 [Player] 而不是 ExoPlayer ——
 * 音乐这条链路上拿到的是跨进程的 [MediaController]。
 *
 * @param startPositionMs 从哪个位置开始。**重装队列但想接着播**时用得上（例如列表换了排序，
 *   队列语义变了，但正在听的那一首不该被拽回开头）。
 */
fun Player.setUfiAudioPlaylist(
    tracks: List<UfiAudioTrack>,
    startIndex: Int,
    startPositionMs: Long = 0L
) {
    if (tracks.isEmpty()) return
    val items = tracks.map { it.toUfiMediaItem() }
    setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), startPositionMs)
    prepare()
}

/**
 * 往队列末尾**追加**（分页拉到后面几首时用）。
 *
 * 不用 [setUfiAudioPlaylist] 重装：重装会把正在播的那一首顶掉从头开始，而"列表多加载了一页"
 * 这件事跟正在听什么毫无关系。
 */
fun Player.addUfiAudioTracks(tracks: List<UfiAudioTrack>) {
    if (tracks.isEmpty()) return
    addMediaItems(tracks.map { it.toUfiMediaItem() })
}

/** 队列里当前各首的 [UfiAudioTrack.mediaId]（= 文件路径），用来判断队列是不是还是同一份。 */
fun Player.ufiAudioQueueIds(): List<String> =
    (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }

/**
 * 「用户想不想让它播」——播放/暂停图标该读这个，**不要读 [Player.isPlaying]**。
 *
 * ## 为什么（2026-09-22 修「切歌时播放键抽搐」）
 * `isPlaying` 的定义是 `playWhenReady && playbackState == READY && 没有被抑制`。
 * 切歌那一下 playbackState 会掉进 `STATE_BUFFERING` 再回 `STATE_READY`，
 * 于是 `isPlaying` 短暂变 false —— 表现就是图标先翻成"播放"（看起来像已暂停）
 * 再翻回来，而音频一秒都没停。缓冲、音频焦点短暂丢失都是同一个毛病。
 *
 * ## 为什么还要排除 STATE_ENDED
 * 队列播完之后 media3 **不会**把 `playWhenReady` 复位成 false。只看 playWhenReady
 * 会让图标停在"正在播放"，而实际上已经停了。
 */
fun Player.ufiPlayIntent(): Boolean =
    playWhenReady && playbackState != Player.STATE_ENDED

/**
 * 「下一首」：切过去并**恢复播放**。
 *
 * media3 的 `seekToNextMediaItem()` 原样保留 `playWhenReady` —— 在 A 里暂停后点下一首，
 * 到了 B 仍然是暂停的。那不是这个按钮的语义：用户按「下一首」是想**听下一首**，
 * 而不是把一个停着的指针挪一格。
 *
 * 只作用在**显式按钮**上。自动续播走 media3 自己的推进，不经过这里，
 * 所以"不循环时播完就停"的行为不受影响。
 */
fun Player.ufiSkipToNext() {
    val target = ufiAdjacentIndex(next = true)
    if (target == null) {
        // 时间线还没就绪（刚 setMediaItems、还没 prepare 完）时退回 media3 自己的推进
        seekToNextMediaItem()
    } else {
        seekTo(target, 0L)
    }
    play()
}

/** 见 [ufiSkipToNext]。 */
fun Player.ufiSkipToPrevious() {
    val target = ufiAdjacentIndex(next = false)
    if (target == null) seekToPreviousMediaItem() else seekTo(target, 0L)
    play()
}

/**
 * 「上一首 / 下一首」该跳到哪一项。时间线为空或算不出来时回 null。
 *
 * ## 为什么不直接用 `seekToNextMediaItem()`（2026-09-23 修「下一首与循环模式互相冲突」）
 * media3 算"下一项"时会把 `repeatMode` 算进去，而 **单曲循环下"下一项"就是自己** ——
 * 于是开着单曲循环按「下一首」是把当前这首从头再放一遍，看起来就是这颗键坏了。
 *
 * 这里在**计算目标下标**时把单曲循环按列表循环算：用户手按这颗键的意思永远是"换一首"；
 * 循环模式回答的是"这一首放完之后怎么办"，两件事不该互相干扰。自动续播不走这里，
 * 所以单曲循环该有的"放完再放一遍"没有被改掉。
 *
 * 随机播放仍然交给时间线：打乱顺序由 media3 维护，自己算会跳到另一首。
 */
private fun Player.ufiAdjacentIndex(next: Boolean): Int? {
    val timeline = currentTimeline
    if (timeline.isEmpty || mediaItemCount == 0) return null
    val repeat = if (repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else repeatMode
    val index = if (next) {
        timeline.getNextWindowIndex(currentMediaItemIndex, repeat, shuffleModeEnabled)
    } else {
        timeline.getPreviousWindowIndex(currentMediaItemIndex, repeat, shuffleModeEnabled)
    }
    // 算不出来时 media3 回 C.INDEX_UNSET(-1)：不循环且已在队尾/队首就是这种情况
    return index.takeIf { it in 0 until mediaItemCount }
}

/**
 * 「下一首 / 上一首」此刻按下去有没有效果 —— 两颗键的 `enabled` 都该读这个。
 *
 * **不要用 `hasNextMediaItem()`**：那一个在单曲循环下恒为 true（"下一项"是自己），
 * 于是键是亮的但按下去只是把当前这首重播一遍。这里与 [ufiSkipToNext] 用同一条判据，
 * 保证"看起来能按"与"按了真有反应"一致。
 */
fun Player.ufiCanSkipToNext(): Boolean = ufiAdjacentIndex(next = true) != null

/** 见 [ufiCanSkipToNext]。 */
fun Player.ufiCanSkipToPrevious(): Boolean = ufiAdjacentIndex(next = false) != null


// ── 队列编辑（2026-09-23「虚拟播放列表」）──────────────────────────────────────
//
// 三个操作都必须 [AudioQueueOwner.markUserEdited]。少了它，播放页下一次装队列时会发现
// "队列内容和作用域推导出来的列表不一致"，于是全量重装 —— 用户刚排的顺序当场消失。
// 详细理由写在 AudioQueueOwner 的 userEdited 注释里。

/**
 * 「下一首播放」：把一首歌插到**当前播放项之后**。
 *
 * 队列为空时（这个进程还没播过任何东西）退化成"立即播放这一首"—— 插到一个空队列里
 * 然后什么都不做，用户会以为点了没反应。
 */
fun Player.ufiPlayNext(track: UfiAudioTrack) {
    val item = track.toUfiMediaItem()
    if (mediaItemCount == 0) {
        setMediaItems(listOf(item), 0, 0L)
        prepare()
        play()
    } else {
        addMediaItem(currentMediaItemIndex + 1, item)
    }
    AudioQueueOwner.markUserEdited()
}

/**
 * 从队列里移除第 [index] 项。**只动队列，不动文件。**
 *
 * 索引是**原始（未打乱）索引**，与 [ufiAudioQueueIds] 的下标一一对应。
 * 随机播放开着时播放顺序与它不同，所以队列面板必须按原始顺序展示 —— 否则用户点掉的
 * 会是另一首。
 *
 * 移除的是正在播的那一首时，media3 自己会跳到下一首；移到空队列则停止。
 */
fun Player.ufiRemoveFromQueue(index: Int) {
    if (index !in 0 until mediaItemCount) return
    removeMediaItem(index)
    AudioQueueOwner.markUserEdited()
    if (mediaItemCount == 0) {
        // 不 stop 的话播放器会停在 ENDED 但迷你条还挂着一条点不动的空壳
        stop()
        clearMediaItems()
        AudioQueueOwner.clear()
    }
}

/**
 * 按路径（= mediaId）把某一首从队列里摘掉。找不到就什么都不做。
 *
 * 删文件之前必须先调这个：留在队列里的话，播到那一项时 ExoPlayer 会抛
 * `ERROR_CODE_IO_FILE_NOT_FOUND`，用户看到的是"播放失败"而不是"这首歌被我删了"。
 */
fun Player.ufiRemoveFromQueueByMediaId(mediaId: String) {
    val index = (0 until mediaItemCount).firstOrNull { getMediaItemAt(it).mediaId == mediaId }
    if (index != null) ufiRemoveFromQueue(index)
}

/** 队列内重排。索引语义同 [ufiRemoveFromQueue]。 */
fun Player.ufiMoveInQueue(from: Int, to: Int) {
    if (from == to) return
    if (from !in 0 until mediaItemCount || to !in 0 until mediaItemCount) return
    moveMediaItem(from, to)
    AudioQueueOwner.markUserEdited()
}

/**
 * 播放队列的持久化。
 *
 * ## 存什么、为什么
 * 只存**路径列表 + 每首的封面 URL + 当前索引 + 位置 + userEdited + 作用域**。
 * `MediaItem` 不可序列化，而播放地址可以现场重建 —— [MediaModule.streamUrl] 本来也只依赖
 * `AppPreferences` 里的主机与端口，所以服务自己就能拼出来，不需要 ViewModel。
 * 封面 URL 是唯一**重建不出来**的那一项（它要 MediaStore 的 id），所以必须存下来，
 * 见 [KEY_ARTWORKS]。
 *
 * `userEdited` 必须一起存：只存队列不存它，进程重启后标记回 false，
 * 下一次进播放页照样被作用域重装，持久化白做（见 `AudioQueueOwner`）。
 *
 * ## 为什么用 org.json 而不是 kotlinx.serialization
 * 这份结构只在本文件内读写、没有跨模块契约，用 `JSONObject` 省掉一个 @Serializable
 * 数据类和「Android 上必须显式给 serializer」那一串注意事项。
 */
internal object UfiAudioQueueStore {

    /**
     * 单次写盘的最大条目数。几百首的路径列表塞进一个 prefs 键不合适，也没人会去翻那么长的队列。
     *
     * 超长队列取的是**以正在播的那一首为中心**的一段，不是前 500 条 —— 理由见 `saveNow`。
     */
    private const val MAX_ENTRIES = 500

    /** 写盘去抖：拖拽排序会连续触发 timeline 变化。 */
    private const val SAVE_DEBOUNCE_MS = 2_000L

    private const val KEY_PATHS = "paths"

    /**
     * 与 [KEY_PATHS] **一一对应**的封面 URL 数组（没有封面时那一格是空串）。
     *
     * 为什么必须存：封面 URL 是 `/api/media/cover?id=`，而 id 是 MediaStore 的 id，
     * 光有路径拼不出来（core 没有 by-path 的封面接口）。不存的那一版恢复出来的队列项
     * `artworkUri` 全是 null —— 迷你条与标题栏挂件读的正是队列项的 artworkUri，
     * 于是重启后它们只剩一个占位音符（播放页自己按 id 查，所以那一屏看不出问题，
     * 这也是这个 bug 能溜过去的原因）。
     */
    private const val KEY_ARTWORKS = "artworks"
    private const val KEY_INDEX = "index"
    private const val KEY_POSITION = "positionMs"
    private const val KEY_EDITED = "userEdited"
    private const val KEY_SCOPE_KIND = "scopeKind"
    private const val KEY_SCOPE_KEY = "scopeKey"

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    /** 去抖保存。多次调用只有最后一次落盘。 */
    fun scheduleSave(context: Context, player: Player) {
        pending?.let(handler::removeCallbacks)
        val task = Runnable { saveNow(context, player) }
        pending = task
        handler.postDelayed(task, SAVE_DEBOUNCE_MS)
    }

    /** 立刻落盘并取消挂起的去抖任务。服务销毁前必须调，否则最后一次改动会丢。 */
    fun flush(context: Context, player: Player) {
        pending?.let(handler::removeCallbacks)
        pending = null
        saveNow(context, player)
    }

    private fun saveNow(context: Context, player: Player) {
        runCatching {
            val prefs = AppPreferences(context)
            val count = player.mediaItemCount
            if (count == 0) {
                prefs.clearAudioQueue()
                return
            }
            val current = player.currentMediaItemIndex.coerceIn(0, count - 1)
            /*
             * 截断窗口**以正在播的那一首为中心**（2026-09-23 修「重启后回来是另一首歌」）。
             *
             * 原来是 `ids.take(MAX_ENTRIES)` + 原样存 index：整库队列上千首时，正在听第 900 首
             * 的话前 500 条里根本没有它，恢复时 index 又被 `coerceIn(0, lastIndex)` 夹到 499 ——
             * 开 app 回来听到的是完全不相干的一首，而且「上一首」还能往前翻 499 首。
             */
            val start = (current - MAX_ENTRIES / 2).coerceIn(0, maxOf(0, count - MAX_ENTRIES))
            val end = minOf(count, start + MAX_ENTRIES)
            val paths = JSONArray()
            val artworks = JSONArray()
            for (i in start until end) {
                val item = player.getMediaItemAt(i)
                paths.put(item.mediaId)
                // 没有封面的那一格存空串而不是跳过：两个数组必须逐项对齐
                artworks.put(item.mediaMetadata.artworkUri?.toString().orEmpty())
            }
            val scope = AudioQueueOwner.current()
            val json = JSONObject().apply {
                put(KEY_PATHS, paths)
                put(KEY_ARTWORKS, artworks)
                put(KEY_INDEX, current - start)
                put(KEY_POSITION, player.currentPosition.coerceAtLeast(0L))
                put(KEY_EDITED, AudioQueueOwner.isUserEdited())
                put(KEY_SCOPE_KIND, scope?.kind.orEmpty())
                put(KEY_SCOPE_KEY, scope?.key.orEmpty())
            }
            prefs.audioQueueJson = json.toString()
        }.onFailure {
            DebugLog.w(TAG, "队列快照写盘失败（已忽略）: ${it.message}")
        }
    }

    /**
     * 把上次的队列摆回 [player]。
     *
     * **不做文件存在性校验**：那要对每一条做一次 IO。失效的条目留在队列里，
     * 播到它时 media3 自己报错跳过，队列面板那一行显示为文件名（查不到元信息）。
     *
     * **不自动播放**：`playWhenReady` 保持 false。开 app 自己响是重大体验事故。
     */
    fun restoreInto(context: Context, player: Player) {
        runCatching {
            val prefs = AppPreferences(context)
            val raw = prefs.audioQueueJson
            if (raw.isBlank()) return
            val json = JSONObject(raw)
            val arr = json.optJSONArray(KEY_PATHS) ?: return
            val arts = json.optJSONArray(KEY_ARTWORKS)
            // 路径与封面成对取，**不要**先 mapNotNull 出路径再按下标取封面：
            // 中间掉一条空路径两个数组就错位，整份队列的封面会集体串一格
            val entries = (0 until arr.length()).mapNotNull { i ->
                val path = arr.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                path to arts?.optString(i)?.takeIf { it.isNotBlank() }
            }
            if (entries.isEmpty()) return

            val host = prefs.effectiveHost
            val port = prefs.serverPort
            val tracks = entries.map { (path, artwork) ->
                UfiAudioTrack(
                    mediaId = path,
                    url = "http://$host:$port/api/files/stream" +
                        "?path=${URLEncoder.encode(path, "UTF-8")}",
                    artworkUrl = artwork
                )
            }
            val index = json.optInt(KEY_INDEX, 0).coerceIn(0, tracks.lastIndex)
            val position = json.optLong(KEY_POSITION, 0L).coerceAtLeast(0L)

            player.setMediaItems(tracks.map { it.toUfiMediaItem() }, index, position)
            player.prepare()
            player.playWhenReady = false

            val kind = json.optString(KEY_SCOPE_KIND)
            val key = json.optString(KEY_SCOPE_KEY)
            AudioQueueOwner.restore(
                value = AudioQueueScope.of(kind, key),
                edited = json.optBoolean(KEY_EDITED, false)
            )
            DebugLog.d(TAG, "恢复播放队列：${tracks.size} 首，index=$index")
        }.onFailure {
            DebugLog.w(TAG, "队列快照恢复失败（已忽略）: ${it.message}")
        }
    }

    private const val TAG = "AudioQueue"
}



private fun UfiAudioTrack.toUfiMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setUri(url)
    .setMediaMetadata(
        // 只给封面与"可播放"这两件事；曲名/艺术家/专辑刻意留空，让播放器用文件内的标签
        // （见 [UfiAudioTrack] 的说明：MediaItem 的字段会盖掉流内解析结果）。
        MediaMetadata.Builder()
            .setArtworkUri(artworkUrl?.toUri())
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
    )
    .build()
