package com.ufi_axis.data.notification

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.ufi_axis.util.DebugLog

/**
 * 通知进程（:ufi_notify）AIDL 客户端 —— UI 进程侧封装（独立进程架构 v2 Phase 2）。
 *
 * 职责：
 * - [bind] 异步绑定 :ufi_notify 进程的 NotifyService（显式 ComponentName，按完整类名字符串
 *   引用，避免 :app:data 模块反向依赖 :app 模块的类）；
 * - [setGuardEnabled] / [setGuardIntervalMinutes] / [setDndEnabled] / [reloadConfig]
 *   为代理方法：未绑定 / 绑定失败时静默跳过（进程未启动则不影响 UI）；
 * - [startKeepAlive] / [stopKeepAlive] 为静态入口：启动/停止通知进程前台保活服务。
 *
 * 使用方式（推荐在 Compose Screen 内 remember + DisposableEffect 管理生命周期，
 * 避免 ViewModel 复杂化）：
 * ```
 * val client = remember { NotificationConfigClient() }
 * LaunchedEffect(Unit) { client.bind(context) }
 * DisposableEffect(Unit) { onDispose { client.unbind(context) } }
 * ```
 */
class NotificationConfigClient {

    private var boundService: INotificationConfigService? = null

    private var bindCallback: ((Boolean) -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            boundService = if (service != null) {
                INotificationConfigService.Stub.asInterface(service)
            } else {
                null
            }
            bindCallback?.invoke(boundService != null)
            bindCallback = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }

        override fun onBindingDied(name: ComponentName?) {
            boundService = null
        }
    }

    /**
     * 异步绑定通知进程 NotifyService。
     *
     * 绑定失败（进程未启动 / 服务未声明 / 系统限制）时静默返回 false，不影响 UI。
     *
     * @param callback 可选回调：true=已连接；false=绑定失败（也可能永远不回调，属正常）。
     */
    fun bind(context: Context, callback: ((Boolean) -> Unit)? = null) {
        bindCallback = callback
        val intent = Intent().setComponent(ComponentName(context.packageName, SERVICE_CLASS_NAME))
        val ok = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            DebugLog.w(TAG, "bind NotifyService 失败: ${e.message}")
            false
        }
        if (!ok) {
            boundService = null
            bindCallback?.invoke(false)
            bindCallback = null
        }
    }

    /** 解绑通知进程（Screen onDispose 时调用；未绑定/已解绑时静默）。 */
    fun unbind(context: Context) {
        bindCallback = null
        boundService = null
        try {
            context.unbindService(connection)
        } catch (_: Exception) {
            // 未绑定 / 已解绑：静默
        }
    }

    /** 代理：开/关后台轮询总开关（未绑定则静默跳过）。 */
    fun setGuardEnabled(enabled: Boolean) {
        try {
            boundService?.setGuardEnabled(enabled)
        } catch (e: Exception) {
            DebugLog.w(TAG, "setGuardEnabled 失败: ${e.message}")
        }
    }

    /** 代理：修改轮询间隔（未绑定则静默跳过）。 */
    fun setGuardIntervalMinutes(minutes: Int) {
        try {
            boundService?.setGuardIntervalMinutes(minutes)
        } catch (e: Exception) {
            DebugLog.w(TAG, "setGuardIntervalMinutes 失败: ${e.message}")
        }
    }

    /** 代理：开/关免打扰时段（未绑定则静默跳过）。 */
    fun setDndEnabled(enabled: Boolean) {
        try {
            boundService?.setDndEnabled(enabled)
        } catch (e: Exception) {
            DebugLog.w(TAG, "setDndEnabled 失败: ${e.message}")
        }
    }

    /** 代理：修改免打扰时段（未绑定则静默跳过）。 */
    fun setDndWindow(startHour: Int, endHour: Int) {
        try {
            boundService?.setDndWindow(startHour, endHour)
        } catch (e: Exception) {
            DebugLog.w(TAG, "setDndWindow 失败: ${e.message}")
        }
    }

    /**
     * 代理：开/关前台服务保活（未绑定则静默跳过）。
     *
     * 关闭时这条通道是「常驻通知立刻消失」的关键 —— 服务侧会写 `mirror_` 并当场
     * `stopForeground(STOP_FOREGROUND_REMOVE)`；只调 [stopKeepAlive] 在**本页仍绑定**的情况下
     * 不会销毁服务，通知会残留（见 .aidl 里 `setForegroundKeepAlive` 的注释）。
     */
    fun setForegroundKeepAlive(enabled: Boolean) {
        try {
            boundService?.setForegroundKeepAlive(enabled)
        } catch (e: Exception) {
            DebugLog.w(TAG, "setForegroundKeepAlive 失败: ${e.message}")
        }
    }

    /** 代理：通知进程重新加载配置并按需重调度（未绑定则静默跳过）。 */
    fun reloadConfig() {
        try {
            boundService?.reloadConfig()
        } catch (e: Exception) {
            DebugLog.w(TAG, "reloadConfig 失败: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "NotificationConfigClient"

        /**
         * NotifyService 完整类名（位于 :app 模块 com.ufi_axis.notification 包）。
         *
         * 用字符串而非 Class 引用：本类在 :app:data 模块，直接引用 :app 模块的类会造成
         * 循环依赖；运行时同一 APK classpath 可解析。
         */
        private const val SERVICE_CLASS_NAME = "com.ufi_axis.notification.NotifyService"

        /**
         * 启动通知进程前台保活服务（UI 进程调用；异步失败静默，WorkManager 仍兜底）。
         *
         * 与 `NotifyService.startKeepAlive` 共用同一闸门 [NotifyPrefs.keepAliveShouldRun]：
         * 只看「前台服务保活」这一个开关（「系统通知推送」不参与，见 [KeepAliveGate]）。
         * 这里也要判，是因为本方法是 `NotificationsGuardScreen` / `NotificationConfigSync`
         * 用的入口，不经过 `:app` 那个同名函数。
         *
         * ⚠ 调用顺序：调用方必须**先把开关写进 prefs、再调本方法** ——
         * 闸门读的是 prefs，反过来会被自己刚要打开的那个开关挡掉。
         */
        fun startKeepAlive(context: Context) {
            if (!NotifyPrefs.keepAliveShouldRun(context)) {
                DebugLog.i(TAG, "startKeepAlive 跳过：「前台服务保活」未开启")
                return
            }
            val intent = Intent().setClassName(context.packageName, SERVICE_CLASS_NAME)
                // 顺带下发开关快照：:ufi_notify 读不到主进程刚写的开关（见 NotifyPrefs）
                .putExtra(NotifyDispatchReceiver.EXTRA_SWITCH_SNAPSHOT, NotifyPrefs.snapshot(context))
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // 后台 FGS 启动受限（Android 12+/14+）或服务未声明：静默失败
                DebugLog.w(TAG, "startKeepAlive 失败: ${e.message}")
            }
        }

        /**
         * 停止通知进程前台保活服务（仅停止 FGS，周期 Worker 仍由 WorkManager 负责）——
         * **关闭方向的收口点**。
         *
         * 两步都必要：
         * 1. [NotifyDispatchReceiver.dispatchSwitchSnapshot]：把最新开关快照落进 `:ufi_notify`
         *    的 `mirror_` 副本。那个接收器在 Manifest 里声明于该进程，**进程不在线也会被系统拉起**，
         *    这就堵住了唯一的「关掉之后又自己回来」窗口 —— 用户在通知进程已被杀的时候关开关，
         *    镜像里留着上次的 `true`，之后 START_STICKY 重建 / 别的入口启动时自查会通过，
         *    常驻通知复活（`stopService` 对一个不存在的服务是 no-op，管不到这件事）；
         * 2. `stopService`：进程在线时立刻收掉服务（若正被 `BIND_AUTO_CREATE` 绑着，
         *    真正撤通知的是实例方法 [setForegroundKeepAlive]，调用方两个都要走）。
         */
        fun stopKeepAlive(context: Context) {
            NotifyDispatchReceiver.dispatchSwitchSnapshot(context)
            val intent = Intent().setClassName(context.packageName, SERVICE_CLASS_NAME)
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                DebugLog.w(TAG, "stopKeepAlive 失败: ${e.message}")
            }
        }
    }
}
