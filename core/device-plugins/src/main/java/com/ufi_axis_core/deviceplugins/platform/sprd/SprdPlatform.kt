package com.ufi_axis_core.deviceplugins.platform.sprd

import com.ufi_axis_core.devicespi.AtTransport
import com.ufi_axis_core.devicespi.PlatformAdapter
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 展锐（Spreadtrum / UNISOC）平台适配层 —— 阶段 4 的 4.1。
 *
 * ## 为什么放在 `platform/sprd/` 而不是 `zte/f50/`
 *
 * 本模块的布局纪律是「一个 module、**每设备**一个 package」，隔离靠守门测试
 * （某设备的符号不许出现在别的设备 package 里）。而本类里没有一个字节是 ZTE 的知识 ——
 * 服务名、事务码、API 等级分档全是**展锐平台**的事实，
 * 别家的 Unisoc 随身 WiFi（同平台不同厂）会**原样复用**它。
 * 塞进 `zte/f50/` 的话，第二台 Unisoc 设备的插件就得 `import ...zte.f50.SprdPlatform` ——
 * 那正是上面那条守门纪律要拦的形状。所以平台适配单独一层 package：
 * **`zte/f50/` 放「这台设备是什么」，`platform/sprd/` 放「它跑在什么平台上」。**
 *
 * ## 落地到什么程度（批 F 起，批 G 更新）
 *
 * - [atTransports] **已经是真的**：返回唯一的 [AtTransport] 实现
 *   [ServiceCallAtExecutor]（批 F 从 `:core:collector` 搬过来）。
 *   `ATChannel` 不再自己 `new` 它，改为接收注入 —— 装配点在 `ComponentFactory`。
 * - [restartNetworkStack] **批 G 起是真的**：`AT+SFUN=5` / `AT+SFUN=4` 从
 *   `NetworkController` 逐字搬进来了（任务 4.4）。执行通道由调用方注入，
 *   理由见 [PlatformAdapter.restartNetworkStack] 的 KDoc。
 * - [readTemperature] **批 H 起是真的**：`DownloadManager.readMaxTemp()` 的读法逐字搬进来了
 *   （任务 4.3 的一半）。**批 I 起 `DataScheduler.readMaxCpuTemp()` 也改成调它** ——
 *   那一处不是等价搬迁而是行为变更（四点语义差异），见 [readTemperature] 的 KDoc。
 *
 * ## `/proc/cpuinfo` 的展锐判据**不在本类**（4.6，2026-09-24）
 *
 * 按「平台知识住 `platform/sprd/`」的纪律，那组 marker 本该在这里。它没有落在这里，
 * 原因只有一个：它的第二个消费者 `ATChannel`（`:core:collector`）**看不见本模块** ——
 * 「谁都不许 import 具体插件」是硬纪律，放这里等于继续留两份不一致的判据（那正是 4.6 要消掉的）。
 * 所以判据落在 `CpuInfoPlatform`（`:core:device-spi`，纯常量 + 纯函数），
 * 本模块的 `ZteF50Plugin.probe()` 与 `ATChannel` 共用同一份。完整理由见那个类的 KDoc。
 */
class SprdPlatform : PlatformAdapter {

    /**
     * ⚠ **这不是 `NetworkController` 那个 tag**。
     *
     * [restartNetworkStack] 的**日志文案**是从 `NetworkController` 逐字搬过来的，
     * 但 tag 跟着类走（口径同 [ServiceCallAtExecutor] 的 `"ServiceCallAt"`）——
     * 留着 `"NetworkController"` 会让 logcat 指向一个已经不发这两条命令的类。
     * 排障时按文案 `Restarting network stack` 搜即可，两边一字不差。
     */
    private val tag = "SprdPlatform"

    /**
     * ⚠ **取值沿用现有的对外字符串 `"SPREADTRUM"`，不是新起的 `"sprd"`。**
     *
     * 依据：`/api/at/platform` 与 `/api/at/status` 现在下发的 `platform` 键，
     * 取的是 `ATChannel.Platform.SPREADTRUM.name` —— 也就是**大写的 `SPREADTRUM`**
     * （`ATChannel.getPlatformInfo()` 里写的是 `platform.name`）。
     * 阶段 4 的 4.6 会把那个枚举退化成 adapter 内部细节、改由本字段供值，
     * 届时若本字段是 `"sprd"`，对外取值就会从 `SPREADTRUM` 静默变成 `sprd` ——
     * 那是一次没人要求的客户端适配。
     *
     * 换句话说：类名用 `Sprd`（Kotlin 侧的简称），**对外字符串保持 `SPREADTRUM`**。
     * 想改对外取值必须是一次显式的两端变更，不能由「顺手统一命名风格」带出来。
     */
    override val name: String = "SPREADTRUM"

    /**
     * 本平台的 AT 通道：只有 `service call` 一条。
     *
     * 每次调用**新建**一个 [ServiceCallAtExecutor]：它无内部状态
     * （`reset()` 是空实现），生命周期跟组件图而不是跟本对象 ——
     * 口径与 `DevicePlugin.createTransport()` 的「插件自己不缓存」一致。
     *
     * 2026-09-06 起 `sendat` 回落通道已删除，所以这里**不是**「按优先级返回多条」，
     * 而是确实只有一条。HAL 认不出来即视为本机 AT 通道不可用（由 `ATChannel.init()` 判定）。
     */
    override fun atTransports(): List<AtTransport> = listOf(ServiceCallAtExecutor())

    /**
     * 热区读数，**摄氏度** —— 任务 4.3 的一半，从 `DownloadManager.readMaxTemp()`
     * **逐字搬过来**（遍历 `/sys/class/thermal/thermal_zone*` 的 `temp`，取最大值）。
     *
     * 搬迁范围（路径集合、max 逻辑、异常处理一字未改）：
     * - 先 `File("/sys/class/thermal").exists()` 再 `listFiles()`，按 `thermal_zone` 前缀过滤；
     * - 每个热区**单独** `try` / `catch (_: Exception) {}` —— 一个热区读不动不影响其它热区；
     * - 读之前查 `canRead()`；内容 `toLongOrNull() ?: 0L`，再 `/ 1000f` 换算成摄氏度；
     * - `maxTemp` 从 `0f` 起，只在 `tempC > maxTemp` 时抬升 —— 所以**负数读数被地板夹成 0**
     *   （某些内核在热区未就绪时会写 `-1` 之类的值）。这条是原实现的行为，跟着搬；
     * - 整段外面再包一层 `try` / `catch`，异常一律当「读不到」。
     *
     * ## `null` 与 `0f` 的边界（与原实现的唯一差别，且在调用点上等价）
     *
     * 原实现返回 `Float`，「一个热区都读不到」与「读到了但都 ≤ 0」都返回 `0f`。
     * 本方法按 [PlatformAdapter.readTemperature] 的第 1 条硬约束区分开：
     * **一个 `temp` 都没读成功 → `null`**；读成功过至少一个 → 返回那个（地板夹到 0 的）最大值。
     * 调用点 `DownloadManager` 写的是 `?: 0f`，所以两种情形合并回 `0f`，**运行时取值逐路一致**。
     *
     * ## `DataScheduler.readMaxCpuTemp()` 批 I 起也走本方法（**行为变更，已裁决**）
     *
     * 那一处与本方法**不是同一个读法**（不是单位不同，是语义不同），所以批 H 没动它：
     * 它读的是**毫摄氏度 `Int` 原值**（不做 `/1000`）、没有 `exists()` / `canRead()` 守卫、
     * 只有一层外层 `try`（**任一热区抛异常 → 整轮读数退化成 0 并记一条 WARN**）、
     * 且 `maxOrNull()` **不夹地板**（可以返回负数）。
     *
     * 批 I 接上时这四点全部随本方法变，用户已裁决接受；`DataScheduler` 侧
     * `readMaxCpuTemp()` 的 KDoc 逐条记了每一点的实际影响。
     *
     * 取整那一条在批 I 有结论：**换算必须 `roundToInt()`，不许 `toInt()`**。
     * 实测 20~100°C 的 80001 个毫度值做「毫度 → `Float` → 毫度」往返，
     * `toInt()` 有 **555** 个少 1（最小的是 32002 → 32.002f → 32001.998f → 32001），
     * `roundToInt()` **0** 个不匹配。换算函数落在 `core/common` 的 `celsiusToMilliC()`（有单测）。

     *
     * ## 调度器
     *
     * 内部 `withContext(Dispatchers.IO)`：本方法做阻塞 sysfs 读。
     * 两个调用点（`DownloadManager.checkAndThrottle()` 与 `DataScheduler.readMaxCpuTemp()`）
     * 本来就跑在 `Dispatchers.IO` 上，所以这层包裹不切线程、对现状零影响；
     * 加它是为了让「谁来保证不在主线程读盘」这件事有唯一答案
     * —— 批 I 起 `DataScheduler` 那一份自己的 `withContext(Dispatchers.IO)` 已经删掉了。
     *
     * @return 摄氏度；一个热区都读不到时 `null`。
     */
    override suspend fun readTemperature(): Float? = withContext(Dispatchers.IO) {
        try {
            var maxTemp = 0f
            var anyZoneRead = false
            // 优先用 Java File I/O（无需 fork 进程，更快）
            val thermalDir = File("/sys/class/thermal")
            if (thermalDir.exists()) {
                thermalDir.listFiles()?.filter { it.name.startsWith("thermal_zone") }?.forEach { zone ->
                    try {
                        val tempFile = File(zone, "temp")
                        if (tempFile.canRead()) {
                            val milli = tempFile.readText().trim().toLongOrNull() ?: 0L
                            anyZoneRead = true
                            val tempC = milli / 1000f
                            if (tempC > maxTemp) maxTemp = tempC
                        }
                    } catch (_: Exception) {}
                }
            }
            if (anyZoneRead) maxTemp else null
        } catch (_: Exception) { null }
    }

    /**
     * 网络栈重启 —— 任务 4.4，从 `NetworkController.restartNetworkStack()` **逐字搬过来**。
     *
     * 参考 UFI-TOOLS-REF: networkStackSwitch()
     * `AT+SFUN=5` 关闭网络栈 → 等待芯片重新加载配置 → `AT+SFUN=4` 重启。
     * 这使得 goform 写入的频段限制无需设备重启即可生效。
     *
     * 搬迁范围（命令、顺序、等待、判据、日志文案一字未改）：
     * - 命令序列固定两条：先 `AT+SFUN=5`，再 `AT+SFUN=4`；
     * - 判成功的依据是**回显串含 `OK`**（`null` 与不含 `OK` 都算失败，立即返回 `false`）；
     * - 两条之间 `delay(500)`（等芯片重新加载配置），第二条之后 `delay(2000)`
     *   （等 modem 完全稳定）—— 这两个数是**设备事实**，所以跟着搬进来；
     * - 整段包 `try` / `catch`，异常一律记 ERROR 并返回 `false`。
     *
     * ## 没有跟着搬过来的两样，以及为什么
     *
     * 1. **并发互斥锁留在 `NetworkController`**。「同一时刻只许一次 `AT+SFUN`」是**我们的策略**，
     *    不是展锐平台的事实；口径同 `ATChannel` 的全局互斥 / 退避 / 熔断留在 `:core:collector`。
     *    而且 `DevicePlugin.platform(ctx)` 每次调用都新建一个本类实例（插件不缓存），
     *    锁放这里就是「每个实例各锁自己」= 等于没锁。
     * 2. **AT 超时值 5000ms 留在调用方**。本方法的执行器签名里没有超时参数：
     *    超时是通道策略（`ATChannel.sendCommand(cmd, timeoutMs)`），由持有通道的一侧给。
     *
     * ## 一处照搬的既有缺陷（本批刻意不修）
     *
     * `catch (e: Exception)` 会把 `CancellationException` 也吞掉 —— 调用方取消这次协程时，
     * 本方法会返回 `false` 而不是把取消传播出去（同批的 [ServiceCallAtExecutor] 是先
     * `catch CancellationException` 再 `throw` 的）。原实现就是这样，改它会改变取消语义，
     * 不属于「等价搬迁」，登记在此。
     *
     * @param at 由调用方注入的单条 AT 执行器，见 [PlatformAdapter.restartNetworkStack]。
     */
    override suspend fun restartNetworkStack(at: suspend (String) -> String?): Boolean {
        AppLogger.i(tag, "Restarting network stack (AT+SFUN=5/4)...")
        try {
            val off = at("AT+SFUN=5")
            if (off?.contains("OK") != true) {
                AppLogger.w(tag, "AT+SFUN=5 failed: $off, stack restart skipped")
                return false
            }
            AppLogger.i(tag, "Network stack off, waiting 500ms...")
            delay(500)
            val on = at("AT+SFUN=4")
            if (on?.contains("OK") != true) {
                AppLogger.w(tag, "AT+SFUN=4 failed: $on")
                return false
            }
            AppLogger.i(tag, "Network stack restarted, waiting 2s for modem stabilization...")
            delay(2000)
            AppLogger.i(tag, "Network stack restart complete")
            return true
        } catch (e: Exception) {
            AppLogger.e(tag, "Network stack restart exception: ${e.message}")
            return false
        }
    }
}
