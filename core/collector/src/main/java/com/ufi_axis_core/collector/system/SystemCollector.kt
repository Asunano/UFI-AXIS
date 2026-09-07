package com.ufi_axis_core.collector.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.SystemClock
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Android API 采集器
 * 采集 CPU/内存/存储/温度/电池/流量等系统信息
 * 数据来源: /proc, /sys 文件系统 + Android API
 */
class SystemCollector(private val context: Context) {

    private val tag = "SystemCollector"

    /**
     * Core 后端进程首次启动时间（绝对 epoch 毫秒）。构造时记录，整个进程生命周期不变。
     * 2026-08-08 12:09 新增：监控页"自定义时间范围"对话框需要限制最小日期 = Core 首次启动时间（防用户选早于后端记录的日期）。
     * 暴露 getStartupTime() 给 SystemRoutes 返回 /api/system/startup-time。
     */
    private val processStartedAt: Long = System.currentTimeMillis()

    /**
     * 获取 CPU 信息：总使用率 + 各核频率
     */
    /**
     * 缓存控制：CPU 频率和温度变化较慢，缓存有效期内直接返回上次结果。
     * 由 DataScheduler 每 3s 调用一次，但 sysfs 值实际变化周期远大于 3s。
     */
    private var lastCpuCacheTime: Long = 0L
    private var lastCpuCache: CpuInfo? = null

    suspend fun getCpuInfo(): CpuInfo {
        val now = System.currentTimeMillis()
        // ── CPU 信息缓存：5s 内直接返回上一次结果，减少 sysfs 读取压力 ──
        if (lastCpuCache != null && (now - lastCpuCacheTime) < 5_000L) {
            return lastCpuCache!!
        }
        var cpuUsage = 0.0
        val cores = mutableListOf<CpuCore>()
        var temperature = 0.0
        try {
            cpuUsage = readCpuUsage()
            
            val coreCount = Runtime.getRuntime().availableProcessors()
            // 直接读取 sysfs 文件，免 root 免 shell fork
            for (i in 0 until coreCount) {
                val freqKhz = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                    .also { if (!it.exists()) continue }
                    .readText().trim().toLongOrNull() ?: 0
                val freqMhz = freqKhz / 1000.0
                cores.add(CpuCore(core = i, freq_mhz = freqMhz, freq_display = formatFrequency(freqKhz)))
            }

            // 2026-08-11：先 exists 检查，避免 Android 设备无 /sys/class/thermal/* 节点时反复抛 ENOENT
            // 导致 error 日志爆炸（每天 398 次 × 30 行堆栈 = 30MB；之前未对齐 cpu freq 的 .exists 模式）
            val thermalFile = File("/sys/class/thermal/thermal_zone0/temp")
            if (thermalFile.exists()) {
                val tempMilli = thermalFile.readText().trim().toLongOrNull() ?: 0
                temperature = tempMilli / 1000.0
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to get CPU info", e)
        }
        val info = CpuInfo(usage_percent = cpuUsage, core_count = cores.size, cores = cores, temperature = temperature)
        lastCpuCache = info
        lastCpuCacheTime = now
        return info
    }

    /**
     * 获取内存信息
     * 直接读取 /proc/meminfo（world-readable，无需 shell）
     */
    suspend fun getMemoryInfo(): MemoryInfo {
        var total = 0L
        var free = 0L
        var available = 0L
        var buffers = 0L
        var cached = 0L
        try {
            val memInfo = File("/proc/meminfo").readText()
            val memMap = mutableMapOf<String, Long>()
            for (line in memInfo.lines()) {
                val parts = line.split(":")
                if (parts.size >= 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim().replace("kB", "").trim().toLongOrNull() ?: 0
                    memMap[key] = value * 1024  // 转为字节
                }
            }
            total = memMap["MemTotal"] ?: 0
            free = memMap["MemFree"] ?: 0
            available = memMap["MemAvailable"] ?: free
            buffers = memMap["Buffers"] ?: 0
            cached = memMap["Cached"] ?: 0
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to get memory info", e)
        }
        val used = total - available
        val usagePercent = if (total > 0) (used.toDouble() / total * 100) else 0.0
        return MemoryInfo(
            total = total,
            used = used,
            available = available,
            free = free,
            buffers = buffers,
            cached = cached,
            usage_percent = usagePercent
        )
    }

    /**
     * 获取存储信息
     */
    fun getStorageInfo(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        try {
            val stat = android.os.StatFs("/data")
            val totalBytes = stat.totalBytes
            val availableBytes = stat.availableBytes
            val usedBytes = totalBytes - availableBytes

            result["total"] = totalBytes
            result["available"] = availableBytes
            result["used"] = usedBytes
            result["usage_percent"] = if (totalBytes > 0) {
                (usedBytes.toDouble() / totalBytes * 100)
            } else 0.0
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to get storage info", e)
        }
        return result
    }

    /**
     * 获取电池信息
     *
     * 三级取值，任一级拿到就用（UFI 设备的 sticky ACTION_BATTERY_CHANGED 经常只带 status/plugged，
     * 不带 EXTRA_LEVEL/EXTRA_SCALE —— 于是 percent 恒为 -1，就是"电量显示 -1"的直接原因）：
     *   1. sticky ACTION_BATTERY_CHANGED 的 EXTRA_LEVEL / EXTRA_SCALE
     *   2. BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)（framework 自己去问 HAL）
     *   3. /sys/class/power_supply/<x>/capacity（节点名各家不同，遍历所有 type=Battery 的目录）
     *
     * 温度/电压同样做兜底，并且**取不到就返回 -1，而不是 -1/10.0 = -0.1** —— 原来那个
     * -0.1°C / -0.001V 比 -1 更难认出来是"没取到"。
     */
    fun getBatteryInfo(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)

            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val temperature = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val voltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

            val percent: Int = when {
                level >= 0 && scale > 0 -> level * 100 / scale
                else -> batteryCapacityFallback()
            }

            result["level"] = if (level >= 0) level else percent
            result["scale"] = if (scale > 0) scale else 100
            result["percent"] = percent
            result["temperature"] = when {
                temperature > 0 -> temperature / 10.0
                else -> readPowerSupplyInt("temp")?.let { it / 10.0 } ?: -1.0
            }
            result["voltage"] = when {
                voltage > 0 -> voltage / 1000.0
                // voltage_now 是微伏
                else -> readPowerSupplyInt("voltage_now")?.let { it / 1_000_000.0 } ?: -1.0
            }
            // FULL 也算在充电（与 BatteryNotifier / DownloadManager 的判定保持一致）；
            // status 取不到时退到 sysfs 的 status 文本。
            result["is_charging"] = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
                -1, BatteryManager.BATTERY_STATUS_UNKNOWN -> readPowerSupplyText("status")?.let {
                    it.equals("Charging", ignoreCase = true) || it.equals("Full", ignoreCase = true)
                } ?: false
                else -> false
            }
            result["plugged"] = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "None"
            }
            if (percent < 0) {
                AppLogger.w(tag, "Battery level unavailable: intent=${batteryStatus != null}, level=$level, scale=$scale")
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to get battery info", e)
        }
        return result
    }

    /** 电量兜底：BatteryManager 属性 → sysfs capacity；都拿不到返回 -1 */
    private fun batteryCapacityFallback(): Int {
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val capacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            // 无电池设备这里会返回 Integer.MIN_VALUE 或 0，一并当作"没有"
            if (capacity in 1..100) return capacity
        } catch (e: Exception) {
            AppLogger.w(tag, "BATTERY_PROPERTY_CAPACITY failed: ${e.message}")
        }
        return readPowerSupplyInt("capacity")?.coerceIn(0, 100) ?: -1
    }

    /**
     * 遍历 /sys/class/power_supply/ 下所有电池节点读取指定文件。
     * 节点名不固定（battery / bat / max170xx_battery …），所以按 `type` 文件判断，
     * 不硬编码路径 —— 硬编码 /sys/class/power_supply/battery 在这台设备上就是读不到。
     */
    private fun powerSupplyFile(name: String): File? {
        val root = File("/sys/class/power_supply")
        val dirs = root.listFiles()?.takeIf { it.isNotEmpty() } ?: return null
        for (dir in dirs) {
            val type = File(dir, "type").takeIf { it.canRead() }?.runCatching { readText().trim() }?.getOrNull()
            if (!type.equals("Battery", ignoreCase = true)) continue
            val f = File(dir, name)
            if (f.canRead()) return f
        }
        return null
    }

    private fun readPowerSupplyText(name: String): String? =
        powerSupplyFile(name)?.runCatching { readText().trim() }?.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun readPowerSupplyInt(name: String): Int? = readPowerSupplyText(name)?.toIntOrNull()


    /**
     * 获取流量统计（仅返回累计收发字节数，速率由 DataScheduler 从 Goform thrpt 获取）
     */
    fun getTrafficStats(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        try {
            val rxBytes = TrafficStats.getTotalRxBytes()
            val txBytes = TrafficStats.getTotalTxBytes()

            result["rx_bytes"] = rxBytes
            result["tx_bytes"] = txBytes
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to get traffic stats", e)
        }
        return result
    }

    /**
     * 获取系统运行时间
     */
    fun getUptime(): Map<String, Any> {
        val uptimeMs = SystemClock.elapsedRealtime()
        val uptimeSeconds = uptimeMs / 1000
        return mapOf(
            "uptime_seconds" to uptimeSeconds,
            "uptime_display" to formatUptime(uptimeSeconds)
        )
    }

    /**
     * 获取 Core 进程首次启动的绝对 epoch 毫秒（UTC）。
     * 用于监控页"自定义时间范围"日期选择下限（防止选早于后端记录的日期导致空查询）。
     */
    fun getStartupTime(): Long = processStartedAt

    // --- 内部方法 ---

    /**
     * 读取 CPU 使用率（两次采样取差值）。
     * 使用协程 delay 而非 shell sleep，避免阻塞 Dispatchers.IO 线程。
     *
     * 直接读取 /proc/stat（本地文件，无需 fork shell 进程），
     * 避免每次采样都调用 ShellQoS.execute() 创建子进程的开销。
     * 低端设备上进程创建成本极高，而文件读写在 IO 线程上几乎无开销。
     */
    private suspend fun readCpuUsage(): Double = withContext(Dispatchers.IO) {
        try {
            val stat1 = File("/proc/stat").readLines()
                .firstOrNull { it.startsWith("cpu ") } ?: return@withContext 0.0
            kotlinx.coroutines.delay(200)
            val stat2 = File("/proc/stat").readLines()
                .firstOrNull { it.startsWith("cpu ") } ?: return@withContext 0.0

            // 使用 \\s+ 分隔以适应多空格/制表符对齐的 /proc/stat 格式
            val values1 = stat1.substring(4).trim().split("\\s+".toRegex()).map { it.toLongOrNull() ?: 0 }
            val values2 = stat2.substring(4).trim().split("\\s+".toRegex()).map { it.toLongOrNull() ?: 0 }

            if (values1.size < 4 || values2.size < 4) return@withContext 0.0

            val idle1 = values1[3]
            val idle2 = values2[3]
            val total1 = values1.sum()
            val total2 = values2.sum()

            val totalDiff = (total2 - total1).toDouble()
            val idleDiff = (idle2 - idle1).toDouble()

            return@withContext if (totalDiff > 0) {
                ((totalDiff - idleDiff) / totalDiff) * 100.0
            } else 0.0
        } catch (e: Exception) {
            0.0
        }
    }

    private fun formatFrequency(khz: Long): String {
        return when {
            khz >= 1_000_000 -> "%.2f GHz".format(khz / 1_000_000.0)
            khz >= 1_000 -> "%d MHz".format(khz / 1_000)
            else -> "$khz KHz"
        }
    }

    /**
     * 获取网络连接数（直接读 /proc/net/ 下各协议文件，world-readable，无需 shell）
     */
    fun getConnectionCounts(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        try {
            result["tcp"] = countProcNetLines("/proc/net/tcp")
            result["tcp6"] = countProcNetLines("/proc/net/tcp6")
            result["udp"] = countProcNetLines("/proc/net/udp")
            result["udp6"] = countProcNetLines("/proc/net/udp6")
            result["unix"] = countProcNetLines("/proc/net/unix")
        } catch (e: Exception) { AppLogger.e(tag, "Failed to get connection counts", e) }
        return result
    }

    /** 统计 /proc/net/ 协议文件行数（减去 header 行） */
    private fun countProcNetLines(path: String): Int {
        return try {
            (File(path).readLines().size - 1).coerceAtLeast(0)
        } catch (_: Exception) { 0 }
    }

    /**
     * 获取蜂窝数据用量（直接读 /sys/class/net/{iface}/statistics/，无需 root）
     * 汇总所有非 lo 接口的 rx/tx 字节数
     */
    fun getCellularDataUsage(startTime: Long = 0, endTime: Long = 0): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        try {
            var rx = 0L; var tx = 0L
            val netDir = File("/sys/class/net")
            if (netDir.exists()) {
                netDir.listFiles()?.forEach { iface ->
                    if (iface.name == "lo") return@forEach
                    val rxFile = File(iface, "statistics/rx_bytes")
                    val txFile = File(iface, "statistics/tx_bytes")
                    if (rxFile.exists()) rx += rxFile.readText().trim().toLongOrNull() ?: 0
                    if (txFile.exists()) tx += txFile.readText().trim().toLongOrNull() ?: 0
                }
            }
            result["rx_bytes"] = rx; result["tx_bytes"] = tx; result["total_bytes"] = rx + tx
        } catch (e: Exception) { AppLogger.e(tag, "Failed to get data usage", e) }
        return result
    }

    /**
     * 获取所有热区温度（直接遍历 /sys/class/thermal/ 目录，无需 root shell）
     * F50 SELinux permissive 下各 thermal_zone 的 temp 和 type 均为 world-readable
     */
    fun getThermalZones(): List<Map<String, Any>> {
        val zones = mutableListOf<Map<String, Any>>()
        try {
            val thermalDir = File("/sys/class/thermal")
            if (!thermalDir.exists()) return zones
            thermalDir.listFiles()
                ?.filter { it.name.startsWith("thermal_zone") }
                ?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 0 }
                ?.forEach { zone ->
                    val temp = try {
                        File(zone, "temp").readText().trim().toLongOrNull()?.div(1000.0) ?: 0.0
                    } catch (_: Exception) { 0.0 }
                    val name = try {
                        File(zone, "type").readText().trim()
                    } catch (_: Exception) { zone.name }
                    zones.add(mapOf("name" to name, "temperature" to temp))
                }
        } catch (_: Exception) {}
        return zones
    }

    private fun formatUptime(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return "${days}d ${hours}h ${minutes}m"
    }
}

@Serializable
data class CpuInfo(
    val usage_percent: Double,
    val core_count: Int,
    val cores: List<CpuCore>,
    val temperature: Double = 0.0
)

/**
 * 精简 CPU 信息，仅保留必要字段供 StateFlow 常驻内存。
 * 不含各核频率详情（cores），完整 CpuInfo 通过 WebSocket 广播或 getCpuInfo() 获取。
 */
data class CpuInfoLite(
    val usage_percent: Double,
    val core_count: Int,
    val temperature: Double
)

@Serializable
data class CpuCore(
    val core: Int,
    val freq_mhz: Double,
    val freq_display: String = ""
)

@Serializable
data class MemoryInfo(
    val total: Long,
    val used: Long,
    val available: Long,
    val free: Long,
    val buffers: Long,
    val cached: Long,
    val usage_percent: Double
)

data class StorageInfo(
    val used: Long,
    val total: Long,
    val available: Long
)

data class BatteryInfo(
    val level: Int,
    val isCharging: Boolean,
    val temperature: Float,
    val voltage: Float
)

data class TemperatureInfo(
    val zones: List<TemperatureZone>
)

data class TemperatureZone(
    val name: String,
    val temperature: Float
)
