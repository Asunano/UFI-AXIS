package com.ufi_axis_core.collector.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.SystemClock
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import com.ufi_axis_core.util.ShellQoS
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
            
            val tempMilli = File("/sys/class/thermal/thermal_zone0/temp")
                .readText().trim().toLongOrNull() ?: 0
            temperature = tempMilli / 1000.0
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
     */
    suspend fun getMemoryInfo(): MemoryInfo {
        var total = 0L
        var free = 0L
        var available = 0L
        var buffers = 0L
        var cached = 0L
        try {
            val memInfo = ShellExecutor.readSystemFile("/proc/meminfo")
            if (memInfo != null) {
                val lines = memInfo.lines()
                val memMap = mutableMapOf<String, Long>()
                for (line in lines) {
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
            }
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

            result["level"] = level
            result["scale"] = scale
            result["percent"] = if (level >= 0 && scale > 0) level * 100 / scale else -1
            result["temperature"] = temperature / 10.0  // 0.1°C 单位
            result["voltage"] = voltage / 1000.0  // mV 转 V
            result["is_charging"] = status == BatteryManager.BATTERY_STATUS_CHARGING
            result["plugged"] = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "None"
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to get battery info", e)
        }
        return result
    }

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

    suspend fun getConnectionCounts(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        try {
            // Batch: 5 shell calls → 1
            val cmd = """echo "tcp $(($(wc -l < /proc/net/tcp 2>/dev/null)-1)) tcp6 $(($(wc -l < /proc/net/tcp6 2>/dev/null)-1)) udp $(($(wc -l < /proc/net/udp 2>/dev/null)-1)) udp6 $(($(wc -l < /proc/net/udp6 2>/dev/null)-1)) unix $(($(wc -l < /proc/net/unix 2>/dev/null)-1))" 2>/dev/null"""
            val output = ShellQoS.executeCached(cmd).stdout.trim()
            // Parse: "tcp 5 tcp6 2 udp 3 udp6 0 unix 12"
            val parts = output.split(" ")
            var i = 0
            while (i + 1 < parts.size) {
                val key = parts[i]
                val value = parts[i + 1].toIntOrNull()?.coerceAtLeast(0) ?: 0
                result[key] = value
                i += 2
            }
        } catch (e: Exception) { AppLogger.e(tag, "Failed to get connection counts", e) }
        return result
    }

    suspend fun getCellularDataUsage(startTime: Long, endTime: Long): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        try {
            val stats = ShellExecutor.executeAsRoot("cat /proc/net/xt_qtaguid/stats 2>/dev/null")
            if (stats.isSuccess) {
                var rx = 0L; var tx = 0L
                stats.stdout.lines().forEach { line ->
                    if (line.startsWith(" ")) return@forEach
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 10) {
                        rx += parts[6].toLongOrNull() ?: 0
                        tx += parts[8].toLongOrNull() ?: 0
                    }
                }
                result["rx_bytes"] = rx; result["tx_bytes"] = tx; result["total_bytes"] = rx + tx
            } else {
                // Batch: getprop + 2x cat → 1 shell call
                val iface = ShellExecutor.execute("getprop wifi.interface 2>/dev/null").stdout.trim()
                    .ifEmpty { "wlan0" }
                val batchCmd = "cat /sys/class/net/$iface/statistics/rx_bytes 2>/dev/null; echo '|SEP|'; cat /sys/class/net/$iface/statistics/tx_bytes 2>/dev/null"
                val batchResult = ShellQoS.executeCached(batchCmd)
                val segments = batchResult.stdout.split("|SEP|")
                result["rx_bytes"] = segments.getOrNull(0)?.trim()?.toLongOrNull() ?: 0
                result["tx_bytes"] = segments.getOrNull(1)?.trim()?.toLongOrNull() ?: 0
            }
        } catch (e: Exception) { AppLogger.e(tag, "Failed to get data usage", e) }
        return result
    }

    suspend fun getThermalZones(): List<Map<String, Any>> {
        val zones = mutableListOf<Map<String, Any>>()
        try {
            // Batch: 2N shell calls → 1 (read all zones in a single script)
            val dollar = '$'
            val cmd = "for f in /sys/class/thermal/thermal_zone*/temp; do echo \"${dollar}(cat ${dollar}f 2>/dev/null)\"; done; echo '---NAMES---'; for f in /sys/class/thermal/thermal_zone*/type; do echo \"${dollar}(cat ${dollar}f 2>/dev/null)\"; done"
            val result = ShellQoS.executeAsRootCached(cmd)
            val parts = result.stdout.split("---NAMES---")
            val temps = parts.getOrNull(0)?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            val names = parts.getOrNull(1)?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            for (i in temps.indices) {
                val name = names.getOrNull(i)?.trim() ?: "zone$i"
                val temp = temps[i].trim().toLongOrNull()?.div(1000.0) ?: 0.0
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
