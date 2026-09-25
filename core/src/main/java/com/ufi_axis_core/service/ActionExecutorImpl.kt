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
    private val capabilities: Set<Capability>
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
                val ok = wifi.setWifiEnabled(enabled)
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
                val outcome = network.setBearerPreference(mode)
                val ok = outcome.ok
                // 值域校验失败时把原因带进任务结果，别让用户只看到"切换失败"
                val rejected = (outcome as? WriteOutcome.Rejected)?.reason
                ActionResult(ok, when {
                    ok -> "网络模式已切换为 ${NetworkMode.label(mode)}"
                    rejected != null -> "网络模式参数被拒绝：$rejected"
                    else -> "网络模式切换失败（${NetworkMode.label(mode)} → $bearer）"
                })
            }
            "custom_shell" -> {
                val cmd = params["command"]?.content ?: ""
                if (cmd.isBlank()) ActionResult(false, "命令为空")
                else {
                    val result = ShellExecutor.executeAsRoot(cmd, 120_000L)
                    ActionResult(result.isSuccess, result.stdout.take(500))
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

        /** 失败文案用的中文说法。只服务上表那两项，其余回落到对外 wire 名。 */
        fun capabilityLabel(capability: Capability): String = when (capability) {
            Capability.NETWORK_MODE -> "切换网络制式"
            Capability.PERFORMANCE_MODE -> "性能模式切换"
            else -> capability.wire
        }
    }

}
