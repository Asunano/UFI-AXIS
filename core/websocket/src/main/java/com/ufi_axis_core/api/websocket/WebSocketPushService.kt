package com.ufi_axis_core.api.websocket

import com.ufi_axis_core.util.NotificationPushService
import com.ufi_axis_core.util.PushNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 基于 WebSocket 的通知推送服务实现。
 */
class WebSocketPushService(
    private val webSocketManager: WebSocketManager
) : NotificationPushService {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun push(notification: PushNotification) {
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
            
            // 同时也兼容旧的 "alert" 广播，确保旧版客户端仍能收到触发信号
            if (notification.type != "connected") {
                webSocketManager.broadcast("alert", data)
            }
        }
    }
}
