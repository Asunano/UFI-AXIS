@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.ufi_axis.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * 页面间共享元素（如"迷你条封面 → 播放页封面"）所需的两个作用域。
 *
 * 为什么用 CompositionLocal 而不是给 `AppScreen` 加参数：`AppScreen` 那个 typealias 被近 90 个
 * 页面 lambda 引用，加一个参数要改全部；而需要共享元素的只有两三个页面。
 *
 * 为什么是 `compositionLocalOf` 而不是 `staticCompositionLocalOf`：这两个 scope 对象每次转场都会
 * 换新实例，static 变体的值一变会让**整棵树**重组（本项目在 `MainNavGraph` 里已经为此栽过一次，
 * 见那里 `LocalCapsuleBottomInset` 的长注释）。普通 local 只重组真正读了它的那几个节点。
 *
 * 两者都可能为 null：不在 [androidx.compose.animation.SharedTransitionLayout] 下、
 * 或页面不是由导航登记处那层 provider 包起来的时候，读到 null 的一方应当退化成
 * "不做共享元素，正常渲染"，不要崩。
 */
val LocalUfiSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * 导航层的 [AnimatedVisibilityScope]（`composable {}` 的 receiver）。
 *
 * 必须是**导航层**那一个：共享元素要跨的是"前一个目的地 → 后一个目的地"，页面内部的
 * `AnimatedContent`（例如播放页的切歌动画）会提供自己的同类型 scope，用错了就变成
 * 在同一页内部找对端，转场不会发生。
 */
val LocalUfiNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** 共享元素的 key：音频封面。全 App 同时只可能有一个播放中的封面，所以用常量而不是按 mediaId 分。 */
const val UFI_SHARED_KEY_AUDIO_COVER = "ufi.shared.audio.cover"

/**
 * 共享元素的 key：播放 / 暂停键。
 *
 * 与封面同理用常量：两端各只有一颗。两端的**图标可能不同**（暂停态是 Pause、播放态是
 * PlayArrow），共享元素补的是**位置与尺寸**而非像素内容，图标不同不影响它成立。
 */
const val UFI_SHARED_KEY_AUDIO_PLAY = "ufi.shared.audio.play"

/** 共享元素的 key：下一首键。与上面两颗同理用常量：两端各只有一颗。 */
const val UFI_SHARED_KEY_AUDIO_NEXT = "ufi.shared.audio.next"

/**
 * 共享元素的 key：上一首键。
 *
 * 2026-09-20 新增。以前迷你条上没有这颗键，单端挂 key 补不出任何行程，所以播放页那颗
 * 刻意不传 key；现在迷你条补上了"上一首"，两端都有对端，三颗控制键才是整组一起长大 / 缩回，
 * 而不是中间两颗动、左边那颗凭空淡出。
 */
const val UFI_SHARED_KEY_AUDIO_PREV = "ufi.shared.audio.prev"

/**
 * 共享元素的 key：歌名。
 *
 * ⚠ 迷你条第一行**有歌词时显示的是歌词**，那种情况下它不挂这个 key —— 两端内容不同，
 * 挂上去等于让"当前这句歌词"变形成歌名。判断写在迷你条那一侧（它才知道自己在显示什么），
 * 播放页这一侧无条件挂：单端有 key 找不到对端时会退化成普通淡入，不会出错。
 */
const val UFI_SHARED_KEY_AUDIO_TITLE = "ufi.shared.audio.title"

/** 共享元素的 key：歌手。两端都恒为歌手（空则整行不画），不存在歌名那种内容错配。 */
const val UFI_SHARED_KEY_AUDIO_ARTIST = "ufi.shared.audio.artist"

/**
 * 共享元素的 key：进度条。
 *
 * 挂在**轨道**那一层而不是外面的触控区：触控区两端厚度差 3 倍，轨道两端一样粗，
 * 于是补间几乎是纯位移（理由详见 `MediaSeekBar` 里的注释）。
 */
const val UFI_SHARED_KEY_AUDIO_SEEK = "ufi.shared.audio.seek"

/**
 * 底部迷你控制条当前占的高度（像素）。0 = 屏上没有迷你条。
 *
 * ## 为什么是进程级单例而不是 CompositionLocal
 * 读它的地方是 NavHost 的 `enterTransition` / `popExitTransition` lambda（见
 * `Navigation.kt` 的 `risePanelEnter`）—— 那里不在任何 Composable 的组合作用域内，
 * 拿不到 CompositionLocal。迷你条全 App 同时只可能有一条在屏上，用单例不产生歧义。
 *
 * ## 为什么写入要带 owner、而不是一个裸 `var`
 * 两个都挂了迷你条的页面之间转场时（音乐列表 ↔ 分组详情），两条迷你条会**同时**在树上，
 * 后离场那一条的 `onDispose` 会晚于新页的 `onSizeChanged` 触发。裸 `var` 在那一刻会被
 * 归零，而屏上明明还有一条（尺寸没变，`onSizeChanged` 不会再补一次）——
 * 于是下一次面板升起就退化成整屏高度。用身份比对之后，只有"当前那条"能把值清掉。
 *
 * 普通 `var` 而非 `MutableState`：唯一读者是转场 lambda，不该建立 snapshot 依赖
 * （与 `UfiNavRecedeRole` 同一条理由）。
 */
object UfiMiniPlayerBarMetrics {
    /** 见类注释。只在屏上确实有迷你条时为正数。 */
    var heightPx: Int = 0
        private set

    /** 当前值是哪一条迷你条写的；身份比对用，不做任何其他用途。 */
    private var owner: Any? = null

    /** 迷你条测到自己的高度时调用。后写入者接管所有权（同时只有一条可见，不存在争用）。 */
    fun report(owner: Any, heightPx: Int) {
        this.owner = owner
        this.heightPx = heightPx
    }

    /** 迷你条离开组合时调用。只有仍持有所有权的那条能清零，理由见类注释。 */
    fun release(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            this.heightPx = 0
        }
    }
}

/*
 * ## 「同一个 key 在同一帧出现两次」这件事，能不能在本文件这一层挡掉？—— 不能，别再找了
 *
 * 2026-09-20 通过 androidx.compose.animation 1.10.4 的源码确认过一遍：
 *
 * - `sharedElement` / `sharedBounds` 的参数里**没有**"只参与本次转场"这类开关。它们全部的
 *   可调项是 boundsTransform / placeholderSize / renderInOverlayDuringTransition /
 *   zIndexInOverlay / clipInOverlayDuringTransition —— 都是"匹配成功之后怎么演"，
 *   管不到"要不要参与匹配"。
 * - 唯一能控制参与与否的是 `rememberSharedContentState(key, config)` 的
 *   `SharedContentConfig.isEnabled`。但它要求**调用点自己知道**"我这一份是过期的那一份"，
 *   而迷你条 / 播放页在本地拿不到这个信息（它们不知道自己属于哪个 NavBackStackEntry，
 *   更不知道另一个实例存不存在）。而且该 config 的 `shouldKeepEnabledForOngoingAnimation`
 *   默认为 true —— 动画已经在跑时把 isEnabled 改成 false **不生效**，
 *   恰恰是重叠转场这个场景里最需要生效的时刻。
 *
 * 结论：重叠只能在**源头**挡，也就是"别让第二次导航在转场中途发出去"。
 * 那道闸在 [ufiNavigateOnce]（理由写在 `UfiNavGuard.kt` 的时间窗常量上）。
 */

/**
 * 把一个节点标记成跨页面共享元素。两个页面上用同一个 key，转场时 Compose 会把它
 * 从起点位置/尺寸补间到终点位置/尺寸。
 *
 * 两个 scope 都从 CompositionLocal **显式**取（而不是依赖隐式 receiver）：调用点往往嵌在
 * 别的动画容器里（播放页封面就在一个 `AnimatedContent` 内），隐式 receiver 会被最近的那个
 * 遮蔽，显式取才拿得到导航层那一个。
 *
 * scope 任一为 null 时返回 Modifier 本身（退化成不共享），这样调用点不需要写 if ——
 * 关闭转场、或把这两个组件放进没有 [androidx.compose.animation.SharedTransitionLayout]
 * 的宿主（预览、测试）时都走这条路。
 *
 * 参数刻意按位置传：`sharedElement` 的形参名在 Compose 各版本间改过
 * （`state` → `sharedContentState`），位置传参不受这种重命名影响。
 */
@Composable
fun Modifier.ufiSharedElement(key: String): Modifier {
    val shared = LocalUfiSharedTransitionScope.current ?: return this
    val anim = LocalUfiNavAnimatedScope.current ?: return this
    return with(shared) {
        this@ufiSharedElement.sharedElement(
            rememberSharedContentState(key),
            anim
        )
    }
}

/**
 * 与 [ufiSharedElement] 同样是"跨页面共享元素"，区别在于**内容会被整体缩放**到补间中的框里。
 *
 * ## 为什么图标类共享元素必须用这条
 * `sharedElement` 的 `placeholderSize` 默认是 `contentSize`：它只补间**外框的位置与尺寸**，
 * 框里的内容仍按自己的固有尺寸测量、不跟着缩放。两端框的尺寸与**长宽比**一旦不同
 * （迷你条那颗键是 40×40 的触达区，播放页那颗图标槽是 52×52），那个不缩放的图标就会
 * 在一个逐帧变形的框里跳 —— 观感就是"抽搐 + 尺寸异常"。
 * `sharedBounds` + `scaleToBounds` 则把子树先按 lookahead 约束量一次稳定尺寸、再整体缩放，
 * 补间过程中**不重新测量**，所以图标是连贯地变大/变小而不是在框里蹦。
 *
 * ## 什么时候仍该用 [ufiSharedElement]
 * 两端都是正方形、内容本身就能随约束自适应的元素（封面那张 `ContentScale.Crop` 的图片）——
 * 那种情况重新测量正是想要的（图片按新尺寸重新裁剪，永远填满），换成缩放反而多一层间接。
 *
 * `ContentScale.Fit` 而不是默认的 `FillWidth`：这两颗键两端都是正方形，Fit 与 FillWidth
 * 结果相同，但 Fit 在将来某端变成非正方形时仍然不会把图标拉变形。
 *
 * ⚠ API 名称在 1.10.x 变过：`ResizeMode.ScaleToBounds` 已改成小写工厂函数
 * `ResizeMode.scaleToBounds(...)`（当前依赖 androidx.compose.animation 1.10.4）。
 * 前两个参数与 [ufiSharedElement] 同样按位置传，理由见那边（形参名改过）。
 *
 * @param scaleContent 内容是否随补间的框整体缩放。
 *
 * 图标类传默认的 true（它们必须整体缩放，见上）。**文字类要传 false**：
 * 文字两端差的不只是字号（迷你条 bodyEmphasis → 播放页 sectionTitle），更差在
 * 节点宽度（迷你条那一列一百多 dp、播放页是整屏宽）。缩放按两个方向里**较小**的比例走，
 * 于是起点会把整行字先缩到三四成再长大 —— 观感是"字从很小弹出来"，而且放大方向上
 * 字被当成图片拉伸、边缘发虚。false 走"重新测量"：框在补间中变宽，文字按新宽度重排
 * （长歌名从省略号里展开），字号的切换由 sharedBounds 自带的淡入淡出盖住，不发虚也不弹。
 */
@Composable
fun Modifier.ufiSharedBounds(key: String, scaleContent: Boolean = true): Modifier {
    val shared = LocalUfiSharedTransitionScope.current ?: return this
    val anim = LocalUfiNavAnimatedScope.current ?: return this
    return with(shared) {
        val contentState = rememberSharedContentState(key)
        this@ufiSharedBounds.sharedBounds(
            contentState,
            anim,
            resizeMode = if (scaleContent) {
                SharedTransitionScope.ResizeMode.scaleToBounds(
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center,
                )
            } else {
                SharedTransitionScope.ResizeMode.RemeasureToBounds
            },
        )
    }
}
