// 防腐层（F9）：本文件是**唯一**知道 GoformClient 具体类型的地方——它负责 new 出实现；
// 组件图对外只暴露 GoformGateway 接口（见 ComponentGraph.NetworkGraph.goformClient）。
package com.ufi_axis_core.service

import android.content.Context
import com.ufi_axis_core.alert.AlertEngine
import com.ufi_axis_core.api.DataHub
import com.ufi_axis_core.api.middleware.AuthMiddleware
import com.ufi_axis_core.api.pairing.PairingManager
import com.ufi_axis_core.api.routes.*
import com.ufi_axis_core.api.websocket.WebSocketManager
import com.ufi_axis_core.api.websocket.WebSocketPushService
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.goform.*
import com.ufi_axis_core.controller.network.NetworkController
import com.ufi_axis_core.controller.network.TrafficAutoOffGuard
import com.ufi_axis_core.controller.system.SystemController
import com.ufi_axis_core.core.database.AppDatabase
import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.profile.DeviceProfiles
import com.ufi_axis_core.core.scheduler.DataScheduler
import com.ufi_axis_core.core.server.HttpServer
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.AssetExtractor
import com.ufi_axis_core.util.DynamicThreadPool
import com.ufi_axis_core.util.NativeExecProbe
import com.ufi_axis_core.util.PairedDeviceStore
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.*

/**
 * 组件工厂 — 负责按依赖顺序创建并组装所有后端组件。
 *
 * 遵循原则:
 * 1. 自底向上构造: 数据库 → 采集器 → 客户端 → 控制器 → 基础设施 → 路由 → 服务器
 * 2. 构造与启动分离: Factory 只负责 new+wire，启动动作（start/schedule/register）由 BackendService 执行
 * 3. 所有组件通过 [ComponentGraph] 统一返回，无隐式依赖
 *
 * Phase 2 (#2) 重构：将原 [build] 中 14 个编号步骤按 5 个语义子图聚合为
 * [buildNetworkGraph] / [buildCollectorGraph] / [buildStorageGraph] /
 * [buildControllerGraph] / [buildServerGraph] 五个 builder。[build] 仅做
 * 「依序调用 builder → 组装 [ComponentGraph]」，运行时组件集合与重构前完全一致，
 * 各 [1]..[14] 步骤日志完整保留。
 */
object ComponentFactory {

    private const val TAG = "ComponentFactory"
    private var built = false

    /**
     * 一次性日志迁移标志。2026-08-21 加入：Core 升级后第一次 build 时把
     * /Download/UFI-AXIS/ 根目录下旧版散乱日志文件归类到 /log/_archive/，
     * 保证用户文件管理器看到的根目录干净。
     */
    @Volatile private var legacyLogMigrated = false

    private suspend fun migrateLegacyLogsOnce() {
        if (legacyLogMigrated) return
        legacyLogMigrated = true
        runCatching {
            // 把 /Download/UFI-AXIS/ 根目录下旧路径日志文件归档到 /log/_archive/。
            // 排除目录本身（仅匹配根目录下的文件，不递归）。
            val cmd = """
                |ARCHIVE=/sdcard/Download/UFI-AXIS/log/_archive
                |TS=$(date +%Y%m%d_%H%M%S)
                |mkdir -p "${'$'}ARCHIVE" 2>/dev/null
                |for f in \
                |    /sdcard/Download/UFI-AXIS/ufi_update_*.log \
                |    /sdcard/Download/UFI-AXIS/ufi_update_core*.log \
                |    /sdcard/Download/UFI-AXIS/ufi_install_pi*.log \
                |    /sdcard/Download/UFI-AXIS/.launch.out; do
                |  [ -e "${'$'}f" ] || continue
                |  bn="$(basename "${'$'}f")"
                |  mv "${'$'}f" "${'$'}ARCHIVE/${'$'}TS"'_'"${'$'}bn" 2>/dev/null
                |done
            """.trimMargin().trim()
            val r = ShellExecutor.executeAsRoot(cmd, timeoutMs = 15_000L)
            AppLogger.i(TAG, "Legacy log migration done rc=${r.exitCode}")
        }.onFailure { AppLogger.w(TAG, "Legacy log migration failed: ${it.message}") }
    }

    /**
     * 重置构建状态，允许 [build] 再次调用。
     * 在服务停止后调用，支持服务重启场景。
     */
    fun reset() {
        built = false
        AppLogger.i(TAG, "ComponentFactory reset")
    }

    /**
     * 构建完整组件图。
     * @param context Service Context（用于 AssetExtractor、ATChannel、SystemCollector 等）
     * @param gatewayIp 设备网关 IP（goform 连接地址）
     * @return 完整的 [ComponentGraph]
     * 如果已被调用过，自动 reset 后重建（支持服务被 force-stop 后重启的场景）
     */
    suspend fun build(context: Context, gatewayIp: String, wakeLockRenew: (() -> Unit)? = null): ComponentGraph {
        if (built) {
            AppLogger.w(TAG, "ComponentFactory.build() called while already built — resetting (service restarted without destroy?)")
            reset()
        }
        built = true
        val settings = AppSettings.getInstance(context)
        // 三层开关一次性恢复（持久化值优先）。与 UfiAxisCoreApplication.onCreate 走同一个入口，
        // 避免像以前那样两处各写一遍、其中一处漏掉 debug_mode。
        AppLogger.restoreSwitches(
            logEnabled = settings.logEnabled,
            coreLogEnabled = settings.coreLogEnabled,
            debugMode = settings.debugMode
        )
        AppLogger.i(TAG, "Building component graph...")

        // ── 0. 提取 shell 资产 ──
        try {
            AssetExtractor.extractAll(context.applicationContext)
            AppLogger.i(TAG, "Shell assets extracted")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Asset extraction failed: ${e.message}")
        }

        // ── 0.1 自检「filesDir 下的二进制能不能 exec」──
        // enforcing 设备上 aria2c/ttyd/socat 全都起不来，而各调用点大多 catch 后静默，
        // 表面只表现为"下载不动、终端打不开"。这里显式探一次并缓存，由 /api/service/status
        // 的 native_exec 段暴露。force=true：覆盖安装会重新释放二进制，不能沿用旧结论。
        runCatching { NativeExecProbe.probe(context.applicationContext, force = true) }

        // ── 0.5 旧版本遗留的散乱日志文件迁移到 /log/_archive/（仅首次 build 执行一次）──
        migrateLegacyLogsOnce()

        // 注意：AdbShellExecutor 不在此处初始化。adbd 需要先通过 Goform USB_PORT_SETTING
        // 启用（在 AdbController.start() 中完成），否则 adb connect localhost 必然失败。
        // AdbShellExecutor 由 BackendService 在构建完成后调用 adbController.start() 时初始化。

        // ── 1. 数据库 ──
        val database = AppDatabase.getInstance(context)
        AppLogger.i(TAG, "[1] Database initialized")

        // ── 2-3. 采集器子图（AT 通道 + system/telephony 采集器） ──
        val collector = buildCollectorGraph(context)

        // ── 4-5. 网络子图（Goform 客户端层 + 网络/ SIM 控制器） ──
        // 设备 profile 在整个组件图里只选一次（计划书 3.2），网络子图与 SignalCollector 共用。
        val deviceProfile = resolveDeviceProfile(settings)
        val network = buildNetworkGraph(settings, gatewayIp, collector.atChannel, database, context, deviceProfile)

        // ── 5.5 配对设备存储 + 设备请求验证器 ──
        // 位置提前到 WebSocket 之前：WS 握手与 /api 请求都要按 token 哈希查设备、用记录里的
        // 公钥验签，两者必须共用**同一个** verifier 实例——它内部持有 nonce 缓存，
        // 共享才能拦住"HTTP 用过的 nonce 拿去开 WS"这类跨通道重放。
        // PairedDeviceStore 构造时会迁移旧 pairedFingerprints（见其 init 块）。
        val pairedDeviceStore = PairedDeviceStore(context.applicationContext, settings)
        val deviceVerifier = com.ufi_axis_core.util.DeviceRequestVerifier(pairedDeviceStore)
        AppLogger.i(TAG, "[5.5] Paired device store & request verifier initialized")

        // ── 6. WebSocket 管理器与推送服务 ──
        val wsManager = WebSocketManager(
            authenticator = { token, ts, nonce, sig, path ->
                val result = deviceVerifier.verify(
                    token = token,
                    timestamp = ts,
                    nonce = nonce,
                    signature = sig,
                    method = "GET",
                    uri = path
                )
                (result as? com.ufi_axis_core.util.DeviceRequestVerifier.Result.Ok)?.device?.fingerprint
            }
        )
        val pushService = WebSocketPushService(wsManager)
        AppLogger.i(TAG, "[6] WebSocket manager & PushService initialized")

        // ── 7. 告警引擎 ──
        // 告警系统通知统一由手机端 NotificationCenter 负责，device 端仅入库 + 广播（避免双进程重复弹通知）
        val alert = AlertEngine(database.alertDao(), wsManager, settings, pushService)
        AppLogger.i(TAG, "[7] Alert engine initialized")

        // ── 8. 共享组件 ──
        val dynamicThreadPool = DynamicThreadPool()
        // ResponseCache 在 wsManager 之后创建以便设置回调
        val responseCache = com.ufi_axis_core.core.cache.ResponseCache(
            onInvalidate = { type -> wsManager.broadcastDataChanged(type) }
        )
        AppLogger.i(TAG, "[8] Shared components initialized (cache + ws notification enabled)")

        // ── 8.5 SMS 控制器（提前创建，供 DataScheduler VC 扫描使用）──
        val smsController = com.ufi_axis_core.controller.sms.SmsController(context, network.smsClient, database.smsReadStateDao(), database.smsVerificationCodeDao())

        // ── 9. 数据采集调度器 ──
        val scheduler = DataScheduler(
            systemCollector = collector.systemCollector,
            telephonyCollector = collector.telephonyCollector,
            database = database,
            webSocketManager = wsManager,
            signalClient = network.signalClient,
            smsClient = network.smsClient,
            alertEngine = alert,
            dynamicThreadPool = dynamicThreadPool,
            wakeLockRenew = wakeLockRenew,
            smsReadStateDao = database.smsReadStateDao(),
            settings = settings,
            vcDao = database.smsVerificationCodeDao(),
            smsController = smsController,
            // SignalCollector 的字段映射表。它不吃"关归一化"那个开关（第 1 层就是归一化），
            // 所以关闭时也回落默认 profile。
            deviceProfile = deviceProfile ?: DeviceProfiles.DEFAULT
        )
        AppLogger.i(TAG, "[9] DataScheduler initialized")

        // 方案 A（2026-08-24）：WS 连接回调只驱动「实时推送」开关，不再启停采集循环。
        // 冷数据采集在 Core 启动时由 applyColdCollectionState() 无条件启动（见 BackendService/MonitorRoutes），
        // 前端断开后采集继续（写入 DB/缓存），仅停止 WebSocket 广播以省电；连接恢复即恢复广播。
        wsManager.onConnectionsEmpty = { responseCache.markStale(); scheduler.applyRealtimePushState() }
        wsManager.onConnectionRestored = { responseCache.clearStale(); scheduler.applyRealtimePushState() }

        // ── 10. 认证中间件 ──
        val authMiddleware = AuthMiddleware(deviceVerifier)
        AppLogger.i(TAG, "[10] Auth middleware initialized")

        // ── 11-12. 控制器子图（ADB / SMS 转发 / 任务 / 下载 / System 控制器） ──
        val controller = buildControllerGraph(
            context = context,
            deviceClient = network.deviceClient,
            systemCollector = collector.systemCollector,
            networkController = network.networkController,
            wifiClient = network.wifiClient,
            networkClient = network.networkClient
        )

        // 条件引擎挂载到数据采集调度器（在各采集点并联评估，零额外采集开销）
        scheduler.attachConditionEngine(controller.conditionEngine)

        // 告警邮件：core 侧自产自销 —— 告警落库处直接发信，不再依赖 app 弹通知后回传。
        // 场景 id 与 app 的 NotifyScene 对齐（connectivity 走 connectivity，其余归 alert），
        // 用户在邮件设置里勾了哪个场景才发；app 侧这两个场景的 mailForward 已关，避免一条两封。
        alert.attachMailForwarder { type, level, message, value, threshold ->
            val scene = when (type) {
                "connectivity" -> "connectivity"
                "traffic_limit" -> "traffic80"   // 套餐限额百分比预警自成一个场景（≠ 绝对 MB 阈值告警）
                "device_online", "device_offline" -> "events"
                else -> "alert"
            }
            val detail = buildString {
                appendLine("告警类型: $type")
                appendLine("级别: $level")
                if (value.isNotBlank()) appendLine("当前值: $value")
                if (threshold.isNotBlank()) appendLine("阈值: $threshold")
            }.trimEnd()
            controller.smsForwardController.sendSceneNotification(scene, message, detail)
        }

        // 下载结束 / 隧道异常同理：这两件事都发生在 core 内部（aria2 状态跃迁、看护重连放弃），
        // 以前只有 app 前台轮询才能发现并回传，现在 core 自己发。
        controller.downloadManager.attachMailForwarder { title, body ->
            controller.smsForwardController.sendSceneNotification("download", title, body)
        }
        controller.tunnelManager.attachMailForwarder { title, body ->
            controller.smsForwardController.sendSceneNotification("tunnel", title, body)
        }



        // ── 12.5 DataHub: 统一请求数据中心（集中管理所有 goform 查询缓存，消除路由间重复请求）──
        val dataHub = DataHub(scheduler, network.signalClient, network.wifiClient, responseCache)
        AppLogger.i(TAG, "[12.5] DataHub initialized")

        // 流量套餐限额预警（2026-08-31 从 app 侧下沉）：判定在 AlertEngine，取数走 DataHub 的 10s 缓存。
        // 开关在 lambda 里查，关闭时连 goform 都不查（scheduler 引不到 NotificationConfig，见 attachTrafficLimitProvider）。
        //
        // 2026-09-01：自动关网（TrafficAutoOffGuard）复用同一份限额数据，所以两个开关任一打开都要取数 ——
        // 否则用户只开自动关网、没开告警通道时，限额永远不会被读取，功能等于没接。
        val trafficAutoOffGuard = TrafficAutoOffGuard(
            settings = settings,
            networkController = network.networkController,
            // 走 traffic80 场景：与套餐限额预警同一个邮件场景，用户在邮件设置里勾了才发。
            // 发信失败（含"场景没勾"）会返回 false，Guard 据此**放弃关网**。
            mailSender = { title, body ->
                controller.smsForwardController.sendSceneNotification("traffic80", title, body)
            },
        )
        scheduler.attachTrafficLimitProvider {
            val alertOn = NotificationRoutes.read(settings).traffic_80_enabled
            val guardOn = TrafficAutoOffGuard.readConfig(settings).enabled
            if (!alertOn && !guardOn) null
            else dataHub.getTrafficLimit()
        }
        scheduler.attachTrafficAutoOffGuard { used, limit, percent ->
            trafficAutoOffGuard.onUsage(used, limit, percent)
        }

        // 设备事件（WiFi 客户端接入/离开）：core 每 60s 比对 station_list，落告警表后自动获得
        // 事件中心 / WS 推送 / 邮件（scene=events）三条既有链路。默认关闭 ——
        // station_list 需要 ensureLogin()，开着会跟设备官方 Web UI 抢 session。
        scheduler.attachDeviceEventWatcher(
            provider = {
                if (!NotificationRoutes.read(settings).device_events_enabled) null
                else network.wifiClient.getConnectedClients()
            },
            sink = { online, label, mac -> alert.recordDeviceEvent(online, label, mac) }
        )



        // ── 12.6 RouteContext: 集中所有 Route 共享依赖（阶段1 C3 / A1：按 dep bundle 注入）──
        val routeCtx = RouteContext(
            collector = CollectorDeps(
                systemCollector = collector.systemCollector,
                telephonyCollector = collector.telephonyCollector,
                atChannel = collector.atChannel
            ),
            network = NetworkDeps(
                goformClient = network.goformClient,
                signalClient = network.signalClient,
                networkClient = network.networkClient,
                deviceClient = network.deviceClient,
                wifiClient = network.wifiClient,
                simClient = network.simClient,
                networkController = network.networkController
            ),
            storage = StorageDeps(
                database = database,
                dataScheduler = scheduler,
                responseCache = responseCache,
                dataHub = dataHub,
                settings = settings
            ),
            system = SystemDeps(
                systemController = controller.systemController,
                dynamicThreadPool = dynamicThreadPool
            )
        )
        AppLogger.i(TAG, "[12.6] RouteContext initialized")

        // ── 13. API 路由 ──
        val deviceRoutes = DeviceRoutes(routeCtx)
        val networkRoutes = NetworkRoutes(routeCtx)
        val systemRoutes = SystemRoutes(routeCtx)
        val trafficRoutes = TrafficRoutes(routeCtx)
        val simRoutes = SimRoutes(routeCtx)
        val atRoutes = ATRoutes(collector.atChannel)
        val alertRoutes = AlertRoutes(alert)
        val wifiRoutes = WifiRoutes(routeCtx)
        val configRoutes = ConfigRoutes(settings)
        // 2026-08-10 设备更新：后端自拉取 + ADB 静默安装（P1 B2 改走 v4 守护脚本执行通道）
        // 读取已提取的 assets/shell/ufi_update.sh 模板（ComponentFactory 顶部已 extractAll），
        // 供 UpdateManager 覆盖写到 /data/local/tmp/ufi_update.sh 后 fire-and-forget 执行
        val updateScriptTemplate: String = try {
            val f = java.io.File(com.ufi_axis_core.util.AssetExtractor.getPath(context, "ufi_update.sh"))
            if (f.exists()) f.readText() else ""
        } catch (e: Exception) {
            com.ufi_axis_core.util.AppLogger.w("ComponentFactory", "读取 ufi_update.sh 模板失败: ${e.message}")
            ""
        }
        val appManager = com.ufi_axis_core.controller.system.AppManager(context.applicationContext)
        val updateManager = com.ufi_axis_core.api.update.UpdateManager(
            settings,
            updateScriptTemplate,
            broadcaster = { type, data -> wsManager.broadcast(type, data) },
            appContext = context,
            appManager = appManager
        )
        // 2026-08-20（issue #2）：WS 客户端订阅 "update" 主题时，立即下发当前更新状态快照，
        // 使手动推送 APK 重启 Core 后重连的客户端能立刻收到更新成功/失败结果（无需等待轮询）。
        wsManager.updateSnapshotProvider = { updateManager.statusToMap() }
        // P0-4/P1 B2 启动恢复（RESULT 双通道，顺序：日志 RESULT 行为权威 → prefs pendingUpdate 兜底）：
        // 1) 新 Core 启动读 Download/UFI-AXIS/log/watchdog/watchdog.log 的 RESULT 行（OK/INSTALL_FAILED/VERIFY_FAIL/ENV_FAIL）
        //    → 写回 status + lastUpdateResult；
        // 2) 无日志/无 RESULT 行时回退 prefs（上次更新安装前被杀/断电 → 校验版本后写回 DONE/FAILED）。
        // （均在 HTTP Server 启动前执行，前端恢复连接后即可读到 DONE/FAILED）
        try {
            updateManager.recoverResultFromLog()
        } catch (e: Exception) {
            com.ufi_axis_core.util.AppLogger.w("ComponentFactory", "recoverResultFromLog 异常: ${e.message}")
        }
        try {
            updateManager.recoverFromPending()
        } catch (e: Exception) {
            com.ufi_axis_core.util.AppLogger.w("ComponentFactory", "recoverFromPending 异常: ${e.message}")
        }
        val updateRoutes = com.ufi_axis_core.api.routes.UpdateRoutes(updateManager)
        val appRoutes = AppRoutes(appManager)
        val shellRoutes = ShellRoutes()
        val fileRoutes = FileRoutes()
        val rootSmsRoutes = RootSmsRoutes(smsController, scheduler)
        val smsForwardRoutes = SmsForwardRoutes(controller.smsForwardController)
        val taskRoutes = TaskRoutes(controller.taskScheduler, controller.conditionEngine)
        val speedTestRoutes = SpeedTestRoutes()
        val debugLogRoutes = DebugLogRoutes()
        val qosRoutes = QoSRoutes(routeCtx)
        val monitorRoutes = MonitorRoutes(database, scheduler, settings) { enabled ->
            BackendService.applyBackgroundServices(enabled)
        }
        val notificationRoutes = com.ufi_axis_core.api.routes.NotificationRoutes(settings)
        val downloadRoutes = DownloadRoutes(controller.downloadManager)
        // 服务控制（2026-08-26）：停/启后台采集（HTTP 服务不受影响）+ 完全重启后端。
        // 重启入口用回调注入 —— :core:api 不能反向依赖 :core。
        val serviceRoutes = com.ufi_axis_core.api.routes.ServiceRoutes(
            dataScheduler = scheduler,
            settings = settings,
            onRestart = { BackendService.requestRestart(context.applicationContext) },
            uptimeMs = { BackendService.uptimeMs() },
            // 2026-09-03：采集之外的后台活动（定时任务/短信转发/Samba 保活/电池/下载轮询/隧道看护）
            onBackgroundSwitch = { enabled -> BackendService.applyBackgroundServices(enabled) }
        )
        val dashboardRoutes = DashboardRoutes(routeCtx)
        val tunnelRoutes = com.ufi_axis_core.api.routes.TunnelRoutes(controller.tunnelManager)
        // ── 13.4 可选二进制组件（2026-09-01：frpc / cloudflared 不再随 APK 分发）──
        // 迁移与临时文件清理**必须异步**：搬几十 MB 二进制 + 跑 `--version` 探测都可能很慢，
        // 而 HTTP 服务要等 build() 返回才启动 —— 放同步会把启动卡在「正在初始化组件...」。
        // 迁移晚几秒不影响隧道：看护每轮都重查 isInstalled()。
        val componentStore = com.ufi_axis_core.util.BinaryComponentStore(context.applicationContext)
        val componentManager = com.ufi_axis_core.api.components.ComponentManager(
            context.applicationContext, settings, componentStore
        )
        componentManager.startStartupMaintenance()
        val componentRoutes = com.ufi_axis_core.api.routes.ComponentRoutes(
            componentManager, controller.tunnelManager
        )
        AppLogger.i(TAG, "[13.4] Binary components ready (migration running in background)")
        AppLogger.i(TAG, "[13] API routes initialized")

        // ── 13.5 配对模式路由（免鉴权；发现改为网关探测 + 手动输入，不再注册 mDNS）──
        // pairedDeviceStore 已在 [5.5] 创建（WS 握手鉴权需要它）
        val pairingManager = PairingManager(settings, pairedDeviceStore)
        val pairingRoutes = PairingRoutes(routeCtx, pairingManager)
        val pairedDevicesRoutes = PairedDevicesRoutes(pairingManager)
        AppLogger.i(TAG, "[13.5] Pairing components initialized")

        // ── 13.6 Web 前端资源管理（独立更新机制）──
        val webResourceManager = com.ufi_axis_core.util.WebResourceManager(context)
        // 2026-08-18 Web 自动更新：从 root version.json 的 web 对象自拉取 ZIP（与手动上传 ZIP 并存）
        val webUpdateManager = com.ufi_axis_core.api.update.WebUpdateManager(context, settings, webResourceManager)
        val webUpdateRoutes = com.ufi_axis_core.api.routes.WebUpdateRoutes(webResourceManager, webUpdateManager)
        AppLogger.i(TAG, "[13.6] Web resource manager initialized")

        // ── 14. HTTP Server ──
        val server = HttpServer(
            port = settings.port,
            ctx = routeCtx,
            androidContext = context,
            authMiddleware = authMiddleware,
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
            updateRoutes = updateRoutes,
            appRoutes = appRoutes,
            shellRoutes = shellRoutes,
            fileRoutes = fileRoutes,
            rootSmsRoutes = rootSmsRoutes,
            smsForwardRoutes = smsForwardRoutes,
            taskRoutes = taskRoutes,
            speedTestRoutes = speedTestRoutes,
            debugLogRoutes = debugLogRoutes,
            qosRoutes = qosRoutes,
            monitorRoutes = monitorRoutes,
            notificationRoutes = notificationRoutes,
            downloadRoutes = downloadRoutes,
            serviceRoutes = serviceRoutes,
            dashboardRoutes = dashboardRoutes,
            pairingRoutes = pairingRoutes,
            pairingManager = pairingManager,
            pairedDevicesRoutes = pairedDevicesRoutes,
            webResourceManager = webResourceManager,
            webUpdateRoutes = webUpdateRoutes,
            tunnelRoutes = tunnelRoutes,
            componentRoutes = componentRoutes
        )
        AppLogger.i(TAG, "[14] HTTP server ready (with API cache)")

        // ── 存储子图（settings + database + responseCache） ──
        val storage = buildStorageGraph(settings, database, responseCache)

        AppLogger.i(TAG, "Component graph built successfully")
        // 阶段1（C2 / A3）：按语义子图组装，运行期组件集合与重构前完全一致。
        return ComponentGraph(
            network = network,
            collector = collector,
            storage = storage,
            controller = controller,
            serverGraph = buildServerGraph(
                server = server,
                wsManager = wsManager,
                authMiddleware = authMiddleware,
                alertEngine = alert,
                dataScheduler = scheduler,
                dynamicThreadPool = dynamicThreadPool,
                webResourceManager = webResourceManager
            )
        )
    }

    // ==================== 子图 builders（Phase 2 #2 拆分） ====================

    /** 采集器子图：AT 通道 + system / telephony 采集器（原步骤 2-3）。 */
    private suspend fun buildCollectorGraph(context: Context): CollectorGraph {
        val atChannel = ATChannel()
        val atConnected = try {
            withTimeoutOrNull(10_000L) { atChannel.init() } ?: false
        } catch (e: Exception) {
            AppLogger.e(TAG, "AT channel init exception", e); false
        }
        AppLogger.i(TAG, "[2] AT channel: ${if (atConnected) "connected" else "not available"}")

        val systemCollector = SystemCollector(context)
        val telephonyCollector = TelephonyCollector(context)
        AppLogger.i(TAG, "[3] Collectors initialized")

        return CollectorGraph(
            atChannel = atChannel,
            systemCollector = systemCollector,
            telephonyCollector = telephonyCollector
        )
    }

    /** 网络子图：Goform 客户端层 + NetworkController（原步骤 4-5）。
     * 注意：SystemController 依赖 deviceClient，归入 [buildControllerGraph]。 */
    private fun buildNetworkGraph(
        settings: AppSettings,
        gatewayIp: String,
        atChannel: ATChannel,
        database: AppDatabase,
        context: Context,
        profile: DeviceProfile?
    ): NetworkGraph {
        val goformIp = settings.goformIp.ifBlank { gatewayIp }
        val goform = GoformClient(
            deviceIp = goformIp,
            port = settings.goformPort,
            password = settings.goformPassword
        )
        // profile 由调用方选好后注入（计划书 3.2）。
        // 此前每个客户端的构造参数各带一个 `= ZteGoformProfile` 默认值 —— 等于选型逻辑
        // 散在 6 个签名里，换设备要改 6 处且漏一处不会报错。
        val signalClient = GoformSignalClient(goform, profile)
        val wifiClient = GoformWifiClient(goform, profile)
        val networkClient = GoformNetworkClient(goform, profile)
        val deviceClient = GoformDeviceClient(goform, profile)
        val smsClient = GoformSmsClient(goform)
        val simClient = GoformSimClient(goform, profile)
        AppLogger.i(TAG, "[4] Goform clients initialized")

        val networkController = NetworkController(context, atChannel, networkClient, wifiClient)
        AppLogger.i(TAG, "[5] Controllers initialized")

        return NetworkGraph(
            goformClient = goform,
            signalClient = signalClient,
            wifiClient = wifiClient,
            networkClient = networkClient,
            deviceClient = deviceClient,
            smsClient = smsClient,
            simClient = simClient,
            networkController = networkController
        )
    }

    /**
     * 选设备 profile（计划书 3.2 / 10.2）。
     *
     * - `fieldNormalizationEnabled = false` → 返回 null，设备客户端层退回原样透传（决策 D7 的回退开关）。
     * - `deviceProfileId` 为空 → 注册表默认值（现有部署零配置继续工作）。
     * - 填了但注册表里没有 → **仍然回落默认值** + WARN。型号不认识不能导致整个不工作。
     *
     * 只在构造组件图时读一次；改了配置要重启后台服务。这样"切换后缓存里还躺着上一种形状的
     * 数据"（计划书 1.0.2）在设计上就不成立 —— 组件图重建时各级缓存都是新的。
     */
    private fun resolveDeviceProfile(settings: AppSettings): DeviceProfile? {
        if (!settings.fieldNormalizationEnabled) {
            AppLogger.w(TAG, "字段归一化已关闭（排障开关），设备字段将原样透传，对外字段名会变回设备原名")
            return null
        }
        val id = settings.deviceProfileId
        if (id.isBlank()) return DeviceProfiles.DEFAULT
        val picked = DeviceProfiles.byId(id)
        if (picked == null) {
            AppLogger.w(TAG, "未知的 deviceProfileId=$id，回落 ${DeviceProfiles.DEFAULT.id}" +
                "（可选：${DeviceProfiles.ALL.joinToString { it.id }}）")
            return DeviceProfiles.DEFAULT
        }
        AppLogger.i(TAG, "设备 profile: ${picked.id}（${picked.displayName}）")
        return picked
    }

    /** 存储子图：settings + database + responseCache（原步骤 1 + 8）。 */
    private fun buildStorageGraph(
        settings: AppSettings,
        database: AppDatabase,
        responseCache: com.ufi_axis_core.core.cache.ResponseCache
    ): StorageGraph {
        return StorageGraph(
            settings = settings,
            database = database,
            responseCache = responseCache
        )
    }

    /** 控制器子图：System / ADB / SMS 转发 / 任务 / 下载 控制器（原步骤 5 的 SystemController + 11-12）。 */
    private fun buildControllerGraph(
        context: Context,
        deviceClient: GoformDeviceClient,
        systemCollector: SystemCollector,
        networkController: NetworkController,
        wifiClient: GoformWifiClient,
        networkClient: GoformNetworkClient
    ): ControllerGraph {
        // SystemController 依赖 deviceClient，与原步骤 5 同阶段构造
        val systemController = SystemController(deviceClient)

        // ── 11. ADB 控制器（2026-08-22：移除 Goform USB_PORT_SETTING 依赖，仅管理自连接） ──
        val adbController = com.ufi_axis_core.controller.adb.AdbController(context)
        AppLogger.i(TAG, "[11] ADB controller initialized")

        // ── 12. 扩展组件 ──
        val smsForwardController = com.ufi_axis_core.controller.sms.SmsForwardController(context, systemCollector)
        val actionExecutor = ActionExecutorImpl(networkController, systemController, deviceClient, wifiClient, networkClient)
        val taskScheduler = com.ufi_axis_core.core.scheduler.TaskScheduler(context, actionExecutor)
        // 条件引擎（自动化规则 / 当…就…）：复用 actionExecutor 执行动作
        val conditionEngine = com.ufi_axis_core.core.scheduler.ConditionEngine(context, actionExecutor)
        val downloadManager = com.ufi_axis_core.controller.system.DownloadManager(context.applicationContext)
        val tunnelManager = com.ufi_axis_core.controller.system.TunnelManager(context.applicationContext)
        AppLogger.i(TAG, "[12] Extended components initialized")

        return ControllerGraph(
            systemController = systemController,
            adbController = adbController,
            smsForwardController = smsForwardController,
            downloadManager = downloadManager,
            taskScheduler = taskScheduler,
            conditionEngine = conditionEngine,
            tunnelManager = tunnelManager
        )
    }

    /** 服务子图：HTTP Server + WebSocket 管理器 + 中间件 + 告警引擎 + 调度器 + 线程池。 */
    private fun buildServerGraph(
        server: HttpServer,
        wsManager: WebSocketManager,
        authMiddleware: AuthMiddleware,
        alertEngine: AlertEngine,
        dataScheduler: DataScheduler,
        dynamicThreadPool: DynamicThreadPool,
        webResourceManager: com.ufi_axis_core.util.WebResourceManager
    ): ServerGraph {
        return ServerGraph(
            server = server,
            wsManager = wsManager,
            authMiddleware = authMiddleware,
            alertEngine = alertEngine,
            dataScheduler = dataScheduler,
            dynamicThreadPool = dynamicThreadPool,
            webResourceManager = webResourceManager
        )
    }
}
