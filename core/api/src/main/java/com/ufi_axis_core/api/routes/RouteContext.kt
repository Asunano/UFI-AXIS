package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.DataHub
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.goform.GoformDeviceClient
import com.ufi_axis_core.controller.goform.GoformGateway
import com.ufi_axis_core.controller.goform.GoformNetworkClient
import com.ufi_axis_core.controller.goform.GoformSignalClient
import com.ufi_axis_core.controller.goform.GoformSimClient
import com.ufi_axis_core.controller.goform.GoformWifiClient
import com.ufi_axis_core.controller.network.NetworkController

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
 *
 * 阶段1（C3 / A1）骨架重构：将原本扁平的 20 个依赖按语义拆分为 4 个 dep bundle
 * （[CollectorDeps] / [NetworkDeps] / [StorageDeps] / [SystemDeps]），为后续 A1 将
 * bundle 直接注入各 Route 构造函数、彻底消除扁平 [RouteContext] 做准备。
 *
 * 为保证各 Route 实现零改动（路由体内暂继续用 [RouteContext] 透传属性过渡，不彻底消除
 * 20 个字段），[RouteContext] 仍通过同名透传属性（body getter）暴露与重构前完全一致的
 * 全部依赖引用。运行期组件集合与重构前完全一致。
 */
data class RouteContext(
    val collector: CollectorDeps,
    val network: NetworkDeps,
    val storage: StorageDeps,
    val system: SystemDeps
) {
    // ── 透传属性：保持与重构前完全一致的一级属性，各 Route 零改动 ──
    val systemCollector: SystemCollector get() = collector.systemCollector
    val telephonyCollector: TelephonyCollector get() = collector.telephonyCollector
    val atChannel: ATChannel get() = collector.atChannel

    // F9 防腐层：以 GoformGateway 接口暴露（示范迁移，首个调用方）
    val goformClient: GoformGateway get() = network.goformClient
    val signalClient: GoformSignalClient get() = network.signalClient
    val networkClient: GoformNetworkClient get() = network.networkClient
    val deviceClient: GoformDeviceClient get() = network.deviceClient
    val wifiClient: GoformWifiClient get() = network.wifiClient
    val simClient: GoformSimClient get() = network.simClient
    val networkController: NetworkController get() = network.networkController


    val database: AppDatabase get() = storage.database
    val dataScheduler: DataScheduler get() = storage.dataScheduler
    val responseCache: ResponseCache get() = storage.responseCache
    val dataHub: DataHub get() = storage.dataHub
    val settings: AppSettings get() = storage.settings

    val systemController: SystemController get() = system.systemController
    val dynamicThreadPool: DynamicThreadPool get() = system.dynamicThreadPool
}

/** 采集器依赖：系统 / 电话采集器 + AT 通道。 */
data class CollectorDeps(
    val systemCollector: SystemCollector,
    val telephonyCollector: TelephonyCollector,
    val atChannel: ATChannel
)

/** 网络依赖：全部 Goform 客户端 + 网络控制器。 */
data class NetworkDeps(
    // F9 防腐层：以 GoformGateway 接口暴露
    val goformClient: GoformGateway,
    val signalClient: GoformSignalClient,
    val networkClient: GoformNetworkClient,
    val deviceClient: GoformDeviceClient,
    val wifiClient: GoformWifiClient,
    val simClient: GoformSimClient,
    val networkController: NetworkController
)

/** 存储 / 数据依赖：数据库 + 调度器 + 响应缓存 + DataHub + 配置。 */
data class StorageDeps(
    val database: AppDatabase,
    val dataScheduler: DataScheduler,
    val responseCache: ResponseCache,
    val dataHub: DataHub,
    val settings: AppSettings
)

/** 系统 / 基础设施依赖：系统控制器 + 动态线程池。 */
data class SystemDeps(
    val systemController: SystemController,
    val dynamicThreadPool: DynamicThreadPool
)
