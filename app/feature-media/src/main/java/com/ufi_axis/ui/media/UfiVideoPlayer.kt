package com.ufi_axis.ui.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.ImageButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.data.model.MediaSubtitleEntry
import com.ufi_axis.feature.media.R
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.OkHttpClientProvider
import java.util.concurrent.TimeUnit

/**
 * 播放器核心 —— 全站**唯一一份**（2026-09-16 从 `:app:feature-files` 下沉到 `:app:feature-media`）。
 *
 * 谁在用：
 *  · 媒体中心的视频播放页（playlist、上/下一集）
 *  · 文件管理器的预览浮层（单文件）
 *
 * 下沉的理由：media3 依赖、两个 `PlayerView` 布局、以及"带设备签名的 DataSource"这套东西
 * 只该存在一份。之前它长在 feature-files 里，媒体中心要播就得复制一遍 —— 那必然会分叉。
 */

/**
 * 视频加载最小重试次数：覆盖设备 WiFi 直连的瞬时连接抖动
 * （2001 = IO_NETWORK_CONNECTION_FAILED），让首次连接失败自行退避重试，避免一抖就黑屏报错。
 */
private const val MINIMUM_LOADABLE_RETRY_COUNT = 4

/**
 * 视频 / 音频流专用 OkHttpClient。
 *
 * `/api/files/stream` 挂在 AuthMiddleware 之后，强制校验 Bearer + 设备签名
 * （`X-Timestamp` / `X-Nonce` / `X-Signature`），且 nonce 防重放。所以：
 *  · **不能**裸用 `DefaultHttpDataSource`：它无法逐请求重签，第二个 Range 请求就会因 nonce 复用被拒；
 *  · **不能**只塞一个静态 Bearer：缺签名同样 401/444。
 * `OkHttpDataSource` 每发一个分片请求都过拦截器，自动带最新签名 + 时间戳 + nonce。
 *
 * 单例缓存的理由（踩过的坑）：以前每开一次预览就 `newBuilder().build()`、释放时 `shutdown()`
 * 它的 dispatcher —— 而 `newBuilder()` 复用的是 [OkHttpClientProvider.shared] 的**同一个**
 * dispatcher，于是把全局调度器关掉了，后续所有网络请求报 "executor rejected"。
 * 现在一个进程只建一次、永不 shutdown。
 */
object UfiStreamHttpClient {
    @Volatile
    private var client: okhttp3.OkHttpClient? = null

    fun get(appContext: Context): okhttp3.OkHttpClient {
        return client ?: synchronized(this) {
            client ?: OkHttpClientProvider.shared.newBuilder()
                // 走设备 WiFi 直连，首帧/大文件读取可能较慢，放宽超时避免被误判连接失败（2001）
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .addInterceptor(RetrofitClient.authInterceptor { AppPreferences(appContext) })
                .build()
                .also { client = it }
        }
    }
}

/**
 * 建一个带设备签名的 [ExoPlayer]，并在离开组合时释放。
 *
 * ⚠ **key 是 [key] 而不是 URL**：媒体中心的播放页要"一个播放器 + 一条 playlist"，
 * 换片只 `seekToDefaultPosition(index)`，不能重建播放器（重建 = 黑一下 + 重新连接）。
 * 文件预览浮层传 `filePath` 当 key（单文件、换文件就该换播放器）；
 * 播放页传常量（如 `"video-center"`），整页生命周期内只有一个播放器。
 *
 * `DisposableEffect` 的 key 必须是 `player` 本身：写 `Unit` 会在
 * `remember(key)` 换出新播放器时漏掉旧的那一个（老实例永不释放）。
 */
@Composable
@OptIn(UnstableApi::class)
fun rememberUfiExoPlayer(
    key: Any,
    onError: (Throwable) -> Unit = {}
): ExoPlayer {
    val appContext = LocalContext.current.applicationContext
    val player = remember(key) {
        val httpClient = UfiStreamHttpClient.get(appContext)
        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(
                DefaultLoadErrorHandlingPolicy(MINIMUM_LOADABLE_RETRY_COUNT)
            )
        // applicationContext：播放器活得比某个 Activity 长（配置变更），拿 Activity 会泄漏
        ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .build()
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    return player
}

/**
 * 画面 + 控制条（media3 的 [PlayerView] + 项目自定义 controller 布局）。
 *
 * 控制条是 `res/layout/ufi_preview_player_controller.xml`（489 行，头部有 95 行几何注释，
 * 改之前先读）。这里只做开关：
 *  · [showNextPrevious] —— playlist 场景打开"上/下一集"（浮层单文件场景关掉）
 *  · 字幕按钮常开：无字幕轨时 media3 自己会隐藏它
 *  · 齿轮（倍速 / 音轨 / 字幕轨选择）是 media3 自带弹窗，不用自己写
 *
 * @param onClose 非 null 时显示控制条右上角的关闭按钮（浮层用）；null = 隐藏（有导航栏的独立页用）
 */
@Composable
@OptIn(UnstableApi::class)
fun UfiVideoSurface(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    showNextPrevious: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = LayoutInflater.from(ctx)
                .inflate(R.layout.player_view_media, null) as PlayerView
            view.useController = true
            view.controllerShowTimeoutMs = 3000
            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            view.setShowSubtitleButton(true)
            view.setShowShuffleButton(false)
            view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            view.applyUfiSubtitleStyle()
            view
        },
        update = { view ->
            view.player = player
            view.setShowNextButton(showNextPrevious)
            view.setShowPreviousButton(showNextPrevious)
            if (onToggleFullscreen != null) {
                view.setFullscreenButtonClickListener { onToggleFullscreen() }
            }
            // 关闭按钮由 Kotlin 侧绑定：controller XML 里只放了一个空壳 ImageButton
            view.findViewById<ImageButton?>(R.id.ufi_preview_close)?.let { button ->
                if (onClose == null) {
                    button.visibility = android.view.View.GONE
                } else {
                    button.visibility = android.view.View.VISIBLE
                    button.setOnClickListener { onClose() }
                }
            }
        }
    )
}

/**
 * 统一字幕外观（2026-09-20）。
 *
 * 在此之前字幕层完全没配过，用的是 media3 的库默认值 —— 表现是字偏小、外挂 SRT 与
 * 内嵌 ASS 两套观感不一致。两个播放入口（媒体中心播放页、文件管理器预览浮层）都调这一处，
 * 免得两边各调一套参数然后慢慢分叉。
 *
 * ## 为什么关掉 applyEmbeddedStyles
 * 开着的话，字幕文件自带的样式优先 —— 而 media3 对 ASS 样式的支持是**部分**的：
 * 字号/位置/颜色时灵时不灵，常见结果是一行大一行小、或者跑到画面中间。
 * 关掉之后所有字幕走同一套外观，可读性稳定；代价是丢掉 ASS 里本来正确的那部分
 * （比如双语字幕上下分色）。
 * 在"看得清"和"尽量还原样式"之间，这里选前者 —— 真要完整 ASS 特效，media3 也给不了，
 * 那要换到带 libass 的内核。
 *
 * ## 为什么白字黑边而不是取主题色
 * 字幕压在视频画面上，而画面不随 App 主题变。半透明底框会挡画面，纯白无描边在亮场景
 * （雪地、白墙）里会糊掉 —— 描边是唯一在任何画面上都成立的方案。
 */
@OptIn(UnstableApi::class)
fun PlayerView.applyUfiSubtitleStyle() {
    val view = subtitleView ?: return
    view.setApplyEmbeddedStyles(false)
    view.setStyle(
        CaptionStyleCompat(
            /* foregroundColor = */ android.graphics.Color.WHITE,
            /* backgroundColor = */ android.graphics.Color.TRANSPARENT,
            /* windowColor = */ android.graphics.Color.TRANSPARENT,
            /* edgeType = */ CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            /* edgeColor = */ android.graphics.Color.BLACK,
            /* typeface = */ null
        )
    )
    // 默认 0.0533（≈ 屏高的 5.3%）在手机上偏小，尤其横屏时。放大到 1.25 倍。
    view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SUBTITLE_TEXT_SCALE)
    // 抬离底边：控制条弹出时会盖住最底部那行字
    view.setBottomPaddingFraction(SUBTITLE_BOTTOM_PADDING_FRACTION)
}

/** 字幕字号相对 media3 默认值的放大倍数。 */
private const val SUBTITLE_TEXT_SCALE = 1.25f

/** 字幕底部留白占视图高度的比例（默认 0.08 会被控制条盖住）。 */
private const val SUBTITLE_BOTTOM_PADDING_FRACTION = 0.10f

/** 从 Context 链里找宿主 Activity（`LocalContext as? Activity` 在 Compose 里经常失败）。 */
private fun findActivity(context: Context): Activity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 沉浸式全屏：隐藏系统栏 + 强制横屏 + 保持亮屏，退出时**精确还原**原状态
 * （原方向、原系统栏可见性、原 KEEP_SCREEN_ON —— 不还原会把整个 App 卡在横屏）。
 */
@Composable
fun UfiFullscreenSystemUiEffect(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val activity = findActivity(context) ?: return@DisposableEffect onDispose { }
        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val originalOrientation = activity.requestedOrientation
        val originalKeepScreenOn =
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            activity.requestedOrientation = originalOrientation
            controller.show(WindowInsetsCompat.Type.systemBars())
            if (!originalKeepScreenOn) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

/** 把一串播放地址装进播放器（playlist），从 [startIndex] 开始。 */
fun ExoPlayer.setUfiPlaylist(urls: List<String>, startIndex: Int) {
    setUfiSources(urls.map { UfiVideoSource(it) }, startIndex)
}

/**
 * 播放器要挂的一条外挂字幕轨。
 *
 * [mimeType] 必须准确（`application/x-subrip` / `text/x-ssa` / `text/vtt` …）——
 * media3 靠它选解析器，给错了就是"有这条轨但一个字都不出"。值由 core 的
 * `/api/media/subtitles` 给出，客户端不自己按后缀猜。
 *
 * [url] 应指向 `/api/media/subtitle?path=`（core 已转成 UTF-8），
 * **不要**直接指向 `/api/files/stream` 的原始文件：GB18030/Big5 字幕会整屏乱码。
 */
data class UfiSubtitleTrack(
    val url: String,
    val mimeType: String,
    val label: String = "",
    val language: String? = null
)

/** 一条视频 + 它的外挂字幕。 */
data class UfiVideoSource(
    val url: String,
    val subtitles: List<UfiSubtitleTrack> = emptyList()
)

/**
 * core 给的字幕条目 → 播放器要的字幕轨。
 *
 * [urlOf] 由调用方传（通常是 `MediaModule::subtitleUrl`）：播放器核心只认 data 层的模型，
 * 不去依赖 viewmodel 层，否则这一份"全站唯一的播放器"就被拽进业务依赖里了。
 *
 * `language` 传 null 而不是 [MediaSubtitleEntry.label]：label 是从文件名猜的
 * （`chs`、`简体`、`forced` 都可能），塞进 language 会让 media3 按 BCP-47 去解析并可能丢弃；
 * 显示用 label，语言偏好这件事交给用户在菜单里点。
 */
fun MediaSubtitleEntry.toTrack(urlOf: (String) -> String): UfiSubtitleTrack = UfiSubtitleTrack(
    url = urlOf(path),
    mimeType = mime,
    label = label.ifBlank { name },
    language = null
)

/**
 * 直接拉某个视频的可播放外挂字幕轨（不经 viewmodel）。
 *
 * 给**没有 viewModel 可用**的调用方用 —— 文件管理器的预览浮层是个纯 Composable，
 * 参数链上没有 MainViewModel，为了字幕把 viewModel 一路传进去不划算。
 * 有 viewModel 的地方（媒体中心播放页）仍然走 `MediaModule.subtitlesOf`。
 *
 * 失败回空列表：字幕是增强项，拉不到就当没有，不该让预览浮层报错。
 */
suspend fun loadUfiSubtitleTracks(appContext: Context, videoPath: String): List<UfiSubtitleTrack> =
    try {
        val prefs = AppPreferences(appContext)
        val base = "http://${prefs.effectiveHost}:${prefs.serverPort}"
        RetrofitClient.getApiService(prefs)
            .getMediaSubtitles(videoPath, null)
            .items
            .filter { it.supported }
            .map { entry ->
                entry.toTrack { path ->
                    "$base/api/media/subtitle?path=" +
                        java.net.URLEncoder.encode(path, "UTF-8")
                }
            }
    } catch (e: Exception) {
        emptyList()
    }

/**
 * 把一串「视频 + 外挂字幕」装进播放器。
 *
 * 为什么不和 [setUfiPlaylist] 同名重载：`List<String>` 与 `List<UfiVideoSource>`
 * 在 JVM 上擦除后签名相同，编译不过。
 *
 * 第一条字幕带 `SELECTION_FLAG_DEFAULT` —— core 已把"和视频同名"的排在首位，
 * 所以这条就是最贴切的那个，打开即生效；其余走 media3 齿轮里的字幕轨菜单切换。
 * 不给 default 的话"自动匹配"就成了摆设：用户每次还得自己去菜单点一下。
 */
@OptIn(UnstableApi::class)
fun ExoPlayer.setUfiSources(sources: List<UfiVideoSource>, startIndex: Int) {
    if (sources.isEmpty()) return
    setMediaItems(
        sources.map { source ->
            MediaItem.Builder()
                .setUri(source.url)
                .setSubtitleConfigurations(
                    source.subtitles.mapIndexed { index, track ->
                        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(track.url))
                            .setMimeType(track.mimeType)
                            .setLabel(track.label.ifBlank { null })
                            .setLanguage(track.language)
                            .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
                            .build()
                    }
                )
                .build()
        },
        startIndex.coerceIn(0, sources.lastIndex),
        /* startPositionMs = */ 0L
    )
    prepare()
}
