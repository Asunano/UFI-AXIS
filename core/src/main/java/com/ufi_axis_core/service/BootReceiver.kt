package com.ufi_axis_core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings

/**
 * 开机广播接收器
 *
 * 接收 BOOT_COMPLETED 广播，根据配置决定是否自动启动 BackendService
 */
class BootReceiver : BroadcastReceiver() {

    private val tag = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                val settings = AppSettings.getInstance(context)
                if (settings.autoStartOnBoot) {
                    AppLogger.i(tag, "Boot completed, starting BackendService...")
                    BackendService.start(context)
                } else {
                    AppLogger.i(tag, "Boot completed, auto-start disabled")
                }
            }
        }
    }
}
