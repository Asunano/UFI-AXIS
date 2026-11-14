package com.ufi_axis_core.devicespi

import com.ufi_axis_core.deviceschema.DeviceProfile
// stdlib 的那一个（JVM 上就是 java.util.concurrent.CancellationException，
// kotlinx 的同名类型只是它的 typealias）—— 刻意不 import kotlinx.coroutines 的版本：
// 本模块的协程依赖是 implementation，而这里只需要「取消不当成打分失败」这一条语义。
import kotlin.coroutines.cancellation.CancellationException


/**
 * 中间控制层（计划书 §3.3）—— **唯一知道「当前是哪台设备」的地方**。
 *
 * 上层（route / collector / controller / scheduler）不认识任何具体插件：它们从这里拿
 * [profile]（可空，归一化用）与 [commandProfile]（非空，命令表用），换设备时整个对象重建。
 *
 * ## 生命周期：只在构造组件图时选一次，**不做运行期热换**
 *
 * 两条结论从被删掉的 `ComponentFactory.resolveDeviceProfile()` 搬过来，它们仍然成立
 * （计划书 §11.10 也记了同一条）：
 *
 * 1. **只在构造组件图时读一次配置**，改完 `device_profile_id` / `field_normalization_enabled`
 *    要**重启后台服务**才生效。于是「切换后缓存里还躺着上一种形状的数据」（计划书 1.0.2）
 *    在设计上就不成立 —— 组件图重建时各级缓存都是新的。
 *    所以本类**刻意不提供** `switchPlugin()`。
 * 2. **型号不认识不能导致整个不工作**（计划书 10.2）：配置里的 id 打错了也照样返回一个
 *    能用的插件（`default` + [Selection.FALLBACK] + WARN），不抛异常、不让后端起不来。
 *
 * ## 为什么 [resolve] 的签名里没有 `AppSettings` / `Context`
 *
 * 计划书 §3.3 原来写的是 `resolve(settings, ctx)`。**落地时刻意改成传值**：
 * 那样 `:core:device-spi` 就要依赖 `:core:common`（`AppSettings` 在那里）与 Android `Context`，
 * 而本模块是**纯契约层**，它的单测必须能**不起 Android 就跑**（JUnit4 本地单测，
 * 不是 instrumented test）。
 * 所以配置取值由调用方（装配层 `ComponentFactory`）从 settings 里取好后传进来 ——
 * 「哪个配置键对应哪个参数」这件事只在装配层那一处，契约层不关心配置的存储形态。
 *
 * 同理，[resolve] 的其余入参也全是传值。它从 2026-09-24（阶段 5 的 5.2）起是 `suspend`
 * 并多收一份 [ProbeEnv]：[DevicePlugin.probe] 是 `suspend`，而选型现在真的会调它。
 * 这次改签名**没有牵动任何结构** —— 调用点 `ComponentFactory.build()` 本身就是 `suspend`，
 * 那一份 [ProbeEnv] 也早在 5.1 就在本函数**之前**采好了（5.1 当时刻意没加这个参数，
 * 判据是「选型规则不许在采集那一批里顺手改」；现在选型规则正是本批要改的东西）。
 *
 * ## 2026-09-24（阶段 5 的 5.2 / 5.3 / 5.4）：`probe()` 真正参与选型
 *
 * 选型的五条路径见 [resolve] 自己的 KDoc。这里只记**对外行为**：
 *
 * - 现网零配置部署的 `device_profile_id` 是空 → 走 probe 那条路径 → 唯一插件
 *   `ZteF50Plugin` 的 `probe()` 在真机上应当 > 0（`goformLdReachable` 给 60 分）
 *   → **选中的插件与改造前完全相同**（都是 `ZteF50Plugin`），
 *   于是传输层、命令表、能力集、调参一个字都没变。
 * - **唯一的对外取值变化**是 `/api/diagnose` 的 `device_profile.selection`
 *   从 `"default"` 变成 `"probed"`。这个取值早在阶段 2 批 B2 就定进值域并对外公布
 *   （见 [Selection.PROBED]），所以**不是**一次值域扩张，两端不用改。
 * - ⚠ **`selection` 现在隐含了「探测通不通」**：设备离线 / LD 探不通时 `probe()` 返 0
 *   → 回落默认插件 → `selection` **仍然是** `"default"`。
 *   也就是说零配置部署上 `"probed"` = 「后台可达且有插件认领」、
 *   `"default"` = 「没人认领，正在用兜底插件」。排障时这两个取值可以当一个粗粒度的
 *   探测指示灯用，但**它不是探测结果的正式出口** —— 要看探测细节请看启动日志
 *   （`ProbeEnvCollector` 的 WARN 与本类打的那几条）。
 * - `configured` / `active` / `normalization_enabled` / `status` / `plugin_id` 与
 *   `/api/device/capabilities` **一个字符都没动**。特别是 `status` 那个四态的推导
 *   完全没碰 —— 它不看 [selection]。
 */
class DeviceRuntime private constructor(
    /** 选中的插件。传输层、命令表、调参全从它来。 */
    val plugin: DevicePlugin,
    /**
     * 字段归一化用的 profile。**可空**：`null` = 排障开关关掉了归一化（保留现有语义）。
     *
     * 这个 null 不是「没选到设备」，而是「用户主动要求原样透传」。
     * `/api/diagnose` 的 `device_profile.normalization_enabled` 就是从它是否为 null 推出来的
     * （链路：`GoformFieldMapper.profileId` → `GoformSignalClient.profileId` →
     * `DataHub.deviceProfileId` → `HttpServer` 的 `activeProfile != null`），
     * 所以**不许**在这里用非空兜底把它填上 —— 那个排障开关会永远报 `true`。
     */
    val profile: DeviceProfile?,
    /** 本次选型是怎么定下来的，见 [Selection]。 */
    val selection: Selection,
) {

    /**
     * 命令表用的那一份：**永远非空**（字段归一化可以关，命令表不能关）。
     *
     * 没有 cmd 列表就一条查询都发不出去、一条短信都发不出去，关归一化会把整个只读面打瘫 ——
     * 口径与 `GoformSettingWriter` / `GoformSmsClient` 的既有注释完全一致。
     *
     * ## 为什么兜底是 `plugin.profile()` 而**不是** `default.profile()`
     *
     * 选中的是哪个插件，命令表就必须来自**那个**插件。若这里退回默认插件的 profile，
     * 「用户打开排障开关（关归一化）」就会顺带把命令表悄悄换成**另一台设备**的命令表 ——
     * 读侧只是字段名变回原名（这正是排障想看到的），写侧却会拿着错误的 cmd 名与参数名发请求。
     * 一个排障开关不该改变「往设备发什么命令」。
     *
     * 目前仓里只有一个插件、它的 profile 恰好就是 `DeviceProfiles.DEFAULT`，所以两种写法
     * 取值相同 —— 这也正是它容易被写错、且写错了测试不一定红的原因，故把判据记在这里。
     */
    val commandProfile: DeviceProfile get() = profile ?: plugin.profile()

    /**
     * 选型来源。进日志；阶段 2 的 2.8 起**下发到** `/api/diagnose` 的
     * `device_profile.selection`（用 [wire] 而不是 [name]）。
     *
     * ## 为什么每个值上挂一个显式的 [wire] 而不是在下发处 `name.lowercase()`
     *
     * `name.lowercase()` 对现在这四个值恰好正确，于是「Kotlin 标识符」就成了**对外契约**：
     * 以后谁改一次枚举名（重构里最常见的动作），对外值域就跟着变，而编译器一声不响。
     * 显式写一遍等于把「对外值域」钉在定义点 —— 改名不影响线上值，
     * 真要改线上值必须改这里、改的人一眼就看见这是客户端可见的字符串。
     *
     * 值域在阶段 2 批 B2 就**一次定稳**（当时 [PROBED] 还没有任何代码路径会产出它，
     * 也照样先把值定了）：对外值域二次扩张要两端一起改。

     *
     * @property wire 对外 JSON 里的取值：小写 snake，与 `device_profile` 块现有键的风格一致。
     */
    enum class Selection(val wire: String) {
        /** 配置项显式指定且匹配上了。 */
        CONFIGURED("configured"),

        /**
         * 由 `probe()` 打分选出。
         *
         * **2026-09-24（阶段 5 的 5.2）起真的会返回这个值**：`configuredId` 空白时
         * 逐个插件 `probe(env)` 打分，最高分 > 0 就选它（细则见 [resolve] 的第 4 条）。
         *
         * 枚举值本身是阶段 2 批 B2 定下来的，那时**刻意先定值、后接代码**，
         * 图的就是「对外值域一次定稳」：`selection` 从 2.8 起下发到 `/api/diagnose`，
         * 值域扩一次就是一次客户端适配。本批把它接上时 app / web **一个字都不用改**。
         */
        PROBED("probed"),

        /**
         * 配置项为空、且**没有任何插件的 `probe()` 给出正分**，用注册表默认插件
         * （现有部署零配置继续工作）。
         *
         * ⚠ 这条**不是** [FALLBACK]：没有人配错任何东西，只是「认不出设备」——
         * 而「认不出设备不能导致整个不工作」（计划书 10.2）。
         * 两者混用会让诊断页把一次正常的离线启动报成配置错误。
         */
        DEFAULT("default"),

        /**
         * 配置项填了但两种 id 都匹配不上，已回落默认插件（伴随一条 WARN）。
         *
         * 与 [DEFAULT] 的分界就一条：**这里一定有人填错了字**。
         */
        FALLBACK("fallback"),
    }

    companion object {

        /**
         * 选插件 + 定 profile。**阶段 2 批 B 起是仓里唯一的选型入口**
         * （与被删掉的 `ComponentFactory.resolveDeviceProfile()` 逐条等价，
         * 除了下面第 4 条 —— 那是阶段 5 的 5.2 新增的 probe 打分）。
         *
         * 规则（顺序即优先级，**五条路径互斥**）：
         *
         * 1. `normalizationEnabled == false` → [profile] 置 `null` 并打一条 WARN
         *    （文案与原实现逐字一致，且**仍然在最前面** —— 日志顺序不变）。
         *    ⚠ **插件照样选**：传输层与命令表**不能**跟着归一化一起关。
         *    [selection] 仍按下面 2~4 条算 —— 排障开关**不影响**选型结果，
         *    包括不影响 probe 是否执行。
         * 2. `configuredId` 非空且匹配上 → 该插件 + [Selection.CONFIGURED]。
         *    **匹配两种 id，先新口径后旧口径**：先 [DevicePlugin.id]（如 `zte-f50`），
         *    再 `plugin.profile().id`（**旧口径**，如 `zte-goform`）。
         *    两者都按 trim + 大小写不敏感比较（与原实现 `DeviceProfiles.byId()` 的口径一致）。
         *
         *    **为什么必须兼容旧口径**：配置键 `device_profile_id` 现在存的是 **profile id**
         *    （`ZteGoformProfile.id = "zte-goform"`，而 `ZteF50Plugin.id = "zte-f50"`），
         *    而且它会被**原样**下发到 `/api/diagnose` 的 `configured` 字段。
         *    只按 plugin id 匹配的话，已经填了 `zte-goform` 的存量部署会突然变成
         *    「认不出 → 回落」，日志与诊断页跟着变 —— 那是**行为变更**，不是搬运。
         *    命中旧口径时打的是 **info 而不是 warn**：用户填的值是对的，不需要他改任何东西。
         *
         *    ⚠ 这条命中时**一次 `probe()` 都不发** —— **配置永远是最高优先级**。
         * 3. 两种 id 都匹配不上 → `default` + [Selection.FALLBACK] + WARN（文案与原实现逐字一致；
         *    「可选」清单**两种 id 都列**，让用户知道该填什么）。
         *    同样**不发 probe**：用户明明填了值，这时悄悄探到另一台设备，
         *    会把「我填了 X 却在用 Y」变成一个无法归因的现象 —— 填错了就该看见那条 WARN。
         * 4. `configuredId` 空白 → **对 [plugins] 逐个 `probe(probeEnv)` 打分**（阶段 5 的 5.2）：
         *    - 最高分 **> 0** → 选它 + [Selection.PROBED] + 一条 INFO（写明选中谁、几分，
         *      以及「这是探测结果、不是配置」—— 排障时最容易搞混的就是这一点）；
         *    - **并列同分** → 取 [plugins] 里**声明顺序靠前**的那个（稳定、可预测：注册表是
         *      编译期 `listOf(...)`，顺序是代码里看得见的东西），并打一条 **WARN**。
         *      并列必须留痕：它意味着 probe 判据不足以区分这些插件，
         *      否则「有人调了一下注册表顺序、选中的设备就变了」这件事没有任何人看得见。
         *    - 某个插件的 `probe()` **抛异常** → 当 **0 分**并打一条 WARN，
         *      **整轮选型继续**（一个插件炸了不许让后端起不来 —— 同计划书 10.2）。
         *      WARN 文案带插件 id，但**刻意不带异常 message**：装配层的 `AppLogger`
         *      按「级别 + tag + 完整消息」折叠重复，带上 message 会让折叠基数发散
         *      （同一个坑记在 `ProbeEnvCollector` 的类 KDoc 第 3 条）。
         *      [kotlin.coroutines.cancellation.CancellationException] 例外：原样抛出去，
         *      调用方取消就该真的取消，把它当「插件打分失败」是错的。
         *    - 全部 ≤ 0 → `default` + [Selection.DEFAULT]（**不是** [Selection.FALLBACK]，
         *      分界见那两个枚举值自己的 KDoc）。
         *      **这条路径刻意不打日志**，三条判据：
         *      ① 与改造前逐字一致 —— 零配置那条路径原实现也不打；
         *      ② 信息没丢：LD 探不通时 `ProbeEnvCollector` 已经打过一条 WARN，
         *         而目前唯一的插件正是「LD 不可达 → 0 分」，所以这条路径今天几乎等价于
         *         那条 WARN 已经打过的情形，再补一条只是重复噪音；
         *      ③ `/api/diagnose` 的 `selection = "default"` 本身就是它的对外出口。
         *      ⚠ 以后若出现「LD 可达却没人认领」这种**真正**需要报警的组合，请在这里补一条 WARN，
         *      并同步改 `DeviceRuntimeTest` 里那三条「不打日志」的断言 ——
         *      那是一次**有意的**行为变更，不是「为了让测试通过」。
         * 5. 五条路径里没有「probe 影响 [profile] 是否为 null」这一说：
         *    `profile` 只由 [normalizationEnabled] 决定，与选中谁无关。
         *
         * @param plugins 全部候选插件，**顺序即并列同分时的优先级**。装配层从
         *   `PluginRegistry.ALL` 传进来 —— 契约层不许知道有哪些插件存在。
         * @param default 兜底插件（`PluginRegistry.DEFAULT`）。
         * @param configuredId 配置项 `device_profile_id` 的**原值**（允许空白）。
         * @param normalizationEnabled 配置项 `field_normalization_enabled`（排障开关）。
         * @param probeEnv 装配层在本函数**之前**采好的那一份廉价指纹
         *   （`ProbeEnvCollector.collect()`，阶段 5 的 5.1）。**一份贯穿全程**：
         *   所有插件的 `probe()` 读的是同一个对象，否则打分之间没有可比性
         *   （纪律写在 [ProbeEnv] 与 [DevicePlugin.probe] 上）。
         *   **必填、无默认值**：给个默认值就等于允许「忘了传 → 谁都探不到」静默发生。
         * @param warn 打 WARN 的回调。默认空实现只为**单测**方便：契约层没有 `AppLogger`
         *   （那在 `:core:common`，本模块不依赖它），日志出口由装配层注入。
         * @param info 打 INFO 的回调，同上。
         */
        suspend fun resolve(
            plugins: List<DevicePlugin>,
            default: DevicePlugin,
            configuredId: String,
            normalizationEnabled: Boolean,
            probeEnv: ProbeEnv,
            warn: (String) -> Unit = {},
            info: (String) -> Unit = {},
        ): DeviceRuntime {
            // 先打这条：原实现里它是排障模式下唯一的一行日志，放在最前面才能保持日志顺序不变。
            if (!normalizationEnabled) {
                warn("字段归一化已关闭（排障开关），设备字段将原样透传，对外字段名会变回设备原名")
            }

            val key = configuredId.trim()
            var picked = default
            var selection = Selection.DEFAULT

            if (key.isNotEmpty()) {
                val byPluginId = plugins.firstOrNull { it.id.equals(key, ignoreCase = true) }
                // 新口径没命中才查旧口径 —— 顺序就是优先级，反过来会让 plugin id 被 profile id 抢走。
                val byProfileId =
                    if (byPluginId != null) null
                    else plugins.firstOrNull { it.profile().id.equals(key, ignoreCase = true) }
                when {
                    byPluginId != null -> {
                        picked = byPluginId
                        selection = Selection.CONFIGURED
                        val p = byPluginId.profile()
                        info("设备 profile: ${p.id}（${p.displayName}）")
                    }
                    byProfileId != null -> {
                        picked = byProfileId
                        selection = Selection.CONFIGURED
                        val p = byProfileId.profile()
                        info(
                            "设备 profile: ${p.id}（${p.displayName}）" +
                                "—— 配置项 device_profile_id 填的是 profile id，" +
                                "已映射到插件 ${byProfileId.id}（${byProfileId.displayName}）；" +
                                "plugin id 与 profile id 两种写法都支持"
                        )
                    }
                    else -> {
                        selection = Selection.FALLBACK
                        warn(
                            "未知的 deviceProfileId=$configuredId，回落 ${default.profile().id}" +
                                "（可选：${optionsOf(plugins)}）"
                        )
                    }
                }
            } else {
                // 没有配置才轮到 probe（第 4 条）。注意这一支**在排障开关之后** ——
                // 关归一化不影响选型，所以这里不看 normalizationEnabled。
                val best = probeBest(plugins, probeEnv, warn)
                if (best != null) {
                    picked = best.plugin
                    selection = Selection.PROBED
                    info(
                        "设备插件由 probe() 探测选出：${picked.id}（${picked.displayName}），" +
                            "得分 ${best.score}，profile ${picked.profile().id} —— " +
                            "这是探测结果、不是配置（device_profile_id 为空）；" +
                            "要把设备钉死请显式填 device_profile_id"
                    )
                }
                // best == null（全部 ≤ 0）时 picked / selection 保持 default / DEFAULT，
                // 且**刻意不打日志** —— 三条判据写在本函数 KDoc 第 4 条的最后一项。
            }

            return DeviceRuntime(
                plugin = picked,
                // 排障开关关掉归一化时这里必须是 null（对外 normalization_enabled 靠它推），
                // 但 commandProfile 会用 picked.profile() 兜住命令表。
                profile = if (normalizationEnabled) picked.profile() else null,
                selection = selection,
            )
        }

        /** [probeBest] 的返回值：选中谁 + 几分。分数只进日志，不下发。 */
        private class ProbePick(val plugin: DevicePlugin, val score: Int)

        /**
         * 逐个 `probe()` 打分并取最高分。全部 ≤ 0 返回 `null`（由调用方回落 `default`）。
         *
         * 三条纪律都在这里实现（细则与判据见 [resolve] 的 KDoc 第 4 条）：
         * **并列取声明顺序靠前 + WARN**、**抛异常当 0 分 + WARN 且不中断**、
         * **绝不因为打分失败而抛出去**。
         */
        private suspend fun probeBest(
            plugins: List<DevicePlugin>,
            probeEnv: ProbeEnv,
            warn: (String) -> Unit,
        ): ProbePick? {
            var best: DevicePlugin? = null
            var bestScore = 0
            // 与当前最高分并列的那些（不含 best 自己）。只为那条 WARN 的文案服务。
            val tied = mutableListOf<DevicePlugin>()

            for (plugin in plugins) {
                val score = scoreOf(plugin, probeEnv, warn)
                // 0 分与负分都是「不适用」，不参与比较（负分只可能来自实现写错，同样当不适用）。
                if (score <= 0) continue
                when {
                    // 严格大于才换人 —— 这一条就是「并列取声明顺序靠前」的全部实现。
                    score > bestScore -> {
                        best = plugin
                        bestScore = score
                        tied.clear()
                    }
                    score == bestScore -> tied += plugin
                }
            }

            val winner = best ?: return null
            if (tied.isNotEmpty()) {
                val ids = (listOf(winner) + tied).joinToString { it.id }
                warn(
                    "probe 打分并列：$ids 同为 $bestScore 分，" +
                        "按 plugins 声明顺序取 ${winner.id} —— " +
                        "并列意味着 probe 判据不足以区分这些插件，请给它们补上能区分的判据"
                )
            }
            return ProbePick(winner, bestScore)
        }

        /**
         * 单个插件的打分，**异常一律当 0 分**。
         *
         * WARN 文案里带插件 id（值域有界）但**不带异常 message**（值域发散）——
         * 理由见 [resolve] 的 KDoc 第 4 条。
         */
        private suspend fun scoreOf(
            plugin: DevicePlugin,
            probeEnv: ProbeEnv,
            warn: (String) -> Unit,
        ): Int = try {
            plugin.probe(probeEnv)
        } catch (e: CancellationException) {
            // 调用方取消 ≠ 插件打分失败。原样抛出去，不打日志、不当 0 分。
            throw e
        } catch (_: Exception) {
            warn("插件 ${plugin.id} 的 probe() 抛异常，本次按 0 分处理；其余插件照常参与选型")
            0
        }


        /** 回落 WARN 里的「可选」清单：两种可写的 id 都列出来（新口径在前，旧口径在后）。 */
        private fun optionsOf(plugins: List<DevicePlugin>): String =
            plugins.joinToString { plugin ->
                val profileId = plugin.profile().id
                if (profileId == plugin.id) plugin.id else "${plugin.id} / $profileId"
            }
    }
}
