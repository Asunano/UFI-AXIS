package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.core.scheduler.ActionExecutor
import com.ufi_axis_core.core.scheduler.AutomationRule
import com.ufi_axis_core.core.scheduler.ConditionEngine
import com.ufi_axis_core.core.scheduler.ScheduledTask
import com.ufi_axis_core.core.scheduler.TaskScheduler
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

class TaskRoutes(
    private val taskScheduler: TaskScheduler,
    private val conditionEngine: ConditionEngine? = null
) {
    fun register(route: Route) {
        route.route("/tasks") {
            get {
                val list = taskScheduler.list()
                call.respond(toJsonElement(mapOf("tasks" to list, "count" to list.size)))
            }

            get("/{id}") {
                val id = call.parameters["id"] ?: ""
                val task = taskScheduler.get(id)
                if (task != null) call.respond(toJsonElement(task))
                else call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Task not found")
            }

            get("/{id}/logs") {
                val id = call.parameters["id"] ?: ""
                if (taskScheduler.get(id) == null) {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Task not found")
                    return@get
                }
                val logs = taskScheduler.getLogs(id)
                call.respond(toJsonElement(mapOf("logs" to logs, "count" to logs.size)))
            }

            post {
                val p = call.receiveJsonObject()
                val hour = p["hour"]?.jsonPrimitive?.intOrNull ?: 0
                val minute = p["minute"]?.jsonPrimitive?.intOrNull ?: 0
                if (hour !in 0..23 || minute !in 0..59) {
                    call.respondFail(HttpStatusCode.BadRequest,
                        ErrorCode.BAD_REQUEST, "hour must be 0-23, minute must be 0-59")
                    return@post
                }
                val actionType = p["actionType"]?.jsonPrimitive?.contentOrNull ?: "custom_shell"
                val params = p["params"]?.jsonObject?.mapValues { it.value.jsonPrimitive } ?: emptyMap()
                val command = p["command"]?.jsonPrimitive?.contentOrNull ?: ""

                // 校验 actionType
                if (!com.ufi_axis_core.core.scheduler.ActionExecutor.VALID_ACTION_TYPES.contains(actionType)) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.INVALID_ACTION_TYPE, "Invalid action type: $actionType")
                    return@post
                }
                // 自定义 shell 需要 command 非空
                if (actionType == "custom_shell" && command.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Command is required for custom_shell action")
                    return@post
                }
                // 主人密码门：`custom_shell` 是 root shell 的**第二入口** —— 只在 /api/shell/exec
                // 上要密码而这里不要，等于「建个任务」就能拿到同样的 root shell。
                // 密码在**创建时**校验、不落库：定时执行时没有人在场可问。
                // [owner] POST /api/tasks
                if (actionType in OWNER_GATED_TASK_TYPES && !call.requireOwner(p.ownerPassword())) return@post

                val task = ScheduledTask(
                    name = p["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    actionType = actionType,
                    params = params,
                    command = command,
                    hour = hour,
                    minute = minute,
                    repeatDaily = (p["repeatDaily"] ?: p["repeat_daily"])?.jsonPrimitive?.booleanOrNull ?: true,
                    triggerMode = p["triggerMode"]?.jsonPrimitive?.contentOrNull,
                    scheduleType = p["scheduleType"]?.jsonPrimitive?.contentOrNull,
                    cron = p["cron"]?.jsonPrimitive?.contentOrNull,
                    scheduleParams = p["scheduleParams"]?.jsonObject?.mapValues { it.value.jsonPrimitive } ?: emptyMap(),
                    enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                )
                if (taskScheduler.add(task)) {
                    call.respond(toJsonElement(mapOf("success" to true, "id" to task.id)))
                } else {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.OPERATION_FAILED, "Failed to add task")
                }
            }

            put("/{id}") {
                val id = call.parameters["id"] ?: ""
                val p = call.receiveJsonObject()
                val existing = taskScheduler.get(id) ?: run {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Task not found")
                    return@put
                }
                val newHour = p["hour"]?.jsonPrimitive?.intOrNull ?: existing.hour
                val newMinute = p["minute"]?.jsonPrimitive?.intOrNull ?: existing.minute
                if (newHour !in 0..23 || newMinute !in 0..59) {
                    call.respondFail(HttpStatusCode.BadRequest,
                        ErrorCode.BAD_REQUEST, "hour must be 0-23, minute must be 0-59")
                    return@put
                }
                val actionType = p["actionType"]?.jsonPrimitive?.contentOrNull ?: existing.actionType
                val params = p["params"]?.jsonObject?.mapValues { it.value.jsonPrimitive } ?: existing.params
                val command = p["command"]?.jsonPrimitive?.contentOrNull ?: existing.command

                // 校验 actionType
                if (!com.ufi_axis_core.core.scheduler.ActionExecutor.VALID_ACTION_TYPES.contains(actionType)) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.INVALID_ACTION_TYPE, "Invalid action type: $actionType")
                    return@put
                }
                if (actionType == "custom_shell" && command.isBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "Command is required for custom_shell action")
                    return@put
                }
                // 主人密码门：改一条 custom_shell 任务等于改「将来要执行的 root 命令」，
                // 判据与创建时一致（actionType 缺省时已回落到 existing.actionType）。
                // [owner] PUT /api/tasks/{id}
                if (actionType in OWNER_GATED_TASK_TYPES && !call.requireOwner(p.ownerPassword())) return@put

                val updated = existing.copy(
                    name = p["name"]?.jsonPrimitive?.contentOrNull ?: existing.name,
                    actionType = actionType,
                    params = params,
                    command = command,
                    hour = newHour,
                    minute = newMinute,
                    repeatDaily = (p["repeatDaily"] ?: p["repeat_daily"])?.jsonPrimitive?.booleanOrNull ?: existing.repeatDaily,
                    triggerMode = p["triggerMode"]?.jsonPrimitive?.contentOrNull ?: existing.triggerMode,
                    scheduleType = p["scheduleType"]?.jsonPrimitive?.contentOrNull ?: existing.scheduleType,
                    cron = p["cron"]?.jsonPrimitive?.contentOrNull ?: existing.cron,
                    scheduleParams = p["scheduleParams"]?.jsonObject?.mapValues { it.value.jsonPrimitive } ?: existing.scheduleParams,
                    enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: existing.enabled
                )
                taskScheduler.update(updated)
                call.respond(toJsonElement(mapOf("success" to true)))
            }

            delete("/{id}") {
                val id = call.parameters["id"] ?: ""
                val success = taskScheduler.remove(id)
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.NotFound,
                    toJsonElement(mapOf("success" to success))
                )
            }

            post("/clear") {
                taskScheduler.clear()
                call.respond(toJsonElement(mapOf("success" to true)))
            }
        }

        // ── 自动化规则（当…就… 逻辑任务）──
        route.route("/rules") {
            get {
                val list = conditionEngine?.list() ?: emptyList<AutomationRule>()
                call.respond(toJsonElement(mapOf("rules" to list, "count" to list.size)))
            }

            get("/{id}") {
                val id = call.parameters["id"] ?: ""
                val rule = conditionEngine?.get(id)
                if (rule != null) call.respond(toJsonElement(rule))
                else call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Rule not found")
            }

            get("/{id}/logs") {
                val id = call.parameters["id"] ?: ""
                val logs = conditionEngine?.getLogs(id) ?: emptyList()
                call.respond(toJsonElement(mapOf("logs" to logs, "count" to logs.size)))
            }

            post {
                val p = call.receiveJsonObject()
                val triggerType = p["triggerType"]?.jsonPrimitive?.contentOrNull ?: "traffic_total_reached"
                val actionType = p["actionType"]?.jsonPrimitive?.contentOrNull ?: "data_toggle"
                if (conditionEngine == null) {
                    call.respondFail(HttpStatusCode.NotImplemented, ErrorCode.UNAVAILABLE, "Condition engine unavailable")
                    return@post
                }
                if (!ActionExecutor.VALID_ACTION_TYPES.contains(actionType)) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.INVALID_ACTION_TYPE, "Invalid action type: $actionType")
                    return@post
                }
                // 主人密码门：自动化规则与定时任务是同一个执行器，custom_shell 同样是 root shell，
                // 门槛必须一致（见 /tasks 的说明）。
                // [owner] POST /api/rules
                if (actionType in OWNER_GATED_TASK_TYPES && !call.requireOwner(p.ownerPassword())) return@post
                val triggerParams = p["triggerParams"]?.jsonObject?.mapValues { it.value.jsonPrimitive } ?: emptyMap()
                val params = p["params"]?.jsonObject?.mapValues { it.value.jsonPrimitive } ?: emptyMap()
                val cooldownMs = p["cooldownMs"]?.jsonPrimitive?.longOrNull ?: 60_000L
                val rule = AutomationRule(
                    name = p["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    triggerType = triggerType,
                    triggerParams = triggerParams,
                    actionType = actionType,
                    params = params,
                    cooldownMs = cooldownMs
                )
                if (conditionEngine!!.add(rule)) {
                    call.respond(toJsonElement(mapOf("success" to true, "id" to rule.id)))
                } else {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.OPERATION_FAILED, "Failed to add rule")
                }
            }

            put("/{id}") {
                val id = call.parameters["id"] ?: ""
                val p = call.receiveJsonObject()
                val existing = conditionEngine?.get(id) ?: run {
                    call.respondFail(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Rule not found")
                    return@put
                }
                val actionType = p["actionType"]?.jsonPrimitive?.contentOrNull ?: existing.actionType
                if (!ActionExecutor.VALID_ACTION_TYPES.contains(actionType)) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.INVALID_ACTION_TYPE, "Invalid action type: $actionType")
                    return@put
                }
                // [owner] PUT /api/rules/{id}
                if (actionType in OWNER_GATED_TASK_TYPES && !call.requireOwner(p.ownerPassword())) return@put
                val triggerParams = p["triggerParams"]?.jsonObject?.mapValues { it.value.jsonPrimitive } ?: existing.triggerParams
                val params = p["params"]?.jsonObject?.mapValues { it.value.jsonPrimitive } ?: existing.params
                val cooldownMs = p["cooldownMs"]?.jsonPrimitive?.longOrNull ?: existing.cooldownMs
                val updated = existing.copy(
                    name = p["name"]?.jsonPrimitive?.contentOrNull ?: existing.name,
                    enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: existing.enabled,
                    triggerType = p["triggerType"]?.jsonPrimitive?.contentOrNull ?: existing.triggerType,
                    triggerParams = triggerParams,
                    actionType = actionType,
                    params = params,
                    cooldownMs = cooldownMs
                )
                conditionEngine!!.update(updated)
                call.respond(toJsonElement(mapOf("success" to true)))
            }

            delete("/{id}") {
                val id = call.parameters["id"] ?: ""
                val success = conditionEngine?.remove(id) ?: false
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.NotFound,
                    toJsonElement(mapOf("success" to success))
                )
            }

            post("/clear") {
                conditionEngine?.clear()
                call.respond(toJsonElement(mapOf("success" to true)))
            }
        }
    }
}

/**
 * 建/改这些类型的定时任务要主人密码。
 *
 * 判据不是"这个类型危不危险"，而是**它能不能绕过别处已经登记的门**：
 * `custom_shell` 绕过 `POST /api/shell/exec`，`remote_backup` 绕过 `POST /api/backup/export`
 * （后者同样能拿到整包配置，只是"什么时候发"更隐蔽）。
 */
private val OWNER_GATED_TASK_TYPES = setOf("custom_shell", "remote_backup")
