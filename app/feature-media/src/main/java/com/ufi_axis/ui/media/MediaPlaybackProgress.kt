package com.ufi_axis.ui.media

import com.ufi_axis.util.AppPreferences

/**
 * 播放进度记忆（视频 / 音乐共用）。2026-09-16。
 *
 * ## 存在哪、为什么
 * 存**手机本地** prefs（[AppPreferences.setMediaPlaybackPosition]），不写 core：
 * 「我看到哪儿了」是个人观看进度，两台手机各记自己的才对；core 那边存的是"这台设备的
 * 媒体库范围"那类**设备**配置。这也意味着换手机不会继承进度 —— 这是取舍，不是遗漏。
 *
 * ## 三条判定（都在这里一处，别散到两个播放页）
 * · **太靠前不续播**（< [MIN_RESUME_MS]）：刚开头几秒的"进度"没有价值，还会让人以为跳错了；
 * · **快看完就清除**（超过 [FINISHED_RATIO]）：否则下次打开会从片尾开始，比从头更糟；
 * · 时长未知（直播/未就绪，`duration <= 0`）时只存不判完成 —— 宁可多留一条记录，
 *   也不要因为拿不到时长就把用户的进度丢掉。
 */

/** 小于这个位置就当"还没开始看"，不续播也不记。 */
private const val MIN_RESUME_MS = 3_000L

/** 超过总时长这个比例视为看完，清掉记录。 */
private const val FINISHED_RATIO = 0.95

/** 上次看到哪（毫秒）。0 = 没有可用的续播点。 */
internal fun mediaResumePosition(prefs: AppPreferences, path: String): Long =
    runCatching { prefs.mediaPlaybackPosition(path) }
        .getOrDefault(0L)
        .takeIf { it >= MIN_RESUME_MS }
        ?: 0L

/**
 * 记下 / 清除进度。
 *
 * 调用点应该在三处：定时（几秒一次）、暂停或切歌时、页面销毁时 ——
 * 只在销毁时存的话，进程被杀就什么都没留下。
 */
internal fun mediaSaveProgress(
    prefs: AppPreferences,
    path: String,
    positionMs: Long,
    durationMs: Long
) {
    if (path.isBlank()) return
    val finished = durationMs > 0 && positionMs >= durationMs * FINISHED_RATIO
    val value = if (finished || positionMs < MIN_RESUME_MS) 0L else positionMs
    runCatching { prefs.setMediaPlaybackPosition(path, value) }
}

/**
 * `h:mm:ss` / `m:ss` 时间文案（续播提示用）。
 *
 * 刻意不叫 `formatPlaybackTime`：音乐播放页自己有一份同名的 private 函数（进度条两端用），
 * 同包重名会直接撞成重复声明。两处需求也不完全一样，各留一份比强行合并更省事。
 */
internal fun formatResumeClock(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
