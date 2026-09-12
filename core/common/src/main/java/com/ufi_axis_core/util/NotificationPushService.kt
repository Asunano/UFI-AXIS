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
     *
     * @param notification 通知内容负载
     * @param mirrorToAlertTopic 是否把同一份 payload 再发一遍到 `alert` 频道。
     *
     * **默认 true = 保持既有线上行为**（见 `WebSocketPushService.push` 的注释：
     * 那是给旧客户端的兼容镜像，不是设计）。只有"本来就没有镜像过"的来源才传 false ——
     * 目前是 `PushChannel` 里的 sms / verification 两个场景：它们此前由
     * `DataScheduler` 手写 `broadcast("notification", …)` **单发**，
     * 一旦跟着镜像到 `alert`，web 的告警列表就会把短信列成告警（可见的功能回归）。
     */
    fun push(notification: PushNotification, mirrorToAlertTopic: Boolean = true)

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

