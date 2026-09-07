package com.ufi_axis_core.service

import com.ufi_axis_core.controller.goform.GoformDeviceClient
import com.ufi_axis_core.controller.goform.GoformNetworkClient
import com.ufi_axis_core.controller.goform.GoformWifiClient
import com.ufi_axis_core.controller.goform.WriteOutcome
import com.ufi_axis_core.controller.network.NetworkController
import com.ufi_axis_core.controller.system.SystemController
import com.ufi_axis_core.contract.NetworkMode
import com.ufi_axis_core.core.scheduler.ActionExecutor
import com.ufi_axis_core.core.scheduler.ActionResult
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int

/**
 * 定时任务动作执行器实现
 *
 * 持有所有 Controller 引用，根据 actionType 分发到对应的 Controller 方法。
 * 保留 custom_shell 动作以支持向后兼容和高级用户自定义命令。
 */
class ActionExecutorImpl(
    private val networkController: NetworkController,
    private val systemController: SystemController,
    private val deviceClient: GoformDeviceClient,
    private val wifiClient: GoformWifiClient,
    private val networkClient: GoformNetworkClient
) : ActionExecutor {

    override suspend fun execute(actionType: String, params: Map<String, JsonPrimitive>): ActionResult {
        return when (actionType) {
            "data_toggle" -> {
                val enabled = params["enabled"]?.boolean ?: true
                networkController.setMobileData(enabled)
                ActionResult(true, if (enabled) "移动数据已开启" else "移动数据已关闭")
            }
            "wifi_toggle" -> {
                val enabled = params["enabled"]?.boolean ?: true
                wifiClient.setWifiEnabled(enabled)
                ActionResult(true, if (enabled) "WiFi 热点已开启" else "WiFi 热点已关闭")
            }
            "airplane_toggle" -> {
                val enabled = params["enabled"]?.boolean ?: true
                networkController.setAirplaneMode(enabled)
                ActionResult(true, if (enabled) "飞行模式已开启" else "飞行模式已关闭")
            }
            "reboot" -> {
                systemController.reboot()
                ActionResult(true, "设备重启中...")
            }
            "shutdown" -> {
                deviceClient.shutdownDevice()
                ActionResult(true, "设备关机中...")
            }
            "led_toggle" -> {
                val enabled = params["enabled"]?.boolean ?: true
                deviceClient.setIndicatorLight(enabled)
                ActionResult(true, if (enabled) "指示灯已开启" else "指示灯已关闭")
            }
            "performance_mode" -> {
                val mode = params["mode"]?.int ?: 0
                deviceClient.setPerformanceMode(mode)
                ActionResult(true, "性能模式已切换")
            }
            "roaming_toggle" -> {
                val enabled = params["enabled"]?.boolean ?: true
                networkClient.setRoaming(enabled)
                ActionResult(true, if (enabled) "数据漫游已开启" else "数据漫游已关闭")
            }
            "network_mode" -> {
                val mode = params["mode"]?.content ?: NetworkMode.LTE_AND_5G
                // 别名 → 设备侧 BearerPreference（大小写敏感，如 `Only_5G`）的映射在 profile 的
                // WriteSpec 里，这里只传别名；bearer 只用于消息回显。
                // T15：此前这里直接透传 UI 别名（`5G_ONLY`）且没有任何映射，goform 不认，
                // 定时任务选「仅 5G」静默失败。
                val bearer = NetworkMode.toBearer(mode)
                val outcome = networkClient.setBearerPreference(mode)
                val ok = outcome.ok
                // 值域校验失败时把原因带进任务结果，别让用户只看到"切换失败"
                val rejected = (outcome as? WriteOutcome.Rejected)?.reason
                ActionResult(ok, when {
                    ok -> "网络模式已切换为 $bearer"
                    rejected != null -> "网络模式参数被拒绝：$rejected"
                    else -> "网络模式切换失败（$mode → $bearer）"
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

}
