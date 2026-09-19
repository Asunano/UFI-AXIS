package com.ufi_axis.ui.screens.filemanager

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.Image
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size as CoilSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import com.ufi_axis.ui.components.common.UfiSlider
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.ufi_axis.ui.theme.*
import com.ufi_axis.data.api.FileItem
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.data.media.UfiAudioLyrics
import com.ufi_axis.util.AppPreferences
// 播放器核心在 :app:feature-media（2026-09-16 下沉）：带设备签名的 OkHttp、PlayerView 布局、
// 全屏副作用都在那边，本文件只负责浮层的组织方式。R 也指向那个模块（布局搬过去了）。
import com.ufi_axis.ui.media.UfiFullscreenSystemUiEffect
import com.ufi_axis.ui.media.UfiStreamHttpClient
import com.ufi_axis.feature.media.R
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * 视频加载最小重试次数：覆盖设备 WiFi 直连的瞬时连接抖动（2001=IO_NETWORK_CONNECTION_FAILED），
 * 让首次连接失败自行退避重试，避免一抖就黑屏报错。
 */
private const val MINIMUM_LOADABLE_RETRY_COUNT = 4

/**
 * 音频预览右上角关闭按钮的直径，以及为它在内容区顶部预留的高度。
 *
 * 2026-09-14：这个按钮原来是 40dp、直接叠在内容之上，而音频布局的右列正好是歌词 ——
 * 按钮压在歌词首行/「暂无歌词」文案上。现在缩到 32dp 并**在内容区顶部留出一条空白带**，
 * 让按钮落在带子里而不是盖在歌词上。留白必须 ≥ 按钮直径 + 上边距，否则又会重叠。
 */
private val AUDIO_CLOSE_BUTTON_SIZE = 32.dp
private val AUDIO_CLOSE_STRIP_HEIGHT = 38.dp

/**
 * 文件预览悬浮窗（卡片式）。
 *
 * 取代原先「navController.navigate 进 ImageViewerScreen / MediaScreen 独立页」的做法：
 * 预览直接在文件管理器之上叠一层 in-composition 浮层，文件管理器保持在后台、列表/滚动/
 * 选择状态全部保留，关闭后无跳转感。
 *
 * 视觉语言与全站弹窗一致：cardBg 卡片 + dialogShape + ufiCardShadow 灰影 + dialogBorder 描边。
 * 背后用「模糊文件管理器内容 + 轻量主题化 scrim」实现磨砂观感（不依赖系统跨窗模糊，API 全版本一致）。
 *
 * 动效：进出场用设计系统 [UfiMotion] 令牌的 fade；卡片缩放走 [androidx.compose.ui.draw.graphicsLayer]
 * 而非 AnimatedVisibility 的 scaleIn——后者在进入动画帧会把含 fillMaxWidth 的内容约束压成 0 宽，
 * 触发 `Can't represent a width of 0` 约束崩溃（已在 2026-09-11 真机崩溃日志中复现）。
 *
 * 关闭方式：右上角 × / 系统返回键 / 点 scrim 空白。
 */
@Composable
fun FilePreviewOverlay(
    target: FileItem?,
    onDismiss: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit = {}
) {
    // 退场动画期间仍需展示内容：target 置空后保留最后一次非空值。
    var shown by remember { mutableStateOf(target) }
    LaunchedEffect(target) { if (target != null) shown = target }

    val cardScale by animateFloatAsState(
        targetValue = if (target != null) 1f else 0.94f,
        animationSpec = tween(UfiMotion.Duration.Fluid, easing = UfiAnimSpecs.emphasizedInEasing),
        label = "previewCardScale"
    )

    AnimatedVisibility(
        visible = target != null,
        // 关键：AnimatedVisibility 自身必须 fillMaxSize，否则黑色 scrim 不会铺满全屏
        // （默认只取内容自然尺寸，缩放动画期间更会露边）。
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(UfiMotion.Duration.Fluid, easing = UfiAnimSpecs.emphasizedInEasing)),
        exit = fadeOut(tween(UfiMotion.Duration.Fluid, easing = UfiMotion.Easing.Accelerate))
    ) {
        val item = shown ?: return@AnimatedVisibility
        val kind = fileKindOf(item.name)
        if (kind != FileKind.IMAGE && kind != FileKind.VIDEO && kind != FileKind.AUDIO) return@AnimatedVisibility

        val palette = LocalResolvedPalette.current
        val maxCardH = with(LocalConfiguration.current) { (screenHeightDp * 0.82f).dp }
        // 卡片最大宽度 = 屏幕 92%，图片自适应时以此为上限。
        val maxCardW = with(LocalConfiguration.current) { (screenWidthDp * 0.92f).dp }
        // 视频预览需要更充分利用屏幕以突出画面，取 96% 屏宽（仍留压暗 scrim 呼吸边）。
        val videoCardW = with(LocalConfiguration.current) { (screenWidthDp * 0.96f).dp }
        // 图片：卡片宽度跟图片实际尺寸走（由 ImagePreviewContent 上报 fitSize.width），
        // 避免「卡片固定 0.92 屏宽、图片较窄」时在右侧（及左侧）露出 cardBg 空白边。
        var imageCardSize by remember(item.path) { mutableStateOf<DpSize?>(null) }
        val imageColWidth = imageCardSize?.width ?: (maxCardW.value * 0.7f).dp
        // 视频全屏态：由 MediaPreviewContent 内 PlayerView 全屏按钮切换；全屏时卡片撑满全屏、隐藏标题行。
        var videoFullscreen by remember(item.path) { mutableStateOf(false) }
        LaunchedEffect(videoFullscreen) { onFullscreenChange(videoFullscreen) }
        // 关闭预览（target 置空）时复位全屏态：否则关闭同一视频文件（item.path 不变、
        // remember(item.path) 不重新播种）会让 videoFullscreen 卡在 true，下次打开该文件直接进全屏；
        // 复位后 FullscreenSystemUiEffect 的 enabled 变 false，系统栏在退场动画期间即恢复。
        LaunchedEffect(target) {
            if (target == null) videoFullscreen = false
        }
        UfiFullscreenSystemUiEffect(enabled = kind == FileKind.VIDEO && videoFullscreen)

        // scrim：轻量主题化遮罩（非纯黑），点空白关闭。真正「模糊」由 FileManagerRoot
        // 在预览打开时把文件管理器内容做 blur 实现，此处只负责压暗，让卡片更聚焦。
        // 视频全屏时 scrim 完全透明、不消费点击，让 PlayerView 真正铺满屏幕；
        // 非全屏时保持压暗 + 点空白关闭。
        val scrimAlpha = if (kind == FileKind.VIDEO && videoFullscreen) 0f else 0.45f
        val scrimModifier = if (kind == FileKind.VIDEO && videoFullscreen) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .fillMaxSize()
                .background(palette.pageBg.copy(alpha = scrimAlpha))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        }
        Box(
            modifier = scrimModifier,
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    // 图片按原始尺寸自适应：卡片宽 = 图片宽（上限 maxCardW）；
                    // 音频沿用 0.92 屏宽；视频预览更突出画面，取 0.96 屏宽。
                    .then(
                        when {
                            kind == FileKind.IMAGE -> Modifier.width(imageColWidth)
                            kind == FileKind.VIDEO && videoFullscreen -> Modifier.fillMaxSize()
                            kind == FileKind.VIDEO -> Modifier.width(videoCardW)
                            else -> Modifier.width(maxCardW)
                        }
                    )
                    .then(
                        if (kind == FileKind.VIDEO && videoFullscreen) Modifier else Modifier.heightIn(max = maxCardH)
                    )
                    .graphicsLayer { scaleX = cardScale; scaleY = cardScale }
                    .background(
                        if (kind == FileKind.VIDEO && videoFullscreen) palette.surfaceMuted else palette.cardBg,
                        if (kind == FileKind.VIDEO && videoFullscreen) androidx.compose.ui.graphics.RectangleShape else UfiCardDefaults.dialogShape
                    )
                    .then(
                        if (kind == FileKind.VIDEO && videoFullscreen) Modifier else {
                            Modifier
                                .ufiCardShadow(elevation = UfiCardDefaults.elevationLevel3Dp, shape = UfiCardDefaults.dialogShape)
                                .clip(UfiCardDefaults.dialogShape)
                                .border(UfiCardDefaults.hairlineBorderWidth, palette.dialogBorder, UfiCardDefaults.dialogShape)
                        }
                    )
                    // 消费卡片内部点击，避免冒泡到 scrim 触发关闭（与 UfiDialogShell 同款模式）
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { }
            ) {
                when (kind) {
                    FileKind.IMAGE -> {
                        // 标题行：参照公共弹窗 UfiDialogShell —— 标题用 dialogTitle 字阶（与详情弹窗同款层级），
                        // 关闭按钮底色用 pageBg(0.85) 而非 surfaceMuted（公共组件口径，灰圈几乎融入卡片、不抢眼）；
                        // 标题与内容之间不画分隔线，用 Spacing.Large 留呼吸（公共组件同款），避免多一道横线割裂。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.DialogPaddingH, vertical = Spacing.Small),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.name,
                                style = UfiTextStyles.dialogTitle.copy(fontWeight = UfiWeight.Strong),
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            PreviewCloseButton(onDismiss)
                        }
                        Spacer(Modifier.height(Spacing.Large))
                        key(item.path) { ImagePreviewContent(item.path, maxCardW, onSize = { imageCardSize = it }) }
                    }
                    FileKind.VIDEO -> key(item.path) {
                        // 视频：标题（文件名+关闭）叠加在画面上方，视频真正铺满卡片、顶到弹窗四边。
                        MediaPreviewContent(
                            filePath = item.path,
                            mediaType = "video",
                            fullscreen = videoFullscreen,
                            playing = target != null,
                            onToggleFullscreen = { videoFullscreen = !videoFullscreen },
                            fileName = item.name,
                            onClose = onDismiss
                        )
                    }
                    FileKind.AUDIO -> {
                        // 沉浸式：去掉顶部文件名标题栏，仅把关闭按钮浮在卡片右上角。
                        // 歌曲信息（标题/艺人/专辑）已由下方 SongMeta 展示，顶栏标题冗余。
                        //
                        // 按钮虽然是叠放的，但音频内容自己在顶部留了 AUDIO_CLOSE_STRIP_HEIGHT
                        // 的空白带（见 AudioPreviewContent 的 padding），所以不会压到右列歌词。
                        Box(modifier = Modifier.fillMaxWidth()) {
                            key(item.path) { MediaPreviewContent(item.path, "audio", playing = target != null) }
                            PreviewCloseButton(
                                onDismiss,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = Spacing.Small, end = Spacing.DialogPaddingH),
                                size = AUDIO_CLOSE_BUTTON_SIZE
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun PreviewCloseButton(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val palette = LocalResolvedPalette.current
    // 关闭按钮底色对齐公共弹窗 UfiDialogShell：pageBg(0.85) 而非 surfaceMuted，
    // 让灰圈几乎融入卡片、不抢视觉；图标走 textSecondary。
    Surface(
        onClick = onDismiss,
        shape = CircleShape,
        color = palette.pageBg.copy(alpha = 0.85f),
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭",
                tint = palette.textSecondary,
                modifier = Modifier.size(size / 2)
            )
        }
    }
}

/**
 * 图片预览：按图片原始尺寸自适应悬浮窗大小（保持宽高比，限制于屏幕 92% 宽 / 82% 高内），
 * 小图卡片小、大图卡片大。捏合缩放 + 重置按钮，加载态有进度环，整体走主题化底（surfaceMuted）。
 */
@Composable
private fun ImagePreviewContent(filePath: String, maxCardW: Dp, onSize: (DpSize) -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val palette = LocalResolvedPalette.current
    val density = LocalDensity.current
    val prefs = remember { AppPreferences(appContext) }
    val streamUrl = remember(filePath) {
        val encoded = URLEncoder.encode(filePath, "UTF-8")
        "http://${prefs.effectiveHost}:${prefs.serverPort}/api/files/stream?path=$encoded"
    }
    val configuration = LocalConfiguration.current
    val maxCardH = with(configuration) { (screenHeightDp * 0.82f).dp }
    // 图片区域可用高度 = 卡片上限减去标题行(~56dp)与内边距(~32dp)
    val imageMaxH = (maxCardH - 88.dp).coerceAtLeast(160.dp)

    // 用 painter 取原始尺寸（请求 ORIGINAL 以拿到真实宽高，供自适应计算）
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(appContext)
            .data(streamUrl)
            .crossfade(true)
            .size(CoilSize.ORIGINAL)
            .build()
    )
    val intrinsic = painter.intrinsicSize
    val fitSize = remember(intrinsic, maxCardW, imageMaxH) {
        fitImageSize(
            iw = with(density) { intrinsic.width.toDp() },
            ih = with(density) { intrinsic.height.toDp() },
            maxW = maxCardW,
            maxH = imageMaxH
        )
    }
    // 把图片实际显示尺寸上报给外层卡片，使卡片宽度与图片对齐（消除右侧空白边）。
    LaunchedEffect(fitSize) { onSize(fitSize) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // 基准（fit）尺寸转像素：用于计算放大后可平移的最大范围，把图片拖在视口内、不露底色空白。
    val baseWpx = with(density) { fitSize.width.toPx() }
    val baseHpx = with(density) { fitSize.height.toPx() }

    Box(
        modifier = Modifier
            .size(fitSize.width, fitSize.height)
            .background(palette.surfaceMuted)
            .clip(UfiCardDefaults.dialogShape),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // 不允许缩小到 fit 尺寸以下：scale 下限锁 1f（fit 即图片最大显示尺寸）。
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        // 仅在放大后允许平移；平移范围 = 放大后溢出量的一半，
                        // 超出即把图片拖出视口、露出底色空白。scale=1 时上限为 0，故不可移动。
                        val maxX = baseWpx * (newScale - 1f) / 2f
                        val maxY = baseHpx * (newScale - 1f) / 2f
                        scale = newScale
                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                        offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                    }
                }
        )

        if (painter.state is AsyncImagePainter.State.Loading) {
            CircularProgressIndicator(
                color = palette.accent,
                modifier = Modifier.size(32.dp)
            )
        }

        if (scale != 1f) {
            IconButton(
                onClick = { scale = 1f; offsetX = 0f; offsetY = 0f },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.PagePadding)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = palette.cardBg,
                    shadowElevation = 2.dp,
                    border = androidx.compose.foundation.BorderStroke(
                        UfiCardDefaults.hairlineBorderWidth,
                        palette.inputBorder
                    )
                ) {
                    Icon(
                        Icons.Default.FitScreen,
                        "重置缩放",
                        tint = palette.accent,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

/**
 * 按图片原始宽高比，把显示尺寸限制在 [maxW]×[maxH] 内：不放大超过原始尺寸，
 * 但极小值设下限避免图标类小图变成米粒。返回 DpSize 供悬浮窗卡片直接使用。
 */
private fun fitImageSize(iw: Dp, ih: Dp, maxW: Dp, maxH: Dp): DpSize {
    if (!iw.value.isFinite() || !ih.value.isFinite() || iw.value <= 0f || ih.value <= 0f) {
        // 加载中：给一个中性占位尺寸
        return DpSize((maxW.value * 0.7f).dp, (maxH.value * 0.6f).coerceAtMost(maxH.value).dp)
    }
    var s = min(maxW.value / iw.value, maxH.value / ih.value)
    if (s > 1f) s = 1f
    var w = iw.value * s
    var h = ih.value * s
    val minW = 180f
    val minH = 140f
    if (w < minW) { val k = minW / w; w = minW; h *= k }
    if (h < minH) { val k = minH / h; h = minH; w *= k }
    if (w > maxW.value) { val k = maxW.value / w; w = maxW.value; h *= k }
    if (h > maxH.value) { val k = maxH.value / h; h = maxH.value; w *= k }
    return DpSize(w.dp, h.dp)
}

/**
 * 视频 / 音频预览：复用原 MediaScreen 的 ExoPlayer 逻辑。
 * 视频用 TextureView（XML `surface_type=texture_view`）铺满卡片并经 Compose `clip(dialogShape)`
 * 裁成圆角，文件名+关闭按钮以渐变遮罩叠加在画面上方，视频真正顶到弹窗四边；
 * 音频用渐变背景 + 专辑占位 + 控制条。播放器随浮层挂载创建、卸载（退场动画结束后）经 DisposableEffect 释放。
 * 增加错误监听：播放失败时把原因显示在卡片内，避免「黑屏无法播放」且无可观测信息。
 *
 * @param fileName 叠加在视频上方的文件名（默认取路径末段）。
 * @param onClose  视频上方关闭按钮回调（默认空）。
 */
@OptIn(UnstableApi::class)
@Composable
private fun MediaPreviewContent(
    filePath: String,
    mediaType: String,
    fullscreen: Boolean = false,
    playing: Boolean = true,
    onToggleFullscreen: () -> Unit = {},
    fileName: String = filePath.substringAfterLast("/"),
    onClose: () -> Unit = {}
) {
    val context = LocalContext.current
    // 用 Application Context 避免把 Activity 传给 ExoPlayer / 拦截器长生命周期对象，防止内存泄漏。
    val appContext = context.applicationContext
    val palette = LocalResolvedPalette.current
    val prefs = remember { AppPreferences(appContext) }
    val streamUrl = remember(filePath) {
        val encoded = URLEncoder.encode(filePath, "UTF-8")
        "http://${prefs.effectiveHost}:${prefs.serverPort}/api/files/stream?path=$encoded"
    }

    var errorMsg by remember(filePath) { mutableStateOf<String?>(null) }

    // 关键：/stream 走 AuthMiddleware，强制校验 Bearer + 设备签名（X-Timestamp/X-Nonce/X-Signature），
    // 且 X-Nonce 防重放。图片预览能正常是因为全局 Coil ImageLoader 复用同一条 authInterceptor。
    // 因此 ExoPlayer 必须也走「共享 OkHttp + authInterceptor」：
    //   - 不能裸 DefaultHttpDataSource：它无法逐请求重签，第二个 Range 请求会因 nonce 复用被拒（2004）；
    //   - 也不能只塞静态 Bearer：缺签名同样被 401/444 拒。
    // OkHttpDataSource 每发一个分片请求都过拦截器，自动带最新签名 + 时间戳 + nonce，并自带 401 重签重试。
    val mediaHttpClient = remember { UfiStreamHttpClient.get(appContext) }
    val exoPlayer = remember(filePath) {
        val dataSourceFactory = OkHttpDataSource.Factory(mediaHttpClient)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            // 设备链路偶发抖动：提高加载重试次数，让瞬时连接失败（2001=IO_NETWORK_CONNECTION_FAILED）
            // 自行退避重试，避免一抖就黑屏报错；重试耗尽后才回调 onPlayerError 弹「重试」按钮。
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(MINIMUM_LOADABLE_RETRY_COUNT))
        ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build().apply {
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        // 暴露 errorCode 与根因（含底层异常类型），便于定位：
                        // 2001=IO_NETWORK_CONNECTION_FAILED（连不上/连接被断，非鉴权拒绝 401/444）；
                        // 2004=HTTP 状态码非 2xx（签名/路径问题）。
                        val cause = error.cause
                        errorMsg = "播放失败(${error.errorCode}): ${error.message ?: cause?.message ?: "未知错误"}" +
                            (cause?.let { " [${it.javaClass.simpleName}]" } ?: "")
                    }
                })
            }
    }

    // 键必须是 exoPlayer 而不是 Unit：上面的 `remember(filePath)` 会在**同一个浮层内切换
    // 媒体文件**时换出一个新的 ExoPlayer。若这里用 Unit，切换文件时 dispose 分支不会执行，
    // 旧播放器（及其网络连接、音频焦点、解码线程）就永远不释放 —— 连续预览几个视频即累积。
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    // 关闭预览（退场动画期间）暂停播放：避免视频帧在 exit 动画里继续解码，与 scrim 淡出 / 背景模糊 snap
    // 抢 GPU，导致掉帧卡顿（播放中关闭尤为明显）。playing 仅在 target 置空前为 true；退场期间置 false 即暂停。
    LaunchedEffect(playing) {
        if (playing) exoPlayer.play() else exoPlayer.pause()
    }

    if (mediaType == "video") {
        // 视频铺满卡片：无内缩；TextureView 可被 Compose clip 成圆角，视频真正顶到弹窗四边。
        val videoBoxModifier = if (fullscreen) {
            Modifier.fillMaxSize().background(palette.surfaceMuted)
        } else {
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(palette.cardBg)
        }
        Box(modifier = videoBoxModifier) {
            if (errorMsg != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.surfaceMuted),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = palette.error,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = errorMsg ?: "播放失败",
                            style = UfiTextStyles.note,
                            color = palette.textSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            onClick = {
                                errorMsg = null
                                runCatching { exoPlayer.seekToDefaultPosition() }
                                exoPlayer.prepare()
                                exoPlayer.play()
                            },
                            shape = MaterialTheme.shapes.small,
                            color = palette.cardBg,
                            shadowElevation = 2.dp,
                            border = BorderStroke(UfiCardDefaults.hairlineBorderWidth, palette.inputBorder)
                        ) {
                            Text(
                                "重试",
                                color = palette.accent,
                                style = UfiTextStyles.bodyEmphasis,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        // 用 XML 指定 texture_view，使视频可被 Compose clip 成圆角（SurfaceView 无法被裁剪）。
                        // controller 也走自定义布局，把标题、关闭、全屏都纳入 media3 控制条，
                        // 随控制器一起显示/隐藏，不再额外叠加 Compose 层。
                        val playerView = LayoutInflater.from(ctx)
                            .inflate(R.layout.player_view_media, null, false) as PlayerView
                        playerView.player = exoPlayer
                        playerView.useController = true
                        playerView.setShowNextButton(false)
                        playerView.setShowPreviousButton(false)
                        // 循环：开启「关 → 单曲循环 → 列表循环」三态切换（DEFAULT = one|all）。
                        playerView.setRepeatToggleModes(PlayerControlView.DEFAULT_REPEAT_TOGGLE_MODES)
                        // 字幕：视频含内嵌字幕轨时显示字幕按钮；无字幕轨自动隐藏（不占空间）。
                        playerView.setShowSubtitleButton(true)
                        // 缓冲指示器：设备流式加载时显示缓冲转圈，弱网/首帧可观测（仅播放中缓冲时显示）。
                        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        // 倍速：media3 设置弹窗（exo_settings 齿轮）随 Player 速度能力自动出现，
                        // 内含 0.5x–2x 与音轨/字幕选择，无需额外开关。
                        // 关闭按钮交给自定义 controller layout（沉浸式右上角，无标题栏），
                        // 点击视频区域时与播放控制条同步出现/隐藏。
                        playerView.findViewById<ImageButton>(R.id.ufi_preview_close)?.setOnClickListener { onClose() }
                        // 全屏按钮用 media3 官方 FullscreenButtonClickListener 注册：1.10.0 的
                        // StyledPlayerControlView 默认隐藏 exo_fullscreen，只有设置该 listener 后
                        // 才会显示底部全屏按钮并自动切换进入/退出图标；手动 setOnClickListener 无效。
                        playerView.setFullscreenButtonClickListener { onToggleFullscreen() }
                        playerView
                    },
                    update = { playerView ->
                        // resizeMode 必须在 view 复用期间响应 fullscreen 变化；factory 只在初次创建时执行。
                        playerView.resizeMode = if (fullscreen) {
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        } else {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        // 同步全屏按钮图标（进入/退出），随 videoFullscreen 状态变化。
                        playerView.setFullscreenButtonState(fullscreen)
                        // 关闭预览（退场动画期间 playing=false）立即隐藏视频层，
                        // 避免视频帧跟着 fadeOut/scale 逐帧重合成导致掉帧卡顿（播放中关闭最明显）。
                        playerView.visibility = if (playing) View.VISIBLE else View.GONE
                    },
                    modifier = if (fullscreen) Modifier.fillMaxSize()
                    else Modifier.fillMaxSize().clip(UfiCardDefaults.dialogShape)
                )
            }

            // 标题、关闭、全屏已全部嵌入自定义 media3 controller layout，
            // 随控制条一起显示/隐藏，不再叠加 Compose 层。
        }
    } else {
        // 音频预览：错误优先显示错误卡（与视频一致但保持原音频布局语义）。
        if (errorMsg != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp)
                    .background(palette.surfaceMuted),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = palette.error,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = errorMsg ?: "播放失败",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        onClick = {
                            errorMsg = null
                            runCatching { exoPlayer.seekToDefaultPosition() }
                            exoPlayer.prepare()
                            exoPlayer.play()
                        },
                        shape = MaterialTheme.shapes.small,
                        color = palette.cardBg,
                        shadowElevation = 2.dp,
                        border = BorderStroke(UfiCardDefaults.hairlineBorderWidth, palette.inputBorder)
                    ) {
                        Text(
                            "重试",
                            color = palette.accent,
                            style = UfiTextStyles.bodyEmphasis,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            return
        }
        AudioPlayerUi(
            exoPlayer = exoPlayer,
            httpClient = mediaHttpClient,
            prefs = prefs,
            filePath = filePath,
            fileName = fileName,
            palette = palette
        )
    }
}

/**
 * 音频预览播放器（重构版）：音乐播放器样式。
 * - 元数据：取自 ExoPlayer 解析出的 [MediaMetadata]（标题/艺术家/专辑/内嵌封面），
 *   无标签时回退到文件名（去扩展名）。
 * - 歌词：走公共实现 [UfiAudioLyrics]（同目录同名 `.lrc` → 内嵌 ID3 USLT/SYLT 或 FLAC LYRICS），
 *   与音乐播放页共用同一份；都取不到则显示「暂无歌词」。
 * - 播放控制：自建 播放/暂停 + 进度条 + 当前/总时长，替代默认 PlayerView 控制器（避免音频 SurfaceView 在
 *   退场动画里重合成掉帧，也统一为音乐播放器观感）。
 */
@OptIn(UnstableApi::class)
@Composable
private fun AudioPlayerUi(
    exoPlayer: ExoPlayer,
    httpClient: okhttp3.OkHttpClient,
    prefs: AppPreferences,
    filePath: String,
    fileName: String,
    palette: ResolvedPalette
) {
    var metadata by remember { mutableStateOf<MediaMetadata?>(null) }
    var lyrics by remember { mutableStateOf<List<UfiAudioLyrics.Line>?>(null) }
    var lyricsLoading by remember { mutableStateOf(true) }

    // 播放态（进度/时长/是否播放）单一数据源：内部 250ms 轮询，仅被控件/歌词子项读取 .value，
    // 父级不读 .value，故封面/标题等稳定子项在进度推进时不重合成（性能优化关键点）。
    val positionMs = remember(exoPlayer) { mutableLongStateOf(0L) }
    val durationMs = remember(exoPlayer) { mutableLongStateOf(0L) }
    val isPlaying = remember(exoPlayer) { mutableStateOf(true) }
    // 拖拽态：拖动进度条时本地草稿（scrubPos）接管显示与 seek，轮询暂停回写，
    // 避免「轮询回写播放位置」与「用户手指位置」互相打架导致卡顿/回跳；松手才提交一次 seek。
    val scrubbing = remember { mutableStateOf(false) }
    val scrubPos = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!scrubbing.value) {
                positionMs.value = exoPlayer.currentPosition
                durationMs.value = if (exoPlayer.duration == C.TIME_UNSET) 0L else exoPlayer.duration
                isPlaying.value = exoPlayer.isPlaying
            }
            delay(250)
        }
    }

    // 元数据监听：ExoPlayer 解析到 ID3 后回调 onMediaMetadataChanged。
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(m: MediaMetadata) { metadata = m }
        }
        exoPlayer.addListener(listener)
        metadata = exoPlayer.mediaMetadata
        onDispose { exoPlayer.removeListener(listener) }
    }

    // 歌词：走公共实现 [UfiAudioLyrics]（同目录 .lrc → 内嵌 USLT/SYLT/FLAC LYRICS），
    // 与音乐播放页共用同一份取词与解析。
    // api / mediaId 传 null：文件管理器里的文件不一定在音频库里（没扫到就没有 id），
    // 问 core 的 /api/media/lyrics 也无从下手，直接从这两级按路径找。
    LaunchedEffect(filePath) {
        lyricsLoading = true
        lyrics = null
        lyrics = UfiAudioLyrics.load(
            api = null,
            mediaId = null,
            httpClient = httpClient,
            prefs = prefs,
            filePath = filePath
        )?.lines
        lyricsLoading = false
    }

    // sidecar 封面（2026-09-14 新增，与 web 端同口径）：
    // ExoPlayer 只给内嵌封面（artworkData），而大量 FLAC / 整轨是外挂封面 ——
    // 同目录躺着 cover.jpg 却显示音符占位，是这次双端对齐要补的一处。
    // 只在内嵌封面确实缺失时才去探：有内嵌就不该多发请求。
    var sidecarCover by remember(filePath) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(filePath, metadata?.artworkData == null) {
        if (metadata?.artworkData != null) {
            sidecarCover = null
            return@LaunchedEffect
        }
        sidecarCover = loadSidecarCover(httpClient, prefs, filePath)
    }

    // 歌词区高度必须与左列（封面 + 歌曲信息）**逐像素一致**：上边界对齐封面上沿，
    // 下边界对齐歌曲信息下沿。左列内容是固定高度（封面 150dp + 间距 + 三行文字），
    // 所以由左列实测高度反向约束歌词列，而不是两边各自 fillMaxHeight 后靠 Row 居中"看起来差不多"。
    var lyricsColumnHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(colors = listOf(palette.accentContainer, palette.cardBg)))
            // 内边距对齐公共弹窗 UfiDialogShell（DialogPaddingH 18dp）；纵向收紧，避免上下过空。
            // top 用 AUDIO_CLOSE_STRIP_HEIGHT：给右上角关闭按钮留一条空白带，
            // 否则按钮会压在右列歌词的首行上（2026-09-14 修复）。
            .padding(
                start = Spacing.DialogPaddingH,
                end = Spacing.DialogPaddingH,
                top = AUDIO_CLOSE_STRIP_HEIGHT,
                bottom = Spacing.Medium
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 上半区：左列控件（封面+歌名）/ 右列歌词，50/50 横排；高度封顶，避免卡片被纵向拉得过长。
        //
        // 2026-09-14 第二轮：在上一轮 −15%（200→170 / 300→255）之上再收 10% → 153 / 230。
        // 顶对齐（Alignment.Top）而非居中：居中会让左列内容上下各留一段空白，
        // 而歌词列是 fillMaxHeight，结果歌词比"封面上沿→信息下沿"这一段高出去 ——
        // 那正是"显示区域没固定住"的观感来源。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 153.dp, max = 230.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 左列：封面 + 歌曲信息。不再 fillMaxHeight —— 它自己的内容高度就是这一区的基准，
            // 实测值上报给歌词列使用。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .onSizeChanged { lyricsColumnHeightPx = it.height },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 内嵌封面优先，没有才用同目录 sidecar 图（与 web 端回退顺序一致）
                CoverArtwork(metadata?.artworkData ?: sidecarCover, palette)
                Spacer(Modifier.height(Spacing.Medium))
                SongMeta(metadata, fileName, palette)
            }
            // 右列：歌词（随进度滚动高亮当前行，当前行居中）。
            // 高度锁到左列实测值；首帧还没测到时先不给固定高度（避免 0dp 一帧闪烁）。
            LyricsPanel(
                lyrics = lyrics,
                lyricsLoading = lyricsLoading,
                exoPlayer = exoPlayer,
                palette = palette,
                positionMs = positionMs,
                scrubbing = scrubbing,
                scrubPos = scrubPos,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (lyricsColumnHeightPx > 0) {
                            Modifier.height(with(density) { lyricsColumnHeightPx.toDp() })
                        } else {
                            Modifier.fillMaxHeight()
                        }
                    )
            )
        }
        Spacer(Modifier.height(Spacing.Small))
        // 底部全宽控制条：进度条（公共组件 UfiSlider）+ 时间 + 下方「后退 | 播放 | 前进」三连（带文字说明）。
        AudioProgressControls(
            exoPlayer = exoPlayer,
            palette = palette,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            scrubbing = scrubbing,
            scrubPos = scrubPos
        )
    }
}

/**
 * 同目录 sidecar 封面（2026-09-14 新增，与 web 端 `trySidecarCover` 同口径）。
 *
 * ExoPlayer 只给内嵌封面；无内嵌图的文件（大量 FLAC、整轨）在同目录会躺着一张图。
 * 候选顺序照常见播放器：同名图优先，再看整张专辑共用的 cover / folder / front。
 *
 * 每个候选先用 `Range: bytes=0-0` 花 1 个字节探存在，命中后才整取 ——
 * 盲目整取会在每次打开一首没封面的歌时白拉 5 次几百 KB。
 */
private suspend fun loadSidecarCover(
    httpClient: okhttp3.OkHttpClient,
    prefs: AppPreferences,
    filePath: String
): ByteArray? {
    val base = filePath.substringBeforeLast('.', missingDelimiterValue = filePath)
    val dir = filePath.substringBeforeLast('/', missingDelimiterValue = "")
    val candidates = buildList {
        listOf("jpg", "jpeg", "png", "webp").forEach { add("$base.$it") }
        listOf("cover.jpg", "cover.png", "folder.jpg", "front.jpg").forEach {
            add(if (dir.isEmpty()) it else "$dir/$it")
        }
    }
    val host = "http://${prefs.effectiveHost}:${prefs.serverPort}/api/files/stream?path="
    for (candidate in candidates) {
        val url = host + URLEncoder.encode(candidate, "UTF-8")
        val exists = try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(
                    Request.Builder().url(url).header("Range", "bytes=0-0").build()
                ).execute().use { it.isSuccessful && (it.body?.bytes()?.isNotEmpty() == true) }
            }
        } catch (_: Exception) { false }
        if (!exists) continue
        val data = try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(Request.Builder().url(url).build())
                    .execute().use { if (it.isSuccessful) it.body?.bytes() else null }
            }
        } catch (_: Exception) { null }
        if (data != null && data.size > 32) return data
    }
    return null
}

/** 毫秒格式化为 m:ss。 */
private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

/** 封面：内嵌 artwork（ID3 APIC）优先，否则音符占位；柔和投影 + 极细描边让封面从卡片上浮起。 */
@Composable
private fun CoverArtwork(artwork: ByteArray?, palette: ResolvedPalette) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .size(150.dp)
            .shadow(elevation = 10.dp, shape = shape, clip = false)
            .clip(shape)
            .background(palette.accent.copy(alpha = 0.12f))
            .border(1.dp, palette.accent.copy(alpha = 0.18f), shape),
        contentAlignment = Alignment.Center
    ) {
        if (artwork != null) {
            Image(
                painter = rememberAsyncImagePainter(model = artwork),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = palette.accent
            )
        }
    }
}

/** 歌曲信息：标题/艺术家/专辑（无标签回退文件名）。 */
@Composable
private fun SongMeta(metadata: MediaMetadata?, fileName: String, palette: ResolvedPalette) {
    val displayName = metadata?.title?.toString()?.takeIf { it.isNotBlank() }
        ?: fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
    val artist = metadata?.artist?.toString()?.takeIf { it.isNotBlank() } ?: "未知艺人"
    Text(
        displayName,
        style = UfiTextStyles.dialogTitle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = palette.textPrimary
    )
    Spacer(Modifier.height(4.dp))
    Text(
        artist,
        style = UfiTextStyles.bodyEmphasis,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = palette.textSecondary
    )
    metadata?.albumTitle?.toString()?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(2.dp))
        Text(
            it,
            style = UfiTextStyles.note,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = palette.textSecondary
        )
    }
}

/**
 * 播放控制：进度条（公共组件 UfiSlider，自绘轨道/thumb 全站统一画法）+ 时间 + 下方「后退 | 播放 | 前进」三连，播放键居中。
 * 拖拽性能：拖动中由 [scrubPos] 草稿接管显示、轮询暂停回写，松手（onValueChangeFinished）才提交一次 seek，
 * 避免每帧 seekTo 排队 + 轮询位置回写与手指位置打架造成的卡顿。
 */
@Composable
private fun AudioProgressControls(
    exoPlayer: ExoPlayer,
    palette: ResolvedPalette,
    positionMs: MutableLongState,
    durationMs: MutableLongState,
    isPlaying: MutableState<Boolean>,
    scrubbing: MutableState<Boolean>,
    scrubPos: MutableState<Float>
) {
    // 时长未知（ExoPlayer 尚未解析完成的瞬间）时禁用滑块：避免 valueRange 退化 0..1、
    // 缩放手势被 seek 到 ~0ms 造成回跳。时长就绪后再启用。
    val hasDuration = durationMs.value > 0
    val dur = if (hasDuration) durationMs.value.toFloat() else 1f
    // 拖动中用草稿值，否则用轮询播放位置（拖动时轮询已暂停，故不打架）。
    val cur = if (scrubbing.value) scrubPos.value else positionMs.value.toFloat().coerceAtMost(dur)
    // 进度条（公共组件），占满左右宽度；时长未就绪时禁用（enabled=false 不接手势、整体淡化）。
    UfiSlider(
        value = if (hasDuration) cur else 0f,
        enabled = hasDuration,
        onValueChange = {
            scrubbing.value = true
            scrubPos.value = it
        },
        onValueChangeFinished = {
            // 松手才真正 seek 一次，并把位置立即同步给轮询态，避免松手瞬间回跳。
            val target = scrubPos.value.toLong().coerceAtLeast(0)
            exoPlayer.seekTo(target)
            positionMs.value = target
            scrubbing.value = false
        },
        valueRange = 0f..dur,
        modifier = Modifier.fillMaxWidth()
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            formatTime(if (scrubbing.value) scrubPos.value.toLong() else positionMs.value),
            style = UfiTextStyles.note, color = palette.textSecondary
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (hasDuration) formatTime(durationMs.value) else "--:--",
            style = UfiTextStyles.note, color = palette.textSecondary
        )
    }
    Spacer(Modifier.height(Spacing.Small))
    // 控制三连：后退 | 播放 | 前进，播放键居中；整组在宽度内居中，每个按钮下方加文字说明。
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Large, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayerButton(
            icon = Icons.Filled.FastRewind,
            label = "后退",
            onClick = { exoPlayer.seekTo((exoPlayer.currentPosition - 10_000).coerceAtLeast(0)) },
            palette = palette,
            iconSize = 26.dp
        )
        PlayerButton(
            icon = if (isPlaying.value) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            label = if (isPlaying.value) "暂停" else "播放",
            onClick = {
                exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                isPlaying.value = exoPlayer.playWhenReady
            },
            palette = palette,
            iconSize = 36.dp
        )
        PlayerButton(
            icon = Icons.Filled.FastForward,
            label = "前进",
            onClick = {
                val d = if (exoPlayer.duration == C.TIME_UNSET) Long.MAX_VALUE else exoPlayer.duration
                exoPlayer.seekTo((exoPlayer.currentPosition + 10_000).coerceAtMost(d))
            },
            palette = palette,
            iconSize = 26.dp
        )
    }
}

/**
 * 控制按钮：图标 + 下方文字说明。点击区 40dp，图标略小，整体与公共弹窗组件的按钮节奏一致。
 */
@Composable
private fun PlayerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    palette: ResolvedPalette,
    iconSize: Dp = 26.dp
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(icon, contentDescription = label, tint = palette.accent, modifier = Modifier.size(iconSize))
        }
        Spacer(Modifier.height(Spacing.Small))
        Text(label, style = UfiTextStyles.note, color = palette.textSecondary)
    }
}

/**
 * 歌词面板：当前行基于播放进度派生（仅本子项随进度重合成）。
 * 点击某行跳转；进度推进时自动滚动到当前行（当前行居中，而非贴顶）。
 */
@Composable
private fun LyricsPanel(
    lyrics: List<UfiAudioLyrics.Line>?,
    lyricsLoading: Boolean,
    exoPlayer: ExoPlayer,
    palette: ResolvedPalette,
    positionMs: MutableLongState,
    scrubbing: MutableState<Boolean>,
    scrubPos: MutableState<Float>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // 是否带时间轴：纯文本歌词（时间戳为负）不高亮、不自动滚动、不可点击跳转，
    // 只当作可手动滚动的静态文本渲染 —— 否则会假装"当前行"骗人。
    val synced = lyrics?.any { it.timeMs >= 0L } == true
    // 上下留白：取「(视口高 − 单行高)/2」，使首尾行也能滚动到正中（否则首行最多贴顶）。
    var centerPad by remember { mutableStateOf(0.dp) }
    // 拖拽时跟随草稿位置（与进度条同一份 scrub 状态），松手恢复轮询位置。
    // 位置在首句之前时默认指向第 0 行 → 开局即居中第一句（而非空在顶部）。
    val currentPositionMs = if (scrubbing.value) scrubPos.value.toLong() else positionMs.value
    val currentLine = remember(lyrics, currentPositionMs, synced) {
        if (!synced) -1
        else {
            val idx = lyrics?.indexOfLast { it.timeMs <= currentPositionMs } ?: -1
            if (idx < 0) 0 else idx
        }
    }
    // 进度变化 → 当前行滚动到居中（contentPadding 已保证首/尾行可居中）。
    // 拖拽中（scrubbing）用瞬时 scrollToItem 跟随手指 1:1，避免动画追着移动目标导致发顿；
    // 正常播放推进用 animateScrollToItem 平滑归位。
    LaunchedEffect(currentLine) {
        if (lyrics.isNullOrEmpty() || currentLine < 0) return@LaunchedEffect
        if (scrubbing.value) listState.scrollToItem(currentLine)
        else listState.animateScrollToItem(currentLine)
    }
    // 首帧布局完成后：测量单行高，算出上下留白并把初始当前行平滑居中（开局视口为 0 时避免贴顶）。
    LaunchedEffect(lyrics) {
        if (lyrics.isNullOrEmpty() || !synced) return@LaunchedEffect
        val info = snapshotFlow { listState.layoutInfo }
            .first { it.viewportSize.height > 0 && it.visibleItemsInfo.isNotEmpty() }
        val vp = info.viewportSize.height
        val h = info.visibleItemsInfo.firstOrNull()?.size ?: 0
        val pad = ((vp - h) / 2).coerceAtLeast(0)
        centerPad = with(density) { pad.toDp() }
        listState.animateScrollToItem(currentLine)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = Spacing.Medium),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            lyricsLoading -> {
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator(color = palette.accent, modifier = Modifier.size(28.dp))
            }
            lyrics.isNullOrEmpty() -> {
                Spacer(Modifier.height(8.dp))
                Text("暂无歌词", style = UfiTextStyles.note, color = palette.textSecondary)
            }
            else -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                LazyColumn(
                    state = listState,
                    // 上下留白使首尾行可居中；内容超出时 LazyColumn 原生滚动（长歌词支持滚动）。
                    contentPadding = PaddingValues(vertical = centerPad),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(lyrics.size) { i ->
                        val (t, line) = lyrics[i]
                        val active = synced && i == currentLine
                        // 渐入渐出（强化）：当前行清晰高亮，其余行淡出并轻微缩放，切换平滑过渡；去掉模糊。
                        val activeAnim = animateFloatAsState(
                            targetValue = if (active) 1f else 0f,
                            animationSpec = tween(
                                durationMillis = UfiMotion.Duration.Fluid,
                                easing = UfiMotion.Easing.Standard
                            )
                        )
                        val a = activeAnim.value
                        val lineAlpha = lerp(0.3f, 1f, a)
                        val lineScale = lerp(0.92f, 1f, a)
                        Text(
                            text = line,
                            // 放大字号：当前行 bodyEmphasis、其余行 body（原为 note），并加大行距减少可见行数。
                            style = if (active) UfiTextStyles.bodyEmphasis else UfiTextStyles.body,
                            color = if (active) palette.accent else palette.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    alpha = lineAlpha
                                    scaleX = lineScale
                                    scaleY = lineScale
                                }
                                .padding(vertical = Spacing.Medium)
                                // 无时间轴时不挂点击：跳 t=-1 只会把播放位置甩到开头。
                                .then(if (synced) Modifier.clickable { exoPlayer.seekTo(t) } else Modifier)
                        )
                    }
                }
            }
        }
    }
}
