package com.ufi_axis_core.api

import com.ufi_axis_core.contract.Capability
import com.ufi_axis_core.contract.DeviceFields
import com.ufi_axis_core.core.cache.CacheTTL
import com.ufi_axis_core.core.cache.ResponseCache
import com.ufi_axis_core.core.scheduler.DataScheduler
import com.ufi_axis_core.devicespi.adapter.SignalSource
import com.ufi_axis_core.devicespi.adapter.WifiControl
import com.ufi_axis_core.util.AppLogger
import kotlinx.serialization.json.*

/**
 * 统一请求数据中心（DataHub）
 *
 * ## 架构
 * ```
 * Routes ──▶ DataHub ──┬── 实时缓存 (DataScheduler)
 *                       ├── 按需缓存 (ResponseCache)
 *                       └── 透传查询 (signalQuery/wifiQuery)
 *                                  │
 *                                  ▼
 *                          GoformClient ──▶ ZTE Device
 * ```
 *
 * ## 使用
 * ```kotlin
 * // 高频实时数据 — DataScheduler 自动采集
 * dataHub.latestCpu.value
 * dataHub.latestTraffic.value
 *
 * // 带缓存的 goform 查询
 * dataHub.getNetworkTypeInfo()    // 30s TTL，跨路由共享
 * dataHub.getWifiSettingsMerged() // ResponseCache，30s TTL
 *
 * // 无缓存透传 — signalQuery {} 直接代理到 SignalSource（批 B2 起是域接口，原来是 GoformSignalClient）
 * dataHub.signalQuery { getCellInfo() }
 * dataHub.signalQuery { getFullStatus() }
 *
 * // WiFi 透传
 * dataHub.wifiQuery { getConnectedClients() }
 * ```
 *
 * ## 新增 goform 查询指南
 *
 * **透传（把设备原始字段直接吐给客户端）已经是禁止做法**，只有诊断端点例外
 * （`GET /api/device/goform`、`GET /api/wifi/module-info`，见
 * `DeviceFields.UNSTABLE_ENDPOINTS`）。原因：透传等于把 goform 的字段命名当成对外
 * 契约，设备侧一改名，web 与 app 都得跟着改；而且固件新增的字段会自动泄漏出去。
 *
 * 正确做法（场景 A/B）：
 * 1. **字段登记**：在 `ZteGoformProfile` 加一条 `fieldOf(canonical, group, "设备原名"…)`，
 *    canonical 取 `DeviceFields` 常量（没有就先加，新键用 `snake_case`）。
 * 2. **归一化在设备客户端层做**，不在 route 层：`GoformSignalClient` / `GoformWifiClient`
 *    对外返回的就是 canonical 数据。这样本类和 `ResponseCache` 缓存到的天然是 canonical。
 *
 *    ⚠ **这条纪律有一个已登记的例外：signal 域的第 1 层字段映射在消费方。**
 *    `SignalCollector`（WS `signal` 频道与 `/api/network/signal` 的数据来源）拿
 *    `DeviceProfile` 自己做设备原名 → canonical 的映射，而
 *    [SignalSource.getSignalInfo] 出来的是**设备原始响应**。理由（三条）与它为什么不是
 *    「纪律没落实」写在 [SignalSource] 的类 KDoc 里 —— 简言之：那一层与「PCI 匹配邻区」
 *    「Telephony 兜底」两层是同一段编排，需要原始响应与归一化结果同时在场。
 *    纪律本身不变：**除这一处以外**，读侧归一化仍然只许发生在设备客户端 / 适配实现侧。
 * 3. **需要缓存** → 在本类加专用方法，TTL 选 `CacheTTL` 常量；**跨路由共享** →
 *    `ResponseCache.getOrPut` / `getOrPutAny`。形状统一为
 *    `cache.getOrPut(key, ttl) { client.getXxx() }`（客户端已归一化，缓存不做转换）。
 * 4. route 里**不允许出现设备侧字段名字面量**；`scripts/verify-api-contract.mjs`
 *    的 `p0_goform_field_leaks` 会扫出来并**阻断**（阶段 4.4 起是 P0）。
 *
 * `signalQuery { }` / `wifiQuery { }` 仍然保留，但它们只是"无缓存地调一次客户端方法"，
 * 不代表可以把原始字段直接返回 —— 归一化的责任在被调用的那个客户端方法里。
 *
 * ## 设备选型的三个诊断字段为什么是「值」而不是一个对象
 *
 * `/api/diagnose` 的 `device_profile` 块要的三样东西都从本类取：
 * [deviceProfileId]（生效中的可空 profile id）、[devicePluginId]、[deviceSelection]。
 * 前者是既有链路（来自 [SignalSource.profileId]，goform 系那份最终读的是
 * `GoformFieldMapper.profileId`），后两个是阶段 2 的 2.8 新加的。
 *
 * 后两个**刻意只传两个不可变的 String**，而不是把装配层的 `DeviceRuntime` 整个塞进来：
 *
 * 1. 传对象会让 `:core:api` 依赖 `:core:device-spi` —— 数据层要为「诊断显示」这一个用途
 *    多背一条模块依赖；
 * 2. `DeviceRuntime` 是**选型对象**，它的生命周期属于装配层（构造组件图时定一次）。
 *    把它交给数据层等于把这个生命周期扩散出去，后面很容易演化成「从 DataHub 拿 plugin
 *    去发请求」，而取数该走的是已注入的那几个客户端。
 *
 * 两个值在组件图构造时就定死、之后不会变（换设备要重启后台服务），所以传值不丢信息。
 *
 * ## [deviceCapabilities] 同一条口径（阶段 3 的 3.3）
 *
 * 能力集也是**只传一个不可变集合**，理由与上面两个 String 逐条相同（不让数据层认识
 * `DevicePlugin`、不扩散选型对象的生命周期）。它同样不参与任何取数逻辑：
 * 唯一的消费者是 route 层的门禁（`requireCapability`）与 `GET /api/device/capabilities`。
 */
class DataHub(
    private val scheduler: DataScheduler,
    /**
     * signal 域的设备适配接口（2026-09-25 批 B2 起是 [SignalSource]，原来是
     * `GoformSignalClient`）。装配层传 `deviceHub.signal`，本类不再认识具体协议客户端。
     */
    private val signalClient: SignalSource,
    private val wifiClient: WifiControl,
    private val responseCache: ResponseCache,
    /**
     * 选中插件的 id（如 `zte-f50`）。**恒非空**：认不出设备也会回落到默认插件。
     * 只为 `/api/diagnose` 的 `device_profile.plugin_id` 与
     * `/api/device/capabilities` 的 `plugin_id` 存在（**同源同值**），不参与任何取数逻辑。
     */
    val devicePluginId: String,
    /**
     * 本次选型是怎么定下来的，**已经是对外的小写 snake 取值**
     * （`configured` / `probed` / `default` / `fallback`）。
     * 只为 `/api/diagnose` 的 `device_profile.selection` 存在。
     */
    val deviceSelection: String,
    /**
     * 选中插件声明的能力集（`DevicePlugin.capabilities`）。
     *
     * 只读快照：构造组件图时定一次，运行期不变（换设备要重启后台服务，见计划书 §11.10）。
     * 下面用 `toSet()` 再拷一份，防的是调用方递进来一个 `MutableSet` 之后还留着引用 ——
     * 能力集一旦能在运行期被改，门禁的行为就取决于「谁最后改了它」。
     */
    deviceCapabilities: Set<Capability>
) {
    /** 见构造参数的 KDoc。route 层门禁与 `/api/device/capabilities` 从这里取。 */
    val deviceCapabilities: Set<Capability> = deviceCapabilities.toSet()

    companion object {
        private const val TAG = "DataHub"

        /** 身份信息查询失败的负缓存时长：够短能自愈，够长能挡住 3s 轮询的重复打设备。 */
        private const val IDENTITY_NEGATIVE_TTL_MS = 30_000L
    }


    // ═══════════════════════════════════════════════════════════
    // 第一部分：实时缓存（DataScheduler 周期采集）
    // ═══════════════════════════════════════════════════════════

    /** 信号信息（RSRP/SINR/RSRQ/RSSI/RAT/运营商），DataScheduler 3s 周期 */
    suspend fun getSignalInfo(): Map<String, Any> = scheduler.getSignalInfo()

    /** 实时网速（bytes/s），DataScheduler 3s 周期 */
    val latestTraffic get() = scheduler.latestTraffic.value

    /** Goform 月流量（rxBytes, txBytes），DataScheduler 60s 周期 */
    val goformTraffic get() = scheduler.goformTraffic.value

    /** CPU 信息，DataScheduler 2s 周期 */
    val latestCpu get() = scheduler.latestCpu.value

    /** 电池信息，DataScheduler 30s 周期 */
    val latestBattery get() = scheduler.latestBattery.value

    // ═══════════════════════════════════════════════════════════
    // 第二部分：按需缓存（带 TTL 的专用方法）
    // ═══════════════════════════════════════════════════════════

    /**
     * [ResponseCache getOrPutAny 30s] 网络类型信息（运营商、网络制式）—【可缓存数据】
     * 供 NetworkRoutes /status 和 DeviceRoutes /info 共用。
     * goform HTTP 查询可能因路由器固件无响应导致长时间阻塞，使用 withTimeout 保证 5s 内返回。
     *
     * 【1.8】[GoformNetworkInfo.networkType] 现在是**已映射的可读文案**（`"5G"`），
     * 不再是设备侧的数字码 —— 映射发生在 profile 的 `NETWORK_TYPE_DECODER` 里。
     * 所以 route 层不要再翻译一次（原来那份 `GoformClient.mapNetworkType()` 已删）。
     */
    suspend fun getNetworkTypeInfo(): GoformNetworkInfo {
        return responseCache.getOrPutAny("hub:network-type-info", 30_000L) {
            val canon = try {
                kotlinx.coroutines.withTimeout(5_000L) {
                    signalClient.getConnectionInfo()?.values
                }
            } catch (_: Exception) {
                AppLogger.w("DataHub", "goform network-type-info query timeout")
                null
            }
            fun pick(key: String) = canon?.get(key)?.jsonPrimitive?.contentOrNull ?: ""
            GoformNetworkInfo(
                networkType = pick(DeviceFields.Connection.NETWORK_TYPE),
                networkProvider = pick(DeviceFields.Connection.NETWORK_PROVIDER),
                pppStatus = pick(DeviceFields.Connection.PPP_STATUS),
            )
        }
    }

    /**
     * [ResponseCache 30min] 设备身份信息（msisdn/IMEI/IMSI/ICCID/版本号等 20 字段）
     *
     * 不用 `getOrPut`：它会把查询失败时的空对象也按 30 分钟 TTL 落缓存，一次 goform
     * 超时就让「本机号码/IMEI/IMSI/ICCID」空掉半小时、期间刷新页面也无效 —— 这是
     * 「设备概览有概率不加载」被放大成持续性故障的原因。这里改成：
     * 成功才用长 TTL，失败只用 30s 负缓存（避免失败时每次仪表盘轮询都重打设备）。
     */
    suspend fun getDeviceIdentity(): JsonObject? {
        val cached = responseCache.get("device:identity")
        // 命中负缓存（空对象）时直接返回 null，不重打设备
        if (cached is JsonObject) return cached.takeIf { it.isNotEmpty() }

        val fresh = signalClient.getDeviceIdentity()?.values?.takeIf { it.isNotEmpty() }
        if (fresh == null) {
            responseCache.put("device:identity", JsonObject(emptyMap()), IDENTITY_NEGATIVE_TTL_MS)
            return null
        }
        responseCache.put("device:identity", fresh, CacheTTL.DEVICE_IDENTITY)
        return fresh
    }


    /**
     * [ResponseCache 10s] 流量限额与月用量（goform getDataUsage）。
     * 仪表盘 /api/dashboard/summary 以 3~5s 周期轮询，若每次都透传 goform，
     * 会与 DataScheduler 的 3s 周期采集争抢 GoformQoS 查询信号量（4 并发），
     * 拖慢响应并挤占其他页面配额。限额开关/月用量变化缓慢，10s 缓存足够新鲜。
     */
    suspend fun getTrafficLimit(): JsonObject? {
        val result = responseCache.getOrPut("hub:traffic-limit", 10_000L) {
            signalClient.getDataUsage()?.values ?: JsonObject(emptyMap())
        }
        return result as? JsonObject
    }

    /**
     * [ResponseCache 30s] WiFi 设置。
     *
     * 合并 + 归一化都在 [WifiControl.getWifiSettingsMerged] 里（设备适配实现侧），
     * 这里只负责缓存 —— 所以缓存里存的已经是 canonical 数据。
     * 原先这里还有一份 `processModuleInfo` 把 ZTE 原生键改写成 goform 扁平键，
     * 那是第二份映射表，已迁进 `ZteGoformProfile.liftActiveAccessPoint`。
     */
    suspend fun getWifiSettingsMerged(): JsonObject {
        val result = responseCache.getOrPut("wifi:settings", CacheTTL.WIFI_SETTINGS) {
            wifiClient.getWifiSettingsMerged().values
        }
        return result as JsonObject
    }

    // ═══════════════════════════════════════════════════════════
    // 第三部分：通用透传查询（替代 13 个独立 pass-through 方法）
    // ═══════════════════════════════════════════════════════════

    /**
     * 统一信号查询入口 — 直接代理到 [SignalSource]。
     *
     * 用法：
     * ```kotlin
     * val status = dataHub.signalQuery { getFullStatus() }
     * val pin    = dataHub.signalQuery { getBandLockStatus() }

     * ```
     *
     * 适用于：getFullStatus, getLanSettings,

     * getDeviceInfo, getDeviceVersion,
     * getCellInfo, getNeighborCellInfo, getDataUsage,
     * getBandLockStatus, queryDeviceSettings
     *
     * 批 B1：本方法是**泛型透传**，不需要改 —— 已归一化的那几个客户端方法现在返回
     * `NormalizedFields`（`:core:device-schema`），[T] 原样把它带出来。调用点用 `.values`
     * 解包成 `JsonObject` 再序列化，**解包后的内容与改造前逐字一致**。
     * 哪些方法有这个保证、哪些仍是裸 `JsonObject?`（原样透传），看
     * [SignalSource] 的类 KDoc 与各方法签名。
     *
     * 批 B2：receiver 从具体类 `GoformSignalClient` 换成域接口 [SignalSource] ——
     * 这是接缝上最后一处「泛型透传却把具体类当 receiver」的硬绑带。方法名一个没改，
     * 所以全部 16 处调用点零改动；换设备时这里也不用再动。
     */

    suspend fun <T> signalQuery(block: suspend SignalSource.() -> T): T = signalClient.block()

    /**
     * 统一 WiFi 查询入口 — 直接代理到 [WifiControl]。
     *
     * 用法：
     * ```kotlin
     * val clients = dataHub.wifiQuery { getConnectedClients() }
     * ```
     */
    suspend fun <T> wifiQuery(block: suspend WifiControl.() -> T): T = wifiClient.block()

    /**
     * 字段覆盖率诊断（计划书 10.1）。
     *
     * 之所以在 DataHub 上开这个方法而不是让上层直接调 `signalClient`：`:core:network`
     * （`/api/diagnose` 的落地处）不依赖 `:core:goform`，批 B2 之前 `GoformSignalClient`
     * 这个类型在那边不可见。返回 `JsonObject` 就没有跨模块可见性问题
     * （批 B2 之后 receiver 已是协议无关的 [SignalSource]，但本方法的形状不动：
     * 上层要的是一份报告，不是一个域接口）。
     *
     * **按需调用**：内部会逐分组向设备发查询。
     */
    suspend fun fieldCoverage(): JsonObject = signalClient.diagnoseFieldCoverage()

    /** 生效中的设备 profile id；null = 归一化已关（诊断用，见计划书 10.2）。不发设备查询。 */
    val deviceProfileId: String? get() = signalClient.profileId



    // ═══════════════════════════════════════════════════════════
    // 第四部分：缓存失效
    // ═══════════════════════════════════════════════════════════

    /** WiFi 变更后调用（路由 handler 内 suspend 上下文） */
    suspend fun invalidateWifi() {
        responseCache.invalidate("wifi:*")
    }

    /** 设备设置变更后调用 */
    suspend fun invalidateDeviceSettings() {
        responseCache.invalidate("device:settings")
    }

    /** 网络变更后调用 */
    suspend fun invalidateNetwork() {
        responseCache.invalidateAny("hub:network-type-info")
        responseCache.invalidate("network:*")
    }
}
