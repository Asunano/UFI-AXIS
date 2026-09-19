package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiMotion

/**
 * 标题两侧为动作位预留的宽度（左返回键 / 右 actions 都是 48dp 触区）。
 *
 * 之所以是常量而不是"有返回键才留"：`actions` 是一个 `@Composable` lambda，组合前无法判断它
 * 是否真的画了东西；左右不对称留白又会让居中标题偏轴。留固定值最省心，代价只是极长标题
 * 早一点出现省略号。
 */
private val HEADER_TITLE_SIDE_RESERVE = 52.dp

/*
 * 标题栏上下内边距（2026-09-17 放大约 20%；2026-09-18 再放大一档，与标题字号
 * 22sp → 26sp 同步）。
 *
 * 原来是 4 / 8dp，标题挤在状态栏与内容之间。字号与高度必须一起改：只加字号会让标题
 * 贴住上下边缘，只加高度则显得空。
 *
 * 带「正在播放」的那一套更高：右侧是双行（曲名·歌手 + 歌词），需要更多竖向空间。
 */
private val HEADER_PADDING_TOP = 12.dp
private val HEADER_PADDING_BOTTOM = 16.dp
private val HEADER_PADDING_TOP_NOW_PLAYING = 16.dp
private val HEADER_PADDING_BOTTOM_NOW_PLAYING = 22.dp

/**
 * 标题栏右侧「正在播放」区的注入口（2026-09-17）。
 *
 * ## 为什么是全局单例而不是形参
 * 这块内容要出现在**每一个**页面的标题栏上（音乐在后台放着，用户走到哪都该能控制），
 * 而它的实现要连 MediaSession（在 `:app:feature-media`）—— 本模块不依赖那一层。
 * 逐个页面传形参等于改几十处调用；所以留一个注入口：`:app` 启动时把实现塞进来，
 * 页壳只管渲染。与 [CapsuleInsetHolder] / [CapsuleTouchGate] 是同一种做法。
 *
 * ## [active] 为什么必须单独给
 * `content` 是个 `@Composable` lambda，**组合前无法知道它会不会真的画东西**（没在播时它
 * 什么都不画）。而标题栏版式要据此切换：有内容时标题靠左、右侧让位；没有时标题保持居中。
 * 所以由内容侧显式告知"我现在有东西要显示"。
 */
object UfiNowPlayingSlot {
    /** 标题栏右侧的内容（由 `:app` 注入，实现在 `:app:feature-media`）。 */
    val content: MutableState<(@Composable () -> Unit)?> = mutableStateOf(null)

    /** 此刻是否真的有东西要显示（由内容侧写入）。false = 标题栏保持原来的居中版式。 */
    val active: MutableState<Boolean> = mutableStateOf(false)
}

/**
 * 标题栏右侧「今日天气」区的注入口（2026-09-17）。
 *
 * 与 [UfiNowPlayingSlot] 共用同一块位置（标题栏右侧），**优先级低于它**：
 * 正在放歌时那块地给播放控制，没在放才显示天气。理由是播放态是可操作的、且转瞬即变，
 * 天气是纯展示、慢变量 —— 让可操作的那个赢。两个都想占位的话就成了会互相盖住的假开关。
 *
 * 实现放在 `:app:feature-settings`（那边有 viewModel 与设置页），`:app` 启动时注入。
 */
/**
 * 标题栏**右侧天气**的注入口（2026-09-18 第二版）。
 *
 * 位置演化说明（别再来回搬）：
 * - 09-17 天气在右侧，与「正在播放」抢同一块地、靠优先级互相让位；
 * - 09-18 上午挪到标题下方小字，与诗词并排 —— 但两个都开时一行小字塞不下；
 * - 09-18 现在：**天气回到右侧（信息可以更全）、诗词占标题下方小字**，各有其位。
 *
 * 与 [UfiNowPlayingSlot] 仍然共用右侧那块地，取舍不变：**放歌时播放控制优先**，
 * 天气暂时隐藏。理由是播放态可操作、且转瞬即变，天气是纯展示的慢变量。
 */
object UfiWeatherSlot {
    val content: MutableState<(@Composable () -> Unit)?> = mutableStateOf(null)

    /** 开关开着、城市设过、且已取到数据时才为 true（由内容侧写入）。 */
    val active: MutableState<Boolean> = mutableStateOf(false)
}

/**
 * 标题栏**标题下方小字**的注入口（2026-09-18）。
 *
 * ## 它和 subtitle 不是一回事
 * [UfiHeader] 的 `subtitle` 是**页面说明**（属于这个页面本身，如二级页的"连接模式 · 网络制式"）；
 * 这里的 caption 是**挂件**（今日诗词），与页面内容无关、随设置开关出现或消失，
 * 而且要出现在很多页面上。两者语义不同，所以不复用同一个参数：
 * 让页面把诗词当"页面说明"传进来，就等于每个页面都要知道诗词功能的存在。
 *
 * 与 [UfiNowPlayingSlot] / [UfiWeatherSlot] 同一种做法：`:app` 启动时注入实现，页壳只渲染。
 */
object UfiHeaderCaptionSlot {
    val content: MutableState<(@Composable () -> Unit)?> = mutableStateOf(null)

    /** 此刻是否真的有东西要显示（由内容侧写入）。 */
    val active: MutableState<Boolean> = mutableStateOf(false)
}


/**
 * Custom header bar matching UFITOOLSWidget style:
 * Centered title (28sp bold) + optional subtitle, back button on the left, actions on the right.
 *
 * 2026-08-30 去重：全库唯一调用方是本文件的 [UfiScreenScaffold]，故降为 private。
 * 页面需要标题栏时用 [UfiScreenScaffold]，不要直接摆 header。
 */
@Composable
private fun UfiHeader(
    title: String,
    subtitle: String? = null,
    /**
     * 标题下方的小字（天气 / 今日诗词）。由 [UfiHeaderCaptionSlot] 注入，
     * **不是页面说明** —— 页面说明是 [subtitle]。
     */
    caption: (@Composable () -> Unit)? = null,
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    /**
     * 标题栏右侧的「正在播放」区（[UfiNowPlayingSlot] 注入，非 null 时标题改为左对齐）。
     */
    nowPlaying: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val palette = LocalResolvedPalette.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 2026-08-20（issue #3）：不要在这里加 statusBarsPadding。
            // 状态栏安全区由 [UfiScreenScaffold] 的根 Column 统一消费一次
            // （`statusBarsPadding()`，2026-09-04 由宿主 Scaffold 移交至此），
            // header 只保留 4.dp 的视觉顶间距。两处都加会把标题往下推两倍状栏高。
            //
            // 带「正在播放」时整条**加高**：右侧那块是双行（曲名/歌手 + 歌词），
            // 按原来的高度会挤到标题栏边缘。
            //
            // 2026-09-17：两套高度整体再放大约 20%（全局）—— 原来的标题栏偏紧，
            // 标题基线离状态栏与内容区都太近。这里只加内边距、不动文字样式，
            // 所以标题与右侧动作的对齐关系不变。
            .then(
                if (nowPlaying != null) {
                    Modifier.padding(
                        top = HEADER_PADDING_TOP_NOW_PLAYING,
                        bottom = HEADER_PADDING_BOTTOM_NOW_PLAYING
                    )
                } else {
                    Modifier.padding(
                        top = HEADER_PADDING_TOP,
                        bottom = HEADER_PADDING_BOTTOM
                    )
                }
            )
            .padding(horizontal = Spacing.HeaderPaddingH)
    ) {
        // Back button (left) — 优先使用自定义 navigationIcon（如带文字的"退出"按钮），
        // 否则回退到默认 ArrowBack。
        if (navigationIcon != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clip(UfiCardDefaults.legacyShape),
                contentAlignment = Alignment.Center
            ) {
                navigationIcon()
            }
        } else if (showBack && onBack != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(48.dp)
                    .clip(UfiCardDefaults.legacyShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        // 2026-09-04（P2d · indication 收口）：原为 `indication = null`，
                        // 而本处**没有任何自绘反馈**（无缩放、无变色）——于是全站的返回键是
                        // 唯一「按下去毫无反应」的可点区。全库 indication 的口径是：
                        // 「自绘缩放反馈 → null；纯图标/文字动作 → 默认 ripple」
                        // （UfiToolbarAction 与 FileManagerRoot 的「退出」都是后者）。
                        // 按同类元素一致的原则改为默认 ripple，头部导航动作从此统一。
                        indication = LocalIndication.current,
                        onClick = onBack
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = palette.accent,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Centered title + subtitle
        val titleBlur = remember { Animatable(8f) }
        LaunchedEffect(title) {
            titleBlur.snapTo(8f)
            // 2026-09-04（P2a）：常量原名 UfiMotion.PAGE_TRANSITION_MS ——「页面转场时长」这个
            // 名字与本处唯一用途（标题模糊入场）毫无关系，改转场时长的人会误改到这里。
            // 已改名为 HEADER_TITLE_BLUR_MS，值 350ms 不变（有意例外，理由见其 KDoc）。
            titleBlur.animateTo(0f, tween(UfiMotion.HEADER_TITLE_BLUR_MS))
        }
        Column(
            modifier = Modifier
                // 有「正在播放」区时标题改**左对齐**（右侧要让出位置给它）；否则保持居中。
                .align(if (nowPlaying != null) Alignment.CenterStart else Alignment.Center)
                // 2026-09-16：标题是**居中绝对定位**，原来没给两侧留位置 —— 于是长标题
                // （视频播放页的文件名）会直接压在左侧返回键和右侧 actions 上面。
                // 这里给两边各留一个动作位的宽度（48dp 触区 + 4dp 呼吸），标题超长就省略号，
                // 而不是叠字。左右对称留白是为了让标题保持真正居中。
                .then(
                    if (nowPlaying != null) {
                        // 左对齐：有返回键才让开它的 48dp，否则直接从页面边距起排。
                        // 宽度封到 [HEADER_TITLE_WIDTH_WITH_NOW_PLAYING] —— 剩下的留给右侧那块。
                        Modifier
                            .padding(
                                start = if (showBack || navigationIcon != null) {
                                    HEADER_TITLE_SIDE_RESERVE
                                } else {
                                    0.dp
                                }
                            )
                            .fillMaxWidth(HEADER_TITLE_WIDTH_WITH_NOW_PLAYING)
                    } else {
                        Modifier.padding(horizontal = HEADER_TITLE_SIDE_RESERVE)
                    }
                ),
            horizontalAlignment = if (nowPlaying != null) {
                Alignment.Start
            } else {
                Alignment.CenterHorizontally
            }
        ) {
            Text(
                text = title,
                style = UfiTextStyles.headerTitle,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (nowPlaying != null) TextAlign.Start else TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    val r = titleBlur.value
                    if (r > 0.5f) {
                        renderEffect = android.graphics.RenderEffect.createBlurEffect(
                            r, r, android.graphics.Shader.TileMode.CLAMP
                        ).asComposeRenderEffect()
                    }
                    alpha = if (r > 4f) 0.6f else 1f
                }
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = UfiTextStyles.headerSubtitle,
                    color = palette.textPrimary.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // 标题下方小字（天气 / 今日诗词，2026-09-18）。排在 subtitle 之下：
            // 页面说明是页面自己的属性、优先靠近标题；挂件是外挂信息，垫在最后一行。
            if (caption != null) {
                Box(modifier = Modifier.padding(top = 3.dp)) { caption() }
            }
        }

        // Actions (right) —— 「正在播放」区排在页面自己的 actions 之前（更靠左），
        // 这样各页面右上角原有按钮的位置不会因为音乐在放而左右跳动。
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            nowPlaying?.invoke()
            actions()
        }
    }
}

/** 有「正在播放」区时标题占的宽度比例：另一半留给它。 */
private const val HEADER_TITLE_WIDTH_WITH_NOW_PLAYING = 0.5f

/**
 * Screen scaffold with custom UfiHeader (no TopAppBar).
 * Public API unchanged for backward compatibility with all screens.
 *
 * ## 安全区（window insets）的唯一落点 —— 2026-09-04 转场区满屏化
 *
 * 主导航的 `Scaffold`（`MainNavGraph`）已把 `contentWindowInsets` 清零，`NavHost` 区域
 * 变成真正满屏（这样状态栏与底部那两条带子才会**随页面一起平移**，inset 边界不再有
 * 「动画到此为止」的静止横线）。代价是安全区必须由**页面侧**自己消费，而本壳就是那个
 * 唯一落点：
 * - 根 `Column`：`background(palette.pageBg)` **然后** `statusBarsPadding()` ——
 *   顺序不可颠倒，见下方「★ 顺序铁律」；
 * - 内容 `Box`：`windowInsetsPadding(WindowInsets.navigationBars)` —— 这一句**本来就在**，
 *   语义不变（header 沉浸在状态栏下、内容避开手势条）。
 *
 * ## ★ 顺序铁律：先铺底色，再收 inset（2026-09-04 白框回归）
 * ```
 * Modifier.fillMaxSize()
 *     .background(palette.pageBg)   // 底色铺到屏幕物理边缘
 *     .statusBarsPadding()          // inset 只收内容
 * ```
 * 反过来（先 padding 再 background、或干脆不画背景）会让状态栏那条带子不属于本页像素，
 * 转场中页面平移 / 缩放时露出窗口底色 —— 真机就是「上下各一道白框」。
 *
 * 两句都用 `windowInsetsPadding` 家族（会**消费**已应用的 inset），所以
 * 「`UfiScreenScaffold` 里再套一层 `UfiScreenScaffold(showHeader = false)`」这种嵌套
 * 不会二次叠加 —— 内层读到的 statusBars 已被外层消费掉，加出来是 0。
 *
 * ## 不走本壳的 NavHost 目的地（审计结论，2026-09-04；同日二次修订）
 * 全部路由都过了一遍 `buildAppScreens`，只有三处不经本壳：
 * 1. `Routes.FILE_EDITOR`（`TextEditorScreen`）—— 自持 M3 `Scaffold` + `TopAppBar`，
 *    `contentWindowInsets` 保持默认 safeDrawing。**结论：不改**。它是编辑器，正文区必须
 *    避开手势条与输入法，页内也没有任何转场 / 平移动画（唯一的动效是加载骨架屏），
 *    因此不存在「动画到此为止」的静止带；宿主清零后它由"可能被叠两次"变成干净的一次。
 * 2. `Routes.FILE_IMAGE`（`ImageViewerScreen`）—— **二次修订：改判为需要清零**。
 *    它的正文自己在做 `graphicsLayer` 缩放 + 平移（捏合 / 拖拽看图），默认 inset 会把
 *    可变换区域内缩，顶部与底部各留一条永远拖不进去的静止黑带。已改为
 *    `contentWindowInsets = WindowInsets(0,0,0,0)`，图片满屏出血；`TopAppBar` 仍用自带的
 *    `TopAppBarDefaults.windowInsets` 避让状态栏，底部两个悬浮控件自补 `navigationBarsPadding()`。
 * 3. 后端服务停止时的占位页 `UfiServiceStoppedNotice`（`ServiceGate` 会**替换掉**任何
 *    非白名单页面的内容，因此它绕过了本壳）—— 已在其内部加 `safeDrawingPadding()`。
 *
 * 另有一处**完全不在 NavHost 内**的页面：`SetupScreen`（未配对时由 `MainActivity` 直接渲染）。
 * 它自持 `Scaffold` + `verticalScroll`，默认 inset 会把 padding 加在滚动**视口**上 ⇒
 * 内容在安全区边界被裁掉、下面留一条 `containerColor` 静止色带。已同样清零，并把
 * `windowInsetsPadding(WindowInsets.safeDrawing)` 挪到 `verticalScroll` **之后**
 * （= 滚动内容的内边距），内容因此能滚到屏幕物理边缘。
 *
 * 其余目的地（含 5 个 Tab 与全部 detail / 设置分组 / 隧道 / 下载 / 监控子页）一律
 * 直接或经 `SettingsSubScaffold` 间接走本壳。
 *
 * 全库再无第二个「默认 `contentWindowInsets` 的 `Scaffold` 包着满屏动画容器」的结构，
 * 也**没有嵌套 `NavHost`**（全库只有 `MainNavGraph` 一处 `NavHost(`）。
 *
 * @param onBack 覆盖返回键动作（2026-09-03 新增，默认 null = 走 popBackStack）。
 *   页面内部有"入口列表 → 二级页"这种局部层级时传它：返回先退到上一级，而不是把整个路由弹掉。
 *   这样调用方不必再自绘 `navigationIcon = { IconButton(ArrowBack) }`（会丢掉公共返回键的
 *   accent 配色与 48dp 命中区）。注意：仅在 [showBack] 为 true 时生效；系统返回键不经这里，
 *   需要拦截时调用方自己加 `BackHandler`。
 */
@Composable
fun UfiScreenScaffold(
    title: String,
    navController: NavHostController? = null,
    showBack: Boolean = false,
    showHeader: Boolean = true,  // 2026-08-11：新增，子页面被外层 Tab 嵌套调用时传 false 避免多重标题栏
    /**
     * 整页底色的额外一层渐变（2026-09-16 新增，默认 null = 只用 `pageBg`）。
     *
     * 加它是因为「顶栏与内容不同色」：音乐播放页的内容区自己画了一层 accent 渐变，
     * 而标题栏在本壳里、只有 `pageBg`，于是标题栏与下方内容之间有一道明显的色阶。
     * 传进来的画刷会铺满**整壳**（含标题栏与状态栏区域），页面内容区因此不必再自己画背景。
     */
    backgroundBrush: Brush? = null,
    /**
     * 是否在本页标题栏显示「正在播放」区（默认 **不显示**）。
     *
     * 默认关掉是因为它会改变标题栏版式（标题左对齐 + 整条加高）：二级页的标题栏往上下都塞满了
     * 东西（返回键、长文件名、页面自己的动作），跟着一起变会把那些页面的布局挤坏。
     * 现在只有首页那 5 个 Tab（仪表盘 / 网络设置 / 监控中心 / 工具 / 设置）显式打开它 ——
     * 那几页的标题栏本来就只有一个标题，位置最富余。
     */
    showNowPlaying: Boolean = false,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onBack: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val palette = LocalResolvedPalette.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            // ★★ 顺序不可调：**先铺满屏底色，再用 inset padding 收内容** ★★
            //
            // 2026-09-04（转场中上下各一道白框）：上一轮把 `statusBarsPadding()` 加到这里时
            // **没有**给本 Column 铺底色，页面底色完全靠祖先（目的地 Box / 宿主 Scaffold
            // 的 containerColor / `window` 背景）兜底。于是状态栏与手势条那两条带子并不是
            // 「本页自己的像素」——转场中本页整体平移（不再缩放、不再离屏裁剪）时，
            // 那两条带子露出的就是祖先兜底的非本页像素 —— 窗口底色，真机观感就是
            // 「上下各一道白框，且因卡片阴影更明显」。
            //
            // 现在本壳自持底色：`background` 排在 `statusBarsPadding()` **之前**，
            // 所以底色铺到屏幕物理边缘、inset 只收内容 —— 无论祖先画不画、图层怎么裁，
            // 这两条带子永远是页面底色。
            .background(palette.pageBg)
            // 页面自带的渐变（可选）：铺在 pageBg 之上、inset 之前，所以标题栏与状态栏区域
            // 都会被它盖住 —— 这正是"顶栏与内容同色"的做法。
            .then(if (backgroundBrush != null) Modifier.background(backgroundBrush) else Modifier)
            // ★ 状态栏安全区的唯一落点（2026-09-04 从 MainNavGraph 的 Scaffold innerPadding
            //   移交至此，理由见本函数 KDoc）。用 statusBarsPadding() 而不是
            //   windowInsetsPadding(safeDrawing)：底部由下面那个内容 Box 单独处理
            //   （header 要沉浸、内容要避让），一把 safeDrawing 会把底部也顶起来，
            //   等于给每一页都加一条与手势条同高的死白边。
            .statusBarsPadding()
    ) {
        // Custom header（v3：showHeader=false 时隐藏，解决多重标题栏问题）
        if (showHeader) {
            UfiHeader(
                title = title,
                showBack = showBack,
                // 2026-08-27 修复白屏：原来无条件 popBackStack()。当本页已是返回栈里唯一一项时，
                // popBackStack() 会把起始目的地也弹掉，NavHost 没有目的地可渲染 → 整屏白屏，
                // 而 Activity 还活着，看起来就是「退出后白屏卡死」。系统返回键由 NavHost 自己
                // 在起始目的地处禁用，但手动调用不受保护，所以这里必须自己判一次。
                onBack = when {
                    !showBack -> null
                    onBack != null -> onBack
                    navController != null -> {
                        {
                            if (navController.previousBackStackEntry != null) {
                                navController.popBackStack()
                            }
                        }
                    }
                    else -> null
                },
                navigationIcon = navigationIcon,
                // 标题栏右侧：正在播放优先，其次今日天气（2026-09-18 天气回到右侧）。
                // 两者共用同一块地：播放态可操作、且转瞬即变，所以它赢；天气是慢变量，
                // 放歌期间暂时隐藏。右侧一旦有内容，标题就改为**左对齐**（见 UfiHeader）。
                nowPlaying = if (!showNowPlaying) null else {
                    UfiNowPlayingSlot.content.value?.takeIf { UfiNowPlayingSlot.active.value }
                        ?: UfiWeatherSlot.content.value?.takeIf { UfiWeatherSlot.active.value }
                },
                // 标题下方小字：今日诗词。与右侧插槽互不影响，两个功能各占一处。
                caption = if (!showNowPlaying) null else {
                    UfiHeaderCaptionSlot.content.value?.takeIf { UfiHeaderCaptionSlot.active.value }
                },
                actions = actions
            )
        }

        // Content area — navigationBars padding applied ONLY here so the header
        // sits flush under the status bar and the home indicator is immersed.
        // 2026-08-24 修复：内容 Box 从 fillMaxSize() 改为 weight(1f)+fillMaxWidth()。
        // 根因：DETAIL 路由走 AnimatedContent 转场，外层在某帧会给 fillMaxSize() 容器
        // 传入 infinity 最大高度，进而让内层 verticalScroll/Column 收到 infinity 直接抛异常。
        // 改用 weight(1f) 后，高度由 Column 父（本身 fillMaxSize、有限）分配，子滚动容器
        // 始终拿到有限高度，从根上消除 infinity 崩溃。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .windowInsetsPadding(WindowInsets.navigationBars)
                // 2026-09-03：点内容区空白处清焦点 + 收键盘。
                //
                // 为什么放在这里：输入框的聚焦态（边框变主色 + 底色染 5% 主色，见 UfiInputDefaults）
                // 在 Compose 里**不会自己消失** —— 点输入框以外的区域默认不触发 clearFocus，于是
                // 高亮一直挂着。桌面端有键盘导航，焦点环常驻是对的；触屏上用户"点完就走"，
                // 常驻高亮就成了脏状态。放在 UfiScreenScaffold 是因为全部页面都套它，一处生效。
                //
                // 不会吃掉子组件的点击：detectTapGestures 跑在 Main pass，子组件（按钮/列表项/
                // 输入框/滚动）会先消费 down 事件，所以 onTap 只在真正的空白处触发。
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    })
                }
        ) {
            content(PaddingValues(0.dp))

            // 2026-09-19：天气下拉浮层（覆盖在内容上方、带遮罩、从顶部滑入）。
            // 放在 content 之后（同一个 Box 内）使它叠在内容上面。
            UfiHeaderDrawerHost()
        }
    }
}

@Composable
fun UfiLoadingBox(
    modifier: Modifier = Modifier,
    isLoading: Boolean,
    content: @Composable () -> Unit
) {
    if (isLoading) {
        Box(
            modifier = modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            UfiLoadingIndicator()
        }
    } else {
        content()
    }
}

@Composable
fun UfiLinearLoading(
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    if (isLoading) {
        LinearProgressIndicator(
            modifier = modifier.fillMaxWidth(),
            color = LocalResolvedPalette.current.accent
        )
    }
}
