package com.ufi_axis.viewmodel.module

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.google.gson.JsonParser
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class DownloadModule(
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    fun loadDownloads() {
        scope.launch {
            try {
                _state.value = _state.value.copy(isLoading = true)
                val prefs = AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads"
                val result = withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder().url(url).addHeader("Authorization", "Bearer ${prefs.token}").build()
                    client.newCall(request).execute().body?.string() ?: "{}"
                }
                val json = JsonParser.parseString(result).asJsonObject
                val taskArray = json.getAsJsonArray("tasks") ?: com.google.gson.JsonArray()
                val tasks = taskArray.map { elem ->
                    val obj = elem.asJsonObject
                    DownloadTaskItem(
                        id = obj.get("id")?.asString ?: "", url = obj.get("url")?.asString ?: "",
                        fileName = obj.get("file_name")?.asString ?: "", savePath = obj.get("save_path")?.asString ?: "",
                        totalSize = obj.get("total_size")?.asLong ?: -1L, downloadedBytes = obj.get("downloaded_bytes")?.asLong ?: 0L,
                        progress = obj.get("progress")?.asFloat ?: 0f, speed = obj.get("speed")?.asLong ?: 0L,
                        uploadSpeed = obj.get("upload_speed")?.asLong ?: 0L, status = obj.get("status")?.asString ?: "pending",
                        error = obj.get("error")?.let { if (it.isJsonNull) null else it.asString },
                        createdAt = obj.get("created_at")?.asLong ?: 0L, completedAt = obj.get("completed_at")?.asLong ?: 0L,
                        engine = obj.get("engine")?.asString ?: "aria2", protocol = obj.get("protocol")?.asString ?: "http",
                        connections = obj.get("connections")?.asInt ?: 0, seeders = obj.get("seeders")?.asInt ?: 0
                    )
                }
                val cfgObj = json.getAsJsonObject("config")
                val cfg = if (cfgObj != null) {
                    DownloadConfigItem(
                        maxConcurrent = cfgObj.get("max_concurrent")?.asInt ?: 3,
                        maxConnectionsPerServer = cfgObj.get("max_connections_per_server")?.asInt ?: 4,
                        globalSpeedLimit = cfgObj.get("global_speed_limit")?.asLong ?: 0L,
                        perTaskSpeedLimit = cfgObj.get("per_task_speed_limit")?.asLong ?: 0L,
                        saveDir = cfgObj.get("save_dir")?.asString ?: "/storage/emulated/0/Downloads/UFI",
                        splitCount = cfgObj.get("split_count")?.asInt ?: 4,
                        minSplitSizeMb = cfgObj.get("min_split_size_mb")?.asInt ?: 1,
                        maxOverallUploadLimit = cfgObj.get("max_overall_upload_limit")?.asLong ?: 0L,
                        fileAllocation = cfgObj.get("file_allocation")?.asString ?: "none",
                        btSeedRatio = cfgObj.get("bt_seed_ratio")?.asFloat ?: 1.0f,
                        btMaxPeers = cfgObj.get("bt_max_peers")?.asInt ?: 50,
                        btEnableDht = cfgObj.get("bt_enable_dht")?.asBoolean ?: true,
                        btEnableLpd = cfgObj.get("bt_enable_lpd")?.asBoolean ?: true,
                        dhtListenPort = cfgObj.get("dht_listen_port")?.asString ?: "6881-6999",
                        btTrackerConnectTimeout = cfgObj.get("bt_tracker_connect_timeout")?.asInt ?: 60,
                        btRequestPeerSpeedLimit = cfgObj.get("bt_request_peer_speed_limit")?.asLong ?: 0L,
                        btMaxOpenFiles = cfgObj.get("bt_max_open_files")?.asInt ?: 100,
                        disableIpv6 = cfgObj.get("disable_ipv6")?.asBoolean ?: true,
                        checkCertificate = cfgObj.get("check_certificate")?.asBoolean ?: false,
                        maxTries = cfgObj.get("max_tries")?.asInt ?: 5,
                        retryWait = cfgObj.get("retry_wait")?.asInt ?: 3,
                        maxResumeTries = cfgObj.get("max_resume_tries")?.asInt ?: 0,
                        lowestSpeedLimit = cfgObj.get("lowest_speed_limit")?.asLong ?: 0L,
                        logLevel = cfgObj.get("log_level")?.asString ?: "notice",
                        btTrackerAutoUpdate = cfgObj.get("bt_tracker_auto_update")?.asBoolean ?: true,
                        btTrackerUpdateIntervalHours = cfgObj.get("bt_tracker_update_interval_hours")?.asInt ?: 24,
                        btTrackerSourceUrl = cfgObj.get("bt_tracker_source_url")?.asString ?: "https://cf.trackerslist.com/best_aria2.txt",
                        btTrackerCustomList = cfgObj.get("bt_tracker_custom_list")?.asString ?: "",
                        smartThrottle = cfgObj.get("smart_throttle")?.asBoolean ?: true,
                        throttleTempWarn = cfgObj.get("throttle_temp_warn")?.asFloat ?: 55f,
                        throttleTempCritical = cfgObj.get("throttle_temp_critical")?.asFloat ?: 70f,
                        throttleCpuWarn = cfgObj.get("throttle_cpu_warn")?.asInt ?: 60,
                        throttleCpuCritical = cfgObj.get("throttle_cpu_critical")?.asInt ?: 85,
                        throttleBatteryWarn = cfgObj.get("throttle_battery_warn")?.asInt ?: 30,
                        throttleBatteryCritical = cfgObj.get("throttle_battery_critical")?.asInt ?: 15,
                        throttleMemoryWarn = cfgObj.get("throttle_memory_warn")?.asInt ?: 75,
                        throttleMemoryCritical = cfgObj.get("throttle_memory_critical")?.asInt ?: 90,
                        onlyDownloadWhenCharging = cfgObj.get("only_download_when_charging")?.asBoolean ?: false
                    )
                } else _state.value.config
                val trackerCount = json.get("tracker_count")?.asInt ?: 0
                val trackerStatus = json.get("tracker_status")?.asString ?: "idle"
                val trackerLastUpdated = json.get("tracker_last_updated")?.asLong ?: 0L
                _state.value = _state.value.copy(
                    tasks = tasks, activeCount = json.get("active")?.asInt ?: 0,
                    aria2Running = json.get("aria2_running")?.asBoolean ?: false,
                    aria2Version = json.get("aria2_version")?.let { if (it.isJsonNull) null else it.asString },
                    config = cfg, isLoading = false, errorMessage = null,
                    trackerCount = trackerCount, trackerStatus = trackerStatus,
                    trackerLastUpdated = trackerLastUpdated, trackerRefreshing = false,
                    throttleState = json.get("throttle_state")?.asString ?: "normal",
                    throttleTemp = json.get("throttle_temp")?.asFloat ?: 0f,
                    throttleCpu = json.get("throttle_cpu")?.asInt ?: 0,
                    throttleBattery = json.get("throttle_battery")?.asInt ?: -1,
                    throttleMemory = json.get("throttle_memory")?.asInt ?: 0,
                    throttleCharging = json.get("throttle_charging")?.asBoolean ?: false,
                    throttleWasStopped = json.get("throttle_was_stopped")?.asBoolean ?: false
                )
            } catch (e: Exception) { _state.value = _state.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}") }
        }
    }

    fun createDownload(url: String, fileName: String? = null, savePath: String? = null, speedLimit: Long? = null, connections: Int? = null) {
        scope.launch {
            try {
                val prefs = AppPreferences(appContext)
                val apiUrl = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    // 预解析 DNS：路由器端系统 DNS 不可用，由手机端解析并传 IP 给 Core
                    val resolvedIps = try {
                        val host = java.net.URI(url).host
                        if (host != null && host != java.net.URI("http://127.0.0.1/").host) {
                            java.net.InetAddress.getAllByName(host).map { it.hostAddress }
                        } else emptyList()
                    } catch (_: Exception) { emptyList() }
                    val json = com.google.gson.JsonObject().apply {
                        addProperty("url", url); if (!fileName.isNullOrBlank()) addProperty("file_name", fileName)
                        if (!savePath.isNullOrBlank()) addProperty("save_path", savePath)
                        if (speedLimit != null && speedLimit > 0) addProperty("speed_limit", speedLimit)
                        if (connections != null && connections > 0) addProperty("connections", connections)
                        if (resolvedIps.isNotEmpty()) {
                            val arr = com.google.gson.JsonArray(); resolvedIps.forEach { arr.add(it) }; add("resolved_ips", arr)
                        }
                    }
                    val mediaType = "application/json".toMediaTypeOrNull()!!
                    val body = json.toString().toRequestBody(mediaType)
                    val request = okhttp3.Request.Builder().url(apiUrl).post(body).addHeader("Authorization", "Bearer ${prefs.token}").build()
                    val response = client.newCall(request).execute()

                    // 处理 409 重复下载冲突
                    if (response.code == 409) {
                        val responseBody = response.body?.string() ?: "{}"
                        val respJson = com.google.gson.JsonParser.parseString(responseBody).asJsonObject
                        if (respJson.get("duplicate")?.asBoolean == true) {
                            val existingObj = respJson.getAsJsonObject("existing_task")
                            val existingTask = DownloadTaskItem(
                                id = existingObj.get("id")?.asString ?: "",
                                url = existingObj.get("url")?.asString ?: "",
                                fileName = existingObj.get("file_name")?.asString ?: "",
                                savePath = existingObj.get("save_path")?.asString ?: "",
                                totalSize = existingObj.get("total_size")?.asLong ?: -1L,
                                downloadedBytes = existingObj.get("downloaded_bytes")?.asLong ?: 0L,
                                progress = existingObj.get("progress")?.asFloat ?: 0f,
                                speed = existingObj.get("speed")?.asLong ?: 0L,
                                uploadSpeed = existingObj.get("upload_speed")?.asLong ?: 0L,
                                status = existingObj.get("status")?.asString ?: "pending",
                                error = existingObj.get("error")?.let { if (it.isJsonNull) null else it.asString },
                                createdAt = existingObj.get("created_at")?.asLong ?: 0L,
                                completedAt = existingObj.get("completed_at")?.asLong ?: 0L,
                                engine = existingObj.get("engine")?.asString ?: "aria2",
                                protocol = existingObj.get("protocol")?.asString ?: "http",
                                connections = existingObj.get("connections")?.asInt ?: 0,
                                seeders = existingObj.get("seeders")?.asInt ?: 0
                            )
                            val suggested = respJson.get("suggested_filename")?.asString ?: ""
                            _state.value = _state.value.copy(
                                duplicateInfo = DuplicateInfo(
                                    existingTask = existingTask,
                                    newUrl = url,
                                    suggestedFileName = suggested,
                                    fileName = fileName,
                                    savePath = savePath,
                                    speedLimit = speedLimit,
                                    connections = connections
                                )
                            )
                            return@withContext
                        }
                    }
                    loadDownloads()
                }
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "创建下载失败: ${e.message}") }
        }
    }

    /**
     * 用户确认重复下载后，以 force 模式提交（后台自动重命名文件）
     */
    fun createDownloadForce(url: String, fileName: String, savePath: String? = null, speedLimit: Long? = null, connections: Int? = null) {
        scope.launch {
            try {
                val prefs = AppPreferences(appContext)
                val apiUrl = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads?force=true"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val json = com.google.gson.JsonObject().apply {
                        addProperty("url", url); addProperty("file_name", fileName)
                        if (!savePath.isNullOrBlank()) addProperty("save_path", savePath)
                        if (speedLimit != null && speedLimit > 0) addProperty("speed_limit", speedLimit)
                        if (connections != null && connections > 0) addProperty("connections", connections)
                    }
                    val mediaType = "application/json".toMediaTypeOrNull()!!
                    val body = json.toString().toRequestBody(mediaType)
                    val request = okhttp3.Request.Builder().url(apiUrl).post(body).addHeader("Authorization", "Bearer ${prefs.token}").build()
                    client.newCall(request).execute()
                }
                _state.value = _state.value.copy(duplicateInfo = null)
                loadDownloads()
            } catch (e: Exception) {
                _state.value = _state.value.copy(duplicateInfo = null, errorMessage = "创建下载失败: ${e.message}")
            }
        }
    }

    /** 关闭重复下载对话框 */
    fun dismissDuplicate() {
        _state.value = _state.value.copy(duplicateInfo = null)
    }

    fun pauseDownload(id: String) {
        scope.launch {
            try {
                val prefs = AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/$id/pause"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).addHeader("Authorization", "Bearer ${prefs.token}").build()
                    client.newCall(request).execute()
                }
                loadDownloads()
            } catch (_: Exception) {}
        }
    }

    fun resumeDownload(id: String) {
        scope.launch {
            try {
                val prefs = AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/$id/resume"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).addHeader("Authorization", "Bearer ${prefs.token}").build()
                    client.newCall(request).execute()
                }
                loadDownloads()
            } catch (_: Exception) {}
        }
    }

    fun deleteDownload(id: String, deleteFile: Boolean = false) {
        scope.launch {
            try {
                val prefs = AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/$id?delete_file=$deleteFile"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder().url(url).delete().addHeader("Authorization", "Bearer ${prefs.token}").build()
                    client.newCall(request).execute()
                }
                loadDownloads()
            } catch (_: Exception) {}
        }
    }

    /** 重新下载：删除旧任务并以相同URL重新创建 */
    fun retryDownload(id: String) {
        scope.launch {
            try {
                val prefs = AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/$id/retry"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder().url(url).post(ByteArray(0).toRequestBody(null))
                        .addHeader("Authorization", "Bearer ${prefs.token}").build()
                    client.newCall(request).execute()
                }
                loadDownloads()
            } catch (_: Exception) {}
        }
    }

    /** 复制下载链接到剪贴板 */
    fun copyLinkToClipboard(text: String) {
        val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("download_url", text))
    }

    fun clearCompletedDownloads() {
        scope.launch {
            try {
                val prefs = AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/clear-completed"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).addHeader("Authorization", "Bearer ${prefs.token}").build()
                    client.newCall(request).execute()
                }
                loadDownloads()
            } catch (_: Exception) {}
        }
    }

    fun updateDownloadConfig(config: DownloadConfigItem) {
        scope.launch {
            try {
                val prefs = AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/config"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val json = com.google.gson.JsonObject().apply {
                        addProperty("max_concurrent", config.maxConcurrent)
                        addProperty("max_connections_per_server", config.maxConnectionsPerServer)
                        addProperty("global_speed_limit", config.globalSpeedLimit)
                        addProperty("per_task_speed_limit", config.perTaskSpeedLimit)
                        addProperty("save_dir", config.saveDir)
                        addProperty("split_count", config.splitCount)
                        addProperty("min_split_size_mb", config.minSplitSizeMb)
                        addProperty("max_overall_upload_limit", config.maxOverallUploadLimit)
                        addProperty("file_allocation", config.fileAllocation)
                        addProperty("bt_seed_ratio", config.btSeedRatio)
                        addProperty("bt_max_peers", config.btMaxPeers)
                        addProperty("bt_enable_dht", config.btEnableDht)
                        addProperty("bt_enable_lpd", config.btEnableLpd)
                        addProperty("dht_listen_port", config.dhtListenPort)
                        addProperty("bt_tracker_connect_timeout", config.btTrackerConnectTimeout)
                        addProperty("bt_request_peer_speed_limit", config.btRequestPeerSpeedLimit)
                        addProperty("bt_max_open_files", config.btMaxOpenFiles)
                        addProperty("disable_ipv6", config.disableIpv6)
                        addProperty("check_certificate", config.checkCertificate)
                        addProperty("max_tries", config.maxTries)
                        addProperty("retry_wait", config.retryWait)
                        addProperty("max_resume_tries", config.maxResumeTries)
                        addProperty("lowest_speed_limit", config.lowestSpeedLimit)
                        addProperty("log_level", config.logLevel)
                        addProperty("bt_tracker_auto_update", config.btTrackerAutoUpdate)
                        addProperty("bt_tracker_update_interval_hours", config.btTrackerUpdateIntervalHours)
                        addProperty("bt_tracker_source_url", config.btTrackerSourceUrl)
                        addProperty("bt_tracker_custom_list", config.btTrackerCustomList)
                        addProperty("smart_throttle", config.smartThrottle)
                        addProperty("throttle_temp_warn", config.throttleTempWarn)
                        addProperty("throttle_temp_critical", config.throttleTempCritical)
                        addProperty("throttle_cpu_warn", config.throttleCpuWarn)
                        addProperty("throttle_cpu_critical", config.throttleCpuCritical)
                        addProperty("throttle_battery_warn", config.throttleBatteryWarn)
                        addProperty("throttle_battery_critical", config.throttleBatteryCritical)
                        addProperty("throttle_memory_warn", config.throttleMemoryWarn)
                        addProperty("throttle_memory_critical", config.throttleMemoryCritical)
                        addProperty("only_download_when_charging", config.onlyDownloadWhenCharging)
                    }
                    val mediaType = "application/json".toMediaTypeOrNull()!!
                    val body = json.toString().toRequestBody(mediaType)
                    val request = okhttp3.Request.Builder().url(url).put(body).addHeader("Authorization", "Bearer ${prefs.token}").build()
                    client.newCall(request).execute()
                }
                loadDownloads()
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "更新配置失败: ${e.message}") }
        }
    }

    fun validatePath(path: String, onResult: (Map<String, Any?>) -> Unit) {
        scope.launch {
            try {
                val prefs = AppPreferences(appContext)
                val encoded = java.net.URLEncoder.encode(path, "UTF-8")
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/validate-path?path=$encoded"
                val result = withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder().url(url).addHeader("Authorization", "Bearer ${prefs.token}").build()
                    client.newCall(request).execute().body?.string() ?: "{}"
                }
                val json = JsonParser.parseString(result).asJsonObject
                onResult(json.entrySet().associate { entry ->
                    entry.key to when {
                        entry.value.isJsonNull -> null
                        entry.value.isJsonPrimitive -> { val p = entry.value.asJsonPrimitive; when { p.isBoolean -> p.asBoolean; p.isNumber -> p.asLong; else -> p.asString } }
                        else -> entry.value.toString()
                    }
                })
            } catch (e: Exception) { onResult(mapOf("valid" to false, "error" to e.message)) }
        }
    }

    fun refreshTrackers() {
        scope.launch {
            try {
                _state.value = _state.value.copy(trackerRefreshing = true)
                val prefs = AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/trackers/refresh"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).addHeader("Authorization", "Bearer ${prefs.token}").build()
                    client.newCall(request).execute().close()
                }
                loadDownloads()
            } catch (e: Exception) { _state.value = _state.value.copy(trackerRefreshing = false, errorMessage = "Tracker 刷新失败: ${e.message}") }
        }
    }

    fun loadTrackers() {
        scope.launch {
            try {
                _state.value = _state.value.copy(trackerListLoading = true)
                val prefs = AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/trackers"
                val result = withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder().url(url).addHeader("Authorization", "Bearer ${prefs.token}").build()
                    client.newCall(request).execute().body?.string() ?: "{}"
                }
                val json = JsonParser.parseString(result).asJsonObject
                val trackers = json.get("trackers")?.let { if (it.isJsonNull) "" else it.asString } ?: ""
                _state.value = _state.value.copy(cachedTrackerList = trackers, trackerListLoading = false)
            } catch (e: Exception) { _state.value = _state.value.copy(trackerListLoading = false, errorMessage = "加载 Tracker 列表失败: ${e.message}") }
        }
    }

    fun saveTrackerList(trackers: String) {
        scope.launch {
            try {
                val prefs = AppPreferences(appContext)
                val url = "http://${prefs.serverIp}:${prefs.serverPort}/api/downloads/trackers/save"
                withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val json = com.google.gson.JsonObject().apply { addProperty("trackers", trackers) }
                    val mediaType = "application/json".toMediaTypeOrNull()!!
                    val body = json.toString().toRequestBody(mediaType)
                    val request = okhttp3.Request.Builder().url(url).post(body).addHeader("Authorization", "Bearer ${prefs.token}").build()
                    client.newCall(request).execute().close()
                }
                loadDownloads()
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "保存 Tracker 列表失败: ${e.message}") }
        }
    }
}
