package com.ufi_axis_core.notify

/**
 * 场景 id 的**唯一词表**。
 *
 * 这些字符串同时出现在四个地方：邮件场景勾选（`SmsForwardConfig.scenes`）、
 * `PushNotification.type`（线上契约）、邮件模板的场景色/徽标表（`MailTemplate`）、
 * app 侧 `NotifyScene.sceneId`。以前每处各写一遍字面量，`traffic80` 这种拼写
 * 一旦分叉就会出现「界面上关了但还在发」——那类 bug 从日志里看不出任何异常。
 *
 * app 侧的 `NotifyScene` 枚举是本词表的**镜像**（app 不能依赖 core 模块），
 * 两边必须逐字一致；[ALL] 是 core 侧的完整集合，"新增场景有没有漏登记"由两个单测钉住：
 * `NotifyScenesTest`（每个常量都在 [ALL] 里）与 `MailTemplateSceneCoverageTest`
 * （[ALL] 里每个场景在邮件模板的场景色与场景名两张表里都有条目）。
 */
object NotifyScenes {

    /** 设备短信正文。 */
    const val SMS = "sms"

    /** 从短信里抓到的验证码（与 [SMS] 是两个独立开关：同一条短信抓到码就算验证码）。 */
    const val VERIFICATION = "verification"

    /** 阈值告警（温度 / 电量 / 信号 / 绝对 MB 流量）。 */
    const val ALERT = "alert"

    /** 设备离线 / 上线。 */
    const val CONNECTIVITY = "connectivity"

    /** 套餐限额百分比预警（≠ [ALERT] 里的绝对 MB 阈值），也用于自动关网通报。 */
    const val TRAFFIC_80 = "traffic80"

    /** 下载任务完成 / 失败。 */
    const val DOWNLOAD = "download"

    /** 内网穿透隧道看护放弃。 */
    const val TUNNEL = "tunnel"

    /** 设备事件（WiFi 客户端接入 / 离开）。 */
    const val EVENTS = "events"

    /**
     * 设备电池事件（低电量 / 极低电量 / 充满）。
     *
     * 2026-09-08 新增。此前这类通知借 `forwardSms("SYSTEM", …)` **伪装成短信**进入邮件，
     * 而伪发件人被显式排除在场景判定之外 —— 等于绕过了所有场景开关，用户在邮件设置里
     * 一个勾都没打也会收到低电量邮件，而且界面上找不到关它的地方。
     *
     * **投给除推送之外的所有渠道**（触发源 emit 时带 `exclude = setOf(PushChannel.ID)`）：
     * 邮件、Webhook、本机短信都按各自的场景勾选与级别门槛决定发不发。
     * 2026-09-10 从 `channels = {mail}` 放开 —— 写死邮件时，用户在 Webhook / 本机短信里
     * 勾了「电池状态」永远不触发，那是个假开关。
     *
     * **单独排除推送**是核实过的事实约束，不是保守：app 的 `NotifyService` 只对
     * `sms`/`verification` 与 `download`/`tunnel` 有分流分支，`type = "battery"` 会掉进
     * `maybeNotifyNewAlerts` 被当成一条阈值告警（`id = 0`）并推进告警游标，把随后到达的
     * 真告警吞掉。要让它弹到手机状态栏，得另外补一整套东西 —— core 侧
     * `NotificationConfig.battery_enabled` + `PushChannel.SINGLE_TOPIC_SCENES` 补 `battery`
     * （否则 web 告警列表会多出一条 `alert_records` 里不存在的记录）、app 侧
     * `NotifyScene.BATTERY` + 通知 channel + `NotifyPrefs` 镜像默认值 +
     * `NotificationConfigDto` / `NotificationConfigSync` + 设置页开关行 + `NotifyService`
     * 的 type 分流分支。那是新功能，不是这次放开的一部分。**所以这里不是"忘了接"。**
     */
    const val BATTERY = "battery"

    /**
     * 手动测试信（`POST /api/sms-forward/test`）。
     *
     * 只为**投递记录可分辨**而存在：测试信以前借 [SMS] 的场景 id 发出去，于是
     * `GET /api/sms-forward/history?channel=mail` 里"用户点了几次测试"和"真收到几条短信"
     * 混在同一格里，排查时分不开。
     *
     * **不进用户可勾选的场景集**（邮件通知页那排 chip 不该出现"测试"）：测试信一律带
     * `manual = true`，分发器对 manual 事件跳过 `accepts` 判定，所以它不需要被勾上也能发。
     */
    const val TEST = "test"


    /** core 侧会产出的全部场景 id。新增常量必须同时登记到这里。 */
    val ALL: Set<String> = setOf(
        SMS, VERIFICATION, ALERT, CONNECTIVITY, TRAFFIC_80, DOWNLOAD, TUNNEL, EVENTS, BATTERY, TEST
    )
}

