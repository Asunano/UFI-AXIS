package com.ufi_axis_core.service


import com.ufi_axis_core.alert.AlertEngine
import com.ufi_axis_core.api.middleware.AuthMiddleware
import com.ufi_axis_core.api.websocket.WebSocketManager
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.adb.AdbController
import com.ufi_axis_core.controller.goform.*
import com.ufi_axis_core.controller.network.NetworkController
import com.ufi_axis_core.controller.sms.SmsForwardController
import com.ufi_axis_core.controller.sms.SmsRuleStore
import com.ufi_axis_core.controller.system.DownloadManager
import com.ufi_axis_core.controller.system.SystemController
import com.ufi_axis_core.controller.system.TunnelManager
import com.ufi_axis_core.core.database.AppDatabase
import com.ufi_axis_core.core.cache.ResponseCache
import com.ufi_axis_core.core.scheduler.ConditionEngine
import com.ufi_axis_core.core.scheduler.DataScheduler
import com.ufi_axis_core.core.scheduler.TaskScheduler
import com.ufi_axis_core.core.server.HttpServer
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.DynamicThreadPool
import com.ufi_axis_core.util.WebResourceManager

/**
 * 组件依赖图 — ComponentFactory.build() 的产物。
 * 包含所有已创建并完成依赖注入的组件，BackendService 按需持有引用。
 *
 * 阶段1（C2 / A3）重构：将原本扁平的 27 个组件按语义拆分为 5 个子图
 * （[NetworkGraph] / [CollectorGraph] / [StorageGraph] / [ControllerGraph] / [ServerGraph]），
 * 便于后续按子图维度做依赖注入与测试。
 *
 * 调用方（如 [com.ufi_axis_core.service.BackendService]）直接经 network / collector / storage /
 * controller / serverGraph 五个子图访问组件，不再保留扁平的透传属性。
 */
data class ComponentGraph(
    val network: NetworkGraph,
    val collector: CollectorGraph,
    val storage: StorageGraph,
    val controller: ControllerGraph,
    val serverGraph: ServerGraph
) {
    // ── 子图聚合：调用方直接经 network / collector / storage / controller / serverGraph 子图访问组件 ──
}

/** 网络相关：全部 Goform 客户端 + 网络 / SIM 控制器。 */
data class NetworkGraph(
    /** 防腐层接口（F9）：子图对外只暴露 [GoformGateway]，具体实现只有 ComponentFactory 知道。 */
    val goformClient: GoformGateway,
    val signalClient: GoformSignalClient,
    val wifiClient: GoformWifiClient,
    val networkClient: GoformNetworkClient,
    val deviceClient: GoformDeviceClient,
    val smsClient: GoformSmsClient,
    val simClient: GoformSimClient,
    val networkController: NetworkController
)

/** 采集器相关：AT 通道 + 系统 / 电话采集器。 */
data class CollectorGraph(
    val atChannel: ATChannel,
    val systemCollector: SystemCollector,
    val telephonyCollector: TelephonyCollector
)

/** 存储相关：配置 + 数据库 + 响应缓存。 */
data class StorageGraph(
    val settings: AppSettings,
    val database: AppDatabase,
    val responseCache: ResponseCache
)

/** 控制相关：系统 / ADB / SMS 转发 / 下载 / 任务调度控制器 / 条件引擎 / 内网穿透。 */
data class ControllerGraph(
    val systemController: SystemController,
    val adbController: AdbController,
    val smsForwardController: SmsForwardController,
    // 暴露它只为了让停止流程能收掉它自己的 CoroutineScope + 落盘最后一批命中次数
    //（理由同上面的 pushService）。它本身是在 build() 里先于控制器子图创建的，
    // 六个判定接入点各自持有引用，这里只是给停机流程一个入口。
    val smsRuleStore: SmsRuleStore,
    val downloadManager: DownloadManager,
    val taskScheduler: TaskScheduler,
    val conditionEngine: ConditionEngine,
    val tunnelManager: TunnelManager
)

/** 服务端相关：HTTP Server + 推送 / 鉴权 / 告警 / 调度 / 线程池。 */
data class ServerGraph(
    val server: HttpServer,
    val wsManager: WebSocketManager,
    // 暴露它只为了让停止流程能收掉它自己的 CoroutineScope：
    // 内部持有 `Dispatchers.Default + SupervisorJob()`，不 cancel 的话服务停掉之后
    // 排队中的推送还会继续往已关闭的 WebSocketManager 里灌。
    val pushService: com.ufi_axis_core.api.websocket.WebSocketPushService,
    // 通知分发器（2026-09-08 阶段 1）。暴露它的理由与 pushService / smsRuleStore 相同：
    // 停机流程需要一个入口把注册表清空 —— 否则组件都停了，触发源还会继续往里 emit，
    // 投递最终打在已经关闭的 WebSocketManager / 已 cancel 的 historyScope 上。
    val notificationDispatcher: com.ufi_axis_core.notify.NotificationDispatcher,
    // Webhook 渠道（2026-09-09 阶段 2）。暴露它只为了停机时关掉那个 HttpClient 的连接池 ——
    // 分发器只清注册表，渠道自己的资源由 owner 收（与 pushService 同分工）。
    val webhookChannel: com.ufi_axis_core.controller.notify.WebhookChannel,

    val authMiddleware: AuthMiddleware,
    val alertEngine: AlertEngine,
    val dataScheduler: DataScheduler,
    val dynamicThreadPool: DynamicThreadPool,
    val webResourceManager: WebResourceManager
)
