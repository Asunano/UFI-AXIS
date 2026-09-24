package com.ufi_axis_core.devicespi

/**
 * 一台设备的**实测**阈值调参。
 *
 * 判据（计划书 §3.4）：**「这处数值换设备时会一起换吗？」** 会 → 进这里。
 * 五个字段全是在 ZTE F50（Unisoc 平台随身 WiFi）上一条条踩出来的，
 * 换一台散热/性能不同的设备必须重新量，所以它们是**设备知识**而不是我们的策略。
 *
 * ## 本批（阶段 2 批 A）只定义数据类，**不替换任何调用点**
 *
 * 下面四处现在仍然各自持有自己的常量/默认值，**一行都没动**：
 * `DownloadManager` / `AlertEngine` / `DataScheduler` / `ShellQoS`。
 * 把它们改成读 [DeviceTuning] 属于**阶段 4 的 4.5**（那一步会改运行时取值路径，
 * 必须单独一批、单独验）。本批动它们等于把「新增类型」混成「改行为」。
 *
 * 所以：现在的每个字段 KDoc 都记着**真值来自哪一处**，阶段 4 照着这份清单接线即可。
 */
data class DeviceTuning(
    /**
     * 温控告警/限速的 warning 阈值，摄氏度。**F50 实测 75。**
     *
     * 来源：`DownloadManager.Config.throttleTempWarn`（`:core:controller`）。
     * 那里的 2026-09-02 注释记了原因：warn 原本是 55°C，而 F50 这类 UFI **空载就 64~66°C**
     * —— 等于开机即限速（1000KB/s + 并发砍半）且永不退出，所以按实测空载温度上调到 75。
     * 同文件的 `migrateConfig()` 还会把存量配置里 `< 70f` 的旧值强行抬到 75f。
     */
    val thermalWarnC: Float,

    /**
     * 温控告警/限速的 critical 阈值，摄氏度。**F50 实测 85。**
     *
     * 来源：`DownloadManager.Config.throttleTempCritical`（`:core:controller`），
     * 与 [thermalWarnC] 同一条 2026-09-02 结论（75/85 成对上调，`migrateConfig()` 抬 `< 80f` 的旧值）。
     */
    val thermalCriticalC: Float,

    /**
     * 温度回差（hysteresis）带宽，摄氏度。**F50 实测 3。**
     *
     * 来源：`AlertEngine` 的 `TEMP_HYSTERESIS_C = 3.0`（`:core:alert`，那边是 `Double`）。
     * 理由记在 `leveledWithHysteresis` 的注释里：**零回差 + 边沿触发 = 阈值附近微抖导致的告警风暴**；
     * Unisoc 热区读数的正常抖动就有 1~2°C，所以带宽取 3°C。
     */
    val thermalJitterC: Float,

    /**
     * 开机预热期（boot grace period），毫秒。**F50 实测 90_000（90 秒）。**
     *
     * 来源：`DataScheduler` 的 `BOOT_GRACE_DEFAULT_MS = 90_000L`（`:core:scheduler`；
     * 同一数值也是 `AppSettings.monitorBootGraceMs` 的默认值）。
     * 那里的注释记着：Unisoc + Android 13 + 1.5GB RAM **实测 ~60s** 系统服务才基本稳定
     * （zygote / AMS / PMS / dex2oat 打满全部核心），**留 30s 余量**取 90s。
     * 预热期内不入库、不判本地告警，但 WS 实时推送照常。
     */
    val bootGraceMs: Long,

    /**
     * root shell 的并发许可数。**F50 实测 5。**
     *
     * 来源：`ShellQoS` 的 `DEFAULT_ROOT_PERMITS = 5`（`:core:common`）。
     * 这个数字是安全上限而不是性能调参：并发 `su -c` 一多就会压垮 `sprd_ipc_probe` 驱动、
     * 导致内核 panic（`ShellRoutes` 的注释里也钉了同一条结论）。
     */
    val rootShellPermits: Int,
)
