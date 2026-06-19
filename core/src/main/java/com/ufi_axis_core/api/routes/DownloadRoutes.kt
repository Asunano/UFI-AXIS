package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.controller.system.DownloadManager
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.io.File

class DownloadRoutes(
    private val downloadManager: DownloadManager
) {
    fun register(route: Route) {
        route.route("/downloads") {

            // List all tasks + global stats
            get {
                val tasks = downloadManager.getAllTasks()
                val config = downloadManager.config
                val aria2Running = downloadManager.aria2.isRunning()
                val aria2Version = if (aria2Running) {
                    downloadManager.aria2.getVersion()
                } else {
                    downloadManager.aria2.cachedVersion
                }
                // Tracker 元信息
                val trackerMeta = downloadManager.trackerManager.getMeta()
                val trackerCount = downloadManager.trackerManager.getCachedTrackers()
                    ?.split(",")?.count { it.trim().isNotBlank() } ?: 0

                call.respond(toJsonElement(mapOf(
                    "tasks" to tasks.map { taskToMap(it) },
                    "count" to tasks.size,
                    "active" to downloadManager.getActiveCount(),
                    "aria2_running" to aria2Running,
                    "aria2_version" to aria2Version,
                    "config" to configToMap(config),
                    "tracker_count" to trackerCount,
                    "tracker_status" to trackerMeta.status,
                    "tracker_last_updated" to trackerMeta.lastUpdated,
                    "throttle_state" to downloadManager.throttleState,
                    "throttle_temp" to downloadManager.throttleTemp,
                    "throttle_cpu" to downloadManager.throttleCpu,
                    "throttle_battery" to downloadManager.throttleBattery,
                    "throttle_memory" to downloadManager.throttleMemory,
                    "throttle_charging" to downloadManager.throttleCharging,
                    "throttle_was_stopped" to downloadManager.throttleWasStopped
                )))
            }

            // Get single task
            get("/{id}") {
                val id = call.parameters["id"] ?: ""
                val task = downloadManager.getTask(id)
                if (task != null) call.respond(toJsonElement(taskToMap(task)))
                else call.respond(HttpStatusCode.NotFound, toJsonElement(mapOf("error" to "Not found")))
            }

            // Create new download
            post {
                val body = call.receive<JsonObject>()
                val url = body["url"]?.jsonPrimitive?.contentOrNull ?: ""
                if (url.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf("error" to "url is required")))
                    return@post
                }
                val fileName = body["file_name"]?.jsonPrimitive?.contentOrNull
                val savePath = body["save_path"]?.jsonPrimitive?.contentOrNull
                val speedLimit = body["speed_limit"]?.jsonPrimitive?.longOrNull
                val connections = body["connections"]?.jsonPrimitive?.intOrNull
                val task = downloadManager.createTask(url, fileName, savePath, speedLimit, connections)
                call.respond(HttpStatusCode.Created, toJsonElement(mapOf("success" to true, "task" to taskToMap(task))))
            }

            // Pause / Resume / Delete
            post("/{id}/pause") {
                val id = call.parameters["id"] ?: ""
                call.respond(toJsonElement(mapOf("success" to downloadManager.pauseTask(id))))
            }
            post("/{id}/resume") {
                val id = call.parameters["id"] ?: ""
                call.respond(toJsonElement(mapOf("success" to downloadManager.resumeTask(id))))
            }
            delete("/{id}") {
                val id = call.parameters["id"] ?: ""
                val deleteFile = call.request.queryParameters["delete_file"]?.toBoolean() ?: false
                call.respond(toJsonElement(mapOf("success" to downloadManager.deleteTask(id, deleteFile))))
            }
            post("/clear-completed") {
                val n = downloadManager.getAllTasks().filter { it.status == "completed" }
                    .also { it.forEach { t -> downloadManager.deleteTask(t.id, false) } }.size
                call.respond(toJsonElement(mapOf("success" to true, "cleared" to n)))
            }

            // ─── 路径验证 ────────────────────────────────
            get("/validate-path") {
                val path = call.request.queryParameters["path"] ?: ""
                if (path.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, toJsonElement(mapOf(
                        "valid" to false, "error" to "path is required"
                    )))
                    return@get
                }
                val file = File(path)
                val result = mapOf(
                    "valid" to (file.exists() && file.isDirectory && file.canWrite()),
                    "exists" to file.exists(),
                    "is_directory" to (if (file.exists()) file.isDirectory else null),
                    "writable" to (if (file.exists()) file.canWrite() else null),
                    "free_space" to (if (file.exists() && file.isDirectory) {
                        try { file.freeSpace } catch (_: Exception) { -1L }
                    } else -1L),
                    "absolute_path" to (try { file.absolutePath } catch (_: Exception) { path })
                )
                call.respond(toJsonElement(result))
            }

            // ─── 配置管理 ────────────────────────────────
            get("/config") {
                call.respond(toJsonElement(configToMap(downloadManager.config)))
            }
            put("/config") {
                val body = call.receive<JsonObject>()
                val c = downloadManager.config
                val updated = c.copy(
                    maxConcurrent = body["max_concurrent"]?.jsonPrimitive?.intOrNull ?: c.maxConcurrent,
                    maxConnectionsPerServer = body["max_connections_per_server"]?.jsonPrimitive?.intOrNull ?: c.maxConnectionsPerServer,
                    globalSpeedLimit = body["global_speed_limit"]?.jsonPrimitive?.longOrNull ?: c.globalSpeedLimit,
                    perTaskSpeedLimit = body["per_task_speed_limit"]?.jsonPrimitive?.longOrNull ?: c.perTaskSpeedLimit,
                    saveDir = body["save_dir"]?.jsonPrimitive?.contentOrNull ?: c.saveDir,
                    splitCount = body["split_count"]?.jsonPrimitive?.intOrNull ?: c.splitCount,
                    minSplitSizeMb = body["min_split_size_mb"]?.jsonPrimitive?.intOrNull ?: c.minSplitSizeMb,
                    maxOverallUploadLimit = body["max_overall_upload_limit"]?.jsonPrimitive?.longOrNull ?: c.maxOverallUploadLimit,
                    fileAllocation = body["file_allocation"]?.jsonPrimitive?.contentOrNull ?: c.fileAllocation,
                    btSeedRatio = body["bt_seed_ratio"]?.jsonPrimitive?.floatOrNull ?: c.btSeedRatio,
                    btMaxPeers = body["bt_max_peers"]?.jsonPrimitive?.intOrNull ?: c.btMaxPeers,
                    btEnableDht = body["bt_enable_dht"]?.jsonPrimitive?.booleanOrNull ?: c.btEnableDht,
                    btEnableLpd = body["bt_enable_lpd"]?.jsonPrimitive?.booleanOrNull ?: c.btEnableLpd,
                    dhtListenPort = body["dht_listen_port"]?.jsonPrimitive?.contentOrNull ?: c.dhtListenPort,
                    btTrackerConnectTimeout = body["bt_tracker_connect_timeout"]?.jsonPrimitive?.intOrNull ?: c.btTrackerConnectTimeout,
                    btRequestPeerSpeedLimit = body["bt_request_peer_speed_limit"]?.jsonPrimitive?.longOrNull ?: c.btRequestPeerSpeedLimit,
                    btMaxOpenFiles = body["bt_max_open_files"]?.jsonPrimitive?.intOrNull ?: c.btMaxOpenFiles,
                    disableIpv6 = body["disable_ipv6"]?.jsonPrimitive?.booleanOrNull ?: c.disableIpv6,
                    checkCertificate = body["check_certificate"]?.jsonPrimitive?.booleanOrNull ?: c.checkCertificate,
                    maxTries = body["max_tries"]?.jsonPrimitive?.intOrNull ?: c.maxTries,
                    retryWait = body["retry_wait"]?.jsonPrimitive?.intOrNull ?: c.retryWait,
                    maxResumeTries = body["max_resume_tries"]?.jsonPrimitive?.intOrNull ?: c.maxResumeTries,
                    lowestSpeedLimit = body["lowest_speed_limit"]?.jsonPrimitive?.longOrNull ?: c.lowestSpeedLimit,
                    logLevel = body["log_level"]?.jsonPrimitive?.contentOrNull ?: c.logLevel,
                    btTrackerAutoUpdate = body["bt_tracker_auto_update"]?.jsonPrimitive?.booleanOrNull ?: c.btTrackerAutoUpdate,
                    btTrackerUpdateIntervalHours = body["bt_tracker_update_interval_hours"]?.jsonPrimitive?.intOrNull ?: c.btTrackerUpdateIntervalHours,
                    btTrackerSourceUrl = body["bt_tracker_source_url"]?.jsonPrimitive?.contentOrNull ?: c.btTrackerSourceUrl,
                    btTrackerCustomList = body["bt_tracker_custom_list"]?.jsonPrimitive?.contentOrNull ?: c.btTrackerCustomList,
                    smartThrottle = body["smart_throttle"]?.jsonPrimitive?.booleanOrNull ?: c.smartThrottle,
                    throttleTempWarn = body["throttle_temp_warn"]?.jsonPrimitive?.floatOrNull ?: c.throttleTempWarn,
                    throttleTempCritical = body["throttle_temp_critical"]?.jsonPrimitive?.floatOrNull ?: c.throttleTempCritical,
                    throttleCpuWarn = body["throttle_cpu_warn"]?.jsonPrimitive?.intOrNull ?: c.throttleCpuWarn,
                    throttleCpuCritical = body["throttle_cpu_critical"]?.jsonPrimitive?.intOrNull ?: c.throttleCpuCritical,
                    throttleBatteryWarn = body["throttle_battery_warn"]?.jsonPrimitive?.intOrNull ?: c.throttleBatteryWarn,
                    throttleBatteryCritical = body["throttle_battery_critical"]?.jsonPrimitive?.intOrNull ?: c.throttleBatteryCritical,
                    throttleMemoryWarn = body["throttle_memory_warn"]?.jsonPrimitive?.intOrNull ?: c.throttleMemoryWarn,
                    throttleMemoryCritical = body["throttle_memory_critical"]?.jsonPrimitive?.intOrNull ?: c.throttleMemoryCritical,
                    onlyDownloadWhenCharging = body["only_download_when_charging"]?.jsonPrimitive?.booleanOrNull ?: c.onlyDownloadWhenCharging
                )
                downloadManager.updateConfig(updated)
                call.respond(toJsonElement(mapOf("success" to true, "config" to configToMap(updated))))
            }

            // ─── BT Tracker 管理 ──────────────────────────
            get("/trackers") {
                val tm = downloadManager.trackerManager
                val cached = tm.getCachedTrackers()
                val meta = tm.getMeta()
                call.respond(toJsonElement(mapOf(
                    "trackers" to cached,
                    "tracker_count" to (cached?.split(",")?.count { it.trim().isNotBlank() } ?: 0),
                    "last_updated" to meta.lastUpdated,
                    "source_url" to meta.sourceUrl,
                    "status" to meta.status,
                    "auto_update" to downloadManager.config.btTrackerAutoUpdate,
                    "interval_hours" to downloadManager.config.btTrackerUpdateIntervalHours
                )))
            }
            post("/trackers/refresh") {
                val cfg = downloadManager.config
                val ok = downloadManager.trackerManager.refreshNow(
                    cfg.btTrackerSourceUrl,
                    cfg.btTrackerCustomList
                )
                // 热加载到运行中的 aria2
                if (ok && downloadManager.aria2.isRunning()) {
                    downloadManager.trackerManager.getCachedTrackers()?.let {
                        downloadManager.aria2.changeBtTracker(it)
                    }
                }
                call.respond(toJsonElement(mapOf(
                    "success" to ok,
                    "tracker_count" to downloadManager.trackerManager.getMeta().trackerCount,
                    "status" to downloadManager.trackerManager.getMeta().status
                )))
            }
            // 保存用户手动编辑的 Tracker 列表
            post("/trackers/save") {
                val body = call.receive<JsonObject>()
                val trackers = body["trackers"]?.jsonPrimitive?.contentOrNull ?: ""
                downloadManager.saveTrackerList(trackers)
                val count = trackers.split(",").count { it.trim().isNotBlank() }
                call.respond(toJsonElement(mapOf("success" to true, "tracker_count" to count)))
            }
        }
    }

    private fun taskToMap(task: DownloadManager.DownloadTask): Map<String, Any?> = mapOf(
        "id" to task.id, "url" to task.url, "file_name" to task.fileName,
        "save_path" to task.savePath, "total_size" to task.totalSize,
        "downloaded_bytes" to task.downloadedBytes, "progress" to task.progress,
        "speed" to task.speed, "upload_speed" to task.uploadSpeed,
        "status" to task.status, "error" to task.error,
        "created_at" to task.createdAt, "completed_at" to task.completedAt,
        "engine" to task.engine, "protocol" to task.protocol,
        "connections" to task.connections, "seeders" to task.seeders
    )

    private fun configToMap(c: DownloadManager.DownloadConfig): Map<String, Any?> = mapOf(
        // 普通配置
        "max_concurrent" to c.maxConcurrent,
        "max_connections_per_server" to c.maxConnectionsPerServer,
        "global_speed_limit" to c.globalSpeedLimit,
        "per_task_speed_limit" to c.perTaskSpeedLimit,
        "save_dir" to c.saveDir,
        "split_count" to c.splitCount,
        "min_split_size_mb" to c.minSplitSizeMb,
        "max_overall_upload_limit" to c.maxOverallUploadLimit,
        "file_allocation" to c.fileAllocation,
        // 高级 BT
        "bt_seed_ratio" to c.btSeedRatio,
        "bt_max_peers" to c.btMaxPeers,
        "bt_enable_dht" to c.btEnableDht,
        "bt_enable_lpd" to c.btEnableLpd,
        "dht_listen_port" to c.dhtListenPort,
        "bt_tracker_connect_timeout" to c.btTrackerConnectTimeout,
        "bt_request_peer_speed_limit" to c.btRequestPeerSpeedLimit,
        "bt_max_open_files" to c.btMaxOpenFiles,
        // 高级 网络/重试
        "disable_ipv6" to c.disableIpv6,
        "check_certificate" to c.checkCertificate,
        "max_tries" to c.maxTries,
        "retry_wait" to c.retryWait,
        "max_resume_tries" to c.maxResumeTries,
        "lowest_speed_limit" to c.lowestSpeedLimit,
        // 日志
        "log_level" to c.logLevel,
        // BT Tracker 管理
        "bt_tracker_auto_update" to c.btTrackerAutoUpdate,
        "bt_tracker_update_interval_hours" to c.btTrackerUpdateIntervalHours,
        "bt_tracker_source_url" to c.btTrackerSourceUrl,
        "bt_tracker_custom_list" to c.btTrackerCustomList,
        // Smart Throttle
        "smart_throttle" to c.smartThrottle,
        "throttle_temp_warn" to c.throttleTempWarn,
        "throttle_temp_critical" to c.throttleTempCritical,
        "throttle_cpu_warn" to c.throttleCpuWarn,
        "throttle_cpu_critical" to c.throttleCpuCritical,
        "throttle_battery_warn" to c.throttleBatteryWarn,
        "throttle_battery_critical" to c.throttleBatteryCritical,
        "throttle_memory_warn" to c.throttleMemoryWarn,
        "throttle_memory_critical" to c.throttleMemoryCritical,
        "only_download_when_charging" to c.onlyDownloadWhenCharging
    )
}
