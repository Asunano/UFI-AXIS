package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.DataHub
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.collector.telephony.TelephonyCollector
import com.ufi_axis_core.controller.network.NetworkController

import com.ufi_axis_core.controller.system.SystemController
import com.ufi_axis_core.core.cache.ResponseCache
import com.ufi_axis_core.core.database.AppDatabase
import com.ufi_axis_core.core.scheduler.DataScheduler
import com.ufi_axis_core.devicespi.DeviceTransport
import com.ufi_axis_core.devicespi.adapter.DeviceHub
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

    // F9 防腐层：以 DeviceTransport 接口暴露（示范迁移，首个调用方）
    val goformClient: DeviceTransport get() = network.goformClient
    /**
     * 集中处理器（2026-09-25 批 A1 立，批 A2a 起 device 域也走它，批 A2b 起 network / wifi 域也走它，
     * 批 B2 起 signal 域也走它，批 C2 起 WiFi 读侧也走它）。
     * 上层 route 只认它，不再直接拿 `Goform*Client`。现已迁入的域：
     * `deviceHub.sim` / `deviceHub.device` / `deviceHub.network` / `deviceHub.wifi` / `deviceHub.signal`
     * （wifi 域的**读写操作**都走 `deviceHub.wifi`），其余域后续一批一个地迁。
     */
    val deviceHub: DeviceHub get() = network.deviceHub
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

/** 网络依赖：传输层 + 集中处理器 + 网络控制器。 */
data class NetworkDeps(
    // F9 防腐层：以 DeviceTransport 接口暴露
    val goformClient: DeviceTransport,
    /**
     * 集中处理器（2026-09-25 批 A1）。替掉了原来的 `simClient: GoformSimClient` ——
     * 上层只依赖这一个类型，换设备时装配层改一处、route 零改动。
     *
     * 批 A2a 起 `deviceClient: GoformDeviceClient` 也被它替掉：device 域整体迁进
     * `DeviceControl`，`GoformDeviceClient` 没有读方法，所以没有读侧残留要单独留字段。
     *
     * 批 A2b 起 `networkClient: GoformNetworkClient` 同理（纯写客户端）。
     *
     * 批 B2 起 `signalClient: GoformSignalClient` 也被它替掉：signal 域的 16 个查询方法
     * 与 `profileId` 一个不剩地迁进了 `SignalSource`（走 `deviceHub.signal`），
     * **没有读侧残留**。各 Routes 的 `signalClient` 私有 getter
     * 改成从 `ctx.deviceHub.signal` 取，路由体一行没动。
     *
     * 批 C2 起 `wifiClient: GoformWifiClient` 也被它替掉：WiFi 的**读侧**（状态 / 已连客户端 /
     * 二维码 / 接入控制名单）与 `setAccessControlList` 迁进了 `WifiControl`
     * （`AclEntry` / `AclSnapshot` 随之落到契约层），所以这里不再留那个字段 ——
     * 留一个 `deviceHub.wifi` 的等价物就是两份判据。`WifiRoutes` 改成从
     * `ctx.deviceHub.wifi` 取，端点行为一字未变。
     */
    val deviceHub: DeviceHub,
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
