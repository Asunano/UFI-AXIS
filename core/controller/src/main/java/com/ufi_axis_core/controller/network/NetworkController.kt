package com.ufi_axis_core.controller.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.devicespi.PlatformAdapter
import com.ufi_axis_core.devicespi.WriteOutcome
import com.ufi_axis_core.devicespi.adapter.BandSelection
import com.ufi_axis_core.devicespi.adapter.NetworkControl
import com.ufi_axis_core.devicespi.adapter.WifiControl
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.sync.withLock

/**
 * 网络控制器
 * 统一管理移动数据/WiFi/飞行模式/锁频/锁网
 *
 * @param network network 域的设备适配接口（2026-09-25 批 A2b 起）。
 * @param wifi wifi 域的设备适配接口（同上）。收的是**这两个域**而不是整个 `DeviceHub`：
 *   本类只用得到 network 的 3 个写方法（移动数据 + 两个频段锁定）与 wifi 的 2 个（SSID / 口令），
 *   递整个 hub 等于让它看见所有域，权限比需要的大（口径同批 A2a 的 `ActionExecutorImpl`）。
 */
class NetworkController(
    private val context: Context,
    private val atChannel: ATChannel,
    private val network: NetworkControl,
    private val wifi: WifiControl,
    /**
     * 平台适配层（阶段 4 的 4.4）：网络栈重启「发哪几条命令」这份**设备知识**住在它那里。
     *
     * 由装配层（`ComponentFactory.buildNetworkGraph`）传 `runtime.plugin.platform(context)` ——
     * 本类**不许**自己 new 插件或问 `PluginRegistry`：那会让「当前是哪台设备」在仓里出现第二个答案
     * （唯一答案是 `DeviceRuntime.resolve()` 的结果，见 `ComponentFactory` 里那段注释）。
     *
     * 参数排在最后是为了让现有调用点不必改实参顺序（全仓只有一处 `NetworkController(...)`）。
     */
    private val platform: PlatformAdapter
) {
    private val tag = "NetworkController"
    // 网络栈重启互斥锁 — 防止并发 AT+SFUN 调用
    private val stackRestartMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 开关移动数据
     * 优先使用 goform CONNECT_NETWORK/DISCONNECT_NETWORK（与参考项目一致）
     * 失败后 fallback 到 Shell
     */
    suspend fun setMobileData(enabled: Boolean): Boolean {
        AppLogger.i(tag, "Setting mobile data: $enabled")
        // 方式1: Goform API（与参考项目 KanoUtils.kt 一致）
        if (network.setMobileData(enabled)) {
            AppLogger.i(tag, "Mobile data set via goform: $enabled")
            return true
        }
        // 方式2: settings put global (fallback)
        val settingsCmd = "settings put global mobile_data ${if (enabled) 1 else 0}"
        val settingsResult = ShellExecutor.executeAsRoot(settingsCmd)
        if (settingsResult.isSuccess) {
            ShellExecutor.executeAsRoot("am broadcast -a android.intent.action.ANY_DATA_STATE --ez state $enabled")
            AppLogger.i(tag, "Mobile data set via settings: $enabled")
            return true
        }
        // 方式3: svc 命令
        val command = if (enabled) "svc data enable" else "svc data disable"
        return ShellExecutor.executeAsRoot(command).isSuccess
    }

    /**
     * 修改 WiFi 热点 SSID
     */
    suspend fun setWifiSSID(ssid: String, password: String? = null): Boolean {
        AppLogger.i(tag, "Setting WiFi SSID: $ssid")
        val ssidResult = wifi.setWifiSSID(ssid)
        if (ssidResult && password != null) {
            return wifi.setWifiPassword(password)
        }
        return ssidResult
    }

    /**
     * 修改 WiFi 密码
     */
    suspend fun setWifiPassword(password: String): Boolean {
        AppLogger.i(tag, "Setting WiFi password")
        return wifi.setWifiPassword(password)
    }

    /**
     * 开关飞行模式
     */
    suspend fun setAirplaneMode(enabled: Boolean): Boolean {
        AppLogger.i(tag, "Setting airplane mode: $enabled")
        val value = if (enabled) 1 else 0
        val result = ShellExecutor.executeAsRoot(
            "settings put global airplane_mode_on $value && " +
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled"
        )
        return result.isSuccess
    }

    /**
     * 统一频段锁定（goform + AT+SFUN 网络栈重启，无需设备重启）
     *
     * 流程（严格参考 UFI-TOOLS-REF submitBandForm）：
     * 1. 始终同时发送 LTE_BAND_LOCK 和 NR_BAND_LOCK goform 请求
     *    - 已选频段 → 以逗号拼接
     *    - 未选频段 → 空字符串（清除该 RAT 的频段限制）
     *    参考：lte_band_lock: lte_bands.join(','), nr_band_lock: nr_bands.join(',')
     * 2. 不调用 SET_BEARER_PREFERENCE — 制式切换由设备自动决定
     * 3. AT+SFUN=5 → 等待 500ms → AT+SFUN=4 重启网络协议栈，立即生效
     *
     * @param lteBands LTE 频段号，逗号分隔，如 "1,3,5"；null 表示不限制（发空串）
     * @param nrBands  NR 频段号，逗号分隔，如 "41,78"；null 表示不限制（发空串）
     * @param unlockAll 解锁全部频段 → 发送全频段列表
     * @return BandLockResult
     */
    data class BandLockResult(
        val success: Boolean,
        val mode: String,
        val stackRestarted: Boolean,
        /**
         * 频段值域校验失败的原因（profile 的 `WriteSpec.validate` 给的），非空表示
         * **没有向设备发任何请求** —— route 应回 400 `OUT_OF_RANGE` 而不是 500（计划书 9.5）。
         */
        val rejectedReason: String? = null,
    )


    suspend fun lockBands(
        lteBands: String?,
        nrBands: String?,
        unlockAll: Boolean = false
    ): BandLockResult {
        // unlockAll：同时发全频段 = 解除所有限制
        //
        // 2026-09-25 批 A2b：这里原来先问 `networkClient.lteAllBands()` / `nrAllBands()` 拿
        // **goform 的频段全集掩码串**，再把它当普通频段列表发下去 —— 掩码串是协议细节，
        // 出现在这一层等于规定「别人家的设备也得用掩码串表达全部频段」。现在本类只表达
        // **意图**（[BandSelection.All]），掩码由 adapter 的 goform 实现去问那两个方法
        // （连同 profile 没给掩码时的空串折叠 + WARN，仍在 `GoformNetworkClient` 里，
        // 本批没有重新实现第二份）。**下发给设备的取值逐字未变**。
        //
        // 非 unlockAll 路径的取值仍是这两个串（空串 = 清除该 RAT 限制，
        // 参考项目：lte_bands.join(',') 空数组→空串），下面的空串判断与 modeDesc 用的就是它们。
        val lteValue = lteBands ?: ""
        val nrValue = nrBands ?: ""


        if (!unlockAll && lteValue.isEmpty() && nrValue.isEmpty())
            return BandLockResult(false, "no_bands", false)

        // 始终发送两个 goform 请求（参考项目 Promise.all）
        val lteSelection = if (unlockAll) BandSelection.All else BandSelection.Only(lteValue)
        val nrSelection = if (unlockAll) BandSelection.All else BandSelection.Only(nrValue)
        AppLogger.i(tag, "Band lock goform: LTE=${describe(lteSelection)} NR=${describe(nrSelection)}")
        val lteOutcome = network.lockLteBands(lteSelection)
        val nrOutcome = network.lockNrBands(nrSelection)
        val lteOk = lteOutcome.ok
        val nrOk = nrOutcome.ok
        AppLogger.i(tag, "Band lock result: LTE=$lteOk NR=$nrOk")

        // 值域校验失败（频段号不是 1..255 的纯数字列表）与"设备没写成"是两回事：
        // 前者根本没发请求，要让 route 回 400 OUT_OF_RANGE（计划书 9.5）。
        val rejected = (lteOutcome as? WriteOutcome.Rejected)?.reason
            ?: (nrOutcome as? WriteOutcome.Rejected)?.reason
        if (rejected != null) return BandLockResult(false, "rejected", false, rejected)


        // 不调用 SET_BEARER_PREFERENCE（参考项目 submitBandForm 不设置制式）
        val modeDesc = when {
            unlockAll -> "auto"
            lteValue.isNotBlank() && nrValue.isNotBlank() -> "lte_nr"
            lteValue.isNotBlank() && nrValue.isEmpty()  -> "lte_only"
            lteValue.isEmpty()  && nrValue.isNotBlank() -> "nr_only"
            else -> "auto"
        }

        val goformSuccess = lteOk || nrOk
        val result = BandLockResult(success = goformSuccess, mode = modeDesc, stackRestarted = false)

        // goform 写入成功后重启网络协议栈让频段立即生效
        if (result.success) {
            val restarted = restartNetworkStack()
            AppLogger.i(tag, "Band lock stack restart: $restarted")
            return result.copy(stackRestarted = restarted)
        }
        return result
    }

    /**
     * 频段选择在日志里的写法。
     *
     * [BandSelection.Only] 逐字保留改造前的 `"取值"` 形式；[BandSelection.All] 只能打**意图** ——
     * 掩码串现在只在 adapter 实现里出现（批 A2b），本类拿不到它。这是本批唯一一处可观测差异：
     * `unlockAll` 时这一行从掩码串变成 `<全部频段>`，**下发给设备的取值没变**。
     * 掩码想核对的话看 adapter / profile（`lteAllBandsMask()`）。
     */
    private fun describe(selection: BandSelection): String = when (selection) {
        BandSelection.All -> "<全部频段>"
        is BandSelection.Only -> "\"${selection.bands}\""
    }

    /**
     * 重启网络协议栈（AT+SFUN=5→延时→AT+SFUN=4）
     *
     * 阶段 4 的 4.4 起，「发哪几条命令、什么顺序、中间等多久、怎么判成功」这份**设备知识**
     * 搬到了 [PlatformAdapter.restartNetworkStack]（F50 那份在
     * `:core:device-plugins` 的 `SprdPlatform`，命令 / 顺序 / 等待 / 判据 / 日志文案逐字未改）。
     * 本方法只剩**策略**这两件事：
     *
     * - **互斥锁防止并发 AT+SFUN 调用** —— 留在这里，理由见下；
     * - **执行通道与超时** —— 由本类注入：它本来就持有 [ATChannel]（限流 / 退避 / 熔断那一层），
     *   超时值仍是原来的 5000ms。
     *
     * 为什么锁不跟着搬：`DevicePlugin.platform(ctx)` 每次调用都新建一个适配层实例
     * （插件不缓存），锁放在适配层就是「每个实例各锁自己」= 等于没锁；
     * 而且「同一时刻只许一次 AT+SFUN」是我们的策略，不是这台设备的事实。
     *
     * 重启后额外 2s 等待 modem 完全稳定这一步在适配层里（它是设备事实）。
     */
    suspend fun restartNetworkStack(): Boolean = stackRestartMutex.withLock {
        platform.restartNetworkStack { cmd -> atChannel.sendCommand(cmd, 5000) }
    }

    /**
     * 获取当前网络状态
     */
    fun getNetworkStatus(): Map<String, Any> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val result = mutableMapOf<String, Any>()

        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)

        result["is_connected"] = capabilities != null
        result["has_internet"] = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        result["has_cellular"] = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        result["has_wifi"] = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        return result
    }

}
