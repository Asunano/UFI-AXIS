package com.ufi_axis_core.deviceplugins.platform.sprd

import com.ufi_axis_core.devicespi.AtTransport
import com.ufi_axis_core.devicespi.PlatformAdapter
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ThermalZones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
     * 热区读数，**摄氏度** —— 2026-09-25 改为委托 [ThermalZones.readMax]（单一真源）。
     *
     * 原实现（2026-09-24 批 H/I）是在本方法里**自己遍历** `/sys/class/thermal/thermal_zone<N>/temp`
     * 取最大值。这与 `SystemCollector.getCpuInfo()` 里那份只读 zone0 的实现并存 ——
     * 同一台设备上「告警看到的温度」和「图表里的温度」是两个数（缺陷 C 的根因）。
     *
     * 收敛后：[ThermalZones]（`:core:common`）持有唯一的遍历逻辑，
     * 本方法只做「毫度 Long → 摄氏度 Float」的单位换算。
     *
     * ## 与原实现逐值等价（可比对的四条判据）
     *
     * 1. **遍历范围**：仍是全部 `thermal_zone<N>`，按 `startsWith` 前缀过滤 —— 不变；
     * 2. **负数地板夹 0**：`ThermalZones.readMax()` 的 `maxMilliC` 从 `0L` 起、只在更大时抬升
     *    —— 等价于原来 `Float` 域的 `maxTemp = 0f` + `if (tempC > maxTemp)`；
     * 3. **`null` vs `0f`**：原实现「一个热区都读不到」与「读到了但都 ≤ 0」都返回 `0f`。
     *    `ThermalZones` 区分了（前者 `maxMilliC == null`，后者 `maxMilliC == 0L`），
     *    本方法按 [PlatformAdapter.readTemperature] 第 1 条硬约束：`null` → `null`。
     *    调用点 `DownloadManager` 写的 `?: 0f` 把两种合回 `0f` —— **运行时逐路一致**；
     * 4. **每热区独立 try + canRead() 预检**：`ThermalZones` 照样逐条保留 —— 不变。
     *
     * ⚠ 上面第 2、3 条已被 2026-09-25 的 P3-3 改掉（原文保留供对照）：
     * `ThermalZones` 现在**只把「解析成功且 > 0」算作有效读数**，所以
     * 「热区全部报 -1」「热区内容全不是数字」这两种情况下 `maxMilliC` 是 `null` 而不再是 `0L`，
     * 本方法因此返回 `null` 而不是 `0f`。这是刻意的：原来那个 `0f` 会让 `DownloadManager`
     * 的温控熔断把「读不到温度」当成「最凉」，越热越不熔断且没有任何告警
     * —— 现在 `null` 会走到调用方的「温度不可用」分支。
     * 第 3 条那句「运行时逐路一致」随之不再成立。
     *

     * @return 摄氏度；一个热区都读不到时 `null`。
     */
    override suspend fun readTemperature(): Float? = withContext(Dispatchers.IO) {
        val reading = ThermalZones.readMax()
        val milliC = reading.maxMilliC
        if (milliC != null) milliC / 1000f else null
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
