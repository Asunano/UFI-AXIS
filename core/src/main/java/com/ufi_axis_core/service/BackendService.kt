package com.ufi_axis_core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ufi_axis_core.alert.AlertEngine
import com.ufi_axis_core.api.middleware.AuthMiddleware
import com.ufi_axis_core.api.routes.*
import com.ufi_axis_core.api.websocket.WebSocketManager
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.controller.network.NetworkController
import com.ufi_axis_core.controller.sim.SimController
import com.ufi_axis_core.controller.system.SystemController
import com.ufi_axis_core.core.database.AppDatabase
import com.ufi_axis_core.core.scheduler.DataScheduler
import com.ufi_axis_core.core.server.HttpServer
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.AssetExtractor
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.*

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
class BackendService : Service() {

    private val tag = "BackendService"
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "ufi_axis_core_service"
        const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning: Boolean = false

        fun start(context: Context) {
            // minSdk=31, startForegroundService 始终可用
            context.startForegroundService(Intent(context, BackendService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackendService::class.java))
        }
    }

    // 组件引用
    private var httpServer: HttpServer? = null
    private var dataScheduler: DataScheduler? = null
    private var alertEngine: AlertEngine? = null
    private var webSocketManager: WebSocketManager? = null
    private var goformClient: GoformClient? = null
    private var adbController: com.ufi_axis_core.controller.adb.AdbController? = null
    private var taskScheduler: com.ufi_axis_core.core.scheduler.TaskScheduler? = null
    private var batteryNotifier: BatteryNotifier? = null
    private var downloadManager: com.ufi_axis_core.controller.system.DownloadManager? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(tag, "Service creating...")

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i(tag, "Service starting...")

        serviceScope.launch {
            isRunning = true
            initializeComponents()
        }

        return START_STICKY  // 系统杀死后自动重启
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        AppLogger.i(tag, "Service destroying...")
        // 使用 runBlocking 确保清理操作完成后再继续销毁
        // 不能使用 serviceScope.launch，因为 serviceScope 即将被 cancel
        runBlocking {
            stopAllComponents()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun initializeComponents() {
        try {
            AppLogger.i(tag, "Initializing components...")
            updateNotification("正在初始化组件...")

            val settings = AppSettings.getInstance(this)
            val port = settings.port
            AppLogger.setDebugMode(settings.debugMode)

            // 0. 提取 shell 资产（二进制 + 脚本）
            try {
                AssetExtractor.extractAll(applicationContext)
                AppLogger.i(tag, "Shell assets extracted")
            } catch (e: Exception) {
                AppLogger.w(tag, "Asset extraction failed: ${e.message}")
            }

            // 1. 数据库
            val database = AppDatabase.getInstance(this)
            AppLogger.i(tag, "Database initialized")

            // 2. AT 通道（加超时保护，避免 AT 初始化卡住阻塞整个服务启动）
            val atChannel = ATChannel(applicationContext)
            val atConnected = try {
                kotlinx.coroutines.withTimeoutOrNull(10_000L) { atChannel.init() } ?: false
            } catch (e: Exception) {
                AppLogger.e(tag, "AT channel init exception", e); false
            }
            AppLogger.i(tag, "AT channel: ${if (atConnected) "connected" else "not available"}")

            // 3. 采集器
            val systemCollector = SystemCollector(this)
            val telephonyCollector = TelephonyCollector(this)

            // 4. 控制器 - 使用配置的 IP、端口和密码
            val goformIp = settings.goformIp.ifBlank { getDeviceGatewayIp() }
            val goform = GoformClient(
                deviceIp = goformIp,
                port = settings.goformPort,
                password = settings.goformPassword
            )
            goformClient = goform
            val networkController = NetworkController(this, atChannel, goform)
            val simController = SimController(this, atChannel, database, goform)
            val systemController = SystemController(goform)

            // 5. WebSocket 管理器（传入 token 用于认证）
            val wsManager = WebSocketManager(expectedToken = settings.token)
            webSocketManager = wsManager

            // 6. 告警引擎
            val alert = AlertEngine(database.alertDao(), wsManager)
            alertEngine = alert

            // 6.5 共享组件：动态线程池 + QoS 中间件
            val dynamicThreadPool = com.ufi_axis_core.util.DynamicThreadPool()
            val qosMiddleware = com.ufi_axis_core.api.middleware.QoSMiddleware()

            // 7. 数据采集调度器（注入告警引擎 + 共享线程池）
            val scheduler = DataScheduler(
                systemCollector, telephonyCollector, atChannel, database, wsManager, goform,
                alertEngine = alert,
                dynamicThreadPool = dynamicThreadPool,
                qosMiddleware = qosMiddleware
            )
            dataScheduler = scheduler

            // 8. 认证中间件（从配置读取 Token/Secret/频率限制）
            val authMiddleware = AuthMiddleware(settings)

            // 9. 新控制器
            val ac = com.ufi_axis_core.controller.adb.AdbController(goform).also { adbController = it }

            // 10. API 路由
            val deviceRoutes = DeviceRoutes(systemCollector, telephonyCollector, atChannel, goform, systemController)
            val networkRoutes = NetworkRoutes(telephonyCollector, atChannel, networkController, database, goform, scheduler)
            val systemRoutes = SystemRoutes(systemCollector, scheduler, database, atChannel, applicationContext)
            val trafficRoutes = TrafficRoutes(scheduler, database)

            // 电池事件通知
            val smsFwdForBattery = com.ufi_axis_core.controller.sms.SmsForwardController(this, systemCollector)
            val notifier = BatteryNotifier(
                onLowBattery = { pct ->
                    serviceScope.launch {
                        try { smsFwdForBattery.forwardSms("SYSTEM", "低电量警告: ${pct}%", System.currentTimeMillis()) } catch (_: Exception) {}
                    }
                },
                onVeryLowBattery = { pct ->
                    serviceScope.launch {
                        try { smsFwdForBattery.forwardSms("SYSTEM", "极低电量警告: ${pct}%", System.currentTimeMillis()) } catch (_: Exception) {}
                    }
                },
                onFullBattery = {
                    serviceScope.launch {
                        try { smsFwdForBattery.forwardSms("SYSTEM", "电池已充满", System.currentTimeMillis()) } catch (_: Exception) {}
                    }
                },
                onChargeStart = {
                    AppLogger.d(tag, "Charging started")
                }
            )
            batteryNotifier = notifier

            // 高级工具路由
            val advancedRoutes = AdvancedRoutes(applicationContext)
            val simRoutes = SimRoutes(simController, database, goform)
            val atRoutes = ATRoutes(atChannel)
            val alertRoutes = AlertRoutes(alert)
            val wifiRoutes = WifiRoutes(goform, networkController)
            val configRoutes = ConfigRoutes(settings)
            val appManager = com.ufi_axis_core.controller.system.AppManager()
            val appRoutes = AppRoutes(appManager)
            val shellRoutes = ShellRoutes()
            val fileRoutes = com.ufi_axis_core.api.routes.FileRoutes()
            val smsController = com.ufi_axis_core.controller.sms.SmsController(this, goform)
            val rootSmsRoutes = RootSmsRoutes(smsController)
            val adbRoutes = com.ufi_axis_core.api.routes.AdbRoutes(ac)
            val proxyRoutes = com.ufi_axis_core.api.routes.ProxyRoutes(settings.goformIp.ifBlank { getDeviceGatewayIp() }, settings.goformPort)
            val smsForwardController = com.ufi_axis_core.controller.sms.SmsForwardController(this, systemCollector)
            val smsForwardRoutes = com.ufi_axis_core.api.routes.SmsForwardRoutes(smsForwardController)
            val ts = com.ufi_axis_core.core.scheduler.TaskScheduler(this).also { taskScheduler = it }
            val taskRoutes = com.ufi_axis_core.api.routes.TaskRoutes(ts)
            val speedTestRoutes = com.ufi_axis_core.api.routes.SpeedTestRoutes()
            val debugLogRoutes = com.ufi_axis_core.api.routes.DebugLogRoutes()
            val qosRoutes = com.ufi_axis_core.api.routes.QoSRoutes(qosMiddleware, dynamicThreadPool)
            val monitorRoutes = com.ufi_axis_core.api.routes.MonitorRoutes(database)
            val dm = com.ufi_axis_core.controller.system.DownloadManager(applicationContext)
            downloadManager = dm
            // 复用 DataScheduler 已采集的传感器缓存，避免 DownloadManager 重复 shell 调用
            dataScheduler?.let { dm.setDataScheduler(it) }
            val downloadRoutes = com.ufi_axis_core.api.routes.DownloadRoutes(dm)

            // 11. HTTP Server
            val server = HttpServer(
                port = port,
                authMiddleware = authMiddleware,
                qosMiddleware = qosMiddleware,
                webSocketManager = wsManager,
                deviceRoutes = deviceRoutes,
                networkRoutes = networkRoutes,
                systemRoutes = systemRoutes,
                trafficRoutes = trafficRoutes,
                simRoutes = simRoutes,
                atRoutes = atRoutes,
                alertRoutes = alertRoutes,
                wifiRoutes = wifiRoutes,
                configRoutes = configRoutes,
                appRoutes = appRoutes,
                shellRoutes = shellRoutes,
                fileRoutes = fileRoutes,
                rootSmsRoutes = rootSmsRoutes,
                adbRoutes = adbRoutes,
                proxyRoutes = proxyRoutes,
                smsForwardRoutes = smsForwardRoutes,
                taskRoutes = taskRoutes,
                speedTestRoutes = speedTestRoutes,
                debugLogRoutes = debugLogRoutes,
                advancedRoutes = advancedRoutes,
                qosRoutes = qosRoutes,
                monitorRoutes = monitorRoutes,
                downloadRoutes = downloadRoutes
            )
            httpServer = server

            // 启动后台短信轮询（参考项目 SmsPoll: 8秒间隔，检测最新短信+时间窗口+去重）
            val smsPollJob = serviceScope.launch {
                val smsForwardCtl = smsForwardController
                val smsCtl = com.ufi_axis_core.controller.sms.SmsController(this@BackendService, goform)
                var lastForwardedId: Long = -1
                var pollCount = 0
                // 首次轮询前记录配置状态
                val initCfg = smsForwardCtl.loadConfig()
                AppLogger.i(tag, "SMS poll init: enabled=${initCfg.enabled}, method=${initCfg.method}, smtpHost=${initCfg.smtpHost.takeIf { it.isNotBlank() } ?: "(empty)"}")
                while (isActive) {
                    delay(8_000L)
                    pollCount++
                    try {
                        val cfg = smsForwardCtl.loadConfig()
                        if (!cfg.enabled) {
                            // 每 60 次(约 8 分钟)静默提醒一次
                            if (pollCount % 60 == 1) AppLogger.d(tag, "SMS poll #$pollCount: forwarding disabled, skipping")
                            continue
                        }
                        val latest = smsCtl.getLatest()
                        if (latest == null) {
                            if (pollCount % 30 == 1) AppLogger.w(tag, "SMS poll #$pollCount: getLatest() returned null — SMS 读取失败（ContentResolver 和 goform 均无数据）")
                            continue
                        }
                        // 去重：已转发过的不再处理
                        if (latest.id == lastForwardedId) continue
                        // 时间窗口：只处理最近 2 分钟内的短信（参考项目 minute=2）
                        val age = System.currentTimeMillis() - latest.date
                        if (age <= 2 * 60 * 1000L) {
                            AppLogger.i(tag, "New SMS #${latest.id} from ${latest.address}: ${latest.body.take(50)}")
                            val ok = smsForwardCtl.forwardSms(latest.address, latest.body, latest.date)
                            AppLogger.i(tag, "Forward result: ${if (ok) "success" else "failed"} (method=${cfg.method})")
                            lastForwardedId = latest.id
                        } else {
                            // 最新短信超过 2 分钟 — 正常情况（没有新短信）
                            if (pollCount % 60 == 1) AppLogger.d(tag, "SMS poll #$pollCount: latest SMS age=${age / 1000}s (>2min), no new SMS")
                        }
                    } catch (e: Exception) {
                        AppLogger.w(tag, "SMS poll #$pollCount error: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
            smsPollJob

            // 启动服务
            scheduler.start()
            server.start()

            // 注册电池事件监听
            try {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(notifier, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(notifier, filter)
                }
                AppLogger.i(tag, "Battery notifier registered")
            } catch (e: Exception) {
                AppLogger.w(tag, "Battery notifier registration failed: ${e.message}")
            }

            // 恢复 VoLTE/VoNR 持久化状态
            serviceScope.launch {
                try {
                    val voPrefs = applicationContext.getSharedPreferences("ufi_axis_vo", Context.MODE_PRIVATE)
                    val at = atChannel
                    if (at != null && at.isConnected) {
                        // VoLTE
                        if (voPrefs.contains("voLte_slot0")) {
                            val enabled = voPrefs.getBoolean("voLte_slot0", false)
                            val val0 = if (enabled) "1" else "0"
                            at.sendCommand("AT+CAVIMS=$val0", 5000)
                            AppLogger.i(tag, "VoLTE restored: slot0=$val0")
                        }
                        // VoNR
                        if (voPrefs.contains("voNr_slot0")) {
                            val enabled = voPrefs.getBoolean("voNr_slot0", false)
                            val val0 = if (enabled) "1" else "0"
                            at.sendCommand("AT+SP5GCMDS=\"set nr param\",45,$val0", 5000)
                            AppLogger.i(tag, "VoNR restored: slot0=$val0")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(tag, "VoLTE/VoNR restore failed: ${e.message}")
                }
            }

            // 自动启动 iperf3
            serviceScope.launch {
                try {
                    val iperf3Path = AssetExtractor.getPath(applicationContext, "iperf3")
                    ShellExecutor.executeAsRoot("$iperf3Path -s -D")
                    AppLogger.i(tag, "iperf3 auto-started")
                } catch (e: Exception) {
                    AppLogger.w(tag, "iperf3 auto-start failed: ${e.message}")
                }
            }

            updateNotification("UFI-AXIS-Core 运行中 (:$port)")
            AppLogger.i(tag, "All components initialized successfully. Server listening on :$port")

        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to initialize components", e)
            updateNotification("UFI-AXIS-Core 启动失败: ${e.message}")
        }
    }

    private suspend fun stopAllComponents() {
        AppLogger.i(tag, "Stopping all components...")
        try { batteryNotifier?.let { unregisterReceiver(it) } } catch (e: Exception) { AppLogger.e(tag, "Error unregistering battery notifier", e) }
        try { httpServer?.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping server", e) }
        try { dataScheduler?.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping scheduler", e) }
        try { alertEngine?.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping alert engine", e) }
        try { webSocketManager?.closeAll() } catch (e: Exception) { AppLogger.e(tag, "Error closing websocket", e) }
        try { goformClient?.close() } catch (e: Exception) { AppLogger.e(tag, "Error closing goform client", e) }
        try { adbController?.destroy() } catch (e: Exception) { AppLogger.e(tag, "Error stopping adb", e) }
        try { taskScheduler?.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping task scheduler", e) }
        try { downloadManager?.shutdown() } catch (e: Exception) { AppLogger.e(tag, "Error shutting down download manager", e) }
    }

    private fun createNotificationChannel() {
        // minSdk=31, NotificationChannel 始终可用
        val channel = NotificationChannel(
            CHANNEL_ID,
            "UFI-AXIS-Core Backend",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "UFI-AXIS-Core 后端服务通知"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun getDeviceGatewayIp(): String {
        // 方法1: Android ConnectivityManager
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val lp = cm?.getLinkProperties(activeNetwork)
            val dhcp = lp?.dhcpServerAddress?.hostAddress
            if (!dhcp.isNullOrBlank()) { AppLogger.i(tag, "Gateway (DHCP): $dhcp"); return dhcp }
        } catch (_: Exception) {}

        // 方法2: ip route
        try {
            val ipRoute = runBlocking { ShellExecutor.execute("ip route 2>/dev/null | grep default").stdout }
            val m = Regex("default via (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(ipRoute)
            if (m != null) { val gw = m.groupValues[1]; AppLogger.i(tag, "Gateway (ip route): $gw"); return gw }
        } catch (_: Exception) {}

        // 方法3: getprop (各接口名)
        try {
            for (prop in listOf("dhcp.wlan0.gateway", "dhcp.wlan.gateway", "dhcp.eth0.gateway", "dhcp.rmnet0.gateway")) {
                val gw = runBlocking { ShellExecutor.execute("getprop $prop").stdout.trim() }
                if (gw.isNotBlank() && gw != "unknown") { AppLogger.i(tag, "Gateway (getprop): $gw"); return gw }
            }
        } catch (_: Exception) {}

        // 方法4: /proc/net/route 解析
        try {
            val route = runBlocking { ShellExecutor.executeAsRoot("cat /proc/net/route 2>/dev/null").stdout }
            val lines = route.lines()
            for (line in lines) {
                val parts = line.split("\\s+".toRegex())
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
        } catch (_: Exception) {}

        AppLogger.w(tag, "Could not detect gateway, falling back to 192.168.0.1")
        return "192.168.0.1"
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UFI-AXIS-Core")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
