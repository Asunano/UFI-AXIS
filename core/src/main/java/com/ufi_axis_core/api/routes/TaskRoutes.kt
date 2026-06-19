package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.core.scheduler.ScheduledTask
import com.ufi_axis_core.core.scheduler.TaskScheduler
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

class TaskRoutes(private val taskScheduler: TaskScheduler) {
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
                else call.respond(HttpStatusCode.NotFound, toJsonElement(mapOf("error" to "Task not found")))
            }

            post {
                val p = call.receive<JsonObject>()
                val hour = p["hour"]?.jsonPrimitive?.intOrNull ?: 0
                val minute = p["minute"]?.jsonPrimitive?.intOrNull ?: 0
                if (hour !in 0..23 || minute !in 0..59) {
                    call.respond(HttpStatusCode.BadRequest,
                        toJsonElement(mapOf("error" to "hour must be 0-23, minute must be 0-59")))
                    return@post
                }
                val task = ScheduledTask(
                    name = p["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    command = p["command"]?.jsonPrimitive?.contentOrNull ?: "",
                    hour = hour,
                    minute = minute,
                    repeatDaily = (p["repeatDaily"] ?: p["repeat_daily"])?.jsonPrimitive?.booleanOrNull ?: true,
                    enabled = p["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                )
                if (taskScheduler.add(task)) {
                    call.respond(toJsonElement(mapOf("success" to true, "id" to task.id)))
                } else {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "Command is required")))
                }
            }

            put("/{id}") {
                val id = call.parameters["id"] ?: ""
                val p = call.receive<JsonObject>()
                val existing = taskScheduler.get(id) ?: run {
                    call.respond(HttpStatusCode.NotFound, toJsonElement(mapOf("error" to "Task not found")))
                    return@put
                }
                val newHour = p["hour"]?.jsonPrimitive?.intOrNull ?: existing.hour
                val newMinute = p["minute"]?.jsonPrimitive?.intOrNull ?: existing.minute
                if (newHour !in 0..23 || newMinute !in 0..59) {
                    call.respond(HttpStatusCode.BadRequest,
                        toJsonElement(mapOf("error" to "hour must be 0-23, minute must be 0-59")))
                    return@put
                }
                val updated = existing.copy(
                    name = p["name"]?.jsonPrimitive?.contentOrNull ?: existing.name,
                    command = p["command"]?.jsonPrimitive?.contentOrNull ?: existing.command,
                    hour = newHour,
                    minute = newMinute,
                    repeatDaily = (p["repeatDaily"] ?: p["repeat_daily"])?.jsonPrimitive?.booleanOrNull ?: existing.repeatDaily,
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
    }
}