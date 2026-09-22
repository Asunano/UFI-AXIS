package com.ufi_axis.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_ARTIST
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_COVER
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_NEXT
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_PLAY
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_PREV
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_SEEK
import com.ufi_axis.ui.navigation.UFI_SHARED_KEY_AUDIO_TITLE
import com.ufi_axis.ui.navigation.UfiMiniPlayerBarMetrics
import com.ufi_axis.ui.navigation.ufiNavigateOnce
import com.ufi_axis.ui.navigation.ufiSharedBounds
import com.ufi_axis.ui.navigation.ufiSharedElement
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 音乐页贴底的迷你控制条：一条可拖拽的进度条 + 封面/曲名/歌手 + 上一首/播放暂停/下一首。
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

    val isPlaying = UfiNowPlayingState.isPlaying.value
    val hasPrev = UfiNowPlayingState.hasPrev.value
    val hasNext = UfiNowPlayingState.hasNext.value
    val artwork = UfiNowPlayingState.artworkUrl.value
    val artist = UfiNowPlayingState.artist.value
    // 标题兜底走与列表 / 播放页同一条取值链（见 MediaListCommon 里那段说明），
    // 免得同一首歌在这里显示成另一个样子
    val title = UfiNowPlayingState.title.value
        .ifBlank { audioFileBase(mediaId.substringAfterLast('/')) }

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
            .onSizeChanged { UfiMiniPlayerBarMetrics.report(token, it.height) }
            .background(palette.cardBg)
    ) {
        // 内层：承担安全区避让。inset 落在这里，底色才不会被一起顶上去
        Column(modifier = Modifier.navigationBarsPadding()) {
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
                sharedKey = UFI_SHARED_KEY_AUDIO_SEEK
            )


            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MINI_BAR_CONTENT_PADDING_H,
                        vertical = MINI_BAR_CONTENT_PADDING_V
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                // 封面 + 文字整块可点 → 全屏播放页。两个按钮在这块之外，点它们不会连带跳页
                Row(
                    modifier = Modifier
                        .weight(1f)
                        // ufiNavigateOnce 而不是 navigate：连点两下会 push 两个播放页实例
                        // （返回要按两次）。守卫按目的地去重，不挡时间窗，理由见该函数。
                        .clickable {
                            navController.ufiNavigateOnce(mediaRouteOf("media/audio", mediaId))
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
                         * 歌手、进度条、三颗键仍然连贯，缺的只是歌名这一个节点）。
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
                 * 三颗键也是共享元素：对端是播放页主控三键里那三个固定 52dp 的正方形图标槽。
                 * 挂在 IconButton 这一层（而不是里面的 Icon）——共享元素补的是**节点边界**，
                 * 取触达区这个稳定的 40dp 方框，转场中不会因为图标随播放状态换形而跳变。
                 *
                 * 用 ufiSharedBounds 而不是 ufiSharedElement（2026-09-20 修"抽搐"）：
                 * 后者只补间外框、框里的图标仍按固有尺寸测量，40dp 与 52dp 两端一补间，
                 * 那个不缩放的图标就在一个逐帧变尺寸的框里跳。理由详见 ufiSharedBounds。
                 * 封面那条仍用 ufiSharedElement —— 它两端都是正方形、内容是能随约束重新裁剪的
                 * 图片，重新测量正是想要的效果。
                 *
                 * ## 加第三颗键之后的横向预算（360dp 最窄常见屏，2026-09-20 核算）
                 * 336（屏宽 − 左右各 12dp 内边距）− 120（3×40dp 按钮）− 24（3 道 8dp 间距）
                 * = 192 给左侧那块；再减封面 48 与它与文字之间的 8 ⇒ 文字列还有 **136dp**。
                 * 够放 9 个左右的汉字，所以按钮与封面的尺寸**都不用动** ——
                 * 40dp 已经是触达区下限档，48dp 封面又与曲目行共用同一个令牌，
                 * 两者任一再缩都是在没有必要的地方降标准。
                 */
                IconButton(
                    onClick = { controller.seekToPreviousMediaItem() },
                    enabled = hasPrev,
                    modifier = Modifier
                        .size(MINI_BAR_BUTTON_SIZE)
                        .ufiSharedBounds(UFI_SHARED_KEY_AUDIO_PREV)
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "上一首",
                        // 队列到头时这颗是真的按不动，颜色也要说明这一点
                        tint = if (hasPrev) {
                            palette.textPrimary
                        } else {
                            palette.textSecondary.copy(alpha = MINI_BAR_DISABLED_ALPHA)
                        }
                    )
                }
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
                IconButton(
                    onClick = { controller.seekToNextMediaItem() },
                    enabled = hasNext,
                    modifier = Modifier
                        .size(MINI_BAR_BUTTON_SIZE)
                        .ufiSharedBounds(UFI_SHARED_KEY_AUDIO_NEXT)
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        // 队列到底时这颗是真的按不动，颜色也要说明这一点
                        tint = if (hasNext) {
                            palette.textPrimary
                        } else {
                            palette.textSecondary.copy(alpha = MINI_BAR_DISABLED_ALPHA)
                        }
                    )
                }
            }
        }
    }
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

/** 封面圆角。通栏条本身是直角，封面这里留一点圆角把它与两侧的文字/按钮区分开。 */
private val MINI_BAR_COVER_CORNER = 8.dp

/** 三颗按钮的触达区。40dp 仍在可靠点击区内，比旧的 44dp 小一档以压住整条的高度。 */
private val MINI_BAR_BUTTON_SIZE = 40.dp

/**
 * 第一行与歌手行之间的行距。
 *
 * 2dp 而不是 0：两行都是左对齐的小字，紧贴时会被读成"一句话折了行"。也不能再大 ——
 * 这一列的高度预算是封面那 48dp，间距吃掉的每一 dp 都从两行文字的可用高度里出。
 */
private val MINI_BAR_TEXT_GAP = 2.dp

/** 不可用时的图标透明度：看得出"有这颗键"，也看得出"现在按不动"。 */
private const val MINI_BAR_DISABLED_ALPHA = 0.4f

