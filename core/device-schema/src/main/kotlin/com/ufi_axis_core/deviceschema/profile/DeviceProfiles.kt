package com.ufi_axis_core.deviceschema.profile

import com.ufi_axis_core.deviceschema.DeviceProfile

/**
 * profile 注册表 —— **已废弃**，新代码请用 `PluginRegistry` / `DeviceRuntime`（`:core:device-plugins`）。
 *
 * 加一个 profile 的完整步骤（历史口径，现在改为「加一个插件」）：
 * 1. 新建 `XxxProfile.kt`（实现 [DeviceProfile]，参考 [ZteGoformProfile] 顶部的示例注释）；
 * 2. 把它加进 [ALL]；
 * 3. 配置里把 `deviceProfileId` 设成它的 [DeviceProfile.id]。
 *
 * ## 为什么**保持原实现**、不委托 `PluginRegistry`
 *
 * 计划书 §6 的 2.6 原方案写的是「让 [DEFAULT] 委托 `PluginRegistry`」—— **这条行不通**：
 * 本类在 `:core:device-schema`（纯 JVM、**被所有人依赖**），而 `PluginRegistry` 在
 * `:core:device-plugins`（它依赖 device-schema）。委托就是反向依赖，直接成环、编译不过。
 * 所以这里只加 `@Deprecated`，实现一行不动。
 *
 * ## 什么时候可以真正删掉它
 *
 * 等 `SignalCollector` / `DataScheduler` 那两处 `= ZteGoformProfile` 默认参数也改成
 * 从 `DeviceRuntime` 取（计划书 §6 的 2.6），且本模块自己的测试不再需要拿注册表当断言对象之后
 * —— 那时全仓对「设备默认值」的引用只剩装配层一处，删掉本类才不会改变任何行为。
 * 在那之前删它 = 把默认值搬进各调用点，等于回到「选型逻辑散在 N 个签名里」。
 *
 * ## 为什么不做自动探测
 *
 * 探测要读设备版本字段（`cr_version` / `wa_inner_version`），而**读这些字段本身就需要一个
 * profile**（字段名是设备侧的）—— 鸡生蛋。所以选型策略定为「默认 ZTE + 配置可覆盖」：
 * 默认值让现有部署零配置继续工作，换设备时改一个配置项。
 * （插件侧的绕法是 `ProbeEnv.goformLdReachable`：`LD` 免登录、免 profile。）
 *
 * 这里**不打日志**：本模块是纯 JVM，没有 `AppLogger`。[byId] 认不出就返回 null，
 * 由调用方决定兜底并打 WARN（计划书 10.2：型号不认识不能导致整个不工作）。
 */
@Deprecated(
    "改用 PluginRegistry / DeviceRuntime（:core:device-plugins）",
    level = DeprecationLevel.WARNING,
)
object DeviceProfiles {

    /** 未指定 / 认不出时的兜底。 */
    val DEFAULT: DeviceProfile = ZteGoformProfile

    /** 全部已实现的 profile。新增 profile 时加在这里。 */
    val ALL: List<DeviceProfile> = listOf(ZteGoformProfile)

    /** 按 [DeviceProfile.id] 查找；空串或未登记的 id 返回 null。 */
    fun byId(id: String?): DeviceProfile? {
        val key = id?.trim().orEmpty()
        if (key.isEmpty()) return null
        return ALL.firstOrNull { it.id.equals(key, ignoreCase = true) }
    }
}
