package com.ufi_axis_core.controller.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.contract.DeviceFields
import com.ufi_axis_core.devicespi.PlatformAdapter
import com.ufi_axis_core.devicespi.WriteOutcome
import com.ufi_axis_core.devicespi.adapter.BandSelection
import com.ufi_axis_core.devicespi.adapter.NetworkControl
import com.ufi_axis_core.devicespi.adapter.SignalSource
import com.ufi_axis_core.devicespi.adapter.WifiControl
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 网络控制器
 * 统一管理移动数据/WiFi/飞行模式/锁频/锁网
 *
 * @param network network 域的设备适配接口（2026-09-25 批 A2b 起）。
 * @param wifi wifi 域的设备适配接口（同上）。收的是**按域收窄的接口**而不是整个 `DeviceHub`：
 *   本类只用得到 network 的写方法（移动数据 / 制式 / 两个频段锁定）、wifi 的 2 个（SSID / 口令），
 *   以及 signal 的 1 个只读方法（切制式前判"当时开没开数据"，2026-10-31 起）。
 *   递整个 hub 等于让它看见所有域，权限比需要的大（口径同批 A2a 的 `ActionExecutorImpl`）。
 */
class NetworkController(
    private val context: Context,
    private val atChannel: ATChannel,
    private val network: NetworkControl,
    private val wifi: WifiControl,
    /**
     * signal 域的**只读**接口（2026-10-31）。
     *
     * 本类只有一个用途：切换制式前读一次 `ppp_status`，判断「用户当时开没开移动数据」——
     * 切完之后 ppp 一定是断的，那时再读已经读不出这个意图了（见 [setNetworkMode]）。
     * 收的是一个只读域接口而不是整个 `DeviceHub`，口径同上。
     */
    private val signal: SignalSource,
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

    /**
     * 2026-10-05 R2-3 修复：WiFi 缓存失效钩子。setWifiSSID「SSID 成功、口令失败」的部分失败
     * 也必须让 /api/wifi 回读拿到设备实况（SSID 已变）。由装配层注入（dataHub.invalidateWifi），
     * 控制器层不反向依赖 api/cache —— null 时静默跳过（等价旧行为）。
     */
    @Volatile var wifiCacheInvalidator: (suspend () -> Unit)? = null

    // 网络栈重启互斥锁 — 防止并发 AT+SFUN 调用
    private val stackRestartMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 「移动数据开关」的用户意图（2026-11-02）。
     *
     * 设备侧**没有**可读的数据开关字段（真机 96 字段 dump 与厂商 UI 的 203 个字段名都核对过，
     * 见 [MobileDataIntent] 的类注释），能读到的只有 `ppp_status` —— 那是链路态。
     * 切制式/锁频后设备会先拆链再重拨，这期间链路必断，拿它当开关就会「明明是开的显示关」。
     * 所以开关态由本类每一次成功的写操作记下来，读侧统一走 [resolveMobileDataSwitch]。
     */
    private val mobileDataIntent = MobileDataIntent()

    /**
     * 开关移动数据
     * 优先使用 goform CONNECT_NETWORK/DISCONNECT_NETWORK（与参考项目一致）
     * 失败后 fallback 到 Shell
     *
     * 三条路径**只有成功的那个**才把意图记进 [mobileDataIntent]：设备拒了的那次不该让
     * 界面上的开关跟着动。
     */
    suspend fun setMobileData(enabled: Boolean): Boolean {
        AppLogger.i(tag, "Setting mobile data: $enabled")
        // 方式1: Goform API（与参考项目 KanoUtils.kt 一致）
        if (network.setMobileData(enabled)) {
            AppLogger.i(tag, "Mobile data set via goform: $enabled")
            mobileDataIntent.onExplicitWrite(enabled)
            return true
        }
        // 方式2: settings put global (fallback)
        val settingsCmd = "settings put global mobile_data ${if (enabled) 1 else 0}"
        val settingsResult = ShellExecutor.executeAsRoot(settingsCmd)
        if (settingsResult.isSuccess) {
            ShellExecutor.executeAsRoot("am broadcast -a android.intent.action.ANY_DATA_STATE --ez state $enabled")
            AppLogger.i(tag, "Mobile data set via settings: $enabled")
            mobileDataIntent.onExplicitWrite(enabled)
            return true
        }
        // 方式3: svc 命令
        val command = if (enabled) "svc data enable" else "svc data disable"
        val success = ShellExecutor.executeAsRoot(command).isSuccess
        if (success) mobileDataIntent.onExplicitWrite(enabled)
        return success
    }

    /**
     * 手动拨号（`POST /api/network/connect`）。
     *
     * 改前路由直接调 `network.connectNetwork()`，绕过了本类 —— 于是这条入口不更新
     * [mobileDataIntent]，拨号成功要等下一轮链路读数才能把开关显示成开。
     * 统一收进本类：所有改变「数据连不连」的写操作都必须经过同一个地方记意图。
     */
    suspend fun connectNetwork(): Boolean {
        val success = network.connectNetwork()
        if (success) mobileDataIntent.onExplicitWrite(true)
        return success
    }

    /** 手动挂断（`POST /api/network/disconnect`）。理由同 [connectNetwork]。 */
    suspend fun disconnectNetwork(): Boolean {
        val success = network.disconnectNetwork()
        if (success) mobileDataIntent.onExplicitWrite(false)
        return success
    }

    /**
     * 结合一次链路读数，给出「移动数据开关」该显示成什么。
     *
     * 读侧唯一入口：`/api/network/status` 与 `/api/dashboard/summary` 都调它，
     * 别在路由里各写一份 `ppp_status` 判断。
     *
     * @param linkConnected 设备当前链路：true 通 / false 断 / null 读不到
     * @return 开关态；null = 连意图都没有（刚启动且链路也读不到），调用方退回 shell 判断
     */
    fun resolveMobileDataSwitch(linkConnected: Boolean?): Boolean? =
        mobileDataIntent.resolve(linkConnected)

    /**
     * 切换网络制式（**core 侧唯一实现**，2026-10-31）。
     *
     * ## 为什么这里要顺带恢复移动数据
     *
     * 设备事实：`SET_BEARER_PREFERENCE` 要让 modem 换一组 RAT 重新驻网，当前 PDP/PPP 必须拆掉；
     * 真机上设备**不会自己重拨**（`connection_mode` 是 manual 时必然，auto 时也可能因为重注册
     * 未完成而不拨）。表现就是用户看到的「切完制式，移动数据变成关，得手动再开一次」。
     *
     * 所以：**下发前**先读一次连接状态，**写成功后**按这个意图把数据连接恢复回去。
     * 放在 controller 而不是各个客户端（web / app / 定时任务）：三条入口最终都走这里，
     * 客户端各自补一份必然分叉（web 那份只覆盖 web，定时任务那份压根没有）。
     *
     * ## 三条边界
     *
     * 1. **只恢复"切换前是连着的"**：用户自己关掉的数据不能被我们打开（会直接产生流量费）。
     *    读不到连接状态时同样**不做**恢复 —— 宁可少恢复一次，也不凭空开一次数据。
     * 2. **恢复失败不影响返回值**：制式切换本身已经受理，不能因为"上网开关没恢复"把这次
     *    写报成失败（客户端会去重试一次已经生效的制式切换）。
     * 3. **两次尝试**：设备重注册期间第一条 `CONNECT_NETWORK` 可能不被接受，1.5s 后补一次。
     *    单次阻塞 ≤ 约 3s（两次 goform 写 + 一次等待），调用方本来就带着进度弹窗等这次切换。
     *
     * @param mode contract 的 `NetworkMode` 别名（`/mode` 与 `/bearer` 的入参是同一套值域）。
     */
    suspend fun setNetworkMode(mode: String): WriteOutcome {
        // 换制式必然拆链（PDP/PPP 要先拆掉再按新 RAT 驻网）：先开豁免窗口。
        // 否则这十几到二十几秒里读取链路的每一次轮询都会把移动数据开关报成"关"。
        mobileDataIntent.beginLinkTransition()
        val wasConnected = readDataConnected()
        val outcome = network.setBearerPreference(mode)
        if (!outcome.ok || wasConnected != true) return outcome
        restoreMobileDataAfterModeSwitch()
        return outcome
    }

    /**
     * 这次切换前移动数据是否连着。
     *
     * @return true / false；**null = 读不到**（"不知道"与"关着"必须分开：见 [setNetworkMode] 边界 1）
     *
     * 超时 3s：这次读只服务于"要不要恢复"，不能把用户点的那次切换拖在那里。
     * 读的是 `ppp_status`（[DeviceFields.Connection.isPppConnected]）。
     *
     * 注意：**这里要的确实是链路态**，不是开关意图。2026-11-02 起 `/api/network/status` 的
     * `mobile_data` 改成意图（[resolveMobileDataSwitch]），两者不再共用判据 —— 那次改动只影响
     * 展示，不影响「切换前连没连上」这个判断。
     */
    private suspend fun readDataConnected(): Boolean? = try {
        val fields = withTimeoutOrNull(CONNECTION_READ_TIMEOUT_MS) { signal.getConnectionInfo() }?.values
        val ppp = (fields?.get(DeviceFields.Connection.PPP_STATUS) as? JsonPrimitive)?.contentOrNull
        if (ppp == null) null else DeviceFields.Connection.isPppConnected(ppp)
    } catch (e: Exception) {
        AppLogger.w(tag, "读连接状态失败，本次切换不恢复移动数据: ${e.message}")
        null
    }

    /**
     * 切制式后把移动数据恢复回去（只在"切换前是连着的"时调用）。
     *
     * 判成功用 **写结果**（`CONNECT_NETWORK` 被设备接受）而不是"ppp 立刻连上"：
     * 设备这时还在重新注册，ppp 起来本来就要十几秒；命令本身对已连接状态是幂等的。
     */
    private suspend fun restoreMobileDataAfterModeSwitch() {
        repeat(MOBILE_DATA_RESTORE_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(MOBILE_DATA_RESTORE_RETRY_MS)
            if (runCatching { setMobileData(true) }.getOrDefault(false)) {
                AppLogger.i(tag, "制式切换后已恢复移动数据（第 ${attempt + 1} 次）")
                return
            }
        }
        AppLogger.w(tag, "制式切换已受理，但恢复移动数据失败（设备可能仍在重新注册）")
    }

    /**
     * 修改 WiFi 热点 SSID（2026-10-05 R2-1 修复：三态透传）。
     * 双写部分失败：SSID 成功但口令失败 → 返回口令阶段的结果（Rejected 带原因 / Failed），
     * 客户端能知道卡在哪一步；不在此层做回滚（设备无事务语义，重发 SSID 反而是第三次写）。
     */
    suspend fun setWifiSSID(ssid: String, password: String? = null): WriteOutcome {
        AppLogger.i(tag, "Setting WiFi SSID: $ssid")
        val ssidResult = wifi.setWifiSSID(ssid)
        if (ssidResult is WriteOutcome.Ok && password != null) {
            val pwdResult = wifi.setWifiPassword(password)
            // 2026-10-05 R2-3 修复：SSID 写成功、密码写失败时，设备态已变（SSID=新值），
            // 但按 WriteOutcome 只能回一个失败态 → 路由 500、客户端显示「修改失败」，
            // 而此刻 /api/wifi 缓存若不失效，30s 内回读还是旧 SSID——三层状态互相矛盾。
            // 最小改法：部分失败也强制失效缓存，让下一次回读拿到设备实况（SSID 已变）。
            if (pwdResult !is WriteOutcome.Ok) {
                wifiCacheInvalidator?.invoke()
            }
            return pwdResult
        }
        // SSID 本身写失败：设备态未变（SSID 仍是旧值），无需失效
        return ssidResult
    }

    /**
     * 修改 WiFi 密码（2026-10-05 R2-1 修复：三态透传）。
     */
    suspend fun setWifiPassword(password: String): WriteOutcome {
        AppLogger.i(tag, "Setting WiFi password")
        return wifi.setWifiPassword(password)
    }

    /**
     * 读飞行模式状态（settings global airplane_mode_on）。
     *
     * 2026-11-08 补齐：`/api/network/airplane` 之前**只有写、没有读**，前端只能提供
     * 「开启 / 关闭」两个明确动作（见 useNetworkControls 的注释），做不了开关。
     * 这里给 `/api/network/status` 提供读侧，前端才能用 n-switch 真实反映状态。
     *
     * 走 root shell 而不是 Settings.Global：与写入侧（`settings put`）同一条路径，
     * 读到的值一定与写进去的一致；Settings.Global 在某些定制 ROM 上会被缓存。
     */
    suspend fun isAirplaneModeOn(): Boolean {
        val r = ShellExecutor.executeAsRoot("settings get global airplane_mode_on")
        return r.isSuccess && r.stdout.trim() == "1"
    }

    /**
     * 开关飞行模式
     *
     * 开飞行模式是一次**明确的切网动作**：设备会立刻拆掉蜂窝链路，而且不会自己恢复
     * （直到用户把飞行模式关掉）。所以这里同步把开关意图置关 —— 否则 [MobileDataIntent]
     * 的 2 分钟宽限期会让界面在飞行模式下继续显示「移动数据 已开启」。
     *
     * 关飞行模式**不动意图**：由链路自己决定（设备重拨上来 ⇒ 开；没重拨 ⇒ 保持关，
     * 与改造前的表现一致）。
     */
    suspend fun setAirplaneMode(enabled: Boolean): Boolean {
        AppLogger.i(tag, "Setting airplane mode: $enabled")
        val value = if (enabled) 1 else 0
        val result = ShellExecutor.executeAsRoot(
            "settings put global airplane_mode_on $value && " +
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled"
        )
        if (result.isSuccess && enabled) mobileDataIntent.onExplicitWrite(false)
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
        /**
         * 2026-10-05 R2-4 修复：各路写结果明细。LTE 与 NR 是两次独立 goform 写，
         * 可独立失败；`success`（任一路成功即重启栈）无法表达「半失败」。
         * 客户端凭 [partial] 提示「部分频段未生效」，不再被整体 success 掩盖。
         */
        val lteOk: Boolean? = null,
        val nrOk: Boolean? = null,
    ) {
        /** 至少一路成功、但另一路失败：栈已按「新值+旧值」混合状态重启，必须让客户端可见。 */
        val partial: Boolean
            get() = success && ((lteOk == false) || (nrOk == false))
    }


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
        // 2026-10-05 R2-4 修复：携带各路明细，partial 语义见 BandLockResult
        val result = BandLockResult(success = goformSuccess, mode = modeDesc, stackRestarted = false,
            lteOk = lteOk, nrOk = nrOk)

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
        // 重启协议栈必然拆链（AT+SFUN=5 → 4）。与切制式同一口径：先记豁免窗口，
        // 否则锁频那一路的十几秒断链又会被显示成"移动数据被关了"（2026-11-02）。
        mobileDataIntent.beginLinkTransition()
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

    private companion object {
        /** 切换前读连接状态的超时。只用于"要不要恢复数据"，不参与切换的成败。 */
        const val CONNECTION_READ_TIMEOUT_MS = 3_000L

        /** 恢复移动数据的尝试次数与间隔（设备重注册期间第一条可能不被接受）。 */
        const val MOBILE_DATA_RESTORE_ATTEMPTS = 2
        const val MOBILE_DATA_RESTORE_RETRY_MS = 1_500L
    }

}
