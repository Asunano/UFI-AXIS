package com.ufi_axis_core.collector.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.SystemClock
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ThermalZones
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
     *
     * @param takenAt **单调时钟**读数（`SystemClock.elapsedRealtime()`），不是 epoch 毫秒。
     *   2026-09-25 从 `System.currentTimeMillis()` 换过来，理由见 [isCpuSnapshotFresh]。
     */
    private data class CpuSnapshot(val info: CpuInfo, val takenAt: Long)

    @Volatile private var cpuSnapshot: CpuSnapshot? = null

    /**
     * 获取 CPU 信息：总使用率 + 各核频率 + 温度（全热区最大值）
     *
     * ## 5s 缓存为什么留着（2026-09-25 复核结论：保留）
     *
     * 一次 miss 的代价是 [readCpuUsage] 里**两次 `/proc/stat` 采样之间那 200ms 的 `delay`**，
     * 而本方法的调用方有四路且互不相让：采集主循环（3s 一轮）、性能监控循环（60s 一轮，
     * 用它算降频档与温控熔断）、`/api/system` 下的路由（客户端按需，可并发多个）、
     * `SmsForwardController` 的日报。没有这层缓存，几个消费者会各跑一次 200ms 双采样，
     * 且 `/proc/stat` 差值窗口互相重叠 —— 读数本身也会因此更抖。
     *
     * ⚠ 已知取舍，**本轮刻意不动**：TTL(5s) > 采样周期(3s)，所以采集主循环每隔一轮就会
     * 命中缓存，`cpu_history` 里会出现成对的重复点（第 2、4、6… 点与前一点完全相同）。
     * 把 TTL 调到 3s 以下能消掉它，但那会改变历史数据的密度与并发保护的强度，
     * 属于「采样语义变更」，要单独一次决策，不该夹在这次缺陷修复里顺手带出来。
     */
    suspend fun getCpuInfo(): CpuInfo {
        // ⚠ 单调时钟，**不许**换回 System.currentTimeMillis()：理由见 isCpuSnapshotFresh
        val now = SystemClock.elapsedRealtime()
        // ── CPU 信息缓存：5s 内直接返回上一次结果，减少 sysfs 读取压力 ──
        val cached = cpuSnapshot
        if (cached != null && isCpuSnapshotFresh(now, cached.takenAt)) {
            return cached.info
        }
        var cpuUsage = 0.0
        val cores = mutableListOf<CpuCore>()
        var temperature = 0.0
        // ── 阻塞 sysfs 读整段切 IO（2026-09-25 P3-9）──
        // 下面这段全是同步文件读：cpufreq 是**每核一个**文件，热区从缺陷 C 之后是**每热区一个**
        // （zone 多的平台十几个），加起来一轮十几到几十次 sysfs 读。
        // 而 `GET /api/system/cpu`（SystemRoutes）是直接在 Ktor 的请求协程里调本方法的 ——
        // 不切就等于在 Netty 的事件循环线程上做阻塞 IO。
        // 同文件 readCpuUsage() 与 SprdPlatform.readTemperature() 已经是这个口径；
        // readCpuUsage() 内部自带一层 withContext(IO)，嵌套在同一个调度器上没有额外代价。
        withContext(Dispatchers.IO) {
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

                // ── 温度：遍历**全部**热区取最大值（2026-09-25 缺陷 C）──
                // 原来这里只读 `/sys/class/thermal/thermal_zone0/temp`，读不到就静默留 0.0。
                // 两个问题：
                //   1. zone0 在很多平台压根不是 CPU（可能是电池/外壳/PMIC），CPU 热区在 zone3 之类的
                //      机型上，监控中心温度格因此恒为「暂无数据」；而 core 内部**另有**一份遍历全热区
                //      取最大值的实现（`SprdPlatform.readTemperature()`）只服务温控熔断与温度告警，
                //      不进 cpu_history —— 同一台设备上「告警看到的温度」和「图表里的温度」是两个数。
                //   2. 读失败完全静默 ⇒ 真机上分不清「传感器读不到」与「采集循环死了」，
                //      而这两件事的处置完全不同（后者是缺陷 A）。
                // 现在两个调用点共用 `ThermalZones.readMax()`（:core:common，单一真源，有单测），
                // 读不到时打一条带**具体路径与原因**的 WARN。
                //
                // 单位：readMax 出的是 sysfs 原始毫度 Long，这里除 1000.0（不经 Float），
                // 与原来的 `tempMilli / 1000.0` 逐值等价 —— 精度一点没变，数据库里的值不会跳。
                val thermal = ThermalZones.readMax()
                val milliC = thermal.maxMilliC
                if (milliC != null) {
                    temperature = milliC / 1000.0
                } else {
                    // schema 不变：`cpu_history.temperature` 仍是非空 Double，读不到仍写 0.0。
                    // 但**不许再静默**。文案里只有 ThermalZones 给的结构性事实（路径/热区名/个数），
                    // 不拼读数与时间 —— AppLogger.repeatGate 按「级别+tag+完整消息」折叠成 1 条/分钟。
                    AppLogger.w(tag, "CPU 温度读不到，本帧按 0.0 记录：${thermal.detail}")
                }
            } catch (e: Exception) {
                AppLogger.e(tag, "Failed to get CPU info", e)
            }
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
     * CPU 快照是否还在 5s 有效期内（2026-09-25 缺陷 B）。
     *
     * ## 为什么必须用单调时钟
     *
     * 原实现用 `System.currentTimeMillis()` 做 `takenAt`，再算 `now - takenAt`：
     * NTP 校时或用户手动改时间会让墙上时钟**往回跳**，于是 `now - takenAt` 恒为负，
     * 恒小于 5000 ⇒ 每次都命中缓存 ⇒ 永远返回同一份旧快照、永不刷新。
     * CPU 与温度（同一行数据）就此**冻结**；若冻结的那帧 `temperature == 0.0`，
     * 监控中心温度格直接变「暂无数据」—— 必须重启 core 才恢复。
     *
     * `SystemClock.elapsedRealtime()` 从开机起单调递增，不受 NTP / 手动改时影响。
     * 只要 now >= takenAt（单调时钟保证），`now - takenAt` 就是真实经过的毫秒，
     * 缓存该过期就过期、该命中就命中。
     *
     * ## 防御性兜底
     *
     * 即便出现异常的负差值（理论上不该出现 —— `elapsedRealtime` 是单调的，
     * 但「不该出现」正是原来那个 bug 的写照），也判「过期」而不是「新鲜」：
     * 最坏情况是多读一次 sysfs，不会冻结。
     *
     * ## 为什么在 companion object 里、为什么是 internal
     *
     * 2026-09-25 从 `private` 实例方法提到 `internal companion object`：它是**纯函数**
     * （只看两个入参，不碰任何实例状态），提出来没有行为变化，但让单测可以直接断言真实实现。
     *
     * 此前 `core/src/test/.../SystemCollectorTest.kt` 里那四条用例是把这段逻辑**抄一份**
     * 到测试里再断言抄本（`val fresh = elapsed in 0 until 5_000L`），于是把本函数改回
     * `elapsed < 5_000L`（缺陷 B 的原始写法）那四条**仍然全绿** —— 等于没有回归保护。
     * 现在测试搬到了 `core/collector/src/test/`（与本文件同模块，`internal` 可见），
     * 直接调 `SystemCollector.isCpuSnapshotFresh(...)`。
     */
    internal companion object {
        internal fun isCpuSnapshotFresh(nowElapsed: Long, takenAt: Long): Boolean {
            val elapsed = nowElapsed - takenAt
            // 负差值 ⇒ 过期（防御性兜底，见上述 KDoc）；正常时 < 5s ⇒ 新鲜
            return elapsed in 0 until 5_000L
        }
    }

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
     *
     * 注：本方法是 `GET /api/system/thermal` 的数据来源，返回**全量热区**（名称 + 温度），
     * 与 [getCpuInfo] 里走 [ThermalZones.readMax] 取最大值不同，不做去重也不取 max。
     * 两者的底层文件相同（thermal_zone 系列的 temp），但消费场景不同：
     * thermal 详情页需要分热区展示，`getCpuInfo` 只需要一个标量。
     *
     * ## 2026-09-25 P3-12：遍历不再自己写一份
     *
     * 本方法原来自己 `listFiles()` + `thermal_zone` 前缀过滤 + `temp` 解析 + 静默折 0.0 ——
     * 与 [ThermalZones] 的遍历与兜底口径**重复一份**（缺陷 C 收敛的是 `getCpuInfo` 那一份，
     * 漏了这一份）。现在消费 [ThermalZones.readAll]：`exists()` / `listFiles()` / `canRead()`
     * 守卫与「解析不出来算 0」这些事只有一个归属地。
     *
     * 本方法**保留自己的两件事**：读 `type` 拿热区名（`readAll` 只管 `temp`）、
     * 按 `thermal_zoneN` 的**数字序**排序（`readAll` 是目录名的字典序，zone10 会排在 zone2 前）。
     *
     * ⚠ 对外 JSON 一字未变：仍是 `[{ "name": <type 或目录名>, "temperature": <摄氏度 Double> }]`，
     * 且 `temperature` 仍是「解析得到就原样 / 1000.0，解析不到写 0.0」——
     * 包含热区报 `-1` 时下发 `-0.001` 这个既有取值（那是 P3-3 在 [ThermalZones.readMax] 侧
     * 判无效的那种读数，但**本端点历来原样下发**，不跟着改）。
     *
     * 阻塞 sysfs 读整段切 IO：`readAll()` 加逐热区的 `type` 是十几次同步文件读，
     * 而 `GET /api/device/thermal` 是在 Ktor 请求协程里直接调本方法的（口径同 [getCpuInfo]）。
     */
    suspend fun getThermalZones(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        val zones = mutableListOf<Map<String, Any>>()
        val scan = ThermalZones.readAll()
        scan.zones
            .sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 0 }
            .forEach { zone ->
                // 解析不到（不存在 / 不可读 / 非数字 / 异常）→ milliC 为 null → 0.0，与原实现一致
                val temp = (zone.milliC ?: 0L) / 1000.0
                val name = try {
                    File(zone.dir, "type").readText().trim()
                } catch (_: Exception) { zone.name }
                zones.add(mapOf("name" to name, "temperature" to temp))
            }
        zones
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
