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
 * 音乐播放页（媒体中心 → 音乐 → 点某一首）。
 *
 * ## 版式
 * 顶部左对齐的曲名 / 艺术家 → 大圆角封面 → **封面下方三行歌词**（当前句亮）→ 极细进度条 →
 * 左「已播」右「总时长」→ 三个大图标 → 底部一排次要动作。点歌词区进**整屏歌词**（当前句放大、
 * 自动滚动），再点回封面。
 *
 * 以下三项为版式与资源的硬性取舍：
 * 1. 封面使用 `/api/media/cover`（原始内嵌封面）而非 256px 缩略图：260dp 的容器使用
 *    256px 缩略图会明显模糊；
 * 2. 暂停时封面仅缩到 [AUDIO_COVER_PAUSED_SCALE]（0.96），不取 0.88：幅度过大会产生抖动感；
 * 3. 右侧时间显示**总时长**，而非负的剩余时间。
 *
 * ## 播放器不在这一页里
 * 播放交给 [UfiAudioPlaybackService]（MediaSession 前台服务），本页仅作为其**控制界面**
 * （[rememberUfiAudioController]）。所以：锁屏 / 通知栏 / 快捷设置里有真的媒体控制，耳机暂停键
 * 也能用，退出这一页音乐不会中断。控制器连接是异步的，未连接时显示加载状态，
 * 不呈现不可操作的按钮。
 *
 * ## 底部一排只放**真有能力**的动作
 * 循环模式（关 / 列表 / 单曲）、随机、播放队列 —— 全部是播放器自带能力。参考图里的定时关闭、
 * 音效、更多没有对应能力，所以不摆图标。
 *
 * ## 进度条为自绘实现，未使用 UfiSlider
 * `UfiSlider` 是"设置项里的滑块"（有标签、明显滑块、44dp 触控区），这里要的是"平时极细、
 * 按住变粗、没有滑块"。形态诉求冲突，所以在本模块内自绘（[MediaSeekBar]），颜色全部走令牌，
 * 且不放入 `app/ui` 公共组件 —— 例外范围仅限媒体中心。
 */
@Composable
fun MediaAudioPlayerScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    filePath: String,
    /**
     * 播放范围，由路由传入。
     *
     * **空串表示未指定范围**，而非整库：迷你条 / 标题栏挂件 / 通知栏深链接只知道「正在播这首歌」，
     * 不知道当前队列是按歌单还是专辑装的，所以它们不带这两个参数，本页收到空值时
     * **沿用现有队列**（见装队列 effect 的第一条分支）。
     * 「全部」页点歌会显式带 `scope=all`，那才是"请按整库重建队列"。
     * 语义定义见 `AudioQueueScope.of`。
     */
    scopeKind: String? = null,
    scopeKey: String? = null,
    /**
     * 顶栏返回键的动作（默认 null = 使用页壳默认的 `popBackStack`）。
     *
     * 音乐页的「上滑跟手面板」把这一页直接装进页内浮层（见 MediaAudioRisePanel.kt），
     * 此时它**不是**导航目的地，返回操作应关闭面板而非弹出音乐页，因此由宿主传入。
     * 系统返回键不经过这里，由宿主自己加 `BackHandler` —— 这是页壳 KDoc 里记着的分工。
     */
    onBack: (() -> Unit)? = null,
    /**
     * 是否启用共享元素（默认 true）。
     *
     * 页内浮层路径传入 `false`：面板与迷你条同处一棵树，同一个 key 在同一帧出现两次
     * 属于 `UfiSharedTransition.kt` 记录的禁用情形；且该路径本身是「整块面板升起」，
     * 不存在跨页面转场。导航路径的行为不变。
     */
    shareElements: Boolean = true,
    /**
     * 封面是否跟随手势从迷你条飞入（默认 false）。
     *
     * 仅页内浮层路径传入 true（见 [MediaAudioRisePanel]）：共享元素机制不适用于页内浮层
     * （同一棵树里同一个 key 不能出现两次，而且它的进度由导航转场驱动、不跟手指），
     * 因此封面改由覆盖层中的「飞行封面」承担（[UfiRiseFlyingCover]），本页仅上报槽位
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
     * ## 不使用 `state.tab(MEDIA_TYPE_AUDIO).items` 作为队列
     *
     * 该状态是「全部」页的**第一页**（100 首）。以它作为队列会同时产生三个问题：
     *  1. 从专辑/歌手/文件夹点歌时队列仍是整库，「只播这个歌手」无法实现；
     *     「下一首」会切到整库中的下一首，shuffle 也在整库范围内随机；
     *  2. 点的歌不在前 100 条内时 `indexOf` 返回 -1，若回退为 0，则**播放整库第一首**
     *     （点第 300 首却播第 1 首），UI 也显示错误；
     *  3. 专辑超过 100 首时，第 101 首之后无法进入队列，「下一首」会在第 100 首处停止。
     *
     * 因此按作用域一次性加载完整队列（[MediaModule.queueItemsOf]），队列即该范围的全集。
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
     * 控制器：**优先复用 App 壳中常驻探针已建立的那一份**（`UfiNowPlayingState`）。
     *
     * 页内面板在第一下拖动时才进入组合。若本页在此另开一条 `MediaController` 连接，
     * 面板的头几帧只能是「正在连接播放服务…」加载态，待 binder 返回后才换成真实内容，
     * 表现为上滑刚起步时画面突然变化。探针在 `MainActivity` 顶层持续连接（迷你条能够显示，
     * 即因为它已在播放），直接复用可在**第一帧就有内容**：封面槽、歌词、队列均无需等待往返。
     *
     * 仅当探针尚未连接（App 极早期 / 探针被销毁）时才自行连接，行为与既有实现一致。
     * 生命周期不变：此处只添加/移除监听，**不释放**共享实例。
     */
    val sharedController = UfiNowPlayingState.controller.value
    val ownController = if (sharedController == null) rememberUfiAudioController() else null
    val controller = sharedController ?: ownController

    var currentIndex by remember { mutableIntStateOf(-1) }
    /*
     * 初值直接向控制器查询一次。
     *
     * 若恒从 false 起步，播放态下第一帧会按「暂停」绘制封面（0.96 缩放），待队列 effect
     * 回读后才恢复 1.0；该时间点恰在「上滑完成、真封面接手」时，表现为封面到位后又缩放一次。
     * 现在第一帧即为真实播放态，暂停态直接为 0.96。
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
    /** 「定时关闭」设置弹窗。定时状态在进程级单例中维护，见 MediaSleepTimer.kt。 */
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
     * 媒体库记录只有文件名（如 `曲名-歌手.flac`），且 `MediaStore` 的 artist 在部分 FLAC
     * 上为空。播放器解析容器时会读出真实标签，经 [Player.Listener.onMediaMetadataChanged]
     * 送达，这也是通知栏/锁屏显示的同一份数据；它是曲名/歌手取值链的一级
     * （见 [title] / [artist]）。
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
     * 封面**能查到就提供**：迷你条与标题栏挂件读取队列项的 `artworkUri`（见
     * `UfiAudioNowPlayingProbe`），若给 null，这两处只显示占位音符；而播放页自身按
     * `MediaLibraryItem.id` 查封面，因此本屏无法暴露该问题。
     *
     * 仅当查不到（该曲目不在当前范围）时才给 null：cover 接口只接受 MediaStore id，
     * 仅凭路径无法拼出 URL。
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
     * 1. **路由指定哪一首，就从 0 播哪一首**。[filePath] 在 effect key 中，
     *    每次路由换歌都重跑；跳转一律使用 `seekTo(index, 0L)`，不使用 `seekToDefaultPosition`。
     * 2. **同作用域才复用队列，换作用域则整体重装**。作用域记录在
     *    [AudioQueueOwner]（进程内，不随页面销毁丢失）：从 A 目录切到 B 目录时，
     *    队列必须整体替换为 B，否则「下一首」仍会回到 A 范围。
     *
     * 分支：
     *  · 范围尚未加载完成 → **不做任何操作**。不得先装入半截队列再重装（会重新开始播放）；
     *  · **路由未带范围**（[queueScope] 为 null）→ 沿用播放器现有队列，仅跳到这一首。
     *    迷你条 / 标题栏挂件 / 通知栏走此分支：它们只知道「正在播这首歌」，不知道队列按
     *    歌单还是专辑组织，不应要求重建。若缺少该分支，从歌单「播放全部」后再从挂件
     *    返回播放页，队列会被静默重装为整库：歌曲不变，但「下一首」已超出歌单范围；
     *  · 这首歌不在范围内 → 装入单曲队列。**不得以索引 0 兜底**：那会播放成其他歌曲。
     *    同时清除 owner：该队列不代表任何作用域，下次进入必须重装；
     *  · 同作用域且队列内容一致 → 仅跳到路由指定的曲目；
     *  · 其余情况（换作用域 / 换排序 / 首次进页）→ 整体重装，从路由指定的曲目开始。
     *
     * 最后无条件回读播放器状态：media3 的 [Player.Listener] 只在**变化**时回调，不补发当前值。
     * 缺少此步骤时，页面在「服务已在播放」的情况下会显示暂停图标、循环/随机显示关闭，
     * 即呈现错误状态。
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
             * 用户手工修改过队列（插入 / 移除 / 重排）、仍在同一作用域、**且路由指定的曲目
             * 就在该队列中** → 仅跳转，不重装。
             *
             * 这是「虚拟播放列表」存在的前提。下文的 `loaded == paths` 比较的是「队列内容与
             * 作用域推导出的列表逐项相等」，而：
             * · 拖拽排序后顺序不同，不相等；
             * · 「下一首播放」插入的曲目可能不在该作用域内（从专辑 A 插入专辑 B 的曲目），
             *   永远不可能相等。
             * 若缺少该分支，每次返回播放页都会全量重装，用户排序立即丢失。
             *
             * ## `loaded.contains(filePath)` 的附加条件
             * 若只判断「同作用域 + 已修改 + 队列非空」，队列中不含路由曲目时不做任何操作，
             * 依据是「保留用户排序更重要」。该判断不成立：从列表点歌是**明确指令**（「播放这首」），
             * 而不作反应会使播放器继续播放旧队列（重启后恢复的队列可能仅剩几首），页面却显示
             * 新点的歌曲；「下一首」因旧队列到底而置灰，循环模式也显示异常。
             * 因此该情况落入下文的重装分支：用户点选了整库/专辑中的曲目，即按该范围重建队列；
             * 自定义顺序仅在「点选队列内曲目」时需要保留。
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
             * 播放/暂停图标读取**播放意图**（[Player.ufiPlayIntent]），而非 `isPlaying`。
             *
             * `Player.isPlaying` 的定义是 `playWhenReady && playbackState == READY && 未被抑制`。
             * 切歌时 playbackState 会进入 **BUFFERING** 再回到 READY，isPlaying 因此短暂为 false：
             * 图标先翻转为「播放」（显示为已暂停）再翻回，而音频并未中断。
             *
             * 因此同时订阅 onIsPlayingChanged（缓冲结束、焦点恢复等真实变化）
             * 与 onPlayWhenReadyChanged（用户播放/暂停操作），两者均重算同一意图值。
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
     * [currentIndex] 初值为 -1（尚未从播放器回读），此时使用路由指定的曲目：若直接写 0，
     * 页面会在回读前的帧显示**列表第一首**的曲名与封面，而用户点选的是另一首。
     *
     * 不使用 `coerceAtLeast(0)`：路由曲目不在当前范围时（如从通知栏深链接进入且范围已更换），
     * indexOf 为 -1，钳到 0 会显示**范围内第一首**的曲名/封面/歌词，而实际播放的是路由曲目，
     * 造成显示与播放不一致。`getOrNull(-1)` 返回 null，下游 `current?.path ?: filePath`
     * 会正确降级到路由曲目。
     */
    val resolvedIndex = if (currentIndex >= 0) currentIndex else paths.indexOf(filePath)
    /*
     * 以 **timeline 为权威**取当前曲目。
     *
     * 若直接 `tracks.getOrNull(resolvedIndex)`，即把**播放器 timeline 下标**当作
     * **浏览列表下标**。两者仅在「队列刚按作用域原样装入」时相等；用户在队列面板排序后
     * （或「下一首播放」插入曲目后），timeline 顺序与 `tracks` 分离，而 `currentIndex`
     * 仍随播放推进在 timeline 中移动，于是 `current` 取到另一首歌曲：曲名、封面、歌词、
     * 标签全部错误，表现为「播放的歌曲与显示不一致」「点列表的歌却显示别的歌」。
     *
     * 因此当前项取 `queueIds[currentIndex]`（mediaId = 路径），元信息经 `trackByPath`
     * 按路径查询，与排序无关；查不到（单曲兜底曲目不在浏览范围内）时降级到路由曲目，
     * 行为与「查不到显示文件名」的既有实现一致。
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
     * 封面预取。
     *
     * ## 为什么只预取前后各 [UfiAudioCoverPrefetch.QUEUE_WINDOW] 首而非整个队列
     * `/api/media/cover` 是**按需解析**的：core 每次需读取文件并抽取内嵌封面。
     * 而 `MediaLibraryItem` 中**没有「该曲目是否有封面」的标志**（仅有 web 端两处
     * 局部派生的 `hasCover`，并非接口字段），因此预取整个队列等于对每首曲目各发一次
     * 解析请求，无封面的曲目同样占用一次往返。队列可达数百首，会使 Coil 的请求队列
     * 与 core 的文件 IO 同时饱和，反而拖慢当前显示的封面。
     *
     * 窗口取 3，覆盖「连续点击下一首」与自动续播；若需更大窗口，应先由 core 在列表接口中
     * 提供 `hasCover`，以便只对确实有封面的曲目发请求。
     *
     * 请求构造（尺寸、内存缓存键）收敛在 [UfiAudioCoverPrefetch] 中：与列表首屏的预取
     * 使用同一套口径，分散实现会导致口径漂移。
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
        // 预取邻居必须**按 timeline 顺序**查找（与 current 的取法同一口径）。
        // 按 `tracks` 下标取邻居会在排序后取到「显示顺序的邻居」，而非「接下来要播的曲目」。
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
     * 音乐**不做播放进度记忆**。
     *
     * 若按 `MediaPlaybackProgress` 的存档 seek 回上次位置，「播 A → 播 B → 再点 A」时 A 会
     * 从中段继续播放。点选一首歌即期望完整播放；续播属于视频场景（如剧集中途），
     * 不适用于音乐。存档与续播由视频播放页承担（[MediaVideoPlayerScreen]）。
     *
     * [prefs] 仍需保留：歌词与封面的接口客户端均从它获取设备凭据。
     */
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }
    val currentPath = current?.path ?: filePath

    /*
     * 封面 URL：列表尚未加载时**先使用探针提供的 URL**。
     *
     * `current` 来自异步加载的 `tracks`（打开面板即触发一次拉取），因此页面头几帧 `current`
     * 为 null。若此时不提供封面 URL，`AsyncImage` 整块不参与组合，框内仅有 `surfaceMuted`
     * （浅色主题近白），待图片加载完成后再淡入；反复开关播放页时表现为「封面变白后重新加载」。
     *
     * `UfiNowPlayingState.artworkUrl` 由常驻探针维护，**打开第一帧即有值**；且队列项的
     * artworkUri 与 [media.coverUrl] 为同一 URL（见 `toUfiMediaItem`），因此 `current`
     * 解析完成后 URL 不变，不会触发重新加载。
     *
     * 仅当探针曲目与本页播放曲目相同（[playingPath] 相等）时使用；否则会先显示上一首的封面，
     * 该情况退回「等待列表」。
     *
     * 比较对象必须为 [playingPath]，而非路由传入的 [filePath]：后者仅在「页面刚打开且就是
     * 该曲目」时成立；页内切到下一首后 `filePath` 始终为打开时那一首，该兜底即失效。
     * 队列中查不到该曲目（单曲兜底 / 刚插入「下一首播放」）时 `current` 也为 null，
     * `coverUrl` 随之变为 null，`AsyncImage` 整块不组合，再次出现近白底。
     */
    val probeArtwork = UfiNowPlayingState.artworkUrl.value
    val probeMediaId = UfiNowPlayingState.mediaId.value
    val coverUrl = current?.let { media.coverUrl(it.id) }
        ?: probeArtwork.takeIf { probeMediaId.isNotBlank() && probeMediaId == playingPath }

    /**
     * 上一张**已成功绘制**的封面 URL（`onSuccess` 时更新）。
     *
     * 换歌时将其作为占位图传入新请求（见下方 `placeholderMemoryCacheKey`）：Coil 会**同步**
     * 从内存缓存取出该图绘制，因此换歌第一帧绘制的是旧封面，待新图解码完成后再替换。
     * 「切到未播放过的歌曲时先闪白屏再出图」即该帧无内容可绘制的空档。
     */
    var shownCoverUrl by remember { mutableStateOf<String?>(null) }

    /*
     * 封面请求对象需**记忆化**。
     *
     * 本页大量状态会触发重组（分页切换、歌词、进度等）。若在组合中逐次
     * `ImageRequest.Builder(...).build()` 生成新实例，Coil 会认为「模型变了」并重新执行加载
     * （即使内存缓存命中，也会有一帧空白或重新淡入）。左右划「封面/歌词」时本页重组一次，
     * 表现为手动拖拽时封面抖动。按 `coverUrl` 记忆化后，同一首歌始终使用同一个请求对象。
     *
     * ★ 另外两项：显式**内存缓存键**（与预取侧共用 [audioCoverMemoryCacheKey]，
     *   换歌时才能真正命中内存缓存）与**上一张封面作占位**（覆盖缓存未命中的帧）。
     *   `coverPx` 使用上方预取段计算出的同一个值。
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
     * 歌词状态**跨曲目暂留**。
     *
     * 若使用 `remember(currentPath)` 并在换歌时清空，切歌瞬间 lyrics 变空 → 歌词块淡出，
     * 数十至数百毫秒后新歌词到位 → 再淡入，形成**两段动画之间的空白**。
     *
     * 因此换歌时**不清空**：旧歌词先行显示，待新歌词（或「确认该曲无歌词」的结论）到位后
     * 一次性替换。「有词 → 有词」无需淡出，仅内容更新；只有切到确实无歌词的曲目才淡出。
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
        // ★ 此处**必须清空**：否则上一首的歌词会持续显示在屏幕上
        //   （「切到无歌词的歌后歌词仍在」即该 `?: return` 所致）。
        //   「暂留」仅负责**加载期间**不闪烁，结果返回后必须如实反映。
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
     * 曲名 / 歌手的取值链。
     *
     * 若直接显示 `item.name`，页面显示的是 `曲名-歌手.flac`，即**文件名**而非歌名。
     * 因此按可信度排序取值：
     *  1. **core 解析的标签**（[tags]，服务端直接读文件的 ID3 / FLAC 标签）——进页面就有，
     *     不必等播放器解容器；
     *  2. **列表里带的标签**（`/api/media/list` 的 title/artist，系统扫描时解析好的）；
     *  3. **播放器解出的元信息**（[trackMeta]）——前两级都拿不到时的兜底；
     *  4. **文件名拆分**（`歌名-歌手`，见 [splitFileNameTitleArtist]）：属于推测，置于最后。
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
     * 队列面板的数据 —— **按播放器中的真实队列**渲染，而非按浏览列表。
     *
     * 若直接将 `tracks` 映射为面板行：在队列恒等于整库第一页时两者恰好相同，但引入作用域
     * 后会错位 —— `target < 0` 路径装入的是**单曲队列**，而 `tracks` 是整个范围，
     * 面板会列出数百行，点击第 50 行执行 `seekTo(50)` 将作用于仅含 1 项的 timeline。
     *
     * 因此面板的行数与顺序只取 [queueIds]（`ufiAudioQueueIds()` 的回读），
     * 元信息按 path 在 `tracks` 中查询；查不到（单曲兜底曲目不在范围内）时使用当前曲目的
     * 标题/歌手兜底，避免出现「面板空白但音乐在播放」。
     */
    val queueRows = remember(queueIds, trackByPath, filePath, title, artist, durationMs) {
        val ids = queueIds.ifEmpty { listOf(filePath) }
        // key 必须唯一：LazyColumn 的 itemsIndexed 用它做移动动画与复用。
        // 「下一首播放」允许把同一首歌插多次（没有去重），直接使用路径作为 key 会冲突
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
     * 「封面 / 歌词」两页。
     *
     * 早期方案为「点歌词进入整屏、左右划切换」，存在两个问题：手势与整屏歌词内的点句跳转、
     * 纵向滚动冲突；且没有可见入口。现改为**左右 Tab + Pager**：Tab 提供可见入口，
     * 横向滑动由 Pager 处理，与页内纵向滚动天然分工。
     *
     * 状态提升到此处（而非留在 Column 内）是因为 Tab 与 Pager 分属两个子树、均需读取。
     */
    val pagerState = rememberPagerState(initialPage = AUDIO_PAGE_COVER) { AUDIO_PAGE_COUNT }
    val pagerScope = rememberCoroutineScope()

    /*
     * 返回键只拦截**队列面板**这一层浮层。
     *
     * 歌词页不拦截：Tab 始终可见（点击即回封面），也可左右划切换，无需占用返回键。
     * 若在歌词页按返回先回封面、再按才退出，离开播放页需要两次返回；对平级 Tab 页
     * 采用返回栈语义会被误认为返回失灵。因此歌词页按返回直接退出整个播放页
     * （走页壳的 popBackStack）。
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
        // 顶栏与内容同色：渐变由页壳铺满整页（含标题栏与状态栏区域），
        // 内容区不再自绘背景 —— 否则标题栏仅有 pageBg，与下方 accent 渐变之间出现色阶。
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
                    // 有歌词时「封面 / 歌词」区域整体偏下，此处整体上移 12dp
                    // （两页同步移动，Tab 与页面的对齐关系不变）。
                    .offset(y = (-12).dp)
            ) {
                /*
                 * Tab 与 Pager 的状态位于本 composable 顶层（返回键需要），此处仅做编排。
                 *
                 * ★ `selected` 传入的是**取值 lambda**，而非直接读取 `pagerState.currentPage`。
                 *   直接读取会使「当前页」State 的读发生在**本页组合作用域**内：手指左右划、
                 *   `currentPage` 在临界点反复切换时，整页（含 Pager 内的封面）会随之反复重组；
                 *   重组会重建封面请求与 Pager 内容，表现为手动拖拽时封面抖动。
                 *   在 [MediaPlayerTabs] 内部取值后，翻页只会重组那一排 Tab（开销很小），
                 *   封面不受影响。
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
                     * 关闭该 Pager 自身的**边界回弹**。
                     *
                     * 仅在拖到两端（页边）时出现封面位置抖动。两端唯一新增的因素是
                     * overscroll：Android 12+ 的拉伸回弹会给整块内容逐帧应用 `RenderEffect`，
                     * 而封面由多个图层叠加（缩放 / 阴影 / 圆角裁剪 / 切歌模糊）；拉伸效果
                     * 作用于这些图层时，边缘会逐帧重采样，表现为封面抖动。
                     *
                     * 该 Pager 仅含「封面 / 歌词」两页，位置反馈由上方 Tab 承担，
                     * 边界回弹不是必需信息。因此将本 Pager 的 overscroll 置空（仅影响横向翻页；
                     * 歌词页内部纵向列表的 overscroll 不受影响，其自行使用
                     * `rememberOverscrollEffect()`）。
                     */
                    overscrollEffect = null,
                    /*
                     * 该对齐当前**不影响布局**：两页均为 fillMaxSize —— 封面页占满整页并在
                     * 内部居中，歌词页（MediaLyrics.kt）同样占满整页。Pager 的 verticalAlignment
                     * 仅对「矮于页面」的内容有意义，保留 Top 是为了不依赖 Pager 默认值。
                     *
                     * 该值曾是修复「切歌时封面上下抖动」的关键：当时封面页为 wrap-content 的
                     * Column，高度随曲目变化（歌词块有无相差 86dp、曲名 1 行与 2 行相差一个行高、
                     * 歌手为空时该行连同 Spacer 一起消失），居中会把高度差的一半分配到上方，
                     * 导致封面上下跳动。该问题现由封面页自身处理：其中居中的一组高度为定值
                     * （曲名 minLines = maxLines = 2、歌手行恒定渲染），理由见该页开头的说明。
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
                         * 封面页：**「封面 + 歌名 + 歌手」这一组始终居中，歌词区不参与该居中**。
                         *
                         * 若歌词块不渲染时仍按 Pager 顶部对齐，内容变短后整块会贴在顶部、
                         * 下方留出大片空白，观感由「歌词区为空」变为「整页偏上」。
                         *
                         * 解法**不是**将 Pager 对齐改为 CenterVertically：那样歌词会计入被居中的
                         * 区域，有歌词时该组会上移半个歌词块的高度，切换有无歌词的曲目时封面
                         * 上下跳动。因此此处让 Column 占满整页并居中，再将歌词从「参与居中的高度」
                         * 中排除（做法见下文歌词段）。
                         *
                         * 居中的前提是该组高度为**定值**：封面 260dp、歌名
                         * minLines = maxLines = 2、歌手行恒定渲染。
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
                             * 切歌过渡：淡入 + 超调落位 + 景深对焦。
                             *
                             * key 使用当前曲目 id：换歌才触发，播放/暂停或进度变化不触发。
                             *
                             * ## 封面不放入 AnimatedContent 内
                             * 1. **切歌闪烁**：AnimatedContent 切换期间会同时保留旧内容与新内容，
                             *    常量 key `UFI_SHARED_KEY_AUDIO_COVER` 会在同一帧出现两个节点，
                             *    属于 `UfiSharedTransition.kt` 记录的「同帧重复 key」禁用情形；
                             *    曲名/歌手正因此被移出 AnimatedContent。
                             * 2. **从迷你条进入时封面短暂模糊**：列表异步加载，`current` 由 null
                             *    变为真实曲目时 `targetState` 从 -1L 变为真实 id，该时间点恰在
                             *    导航 RISE 转场中，进场模糊与超调会叠加在共享元素飞入上。
                             *
                             * ## 当前做法
                             * 封面为**单个节点**，缩放与模糊由 [rememberAudioSwitchProgress] 驱动
                             * （与曲名/歌手同一机制）。节点不复制，不会出现重复 key；首次组合
                             * 仅 snap 到终态，进页不播放效果。
                             *
                             * 代价：旧封面没有独立离场动画（同一节点，图片直接替换）。
                             * 该方案同一时刻只有一个元素做几何变化。
                             *
                             * **无方向**：若以 `targetState >= initialState` 比较 id 决定方向，
                             * 在随机播放或乱序队列下不成立（id 大小与队列顺序无关）。
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
                                     * 阴影的显示系数：0 = 无阴影，1 = [AUDIO_COVER_SHADOW]。
                                     *
                                     * 页内浮层路径中，封面由飞行封面交接而来：
                                     * 静态 `.shadow(18.dp)` 会在交接帧突然出现。
                                     * 因此随打开/收起补一条 280ms 的淡入淡出。
                                     *
                                     * 导航路径（riseFollow=false）无此交接过程，系数恒为 1，
                                     * 与既有实现逐像素一致。
                                     */
                                    val coverShadowFactor = rememberCoverShadowFactor(riseFollow)
                                    /*
                                     * 封面分为两层 Box：
                                     * - 外层仅含固定尺寸与共享元素标记，几何稳定；
                                     * - 内层承担播放/暂停的 0.96 缩放，以及切歌的缩放与模糊。
                                     *
                                     * 合并为一层会产生冲突：共享元素基于**测量得到的位置与尺寸**做补间，
                                     * 而 graphicsLayer 的 scale 是绘制期变换。转场进行中切换播放/暂停时，
                                     * 缩放会叠加在补间边界上，导致封面位置突变且落点与终点不一致。
                                     * 分层后共享元素读取的始终是 260dp 的固定框，scale 仅缩放框内内容。
                                     *
                                     * 共享元素的对端是迷你控制条中 48dp 的封面
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
                                                // 可以同时发生，叠加两层 graphicsLayer 只会造成一次无意义的离屏。
                                                scaleX = coverScale * switchScale
                                                scaleY = coverScale * switchScale
                                                /*
                                                 * 缩放 / 阴影 / 圆角裁剪合并到**同一图层**。
                                                 *
                                                 * 若使用四层嵌套（scale 图层 + `.shadow()` 图层 + `.blur()`
                                                 * 图层 + `.clip()` 图层），分页拖拽时这些图层需在**亚像素位置**
                                                 * 逐帧重新栅格化，表现为封面位置抖动 —— 并非布局变化，
                                                 * 而是多层裁剪/阴影重采样的边缘逐帧跳变。
                                                 *
                                                 * 合并后滚动时仅执行一次缩放与一次圆角裁剪：
                                                 * · 阴影用图层 elevation（panel 路径需要它逐帧淡入，
                                                 *   导航路径系数恒为 1，与写死的 `.shadow(18.dp)` 等价）；
                                                 * · clip = true 负责圆角裁剪，原 `.clip()` 不再需要。
                                                 */
                                                shadowElevation =
                                                    AUDIO_COVER_SHADOW.toPx() * coverShadowFactor.value
                                                shape = AUDIO_COVER_SHAPE
                                                clip = true
                                            }
                                            // 落点上报挂在**缩放层之内**：飞行封面需
                                            // 落到「真实封面绘制出的矩形」，暂停态的 0.96 也需计入，
                                            // 否则交接时会缩 4%。该 Modifier 位于 graphicsLayer 之后，
                                            // 测量的 bounds 才包含该缩放。
                                            .ufiRiseCoverSlotReportIf(riseFollow, pagerState, AUDIO_PAGE_COVER)
                                            // 仅在**切歌期间**需要时挂载模糊：
                                            // 静止时 switchBlur 为 0，而 `Modifier.blur` 只要带圆角
                                            // edgeTreatment 仍会添加裁剪图层，造成一次多余的离屏
                                            // 与滚动时的重新栅格化。
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
                                                 * 图片绘制完成后才允许交接：
                                                 * 面板侧收到该信号后才撤除飞行封面
                                                 * （见 [UfiAudioRisePanel.isPlayerCoverReady]），
                                                 * 否则归位瞬间会露出 `surfaceMuted` 近白底。
                                                 */
                                                onSuccess = {
                                                    // 该图已绘制完成：下一首换图时作为占位，
                                                    // 换歌那一帧因此不会露出近白底（见 `shownCoverUrl`）
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
                             * 这两行**刻意放在切歌 AnimatedContent 之外**。
                             *
                             * 它们要挂共享元素（对端是迷你条那两行），而共享元素的 key 是常量：
                             * 放在 AnimatedContent 里面时，切歌那一刻旧内容与新内容会同时在树上，
                             * 同一个 key 会短暂出现**两个**候选，导致连贯动画失效。
                             * 页面进入时几乎必然切歌一次：列表异步加载，`current` 由 null 变为
                             * 真实曲目时 `targetState` 变化，时间点恰在导航转场中。
                             *
                             * 代价是切歌时这两行无法随封面一起由 AnimatedContent 搬运。
                             * 因此改为对**同一节点**应用 alpha + 4dp 上浮
                             *（[audioMetaEnterModifier]），节点不复制，常量 key 不会同帧出现两次。
                             * 三行按 `STAGGER_DELAY_MS` 顺延，形成阅读顺序。
                             * 修饰符位于 `ufiSharedBounds` **之后**：几何仍由共享元素接管，此处仅修改
                             * alpha 与 translationY。
                             */
                            Spacer(Modifier.height(Spacing.Large))
                            /*
                             * 曲名：固定**两行高**的框，文字在其中垂直居中。
                             *
                             * 曲名框按两行预留（使换歌时高度不变），而多数曲名仅一行，
                             * 空出的一整行落在文字与歌手之间，造成两者间距过大。
                             *
                             * 因此使文字在两行框内居中：空白一半移至文字上方、一半留在下方，
                             * 歌名 → 歌手距离减半；**框高仍为两行**，整组高度不随曲名长度变化，
                             * 换歌时封面不会上下跳动（`minLines = 2` 即为此设置）。
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
                             * 歌词预览。
                             *
                             * ## 如何在不影响组居中的前提下紧贴其下
                             * 由父 Column 的 [AudioCoverPageArrangement] 负责：该排布规则将**第一个
                             * 孩子**（即上方那一组）置于整页正中，第二个孩子置于其正下方。歌词不参与
                             * 「居中」计算，因此有歌词时组不会被上移，切换有无歌词的曲目时封面不跳动。
                             *
                             * 此处**不再**自行添加 `Spacer(Spacing.Large)`：间距属于排布规则的一部分，
                             * 添加在子树内会使「组 + 间距 + 歌词」成为一个整体，居中需重新计算。
                             *
                             * ## 判据
                             * 与 MediaLyricsPreview 内部的守卫**完全一致**（空行 / 无时间轴均视为
                             * 「没有」），否则版式与实际渲染内容不一致。代价是：在有歌词与无歌词的
                             * 曲目之间切歌时，歌词块会整块出现 / 消失；但它位于组的**下方**，
                             * 不会带动封面位移，因此不会复现「封面上下抖动」。
                             */
                            val hasLyrics = lyrics.isNotEmpty() && lyrics.first().timeMs >= 0L
                            /*
                             * 有歌词 ⇄ 无歌词的切歌。
                             *
                             * 若使用 `if (hasLyrics) { … }` 直接挂载/卸载，从有词切到无词时三行预览
                             * **整块消失**。因此改用 [AnimatedVisibility] 淡入淡出（末段上移 4dp）。
                             *
                             * 使用 `fadeIn/fadeOut` 而非 `expandVertically/shrinkVertically`：
                             * [AudioCoverPageArrangement] 按「歌词块高度」摆放组，若高度参与补间，
                             * 组会在整段动画中被逐步上移（即「换歌封面上下跳」）。仅改变 alpha 时
                             * 高度为两档定值，组不移动。
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

                // ── 三键：无底色、中间最大，每个键下方标注名称 ──
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
                        // 迷你条已提供「上一首」对端，因此该键也挂 key。
                        // 三颗键同时参与转场，整组同步缩放，不会只有中间两颗在动。
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

                // ── 底部一排次要动作：模式 / 定时 / 队列（均为播放器能力，各带文字）──
                //
                // 采用三等分而非 SpaceEvenly：这三个按钮的文字长度会随状态变化
                // （「不循环」↔「列表循环」、「队列（3）」↔「队列（128）」）。SpaceEvenly 按实际
                // 宽度分配剩余空间，任一按钮字数变化会使三个按钮整体横向移动。改为各自
                // weight(1f) 的固定格子并内部居中，使图标位置固定、仅格内文字变化。
                //
                // 底部三格为 模式 / 定时 / 队列。随机模式已并入
                // **单一四档模式**（列表循环 / 单曲循环 / 不循环 / 随机模式），空出的一格
                // 用于「定时关闭」（见 MediaSleepTimer.kt）。
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
            // 不使用 `if (showQueue)` 摘除整棵子树，而是常驻组合 + 仅翻转 `visible`。
            // 弹窗离场由两部分负责：平台窗口动画（`UfiDialogAnimation`）与 shell 的
            // mounted-state ramp；二者均要求窗口仍存在且 `visible` 翻为 false。
            // 摘除子树会立即销毁窗口，动画未执行，表现为「点击后弹窗瞬间消失」。
            /*
             * 队列面板的一行提示（目前仅「已关闭随机播放」）。
             * 放在本层而非面板内部：它由面板回调触发，但面板**每次打开**都应处于干净状态。
             * 子树常驻组合后，重置时机改为「打开那一刻清空」，使离场动画期间该行仍存在，
             * 不会在弹窗消失过程中提前闪掉。
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
                    // 回读：removeMediaItem 后 timeline 变化，面板需按新队列重排。
                    // media3 的 Listener 仅在变化时回调；本改动由本地发起，直接同步
                    // 比等待回调更可靠（也避免面板显示一帧旧数据）。
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
                     * 排序时若随机播放开启，关闭随机并提示。
                     *
                     * 否则用户完成排序后，实际播放仍按乱序进行，表现为「功能看似生效但实际无效」，
                     * 比禁用排序更差。关闭随机符合当前意图：用户正在手工排序，即希望按该顺序播放。
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
             * 定时关闭设置弹窗：与队列弹窗同一套壳，同样**常驻组合 + 仅翻转 `visible`** ——
             * 摘除子树会一并移除离场动画。
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
 * 使用公共弹窗组件 [UfiCustomDialog]：标题与右上角 × 由其提供，本组件仅填充内容。
 * 队列本身是一层浮层而非独立页面，因此不自绘顶栏。
 *
 * ## 版式要点
 *
 * 1. **leading 使用「序号槽」**。若在 leading 中同时放置 accent 竖条与
 *    PlayArrow/MusicNote 图标，二者表达同一语义（当前项）；且竖条在非当前项时
 *    「透明但仍占宽度」，会使每行文字右移 3dp + 间距。当前槽位显示序号，
 *    非当前项显示 `1 2 3…`，当前项替换为 [UfiPlayingBars]。
 * 2. **显示序号**：「第几首 / 共几首」是队列的核心信息。
 * 3. **「共 N 首」移至标题栏右侧**，并以 `3 / 24` 形式呈现，避免单独占用一行高度。
 * 4. **打开时自动滚到当前项**：队列可达数百首，若停留在顶部，正在播放的曲目可能
 *    位于第 80 行而不可见。
 *
 * 列表仍受 [AUDIO_QUEUE_LIST_MAX_HEIGHT] 上限约束：不限高会使弹窗超出屏幕。
 */
@Composable
internal fun NowPlayingQueueDialog(
    /**
     * 弹窗是否可见。
     *
     * 该参数为必需：弹窗的离场动画由 [UfiDialogShell] 的 mounted-state 模式负责
     * （先执行离场 ramp，完成后才卸载窗口），且**仅在 `visible` 翻为 false 时启动**。
     * 若硬写 `visible = true`、由调用点用 `if (showQueue)` 摘除整棵子树，ramp 无法执行，
     * 表现为「点一首歌 → 弹窗瞬间消失」。因此调用点常驻组合该子树、仅翻转 `visible`，
     * 使关闭路径（点外部 / 返回键 / ×）同样具有动画。
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
     * 「编辑顺序」模式：开启后每行右侧由「时长 + −」替换为「↑ ↓ −」。
     *
     * ## 使用模式切换 + 按钮而非长按拖拽
     * 行组件为 `UfiListRowCard`（签名冻结的公共组件，内部自带 `combinedClickable`）。
     * 若在其外部接入 `detectDragGesturesAfterLongPress`，需与其内部点击手势做仲裁：
     * 长按由谁消费、拖拽期间是否屏蔽 onClick、跨行边界时 LazyColumn 的自动滚动如何接入，
     * 均需真机验证，属于独立工作。
     *
     * ↑↓ 按钮在功能上完备（可实现任意顺序），仅效率较低。
     *
     * ## 使用模式而非常显 ↑↓
     * 行右侧已有「时长 + −」，再添加两个图标将挤占时长；时长为更高频信息。
     * 排序是低频操作，进入模式所需的一次额外点击可以接受。
     */
    var reorderMode by remember { mutableStateOf(false) }
    // 每次打开都重置为「不编辑顺序」：该子树常驻组合（见 [visible] 的说明），
    // 不重置会使上次退出时的编辑态保留。
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
 * 「这一首正在播」的三根律动条。
 *
 * 仅作为「在播」的视觉提示，**不是频谱**：不读取音频数据，仅由三根条按固定节奏反向运动。
 * 实现真实频谱需接入 `Visualizer` 并申请录音权限，对一个 20dp 的装饰元素不适用。
 *
 * 三根条的峰高与起跳延迟固定（[EQ_BARS]）：错开相位才能呈现跳动效果，同步会表现为整体缩放。
 *
 * 队列弹窗行首与音乐列表（[MediaAudioList]）封面角标共用该组件，高度与条宽保持一致，
 * 避免两处尺寸不一致。
 *
 * @param playing 暂停时绘制**静止**的三根条：仍可识别为当前曲目，但不表现持续跳动。
 *   实现上使用 `state.value`（而非 `by` 委托）读取动画值：暂停分支不读取即不订阅，
 *   无限动画因此不再逐帧请求下一帧。
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


// 声明为 internal 而非 private：覆盖层中的「飞行封面」需使用同一套尺寸与圆角，
// 否则交接时与播放页封面存在可见错位或圆角差异。
internal val AUDIO_COVER_SIZE = 260.dp
internal val AUDIO_COVER_CORNER = 20.dp

/**
 * 播放页封面在 Coil **内存缓存**中的键。
 *
 * ## 为什么需要自定义键
 * Coil 的默认键包含其内部解析出的请求选项，**预取**（`enqueue`，无 target）与**显示**
 * （`AsyncImage`，带约束）两条路径构建的请求难以保证完全一致，导致预取失效：
 * 显示时仍需解码，出现空白帧。自定义键仅含「尺寸 + URL」，两侧必然一致。
 *
 * 尺寸必须计入键：迷你条 48dp 与飞行封面 260dp 会解码出不同像素的位图，
 * 共用一个键会使小图覆盖大图（封面被放大后模糊）。
 */
internal fun audioCoverMemoryCacheKey(url: String, px: Int): String = "audio-cover@${px}px:$url"

/** 封面的圆角形状。**必须常量化**：图层参数按引用比较，逐帧新建会被判定为「形状变化」。 */
private val AUDIO_COVER_SHAPE = RoundedCornerShape(AUDIO_COVER_CORNER)

/**
 * 封面阴影的满档高度。见 [rememberCoverShadowFactor]：页内浮层路径在打开/收起时
 * 沿 [UfiMotion.Duration.Gentle] 淡入淡出，导航路径恒为满档（与原 `.shadow(18.dp)` 等价）。
 * internal：覆盖层中的飞行封面需按同一值绘制阴影（见 `UfiRiseFlyingCover`）。
 */
internal val AUDIO_COVER_SHADOW = 18.dp

/**
 * 暂停时封面缩放到的比例。
 *
 * 取 0.96 而非 0.88：0.88 在 260dp 上产生 31dp 位移，反复播放/暂停时呈抖动感，
 * 而该动作仅需表达「已暂停」的状态差异。
 */
private const val AUDIO_COVER_PAUSED_SCALE = 0.96f

// ── 切歌动画（淡叠 + 超调落位 + 景深对焦 + 时序递进）────────────
//
// ## 动作构成
// · 新封面：`1.05 → 0.996 → 1`（超调后落位）叠加 `blur 9dp → 0`（景深对焦）
// · 旧封面：缩到 0.96 + 模糊到 7dp 后淡出
// · 文字：上浮 4dp，曲名 / 歌手 / 歌词按 `UfiMotion.STAGGER_DELAY_MS` 顺延
//
// ## 三条约束
// 1. **blur 只能加在内层、且位于 clip 之前**。封面为两层 Box：外层挂 shared element
//    且几何必须稳定，内层才做变换。此处用 `BlurredEdgeTreatment(圆角)` 裁掉模糊边缘，
//    否则会溢出圆角。`Modifier.blur` 在 **API < 31 上静默无效**（不崩溃），
//    本方案在该版本回落为「仅超调 + 递进」，观感仍成立。
// 2. **超调必须使用 keyframes，不使用 spring**。此处为「1.05 → 0.996 → 1」三段定值，
//    keyframes 可控可测；spring 的停顿点会随起始速度变化，连续点击下一首时幅度不一致。
// 3. **文字不进入 AnimatedContent**，因此其进场是对**同一节点**应用 alpha + translationY，
//    节点不复制。

/** 切歌动画基准时长。使用现成档位而非裸数字（调整值为 290，最近的档位为 280）。 */
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
 * 换封面时的交叉淡入淡出时长。
 *
 * 请求使用 Coil 的 `CrossfadeTransition` 而非 `crossfade(false)`：后者在新图解码完成后
 * 直接替换像素；前者从**上一张**（即 `placeholderMemoryCacheKey` 提供的那张旧封面）
 * 淡入新图，中间不会露出近白底。
 *
 * 260ms 略短于切歌动画本身（[AUDIO_SWITCH_COVER_MS] = 320ms），使「图像替换完成」略早于
 * 「缩放/模糊落位」：观感为先对焦、再落位，而非两者同时结束。冷启动后首次加载
 * （尚无旧封面可淡）时，该段表现为从背景淡入，同样自然。
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
 * 封面阴影的显示系数：`0` = 无阴影，`1` = 满档 [AUDIO_COVER_SHADOW]。
 *
 * 仅对页内浮层路径（[riseFollow]）做淡入淡出：封面由飞行封面交接而来，静态阴影会在
 * 交接帧突然出现。导航路径无此交接时刻，系数恒为 1，与原写死的 `.shadow(18.dp)` 逐像素一致。
 *
 * 返回 `State` 而非 `Float`：调用点仅将其交给 `graphicsLayer {}`，绘制期读值即可，
 * **不会**使整块封面（含 AsyncImage）每帧重组。
 */
@Composable
private fun rememberCoverShadowFactor(riseFollow: Boolean): State<Float> {
    /*
     * ★ `UfiAudioRisePanel.open` **不得在组合中读取**。
     *
     * 该状态在「按下返回（close）」那一帧翻转。在组合期读取会使**整页**在该帧重组，
     * 而这一帧正是关闭动画起始帧，表现为「按下返回瞬间抖动」。
     * 因此改用 snapshotFlow 在 effect 中观察：组合期不读取任何 State，翻转仅推动该
     * Animatable（仅在 `graphicsLayer` 中被读取，属于绘制期），页面不重组。
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
 * 切歌进度：`0` = 刚换歌，`1` = 已落位。封面的缩放与模糊均由其派生。
 *
 * ## 首次组合与「从无曲目到有曲目」均不播放动画
 * 列表异步加载：页面刚组合时 `current` 为 null（trackKey = -1），数帧后才变为真实曲目。
 * 仅对「首次组合」做 snap 不足：-1 → 真实 id 同样会触发一次播放，且时间点落在
 * 打开动画（导航 RISE 或页内上滑）的后半段。落位时真封面接手，动画仍处于 1.05 → 1.0
 * 的收尾阶段，表现为「封面到位后又缩放一次」。
 *
 * 因此判据改为「上一次的键存在且为真实曲目」：`-1` 仅表示「尚未取得曲目」，
 * 从它到真实曲目属于**初始化**，不是换歌。
 *
 * ## remember 不放在 reduceMotion 判断之后
 * 条件式 `remember` 是 Compose 的硬错误：`reduceMotion` 可在运行期被用户修改，
 * 一旦变化会使后续 remember 槽位整体错位。因此始终先执行 remember，
 * 条件分支仅写在 [LaunchedEffect] 内。
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
 * ── 共享元素的开关版本 ────────────────────────────────────────────
 *
 * 本页有两条呈现路径：导航目的地（`Routes.MEDIA_AUDIO`）与音乐页的页内浮层
 * （[MediaAudioRisePanel]）。后者与迷你条同处一棵树，同一个 key 同帧出现两次属于
 * `UfiSharedTransition.kt` 记录的禁用情形，因此该路径整页不挂共享元素。
 *
 * 统一封装为 `xxxIf` 而非在各调用点写 `if`：调用点有五处（封面 / 歌名 / 歌手 / 进度条 /
 * 三颗键），逐一编写条件既冗余又易漏；漏一处会使某个节点在面板打开时意外参与匹配。
 * 此处集中开关，五个调用点统一遵循。
 */
@Composable
private fun Modifier.ufiSharedElementIf(enabled: Boolean, key: String): Modifier =
    if (enabled) ufiSharedElement(key) else this

/** 见 [ufiSharedElementIf]。文字类传入 `scaleContent = false`，理由见 `ufiSharedBounds`。 */
@Composable
private fun Modifier.ufiSharedBoundsIf(
    enabled: Boolean,
    key: String,
    scaleContent: Boolean
): Modifier = if (enabled) ufiSharedBounds(key, scaleContent) else this

/**
 * 页内浮层路径的封面槽**上报**（拆为两个开关）。
 *
 * 拆分原因：上报需挂在**带缩放的图层**（暂停 0.96 / 切歌缩放均需计入飞行封面落点），
 * 而「隐藏真封面」需挂在外层（缩放不应影响让位/透明度）。合并为一个 modifier 时两者只能
 * 选同一层，导致落点不含缩放（交接缩 4%）或让位也随缩放变化。
 *
 * `pagerState` 传入后仅在封面页**停稳**时上报，详见 `ufiRiseCoverSlotReport` 的说明
 * （分页滑动会带动该区域的窗口坐标）。
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
 * 取 24dp 而非 `Spacing.Large`（12dp）：12dp 在 6 英寸屏上使封面与文字几乎贴边，
 * 底部两排控件也被挤至屏幕边缘。播放页为单一焦点页面，边距应大于列表页。
 */
private val AUDIO_PAGE_PADDING = 24.dp

/** 两页的下标与总数：0 = 封面，1 = 歌词。 */
private const val AUDIO_PAGE_COVER = 0
private const val AUDIO_PAGE_LYRICS = 1
private const val AUDIO_PAGE_COUNT = 2

/**
 * 翻页效果的三个幅度。
 *
 * 离场页：透明度 → 1 − 0.55、缩放 → 0.94、上移 18dp。三者均取小值：
 * 目的是使两页「分层」，而非卡片轮播；幅度过大时封面在划到一半时会明显跳动。
 */
private const val AUDIO_PAGE_SWITCH_FADE = 0.55f
private const val AUDIO_PAGE_SWITCH_SHRINK = 0.06f
private val AUDIO_PAGE_SWITCH_RISE = 18.dp

/**
 * 封面 ⇄ 歌词翻页时两页各自的进场 / 退场效果。
 *
 * `pagerState` 的位置**仅在绘制期**读取（`graphicsLayer` 块每帧末执行），因此：
 * · 手指划到何处效果即跟随到何处，无需另起动画对时；
 * · 不会因读取 State 使整页（尤其封面）重组 —— 与 Tab 的 `selected = { }` 同理。
 *
 * `alpha` 保留 45% 底值而不淡到 0：两页底色为同一张 accent 渐变，淡到 0 会在交界处
 * 露出后方空白，产生抖动感。
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
 * 封面页的纵向排布：**「封面 + 歌名 + 歌手」这一组居中，歌词位于其正下方**。
 *
 * ## 不使用 `Arrangement.Center`
 * 使用 Center 会将歌词计入「需居中的整块」：有歌词时整块变高、组上移半个歌词块高度，
 * 切换有无歌词的曲目时封面上下跳动。因此自定义排布规则，保证
 * **歌词区始终不参与该组的居中**。
 *
 * ## 该页包含五个孩子
 * 若仅将 `sizes[0]` 视为组、`sizes[1]` 视为歌词，只设置这两个下标的位置，则真实的
 * Column 中该组是**五个并列的孩子**：封面 / 间距 / 歌名 / 间距 / 歌手
 * （`Arrangement.arrange` 只能取得每个孩子的尺寸，不知道嵌套关系）。
 * 第 3 个孩子之后的位置将保持 `outPositions` 的默认值 0，表现为**歌名与歌手被顶到
 * 页面最上方、覆盖在封面之上**；有歌词时歌词落在其原位置，形成重叠。
 * 因此按 [groupChildCount] 逐项排列，最后一个孩子（若存在）为歌词。
 *
 * ## 高度不足时
 * 组居中优先；仅当「组 + 间距 + 歌词」超出可用高度时，才将组上移**恰好容纳歌词**的量
 * （不越过顶边）。小屏上宁可减少留白，也不裁掉歌词。
 * 该分支仅在屏幕确实放不下时生效，此时封面位移幅度远小于「整块居中」的方案。
 *
 * @param groupChildCount 参与居中的孩子个数（封面 / 间距 / 歌名 / 间距 / 歌手 = 5）。
 *   剩余孩子为歌词：不参与居中，置于组的正下方。调用点必须与实际孩子数一致，
 *   多一个或少一个都会改变歌词与组的划分。
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
 * 封面页中「参与居中的那一组」的孩子个数：封面 / 间距 / 歌名 / 间距 / 歌手。
 *
 * 修改该页版式时（例如在歌名下方增加专辑名行）**必须同步修改该值**：
 * 排布规则按「前 N 个为组、第 N+1 个为歌词」划分，数字不一致会使多出的孩子被当作歌词
 * 摆到组的下方。
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
 * 页面壳已扣除导航栏/手势条的安全区，该段为额外的**可触达余量**：
 * 贴近屏幕最下沿的按钮需要拇指伸至边缘才能点击，且易与系统边缘手势冲突。
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
     * 取值 lambda 而非 Int：将 `pagerState.currentPage` 的读取作用域限制在本函数内，
     * 翻页时才不会引起整页（尤其封面）重组。
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
             * 切页过渡：选中态由**瞬时切换**改为三项分别补间：
             * · 下划线宽度 0 → 满档（从文字中间展开）；
             * · 下划线颜色 透明 → accent；
             * · 文字颜色 secondary → primary。
             * 时长统一使用 [UfiMotion.Duration.Standard]，与 Pager 侧保持同一节奏。
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
 * 播放控制中的一个动作：图标 + **下方文字说明**。
 *
 * 纯图标对「循环 / 随机」这类三态开关表达不足：图标只能表达「具备该功能」，
 * 无法表达「当前档位」。文字与图标同步变化（「列表循环」/「单曲循环」/「不循环」）。
 *
 * @param active 高亮态（循环/随机开启时使用 accent），与 [enabled]（是否可点击）含义不同。
 * @param sharedKey 跨页面共享元素的 key（见 [ufiSharedBounds]）；`null` 表示该键在迷你条上
 *                  没有对端，不参与转场。主控三键（上一首 / 播放暂停 / 下一首）均传值；
 *                  次要动作（循环 / 随机 / 队列）在迷你条上没有对端，均为 null。
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
         * 图标放入**固定尺寸的正方形槽**内居中，而非直接放置 Icon。
         *
         * 该排三个键的图标尺寸不同（上/下首 34dp、播放 52dp），外层 Row 使用
         * `Alignment.Top`，三个图标的**顶边**对齐而圆心相差 9dp，表现为播放键偏下、
         * 文字随之错行。统一槽高后，圆心与下方文字位于同一水平线。
         *
         * 槽使用 `size()` 而非 `height()`：仅约束高度时宽度为 wrap content（= 图标宽度），
         * 槽的长宽比会随图标尺寸变化（34×52 / 52×52）；而迷你条对端为 40×40 正方形，
         * 共享元素补间时两端长宽比不同，图标会在被拉伸的框内跳动。固定为正方形后，
         * 两端仅尺寸不同、比例一致，缩放即单纯的等比变化。
         *
         * 共享元素挂在该槽上而非整个 Column：Column 还包含文字，若文字一并补间，
         * 会从迷你条的纯按钮中「长出」文字。槽尺寸为常量、与图标尺寸无关，
         * 是该列中几何最稳定的节点。
         *
         * 使用 ufiSharedBounds 而非 ufiSharedElement：两端尺寸相差 12dp，且内容为
         * **不会**随约束自适应的图标，需整体缩放而非重新测量，理由见该函数注释。
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
 * 播放模式：将原「循环」与「随机」两个开关合并为**单一四档模式**。
 *
 * ## 合并原因
 * 两个开关原为正交（可组合为「随机 + 单曲循环」），但该组合难以理解：单曲循环下随机
 * 实际不生效，而界面两键同时高亮。用户需要的是「当前处于哪种播放方式」，
 * 因此合并为一个可循环切换的四档模式。
 *
 * ## 顺序
 * 列表循环 → 单曲循环 → 不循环 → 随机模式，`next()` 按该顺序循环。
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
 * 随机模式对应 **REPEAT_MODE_ALL**，而非 OFF：随机的语义是「将整个列表打乱后播放」，
 * 播放到末尾应回到开头；若配置 OFF，随机播放完最后一首即停止，与「随机模式」的预期不符。
 * 实现集中在 `applyPlayMode` 一处。
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


