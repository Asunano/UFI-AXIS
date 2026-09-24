package com.ufi_axis_core.devicespi

/**
 * 平台适配层（计划书 §3.2 / 阶段 4 的 4.1）—— **一台设备「操作系统这一侧」的事实**。
 *
 * 与 [DevicePlugin] 其它成员的分工：
 * - [DeviceProfile][com.ufi_axis_core.deviceschema.DeviceProfile] 管**设备后台协议**（goform 字段与命令）；
 * - [DeviceTransport] 管**怎么跟后台说话**；
 * - 本接口管**绕过后台、直接跟本机平台打交道**的那几件事：AT 通道、热区读数、网络栈重启。
 *
 * 实现放 `:core:device-plugins`（可以依赖具体协议与 Android API），
 * 契约放本模块 —— 上层（collector / controller / route）只认这个接口。
 *
 * ## 本接口刻意**没有**的两个成员
 *
 * 计划书 §3.2 的骨架里还有两个，2026-09-24 阶段 4 开工前的实测把它们否掉 / 推后了：
 *
 * - **`privilegeEscalation(): PrivilegeStrategy?` —— 不做（§8 裁决 ④）**。
 *   实测本仓的提权只有**一条**实际路径：ADB 自连 `localhost:5555`（uid 2000），
 *   `ShellExecutor.executeAsRoot()` 全走它，回落是普通 `sh -c`（无特权）；`su -c` 已彻底移除。
 *   Samba `root preexec` 那条虽然部署与 60s 保活都在跑，但**执行入口没人调用，是死代码**。
 *   也就是说「`SambaPreexecStrategy` + `AdbOnlyStrategy` 二选一」是为一条不存在的路径造抽象 ——
 *   一个永远只有单一实现的策略接口，只会让读代码的人以为这里真有选择。
 *   等 Samba 那条路径真的被接上（登记在 §15 的 P1-38）再谈。
 * - **`readBattery(): BatteryReading?` —— 推后到 3B**。
 *   它存在的意义是驱动 `Capability.BATTERY`，而 `BATTERY` **不在阶段 3 已落地的 10 项里**
 *   （3A 只定了 SMS / SIM_SLOT_SWITCH / BAND_LOCK / CELL_LOCK / NETWORK_MODE /
 *   SAMBA / USB_DEBUG / FOTA / PERFORMANCE_MODE / TRAFFIC_LIMIT）。
 *   先加读法、能力集里却没有对应项，等于加一个没人消费的成员。
 *   `BATTERY` / `ROOT_SHELL` / `AT_CHANNEL` 三项由本接口推导是 **3.9（3B）**，届时一起加。
 */
interface PlatformAdapter {

    /**
     * 平台标识。
     *
     * ⚠ **这是对外可见的字符串**，不是内部标签：阶段 4 的 4.6 会把
     * `ATChannel.detectPlatform()` 的结果换成本字段，而那个结果现在就在
     * `/api/at/platform` 与 `/api/at/status` 的 `platform` 键上下发。
     * 所以取值要跟现有下发值对齐，**改它等于一次静默的对外 JSON 变更**。
     */
    val name: String

    /**
     * 本平台可用的 AT 通道，**按优先级排列**（下标 0 优先）。
     *
     * 调用方（`ATChannel.init()`）按顺序 `probe()`，第一条可用的即选中。
     * 限流（500ms 最小间隔）、指数退避、20 次熔断仍然在 `ATChannel` 那一层 ——
     * 那是**我们的策略**，不是设备事实，见 [AtTransport] 的接口注释。
     *
     * 本机一条都不可用时返回**空列表**，不要返回一个「probe 永远 false 的假实现」：
     * 空列表让 `ATChannel` 直接判定「本机无 AT 通道」，假实现只会多绕一圈。
     */
    fun atTransports(): List<AtTransport>

    /**
     * 热区读数，**摄氏度**。
     *
     * ## 两条硬约束
     *
     * 1. **读不到返回 `null`，不许返回 `0`** —— 0 会被下游当成一个真实读数
     *    （「设备 0°C」既不会触发任何阈值、也不会被判成异常，于是热保护静默失效）。
     * 2. **这是「原始热区读数」，不承载任何阈值判断。** 本方法只回答「现在多少度」，
     *    不回答「该不该限速 / 该不该降频 / 该不该告警」。
     *
     * ## 为什么第 2 条要写这么重
     *
     * 实测（§8 裁决 ②）仓里有**三套完全不同**的温度阈值，动作、类型、单位、可配路径都不一样：
     *
     * - **下载限速** 75 / 85（`DownloadManager.Config`，`Float`，改 aria2 并发与限速，
     *   还有第 4 档 `critical + 10` = 95°C 的 `forcePauseAll`）；
     * - **采集降频 / 熔断** 70 / 80（`AppSettings.monitorThermalWarnC/CriticalC`，
     *   **`Int` 毫摄氏度**，调 QoS 许可、拉长 cache TTL、暂停采集）；
     * - **用户告警** 65 / 75（`AlertEngine.AlertConfig`，`Double`，**带 3°C 回差**，
     *   写 alert_records + 推送）。
     *
     * 三者是**三件事**，不是一件事的三套阈值。把判断塞进本方法（比如「超过阈值就返回 null」
     * 或者加一个 `isOverheat()`）会让这三条链路被迫共用一个口径 ——
     * 那正是「重构顺手统一，结果告警行为变了」的典型（§11.9）。
     *
     * ## 口径：本方法是「全热区最大值」，另外两处上报读法留在原地（4.3 结论）
     *
     * 现有读法本身就有多个口径：`DataScheduler` 取全热区最大值、`QoSRoutes` 只读
     * `thermal_zone0`，Unisoc 上两者稳定差数度。4.3 落地后的分工是：
     *
     * - **判据侧**（下载限速 `DownloadManager`、采集降频 / 熔断 + 温度告警 `DataScheduler`）
     *   全部改成调本方法 —— 也就是统一到**全热区最大值**这一个口径；
     * - **上报侧**（`SystemCollector` 的 zone0 + 全热区列表、`QoSRoutes` 的 zone0 原值）
     *   **不动**：那是给人看的读数，不是判定阈值用的，语义不同。
     *
     * 所以 adapter 最终**没有**加第二个「只读 zone0」的方法 —— 那会是一个只被上报侧使用、
     * 却长得像判据读法的成员。要不要把上报侧也收进来是另一个 PR 的事。

     *
     * @return 摄氏度；读不到 / 不支持一律 `null`。
     */
    suspend fun readTemperature(): Float?

    /**
     * 重启网络栈（F50 是 `AT+SFUN=5` 关射频、`AT+SFUN=4` 开）。
     *
     * 本方法只承载**「这台设备怎么重启网络栈」这一份知识**：发哪几条命令、什么顺序、
     * 中间等多久、怎么判成功。**执行通道不在这里**，见下。
     *
     * ## 为什么执行器要由调用方注入（§8 的裁决 ⑤）
     *
     * 实现要真干活就得能发 AT 命令，而全仓唯一的执行通道 `ATChannel` 住在 `:core:collector`，
     * 依赖方向是 `ATChannel` → 本接口的实现（批 F 起 `ATChannel` 由插件供给下发实现）。
     * 让实现反过来持有 `ATChannel`，等于把**策略层**（全局互斥、500ms 最小间隔、
     * 指数退避、20 次熔断）塞进**平台实现**里 —— 方向倒置，而且 `:core:device-plugins`
     * 会因此多一条对 `:core:collector` 的依赖、实现里还要多一份可变状态（setter 或延迟注入）。
     *
     * 所以签名收一个 `at` 执行器：调用方（`NetworkController`）本来就持有 `ATChannel`，
     * 由它把「怎么发」递进来，本接口只回答「发什么」。附带收益是本方法**可单测** ——
     * 传一个假执行器就能断言命令序列与判据，不需要真设备。
     *
     * @param at 单条 AT 命令的执行器：入参是命令原文，返回设备回显；发不出去 / 超时返回 `null`。
     *   超时值、限流、熔断全在调用方那一侧决定，本接口不关心。
     *   ⚠ 实现**不许**把这个 lambda 存起来跨调用复用：它绑的是调用方那一次的通道状态。
     *
     * @return `true` = 这次确实重启成功。
     *
     *   ⚠ **`false` 现在无法区分「本平台不支持」与「这次失败了」** —— 两者都是 `false`。
     *   这是照搬现状：唯一的调用点 `NetworkController.lockBands()` 把返回值原样放进
     *   `/api/network/band` 的 `network_restarted` 字段，那个布尔字段本身就只有两态，
     *   区分开就得同时改端点契约与两端解析。本批（批 G）是等价搬迁，不动对外语义，
     *   所以这个局限**写明而不修**。不支持该动作的平台一律返回 `false`、**不要抛异常**：
     *   调用点把它当成一次普通的「操作失败」处理。
     *   真要分开，正确做法是换成一个三态返回（不支持 / 成功 / 失败）并同步改端点，
     *   那是独立的一次对外变更。
     */
    suspend fun restartNetworkStack(at: suspend (String) -> String?): Boolean
}
