// TODO(F25): God-class 计划内拆分（DownloadManager 1094 / GoformClient 615 / Aria2Engine 577 / BackendService 570 / NetworkModule 921 / FileManagerScreen 879）。本类仅做最小安全抽取（见 GoformCodec），全量拆分需人工评审 + 编译验证。
package com.ufi_axis_core.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.ufi_axis_core.core.scheduler.DataScheduler
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.GoformQoS
import com.ufi_axis_core.util.ShellExecutor
import com.ufi_axis_core.util.ShellQoS
import kotlinx.coroutines.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

/**
 * 后端前台服务
 *
 * 启动流程:
 * 1. 初始化数据库
 * 2. 探测 AT 通道
 * 3. 启动 HTTP Server (:8088)
 * 4. 启动数据采集调度器
 * 5. 启动告警引擎
 *
 * Magisk 自启动 & 进程管理，零保活
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackendService : Service() {

    private val tag = "BackendService"
    private val serviceScope = CoroutineScope(Dispatchers.IO.limitedParallelism(8) + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        AppLogger.e(tag, "BackendService coroutine exception (uncaught)", e)
    })

    /**
     * SMS 轮询独立 scope：使用普通 IO（无 parallelism 限制），
     * 避免 while(isActive) 永久循环占用 limitedParallelism(8) 的稀缺线程。
     */
    private val smsPollScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "ufi_axis_core_service_v2"
        const val NOTIFICATION_ID = 1
        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val MAX_CRASH_RETRY = 3

        /**
         * SMS 转发兜底轮询间隔：5 分钟。
         *
         * 正常路径是 ContentObserver 即时触发（见 registerSmsObserver），这条只负责兜住
         * "短信不进系统 Provider（纯 goform 设备）" 和 "observer 漏回调" 两种情况，
         * 所以间隔可以放得很松 —— 原实现 60s 一次的周期唤醒纯属浪费电。
         */
        private const val SMS_FALLBACK_POLL_MS = 5 * 60 * 1000L

        /**
         * "算新短信" 的最大回溯年龄：24 小时。
         *
         * 2026-08-31 改：这里原来是"轮询间隔 + 1 分钟"的新鲜度窗口，与 **内存版** 判重 id 配套。
         * 但内存 id 一重启就没，窗口就成了唯一闸门，于是任何"发现得晚"的短信（CPU 深睡期间
         * 到达、几分钟后才被处理）都被判成历史短信丢掉。判重 id 已持久化
         * （[com.ufi_axis_core.controller.sms.SmsForwardController.lastForwardedSmsId]），
         * 这条只剩一个作用：换 id 体系 / 首次装机时别把收件箱里的老短信补发一遍，所以放到 24h。
         */
        private const val SMS_MAX_AGE_MS = 24 * 60 * 60 * 1000L


        @Volatile
        var isRunning: Boolean = false

        /**
         * 用户**主动**停止（MainActivity 的「停止服务」按钮）。
         *
         * 2026-08-30 修：`onDestroy` 原来无条件 `scheduleServiceRestart()`，于是主动停止
         * 10 秒后闹钟又把服务拉起来 —— 表现就是「点了停止，goform 采集照跑」。
         * 有了这个标记，主动停止走「取消闹钟 + 落停止标记文件」，被杀 / 重启仍走原来的兜底。
         *
         * 放在 companion 而不是实例上：`stop()` 是静态入口，且 Service 实例在 onDestroy 后即销毁。
         */
        @Volatile
        private var explicitStop: Boolean = false

        /**
         * 主动停止标记文件，给 shell 看门狗（assets/shell/ufi_keepalive.sh）看。
         *
         * 看门狗判的是**进程**存活，主动停止后进程通常还在，所以多数情况它不会介入；
         * 但进程随后被系统回收时它就会把服务拉回来。落一个文件让它跳过，
         * 下次正常启动（onStartCommand）再删掉。
         */
        private const val STOP_FLAG_PATH = "/data/local/tmp/ufi_core_stopped"


        /** 本次服务启动时刻（`SystemClock.elapsedRealtime()`），供 `/api/service/status` 回报运行时长。 */
        @Volatile
        private var startedAtElapsed: Long = 0L

        /** 已运行时长（毫秒）；服务未运行时返回 0。 */
        fun uptimeMs(): Long =
            if (isRunning && startedAtElapsed > 0L) SystemClock.elapsedRealtime() - startedAtElapsed else 0L

        /**
         * 重启后端服务（`POST /api/service/restart` 的落地实现）。
         * `stopService` → `onDestroy` → `scheduleServiceRestart()`（AlarmManager，[RESTART_DELAY_MS] 后
         * 以**前台服务**语义拉起，另有一枚 3 倍延迟的备份闹钟），期间 HTTP 不可用。
         *
         * 口径澄清（别再写成"进程级重启"）：`stopService` 只销毁 Service 组件，**进程通常仍存活**，
         * 因此 `object` 单例与静态状态不会被清掉；也因此 shell 看门狗（判的是 `pgrep` 进程存活）
         * **在这条路径上不会介入**，恢复依赖上面的闹钟。
         */
        fun requestRestart(context: Context) {
            AppLogger.w("BackendService", "Full restart requested — stopping service, AlarmManager will bring it back")
            // 不能走 stop()：那会置 explicitStop，onDestroy 就不排重启闹钟了，服务再也回不来。
            context.stopService(Intent(context, BackendService::class.java))
        }



        // 启动失败计数（防止 START_STICKY + init 失败 = 无限重启循环）
        val crashRetryCount = AtomicInteger(0)

        private const val ACTION_RESTART = "com.ufi_axis_core.ACTION_RESTART"
        private const val RESTART_DELAY_MS = 10_000L    // 被杀后 10 秒重启
        /**
         * 组件图初始化的硬上限。超时即排重启闹钟并杀进程 —— build() 里全是阻塞调用，
         * 协程取消对它们无效，只有换一个进程才能真正恢复。
         * 正常初始化个位数秒级，2 分钟足够覆盖首次安装解压 13MB 资产 + shell 探测。
         */
        private const val INIT_TIMEOUT_MS = 120_000L
        private const val WAKELOCK_TAG = "UfiAxisCore::KeepAlive"
        private const val WAKELOCK_RENEW_MS = 120_000L  // 动态续期间隔：采集活跃时持续续期，idle 时自然过期

        /**
         * 邮件投递专用 WakeLock（与 [WAKELOCK_TAG] 的保活锁完全独立）。
         *
         * 保活锁只在**前端连接时**才续期（见 `DataScheduler` 的 wakeLockRenewJob），前端断开
         * 120s 后它就过期、CPU 允许深睡 —— 这正是"必须连上前端才收到邮件"的根因：短信到达时
         * RIL 唤醒了 CPU，但那把锁在系统广播结束就放掉，而我们的读库 + SMTP 握手是异步的，
         * 常常在中途被挂起，等下一次 CPU 被唤醒（往往就是前端连上来）才继续。
         * 所以邮件链路必须自己持锁，且只在**有事件发生且邮件功能可用**时持有，平时照旧省电。
         */
        private const val MAIL_WAKELOCK_TAG = "UfiAxisCore::MailSend"
        /** 单次投递的持锁上限：SMTP 连接/读超时各 10s，60s 足够覆盖握手 + 重试，超时自动释放兜住泄漏。 */
        private const val MAIL_WAKELOCK_TIMEOUT_MS = 60_000L


        fun start(context: Context) {
            // minSdk=31, startForegroundService 始终可用
            context.startForegroundService(Intent(context, BackendService::class.java))
        }

        fun stop(context: Context) {
            // 先置标记再 stopService：onDestroy 靠它决定「不排重启闹钟」。
            explicitStop = true
            context.stopService(Intent(context, BackendService::class.java))
        }

        /**
         * 当前存活的 Service 实例（仅供后台总闸使用）。
         *
         * `:core:api` 不能反向依赖 `:core`，所以 `/api/service/{start,stop}` 只能靠这个静态入口
         * 回调到实例方法（与 [requestRestart] / [uptimeMs] 同一套路）。
         */
        @Volatile
        private var instance: BackendService? = null

        /**
         * 后台服务总闸（`POST /api/service/start|stop` 的落地实现）。
         *
         * 语义：**除 HTTP 服务之外**的所有自主活动全停 —— 采集调度、告警、定时任务、
         * 短信转发（observer + 兜底轮询）、Samba socket 保活、电池事件、下载后台轮询与隧道看护。
         * HTTP 必须留着，否则用户再也没法远程把服务开回来。
         * 见 [setBackgroundServicesEnabled]。
         */
        fun applyBackgroundServices(enabled: Boolean) {
            instance?.setBackgroundServicesEnabled(enabled)
        }


    }

    // 组件图（由 ComponentFactory 构建）
    private var graph: ComponentGraph? = null
    // 系统级组件引用（用于 destroy 和启动动作）
    private var batteryNotifier: BatteryNotifier? = null
    // 保活组件
    private var wakeLock: PowerManager.WakeLock? = null
    private var oomScoreProtected = false

    // ── SMS 转发（事件驱动，见 registerSmsObserver / forwardLatestSmsIfNew）──
    /** 短信库变更观察者；onDestroy 时必须反注册，否则 Service 重建会叠加多个 observer。 */
    private var smsObserver: ContentObserver? = null
    /** observer 与兜底轮询可能同时进来；串行化以免同一条短信被两条路径各发一封。 */
    private val smsForwardMutex = Mutex()

    // ── 后台服务总闸（POST /api/service/start|stop）──
    /** Samba socket 保活循环（60s / 失败退避 300s）。 */
    private var sambaKeepAliveJob: Job? = null
    /**
     * shell 看门狗自愈复查循环（5 分钟一次，见 [ensureKeepAliveWatchdog]）。
     * **不受后台服务总闸控制** —— 用户点"停止服务"停的是采集等自主活动，
     * 而看门狗是"Core 别死"的最后一道保险，它自己会读 stop-flag 决定要不要拉起。
     */
    private var keepAliveWatchdogJob: Job? = null
    /** 短信转发：observer 注册 + 5 分钟兜底轮询。 */
    private var smsForwardJob: Job? = null
    /** 电池广播是否已注册（重复 unregister 会抛，重复 register 会叠加回调）。 */
    private var batteryRegistered: Boolean = false
    /**
     * 总闸当前状态。初值 true 只是"未表态"，真正的初值在 initializeComponents 里按
     * 持久化的 `AppSettings.backgroundServiceEnabled` 决定 —— 用户上次停掉的服务，
     * 进程重启后必须保持停止。
     */
    @Volatile
    private var backgroundActive: Boolean = true


    /**
     * 取一把邮件投递专用 WakeLock（见 [MAIL_WAKELOCK_TAG]）。失败返回 null，不影响主流程。
     *
     * 每次投递新建一把：`setReferenceCounted(false)` + 超时兜底，调用方 finally 释放。
     */
    private fun acquireMailWakeLock(): PowerManager.WakeLock? = try {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, MAIL_WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(MAIL_WAKELOCK_TIMEOUT_MS)
        }
    } catch (e: Exception) {
        AppLogger.w(tag, "邮件 WakeLock 获取失败（投递可能被深睡打断）: ${e.message}")
        null
    }

    private fun releaseMailWakeLock(lock: PowerManager.WakeLock?) {
        try {
            if (lock != null && lock.isHeld) lock.release()
        } catch (e: Exception) {
            AppLogger.w(tag, "邮件 WakeLock 释放失败: ${e.message}")
        }
    }

    /**
     * 事件驱动的邮件投递统一入口（电池等非短信事件用）。
     *
     * 先判邮件功能是否可用 —— 不可用直接返回，一把锁都不取、平时完全不影响省电；
     * 可用才持 [MAIL_WAKELOCK_TAG] 跑完 SMTP，避免 CPU 在握手中途深睡把投递挂死。
     */
    private fun launchMailEvent(source: String, block: suspend () -> Unit) {
        val ctl = graph?.controller?.smsForwardController ?: return
        if (!ctl.isSendable()) return
        val lock = acquireMailWakeLock()
        serviceScope.launch {
            try {
                block()
            } catch (e: Exception) {
                AppLogger.w(tag, "$source mail forward failed: ${e.message}")
            } finally {
                releaseMailWakeLock(lock)
            }
        }
    }


    /**
     * 注册系统短信库变更观察者：来了新短信立刻触发 [onNewSms]（亚秒级），取代 60s 轮询。
     *
     * - 用 `notifyForDescendants = true`：写入通常落在 `content://sms/inbox/{id}` 这样的子 URI 上，
     *   只监听根 URI 会收不到。
     * - 回调在主线程 Looper，不能做 IO，因此立刻转投 [smsPollScope]。
     * - 系统写一条短信可能触发多次 onChange；[forwardLatestSmsIfNew] 用 id 去重兜住。
     * - 注册失败（无 READ_SMS 权限或设备没有短信 Provider）不算致命：兜底轮询仍在跑。
     *
     * @param isMailEnabled 邮件功能是否可用（配置齐全且开启）。**在回调里同步判一次**：
     *   只有为 true 才持锁，否则平时保持省电、一把锁都不取。
     */
    private fun registerSmsObserver(isMailEnabled: () -> Boolean, onNewSms: suspend () -> Unit) {
        if (smsObserver != null) return
        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                    // 锁必须在**转投协程之前**取：onChange 之后系统那把广播锁随时会放掉，
                    // 后面的 delay + Cursor 查询 + SMTP 握手全在异步链路上，没锁就会被深睡截断。
                    val lock = if (isMailEnabled()) acquireMailWakeLock() else null
                    smsPollScope.launch {
                        try {
                            // 等一下再读：onChange 可能早于行完全可见（尤其 body 还在补写时）
                            delay(300L)
                            onNewSms()
                        } finally {
                            releaseMailWakeLock(lock)
                        }
                    }
                }
            }
            contentResolver.registerContentObserver(
                android.provider.Telephony.Sms.CONTENT_URI, true, observer
            )
            smsObserver = observer
            AppLogger.i(tag, "SMS ContentObserver registered (event-driven forward)")
        } catch (e: Exception) {
            AppLogger.w(tag, "SMS ContentObserver 注册失败，仅依赖兜底轮询: ${e.message}")
        }
    }

    /**
     * 读取最新一条短信，若是没转发过的新消息则立即转发。
     *
     * 三道闸：
     * - 配置可发信（[com.ufi_axis_core.controller.sms.SmsForwardController.isSendable]）：
     *   不可发就直接退出，绝不为此做任何 IO / 持锁；
     * - id 去重（持久化的 `lastForwardedSmsId`）：同一条只发一次，服务重启也认；
     * - `age <= `[SMS_MAX_AGE_MS]：首次装机 / 换 id 体系时别把老短信补发一遍。
     *
     * @param source 仅用于日志区分触发来源（observer / poll）。
     */
    private suspend fun forwardLatestSmsIfNew(
        forwardCtl: com.ufi_axis_core.controller.sms.SmsForwardController,
        smsCtl: com.ufi_axis_core.controller.sms.SmsController,
        source: String
    ) {
        smsForwardMutex.withLock {
            try {
                if (!forwardCtl.isSendable()) return
                // fallbackOnEmpty：纯 goform 设备的短信不进系统 Provider，Provider 读到的是空列表
                // （不是"不可用"），不允许空列表兜底就等于邮件功能在这类设备上完全不工作。
                val latest = smsCtl.getLatest(fallbackOnEmpty = true) ?: return
                val lastId = forwardCtl.lastForwardedSmsId
                if (latest.id == lastId) return
                val age = System.currentTimeMillis() - latest.date
                if (lastId < 0L || age > SMS_MAX_AGE_MS || latest.direction != "received") {
                    // 首次落基线 / 历史短信 / 自己发出去的短信：只记 id 不发信
                    forwardCtl.lastForwardedSmsId = latest.id
                    return
                }
                AppLogger.i(tag, "New SMS #${latest.id} from ${latest.address} via $source: ${latest.body.take(50)}")
                forwardCtl.lastForwardedSmsId = latest.id
                val ok = forwardCtl.forwardSms(latest.address, latest.body, latest.date)
                AppLogger.i(tag, "Forward result: ${if (ok) "success" else "failed"}")
            } catch (e: Exception) {
                AppLogger.w(tag, "SMS forward ($source) error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }



    override fun onCreate() {
        super.onCreate()
        AppLogger.i(tag, "Service creating...")
        instance = this


        createNotificationChannel()

        // Android 14 (API 34) 要求显式声明 foregroundServiceType
        val notification = createNotification("UFI-AXIS-Core 正在启动...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // ── 保活增强 ──
        protectFromLmk()
        acquireWakeLock()

        // 2026-08-21：ensureKeepAliveWatchdog() 移至 initializeComponents() 中
        // ADB 特权通道初始化之后，避免 AdbShellExecutor 未就绪时 ShellExecutor.executeAsRoot
        // fallback 到 app UID 的 ProcessBuilder，无法写入 /data/local/tmp/（鸡生蛋问题修复）。
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 又被启动了 → 之前那次主动停止作废（含 shell 看门狗的停止标记）
        explicitStop = false
        clearStopFlag()

        // AlarmManager 拉起重启
        if (intent?.action == ACTION_RESTART) {
            AppLogger.w(tag, "Service restarted by AlarmManager")
            crashRetryCount.set(0)
        }

        if (isRunning) {
            AppLogger.i(tag, "Service already running, ignoring duplicate start")
            return START_STICKY
        }
        AppLogger.i(tag, "Service starting...")

        // ── 自动授予运行时权限（best-effort，fire-and-forget，避免主线程阻塞）──
        // pm grant 通过 ProcessBuilder 同步执行，waitFor(5s)×3 最多阻塞 15s，
        // 在主线程会直接触发 ANR，故移入独立 IO 协程，不阻塞生命周期回调。
        // 权限授予失败不影响服务其余功能（用户可经 MainActivity 手动授权）。
        serviceScope.launch(Dispatchers.IO) {
            ensureRuntimePermissions()
        }

        // isRunning / startedAtElapsed **同步**置位：原来放在 serviceScope.launch 里，
        // 于是 AlarmManager(10s) 与 keepalive 看门狗两次 startService 有机会都通过上面的
        // `if (isRunning)` 守卫 → ComponentFactory 走 "already built → reset 重建" 分支，
        // 出现两套组件图 / 两次端口 bind，失败一路会 stopSelf() 把健康那路一起停掉。
        isRunning = true
        startedAtElapsed = SystemClock.elapsedRealtime()
        serviceScope.launch {
            initializeComponents()
        }

        return START_STICKY  // 系统杀死后自动重启
    }

    /**
     * 检查并尝试自授运行时权限。
     * - 如果 app 已安装为系统应用（Magisk module），pm grant 会以 system UID 成功。
     * - 如果 app 是普通用户应用（adb install），pm grant 会失败并记录警告，
     *   用户需要通过 MainActivity 手动授权。
     */
    private fun ensureRuntimePermissions() {
        val required = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE,
        )
        val missing = required.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            AppLogger.d(tag, "All runtime permissions already granted")
            return
        }
        AppLogger.i(tag, "Missing permissions: $missing — attempting pm grant")
        for (perm in missing) {
            try {
                val proc = ProcessBuilder("pm", "grant", packageName, perm)
                    .redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().readText()
                val exited = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (exited && proc.exitValue() == 0) {
                    AppLogger.i(tag, "pm grant $perm → OK")
                } else {
                    AppLogger.w(tag, "pm grant $perm → failed (exit=${if (exited) proc.exitValue() else "timeout"}): $output")
                }
                proc.destroyForcibly()
            } catch (e: Exception) {
                AppLogger.w(tag, "pm grant $perm → exception: ${e.message}")
            }
        }
        // 再次检查并记录最终状态
        val stillMissing = missing.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (stillMissing.isNotEmpty()) {
            AppLogger.w(tag, "Permissions still missing after pm grant: $stillMissing — signal data will be incomplete. Open the app UI to grant manually.")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户从最近任务滑动清除时触发。
     * START_STICKY 在某些 ROM 上不可靠，用 AlarmManager 兜底拉起。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLogger.w(tag, "Task removed, scheduling restart in ${RESTART_DELAY_MS / 1000}s")
        scheduleServiceRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        AppLogger.i(tag, "Service destroying...")
        // 清理操作（含 Netty event loop 关闭后的 delay(2000)）放入独立后台作用域，
        // 不阻塞主线程（runBlocking 会卡主线程 ~2s → 系统不响应/ANR），
        // 且使用独立 CoroutineScope 而非 serviceScope，避免被下方 serviceScope.cancel()
        // 取消导致清理未完成。onDestroy 必须尽快返回，故 fire-and-forget。
        val cleanupScope = CoroutineScope(Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            AppLogger.e(tag, "Error during destroy cleanup", e)
        })
        cleanupScope.launch {
            stopAllComponents()
        }
        // 取消业务协程作用域（清理已交由独立 scope，互不干扰）
        serviceScope.cancel()
        smsPollScope.cancel()
        // 反注册短信观察者：Service 被杀→拉起会走一遍 onCreate，不反注册会叠加多个 observer，
        // 同一条短信被回调 N 次（虽有 id 去重，但白跑 N 次 Cursor 查询）。
        stopSmsForwarding()
        stopSambaKeepAlive()
        instance = null
        releaseWakeLock()
        // 主动停止（MainActivity「停止服务」/ BackendService.stop）：不排重启闹钟，
        // 并且要把**已经排过**的取消掉（onTaskRemoved 可能已经排了一枚），否则 10 秒后照样回来。
        // 非主动停止（被系统杀 / 崩溃）仍走 AlarmManager 兜底。
        if (explicitStop) {
            AppLogger.i(tag, "Explicit stop — cancelling pending restart alarms, service will stay down")
            cancelScheduledRestart()
            cleanupScope.launch { writeStopFlag() }
        } else {
            scheduleServiceRestart()
        }
        super.onDestroy()
    }

    /**
     * 取消 [scheduleServiceRestart] 排下的两枚闹钟。
     *
     * 必须用 `FLAG_NO_CREATE` 拿同一枚 PendingIntent（requestCode + Intent 必须完全一致），
     * 拿不到说明本来就没排过；取得到就 `AlarmManager.cancel` 再 `PendingIntent.cancel`。
     */
    private fun cancelScheduledRestart() {
        try {
            val intent = Intent(this, BackendService::class.java).apply { action = ACTION_RESTART }
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            for (requestCode in intArrayOf(0, 1)) {
                PendingIntent.getForegroundService(
                    this, requestCode, intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )?.let {
                    alarmManager.cancel(it)
                    it.cancel()
                }
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to cancel restart alarms: ${e.message}")
        }
    }

    /** 落主动停止标记，让 shell 看门狗跳过拉起（见 [STOP_FLAG_PATH]）。 */
    private suspend fun writeStopFlag() {
        try {
            ShellExecutor.executeAsRoot("touch $STOP_FLAG_PATH", timeoutMs = 5_000)
        } catch (e: Exception) {
            AppLogger.w(tag, "写停止标记失败（看门狗可能会重新拉起）: ${e.message}")
        }
    }

    /** 清除主动停止标记；服务正常启动时调用。 */
    private fun clearStopFlag() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                ShellExecutor.executeAsRoot("rm -f $STOP_FLAG_PATH", timeoutMs = 5_000)
            } catch (_: Exception) {}
        }
    }


    /**
     * 内存压力响应 — 主动释放缓存，降低被 LMK 选中的概率。
     * 前台服务通常不会收到 TRIM_MEMORY_COMPLETE，但在极端 OOM 场景仍可能触发。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                AppLogger.w(tag, "TRIM_MEMORY_RUNNING_CRITICAL — 主动释放全部缓存")
                graph?.storage?.responseCache?.clear()
                GoformQoS.clearCache()
                ShellQoS.clearCache()
                System.gc()
            }
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                AppLogger.w(tag, "TRIM_MEMORY_RUNNING_LOW — 释放 HTTP 响应缓存")
                graph?.storage?.responseCache?.clear()
                GoformQoS.clearCache()
            }
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                AppLogger.w(tag, "TRIM_MEMORY_BACKGROUND — 释放缓存 + GC")
                graph?.storage?.responseCache?.clear()
                GoformQoS.clearCache()
                ShellQoS.clearCache()
                System.gc()
            }
        }
    }

    private suspend fun initializeComponents() {
        try {
            AppLogger.i(tag, "Initializing components...")
            updateNotification("正在初始化组件...")

            // 初始化看门狗：build() 全程是阻塞调用（解压资产、shell 探测网关、二进制 --version、
            // 特权 shell 迁移），任何一处卡住此前都没人发现 —— 通知栏永远停在"正在初始化组件..."，
            // HTTP 端口不监听，而 keepalive 脚本只检查进程存活，不会介入。
            // 心跳把已耗时写进通知栏与日志（便于定位卡在哪一步），超时则重启进程自愈。
            val initStart = SystemClock.elapsedRealtime()
            val initWatchdog = serviceScope.launch {
                while (isActive) {
                    delay(15_000)
                    val elapsed = SystemClock.elapsedRealtime() - initStart
                    if (elapsed >= INIT_TIMEOUT_MS) {
                        AppLogger.e(tag, "component init timed out after ${elapsed / 1000}s — killing process to recover")
                        updateNotification("初始化超时，正在重启服务…")
                        scheduleServiceRestart()
                        android.os.Process.killProcess(android.os.Process.myPid())
                        return@launch
                    }
                    AppLogger.w(tag, "still initializing components (${elapsed / 1000}s elapsed)")
                    updateNotification("正在初始化组件…（已 ${elapsed / 1000}s）")
                }
            }
            val gatewayIp = getDeviceGatewayIp()
            val g = try {
                ComponentFactory.build(this, gatewayIp, wakeLockRenew = ::renewWakeLock)
            } finally {
                initWatchdog.cancel()
            }
            graph = g

            // ── Web 前端版本检查（在 HttpServer 启动前执行，此时无 HTTP 请求进来，无竞态）──
            // 判据是内置 web 的 version（不是 buildTime，那个每次构建都变，会把用户上传的
            // override 一重启就删掉）。读不到版本时返回 null —— 此时**不要覆写基线**，
            // 否则基线被擦掉，下次启动会误判成"版本变了"再删一次 override。
            try {
                val webMgr = g.serverGraph.webResourceManager
                val settings = g.storage.settings
                webMgr.cleanupStaging()
                val bundledWebVersion = webMgr.checkAndClearStaleOverride(settings.lastSeenBundledWebVersion)
                if (bundledWebVersion != null) {
                    settings.lastSeenBundledWebVersion = bundledWebVersion
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "Web version check failed (non-fatal): ${e.message}")
            }


            // ── 关键修复：优先启动 HTTP 服务，让所有接口（含 /api/device/info）尽快可用 ──
            // 原实现把 ADB / Samba / 看门狗等阻塞式系统初始化排在 server.start() 之前，
            // 一旦特权 shell 命令变慢或卡住，HTTP 端口迟迟不监听，前端一直“加载中”且无数据。
            // 现在组件图构建完成即启动服务，系统级初始化放后台，绝不再阻塞 HTTP 服务。
            // 方案 A（2026-08-24）：Core 启动即无条件启动冷数据采集（月流量/电池/SMS/信号等），
            // 与前端是否连接解耦——避免前端（重）连接间隙或进程重启后 goform 数据永久归零
            // （见 2026-08-24 goform 掉线复盘：11:38 进程重启后采集循环因前端未连而从未重启）。
            // 实时 WebSocket 推送的启停由 ComponentFactory 的 WS 连接回调（applyRealtimePushState）单独控制。
            // 用户显式停掉的后台采集在服务重启后必须保持停止 —— 开关真源是持久化的
            // `AppSettings.backgroundServiceEnabled`（默认 true，即保持"启动即采集"的既有行为）。
            // 注意：setMonitorEnabled 内部已经调用 applyColdCollectionState()，此处不要再调一次
            // （重复调用会多开一次 start/stop 窗口，无益且放大竞态）。
            g.serverGraph.dataScheduler.setMonitorEnabled(
                AppSettings.getInstance(this@BackendService).backgroundServiceEnabled
            )
            // 2026-09-03：「停止服务」= 除 HTTP 之外全停。除采集调度外，定时任务 / 隧道看护 /
            // 下载后台轮询都在各自 init 里就起来了（ComponentFactory.build 期间），
            // 所以关闸状态下必须在这里补一刀把它们按下去，否则重启一轮就自己复活。
            backgroundActive = AppSettings.getInstance(this@BackendService).backgroundServiceEnabled
            if (!backgroundActive) {
                AppLogger.w(tag, "backgroundServiceEnabled=false — 后台自主活动保持停止（仅 HTTP 服务可用）")
                pauseComponentBackground(g)
            }

            val serverOk = g.serverGraph.server.start()
            if (!serverOk) {
                // 服务启动失败：不要静默继续（否则端口不监听，外部只能看到 ERR_CONNECTION_REFUSED）。
                // 直接停止并记入失败计数，由 START_STICKY / 看门狗重新拉起，并在 logcat 暴露真实异常。
                AppLogger.e(tag, "HTTP Server failed to start — stopping service to surface failure")
                crashRetryCount.set(0)
                isRunning = false
                g.serverGraph.server.stop()
                stopSelf()
                return
            }
            AppLogger.i(tag, "[14] HTTP server ready")

            // ── 系统关键但非接口前置的初始化：放后台，绝不阻塞 HTTP 服务 ──
            serviceScope.launch(Dispatchers.IO) {
                try {
                    // ADB 特权通道（系统关键，无条件启动）
                    // AdbController.start() 先通过 Goform USB_PORT_SETTING 启用 adbd，
                    // 再初始化 AdbShellExecutor（所有 executeAsRoot 依赖的系统级特权执行通道）。
                    try {
                        val adbOk = g.controller.adbController.start()
                        AppLogger.i(tag, "ADB privileged channel started: $adbOk")
                    } catch (e: Exception) {
                        AppLogger.e(tag, "ADB privileged channel start failed", e)
                    }
                    // ADB 通道就绪后部署 shell 看门狗（确保 ShellExecutor.executeAsRoot 走 ADB shell）
                    ensureKeepAliveWatchdog()
                    // 部署 Samba root shell 基础设施（socat + samba_exec.sh + smb.conf）
                    try {
                        com.ufi_axis_core.util.SambaRootShell.deploy(this@BackendService)
                    } catch (e: Exception) {
                        AppLogger.w(tag, "Samba root shell deploy failed (non-fatal): ${e.message}")
                    }
                } catch (e: Exception) {
                    AppLogger.e(tag, "Background system init failed (non-fatal): ${e.message}", e)
                }
            }

            // 2026-08-21：定期重新触发 Samba 连接（保持 socat socket 存活）
            // 2026-09-03：循环体抽到 startSambaKeepAlive()，受后台服务总闸控制。
            if (backgroundActive) startSambaKeepAlive()



            // ── 电池事件通知（依赖 smsForwardController） ──
            // 走 launchMailEvent：与短信一样，投递期间自己持锁，别指望"前端连着"那把保活锁。
            val notifier = BatteryNotifier(
                onLowBattery = { pct ->
                    launchMailEvent("Battery low") {
                        g.controller.smsForwardController.forwardSms("SYSTEM", "低电量警告: ${pct}%", System.currentTimeMillis())
                    }
                },
                onVeryLowBattery = { pct ->
                    launchMailEvent("Battery very low") {
                        g.controller.smsForwardController.forwardSms("SYSTEM", "极低电量警告: ${pct}%", System.currentTimeMillis())
                    }
                },
                onFullBattery = {
                    launchMailEvent("Battery full") {
                        g.controller.smsForwardController.forwardSms("SYSTEM", "电池已充满", System.currentTimeMillis())
                    }
                },
                onChargeStart = {
                    AppLogger.d(tag, "Charging started")
                }
            )

            batteryNotifier = notifier

            // ── SMS 转发：ContentObserver 事件驱动 + 5 分钟兜底轮询 ──
            // 2026-09-03：循环体抽到 startSmsForwarding()，受后台服务总闸控制。
            if (backgroundActive) startSmsForwarding(g)


            // 注意：HTTP 服务已在组件图构建完成后提前启动（见上方“关键修复”处），
            // 此处不再重复启动；ADB / Samba / 看门狗等系统初始化也已移至后台执行。

            // ── 配对模式（Onboarding）：始终确保配对码存在（多设备模式下不再因已配对而跳过）──
            g.storage.settings.enterPairingMode()

            // ── 注册电池事件监听 ──
            if (backgroundActive) registerBatteryNotifier()


            updateNotification("UFI-AXIS-Core 运行中 (:${g.storage.settings.port})")
            AppLogger.i(tag, "All components initialized successfully. Server listening on :${g.storage.settings.port}")

            // 2026-08-22：会话诊断日志启动标记（Download/UFI-AXIS/log/core/goform-session.log）。
            // 版本 + APK 安装时间可确认部署的构建；该文件若不存在即说明设备上还是旧构建。
            try {
                val pkgInfo = packageManager.getPackageInfo(packageName, 0)
                com.ufi_axis_core.util.GoformSessionLog.logStartupMarker(
                    versionName = pkgInfo.versionName ?: "unknown",
                    lastUpdateTime = pkgInfo.lastUpdateTime
                )
            } catch (_: Exception) {}

            // 重置启动失败计数（成功启动后）
            crashRetryCount.set(0)

        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to initialize components", e)
            updateNotification("UFI-AXIS-Core 启动失败: ${e.message}")

            // 防止 START_STICKY 无限重启循环：连续失败 3 次后主动停止
            val retries = crashRetryCount.incrementAndGet()
            AppLogger.e(tag, "Init failure count: $retries/$MAX_CRASH_RETRY")
            if (retries >= MAX_CRASH_RETRY) {
                AppLogger.e(tag, "Too many init failures, stopping service to prevent crash loop")
                crashRetryCount.set(0)
                isRunning = false
                stopSelf()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 后台服务总闸（POST /api/service/start|stop）
    // ═══════════════════════════════════════════════════════════

    /**
     * 「停止服务 / 启动服务」的落地实现：**除 HTTP 服务之外**的所有自主活动一起停/一起起。
     *
     * 为什么 HTTP 必须留着：它是用户再把服务开回来的唯一通道，停了就把自己锁在门外了
     * （真正的整体停机是 MainActivity 的 `BackendService.stop()` → onDestroy → [stopAllComponents]）。
     *
     * 覆盖范围（与 [stopAllComponents] 的区别是"可逆"）：
     * - 采集调度 DataScheduler（由 `ServiceRoutes.applySwitch` 直接调 setMonitorEnabled，这里不重复）
     * - 告警引擎在途任务、定时任务调度、隧道看护循环、下载后台轮询 + aria2 进程
     * - 短信转发（observer + 5 分钟兜底轮询）、Samba socket 保活、电池事件广播
     *
     * 不动的：HTTP/WS 服务本体、ADB 特权通道（[AdbController] 是所有 root 命令的通道，
     * 停了就没法把服务开回来）、shell 看门狗（它保的是进程存活，进程没停）。
     */
    fun setBackgroundServicesEnabled(enabled: Boolean) {
        if (backgroundActive == enabled) return
        backgroundActive = enabled
        val g = graph
        if (enabled) {
            AppLogger.i(tag, "Background services: START")
            if (g != null) resumeComponentBackground(g)
            startSambaKeepAlive()
            if (g != null) startSmsForwarding(g)
            registerBatteryNotifier()
        } else {
            AppLogger.w(tag, "Background services: STOP (HTTP server keeps running)")
            stopSmsForwarding()
            stopSambaKeepAlive()
            unregisterBatteryNotifier()
            if (g != null) pauseComponentBackground(g)
        }
    }

    /** 停掉组件图里的自主循环（可逆，配 [resumeComponentBackground]）。 */
    private fun pauseComponentBackground(g: ComponentGraph) {
        // 读穿透闸门：客户端的读请求不再因缓存过期去打 goform，只回最后一次已知值。
        // 这是"停止服务后还在登录 goform 后台"的根因 —— 采集停了，但任何一次 REST 读
        // 都是 cache miss，miss 就会重新 ensureLogin()。
        try { g.storage.responseCache.setReadThroughPaused(true) } catch (e: Exception) { AppLogger.w(tag, "cache gate failed: ${e.message}") }
        try { g.serverGraph.alertEngine.stop() } catch (e: Exception) { AppLogger.w(tag, "alertEngine.stop failed: ${e.message}") }
        try { g.controller.taskScheduler.pause() } catch (e: Exception) { AppLogger.w(tag, "taskScheduler.pause failed: ${e.message}") }
        try { g.controller.tunnelManager.stopGuard() } catch (e: Exception) { AppLogger.w(tag, "tunnelManager.stopGuard failed: ${e.message}") }
        try { g.controller.downloadManager.pauseBackground() } catch (e: Exception) { AppLogger.w(tag, "downloadManager.pauseBackground failed: ${e.message}") }
    }

    private fun resumeComponentBackground(g: ComponentGraph) {
        try { g.storage.responseCache.setReadThroughPaused(false) } catch (e: Exception) { AppLogger.w(tag, "cache gate failed: ${e.message}") }
        try { g.controller.taskScheduler.resume() } catch (e: Exception) { AppLogger.w(tag, "taskScheduler.resume failed: ${e.message}") }
        try { g.controller.tunnelManager.startGuard() } catch (e: Exception) { AppLogger.w(tag, "tunnelManager.startGuard failed: ${e.message}") }
        try { g.controller.downloadManager.resumeBackground() } catch (e: Exception) { AppLogger.w(tag, "downloadManager.resumeBackground failed: ${e.message}") }
    }

    /**
     * Samba socket 保活：60s 一轮，连续失败 ≥3 次退到 300s。
     *
     * 2026-08-28：这里原来是 while(true) + delay 在 try 内 + catch(Exception)。
     * CancellationException 是 Exception 子类 → serviceScope 取消后 delay 立刻抛出、
     * 被 catch 吞掉、循环继续，退化成每秒数千次的无延迟死循环，
     * 单日往 app_*.log 写了 304MB（实测 2026-08-23）。
     * 修法三处：循环条件用 isActive、delay 移到 try 外、CancellationException 先 rethrow。
     */
    private fun startSambaKeepAlive() {
        if (sambaKeepAliveJob?.isActive == true) return
        sambaKeepAliveJob = serviceScope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0
            while (isActive) {
                val baseDelay = if (consecutiveFailures >= 3) 300_000L else 60_000L
                delay(baseDelay)
                try {
                    if (!com.ufi_axis_core.util.SambaRootShell.isSocketAvailable()) {
                        AppLogger.d(tag, "Samba socket not available, triggering connection... (failCount=$consecutiveFailures)")
                        com.ufi_axis_core.util.SambaRootShell.triggerSambaConnection()
                        // triggerSambaConnection 内部没有返回值，我们通过下一次循环 checkSocket 判定
                        consecutiveFailures++
                    } else {
                        consecutiveFailures = 0
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w(tag, "Samba periodic trigger error: ${e.message}")
                    consecutiveFailures++
                }
            }
        }
    }

    private fun stopSambaKeepAlive() {
        sambaKeepAliveJob?.cancel()
        sambaKeepAliveJob = null
    }

    /**
     * 短信转发：ContentObserver 事件驱动 + 5 分钟兜底轮询。
     *
     * 2026-08-30：原实现是「每 60s 轮询一次，且 delay 写在循环开头」，于是从收到验证码到
     * 发出邮件平均要等 30s、最坏 60s+，还得再加 SMTP 握手 —— 这是整条链路最大的一段延迟。
     * 现在改成短信库变更即触发：ContentObserver 在系统写入短信那一刻回调（亚秒级），
     * 顺带把 60s 的周期性唤醒去掉，耗电反而更低。
     * 兜底轮询留 5 分钟一次，覆盖两种 observer 靠不住的情况：
     *   ① 短信只存在于 modem（goform）、不进系统 ContentProvider 的设备；
     *   ② observer 因进程被冻结等原因漏掉回调。
     */
    private fun startSmsForwarding(g: ComponentGraph) {
        if (smsForwardJob?.isActive == true) return
        smsForwardJob = smsPollScope.launch {
            val smsForwardCtl = g.controller.smsForwardController
            val smsCtl = com.ufi_axis_core.controller.sms.SmsController(this@BackendService, g.network.smsClient)
            val initCfg = smsForwardCtl.loadConfig()
            AppLogger.i(tag, "SMS forward init: enabled=${initCfg.enabled}, smtpHost=${initCfg.smtpHost.takeIf { it.isNotBlank() } ?: "(empty)"}")

            // 事件驱动：注册到系统短信库
            registerSmsObserver(
                isMailEnabled = { smsForwardCtl.isSendable() },
                onNewSms = { forwardLatestSmsIfNew(smsForwardCtl, smsCtl, "observer") }
            )

            // 启动即查一次（不再先 delay 60s），之后 5 分钟兜底。
            // 注意这条循环是 **机会性** 的：CPU 深睡期间 delay 不会把设备唤醒（这是有意的，
            // 不为兜底轮询排闹钟耗电），醒着的时候才补一次。及时性靠上面的 observer 保证。
            while (isActive) {
                val lock = if (smsForwardCtl.isSendable()) acquireMailWakeLock() else null
                try {
                    forwardLatestSmsIfNew(smsForwardCtl, smsCtl, "poll")
                } finally {
                    releaseMailWakeLock(lock)
                }
                delay(SMS_FALLBACK_POLL_MS)
            }
        }
    }

    /** 停轮询 + 反注册 observer；两条路径都要断，否则短信照旧触发转发。 */
    private fun stopSmsForwarding() {
        smsForwardJob?.cancel()
        smsForwardJob = null
        try {
            smsObserver?.let { contentResolver.unregisterContentObserver(it) }
        } catch (e: Exception) {
            AppLogger.w(tag, "SMS observer 反注册失败: ${e.message}")
        }
        smsObserver = null
    }

    private fun registerBatteryNotifier() {
        val notifier = batteryNotifier ?: return
        if (batteryRegistered) return
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 2026-09-06：这里原来传 RECEIVER_NOT_EXPORTED，导致注册瞬间的 sticky 回放被系统丢弃：
                //   W BroadcastQueue: Exported Denial: sending Intent { act=...BATTERY_CHANGED },
                //     from null (uid=-1) due to receiver ...ufi_axis_core... not specifying RECEIVER_EXPORTED
                // sticky 广播回放时 callingUid 是 -1（不是 SYSTEM_UID），过不了 NOT_EXPORTED 的同应用校验。
                // BATTERY_CHANGED 属于系统 protected broadcast，三方应用发不出来，用 EXPORTED 不引入攻击面。
                registerReceiver(notifier, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(notifier, filter)
            }
            batteryRegistered = true
            AppLogger.i(tag, "Battery notifier registered")
        } catch (e: Exception) {
            AppLogger.w(tag, "Battery notifier registration failed: ${e.message}")
        }
    }

    private fun unregisterBatteryNotifier() {
        if (!batteryRegistered) return
        try {
            batteryNotifier?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            AppLogger.w(tag, "Battery notifier unregister failed: ${e.message}")
        }
        batteryRegistered = false
    }

    private suspend fun stopAllComponents() {
        AppLogger.i(tag, "Stopping all components...")
        unregisterBatteryNotifier()
        val g = graph ?: return
        // ① 先停**数据生产者**。2026-08-30 修：原顺序是 server.stop() → delay(2000) →
        // dataScheduler.stop()，于是"停止服务"后 DataScheduler 还会继续采集整整 2 秒 ——
        // 设备侧看到的就是"点了停止，goform 请求还在打"。生产者必须最先停。
        try { g.serverGraph.dataScheduler.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping scheduler", e) }
        try { g.serverGraph.alertEngine.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping alert engine", e) }
        try { g.controller.taskScheduler.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping task scheduler", e) }
        // ② 再断开对外通道（WS 先关，避免 server 停了还有连接在等推送）
        try { g.serverGraph.wsManager.closeAll() } catch (e: Exception) { AppLogger.e(tag, "Error closing websocket", e) }
        try { g.serverGraph.server.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping server", e) }
        // Netty eventLoopGroup 异步关闭，需等待足够时间让 event loop 线程完成清理。
        // 过短会导致 CompletionHandlerException（event loop 线程的完成处理器引用已取消的协程）。
        try { delay(2000) } catch (_: Exception) {}
        // ③ 最后关设备侧连接与其余组件（此时已无人再发请求）
        try { g.network.goformClient.close() } catch (e: Exception) { AppLogger.e(tag, "Error closing goform client", e) }
        try { g.controller.adbController.destroy() } catch (e: Exception) { AppLogger.e(tag, "Error stopping adb", e) }
        try { g.controller.downloadManager.shutdown() } catch (e: Exception) { AppLogger.e(tag, "Error shutting down download manager", e) }
        try { g.controller.tunnelManager.shutdown() } catch (e: Exception) { AppLogger.e(tag, "Error shutting down tunnel manager", e) }
        // 关闭 ADB shell 持久会话
        try { com.ufi_axis_core.util.AdbShellExecutor.shutdown() } catch (e: Exception) { AppLogger.e(tag, "Error shutting down ADB shell", e) }
        // 重置 ComponentFactory 构建状态，允许服务重启时重新 build
        com.ufi_axis_core.service.ComponentFactory.reset()
    }

    // ═══════════════════════════════════════════════════════════
    // 保活增强
    // ═══════════════════════════════════════════════════════════

    /**
     * 保活增强：提升线程优先级。
     * 不再写 /proc/self/oom_score_adj（需要 root）。
     * 依赖前台服务 + START_STICKY + WakeLock 保活。
     */
    private fun protectFromLmk() {
        if (oomScoreProtected) return
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            oomScoreProtected = true
            AppLogger.i(tag, "LMK protection: thread priority set to FOREGROUND")
        } catch (_: Exception) {}
    }

    /**
     * 获取部分 WakeLock — 初始获取，120s 后自动过期。
     * 部分 WakeLock 不亮屏，仅阻止 CPU 进入深度休眠。
     * 通过 renewWakeLock() 在采集活跃期持续续期，idle 时自然过期允许 CPU 深度休眠。
     */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_RENEW_MS)
            }
            AppLogger.i(tag, "WakeLock acquired ($WAKELOCK_TAG, ${WAKELOCK_RENEW_MS / 1000}s)")
        } catch (e: Exception) {
            AppLogger.w(tag, "WakeLock acquire failed: ${e.message}")
        }
    }

    /**
     * 续期 WakeLock — 由 DataScheduler 每次采集循环调用。
     * 采集活跃时持续续期保持 CPU 唤醒；idle 期间（热节流/前端断开/长自适应延迟）
     * WakeLock 自然过期，CPU 可进入深度休眠，降低功耗和发热。
     */
    fun renewWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.acquire(WAKELOCK_RENEW_MS)
                } else {
                    it.acquire(WAKELOCK_RENEW_MS)
                    AppLogger.d(tag, "WakeLock re-acquired after expiry")
                }
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "WakeLock renew failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            AppLogger.i(tag, "WakeLock released")
        } catch (e: Exception) {
            AppLogger.w(tag, "WakeLock release failed: ${e.message}")
        }
    }

    /**
     * 通过 AlarmManager 定时拉起重服务 — 比 START_STICKY 更可靠。
     * 覆盖场景：用户滑动清除、LMK 杀死、系统回收后重启、`POST /api/service/restart`。
     * 使用 setAndAllowWhileIdle 无需 SCHEDULE_EXACT_ALARM 权限。
     *
     * 2026-08-26：改用 `getForegroundService`。原来的 `getService` 走的是普通 `startService`，
     * Android 12+ 禁止后台启动普通服务（`ForegroundServiceStartNotAllowedException` / 直接被拒），
     * 而本服务在 `onCreate` 里就 `startForeground`，属于前台服务，必须用前台启动语义
     * ——否则 `/api/service/restart` 之后可能起不来，而看门狗判的是"进程存活"（进程通常还在），
     * 不会介入，等于把客户端锁在门外。
     */
    private fun scheduleServiceRestart() {
        try {
            val intent = Intent(this, BackendService::class.java).apply {
                action = ACTION_RESTART
            }
            val pendingIntent = PendingIntent.getForegroundService(
                this, 0, intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
                pendingIntent
            )
            // 备份闹钟（不同 requestCode，否则会覆盖上面那枚）：主闹钟被系统吞掉时兜底。
            // 重复拉起是安全的 —— onStartCommand 的 isRunning 守卫已改为同步置位。
            val backupIntent = PendingIntent.getForegroundService(
                this, 1, intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS * 3,
                backupIntent
            )
            AppLogger.i(tag, "Service restart scheduled in ${RESTART_DELAY_MS / 1000}s via AlarmManager (backup at ${RESTART_DELAY_MS * 3 / 1000}s)")
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to schedule restart: ${e.message}")
        }
    }

    /**
     * 部署并启动独立 shell 看门狗（assets/shell/ufi_keepalive.sh），并启动**自愈复查**。
     *
     * 与前台服务/AlarmManager 形成双保险：
     * - 看门狗是独立进程，即使 Core 主进程被 LMK 整体回收，它仍可在 /data/local/tmp 上
     *   持续检测并在 15 秒内通过 `am start-foreground-service` 重新拉起 BackendService；
     * - 检测到 /data/local/tmp/ufi_update.lock 时自动跳过，避免与更新脚本互抢导致安装失败。
     *
     * 2026-09-04 两处改动：
     * 1. 判定与部署收敛到 [KeepAliveWatchdog] —— 原来这里和 `BootReceiver` 各写一份，
     *    且都把「锁目录存在」当成「看门狗在跑」，而锁在 SIGKILL/掉电后残留，
     *    看门狗被杀一次就再也起不来（日志里还写着 already running）。
     * 2. 加 [KeepAliveWatchdog.SELF_HEAL_INTERVAL_MS] 的复查：**谁来看着看门狗**。
     *    此前只在服务启动时部署一次，看门狗中途被 phantom process killer 带走后
     *    要等到下一次 Core 启动才会重建 —— 而那时已经没人负责把 Core 拉起来了。
     *    互相看护（Core 看 shell、shell 看 Core）才闭环。复查只做一次文件/proc 读，
     *    不唤醒设备、不起闹钟。
     */
    private fun ensureKeepAliveWatchdog() {
        if (keepAliveWatchdogJob?.isActive == true) return
        keepAliveWatchdogJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (KeepAliveWatchdog.ensureRunning(this@BackendService)) {
                        AppLogger.i(tag, "keepalive watchdog (re)started")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w(tag, "ensureKeepAliveWatchdog failed: ${e.message}")
                }
                delay(KeepAliveWatchdog.SELF_HEAL_INTERVAL_MS)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "UFI-AXIS-Core Backend",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "UFI-AXIS-Core 后端服务通知"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private suspend fun getDeviceGatewayIp(): String {
        // 方法1: Android ConnectivityManager
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val lp = cm?.getLinkProperties(activeNetwork)
            val dhcp = lp?.dhcpServerAddress?.hostAddress
            if (!dhcp.isNullOrBlank()) { AppLogger.i(tag, "Gateway (DHCP): $dhcp"); return dhcp }
        } catch (e: Exception) {
            AppLogger.w(tag, "Gateway detection method 1 (ConnectivityManager) failed: ${e.message}")
        }

        // 方法2: ip route
        try {
            val ipRoute = ShellExecutor.execute("ip route 2>/dev/null | grep default").stdout
            val m = Regex("default via (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(ipRoute)
            if (m != null) { val gw = m.groupValues[1]; AppLogger.i(tag, "Gateway (ip route): $gw"); return gw }
        } catch (e: Exception) {
            AppLogger.w(tag, "Gateway detection method 2 (ip route) failed: ${e.message}")
        }

        // 方法3: getprop (各接口名)
        try {
            for (prop in listOf("dhcp.wlan0.gateway", "dhcp.wlan.gateway", "dhcp.eth0.gateway", "dhcp.rmnet0.gateway")) {
                val gw = ShellExecutor.execute("getprop $prop").stdout.trim()
                if (gw.isNotBlank() && gw != "unknown") { AppLogger.i(tag, "Gateway (getprop): $gw"); return gw }
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "Gateway detection method 3 (getprop) failed: ${e.message}")
        }

        // 方法4: /proc/net/route 解析（直接文件读取，免 root）
        try {
            val route = File("/proc/net/route").readText()
            val lines = route.lines()
            for (line in lines) {
                val parts = line.split(WHITESPACE_REGEX)
                if (parts.size >= 3 && parts[1] == "00000000") {
                    val gwHex = parts[2].padStart(8, '0')
                    // /proc/net/route 中 gateway 是 little-endian hex:
                    // gwHex[6..7].[4..5].[2..3].[0..1] 各为一个十进制 octet
                    val gw = "${gwHex.substring(6, 8).toInt(16)}." +
                            "${gwHex.substring(4, 6).toInt(16)}." +
                            "${gwHex.substring(2, 4).toInt(16)}." +
                            "${gwHex.substring(0, 2).toInt(16)}"
                    AppLogger.i(tag, "Gateway (proc/net/route): $gw"); return gw
                }
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "Gateway detection method 4 (/proc/net/route) failed: ${e.message}")
        }

        AppLogger.w(tag, "Could not detect gateway, falling back to 192.168.0.1")
        return "192.168.0.1"
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UFI-AXIS-Core")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
