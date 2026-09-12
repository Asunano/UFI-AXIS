package com.ufi_axis_core.notify

import com.ufi_axis_core.util.NotificationPushService
import com.ufi_axis_core.util.PushNotification

/**
 * WS 实时推送渠道。把 [NotifyEvent] 转成既有的 [PushNotification] 交给 [NotificationPushService]。
 *
 * **薄适配层**：广播实现（`WebSocketPushService`，含往 `notification` / `alert` 两个 topic
 * 双发以兼容旧客户端）原地不动。
 *
 * ## [PushNotification] 一个字段都不许改
 *
 * 它是**线上契约**：app 的 `:ufi_notify` 守护进程与 web 都在解析这份 JSON。
 * 转换规则因此写死：
 * - `type` → `type`（**不是 `scene`** —— 告警的推送类型是 `temperature` / `connectivity` 这类
 *   具体类型，而 scene 把全部阈值告警归成一格 `alert`。用 scene 当 type，客户端就分不出
 *   是温度还是断网了。缺省情况下 `NotifyEvent.type == scene`，只有告警会显式给两个值）
 * - `level` 转小写（`NotifyLevel.wireName`）
 * - `title` ← [NotifyEvent.pushTitle] **优先**，回落 [NotifyEvent.title]（告警靠这个把线上
 *   固定串「设备告警」保住，同时让邮件主题用有信息量的文案，见 `NotifyEvent.pushTitle`）
 * - `body` → `message`
 * - `extra` 原样带过
 * - `timestamp` 原样带过（不取"此刻" —— 短信通知用的是短信到达时间）
 *
 * [NotifyEvent.meta] 与 [NotifyEvent.highlight] 不进 payload：前者是邮件模板的元信息行，
 * 后者（验证码）已经由触发源写进 `extra["code"]` —— 契约里没有这两个字段，硬塞就是改契约。
 */
class PushChannel(
    private val pushService: NotificationPushService
) : NotifyChannel {

    override val id: String = ID

    /**
     * 不留投递记录：WS 广播是一对多的，没有"逐端投递结果"这种东西
     * （连了 0 个客户端算成功还是失败？），而客户端那侧本来就有自己的 `notify_history`。
     */
    override val recordsHistory: Boolean = false

    /**
     * **不受全局总闸与免打扰约束。**
     *
     * 推送到达只表示"这件事发生了"，**弹不弹是客户端的决定** —— 客户端自己有
     * `NotificationConfig` 总闸 + 分类开关 + 免打扰，判定在 `NotificationCenter.notify`。
     * 在 core 侧再判一遍等于把客户端开关复制一份到设备侧；而且 `master_enabled` 默认 false，
     * 在这里判就意味着"用户没手动打开总开关之前，一条 WS 推送都不发" —— app 的
     * `:ufi_notify` 与 web 的实时告警会全停。
     *
     * 顺带说 web：它根本不弹状态栏，"免打扰"对它不成立，却会跟着一起被掐掉实时告警。
     */
    override val respectsMasterGate: Boolean = false

    /**
     * 没有送达确认：[deliver] 里的广播是 fire-and-forget（实现内部 launch 到自己的 scope），
     * 交出去就返回，拿不到逐端结果。所以本渠道的 `Sent` **不能**用来判断"通报到位"
     * （见 `DeliveryReport.anyConfirmedSent`）。
     */
    override val hasDeliveryConfirmation: Boolean = false

    /** 推送没有需要用户填的配置：WS 服务随 core 一起起来，恒可用。 */
    override fun isConfigured(): Boolean = true

    /**
     * 恒接受所有场景。
     *
     * "这类通知要不要弹"的真源在**客户端**（`NotificationConfig` 的分类开关 +
     * `NotificationCenter.notify` 的闸门）：core 推送到达即代表"有这件事发生了"，
     * 弹不弹由收到的那一端决定。在这里再加一层场景过滤，等于把客户端开关复制一份到设备侧。
     */
    override fun accepts(scene: String): Boolean = true

    override suspend fun deliver(event: NotifyEvent): DeliveryAttempt {
        pushService.push(
            PushNotification(
                type = event.type,
                level = event.level.wireName,
                title = event.pushTitle ?: event.title,
                message = event.body,
                timestamp = event.timestamp,
                extra = event.extra
            ),
            mirrorToAlertTopic = event.scene !in SINGLE_TOPIC_SCENES
        )
        // push 是 fire-and-forget（实现内部 launch 到自己的 scope），交出去就算投递完成 ——
        // 这里没有"送达确认"可等，所以也不存在可重试的失败。
        // 不带诊断：推送没有 /test 端点，也没有"状态码"这种东西可回。
        return DeliveryAttempt(DeliveryOutcome.Sent)
    }



    companion object {
        /** 渠道 id。`NotifyEvent.channels` 限定集合与投递记录的 `channel` 列都用它。 */
        const val ID = "push"

        /**
         * **只发 `notification`、不镜像到 `alert`** 的场景。
         *
         * 这个分叉是**历史线上兼容，不是设计**。
         *
         * 迁移前这两个场景的推送由 `DataScheduler` 手写 `broadcast("notification", …)`
         * **单发**，而 `WebSocketPushService` 对其它来源是 `notification` + `alert` 双发
         * （旧客户端只订 `alert`）。收进分发器之后如果一视同仁地双发，web 订阅 `alert` 的
         * 告警列表就会凭空多出一堆短信 —— 那是可见的功能回归，不是兼容。
         * 反过来把别人的双发去掉也不行：旧客户端会收不到。所以按**来源**保持原样。
         *
         * 顺带说明另一半包袱：`WsChannel.UI_TOPICS`（`Enums.kt:218`）刻意把 `notification`
         * 剔除掉，正是因为双发会让同时订两个频道的前端收到两次。**"多发"与"少订"是同一件事的两半。**
         * 收敛方向：旧客户端淘汰后，`WebSocketPushService` 只发 `notification`、
         * `UI_TOPICS` 把它加回来，这个集合随之删除。
         */
        private val SINGLE_TOPIC_SCENES = setOf(NotifyScenes.SMS, NotifyScenes.VERIFICATION)
    }
}

