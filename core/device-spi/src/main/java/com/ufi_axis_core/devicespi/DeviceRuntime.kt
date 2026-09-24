package com.ufi_axis_core.devicespi

import com.ufi_axis_core.deviceschema.DeviceProfile

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
 * 同理，[resolve] 不是 `suspend`：本批不做 probe 选型（见 [Selection.PROBED]），
 * 没有任何 I/O。阶段 5 接 probe 时会需要 `suspend`，届时再改签名
 * （调用点 `ComponentFactory.build()` 本身就是 `suspend`，不构成结构问题）。
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
     * 值域**现在就一次定稳**（含本批不会出现的 [PROBED]）：对外值域二次扩张要两端一起改。
     *
     * @property wire 对外 JSON 里的取值：小写 snake，与 `device_profile` 块现有键的风格一致。
     */
    enum class Selection(val wire: String) {
        /** 配置项显式指定且匹配上了。 */
        CONFIGURED("configured"),

        /**
         * 由 `probe()` 打分选出。
         *
         * **本批（阶段 2 批 B）不会返回这个值** —— probe 选型是**阶段 5 的 5.2**
         * （要先有 `ProbeEnv` 的采集实现，那是 5.1）。
         * 现在就把枚举值定下来是为了让 `selection` 的值域一次定稳、
         * 阶段 2.8 下发到 `/api/diagnose` 之后不必再扩值域（对外值域扩一次就是一次客户端适配）。
         * **不要**为它写死代码，也不要因为「现在用不到」就删掉。
         */
        PROBED("probed"),

        /** 配置项为空，用注册表默认插件（现有部署零配置继续工作）。 */
        DEFAULT("default"),

        /** 配置项填了但两种 id 都匹配不上，已回落默认插件（伴随一条 WARN）。 */
        FALLBACK("fallback"),
    }

    companion object {

        /**
         * 选插件 + 定 profile。**与被删掉的 `ComponentFactory.resolveDeviceProfile()` 逐条等价。**
         *
         * 规则（顺序即优先级）：
         *
         * 1. `normalizationEnabled == false` → [profile] 置 `null` 并打一条 WARN
         *    （文案与原实现逐字一致）。
         *    ⚠ **插件照样选** —— 这是与原实现唯一的结构差异：原实现在这里直接 `return null`，
         *    根本没有「选型」这回事；而传输层与命令表**不能跟着归一化一起关**，
         *    所以现在必须先有插件才谈得上 `profile = null`。[selection] 仍按下面 2~4 条算。
         * 2. `configuredId` 空白 → `default` + [Selection.DEFAULT]，**不打日志**（原实现也不打）。
         * 3. `configuredId` 匹配上 → 该插件 + [Selection.CONFIGURED]。
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
         * 4. 两种 id 都匹配不上 → `default` + [Selection.FALLBACK] + WARN（与原实现同义；
         *    「可选」清单现在**两种 id 都列**，让用户知道该填什么）。
         * 5. [Selection.PROBED] 本批不产生，见它自己的 KDoc。
         *
         * @param plugins 全部候选插件。装配层从 `PluginRegistry.ALL` 传进来 ——
         *   契约层不许知道有哪些插件存在。
         * @param default 兜底插件（`PluginRegistry.DEFAULT`）。
         * @param configuredId 配置项 `device_profile_id` 的**原值**（允许空白）。
         * @param normalizationEnabled 配置项 `field_normalization_enabled`（排障开关）。
         * @param warn 打 WARN 的回调。默认空实现只为**单测**方便：契约层没有 `AppLogger`
         *   （那在 `:core:common`，本模块不依赖它），日志出口由装配层注入。
         * @param info 打 INFO 的回调，同上。
         */
        fun resolve(
            plugins: List<DevicePlugin>,
            default: DevicePlugin,
            configuredId: String,
            normalizationEnabled: Boolean,
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
            }

            return DeviceRuntime(
                plugin = picked,
                // 排障开关关掉归一化时这里必须是 null（对外 normalization_enabled 靠它推），
                // 但 commandProfile 会用 picked.profile() 兜住命令表。
                profile = if (normalizationEnabled) picked.profile() else null,
                selection = selection,
            )
        }

        /** 回落 WARN 里的「可选」清单：两种可写的 id 都列出来（新口径在前，旧口径在后）。 */
        private fun optionsOf(plugins: List<DevicePlugin>): String =
            plugins.joinToString { plugin ->
                val profileId = plugin.profile().id
                if (profileId == plugin.id) plugin.id else "${plugin.id} / $profileId"
            }
    }
}
