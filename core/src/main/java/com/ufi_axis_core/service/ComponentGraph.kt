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
    val downloadManager: DownloadManager,
    val taskScheduler: TaskScheduler,
    val conditionEngine: ConditionEngine,
    val tunnelManager: TunnelManager
)

/** 服务端相关：HTTP Server + 推送 / 鉴权 / 告警 / 调度 / 线程池。 */
data class ServerGraph(
    val server: HttpServer,
    val wsManager: WebSocketManager,
    val authMiddleware: AuthMiddleware,
    val alertEngine: AlertEngine,
    val dataScheduler: DataScheduler,
    val dynamicThreadPool: DynamicThreadPool,
    val webResourceManager: WebResourceManager
)
