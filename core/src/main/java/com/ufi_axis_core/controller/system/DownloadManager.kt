package com.ufi_axis_core.controller.system

import com.ufi_axis_core.util.AppLogger
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 双引擎后台下载管理器
 *
 * Java 引擎: HTTP/HTTPS（单连接 Range 断点续传）
 * aria2 引擎: FTP / magnet / torrent / metalink（多连接、P2P）
 */
class DownloadManager(
    private val appContext: android.content.Context
) {
    companion object {
        private const val TAG = "DownloadManager"
        private const val BUFFER_SIZE = 8192
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val ARIA2_POLL_INTERVAL_MS = 1500L
        const val DEFAULT_SAVE_DIR = "/storage/emulated/0/Downloads/UFI"
    }

    @Serializable
    data class DownloadTask(
        val id: String = "",
        val url: String = "",
        var fileName: String = "",
        var savePath: String = "",
        var totalSize: Long = -1L,
        var downloadedBytes: Long = 0L,
        var progress: Float = 0f,
        var speed: Long = 0L,
        var status: String = "pending",
        var error: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        var completedAt: Long = 0L,
        val engine: String = "java",
        val protocol: String = "http",
        var gid: String? = null,
        var connections: Int = 0,
        var seeders: Int = 0,
        var uploadSpeed: Long = 0L
    )

    @Serializable
    data class DownloadConfig(
        // ── 普通配置 ──
        var maxConcurrent: Int = 3,
        var maxConnectionsPerServer: Int = 4,
        var globalSpeedLimit: Long = 0,
        var perTaskSpeedLimit: Long = 0,
        var saveDir: String = DEFAULT_SAVE_DIR,
        var splitCount: Int = 4,
        var minSplitSizeMb: Int = 1,
        var maxOverallUploadLimit: Long = 0,
        var fileAllocation: String = "prealloc",
        // ── 高级 BT ──
        var btSeedRatio: Float = 1.0f,
        var btMaxPeers: Int = 50,
        var btEnableDht: Boolean = true,
        var btEnableLpd: Boolean = true,
        var dhtListenPort: String = "6881-6999",
        var btTrackerConnectTimeout: Int = 60,
        var btRequestPeerSpeedLimit: Long = 0,
        var btMaxOpenFiles: Int = 100,
        // ── 高级 网络/重试 ──
        var disableIpv6: Boolean = true,
        var checkCertificate: Boolean = false,
        var maxTries: Int = 5,
        var retryWait: Int = 3,
        var maxResumeTries: Int = 0,
        var lowestSpeedLimit: Long = 0,
        // ── 日志 ──
        var logLevel: String = "notice",
        // ── BT Tracker 管理 ──
        var btTrackerAutoUpdate: Boolean = true,
        var btTrackerUpdateIntervalHours: Int = 24,
        var btTrackerSourceUrl: String = "https://cf.trackerslist.com/best_aria2.txt",
        var btTrackerCustomList: String = "",
        // ── 智能性能控制 ──
        var smartThrottle: Boolean = true,
        var throttleTempWarn: Float = 55f,
        var throttleTempCritical: Float = 70f,
        var throttleCpuWarn: Int = 60,
        var throttleCpuCritical: Int = 85,
        var throttleBatteryWarn: Int = 30,
        var throttleBatteryCritical: Int = 15,
        var throttleMemoryWarn: Int = 75,
        var throttleMemoryCritical: Int = 90,
        var onlyDownloadWhenCharging: Boolean = false
    )

    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val persistFile: File
    private val configFile: File
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    val aria2 = Aria2Engine(appContext)
    val trackerManager = TrackerManager(appContext)
    var config = DownloadConfig()
        private set

    private var aria2PollJob: Job? = null
    private var smartThrottleJob: Job? = null
    private var javaSemaphore = kotlinx.coroutines.sync.Semaphore(3)

    /** 当前智能限速状态（供 UI 显示） */
    @Volatile var throttleState: String = "normal"
        private set
    @Volatile var throttleTemp: Float = 0f
        private set
    @Volatile var throttleCpu: Int = 0
        private set
    @Volatile var throttleBattery: Int = -1
        private set
    @Volatile var throttleMemory: Int = 0
        private set
    @Volatile var throttleCharging: Boolean = false
        private set
    @Volatile var throttleWasStopped: Boolean = false
        private set

    /** DataScheduler 引用，复用已采集的传感器缓存数据，避免重复 shell 调用 */
    private var dataScheduler: com.ufi_axis_core.core.scheduler.DataScheduler? = null
    fun setDataScheduler(scheduler: com.ufi_axis_core.core.scheduler.DataScheduler) {
        this.dataScheduler = scheduler
    }

    init {
        val dir = File(appContext.filesDir, "downloads")
        dir.mkdirs()
        persistFile = File(dir, "tasks.json")
        configFile = File(dir, "config.json")
        loadConfig()
        loadTasks()
        // 将之前下载中的任务标记为暂停
        tasks.values.filter { it.status == "downloading" }.forEach { it.status = "paused" }
        saveTasks()
        File(config.saveDir).mkdirs()
        javaSemaphore = kotlinx.coroutines.sync.Semaphore(config.maxConcurrent)

        // 懒加载: 探测 aria2 版本信息（不长期运行进程）
        aria2.probeVersion()

        // 启动 BT Tracker 自动更新
        if (config.btTrackerAutoUpdate) {
            trackerManager.startAutoUpdate(
                config.btTrackerUpdateIntervalHours,
                config.btTrackerSourceUrl,
                config.btTrackerCustomList
            )
        }

        // 如果有未完成的 aria2 任务，自动启动引擎恢复
        val hasPendingAria2 = tasks.values.any {
            it.engine == "aria2" && (it.status == "paused" || it.status == "pending")
        }
        if (hasPendingAria2) {
            aria2.start(config)
            startAria2Polling()
        }

        // 启动智能性能监控
        startSmartThrottle()
    }

    // ─── 配置 ──────────────────────────────────────────────

    fun updateConfig(newConfig: DownloadConfig) {
        config = newConfig
        saveConfig()
        javaSemaphore = kotlinx.coroutines.sync.Semaphore(config.maxConcurrent)

        // 重启 Tracker 定时任务
        trackerManager.stopAutoUpdate()
        if (config.btTrackerAutoUpdate) {
            trackerManager.startAutoUpdate(
                config.btTrackerUpdateIntervalHours,
                config.btTrackerSourceUrl,
                config.btTrackerCustomList
            )
        }

        if (aria2.isRunning()) {
            aria2.changeGlobalSetting(
                maxConcurrentDownloads = config.maxConcurrent,
                maxOverallDownloadLimit = if (config.globalSpeedLimit > 0) config.globalSpeedLimit else null,
                maxOverallUploadLimit = if (config.maxOverallUploadLimit > 0) config.maxOverallUploadLimit else null
            )
        }

        // 重启智能限速
        startSmartThrottle()
    }

    // ─── Tracker 列表编辑 ──────────────────────────────────

    /** 保存用户手动编辑的 Tracker 列表 */
    fun saveTrackerList(trackers: String) {
        val trackerFile = File(appContext.filesDir, "aria2/bt-trackers.txt")
        trackerFile.parentFile?.mkdirs()
        trackerFile.writeText(trackers.trim())
        // 热加载到运行中的 aria2
        if (aria2.isRunning() && trackers.isNotBlank()) {
            aria2.changeBtTracker(trackers.trim())
        }
        AppLogger.i(TAG, "Tracker list saved (${trackers.split(",").count { it.trim().isNotBlank() }} entries)")
    }

    /** 获取当前缓存的 Tracker 列表 */
    fun getCachedTrackerList(): String? = trackerManager.getCachedTrackers()

    // ─── 智能性能控制 ──────────────────────────────────────

    private fun startSmartThrottle() {
        smartThrottleJob?.cancel()
        if (!config.smartThrottle) {
            throttleState = "disabled"
            return
        }
        smartThrottleJob = scope.launch {
            while (isActive) {
                try {
                    checkAndThrottle()
                } catch (_: CancellationException) { break } catch (e: Exception) {
                    AppLogger.w(TAG, "Smart throttle check error: ${e.message}")
                }
                delay(5000)  // 每 5 秒检查一次
            }
        }
    }

    private suspend fun checkAndThrottle() {
        // ── 采集传感器数据（优先复用 DataScheduler 缓存，减少 shell 调用开销）──
        val scheduler = dataScheduler

        // 温度: CpuInfo.temperature 来自 DataScheduler 的 CPU 采集（包含 thermal_zone 读数）
        val temp = scheduler?.latestCpu?.value?.temperature?.toFloat()
            ?: readMaxTemp()

        // CPU: CpuInfo.usage_percent 来自 DataScheduler 的 /proc/stat 差值计算
        val cpu = scheduler?.latestCpu?.value?.usage_percent?.toInt()
            ?: readCpuUsage()

        // 电量: latestBattery Map 包含 percent 和 is_charging
        val batteryMap = scheduler?.latestBattery?.value
        val batteryInfo = if (batteryMap != null && batteryMap.isNotEmpty()) {
            Pair(
                (batteryMap["percent"] as? Number)?.toInt() ?: -1,
                batteryMap["is_charging"] as? Boolean ?: false
            )
        } else {
            readBatteryInfo()
        }

        // 内存: MemoryInfo.usage_percent 来自 DataScheduler 的 /proc/meminfo 解析
        val memory = scheduler?.latestMemory?.value?.usage_percent?.toInt()
            ?: readMemoryUsage()

        throttleTemp = temp
        throttleCpu = cpu
        throttleBattery = batteryInfo.first
        throttleCharging = batteryInfo.second
        throttleMemory = memory

        if (!aria2.isRunning()) return

        val batteryLevel = batteryInfo.first
        val isCharging = batteryInfo.second

        // ── 仅充电时下载 ──
        if (config.onlyDownloadWhenCharging && !isCharging) {
            val activeNow = tasks.values.count { it.status == "downloading" }
            if (activeNow > 0) {
                aria2.forcePauseAll()
                tasks.values.filter { it.status == "downloading" }.forEach { it.status = "paused" }
                saveTasks()
                throttleWasStopped = true
                throttleState = "stopped"
                AppLogger.w(TAG, "仅充电时下载: 设备未充电，已暂停所有任务")
            }
            return
        }
        // 如果之前因未充电而停止，现在充电了 → 恢复
        if (config.onlyDownloadWhenCharging && isCharging && throttleWasStopped && throttleState == "stopped") {
            aria2.unpauseAll()
            tasks.values.filter { it.status == "paused" }.forEach { it.status = "pending" }
            saveTasks()
            throttleWasStopped = false
            AppLogger.i(TAG, "仅充电时下载: 设备已充电，已恢复所有任务")
            // 恢复后继续走下面的正常限速逻辑
        }

        // ── 无活跃任务时恢复 ──
        val activeTasks = tasks.values.count { it.status == "downloading" || it.status == "pending" }
        if (activeTasks == 0) {
            if (throttleWasStopped) {
                // 之前是 stopped 状态但现在无活跃任务，恢复正常标记
                throttleWasStopped = false
            }
            if (throttleState != "normal" && throttleState != "disabled" && throttleState != "stopped") {
                restoreOriginalSettings()
                throttleState = "normal"
            }
            return
        }

        // ── 计算各项指标等级 ──
        // 温度等级
        val tempLevel = when {
            temp >= config.throttleTempCritical + 10 -> 4  // 极端高温
            temp >= config.throttleTempCritical -> 3
            temp >= config.throttleTempWarn -> 2
            else -> 0
        }
        // CPU 等级
        val cpuLevel = when {
            cpu >= config.throttleCpuCritical + 10 -> 4
            cpu >= config.throttleCpuCritical -> 3
            cpu >= config.throttleCpuWarn -> 2
            else -> 0
        }
        // 电量等级（充电时不触发）
        val batteryLevel_ = when {
            !isCharging && batteryLevel in 1..5 -> 4   // 极低
            !isCharging && batteryLevel in 1..config.throttleBatteryCritical -> 3
            !isCharging && batteryLevel in 1..config.throttleBatteryWarn -> 2
            else -> 0
        }
        // 内存等级
        val memoryLevel = when {
            memory >= config.throttleMemoryCritical + 5 -> 4
            memory >= config.throttleMemoryCritical -> 3
            memory >= config.throttleMemoryWarn -> 2
            else -> 0
        }

        // 取最高等级作为最终等级
        val finalLevel = maxOf(tempLevel, cpuLevel, batteryLevel_, memoryLevel)

        // 收集触发原因
        val reasons = buildList {
            if (tempLevel >= 3) add("temp=${temp}°C≥${config.throttleTempCritical}")
            else if (tempLevel == 2) add("temp=${temp}°C≥${config.throttleTempWarn}")
            if (cpuLevel >= 3) add("cpu=${cpu}%≥${config.throttleCpuCritical}")
            else if (cpuLevel == 2) add("cpu=${cpu}%≥${config.throttleCpuWarn}")
            if (batteryLevel_ >= 3) add("battery=${batteryLevel}%≤${config.throttleBatteryCritical}")
            else if (batteryLevel_ == 2) add("battery=${batteryLevel}%≤${config.throttleBatteryWarn}")
            if (memoryLevel >= 3) add("mem=${memory}%≥${config.throttleMemoryCritical}")
            else if (memoryLevel == 2) add("mem=${memory}%≥${config.throttleMemoryWarn}")
        }

        when (finalLevel) {
            4 -> {
                // 极端: 暂停所有下载
                if (throttleState != "stopped") {
                    aria2.forcePauseAll()
                    tasks.values.filter { it.status == "downloading" }.forEach { it.status = "paused" }
                    saveTasks()
                    throttleWasStopped = true
                }
                throttleState = "stopped"
                AppLogger.w(TAG, "Smart throttle STOPPED: ${reasons.joinToString()} → 暂停所有下载")
            }
            3 -> {
                // 严重: 1 并发，20% 速度
                if (throttleWasStopped) {
                    aria2.unpauseAll()
                    throttleWasStopped = false
                }
                val limit = if (config.globalSpeedLimit > 0) (config.globalSpeedLimit * 20 / 100) else 256_000L
                aria2.changeGlobalSetting(
                    maxConcurrentDownloads = 1,
                    maxOverallDownloadLimit = limit
                )
                throttleState = "critical"
                AppLogger.w(TAG, "Smart throttle CRITICAL: ${reasons.joinToString()} → limit=${limit/1024}KB/s, 1 concurrent")
            }
            2 -> {
                // 警告: 一半并发，50% 速度
                if (throttleWasStopped) {
                    aria2.unpauseAll()
                    throttleWasStopped = false
                }
                val limit = if (config.globalSpeedLimit > 0) (config.globalSpeedLimit * 50 / 100) else 1_024_000L
                val concurrent = (config.maxConcurrent / 2).coerceAtLeast(1)
                aria2.changeGlobalSetting(
                    maxConcurrentDownloads = concurrent,
                    maxOverallDownloadLimit = limit
                )
                throttleState = "warning"
                AppLogger.w(TAG, "Smart throttle WARNING: ${reasons.joinToString()} → limit=${limit/1024}KB/s, $concurrent concurrent")
            }
            else -> {
                if (throttleWasStopped) {
                    aria2.unpauseAll()
                    throttleWasStopped = false
                }
                if (throttleState != "normal") {
                    restoreOriginalSettings()
                    AppLogger.i(TAG, "Smart throttle NORMAL: temp=${temp}°C cpu=${cpu}% battery=${batteryLevel}% mem=${memory}% → restored")
                }
                throttleState = "normal"
            }
        }
    }

    private fun restoreOriginalSettings() {
        aria2.changeGlobalSetting(
            maxConcurrentDownloads = config.maxConcurrent,
            maxOverallDownloadLimit = if (config.globalSpeedLimit > 0) config.globalSpeedLimit else null
        )
    }

    // ─── 传感器读取 ──────────────────────────────────────────

    /** 读取所有 thermal zone 取最大温度 */
    private suspend fun readMaxTemp(): Float {
        return try {
            var maxTemp = 0f
            // 优先用 Java File I/O（无需 fork 进程，更快）
            val thermalDir = File("/sys/class/thermal")
            if (thermalDir.exists()) {
                thermalDir.listFiles()?.filter { it.name.startsWith("thermal_zone") }?.forEach { zone ->
                    try {
                        val tempFile = File(zone, "temp")
                        if (tempFile.canRead()) {
                            val milli = tempFile.readText().trim().toLongOrNull() ?: 0L
                            val tempC = milli / 1000f
                            if (tempC > maxTemp) maxTemp = tempC
                        }
                    } catch (_: Exception) {}
                }
            }
            // 如果 Java I/O 全部失败，fallback 到 root shell
            if (maxTemp <= 0f) {
                val result = com.ufi_axis_core.util.ShellExecutor.executeAsRoot(
                    "for f in /sys/class/thermal/thermal_zone*/temp; do cat \$f 2>/dev/null; done"
                )
                if (result != null && result.isSuccess) {
                    result.stdout.lines().forEach { line ->
                        val milli = line.trim().toLongOrNull() ?: 0L
                        val tempC = milli / 1000f
                        if (tempC > maxTemp) maxTemp = tempC
                    }
                }
            }
            maxTemp
        } catch (_: Exception) { 0f }
    }

    /** 读取 CPU 使用率（单次 shell 调用，内部 sleep 0.2s） */
    private suspend fun readCpuUsage(): Int {
        return try {
            val result = com.ufi_axis_core.util.ShellExecutor.execute(
                "head -1 /proc/stat; sleep 0.2; head -1 /proc/stat"
            )
            if (result == null || !result.isSuccess) return 0
            val cpuLines = result.stdout.lines().filter { it.startsWith("cpu ") }
            if (cpuLines.size < 2) return 0
            val parse = { s: String ->
                s.substringAfter("cpu ").trim().split(" ").map { it.toLongOrNull() ?: 0L }
            }
            val v1 = parse(cpuLines[0])
            val v2 = parse(cpuLines[1])
            if (v1.size < 7 || v2.size < 7) return 0
            // 全部字段求和（user + nice + system + idle + iowait + irq + softirq + steal）
            val total = v1.indices.sumOf { i -> v2.getOrElse(i) { 0L } - v1.getOrElse(i) { 0L } }
            val idle = v2.getOrElse(3) { 0L } - v1.getOrElse(3) { 0L }
            if (total > 0) ((total - idle) * 100 / total).toInt().coerceIn(0, 100) else 0
        } catch (_: Exception) { 0 }
    }

    /** 读取电池电量和充电状态，返回 Pair(电量%, 是否充电) */
    private fun readBatteryInfo(): Pair<Int, Boolean> {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = appContext.registerReceiver(null, intentFilter)
            if (batteryStatus == null) {
                // fallback: 从 sysfs 读取
                val capacity = File("/sys/class/power_supply/battery/capacity").let {
                    if (it.canRead()) it.readText().trim().toIntOrNull() ?: -1 else -1
                }
                val status = File("/sys/class/power_supply/battery/status").let {
                    if (it.canRead()) it.readText().trim() else ""
                }
                val charging = status.equals("Charging", ignoreCase = true) ||
                               status.equals("Full", ignoreCase = true)
                return Pair(capacity, charging)
            }
            val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL
            Pair(percent, charging)
        } catch (_: Exception) {
            Pair(-1, false)
        }
    }

    /** 读取内存使用百分比 */
    private suspend fun readMemoryUsage(): Int {
        return try {
            // 优先 Java File I/O
            val memFile = File("/proc/meminfo")
            val content = if (memFile.canRead()) {
                memFile.readText()
            } else {
                com.ufi_axis_core.util.ShellExecutor.readSystemFile("/proc/meminfo") ?: return 0
            }
            val lines = content.lines()
            val getValue = { prefix: String ->
                lines.firstOrNull { it.startsWith(prefix) }
                    ?.substringAfter(":")
                    ?.trim()
                    ?.substringBefore(" ")
                    ?.toLongOrNull() ?: 0L
            }
            val total = getValue("MemTotal")
            val available = getValue("MemAvailable")
            if (total > 0) ((total - available) * 100 / total).toInt().coerceIn(0, 100) else 0
        } catch (_: Exception) { 0 }
    }

    // ─── 任务 API ──────────────────────────────────────────

    fun createTask(
        url: String,
        fileName: String? = null,
        savePath: String? = null,
        speedLimit: Long? = null,
        connections: Int? = null
    ): DownloadTask {
        val id = UUID.randomUUID().toString().take(8)
        val protocol = detectProtocol(url)
        val engine = "aria2"  // 统一走 aria2
        val dir = savePath ?: config.saveDir
        File(dir).mkdirs()

        val task = DownloadTask(
            id = id, url = url,
            fileName = fileName ?: "",
            savePath = dir,
            engine = engine, protocol = protocol
        )
        tasks[id] = task
        saveTasks()

        startAria2IfNeeded()
        submitAria2Download(task, speedLimit, connections)
        return task
    }

    fun getTask(id: String): DownloadTask? = tasks[id]
    fun getAllTasks(): List<DownloadTask> = tasks.values.sortedByDescending { it.createdAt }

    fun pauseTask(id: String): Boolean {
        val task = tasks[id] ?: return false
        return if (task.engine == "aria2") {
            task.gid?.let { aria2.pause(it) } ?: false
        } else {
            jobs[id]?.cancel(); jobs.remove(id)
            task.status = "paused"; saveTasks(); true
        }
    }

    fun resumeTask(id: String): Boolean {
        val task = tasks[id] ?: return false
        if (task.status != "paused" && task.status != "error") return false
        task.error = null
        return if (task.engine == "aria2") {
            task.gid?.let { aria2.unpause(it) } ?: run {
                startAria2IfNeeded(); submitAria2Download(task, null, null); true
            }
        } else {
            task.status = "pending"; saveTasks(); startJavaDownload(id); true
        }
    }

    fun deleteTask(id: String, deleteFile: Boolean = false): Boolean {
        val task = tasks[id] ?: return false
        if (task.engine == "aria2") {
            task.gid?.let { aria2.remove(it); if (deleteFile) aria2.removeDownloadResult(it) }
        } else {
            jobs[id]?.cancel(); jobs.remove(id)
        }
        tasks.remove(id)
        if (deleteFile && task.engine == "java") File(task.savePath).delete()
        saveTasks()
        return true
    }

    fun getActiveCount(): Int = tasks.values.count { it.status == "downloading" || it.status == "pending" }

    fun shutdown() {
        aria2PollJob?.cancel(); smartThrottleJob?.cancel()
        jobs.keys.forEach { jobs[it]?.cancel(); tasks[it]?.let { t -> if (t.status == "downloading") t.status = "paused" } }
        jobs.clear(); saveTasks(); aria2.stop(); trackerManager.stopAutoUpdate(); scope.cancel()
    }

    // ─── Java 引擎 ────────────────────────────────────────

    private fun startJavaDownload(id: String) {
        val task = tasks[id] ?: return
        jobs[id] = scope.launch {
            javaSemaphore.acquire()
            try {
                executeJavaDownload(task)
            } catch (_: CancellationException) {
                if (task.status == "downloading") task.status = "paused"; saveTasks()
            } catch (e: Exception) {
                task.status = "error"; task.error = e.message ?: e.javaClass.simpleName; saveTasks()
            } finally {
                javaSemaphore.release(); jobs.remove(id)
            }
        }
    }

    private suspend fun executeJavaDownload(task: DownloadTask) {
        task.status = "downloading"; saveTasks()
        val file = File(task.savePath)
        val existingBytes = if (file.exists()) file.length() else 0L
        val totalSize = withContext(Dispatchers.IO) { probeFileSize(task.url) }

        if (totalSize > 0 && existingBytes >= totalSize) {
            task.totalSize = totalSize; task.downloadedBytes = totalSize
            task.progress = 1f; task.status = "completed"
            task.completedAt = System.currentTimeMillis(); saveTasks(); return
        }

        val conn = withContext(Dispatchers.IO) {
            val c = URL(task.url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "UFI-AXIS-Download/1.0")
            if (existingBytes > 0L) c.setRequestProperty("Range", "bytes=$existingBytes-")
            c
        }
        try {
            withContext(Dispatchers.IO) { conn.connect() }
            val code = conn.responseCode
            val resumeFrom: Long; val responseTotal: Long
            if (code == 206) {
                resumeFrom = existingBytes
                responseTotal = conn.getHeaderField("Content-Range")?.substringAfterLast("/")?.toLongOrNull()
                    ?: (existingBytes + conn.contentLengthLong)
            } else if (code == 200) {
                resumeFrom = 0L; responseTotal = conn.contentLengthLong.takeIf { it > 0 } ?: totalSize
                if (existingBytes > 0L) file.delete()
            } else throw Exception("HTTP $code: ${conn.responseMessage}")

            task.totalSize = responseTotal; task.downloadedBytes = resumeFrom; task.connections = 1
            val startTime = System.currentTimeMillis(); val startBytes = resumeFrom
            val speedLimitBps = config.perTaskSpeedLimit

            conn.inputStream.use { ins ->
                FileOutputStream(file, resumeFrom > 0L).use { outs ->
                    val buf = ByteArray(BUFFER_SIZE); var lastUpdate = 0L
                    while (true) {
                        yield()
                        val n = ins.read(buf); if (n <= 0) break
                        outs.write(buf, 0, n); task.downloadedBytes += n
                        if (speedLimitBps > 0) {
                            val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                            val expected = (task.downloadedBytes - startBytes) * 1000 / speedLimitBps
                            if (elapsed < expected) delay(expected - elapsed)
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > PROGRESS_INTERVAL_MS) {
                            lastUpdate = now
                            task.progress = if (responseTotal > 0) (task.downloadedBytes.toFloat() / responseTotal).coerceIn(0f, 0.99f) else -1f
                            val el = (now - startTime) / 1000.0
                            if (el > 0) task.speed = ((task.downloadedBytes - startBytes) / el).toLong()
                            saveTasks()
                        }
                    }
                    outs.flush()
                }
            }
            task.progress = 1f; task.status = "completed"
            task.completedAt = System.currentTimeMillis(); task.speed = 0L; saveTasks()
        } finally { conn.disconnect() }
    }

    // ─── aria2 引擎 ───────────────────────────────────────

    private fun startAria2IfNeeded() {
        if (!aria2.isRunning()) {
            aria2.start(config)
            startAria2Polling()
        }
    }

    private fun submitAria2Download(task: DownloadTask, speedLimit: Long?, connections: Int?) {
        scope.launch {
            // 等待 aria2 RPC 就绪
            var waited = 0
            while (!aria2.isRunning() && waited < 18000) {
                delay(500); waited += 500
            }
            if (!aria2.isRunning()) {
                task.status = "error"; task.error = "aria2 引擎未就绪"; saveTasks(); return@launch
            }
            val gid = aria2.addUri(
                uris = listOf(task.url), dir = task.savePath,
                fileName = task.fileName.ifBlank { null },
                maxConnPerServer = connections ?: config.maxConnectionsPerServer,
                speedLimit = speedLimit ?: if (config.perTaskSpeedLimit > 0) config.perTaskSpeedLimit else null
            )
            if (gid != null) {
                task.gid = gid; task.status = "downloading"
                task.connections = connections ?: config.maxConnectionsPerServer; saveTasks()
            } else {
                task.status = "error"; task.error = "aria2 提交失败"; saveTasks()
            }
        }
    }

    private fun startAria2Polling() {
        aria2PollJob?.cancel()
        aria2PollJob = scope.launch {
            while (isActive) {
                try { syncAria2Status() } catch (_: CancellationException) { break } catch (_: Exception) {}
                delay(ARIA2_POLL_INTERVAL_MS)
            }
        }
    }

    private fun syncAria2Status() {
        val aria2Tasks = tasks.values.filter { it.engine == "aria2" }
        if (aria2Tasks.isEmpty()) return
        for (task in aria2Tasks) {
            val gid = task.gid ?: continue
            val s = aria2.tellStatus(gid) ?: continue
            val totalLen = s["totalLength"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1L
            val completedLen = s["completedLength"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            task.totalSize = totalLen; task.downloadedBytes = completedLen
            task.speed = s["downloadSpeed"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            task.uploadSpeed = s["uploadSpeed"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            task.seeders = s["numSeeders"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            task.connections = s["connections"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            task.progress = if (totalLen > 0) (completedLen.toFloat() / totalLen).coerceIn(0f, 1f) else 0f

            // 更新文件名和路径（aria2 提供完整路径）
            val files = s["files"]?.jsonArray
            if (!files.isNullOrEmpty()) {
                val fullPath = files[0].jsonObject["path"]?.jsonPrimitive?.contentOrNull
                if (fullPath != null && fullPath.isNotBlank()) {
                    val actualFileName = fullPath.substringAfterLast("/")
                    if (task.fileName.isBlank()) {
                        task.fileName = actualFileName
                    }
                    // 更新 savePath 为完整文件路径（目录/文件名）
                    if (!fullPath.startsWith("magnet") && actualFileName.isNotBlank()) {
                        task.savePath = fullPath
                    }
                }
            }
            // 如果 aria2 返回了 dir 字段，用它来修正路径
            val aria2Dir = s["dir"]?.jsonPrimitive?.contentOrNull
            if (aria2Dir != null && task.fileName.isNotBlank() && !task.savePath.contains(task.fileName)) {
                task.savePath = "${aria2Dir.trimEnd('/')}/${task.fileName}"
            }

            task.status = when (s["status"]?.jsonPrimitive?.contentOrNull) {
                "active" -> "downloading"; "paused" -> "paused"; "waiting" -> "pending"
                "complete" -> { task.completedAt = System.currentTimeMillis(); "completed" }
                "error" -> { task.error = s["errorMessage"]?.jsonPrimitive?.contentOrNull ?: "aria2 错误"; "error" }
                "removed" -> "error"; else -> task.status
            }
        }
        saveTasks()
    }

    // ─── 工具 ──────────────────────────────────────────────

    private fun detectProtocol(url: String): String {
        val l = url.lowercase()
        return when {
            l.startsWith("magnet:") -> "magnet"
            l.endsWith(".torrent") -> "torrent"
            l.startsWith("ftp://") -> "ftp"
            l.startsWith("sftp://") -> "sftp"
            l.endsWith(".metalink") -> "metalink"
            l.startsWith("https://") -> "https"
            else -> "http"
        }
    }

    private fun probeFileSize(url: String): Long {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "HEAD"; c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.connect(); val len = c.contentLengthLong; c.disconnect()
            if (len > 0) len else -1L
        } catch (_: Exception) { -1L }
    }

    private fun extractFileName(url: String): String {
        try {
            val name = URL(url).path.substringAfterLast("/").takeIf { it.isNotBlank() && it.contains(".") }
            if (name != null) return java.net.URLDecoder.decode(name, "UTF-8")
        } catch (_: Exception) {}
        return "download_${System.currentTimeMillis()}"
    }

    // ─── 持久化 ────────────────────────────────────────────

    private fun saveTasks() { try { persistFile.writeText(json.encodeToString(tasks.values.toList())) } catch (_: Exception) {} }
    private fun loadTasks() { try { if (persistFile.exists()) { val t = persistFile.readText(); if (t.isNotBlank()) json.decodeFromString<List<DownloadTask>>(t).forEach { tasks[it.id] = it } } } catch (_: Exception) {} }
    private fun saveConfig() { try { configFile.writeText(json.encodeToString(config)) } catch (_: Exception) {} }
    private fun loadConfig() { try { if (configFile.exists()) { val t = configFile.readText(); if (t.isNotBlank()) config = json.decodeFromString<DownloadConfig>(t) } } catch (_: Exception) {} }
}
