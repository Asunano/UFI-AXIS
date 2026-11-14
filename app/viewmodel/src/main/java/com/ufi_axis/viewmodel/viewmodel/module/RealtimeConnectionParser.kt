package com.ufi_axis.viewmodel.module

import com.ufi_axis.data.model.WebSocketMessage
import com.ufi_axis_core.contract.DeviceFields
import com.ufi_axis_core.contract.WsChannel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 从一条 WS 消息里解出实时连接态（`ppp_status`），2026-11-07。
 *
 * core 的 `DataScheduler.collectSignal()` 在采集 signal 的同一次 goform 查询里已经读到
 * `ppp_status`，并通过 [WsChannel.CONNECTION] 频道单独推送。这里只做一件事：
 * 判断是不是这条频道、取出非空的 `ppp_status`。
 *
 * ## 为什么和 signal 分开推送
 * `signal` 的 `SignalInfo` 模型是"信号读数"，`ppp_status` 是"链路态"，语义不同；
 * 把它塞进 `SignalInfo` 会让所有消费 signal 的地方都多背一个连接态字段，也会让
 * core 的 SIGNAL 字段组和 CONNECTION 字段组混在一起。单独一条小频道最干净。
 *
 * ## 空值语义
 * 返回 null = 不是 `connection` 频道 / 载荷不是对象 / `ppp_status` 缺失或空串。
 * 空串在 core 侧表示"这轮没读到"（不是"断开"），调用方必须**保留上一帧**，
 * 不能把空串当成 disconnected 写进状态。
 */
internal fun pppStatusFromWsMessage(message: WebSocketMessage): String? {
    if (message.type != WsChannel.CONNECTION) return null
    val payload = message.data as? JsonObject ?: return null
    return payload[DeviceFields.Connection.PPP_STATUS]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
}
