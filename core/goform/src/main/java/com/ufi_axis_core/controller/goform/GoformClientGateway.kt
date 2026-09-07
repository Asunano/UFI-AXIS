package com.ufi_axis_core.controller.goform

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * [GoformGateway] 的适配器实现（F9 防腐层）。
 *
 * 当前直接委托给具体 [GoformClient]（`by client` 实现接口全部方法，零行为差异）。
 * 该适配器的存在意义在于：为上层提供稳定的接口边界，未来若需替换为 Mock / 另一协议
 * 实现，仅需在此处切换被委托对象，调用方代码不变。
 *
 * 使用：在 ComponentGraph / ComponentFactory 中构造 [GoformClient] 后包一层本适配器，
 * 以 [GoformGateway] 类型向上层（RouteContext 等）暴露。
 */
class GoformClientGateway(
    private val client: GoformClient
) : GoformGateway by client
