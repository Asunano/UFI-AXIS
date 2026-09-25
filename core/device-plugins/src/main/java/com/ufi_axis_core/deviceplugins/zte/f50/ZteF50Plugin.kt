package com.ufi_axis_core.deviceplugins.zte.f50

import android.content.Context
import com.ufi_axis_core.contract.Capability
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.deviceplugins.platform.sprd.SprdPlatform
import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.profile.ZteGoformProfile
import com.ufi_axis_core.devicespi.CpuInfoPlatform
import com.ufi_axis_core.devicespi.DevicePlugin
import com.ufi_axis_core.devicespi.DeviceTransport
import com.ufi_axis_core.devicespi.DeviceTuning
import com.ufi_axis_core.devicespi.PlatformAdapter
import com.ufi_axis_core.devicespi.ProbeEnv
import com.ufi_axis_core.devicespi.TransportConfig

/**
 * ZTE F50（Unisoc 平台随身 WiFi）插件 —— 本仓的首个 [DevicePlugin] 实现，也是默认插件。
 *
 * 它把已经存在的四样东西聚到一起，**没有新增任何设备知识**：
 * - [profile] → `ZteGoformProfile`（`:core:device-schema`）；
 * - [createTransport] → `GoformClient`（`:core:goform`）；
 * - [platform] → [SprdPlatform]（同模块的 `platform/sprd/`，阶段 4 批 F）；
 * - [tuning] → 实测常量的原值（来源逐条记在 [DeviceTuning] 的字段 KDoc 上）。
 *
 * [capabilities] 同理：3A 那 10 个域逐一对着「`ZteGoformProfile` 里有没有那条 `WriteSpec`」+
 * 「core 侧有没有那个写 route」核过（2026-09-24 阶段 3.2），不是照 [Capability] 的清单抄一遍。
 * 3B 新增的 `Capability.BATTERY` **刻意没有声明**（F50 无电池，理由见 [capabilities] 的 KDoc）。
 *
 * 是 `object` 而不是 class：插件本身**无状态**（有状态的是它造出来的传输层）。
 */
object ZteF50Plugin : DevicePlugin {

    override val id = "zte-f50"

    override val displayName = "ZTE F50"

    /**
     * F50 支持 [Capability] 里**除 [Capability.BATTERY] 以外的 10 个域**（也就是 3A 那一批的全部）。
     *
     * ## 判据（逐项都是「WriteSpec 在 + route 在」两条同时成立）
     *
     * | Capability | 设备侧依据（`ZteGoformProfile`） | 写 route |
     * | --- | --- | --- |
     * | [Capability.SMS] | `smsSpec() = ZteSmsSpec` | `POST /api/sms/send` |
     * | [Capability.SIM_SLOT_SWITCH] | `SettingKey.SIM_SLOT` | `POST /api/sim/switch` |
     * | [Capability.BAND_LOCK] | `BAND_LOCK_LTE` / `BAND_LOCK_NR` | `POST /api/network/band` |
     * | [Capability.CELL_LOCK] | `CELL_LOCK` / `CELL_UNLOCK` | `POST /api/device/cell-lock` |
     * | [Capability.NETWORK_MODE] | `NETWORK_MODE` | `POST /api/network/mode` |
     * | [Capability.SAMBA] | `SAMBA` | `POST /api/device/samba` |
     * | [Capability.USB_DEBUG] | `USB_PORT` | `POST /api/device/debug` |
     * | [Capability.FOTA] | `FOTA_AUTO_UPDATE` | `POST /api/device/fota` |
     * | [Capability.PERFORMANCE_MODE] | `PERFORMANCE_MODE` | `POST /api/device/performance` |
     * | [Capability.TRAFFIC_LIMIT] | `TRAFFIC_LIMIT` | `POST /api/device/data-limit` |
     *
     * 「3A 那 10 个全填」不是偷懒：那一批本来就是按「能在 F50 上明确验证、
     * 且已有对应 route」挑出来的（计划书 §7）。真正需要挑的是**下一台设备** ——
     * 那时这里的对照表就是「该怎么核」的样例。
     *
     * ## 为什么**不**声明 [Capability.BATTERY]（2026-09-24 批 L，批 M 修正后果描述）
     *
     * **F50 没有电池**，用户实测确认。
     *
     * 这一项是纯读侧能力（不进上面那张表：它没有 `WriteSpec`、也没有写 route，
     * 口径见 `Capability` 的文件头）。不声明它的直接后果（批 M / 方案 D 的口径）是：
     * 电量**照系统值下发**（这台机器上恒为 50%），battery map 里多一个 `supported=false`
     * 告诉客户端这个读数不可信；同时 `DataScheduler` 不把它入库、不拿它判电池告警。
     * ⚠ 不是「抹成 -1」—— 批 L 那个做法已被用户推翻（app 端会渲染出红色的 `-1%`，更像故障）。
     *
     * ⚠ 特别注意**不要**因为「机器上能读到电量」就把它加回来：这台设备的
     * sticky `ACTION_BATTERY_CHANGED` 恒报 `level=50, scale=100` —— 那是**假值**。
     * 正是它让「读不到就兜底」的旧逻辑全部失效，50% 被当成真实读数存了很久。
     *
     * 排障时可以临时删掉一项验证门禁（如 [Capability.SAMBA] → `/api/device/samba` 回 501），
     * **验完必须还原**（计划书 §7 的验收写明了这是排查性删除）。
     */
    override val capabilities: Set<Capability> = setOf(
        Capability.SMS,
        Capability.SIM_SLOT_SWITCH,
        Capability.BAND_LOCK,
        Capability.CELL_LOCK,
        Capability.NETWORK_MODE,
        Capability.SAMBA,
        Capability.USB_DEBUG,
        Capability.FOTA,
        Capability.PERFORMANCE_MODE,
        Capability.TRAFFIC_LIMIT,
    )


    override fun profile(): DeviceProfile = ZteGoformProfile

    override fun createTransport(cfg: TransportConfig): DeviceTransport =
        GoformClient(deviceIp = cfg.deviceIp, port = cfg.port, password = cfg.password)

    /**
     * F50 跑在展锐（Unisoc）平台上 → [SprdPlatform]。
     *
     * ⚠ [ctx] **用不到**（[SprdPlatform] 只需要 `ProcessBuilder` 与 `/sys` 文件读，
     * 两者都不要 `Context`），所以这里没往下传。
     * 2026-09-24 批 L 更新：原来这里写「4.3 收电池读法时大概会需要」——
     * 那个假设已经作废，`readBattery()` 明确**不进** `PlatformAdapter`（理由在那个接口的文件头），
     * 所以 `Context` 的唯一预期用途消失了。签名仍然保留它：`DevicePlugin.platform(ctx)` 是契约，
     * 下一台设备的平台实现很可能真的需要 `Context`（例如读 framework 级别的系统属性）。
     * 每次调用新建一个：口径与 [createTransport] 一致，插件自己不缓存（见 [DevicePlugin.platform]）。
     */
    override fun platform(ctx: Context): PlatformAdapter = SprdPlatform()

    /**
     * 取值与来源见 [DeviceTuning] 各字段的 KDoc（本批仍然只搬数值，四处调用点一行未动）。
     *
     * 2026-09-24 阶段 4 批 F 随字段改名同步改了三处，**数值一个没变**：
     * - `thermalWarnC` / `thermalCriticalC` → [DeviceTuning.downloadThrottleWarnC] /
     *   [DeviceTuning.downloadThrottleCriticalC]（75 / 85 只服务下载限速，§8 裁决 ②）；
     * - 新增 [DeviceTuning.downloadThrottleForcePauseOffsetC]`= 10f` ——
     *   `DownloadManager` 第 4 档 `temp >= critical + 10`（→ 95°C 全部暂停）此前是个裸字面量；
     * - 删掉 `rootShellPermits` —— 它是 QoS 配置默认值不是设备事实，
     *   而且实测用户默认值是 3 不是 5（§8 裁决 ③）。
     */
    override fun tuning(): DeviceTuning = DeviceTuning(
        downloadThrottleWarnC = 75f,
        downloadThrottleCriticalC = 85f,
        downloadThrottleForcePauseOffsetC = 10f,
        thermalJitterC = 3f,
        bootGraceMs = 90_000L,
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
     *    F50 走的正是展锐 HAL 那条路径。判据**不在本文件里写**，
     *    统一走 [CpuInfoPlatform.isSpreadtrum]（阶段 4 的 4.6）：
     *    本插件此前自己抄了一份 marker 列表，与 `ATChannel.detectPlatform()` 那一份
     *    **不一致**（这边多一个 `unisoc`），两份分处两个 module、改一处忘一处是必然。
     *    合并取并集之后（`sprd` / `spreadtrum` / `unisoc`），本方法的打分**没有变化** ——
     *    本来就是这三个串。
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
        if (CpuInfoPlatform.isSpreadtrum(env.cpuInfoPlatform)) {
            score += SCORE_SPREADTRUM_PLATFORM
        }
        return score
    }

    /** goform 后台可达的权重（准入条件，占大头）。 */
    private const val SCORE_GOFORM_REACHABLE = 60

    /** 展锐平台的加分权重（同平台别家设备也会命中，所以只是加分）。 */
    private const val SCORE_SPREADTRUM_PLATFORM = 20
}
