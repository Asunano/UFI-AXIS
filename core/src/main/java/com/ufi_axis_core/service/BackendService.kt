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
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.core.scheduler.DataScheduler
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.concurrent.atomic.AtomicInteger

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

    companion object {
        const val CHANNEL_ID = "ufi_axis_core_service"
        const val NOTIFICATION_ID = 1
        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val MAX_CRASH_RETRY = 3

        @Volatile
        var isRunning: Boolean = false

        // 启动失败计数（防止 START_STICKY + init 失败 = 无限重启循环）
        val crashRetryCount = AtomicInteger(0)

        fun start(context: Context) {
            // minSdk=31, startForegroundService 始终可用
            context.startForegroundService(Intent(context, BackendService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackendService::class.java))
        }
    }

    // 组件图（由 ComponentFactory 构建）
    private var graph: ComponentGraph? = null
    // 系统级组件引用（用于 destroy 和启动动作）
    private var batteryNotifier: BatteryNotifier? = null

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
        if (isRunning) {
            AppLogger.i(tag, "Service already running, ignoring duplicate start")
            return START_STICKY
        }
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

            val gatewayIp = getDeviceGatewayIp()
            val g = ComponentFactory.build(this, gatewayIp)
            graph = g

            // ── 电池事件通知（依赖 smsForwardController） ──
            val notifier = BatteryNotifier(
                onLowBattery = { pct ->
                    serviceScope.launch {
                        try { g.smsForwardController.forwardSms("SYSTEM", "低电量警告: ${pct}%", System.currentTimeMillis()) } catch (_: Exception) {}
                    }
                },
                onVeryLowBattery = { pct ->
                    serviceScope.launch {
                        try { g.smsForwardController.forwardSms("SYSTEM", "极低电量警告: ${pct}%", System.currentTimeMillis()) } catch (_: Exception) {}
                    }
                },
                onFullBattery = {
                    serviceScope.launch {
                        try { g.smsForwardController.forwardSms("SYSTEM", "电池已充满", System.currentTimeMillis()) } catch (_: Exception) {}
                    }
                },
                onChargeStart = {
                    AppLogger.d(tag, "Charging started")
                }
            )
            batteryNotifier = notifier

            // ── SMS 轮询 Job ──
            val smsPollJob = serviceScope.launch {
                val smsForwardCtl = g.smsForwardController
                val smsCtl = com.ufi_axis_core.controller.sms.SmsController(this@BackendService, g.smsClient)
                var lastForwardedId: Long = -1
                var pollCount = 0
                val initCfg = smsForwardCtl.loadConfig()
                AppLogger.i(tag, "SMS poll init: enabled=${initCfg.enabled}, method=${initCfg.method}, smtpHost=${initCfg.smtpHost.takeIf { it.isNotBlank() } ?: "(empty)"}")
                while (isActive) {
                    delay(8_000L)
                    pollCount++
                    try {
                        val cfg = smsForwardCtl.loadConfig()
                        if (!cfg.enabled) {
                            if (pollCount % 60 == 1) AppLogger.d(tag, "SMS poll #$pollCount: forwarding disabled, skipping")
                            continue
                        }
                        val latest = smsCtl.getLatest()
                        if (latest == null) {
                            if (pollCount % 30 == 1) AppLogger.w(tag, "SMS poll #$pollCount: getLatest() returned null — SMS 读取失败（ContentResolver 和 goform 均无数据）")
                            continue
                        }
                        if (latest.id == lastForwardedId) continue
                        val age = System.currentTimeMillis() - latest.date
                        if (age <= 2 * 60 * 1000L) {
                            AppLogger.i(tag, "New SMS #${latest.id} from ${latest.address}: ${latest.body.take(50)}")
                            val ok = smsForwardCtl.forwardSms(latest.address, latest.body, latest.date)
                            AppLogger.i(tag, "Forward result: ${if (ok) "success" else "failed"} (method=${cfg.method})")
                            lastForwardedId = latest.id
                        } else {
                            if (pollCount % 60 == 1) AppLogger.d(tag, "SMS poll #$pollCount: latest SMS age=${age / 1000}s (>2min), no new SMS")
                        }
                    } catch (e: Exception) {
                        AppLogger.w(tag, "SMS poll #$pollCount error: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }

            // ── 启动服务 ──
            g.dataScheduler.start()
            val serverOk = g.server.start()
            if (!serverOk) {
                AppLogger.e(tag, "HTTP Server failed to start, but service will continue")
            }

            // ── 注册电池事件监听 ──
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

            // ── 自动启动 iperf3 ── （已禁用）
            if (false) serviceScope.launch {
                try {
                    val iperf3Path = com.ufi_axis_core.util.AssetExtractor.getPath(applicationContext, "iperf3")
                    ShellExecutor.executeAsRoot("$iperf3Path -s -D")
                    AppLogger.i(tag, "iperf3 auto-started")
                } catch (e: Exception) {
                    AppLogger.w(tag, "iperf3 auto-start failed: ${e.message}")
                }
            }

            // ── 开机自动启动 ADB WiFi (根据配置) ── （已禁用）
            if (false) if (g.settings.adbAutoStartOnBoot) {
                serviceScope.launch {
                    delay(5000) // 等待其他组件稳定
                    try {
                        val started = g.adbController.start()
                        AppLogger.i(tag, "ADB auto-start: ${if (started) "success" else "failed"}")
                    } catch (e: Exception) {
                        AppLogger.w(tag, "ADB auto-start error: ${e.message}")
                    }
                }
            }

            updateNotification("UFI-AXIS-Core 运行中 (:${g.settings.port})")
            AppLogger.i(tag, "All components initialized successfully. Server listening on :${g.settings.port}")

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

    private suspend fun stopAllComponents() {
        AppLogger.i(tag, "Stopping all components...")
        try { batteryNotifier?.let { unregisterReceiver(it) } } catch (e: Exception) { AppLogger.e(tag, "Error unregistering battery notifier", e) }
        val g = graph ?: return
        try { g.server.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping server", e) }
        // drain 等待 Netty 事件循环线程完全关闭，避免其 pending 的协程完成处理器
        // 在后续 scope.cancel() 中抛出 CompletionHandlerException
        try { delay(300) } catch (_: Exception) {}
        try { g.dataScheduler.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping scheduler", e) }
        try { g.alertEngine.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping alert engine", e) }
        try { g.wsManager.closeAll() } catch (e: Exception) { AppLogger.e(tag, "Error closing websocket", e) }
        try { g.goformClient.close() } catch (e: Exception) { AppLogger.e(tag, "Error closing goform client", e) }
        try { g.adbController.destroy() } catch (e: Exception) { AppLogger.e(tag, "Error stopping adb", e) }
        try { g.taskScheduler.stop() } catch (e: Exception) { AppLogger.e(tag, "Error stopping task scheduler", e) }
        try { g.downloadManager.shutdown() } catch (e: Exception) { AppLogger.e(tag, "Error shutting down download manager", e) }
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

    private suspend fun getDeviceGatewayIp(): String {
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
            val ipRoute = ShellExecutor.execute("ip route 2>/dev/null | grep default").stdout
            val m = Regex("default via (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(ipRoute)
            if (m != null) { val gw = m.groupValues[1]; AppLogger.i(tag, "Gateway (ip route): $gw"); return gw }
        } catch (_: Exception) {}

        // 方法3: getprop (各接口名)
        try {
            for (prop in listOf("dhcp.wlan0.gateway", "dhcp.wlan.gateway", "dhcp.eth0.gateway", "dhcp.rmnet0.gateway")) {
                val gw = ShellExecutor.execute("getprop $prop").stdout.trim()
                if (gw.isNotBlank() && gw != "unknown") { AppLogger.i(tag, "Gateway (getprop): $gw"); return gw }
            }
        } catch (_: Exception) {}

        // 方法4: /proc/net/route 解析
        try {
            val route = ShellExecutor.executeAsRoot("cat /proc/net/route 2>/dev/null").stdout
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
