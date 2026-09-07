package com.ufi_axis_core.util

import kotlinx.serialization.Serializable

/**
 * 后端到前端的推送通知负载模型。
 */
@Serializable
data class PushNotification(
    val type: String,
    val level: String,
    val title: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val extra: Map<String, String> = emptyMap()
)

/**
 * 核心后端通知推送服务接口（公共组件）。
 * 负责将核心事件实时同步到连接的手机端/Web端前端。
 */
interface NotificationPushService {
    /**
     * 发送一条实时通知。
     * @param notification 通知内容负载
     */
    fun push(notification: PushNotification)

    /**
     * 快捷发送告警类通知。
     */
    fun pushAlert(type: String, level: String, message: String, extra: Map<String, String> = emptyMap()) {
        push(PushNotification(
            type = type,
            level = level,
            title = "设备告警",
            message = message,
            extra = extra
        ))
    }
}
