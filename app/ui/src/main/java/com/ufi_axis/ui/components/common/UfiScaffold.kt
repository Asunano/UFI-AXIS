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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
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
            .padding(top = 4.dp, bottom = 8.dp)
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
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = UfiTextStyles.headerTitle,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
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
        }

        // Actions (right)
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = actions
        )
    }
}

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
            // 「本页自己的像素」——转场中本页整体平移 / 缩放（`ufiNavRecedeLayer` 的
            // `CompositingStrategy.Offscreen` 会把内容裁到图层边界）时，那两条带子露出的是
            // 图层外的窗口底色，真机观感就是「上下各一道白框，且因卡片阴影更明显」。
            //
            // 现在本壳自持底色：`background` 排在 `statusBarsPadding()` **之前**，
            // 所以底色铺到屏幕物理边缘、inset 只收内容 —— 无论祖先画不画、图层怎么裁，
            // 这两条带子永远是页面底色。
            .background(palette.pageBg)
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
