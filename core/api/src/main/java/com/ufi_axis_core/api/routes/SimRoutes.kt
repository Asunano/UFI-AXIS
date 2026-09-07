package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.routes.RouteContext
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.util.AppLogger
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * SIM 卡路由
 * POST /api/sim/switch  - SIM 卡槽切换
 *
 * 2026-08-27（T40-15）：删除 `GET /api/sim/info`。它返回的 imei/imsi/iccid/sim_state 与
 * `/api/device/info` 的 identity、`/api/dashboard/summary` 的 sim 同源（都出自 goform
 * getDeviceInfo / TelephonyCollector），且更新更慢；app 侧虽有 Retrofit 方法却从不渲染
 * 结果，web 侧从未引用 —— 属于「core 存在但无真实消费者」的重复能力，按真源唯一原则删除，
 * 不留兼容壳。KDoc 里原先还写着 `POST /api/sim/ussd`，那个端点从来没被注册过，一并删掉。
 *
 * 注意: SMS 路由(/api/sms/)统一由 RootSmsRoutes 管理
 */
class SimRoutes(
    private val ctx: RouteContext
) {
    // ── 反向兼容 getter ──
    private val simClient get() = ctx.simClient
    private val cache get() = ctx.responseCache

    fun register(route: Route) {
        // 注意: SMS 路由(/api/sms/*)统一由 RootSmsRoutes 管理，此处仅注册 /sim 路由
        route.route("/sim") {
            // SIM 卡槽切换
            post("/switch") {
                val client = simClient
                if (client == null) {
                    call.respondFail(HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE,
                        "Goform client not available")
                    return@post
                }
                val params = call.receiveJsonObject()
                // 规范入参：slot = 1 起的卡槽序号，或 "external"（外置卡）。
                // 序号 → 设备值的映射在 profile 的 WriteSpec 里（计划书 2.6）。
                //
                // 旧入参 goformSlot 直接是设备侧的运营商预置位（0=移动 1=电信 2=联通 11=外置），
                // 取值域与新的序号**重叠**（旧的 "1" 是电信，新的 1 是第一个槽位），
                // 所以这里必须先换算成序号再往下传，不能直接当 slot 用。
                // 换算与 /api/device/data-limit 收旧 limit_size 复合串是同一种兼容做法：
                // 设备侧取值只在这一处兼容分支里出现，一版后连分支一起删。
                val slot = params["slot"]?.jsonPrimitive?.contentOrNull
                    ?: params["goformSlot"]?.jsonPrimitive?.contentOrNull?.let { legacy ->
                        AppLogger.w("SimRoutes",
                            "POST /api/sim/switch 收到旧字段 goformSlot（$legacy），请改用 slot=1|2|3|external")
                        when (legacy.trim()) {
                            "0" -> "1"
                            "1" -> "2"
                            "2" -> "3"
                            "11" -> "external"
                            else -> null
                        }
                    }
                if (slot.isNullOrBlank()) {
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "slot is required (1/2/3 or \"external\")")
                    return@post
                }
                val outcome = client.switchSimSlot(slot)
                if (call.respondRejected(outcome)) return@post
                val success = outcome.ok
                // 换卡后失效的是 device:* 那几份缓存（device:info / device:identity / device:goform
                // 都带 imei/imsi/iccid）。原来写的是 `sim:*`，那是已删除的 /api/sim/info 的键，
                // 现在没有任何缓存项匹配它，客户端也不会因此刷新真正展示 SIM 的那几个接口。
                if (success) cache?.invalidate("device:*")
                call.respond(
                    if (success) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                    toJsonElement(mapOf("success" to success, "slot" to slot))
                )
            }
        }
    }

}
