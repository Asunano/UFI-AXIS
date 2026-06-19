package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.controller.adb.AdbController
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

class AdbRoutes(private val adbController: AdbController) {
    fun register(route: Route) {
        route.route("/adb") {
            get("/status") {
                call.respond(toJsonElement(adbController.getStatus()))
            }

            post("/start") {
                val params = call.receive<JsonObject>()
                val port = params["port"]?.jsonPrimitive?.intOrNull ?: 5555
                if (port !in 1..65535) {
                    call.respond(HttpStatusCode.BadRequest,
                        toJsonElement(mapOf("error" to "port must be 1-65535")))
                    return@post
                }
                val success = adbController.start(port)
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "port" to port))
                )
            }

            post("/stop") {
                val success = adbController.stop()
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success))
                )
            }

            get("/ping") {
                val alive = adbController.ping()
                call.respond(toJsonElement(mapOf("alive" to alive)))
            }
        }
    }
}