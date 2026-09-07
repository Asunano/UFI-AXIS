// P3（应用内通知）：告警事件总线（置于 core:common，供 app:data / app:ui / app:viewmodel 共享）。
// 作为「系统通知」与「应用内浮层」的统一入口源：NotificationCenter 在识别出新告警后 emit，
// MainActivity 顶层 UfiAlertToastBridge 收集并投给普通 Toast。SharedFlow 无状态、多订阅者安全。
package com.ufi_axis_core.alert

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 应用内浮层携带的告警负载（轻量，避免 core:common 反向依赖 app:data 的 AlertRecord）。
 * NotificationCenter 从 AlertRecord 投影构造；UfiAlertToastBridge 消费。
 */
data class AlertBusItem(
    val id: Long,
    val type: String,
    val level: String,
    val message: String
)

/**
 * 进程内告警事件总线。单例，跨组件解耦。
 *
 * 设计要点：
 * - 使用 [MutableSharedFlow]（replay=0, extraBufferCapacity=64）：新告警即发即弃，不积压历史；
 *   缓冲 64 防止 banner 渲染慢时丢事件（爆发场景）。
 * - 仅承载「新告警」语义事件（即 NotificationCenter 差异检测后认定的 newAlerts），
 *   不承载完整列表 / ack 状态，那些仍走 DashboardModule + 分页 API。
 */
object AlertBus {

    private val _events = MutableSharedFlow<AlertBusItem>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 对外只读流，供 UI 层 collect。 */
    val events: SharedFlow<AlertBusItem> = _events.asSharedFlow()

    /** 发射单条新告警。返回是否成功入队（缓冲满时可能 false，但已配置 DROP_OLDEST 故基本成功）。 */
    fun emit(item: AlertBusItem): Boolean = _events.tryEmit(item)

    /** 批量发射（NotificationCenter 一次识别多条新告警时）。 */
    fun emitAll(items: List<AlertBusItem>) {
        items.forEach { _events.tryEmit(it) }
    }
}
