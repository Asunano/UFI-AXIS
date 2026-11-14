package com.ufi_axis_core.api.routes

import com.ufi_axis_core.contract.Capability

/**
 * 「写端点 → 它必须声明哪个能力」的唯一真源（2026-10-30 越权审查 §2.6）。
 *
 * ## 为什么需要一张表
 *
 * 门禁本身仍是每个 handler 第一行的 `requireCapability(Capability.X)`
 * （`CapabilityMissing` → 501 的出口只有一个，见 `CapabilityGate.kt`），
 * 但**「哪个端点该用哪个 X」此前只存在于 code review 与注释里**。
 * 现有测试 `CapabilityGateTest` 只断言「每个 Capability 至少被引用一次」：
 * 把 `/device/samba` 的门禁误写成 `Capability.SMS` 照样绿，新加写端点漏门禁也照样绿。
 *
 * 本表把它变成可执行事实：`CapabilityBindingTest` 按 [file] + [method] + [path]
 * 定位到具体那一行路由声明，然后断言紧随其后的代码里确实有**同一个能力**的门禁调用。
 *
 * ## 维护规矩
 *
 * 1. **新增带门禁的写端点 → 在这里加一行**（`CapabilityBindingTest` 会因「表里有、
 *    源码里没有」而红，倒逼两端同步）；
 * 2. 不在这里的写端点 = 该功能域**没有能力概念**（如 `log_enabled`）或**已在
 *    `ActionExecutorImpl` 里登记为不需要**，不要为了「看起来整齐」硬填一个能力；
 * 3. [rejectWhenUpdating] 只对「会破坏 OTA 状态」的端点填 true（审查 §2.10 的口径），
 *    不要把所有写端点都标上 —— 那会让更新期间整块 UI 变成 409，用户无法判断发生了什么。
 *
 * @property file 路由文件名（`.../api/routes/` 下的文件名，含 `.kt`）
 * @property method 小写的 Ktor 构建器名（`post` / `put` / `patch` / `delete`）
 * @property path 路由**字面量**（不含 `route("/device")` 那层前缀），如 `"/samba"`
 * @property capability 该端点必须声明的能力
 * @property rejectWhenUpdating 是否必须同时有 `rejectIfUpdating()`
 */
data class WriteEndpointBinding(
    val file: String,
    val method: String,
    val path: String,
    val capability: Capability,
    val rejectWhenUpdating: Boolean = false,
)

/**
 * 当前全部「受能力门禁约束的写端点」。
 *
 * 12 条对应 `Capability` 里除 [Capability.BATTERY]（纯读侧，见 `CapabilityGateTest.exempt`）
 * 之外的全部项；`CELL_LOCK` 与 `NETWORK_MODE` 各有**两个**入口，这里逐条列出 ——
 * 能力是功能域，域内每个写入口都必须拦（见 `Capabilities.kt` 的门禁落点注释）。
 */
val WRITE_ENDPOINT_BINDINGS: List<WriteEndpointBinding> = listOf(
    WriteEndpointBinding("DeviceRoutes.kt", "post", "/debug", Capability.USB_DEBUG),
    WriteEndpointBinding("DeviceRoutes.kt", "post", "/performance", Capability.PERFORMANCE_MODE),
    WriteEndpointBinding("DeviceRoutes.kt", "post", "/fota", Capability.FOTA),
    WriteEndpointBinding("DeviceRoutes.kt", "post", "/samba", Capability.SAMBA),
    WriteEndpointBinding("DeviceRoutes.kt", "post", "/cell-lock", Capability.CELL_LOCK),
    WriteEndpointBinding("DeviceRoutes.kt", "post", "/cell-unlock", Capability.CELL_LOCK),
    WriteEndpointBinding("DeviceRoutes.kt", "post", "/data-limit", Capability.TRAFFIC_LIMIT),
    WriteEndpointBinding("NetworkRoutes.kt", "post", "/band", Capability.BAND_LOCK),
    WriteEndpointBinding("NetworkRoutes.kt", "post", "/mode", Capability.NETWORK_MODE),
    WriteEndpointBinding("NetworkRoutes.kt", "post", "/bearer", Capability.NETWORK_MODE),
    WriteEndpointBinding("RootSmsRoutes.kt", "post", "/send", Capability.SMS),
    WriteEndpointBinding("SimRoutes.kt", "post", "/switch", Capability.SIM_SLOT_SWITCH, rejectWhenUpdating = true),
)
