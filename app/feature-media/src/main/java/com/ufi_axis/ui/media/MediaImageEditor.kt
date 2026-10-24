package com.ufi_axis.ui.media

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.ui.theme.UfiTextStyles
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图片编辑器（2026-10-21 第二版：完整版）。
 *
 * ## 五个页签
 * · **裁剪**：左转 / 右转 / 水平翻转 / 垂直翻转、自由裁剪（四角手柄 + 三分线）、比例锁、拉直
 * · **调色**：亮度 / 对比度 / 饱和度 / 色温（ColorMatrix，拖滑杆不重建位图）
 * · **滤镜**：原图 / 黑白 / 暖调 / 冷调 / 胶片 / 褪色（同样是 ColorMatrix 预设）
 * · **工具**：马赛克涂抹、文字水印（九宫格）、圆角
 * · **导出**：长边尺寸、格式、质量 + 两个保存入口
 *
 * ## 三条不变量（改这个文件前先读）
 * 1. **预览与导出共用 [drawEdited]**（`MediaImageEditModel.kt`）。预览只是"画布更小"，
 *    所以不存在"预览对了、导出错了"。新效果一律加进那个函数，不要另写一份预览逻辑。
 * 2. **撤销存的是 [MediaImageEditState]**，不是位图：滑杆拖动中不入栈，松手才入栈一次
 *    （拖动前的快照由 [sliderBaseline] 兜住）；离散操作与裁剪框松手立即入栈。
 * 3. **长按看原图 / 拖裁剪框 / 马赛克涂抹共用一个手势循环**：先判"这是拖还是按住了"，
 *    所以画裁剪框时不会突然切成原图，长按时也不会误拖。
 */
private const val TAB_CROP = 0
private const val TAB_TONE = 1
private const val TAB_FILTER = 2
private const val TAB_TOOL = 3
private const val TAB_EXPORT = 4

/** 撤销步数上限。存的是 state（几十字节），给得大方一点也不心疼。 */
private const val UNDO_LIMIT = 50

/** 按住多久算"看原图"。 */
private const val COMPARE_HOLD_MS = 320L

/** 裁剪框最小边长（归一化），避免被拖成一条线。 */
private const val CROP_MIN = 0.06f

/** 角手柄的触控半径。 */
private val HANDLE_TOUCH = 28.dp

/** 就地图层版入场缩放。 */
private const val EDITOR_OVERLAY_SCALE_FROM = 0.97f

/** 命中判定结果：0..3 = 四个角，4 = 整体移动，-1 = 没命中。 */
private const val HANDLE_NONE = -1
private const val HANDLE_MOVE = 4

@Composable
internal fun MediaImageEditor(
    item: MediaLibraryItem,
    url: String,
    onDismiss: () -> Unit,
    onSaved: (String, ToastType) -> Unit,
    exportToPhone: suspend (File, String) -> Result<Unit>,
    uploadCopy: (Uri, String) -> Unit,
    fileProviderAuthority: String
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 关掉"窗口自己内缩系统栏"：否则状态栏被算两遍，顶部条与灯箱对不上
            decorFitsSystemWindows = false
        )
    ) {
        MediaImageEditorBody(
            item, url, onDismiss, onSaved, exportToPhone, uploadCopy, fileProviderAuthority
        )
    }
}

/**
 * 编辑器**就地浮层**版：从灯箱点「编辑」走这条（不建窗口 ⇒ 没有窗口合成的空帧）。
 *
 * `AnimatedVisibility` 在退场期间继续画内容，所以 [item] 变 null 之后动画还能播完；
 * 内容取 `SideEffect` 记下的最后一张（首帧为空时也只是黑底，看不出来）。
 */
@Composable
internal fun MediaImageEditorOverlay(
    visible: Boolean,
    item: MediaLibraryItem?,
    url: String,
    onDismiss: () -> Unit,
    onSaved: (String, ToastType) -> Unit,
    exportToPhone: suspend (File, String) -> Result<Unit>,
    uploadCopy: (Uri, String) -> Unit,
    fileProviderAuthority: String
) {
    val lastItem = remember { mutableStateOf<MediaLibraryItem?>(null) }
    val lastUrl = remember { mutableStateOf("") }
    SideEffect {
        if (item != null) {
            lastItem.value = item
            lastUrl.value = url
        }
    }
    AnimatedVisibility(
        visible = visible && item != null,
        enter = fadeIn(tween(UfiMotion.Duration.Swift)) + scaleIn(
            animationSpec = tween(UfiMotion.Duration.Swift),
            initialScale = EDITOR_OVERLAY_SCALE_FROM
        ),
        exit = fadeOut(tween(UfiMotion.Duration.Swift)) + scaleOut(
            animationSpec = tween(UfiMotion.Duration.Swift),
            targetScale = EDITOR_OVERLAY_SCALE_FROM
        )
    ) {
        val shown = lastItem.value
        if (shown != null) {
            MediaImageEditorBody(
                shown, lastUrl.value, onDismiss, onSaved, exportToPhone, uploadCopy, fileProviderAuthority
            )
        }
    }
}

@Composable
private fun MediaImageEditorBody(
    item: MediaLibraryItem,
    url: String,
    onDismiss: () -> Unit,
    onSaved: (String, ToastType) -> Unit,
    exportToPhone: suspend (File, String) -> Result<Unit>,
    uploadCopy: (Uri, String) -> Unit,
    fileProviderAuthority: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var source by remember(url) { mutableStateOf<Bitmap?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var state by remember(url) { mutableStateOf(MediaImageEditState()) }
    val undoStack = remember(url) { mutableStateListOf<MediaImageEditState>() }
    val redoStack = remember(url) { mutableStateListOf<MediaImageEditState>() }
    var busy by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(TAB_CROP) }
    var mosaicPainting by remember { mutableStateOf(false) }

    // 导出设置：默认值与上一版（2560 + JPEG 92）一致，什么都不改就是原来的行为
    var exportSize by remember { mutableStateOf(ExportSize.P2560) }
    var exportFormat by remember { mutableStateOf(ExportFormat.Jpeg) }
    var exportQuality by remember { mutableFloatStateOf(92f) }

    LaunchedEffect(url) {
        error = null
        val loaded = loadEditableBitmap(context, url)
        if (loaded == null) error = "这张图片打不开" else source = loaded
    }

    fun pushUndo(before: MediaImageEditState) {
        if (before == state) return
        undoStack.add(before)
        if (undoStack.size > UNDO_LIMIT) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun commit(next: MediaImageEditState) {
        if (next == state) return
        pushUndo(state)
        state = next
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(state)
        state = undoStack.removeAt(undoStack.lastIndex)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(state)
        state = redoStack.removeAt(redoStack.lastIndex)
    }

    // 滑杆：拖动中只改 state，松手才把"拖动前"压栈（否则每一步微调都会占一格撤销）
    var sliderBaseline by remember { mutableStateOf<MediaImageEditState?>(null) }
    fun sliderChanged(next: MediaImageEditState) {
        if (sliderBaseline == null) sliderBaseline = state
        state = next
    }

    fun sliderFinished() {
        val base = sliderBaseline
        sliderBaseline = null
        if (base != null) pushUndo(base)
    }

    fun save(kind: SaveTarget) {
        val src = source ?: return
        keyboard?.hide()
        busy = true
        scope.launch {
            val exported = withContext(Dispatchers.IO) {
                runCatching {
                    val out = state.exportBitmap(src, exportSize.longEdge)
                    val file = writeEditCache(
                        context = context,
                        bitmap = out,
                        name = editedFileName(item.name, exportFormat),
                        format = exportFormat,
                        quality = exportQuality.roundToInt()
                    )
                    out.recycle()
                    file
                }
            }
            val file = exported.getOrNull()
            if (file == null) {
                busy = false
                onSaved("导出失败：${exported.exceptionOrNull()?.message.orEmpty()}", ToastType.ERROR)
                return@launch
            }
            when (kind) {
                SaveTarget.Phone -> {
                    val result = exportToPhone(file, file.name)
                    busy = false
                    if (result.isSuccess) {
                        onSaved("已保存到手机下载目录（${file.name}）", ToastType.SUCCESS)
                    } else {
                        onSaved(
                            "保存失败：${result.exceptionOrNull()?.message.orEmpty()}",
                            ToastType.ERROR
                        )
                    }
                }
                SaveTarget.Device -> {
                    val dir = item.path.substringBeforeLast('/', missingDelimiterValue = "")
                    runCatching {
                        uploadCopy(FileProvider.getUriForFile(context, fileProviderAuthority, file), dir)
                    }
                    busy = false
                    onSaved("已提交上传（设备端另存为 ${file.name}）", ToastType.SUCCESS)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 纯黑：与灯箱同一个底色，切换时只剩内容在动
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            EditorTopBar(
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                onClose = onDismiss,
                onUndo = { undo() },
                onRedo = { redo() },
                onSaveDevice = { save(SaveTarget.Device) },
                onSavePhone = { save(SaveTarget.Phone) }
            )
            EditorTabBar(selected = tab, onSelect = { tab = it })

            val bitmap = source
            when {
                error != null -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(error.orEmpty(), style = UfiTextStyles.note, color = Color.White.copy(alpha = 0.7f))
                }
                bitmap == null -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    UfiLoadingIndicator()
                }
                else -> {
                    CropPreview(
                        source = bitmap,
                        state = state,
                        tab = tab,
                        mosaicPainting = mosaicPainting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = LIGHTBOX_SIDE_PADDING),
                        onLive = { state = it },
                        onGestureCommit = { before -> pushUndo(before) }
                    )
                    EditorPanel(
                        tab = tab,
                        state = state,
                        onCommit = { commit(it) },
                        onLiveCommit = { before, next ->
                            if (next != before) {
                                pushUndo(before)
                                state = next
                            }
                        },
                        onSliderChange = { sliderChanged(it) },
                        onSliderFinished = { sliderFinished() },
                        mosaicPainting = mosaicPainting,
                        onMosaicPainting = { mosaicPainting = it },
                        exportSize = exportSize,
                        onExportSize = { exportSize = it },
                        exportFormat = exportFormat,
                        onExportFormat = { exportFormat = it },
                        exportQuality = exportQuality,
                        onExportQuality = { exportQuality = it },
                        onSaveDevice = { save(SaveTarget.Device) },
                        onSavePhone = { save(SaveTarget.Phone) }
                    )
                }
            }
        }

        if (busy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                UfiLoadingIndicator()
            }
        }
    }
}

/** 保存目标：手机下载目录 / 设备端原目录副本。 */
private enum class SaveTarget { Phone, Device }

/** 顶部条：关闭 / 撤销 / 重做 … 另存到设备 / 保存到手机。 */
@Composable
private fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSaveDevice: () -> Unit,
    onSavePhone: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Small, vertical = Spacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
        }
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = "撤销",
                tint = if (canUndo) Color.White else Color.White.copy(alpha = 0.3f)
            )
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(
                Icons.AutoMirrored.Filled.Redo,
                contentDescription = "重做",
                tint = if (canRedo) Color.White else Color.White.copy(alpha = 0.3f)
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSaveDevice) {
            Icon(Icons.Default.Save, contentDescription = "另存到设备", tint = Color.White)
        }
        IconButton(onClick = onSavePhone) {
            Icon(Icons.Default.Download, contentDescription = "保存到手机", tint = Color.White)
        }
    }
}

private val EDITOR_TABS = listOf("裁剪", "调色", "滤镜", "工具", "导出")

/** 页签条：文字 + 选中下划线（与播放页那排同一种轻量做法）。 */
@Composable
private fun EditorTabBar(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Small),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        EDITOR_TABS.forEachIndexed { index, label ->
            val active = index == selected
            Column(
                modifier = Modifier
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    label,
                    style = if (active) UfiTextStyles.bodyLeadStrong else UfiTextStyles.body,
                    color = if (active) Color.White else Color.White.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(if (active) 20.dp else 0.dp)
                        .height(3.dp)
                        .background(Color.White, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

/**
 * 预览 + 手势。
 *
 * 一个手势循环里分三种意图（见文件头"三条不变量"第 3 条）：
 * · 裁剪页签：按下即判定命中哪个手柄 → 拖动改裁剪框；
 * · 马赛克模式：按下即开始涂抹；
 * · 其它：按住 [COMPARE_HOLD_MS] 不动 = 看原图；动了就什么都不做。
 */
@Composable
private fun CropPreview(
    source: Bitmap,
    state: MediaImageEditState,
    tab: Int,
    mosaicPainting: Boolean,
    modifier: Modifier = Modifier,
    onLive: (MediaImageEditState) -> Unit,
    onGestureCommit: (before: MediaImageEditState) -> Unit
) {
    var comparing by remember { mutableStateOf(false) }
    val liveState = rememberUpdatedState(state)
    val srcW = source.width.toFloat()
    val srcH = source.height.toFloat()

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                if (comparing) {
                    drawOriginalForCompare(
                        native, source, size.width.roundToInt(), size.height.roundToInt()
                    )
                } else {
                    // 裁剪页签里马赛克/水印先不画：那时看的是"还没裁的整张图"，装饰会落在
                    // 最终根本不会保留的区域上，反而干扰判断
                    val cropTab = tab == TAB_CROP
                    val shown = if (cropTab) {
                        liveState.value.copy(
                            mosaicStrokes = emptyList(),
                            watermark = liveState.value.watermark.copy(text = "")
                        )
                    } else {
                        liveState.value
                    }
                    drawEdited(
                        native,
                        source,
                        shown,
                        size.width.roundToInt(),
                        size.height.roundToInt(),
                        // 裁剪页签看**整张旋转后的图**（框外面还剩什么一目了然）；其它页签看成品
                        cropOverride = if (cropTab) CropBox.FULL else null
                    )
                }
            }
        }

        // 裁剪框（只在裁剪页签、且不在看原图时显示）。纯展示，手势在上面的透明层里统一处理。
        if (tab == TAB_CROP && !comparing) {
            val crop = liveState.value.crop
            CropFrame(
                crop = liveState.value.crop,
                // 裁剪页签画布铺的是**整张图**，映射基准就是整块（0..1）
                eff = CropBox.FULL,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 手势层：覆盖整个预览区（含裁剪框），统一分发三种意图
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(tab, mosaicPainting, srcW, srcH) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val vw = size.width.toFloat()
                        val vh = size.height.toFloat()
                        if (vw <= 0f || vh <= 0f) return@awaitEachGesture
                        val before = liveState.value
                        val geo = before.geometry(srcW, srcH)
                        val handlePx = HANDLE_TOUCH.toPx()

                        // 意图判定
                        var mode = MODE_IDLE
                        var handle = HANDLE_NONE
                        if (tab == TAB_CROP) {
                            handle = hitHandle(before.crop, CropBox.FULL, vw, vh, down.position, handlePx)
                            if (handle != HANDLE_NONE) mode = MODE_CROP
                        } else if (mosaicPainting) {
                            mode = MODE_MOSAIC
                        }
                        var comparingNow = false
                        val downAt = System.currentTimeMillis()
                        var moved = 0f
                        var pending = before

                        if (mode == MODE_MOSAIC) {
                            pending = addMosaicDot(before, down.position.x / vw, down.position.y / vh)
                            onLive(pending)
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.position - change.previousPosition
                            moved += delta.getDistance()
                            when (mode) {
                                MODE_CROP -> {
                                    change.consume()
                                    pending = dragCrop(
                                        before = pending,
                                        handle = handle,
                                        deltaX = delta.x,
                                        deltaY = delta.y,
                                        vw = vw,
                                        vh = vh,
                                        srcW = srcW,
                                        srcH = srcH,
                                        rotW = geo.rotatedW,
                                        rotH = geo.rotatedH
                                    )
                                    onLive(pending)
                                }
                                MODE_MOSAIC -> {
                                    change.consume()
                                    pending = addMosaicDot(
                                        pending,
                                        change.position.x / vw,
                                        change.position.y / vh
                                    )
                                    onLive(pending)
                                }
                                MODE_IDLE -> {
                                    if (moved > viewConfiguration.touchSlop) {
                                        // 动了：这不是长按，后续也不接管（交还给外层滚动）
                                        mode = MODE_OTHER
                                    } else if (System.currentTimeMillis() - downAt >= COMPARE_HOLD_MS) {
                                        mode = MODE_OTHER
                                        comparingNow = true
                                        comparing = true
                                        change.consume()
                                    }
                                }
                                else -> Unit
                            }
                        }
                        if (comparingNow) {
                            comparing = false
                        } else if (mode == MODE_CROP || mode == MODE_MOSAIC) {
                            if (pending != before) {
                                onLive(pending)
                                onGestureCommit(before)
                            }
                        }
                    }
                }
        )

        if (comparing) {
            Text(
                "原图",
                style = UfiTextStyles.caption,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

private const val MODE_IDLE = 0
private const val MODE_CROP = 1
private const val MODE_MOSAIC = 2
private const val MODE_OTHER = 3

/** 命中哪个手柄：四个角 → 0..3，框内 → [HANDLE_MOVE]，框外 → [HANDLE_NONE]。 */
private fun hitHandle(
    crop: CropBox,
    eff: CropBox,
    vw: Float,
    vh: Float,
    pos: Offset,
    touchPx: Float
): Int {
    // 屏幕坐标 ↔ 归一化：预览里"生效裁剪框"铺满画布
    fun sx(u: Float) = (u - eff.left) / eff.width * vw
    fun sy(v: Float) = (v - eff.top) / eff.height * vh
    val corners = listOf(
        Offset(sx(crop.left), sy(crop.top)),
        Offset(sx(crop.right), sy(crop.top)),
        Offset(sx(crop.left), sy(crop.bottom)),
        Offset(sx(crop.right), sy(crop.bottom))
    )
    corners.forEachIndexed { index, c ->
        if ((c - pos).getDistance() <= touchPx) return index
    }
    if (pos.x >= sx(crop.left) && pos.x <= sx(crop.right) &&
        pos.y >= sy(crop.top) && pos.y <= sy(crop.bottom)
    ) {
        return HANDLE_MOVE
    }
    return HANDLE_NONE
}

/** 拖裁剪框：像素位移 → 归一化位移，再按手柄方向改边；带最小尺寸与边界夹取。 */
private fun dragCrop(
    before: MediaImageEditState,
    handle: Int,
    deltaX: Float,
    deltaY: Float,
    vw: Float,
    vh: Float,
    srcW: Float,
    srcH: Float,
    rotW: Float,
    rotH: Float
): MediaImageEditState {
    // 生效裁剪框按**源图尺寸**算（拉直的内缩比例只与源图尺寸和总角度有关）
    val eff = before.geometry(srcW, srcH).crop
    // 显示基准是整张图（CropBox.FULL），所以像素位移直接除以画布尺寸
    val dnx = deltaX / vw
    val dny = deltaY / vh
    var c = before.crop
    c = when (handle) {
        0 -> CropBox(
            (c.left + dnx).coerceIn(eff.left, c.right - CROP_MIN), (c.top + dny).coerceIn(eff.top, c.bottom - CROP_MIN),
            c.right, c.bottom
        )
        1 -> CropBox(
            c.left, (c.top + dny).coerceIn(eff.top, c.bottom - CROP_MIN),
            (c.right + dnx).coerceIn(c.left + CROP_MIN, eff.right), c.bottom
        )
        2 -> CropBox(
            (c.left + dnx).coerceIn(eff.left, c.right - CROP_MIN), c.top,
            c.right, (c.bottom + dny).coerceIn(c.top + CROP_MIN, eff.bottom)
        )
        3 -> CropBox(
            c.left, c.top,
            (c.right + dnx).coerceIn(c.left + CROP_MIN, eff.right),
            (c.bottom + dny).coerceIn(c.top + CROP_MIN, eff.bottom)
        )
        else -> {
            // 整体移动：先夹取宽高，再夹位置，避免"贴着边还能继续挤出画布"
            val w = c.width
            val h = c.height
            val left = (c.left + dnx).coerceIn(eff.left, eff.right - w)
            val top = (c.top + dny).coerceIn(eff.top, eff.bottom - h)
            CropBox(left, top, left + w, top + h)
        }
    }
    // 比例锁：按"拖哪个角、对角固定"重算另一条边
    before.ratio?.let { ratio -> c = fitRatio(c, ratio, rotW, rotH, handle) }
    return before.copy(crop = c)
}

/**
 * 比例锁：保持 `像素宽 / 像素高 = ratio`。
 *
 * 归一化空间的宽高比不等于像素宽高比（旋转后的画布不是正方形），所以必须乘上 rotW / rotH
 * 换算回去 —— 直接拿归一化值算会得到"看着像 1:1 其实不是"的结果。
 */
private fun fitRatio(c: CropBox, ratio: Float, rotW: Float, rotH: Float, handle: Int): CropBox {
    val pxW = c.width * rotW
    val pxH = c.height * rotH
    if (pxW <= 0f || pxH <= 0f) return c
    val current = pxW / pxH
    var w = c.width
    var h = c.height
    if (current > ratio) {
        // 太宽：按高度反推宽度
        w = h * rotH * ratio / rotW
    } else {
        h = w * rotW / ratio / rotH
    }
    // 以"被拖的角的对角"为锚点重新落位
    val anchorLeft = when (handle) {
        0, 2 -> c.right - w   // 拖左侧 → 右缘固定
        else -> c.left
    }
    val anchorTop = when (handle) {
        0, 1 -> c.bottom - h  // 拖上侧 → 下缘固定
        else -> c.top
    }
    val resized = CropBox(anchorLeft, anchorTop, anchorLeft + w, anchorTop + h)
    // 夹回 0..1 之内（比例已经定死，只能整体平移）
    val shiftX = when {
        resized.left < 0f -> -resized.left
        resized.right > 1f -> 1f - resized.right
        else -> 0f
    }
    val shiftY = when {
        resized.top < 0f -> -resized.top
        resized.bottom > 1f -> 1f - resized.bottom
        else -> 0f
    }
    return CropBox(
        resized.left + shiftX,
        resized.top + shiftY,
        resized.right + shiftX,
        resized.bottom + shiftY
    )
}

/** 马赛克：每个采样点落一个小方块（同一笔画里的方块重叠成一片）。 */
private fun addMosaicDot(state: MediaImageEditState, nx: Float, ny: Float): MediaImageEditState {
    val s = state.mosaicSize / 2f
    val box = CropBox(
        (nx - s).coerceIn(0f, 1f),
        (ny - s).coerceIn(0f, 1f),
        (nx + s).coerceIn(0f, 1f),
        (ny + s).coerceIn(0f, 1f)
    )
    if (box.width <= 0f || box.height <= 0f) return state
    return state.copy(mosaicStrokes = state.mosaicStrokes + box)
}

/** 裁剪框的可视部分：外面压暗 + 框线 + 三分线 + 四角括号。 */
@Composable
private fun CropFrame(crop: CropBox, eff: CropBox, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        fun sx(u: Float) = (u - eff.left) / eff.width * w
        fun sy(v: Float) = (v - eff.top) / eff.height * h
        val l = sx(crop.left)
        val t = sy(crop.top)
        val r = sx(crop.right)
        val b = sy(crop.bottom)

        // 框外压暗（四条矩形，比挖洞路径便宜且不会有接缝）
        val dim = Color.Black.copy(alpha = 0.55f)
        drawRect(dim, topLeft = Offset(0f, 0f), size = Size(w, t))
        drawRect(dim, topLeft = Offset(0f, b), size = Size(w, h - b))
        drawRect(dim, topLeft = Offset(0f, t), size = Size(l, b - t))
        drawRect(dim, topLeft = Offset(r, t), size = Size(w - r, b - t))

        // 三分线
        val guide = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            val x = l + (r - l) * i / 3f
            val y = t + (b - t) * i / 3f
            drawLine(guide, Offset(x, t), Offset(x, b), strokeWidth = 1.dp.toPx())
            drawLine(guide, Offset(l, y), Offset(r, y), strokeWidth = 1.dp.toPx())
        }

        // 框线
        drawRect(
            color = Color.White,
            topLeft = Offset(l, t),
            size = Size(r - l, b - t),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // 四角括号（比整圈描边更像"可以拖"的手柄）
        val arm = 18.dp.toPx()
        val stroke = 3.dp.toPx()
        fun corner(cx: Float, cy: Float, dx: Float, dy: Float) {
            drawLine(Color.White, Offset(cx, cy), Offset(cx + dx * arm, cy), strokeWidth = stroke)
            drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + dy * arm), strokeWidth = stroke)
        }
        corner(l, t, 1f, 1f)
        corner(r, t, -1f, 1f)
        corner(l, b, 1f, -1f)
        corner(r, b, -1f, -1f)
    }
}

// ───────────────────────── 底部面板 ─────────────────────────

@Composable
private fun EditorPanel(
    tab: Int,
    state: MediaImageEditState,
    onCommit: (MediaImageEditState) -> Unit,
    onLiveCommit: (before: MediaImageEditState, next: MediaImageEditState) -> Unit,
    onSliderChange: (MediaImageEditState) -> Unit,
    onSliderFinished: () -> Unit,
    mosaicPainting: Boolean,
    onMosaicPainting: (Boolean) -> Unit,
    exportSize: ExportSize,
    onExportSize: (ExportSize) -> Unit,
    exportFormat: ExportFormat,
    onExportFormat: (ExportFormat) -> Unit,
    exportQuality: Float,
    onExportQuality: (Float) -> Unit,
    onSaveDevice: () -> Unit,
    onSavePhone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Medium, vertical = Spacing.Small)
    ) {
        when (tab) {
            TAB_CROP -> CropPanel(state, onCommit, onSliderChange, onSliderFinished)
            TAB_TONE -> TonePanel(state, onCommit, onSliderChange, onSliderFinished)
            TAB_FILTER -> FilterPanel(state, onCommit)
            TAB_TOOL -> ToolPanel(
                state = state,
                onCommit = onCommit,
                onSliderChange = onSliderChange,
                onSliderFinished = onSliderFinished,
                mosaicPainting = mosaicPainting,
                onMosaicPainting = onMosaicPainting
            )
            else -> ExportPanel(
                size = exportSize,
                onSize = onExportSize,
                format = exportFormat,
                onFormat = onExportFormat,
                quality = exportQuality,
                onQuality = onExportQuality,
                onSaveDevice = onSaveDevice,
                onSavePhone = onSavePhone
            )
        }
    }
}

@Composable
private fun CropPanel(
    state: MediaImageEditState,
    onCommit: (MediaImageEditState) -> Unit,
    onSliderChange: (MediaImageEditState) -> Unit,
    onSliderFinished: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChoiceChip("左转") { onCommit(state.copy(rotation = (state.rotation - 90).normalizedRotation())) }
        ChoiceChip("右转") { onCommit(state.copy(rotation = (state.rotation + 90).normalizedRotation())) }
        ChoiceChip("水平翻转") { onCommit(state.copy(flipH = !state.flipH)) }
        ChoiceChip("垂直翻转") { onCommit(state.copy(flipV = !state.flipV)) }
        ChoiceChip("重置裁剪") {
            onCommit(state.copy(crop = CropBox.FULL, straighten = 0f, ratio = null))
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("比例", style = UfiTextStyles.caption, color = Color.White.copy(alpha = 0.6f))
        ChoiceChip("自由", selected = state.ratio == null) {
            onCommit(state.copy(ratio = null))
        }
        listOf(1f to "1:1", 4f / 3f to "4:3", 16f / 9f to "16:9", 3f / 4f to "3:4").forEach { (r, label) ->
            ChoiceChip(label, selected = state.ratio == r) {
                onCommit(state.copy(ratio = r, crop = centeredCrop(state.crop, r)))
            }
        }
    }
    EditorSlider(
        label = "拉直",
        value = state.straighten,
        range = -15f..15f,
        onChange = { onSliderChange(state.copy(straighten = it)) },
        onFinished = onSliderFinished
    )
}

/** 换比例时把现有裁剪框"居中收缩"到目标比例（不动位置中心，用户不会突然找不到框）。 */
private fun centeredCrop(current: CropBox, ratio: Float): CropBox {
    // 归一化空间里没有画布尺寸信息，这里先按"正方形假设"做近似 → 实际的像素比例由 fitRatio 在拖动时校正
    val cx = current.centerX
    val cy = current.centerY
    val maxW = 2f * min(cx, 1f - cx) * 0.98f
    val maxH = 2f * min(cy, 1f - cy) * 0.98f
    var w = min(current.width, maxW)
    var h = min(current.height, maxH)
    if (w / h > ratio) w = h * ratio else h = w / ratio
    return CropBox(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
}

@Composable
private fun TonePanel(
    state: MediaImageEditState,
    onCommit: (MediaImageEditState) -> Unit,
    onSliderChange: (MediaImageEditState) -> Unit,
    onSliderFinished: () -> Unit
) {
    EditorSlider(
        label = "亮度",
        value = state.brightness,
        range = -1f..1f,
        onChange = { onSliderChange(state.copy(brightness = it)) },
        onFinished = onSliderFinished
    )
    EditorSlider(
        label = "对比度",
        value = state.contrast,
        range = -1f..1f,
        onChange = { onSliderChange(state.copy(contrast = it)) },
        onFinished = onSliderFinished
    )
    EditorSlider(
        label = "饱和度",
        value = state.saturation,
        range = -1f..1f,
        onChange = { onSliderChange(state.copy(saturation = it)) },
        onFinished = onSliderFinished
    )
    EditorSlider(
        label = "色温",
        value = state.warmth,
        range = -1f..1f,
        onChange = { onSliderChange(state.copy(warmth = it)) },
        onFinished = onSliderFinished
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            "重置调色",
            style = UfiTextStyles.caption,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier
                .clickable {
                    onCommit(state.copy(brightness = 0f, contrast = 0f, saturation = 0f, warmth = 0f))
                }
                .padding(8.dp)
        )
    }
}

@Composable
private fun FilterPanel(state: MediaImageEditState, onCommit: (MediaImageEditState) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ImageFilter.entries.forEach { f ->
            ChoiceChip(f.label, selected = state.filter == f) { onCommit(state.copy(filter = f)) }
        }
    }
}

@Composable
private fun ToolPanel(
    state: MediaImageEditState,
    onCommit: (MediaImageEditState) -> Unit,
    onSliderChange: (MediaImageEditState) -> Unit,
    onSliderFinished: () -> Unit,
    mosaicPainting: Boolean,
    onMosaicPainting: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChoiceChip(if (mosaicPainting) "涂抹中（点此结束）" else "马赛克涂抹", selected = mosaicPainting) {
            onMosaicPainting(!mosaicPainting)
        }
        ChoiceChip("清除马赛克") { onCommit(state.copy(mosaicStrokes = emptyList())) }
    }
    if (mosaicPainting) {
        EditorSlider(
            label = "笔刷大小",
            value = state.mosaicSize,
            range = 0.02f..0.2f,
            onChange = { onSliderChange(state.copy(mosaicSize = it)) },
            onFinished = onSliderFinished
        )
    }
    // ── 水印 ──
    Text("水印文字", style = UfiTextStyles.caption, color = Color.White.copy(alpha = 0.6f))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        if (state.watermark.text.isEmpty()) {
            Text("留空则不加水印", style = UfiTextStyles.note, color = Color.White.copy(alpha = 0.35f))
        }
        BasicTextField(
            value = state.watermark.text,
            onValueChange = { text ->
                onSliderChange(state.copy(watermark = state.watermark.copy(text = text.take(40))))
            },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (state.watermark.text.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("位置", style = UfiTextStyles.caption, color = Color.White.copy(alpha = 0.6f))
            Spacer(Modifier.width(10.dp))
            NineGridPicker(
                selected = state.watermark.position,
                onSelect = {
                    onCommit(state.copy(watermark = state.watermark.copy(position = it)))
                }
            )
        }
        EditorSlider(
            label = "字号",
            value = state.watermark.sizeRatio,
            range = 0.02f..0.12f,
            onChange = { onSliderChange(state.copy(watermark = state.watermark.copy(sizeRatio = it))) },
            onFinished = onSliderFinished
        )
    }
    EditorSlider(
        label = "圆角",
        value = state.cornerRadius,
        range = 0f..0.12f,
        onChange = { onSliderChange(state.copy(cornerRadius = it)) },
        onFinished = onSliderFinished
    )
}

/** 九宫格位置选择：3×3 小方块，选中那颗填白。 */
@Composable
private fun NineGridPicker(selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (row in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (col in 0..2) {
                    val index = row * 3 + col
                    val active = index == selected
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(
                                if (active) Color.White else Color.White.copy(alpha = 0.15f),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { onSelect(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportPanel(
    size: ExportSize,
    onSize: (ExportSize) -> Unit,
    format: ExportFormat,
    onFormat: (ExportFormat) -> Unit,
    quality: Float,
    onQuality: (Float) -> Unit,
    onSaveDevice: () -> Unit,
    onSavePhone: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("长边", style = UfiTextStyles.caption, color = Color.White.copy(alpha = 0.6f))
        ExportSize.entries.forEach { s ->
            ChoiceChip(s.label, selected = size == s) { onSize(s) }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("格式", style = UfiTextStyles.caption, color = Color.White.copy(alpha = 0.6f))
        ExportFormat.entries.forEach { f ->
            ChoiceChip(f.label, selected = format == f) { onFormat(f) }
        }
        if (!format.hasQuality) {
            Text("无损，无需质量", style = UfiTextStyles.caption, color = Color.White.copy(alpha = 0.4f))
        }
    }
    if (format.hasQuality) {
        EditorSlider(
            label = "质量 ${quality.roundToInt()}",
            value = quality,
            range = 60f..100f,
            onChange = { onQuality(it) },
            onFinished = {}
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DarkButton("另存到设备", Modifier.weight(1f), onSaveDevice)
        DarkButton("保存到手机", Modifier.weight(1f), onSavePhone)
    }
}

// ───────────────────────── 小零件 ─────────────────────────

@Composable
private fun ChoiceChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
                RoundedCornerShape(10.dp)
            )
            .border(
                1.dp,
                if (selected) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            style = UfiTextStyles.caption,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun DarkButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = UfiTextStyles.bodyLeadStrong, color = Color.White)
    }
}

/**
 * 一条深色版的滑杆：标签与数值在左，滑杆占满宽度。
 *
 * 不用 M3 默认配色 —— 默认是浅色主题的（轨道近白、标签用 `onSurface`），压在纯黑上会看不见。
 */
@Composable
private fun EditorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = UfiTextStyles.caption,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.width(64.dp)
        )
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.85f),
                inactiveTrackColor = Color.White.copy(alpha = 0.22f)
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

/** 把编辑结果写进 cache（导出 / 上传都从这里取）。 */
private fun writeEditCache(
    context: android.content.Context,
    bitmap: Bitmap,
    name: String,
    format: ExportFormat,
    quality: Int
): File {
    val dir = File(context.cacheDir, "image-edit").apply { mkdirs() }
    val file = File(dir, name)
    val compressFormat: Bitmap.CompressFormat = when (format) {
        ExportFormat.Jpeg -> Bitmap.CompressFormat.JPEG
        ExportFormat.Png -> Bitmap.CompressFormat.PNG
        ExportFormat.Webp -> Bitmap.CompressFormat.WEBP_LOSSY
    }
    FileOutputStream(file).use { out ->
        bitmap.compress(compressFormat, quality.coerceIn(1, 100), out)
    }
    return file
}
