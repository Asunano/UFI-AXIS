package com.ufi_axis.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.ufi_axis.ui.components.common.LocalUfiInsideRisePanel
import com.ufi_axis.ui.components.common.UfiPageBackdrop
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.viewmodel.MainViewModel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  音乐页 → 播放页：**跟手升起的面板**（2026-10-14）
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * ## 为什么不能继续用导航转场
 * 播放页是 NavHost 的一个目的地，它"从迷你条上沿升起"是 navigation-compose 的
 * `enterTransition`（见 `Navigation.kt` 的 `risePanelEnter`）—— 那是**时间驱动**的动画：
 * 手指松开的那一刻它才开始跑，无论用户拖了 20dp 还是 200dp 都一样。
 * 用户要的是**渐进式**：面板的位移 = 手指的位移，松手才决定"落位"还是"回弹"。
 * Compose 没有公开 API 能把导航转场的进度交给手势（`SeekableTransitionState` 只对
 * 自己持有的 `Transition` 有效，NavHost 不暴露它），所以这条路只能绕开导航。
 *
 * ## 现在的做法：面板由音乐页自己托管
 * 三个挂着迷你条的页面（音乐列表 / 歌单详情 / 分组详情）把 [MediaAudioRisePanel] 交给
 * 页壳的 `overlay` 槽，面板里装的是**同一个** [MediaAudioPlayerScreen]：
 * · 拖动中：面板位移 = 手指位移（`offsetPx` 直接累加，1:1，不做任何曲线映射）；
 * · 松手：按"走过行程的 [UFI_RISE_COMMIT_FRACTION]"或"甩得够快"决定补间到 0 还是回行程；
 * · 返回键：关面板，而不是把音乐页弹掉。
 *
 * ## 代价（用户已确认接受）
 * 1. 其它入口（点列表行 / 标题栏挂件 / 通知栏）**仍然走导航 + RISE 转场**，于是同一份
 *    播放页内容有两条呈现路径。两条路调的都是 `MediaAudioPlayerScreen`，内容不会漂走。
 * 2. 这条路径上**没有共享元素形变**：面板与迷你条同处一棵树，同一个 key 同帧出现两次正是
 *    `UfiSharedTransition.kt` 记下的禁区，所以面板传 `shareElements = false`。
 *    观感由"面板整体从迷你条上沿（进度条那条线）升起"承担 —— 起点与导航那条路的
 *    `riseStartOffset` 是同一条线，封面由手写的飞行封面接手（见 [UfiRiseFlyingCover]）。
 *
 * ## 为什么状态是进程级单例而不是 CompositionLocal
 * 读它的是两处**不属于同一棵子树**的东西：迷你条（在页壳的 `bottomBar` 槽里）与面板
 * （在页壳的 `overlay` 槽里）。用 CompositionLocal 就得把两者共同包起来，而那意味着把
 * 三个页面的 `UfiScreenScaffold(...)` 整体再缩进一层。这里与 [UfiNowPlayingState] 一样
 * 用进程级单例：同时只可能有一页在放音乐，不存在两处争用。
 */

/**
 * 跟手形变的 key：迷你条那张封面 → 面板里那张封面（2026-10-14）。
 *
 * 这条路径**用不上**共享元素机制（见文件头"代价 2"），所以封面是**手写插值**的：
 * 迷你条把自己那张封面的窗口位置报进 [UfiAudioRisePanel.reportFollowSource]，
 * 面板里那张封面用 [ufiRiseFollow] 按同一个位移插过去。
 */
internal const val UFI_RISE_FOLLOW_COVER = "ufi.rise.cover"

/**
 * 松手时"已经走过多少行程"算提交。
 *
 * 0.25 与一次明确的短扫相当（800dp 的屏上是 200dp）；再小会让"轻轻碰一下"也开页，
 * 再大则要划掉小半屏才认。速度够快的甩动另有一条通道（见 [UFI_RISE_FLING_PX_PER_SEC]），
 * 所以这个值不必迁就"快速轻扫"。
 */
private const val UFI_RISE_COMMIT_FRACTION = 0.25f

/**
 * 甩动判定：向上速度超过这么多 px/s，且本次手势确实向上拖过，即使行程不够也算提交。
 *
 * 900px/s ≈ 屏幕高度的 1.1 倍/秒，是一次利落的上滑；慢速拖到一半再松手仍走行程判定。
 */
private const val UFI_RISE_FLING_PX_PER_SEC = 900f

/**
 * 下拉关闭的提交阈值（占行程比例），2026-10-21 由"复用打开那档 25%"单独提出来降到 8%。
 *
 * 打开与关闭**不该共用一个数**：打开是"我要看这个"，手会往上推到底；关闭是"我看完了"，
 * 用户往下甩一小段就是在表达"够了"。原来复用 25%（约 170dp）时，手感是"拖了半屏才关" ——
 * 用户报的就是"触发条件太难"。
 */
private const val UFI_RISE_CLOSE_FRACTION = 0.08f

/**
 * 下拉关闭判定前的位移门槛（dp）。
 *
 * 系统 touch slop 是 8dp 量级，而这条手势只需要分清"想往下拖"而不是"手抖" ——
 * 4dp 就够，且换来了"一起手就跟手"的手感（见 `awaitEachGesture` 那段说明）。
 */
private val UFI_RISE_CLOSE_DRAG_SLOP = 4.dp

/** 下拉关闭的绝对位移阈值：拖过这么多就一定关（与比例阈值取"或"）。 */
private val UFI_RISE_CLOSE_MIN_DRAG = 36.dp

/**
 * 下拉关闭的甩动阈值（px/s），比打开的 900 低得多。
 *
 * 向下轻扫是很轻的动作（拇指蹭一下就 400~600px/s），按打开那档判会经常"甩了却不关"。
 */
private const val UFI_RISE_CLOSE_FLING_PX_PER_SEC = 400f

/**
 * 起手那一段的**淡入行程**（2026-10-20）。
 *
 * 面板顶边一压到进度条那条线，整条迷你条就被它盖住了。纯位移的整屏面板在头几十 dp 里
 * 读起来像"凭空贴上来的一块板"——导航那条转场为此也做了 40% 时长的淡入（见
 * `Navigation.kt` 的 `RISE_FADE_FRACTION`）。手势路径上把淡入改成**跟着拖动距离**走：
 * 拖过一个迷你条高度左右，面板完全实心，迷你条也就完全让位。
 *
 * 取 64dp（≈迷你条主体行高度）：再小会在起手几帧里"啪"地出现，再大则半透明要拖很久。
 */
internal val UFI_RISE_ENTER_FADE = 64.dp

/**
 * 落位判定阈值（px）：真封面与飞行封面在这里**硬切**交棒（2026-10-21）。
 *
 * 原先是 24px 的交叉淡入（真封面 `1 - offset/24`、飞行封面 `offset/24`），两块半透明
 * 叠在一起时整体只有 ~75% 不透明（1 - t + t²），透出的那 25% 浅色底读起来就是
 * 「落位 / 起手那一下封面闪一下」（用户报的 B、C）。
 *
 * 落位时 `progress = 1`，飞行封面正好落在播放页封面槽上 —— 两层位置尺寸**完全重合**，
 * 所以硬切看不出来，交叉淡入在这里只有副作用。两侧判据必须共用这一个值。
 */
private const val UFI_RISE_COVER_HANDOFF_PX = 0.5f

/**
 * 背景模糊的行程（2026-10-20）：拖过这么多 dp，面板身后的页面就糊到位。
 *
 * 比面板自己的淡入（[UFI_RISE_ENTER_FADE]）长得多：模糊要跟着"面板占了多少屏"走，
 * 太早糊满会读成"一按就整页毛了"。约 1/4 屏高时背景已经完全失焦，此时面板也盖住了大半。
 */
internal val UFI_RISE_BACKDROP_FADE = 220.dp

/**
 * 飞行封面"重新对位"的时长（2026-10-20）。
 *
 * 播放页的封面槽在异步内容（歌名 / 歌手 / 歌词 / 封面图）到位后可能挪动几个 dp。
 * 落点变化如果直接生效，飞行封面当场改道 ⇒ 看起来像"封面抖一下"。给它 140ms 追上去：
 * 布局稳定时这段动画不存在（目标值没变），所以没有额外开销、也没有滞后。
 */
private const val UFI_RISE_SLOT_RETARGET_MS = 140

/**
 * 判定"封面页停在原位"的容差（相对一整页的偏移量）。
 *
 * Pager 的 `currentPageOffsetFraction` 在滑动中会取 0..±1 之外的值；1% 页宽以下肉眼看不出来，
 * 但足以把"手指轻碰一下又放开"与"真的在翻页"区分开。
 */
private const val UFI_RISE_SLOT_PAGE_EPSILON = 0.01f

/**
 * 面板收起后，背景模糊图层延迟多久再摘（ms）。
 *
 * 只为一件事：别让"删图层"和"面板消失、迷你条重新露出"压在同一帧。120ms 是两三个帧，
 * 用户感知不到，但足够让露出的那一帧独占预算。
 */
private const val UFI_RISE_BACKDROP_RELEASE_MS = 120L

/**
 * 跟手升起的面板状态。生命周期跟着进程（见文件头"为什么是单例"）。
 *
 * 位移用**像素**而不是 0~1 的进度：拖动时手指走多少、面板就走多少，中间不做归一化，
 * 也就不存在"行程量得不准导致面板比手指快/慢"的问题。行程只在两处需要：
 * 松手后的提交判定与补间目标 —— 那两处调用点会把行程先写进来（见 [onDragStart]）。
 */
internal object UfiAudioRisePanel {

    /**
     * 面板的纵向位移（px）。
     *
     * 0 = 完全升起（面板铺满整窗）；`travelPx` = 面板顶边停在迷你条上沿那条线（进度条线）上，
     * 也就是"收起"状态 —— 与导航转场 `riseStartOffset` 的起点是同一个位置。
     */
    var offsetPx by mutableFloatStateOf(0f)

    /** 面板要不要出现在组合里：拖动一开始就为 true，收起动画跑完才置回 false。 */
    var visible by mutableStateOf(false)
        private set

    /** 面板是否已经"打开"（松手提交之后）。 */
    var open by mutableStateOf(false)
        private set

    /** 补间跑完的计数（单调递增）。给组合外的东西"等这一帧过去"再收尾用。 */
    var settleTick by mutableStateOf(0L)
        private set

    /** 松手后的补间目标（px）；null = 没有在跑的补间。覆盖层读它来启动动画。 */
    var settleTargetPx by mutableStateOf<Float?>(null)
        private set

    /**
     * 本次手势的行程（px），由 [onDragStart] 写入。
     *
     * 对外可读：跟手形变（[ufiRiseFollow]）要用它把位移换算成进度 0..1。
     */
    var travelPx = 0f
        private set

    /**
     * 飞行封面那张图**画出来了**的是哪一张（2026-10-20）。
     *
     * 迷你条那张封面是在 48dp 的框里解码的，飞行封面是 260dp 的框 —— Coil 的缓存键带尺寸，
     * 换尺寸就是一次新的解码，面板起手那一两帧它只能先画自己的占位（浅色底 + 音符），
     * 而同一时刻迷你条那张真封面已经被藏起来了 ⇒ 用户看到"封面闪一下"。
     *
     * 现在两边约定：**飞行封面没就绪之前，迷你条那张不藏，飞行封面自己也不画**
     * （见 [UfiRiseFlyingCover] 与 [MediaAudioMiniBar]），交接因此发生在两处完全重合的时候。
     * 就绪一次之后同一张图会留在 Coil 内存缓存里，后续手势第一帧就能画。
     *
     * 记 URL 而不是布尔：换歌之后旧的那张不算"就绪"，不需要额外的复位动作。
     */
    var flyingCoverReadyUrl by mutableStateOf<String?>(null)
        private set

    /** 飞行封面把图**画出来了**（只有非空 URL 才会调用）。 */
    fun markFlyingCoverReady(url: String) {
        if (flyingCoverReadyUrl != url) flyingCoverReadyUrl = url
    }

    /**
     * 这一张封面的飞行替身现在能不能画。
     *
     * `url == null`（这首歌没有封面）时返回 **false**：两边的占位画法不是一个尺寸
     * （迷你条是 48dp 框里的 24dp 图标，飞行封面是 260dp 框里的 56dp 图标再整体缩小），
     * 让占位飞过去会在交接那一下换一个图标大小。这种歌就不飞了 —— 迷你条那张留在原处，
     * 随面板升起被一起盖住，下一页显示它自己的占位。
     */
    fun isFlyingCoverReady(url: String?): Boolean = url != null && flyingCoverReadyUrl == url

    /**
     * 播放页那张**真封面**画出来了的是哪一张（2026-10-20）。
     *
     * 「连续打开关闭播放页时封面变白再重新加载」的收口：这一页每次打开都是新组合，
     * 封面图从 Coil 拿回来之前那块框只有 `surfaceMuted`（浅色主题近白）。以前归位那一刻
     * 飞行封面按 offset 硬切走，正好把这个空白露出来 —— 用户看到的就是"变白再加载"。
     * 现在归位后飞行封面**继续顶着**，直到真封面报出这里已就绪才交接（两者位置/尺寸一致，
     * 交接那一帧看不出来）。
     */
    var playerCoverReadyUrl by mutableStateOf<String?>(null)
        private set

    /** 播放页真封面把图**画出来了**（只有非空 URL 才会调用）。 */
    fun markPlayerCoverReady(url: String) {
        if (playerCoverReadyUrl != url) playerCoverReadyUrl = url
    }

    /** 这一张真封面现在能画了吗（见 [playerCoverReadyUrl]）。 */
    fun isPlayerCoverReady(url: String?): Boolean = url != null && playerCoverReadyUrl == url

    private var dragStartedAtMs = 0L
    private var dragDistancePx = 0f

    /**
     * 下拉关闭的**绝对位移**阈值（px），由调用点按密度换算后传进来（见 [onCloseDragStart]）。
     *
     * 为什么除了比例还要一条绝对值：比例是按"整段行程"算的，大屏上 5% 也可能是 80dp；
     * 而用户的直觉是"我往下拖了这么一小段就该关"。36dp 大约是一个拇指节的位移。
     */
    private var closeMinPx = 0f

    /**
     * 背景模糊的行程（px），由调用点按密度换算后传入（见 [onDragStart]）。
     *
     * 默认 1px 只是防除零：手势一开始就会写进真值。
     */
    private var backdropFadePx = 1f

    /**
     * 迷你条上那些"要在面板升起时飞过去"的节点，在**窗口坐标**里的位置。
     *
     * 为什么是 State 而 [hosted] 是普通 `var`：这里的读者是**组合**（面板里那个封面节点
     * 要知道自己该从哪飞来），迷你条布局一变就得让它重读一次；而 [hosted] 的读者是手势 lambda。
     * 写入点在迷你条的 `onGloballyPositioned`，只在布局真的变化时才发生，不会逐帧重建。
     */
    var followSources by mutableStateOf<Map<String, Rect>>(emptyMap())
        private set

    /** 迷你条上报自己某个节点的窗口位置（见 [followSources]）。 */
    fun reportFollowSource(id: String, bounds: Rect) {
        if (followSources[id] == bounds) return
        followSources = followSources + (id to bounds)
    }

    /** 面板侧读起点位置。 */
    fun followSource(id: String): Rect? = followSources[id]

    /**
     * 播放页那个封面槽在窗口里的矩形（飞行封面的**终点**），已换算成"面板归位时"的位置。
     *
     * 换算必须用**量到那一刻**的位移，不能减当下的：`onGloballyPositioned` 什么时候重跑
     * （面板每帧位移算不算"位置变化"）在 Compose 各版本上并不一致，配上那一刻的位移之后
     * 两种情况都对。
     */
    var coverSlot by mutableStateOf<Rect?>(null)
        private set

    fun reportCoverSlot(bounds: Rect, offsetAtMeasure: Float) {
        val rest = bounds.translate(0f, -offsetAtMeasure)
        if (coverSlot == rest) return
        coverSlot = rest
    }

    /**
     * 覆盖层自己在窗口里的原点。飞行封面用窗口坐标插值，落到层内要减掉它。
     *
     * 用 `Offset.Zero` 起步：没量到之前飞行封面会按窗口坐标摆放（偏一段），
     * 而那一刻它本来就还在迷你条附近、且下一帧就会被纠正，观感上看不出来。
     */
    var overlayOrigin by mutableStateOf(Offset.Zero)
        private set

    fun reportOverlayOrigin(origin: Offset) {
        if (overlayOrigin == origin) return
        overlayOrigin = origin
    }

    /**
     * 屏上**有没有**页内面板宿主（挂着迷你条的三个页面之一正在组合）。
     *
     * 迷你条据此决定"上滑"是拉起面板还是回退到导航那条路：没有宿主时驱动器就是空转，
     * 上滑会变成什么都不发生 —— 那是静默失效，比走导航糟得多。
     *
     * 普通 `var` 而不是 `MutableState`：读者是手势 lambda（组合之外），不需要建立
     * snapshot 依赖（与 `UfiMiniPlayerBarMetrics` 同一条理由）。写入者见 [MediaAudioRisePanel]。
     */
    var hosted: Boolean = false

    /** 屏上正在组合的宿主数量，见 [hosted]。 */
    private var hostCount: Int = 0

    /** 宿主进入组合（见 [MediaAudioRisePanel] 里那段登记）。 */
    fun onHostAttached() {
        hostCount++
        hosted = true
    }

    /** 宿主离开组合。计数归零才认为"这一页没有面板可用"。 */
    fun onHostDetached() {
        hostCount = (hostCount - 1).coerceAtLeast(0)
        hosted = hostCount > 0
    }

    /**
     * 手指按下（越过 touch slop 之后）。[travelPx] 由调用点量出来并传进来。
     *
     * 为什么行程要由调用点给：面板此刻还没进组合（它就是被这次拖动唤起来的），量不到自己的
     * 位置；而迷你条在窗口里，能量到"它上沿（进度条那条线）到窗口顶"的距离 —— 那正是面板
     * 顶边要走的行程（与导航转场 `riseStartOffset` 同一条线）。
     */
    fun onDragStart(travelPx: Float, backdropFadePx: Float) {
        this.travelPx = travelPx.coerceAtLeast(1f)
        this.backdropFadePx = backdropFadePx.coerceAtLeast(1f)
        // 收起状态下每次手势都从"顶边停在进度条那条线上"起步：上一次回弹可能还没跑完就被
        // 再次按下，不钉这一下就会从半途接着走。
        if (!open) offsetPx = this.travelPx
        // 上一次松手留下的补间要**当场取消**：否则它会和手指抢着写 offsetPx
        //（覆盖层那条 LaunchedEffect 以它为 key，置 null 就会取消并重跑一条空转的）。
        settleTargetPx = null
        // 真封面的"已就绪"要按**这一次**打开重新确认（2026-10-20）：页面是新组合的，
        // Coil 内存缓存万一被回收，上一轮那个 true 会让飞行封面提前撤走、露出近白底。
        //
        // ★ 2026-10-21：只在 `open == false`（页面这次真的会重新组合）时清。从已停好的面板
        //   往下拖关闭时页面没变，清了会让真封面那层当场消失 —— 与"还在飞行途中"的替身
        //   叠在一起就是一帧空白（用户报的 C：往下拖起手那一下闪）。
        if (!open) playerCoverReadyUrl = null
        syncBackdrop()
        dragStartedAtMs = System.currentTimeMillis()
        dragDistancePx = 0f
    }

    /** 手指移动了 [dyPx]（屏幕坐标，向上为负）。 */
    fun onDrag(dyPx: Float) {
        dragDistancePx += -dyPx
        // 向上 = offset 变小 = 面板露出来。向下拖到行程以外会被 clamp 吃掉：面板不动。
        val next = (offsetPx + dyPx).coerceIn(0f, travelPx)
        offsetPx = next
        // 只有真的露出来才进组合（原地按住不动、或只往下拖，都不该拉起整个播放页）
        if (next < travelPx) {
            visible = true
            // 背景页的模糊图层只在面板真的露出来时挂：横向切歌那条手势也会走 onDragStart，
            // 不能在那里点亮它（否则会留下一层永远不摘的离屏图层）。
            if (!UfiPageBackdrop.active) UfiPageBackdrop.active = true
        }
        syncBackdrop()
    }

    /**
     * 把当前位移换算成背景页的模糊进度（0..1）。
     *
     * 写入点在"位移真的变了"的地方：[onDrag]、覆盖层那条补间动画、以及 [reset]/[onSettleFinished]。
     * 页壳在绘制期读它，所以逐帧写不产生重组。
     */
    fun syncBackdrop() {
        UfiPageBackdrop.progress =
            ((travelPx - offsetPx) / backdropFadePx).coerceIn(0f, 1f)
    }

    /** 松手：按"走够了"或"甩得够快"决定落位还是回弹。 */
    fun onDragEnd() {
        // 这一下没让面板露出来（只往下拖、或几乎没动）：不启动任何补间。
        // 也正因为这里不写 settleTargetPx，"没进组合时留下一个陈旧目标"这条坑不存在。
        if (!visible) {
            clearBackdrop()
            return
        }
        val elapsedMs = (System.currentTimeMillis() - dragStartedAtMs).coerceAtLeast(1L)
        val velocity = dragDistancePx / elapsedMs * 1000f
        val progress = 1f - offsetPx / travelPx
        val commit = progress >= UFI_RISE_COMMIT_FRACTION ||
            (velocity >= UFI_RISE_FLING_PX_PER_SEC && dragDistancePx > 0f)
        settle(commit)
    }

    /** 立刻收起（返回键 / 顶栏返回）。 */
    fun close() = settle(false)

    /**
     * 手势被上层抢走（转场开始、父级 consume 掉这一串事件等）：**一律回弹**，不判提交 ——
     * 用户还没松手就丢了事件，说明这一次手势没走完，当"取消"最符合直觉。
     *
     * 没有这一条的话，面板会**卡在半空**：offsetPx 停在手指最后的位置，而没有任何东西
     * 会把它推回 0 或推到行程尽头。
     */
    fun onDragCancel() {
        if (visible) settle(false) else clearBackdrop()
    }

    /*
     * ── 播放页内的**下拉关闭**（2026-10-21）────────────────────────────────
     *
     * 打开是"迷你条上划"，关闭此前只有返回键 / 顶栏返回。用户要的是同一个手势的反向：
     * 在播放页里往下拖，面板跟着落回迷你条。
     *
     * 复用同一套状态（[offsetPx] / [travelPx] / [settle]），所以位移、压暗、背景模糊、
     * 末段淡出**一条代码都不用重写** —— 差别只在判定方向：上划是"进度够多就算提交"，
     * 下拖是"退出行程够多就算关掉"。
     */

    /** 下拉关闭的起点：面板已经停好（[open]）才起效，否则这次手势没意义。 */
    fun onCloseDragStart(minClosePx: Float = 0f) {
        if (!open) return
        // 上一次松手留下的补间要当场取消：否则它会和手指抢着写 offsetPx
        settleTargetPx = null
        dragStartedAtMs = System.currentTimeMillis()
        dragDistancePx = 0f
        this.closeMinPx = minClosePx
        // 背景页要跟着"褪糊"：面板往下走、下面的列表露出来，模糊随进度回落
        if (!UfiPageBackdrop.active) UfiPageBackdrop.active = true
        syncBackdrop()
    }

    /** 下拉关闭松手：比例、绝对位移、向下甩动，**三者任一**满足就关掉。 */
    fun onCloseDragEnd() {
        val elapsedMs = (System.currentTimeMillis() - dragStartedAtMs).coerceAtLeast(1L)
        // onDrag 里攒的是 -dy，向下为正；甩动判定要的正是"向下"这个方向
        val downPx = -dragDistancePx
        val velocity = downPx / elapsedMs * 1000f
        val closedRatio = if (travelPx > 0f) offsetPx / travelPx else 1f
        val close = closedRatio >= UFI_RISE_CLOSE_FRACTION ||
            (closeMinPx > 0f && offsetPx >= closeMinPx) ||
            (velocity >= UFI_RISE_CLOSE_FLING_PX_PER_SEC && downPx > 0f)
        settle(!close)
    }

    /** 下拉途中被上层抢走（父级 consume / 转场开始）：**弹回打开态**，与上划那条相反的默认。 */
    fun onCloseDragCancel() = settle(true)

    /**
     * **点按迷你条**打开面板（2026-10-21）。
     *
     * 此前"上划"走页内面板、"点按"走导航路由，两条路能力不一致：导航那条不在面板里，
     * 于是"点开之后下拉关闭"什么都不会发生（用户报的就是这个）。有宿主时让点按也走面板，
     * 两个入口合流，手势能力（含下拉关闭、飞行封面、背景模糊）完全一致。
     *
     * 起点与上划同一条线：迷你条上沿（= 进度条那条线），所以观感就是"面板从条上长出来"，
     * 只是这次由一次补间代劳，而不是跟手。
     */
    fun openByTap(travelPx: Float, backdropFadePx: Float) {
        // 已经在升起 / 已经打开：不重复启动（连点两下不该把补间重启一次）
        if (open || visible) return
        this.travelPx = travelPx.coerceAtLeast(1f)
        this.backdropFadePx = backdropFadePx.coerceAtLeast(1f)
        // 页面是**新组合**的：真封面的"已就绪"要重新确认（与 [onDragStart] 同一条理由）
        playerCoverReadyUrl = null
        offsetPx = this.travelPx
        visible = true
        UfiPageBackdrop.active = true
        syncBackdrop()
        // 补间到 0 = 打开到位；到位后 [onSettleFinished] 负责收尾（含延迟摘模糊图层）
        settle(true)
    }

    /** 背景模糊图层收工（面板没露出来 / 宿主没了 / 补间到位，都走这里）。 */
    private fun clearBackdrop() {
        UfiPageBackdrop.active = false
        UfiPageBackdrop.progress = 0f
    }

    /** 覆盖层把补间跑完时回调：收起的话把面板移出组合。 */
    fun onSettleFinished() {
        settleTargetPx = null
        if (open) {
            /*
             * 打开到位时**先不摘**背景模糊图层（2026-10-20）。
             *
             * 摘图层会让页壳重组一次；而这一帧面板自己刚跑完最后一段补间 —— 两件事挤在同一帧
             * 里，正好是用户报的"归位时抖一下"最容易出现的位置。此时 progress 已是 0（不糊），
             * 图层留着也不影响观感，等这一帧过去再摘（见下方 settleTick 那条收尾）。
             */
            UfiPageBackdrop.progress = 0f
        } else {
            /*
             * 收起同理，只是反了个方向（2026-10-20）：这一帧是"面板消失、迷你条重新露出来"，
             * 页壳若同帧删图层，正好和露出来的那一帧抢预算 —— 用户读到的就是"退出时抖一下"。
             * 所以这里只把进度归零，图层交给组合里那条延迟 120ms 的收尾（见 [releaseBackdrop]）。
             */
            visible = false
            UfiPageBackdrop.progress = 0f
        }
        // 落位 / 收起两个方向都通知一次：读者据此"等两三帧"再摘背景图层。
        settleTick += 1
    }

    /**
     * 收起动画结束、面板已经消失之后**再晚一点**摘背景模糊图层。
     *
     * 由 `MediaAudioRisePanel` 里那条 `LaunchedEffect` 延迟调用（它盯着 [settleTick]）：
     * 那一帧页壳重组（删图层）不会和"面板落位 / 面板消失、迷你条重新出现"挤在一起。
     */
    fun releaseBackdrop() = clearBackdrop()

    /** 宿主页面离开组合时清空（理由见 [MediaAudioRisePanel] 里那段 onDispose）。 */
    fun reset() {
        settleTargetPx = null
        visible = false
        open = false
        offsetPx = 0f
        // 起点位置与封面槽也一起清：留着上一页那一张封面的矩形，下一页第一次拖动会从别人的位置飞过来
        followSources = emptyMap()
        coverSlot = null
        // 背景页的效果属于"这一次手势"，宿主没了必须一起收 —— 否则下一页会带着一层糊
        clearBackdrop()
    }

    private fun settle(commit: Boolean) {
        open = commit
        settleTargetPx = if (commit) 0f else travelPx
    }
}

/**
 * 跟手面板本体：挂在页壳的 `overlay` 槽上（覆盖整屏，含状态栏区域）。
 *
 * 没在拖动也没打开时**整块不组合** —— 面板里是完整的播放页（歌词拉取、封面预取、队列回读），
 * 常驻组合的代价远大于它换来的"首帧快一点"。所以拖动第一下会带上播放页的首次组合，
 * 这一点与导航那条路完全一样（导航也是转场开始才组合目的地）。
 */
@Composable
internal fun MediaAudioRisePanel(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    /*
     * 宿主登记：这个 composable 由页壳的 `overlay` 槽**无条件**组合（它自己会早退），
     * 所以"它被组合着"就等于"这一页有面板可用"。迷你条据此决定上滑的去向。
     *
     * 用计数而不是布尔：转场期间两页会同时在树上（音乐列表 ↔ 歌单详情），
     * 后离场那一条的 onDispose 晚于新页的进入，布尔会被归零。
     */
    DisposableEffect(Unit) {
        UfiAudioRisePanel.onHostAttached()
        onDispose {
            UfiAudioRisePanel.onHostDetached()
            /*
             * 宿主没了就把面板状态一起清掉。
             *
             * 不清的后果：面板开着的时候从别处（通知栏 / 深链接）跳到播放页路由，本页被销毁，
             * 而单例里 `visible` 还是 true —— 用户从播放页返回时会**又看到一遍播放页**，
             * 得再按一次返回才回到列表。清掉之后"面板属于托管它的那一页"这条语义才成立。
             *
             * 代价：旋屏等配置变更会重建整棵树，面板随之收起（走导航那条路不会）。
             * 这一条比"返回后看到两次播放页"轻得多，接受。
             */
            UfiAudioRisePanel.reset()
        }
    }

    /*
     * 补间跑完之后**晚一点**再摘背景模糊图层（2026-10-20）。
     *
     * 摘图层会让页壳重组一次；如果和"面板落位 / 面板消失、迷你条重新露出来"压在同一帧上，
     * 用户读到的就是那一下抖。图层本身进度早已归零（不糊），晚收不影响观感。
     *
     * ★ 现在两个方向都收（原来只收收起那一支）：打开到位之后面板已完全盖住本页，
     *   背景页再挂一层整页离屏合成纯属白花 —— 而且面板里的播放页现在是它自己的树。
     *   改成看 settleTick 之后，这条 effect 也不再依赖"面板在不在组合里"的分支，
     *   写在早退之前只为了活过收起（收起后本 composable 只剩宿主登记）。
     */
    LaunchedEffect(Unit) {
        snapshotFlow { UfiAudioRisePanel.settleTick }.collect {
            // 落位/收起那一帧别动图层，等两三帧的预算空出来再摘
            delay(UFI_RISE_BACKDROP_RELEASE_MS)
            UfiAudioRisePanel.releaseBackdrop()
        }
    }

    if (!UfiAudioRisePanel.visible) return

    val palette = LocalResolvedPalette.current
    val mediaId = UfiNowPlayingState.mediaId.value
    /*
     * 没有正在播的东西就没有面板可言（正常路径下也拖不起来：迷你条本身不显示）。
     *
     * ★ 2026-10-20：这里必须**收尾**而不是直接 return。面板打开期间队列可以被清空
     *   （播放页/迷你条里的队列浮层删掉最后一首 → `ufiRemoveFromQueue` 会 stop + clear），
     *   那一刻 mediaId 变空、本 composable 早退，但单例里的 `visible/open/offsetPx` 还留着。
     *   只早退的后果：下次开始播歌时，整页播放页会**无动画地**盖回音乐列表（用户看到
     *   "播放页突然闪出来"），而且这期间返回键那一层拦截是缺失的。
     *   清理走 reset()：它同时清掉飞行封面的起点与槽位，理由见那个函数。
     */
    if (mediaId.isBlank()) {
        LaunchedEffect(Unit) { UfiAudioRisePanel.reset() }
        return
    }

    // 面板在场时返回键只关面板。播放页自己那条 BackHandler 管的是队列浮层，层级更内，
    // 浮层开着时它会先吃掉返回键 —— 这正是想要的优先级。
    BackHandler { UfiAudioRisePanel.close() }

    // 顶栏返回与系统返回同一个语义；**记住**这个 lambda：面板重组时它应当保持不变，
    // 否则里面那页会一直判定"参数变了"而重组（关闭动画起步那一帧尤其吃不起）。
    val panelBack: () -> Unit = remember { { UfiAudioRisePanel.close() } }

    /*
     * 补间在组合里跑，状态对象自己不持有协程作用域：动画的宿主必须跟着组合生灭，
     * 否则页面销毁之后还有一条协程在写状态。
     *
     * ★ 2026-10-20 六修：目标值改成 **snapshotFlow 观察**，不在组合里读 `settleTargetPx`。
     *   原来 `val settleTarget = ...` 是组合期读：松手 / 按下返回那一刻目标一变，整个面板
     *   （以及它怀里的整页播放页）就重组一次 —— 而"按下返回的那一帧"正是用户报的抖动点。
     *   改成流之后，目标变化只推动这条协程，面板不重组、播放页更不会。
     *   用 `collectLatest`：目标被新的一次覆盖时，上一条补间会被取消（与原来 key 变化的语义一致）。
     */
    LaunchedEffect(Unit) {
        snapshotFlow { UfiAudioRisePanel.settleTargetPx }.collectLatest { settleTarget ->
            if (settleTarget == null) return@collectLatest
            animate(
                initialValue = UfiAudioRisePanel.offsetPx,
                targetValue = settleTarget,
                animationSpec = tween(
                    // 提交要走完剩下的行程，略长一点；回弹只是把已经走过的路还回去，可以更快
                    durationMillis = if (settleTarget == 0f) {
                        UfiMotion.Duration.Gentle
                    } else {
                        UfiMotion.Duration.Standard
                    },
                    easing = UfiMotion.Easing.Standard
                )
            ) { value, _ ->
                UfiAudioRisePanel.offsetPx = value
                // 背景模糊与面板位移同一条时间线（收起方向也要跟着回到 0）
                UfiAudioRisePanel.syncBackdrop()
            }
            UfiAudioRisePanel.onSettleFinished()
        }
    }

    /*
     * 整块浮层自己一个根 Box（2026-10-14）：
     * · 它是**飞行封面**的坐标原点 —— 封面用窗口坐标插值，落到这一层要减掉本层的窗口原点；
     * · 它也是"覆盖层"的边界，飞行封面画在这里就绕开了 Pager 的裁剪（见 [UfiRiseFlyingCover]）。
     */
    Box(
        modifier = Modifier
            .fillMaxSize()
            /*
             * 面板是**模态**的：覆盖层只是"最后绘制"，它本身不会拦触摸。而面板里那页的
             * 标题栏没有 pointerInput（只有返回键/actions 是 clickable），所以点标题栏空白处
             * 会**穿过**面板落到下面音乐页的标题栏上 —— 位置恰好对上「播放全部 / 随机」
             * 那一排时，ufiNavigateOnce 会真的 push 出导航播放页，于是「面板 + 播放页」
             * 同屏，转场结束时面板再"啪"地消失。
             *
             * 这里挂一个"只收不发"的 pointerInput：命中测试里它是这一层的直接命中，会挡住
             * 下面的兄弟节点；面板自己的子节点不受影响（Main pass 子先父后，按钮/列表/手势
             * 照常）。它不消费事件，所以面板内部原有交互一个字节都没改。
             */
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            }
            .onGloballyPositioned {
                UfiAudioRisePanel.reportOverlayOrigin(it.positionInWindow())
            }
    ) {
        /*
         * 下层压暗：与导航转场（`ufiSharedAxisLayer`）用同一套 token。
         *
         * 幅度是**面板行程的进度**的纯函数，所以拖动过程中它跟着手指加深，不需要第二条动画。
         * 分母必须是 [UfiAudioRisePanel.travelPx]（= 进度条那条线到窗口顶），不是本节点自己的
         * 高度：2026-10-20 把行程从"整窗高"改成"到迷你条上沿"之后，两者不再相等 ——
         * 继续用 height 的话，起手第一帧 scrim 就从 0 跳到 ≈11%（收起态也永远归不了零），
         * 每次起手闪一下暗。
         */
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val travel = UfiAudioRisePanel.travelPx
                    alpha = if (travel > 0f) {
                        (1f - UfiAudioRisePanel.offsetPx / travel).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
                .background(palette.scrim.copy(alpha = UfiMotion.NavRecede.ScrimAlpha))
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                /*
                 * 播放页里**往下拖 = 关闭面板**（2026-10-21）。
                 *
                 * 为什么挂在 Main pass 而不做手势仲裁：内层能纵向滚动的地方（歌词页那个列表）
                 * 会先把手势吃掉，这里根本收不到 —— 那正是想要的优先级，一行仲裁代码都不用写。
                 * 封面页没有纵向滚动容器，纵向下拖一定会落到这里。
                 *
                 * 只认"往下"：往上拖在这个页面没有语义（面板已经在顶），放行给子节点。
                 */
                .pointerInput(Unit) {
                    /*
                     * 手写这一条而不是用 `detectVerticalDragGestures`（2026-10-21 二次调整）。
                     *
                     * 那个帮助函数用的是系统 touch slop（8dp 量级）且只在**越过 slop 之后**才回调
                     * `onDragStart`，再加上"必须由父层仲裁才轮到"的时序，实测就是"要很有意识地
                     * 往下划才认"（用户："下拉更难触发了"）。这里自己数位移，slop 收到 4dp，
                     * 一旦够了立刻接管并 consume，手势一起手就跟手。
                     *
                     * 仍然是**只认向下**：向上那一侧直接放手（`return@awaitEachGesture`），
                     * 交给内层（歌词列表 / Pager）自己处理。
                     */
                    val slopPx = UFI_RISE_CLOSE_DRAG_SLOP.toPx()
                    val minDragPx = UFI_RISE_CLOSE_MIN_DRAG.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalY = 0f
                        var claimed = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dy = change.position.y - change.previousPosition.y
                            totalY += dy
                            if (!claimed) {
                                if (totalY > slopPx) {
                                    claimed = true
                                    UfiAudioRisePanel.onCloseDragStart(minDragPx)
                                } else if (totalY < -slopPx) {
                                    // 向上：这一页没有"再往上"的语义，整个手势让给子节点
                                    return@awaitEachGesture
                                } else {
                                    continue
                                }
                            }
                            change.consume()
                            // 向下 = offsetPx 变大 = 面板落回迷你条（onDrag 内部已 clamp 到行程内）
                            UfiAudioRisePanel.onDrag(dy)
                        }
                        if (claimed) UfiAudioRisePanel.onCloseDragEnd()
                    }
                }
                .graphicsLayer {
                    translationY = UfiAudioRisePanel.offsetPx
                    /*
                     * 2026-10-20：起手那段用 alpha 把「迷你条 → 整页」接起来。
                     *
                     * 面板顶边压在进度条那条线上之后，下面那条迷你条会被它整块盖住 ——
                     * 纯位移的话起手第一帧就是"条没了、换成一页的顶边"，用户读到的正是
                     * 「刚上滑时突然变一下」。这里让面板在**拖过一个迷你条高度**的过程里
                     * 从透明到实心：迷你条的内容在这段里逐渐透出来再消失，封面则交给
                     * 飞行封面接着（见 [UfiRiseFlyingCover]），两件事不会同时跳。
                     *
                     * 收起方向的最后 [UFI_RISE_ENTER_FADE] 同样会淡出，与导航那条的
                     * `risePanelPopExit`（末段 40% 淡出）是同一套语言。
                     */
                    val travel = UfiAudioRisePanel.travelPx
                    val fadePx = UFI_RISE_ENTER_FADE.toPx()
                    alpha = if (travel <= 0f || fadePx <= 0f) {
                        1f
                    } else {
                        ((travel - UfiAudioRisePanel.offsetPx) / fadePx).coerceIn(0f, 1f)
                    }
                }
        ) {
            CompositionLocalProvider(LocalUfiInsideRisePanel provides true) {
                MediaAudioPlayerScreen(
                    viewModel = viewModel,
                    navController = navController,
                    filePath = mediaId,
                    // 顶栏返回关面板，而不是把音乐页弹掉。
                    // 用 remember 记住这个 lambda：面板重组时它不该变，否则整页播放页会被判定
                    // "参数变了"而跟着重组（那一页很重，落在关闭动画起步那一帧就是一次可见的顿挫）。
                    onBack = panelBack,
                    // 见文件头"代价 2"：这条路径不参与共享元素转场
                    shareElements = false,
                    // 封面交给覆盖层里那张"飞行封面"（播放页那张在 Pager 里，会被裁）
                    riseFollow = true
                )
            }
        }

        // 画在最后 = 压在面板之上。归位（offset == 0）时它自己淡出，把位置交还给播放页那张。
        UfiRiseFlyingCover()
    }
}

/**
 * 跟着手指飞的封面（2026-10-14）。
 *
 * ## 为什么必须画在覆盖层，而不是直接变形播放页那张
 * 播放页的封面挂在 `HorizontalPager` 里，而 Pager 内部会 `clipScrollableContainer`
 * （见 foundation 的 `ScrollableArea.kt`）—— 封面一离开 Pager 的矩形，也就是往迷你条
 * 那边飞的时候，就被**裁掉**。观感就是"封面不见了"。
 * 覆盖层在 Pager 之外、不裁剪，所以这一张画在这里。
 *
 * ## 位置怎么来的
 * 起点是迷你条上报的那张封面的窗口矩形，终点是播放页封面槽的窗口矩形（由
 * [ufiRiseCoverSlot] 上报，且已换算成"面板归位时"的位置）。按面板进度插值之后，
 * 减掉本层自己的窗口原点就得到层内坐标，再用 `graphicsLayer` 的 scale + translation 摆过去。
 *
 * ## 什么时候不画
 * 面板完全归位（`offsetPx == 0`）时它淡出、播放页那张淡入 —— 静止时显示的是**真实**那张，
 * 位置由布局保证，不依赖这里的换算精度。
 */
@Composable
private fun UfiRiseFlyingCover() {
    val palette = LocalResolvedPalette.current
    val artwork = UfiNowPlayingState.artworkUrl.value
    val slot = UfiAudioRisePanel.coverSlot
    /*
     * 落点平滑（2026-10-20）：播放页那一屏里有几样东西是**异步**才到位的（歌名 / 歌手 /
     * 歌词预览 / 封面图）。只要其中任何一样在拖动途中把封面槽挪了几 dp，直接把新落点喂给
     * 插值就会让飞行封面当场改道 —— 观感正是"封面抖一下"（慢拖时最明显，每一帧都看得清）。
     *
     * 这里让落点用 [UFI_RISE_SLOT_RETARGET_MS] 追上目标：
     * · 布局稳定时它就是常量 ⇒ 不跑动画、不重组，与之前逐字节相同；
     * · 真的变了也只是一次很轻的重新对位，而不是跳变。
     * 第一份落点必须**直接采用**（snapTo）：否则飞行封面会从"原点 + 0 尺寸"长到目标上。
     */
    val slotLeft = remember { Animatable(0f) }
    val slotTop = remember { Animatable(0f) }
    val slotWidth = remember { Animatable(0f) }
    val slotHeight = remember { Animatable(0f) }
    var slotSeen by remember { mutableStateOf(false) }
    LaunchedEffect(slot) {
        val s = slot ?: return@LaunchedEffect
        if (!slotSeen) {
            slotLeft.snapTo(s.left)
            slotTop.snapTo(s.top)
            slotWidth.snapTo(s.width)
            slotHeight.snapTo(s.height)
            slotSeen = true
        } else {
            val spec = tween<Float>(
                durationMillis = UFI_RISE_SLOT_RETARGET_MS,
                easing = UfiMotion.Easing.Standard
            )
            launch { slotLeft.animateTo(s.left, spec) }
            launch { slotTop.animateTo(s.top, spec) }
            launch { slotWidth.animateTo(s.width, spec) }
            launch { slotHeight.animateTo(s.height, spec) }
        }
    }
    Box(
        modifier = Modifier
            .size(AUDIO_COVER_SIZE)
            .graphicsLayer {
                val from = UfiAudioRisePanel.followSource(UFI_RISE_FOLLOW_COVER)
                val travel = UfiAudioRisePanel.travelPx
                val offset = UfiAudioRisePanel.offsetPx
                val raw = slot
                if (from == null || raw == null || travel <= 0f) {
                    alpha = 0f
                    return@graphicsLayer
                }
                // 第一份落点还没被 snap 进来时直接用原始值（也就第一帧，避免从 0 长出来）
                val toLeft = if (slotSeen) slotLeft.value else raw.left
                val toTop = if (slotSeen) slotTop.value else raw.top
                val toWidth = if (slotSeen) slotWidth.value else raw.width
                val toHeight = if (slotSeen) slotHeight.value else raw.height
                // 2026-10-20（回退）：恢复「迷你条 → 播放页」插值 —— 上一版让封面直接跟着
                // 面板位移，结果迷你条那张封面在拖动一开始就消失，观感像"封面丢了"。
                // 这里重新从迷你条矩形出发，进度与面板一致（线性），末段淡出交给播放页那张。
                val progress = (1f - offset / travel).coerceIn(0f, 1f)
                val origin = UfiAudioRisePanel.overlayOrigin
                val left = (from.left + (toLeft - from.left) * progress) - origin.x
                val top = (from.top + (toTop - from.top) * progress) - origin.y
                /*
                 * 2026-10-20 二次修正：删掉 `top = maxOf(rawTop, offset)` 那道钳制。
                 *
                 * 它是"行程 = 整窗高"时打的一个补丁：那时插值起点（迷你条封面 y）比面板顶边
                 * （offset = 整窗高）低一整截，rawTop 永远比 offset 小，封面会飘到面板上面去，
                 * 所以被硬钳在面板顶边 —— 表现就是"封面按下先消失、再从屏幕底边贴边上滑"。
                 *
                 * 行程改成 [UfiAudioRisePanel.travelPx]（进度条那条线）之后，起点天然落在
                 * 面板内部（面板顶边 = offset ≤ travel，而 from.top 在行程尽头附近），钳制永不
                 * 触发；留着它只会在行程量偏小时重新把封面钉在面板上沿。位置全部交给上面的插值。
                 */
                val width = from.width + (toWidth - from.width) * progress
                val height = from.height + (toHeight - from.height) * progress
                scaleX = width / size.width
                scaleY = height / size.height
                translationX = (left + width / 2f) - size.width / 2f
                translationY = (top + height / 2f) - size.height / 2f
                /*
                 * 圆角按"屏幕上的等效半径"插值（2026-10-20 四修）。
                 *
                 * 两边对不上过两次：最早迷你条写死 8dp、飞行封面是 20dp 图层缩放；
                 * 后来把迷你条改成"20dp × 44/260 ≈ 3.4dp"去迁就它，结果这一路缩下去圆角
                 * 只有 3dp，交接那一下看起来就是"圆角丢了、变成直角"。
                 *
                 * 现在反过来：起点用迷你条真实圆角（[MINI_BAR_COVER_CORNER]）、终点用播放页
                 * 真实圆角（[AUDIO_COVER_CORNER]），中间线性插值。图层自己的 clip 在**缩放之前**
                 * 的空间里生效，所以半径要先除以 scale 才是屏幕上的等效值。
                 */
                val scale = (width / size.width).coerceAtLeast(0.0001f)
                val screenRadius = lerp(
                    MINI_BAR_COVER_CORNER.toPx(),
                    AUDIO_COVER_CORNER.toPx(),
                    progress
                )
                shape = RoundedCornerShape((screenRadius / scale).toDp())
                clip = true
                /*
                 * 飞行封面自己也长一层阴影，按进度的平方淡入（2026-10-20 五修）。
                 *
                 * 起点贴在迷你条上（那张没有阴影）、终点落在播放页封面槽（那张有
                 * [AUDIO_COVER_SHADOW]）。两边对称之后，**退出**时就不会再出现
                 * "真封面带着阴影淡出、替身没阴影"那一下明暗跳变 —— 用户读到的就是"抖一下"。
                 * 平方而不是线性：起手贴条那一段几乎不要阴影，越接近落位越像真封面。
                 */
                shadowElevation =
                    (AUDIO_COVER_SHADOW.toPx() * progress * progress / 3f).roundToInt() * 3f
                /*
                 * 什么时候画这张替身（2026-10-20 三修 / 四修微调）：
                 * ① 它自己那张图还没画出来 → 不画（迷你条那张替它顶着，见 MediaAudioMiniBar）；
                 * ② 归位（offset≈0）之后才知道：播放页那张真封面**画出来了**才交棒 —— 没画出来
                 *    之前继续顶着（此时 progress=1，这一张正好落在封面槽上，两层完全重合），
                 *    否则会把真封面那块近白底露出来（"封面变白再重新加载"）。
                 * 交棒是**落位那一刻硬切**（2026-10-21 回退了 24px 交叉淡入，理由见
                 * [UFI_RISE_COVER_HANDOFF_PX]）：两层在落位时完全重合，硬切看不出来；
                 * 交叉淡入会让两块半透明叠出 25% 的透底 —— 那就是"封面闪一下"。
                 */
                alpha = when {
                    !UfiAudioRisePanel.isFlyingCoverReady(artwork) -> 0f
                    // 真封面已就绪：落位前一直由替身实心顶着，落位即交棒，中间不留半透明段。
                    UfiAudioRisePanel.isPlayerCoverReady(artwork) ->
                        if (offset <= UFI_RISE_COVER_HANDOFF_PX) 0f else 1f
                    else -> 1f
                }
            }
            .background(palette.surfaceMuted),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(56.dp)
        )
        artwork?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // 就绪信号：迷你条那张封面要等这一张真的画出来了才让位（见 isFlyingCoverReady）
                onSuccess = { UfiAudioRisePanel.markFlyingCoverReady(url) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 标记"播放页那张封面"的槽位（2026-10-14 / 2026-10-20 拆成两半）：把它的窗口矩形报给覆盖层，
 * 并在面板升起期间把自己藏起来。
 *
 * ## 为什么不是直接变形自己
 * 上一版就是直接给这个节点加 `graphicsLayer` 做插值，两个问题：
 * 1. **被 Pager 裁掉**。封面在 `HorizontalPager` 里，Pager 内部 `clipScrollableContainer` ——
 *    往迷你条方向飞出去的部分直接被裁，表现是"封面丢了"。所以真正在飞的是覆盖层里那张
 *    （见 [UfiRiseFlyingCover]），这里只负责报位置 + 让位。
 * 2. **位移算错**。`boundsInWindow()` 量到的是"此刻屏幕上"的位置，用它当锚点去算 translation
 *    时，只要测量没跟着面板每帧重跑，静止态就会整体偏掉一个屏高（封面看起来不见了）。
 *    现在这里只上报、不变形，位置全部由覆盖层那张按插值算，没有这类时序依赖。
 *
 * ## 为什么只在"封面页停在原位"时上报（2026-10-20 五修）
 * 这个槽位在 `HorizontalPager` 里 —— 用户左右划"封面 / 歌词"时，它的窗口坐标**跟着一页宽
 * 左右跑**，而且 `onGloballyPositioned` 每帧都会重跑。如果照单全收：
 * · 飞行封面的落点被改成滑动中的临时位置（x 偏出最多一页宽），退出时按这个错误落点飞 ⇒ 抖；
 * · 每一帧都写一次 [UfiAudioRisePanel.coverSlot]，迷你条与飞行封面跟着逐帧重组、
 *   140ms 落点补间被逐帧重启 ⇒ 滑动本身掉帧（用户报的"手动划切换时特别明显"）。
 * 现在加两道闸：只有 `currentPage` 是封面页、且 `currentPageOffsetFraction` 基本为 0 才上报。
 * 分页滑动期间保持上一次"停在封面页"时的落点 —— 那也是封面槽唯一的静止位置。
 */
internal fun Modifier.ufiRiseCoverSlotReport(
    pagerState: PagerState,
    coverPage: Int
): Modifier =
    this.onGloballyPositioned {
        if (pagerState.currentPage != coverPage) return@onGloballyPositioned
        if (abs(pagerState.currentPageOffsetFraction) > UFI_RISE_SLOT_PAGE_EPSILON) {
            return@onGloballyPositioned
        }
        UfiAudioRisePanel.reportCoverSlot(
            // clipBounds = false：这个槽在 Pager 里，默认那版会把矩形裁到 Pager 的边界上，
            // 拿它当终点会让封面少飞一截（封面本来就要飞出 Pager）
            bounds = it.boundsInWindow(clipBounds = false),
            offsetAtMeasure = UfiAudioRisePanel.offsetPx
        )
    }

/**
 * 升起期间把真封面让给飞行封面（见 [ufiRiseCoverSlotReport] 的说明）。
 *
 * 归位（`offsetPx == 0`）时这张硬切回来、飞行那张让开：静止时显示的是**真实**那张，
 * 位置由布局保证，不依赖任何换算精度。
 *
 * 判据（2026-10-21 重定）：
 * · 有替身可顶（`isFlyingCoverReady`）→ 升降途中**整块藏起来**，落位（offset ≈ 0）才显示；
 * · 没有替身（这首歌没封面 / 替身还没画出来）→ 一直显示，占位音符照旧。
 *
 * 判据不再看"真封面就绪没有"：原先两侧各算 `offset/24` 做交叉淡入，交叉段两块半透明
 * 叠起来只有 ~75% 不透明，透出的浅底就是"封面闪一下"（见 [UFI_RISE_COVER_HANDOFF_PX]）。
 * 现在与飞行封面共用同一个阈值，任何一帧都只有一块实心。
 */
internal fun Modifier.ufiRiseCoverHidden(coverUrl: String?): Modifier =
    if (!UfiAudioRisePanel.visible) {
        this
    } else {
        this.graphicsLayer {
            val canStandIn = UfiAudioRisePanel.isFlyingCoverReady(coverUrl)
            val standing =
                canStandIn && UfiAudioRisePanel.offsetPx > UFI_RISE_COVER_HANDOFF_PX
            alpha = if (standing) 0f else 1f
        }
    }
