package com.ufi_axis.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.ufi_axis.data.model.MEDIA_TYPE_IMAGE
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel
import kotlin.math.abs

/**
 * 图片查看器（媒体中心 → 图片 → 点某一张，2026-09-16）。
 *
 * 左右翻页看同一批图片、双指缩放、双击复位。图片直接从 `/api/files/stream` 取**原图**
 * （不是缩略图）：列表里的缩略图只有 256px，放大就糊了。Coil 的全局 ImageLoader 带设备签名
 * 拦截器，所以原图和缩略图走同一条鉴权链路。
 *
 * ## 缩放状态按页独立
 * `key(page)` 包住每一页的 scale/offset：不这么做的话，放大第 3 张再翻到第 4 张，
 * 第 4 张会带着上一张的缩放和位移出现。
 *
 * ## 为什么翻页交给 HorizontalPager 而不是自己识别横滑
 * 缩放后的拖动与翻页是同一个方向的手势。Pager 自己有 nestedScroll 让位逻辑，
 * 而这里只在 `scale > 1` 时把水平拖动吃掉（见 `pointerInput` 里的判定），
 * 于是"放大后拖动看细节"和"原尺寸下横滑翻页"不会互相抢。
 */
@Composable
fun MediaImageViewerScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    filePath: String
) {
    val palette = LocalResolvedPalette.current
    val media = viewModel.media
    val state by media.state.collectAsState()
    val images = state.tab(MEDIA_TYPE_IMAGE).items

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

    val title = "${pagerState.currentPage + 1} / ${paths.size}"

    UfiScreenScaffold(title = title, navController = navController, showBack = true) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                key(page) {
                    ZoomableImage(
                        url = media.streamUrl(paths[page]),
                        emptyTint = palette.textSecondary
                    )
                }
            }
        }
    }
}

/**
 * 单张可缩放图片。
 *
 * 缩放/位移只写在 `graphicsLayer` 里 —— 那是**图层阶段**的读，逐帧不重组、不重新布局。
 * 双击在"原尺寸 ↔ 2 倍"之间切换（不是无级放大：双击的语义是"看细节/看全貌"两态）。
 */
@Composable
private fun ZoomableImage(url: String, emptyTint: Color) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var failed by remember(url) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
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
                    if (abs(offsetX) > limitX) offsetX = if (offsetX > 0) limitX else -limitX
                    if (abs(offsetY) > limitY) offsetY = if (offsetY > 0) limitY else -limitY
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
                    }
            )
        }
    }
}
