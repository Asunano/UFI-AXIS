package com.ufi_axis.ui.components.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.ufi_axis_core.contract.NetworkMode
import kotlinx.serialization.json.JsonPrimitive

/**
 * 动作参数类型
 */
enum class ParamType {
    BOOLEAN, INT, STRING
}

/**
 * 动作参数定义
 */
data class ParamDef(
    val key: String,
    val label: String,
    val type: ParamType,
    val default: JsonPrimitive,
    val options: List<Pair<String, String>>? = null  // 可选值（用于 ChipSelector）
)

/**
 * 动作定义
 */
data class ActionDef(
    val type: String,
    val category: String,       // "network" / "system" / "device" / "advanced"
    val name: String,
    val icon: ImageVector,
    val params: List<ParamDef>  // 参数定义（用于动态渲染表单）
)

/**
 * 前端动作注册表
 *
 * 定义所有已知的动作类型元数据（图标、名称、参数定义）。
 * 后端 ActionExecutorImpl.VALID_ACTION_TYPES 必须与此处保持一致。
 */
object ActionRegistry {
    val actions = listOf(
        // 网络类
        ActionDef("data_toggle", "network", "移动数据", Icons.Default.DataUsage,
            listOf(ParamDef("enabled", "状态", ParamType.BOOLEAN, JsonPrimitive(true)))),
        ActionDef("wifi_toggle", "network", "WiFi 热点", Icons.Default.Wifi,
            listOf(ParamDef("enabled", "状态", ParamType.BOOLEAN, JsonPrimitive(true)))),
        ActionDef("airplane_toggle", "network", "飞行模式", Icons.Default.Flight,
            listOf(ParamDef("enabled", "状态", ParamType.BOOLEAN, JsonPrimitive(true)))),
        ActionDef("roaming_toggle", "network", "数据漫游", Icons.Default.Public,
            listOf(ParamDef("enabled", "状态", ParamType.BOOLEAN, JsonPrimitive(true)))),
        ActionDef("network_mode", "network", "网络模式", Icons.Default.CellWifi,
            listOf(ParamDef("mode", "模式", ParamType.STRING, JsonPrimitive(NetworkMode.LTE_AND_5G),
                // T15：取值必须是 contract 的**别名集**（core 侧 NetworkMode.toBearer 再映射成设备值）；
                // 此前 ActionExecutorImpl 不做映射，选「仅 5G」下发 5G_ONLY 被设备忽略，静默失败。
                options = listOf(
                    NetworkMode.AUTO to "自动",
                    NetworkMode.ONLY_5G to "仅 5G",
                    NetworkMode.LTE_AND_5G to "5G 优先",
                    NetworkMode.ONLY_LTE to "仅 4G",
                    NetworkMode.WCDMA_AND_LTE to "4G / 3G",
                    NetworkMode.ONLY_WCDMA to "仅 3G"
                )))),

        // 系统类
        ActionDef("reboot", "system", "重启设备", Icons.Default.RestartAlt, emptyList()),
        ActionDef("shutdown", "system", "关机", Icons.Default.PowerSettingsNew, emptyList()),

        // 设备类
        ActionDef("led_toggle", "device", "指示灯", Icons.Default.Lightbulb,
            listOf(ParamDef("enabled", "状态", ParamType.BOOLEAN, JsonPrimitive(true)))),
        ActionDef("performance_mode", "device", "性能模式", Icons.Default.Speed,
            listOf(ParamDef("mode", "模式", ParamType.INT, JsonPrimitive(0),
                options = listOf("0" to "均衡", "1" to "性能")))),

        // 高级
        ActionDef("custom_shell", "advanced", "自定义命令", Icons.Default.Terminal,
            listOf(ParamDef("command", "Shell 命令", ParamType.STRING, JsonPrimitive(""))))
    )

    val categories = listOf(
        "network" to "网络",
        "system" to "系统",
        "device" to "设备",
        "advanced" to "高级"
    )

    fun getByType(type: String): ActionDef? = actions.find { it.type == type }
    fun getByCategory(category: String): List<ActionDef> = actions.filter { it.category == category }
}
