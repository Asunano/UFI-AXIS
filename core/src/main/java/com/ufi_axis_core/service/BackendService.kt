// 本类职责较多（DownloadManager / GoformClient / Aria2Engine / BackendService / NetworkModule / FileManagerScreen 均偏大）。全量拆分需人工评审并在编译后验证；当前仅保留最小安全抽取（见 GoformCodec）。
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
     *
     * CoroutineExceptionHandler：缺少它时，轮询协程中未捕获的异常会沿 SupervisorJob
     * 静默终止协程本身，且不产生任何日志，"短信不进 Provider 时 5 分钟兜底"整条链路
     * 随之失效。与上方 serviceScope 同一口径：记录 ERROR 后放行（SupervisorJob 已隔离，
     * 不影响兄弟协程）。
     */
    private val smsPollScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        AppLogger.e(tag, "SmsPollScope coroutine exception (uncaught) — 兜底轮询协程已终止", e)
    })

    companion object {
        const val CHANNEL_ID = "ufi_axis_core_service_v2"
        const val NOTIFICATION_ID = 1
        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val MAX_CRASH_RETRY = 3

        /**
         * SMS 转发兜底轮询间隔：5 分钟。
         *
         * 正常路径是 ContentObserver 即时触发（见 registerSmsObserver），本条只负责覆盖
         * "短信不进系统 Provider（纯 goform 设备）" 和 "observer 漏回调" 两种情况，
         * 所以间隔可以放得很松 —— 原实现 60s 一次的周期唤醒纯属浪费电。
         */
        private const val SMS_FALLBACK_POLL_MS = 5 * 60 * 1000L

        /**
         * "算新短信" 的最大回溯年龄：24 小时。
         *
         * 该窗口只用于「换 id 体系 / 首次装机」时避免补发收件箱中的历史短信，因此取 24 小时。
         * 不能把它收窄成"轮询间隔 + 1 分钟"式的新鲜度窗口：判重 id 已持久化
         * （[com.ufi_axis_core.controller.sms.SmsForwardController.lastForwardedSmsId]），
         * 但窗口若同时承担"发现得晚即丢弃"的职责，CPU 深睡期间到达、几分钟后才被处理的短信
         * 会被判成历史短信丢弃。
         */
        private const val SMS_MAX_AGE_MS = 24 * 60 * 60 * 1000L


        @Volatile
        var isRunning: Boolean = false

        /**
         * 用户**主动**停止（MainActivity 的「停止服务」按钮）。
         *
         * `onDestroy` 不得无条件 `scheduleServiceRestart()`：否则主动停止 10 秒后闹钟会把
         * 服务重新拉起，表现为「点了停止，goform 采集照跑」。带此标记的主动停止走
         * 「取消闹钟 + 落停止标记文件」，被杀 / 重启仍走原有兜底。
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
         *
         * ## 双保险
         * 恢复路径共两条，任一条先到即可（[onStartCommand] 的 `isRunning` 守卫可接受重复拉起）：
         * 1. **shell 侧**（本方法派出的 detached `am start-foreground-service`）：不受 AMS 后台限制，
         *    与 core 自更新脚本 `ufi_update.sh` 使用同一条已验证的命令；
         * 2. **闹钟侧**（[scheduleServiceRestart]，使用精确闹钟）：不依赖 ADB 通道。
         *
         * 只保留闹钟一条路径时存在失联风险：闹钟若非精确，Android 12+ 的「后台启动前台服务」
         * 豁免不适用，到点时 `startForegroundService` 被 AMS 拒绝，异常发生在系统侧不可见，
         * 且两枚闹钟都是 `FLAG_ONE_SHOT`，消耗后不会再排。
         *
         * 该功能的失败代价是「设备失联」，不能依赖单点恢复。
         *
         * 语义：`stopService` 只销毁 Service 组件，**进程通常仍存活**，因此 `object` 单例与
         * 静态状态不会被清掉；此处不是进程级重启。
         */
        fun requestRestart(context: Context) {
            AppLogger.w("BackendService", "Full restart requested — shell + alarm 双保险")
            dispatchShellRestart(context)
            // 不能走 stop()：那会置 explicitStop，onDestroy 就不排重启闹钟了，服务再也回不来。
            context.stopService(Intent(context, BackendService::class.java))
        }

        /**
         * 派一个 detached shell 把服务拉回来。
         *
         * 几个细节都是必须的：
         * - `nohup setsid` —— 脱离本进程的进程组。本进程随后可能被系统回收，子 shell 必须能活下去；
         * - `sleep` —— 必须等 `stopService` 走完 `onDestroy`（那里才真正停 Ktor / 采集），
         *   否则 `am` 拉起的新实例会被随后的销毁流程一起带走；
         * - `-f 0x00000020`（`FLAG_INCLUDE_STOPPED_PACKAGES`）—— 与自更新脚本一致；
         * - 不发 `am kill`：这里是重启**服务**而不是替换 APK，进程留着还能让闹钟那条路生效。
         * - 老 ROM 没有 `start-foreground-service` 时退回 `am startservice`。
         *
         * ADB 通道不可用时 `executeAsRoot` 会回退到 app uid 的普通 shell，那种情况下
         * `am start-foreground-service` 会被 AMS 拒 —— 这正是还要保留闹钟那一路的原因。
         */
        private fun dispatchShellRestart(context: Context) {
            val component = "${context.packageName}/com.ufi_axis_core.service.BackendService"
            val cmd = "nohup setsid sh -c 'sleep ${SHELL_RESTART_DELAY_SEC}; " +
                "am start-foreground-service --user 0 -f 0x00000020 -n $component || " +
                "am startservice --user 0 -f 0x00000020 -n $component' >/dev/null 2>&1 &"
            // 用独立 scope：serviceScope 会在 onDestroy 里被 cancel，而这条命令必须在那之前派出去。
            // 进程不会因 stopService 而死，所以这个 launch 有机会跑完。
            CoroutineScope(Dispatchers.IO).launch {
                val r = runCatching { ShellExecutor.executeAsRoot(cmd, SHELL_DISPATCH_TIMEOUT_MS) }
                r.onSuccess {
                    AppLogger.i("BackendService", "shell 重启已派发（${SHELL_RESTART_DELAY_SEC}s 后执行）")
                }.onFailure {
                    AppLogger.w("BackendService", "shell 重启派发失败，仅剩闹钟兜底: ${it.message}")
                }
            }
        }

        /** shell 侧延迟拉起的秒数：必须长于 `onDestroy` 停组件所需时间。 */
        private const val SHELL_RESTART_DELAY_SEC = 4

        /** 派发命令本身的超时（只是 `&` 出去，不等 sleep 结束）。 */
        private const val SHELL_DISPATCH_TIMEOUT_MS = 10_000L



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
         * 120s 后它就过期、CPU 允许深睡 —— 这导致邮件只在「前端已连接」时才可靠送达：短信到达时
         * RIL 唤醒了 CPU，但保活锁在系统广播结束即释放，而读库 + SMTP 握手是异步的，
         * 常在中途被挂起，直到下一次 CPU 被唤醒（往往就是前端连上来）才继续。
         * 所以邮件链路必须自己持锁，且只在**有事件发生且邮件功能可用**时持有，平时照旧省电。
         */
        private const val MAIL_WAKELOCK_TAG = "UfiAxisCore::MailSend"
        /** 单次投递的持锁上限：SMTP 连接/读超时各 10s，60s 足够覆盖握手 + 重试；超时自动释放，防止泄漏。 */
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
     * 而看门狗负责保证 Core 进程存活，它自身会读取 stop-flag 决定是否拉起。
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
        // acquire 必须在协程内调用，消除「launch 未被调度则锁永不释放」的泄漏窗口。
        serviceScope.launch {
            val lock = acquireMailWakeLock()
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
     * 注册系统短信库变更观察者：新短信到达即触发 [onNewSms]（亚秒级），主路径不再依赖 60s 轮询。
     *
     * - 用 `notifyForDescendants = true`：写入通常落在 `content://sms/inbox/{id}` 这样的子 URI 上，
     *   只监听根 URI 会收不到。
     * - 回调在主线程 Looper，不能做 IO，因此立刻转投 [smsPollScope]。
     * - 系统写一条短信可能触发多次 onChange；[forwardLatestSmsIfNew] 按 id 去重。
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
     * 扫一遍「水位之后的新短信」，把其中真实来信逐条转成邮件。
     *
     * 三道闸：
     * - 配置可发信（[com.ufi_axis_core.controller.sms.SmsForwardController.isSendable]）：
     *   不可发就直接退出，绝不为此做任何 IO / 持锁；
     * - id 去重（持久化的 `lastForwardedSmsId`）：同一条只发一次，服务重启也认；
     * - `age <= `[SMS_MAX_AGE_MS]：首次装机 / 换 id 体系时不补发收件箱中的历史短信。
     *
     * 后两道的选取逻辑在纯函数 [com.ufi_axis_core.controller.sms.SmsForwardWindow.plan] 里。
     * 必须扫一批而不是只读取最新一条：**来信与本机发出的短信几乎同时落库、来信在先**时，
     * 只看最新一条会永久跳过该邮件。
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
                val window = smsCtl.getAllUnfiltered(
                    limit = com.ufi_axis_core.controller.sms.SmsForwardWindow.SCAN_READ_LIMIT,
                    fallbackOnEmpty = true
                )
                if (window.isEmpty()) return
                val lastId = forwardCtl.lastForwardedSmsId
                val plan = com.ufi_axis_core.controller.sms.SmsForwardWindow.plan(
                    messages = window,
                    lastForwardedId = lastId,
                    now = System.currentTimeMillis(),
                    maxAgeMs = SMS_MAX_AGE_MS
                )
                if (plan.highWaterMark == lastId) return
                if (plan.skipped > 0) {
                    AppLogger.w(
                        tag,
                        "SMS forward ($source): 窗口内新来信超过单轮上限" +
                            "（${com.ufi_axis_core.controller.sms.SmsForwardWindow.MAX_BATCH_PER_SCAN} 条）" +
                            "，放弃较早的 ${plan.skipped} 条邮件通知（正文仍可在 /api/sms 查到）"
                    )
                }
                // 先推进 lastForwardedSmsId 再投递，**顺序不可交换**：
                // 投递失败（含总闸关闭 / 免打扰静默期 / SMTP 不可用）时这些短信不会被下一轮补发。
                // 反序（先投递成功再落 id）会使总开关打开时集中补发静默期内累积的通知，
                // 而这些通知的时效已过。短信正文在 /api/sms 中始终可查，补发的只是通知。
                // 写水位走 synchronized 的 advanceWaterMarkIfBeyond，与读侧互斥，
                // 消除「A/B 双路径并发推进同一水位时互相覆盖 / 重复投递」的竞态。
                forwardCtl.advanceWaterMarkIfBeyond(plan.highWaterMark)
                // **逐条串行**：forwardSms 内部持 WakeLock 走 SMTP，并发只会让几个握手互相抢锁。
                for (msg in plan.toForward) {
                    // 自动登录正在等这条 10086 验证码：core 自己消费掉，不再转发/推送。
                    // （Android 默认短信 App 的系统通知不归 core 管，见 CarrierAutoLoginRuntime 注释）
                    if (com.ufi_axis_core.controller.carrier.CarrierAutoLoginRuntime
                            .shouldSuppress(msg.address, msg.body)
                    ) {
                        AppLogger.i(tag, "Auto-login consumed SMS #${msg.id}; skip forward")
                        continue
                    }
                    // 号码打码 + 正文只留摘要（与 SmsForwardController 同一口径）
                    AppLogger.i(
                        tag,
                        "New SMS #${msg.id} from ${AppLogger.maskPhone(msg.address)} via $source: " +
                            AppLogger.bodyDigest(msg.body)
                    )
                    val ok = forwardCtl.forwardSms(msg.address, msg.body, msg.date)
                    AppLogger.i(tag, "Forward result #${msg.id}: ${if (ok) "success" else "failed"}")
                }
            } catch (e: CancellationException) {
                // 取消不是投递失败：原样重抛，不记为一条 SMS 转发错误
                throw e
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

        // ensureKeepAliveWatchdog() 必须在 initializeComponents() 中、ADB 特权通道初始化之后调用：
        // AdbShellExecutor 未就绪时 ShellExecutor.executeAsRoot 会 fallback 到 app UID 的
        // ProcessBuilder，无法写入 /data/local/tmp/。
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务被再次启动时，上一次的主动停止作废（含 shell 看门狗的停止标记）
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

        // isRunning / startedAtElapsed **同步**置位，不能放在 serviceScope.launch 内：
        // 否则 AlarmManager(10s) 与 keepalive 看门狗的两次 startService 都可能通过上面的
        // `if (isRunning)` 守卫，ComponentFactory 走 "already built → reset 重建" 分支，
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
        graph?.serverGraph?.carrierAutoLoginManager?.stop()
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

    /**
     * 端口预检与自动回退。
     *
     * 端口被占时 core 会陷入"启动失败 → stopSelf → 看门狗拉起 → 再失败"的无限循环，
     * 客户端完全连不上，而远程没有任何入口能改端口（HTTP 都不在）。所以必须在 build 之前
     * （HttpServer 的端口是构造参数）先探一次，必要时回退默认端口。
     *
     * 判两轮是为了避开"自己的旧 server 还没退净"：`stopSelf` 后重启时端口可能被上一轮
     * 短暂占着，直接判"被占"会白白把用户配置的端口改掉。
     */
    private suspend fun ensurePortUsable() {
        val settings = AppSettings.getInstance(this)

        // 安全模式：连续启动失败达到阈值后，下一次启动用**最小配置**拉起
        // 强制默认端口 + 关闭后台采集 —— 目标是"先让设备能被连上、能进设置页改回来"，
        // 而不是继续按原配置无限重启。成功启动后由调用方清除（一次性）。
        if (settings.safeModeActive) {
            AppLogger.w(tag, "SAFE MODE active — starting with minimal config (default port, background off)")
            if (settings.port != AppSettings.DEFAULT_PORT) {
                AppLogger.w(tag, "safe mode: port ${settings.port} -> ${AppSettings.DEFAULT_PORT}")
                settings.port = AppSettings.DEFAULT_PORT
            }
            settings.backgroundServiceEnabled = false
        }

        val configured = settings.port
        if (isPortBindable(configured)) return

        delay(2_000)
        if (isPortBindable(configured)) return

        if (configured == AppSettings.DEFAULT_PORT) {
            AppLogger.e(
                tag,
                "Port $configured is occupied and it is already the default — cannot fall back; " +
                    "service will fail to start (use local Settings page to change port)"
            )
            return
        }
        if (!isPortBindable(AppSettings.DEFAULT_PORT)) {
            AppLogger.e(
                tag,
                "Port $configured occupied and default ${AppSettings.DEFAULT_PORT} also occupied — cannot fall back"
            )
            return
        }
        settings.port = AppSettings.DEFAULT_PORT
        AppLogger.w(
            tag,
            "Port $configured occupied — fell back to default ${AppSettings.DEFAULT_PORT} " +
                "(可以恢复：本机设置页改回端口，或 App/Web 的 PUT /api/config)"
        )
    }

    /** 能否在本机绑定该端口（true = 端口空闲）。 */
    private fun isPortBindable(port: Int): Boolean = runCatching {
        java.net.ServerSocket(port).use { true }
    }.getOrDefault(false)

    private suspend fun initializeComponents() {
        try {
            AppLogger.i(tag, "Initializing components...")
            updateNotification("正在初始化组件...")

            // 端口占用预检 + 自动回退。
            //
            // 必须在 build() 之前执行：HttpServer 的端口是**构造参数**，build 之后无法修改；
            // 端口被占时 HttpServer 只会自检失败 → stopSelf → 看门狗再拉 → 再失败，
            // 形成无限重启（且 BackendService 在失败分支把 crashRetryCount 清零，
            // "连续 3 次就停"的保护对这条路径不生效）。
            // 设备上最常见的冲突：8080（厂商 goform 管理面）、5555（adbd）、7682（ttyd）。
            ensurePortUsable()

            // 初始化看门狗：build() 全程是阻塞调用（解压资产、shell 探测网关、二进制 --version、
            // 特权 shell 迁移），任何一处卡住都不会被察觉 —— 通知栏停在"正在初始化组件..."，
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
            // override 一重启就删掉）。读不到版本时返回 null —— 此时**不得覆写基线**，
            // 否则基线被擦除，下次启动会误判成"版本变了"并再删一次 override。
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


            // ── HTTP 服务优先启动，使所有接口（含 /api/device/info）尽快可用 ──
            // 组件图构建完成即启动服务，ADB / Samba / 看门狗等阻塞式系统初始化放到后台：
            // 特权 shell 命令变慢或卡住时，HTTP 端口仍能尽早监听，前端不会停在“加载中”。
            // Core 启动即无条件启动冷数据采集（月流量 / 电池 / SMS / 信号等），
            // 与前端是否连接解耦 —— 否则前端（重）连接间隙或进程重启后 goform 数据会永久归零。
            // 实时 WebSocket 推送的启停由 ComponentFactory 的 WS 连接回调（applyRealtimePushState）单独控制。
            // 用户显式停掉的后台采集在服务重启后必须保持停止 —— 开关真源是持久化的
            // `AppSettings.backgroundServiceEnabled`（默认 true，即"启动即采集"）。
            // 注意：setMonitorEnabled 内部已经调用 applyColdCollectionState()，此处不得重复调用
            // （重复调用会多开一次 start/stop 窗口，放大竞态）。
            // （重复调用会多开一次 start/stop 窗口，无益且放大竞态）。
            g.serverGraph.dataScheduler.setMonitorEnabled(
                AppSettings.getInstance(this@BackendService).backgroundServiceEnabled
            )
            // 「停止服务」= 除 HTTP 之外全停。除采集调度外，定时任务 / 隧道看护 /
            // 下载后台轮询都在各自 init 中启动（ComponentFactory.build 期间），
            // 因此关闸状态下必须在此显式停止，否则服务重启后会自动恢复。
            backgroundActive = AppSettings.getInstance(this@BackendService).backgroundServiceEnabled
            if (!backgroundActive) {
                AppLogger.w(tag, "backgroundServiceEnabled=false — 后台自主活动保持停止（仅 HTTP 服务可用）")
                pauseComponentBackground(g)
            } else {
                // 自动登录会发真实短信，必须跟后台服务总闸一起起停。
                g.serverGraph.carrierAutoLoginManager.start()
            }

            // ── 应用列表定时主动刷新 ──
            // 启动位置在 HTTP 就绪之后，不在 ComponentFactory.build() 内：
            // build() 是阻塞调用且受 15s 初始化看门狗监视，而首轮刷新要串 4 条特权 shell
            // （pm list ×3 + dumpsys），设备上为秒级 —— 放进 build 会使「通知栏停在初始化 →
            // 进程被判超时重启」。与本文件中的 geo 检测同一理由。
            //
            // 也不在 AppManager 构造中启动：AppManager 还有一个仅做自更新安装的短暂实例
            // （BootReceiver 开机恢复），构造即起协程会给该一次性实例挂上永不结束的循环。
            //
            // 作用域使用 serviceScope：onDestroy 中会被 cancel，即使下方显式 stop 遗漏也会终止。
            // 停机路径另有 stopAllComponents 中的显式停止。
            g.serverGraph.appManager.startPeriodicRefresh(serviceScope)

            // ── 媒体索引缓存 ──
            // 与 appManager 同一套挂法：定时刷新用 serviceScope（onDestroy 会 cancel，
            // 停机路径另有 stopAllComponents / pauseComponentBackground 中的显式停止）。
            g.serverGraph.mediaIndexStore.startPeriodicRefresh(serviceScope)
            // 启动时预热一轮：定时循环的第一轮要等满 5 分钟，不预热时用户首次进入媒体页
            // 会触发"冷启动同步拉取"（此时快照还不存在，只能现场查询一次）。
            // 延迟 8s 避开启动高峰（AT 通道 / goform 登录 / update 恢复都在头几秒），
            // 而它是纯 MediaStore 元数据查询（不像 appManager 那样串 4 条特权 shell），
            // 所以不必像它那样等到 HTTP 就绪之后很久。
            serviceScope.launch {
                delay(8_000L)
                runCatching { g.serverGraph.mediaIndexStore.refresh(force = true) }
                    .onFailure { AppLogger.w(tag, "媒体索引启动预热失败: ${it.message}") }
            }

            val serverOk = g.serverGraph.server.start()
            if (!serverOk) {
                // 服务启动失败：不得静默继续（否则端口不监听，外部只能看到 ERR_CONNECTION_REFUSED）。
                // 直接停止并记入失败计数，由 START_STICKY / 看门狗重新拉起，并在 logcat 暴露真实异常。
                AppLogger.e(tag, "HTTP Server failed to start — stopping service to surface failure")
                // 累计"连续启动失败"次数；达到阈值即启用安全模式，
                // 下一次启动使用默认端口 + 关闭后台采集，避免陷入无法远程修正的无限重启循环。
                runCatching {
                    val s = AppSettings.getInstance(this@BackendService)
                    val failures = s.startupFailureCount + 1
                    s.startupFailureCount = failures
                    if (failures >= AppSettings.SAFE_MODE_THRESHOLD) {
                        s.safeModeActive = true
                        AppLogger.e(
                            tag,
                            "startup failed $failures times — SAFE MODE armed for next start " +
                                "(default port ${AppSettings.DEFAULT_PORT}, background off)"
                        )
                    }
                }.onFailure { AppLogger.w(tag, "failed to record startup failure: ${it.message}") }
                // start 失败后先延迟再 stopSelf，避开旧 Netty event loop 未退净导致的端口冲突窗口
                // （同进程先 stop 后立刻 start 偶发 Address already in use）。
                delay(3000)
                crashRetryCount.set(0)
                isRunning = false
                g.serverGraph.server.stop()
                stopSelf()
                return
            }
            // 启动成功 → 清失败计数、退出安全模式（安全模式只作用于下一次启动）。
            runCatching {
                val s = AppSettings.getInstance(this@BackendService)
                if (s.startupFailureCount != 0) s.startupFailureCount = 0
                if (s.safeModeActive) {
                    s.safeModeActive = false
                    AppLogger.i(tag, "SAFE MODE cleared — normal startup succeeded")
                }
            }
            AppLogger.i(tag, "[14] HTTP server ready")

            // ── 出网国家/地区检测（`/api/geo`）──
            //
            // 【为什么挂在这里，而不是 ComponentFactory.build() 里】
            // build() 是**阻塞**调用，且被初始化看门狗盯着（15s 一次心跳，超时直接杀进程自愈）。
            // 这个检测最坏要串三个上游 × 8s = 24s，放进 build() 等于用一次外网抖动去换
            // 「通知栏卡在正在初始化 → 进程被判超时重启」。放在 HTTP 就绪之后的独立协程里，
            // 端口早已监听，检测慢多久都只影响它自己。
            //
            // 【仅启动时执行，不使用定时器】本仓不允许常驻定时器 / 周期闹钟；
            // 国家/地区几乎不变，启动时检测一次已足够，7 天过期可覆盖"设备被带出国"的情形。
            //
            // 【失败不重试】一次失败后等下一次 core 重启（或客户端主动调用 `/api/geo`）再试，
            // 只记录一条 WARN。在此加入退避重试等同于引入周期定时器。
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val settings = AppSettings.getInstance(this@BackendService)
                    // 更新下载方式的一次性迁移：旧设备没有 update_source_mode 键时统一置 auto，
                    // 由 core 按地区决定。放在此处而不是 build()：它只写一次 prefs，
                    // 但需要输出一条说明日志，与地区检测同属启动后的一次性动作。
                    settings.migrateUpdateSourceModeIfAbsent()
                    if (!com.ufi_axis_core.api.geo.GeoDetector.isStale(settings)) {
                        AppLogger.i(
                            tag,
                            "地理位置无需重测（${settings.geoCountry}，7 天内测过）— 本次启动不出网"
                        )
                        return@launch
                    }
                    com.ufi_axis_core.api.geo.GeoDetector.refreshIfStale(settings)
                } catch (e: Exception) {
                    AppLogger.w(tag, "启动时地理位置检测失败（非致命，等下次启动再试）: ${e.message}")
                }
            }

            // ── 系统关键但非接口前置的初始化：放后台，绝不阻塞 HTTP 服务 ──
            serviceScope.launch(Dispatchers.IO) {
                try {
                    // ADB 特权通道（系统关键，无条件启动）
                    // `AdbController.start()` **不**打开 USB_PORT ——
                    // 该自动切换逻辑已移除（见 AdbController 头注释）。
                    // 它只负责初始化 AdbShellExecutor（所有 executeAsRoot 依赖的 uid 2000 通道）；
                    // adbd 是否监听由设备侧决定，core 不再替用户打开。
                    try {
                        val adbOk = g.controller.adbController.start()
                        AppLogger.i(tag, "ADB privileged channel started: $adbOk")
                    } catch (e: Exception) {
                        AppLogger.e(tag, "ADB privileged channel start failed", e)
                    }
                    // 部署 Samba root shell 基础设施（socat + samba_exec.sh + smb.conf）
                    //
                    // **顺序要求**：必须先建立 root 通道，再部署看门狗。
                    // 反序时 ensureRunning 拿不到 root socket，只能退回 uid 2000 的 ADB 通道
                    //（在本机不生效），开机后需等待 5 分钟的自愈循环才能把看门狗拉起。
                    // deploy 返回结构化结果：失败不再只有一行 W 日志，目录属主为上次安装 uid
                    // 这类静默失败可从结果中区分。
                    try {
                        val r = com.ufi_axis_core.util.SambaRootShell.deploy(this@BackendService)
                        if (r.ok) {
                            AppLogger.i(tag, "Samba root shell 就绪：${r.detail}")
                        } else {
                            AppLogger.w(tag, "Samba root shell 未就绪（${r.stage}）：${r.detail}")
                        }
                    } catch (e: Exception) {
                        AppLogger.w(tag, "Samba root shell deploy failed (non-fatal): ${e.message}")
                    }
                    // root 通道就绪后再部署 shell 看门狗（现在它默认走 root socket，uid 0）
                    ensureKeepAliveWatchdog()
                } catch (e: Exception) {
                    AppLogger.e(tag, "Background system init failed (non-fatal): ${e.message}", e)
                }
            }

            // 定期重新触发 Samba 连接（保持 socat socket 存活）。
            // 循环体在 startSambaKeepAlive() 中，受后台服务总闸控制。
            if (backgroundActive) startSambaKeepAlive()



            // ── 电池事件通知（走通知分发器） ──
            // 走 launchMailEvent：与短信一样，投递期间自行持锁，不能依赖"前端连着"的保活锁。
            //
            // 电池事件不得借 `forwardSms("SYSTEM", …)` **伪装成短信**：
            // `SYSTEM` 这个伪发件人被显式排除在场景判定之外，等于绕过全部场景开关 ——
            // 用户在邮件设置里未勾选任何场景也会收到低电量邮件，且界面上没有关闭入口。
            // 电池事件使用独立场景 [NotifyScenes.BATTERY]（存量用户由 `migrateScenesV3` 补入
            // scenes，升级前后行为一致）。
            //
            // `exclude = {push}`：除**推送**之外的所有渠道都投（邮件、Webhook、本机短信……）。
            // 不使用 `channels = {mail}` 白名单：写死邮件渠道时，用户在 Webhook /
            // 本机短信中勾选「电池状态」不会触发，形成无效开关。
            //
            // 单独排除推送的依据：app 侧 `NotifyService` 的推送分流只认
            // `sms`/`verification` 与 `download`/`tunnel`，其余带 `message` 的一律当成 AlertRecord
            // 走 `maybeNotifyNewAlerts` —— `type = "battery"` 会被记成一条阈值告警（`id = 0`），
            // 还会把告警游标推到电池事件的时间戳，把随后到达的真告警吞掉。
            // 让它进推送需要 app 侧一整套镜像（通知 channel + 分类开关 + 分流分支）外加把
            // `battery` 补进 `PushChannel.SINGLE_TOPIC_SCENES`（否则 web 的告警列表会多出一条
            // core `alert_records` 中不存在的记录）。该能力属于新功能，见 `NotifyScenes.BATTERY`。
            //
            // 使用 exclude 而不是枚举全部渠道 id：该清单每新增一个渠道都需要同步维护，遗漏即产生无效开关。
            val emitBattery: suspend (String, String) -> Unit = { title, body ->
                g.serverGraph.notificationDispatcher.emit(
                    com.ufi_axis_core.notify.NotifyEvent(
                        scene = com.ufi_axis_core.notify.NotifyScenes.BATTERY,
                        level = com.ufi_axis_core.notify.NotifyLevel.WARNING,
                        title = title,
                        body = body,
                        exclude = setOf(com.ufi_axis_core.notify.PushChannel.ID)
                    )
                )
            }
            val notifier = BatteryNotifier(
                onLowBattery = { pct ->
                    launchMailEvent("Battery low") {
                        emitBattery("低电量警告", "设备电量已降至 ${pct}%")
                    }
                },
                onVeryLowBattery = { pct ->
                    launchMailEvent("Battery very low") {
                        emitBattery("极低电量警告", "设备电量已降至 ${pct}%，随时可能关机")
                    }
                },
                onFullBattery = {
                    launchMailEvent("Battery full") {
                        emitBattery("电池已充满", "设备电池已充满，可以拔掉充电器")
                    }
                },
                onChargeStart = {
                    AppLogger.d(tag, "Charging started")
                }
            )

            batteryNotifier = notifier

            // ── SMS 转发：ContentObserver 事件驱动 + 5 分钟兜底轮询 ──
            // 循环体在 startSmsForwarding() 中，受后台服务总闸控制。
            if (backgroundActive) startSmsForwarding(g)


            // 注意：HTTP 服务已在组件图构建完成后提前启动（见上），此处不重复启动；
            // ADB / Samba / 看门狗等系统初始化在后台执行。

            // ── 配对模式（Onboarding）：始终确保配对码存在（多设备模式下不因已配对而跳过）──
            g.storage.settings.enterPairingMode()

            // ── 注册电池事件监听 ──
            if (backgroundActive) registerBatteryNotifier()


            updateNotification("UFI-AXIS-Core 运行中 (:${g.storage.settings.port})")
            AppLogger.i(tag, "All components initialized successfully. Server listening on :${g.storage.settings.port}")

            // 会话诊断日志启动标记（Download/UFI-AXIS/log/core/goform-session.log）。
            // 版本 + APK 安装时间可用于确认部署的构建；文件不存在说明设备上仍是旧构建。
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
            // 通知栏若只带 e.message（如 ART 的 "Null reference used for
            // synchronization"）信息量不足，且用户常无法取得日志。把最深的 3 个应用栈帧
            // 拼进通知，无需日志即可定位到类。
            val frames = e.stackTrace
                .take(6)
                .filter { it.className.startsWith("com.ufi_axis") }
                .take(3)
                .joinToString(" ← ") { "${it.className.substringAfterLast('.').substringBefore("Kt")}.${it.methodName}:${it.lineNumber}" }
            val detail = if (frames.isNotBlank()) "${e.message} @ $frames" else e.message
            updateNotification("UFI-AXIS-Core 启动失败: $detail")

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
     * HTTP 必须保留：它是用户重新启动服务的唯一通道，停掉后无法远程恢复
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
            g?.serverGraph?.carrierAutoLoginManager?.start()
            registerBatteryNotifier()
        } else {
            AppLogger.w(tag, "Background services: STOP (HTTP server keeps running)")
            stopSmsForwarding()
            stopSambaKeepAlive()
            g?.serverGraph?.carrierAutoLoginManager?.stop()
            unregisterBatteryNotifier()
            if (g != null) pauseComponentBackground(g)
        }
    }

    /** 停掉组件图里的自主循环（可逆，配 [resumeComponentBackground]）。 */
    private fun pauseComponentBackground(g: ComponentGraph) {
        // 读穿透闸门：客户端的读请求不再因缓存过期去打 goform，只回最后一次已知值。
        // 否则采集已停，但任何一次 REST 读都是 cache miss，miss 会重新 ensureLogin()，
        // 表现为「停止服务后仍在登录 goform 后台」。
        try { g.storage.responseCache.setReadThroughPaused(true) } catch (e: Exception) { AppLogger.w(tag, "cache gate failed: ${e.message}") }
        try { g.serverGraph.alertEngine.stop() } catch (e: Exception) { AppLogger.w(tag, "alertEngine.stop failed: ${e.message}") }
        try { g.controller.taskScheduler.pause() } catch (e: Exception) { AppLogger.w(tag, "taskScheduler.pause failed: ${e.message}") }
        try { g.controller.tunnelManager.stopGuard() } catch (e: Exception) { AppLogger.w(tag, "tunnelManager.stopGuard failed: ${e.message}") }
        try { g.programManager.pause() } catch (e: Exception) { AppLogger.w(tag, "programManager.pause failed: ${e.message}") }
        try { g.controller.downloadManager.pauseBackground() } catch (e: Exception) { AppLogger.w(tag, "downloadManager.pauseBackground failed: ${e.message}") }
        // 应用列表定时刷新同属「自主活动」：用户点「停止服务」后不该还每 2 分钟打 4 条特权 shell。
        try { g.serverGraph.appManager.setPeriodicRefreshEnabled(false) } catch (e: Exception) { AppLogger.w(tag, "appManager.setPeriodicRefreshEnabled failed: ${e.message}") }
        // 媒体索引定时刷新同理：停止后不应仍每 5 分钟扫描一遍整库。
        try { g.serverGraph.mediaIndexStore.setPeriodicRefreshEnabled(false) } catch (e: Exception) { AppLogger.w(tag, "mediaIndexStore.setPeriodicRefreshEnabled failed: ${e.message}") }
        // 自动登录会发真实短信；后台服务停止时必须一起停。
        g.serverGraph.carrierAutoLoginManager.stop()
    }

    private fun resumeComponentBackground(g: ComponentGraph) {
        try { g.storage.responseCache.setReadThroughPaused(false) } catch (e: Exception) { AppLogger.w(tag, "cache gate failed: ${e.message}") }
        try { g.controller.taskScheduler.resume() } catch (e: Exception) { AppLogger.w(tag, "taskScheduler.resume failed: ${e.message}") }
        try { g.controller.tunnelManager.startGuard() } catch (e: Exception) { AppLogger.w(tag, "tunnelManager.startGuard failed: ${e.message}") }
        try { g.programManager.resume() } catch (e: Exception) { AppLogger.w(tag, "programManager.resume failed: ${e.message}") }
        try { g.controller.downloadManager.resumeBackground() } catch (e: Exception) { AppLogger.w(tag, "downloadManager.resumeBackground failed: ${e.message}") }
        try { g.serverGraph.appManager.setPeriodicRefreshEnabled(true) } catch (e: Exception) { AppLogger.w(tag, "appManager.setPeriodicRefreshEnabled failed: ${e.message}") }
        // 媒体索引定时刷新同属「自主活动」
        try { g.serverGraph.mediaIndexStore.setPeriodicRefreshEnabled(true) } catch (e: Exception) { AppLogger.w(tag, "mediaIndexStore.setPeriodicRefreshEnabled failed: ${e.message}") }
    }

    /**
     * Samba socket 保活：60s 一轮，连续失败 ≥3 次退到 300s。
     *
     * 循环必须同时满足三点：条件用 isActive、delay 写在 try 外、CancellationException 先 rethrow。
     * 否则 CancellationException（Exception 子类）在 serviceScope 取消后由 delay 抛出并被
     * catch 吞掉，循环继续，退化为每秒数千次的无延迟死循环，单日可向 app_*.log 写入
     * 304MB（实测）。
     */
    private fun startSambaKeepAlive() {
        if (sambaKeepAliveJob?.isActive == true) return
        sambaKeepAliveJob = serviceScope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0
            while (isActive) {
                // 连续失败 ≥10 次后进入「挂起」档（30min）：设备 SMB 未启用 / 密码错误时，
                // 每 5 分钟 6 条连接失败 + 1 条汇总（真机日志实测）会持续刷日志，而 socket
                // 不会自行恢复。30min 后仍会重试（不是放弃），部署成功或 socket 恢复即复位。
                val baseDelay = when {
                    consecutiveFailures >= 10 -> 1_800_000L
                    consecutiveFailures >= 3 -> 300_000L
                    else -> 60_000L
                }
                delay(baseDelay)
                try {
                    if (com.ufi_axis_core.util.SambaRootShell.isSocketAvailable(this@BackendService)) {
                        if (consecutiveFailures >= 10) {
                            AppLogger.i(tag, "Samba socket recovered after $consecutiveFailures failures")
                        }
                        consecutiveFailures = 0
                    } else if (consecutiveFailures >= 10 && consecutiveFailures % 6 == 0) {
                        // 挂起档下每 ~3 小时才提醒一次（30min × 6），其余轮次静默
                        AppLogger.d(tag, "Samba socket still unavailable (failCount=$consecutiveFailures), skipping trigger this round")
                        consecutiveFailures++
                    } else {
                        AppLogger.d(tag, "Samba socket not available, redeploying... (failCount=$consecutiveFailures)")
                        // 这里执行整条 deploy，而不是只触发一次连接。
                        // 开机时 core 可能比固件写出 smb.conf 早数秒，deploy 读不到配置会直接失败；
                        // 若只执行一次，整条 root 通道直到下次重启都不会恢复，看门狗也无法启动。
                        // deploy 是幂等的（配置已是目标状态即跳过写入），放进这个
                        // 60s/5min/30min 退避循环重试的代价可以忽略。
                        val r = com.ufi_axis_core.util.SambaRootShell.deploy(this@BackendService)
                        if (r.ok) {
                            AppLogger.i(tag, "Samba root 通道已恢复：${r.detail}")
                            consecutiveFailures = 0
                        } else {
                            AppLogger.w(tag, "Samba root 通道仍未恢复（${r.stage}）：${r.detail}")
                            consecutiveFailures++
                        }
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
     * 主路径为短信库变更即触发：ContentObserver 在系统写入短信时回调（亚秒级），
     * 且不保留 60s 的周期性唤醒，功耗更低。60s 轮询下，从收到验证码到发出邮件平均需等待
     * 30s、最坏 60s+，再加 SMTP 握手，是整条链路最大的一段延迟。
     * 兜底轮询保留 5 分钟一次，覆盖两种 observer 不可靠的情况：
     *   ① 短信只存在于 modem（goform）、不进系统 ContentProvider 的设备；
     *   ② observer 因进程被冻结等原因漏掉回调。
     */
    private fun startSmsForwarding(g: ComponentGraph) {
        if (smsForwardJob?.isActive == true) return
        smsForwardJob = smsPollScope.launch {
            val smsForwardCtl = g.controller.smsForwardController
            // sms 域迁入 DeviceAdapter 后取值从 deviceHub.sms 获取
            // （NetworkGraph 不再有 smsClient 字段）。取得的是同一实例，行为不变。
            val smsCtl = com.ufi_axis_core.controller.sms.SmsController(this@BackendService, g.network.deviceHub.sms)
            val initCfg = smsForwardCtl.loadConfig()
            AppLogger.i(tag, "SMS forward init: enabled=${initCfg.enabled}, smtpHost=${initCfg.smtpHost.takeIf { it.isNotBlank() } ?: "(empty)"}")

            // 事件驱动：注册到系统短信库
            registerSmsObserver(
                isMailEnabled = { smsForwardCtl.isSendable() },
                onNewSms = { forwardLatestSmsIfNew(smsForwardCtl, smsCtl, "observer") }
            )

            // 启动即查询一次（不先 delay 60s），之后每 5 分钟兜底。
            // 该循环是 **机会性** 的：CPU 深睡期间 delay 不会唤醒设备（有意为之，
            // 不为兜底轮询排闹钟耗电），设备醒着时才补一次。及时性由上面的 observer 保证。
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
                // 必须传 RECEIVER_EXPORTED：sticky 广播回放时 callingUid 是 -1（不是 SYSTEM_UID），
                // 过不了 NOT_EXPORTED 的同应用校验，注册瞬间的回放会被系统丢弃，日志为：
                //   W BroadcastQueue: Exported Denial: sending Intent { act=...BATTERY_CHANGED },
                //     from null (uid=-1) due to receiver ...ufi_axis_core... not specifying RECEIVER_EXPORTED
                // BATTERY_CHANGED 属于系统 protected broadcast，三方应用无法发送，使用 EXPORTED 不引入攻击面。
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
        // ① 先停**数据生产者**。顺序必须是 dataScheduler.stop() 先于 server.stop()：
        // 反序（server.stop() → delay(2000) → dataScheduler.stop()）时，「停止服务」后
        // DataScheduler 仍会继续采集约 2 秒，设备侧表现为仍在发出 goform 请求。
        try { g.serverGraph.dataScheduler.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping scheduler", e) }
        try { g.serverGraph.alertEngine.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping alert engine", e) }
        try { g.controller.taskScheduler.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping task scheduler", e) }
        // ② 再断开对外通道（WS 先关，避免 server 停了还有连接在等推送）
        // 分发器排在最先：它先停止接受新触发（此后任何触发源 emit 都是空操作）、再**等待在途轮次收尾** ——
        // 否则下面把 webhookChannel / wsManager / historyScope 收掉之后，还在跑的那几个协程会往
        // 已关闭的组件里投递（投递撞上被关掉的 CIO 连接池、投递记录写进已 cancel 的 scope）。
        try { g.serverGraph.notificationDispatcher.shutdown() } catch (e: Exception) { AppLogger.e(tag, "Error shutting down notification dispatcher", e) }
        // Webhook 渠道自带一个 HttpClient（连接池 + CIO 的线程），排在分发器之后：
        // 上一步已经等过在途轮次，此时不会再有人在用这个连接池。
        try { g.serverGraph.webhookChannel.close() } catch (e: Exception) { AppLogger.e(tag, "Error closing webhook channel", e) }
        try { g.serverGraph.wsManager.closeAll() } catch (e: Exception) { AppLogger.e(tag, "Error closing websocket", e) }
        // 推送服务自带 CoroutineScope，不 cancel 的话服务停掉之后排队中的推送还会往已关闭的
        // WebSocketManager 里灌。必须在 wsManager 关闭之后立刻收掉。
        try { g.serverGraph.pushService.shutdown() } catch (e: Exception) { AppLogger.e(tag, "Error shutting down push service", e) }
        try { g.serverGraph.server.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping server", e) }
        // Netty eventLoopGroup 异步关闭，需等待足够时间让 event loop 线程完成清理。
        // 过短会导致 CompletionHandlerException（event loop 线程的完成处理器引用已取消的协程）。
        try { delay(2000) } catch (_: Exception) {}
        // ③ 最后关设备侧连接与其余组件（此时已无人再发请求）
        try { g.network.goformClient.close() } catch (e: Exception) { AppLogger.e(tag, "Error closing goform client", e) }
        try { g.controller.adbController.destroy() } catch (e: Exception) { AppLogger.e(tag, "Error stopping adb", e) }
        try { g.controller.downloadManager.shutdown() } catch (e: Exception) { AppLogger.e(tag, "Error shutting down download manager", e) }
        try { g.controller.tunnelManager.shutdown() } catch (e: Exception) { AppLogger.e(tag, "Error shutting down tunnel manager", e) }
        // 应用列表定时刷新：持有自己的 CoroutineScope，不显式收的话服务停掉后仍每 2 分钟
        // 打 4 条特权 shell（serviceScope 虽会在 onDestroy 被 cancel，但 stopAllComponents 也走
        // 「停止服务」而不销毁 Service 的路径 —— 那条路上 serviceScope 还活着）。
        try { g.serverGraph.appManager.setPeriodicRefreshEnabled(false) } catch (e: Exception) { AppLogger.e(tag, "Error stopping app list refresh", e) }
        // 媒体索引：除了定时刷新，它还自持一个作用域跑「重新扫描」的追赶刷新
        //（那组协程要活过一次 HTTP 请求，不能挂 serviceScope），只能靠 stop() 显式收。
        // 不调它的话，停止服务后最多还会多跑三次整库扫描（+3s/+15s/+45s）。
        try { g.serverGraph.mediaIndexStore.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping media index", e) }
        // 第三方程序：runtime / supervisor / install scope 都必须显式收，否则停止服务后
        // 程序继续跑、看护继续拉起，页面却因服务已停止不可见。
        try { g.programManager.shutdown() } catch (e: Exception) { AppLogger.e(tag, "Error shutting down program manager", e) }
        // ttyd：父进程（core）退出不会带走子进程，必须显式杀，否则孤儿 shell 常驻耗电。
        // 注意顺序在 server.stop() 之后：先把 WS 反代收掉，再杀它背后的进程。
        try { g.serverGraph.ttydRoutes.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping ttyd", e) }
        // 远程屏幕：同样必须显式停止。scrcpy-server 是 adb shell 的子进程，
        // core 退出不会带走它；它的 socket 一断会自行退出，但「停止服务」这条路径上
        // core 还活着，不显式杀就会留下一个仍在编码、仍持有唤醒锁的孤儿。
        try { g.serverGraph.screenSessionManager.shutdownBlocking() } catch (e: Exception) { AppLogger.e(tag, "Error stopping screen session", e) }
        // 关闭 ADB shell 持久会话
        try { com.ufi_axis_core.util.AdbShellExecutor.shutdown() } catch (e: Exception) { AppLogger.e(tag, "Error shutting down ADB shell", e) }
        // 配对存储：同步落一次盘并收掉 lastSeen 的异步 flush 线程。
        // 其 flush 线程是 daemon，进程退出时会被直接终止；落盘若不是原子操作，在该窗口内
        // 退出会留下半截 paired_devices.json —— 下次启动读不出任何设备，手机被判未配对、
        // 清空 token、回到配对页要求重新输入密码。落盘现为原子操作，此处仍须显式收尾：
        // 停止流程必须自行回收该线程。
        try { com.ufi_axis_core.util.PairedDeviceStore.shutdownInstance() } catch (e: Exception) { AppLogger.e(tag, "Error shutting down paired device store", e) }
        // 拦截规则 store：先把内存里累计的命中次数落盘再收协程。
        // `pendingHits` 只在「下次命中且过了 60s」或读规则列表时才写盘，不在这里 flush 的话
        // 最后一批命中数随停机丢掉 —— 而「命中 N 次」是用户判断规则到底有没有生效的唯一线索。
        try { g.controller.smsRuleStore.shutdown() } catch (e: Exception) { AppLogger.e(tag, "Error shutting down sms rule store", e) }
        // 邮件控制器的历史写入 scope：不 cancel 的话停机后排队中的记录写入与裁剪还在打 Room。
        try { g.controller.smsForwardController.shutdown() } catch (e: Exception) { AppLogger.e(tag, "Error shutting down sms forward controller", e) }
        // 重置 ComponentFactory 构建状态，允许服务重启时重新 build
        com.ufi_axis_core.service.ComponentFactory.reset()
    }

    // ═══════════════════════════════════════════════════════════
    // 保活增强
    // ═══════════════════════════════════════════════════════════

    /**
     * 保活增强：提升线程优先级。
     * 不写 /proc/self/oom_score_adj（需要 root）。
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
     * 通过 AlarmManager 定时拉起服务 — 比 START_STICKY 更可靠。
     * 覆盖场景：用户滑动清除、LMK 杀死、系统回收后重启、`POST /api/service/restart`。
     *
     * 必须使用 `getForegroundService`：`getService` 走普通 `startService`，
     * Android 12+ 禁止后台启动普通服务（`ForegroundServiceStartNotAllowedException` / 直接被拒），
     * 而本服务在 `onCreate` 里就 `startForeground`，属于前台服务，必须使用前台启动语义。
     *
     * ## 必须使用**精确**闹钟
     * `setAndAllowWhileIdle`（inexact）通常以"无需 SCHEDULE_EXACT_ALARM 权限"为理由选用，
     * 但 Android 12+ 的「后台启动前台服务」豁免**只给精确闹钟**（`setExact*`）：
     * inexact 闹钟到点时进程处于 cached 状态，`startForegroundService` 会被 AMS 判为后台启动
     * 而抛 `ForegroundServiceStartNotAllowedException`，**异常发生在系统侧，此处的 try/catch
     * 无法捕获**，日志里只会留下一句 "restart scheduled"。且两枚闹钟都是
     * `FLAG_ONE_SHOT`，一旦失败不会再排 —— 这是「重启服务之后彻底失联」的成因之一。
     *
     * 拿不到精确闹钟权限时仍退回 inexact：那种情况下的恢复责任落在 shell 侧
     * （[requestRestart] 派出的 detached `am start-foreground-service`），双保险里至少还有一路。
     */
    private fun scheduleServiceRestart() {
        try {
            val intent = Intent(this, BackendService::class.java).apply {
                action = ACTION_RESTART
            }
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else true

            fun schedule(requestCode: Int, delayMs: Long) {
                val pi = PendingIntent.getForegroundService(
                    this, requestCode, intent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = SystemClock.elapsedRealtime() + delayMs
                if (canExact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi
                    )
                }
            }

            schedule(0, RESTART_DELAY_MS)
            // 备份闹钟（不同 requestCode，否则会覆盖上面那枚）：主闹钟被系统吞掉时兜底。
            // 重复拉起是安全的 —— onStartCommand 的 isRunning 守卫已改为同步置位。
            schedule(1, RESTART_DELAY_MS * 3)
            AppLogger.i(
                tag,
                "Service restart scheduled in ${RESTART_DELAY_MS / 1000}s " +
                    "(backup ${RESTART_DELAY_MS * 3 / 1000}s, exact=$canExact)"
            )
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
     * 两条设计约束：
     * 1. 判定与部署统一收敛到 [KeepAliveWatchdog]（本处与 `BootReceiver` 不得各写一份）。
     *    不能把「锁目录存在」当成「看门狗在跑」：锁在 SIGKILL / 掉电后残留，
     *    看门狗被杀一次就再也起不来，日志里仍写着 already running。
     * 2. 必须包含 [KeepAliveWatchdog.SELF_HEAL_INTERVAL_MS] 的复查，即对看门狗本身的监护。
     *    只在服务启动时部署一次时，看门狗被 phantom process killer 带走后要等到下一次
     *    Core 启动才会重建，而那时已没有组件负责拉起 Core。
     *    互相看护（Core 看 shell、shell 看 Core）才构成闭环。复查只做一次文件 / proc 读取，
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
