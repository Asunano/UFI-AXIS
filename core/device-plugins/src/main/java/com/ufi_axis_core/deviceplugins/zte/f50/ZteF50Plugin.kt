package com.ufi_axis_core.deviceplugins.zte.f50

import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.profile.ZteGoformProfile
import com.ufi_axis_core.devicespi.DevicePlugin
import com.ufi_axis_core.devicespi.DeviceTransport
import com.ufi_axis_core.devicespi.DeviceTuning
import com.ufi_axis_core.devicespi.ProbeEnv
import com.ufi_axis_core.devicespi.TransportConfig

/**
 * ZTE F50（Unisoc 平台随身 WiFi）插件 —— 本仓的首个 [DevicePlugin] 实现，也是默认插件。
 *
 * 它把已经存在的三样东西聚到一起，**没有新增任何设备知识**：
 * - [profile] → `ZteGoformProfile`（`:core:device-schema`）；
 * - [createTransport] → `GoformClient`（`:core:goform`）；
 * - [tuning] → 四处实测常量的原值（来源逐条记在 [DeviceTuning] 的字段 KDoc 上）。
 *
 * 是 `object` 而不是 class：插件本身**无状态**（有状态的是它造出来的传输层）。
 */
object ZteF50Plugin : DevicePlugin {

    override val id = "zte-f50"

    override val displayName = "ZTE F50"

    override fun profile(): DeviceProfile = ZteGoformProfile

    override fun createTransport(cfg: TransportConfig): DeviceTransport =
        GoformClient(deviceIp = cfg.deviceIp, port = cfg.port, password = cfg.password)

    /** 取值与来源见 [DeviceTuning] 各字段的 KDoc（本批只搬数值，四处调用点一行未动）。 */
    override fun tuning(): DeviceTuning = DeviceTuning(
        thermalWarnC = 75f,
        thermalCriticalC = 85f,
        thermalJitterC = 3f,
        bootGraceMs = 90_000L,
        rootShellPermits = 5,
    )

    /**
     * 保守打分。**只读 [env]，零 I/O**（[DevicePlugin.probe] 的硬约束）。
     *
     * ## 判据与它们的依据
     *
     * 1. **`goformLdReachable`（60 分，且是唯一的准入条件）** —— 有实测依据：
     *    本插件的传输层就是 goform 后台客户端（`GoformClient`），`LD` 取不到就意味着
     *    要么不是 goform 后台的设备、要么后台不可达 —— 两种情况下本插件都不该自称匹配。
     *    `LD` 免登录、免 profile，这也是 `DeviceProfiles` 记的「鸡生蛋」问题的唯一绕法。
     * 2. **`cpuInfoPlatform` 含展锐特征（+20 分）** —— 有实测依据：
     *    `ATChannel.detectPlatform()` 就是按 `/proc/cpuinfo` 里的 `Spreadtrum` / `sprd`
     *    判展锐平台，F50 走的正是那条展锐 HAL 路径。`unisoc` 是同一家的现用品牌名，
     *    一并匹配（大小写不敏感，不依赖调用方是否已转小写）。
     *    它只是**加分项**而不是准入条件：同平台的别家设备也会命中这一条。
     * 3. **`androidBuild` 的 F50 特征匹配 —— 本批刻意不写。**
     *    全仓找不到任何 F50 的 `Build.BRAND` / `MODEL` / `DEVICE` / `MANUFACTURER` 实测取值：
     *    `SystemController.getDeviceInfo()` 只是把这四个字段原样下发，
     *    app 侧 `CrashHandler` 只是把它们打进崩溃日志，**没有一处记录过真机上到底是什么串**。
     *    没有实测依据就编造字符串匹配，只会得到一条「看着像在判设备、实际恒不命中」的死代码
     *    （或者更糟：命中了别家设备）。等有真机 dump 之后再补，届时给它一个独立权重。
     *
     * ## 返回 0 的条件
     *
     * `goformLdReachable == false` → **直接 0**（不适用），哪怕平台串命中展锐。
     * 全部插件都 0 时由 `PluginRegistry.DEFAULT` 兜底（也就是本插件），
     * 所以返回 0 不会导致「认不出设备 = 整个不工作」。
     *
     * 满分情形：goform 后台可达 + 展锐平台 = 80。留出的余量给上面第 3 条以后接上。
     */
    override suspend fun probe(env: ProbeEnv): Int {
        if (!env.goformLdReachable) return 0
        var score = SCORE_GOFORM_REACHABLE
        val platform = env.cpuInfoPlatform
        if (platform != null && SPREADTRUM_MARKERS.any { platform.contains(it, ignoreCase = true) }) {
            score += SCORE_SPREADTRUM_PLATFORM
        }
        return score
    }

    /** goform 后台可达的权重（准入条件，占大头）。 */
    private const val SCORE_GOFORM_REACHABLE = 60

    /** 展锐平台的加分权重（同平台别家设备也会命中，所以只是加分）。 */
    private const val SCORE_SPREADTRUM_PLATFORM = 20

    /** `/proc/cpuinfo` 里的展锐特征串，取值同 `ATChannel.detectPlatform()`（`unisoc` 是现用品牌名）。 */
    private val SPREADTRUM_MARKERS = listOf("sprd", "spreadtrum", "unisoc")
}
