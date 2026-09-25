package com.ufi_axis_core.devicespi

/**
 * 一台设备的**实测**阈值调参。
 *
 * 判据（计划书 §3.4）：**「这处数值换设备时会一起换吗？」** 会 → 进这里。
 * 五个字段全是在 ZTE F50（Unisoc 平台随身 WiFi）上一条条踩出来的，
 * 换一台散热/性能不同的设备必须重新量，所以它们是**设备知识**而不是我们的策略。
 *
 * ## 本批（阶段 4 批 F）只改**字段定义**，仍然**不接线**
 *
 * `DownloadManager` / `AlertEngine` / `DataScheduler` / `ShellQoS` 现在仍然各自持有自己的
 * 常量/默认值，**本批一行都没动**。把它们改成读本类属于 **4.5 的后半**
 * （那一步会改运行时取值路径，必须单独一批、单独验）。
 *
 * ## 为什么阶段 4 开工前先做了一次改名（§8 裁决 ② / ③）
 *
 * 原来的 `thermalWarnC` / `thermalCriticalC` 这两个名字**撞了三套完全不同的阈值**：
 *
 * | 这套阈值 | 取值 | 类型与单位 | 它管的动作 | 可配路径 |
 * | --- | --- | --- | --- | --- |
 * | **下载限速** | 75 / 85（+10 的第 4 档 95） | `Float`，摄氏度 | 改 aria2 并发与限速、95°C 全部暂停 | `DownloadManager.Config` |
 * | **采集降频 / 熔断** | 70 / 80 | **`Int`，毫摄氏度** | 调 QoS 许可、拉长 cache TTL、暂停采集 | `AppSettings.monitorThermal*` |
 * | **用户告警** | 65 / 75（带 3°C 回差） | `Double`，摄氏度 | 写 alert_records + 推送 | `AlertEngine.AlertConfig` |
 *
 * 动作、类型、温度单位、可配路径、有无回差全不同 —— **是三件事，不是一件事的三套阈值**。
 *
 * ⚠ **最容易踩的坑**：[downloadThrottleWarnC]`= 75f` 与 `AlertEngine` 的
 * `temperatureCritical = 75.0` **数值相同、语义相反**（一个是「开始限速」的下沿，
 * 一个是「最高级告警」的上沿）。谁看到「两边都是 75」就把它们接到一起，
 * 会**静默改掉告警行为，而且单测不会红**。
 *
 * 裁决是**字段名说实话**：凡是只服务下载限速的就叫 `downloadThrottle*`。
 * **另两套不进本类**：它们是**用户可配的策略**，不是设备事实
 * （用户改过一次之后，插件里写什么都不算）。
 *
 * 同一轮裁决还**删掉了 `rootShellPermits`**：实测它记错了适用范围 ——
 * 用户默认值是 **3**（`ShellQoS.DEFAULT_QOS_SHELL_MAX`），一旦用户写过任何一次配置，
 * 生效值就是 3；文档里那个 5 只在「从没写过配置」时成立。
 * 它是 **QoS 配置默认值，不是设备事实**，放在插件里只会让人以为它说了算。
 *
 * 所以：现在的每个字段 KDoc 都记着三件事 —— **真值来自哪一处**、**它管什么动作**、
 * **哪些同名/近名阈值不归它管**。4.5 接线时照着这份清单来，不要按名字猜。
 */
data class DeviceTuning(
    /**
     * **下载限速**的 warning 阈值，摄氏度。**F50 实测 75。**
     *
     * 来源：`DownloadManager.Config.throttleTempWarn`（`:core:controller`）。
     * 那里的 2026-09-02 注释记了原因：warn 原本是 55°C，而 F50 这类 UFI **空载就 64~66°C**
     * —— 等于开机即限速（1000KB/s + 并发砍半）且永不退出，所以按实测空载温度上调到 75。
     * 同文件的 `migrateConfig()` 还会把存量配置里 `< 70f` 的旧值强行抬到 75f。
     *
     * **它管什么**：越过这条线 → aria2 并发砍半 + 全局限速，退回线下恢复。只此一件事。
     *
     * **哪些阈值不归它管**（§8 裁决 ②）：
     * - `AppSettings.monitorThermalWarnC`（采集降频，**Int 毫摄氏度**，F50 侧默认 70）——
     *   那是用户可配策略，它管的是采集频率与 QoS 许可，不是下载。
     * - `AlertEngine.AlertConfig.temperatureWarning`（用户告警，`Double`，65）。
     * - ⚠ 尤其不归它管：`AlertEngine.AlertConfig.temperatureCritical` = **75.0** ——
     *   **与本字段数值相同、语义相反**，一个是限速的起点、一个是告警的最高级。
     *   看到「都是 75」就接线 = 静默改掉告警行为，且单测不会红。
     */
    val downloadThrottleWarnC: Float,

    /**
     * **下载限速**的 critical 阈值，摄氏度。**F50 实测 85。**
     *
     * 来源：`DownloadManager.Config.throttleTempCritical`（`:core:controller`），
     * 与 [downloadThrottleWarnC] 同一条 2026-09-02 结论（75/85 成对上调，
     * `migrateConfig()` 抬 `< 80f` 的旧值）。
     *
     * **它管什么**：越过这条线 → 比 warn 更狠的一档限速（并发压到最低 + 更低的速率上限）。
     * **还不是暂停** —— 暂停是再上面一档，见 [downloadThrottleForcePauseOffsetC]。
     *
     * **哪些阈值不归它管**：
     * - `AppSettings.monitorThermalCriticalC`（采集熔断，**Int 毫摄氏度**，F50 侧默认 80）——
     *   那条越线是「停止采集」，与下载无关。
     * - `AlertEngine.AlertConfig.temperatureCritical`（用户告警的 75.0）——
     *   它比本字段**低** 10°C，因为告警本来就该比限速更早响。
     *   别因为「都叫 critical」把两条线对齐。
     */
    val downloadThrottleCriticalC: Float,

    /**
     * **下载限速第 4 档**（强制全部暂停）相对 [downloadThrottleCriticalC] 的**偏移量**，摄氏度。
     * **F50 实测 10**（即 85 + 10 = 95°C）。
     *
     * 来源：`DownloadManager` 里第 4 档的判据写的是 `temp >= critical + 10` → `forcePauseAll()`。
     * 那个 `+10` 此前**没有任何常量承载**，是直接写在条件里的字面量 ——
     * 本批把它提成设备知识（换一台散热不同的设备，这个余量要重新量）。
     *
     * **为什么是偏移量而不是绝对温度**：它在实现里就是跟着 critical 走的
     * （critical 一改，暂停线自动跟着走）。写成绝对值 95 会让两条线可以各自被改成不自洽的组合。
     *
     * **它管什么**：越过 `critical + 本字段` → **暂停全部下载任务**（不是限速，是停）。
     *
     * **哪些阈值不归它管**：这一条在另两套阈值里**根本没有对应物** ——
     * 采集那套的最高档是「暂停采集」，告警那套的最高档只是推一条通知。
     * 不要给它们找一个「第 4 档」。
     *
     * 值域约束：**必须 > 0**（`PluginContractTest` 守着）。填 0 会让「暂停」与「最狠限速」
     * 同温触发，等于删掉了第 3 档。
     */
    val downloadThrottleForcePauseOffsetC: Float,

    /**
     * **用户告警**的温度回差（hysteresis）带宽，摄氏度。**F50 实测 3。**
     *
     * 来源：`AlertEngine` 的 `DEFAULT_TEMP_HYSTERESIS_C = 3.0`（`:core:alert`，那边是 `Double`）。
     * 消费点：`AlertEngine(temperatureHysteresisC = ...)`，由 `ComponentFactory` 接线。
     * 理由记在 `leveledWithHysteresis` 的注释里：**零回差 + 边沿触发 = 阈值附近微抖导致的告警风暴**；
     * Unisoc 热区读数的正常抖动就有 1~2°C，所以带宽取 3°C。
     *
     * **它管什么**：只管**用户告警**那一套（`AlertEngine` 的 65/75 上下沿）——
     * 告警级别升上去之后，要低于「阈值 − 本字段」才降级。
     * 名字里没有 `alert` 前缀是因为实测它在全仓只有这一处语义、取值也只有一份，
     * 没有撞名风险；但它与 `downloadThrottle*` **不是同一件事**，这一点必须记在这里。
     *
     * **哪些阈值不归它管**：
     * - `downloadThrottle*` 那三条**没有回差**（`DownloadManager` 是直接比较，
     *   越线即限速、退线即恢复）。不要「顺手给限速也加上回差」——
     *   那是行为变更，不是接线。
     * - 采集降频那套同样没有回差。
     */
    val thermalJitterC: Float,

    /**
     * 开机预热期（boot grace period），毫秒。**F50 实测 90_000（90 秒）。**
     *
     * 来源：`DataScheduler` 的 `BOOT_GRACE_DEFAULT_MS = 90_000L`（`:core:scheduler`；
     * 同一数值也是 `AppSettings.monitorBootGraceMs` 的默认值）。
     * 那里的注释记着：Unisoc + Android 13 + 1.5GB RAM **实测 ~60s** 系统服务才基本稳定
     * （zygote / AMS / PMS / dex2oat 打满全部核心），**留 30s 余量**取 90s。
     *
     * 消费点：`DataScheduler(bootGraceDeviceDefaultMs = ...)`，由 `ComponentFactory` 接线。
     * 用户在 `AppSettings.monitorBootGraceMs` 配过就以用户为准，本字段只是设备侧兜底。
     *
     * **它管什么**：预热期内**不入库、不判本地告警**，但 WS 实时推送照常。
     *
     * **哪些阈值不归它管**：与温度三套阈值毫无关系 ——
     * 它按 `elapsedRealtime()` 判时间，不看温度。放在本类里只因为
     * 「换一台启动更快/更慢的设备，这个余量要重新量」。
     */
    val bootGraceMs: Long,
)
