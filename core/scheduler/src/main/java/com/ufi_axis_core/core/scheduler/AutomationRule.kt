package com.ufi_axis_core.core.scheduler

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * 自动化规则模型（当…就… 逻辑任务）
 *
 * 统一模型 = 触发(trigger) → 动作(action)。动作复用 [ActionExecutor] 全部已注册动作
 * （data_toggle / network_mode / reboot / wifi_toggle ...），时间触发与条件触发共用。
 *
 * triggerType（v1 条件类型）:
 *  - traffic_total_reached : 当月累计流量 ≥ thresholdBytes（电平类，triggerParams["thresholdBytes"]: Long）
 *  - network_type_changed  : 网络类型变为 targetType（边沿类，triggerParams["targetType"]: "4G"/"5G"/...）
 *  - signal_below          : RSRP ≤ rsrp（电平类，triggerParams["rsrp"]: Int，负值越小越差）
 *  - battery_below         : 电量 ≤ levelPercent 且未充电（电平类，triggerParams["levelPercent"]: Int）
 *  - disconnect            : 蜂窝网络断开（边沿类）
 *
 * 数据全部来自 Core 既有采集链路（DataScheduler 每轮并联 evaluate），零额外采集开销。
 * 向后兼容：纯新增模型，不影响既有 ScheduledTask 时间任务。
 */
@Serializable
data class AutomationRule(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String = "",
    val enabled: Boolean = true,
    // —— 触发条件 ——
    val triggerType: String = "traffic_total_reached",
    val triggerParams: Map<String, JsonPrimitive> = emptyMap(),
    // —— 动作（复用 ActionExecutor）——
    val actionType: String = "data_toggle",
    val params: Map<String, JsonPrimitive> = emptyMap(),
    // —— 通用 ——
    val cooldownMs: Long = 60_000L,
    val createdAt: Long = System.currentTimeMillis(),
    val logs: List<ExecutionLog> = emptyList()
)
