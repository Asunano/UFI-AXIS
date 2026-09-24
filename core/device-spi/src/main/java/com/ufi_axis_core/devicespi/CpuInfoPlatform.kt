package com.ufi_axis_core.devicespi

/**
 * `/proc/cpuinfo` 平台判据 —— **全仓唯一的一份**（阶段 4 的 4.6）。
 *
 * ## 为什么会有这个类：4.6 要消掉第二份判据
 *
 * 4.6 之前同一件事写了两遍，而且**两遍不一致**：
 * - `ATChannel.detectPlatform()`（`:core:collector`）：`Spreadtrum` / `sprd` 判展锐，
 *   `Qualcomm` / `qcom` 判高通 —— **没有** `unisoc`；
 * - `ZteF50Plugin.probe()`（`:core:device-plugins`）：自己抄了一份，**多一个** `unisoc`。
 *
 * 两份判据分处两个互不可见的 module，于是「改一处忘一处」是必然而不是意外。
 * 本类把它们**合成一份并取并集**（见 [SPREADTRUM_MARKERS]），两侧一起引用。
 *
 * ## 为什么落在 `:core:device-spi` 而不是 `platform/sprd/`
 *
 * 判据本身确实是**平台知识**，按布局纪律应该住在 `:core:device-plugins` 的 `platform/sprd/`。
 * 但它有**两个**消费者，而它们分处两个 module：
 * - 打分侧 `ZteF50Plugin.probe()` 在 `:core:device-plugins`；
 * - 上报侧 `ATChannel.detectPlatform()` 在 `:core:collector`，而 `:core:collector`
 *   **不许依赖** `:core:device-plugins` —— 「谁都不许 import 具体插件，这条依赖只许出现在装配层」
 *   是本框架的硬纪律（写在 `core/build.gradle.kts` 里）。
 *
 * 也就是说放进 `platform/sprd/` 的任何写法（哪怕是 public）都到不了 `ATChannel`，
 * 结果只能是**继续留两份**。而 [QUALCOMM_MARKERS] 更直接：本仓没有高通插件，
 * 它在 `platform/` 下根本没有归属。
 *
 * 两个 module 都能看见、且在两者**之上**的最近层就是本模块（`:core:collector` 与
 * `:core:device-plugins` 都依赖它）。本类是**纯 Kotlin 常量 + 纯函数**：零 I/O、零 Android
 * 类型、零协议实现，没有违反本模块「只放契约、不写采集/装配实现」的约束
 * —— 采集实现（读文件、发 HTTP）在 `ProbeEnvCollector`（`:core:device-plugins`）。
 *
 * ## 与 [PlatformAdapter.name] 的分工（别搞混）
 *
 * [PlatformAdapter.name] 回答的是「**选中的插件**跑在什么平台上」（F50 → `SPREADTRUM`），
 * 本类回答的是「**这台机器的 cpuinfo 看起来**是什么平台」。两者可以不一致 ——
 * 比如配置里硬指定了 F50 插件、实际插在一台高通机器上：前者仍是 `SPREADTRUM`，
 * 后者是高通。`/api/at/platform` 要的是**后者**（「这台其实不是展锐」是排障要看的信息），
 * 所以 `ATChannel` 不许改成直接取 `adapter.name`。
 */
object CpuInfoPlatform {

    /**
     * 展锐特征串。**这是并集**：`sprd` / `spreadtrum` 来自 `ATChannel.detectPlatform()`，
     * `unisoc` 来自 `ZteF50Plugin.probe()`。
     *
     * `unisoc` 是紫光展锐（Spreadtrum → UNISOC）的**现用品牌名**，合并前只有插件打分那一侧带它。
     *
     * ⚠ **合并后有一条对外行为变更**：在 cpuinfo 只写 `unisoc`、不写 `sprd` / `spreadtrum`
     * 的机型上，`/api/at/platform` 与 `/api/at/status` 的 `platform` 字段
     * 会从 `UNKNOWN` 变成 `SPREADTRUM`。方向是「认得更准」，但它确实是一次取值变化，
     * 已在 4.6 的执行报告里显式报出。
     * 实测在用的 F50（`Hardware : Spreadtrum ums9620` 一类）本来就命中 `spreadtrum`，
     * 取值不变。
     */
    val SPREADTRUM_MARKERS: List<String> = listOf("sprd", "spreadtrum", "unisoc")

    /**
     * 高通特征串，取值与 `ATChannel.detectPlatform()` 改造前**逐字一致**。
     *
     * 本仓没有高通插件，所以这一组**只服务上报**（`/api/at/platform` 的 `QUALCOMM` 分支）。
     * 不许因为「没有插件用它」就删掉：删掉等于把高通机器报成 `UNKNOWN`，
     * 而「这台其实不是展锐」正是这个字段的排障价值所在。
     */
    val QUALCOMM_MARKERS: List<String> = listOf("qualcomm", "qcom")

    /**
     * 把 `/proc/cpuinfo` 的**原始内容**归一化成 [ProbeEnv.cpuInfoPlatform] 的取值：
     * `trim` + 转小写；空白内容 → `null`。
     *
     * ## 为什么**不**从里面抽取某一行（如 `Hardware`）
     *
     * 4.6 的硬约束是「`/api/at/platform` 的取值除 `unisoc` 那条外一个字符都不许变」，
     * 而改造前的判据是**对整个文件内容**做 `contains` —— 只要文件里任何位置出现 `sprd`
     * 就判展锐。改成「只看 `Hardware` 行」在 F50 上取值相同，但在「特征串出现在别的键上」
     * 的机型上会从 `SPREADTRUM` 静默变成 `UNKNOWN`：那是一次我们**无法在无真机的情况下
     * 证伪**的行为变更。所以这里保留全文，判据口径与改造前逐位等价，
     * 唯一的差别是大小写已经统一（匹配本来就是大小写不敏感的）。
     *
     * 代价是这个字段的取值是几 KB 的文本而不是一个短串 —— 它只进内存、**不进日志、不下发**，
     * 可接受。等哪天有真机 dump 能证明抽取哪一行都安全，再收窄。
     */
    fun normalize(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        return if (text.isEmpty()) null else text.lowercase()
    }

    /**
     * 是否命中展锐。`null` / 空串 → `false`。
     *
     * 大小写不敏感：**不依赖调用方是否已经 [normalize] 过**（单测与插件都可能直接塞原串）。
     */
    fun isSpreadtrum(cpuInfoPlatform: String?): Boolean = matchesAny(cpuInfoPlatform, SPREADTRUM_MARKERS)

    /** 是否命中高通，口径同 [isSpreadtrum]。 */
    fun isQualcomm(cpuInfoPlatform: String?): Boolean = matchesAny(cpuInfoPlatform, QUALCOMM_MARKERS)

    private fun matchesAny(cpuInfoPlatform: String?, markers: List<String>): Boolean {
        val text = cpuInfoPlatform ?: return false
        return markers.any { text.contains(it, ignoreCase = true) }
    }
}
