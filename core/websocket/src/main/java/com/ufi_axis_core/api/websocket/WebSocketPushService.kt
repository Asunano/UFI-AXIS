package com.ufi_axis_core.api.websocket

import com.ufi_axis_core.util.NotificationPushService
import com.ufi_axis_core.util.PushNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 基于 WebSocket 的通知推送服务实现。
 */
class WebSocketPushService(
    private val webSocketManager: WebSocketManager
) : NotificationPushService {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun push(notification: PushNotification, mirrorToAlertTopic: Boolean) {
        scope.launch {
            // 将 PushNotification 转换为 Map 以适配 WebSocketManager.broadcast
            val data = mapOf(
                "type" to notification.type,
                "level" to notification.level,
                "title" to notification.title,
                "message" to notification.message,
                "timestamp" to notification.timestamp,
                "extra" to notification.extra
            )
            // 广播类型固定为 "notification"，方便前端统一监听
            webSocketManager.broadcast("notification", data)

            // ── 往 `alert` 再发一遍：**历史线上兼容，不是设计** ──
            //
            // 旧客户端只订 `alert`，所以同一份 payload 双发。代价是订两个频道的前端会收到两次，
            // 因此 `WsChannel.UI_TOPICS`（`Enums.kt:218`）刻意把 `notification` 剔除掉 ——
            // UI 只认 `alert`，`:ufi_notify` 守护进程才订 `notification`。
            // 也就是说这个"多发"和那个"少订"是同一件历史包袱的两半，改一边必须同时改另一边。
            //
            // 收敛方向：旧客户端淘汰之后，这里只发 `notification`、`UI_TOPICS` 把它加回来，
            // 双方都不再需要为镜像做特殊处理。
            //
            // `mirrorToAlertTopic = false` 是给"本来就没镜像过"的来源用的（当前只有
            // `PushChannel` 的 sms / verification）：给它们补上镜像不是兼容，是让 web
            // 的告警列表凭空多出一堆短信。
            if (mirrorToAlertTopic && notification.type != "connected") {
                webSocketManager.broadcast("alert", data)
            }
        }
    }


    /**
     * 释放推送协程作用域。
     *
     * 早期实现只建 scope 不销毁：BackendService 停掉之后，最后一批 push 里的
     * broadcast 仍在 Dispatchers.Default 上跑，往已经关闭的 WebSocketManager 里灌数据，
     * 服务重启后还会看到上一条命的告警重放。停服路径必须显式调一次。
     */
    fun shutdown() {
        scope.cancel()
    }
}
