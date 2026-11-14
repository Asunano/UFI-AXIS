package com.ufi_axis.installer.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ufi_axis.installer.R
import com.ufi_axis.installer.core.NotificationHelper
import com.ufi_axis.installer.logging.InstallLogger
import com.ufi_axis.installer.state.InstallEngine
import com.ufi_axis.installer.state.InstallStage
import com.ufi_axis.installer.state.InstallState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 安装前台服务。
 *
 * 为什么必须是前台服务：一次完整安装要经历
 * 「推 APK（可能几十 MB）+ 十多项权限授予 + 启动等待 5s + 健康检查最多 6×5s」，
 * 全程可能 1~3 分钟。若只跑在 Activity 里，用户切后台或锁屏就会被系统冻结。
 *
 * 服务本身不做业务，只负责两件事：
 * 1. 撑住前台通知，保证进程不被杀
 * 2. 把 [InstallEngine] 的状态变化同步到通知栏
 */
class InstallerService : LifecycleService() {

    /** 本次服务实例是否真的收到过安装任务：避免把上一轮遗留的结束态当成本轮完成 */
    @Volatile
    private var taskStarted = false

    /** startForeground 是否成功。失败时只能退化成普通服务，必须让用户知道。 */
    @Volatile
    private var foregroundOk = false

    private var wakeLock: PowerManager.WakeLock? = null

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
                taskStarted = true
                warnIfDegraded()
                acquireWakeLock()
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                InstallEngine.start(address, lifecycleScope)
            }
            ACTION_CANCEL -> {
                InstallEngine.cancel()
                // 没有任务在跑时收到取消（例如服务已因上一轮结束而停止后又被拉起）：
                // 直接退出，别留一个挂着前台通知的空服务
                if (!InstallEngine.isRunning()) stopSelfSafely()
            }
            else -> {
                // 服务被系统拉起但没带动作：如果没在跑就自行退出，避免占着通知
                if (!InstallEngine.isRunning()) stopSelfSafely()
            }
        }
        return START_NOT_STICKY
    }

    /** 前台服务或通知不可用时，安装仍能跑，但必须提示用户别锁屏 */
    private fun warnIfDegraded() {
        if (!foregroundOk) {
            InstallLogger.warn("前台服务未生效：安装期间请保持应用在前台、不要锁屏，否则可能被系统冻结")
        }
        if (!NotificationHelper.hasPermission(this)) {
            InstallLogger.warn("通知权限未授予：通知栏不会显示安装进度，请在应用内查看进度与日志")
        }
    }

    /**
     * 安装期间持一把 PARTIAL_WAKE_LOCK。
     *
     * 前台服务只保证进程优先级，不保证灭屏后 CPU 不挂起；推几十 MB APK 时
     * 部分 ROM 会让传输停滞。按「事件驱动 + 按需持锁」的原则，只在安装这段时间持锁，
     * 结束（[releaseWakeLock]）立刻释放，不常驻。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            InstallLogger.warn("获取 WakeLock 失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    /** 订阅引擎状态，把关键节点反映到通知栏 */
    private fun observeState() {
        lifecycleScope.launch {
            InstallEngine.state.collectLatest { state ->
                if (taskStarted && state.finished) {
                    // 结束态：通知必须是可划掉的（ongoing=false）。
                    // 这里也是服务的唯一退出点——不退的话通知会一直挂着。
                    finishAndStop(state)
                    return@collectLatest
                }
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

    private fun finishAndStop(state: InstallState) {
        taskStarted = false
        releaseWakeLock()
        try {
            NotificationHelper.update(
                this,
                getString(R.string.app_name),
                state.statusText,
                ongoing = false
            )
        } catch (_: Exception) {
        }
        // DETACH：退出前台但保留这条结束态通知，让用户还能看到结果
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        } catch (_: Exception) {
        }
        stopSelf()
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
            foregroundOk = true
        } catch (e: Exception) {
            // Android 12+ 后台启动限制、Android 14 的 FGS 类型校验都会抛异常，
            // 此时只能降级为普通服务。必须把异常类型记下来，否则表现成「安装莫名失败」。
            foregroundOk = false
            InstallLogger.error(
                "前台服务启动失败（${e.javaClass.simpleName}）：${e.message ?: "无详细信息"}"
            )
        }
    }

    private fun stopSelfSafely() {
        releaseWakeLock()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        const val ACTION_START = "com.ufi_axis.installer.action.START"
        const val ACTION_CANCEL = "com.ufi_axis.installer.action.CANCEL"
        const val EXTRA_ADDRESS = "extra_address"

        private const val WAKE_LOCK_TAG = "ufi-axis-installer:install"

        /** 兜底超时：正常流程会主动释放，这里只防「异常路径下忘记释放」 */
        private const val WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1000L

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
