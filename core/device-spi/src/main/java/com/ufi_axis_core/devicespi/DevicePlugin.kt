package com.ufi_axis_core.devicespi

import android.content.Context
import com.ufi_axis_core.contract.Capability
import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.devicespi.adapter.DeviceAdapter

/**
 * 设备插件（聚合根）—— **适配一台新设备 = 新增一个本接口的实现**。
 *
 * 一个插件把「换设备时会一起换掉」的东西聚在一起：字段/命令映射（[profile]）、
 * 协议传输（[createTransport]）、六个域的实现（[createAdapter]）、平台适配（[platform]）、
 * 实测调参（[tuning]）、能力集（[capabilities]），以及自我识别（[probe]）。
 * 上层（route / collector / controller / scheduler）不认识任何具体插件，
 * 只经中间控制层拿到这几样东西。
 *
 * ## 成员已全部到齐（2026-09-24 阶段 4 批 F）
 *
 * 计划书 §3.2 的骨架里有两个成员曾经刻意缺席，现在都落地了：
 *
 * - **[platform] —— 本批（阶段 4 批 F）落地。** 原先缺席的理由是
 *   `PlatformAdapter.atTransports()` 返回 [AtTransport]，而 [AtTransport] 当时住在
 *   `:core:collector`：让 `:core:device-spi` 依赖 collector 会直接成环
 *   （collector → goform → device-spi → collector）。
 *   本批先把 [AtTransport] 上移到本模块（零 import、零 Android 类型，不需要新增任何依赖），
 *   环就不存在了，`platform()` 随即补齐。
 * - **[capabilities] —— 阶段 3（2026-09-24）落地。** 当时推迟的理由是
 *   「`Capability` 要进 `:core:contract` 这个双端冻结区，宁可晚定也不要定错再改」，
 *   现在那 10 个域已经按真机实测定稳，见 [Capability] 的文件头。
 *
 * ## 2026-09-25 批 A3：新增 [createAdapter]
 *
 * 这是计划书骨架之外的一个成员，接缝迁移（批 A1 ~ C2 把六个域搬进
 * [com.ufi_axis_core.devicespi.adapter.DeviceAdapter]）做完之后才有意义：
 * 在它之前，装配层必须自己 `new` 六个具体协议客户端、并为此把传输层**向下转型**成
 * 具体实现类。现在插件自己交付 adapter，装配层只认本模块的契约类型。
 * 两轮前核过一次并停手，当时的阻塞是 WiFi 读侧与 sms 域**还没有域接口**，
 * 装配层必须把那两个具体客户端交给 5 个上层消费点 —— 批 C1 / C2 把它们还完了。
 */
interface DevicePlugin {

    /** 稳定标识（如 `"zte-f50"`）。进日志、进配置项，**不许因为改显示名而改它**。 */
    val id: String

    /** 人类可读名称（如 `"ZTE F50"`），只用于日志与诊断展示。 */
    val displayName: String

    /**
     * 本设备支持的**功能域**（计划书 §7 / §11.4）。
     *
     * ## 怎么填
     *
     * **有实测依据、且 core 侧确实有对应写 route 的才填**。判据是「用户可见动作是否可达」，
     * 不是「某个通道是否支持」（§11.6）—— 所以填之前要能回答：
     * 「这台设备上点这个开关，命令真的下去了吗？」
     *
     * 与 `SettingKey` **不要求一一对应**：10 个域 vs 29 个 key，
     * 没有 capability 的写操作照旧不拦（只有这里列出的域走 route 门禁）。
     * 所以不要为了「凑齐」去声明一个没实测过的域 —— 声明了就等于告诉两端「这个开关能点」。
     *
     * ## 缺一项的后果
     *
     * 对应 route 回 **501 + `ErrorCode.NOT_SUPPORTED`**（由 route 层统一门禁产生），
     * app / web 把开关灰掉并写明「当前设备不支持」。
     * 所以少填一项 = 一个本来能用的功能被灰掉；多填一项 = 用户点了没反应。
     *
     * 返回值必须是**不可变**的（用 `setOf(...)`）：装配层会把它递给数据层与 route 层，
     * 运行期不允许有人往里加一项 —— 换设备要重启后台服务（§11.10）。
     */
    val capabilities: Set<Capability>


    /** 本设备的字段/命令映射登记表。 */
    fun profile(): DeviceProfile

    /**
     * 按配置构造本设备的传输层实现。
     *
     * 谁来调：装配层（`ComponentFactory`，阶段 2 的批 B 才接线）。插件自己不缓存返回值 ——
     * 传输层持有会话与 HTTP 连接池，生命周期跟组件图，不跟插件对象（插件通常是 `object`）。
     */
    fun createTransport(cfg: TransportConfig): DeviceTransport

    /**
     * 按传输层 + 两份 profile 交付本设备的 [DeviceAdapter]（六个域的实现）。
     *
     * 谁来调：装配层（`ComponentFactory.buildNetworkGraph`），2026-09-25 批 A3 接线。
     *
     * ## 为什么 [transport] 是**入参**，而不是本方法内部再造一个
     *
     * 整个组件图**只许有一份传输层实例**：它持有会话（cookie、wa / cr 版本号）、
     * 登录退避与 HTTP 连接池，造第二份等于两套会话互相把对方顶下线。
     * 装配层仍然自己调 [createTransport]（`ComponentGraph.NetworkGraph.goformClient` 那条链路
     * 要用**同一个**实例 —— `PairingRoutes` / `DeviceRoutes` 改设备后台口令走的就是它），
     * 然后把那一份递进来。所以实现里**不许**再调一次 [createTransport]。
     *
     * ## 为什么「往具体协议类型的向下转型」归插件、不归装配层
     *
     * 批 A3 之前这一步在 `ComponentFactory.buildNetworkGraph` 里（`transport as? GoformClient`），
     * 理由是六个 goform 客户端的构造参数是 `:core:goform` 模块内部那层传输接口，
     * 用 [DeviceTransport] 接不上、而装配层又不许写那个内部接口的类型名。
     * 现在这一步移进插件：**transport 是插件自己造的，它知道那是什么类型**，
     * 装配层从此只认 [DeviceTransport]，一个具体协议符号都不需要认识。
     * 实现里转型失败的真实含义是「有人换掉了本插件的 transport 实现」，
     * 请照 `ZteF50Plugin` 那样写成一句话再抛 —— 这条路径上只留一行 ClassCastException 堆栈没人看得懂。
     *
     * @param transport 装配层调 [createTransport] 造出来的那一份实例（理由见上）。
     * @param commandProfile 命令表那一份，**非空**：字段归一化可以关，命令表不能关。
     *   取值是 `DeviceRuntime.commandProfile`（**选中插件**的 profile，不是注册表默认值）。
     * @param normalizeProfile 字段归一化那一份，`null` = 排障开关关掉了归一化
     *   （取值是 `DeviceRuntime.profile`）。两份 profile 的分工见 `DeviceRuntime` 的字段 KDoc ——
     *   **不许**在实现里用非空兜底把它填上，那个排障开关会永远报 `true`。
     */
    fun createAdapter(
        transport: DeviceTransport,
        commandProfile: DeviceProfile,
        normalizeProfile: DeviceProfile?,
    ): DeviceAdapter

    /**
     * 本设备的平台适配层（AT 通道 / 热区读数 / 网络栈重启），见 [PlatformAdapter]。
     *
     * 谁来调：装配层（`ComponentFactory`）。与 [createTransport] 同一条纪律 ——
     * **插件自己不缓存返回值**：适配层可能持有进程、句柄一类的东西，
     * 生命周期跟组件图，不跟插件对象（插件通常是 `object`）。
     *
     * @param ctx 平台适配需要的 Android 上下文（读 `/sys` 之外的东西时要它）。
     *   本模块**刻意是 Android library 而不是纯 JVM**，正是为了这个参数
     *   （理由写在 `build.gradle.kts` 的约束 2 上，比这一步早了两个阶段定下来）。
     *   ⚠ 传 `applicationContext` 或 Service Context，不要传 Activity ——
     *   适配层的寿命跟后台服务一样长。
     */
    fun platform(ctx: Context): PlatformAdapter

    /** 本设备的实测阈值调参。见 [DeviceTuning] 对「本批只定义、不替换调用点」的说明。 */
    fun tuning(): DeviceTuning

    /**
     * 匹配置信度：**0 = 不适用**，越大越匹配。
     *
     * 硬约束（违反就等于把「选型」变成一次可能超时的设备交互）：
     * - **只读 [ProbeEnv] 里已备好的廉价指纹**；
     * - **禁止发 AT**；
     * - **禁止登录设备**；
     * - **禁止任何 I/O**（文件、网络、shell 全都不许）。
     *
     * [ProbeEnv] 由中间控制层采一次后共享给所有插件，所以插件里不需要、也不许再去读一遍。
     * 全部插件都返回 0 时由注册表的默认插件兜底 —— **认不出设备不能导致整个不工作**。
     */
    suspend fun probe(env: ProbeEnv): Int
}
