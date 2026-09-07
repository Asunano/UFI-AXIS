package com.ufi_axis_core.core.scheduler

import com.ufi_axis_core.contract.ActionType
import kotlinx.serialization.json.JsonPrimitive

/**
 * 定时任务动作执行器接口
 *
 * 定义在 core:scheduler 模块，避免调度器直接依赖具体 Controller 实现。
 * 具体实现在 core service 层（ActionExecutorImpl），持有所有 Controller 引用。
 */
interface ActionExecutor {
    /**
     * 执行指定类型的动作
     * @param actionType 动作类型标识（如 "data_toggle", "reboot" 等）
     * @param params 动作参数（key-value 对，值统一为 JsonPrimitive）
     * @return 执行结果
     */
    suspend fun execute(actionType: String, params: Map<String, JsonPrimitive>): ActionResult

    companion object {
        /**
         * 动作类型白名单 —— **权威定义已上移到 `:core:contract` 的 [ActionType]**。
         * 这里只做一次转换，保证 core 校验点、执行侧 `when` 分支与 app/web UI 用同一份清单。
         */
        val VALID_ACTION_TYPES: Set<String> = ActionType.ALL.toSet()
    }
}

/**
 * 动作执行结果
 */
data class ActionResult(
    val success: Boolean,
    val message: String = ""
)
