package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.util.plan.PlanProfileManager
import com.ufi_axis_core.contract.ErrorCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 套餐档案（2026-10-07 到量/到期融合方案）。
 *
 * ```
 * GET  /api/device/plan        — 读套餐档案 + 当前到期状态 + fixed 累计用量
 * POST /api/device/plan        — 写套餐档案（模式切换 / 生效日 / 天数 / 提醒天数）
 * ```
 *
 * 与 `POST /api/device/data-limit` 的分工：那条写**设备**（总量/清零日/阈值），
 * 这条写 **core 自己的套餐语义**（模式/生效日/时长）。模式切到 fixed 时，
 * 前端应同时把设备侧 auto_clear 关掉（累计口径不能被固件月清打断，接口返回里会带提示）。
 */
class PlanRoutes(private val ctx: RouteContext) {

    fun register(route: Route) {
        val manager = PlanProfileManager(ctx.settings)
        route.route("/device/plan") {
            get {
                val p = manager.read()
                val st = manager.expiryState()
                val usage = manager.fixedUsageWindow()?.let { (start, end) ->
                    // fixed 累计用量走 traffic_hourly；monthly 模式为 null（固件计数器是真源）
                    ctx.database.trafficHourlyDao().listBetween(start, end).sumOf { it.rxBytes + it.txBytes }
                }
                call.respond(toJsonElement(mapOf(
                    "mode" to p.mode,
                    "start_date" to (p.startDate ?: ""),
                    "duration_days" to p.durationDays,
                    "notify_days" to p.notifyDays,
                    "expiry_at" to (st?.expiryAt ?: 0L),
                    "days_left" to (st?.daysLeft ?: 0L),
                    "fixed_usage_bytes" to (usage ?: 0L),
                    // 切 fixed 需关设备侧自动清零的口径提示（前端可据此弹确认）
                    "requires_auto_clear_off" to p.isFixed,
                )))
            }
            post {
                val b = call.receiveJsonObject()
                val mode = b["mode"]?.jsonPrimitive?.contentOrNull ?: return@post call.respondFail(
                    HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "mode is required"
                )
                val startDate = b["start_date"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val durationDays = b["duration_days"]?.jsonPrimitive?.intOrNull
                val notifyDays = b["notify_days"]?.jsonPrimitive?.intOrNull
                val err = manager.write(mode, startDate, durationDays, notifyDays)
                if (err != null) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, err)
                    return@post
                }
                val p = manager.read()
                call.respond(toJsonElement(mapOf(
                    "success" to true,
                    "mode" to p.mode,
                    "requires_auto_clear_off" to p.isFixed,
                )))
            }
        }
    }
}
