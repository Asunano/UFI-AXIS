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
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
        // aria2c 子进程写入的私有工作目录（避免 scoped storage 限制）
        const val DEFAULT_SAVE_DIR = "/storage/emulated/0/Android/data/com.ufi_axis_core/files/Downloads"
        // 下载完成后自动转移到的公共目录（用户可见）
        const val PUBLIC_DOWNLOAD_DIR = "/storage/emulated/0/Downloads/UFI"
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
        val engine: String = "aria2",
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
        var saveDir: String = PUBLIC_DOWNLOAD_DIR,
        var splitCount: Int = 4,
        var minSplitSizeMb: Int = 1,
        var maxOverallUploadLimit: Long = 0,
        var fileAllocation: String = "none",
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

    // 保底链 root 检测缓存（必须在 init 块之前初始化）
    private val rootChecked = AtomicBoolean(false)
    private val rootAvailable = AtomicBoolean(false)

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
        // 关键：调用 getExternalFilesDir 让 Android 系统创建 /Android/data/<pkg>/files/ 父目录链
        // 否则 aria2c 子进程无法 mkdir DEFAULT_SAVE_DIR (Permission denied)
        appContext.getExternalFilesDir(null)
        File(DEFAULT_SAVE_DIR).mkdirs()
        smartMkdir(config.saveDir)
        // 启动时恢复孤儿文件：将私有目录中已完成但未转移的文件移至公共目录
        recoverOrphanedDownloads()
        loadTasks()
        // 将之前下载中的任务标记为暂停
        tasks.values.filter { it.status == "downloading" }.forEach { it.status = "paused" }
        saveTasks()

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

        // 如果有未完成的 aria2 任务，自动启动引擎（RPC 就绪后启动轮询）
        val hasPendingAria2 = tasks.values.any {
            it.engine == "aria2" && (it.status == "paused" || it.status == "pending")
        }
        if (hasPendingAria2) {
            aria2.start(config)
            scope.launch {
                var waited = 0L
                while (!aria2.isRunning() && waited < 15_000) {
                    delay(500); waited += 500
                }
                if (aria2.isRunning()) {
                    startAria2Polling()
                } else {
                    AppLogger.e(TAG, "aria2 init: engine failed to become ready after ${waited}ms")
                }
            }
        }

        // 启动智能性能监控
        startSmartThrottle()
    }

    // ─── 配置 ──────────────────────────────────────────────

    fun updateConfig(newConfig: DownloadConfig) {
        config = newConfig
        saveConfig()

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

    // ─── 最原始下载测试（命令行直调，不用 conf/RPC）─────────

    /**
     * 用最原始的 aria2c CLI 模式测试下载。
     * 先停止当前 RPC 引擎，直接调 `aria2c <url> --check-certificate=false ...`
     * 捕获所有 stdout/stderr 到 AppLogger。
     *
     * @param url 下载链接
     * @return RawTestResult
     */
    data class RawTestResult(
        val success: Boolean,
        val exitCode: Int,
        val elapsedMs: Long,
        val logSummary: String,
        val downloadedFile: String?
    )

    fun rawTestDownload(url: String): RawTestResult {
        AppLogger.i(TAG, "━━━ Raw Test Download START ━━━")
        val result = aria2.rawDownload(url)
        AppLogger.i(TAG, "━━━ Raw Test Download END: success=${result.success} exit=${result.exitCode} time=${result.elapsedMs}ms file=${result.downloadedFile} ━━━")
        return RawTestResult(
            success = result.success,
            exitCode = result.exitCode,
            elapsedMs = result.elapsedMs,
            logSummary = "exit=${result.exitCode}, stdout=${result.stdout}, stderr=${result.stderr}",
            downloadedFile = result.downloadedFile
        )
    }

    // ─── 任务 API ──────────────────────────────────────────

    /**
     * 按 URL 查找重复任务（排除 error 状态，允许重试）
     * @return 存在且状态非 error 的旧任务
     */
    fun findDuplicateByUrl(url: String): DownloadTask? {
        return tasks.values.firstOrNull {
            it.url == url && it.status != "error" && it.status != "removed"
        }
    }

    /**
     * 查找同名任务（fileName 完全匹配且非 error 状态）
     */
    fun findTasksByName(fileName: String): List<DownloadTask> {
        if (fileName.isBlank()) return emptyList()
        return tasks.values.filter {
            it.fileName == fileName && it.status != "error"
        }
    }

    /**
     * 生成不重复的文件名，追加 (1)/(2)… 后缀
     */
    fun generateUniqueFileName(baseName: String): String {
        if (baseName.isBlank()) return baseName
        val dotIndex = baseName.lastIndexOf('.')
        val name = if (dotIndex > 0) baseName.substring(0, dotIndex) else baseName
        val ext = if (dotIndex > 0) baseName.substring(dotIndex) else ""
        var counter = 1
        var newName = "${name}($counter)$ext"
        while (tasks.values.any { it.fileName == newName }) {
            counter++
            newName = "${name}($counter)$ext"
        }
        return newName
    }

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
        // savePath 始终指向公共目录（用户期望的最终位置）
        val finalSavePath = savePath ?: config.saveDir
        // 区分目录路径 vs 文件路径，避免把文件名当成目录创建
        // 使用 smartMkdir 保底链（root → File.mkdirs）
        if (isFilePath(finalSavePath)) {
            File(finalSavePath).parentFile?.let { smartMkdir(it.absolutePath) }
        } else {
            smartMkdir(finalSavePath)
        }
        // 确保私有工作目录存在（aria2c 实际下载位置）
        File(DEFAULT_SAVE_DIR).mkdirs()

        val task = DownloadTask(
            id = id, url = url,
            fileName = fileName ?: "",
            savePath = finalSavePath,
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
        return task.gid?.let { aria2.pause(it) } ?: false
    }

    fun resumeTask(id: String): Boolean {
        val task = tasks[id] ?: return false
        if (task.status != "paused" && task.status != "error") return false
        task.error = null
        return task.gid?.let { aria2.unpause(it) } ?: run {
            startAria2IfNeeded(); submitAria2Download(task, null, null); true
        }
    }

    fun deleteTask(id: String, deleteFile: Boolean = false): Boolean {
        val task = tasks[id] ?: return false
        task.gid?.let { aria2.remove(it); if (deleteFile) aria2.removeDownloadResult(it) }

        // 删除任务记录时，若要求同时删除下载文件
        if (deleteFile) {
            deleteDownloadedFiles(task)
        }

        tasks.remove(id)
        saveTasks()
        return true
    }

    /**
     * 删除任务对应的实际下载文件。
     * 按优先级依次尝试：
     * ① task.savePath（任务记录的目标路径）
     * ② DEFAULT_SAVE_DIR/fileName（aria2 私有工作目录）
     * ③ config.saveDir/fileName（用户配置的保存目录）
     * ④ PUBLIC_DOWNLOAD_DIR/fileName（默认公共目录）
     * ⑤ shell find 在 /storage/emulated/0 下按文件名搜索（文件被移动后的保底）
     */
    /**
     * 同步删除下载文件（使用 runBlocking 确保一次只运行一个 find 进程，
     * 避免批量删除时并发 find 扫描全盘导致 CPU 100% 过热关机）
     */
    private fun deleteDownloadedFiles(task: DownloadTask) {
        if (task.fileName.isBlank()) return

        // Java File API 受进程 UID 限制，所有操作通过 shell 执行
        runBlocking(Dispatchers.IO) {
            val searchPaths = mutableSetOf(
                task.savePath,
                "${DEFAULT_SAVE_DIR}/${task.fileName}",
                "${config.saveDir}/${task.fileName}",
                "${PUBLIC_DOWNLOAD_DIR}/${task.fileName}"
            )

            var found = false

            for (path in searchPaths) {
                val existsResult = com.ufi_axis_core.util.ShellExecutor.execute(
                    "test -e \"$path\" && echo yes"
                )
                val exists = existsResult.isSuccess && existsResult.stdout.trim() == "yes"
                if (exists) {
                    val isDirResult = com.ufi_axis_core.util.ShellExecutor.execute(
                        "test -d \"$path\" && echo yes"
                    )
                    val isDir = isDirResult.isSuccess && isDirResult.stdout.trim() == "yes"
                    val rmCmd = if (isDir) "rm -rf \"$path\"" else "rm -f \"$path\""
                    val deleteResult = com.ufi_axis_core.util.ShellExecutor.executeAsRoot(rmCmd)
                    if (deleteResult.isSuccess) {
                        AppLogger.i(TAG, "Deleted file (shell): $path")
                        found = true
                    } else {
                        AppLogger.w(TAG, "Failed to delete file (shell): $path")
                    }
                }
            }

            // 清理 aria2 控制文件 (.aria2)
            val controlPath = "${DEFAULT_SAVE_DIR}/${task.fileName}.aria2"
            com.ufi_axis_core.util.ShellExecutor.executeAsRoot("rm -f \"$controlPath\"")

            // shell find 兜底：文件被移动到未知路径时，按文件名在用户存储中搜索
            if (!found && !task.fileName.contains("..") && task.totalSize > 0) {
                try {
                    val findResult = com.ufi_axis_core.util.ShellExecutor.execute(
                        "find /storage/emulated/0 -maxdepth 5 -name \"${task.fileName}\" -type f 2>/dev/null | head -5"
                    )
                    if (findResult.isSuccess && findResult.stdout.isNotBlank()) {
                        val foundPaths = findResult.stdout.lines().filter { it.isNotBlank() }
                        for (fp in foundPaths) {
                            val sizeResult = com.ufi_axis_core.util.ShellExecutor.execute(
                                "stat -L -c %s \"$fp\" 2>/dev/null"
                            )
                            val fileSize = sizeResult.stdout.trim().toLongOrNull() ?: -1L
                            if (fileSize != task.totalSize) {
                                AppLogger.w(TAG, "Moved file size mismatch, skipping: $fp (expected ${task.totalSize}, got $fileSize)")
                                continue
                            }
                            val deleteResult = com.ufi_axis_core.util.ShellExecutor.executeAsRoot(
                                "rm -rf \"$fp\""
                            )
                            if (deleteResult.isSuccess) {
                                AppLogger.i(TAG, "Deleted moved file (shell find, size verified): $fp")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            if (!found) {
                if (task.totalSize > 0) {
                    AppLogger.w(TAG, "deleteDownloadedFiles: file '${task.fileName}' not found at any known location")
                } else {
                    AppLogger.d(TAG, "deleteDownloadedFiles: unknown file size, skip shell find for '${task.fileName}'")
                }
            }
        }
    }

    fun getActiveCount(): Int = tasks.values.count { it.status in listOf("downloading", "pending", "meta") }

    /** 重新下载：删除旧任务 → 用相同 URL 重新提交 */
    fun retryTask(id: String): Boolean {
        val old = tasks[id] ?: return false
        val url = old.url
        val fileName = old.fileName
        val savePath = old.savePath
        val speedLimit = if (config.perTaskSpeedLimit > 0) config.perTaskSpeedLimit else null
        // 先删除旧任务
        deleteTask(id, deleteFile = false)
        // 用原始参数重建
        val task = createTask(
            url = url,
            fileName = if (fileName.isNotBlank()) fileName else null,
            savePath = if (savePath.isNotBlank()) savePath else null,
            speedLimit = speedLimit,
            connections = config.maxConnectionsPerServer
        )
        return task.id.isNotBlank()
    }

    fun shutdown() {
        aria2PollJob?.cancel(); smartThrottleJob?.cancel()
        tasks.values.filter { it.status == "downloading" }.forEach { it.status = "paused" }
        saveTasks(); aria2.stop(); trackerManager.stopAutoUpdate(); scope.cancel()
    }

    // ─── aria2 引擎 ───────────────────────────────────────

    private fun startAria2IfNeeded() {
        if (!aria2.isRunning()) {
            AppLogger.i(TAG, "Starting aria2 engine...")
            aria2.start(config)
            // 延迟启动轮询：等 RPC 就绪后再开始同步任务状态
            scope.launch {
                var waited = 0L
                while (!aria2.isRunning() && waited < 15_000) {
                    delay(500); waited += 500
                }
                if (aria2.isRunning()) {
                    startAria2Polling()
                } else {
                    AppLogger.e(TAG, "aria2 engine failed to become ready after ${waited}ms")
                }
            }
        }
    }

    private var rawTestOnce = false // 只运行一次原始 CLI 诊断测试

    private fun submitAria2Download(task: DownloadTask, speedLimit: Long?, connections: Int?) {
        scope.launch {
            // ── 首次下载诊断测试（fire-and-forget，不阻塞实际下载）──
            if (!rawTestOnce) {
                rawTestOnce = true
                scope.launch {
                    AppLogger.i(TAG, "━━━ Auto raw-test (background) ━━━")
                    aria2.rawDownload(url = task.url, saveDir = DEFAULT_SAVE_DIR,
                        fileName = null, timeoutSec = 10, killLingering = false)
                    AppLogger.i(TAG, "━━━ Auto raw-test completed ━━━")
                }
            }

            // 等待 aria2 RPC 就绪
            var waited = 0
            val aria2Ver = aria2.cachedVersion
            while (!aria2.isRunning() && waited < 12000) {
                delay(500); waited += 500
            }
            if (!aria2.isRunning()) {
                val diag = buildString {
                    append("aria2 引擎未就绪")
                    if (aria2Ver == null) append("（二进制可能不兼容，版本探测失败）")
                    else append("（v$aria2Ver 启动超时，请检查日志）")
                }
                task.status = "error"; task.error = diag; saveTasks(); return@launch
            }
            // aria2c 子进程只能写私有目录（scoped storage 限制），
            // 完成后 transferToPublicDir 会拷贝到 task.savePath 公共目录
            val gid = aria2.addUri(
                uris = listOf(task.url), dir = DEFAULT_SAVE_DIR,
                fileName = task.fileName.ifBlank { null },
                maxConnPerServer = connections ?: config.maxConnectionsPerServer,
                speedLimit = speedLimit ?: if (config.perTaskSpeedLimit > 0) config.perTaskSpeedLimit else null
            )
            if (gid != null) {
                task.gid = gid; task.status = "downloading"
                task.connections = connections ?: config.maxConnectionsPerServer; saveTasks()
            } else {
                task.status = "error"
                task.error = when {
                    task.protocol == "https" && config.checkCertificate -> "aria2 提交失败（HTTPS证书校验可能不通过，尝试关闭\"校验证书\"）"
                    task.protocol == "https" -> "aria2 提交失败（可能是TLS/证书问题，查看aria2日志）"
                    else -> "aria2 提交失败（查看设备日志排查）"
                }
                saveTasks()
            }
        }
    }

    private fun startAria2Polling() {
        aria2PollJob?.cancel()
        aria2PollJob = scope.launch {
            while (isActive) {
                try {
                    syncAria2Status()
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    AppLogger.w(TAG, "aria2 poll error (retry in ${ARIA2_POLL_INTERVAL_MS}ms): ${e.message}")
                }
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
            // 进度计算：区分 metadata 获取阶段和真正的下载阶段
            task.progress = if (totalLen > 0) {
                (completedLen.toFloat() / totalLen).coerceIn(0f, 1f)
            } else {
                // totalLength=0 且无 connection → metadata 获取阶段，进度未知
                -1f
            }

            // 更新文件名（aria2 提供文件名，但不覆盖公共目标路径）
            val files = s["files"]?.jsonArray
            if (!files.isNullOrEmpty()) {
                val fullPath = files[0].jsonObject["path"]?.jsonPrimitive?.contentOrNull
                if (fullPath != null && fullPath.isNotBlank()) {
                    val actualFileName = fullPath.substringAfterLast("/")
                    if (task.fileName.isBlank()) {
                        task.fileName = actualFileName
                        // 文件名首次获取时更新 savePath（保持指向公共目录）
                        if (task.savePath.startsWith(DEFAULT_SAVE_DIR) || task.savePath.isBlank()) {
                            task.savePath = PUBLIC_DOWNLOAD_DIR
                        }
                    }
                }
            }
            // 如果 aria2 返回了 dir 字段且 fileName 不在当前 savePath 中（首次轮询），
            // 但只在 savePath 尚未指向公共目录时修正
            val aria2Dir = s["dir"]?.jsonPrimitive?.contentOrNull
            if (aria2Dir != null && task.fileName.isNotBlank() &&
                task.savePath.isBlank()) {
                task.savePath = "${PUBLIC_DOWNLOAD_DIR}/${task.fileName}"
            }

            val newStatus = when (s["status"]?.jsonPrimitive?.contentOrNull) {
                "active" -> {
                    // metadata-fetching phase: totalLength=0, BT protocol, no data yet
                    if (totalLen <= 0 && task.protocol in listOf("magnet", "torrent")) "meta"
                    else "downloading"
                }
                "paused" -> "paused"; "waiting" -> "pending"
                "complete" -> {
                    // 如果之前已是 completed，不再重复触发 transferToPublicDir
                    if (task.status != "completed") {
                        task.completedAt = System.currentTimeMillis()
                        // aria2 始终下载到私有目录 DEFAULT_SAVE_DIR，
                        // 完成后必须转移到 task.savePath 公共目标
                        scope.launch { transferToPublicDir(task) }
                    }
                    "completed"
                }
                "error" -> { task.error = s["errorMessage"]?.jsonPrimitive?.contentOrNull ?: "aria2 错误"; "error" }
                "removed" -> "error"; else -> task.status
            }
            task.status = newStatus
        }
        saveTasks()
    }

    // ─── 文件转移（私有目录 → 公共下载目录）──────────────

    /**
     * 启动时恢复孤儿文件：应用崩溃/重启后，私有目录中已下载完成但未转移到公共目录的文件，
     * 在此补转移。使用三层保底链，无 root 设备也能尽力恢复。
     */
    private fun recoverOrphanedDownloads() {
        val privateDir = File(DEFAULT_SAVE_DIR)
        if (!privateDir.exists() || !privateDir.isDirectory) return
        val items = privateDir.listFiles()?.filter {
            !it.name.endsWith(".aria2")  // 跳过 aria2 控制文件 (.aria2)
        } ?: return
        if (items.isEmpty()) return

        val pubDir = File(config.saveDir)
        smartMkdir(pubDir.absolutePath)
        var recovered = 0

        for (item in items) {
            val target = File(pubDir, item.name)
            if (item.isDirectory) {
                // 磁链/BT 输出目录
                if (smartCopyDir(item, target)) {
                    val fileCount = target.walkTopDown().count { it.isFile }
                    AppLogger.i(TAG, "recover dir: ~$fileCount files '${item.name}/' → ${target.absolutePath}/")
                    recovered += fileCount
                } else {
                    AppLogger.w(TAG, "recover dir ALL failed: ${item.name} (will stay in private dir)")
                }
            } else {
                if (item.length() <= 0L) continue
                if (target.exists() && target.length() == item.length()) {
                    AppLogger.d(TAG, "recover skip (already exists): ${item.name}")
                    continue
                }
                if (smartCopyFile(item, target)) {
                    AppLogger.i(TAG, "recover file: ${item.length()} bytes ${item.name} → ${target.absolutePath}")
                    recovered++
                } else {
                    AppLogger.w(TAG, "recover file ALL failed: ${item.name} (will stay in private dir)")
                }
            }
        }
        if (recovered > 0) {
            AppLogger.i(TAG, "recoverOrphanedDownloads: completed, $recovered items transferred")
        }
    }

    /**
     * 下载完成后，将文件/目录从私有工作目录拷贝到公共 Download 目录。
     * aria2c 子进程只能写入私有目录，而公共目录受 Android Scoped Storage 限制。
     *
     * 保底链：root cp → MediaStore → 直接拷贝 → 私有目录兜底
     *
     * 支持两种场景：
     * - 普通 HTTP 下载 → 单文件拷贝
     * - 磁链/BT 下载 → 递归目录拷贝（aria2 产出的是文件夹）
     */
    private fun transferToPublicDir(task: DownloadTask): DownloadTask {
        val targetName = task.fileName.ifBlank { 
            File(task.savePath).name.ifBlank { return task }
        }
        // aria2c 下载到私有工作目录（可能是文件或目录）
        val privateSource = File(DEFAULT_SAVE_DIR, targetName)
        if (!privateSource.exists()) {
            AppLogger.w(TAG, "transferToPublicDir: source not found: ${privateSource.absolutePath}")
            return task
        }

        val isDir = privateSource.isDirectory
        if (!isDir && privateSource.length() <= 0L) {
            AppLogger.w(TAG, "transferToPublicDir: file is empty: ${privateSource.absolutePath}")
            return task
        }

        // 确定公共目标路径
        val savePathFile = File(task.savePath)
        val publicTarget: File = when {
            savePathFile.isDirectory || !isFilePath(task.savePath) -> {
                smartMkdir(savePathFile.absolutePath)
                File(savePathFile, targetName)
            }
            else -> {
                savePathFile.parentFile?.let { smartMkdir(it.absolutePath) }
                savePathFile
            }
        }

        // 如果公共目录已有相同大小的文件，跳过拷贝
        if (!isDir && publicTarget.exists() && publicTarget.length() == privateSource.length()) {
            AppLogger.i(TAG, "transferToPublicDir: already exists (same size), skip: ${publicTarget.absolutePath}")
            task.savePath = publicTarget.absolutePath
            saveTasks()
            return task
        }

        val success = if (isDir) smartCopyDir(privateSource, publicTarget)
                      else smartCopyFile(privateSource, publicTarget)

        if (success) {
            task.savePath = publicTarget.absolutePath
            AppLogger.i(TAG, "transferToPublicDir: OK '${targetName}' → ${publicTarget.absolutePath}")
        } else {
            // ── 终极保底：所有方式均失败，回退到私有目录 ──
            AppLogger.w(TAG, "transferToPublicDir: ALL methods failed for '$targetName', keeping in private dir")
            task.savePath = privateSource.absolutePath
        }
        saveTasks()
        return task
    }

    // ─── 文件拷贝三层保底机制 ─────────────────────────────────
    // ① root shell cp（最快、最可靠）→ ② MediaStore API → ③ 直接拷贝 → ④ 私有目录兜底

    /** 检测 root 是否可用（只测一次，缓存结果） */
    private fun isRootAvailable(): Boolean {
        if (rootChecked.get()) return rootAvailable.get()
        synchronized(rootChecked) {
            if (rootChecked.get()) return rootAvailable.get()
            rootChecked.set(true)
            val ok = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo ok"))
                p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
            } catch (_: Exception) { false }
            rootAvailable.set(ok)
            AppLogger.i(TAG, "Root available: $ok")
            return ok
        }
    }

    /** 通过 root shell cp 拷贝单个文件 */
    private fun rootCp(src: String, dst: String): Boolean {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cp '$src' '$dst'"))
            p.waitFor(60, TimeUnit.SECONDS)
            return p.exitValue() == 0
        } catch (e: Exception) {
            AppLogger.w(TAG, "rootCp: ${e.message}")
            return false
        }
    }

    /** root cp -r 递归拷贝目录 */
    private fun rootCpRecursive(srcDir: String, dstDir: String): Boolean {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cp -r '$srcDir' '$dstDir'"))
            p.waitFor(120, TimeUnit.SECONDS)
            return p.exitValue() == 0
        } catch (e: Exception) {
            AppLogger.w(TAG, "rootCpRecursive: ${e.message}")
            return false
        }
    }

    /** root mkdir -p 创建目录（含父目录） */
    private fun rootMkdir(path: String): Boolean {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "mkdir -p '$path'"))
            p.waitFor(5, TimeUnit.SECONDS)
            return p.exitValue() == 0
        } catch (e: Exception) {
            AppLogger.w(TAG, "rootMkdir: ${e.message}")
            return false
        }
    }

    /**
     * 通过 MediaStore API 写入公共 Downloads 目录。
     * 这是 Android 官方推荐的无权限公共目录写入方式，适配无 root 设备。
     */
    private fun writeViaMediaStore(src: File, dstFileName: String): Boolean {
        try {
            val mime = when {
                dstFileName.endsWith(".apk") -> "application/vnd.android.package-archive"
                dstFileName.endsWith(".zip") -> "application/zip"
                dstFileName.endsWith(".torrent") -> "application/x-bittorrent"
                dstFileName.endsWith(".mp4") -> "video/mp4"
                dstFileName.endsWith(".mp3") -> "audio/mpeg"
                dstFileName.endsWith(".pdf") -> "application/pdf"
                dstFileName.endsWith(".jpg", true) || dstFileName.endsWith(".jpeg", true) -> "image/jpeg"
                dstFileName.endsWith(".png", true) -> "image/png"
                else -> "application/octet-stream"
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, dstFileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/UFI/")
            }
            val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            appContext.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { inp -> inp.copyTo(out) }
            } ?: return false
            AppLogger.i(TAG, "MediaStore: wrote ${src.length()} bytes → Downloads/UFI/$dstFileName")
            return true
        } catch (e: Exception) {
            AppLogger.w(TAG, "MediaStore write failed for '$dstFileName': ${e.message}")
            return false
        }
    }

    /**
     * 统一的文件拷贝保底链（单文件）。
     * @return false 表示所有方式均失败，调用方应回退到私有目录
     */
    private fun smartCopyFile(src: File, dst: File): Boolean {
        // ① root shell
        if (isRootAvailable() && rootCp(src.absolutePath, dst.absolutePath)) {
            AppLogger.d(TAG, "smartCopyFile: root cp OK '${src.name}'")
            return true
        }
        // ② MediaStore
        if (writeViaMediaStore(src, dst.name)) {
            return true
        }
        // ③ 直接拷贝（MANAGE_EXTERNAL_STORAGE 已授予时可能成功）
        try {
            src.copyTo(dst, overwrite = true)
            AppLogger.d(TAG, "smartCopyFile: direct copy OK '${src.name}'")
            return true
        } catch (_: Exception) {}
        return false
    }

    /**
     * 统一的目录拷贝保底链（磁链/BT）。
     * MediaStore 不支持目录，所以只有 ①→③ 两级。
     */
    private fun smartCopyDir(srcDir: File, dstDir: File): Boolean {
        // ① root shell
        if (isRootAvailable() && rootCpRecursive(srcDir.absolutePath, dstDir.absolutePath)) {
            AppLogger.d(TAG, "smartCopyDir: root cp -r OK '${srcDir.name}/'")
            return true
        }
        // ② 直接递归拷贝
        try {
            srcDir.copyRecursively(dstDir, overwrite = true)
            AppLogger.d(TAG, "smartCopyDir: direct copyRecursively OK '${srcDir.name}/'")
            return true
        } catch (_: Exception) {}
        return false
    }

    /**
     * 统一的目录创建保底链。
     */
    private fun smartMkdir(path: String): Boolean {
        if (isRootAvailable() && rootMkdir(path)) return true
        return try { File(path).mkdirs() } catch (_: Exception) { false }
    }

    // ─── 工具 ──────────────────────────────────────────────

    /** 判断路径是否像文件路径（最后一段包含扩展名），而非目录 */
    private fun isFilePath(path: String): Boolean {
        val lastPart = path.substringAfterLast("/")
        return lastPart.contains(".")
    }

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

    private fun extractFileName(url: String): String {
        try {
            val name = URL(url).path.substringAfterLast("/").takeIf { it.isNotBlank() && it.contains(".") }
            if (name != null) return java.net.URLDecoder.decode(name, "UTF-8")
        } catch (_: Exception) {}
        return "download_${System.currentTimeMillis()}"
    }

    // ─── 持久化 ────────────────────────────────────────────

    private fun saveTasks() {
        try { persistFile.writeText(json.encodeToString(tasks.values.toList())) }
        catch (e: Exception) { AppLogger.e(TAG, "saveTasks failed: ${e.javaClass.simpleName}: ${e.message}", e) }
    }
    private fun loadTasks() { try { if (persistFile.exists()) { val t = persistFile.readText(); if (t.isNotBlank()) json.decodeFromString<List<DownloadTask>>(t).forEach { tasks[it.id] = it } } } catch (_: Exception) {} }
    private fun saveConfig() { try { configFile.writeText(json.encodeToString(config)) } catch (_: Exception) {} }
    private fun loadConfig() { try { if (configFile.exists()) { val t = configFile.readText(); if (t.isNotBlank()) config = json.decodeFromString<DownloadConfig>(t) } } catch (_: Exception) {} }
}
