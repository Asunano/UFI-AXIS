package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.DataHub
import com.ufi_axis_core.api.middleware.QoSMiddleware
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.adb.AdbController
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.controller.goform.GoformDeviceClient
import com.ufi_axis_core.controller.goform.GoformNetworkClient
import com.ufi_axis_core.controller.goform.GoformSignalClient
import com.ufi_axis_core.controller.goform.GoformSimClient
import com.ufi_axis_core.controller.goform.GoformWifiClient
import com.ufi_axis_core.controller.network.NetworkController
import com.ufi_axis_core.controller.sim.SimController
import com.ufi_axis_core.controller.system.SystemController
import com.ufi_axis_core.core.cache.ResponseCache
import com.ufi_axis_core.core.database.AppDatabase
import com.ufi_axis_core.core.scheduler.DataScheduler
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.DynamicThreadPool

/**
 * Route 上下文——集中持有所有 Route 共享的依赖，
 * 避免分散的构造函数参数传递。
 *
 * 创建时机：所有后端共享组件构造完成后（ComponentFactory 第 12.5～13 步之间）。
 */
data class RouteContext(
    // ── 采集器 ──
    val systemCollector: SystemCollector,
    val telephonyCollector: TelephonyCollector,
    val atChannel: ATChannel,

    // ── Goform 客户端层 ──
    val goformClient: GoformClient,
    val signalClient: GoformSignalClient,
    val networkClient: GoformNetworkClient,
    val deviceClient: GoformDeviceClient,
    val wifiClient: GoformWifiClient,
    val simClient: GoformSimClient,

    // ── 控制器 ──
    val systemController: SystemController,
    val networkController: NetworkController,
    val simController: SimController,
    val adbController: AdbController,

    // ── 数据层 ──
    val database: AppDatabase,
    val dataScheduler: DataScheduler,
    val responseCache: ResponseCache,
    val dataHub: DataHub,

    // ── 配置 ──
    val settings: AppSettings,

    // ── 基础设施 ──
    val dynamicThreadPool: DynamicThreadPool,
    val qosMiddleware: QoSMiddleware
)