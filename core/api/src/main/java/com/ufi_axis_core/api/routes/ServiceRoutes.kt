package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.core.scheduler.DataScheduler
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.NativeExecProbe
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 服务控制路由（2026-08-26）
 *
 * GET  /api/service/status   — 服务状态回读（后台采集是否在跑 + 运行时长 + 开机自启）
 * POST /api/service/start    — 启动后台采集服务（HTTP 服务不受影响）
 * POST /api/service/stop     — 停止后台采集服务（HTTP 服务继续运行，客户端仍可远程再启动）
 * POST /api/service/restart  — **重启后端服务**（销毁 Service 组件后由 AlarmManager 以前台服务语义拉起；
 *                               HTTP 会中断约 10 秒。注意：进程通常不会退出，静态状态不清空）
 * POST /api/service/autostart — 开机自启开关
 *
 * 设计要点：
 * 1. "停止服务"停的是**除 HTTP 之外的全部后台自主活动**：[DataScheduler] 采集循环
 *    （CPU/内存/信号/流量/SMS/电池/清理）、告警引擎、定时任务调度、短信转发（observer +
 *    5 分钟兜底轮询）、Samba socket 保活、电池事件广播、下载后台轮询与 aria2 进程、隧道看护。
 *    非 DataScheduler 的那部分由 [onBackgroundSwitch] 回调落地（实现方 `BackendService`）。
 *    **不停 Ktor HTTP 服务**——否则客户端把自己锁在门外，再也无法远程启动。
 * 2. 开关值持久化在 [AppSettings.backgroundServiceEnabled]，服务重启后仍然生效；
 *    `/api/monitor/control` 是同一开关的旧入口（只写内存），两者已统一到本真源。
 * 3. `restart` 走 [onRestart] 回调（由 `:core` 注入 `BackendService` 的重启入口），
 *    因为 `:core:api` 不能反向依赖 `:core`。
 */
class ServiceRoutes(
    private val dataScheduler: DataScheduler,
    private val settings: AppSettings,
    /** 完全重启后端服务；实现方为 `BackendService.requestRestart(context)`。 */
    private val onRestart: () -> Unit,
    /** 后端服务已运行时长（毫秒）；实现方为 `BackendService.uptimeMs()`。 */
    private val uptimeMs: () -> Long,
    /**
     * 采集之外的后台服务总闸；实现方为 `BackendService.applyBackgroundServices(enabled)`。
     * 幂等，可反复调用（内部按当前状态去重）。
     */
    private val onBackgroundSwitch: (Boolean) -> Unit
) {

    private val tag = "ServiceRoutes"

    fun register(route: Route) {
        route.route("/service") {

            /** 服务状态：后台采集是否在跑、开关值、运行时长、开机自启。 */
            get("/status") {
                call.respond(statusPayload())
            }

            /** 启动后台采集服务（幂等）。 */
            post("/start") {
                applySwitch(true)
                AppLogger.i(tag, "Background service started via REST")
                call.respond(statusPayload())
            }

            /** 停止后台采集服务（幂等）。HTTP 服务保持运行，可随时再 start。 */
            post("/stop") {
                applySwitch(false)
                AppLogger.w(tag, "Background service stopped via REST (HTTP server keeps running)")
                call.respond(statusPayload())
            }

            /**
             * 完全重启后端服务：先回响应，再触发重启（否则客户端拿不到结果）。
             * 服务被 AlarmManager 在约 10 秒后拉起，期间 HTTP 不可用。
             */
            post("/restart") {
                call.respond(
                    toJsonElement(
                        mapOf(
                            "success" to true,
                            "ok" to true,
                            "restarting" to true,
                            "estimated_downtime_ms" to RESTART_DOWNTIME_HINT_MS,
                            "hint" to "后端服务正在完全重启，约 10 秒后自动恢复，请稍后重连"
                        )
                    )
                )
                AppLogger.w(tag, "Full backend restart requested via REST")
                onRestart()
            }

            /** 开机自启开关。 */
            post("/autostart") {
                val body = call.receiveJsonObject()
                val enabled = body["enabled"]?.jsonPrimitive?.booleanOrNull
                if (enabled == null) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "enabled (boolean) required"
                    )
                    return@post
                }
                settings.autoStartOnBoot = enabled
                AppLogger.i(tag, "autoStartOnBoot=$enabled via REST")
                call.respond(statusPayload())
            }

            /**
             * 上一次 core 崩溃的信息（2026-09-04）。
             *
             * 为什么需要：core 崩溃后由 keepalive 脚本 / START_STICKY 自动拉起，HTTP 很快恢复，
             * **前端完全无感** —— 用户只看到数据断了一下。两端在连上设备后拉一次这个接口，
             * 与本地"已提示过的时间戳"比对，不同就弹一次窗。
             *
             * 为什么不用 WS 主动推：崩溃重启的那一刻没有任何客户端订阅（`WebSocketManager.broadcast`
             * 在无订阅者时直接丢弃），而且 app/web 可能压根没开着。REST 回读是唯一不丢的方式。
             *
             * 为什么没有 ack 接口：app 与 web 是两个独立展示端，谁先 ack 另一端就再也看不到。
             * 去重放各自本地（app: `AppPreferences.coreCrashShownAt`，web: localStorage）。
             */
            get("/crash") {
                val at = settings.lastCrashAt
                call.respond(
                    toJsonElement(
                        mapOf(
                            "success" to true,
                            "ok" to true,
                            "crashed" to (at > 0L),
                            "timestamp" to at,
                            "summary" to settings.lastCrashSummary,
                            "file" to settings.lastCrashFile,
                            // 拿来算"崩溃发生在本次启动之前多久"，也能让前端判断服务是否刚起来
                            "uptime_ms" to uptimeMs()
                        )
                    )
                )
            }
        }
    }

    /** 写持久化开关 + 立即生效（`applyColdCollectionState` 内部按开关 start/stop）。 */
    private fun applySwitch(enabled: Boolean) {
        settings.backgroundServiceEnabled = enabled
        dataScheduler.setMonitorEnabled(enabled)
        // 采集之外的后台活动（定时任务/短信转发/Samba 保活/电池/下载轮询/隧道看护）
        // 2026-09-03 起一并停/起，否则"停止服务"只停了采集，其余照旧唤醒设备并登录 goform。
        onBackgroundSwitch(enabled)
    }

    private fun statusPayload() = toJsonElement(
        mapOf(
            "success" to true,
            "ok" to true,
            // 用户可见的"服务开关"值（持久化真源）
            "enabled" to settings.backgroundServiceEnabled,
            // 采集循环的实际运行状态。停止时会与 enabled 短暂不一致：
            // stop() 先同步置 isRunning=false，但收尾 flush 是异步的（最多 5s），
            // 因此可能出现 enabled=false && collecting=false 但仍有 goform 查询在收尾。
            "collecting" to dataScheduler.collecting,
            "monitor_switch_on" to dataScheduler.monitorSwitchOn,
            // HTTP 服务能回响应就说明它在跑，恒为 true；保留字段让两端不必特判
            "http_running" to true,
            "uptime_ms" to uptimeMs(),
            "auto_start_on_boot" to settings.autoStartOnBoot,
            // 自带原生二进制（aria2c/ttyd/socat/curl/jq/adb）能否 exec —— 由 ComponentFactory 在
            // 资产释放后探一次并缓存。enforcing 设备上恒为 false，此时下载引擎 / 网页终端 / Samba
            // 都起不来；之前这些调用点失败后静默 catch，只能靠翻 avc 日志才知道根因。
            // probed=false 表示本进程尚未跑过自检（组件图还没构建），不代表不可用。
            "native_exec" to NativeExecProbe.cachedResult().let { r ->
                mapOf(
                    "probed" to (r != null),
                    "executable" to r?.executable,
                    "selinux" to (r?.selinux ?: "unknown"),
                    "detail" to (r?.detail ?: "尚未自检")
                )
            }
        )
    )

    companion object {
        /** 与 `BackendService.RESTART_DELAY_MS` 对齐，仅作为客户端提示。 */
        private const val RESTART_DOWNTIME_HINT_MS = 10_000L
    }
}
