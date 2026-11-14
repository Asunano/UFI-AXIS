package com.ufi_axis_core.deviceplugins

import com.ufi_axis_core.deviceplugins.zte.f50.ZteF50Plugin
import com.ufi_axis_core.devicespi.DevicePlugin

/**
 * 插件注册表 —— **新增一台设备后唯一需要改的公共代码**（加一行进 [ALL]）。
 *
 * ## 编译期注册，不做运行时动态加载
 *
 * [ALL] 是一个写死的 `listOf(...)`。**刻意不做** DexClassLoader / 反射扫 dex /
 * ServiceLoader 那一套（计划书 §1 已经否掉）：本仓是单 APK 交付，
 * 「运行时装插件」带来的是签名校验、混淆保留规则、R8 裁剪、崩溃归因全套代价，
 * 换来的只是一个我们不需要的能力 —— 加一台设备本来就要改代码、要重新出包。
 *
 * 编译期注册还有一条实打实的好处：R8 能看见全部插件的引用关系，
 * 用不到的设备知识该被裁掉就裁掉，不需要写一堆 `-keep`。
 *
 * ## 为什么要有 [DEFAULT]
 *
 * **认不出设备不能导致整个不工作**（计划书 10.2）。配置里指定的 id 打错了、
 * 或者 `probe()` 全部返回 0（比如设备后台暂时不可达）时，选型必须落到一个能用的插件上
 * 并打 WARN，而不是让整个后端起不来。这与 `DeviceProfiles.DEFAULT` 是同一条纪律。
 */
object PluginRegistry {

    /** 全部已实现的插件。新增设备时加在这里。 */
    val ALL: List<DevicePlugin> = listOf(ZteF50Plugin)

    /** 未指定 / 认不出 / 全部 `probe()` 为 0 时的兜底。 */
    val DEFAULT: DevicePlugin = ZteF50Plugin

    /** 按 [DevicePlugin.id] 查找；未登记返回 null（由调用方决定兜底并打 WARN）。 */
    fun byId(id: String): DevicePlugin? = ALL.firstOrNull { it.id == id }
}
