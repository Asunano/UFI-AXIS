package com.ufi_axis.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.data.notification.GuardScheduler
import com.ufi_axis.data.notification.INotificationConfigService
import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.data.notification.NotifyDispatchReceiver
import com.ufi_axis.data.notification.NotifyPrefs
import com.ufi_axis.data.repository.ConnectionState
import com.ufi_axis.data.repository.WebSocketRepository
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import com.ufi_axis.connection.ConnectionBootstrap
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.ufi_axis.data.model.AlertRecord

/**
 * :ufi_notify 进程前台保活服务（独立进程架构 v2 Phase 1/3）。
 *
 * 职责：
 * - onStartCommand 即 startForeground（foregroundServiceType="dataSync"，常驻通知「通知守护」），
 *   紧接着**自查闸门** [shouldRun]，不满足就撤回通知并自停；
 *   放在 onStartCommand 而不是 onCreate：纯 bind 也会触发 onCreate（「后台守护」页一进页面就绑），
 *   在那里发通知等于「开着页面就凭空多一条常驻通知」，且 onCreate 早于快照落地（见其内注释）；
 * - 进程内初始化 [NotificationCenter] + [GuardScheduler]（GuardScheduler 构造期会自动
 *   按 prefs 幂等补 enqueue 周期任务，进程重启后 WorkManager 调度不丢失）；
 * - 实现 AIDL [INotificationConfigService]：UI 进程跨进程同步开关/间隔/免打扰并触发重调度。
 *
 * 注意：
 * - 本服务运行在 `:ufi_notify` 进程（Manifest 声明），不加载 Compose / ViewModel；
 * - 启动入口统一走 [startKeepAlive]（前台服务，startForegroundService），闸门就在那里；
 * - 关闭走 [stopKeepAlive]（stopService）或 AIDL `setForegroundKeepAlive(false)`
 *   （绑定中时必须走后者，否则服务不销毁、常驻通知不消失）；
 * - 常驻通知是**用户可见代价**，必须由「前台服务保活」开关授权 —— 别再让任何路径
 *   绕过 [shouldRun] 直接 `startForegroundService`（2026-09-05 回归的成因）。
 */
class NotifyService : Service() {

    private lateinit var notificationCenter: NotificationCenter
    private lateinit var guardScheduler: GuardScheduler
    private var webSocketRepository: WebSocketRepository? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var alertPollingJob: Job? = null

    /**
     * onCreate 建好的 API 客户端（用当时的 prefs 参数）。
     *
     * 兜底轮询的启动被推迟到 [onStartCommand] 的闸门之后（见 [startBackgroundChannelsIfNeeded]），
     * 那时不该再重建一遍 Retrofit，所以在这里留一份引用；连接参数变更走
     * [applyConnectionChange] 的 standalone 客户端并同步刷新本字段。
     */
    private var backgroundApi: com.ufi_axis.data.api.UfiAxisApi? = null

    /** 已消费到的最新告警时间戳（ms），推送与轮询共用，用于增量拉取。 */
    private var lastSeenAlertTs: Long = 0L

    /** 当前使用的连接参数（由主进程经 Intent 通报更新，见 [applyConnectionChange]）。 */
    private var connBaseUrl: String = ""
    private var connToken: String = ""

    /** AIDL 实现：方法运行在 :ufi_notify 进程 binder 线程池，均为轻量 prefs 写 + WorkManager 调度。 */
    private val configBinder = object : INotificationConfigService.Stub() {
        override fun setGuardEnabled(enabled: Boolean) {
            // GuardScheduler.setEnabled 内部：写 guard_enabled + enqueue/cancel 周期任务
            guardScheduler.setEnabled(enabled)
        }

        override fun setGuardIntervalMinutes(minutes: Int) {
            // GuardScheduler.setInterval 内部：钳制 15-60 + 写 guard_interval_minutes + 重调度
            guardScheduler.setInterval(minutes)
        }

        override fun setDndEnabled(enabled: Boolean) {
            notificationCenter.setDndEnabled(enabled)
        }

        override fun setDndWindow(startHour: Int, endHour: Int) {
            // NotificationCenter.setDndWindow 内部钳到 0..23，越界值不会写进 prefs
            notificationCenter.setDndWindow(startHour, endHour)
        }

        /**
         * 前台保活开关的**跨进程即时生效**通道。
         *
         * 为什么不能只靠 `stopService`：本进程读开关时优先读自己那份 `mirror_` 副本，
         * 主进程改了共享文件它看不到（`MODE_PRIVATE` 无跨进程 reload）；而「后台守护」页
         * 在页面内一直 `BIND_AUTO_CREATE` 绑着本服务 —— `stopService` 期间服务不会销毁，
         * 那条常驻通知会一直挂到用户离开页面。所以关开关必须显式把新值送进来，
         * 由服务当场撤通知 + 自停。
         */
        override fun setForegroundKeepAlive(enabled: Boolean) {
            NotifyPrefs.putSwitch(
                this@NotifyService, NotificationCenter.KEY_GUARD_FOREGROUND_KEEPALIVE, enabled
            )
            if (!shouldRun(this@NotifyService)) {
                DebugLog.i(TAG, "AIDL setForegroundKeepAlive($enabled)：闸门已关，撤回常驻通知并自停")
                quitWithoutNotification()
            }
        }

        override fun reloadConfig() {
            guardScheduler.refresh()
            val current = guardScheduler.state.value
            if (current.enabled) {
                // 幂等重调度（UPDATE）：进程重启 / 外部 prefs 写入后恢复周期任务
                guardScheduler.setInterval(current.intervalMinutes)
            } else {
                guardScheduler.cancel()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // ★ 这里**故意不** startForeground（2026-09-05）。
        //
        // 常驻通知只在 onStartCommand 里发（那里能拿到 Intent 携带的最新开关快照），原因有二：
        // 1. `onCreate` 也会被**纯 bind** 触发（`BIND_AUTO_CREATE`，「后台守护」页一进页面就绑）。
        //    在这里发通知 = 用户只要打开那一页就凭空多一条「通知守护」，与保活开关无关；
        // 2. 本进程读开关优先读自己那份 `mirror_` 副本，而 `onCreate` 早于 `onStartCommand`
        //    ——此刻镜像里可能还是上一次关闭时写下的 false。若在 onCreate 就据此自停，
        //    「用户重新打开保活」这条最常见的路径会失败：页面此刻正绑着服务，
        //    `startForegroundService` 只会触发 onStartCommand，而实例早已判死。
        //
        // 5s 契约仍然满足：`startForegroundService` 必然紧接着回调 onStartCommand（同一轮主线程
        // 消息循环内，毫秒级），在那里第一件事就是 [enterForeground]。
        //
        // ★ 同理，WebSocket 与兜底轮询这两个**后台副作用**也必须留在闸门之后（见
        //   [startBackgroundChannelsIfNeeded]）：纯 bind 只触发 onCreate、不触发 onStartCommand，
        //   在这里无条件启动等于「保活开关关着，:ufi_notify 照样建 WS + 每 60s 醒一次拉告警
        //   （每轮还 acquire 一把 10s WakeLock）」—— 那是用户没有授权的耗电。
        //   这里只做「不产生副作用」的初始化：通知中心 + 连接参数 + WorkManager 调度器。
        notificationCenter = NotificationCenter(this)

        val prefs = AppPreferences(this)
        connBaseUrl = prefs.baseUrl
        connToken = prefs.token
        val api = RetrofitClient.getApiService(prefs)
        backgroundApi = api
        guardScheduler = GuardScheduler(this, api)
        DebugLog.i(TAG, "NotifyService onCreate (process: ufi_notify)")
    }

    /**
     * 启动后台副作用通道（WebSocket 推送 + 兜底轮询）—— **只允许从闸门之后调用**。
     *
     * 幂等：`onStartCommand` 可能被反复回调（每次 startForegroundService / START_STICKY 重建），
     * 重复启动会叠加协程。复用现有两个字段判活即可，不引入额外状态：
     * - WS：`webSocketRepository != null` 说明已建过（断线重连由它自己的退避逻辑负责）；
     * - 轮询：`alertPollingJob?.isActive == true` 说明循环还在跑。
     */
    private fun startBackgroundChannelsIfNeeded() {
        if (webSocketRepository == null) {
            initWebSocket(connBaseUrl, connToken)
        }
        if (alertPollingJob?.isActive != true) {
            val api = backgroundApi ?: RetrofitClient.getApiService(AppPreferences(this)).also {
                backgroundApi = it
            }
            startAlertPolling(api)
        }
    }

    /**
     * 应用主进程通报的最新连接参数（重新配对后 token 轮换 / 改服务端 IP 端口）。
     *
     * 本进程的 `SharedPreferences` 读不到主进程刚写入的值（`MODE_PRIVATE` 不跨进程 reload），
     * 因此参数必须由 Intent 显式带过来，否则 WS 与轮询会一直用旧 token → 401/444 → 通知静默失效。
     */
    private fun applyConnectionChange(baseUrl: String, token: String) {
        if (baseUrl.isBlank()) return
        if (baseUrl == connBaseUrl && token == connToken) return
        DebugLog.i(TAG, "连接参数变更，重建 WS 与轮询通道")
        connBaseUrl = baseUrl
        connToken = token
        webSocketRepository?.updateConfig(ConnectionBootstrap.rewriteWsUrl(baseUrl), token)
        val api = RetrofitClient.createStandaloneApiService(baseUrl, token)
        backgroundApi = api
        startAlertPolling(api)
    }

    private fun initWebSocket(baseUrl: String, token: String) {
        val wsUrl = ConnectionBootstrap.rewriteWsUrl(baseUrl)
        val wsRepo = WebSocketRepository(wsUrl, token)
        webSocketRepository = wsRepo
        
        // 绑定网络恢复重连
        wsRepo.bindNetworkRecovery(this)
        // 只订阅告警相关频道：core 的采集循环会在有 WS 连接时从 60s 提速到 3s，
        // 后台进程订阅 cpu/memory/traffic 会让设备端 24h 处于高频采集，纯属浪费。
        wsRepo.connect(serviceScope, WebSocketRepository.NOTIFY_TOPICS)

        serviceScope.launch {
            wsRepo.messages.collect { message ->
                when (message.type) {
                    "alert", "notification" -> {
                        // 收到推送，立即同步到通知中心
                        try {
                            val dataObj = message.data as? JsonObject
                            // 2026-09-04：短信 / 验证码不走告警管线。
                            // core 的 DataScheduler 在"发现新短信"的边沿上推一条 type=sms|verification
                            // 的 notification（此前 app 只有"进短信页轮询"和 15/30 分钟兜底两条路，
                            // 表现为"只有打开短信页才弹通知"）。这里必须先分流：
                            // 否则它们会被当成 AlertRecord 走 maybeNotifyNewAlerts —— 受告警总闸约束、
                            // 用告警 channel、还会推进告警游标把真告警吞掉。
                            val pushType = dataObj?.get("type")?.jsonPrimitive?.content
                            if (pushType == "sms" || pushType == "verification") {
                                val extra = dataObj?.get("extra") as? JsonObject
                                val sender = extra?.get("sender")?.jsonPrimitive?.content ?: ""
                                if (pushType == "verification") {
                                    val code = extra?.get("code")?.jsonPrimitive?.content ?: ""
                                    if (code.isNotEmpty()) notificationCenter.notifyVerificationCode(sender, code)
                                } else {
                                    val snippet = extra?.get("snippet")?.jsonPrimitive?.content ?: ""
                                    notificationCenter.notifyNewSms(sender, snippet)
                                }
                                // 让前台 UI（若存活）刷新短信列表
                                sendAlertBroadcast()
                                return@collect
                            }
                            if (dataObj != null && dataObj.containsKey("message")) {
                                // core 的 WebSocketPushService 把 id/value/threshold 放在 data.extra 里，
                                // 顶层只有 type/level/title/message/timestamp。
                                val extra = dataObj["extra"] as? JsonObject
                                val ts = dataObj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                                    ?: System.currentTimeMillis()
                                val alert = AlertRecord(
                                    id = extra?.get("id")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                                    type = dataObj["type"]?.jsonPrimitive?.content ?: "unknown",
                                    level = dataObj["level"]?.jsonPrimitive?.content ?: "info",
                                    message = dataObj["message"]?.jsonPrimitive?.content ?: "",
                                    value = extra?.get("value")?.jsonPrimitive?.content ?: "",
                                    threshold = extra?.get("threshold")?.jsonPrimitive?.content ?: "",
                                    acknowledged = false,
                                    timestamp = ts
                                )
                                // 推送已消费到这个时间点，兜底轮询从此处继续增量拉取
                                if (ts > lastSeenAlertTs) lastSeenAlertTs = ts
                                // 后台进程差异检测并发送系统通知
                                notificationCenter.maybeNotifyNewAlerts(listOf(alert))
                                // 发送广播让前台 UI 刷新（如果前台存活）
                                sendAlertBroadcast()
                            }
                        } catch (e: Exception) {
                            DebugLog.e(TAG, "Parse pushed alert in background failed", e)
                        }
                    }
                }
            }
        }
    }

    /**
     * WebSocket 的兜底轮询（不是主数据源）。
     *
     * - WS 已连接：只需极低频对账（[POLL_INTERVAL_WS_OK_MS]），防止推送丢帧；
     * - WS 断开：退回 [POLL_INTERVAL_WS_DOWN_MS] 保证可用性；
     * - 用 `start_time` 做增量拉取，而不是每轮全量拉最近 N 条。
     */
    private fun startAlertPolling(api: com.ufi_axis.data.api.UfiAxisApi) {
        alertPollingJob?.cancel()
        alertPollingJob = serviceScope.launch {
            // 首次启动延迟一下，避开启动峰值
            delay(5000)

            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            // WakeLock 必须是**协程局部**的（2026-09-05）：[applyConnectionChange] 只 cancel()
            // 不 join() 旧 job，新旧两个循环会短暂并存。若共用一个服务级字段，两轮会先后覆盖它，
            // 于是旧 job 的 finally 释放的是**新 job 刚 acquire 的那把**（setReferenceCounted(false)
            // 是全量释放），而旧的那把只能等 10s 超时自己掉。各自持有各自的，谁 acquire 谁 release。
            val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "UfiAxis:AlertPolling").apply {
                setReferenceCounted(false)
            }

            try {
                while (isActive) {
                    val wsConnected = webSocketRepository?.connectionState?.value == ConnectionState.CONNECTED
                    try {
                        // 轮询期间短暂持有 WakeLock，确保网络请求能发出去
                        wl.acquire(10_000L)

                        // 增量拉取：只要比已知最新告警更新的记录（首轮 lastSeenAlertTs=0 → 拉最近 20 条建立基线）
                        val resp = if (lastSeenAlertTs > 0L) {
                            api.getAlertList(limit = 50, startTimeMs = lastSeenAlertTs)
                        } else {
                            api.getAlertList(limit = 20)
                        }
                        notificationCenter.maybeNotifyNewAlerts(resp.alerts)
                        resp.alerts.maxOfOrNull { it.timestamp }?.let { if (it > lastSeenAlertTs) lastSeenAlertTs = it }

                        // 如果拉取到了告警，发送全局广播通知 UI 进程刷新（用于显示应用内 banner/Toast）。
                        // 差异检测去重逻辑仍由 UI 进程的 NotificationCenter.maybeNotifyNewAlerts 保证。
                        if (resp.alerts.isNotEmpty()) {
                            sendAlertBroadcast()
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        DebugLog.w(TAG, "Background alert polling failed: ${e.message}")
                    } finally {
                        try { if (wl.isHeld) wl.release() } catch (_: Exception) {}
                    }
                    delay(if (wsConnected) POLL_INTERVAL_WS_OK_MS else POLL_INTERVAL_WS_DOWN_MS)
                }
            } finally {
                // 取消发生在 acquire 与内层 finally 之间时的兜底（否则要等 10s 超时）
                try { if (wl.isHeld) wl.release() } catch (_: Exception) {}
            }
        }
    }

    /** 发送全局广播，通知 UI 进程有新告警（用于显示应用内 banner/Toast）。 */
    private fun sendAlertBroadcast() {
        val intent = Intent(NotificationCenter.ACTION_NEW_ALERTS).apply {
            // 仅在主进程运行且在前台时，UI 进程会接收此广播并触发 loadAlerts
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder = configBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ★ 顺序铁律 ①：第一件事就是 enterForeground，满足系统「startForegroundService 之后必须
        //   5s 内 startForeground」的契约。哪怕紧接着就要撤掉，也得先满足契约，
        //   否则抛 ForegroundServiceDidNotStartInTimeException。
        //   try/catch 不改变这个顺序：Android 12+ 的后台 FGS 限制
        //   （ForegroundServiceStartNotAllowedException）与 14+ 的类型/权限校验
        //   （InvalidForegroundServiceTypeException）都会**抛在本回调里**，不接就是 :ufi_notify
        //   当场崩溃；再叠上 START_STICKY 的反复重建就是崩溃循环。这里降级为自停，
        //   通知能力仍有 WorkManager 周期任务兜底。
        try {
            enterForeground()
        } catch (e: Exception) {
            DebugLog.w(TAG, "enterForeground 失败（FGS 启动受限/类型不匹配），放弃保活: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        // ★ 顺序铁律 ②：先让 Intent 里的开关快照落地，再判闸门 —— 本进程读的是自己那份
        //   mirror_ 副本，快照可能刚把保活开关刷成 false（或刚刷成 true）。
        NotifyPrefs.applySnapshot(this, intent?.getStringExtra(NotifyDispatchReceiver.EXTRA_SWITCH_SNAPSHOT))
        // 自查自停：START_STICKY 的系统重建（intent 为 null，无从下发快照）与任何绕过
        // startKeepAlive 的启动路径都不查开关，服务只能自己兜住。
        if (!shouldRun(this)) {
            DebugLog.i(TAG, "自查未通过（「前台服务保活」已关），撤回常驻通知并自停")
            quitWithoutNotification()
            return START_NOT_STICKY
        }
        // ★ 顺序铁律 ③：后台副作用（WS + 兜底轮询）只能在闸门之后启动，且必须幂等 ——
        //   放在 onCreate 会被纯 bind 触发（详见 onCreate 内注释）。
        startBackgroundChannelsIfNeeded()
        // 主进程通报的连接参数变更（重新配对 / 改 IP 端口）
        val baseUrl = intent?.getStringExtra(NotifyDispatchReceiver.EXTRA_BASE_URL)
        if (!baseUrl.isNullOrBlank()) {
            applyConnectionChange(baseUrl, intent.getStringExtra(NotifyDispatchReceiver.EXTRA_TOKEN) ?: "")
        }
        // START_STICKY：进程被系统回收后由系统重建服务并回调 onCreate + onStartCommand(null)
        //（重建路径不带 Intent、也不查开关，由上面那次自查兜住）
        return START_STICKY
    }

    /**
     * 撤回常驻通知并结束自己 —— 关闭保活时**立即**回收状态栏那条通知的唯一动作。
     *
     * 为什么不能只靠 `stopSelf()` / 外部 `stopService()`：
     * - 只要还有客户端处于 `BIND_AUTO_CREATE` 绑定中（「后台守护」页在页面内一直绑着），
     *   `stopService` 不会销毁服务，`onDestroy` 也就不会跑，通知会一直挂到用户离开那一页；
     * - 即使服务真的被销毁，部分国产 ROM 上 FGS 通知不会随进程/服务消失，
     *   必须显式 `STOP_FOREGROUND_REMOVE`。
     *
     * 同理，后台副作用（WS + 兜底轮询）也**不能等 onDestroy 清理** —— 绑定期间它压根不会跑，
     * 于是「自查未通过」之后 WebSocket 还连着、轮询还在每 60s 醒一次 acquire 一把 WakeLock。
     * 这里就地停掉：取消 scope 的子协程（不是 cancel 整个 serviceJob —— 服务实例在绑定期间会存活，
     * 用户随后重新打开保活时还要靠这个 scope 把通道拉起来，整个 job 被 cancel 后 launch 会静默失效）
     * 并断开 WS，同时把引用清空，让 [startBackgroundChannelsIfNeeded] 之后能重建。
     */
    private fun quitWithoutNotification() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            DebugLog.w(TAG, "stopForeground 失败: ${e.message}")
        }
        serviceScope.coroutineContext.cancelChildren()
        alertPollingJob = null
        webSocketRepository?.disconnect()
        webSocketRepository?.unbindNetworkRecovery()
        webSocketRepository = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 显式撤通知：见 [quitWithoutNotification] 的第二条理由（ROM 残留）
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            DebugLog.w(TAG, "onDestroy stopForeground 失败: ${e.message}")
        }
        serviceJob.cancel()
        webSocketRepository?.disconnect()
        webSocketRepository?.unbindNetworkRecovery()
        DebugLog.i(TAG, "NotifyService onDestroy")
    }

    /**
     * 创建保活 channel + 进入前台（startForeground 必须 5s 内完成，否则系统抛错）。
     *
     * **会抛**：Android 12+ 的后台启动限制、14+ 的 foregroundServiceType / 权限校验都在这里抛
     * （ForegroundServiceStartNotAllowedException / InvalidForegroundServiceTypeException），
     * 调用方（[onStartCommand]）必须接住，见那里的注释。
     */
    private fun enterForeground() {
        createKeepAliveChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_KEEPALIVE)
            .setSmallIcon(android.R.drawable.stat_notify_error)  // 系统图标（无 res 依赖）
            .setContentTitle("通知守护")
            .setContentText("后台通知守护运行中")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        // minSdk=31（>= Q），3 参重载可用；foregroundServiceType 与 Manifest 声明一致
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** 保活专属 channel（IMPORTANCE_LOW：无声无横幅，仅常驻）。 */
    private fun createKeepAliveChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_KEEPALIVE, "通知守护保活", NotificationManager.IMPORTANCE_LOW).apply {
                description = "后台通知守护前台服务常驻通知（低优先级，无声音）"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val TAG = "NotifyService"
        private const val CHANNEL_KEEPALIVE = "ufi_notify_keepalive"
        private const val NOTIFICATION_ID = 9001

        /** WS 正常时的对账间隔（推送是主通道，轮询只防丢帧）。 */
        private const val POLL_INTERVAL_WS_OK_MS = 5 * 60_000L

        /** WS 断开时的兜底轮询间隔。 */
        private const val POLL_INTERVAL_WS_DOWN_MS = 60_000L

        /**
         * 启动 `:ufi_notify` 进程前台保活服务 —— **闸门收口在此**。
         *
         * 所有入口（Application 冷启动 / 开机广播 / 覆盖安装 / 设置页开关 / core 配置回显）
         * 都必须从这里过，调用点不再各自复查开关：历史上正是「每个入口各自复查」漏掉了
         * 保活键（见 [shouldRun] 的 KDoc）。
         */
        fun startKeepAlive(context: Context) {
            if (!shouldRun(context)) {
                DebugLog.i(TAG, "startKeepAlive 跳过：「前台服务保活」未开启")
                return
            }
            val intent = Intent(context, NotifyService::class.java)
                // 顺带下发开关快照：:ufi_notify 读不到主进程刚写的开关（见 NotifyPrefs）
                .putExtra(NotifyDispatchReceiver.EXTRA_SWITCH_SNAPSHOT, NotifyPrefs.snapshot(context))
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // 后台 FGS 启动受限（Android 12+/14+）或权限缺失：静默失败，WorkManager 仍兜底
                DebugLog.w(TAG, "startKeepAlive 失败: ${e.message}")
            }
        }

        /**
         * 守护服务是否应处于运行状态 —— **所有** `startForeground` 路径的唯一闸门。
         *
         * 判据只有 `guard_foreground_keepalive_enabled`（「后台守护 → 前台服务保活」）一个键：
         * 那条常驻通知是用户可见代价，必须由这个开关授权，否则就是「假开关」。
         * 实现在 [NotifyPrefs.keepAliveShouldRun] → [com.ufi_axis.data.notification.KeepAliveGate.shouldRun]，
         * 与 `NotificationConfigClient.startKeepAlive` 共用同一份。
         *
         * **「系统通知推送」（`alert_notification_enabled`）不参与**：本进程是短信 / 验证码
         * 推送的唯一订阅方，那两条通知只看 `sms_notification_enabled`（默认 true），
         * 与告警总闸无关；把总闸 AND 进来会让「只开保活」的用户既没有常驻通知、
         * 也收不到实时短信（2026-09-05 上午的回归，见 [KeepAliveGate] 的 KDoc）。
         *
         * 为什么 2026-09-05 才补保活键：[NotifyBootReceiver] 那次口径变更（其 KDoc 记载
         * 「此前要求 guard_enabled && guard_foreground_keepalive_enabled 双开关同时为 true，
         * 两者默认都是 false，导致开机自启实际从不生效」）**只该放宽 `guard_enabled`** ——
         * 它管的是 WorkManager 周期任务，与 FGS 无关；却把保活开关一起摘了，
         * 于是「用户没开保活，通知栏仍有一条固定通知」。
         */
        fun shouldRun(context: Context): Boolean = NotifyPrefs.keepAliveShouldRun(context)

        /**
         * Application.onCreate / 开机 / 覆盖安装 / 开关打开的统一入口。
         *
         * 闸门已收口到 [startKeepAlive] 内部，这里只是保留一个语义明确的名字：
         * 「按开关状态决定要不要起」。
         */
        fun startIfEnabled(context: Context) {
            startKeepAlive(context)
        }

        /**
         * 停止 :ufi_notify 进程前台保活服务（仅停 FGS，周期 Worker 由 WorkManager 负责）。
         *
         * 与 `NotificationConfigClient.stopKeepAlive` 同口径：先把开关快照落进那个进程的
         * `mirror_`（接收器能把进程拉起），否则「进程不在线时关开关」会留下陈旧的 true，
         * 之后任何启动路径都会让常驻通知复活。
         */
        fun stopKeepAlive(context: Context) {
            NotifyDispatchReceiver.dispatchSwitchSnapshot(context)
            try {
                context.stopService(Intent(context, NotifyService::class.java))
            } catch (e: Exception) {
                DebugLog.w(TAG, "stopKeepAlive 失败: ${e.message}")
            }
        }
    }
}
