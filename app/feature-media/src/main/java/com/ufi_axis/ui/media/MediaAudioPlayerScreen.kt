package com.ufi_axis.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.navigation.NavHostController
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
import kotlin.math.abs

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
    scopeKey: String? = null,
    /**
     * 顶栏返回键的动作（2026-10-14 新增，默认 null = 走页壳默认的 `popBackStack`）。
     *
     * 音乐页的「上滑跟手面板」把这一页直接装进页内浮层（见 MediaAudioRisePanel.kt），
     * 那时它**不是**一个导航目的地，按返回该关面板而不是把音乐页弹掉，所以由宿主传进来。
     * 系统返回键不经过这里，由宿主自己加 `BackHandler` —— 这是页壳 KDoc 里记着的分工。
     */
    onBack: (() -> Unit)? = null,
    /**
     * 是否挂共享元素（2026-10-14 新增，默认 true）。
     *
     * 页内浮层那条路径传 `false`：面板与迷你条同处一棵树，同一个 key 在同一帧出现两次
     * 正是 `UfiSharedTransition.kt` 记下的禁区；而且那条路径本来就是"整块面板升起"，
     * 没有跨页面可言。走导航的那条路径一个字节都没变。
     */
    shareElements: Boolean = true,
    /**
     * 封面是否"跟着手指从迷你条飞过来"（2026-10-14 新增，默认 false）。
     *
     * 只有页内浮层那条路径传 true（见 [MediaAudioRisePanel]）：共享元素那套在页内用不了
     * （同一棵树里同一个 key 不能出现两次，而且它的进度由导航转场驱动、不跟手指），
     * 所以封面改由覆盖层里那张"飞行封面"顶着（[UfiRiseFlyingCover]），这一页只报出槽位
     * 并在飞行期间把自己藏起来（见 [ufiRiseCoverSlot]）。与 [shareElements] 互斥。
     */
    riseFollow: Boolean = false
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


    /*
     * 控制器：**优先复用 App 壳里那个常驻探针已经连好的那一份**（`UfiNowPlayingState`）。
     *
     * 2026-10-20（修「刚上滑时画面突然变一下」）：页内面板是"第一下拖动才进组合"的。
     * 这一页如果在这里再开一条 `MediaController` 连接，面板的头几帧只能是"正在连接播放服务…"
     * 那个加载态，等 binder 回来才换成真内容 —— 用户看到的正是"上滑刚起步、画面突然变一下"。
     * 探针在 `MainActivity` 顶层一直连着（迷你条之所以能显示，就是因为它已经在播），
     * 直接复用就能**第一帧就有内容**：封面槽、歌词、队列都不必再等一次往返。
     *
     * 只有探针还没连上（App 极早期 / 探针被销毁）时才自己连，行为与从前一致。
     * 生命周期不变：这里只加/摘监听，**不释放**共享的那一份。
     */
    val sharedController = UfiNowPlayingState.controller.value
    val ownController = if (sharedController == null) rememberUfiAudioController() else null
    val controller = sharedController ?: ownController

    var currentIndex by remember { mutableIntStateOf(-1) }
    /*
     * 初值直接问一次控制器（2026-10-20）。
     *
     * 原来恒从 false 起步：播放态下第一帧会按"暂停"画封面（0.96 缩放），等队列 effect
     * 回读完才长回 1.0 —— 时间点正好压在「上滑完成、真封面接手」那一刻，看起来就是
     * "封面到位之后又缩/弹一下"。现在第一帧就是真实播放态，暂停态也直接是 0.96。
     */
    var isPlaying by remember { mutableStateOf(controller?.ufiPlayIntent() == true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    /*
     * 进度条的拖拽状态。与迷你条**共用同一份**（[MediaSeekState]）：
     * "拖动中 / 刚 seek 完还没回读 / 播放器回读"这三级优先在两处各写一份的那一版
     * 已经漂过一次，逻辑现在只有 MediaSeekBar.kt 里那一份。
     */
    val seekState = rememberMediaSeekState()

    var showQueue by remember { mutableStateOf(false) }
    /** 「定时关闭」设置弹窗（2026-10-14）。定时本身的状态在进程级单例里，见 MediaSleepTimer.kt。 */
    var showSleepTimer by remember { mutableStateOf(false) }

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
    /** 路径 → 列表项。`current`（按 timeline 的 mediaId 查）与封面预取都用它。 */
    val trackByPath = remember(tracks) { tracks.associateBy { it.path } }

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
    /*
     * 2026-10-07 修「排序后播放与显示彻底错乱」：
     *
     * 原来这里直接 `tracks.getOrNull(resolvedIndex)` —— 把**播放器 timeline 的下标**当
     * **浏览列表的下标**用。两者只有在"队列刚按作用域原样装入"时才恰好相等；用户在队列
     * 面板 ↑↓ 排序之后（或「下一首播放」插过歌），timeline 的顺序就与 `tracks` 分道扬镳，
     * 而 `currentIndex` 仍随播放推进在 timeline 里走 —— 于是 `current` 取出来是另一首歌：
     * 曲名、封面、歌词、标签全都显示成错的，看起来就是"播放的歌曲与显示的不一致"、
     * "点列表的歌播了别的歌"（点歌本身播对了，是这页顶部显示错了）。
     *
     * 现在以 **timeline 为权威**：当前项 = `queueIds[currentIndex]`（mediaId = 路径），
     * 元信息去 `trackByPath` 按路径查。顺序怎么排都错不了；查不到（单曲兜底那首不在
     * 浏览范围内）降级到路由那一首，行为与旧版的"查不到显示文件名"一致。
     */
    val playingPath = queueIds.getOrNull(currentIndex) ?: filePath
    val current = trackByPath[playingPath]

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
     * ## 为什么是"前后各 [UfiAudioCoverPrefetch.QUEUE_WINDOW] 首"而不是整队列
     * `/api/media/cover` 是**按需解析**的：core 每次要去读文件、把内嵌封面抽出来。
     * 而 `MediaLibraryItem` 里**没有任何"这首有没有封面"的标志**（全仓只有 web 端两个
     * 局部派生的 `hasCover`，不是接口字段）—— 也就是说预取整队列就是对每一首都发一次
     * 解析请求，**没封面的那些也照样占一次往返**。队列可以是几百首，那会把 Coil 的请求
     * 队列和 core 的文件 IO 一起灌满，反过来拖慢用户真正在看的这一张。
     *
     * 窗口取 3（2026-10-21 由 2 提到 3）覆盖"连点几下下一首"与自动续播；再大就要先让
     * core 在列表接口里带上 hasCover，才能只对真有封面的那些发请求。
     *
     * 具体的请求构造（尺寸、内存缓存键）收在 [UfiAudioCoverPrefetch] 里 —— 它和列表首屏
     * 那条预取是同一套口径，散在两边写迟早走样。
     */
    val coverContext = LocalPlatformContext.current
    val coverDensity = LocalDensity.current
    /**
     * 封面框的像素边长。
     *
     * 预取与显示**必须**用同一个值：内存缓存键里带着它（见 [audioCoverMemoryCacheKey]），
     * 差 1px 就是两份位图、两次解码，预取也就不算命中了。
     */
    val coverPx = with(coverDensity) { AUDIO_COVER_SIZE.roundToPx() }
    LaunchedEffect(playingPath, tracks) {
        // 2026-10-07：预取邻居改为**按 timeline 顺序**找（与 current 的修法同一口径）。
        // 原来按 tracks 下标取邻居，排序后取到的是"显示顺序的邻居"而不是"接下来要播的"。
        val pos = queueIds.indexOf(playingPath)
        if (pos < 0) return@LaunchedEffect
        val window = UfiAudioCoverPrefetch.QUEUE_WINDOW
        val urls = (pos - window..pos + window)
            .filter { it != pos }
            .mapNotNull { trackByPath[queueIds.getOrNull(it)] }
            .map { media.coverUrl(it.id) }
        UfiAudioCoverPrefetch.playerSize(coverContext, urls, coverPx)
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
     * 封面 URL：列表还没拉到时**先用探针那一条**（2026-10-20）。
     *
     * `current` 来自异步拉的 `tracks`（打开一次面板就是一次拉取），所以这一页的头几帧
     * `current` 是 null —— 此前封面那张 AsyncImage 整块不组合，框里只剩 `surfaceMuted`
     * （浅色主题近白），等图加载完再淡入。用户逐次开关播放页看到的就是「封面变白再重新加载」。
     *
     * `UfiNowPlayingState.artworkUrl` 由常驻探针维护、**打开第一帧就有值**，而且队列项里
     * 的 artworkUri 与 [media.coverUrl] 是同一个 URL（见 `toUfiMediaItem`），所以它到
     * `current` 解析出来时不会换地址、也就不会重新加载。
     *
     * 只在"探针那一首就是本页要放的那一首"时才用（[playingPath] 相等）：点列表另一首进播放页时
     * 两首不是同一首，用探针那张会先闪一下**上一首**的封面 —— 那种情况退回原来的"等列表"。
     *
     * 2026-10-21：比的对象从路由带来的 [filePath] 改成 [playingPath]。原来的写法只在"页刚打开
     * 且就是那一首"时成立 —— 页内切到下一首之后 `filePath` 永远是打开时那一首，这条兜底
     * 当场失效；而队列里查不到那首（单曲兜底 / 刚"下一首播放"插进来）时 `current` 也是 null，
     * 于是 `coverUrl` 变成 null、封面那张 AsyncImage 整块不组合 ⇒ 又一段近白底。
     */
    val probeArtwork = UfiNowPlayingState.artworkUrl.value
    val probeMediaId = UfiNowPlayingState.mediaId.value
    val coverUrl = current?.let { media.coverUrl(it.id) }
        ?: probeArtwork.takeIf { probeMediaId.isNotBlank() && probeMediaId == playingPath }

    /**
     * 上一张**真的画出来了**的封面 URL（2026-10-21，`onSuccess` 时更新）。
     *
     * 换歌时把它当占位图传给新请求（见下面的 `placeholderMemoryCacheKey`）：Coil 会**同步**
     * 从内存缓存里取出这一张来画，于是换歌第一帧画出来的还是旧封面，等新的解出来再接上。
     * 用户报的"切到没放过的歌会闪一下白屏再出图"就是这一帧没东西可画的空档。
     */
    var shownCoverUrl by remember { mutableStateOf<String?>(null) }

    /*
     * 封面请求**记住**（2026-10-20 六修）。
     *
     * 这一页里很多状态都会触发重组（分页切换、歌词、进度……），而请求对象原来是在组合里
     * 逐次 `ImageRequest.Builder(...).build()` 出来的新实例 —— 对 Coil 来说"模型变了"，
     * 于是重新走一遍加载（哪怕内存缓存命中，也会有一帧空白/重新淡入）。
     * 左右划"封面/歌词"时这一页会重组一次，用户看到的就是"手动拖拽时封面抽搐"。
     * 按 `coverUrl` 记住之后，同一首歌从头到尾都是同一个请求对象。
     *
     * ★ 2026-10-21 加了两样：显式**内存缓存键**（与预取侧共用 [audioCoverMemoryCacheKey]，
     *   换歌才能是真正的内存命中）与**上一张封面作占位**（兜住缓存没命中的那几帧）。
     *   `coverPx` 用的是上面预取那段算出来的同一个值。
     */
    val coverImageRequest = remember(coverUrl, coverPx) {
        coverUrl?.let { url ->
            ImageRequest.Builder(coverContext)
                .data(url)
                // 尺寸写死成封面框的 px。AsyncImage 按约束也会算出同一个值，写出来是为了
                // 让预取与显示落在**同一个**缓存键上（键里带尺寸，见 audioCoverMemoryCacheKey）。
                .size(coverPx, coverPx)
                .memoryCacheKey(audioCoverMemoryCacheKey(url, coverPx))
                // 换歌那一帧先画上一张（同步命中内存缓存），新图到位后由 Coil 直接换掉，
                // 中间不留空帧。`shownCoverUrl` 故意不进 key：它变化只说明"这张已经画出来了"，
                // 不该让请求对象重建、从而把刚加载好的图再走一遍。
                .apply {
                    shownCoverUrl?.let {
                        placeholderMemoryCacheKey(audioCoverMemoryCacheKey(it, coverPx))
                    }
                }
                // 换歌时从上一张封面淡过来（见 AUDIO_COVER_CROSSFADE_MS）。
                // 没有旧封面可淡时即为"从背景淡入"，同样比硬弹出来柔和。
                .crossfade(AUDIO_COVER_CROSSFADE_MS)
                .build()
        }
    }


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
    /*
     * 歌词状态**跨曲目暂留**（2026-10-21）。
     *
     * 原来 `remember(currentPath)` + "换歌先清空"：切歌那一刻 lyrics 变空 → 歌词块淡出，
     * 几十~几百毫秒后新歌词到位 → 再淡入。用户看到的其实是**两段动画中间空一下**
     * （"有词 ⇄ 无词不够优雅"真正的来源）。
     *
     * 现在换歌**不清空**：旧歌词先顶着，等新的一份（或"确认这首没词"的结论）到位再一次性替换。
     * 于是"有词 → 有词"根本不需要淡出，只是内容换了一版；只有真的切到没词的歌才会走淡出。
     */
    var lyrics by remember {
        mutableStateOf(UfiAudioLyrics.cached(currentPath)?.lines.orEmpty())
    }
    var lyricsSource by remember {
        mutableStateOf(UfiAudioLyrics.cached(currentPath)?.source.orEmpty())
    }
    LaunchedEffect(current?.id, currentPath) {
        // 查过就直接用（含"确认这首歌没有歌词"）：不清状态、不重拉
        val cached = UfiAudioLyrics.cached(currentPath)
        if (cached != null) {
            lyrics = cached.lines
            lyricsSource = cached.source
            return@LaunchedEffect
        }
        // 取词结束（`null` = 各级都没找到，`UfiAudioLyrics` 会把"确实没有"一起记进缓存）。
        // ★ 这里**必须**清空：否则上一首的歌词会一直挂在屏幕上 —— 用户报的
        //   "切到无歌词的歌之后歌词还在"就是这个 `?: return` 留下的。
        //   "暂留"只负责**加载期间**不闪，答案来了就要如实反映。
        val found = UfiAudioLyrics.load(
            api = lyricsApi,
            mediaId = current?.id,
            httpClient = lyricsHttpClient,
            prefs = prefs,
            filePath = current?.path ?: currentPath
        )
        lyrics = found?.lines.orEmpty()
        lyricsSource = found?.source.orEmpty()
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
    val queueRows = remember(queueIds, trackByPath, filePath, title, artist, durationMs) {
        val ids = queueIds.ifEmpty { listOf(filePath) }
        // key 必须唯一：LazyColumn 的 itemsIndexed 用它做移动动画与复用。
        // 「下一首播放」允许把同一首歌插多次（没有去重），裸用路径做 key 会撞
        // "Key was already used" 直接崩；给重复项追加出现序号。
        val seen = HashMap<String, Int>()
        ids.map { id ->
            val n = seen.merge(id, 1, Int::plus) ?: 1
            val rowKey = if (n == 1) id else "$id#$n"
            val item = trackByPath[id]
            if (item != null) {
                AudioQueueRow(
                    key = rowKey,
                    title = audioDisplayTitle(item),
                    subtitle = listOf(
                        audioDisplayArtist(item),
                        item.album
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    duration = formatMediaDuration(item.duration_ms)
                )
            } else {
                AudioQueueRow(
                    key = rowKey,
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
        // 页内浮层那条路径要自己接管返回（关面板而不是弹掉音乐页），见 [onBack]
        onBack = onBack,
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
                    // 2026-10-20（用户要求）：有歌词时「封面 / 歌词」这一块整体偏下，
                    // 整块上移 12dp（两页一起动，Tab 与页面对齐关系不变）。
                    .offset(y = (-12).dp)
            ) {
                /*
                 * Tab 与 Pager 的状态在本 composable 顶层（返回键要用），这里只做编排。
                 *
                 * ★ 2026-10-20 六修：`selected` 传的是**取值 lambda**，不是 `pagerState.currentPage`
                 *   的直接读值。直接读会把"当前页"这条 State 的读发生在**本页组合作用域**里 ——
                 *   手指左右划、`currentPage` 在临界点来回翻的时候，整页（含 Pager 里的封面）
                 *   会跟着一次次重组；而重组会重建封面请求、重建 Pager 内容，观感就是
                 *   "手动拖拽时封面抽搐"。改成在 [MediaPlayerTabs] 内部取值之后，翻页只会
                 *   重组那一排 Tab（很轻），封面一个字节都不动。
                 */
                MediaPlayerTabs(
                    selected = { pagerState.currentPage },
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
                     * 关掉这个 Pager 自己的**边界回弹**（2026-10-20 七修）。
                     *
                     * 用户报「只在拖到两端（页边）时封面位置在抖」。两端唯一多出来的东西
                     * 就是 overscroll：Android 12+ 的拉伸回弹会给整块内容逐帧套一个
                     * `RenderEffect`，而封面自己是一摞图层（缩放 / 阴影 / 圆角裁剪 / 切歌模糊）——
                     * 拉伸效果套在这摞图层上时，边缘会跟着逐帧重采样，观感就是"封面在抖"。
                     *
                     * 这个 Pager 只有「封面 / 歌词」两页，位置反馈由上面那排 Tab 负责，
                     * 边界弹一下并不是必需的信息。所以这里直接把这个 Pager 的 overscroll
                     * 置空（只影响横向翻页；歌词页内部那个纵向列表的 overscroll 不受影响，
                     * 它自己走 `rememberOverscrollEffect()`）。
                     */
                    overscrollEffect = null,
                    /*
                     * 这个对齐现在**管不到任何东西**（2026-10-14）：两页都是 fillMaxSize ——
                     * 封面页自己占满整页并在内部居中，歌词页（MediaLyrics.kt）也是整页。
                     * Pager 的 verticalAlignment 只对"比页面矮"的内容有意义，保留 Top
                     * 只是不去依赖 Pager 那个默认值。
                     *
                     * 2026-09-22 这里曾是修「切歌时封面上下抖动」的关键：当时封面页是一个
                     * wrap-content 的 Column，高度跟着曲目变（歌词块有无差 86dp、曲名 1 行还是
                     * 2 行差一个行高、歌手为空时那一行连 Spacer 一起消失），居中会把高度差的
                     * **一半分给上方**，于是封面自己跟着上下跳。
                     * 那条取舍现在由封面页自己承担：它居中的那一组高度是定值（曲名
                     * minLines = maxLines = 2、歌手行恒渲染），理由见该页开头那段。
                     */
                    verticalAlignment = Alignment.Top
                ) { page ->
                    if (page == AUDIO_PAGE_LYRICS) {
                        MediaLyricsFullView(
                            lines = lyrics,
                            index = lyricIndex,
                            source = lyricsSource,
                            // 翻页过渡：与封面页共用同一套（见 [ufiPageSwitchEffect]）
                            modifier = Modifier.ufiPageSwitchEffect(pagerState, page),
                            // 点某一句 = 跳到那一句（切回封面用 Tab / 左划 / 返回键）
                            onSeek = { target ->
                                controller.seekTo(target)
                                positionMs = target
                            }
                        )
                    } else {
                        /*
                         * 封面页（2026-10-14 重排）：**「封面 + 歌名 + 歌手」这一组永远居中，
                         * 歌词区不参与这个居中**。
                         *
                         * 用户报的问题：没有歌词时歌词块已经不渲染了，可这一页是 wrap-content
                         * 的 Column、靠 Pager 顶部对齐 —— 内容变短之后整块贴在顶上，下面空出
                         * 一大片，观感从"歌词区是空的"变成了"整页偏上"。
                         *
                         * 修法**不是**把 Pager 的对齐换成 CenterVertically：那样歌词会算进
                         * 被居中的那一块，有歌词时这一组被顶上去半个歌词块 —— 换一首有无歌词的歌
                         * 封面就上下跳一次，正是 2026-09-22 修掉的那个病。所以这里让 Column 占满
                         * 整页并居中，再把歌词从"参与居中的高度"里摘出去（做法见下面歌词那一段）。
                         *
                         * 敢居中的前提是这一组的高度是**定值**：封面 260dp、歌名
                         * minLines = maxLines = 2、歌手行恒渲染 —— 三条都是 2026-09-22 那次
                         * 修「封面上下抖动」留下的约束，这里正是它们的回报。
                         */
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                // 翻页过渡：与歌词页共用同一套（见 [ufiPageSwitchEffect]）
                                .ufiPageSwitchEffect(pagerState, page),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            // 见 [AudioCoverPageArrangement]：组居中、歌词挂在组下方，两者互不牵动
                            verticalArrangement = AUDIO_COVER_PAGE_ARRANGEMENT
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
                                     * 阴影的显示系数（2026-10-20）：0 = 没阴影，1 = [AUDIO_COVER_SHADOW]。
                                     *
                                     * 页内浮层那条路径上，封面是"从飞行封面手里接过来"的：原来
                                     * `.shadow(18.dp)` 是静态的，接手那一刻阴影一刀切地出现 —— 用户
                                     * 报的就是"会突然出现阴影"。现在跟着打开/收起补一条 280ms 的淡入淡出。
                                     *
                                     * 导航那条路（riseFollow=false）没有这个接手过程，系数恒为 1，
                                     * 与改动前逐像素一致。
                                     */
                                    val coverShadowFactor = rememberCoverShadowFactor(riseFollow)
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
                                            .ufiSharedElementIf(shareElements, UFI_SHARED_KEY_AUDIO_COVER)
                                            // 页内浮层那条路径：真封面让位给覆盖层那张飞行封面
                                            // （隐藏挂在**外层**：内层的暂停/切歌缩放不该参与）
                                            .ufiRiseCoverHiddenIf(riseFollow, coverUrl)
                                    ) {

                                    Box(
                                        modifier = Modifier
                                            .size(AUDIO_COVER_SIZE)
                                            .graphicsLayer {
                                                // 两个缩放相乘而不是各占一层：播放/暂停与切歌
                                                // 可以同时发生，叠两层 graphicsLayer 只是白付一次离屏。
                                                scaleX = coverScale * switchScale
                                                scaleY = coverScale * switchScale
                                                /*
                                                 * 缩放 / 阴影 / 圆角裁剪合成**同一层**（2026-10-20 七修）。
                                                 *
                                                 * 原来这里是四层嵌套：scale 图层 + `.shadow()` 图层
                                                 * + `.blur()`（它看到 edgeTreatment.shape 就 clip = true，
                                                 * 哪怕半径是 0 也照样加一层）+ `.clip()` 图层。
                                                 * 分页拖拽时，这一摞图层要在**亚像素位置**逐帧重新栅格化，
                                                 * 用户看到的是"封面位置在抖"—— 其实不是布局在动，是
                                                 * 多层裁剪/阴影重采样的边缘在逐帧跳。
                                                 *
                                                 * 合成一层之后滚动时只有一次缩放 + 一次圆角裁剪：
                                                 * · 阴影用图层 elevation（panel 路径需要它逐帧淡入，
                                                 *   导航路径系数恒为 1，与原来 `.shadow(18.dp)` 等价）；
                                                 * · clip = true 负责圆角裁剪，原来的 `.clip()` 不再需要。
                                                 */
                                                shadowElevation =
                                                    AUDIO_COVER_SHADOW.toPx() * coverShadowFactor.value
                                                shape = AUDIO_COVER_SHAPE
                                                clip = true
                                            }
                                            // 落点挂在**缩放这一层之内**（2026-10-20）：飞行封面要
                                            // 落到"真实封面画出来的那个矩形"，暂停态的 0.96 也得算进去，
                                            // 否则交给真封面那一下会缩 4%。排在 graphicsLayer 之后，
                                            // 量到的 bounds 才会带上这个缩放。
                                            .ufiRiseCoverSlotReportIf(riseFollow, pagerState, AUDIO_PAGE_COVER)
                                            // 模糊只在"切歌那一段"真的需要时才挂（2026-10-20 七修）：
                                            // 静止时 switchBlur 是 0，而 `Modifier.blur` 只要带圆角
                                            // edgeTreatment 就仍然会加一层裁剪图层 —— 白付一次离屏，
                                            // 还要在滚动时跟着重新栅格化。
                                            .then(
                                                if (switchBlur > 0.dp) {
                                                    Modifier.blur(
                                                        switchBlur,
                                                        BlurredEdgeTreatment(AUDIO_COVER_SHAPE)
                                                    )
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .background(palette.surfaceMuted),
                                        contentAlignment = Alignment.Center
                                    ) {

                                        Icon(
                                            Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = palette.textSecondary,
                                            modifier = Modifier.size(56.dp)
                                        )
                                        coverImageRequest?.let { request ->
                                            AsyncImage(
                                                model = request,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                /*
                                                 * 图真的画出来了才允许交接（2026-10-20）：
                                                 * 面板那侧只有在收到这个信号之后才把飞行封面撤掉
                                                 * （见 [UfiAudioRisePanel.isPlayerCoverReady]），
                                                 * 否则归位那一刻会露出这块 `surfaceMuted` 近白底。
                                                 */
                                                onSuccess = {
                                                    // 这一张画出来了：下一首换图时拿它当占位，
                                                    // 换歌那一帧因此不会露出这块近白底（见所示变量）
                                                    shownCoverUrl = coverUrl
                                                    if (riseFollow) {
                                                        coverUrl?.let {
                                                            UfiAudioRisePanel.markPlayerCoverReady(it)
                                                        }
                                                    }
                                                },
                                                // 失败仍是那个音符占位图标，但要留下原因：
                                                // 401 / 404 / 超时在界面上长得一模一样
                                                onError = { state ->
                                                    DebugLog.w(
                                                        "AudioCover",
                                                        "封面加载失败: $coverUrl",
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
                            /*
                             * 曲名：固定**两行高**的框，文字在其中垂直居中（2026-10-14）。
                             *
                             * 用户报"歌名与歌手之间隔得太远，砍掉一半"。原因不在下面那 4dp 间距，
                             * 而在这一条：曲名框按两行留（为的是换歌时高度不跳），而绝大多数曲名
                             * 只有一行 —— 空出来的整整一行就落在文字与歌手之间。
                             *
                             * 现在让文字在两行框里居中：这段空白一半挪到文字上方、一半留在下方，
                             * 歌名 → 歌手的距离正好减半；**框高仍然是两行**，所以整组高度不随曲名
                             * 长短变，换歌时封面不会上下跳（`minLines = 2` 当初就是为这个加的）。
                             */
                            val titleLineHeight = with(LocalDensity.current) {
                                UfiTextStyles.sectionTitle.lineHeight.toDp()
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(titleLineHeight * 2),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    title,
                                    style = UfiTextStyles.sectionTitle,
                                    color = palette.textPrimary,
                                    // 框高已经由上面那条 height 定死，这里不再用 minLines 撑高：
                                    // 文字占几行由内容决定，多出来的空白由 Box 居中分配
                                    minLines = 1,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    // scaleContent = false：文字整体缩放会把这行字在转场起点压到
                                    // 三四成再长大、且放大方向发虚，理由见 ufiSharedBounds
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .ufiSharedBoundsIf(
                                            shareElements,
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
                            }
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
                                    .ufiSharedBoundsIf(
                                        shareElements,
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

                            /*
                             * 歌词预览（2026-10-14 重排）。
                             *
                             * ## 它怎么做到"不影响组的居中，又紧贴在组下面"
                             * 由父 Column 的 [AudioCoverPageArrangement] 负责：那一条排布规则
                             * 把**第一个孩子**（也就是上面那一组）放在整页正中，把第二个孩子
                             * 放在它的正下方 —— 歌词不参与"居中"这个计算，所以有歌词时组不会被
                             * 顶上去，换一首有无歌词的歌封面也不会跳。
                             *
                             * 这里**不再**自己垫 `Spacer(Spacing.Large)`：间距是排布规则的一部分
                             * （它得知道自己排的是哪两个东西），垫在子树里会让"组 + 间距 + 歌词"
                             * 变成一个整体，居中又要重新算一遍。
                             *
                             * ## 判据
                             * 与 MediaLyricsPreview 内部那条守卫**逐字一致**（空行 / 无时间轴都算
                             * "没有"），否则版式会与实际渲染的内容对不上 —— 那正是上一次修错位时
                             * 定下的规矩。代价仍有：有歌词 ⇄ 无歌词 的歌之间切歌时，歌词块会整块
                             * 出现 / 消失；但它挂在组的**下方**，不会带动封面位移，所以不会复发
                             * "封面上下抖动"。
                             */
                            val hasLyrics = lyrics.isNotEmpty() && lyrics.first().timeMs >= 0L
                            /*
                             * 有歌词 ⇄ 无歌词的切歌（2026-10-21）。
                             *
                             * 原来是 `if (hasLyrics) { … }` 硬挂载/卸载：从有词切到无词时，
                             * 那三行预览**整块消失**，观感是"画面忽然少了一块"。现在走
                             * [AnimatedVisibility] 的淡入淡出（末段略上移 4dp）。
                             *
                             * 为什么用 `fadeIn/fadeOut` 而不是 `expandVertically/shrinkVertically`：
                             * 前面那条 [AudioCoverPageArrangement] 是按"歌词块占多高"来摆组的 ——
                             * 让高度一起补间，组会在整段动画里被一点点顶上去（正是 2026-09-22
                             * 修掉的"换歌封面上下跳"）。只动 alpha，高度就是两档定值，组不挪。
                             */
                            AnimatedVisibility(
                                visible = hasLyrics,
                                enter = fadeIn(tween(UfiMotion.Duration.Standard)) +
                                    slideInVertically(tween(UfiMotion.Duration.Standard)) { -it / 6 },
                                exit = fadeOut(tween(UfiMotion.Duration.Standard)) +
                                    slideOutVertically(tween(UfiMotion.Duration.Standard)) { -it / 6 }
                            ) {
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
                    sharedKey = UFI_SHARED_KEY_AUDIO_SEEK.takeIf { shareElements },
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
                        sharedKey = UFI_SHARED_KEY_AUDIO_PREV.takeIf { shareElements },
                        onClick = { controller.ufiSkipToPrevious() }
                    )
                    Spacer(Modifier.width(Spacing.XLarge))
                    MediaControlAction(
                        icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        label = if (isPlaying) "暂停" else "播放",
                        iconSize = 52.dp,
                        // 迷你条那颗播放/暂停键是本键的对端，转场时两者连贯长大 / 缩回
                        sharedKey = UFI_SHARED_KEY_AUDIO_PLAY.takeIf { shareElements },
                        onClick = { if (isPlaying) controller.pause() else controller.play() }
                    )
                    Spacer(Modifier.width(Spacing.XLarge))
                    MediaControlAction(
                        icon = Icons.Default.SkipNext,
                        label = "下一首",
                        iconSize = 34.dp,
                        enabled = controller.ufiCanSkipToNext(),
                        sharedKey = UFI_SHARED_KEY_AUDIO_NEXT.takeIf { shareElements },
                        onClick = { controller.ufiSkipToNext() }
                    )
                }


                Spacer(Modifier.height(Spacing.Medium))

                // ── 底部一排次要动作：模式 / 定时 / 队列（三个都是播放器自带能力，各带文字）──
                //
                // 三等分而不是 SpaceEvenly（2026-09-19）：这三个按钮的文字长度会随状态变
                // （"不循环" ↔ "列表循环"、"队列（3）" ↔ "队列（128）"），SpaceEvenly 按实际
                // 宽度分配剩余空间，任何一个字数一变、三个按钮就一起横向挪位。改成每个占
                // weight(1f) 的固定格子、内部居中，这样只有格子里的文字变，图标位置钉死不动。
                //
                // 2026-10-14：原来是 循环 / 随机 / 队列。用户要求把"随机"从独立开关收进
                // **一个四档模式**（列表循环 / 单曲循环 / 不循环 / 随机模式），空出来的那一格
                // 给"定时关闭"（见 MediaSleepTimer.kt）。
                val playMode = AudioPlayMode.of(repeatMode, shuffle)
                val sleepTimerLabel = rememberSleepTimerLabel()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        MediaControlAction(
                            icon = when (playMode) {
                                AudioPlayMode.SingleLoop -> Icons.Default.RepeatOne
                                AudioPlayMode.Shuffle -> Icons.Default.Shuffle
                                else -> Icons.Default.Repeat
                            },
                            label = playMode.label,
                            iconSize = 22.dp,
                            active = playMode != AudioPlayMode.NoLoop,
                            /*
                             * 点一下切到下一档。**基准用 controller 的现值推**，不用上面那个
                             * `playMode`（它读的是本地镜像）：镜像在本页离开组合时会冻结
                             *（Listener 被移除），而用户完全可以从标题栏挂件的迷你弹窗或
                             * 通知栏改模式 —— 用冻结值推的表现是"点一下没反应"或"跳过一档"。
                             * 显示用镜像（它才是响应式的），推进用现值，两者各司其职。
                             */
                            onClick = {
                                val current = AudioPlayMode.of(
                                    repeatMode = controller.repeatMode,
                                    shuffle = controller.shuffleModeEnabled
                                )
                                applyPlayMode(controller, current.next())
                            }
                        )
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        MediaControlAction(
                            icon = Icons.Default.Timer,
                            label = sleepTimerLabel,
                            iconSize = 22.dp,
                            active = UfiSleepTimer.isActive,
                            onClick = { showSleepTimer = true }
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
            //
            // 2026-10-14（修「队列里点一首歌，弹窗的关闭动画丢失」）：
            // 这里**不再**用 `if (showQueue)` 把整棵子树摘掉，改成常驻组合 + 只翻 `visible`。
            // 弹窗的离场由两件事负责 —— 平台窗口动画（`UfiDialogAnimation`）与 shell 的
            // mounted-state ramp —— 两者都只在"窗口还在、`visible` 翻 false"时才跑得起来。
            // 摘子树等于当场销毁窗口：动画一帧都没播，观感就是"点一下，弹窗瞬间没了"。
            /*
             * 队列面板里的一行提示（目前只有"已关闭随机播放"那一条）。
             * 放在这一层而不是面板内部：它由面板的回调触发，但面板**每次打开**都该是干净的。
             * 原来靠 `remember(showQueue)` 在关闭时重置；现在子树常驻，改成"打开那一刻清掉" ——
             * 这样离场动画期间那一行还在，不会在弹窗消失到一半时先闪掉。
             */
            var queueHint by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(showQueue) { if (showQueue) queueHint = null }

            NowPlayingQueueDialog(
                visible = showQueue,
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

            /*
             * 定时关闭的设置弹窗（2026-10-14）：与队列弹窗同一套壳，同样**常驻组合 + 只翻
             * `visible`** —— 摘子树会把离场动画一起摘掉，这条在队列弹窗那边刚踩过。
             */
            UfiSleepTimerDialog(
                visible = showSleepTimer,
                onDismiss = { showSleepTimer = false }
            )
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
 *    当前项换成 [UfiPlayingBars]。
 * 2. **补上序号**。"这是第几首、还剩几首"是队列最有用的信息之一，旧版完全读不到。
 * 3. **"共 N 首"从正文挪到标题栏右侧**，并改成 `3 / 24`。旧版它单独占一整行，
 *    用一行高度换一条低信息量文字。
 * 4. **打开时自动滚到当前项**。这是行为不是样式：队列可能上百首，旧版打开停在顶部，
 *    正在播的那首可能在第 80 行 —— 高亮做得再好也看不见。
 *
 * 列表仍带 [AUDIO_QUEUE_LIST_MAX_HEIGHT] 上限：不限高会把弹窗撑出屏幕。
 */
@Composable
internal fun NowPlayingQueueDialog(
    /**
     * 弹窗是否可见（2026-10-14 修「点歌后关闭动画丢失」）。
     *
     * 这个参数是必须的：弹窗的离场动画由 [UfiDialogShell] 的 mounted-state 模式负责
     * （先跑离场 ramp，跑完才真正卸载窗口），而它**只在 `visible` 翻 false 时才启动**。
     * 之前这里硬写 `visible = true`、由调用点用 `if (showQueue)` 把整棵子树摘掉 ——
     * 子树一摘，ramp 根本没有机会跑，于是"点一首歌 → 弹窗瞬间消失"。
     * 现在调用点常驻组合这棵子树、只翻 `visible`，关闭那条路（点外部 / 返回键 / ×）
     * 也照样有动画。
     */
    visible: Boolean,
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
    // 每次打开都回到"不编辑顺序"（2026-10-14）：这棵子树现在常驻组合（见 [visible] 的说明），
    // 不重置的话上次退出时的编辑态会跨次保留 —— 而 `if (showQueue)` 那个版本天然没这问题。
    LaunchedEffect(visible) { if (visible) reorderMode = false }


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
        visible = visible,
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
                                UfiPlayingBars(color = palette.accent)
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
 * 「这一首正在播」的三根律动条（2026-10-21 由 `QueuePlayingBars` 改名并 internal 化）。
 *
 * 只是"在播"的视觉暗示，**不是频谱** —— 它不读音频数据，就是三根按固定节奏反向来回的条。
 * 真做频谱要接 `Visualizer`，那需要录音权限，为一个 20dp 的装饰不值得。
 *
 * 三根条的峰高与起跳延迟都写死（[EQ_BARS]）：错开才像在跳，同步就成了一个整体在缩放。
 *
 * 两处共用：队列弹窗的行首，以及音乐列表（[MediaAudioList]）封面上那层角标。共用同一个
 * 高度与条宽，两处才不会出现"弹窗里是 14dp、列表里变成 10dp"的错位。
 *
 * @param playing 暂停时画成**静止**的三根条：仍然一眼看出"这是当前这首"，但不假装还在跳。
 *   实现上刻意用 `state.value`（而不是 `by` 委托）读动画值 —— 暂停分支不读就不会订阅，
 *   无限动画也就不再逐帧请求下一帧。
 */
@Composable
internal fun UfiPlayingBars(color: Color, playing: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "eq-bars")
    Row(
        modifier = Modifier.height(AUDIO_QUEUE_EQ_HEIGHT),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        EQ_BARS.forEachIndexed { index, bar ->
            val (peak, delayMs) = bar
            val animated = transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = AUDIO_QUEUE_EQ_PERIOD_MS),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(delayMs)
                ),
                label = "eq-bar"
            )
            val scale = if (playing) {
                animated.value
            } else {
                // 暂停：中间那根略高，读起来像被"按停"的波形，而不是三根一样长的柱子
                if (index == EQ_BARS.size / 2) 0.8f else 0.45f
            }
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
internal data class AudioQueueRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val duration: String
)


// internal 而不是 private（2026-10-14）：覆盖层里那张"飞行封面"要用同一套尺寸与圆角，
// 否则它和播放页这张在交接那一刻会有肉眼可见的错位/圆角差。
internal val AUDIO_COVER_SIZE = 260.dp
internal val AUDIO_COVER_CORNER = 20.dp

/**
 * 播放页那张封面在 Coil **内存缓存**里的键（2026-10-21）。
 *
 * ## 为什么要自己拼一个
 * Coil 的默认键里含它内部解析出来的请求选项，**预取**（`enqueue`，无 target）与**显示**
 * （`AsyncImage`，带约束）两条路径各自 build 出来的请求很难保证逐字节相同 —— 于是预取
 * 白做：显示那一刻仍然是"解一帧空白"。自己拼一个只含「尺寸 + URL」的键之后，两边必然对上。
 *
 * 尺寸必须进键：迷你条那 48dp、飞行封面那 260dp 会解出不同像素的位图，共用一个键的话
 * 小图会顶掉大图（封面被放大成一团糊）。
 */
internal fun audioCoverMemoryCacheKey(url: String, px: Int): String = "audio-cover@${px}px:$url"

/** 封面的圆角形状。**常量化**：图层参数比较的是引用，逐帧新建会被当成"形状变了"。 */
private val AUDIO_COVER_SHAPE = RoundedCornerShape(AUDIO_COVER_CORNER)

/**
 * 封面阴影的满档高度。见 [rememberCoverShadowFactor]：页内浮层那条路会在打开/收起时
 * 沿 [UfiMotion.Duration.Gentle] 淡入淡出，导航那条路恒为满档（与原来的 `.shadow(18.dp)` 等价）。
 * internal：覆盖层里那张飞行封面要按同一个值长阴影（见 `UfiRiseFlyingCover`）。
 */
internal val AUDIO_COVER_SHADOW = 18.dp

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
private val AUDIO_SWITCH_BLUR_IN = 14.dp

/**
 * 换封面时的交叉淡入淡出时长（2026-10-21）。
 *
 * 用户报「切歌那一下封面是硬换的，过渡不流畅」。原来请求里写的是 `crossfade(false)` ——
 * 新图解好就直接替换像素。现在交给 Coil 的 `CrossfadeTransition`：它从**上一张**
 * （也就是 `placeholderMemoryCacheKey` 给的那张旧封面）淡到新的，中间不会露出近白底。
 *
 * 260ms 比切歌动画本身（[AUDIO_SWITCH_COVER_MS] = 320ms）略短，于是"图像换完"发生在
 * "缩放/模糊落位"之前一点点 —— 观感上是先对上焦、再站稳，而不是两件事同时结束。
 * 冷启动后第一次加载（还没有旧封面可淡）时，这一段就是"从背景淡入"，也不突兀。
 */
private const val AUDIO_COVER_CROSSFADE_MS = 260

/** 曲名 / 歌手 / 歌词进场时的上浮距离。 */
private val AUDIO_SWITCH_META_RISE = 4.dp

/**
 * 队列面板里「移出队列」按钮的触控尺寸。比 Material 默认的 48dp 小一档：
 * 它在一行卡片的右侧、紧挨时长文字，48dp 会把那一行撑高。
 */
private val AUDIO_QUEUE_REMOVE_SIZE = 34.dp


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
 * 封面阴影的显示系数（2026-10-20）：`0` = 没有阴影，`1` = 满档 [AUDIO_COVER_SHADOW]。
 *
 * 只对页内浮层那条路径（[riseFollow]）做淡入淡出：封面是从飞行封面手里接过来的，
 * 静态阴影会在接手那一帧"一刀切"出现（用户报的"阴影突然出现"）。
 * 导航那条路没有这个时刻，恒为 1 —— 与原来写死的 `.shadow(18.dp)` 逐像素一致。
 *
 * 返回 `State` 而不是 `Float`：调用点只把它交给 `graphicsLayer {}`，绘制期读值就够，
 * **不会**让整块封面（含 AsyncImage）每帧重组。
 */
@Composable
private fun rememberCoverShadowFactor(riseFollow: Boolean): State<Float> {
    /*
     * ★ 2026-10-20 六修：`UfiAudioRisePanel.open` **不能在组合里读**。
     *
     * 它恰好是在"按下返回（close）"那一帧翻转的状态。在组合期读它 = 那一下让**整页**重组；
     * 而这一帧正是关闭动画起步的一帧 —— 用户报的「按下返回的瞬间抖一下」最可能就出在这里。
     * 现在改成 snapshotFlow 在 effect 里观察：组合期一个 State 都不读，翻转只推动这个
     * Animatable（它只在 `graphicsLayer` 里被读到，属于绘制期），页面不重组。
     */
    val factor = remember { Animatable(1f) }
    if (!riseFollow) return remember { mutableStateOf(1f) }
    LaunchedEffect(Unit) {
        var first = true
        snapshotFlow { UfiAudioRisePanel.open }.collect { open ->
            val target = if (open) 1f else 0f
            if (first) {
                // 第一帧直接采纳当前态（此时面板还没提交，阴影应当为 0）
                factor.snapTo(target)
                first = false
            } else {
                factor.animateTo(
                    targetValue = target,
                    animationSpec = tween(
                        durationMillis = UfiMotion.Duration.Gentle,
                        easing = UfiMotion.Easing.Standard
                    )
                )
            }
        }
    }
    return factor.asState()
}

/**
 * 切歌进度：`0` = 刚换歌，`1` = 已落位。封面的缩放与模糊都由它派生。
 *
 * ## 为什么"首次组合"和"从没有曲目到有曲目"都不播
 * 列表是异步拉的：这一页刚组合时 `current` 还是 null（trackKey = -1），几帧后才变成真正那一首。
 * 只对"首次组合"做 snap 是不够的 —— -1 → 真 id 同样会触发一次播放，而时间点正好压在
 * 打开动画（导航 RISE 或页内上滑）的后半段。落位那一刻真封面接手时它还在 1.05 → 1.0 的
 * 收尾里，用户看到的就是「封面到位后又缩一下」（2026-10-20 报的就是这条）。
 *
 * 所以判据改成"上一次的键是否存在且是真实曲目"：`-1` 只表示"还没拿到曲目"，
 * 从它到真曲目是**初始化**，不是换歌。
 *
 * ## 为什么 remember 不放在 reduceMotion 判断后面
 * 条件式 `remember` 是 Compose 的硬错误：`reduceMotion` 在运行期可以被用户改，
 * 一变就会让后面的 remember 槽位整体错位。所以无论如何都先 remember，
 * 分支只写在 [LaunchedEffect] 里面。
 */
@Composable
private fun rememberAudioSwitchProgress(trackKey: Long, reduceMotion: Boolean): Float {
    val progress = remember { Animatable(1f) }
    /** 上一次的键；null = 还没跑过。都放在 State 里，分支只在 effect 里读。 */
    var lastKey by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(trackKey, reduceMotion) {
        val previous = lastKey
        lastKey = trackKey
        val isInit = previous == null || previous <= 0L || trackKey <= 0L
        if (isInit || reduceMotion) {
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
 * 首次组合、以及"从没有曲目到有曲目"都不播（理由与 [rememberAudioSwitchProgress] 完全相同），
 * 以及为什么 remember 不能写在 `reduceMotion` 判断后面。
 */
@Composable
private fun audioMetaEnterModifier(
    trackKey: Long,
    delayMillis: Int,
    reduceMotion: Boolean
): Modifier {
    val progress = remember { Animatable(1f) }
    val riseTargetPx = with(LocalDensity.current) { AUDIO_SWITCH_META_RISE.toPx() }
    var lastKey by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(trackKey, reduceMotion) {
        val previous = lastKey
        lastKey = trackKey
        val isInit = previous == null || previous <= 0L || trackKey <= 0L
        if (isInit || reduceMotion) {
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

/*
 * ── 共享元素的开关版（2026-10-14）────────────────────────────────────────────
 *
 * 本页有两条呈现路径：导航目的地（`Routes.MEDIA_AUDIO`）与音乐页的页内浮层
 * （[MediaAudioRisePanel]，见那个文件的说明）。后者与迷你条同处一棵树，同一个 key
 * 同帧出现两次是 `UfiSharedTransition.kt` 里记下的禁区，所以那条路径整页不挂共享元素。
 *
 * 写成 `xxxIf` 而不是在调用点铺 `if`：调用点有五处（封面 / 歌名 / 歌手 / 进度条 / 三颗键），
 * 每处都写一遍条件既啰嗦又容易漏 —— 漏一处的表现是"面板打开时某个节点突然开始参与匹配"，
 * 属于最难查的那类。这里一处开关，五个调用点跟着走。
 */
@Composable
private fun Modifier.ufiSharedElementIf(enabled: Boolean, key: String): Modifier =
    if (enabled) ufiSharedElement(key) else this

/** 见 [ufiSharedElementIf]。文字类要传 `scaleContent = false`，理由见 `ufiSharedBounds`。 */
@Composable
private fun Modifier.ufiSharedBoundsIf(
    enabled: Boolean,
    key: String,
    scaleContent: Boolean
): Modifier = if (enabled) ufiSharedBounds(key, scaleContent) else this

/**
 * 页内浮层那条路径的封面槽**上报**（2026-10-20 拆成两个开关）。
 *
 * 为什么拆：上报要挂在**带缩放的那一层**（暂停 0.96 / 切歌缩放都要算进飞行封面的落点），
 * 而"藏起真封面"要挂在**外层**（缩放不该影响让位/透明度）。合成一个 modifier 时两者只能
 * 选同一层，于是要么落点不含缩放（交接缩 4%）、要么让位也跟着缩放。
 *
 * 2026-10-20 五修：把 `pagerState` 传进去，只在封面页**停稳**时上报 —— 详见
 * `ufiRiseCoverSlotReport` 的说明（分页滑动会把这一块的窗口坐标带着左右跑）。
 */
@Composable
private fun Modifier.ufiRiseCoverSlotReportIf(
    enabled: Boolean,
    pagerState: PagerState,
    coverPage: Int
): Modifier =
    if (enabled) ufiRiseCoverSlotReport(pagerState, coverPage) else this

/** 页内浮层那条路径：真封面在升起期间让位（见 [ufiRiseCoverSlotReportIf]）。 */
@Composable
private fun Modifier.ufiRiseCoverHiddenIf(enabled: Boolean, coverUrl: String?): Modifier =
    if (enabled) ufiRiseCoverHidden(coverUrl) else this



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

/**
 * 翻页效果的三个幅度（2026-10-21）。
 *
 * 离场那一页：透明度 → 1 − 0.55、缩放 → 0.94、上移 18dp。三样都刻意取小：
 * 目的是让两页"分层"，不是做成卡片轮播 —— 幅度一大，封面在划到一半时就会明显跳一下。
 */
private const val AUDIO_PAGE_SWITCH_FADE = 0.55f
private const val AUDIO_PAGE_SWITCH_SHRINK = 0.06f
private val AUDIO_PAGE_SWITCH_RISE = 18.dp

/**
 * 封面 ⇄ 歌词翻页时，两页各自的进场 / 退场效果。
 *
 * 读 `pagerState` 的位置**只在绘制期**（`graphicsLayer` 的块每帧末执行），所以：
 * · 手指划到哪儿效果就跟到哪儿，不需要另起一条动画去对时；
 * · 不会因为读 State 把整页（尤其封面）重组 —— 与 Tab 那条 `selected = { }` 同一个理由。
 *
 * `alpha` 留了 45% 的底而不是淡到 0：两页的底色是同一张 accent 渐变，淡到 0 会在
 * 交界处露出后面的空白，看着像"抽搐一下"。
 */
private fun Modifier.ufiPageSwitchEffect(state: PagerState, page: Int): Modifier =
    this.graphicsLayer {
        // 0 = 正停在中间，1 = 完全离场（手指划到一半时正是 0..1 之间的连续值）
        val offset = abs((state.currentPage - page) + state.currentPageOffsetFraction)
            .coerceIn(0f, 1f)
        alpha = 1f - offset * AUDIO_PAGE_SWITCH_FADE
        val scale = 1f - offset * AUDIO_PAGE_SWITCH_SHRINK
        scaleX = scale
        scaleY = scale
        translationY = -offset * AUDIO_PAGE_SWITCH_RISE.toPx()
    }

/**
 * 封面页的纵向排布（2026-10-14）：**「封面 + 歌名 + 歌手」这一组居中，歌词挂在它的正下方**。
 *
 * ## 为什么不直接用 `Arrangement.Center`
 * 用 Center 的话，歌词会被算进"要居中的那一整块"里：有歌词时整块变高、组被顶上去半个
 * 歌词块，于是换一首有无歌词的歌，封面就上下跳一次 —— 正是 2026-09-22 修掉的那个病。
 * 用户要的是"没有歌词时忽略歌词区域、重新居中"，反过来也必须是同一句话：
 * **歌词区永远不参与这一组的居中**。所以这里自己写一条排布规则。
 *
 * ## 这一页的孩子不是一个，是五个（2026-10-14 修 "歌名跑到封面上方"）
 * 第一版把 `sizes[0]` 当成"那一组"、`sizes[1]` 当成歌词，只给这两个下标写了位置 ——
 * 而真实的 Column 里那一组是**五个并列的孩子**：封面 / 间距 / 歌名 / 间距 / 歌手
 *（`Arrangement.arrange` 只拿到每个孩子的尺寸，不知道谁包着谁）。
 * 于是第 3 个孩子往后全落在 `outPositions` 的默认值 0 上 —— 表现就是**歌名与歌手被顶到
 * 页面最上方、压在封面之上**，有歌词时歌词又正好落在它们原来的位置上，看起来像重叠。
 * 现在按 [groupChildCount] 逐个排下去，最后一个孩子（如果有）才是歌词。
 *
 * ## 放不下的时候
 * 组的居中优先；只有当"组 + 间距 + 歌词"超出可用高度时，才把组上移**恰好够放下歌词**
 * （不会越过顶边）—— 小屏上宁可少一点留白，也不要把歌词裁掉。
 * 这一段只在屏幕真的放不下时才生效，那时封面位移的幅度也远小于"整块居中"的跳法。
 *
 * @param groupChildCount 参与居中的孩子个数（封面 / 间距 / 歌名 / 间距 / 歌手 = 5）。
 *   剩下的那个孩子是歌词：它不参与居中，被摆到组的正下方。调用点必须与实际的孩子数一致，
 *   多一个少一个都会让歌词被当成组的一部分（或反过来）。
 */
private class AudioCoverPageArrangement(private val groupChildCount: Int) : Arrangement.Vertical {
    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        if (sizes.isEmpty()) return
        val groupCount = groupChildCount.coerceIn(0, sizes.size)
        var groupHeight = 0
        for (i in 0 until groupCount) groupHeight += sizes[i]
        val lyricsHeight = if (groupCount < sizes.size) sizes[groupCount] else 0
        val gap = if (lyricsHeight > 0) Spacing.Large.roundToPx() else 0
        val centeredTop = ((totalSize - groupHeight) / 2).coerceAtLeast(0)
        val top = if (lyricsHeight > 0) {
            val maxTopForLyrics =
                (totalSize - groupHeight - gap - lyricsHeight).coerceAtLeast(0)
            minOf(centeredTop, maxTopForLyrics)
        } else {
            centeredTop
        }
        // 组内按顺序挨着排（间距本来就在孩子序列里，是独立的 Spacer）
        var y = top
        for (i in 0 until groupCount) {
            outPositions[i] = y
            y += sizes[i]
        }
        if (lyricsHeight > 0) outPositions[groupCount] = top + groupHeight + gap
    }
}

/**
 * 封面页里"参与居中的那一组"由几个孩子组成：封面 / 间距 / 歌名 / 间距 / 歌手。
 *
 * ⚠ 改这一页的版式时（比如给歌名下面再加一行专辑名）**必须同步改这个数** ——
 * 排布规则靠"前 N 个是组、第 N+1 个是歌词"来分工，数字对不上就会出现
 * 2026-10-14 那个 bug 的翻版：多出来的孩子被当成歌词摆到组的下方去。
 */
private const val AUDIO_COVER_PAGE_GROUP_CHILDREN = 5

/** 封面页的排布规则实例（无状态，共用一个即可）。 */
private val AUDIO_COVER_PAGE_ARRANGEMENT =
    AudioCoverPageArrangement(AUDIO_COVER_PAGE_GROUP_CHILDREN)

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
    /*
     * 取值 lambda 而不是 Int（2026-10-20 六修）：见调用点那段说明 —— 把
     * `pagerState.currentPage` 的读作用域收进本函数，翻页时才不会连累整页（尤其封面）重组。
     */
    selected: () -> Int,
    onSelect: (Int) -> Unit
) {
    val palette = LocalResolvedPalette.current
    val selectedIndex = selected()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Small),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AUDIO_TAB_LABELS.forEachIndexed { index, label ->
            val active = index == selectedIndex
            /*
             * 切页的过渡（2026-10-21）：选中态原来是**硬切**（文字样式、颜色、下划线底一次跳完），
             * 配合下面 Pager 的翻页动画会显得"两件事各做各的"。现在三样各自补间：
             * · 下划线宽度 0 → 满档（像从文字中间长出来）；
             * · 下划线颜色 透明 → accent；
             * · 文字颜色 secondary → primary。
             * 时长统一走 [UfiMotion.Duration.Standard]，与 Pager 那一侧同一节奏。
             */
            val indicatorWidth by animateDpAsState(
                targetValue = if (active) AUDIO_TAB_INDICATOR_WIDTH else 0.dp,
                animationSpec = tween(UfiMotion.Duration.Standard),
                label = "tabIndicatorWidth"
            )
            val indicatorColor by animateColorAsState(
                targetValue = if (active) palette.accent else Color.Transparent,
                animationSpec = tween(UfiMotion.Duration.Standard),
                label = "tabIndicatorColor"
            )
            val labelColor by animateColorAsState(
                targetValue = if (active) palette.textPrimary else palette.textSecondary,
                animationSpec = tween(UfiMotion.Duration.Standard),
                label = "tabLabelColor"
            )
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
                    color = labelColor
                )
                Spacer(Modifier.height(Spacing.Small))
                Box(
                    modifier = Modifier
                        .width(indicatorWidth)
                        .height(AUDIO_TAB_INDICATOR_HEIGHT)
                        .clip(RoundedCornerShape(AUDIO_TAB_INDICATOR_HEIGHT))
                        .background(indicatorColor)
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
/**
 * 播放模式（2026-10-14）：把原来的"循环"与"随机"两个开关收成**一个四档模式**。
 *
 * ## 为什么合成一档
 * 那两个开关本来是正交的（可以"随机 + 单曲循环"），但那个组合在用户眼里几乎无法理解 ——
 * 单曲循环下随机其实不生效，而界面上两颗键都亮着。用户要的是"我现在处于哪种播放方式"，
 * 所以收成一个循环切换的四档。
 *
 * ## 顺序
 * 列表循环 → 单曲循环 → 不循环 → 随机模式，用户给的顺序，`next()` 按它循环。
 */
private enum class AudioPlayMode(val label: String) {
    ListLoop("列表循环"),
    SingleLoop("单曲循环"),
    NoLoop("不循环"),
    Shuffle("随机模式");

    /** 下一档。 */
    fun next(): AudioPlayMode {
        val all = values()
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        /** 从播放器那两个开关推出当前模式。随机优先：随机开着时循环档位不显示。 */
        fun of(repeatMode: Int, shuffle: Boolean): AudioPlayMode = when {
            shuffle -> Shuffle
            repeatMode == Player.REPEAT_MODE_ONE -> SingleLoop
            repeatMode == Player.REPEAT_MODE_ALL -> ListLoop
            else -> NoLoop
        }
    }
}

/**
 * 把一档模式写回播放器。
 *
 * 随机模式配的是 **REPEAT_MODE_ALL**，不是 OFF：随机的语义是"把整个列表打乱着放"，
 * 放到头就该绕回去；配 OFF 的话随机放完最后一首就停了，与"随机模式"这个名字给人的预期不符。
 * （用户说随机的细节"另外讨论"，这里先按最不意外的一种落地，改动只在 `applyPlayMode` 一处。）
 */
private fun applyPlayMode(controller: Player, mode: AudioPlayMode) {
    controller.shuffleModeEnabled = mode == AudioPlayMode.Shuffle
    controller.repeatMode = when (mode) {
        AudioPlayMode.SingleLoop -> Player.REPEAT_MODE_ONE
        AudioPlayMode.NoLoop -> Player.REPEAT_MODE_OFF
        AudioPlayMode.ListLoop, AudioPlayMode.Shuffle -> Player.REPEAT_MODE_ALL
    }
}

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


