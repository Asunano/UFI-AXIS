package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    filePath: String
) {
    val context = LocalContext.current
    val fileName = remember(filePath) { filePath.substringAfterLast("/") }
    val prefs = remember { AppPreferences(context) }

    val streamUrl = remember(filePath) {
        val encoded = URLEncoder.encode(filePath, "UTF-8")
        "http://${prefs.effectiveHost}:${prefs.serverPort}/api/files/stream?path=$encoded"
    }

    // 图片加载走 Application 提供的全局唯一 ImageLoader（含鉴权拦截器），不再自建实例

    // Pinch-to-zoom state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var showTopBar by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = { Text(fileName, fontWeight = UfiWeight.Strong, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.7f),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )
            }
        },
        containerColor = Color.Black,
        // ★★ 2026-09-04（转场区满屏化 · 全库第三处）★★
        //
        // 上一轮把本页判为「M3 自己会处理、无需改」。重新评估后**改判为成立**，理由是
        // 本页的主体内容自己就在做 `graphicsLayer` 平移 + 缩放（下面的双指拖拽/捏合）：
        // 默认的 `contentWindowInsets`（= safeDrawing）把图片容器内缩到安全区里，于是
        // 状态栏与手势条那两条带子永远是 `containerColor` 的纯黑、图片放大后也拖不进去 ——
        // 正是「变换到此为止」的静止带，只是这里它恰好也是黑的、不容易一眼看出。
        // 看图页天生该满屏出血，`TopAppBar` 自带 `TopAppBarDefaults.windowInsets`
        // （systemBars 的 Top + Horizontal），清零 `contentWindowInsets` 不影响它避让状态栏，
        // 也不会双倍 padding（两者本来就是各自独立的一份）。
        //
        // 代价：正文区不再自动避开手势条 —— 图片满屏是**要的**，但底部两个悬浮控件
        // （缩放百分比 / 重置按钮）必须自己补 `navigationBarsPadding()`，见下方。
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 5f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(streamUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .clickable { showTopBar = !showTopBar }
            )

            // Zoom indicator
            if (scale != 1f) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 2026-09-04：contentWindowInsets 已清零（图片要满屏出血），
                        // 悬浮控件自己避让手势条。
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                    shape = MaterialTheme.shapes.small,
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    Text(
                        "${(scale * 100).toInt()}%",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            // Reset zoom button
            if (scale != 1f) {
                IconButton(
                    onClick = {
                        scale = 1f; offsetX = 0f; offsetY = 0f
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // 同上：清零后自己避让手势条。
                        .navigationBarsPadding()
                        .padding(Spacing.PagePadding)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = Color.Black.copy(alpha = 0.6f)
                    ) {
                        Icon(
                            Icons.Default.FitScreen,
                            "重置缩放",
                            tint = Color.White,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}
