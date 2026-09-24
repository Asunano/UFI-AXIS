package com.ufi_axis_core.devicespi

import com.ufi_axis_core.deviceschema.DeviceProfile

/**
 * 设备插件（聚合根）—— **适配一台新设备 = 新增一个本接口的实现**。
 *
 * 一个插件把「换设备时会一起换掉」的东西聚在一起：字段/命令映射（[profile]）、
 * 协议传输（[createTransport]）、实测调参（[tuning]），以及自我识别（[probe]）。
 * 上层（route / collector / controller / scheduler）不认识任何具体插件，
 * 只经中间控制层拿到这四样东西。
 *
 * ## 本批（阶段 2 批 A）刻意**没有**的两个成员
 *
 * 计划书 §3.2 的骨架里还有 `capabilities` 与 `platform(ctx)`，这里**故意不带**，
 * 不是漏了：
 *
 * 1. **`val capabilities: Set<Capability>`** —— `Capability` 要定在 `:core:contract`，
 *    而那是双端共享的**冻结区**（app / web 都照它写）。冻结区里的东西宁可晚定也不要
 *    定错再改，所以按计划书排到**阶段 3** 和能力门禁一起落。
 * 2. **`fun platform(ctx: Context): PlatformAdapter`** —— `PlatformAdapter` 的
 *    `atTransports()` 返回 `AtTransport`，而 `AtTransport` 现在在 `:core:collector`。
 *    让 `:core:device-spi` 依赖 collector 会直接成环
 *    （collector → goform → device-spi → collector）。所以它必须等 `AtTransport`
 *    先上移到本模块，两件事一起归**阶段 4**。
 *
 * 换句话说：这两条是**依赖方向**与**冻结区纪律**决定的排期，不是设计上不要。
 * 谁要补它们，先把上面那两个前置条件解决掉。
 */
interface DevicePlugin {

    /** 稳定标识（如 `"zte-f50"`）。进日志、进配置项，**不许因为改显示名而改它**。 */
    val id: String

    /** 人类可读名称（如 `"ZTE F50"`），只用于日志与诊断展示。 */
    val displayName: String

    /** 本设备的字段/命令映射登记表。 */
    fun profile(): DeviceProfile

    /**
     * 按配置构造本设备的传输层实现。
     *
     * 谁来调：装配层（`ComponentFactory`，阶段 2 的批 B 才接线）。插件自己不缓存返回值 ——
     * 传输层持有会话与 HTTP 连接池，生命周期跟组件图，不跟插件对象（插件通常是 `object`）。
     */
    fun createTransport(cfg: TransportConfig): DeviceTransport

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
