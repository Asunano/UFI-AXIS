package com.ufi_axis_core.deviceschema.profile

import com.ufi_axis_core.deviceschema.DeviceProfile

/**
 * profile 注册表 —— **新增一类设备后台时唯一需要改的公共代码**（计划书 3.2）。
 *
 * 加一个 profile 的完整步骤：
 * 1. 新建 `XxxProfile.kt`（实现 [DeviceProfile]，参考 [ZteGoformProfile] 顶部的示例注释）；
 * 2. 把它加进 [ALL]；
 * 3. 配置里把 `deviceProfileId` 设成它的 [DeviceProfile.id]。
 * route / web / app 一行都不用改。
 *
 * ## 为什么不做自动探测
 *
 * 探测要读设备版本字段（`cr_version` / `wa_inner_version`），而**读这些字段本身就需要一个
 * profile**（字段名是设备侧的）—— 鸡生蛋。所以选型策略定为「默认 ZTE + 配置可覆盖」：
 * 默认值让现有部署零配置继续工作，换设备时改一个配置项。
 *
 * 这里**不打日志**：本模块是纯 JVM，没有 `AppLogger`。[byId] 认不出就返回 null，
 * 由调用方决定兜底并打 WARN（计划书 10.2：型号不认识不能导致整个不工作）。
 */
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
