package com.ufi_axis.viewmodel.module

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import com.ufi_axis.util.DebugLog

class DownloadModule(
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private fun api() = RetrofitClient.getApiService(AppPreferences(appContext))

    /**
     * 最近一次下载列表**成功落地**的时刻（单调时钟，`0` = 本进程内从未成功）。
     * 单调时钟的理由见 `DataFreshness.kt`（`currentTimeMillis` 可被用户 / NTP 随时改）。
     */
    @Volatile private var downloadsSuccessElapsed: Long = 0L

    /**
     * 读下载任务列表 + 引擎配置（`GET /api/downloads`）。
     *
     * ## 两条闸门（2026-09-05，与 `ToolsModule.loadTrafficLimit` / `NetworkModule.refreshWifi` 同款）
     *
     * ### 1. 只有"首屏"才写 loading 态
     * 判据是 [downloadsSuccessElapsed] `== 0L`（本进程从未成功落地过），**不是**
     * `tasks.isEmpty()`。这一条是"进下载管理时中间那块图标和描述闪一下、添加任务后就正常"
     * 的根因修复：`tasks.isEmpty()` 意味着**每次**进入空列表页都会写一次 `isLoading = true`，
     * 而 `DownloadTasksUi` 当时的空态判据带着 `&& !state.isLoading` ⇒ 空态 item 被摘掉、
     * 回包后再挂回来 = 闪一下；有任务时不写 loading，所以加了任务就"正常"了。
     * "一个任务都没有"是本页合法的稳定状态，和新鲜度闸门（下）用的是同一条口径。
     *
     * `isLoading` 现在只用于**首屏空态文案分档**（`DownloadEmptyPhase.LOADING`）；空态 item
     * 本身已改为恒定挂载，不再随它挂/卸（骨架屏 / `PullToRefreshBox` / 列表尾 skeleton /
     * 不确定态进度条已于 2026-09-04 全部删除）。已有任务时翻转它对 UI 毫无影响，却让整个
     * [com.ufi_axis.viewmodel.state.DownloadState] 换实例 ⇒ `DownloadScreen` 整页 + 整列表
     * 白付一次重组。两个条件都不满足时**一个字都不写**：`DownloadState` 是 data class，
     * `MutableStateFlow` 对结构相等值不发射，所以这条路径是真正的零重组（刷新本身不重组，
     * 只有回包带来真实字段变化时才重组一次）。
     *
     * ### 2. 数据新鲜就不重拉（[force] = false 时）
     * 判据 [isForegroundDataFresh]，窗口 [FOREGROUND_REFRESH_INTERVAL_MS]；基准
     * [downloadsSuccessElapsed] **只在成功落地后写** —— 失败 / 格式异常 / 取消都不写，
     * 否则会变成"越坏越不刷"。
     *
     * 这里刻意**不附加"已有数据"前置条件**（Tools/Network 那两处有 `!= null` 判断）：
     * "一个下载任务都没有"是本页完全合法的稳定状态，若要求 `tasks.isNotEmpty()` 才允许
     * 命中缓存，空列表就永远吃不到闸门、每次进页面都白拉一次。"从未成功拉过"这一种情况
     * 已由 `downloadsSuccessElapsed == 0L` 判成不新鲜（见 [foregroundRefreshDelayMs]）。
     *
     * @param silent 静默刷新：不置 `isLoading`。2s/6s 轮询用它（历史上"每 2s 闪一次刷新圈"
     *   就是漏了它）。
     * @param force 绕过新鲜度闸门。**必须为 true 的两类调用方**：
     *   - `DownloadScreen` 的 2s/6s 轮询 —— 产品要求是"下载进度实时可见"，
     *     2s 周期被 10s 新鲜窗口节流就等于把进度条钉死；
     *   - 顶栏手动刷新，以及本模块内**所有写后回读**（创建/暂停/恢复/删除/重试/重命名/
     *     清空已完成/改配置/Tracker 刷新与保存）—— 前提都是"值刚被改过"，吃缓存会让
     *     UI 停在旧状态上。
     */
    fun loadDownloads(silent: Boolean = false, force: Boolean = false) {
        if (!force && isForegroundDataFresh(downloadsSuccessElapsed, SystemClock.elapsedRealtime())) {
            return
        }
        scope.launch {
            try {
                // 只有"本进程从未成功拉到过列表"（真首屏）才写 loading 态；
                // 其余情况一个字都不写（零重组）。用 tasks.isEmpty() 会让空列表每次进页面都写一次，
                // 空态文案就跟着翻一次档 —— 那正是"图标和描述闪一下"的来源。
                if (!silent && downloadsSuccessElapsed == 0L) {
                    _state.value = _state.value.copy(isLoading = true)
                }
                val element = withContext(Dispatchers.IO) { api().getDownloads() }
                if (element !is JsonObject) {
                    _state.value = _state.value.copy(isLoading = false, errorMessage = "下载数据格式异常")
                    return@launch
                }
                val json = element
                val taskArray = json["tasks"]?.jsonArray
                val tasks = taskArray?.map { elem ->
                    val obj = elem.jsonObject
                    DownloadTaskItem(
                        id = obj["id"]?.jsonPrimitive?.content ?: "", url = obj["url"]?.jsonPrimitive?.content ?: "",
                        fileName = obj["file_name"]?.jsonPrimitive?.content ?: "", savePath = obj["save_path"]?.jsonPrimitive?.content ?: "",
                        totalSize = obj["total_size"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1L, downloadedBytes = obj["downloaded_bytes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        progress = obj["progress"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f, speed = obj["speed"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        uploadSpeed = obj["upload_speed"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L, status = obj["status"]?.jsonPrimitive?.content ?: "pending",
                        error = obj["error"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content },
                        createdAt = obj["created_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L, completedAt = obj["completed_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        protocol = obj["protocol"]?.jsonPrimitive?.content ?: "http",
                        connections = obj["connections"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0, seeders = obj["seeders"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    )
                } ?: emptyList()
                val cfgObj = json["config"]?.jsonObject
                val cfg = if (cfgObj != null) {
                    DownloadConfigItem(
                        maxConcurrent = cfgObj["max_concurrent"]?.jsonPrimitive?.content?.toIntOrNull() ?: 3,
                        maxConnectionsPerServer = cfgObj["max_connections_per_server"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4,
                        globalSpeedLimit = cfgObj["global_speed_limit"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        perTaskSpeedLimit = cfgObj["per_task_speed_limit"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        saveDir = cfgObj["save_dir"]?.jsonPrimitive?.content ?: "/storage/emulated/0/Download/UFI-AXIS/Download",
                        splitCount = cfgObj["split_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4,
                        maxOverallUploadLimit = cfgObj["max_overall_upload_limit"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        btSeedRatio = cfgObj["bt_seed_ratio"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 1.0f,
                        btMaxPeers = cfgObj["bt_max_peers"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50,
                        btEnableDht = cfgObj["bt_enable_dht"]?.jsonPrimitive?.content?.toBoolean() ?: true,
                        btEnableLpd = cfgObj["bt_enable_lpd"]?.jsonPrimitive?.content?.toBoolean() ?: true,
                        disableIpv6 = cfgObj["disable_ipv6"]?.jsonPrimitive?.content?.toBoolean() ?: true,
                        checkCertificate = cfgObj["check_certificate"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                        maxTries = cfgObj["max_tries"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5,
                        retryWait = cfgObj["retry_wait"]?.jsonPrimitive?.content?.toIntOrNull() ?: 3,
                        btTrackerAutoUpdate = cfgObj["bt_tracker_auto_update"]?.jsonPrimitive?.content?.toBoolean() ?: true,
                        btTrackerUpdateIntervalHours = cfgObj["bt_tracker_update_interval_hours"]?.jsonPrimitive?.content?.toIntOrNull() ?: 24,
                        btTrackerSourceUrl = cfgObj["bt_tracker_source_url"]?.jsonPrimitive?.content ?: "https://cf.trackerslist.com/best_aria2.txt",
                        btTrackerCustomList = cfgObj["bt_tracker_custom_list"]?.jsonPrimitive?.content ?: "",
                        smartThrottle = cfgObj["smart_throttle"]?.jsonPrimitive?.content?.toBoolean() ?: true,
                        throttleTempWarn = cfgObj["throttle_temp_warn"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 55f,
                        throttleTempCritical = cfgObj["throttle_temp_critical"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 70f,
                        throttleCpuWarn = cfgObj["throttle_cpu_warn"]?.jsonPrimitive?.content?.toIntOrNull() ?: 60,
                        throttleCpuCritical = cfgObj["throttle_cpu_critical"]?.jsonPrimitive?.content?.toIntOrNull() ?: 85,
                        throttleBatteryWarn = cfgObj["throttle_battery_warn"]?.jsonPrimitive?.content?.toIntOrNull() ?: 30,
                        throttleBatteryCritical = cfgObj["throttle_battery_critical"]?.jsonPrimitive?.content?.toIntOrNull() ?: 15,
                        throttleMemoryWarn = cfgObj["throttle_memory_warn"]?.jsonPrimitive?.content?.toIntOrNull() ?: 75,
                        throttleMemoryCritical = cfgObj["throttle_memory_critical"]?.jsonPrimitive?.content?.toIntOrNull() ?: 90,
                        onlyDownloadWhenCharging = cfgObj["only_download_when_charging"]?.jsonPrimitive?.content?.toBoolean() ?: false
                    )
                } else _state.value.config
                val trackerCount = json["tracker_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val trackerStatus = json["tracker_status"]?.jsonPrimitive?.content ?: "idle"
                val trackerLastUpdated = json["tracker_last_updated"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                _state.value = _state.value.copy(
                    tasks = tasks, activeCount = json["active"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    aria2Running = json["aria2_running"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                    aria2Version = json["aria2_version"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content },
                    config = cfg, isLoading = false, errorMessage = null,
                    // 首屏空态文案的分档依据（见 DownloadState.hasLoadedOnce）：随这次现有的
                    // copy 一并置 true，不额外发一次状态 ⇒ 零额外重组。
                    hasLoadedOnce = true,
                    trackerCount = trackerCount, trackerStatus = trackerStatus,
                    trackerLastUpdated = trackerLastUpdated, trackerRefreshing = false,
                    throttleState = json["throttle_state"]?.jsonPrimitive?.content ?: "normal",
                    throttleTemp = json["throttle_temp"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                    throttleCpu = json["throttle_cpu"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    throttleBattery = json["throttle_battery"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1,
                    throttleMemory = json["throttle_memory"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    throttleCharging = json["throttle_charging"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                    throttleWasStopped = json["throttle_was_stopped"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                    pendingEngineRestart = json["pending_engine_restart"]?.jsonPrimitive?.content?.toBoolean() ?: false
                )
                // 只在成功落地后记新鲜度基准，失败不记（否则一次失败会把后续重拉全跳过）
                downloadsSuccessElapsed = SystemClock.elapsedRealtime()
            } catch (e: Exception) { _state.value = _state.value.copy(isLoading = false, errorMessage = "加载失败: ${e.message}") }
        }
    }

    fun createDownload(url: String, fileName: String? = null, savePath: String? = null, speedLimit: Long? = null, connections: Int? = null) {
        scope.launch {
            try {
                // 注意：早期版本在此处对 URL 做客户端 DNS 预解析并把 IP 作为 resolved_ips 下发，
                // 但后端从未消费该字段，且 aria2 自身已通过 async-dns + 国内 DNS 服务器解析，
                // 故该预解析为死代码，已从 DownloadModule 删除。
                val body = mutableMapOf<String, Any>("url" to url)
                if (!fileName.isNullOrBlank()) body["file_name"] = fileName
                if (!savePath.isNullOrBlank()) body["save_path"] = savePath
                if (speedLimit != null && speedLimit > 0) body["speed_limit"] = speedLimit
                if (connections != null && connections > 0) body["connections"] = connections
                val resp = withContext(Dispatchers.IO) { api().createDownload(body) }

                // 处理 409 重复下载冲突
                if (resp.code() == 409) {
                    val respJson = resp.body()?.jsonObject
                    if (respJson != null && respJson["duplicate"]?.jsonPrimitive?.content?.toBoolean() == true) {
                        val existingObj = respJson["existing_task"]?.jsonObject
                        val existingTask = DownloadTaskItem(
                            id = existingObj?.get("id")?.jsonPrimitive?.content ?: "",
                            url = existingObj?.get("url")?.jsonPrimitive?.content ?: "",
                            fileName = existingObj?.get("file_name")?.jsonPrimitive?.content ?: "",
                            savePath = existingObj?.get("save_path")?.jsonPrimitive?.content ?: "",
                            totalSize = existingObj?.get("total_size")?.jsonPrimitive?.content?.toLongOrNull() ?: -1L,
                            downloadedBytes = existingObj?.get("downloaded_bytes")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                            progress = existingObj?.get("progress")?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                            speed = existingObj?.get("speed")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                            uploadSpeed = existingObj?.get("upload_speed")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                            status = existingObj?.get("status")?.jsonPrimitive?.content ?: "pending",
                            error = existingObj?.get("error")?.let { if (it is JsonNull) null else it.jsonPrimitive.content },
                            createdAt = existingObj?.get("created_at")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                            completedAt = existingObj?.get("completed_at")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                            protocol = existingObj?.get("protocol")?.jsonPrimitive?.content ?: "http",
                            connections = existingObj?.get("connections")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                            seeders = existingObj?.get("seeders")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        )
                        val suggested = respJson["suggested_filename"]?.jsonPrimitive?.content ?: ""
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
                        return@launch
                    }
                }
                // 写后回读：值刚被本 App 改过，必须绕过新鲜度闸门，否则回读到旧状态
                loadDownloads(force = true)
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "创建下载失败: ${e.message}") }
        }
    }

    /**
     * 用户确认重复下载后，以 force 模式提交（后台自动重命名文件）
     */
    fun createDownloadForce(url: String, fileName: String, savePath: String? = null, speedLimit: Long? = null, connections: Int? = null) {
        scope.launch {
            try {
                val body = mutableMapOf<String, Any>("url" to url, "file_name" to fileName)
                if (!savePath.isNullOrBlank()) body["save_path"] = savePath
                if (speedLimit != null && speedLimit > 0) body["speed_limit"] = speedLimit
                if (connections != null && connections > 0) body["connections"] = connections
                withContext(Dispatchers.IO) { api().createDownload(body, force = true) }
                _state.value = _state.value.copy(duplicateInfo = null)
                // 写后回读：值刚被本 App 改过，必须绕过新鲜度闸门，否则回读到旧状态
                loadDownloads(force = true)
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
                val resp = withContext(Dispatchers.IO) { api().pauseDownload(id) }
                if (!resp.isSuccessful) {
                    _state.value = _state.value.copy(errorMessage = "暂停下载失败: HTTP ${resp.code()}")
                    return@launch
                }
                // 写后回读：值刚被本 App 改过，必须绕过新鲜度闸门，否则回读到旧状态
                loadDownloads(force = true)
            } catch (e: Exception) {
                DebugLog.w("Download", "暂停下载失败: ${e.message}", e)
                _state.value = _state.value.copy(errorMessage = "暂停下载失败: ${e.message}")
            }
        }
    }

    fun resumeDownload(id: String) {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { api().resumeDownload(id) }
                if (!resp.isSuccessful) {
                    _state.value = _state.value.copy(errorMessage = "恢复下载失败: HTTP ${resp.code()}")
                    return@launch
                }
                // 写后回读：值刚被本 App 改过，必须绕过新鲜度闸门，否则回读到旧状态
                loadDownloads(force = true)
            } catch (e: Exception) {
                DebugLog.w("Download", "恢复下载失败: ${e.message}", e)
                _state.value = _state.value.copy(errorMessage = "恢复下载失败: ${e.message}")
            }
        }
    }

    fun deleteDownload(id: String, deleteFile: Boolean = false) {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { api().deleteDownload(id, deleteFile) }
                if (!resp.isSuccessful) {
                    _state.value = _state.value.copy(errorMessage = "删除下载失败: HTTP ${resp.code()}")
                    return@launch
                }
                // 写后回读：值刚被本 App 改过，必须绕过新鲜度闸门，否则回读到旧状态
                loadDownloads(force = true)
            } catch (e: Exception) {
                DebugLog.w("Download", "删除下载失败: ${e.message}", e)
                _state.value = _state.value.copy(errorMessage = "删除下载失败: ${e.message}")
            }
        }
    }

    /** 重新下载：删除旧任务并以相同URL重新创建 */
    fun retryDownload(id: String) {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { api().retryDownload(id) }
                if (!resp.isSuccessful) {
                    _state.value = _state.value.copy(errorMessage = "重试下载失败: HTTP ${resp.code()}")
                    return@launch
                }
                // 写后回读：值刚被本 App 改过，必须绕过新鲜度闸门，否则回读到旧状态
                loadDownloads(force = true)
            } catch (e: Exception) {
                DebugLog.w("Download", "重试下载失败: ${e.message}", e)
                _state.value = _state.value.copy(errorMessage = "重试下载失败: ${e.message}")
            }
        }
    }

    /** 重命名下载任务（文件） */
    fun renameDownload(id: String, newName: String) {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { api().renameDownload(id, mapOf("name" to newName)) }
                if (!resp.isSuccessful) {
                    _state.value = _state.value.copy(errorMessage = "重命名失败: HTTP ${resp.code()}")
                    return@launch
                }
                // 写后回读：值刚被本 App 改过，必须绕过新鲜度闸门，否则回读到旧状态
                loadDownloads(force = true)
            } catch (e: Exception) {
                DebugLog.w("Download", "重命名失败: ${e.message}", e)
                _state.value = _state.value.copy(errorMessage = "重命名失败: ${e.message}")
            }
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
                val resp = withContext(Dispatchers.IO) { api().clearCompletedDownloads() }
                if (!resp.isSuccessful) {
                    _state.value = _state.value.copy(errorMessage = "清空已完成下载失败: HTTP ${resp.code()}")
                    return@launch
                }
                // 写后回读：值刚被本 App 改过，必须绕过新鲜度闸门，否则回读到旧状态
                loadDownloads(force = true)
            } catch (e: Exception) {
                DebugLog.w("Download", "清空已完成下载失败: ${e.message}", e)
                _state.value = _state.value.copy(errorMessage = "清空已完成下载失败: ${e.message}")
            }
        }
    }

    fun updateDownloadConfig(config: DownloadConfigItem) {
        scope.launch {
            try {
                val body = mutableMapOf<String, Any>(
                    "max_concurrent" to config.maxConcurrent,
                    "max_connections_per_server" to config.maxConnectionsPerServer,
                    "global_speed_limit" to config.globalSpeedLimit,
                    "per_task_speed_limit" to config.perTaskSpeedLimit,
                    "save_dir" to config.saveDir,
                    "split_count" to config.splitCount,
                    "max_overall_upload_limit" to config.maxOverallUploadLimit,
                    "bt_seed_ratio" to config.btSeedRatio,
                    "bt_max_peers" to config.btMaxPeers,
                    "bt_enable_dht" to config.btEnableDht,
                    "bt_enable_lpd" to config.btEnableLpd,
                    "disable_ipv6" to config.disableIpv6,
                    "check_certificate" to config.checkCertificate,
                    "max_tries" to config.maxTries,
                    "retry_wait" to config.retryWait,
                    "bt_tracker_auto_update" to config.btTrackerAutoUpdate,
                    "bt_tracker_update_interval_hours" to config.btTrackerUpdateIntervalHours,
                    "bt_tracker_source_url" to config.btTrackerSourceUrl,
                    "bt_tracker_custom_list" to config.btTrackerCustomList,
                    "smart_throttle" to config.smartThrottle,
                    "throttle_temp_warn" to config.throttleTempWarn,
                    "throttle_temp_critical" to config.throttleTempCritical,
                    "throttle_cpu_warn" to config.throttleCpuWarn,
                    "throttle_cpu_critical" to config.throttleCpuCritical,
                    "throttle_battery_warn" to config.throttleBatteryWarn,
                    "throttle_battery_critical" to config.throttleBatteryCritical,
                    "throttle_memory_warn" to config.throttleMemoryWarn,
                    "throttle_memory_critical" to config.throttleMemoryCritical,
                    "only_download_when_charging" to config.onlyDownloadWhenCharging
                )
                val resp = withContext(Dispatchers.IO) { api().updateDownloadConfig(body) }
                if (!resp.isSuccessful) {
                    _state.value = _state.value.copy(errorMessage = "更新配置失败: HTTP ${resp.code()}")
                    return@launch
                }
                // 写后回读：值刚被本 App 改过，必须绕过新鲜度闸门，否则回读到旧状态
                loadDownloads(force = true)
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "更新配置失败: ${e.message}") }
        }
    }

    fun validatePath(path: String, onResult: (Map<String, Any?>) -> Unit) {
        scope.launch {
            try {
                val element = withContext(Dispatchers.IO) { api().validateDownloadPath(path) }
                if (element !is JsonObject) { onResult(mapOf("valid" to false, "error" to "数据格式异常")); return@launch }
                val json = element
                onResult(json.entries.associate { (key, value) ->
                    key to when {
                        value is JsonNull -> null
                        value is JsonPrimitive -> {
                            val c = value.content
                            when {
                                c == "true" || c == "false" -> c.toBoolean()
                                c.toLongOrNull() != null -> c.toLong()
                                else -> c
                            }
                        }
                        else -> value.toString()
                    }
                })
            } catch (e: Exception) { onResult(mapOf("valid" to false, "error" to e.message)) }
        }
    }

    fun refreshTrackers() {
        scope.launch {
            try {
                _state.value = _state.value.copy(trackerRefreshing = true)
                val resp = withContext(Dispatchers.IO) { api().refreshTrackers() }
                if (!resp.isSuccessful) {
                    _state.value = _state.value.copy(trackerRefreshing = false, errorMessage = "Tracker 刷新失败: HTTP ${resp.code()}")
                    return@launch
                }
                // 写后回读：值刚被本 App 改过，必须绕过新鲜度闸门，否则回读到旧状态
                loadDownloads(force = true)
            } catch (e: Exception) { _state.value = _state.value.copy(trackerRefreshing = false, errorMessage = "Tracker 刷新失败: ${e.message}") }
        }
    }

    fun loadTrackers() {
        scope.launch {
            try {
                _state.value = _state.value.copy(trackerListLoading = true)
                val element = withContext(Dispatchers.IO) { api().getTrackers() }
                if (element !is JsonObject) {
                    _state.value = _state.value.copy(trackerListLoading = false, errorMessage = "Tracker 列表格式异常")
                    return@launch
                }
                val json = element
                val trackers = json["trackers"]?.let { if (it is JsonNull) "" else it.jsonPrimitive.content } ?: ""
                _state.value = _state.value.copy(cachedTrackerList = trackers, trackerListLoading = false)
            } catch (e: Exception) { _state.value = _state.value.copy(trackerListLoading = false, errorMessage = "加载 Tracker 列表失败: ${e.message}") }
        }
    }

    fun saveTrackerList(trackers: String) {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { api().saveTrackers(mapOf("trackers" to trackers)) }
                if (!resp.isSuccessful) {
                    _state.value = _state.value.copy(errorMessage = "保存 Tracker 列表失败: HTTP ${resp.code()}")
                    return@launch
                }
                // 写后回读：值刚被本 App 改过，必须绕过新鲜度闸门，否则回读到旧状态
                loadDownloads(force = true)
            } catch (e: Exception) { _state.value = _state.value.copy(errorMessage = "保存 Tracker 列表失败: ${e.message}") }
        }
    }
}
