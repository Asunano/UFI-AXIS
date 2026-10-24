package com.ufi_axis.ui.media

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.UfiMotion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.media3.common.Player
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiOptionGrid
import com.ufi_axis.ui.components.common.UfiOptionItem
import com.ufi_axis.ui.components.common.UfiRingProgress
import com.ufi_axis.ui.components.common.UfiSwitch
import com.ufi_axis.ui.components.common.UfiTextField
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import kotlinx.coroutines.delay

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  定时关闭（2026-10-14）
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * ## 它取代了底部那排的"随机"开关
 * 底部那排原来是 循环 / 随机 / 队列。用户要求把"随机"从独立开关收进**模式**（列表循环 /
 * 单曲循环 / 不循环 / 随机模式，见播放页的 [AudioPlayMode]），空出来的那一格给定时关闭。
 *
 * ## 状态为什么是进程级单例
 * 定时器不能因为用户离开播放页就停 —— 用户完全可能"设 30 分钟然后退出播放页去刷别的"。
 * 所以状态放在这里，倒计时挂在**常驻组合**里（[UfiSleepTimerEffect]，由
 * `UfiAudioNowPlayingProbe` 调用，那一层在 App 壳里一直在），而不是挂在播放页上。
 */

/** 可选的时长（分钟）。 */
private val UFI_SLEEP_PRESET_MINUTES = listOf(10, 20, 30, 60)

/** 自定义时长那一格的哨兵值（真实时长都是正数，撞不上）。 */
private const val UFI_SLEEP_CUSTOM_VALUE = -1

/** 自定义时长的允许区间（分钟）：上限 1440 = 24 小时，够长又不会溢出。 */
private const val UFI_SLEEP_CUSTOM_MIN = 1
private const val UFI_SLEEP_CUSTOM_MAX = 1440

/** 自定义输入框最多几位数字（4 位够到 1440，再长只能是误触）。 */
private const val UFI_SLEEP_CUSTOM_MAX_DIGITS = 4

/**
 * 到点后"放完当前这首再停"这条路的收口方式：等**这一首结束**（换歌 / 播完）再暂停。
 *
 * 为什么不用"位置快到头了就暂停"：位置轮询是 1s 一次，而结尾那几百毫秒里 poll 可能整个
 * 跨过去（上一帧还在 11.5s、下一帧歌已经换了），于是要么早停要么停不下来。改成听播放器的
 * 换歌回调之后，判定变成"pending 的那一首已经不是当前这一首了"，与轮询节奏无关。
 */

/**
 * 定时关闭的状态。生命周期跟着进程（见文件头"状态为什么是进程级单例"）。
 */
internal object UfiSleepTimer {

    /** 截止时刻（`SystemClock.elapsedRealtime()`）；null = 没开。 */
    var deadlineMs by mutableStateOf<Long?>(null)
        private set

    /**
     * 到点后是否先放完当前这首（默认**开**）。
     *
     * 关掉时到点**立刻暂停**（哪怕这首刚播到一半）；打开时这一首放完再停。
     */
    var finishCurrentTrack by mutableStateOf(true)

    /**
     * 到点后等着放完的那一首的 mediaId；null = 没有待办（立即暂停那条路走完就一直是 null）。
     */
    var pendingPauseTrackId by mutableStateOf<String?>(null)
        private set

    /** 定时是否开着。 */
    val isActive: Boolean get() = deadlineMs != null

    /**
     * 这一次定时的**总时长**（2026-10-20）。
     * 倒计时圆环要按「剩余 / 总时长」画，取模会变成"每分钟转一圈"，那是错的。
     */
    var totalMs by mutableStateOf(0L)
        private set

    /** 剩余毫秒（没开时为 0）。 */
    fun remainingMs(nowMs: Long = SystemClock.elapsedRealtime()): Long =
        deadlineMs?.let { (it - nowMs).coerceAtLeast(0L) } ?: 0L

    /** 开一个 [durationMs] 的定时。 */
    fun start(durationMs: Long) {
        totalMs = durationMs
        deadlineMs = SystemClock.elapsedRealtime() + durationMs
        pendingPauseTrackId = null
    }

    /** 关掉定时（"关闭定时"那颗按钮 / 播完到点之后自己清）。 */
    fun cancel() {
        deadlineMs = null
        totalMs = 0L
        pendingPauseTrackId = null
    }

    /**
     * 到点了。[currentTrackId] 是这一刻正在播的那一首。
     *
     * 两种收口方式：立即暂停，或挂上"这一首放完再停"（由 [onPlayerSynced] 在换歌时执行）。
     */
    fun onExpired(controller: Player, currentTrackId: String) {
        if (finishCurrentTrack && currentTrackId.isNotBlank()) {
            pendingPauseTrackId = currentTrackId
        } else {
            controller.pause()
        }
        deadlineMs = null
    }

    /**
     * 播放器每次状态同步时调用（见 `UfiAudioNowPlayingProbe` 的 `sync()`）。
     *
     * 有 pending 时：只要当前这一首已经**不是** pending 那一首（换歌了），或者队列已经播完
     *（STATE_ENDED），就把播放停掉并清掉 pending。
     */
    fun onPlayerSynced(player: Player) {
        val pending = pendingPauseTrackId ?: return
        val current = player.currentMediaItem?.mediaId.orEmpty()
        if (current != pending || player.playbackState == Player.STATE_ENDED) {
            player.pause()
            pendingPauseTrackId = null
        }
    }
}

/**
 * 倒计时与到点执行。挂在**常驻组合**里（`UfiAudioNowPlayingProbe` 那一层），理由见文件头。
 *
 * 倒计时本身不产生 UI：`deadlineMs` 是唯一事实，按钮上的剩余时间是它自己的纯函数
 *（见 [rememberSleepTimerLabel]），这里只负责"到点那一刻把事办了"。
 */
@Composable
internal fun UfiSleepTimerEffect() {
    val deadline = UfiSleepTimer.deadlineMs
    LaunchedEffect(deadline) {
        if (deadline == null) return@LaunchedEffect
        while (true) {
            val remain = deadline - SystemClock.elapsedRealtime()
            if (remain <= 0) break
            delay(minOf(remain, 500L))
        }
        // 到点这一刻再读控制器与当前曲目：effect 是在设定时那一刻启动的，
        // 捕获进去的值到这里可能已经过期（用户中途切了歌）
        val controller = UfiNowPlayingState.controller.value ?: return@LaunchedEffect
        UfiSleepTimer.onExpired(controller, UfiNowPlayingState.mediaId.value)
    }
}

/**
 * 按钮上那行字：没开定时是"定时关闭"，开着是剩余分钟数（向上取整，免得刚开始就显示 0 分）。
 *
 * 每 30 秒刷一次而不是每秒：显示的是分钟，秒级刷新只会让整块按钮白重组。
 */
@Composable
internal fun rememberSleepTimerLabel(): String {
    val deadline = UfiSleepTimer.deadlineMs
    var nowMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadline) {
        if (deadline == null) return@LaunchedEffect
        while (true) {
            nowMs = SystemClock.elapsedRealtime()
            delay(30_000L)
        }
    }
    if (deadline == null) return "定时关闭"
    val remainMin = ((deadline - nowMs).coerceAtLeast(0L) + 59_999L) / 60_000L
    return "定时 ${remainMin}分"
}

/**
 * 定时关闭的设置弹窗：四个常用时长 + 「自定义…」 + 一个"播完当前这首再停"的开关 + 关闭定时。
 *
 * 用 [UfiCustomDialog] 而不是自绘：与全 App 其他弹窗同一套壳（标题、右上角 ×、
 * 离场动画），这一条在队列弹窗那边踩过坑（摘子树会把离场动画一起摘掉）。
 *
 * ## 「自定义」为什么做在同一层里（换内容），而不是再开一个输入弹窗
 * 再开一个 `UfiInputDialog` 就是两个窗口叠着：下层的离场动画、返回键归谁、点外部先关谁，
 * 三条都要额外想。这里改成同一个弹窗换内容（标题也跟着换），只有一层。
 */
/** 倒计时的「X分SS秒」展示（2026-10-20，定时弹窗里用；向上取整，免得刚开就显示 0 秒）。 */
private fun formatRemaining(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
    return "${totalSeconds / 60}分${(totalSeconds % 60).toString().padStart(2, '0')}秒"
}

@Composable
internal fun UfiSleepTimerDialog(
    visible: Boolean,
    onDismiss: () -> Unit
) {
    val palette = LocalResolvedPalette.current

    /** 是否停在"自定义"那一页。 */
    var custom by remember { mutableStateOf(false) }
    /** 自定义输入框里的文本（只留数字，最多 4 位）。 */
    var customText by remember { mutableStateOf("") }

    /*
     * 2026-10-20（用户要求，两条一起改）：
     * 1) 弹窗里显示倒计时；
     * 2) 弹窗内容在关闭瞬间**不能变**。原来直接读 `UfiSleepTimer.isActive`：用户点任一档位
     *    （或点"关闭定时"）会让「关闭定时」按钮立刻出现/消失，而弹窗同时正在播离场动画 ——
     *    观感就是内容抽搐一下。现在进入弹窗时**快照**一次，存活期间不再随单例变化；
     *    要看到最新状态，重新打开弹窗即可。
     */
    val activeOnOpen = remember(visible) { UfiSleepTimer.isActive }
    var remainingMs by remember(visible) { mutableStateOf(UfiSleepTimer.remainingMs()) }
    LaunchedEffect(visible, activeOnOpen) {
        if (!visible || !activeOnOpen) return@LaunchedEffect
        while (true) {
            remainingMs = UfiSleepTimer.remainingMs()
            if (remainingMs <= 0L) break
            kotlinx.coroutines.delay(1_000L)
        }
    }

    // 关掉之后复位：下次打开是干净的"时长选择"页，输入框也不会留着上一次的数字
    LaunchedEffect(visible) {
        if (!visible) {
            custom = false
            customText = ""
        }
    }

    val customMinutes = customText.toIntOrNull()
    val customError = when {
        customText.isBlank() -> null
        customMinutes == null ||
            customMinutes < UFI_SLEEP_CUSTOM_MIN ||
            customMinutes > UFI_SLEEP_CUSTOM_MAX ->
            "请输入 $UFI_SLEEP_CUSTOM_MIN - $UFI_SLEEP_CUSTOM_MAX 之间的分钟数"
        else -> null
    }

    // 2026-10-20（用户要求）：
    // ① 有定时时**隐藏时长选择**，整块让给倒计时（圆环 + 剩余时间）；
    // ② 三页（选择 / 自定义 / 倒计时）之间加过渡动画 —— 原来 custom 页是硬切，观感就是"抽搐"。
    val page: String = when {
        activeOnOpen -> "countdown"
        custom -> "custom"
        else -> "options"
    }

    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "定时关闭",
        showCloseButton = true
    ) {
        UfiDialogBody {
            // 2026-10-20：**不再用 AnimatedContent**。三页高度差很大，动画中间态会被弹窗外壳
            // 按旧高度裁剪（用户看到的就是"开关那一行错乱/被切"）。这里直接切内容，稳定优先；
            // 需要过渡的话在页面之间做，不在弹窗内做。
            when (page) {
                "countdown" -> {
                    val total = UfiSleepTimer.totalMs.coerceAtLeast(1L)
                    val fraction = (remainingMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        UfiRingProgress(
                            progress = fraction,
                            size = 160.dp,
                            strokeWidth = 12.dp,
                            color = palette.accent,
                            trackColor = palette.divider,
                            animate = false,
                            centerContent = {
                                Text(
                                    text = formatRemaining(remainingMs),
                                    style = UfiTextStyles.sectionTitle,
                                    color = palette.textPrimary
                                )
                            }
                        )
                    }
                    Spacer(Modifier.height(Spacing.Large))
                    Text(
                        if (UfiSleepTimer.finishCurrentTrack) "到点后等这一首放完再停。" else "到点后立即停止播放。",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.Large))
                    UfiButton(
                        text = "关闭定时",
                        onClick = {
                            UfiSleepTimer.cancel()
                            onDismiss()
                        },
                        variant = UfiButtonVariant.Subtle,
                        size = UfiButtonSize.Standard,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                "custom" -> {
                    UfiTextField(
                        value = customText,
                        onValueChange = { input ->
                            customText = input.filter { it.isDigit() }.take(UFI_SLEEP_CUSTOM_MAX_DIGITS)
                        },
                        label = "时长（分钟）",
                        placeholder = "$UFI_SLEEP_CUSTOM_MIN - $UFI_SLEEP_CUSTOM_MAX",
                        isError = customError != null,
                        errorMessage = customError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.Large))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Large)
                    ) {
                        UfiButton(
                            text = "返回",
                            onClick = { custom = false },
                            variant = UfiButtonVariant.Subtle,
                            size = UfiButtonSize.Standard,
                            modifier = Modifier.weight(1f)
                        )
                        UfiButton(
                            text = "开始计时",
                            onClick = {
                                val minutes = customMinutes ?: return@UfiButton
                                UfiSleepTimer.start(minutes * 60_000L)
                                onDismiss()
                            },
                            variant = UfiButtonVariant.Primary,
                            size = UfiButtonSize.Standard,
                            enabled = customMinutes != null && customError == null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                else -> {
                    Text(
                        "到点后停止播放。开启下面的开关时，会等这一首放完再停。",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.Large))
                    UfiOptionGrid(
                        options = UFI_SLEEP_PRESET_MINUTES.map { minutes ->
                            UfiOptionItem(value = minutes, label = "$minutes 分钟")
                        } + UfiOptionItem(value = UFI_SLEEP_CUSTOM_VALUE, label = "自定义…"),
                        selectedValue = 0,
                        onSelect = { minutes ->
                            if (minutes == UFI_SLEEP_CUSTOM_VALUE) {
                                custom = true
                            } else {
                                UfiSleepTimer.start(minutes * 60_000L)
                                onDismiss()
                            }
                        },
                        columns = 2
                    )
                    Spacer(Modifier.height(Spacing.Large))
                    // 「播完当前这首再停」：左右两段**各占权重**，开关固定在行尾；
                    // 之前它挤在一行里被文字宽度推着走，看起来就是"错乱"。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "播完当前这首再停",
                                style = UfiTextStyles.body,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "关掉时到点立即停止（可能停在一首歌中间）",
                                style = UfiTextStyles.caption,
                                color = palette.textSecondary
                            )
                        }
                        Spacer(Modifier.width(Spacing.Medium))
                        UfiSwitch(
                            checked = UfiSleepTimer.finishCurrentTrack,
                            onCheckedChange = { UfiSleepTimer.finishCurrentTrack = it }
                        )
                    }
                }
            }
        }
    }
}
