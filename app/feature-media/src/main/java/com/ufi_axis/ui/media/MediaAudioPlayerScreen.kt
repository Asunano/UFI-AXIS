package com.ufi_axis.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.data.media.UfiAudioLyrics
import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.data.model.MediaTagsResponse
import com.ufi_axis.ui.animation.page.LocalUfiReduceMotion
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiListRowCard
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_ARTIST
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_COVER
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_NEXT
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_PREV
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_PLAY
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_SEEK
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_TITLE
import com.ufi_axis.ui.navigation.ufiSharedBounds
import com.ufi_axis.ui.navigation.ufiSharedElement
import com.ufi_axis.ui.theme.*
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.AudioQueueOwner
import com.ufi_axis.viewmodel.state.AudioQueueScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 音乐播放页（媒体中心 → 音乐 → 点某一首）。2026-09-16 第二版：对齐参考图 + 接入系统媒体控制。
 *
 * ## 版式（照参考图）
 * 顶部左对齐的曲名 / 艺术家 → 大圆角封面 → **封面下方三行歌词**（当前句亮）→ 极细进度条 →
 * 左「已播」右「总时长」→ 三个大图标 → 底部一排次要动作。点歌词区进**整屏歌词**（当前句放大、
 * 自动滚动），再点回封面。
 *
 * 与第一版的三处修正（都是用户实测反馈）：
 * 1. 封面用 `/api/media/cover`（原始内嵌封面）而不是 256px 缩略图 —— 260dp 的框喂 256px 必糊；
 * 2. 暂停时封面只缩到 [AUDIO_COVER_PAUSED_SCALE]（0.96），不是 0.88 —— 原来幅度大得像在抖；
 * 3. 右侧时间显示**总时长**，不是负的剩余时间（参考图就是总时长）。
 *
 * ## 播放器不在这一页里
 * 播放交给 [UfiAudioPlaybackService]（MediaSession 前台服务），本页只是它的一个**遥控器**
 * （[rememberUfiAudioController]）。所以：锁屏 / 通知栏 / 快捷设置里有真的媒体控制，耳机暂停键
 * 也能用，退出这一页音乐不会断。控制器连接是异步的，没连上时显示加载 —— 不画一套点不动的按钮。
 *
 * ## 底部一排只放**真有能力**的动作
 * 循环模式（关 / 列表 / 单曲）、随机、播放队列 —— 全部是播放器自带能力。参考图里的定时关闭、
 * 音效、更多没有对应能力，所以不摆图标。
 *
 * ## 破例说明：进度条是自绘的，没用 UfiSlider
 * `UfiSlider` 是"设置项里的滑块"（有标签、明显滑块、44dp 触控区），这里要的是"平时极细、
 * 按住变粗、没有滑块"。形态诉求冲突，所以在本模块内自绘（[MediaSeekBar]），颜色全部走令牌，
 * 且不放进 `app/ui` 公共组件 —— 破例范围仅限媒体中心。
 */
@Composable
fun MediaAudioPlayerScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    filePath: String,
    /**
     * 播放范围（2026-09-21）。由路由带进来。
     *
     * **空串 = 没指定范围**，不是整库：迷你条 / 标题栏挂件 / 通知栏深链接只知道"正在播这首歌"，
     * 不知道当前队列是按歌单还是专辑装的，所以它们不带这两个参数，本页收到空值时
     * **沿用现有队列**（见装队列 effect 的第一条分支）。
     * 「全部」页点歌会显式带 `scope=all`，那才是"请按整库重建队列"。
     * 语义定义见 `AudioQueueScope.of`。
     */
    scopeKind: String? = null,
    scopeKey: String? = null
) {
    val palette = LocalResolvedPalette.current
    val media = viewModel.media

    /**
     * 本次播放的作用域，以及这个范围里的**全部**曲目。
     *
     * `null` = 路由**没指定**范围（迷你条 / 标题栏挂件 / 通知栏进来的），
     * 此时队列一律沿用播放器里现有的那份，详见装队列 effect 的第一条分支。
     *
     * ## 为什么不再用 `state.tab(MEDIA_TYPE_AUDIO).items`（2026-09-21 改）
     *
     * 那是「全部」页的**第一页**（100 首）。用它当队列会同时制造三个问题：
     *  1. 从专辑/歌手/文件夹点歌，队列仍是整库 —— 「只播这个歌手」根本做不到，
     *     「下一首」跑去整库的下一首，shuffle 在整库里随机；
     *  2. 点的歌不在前 100 条内时 `indexOf` 返回 -1，旧代码 `?: 0` 兜成 0，
     *     **播的是整库第一首**（点第 300 首播第 1 首），UI 也跟着显示错的；
     *  3. 专辑超过 100 首时，第 101 首之后进不了队列，「下一首」会在第 100 首处停下。
     *
     * 现在按作用域一次性拉齐（[MediaModule.queueItemsOf]），队列 = 这个范围的全集。
     */
    val queueScope = remember(scopeKind, scopeKey) { AudioQueueScope.of(scopeKind, scopeKey) }
    var tracks by remember { mutableStateOf<List<MediaLibraryItem>>(emptyList()) }
    var scopeLoaded by remember { mutableStateOf(false) }
    /*
     * 范围没指定时仍然拉整库，但**只用于显示**（曲名/歌手/歌词/队列面板的元信息）——
     * 队列本身不动。不拉的话从迷你条进来的播放页会退化成"文件名猜歌名 + 没有歌词"。
     */
    LaunchedEffect(queueScope) {
        scopeLoaded = false
        tracks = media.queueItemsOf(queueScope ?: AudioQueueScope.ALL)
        scopeLoaded = true
    }


    val controller = rememberUfiAudioController()

    var currentIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    /*
     * 进度条的拖拽状态。与迷你条**共用同一份**（[MediaSeekState]）：
     * "拖动中 / 刚 seek 完还没回读 / 播放器回读"这三级优先在两处各写一份的那一版
     * 已经漂过一次，逻辑现在只有 MediaSeekBar.kt 里那一份。
     */
    val seekState = rememberMediaSeekState()

    var showQueue by remember { mutableStateOf(false) }

    /** 「加入歌单」面板的目标曲目。null = 面板不显示（见本文件末尾的 MediaAddToPlaylistHost）。 */
    var addTarget by remember { mutableStateOf<MediaLibraryItem?>(null) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var shuffle by remember { mutableStateOf(false) }
    var handedOff by remember { mutableStateOf(false) }

    /**
     * 播放器里**真实的**队列（mediaId = 文件路径），由装队列的 effect 回读写入。
     *
     * 队列面板必须按它渲染而不是按浏览列表 —— 见 [queueRows] 处的说明。
     * 本页是队列的唯一写入方，所以在装完之后回读一次就够，不需要监听 timeline 变化。
     */
    var queueIds by remember { mutableStateOf<List<String>>(emptyList()) }

    /**
     * 播放器给出的**当前曲目元信息**（内嵌 ID3 / FLAC 标签解析结果）。
     *
     * 曲名与歌手只能从这里来：媒体库那份记录只有文件名（`带我走-杨丞琳.flac` 这种），
     * 而 `MediaStore` 的 artist 在不少 FLAC 上是空的。播放器解容器时会读出真标签，
     * 通过 [Player.Listener.onMediaMetadataChanged] 送来 —— 这也是通知栏/锁屏显示的同一份。
     */
    var trackMeta by remember { mutableStateOf<MediaMetadata?>(null) }


    val paths = remember(tracks) { tracks.map { it.path } }

    /** 列表项 → 队列项。三处装队列（首装 / 追加 / 重装）共用，构造口径不能各写一份。 */
    val toTrack: (MediaLibraryItem) -> UfiAudioTrack = remember(media) {
        { item ->
            UfiAudioTrack(
                mediaId = item.path,
                url = media.streamUrl(item.path),
                artworkUrl = media.coverUrl(item.id)
            )
        }
    }

    /**
     * 单曲兜底队列项。
     *
     * 封面**能查到就给**：迷你条与标题栏挂件读的是队列项的 `artworkUri`（见
     * `UfiAudioNowPlayingProbe`），给 null 的那一版会让那两处只剩一个占位音符 ——
     * 而播放页自己是按 `MediaLibraryItem.id` 查封面的，所以这一屏看不出问题，
     * bug 就是这么溜过去的（2026-09-23）。
     *
     * 查不到（这首歌不在当前范围里）才只能给 null：cover 接口只认 MediaStore 的 id，
     * 光有路径拼不出 URL。
     */
    val fallbackTrack: (String) -> UfiAudioTrack = { p ->
        UfiAudioTrack(
            mediaId = p,
            url = media.streamUrl(p),
            artworkUrl = tracks.firstOrNull { it.path == p }?.let { media.coverUrl(it.id) }
        )
    }

    /*
     * 装队列。
     *
     * ## 两条规则
     * 1. **路由指定哪一首，就从 0 播哪一首**（2026-09-19）。[filePath] 在 effect key 里，
     *    每次路由换歌都重跑；跳转一律 `seekTo(index, 0L)` 而不是 `seekToDefaultPosition`。
     * 2. **同作用域才复用队列，换作用域就整体重装**（2026-09-21）。作用域记在
     *    [AudioQueueOwner]（进程内，不随页面销毁丢失）—— 从 A 目录切到 B 目录时，
     *    队列必须整体换成 B 的，否则「下一首」还会跑回 A。
     *
     * 分支：
     *  · 范围还没拉齐 → **什么都不做**。宁可等一下，也不要先装个半截队列再重装（会听到重新开始）；
     *  · **路由没带范围**（[queueScope] 为 null）→ 沿用播放器里现有的队列，只跳到这一首。
     *    迷你条 / 标题栏挂件 / 通知栏就走这条：它们只知道"正在播这首歌"，不知道队列是按
     *    歌单还是专辑装的，没有资格要求重建。少了这条分支，从歌单「播放全部」之后再从挂件
     *    点回播放页，队列会被静默重装成整库 —— 歌还是那首，但「下一首」已经跑出歌单了
     *    （2026-09-21 实测到的 bug）；
     *  · 这首歌不在范围内 → 装单曲队列。**绝不用索引 0 兜底**：那会播成别的歌（旧版的 bug）。
     *    同时清掉 owner —— 这个队列不代表任何作用域，下次进来必须重装；
     *  · 同作用域且队列内容一致 → 只跳到路由那一首；
     *  · 其余（换作用域 / 换排序 / 首次进页）→ 整体重装，从路由那一首开始。
     *
     * 最后无条件回读播放器状态：media3 的 [Player.Listener] 只在**变化**时回调，不补发当前值。
     * 少这一步，页面在"服务已经在播"的情况下会显示暂停图标、循环/随机显示关 —— 那就是假状态。
     */
    LaunchedEffect(controller, tracks, scopeLoaded, filePath, queueScope) {
        val c = controller ?: return@LaunchedEffect
        val loaded = c.ufiAudioQueueIds()
        val target = paths.indexOf(filePath)
        val sameScope = AudioQueueOwner.current() == queueScope
        when {
            !scopeLoaded -> Unit

            queueScope == null -> {
                val inQueue = loaded.indexOf(filePath)
                if (inQueue >= 0) {
                    // 队列里已经有这一首：只跳过去，**不碰 timeline、不碰 owner**
                    if (c.currentMediaItemIndex != inQueue) {
                        c.seekTo(inQueue, 0L)
                        c.play()
                    }
                } else if (loaded != listOf(filePath)) {
                    // 走到这里说明播放器里没有这首歌（进程重启后的深链接之类）。
                    // 只能装单曲 —— 没有任何范围信息可以据此重建队列。
                    c.setUfiAudioPlaylist(listOf(fallbackTrack(filePath)), 0)
                    c.playWhenReady = true
                    AudioQueueOwner.clear()
                }
            }

            /*
             * 用户手工改过队列（插入 / 移除 / 重排）、还在同一个作用域、**而且路由那一首就在
             * 这个队列里** → 只跳过去，绝不重装。
             *
             * 这一条是「虚拟播放列表」能不能存在的前提。下面 `loaded == paths` 那条判据
             * 比的是"队列内容与作用域推导出的列表逐项相等"，而：
             * · 拖拽排过序 → 顺序不同，不相等；
             * · 「下一首播放」插进来的歌**很可能根本不在这个作用域里**（从专辑 A 插一首专辑 B 的）
             *   → 永远不可能相等。
             * 少了这条分支，每次回到播放页都会全量重装一次，用户排的顺序当场消失。
             *
             * ## `loaded.contains(filePath)` 这个条件是后补的（2026-09-23 修「播放队列完全错误」）
             * 原来这里只判"同作用域 + 改过 + 队列非空"，队列里没有路由那一首时**什么都不做**，
             * 理由写的是"保留用户排的队列更重要"。那个理由错了：
             * 从列表点一首歌是**明确的指令**（"放这首"），而当时的表现是点下去毫无反应 ——
             * 播放器还在放旧队列（重启后恢复的那份可能只剩几首），页面却显示着新点的歌，
             * 「下一首」因为旧队列已到底而变灰，循环模式看起来也全乱。
             * 现在这种情况直接落到下面的重装分支：用户既然点了整库/专辑里的一首，
             * 那就按那个范围重建队列 —— 自定义顺序只在"点的是队列里的歌"时才需要保住。
             */
            sameScope && AudioQueueOwner.isUserEdited() && loaded.contains(filePath) -> {
                val inQueue = loaded.indexOf(filePath)
                if (c.currentMediaItemIndex != inQueue) {
                    c.seekTo(inQueue, 0L)
                    c.play()
                }
            }

            target < 0 -> if (loaded != listOf(filePath)) {
                c.setUfiAudioPlaylist(listOf(fallbackTrack(filePath)), 0)
                c.playWhenReady = true
                AudioQueueOwner.clear()
            }

            sameScope && loaded == paths -> if (c.currentMediaItemIndex != target) {
                c.seekTo(target, 0L)
                c.play()
            }

            else -> {
                c.setUfiAudioPlaylist(tracks.map(toTrack), target)
                c.playWhenReady = true
                AudioQueueOwner.set(queueScope)
            }
        }
        // 队列面板按这份回读渲染（见 queueRows）
        queueIds = c.ufiAudioQueueIds()
        // 「随机播放本组」的一次性请求（分组页拿不到 controller，只能在这里落地）。
        // 放在装队列之后：shuffleModeEnabled 要作用在**新队列**上，先开再换队列会被重置。
        if (AudioQueueOwner.consumeShuffleRequest()) c.shuffleModeEnabled = true
        handedOff = true
        currentIndex = c.currentMediaItemIndex
        isPlaying = c.ufiPlayIntent()
        repeatMode = c.repeatMode
        shuffle = c.shuffleModeEnabled
        trackMeta = c.mediaMetadata
    }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                currentIndex = c.currentMediaItemIndex
            }

            /*
             * 播放/暂停图标读的是**播放意图**（[Player.ufiPlayIntent]），不是 `isPlaying`。
             *
             * 2026-09-22 修「切歌时播放键抽搐」：`Player.isPlaying` 的定义是
             * `playWhenReady && playbackState == READY && 没有被抑制`。切歌那一下
             * playbackState 会掉进 **BUFFERING** 再回 READY，于是 isPlaying 短暂变 false ——
             * 图标先翻成"播放"（= 显示为已暂停）再翻回来，而音频一秒都没停。
             *
             * 所以这里既订阅 onIsPlayingChanged（缓冲结束、焦点恢复这类真实变化），
             * 也订阅 onPlayWhenReadyChanged（用户点了播放/暂停），两边都重算同一个意图值。
             */
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = c.ufiPlayIntent()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                isPlaying = c.ufiPlayIntent()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // 队列播完（STATE_ENDED）时 playWhenReady 仍是 true，只有这里能把图标收回"暂停"
                isPlaying = c.ufiPlayIntent()
            }


            override fun onRepeatModeChanged(mode: Int) {
                repeatMode = mode
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                shuffle = enabled
            }

            // 内嵌标签解析完成 / 换歌时都会回调；曲名与歌手就从这里来
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                trackMeta = mediaMetadata
            }
        }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }

    // 进度轮询 500ms：歌词高亮与进度条都不需要每帧精度
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            if (!seekState.dragging) positionMs = c.currentPosition
            durationMs = c.duration.takeIf { it > 0 } ?: 0L
            delay(500)
        }
    }

    /*
     * 当前是第几首。
     *
     * [currentIndex] 初值是 -1（"还没从播放器回读过"），这时用路由带来的那一首 —— 直接写 0
     * 会让页面在回读之前的那一帧显示**列表第一首**的曲名与封面，而用户点的是另一首。
     *
     * 2026-09-21：去掉 `coerceAtLeast(0)`。路由这首不在当前范围里时（比如从通知栏深链接
     * 进来、而范围已经换了），indexOf 是 -1，钳到 0 就会显示**范围里第一首**的曲名/封面/歌词，
     * 而实际在播的是路由那一首 —— 显示与播放不是同一首歌。`getOrNull(-1)` 回 null，
     * 下游的 `current?.path ?: filePath` 会正确降级到路由那一首。
     */
    val resolvedIndex = if (currentIndex >= 0) currentIndex else paths.indexOf(filePath)
    val current = tracks.getOrNull(resolvedIndex)

    /**
     * 系统「降低动效」闸门。本页此前完全没读它 —— 切歌动画重做时一并接上：
     * 开了就退化成极短的交叉淡入，缩放 / 模糊 / 文字递进全部不播。
     */
    val reduceMotion = LocalUfiReduceMotion.current

    /**
     * 换歌动画的触发键。用**曲目 id** 而不是整个 item：同一首歌的元信息刷新
     *（封面地址签名换了、内嵌标签解析完成）不该触发一次切歌动画。
     */
    val trackAnimKey = current?.id ?: -1L

    /**
     * 封面预取（2026-09-22 修「播到没加载过的歌时先闪一下白底」）。
     *
     * ## 为什么是"前后各 [COVER_PREFETCH_RADIUS] 首"而不是整队列
     * `/api/media/cover` 是**按需解析**的：core 每次要去读文件、把内嵌封面抽出来。
     * 而 `MediaLibraryItem` 里**没有任何"这首有没有封面"的标志**（全仓只有 web 端两个
     * 局部派生的 `hasCover`，不是接口字段）—— 也就是说预取整队列就是对每一首都发一次
     * 解析请求，**没封面的那些也照样占一次往返**。队列可以是几百首，那会把 Coil 的请求
     * 队列和 core 的文件 IO 一起灌满，反过来拖慢用户真正在看的这一张。
     *
     * 半径取 2 覆盖了"连点两三下下一首"这个最常见的路径。想改成整队列的前提是先让
     * core 在列表接口里带上 hasCover，让我们能只对真有封面的那些发请求。
     *
     * 走 `enqueue` 而不是 `execute`：只要把图放进 Coil 的内存 / 磁盘缓存就行，
     * 这里不关心结果，也不该阻塞当前组合的协程。
     */
    val coverContext = LocalPlatformContext.current
    LaunchedEffect(trackAnimKey, tracks) {
        if (resolvedIndex < 0) return@LaunchedEffect
        val loader = SingletonImageLoader.get(coverContext)
        (resolvedIndex - COVER_PREFETCH_RADIUS..resolvedIndex + COVER_PREFETCH_RADIUS)
            .filter { it != resolvedIndex }
            .mapNotNull { tracks.getOrNull(it) }
            .forEach { neighbour ->
                loader.enqueue(
                    ImageRequest.Builder(coverContext)
                        .data(media.coverUrl(neighbour.id))
                        .build()
                )
            }
    }





    /*
     * 音乐**不做播放进度记忆**（2026-09-19 去掉）。
     *
     * 之前这里会按 `MediaPlaybackProgress` 的存档 seek 回上次离开的位置，于是
     * "播 A → 播 B → 再点 A"时 A 从中段接着放 —— 点一首歌就是要听这首歌，续播是视频的
     * 需求（一集看到一半），不是音乐的。存档与续播留给视频播放页（[MediaVideoPlayerScreen]）。
     *
     * [prefs] 仍要留着：歌词与封面的接口客户端都从它拿设备凭据。
     */
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }
    val currentPath = current?.path ?: filePath


    /*
     * 歌词：走公共实现 [UfiAudioLyrics]（core 接口 → 同目录旁挂 .lrc → 内嵌 USLT/SYLT/FLAC LYRICS），
     * 与文件管理器的音频预览**共用同一份**取词与解析。
     *
     * 播放页此前只问 core 的 `/api/media/lyrics`，也就是只认旁挂文件 —— 而大量歌曲的歌词嵌在
     * 音频里，同一首歌在文件管理器能看到歌词、在播放页看不到。优先级由公共类内部决定，
     * 这里只调一次 load：页面不该知道"先问谁"。
     *
     * OkHttp 用 [UfiStreamHttpClient]（带设备签名、进程内单例），与本模块播放器同一条口径。
     */
    val lyricsHttpClient = remember(context) { UfiStreamHttpClient.get(context.applicationContext) }
    val lyricsApi = remember(prefs) { RetrofitClient.getApiService(prefs) }
    var lyrics by remember { mutableStateOf<List<UfiAudioLyrics.Line>>(emptyList()) }
    var lyricsSource by remember { mutableStateOf("") }
    LaunchedEffect(current?.id, currentPath) {
        lyrics = emptyList()
        lyricsSource = ""
        val found = UfiAudioLyrics.load(
            api = lyricsApi,
            mediaId = current?.id,
            httpClient = lyricsHttpClient,
            prefs = prefs,
            filePath = current?.path ?: currentPath
        ) ?: return@LaunchedEffect
        lyrics = found.lines
        lyricsSource = found.source
    }
    val lyricIndex = remember(lyrics, positionMs) { ufiLyricIndexAt(lyrics, positionMs) }

    // core 侧解析的标签（`/api/media/tags`）：换歌重拉一次。失败回 null，取值链自动降级。
    var tags by remember { mutableStateOf<MediaTagsResponse?>(null) }
    LaunchedEffect(current?.id) {
        val id = current?.id
        tags = null
        if (id != null && id > 0) tags = media.tagsOf(id)
    }

    /*
     * 曲名 / 歌手的取值链（2026-09-16）。
     *
     * 之前直接显示 `item.name`，于是页面上写的是 `带我走-杨丞琳.flac` —— 那是**文件名**，
     * 不是歌名。现在按可信度排：
     *  1. **core 解析的标签**（[tags]，服务端直接读文件的 ID3 / FLAC 标签）——进页面就有，
     *     不必等播放器解容器；
     *  2. **列表里带的标签**（`/api/media/list` 的 title/artist，系统扫描时解析好的）；
     *  3. **播放器解出的元信息**（[trackMeta]）——前两级都拿不到时的兜底；
     *  4. **文件名拆分**（`歌名-歌手`，见 [splitFileNameTitleArtist]）：纯属猜测，放最后。
     */
    val fallbackBase = audioFileBase(current?.name ?: filePath.substringAfterLast('/'))
    val guessed = remember(fallbackBase) { splitFileNameTitleArtist(fallbackBase) }
    val title = tags?.title?.takeIf { it.isNotBlank() }
        ?: current?.title?.takeIf { it.isNotBlank() }
        ?: trackMeta?.title?.toString()?.takeIf { it.isNotBlank() }
        ?: guessed.first
    val artist = tags?.artist?.takeIf { it.isNotBlank() }
        ?: current?.artist?.takeIf { it.isNotBlank() }
        ?: trackMeta?.artist?.toString()?.takeIf { it.isNotBlank() }
        ?: trackMeta?.albumArtist?.toString()?.takeIf { it.isNotBlank() }
        ?: guessed.second
        ?: ""

    /*
     * 队列面板的数据 —— **按播放器里真实的队列**渲染，不是按浏览列表。
     *
     * 2026-09-21 改：原来直接把 `tracks` 映射成面板行。在"队列恒等于整库第一页"的年代
     * 这没问题（两者恰好是同一份），但引入作用域后会错位：`target < 0` 那条路径装的是
     * **单曲队列**，而 `tracks` 是整个范围 —— 面板会列出上百行，点第 50 行执行
     * `seekTo(50)` 打到一个只有 1 项的 timeline 上。
     *
     * 所以面板的行数与顺序只认 [queueIds]（`ufiAudioQueueIds()` 的回读），
     * 元信息去 `tracks` 里按 path 查；查不到（单曲兜底那首不在范围内）就用当前曲目的
     * 标题/歌手兜底，至少不会出现"面板空白但音乐在放"。
     */
    val trackByPath = remember(tracks) { tracks.associateBy { it.path } }
    val queueRows = remember(queueIds, trackByPath, filePath, title, artist, durationMs) {
        val ids = queueIds.ifEmpty { listOf(filePath) }
        ids.map { id ->
            val item = trackByPath[id]
            if (item != null) {
                AudioQueueRow(
                    key = id,
                    title = audioDisplayTitle(item),
                    subtitle = listOf(
                        audioDisplayArtist(item),
                        item.album
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    duration = formatMediaDuration(item.duration_ms)
                )
            } else {
                AudioQueueRow(
                    key = id,
                    title = title,
                    subtitle = artist,
                    duration = formatMediaDuration(durationMs)
                )
            }
        }
    }

    /*
     * 「封面 / 歌词」两页（2026-09-16 第三版）。
     *
     * 上一版是"点歌词进整屏、左右划切换"，两个问题：手势要和整屏歌词里的点句跳转、
     * 纵向滚动抢事件；而且没有任何可见入口，用户不知道能划。现在改成**左右 Tab + Pager**：
     * Tab 是看得见的入口，滑动由 Pager 自己处理，与页内纵向滚动天然分工。
     *
     * 状态提到这里（而不是留在 Column 里）是因为 Tab 与 Pager 分属两个子树、都要读它。
     */
    val pagerState = rememberPagerState(initialPage = AUDIO_PAGE_COVER) { AUDIO_PAGE_COUNT }
    val pagerScope = rememberCoroutineScope()

    /*
     * 返回键只拦截**队列面板**这一层浮层。
     *
     * 歌词页不再拦：Tab 一直摆在那儿（点一下就回封面），左右划也能切，用不着再占用返回键。
     * 之前在歌词页按返回是"先回封面、再按才退出"，等于要按两次才离开播放页 —— 对着
     * 一个平级的 Tab 页做返回栈语义，只会让人以为返回失灵了。现在歌词页按返回直接退出
     * 整个播放页（走页壳的 popBackStack）。
     */
    BackHandler(enabled = showQueue) {
        showQueue = false
    }


    UfiScreenScaffold(
        title = "正在播放",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(onClick = {
                addTarget = current ?: MediaLibraryItem(
                    path = currentPath,
                    title = title,
                    artist = artist
                )
            }) {
                Icon(
                    Icons.Default.PlaylistAdd,
                    contentDescription = "加入歌单",
                    tint = palette.textSecondary
                )
            }
        },
        // 顶栏与内容同色（2026-09-16）：渐变交给页壳铺满整页（含标题栏与状态栏区域），
        // 内容区不再自己画背景 —— 否则标题栏只有 pageBg，与下面的 accent 渐变之间有一道色阶。
        backgroundBrush = Brush.verticalGradient(
            colors = listOf(palette.accent.copy(alpha = AUDIO_GRADIENT_ALPHA), palette.pageBg)
        )
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (controller == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    UfiLoadingIndicator()
                    Spacer(Modifier.height(Spacing.Medium))
                    Text(
                        "正在连接播放服务…",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                }
                return@Box
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AUDIO_PAGE_PADDING)
            ) {
                /*
                 * Tab 与 Pager 的状态在本 composable 顶层（返回键要用），这里只做编排。
                 */
                MediaPlayerTabs(
                    selected = pagerState.currentPage,
                    onSelect = { page ->
                        pagerScope.launch { pagerState.animateScrollToPage(page) }
                    }
                )


                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    pageSpacing = Spacing.Medium,
                    /*
                     * 顶部对齐，**不要** CenterVertically（2026-09-22 修「切歌时封面上下抖动」）。
                     *
                     * 封面页是一个 wrap-content 的 Column，而它的高度是**跟着曲目变的**：
                     * · 歌词预览在没歌词 / 没时间轴时整块不渲染（MediaLyrics.kt 里直接 return），
                     *   有歌词时是 86dp —— 一来一回就是 86dp 的落差；
                     * · 曲名 1 行还是 2 行差一个行高；
                     * · 歌手为空时那一行连 Spacer 一起消失。
                     *
                     * 居中对齐会把这些落差**一半分给上方**，于是封面自己跟着上下跳。
                     * 以前这事被"整块横滑"的切歌动画盖住了（位移期间看不出 13~51dp 的纵向偏移），
                     * 封面改成原地对焦落位之后就直接暴露出来。
                     *
                     * 顶部对齐之后，下方内容再怎么变都只影响它自己下面的东西，封面纹丝不动。
                     */
                    verticalAlignment = Alignment.Top
                ) { page ->
                    if (page == AUDIO_PAGE_LYRICS) {
                        MediaLyricsFullView(
                            lines = lyrics,
                            index = lyricIndex,
                            source = lyricsSource,
                            // 点某一句 = 跳到那一句（切回封面用 Tab / 左划 / 返回键）
                            onSeek = { target ->
                                controller.seekTo(target)
                                positionMs = target
                            }
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            /*
                             * 切歌过渡（2026-09-22 重做）：淡叠 + 超调落位 + 景深对焦。
                             *
                             * key 用当前曲目的 id：换歌才动，播放/暂停或进度变化不触发。
                             *
                             * **没有方向**。旧实现用 `targetState >= initialState` 比较 id 来决定
                             * 往左还是往右，那在随机播放或乱序队列下是错的（id 大小与队列顺序无关）。
                             * 现在进出都是同心缩放 + 淡入淡出，「下一首 / 上一首 / 点队列里某一首 /
                             * 自动续播」全是同一个动作，不需要也不读方向。
                             *
                             * 位移与模糊的具体参数、以及为什么超调用 keyframes 而不是 spring，
                             * 见文件末尾 AUDIO_SWITCH_* 那一组常量上方的说明。
                             */
                            /*
                             * 切歌过渡（2026-09-22 二次修正）：淡入 + 超调落位 + 景深对焦。
                             *
                             * ## 为什么封面**不再**放在 AnimatedContent 里
                             * 第一版把封面留在 AnimatedContent 里，结果两个真实 bug：
                             *
                             * 1. **切歌闪烁**。AnimatedContent 在切换期间会同时保留旧内容与新内容，
                             *    于是 `UFI_SHARED_KEY_AUDIO_COVER` 这个**常量** key 在同一帧出现两个节点
                             *    —— 正是 UfiSharedTransition.kt 记过的「同帧重复 key」禁区，
                             *    也正是曲名/歌手当初被移出 AnimatedContent 的原因。封面当时没被移出去，
                             *    是因为横滑+淡入把闪烁盖住了；换成 blur 之后就藏不住了。
                             * 2. **从迷你条点进来时封面闪一下模糊**。列表是异步拉的，`current` 从 null
                             *    变成真正那一首时 `targetState` 就从 -1L 变成真实 id —— 时间点正好压在
                             *    导航 RISE 转场中间，于是「进场模糊 + 超调」叠在共享元素的飞入上。
                             *
                             * ## 现在的做法
                             * 封面是**单个节点**，缩放与模糊由一个 [rememberAudioSwitchProgress] 驱动
                             *（与曲名/歌手同一套机制）。节点不复制 ⇒ 不可能出现重复 key；
                             * 首次组合只 snap 到终态 ⇒ 进页不播效果。
                             *
                             * 代价：旧封面没有独立的离场动画（同一个节点，图片直接换）。
                             * 这就是设计评审里 V2「只进不出」那一档 —— 同一时刻只有一个元素在做几何变化，
                             * 本来就是四套方案里观感最干净的。
                             *
                             * **没有方向**。旧实现用 `targetState >= initialState` 比较 id 来定方向，
                             * 那在随机播放或乱序队列下是错的（id 大小与队列顺序无关）。
                             */
                            run {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    val coverScale by animateFloatAsState(
                                        targetValue = if (isPlaying) 1f else AUDIO_COVER_PAUSED_SCALE,
                                        animationSpec = UfiMotion.sliderTrack(),
                                        label = "audioCoverScale"
                                    )
                                    // 0 = 刚换歌，1 = 已落位。缩放的超调与模糊都是它的纯函数，
                                    // 所以两者的时间轴天然对齐，不需要各自起一个动画去对时。
                                    val switchProgress = rememberAudioSwitchProgress(
                                        trackKey = trackAnimKey,
                                        reduceMotion = reduceMotion
                                    )
                                    val switchScale = audioSwitchScaleAt(switchProgress)
                                    val switchBlur = AUDIO_SWITCH_BLUR_IN * (1f - switchProgress)
                                    /*
                                     * 封面刻意分成两层 Box（2026-09-20）：
                                     * - 外层只有固定尺寸 + 共享元素标记，几何稳定；
                                     * - 内层才做播放/暂停的 0.96 缩放、以及切歌的缩放与模糊。
                                     *
                                     * 合成一层会打架：共享元素靠**测量出来的位置与尺寸**做补间，
                                     * 而 graphicsLayer 的 scale 是绘制期变换 —— 转场进行中
                                     * 暂停/播放一下，缩放会叠在补间的边界上，观感是封面抽一下、
                                     * 且落点与终点差一截。分层后共享元素读到的始终是 260dp 这个
                                     * 确定的框，scale 只在框内缩放内容。
                                     *
                                     * 共享元素的对端是迷你控制条那张 48dp 的封面
                                     * （同一个 [UFI_SHARED_KEY_AUDIO_COVER]）。
                                     */
                                    Box(
                                        modifier = Modifier
                                            .size(AUDIO_COVER_SIZE)
                                            .ufiSharedElement(UFI_SHARED_KEY_AUDIO_COVER)
                                    ) {

                                    Box(
                                        modifier = Modifier
                                            .size(AUDIO_COVER_SIZE)
                                            .graphicsLayer {
                                                // 两个缩放相乘而不是各占一层：播放/暂停与切歌
                                                // 可以同时发生，叠两层 graphicsLayer 只是白付一次离屏。
                                                scaleX = coverScale * switchScale
                                                scaleY = coverScale * switchScale
                                            }
                                            .shadow(18.dp, RoundedCornerShape(AUDIO_COVER_CORNER))
                                            // blur 必须在 clip **之前**：糊出来的边由
                                            // BlurredEdgeTreatment 按同一个圆角裁掉，否则会溢出圆角。
                                            // API < 31 上这一句静默无效果 —— 见常量处说明的回落形态。
                                            .blur(
                                                switchBlur,
                                                BlurredEdgeTreatment(
                                                    RoundedCornerShape(AUDIO_COVER_CORNER)
                                                )
                                            )
                                            .clip(RoundedCornerShape(AUDIO_COVER_CORNER))
                                            .background(palette.surfaceMuted),
                                        contentAlignment = Alignment.Center
                                    ) {

                                        Icon(
                                            Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = palette.textSecondary,
                                            modifier = Modifier.size(56.dp)
                                        )
                                        current?.let { item ->
                                            AsyncImage(
                                                model = ImageRequest.Builder(coverContext)
                                                    .data(media.coverUrl(item.id))
                                                    // 淡入而不是硬切：封面是异步拉的，硬切时
                                                    // 会看到底色（浅色主题下 surfaceMuted 近白）
                                                    // 先闪一下再"啪"地换成图。
                                                    .crossfade(UfiMotion.Duration.Gentle)
                                                    .build(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                // 失败仍是那个音符占位图标，但要留下原因：
                                                // 401 / 404 / 超时在界面上长得一模一样
                                                onError = { state ->
                                                    DebugLog.w(
                                                        "AudioCover",
                                                        "封面加载失败: ${media.coverUrl(item.id)}",
                                                        state.result.throwable
                                                    )
                                                },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                    }
                                }
                            }

                            /*
                             * ── 封面下方：歌名，其下歌手 ──
                             *
                             * 这两行**刻意放在切歌那个 AnimatedContent 之外**（2026-09-20）。
                             *
                             * 它们要挂共享元素（对端是迷你条那两行），而共享元素的 key 是常量：
                             * 放在 AnimatedContent 里面时，切歌那一刻旧内容与新内容会同时在树上，
                             * 同一个 key 短时间出现**两个**候选 —— 上一轮"连贯动画丢失"就是这么来的。
                             * 而这一页进来时几乎必然会切一次：列表是异步拉的，`current` 从 null
                             * 变成真正那一首时 `targetState` 就变了，时间点正好压在导航转场中间。
                             *
                             * 代价是切歌时这两行不能跟着封面一起被 AnimatedContent 搬运。
                             * 2026-09-22 补上等价效果：改为对**同一个节点**做 alpha + 4dp 上浮
                             *（[audioMetaEnterModifier]），节点不复制，所以常量 key 不会同帧出现两次。
                             * 三行按 `STAGGER_DELAY_MS` 顺延，让信息有阅读顺序。
                             * 修饰符接在 `ufiSharedBounds` **之后**：几何仍由共享元素接管，这里只动
                             * alpha 与 translationY。
                             */
                            Spacer(Modifier.height(Spacing.Large))
                            Text(
                                title,
                                style = UfiTextStyles.sectionTitle,
                                color = palette.textPrimary,
                                // minLines = maxLines：曲名 1 行还是 2 行都占同样高度。
                                // 否则下面的歌手与歌词会在换歌时上下跳一个行高 ——
                                // 那是「切歌时封面上下抖动」同一个病的下半身。
                                minLines = 2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                // scaleContent = false：文字整体缩放会把这行字在转场起点压到
                                // 三四成再长大、且放大方向发虚，理由见 ufiSharedBounds
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .ufiSharedBounds(
                                        UFI_SHARED_KEY_AUDIO_TITLE,
                                        scaleContent = false
                                    )
                                    .then(
                                        audioMetaEnterModifier(
                                            trackKey = trackAnimKey,
                                            delayMillis = UfiMotion.STAGGER_DELAY_MS.toInt(),
                                            reduceMotion = reduceMotion
                                        )
                                    )
                            )
                            // 歌手行**始终渲染**，空串也占一行高度。
                            // 用 if 包起来的那一版里，从"有歌手"切到"没歌手"会连 Spacer
                            // 一起消失，整栏高度跳一截 —— 同「封面上下抖动」一个病。
                            Spacer(Modifier.height(Spacing.Small))
                            Text(
                                artist,
                                style = UfiTextStyles.body,
                                color = palette.textSecondary,
                                minLines = 1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .ufiSharedBounds(
                                        UFI_SHARED_KEY_AUDIO_ARTIST,
                                        scaleContent = false
                                    )
                                    .then(
                                        audioMetaEnterModifier(
                                            trackKey = trackAnimKey,
                                            delayMillis =
                                                (UfiMotion.STAGGER_DELAY_MS * 2).toInt(),
                                            reduceMotion = reduceMotion
                                        )
                                    )
                            )

                            Spacer(Modifier.height(Spacing.Large))
                            Box(
                                modifier = audioMetaEnterModifier(
                                    trackKey = trackAnimKey,
                                    delayMillis = (UfiMotion.STAGGER_DELAY_MS * 3).toInt(),
                                    reduceMotion = reduceMotion
                                )
                            ) {
                                MediaLyricsPreview(
                                    lines = lyrics,
                                    index = lyricIndex,
                                    // 点三行预览 = 翻到歌词页（与 Tab 同一个去处）
                                    onClick = {
                                        pagerScope.launch {
                                            pagerState.animateScrollToPage(AUDIO_PAGE_LYRICS)
                                        }
                                    }
                                )
                            }
                        }


                    }
                }


                // ── 进度条 + 时间（左已播、右总时长）──
                //
                // 这个节点在切歌那个 AnimatedContent **之外**，所以整个转场期间它只有一份、
                // 不会被重建 —— 共享元素挂在这里是安全的（对端是迷你条那条轨道）。
                MediaSeekBar(
                    state = seekState,
                    durationMs = durationMs,
                    positionMs = positionMs,
                    onSeek = { target ->
                        controller.seekTo(target)
                        // 立刻写一份：轮询是 500ms 一次，不写的话歌词高亮会慢半拍
                        positionMs = target
                    },
                    sharedKey = UFI_SHARED_KEY_AUDIO_SEEK,
                    // 换歌时已播段平滑过去，不瞬移 —— 封面那边已经是柔和落位，
                    // 进度条再"啪"地弹回 0 就成了整屏最跳眼的东西。
                    trackKey = trackAnimKey
                )
                Spacer(Modifier.height(Spacing.Small))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        formatPlaybackTime(seekState.shownPositionMs(durationMs, positionMs)),
                        style = UfiTextStyles.monoCaption,
                        color = palette.textSecondary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatPlaybackTime(durationMs),
                        style = UfiTextStyles.monoCaption,
                        color = palette.textSecondary
                    )
                }

                Spacer(Modifier.height(Spacing.Medium))

                // ── 三键：无底色、中间最大，每个键下面写清楚它是什么（2026-09-16）──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.Center
                ) {
                    MediaControlAction(
                        icon = Icons.Default.SkipPrevious,
                        label = "上一首",
                        iconSize = 34.dp,
                        enabled = controller.ufiCanSkipToPrevious(),
                        // 2026-09-20：迷你条补上"上一首"之后这颗才有对端，于是也挂上 key。
                        // 三颗键同时参与转场，整组一起长大 / 缩回，不会只有中间两颗在动。
                        sharedKey = UFI_SHARED_KEY_AUDIO_PREV,
                        onClick = { controller.ufiSkipToPrevious() }
                    )
                    Spacer(Modifier.width(Spacing.XLarge))
                    MediaControlAction(
                        icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        label = if (isPlaying) "暂停" else "播放",
                        iconSize = 52.dp,
                        // 迷你条那颗播放/暂停键是本键的对端，转场时两者连贯长大 / 缩回
                        sharedKey = UFI_SHARED_KEY_AUDIO_PLAY,
                        onClick = { if (isPlaying) controller.pause() else controller.play() }
                    )
                    Spacer(Modifier.width(Spacing.XLarge))
                    MediaControlAction(
                        icon = Icons.Default.SkipNext,
                        label = "下一首",
                        iconSize = 34.dp,
                        enabled = controller.ufiCanSkipToNext(),
                        sharedKey = UFI_SHARED_KEY_AUDIO_NEXT,
                        onClick = { controller.ufiSkipToNext() }
                    )
                }


                Spacer(Modifier.height(Spacing.Medium))

                // ── 底部一排次要动作：循环 / 随机 / 队列（三个都是播放器自带能力，各带文字）──
                //
                // 三等分而不是 SpaceEvenly（2026-09-19）：这三个按钮的文字长度会随状态变
                // （"不循环" ↔ "列表循环"、"队列（3）" ↔ "队列（128）"），SpaceEvenly 按实际
                // 宽度分配剩余空间，任何一个字数一变、三个按钮就一起横向挪位。改成每个占
                // weight(1f) 的固定格子、内部居中，这样只有格子里的文字变，图标位置钉死不动。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        MediaControlAction(
                            icon = if (repeatMode == Player.REPEAT_MODE_ONE) {
                                Icons.Default.RepeatOne
                            } else {
                                Icons.Default.Repeat
                            },
                            label = when (repeatMode) {
                                Player.REPEAT_MODE_OFF -> "不循环"
                                Player.REPEAT_MODE_ALL -> "列表循环"
                                else -> "单曲循环"
                            },
                            iconSize = 22.dp,
                            active = repeatMode != Player.REPEAT_MODE_OFF,
                            // 下一档必须从 **controller 的现值** 推，不能从本地镜像 repeatMode 推。
                            // 镜像只在两处更新：队列效应末尾的回读、以及本页的 Listener ——
                            // 而 Listener 在本页离开组合时被移除（见下方 DisposableEffect），
                            // 镜像随即冻结。这期间用户完全可以从标题栏挂件的迷你弹窗
                            //（MediaNowPlayingChip，那边一直就是读现值）或通知栏改模式。
                            // 用冻结值推下一档的表现是"点一下没反应"或"跳过一档"。
                            onClick = {
                                controller.repeatMode = when (controller.repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                }
                            }
                        )
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        MediaControlAction(
                            icon = Icons.Default.Shuffle,
                            label = if (shuffle) "随机：开" else "随机：关",
                            iconSize = 22.dp,
                            active = shuffle,
                            // 同上：取反的基准用现值，不用可能已冻结的 shuffle 镜像。
                            onClick = {
                                controller.shuffleModeEnabled = !controller.shuffleModeEnabled
                            }
                        )
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        MediaControlAction(
                            icon = Icons.AutoMirrored.Filled.QueueMusic,
                            label = "队列（${queueRows.size}）",
                            iconSize = 22.dp,
                            onClick = { showQueue = true }
                        )
                    }
                }


                // 底部整块留白：把这一排从屏幕最底边抬起来。贴底的按钮既难点（拇指要伸到边缘）
                // 也容易撞上系统手势热区，而这一页的控件是整页最需要点得准的东西。
                Spacer(Modifier.height(AUDIO_CONTROLS_BOTTOM_LIFT))
            }

            // ── 队列：公共弹窗（[UfiCustomDialog]，标题 + 右上角 ×）──
            if (showQueue) {
                /*
                 * 队列面板里的一行提示（目前只有"已关闭随机播放"那一条）。
                 * 放在这一层而不是面板内部：它由面板的回调触发，但面板关掉再开时应该消失 ——
                 * 状态跟着 showQueue 的生命周期走最省事。
                 */
                var queueHint by remember(showQueue) { mutableStateOf<String?>(null) }

                NowPlayingQueueDialog(
                    queueRows = queueRows,
                    currentIndex = resolvedIndex,
                    hintText = queueHint,
                    onSelect = { idx ->
                        controller.seekTo(idx, 0L)
                        controller.play()
                        showQueue = false
                    },
                    onRemove = { idx ->
                        controller.ufiRemoveFromQueue(idx)
                        // 回读：removeMediaItem 之后 timeline 变了，面板要按新队列重排。
                        // media3 的 Listener 只在变化时回调，这里是本地发起的改动，
                        // 顺手同步比等回调更可靠（也避免面板闪一帧旧数据）。
                        queueIds = controller.ufiAudioQueueIds()
                        currentIndex = controller.currentMediaItemIndex
                        // 队列被清空 → 面板没有任何内容可显示，留着是个空壳
                        if (queueIds.isEmpty()) showQueue = false
                    },
                    onMove = { from, to ->
                        controller.ufiMoveInQueue(from, to)
                        queueIds = controller.ufiAudioQueueIds()
                        currentIndex = controller.currentMediaItemIndex
                        /*
                         * 排序时若随机播放开着，自动关掉它并告知（E13）。
                         *
                         * 否则用户排了半天顺序，实际播放仍按打乱的序走 —— 那是"功能看起来生效了
                         * 其实没有"，比直接禁掉排序更糟。关掉随机是符合此刻意图的解释：
                         * 他既然在手排顺序，就是想按这个顺序听。
                         */
                        if (controller.shuffleModeEnabled) {
                            controller.shuffleModeEnabled = false
                            shuffle = false
                            queueHint = "已关闭随机播放，按你排的顺序播"
                        }
                    },
                    onDismiss = { showQueue = false }
                )
            }
        }
    }

    // 「加入歌单」：正在听到一首想收的歌时，不该逼用户退回列表再长按一次。
    // 目标优先用列表里那份完整 item；单曲兜底那首不在范围内（current 为 null）时
    // 用当前路径 + 已解析出的标题拼一个最小 item —— 加歌只需要 path，标题只用于面板文案。
    MediaAddToPlaylistHost(
        media = media,
        target = addTarget,
        onDone = { addTarget = null }
    )
}

/**
 * 播放队列弹窗。
 *
 * 2026-09-19 改用公共弹窗组件 [UfiCustomDialog]：标题与右上角 × 都由它给，
 * 这里只铺内容。之前是自己用 `Dialog` 画的一整页（自绘顶栏 + 返回箭头），
 * 与全 App 其他弹窗不是一套观感，而"队列"本来就是一层浮层、不是一个页面。
 *
 * ## 2026-09-22 改版（"序号 + 律动条"）
 *
 * 四处改动，每一处都对应旧版一个具体问题：
 *
 * 1. **leading 由「透明竖条 + 图标」换成「序号槽」**。旧版在 leading 里塞了两个东西：
 *    一条 accent 竖条和一个 PlayArrow/MusicNote 图标 —— 它们表达的是**同一件事**
 *    （这是当前项）。更糟的是那条竖条在非当前项时是"透明但仍占宽度"，于是**每一行**的
 *    文字都被无谓地往右推了 3dp + 间距。现在那个槽位放序号，非当前项显示 `1 2 3…`，
 *    当前项换成 [QueuePlayingBars]。
 * 2. **补上序号**。"这是第几首、还剩几首"是队列最有用的信息之一，旧版完全读不到。
 * 3. **"共 N 首"从正文挪到标题栏右侧**，并改成 `3 / 24`。旧版它单独占一整行，
 *    用一行高度换一条低信息量文字。
 * 4. **打开时自动滚到当前项**。这是行为不是样式：队列可能上百首，旧版打开停在顶部，
 *    正在播的那首可能在第 80 行 —— 高亮做得再好也看不见。
 *
 * 列表仍带 [AUDIO_QUEUE_LIST_MAX_HEIGHT] 上限：不限高会把弹窗撑出屏幕。
 */
@Composable
private fun NowPlayingQueueDialog(
    queueRows: List<AudioQueueRow>,
    currentIndex: Int,
    hintText: String?,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val listState = rememberLazyListState()

    /*
     * 「编辑顺序」模式：开了之后每行右侧从「时长 + −」换成「↑ ↓ −」。
     *
     * ## 为什么是模式切换 + 按钮，不是长按拖拽
     * 拖拽是更好的交互，但这里的行是 `UfiListRowCard`（冻结签名的公共件，内部自带
     * `combinedClickable`）。要在它外面接 `detectDragGesturesAfterLongPress`，就得和它
     * 内部的点击手势争仲裁 —— 谁先消费长按、拖拽中要不要屏蔽 onClick、跨行边界时
     * LazyColumn 的自动滚边怎么接，每一条都只能在真机上试出来。
     * 那是一轮独立的工作，不该夹在这次改动里半做。
     *
     * ↑↓ 按钮在功能上是完备的（任意顺序都排得出来），只是慢一点。
     *
     * ## 为什么要模式而不是常显 ↑↓
     * 一行右侧本来已经有「时长 + −」，再塞两个图标要挤掉时长 —— 而时长是平时更常看的信息。
     * 排序是低频操作，值得多点一下进模式。
     */
    var reorderMode by remember { mutableStateOf(false) }


    /*
     * 打开即滚到当前项。
     *
     * 往前多留 [AUDIO_QUEUE_SCROLL_LEAD] 行而不是把当前项顶到首行：当前项贴在列表最上沿时
     * 看不出"它前面还有歌"，而队列的上下文（前一首是什么）本身是有用的信息。
     *
     * 用 `scrollToItem` 而不是 `animateScrollToItem`：弹窗刚出现就播一段长距离滚动动画，
     * 观感是"列表自己在乱跑"。直接定位到位。
     */
    LaunchedEffect(currentIndex, queueRows.size) {
        if (currentIndex in queueRows.indices) {
            listState.scrollToItem((currentIndex - AUDIO_QUEUE_SCROLL_LEAD).coerceAtLeast(0))
        }
    }

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "播放队列",
        showCloseButton = true,
        // 「第几首 / 共几首」放标题行右侧：它是这个弹窗的状态说明，不值得占正文一行
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (currentIndex in queueRows.indices) {
                        "${currentIndex + 1} / ${queueRows.size}"
                    } else {
                        "${queueRows.size} 首"
                    },
                    style = UfiTextStyles.monoCaption,
                    color = palette.textSecondary
                )
                // 队列少于两首时排序无意义，按钮也就不出现
                if (queueRows.size > 1) {
                    Spacer(Modifier.width(Spacing.Small))
                    UfiButton(
                        text = if (reorderMode) "完成" else "编辑顺序",
                        variant = UfiButtonVariant.Subtle,
                        size = UfiButtonSize.Small,
                        onClick = { reorderMode = !reorderMode }
                    )
                }
            }
        }

    ) {
        hintText?.let { hint ->
            // 一行说明，跟着列表一起滚不合适（它是对刚发生的事的解释），所以钉在列表上方
            Text(
                text = hint,
                style = UfiTextStyles.caption,
                color = palette.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.Small)
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = AUDIO_QUEUE_LIST_MAX_HEIGHT),
            // 上下各留一点：行卡自带阴影与描边，贴着裁剪边缘时第一/最后一行会被切掉一线
            contentPadding = PaddingValues(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small)
        ) {
            itemsIndexed(queueRows, key = { _, row -> row.key }) { index, row ->
                val active = index == currentIndex
                UfiListRowCard(
                    title = row.title,
                    subtitle = row.subtitle,
                    selected = active,
                    onClick = { onSelect(index) },
                    leading = {
                        // 固定宽度的槽位：序号与律动条同宽，切换时右边的文字不会左右跳
                        Box(
                            modifier = Modifier.width(AUDIO_QUEUE_INDEX_SLOT),
                            contentAlignment = Alignment.Center
                        ) {
                            if (active) {
                                QueuePlayingBars(color = palette.accent)
                            } else {
                                Text(
                                    text = "${index + 1}",
                                    style = UfiTextStyles.monoCaption,
                                    color = palette.textSecondary
                                )
                            }
                        }
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (reorderMode) {
                                // 首行没有「上移」、末行没有「下移」：摆一个点了没反应的箭头
                                // 比少一个箭头更让人困惑
                                if (index > 0) {
                                    QueueRowIconButton(
                                        icon = Icons.Default.KeyboardArrowUp,
                                        description = "「${row.title}」上移",
                                        tint = palette.textSecondary,
                                        onClick = { onMove(index, index - 1) }
                                    )
                                }
                                if (index < queueRows.lastIndex) {
                                    QueueRowIconButton(
                                        icon = Icons.Default.KeyboardArrowDown,
                                        description = "「${row.title}」下移",
                                        tint = palette.textSecondary,
                                        onClick = { onMove(index, index + 1) }
                                    )
                                }
                            } else {
                                Text(
                                    row.duration,
                                    style = UfiTextStyles.monoCaption,
                                    color = if (active) palette.accent else palette.textSecondary
                                )
                            }
                            /*
                             * 「移出队列」。**只动队列，不动文件。**
                             *
                             * 用 `−` 而不是垃圾桶：垃圾桶在这个 app 的其它地方都是"删文件"，
                             * 在队列面板里摆一个会让人以为点了就把歌删了。
                             *
                             * 索引用 LazyColumn 的 index，它与 `ufiAudioQueueIds()` 的下标
                             * 一一对应，也就是 media3 的**原始（未打乱）索引** ——
                             * `removeMediaItem` / `moveMediaItem` 要的正是这个。
                             * 随机播放开着时播放顺序与本表不同，所以本表一律按原始顺序渲染，
                             * 否则点掉的会是另一首。
                             */
                            QueueRowIconButton(
                                icon = Icons.Default.Remove,
                                description = "从队列移出「${row.title}」",
                                tint = palette.textSecondary,
                                onClick = { onRemove(index) }
                            )
                        }
                    }


                )
            }
        }
    }
}

/**
 * 队列面板里那几个小图标按钮（上移 / 下移 / 移出）。
 *
 * 尺寸比 Material 默认的 48dp 小一档：它们挤在一行卡片的右侧，48dp 会把整行撑高。
 */
@Composable
private fun QueueRowIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(AUDIO_QUEUE_REMOVE_SIZE)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 当前播放项的三根律动条。
 *
 * 只是"在播"的视觉暗示，**不是频谱** —— 它不读音频数据，就是三根按固定节奏反向来回的条。
 * 真做频谱要接 `Visualizer`，那需要录音权限，为一个 20dp 的装饰不值得。
 *
 * 三根条的峰高与起跳延迟都写死（[EQ_BARS]）：错开才像在跳，同步就成了一个整体在缩放。
 */
@Composable
private fun QueuePlayingBars(color: Color) {
    val transition = rememberInfiniteTransition(label = "queue-eq")
    Row(
        modifier = Modifier.height(AUDIO_QUEUE_EQ_HEIGHT),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        EQ_BARS.forEach { (peak, delayMs) ->
            val scale by transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = AUDIO_QUEUE_EQ_PERIOD_MS),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(delayMs)
                ),
                label = "queue-eq-bar"
            )
            Box(
                modifier = Modifier
                    .width(AUDIO_QUEUE_EQ_BAR_WIDTH)
                    .height(peak * scale)
                    .clip(RoundedCornerShape(AUDIO_QUEUE_EQ_BAR_WIDTH))
                    .background(color)
            )
        }
    }
}


// ───────────────────────── 版式常量 ─────────────────────────

/**
 * 队列面板一行要显示的东西。
 *
 * 有这一层是因为面板的数据源不总是媒体库列表：列表拉不到时播放器里是"路由那一首"的单曲队列，
 * 面板也得显示那一行。面板与播放器显示的队列必须是同一份。
 */
private data class AudioQueueRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val duration: String
)


private val AUDIO_COVER_SIZE = 260.dp
private val AUDIO_COVER_CORNER = 20.dp

/**
 * 暂停时封面缩到这个比例。
 *
 * 2026-09-16 从 0.88 收到 0.96：0.88 在 260dp 上是 31dp 的位移，暂停/播放来回按几次像在抖，
 * 而这个动作要表达的只是"停住了"这一点点状态差。
 */
private const val AUDIO_COVER_PAUSED_SCALE = 0.96f

// ── 切歌动画（2026-09-22 重做：淡叠 + 超调落位 + 景深对焦 + 时序递进）────────────
//
// ## 为什么整段推翻重写
// 旧实现是「封面横滑屏宽 1/3 + 淡入」，只有封面参与，且方向由
// `targetState >= initialState`（比较曲目 **id**）决定 —— 随机播放或乱序队列下方向是错的。
// 新方案**不需要方向**：随机播放、点队列里任意一首、自动续播都是同一个动作。
//
// ## 动作构成
// · 新封面：`1.05 → 0.996 → 1`（超调一下再落位）叠加 `blur 9dp → 0`（像镜头对焦）
// · 旧封面：缩到 0.96 + 糊到 7dp 淡出
// · 文字：4dp 上浮，曲名 / 歌手 / 歌词按 `UfiMotion.STAGGER_DELAY_MS` 顺延
//
// ## 三条约束（改之前先读）
// 1. **blur 只能加在内层、且要在 clip 之前**。封面是两层 Box：外层挂 shared element
//    且几何必须稳定，内层才做变换（见上方约 630 行的注释）。这里用
//    `BlurredEdgeTreatment(圆角)` 把糊出来的边直接裁掉，否则会溢出圆角。
//    `Modifier.blur` 在 **API < 31 上静默无效果**（不是崩）—— 那正是本方案的回落形态：
//    低版本退化成「只有超调 + 递进」，观感仍然成立。
// 2. **超调必须用 keyframes，不要用 spring**。这里是「1.05 → 0.996 → 1」三段定值，
//    keyframes 可控可测；spring 的停顿点会随起始速度变，连点下一首时每次幅度都不一样。
// 3. **文字不能进 AnimatedContent**（理由见约 700 行的注释），所以它的进场是对
//    **同一个节点**做 alpha + translationY，节点不复制。

/** 切歌动画基准时长。取现成档位而不是写裸数字（调参停在 290，最近的档位是 280）。 */
private const val AUDIO_SWITCH_MS = UfiMotion.Duration.Gentle

/**
 * 封面时长：比基准长一档，给超调那一下留回弹余量。
 * 280 × 1.15 ≈ 322，正好落在 [UfiMotion.Duration.Sweeping]（320）上。
 */
private const val AUDIO_SWITCH_COVER_MS = UfiMotion.Duration.Sweeping

/** 新封面进场的起始缩放。 */
private const val AUDIO_SWITCH_SCALE_FROM = 1.05f

/** 超调谷值：越过 1 之后回弹到这里再停在 1。差值刻意很小 —— 要的是"落位有重量"而非"弹一下"。 */
private const val AUDIO_SWITCH_SCALE_OVERSHOOT = 0.996f

/** 超调谷值出现的时间点（占整段进度的比例）。 */
private const val AUDIO_SWITCH_OVERSHOOT_AT = 0.62f

/** 新封面进场的起始模糊半径（API < 31 无效果，见上方约束 1）。 */
private val AUDIO_SWITCH_BLUR_IN = 9.dp

/** 曲名 / 歌手 / 歌词进场时的上浮距离。 */
private val AUDIO_SWITCH_META_RISE = 4.dp

/**
 * 队列面板里「移出队列」按钮的触控尺寸。比 Material 默认的 48dp 小一档：
 * 它在一行卡片的右侧、紧挨时长文字，48dp 会把那一行撑高。
 */
private val AUDIO_QUEUE_REMOVE_SIZE = 34.dp

/** 封面预取半径：当前这一首的前后各几首。
 *
 * 不做整队列的理由见调用点的说明（核心是列表接口没有 hasCover 标志，
 * 整队列预取等于对每一首都发一次「读文件抽封面」的请求）。
 */
private const val COVER_PREFETCH_RADIUS = 2


/**
 * 把 0..1 的切歌进度映射成封面缩放，形状是「[AUDIO_SWITCH_SCALE_FROM] →
 * [AUDIO_SWITCH_SCALE_OVERSHOOT] → 1」的两段折线。
 *
 * 用分段 lerp 而不是 `keyframes` spec：现在缩放与模糊共用**同一个**进度值
 *（见 [rememberAudioSwitchProgress]），进度本身已经带了缓动，这里只负责形状。
 * 好处是两者的时间轴不可能错位 —— 各起一个带独立 spec 的动画就得自己对时。
 */
private fun audioSwitchScaleAt(progress: Float): Float =
    if (progress <= AUDIO_SWITCH_OVERSHOOT_AT) {
        lerp(
            AUDIO_SWITCH_SCALE_FROM,
            AUDIO_SWITCH_SCALE_OVERSHOOT,
            (progress / AUDIO_SWITCH_OVERSHOOT_AT).coerceIn(0f, 1f)
        )
    } else {
        lerp(
            AUDIO_SWITCH_SCALE_OVERSHOOT,
            1f,
            ((progress - AUDIO_SWITCH_OVERSHOOT_AT) / (1f - AUDIO_SWITCH_OVERSHOOT_AT))
                .coerceIn(0f, 1f)
        )
    }

/**
 * 切歌进度：`0` = 刚换歌，`1` = 已落位。封面的缩放与模糊都由它派生。
 *
 * ## 为什么首次组合不播
 * 列表是异步拉的，`current` 从 null 变成真正那一首时 [trackKey] 就会变一次 ——
 * 时间点正好压在导航 RISE 转场中间。这时候播「模糊 → 对焦」，观感就是
 * **从迷你条点进来时封面闪一下模糊**（用户实测报的第 2 个 bug）。
 * 所以首次只 snap 到终态，只有**后续**的 [trackKey] 变化（= 真的换歌了）才播。
 *
 * ## 为什么 remember 不放在 reduceMotion 判断后面
 * 条件式 `remember` 是 Compose 的硬错误：`reduceMotion` 在运行期可以被用户改，
 * 一变就会让后面的 remember 槽位整体错位。所以无论如何都先 remember，
 * 分支只写在 [LaunchedEffect] 里面。
 */
@Composable
private fun rememberAudioSwitchProgress(trackKey: Long, reduceMotion: Boolean): Float {
    val progress = remember { Animatable(1f) }
    var firstPass by remember { mutableStateOf(true) }
    LaunchedEffect(trackKey, reduceMotion) {
        if (firstPass || reduceMotion) {
            firstPass = false
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = AUDIO_SWITCH_COVER_MS,
                easing = UfiMotion.Easing.EmphasizedIn
            )
        )
    }
    return progress.value
}

/**
 * 换歌时给曲名 / 歌手 / 歌词用的进场修饰符：alpha + 上浮，按 [delayMillis] 顺延。
 *
 * ## 为什么返回 Modifier 而不是包一层 Composable
 * 这三处都挂着常量 shared key 或参与页面转场，套一个 Box 会多一层布局节点、
 * 影响 `ufiSharedBounds` 的测量。返回 Modifier 让调用点接在 sharedBounds **之后**：
 * 几何仍由共享元素接管，这里只动 alpha 与 translationY。
 *
 * 首次组合不播、以及为什么 remember 不能写在 `reduceMotion` 判断后面，
 * 理由与 [rememberAudioSwitchProgress] 完全相同。
 */
@Composable
private fun audioMetaEnterModifier(
    trackKey: Long,
    delayMillis: Int,
    reduceMotion: Boolean
): Modifier {
    val progress = remember { Animatable(1f) }
    val riseTargetPx = with(LocalDensity.current) { AUDIO_SWITCH_META_RISE.toPx() }
    var firstPass by remember { mutableStateOf(true) }
    LaunchedEffect(trackKey, reduceMotion) {
        if (firstPass || reduceMotion) {
            firstPass = false
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = AUDIO_SWITCH_MS,
                delayMillis = delayMillis,
                easing = UfiMotion.Easing.EmphasizedIn
            )
        )
    }
    return Modifier.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * riseTargetPx
    }
}



/** 顶部渐变里 accent 的浓度：再高就压过封面，再低就看不出来。 */
private const val AUDIO_GRADIENT_ALPHA = 0.22f

/**
 * 页面左右内边距。
 *
 * 2026-09-16 从 `Spacing.Large`（12dp）加到 24dp：12dp 在 6 英寸屏上让封面与文字几乎贴边，
 * 底部两排控件也被挤到屏幕边缘。播放页是"只看一件事"的页面，边距该比列表页更松。
 */
private val AUDIO_PAGE_PADDING = 24.dp

/** 两页的下标与总数：0 = 封面，1 = 歌词。 */
private const val AUDIO_PAGE_COVER = 0
private const val AUDIO_PAGE_LYRICS = 1
private const val AUDIO_PAGE_COUNT = 2

/** Tab 文案与选中指示条尺寸。 */
private val AUDIO_TAB_LABELS = listOf("封面", "歌词")
private val AUDIO_TAB_INDICATOR_WIDTH = 20.dp
private val AUDIO_TAB_INDICATOR_HEIGHT = 3.dp


/**
 * 底部控件区与屏幕底边之间的留白。
 *
 * 页面壳已经扣掉了导航栏/手势条的安全区，这一段是额外的**手够得着**余量：
 * 贴着屏幕最下沿的按钮拇指要伸到边缘才点得到，且容易与系统的边缘手势打架。
 */
private val AUDIO_CONTROLS_BOTTOM_LIFT = 40.dp


/** 队列弹窗里列表的最大高度：弹窗不能被长队列撑出屏幕。 */
private val AUDIO_QUEUE_LIST_MAX_HEIGHT = 420.dp

/**
 * 队列行左侧那个槽位的宽度（序号 / 律动条共用）。
 *
 * 两者必须同宽，否则切歌时右边的歌名会左右跳一下。20dp 装得下三位数序号（"100"）
 * 与三根 3dp 的条 + 两个 2dp 间隙（共 13dp）。
 */
private val AUDIO_QUEUE_INDEX_SLOT = 20.dp

/** 律动条整体高度（三根条里最高那根的峰值）。 */
private val AUDIO_QUEUE_EQ_HEIGHT = 14.dp

/** 单根律动条的宽度。也当作圆角半径用，于是两端是半圆。 */
private val AUDIO_QUEUE_EQ_BAR_WIDTH = 3.dp

/** 律动条单程时长。来回一轮 ≈ 1.1s，比心跳略慢，不会让人觉得界面在抖。 */
private const val AUDIO_QUEUE_EQ_PERIOD_MS = 560

/**
 * 三根律动条的「峰高 → 起跳延迟」。
 *
 * 延迟错开是关键：三根同步起落就是一个整体在缩放，看不出"在跳"。
 * 峰高也不一样 —— 等高三根条像信号格而不像律动。
 */
private val EQ_BARS = listOf(
    7.dp to 0,
    14.dp to 180,
    10.dp to 360
)

/**
 * 打开队列时，当前项上方多留几行。
 *
 * 不把当前项顶到首行：贴在最上沿时看不出"它前面还有歌"，而"前一首是什么"本身是有用的上下文。
 */
private const val AUDIO_QUEUE_SCROLL_LEAD = 2


/**
 * 播控键的图标槽边长：取这一排里最大的那个图标（播放/暂停 52dp），**正方形**。
 *
 * 一排键的图标大小不同（上/下首 34dp、播放 52dp），各自直接摆 Icon 会让它们**顶边**对齐、
 * 圆心错开。统一槽尺寸、图标在槽里居中之后，三个圆心与下方文字都落在同一水平线上。
 *
 * 为什么必须是正方形而不只是固定高度：这个槽是迷你条那两颗 40×40 按钮的共享元素对端，
 * 只固定高度时槽宽会跟着图标变（34 或 52），两端长宽比不同 ⇒ 补间时图标在变形的框里跳。
 */
private val MEDIA_CONTROL_ICON_SLOT = 52.dp


/*
 * 进度条（`MediaSeekBar`）与它的拖拽状态（`MediaSeekState`）已上提到 MediaSeekBar.kt：
 * 底部迷你条要的是**同一个**进度条，留在这里就会像上一版那样长成两个控件。
 */

/**
 * 封面页 / 歌词页的 Tab 条。

 *
 * 只有两页，所以不套 M3 `TabRow`（它会带来自己的指示器动画与最小高度）：
 * 两个文字按钮 + 选中态下划线，与页壳的工具条同一种轻量做法。
 */
@Composable
private fun MediaPlayerTabs(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Small),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AUDIO_TAB_LABELS.forEachIndexed { index, label ->
            val active = index == selected
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(Spacing.Medium))
                    .clickable { onSelect(index) }
                    .padding(horizontal = Spacing.XLarge, vertical = Spacing.Medium),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    label,
                    style = if (active) UfiTextStyles.bodyLeadStrong else UfiTextStyles.body,
                    color = if (active) palette.textPrimary else palette.textSecondary
                )
                Spacer(Modifier.height(Spacing.Small))
                Box(
                    modifier = Modifier
                        .width(AUDIO_TAB_INDICATOR_WIDTH)
                        .height(AUDIO_TAB_INDICATOR_HEIGHT)
                        .clip(RoundedCornerShape(AUDIO_TAB_INDICATOR_HEIGHT))
                        .background(if (active) palette.accent else Color.Transparent)
                )
            }
        }
    }
}

/**
 * 播放控制里的一个动作：图标 + **下方文字说明**。
 *
 * 2026-09-16 加文字：纯图标对"循环 / 随机"这类三态开关尤其不友好 —— 图标只能表达
 * "有这个功能"，表达不了"现在是哪一档"。文字与图标一起变（"列表循环" / "单曲循环" / "不循环"）。
 *
 * @param active 高亮态（循环/随机开启时用 accent），与 [enabled]（能不能点）是两件事。
 * @param sharedKey 跨页面共享元素的 key（见 [ufiSharedBounds]）；`null` = 这颗键在迷你条上
 *                  没有对端，不参与转场。主控三键（上一首 / 播放暂停 / 下一首）都传值，
 *                  下面那排次要动作（循环 / 随机 / 队列）迷你条上没有，所以都是 null。
 */
@Composable
private fun MediaControlAction(
    icon: ImageVector,
    label: String,
    iconSize: Dp,
    enabled: Boolean = true,
    active: Boolean = false,
    sharedKey: String? = null,
    onClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val tint = when {
        !enabled -> palette.textSecondary.copy(alpha = 0.4f)
        active -> palette.accent
        else -> palette.textPrimary
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Spacing.Medium))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        /*
         * 图标放进一个**固定尺寸的正方形槽**里居中，而不是直接摆 Icon。
         *
         * 2026-09-20 修①：这一排三个键的图标不一样大（上/下首 34dp、播放 52dp），
         * 外层 Row 又是 `Alignment.Top` —— 于是三个图标的**顶边**对齐，圆心差了 9dp，
         * 看着就是播放键偏下、文字也跟着错行。统一槽高之后圆心与下方文字都在同一水平线上。
         *
         * 2026-09-20 修②：槽从 `height()` 改成 `size()`。只约束高度时宽度是 wrap content
         * （= 图标宽度），于是这个槽的长宽比随图标大小变（34×52 / 52×52）；而迷你条那端
         * 是 40×40 的正方形，共享元素补间时两端长宽比不同，图标就在一个被拉扁/拉长的框里跳。
         * 定成正方形之后两端只差"尺寸"、不差"比例"，缩放才是单纯的变大变小。
         *
         * 共享元素也挂在这个槽上，而不是整个 Column：Column 里还有文字，
         * 把文字一起补间会让"暂停"两个字从迷你条那颗光秃秃的按钮里凭空长出来。
         * 槽的尺寸是常量、与图标大小无关，是这一列里几何最稳的那个节点。
         *
         * 用 ufiSharedBounds 而不是 ufiSharedElement：两端尺寸差 12dp 且内容是**不会**
         * 随约束自适应的图标，必须整体缩放而不是重新测量，理由见该函数的注释。
         */
        val sharedSlot = if (sharedKey != null) {
            Modifier.ufiSharedBounds(sharedKey)
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .size(MEDIA_CONTROL_ICON_SLOT)
                .then(sharedSlot),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(iconSize)
            )
        }
        Spacer(Modifier.height(Spacing.Small))
        Text(
            label,
            style = UfiTextStyles.caption,
            color = if (enabled) palette.textSecondary else palette.textSecondary.copy(alpha = 0.4f),
            maxLines = 1
        )
    }
}


/**
 * 播放时间文案。与 [formatMediaDuration] 的区别：这里 0 要显示 `0:00`（进度条两端总有数字），
 * 那个在列表里 0 就该什么都不显示。
 */
private fun formatPlaybackTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/*
 * 「文件名 → 歌名 / 歌手」的拆分已上提到 MediaListCommon.kt（`splitFileNameTitleArtist`）：
 * 列表、播放页、队列面板、迷你条都要用同一份口径，留在这里就会变成第二份。
 */


