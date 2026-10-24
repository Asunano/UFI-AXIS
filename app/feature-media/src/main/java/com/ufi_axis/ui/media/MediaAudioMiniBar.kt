package com.ufi_axis.ui.media

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_ARTIST
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_COVER
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_PLAY
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_SEEK
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_TITLE
import com.ufi_axis.ui.navigation.UfiMiniPlayerBarMetrics
import com.ufi_axis.ui.navigation.ufiNavigateOnce
import com.ufi_axis.ui.navigation.ufiSharedBounds
import com.ufi_axis.ui.navigation.ufiSharedElement
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.ui.theme.UfiTextStyles
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 音乐页贴底的迷你控制条：一条可拖拽的进度条 + 封面/曲名/歌手 + 播放/暂停，
 * 整条可划 —— 水平滑动切歌、上滑打开播放页。
 *
 * ## 2026-10-14：上一首/下一首从两颗键改成水平滑动（方案 A）
 * 三颗键并排时，这条通栏条右侧有 120dp 被按钮占掉，而"上一首/下一首"是低频动作。
 * 现在它们改由滑动触发：**向右 = 上一首，向左 = 下一首**，只留播放/暂停那一颗键。
 * 手势挂在**外层 Row** 上，覆盖除顶部进度条以外的整条（含播放键所在的那块区域）；
 * 拖动时「封面 + 文字」整块跟手位移，并在露出的那一侧淡入「上一首 / 下一首」提示。
 * 一次手势只认**一个方向**（横向或竖向，认了之后锁死），斜划不会两个轴一起动；
 * 位移被裁在这块自己的框里，布局始终是固定那一条。阈值与方向判定的细节写在下方手势区。
 *
 * 上滑的去向分两种（2026-10-14 同日第三次修订）：**挂着页内播放面板的页面**（音乐列表 /
 * 歌单详情 / 分组详情）把竖向位移交给 [UfiAudioRisePanel]，面板跟着手指整块升起；
 * 没有面板宿主的场合退回导航 + RISE 转场。迷你条自己那套"内容跟手抬起"只在没有宿主时用。
 *
 * 代价（用户已确认接受）：播放页主控键的 `UFI_SHARED_KEY_AUDIO_PREV` / `UFI_SHARED_KEY_AUDIO_NEXT`
 * 在这条上失去对端，转场时退化成普通淡入 —— 与"歌词态下歌名不挂共享元素"是同一种降级。
 * （这两个 key 的 import 也随两颗键一起删掉了，写在这里只为留下这条取舍的线索。）
 *
 * ## 2026-09-20：从"悬浮胶囊卡"改成通栏贴底条
 * 原来是圆角 16dp + 8dp 阴影 + 描边 + 左右留白的卡片，浮在列表之上。改掉的理由：
 * 那个形状读起来像"一张可以划走的卡"，而它其实是这一页**常驻**的播放控制区；
 * 加上它与当时同在底部的分类 Tab 叠在一起，两层圆角卡片彼此争边界。
 * 现在是通栏、直角、左右到屏幕边缘的一条 —— 与系统级播放控制的形态一致，
 * 一眼就能读成"这一条属于屏幕，不属于列表"。
 *
 * ## 顶部只有进度条，没有分隔线（2026-09-20 同日修订）
 * 这里原先还有一条 1dp 分隔线，理由写的是"通栏条没有圆角也没有阴影，边界全靠它立住"。
 * 那个理由站不住：紧挨着它下面就是进度条本身 —— 两条横线只差 1dp 间距，
 * 读起来是"一条被描了边的线"，反而把真正要看的进度削弱了。
 * 现在边界由两样东西表达：**进度条本身**（它横贯屏幕，本来就是一条视觉分界），
 * 以及控制条底色 `cardBg` 与页面底色 `pageBg` 的色差。
 *
 * ## 进度条与播放页是**同一个组件**（2026-09-20 再修订）
 * 之前这里是自绘的 2dp 细线（`LinearProgressIndicator` + 两个自己挂的手势检测器），
 * 播放页那个是 6dp/按住 12dp 的 [MediaSeekBar]。同一个控件在两屏长得不一样、手势通道也
 * 不一样，还各存了一份"拖动中 / 待生效 seek / 播放器回读"的三级优先。现在两边都用
 * [MediaSeekBar]（迷你条传 `compact = true`），差异只有**触控区高度与轨道贴边方向**。
 */


@Composable
internal fun MediaAudioMiniBar(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val controller = UfiNowPlayingState.controller.value ?: return
    val mediaId = UfiNowPlayingState.mediaId.value
    if (mediaId.isBlank()) return

    // 2026-10-20（用户要求）：面板从**进度条**那条线升起，而不是屏幕底边。
    // 迷你条根节点的 boundsInWindow().top 就是这条线（进度条贴住它的上沿）。
    val barTopPx = remember { mutableStateOf(0f) }
    val isPlaying = UfiNowPlayingState.isPlaying.value
    // 2026-10-20（用户要求）：迷你条加「播放队列」入口，与播放页右下角那颗是**同一个面板**。
    var showQueue by remember { mutableStateOf(false) }
    // 2026-10-14：hasPrev / hasNext 不再读了 —— 左右滑动走的是**循环版**跳转
    //（到头绕回开头，见 ufiSkipToNextLooped），两侧永远切得动。
    // 播放页那两颗键仍然与模式相关（不循环时到头就按不动），那是另一条路。
    val artwork = UfiNowPlayingState.artworkUrl.value
    val artist = UfiNowPlayingState.artist.value
    // 标题兜底走与列表 / 播放页同一条取值链（见 MediaListCommon 里那段说明），
    // 免得同一首歌在这里显示成另一个样子
    val title = UfiNowPlayingState.title.value
        .ifBlank { audioFileBase(mediaId.substringAfterLast('/')) }

    /*
     * 2026-10-20：替「上滑面板」预取这张封面在**播放页那个尺寸**下的解码结果。
     *
     * 迷你条这块封面只有 48dp，Coil 的解码尺寸跟着约束走；飞行封面是 260dp 的框，
     * 缓存键带尺寸 ⇒ 面板起手第一帧它是空的，要等一次新的解码，而那时这张封面已经
     * 让位给了它 —— 观感就是"起手闪一下"。在这里提前把它按 260dp 解好放进内存缓存，
     * 面板起手第一帧就能画（见 MediaAudioRisePanel 的 isFlyingCoverReady）。
     *
     * 只在封面 URL 变化时做一次；命中内存缓存时 enqueue 几乎不花钱。
     */
    val coverContext = LocalPlatformContext.current
    val coverDensity = LocalDensity.current
    LaunchedEffect(artwork) {
        val url = artwork ?: return@LaunchedEffect
        val px = with(coverDensity) { AUDIO_COVER_SIZE.roundToPx() }
        SingletonImageLoader.get(coverContext).enqueue(
            ImageRequest.Builder(coverContext)
                .data(url)
                .size(px, px)
                .build()
        )
    }

    /*
     * 第一行显示歌词还是歌名，判据与标题栏挂件完全一致（[UfiAudioNowPlayingProbe] 写入）：
     * `subtitleIsLyric` 为真才说明 subtitle 是**这一刻的歌词**；这首歌没歌词、歌词还没解析完、
     * 或当前位置落在两句之间时它是 false，那就退回歌名，而不是让这一行空着。
     */
    val subtitle = UfiNowPlayingState.subtitle.value
    val showLyric = UfiNowPlayingState.subtitleIsLyric.value && subtitle.isNotBlank()


    val durationMs = UfiNowPlayingState.durationMs.value

    /*
     * 进度条的拖拽状态。显示值的三级优先（拖动中 > 刚 seek 完还没回读 > 播放器回读）
     * 现在由 [MediaSeekState] 持有，与播放页共用同一份实现 —— 这里不再自己算比例。
     */
    val seekState = rememberMediaSeekState()


    // 外层：只铺底色，一路铺到屏幕物理底边（见上方「分层」）
    /*
     * 把量到的高度报给 [UfiMiniPlayerBarMetrics]，供播放页的升起转场拿"从哪条线开始长"。
     *
     * 量在**最外层**这个节点上：它含底色、进度条（连它的触控区）、主体与手势条避让，
     * 也就是这条控制条在屏幕上真正占掉的那一段 —— 转场要的正是这个数，
     * 量内层会漏掉手势条那一截，面板起点就会低于控制条的上沿。
     *
     * 换成共用的 [MediaSeekBar] 之后这个数**没有变**：compact 形态的触控区仍是 14dp
     * （只是里面那条线从 2dp 变成 6dp），主体行仍是 64dp。即便将来改了，这里量的是
     * 实际布局结果而不是常量相加，转场起点自动跟着走。

     *
     * `token` 是这条迷你条的身份：同时挂着迷你条的两页转场时两条会短暂并存，
     * 清零必须只由"当前那条"执行（理由写在 [UfiMiniPlayerBarMetrics]）。
     */
    val token = remember { Any() }
    DisposableEffect(token) {
        onDispose { UfiMiniPlayerBarMetrics.release(token) }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            /*
             * 面板升起时整条淡出（2026-10-20 用户要求"把控制条渐出"）。
             *
             * 面板从第一帧就盖住这条，但它的内容也在同一条行程里淡入（见
             * [MediaAudioRisePanel] 的 `UFI_RISE_ENTER_FADE`）—— 一边减一边加，
             * 读起来是"控制条化进播放页"，而不是"条被一块板拍没了"。
             * 静止时这段恒为 1，原有观感一个像素都不变。
             */
            .graphicsLayer {
                val travel = UfiAudioRisePanel.travelPx
                alpha = if (UfiAudioRisePanel.visible && travel > 0f) {
                    val fadePx = UFI_RISE_ENTER_FADE.toPx()
                    (1f - ((travel - UfiAudioRisePanel.offsetPx) / fadePx)).coerceIn(0f, 1f)
                } else {
                    1f
                }
            }
            .onSizeChanged { UfiMiniPlayerBarMetrics.report(token, it.height) }
            .background(palette.cardBg)
    ) {
        // 内层：承担安全区避让。inset 落在这里，底色才不会被一起顶上去
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .onGloballyPositioned { barTopPx.value = it.boundsInWindow().top }
        ) {
            /*
             * 进度条：与播放页**同一个组件**（[MediaSeekBar]，compact 形态）。
             *
             * ## 为什么它要贴住这条控制条的上沿
             * 轨道只有 6dp，手势区却要 14dp 才按得准，多出来的余量得有个去处。
             * 上一版让轨道在手势区里**垂直居中** —— 于是轨道上方还剩 4dp 的 `cardBg`，
             * 浅色主题下就是一条白带，读起来像"进度条上面还压着一个空条"。
             * 现在余量全部留给下方（compact = true 走 `Alignment.TopCenter`），
             * 轨道就是这条控制条的上边界本身，触达一点没退步。
             *
             * 这条路径上**没有任何内边距**可以再把轨道顶下来：外层 Column 只有 fillMaxWidth
             * 与 background，本层只有 navigationBarsPadding（那是**底部**系统栏的 inset，
             * 横屏时也只会加到左右与底部，不会加到顶上），主体行的 padding 挂在下面那个 Row 上。
             *
             * 共享元素的 key 传进组件、由它挂在**轨道**那一层（理由见 [MediaSeekBar]）。
             */
            MediaSeekBar(
                state = seekState,
                durationMs = durationMs,
                positionMs = UfiNowPlayingState.positionMs.value,
                onSeek = { controller.seekTo(it) },
                compact = true,
                // 2026-10-14（用户）：**去掉进度条的共享元素形变，改成淡入**。
                // 此前传 UFI_SHARED_KEY_AUDIO_SEEK，这条通栏轨道会在转场里连贯长成播放页那根。
                // 现在传 null（= 不参与转场）：本侧只做普通淡入，对端（播放页那根）找不到对端、
                // 同样退化成淡入 —— 与「歌词态下歌名不挂共享元素」是同一种降级，不会报错。
                // trackKey 保留：换歌时已播段仍要平滑走过去而不是瞬移，那是另一件事。
                trackKey = UfiNowPlayingState.mediaId.value
            )


            /*
             * ── 手势区（2026-10-14 方案 A；同日二次修订：改成**轴向锁定**）──────────────
             * 这一条上除了顶部进度条，整条都能划：水平切歌、上滑开播放页。
             *
             * ## 为什么挂在外层 Row 上，而不是内层「封面 + 文字」那块
             * 手势要覆盖播放/暂停键所在的区域，而那颗键在内层块**之外** —— 挂在内层块上
             * 就划不到它。挂到外层 Row 之后播放键也落进手势区：拖动划过它照样切歌，
             * 点按仍走它自己的 clickable。这是安全的，因为 detectDragGestures 要**越过
             * touch slop** 才把事件 consume 掉，没越过的那一串事件原样传给子节点 ——
             * 点按与拖动的分界由此成立（用户确认过的那个例外）。
             *
             * ## 为什么只用一个 pointerInput 判两个方向
             * 水平切歌与竖直开播放页本是两件事，但用两个 pointerInput 各盯一个方向时，
             * 它们会互相抢 `consume()`：先命中的那个把事件吃掉，另一个再也看不到完整位移，
             * 斜着划一下两边都不触发。一个 detectDragGestures 拿到的是同一条位移向量，
             * 方向在这里自己判。
             *
             * ## ★ 2026-10-14 二次修订：方向要**锁**，不是两个轴各跟各的
             * 第一版把 `translationX` 与 `translationY` 同时喂给手指位移 —— 那是"自由拖动"：
             * 斜着划一下，封面会一边往左跑一边往上飘，两个方向都不成立、松手时还要按
             * 谁占主导去猜用户想干什么。用户明确否决了这个手感（"应该只有左右滑和上滑"）。
             * 现在位移累到 [MINI_BAR_DRAG_LOCK] 就**认一次方向**并锁死，此后只动那一个轴：
             * · 横向锁定 → 只改 translationX（上一首 / 下一首）；
             * · 竖向锁定 → 只改 translationY，且只允许负值（上滑开播放页，下滑什么也不做）。
             * 锁定判据用的是**累计位移**而不是某一帧的增量：单帧增量在手指刚起步时只有
             * 一两个像素，拿它比大小会把一次斜划判反。
             *
             * ## 为什么 pointerInput 排在 padding **之前**
             * modifier 链是外到内，padding 会把排在它后面的 modifier 的可用区域缩进一圈。
             * 手势若挂在 padding 之后，左右各 12dp 就成了划不动的死区 —— 而贴着屏幕边缘
             * 正是手指最容易落下的地方。挂在前面，整条（含左右边距与上下内边距）都是手势区。
             */
            val density = LocalDensity.current
            val commitPx = with(density) { MINI_BAR_DRAG_COMMIT.toPx() }
            val maxDragPx = with(density) { MINI_BAR_DRAG_MAX.toPx() }
            val lockPx = with(density) { MINI_BAR_DRAG_LOCK.toPx() }
            /*
             * 上滑交给页内面板时的行程 = **迷你条上沿（进度条那条线）到窗口顶的距离**，
             * 在手指按下那一刻才读（见下面 onDragStart）。
             *
             * 面板是"整块从这条线往上长"：起点顶边压在这条线上，手指再抬多少、面板顶边就上移
             * 多少。这与导航那条路的起点是同一个语义（`Navigation.kt` 的 `riseStartOffset`
             * 也是 `fullHeight - 迷你条高度`）—— 两条路径必须从同一条线起步，否则同一次"打开
             * 播放页"会有两种观感。
             *
             * 2026-10-20 修正：原来这里传的是整窗高度，于是面板顶边从**屏幕物理底边**起步，
             * 前一段（正好是迷你条自身的高度）手指在走、面板还在屏幕外，之后又从下往上把
             * 迷你条本体吃掉 —— 用户报的"不是从进度条那条线升起"就是这一段。
             *
             * `barTopPx` 由内层 Column 的 `onGloballyPositioned` 上报（见下方），是窗口坐标里的
             * 那条线；它比 `screenHeightDp` 一类的估算可靠（后者在 edge-to-edge 下不含系统栏）。
             * 读的是 State **对象**而不是它此刻的值：手势 lambda 在组合之外运行，只有读 State
             * 才拿得到当下值（首帧之前它还是 0，此时退回整窗高，与从前的行为一致）。
             */
            val windowInfo = LocalWindowInfo.current

            /*
             * 手势键为什么不再用 mediaId（2026-10-20）：
             * `pointerInput(key)` 的 key 一变，Compose 会取消正在跑的手势协程，而
             * `detectDragGestures` **不会**在协程取消时回调 `onDragCancel`（它只在"事件被
             * 别的节点消费"时回调）—— 于是拖动到一半自动切歌（队列自动续播 / 耳机切歌）会把
             * 面板丢在半空：`visible` 还是 true、`settleTargetPx` 已被 onDragStart 清成 null，
             * 没有任何补间会把它推回 0 或推到行程尽头。
             *
             * key 固定之后手势不再被打断；切歌后要用的最新 mediaId / controller 从
             * `rememberUpdatedState` 读 —— 它返回的 State 实例稳定，组合外读到的永远是当下值。
             */
            val currentMediaId = rememberUpdatedState(mediaId)
            val currentController = rememberUpdatedState(controller)

            /*
             * 五个量都放在 State 里，而不是普通局部变量：pointerInput 的 lambda 与
             * graphicsLayer 的 lambda 都在组合之外运行，只有读 State 才拿得到当下这一帧的值。
             * total* 是本次手势的累计位移（认方向用），drag* 是锁定后限幅的跟手量（画位移用），
             * axis 是方向锁（0 = 还没认，1 = 横向，2 = 竖向）。
             */
            val totalX = remember { mutableStateOf(0f) }
            val totalY = remember { mutableStateOf(0f) }
            val dragX = remember { mutableStateOf(0f) }
            val dragY = remember { mutableStateOf(0f) }
            val axis = remember { mutableStateOf(0) }
            /*
             * 起手那一刻已有的位移（2026-10-14）。松手后会有一段 Q 弹回位动画（见
             * [settleDragBack]），动画没跑完就被再次按下时，新的一次拖动要**接着它现在的位置**
             * 继续，而不是从 0 重新开始 —— 否则画面会先"啪"地跳回 0 再跟手。
             */
            val dragBaseX = remember { mutableStateOf(0f) }
            val dragBaseY = remember { mutableStateOf(0f) }
            /** 回位动画的句柄：再次按下 / 手势取消时要掐掉它，免得两条协程抢着写同一个位移。 */
            val dragSettle = remember { MiniBarDragSettle() }
            val dragSettleScope = rememberCoroutineScope()
            // 只归零"手势本身的记账"，**不动位移** —— 位移交给回位动画，Q 弹就是从这里来的
            val resetGesture = {
                totalX.value = 0f
                totalY.value = 0f
                axis.value = 0
            }
            /*
             * ── 松手后的 Q 弹回位（2026-10-14）──────────────────────────────────
             *
             * 原来这里是把 dragX / dragY 直接置 0：一次左右滑动松手，整块内容是**瞬间**弹回
             * 原位的，读起来像被谁一把按住 —— 用户报的"僵硬"就是这一下（切歌提交时同样：
             * 新歌的内容是"啪"地跳到位，没有任何回弹）。
             *
             * 现在走一条带过冲的 spring（[UfiMotion.controlPop]，0.5 / 600）：内容先越过原位
             * 一点点再收回来。提交切歌那一下因此变成"旧内容滑出去、新内容弹回来"，而不是硬切。
             *
             * 横向与竖直各一条动画：竖直在有面板宿主时恒为 0（面板接管），白跑一条也不贵，
             * 但分开写能让"只动过一个轴"的常见情况少一次无谓的动画。
             */
            val settleDragBack = {
                dragSettle.job?.cancel()
                dragSettle.job = dragSettleScope.launch {
                    launch {
                        animate(
                            initialValue = dragX.value,
                            targetValue = 0f,
                            animationSpec = UfiMotion.controlPop()
                        ) { value, _ -> dragX.value = value }
                    }
                    animate(
                        initialValue = dragY.value,
                        targetValue = 0f,
                        animationSpec = UfiMotion.controlPop()
                    ) { value, _ -> dragY.value = value }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                resetGesture()
                                // 上一次的 Q 弹回位还没跑完就再次按下：先让它让位给手指，
                                // 再把它此刻的位移当作这一次拖动的起点
                                dragSettle.job?.cancel()
                                dragBaseX.value = dragX.value
                                dragBaseY.value = dragY.value
                                // 有页内面板宿主时，竖向拖动由面板接管（见 UfiAudioRisePanel）
                                if (UfiAudioRisePanel.hosted) {
                                    val barTop = barTopPx.value
                                    UfiAudioRisePanel.onDragStart(
                                        travelPx = if (barTop > 0f) barTop
                                        else windowInfo.containerSize.height.toFloat(),
                                        // 背景页模糊的行程（px）：面板那边按这个值把位移换算成
                                        // 模糊进度，页壳在绘制期读（见 UfiPageBackdrop）
                                        backdropFadePx = with(density) {
                                            UFI_RISE_BACKDROP_FADE.toPx()
                                        }
                                    )
                                }
                            },
                            onDragEnd = {
                                when {
                                    /*
                                     * 竖向 + 有面板宿主：提交与否由**面板**自己判 —— 行程与速度
                                     * 都在它那边，迷你条这条 56dp 的阈值在这里用不上。
                                     * 没让面板露出来的拖动会在那边直接返回，什么也不做。
                                     */
                                    axis.value == 2 && UfiAudioRisePanel.hosted ->
                                        UfiAudioRisePanel.onDragEnd()
                                    /*
                                     * 上滑 = 打开播放页。只看 dragY：它在横向锁定时恒为 0，
                                     * 所以"斜着往左上划"不会再同时命中两条分支。
                                     */
                                    dragY.value < -commitPx ->
                                        // ufiNavigateOnce 而不是 navigate：连点两下会 push 两个
                                        // 播放页实例（返回要按两次）。守卫按目的地去重，理由见该函数。
                                        navController.ufiNavigateOnce(
                                            mediaRouteOf("media/audio", currentMediaId.value)
                                        )
                                    /*
                                     * 队列到头 / 到底也照样切：这两下走的是**循环版**
                                     *（[ufiSkipToNextLooped] / [ufiSkipToPreviousLooped]），
                                     * 列表放完绕回开头，不看播放页当前的循环模式 —— 用户明确
                                     * 要求"滑动不要被模式限制"（播放页那两颗键仍然是模式相关的）。
                                     */
                                    dragX.value > commitPx ->
                                        currentController.value.ufiSkipToPreviousLooped()
                                    dragX.value < -commitPx ->
                                        currentController.value.ufiSkipToNextLooped()
                                }
                                resetGesture()
                                // 位移不在这里归零：交给 Q 弹回位动画（见 settleDragBack）
                                settleDragBack()
                            },
                            // 手势被上层抢走（例如转场开始）时也要归位，理由同 resetGesture
                            onDragCancel = {
                                // 面板拖到一半被抢走事件时同样要归位，否则它会卡在半空
                                if (axis.value == 2 && UfiAudioRisePanel.hosted) {
                                    UfiAudioRisePanel.onDragCancel()
                                }
                                resetGesture()
                                settleDragBack()
                            },
                            onDrag = { change, drag ->
                                // 走到这里说明已经越过 touch slop（那是 detectDragGestures 的门槛），
                                // 手势成立就 consume：底下的 clickable 不该再收到这一串事件
                                change.consume()
                                totalX.value += drag.x
                                totalY.value += drag.y
                                if (axis.value == 0) {
                                    val ax = abs(totalX.value)
                                    val ay = abs(totalY.value)
                                    // 横向与竖向谁大认谁；完全相等时认横向（切歌是这条上的主要动作）
                                    if (ax + ay >= lockPx) axis.value = if (ay > ax) 2 else 1
                                }
                                when (axis.value) {
                                    // 横向跟手。限幅只防封面被甩出屏幕太远，方向不受限
                                    1 -> {
                                        dragX.value = (dragBaseX.value + totalX.value)
                                            .coerceIn(-maxDragPx, maxDragPx)
                                        dragY.value = 0f
                                    }
                                    // 竖向只允许上滑：向下拖不给位移（coerceAtMost(0)），
                                    // 免得这条贴底的条被"拉离"屏幕下沿、在它下面露出一条底色
                                    2 -> {
                                        /*
                                         * 有面板宿主时，竖向位移交给**面板**去跟手：迷你条自己的
                                         * 内容不动 —— 面板升起时本来就要把整条盖住，再让它自己
                                         * 往上挪一下只是多一层会动的东西（用户明确要"滑动只改变
                                         * 元素"，这里改的是面板那一个元素）。
                                         * 没有宿主（预览 / 将来别的宿主）时退回老行为：内容跟手
                                         * 抬起，松手过阈值再走导航。
                                         */
                                        if (UfiAudioRisePanel.hosted) {
                                            UfiAudioRisePanel.onDrag(drag.y)
                                        } else {
                                            dragY.value = (dragBaseY.value + totalY.value)
                                                .coerceIn(-maxDragPx, 0f)
                                        }
                                        dragX.value = 0f
                                    }
                                    else -> {
                                        dragX.value = 0f
                                        dragY.value = 0f
                                    }
                                }
                            }
                        )
                    }
                    .padding(
                        horizontal = MINI_BAR_CONTENT_PADDING_H,
                        vertical = MINI_BAR_CONTENT_PADDING_V
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                /*
                 * weight(1f) 从内层 Row 挪到了这个 Box 上（2026-10-14）。
                 *
                 * 跟手位移要作用在**整块**「封面 + 文字」上，所以这一块必须是一个能被
                 * graphicsLayer 平移的整体；而 Box 里还要放提示文字 —— 提示不跟着滑，
                 * 它得留在露出来的那一侧。weight 若还挂在内层 Row 上，Box 自己就没有宽度，
                 * 提示文字也就没有可对齐的容器。
                 *
                 * ## clipToBounds：布局是固定的，滑动只改元素
                 * 位移走的是 `graphicsLayer`，本来就不参与测量 —— 布局**永远**是原来那一条。
                 * 但绘制默认不裁剪，位移出去的封面/文字会画到 Box 之外：向右拖时文字压到
                 * 播放键上、上滑时封面盖住上面的进度条。用户要的是"固定布局、滑动只改变元素"，
                 * 所以这里把绘制裁在 Box 自己的框里：元素在这块固定的框内跟手移动，
                 * 越过框的部分直接消失（与"面板从这条上沿长出来"是同一套语言）。
                 */
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clipToBounds()
                ) {
                    /*
                     * 封面 + 文字整块可点 → 全屏播放页。播放/暂停键在这块之外，
                     * 点它不会连带跳页。
                     *
                     * graphicsLayer 排在 clickable **之前**：modifier 链是外到内，
                     * 这样点按区域也跟着位移走 —— 拖动中这一串事件已经被手势 consume，
                     * 这里只是让"滑到一半松手后手指底下是什么"与眼睛看到的一致。
                     */
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                translationX = dragX.value
                                translationY = dragY.value
                            }
                            // 有面板宿主时**点按也走面板**（2026-10-21）：否则点开的是导航那条
                            // 播放页，它不在面板里，"点开之后下拉关闭"就没有任何反应 ——
                            // 与上划那条能力不一致。没有宿主（如播放页自身在屏上）才退回导航。
                            .clickable {
                                if (UfiAudioRisePanel.hosted) {
                                    UfiAudioRisePanel.openByTap(
                                        travelPx = barTopPx.value.takeIf { it > 0f }
                                            ?: windowInfo.containerSize.height.toFloat(),
                                        backdropFadePx = with(density) {
                                            UFI_RISE_BACKDROP_FADE.toPx()
                                        }
                                    )
                                } else {
                                    // ufiNavigateOnce 而不是 navigate：连点两下会 push 两个播放页实例
                                    // （返回要按两次）。守卫按目的地去重，不挡时间窗，理由见该函数。
                                    navController.ufiNavigateOnce(
                                        mediaRouteOf("media/audio", mediaId)
                                    )
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(MINI_BAR_COVER_SIZE)
                                // 共享元素的起点：转场时这张 48dp 的封面会连贯长成播放页那张 260dp 的。
                                // 挂在 clip 之前，让圆角也参与补间（终点圆角更大）。
                                .ufiSharedElement(UFI_SHARED_KEY_AUDIO_COVER)
                                // 页内跟手面板的起点（2026-10-14）：把自己在窗口里的位置报给面板，
                                // 面板那张封面会按同一个手指位移从这块矩形飞过去（见 ufiRiseFollow）
                                /*
                                 * 报的是**归位后的矩形**，要减掉这次手势已经产生的跟手位移
                                 * （2026-10-20 五修）。
                                 *
                                 * 父级 Row 的 graphicsLayer 平移会被 `boundsInWindow` 算进去，
                                 * 而横向拖动（切歌）时它每帧都在变 —— 不减掉的话这里每帧都会
                                 * `reportFollowSource` 写一次 State，整条迷你条跟着逐帧重组，
                                 * 手动划着切歌就明显掉帧（用户报的"手动划切换特别明显"）。
                                 * 减掉之后整流拖动期间上报值恒定，`reportFollowSource` 的相等判断
                                 * 直接早退；起点语义也更对：飞行封面该从封面**家里**起飞，不是从
                                 * 被人拽到一半的位置起飞。
                                 */
                                .onGloballyPositioned {
                                    UfiAudioRisePanel.reportFollowSource(
                                        UFI_RISE_FOLLOW_COVER,
                                        // clipBounds = false：这条 Row 自己带 clipToBounds（跟手位移
                                        // 要裁在自己框里），默认那版会把矩形裁掉一截
                                        it.boundsInWindow(clipBounds = false)
                                            .translate(-dragX.value, -dragY.value)
                                    )
                                }
                                // 面板在场时这一张必须藏起来：封面已经"飞"到面板里去了。
                                //
                                // 三个判据缺一不可（2026-10-20）：
                                // · `coverSlot != null`：面板组合的第一帧还没量到它自己那张封面的
                                //   槽位，飞行封面那一刻也算不出落点 —— 那一帧继续显示这一张；
                                // · `followSource != null`：起点还没上报时飞行封面同样画不出来；
                                // · `isFlyingCoverReady(artwork)`：飞行封面是 260dp 的框，它的解码结果
                                //   与迷你条这 48dp 那张不是同一个缓存项 —— 还没画出来就让位的话，
                                //   这一块会先变成它的占位底再变回真封面，用户看到的就是"起手闪一下"。
                                //   所以让位发生在飞行封面**真的画出图**之后（两处位置重合，看不出切换）。
                                //
                                // 用条件 modifier 而不是常挂一层 alpha 图层：不拖面板时一个字节的
                                // 绘制代价都不加（这一块同时还挂着导航那条路的共享元素）。
                                .then(
                                    if (UfiAudioRisePanel.visible &&
                                        UfiAudioRisePanel.coverSlot != null &&
                                        UfiAudioRisePanel.followSource(UFI_RISE_FOLLOW_COVER) != null &&
                                        UfiAudioRisePanel.isFlyingCoverReady(artwork)
                                    ) {
                                        Modifier.alpha(0f)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clip(RoundedCornerShape(MINI_BAR_COVER_CORNER))
                                .background(palette.surfaceMuted),
                            contentAlignment = Alignment.Center
                        ) {
                            // 占位图标画在底层，有封面时盖上去 —— 拉不到封面看到的是图标而不是破图
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = palette.textSecondary,
                                modifier = Modifier.size(Spacing.IconSizeMedium)
                            )
                            if (artwork != null) {
                                AsyncImage(
                                    model = artwork,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            // 两行之间给一道 [MINI_BAR_TEXT_GAP]：紧贴时两行小字会读成一句被折行的话
                            verticalArrangement = Arrangement.spacedBy(MINI_BAR_TEXT_GAP)
                        ) {
                            /*
                             * 第一行：**有歌词时显示当前歌词，没歌词时显示歌名**。
                             *
                             * 为什么让歌词顶掉歌名而不是再加一行：这条控制条是贴底的通栏条，高度由
                             * 封面（48dp）定死，加第三行要么把条撑高、要么把字压小。而"正在唱哪一句"
                             * 是这一刻唯一在变的信息，歌名在第二行的歌手旁边已经不缺上下文
                             * （真要看歌名，点进播放页就是整屏的）。
                             *
                             * 歌词那份走 [MediaLyricTickerText]（与标题栏挂件同一个组件）：换句是整行
                             * 竖向滚动，长句按这句的剩余时长横向跑一趟。歌名不走它 ——
                             * 那个组件为了能横向滚必须 `softWrap = false`，于是长歌名只会被裁掉而
                             * 没有省略号，而歌名恰恰是"看不全也要看出是被截了"的那类文字。
                             *
                             * ## 共享元素只挂在"确实显示歌名"这一支上
                             * [UFI_SHARED_KEY_AUDIO_TITLE] 的对端是播放页那行歌名。显示歌词的时候
                             * 这一行的内容与对端**不是同一件事**，挂上去就是让"当前这句歌词"一路
                             * 变形成歌名 —— 用户读到的是一次错配的变形，比没有动画更糟。
                             * 歌词态下不挂：播放页那行歌名找不到对端，退化成普通淡入（转场时封面、
                             * 歌手、进度条、播放键仍然连贯，缺的只是歌名这一个节点）。
                             */
                            if (showLyric) {
                                MediaLyricTickerText(
                                    text = subtitle,
                                    holdMs = UfiNowPlayingState.lyricHoldMs.value,
                                    style = UfiTextStyles.bodyEmphasis,
                                    color = palette.accent
                                )
                            } else {
                                Text(
                                    title,
                                    style = UfiTextStyles.bodyEmphasis,
                                    color = palette.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    // scaleContent = false：文字两端字号与可用宽度都差得多，
                                    // 整体缩放会先把字缩到三四成再长大且发虚，理由见 ufiSharedBounds
                                    modifier = Modifier.ufiSharedBounds(
                                        UFI_SHARED_KEY_AUDIO_TITLE,
                                        scaleContent = false
                                    )
                                )
                            }
                            // 第二行恒为歌手。猜不出歌手时整行不画，不写"未知艺人"这种看着像数据的占位
                            if (artist.isNotBlank()) {
                                Text(
                                    artist,
                                    style = UfiTextStyles.caption,
                                    color = palette.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.ufiSharedBounds(
                                        UFI_SHARED_KEY_AUDIO_ARTIST,
                                        scaleContent = false
                                    )
                                )
                            }

                        }
                    }

                    /*
                     * 提示文字（2026-10-14）：只在拖动时出现，松手即消失（dragX 归零 → 不画）。
                     *
                     * ## 为什么画在滑动块**之后**
                     * Compose 里后画的节点画在上层。提示要压在滑过来的封面/文字上面，
                     * 就必须排在它们后面 —— 排在前面会被整块盖住，拖动时反而看不见。
                     *
                     * ## 为什么位置与位移方向相反
                     * 向右拖 = 上一首，右侧那块被拖走，**左边**露出空间，提示就该在左；
                     * 向左拖 = 下一首，提示在右。所以 align 取的方向与 dragX 的符号相反。
                     *
                     * ## 为什么不再判"这一侧切得动"
                     * 滑动走的是循环版跳转（到头绕回），两侧永远切得动，提示也就永远成立。
                     */
                    val hint = when {
                        dragX.value > 0f -> "上一首"
                        dragX.value < 0f -> "下一首"
                        else -> null
                    }
                    hint?.let {
                        Text(
                            it,
                            modifier = Modifier
                                .align(
                                    if (dragX.value > 0f) {
                                        Alignment.CenterStart
                                    } else {
                                        Alignment.CenterEnd
                                    }
                                )
                                // 淡入跟着位移走：划过一点点就只露一点点，读起来像"提示从边上长出来"
                                .alpha((abs(dragX.value) / maxDragPx).coerceIn(0f, 1f)),
                            style = UfiTextStyles.caption,
                            color = palette.textSecondary
                        )
                    }
                }

                /*
                 * 播放/暂停是这条上**唯一**留下的一颗键（2026-10-14：上一首/下一首被水平滑动取代，
                 * 理由见上面那段手势区注释）。
                 *
                 * 它仍是共享元素：对端是播放页主控三键中间那个固定 52dp 的图标槽。
                 * 挂在 IconButton 这一层（而不是里面的 Icon）——共享元素补的是**节点边界**，
                 * 取触达区这个稳定的 40dp 方框，转场中不会因为图标随播放状态换形而跳变。
                 *
                 * 用 ufiSharedBounds 而不是 ufiSharedElement（2026-09-20 修"抽搐"）：
                 * 后者只补间外框、框里的图标仍按固有尺寸测量，40dp 与 52dp 两端一补间，
                 * 那个不缩放的图标就在一个逐帧变尺寸的框里跳。理由详见 ufiSharedBounds。
                 * 封面那条仍用 ufiSharedElement —— 它两端都是正方形、内容是能随约束重新裁剪的
                 * 图片，重新测量正是想要的效果。
                 *
                 * ## 上一首/下一首的共享元素在这条上没有了（用户已确认接受）
                 * UFI_SHARED_KEY_AUDIO_PREV / _NEXT 的对端随那两颗键一起删掉，
                 * 播放页那两颗在转场里找不到对端，退化成普通淡入 —— 与"歌词态下歌名不挂
                 * 共享元素"是同一种降级：少一段补间，不会报错。
                 *
                 * ## 横向预算（360dp 最窄常见屏，2026-10-14 重算）
                 * 336（屏宽 − 左右各 12dp 内边距）− 40（这一颗键）− 8（它与左块之间的间距）
                 * = 288 给左侧那块；再减封面 48 与它与文字之间的 8 ⇒ 文字列有 **232dp**，
                 * 比三颗键时的 136dp 宽出近一倍 —— 长歌名/长句歌词被截断的机会少了很多。
                 * 这是删掉两颗键顺带换来的，尺寸令牌一个都没动。
                 */
                IconButton(
                    onClick = { if (isPlaying) controller.pause() else controller.play() },
                    modifier = Modifier
                        .size(MINI_BAR_BUTTON_SIZE)
                        .ufiSharedBounds(UFI_SHARED_KEY_AUDIO_PLAY)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = palette.accent
                    )
                }
                // 2026-10-20：播放队列按钮（与播放页右下角那颗同一个面板）。
                IconButton(
                    onClick = { showQueue = true },
                    modifier = Modifier.size(MINI_BAR_BUTTON_SIZE)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "播放队列",
                        tint = palette.textSecondary
                    )
                }
            }
        }
    }

    // 2026-10-20：播放队列面板（与播放页右下角那颗同一个面板）。
    // 行数据按**播放器里真实的队列**构建（ufiAudioQueueIds + 当前 timeline 的元信息），
    // 与播放页那边的口径一致：面板行数与顺序只认播放器，不认浏览列表。
    val queueRows = remember(showQueue, controller.currentMediaItemIndex, controller.mediaItemCount) {
        if (!showQueue) {
            emptyList()
        } else {
            controller.ufiAudioQueueIds().mapIndexed { index, id ->
                val md = runCatching { controller.getMediaItemAt(index).mediaMetadata }.getOrNull()
                AudioQueueRow(
                    key = id,
                    title = md?.title?.toString()?.takeIf { it.isNotBlank() }
                        ?: id.substringAfterLast('/'),
                    subtitle = md?.artist?.toString().orEmpty(),
                    duration = formatMediaDuration(md?.durationMs ?: 0L)
                )
            }
        }
    }
    NowPlayingQueueDialog(
        visible = showQueue,
        queueRows = queueRows,
        currentIndex = controller.currentMediaItemIndex,
        hintText = null,
        onSelect = { idx ->
            controller.seekTo(idx, 0L)
            controller.play()
            showQueue = false
        },
        onRemove = { idx ->
            controller.ufiRemoveFromQueue(idx)
            if (controller.mediaItemCount == 0) showQueue = false
        },
        onMove = { from, to -> controller.ufiMoveInQueue(from, to) },
        onDismiss = { showQueue = false }
    )
}

/*
 * 进度条的尺寸（轨道粗细、触控区高度、暂存位置的保持时长）与轨道配色全部由
 * [MediaSeekBar] 那边定义 —— 本文件不再留一份自己的取值，否则两屏的进度条
 * 又会各自漂走。
 */

/**
 * 主体左右内边距。比页面内容区（[Spacing.Medium] = 16dp）窄一档：
 * 通栏条的左右已经是屏幕边缘，留太宽会让封面看着"往里缩了一块"。
 */
private val MINI_BAR_CONTENT_PADDING_H = 12.dp

/**
 * 主体上下内边距。封面 48dp + 上下各 8dp ⇒ 主体行 64dp，与系统通知里的媒体控制同量级。
 * （整条的高度还要加上进度条那个触控区，见 [MediaSeekBar] 的 compact 形态。）
 */

private val MINI_BAR_CONTENT_PADDING_V = 8.dp


/** 封面边长，直接取曲目行那个令牌：同一张封面在两处不该一大一小。 */
private val MINI_BAR_COVER_SIZE = MEDIA_AUDIO_THUMB_SIZE

/**
 * 跟手位移"Q 弹回位"那条协程的句柄（2026-10-14）。
 *
 * 单独一个类而不是 `remember { mutableStateOf<Job?>(null) }`：它**不该参与组合** ——
 * 存进去的只是协程句柄，建立 snapshot 依赖只会让每次手势多一次无意义的重组
 *（与 `UfiMiniPlayerBarMetrics` 用普通 `var` 是同一条理由）。
 */
private class MiniBarDragSettle {
    var job: Job? = null
}

/**
 * 封面圆角（2026-10-20 四修：**回到 8dp**，与飞行封面的起点对齐）。
 *
 * 上一版按"播放页 20dp × 44/260"算成 3.4dp，想让它和飞行封面缩到迷你条尺寸时的等效
 * 圆角一致 —— 结果那个尺寸下圆角几乎看不见，交接时读起来就是"圆角丢了、变直角"。
 * 现在反过来：这里保持 8dp，由**飞行封面**把自己的起点圆角对齐到 8dp、终点对齐到
 * 播放页的 20dp（见 `UfiRiseFlyingCover` 里按屏幕等效半径插值的那段）。两处交接都严丝合缝，
 * 而且圆角在大尺寸时仍然是播放页那个更圆的形态。
 */
internal val MINI_BAR_COVER_CORNER = 8.dp

/**
 * 播放/暂停那颗键的触达区（2026-10-14：上一首/下一首被滑动取代，这条上只剩它一颗）。
 * 40dp 仍在可靠点击区内，比旧的 44dp 小一档以压住整条的高度。
 */
private val MINI_BAR_BUTTON_SIZE = 48.dp

/**
 * 第一行与歌手行之间的行距。
 *
 * 留一道间距而不是 0：两行都是左对齐的小字，紧贴时会被读成"一句话折了行"。也不能再大 ——
 * 这一列的高度预算是封面那 48dp，间距吃掉的每一 dp 都从两行文字的可用高度里出。
 */
private val MINI_BAR_TEXT_GAP = 6.dp // 2026-10-14：2 → 6（用户："歌曲名和歌手的上下间隔增加一点点"）

/**
 * 手势的提交阈值（2026-10-14）：位移过了这么多，松手才真的切歌 / 开播放页。
 *
 * 56dp 比 40dp 的按钮触达区略大一档：这两个动作会**换掉整条上显示的内容**，
 * 比点一下按钮更该要求一点明确的意图；再大就会开始显得"划不动"。
 * 水平与竖直共用同一个值 —— 两个方向的动作分量相当，没有理由给不同的门槛。
 */
private val MINI_BAR_DRAG_COMMIT = 56.dp

/**
 * 认方向的阈值（2026-10-14 二次修订）：累计位移过了这么多，就把这次手势**锁**在横向或竖向。
 *
 * 为什么不直接用平台的 touch slop：那个门槛在 `detectDragGestures` 内部已经用过一次
 * （越过它才会开始派发 onDrag），而它派发的第一个增量只是"越过 slop 之后的那一点点"，
 * 拿一两个像素去比 |x| 与 |y| 会把斜划判反。这里再累几个像素，方向就稳定了。
 *
 * 4dp 是"来得及"与"不迟钝"之间的取值：太小（1dp）仍会被抖动带偏，太大（12dp 以上）
 * 会让手指已经走了一段、画面还一动不动。
 */
private val MINI_BAR_DRAG_LOCK = 4.dp

/**
 * 跟手位移的上限（2026-10-14）。它**不是**阈值 —— 阈值另有 [MINI_BAR_DRAG_COMMIT]，
 * 这里管的是"最多能拖多远"：提示文字只有两三个字宽，露再多也只是把底下的页面空白拉进来，
 * 而封面被拖出去半条屏幕反而看不清自己在滑什么。竖直方向共用同一个上限。
 */
private val MINI_BAR_DRAG_MAX = 96.dp

