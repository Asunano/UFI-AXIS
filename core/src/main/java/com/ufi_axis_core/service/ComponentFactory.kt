package com.ufi_axis_core.service

import android.content.Context
import com.ufi_axis_core.alert.AlertEngine
import com.ufi_axis_core.api.DataHub
import com.ufi_axis_core.api.middleware.AuthMiddleware
import com.ufi_axis_core.api.middleware.QoSMiddleware
import com.ufi_axis_core.api.routes.*
import com.ufi_axis_core.api.websocket.WebSocketManager
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.goform.*
import com.ufi_axis_core.controller.network.NetworkController
import com.ufi_axis_core.controller.sim.SimController
import com.ufi_axis_core.controller.system.SystemController
import com.ufi_axis_core.core.database.AppDatabase
import com.ufi_axis_core.core.scheduler.DataScheduler
import com.ufi_axis_core.core.server.HttpServer
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.AssetExtractor
import com.ufi_axis_core.util.DynamicThreadPool
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.*

/**
 * 组件工厂 — 负责按依赖顺序创建并组装所有后端组件。
 *
 * 遵循原则:
 * 1. 自底向上构造: 数据库 → 采集器 → 客户端 → 控制器 → 基础设施 → 路由 → 服务器
 * 2. 构造与启动分离: Factory 只负责 new+wire，启动动作（start/schedule/register）由 BackendService 执行
 * 3. 所有组件通过 [ComponentGraph] 统一返回，无隐式依赖
 */
object ComponentFactory {

    private const val TAG = "ComponentFactory"
    private var built = false

    /**
     * 构建完整组件图。
     * @param context Service Context（用于 AssetExtractor、ATChannel、SystemCollector 等）
     * @param gatewayIp 设备网关 IP（goform 连接地址）
     * @return 完整的 [ComponentGraph]
     * @throws IllegalStateException 如果 [build] 已被调用过
     */
    suspend fun build(context: Context, gatewayIp: String): ComponentGraph {
        check(!built) { "ComponentGraph already built, build() must only be called once" }
        built = true
        val settings = AppSettings.getInstance(context)
        AppLogger.setDebugMode(settings.debugMode)
        AppLogger.i(TAG, "Building component graph...")

        // ── 0. 提取 shell 资产 ──
        try {
            AssetExtractor.extractAll(context.applicationContext)
            AppLogger.i(TAG, "Shell assets extracted")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Asset extraction failed: ${e.message}")
        }

        // ── 1. 数据库 ──
        val database = AppDatabase.getInstance(context)
        AppLogger.i(TAG, "[1] Database initialized")

        // ── 2. AT 通道 ──
        val atChannel = ATChannel(context.applicationContext)
        val atConnected = try {
            withTimeoutOrNull(10_000L) { atChannel.init() } ?: false
        } catch (e: Exception) {
            AppLogger.e(TAG, "AT channel init exception", e); false
        }
        AppLogger.i(TAG, "[2] AT channel: ${if (atConnected) "connected" else "not available"}")

        // ── 3. 采集器 ──
        val systemCollector = SystemCollector(context)
        val telephonyCollector = TelephonyCollector(context)
        AppLogger.i(TAG, "[3] Collectors initialized")

        // ── 4. Goform 客户端层 ──
        val goformIp = settings.goformIp.ifBlank { gatewayIp }
        val goform = GoformClient(
            deviceIp = goformIp,
            port = settings.goformPort,
            password = settings.goformPassword
        )
        val signalClient = GoformSignalClient(goform)
        val wifiClient = GoformWifiClient(goform)
        val networkClient = GoformNetworkClient(goform)
        val deviceClient = GoformDeviceClient(goform)
        val smsClient = GoformSmsClient(goform)
        val simClient = GoformSimClient(goform)
        AppLogger.i(TAG, "[4] Goform clients initialized")

        // ── 5. 控制器 ──
        val networkController = NetworkController(context, atChannel, networkClient, wifiClient)
        val simController = SimController(context, database, smsClient, signalClient)
        val systemController = SystemController(deviceClient)
        AppLogger.i(TAG, "[5] Controllers initialized")

        // ── 6. WebSocket 管理器 ──
        val wsManager = WebSocketManager(expectedToken = settings.token)
        AppLogger.i(TAG, "[6] WebSocket manager initialized")

        // ── 7. 告警引擎 ──
        val alert = AlertEngine(database.alertDao(), wsManager, settings)
        AppLogger.i(TAG, "[7] Alert engine initialized")

        // ── 8. 共享组件 ──
        val dynamicThreadPool = DynamicThreadPool()
        val qosMiddleware = QoSMiddleware()
        // ResponseCache 在 wsManager 之后创建以便设置回调
        val responseCache = com.ufi_axis_core.core.cache.ResponseCache(
            onInvalidate = { type -> wsManager.broadcastDataChanged(type) }
        )
        AppLogger.i(TAG, "[8] Shared components initialized (cache + ws notification enabled)")

        // ── 9. 数据采集调度器 ──
        val scheduler = DataScheduler(
            systemCollector, telephonyCollector, database, wsManager, signalClient,
            smsClient = smsClient,
            alertEngine = alert,
            dynamicThreadPool = dynamicThreadPool
        )
        AppLogger.i(TAG, "[9] DataScheduler initialized")

        // ── 10. 认证中间件 ──
        val authMiddleware = AuthMiddleware(settings)
        AppLogger.i(TAG, "[10] Auth middleware initialized")

        // ── 11. ADB 控制器 ──
        val adbController = com.ufi_axis_core.controller.adb.AdbController(goform, deviceClient)
        AppLogger.i(TAG, "[11] ADB controller initialized")

        // ── 12. 扩展组件 ──
        val smsForwardController = com.ufi_axis_core.controller.sms.SmsForwardController(context, systemCollector)
        val taskScheduler = com.ufi_axis_core.core.scheduler.TaskScheduler(context)
        val downloadManager = com.ufi_axis_core.controller.system.DownloadManager(context.applicationContext)
        scheduler.let { downloadManager.setDataScheduler(it) }
        AppLogger.i(TAG, "[12] Extended components initialized")

        // ── 12.5 DataHub: 统一请求数据中心（集中管理所有 goform 查询缓存，消除路由间重复请求）──
        val dataHub = DataHub(scheduler, signalClient, wifiClient, responseCache)
        AppLogger.i(TAG, "[12.5] DataHub initialized")

        // ── 12.6 RouteContext: 集中所有 Route 共享依赖 ──
        val routeCtx = RouteContext(
            systemCollector = systemCollector,
            telephonyCollector = telephonyCollector,
            atChannel = atChannel,
            goformClient = goform,
            signalClient = signalClient,
            networkClient = networkClient,
            deviceClient = deviceClient,
            wifiClient = wifiClient,
            simClient = simClient,
            systemController = systemController,
            networkController = networkController,
            simController = simController,
            adbController = adbController,
            database = database,
            dataScheduler = scheduler,
            responseCache = responseCache,
            dataHub = dataHub,
            settings = settings,
            dynamicThreadPool = dynamicThreadPool,
            qosMiddleware = qosMiddleware
        )
        AppLogger.i(TAG, "[12.6] RouteContext initialized")

        // ── 13. API 路由 ──
        val deviceRoutes = DeviceRoutes(routeCtx)
        val networkRoutes = NetworkRoutes(routeCtx)
        val systemRoutes = SystemRoutes(routeCtx)
        val trafficRoutes = TrafficRoutes(routeCtx)
        val advancedRoutes = AdvancedRoutes(context.applicationContext)
        val simRoutes = SimRoutes(routeCtx)
        val atRoutes = ATRoutes(atChannel)
        val alertRoutes = AlertRoutes(alert)
        val wifiRoutes = WifiRoutes(routeCtx)
        val configRoutes = ConfigRoutes(settings)
        val appManager = com.ufi_axis_core.controller.system.AppManager()
        val appRoutes = AppRoutes(appManager)
        val shellRoutes = ShellRoutes()
        val fileRoutes = FileRoutes()
        val smsController = com.ufi_axis_core.controller.sms.SmsController(context, smsClient)
        val rootSmsRoutes = RootSmsRoutes(smsController, scheduler)
        val adbRoutes = AdbRoutes(adbController, settings)
        val smsForwardRoutes = SmsForwardRoutes(smsForwardController)
        val taskRoutes = TaskRoutes(taskScheduler)
        val speedTestRoutes = SpeedTestRoutes()
        val debugLogRoutes = DebugLogRoutes()
        val qosRoutes = QoSRoutes(routeCtx)
        val monitorRoutes = MonitorRoutes(database)
        val downloadRoutes = DownloadRoutes(downloadManager)
        val dashboardRoutes = DashboardRoutes(routeCtx)
        AppLogger.i(TAG, "[13] API routes initialized")

        // ── 14. HTTP Server ──
        val server = HttpServer(
            port = settings.port,
            ctx = routeCtx,
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
            smsForwardRoutes = smsForwardRoutes,
            taskRoutes = taskRoutes,
            speedTestRoutes = speedTestRoutes,
            debugLogRoutes = debugLogRoutes,
            advancedRoutes = advancedRoutes,
            qosRoutes = qosRoutes,
            monitorRoutes = monitorRoutes,
            downloadRoutes = downloadRoutes,
            dashboardRoutes = dashboardRoutes
        )
        AppLogger.i(TAG, "[14] HTTP server ready (with API cache)")

        AppLogger.i(TAG, "Component graph built successfully")
        return ComponentGraph(
            settings = settings,
            responseCache = responseCache,
            database = database,
            atChannel = atChannel,
            systemCollector = systemCollector,
            telephonyCollector = telephonyCollector,
            goformClient = goform,
            signalClient = signalClient,
            wifiClient = wifiClient,
            networkClient = networkClient,
            deviceClient = deviceClient,
            smsClient = smsClient,
            simClient = simClient,
            networkController = networkController,
            simController = simController,
            systemController = systemController,
            adbController = adbController,
            wsManager = wsManager,
            alertEngine = alert,
            dynamicThreadPool = dynamicThreadPool,
            qosMiddleware = qosMiddleware,
            authMiddleware = authMiddleware,
            dataScheduler = scheduler,
            smsForwardController = smsForwardController,
            taskScheduler = taskScheduler,
            downloadManager = downloadManager,
            server = server
        )
    }
}
