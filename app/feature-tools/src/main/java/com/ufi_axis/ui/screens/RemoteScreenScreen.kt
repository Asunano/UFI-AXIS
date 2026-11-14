package com.ufi_axis.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.ufi_axis.data.screen.ScreenProtocol
import com.ufi_axis.data.screen.ScreenShot
import com.ufi_axis.data.screen.ScreenStreamClient
import com.ufi_axis.data.screen.ScreenVideoDecoder
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsGroup
import com.ufi_axis.ui.components.common.UfiInputDialog
import com.ufi_axis.ui.components.common.UfiSlider
import com.ufi_axis.ui.components.common.UfiDialogBody
import androidx.compose.material3.IconButton
import com.ufi_axis.ui.components.common.UfiDialogNote
import com.ufi_axis.ui.components.common.UfiPopupOption
import com.ufi_axis.ui.components.common.UfiPopupAnchor
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.foundation.clickable
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.components.common.UfiDialogActions
import com.ufi_axis.ui.components.common.UfiScrollableDialog
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 远程屏幕（2026-11-01 起；2026-11-02 重构渲染与布局）。
 *
 * ## 两条链路，互不遮蔽
 * ```
 * 画面：WS 视频下行 → MediaCodec → 画面                 ← 硬解（**默认 / 推荐**，实时）
 *       或 GET /api/screen/screenshot（1–2 fps，PNG）   ← 快照（兼容模式，退路）
 * 控制：WS 上行 → scrcpy 控制 socket（触摸/按键/文本）   ← 与画面无关
 * ```
 *
 * 画面模式的取舍（2026-11-04 定稿）：**硬解是正常路径，快照只作兼容**。
 * 快照存在的理由只有三条 —— 没有可用 Surface、厂商解码器不兼容、
 * 或对端拿不到控制票据（硬解需要它）。这三种情况下代码会自己降级并说明原因，
 * 但不会把"降级"写进用户偏好。
 *
 * ## 画面有两种渲染方式（目标是达到 web 端"GPU 合成、CPU 不碰像素"的效果）
 * - **Surface 直绘（默认）**：`MediaCodec → SurfaceView`。解码帧直接交给合成器，
 *   CPU 既不做 YUV→RGB、也不产生 Bitmap。web 端是 WebGL 着色器转换，这里是交给显示硬件，
 *   两者都不占 CPU。这条路上还能放心用厂商硬解（不必担心它的私有/tiled 布局）。
 * - **像素路径（兜底）**：`MediaCodec → ImageReader → Kotlin YUV→ARGB → Bitmap`。
 *   Surface 万一在这台机器上崩（历史上遇到过），下次进页面会自动回退到这里。
 *
 * ## 布局
 * 画面在上、状态行与工具条在下，**不再用浮在画面上的胶囊** —— 那套会挡画面，按钮一多就没法看。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreenScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
) {
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }
    val scope = rememberCoroutineScope()

    /** 投屏开关。null = 还没读到（**不能**当成 false，否则网络瞬断会被画成"未开启"） */
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    var enabling by remember { mutableStateOf(false) }
    /** 画面侧的**错误**（红字；会改变遮罩上的主按钮文案为"重试"） */
    var error by remember { mutableStateOf("") }

    /**
     * 画面侧的**告知**（不是错误）：例如"上次 Surface 未干净退出，已切回像素模式"。
     *
     * ★ 2026-11-04：这条以前塞在 [error] 里，于是遮罩主按钮变成了"重试"、文字也变成错误色 ——
     * 但其实什么都没失败，只是降了一档渲染方式。告知与错误必须分开走。
     */
    var notice by remember { mutableStateOf("") }
    var pictureStatus by remember { mutableStateOf("未连接") }
    /** 控制通道状态，独立于画面 */
    var controlStatus by remember { mutableStateOf("") }
    var stats by remember { mutableStateOf("") }
    var streamInfo by remember { mutableStateOf("") }
    var lastState by remember { mutableStateOf("") }
    /** 流解析失败后是否已经自动重连过一次（限一次，避免把真故障变成死循环） */
    var autoRetried by remember { mutableStateOf(false) }

    /**
     * 画面模式：`true` = 快照（兼容），`false` = **硬解（默认，实时）**。
     *
     * 2026-11-04：默认从"快照"改成"硬解"。快照是给不兼容场景留的退路，
     * 不该是所有人第一次进来的体验（1–2 fps 的观感与"远程控制"这个功能名不匹配）。
     * 用户手动切过就记住（[AppPreferences.screenStillMode]）。
     */
    var stillMode by remember { mutableStateOf(prefs.screenStillMode) }
    /** 会话启动参数（与 web 面板同一组） */
    var maxSize by remember { mutableIntStateOf(848) }
    var maxFps by remember { mutableIntStateOf(20) }
    var bitRateKbps by remember { mutableIntStateOf(2000) }
    /** 服务端**实际生效**的参数（0 = 还没连过）。与上面的"期望值"分开，设置面板里同时显示 */
    var appliedParams by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    /** 0 = 未连接；每次 +1 就重开一次会话（切模式/改参数） */
    var session by remember { mutableStateOf(0) }
    /** 「画面设置」弹窗（「更多」本身已经是悬浮菜单，不再是弹窗）。 */
    var showSettings by remember { mutableStateOf(false) }
    /** 退出页面时询问是否断开连接 */
    var showExitDialog by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    var showTextInput by remember { mutableStateOf(false) }
    /** 截图等一次性操作的结果提示（Toast，不占画面状态行） */
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    /** 当前打开的二选一面板（画面模式 / 渲染方式），null = 关闭 */
    var shotSaving by remember { mutableStateOf(false) }

    /**
     * 渲染方式：true = Surface 直绘（默认），false = 像素路径。
     * 进页时若发现上一次"Surface 未干净退出"的标记还在，自动改回像素路径。
     */
    var surfaceMode by remember { mutableStateOf(prefs.screenSurfaceMode) }

    // 一次性升级旧安装：渲染默认统一为 Surface；用户之后手动切回像素路径仍会记住。
    LaunchedEffect(Unit) {
        if (!prefs.screenSurfaceDefaultMigrated) {
            prefs.screenSurfaceMode = true
            prefs.screenSurfaceDefaultMigrated = true
            // 旧版本可能留下 pending 标记；默认升级不应被上一次的崩溃自愈覆盖。
            prefs.screenSurfacePending = false
            surfaceMode = true
        }
    }

    /**
     * 像素路径/快照模式的画面宿主（ImageView）。
     *
     * 为什么用 ImageView 而不是 Compose 的 `Image`：工具条要**浮在画面之上**，
     * 而 SurfaceView 会把自己那块区域从窗口里"挖掉"（punch hole），Compose 画在它上面的
     * 内容会被一起擦掉。所以画面和工具条都放进同一个 AndroidView 层级：
     * 先加视频层，再加一个 ComposeView 承载工具条 —— 后加的画在上面，SurfaceView 也盖不住它。
     */
    val imageViewHolder = remember { mutableStateOf<android.widget.ImageView?>(null) }

    /**
     * 浮层状态。
     *
     * ★ 工具条住在 [androidx.compose.ui.platform.ComposeView] 里 —— 那是一个**独立 composition**，
     * `setContent` 只跑一次，闭包捕获的普通值（`val connected = session != 0` 这种）会永远停在
     * 第一次的取值上：按钮文案不更新、点了也调不到最新逻辑。所以这里用 state 持有者，
     * 由主 composition 每帧同步进去（见下面的 `SideEffect`），浮层只读 `.value`。
     */
    val overlayState = remember { ScreenOverlayState() }
    /** 当前 Surface（由 SurfaceView 的 holder 回调维护） */
    val surfaceState = remember { mutableStateOf<Surface?>(null) }
    /** SurfaceView 本体：断开/切模式时要主动清屏（Surface 会保留最后一帧） */
    val surfaceViewHolder = remember { mutableStateOf<SurfaceView?>(null) }

    /** 把 Surface 刷成黑色。SurfaceView 不会自己清屏，不清就会一直停着最后一帧。 */
    fun clearSurface() {
        val sv = surfaceViewHolder.value ?: return
        sv.post {
            runCatching {
                val canvas = sv.holder.lockCanvas() ?: return@runCatching
                canvas.drawColor(android.graphics.Color.BLACK)
                sv.holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    var videoW by remember { mutableStateOf(0) }
    var videoH by remember { mutableStateOf(0) }

    /** 会话已发起（= 有连接在跑）。**不等于**画面已经出来。 */
    val connected = session != 0

    /**
     * 画面**真的**出来了（= 第一帧已上屏）。
     *
     * ★★ 2026-11-04（用户："点连接后没有加载动画，看着像已经连上了"）：★★
     * 这里以前只有一个 `connected = session != 0`，而 `session` 是在**点击那一刻**就 +1 的。
     * 于是遮罩的三态判据 `connected -> CONNECTED` 从第一帧起就成立，
     * `MASK_CONNECTING`（带进度环的那一屏）**永远走不到** —— 死代码。
     * 用户点完立刻看到控制条，但画面要等 1–2 秒（取票 + 建 WS + 等关键帧）才出来，
     * 中间那段"看起来能点、其实点什么都没用"的空白就是它。
     *
     * 现在把"会话起来"与"画面就绪"拆成两个判据：首帧到达之前一律停在 CONNECTING。
     * 硬解的首帧信号来自解码器的 `onState("首帧 …")`（Surface 直绘路径也补了这条，
     * 见 ScreenVideoDecoder）；快照模式则是第一张 PNG 解码成功。
     */
    var streamReady by remember { mutableStateOf(false) }
    val runtime = remember { ScreenRuntime() }

    /** 面包屑落盘：native 崩溃时只有"崩溃前最后一行"能留下 */
    fun trace(msg: String) {
        runCatching {
            val f = java.io.File(context.getExternalFilesDir(null), "screen-debug.log")
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("$ts $msg\n")
        }
    }

    /** 把一帧丢给画面宿主（任何线程都可调） */
    fun showFrame(bmp: Bitmap?) {
        val iv = imageViewHolder.value ?: return
        iv.post { iv.setImageBitmap(bmp) }
    }

    var lastMoveAt by remember { mutableStateOf(0L) }

    /**
     * 触摸：视图坐标 × 缩放 = 视频坐标。
     *
     * MOVE 按 ~12ms 合并：手机触控上报常见 120Hz，逐事件全发会让上行帧数翻倍；
     * DOWN / UP / CANCEL **一律不合并**，否则会丢点击、残留手指。
     */
    fun touch(action: Int, x: Float, y: Float, viewWidthPx: Int) {
        if (videoW <= 0 || videoH <= 0 || viewWidthPx <= 0) return
        if (action == ScreenProtocol.ACTION_MOVE) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastMoveAt < 12L) return
            lastMoveAt = now
        }
        val scale = videoW.toFloat() / viewWidthPx
        if (!runtime.send(
                ScreenProtocol.touch(
                    action,
                    (x * scale).toInt().coerceIn(0, videoW - 1),
                    (y * scale).toInt().coerceIn(0, videoH - 1),
                    videoW,
                    videoH,
                )
            )
        ) {
            if (controlStatus.isBlank()) controlStatus = "控制通道未连接，点击不会生效"
        }
    }

    fun connect() {
        if (enabled == false) {
            error = "投屏未开启：「更多 → 开启投屏」里打开"
            return
        }
        error = ""
        notice = ""
        streamReady = false
        showFrame(null)
        videoW = 0
        videoH = 0
        stats = ""
        streamInfo = ""
        lastState = ""
        controlStatus = ""
        autoRetried = false
        session += 1
    }

    fun disconnect() {
        session = 0
        streamReady = false
        // 用户主动断开 / 连接中点取消。
        runtime.close()
        clearSurface()
        showFrame(null)
        stats = ""
        streamInfo = ""
        controlStatus = ""
        pictureStatus = "未连接"
        autoRetried = false
    }

    /** 退出页面：连接中先询问是否断开；未连接直接返回。 */
    fun requestExit() {
        if (session != 0) {
            showExitDialog = true
        } else {
            navController.popBackStack()
        }
    }

    BackHandler(enabled = !showExitDialog) { requestExit() }

    fun sendKey(keyCode: Int) {
        runtime.send(ScreenProtocol.key(0, keyCode))
        runtime.send(ScreenProtocol.key(1, keyCode))
    }

    fun saveScreenshot() {
        if (shotSaving) return
        shotSaving = true
        scope.launch {
            when (val shot = viewModel.tools.screenShot()) {
                is ScreenShot.Ok -> {
                    val ok = withContext(Dispatchers.IO) { savePngToGallery(context, shot.png) }
                    // ★ 2026-11-04（用户："截图需要 toast 提示状态"）：
                    // 以前写进 `error`，那是画面侧的状态行 —— 在连接态下它**根本不显示**
                    // （遮罩已经撤掉），用户点完截图得不到任何反馈。改为 Toast。
                    toastMessage = if (ok) {
                        ToastMessage(
                            text = "截图已保存",
                            subtitle = "相册 · Pictures/UFI-AXIS",
                            type = ToastType.SUCCESS,
                        )
                    } else {
                        ToastMessage(
                            text = "截图保存失败",
                            subtitle = "写入相册被拒绝，请检查存储权限",
                            type = ToastType.ERROR,
                        )
                    }
                }
                ScreenShot.Disabled -> {
                    enabled = false
                    toastMessage = ToastMessage("投屏开关已被关闭", type = ToastType.WARNING)
                }
                ScreenShot.Busy -> toastMessage = ToastMessage("上一张还没完成，稍后再试")
                is ScreenShot.Failed -> toastMessage = ToastMessage(
                    text = "截图失败",
                    subtitle = shot.reason,
                    type = ToastType.ERROR,
                )
            }
            shotSaving = false
        }
    }

    /**
     * 开启远程屏幕并直接连接。
     *
     * 与「更多」面板里那颗开关走同一条路径（`setScreenEnabled(true)`）。区别只在于
     * 这里开启成功后**顺手连上** ——
     * 遮罩上的主按钮语义就是"开启并连接"，不该还要用户再点一次。
     */
    fun enableScreen() {
        if (enabling) return
        enabling = true
        error = ""
        scope.launch {
            val reason = viewModel.tools.setScreenEnabled(true)
            if (reason == null) {
                enabled = true
                connect()
            } else {
                error = reason
            }
            enabling = false
        }
    }

    val toggleStill: (Boolean) -> Unit = { wantStill ->
        if (wantStill != stillMode) {
            stillMode = wantStill
            prefs.screenStillMode = wantStill
            stats = ""
            streamInfo = ""
            lastState = ""
            showFrame(null)
            autoRetried = false
            // 换了画面模式，旧 Surface 一定不再属于这次会话 —— 清掉引用，
            // 否则新会话会拿到一个"看着有值、其实已失效"的 Surface 去配解码器。
            surfaceState.value = null
            streamReady = false
            if (connected) session += 1
        }
    }
    val toggleSurface: (Boolean) -> Unit = { wantSurface ->
        if (wantSurface != surfaceMode) {
            surfaceMode = wantSurface
            prefs.screenSurfaceMode = surfaceMode
            prefs.screenSurfacePending = false
            streamReady = false
            if (connected) session += 1
        }
    }
    val popupOptions: List<UfiPopupOption> = buildList {
        if (enabled == false) {
            add(
                UfiPopupOption(
                    id = "enable",
                    label = if (enabling) "正在开启…" else "开启远程屏幕",
                    icon = Icons.Default.PowerSettingsNew,
                    onClick = { if (!enabling) enableScreen() },
                )
            )
            add(UfiPopupOption.divider())
        }
        add(
            UfiPopupOption(
                id = "mode_hard",
                label = "画面：硬解 · 实时",
                isSelected = !stillMode,
                onClick = { toggleStill(false) },
            )
        )
        add(
            UfiPopupOption(
                id = "mode_still",
                label = "画面：快照 · 兼容",
                isSelected = stillMode,
                onClick = { toggleStill(true) },
            )
        )
        add(UfiPopupOption.divider())
        add(
            UfiPopupOption(
                id = "render_surface",
                label = "渲染：Surface 直绘",
                isSelected = surfaceMode,
                onClick = { toggleSurface(true) },
            )
        )
        add(
            UfiPopupOption(
                id = "render_pixel",
                label = "渲染：像素路径",
                isSelected = !surfaceMode,
                onClick = { toggleSurface(false) },
            )
        )
        add(UfiPopupOption.divider())
        add(
            UfiPopupOption(
                id = "settings",
                label = "画面设置…",
                icon = Icons.Default.Tune,
                onClick = { showSettings = true },
            )
        )
        add(
            UfiPopupOption(
                id = "shot",
                label = if (shotSaving) "截图（保存中…）" else "截图",
                icon = Icons.Default.PhotoCamera,
                onClick = { if (!shotSaving) saveScreenshot() },
            )
        )
        add(
            UfiPopupOption(
                id = "text",
                label = "发送文本",
                icon = Icons.Default.Keyboard,
                onClick = { showTextInput = true },
            )
        )
        // 电源键：岛内那排 2026-11-05 起只留"返回/主页/最近/更多"四颗，
        // 电源挪到这里 —— 低频且有"把设备屏幕关掉"的实际后果，放在菜单里更难误触。
        add(UfiPopupOption.divider())
        add(
            UfiPopupOption(
                id = "diag",
                label = if (showDiag) "隐藏诊断信息" else "诊断信息",
                icon = Icons.Default.BugReport,
                onClick = { showDiag = !showDiag },
            )
        )
        add(
            UfiPopupOption(
                id = "power",
                label = "电源键",
                icon = Icons.Default.PowerSettingsNew,
                onClick = { sendKey(26) },
            )
        )
    }

    // ★★ 2026-11-04（实测：解码器已报"首帧"，遮罩却停在"正在建立会话"）★★
    //
    // 下面这些 state 必须在**组合期**读出来（读 = 订阅），`SideEffect` 只负责把算好的值
    // 写进浮层容器。以前整段都写在 `SideEffect { }` 的 lambda 里 —— 那是组合结束后才执行的
    // 回调，读到的值变化**不会**让本页重组，浮层就永远停在旧文字上。
    // 这次正好被"首帧→换成控制条"这一步撞出来：`streamReady` 变了，页面却没重组。
    val overlayPhase = screenPhaseOf(sessionStarted = connected, streamReady = streamReady)
    val overlayHasError = error.isNotBlank()
    // 参数脚注：连上之后用会话真实分辨率，之前用"即将采用的"设置值。
    val overlayParams = if (videoW > 0 && videoH > 0) {
        "$videoW × $videoH · $maxFps fps · ${"%.1f".format(bitRateKbps / 1000f)} Mbps"
    } else {
        "长边 $maxSize · $maxFps fps · ${"%.1f".format(bitRateKbps / 1000f)} Mbps"
    }
    val overlayTitle: String
    val overlaySubtitle: String
    val overlayPrimaryLabel: String
    when (overlayPhase) {
        ScreenPhase.MASK_CONNECTING -> {
            overlayTitle = if (overlayHasError) "连接未完成" else "正在连接设备"
            overlaySubtitle = if (overlayHasError) {
                error
            } else {
                "正在取会话票据并等首帧，通常 1–2 秒。\n久无画面会自动切到快照（兼容）模式。"
            }
            overlayPrimaryLabel = ""
        }
        ScreenPhase.MASK_OPEN -> {
            overlayTitle = "远程屏幕"
            overlaySubtitle = error.ifBlank {
                notice.ifBlank {
                    if (enabled == false) {
                        "设备画面会显示在这里。\n关闭状态下不会启动投屏会话。"
                    } else {
                        "设备画面会显示在这里。\n点下面的按钮开始投屏。"
                    }
                }
            }
            overlayPrimaryLabel = when {
                enabled == false -> "开启并连接"
                overlayHasError -> "重试"
                else -> "连接"
            }
        }
        ScreenPhase.CONNECTED -> {
            overlayTitle = ""
            overlaySubtitle = ""
            overlayPrimaryLabel = ""
        }
    }
    val overlayDiag = listOf(controlStatus, lastState, streamInfo, stats).filter { it.isNotBlank() }

    // 把上面算好的值同步给浮层（它住在独立 composition 里，只能靠 state 更新）
    androidx.compose.runtime.SideEffect {
        overlayState.phase.value = overlayPhase
        overlayState.isError.value = overlayHasError
        overlayState.params.value = overlayParams
        overlayState.title.value = overlayTitle
        overlayState.subtitle.value = overlaySubtitle
        overlayState.primaryLabel.value = overlayPrimaryLabel
        overlayState.diagLines.value = overlayDiag
        overlayState.showDiag.value = showDiag
        overlayState.onToggleDiag = { showDiag = !showDiag }
        overlayState.onPrimary = {
            if (enabled == false) enableScreen() else connect()
        }
        overlayState.onCancel = { disconnect() }
        overlayState.onDisconnect = { disconnect() }
        // 未连接遮罩上的「画面设置」按钮：参数可以连之前先调好。
        overlayState.onOpenSettings = { showSettings = true }
        overlayState.onBack = { sendKey(4) }
        overlayState.onHome = { sendKey(3) }
        overlayState.onRecents = { sendKey(187) }
        overlayState.onPower = { sendKey(26) }
        // 「更多」不再弹窗：改成锚在按钮上的悬浮菜单（见 popupOptions 的注释）。
        overlayState.options.value = popupOptions
    }

    // 进页：读开关；顺带做 Surface 崩溃自愈
    LaunchedEffect(Unit) {
        if (prefs.screenSurfacePending) {
            prefs.screenSurfacePending = false
            prefs.screenSurfaceMode = false
            surfaceMode = false
            notice = "上次 Surface 渲染未正常退出，已切回像素模式（可在「更多」里再试）"
        }
        enabled = viewModel.tools.screenEnabled()
    }

    // 离开页面时清掉"未干净退出"标记：能走到这里说明这次没崩
    DisposableEffect(Unit) {
        onDispose {
            prefs.screenSurfacePending = false
            runtime.close()
        }
    }

    /**
     * 切到后台就停流，回到前台重开。
     *
     * 页面原先只靠 `DisposableEffect` 收尾 —— 那是**离开组合**才触发，而切后台属于
     * STOPPED 但仍在组合里：硬解模式下解码器继续吃帧、快照模式下每 650ms 仍在拉一张
     * `screencap`（300–800ms 的 adb 调用 + 解码），用户在别的应用里也不知道。
     *
     * 用 `session = 0` 让上面的 `LaunchedEffect` 走正常收尾，回前台再自增重开。
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> session = 0
                Lifecycle.Event.ON_START -> if (session == 0 && enabled == true) session = 1
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * 一个会话 = 一次 LaunchedEffect。
     *
     * 画面与控制**同时起、但分别汇报**：快照轮询不依赖票据/WS；WS 只负责控制
     * （硬解模式下顺带出画面）。所以"WS 起不来"只影响控制状态，画面照常刷新。
     */
    // 会话代际号：`session` 会被重置成 0/1，值会复用，靠它判断"我是不是最新"不可靠
    // （旧 effect 的 finally 可能在 session 又变回 1 之后才跑）。每次真正启动都自增一次。
    val sessionGeneration = remember { java.util.concurrent.atomic.AtomicInteger(0) }

    LaunchedEffect(session) {
        if (session == 0) return@LaunchedEffect
        val myGeneration = sessionGeneration.incrementAndGet()
        // ★★ 2026-11-04（"切模式必须断开重连才生效"）：**在这里主动关掉上一轮**。★★
        //
        // 根因是 `LaunchedEffect(session)` 的重启时序：key 变化时 Compose 先 `cancel()` 旧协程、
        // 再启动新协程，但 `cancel()` 只做标记 —— 旧协程那个 `finally { runtime.close() }`
        // 要等它在 AndroidUiDispatcher 上被真正恢复（通常是**下一帧**）才执行。
        // 于是顺序变成：新会话已经 `runtime.connect()` → 旧会话的 finally 把**新连接**关掉。
        // 用户看到的就是"切了模式没反应 / 画面卡住不动，断开重连才好"。
        //
        // 两道保险：
        // 1. 新会话开头自己 close 一次（清理动作与"谁开谁关"解耦，不再依赖旧协程的时序）；
        // 2. 旧会话的 finally 只在"自己仍是最新一代"时才 close（[myGeneration] 比对）。
        runtime.close()
        pictureStatus = if (stillMode) "快照：等待第一张…" else "硬解：连接中…"

        // 每秒刷一次诊断（stats 只在 onState 时更新，不刷新就是一堆过期数字）
        launch {
            while (isActive) {
                delay(1_000)
                runtime.statsSnapshot()?.let { stats = it }
                streamInfo = runtime.streamInfo()
            }
        }

        if (stillMode) {
            launch {
                while (isActive) {
                    val startedAt = SystemClock.elapsedRealtime()
                    when (val shot = viewModel.tools.screenShot()) {
                        is ScreenShot.Ok -> {
                            val bmp = withContext(Dispatchers.Default) {
                                BitmapFactory.decodeByteArray(shot.png, 0, shot.png.size)
                            }
                            if (bmp == null) {
                                error = "截图解码失败（${shot.png.size}B）"
                            } else {
                                showFrame(bmp)
                                streamReady = true
                                pictureStatus = "快照 ${bmp.width}×${bmp.height}"
                                if (videoW == 0) {
                                    videoW = bmp.width
                                    videoH = bmp.height
                                }
                                if (error.startsWith("截图")) error = ""
                            }
                        }
                        ScreenShot.Busy -> Unit
                        ScreenShot.Disabled -> {
                            enabled = false
                            error = "投屏开关已被关闭"
                            break
                        }
                        is ScreenShot.Failed -> error = shot.reason
                    }
                    val spent = SystemClock.elapsedRealtime() - startedAt
                    delay((SCREENSHOT_INTERVAL_MS - spent).coerceAtLeast(150L))
                }
            }
        } else {
            // 硬解保底：6 秒还没出过一帧就自动切回快照
            launch {
                delay(6_000)
                if (session != 0 && runtime.renderedFrames() == 0L && !stillMode) {
                    stillMode = true
                    // ⚠ 不写 prefs：这是**本次**的降级，不是用户的选择。
                    // 否则一次临时故障会永久把用户按在快照模式上。
                    showFrame(null)
                    error = "硬解 6 秒没出图，已临时切到快照（兼容模式）"
                    session += 1
                }
            }
        }

        try {
            val params = ScreenStreamClient.VideoParams(
                maxSize = maxSize,
                maxFps = maxFps,
                bitRate = bitRateKbps * 1000,
            )
            // Surface 是异步就绪的（surfaceCreated），硬解走 Surface 时先等它一下
            val useSurface = !stillMode && surfaceMode
            if (useSurface) {
                // 等一块**可用**的 Surface，而不是"非 null 的 Surface"：
                // 切换渲染方式/画面模式时，旧 SurfaceView 还没走完 surfaceDestroyed，
                // `surfaceState` 里可能短暂留着上一块已经失效的 Surface。
                var waited = 0
                while (surfaceState.value?.isValid != true && waited < 2_000) {
                    delay(50)
                    waited += 50
                }
            }
            val surface = surfaceState.value?.takeIf { useSurface && it.isValid }
            if (useSurface && surface == null) {
                controlStatus = "Surface 未就绪，本次退回像素路径"
            }

            val ticket = viewModel.tools.screenTicket(params)
            if (ticket != null) {
                // 服务端钳制后的真实值：既回写滑块，也单独记一份"生效中"
                maxSize = ticket.maxSize
                maxFps = ticket.maxFps
                bitRateKbps = ticket.bitRate / 1000
                appliedParams = Triple(ticket.maxSize, ticket.maxFps, ticket.bitRate / 1000)
            }
            if (ticket == null) {
                controlStatus = "控制通道未建立：拿不到票据（403=未开启 / 503=adb 不可用）"
                if (!stillMode) pictureStatus = "硬解需要控制通道"
            } else {
                if (surface != null) prefs.screenSurfacePending = true
                runtime.connect(
                    baseUrl = prefs.baseUrl,
                    token = prefs.token,
                    ticket = ticket.ticket,
                    params = params,
                    enableDecoder = !stillMode,
                    surface = surface,
                    onHeader = { w, h ->
                        videoW = w
                        videoH = h
                        if (stillMode) controlStatus = "控制已连接 ${w}×$h"
                        else pictureStatus = "硬解 ${w}×$h"
                    },
                    onControlError = { msg ->
                        controlStatus = msg
                        if (!stillMode && !autoRetried && msg.contains("视频流解析失败")) {
                            autoRetried = true
                            scope.launch {
                                delay(1_200)
                                showFrame(null)
                                if (session != 0) session += 1
                            }
                        }
                    },
                    onState = { msg ->
                        trace(msg)
                        lastState = msg
                        // 解码器把"首帧"作为一条状态上报（Surface 直绘与像素路径都发，
                        // 见 ScreenVideoDecoder）：这是**硬解真正出图**的唯一可靠信号，
                        // 遮罩靠它从"正在建立会话"切到控制条。
                        if (msg.startsWith("首帧")) streamReady = true
                        runtime.statsSnapshot()?.let { stats = it }
                    },
                    onFrame = { bmp ->
                        if (!stillMode) {
                            showFrame(bmp)
                            pictureStatus = "硬解 ${videoW}×${videoH}"
                        }
                    },
                )
            }
            awaitCancellation()
        } finally {
            // 只有在"自己仍是最新一代"时才收尾：被新会话顶替的情况下，
            // 资源已经由新会话开头那次 close / 新一次 connect 接管了。
            //
            // 用代际号而不是 `session`：ON_STOP 把 session 置 0、ON_START 又置回 1，
            // 值会复用 —— 旧 effect（mySession=1）在新会话（session=1）建立后才跑 finally 时，
            // 相等判断会把**新连接**关掉。而"退到后台"时没有新代际产生，
            // 代际号不变，旧 effect 一定能走到 close —— 两条路径都对。
            if (sessionGeneration.get() == myGeneration) {
                runtime.close()
                prefs.screenSurfacePending = false
            }
        }
    }

    UfiScreenScaffold(
        title = "远程屏幕",
        navController = navController,
        showBack = true,
        onBack = { requestExit() },
    ) { padding ->
        // Toast 要盖在画面之上：VideoArea 是 SurfaceView（会把自己那块从窗口里挖掉），
        // 所以它必须是同一个 Box 里**后**绘制的兄弟节点，不能塞进 VideoArea 内部。
        Box(Modifier.fillMaxSize()) {
        VideoArea(
            useSurface = !stillMode && surfaceMode,
            imageViewHolder = imageViewHolder,
            surfaceState = surfaceState,
            surfaceViewHolder = surfaceViewHolder,
            videoW = videoW,
            videoH = videoH,
            onTouch = { action, x, y, viewWidth -> touch(action, x, y, viewWidth) },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            overlay = { ControlOverlay(state = overlayState) },
        )
            // 截图/操作结果提示。放在 VideoArea 之后 = 画在它上面（同 Box 内后绘制者在上）。
            UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
        }
    }

    // ★★ 2026-11-05：**「更多」改成锚在按钮上的悬浮菜单**，弹窗只留「画面设置」★★
    //
    // 为什么不再用弹窗盛这 7 项：
    // 1. 这是一块**整屏画面**。弹窗会压暗 + 模糊背景，正好把用户唯一想看的东西盖掉；
    //    而悬浮菜单只占右上角一小块，画面不动。
    // 2. 「更多」是一组**动作**（换模式 / 截图 / 发送文本），动作菜单就该贴着触发它的那颗按钮，
    //    手指不用横跨半个屏幕回到中央。
    // 3. 本项目唯一的菜单实现就是 [UfiPopupAnchor]（工具栏「⋮ 更多」与长按菜单都用它），
    //    自带锚点定位与淡入淡出，不必再造一套。
    //
    // 画面模式 / 渲染方式在菜单里**平铺成两行**并用 ✓ 标当前值：菜单不支持二级页，
    // 而这正好省掉"点进去再选一次"——一次点击就切换。
    // 只有「画面设置」还需要弹窗：三个滑块 + 一个"应用并重连"，菜单装不下表单。
    // 退出确认：只有「取消 / 确认」；确认即断开并退出，取消留在本页。
    if (showExitDialog) {
        UfiConfirmDialog(
            title = "是否断开连接？",
            text = "退出远程屏幕将断开当前连接。",
            confirmText = "确认",
            dismissText = "取消",
            destructive = true,
            onConfirm = {
                showExitDialog = false
                disconnect()
                navController.popBackStack()
            },
            onDismiss = {
                showExitDialog = false
            },
            // 确认即退出：不要等弹窗离场 ramp 播完再 popBackStack。
            immediateConfirm = true,
        )
    }

    ScreenSettingsDialog(
        open = showSettings,
        onDismiss = { showSettings = false },
        maxSize = maxSize,
        maxFps = maxFps,
        bitRateKbps = bitRateKbps,
        applied = appliedParams,
        onSize = { maxSize = it },
        onFps = { maxFps = it },
        onBitRate = { bitRateKbps = it },
        onApply = {
            showSettings = false
            streamReady = false
            if (connected) session += 1 else connect()
        },
    )

    // ★★ 2026-11-04：改用公共弹窗 [UfiInputDialog] ★★
    // 原来是手搓的 Material `AlertDialog`：圆角/边距/按钮宽度与全站其它弹窗都不一样，
    // 深色主题下边框颜色也对不上。公共组件已经把这些收口（含 validator、按回车提交）。
    UfiInputDialog(
        visible = showTextInput,
        title = "发送文本到设备",
        hint = "需要设备侧有焦点输入框（中文会自动走剪贴板粘贴）",
        confirmText = "发送",
        dismissText = "取消",
        onConfirm = { value ->
            sendText(runtime, value)
            showTextInput = false
        },
        onDismiss = { showTextInput = false },
    )
}

/** ASCII 走按键映射；含非 ASCII 走剪贴板 + 粘贴（中文只能这样） */
private fun sendText(runtime: ScreenRuntime, text: String) {
    val t = text.trim()
    if (t.isEmpty()) return
    runtime.send(
        if (t.all { it.code in 0x20..0x7E }) ScreenProtocol.text(t)
        else ScreenProtocol.clipboard(t, paste = true)
    )
}

/**
 * 画面 + 浮层。
 *
 * ## 结构
 * ```
 * BoxWithConstraints(fillMaxSize)                 ← 整块内容区
 *   └ AndroidView(fillMaxSize)                    ← 必须铺满：浮层要能全屏（遮罩、工具条）
 *       └ FrameLayout
 *           ├ SurfaceView / ImageView（按视频比例居中，黑底留边）
 *           └ ComposeView（铺满，画工具条与遮罩）  ← 后加，画在视频之上
 * ```
 * SurfaceView 会把自己那块区域从窗口里"挖掉"，Compose 画在它上面的内容会被擦掉；
 * 所以浮层不能放在外层 Compose 树里，必须和视频同一个 ViewGroup、并且**后加**。
 */
@Composable
private fun VideoArea(
    useSurface: Boolean,
    imageViewHolder: MutableState<android.widget.ImageView?>,
    surfaceState: MutableState<Surface?>,
    surfaceViewHolder: MutableState<SurfaceView?>,
    videoW: Int,
    videoH: Int,
    onTouch: (action: Int, x: Float, y: Float, viewWidthPx: Int) -> Unit,
    overlay: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.background(Color.Black)) {
        // 视频按比例居中：宽高按容器算，剩下的留给黑边
        val containerW = constraints.maxWidth.coerceAtLeast(1)
        val containerH = constraints.maxHeight.coerceAtLeast(1)
        val videoRatio = if (videoW > 0 && videoH > 0) videoW.toFloat() / videoH else 480f / 848f
        val containerRatio = containerW.toFloat() / containerH
        val videoWpx = if (videoRatio > containerRatio) containerW else (containerH * videoRatio).toInt()
        val videoHpx = if (videoRatio > containerRatio) (containerW / videoRatio).toInt() else containerH

        key(useSurface) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    android.widget.FrameLayout(ctx).apply {
                        val videoLp = android.widget.FrameLayout.LayoutParams(
                            videoWpx,
                            videoHpx,
                            android.view.Gravity.CENTER,
                        )
                        // ① 视频层
                        if (useSurface) {
                            addView(
                                SurfaceView(ctx).apply {
                                    surfaceViewHolder.value = this
                                    holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            surfaceState.value = holder.surface
                                        }

                                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = Unit

                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            surfaceState.value = null
                                        }
                                    })
                                    setOnTouchListener { v, e ->
                                        dispatchTouch(e, v.width, onTouch)
                                        true
                                    }
                                },
                                videoLp,
                            )
                        } else {
                            addView(
                                android.widget.ImageView(ctx).apply {
                                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                    setOnTouchListener { v, e ->
                                        dispatchTouch(e, v.width, onTouch)
                                        true
                                    }
                                    imageViewHolder.value = this
                                },
                                videoLp,
                            )
                        }
                        // ② 浮层（铺满窗口，后加 → 画在上面）
                        addView(
                            androidx.compose.ui.platform.ComposeView(ctx).apply {
                                setContent { overlay() }
                            },
                            android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    }
                },
                onRelease = {
                    imageViewHolder.value = null
                    surfaceViewHolder.value = null
                },
            )
        }
    }
}

/** SurfaceView / ImageView 的触摸事件 → scrcpy 触摸消息 */
private fun dispatchTouch(
    e: MotionEvent,
    viewWidth: Int,
    onTouch: (action: Int, x: Float, y: Float, viewWidthPx: Int) -> Unit,
) {
    when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> onTouch(ScreenProtocol.ACTION_DOWN, e.x, e.y, viewWidth)
        MotionEvent.ACTION_MOVE -> onTouch(ScreenProtocol.ACTION_MOVE, e.x, e.y, viewWidth)
        MotionEvent.ACTION_UP -> onTouch(ScreenProtocol.ACTION_UP, e.x, e.y, viewWidth)
        MotionEvent.ACTION_CANCEL -> onTouch(ScreenProtocol.ACTION_CANCEL, e.x, e.y, viewWidth)
    }
}

/** 浮层当前该显示哪一态（三态一一对应设计稿） */
internal enum class ScreenPhase { MASK_OPEN, MASK_CONNECTING, CONNECTED }

/**
 * 三态的判据（纯函数，可单测）。
 *
 * ★ 2026-11-04：这里曾经写成 `connected -> CONNECTED`，而 `connected` 就是 `session != 0`
 * —— 点击那一刻就为真。于是 [ScreenPhase.MASK_CONNECTING] **永远走不到**，用户点完直接看到
 * 控制条、画面却要 1–2 秒才出来，读起来就是"点完像已经连上了，其实在等"。
 *
 * 现在的语义：
 * - 会话没起 → 未连接遮罩（可以点"连接"）；
 * - 会话起了但**首帧还没到** → 连接中（圆环 + 取消），这一屏不许出现任何"可操作"的假象；
 * - 首帧到了 → 控制条。
 *
 * 抽成纯函数不只是为了测试：它把"什么时候算连上"这件事从 Compose 的状态拼装里摘出来，
 * 避免以后又被某个 `session`/`connected` 的等价关系糊回去。
 */
internal fun screenPhaseOf(sessionStarted: Boolean, streamReady: Boolean): ScreenPhase = when {
    sessionStarted && streamReady -> ScreenPhase.CONNECTED
    sessionStarted -> ScreenPhase.MASK_CONNECTING
    else -> ScreenPhase.MASK_OPEN
}

/**
 * 浮层状态（跨 composition 传递，必须全是 state）。
 *
 * 浮层住在 [androidx.compose.ui.platform.ComposeView] 里 —— 那是独立 composition，
 * `setContent` 只跑一次，闭包捕获的普通值会永远停在第一次的取值上。所以这里只放
 * `MutableState` 与回调，由主 composition 每帧同步（见调用处的 `SideEffect`）。
 */
private class ScreenOverlayState {
    val phase = mutableStateOf(ScreenPhase.MASK_OPEN)
    val title = mutableStateOf("远程屏幕")
    val subtitle = mutableStateOf("")
    val primaryLabel = mutableStateOf("开启并连接")
    val params = mutableStateOf("")
    val isError = mutableStateOf(false)
    val diagLines = mutableStateOf<List<String>>(emptyList())
    val showDiag = mutableStateOf(false)
    var onPrimary: () -> Unit = {}
    var onCancel: () -> Unit = {}
    var onDisconnect: () -> Unit = {}
    var onOpenSettings: () -> Unit = {}
    var onToggleDiag: () -> Unit = {}
    var onBack: () -> Unit = {}
    var onHome: () -> Unit = {}
    var onRecents: () -> Unit = {}
    var onPower: () -> Unit = {}
    /**
     * 「更多」菜单的条目。
     *
     * 用 `State<List<…>>` 而不是回调：菜单条目要跟着"当前选的是硬解还是快照""投屏开没开"
     * 变化，而浮层住在独立 composition 里，只有 State 能把这些变化推过去。
     */
    val options = mutableStateOf<List<UfiPopupOption>>(emptyList())
}

/**
 * 画面上的浮层。
 *
 * ## 三态（与设计稿一一对应）
 * 1. [ScreenPhase.MASK_OPEN]：全屏遮罩。既说明状态，也**顺手盖掉 SurfaceView 上残留的
 *    最后一帧** —— 否则"断开"之后画面还停在那儿，看着像没断开。
 * 2. [ScreenPhase.MASK_CONNECTING]：**同一块全屏遮罩**，中间换成进度环 + 取消。
 *    刻意不让底栏在这一态先出现：连上的那一帧如果结构突变，会有"换了个页面"的错觉。
 * 3. [ScreenPhase.CONNECTED]：悬浮控制岛。四条边都不贴屏，遮挡从贴底通栏的约 17% 降到 10%。
 *
 * ## 为什么遮罩是实心 94% 而不是渐变
 * 上一版在底栏下面铺 200px 的黑色渐变，压在被投屏的**浅色**画面上会糊成一片雾；
 * 实心底 + 一次性淡出反而更干净，也省掉一次全屏渐变绘制。
 */
@Composable
private fun ControlOverlay(state: ScreenOverlayState) {
    // ★★ 2026-11-11（用户："连接动画先变暗再变亮，会闪"）★★
    //
    // 根因不是 scale/fade 本身，而是把**不透明的全屏遮罩底**也放进了 AnimatedContent：
    // 两态交叉时旧底 alpha 在降、新底 alpha 还没到 1，两层叠起来仍不是完全不透明，
    // 下面的 SurfaceView（通常是深色画面）会从中间透出来 —— 先暗一下再亮回去。
    //
    // 现在把遮罩底单独拿出来：
    // - MASK_OPEN ↔ MASK_CONNECTING 底色始终为 1，只有中间内容交叉淡入，不再闪烁；
    // - MASK ↔ CONNECTED 时底色用 animateFloatAsState 单独淡出/淡入，
    //   与内容换场解耦，仍能看到画面出现/遮罩落下。
    //
    // 内容换场规则保持不变：
    // - **淡入 180ms / 淡出 120ms**：进比出慢一点，后一屏先成形、前一屏才散尽；
    // - **scale 只给入场方**（0.98 → 1）：这是"内容浮现"的常见语言，屏幕坐标系不变；
    // - **sizeTransform = null**：三态都是整屏，补间容器尺寸只会带来伪位移与逐帧重测量。
    val palette = com.ufi_axis.ui.theme.LocalResolvedPalette.current
    val phase = state.phase.value
    val maskBgAlpha by animateFloatAsState(
        targetValue = if (phase == ScreenPhase.CONNECTED) 0f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "screenMaskBg",
    )

    Box(Modifier.fillMaxSize()) {
        // 遮罩底：不属于 AnimatedContent，避免两层半透明底叠出暗闪。
        if (maskBgAlpha > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(palette.pageBg.copy(alpha = maskBgAlpha))
            )
        }
        AnimatedContent(
            targetState = phase,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                // `using(null)` = 关掉容器尺寸补间（三态都是整屏，补间只会带来伪位移与逐帧重测量）
                ((fadeIn(tween(180)) + scaleIn(initialScale = 0.98f, animationSpec = tween(180))) togetherWith
                    fadeOut(tween(120))) using null
            },
            label = "screenOverlayPhase",
        ) { targetPhase ->
            Box(Modifier.fillMaxSize()) {
                when (targetPhase) {
                    ScreenPhase.MASK_OPEN -> OpenMask(state)
                    ScreenPhase.MASK_CONNECTING -> ConnectingMask(state)
                    ScreenPhase.CONNECTED -> ConnectedOverlay(state)
                }
            }
        }
    }
}

/**
 * 打开时：全屏遮罩 + 居中内容（无卡片边框，内容直接铺在遮罩上）。
 *
 * ★ 2026-11-03（用户反馈"背景是黑色的，我需要白色"）：底色原来写成
 * `palette.scrim.copy(alpha = 0.94f)` —— 而 `scrim` 在**明暗两套主题里都是黑色**
 * （浅色 35%、深色 50%），把 alpha 拉到 0.94 就变成了一块纯黑板，浅色主题下与白色页面
 * 撞得很难看，标题/说明的 `textPrimary` 也会因为底色变了而失去对比。
 *
 * 现在改用 [com.ufi_axis.ui.theme.UfiPalette.pageBg]：
 * - 浅色主题 = 页面白底 ⇒ 用户要的"白色遮罩"；
 * - 深色主题 = 页面深底 ⇒ 与其余页面一致，不会出现一块突兀的白板。
 *
 * 顺带一个好处：底色不透明，SurfaceView 上残留的最后一帧被彻底盖住，不必再叠模糊。
 */
@Composable
private fun OpenMask(state: ScreenOverlayState) {
    val palette = com.ufi_axis.ui.theme.LocalResolvedPalette.current
    Box(
        // 底色由 ControlOverlay 统一铺；这一层只负责居中的内容，避免换场时 alpha 叠出暗闪。
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        MaskContent(
            glyph = {
                MaskGlyph(palette.accent) {
                    Icon(
                        Icons.Default.Smartphone,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(28.dp),
                    )
                }
            },
            title = state.title.value,
            subtitle = state.subtitle.value,
            isError = state.isError.value,
            params = state.params.value,
        ) {
            MaskButton(text = state.primaryLabel.value, primary = true, onClick = { state.onPrimary() })
            Spacer(Modifier.height(9.dp))
            MaskButton(
                text = "画面设置",
                primary = false,
                icon = Icons.Default.Tune,
                onClick = { state.onOpenSettings() },
            )
        }
    }
}

/** 连接中：同一块全屏遮罩，中间换成进度环 + 取消 */
@Composable
private fun ConnectingMask(state: ScreenOverlayState) {
    val palette = com.ufi_axis.ui.theme.LocalResolvedPalette.current
    Box(
        // 同上：底色由 ControlOverlay 统一铺，连接中与打开态之间只换内容。
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        MaskContent(
            glyph = { ProgressRing(palette.accent) },
            title = state.title.value,
            subtitle = state.subtitle.value,
            // 连接失败时同一屏就地变成"错误态"，不必退回上一屏 —— 用户不用再点一次才知道发生了什么
            isError = state.isError.value,
            params = state.params.value,
        ) {
            MaskButton(text = "取消", primary = false, onClick = { state.onCancel() })
        }
    }
}

/**
 * 遮罩的公共骨架：图标 / 标题 / 说明 /（按钮）/ 参数脚注。
 *
 * 三态里"打开时"与"连接中"只差中间那块图标与按钮，其余排版逐像素一致 ——
 * 这样连上前后不会有字号、行距、间距上的跳动。
 */
@Composable
private fun MaskContent(
    glyph: @Composable () -> Unit,
    title: String,
    subtitle: String,
    isError: Boolean,
    params: String,
    actions: @Composable () -> Unit,
) {
    val palette = com.ufi_axis.ui.theme.LocalResolvedPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp).padding(bottom = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        glyph()
        Spacer(Modifier.height(22.dp))
        Text(
            text = title,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = if (isError) palette.error else palette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 260.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 270.dp).fillMaxWidth()) { actions() }
        if (params.isNotBlank()) {
            Spacer(Modifier.height(26.dp))
            Text(
                text = params,
                fontSize = 11.5.sp,
                color = palette.textSecondary.copy(alpha = 0.75f),
            )
        }
    }
}

/** 遮罩顶部的方形图标（66dp / 22dp 圆角 / 强调色 12%） */
@Composable
private fun MaskGlyph(accent: Color, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(66.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.26f), RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 不确定进度环：转动的 220° 弧；不显示假百分比 */
@Composable
private fun ProgressRing(accent: Color) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "screenRing")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = 1200,
                easing = androidx.compose.animation.core.LinearEasing,
            ),
        ),
        label = "screenRingAngle",
    )
    androidx.compose.foundation.Canvas(Modifier.size(66.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 3.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawArc(accent.copy(alpha = 0.16f), 0f, 360f, false, style = stroke)
        drawArc(accent, angle, 220f, false, style = stroke)
    }
}

/** 遮罩里的按钮：实心 = 主操作，描边 = 次操作 */
@Composable
private fun MaskButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    val palette = com.ufi_axis.ui.theme.LocalResolvedPalette.current
    val ink = if (primary) palette.onAccent else palette.textPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (primary) palette.accent else Color.Transparent)
            .then(
                if (primary) Modifier
                else Modifier.border(1.dp, palette.divider, RoundedCornerShape(14.dp))
            )
            .pointerInput(onClick) { detectTapGestures { onClick() } },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 已连接：悬浮控制岛（+ 可选的诊断条） */
@Composable
private fun ConnectedOverlay(state: ScreenOverlayState) {
    val palette = com.ufi_axis.ui.theme.LocalResolvedPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (state.showDiag.value) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.scrim.copy(alpha = 0.72f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val lines = state.diagLines.value
                if (lines.isEmpty()) {
                    Text("暂无诊断信息", style = UfiTextStyles.captionTiny, color = palette.onGradient)
                } else {
                    lines.forEach {
                        Text(it, style = UfiTextStyles.captionTiny, color = palette.onGradient)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ★★ 2026-11-05（用户："把岛改成独立按钮，不需要岛"）★★
        //
        // 整条胶囊底撤掉：外框会把五颗键连成"一根工具条"，而它们其实是两组不同性质的动作
        // （左侧会话开关 / 右侧设备导航）。现在每颗键自带底色与描边，彼此 8dp 间隙，
        // 只剩一排浮动按钮浮在画面底部。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── 左：会话开关（断开）──────────────────────────────────────────
            IslandKey(
                // Tabler 的 plug-off：Material 图标里没有"插头"这一族，
                // LinkOff / PowerOff 在 22dp 下都读不出"断开一路连接"。
                painter = painterResource(com.ufi_axis.feature.tools.R.drawable.ufi_ic_plug_off),
                label = "断开",
                onClick = state.onDisconnect,
                tint = palette.accent,
            )
            Spacer(Modifier.weight(1f))
            // ── 右：四颗导航键，靠右对齐 ─────────────────────────────────────
            // 电源键与"在跑"指示灯都从岛内撤掉（这版只要这四颗）：前者进「更多」菜单，
            // 后者原本兼作诊断开关，现在也进菜单（「诊断信息」），从隐藏手势变成可发现。
            IslandKey(rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack), "返回", state.onBack)
            Spacer(Modifier.width(8.dp))
            IslandKey(rememberVectorPainter(Icons.Default.Home), "主页", state.onHome)
            Spacer(Modifier.width(8.dp))
            IslandKey(rememberVectorPainter(Icons.Default.Apps), "最近", state.onRecents)
            Spacer(Modifier.width(8.dp))
            // 「更多」= 锚在这个按钮上的悬浮菜单（本项目唯一的菜单实现）。
            UfiPopupAnchor(
                options = state.options.value,
                anchor = { toggle ->
                    IslandKey(rememberVectorPainter(Icons.Default.MoreHoriz), "更多", toggle)
                },
            )
        }
    }
}

/**
 * 一颗**独立的浮动按钮**：图标 + 文字下标（2026-11-05 二次改版）。
 *
 * ## 为什么不再有"岛"
 * 上一版是"一整条胶囊底 + 里面排按钮"。胶囊一撤，按钮就必须**自己带底** ——
 * 否则白图标压在浅色画面上、或深图标压在深色画面上都会糊成一片，这正是用户提的
 * "仅剩图标和文字会看不清"。现在每颗键各自是一块 `cardBg`（0.92 不透明）+ 描边的小方牌，
 * 彼此留 8dp 间隙，浮在画面之上；颜色全部取自 [LocalResolvedPalette]，跟随明暗与皮肤。
 *
 * ## 尺寸口径（2026-11-05 用户："图标大一点、字小一点"）
 * - 图标 18 → **22dp**
 * - 文字 9.5 → **9sp**，且行高压到 11sp，避免两行挤在一起
 * - 牌面 **52dp 见方**，圆角 16dp —— 与全站卡片圆角语言一致
 *
 * @param painter 图标。既接受 `ImageVector`（经 [rememberVectorPainter] 传入），
 *   也接受 `painterResource`（断开键用的 Tabler 插头图标就是后者）。
 * @param tint 着色；`null` 走主题 `iconTint`。「断开」传 accent，在一排灰键里保持主次。
 */
@Composable
private fun IslandKey(
    painter: Painter,
    label: String,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    val palette = com.ufi_axis.ui.theme.LocalResolvedPalette.current
    val ink = tint ?: palette.iconTint
    Column(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(16.dp))
            // 自身底色：这是"撤掉岛"之后唯一保证可读性的东西
            .background(palette.cardBg.copy(alpha = 0.92f))
            .border(1.dp, palette.divider.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .pointerInput(onClick) { detectTapGestures { onClick() } },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = label,
            tint = ink,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = if (tint != null) ink else palette.textSecondary,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            maxLines = 1,
        )
    }
}


/**
 * 非 Compose 运行时：WS 客户端与解码器。
 *
 * 抽出来是因为它们都在 WS / 解码线程上被读写，交给 Compose 重组会变成
 * "每次重组重建解码器"，表现为画面反复黑一下。
 */
private class ScreenRuntime {
    @Volatile private var client: ScreenStreamClient? = null
    @Volatile private var decoder: ScreenVideoDecoder? = null

    private var pendingConfig: ByteArray? = null
    private var pendingSize: Pair<Int, Int>? = null

    @Volatile private var enableDecoder = false
    @Volatile private var surface: Surface? = null

    @Volatile private var onState: (String) -> Unit = {}
    @Volatile private var onFrame: (Bitmap) -> Unit = {}

    fun connect(
        baseUrl: String,
        token: String,
        ticket: String,
        params: ScreenStreamClient.VideoParams,
        /** false = 只用 WS 做控制，不初始化解码器（快照模式） */
        enableDecoder: Boolean,
        /** 非 null = 硬解直接输出到这块 Surface（不产生像素） */
        surface: Surface? = null,
        onHeader: (Int, Int) -> Unit,
        onControlError: (String) -> Unit,
        onState: (String) -> Unit,
        onFrame: (Bitmap) -> Unit,
    ) {
        close()
        this.onState = onState
        this.onFrame = onFrame
        this.enableDecoder = enableDecoder
        this.surface = surface
        pendingConfig = null
        pendingSize = null

        val listener = object : ScreenStreamClient.Listener {
            override fun onHeader(header: ScreenProtocol.StreamHeader) {
                pendingSize = header.width to header.height
                onHeader(header.width, header.height)
            }

            override fun onConfig(csd: ByteArray) {
                pendingConfig = csd
                if (!enableDecoder) return
                pendingSize?.let { (w, h) -> configureDecoder(w, h, csd) }
            }

            override fun onFrame(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
                decoder?.submit(data, ptsUs, keyframe)
            }

            override fun onClosed(code: Int, reason: String) {
                onControlError(closeText(code, reason))
            }

            override fun onError(cause: Throwable) {
                onControlError("连接失败：${cause.message ?: cause.javaClass.simpleName}")
            }
        }
        client = ScreenStreamClient(baseUrl, token, ticket, listener, params).also { it.connect() }
        onControlError("")
    }

    /** 发一条控制消息。返回 false = 控制通道不可用。 */
    fun send(payload: ByteArray): Boolean = client?.send(payload) == true

    fun statsSnapshot(): String? = decoder?.stats()

    fun renderedFrames(): Long = decoder?.renderedFrames ?: 0L

    fun streamInfo(): String {
        val p = client?.parser ?: return ""
        return "收 ${p.receivedBytes}B / ${p.frameCount} 帧"
    }

    fun close() {
        client?.close()
        client = null
        decoder?.release()
        decoder = null
        pendingConfig = null
        pendingSize = null
        surface = null
    }

    private fun configureDecoder(w: Int, h: Int, csd: ByteArray) {
        decoder?.release()
        val useSurface = surface?.isValid == true
        val d = ScreenVideoDecoder(
            onState = { onState(it) },
            onFrame = { bmp -> onFrame(bmp) },
            surface = if (useSurface) surface else null,
            // Surface 路径不读像素 → 可以放心用厂商硬解（更快更省电）；
            // 像素路径必须软解（厂商硬解的私有/tiled 布局读出来是花屏）
            preferSoftware = !useSurface,
        )
        decoder = d
        if (!d.configure(w, h, csd)) {
            onState("解码器配置失败（${if (useSurface) "Surface" else "像素"}路径），可切换渲染方式再试")
        }
    }
}

/** WS 关闭码 → 一句人话。1008/1013 是 core 侧明确表过态的两类。 */
private fun closeText(code: Int, reason: String): String {
    val head = when (code) {
        1000 -> "连接已关闭"
        1008 -> "被拒绝（凭据/票据无效，或投屏开关已关）"
        1011 -> "服务端内部错误"
        1013 -> "会话未就绪（设备忙 / adb 不可用），稍后重试"
        else -> "连接已断开（$code）"
    }
    return if (reason.isBlank()) head else "$head：$reason"
}

/** 把 PNG 写进系统相册（Pictures/UFI-AXIS）。API 29+ 分区存储下不需要任何权限。 */
private fun savePngToGallery(context: android.content.Context, png: ByteArray): Boolean = runCatching {
    val values = android.content.ContentValues().apply {
        put(
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            "ufi-screen-${System.currentTimeMillis()}.png",
        )
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(
            android.provider.MediaStore.Images.Media.RELATIVE_PATH,
            android.os.Environment.DIRECTORY_PICTURES + "/UFI-AXIS",
        )
    }
    val uri = context.contentResolver
        .insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    context.contentResolver.openOutputStream(uri)?.use { it.write(png) } ?: return false
    true
}.getOrDefault(false)

/** 「二选一」面板的标识与取值（UfiChoiceSheet 用字符串） */


/** 快照轮询的基础间隔（实际节奏是"请求 + 这个间隔"，见调用处注释） */
private const val SCREENSHOT_INTERVAL_MS = 650L

/**
 * 「画面设置」弹窗：会话启动参数（长边 / 帧率 / 码率）。
 *
 * 这是本页**唯一**还需要弹窗的东西：三个滑块 + 一个"应用并重连"，菜单装不下表单。
 * 用法严格按公共弹窗的约定：
 * - 容器 [UfiScrollableDialog]：正文进滚动区，按钮进 `actions` 槽位（FIX-24：按钮必须在
 *   滚动区外，否则内容一长按钮会被顶走、底部留白也算错）；
 * - 正文块包在 [UfiDialogBody] 里，块间 12dp 由它统一给，调用方不再自己 spacedBy；
 * - **不套** `UfiSettingsGroup` —— 那是"页面上的卡片"，自带阴影与左右外边距，
 *   塞进弹窗会和 shell 的内边距叠成双重缩进（2026-11-04 的"样式错乱"就是这么来的）。
 */
@Composable
private fun ScreenSettingsDialog(
    open: Boolean,
    onDismiss: () -> Unit,
    maxSize: Int,
    maxFps: Int,
    bitRateKbps: Int,
    applied: Triple<Int, Int, Int>?,
    onSize: (Int) -> Unit,
    onFps: (Int) -> Unit,
    onBitRate: (Int) -> Unit,
    onApply: () -> Unit,
) {
    UfiScrollableDialog(
        visible = open,
        onDismiss = onDismiss,
        title = "画面设置",
        actions = {
            UfiDialogActions(
                onDismiss = onDismiss,
                onConfirm = onApply,
                confirmText = "应用并重连",
                dismissText = "取消",
            )
        },
    ) {
        UfiDialogBody {
            UfiDialogNote("三项都是会话启动参数，改完要重开会话（scrcpy 运行期改不了）。")
            if (applied != null) {
                UfiDialogNote(
                    "当前生效：长边 ${applied.first}px · ${applied.second}fps · " +
                        "${applied.third / 1000f}Mbps"
                )
            }
            UfiSlider(
                value = maxSize.toFloat(),
                onValueChange = { onSize((it.toInt() / 8) * 8) },
                valueRange = 320f..1280f,
                label = "长边",
                valueLabel = "${maxSize}px",
            )
            UfiSlider(
                value = maxFps.toFloat(),
                onValueChange = { onFps(it.toInt().coerceIn(5, 60)) },
                valueRange = 5f..60f,
                label = "帧率",
                valueLabel = "${maxFps}fps",
            )
            UfiSlider(
                value = bitRateKbps.toFloat(),
                onValueChange = { onBitRate((it.toInt() / 128) * 128) },
                valueRange = 512f..8000f,
                label = "码率",
                valueLabel = "${bitRateKbps}kbps",
            )
        }
    }
}
