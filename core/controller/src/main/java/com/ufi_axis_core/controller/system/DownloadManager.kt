// TODO(F25): God-class 计划内拆分（DownloadManager 1094 / GoformClient 615 / Aria2Engine 577 / BackendService 570 / NetworkModule 921 / FileManagerScreen 879）。本类仅做最小安全抽取（见 GoformCodec），全量拆分需人工评审 + 编译验证。
package com.ufi_axis_core.controller.system

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.notify.NotifyEvent
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.NotifyScenes
import com.ufi_axis_core.notify.Notifier
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
import android.provider.MediaStore
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 无 root 的 aria2 下载管理器
 *
 * 所有任务统一由 aria2c 子进程（JSON-RPC）驱动，支持
 * HTTP/HTTPS/FTP/magnet/torrent/metalink（多连接、P2P）。
 * 文件读写、传感器采集均走 Java File I/O / 系统 API，不依赖 root。
 */
class DownloadManager(
    private val appContext: android.content.Context
) {
    companion object {
        private const val TAG = "DownloadManager"
        private const val BUFFER_SIZE = 8192
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val ARIA2_POLL_INTERVAL_MS = 1500L
        // GID 在 aria2 里查不到后，等这么久再尝试认领/重提交（避开引擎刚启动、session 未加载的窗口）
        private const val LOST_GID_GRACE_MS = 10_000L
        // aria2 里"还活着"的下载状态：只有这些 GID 值得认领，
        // complete/error/removed 属于结果列表里的死条目（占着 infoHash，但认领了没用）
        private val LIVE_ARIA2_STATUSES = setOf("active", "waiting", "paused")
        // aria2c 子进程写入的私有工作目录（避免 scoped storage 限制）
        const val DEFAULT_SAVE_DIR = "/storage/emulated/0/Android/data/com.ufi_axis_core/files/Downloads"
        // 下载完成后自动转移到的公共目录（用户可见）。
        // 一级目录必须是 Android 标准的 "Download"（单数）—— MediaStore 的
        // RELATIVE_PATH 只认这个名字；二级收敛到 UFI-AXIS/ 下与日志、更新包同一品牌根目录。
        const val PUBLIC_DOWNLOAD_DIR = "/storage/emulated/0/Download/UFI-AXIS/Download"
        // 旧默认值，用于把已有配置迁移到上面的标准路径
        private val LEGACY_PUBLIC_DOWNLOAD_DIRS = listOf(
            "/storage/emulated/0/Downloads/UFI",
            "/storage/emulated/0/Download/UFI"
        )
        // 外置存储根，MediaStore RELATIVE_PATH 需要相对它计算
        private const val EXTERNAL_ROOT = "/storage/emulated/0"
    }

    @Serializable
    data class DownloadTask(
        val id: String = "",
        val url: String = "",
        @Volatile var fileName: String = "",
        @Volatile var savePath: String = "",
        @Volatile var totalSize: Long = -1L,
        @Volatile var downloadedBytes: Long = 0L,
        @Volatile var progress: Float = 0f,
        @Volatile var speed: Long = 0L,
        @Volatile var status: String = "pending",
        @Volatile var error: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var completedAt: Long = 0L,
        val engine: String = "aria2",
        val protocol: String = "http",
        @Volatile var gid: String? = null,
        @Volatile var connections: Int = 0,
        @Volatile var seeders: Int = 0,
        @Volatile var uploadSpeed: Long = 0L
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
        var maxOverallUploadLimit: Long = 0,
        // ── 高级 BT（专家项 file-allocation/dht-listen-port/bt-max-open-files/
        //     bt-tracker-connect-timeout/bt-request-peer-speed-limit/max-resume-failure
        //     已在 Aria2Engine 固化为字面量，不再进入持久化配置）──
        var btSeedRatio: Float = 1.0f,
        var btMaxPeers: Int = 50,
        var btEnableDht: Boolean = true,
        var btEnableLpd: Boolean = true,
        // ── 高级 网络/重试 ──
        var disableIpv6: Boolean = true,
        var checkCertificate: Boolean = false,
        var maxTries: Int = 5,
        var retryWait: Int = 3,
        // ── BT Tracker 管理 ──
        var btTrackerAutoUpdate: Boolean = true,
        var btTrackerUpdateIntervalHours: Int = 24,
        var btTrackerSourceUrl: String = "https://cf.trackerslist.com/best_aria2.txt",
        var btTrackerCustomList: String = "",
        // ── 智能性能控制（阈值已由固件调参固定，仅保留开关与用户偏好）──
        var smartThrottle: Boolean = true,
        // 2026-09-02：warn 原本是 55°C，而 F50 这类 UFI 空载就 64~66°C —— 等于开机即限速
        // （1000KB/s + 并发砍半）且永不退出。按实测空载温度上调到 75/85。
        var throttleTempWarn: Float = 75f,
        var throttleTempCritical: Float = 85f,
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
    @Volatile var config = DownloadConfig()
        private set

    private var aria2PollJob: Job? = null
    private var smartThrottleJob: Job? = null

    /** 后台总闸（「停止服务」）是否已停掉下载侧的自主活动。 */
    @Volatile
    private var backgroundPaused: Boolean = false

    /** 被总闸自动暂停的任务 id；[resumeBackground] 只拉回这批，用户自己暂停的不动。 */
    private val autoPausedIds = ConcurrentHashMap.newKeySet<String>()


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
    // DataScheduler is in the main core module (not extracted yet); always use direct reads

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
        // 将之前下载中的任务标记为暂停（含 BT 的 metadata 阶段 "meta"：漏掉它的话
        // 下面的 hasPendingAria2 判定不成立，引擎不会被拉起，任务就永远停在获取种子信息中）
        tasks.values.filter { it.status in listOf("downloading", "meta", "verifying") }
            .forEach { it.status = "paused" }
        saveTasks()

        // 懒加载: 探测 aria2 版本信息（不长期运行进程）
        aria2.probeVersion()

        // 定时拉到的新 Tracker 列表热加载进运行中的 aria2：
        // 原来只写缓存文件，运行中的实例要等下次引擎重启才看得到新列表。
        trackerManager.onTrackersUpdated = { list ->
            if (aria2.isRunning()) aria2.changeBtTracker(list)
        }

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

    /**
     * 只有这些字段能被 aria2 的 changeGlobalOption 热改（并发数、全局限速、BT 节点数）。
     * 其余字段（split / max-connection-per-server / enable-dht / bt-enable-lpd /
     * disable-ipv6 / check-certificate / max-tries / retry-wait / seed-ratio / dir）
     * 属于启动期选项，只有重写 aria2.conf 并重启进程才生效。
     */
    private fun needsEngineRestart(old: DownloadConfig, new: DownloadConfig): Boolean =
        old.splitCount != new.splitCount ||
            old.maxConnectionsPerServer != new.maxConnectionsPerServer ||
            old.btEnableDht != new.btEnableDht ||
            old.btEnableLpd != new.btEnableLpd ||
            old.btSeedRatio != new.btSeedRatio ||
            old.disableIpv6 != new.disableIpv6 ||
            old.checkCertificate != new.checkCertificate ||
            old.maxTries != new.maxTries ||
            old.retryWait != new.retryWait ||
            old.saveDir != new.saveDir

    /**
     * 有启动期配置改了但引擎正忙（有任务在跑），没法立刻重启 —— 置位后由 UI 提示用户，
     * 下次引擎启动时 aria2.conf 会重写，配置自然生效。
     */
    @Volatile var pendingEngineRestart: Boolean = false
        private set

    fun updateConfig(newConfig: DownloadConfig) {
        val previous = config
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
                maxOverallUploadLimit = if (config.maxOverallUploadLimit > 0) config.maxOverallUploadLimit else null,
                btMaxPeers = config.btMaxPeers
            )
            // 启动期选项改了：引擎空闲就直接重启让它生效（之前这些项写完 config.json 就没人管，
            // 用户改完"分片数/DHT/证书校验"什么都不会发生）。有任务在跑时不能重启：
            // 重启后 aria2 的 GID 会变，正在轮询的任务会全部对不上号。
            if (needsEngineRestart(previous, config)) {
                val busy = tasks.values.any {
                    it.engine == "aria2" && it.status in listOf("downloading", "meta", "verifying", "pending")
                }
                if (busy) {
                    pendingEngineRestart = true
                    AppLogger.i(TAG, "engine restart deferred: tasks running, new conf applies on next start")
                } else {
                    AppLogger.i(TAG, "restarting aria2 to apply startup-only config")
                    aria2PollJob?.cancel()
                    aria2.stop()
                    aria2.start(config)
                    pendingEngineRestart = false
                    scope.launch {
                        var waited = 0L
                        while (!aria2.isRunning() && waited < 15_000) { delay(500); waited += 500 }
                        if (aria2.isRunning()) startAria2Polling()
                        else AppLogger.e(TAG, "aria2 restart failed after ${waited}ms")
                    }
                }
            }
        } else {
            // 引擎没在跑：conf 在下次 start() 时按新配置重写，没有待处理项
            pendingEngineRestart = false
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
                delay(10_000)  // 与 DataScheduler 采集周期对齐，避免连续两次读到相同缓存数据
            }
        }
    }

    private suspend fun checkAndThrottle() {
        // ── 采集传感器数据（直接读取，DataScheduler 在主 core 模块尚未抽取）──

        // 温度: 直接读取 thermal zone
        val temp = readMaxTemp()

        // CPU: 直接读取 /proc/stat 差值计算
        val cpu = readCpuUsage()

        // 电量: 直接读取电池信息
        val batteryInfo = readBatteryInfo()

        // 内存: 直接读取 /proc/meminfo
        val memory = readMemoryUsage()

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
                debounceSaveTasks()
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
            debounceSaveTasks()
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
            maxTemp
        } catch (_: Exception) { 0f }
    }

    /** 读取 CPU 使用率（直接读 /proc/stat，无需 shell） */
    private suspend fun readCpuUsage(): Int {
        return try {
            val parse = { text: String ->
                text.lineSequence().firstOrNull { it.startsWith("cpu ") }
                    ?.substringAfter("cpu ")?.trim()?.split("\\s+".toRegex())
                    ?.map { it.toLongOrNull() ?: 0L } ?: emptyList()
            }
            val v1 = parse(File("/proc/stat").readText())
            delay(200)
            val v2 = parse(File("/proc/stat").readText())
            if (v1.size < 7 || v2.size < 7) return 0
            val total = v1.indices.sumOf { v2.getOrElse(it) { 0L } - v1.getOrElse(it) { 0L } }
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
            val content = memFile.readText()
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
        // 同一个链接不允许产生第二条记录。aria2 侧本来就只认一个 infoHash，
        // 重复添加会被回 "InfoHash … is already registered."，新记录拿不到 GID，
        // 之后每轮轮询都重提交、每轮都被拒，任务永久停在报错态。
        findDuplicateTask(url)?.let { existing ->
            return when (existing.status) {
                "error" -> {
                    // 旧记录已经失败：复用它重来，而不是再开一条
                    AppLogger.i(TAG, "createTask: reusing failed task ${existing.id} for the same source")
                    existing.status = "pending"
                    existing.error = null
                    existing.gid = null
                    existing.savePath = savePath ?: existing.savePath
                    saveTasks()
                    startAria2IfNeeded()
                    submitAria2Download(existing, speedLimit, connections)
                    existing
                }
                else -> {
                    AppLogger.i(TAG, "createTask: duplicate source, returning existing task ${existing.id} (${existing.status})")
                    existing
                }
            }
        }

        val id = UUID.randomUUID().toString().take(8)
        val protocol = detectProtocol(url)
        val engine = "aria2"  // 统一走 aria2
        // savePath 始终指向公共目录（用户期望的最终位置）
        val finalSavePath = savePath ?: config.saveDir
        // 区分目录路径 vs 文件路径，避免把文件名当成目录创建
        // 使用 smartMkdir 保底链（File.mkdirs）
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
        // removeDownloadResult 必须无条件调用（此前只在 deleteFile=true 时调）：
        // forceRemove 只是把下载挪进 tellStopped 的结果列表，BT 的 infoHash 依旧挂在
        // aria2 的 BtRegistry 上。结果不清 → 同一个磁链再也加不进来，addUri 永远回
        // "InfoHash … is already registered."。retryTask 走的也是这条路（deleteFile=false），
        // 所以"重新下载"一个 BT 任务此前必然失败。
        task.gid?.let { aria2.remove(it); aria2.removeDownloadResult(it) }
        tasks.remove(id)
        saveTasks()

        // 文件删除异步执行，不阻塞调用线程（含多次 shell 调用 + find 兜底）
        if (deleteFile) {
            scope.launch { deleteDownloadedFiles(task) }
        }
        return true
    }

    /**
     * 重命名下载任务（文件）。
     *
     * 行为：
     * ① 已完成任务：重命名磁盘上实际文件（savePath 指向的文件），并同步更新 savePath。
     * ② 进行中/暂停/失败任务：aria2 私有工作目录中可能已存在旧名文件，一并重命名；
     *    同时把任务目标路径的文件名后缀替换为新名，保证完成转移后使用新名。
     * ③ 始终更新内存中的 fileName 显示名，并持久化。
     *
     * 名称校验：非空、不含路径分隔符。
     *
     * @return 是否成功（任务不存在或名称非法返回 false）
     */
    fun renameTask(id: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isBlank() || clean.contains("/") || clean.contains("\\")) return false
        val task = tasks[id] ?: return false
        val oldName = task.fileName
        // ① 已完成：重命名实际文件
        val curFile = File(task.savePath)
        if (curFile.exists() && curFile.isFile) {
            val parent = curFile.parent
            if (parent != null) {
                val newFile = File(parent, clean)
                try {
                    if (curFile.renameTo(newFile)) task.savePath = newFile.absolutePath
                } catch (_: Exception) { /* 忽略重命名失败，仅更新显示名 */ }
            }
        }
        // ② 进行中：一并重命名 aria2 私有工作目录中的旧名文件，并同步目标路径文件名
        if (oldName.isNotBlank()) {
            val privOld = File(DEFAULT_SAVE_DIR, oldName)
            if (privOld.exists()) {
                val privNew = File(DEFAULT_SAVE_DIR, clean)
                try { privOld.renameTo(privNew) } catch (_: Exception) { /* 忽略 */ }
            }
            if (task.savePath.endsWith(oldName)) {
                task.savePath = task.savePath.removeSuffix(oldName) + clean
            }
        }
        task.fileName = clean
        saveTasks()
        return true
    }

    /**
     * 删除下载文件（使用 withContext 在 IO 线程池执行，避免阻塞调用线程）。
     * 按优先级依次尝试：
     * ① task.savePath（任务记录的目标路径）
     * ② DEFAULT_SAVE_DIR/fileName（aria2 私有工作目录）
     * ③ config.saveDir/fileName（用户配置的保存目录）
     * ④ PUBLIC_DOWNLOAD_DIR/fileName（默认公共目录）
     * ⑤ shell find 在 /storage/emulated/0 下按文件名搜索（文件被移动后的保底）
     */
    private suspend fun deleteDownloadedFiles(task: DownloadTask) {
        if (task.fileName.isBlank()) return

        withContext(Dispatchers.IO) {
            val searchPaths = mutableSetOf(
                task.savePath,
                "${DEFAULT_SAVE_DIR}/${task.fileName}",
                "${config.saveDir}/${task.fileName}",
                "${PUBLIC_DOWNLOAD_DIR}/${task.fileName}"
            )

            var found = false

            for (path in searchPaths) {
                if (path.isBlank()) continue
                val file = File(path)
                if (!file.exists()) continue
                val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (ok) {
                    AppLogger.i(TAG, "Deleted file: $path")
                    found = true
                } else {
                    AppLogger.w(TAG, "Failed to delete file: $path")
                }
            }

            // 清理 aria2 控制文件 (.aria2)
            val controlPath = "${DEFAULT_SAVE_DIR}/${task.fileName}.aria2"
            File(controlPath).delete()

            // 兜底：文件被移动到未知路径时，按文件名 + 大小在用户存储中搜索
            if (!found && !task.fileName.contains("..") && task.totalSize > 0) {
                try {
                    val root = File("/storage/emulated/0")
                    if (root.exists()) {
                        root.walkTopDown().maxDepth(5)
                            .filter { it.isFile && it.name == task.fileName }
                            .forEach { fp ->
                                if (fp.length() != task.totalSize) {
                                    AppLogger.w(TAG, "Moved file size mismatch, skipping: ${fp.absolutePath} (expected ${task.totalSize}, got ${fp.length()})")
                                    return@forEach
                                }
                                if (fp.delete()) {
                                    AppLogger.i(TAG, "Deleted moved file (size verified): ${fp.absolutePath}")
                                    found = true
                                }
                            }
                    }
                } catch (_: Exception) {}
            }

            if (!found) {
                if (task.totalSize > 0) {
                    AppLogger.w(TAG, "deleteDownloadedFiles: file '${task.fileName}' not found at any known location")
                } else {
                    AppLogger.d(TAG, "deleteDownloadedFiles: unknown file size, skip search for '${task.fileName}'")
                }
            }
        }
    }

    fun getActiveCount(): Int = tasks.values.count { it.status in listOf("downloading", "pending", "meta", "verifying") }

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

    /**
     * 后台总闸关闭（用户点了「停止服务」）：停掉下载侧全部自主活动 ——
     * aria2 状态轮询、智能限速巡检、BT Tracker 定时更新，并把在跑的任务落成 paused 后关掉 aria2 进程。
     *
     * 与 [shutdown] 的区别：**不 `scope.cancel()`**。scope 一旦取消，后面所有 `scope.launch`
     * 都变 no-op，只能重建实例才能恢复 —— 那样"再次开启服务"就名存实亡。
     * 已下载的分片与 `.aria2` 控制文件都保留，[resumeBackground] 会断点续传。
     */
    fun pauseBackground() {
        if (backgroundPaused) return
        backgroundPaused = true
        aria2PollJob?.cancel(); aria2PollJob = null
        smartThrottleJob?.cancel(); smartThrottleJob = null
        throttleState = "disabled"
        trackerManager.stopAutoUpdate()
        autoPausedIds.clear()
        tasks.values.filter { it.status in listOf("downloading", "meta", "verifying") }.forEach {
            it.status = "paused"
            autoPausedIds += it.id
        }
        saveTasks()
        aria2.stop()
        AppLogger.i(TAG, "download background paused (${autoPausedIds.size} task(s) auto-paused)")
    }

    /**
     * 后台总闸开启：恢复 Tracker 更新与智能限速，并把 [pauseBackground] 自动暂停的任务续传。
     *
     * 续传走"清掉旧 GID 后重新提交"：aria2 进程已经换过一轮，旧 GID 在新进程里不存在，
     * `aria2.unpause(旧 GID)` 必然失败。重新 addUri 会命中同目录下的 `.aria2` 控制文件断点续传。
     */
    fun resumeBackground() {
        if (!backgroundPaused) return
        backgroundPaused = false
        if (config.btTrackerAutoUpdate) {
            trackerManager.startAutoUpdate(
                config.btTrackerUpdateIntervalHours,
                config.btTrackerSourceUrl,
                config.btTrackerCustomList
            )
        }
        startSmartThrottle()
        val pending = autoPausedIds.toList()
        autoPausedIds.clear()
        if (pending.isEmpty()) return
        startAria2IfNeeded()
        scope.launch {
            var waited = 0L
            while (!aria2.isRunning() && waited < 15_000) { delay(500); waited += 500 }
            if (!aria2.isRunning()) {
                AppLogger.e(TAG, "resume: aria2 not ready after ${waited}ms, ${pending.size} task(s) stay paused")
                return@launch
            }
            pending.mapNotNull { tasks[it] }.forEach { task ->
                task.gid = null
                task.error = null
                resumeTask(task.id)
            }
            saveTasks()
            AppLogger.i(TAG, "download background resumed (${pending.size} task(s) restored)")
        }
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
                    // 引擎刚起来先对账，再开轮询
                    reconcileOrphanDownloads()
                    startAria2Polling()
                } else {
                    AppLogger.e(TAG, "aria2 engine failed to become ready after ${waited}ms")
                }
            }
        }
    }

    private fun submitAria2Download(task: DownloadTask, speedLimit: Long?, connections: Int?) {
        scope.launch {
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
                task.status = "error"; task.error = diag; saveTasks(); notifyTaskResult(task, "error"); return@launch
            }
            // aria2c 子进程只能写私有目录（scoped storage 限制），
            // 完成后 transferToPublicDir 会拷贝到 task.savePath 公共目录
            val add = aria2.addUriDetailed(
                uris = listOf(task.url), dir = DEFAULT_SAVE_DIR,
                fileName = task.fileName.ifBlank { null },
                maxConnPerServer = connections ?: config.maxConnectionsPerServer,
                speedLimit = speedLimit ?: if (config.perTaskSpeedLimit > 0) config.perTaskSpeedLimit else null
            )
            // "已注册" 不是失败：aria2 里可能真有这个 infoHash 在跑（认领它的 GID），
            // 也可能只是上一条任务留下的死结果占着注册（清掉再提一次）。
            val gid = when (add) {
                is Aria2Engine.AddResult.Ok -> add.gid
                is Aria2Engine.AddResult.Duplicate -> recoverDuplicate(task) {
                    aria2.addUriDetailed(
                        uris = listOf(task.url), dir = DEFAULT_SAVE_DIR,
                        fileName = task.fileName.ifBlank { null },
                        maxConnPerServer = connections ?: config.maxConnectionsPerServer,
                        speedLimit = speedLimit ?: if (config.perTaskSpeedLimit > 0) config.perTaskSpeedLimit else null
                    )
                }
                is Aria2Engine.AddResult.Failed -> null
            }
            if (gid != null) {
                task.gid = gid; task.status = "downloading"
                task.connections = connections ?: config.maxConnectionsPerServer; saveTasks()
            } else {
                task.status = "error"
                val rpcMessage = (add as? Aria2Engine.AddResult.Failed)?.message
                task.error = when {
                    add is Aria2Engine.AddResult.Duplicate ->
                        "aria2 里的 infoHash ${add.infoHash ?: "未知"} 无法清除，请重启后端后重试"
                    task.protocol == "https" && config.checkCertificate -> "aria2 提交失败（HTTPS证书校验可能不通过，尝试关闭\"校验证书\"）"
                    task.protocol == "https" -> "aria2 提交失败（可能是TLS/证书问题，查看aria2日志）"
                    rpcMessage != null -> "aria2 提交失败：$rpcMessage"
                    else -> "aria2 提交失败（查看设备日志排查）"
                }
                saveTasks()
                notifyTaskResult(task, "error")
            }
        }
    }

    // ── 通知钩子（由 ComponentFactory 接到 NotificationDispatcher::emit）──
    // 下载是 core 的能力，"完成/失败"的状态跃迁只有 core 看得见。此前邮件靠 app 前台轮询下载列表、
    // 自己比对上一次的状态后回传，app 不在线 = 永远没有下载邮件；推送则挂在另一个
    // attachPushService 钩子上 —— 同一件事两个出口，加第三个渠道就要再缝一遍。
    // 2026-09-08 合成一个：投给哪些渠道由分发器的注册表决定。
    @Volatile
    private var notifier: Notifier? = null

    /** 装配通知钩子；传 null 解除。 */
    fun attachNotifier(n: Notifier?) {
        notifier = n
    }

    /**
     * 任务终态通知（fire-and-forget）：交给分发器。
     *
     * 这里**不判任何开关**：总闸与免打扰在分发器，场景勾选在邮件渠道。
     * 失败只落日志，绝不影响下载本身。
     */
    private fun notifyTaskResult(task: DownloadTask, status: String) {
        val emit = notifier ?: return
        val name = task.fileName.ifBlank { task.url.substringAfterLast('/').ifBlank { task.id } }
        val failed = status == "error"
        val title = if (failed) "下载失败: $name" else "下载完成: $name"
        val body = buildString {
            appendLine("文件: $name")
            appendLine("来源: ${task.url}")
            if (task.totalSize > 0) appendLine("大小: ${task.totalSize / 1024 / 1024} MB")
            if (failed) appendLine("错误: ${task.error ?: "未知"}")
        }.trimEnd()

        scope.launch {
            try {
                emit(
                    NotifyEvent(
                        scene = NotifyScenes.DOWNLOAD,
                        level = if (failed) NotifyLevel.WARNING else NotifyLevel.INFO,
                        title = title,
                        body = body,
                        // extra 逐字沿用原 PushNotification.extra：app 侧 `NotifyService`
                        // 读 status 判断是不是失败，改一个键它就会把失败渲染成成功。
                        extra = mapOf(
                            "task_id" to task.id,
                            "name" to name,
                            "status" to status,
                            "error" to (task.error ?: "")
                        )
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "download notify failed: ${e.message}")
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

    /** GID 查不到的首次时间戳（任务 id → 毫秒），用于宽限后再重提交 */
    private val lostGidSince = ConcurrentHashMap<String, Long>()

    /**
     * 任务的 GID 在 aria2 里查不到时的自愈：
     * ① 先在现有下载里按 infoHash / URI 认领（引擎重启后 session 恢复会换 GID）；
     * ② 认领不到就重新提交 —— 磁链优先用 bt-save-metadata 存下的 .torrent，
     *    这样跳过 metadata 阶段，并配合 conf 里的 continue=true 续上私有目录里的分片。
     *
     * 之所以要这套逻辑：引擎重启（改配置/服务被拉起/进程被杀）后，磁链的载荷 GID 不在
     * aria2 的 session 里，旧实现直接 continue，任务就永久停在"获取种子信息中"。
     */
    private fun resubmitLostTask(task: DownloadTask) {
        if (!aria2.isRunning()) return
        if (task.status in listOf("completed", "error")) return
        val firstSeen = lostGidSince.putIfAbsent(task.id, System.currentTimeMillis())
        // 引擎刚起来时 session 可能还没加载完，给一段宽限期再动手
        if (firstSeen == null || System.currentTimeMillis() - firstSeen < LOST_GID_GRACE_MS) return

        adoptExistingGid(task)?.let { adopted ->
            AppLogger.i(TAG, "task ${task.id}: adopt aria2 gid ${task.gid} -> $adopted")
            task.gid = adopted
            lostGidSince.remove(task.id)
            saveTasks()
            return
        }

        val savedTorrent = magnetInfoHash(task.url)
            ?.let { File(DEFAULT_SAVE_DIR, "$it.torrent") }
            ?.takeIf { it.exists() }
        // 有本地 .torrent 就先用它（跳过 metadata 阶段）；失败再退回原始 URI，
        // 不能因为种子文件坏了就让任务卡死。
        val fromTorrent = savedTorrent?.let { aria2.addTorrentDetailed(it.absolutePath, DEFAULT_SAVE_DIR) }
        val add = fromTorrent?.takeIf { it !is Aria2Engine.AddResult.Failed }
            ?: aria2.addUriDetailed(
                uris = listOf(task.url), dir = DEFAULT_SAVE_DIR,
                fileName = task.fileName.ifBlank { null },
                maxConnPerServer = config.maxConnectionsPerServer,
                speedLimit = if (config.perTaskSpeedLimit > 0) config.perTaskSpeedLimit else null
            )
        val newGid = when (add) {
            is Aria2Engine.AddResult.Ok -> add.gid
            // aria2 说这个 infoHash 已经注册了 —— 先认领活着的下载，认不到就清残留再提一次。
            // 这是"提示 InfoHash … is already registered"死循环的出口。
            is Aria2Engine.AddResult.Duplicate -> recoverDuplicate(task) {
                aria2.addUriDetailed(
                    uris = listOf(task.url), dir = DEFAULT_SAVE_DIR,
                    fileName = task.fileName.ifBlank { null },
                    maxConnPerServer = config.maxConnectionsPerServer,
                    speedLimit = if (config.perTaskSpeedLimit > 0) config.perTaskSpeedLimit else null
                )
            }
            is Aria2Engine.AddResult.Failed -> null
        }
        if (newGid != null) {
            AppLogger.i(TAG, "task ${task.id}: gid ${task.gid} lost, now $newGid" +
                when {
                    add is Aria2Engine.AddResult.Duplicate -> " (adopted, already registered)"
                    fromTorrent is Aria2Engine.AddResult.Ok -> " (resubmitted from saved metadata)"
                    else -> " (resubmitted)"
                })
            task.gid = newGid
            task.status = "downloading"
            task.error = null
            lostGidSince.remove(task.id)
            saveTasks()
        } else {
            val reason = when (add) {
                is Aria2Engine.AddResult.Duplicate -> "infoHash 已注册且残留结果清不掉"
                is Aria2Engine.AddResult.Failed -> add.message ?: "未知错误"
                else -> "未知错误"
            }
            AppLogger.w(TAG, "task ${task.id}: gid lost and resubmit failed ($reason), will retry")
            lostGidSince[task.id] = System.currentTimeMillis()
        }
    }

    /**
     * 在 aria2 现有下载里找同一个任务：BT 比 infoHash，其余比首个 URI。
     *
     * 三点必须注意：
     * - 要连 `tellStopped` 一起看。磁链的第一阶段 GID 抓完 metadata 就变成 stopped，
     *   真正的载荷 GID 在它的 `followedBy` 里，只翻 active/waiting 会漏掉。
     * - 命中 metadata 条目时要跟着 `followedBy` 跳到载荷 GID，否则认领到一个已完成的空壳。
     * - [ignoreOwned] = true 时不再跳过"已被其它任务记录占用"的 GID：aria2 回
     *   "already registered" 说明这个 infoHash 确实在跑，此时宁可让两条记录指向同一个 GID，
     *   也比无限重提交、任务永远报错要好。
     */
    private fun adoptExistingGid(task: DownloadTask, ignoreOwned: Boolean = false): String? {
        val hash = magnetInfoHash(task.url)
        val ownedGids = if (ignoreOwned) emptySet() else
            tasks.values.filter { it.id != task.id }.mapNotNull { it.gid }.toSet()
        val candidates = aria2.tellActive() + aria2.tellWaiting() + aria2.tellStopped()
        for (c in candidates) {
            val gid = c["gid"]?.jsonPrimitive?.contentOrNull ?: continue
            val matched = if (hash != null) {
                c["infoHash"]?.jsonPrimitive?.contentOrNull?.equals(hash, ignoreCase = true) == true
            } else {
                val uri = c["files"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("uris")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("uri")?.jsonPrimitive?.contentOrNull
                uri == task.url
            }
            if (!matched) continue
            // metadata 阶段的条目：跳到它带出来的载荷 GID
            val followed = c["followedBy"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
            val resolved = followed ?: gid
            if (resolved in ownedGids) continue
            // 只认活着的下载。tellStopped 里 complete/error/removed 的结果同样占着 infoHash
            // 注册，认领过来只会让任务立刻变成"已完成/错误"（而且没有文件），真正该做的是
            // 把那条死结果清掉重提 —— 见 purgeStaleAria2Results。
            val st = aria2.tellStatus(resolved)?.get("status")?.jsonPrimitive?.contentOrNull
            if (st != null && st !in LIVE_ARIA2_STATUSES) continue
            return resolved
        }
        return null
    }

    /**
     * 处理 aria2 的 "InfoHash … is already registered."：
     * ① 先在活着的下载里认领同 infoHash 的 GID（引擎重启后 session 恢复会换 GID）；
     * ② 认不到就说明注册是残留 —— 上一条任务被 forceRemove/删除，但 downloadResult 还在
     *    结果列表里占着 infoHash。清掉残留后用 [retry] 重提一次。
     */
    private fun recoverDuplicate(task: DownloadTask, retry: () -> Aria2Engine.AddResult): String? {
        adoptExistingGid(task, ignoreOwned = true)?.let {
            AppLogger.i(TAG, "task ${task.id}: infoHash already registered, adopted live gid $it")
            return it
        }
        if (!purgeStaleAria2Results(task)) {
            AppLogger.w(TAG, "task ${task.id}: infoHash registered but no stale result found to purge")
            return null
        }
        return (retry() as? Aria2Engine.AddResult.Ok)?.gid?.also {
            AppLogger.i(TAG, "task ${task.id}: re-added after purging stale registration -> gid $it")
        }
    }

    /**
     * 引擎就绪后与本地任务对账：aria2 里还在跑、但本地已经没有对应记录的下载一律清掉。
     *
     * 这种"孤儿下载"的来源是删任务时 aria2 RPC 不可达（forceRemove 与 removeDownloadResult
     * 双双 ConnectException，2026-09-03 实测），下载继续跑、infoHash 继续占着注册，
     * 于是同一个磁链再也添加不进来 —— 而且重启引擎会从 session 里把它恢复出来，永久生效。
     */
    private fun reconcileOrphanDownloads() {
        // 本地一条记录都没有时不动手：可能是 tasks.json 刚被重置，宁可留着也别误删用户的下载
        if (tasks.isEmpty()) return
        val ownedGids = tasks.values.mapNotNull { it.gid }.toSet()
        val ownedHashes = tasks.values.mapNotNull { magnetInfoHash(it.url) }.toSet()
        val ownedUrls = tasks.values.map { it.url }.toSet()
        for (c in aria2.tellActive() + aria2.tellWaiting()) {
            val gid = c["gid"]?.jsonPrimitive?.contentOrNull ?: continue
            if (gid in ownedGids) continue
            // 两段式下载的另一半可能正被某条任务持有
            val related = (c["followedBy"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()) +
                listOfNotNull(c["following"]?.jsonPrimitive?.contentOrNull)
            if (related.any { it in ownedGids }) continue
            val hash = c["infoHash"]?.jsonPrimitive?.contentOrNull?.lowercase()
            if (hash != null && hash in ownedHashes) continue
            val uri = c["files"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("uris")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("uri")?.jsonPrimitive?.contentOrNull
            if (uri != null && uri in ownedUrls) continue
            // 既认不出 infoHash 也认不出 URI 时保守放过，避免误删
            if (hash == null && uri == null) continue
            AppLogger.i(TAG, "reconcile: dropping orphan aria2 download $gid (infoHash ${hash ?: "-"}, uri ${uri ?: "-"})")
            aria2.remove(gid)
            aria2.removeDownloadResult(gid)
        }
    }

    /**
     * 清掉 aria2 结果列表里同源的死结果（complete/error/removed），释放 infoHash 注册。
     * @return 是否清掉了至少一条
     */
    private fun purgeStaleAria2Results(task: DownloadTask): Boolean {
        val hash = magnetInfoHash(task.url)
        var purged = false
        for (c in aria2.tellStopped()) {
            val gid = c["gid"]?.jsonPrimitive?.contentOrNull ?: continue
            val matched = if (hash != null) {
                c["infoHash"]?.jsonPrimitive?.contentOrNull?.equals(hash, ignoreCase = true) == true
            } else {
                val uri = c["files"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("uris")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("uri")?.jsonPrimitive?.contentOrNull
                uri == task.url
            }
            if (!matched) continue
            if (aria2.removeDownloadResult(gid)) {
                purged = true
                AppLogger.i(TAG, "purged stale aria2 result $gid (infoHash ${hash ?: "-"})")
            } else {
                AppLogger.w(TAG, "removeDownloadResult failed for stale gid $gid")
            }
        }
        return purged
    }

    /** 从磁链里取 40 位十六进制 infohash（base32 形式的磁链取不到，返回 null） */
    private fun magnetInfoHash(url: String): String? {
        if (!url.startsWith("magnet:", ignoreCase = true)) return null
        val m = Regex("xt=urn:btih:([0-9a-fA-F]{40})").find(url) ?: return null
        return m.groupValues[1].lowercase()
    }

    /**
     * 找已存在的同源任务：磁链比 infoHash（同一个种子换个 tracker 参数也算同一个），其余比 URL。
     * 已完成的任务不算重复 —— 用户可能就是想重新下一遍。
     */
    private fun findDuplicateTask(url: String): DownloadTask? {
        val hash = magnetInfoHash(url)
        return tasks.values
            .filter { it.status != "completed" }
            .firstOrNull { t ->
                if (hash != null) magnetInfoHash(t.url) == hash else t.url == url
            }
    }

    private fun syncAria2Status() {
        val aria2Tasks = tasks.values.filter { it.engine == "aria2" }
        if (aria2Tasks.isEmpty()) return
        for (task in aria2Tasks) {
            val gid = task.gid
            if (gid == null) {
                // 有任务记录但没 GID：createTask 提交失败或进程在提交前被杀。
                // 旧实现直接跳过，任务就永远躺在列表里不动。
                if (task.status in listOf("pending", "downloading", "meta", "verifying")) resubmitLostTask(task)
                continue
            }
            val s = aria2.tellStatus(gid)
            if (s == null) {
                // GID 在 aria2 里查不到：引擎重启过、session 丢了或任务被外部移除。
                // 以前这里直接 continue，任务就永久卡在旧状态（磁链表现为一直"获取种子信息中"）。
                // 现在重新提交一次，把新 GID 记回任务。
                resubmitLostTask(task)
                continue
            }
            lostGidSince.remove(task.id)
            // 磁链/种子是两段式：第一个 GID 只下 metadata，拿到后 aria2 会另起一个 GID 下真正的内容，
            // 并把新 GID 写进 metadata GID 的 followedBy。此前只盯着原 GID —— metadata GID 一 complete
            // 就把任务判成"已完成"（transferToPublicDir 找不到文件），真正的内容 GID 没人轮询，
            // 表现就是磁链要么一直"获取种子信息中"，要么秒完成却没有文件。
            val followedGid = s["followedBy"]?.jsonArray?.firstOrNull()
                ?.jsonPrimitive?.contentOrNull
            if (!followedGid.isNullOrBlank() && followedGid != gid) {
                task.gid = followedGid
                task.status = "downloading"
                task.progress = -1f
                // metadata 阶段 aria2 报的文件名是 "[METADATA]<infohash>"，不是真实文件名
                if (task.fileName.startsWith("[METADATA]")) task.fileName = ""
                AppLogger.i(TAG, "BT metadata ready, follow gid $gid -> $followedGid")
                saveTasks()  // GID 换绑必须立刻落盘：晚一步遇上重启就退回 metadata 阶段
                continue
            }
            val prevStatus = task.status
            val totalLen = s["totalLength"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1L
            val completedLen = s["completedLength"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            // 校验（hash check）阶段：aria2 把进度记在 verifiedLength 上，completedLength 一直是 0。
            // 不读它的话，续传/重加已有数据的 BT 任务在校验期间界面上是"完全不动"，
            // 校验一结束又直接变 100% —— 用户看到的就是"卡住然后瞬间完成"（2026-09-03 反馈）。
            val verifiedLen = s["verifiedLength"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val verifyPending = s["verifyIntegrityPending"]?.jsonPrimitive?.contentOrNull == "true"
            val verifying = verifiedLen > 0L || verifyPending
            task.totalSize = totalLen; task.downloadedBytes = completedLen

            task.speed = s["downloadSpeed"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            task.uploadSpeed = s["uploadSpeed"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            task.seeders = s["numSeeders"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            task.connections = s["connections"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            // 进度计算：区分 metadata 获取阶段、校验阶段和真正的下载阶段
            task.progress = if (totalLen > 0) {
                // 校验中 completedLength=0，用 verifiedLength 顶上，进度条才会走
                val done = if (completedLen == 0L && verifiedLen > 0L) verifiedLen else completedLen
                (done.toFloat() / totalLen).coerceIn(0f, 1f)
            } else {
                // totalLength=0 且无 connection → metadata 获取阶段，进度未知
                -1f
            }

            // 更新文件名（aria2 提供文件名，但不覆盖公共目标路径）
            // BT 任务优先用 torrent 顶层名：多文件种子落盘的是一个目录，而 files[0].path
            // 只是种子里的第一个文件，拿它当 fileName 会让完成后的转移阶段找不到东西。
            val btName = s["bittorrent"]?.jsonObject?.get("info")
                ?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val reportedName = btName
                ?: s["files"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("path")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.substringAfterLast("/")
            // "[METADATA]<infohash>" 是 metadata 阶段的占位名，不能当真实文件名
            if (reportedName != null && !reportedName.startsWith("[METADATA]") && task.fileName.isBlank()) {
                task.fileName = reportedName
                // 文件名首次获取时更新 savePath（保持指向公共目录）
                if (task.savePath.startsWith(DEFAULT_SAVE_DIR) || task.savePath.isBlank()) {
                    task.savePath = PUBLIC_DOWNLOAD_DIR
                }
            }
            // 如果 aria2 返回了 dir 字段且 fileName 不在当前 savePath 中（首次轮询），
            // 但只在 savePath 尚未指向公共目录时修正
            val aria2Dir = s["dir"]?.jsonPrimitive?.contentOrNull
            if (aria2Dir != null && task.fileName.isNotBlank() &&
                task.savePath.isBlank()) {
                task.savePath = "${PUBLIC_DOWNLOAD_DIR}/${task.fileName}"
            }

            val rawStatus = s["status"]?.jsonPrimitive?.contentOrNull
            val rawError = s["errorMessage"]?.jsonPrimitive?.contentOrNull
            // 磁链的两段式里，metadata 拿到种子后 aria2 要给载荷下载注册 infoHash；
            // 如果同一个 infoHash 已经在跑（典型来源：上一条任务删除时 RPC 不可达，
            // forceRemove/removeDownloadResult 双双失败，下载还在跑但本地记录没了），
            // 注册失败，这条 metadata GID 就以 "InfoHash … is already registered." 收场。
            // 这不是真失败 —— 那个下载好好地在跑。直接把任务改绑过去，
            // 否则用户只能看到这行英文报错，而且怎么删了重加都一样（2026-09-03 实测）。
            if (rawStatus == "error" && rawError?.contains("already registered", ignoreCase = true) == true) {
                val live = adoptExistingGid(task, ignoreOwned = true)
                if (live != null && live != gid) {
                    AppLogger.i(TAG, "task ${task.id}: metadata rejected (infoHash registered), rebind $gid -> $live")
                    aria2.removeDownloadResult(gid)   // 清掉这条死掉的 metadata 结果
                    task.gid = live
                    task.status = "downloading"
                    task.error = null
                    saveTasks()
                    continue
                }
                // 找不到活着的下载 = 注册被死结果占着：清掉它，下一轮按"无 GID"重新提交
                if (purgeStaleAria2Results(task)) {
                    AppLogger.i(TAG, "task ${task.id}: purged stale registration, will resubmit")
                    aria2.removeDownloadResult(gid)
                    task.gid = null
                    task.status = "pending"
                    task.error = null
                    lostGidSince.remove(task.id)
                    saveTasks()
                    continue
                }
            }
            val newStatus = when (rawStatus) {
                "active" -> when {
                    // metadata-fetching phase: totalLength=0, BT protocol, no data yet
                    totalLen <= 0 && task.protocol in listOf("magnet", "torrent") -> "meta"
                    // hash check：已有数据的续传/重加会先校验一遍，进度走 verifiedLength
                    verifying -> "verifying"
                    else -> "downloading"
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
                "error" -> { task.error = rawError ?: "aria2 错误"; "error" }
                "removed" -> "error"; else -> task.status
            }
            task.status = newStatus
            // 状态跃迁到终态才发邮件（轮询 1.5s 一次，不比对就是每轮一封）
            if (newStatus != prevStatus) {
                saveTasks()  // 状态跃迁立刻落盘，避免重启后读回过期状态
                if (newStatus == "completed" || newStatus == "error") {
                    notifyTaskResult(task, newStatus)
                }
            }
        }
        debounceSaveTasks()  // 1.5s 轮询周期 → 防抖合并，避免频繁写磁盘
    }

    // ─── 文件转移（私有目录 → 公共下载目录）──────────────

    /**
     * 启动时恢复孤儿文件：应用崩溃/重启后，私有目录中已下载完成但未转移到公共目录的文件，
     * 在此补转移。使用三层保底链，无 root 设备也能尽力恢复。
     *
     * 2026-09-02：只跳过 .aria2 本身是不够的 —— 未下完的数据文件旁边就有同名 .aria2 控制
     * 文件，旧实现把它也当"孤儿"搬进公共目录（实测搬走了一个 99.96% 的 mkv），用户看到的是
     * 一个残缺文件 + 任务仍卡着。现在按 .aria2 判定未完成，未完成的一律留在私有目录。
     */
    private fun recoverOrphanedDownloads() {
        val privateDir = File(DEFAULT_SAVE_DIR)
        if (!privateDir.exists() || !privateDir.isDirectory) return
        val all = privateDir.listFiles() ?: return
        val controlNames = all.filter { it.name.endsWith(".aria2") }.map { it.name.removeSuffix(".aria2") }.toSet()
        val items = all.filter { !it.name.endsWith(".aria2") }
        if (items.isEmpty()) return

        val pubDir = File(config.saveDir)
        smartMkdir(pubDir.absolutePath)
        var recovered = 0

        for (item in items) {
            if (item.name in controlNames) {
                AppLogger.i(TAG, "recover skip (still downloading, has .aria2): ${item.name}")
                continue
            }
            val target = File(pubDir, item.name)
            if (item.isDirectory) {
                // 磁链/BT 输出目录：目录内任何一个未完成分片都意味着整体没下完
                val hasControl = item.walkTopDown().any { it.isFile && it.name.endsWith(".aria2") }
                if (hasControl) {
                    AppLogger.i(TAG, "recover skip (dir still downloading): ${item.name}/")
                    continue
                }
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
     * 保底链：MediaStore → 直接拷贝 → 私有目录兜底
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
            debounceSaveTasks()
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
        debounceSaveTasks()
        return task
    }

    // ─── 文件拷贝保底机制（无 root）───────────────────────────
    // MediaStore API → 直接拷贝 → 私有目录兜底

    /**
     * 通过 MediaStore API 写入公共 Downloads 目录。
     * 这是 Android 官方推荐的无权限公共目录写入方式，适配无 root 设备。
     *
     * 2026-09-02：RELATIVE_PATH 原来硬编码 "Download/UFI/"，用户改了保存目录也会被写到这里；
     * 现在按目标路径推导，超出 MediaStore 能力（不在外置存储下）时才回落到默认目录。
     */
    private fun writeViaMediaStore(src: File, dst: File): Boolean {
        val dstFileName = dst.name
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
            val relative = mediaStoreRelativePath(dst)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, dstFileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relative)
            }
            val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            appContext.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { inp -> inp.copyTo(out) }
            } ?: return false
            AppLogger.i(TAG, "MediaStore: wrote ${src.length()} bytes → $relative$dstFileName")
            return true
        } catch (e: Exception) {
            AppLogger.w(TAG, "MediaStore write failed for '$dstFileName': ${e.message}")
            return false
        }
    }

    /**
     * 把绝对目标路径换算成 MediaStore 的 RELATIVE_PATH。
     * MediaStore.Downloads 只接受 "Download/…" 下的相对路径，其余情况回落到默认目录，
     * 由调用方的直接拷贝兜底。
     */
    private fun mediaStoreRelativePath(dst: File): String {
        val fallback = PUBLIC_DOWNLOAD_DIR.removePrefix("$EXTERNAL_ROOT/") + "/"
        val parent = dst.parentFile?.absolutePath ?: return fallback
        val rel = parent.removePrefix("$EXTERNAL_ROOT/").trim('/')
        return if (rel.isNotEmpty() && rel != parent && rel.startsWith("Download")) "$rel/"
        else fallback
    }

    /**
     * 统一的文件拷贝保底链（单文件，无 root）。
     * ① MediaStore API（官方推荐写入公共目录）→ ② 直接拷贝 → 调用方回退私有目录
     * @return false 表示所有方式均失败，调用方应回退到私有目录
     */
    private fun smartCopyFile(src: File, dst: File): Boolean {
        // ① MediaStore
        if (writeViaMediaStore(src, dst)) {
            return true
        }
        // ② 直接拷贝（MANAGE_EXTERNAL_STORAGE 已授予时可能成功）
        try {
            src.copyTo(dst, overwrite = true)
            AppLogger.d(TAG, "smartCopyFile: direct copy OK '${src.name}'")
            return true
        } catch (_: Exception) {}
        return false
    }

    /**
     * 统一的目录拷贝保底链（磁链/BT，无 root）。
     * MediaStore 不支持目录，所以只有直接递归拷贝一级。
     */
    private fun smartCopyDir(srcDir: File, dstDir: File): Boolean {
        try {
            srcDir.copyRecursively(dstDir, overwrite = true)
            AppLogger.d(TAG, "smartCopyDir: direct copyRecursively OK '${srcDir.name}/'")
            return true
        } catch (_: Exception) {}
        return false
    }

    /**
     * 统一的目录创建（无 root）。
     */
    private fun smartMkdir(path: String): Boolean {
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

    // ─── 持久化 ────────────────────────────────────────────

    private var saveTasksJob: Job? = null

    /**
     * 节流写入 —— 3s 内的多次调用合并为一次磁盘写入，保护闪存。
     *
     * 2026-09-02：原实现每次调用都 cancel 上一个 job 再排一个 3s 的延迟写，而轮询周期是
     * 1.5s < 3s —— 于是只要有 aria2 任务在轮询，落盘任务就永远在被取消前重排，tasks.json
     * 一次都写不出去。表现：内存里进度/GID 正常，但磁盘上永远停在"上次非防抖保存"的快照；
     * 服务或引擎一重启，loadTasks 读回旧快照，磁链的 GID 退回 metadata 阶段、进度归零，
     * UI 就永久停在"获取种子信息中"。改成"已有待写任务就合并进去"，保证最多 3s 必落盘。
     */
    private fun debounceSaveTasks() {
        if (saveTasksJob?.isActive == true) return
        saveTasksJob = scope.launch {
            delay(3_000)
            saveTasks()
        }
    }

    private fun saveTasks() {
        try { persistFile.writeText(json.encodeToString(tasks.values.toList())) }
        catch (e: Exception) { AppLogger.e(TAG, "saveTasks failed: ${e.javaClass.simpleName}: ${e.message}", e) }
    }
    /**
     * 任务/配置的读写失败以前是 `catch (_: Exception) {}` 全静默的。
     * 后果是「用户改的配置自己回默认值」这类现场完全不可查：文件权限异常、
     * 半截 JSON、字段类型不兼容，表现都一样 —— 静静地退回默认 config、任务列表空掉。
     * 所以这里只补日志（带文件路径 + 异常类型），控制流一个字都不改：
     * 读失败仍然沿用默认值继续跑，不能因为一次读盘失败就让下载模块起不来。
     */
    private fun loadTasks() {
        try {
            if (persistFile.exists()) {
                val t = persistFile.readText()
                if (t.isNotBlank()) json.decodeFromString<List<DownloadTask>>(t).forEach { tasks[it.id] = it }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "loadTasks failed, tasks stay empty: ${persistFile.absolutePath}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun saveConfig() {
        try {
            configFile.writeText(json.encodeToString(config))
        } catch (e: Exception) {
            AppLogger.w(TAG, "saveConfig failed, config not persisted: ${configFile.absolutePath}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun loadConfig() {
        try {
            if (configFile.exists()) {
                val t = configFile.readText()
                if (t.isNotBlank()) config = json.decodeFromString<DownloadConfig>(t)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "loadConfig failed, falling back to defaults: ${configFile.absolutePath}: ${e.javaClass.simpleName}: ${e.message}")
        }
        migrateConfig()
    }


    /**
     * 老配置迁移。config.json 里的值优先级高于默认值，所以光改默认值对已有设备无效：
     * - saveDir 还指着旧的 "Downloads/UFI"（MediaStore 实际写 "Download/UFI"，两个目录）
     * - 温度阈值还是 55/70，在空载 65°C 的 UFI 上等于永久限速
     */
    private fun migrateConfig() {
        var changed = false
        if (config.saveDir in LEGACY_PUBLIC_DOWNLOAD_DIRS) {
            config.saveDir = PUBLIC_DOWNLOAD_DIR
            changed = true
        }
        if (config.throttleTempWarn < 70f) { config.throttleTempWarn = 75f; changed = true }
        if (config.throttleTempCritical < 80f) { config.throttleTempCritical = 85f; changed = true }
        if (changed) {
            AppLogger.i(TAG, "config migrated: saveDir=${config.saveDir}, " +
                "tempWarn=${config.throttleTempWarn}, tempCritical=${config.throttleTempCritical}")
            saveConfig()
        }
    }
}
