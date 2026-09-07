package com.ufi_axis.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ufi_axis.util.DebugLog

/**
 * 开机自启 / 覆盖安装恢复广播接收器（独立进程架构 v2 Phase 1）。
 *
 * - BOOT_COMPLETED、MY_PACKAGE_REPLACED 后按 [NotifyService.shouldRun] 恢复守护服务；
 * - 判据与 Application.onCreate / 设置页开关完全一致：只看「前台服务保活」这一个开关。
 *
 *   历史：最初这里要求 `guard_enabled && guard_foreground_keepalive_enabled` 双开关同时为
 *   true，两者默认都是 false，导致开机自启实际从不生效。那次修正**只该放宽 `guard_enabled`**
 *   —— 它管的是 WorkManager 周期任务，与前台服务无关；却把 `guard_foreground_keepalive_enabled`
 *   一起摘掉了，于是「用户没开保活，通知栏仍常驻一条通知」。2026-09-05 把保活键补回，
 *   `guard_enabled` 与「系统通知推送」都不参与（见 [NotifyService.shouldRun]）。
 * - 运行在 `:ufi_notify` 进程（Manifest 声明），Manifest 已声明 RECEIVE_BOOT_COMPLETED。
 *
 * 说明：即使此处不启动 FGS，WorkManager 自带开机重调度兜底（周期任务不丢）。
 */
class NotifyBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (NotifyService.shouldRun(context)) {
            NotifyService.startKeepAlive(context)
            DebugLog.i(TAG, "$action: 启动通知守护前台服务")
        } else {
            DebugLog.i(TAG, "$action: 前台服务保活未开启，跳过守护服务")
        }
    }

    private companion object {
        const val TAG = "NotifyBootReceiver"
    }
}
