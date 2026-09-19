package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.geo.GeoDetector
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * 地理位置（`/api/geo`，2026-09-18）：这台设备的**出网国家/地区**。
 *
 * ## 为什么在 core
 * 检测靠的是出网 IP 的归属，而设备才是真正的出网点。app 走设备热点时 IP 虽然也一样，
 * 但把检测放在 app 会让每个客户端各存一份、结果可能不一致；放在 core 只有一份真源。
 * 目前唯一的消费方是 app 的更新源自动选择（CN → 走镜像，其余直连）。
 *
 * ## 端点
 * - `GET  /api/geo`        —— 读结果。已有且未过期直接回缓存；缺失或超过
 *   [GeoDetector.STALE_AFTER_MS] 时**顺手测一次**（core 启动那次若失败，这里就是自愈点）。
 * - `POST /api/geo/detect` —— 强制重测，忽略过期判定。给"重新检测"这类显式入口留的口子，
 *   客户端正常展示不需要调它。
 *
 * ## 没有 TTL 缓存层
 * 与 [WeatherRoutes] / [PoetryRoutes] 不同，本路由**不收 `ResponseCache`**：结果的真源就是
 * [AppSettings.geoCountry] 那两个键（读 prefs 没有可缓存的上游调用），而"多久之内不重测"
 * 由 [GeoDetector.isStale] 用落盘时间戳表达。再叠一层内存 TTL 只会造出第二个过期口径。
 *
 * ## 没有定时器
 * 检测只在两种事件下发生：core 启动（见 `BackendService`）与本文件这两个请求。
 * 本仓明令禁止常驻定时器 / 周期闹钟，国家/地区这种几乎不变的量更没有轮询的理由。
 */
class GeoRoutes(
    private val settings: AppSettings
) {

    fun register(route: Route) {
        route.route("/geo") {

            get {
                val fresh = GeoDetector.isStale(settings)
                if (fresh) GeoDetector.refreshIfStale(settings)
                call.respond(snapshot(if (fresh) SOURCE_FRESH else SOURCE_CACHE))
            }

            /**
             * 强制重测。失败回 502 而不是 200 —— 用户是显式点了"重新检测"，
             * 静默回一个没变的旧值会让人以为按钮没反应。
             */
            post("/detect") {
                val country = GeoDetector.detectAndSave(settings)
                if (country == null) {
                    AppLogger.w(TAG, "强制重测失败：三个地理源都不可达")
                    call.respondFail(
                        HttpStatusCode.BadGateway, ErrorCode.OPERATION_FAILED,
                        "国家/地区检测失败：地理源均不可达（已保留上次结果）"
                    )
                    return@post
                }
                call.respond(snapshot(SOURCE_FRESH))
            }
        }
    }

    /**
     * 当前结果快照。
     *
     * [source] 只描述"这次响应有没有真的出网"，不参与任何判定；检测失败且从未测过时
     * 回 [SOURCE_UNKNOWN] + 空 country —— 把空值标成 `cache` 是在撒谎，客户端据此
     * 显示"未检测"而不是"缓存里就是空的"。
     */
    private fun snapshot(source: String) = toJsonElement(
        mapOf(
            // ISO 3166-1 alpha-2（如 CN / US）；"" = 未检测出结果
            "country" to settings.geoCountry,
            "detected_at" to settings.geoDetectedAt,
            "source" to if (settings.geoCountry.isBlank()) SOURCE_UNKNOWN else source
        )
    )

    companion object {
        private const val TAG = "GeoRoutes"

        /** 本次响应直接来自已落盘的结果，没有出网。 */
        const val SOURCE_CACHE = "cache"

        /** 本次响应之前刚出网测过一次。 */
        const val SOURCE_FRESH = "fresh"

        /** 从未测出过结果（含本次检测也失败）。 */
        const val SOURCE_UNKNOWN = "unknown"
    }
}
