package com.ufi_axis.viewmodel.module

import com.ufi_axis.data.model.SignalInfo
import com.ufi_axis.data.model.WebSocketMessage
import com.ufi_axis.util.AppJson
import com.ufi_axis.util.DebugLog
import com.ufi_axis_core.contract.WsChannel
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 从一条 WS 消息里解出信号快照（2026-11-07）。
 *
 * ## 为什么单独放一个文件
 * 仪表盘 hero 卡与网络页 hero 卡读的是**同一条** `signal` 频道。两边各自写一份
 * `when (type) { "signal" -> decode… }` 的话，频道名、反序列化异常处理、坏帧语义
 * 会慢慢长成两套 —— 而这正是上一轮「仪表盘正常、网络页一直无服务」的同一类问题。
 * 现在两处都走本函数：**解码只有一份**，各自只保留自己的状态写入。
 *
 * 注意：这里只共享**解码**，不共享 State。两个模块各自持有一份 `signalInfo` 是有意的：
 * - 网络页与仪表盘的前台/轮询门控不同（`isUfiPageForeground` 是页面级）；
 * - `DashboardState` 还会被 Room 缓存 / 监控页复用，`NetworkState` 只服务网络域；
 * - 让 Dashboard 直接读 `NetworkState` 会把「仪表盘轮询在离屏时停止」这个时序问题
 *   带进网络页 —— 用户切到网络页时，仪表盘的 10s 轮询已经停了，读到的是旧值。
 * 共享解码、独立状态，是这两者之间复杂度最低的切法。
 *
 * ## 判据
 * - 只接受 contract 的 [WsChannel.SIGNAL]（不写裸 `"signal"` 字面量）；
 * - 没有 `data` 或载荷解析失败 → 返回 null，只记日志、不写状态：一条坏帧不该把上一帧
 *   正确的信号读数清掉。
 *
 * 抽成顶层纯函数而不是塞进某个 Module 的协程 lambda，是为了让「哪些帧该更新 hero 卡」
 * 这条判据能被单测直接覆盖 —— 不需要 Robolectric / Android Context。
 *
 * @return null = 不是 `signal` 频道 / 没有 data / 载荷解析失败。
 */
internal fun signalFromWsMessage(message: WebSocketMessage): SignalInfo? {
    if (message.type != WsChannel.SIGNAL) return null
    val payload = message.data ?: return null
    return try {
        AppJson.decodeFromJsonElement<SignalInfo>(payload)
    } catch (e: Exception) {
        DebugLog.parseError("WS", "signal", payload.toString(), e)
        null
    }
}
