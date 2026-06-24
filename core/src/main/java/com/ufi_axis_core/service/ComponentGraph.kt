package com.ufi_axis_core.service

import com.ufi_axis_core.alert.AlertEngine
import com.ufi_axis_core.api.middleware.AuthMiddleware
import com.ufi_axis_core.api.middleware.QoSMiddleware
import com.ufi_axis_core.api.websocket.WebSocketManager
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.goform.*
import com.ufi_axis_core.controller.network.NetworkController
import com.ufi_axis_core.controller.sim.SimController
import com.ufi_axis_core.controller.system.SystemController
import com.ufi_axis_core.core.database.AppDatabase
import com.ufi_axis_core.core.cache.ResponseCache
import com.ufi_axis_core.core.scheduler.DataScheduler
import com.ufi_axis_core.core.server.HttpServer
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.DynamicThreadPool

/**
 * 组件依赖图 — ComponentFactory.build() 的产物。
 * 包含所有已创建并完成依赖注入的组件，BackendService 按需持有引用。
 */
data class ComponentGraph(
    val settings: AppSettings,
    val database: AppDatabase,
    val atChannel: ATChannel,
    val systemCollector: SystemCollector,
    val telephonyCollector: TelephonyCollector,

    // Goform 层
    val goformClient: GoformClient,
    val signalClient: GoformSignalClient,
    val wifiClient: GoformWifiClient,
    val networkClient: GoformNetworkClient,
    val deviceClient: GoformDeviceClient,
    val smsClient: GoformSmsClient,
    val simClient: GoformSimClient,

    // 控制器
    val networkController: NetworkController,
    val simController: SimController,
    val systemController: SystemController,
    val adbController: com.ufi_axis_core.controller.adb.AdbController,

    // 基础设施
    val wsManager: WebSocketManager,
    val alertEngine: AlertEngine,
    val dynamicThreadPool: DynamicThreadPool,
    val qosMiddleware: QoSMiddleware,
    val authMiddleware: AuthMiddleware,
    val dataScheduler: DataScheduler,

    // 缓存
    val responseCache: ResponseCache,

    // 扩展组件
    val smsForwardController: com.ufi_axis_core.controller.sms.SmsForwardController,
    val taskScheduler: com.ufi_axis_core.core.scheduler.TaskScheduler,
    val downloadManager: com.ufi_axis_core.controller.system.DownloadManager,

    // HTTP Server
    val server: HttpServer
)
