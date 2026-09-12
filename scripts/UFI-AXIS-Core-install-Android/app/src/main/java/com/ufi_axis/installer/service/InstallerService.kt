package com.ufi_axis.installer.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ufi_axis.installer.R
import com.ufi_axis.installer.core.NotificationHelper
import com.ufi_axis.installer.logging.InstallLogger
import com.ufi_axis.installer.state.InstallEngine
import com.ufi_axis.installer.state.InstallStage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 安装前台服务。
 *
 * 为什么必须是前台服务：一次完整安装要经历
 * 「推 APK（可能几十 MB）+ 12 项权限授予 + 启动等待 5s + 健康检查最多 6×5s」，
 * 全程可能 1~3 分钟。若只跑在 Activity 里，用户切后台或锁屏就会被系统冻结。
 *
 * 服务本身不做业务，只负责两件事：
 * 1. 撑住前台通知，保证进程不被杀
 * 2. 把 [InstallEngine] 的状态变化同步到通知栏
 */
class InstallerService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        startForegroundCompat()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                InstallEngine.start(address, lifecycleScope)
            }
            ACTION_CANCEL -> InstallEngine.cancel()
            ACTION_STOP -> stopSelfSafely()
            else -> {
                // 服务被系统拉起但没带动作：如果没在跑就自行退出，避免占着通知
                if (!InstallEngine.isRunning()) stopSelfSafely()
            }
        }
        return START_NOT_STICKY
    }

    /** 订阅引擎状态，把关键节点反映到通知栏 */
    private fun observeState() {
        lifecycleScope.launch {
            InstallEngine.state.collectLatest { state ->
                val text = when (state.stage) {
                    InstallStage.INSTALL ->
                        if (state.progress >= 0) "正在推送 APK… ${state.progress}%" else state.statusText
                    InstallStage.GRANT ->
                        "正在授予权限 ${state.grantedCount}/${state.totalPermissions}"
                    InstallStage.HEALTH ->
                        "正在检查服务状态 ${state.healthAttempt}/${state.healthMaxAttempts}"
                    InstallStage.CONFIRM -> "等待确认安装"
                    else -> state.statusText
                }
                try {
                    NotificationHelper.update(this@InstallerService, getString(R.string.app_name), text)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun startForegroundCompat() {
        val notification = NotificationHelper.build(this, getString(R.string.app_name), "准备就绪")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android 12+ 后台启动前台服务受限时会抛异常，此时降级为普通服务
            InstallLogger.warn("前台服务启动失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun stopSelfSafely() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        const val ACTION_START = "com.ufi_axis.installer.action.START"
        const val ACTION_CANCEL = "com.ufi_axis.installer.action.CANCEL"
        const val ACTION_STOP = "com.ufi_axis.installer.action.STOP"
        const val EXTRA_ADDRESS = "extra_address"

        /** 启动服务并下发安装任务 */
        fun startInstall(context: Context, address: String) {
            val intent = Intent(context, InstallerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ADDRESS, address)
            }
            startCompat(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, InstallerService::class.java).apply {
                action = ACTION_CANCEL
            }
            startCompat(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, InstallerService::class.java).apply {
                action = ACTION_STOP
            }
            startCompat(context, intent)
        }

        private fun startCompat(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ 从后台启动前台服务会被拒，此时记录并提示用户回前台操作
                InstallLogger.error("无法启动安装服务：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
