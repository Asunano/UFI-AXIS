package com.ufi_axis.ui.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.ufi_axis.data.model.MEDIA_TYPE_IMAGE
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 图片查看器 —— **灯箱（lightbox）**（2026-10-21 改版）。
 *
 * ## 为什么不再用页壳（`UfiScreenScaffold`）
 * 上一版套的是普通二级页：标题栏 "1 / 12"、状态栏底色、内容区黑底。那是"另一页"的语言，
 * 而看图要的是"图片浮在暗场里"——用户的诉求就是"不符合设计"。
 *
 * 现在这一页**没有页壳**：
 * · 整屏一块纯黑底（[LIGHTBOX_SCRIM]），图片按 fit 居中、四周留白、圆角 + 阴影，像放在灯箱里；
 * · 顶部/底部是一条**自动隐藏**的浮层（关闭 / 计数 / 更多，底部是文件名），
 *   点图片中间任意处切换显隐，观图时不被工具栏占掉一块；
 * · 关闭走左上角的 ×（系统返回键同样生效，这是导航栈上的普通目的地）。
 *
 * ## 手势优先级（别乱改顺序）
 * 1. 双击：原尺寸 ⇄ 2 倍（看细节 / 看全貌两态，不做无级）；
 * 2. 双指 / 拖动：放大后拖动看细节，**只在 scale > 1 时**把水平拖动吃掉；
 * 3. 单击：切换浮层（不是关闭 —— 误触就退出看图是最恼人的体验）；
 * 4. 左右划：原尺寸下交给 Pager 翻页。
 *
 * 图片取的是**原图**（`/api/files/stream`）而不是列表里 256px 的缩略图：灯箱里放大看的是细节，
 * 喂缩略图必然糊。鉴权仍走全局 Coil ImageLoader 那条设备签名拦截链路。
 */
@Composable
fun MediaImageViewerScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    filePath: String
) {
    val palette = LocalResolvedPalette.current
    val context = LocalContext.current
    val media = viewModel.media
    val state by media.state.collectAsState()
    val images = state.tab(MEDIA_TYPE_IMAGE).items

    /*
     * 灯箱是近黑的整屏底色，而系统状态栏图标是 `MainActivity` 按**应用主题**定过一次的
     * （浅色主题 → 深色图标）。不接管的话，浅色主题下顶部那排图标压在近黑底上等于消失。
     * 这里在本页在场期间强制用**浅色图标**，离开时还原 —— 与视频播放页/UfiVideoPlayer
     * 里那套 appearance 处理同一个思路。
     */
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = findActivityForBarIcons(view.context)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (controller != null) {
                previousStatus?.let { controller.isAppearanceLightStatusBars = it }
                previousNav?.let { controller.isAppearanceLightNavigationBars = it }
            }
        }
    }

    LaunchedEffect(Unit) { media.loadFirstPage(MEDIA_TYPE_IMAGE) }

    // 列表还没到时先只显示路由带来的那一张；到了之后把 pager 定位到它
    val paths = remember(images, filePath) {
        images.map { it.path }.takeIf { it.isNotEmpty() } ?: listOf(filePath)
    }
    val startIndex = remember(paths) { paths.indexOf(filePath).takeIf { it >= 0 } ?: 0 }
    val pagerState = rememberPagerState(initialPage = startIndex) { paths.size }

    // 列表后到：把当前页对到那张图（否则会停在第 0 张）
    LaunchedEffect(paths) {
        val target = paths.indexOf(filePath)
        if (target >= 0 && pagerState.currentPage != target && pagerState.pageCount > target) {
            pagerState.scrollToPage(target)
        }
    }

    // 浮层（关闭 / 计数 / 更多 / 文件名）的显隐：点图片切换，翻页时自动露出一下
    var chromeVisible by remember { mutableStateOf(true) }
    LaunchedEffect(pagerState.currentPage) {
        chromeVisible = true
    }

    val currentPath = paths.getOrNull(pagerState.currentPage) ?: filePath
    val currentName = currentPath.substringAfterLast('/')

    /*
     * 图片操作状态（编辑 / 重命名 / 删除 / toast）：底栏按钮直接写它，
     * 弹窗与编辑器由 MediaImageActionDialogs 统一渲染 —— 与网格长按共用同一份实现。
     */
    val actions = remember { MediaImageActionState() }
    val currentItem = remember(paths, pagerState.currentPage, images) {
        images.firstOrNull { it.path == currentPath }
            // 列表还没到时先用路由那一张拼一个最小 item：菜单里的重命名/删除只需要 path 与 name
            ?: MediaLibraryItem(id = 0, name = currentName, path = currentPath)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LIGHTBOX_SCRIM)
    ) {
        // ── 图片本体：灯箱留白 + 圆角 + 阴影 ──
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = Spacing.Medium
        ) { page ->
            val url = remember(paths, page) { media.streamUrl(paths[page]) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = LIGHTBOX_SIDE_PADDING,
                        // 上下多留一截：浮层是压在图片之上的，图片不该钻到关闭键底下
                        vertical = LIGHTBOX_VERTICAL_PADDING
                    ),
                contentAlignment = Alignment.Center
            ) {
                // key(page)：缩放/位移按页独立，翻页不会把上一张的倍率带过去
                androidx.compose.runtime.key(page) {
                    ZoomableImage(
                        url = url,
                        emptyTint = palette.textSecondary,
                        onTap = { chromeVisible = !chromeVisible }
                    )
                }
            }
        }

        // ── 顶部浮层：关闭 / 文件名 + 页数 ──
        //
        // 2026-10-21（用户要求）：文件名从**左下角**挪到顶部中间，页数改到它下面一行。
        // 理由：看图时"这是哪一张"与"第几张"是同一类信息，分处屏幕两端反而要来回看；
        // 而且底部要留给动作条，文件名占着只会把动作条顶高一截。
        AnimatedVisibility(
            visible = chromeVisible && actions.editTarget == null,
            enter = fadeIn(tween(UfiMotion.Duration.Swift)),
            exit = fadeOut(tween(UfiMotion.Duration.Flick)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Small, vertical = Spacing.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = currentName,
                        style = UfiTextStyles.bodyLeadStrong,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${pagerState.currentPage + 1} / ${paths.size}",
                        style = UfiTextStyles.caption,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                Spacer(Modifier.weight(1f))
                // 与左侧 × 等宽的占位：这样中间那列才是屏幕正中，而不是被按钮挤偏
                Spacer(Modifier.size(48.dp))
            }
        }

        // ── 底部浮层：动作条 ──
        //
        // 2026-10-21（用户要求）：原来右上角那个三点菜单挪到这里。
        // 理由：灯箱里"对这张图做什么"本来就该和图片在同一块视觉区域里（底部手指够得着），
        // 而顶部只留"是谁 / 第几张"。菜单项与网格长按仍是同一份实现，只是换了触发方式。
        AnimatedVisibility(
            visible = chromeVisible && actions.editTarget == null,
            enter = fadeIn(tween(UfiMotion.Duration.Swift)),
            exit = fadeOut(tween(UfiMotion.Duration.Flick)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.Medium),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val target = currentItem
                    // 图片编辑入口本轮隐藏（见 MEDIA_IMAGE_EDIT_VISIBLE），下个版本优化后再放出来
                    if (MEDIA_IMAGE_EDIT_VISIBLE) {
                        LightboxAction("编辑", Icons.Default.Edit) {
                            target?.let { actions.editTarget = it }
                        }
                    }
                    LightboxAction("重命名", Icons.Default.DriveFileRenameOutline) {
                        target?.let { actions.renameTarget = it }
                    }
                    LightboxAction("下载", Icons.Default.Download) {
                        target?.let { actions.downloadToPhone(viewModel, it) }
                    }
                    LightboxAction("复制路径", Icons.Default.ContentCopy) {
                        target?.let { actions.copyPath(viewModel, it) }
                    }
                    LightboxAction("删除", Icons.Default.Delete, destructive = true) {
                        target?.let { actions.deleteTarget = it }
                    }
                }
            }
        }

        /*
         * 编辑器**就地**渲染在灯箱这一层 Box 里（2026-10-21）。
         *
         * 这样进出只是一次 Compose 的淡入淡出 + 0.97→1 缩放，不经历"新窗口合成"那一帧 ——
         * 用户报的"切换时闪烁"正是那个空帧。挂在 Box 内部也让浮层拿到整屏约束（不会被量成不定尺寸）。
         */
        MediaImageEditorOverlay(
            visible = actions.editTarget != null,
            item = actions.editTarget,
            url = actions.editTarget?.let { media.streamUrl(it.path) }.orEmpty(),
            onDismiss = { actions.editTarget = null },
            onSaved = { message, type -> actions.notify(message, type) },
            exportToPhone = { file, name -> viewModel.files.exportLocalToDownloads(file, name) },
            uploadCopy = { uri, dir -> viewModel.files.uploadFileToServer(uri, dir) },
            fileProviderAuthority = "${context.packageName}.fileprovider"
        )
    }

    /*
     * 重命名 / 删除确认 / 图片编辑 / toast：与网格长按共用同一套弹窗与编辑器
     * （见 MediaImageItemMenu.kt）。灯箱里不再有 Popup 菜单。
     */
    MediaImageActionDialogs(viewModel, actions, inlineEditor = true)
}

/**
 * 灯箱底栏的一颗动作：图标 + 文字。
 *
 * 用"图标 + 文字"而不是纯图标：删除 / 重命名 / 编辑这几个动作在图标语义上互相容易混，
 * 而灯箱底栏横向空间够（超宽时自己横向滚动）。
 */
@Composable
private fun LightboxAction(
    label: String,
    icon: ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val tint = if (destructive) Color(0xFFFF6B6B) else Color.White
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = UfiTextStyles.caption, color = tint.copy(alpha = 0.9f))
    }
}

/**
 * 单张可缩放图片。
 *
 * 缩放/位移只写在 `graphicsLayer` 里 —— 那是**图层阶段**的读，逐帧不重组、不重新布局。
 * 双击在"原尺寸 ↔ 2 倍"之间切换（不是无级放大：双击的语义是"看细节/看全貌"两态）。
 * 单击回调给灯箱切换浮层用（不是关闭）。
 *
 * 灯箱观感：圆角 + 投影。投影不是装饰 —— 暗底上的亮图没有它就会"贴"在背景上，
 * 看不出这是一张"浮起来的照片"。
 */
@Composable
private fun ZoomableImage(url: String, emptyTint: Color, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var failed by remember(url) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2f
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        // 回到原尺寸就归位，否则图片会歪在一边
                        offsetX = 0f
                        offsetY = 0f
                    }
                    // 放大后不让位移跑太远（按缩放倍数给一个粗略边界，避免图片被拖出屏幕外找不回来）
                    val limitX = size.width * (scale - 1f) / 2f
                    val limitY = size.height * (scale - 1f) / 2f
                    if (kotlin.math.abs(offsetX) > limitX) {
                        offsetX = if (offsetX > 0) limitX else -limitX
                    }
                    if (kotlin.math.abs(offsetY) > limitY) {
                        offsetY = if (offsetY > 0) limitY else -limitY
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.BrokenImage,
                    contentDescription = null,
                    tint = emptyTint,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(Spacing.Small))
                Text("这张图片打不开", style = UfiTextStyles.note, color = emptyTint)
            }
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                        // 圆角跟缩放一起走：放大后仍是一张"带圆角的照片"，而不是被裁成方角
                        shape = LIGHTBOX_IMAGE_SHAPE
                        clip = true
                        shadowElevation = LIGHTBOX_IMAGE_SHADOW.toPx()
                    }
            )
        }
    }
}

/** 灯箱底色：近黑但留一点透明度，压得住亮图、又不至于像纯黑那样"断了层"。 */
internal val LIGHTBOX_SCRIM = Color.Black

/** 从任意 Context 找到宿主 Activity（系统栏 appearance 只能从 Window 改）。 */
private fun findActivityForBarIcons(context: Context): Activity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** 灯箱图片的圆角与投影（与播放页封面同一套语言：大圆角 + 轻投影）。 */
internal val LIGHTBOX_IMAGE_SHAPE = RoundedCornerShape(14.dp)
private val LIGHTBOX_IMAGE_SHADOW = 24.dp

/** 灯箱留白：左右窄、上下宽（上下要给浮层让位，所以比左右多留一截）。 */
internal val LIGHTBOX_SIDE_PADDING = 16.dp
private val LIGHTBOX_VERTICAL_PADDING = 88.dp
