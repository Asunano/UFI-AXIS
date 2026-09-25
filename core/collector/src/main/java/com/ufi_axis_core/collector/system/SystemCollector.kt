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
 *
 * @param batteryDeclared **这个型号声明了 `Capability.BATTERY` 吗**（2026-09-24 批 M）。
 *   由装配层从 `runtime.plugin.capabilities` 算出来传进来。
 *
 *   ⚠ **它不参与任何读数计算**。整个类里它只有一个用途：如实填进 [getBatteryInfo] 返回的
 *   `supported` 字段，告诉客户端「这个型号声明了电池能力吗」。
 *   `percent` / `level` / `temperature` / `voltage` / `is_charging` / `plugged` / `scale`
 *   一律照三级取值跑出系统报的值，**不因为它是 false 而被抹掉**。
 *
 *   ⚠ 别把它当成批 L 那个 `batterySupported`：那个参数（裁决 C）会在 false 时整段短路、
 *   把 `percent` / `level` 抹成 -1。用户 2026-09-24 推翻了那个裁决（改成方案 D）——
 *   抹成 -1 之后 app 端会渲染出红色的 `-1%`，比假的 50% 更像故障。
 *   现在的口径是：**读数照原样下发，可信度另开一个字段说**。
 *
 *   ⚠ 这里刻意收一个**不可变的 Boolean**，不收整个 capabilities 集合、更不收选型对象：
 *   采集器需要的只是「这一个事实成不成立」，递进来一个集合等于让采集器自己去解释能力语义，
 *   以后谁往集合里加项都可能顺手在采集器里加分支。口径同批 B2。
 */
class SystemCollector(
    private val context: Context,
    private val batteryDeclared: Boolean,
) {

    private val tag = "SystemCollector"

    /**
     * Core 后端进程首次启动时间（绝对 epoch 毫秒）。构造时记录，整个进程生命周期不变。
     * 2026-08-08 12:09 新增：监控页"自定义时间范围"对话框需要限制最小日期 = Core 首次启动时间（防用户选早于后端记录的日期）。
     * 暴露 getStartupTime() 给 SystemRoutes 返回 /api/system/startup-time。
     */
    private val processStartedAt: Long = System.currentTimeMillis()

    /**
     * 缓存控制：CPU 频率和温度变化较慢，缓存有效期内直接返回上次结果。
     * 由 DataScheduler 每 3s 调用一次，但 sysfs 值实际变化周期远大于 3s。
     *
     * 时间戳与数据必须是**一个**不可变快照，且用 @Volatile 发布：
     * 原实现是两个普通 var（`lastCpuCacheTime` / `lastCpuCache`），读方有两路 —— 性能监控
     * 协程和 API 路由协程跑在不同线程上。两个字段分别写，读方可能看到「新时间戳配旧数据」
     * 甚至只看到时间戳的写入而看不到 CpuInfo 的写入（无 happens-before），
     * 那个 `lastCpuCache!!` 也就只是「碰巧没人写 null」才没炸。
     * 换成单个 @Volatile 引用后，一次写发布整个快照，读方拿到的时间戳和数据永远配对，!! 也不需要了。
     */
    private data class CpuSnapshot(val info: CpuInfo, val takenAt: Long)

    @Volatile private var cpuSnapshot: CpuSnapshot? = null

    /**
     * 获取 CPU 信息：总使用率 + 各核频率
     */
    suspend fun getCpuInfo(): CpuInfo {
        val now = System.currentTimeMillis()
        // ── CPU 信息缓存：5s 内直接返回上一次结果，减少 sysfs 读取压力 ──
        val cached = cpuSnapshot
        if (cached != null && (now - cached.takenAt) < 5_000L) {
            return cached.info
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
        cpuSnapshot = CpuSnapshot(info, now)
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
     * ## 三级取值，任一级拿到就用
     *
     *   1. sticky ACTION_BATTERY_CHANGED 的 EXTRA_LEVEL / EXTRA_SCALE
     *   2. BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)（framework 自己去问 HAL）
     *   3. /sys/class/power_supply/<x>/capacity（节点名各家不同，遍历所有 type=Battery 的目录）
     *
     * 温度/电压同样做兜底，并且**取不到就返回 -1，而不是 -1/10.0 = -0.1** —— 原来那个
     * -0.1°C / -0.001V 比 -1 更难认出来是"没取到"。
     *
     * ## 无电池机型的两种表现，都记下来（2026-09-24 事实更正）
     *
     * 这段原来写的是「UFI 设备的 sticky intent 不带 EXTRA_LEVEL / EXTRA_SCALE，于是 percent 恒为 -1」。
     * 那只描述了其中一种机型。实测确认**两种都存在**，而且第一种更难发现：
     *
     * 1. **sticky intent 带 level/scale，但值是假的** —— ZTE F50 就是这种：设备**没有电池**，
     *    系统却恒报 `level=50, scale=100`。于是上面第 1 级的 `level >= 0 && scale > 0` 成立，
     *    `percent` **恒为 50**，下面那条「读不到」的 WARN 压根不打。
     *    也就是说这台机器上没有任何「读不到」的信号 —— 靠读数本身分辨不出真假。
     * 2. **sticky intent 不带 level/scale** —— 另一种机型（本函数原注释描述的那种）：
     *    第 1 级不成立，两级兜底也拿不到，`percent` **恒为 -1**。
     *
     * ⚠ 别把这两种混成一句话。看到「恒为 -1」就以为无电池设备一定报 -1，
     * 会直接漏掉第 1 种（F50 那条永远 50% 的假曲线就是这么存进数据库的）。
     *
     * ## `supported` 字段：读数照发，可信度单独说（2026-09-24 批 M / 方案 D）
     *
     * 本函数**不因为 [batteryDeclared] 为 false 而改动任何读数** —— percent 就是系统报的值
     * （F50 上是 50）。抹成 -1 的做法（批 L 裁决 C）已被用户推翻：app 端会渲染出红色的 `-1%`，
     * 比假的 50% 更像故障。
     *
     * 取而代之，map 里多一个 `supported: Boolean`，如实转述装配层算出的
     * 「这个型号声明了 `Capability.BATTERY` 吗」。客户端拿它去决定要不要在电池详情里
     * 提示「可能无电池或检测不到」，**读数本身照原样渲染**。
     *
     * 为什么放在这张 map 里、而不是让客户端去读 `/api/device/capabilities`：
     *   - 客户端渲染电量卡片时手里**已经有这张 map** 了，为一个布尔再发一次请求不划算；
     *   - 「读数」与「读数可信吗」落在**同一个响应**里，不会出现两者不同步的窗口
     *     （分两个请求就一定有那个窗口：capabilities 先到、battery 后到，中间那一帧
     *     客户端只能拿旧的可信度去渲染新读数）。
     *
     * 新增键对旧客户端安全：app 侧走 `AppJson` 的 `ignoreUnknownKeys = true`，
     * web 侧是逐字段挑（批 B2 已核实）。
     */
    fun getBatteryInfo(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        // supported 在 try 之外先填：它不依赖任何一次读取，而恰恰是「读数可信吗」这个答案 ——
        // 下面任何一步抛异常（走到 catch、result 只剩半张表）时它都得在。
        result["supported"] = batteryDeclared
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
