package com.ufi_axis.ui.media

import android.content.ComponentName
import android.content.Intent
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
