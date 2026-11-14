package com.ufi_axis_core.service

import com.ufi_axis_core.controller.network.NetworkController
import com.ufi_axis_core.controller.system.SystemController
import com.ufi_axis_core.contract.Capability
import com.ufi_axis_core.contract.NetworkMode
import com.ufi_axis_core.core.scheduler.ActionExecutor
import com.ufi_axis_core.core.scheduler.ActionResult
import com.ufi_axis_core.devicespi.WriteOutcome
import com.ufi_axis_core.devicespi.adapter.DeviceControl
import com.ufi_axis_core.devicespi.adapter.NetworkControl
import com.ufi_axis_core.devicespi.adapter.WifiControl
import com.ufi_axis_core.util.ShellCommandPolicy
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int

/**
 * 定时任务动作执行器实现
 *
 * 持有所有 Controller 引用，根据 actionType 分发到对应的 Controller 方法。
 * 保留 custom_shell 动作以支持向后兼容和高级用户自定义命令。
 *
 * @param device device 域的设备适配接口（2026-09-25 批 A2a 起）。收的是**这一个域**而不是整个
 *   `DeviceHub`：本类只用 [DeviceControl.setIndicatorLight] / [DeviceControl.setPerformanceMode]
 *   两个方法，递整个 hub 等于让它看见所有域，权限比需要的大。
 * @param wifi wifi 域的设备适配接口（批 A2b 起）。同上口径：只用 [WifiControl.setWifiEnabled]。
 * @param network network 域的设备适配接口（批 A2b 起）。只用 [NetworkControl.setRoaming] /
 *   [NetworkControl.setBearerPreference]。
 * @param capabilities 这台设备声明的能力集（2026-09-25 P3-5）。装配层从
 *   `network.deviceHub.capabilities` 取，**判据只有这一份** —— 与 route 层门禁
 *   （`requireCapability`）同一个来源，定时任务不另立第二套判断。
 *   映射表见 [REQUIRED_CAPABILITY]。
 */
class ActionExecutorImpl(
    private val networkController: NetworkController,
    private val systemController: SystemController,
    private val device: DeviceControl,
    private val wifi: WifiControl,
    private val network: NetworkControl,
    private val capabilities: Set<Capability>,
    /**
     * 定时测速（2026-10-06，可空兼容既有装配/测试）：执行一轮「跑+入库+推送」，
     * 返回结果摘要；null = 并发位被占 / 未装配。
     */
    private val speedtestAction: (suspend () -> String?)? = null,
    /**
     * 远端备份（2026-10-06，可空兼容既有装配/测试）：导出加密包推远端+滚动清理，
     * 返回结果摘要；null = 未装配。
     */
    private val remoteBackupAction: (suspend () -> String)? = null,
    /**
     * 流量报告（2026-10-06）：入参 period(day|week|month)，返回摘要文案；
     * null = 未装配。
     */
    private val trafficReportAction: (suspend (String) -> String?)? = null,
    /**
     * 更新进行中的判据（2026-10-30 越权审查 §2.2），装配层传 `WriteGate::updateBusy`。
     *
     * 为什么用回调而不是直接引用 `WriteGate`：本类在 `:core`、`WriteGate` 在 `:core:api`，
     * 直接引用会让「定时任务」这个纯服务层反向依赖路由层。默认 `{ false }` 让既有测试
     * 与局部装配零改动（同 [speedtestAction] 等三个回调的口径）。
     *
     * 漏装配的代价是「OTA 进行中，定时任务仍能重启设备」—— 由装配守卫测试兜住，
     * 见 `ComponentFactory` 里的注入点。
     */
    private val updateBusy: () -> Boolean = { false },
) : ActionExecutor {

    override suspend fun execute(actionType: String, params: Map<String, JsonPrimitive>): ActionResult {
        // ── 能力门禁（2026-09-25 P3-5）──
        // route 层对这些动作已经有 requireCapability 门禁，定时任务是同一批写动作的**第二条入口**：
        // 不判就等于给用户留了一条「web/app 上被 501 拦住的动作，挂成定时任务就能打到设备」的路。
        // 判在 when 之前：不支持时压根不下发，也不会产生一条含义不明的 success。
        val required = REQUIRED_CAPABILITY[actionType]
        if (required != null && required !in capabilities) {
            return ActionResult(false, "设备不支持${capabilityLabel(required)}，本次动作未下发")
        }

        // ── 更新互斥（2026-10-30 越权审查 §2.2）──
        // route 层对 reboot / shutdown 有 `rejectIfUpdating()`（OTA 半写会让设备卡在
        // INSTALLING），而定时任务是同一批动作的**第二条入口**：不判就等于「web 上被 409
        // 拦住的恢复出厂/重启，挂成定时任务就能在 OTA 中途执行」。
        // 只拦会破坏更新状态的动作（[UPDATE_SENSITIVE_ACTIONS]），不是一刀切：
        // 流量报告 / 测速这类只读动作在更新期间照常可用，否则「更新中定时任务全哑」比漏拦更难排查。
        if (actionType in UPDATE_SENSITIVE_ACTIONS && updateBusy()) {
            return ActionResult(false, "更新进行中，本次动作未下发（避免 OTA 半写状态）")
        }
        return when (actionType) {
            // ── 写结果必须接住（2026-09-25 P2-1）──
            // 这八个分支原来把域接口返回的 Boolean 直接丢掉、无条件回 ActionResult(true, "…已开启")。
            // 于是设备侧写失败（会话失效、固件拒绝）时 TaskScheduler / ConditionEngine 仍然把
            // success=true 写进 ExecutionLog —— 用户在「执行记录」里看到「已执行成功」，
            // 而设备状态一点没变，且没有任何别的地方能看出这次写失败了。
            // 现在一律「接住返回值 + 成功失败两套具体文案」，各分支按自己的真实返回类型取值：
            // Boolean 的直接用，WriteOutcome 的取 outcome.ok（只有 network_mode 是后者）。
            "data_toggle" -> {
                val enabled = params["enabled"]?.boolean ?: true
                val ok = networkController.setMobileData(enabled)
                ActionResult(ok, when {
                    ok && enabled -> "移动数据已开启"
                    ok -> "移动数据已关闭"
                    enabled -> "移动数据开启失败"
                    else -> "移动数据关闭失败"
                })
            }
            "wifi_toggle" -> {
                val enabled = params["enabled"]?.boolean ?: true
                // 2026-10-05 R2-1：setWifiEnabled 改三态返回，自动化动作取 ok 即可
                // （Rejected 的具体原因在这里仍值得留日志，动作结果文案保持简单）。
                val outcome = wifi.setWifiEnabled(enabled)
                val ok = outcome.ok
                if (!ok && outcome is com.ufi_axis_core.devicespi.WriteOutcome.Rejected) {
                    android.util.Log.w("ActionExecutor", "wifi_toggle 被设备拒绝: ${outcome.reason}")
                }
                ActionResult(ok, when {
                    ok && enabled -> "WiFi 热点已开启"
                    ok -> "WiFi 热点已关闭"
                    enabled -> "WiFi 热点开启失败"
                    else -> "WiFi 热点关闭失败"
                })
            }
            "airplane_toggle" -> {
                val enabled = params["enabled"]?.boolean ?: true
                val ok = networkController.setAirplaneMode(enabled)
                ActionResult(ok, when {
                    ok && enabled -> "飞行模式已开启"
                    ok -> "飞行模式已关闭"
                    enabled -> "飞行模式开启失败"
                    else -> "飞行模式关闭失败"
                })
            }
            "reboot" -> {
                val ok = systemController.reboot()
                ActionResult(ok, if (ok) "设备重启中..." else "设备重启失败")
            }
            "shutdown" -> {
                val ok = systemController.shutdown()
                ActionResult(ok, if (ok) "设备关机中..." else "设备关机失败")
            }
            "led_toggle" -> {
                val enabled = params["enabled"]?.boolean ?: true
                val ok = device.setIndicatorLight(enabled)
                ActionResult(ok, when {
                    ok && enabled -> "指示灯已开启"
                    ok -> "指示灯已关闭"
                    enabled -> "指示灯开启失败"
                    else -> "指示灯关闭失败"
                })
            }
            "performance_mode" -> {
                val mode = params["mode"]?.int ?: 0
                val ok = device.setPerformanceMode(mode)
                ActionResult(ok, if (ok) "性能模式已切换为 $mode" else "性能模式切换为 $mode 失败")
            }
            "roaming_toggle" -> {
                val enabled = params["enabled"]?.boolean ?: true
                val ok = network.setRoaming(enabled)
                ActionResult(ok, when {
                    ok && enabled -> "数据漫游已开启"
                    ok -> "数据漫游已关闭"
                    enabled -> "数据漫游开启失败"
                    else -> "数据漫游关闭失败"
                })
            }
            "network_mode" -> {
                val mode = params["mode"]?.content ?: NetworkMode.LTE_AND_5G
                // 别名 → 设备侧 BearerPreference（大小写敏感，如 `Only_5G`）的映射在 profile 的
                // WriteSpec 里，这里只传别名；bearer 只用于消息回显。
                // T15：此前这里直接透传 UI 别名（`5G_ONLY`）且没有任何映射，goform 不认，
                // 定时任务选「仅 5G」静默失败。
                val bearer = NetworkMode.toBearer(mode)
                // 与 REST 的 /network/mode 走同一份实现（2026-10-31）：切制式会让设备拆掉 ppp
                // 且不自己重拨，恢复移动数据这件事属于这个业务动作，不能只做在 web 那条入口上。
                val outcome = networkController.setNetworkMode(mode)
                val ok = outcome.ok
                // 值域校验失败时把原因带进任务结果，别让用户只看到"切换失败"
                val rejected = (outcome as? WriteOutcome.Rejected)?.reason
                ActionResult(ok, when {
                    ok -> "网络模式已切换为 ${NetworkMode.label(mode)}"
                    rejected != null -> "网络模式参数被拒绝：$rejected"
                    else -> "网络模式切换失败（${NetworkMode.label(mode)} → $bearer）"
                })
            }
            "speedtest" -> {
                val action = speedtestAction
                if (action == null) {
                    ActionResult(false, "测速执行器未装配")
                } else {
                    val summary = action()
                    if (summary == null) ActionResult(false, "已有测速在跑")
                    else ActionResult(true, summary)
                }
            }
            "remote_backup" -> {
                val action = remoteBackupAction
                if (action == null) ActionResult(false, "远端备份未装配")
                else runCatching { action() }.fold(
                    onSuccess = { ActionResult(true, it) },
                    onFailure = { ActionResult(false, "备份失败: ${it.message}") }
                )
            }
            "traffic_report" -> {
                val action = trafficReportAction
                val period = params["period"]?.content ?: "week"
                if (action == null) ActionResult(false, "流量报告未装配")
                else runCatching { action(period) }.fold(
                    onSuccess = { s ->
                        if (s == null) ActionResult(false, "报告生成失败（流量数据可能还没落库）")
                        else ActionResult(true, s)
                    },
                    onFailure = { ActionResult(false, "报告失败: ${it.message}") }
                )
            }
            "custom_shell" -> {
                val cmd = params["command"]?.content ?: ""
                when {
                    cmd.isBlank() -> ActionResult(false, "命令为空")
                    // 2026-10-30（越权审查 §2.2）：与 `POST /api/shell/exec` 共用同一份危险命令判据。
                    // 在此之前这里直接 `executeAsRoot`，于是 `/api/shell/exec` 明确回 403 的
                    // `reboot` / `insmod` / `kill -9 rild`，只要「建一个定时任务」就能执行 ——
                    // 操作 A（建任务）干了被禁止的 B（危险命令）。判据只有一份
                    //（ShellCommandPolicy），新增危险模式时两个入口同时生效。
                    ShellCommandPolicy.isDangerous(cmd) ->
                        ActionResult(false, "命令被安全策略拦截（可能导致内核崩溃或设备重启）")
                    else -> {
                        val result = ShellExecutor.executeAsRoot(cmd, 120_000L)
                        ActionResult(result.isSuccess, result.stdout.take(500))
                    }
                }
            }
            else -> ActionResult(false, "未知动作类型: $actionType")
        }
    }

    private companion object {

        /**
         * actionType → 需要的 [Capability]。
         *
         * **登记依据只有一条：route 层对同一个写动作确实有 `requireCapability` 门禁。**
         * 逐个核过 `DeviceRoutes` / `NetworkRoutes` / `SimRoutes` / `RootSmsRoutes` 里
         * 全部 12 个门禁调用点之后，与本执行器的 actionType 对得上的只有这两条：
         *
         * | actionType | Capability | route 侧门禁落点 |
         * |---|---|---|
         * | `network_mode` | [Capability.NETWORK_MODE] | `POST /api/network/mode` 与 `/bearer` |
         * | `performance_mode` | [Capability.PERFORMANCE_MODE] | `POST /api/device/performance` |
         *
         * ⚠ 其余动作**刻意不登记**：`data_toggle`（`POST /api/network/data`）、`airplane_toggle`
         * （`POST /api/network/airplane`）、`wifi_toggle`（`WifiRoutes` 下的热点开关）、
         * `led_toggle`、`roaming_toggle`、`reboot` / `shutdown`、`custom_shell`
         * 在 route 侧**都没有**能力门禁。
         * 在这里替它们发明一项能力，等于让定时任务比 REST 入口更严 —— 同一个动作在 web 上能点、
         * 挂成定时任务却回「设备不支持」，而能力集里压根没有那一项可查。
         * 要拦它们得先在 `Capability` 里立项、在 route 侧一起拦，那是另一次决策。
         */
        val REQUIRED_CAPABILITY: Map<String, Capability> = mapOf(
            "network_mode" to Capability.NETWORK_MODE,
            "performance_mode" to Capability.PERFORMANCE_MODE
        )

        /**
         * 更新进行中必须拒绝的动作（2026-10-30 越权审查 §2.2）。
         *
         * 判据只有一条：**这个动作会不会破坏 OTA 状态机**。
         *
         * - `reboot` / `shutdown`：与 route 侧的 `/api/device/reboot`（有 `rejectIfUpdating`）
         *   是同一个动作，定时任务版原先漏了判 —— OTA 拷到一半重启会留下半写状态，
         *   `UpdateManager` 的 BUSY_STATES 会一直卡到人工重置。
         * - `custom_shell`：能 `rm` 更新临时文件、能杀更新进程、能 `reboot`，
         *   等价于 route 侧 `/api/shell/exec`（同样有 `rejectIfUpdating`）。
         *
         * 刻意**不**包含 `network_mode` / `wifi_toggle` / `data_toggle`：它们会短暂断网，
         * 但更新检查/下载失败本来就有重试与 NEED_PUSH 兜底，拦掉它们只会让「更新期间
         * 定时任务集体失效」这个更难排查的问题出现在用户面前。
         */
        val UPDATE_SENSITIVE_ACTIONS: Set<String> = setOf("reboot", "shutdown", "custom_shell")

        /** 失败文案用的中文说法。只服务上表那两项，其余回落到对外 wire 名。 */
        fun capabilityLabel(capability: Capability): String = when (capability) {
            Capability.NETWORK_MODE -> "切换网络制式"
            Capability.PERFORMANCE_MODE -> "性能模式切换"
            else -> capability.wire
        }
    }

}
