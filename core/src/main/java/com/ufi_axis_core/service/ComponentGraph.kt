package com.ufi_axis_core.service


import com.ufi_axis_core.alert.AlertEngine
import com.ufi_axis_core.api.middleware.AuthMiddleware
import com.ufi_axis_core.api.websocket.WebSocketManager
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.adb.AdbController
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
import com.ufi_axis_core.devicespi.DeviceTransport
import com.ufi_axis_core.devicespi.adapter.DeviceHub
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

/** 网络相关：传输层 + 集中处理器 + 网络控制器。 */
data class NetworkGraph(
    /** 防腐层接口（F9）：子图对外只暴露 [DeviceTransport]，具体实现只有 ComponentFactory 知道。 */
    val goformClient: DeviceTransport,
    /**
     * 集中处理器（2026-09-25 批 A1）。替掉了原来的 `simClient: GoformSimClient` ——
     * `GoformSimClient` 现在只被 `ZteGoformAdapter` 持有，子图对外只暴露 [DeviceHub]。
     *
     * 批 A2a 起 `deviceClient: GoformDeviceClient` 同样只被 `ZteGoformAdapter` 持有：
     * device 域整体迁进 `DeviceControl`，该客户端没有读方法，所以没有读侧残留要留字段。
     *
     * 批 A2b 起 `networkClient: GoformNetworkClient` 同理（它是纯写客户端，
     * 两个频段全集方法只是写命令的参数值域，现在只有 adapter 用得到）。
     *
     * 批 B2 起 `signalClient: GoformSignalClient` 也一样：signal 域的 16 个查询方法与
     * `profileId` 整体迁进 `SignalSource`，**没有读侧残留**，所以本子图不再单独留那个字段。
     * 需要 signal 取数的两处（`DataScheduler` / `DataHub`）现在都收 `deviceHub.signal`。
     *
     * 批 C1 起 `smsClient: GoformSmsClient` 也一样：sms 域的 5 个 public 方法与 3 个结论类型
     * 整体迁进 `SmsControl`（信箱的读方法也进去了 —— 短信没有 profile 字段映射，
     * 理由见那个接口的类 KDoc），所以本子图不再留那个字段。需要短信的四处
     * （`SmsController` / `LocalSmsChannel` / `DataScheduler` / `LocalSmsDelivery` 的签名）
     * 现在都收 `deviceHub.sms` 或契约层的结论类型。
     *
     * 批 C2 起 `wifiClient: GoformWifiClient` 也一样：WiFi 的**读侧**（状态 / 已连客户端 /
     * 二维码 / 接入控制名单）与 `setAccessControlList` 整体迁进 `WifiControl`
     * （`AclEntry` / `AclSnapshot` 随之落到 `:core:device-spi`），读侧残留归零，
     * 所以本子图不再留那个字段。三个消费点（`DataHub` 的构造 /
     * `attachDeviceEventWatcher` 的 provider / `RouteContext`）现在都收 `deviceHub.wifi`。
     */
    val deviceHub: DeviceHub,
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
