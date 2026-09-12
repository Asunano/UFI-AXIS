package com.ufi_axis.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent

/**
 * 通知场景注册表（通知模块化核心，2026-08-10 半重构）。
 *
 * 目的：新增通知场景只需在 [NotifyScene] 加一个枚举项 + 调用处一行
 * `notificationCenter.notify(scene, title, message, ...)`，无需重复写
 * 开关检查/去重/限频/免打扰/channel 构建等样板代码。
 *
 * 每个场景声明：
 * - [channelId]：归属 channel（见 [NotificationCenter.Companion.CHANNEL_*]）
 * - [defaultEnabled]：开关默认值（用户可在设置页关闭）
 * - [importance]：channel 重要性（决定响铃/横幅/静默，需与 channel 创建一致）
 * - [dndBreakthrough]：免打扰时段（起止小时用户可配，默认 23:00-07:00）是否仍可突破（仅 critical 类）
 * - [rateLimitMinutes]：同场景限频窗口（分钟，0=不限）
 *
 * **限频取值原则**（2026-08-25 修复：此前 `isRateLimited` 只读不写，限频实际从未生效）：
 * 已有更精确去重手段的场景一律设 0，避免"第二条不同内容的通知被静默丢弃"：
 * - ALERT：core 侧边沿触发（同级别不重复推）+ 游标 + 10min 签名 TTL；
 * - SMS / VERIFICATION_CODE / DOWNLOAD / TUNNEL / CONNECTIVITY：**判定与「只提醒一次」
 *   的记账全部在 core**，推送到达即代表该提醒，app 只渲染，不需要也不该再限频。
 * 目前**没有场景**依赖限频窗口 —— 新增场景前先想清楚有没有更精确的去重键。
 *
 * 使用示例：
 * ```kotlin
 * val nc = NotificationCenter(context)
 * // 一行调用，内部自动处理开关/去重(可选)/限频/免打扰/channel
 * nc.notify(
 *     scene = NotifyScene.SMS,
 *     title = "新短信",
 *     message = "138****1234：验证码 123456",
 *     notificationId = 9002,
 *     deDupKey = "last_sms_${contactId}", deDupValue = msgId.toString()
 * )
 * ```
 */
enum class NotifyScene(
    val sceneId: String,
    val channelId: String,
    val defaultEnabled: Boolean,
    val importance: Int,
    val dndBreakthrough: Boolean = false,
    val rateLimitMinutes: Int = 5
) {
    /**
     * 温度/电量/流量/信号阈值告警（最高优先级，可突破免打扰）。
     *
     * `defaultEnabled = false`：本场景的开关 key 是 `alert_notification_enabled`
     * （同时也是告警族的上层总闸），而 app 侧所有实际闸门（NotifyService /
     * AppPreferences / BackgroundGuardWorker / NotificationCenter 内部方法）读这个 key 时
     * 默认值都是 false，core 的 `NotificationConfig.alert_enabled` 也默认 false。
     * 这里若填 true，同一个 key 在"未初始化"状态下会因读取路径不同给出相反答案。
     */
    ALERT(
        sceneId = "alert",
        channelId = NotificationCenter.CHANNEL_ALERTS,
        defaultEnabled = false,
        importance = NotificationManager.IMPORTANCE_MAX,
        dndBreakthrough = true,
        rateLimitMinutes = 0
    ),

    /**
     * 设备离线/上线。
     *
     * 2026-08-29 从 ALERT 拆出独立开关 `connectivity_notification_enabled`
     * （[NotificationCenter.KEY_CONNECTIVITY_NOTIF]）。
     *
     * 2026-09-07：`defaultEnabled` 由 true 改为 **false** —— 用户明确要求告警与日常通知
     * 一律不默认开启、需手动打开。本字段同时是 `sceneEnabledKey` 的读取默认值，必须与
     * core `NotificationConfig.connectivity_enabled`、`NotificationConfigDto`、
     * `NotificationConfigSync.readLocal` 及 DailyNotifyScreen 的 `defaultEnabled` 逐字一致。
     * 仍受总闸 `alert_notification_enabled` 约束。
     *
     * `rateLimitMinutes = 0`：限频按场景记时间戳，离线/上线共享同一窗口 ——
     * 离线报完 5 分钟内恢复的话上线通知会被吞掉。判定与「只报一次」的记账都在 core，
     * app 收到推送即渲染。
     */
    CONNECTIVITY(
        sceneId = "connectivity",
        channelId = NotificationCenter.CHANNEL_CONNECTIVITY,
        defaultEnabled = false,
        importance = NotificationManager.IMPORTANCE_HIGH,
        rateLimitMinutes = 0
    ),

    /** 新短信到达（静默，不响铃） */
    SMS(
        sceneId = "sms",
        channelId = NotificationCenter.CHANNEL_SMS,
        defaultEnabled = false,
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        rateLimitMinutes = 0
    ),

    /** 验证码提取（依附短信 channel） */
    VERIFICATION_CODE(
        sceneId = "verification",
        channelId = NotificationCenter.CHANNEL_SMS,
        defaultEnabled = false,
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        rateLimitMinutes = 0
    ),

    /** 下载完成/失败 */
    DOWNLOAD(
        sceneId = "download",
        channelId = NotificationCenter.CHANNEL_DOWNLOADS,
        defaultEnabled = false,
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        rateLimitMinutes = 0
    ),

    /** 流量 80% 限额预警（复用告警 channel，DEFAULT 不响铃） */
    TRAFFIC_80(
        sceneId = "traffic80",
        channelId = NotificationCenter.CHANNEL_ALERTS,
        defaultEnabled = false,
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        rateLimitMinutes = 0
    ),

    /** 设备事件（WiFi 客户端上下线，默认关闭） */
    DEVICE_EVENTS(
        sceneId = "events",
        channelId = NotificationCenter.CHANNEL_EVENTS,
        defaultEnabled = false,
        importance = NotificationManager.IMPORTANCE_LOW,
        rateLimitMinutes = 0
    ),

    /**
     * 内网穿透隧道启动失败 / 意外断开。
     *
     * **要不要推**的真源在 core 的 `tunnel_notify_on_failure`（`PUT /api/tunnel/settings`），
     * 闸门在 `TunnelManager.notifyGiveUp` —— 推送出不来就等于关；隧道设置页的开关直接写那个字段。
     *
     * **本机要不要弹**：2026-09-08 起有了自己的分类键 `tunnel_notification_enabled`
     * （`sceneEnabledKey(TUNNEL)`）。此前它借用 `alert_notification_enabled`，
     * 于是"只关隧道提醒、留阈值告警"做不到。全局总闸 `notification_master_enabled`
     * 由 `notify()` 统一判定，不需要本场景操心。
     *
     * `rateLimitMinutes = 0`：限频是**按场景**记时间戳的，一开就变成"同一时刻只有第一条隧道能提醒"，
     * 后面那条会被静默丢掉。去重靠 core 侧的"重连到达上限才推一次"，不需要限频。
     */
    TUNNEL(
        sceneId = "tunnel",
        channelId = NotificationCenter.CHANNEL_EVENTS,
        // 默认 false：`notify()` 用它当 sceneEnabledKey 的默认值，写 true 等于"用户从未碰过
        // 隧道通知开关时它自己就是开的"——与其余 7 个分类键（default=false）不一致。
        defaultEnabled = false,
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        rateLimitMinutes = 0
    )
}

/**
 * 通知模块化的统一载荷：一次 notify 调用的全部参数。
 * 语义化字段避免散落的魔法参数。
 */
data class NotifyPayload(
    val title: String,
    val message: String,
    /** 展开大文本（BigTextStyle 折叠态展开内容）；null=复用 message */
    val bigText: String? = null,
    /** 通知 id（唯一；同 id 重复 notify 会覆盖旧通知） */
    val notificationId: Int,
    /** 去重 key（写入 prefs；同 key+value 已存在则跳过）。null=不去重 */
    val deDupKey: String? = null,
    /** 去重 value */
    val deDupValue: String? = null,
    /** 点击跳转 PendingIntent；null=打开 App 主界面 */
    val onTap: PendingIntent? = null,
    /** 通知优先级（NotificationCompat.PRIORITY_*）；null=按场景 importance 派生 */
    val priority: Int? = null,
    /** 强制静默（不响铃不震动）；免打扰命中时自动置 true */
    val silent: Boolean = false,
    /** 分组 key（通知栏按组折叠，如按告警 type） */
    val groupKey: String? = null,
    /** 通知类别（NotificationCompat.CATEGORY_*）：影响免打扰白名单与锁屏展示 */
    val category: String? = null,
    /** 小图标资源 id；null=系统默认图标 */
    val smallIconRes: Int = android.R.drawable.stat_notify_error,
    /** 自动清除（点击后消失） */
    val autoCancel: Boolean = true
)
