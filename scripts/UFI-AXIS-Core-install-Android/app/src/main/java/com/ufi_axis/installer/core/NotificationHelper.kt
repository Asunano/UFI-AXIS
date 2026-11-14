package com.ufi_axis.installer.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ufi_axis.installer.MainActivity
import com.ufi_axis.installer.R

/**
 * 前台服务通知。
 *
 * 安装过程可能持续几分钟（推 APK + 10 多项授权 + 健康检查重试 6 次 × 5 秒），
 * 必须挂前台服务，否则锁屏后进程可能被杀。
 */
object NotificationHelper {

    const val CHANNEL_ID = "ufi_axis_install"
    const val NOTIFICATION_ID = 1001

    /** 创建通知渠道（幂等） */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** 构造前台服务通知。点击回到 MainActivity。 */
    fun build(
        context: Context,
        title: String,
        text: String,
        ongoing: Boolean = true
    ): Notification {
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getActivity(context, 0, intent, flags)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    /**
     * 就地更新通知内容。
     *
     * Android 13+ 未授予 POST_NOTIFICATIONS 时 `notify` 是**静默丢弃**（不抛异常），
     * 所以这里先判权限再走，避免白做一次构造，也让调用方知道通知不可用。
     *
     * @return 是否真的下发了通知
     */
    fun update(context: Context, title: String, text: String, ongoing: Boolean = true): Boolean {
        if (!hasPermission(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return try {
            manager.notify(NOTIFICATION_ID, build(context, title, text, ongoing))
            true
        } catch (_: SecurityException) {
            // 通知权限被拒时不阻断流程
            false
        }
    }

    /** 结束态通知：ongoing=false，可被划掉 */
    fun finish(context: Context, title: String, text: String) {
        update(context, title, text, ongoing = false)
    }

    /** 判断通知权限是否已授予（Android 13+ 需要运行时权限） */
    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
