package com.ufi_axis_core.util

import java.io.File

/**
 * `/sys/class/thermal/thermal_zone<N>/temp` 的**唯一**读法（2026-09-25 监控采集缺陷 C）。
 *
 * （注：KDoc 里刻意写 `<N>` 而不是通配号 —— 那个写法会带出 `*` 加斜杠，直接把块注释提前闭掉。）
 *
 * ## 为什么会有这个文件：core 内原来有两份热区读法，且好的那份不进 cpu_history
 *
 * - `SprdPlatform.readTemperature()`：**遍历全部热区取最大值**，有 `exists()` / `canRead()`
 *   守卫、每热区独立 try。它只服务两件事 —— 下载限速的温控熔断、`scanLocalAlerts()` 的温度告警。
 * - `SystemCollector.getCpuInfo()`：**只读 `thermal_zone0`**，读不到就留 `temperature = 0.0`，
 *   而这一份才是写进 `cpu_history.temperature`、发上 WS `cpu` 频道、进监控中心温度格的那个值。
 *
 * 于是同一台设备上「告警看到的温度」和「图表里的温度」是两个数（Unisoc 上稳定差数度），
 * 且一旦这台设备的 CPU 热区不是 zone0（`thermal_zone0` 很多平台是电池或外壳），
 * 监控中心的温度格就恒为「暂无数据」—— 但温控熔断那边一直是好的，所以没人怀疑读法。
 *
 * 收敛到本对象之后：**两个调用点共用同一次遍历逻辑**，不可能再各自演化。
 *
 * ## 放在 `:core:common` 而不是就近放在某一侧
 *
 * 两个调用点分属 `:core:collector` 与 `:core:device-plugins`，而 plugins → collector 的依赖边
 * **不存在也不该存在**（依赖方向是 plugins 依赖 spi/goform/common，反向是硬纪律里禁止的）。
 * `:core:common` 是两侧**都已经**依赖的模块（collector 见其 build.gradle 第一行，
 * plugins 为了 `AppLogger` / `decodeServiceCallText` 也依赖它），所以放这里不新增任何依赖边。
 * 口径与同目录的 [celsiusToMilliC] 一致：纯逻辑 + 纯函数 + 可裸 JVM 单测。
 *
 * ## 出数用**毫摄氏度 Long**，不是摄氏度 Float
 *
 * 两个调用点的目标精度不同：`SprdPlatform` 要 `Float`（`PlatformAdapter` 契约），
 * `SystemCollector` 要 `Double`（`CpuInfo.temperature` / `cpu_history.temperature`）。
 * 如果本对象先折成 `Float` 再让 collector 侧 `.toDouble()`，`45123` 会变成
 * `45.12300109863281` 而不是原来的 `45.123` —— 那是一次没人要求的、会写进数据库的精度回退。
 * 所以本对象只出 sysfs 里的**原始毫度整数**，各自按自己的目标类型除 1000f / 1000.0。
 *
 * 地板夹 0 的语义因此落在毫度域（`maxMilliC` 从 `0L` 起）。与原 `Float` 域的
 * `maxTemp = 0f` + `if (tempC > maxTemp)` 逐值等价 —— `milli / 1000f` 是单调映射。
 *
 * ## 从 `SprdPlatform.readTemperature()` 逐条搬过来的坑（一条都不许凭记忆重写）
 *
 * 1. 先 `File(root).exists()` 再 `listFiles()`，按 `thermal_zone` **前缀**过滤
 *    —— 直接 `listFiles()` 在无 thermal 子系统的机型上会反复抛 ENOENT，
 *    日志会被 30 行堆栈 × 每轮刷爆（`SystemCollector` 里 2026-08-11 那条注释记的就是这个事故）；
 * 2. 每个热区**单独** try —— 一个热区读不动（EIO / 热区热插拔消失）不影响其余热区，
 *    「部分热区损坏」时给出其余热区的真实最大值而不是 0；
 * 3. 读之前查 `canRead()`，并**区分** `exists()` 为 false（节点不存在）与 true（权限挡住）
 *    —— 这两件事在真机上的处置完全不同，合成一句「读不到」等于把排障成本推给下一个人；
 * 4. 内容 `toLongOrNull() ?: 0L`，**并且仍然计入「读到了」**：0 不会抬升 max，
 *    但「文件能读、内容不是数字」与「文件读不了」是两种设备事实，不能合并；
 * 5. `maxMilliC` 从 `0L` 起、只在更大时抬升 ⇒ **负数读数被地板夹成 0**
 *    （某些内核在热区未就绪时写 `-1`）。这是原实现的行为，跟着搬；
 * 6. 整段外面再包一层 try，异常一律当「读不到」返回 —— 本函数**永不抛**，
 *    调用方不需要也不应该再包一层 try。
 *
 * ## ⚠ 上面第 4、5 条已被 2026-09-25 的 P3-3 推翻（原文保留，供对照）
 *
 * 那两条是从 `SprdPlatform` 搬来的**原实现行为**，而它们正是一个缺陷：
 * 「热区全部可读但内容不是数字」与「热区读数全是 -1」这两种情况下，`zonesRead` 照加，
 * 于是 [readMax] 返回 `maxMilliC = 0L` 而**不是** `null`。两个调用点都靠 `null` 决定
 * 要不要打 WARN，于是这两种情况**一条日志都不打**；而 `0`（毫度）对下游是「最凉」哨兵，
 * 下载的温控熔断与温度告警会一起**静默失效** —— 越热越不熔断，且没人看得出来。
 *
 * 现在的判据：**只有 `toLongOrNull() != null` 且 `milli > 0` 才算一次有效读数**
 * （才计入 [Reading.zonesRead]、才参与取最大值），其余一律计入 failures 并进 [Reading.detail]。
 * 「非数字」「读数为负」「读数为 0」三种事实在 detail 里**分开表述**，不合成一句
 * 「读不到」—— 它们在真机上的处置完全不同（第 3 条那句话同样适用于这里）。
 * 因此第 5 条的「负数被地板夹成 0」不再成立：负数现在是一次**失败**的读数，
 * 全部热区都是负数时 `maxMilliC == null`。
 *
 * ## 两个出口：[readAll] 逐热区、[readMax] 折叠成一个标量
 *
 * [readAll] 是底层那一遍遍历（exists / listFiles / canRead / 解析 / 每热区独立 try 全在它里面），
 * [readMax] 只是在它之上做一次折叠。`GET /api/system/thermal` 需要**分热区**展示，
 * 走 [readAll]；`cpu_history.temperature` 与温控熔断只要一个标量，走 [readMax]。
 *
 * 这条分工是 2026-09-25 P3-12 加的：在那之前 `SystemCollector.getThermalZones()` 自己
 * 又写了一遍 `listFiles()` + `thermal_zone` 前缀过滤 + `temp` 解析 + 静默折 0.0 ——
 * 于是「守卫与兜底口径」在 core 里仍然有两份，只是从两份变成了一份半。

 *
 * ## 为什么返回 [Reading] 而不是直接返回 `Long?`
 *
 * 缺陷 C 的第 4 条要求「读失败时打一条**带具体路径与原因**的 warn」（原来是静默 0.0），
 * 好让真机日志能分清「传感器读不到」与「采集循环死了」。但日志出口不该住在这里：
 * 本对象要能在裸 JVM 单测里跑（`AppLogger` 那边有 Android 依赖与全局开关），
 * 而且两个调用点对「读不到」的记法本来就不同（`SprdPlatform` 交给 `DataScheduler` 打，
 * `SystemCollector` 自己打）。所以本对象只**产出**诊断串，打不打、打成什么级别由调用方定。
 *
 * ⚠ [Reading.detail] 里**只拼设备结构性的事实**（路径、热区名、热区个数、异常类名），
 * 不拼读数、不拼时间戳 —— `AppLogger.repeatGate` 按「级别 + tag + 完整消息」折叠成 1 条/分钟，
 * 拼进每轮都变的值会让折叠键基数无界、折叠失效（计划书 §15 的 P1-35）。
 * 同一台设备上本串是稳定的，所以折叠照常生效。
 */
object ThermalZones {

    /** 热区根目录。真机路径，测试里由 [readMax] 的参数覆盖。 */
    const val ROOT_PATH = "/sys/class/thermal"

    /** 热区目录前缀。`thermal_zone0` / `thermal_zone1` … */
    private const val ZONE_PREFIX = "thermal_zone"

    /** 每个热区下的读数文件名，内容是**毫摄氏度**整数。 */
    private const val TEMP_FILE = "temp"

    /** 诊断串里最多列几条失败原因：热区多的平台（十几个）全列出来就成了日志噪音。 */
    private const val MAX_FAILURES_IN_DETAIL = 3

    /**
     * 一次热区遍历的结果。
     *
     * @param maxMilliC 全部**有效读数**（解析成功且 > 0）里的最大毫摄氏度；
     *   **一个有效读数都没有时为 `null`** —— 两个调用点都靠它决定要不要打 warn。
     *   2026-09-25 P3-3 之前这里写的是「最大毫摄氏度（地板夹 0）」并把
     *   「读到了但都 ≤ 0」算成读到了，于是那种情况回 `0L` 而不是 `null` —— 见类注释那一节。

     * @param zonesSeen 目录里匹配 `thermal_zone*` 的热区个数（不代表读成功）。
     * @param zonesRead 其中 `temp` 真的读出来了的个数。
     * @param detail 给日志用的诊断串，见类注释里关于折叠键的警告。
     */
    data class Reading(
        val maxMilliC: Long?,
        val zonesSeen: Int,
        val zonesRead: Int,
        val detail: String,
    )

    /**
     * **一个**热区的一次读数（2026-09-25 P3-12）。
     *
     * @param dir 热区目录本身。给调用方读同级的其他节点用（`GET /api/system/thermal` 要读
     *   `type` 拿热区名）—— 这是把 `SystemCollector.getThermalZones()` 那份重复遍历
     *   收进本对象的前提：它仍然保留自己的 `type` 读取与数字序排序，只是不再自己遍历。
     * @param name 目录名（`thermal_zone0` / `thermal_zone1` …）。
     * @param milliC `temp` 里解析出来的**原始**毫度整数。读不到或不是数字时 `null`；
     *   **负数与 0 照原样给出**（不夹 0、不抹成 null）—— `/api/system/thermal` 的对外取值
     *   历来是「解析得到就原样除 1000」，改掉它就是改线上契约。
     *   「这次读数算不算有效」看 [failure] / [valid]，不看本字段。
     * @param failure 这一次**不算有效读数**的原因；有效时 `null`。
     *   口径见类注释 P3-3 那一节：非数字 / 为负 / 为 0 / 不可读 / 不存在 / 异常，各自一句。
     */
    data class ZoneReading(
        val dir: File,
        val name: String,
        val milliC: Long?,
        val failure: String?,
    ) {
        /** 有效读数 = 解析出来了**而且** > 0。 */
        val valid: Boolean get() = failure == null
    }

    /**
     * 一次目录遍历的原始结果（2026-09-25 P3-12）。
     *
     * @param zones 逐热区读数，按目录名排序。[rootFailure] 非 null 时必为空。
     * @param rootFailure **目录层**就失败了的原因（根目录不存在 / 列不出目录 / 其下没有热区 /
     *   遍历异常）。这一档与「热区读不到」是两件事：前者说明这台机器压根没有 thermal 子系统
     *   或被 SELinux 挡在目录层，后者说明有热区但读不动。
     */
    data class Scan(
        val zones: List<ZoneReading>,
        val rootFailure: String?,
    )

    /**
     * 遍历全部热区，返回**逐热区**读数。**不抛异常**，见类注释第 6 条。
     *
     * 这是本对象唯一那一遍遍历：exists / listFiles / 前缀过滤 / canRead / 解析 /
     * 每热区独立 try 全在这里，[readMax] 只是在它之上折叠。
     *
     * @param rootPath 热区根目录。默认 [ROOT_PATH]；单测传临时目录 ——
     *   这是本函数唯一为可测性留的口子，生产调用点一律用默认值。
     */
    fun readAll(rootPath: String = ROOT_PATH): Scan {
        return try {
            val root = File(rootPath)
            // exists() 先行：见类注释第 1 条（无 thermal 子系统的机型上 ENOENT 会刷爆日志）
            if (!root.exists()) {
                return Scan(emptyList(), "$rootPath 不存在（本机型无 thermal 子系统，或被 SELinux 挡在目录层）")
            }
            val zones = root.listFiles()?.filter { it.name.startsWith(ZONE_PREFIX) }
                ?: return Scan(emptyList(), "$rootPath 无法列目录（listFiles() 返回 null：不是目录或无读权限）")
            if (zones.isEmpty()) {
                return Scan(emptyList(), "$rootPath 存在但其下没有 $ZONE_PREFIX* 节点")
            }
            Scan(zones.sortedBy { it.name }.map { readZone(it) }, null)
        } catch (e: Exception) {
            // 兜底：见类注释第 6 条。走到这里说明连 File/listFiles 层面都炸了
            Scan(emptyList(), "$rootPath 遍历异常（${e.javaClass.simpleName}）")
        }
    }

    /**
     * 单个热区的 `temp`。**永不抛**（见类注释第 2 条：一个热区读不动不影响其余热区）。
     *
     * 判有效的口径见类注释 P3-3 那一节：`toLongOrNull() != null` **且** `> 0`。
     */
    private fun readZone(zone: File): ZoneReading {
        return try {
            val tempFile = File(zone, TEMP_FILE)
            if (!tempFile.canRead()) {
                // 区分「节点不存在」与「存在但没权限」：见类注释第 3 条
                // （0400 root:root 的场景由 canRead() 挡在这里，是正确行为）
                return ZoneReading(
                    zone, zone.name, null,
                    if (tempFile.exists()) {
                        "${zone.name}/$TEMP_FILE 存在但不可读（权限/SELinux）"
                    } else {
                        "${zone.name}/$TEMP_FILE 不存在"
                    },
                )
            }
            val milli = tempFile.readText().trim().toLongOrNull()
            when {
                // 「文件能读、内容不是数字」是一种独立的设备事实，不与「读不了」合并
                milli == null ->
                    ZoneReading(zone, zone.name, null, "${zone.name}/$TEMP_FILE 内容不是数字")
                // 某些内核在热区未就绪时写 -1。原样留在 milliC 里（对外契约），但不算有效读数
                milli < 0L ->
                    ZoneReading(zone, zone.name, milli, "${zone.name}/$TEMP_FILE 读数为负（热区未就绪，某些内核写 -1）")
                // 0 毫度 = 0°C。真机上它是「没就绪」而不是「真的 0 度」，且下游把 0 当「最凉」哨兵
                milli == 0L ->
                    ZoneReading(zone, zone.name, milli, "${zone.name}/$TEMP_FILE 读数为 0（热区未就绪或无效）")
                else -> ZoneReading(zone, zone.name, milli, null)
            }
        } catch (e: Exception) {
            // 只记异常**类名**，不记 message（message 里常带 errno/路径变体，
            // 拼进折叠键会让 AppLogger.repeatGate 的折叠失效）
            ZoneReading(zone, zone.name, null, "${zone.name}/$TEMP_FILE 读取异常（${e.javaClass.simpleName}）")
        }
    }

    /**
     * 遍历全部热区，返回最大读数。**不抛异常**，见类注释第 6 条。
     *
     * 只是 [readAll] 之上的一次折叠：有效读数（解析成功且 > 0）里取最大值，
     * 一个有效读数都没有时 `maxMilliC == null`。
     *
     * @param rootPath 热区根目录。默认 [ROOT_PATH]；单测传临时目录 ——
     *   这是本函数唯一为可测性留的口子，生产调用点一律用默认值。
     */
    fun readMax(rootPath: String = ROOT_PATH): Reading {
        val scan = readAll(rootPath)
        // 目录层就失败了：zonesSeen / zonesRead 都是 0，detail 用目录层那句
        scan.rootFailure?.let { return Reading(null, 0, 0, it) }

        val zones = scan.zones
        val valid = zones.filter { it.valid }
        val failures = zones.mapNotNull { it.failure }
        return if (valid.isNotEmpty()) {
            val detail = if (failures.isEmpty()) {
                "$rootPath 下 ${zones.size} 个热区全部读取成功"
            } else {
                "$rootPath 下 ${zones.size} 个热区读到 ${valid.size} 个，" +
                    "其余失败：${failures.take(MAX_FAILURES_IN_DETAIL).joinToString("；")}"
            }
            // valid 里的 milliC 一定非空且 > 0（见 ZoneReading.valid）
            Reading(valid.maxOf { it.milliC ?: 0L }, zones.size, valid.size, detail)
        } else {
            Reading(
                null, zones.size, 0,
                "$rootPath 下 ${zones.size} 个热区全部读不到：" +
                    failures.take(MAX_FAILURES_IN_DETAIL).joinToString("；"),
            )
        }
    }
}

