package com.ufi_axis.ui.media

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaNotification

/**
 * 把**当前这一句歌词**推到媒体通知的第二行（2026-10-21）。
 *
 * ## 为什么只能走通知，不能走蓝牙
 * AVRCP 协议里**没有歌词字段**（只有 title / artist / album / track / duration / artwork），
 * 所以"让车机屏幕显示歌词"在协议层就做不到。能做的只有两件事：
 * 1. 通知的 `contentText` / `subText` —— 车机与手表**有的**会读这两处（取决于厂商实现）；
 * 2. MediaSession metadata 的 subtitle —— 但它由 player 的 `MediaItem` 提供，逐句改
 *    `MediaItem.metadata` 会触发重新 prepare（音频会断一下），所以**只在换歌时**设一次，
 *    绝不逐句更新。
 *
 * ## 为什么这里自己重新 post 通知
 * media3 的通知只在**播放状态变化**时重建，歌词每句变一次它不会知道。所以这里记住上一个
 * 由 `MediaNotification.Provider` 产出并已经在通知栏上的那条通知，歌词换句时用同一个
 * notificationId 把它重建一次（就地更新，不会出现第二条通知）。
 *
 * 节流：只在**这一句文案变了**时才重新 post（一首歌几十次，开销可忽略）；开关关闭时直接不做。
 */
internal object UfiAudioLyricBadge {

    /** 最近一次由 service 的 provider 产出并 post 出去的通知（用来就地改写）。 */
    private var lastNotificationId: Int = 0
    private var lastNotification: Notification? = null

    /** 上一次已经写进通知的那一句：相同就不重复 post（探针每秒都会回调）。 */
    private var lastAppliedLine: String? = null

    /** provider 每次产出通知时登记一下，后续才有对象可以就地改写。 */
    fun rememberNotification(notificationId: Int, notification: Notification) {
        lastNotificationId = notificationId
        lastNotification = notification
    }

    /**
     * 把 [result] 这条通知改写成"带上当前歌词"的版本。
     *
     * 拿不到原始通知（还没 post 过）时原样返回 —— 第一次那条由 media3 自己 post，
     * 下一句开始才由 [push] 接管。
     */
    @UnstableApi
    fun decorate(context: Context, result: MediaNotification, line: String?): MediaNotification? {
        val text = line?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val rebuilt = Notification.Builder.recoverBuilder(context, result.notification)
                .setContentText(text)
                // subText 是很多车机/手表读的那一行（手表列表里显示在应用名下方）
                .setSubText(text)
                .build()
            MediaNotification(result.notificationId, rebuilt)
        }.getOrNull()
    }

    /**
     * 歌词换句时调用（探针每秒回调一次）。
     *
     * @param line 当前这一句；null / 空串 = 此刻没有"正在唱的那一句"（前奏、间奏、纯文本歌词）
     * @param enabled 用户在设置里是否打开了「蓝牙歌词」
     */
    @UnstableApi
    fun push(context: Context, line: String?, enabled: Boolean) {
        if (!enabled || line.isNullOrBlank()) return
        if (line == lastAppliedLine) return
        val base = lastNotification ?: return
        lastAppliedLine = line
        runCatching {
            val rebuilt = Notification.Builder.recoverBuilder(context, base)
                .setContentText(line)
                .setSubText(line)
                .build()
            NotificationManagerCompat.from(context).notify(lastNotificationId, rebuilt)
            // 记住改写后的版本，下一句在它基础上继续改（否则会把上一句的改动丢掉）
            lastNotification = rebuilt
        }
    }

    /** 换歌 / 关开关时复位：下一条通知重新由 provider 产出。 */
    fun reset() {
        lastAppliedLine = null
    }
}
